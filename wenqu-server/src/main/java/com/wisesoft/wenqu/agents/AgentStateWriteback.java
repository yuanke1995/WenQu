package com.wisesoft.wenqu.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run 级 agent state 写回缓冲（<b>平台差异专用，参考实现无对应物</b>）。
 *
 * <p>参考实现里"把结果写回 LangGraph state"由 {@code Command(update={...})} 完成：
 * 中间件（{@code wrap_model_call} 返回 {@code ExtendedModelResponse(command=...)}）
 * 与工具（返回 {@code Command}）都能直接改 state。本工程的两类承载点都没有这个能力 ——
 * {@code ModelInterceptor} 只能返回 {@code ModelResponse}、
 * Spring AI 的 {@code ToolCallback} 只能返回字符串 —— 移植时把这一动作<em>抽成载体</em>，
 * 并声明"由构图方/节点负责落 state"，但落 state 的一侧<strong>从未接线</strong>：
 *
 * <ul>
 *   <li>{@code TokenUsageMiddleware} 把快照写进 {@code ModelRequest} 的 context，无人读取；</li>
 *   <li>{@code SubAgentMiddleware.drainPendingCommands()} 无任何调用方；</li>
 *   <li>{@code BuildinTools.presentArtifacts(...)} 无任何调用方（工具本身未注册）；</li>
 * </ul>
 *
 * 结果是 {@code token_usage} / {@code artifacts} / {@code subagent_runs} 从未进入
 * {@code OverAllState}，checkpoint 里查不到这些键，`/api/chat/thread/{id}/state` 的
 * {@code agent_state} 恒为空壳，前端状态面板只剩「暂无状态内容」。
 *
 * <p>本类承载那条缺失的通道：各生产点把"要写回 state 的增量"暂存在
 * {@link BaseContext} 的动态槽位（{@link #BUFFER_KEY}）里，
 * {@link com.wisesoft.wenqu.agents.middlewares.AgentStateWritebackHook} 在每次模型调用后
 * 取出并返回，框架把该 Map 合并回 {@code OverAllState} —— 与参考实现
 * {@code Command(update=...)} 的落点等价。
 *
 * <p>累积语义按参考实现的 reducer 照搬：{@code artifacts} 走
 * {@link AgentState#mergeArtifacts}（保序去重）、{@code subagent_runs} 走
 * {@link ChatBotState#mergeSubagentRuns}（按 {@code run_id} 更新同一条），
 * {@code token_usage} / {@code todos} 为整体替换。
 */
public final class AgentStateWriteback {

    /** 缓冲在 {@link BaseContext} 动态槽位里的键。 */
    public static final String BUFFER_KEY = "__agent_state_writeback__";

    /**
     * 运行时上下文输入表在 {@link BaseContext} 动态槽位里的键。
     *
     * <p>该表就是 {@code RunnableConfig.context()["context"]} 指向的<b>同一个 Map 实例</b>：
     * 框架把 {@code RunnableConfig.context()} 整体作为 {@code ModelRequest.context} 交给拦截器，
     * 而 {@code TokenUsageMiddleware} 约定那里的 {@code "context"} 是 Map
     * （取 {@code messages} / {@code model} / {@code model_profile} / {@code token_usage} /
     * {@code summary_threshold} / {@code run_id}）。整轮 Run 共用一份，故在 Run 内可累计
     * （上一轮快照作为下一轮的 {@code prevSnapshot}）。
     */
    public static final String RUNTIME_INPUTS_KEY = "__runtime_context_inputs__";

    /** {@link #runtimeInputs} 里承载 {@link BaseContext} 本体的键（供需要对象语义的组件取用）。 */
    public static final String BASE_CONTEXT_KEY = "base_context";

    private AgentStateWriteback() {}

    /**
     * 取（或按需创建）本 Run 的运行时上下文输入表（见 {@link #RUNTIME_INPUTS_KEY}）。
     *
     * <p>表内先落四类静态输入：{@code model} / {@code run_id} / {@code summary_threshold} /
     * {@code model_metadata}；消息与上一轮快照由运行期逐步补齐。
     */
    public static synchronized Map<String, Object> runtimeInputs(BaseContext context) {
        if (context == null) {
            return new LinkedHashMap<>();
        }
        Object existing = context.getDynamic(RUNTIME_INPUTS_KEY, null);
        Map<String, Object> inputs;
        if (existing instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) map;
            inputs = casted;
        } else {
            inputs = new LinkedHashMap<>();
            context.setDynamic(RUNTIME_INPUTS_KEY, inputs);
        }
        inputs.put(BASE_CONTEXT_KEY, context);
        putIfAbsent(inputs, "model", context.get("model"));
        putIfAbsent(inputs, "run_id", context.getString("run_id"));
        // 键名与 TokenUsageMiddleware.SUMMARY_THRESHOLD_KEY 一致（该常量包级可见，此处按字面量对齐）。
        putIfAbsent(inputs, "summary_threshold", context.getDynamic("summary_threshold", null));
        putIfAbsent(inputs, "model_metadata", context.get("model_metadata"));
        return inputs;
    }

    private static void putIfAbsent(Map<String, Object> target, String key, Object value) {
        if (value != null && !target.containsKey(key)) {
            target.put(key, value);
        }
    }

    /** 取（或按需创建）本 Run 的缓冲；{@code create=false} 且不存在时返回 {@code null}。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> bufferOf(BaseContext context, boolean create) {
        if (context == null) {
            return null;
        }
        Object existing = context.getDynamic(BUFFER_KEY, null);
        if (existing instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (!create) {
            return null;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        context.setDynamic(BUFFER_KEY, created);
        return created;
    }

    /** 整体替换式写入（{@code token_usage} / {@code todos}）。 */
    public static synchronized void put(BaseContext context, String stateKey, Object value) {
        if (stateKey == null || value == null) {
            return;
        }
        Map<String, Object> buffer = bufferOf(context, true);
        if (buffer == null) {
            return;
        }
        buffer.put(stateKey, value);
    }

    /** 累积 {@code artifacts}（保序去重，与 {@link AgentState#mergeArtifacts} 同语义）。 */
    public static synchronized void addArtifacts(BaseContext context, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        Map<String, Object> buffer = bufferOf(context, true);
        if (buffer == null) {
            return;
        }
        List<String> existing = new ArrayList<>();
        Object current = buffer.get(AgentState.ARTIFACTS);
        if (current instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    existing.add(String.valueOf(item));
                }
            }
        }
        buffer.put(AgentState.ARTIFACTS, AgentState.mergeArtifacts(existing, paths));
    }

    /** 累积 {@code subagent_runs}（按 {@code run_id} 更新同一条，与 {@link ChatBotState#mergeSubagentRuns} 同语义）。 */
    @SuppressWarnings("unchecked")
    public static synchronized void addSubagentRuns(BaseContext context, List<Map<String, Object>> runs) {
        if (runs == null || runs.isEmpty()) {
            return;
        }
        Map<String, Object> buffer = bufferOf(context, true);
        if (buffer == null) {
            return;
        }
        List<Map<String, Object>> existing = new ArrayList<>();
        Object current = buffer.get(ChatBotState.SUBAGENT_RUNS);
        if (current instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    existing.add((Map<String, Object>) map);
                }
            }
        }
        buffer.put(ChatBotState.SUBAGENT_RUNS, ChatBotState.mergeSubagentRuns(existing, runs));
    }

    /** 取出并清空缓冲（由 {@code AgentStateWritebackHook} 调用）。 */
    public static synchronized Map<String, Object> drain(BaseContext context) {
        Map<String, Object> buffer = bufferOf(context, false);
        if (buffer == null || buffer.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> drained = new LinkedHashMap<>(buffer);
        buffer.clear();
        return drained;
    }
}
