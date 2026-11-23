package com.wenqu.ai.agentx.team;

import com.alibaba.fastjson2.JSON;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentScope 框架 {@code MessageBus} 的 Redis 实现：让 {@code TeamsMiddleware} 的 wakeup、
 * {@code InboxMiddleware} 的收件箱注入在问渠真正跑起来（问渠过去用 {@code NoopBusComponents}
 * 把它顶成恒空，团队消息唤醒因此失效）。
 *
 * <p>只实现框架实际会用的原语：队列（Mode A，inbox / wakeup）、日志（Mode C，会话事件回放）
 * 与广播（Mode D）。{@code subscribe} 返回空流——问渠的唤醒不走框架的 signal 订阅，而是由
 * {@link #enqueueWakeup} 直接回调宿主注册的 {@link WakeupHandler}（框架只要求“入队 + 触发”，
 * 触发这一脚由宿主承接，和网关在框架里的角色一致）。
 *
 * <p>非团队会话仍用 {@code NoopBusComponents.MESSAGE_BUS}：本类只在团队会话实例上挂载，
 * 不改变普通问答的每轮开销。
 *
 * @author yuanke
 */
@Component
public class WenquRedisMessageBus implements MessageBus {

    /** 宿主承接“该起一轮了”：由问答侧注册，入参 (userId, sessionId, agentId)。 */
    @FunctionalInterface
    public interface WakeupHandler {
        void wake(String userId, String sessionId, String agentId);
    }

    private final StringRedisTemplate redis;
    private final AtomicReference<WakeupHandler> wakeupHandler = new AtomicReference<>();

    public WenquRedisMessageBus(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 注册唤醒承接器（问答侧在启动时绑定；未绑定时唤醒只入队、等下一轮被 InboxMiddleware 读到）。 */
    public void bindWakeup(WakeupHandler handler) {
        wakeupHandler.set(handler);
    }

    // ---- Mode A: drain queue ----

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.fromCallable(() -> {
            redis.opsForList().rightPush(key, JSON.toJSONString(payload));
            Long seq = redis.opsForValue().increment(key + ":seq");
            return seq == null ? UUID.randomUUID().toString() : String.valueOf(seq);
        });
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.fromCallable(() -> {
            List<BusEntry> out = new ArrayList<>();
            int cap = maxCount <= 0 ? 100 : maxCount;
            for (int i = 0; i < cap; i++) {
                String raw = redis.opsForList().leftPop(key);
                if (raw == null) break;
                out.add(new BusEntry(UUID.randomUUID().toString(), parse(raw)));
            }
            return out;
        });
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        return Mono.fromRunnable(() -> {
            redis.delete(key);
            redis.delete(key + ":seq");
        });
    }

    @Override
    public Mono<Boolean> queuePeek(String key) {
        return Mono.fromCallable(() -> {
            Long size = redis.opsForList().size(key);
            return size != null && size > 0;
        });
    }

    // ---- Mode C: replay log ----

    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return Mono.fromCallable(() -> {
            Long seq = redis.opsForValue().increment(key + ":seq");
            String id = seq == null ? UUID.randomUUID().toString() : String.valueOf(seq);
            String envelope = JSON.toJSONString(Map.of("id", id, "p", payload));
            redis.opsForList().rightPush(key, envelope);
            if (maxLen > 0) {
                redis.opsForList().trim(key, -maxLen, -1);
            }
            return id;
        });
    }

    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        return Mono.fromCallable(() -> {
            List<String> raw = redis.opsForList().range(key, 0, -1);
            if (raw == null) return List.of();
            long cursor = parseLong(since, 0L);
            int cap = maxCount <= 0 ? 100 : maxCount;
            List<BusEntry> out = new ArrayList<>();
            for (String one : raw) {
                Map<String, Object> env = parse(one);
                String id = String.valueOf(env.get("id"));
                if (parseLong(id, 0L) <= cursor) continue;
                Object p = env.get("p");
                out.add(new BusEntry(id, p instanceof Map<?, ?> m ? cast(m) : Map.of()));
                if (out.size() >= cap) break;
            }
            return out;
        });
    }

    @Override
    public Mono<Void> logTrim(String key) {
        return Mono.fromRunnable(() -> {
            redis.delete(key);
            redis.delete(key + ":seq");
        });
    }

    // ---- Mode D: transient broadcast ----

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return Mono.fromRunnable(() -> redis.convertAndSend(key, JSON.toJSONString(payload)));
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        // 唤醒不走 signal 订阅（见类注释）；会话事件回放走 logRead。返回空流即可。
        return Flux.empty();
    }

    /**
     * 唤醒：先把 notice 入目标会话收件箱（由 {@link #inboxPush} 的默认实现落到
     * {@code agentscope:inbox:{sessionId}}），再回调宿主起一轮。宿主负责“已有活轮就只入队、
     * 不重复起轮”的准入。
     */
    @Override
    public Mono<Void> enqueueWakeup(String userId, String sessionId, String agentId) {
        return Mono.fromRunnable(() -> {
            WakeupHandler h = wakeupHandler.get();
            if (h != null && sessionId != null && !sessionId.isBlank()) {
                h.wake(userId == null ? "" : userId, sessionId, agentId);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        Object o = JSON.parseObject(json);
        return o instanceof Map<?, ?> m ? cast(m) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static long parseLong(String s, long dft) {
        if (s == null) return dft;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return dft;
        }
    }
}
