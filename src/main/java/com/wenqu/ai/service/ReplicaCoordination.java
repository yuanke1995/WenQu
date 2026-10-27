package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 多副本协调的<b>唯一</b>原语层：租约（谁持有这条资源）、广播（发给所有副本）、请求/应答（问持有者要东西）。
 *
 * <p>为什么要单独一层：总线（{@link RunBusCluster}）与框架会话（{@code AgentxCluster}）要的都是同三件事
 * ——「这把现在归谁」「广播一条」「问持有者要一份回话」——各写一遍就会漂：一处用 SETNX 一处用 HSET、
 * 一处超时 2 秒一处 5 秒，将来改语义就得两头对账。这里只放机制，<b>不含任何业务判据</b>：
 * 键名与「什么算属于我」由上层决定，本层只保证原子性与收口。
 *
 * <p>框架口径：租约用 Redis 的 {@code SET key value NX PX ttl} 与一段比对持有者再删的 Lua
 * （比较删除不能靠「先 GET 再 DEL」——中间被别人抢走就会误删别人的租约）；
 * 广播与应答用 Spring Data 的 {@link RedisMessageListenerContainer}；不自建线程池、不手写 RESP。
 *
 * <p>Redis 不可用时所有方法都<b>如实返回「没查到/没抢到」</b>而不是抛异常：上层据此退回单副本语义
 * （协调层坏了不该打断正在跑的业务），但绝不伪造「归我」——否则会两个副本同时认为自己持有同一条资源。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ReplicaCoordination {

    /** 只有持有者能续期 */
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);
    /** 只有持有者能释放（避免误删别人的租约） */
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    private final String replicaId;
    private final Map<String, BiConsumer<JSONObject, String>> listeners = new ConcurrentHashMap<>();
    /** 等待中的应答：requestId → 闩 + 结果 */
    private final Map<String, Wait> pending = new ConcurrentHashMap<>();

    private static final class Wait {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile JSONObject reply;
    }

    public ReplicaCoordination(StringRedisTemplate redis) {
        this.redis = redis;
        this.container.setConnectionFactory(redis.getConnectionFactory());
        this.replicaId = resolveReplicaId();
    }

    private static String resolveReplicaId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "node";
        }
        return host + "#" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 本副本标识：写进租约值与每条广播，收口时认出「这条是我自己发的」 */
    public String replicaId() {
        return replicaId;
    }

    @PostConstruct
    void start() {
        container.afterPropertiesSet();
        container.start();
        log.info("[CLUSTER] 副本协调层已启动 replica={}", replicaId);
    }

    @PreDestroy
    void stop() {
        try {
            container.stop();
            container.destroy();
        } catch (Exception e) {
            log.warn("[CLUSTER] 协调层停止异常: {}", e.getMessage());
        }
    }

    // ==================== 租约 ====================

    /**
     * 抢占租约（{@code SET NX PX}）。
     *
     * @return true=这把归我了；false=别人持有、或 Redis 不可用（调用方按「不是我的」处理，不能当作归自己）
     */
    public boolean acquire(String key, Duration ttl) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, replicaId, ttl));
        } catch (Exception e) {
            log.warn("[CLUSTER] 抢占租约失败（按没抢到处理）: key={} {}", key, e.getMessage());
            return false;
        }
    }

    /** 强制占住（权威那一路用：新一轮的提问者就是比续跑跟随者优先，覆盖不报错） */
    public void claim(String key, Duration ttl) {
        try {
            redis.opsForValue().set(key, replicaId, ttl);
        } catch (Exception e) {
            log.warn("[CLUSTER] 占位失败: key={} {}", key, e.getMessage());
        }
    }

    /** 释放（只释放自己那一份）；Redis 不可用时什么都不做，交给 TTL 过期 */
    public void release(String key) {
        try {
            redis.execute(RELEASE, List.of(key), replicaId);
        } catch (Exception e) {
            log.warn("[CLUSTER] 释放租约失败（交给 TTL）: key={} {}", key, e.getMessage());
        }
    }

    /** 续期（只续自己那一份）；没续上说明已被别人接管，调用方该停下来 */
    public boolean renew(String key, Duration ttl) {
        try {
            Long ok = redis.execute(RENEW, List.of(key), replicaId, String.valueOf(ttl.toMillis()));
            return ok != null && ok == 1L;
        } catch (Exception e) {
            return false;
        }
    }

    /** 当前持有者标识；null=没人持有或 Redis 不可用（这两者在上层都按「没人持有」处理） */
    public String holder(String key) {
        try {
            Object v = redis.opsForValue().get(key);
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否有人持有（Redis 不可用返回 false，上层退回本副本判据） */
    public boolean held(String key) {
        return holder(key) != null;
    }

    /** 是不是本副本持有 */
    public boolean heldByMe(String key) {
        return replicaId.equals(holder(key));
    }

    // ==================== 广播 ====================

    /**
     * 注册一个频道的处理方（同一频道名只留最后一份，重复注册即覆盖并当场报错——
     * 两个业务抢同一频道名说明命名撞了，静默覆盖会让其中一个永远收不到消息）。
     *
     * @param handler 收到载荷时的回调；{@code from} 是发送方副本 id，<b>本副本自己发的已经被滤掉</b>
     */
    public void listen(String channel, BiConsumer<JSONObject, String> handler) {
        if (listeners.putIfAbsent(channel, handler) != null) {
            throw new IllegalStateException("频道 " + channel + " 已有处理方，命名撞了");
        }
        container.addMessageListener((message, pattern) -> {
            JSONObject obj = parse(message);
            if (obj == null) return;
            String from = obj.getString("_from");
            if (replicaId.equals(from)) return;
            try {
                handler.accept(obj, from);
            } catch (Exception e) {
                log.warn("[CLUSTER] 频道 {} 处理异常: {}", channel, e.toString());
            }
        }, new ChannelTopic(channel));
    }

    /** 广播一条（自动带上本副本标识） */
    public void broadcast(String channel, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>(payload);
        envelope.put("_from", replicaId);
        try {
            redis.convertAndSend(channel, JSON.toJSONString(envelope));
        } catch (Exception e) {
            log.debug("[CLUSTER] 广播失败（频道 {}）: {}", channel, e.getMessage());
        }
    }

    // ==================== 请求/应答（问持有者要东西） ====================

    /**
     * 发一次请求并等应答。应答频道由上层约定成「{@code replyPrefix + 本副本 id}」，
     * 那个前缀同时就是本层已经监听的频道名——所以本副本要能收到回答，上层得先
     * {@link #listen} 好它自己那条应答频道。
     *
     * @return 应答载荷；超时或失败返回 null（调用方如实处理，不许拿空对象糊过去）
     */
    public JSONObject request(String reqChannel, Map<String, Object> payload, Duration timeout) {
        String requestId = UUID.randomUUID().toString();
        Wait wait = new Wait();
        pending.put(requestId, wait);
        Map<String, Object> envelope = new LinkedHashMap<>(payload);
        envelope.put("_reqId", requestId);
        broadcast(reqChannel, envelope);
        try {
            if (!wait.latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
            return wait.reply;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pending.remove(requestId);
        }
    }

    /** 回应一次请求（{@code replyTo} 是发起方本副本的应答频道名） */
    public void respond(JSONObject request, Map<String, Object> payload) {
        String requestId = request.getString("_reqId");
        String replyChannel = request.getString("_replyTo");
        if (requestId == null || replyChannel == null) return;
        Map<String, Object> envelope = new LinkedHashMap<>(payload);
        envelope.put("_reqId", requestId);
        broadcast(replyChannel, envelope);
    }

    /**
     * 登记本副本的应答频道：收到的载荷按 {@code _reqId} 交给对应的等待方。
     * <p>返回值是这一台的频道名，发起方要把它放进请求的 {@code _replyTo}，应答才找得到回路上。
     */
    public String replyChannel(String prefix) {
        String channel = prefix + replicaId;
        listen(channel, (payload, from) -> {
            Wait wait = pending.get(payload.getString("_reqId"));
            if (wait == null) return;
            wait.reply = payload;
            wait.latch.countDown();
        });
        return channel;
    }

    private static JSONObject parse(Message message) {
        try {
            return JSON.parseObject(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** 运维可见性：Redis 此刻通不通（协调层不报错给业务，只在这里露一次） */
    public boolean available() {
        try {
            return Boolean.TRUE.equals(redis.hasKey("wenqu:cluster:probe"));
        } catch (Exception e) {
            return false;
        }
    }
}
