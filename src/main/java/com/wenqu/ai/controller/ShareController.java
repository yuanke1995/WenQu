package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.AgentShare;
import com.wenqu.ai.service.AgentShareService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.RagService;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.service.SessionService;
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
    private final com.wenqu.ai.service.SessionShareService sessionShareService;
    private final com.wenqu.ai.service.ImageUrlSigner imageUrlSigner;

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

    @Operation(summary = "分享信息", description = "token 换取智能体公开信息（名称/描述/图标；不含提示词与知识库配置）")
    @GetMapping("/{token}/info")
    public ResultJson info(@Parameter(description = "分享令牌") @PathVariable("token") String token) {
        var ctx = agentShareService.resolveGuest(token);
        return ResultJson.ok(Map.of(
                "name", ctx.agent().getName() == null ? "" : ctx.agent().getName(),
                "description", ctx.agent().getDescription() == null ? "" : ctx.agent().getDescription(),
                // 图标：分享页头部/空态复用 AgentAvatar 的同一套解析口径（'wenqu'=品牌标 / emoji / 空=机器人）
                "icon", ctx.agent().getIcon() == null ? "" : ctx.agent().getIcon(),
                "isBuiltin", ctx.agent().getIsBuiltin() != null && ctx.agent().getIsBuiltin() == 1));
    }

    /**
     * 下载分享会话里的产物文件（免登录，凭 token）。
     *
     * <p><b>为什么不复用产物列表里的签名 URL</b>：产物走 {@code /ai/artifacts/**} 静态映射 +
     * expire+sig 拦截器，签名默认只有 1 小时，而分享链接可以挂几个月——把签名 URL 写进分享页，
     * 等于给出一批"当天能下、隔天全 401"的死链。改成每次下载凭 token 重新解析并现场返回，
     * 让链接有效期与分享有效期一致（都只由 enabled 决定）。
     *
     * <p>入参只有会话内序号 {@code seq}，没有路径：产物归属校验、序号→文件的映射、
     * 已删/已清理的判定全在 {@code SessionShareService.sharedArtifact} 内完成。
     * 停用或换 token 后立即 404（与 {@link #sharedSession} 同一套 resolvePublic 口径，
     * 不区分"不存在"与"已停用"）；文件已被超期清理或手动删除同样 404。
     */
    @Operation(summary = "下载分享产物", description = "按分享 token 下载该会话的产物（凭会话内序号；停用即失效）")
    @GetMapping("/session/{token}/artifact")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadArtifact(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @Parameter(description = "会话内产物序号") @RequestParam("seq") int seq) {
        var artifact = sessionShareService.sharedArtifact(token, seq);
        if (artifact == null) throw new BizException(404, "产物不存在或分享链接已失效");
        // 中文文件名必须按 RFC 5987 编码，否则部分浏览器/客户端落盘乱码
        String encoded = java.net.URLEncoder.encode(artifact.filename(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        log.info("[AUDIT] 分享产物下载 token={} file={} seq={}", token, artifact.filename(), seq);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded)
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(new org.springframework.core.io.FileSystemResource(artifact.file()));
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
        var history = sessionService.getRecentHistory(sessionId, 20);
        // 与登录态会话历史同一套出参签名口径（ImageUrlSigner.signMessageMedia）：库里存的是原始 URL，
        // 签名是一次性凭据不能落库，所以每次响应现场签。少了这一步，游客刷新页面后图片全 404
        // （首轮能看到图，因为 SSE 下发时已签过）。
        imageUrlSigner.signMessageMedia(history);
        return ResultJson.ok(history);
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

        // 游客对话同样不设容器级 SSE 超时：整轮截断由 RagService 的存活看门狗按机器耗时判定
        SseEmitter emitter = new SseEmitter(0L);
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
