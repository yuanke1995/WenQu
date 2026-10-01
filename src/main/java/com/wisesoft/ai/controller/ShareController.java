package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.AgentShare;
import com.wisesoft.ai.service.AgentShareService;
import com.wisesoft.ai.service.ModelRegistryService;
import com.wisesoft.ai.service.RagService;
import com.wisesoft.ai.service.RateLimitService;
import com.wisesoft.ai.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 智能体公开分享（游客通道，免登录）：/s/{token} 前端页面的后端 API。
 * <p>
 * 安全模型（与 SecurityConfig.isShareGuestEndpoint 放行配套）：
 * <ul>
 *   <li>token 即凭据：链接持有者即可对话，停用/撤销立即失效（404 不泄露存在性）；</li>
 *   <li>限流：复用 chat 桶按 IP 限频（游客无身份，防单人挤兑共享池）；</li>
 *   <li>身份：检索可见性与个人默认模型按发布者执行（发布即授权）；</li>
 *   <li>能力：RagService 游客模式只暴露知识检索与内置工具，沙盒/产物/MCP/技能不暴露；</li>
 *   <li>会话：游客 uid = "share-visitor:{客户端随机ID}"，SessionService 按 owner 精确匹配，
 *       游客只能读写自己的会话，也不会出现在任何登录用户的会话列表里。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/share")
@RequiredArgsConstructor
@Tag(name = "智能体公开分享", description = "游客免登录与指定智能体对话（/s/{token}）")
public class ShareController {

    private final AgentShareService agentShareService;
    private final SessionService sessionService;
    private final RagService ragService;
    private final RateLimitService rateLimitService;
    private final ModelRegistryService modelRegistryService;
    private final com.wisesoft.ai.service.ConfigService configService;
    private final com.wisesoft.ai.service.SessionShareService sessionShareService;

    /** 单条提问长度上限（与对话页同量级的防滥用口径） */
    private static final int MAX_QUESTION_CHARS = 8000;

    /**
     * 会话只读分享（免登录）：按 token 取回被分享会话的标题与消息。
     * <p>
     * 与智能体分享的差别：这里没有"以分享者身份执行"的授权语义——只读、且内容已裁剪
     * （消息正文 + 来源的文档名/章节/相关度；不含知识块全文、工具过程、思考全文与用量）。
     * 停用或换 token 后立即 404（不区分"不存在"与"已停用"，不泄露存在性）。
     */
    @Operation(summary = "会话只读分享", description = "按分享 token 取回会话标题与消息（只读，免登录）")
    @GetMapping("/session/{token}")
    public ResultJson sharedSession(
            @Parameter(description = "分享令牌") @PathVariable("token") String token) {
        var share = sessionShareService.resolvePublic(token);
        if (share == null) throw new BizException(404, "分享链接无效或已停止访问");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("title", sessionShareService.sessionTitle(share.getSessionId()));
        out.put("sharedAt", share.getCreateTime());
        out.put("messages", sessionShareService.publicHistory(share.getSessionId()));
        return ResultJson.ok(out);
    }

    @Operation(summary = "分享信息", description = "token 换取智能体公开信息（名称/描述；不含提示词与知识库配置）")
    @GetMapping("/{token}/info")
    public ResultJson info(@Parameter(description = "分享令牌") @PathVariable("token") String token) {
        var ctx = agentShareService.resolveGuest(token);
        return ResultJson.ok(Map.of(
                "name", ctx.agent().getName() == null ? "" : ctx.agent().getName(),
                "description", ctx.agent().getDescription() == null ? "" : ctx.agent().getDescription()));
    }

    @Operation(summary = "游客对话历史", description = "取游客自己会话的最近若干轮（刷新页面恢复用；只能取自己的会话）")
    @GetMapping("/{token}/history")
    public ResultJson history(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @Parameter(description = "游客会话 ID") @RequestParam("sessionId") String sessionId,
            @RequestParam("visitorId") String visitorId) {
        agentShareService.resolveGuest(token); // 链接失效则历史同样拒绝
        String visitorUid = AgentShareService.visitorUid(visitorId);
        try {
            sessionService.assertOwned(sessionId, visitorUid);
        } catch (BizException e) {
            return ResultJson.ok(List.of()); // 404/403 一律按空历史（不泄露会话存在性）
        }
        return ResultJson.ok(sessionService.getRecentHistory(sessionId, 20));
    }

    @Operation(summary = "游客对话", description = "免登录流式问答（SSE）：按发布者身份检索、游客受限工具集；按 IP 限频")
    @PostMapping("/{token}/chat")
    public SseEmitter chat(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        // IP 限频先于任何查询（最便宜的一关）
        rateLimitService.checkRateLimit("chat", "ip:" + clientIp(httpRequest));
        var ctx = agentShareService.resolveGuest(token);
        var owner = ctx.owner();
        if (owner.getStatus() != null && owner.getStatus() == 0) {
            throw new BizException("分享不存在或已停止访问");
        }
        String modelRef = ctx.share().getModelRef() == null ? "" : ctx.share().getModelRef();
        // 发布者模型可用性复核（发布后供应商可能被停用/删除；fail-loud 而不是拿不到模型静默报错）
        if (!modelRef.isBlank()) {
            modelRegistryService.assertUsable(modelRef, owner.getUid(), owner.getRole());
        }

        String message = body.get("message");
        if (message == null || message.isBlank()) throw new BizException("请输入内容");
        if (message.length() > MAX_QUESTION_CHARS) {
            throw new BizException("单条内容过长（最多 " + MAX_QUESTION_CHARS + " 字）");
        }
        String sessionId = body.get("sessionId");
        String visitorUid = AgentShareService.visitorUid(body.get("visitorId"));
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionService.createSession(visitorUid);
        } else {
            try {
                sessionService.assertOwned(sessionId, visitorUid);
            } catch (BizException e) {
                if (e.getCode() == 404) {
                    sessionId = sessionService.ensureSession(sessionId, visitorUid);
                } else {
                    throw e;
                }
            }
        }

        long sseTimeout = configService.getLong("chat.sseTimeoutMs");
        if (sseTimeout <= 0) sseTimeout = 300000L;
        SseEmitter emitter = new SseEmitter(sseTimeout);
        // guestMode=true：工具白名单收窄（知识检索+内置），身份按发布者装载（检索可见性/默认模型）
        ragService.chat(sessionId, message.trim(), List.of(), List.of(), List.of(),
                false, ctx.agent().getId(), modelRef, owner.getUid(), emitter, true);
        agentShareService.incrementVisit(ctx.share().getId());
        log.info("[AUDIT] 游客对话 ip={} agent={} session={}",
                clientIp(httpRequest), ctx.agent().getId(), sessionId);
        return emitter;
    }

    /** 客户端 IP（X-Forwarded-For 首段优先；与 ChatController 同口径） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }
}
