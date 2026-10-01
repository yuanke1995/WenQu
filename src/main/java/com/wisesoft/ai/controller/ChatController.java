package com.wisesoft.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.config.AdminGuard;
import com.wisesoft.ai.dto.ChatRequest;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.dto.SessionInfo;
import com.wisesoft.ai.mapper.KnowledgeMapper;
import com.wisesoft.ai.model.Knowledge;
import com.wisesoft.ai.service.ImageUrlSigner;
import com.wisesoft.ai.service.ChatAttachmentService;
import com.wisesoft.ai.service.QaLogService;
import com.wisesoft.ai.service.RagService;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.RateLimitService;
import com.wisesoft.ai.service.SessionService;
import com.wisesoft.ai.service.AuthService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 聊天控制器（SSE 流式）
 * 用户身份：登录用户 uid（未登录为 anonymous）；会话按用户隔离
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "智能问答", description = "SSE 流式问答、会话管理、知识块详情")
public class ChatController {

    private final RagService ragService;
    private final SessionService sessionService;
    private final ImageUrlSigner imageUrlSigner;
    private final KnowledgeMapper knowledgeMapper;
    private final com.wisesoft.ai.mapper.MessageMapper messageMapper;
    private final ConfigService configService;
    private final RateLimitService rateLimitService;
    private final QaLogService qaLogService;
    private final AdminGuard adminGuard;
    private final AuthService authService;
    private final com.wisesoft.ai.service.MenuService menuService;
    private final com.wisesoft.ai.service.ModelRegistryService modelRegistryService;
    private final com.wisesoft.ai.service.ResourceVisibilityService visibility;
    private final com.wisesoft.ai.service.KnowledgeBaseService kbService;
    private final com.wisesoft.ai.mapper.AiDocumentMapper documentMapper;
    private final com.wisesoft.ai.service.ChatUploadService chatUploadService;

    @Operation(summary = "上传聊天附件", description = "把聊天附件先上传换 fileId（问答请求体只带 fileId，不再内联 base64）；"
            + "文件按用户隔离落盘、超期自动清理；按用户限频（ratelimit.uploadPerMinute）")
    @ApiResponse(responseCode = "200", description = "返回 {fileId,name,mime,size}")
    @PostMapping("/chat/attachment")
    public ResultJson uploadAttachment(
            @Parameter(description = "附件文件") @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        rateLimitService.checkRateLimit("upload", RequestUser.ANONYMOUS.equals(userId)
                ? "ip:" + clientIp(httpRequest) : "user:" + userId);
        return ResultJson.ok(chatUploadService.save(file, userId), "上传完成");
    }

    /**
     * 当前身份与权限（普通用户问答 UI 据此隐藏/显示管理入口；白名单端点，无需管理员即可调用）。
     * 2026-09-26 RBAC：响应追加 {@code menus}（当前角色可见的侧边栏菜单树，前端动态渲染导航）；
     * {@code admin} 按管理员级角色判定（含自定义 admin_flag=1 角色）。
     */
    @Operation(summary = "当前身份与权限", description = "返回当前登录用户（uid/username/role/departmentId）、是否管理员（admin）"
            + "与可见菜单树（menus，按角色绑定下发，前端侧边栏数据源）")
    @GetMapping("/auth/me")
    public ResultJson authMe(HttpServletRequest httpRequest) {
        Map<String, Object> me = authService.currentUser(RequestUser.uid(), adminGuard.isAdmin(httpRequest));
        me.put("menus", menuService.visibleMenusFor(RequestUser.role()));
        return ResultJson.ok(me);
    }

    @Operation(summary = "SSE 流式问答",
            description = "发送问题（可含图片），通过 SSE 流式返回 AI 回答。事件类型：thinking（深度思考增量）、thinking_done（思考结束）、token（文本增量）、image（图片 URL 列表）、done（引用来源/相关推荐/消息ID/思考全文）、error（错误信息）。按用户限频（ratelimit.chatPerMinute）")
    @ApiResponse(responseCode = "200", description = "SSE 流式响应",
            content = @Content(mediaType = "text/event-stream"))
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody @Valid ChatRequest request, HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        // 按用户限频（anonymous 落到 IP 维度，避免匿名共享池互相挤兑）
        rateLimitService.checkRateLimit("chat", RequestUser.ANONYMOUS.equals(userId)
                ? "ip:" + clientIp(httpRequest) : "user:" + userId);

        String question = request.getQuestion().trim();

        // 聊天传图上限：数量与单张体积（防 base64 洪峰压垮解码/视觉处理；数据 URL 字符量≈体积×4/3）
        List<String> images = request.getImages();
        if (images != null && !images.isEmpty()) {
            int maxImages = Math.max(1, configService.getInt("chat.maxImagesPerMessage", 9));
            if (images.size() > maxImages) {
                throw new BizException("一次最多发送 " + maxImages + " 张图片");
            }
            int maxImageMb = Math.max(1, configService.getInt("chat.maxImageMb", 10));
            long maxBase64Chars = maxImageMb * 4L * 1024 * 1024 / 3; // base64 膨胀 4/3 后的字符量上限
            for (String img : images) {
                int comma = img == null ? -1 : img.indexOf(',');
                if (comma <= 0 || !img.startsWith("data:image/")) {
                    throw new BizException("图片格式不正确（需 data:image/* 的 data URL）");
                }
                if (img.length() - comma - 1 > maxBase64Chars) { // ≈maxImageMb MB 原图（base64 膨胀 4/3）
                    throw new BizException("单张图片不能超过 " + maxImageMb + "MB");
                }
            }
        }

        // 附件：请求体只带 fileId（内容已先行上传落盘换号）。
        // 数量上限 + 存在性与归属校验（拿别人的 fileId 一律拒绝）；类型与体积在上传接口已把关，
        // 这里不再按 base64 字符量估算——口径的变化正是这次改造的目的（body 从 ~100MB 降到几十字节）
        List<ChatRequest.Attachment> attachments = request.getAttachments();
        if (attachments != null && !attachments.isEmpty()) {
            int maxAtt = Math.max(1, configService.getInt("chat.maxAttachmentsPerMessage", 5));
            if (attachments.size() > maxAtt) {
                throw new BizException("一次最多上传 " + maxAtt + " 个附件");
            }
            for (ChatRequest.Attachment att : attachments) {
                if (att == null || att.getFileId() == null || att.getFileId().isBlank()) {
                    throw new BizException("附件信息不完整（缺少上传标识，请重新上传）");
                }
                var meta = chatUploadService.stat(att.getFileId(), userId);
                if (meta == null) {
                    throw new BizException("附件不存在或已过期，请重新上传");
                }
                // 名称/MIME 以服务端元信息为准：客户端可随意伪造展示名，解析与展示都该信落盘时的记录
                att.setName(meta.name());
                att.setMime(meta.mime());
            }
        }

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = sessionService.createSession(userId);
        } else {
            // 客户端传入会话 ID：存在则校验归属（防跨用户读写），不存在则按当前用户补建会话记录（兼容旧客户端行为）
            try {
                sessionService.assertOwned(sessionId, userId);
            } catch (BizException e) {
                if (e.getCode() == 404) {
                    sessionId = sessionService.ensureSession(sessionId, userId);
                } else {
                    throw e;
                }
            }
        }

        // 模型引用归属校验：普通用户只能用自己可用的供应商（平台级 + 自己登记的个人级）。
        // 放在发起问答之前 fail-loud——否则引用他人个人级供应商时会拿对方的 Key 跑通，事后无从发现。
        modelRegistryService.assertUsable(request.getModel(), userId, RequestUser.role());

        // @ 引用校验（同样必须在发起问答前 fail-loud）：类型合法 + 资源存在 + 在当前用户共享范围内。
        // 放在这里而不是流水线内：流水线是异步线程，异常只能变成 SSE error，用户拿不到明确的 400 语义
        List<ChatRequest.Mention> mentions = validateMentions(request.getMentions(), httpRequest);

        // 超时配置化（chat.sseTimeoutMs，默认 5 分钟）；超时由 RagService.onTimeout 先发 warn 再 dispose（fail-loud）
        long sseTimeout = configService.getLong("chat.sseTimeoutMs");
        if (sseTimeout <= 0) sseTimeout = 300000L;
        SseEmitter emitter = new SseEmitter(sseTimeout);
        // regenerate：重新生成/自动重试的重发——该问题的用户消息已随上一轮请求即时落库，跳过重复落库
        ragService.chat(sessionId, question, images, attachments, request.getSkills(), mentions,
                request.isDeepThink(), request.getAgentId(), request.getModel(), userId, emitter,
                false, request.isRegenerate(), request.getReplaceMessageId());
        return emitter;
    }

    /**
     * @ 引用校验（fail-loud）：类型合法、资源存在、当前用户在共享范围内。
     * 不可见按「不存在」处理（不泄露存在性，与文档接口同一口径）；展示名以库里实名为准，不信任客户端传值。
     * 返回归一后的引用列表（null 表示无引用）。
     */
    private List<ChatRequest.Mention> validateMentions(List<ChatRequest.Mention> mentions,
                                                       HttpServletRequest httpRequest) {
        if (mentions == null || mentions.isEmpty()) return null;
        int max = Math.max(1, configService.getInt("chat.maxMentionsPerMessage", 10));
        if (mentions.size() > max) {
            throw new BizException("一次最多引用 " + max + " 个知识库/文档");
        }
        boolean admin = adminGuard.isAdmin(httpRequest);
        var kind = com.wisesoft.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE;
        var principal = new com.wisesoft.ai.service.ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
        List<ChatRequest.Mention> out = new java.util.ArrayList<>();
        for (ChatRequest.Mention m : mentions) {
            if (m == null) continue;
            String type = m.getType() == null ? "" : m.getType().trim().toLowerCase();
            String id = m.getId() == null ? "" : m.getId().trim();
            if (id.isEmpty()) {
                throw new BizException("引用项缺少资源 ID");
            }
            ChatRequest.Mention normalized = new ChatRequest.Mention();
            if ("kb".equals(type)) {
                var kb = kbService.get(id);
                if (kb == null || (!admin
                        && !visibility.canRead(principal, kb.getShareConfig(), kb.getCreatedBy(), kind))) {
                    throw new BizException("引用的知识库不存在或无权访问");
                }
                normalized.setType("kb");
                normalized.setId(kb.getId());
                normalized.setName(kb.getName());
            } else if ("doc".equals(type)) {
                var doc = documentMapper.selectById(id);
                if (doc == null || (!admin
                        && !visibility.canRead(principal, doc.getShareConfig(), doc.getCreatedBy(), kind))) {
                    throw new BizException("引用的文档不存在或无权访问");
                }
                // 文档所属库同样须可见（与文档列表接口的两级过滤一致）
                if (!admin && doc.getKbId() != null) {
                    var kb = kbService.get(doc.getKbId());
                    if (kb == null || !visibility.canRead(principal, kb.getShareConfig(), kb.getCreatedBy(), kind)) {
                        throw new BizException("引用的文档不存在或无权访问");
                    }
                }
                normalized.setType("doc");
                normalized.setId(doc.getId());
                normalized.setName(doc.getFileName());
                normalized.setKbId(doc.getKbId() == null ? kbService.defaultId() : doc.getKbId());
            } else {
                throw new BizException("不支持的引用类型：" + m.getType() + "（仅支持 kb / doc）");
            }
            out.add(normalized);
        }
        return out.isEmpty() ? null : out;
    }

    @Operation(summary = "工具执行审批", description = "裁决智能体的工具执行请求（approval_required 事件下发）：仅本轮用户本人可批，"
            + "拒绝/超时后模型会收到未执行错误并继续回答；审批挂起为内存态，刷新页面即失效")
    @PostMapping("/tool-approval/{approvalId}")
    public ResultJson resolveToolApproval(
            @Parameter(description = "审批请求 ID（approval_required 事件下发）") @PathVariable("approvalId") String approvalId,
            @RequestBody Map<String, Boolean> body) {
        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        boolean ok = ragService.resolveApproval(approvalId, approved, com.wisesoft.ai.util.RequestUser.uid());
        if (!ok) return ResultJson.error("审批请求不存在或已失效（可能已超时）");
        return ResultJson.ok(approved ? "已批准" : "已拒绝");
    }

    @Operation(summary = "会话列表", description = "列出当前用户的会话（含 anonymous 历史兼容池；置顶优先、按更新时间倒序）；支持 keyword 按标题或消息内容模糊搜索")
    @GetMapping("/sessions")
    public ResultJson listSessions(
            @Parameter(description = "搜索关键词（可选，按标题/消息内容模糊匹配）")
            @RequestParam(value = "keyword", required = false) String keyword,
            HttpServletRequest httpRequest) {
        List<SessionInfo> sessions = sessionService.listSessions(RequestUser.uid(), keyword);
        return ResultJson.ok(sessions);
    }

    @Operation(summary = "置顶/取消置顶会话", description = "设置会话置顶状态，置顶会话排在列表最前")
    @PutMapping("/session/{sessionId}/pin")
    public ResultJson updatePin(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest httpRequest) {
        boolean pinned = Boolean.TRUE.equals(body.get("pinned"));
        sessionService.updatePin(RequestUser.uid(), sessionId, pinned);
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "收藏/取消收藏会话", description = "设置会话收藏状态，收藏会话可在侧边栏筛选")
    @PutMapping("/session/{sessionId}/favorite")
    public ResultJson updateFavorite(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest httpRequest) {
        boolean favorite = Boolean.TRUE.equals(body.get("favorite"));
        sessionService.updateFavorite(RequestUser.uid(), sessionId, favorite);
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "重命名会话", description = "修改会话标题（≤50 字；校验归属）")
    @PutMapping("/session/{sessionId}/rename")
    public ResultJson renameSession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        String title = body == null ? null : body.get("title");
        if (title == null || title.isBlank()) {
            throw new BizException("标题不能为空");
        }
        if (title.trim().length() > 50) {
            throw new BizException("标题过长（最多 50 字）");
        }
        sessionService.renameSession(RequestUser.uid(), sessionId, title);
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "会话历史", description = "获取指定会话的完整对话历史（含图片与引用来源；校验会话归属）")
    @GetMapping("/session/{sessionId}")
    public ResultJson getHistory(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        List<Map<String, Object>> history = sessionService.getHistory(sessionId);
        // 历史图片存的是原始 URL，响应时动态签名（避免签名过期导致恢复会话图片 401）
        // 同步带回各回答消息的既有评价（fb）：前端"有/没帮助单选锁定"依赖此状态，刷新后不丢
        List<String> msgIds = history.stream()
                .map(m -> m.get("messageId") == null ? null : String.valueOf(m.get("messageId")))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, Integer> ratings = qaLogService.loadFeedbackRatings(msgIds);
        for (Map<String, Object> msg : history) {
            Object imgs = msg.get("images");
            if (imgs instanceof List<?> list && !list.isEmpty()) {
                msg.put("images", list.stream()
                        .map(String::valueOf)
                        .map(imageUrlSigner::signUrl)
                        .toList());
            }
            // 引用来源内的图片同样动态签名（引用弹窗直接加载；原始 URL 存库，避免签名过期 401）
            Object srcs = msg.get("sources");
            if (srcs instanceof List<?> srcList && !srcList.isEmpty()
                    && srcList.get(0) instanceof Map) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typed = (List<Map<String, Object>>) srcList;
                msg.put("sources", imageUrlSigner.signSourceImages(typed));
            }
            // 产物卡片 URL 动态签名（下载/预览走静态资源鉴权；原始 URL 存库）
            Object arts = msg.get("artifacts");
            if (arts instanceof List<?> artList && !artList.isEmpty()
                    && artList.get(0) instanceof Map) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typedArts = (List<Map<String, Object>>) artList;
                for (Map<String, Object> a : typedArts) {
                    Object u = a.get("url");
                    if (u != null) a.put("url", imageUrlSigner.signUrl(String.valueOf(u)));
                }
            }
            Object mid = msg.get("messageId");
            if (mid != null) {
                Integer fb = ratings.get(String.valueOf(mid));
                if (fb != null) msg.put("fb", fb);
            }
        }
        return ResultJson.ok(history);
    }

    @Operation(summary = "删除会话", description = "删除指定会话（MySQL 软删除 + Redis 清理；校验会话归属）")
    @DeleteMapping("/session/{sessionId}")
    public ResultJson deleteSession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.deleteSession(RequestUser.uid(), sessionId);
        return ResultJson.ok("会话已删除");
    }

    @Operation(summary = "清空会话", description = "清空当前用户名下的全部会话数据")
    @DeleteMapping("/sessions")
    public ResultJson clearAllSessions(HttpServletRequest httpRequest) {
        sessionService.clearAll(RequestUser.uid());
        return ResultJson.ok("会话已清空");
    }

    @Operation(summary = "批量删除会话", description = "按 ID 列表软删除多个会话（逐个校验归属，单条失败不中断）；返回成功删除数")
    @PostMapping("/sessions/batch-delete")
    public ResultJson batchDeleteSessions(
            @Parameter(description = "{\"ids\": [\"会话ID\", ...]}") @org.springframework.web.bind.annotation.RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        List<String> ids = body.get("ids") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        if (ids.isEmpty()) throw new BizException("ids 不能为空");
        int deleted = sessionService.batchDelete(RequestUser.uid(), ids);
        return ResultJson.ok(Map.of("deleted", deleted));
    }

    @Operation(summary = "新建会话", description = "创建一个新的对话会话（归属当前用户），返回会话 ID")
    @PostMapping("/session/new")
    public ResultJson newSession(HttpServletRequest httpRequest) {
        return ResultJson.ok(Map.of("sessionId", sessionService.createSession(RequestUser.uid())));
    }

    @Operation(summary = "知识块详情", description = "获取指定知识块的全文内容（引用溯源：弹窗展示来源知识块全文与图片）")
    @GetMapping("/knowledge/{knowledgeId}")
    public ResultJson knowledgeDetail(
            @Parameter(description = "知识块 ID") @PathVariable("knowledgeId") String knowledgeId) {
        Knowledge k = knowledgeMapper.selectById(knowledgeId);
        if (k == null) {
            return ResultJson.error(404, "知识块不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("docId", k.getDocId());
        m.put("title", k.getTitle());
        m.put("titlePath", k.getTitlePath());
        m.put("content", k.getContent());
        m.put("chunkIndex", k.getChunkIndex());
        // 知识块原文内嵌图片：原始 URL 存库，响应时动态签名（否则引用弹窗图片 401）
        List<String> kImgs = (k.getImages() == null || k.getImages().isBlank())
                ? List.of() : JSON.parseArray(k.getImages(), String.class);
        m.put("images", imageUrlSigner.signUrls(kImgs));
        return ResultJson.ok(m);
    }

    @Operation(summary = "删除一轮对话", description = "按对话组删除：指定该轮回答（assistant 消息）ID，连同其前面的用户问题一起软删除，并清理 Redis 兜底缓存。立即生效，前端 5 秒内可调撤销接口恢复")
    @DeleteMapping("/message-group/{assistantMessageId}")
    public ResultJson deleteMessageGroup(
            @Parameter(description = "该轮回答（assistant 消息）ID") @PathVariable("assistantMessageId") String assistantMessageId,
            HttpServletRequest httpRequest) {
        com.wisesoft.ai.model.Message assistant = messageMapper.selectById(assistantMessageId);
        if (assistant == null) throw new BizException(404, "消息不存在");
        sessionService.assertOwned(assistant.getSessionId(), RequestUser.uid());
        int deleted = sessionService.deleteRound(assistant.getSessionId(), assistantMessageId);
        if (deleted == 0) throw new BizException(404, "消息不存在或已删除");
        return ResultJson.ok("已删除该轮对话");
    }

    @Operation(summary = "撤销删除一轮对话", description = "恢复最近一次按组删除的对话（回答 + 同组用户问题），撤销期内有效")
    @PostMapping("/message-group/undo")
    public ResultJson undoDeleteMessageGroup(
            @Parameter(description = "{\"messageId\": \"该轮回答（assistant 消息）ID\"}")
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        String messageId = body.get("messageId");
        if (messageId == null || messageId.isBlank()) throw new BizException("缺少 messageId");
        // 已软删消息的归属校验：忽略删除标记取回，会话本身仍须存在且属于当前用户
        com.wisesoft.ai.model.Message assistant = messageMapper.selectByIdIgnoreDeleted(messageId);
        if (assistant == null) throw new BizException(404, "消息不存在或已过撤销期");
        sessionService.assertOwned(assistant.getSessionId(), RequestUser.uid());
        int restored = sessionService.undoDeleteRound(assistant.getSessionId(), messageId);
        if (restored == 0) throw new BizException(410, "已过撤销期，无法恢复");
        return ResultJson.ok(Map.of("restored", restored), "已恢复该轮对话");
    }

    /** 客户端真实 IP（nginx 反代场景取 X-Forwarded-For 首段，兜底 remoteAddr） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}