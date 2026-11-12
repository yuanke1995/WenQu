package com.wenqu.ai.agent.chat;

import com.wenqu.ai.service.ReplicaCoordination;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1-1 起点：在真 Redis 上验证轮锁租约与单副本启动清理。
 * <p>覆盖 S0-3 的 Redis 实现路径：同会话第二问被拒、释放后可再占、启动清掉遗留锁。
 * Docker 不可用时自动跳过。
 */
@Tag("container")
@Testcontainers(disabledWithoutDocker = true)
class RedisTurnStateStoreContainerTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static JedisConnectionFactory factory;

    @AfterAll
    static void stop() {
        if (factory != null) factory.destroy();
    }

    @Test
    void leaseIsExclusiveAndStartupCleanupRemovesStale() {
        factory = new JedisConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        ReplicaCoordination coordination = new ReplicaCoordination(redis);
        RedisTurnStateStore store = new RedisTurnStateStore(coordination, redis, "single");

        assertThat(store.tryAcquire("s1")).isTrue();
        assertThat(store.tryAcquire("s1")).as("同一会话第二轮必须被拒").isFalse();

        store.release("s1");
        assertThat(store.tryAcquire("s1")).as("释放后可再次占用").isTrue();

        redis.opsForValue().set("wenqu:turn:in-flight:stale", "stale");
        new RedisTurnStateStore(coordination, redis, "single").clearStaleOnStartup();
        assertThat(redis.hasKey("wenqu:turn:in-flight:stale")).as("启动清理遗留锁").isFalse();
    }
}
