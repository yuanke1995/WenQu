package com.wenqu.ai.agentx;

import com.wenqu.ai.service.ConfigService;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.RedisDistributedStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;

/**
 * 引擎的框架基础设施单例（从 AgentscopeChatEngine 抽出）：Redis 连接、框架会话状态存储、
 * 跨副本 distributed store、模型 HTTP 传输。都是「进程内建一次、之后复用」的缓存单例。
 *
 * <p>Redis 是硬依赖（会话状态没有本地兜底：容器重建即丢、多轮对话失忆），连接失败即抛，
 * 宁可应用起不来也不带病对外服务。模型传输把连接超时压到 8 秒（框架默认 30 秒太慢），
 * 一条连接池给主链与辅助链共用。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxFrameworkInfra {

    private final ConfigService configService;
    private final RedisProperties redisProperties;

    private volatile AgentStateStore cachedStateStore;
    private volatile boolean stateStoreResolved;
    private volatile io.agentscope.harness.agent.DistributedStore cachedDistributedStore;
    private volatile boolean distributedStoreResolved;
    private volatile UnifiedJedis cachedJedis;
    private volatile boolean jedisResolved;
    private volatile OkHttpTransport cachedModelTransport;

    public AgentStateStore stateStore() {
        if (stateStoreResolved) return cachedStateStore;
        synchronized (this) {
            if (stateStoreResolved) return cachedStateStore;
            cachedStateStore = RedisAgentStateStore.builder()
                    .jedisClient(requireRedis())
                    .keyPrefix(AgentxTextUtil.firstNonBlank(configService.get("chat.agentscopeRedisPrefix"), "agentscope:session:"))
                    .build();
            stateStoreResolved = true;
            return cachedStateStore;
        }
    }

    public UnifiedJedis requireRedis() {
        if (jedisResolved && cachedJedis != null) return cachedJedis;
        synchronized (this) {
            if (jedisResolved && cachedJedis != null) return cachedJedis;
            String host = redisProperties.getHost() == null || redisProperties.getHost().isBlank()
                    ? "localhost" : redisProperties.getHost();
            int port = redisProperties.getPort() > 0 ? redisProperties.getPort() : 6379;
            String password = redisProperties.getPassword();
            try {
                DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
                        .database(Math.max(0, redisProperties.getDatabase()))
                        .ssl(redisProperties.getSsl().isEnabled());
                if (password != null && !password.isBlank()) cfg.password(password);
                UnifiedJedis jedis = new UnifiedJedis(new HostAndPort(host, port), cfg.build());
                jedis.ping();
                cachedJedis = jedis;
                jedisResolved = true;
                log.info("[agentx] 框架 Redis 已连接: {}:{} db={}", host, port, redisProperties.getDatabase());
            } catch (Throwable e) {
                // 不置 jedisResolved：下一次调用还能重试（启动自检失败后进程退出，不靠重试兜底）
                throw new IllegalStateException("框架会话状态存储依赖 Redis（" + host + ":" + port
                        + "），连接失败：" + e + "。会话状态没有本地兜底（本地文件方案已退役：容器重建即丢，"
                        + "会导致多轮对话失忆），请先恢复 Redis 再启动应用。", e);
            }
            return cachedJedis;
        }
    }

    public io.agentscope.harness.agent.DistributedStore distributedStore() {
        if (distributedStoreResolved) return cachedDistributedStore;
        synchronized (this) {
            if (distributedStoreResolved) return cachedDistributedStore;
            cachedDistributedStore = io.agentscope.extensions.redis.RedisDistributedStore
                    .fromJedis(requireRedis(), "agentscope:");
            distributedStoreResolved = true;
            log.info("[agentx] 记忆/压缩节流与 consolidation watermark 使用 Redis 跨副本协调");
            log.warn("[agentx] 多副本提示：记忆文件仍落本地 chat.agentscopeStateDir，"
                    + "请确保各副本挂同一共享卷，否则同一用户在不同副本记忆不一致");
            return cachedDistributedStore;
        }
    }

    public io.agentscope.core.model.transport.OkHttpTransport sharedModelTransport() {        io.agentscope.core.model.transport.OkHttpTransport t = cachedModelTransport;
        if (t != null) return t;
        synchronized (this) {
            if (cachedModelTransport != null) return cachedModelTransport;
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    // 连接超时 8 秒：框架默认的 30 秒是一条已经死掉的连接在慢慢超时，
                    // 重试也得等满才出字，所以把连接这一截压到 8 秒。
                    .connectTimeout(java.time.Duration.ofSeconds(8))
                    .readTimeout(java.time.Duration.ofMinutes(5))
                    .writeTimeout(java.time.Duration.ofSeconds(30))
                    .pingInterval(java.time.Duration.ofSeconds(30))
                    .connectionPool(new okhttp3.ConnectionPool(16, 5, java.util.concurrent.TimeUnit.MINUTES))
                    .build();
            cachedModelTransport = io.agentscope.core.model.transport.OkHttpTransport.builder()
                    .client(client)
                    .config(io.agentscope.core.model.transport.HttpTransportConfig.builder()
                            .connectTimeout(java.time.Duration.ofSeconds(8))
                            .build())
                    .build();
            return cachedModelTransport;
        }
    }
}
