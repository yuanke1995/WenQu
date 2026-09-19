package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.wisesoft.wenqu.agents.BaseContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 会话摘要中间件（对应参考实现 {@code agents/middlewares/summary.py} 的
 * {@code YuxiSummarizationMiddleware}，类名去掉参考实现品牌前缀）。
 *
 * <p>参考实现继承第三方 {@code deepagents.middleware.summarization.SummarizationMiddleware}，
 * 本工程没有该框架，故按既有口径处理：<b>确定性部分逐字照搬、框架提供的部分声明为接口并标注能力差异</b>
 * （与 {@code dify} / {@code notion} / {@code sandbox} / {@code skill_remote_install} 同口径）。
 *
 * <p>已逐字照搬的确定性部分：
 * <ul>
 *   <li>工具结果落盘 + 预览：{@link #buildToolResultPreview} / {@link #structuredSearchPreview} /
 *       {@link #genericToolResultPreview} / {@link #clipSearchContent} / {@link #toolResultPath} /
 *       {@link #replaceToolMessageContent} / {@link #shouldOffloadToolMessage}。</li>
 *   <li>工具入参截断：{@link #truncateToolCallArgs} / {@link #truncateProviderToolCalls} /
 *       {@link #truncateStringArg}。</li>
 *   <li>触发判定：{@link #entryTriggerTokens} / {@link #triggerClauseMet} / {@link #shouldSummarize}。</li>
 *   <li>状态更新与事件：{@link #buildStateUpdate} / {@link #buildSummaryEvent} / {@link #reportOffloadResult}。</li>
 * </ul>
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li>{@code _TOOL_RESULT_SAVED_MARKER}（参考实现含品牌词）→ {@link #TOOL_RESULT_SAVED_MARKER}
 *       ＝ {@code wenqu_tool_result_saved}（内部标记，不落库，重命名无副作用）。</li>
 *   <li>{@code backend.write(path, content)} → {@link ToolResultBackend} 端口（返回 error 文本，
 *       {@code null} 表示成功）。</li>
 *   <li>{@code count_tokens_approximately} → {@link TokenUsageMiddleware#countTokensApproximately(List)}
 *       （本工程已有等价实现）。</li>
 *   <li>{@code get_stream_writer()} → {@link CompressionEventSink} 端口。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>摘要生成与历史落盘未搬</b>：{@code _create_summary} / {@code _offload_to_backend} /
 *       {@code _determine_cutoff_index} / {@code _partition_messages} / {@code _offload_inline_media}
 *       均由 {@code SummarizationMiddleware} 基类提供（含 {@code CompositeBackend} 与
 *       {@code ContextSize} 语义）。本类把它们收敛为 {@link SummaryGenerator} 与
 *       {@link HistoryOffloader} 两个接口；未注入时 {@link #interceptModel} 只做
 *       「入参截断 + 工具结果压缩」后交给 handler（压缩本身是确定性的、可独立生效）。</li>
 *   <li><b>{@code ContextOverflowError} 无对位</b>：参考实现捕获该异常触发溢出裁剪；本工程按
 *       {@link #isContextOverflow} 判定（默认识别 {@code NonTransientAiException} 且消息含
 *       context/token 类关键词），可覆写。</li>
 *   <li><b>Command 状态写回</b>：参考实现返回
 *       {@code ExtendedModelResponse(command=Command(update={...}))}；本工程
 *       {@link ModelResponse} 无此能力，更新由 {@link #pendingStateUpdate} 经构图方落 state
 *       （与 {@code TokenUsageMiddleware}/{@code SubAgentMiddleware} 同一解法）。</li>
 *   <li><b>{@code aforce_summarize} 未搬</b>：主动压缩依赖基类的历史落盘与
 *       {@code _aclip_overflow_tail}，见能力差异 1。</li>
 * </ol>
 */
public class SummaryMiddleware extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SummaryMiddleware.class);

    /** 参考实现 {@code _APPROX_CHARS_PER_TOKEN}。 */
    public static final int APPROX_CHARS_PER_TOKEN = 4;

    /** 参考实现 {@code _DEFAULT_SUMMARY_TOOL_RESULT_LIMIT_TOKENS}。 */
    public static final int DEFAULT_SUMMARY_TOOL_RESULT_LIMIT_TOKENS = 300;

    /** 参考实现 {@code _DEFAULT_TOOL_ARG_MAX_LENGTH}。 */
    public static final int DEFAULT_TOOL_ARG_MAX_LENGTH = 2000;

    /** 参考实现 {@code _TRUNCATED_TOOL_ARG_TEXT}（逐字）。 */
    public static final String TRUNCATED_TOOL_ARG_TEXT = "...(argument truncated for context view)";

    /** 参考实现 {@code _TOOL_RESULT_SAVED_MARKER}（品牌词已替换，见类注释「必要替换 1」）。 */
    public static final String TOOL_RESULT_SAVED_MARKER = "wenqu_tool_result_saved";

    /** 参考实现 {@code _STRUCTURED_SEARCH_TOOL_NAMES}。 */
    public static final Set<String> STRUCTURED_SEARCH_TOOL_NAMES = Set.of("query_kb", "web_search");

    /** 参考实现 {@code _SEARCH_CONTENT_KEYS}（顺序敏感）。 */
    public static final List<String> SEARCH_CONTENT_KEYS = List.of("content", "text", "snippet", "summary");

    /** 参考实现裁剪预览里的省略符（逐字）。 */
    static final String CLIP_MARKER = "…";

    /** 参考实现 {@code _TOOL_RESULT_PATH} 的默认工具名。 */
    static final String DEFAULT_TOOL_RESULT_NAME = "tool-result";

    private final ToolResultBackend backend;
    private final SummaryGenerator summaryGenerator;
    private final HistoryOffloader historyOffloader;
    private final CompressionEventSink eventSink;
    private final Integer toolResultOffloadTokenLimit;
    private final int toolArgMaxLength;

    /** 待落 state 的摘要更新（见类注释「能力差异 3」）。 */
    private final Map<String, Object> pendingStateUpdate = new LinkedHashMap<>();

    public SummaryMiddleware(
            ToolResultBackend backend,
            SummaryGenerator summaryGenerator,
            HistoryOffloader historyOffloader,
            CompressionEventSink eventSink,
            Integer toolResultOffloadTokenLimit,
            int toolArgMaxLength) {
        this.backend = backend;
        this.summaryGenerator = summaryGenerator;
        this.historyOffloader = historyOffloader;
        this.eventSink = eventSink;
        this.toolResultOffloadTokenLimit = toolResultOffloadTokenLimit;
        this.toolArgMaxLength = toolArgMaxLength;
    }

    /** 取走并清空待落 state 的更新（由构图方调用）。 */
    public Map<String, Object> drainStateUpdate() {
        Map<String, Object> drained = new LinkedHashMap<>(pendingStateUpdate);
        pendingStateUpdate.clear();
        return drained;
    }

    /**
     * 按 Agent 运行时配置创建自动与主动压缩共用的摘要器（对应参考实现
     * {@code agents/middlewares/summary.py} 的 {@code create_summary_middleware_from_context}）。
     *
     * <h3>逐参对齐</h3>
     * <table border="1">
     *   <caption>参考实现 → 本工程</caption>
     *   <tr><th>{@code create_summary_middleware_from_context} 参数</th><th>本工程取值处</th></tr>
     *   <tr><td>{@code trigger=("tokens", summary_threshold * 1024)}</td>
     *       <td>不在此处传参 —— {@link #thresholdClauses(ModelRequest)} 运行时从
     *           {@code BaseContext.summary_threshold} 换算（同一换算口径：KB × 1024）</td></tr>
     *   <tr><td>{@code keep=("messages", summary_keep_messages)}</td>
     *       <td>同上口径：由 context 的 {@code summary_keep_messages} 承载</td></tr>
     *   <tr><td>{@code summary_prompt=summary_prompt or DEFAULT_YUXI_SUMMARY_PROMPT}</td>
     *       <td>{@code BaseContext.summary_prompt}（默认值即
     *           {@link BaseContext#DEFAULT_SUMMARY_PROMPT}）</td></tr>
     *   <tr><td>{@code trim_tokens_to_summarize=trigger_tokens}</td>
     *       <td>摘要/落盘未注入（见类注释能力差异 1），该参数无消费方，不传</td></tr>
     *   <tr><td>{@code tool_result_offload_token_limit=summary_tool_result_token_limit}</td>
     *       <td>{@link #createFromContext} 的第三个观测量，逐字取 context 同名键
     *           （默认 {@link #DEFAULT_SUMMARY_TOOL_RESULT_LIMIT_TOKENS}）</td></tr>
     *   <tr><td>{@code backend=backend}</td>
     *       <td>{@link ToolResultBackend} 端口（由构图方用本 Run 独享的沙盒 backend 适配）</td></tr>
     *   <tr><td>{@code model=load_chat_model(…)}</td>
     *       <td><b>不传</b>：本工程 {@link SummaryGenerator} 需要一个模型调用体，
     *           而参考实现由未搬的 {@code SummarizationMiddleware} 基类持有，见下。</td></tr>
     * </table>
     *
     * <h3>能力差异（显式标注，沿用类注释能力差异 1）</h3>
     * <p>{@code summaryGenerator} 与 {@code historyOffloader} 传 {@code null}：
     * 参考实现在基类 {@code _create_summary} / {@code _offload_to_backend} 中实现，
     * 这两个方法依赖 {@code SummarizationMiddleware} 与 {@code ContextSize} 语义
     * （未照搬，见类注释）。此时 {@link #canSummarize()} 为 {@code false}，
     * 本中间件退化为「工具入参截断 + 工具结果压缩」的确定性部分，<b>不</b>生成摘要、
     * <b>不</b>落历史 —— 这是显式降级，不是静默成功。
     * {@code eventSink} 仍照常注入（压缩开始/完成事件照发）。
     *
     * @param context 本 Run 的 Agent context
     * @param backend 工具结果落盘端口（形状对应 {@code backend.write}）
     * @param eventSink 压缩事件推送端口（形状对应 {@code get_stream_writer()}）；可为 null
     */
    public static SummaryMiddleware createFromContext(
            BaseContext context, ToolResultBackend backend, CompressionEventSink eventSink) {
        Integer toolResultOffloadTokenLimit =
                DEFAULT_SUMMARY_TOOL_RESULT_LIMIT_TOKENS;
        if (context != null) {
            Object configured = context.get("summary_tool_result_token_limit");
            if (configured instanceof Number number) {
                toolResultOffloadTokenLimit = number.intValue();
            }
        }
        return new SummaryMiddleware(
                backend,
                null,
                null,
                eventSink,
                toolResultOffloadTokenLimit,
                DEFAULT_TOOL_ARG_MAX_LENGTH);
    }

    /** 工具结果落盘端口（对应参考实现 {@code backend.write}）。 */
    @FunctionalInterface
    public interface ToolResultBackend {
        /** 写入成功返回 {@code null}，否则返回 error 文本。 */
        String write(String path, String content);
    }

    /** 摘要生成端口（对应参考实现 {@code _create_summary}）。 */
    @FunctionalInterface
    public interface SummaryGenerator {
        String summarize(String prompt);
    }

    /** 历史落盘端口（对应参考实现 {@code _offload_to_backend}）。 */
    @FunctionalInterface
    public interface HistoryOffloader {
        String offload(List<Message> messages, String sessionId);
    }

    /**
     * 压缩事件推送端口（对应参考实现 {@code get_stream_writer()}，其发射侧为
     * {@code _emit_compression(status, **extra)}）。
     *
     * <p><b>实现契约（接线时必须遵守）</b>：实现方负责把 {@code status} 与 {@code extra} 包成
     * 参考实现同形的 payload —— {@code {"type": "wenqu.context_compression", "status": status, **extra}}，
     * 并投递给流式输出通道。其中事件类型的字面量是**品牌替换**后的值
     * （参考实现为 {@code "yuxi.context_compression"}；生产者与消费者同处后端、前端无引用，
     * 见 {@link com.wisesoft.wenqu.service.ChatService} 的 {@code contextCompressionPayload}）。
     * 两侧字面量必须一致，否则压缩事件会在消费者侧被静默丢弃（收到但不转发）。
     */
    @FunctionalInterface
    public interface CompressionEventSink {
        void emit(String status, Map<String, Object> extra);
    }

    @Override
    public String getName() {
        return "summary";
    }

    /**
     * 对应参考实现 {@code _wrap_model_call_with_compaction} 的确定性部分。
     *
     * <p>流程与参考实现一致：截断工具入参 → 计算 token → 判定是否需要压缩 → 压缩工具结果 →
     * 按压力阈值判定是否生成摘要。摘要/落盘步骤未注入时（能力差异 1）直接把压缩后的消息交给 handler。
     */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<Object> effectiveMessages = effectiveMessages(request);
        int totalTokens = TokenUsageMiddleware.countTokensApproximately(effectiveMessages);
        List<Message> truncated = truncateArgs(effectiveMessages, totalTokens);
        boolean shouldCompact = shouldSummarize(thresholdClauses(request), truncated, totalTokens);
        if (!shouldCompact) {
            return handler.call(overrideMessages(request, truncated));
        }
        emit("started", Map.of());
        List<Message> compacted = compactMessages(truncated, largeToolResultsPrefix(request));
        return handler.call(overrideMessages(request, compacted));
    }

    // ==================== 工具结果压缩（确定性，逐字照搬） ====================

    /** 对应参考实现 {@code _compact_messages}。 */
    public List<Message> compactMessages(List<Message> messages, String largeToolResultsPrefix) {
        List<Message> compacted = new ArrayList<>();
        boolean modified = false;
        for (Message message : messages) {
            Message updated = message;
            if (message instanceof AssistantMessage assistant) {
                updated = truncateAiToolCallArgs(assistant, toolArgMaxLength);
            } else if (message instanceof ToolResponseMessage toolMessage
                    && !Boolean.TRUE.equals(toolMessage.getMetadata().get(TOOL_RESULT_SAVED_MARKER))
                    && shouldOffloadToolMessage(toolMessage, toolResultOffloadTokenLimit)) {
                updated = replaceToolMessageContent(toolMessage, backend, toolResultOffloadTokenLimit,
                        largeToolResultsPrefix);
            }
            compacted.add(updated);
            modified = modified || updated != message;
        }
        return modified ? compacted : messages;
    }

    /** 对应参考实现 {@code _should_offload_tool_message}。 */
    static boolean shouldOffloadToolMessage(ToolResponseMessage message, Integer tokenLimit) {
        if (tokenLimit == null || tokenLimit <= 0) {
            return true;
        }
        String content = extractTextContent(message.getText());
        int estimatedTokens = Math.max((content.length() + APPROX_CHARS_PER_TOKEN - 1) / APPROX_CHARS_PER_TOKEN, 1);
        return estimatedTokens > tokenLimit;
    }

    /** 对应参考实现 {@code _replace_tool_message_content}。 */
    static ToolResponseMessage replaceToolMessageContent(
            ToolResponseMessage message,
            ToolResultBackend backend,
            Integer toolResultTokenLimit,
            String largeToolResultsPrefix) {
        String content = extractTextContent(message.getText());
        String toolName = toolNameOf(message);
        String path = writeToolResult(backend, toolResultPath(toolName, content, largeToolResultsPrefix), content);
        Preview preview = buildToolResultPreview(content, toolResultTokenLimit, toolName, message.getText());
        int approxTokens = Math.max((content.length() + APPROX_CHARS_PER_TOKEN - 1) / APPROX_CHARS_PER_TOKEN, 1);
        List<String> lines = new ArrayList<>();
        lines.add("[Tool result saved]");
        lines.add("Tool: " + (toolName == null ? "unknown" : toolName));
        lines.add("Approx tokens: " + approxTokens);
        lines.add("SHA-256: " + sha256Hex(content));
        lines.add("Full output path: " + path);
        if (!preview.text().isEmpty()) {
            lines.add("");
            lines.add("Output preview:");
            lines.add(preview.text());
        }
        if (preview.omittedChars() > 0) {
            lines.add("[Truncated " + preview.omittedChars()
                    + " chars. Read the full output from the saved file.]");
        }
        Map<String, Object> metadata = new LinkedHashMap<>(message.getMetadata());
        metadata.put(TOOL_RESULT_SAVED_MARKER, true);
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            responses.add(new ToolResponseMessage.ToolResponse(
                    response.id(), response.name(), String.join("\n", lines)));
        }
        return ToolResponseMessage.builder().responses(responses).metadata(metadata).build();
    }

    /** 对应参考实现 {@code _build_tool_result_preview}（返回预览文本与被省略的字符数）。 */
    public record Preview(String text, int omittedChars) {
    }

    /** 对应参考实现 {@code _build_tool_result_preview}。 */
    static Preview buildToolResultPreview(String content, Integer tokenLimit, String toolName, Object rawContent) {
        String text = content.strip();
        if (tokenLimit == null) {
            return new Preview(text, 0);
        }
        if (tokenLimit <= 0) {
            return new Preview("", text.length());
        }
        int maxChars = tokenLimit * APPROX_CHARS_PER_TOKEN;
        if (text.length() <= maxChars) {
            return new Preview(text, 0);
        }
        String structured = structuredSearchPreview(toolName, rawContent, maxChars);
        if (structured != null) {
            return new Preview(structured, Math.max(text.length() - structured.length(), 0));
        }
        String preview = genericToolResultPreview(text, maxChars);
        return new Preview(preview, text.length() - preview.length());
    }

    /** 对应参考实现 {@code _structured_search_preview}。 */
    static String structuredSearchPreview(String toolName, Object rawContent, int maxChars) {
        if (toolName == null || !STRUCTURED_SEARCH_TOOL_NAMES.contains(toolName) || maxChars <= 0) {
            return null;
        }
        Map<String, Object> parsed = parseStructuredToolResult(rawContent);
        if (parsed == null) {
            return null;
        }
        List<Object> results = parsed.get("results") instanceof List<?> list ? new ArrayList<>(list) : List.of();
        Map<String, Object> header = searchHeader(toolName, parsed, results.size());
        List<Object[]> selected = new ArrayList<>();
        for (Object result : results.subList(0, Math.min(8, results.size()))) {
            selected.add(searchResultRecord(toolName, result));
        }
        while (!selected.isEmpty() && encodeSearchPreview(header, selected, results.size()).length() > maxChars) {
            selected.remove(selected.size() - 1);
        }
        if (selected.isEmpty()) {
            String compact = encodeSearchPreview(header, List.of(), results.size());
            Map<String, Object> minimalHeader = new LinkedHashMap<>();
            minimalHeader.put("kind", "query_kb".equals(toolName) ? "knowledge_base" : "web_search");
            minimalHeader.put("result_count", results.size());
            String minimal = encodeSearchPreview(minimalHeader, List.of(), results.size());
            if (compact.length() <= maxChars) {
                return compact;
            }
            return minimal.length() <= maxChars ? minimal : null;
        }
        String baseText = encodeSearchPreview(header, selected, results.size());
        int perResultChars = Math.max(maxChars - baseText.length() - 24 * selected.size(), 0) / selected.size();
        for (Object[] pair : selected) {
            String body = pair[1] == null ? "" : String.valueOf(pair[1]);
            if (!body.isEmpty() && perResultChars >= 24) {
                @SuppressWarnings("unchecked")
                Map<String, Object> record = (Map<String, Object>) pair[0];
                record.put("content_preview", clipSearchContent(body, perResultChars));
            }
        }
        String encoded = encodeSearchPreview(header, selected, results.size());
        while (encoded.length() > maxChars && selected.stream().anyMatch(pair ->
                pair[0] instanceof Map<?, ?> map && map.containsKey("content_preview"))) {
            for (Object[] pair : selected) {
                @SuppressWarnings("unchecked")
                Map<String, Object> record = (Map<String, Object>) pair[0];
                Object current = record.get("content_preview");
                if (current instanceof String value) {
                    String shortened = value.substring(0, Math.max(value.length() - 16, 0));
                    if (!shortened.isEmpty()) {
                        record.put("content_preview", shortened);
                    } else {
                        record.remove("content_preview");
                    }
                }
            }
            encoded = encodeSearchPreview(header, selected, results.size());
        }
        return encoded;
    }

    /** 对应参考实现 {@code _parse_structured_tool_result}。 */
    static Map<String, Object> parseStructuredToolResult(Object content) {
        Object value = content;
        if (content instanceof List<?> list) {
            List<String> textParts = new ArrayList<>();
            boolean structured = false;
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    if (map.containsKey("type") || map.containsKey("text")) {
                        structured = true;
                    }
                    if (map.get("text") instanceof String text) {
                        textParts.add(text);
                    }
                }
            }
            if (structured) {
                if (textParts.size() != 1) {
                    return null;
                }
                value = textParts.get(0);
            }
        }
        if (value instanceof String text) {
            try {
                value = JSON.parse(text);
            } catch (RuntimeException exc) {
                return null;
            }
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            boolean allMaps = true;
            for (Object item : list) {
                if (!(item instanceof Map)) {
                    allMaps = false;
                    break;
                }
            }
            if (allMaps) {
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("results", list);
                value = wrapped;
            }
        }
        if (!(value instanceof Map<?, ?> map) || !(map.get("results") instanceof List)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) value;
        return typed;
    }

    /** 对应参考实现 {@code _search_header}。 */
    static Map<String, Object> searchHeader(String toolName, Map<String, Object> parsed, int resultCount) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("kind", "query_kb".equals(toolName) ? "knowledge_base" : "web_search");
        preview.put("result_count", resultCount);
        for (String key : List.of("kb_id", "query", "response_time", "error")) {
            if (parsed.get(key) != null) {
                preview.put(key, boundedSearchScalar(parsed.get(key)));
            }
        }
        return preview;
    }

    /** 对应参考实现 {@code _search_result_record}（返回 [record, body]）。 */
    static Object[] searchResultRecord(String toolName, Object result) {
        if (!(result instanceof Map<?, ?> raw)) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("value", boundedSearchScalar(String.valueOf(result)));
            return new Object[] {record, ""};
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) result;
        List<String> keys = "query_kb".equals(toolName)
                ? List.of("id", "kb_id", "file_id", "title", "source", "score", "distance")
                : List.of("title", "url", "site_name", "publish_time", "score");
        Map<String, Object> record = new LinkedHashMap<>();
        for (String key : keys) {
            if (item.get(key) != null) {
                record.put(key, boundedSearchScalar(item.get(key)));
            }
        }
        if ("query_kb".equals(toolName) && item.get("metadata") instanceof Map<?, ?> rawMetadata) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) rawMetadata;
            List<String> metadataKeys = List.of(
                    "source", "filename", "title", "chunk_index", "score",
                    "rerank_score", "hybrid_score", "graph_score", "distance");
            Map<String, Object> selected = new LinkedHashMap<>();
            for (String key : metadataKeys) {
                if (metadata.get(key) != null) {
                    selected.put(key, boundedSearchScalar(metadata.get(key)));
                }
            }
            if (!selected.isEmpty()) {
                record.put("metadata", selected);
            }
        }
        String body = "";
        for (String key : SEARCH_CONTENT_KEYS) {
            if (item.get(key) instanceof String value) {
                body = value;
                break;
            }
        }
        return new Object[] {record, body};
    }

    /** 对应参考实现 {@code _encode_search_preview}（紧凑分隔符、非 ASCII 原样）。 */
    static String encodeSearchPreview(Map<String, Object> preview, List<Object[]> selected, int resultCount) {
        Map<String, Object> encoded = new LinkedHashMap<>(preview);
        List<Object> records = new ArrayList<>();
        for (Object[] pair : selected) {
            records.add(pair[0]);
        }
        encoded.put("results", records);
        int omitted = resultCount - records.size();
        if (omitted > 0) {
            encoded.put("omitted_results", omitted);
        }
        return JSON.toJSONString(encoded);
    }

    /** 对应参考实现 {@code _bounded_search_scalar}（默认 240 字符）。 */
    static Object boundedSearchScalar(Object value) {
        return value instanceof String text ? clipSearchContent(text, 240) : value;
    }

    /** 对应参考实现 {@code _clip_search_content}。 */
    static String clipSearchContent(String value, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 24) {
            return value.substring(0, maxChars);
        }
        int headLength = ((maxChars - CLIP_MARKER.length()) * 3) / 4;
        int tailLength = maxChars - CLIP_MARKER.length() - headLength;
        return value.substring(0, headLength) + CLIP_MARKER + value.substring(value.length() - tailLength);
    }

    /** 对应参考实现 {@code _generic_tool_result_preview}。 */
    static String genericToolResultPreview(String text, int maxChars) {
        List<String> labels = List.of("[HEAD]\n", "\n\n[MIDDLE]\n", "\n\n[TAIL]\n");
        int labelLength = labels.get(0).length() + labels.get(1).length() + labels.get(2).length();
        int contentBudget = maxChars - labelLength;
        if (contentBudget <= 0) {
            return text.substring(0, maxChars);
        }
        int headLength = (contentBudget * 2) / 5;
        int middleLength = contentBudget / 5;
        int tailLength = contentBudget - headLength - middleLength;
        int middleStart = Math.max((text.length() - middleLength) / 2, headLength);
        return labels.get(0)
                + text.substring(0, headLength)
                + labels.get(1)
                + text.substring(middleStart, Math.min(middleStart + middleLength, text.length()))
                + labels.get(2)
                + text.substring(Math.max(text.length() - tailLength, 0));
    }

    /** 对应参考实现 {@code _tool_result_path}。 */
    static String toolResultPath(String toolName, String content, String prefix) {
        String safeName = (toolName == null ? "" : toolName).strip().replaceAll("[^A-Za-z0-9_.-]+", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        if (safeName.isEmpty()) {
            safeName = DEFAULT_TOOL_RESULT_NAME;
        }
        return prefix + "/" + safeName + "-" + sha256Hex(content).substring(0, 16) + ".txt";
    }

    /** 对应参考实现 {@code _write_tool_result}。 */
    static String writeToolResult(ToolResultBackend backend, String path, String content) {
        if (backend == null) {
            throw new IllegalStateException("Cannot save tool result to " + path + ": backend is unavailable");
        }
        String error = backend.write(path, content);
        if (error == null || error.toLowerCase().contains("already exists")) {
            return path;
        }
        throw new IllegalStateException("Failed to write tool result to " + path + ": " + error);
    }

    /** 对应参考实现 {@code _extract_text_content}。 */
    static String extractTextContent(Object content) {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String text) {
                    parts.add(text);
                } else if (item instanceof Map<?, ?> map && map.get("text") instanceof String text) {
                    parts.add(text);
                }
            }
            return String.join("\n", parts);
        }
        return content == null ? "" : String.valueOf(content);
    }

    // ==================== 工具入参截断（确定性，逐字照搬） ====================

    /** 对应参考实现 {@code _truncate_ai_tool_call_args}。 */
    static AssistantMessage truncateAiToolCallArgs(AssistantMessage message, int maxLength) {
        List<AssistantMessage.ToolCall> toolCalls = message.getToolCalls();
        if ((toolCalls == null || toolCalls.isEmpty()) && message.getMetadata().isEmpty()) {
            return message;
        }
        List<AssistantMessage.ToolCall> updatedToolCalls = new ArrayList<>();
        boolean toolCallsModified = false;
        for (AssistantMessage.ToolCall toolCall : toolCalls == null ? List.<AssistantMessage.ToolCall>of() : toolCalls) {
            TruncatedCall truncated = truncateToolCallArgs(toolCall, maxLength);
            updatedToolCalls.add(truncated.call());
            toolCallsModified = toolCallsModified || truncated.modified();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(message.getMetadata());
        ProviderCallsResult provider = truncateProviderToolCalls(metadata, maxLength);
        if (!toolCallsModified && !provider.modified()) {
            return message;
        }
        return AssistantMessage.builder()
                .content(message.getText())
                .properties(provider.additional_kwargs())
                .toolCalls(toolCallsModified ? updatedToolCalls : toolCalls)
                .build();
    }

    /** 对应参考实现 {@code _truncate_tool_call_args}。 */
    static TruncatedCall truncateToolCallArgs(AssistantMessage.ToolCall toolCall, int maxLength) {
        if (!Set.of("write_file", "edit_file").contains(toolCall.name())) {
            return new TruncatedCall(toolCall, false);
        }
        Map<String, Object> args = parseObject(toolCall.arguments());
        Map<String, Object> truncatedArgs = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (entry.getValue() instanceof String value && !truncateStringArg(value, maxLength).equals(value)) {
                truncatedArgs.put(entry.getKey(), truncateStringArg(value, maxLength));
                changed = true;
            } else {
                truncatedArgs.put(entry.getKey(), entry.getValue());
            }
        }
        if (!changed) {
            return new TruncatedCall(toolCall, false);
        }
        return new TruncatedCall(new AssistantMessage.ToolCall(
                toolCall.id(), toolCall.type(), toolCall.name(), JSON.toJSONString(truncatedArgs)), true);
    }

    /** 对应参考实现 {@code _truncate_provider_tool_calls}。 */
    static ProviderCallsResult truncateProviderToolCalls(Map<String, Object> additionalKwargs, int maxLength) {
        Object rawToolCalls = additionalKwargs.get("tool_calls");
        if (!(rawToolCalls instanceof List<?> list)) {
            return new ProviderCallsResult(additionalKwargs, false);
        }
        List<Object> updated = new ArrayList<>();
        boolean modified = false;
        for (Object rawCall : list) {
            if (!(rawCall instanceof Map<?, ?> callMap)) {
                updated.add(rawCall);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> call = (Map<String, Object>) callMap;
            Object function = call.get("function");
            if (!(function instanceof Map<?, ?> rawFunction)) {
                updated.add(rawCall);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fn = (Map<String, Object>) function;
            Object arguments = fn.get("arguments");
            if (!Set.of("write_file", "edit_file").contains(fn.get("name"))
                    || !(arguments instanceof String text)
                    || text.length() <= maxLength) {
                updated.add(rawCall);
                continue;
            }
            Map<String, Object> updatedFunction = new LinkedHashMap<>(fn);
            updatedFunction.put("arguments", truncateStringArg(text, maxLength));
            Map<String, Object> updatedCall = new LinkedHashMap<>(call);
            updatedCall.put("function", updatedFunction);
            updated.add(updatedCall);
            modified = true;
        }
        if (!modified) {
            return new ProviderCallsResult(additionalKwargs, false);
        }
        Map<String, Object> updatedKwargs = new LinkedHashMap<>(additionalKwargs);
        updatedKwargs.put("tool_calls", updated);
        return new ProviderCallsResult(updatedKwargs, true);
    }

    /** 对应参考实现 {@code _truncate_string_arg}。 */
    static String truncateStringArg(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, 20) + TRUNCATED_TOOL_ARG_TEXT;
    }

    /** {@code _truncate_tool_call_args} 的返回对（参考实现为二元组）。 */
    public record TruncatedCall(AssistantMessage.ToolCall call, boolean modified) {
    }

    /** {@code _truncate_provider_tool_calls} 的返回对（参考实现为二元组）。 */
    public record ProviderCallsResult(Map<String, Object> additional_kwargs, boolean modified) {
    }

    // ==================== 触发判定与状态更新 ====================

    /** 对应参考实现 {@code _entry_trigger_tokens}（取所有触发阈值的下界）。 */
    public static Integer entryTriggerTokens(List<Map<String, Object>> triggerClauses, Integer maxInputTokens) {
        List<Integer> thresholds = new ArrayList<>();
        for (Map<String, Object> clause : triggerClauses == null ? List.<Map<String, Object>>of() : triggerClauses) {
            Object tokens = clause.get("tokens");
            if (tokens instanceof Number number && number.intValue() > 0) {
                thresholds.add(number.intValue());
            }
            Object fraction = clause.get("fraction");
            if (fraction instanceof Number value && maxInputTokens != null) {
                thresholds.add(Math.max((int) (maxInputTokens * value.doubleValue()), 1));
            }
        }
        return thresholds.isEmpty() ? null : thresholds.stream().min(Integer::compareTo).orElse(null);
    }

    /** 对应参考实现 {@code _trigger_clause_met}。 */
    public static boolean triggerClauseMet(
            Map<String, Object> clause, List<?> messages, int totalTokens, Integer maxInputTokens) {
        for (Map.Entry<String, Object> entry : clause.entrySet()) {
            String kind = entry.getKey();
            Object value = entry.getValue();
            if ("messages".equals(kind) && value instanceof Number number && messages.size() < number.intValue()) {
                return false;
            }
            if ("tokens".equals(kind) && value instanceof Number number && totalTokens < number.intValue()) {
                return false;
            }
            if ("fraction".equals(kind) && value instanceof Number number) {
                if (maxInputTokens == null
                        || totalTokens < Math.max((int) (maxInputTokens * number.doubleValue()), 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 对应参考实现 {@code _should_summarize}。 */
    public static boolean shouldSummarize(List<Map<String, Object>> triggerClauses, List<?> messages, int totalTokens) {
        if (triggerClauses == null || triggerClauses.isEmpty()) {
            return false;
        }
        for (Map<String, Object> clause : triggerClauses) {
            if (triggerClauseMet(clause, messages, totalTokens, null)) {
                return true;
            }
        }
        return false;
    }

    /** 对应参考实现 {@code _build_summary_event}。 */
    public static Map<String, Object> buildSummaryEvent(
            Map<String, Object> previousEvent, int cutoffIndex, Object summaryMessage, String file_path) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("cutoff_index", computeStateCutoff(previousEvent, cutoffIndex));
        event.put("summary_message", summaryMessage);
        event.put("file_path", file_path);
        return event;
    }

    /** 对应参考实现 {@code _build_state_update}。 */
    public static Map<String, Object> buildStateUpdate(
            Map<String, Object> event, String sessionId, List<Message> newStateTail) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("_summarization_event", event);
        update.put("_summarization_session_id", sessionId);
        if (newStateTail != null && !newStateTail.isEmpty()) {
            update.put("messages", newStateTail);
        }
        return update;
    }

    /** 对应参考实现 {@code _compute_state_cutoff}（累计历次压缩的截断下标）。 */
    static int computeStateCutoff(Map<String, Object> previousEvent, int cutoffIndex) {
        if (previousEvent == null) {
            return cutoffIndex;
        }
        Object previous = previousEvent.get("cutoff_index");
        if (previous instanceof Number number) {
            return number.intValue() + cutoffIndex;
        }
        return cutoffIndex;
    }

    /** 对应参考实现 {@code _report_offload_result}。 */
    static void reportOffloadResult(String file_path, int failedMedia) {
        if (file_path == null) {
            log.error("Offloading conversation history to backend failed during summarization. "
                    + "Older messages will not be recoverable.");
            return;
        }
        if (failedMedia > 0) {
            log.warn("Conversation history offloaded to {}, but {} media block(s) could not be offloaded.",
                    file_path, failedMedia);
        }
    }

    /** 对应参考实现 {@code _count_tokens_for_summary_trigger}（忽略 usage 缩放）。 */
    static int countTokensForSummaryTrigger(List<Object> messages) {
        return TokenUsageMiddleware.countTokensApproximately(messages);
    }

    // ==================== 承载点辅助（能力差异区） ====================

    /** 参考实现 {@code request.messages}；本工程 {@link ModelRequest} 的等价读取。 */
    static List<Object> effectiveMessages(ModelRequest request) {
        List<Message> messages = request == null ? null : request.getMessages();
        return messages == null ? List.of() : new ArrayList<>(messages);
    }

    /** 参考实现 {@code ContextOverflowError} 的判定（能力差异 2，可覆写）。 */
    protected boolean isContextOverflow(RuntimeException exc) {
        String message = exc.getMessage() == null ? "" : exc.getMessage().toLowerCase();
        return message.contains("context") && (message.contains("overflow") || message.contains("too long")
                || message.contains("token"));
    }

    /** 触发子句（{@code trigger}）：默认取 context 的 {@code summary_threshold}（KB）换算的 token 阈值。 */
    protected List<Map<String, Object>> thresholdClauses(ModelRequest request) {
        Map<String, Object> raw = request == null ? null : request.getContext();
        Object context = raw == null ? null : raw.get(ContextAwareInterceptor.CONTEXT_KEY);
        if (!(context instanceof BaseContext base)) {
            return List.of();
        }
        Object threshold = base.get("summary_threshold");
        int tokens = threshold instanceof Number number
                ? number.intValue() * 1024
                : BaseContext.DEFAULT_SUMMARY_THRESHOLD_K * 1024;
        Map<String, Object> clause = new LinkedHashMap<>();
        clause.put("tokens", tokens);
        return List.of(clause);
    }

    /** 大工具结果落盘前缀：默认取 context 的 {@code large_tool_results_prefix}，缺省走 {@link BackendPaths} 口径。 */
    protected String largeToolResultsPrefix(ModelRequest request) {
        Map<String, Object> raw = request == null ? null : request.getContext();
        Object context = raw == null ? null : raw.get(ContextAwareInterceptor.CONTEXT_KEY);
        if (context instanceof BaseContext base && base.getDynamic("large_tool_results_prefix", null) != null) {
            return String.valueOf(base.getDynamic("large_tool_results_prefix", null));
        }
        return com.wisesoft.wenqu.agents.BackendPaths.LARGE_TOOL_RESULTS_DIR_NAME;
    }

    private List<Message> truncateArgs(List<Object> messages, int totalTokens) {
        List<Message> truncated = new ArrayList<>();
        for (Object message : messages) {
            if (message instanceof AssistantMessage assistant) {
                truncated.add(truncateAiToolCallArgs(assistant, toolArgMaxLength));
            } else if (message instanceof Message plain) {
                truncated.add(plain);
            }
        }
        return truncated;
    }

    private static ModelRequest overrideMessages(ModelRequest request, List<Message> messages) {
        return ModelRequest.builder(request).messages(messages).build();
    }

    private void emit(String status, Map<String, Object> extra) {
        if (eventSink != null) {
            eventSink.emit(status, extra);
        }
    }

    private static String toolNameOf(ToolResponseMessage message) {
        List<ToolResponseMessage.ToolResponse> responses = message.getResponses();
        if (responses.isEmpty()) {
            return null;
        }
        String name = responses.get(0).name();
        return name == null || name.isEmpty() ? null : name;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : hashed) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 unavailable", exc);
        }
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

    /** 供构图方读取当前中间件的摘要/落盘端口是否已注入（未注入即能力差异 1 生效）。 */
    public boolean canSummarize() {
        return summaryGenerator != null && historyOffloader != null;
    }

    /** 供调试：以紧凑 JSON 输出待落 state 的更新。 */
    public String pendingStateUpdateAsJson() {
        return JSON.toJSONString(pendingStateUpdate, JSONWriter.Feature.WriteMapNullValue);
    }
}
