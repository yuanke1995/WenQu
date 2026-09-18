package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.common.DateTimeUtils;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Run 队列 / Redis 辅助。
 *
 * <p>由参考实现的 services/run_queue_service.py 逐函数翻译：Run 取消信号（SET/GET/DEL + TTL）、
 * Run 事件流（Redis Stream XADD/XRANGE/XREVRANGE，信封 schema_version=1）、worker 健康键常量。
 *
 * <p>必要替换：
 * <ul>
 *   <li>异步 Redis 客户端 / ARQ Redis 池 → {@link StringRedisTemplate}；ARQ 池仅服务于
 *       ARQ 唤醒消息（{@code get_arq_pool().enqueue_job}），本工程由任务唤醒端口承担
 *       （见 TaskQueueService），此处不建池。
 *   <li>缓存键前缀（run:cancel:/run:events:/worker:health:）为参考实现直接使用的键名，
 *       不含产品命名空间，照搬；worker:health: 键的命名空间前缀按本系统约定
 *       （共享 Redis 中标识本系统的键空间，与 KnowledgeBaseCache/LoginRateLimitService 一致）。
 *   <li>{@code datetime.now(tz=UTC).isoformat()} → {@link DateTimeUtils#utcIsoformat()}；
 *       {@code asyncio} 轮询/并发 → 阻塞循环 + 顺序循环（调用方线程承担）。
 *   <li>{@code xrange(key, min="(...)", count)} → Spring Data Redis 的
 *       {@code range(key, Range.leftOpen(...), Limit)}（左开区间同义）。
 * </ul>
 */
@Service
public class RunQueueService {

    public static final int RUN_CANCEL_KEY_TTL_SECONDS = Integer.getInteger("RUN_CANCEL_KEY_TTL_SECONDS", 1800);
    public static final int RUN_EVENTS_STREAM_TTL_SECONDS = Integer.getInteger("RUN_EVENTS_STREAM_TTL_SECONDS", 7200);
    public static final int RUN_EVENTS_STREAM_MAXLEN = Integer.getInteger("RUN_EVENTS_STREAM_MAXLEN", 0);
    public static final String WORKER_HEALTH_CONTRACT = "agent-run-v1";
    public static final String WORKER_HEALTH_KEY = "wenqu:worker:health:" + WORKER_HEALTH_CONTRACT;
    public static final double WORKER_HEALTH_INTERVAL_SECONDS =
            Double.parseDouble(System.getenv().getOrDefault("WORKER_HEALTH_INTERVAL_SECONDS", "5"));
    public static final int WORKER_HEALTH_MAX_TTL_MS = (int) ((WORKER_HEALTH_INTERVAL_SECONDS + 1) * 1000);
    public static final int RUN_RECONCILIATION_SECONDS = 30;
    public static final String WORKER_RECONCILIATION_HEALTH_KEY = WORKER_HEALTH_KEY + ":lease-reconciliation";
    public static final int WORKER_RECONCILIATION_HEALTH_TTL_SECONDS = RUN_RECONCILIATION_SECONDS * 2 + 5;

    static {
        if (!(0 < WORKER_HEALTH_INTERVAL_SECONDS && WORKER_HEALTH_INTERVAL_SECONDS <= 10)) {
            throw new IllegalStateException("WORKER_HEALTH_INTERVAL_SECONDS 必须大于 0 且不超过 10");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RunQueueService.class);

    private final StringRedisTemplate redis;

    public RunQueueService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String cancelKey(String runId) {
        return "run:cancel:" + runId;
    }

    private static String eventStreamKey(String runId) {
        return "run:events:" + runId;
    }

    private static boolean isValidStreamSeq(String value) {
        int sep = value.indexOf('-');
        if (sep < 0) {
            return false;
        }
        String major = value.substring(0, sep);
        String minor = value.substring(sep + 1);
        return major.chars().allMatch(Character::isDigit) && minor.chars().allMatch(Character::isDigit);
    }

    /** Normalize after_seq cursor to redis stream id format. */
    public static String normalizeAfterSeq(String afterSeq) {
        if (afterSeq == null) {
            return "0-0";
        }
        String text = afterSeq.strip();
        if (text.isEmpty()) {
            return "0-0";
        }
        return isValidStreamSeq(text) ? text : "0-0";
    }

    public static Map<String, Object> buildRunEventEnvelope(
            String runId, String eventType, Map<String, Object> payload, String threadId, String createdAt) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema_version", 1);
        envelope.put("run_id", runId);
        envelope.put("thread_id", threadId);
        envelope.put("event", eventType);
        envelope.put("payload", payload == null ? new LinkedHashMap<>() : payload);
        envelope.put("created_at", createdAt != null ? createdAt : DateTimeUtils.utcIsoformat());
        return envelope;
    }

    private static String payloadThreadId(Map<String, Object> payload) {
        if (!(payload instanceof Map) || !(payload.get("chunk") instanceof Map<?, ?> chunk)) {
            return null;
        }
        Object threadId = chunk.get("thread_id");
        if (threadId instanceof String text && !text.strip().isEmpty()) {
            return text.strip();
        }
        return null;
    }

    public void publishCancelSignal(String runId) {
        try {
            redis.opsForValue().set(cancelKey(runId), "1", Duration.ofSeconds(RUN_CANCEL_KEY_TTL_SECONDS));
        } catch (Exception exception) {
            log.warn("Failed to publish cancel signal for run {}: {}", runId, exception.getMessage());
        }
    }

    /** 并发发布一组 best-effort Run 取消信号（参考实现 asyncio.gather；Java 顺序发布，同为 best-effort）。 */
    public void publishCancelSignals(List<String> runIds) {
        for (String runId : runIds) {
            publishCancelSignal(runId);
        }
    }

    public boolean readCancelSignal(String runId) {
        String value = redis.opsForValue().get(cancelKey(runId));
        return value != null && !value.isEmpty();
    }

    /**
     * 按固定间隔读取 Redis key，直到收到取消（阻塞式轮询；对应参考实现的异步循环，
     * 由调用方工作线程承担等待）。
     */
    public boolean waitForCancelSignal(String runId, double pollIntervalSeconds) throws InterruptedException {
        double interval = Math.max(0.0, pollIntervalSeconds);
        boolean keyFailureLogged = false;
        while (true) {
            long attemptStartedAt = System.nanoTime();
            boolean readFailed = false;
            try {
                if (readCancelSignal(runId)) {
                    return true;
                }
            } catch (Exception exception) {
                if (!keyFailureLogged) {
                    log.warn("Failed to read cancel signal for run {}: {}", runId, exception.getMessage());
                    keyFailureLogged = true;
                }
                readFailed = true;
            }
            if (!readFailed) {
                keyFailureLogged = false;
            }
            long elapsedNanos = System.nanoTime() - attemptStartedAt;
            long remainingMillis =
                    (long) ((interval - elapsedNanos / 1_000_000_000.0) * 1000.0);
            if (remainingMillis > 0) {
                Thread.sleep(remainingMillis);
            }
        }
    }

    public void clearCancelSignal(String runId) {
        try {
            redis.delete(cancelKey(runId));
        } catch (Exception exception) {
            log.warn("Failed to clear cancel signal for run {}: {}", runId, exception.getMessage());
        }
    }

    public String appendRunStreamEvent(String runId, String eventType, Map<String, Object> payload, String threadId) {
        String key = eventStreamKey(runId);
        OffsetDateTime now = DateTimeUtils.utcNow();
        long nowMs = now.toInstant().toEpochMilli();
        String eventThreadId = threadId != null ? threadId : payloadThreadId(payload);
        Map<String, Object> envelope =
                buildRunEventEnvelope(runId, eventType, payload == null ? new LinkedHashMap<>() : payload, eventThreadId, DateTimeUtils.utcIsoformat(now));
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event_type", eventType);
        fields.put("payload", JSON.toJSONString(envelope));
        fields.put("ts", String.valueOf(nowMs));

        // 参考实现用非事务 pipeline 同一连接发出 XADD+EXPIRE 并取回 event_id；
        // Spring 的 xAdd 直接返回条目 id（确定性一致），EXPIRE 随后单独发出
        org.springframework.data.redis.connection.stream.RecordId recordId;
        if (RUN_EVENTS_STREAM_MAXLEN > 0) {
            recordId = redis.opsForStream().add(
                    org.springframework.data.redis.connection.stream.StreamRecords
                            .newRecord()
                            .in(key)
                            .ofStrings(fields),
                    XAddOptions.maxlen(RUN_EVENTS_STREAM_MAXLEN).approximateTrimming(true));
        } else {
            recordId = redis.opsForStream().add(
                    org.springframework.data.redis.connection.stream.StreamRecords.newRecord().in(key).ofStrings(fields));
        }
        redis.expire(key, Duration.ofSeconds(RUN_EVENTS_STREAM_TTL_SECONDS));
        return recordId.getValue();
    }

    /** 解码单条 Redis Stream 事件，兼容旧载荷并保持统一返回形状。 */
    static Map<String, Object> decodeRunStreamRow(String runId, String eventId, Map<Object, Object> fields) {
        String payloadRaw = fields.get("payload") == null ? "{}" : String.valueOf(fields.get("payload"));
        Object payload;
        try {
            payload = JSON.parseObject(payloadRaw);
        } catch (Exception exception) {
            payload = new LinkedHashMap<>();
        }

        String eventType = fields.get("event_type") == null ? "message" : String.valueOf(fields.get("event_type"));
        boolean envelopeValid =
                payload instanceof JSONObject json && Integer.valueOf(1).equals(json.getInteger("schema_version"));
        if (!envelopeValid) {
            Map<String, Object> legacy = new LinkedHashMap<>();
            legacy.put("schema_version", 1);
            legacy.put("run_id", runId);
            legacy.put("thread_id", null);
            legacy.put("event", eventType);
            legacy.put("payload", payload instanceof Map ? payload : new LinkedHashMap<>());
            legacy.put("created_at", null);
            payload = legacy;
        }

        Object tsValue = fields.get("ts");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", eventId);
        row.put("event_type", eventType);
        row.put("payload", payload);
        row.put("ts", tsValue == null ? null : Long.parseLong(String.valueOf(tsValue)));
        return row;
    }

    public List<Map<String, Object>> listRunStreamEvents(String runId, String afterSeq, int limit) {
        String key = eventStreamKey(runId);
        String normalized = normalizeAfterSeq(afterSeq);
        List<Map<String, Object>> events = new ArrayList<>();
        org.springframework.data.domain.Range<String> range =
                "0-0".equals(normalized) || normalized.isEmpty()
                        ? org.springframework.data.domain.Range.unbounded()
                        : org.springframework.data.domain.Range.from(
                                org.springframework.data.domain.Range.Bound.exclusive(normalized))
                                .to(org.springframework.data.domain.Range.Bound.unbounded());
        List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> rows =
                redis.opsForStream().range(key, range, Limit.limit().count(limit));
        if (rows == null) {
            return events;
        }
        for (var record : rows) {
            events.add(decodeRunStreamRow(runId, record.getId().getValue(), record.getValue()));
        }
        return events;
    }

    /** 从 Redis Stream 反向读取最近的 run events，返回顺序为新到旧。 */
    public List<Map<String, Object>> listRecentRunStreamEvents(String runId, int limit) {
        String key = eventStreamKey(runId);
        List<Map<String, Object>> events = new ArrayList<>();
        List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> rows =
                redis.opsForStream()
                        .reverseRange(
                                key, org.springframework.data.domain.Range.unbounded(), Limit.limit().count(limit));
        if (rows == null) {
            return events;
        }
        for (var record : rows) {
            events.add(decodeRunStreamRow(runId, record.getId().getValue(), record.getValue()));
        }
        return events;
    }

    public String getLastRunStreamSeq(String runId) {
        String key = eventStreamKey(runId);
        List<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>> rows =
                redis.opsForStream()
                        .reverseRange(
                                key, org.springframework.data.domain.Range.unbounded(), Limit.limit().count(1));
        if (rows == null || rows.isEmpty()) {
            return "0-0";
        }
        return rows.get(0).getId().getValue();
    }
}
