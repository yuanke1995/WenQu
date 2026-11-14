package com.wenqu.ai.agent.goal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标模式运营参数的读取口径。
 *
 * <p>预算类参数已退役为代码常量（2026-11-14）：轮数上限恒 {@link GoalSettings#MAX_ROUNDS}，
 * token/时长/工具调用三项硬预算整个机制随深度执行设置面板退役。
 * 剩余旋钮（调度/恢复/核验）值固定在 ConfigDefaults，读取只走钳制不走配置。
 */
class GoalSettingsTest {

    @Test
    @DisplayName("轮数上限恒为代码常量 20，不再读配置")
    void maxRoundsIsConstant() {
        assertThat(GoalSettings.MAX_ROUNDS).isEqualTo(20);
    }

    @Test
    @DisplayName("完成检查/核验失败上限恒为不限制哨兵")
    void checkAndVerifyLimitsAreUnlimited() {
        GoalSettings s = new GoalSettings();
        assertThat(s.maxCompletionChecks()).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.maxVerifyFailures()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("其余旋钮按既有范围钳制（默认值行为不变）")
    void otherKnobsStillClamp() {
        GoalSettings s = new GoalSettings();
        assertThat(s.nextRoundDelayMs()).isEqualTo(2000L);
        assertThat(s.softBudgetRatio()).isEqualTo(0.8);
        assertThat(s.workers()).isEqualTo(4);
        assertThat(s.leaseSeconds()).isEqualTo(300);
    }
}
