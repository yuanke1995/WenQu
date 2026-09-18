package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.repositories.ToolMessageAuditRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 v3 Tool 生命周期投影为工具审计消息。
 *
 * <p>由参考实现的 services/tool_message_audit_service.py 逐类逐方法翻译：
 * {@code ToolMessageAuditCollector} 按 (run_id, request_id, thread_id, worker_id) 实例化
 * （对应参考实现的构造签名；仓储由调用方从容器取得后传入），按 tools lifecycle 串行提交
 * 工具审计短事务。
 *
 * <p>必要替换：与 ModelMessageAuditCollector 相同（ProtocolEvent → Map、短事务 → 仓储事务边界、
 * monotonic → System.nanoTime、UTC 纪元毫秒 → naive LocalDateTime、ValueError →
 * IllegalArgumentException）；模块级函数 {@code _tool_output_content} → {@link #toolOutputContent}。
 */
public class ToolMessageAuditCollector {

    private final ToolMessageAuditRepository auditRepository;
    private final String runId;
    private final String requestId;
    private final String threadId;
    private final String workerId;
    private final Map<String, Long> operations = new LinkedHashMap<>();

    public ToolMessageAuditCollector(
            ToolMessageAuditRepository auditRepository,
            String runId,
            String requestId,
            String threadId,
            String workerId) {
        this.auditRepository = auditRepository;
        this.runId = runId;
        this.requestId = requestId;
        this.threadId = threadId;
        this.workerId = workerId;
    }

    /** 消费一条根 tools ProtocolEvent；非生命周期事件保持无副作用。 */
    public void consume(Object rawEvent) {
        if (!(rawEvent instanceof Map<?, ?> eventMap) || !"tools".equals(eventMap.get("method"))) {
            return;
        }
        Map<String, Object> event = asStringMap(eventMap);
        if (!(event.get("data") instanceof Map<?, ?> dataMap)
                || !(dataMap.get("event") instanceof String eventName)) {
            return;
        }
        Map<String, Object> data = asStringMap(dataMap);

        switch (eventName) {
            case "tool-started" -> start(event, data);
            case "tool-finished" -> finish(event, data);
            case "tool-error" -> error(event, data);
            default -> {
                // 非生命周期事件：无副作用
            }
        }
    }

    private void start(Map<String, Object> event, Map<String, Object> data) {
        String toolCallId = toolCallId(data);
        String toolName = data.get("tool_name") == null ? "" : String.valueOf(data.get("tool_name")).strip();
        Object rawInput = data.get("input");
        Map<String, Object> toolInput;
        if (rawInput == null) {
            toolInput = new LinkedHashMap<>();
        } else if (rawInput instanceof Map<?, ?> map) {
            toolInput = asStringMap(map);
        } else {
            throw new IllegalArgumentException("tool-started input 必须是对象");
        }

        long sequence = sequence(event);
        LocalDateTime startedAt = wallClock(event);
        long monotonicStartedAt = System.nanoTime();
        List<String> namespace = namespace(event);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("namespace", namespace);
        ToolMessageAuditRepository.StartResult result =
                auditRepository.start(
                        runId,
                        requestId,
                        threadId,
                        workerId,
                        toolCallId,
                        toolName,
                        toolInput,
                        sequence,
                        startedAt,
                        metadata);

        if (result.created()) {
            operations.put(toolCallId, monotonicStartedAt);
        } else {
            operations.putIfAbsent(toolCallId, null);
        }
    }

    private void finish(Map<String, Object> event, Map<String, Object> data) {
        String toolCallId = toolCallId(data);
        Object output = data.get("output");
        String content = toolOutputContent(output);
        boolean failed = output instanceof Map<?, ?> map && "error".equals(map.get("status"));
        String errorMessage = failed ? content : null;
        close(event, toolCallId, output, content, errorMessage, false);
    }

    private void error(Map<String, Object> event, Map<String, Object> data) {
        String toolCallId = toolCallId(data);
        String errorMessage =
                data.get("message") == null ? "Tool 执行失败" : String.valueOf(data.get("message"));
        close(event, toolCallId, null, "", errorMessage, true);
    }

    /** 将进程内计时与 terminal 事实一次提交给 Repository。 */
    private void close(
            Map<String, Object> event,
            String toolCallId,
            Object output,
            String content,
            String errorMessage,
            boolean waitForRunTerminal) {
        Long monotonicStartedAt = operations.get(toolCallId);
        Long durationMs =
                monotonicStartedAt != null
                        ? Math.max(0, Math.round((System.nanoTime() - monotonicStartedAt) / 1_000_000.0))
                        : null;
        LocalDateTime finishedAt = wallClock(event);
        long finishedSequence = sequence(event);

        if (waitForRunTerminal) {
            auditRepository.observeError(
                    runId,
                    requestId,
                    threadId,
                    workerId,
                    toolCallId,
                    errorMessage == null || errorMessage.isEmpty() ? "Tool 执行失败" : errorMessage,
                    finishedAt,
                    durationMs,
                    finishedSequence);
        } else if (errorMessage == null) {
            auditRepository.complete(
                    runId,
                    requestId,
                    threadId,
                    workerId,
                    toolCallId,
                    output,
                    content,
                    finishedAt,
                    durationMs,
                    finishedSequence);
        } else {
            auditRepository.fail(
                    runId,
                    requestId,
                    threadId,
                    workerId,
                    toolCallId,
                    errorMessage,
                    output,
                    content,
                    finishedAt,
                    durationMs,
                    finishedSequence);
        }
        operations.remove(toolCallId);
    }

    private static String toolCallId(Map<String, Object> data) {
        String toolCallId = data.get("tool_call_id") == null ? "" : String.valueOf(data.get("tool_call_id")).strip();
        if (toolCallId.isEmpty()) {
            throw new IllegalArgumentException("Tool lifecycle 缺少稳定 tool_call_id");
        }
        return toolCallId;
    }

    private static long sequence(Map<String, Object> event) {
        Object sequence = event.get("seq");
        if (!(sequence instanceof Number number) || sequence instanceof Boolean || number.longValue() < 0) {
            throw new IllegalArgumentException("Tool lifecycle 缺少有效 ProtocolEvent seq");
        }
        return number.longValue();
    }

    private static LocalDateTime wallClock(Map<String, Object> event) {
        Object timestamp = event.get("timestamp");
        if (!(timestamp instanceof Number number) || timestamp instanceof Boolean) {
            throw new IllegalArgumentException("Tool lifecycle 缺少有效 params.timestamp");
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), ZoneOffset.UTC);
    }

    private static List<String> namespace(Map<String, Object> event) {
        List<String> namespace = new ArrayList<>();
        if (event.get("namespace") instanceof List<?> list) {
            for (Object item : list) {
                namespace.add(String.valueOf(item));
            }
        }
        return namespace;
    }

    /** 将 ToolMessage output 规整为兼容展示文本，同时保留原始 output metadata。 */
    static String toolOutputContent(Object output) {
        Object value = output instanceof Map<?, ?> map ? map.get("content") : output;
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        return JSON.toJSONString(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
