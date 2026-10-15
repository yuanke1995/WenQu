package com.wenqu.ai.agentx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 人在回路「等待预算按卡种分流」的回归账。
 * <p>这条线上真机踩过一次，且症状极具误导性：模型问「你打算把问渠部署到什么环境」，
 * 用户两分钟没回（读文档、开会），整轮就被判超时收尾，只留下一句
 * 「等待确认超时，这一轮没有继续执行」——本该由模型拿默认值继续答完。
 * <p>根因是形态判定只分了「计划卡 vs 其余」，提问卡被塞进了 {@code chat.approvalTimeoutMs}（120s），
 * 比它本该有的 {@code chat.askTimeoutMs}（600s）短了 5 倍；而超时那条路当时还只有「收轮」一种结局。
 * 两处都在本测试的判据范围内：形态必须分清三档，且<b>提问与审批不能共用一档</b>。
 */
@DisplayName("框架引擎：待办形态判定（提问/计划/审批各走各的等待预算）")
class AgentxPendingKindTest {

    /** 框架把工具挂起记成 external_execution（{@code AskUserTool} 抛 ToolSuspendException 的实测形状）。 */
    private static Map<String, Object> askPending() {
        return Map.of("kind", "external_execution",
                "call", Map.of("name", "askUser", "input",
                        Map.of("question", "你打算把问渠部署到什么环境？", "options", List.of("云服务器"))));
    }

    /** 工具执行前确认 = confirmation + 真实工具名。 */
    private static Map<String, Object> approvalPending(String tool) {
        return Map.of("kind", "confirmation",
                "call", Map.of("name", tool, "input", Map.of("command", "ls")));
    }

    @Test
    @DisplayName("工具挂起（askUser）判为提问，不是审批——这一条就是 2026-10-15 那次超时的根因")
    void askUser挂起判为提问() {
        assertEquals("ASK", AgentscopeChatEngine.pendingKindName(List.of(askPending())));
    }

    @Test
    @DisplayName("计划卡判为计划，工具审批判为审批，两者不混")
    void 计划与审批各归各() {
        assertEquals("PLAN", AgentscopeChatEngine.pendingKindName(
                List.of(approvalPending("plan_enter"))));
        assertEquals("APPROVAL", AgentscopeChatEngine.pendingKindName(
                List.of(approvalPending("run_shell"))));
    }

    @Test
    @DisplayName("混挂时提问优先于计划与审批：提问最需要的是不替用户做决定")
    void 混挂时以提问为准() {
        assertEquals("ASK", AgentscopeChatEngine.pendingKindName(
                List.of(approvalPending("run_shell"), askPending())));
        // 顺序无关：计划卡排在前面也仍然按提问走，否则「先批准计划、后被追问」这一轮会被按计划超时掐掉
        assertEquals("ASK", AgentscopeChatEngine.pendingKindName(
                List.of(askPending(), approvalPending("plan_enter"))));
    }

    @Test
    @DisplayName("未知 kind 归提问（宁可多等，也不把人的一句话当拒绝）；空批按审批")
    void 未知与空批() {
        assertEquals("ASK", AgentscopeChatEngine.pendingKindName(
                List.of(Map.of("kind", "some_future_kind", "call", Map.of("name", "x")))));
        assertEquals("ASK", AgentscopeChatEngine.pendingKindName(List.of(Map.of())));
        assertEquals("APPROVAL", AgentscopeChatEngine.pendingKindName(List.of()));
        assertEquals("APPROVAL", AgentscopeChatEngine.pendingKindName(null));
    }
}
