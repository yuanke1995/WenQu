package com.wenqu.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.util.List;

/**
 * 守卫的多副本实现（<b>骨架，默认不启用</b>——{@code project.turnGuard.store=redis} 时才装配）。
 *
 * <p>租约落在 Redis：{@code SET NX PX} 原子占位 + 按 turnId 比对的 Lua 续约/释放，TTL 到点
 * Redis 自愈过期——与 {@link JvmProjectTurnGuard} 同一套语义，持锁方崩溃不再把工作区锁死。
 *
 * <p><b>启用前置（缺一不可，见 {@code ProjectTurnMutex} 时代就写明的取舍）</b>：多副本部署意味着
 * 两个副本的轮次会落到<b>同一份共用工作目录</b>上，锁挪到 Redis 只是让它们排起队来；所有跑沙盒的
 * 副本还必须共享同一份工作目录存储，否则排队排得再好，各写各的盘仍是两份目录。存储收拢之前，
 * 这个实现保持停用。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "project.turnGuard.store", havingValue = "redis")
public class RedisProjectTurnGuard implements ProjectTurnGuard {

    private static final String KEY_PREFIX = "wenqu:turnguard:";

    private final RedisProperties redisProperties;
    private final ObjectMapper objectMapper;
    private final ConfigService configService;

    private volatile JedisPooled jedis;

    private JedisPooled jedis() {
        if (jedis == null) {
            synchronized (this) {
                if (jedis == null) {
                    DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
                            .connectionTimeoutMillis(5000)
                            .socketTimeoutMillis(5000);
                    if (redisProperties.getPassword() != null && !redisProperties.getPassword().isBlank()) {
                        cfg.password(redisProperties.getPassword());
                    }
                    jedis = new JedisPooled(new HostAndPort(redisProperties.getHost(), redisProperties.getPort()),
                            cfg.build());
                }
            }
        }
        return jedis;
    }

    private long ttlMs() {
        long v = configService.getLong("project.turnGuard.leaseTtlMs", JvmProjectTurnGuard.DEFAULT_LEASE_TTL_MS);
        return v > 0 ? v : JvmProjectTurnGuard.DEFAULT_LEASE_TTL_MS;
    }

    private String keyOf(String projectId) {
        return KEY_PREFIX + projectId;
    }

    private String serialize(TurnLease lease) {
        try {
            return objectMapper.writeValueAsString(lease);
        } catch (Exception e) {
            throw new IllegalStateException("租约序列化失败", e);
        }
    }

    private TurnLease deserialize(String json) {
        try {
            return objectMapper.readValue(json, TurnLease.class);
        } catch (Exception e) {
            log.warn("[PROJECT] 租约反序列化失败，按无租约处理: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean tryBegin(String projectId, TurnLease lease) {
        if (projectId == null || projectId.isBlank()) return true;
        long ttl = ttlMs();
        // 载荷里的 deadline 与键的 PX 同源同值：调用方只带身份不带期限，期限归守卫管
        TurnLease withDeadline = new TurnLease(lease.turnId(), lease.sessionId(), lease.kind(),
                lease.name(), lease.uid(), lease.startedAt(), System.currentTimeMillis() + ttl);
        // 原子 create-if-absent：键不存在才写入租约载荷并设 PX；拿不到即已被占
        Object r = jedis().eval(
                "if redis.call('exists', KEYS[1]) == 0 then "
                        + "redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2]) return 1 "
                        + "else return 0 end",
                List.of(keyOf(projectId)), List.of(serialize(withDeadline), String.valueOf(ttl)));
        return Long.valueOf(1L).equals(r);
    }

    @Override
    public void renew(String projectId, String turnId, long ttlMs) {
        if (projectId == null || projectId.isBlank()) return;
        jedis().eval(
                "local v = redis.call('get', KEYS[1]) "
                        + "if v and string.find(v, '\"' .. ARGV[1] .. '\"') then "
                        + "redis.call('pexpire', KEYS[1], ARGV[2]) end",
                List.of(keyOf(projectId)), List.of(String.valueOf(turnId), String.valueOf(ttlMs)));
    }

    @Override
    public void end(String projectId, String turnId) {
        if (projectId == null || projectId.isBlank()) return;
        jedis().eval(
                "local v = redis.call('get', KEYS[1]) "
                        + "if v and string.find(v, '\"' .. ARGV[1] .. '\"') then "
                        + "redis.call('del', KEYS[1]) end",
                List.of(keyOf(projectId)), List.of(String.valueOf(turnId)));
    }

    @Override
    public TurnLease current(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        String v = jedis().get(keyOf(projectId));
        if (v == null) return null;
        TurnLease lease = deserialize(v);
        if (lease == null || lease.expired()) {
            // TTL 是主自愈（PX 到点键就没了）；payload 期限是双保险（renew 走 pexpire，两钟同频）
            jedis().eval(
                    "local v = redis.call('get', KEYS[1]) "
                            + "if v then "
                            + "  local ok, lease = pcall(cjson.decode, v) "
                            + "  if ok and lease.deadline and tonumber(lease.deadline) < tonumber(ARGV[1]) then "
                            + "    redis.call('del', KEYS[1]) "
                            + "  end "
                            + "end",
                    List.of(keyOf(projectId)), List.of(String.valueOf(System.currentTimeMillis())));
            return null;
        }
        return lease;
    }

    /** 供启动期自检：连不上 Redis 会在首次取锁时 fail-loud，这里提前探一次 */
    public boolean ping() {
        try {
            return "PONG".equals(jedis().ping());
        } catch (Exception e) {
            log.warn("[PROJECT] Redis 守卫连通性自检失败: {}", e.getMessage());
            return false;
        }
    }
}
