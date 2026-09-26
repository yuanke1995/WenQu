package com.wisesoft.wenqu.agents;

import com.alibaba.cloud.ai.graph.agent.extension.interceptor.PatchToolCallsInterceptor;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.wenqu.agents.engine.GraphFactory;
import com.wisesoft.wenqu.agents.engine.GraphPort;
import com.wisesoft.wenqu.agents.middlewares.AgentStateWritebackHook;
import com.wisesoft.wenqu.agents.middlewares.ImageInputCompatibilityMiddleware;
import com.wisesoft.wenqu.agents.middlewares.NetworkRetryMiddleware;
import com.wisesoft.wenqu.agents.middlewares.SkillsMiddleware;
import com.wisesoft.wenqu.agents.middlewares.SubAgentMiddleware;
import com.wisesoft.wenqu.agents.middlewares.SummaryMiddleware;
import com.wisesoft.wenqu.agents.middlewares.TokenUsageMiddleware;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.AgentRunService;
import com.wisesoft.wenqu.service.SubagentRunService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 子智能体后端（对应参考实现 {@code agents/buildin/subagent/graph.py} 的
 * {@code SubAgentBackend}）。
 *
 * <p>逐字对齐的类属性：{@code name} / {@code description} / {@code capabilities}
 * / {@code context_schema}。隐藏/拒绝工具集合由 {@link SubAgentToolFilterMiddleware}
 * 承载（同属参考实现该模块的 {@code _SUBAGENT_DISABLED_TOOLS} /
 * {@code _SUBAGENT_DISABLED_TOOLS_DEFAULT_MODE} / {@code _disabled_tools_for} /
 * {@code _filter_disabled_tools}）。
 *
 * <h3>{@code get_info} 逐行对位</h3>
 * <pre>
 * info = await super().get_info(...)
 * tools_item = (info.get("configurable_items") or {}).get("tools")
 * if isinstance(tools_item, dict):
 *     tools_item["options"] = [o for o in tools_item.get("options") or []
 *                              if o.get("key") not in _SUBAGENT_DISABLED_TOOLS]
 * return info
 * </pre>
 * <p>注意：这里过滤用的是<b>基础集合</b>{@link SubAgentToolFilterMiddleware#SUBAGENT_DISABLED_TOOLS}
 * （不含敏感 backend 工具）—— 与参考实现一致：敏感工具仍<b>列出</b>给用户配置，
 * 只是在默认审批模式下运行期隐藏/拒绝。
 *
 * <h3>构图流水线（{@code get_graph} 逐行对位）</h3>
 * <ol>
 *   <li>{@code if not context._runtime_prepared: raise} → {@link AgentGraphSupport#isRuntimePrepared}</li>
 *   <li>{@code await sync_agent_context_skills(context)} → {@link AgentCompositeBackend#syncAgentContextSkills}</li>
 *   <li>{@code tool_approval_mode = normalize_tool_approval_mode(getattr(context, "tool_approval_mode", "default"))}</li>
 *   <li>{@code disabled_tools = _disabled_tools_for(tool_approval_mode)}</li>
 *   <li>{@code backend = create_agent_composite_backend(context)}</li>
 *   <li>{@code tools=_filter_disabled_tools(await resolve_configured_runtime_tools(context), disabled_tools)}
 *       —— 与主智能体的差别：子智能体在图<b>构图期</b>就把禁用工具从工具表里摘掉；
 *       两者都在构图期经 {@link ToolRuntimeBinder#bindRuntimeTools} 绑定本次 Run 的 {@code ToolRuntime}</li>
 *   <li>{@code middleware=await _build_middlewares(context, backend, tool_approval_mode)}</li>
 * </ol>
 *
 * <h3>必要替换（平台差异，显式标注）</h3>
 * <ol>
 *   <li><b>{@code middleware} 拆成 hooks + interceptors</b>：本文件的中间件列表
 *       <b>没有</b> {@code SteerMiddleware} 与 {@code MemoryMiddleware}（与参考实现一致），
 *       故 hooks 为空；{@code NetworkRetryMiddleware} 用无参构造（{@code max_retries} 走默认 2），
 *       也与参考实现一致（主智能体才传 {@code context.model_retry_times}）。</li>
 *   <li><b>{@code _SubAgentToolFilterMiddleware} 需注册两个对象</b>：
 *       模型面（{@link SubAgentToolFilterMiddleware}）与工具面
 *       （{@link SubAgentToolFilterMiddleware#asToolInterceptor()}）——
 *       Java 单继承无法同体，见该类类注释必要替换 1。</li>
 *   <li><b>{@code SkillsMiddleware} 同样注册两次</b>（模型面 + 工具面）。</li>
 *   <li><b>压缩事件通道未接线</b>：同 {@code ChatbotAgent}，{@code eventSink} 传 {@code null}。</li>
 *   <li><b>{@code state_schema=BaseState}</b>：引擎侧固定 {@code messages}+{@code AppendStrategy}，
 *       此处无对应传参（见 {@link GraphFactory} 类注释）。</li>
 * </ol>
 */
@Service
public class SubAgentBackend extends BaseAgent {

    /** 参考实现 {@code SubAgentBackend.name}（逐字）。 */
    public static final String AGENT_NAME = "子智能体";

    /** 参考实现 {@code SubAgentBackend.description}（逐字）。 */
    public static final String AGENT_DESCRIPTION = "用于被主智能体通过 task 工具调用的专用智能体后端。";

    /** 参考实现 {@code SubAgentBackend.capabilities}（逐字、顺序一致）。 */
    public static final List<String> AGENT_CAPABILITIES = List.of("file_upload", "files");

    private final AgentCompositeBackend compositeBackend;
    private final AgentChatModel agentChatModel;
    private final McpService mcpService;
    private final SkillRuntime skillRuntime;
    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final SubagentRunService subagentRunService;
    private final AgentRunService agentRunService;
    private final ModelProviderCache modelProviderCache;
    private final ToolRuntimeBinder toolRuntimeBinder;

    public SubAgentBackend(
            AgentCompositeBackend compositeBackend,
            AgentChatModel agentChatModel,
            McpService mcpService,
            SkillRuntime skillRuntime,
            AgentRepository agentRepository,
            UserRepository userRepository,
            SubagentRunService subagentRunService,
            AgentRunService agentRunService,
            ModelProviderCache modelProviderCache,
            ToolRuntimeBinder toolRuntimeBinder,
            MysqlLanggraphCheckpointerProvider checkpointerProvider) {
        this.compositeBackend = compositeBackend;
        this.agentChatModel = agentChatModel;
        this.mcpService = mcpService;
        this.skillRuntime = skillRuntime;
        this.agentRepository = agentRepository;
        this.userRepository = userRepository;
        this.subagentRunService = subagentRunService;
        this.agentRunService = agentRunService;
        this.modelProviderCache = modelProviderCache;
        this.toolRuntimeBinder = toolRuntimeBinder;
        // 与 ChatbotAgent 同一套进程级 checkpointer：子智能体线程也要能跨轮续跑
        // （子线程 id 是 64 位，落的是 saver 的 thread_name VARCHAR(255)，容得下）。
        this.checkpointerProvider = checkpointerProvider;

        this.name = AGENT_NAME;
        this.description = AGENT_DESCRIPTION;
        this.capabilities = new ArrayList<>(AGENT_CAPABILITIES);
        this.contextSchema = SubAgentContext.class;
    }

    /** 对应 {@code get_info} 中过滤 {@code configurable_items.tools.options} 的那三行。 */
    @Override
    public Map<String, Object> getInfo(
            boolean includeConfigurableItems,
            String userRole,
            AgentResourceOptionsResolver resourceOptionsResolver) {
        Map<String, Object> info =
                super.getInfo(includeConfigurableItems, userRole, resourceOptionsResolver);
        Map<String, Map<String, Object>> configurableItems = configurableItemsOf(info);
        Map<String, Object> toolsItem = configurableItems == null ? null : configurableItems.get("tools");
        if (toolsItem != null) {
            List<Map<String, Object>> filtered = new ArrayList<>();
            Object options = toolsItem.get("options");
            if (options instanceof List<?> list) {
                for (Object option : list) {
                    if (option instanceof Map<?, ?> optionMap
                            && !SubAgentToolFilterMiddleware.SUBAGENT_DISABLED_TOOLS.contains(
                                    optionMap.get("key"))) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> kept = (Map<String, Object>) optionMap;
                        filtered.add(kept);
                    }
                }
            }
            toolsItem.put("options", filtered);
        }
        return info;
    }

    @Override
    public AgentsGraphPort getGraph(BaseContext context) {
        if (!AgentGraphSupport.isRuntimePrepared(context)) {
            throw new IllegalArgumentException("构图需要已准备的 Context");
        }
        compositeBackend.syncAgentContextSkills(context);

        String approvalMode = ToolApproval.normalizeToolApprovalMode(
                context.getDynamic("tool_approval_mode", ToolApproval.DEFAULT_TOOL_APPROVAL_MODE));
        Set<String> disabledTools = SubAgentToolFilterMiddleware.disabledToolsFor(approvalMode);
        ProvisionerSandboxBackend backend =
                AgentGraphSupport.requireBackend(compositeBackend.createAgentCompositeBackend(context));

        List<Hook> hooks = new ArrayList<>();
        List<Interceptor> interceptors = new ArrayList<>();
        buildMiddlewares(context, backend, approvalMode, disabledTools, hooks, interceptors);

        List<Object> runtimeTools = ToolkitsService.resolveConfiguredRuntimeTools(context, mcpService, skillRuntime);
        return GraphFactory.builder()
                .name(name)
                .description(description)
                .model(AgentGraphSupport.loadModel(agentChatModel, context))
                .systemPrompt(ChatbotPrompt.buildPromptWithContext(AgentGraphSupport.promptContext(context)))
                .tools(AgentGraphSupport.toToolCallbacks(
                        SubAgentToolFilterMiddleware.filterDisabledTools(
                                toolRuntimeBinder.bindRuntimeTools(runtimeTools, context, interceptors),
                                disabledTools)))
                .hooks(hooks)
                .interceptors(interceptors)
                .saver((BaseCheckpointSaver) getCheckpointer())
                .recursionLimit(BaseAgent.recursionLimitFromContext(
                        context, BaseContext.DEFAULT_MAX_EXECUTION_STEPS))
                .build();
    }

    /**
     * 构建中间件列表（对应 {@code _build_middlewares(context, backend, tool_approval_mode)}）。
     *
     * <p>顺序与参考实现逐项一致：filesystem（带 disabled_tools）→ skills → summary →
     * todos → patch tool calls → 子智能体工具过滤 → network retry → image input → token usage。
     */
    private void buildMiddlewares(
            BaseContext context,
            ProvisionerSandboxBackend backend,
            String approvalMode,
            Set<String> disabledTools,
            List<Hook> hooks,
            List<Interceptor> interceptors) {
        // 平台差异：token_usage / artifacts / subagent_runs / todos 的写回钩子
        // （参考实现由 Command(update=...) 完成，见 AgentStateWriteback 类注释）。
        hooks.add(new AgentStateWritebackHook());

        String artifactsRoot = AgentCompositeBackend.artifactsRoot(context);
        interceptors.add(compositeBackend.createAgentFilesystemMiddleware(
                AgentGraphSupport.toolTokenLimitBeforeEvict(context),
                backend,
                artifactsRoot,
                disabledTools));

        SkillsMiddleware skillsMiddleware = new SkillsMiddleware(skillRuntime, mcpService);
        interceptors.add(skillsMiddleware);
        interceptors.add(skillsMiddleware.asToolInterceptor());

        interceptors.add(SummaryMiddleware.createFromContext(
                context, summaryToolResultBackend(backend), null));

        interceptors.add(TodoListInterceptor.builder()
                .systemPrompt(ChatbotPrompt.TODO_MID_PROMPT)
                // 平台差异：同 ChatbotAgent，待办经事件回调转交写回缓冲。
                .todoEventHandler(todos -> AgentStateWriteback.put(
                        context, AgentState.AgentStatePayload.TODOS, ChatbotAgent.todoPayloads(todos)))
                .build());
        interceptors.add(PatchToolCallsInterceptor.builder().build());

        // 工具列表隐藏不构成执行边界；显式传入的禁用工具调用也必须拒绝。
        SubAgentToolFilterMiddleware toolFilter = new SubAgentToolFilterMiddleware(approvalMode);
        interceptors.add(toolFilter);
        interceptors.add(toolFilter.asToolInterceptor());

        interceptors.add(new NetworkRetryMiddleware());
        interceptors.add(new ImageInputCompatibilityMiddleware());
        interceptors.add(new TokenUsageMiddleware(modelProviderCache));
    }

    /** 工具结果落盘端口：把 {@code backend.write} 的 error 文本原样上报。 */
    private static SummaryMiddleware.ToolResultBackend summaryToolResultBackend(ProvisionerSandboxBackend backend) {
        return (path, content) -> backend.write(path, content).error;
    }

    /** 安全取 {@code info["configurable_items"]}（非 Map 形状时返回 null，不抛错）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> configurableItemsOf(Map<String, Object> info) {
        Object items = info == null ? null : info.get("configurable_items");
        return items instanceof Map<?, ?> map ? (Map<String, Map<String, Object>>) map : null;
    }
}
