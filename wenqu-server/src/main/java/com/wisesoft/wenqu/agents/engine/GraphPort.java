package com.wisesoft.wenqu.agents.engine;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.wisesoft.wenqu.agents.BaseAgent;
import com.wisesoft.wenqu.agents.AgentStateWriteback;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.agents.middlewares.ContextAwareInterceptor;
import com.wisesoft.wenqu.agents.GraphCommand;
import com.wisesoft.wenqu.agents.GraphStateSnapshot;
import com.wisesoft.wenqu.repositories.AgentStateRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;

/**
 * 图引擎端口实现：以 <b>Spring AI + spring-ai-alibaba</b> 承接
 * {@link BaseAgent.AgentsGraphPort} 与 {@link AgentStateRepository.StateGraphPort}。
 *
 * <h3>对齐关系（逐条，参考实现 → 本实现）</h3>
 * <table>
 *   <tr><th>参考实现（LangGraph）</th><th>本实现（spring-ai-alibaba graph-core）</th></tr>
 *   <tr><td>{@code graph.astream(inputs, stream_mode="messages")}</td>
 *       <td>{@link CompiledGraph#stream(Map, RunnableConfig)} 过滤出模型节点的
 *           {@code messages} 事件（合成见 {@link AgentEventStream}）</td></tr>
 *   <tr><td>{@code graph.astream_events(version="v3")}</td>
 *       <td>{@link AgentEventStream}（词汇对齐、粒度降级，见该类注释）</td></tr>
 *   <tr><td>{@code graph.ainvoke(...)}</td>
 *       <td>{@link CompiledGraph#invoke(Map, RunnableConfig)} → state values</td></tr>
 *   <tr><td>{@code graph.aget_state(config)}</td>
 *       <td>{@link CompiledGraph#getState(RunnableConfig)} → {@link GraphStateSnapshot}</td></tr>
 *   <tr><td>{@code graph.checkpointer is not None}</td>
 *       <td>{@code compileConfig().checkpointSaver().isPresent()}</td></tr>
 *   <tr><td>{@code graph.aupdate_state(config, values)}</td>
 *       <td>{@link CompiledGraph#updateState(RunnableConfig, Map, String)}（{@code asNode=null}）</td></tr>
 * </table>
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ol>
 *   <li><b>resume（Human-in-the-loop 续跑）</b>：参考实现用 {@code Command(resume=…)} 作为
 *       graph 输入；spring-ai-alibaba 的续跑是
 *       {@code RunnableConfig.withResume()} + {@code agent.updateAgentState(feedback, config)}，
 *       二者配合由 {@code InterruptionHook} 消费。本实现把 {@link GraphCommand} 输入拆成
 *       「先 {@code updateState} 落增量、再 {@code withResume()} 续跑」，
 *       <b>需要 hip（Human-in-the-loop）hook 装配后才完整生效</b>；
 *       未装配时 {@code withResume()} 仅是 config 标记，图按正常路径跑完。</li>
 *   <li><b>递归上限</b>：编译期设置（见 {@link GraphCodec} 类注释）。</li>
 *   <li><b>{@code aupdate_state} 前置条件</b>：spring-ai-alibaba 要求该 thread
 *       <b>已有 checkpoint</b>（否则抛 {@code Missing Checkpoint!}）；参考实现的
 *       {@code aupdate_state} 对空 thread 会新建。本实现对空 thread 显式抛
 *       {@link IllegalStateException}，不做静默新建 —— 避免造出引擎不认的假 checkpoint。</li>
 *   <li><b>checkpointer 恒存在</b>：引擎 {@code CompileConfig} 默认注册 {@code MemorySaver}，
 *       故 {@link #hasCheckpointer()} 恒真，与 langgraph 的 {@code None} 语义不同；
 *       见该方法注释与 {@link #hasPersistentCheckpointer()}。</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * <p>每个实例包一个<b>单次调用</b>的编译图（参考实现同样每次 {@code get_graph} 重新构图），
 * 不跨线程共享可变状态；{@link #ainvoke} / {@link #astreamEvents} 可被同一调用方先后使用。
 */
public final class GraphPort
        implements BaseAgent.AgentsGraphPort, AgentStateRepository.StateGraphPort {

    private static final Logger log = LoggerFactory.getLogger(GraphPort.class);

    /** state 中承载消息的键（与 {@code ReactAgent} 的 {@code AppendStrategy} 约定一致）。 */
    public static final String MESSAGES_KEY = "messages";

    private final CompiledGraph compiledGraph;
    private final String agentName;
    private final boolean persistentCheckpointer;

    private GraphPort(
            CompiledGraph compiledGraph, String agentName, boolean persistentCheckpointer) {
        this.compiledGraph = compiledGraph;
        this.agentName = agentName;
        this.persistentCheckpointer = persistentCheckpointer;
    }

    /** 由 {@link ReactAgent} 构造（内部触发懒编译，与 {@code Agent.getAndCompileGraph()} 同口径）。 */
    public static GraphPort of(ReactAgent agent) {
        return of(agent, false);
    }

    /** 由 {@link ReactAgent} 构造，并声明是否注册了持久化 saver（见 {@link #hasPersistentCheckpointer()}）。 */
    public static GraphPort of(ReactAgent agent, boolean persistentCheckpointer) {
        if (agent == null) {
            throw new IllegalArgumentException("ReactAgent 不能为空");
        }
        return new GraphPort(
                agent.getAndCompileGraph(), agent.name(), persistentCheckpointer);
    }

    /** 由已编译图构造。 */
    public static GraphPort of(CompiledGraph compiledGraph) {
        return of(compiledGraph, false);
    }

    public static GraphPort of(CompiledGraph compiledGraph, boolean persistentCheckpointer) {
        if (compiledGraph == null) {
            throw new IllegalArgumentException("CompiledGraph 不能为空");
        }
        return new GraphPort(compiledGraph, null, persistentCheckpointer);
    }

    /** 底层的 spring-ai-alibaba 编译图（供高级用法直取）。 */
    public CompiledGraph compiledGraph() {
        return compiledGraph;
    }

    // =========================================================================
    // === AgentsGraphPort ===
    // =========================================================================

    /**
     * {@code graph.checkpointer is not None}。
     *
     * <p><b>必要替换（平台差异，显式标注）</b>：spring-ai-alibaba 的
     * {@code CompileConfig} 字段初始化即为
     * {@code new SaverConfig().register(MemorySaver.builder().build())}
     * （{@code CompileConfig.java:45}）—— 也就是**引擎永远带一个默认 checkpointer**，
     * 该判定恒为 true。langgraph 侧则可以为 {@code None}。
     *
     * <p>语义上这是"能否读取 (thread) 的 state"，本引擎恒为"能"，故恒真；
     * 而 {@code AgentStateRepository} 用该判定做的构造前置检查相应地永不触发
     * ——那是守卫而非特性，不影响行为。真正有意义的"是否跨进程持久化"见
     * {@link #hasPersistentCheckpointer()}。
     */
    @Override
    public boolean hasCheckpointer() {
        // compileConfig 是 CompiledGraph 的公开字段（非 accessor）
        return compiledGraph.compileConfig != null
                && compiledGraph.compileConfig.checkpointSaver().isPresent();
    }

    /**
     * 是否注册了<b>持久化</b> saver（如 {@code MysqlSaver}）而非引擎默认的 {@code MemorySaver}。
     *
     * <p>由构图方声明（{@code GraphFactory} 按是否传入 {@code saver} 设置）：
     * 引擎无法区分"默认 MemorySaver"与"显式传入的 MemorySaver"，故必须显式声明。
     */
    public boolean hasPersistentCheckpointer() {
        return persistentCheckpointer;
    }

    @Override
    public void astreamMessages(
            List<Object> messages,
            BaseContext context,
            Map<String, Object> config,
            BiConsumer<Object, Map<String, Object>> sink) {
        RunnableConfig runnableConfig =
                withRuntimeContext(GraphCodec.toRunnableConfig(config), context);
        log.info(
                "[GraphPort] astreamMessages: agent={} thread={} runtimeContextKeys={}",
                agentName,
                runnableConfig.threadId().orElse(null),
                runnableConfig.context().keySet());

        AgentEventStream stream = new AgentEventStream(
                compiledGraph.stream(graphInput(messages), runnableConfig), runnableConfig);
        try {
            stream.forEachEvent(event -> {
                if (!"messages".equals(event.get("method"))) {
                    return;
                }
                Object params = event.get("params");
                if (!(params instanceof Map<?, ?> paramsMap)) {
                    return;
                }
                Object data = paramsMap.get("data");
                if (data instanceof List<?> pair && !pair.isEmpty()) {
                    Map<String, Object> metadata = pair.size() > 1 && pair.get(1) instanceof Map<?, ?> map
                            ? toStringKeyedMap(map)
                            : new LinkedHashMap<>();
                    sink.accept(pair.get(0), metadata);
                }
            });
        } finally {
            stream.close();
        }
    }

    @Override
    public BaseAgent.EventStream astreamEvents(
            Object graphInput,
            BaseContext context,
            Map<String, Object> config,
            String version,
            List<Object> transformers) {
        RunnableConfig runnableConfig =
                withRuntimeContext(GraphCodec.toRunnableConfig(config), context);
        log.debug("astreamEvents: agent={} version={} thread={}",
                agentName, version, runnableConfig.threadId().orElse(null));
        return new AgentEventStream(
                compiledGraph.stream(toGraphInput(graphInput), runnableConfig), runnableConfig);
    }

    @Override
    public Object ainvoke(List<Object> messages, BaseContext context, Map<String, Object> config) {
        RunnableConfig runnableConfig =
                withRuntimeContext(GraphCodec.toRunnableConfig(config), context);
        log.debug("ainvoke: agent={} thread={}", agentName, runnableConfig.threadId().orElse(null));
        Optional<OverAllState> state = compiledGraph.invoke(graphInput(messages), runnableConfig);
        return state.map(GraphCodec::stateToMap).orElseGet(LinkedHashMap::new);
    }

    /**
     * 把本次 Run 的运行时上下文注入 {@link RunnableConfig#context()}。
     *
     * <p><b>平台差异（2026-09-23 接线）</b>：参考实现里 {@code runtime.context} 由 LangGraph
     * 在执行期注入中间件与工具（{@code request.runtime.context}）。本工程的框架会把
     * {@code RunnableConfig.context()} <b>整体</b>作为 {@code ModelRequest.context} 交给拦截器
     * （见 {@code AgentLlmNode} 字节码：{@code RunnableConfig.context()} →
     * {@code ModelRequest.Builder.context(...)}），但调用层此前<b>从未填过它</b>，后果是：
     * <ul>
     *   <li>{@code TokenUsageMiddleware} 取不到上下文 → 用量快照恒为 null（状态面板无 token 数据）；</li>
     *   <li>{@code SteerMiddleware} 的 {@code runIdOf} 取不到 run_id → JumpTo 恒不触发（steer 静默失效）；</li>
     *   <li>{@code AgentStateWritebackHook} 取不到 BaseContext → 状态写回落空。</li>
     * </ul>
     * 故在三个调用入口统一补齐（键与 {@link ContextAwareInterceptor#CONTEXT_KEY} 一致）。
     */
    private static RunnableConfig withRuntimeContext(RunnableConfig config, BaseContext context) {
        if (config != null && context != null) {
            // 值形态按消费者约定：TokenUsageMiddleware 读的是 Map（messages/model/token_usage/…），
            // 表内另以 base_context 键携带 BaseContext 本体供对象语义的组件取用。
            config.context().put(ContextAwareInterceptor.CONTEXT_KEY, AgentStateWriteback.runtimeInputs(context));
        }
        return config;
    }

    @Override
    public GraphStateSnapshot agetState(Map<String, Object> config) {
        RunnableConfig runnableConfig = GraphCodec.toRunnableConfig(config);
        return snapshotOf(runnableConfig);
    }

    // =========================================================================
    // === AgentStateRepository.StateGraphPort ===
    // =========================================================================

    @Override
    public Map<String, Object> getState(String uid, String threadId) {
        return snapshotOf(GraphCodec.toRunnableConfig(uid, threadId)).values();
    }

    @Override
    public void updateState(String uid, String threadId, Map<String, Object> values) {
        RunnableConfig runnableConfig = GraphCodec.toRunnableConfig(uid, threadId);
        if (compiledGraph.getState(runnableConfig) == null) {
            // 见类注释「能力差异 3」：不做静默新建
            throw new IllegalStateException(
                    "Missing Checkpoint: thread " + threadId + " 尚无 checkpoint，无法追加 state");
        }
        try {
            compiledGraph.updateState(runnableConfig, values, null);
        } catch (Exception error) {
            throw new IllegalStateException("updateState 失败: " + error.getMessage(), error);
        }
    }

    // =========================================================================
    // === 内部：快照与输入装配 ===
    // =========================================================================

    /** {@code graph.aget_state(config)} 的等价：{@code StateSnapshot} → {@link GraphStateSnapshot}。 */
    private GraphStateSnapshot snapshotOf(RunnableConfig runnableConfig) {
        StateSnapshot snapshot = compiledGraph.getState(runnableConfig);
        if (snapshot == null) {
            return GraphStateSnapshot.empty();
        }
        Map<String, Object> values = GraphCodec.stateToMap(snapshot.state());
        List<String> next = new ArrayList<>();
        if (snapshot.next() != null) {
            next.add(snapshot.next());
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("configurable", new LinkedHashMap<>(Map.of(
                "thread_id", runnableConfig.threadId().orElse(null) == null
                        ? "" : runnableConfig.threadId().orElse(""))));
        // StateSnapshot 不含 tasks（参考实现的 StateSnapshot.tasks 用于 HITL 中断信息），
        // 中断信息改由 RunnableConfig.isInterrupted 暴露；此处留空并在 extractInterruptInfo 回落到
        // values["__interrupt__"]。见 AgentEventStream 的 hip 装配说明。
        return GraphStateSnapshot.of(values, null, next, config);
    }

    /** {@code {"messages": [...]}} 形态的图输入。 */
    private Map<String, Object> graphInput(List<Object> messages) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(MESSAGES_KEY, GraphCodec.toSpringAiMessages(messages));
        return input;
    }

    /**
     * {@code astreamEvents} 的图输入装配：普通 {@code {"messages": …}} 直通；
     * {@link GraphCommand} 视为增量状态（配合 resume，见类注释「能力差异 1」）。
     */
    private Map<String, Object> toGraphInput(Object graphInput) {
        if (graphInput instanceof GraphCommand command) {
            return new LinkedHashMap<>(command.getUpdate());
        }
        if (graphInput instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (MESSAGES_KEY.equals(key)) {
                    result.put(key, GraphCodec.toSpringAiMessages(asList(entry.getValue())));
                } else {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        if (graphInput instanceof List<?> messages) {
            return graphInput(new ArrayList<>(messages));
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        if (graphInput != null) {
            empty.put(MESSAGES_KEY,
                    GraphCodec.toSpringAiMessages(List.of(String.valueOf(graphInput))));
        }
        return empty;
    }

    private static List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return value == null ? List.of() : List.of(value);
    }

    private static Map<String, Object> toStringKeyedMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /** 供诊断：本次编译图的实际递归上限（等价参考实现的 {@code recursion_limit} 生效值）。 */
    public int effectiveRecursionLimit() {
        return compiledGraph.getMaxIterations();
    }

    /** 供诊断：已声明的 state 键策略（等价 {@code graph.get_key_strategy_map()}）。 */
    public Map<String, ?> keyStrategies() {
        return compiledGraph.getKeyStrategyMap();
    }

    /** 供事件适配层复用：Spring AI 消息列表 → 图输入。 */
    static Map<String, Object> inputOf(List<Message> messages) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(MESSAGES_KEY, messages);
        return input;
    }
}
