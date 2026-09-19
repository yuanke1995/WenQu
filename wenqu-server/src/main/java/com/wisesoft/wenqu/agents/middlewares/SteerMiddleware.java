package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.service.AgentRequestQueueService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * 主会话 Steer 中间件（对应参考实现 {@code agents/middlewares/steer.py}）。
 *
 * <p>在安全生命周期边界结束当前 Run，让队列优先执行 Steer。
 *
 * <p><b>映射关系（本批最干净的一处：语义完全同形）</b>
 * <table border="1">
 *   <tr><th>参考实现</th><th>本工程</th></tr>
 *   <tr><td>{@code AgentMiddleware.abefore_model}</td>
 *       <td>{@link #beforeModel(OverAllState, RunnableConfig)}（{@code ModelHook}）</td></tr>
 *   <tr><td>{@code AgentMiddleware.aafter_model}</td>
 *       <td>{@link #afterModel(OverAllState, RunnableConfig)}</td></tr>
 *   <tr><td>{@code @hook_config(can_jump_to=["end"])}</td>
 *       <td>{@link #canJumpTo()} 返回 {@code List.of(JumpTo.end)}</td></tr>
 *   <tr><td>{@code return {"jump_to": "end"}}</td>
 *       <td>返回 {@code Map.of("jump_to", "end")} —— 框架
 *       {@code ReactAgent} 同样读 {@code state.value("jump_to")} 并
 *       {@code JumpTo.fromStringOrNull}，<b>键名与取值逐字一致</b></td></tr>
 *   <tr><td>{@code should_end_run_for_steer(run_id)}</td>
 *       <td>{@link AgentRequestQueueService#shouldEndRunForSteer(String)}（已搬）</td></tr>
 * </table>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>钩子位置的声明方式</b>：参考实现用装饰器 {@code @hook_config}；本工程用
 *       {@link HookPositions} 注解声明 {@code BEFORE_MODEL} / {@code AFTER_MODEL}
 *       （与 {@link ModelHook} 的默认位置一致，显式写出以免依赖默认值）。</li>
 *   <li><b>异步 → 同步</b>：参考实现是 {@code async def}，本工程为同步方法
 *       （{@code ModelHook} 的方法签名返回 {@code CompletableFuture}，但框架内部同步等待）。
 *       {@code shouldEndRunForSteer} 的数据库查询为阻塞调用，故直接在调用线程执行。</li>
 *   <li><b>{@code _last_message_has_tool_calls} 的消息形态</b>：参考实现兼容 dict 与对象两种
 *       （{@code state.get("messages")} 可能是序列化后的 dict）；本工程的 state 里是框架消息对象，
 *       故直接按 {@link AssistantMessage} 的 {@code getToolCalls()} 判定。两种消息形态的
 *       <b>语义一致</b>：判断最后一条消息是否仍需执行工具，避免跳过工具批次。</li>
 * </ol>
 */
@HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
public class SteerMiddleware extends ModelHook {

    /** 与参考实现 {@code {"jump_to": "end"}} 的键名逐字一致。 */
    static final String JUMP_TO_KEY = "jump_to";

    /** 与参考实现 {@code {"jump_to": "end"}} 的取值逐字一致。 */
    static final String JUMP_TO_END = "end";

    private final AgentRequestQueueService requestQueueService;

    public SteerMiddleware(AgentRequestQueueService requestQueueService) {
        this.requestQueueService = requestQueueService;
    }

    @Override
    public String getName() {
        return "steer";
    }

    @Override
    public List<JumpTo> canJumpTo() {
        // 对应 @hook_config(can_jump_to=["end"])
        return List.of(JumpTo.end);
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(jumpIfSteerRequested(config));
    }

    /**
     * 兜底处理无工具模型轮次，避免 Steer 落在最后一次检查之后。
     *
     * <p>对应参考实现 {@code aafter_model}。
     */
    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        if (lastMessageHasToolCalls(state)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return CompletableFuture.completedFuture(jumpIfSteerRequested(config));
    }

    /**
     * 对应参考实现 {@code _jump_if_steer_requested(runtime)}。
     *
     * <p>run_id 从 runtime context 取（参考实现 {@code getattr(runtime.context, "run_id", None)}）；
     * 未取到或队列未请求结束时不跳转（返回空表，与 {@code return None} 等价）。
     */
    private Map<String, Object> jumpIfSteerRequested(RunnableConfig config) {
        String runId = runIdOf(config);
        if (runId == null || runId.isEmpty() || requestQueueService == null) {
            return Map.of();
        }
        if (!requestQueueService.shouldEndRunForSteer(runId)) {
            return Map.of();
        }
        Map<String, Object> command = new LinkedHashMap<>();
        command.put(JUMP_TO_KEY, JUMP_TO_END);
        return command;
    }

    /** 从 {@link RunnableConfig} 的 metadata 里取运行时上下文中的 run_id。 */
    private static String runIdOf(RunnableConfig config) {
        if (config == null) {
            return null;
        }
        Map<String, Object> metadata = config.metadata().orElse(null);
        Object context = metadata == null ? null : metadata.get(ContextAwareInterceptor.CONTEXT_KEY);
        if (context instanceof BaseContext baseContext) {
            return baseContext.getString("run_id");
        }
        return null;
    }

    /**
     * 判断模型最后一条消息是否仍需执行工具，避免跳过工具批次。
     *
     * <p>对应参考实现 {@code _last_message_has_tool_calls(state)}。
     */
    static boolean lastMessageHasToolCalls(OverAllState state) {
        if (state == null) {
            return false;
        }
        Object raw = state.value("messages").orElse(null);
        if (!(raw instanceof List<?> messages) || messages.isEmpty()) {
            return false;
        }
        Object last = messages.get(messages.size() - 1);
        if (last instanceof AssistantMessage assistant) {
            return assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty();
        }
        // 兼容消息以承载类/Map 形态出现在 state 的情况（与参考实现兼容 dict 的口径一致）
        if (last instanceof com.wisesoft.wenqu.agents.AIMessage carrier) {
            return carrier.hasToolCalls();
        }
        if (last instanceof Map<?, ?> map) {
            Object toolCalls = map.get("tool_calls");
            return toolCalls instanceof List<?> list && !list.isEmpty();
        }
        // 工具响应消息本身不携带新的工具调用
        if (last instanceof ToolResponseMessage) {
            return false;
        }
        if (last instanceof Message message) {
            return false;
        }
        return false;
    }
}
