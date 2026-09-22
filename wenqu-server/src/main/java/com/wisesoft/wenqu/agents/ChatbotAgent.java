package com.wisesoft.wenqu.agents;

import com.alibaba.cloud.ai.graph.agent.extension.interceptor.PatchToolCallsInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.todolist.TodoListInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.wenqu.agents.engine.GraphFactory;
import com.wisesoft.wenqu.agents.engine.GraphPort;
import com.wisesoft.wenqu.agents.middlewares.ImageInputCompatibilityMiddleware;
import com.wisesoft.wenqu.agents.middlewares.MemoryMiddleware;
import com.wisesoft.wenqu.agents.middlewares.NetworkRetryMiddleware;
import com.wisesoft.wenqu.agents.middlewares.SkillsMiddleware;
import com.wisesoft.wenqu.agents.middlewares.SteerMiddleware;
import com.wisesoft.wenqu.agents.middlewares.SubAgentMiddleware;
import com.wisesoft.wenqu.agents.middlewares.SummaryMiddleware;
import com.wisesoft.wenqu.agents.middlewares.TokenUsageMiddleware;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.AgentRunService;
import com.wisesoft.wenqu.service.AgentRequestQueueService;
import com.wisesoft.wenqu.service.MemoryService;
import com.wisesoft.wenqu.service.SubagentRunService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 内置主对话智能体（对应参考实现 {@code agents/buildin/chatbot/graph.py} 的
 * {@code ChatbotAgent}）。
 *
 * <p>逐字对齐的类属性：{@code name} / {@code description} / {@code capabilities}
 * / {@code context_schema}。
 *
 * <h3>构图流水线（{@code get_graph} 逐行对位）</h3>
 * <ol>
 *   <li>{@code if not context._runtime_prepared: raise} → {@link AgentGraphSupport#isRuntimePrepared}</li>
 *   <li>{@code await sync_agent_context_skills(context)} →
 *       {@link AgentCompositeBackend#syncAgentContextSkills}</li>
 *   <li>{@code backend = create_agent_composite_backend(context)} →
 *       {@link AgentCompositeBackend#createAgentCompositeBackend}</li>
 *   <li>{@code model=load_chat_model(…)} → {@link AgentGraphSupport#loadModel}</li>
 *   <li>{@code tools=await resolve_configured_runtime_tools(context)} →
 *       {@link ToolkitsService#resolveConfiguredRuntimeTools} →
 *       {@link ToolRuntimeBinder#bindRuntimeTools}（按本次 Run 绑定 {@code ToolRuntime}）→
 *       {@link AgentGraphSupport#toToolCallbacks}</li>
 *   <li>{@code system_prompt=build_prompt_with_context(context)} →
 *       {@link ChatbotPrompt#buildPromptWithContext}</li>
 *   <li>{@code middleware=await _build_middlewares(context, backend)} → {@link #buildMiddlewares}</li>
 *   <li>{@code checkpointer=await self._get_checkpointer()} →
 *       {@link BaseAgent#getCheckpointer()}（经 {@link GraphFactory.Builder#saver}）</li>
 * </ol>
 *
 * <h3>必要替换（平台差异，显式标注）</h3>
 * <ol>
 *   <li><b>{@code middleware} 一列拆成 hooks + interceptors</b>：LangChain 的
 *       {@code AgentMiddleware} 在 Java 侧按「生命周期位置」（{@link Hook}）与
 *       「调用包裹」（{@link Interceptor}）分列，见 {@link GraphFactory} 类注释。
 *       本类保持参考实现的<b>先后顺序</b>：hook 面只有 {@code SteerMiddleware}，
 *       其余按原序进 interceptors —— 参考实现的列表顺序即包裹顺序，此处不重排。</li>
 *   <li><b>{@code SkillsMiddleware} 需注册两次</b>：参考实现的同一个中间件同时负责
 *       模型面提示词注入与工具面激活；本工程的 {@code SkillsMiddleware} 是
 *       {@code ModelInterceptor}，工具面经 {@code asToolInterceptor()} 暴露，
 *       故两者都注册（见 {@code SkillsMiddleware} 类注释）。</li>
 *   <li><b>审批中间件未注册</b>：参考实现 {@code create_tool_approval_middleware}
 *       会应非 {@code always_trust} 模式注册 {@code HumanInTheLoopMiddleware}；
 *       本工程无该中间件（{@link ToolApproval} 类注释已标注），故此处只保留
 *       {@link ToolApproval#createToolApprovalInterruptOn} 的返回值形状，
 *       <b>不</b>注入任何拦截器 —— 即「审批在 Java 侧尚未接线」，不是静默通过。</li>
 *   <li><b>压缩事件通道未接线</b>：参考实现用 {@code get_stream_writer()} 推送压缩事件；
 *       本工程该端口由 {@link SummaryMiddleware.CompressionEventSink} 抽象，构图处暂传
 *       {@code null}（{@code emit} 对 null 安全），故压缩开始/完成事件当前不外发。</li>
 *   <li><b>{@code state_schema=ChatBotState}</b>：引擎侧 {@code ReactAgent} 固定
 *       {@code messages}+{@code AppendStrategy}，额外状态键（{@code subagent_runs} 等）
 *       随 middlewares 注册 {@code KeyStrategy}，见 {@link GraphFactory} 类注释与
 *       {@link ChatBotState}。此处无对应传参。</li>
 *   <li><b>{@code session_id} 不参与模型选择</b>：见 {@link AgentChatModel} 能力差异 2。</li>
 * </ol>
 */
@Service
public class ChatbotAgent extends BaseAgent {

    /** 参考实现 {@code ChatbotAgent.name}（逐字）。 */
    public static final String AGENT_NAME = "智能助手";

    /** 参考实现 {@code ChatbotAgent.description}（逐字）。 */
    public static final String AGENT_DESCRIPTION = "基础的对话机器人，可以回答问题，可在配置中启用需要的工具。";

    /** 参考实现 {@code ChatbotAgent.capabilities}（逐字、顺序一致）。 */
    public static final List<String> AGENT_CAPABILITIES = List.of("file_upload", "files", "context_compression");

    private final AgentCompositeBackend compositeBackend;
    private final AgentChatModel agentChatModel;
    private final McpService mcpService;
    private final SkillRuntime skillRuntime;
    private final MemoryService memoryService;
    private final AgentRepository agentRepository;
    private final UserRepository userRepository;
    private final SubagentRunService subagentRunService;
    private final AgentRunService agentRunService;
    private final AgentRequestQueueService requestQueueService;
    private final ModelProviderCache modelProviderCache;
    private final ToolRuntimeBinder toolRuntimeBinder;

    public ChatbotAgent(
            AgentCompositeBackend compositeBackend,
            AgentChatModel agentChatModel,
            McpService mcpService,
            SkillRuntime skillRuntime,
            MemoryService memoryService,
            AgentRepository agentRepository,
            UserRepository userRepository,
            SubagentRunService subagentRunService,
            AgentRunService agentRunService,
            AgentRequestQueueService requestQueueService,
            ModelProviderCache modelProviderCache,
            ToolRuntimeBinder toolRuntimeBinder,
            MysqlLanggraphCheckpointerProvider checkpointerProvider) {
        this.compositeBackend = compositeBackend;
        this.agentChatModel = agentChatModel;
        this.mcpService = mcpService;
        this.skillRuntime = skillRuntime;
        this.memoryService = memoryService;
        this.agentRepository = agentRepository;
        this.userRepository = userRepository;
        this.subagentRunService = subagentRunService;
        this.agentRunService = agentRunService;
        this.requestQueueService = requestQueueService;
        this.modelProviderCache = modelProviderCache;
        this.toolRuntimeBinder = toolRuntimeBinder;
        // 进程级持久化 checkpointer（对应蓝本 pg_manager.get_langgraph_checkpointer()）：
        // 不装配的话构图会退回"每次新建的 MemorySaver"，对话记忆与 checkpoint 面全部落空。
        this.checkpointerProvider = checkpointerProvider;

        this.name = AGENT_NAME;
        this.description = AGENT_DESCRIPTION;
        this.capabilities = new ArrayList<>(AGENT_CAPABILITIES);
        this.contextSchema = ChatBotContext.class;
    }

    @Override
    public AgentsGraphPort getGraph(BaseContext context) {
        if (!AgentGraphSupport.isRuntimePrepared(context)) {
            throw new IllegalArgumentException("构图需要已准备的 Context");
        }
        compositeBackend.syncAgentContextSkills(context);

        // DeepAgents 0.7 移除 backend factory：每次 graph 构造创建本 Run 独享的
        // backend，filesystem 与 summary middleware 共用同一实例。
        ProvisionerSandboxBackend backend =
                AgentGraphSupport.requireBackend(compositeBackend.createAgentCompositeBackend(context));

        List<Hook> hooks = new ArrayList<>();
        List<Interceptor> interceptors = new ArrayList<>();
        buildMiddlewares(context, backend, hooks, interceptors);

        // 工具面：按配置解析 → 绑定本次 Run 的 ToolRuntime（参考实现里该绑定由 LangGraph 按调用注入）
        List<Object> tools = toolRuntimeBinder.bindRuntimeTools(
                ToolkitsService.resolveConfiguredRuntimeTools(context, mcpService, skillRuntime), context);

        return GraphFactory.builder()
                .name(name)
                .description(description)
                .model(AgentGraphSupport.loadModel(agentChatModel, context))
                .systemPrompt(ChatbotPrompt.buildPromptWithContext(AgentGraphSupport.promptContext(context)))
                .tools(AgentGraphSupport.toToolCallbacks(tools))
                .hooks(hooks)
                .interceptors(interceptors)
                .saver((BaseCheckpointSaver) getCheckpointer())
                .recursionLimit(BaseAgent.recursionLimitFromContext(
                        context, BaseContext.DEFAULT_MAX_EXECUTION_STEPS))
                .build();
    }

    /**
     * 构建中间件列表（对应 {@code _build_middlewares(context, backend)}）。
     *
     * <p>顺序与参考实现逐项一致：Steer → filesystem → skills →（memory）→（subagent）→
     * summary → todos → patch tool calls → network retry → image input → token usage →（approval）。
     */
    private void buildMiddlewares(
            BaseContext context,
            ProvisionerSandboxBackend backend,
            List<Hook> hooks,
            List<Interceptor> interceptors) {
        hooks.add(new SteerMiddleware(requestQueueService));

        String artifactsRoot = AgentCompositeBackend.artifactsRoot(context);
        interceptors.add(compositeBackend.createAgentFilesystemMiddleware(
                AgentGraphSupport.toolTokenLimitBeforeEvict(context),
                backend,
                artifactsRoot,
                Set.of()));

        SkillsMiddleware skillsMiddleware = new SkillsMiddleware(skillRuntime, mcpService);
        interceptors.add(skillsMiddleware);
        interceptors.add(skillsMiddleware.asToolInterceptor());

        MemoryMiddleware memoryMiddleware = MemoryMiddleware.create(context, memoryService);
        if (memoryMiddleware != null) {
            interceptors.add(memoryMiddleware);
        }

        SubAgentMiddleware subagentMiddleware = SubAgentMiddleware.create(
                context, agentRepository, userRepository, subagentRunService, agentRunService);
        if (subagentMiddleware != null) {
            interceptors.add(subagentMiddleware);
        }

        interceptors.add(SummaryMiddleware.createFromContext(
                context, summaryToolResultBackend(backend), null));

        interceptors.add(TodoListInterceptor.builder()
                .systemPrompt(ChatbotPrompt.TODO_MID_PROMPT)
                .build());
        interceptors.add(PatchToolCallsInterceptor.builder().build());

        // 网络类错误(断网/连接抖动)按预算(默认600s)持续重试，非网络错误按 max_retries
        // 次数重试——两者合并进 NetworkRetryMiddleware，避免拆成两个中间件后因装配顺序
        // 或外层重试网络错误而放大预算。
        interceptors.add(new NetworkRetryMiddleware(intValueOrDefault(context, "model_retry_times", 2)));
        interceptors.add(new ImageInputCompatibilityMiddleware());
        interceptors.add(new TokenUsageMiddleware(modelProviderCache));

        // 参考实现在此处注册审批中间件；本工程无 HumanInTheLoopMiddleware 对位（见类注释必要替换 3），
        // 仅计算一次以保持 normalize 的边界校验（非法 mode 在此抛错，与参考实现同点失败）。
        String approvalMode = ToolApproval.normalizeToolApprovalMode(
                context.getDynamic("tool_approval_mode", ToolApproval.DEFAULT_TOOL_APPROVAL_MODE));
        ToolApproval.createToolApprovalInterruptOn(approvalMode, AgentGraphSupport.promptContext(context).getWorkdirPath());
    }

    /** 工具结果落盘端口：把 {@code backend.write} 的 error 文本原样上报（对应 {@code backend.write} 返回面）。 */
    private static SummaryMiddleware.ToolResultBackend summaryToolResultBackend(ProvisionerSandboxBackend backend) {
        return (path, content) -> backend.write(path, content).error;
    }

    /** {@code getattr(context, key, default)} 的整数读取。 */
    private static int intValueOrDefault(BaseContext context, String key, int defaultValue) {
        Object value = context == null ? null : context.getDynamic(key, null);
        if (value == null && context != null) {
            value = context.get(key);
        }
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
