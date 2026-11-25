package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.service.AgentService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.UsageQuotaService;
import com.wenqu.ai.util.ClientIp;
import com.wenqu.ai.util.RequestUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 问答开轮之前「能不能问、问进哪个会话、用哪个智能体」这一段准入逻辑。
 *
 * <p><b>为什么单独一个类</b>：这些事原本都挤在 {@code ChatRunController#chat} 一个方法里，
 * 导致该控制器为了一个端点就依赖限流、配额、模型登记、智能体、会话五个服务；
 * 而同一个控制器的其余九个端点（停止、插话、重连、文件改动）一个都不用。
 * 抽出来以后控制器只依赖「本轮的通道与生死」，准入规则也只有一处可改。
 *
 * <p>全部为同步段校验，失败抛 {@link BizException}（fail-loud）；
 * 唯一的写操作是「会话不存在时按当前用户补建」与「会话级模型覆盖落库」，两者都是幂等的。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class ChatTurnAdmission {

    private final SessionService sessionService;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuota;
    private final ModelRegistryService modelRegistryService;
    private final AgentService agentService;

    /**
     * 入口准入：按用户限频（SYSTEM 落到 IP 维度）→ 配额预检 → 单条字数上限。
     *
     * @return trim 之后的提问正文
     */
    public String admit(String question, HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        rateLimitService.checkRateLimit("chat", RequestUser.SYSTEM.equals(userId)
                ? "ip:" + ClientIp.of(httpRequest) : "user:" + userId);
        // 配额闸在入口先判一次：模型出口那道闸是硬保证，这一道是为了让用户当场看到
        // 「哪条规则、用了多少、何时恢复」，而不是看一条转了很久才失败的流
        // （真正的强制仍在出口，两处共用 UsageQuotaService 一份判据）
        usageQuota.assertWithinBudget(RequestUser.SYSTEM.equals(userId) ? null : userId, "chat");

        String text = question.trim();
        // 单条字数上限固定 20 万字：集成方每轮要把当前画布 schema 带上，一张大表就到数万至十万字量级，
        // 上限定在 20 万字以容纳这种整份携带，同时挡住会吃爆内存的超大请求。
        int maxQuestionChars = Math.max(200, ConfigDefaults.CHAT_MAX_QUESTION_CHARS);
        if (text.length() > maxQuestionChars) {
            throw new BizException("单条内容过长（最多 " + maxQuestionChars + " 字）");
        }
        return text;
    }

    /** 会话 ID 归一：未带则新建；带了则校验归属，不存在则按当前用户补建记录。 */
    public String resolveSessionId(String requested, String userId) {
        if (requested == null || requested.isEmpty()) {
            return sessionService.createSession(userId);
        }
        try {
            sessionService.assertOwned(requested, userId);
            return requested;
        } catch (BizException e) {
            if (e.getCode() == 404) return sessionService.ensureSession(requested, userId);
            throw e;
        }
    }

    /**
     * 模型与智能体的可用性校验（同步段 fail-loud）。
     *
     * <p>模型：普通用户只能用自己可用的供应商（平台级 + 自己登记的个人级）——
     * 放在发起问答之前拦住，否则引用他人个人级供应商时会拿对方的 Key 跑通，事后无从发现。
     * <p>智能体：显式选择的要过停用校验，不能静默回落全局配置（用户会以为还是它在答）；
     * 未选择则用默认智能体作答（与 DeepController.start 同一口径）。
     *
     * @return 本轮最终生效的智能体 ID（可能为 null = 按平台默认档降级）
     */
    public String admitModelAndAgent(ChatRequest request, String userId, String sessionId) {
        modelRegistryService.assertUsable(request.getModel(), userId, RequestUser.role());
        persistModelOverride(request, userId, sessionId);

        String agentId = request.getAgentId() == null || request.getAgentId().isBlank()
                ? null : request.getAgentId().trim();
        if (agentId != null) {
            var picked = agentService.findReadable(agentId);
            if (picked != null && AgentService.isDisabled(picked)) {
                throw new BizException("智能体「" + picked.getName() + "」已停用，请切换其它智能体后再提问");
            }
            return agentId;
        }
        var def = agentService.defaultAgent();
        return def != null && agentService.resolveForTurn(def.getId(), null) != null ? def.getId() : null;
    }

    /**
     * 会话级模型覆盖落库（聊天页手动切换即存，刷新/换端后选择不丢）。
     * 只有请求显式携带才写；重新生成/自动重试带的还是这一轮原来那个模型，
     * 那不是「用户改了一次选择」——写下去会覆盖掉别的端刚选的那一份。
     */
    private void persistModelOverride(ChatRequest request, String userId, String sessionId) {
        if (request.getModel() == null || request.getModel().isBlank()) return;
        String modelRef = request.getModel().trim();
        // 已停用的引用不再写进会话：界面只列可用项，走到这里的只有停留在旧选择上的页签与重发，
        // 让它落库就是「A 端切成 X、B 端一句重发又盖回停用模型」。
        String issue = modelRegistryService.issueMessage(modelRef, userId, RequestUser.role());
        if (!issue.isEmpty()) {
            throw new BizException("模型" + issue + "，请重新选择");
        }
        if (!request.isRegenerate()) {
            sessionService.updateModel(userId, sessionId, modelRef);
        }
    }
}