package com.wisesoft.ai.service;

import com.wisesoft.ai.model.Agent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 智能体自动派遣（对话页「自动派遣」模式）：每轮消息由当轮生效模型按候选智能体的
 * 名称+描述挑选最合适的角色（对齐市面 Coze 多 Agent 模式 / OpenAI Agents SDK triage 的路由设计）。
 * <p>
 * 路由要点（与各平台共识一致）：
 * <ul>
 *   <li><b>每条消息重新路由</b>（非会话级 sticky）；</li>
 *   <li>路由输入 = 当前问题 + <b>最近几轮对话上下文</b>（保证"之前说的退款进度呢"这类追问能派对）；</li>
 *   <li>单候选直接命中不走 LLM；候选多时靠描述质量——描述重叠会导致路由错误；</li>
 *   <li>超时/失败/无命中 → 返回 null，由调用方回落默认智能体（对应 Dify/FastGPT 的"其他分类"兜底）。</li>
 * </ul>
 * 超时/开关复用 SubAgent 委派路由的配置（agent.routeTimeoutMs / agent.autoDispatch）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class AgentDispatchService {

    private final ConfigService configService;
    private final ChatClient chatClient;

    /** 派遣路由专用线程池（带超时，避免路由模型卡住拖垮整轮问答；daemon 不阻碍 JVM 退出） */
    private static final java.util.concurrent.ExecutorService DISPATCH_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "agent-dispatcher");
                t.setDaemon(true);
                return t;
            });

    public AgentDispatchService(ConfigService configService, ChatClient chatClient) {
        this.configService = configService;
        this.chatClient = chatClient;
    }

    /**
     * 自动派遣：从候选主智能体中挑一个最合适的。
     *
     * @param question      用户当前问题
     * @param candidates    可见主智能体（调用方过滤 isSubagent 与可见性；空由调用方处理）
     * @param resolvedModel 当轮生效模型（路由调用与回答同模型，思考档关闭保低延迟）
     * @param recentContext 最近几轮对话文本（可为空串：新会话首问）
     * @return 被派遣的智能体；路由失败/超时/判定无匹配返回 null（调用方回落默认智能体）
     */
    public Agent dispatch(String question, List<Agent> candidates, String resolvedModel, String recentContext) {
        if (candidates == null || candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.get(0);
        try {
            StringBuilder sb = new StringBuilder();
            for (Agent a : candidates) {
                sb.append("- ").append(a.getId()).append(" | ").append(a.getName()).append("：")
                        .append(a.getDescription() == null || a.getDescription().isBlank()
                                ? "（无描述，通用助手）" : a.getDescription()).append("\n");
            }
            String prompt = "你是任务分派员。可派遣的智能体清单如下（每行：id | 名称：职责描述）：\n" + sb
                    + (recentContext == null || recentContext.isBlank()
                    ? "" : "\n最近对话（用于理解追问的上下文）：\n" + recentContext + "\n")
                    + "\n用户当前问题：" + question
                    + "\n\n请判断该问题最适合交给哪个智能体回答（依据职责描述匹配，追问要结合最近对话理解）。"
                    + "\n只输出一个 JSON 对象：{\"id\":\"智能体id\"}；若没有任何智能体匹配则输出 {}。不要输出任何解释文字。";
            int timeoutMs = Math.max(1000, configService.getInt("agent.routeTimeoutMs", 8000));
            String out = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> chatClient.prompt()
                            .user(prompt)
                            .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                                    .model(resolvedModel)
                                    .temperature(0.0)
                                    .internalToolExecutionEnabled(false)
                                    .build())
                            .call()
                            .content(), DISPATCH_EXECUTOR)
                    .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            Agent picked = parseDispatchResult(out, candidates);
            if (picked == null) {
                log.info("[DISPATCH] 路由判定无匹配智能体（{} 个候选），回落默认智能体", candidates.size());
            }
            return picked;
        } catch (Exception e) {
            log.warn("[DISPATCH] 自动派遣失败，回落默认智能体（{} 个候选）: {}", candidates.size(), e.getMessage());
            return null;
        }
    }

    /**
     * 解析路由结果（JSON 对象 {"id":"..."}）：按 id 匹配，兼容模型直接输出 id 字符串/名称的情况。
     * 解析不出有效项返回 null（回落），模型明确输出 {} 也返回 null（判定无匹配）。
     */
    Agent parseDispatchResult(String out, List<Agent> candidates) {
        if (out == null || out.isBlank()) return null;
        try {
            String s = out.trim();
            int lb = s.indexOf('{');
            int rb = s.lastIndexOf('}');
            if (lb >= 0 && rb > lb) {
                com.alibaba.fastjson2.JSONObject obj = com.alibaba.fastjson2.JSON.parseObject(s.substring(lb, rb + 1));
                if (obj == null || obj.isEmpty()) return null;   // 明确判定"都不匹配"
                String id = obj.getString("id");
                if (id != null && !id.isBlank()) {
                    for (Agent a : candidates) {
                        if (a.getId().equals(id.trim()) || a.getName().equals(id.trim())) return a;
                    }
                }
                return null;
            }
            // 兼容裸 id/名称输出
            for (Agent a : candidates) {
                if (s.contains(a.getId()) || s.contains(a.getName())) return a;
            }
            return null;
        } catch (Exception e) {
            log.warn("[DISPATCH] 路由结果解析失败: {}", e.getMessage());
            return null;
        }
    }
}
