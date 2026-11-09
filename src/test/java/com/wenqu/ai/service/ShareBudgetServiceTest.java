package com.wenqu.ai.service;

import com.wenqu.ai.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分享游客额度闸判定核：分享/访客轮次上限、token 上限、Redis 故障 fail-closed。
 * Redis 用 Mockito 假件，不起上下文。
 */
class ShareBudgetServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ConfigService config;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        config = mock(ConfigService.class);
        // 出厂默认已改为 0=不限制；这里显式给有限值测「设了限额时」的闸判定
        when(config.getInt("share.guestDailyTurns", 0)).thenReturn(2);
        when(config.getInt("share.guestDailyTurnsPerVisitor", 0)).thenReturn(1);
        when(config.getLong("share.guestDailyTokens", 0L)).thenReturn(10L);
    }

    @Test
    @DisplayName("分享级轮次超限：429")
    void rejectsWhenShareTurnsExceeded() {
        // 第 1 次分享级计数 1，访客级 1；第 2 次分享级 2（未超），访客级 2（超 1）→ 拒绝
        when(ops.increment(anyString())).thenReturn(1L, 1L, 2L, 2L);
        ShareBudgetService svc = new ShareBudgetService(redis, config);
        svc.checkGuestTurn("s1", "visitor:v1");
        assertThatThrownBy(() -> svc.checkGuestTurn("s1", "visitor:v1"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 429);
    }

    @Test
    @DisplayName("token 日额度用满：下一轮前 429")
    void rejectsWhenTokensExhausted() {
        when(ops.get(anyString())).thenReturn("10");
        ShareBudgetService svc = new ShareBudgetService(redis, config);
        assertThatThrownBy(() -> svc.checkGuestTurn("s1", "visitor:v1"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 429);
    }

    @Test
    @DisplayName("Redis 故障：fail-closed（503，而不是放行）")
    void failsClosedWhenRedisDown() {
        when(ops.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        ShareBudgetService svc = new ShareBudgetService(redis, config);
        assertThatThrownBy(() -> svc.checkGuestTurn("s1", "visitor:v1"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("code", 503);
    }

    @Test
    @DisplayName("addTokens：当日 token 累加失败不影响主链路")
    void addTokensBestEffort() {
        when(ops.increment(anyString(), anyLong())).thenThrow(new RuntimeException("redis down"));
        ShareBudgetService svc = new ShareBudgetService(redis, config);
        svc.addTokens("s1", 5); // 不抛
    }
}
