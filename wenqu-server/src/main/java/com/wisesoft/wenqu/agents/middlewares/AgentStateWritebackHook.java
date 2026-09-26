package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.wisesoft.wenqu.agents.AgentState;
import com.wisesoft.wenqu.agents.AgentStateWriteback;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.agents.ChatBotState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * agent state 写回钩子（<b>平台差异专用，参考实现无对应物</b>）。
 *
 * <p>承担参考实现里 {@code Command(update={...})} 的落点：每次模型调用结束后，把
 * {@link AgentStateWriteback} 缓冲里的增量取出并返回，框架将该 Map 合并回
 * {@code OverAllState} —— 于是 {@code token_usage} / {@code artifacts} /
 * {@code subagent_runs} / {@code todos} 才真正进入 checkpoint，状态面板才有数据。
 *
 * <h3>为什么挂在 AFTER_MODEL 而不是 AFTER_AGENT</h3>
 * <p>参考实现 {@code wrap_model_call} 的 Command 是<b>每次模型调用</b>都写回 state
 * （面板因此能实时反映"最近一次调用"）。{@code AFTER_MODEL} 与之同频：
 * ReAct 循环里工具执行后必然还有一次模型调用（工具结果要回给模型），
 * 故工具产出的 {@code artifacts} / {@code subagent_runs} 也会在同一轮内落盘，
 * 不必等到 Run 结束。
 *
 * <h3>合并策略</h3>
 * <p>本钩子<b>自行完成</b> reducers 合并（{@link AgentState#mergeArtifacts} /
 * {@link ChatBotState#mergeSubagentRuns}），并把对应键声明为
 * {@link KeyStrategy#REPLACE}，避免与框架的 APPEND 叠加造成重复。
 */
@HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
public class AgentStateWritebackHook extends ModelHook {

    private static final Logger log = LoggerFactory.getLogger(AgentStateWritebackHook.class);

    public AgentStateWritebackHook() {
        log.info("[StateWriteback] hook 实例已创建（构图时装配）");
    }

    @Override
    public String getName() {
        return "agent_state_writeback";
    }

    /** 声明本钩子负责的 state 键为整体替换（合并已在本类内完成）。 */
    @Override
    public Map<String, KeyStrategy> getKeyStrategys() {
        Map<String, KeyStrategy> strategies = new LinkedHashMap<>();
        strategies.put(AgentState.AgentStatePayload.TOKEN_USAGE, KeyStrategy.REPLACE);
        strategies.put(AgentState.AgentStatePayload.TODOS, KeyStrategy.REPLACE);
        strategies.put(AgentState.ARTIFACTS, KeyStrategy.REPLACE);
        strategies.put(ChatBotState.SUBAGENT_RUNS, KeyStrategy.REPLACE);
        return strategies;
    }

    /**
     * 模型调用前，把 state 侧的输入同步进运行时输入表。
     *
     * <p>为什么需要：{@code TokenUsageMiddleware} 估算上下文占用要读 state 的消息与"上一次快照"
     * （累计基线），而拦截器只能看到 {@code ModelRequest}，拿不到 state —— 参考实现里
     * {@code request.state} 直接可用。此处用钩子的 BEFORE_MODEL 位置把这两项写进
     * {@link AgentStateWriteback#runtimeInputs(BaseContext)}（框架会把它作为
     * {@code ModelRequest.context["context"]} 交给拦截器）。
     */
    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        BaseContext context = contextOf(config);
        if (context == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Map<String, Object> inputs = AgentStateWriteback.runtimeInputs(context);
        log.info("[StateWriteback] beforeModel, inputsKeys={}", inputs.keySet());
        Object messages = stateValue(state, "messages");
        if (messages instanceof List<?> list && !list.isEmpty()) {
            inputs.put("messages", new ArrayList<>(list));
        }
        Object tokenUsage = stateValue(state, AgentState.AgentStatePayload.TOKEN_USAGE);
        if (tokenUsage != null) {
            inputs.put("token_usage", tokenUsage);
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        BaseContext context = contextOf(config);
        if (context == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        Map<String, Object> pending = AgentStateWriteback.drain(context);
        log.info("[StateWriteback] afterModel, pendingKeys={}", pending.keySet());
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        Map<String, Object> updates = new LinkedHashMap<>();

        Object tokenUsage = pending.get(AgentState.AgentStatePayload.TOKEN_USAGE);
        if (tokenUsage != null) {
            updates.put(AgentState.AgentStatePayload.TOKEN_USAGE, tokenUsage);
            // 作为下一轮的累计基线（对应参考实现把 Command 写回 state 后，
            // 下一次 wrap_model_call 从 request.state 读到的就是这份快照）。
            AgentStateWriteback.runtimeInputs(context).put("token_usage", tokenUsage);
        }
        Object todos = pending.get(AgentState.AgentStatePayload.TODOS);
        if (todos != null) {
            updates.put(AgentState.AgentStatePayload.TODOS, todos);
        }

        List<String> newArtifacts = stringList(pending.get(AgentState.ARTIFACTS));
        if (!newArtifacts.isEmpty()) {
            updates.put(
                    AgentState.ARTIFACTS,
                    AgentState.mergeArtifacts(stringList(stateValue(state, AgentState.ARTIFACTS)), newArtifacts));
        }

        List<Map<String, Object>> newRuns = mapList(pending.get(ChatBotState.SUBAGENT_RUNS));
        if (!newRuns.isEmpty()) {
            updates.put(
                    ChatBotState.SUBAGENT_RUNS,
                    ChatBotState.mergeSubagentRuns(
                            mapList(stateValue(state, ChatBotState.SUBAGENT_RUNS)), newRuns));
        }
        log.info("[StateWriteback] 写回 state: keys={}", updates.keySet());
        return CompletableFuture.completedFuture(updates);
    }

    /**
     * 取运行时上下文：优先 {@link RunnableConfig#context()}（框架会把该 Map 整体作为
     * {@code ModelRequest.context} 交给拦截器，由 {@code GraphPort} 在调用前注入），
     * 其次 {@code config.metadata()}（既有 {@code SteerMiddleware} 的读法，保留兼容）。
     */
    private static BaseContext contextOf(RunnableConfig config) {
        if (config == null) {
            return null;
        }
        BaseContext resolved = asBaseContext(config.context().get(ContextAwareInterceptor.CONTEXT_KEY));
        if (resolved != null) {
            return resolved;
        }
        Map<String, Object> metadata = config.metadata().orElse(null);
        return asBaseContext(metadata == null ? null : metadata.get(ContextAwareInterceptor.CONTEXT_KEY));
    }

    /** 兼容两种形态：{@link BaseContext} 本体，或运行时输入表（其 {@code base_context} 键携带本体）。 */
    private static BaseContext asBaseContext(Object value) {
        if (value instanceof BaseContext baseContext) {
            return baseContext;
        }
        if (value instanceof Map<?, ?> map) {
            return map.get(AgentStateWriteback.BASE_CONTEXT_KEY) instanceof BaseContext nested ? nested : null;
        }
        return null;
    }

    private static Object stateValue(OverAllState state, String key) {
        return state == null ? null : state.value(key).orElse(null);
    }

    private static List<String> stringList(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
        }
        return result;
    }
}
