package com.wisesoft.wenqu.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主智能体状态（对应参考实现 {@code agents/buildin/chatbot/state.py}）。
 *
 * <p>参考实现是 LangGraph 的 {@code TypedDict} 状态定义：
 * {@code SubAgentRunState}（子智能体运行摘要的键集与 status 枚举）、
 * {@code merge_subagent_runs}（{@code subagent_runs} 的 reducer）与
 * {@code ChatBotState(BaseState)}（在基状态上叠加 {@code subagent_runs}）。
 *
 * <p>本类逐字照搬 reducer 与键集；状态本体为 {@code Map}（与 {@link AgentState} 同口径，
 * 引擎接入时按同一键名挂载）。
 *
 * <p>reducer 语义（参考实现 docstring，逐字）：{@code run_id} 是一次真实子智能体执行的身份。
 * 只有相同 {@code run_id} 才会更新同一条记录；没有 {@code run_id} 的增量记录直接追加，
 * 不用工具调用 ID 或子线程 ID 做旧状态兼容匹配。
 *
 * <p>该 reducer 正对应 {@code SubAgentMiddleware} 产出的
 * {@code Command(update={"subagent_runs": [...]})} —— 子智能体每次状态推进都按
 * {@code run_id} 更新同一条记录，而不是追加新记录。
 */
public final class ChatBotState {

    /** 参考实现 {@code ChatBotState.subagent_runs} 状态键（逐字）。 */
    public static final String SUBAGENT_RUNS = "subagent_runs";

    /** 参考实现 {@code SubAgentRunState} 的键（顺序与 TypedDict 声明一致）。 */
    public static final List<String> SUBAGENT_RUN_STATE_KEYS = List.of(
            "id",
            "run_id",
            "subagent_slug",
            "subagent_name",
            "child_thread_id",
            "description",
            "status",
            "created_at",
            "completed_at",
            "error",
            "artifacts",
            "events_url",
            "result_url");

    /** 参考实现 {@code SubAgentRunState.status} 的 Literal 枚举（顺序逐字）。 */
    public static final List<String> SUBAGENT_RUN_STATUSES = List.of(
            "pending",
            "running",
            "completed",
            "failed",
            "cancel_requested",
            "cancelled",
            "interrupted");

    private ChatBotState() {}

    /**
     * 增量合并父 Agent 记录的子智能体运行摘要（对应 {@code merge_subagent_runs}）。
     *
     * @param existing 已有记录（{@code null} 视为空）
     * @param newRuns  增量记录（{@code null} 时原样返回 {@code existing}）
     */
    public static List<Map<String, Object>> mergeSubagentRuns(
            List<Map<String, Object>> existing, List<Map<String, Object>> newRuns) {
        if (existing == null) {
            return newRuns == null ? new ArrayList<>() : new ArrayList<>(newRuns);
        }
        if (newRuns == null) {
            return existing;
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> item : existing) {
            merged.add(item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item));
        }
        Map<String, Integer> runIdIndex = new LinkedHashMap<>();
        for (int position = 0; position < merged.size(); position++) {
            Object runId = merged.get(position).get("run_id");
            if (runId != null) {
                runIdIndex.put(String.valueOf(runId), position);
            }
        }

        for (Map<String, Object> item : newRuns) {
            Map<String, Object> run = item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item);
            Object rawRunId = run.get("run_id");
            String runId = rawRunId == null ? null : String.valueOf(rawRunId);
            Integer position = null;
            if (runId != null && runIdIndex.containsKey(runId)) {
                position = runIdIndex.get(runId);
            }
            if (position == null) {
                position = merged.size();
                merged.add(run);
            } else {
                Map<String, Object> updated = merged.get(position);
                updated.putAll(run);
                merged.set(position, updated);
            }
            if (runId != null) {
                runIdIndex.put(runId, position);
            }
        }
        return merged;
    }
}
