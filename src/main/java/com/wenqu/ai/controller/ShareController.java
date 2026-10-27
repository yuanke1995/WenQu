package com.wenqu.ai.controller;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.AuditService;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.dto.ShareChatRequest;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.model.AgentShare;
import com.wenqu.ai.model.Message;
import com.wenqu.ai.service.AgentShareService;
import com.wenqu.ai.service.ChatUploadService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.ChatService;
import com.wenqu.ai.service.QaLogService;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 *   <li>能力：ChatService 游客模式只暴露知识检索与内置工具，沙盒/产物/MCP/技能不暴露；</li>
 *   <li>会话：游客 uid = "share-visitor:{客户端随机ID}"，SessionService 按 owner 精确匹配，
 *       游客只能读写自己的会话，也不会出现在任何登录用户的会话列表里。
 *       清除同样只按 (uid, 智能体) 在 SQL 里圈定，不接受调用方传会话 ID。</li>
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
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    private final AgentShareService agentShareService;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final com.wenqu.ai.service.UserRoleService userRoleService;

    /**
     * 轮次控制与存活（P6 刀 2）。免登录分享页也要能问「这一轮还在跑吗」、能接回正在跑的流、
     * 能叫停——这三件事与「谁在答这一问」无关，归轮次运行时。
     */
    private final com.wenqu.ai.agent.chat.ChatRunRuntime runtime;
    private final RateLimitService rateLimitService;
    private final ModelRegistryService modelRegistryService;
    private final com.wenqu.ai.service.SessionShareService sessionShareService;
    private final com.wenqu.ai.service.ImageUrlSigner imageUrlSigner;
    private final ConfigService configService;
    private final ChatUploadService chatUploadService;
    private final MessageMapper messageMapper;
    private final QaLogService qaLogService;

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

    @Operation(summary = "分享信息", description = "token 换取智能体公开信息（名称/描述/图标 + 游客能力位与限额；不含提示词与知识库配置）")
    @GetMapping("/{token}/info")
    public ResultJson info(@Parameter(description = "分享令牌") @PathVariable("token") String token) {
        var ctx = agentShareService.resolveGuest(token);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("name", ctx.agent().getName() == null ? "" : ctx.agent().getName());
        out.put("description", ctx.agent().getDescription() == null ? "" : ctx.agent().getDescription());
        // 图标：分享页头部/空态复用 AgentAvatar 的同一套解析口径（'wenqu'=品牌标 / emoji / 空=机器人）
        out.put("icon", ctx.agent().getIcon() == null ? "" : ctx.agent().getIcon());
        out.put("isBuiltin", ctx.agent().getIsBuiltin() != null && ctx.agent().getIsBuiltin() == 1);
        // 游客多模态能力位：由发布者在「公开发布」弹窗里显式打开。前端据此决定要不要渲染
        // 附件/图片入口——能力没开就必须连入口都不出现，做一个点了才报错的按钮等于骗用户
        out.put("guestImages", AgentShareService.guestImagesOn(ctx.share()));
        out.put("guestFiles", AgentShareService.guestFilesOn(ctx.share()));
        // 限额随能力位一起下发：前端发送前就地拦（"最多 9 张、单张 10MB"），不在客户端另写一套会漂移的数字。
        // 后端仍逐项复核——下发限额只是让提示更早出现，不是把校验搬到前端
        out.put("maxImages", maxImagesPerMessage());
        out.put("maxImageMb", maxImageMb());
        out.put("maxAttachments", maxAttachmentsPerMessage());
        out.put("maxAttachmentMb", maxAttachmentMb());
        out.put("maxQuestionChars", MAX_QUESTION_CHARS);
        return ResultJson.ok(out);
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
        audit.record("[AUDIT] 分享产物下载 token={} file={} seq={}", token, artifact.filename(), seq);
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
        // 评价状态回填（与 ChatController.history 同口径）：游客点过 👍/👎 之后刷新，
        // 按钮得还是「已评价」的锁定态。不回填的话界面显示成没评过——游客以为反馈丢了会再点一次，
        // 而服务端按 messageId 唯一只会覆盖，发布者那边看到的一次不多、游客这边却白白多疑一次。
        List<String> msgIds = history.stream()
                .map(m -> m.get("messageId") == null ? null : String.valueOf(m.get("messageId")))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, Integer> ratings = qaLogService.loadFeedbackRatings(msgIds);
        for (Map<String, Object> msg : history) {
            Object mid = msg.get("messageId");
            if (mid == null) continue;
            Integer fb = ratings.get(String.valueOf(mid));
            if (fb != null) msg.put("fb", fb);
        }
        return ResultJson.ok(history);
    }

    @Operation(summary = "游客历史会话", description = "列出该访客在此分享链接下的会话（按访客 uid + 智能体过滤，只返回自己的）")
    @GetMapping("/{token}/sessions")
    public ResultJson sessions(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @Parameter(description = "访客 ID") @RequestParam("visitorId") String visitorId) {
        var ctx = agentShareService.resolveGuest(token);
        return ResultJson.ok(Map.of("items", sessionService.listSessionsForAgent(
                AgentShareService.visitorUid(visitorId), ctx.agent().getId(), 50)));
    }

    @Operation(summary = "清除游客会话", description = "删除该访客在此分享链接下的全部会话（公用电脑上的收尾动作；只作用于自己 uid 名下的数据）")
    @DeleteMapping("/{token}/sessions")
    public ResultJson clearSessions(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @Parameter(description = "访客 ID") @RequestParam("visitorId") String visitorId,
            HttpServletRequest httpRequest) {
        var ctx = agentShareService.resolveGuest(token);
        int deleted = sessionService.deleteSessionsOfVisitor(
                AgentShareService.visitorUid(visitorId), ctx.agent().getId());
        audit.record("[AUDIT] 游客清除会话 ip={} agent={} 会话数={}",
                clientIp(httpRequest), ctx.agent().getId(), deleted);
        return ResultJson.ok(Map.of("deleted", deleted));
    }

    @Operation(summary = "游客对话", description = "免登录流式问答（SSE）：按发布者身份检索、游客受限工具集；按 IP 限频。"
            + "支持图片与文档附件（须发布者在公开发布里开启对应开关，未开启时带料请求一律拒绝而不是静默丢弃）")
    @PostMapping("/{token}/chat")
    public SseEmitter chat(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @RequestBody ShareChatRequest body,
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
            modelRegistryService.assertUsable(modelRef, owner.getUid(), userRoleService.primaryRole(owner.getUid()));
        }

        String message = body.getMessage();
        if (message == null || message.isBlank()) throw new BizException("请输入内容");
        if (message.length() > MAX_QUESTION_CHARS) {
            throw new BizException("单条内容过长（最多 " + MAX_QUESTION_CHARS + " 字）");
        }
        String visitorUid = AgentShareService.visitorUid(body.getVisitorId());
        // 多模态：先过发布者的能力开关，再过形状/数量/体积。顺序不能反——开关关着的时候
        // 用户最需要看到的是"这个分享没开放图片"，而不是"单张图片不能超过 10MB"
        List<String> images = parseGuestImages(ctx.share(), body.getImages());
        List<ChatRequest.Attachment> attachments =
                parseGuestAttachments(ctx.share(), body.getAttachments(), visitorUid);

        String sessionId = body.getSessionId();
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
        // 重新生成：必须在会话归属确认之后解析（校验要拿 sessionId 比对消息归属）
        String replaceMessageId = resolveReplaceTarget(body.getReplaceMessageId(), sessionId);

        // 游客对话同样不设容器级 SSE 超时：整轮截断由 ChatRunRuntime 的存活看门狗按机器耗时判定
        SseEmitter emitter = new SseEmitter(0L);
        // guestMode=true：工具白名单收窄（知识检索+内置），身份按发布者装载（检索可见性/默认模型）
        chatService.chat(ChatCommand.builder()
                .sessionId(sessionId).question(message.trim()).userId(owner.getUid())
                // 接流归属用访客身份（不是发布者）：分享页刷新/换设备能把这一轮接回来，
                // 而别人即便拿到同一个 token 也接不走不属于自己的会话
                .userImages(images).attachments(attachments).skills(List.of())
                .agentId(ctx.agent().getId()).modelOverride(modelRef).guestMode(true)
                .guestUid(visitorUid)
                // 附件的字节在**访客**的目录里（上传通道按 share-visitor 隔离落盘），
                // 而 userId 是发布者（检索可见性按他执行）——两者必须分开说，
                // 否则附件正文会被当成"已过期"读不出来（见 ChatCommand#attachmentOwnerUid）
                .attachmentOwnerUid(visitorUid)
                // regenerate + replaceMessageId：分享页的「重新生成」复用登录态同一套语义
                // （不重复落库用户消息；工作流驱动的智能体还会据此软删旧回答）
                .regenerate(body.isRegenerate()).replaceMessageId(replaceMessageId)
                .build(), emitter);
        agentShareService.incrementVisit(ctx.share().getId());
        audit.record("[AUDIT] 游客对话 ip={} agent={} session={} 图片={} 附件={} 重新生成={}",
                clientIp(httpRequest), ctx.agent().getId(), sessionId,
                images.size(), attachments.size(), body.isRegenerate());
        return emitter;
    }

    /**
     * 游客上传文档附件：返回 {@code {fileId,name,mime,size}}，后续提问只带 fileId。
     *
     * <p><b>为什么不复用登录态的 {@code POST /chat/attachment}</b>：那个端点按 {@code RequestUser.uid()}
     * 分目录落盘，而游客的 uid 恒为 {@code "anonymous"}——所有访客会共用同一个上传目录，而
     * {@code stat()/read()} 的归属判定是"文件在我目录里即为我的"。跨访客引用就只剩下
     * "fileId 是 32 位随机 UUID 猜不到"这一层挡着，那不是隔离，是运气。
     * 本端点改用 {@code share-visitor:{visitorId}} 作 uid，落盘目录随之逐访客派生，
     * 别家访客的 fileId 在这里 {@code stat()} 直接读不到。
     *
     * <p>三道闸与登录态同源：发布者的「允许上传附件」开关、IP 限频（upload 桶）、
     * 以及 {@link ChatUploadService} 内的类型白名单与体积上限。留盘同样受
     * {@code chat.uploadRetentionHours}（默认 24h）周期清理。
     */
    @Operation(summary = "游客上传附件", description = "上传文档类附件换 fileId（按访客隔离落盘、24h 清理）；"
            + "需发布者开启「允许游客上传附件」，按 IP 限频")
    @ApiResponse(responseCode = "200", description = "返回 {fileId,name,mime,size}")
    @PostMapping("/{token}/attachment")
    public ResultJson uploadAttachment(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @Parameter(description = "访客 ID（客户端随机）") @RequestParam("visitorId") String visitorId,
            @Parameter(description = "附件文件") @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            HttpServletRequest httpRequest) {
        // 限频最便宜，放最前（与 chat 同一口径）
        rateLimitService.checkRateLimit("upload", "ip:" + clientIp(httpRequest));
        var ctx = agentShareService.resolveGuest(token);
        if (!AgentShareService.guestFilesOn(ctx.share())) {
            throw new BizException("该分享未开放附件上传");
        }
        String visitorUid = AgentShareService.visitorUid(visitorId);
        var saved = chatUploadService.save(file, visitorUid);
        audit.record("[AUDIT] 游客上传附件 ip={} agent={} file={} size={}",
                clientIp(httpRequest), ctx.agent().getId(), saved.name(), saved.size());
        return ResultJson.ok(saved, "上传完成");
    }

    /**
     * 游客提交回答反馈（👍/👎，可选文字）：发布者在公开链接上唯一拿得到的内容质量信号。
     *
     * <p>不能复用登录态的 {@code POST /feedback}：那条走 {@code assertOwned(sessionId, RequestUser.uid())}，
     * 游客的 uid 是 {@code anonymous}，一律 403。这里换成按<b>访客 uid</b>校验归属：
     * 先由 messageId 取回消息，再拿消息自己的 sessionId 去比对（调用方不必传会话 ID，
     * 也就少一个可以被伪造的参数）。
     */
    @Operation(summary = "游客提交回答反馈", description = "对某条回答 👍/👎（可选文字）；只能评价本访客会话内的消息，按 IP 限频")
    @PostMapping("/{token}/feedback")
    public ResultJson feedback(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @Parameter(description = "{\"messageId\":\"消息ID\",\"visitorId\":\"访客ID\",\"rating\":1|0,\"feedbackText\":\"可选\"}")
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        rateLimitService.checkRateLimit("chat", "ip:" + clientIp(httpRequest));
        agentShareService.resolveGuest(token);
        String visitorUid = AgentShareService.visitorUid(str(body, "visitorId"));
        String messageId = str(body, "messageId");
        if (messageId == null || messageId.isBlank()) throw new BizException("缺少 messageId");
        // 归属：消息属于哪个会话，那个会话就必须是本访客的。与登录态 QaController.feedback 同一口径，
        // 只把「本人」换成「本访客」——少了这一步，任何拿到链接的人都能用别人的 messageId 灌反馈
        Message msg = messageMapper.selectByIdIgnoreDeleted(messageId);
        if (msg == null) throw new BizException(404, "消息不存在或已过撤销期");
        sessionService.assertOwned(msg.getSessionId(), visitorUid);
        int rating = parseRating(body.get("rating"));
        qaLogService.feedback(messageId, rating, str(body, "feedbackText"));
        audit.record("[AUDIT] 游客反馈 ip={} session={} message={} rating={}",
                clientIp(httpRequest), msg.getSessionId(), messageId, rating);
        return ResultJson.ok("感谢反馈");
    }

    /** 本轮发图数量上限（与登录态同一配置键：两处各写一个默认值迟早对不上） */
    private int maxImagesPerMessage() {
        return Math.max(1, configService.getInt("chat.maxImagesPerMessage", 9));
    }

    /** 单张图片体积上限（MB） */
    private int maxImageMb() {
        return Math.max(1, configService.getInt("chat.maxImageMb", 10));
    }

    /** 本轮附件数量上限 */
    private int maxAttachmentsPerMessage() {
        return Math.max(1, configService.getInt("chat.maxAttachmentsPerMessage", 5));
    }

    /** 单个附件体积上限（MB） */
    private int maxAttachmentMb() {
        return Math.max(1, configService.getInt("chat.maxAttachmentMb", 15));
    }

    /**
     * 游客发图校验：发布者开关 → 数量 → 形状（须 data:image/* 的 data URL）→ 单张体积。
     * 与 {@code ChatController.chat} 的图片校验同一套口径（那里也校验 data URL 前缀与 base64 字符量）。
     * <p>开关关着却带图 = fail-loud，不静默丢弃：静默丢弃会让发布者收到一条"游客说发了图但答非所问"
     * 的投诉，而双方都看不出真正原因。
     */
    private List<String> parseGuestImages(AgentShare share, List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (!AgentShareService.guestImagesOn(share)) {
            throw new BizException("该分享未开放图片输入，请改用文字提问");
        }
        int maxImages = maxImagesPerMessage();
        if (raw.size() > maxImages) throw new BizException("一次最多发送 " + maxImages + " 张图片");
        long maxBase64Chars = maxImageMb() * 4L * 1024 * 1024 / 3; // base64 膨胀 4/3 后的字符量上限
        for (String img : raw) {
            int comma = img == null ? -1 : img.indexOf(',');
            if (comma <= 0 || !img.startsWith("data:image/")) {
                throw new BizException("图片格式不正确（需 data:image/* 的 data URL）");
            }
            if (img.length() - comma - 1 > maxBase64Chars) {
                throw new BizException("单张图片不能超过 " + maxImageMb() + "MB");
            }
        }
        return raw;
    }

    /**
     * 游客附件校验：发布者开关 → 数量 → 逐个按 {@code (fileId, 访客 uid)} 查存在性与归属。
     * <p>名称与 MIME 一律以服务端落盘时的元信息为准（客户端可随意伪造展示名与类型）。
     * <p><b>归属隔离就落在 {@code stat(fileId, visitorUid)} 这一句</b>：文件不在该访客的目录下即返回 null，
     * 于是拿别人的 fileId 过来只会得到"附件不存在或已过期"，读不到内容。
     */
    private List<ChatRequest.Attachment> parseGuestAttachments(AgentShare share,
                                                              List<ChatRequest.Attachment> raw,
                                                              String visitorUid) {
        if (raw == null || raw.isEmpty()) return List.of();
        if (!AgentShareService.guestFilesOn(share)) {
            throw new BizException("该分享未开放附件上传");
        }
        int maxAtt = maxAttachmentsPerMessage();
        if (raw.size() > maxAtt) throw new BizException("一次最多上传 " + maxAtt + " 个附件");
        for (ChatRequest.Attachment att : raw) {
            if (att == null || att.getFileId() == null || att.getFileId().isBlank()) {
                throw new BizException("附件信息不完整（缺少上传标识，请重新上传）");
            }
            var meta = chatUploadService.stat(att.getFileId(), visitorUid);
            if (meta == null) throw new BizException("附件不存在或已过期，请重新上传");
            att.setName(meta.name());
            att.setMime(meta.mime());
        }
        return raw;
    }

    /**
     * 「重新生成」时被替换的旧回答：归一成可安全传给 {@code beginAnswerReplace} 的值。
     * <p>{@code SessionService.beginAnswerReplace} 只认消息 ID，自身不校验归属；游客链路若直接把
     * 请求里的 ID 透下去，任何拿到分享链接的人都能软删别人会话里的回答。所以这里两道都查：
     * 消息必须是<b>本会话</b>的，且必须是<b>assistant</b>（替换一条用户提问没有语义）。
     * 会话本身的归属已在调用点确认过，这里只补"消息属不属于这个会话"。
     */
    private String resolveReplaceTarget(String replaceMessageId, String sessionId) {
        if (replaceMessageId == null || replaceMessageId.isBlank()) return null;
        Message msg = messageMapper.selectByIdIgnoreDeleted(replaceMessageId);
        if (msg == null) throw new BizException(404, "被替换的回答不存在或已过撤销期");
        if (!sessionId.equals(msg.getSessionId())) {
            throw new BizException(403, "被替换的回答不属于本会话");
        }
        if (!"assistant".equals(msg.getRole())) {
            throw new BizException("只能重新生成回答，不能替换提问");
        }
        return replaceMessageId;
    }

    /** 取字符串入参（Map body 的安全读取；null 原样返回，由调用方决定必填与否） */
    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /** 反馈评分归一：只认 1（有帮助）/ 0（没帮助），其余与缺省一律按 0（与登录态同一口径） */
    private static int parseRating(Object raw) {
        if (raw == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(raw).trim()) == 1 ? 1 : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Operation(summary = "游客接流·探测", description = "问这个访客的会话是否还有一轮在跑（分享页刷新/换设备前问一句）。"
            + "归属按访客身份（visitorId 派生）判，与 /share/{token}/history 同一条口径；"
            + "会话不是这个访客的就报错，不在跑返回 running=false。")
    @GetMapping("/{token}/chat/run")
    public ResultJson guestRunStatus(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId,
            @Parameter(description = "访客 ID（客户端随机）") @RequestParam("visitorId") String visitorId,
            HttpServletRequest httpRequest) {
        if (sessionId == null || sessionId.isBlank() || visitorId == null || visitorId.isBlank()) {
            return ResultJson.error("缺少会话 ID 或访客 ID");
        }
        agentShareService.resolveGuest(token);
        rateLimitService.checkRateLimit("chat", "ip:" + clientIp(httpRequest));
        String visitorUid = AgentShareService.visitorUid(visitorId);
        sessionService.assertOwned(sessionId.trim(), visitorUid);
        return ResultJson.ok(runtime.runStatus(sessionId.trim(), visitorUid));
    }

    @Operation(summary = "游客接流", description = "把一条新的 SSE 通道挂到该访客这一会话正在跑的那一轮上："
            + "先送 snapshot（此刻为止的正文/工具卡/计划/产物 + 状态事件最后一份），此后只送快照之后的增量。"
            + "没有在跑、已收尾、或服务已重启（内存态把手不跨进程）→ snapshot{running:false} 后立即关闭。"
            + "分享页原来只有「按历史回显」这一条恢复路径，刷新那一刻正在生成的回答会停住不动。")
    @ApiResponse(responseCode = "200", description = "SSE 流式响应",
            content = @Content(mediaType = "text/event-stream"))
    @GetMapping("/{token}/chat/resume")
    public SseEmitter guestResumeChatTurn(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId,
            @Parameter(description = "访客 ID（客户端随机）") @RequestParam("visitorId") String visitorId,
            HttpServletRequest httpRequest) {
        SseEmitter emitter = new SseEmitter(0L);
        if (sessionId == null || sessionId.isBlank() || visitorId == null || visitorId.isBlank()) {
            emitter.complete();
            return emitter;
        }
        try {
            agentShareService.resolveGuest(token);
            rateLimitService.checkRateLimit("chat", "ip:" + clientIp(httpRequest));
            String visitorUid = AgentShareService.visitorUid(visitorId);
            sessionService.assertOwned(sessionId.trim(), visitorUid);
            runtime.resumeTurn(sessionId.trim(), visitorUid, emitter);
        } catch (Exception e) {
            // 这条通道是免登录的：任何一关没过就送一份 running:false 的快照并关掉，
            // 前端按历史回显——不能留一条挂着不走的 SSE 请求
            runtime.resumeTurn(sessionId.trim(), null, emitter);
        }
        return emitter;
    }

    @Operation(summary = "游客停止本轮", description = "叫停该访客这一会话正在跑的这一轮：半程正文按停止态落库、"
            + "会话互斥立刻释放，下一次提问不用等宽限。分享页的「停止」按钮必须打到服务端——"
            + "本轮已接到运行总线上（为了断线能接回来），只 abort 前端 fetch 会让它继续在后台跑满宽限期。")
    @PostMapping("/{token}/chat/stop")
    public ResultJson guestStopChatTurn(
            @Parameter(description = "分享令牌") @PathVariable("token") String token,
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId,
            @Parameter(description = "访客 ID（客户端随机）") @RequestParam("visitorId") String visitorId,
            HttpServletRequest httpRequest) {
        if (sessionId == null || sessionId.isBlank() || visitorId == null || visitorId.isBlank()) {
            return ResultJson.error("缺少会话 ID 或访客 ID");
        }
        var ctx = agentShareService.resolveGuest(token);
        rateLimitService.checkRateLimit("chat", "ip:" + clientIp(httpRequest));
        // 归属按<b>访客</b>认（会话是这个访客建的，别人没有这个访客标识就过不去这道关），
        // 过完之后才按发布者 uid 叫停——本轮在流水线里登记的身份是发布者（免登录问答按发布者检索）。
        String visitorUid = AgentShareService.visitorUid(visitorId);
        sessionService.assertOwned(sessionId.trim(), visitorUid);
        boolean stopped = runtime.stopTurn(sessionId.trim(), ctx.owner().getUid());
        audit.record("[AUDIT] 游客停止本轮 ip={} agent={} session={} stopped={}",
                clientIp(httpRequest), ctx.agent().getId(), sessionId, stopped);
        return ResultJson.ok(stopped ? "已停止本轮" : "这一会话没有在跑的回答（可能刚结束）");
    }

    /** 客户端 IP（X-Forwarded-For 首段优先；与 ChatController 同口径） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }
}
