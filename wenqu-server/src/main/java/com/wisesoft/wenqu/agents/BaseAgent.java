package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.common.ThreadUtils;
import com.wisesoft.wenqu.common.HashUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 基类，供各类 graph 继承。
 *
 * <p>由参考实现的 {@code agents/base.py} 逐段翻译：顶部 4 个模块级纯函数
 * （{@code _json_safe} / {@code _normalize_tool_event_data} /
 * {@code _subagent_route_for_namespace} / {@code _recursion_limit_from_context}）
 * 与 {@code BaseAgent} 的全部方法。
 *
 * <h3>能力差异（显式标注，非遗漏）：图引擎端口</h3>
 * <p>参考实现直接继承/依赖 LangGraph 的 {@code CompiledStateGraph}
 * （{@code astream} / {@code astream_events} / {@code ainvoke} / {@code aget_state}）、
 * {@code langgraph.types.Command}、{@code langgraph.stream.transformers.CustomTransformer}；
 * Java 侧**尚无对应的编译图引擎**（该引擎属于 agents 运行时的引擎层，本工程未照搬）。
 * 因此本类把"图"收敛为 {@link AgentsGraphPort} 端口 —— 与
 * {@link com.wisesoft.wenqu.repositories.AgentStateRepository.StateGraphPort} 同一口径：
 * <ul>
 *   <li>{@code graph.astream(..., stream_mode="messages")} → {@link AgentsGraphPort#astreamMessages}</li>
 *   <li>{@code async with graph.astream_events(...) as run} → {@link AgentsGraphPort#astreamEvents}</li>
 *   <li>{@code graph.ainvoke(...)} → {@link AgentsGraphPort#ainvoke}</li>
 *   <li>{@code graph.aget_state(config)} → {@link AgentsGraphPort#agetState}</li>
 * </ul>
 * 端口实现由 {@code agents/engine/GraphPort} 提供 —— 引擎底座为
 * <b>Spring AI + spring-ai-alibaba</b>（{@code CompiledGraph} / {@code ReactAgent}），
 * 与参考实现的 LangGraph 是同一套图模型概念，本类不感知具体引擎。
 *
 * <h3>语言差异（只翻译语法，不改语义）</h3>
 * <ul>
 *   <li>Python 异步生成器（{@code async def ... yield}）→ 回调式 sink
 *       （{@link BiConsumer} / {@link Consumer}）：调用方线程顺序消费，语义等价。</li>
 *   <li>{@code asyncio.create_task(_collect_subagent_routes(...))} 与主流并发消费 →
 *       一个后台 {@link Thread} 写 {@link ConcurrentHashMap}，主流结束时
 *       {@code cancel()} → 置位并 {@code interrupt}（对应 {@code finally} 块）。</li>
 *   <li>{@code hasattr(value, "model_dump")} → {@link ModelDumpable} 显式接口
 *       （本工程无 pydantic，无 duck typing）。</li>
 *   <li>Python 的 2 元组产出（{@code yield "messages", (msg, metadata)} 与
 *       {@code ("stream_event", payload)} 的元组形态）→ Java 无元组，
 *       messages 分支用 {@code {"message":…, "metadata":…}} 承载、其余分支用原值承载；
 *       下游消费者按同一键名解包。</li>
 *   <li>Python {@code asyncio.CancelledError} → {@link InterruptedException} 静默吞掉
 *       （对应 {@code contextlib.suppress}）。</li>
 * </ul>
 *
 * <p>参考实现 {@code resolve_agent_resource_options} 依赖 toolkits.service / knowledge.runtime /
 * agents.mcp.service / agents.skills.runtime / backends.knowledge_base_backend，尚未照搬；
 * 故 {@link #getInfo} 的资源选项注入收敛为 {@link AgentResourceOptionsResolver} 接缝
 * （未注入时跳过，等价于参考实现 {@code db is None or user is None} 的分支）。
 */
public abstract class BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(BaseAgent.class);

    // =========================================================================
    // === 模块级纯函数（对应 base.py 顶部函数） ===
    // =========================================================================

    /**
     * {@code _json_safe}：把任意值规整为 JSON 可序列化结构。
     *
     * <p>{@code None/str/int/float/bool} 原样返回；dict → 键转 str 递归；list/tuple → 递归；
     * 有 {@code model_dump} → 递归其 dump；其余退化 {@code str(value)}。
     */
    public static Object jsonSafe(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Double
                || value instanceof Float
                || value instanceof Boolean
                || value instanceof Short
                || value instanceof Byte) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), jsonSafe(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object child : list) {
                result.add(jsonSafe(child));
            }
            return result;
        }
        if (value instanceof Object[] array) {
            List<Object> result = new ArrayList<>(array.length);
            for (Object child : array) {
                result.add(jsonSafe(child));
            }
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object child : iterable) {
                result.add(jsonSafe(child));
            }
            return result;
        }
        if (value instanceof ModelDumpable dumpable) {
            return jsonSafe(dumpable.modelDump());
        }
        return String.valueOf(value);
    }

    /**
     * {@code _normalize_tool_event_data}：规整 tools 流事件。
     *
     * <p>{@code write_todos} / {@code task} 等返回 {@code Command} 的工具，其 tool-finished
     * output 是 {@code Command} 对象，{@code _json_safe} 只能退化成 repr 字符串，前端无法
     * 关联结果。这里从 {@code Command.update["messages"]} 取出真正的 {@link ToolMessage}，
     * 使其与普通工具一致。
     */
    public static Map<String, Object> normalizeToolEventData(Object data) {
        if (!(data instanceof Map<?, ?> map) || !"tool-finished".equals(map.get("event"))) {
            return asStringKeyedMap(data);
        }
        Object output = map.get("output");
        if (!(output instanceof GraphCommand command)) {
            return asStringKeyedMap(data);
        }
        Object update = command.getUpdate();
        if (!(update instanceof Map<?, ?> updateMap)) {
            return asStringKeyedMap(data);
        }
        Object messages = updateMap.get("messages");
        if (!(messages instanceof List<?> messageList)) {
            return asStringKeyedMap(data);
        }
        Object toolCallId = map.get("tool_call_id");
        ToolMessage toolMessage = null;
        for (Object candidate : messageList) {
            if (candidate instanceof ToolMessage message
                    && toolCallId != null
                    && toolCallId.equals(message.getToolCallId())) {
                toolMessage = message;
                break;
            }
        }
        if (toolMessage == null) {
            for (Object candidate : messageList) {
                if (candidate instanceof ToolMessage message) {
                    toolMessage = message;
                    break;
                }
            }
        }
        if (toolMessage == null) {
            return asStringKeyedMap(data);
        }
        Map<String, Object> result = asStringKeyedMap(data);
        result.put("output", toolMessage);
        return result;
    }

    /**
     * {@code _subagent_route_for_namespace}：按命名空间前缀匹配子智能体路由。
     *
     * <p>路由按 path 长度降序排列（最长前缀优先），返回首个满足
     * {@code ns[:len(path)] == path} 的路由；无匹配返回 {@code null}。
     */
    public static Map<String, String> subagentRouteForNamespace(
            Map<List<String>, Map<String, String>> routes, List<String> namespace) {
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        List<String> ns = namespace == null ? List.of() : namespace;
        List<Map.Entry<List<String>, Map<String, String>>> ordered = new ArrayList<>(routes.entrySet());
        ordered.sort((left, right) -> Integer.compare(right.getKey().size(), left.getKey().size()));
        for (Map.Entry<List<String>, Map<String, String>> entry : ordered) {
            List<String> path = entry.getKey();
            if (ns.size() < path.size()) {
                continue;
            }
            boolean matched = true;
            for (int index = 0; index < path.size(); index++) {
                if (!java.util.Objects.equals(ns.get(index), path.get(index))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * {@code _recursion_limit_from_context}：从上下文取 recursion_limit。
     *
     * <p>{@code value = getattr(context, "max_execution_steps", default)}；
     * 仅当 value 是 int 且 {@code > 0} 时采用，否则回落 default。
     */
    public static int recursionLimitFromContext(BaseContext context, int defaultValue) {
        Object value = context == null ? null : context.get("max_execution_steps");
        if (value instanceof Integer steps && steps > 0) {
            return steps;
        }
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        return defaultValue;
    }

    // =========================================================================
    // === 图引擎端口（能力差异，显式标注：引擎未照搬，端口无实现） ===
    // =========================================================================

    /**
     * 编译图端口，对应 {@code CompiledStateGraph} 被本类用到的那一面。
     *
     * <p>端口**无实现**：Java 侧编译图引擎属于 agents 运行时引擎层，本工程未照搬。
     */
    public interface AgentsGraphPort {

        /** 图是否配置了 checkpointer（对应 {@code graph.checkpointer is not None}）。 */
        boolean hasCheckpointer();

        /**
         * {@code graph.astream(\{"messages": messages\}, stream_mode="messages", context=..., config=...)}。
         *
         * <p>逐条推送 {@code (msg, metadata)}；调用方线程顺序消费。
         */
        void astreamMessages(
                List<Object> messages,
                BaseContext context,
                Map<String, Object> config,
                BiConsumer<Object, Map<String, Object>> sink);

        /**
         * {@code async with graph.astream_events(..., version="v3", transformers=[CustomTransformer]) as run}。
         *
         * @return 事件流（对应 {@code run}）；调用方负责 {@link EventStream#close()}
         */
        EventStream astreamEvents(
                Object graphInput,
                BaseContext context,
                Map<String, Object> config,
                String version,
                List<Object> transformers);

        /** {@code graph.ainvoke(\{"messages": messages\}, context=..., config=...)}。 */
        Object ainvoke(List<Object> messages, BaseContext context, Map<String, Object> config);

        /**
         * {@code graph.aget_state(config)}：返回当前 checkpoint 快照。
         *
         * <p>返回体是 {@link GraphStateSnapshot} 而非裸 Map：参考实现的消费面是
         * {@code state.values}（{@code chat_service.py:717} 取 {@code values["messages"]}、
         * :857 取 {@code values["__interrupt__"]}）与 {@code state.tasks[].interrupts}
         * （:852 {@code _extract_interrupt_info}）。若在这里退化成 Map，该形状差异会扩散到
         * 每一个消费者，故按参考实现保留快照对象。
         */
        GraphStateSnapshot agetState(Map<String, Object> config);
    }

    /**
     * 事件流，对应 {@code async with graph.astream_events(...) as run} 拿到的 {@code run}。
     *
     * <p>{@code run} 既是异步可迭代对象，也暴露 {@code run.subagents}（异步可迭代）。
     */
    public interface EventStream extends AutoCloseable {

        /** 流已就绪、开始消费前调用（对应 {@code on_prepared()} 钩子）。 */
        default void onPrepared() {
        }

        /** {@code async for event in run}：逐事件消费，事件为 {@code {method, params, seq}} 形状。 */
        void forEachEvent(Consumer<Map<String, Object>> consumer);

        /**
         * {@code run.subagents}：子智能体流。
         *
         * <p>无子智能体时返回空列表（等价于参考实现 {@code getattr(run, "subagents", None) is None}）。
         */
        default List<Map<String, Object>> subagents() {
            return List.of();
        }

        @Override
        void close();
    }

    /**
     * {@code resolve_agent_resource_options} 的注入接缝（对应参考实现按 kind 注入候选资源）。
     *
     * <p>该函数依赖 toolkits / knowledge.runtime / mcp / skills.runtime / kb_backend，
     * 尚未照搬。未注入时 {@link #getInfo} 跳过资源选项注入。
     */
    public interface AgentResourceOptionsResolver {
        Map<String, List<Map<String, Object>>> resolve(java.util.Set<String> resourceFields);
    }

    /** 流调用的可选参数（对应参考实现 {@code stream_*}` 的 {@code callbacks/metadata/tags/on_prepared}）。 */
    public record StreamOptions(
            List<Object> callbacks,
            Map<String, Object> metadata,
            List<String> tags,
            Runnable onPrepared) {

        public static StreamOptions empty() {
            return new StreamOptions(null, null, null, null);
        }
    }

    // =========================================================================
    // === 类属性（对应 base.py 的类属性） ===
    // =========================================================================

    /** {@code name = "base_agent"}。 */
    protected String name = "base_agent";

    /** {@code description = "base_agent"}。 */
    protected String description = "base_agent";

    /** {@code capabilities: list[str] = []}（如 ["file_upload", "web_search"]）。 */
    protected List<String> capabilities = new ArrayList<>();

    /** {@code context_schema: type[BaseContext] = BaseContext}。 */
    protected Class<? extends BaseContext> contextSchema = BaseContext.class;

    /** {@code self.graph = None}（will be covered by get_graph）。 */
    private AgentsGraphPort graph;

    /**
     * 读取后端声明的上下文 schema 类（对应参考实现类属性 {@code context_schema} 的跨包访问）。
     *
     * <p>参考实现里调用点是 {@code agent_backend.context_schema()}——属性本身是类对象，括号是
     * 对类做实例化；Java 侧把「取类」与「实例化」拆开：本方法只负责取类，实例化由调用方
     * {@code getDeclaredConstructor().newInstance()} 完成（语义等价，异常类型按 Java 惯例映射）。
     */
    public Class<? extends BaseContext> resolveContextSchema() {
        return contextSchema;
    }

    /** checkpointer 提供方接缝（对应 {@code pg_manager.get_langgraph_checkpointer()}）。 */
    protected CheckpointerProvider checkpointerProvider;

    public BaseAgent() {
        this.graph = null;
    }

    /** 对应 {@code pg_manager.get_langgraph_checkpointer()}。 */
    protected interface CheckpointerProvider {
        Object getLanggraphCheckpointer();
    }

    // =========================================================================
    // === 标识与元信息 ===
    // =========================================================================

    /** {@code module_name}：智能体类所在模块名（Java 侧取包名末段的下级类名归属）。 */
    public String moduleName() {
        String packageName = getClass().getPackageName();
        int index = packageName.lastIndexOf('.');
        return index < 0 ? packageName : packageName.substring(index + 1);
    }

    /** {@code id}：智能体类名。 */
    public String id() {
        return getClass().getSimpleName();
    }

    /**
     * {@code get_info}：返回智能体元信息 + 可配置项（可含候选资源选项）。
     *
     * <p>metadata 固定在代码中，由各 Agent 的类属性提供；返回体键序与参考实现一致
     * （id / name / description / metadata / configurable_items / capabilities）。
     */
    public Map<String, Object> getInfo(
            boolean includeConfigurableItems,
            String userRole,
            AgentResourceOptionsResolver resourceOptionsResolver) {
        Map<String, Object> metadata = loadMetadata();
        Map<String, Map<String, Object>> configurableItems = new LinkedHashMap<>();

        if (includeConfigurableItems) {
            configurableItems = new LinkedHashMap<>(configurableItems(userRole));
            if (resourceOptionsResolver != null) {
                java.util.Set<String> resourceFields = new LinkedHashSet<>();
                for (Map<String, Object> item : configurableItems.values()) {
                    Object kind = item.get("kind");
                    if (kind != null && RESOURCE_KINDS.contains(String.valueOf(kind))) {
                        resourceFields.add(String.valueOf(kind));
                    }
                }
                Map<String, List<Map<String, Object>>> resourceOptions =
                        resourceOptionsResolver.resolve(resourceFields);
                if (resourceOptions != null) {
                    for (Map<String, Object> item : configurableItems.values()) {
                        Object kind = item.get("kind");
                        if (kind != null && resourceOptions.containsKey(String.valueOf(kind))) {
                            item.put("options", resourceOptions.get(String.valueOf(kind)));
                        }
                    }
                }
            }
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", id());
        info.put("name", name == null ? "Unknown" : name);
        info.put("description", description == null ? "Unknown" : description);
        info.put("metadata", metadata);
        info.put("configurable_items", configurableItems);
        info.put("capabilities", capabilities == null ? List.of() : capabilities);
        return info;
    }

    /** {@code {"tools", "knowledges", "mcps", "skills", "subagents"}}（base.py 内联字面量）。 */
    protected static final java.util.Set<String> RESOURCE_KINDS =
            java.util.Set.of("tools", "knowledges", "mcps", "skills", "subagents");

    /**
     * 对应 {@code self.context_schema.get_configurable_items(user_role=user_role)}。
     *
     * <p>参考实现是 classmethod，按 {@code context_schema} 多态分派；Java 的 {@code static}
     * 方法不可多态，故收敛为本方法 —— 子类 schema（chatbot / subagent）随其 agent 类一起覆写。
     */
    protected Map<String, Map<String, Object>> configurableItems(String userRole) {
        return BaseContext.getConfigurableItems(userRole);
    }

    // =========================================================================
    // === 流式与一次性调用 ===
    // =========================================================================

    /**
     * {@code stream_messages}：以 {@code stream_mode="messages"} 流式产出 {@code (msg, metadata)}。
     *
     * <p>LangGraph 会自动从 checkpointer 恢复 state（configurable 里的 thread_id/uid 决定恢复哪条）。
     */
    public void streamMessages(
            List<Object> messages,
            BaseContext context,
            StreamOptions options,
            BiConsumer<Object, Map<String, Object>> sink) {
        AgentsGraphPort compiled = getGraph(context);
        log.debug("stream_messages: context={}", context);

        Map<String, Object> inputConfig = buildInputConfig(context, options, true);

        compiled.astreamMessages(messages, context, inputConfig, sink);
    }

    /**
     * {@code _stream_input_with_state}：产出
     * {@code ("custom"|"messages"|"values"|"stream_event"|"checkpoint", payload)} 序列。
     */
    protected void streamInputWithState(
            Object graphInput,
            BaseContext context,
            StreamOptions options,
            BiConsumer<String, Object> sink) {
        AgentsGraphPort compiled = getGraph(context);
        log.debug("stream_with_state: context={}", context);

        Map<String, Object> inputConfig = buildInputConfig(context, options, true);
        StreamOptions effective = options == null ? StreamOptions.empty() : options;

        // subagent_routes：主流事件线程写、这里读；对应参考实现的共享 dict。
        Map<List<String>, Map<String, String>> subagentRoutes = new ConcurrentHashMap<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);

        EventStream run = compiled.astreamEvents(
                graphInput, context, inputConfig, "v3", List.of(CUSTOM_TRANSFORMER));
        try {
            if (effective.onPrepared() != null) {
                effective.onPrepared().run();
            }

            // 对应 asyncio.create_task(_collect_subagent_routes(run, thread_id, routes))
            Thread routeCollector = new Thread(
                    () -> collectSubagentRoutes(run, context == null ? null : context.getString("thread_id"),
                            subagentRoutes, cancelled),
                    "subagent-route-collector");
            routeCollector.setDaemon(true);
            routeCollector.start();

            try {
                run.forEachEvent(event -> handleStreamEvent(event, context, subagentRoutes, sink));
            } finally {
                // 对应 finally: route_task.cancel(); with suppress(CancelledError): await route_task
                cancelled.set(true);
                routeCollector.interrupt();
                try {
                    routeCollector.join(2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            // 流已耗尽、checkpoint 写入已完成；收尾消费者共享本次图的持久状态。
            sink.accept("checkpoint", compiled.agetState(inputConfig));
        } finally {
            try {
                run.close();
            } catch (Exception closeError) {
                log.debug("close stream failed: {}", closeError.getMessage());
            }
        }
    }

    /** {@code CustomTransformer}（langgraph.stream.transformers）—— 引擎未照搬，仅承载标识。 */
    public static final String CUSTOM_TRANSFORMER = "langgraph.stream.transformers.CustomTransformer";

    /**
     * 主事件循环体（对应参考实现 {@code async for event in run} 内部的分支）。
     */
    private void handleStreamEvent(
            Map<String, Object> event,
            BaseContext context,
            Map<List<String>, Map<String, String>> subagentRoutes,
            BiConsumer<String, Object> sink) {
        Map<String, Object> params = asStringKeyedMap(event.get("params"));
        List<String> namespace = asStringList(params.get("namespace"));
        Object method = event.get("method");
        Object data = params.get("data");
        Object sequence = event.get("seq");
        Object timestamp = params.get("timestamp");
        Map<String, String> subagentRoute = subagentRouteForNamespace(subagentRoutes, namespace);

        if ("custom".equals(method)) {
            sink.accept("custom", data);
            return;
        }

        if ("messages".equals(method)) {
            Object message = null;
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (data instanceof List<?> pair) {
                if (!pair.isEmpty()) {
                    message = pair.get(0);
                }
                if (pair.size() > 1) {
                    metadata = asStringKeyedMap(pair.get(1));
                }
            }
            String actualThreadId = subagentRoute == null ? null : subagentRoute.get("thread_id");
            if (actualThreadId == null) {
                actualThreadId = ThreadUtils.extractThreadId(metadata, null);
            }
            metadata.put("namespace", namespace);
            Map<String, Object> streamEvent = new LinkedHashMap<>();
            streamEvent.put("method", method);
            streamEvent.put("namespace", namespace);
            streamEvent.put("seq", sequence);
            streamEvent.put("timestamp", timestamp);
            metadata.put("stream_event", streamEvent);
            if (subagentRoute != null) {
                metadata.putAll(subagentRoute);
            }
            if (actualThreadId != null) {
                metadata.put("thread_id", actualThreadId);
            }
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("message", message);
            pair.put("metadata", metadata);
            sink.accept("messages", pair);
            return;
        }

        if ("values".equals(method) && namespace.isEmpty()) {
            sink.accept("values", data);
            return;
        }

        if ("tasks".equals(method) || "tools".equals(method) || "lifecycle".equals(method)) {
            if ("tools".equals(method)) {
                data = normalizeToolEventData(data);
            }
            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("method", method);
            eventPayload.put("namespace", namespace);
            eventPayload.put("seq", sequence);
            eventPayload.put("timestamp", timestamp);
            eventPayload.put("data", jsonSafe(data));
            String actualThreadId = subagentRoute == null ? null : subagentRoute.get("thread_id");
            if (actualThreadId == null) {
                actualThreadId = ThreadUtils.extractThreadId(params, null);
            }
            if (subagentRoute != null) {
                eventPayload.putAll(subagentRoute);
            }
            if (actualThreadId != null) {
                eventPayload.put("thread_id", actualThreadId);
            }
            sink.accept("stream_event", eventPayload);
        }
    }

    /**
     * {@code _collect_subagent_routes}：并发收集子智能体路由（path → 路由信息）。
     *
     * <p>线程版：{@code async for subagent in run.subagents} 的顺序语义由实现方保证。
     */
    private void collectSubagentRoutes(
            EventStream run,
            String parentThreadId,
            Map<List<String>, Map<String, String>> routes,
            AtomicBoolean cancelled) {
        List<Map<String, Object>> subagents;
        try {
            subagents = run.subagents();
        } catch (RuntimeException error) {
            log.debug("collect subagent stream routes failed: {}", error.getMessage());
            return;
        }
        if (subagents == null) {
            return;
        }
        try {
            for (Map<String, Object> subagent : subagents) {
                if (cancelled.get()) {
                    return;
                }
                List<String> path = asStringList(subagent.get("path"));
                Object subagentSlug = subagent.get("name") != null
                        ? subagent.get("name") : subagent.get("graph_name");
                Object cause = subagent.get("cause");
                Object toolCallId;
                if (cause instanceof Map<?, ?> causeMap) {
                    toolCallId = causeMap.get("tool_call_id");
                } else {
                    toolCallId = subagent.get("trigger_call_id");
                }
                String threadId = ThreadUtils.extractThreadId(subagent.get("metadata"), null);
                if (threadId == null) {
                    threadId = ThreadUtils.extractThreadId(subagent.get("state"), null);
                }
                if (threadId == null
                        && subagentSlug instanceof String slug
                        && toolCallId instanceof String callId
                        && !callId.isEmpty()) {
                    threadId = HashUtils.subagentChildThreadId(parentThreadId, slug, callId);
                }
                if (!path.isEmpty()
                        && subagentSlug instanceof String slug
                        && toolCallId instanceof String callId
                        && !callId.isEmpty()
                        && threadId != null) {
                    Map<String, String> route = new LinkedHashMap<>();
                    route.put("thread_id", threadId);
                    route.put("parent_thread_id", parentThreadId);
                    route.put("subagent_slug", slug);
                    route.put("tool_call_id", callId);
                    routes.put(Collections.unmodifiableList(path), route);
                }
            }
        } catch (RuntimeException error) {
            log.debug("collect subagent stream routes failed: {}", error.getMessage());
        }
    }

    /** {@code stream_messages_with_state}。 */
    public void streamMessagesWithState(
            List<Object> messages,
            BaseContext context,
            StreamOptions options,
            BiConsumer<String, Object> sink) {
        Map<String, Object> graphInput = new LinkedHashMap<>();
        graphInput.put("messages", messages);
        streamInputWithState(graphInput, context, options, sink);
    }

    /** {@code stream_resume_with_state}。 */
    public void streamResumeWithState(
            Object resumeInput,
            BaseContext context,
            StreamOptions options,
            BiConsumer<String, Object> sink) {
        streamInputWithState(resumeInput, context, options, sink);
    }

    /** {@code invoke_messages}：一次性调用并返回最终 state。 */
    public Object invokeMessages(
            List<Object> messages,
            BaseContext context,
            StreamOptions options) {
        AgentsGraphPort compiled = getGraph(context);
        log.debug("invoke_messages: context={}", context);

        Map<String, Object> inputConfig = buildInputConfig(context, options, true);
        return compiled.ainvoke(messages, context, inputConfig);
    }

    /**
     * 构造 {@code input_config}（参考实现三处重复的同一段：configurable + recursion_limit
     * + 可选 callbacks/metadata/tags）。
     */
    private Map<String, Object> buildInputConfig(
            BaseContext context, StreamOptions options, boolean withRecursionLimit) {
        Map<String, Object> configurable = new LinkedHashMap<>();
        configurable.put("thread_id", context == null ? null : context.getString("thread_id"));
        configurable.put("uid", context == null ? null : context.getString("uid"));

        Map<String, Object> inputConfig = new LinkedHashMap<>();
        inputConfig.put("configurable", configurable);
        if (withRecursionLimit) {
            inputConfig.put("recursion_limit",
                    recursionLimitFromContext(context, BaseContext.DEFAULT_MAX_EXECUTION_STEPS));
        }

        StreamOptions effective = options == null ? StreamOptions.empty() : options;
        if (effective.callbacks() != null && !effective.callbacks().isEmpty()) {
            inputConfig.put("callbacks", new ArrayList<>(effective.callbacks()));
        }
        if (effective.metadata() != null && !effective.metadata().isEmpty()) {
            inputConfig.put("metadata", new LinkedHashMap<>(effective.metadata()));
        }
        if (effective.tags() != null && !effective.tags().isEmpty()) {
            inputConfig.put("tags", new ArrayList<>(effective.tags()));
        }
        return inputConfig;
    }

    /** {@code reload_graph}：重置 graph 缓存，强制下次调用 {@code get_graph} 时重新构建。 */
    public void reloadGraph() {
        this.graph = null;
        log.info("{} graph 缓存已清空，将在下次调用时重新构建", name);
    }

    /** 当前缓存的编译图（{@code self.graph}）。 */
    protected AgentsGraphPort cachedGraph() {
        return graph;
    }

    /** 缓存编译图（供子类 {@code get_graph} 实现使用，对应 {@code self.graph = ...}）。 */
    protected void cacheGraph(AgentsGraphPort compiled) {
        this.graph = compiled;
    }

    /**
     * {@code get_graph}：获取并编译对话图实例。
     *
     * <p>必须确保在编译时设置 checkpointer，否则将无法获取历史记录
     * （例如 {@code graph = workflow.compile(checkpointer=checkpointer)}）。
     */
    public abstract AgentsGraphPort getGraph(BaseContext context);

    /**
     * {@code _get_checkpointer}：每次构图独享 saver，避免全局 Agent 缓存把不同用户的 I/O 串行化。
     *
     * <p>对应 {@code pg_manager.get_langgraph_checkpointer()}；未注入提供方时返回 {@code null}。
     */
    protected Object getCheckpointer() {
        return checkpointerProvider == null ? null : checkpointerProvider.getLanggraphCheckpointer();
    }

    /** {@code load_metadata}：从 Agent 类属性加载 metadata（非 dict 时告警并回落空表）。 */
    public Map<String, Object> loadMetadata() {
        Map<String, Object> metadata = metadata();
        if (metadata != null) {
            return metadata;
        }
        log.warn("Agent {} metadata is not a dict, fallback to empty metadata", moduleName());
        return new LinkedHashMap<>();
    }

    /**
     * 对应 {@code getattr(self, "metadata", {})}：类属性 metadata。
     *
     * <p>参考实现靠类属性承载（非 dict 时告警）；Java 无同名类属性机制，
     * 故收敛为本钩子，默认无 metadata（子类覆写）。
     */
    protected Map<String, Object> metadata() {
        return new LinkedHashMap<>();
    }

    // =========================================================================
    // === 小工具 ===
    // =========================================================================

    /** {@code dict} 兜底转 string 键 Map（非 Map 输入返回空表，不抛错）。 */
    private static Map<String, Object> asStringKeyedMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    /** {@code list} 兜底转 string 列表（非 List 输入返回空表，不抛错）。 */
    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                result.add(item == null ? null : String.valueOf(item));
            }
        }
        return result;
    }
}
