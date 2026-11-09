package com.wenqu.ai.agent.goal;

import com.wenqu.ai.service.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标模式运营参数的读取口径（2026-11 默认放开批次）。
 *
 * <p>钉死三件事：出厂默认即「不限制」；0 走 UNLIMITED 哨兵而不是被 clamp 夹成下限；
 * 正数仍按既有范围钳制（管理员手动收紧时行为不变）。
 */
class GoalSettingsTest {

    /** 脚本化配置源：只回放 Map 里的键，缺行返回调用方给的 def（与真实 getInt/getLong 同语义）。 */
    private static GoalSettings settingsWith(Map<String, String> values) {
        ConfigService fake = new ConfigService(null, null, null, null, null, null, null, null) {
            @Override
            public int getInt(String key, int def) {
                String v = values.get(key);
                return v == null ? def : Integer.parseInt(v.trim());
            }

            @Override
            public long getLong(String key, long def) {
                String v = values.get(key);
                return v == null ? def : Long.parseLong(v.trim());
            }
        };
        return new GoalSettings(fake);
    }

    @Test
    @DisplayName("缺行（未配置）时全部按不限制出厂")
    void defaultsAreUnlimited() {
        GoalSettings s = settingsWith(new HashMap<>());
        assertThat(GoalSettings.DEFAULT_MAX_ROUNDS).isZero();
        assertThat(s.maxRounds()).isEqualTo(GoalSettings.UNLIMITED);
        assertThat(s.maxTokens()).isEqualTo(GoalSettings.UNLIMITED_LONG);
        assertThat(s.maxMs()).isEqualTo(GoalSettings.UNLIMITED_LONG);
        assertThat(s.maxToolCalls()).isEqualTo(GoalSettings.UNLIMITED);
        assertThat(s.maxCompletionChecks()).isEqualTo(GoalSettings.UNLIMITED);
        assertThat(s.maxVerifyFailures()).isEqualTo(GoalSettings.UNLIMITED);
    }

    @Test
    @DisplayName("显式 0 走 UNLIMITED 哨兵，不被 clamp 夹成下限")
    void zeroMeansUnlimitedForEveryBudget() {
        Map<String, String> v = new HashMap<>();
        v.put("chat.goal.maxRounds", "0");
        v.put("chat.goal.maxTokens", "0");
        v.put("chat.goal.maxMs", "0");
        v.put("chat.goal.maxToolCalls", "0");
        GoalSettings s = settingsWith(v);
        assertThat(s.maxRounds()).isEqualTo(GoalSettings.UNLIMITED);
        assertThat(s.maxTokens()).isEqualTo(GoalSettings.UNLIMITED_LONG);
        assertThat(s.maxMs()).isEqualTo(GoalSettings.UNLIMITED_LONG);
        assertThat(s.maxToolCalls()).isEqualTo(GoalSettings.UNLIMITED);
        // 完成检查/核验失败上限已固定为常量 0=不限制
        assertThat(s.maxCompletionChecks()).isEqualTo(GoalSettings.UNLIMITED);
        assertThat(s.maxVerifyFailures()).isEqualTo(GoalSettings.UNLIMITED);
    }

    @Test
    @DisplayName("正数仍按既有范围钳制（管理员收紧时行为不变）")
    void positiveValuesStillClamp() {
        Map<String, String> v = new HashMap<>();
        v.put("chat.goal.maxRounds", "40");
        v.put("chat.goal.maxTokens", "2000000");
        v.put("chat.goal.maxMs", "7200000");
        v.put("chat.goal.maxToolCalls", "400");
        GoalSettings s = settingsWith(v);
        assertThat(s.maxRounds()).isEqualTo(40);
        assertThat(s.maxTokens()).isEqualTo(2_000_000L);
        assertThat(s.maxMs()).isEqualTo(7_200_000L);
        assertThat(s.maxToolCalls()).isEqualTo(400);

        v.put("chat.goal.maxRounds", "99999");
        v.put("chat.goal.maxTokens", "99999999999");
        assertThat(s.maxRounds()).isEqualTo(5000);
        assertThat(s.maxTokens()).isEqualTo(100_000_000L);
    }
}
