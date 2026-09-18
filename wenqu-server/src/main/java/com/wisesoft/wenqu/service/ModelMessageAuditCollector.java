package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.repositories.ModelMessageAuditRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 v3 Model 生命周期投影为模型审计消息。
 *
 * <p>由参考实现的 services/model_message_audit_service.py 逐类逐方法翻译：
 * {@code ModelMessageAuditCollector} 按 (run_id, request_id, thread_id, worker_id) 实例化
 * （对应参考实现的构造签名；仓储由调用方从容器取得后传入），按 message lifecycle 串行提交
 * 模型审计短事务（每个事件对应仓储的一次独立短事务）。
 *
 * <p>必要替换：参考实现的 ProtocolEvent 为 dict，本工程以 {@code Map<String, Object>} 承载；
 * {@code pg_manager.get_async_session_context()} 短事务 → 仓储方法自带的事务边界（REQUIRED，
 * 与参考实现"每个事件一个短事务"一致）；{@code monotonic()} → {@code System.nanoTime()}；
 * {@code datetime.fromtimestamp(ts/1000, UTC).replace(tzinfo=None)} → UTC 纪元毫秒转 naive
 * {@code LocalDateTime}；{@code ValueError} → {@code IllegalArgumentException}。
 */
public class ModelMessageAuditCollector {

    /** 保存单次 Model 生命周期的进程内聚合状态。 */
    private static final class ModelOperation {
        final String operationId;
        Long monotonicStartedAt;
        final List<String> contentParts = new ArrayList<>();
        final List<Map<String, Object>> contentBlocks = new ArrayList<>();

        ModelOperation(String operationId, Long monotonicStartedAt) {
            this.operationId = operationId;
            this.monotonicStartedAt = monotonicStartedAt;
        }
    }

    private final ModelMessageAuditRepository auditRepository;
    private final String runId;
    private final String requestId;
    private final String threadId;
    private final String workerId;
    private final Map<String, ModelOperation> operations = new LinkedHashMap<>();

    public ModelMessageAuditCollector(
            ModelMessageAuditRepository auditRepository,
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

    /** 消费一条 raw messages ProtocolEvent；非生命周期消息保持无副作用。 */
    public void consume(Object rawMessage, Map<String, Object> rawMetadata) {
        if (!(rawMessage instanceof Map<?, ?> messageMap)
                || !(messageMap.get("event") instanceof String eventName)) {
            return;
        }
        Map<String, Object> message = asStringMap(messageMap);
        Map<String, Object> metadata =
                rawMetadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawMetadata);
        Object streamEventRaw = metadata.get("stream_event");
        Map<String, Object> streamEvent =
                streamEventRaw instanceof Map<?, ?> map ? asStringMap(map) : new LinkedHashMap<>();
        List<String> namespace = namespaceOf(streamEvent.get("namespace"), metadata.get("namespace"));
        String key = operationKey(metadata, namespace);

        switch (eventName) {
            case "message-start" -> start(message, metadata, streamEvent, key, namespace);
            case "content-block-delta" -> {
                ModelOperation operation = operations.get(key);
                if (operation == null) {
                    return;
                }
                String text = textDelta(message);
                if (!text.isEmpty()) {
                    operation.contentParts.add(text);
                }
            }
            case "content-block-finish" -> {
                ModelOperation operation = operations.get(key);
                if (operation == null) {
                    return;
                }
                if (message.get("content") instanceof Map<?, ?> content) {
                    operation.contentBlocks.add(asStringMap(content));
                }
            }
            case "message-finish" -> {
                ModelOperation operation = operations.get(key);
                if (operation != null) {
                    finish(message, streamEvent, key, operation, namespace);
                }
            }
            default -> {
                // 非生命周期事件：无副作用
            }
        }
    }

    /** 持久化 Model start 并初始化进程内增量聚合。 */
    private void start(
            Map<String, Object> message,
            Map<String, Object> metadata,
            Map<String, Object> streamEvent,
            String key,
            List<String> namespace) {
        Object idValue = message.get("id") != null ? message.get("id") : metadata.get("run_id");
        String operationId = idValue == null ? "" : String.valueOf(idValue).strip();
        if (operationId.isEmpty()) {
            throw new IllegalArgumentException("message-start 缺少稳定 Model operation id");
        }
        ModelOperation currentOperation = operations.get(key);
        if (currentOperation != null && !currentOperation.operationId.equals(operationId)) {
            throw new IllegalArgumentException("同一 Model lifecycle 不能更换 operation id");
        }
        long sequence = sequence(streamEvent);
        LocalDateTime startedAt = wallClock(streamEvent);
        long monotonicStartedAt = System.nanoTime();

        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put(
                "id", String.valueOf(message.get("id") != null ? message.get("id") : operationId));
        auditMetadata.put("audit_kind", "model");
        auditMetadata.put("namespace", namespace);
        auditMetadata.put("model_run_id", metadata.get("run_id"));
        auditMetadata.put(
                "start_metadata",
                message.get("metadata") instanceof Map<?, ?> map ? asStringMap(map) : new LinkedHashMap<>());
        ModelMessageAuditRepository.StartResult result =
                auditRepository.start(
                        runId, requestId, threadId, workerId, operationId, sequence, startedAt, auditMetadata);

        if (currentOperation == null) {
            operations.put(key, new ModelOperation(operationId, result.created() ? monotonicStartedAt : null));
        }
    }

    /** 用聚合内容和原始 usage 关闭同一 Model lifecycle。 */
    private void finish(
            Map<String, Object> message,
            Map<String, Object> streamEvent,
            String key,
            ModelOperation operation,
            List<String> namespace) {
        LocalDateTime finishedAt = wallClock(streamEvent);
        Long durationMs =
                operation.monotonicStartedAt != null
                        ? Math.max(0, Math.round((System.nanoTime() - operation.monotonicStartedAt) / 1_000_000.0))
                        : null;
        Map<String, Object> usage = message.get("usage") instanceof Map<?, ?> map ? asStringMap(map) : null;
        List<Map<String, Object>> toolCalls =
                operation.contentBlocks.stream()
                        .filter(block -> "tool_call".equals(block.get("type")))
                        .toList();

        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("namespace", namespace);
        auditMetadata.put("content", operation.contentBlocks);
        auditMetadata.put("tool_calls", toolCalls);
        auditMetadata.put("finished_sequence", sequence(streamEvent));
        auditMetadata.put(
                "finish_metadata",
                message.get("metadata") instanceof Map<?, ?> map ? asStringMap(map) : new LinkedHashMap<>());

        auditRepository.finish(
                runId,
                requestId,
                threadId,
                workerId,
                operation.operationId,
                String.join("", operation.contentParts),
                finishedAt,
                durationMs,
                usage,
                auditMetadata);
        operations.remove(key);
    }

    private static String operationKey(Map<String, Object> metadata, List<String> namespace) {
        Object runId = metadata.get("run_id") != null ? metadata.get("run_id") : metadata.get("langgraph_node");
        return (runId == null ? "" : String.valueOf(runId)) + "/" + String.join("/", namespace);
    }

    private static String textDelta(Map<String, Object> message) {
        Map<String, Object> delta =
                message.get("delta") instanceof Map<?, ?> map ? asStringMap(map) : new LinkedHashMap<>();
        if ("text-delta".equals(delta.get("type")) && delta.get("text") instanceof String text) {
            return text;
        }
        Map<String, Object> fields =
                delta.get("fields") instanceof Map<?, ?> map ? asStringMap(map) : new LinkedHashMap<>();
        if ("text-delta".equals(fields.get("type")) && fields.get("text") instanceof String text) {
            return text;
        }
        return "";
    }

    private static long sequence(Map<String, Object> streamEvent) {
        Object sequence = streamEvent.get("seq");
        if (!(sequence instanceof Number number) || sequence instanceof Boolean || number.longValue() < 0) {
            throw new IllegalArgumentException("Model lifecycle 缺少有效 ProtocolEvent seq");
        }
        return number.longValue();
    }

    private static LocalDateTime wallClock(Map<String, Object> streamEvent) {
        Object timestamp = streamEvent.get("timestamp");
        if (!(timestamp instanceof Number number) || timestamp instanceof Boolean) {
            throw new IllegalArgumentException("Model lifecycle 缺少有效 params.timestamp");
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), ZoneOffset.UTC);
    }

    private static List<String> namespaceOf(Object streamNamespace, Object metadataNamespace) {
        Object source = streamNamespace != null ? streamNamespace : metadataNamespace;
        List<String> namespace = new ArrayList<>();
        if (source instanceof List<?> list) {
            for (Object item : list) {
                namespace.add(String.valueOf(item));
            }
        }
        return namespace;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
