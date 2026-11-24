package com.wenqu.ai.agentx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 框架原生工具的中文名覆盖：这里只锁「会出现在工具卡上」的那几族，
 * 不枚举 {@code HarnessPlatformTools.NAMES} 全集——框架加一把工具就红一片，
 * 那种测试会被当成噪音删掉，反而守不住真正会漂的那份。
 */
class FrameworkToolLabelsTest {

    /**
     * 智能体团队的十九把：框架 {@code TeamTool} 的每个 action 各带一个 {@code @Tool}
     * （方法名即工具名，camelCase），连同按 action 分派的统一入口 {@code team}。
     * 漏一把的表现就是卡片流水里冒出一行英文函数名。
     */
    private static final List<String> TEAM_TOOLS = List.of(
            "team",
            "listTasks", "listClaimableTasks", "createTask", "assignTask", "claimTask", "unclaimTask",
            "completeTask", "failTask", "sendMessage", "broadcastMessage", "listMessages", "listMembers",
            "spawnMember", "shutdownMember", "submitPlan", "approvePlan", "rejectPlan", "completeTeam");

    @Test
    @DisplayName("团队工具逐把都有卡片名与悬停说明")
    void teamToolsHaveLabelAndHint() {
        assertThat(TEAM_TOOLS)
                .allSatisfy(name -> {
                    assertThat(FrameworkToolLabels.label(name)).as("label of " + name).isNotBlank();
                    assertThat(FrameworkToolLabels.hint(name)).as("hint of " + name).isNotBlank();
                });
    }

    @Test
    @DisplayName("展示名不是把工具名原样抄回来（否则界面上仍是英文）")
    void labelsAreNotBareNames() {
        assertThat(TEAM_TOOLS).allSatisfy(name ->
                assertThat(FrameworkToolLabels.label(name)).as("label of " + name).isNotEqualTo(name));
        assertThat(FrameworkToolLabels.label("spawnMember")).isEqualTo("派出团队成员");
        assertThat(FrameworkToolLabels.label("completeTeam")).isEqualTo("结束团队任务");
    }

    @Test
    @DisplayName("未知工具返回 null，由前端兜底映射，不硬造翻译")
    void unknownToolIsNull() {
        assertThat(FrameworkToolLabels.label("no_such_tool")).isNull();
        assertThat(FrameworkToolLabels.label(null)).isNull();
    }
}
