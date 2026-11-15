package com.wenqu.ai.service.openrun;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外 run 的事件回放缓冲（方案 J2）。
 *
 * <p>现有 native 链路的事件只在进程内存 {@code ChatRunBus}，进程重启就没；而 SSE 帧从不写
 * {@code id:}，客户端也没有断点。这里给对外 run 单独准备一条短 TTL 的可回放日志：
 * 投影层每写一帧 v1 事件就 append 一次，{@code GET /v1/runs/{id}/events} 按
 * {@code Last-Event-ID} 从这里续。</p>
 *
 * <p><b>刻意不落 MySQL</b>：逐字 token 含模型输出，全量入库既有体积成本也有隐私面，
 * 而方案 06 节允许「保留期外返回快照，SDK 自动降级重拉」。所以这里是 Redis List + TTL，
 * 不是永久事件账本；过期即由调用方回 {@code resume_expired} 降级。</p>
 */
@Component
@RequiredArgsConstructor
public class RunEventReplay {

    /** 保留期：方案验收要求断线 5 分钟仍能续传，留一倍余量 */
    public static final Duration RETENTION = Duration.ofMinutes(10);
    public static final String KEY_PREFIX = "wenqu:run:events:";
    /** 「调用方主动停止过」标记：原生 stopTurn 不发 SSE 帧，终态仍是引擎的 done，见 markStopped */
    private static final String STOP_PREFIX = "wenqu:run:stop:";

    private final StringRedisTemplate redis;

    private static String key(String runId) {
        return KEY_PREFIX + runId;
    }

    /**
     * 记下这次 run 被主动停止过。
     * <p>原生 {@code ChatRunRuntime.stopTurn} 只置把手上的 stopped 标记、不写 SSE；收尾仍由引擎
     * 发 {@code done}，投影层若照单全收会把状态写成 succeeded。用 Redis 短标记把「用户停过」
     * 从控制端点带到投影层（跨副本也成立），在 done 到达时改判 stopped，与 c_ai_open_run.status
     * 和 run.done 帧保持一致。TTL 与回放缓冲同长，不会留垃圾键。</p>
     */
    public void markStopped(String runId) {
        redis.opsForValue().set(STOP_PREFIX + runId, "1", RETENTION);
    }

    /** 消费一次停止标记；返回 true 表示这轮被用户主动停过 */
    public boolean consumeStopped(String runId) {
        return Boolean.TRUE.equals(redis.delete(STOP_PREFIX + runId));
    }

    /** 追加一帧；每次 append 续期，长跑的 run 不会在跑到一半被清掉 */
    public void append(String runId, long id, String event, String dataJson) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("id", id);
        frame.put("event", event);
        frame.put("data", dataJson);
        String k = key(runId);
        redis.opsForList().rightPush(k, JSON.toJSONString(frame));
        redis.expire(k, RETENTION);
    }

    /**
     * 取 id 大于 afterId 的帧。
     *
     * @return {@code null} = 缓冲已过期或从未存在（调用方按 {@code resume_expired} 降级），
     *         空列表 = 缓冲在但暂无新帧
     */
    public List<Map<String, Object>> since(String runId, long afterId) {
        List<String> raw = redis.opsForList().range(key(runId), 0, -1);
        if (raw == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null) continue;
            Map<String, Object> f = JSON.parseObject(s, Map.class);
            Object id = f.get("id");
            if (id instanceof Number n && n.longValue() > afterId) out.add(f);
        }
        return out;
    }

    public boolean available(String runId) {
        return Boolean.TRUE.equals(redis.hasKey(key(runId)));
    }

    /** 缓冲里最后一个 id（降级时告诉客户端从哪重拉） */
    public long latestId(String runId) {
        List<Map<String, Object>> all = since(runId, -1);
        if (all == null || all.isEmpty()) return 0L;
        Object id = all.get(all.size() - 1).get("id");
        return id instanceof Number n ? n.longValue() : 0L;
    }

    /** 最后一个终态帧（run.done / run.error）的 data，供 GET /runs/{id} 与回调同口径 */
    public String terminalData(String runId) {
        List<Map<String, Object>> all = since(runId, -1);
        if (all == null) return null;
        String last = null;
        for (Map<String, Object> f : all) {
            String ev = String.valueOf(f.get("event"));
            if ("run.done".equals(ev) || "run.error".equals(ev)) last = String.valueOf(f.get("data"));
        }
        return last;
    }
}
