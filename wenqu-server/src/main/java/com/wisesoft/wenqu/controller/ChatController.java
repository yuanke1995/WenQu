package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.agents.ToolApproval;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.BizException;
import com.wisesoft.wenqu.common.ImageProcessor;
import com.wisesoft.wenqu.common.JsonValues;
import com.wisesoft.wenqu.common.PydanticValidation;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.ArtifactService;
import com.wisesoft.wenqu.service.AttachmentService;
import com.wisesoft.wenqu.service.ChatService;
import com.wisesoft.wenqu.service.ContextCompressionService;
import com.wisesoft.wenqu.service.ConversationService;
import com.wisesoft.wenqu.service.FeedbackService;
import com.wisesoft.wenqu.service.FilePreviewService;
import com.wisesoft.wenqu.service.ModelSelectors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 对话线程 / 附件 / 反馈 / 多模态图片接口（对应参考实现 {@code server/routers/chat_router.py}，21 端点）。
 *
 * <p>逐端点对齐；pydantic 契约（模型字段约束）逐条落为控制器层 422：
 * <ul>
 *   <li>{@code ThreadCreate} / {@code ThreadUpdate} 声明 {@code extra="forbid"} → 显式拒绝未知键；
 *   <li>{@code ThreadUpdate.tool_approval_mode} 为 {@code Literal["default","always_trust"]} → 非白名单值 422
 *       {@code literal_error}；
 *   <li>查询参数约束（{@code limit ge=1 le=…}、{@code q min_length=1 max_length=200}、{@code offset ge=0}）
 *       → 各用独立错误码（{@code greater_than_equal} / {@code less_than_equal} / {@code string_too_short} /
 *       {@code string_too_long}）。
 * </ul>
 *
 * <p><b>response_model 投影</b>：参考实现多处声明 {@code response_model}，FastAPI 据此丢弃服务层返回的
 * 多余键（wire 可见契约）：
 * <ul>
 *   <li>{@code ImageUploadResponse} 只有 9 键，而 {@link ImageProcessor#processUploadedImage} 返回 10 键
 *       （多 {@code original_filename}）→ 控制器投影丢弃之；</li>
 *   <li>{@code ThreadResponse}（{@code serializeThread} 11 键）与 {@code ThreadSearchResponse}
 *       （{@code searchThreadsView} 13 键：item 含 {@code thread_id}、缺 {@code project_id}/{@code workdir_path}/
 *       {@code thread_status}）的产出键序与服务层逐字一致 —— 直接透传，<b>不额外补键</b>。
 *       参考 {@code ThreadSearchItem extends ThreadResponse} 里 {@code workdir_path} 无默认值，理论上
 *       FastAPI 会报 500；但参考服务层本就返回缺该键的 item 且无单测覆盖，属参考实现既有瑕疵，
 *       本工程不为此自设计补键（保持与参考服务层产出逐字一致）。</li>
 * </ul>
 *
 * <p><b>多态 artifact 端点</b>：{@code GET /thread/{thread_id}/artifacts/{path}} 返回三态
 * （文本/不支持预览 → {@code Map}；二进制预览 → {@link FilePreviewService.BinaryPreview}；下载 →
 * {@link ArtifactService.ArtifactDownload}），经 {@code Object} 返回 + 装配阶段分派。
 *
 * <p>平台差异（必要替换）：FastAPI 的 {@code Depends(get_required_user)} → {@link AuthGuards#requireUser()}；
 * {@code Depends(get_superadmin_user)} → {@link AuthGuards#requireSuperadmin()}。部分服务方法接收
 * {@link User} 实体，此处经 {@link UserRepository#getByUid} 由当前 uid 还原（与既有控制器先例一致）。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Tag(name = "ChatController", description = "对话线程 / 附件 / 反馈 / 多模态图片")
public class ChatController {

    private final ConversationService conversationService;
    private final ArtifactService artifactService;
    private final FeedbackService feedbackService;
    private final AttachmentService attachmentService;
    private final ChatService chatService;
    private final ContextCompressionService contextCompressionService;
    private final ModelSelectors modelSelectors;
    private final OptionsService optionsService;
    private final UserRepository userRepository;

    /** {@code ThreadCreate} 允许的键（extra="forbid"）。 */
    private static final java.util.Set<String> THREAD_CREATE_FIELDS =
            java.util.Set.of("request_id", "title", "agent_id", "metadata", "project_id");

    /** {@code ThreadUpdate} 允许的键（extra="forbid"）。 */
    private static final java.util.Set<String> THREAD_UPDATE_FIELDS =
            java.util.Set.of("title", "is_pinned", "tool_approval_mode");

    // ==================== 简单问答 ====================

    /** 调用模型进行简单问答（需要登录）。 */
    @Operation(summary = "简单问答", description = "POST /chat/call")
    @PostMapping("/call")
    public Map<String, Object> call(
            @RequestBody(required = false) Map<String, Object> body) {
        AuthGuards.requireUser();
        PydanticValidation.requireBody(body);
        String query = PydanticValidation.requireString(body, "query", PydanticValidation.NO_LENGTH_LIMIT);
        Map<String, Object> meta = PydanticValidation.optionalDict(body, "meta");

        if (JsonValues.text(meta.get("request_id")) == null || JsonValues.text(meta.get("request_id")).isEmpty()) {
            meta.put("request_id", UUID.randomUUID().toString());
        }

        Map<String, Object> options = optionsService.get(OptionsService.SYSTEM_OPTIONS);
        Object defaultModel = options.get("default_model");
        Object spec = JsonValues.or(
                JsonValues.or(meta.get("model_spec"), meta.get("model")), defaultModel);
        ModelSelectors.ChatAdapter model = modelSelectors.selectModel(String.valueOf(spec));

        ModelSelectors.GeneralResponse response = model.call(query);
        log.debug("query: {}, response: {}", query, response.content);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("response", response.content);
        result.put("request_id", meta.get("request_id"));
        return result;
    }

    // ==================== 线程 ====================

    /** 读取当前用户的线程信息、Run 与历史消息。 */
    @Operation(summary = "线程历史", description = "GET /chat/thread/{thread_id}/history")
    @GetMapping("/thread/{threadId}/history")
    public Map<String, Object> getThreadHistory(@PathVariable("threadId") String threadId) {
        String uid = AuthGuards.requireUser();
        try {
            return conversationService.getThreadHistoryView(threadId, uid);
        } catch (BizException exc) {
            throw new ApiHttpException(exc.getCode(), exc.getMessage());
        } catch (RuntimeException exc) {
            log.error("获取对话历史消息出错: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "获取对话历史消息出错: " + exc.getMessage());
        }
    }

    /** 读取超级管理员自身线程内的 Model/Tool 生命周期审计。 */
    @Operation(summary = "线程审计", description = "GET /chat/thread/{thread_id}/audits（超级管理员）")
    @GetMapping("/thread/{threadId}/audits")
    public Map<String, Object> getThreadMessageAudits(@PathVariable("threadId") String threadId) {
        String uid = AuthGuards.requireSuperadmin();
        try {
            return conversationService.getThreadMessageAuditsView(threadId, uid);
        } catch (BizException exc) {
            throw new ApiHttpException(exc.getCode(), exc.getMessage());
        } catch (RuntimeException exc) {
            log.error("获取 Message 审计出错: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "获取 Message 审计出错");
        }
    }

    /** 获取对话当前状态（需要登录）。 */
    @Operation(summary = "线程状态", description = "GET /chat/thread/{thread_id}/state")
    @GetMapping("/thread/{threadId}/state")
    public Map<String, Object> getThreadState(
            @PathVariable("threadId") String threadId,
            @RequestParam(value = "include_messages", defaultValue = "false") boolean includeMessages) {
        String uid = AuthGuards.requireUser();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(404, "用户不存在");
        }
        try {
            // 参考实现 get_agent_state_view 的 include_relations 默认 True
            return chatService.getAgentStateView(threadId, user, includeMessages, true);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (RuntimeException exc) {
            log.error("获取对话状态出错: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "获取对话状态出错: " + exc.getMessage());
        }
    }

    /** 在线程空闲时主动压缩上下文。 */
    @Operation(summary = "压缩上下文", description = "POST /chat/thread/{thread_id}/compress")
    @PostMapping("/thread/{threadId}/compress")
    public Map<String, Object> compressThreadContext(@PathVariable("threadId") String threadId) {
        String uid = AuthGuards.requireUser();
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw new ApiHttpException(404, "用户不存在");
        }
        return contextCompressionService.compressThreadContext(threadId, user);
    }

    /** 创建新对话线程（使用新存储系统）。 */
    @Operation(summary = "创建线程", description = "POST /chat/thread")
    @PostMapping("/thread")
    public Map<String, Object> createThread(@RequestBody(required = false) Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        PydanticValidation.requireBody(body);
        rejectUnknownFields(body, THREAD_CREATE_FIELDS);
        String requestId = PydanticValidation.optionalString(body, "request_id", 64);
        String title = PydanticValidation.optionalString(body, "title", PydanticValidation.NO_LENGTH_LIMIT);
        String agentId = PydanticValidation.requireString(body, "agent_id", PydanticValidation.NO_LENGTH_LIMIT);
        Map<String, Object> metadata = PydanticValidation.optionalDict(body, "metadata");
        String projectId = PydanticValidation.optionalString(body, "project_id", PydanticValidation.NO_LENGTH_LIMIT);
        return conversationService.createThreadView(agentId, requestId, title, metadata, projectId, uid);
    }

    /** 获取用户的所有对话线程（使用新存储系统）。 */
    @Operation(summary = "线程列表", description = "GET /chat/threads")
    @GetMapping("/threads")
    public List<Map<String, Object>> listThreads(
            @RequestParam(value = "agent_id", required = false) String agentId,
            @RequestParam(value = "limit", defaultValue = "100") Integer limit,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        String uid = AuthGuards.requireUser();
        checkRange(limit, 1, 500, "limit");
        checkRange(offset, 0, null, "offset");
        return conversationService.listThreadsView(agentId, uid, limit, offset);
    }

    /** 搜索当前用户的历史对话。 */
    @Operation(summary = "搜索线程", description = "GET /chat/threads/search")
    @GetMapping("/threads/search")
    public Map<String, Object> searchThreads(
            @RequestParam(value = "q") String q,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        String uid = AuthGuards.requireUser();
        checkStringLength(q, 1, 200, "q");
        checkRange(limit, 1, 50, "limit");
        checkRange(offset, 0, null, "offset");
        return conversationService.searchThreadsView(q, agentId, uid, limit, offset);
    }

    /** 删除对话线程（使用新存储系统）。 */
    @Operation(summary = "删除线程", description = "DELETE /chat/thread/{thread_id}")
    @DeleteMapping("/thread/{threadId}")
    public Map<String, Object> deleteThread(@PathVariable("threadId") String threadId) {
        String uid = AuthGuards.requireUser();
        return conversationService.deleteThreadView(threadId, uid);
    }

    /** 更新对话线程信息（使用新存储系统）。 */
    @Operation(summary = "更新线程", description = "PUT /chat/thread/{thread_id}")
    @PutMapping("/thread/{threadId}")
    public Map<String, Object> updateThread(
            @PathVariable("threadId") String threadId,
            @RequestBody(required = false) Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        PydanticValidation.requireBody(body);
        rejectUnknownFields(body, THREAD_UPDATE_FIELDS);
        String title = PydanticValidation.optionalString(body, "title", PydanticValidation.NO_LENGTH_LIMIT);
        Object isPinnedObj = PydanticValidation.optionalBoolean(body, "is_pinned", null);
        Boolean isPinned = isPinnedObj == null ? null : (Boolean) isPinnedObj;
        String toolApprovalMode = PydanticValidation.optionalString(
                body, "tool_approval_mode", PydanticValidation.NO_LENGTH_LIMIT);
        if (toolApprovalMode != null && !ToolApproval.TOOL_APPROVAL_MODES.contains(toolApprovalMode)) {
            throw PydanticValidation.validationError(
                    PydanticValidation.bodyLoc("tool_approval_mode"),
                    "Input should be '" + String.join("' or '", ToolApproval.TOOL_APPROVAL_MODES) + "'",
                    "literal_error");
        }
        return conversationService.updateThreadView(threadId, title, isPinned, toolApprovalMode, uid);
    }

    /** 记录用户已查看该线程的最新顶层 run，清除侧边栏未读状态。 */
    @Operation(summary = "标记已读", description = "POST /chat/thread/{thread_id}/viewed")
    @PostMapping("/thread/{threadId}/viewed")
    public Map<String, Object> markThreadViewed(@PathVariable("threadId") String threadId) {
        String uid = AuthGuards.requireUser();
        return conversationService.markThreadViewedView(threadId, uid);
    }

    // ==================== 附件 ====================

    /** 上传附件到 MinIO tmp，暂不关联线程。 */
    @Operation(summary = "上传临时附件", description = "POST /chat/attachments/tmp")
    @PostMapping("/attachments/tmp")
    public Map<String, Object> uploadTmpAttachment(
            @RequestPart("file") MultipartFile file) {
        String uid = AuthGuards.requireUser();
        return attachmentService.uploadTmpAttachment(file, uid);
    }

    /** 解析 tmp 附件并返回解析后的 tmp URL。 */
    @Operation(summary = "解析临时附件", description = "POST /chat/attachments/tmp/parse")
    @PostMapping("/attachments/tmp/parse")
    public Map<String, Object> parseTmpAttachment(
            @RequestBody(required = false) Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        PydanticValidation.requireBody(body);
        String objectName = PydanticValidation.requireString(body, "object_name", PydanticValidation.NO_LENGTH_LIMIT);
        String parseMethod = PydanticValidation.optionalString(
                body, "parse_method", PydanticValidation.NO_LENGTH_LIMIT);
        return attachmentService.parseTmpAttachment(objectName, parseMethod, uid);
    }

    /** 将 tmp 附件正式加入线程附件列表。 */
    @Operation(summary = "确认线程附件", description = "POST /chat/thread/{thread_id}/attachments/confirm")
    @PostMapping("/thread/{threadId}/attachments/confirm")
    public Map<String, Object> confirmTmpThreadAttachments(
            @PathVariable("threadId") String threadId,
            @RequestBody(required = false) Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        PydanticValidation.requireBody(body);
        List<Map<String, Object>> attachments = PydanticValidation.requireListOfDict(body, "attachments");
        return attachmentService.confirmTmpThreadAttachments(threadId, attachments, uid);
    }

    /** 列出当前对话线程的所有附件元信息。 */
    @Operation(summary = "线程附件列表", description = "GET /chat/thread/{thread_id}/attachments")
    @GetMapping("/thread/{threadId}/attachments")
    public Map<String, Object> listThreadAttachments(@PathVariable("threadId") String threadId) {
        String uid = AuthGuards.requireUser();
        return attachmentService.listThreadAttachments(threadId, uid);
    }

    /** 移除指定附件。 */
    @Operation(summary = "删除线程附件", description = "DELETE /chat/thread/{thread_id}/attachments/{file_id}")
    @DeleteMapping("/thread/{threadId}/attachments/{fileId}")
    public Map<String, Object> deleteThreadAttachment(
            @PathVariable("threadId") String threadId,
            @PathVariable("fileId") String fileId) {
        String uid = AuthGuards.requireUser();
        return attachmentService.deleteThreadAttachment(threadId, fileId, uid);
    }

    // ==================== 交付物 ====================

    /** 下载或预览线程文件（多态返回：文本 Map / 二进制预览 / 下载流）。 */
    @Operation(summary = "线程交付物", description = "GET /chat/thread/{thread_id}/artifacts/{path}")
    @GetMapping("/thread/{threadId}/artifacts/{*path}")
    public ResponseEntity<Object> getThreadArtifact(
            @PathVariable("threadId") String threadId,
            @PathVariable("path") String path,
            @RequestParam(value = "download", defaultValue = "false") boolean download,
            @RequestParam(value = "preview", defaultValue = "false") boolean preview) {
        String uid = AuthGuards.requireUser();
        // Spring 的 {*path} 捕获含前导斜杠（对应参考实现 FastAPI {path:path} 不含）；故剥离
        String artifactPath = path != null && path.startsWith("/") ? path.substring(1) : path;
        Object result = artifactService.resolveThreadArtifactView(threadId, uid, artifactPath, download, preview);

        if (result instanceof ArtifactService.ArtifactDownload artifactDownload) {
            // 下载：流式写出，流结束即清理临时文件
            Path tempFile = artifactDownload.tempFilePath();
            org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = outputStream -> {
                try (InputStream input = Files.newInputStream(tempFile)) {
                    input.transferTo(outputStream);
                } finally {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException cleanupError) {
                        log.warn("临时下载文件清理失败: {}", tempFile, cleanupError);
                    }
                }
            };
            String mediaType = artifactDownload.getMediaType() == null
                    ? "application/octet-stream"
                    : artifactDownload.getMediaType();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mediaType))
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            artifactDownload.contentDisposition() == null
                                    ? "attachment; filename=download"
                                    : artifactDownload.contentDisposition())
                    .body(body);
        }

        if (result instanceof FilePreviewService.BinaryPreview binaryPreview) {
            // 二进制预览：流式 + X- 头携带元数据
            byte[] content = binaryPreview.getContent();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(binaryPreview.getMediaType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, binaryPreview.contentDisposition())
                    .header(binaryPreview.previewTypeHeaderName(), binaryPreview.getPreviewType())
                    .header(binaryPreview.previewFilenameHeaderName(), binaryPreview.previewFilenameHeaderValue())
                    .body(new InputStreamResource(new java.io.ByteArrayInputStream(content)));
        }

        // 文本 / 不支持预览 → Map
        return ResponseEntity.ok(result);
    }

    /** 保存交付物到用户工作区中指定的目录。 */
    @Operation(summary = "保存交付物", description = "POST /chat/thread/{thread_id}/artifacts/save")
    @PostMapping("/thread/{threadId}/artifacts/save")
    public Map<String, Object> saveThreadArtifactToWorkspace(
            @PathVariable("threadId") String threadId,
            @RequestBody(required = false) Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        PydanticValidation.requireBody(body);
        String path = PydanticValidation.requireString(body, "path", PydanticValidation.NO_LENGTH_LIMIT);
        String destinationPath = PydanticValidation.optionalString(
                body, "destination_path", PydanticValidation.NO_LENGTH_LIMIT);
        return artifactService.saveThreadArtifactToWorkspaceView(threadId, uid, path, destinationPath);
    }

    // ==================== 消息反馈 ====================

    /** 提交消息反馈（需要登录）。 */
    @Operation(summary = "提交反馈", description = "POST /chat/message/{message_id}/feedback")
    @PostMapping("/message/{messageId}/feedback")
    public Map<String, Object> submitMessageFeedback(
            @PathVariable("messageId") Integer messageId,
            @RequestBody(required = false) Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        PydanticValidation.requireBody(body);
        String rating = PydanticValidation.requireString(body, "rating", PydanticValidation.NO_LENGTH_LIMIT);
        String reason = PydanticValidation.optionalString(body, "reason", PydanticValidation.NO_LENGTH_LIMIT);
        return feedbackService.submitMessageFeedbackView(messageId, rating, reason, uid);
    }

    /** 获取指定消息的用户反馈（需要登录）。 */
    @Operation(summary = "获取反馈", description = "GET /chat/message/{message_id}/feedback")
    @GetMapping("/message/{messageId}/feedback")
    public Map<String, Object> getMessageFeedback(@PathVariable("messageId") Integer messageId) {
        String uid = AuthGuards.requireUser();
        return feedbackService.getMessageFeedbackView(messageId, uid);
    }

    // ==================== 多模态图片 ====================

    /** 上传并处理图片，返回 base64 编码的图片数据。 */
    @Operation(summary = "上传图片", description = "POST /chat/image/upload")
    @PostMapping("/image/upload")
    public Map<String, Object> uploadImage(@RequestPart("file") MultipartFile file) {
        AuthGuards.requireUser();
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ApiHttpException(400, "只支持图片文件上传");
        }
        byte[] imageData;
        try {
            imageData = file.getBytes();
        } catch (IOException exc) {
            throw new ApiHttpException(500, "读取上传图片失败: " + exc.getMessage());
        }
        if (imageData.length > 10 * 1024 * 1024) {
            throw new ApiHttpException(400, "图片文件过大，请上传小于10MB的图片");
        }
        Map<String, Object> result = ImageProcessor.processUploadedImage(imageData, file.getOriginalFilename());
        if (!Boolean.TRUE.equals(result.get("success"))) {
            throw new ApiHttpException(400, "图片处理失败: " + result.get("error"));
        }
        log.info(
                "用户成功上传图片: {}, 尺寸: {}x{}, 格式: {}, 大小: {} bytes",
                file.getOriginalFilename(),
                result.get("width"),
                result.get("height"),
                result.get("format"),
                result.get("size_bytes"));
        // response_model=ImageUploadResponse 丢弃 original_filename（服务层多返回的键）
        result.remove("original_filename");
        return result;
    }

    // ==================== 私有工具 ====================

    /** 拒绝未声明字段（对应 pydantic {@code extra="forbid"} 的 {@code extra_forbidden}）。 */
    private static void rejectUnknownFields(Map<String, Object> body, java.util.Set<String> allowed) {
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw PydanticValidation.validationError(
                        PydanticValidation.bodyLoc(key),
                        "Extra inputs are not permitted",
                        "extra_forbidden");
            }
        }
    }

    /** 查询参数数值范围校验（pydantic {@code ge}/{@code le} → 独立错误码）。 */
    private static void checkRange(Integer value, Integer min, Integer max, String field) {
        if (min != null && value < min) {
            throw PydanticValidation.validationError(
                    PydanticValidation.bodyLoc(field),
                    "Input should be greater than or equal to " + min,
                    "greater_than_equal");
        }
        if (max != null && value > max) {
            throw PydanticValidation.validationError(
                    PydanticValidation.bodyLoc(field),
                    "Input should be less than or equal to " + max,
                    "less_than_equal");
        }
    }

    /** 查询字符串长度校验（pydantic {@code min_length}/{@code max_length}）。 */
    private static void checkStringLength(String value, int min, int max, String field) {
        if (value.length() < min) {
            throw PydanticValidation.validationError(
                    PydanticValidation.bodyLoc(field),
                    "String should have at least " + min + " characters",
                    "string_too_short");
        }
        if (value.length() > max) {
            throw PydanticValidation.validationError(
                    PydanticValidation.bodyLoc(field),
                    "String should have at most " + max + " characters",
                    "string_too_long");
        }
    }
}