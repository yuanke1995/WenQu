package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.fastjson2.JSON;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;

/**
 * 模型输入兼容性中间件（对应参考实现 {@code agents/middlewares/model_input.py} 的
 * {@code ImageInputCompatibilityMiddleware}）。
 *
 * <p>职责（与参考实现逐条对位）：
 * <ul>
 *   <li>把 OpenAI 兼容协议下 {@code read_file} 返回的图片从 ToolMessage 里摘出来，
 *       追加成一条 {@code HumanMessage}（{@link #bridgeOpenAiToolImages}）。</li>
 *   <li>模型明确拒绝图片输入时，降级为 {@code ocr_parse_file} 工具调用
 *       （{@link #ocrFallbackResponse}）。</li>
 *   <li>把「图片能力拒绝」与「其他 4xx 错误」区分开（{@link #isImageInputRejection}），
 *       后者照旧上抛（参考单测 {@code test_does_not_mask_unrelated_provider_errors_when_image_is_present}
 *       与 {@code test_does_not_report_malformed_image_as_unsupported_model} 锁死该口径）。</li>
 * </ul>
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li>{@code request.model} → 本工程 {@link ModelRequest} <b>无 model 字段</b>（同
 *       {@link ContextAwareInterceptor} 的既有结论）。参考实现的
 *       {@code isinstance(request.model, ChatOpenAI)} 判定因此改由
 *       {@link #isOpenAiCompatModel(ModelRequest)} 承载：默认读 request context 的
 *       {@code openai_compat_model}（Boolean），由构图方按实际 ChatModel 实现注入。</li>
 *   <li>{@code content_blocks} → Spring AI 消息没有该概念，用 {@link #contentBlocks(Message)}
 *       归一化（ToolMessage 的每条 response 解析为 block；{@link MediaContent} 的 media
 *       映射为 {@code image} block）。</li>
 *   <li>{@code additional_kwargs["read_file_path"]} → {@link Message#getMetadata()} 的同名键。</li>
 *   <li>{@code ModelResponse(result=[AIMessage(...)])}} → {@link ModelResponse#of(AssistantMessage)}。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>{@code ocr_parse_file} 未注册可执行实例</b>：该工具在参考实现位于
 *       {@code agents/toolkits/buildin/tools.py}，本体依赖沙盒（{@code backends/sandbox}
 *       **已随 §三 backends 落地**，见 {@code agents/backends/sandbox/}；原文写「未搬」，
 *       2026-09-20 更正 → 是「未接线」）。本类只保留工具名常量 {@link #OCR_TOOL_NAME} 与
 *       {@link #getToolNames()} 的声明口径（与参考实现 {@code tools = [ocr_parse_file]} 对位），
 *       不注册可执行实例 —— 与 {@code dify} / {@code notion} / {@code skill_remote_install} 同口径。</li>
 *   <li><b>图片块以 {@link Media} 承载</b>：参考实现把原始 dict 直接塞进
 *       {@code HumanMessage(content_blocks=[...])}；本工程 {@link UserMessage} 只能带
 *       {@link Media}，故 {@link #toMedia(Map)} 负责把 {@code base64}/{@code url} 转成 Media，
 *       无法转换的块被跳过（参考实现无此限制）。</li>
 *   <li><b>{@code asyncio} 版本未保留</b>：{@code awrap_model_call} 与 {@code wrap_model_call}
 *       逻辑完全相同，本类只保留同步链路。</li>
 * </ol>
 */
public class ImageInputCompatibilityMiddleware extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ImageInputCompatibilityMiddleware.class);

    /** 参考实现 {@code ocr_parse_file} 工具名（逐字）。 */
    public static final String OCR_TOOL_NAME = "ocr_parse_file";

    /** 参考实现 {@code _TOOL_IMAGE_USER_TEXT}（逐字）。 */
    public static final String TOOL_IMAGE_USER_TEXT =
            "Images returned by read_file are attached below. Inspect them when answering.";

    /** 参考实现 {@code _IMAGE_ERROR_TERMS}（逐字、顺序一致）。 */
    public static final List<String> IMAGE_ERROR_TERMS = List.of("image", "vision", "multimodal", "multi-modal");

    /** 参考实现 {@code _REJECTION_TERMS}（逐字、顺序一致）。 */
    public static final List<String> REJECTION_TERMS = List.of(
            "does not support",
            "no endpoints found that support",
            "not allowed",
            "not a vlm",
            "not supported",
            "text-only prompts",
            "unsupported");

    /** 参考实现 {@code status_code not in {400, 404, 415, 422}} 的判定集合。 */
    public static final Set<Integer> REJECTION_STATUS_CODES = Set.of(400, 404, 415, 422);

    /** 参考实现判定「图片能力拒绝」时读的状态码属性名（逐字）。 */
    static final String STATUS_CODE_ATTR = "status_code";

    /** 参考实现 {@code message.additional_kwargs["read_file_path"]} 的键（逐字）。 */
    static final String READ_FILE_PATH_KEY = "read_file_path";

    /** 参考实现无 OCR 路径时的兜底文案（逐字）。 */
    static final String NO_OCR_PATH_TEXT = "当前模型无法读取图片，且没有可供 OCR 工具解析的文件路径。";

    /** 参考实现触发 OCR 回退时的文案（逐字）。 */
    static final String OCR_FALLBACK_TEXT = "当前模型不支持图片输入，正在改用 OCR 工具提取图片文字。";

    /** 参考实现 OCR 回退时工具消息的占位文案（逐字）。 */
    static final String OCR_FALLBACK_REQUESTED_HINT = "OCR fallback was requested for this image.";

    /** 参考实现图片外挂时的占位文案（逐字）。 */
    static final String IMAGE_ATTACHED_HINT = "The image content is attached in the following user message for visual inspection.";

    @Override
    public String getName() {
        return "model_input";
    }

    /**
     * 参考实现 {@code tools = [ocr_parse_file]} 的声明口径。
     *
     * <p>见类注释「能力差异 1」：工具本体依赖未搬的沙盒后端，此处只给名字。
     */
    public List<String> getToolNames() {
        return List.of(OCR_TOOL_NAME);
    }

    /** 对应参考实现 {@code wrap_model_call}：桥接图片 + 拒绝时降级 OCR。 */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<Message> messages = request == null || request.getMessages() == null ? List.of() : request.getMessages();
        List<String> imagePaths = readFileImagePaths(messages);
        ModelRequest bridgedRequest = bridgeOpenAiToolImages(request);
        try {
            return handler.call(bridgedRequest);
        } catch (RuntimeException exc) {
            if (hasImage(messagesOf(bridgedRequest)) && isImageInputRejection(exc)) {
                log.debug("ImageInputCompatibilityMiddleware: translate image rejection into ocr fallback: {}",
                        exc.getMessage());
                return ocrFallbackResponse(imagePaths);
            }
            throw exc;
        }
    }

    // ==================== 图片桥接（纯函数） ====================

    /**
     * 对应参考实现 {@code _bridge_openai_tool_images}。
     *
     * <p>把 OpenAI 兼容协议下 read_file 返回的图片从 ToolMessage 摘出，追加为一条
     * {@link UserMessage}；ToolMessage 本身替换为纯文本（保留 tool_call_id）。
     */
    ModelRequest bridgeOpenAiToolImages(ModelRequest request) {
        if (request == null || !isOpenAiCompatModel(request)) {
            return request;
        }
        List<Message> messages = messagesOf(request);
        Map<String, Integer> latestOcrCallByPath = new LinkedHashMap<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (!(message instanceof AssistantMessage assistant)) {
                continue;
            }
            List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
            if (toolCalls == null) {
                continue;
            }
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                if (toolCall == null || !OCR_TOOL_NAME.equals(toolCall.name())) {
                    continue;
                }
                Map<String, Object> args = parseObject(toolCall.arguments());
                Object filePath = args.get("file_path");
                if (filePath instanceof String path && !path.isEmpty()) {
                    latestOcrCallByPath.put(path, index);
                }
            }
        }

        List<Message> bridgedMessages = new ArrayList<>();
        List<Map<String, Object>> pendingImages = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                flushPendingImages(bridgedMessages, pendingImages);
                bridgedMessages.add(message);
                continue;
            }
            List<Map<String, Object>> blocks = contentBlocks(toolMessage);
            List<Map<String, Object>> imageBlocks = new ArrayList<>();
            for (Map<String, Object> block : blocks) {
                if ("image".equals(block.get("type"))) {
                    imageBlocks.add(block);
                }
            }
            if (imageBlocks.isEmpty()) {
                bridgedMessages.add(toolMessage);
                continue;
            }
            Object imagePath = toolMessage.getMetadata().get(READ_FILE_PATH_KEY);
            boolean ocrFallbackRequested = imagePath instanceof String path
                    && latestOcrCallByPath.getOrDefault(path, -1) > index;
            if (!ocrFallbackRequested) {
                pendingImages.addAll(imageBlocks);
            }
            String text = joinTextBlocks(blocks);
            if (text.isEmpty()) {
                text = "read_file returned " + imageBlocks.size() + " image(s). "
                        + (ocrFallbackRequested ? OCR_FALLBACK_REQUESTED_HINT : IMAGE_ATTACHED_HINT);
            }
            bridgedMessages.add(replaceToolMessageContent(toolMessage, text));
        }
        flushPendingImages(bridgedMessages, pendingImages);

        if (bridgedMessages.size() == messages.size() && sameReferences(bridgedMessages, messages)) {
            return request;
        }
        return ModelRequest.builder(request).messages(bridgedMessages).build();
    }

    /** 对应参考实现 {@code _read_file_image_paths}。 */
    List<String> readFileImagePaths(List<Message> messages) {
        List<String> paths = new ArrayList<>();
        if (messages == null) {
            return paths;
        }
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolMessage)) {
                continue;
            }
            boolean hasImage = false;
            for (Map<String, Object> block : contentBlocks(toolMessage)) {
                if ("image".equals(block.get("type"))) {
                    hasImage = true;
                    break;
                }
            }
            if (!hasImage) {
                continue;
            }
            Object path = toolMessage.getMetadata().get(READ_FILE_PATH_KEY);
            if (path instanceof String value && !value.isEmpty() && !paths.contains(value)) {
                paths.add(value);
            }
        }
        return paths;
    }

    /** 对应参考实现 {@code _ocr_fallback_response}。 */
    ModelResponse ocrFallbackResponse(List<String> imagePaths) {
        if (imagePaths == null || imagePaths.isEmpty()) {
            return ModelResponse.of(new AssistantMessage(NO_OCR_PATH_TEXT));
        }
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (String path : imagePaths) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("file_path", path);
            toolCalls.add(new AssistantMessage.ToolCall(
                    "call_ocr_" + UUID.randomUUID().toString().replace("-", ""),
                    "function",
                    OCR_TOOL_NAME,
                    JSON.toJSONString(args)));
        }
        return ModelResponse.of(AssistantMessage.builder()
                .content(OCR_FALLBACK_TEXT)
                .toolCalls(toolCalls)
                .build());
    }

    /** 对应参考实现 {@code _has_image}。 */
    boolean hasImage(List<Message> messages) {
        if (messages == null) {
            return false;
        }
        for (Message message : messages) {
            for (Map<String, Object> block : contentBlocks(message)) {
                Object type = block.get("type");
                if ("image".equals(type) || "image_url".equals(type) || "input_image".equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 对应参考实现 {@code _is_image_input_rejection}。 */
    boolean isImageInputRejection(Throwable exc) {
        if (exc == null) {
            return false;
        }
        Integer statusCode = statusCodeOf(exc);
        boolean statusMatches = statusCode != null && REJECTION_STATUS_CODES.contains(statusCode);
        if (!statusMatches && !(exc instanceof IllegalArgumentException)) {
            return false;
        }
        String detail = (exc.getMessage() == null ? exc.toString() : exc.getMessage()).toLowerCase();
        boolean hasImageTerm = false;
        for (String term : IMAGE_ERROR_TERMS) {
            if (detail.contains(term)) {
                hasImageTerm = true;
                break;
            }
        }
        boolean hasRejectionTerm = false;
        for (String term : REJECTION_TERMS) {
            if (detail.contains(term)) {
                hasRejectionTerm = true;
                break;
            }
        }
        return hasImageTerm && hasRejectionTerm;
    }

    // ==================== 承载点扩展 ====================

    /**
     * 参考实现 {@code isinstance(request.model, ChatOpenAI)} 的对位判定。
     *
     * <p>见类注释「必要替换 1」：{@link ModelRequest} 无 model 字段，默认实现读 request context
     * 的 {@code openai_compat_model}（Boolean）。构图方按实际 ChatModel 注入；子类可覆写。
     */
    protected boolean isOpenAiCompatModel(ModelRequest request) {
        Map<String, Object> context = request == null ? null : request.getContext();
        Object value = context == null ? null : context.get("openai_compat_model");
        return Boolean.TRUE.equals(value);
    }

    // ==================== 消息归一化小工具 ====================

    /** 把任意 Spring AI 消息归一化为 {@code content_blocks} 形态（见类注释「必要替换 2」）。 */
    static List<Map<String, Object>> contentBlocks(Message message) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (message == null) {
            return blocks;
        }
        if (message instanceof ToolResponseMessage toolMessage) {
            for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                Map<String, Object> parsed = parseObject(response.responseData());
                if (parsed.get("type") != null) {
                    blocks.add(parsed);
                    continue;
                }
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "text");
                block.put("text", response.responseData() == null ? "" : response.responseData());
                blocks.add(block);
            }
            return blocks;
        }
        if (message instanceof MediaContent mediaContent && mediaContent.getMedia() != null) {
            for (Media media : mediaContent.getMedia()) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "image");
                block.put("mime_type", media.getMimeType() == null ? null : media.getMimeType().toString());
                block.put("media", media);
                blocks.add(block);
            }
        }
        String text = message.getText();
        if (text != null && !text.isEmpty()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "text");
            block.put("text", text);
            blocks.add(block);
        }
        return blocks;
    }

    /** 把图片 block 转成 {@link Media}（见类注释「能力差异 2」；无法转换时返回 null）。 */
    static Media toMedia(Map<String, Object> block) {
        Object media = block.get("media");
        if (media instanceof Media value) {
            return value;
        }
        String mimeType = block.get("mime_type") == null ? "application/octet-stream"
                : String.valueOf(block.get("mime_type"));
        Object base64 = block.get("base64");
        if (base64 instanceof String value && !value.isEmpty()) {
            try {
                return new Media(MimeType.valueOf(mimeType),
                        new ByteArrayResource(Base64.getDecoder().decode(value)));
            } catch (RuntimeException exc) {
                return null;
            }
        }
        Object url = block.get("url") != null ? block.get("url") : block.get("uri");
        if (url instanceof String value && !value.isEmpty()) {
            try {
                return new Media(MimeType.valueOf(mimeType), URI.create(value));
            } catch (RuntimeException exc) {
                return null;
            }
        }
        return null;
    }

    /** 从异常上提取 HTTP 状态码（参考实现读 {@code status_code} / {@code response.status_code}）。 */
    static Integer statusCodeOf(Throwable exc) {
        Object value = invokeNoArg(exc, "getStatusCode", "statusCode", "getStatus");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            Object nested = invokeNoArg(value, "value", "getStatusCode");
            if (nested instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }

    static String joinTextBlocks(List<Map<String, Object>> blocks) {
        List<String> texts = new ArrayList<>();
        for (Map<String, Object> block : blocks) {
            if ("text".equals(block.get("type")) && block.get("text") instanceof String text && !text.isEmpty()) {
                texts.add(text);
            }
        }
        return String.join("\n", texts);
    }

    private void flushPendingImages(List<Message> bridgedMessages, List<Map<String, Object>> pendingImages) {
        if (pendingImages.isEmpty()) {
            return;
        }
        List<Media> mediaList = new ArrayList<>();
        for (Map<String, Object> block : pendingImages) {
            Media media = toMedia(block);
            if (media != null) {
                mediaList.add(media);
            }
        }
        bridgedMessages.add(UserMessage.builder()
                .text(TOOL_IMAGE_USER_TEXT)
                .media(mediaList)
                .build());
        pendingImages.clear();
    }

    /** 复制 ToolMessage 并替换其文本内容（保留 id/name/metadata）。 */
    private static ToolResponseMessage replaceToolMessageContent(ToolResponseMessage message, String content) {
        List<ToolResponseMessage.ToolResponse> responses = message.getResponses();
        List<ToolResponseMessage.ToolResponse> replaced = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse response : responses) {
            replaced.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), content));
        }
        return ToolResponseMessage.builder()
                .responses(replaced)
                .metadata(new LinkedHashMap<>(message.getMetadata()))
                .build();
    }

    private static List<Message> messagesOf(ModelRequest request) {
        List<Message> messages = request == null ? null : request.getMessages();
        return messages == null ? List.of() : messages;
    }

    private static boolean sameReferences(List<Message> left, List<Message> right) {
        for (int i = 0; i < left.size(); i++) {
            if (left.get(i) != right.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> parseObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(raw);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (RuntimeException exc) {
            return new LinkedHashMap<>();
        }
    }

    private static Object invokeNoArg(Object target, String... methodNames) {
        if (target == null) {
            return null;
        }
        for (String name : methodNames) {
            try {
                Method method = target.getClass().getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 按下一个候选名继续尝试（参考实现是 getattr 取值，取不到即为 None）
            }
        }
        return null;
    }
}
