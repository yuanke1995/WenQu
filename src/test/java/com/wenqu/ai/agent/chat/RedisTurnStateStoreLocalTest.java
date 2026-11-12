package com.wenqu.ai.agent.chat;

import com.wenqu.ai.service.ReplicaCoordination;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 本地 Redis 集成测试（隔离 DB 15）：轮锁互斥、释放、启动清理。
 *
 * <p>不依赖 Docker；本机 6379 不可达时自动跳过。CI 里仍由
 * {@link RedisTurnStateStoreContainerTest}（Testcontainers）覆盖。
 */
class RedisTurnStateStoreLocalTest {

    private static JedisConnectionFactory factory;

    @AfterAll
    static void stop() {
        if (factory != null) factory.destroy();
    }

    @Test
    void leaseIsExclusiveAndStartupCleanupRemovesStale() {
        assumeTrue(canConnect(), "本机 Redis 不可达，跳过本地集成测试");

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        config.setDatabase(15);
        factory = new JedisConnectionFactory(config);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        flushDb(redis);

        ReplicaCoordination coordination = new ReplicaCoordination(redis);
        RedisTurnStateStore store = new RedisTurnStateStore(coordination, redis, "single");

        assertThat(store.tryAcquire("local-s1")).isTrue();
        assertThat(store.tryAcquire("local-s1")).as("同一会话第二轮必须被拒").isFalse();
        store.release("local-s1");
        assertThat(store.tryAcquire("local-s1")).as("释放后可再次占用").isTrue();

        redis.opsForValue().set("wenqu:turn:in-flight:local-stale", "stale");
        new RedisTurnStateStore(coordination, redis, "single").clearStaleOnStartup();
        assertThat(redis.hasKey("wenqu:turn:in-flight:local-stale")).as("启动清理遗留锁").isFalse();

        flushDb(redis);
    }

    private static void flushDb(StringRedisTemplate redis) {
        redis.execute((RedisCallback<Void>) conn -> {
            conn.serverCommands().flushDb();
            return null;
        });
    }

    private static boolean canConnect() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 6379), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
