package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.context.RetrievalScopeService;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.service.AgentService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.KnowledgeRetrievalTool;
import com.wenqu.ai.service.RerankService;
import com.wenqu.ai.util.RequestUser;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问渠领域能力 → 框架工具的挂载点。
 * <p>框架没有问渠这套多路召回（向量+关键词+重排+库界+引用编号），所以检索链<b>不重造也不重写</b>：
 * 直接把现有 {@link KnowledgeRetrievalTool} 挂成框架的一等工具，工具本体、检索开关覆盖、降级登记
 * 全部沿用同一份实现。这里只负责按「这一轮」把库界与引用池接上。
 */
@Slf4j
@Component
public class WenquAgentxTools {

    /**
     * API Key 轮次禁用的沙盒/文件/命令工具（框架原生名 + 问渠 run_code）。
     * <p>Key 只开放问答链路：知识检索、引用、历史这些照常；一旦允许文件或命令，
     * 集成方就能借代表账号在服务器容器里读写/执行，超出 Key 的设计能力。
     */
    private static final Set<String> API_KEY_DENIED_TOOLS = Set.of(
            "run_code", "read_file", "write_file", "edit_file", "execute",
            "shell", "shell_execute", "list_files", "grep_files", "glob_files",
            "ls", "deliver_artifact", "ssh_exec", "ssh_list_aliases");

    private final KnowledgeRetrievalTool knowledgeRetrieval;
    private final RetrievalScopeService retrievalScope;
    private final AgentService agentService;
    private final ChatRunRuntime runtime;
    private final ConfigService configService;
    private final RerankService rerankService;
    private final com.wenqu.ai.service.KbRetrievalParamService kbRetrievalParams;
    private final ImageUrlSigner imageUrlSigner;
    private final com.wenqu.ai.service.ToolAssemblyService toolAssembly;
    private final com.wenqu.ai.agent.tool.ToolRegistry toolRegistry;
    private final com.wenqu.ai.service.TodoService todoService;
    /** 清单的存储桥：问渠只出护栏与呈现，实际落盘写进框架 AgentState（见 {@link TodoBridge}） */
    private final com.wenqu.ai.service.TodoBridge todoBridge;
    private final com.wenqu.ai.service.ToolSpillService toolSpillService;
    private final com.wenqu.ai.service.SessionEventService sessionEventService;
    /** 会话级权限档位（工作区会话的「仅可查看 / 工作区内修改 / 完全权限」选择器存在 c_ai_session） */
    private final com.wenqu.ai.service.SessionService sessionService;
    private final com.wenqu.ai.agent.subagent.SubAgentConsultService consultService;
    private final com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branchRuntime;
    private final com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime subCall;
    /** 引用来源的文件名兜底查询：工具首命中的文档不在主检索映射里，靠它按 docId 补查（见 {@link #newPool}）。 */
    private final com.wenqu.ai.service.DocumentMetaCache documentMetaCache;
    private final com.wenqu.ai.service.SourceTypeResolver sourceTypeResolver;
    /** 本机浏览器：目标轮收尾时把 headed 窗口收掉（用户桌面上真实存在的窗口，不能一直挂着）。 */
    private final com.wenqu.ai.service.LocalBrowserTools localBrowserTools;
    /** 工作流工具的发布态指纹（常驻实例键需要，workflowTools 只存 id 不存版本）。 */
    private final com.wenqu.ai.service.workflowtool.WorkflowToolService workflowToolService;
    /**
     * 问答侧的「跑一整轮」入口（分支执行器要它起子轮）。用函数注册而不是注入 {@code ChatService}：
     * 认一个可调用口、不认那个上帝类本身，否则构造环与「框架引擎反过来依赖问渠编排」这两件事同时发生。
     */
    private volatile com.wenqu.ai.agent.subagent.SubAgentBranchRuntime.TurnRunner branchTurnRunner;

    public WenquAgentxTools(KnowledgeRetrievalTool knowledgeRetrieval, RetrievalScopeService retrievalScope,
                            AgentService agentService, ChatRunRuntime runtime, ConfigService configService,
                            RerankService rerankService, com.wenqu.ai.service.KbRetrievalParamService kbRetrievalParams,
                            ImageUrlSigner imageUrlSigner,
                            com.wenqu.ai.service.ToolAssemblyService toolAssembly,
                            com.wenqu.ai.agent.tool.ToolRegistry toolRegistry,
                            com.wenqu.ai.service.TodoService todoService,
                            com.wenqu.ai.service.TodoBridge todoBridge,
                            com.wenqu.ai.service.ToolSpillService toolSpillService,
                            com.wenqu.ai.service.SessionEventService sessionEventService,
                               com.wenqu.ai.service.SessionService sessionService,
                            com.wenqu.ai.agent.subagent.SubAgentConsultService consultService,
                            com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branchRuntime,
                            com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime subCall,
                            com.wenqu.ai.service.DocumentMetaCache documentMetaCache,
                            com.wenqu.ai.service.SourceTypeResolver sourceTypeResolver,
                            com.wenqu.ai.service.LocalBrowserTools localBrowserTools,
                             com.wenqu.ai.service.workflowtool.WorkflowToolService workflowToolService) {
        this.knowledgeRetrieval = knowledgeRetrieval;
        this.retrievalScope = retrievalScope;
        this.agentService = agentService;
        this.runtime = runtime;
        this.configService = configService;
        this.rerankService = rerankService;
        this.kbRetrievalParams = kbRetrievalParams;
        this.imageUrlSigner = imageUrlSigner;
        this.toolAssembly = toolAssembly;
        this.toolRegistry = toolRegistry;
        this.todoService = todoService;
        this.todoBridge = todoBridge;
        this.toolSpillService = toolSpillService;
        this.sessionEventService = sessionEventService;
        this.sessionService = sessionService;
        this.consultService = consultService;
        this.branchRuntime = branchRuntime;
        this.subCall = subCall;
        this.documentMetaCache = documentMetaCache;
        this.sourceTypeResolver = sourceTypeResolver;
        this.localBrowserTools = localBrowserTools;
        this.workflowToolService = workflowToolService;
    }

    /** 收掉用户机器上的浏览器窗口（没有在跑的执行器时是空操作）。 */
    public void closeLocalBrowser(String uid) {
        if (localBrowserTools == null) return;
        localBrowserTools.closeQuietly(uid);
    }

    /** 由 {@code AgentxConfiguration} 在装配期登记：{@code ChatService::chat}（含轮级互斥外壳的那个入口）。 */
    public void bindBranchTurn(com.wenqu.ai.agent.subagent.SubAgentBranchRuntime.TurnRunner runner) {
        this.branchTurnRunner = runner;
    }

    /**
     * 本轮的「落点」四件套：会话号 + 使用者 + 轮号 + 工具大输出外溢的回收队列。
     * <p>为什么单独立一个类型：任务清单要按轮留痕（{@code turnId}）、外溢要挂回收尾那一刻的
     * 工具卡（{@code spills}），这些都是「这一轮」的东西，而框架的工具在弹性线程上跑、
     * 也没有 {@code TurnState} 这样的状态袋，只能按轮显式传进来——藏进单例就是别人的轮串到这一轮。
     * <p>{@code userId} 也必须显式带：{@code RequestUser} 是 ThreadLocal，在框架的弹性线程上读出来
     * 是 SYSTEM，留痕就会记到 SYSTEM 头上。
     */
    public record TurnHooks(String sessionId, String userId, String modelRef,
                            java.util.function.Supplier<String> turnId,
                            java.util.concurrent.ConcurrentLinkedQueue<String> spills,
                            com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger consultLedger,
                            RequestUser.Snapshot identity,
                            Map<String, String> overrides, Map<String, String> userOverrides,
                            RunCodeHolder runCode, StepTrack step, InfraTrack infra,
                            /** 本轮用户原始问题：追问改写要拿它对齐「用户到底在问什么」 */
                            String question,
                            /** 最近 3 轮对话（懒加载：本轮不检索就不读库；null=读取失败） */
                            java.util.function.Supplier<List<Map<String, Object>>> recentHistory) {
    }

    /**
     * 本轮「基建故障上报」的登记处：沙盒/子调用没通（不是命令跑失败）时，工具经
     * {@code CTX_INFRA_ERROR_SINK} 把工具名与故障文本记到这里，引擎在紧接着的那次
     * {@code tool/result} 定性时取走（取走即清）——该工具卡因此落成 error，而不是
     * 框架给出的绿色成功。每轮随 {@link TurnHooks} 新建，轮界即清空。
     */
    public static final class InfraTrack {
        private final java.util.concurrent.ConcurrentHashMap<String, String> notes =
                new java.util.concurrent.ConcurrentHashMap<>();

        public void mark(String toolName, String note) {
            notes.put(toolName, note == null ? "" : note);
        }

        /** 取走该工具的基建故障标记；没有返回 null。 */
        public String take(String toolName) {
            return toolName == null ? null : notes.remove(toolName);
        }
    }

    /**
     * 本轮「当前在做清单第几项」的活状态（-1 = 还没开始/没有清单）。
     * <p>写入口只有 {@code writeTodo}（清单里唯一标成 doing 的那一项就是当前步骤），读它的是工具卡下发
     * （{@code tool_status} 的 {@code stepId}）。有了它，执行详情才能把工具与产物稳定归到某一步，
     * 而不是按调用顺序猜——猜一次就会串步一次。
     * <p>刻意与 {@link RunCodeHolder} 一样是可变对象：清单在工具线程上写，工具卡在流水线线程上读。
     */
    public static final class StepTrack {
        private volatile int index = -1;

        public int index() {
            return index;
        }

        void index(int value) {
            this.index = value;
        }
    }

    /**
     * 本轮的 run_code 子调用运行时句柄。
     * <p>为什么可变而不是塞进 {@link TurnHooks}：可调用清单要等各族工具挂完才知道（装配期最后一件事），
     * 而 hooks 在装配之前就建好了；引擎在 {@code run_code} 起头/收尾时读它，读不到就是本轮没挂这把工具。
     */
    public static final class RunCodeHolder {
        private volatile com.wenqu.ai.agent.runcode.RunCodeTurn turn;

        public com.wenqu.ai.agent.runcode.RunCodeTurn turn() {
            return turn;
        }

        void turn(com.wenqu.ai.agent.runcode.RunCodeTurn value) {
            this.turn = value;
        }
    }

    /**
     * 装本轮落点袋。<b>必须在发起线程调用</b>：身份与本轮检索开关覆盖都是 ThreadLocal，
     * 框架把工具派到自己的弹性线程上，不在这里取快照就拿不到（{@code KnowledgeTools} 同一口径）。
     */
    public TurnHooks newHooks(ChatCommand cmd, String modelRef, java.util.function.Supplier<String> turnId,
                              java.util.concurrent.ConcurrentLinkedQueue<String> spills,
                              com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger ledger) {
        return new TurnHooks(cmd.getSessionId(), cmd.getUserId(), modelRef, turnId, spills, ledger,
                RequestUser.snapshot(), configService.currentOverrides(), configService.currentUserOverrides(),
                new RunCodeHolder(), new StepTrack(), new InfraTrack(),
                cmd.getQuestion(), memoHistory(cmd.getSessionId()));
    }

    /**
     * 最近 3 轮对话的懒加载快照：首次取用才读 MySQL（一轮不检索就不花这次查询），
     * 同轮多次检索复用同一份；{@code getRecentHistory} 返回 null 表示读取失败（与「无历史」区分）。
     */
    private java.util.function.Supplier<List<Map<String, Object>>> memoHistory(String sessionId) {
        return new java.util.function.Supplier<>() {
            private boolean loaded;
            private List<Map<String, Object>> value;

            @Override
            public synchronized List<Map<String, Object>> get() {
                if (!loaded) {
                    loaded = true;
                    value = sessionService.getRecentHistory(sessionId, 3);
                }
                return value;
            }
        };
    }

    /**
     * 在工具线程上复原「这一轮是谁、这一轮显式改过哪些检索参数」，用完复原回去。
     * <p>不复原的后果：{@code AgentService#get} 内部带登录户可读校验，弹性线程上没身份
     * 就返回 null，于是咨询把「权限问题」报成了「查无这位助手（id 不在清单里）」——排查方向直接被带偏。
     * <p>{@code putOverrides} 是合并语义，所以必须先清再装，否则设置页那份覆盖对工具侧静默失效。
     */
    private <T> T asCaller(TurnHooks hooks, java.util.function.Supplier<T> action) {
        AutoCloseable identity = RequestUser.restore(hooks.identity());
        try {
            Map<String, String> prev = configService.currentOverrides();
            Map<String, String> prevUser = configService.currentUserOverrides();
            configService.putOverrides(hooks.overrides());
            configService.putUserOverrides(hooks.userOverrides());
            try {
                return action.get();
            } finally {
                configService.clearOverride();
                configService.clearUserOverrides();
                if (!prev.isEmpty()) configService.putOverrides(prev);
                if (!prevUser.isEmpty()) configService.putUserOverrides(prevUser);
            }
        } finally {
            try {
                identity.close();
            } catch (Exception e) {
                RequestUser.clear();
            }
        }
    }

    /**
     * 框架引擎本轮可用的工具持有对象：知识族走问渠自己的 {@code @Tool}（引用注册器与库界直接给），
     * 联网族经适配器复用现有工具定义——**同一批定义**，不在框架侧重写一遍名字与参数说明
     * （工具名与说明是审批、按名黑名单、权限档位共同的判据，重写必然漂移）。
     * <p>还缺的那一把工具（沙盒的 {@code run_code}）单独用一条降级说出：静默少挂一个能力会被读成
     * 「配了没生效」，而「框架引擎还没有这个能力」是必须让用户看见的事实。
     *
     * @param onDegrade 降级出口（code, message），由引擎发成 warn 事件
     */
    public List<Object> frameworkTools(ChatCommand cmd,
                                       com.wenqu.ai.agent.permission.ExecutionPolicy policy,
                                       AgentxTurnSlot slot,
                                       java.util.function.BiConsumer<String, String> onDegrade,
                                       boolean approvalCanAsk,
                                       boolean sandboxEnabled,
                                       boolean mcpEnabled) {
        var mc = toolAssembly.mountContext(agentOf(cmd), cmd.getUserId(), cmd.isGuestMode(),
                cmd.branchTurn(), cmd.getWfDepth(), onDegrade, policy, cmd.getGuestMcpServers());
        List<Object> holders = new java.util.ArrayList<>(turnTools(cmd, slot));
        // 建实例时才能定下来的工具面：脚本子调用要用同一批回调，逐轮只换轮号/通道/台账。
        // 工具闭包里<b>只允许</b>捕获这个会话级槽位（slot），按轮的数据一律调用时现取——
        // 实例按会话常驻，把 pool/channel/hooks 捕进闭包就是「第二轮起写上一轮的池子」。
        List<org.springframework.ai.tool.ToolCallback> rawCallbacks = new java.util.ArrayList<>();
        java.util.Set<String> mcpToolNames = new java.util.HashSet<>();
        for (com.wenqu.ai.agent.tool.ToolFamilyProvider provider : toolRegistry.providersInFamilyOrder()) {
            var family = provider.family();
            // 聊天档没有沙盒：run_code 这类必须落容器执行的能力整族不挂。
            // 不是执行期拒绝，而是模型压根看不到——与「被禁的能力与不存在不可区分」同一口径。
            if (!sandboxEnabled && family == com.wenqu.ai.agent.tool.ToolFamily.SANDBOX) {
                continue;
            }
            // 聊天档不挂 MCP：MCP 是外部进程/网络连接，一个不可达的服务会把开轮拖到超时；
            // 纯问答不需要它，需要外部工具的会话走工作区档。
            if (!mcpEnabled && family == com.wenqu.ai.agent.tool.ToolFamily.MCP) {
                continue;
            }
            if (family == com.wenqu.ai.agent.tool.ToolFamily.KNOWLEDGE) {
                // 知识族自己走问渠那条 @Tool（框架侧引用注册直接给），但脚本的子调用清单里要有它——
                // 所以只把回调收进本轮原始清单，不再挂进框架 Toolkit。
                // 纯角色智能体（knowledge_disabled=1）连这份原始清单也不给：脚本子调用那条路同样能打到检索，
                // 只闸 Toolkit 而漏了这里，等于「不使用知识库」被 run_code 里的子调用绕过去
                if (!knowledgeDisabledFor(cmd)) {
                    provider.callbacks(mc).forEach(rawCallbacks::add);
                }
                continue;
            }

            // 需要「本轮落点」的两族各自给出：联网的落点是本轮引用池、沙盒是档位与输出通道；
            // 其余族的主链路侧不读 per-call 上下文
            // （框架的 enrichments 只有 KNOWLEDGE/WEBSEARCH/SANDBOX 三条），可直接复用
            if (family == com.wenqu.ai.agent.tool.ToolFamily.WEBSEARCH) {
                mountFrom(provider, mc, holders, cmd, slot, rawCallbacks, mcpToolNames, policy, approvalCanAsk,
                        name -> {
                            var t = slot.require();
                            // 落点之外再带本轮 hooks 快照：服务商/Key 是个人专属配置（ThreadLocal），
                            // 工具入口据此重放（WebSearchTools.CTX_HOOKS），不带就静默回落平台内置
                            java.util.Map<String, Object> ctx = new java.util.HashMap<>(2);
                            ctx.put(com.wenqu.ai.service.websearch.WebSearchTools.CTX_SINK, t.pool().sink());
                            if (t.hooks() != null) {
                                ctx.put(com.wenqu.ai.service.websearch.WebSearchTools.CTX_HOOKS, t.hooks());
                            }
                            return ctx;
                        });
                continue;
            }
            if (family == com.wenqu.ai.agent.tool.ToolFamily.SANDBOX) {
                // 沙盒本轮落点给权限档位、输出增量、基建错误、外溢落盘与 run_code 的子调用回程
                mountFrom(provider, mc, holders, cmd, slot, rawCallbacks, mcpToolNames, policy, approvalCanAsk,
                        name -> sandboxContext(name, slot.require()));
                continue;
            }
            if (family == com.wenqu.ai.agent.tool.ToolFamily.ASK_USER) {
                // 提问卡换成框架原生实现（AskUserTool）：内置那个靠阻塞 future 等答案，挂起信号又会被
                // Spring AI 包掉——框架内只留一套「等人」，不并存。
                // 分支轮没有观众：提问卡只会在临时会话里挂到超时，按既有 viewerRequired 口径不挂。
                if (!cmd.branchTurn()) holders.add(new AskUserTool());
                mountFrom(provider, mc, holders, cmd, slot, rawCallbacks, mcpToolNames, policy, approvalCanAsk,
                        name -> Map.of());
                continue;
            }
            mountFrom(provider, mc, holders, cmd, slot, rawCallbacks, mcpToolNames, policy, approvalCanAsk,
                    name -> Map.of());
        }
        boolean runCodeMounted = rawCallbacks.stream().anyMatch(cb -> cb.getToolDefinition() != null
                && com.wenqu.ai.service.SandboxTools.RUN_CODE_TOOL_NAME.equals(cb.getToolDefinition().name()));
        slot.bindSurface(new AgentxTurnSlot.Surface(List.copyOf(rawCallbacks), java.util.Set.copyOf(mcpToolNames),
                runCodeMounted));
        return holders;
    }

    /**
     * 本轮交给框架的 {@code ToolsConfig}：由问渠业务判据算出「允许集合」，再交框架 {@code ToolFilter}
     * 做最终裁剪（schema/查找/执行一起消失）。
     * <p>业务判据仍只在 {@code ToolRegistry.exposes} 一处：族三态、具体项、档位、游客、分支、按名黑名单
     * 都从那里读；这里不复制 if，只把结果并成 allow 名单。框架自己的平台工具（team/subagent/plan 等）
     * 不在名单里，由框架的 platform 白名单放行。
     */
    public io.agentscope.harness.agent.tools.ToolsConfig toolsConfig(
            ChatCommand cmd, com.wenqu.ai.agent.permission.ExecutionPolicy policy,
            java.util.Collection<String> registeredNames) {
        var mc = toolAssembly.mountContext(agentOf(cmd), cmd.getUserId(), cmd.isGuestMode(),
                cmd.branchTurn(), cmd.getWfDepth(), (code, message) -> {}, policy, cmd.getGuestMcpServers());
        java.util.Set<String> allow = new java.util.LinkedHashSet<>();
        if (registeredNames != null) {
            for (String name : registeredNames) {
                // 有静态描述符的按业务判据过滤；动态族（MCP/HTTP/工作流）没有描述符，
                // provider 已经按自身资源选择筛过，直接保留。
                var d = toolRegistry.find(name);
                if (d.isPresent() && !toolRegistry.exposes(d.get().family(), name, mc)) continue;
                allow.add(name);
            }
        }
        // 框架在 build 期后挂的工具没有问渠描述符：文件/记忆/会话/产物/读图按既有名单纳入。
        allow.addAll(FRAMEWORK_SURFACE_TOOLS);
        // 平台工具（team/subagent/plan/skill_manage 等）与按路径加载技能的工具不在问渠登记处。
        allow.addAll(io.agentscope.harness.agent.tools.HarnessPlatformTools.NAMES);
        allow.add("load_skill_through_path");
        return com.wenqu.ai.agent.tool.ToolConfigFactory.fromPolicyAndAllow(policy, allow);
    }

    /**
     * 交给框架组管理的族（注册名经别名换算后逐把建组）。
     * <p>MCP 刻意不在这里：动态工具没有静态描述符，激活集无从按 {@code exposes} 问它，
     * 由 {@link FrameworkSessions#applyToolSurface} 那侧按档位与黑名单单独算。
     */
    private static final java.util.List<com.wenqu.ai.agent.tool.ToolFamily> GROUPED_FAMILIES =
            java.util.List.of(com.wenqu.ai.agent.tool.ToolFamily.ASK_USER,
                    com.wenqu.ai.agent.tool.ToolFamily.KNOWLEDGE,
                    com.wenqu.ai.agent.tool.ToolFamily.WEBSEARCH,
                    com.wenqu.ai.agent.tool.ToolFamily.ARTIFACT,
                    com.wenqu.ai.agent.tool.ToolFamily.TODO,
                    com.wenqu.ai.agent.tool.ToolFamily.TEAM,
                    // 沙盒族（run_code）：档位裁剪与审批档都已在 exposes 里，进组后
                    // 「只读档不给脚本」才真的做到「模型看不见」，而不是调了被拒
                    com.wenqu.ai.agent.tool.ToolFamily.SANDBOX,
                    // SSH 族（ssh_exec）：同上，档位/白名单一律按组裁，不能让它无组常驻
                    com.wenqu.ai.agent.tool.ToolFamily.SSH,
                    // 文档处理 / 桌面操作 / 历史对话检索 / MCP 资源：同样按组管理，档位裁剪才生效
                    com.wenqu.ai.agent.tool.ToolFamily.OFFICE,
                    com.wenqu.ai.agent.tool.ToolFamily.COMPUTER_USE,
                    com.wenqu.ai.agent.tool.ToolFamily.SESSION_RETRIEVAL,
                    com.wenqu.ai.agent.tool.ToolFamily.MCP_RESOURCE);

    /**
     * 建实例之后才出现在工具表里、又没有问渠描述符的那批框架工具（文件七把、产物交付、记忆与会话日志）。
     * <p>它们也得进同一张组管理面：手册对「只读调研」写的是<b>命令执行与写入不暴露给模型</b>，
     * 留在无组状态就等于「永远看得见、调了才被拒」——白烧一步预算，也与档位语义不符。
     * 记忆那批一起纳入：只读档不该让模型看见「写长期记忆」这出口（{@code memory_save} 申报非只读，
     * 读类三把与 {@code session_*} 申报只读、照常可见）。子智能体轮的「不许写记忆」另有那道闸，
     * 档位与「这一轮有没有人能答卡」是两件事，不互相顶替。
     */
    static final java.util.List<String> FRAMEWORK_SURFACE_TOOLS = java.util.List.of(
            FrameworkToolLabels.FilesystemToolNames.READ_FILE,
            FrameworkToolLabels.FilesystemToolNames.WRITE_FILE,
            FrameworkToolLabels.FilesystemToolNames.EDIT_FILE,
            FrameworkToolLabels.FilesystemToolNames.GREP_FILES,
            FrameworkToolLabels.FilesystemToolNames.GLOB_FILES,
            FrameworkToolLabels.FilesystemToolNames.LIST_FILES,
            FrameworkToolLabels.FilesystemToolNames.EXECUTE,
            "deliver_artifact",
            "memory_save",
            "memory_get",
            "memory_search",
            "session_search",
            "session_list",
            "session_history",
            // 读图工具 read_image：由引擎按「模型支持读图 + 有文件系统」条件挂进 Toolkit，
            // 纳入同一张组管理面后，只读档也按它申报的只读位放行（见 tierAllows）。
            "read_image");

    /** 档位名单覆盖的那几把框架沙盒工具名（系统巡检按它核「有没有漏建组=漏一道闸」）。 */
    public static java.util.List<String> frameworkSurfaceToolNames() {
        return FRAMEWORK_SURFACE_TOOLS;
    }

    /**
     * 给那批框架沙盒工具补建「一把一组」的 EXTERNAL 组（幂等，可在每轮调）。
     *
     * @param managedOut 这条会话的组管理面，本方法把新纳入的名字并进去
     * @return 当前工具表里真实存在、且按档位判的那批名字（调用方据此走档位判据，不走过装配判据）
     */
    static java.util.Set<String> groupFrameworkSurface(io.agentscope.core.tool.Toolkit toolkit,
                                                       java.util.Set<String> managedOut) {
        java.util.Set<String> frameworkManaged = new java.util.LinkedHashSet<>();
        if (toolkit == null) return frameworkManaged;
        for (String name : FRAMEWORK_SURFACE_TOOLS) {
            if (toolkit.getTool(name) == null) continue;   // 沙盒没开、框架没挂：不建空组
            if (toolkit.getToolGroup(name) == null) {
                toolkit.createToolGroup(name, "wenqu:" + name, true,
                        io.agentscope.core.tool.ToolGroupScope.EXTERNAL);
                toolkit.addToolToGroup(name, name);
            }
            frameworkManaged.add(name);
            managedOut.add(name);
        }
        return frameworkManaged;
    }

    /**
     * 沙盒工具在这个档位下该不该出现在模型的工具表里：只读档只放<b>自己申报只读</b>的那几把，
     * 其余档全放；按名黑名单一律优先。
     * <p>判据与 {@code AgentxFrameworkToolGate} 执行期那条同源——都读工具申报的只读标记，
     * 两边各自列名单迟早对不上，而错的那一侧总是静默的那一侧。
     */
    static boolean tierAllows(ExecutionPolicy policy, io.agentscope.core.tool.AgentTool tool) {
        if (tool == null) return false;
        if (policy == null) return false;
        if (policy.denies(tool.getName())) return false;
        if (policy.sandboxMode() != com.wenqu.ai.agent.permission.SandboxMode.READ_ONLY) return true;
        // 远端自己报的只读位不算判据（理由见 AgentxFrameworkToolGate#readOnly）：
        // 一句 readOnlyHint=true 不该同时免掉档位与「执行前确认」。
        if (tool instanceof io.agentscope.core.tool.ToolBase base && base.isMcp()) return false;
        return tool.isReadOnly();
    }

    /**
     * 描述符名 → 框架 Toolkit 里的真实注册名。
     * <p>知识族是唯一不同源的：注册中心登记的是 {@code searchKnowledge}（Spring AI 那条 @Tool 的方法名），
     * 框架侧走 {@link #turnTools} 交出去的那把 @Tool 逐字叫 {@code search_knowledge}。组名必须对上注册名，
     * 对不上就是「建了一条空组、真工具永远无组=永远可见」。
     */
    private static final Map<String, String> GROUPED_NAME_ALIASES =
            Map.of("searchKnowledge", "search_knowledge");

    private static String registeredName(String descriptorName) {
        return GROUPED_NAME_ALIASES.getOrDefault(descriptorName, descriptorName);
    }

    /** 本刀纳入组管理的全部注册名（描述符名经别名换算）。 */
    public java.util.Set<String> groupableNames() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (var family : GROUPED_FAMILIES) {
            for (var d : toolRegistry.ofFamily(family)) out.add(registeredName(d.name()));
        }
        return out;
    }

    /**
     * 按<b>老装配判据</b>算出本轮允许的注册名——这是组激活集的输入。
     * <p>一条判据都不重写：直接问 {@code ToolRegistry#exposes}，也就是装配循环用的那一份（族开关、
     * 智能体三态、游客可见、档位裁剪、按名黑名单、分支轮不收的人在回路工具）。另写一份就会漂移成
     * 「提示词教了、工具表里却没有」。
     */
    public java.util.Set<String> turnGroupedNames(ChatCommand cmd, ExecutionPolicy policy) {
        var mc = toolAssembly.mountContext(agentOf(cmd), cmd.getUserId(), cmd.isGuestMode(),
                cmd.branchTurn(), cmd.getWfDepth(), (code, message) -> { }, policy, cmd.getGuestMcpServers());
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (var family : GROUPED_FAMILIES) {
            for (var d : toolRegistry.ofFamily(family)) {
                if (toolRegistry.exposes(family, d.name(), mc)) out.add(registeredName(d.name()));
            }
        }
        return out;
    }

    /**
     * 注册工具持有对象，并给问渠工具<b>一把建一个 EXTERNAL 组</b>（组名=工具名）。
     * <p>为什么一把一个组而不是一个族一个组：框架的组是整组开关，族粒度表达不了「一族三把里只用一把」
     * （智能体的内置工具白名单）与逐工具黑名单。换成按名 deny 规则更糟：
     * {@code Toolkit#getToolSchemas} 只按激活集过滤，<b>权限规则不过滤 schema</b>，于是那条能力会从
     * 「模型压根看不见」退化成「看得见、调了被拒」，白烧一步工具预算与 token。
     * <p>为什么是 EXTERNAL：{@code reset_equipped_tools} 的 enum 与 {@code getNotes()} 只列 META 组，
     * EXTERNAL 组模型既看不见也装备不回来，这正是问渠「被禁掉的能力要与不存在不可区分」的口径。
     *
     * @return 本轮真正建了组的注册名（每轮激活集只会在这份名单里增删）
     */
    public java.util.Set<String> registerWithGroups(io.agentscope.core.tool.Toolkit toolkit,
                                                    List<Object> holders) {
        java.util.Set<String> before = new java.util.LinkedHashSet<>(toolkit.getToolNames());
        for (Object holder : holders) {
            try {
                toolkit.registerTool(holder);
            } catch (Throwable t) {
                log.warn("[agentx] registerTool 失败 holder={}: {}", holder.getClass().getSimpleName(), t.toString());
            }
        }
        java.util.Set<String> added = new java.util.LinkedHashSet<>(toolkit.getToolNames());
        added.removeAll(before);
        log.debug("[agentx] 本轮注册新增工具 {} 个: {}", added.size(), added);
        java.util.Set<String> groupable = groupableNames();
        java.util.Set<String> grouped = new java.util.LinkedHashSet<>();
        for (String name : added) {
            if (!groupable.contains(name)) continue;
            if (toolkit.getToolGroup(name) == null) {
                toolkit.createToolGroup(name, "wenqu:" + name, true,
                        io.agentscope.core.tool.ToolGroupScope.EXTERNAL);
            }
            toolkit.addToolToGroup(name, name);
            grouped.add(name);
        }
        // 漏闸断言：属于本刀的族却没进组的注册名 = 无组 = 永远出现在模型工具表里，
        // 档位裁剪与按名白名单一概拦不住。宁可当场说错名字，也不要留着静默漏一道闸。
        for (String name : added) {
            if (grouped.contains(name)) continue;
            var family = toolRegistry.familyOf(name);
            if (family != null && GROUPED_FAMILIES.contains(family)) {
                log.error("[agentx] 工具 {} 属于族 {} 却没进组（登记名与注册名不同源？）："
                        + "它将永远出现在模型工具表里，档位与白名单拦不住", name, family.code());
            }
        }
        // 反向核对：名单里有、这轮却没注册进来的名字。族开关关着是正常态，所以只记 info；
        // 但它同时是「别名写错→建了一条空组、真工具无组」唯一的可见线索。
        for (String name : groupable) {
            if (!added.contains(name)) {
                log.info("[agentx] 组管理名单里的 {} 本轮未注册进 Toolkit（族开关关着、游客收窄，或别名与注册名不符）", name);
            }
        }
        return grouped;
    }

    /**
     * 本轮的落点（引用池/通道/轮号/台账/档位）。引擎在 {@code submit} 之前调它并交给
     * {@link AgentxTurnSlot#begin}；工具在每次调用时从槽位现取。
     */
    public AgentxTurnSlot.Turn newTurn(ChatCommand cmd, WenquSourcePool pool,
                                       com.wenqu.ai.engine.RunChannel channel,
                                       ExecutionPolicy policy, TurnHooks hooks) {
        return newTurn(cmd, pool, channel, policy, hooks, false);
    }

    /**
     * 本轮落点（可按实例状态预置污点）。
     * <p>{@code workspaceMemoryTainted} 取自会话槽位：实例装配时注入了 .wenqu/MEMORY.md，
     * 本轮一开场就该按「上下文里已有可编辑资料」处理，不能等模型先读一次文件才收紧。
     */
    public AgentxTurnSlot.Turn newTurn(ChatCommand cmd, WenquSourcePool pool,
                                       com.wenqu.ai.engine.RunChannel channel,
                                       ExecutionPolicy policy, TurnHooks hooks,
                                       boolean workspaceMemoryTainted) {
        var override = cmd.getAgentConfigOverride();
        // 登录父轮默认不限库；本轮显式指定 kbIds 时必须形成围墙，否则 patch 静默无效。
        boolean walledByOverride = override != null && override.knowledge() != null
                && override.knowledge().hasKbIds();
        KnowledgeRetrievalTool.KbScope scope = retrievalScope.resolve(agentOf(cmd), cmd.getUserId(),
                cmd.isGuestMode() || walledByOverride, cmd.getParentScopeKbIds());
        java.util.Set<String> mentionedKbIds = mentionedKbIdsOf(cmd);
        // @ 知识库提及的收窄：向量路的 kbIds 只在「没有围墙域」时收成点名的库——围墙域原样保留，
        // 域外被点名的库不因提及越界；kbFilter 编译进 filter.kbIds 下推，命中块所属库必须落在
        // 点名集合里（关键词路与图扩展同样生效，不再留「召回后再剔」的口子）。
        if (!mentionedKbIds.isEmpty()) {
            // filter 必须原样带过去：这里重建 KbScope 时漏掉它，@ 了任一知识库就把智能体级标签/范围收窄悄悄关掉
            scope = new KnowledgeRetrievalTool.KbScope(
                    scope.kbIds() == null ? mentionedKbIds : scope.kbIds(),
                    mentionedKbIds, scope.filter());
        }
        AgentxTaint taint = new AgentxTaint();
        if (workspaceMemoryTainted) taint.mark("workspace-memory");
        return new AgentxTurnSlot.Turn(hooks.turnId().get(), pool, channel, hooks, policy, scope, taint);
    }

    /** 本轮 @ 提及里 kb 类型的 id 集合（保序去重；无提及返回空集）。 */
    private static java.util.Set<String> mentionedKbIdsOf(ChatCommand cmd) {
        List<com.wenqu.ai.dto.ChatRequest.Mention> ms = cmd.getMentions();
        if (ms == null || ms.isEmpty()) return java.util.Set.of();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (com.wenqu.ai.dto.ChatRequest.Mention m : ms) {
            if (m != null && "kb".equals(m.getType()) && m.getId() != null && !m.getId().isBlank()) {
                out.add(m.getId());
            }
        }
        return out;
    }

    /**
     * 每轮把脚本子调用通道重新装一遍：轮号、通道、台账、档位都是当轮的。
     * <p>工具面（{@code rawCallbacks}）在 build 期已随 {@link AgentxTurnSlot#surface()} 固定；
     * 实例复用时不能沿用建实例那一轮装好的 {@code RunCodeTurn}——那会把脚本进度与产物记到旧轮上。
     */
    public void ensureRunCodeTurn(AgentxTurnSlot slot, ChatCommand cmd) {
        AgentxTurnSlot.Turn t = slot.current();
        AgentxTurnSlot.Surface surface = slot.surface();
        if (t == null || !surface.runCodeMounted()) return;
        var turn = com.wenqu.ai.agent.runcode.RunCodeTurn.ofFramework(cmd.getSessionId(), t.turnId(),
                cmd.getUserId(), new com.wenqu.ai.agent.chat.TurnRunBoard(), new java.util.LinkedHashMap<>(),
                t.channel()::emitter, new AgentxTurnContext(t, cmd, surface));
        t.hooks().runCode().turn(turn);
        // 「会不会问人」认的是问渠那条审批档（auto/ask/off），不是框架的 PermissionMode：
        // 框架 DEFAULT 下模型直调照样弹卡（那是看得见的逐项确认），但脚本里没有那个卡位。
        // 而且这条判据在装配前就被本族把过——approvalMode=ask 时 run_code 根本不挂
        //（见 SandboxToolProvider#runCodeAllowed），所以这里通常是空转的兜底。
        boolean asksHuman = asksHuman(t.policy());
        List<org.springframework.ai.tool.ToolCallback> forScript = new java.util.ArrayList<>();
        for (org.springframework.ai.tool.ToolCallback cb : surface.rawCallbacks()) {
            String name = cb.getToolDefinition() == null ? null : cb.getToolDefinition().name();
            if (name == null) continue;
            if (asksHuman && toolRegistry.requiresApproval(name)) continue;
            forScript.add(cb);
        }
        // 框架原生的只读工具也进脚本名册（批量读文件、跨文件搜索、查记忆与会话日志——
        // 这些正是「一次模型往返换几十次脚本调用」要用的）。判据只用框架自己那份申报：
        // 只读=不改世界、框架侧免审批，扇出几十次也不会挂出一排没人点的确认卡；
        // 写文件/编辑/命令/交付/写长期记忆一把都不给（与「脚本里不给 execute」同一条理由）。
        var scriptToolkit = slot.toolkit();
        if (scriptToolkit != null) {
            io.agentscope.core.agent.RuntimeContext rc = io.agentscope.core.agent.RuntimeContext.builder()
                    .userId(cmd.getUserId() == null || cmd.getUserId().isBlank() ? "guest" : cmd.getUserId())
                    .sessionId(cmd.getSessionId())
                    .build();
            long subCallTimeoutMs = (long) ConfigDefaults.TOOL_RUNCODE_SUB_CALL_TIMEOUT_MS;
            for (String fwName : FRAMEWORK_SURFACE_TOOLS) {
                var fwTool = scriptToolkit.getTool(fwName);
                if (fwTool == null || !fwTool.isReadOnly()) continue;
                // 只读位要问得来路才算：MCP 那批是远端自己报的，不当问渠的判据（同 tierAllows）
                if (fwTool instanceof io.agentscope.core.tool.ToolBase base && base.isMcp()) continue;
                if (t.policy() != null && t.policy().denies(fwName)) continue;
                forScript.add(new com.wenqu.ai.agent.runcode.FrameworkToolCallback(
                        fwTool, () -> rc, subCallTimeoutMs));
            }
        }
        subCall.prepareSubCallPipeline(turn, forScript);
        if (asksHuman) {
            t.channel().emit(com.wenqu.ai.engine.RunEventType.WARN,
                    "脚本可调用的工具已按档位收窄：本轮要求执行前确认，而脚本里没有能答的人"
                            + "（需确认的那几把不进脚本清单；全自主档不受此限）");
        }
    }

    /** 本轮权限档位（装配判据、执行守卫与框架权限系统三处读同一份解析，见
     * {@code PermissionPolicyResolver}）。框架引擎这一侧没有 {@code TurnState}，
     * 所以档位在这里现算——它问的是同一个解析器，不是第二套规则。
     */
    public com.wenqu.ai.agent.permission.ExecutionPolicy policyFor(ChatCommand cmd) {
        String sessionPreset = cmd.getSessionId() == null ? null : sessionService.permissionPresetOf(cmd.getSessionId());
        com.wenqu.ai.agent.permission.ExecutionPolicy policy =
                toolAssembly.resolvePermissionPolicy(agentOf(cmd), sessionPreset,
                        cmd.getInheritedApprovalMode(), cmd.isGuestMode());
        // 团队成员不得越过根会话的能力上限：以根会话当前生效策略为封顶，取更严的沙盒/审批、并集 deny。
        // 这不是“给成员另设一套权限”，而是把「委派不放大权限」这条不变量钉在唯一的策略出口上。
        if (cmd.getMemberSessionId() != null && !cmd.getMemberSessionId().isBlank()) {
            policy = capToTeamRoot(cmd, policy);
        }
        if (!cmd.isApiKey()) return policy;
        // API Key 只开放问答：把文件/命令工具按名禁用。装配侧（allowsTool）与执行闸
        // （AgentxFrameworkToolGate.permits）读的是同一份 deny，框架原生工具也拦得住。
        Set<String> deny = new LinkedHashSet<>(policy.deny());
        deny.addAll(API_KEY_DENIED_TOOLS);
        return new com.wenqu.ai.agent.permission.ExecutionPolicy(
                policy.preset(), policy.sandboxMode(), policy.approvalMode(),
                policy.enforcement(), policy.writableRoot(), Set.copyOf(deny));
    }

    /**
     * 本轮的工具步数上限（0=不限制）：与问答侧同一份解析（{@code ToolAssemblyService#maxToolSteps}），
     * 分支深度也照读 {@code cmd.getSubagentDepth()}——两边走的是同一把预算，不是各数各的。
     */
    /**
     * 「这一轮会不会问人」——判据必须与 {@code ApprovalGate} 一字不差：智能体的审批档位是
     * {@code ask} 才算会问人（{@code auto}/{@code off}/未登记都算不问），档位没解析出来时按最保守的会问人处理。
     * <p>为什么单独收成一处：若只判「这把工具有没有副作用」，auto 档也会每把写操作都弹卡，
     * 与问答侧口径不一致。
     * 卡片没人答的代价不是「多问一句」而是整轮悬到确认超时、正文什么都没有。
     */
    public static boolean asksHuman(com.wenqu.ai.agent.permission.ExecutionPolicy policy) {
        String approval = policy == null ? "ask" : policy.approvalMode();
        return "ask".equalsIgnoreCase(approval == null ? "auto" : approval.trim());
    }

    /**
     * 把成员轮的策略钳到根会话当前生效策略之下：沙盒取更严档、审批取更严档、deny 取并集。
     * 根会话解析不到时原样返回（fail-open 会放大权限，故这里选择“查不到就不额外收紧”，
     * 但根会话本身已受它自己的策略约束，成员默认也继承 cmd.inheritedApprovalMode）。
     */
    private com.wenqu.ai.agent.permission.ExecutionPolicy capToTeamRoot(
            ChatCommand cmd, com.wenqu.ai.agent.permission.ExecutionPolicy memberPolicy) {
        try {
            String root = cmd.getTeamRootSessionId();
            if (root == null || root.isBlank()) {
                var ms = sessionService.sessionById(cmd.getMemberSessionId());
                root = ms == null ? null : ms.getRootSessionId();
            }
            if (root == null || root.isBlank()) return memberPolicy;
            var rootSession = sessionService.sessionById(root);
            if (rootSession == null) return memberPolicy;
            com.wenqu.ai.model.Agent rootAgent = agentService.get(rootSession.getAgentId());
            String rootPreset = sessionService.permissionPresetOf(root);
            var rootPolicy = toolAssembly.resolvePermissionPolicy(rootAgent, rootPreset, null, false);
            if (rootPolicy == null) return memberPolicy;
            return capPolicy(memberPolicy, rootPolicy);
        } catch (RuntimeException e) {
            return memberPolicy;
        }
    }

    /**
     * 纯函数：成员策略 ∩ 根策略（沙盒取更严、审批取更严、deny 并集、writableRoot 取成员自身）。
     * 抽成静态便于单测覆盖，不依赖会话/DB。
     */
    static com.wenqu.ai.agent.permission.ExecutionPolicy capPolicy(
            com.wenqu.ai.agent.permission.ExecutionPolicy memberPolicy,
            com.wenqu.ai.agent.permission.ExecutionPolicy rootPolicy) {
        var mSm = memberPolicy.sandboxMode();
        var rSm = rootPolicy.sandboxMode();
        var sm = mSm.rank() <= rSm.rank() ? mSm : rSm;
        var preset = rSm.rank() < mSm.rank() ? rootPolicy.preset() : memberPolicy.preset();
        String approval = stricterApproval(memberPolicy.approvalMode(), rootPolicy.approvalMode());
        java.util.Set<String> deny = new java.util.LinkedHashSet<>(memberPolicy.deny());
        deny.addAll(rootPolicy.deny());
        var enforcement = (sm == com.wenqu.ai.agent.permission.SandboxMode.WORKSPACE_WRITE && sm.executable())
                ? com.wenqu.ai.agent.permission.SandboxEnforcement.PARTIAL
                : com.wenqu.ai.agent.permission.SandboxEnforcement.FULL;
        return new com.wenqu.ai.agent.permission.ExecutionPolicy(
                preset, sm, approval, enforcement, memberPolicy.writableRoot(),
                java.util.Set.copyOf(deny));
    }

    /** 审批档严格度：off（禁用副作用工具）&gt; ask（逐项确认）&gt; auto（免确认）；null 视为 auto。 */
    private static String stricterApproval(String a, String b) {
        return approvalRank(a) >= approvalRank(b) ? a : b;
    }

    private static int approvalRank(String mode) {
        String m = mode == null ? "auto" : mode.trim().toLowerCase();
        return switch (m) {
            case "off" -> 2;
            case "ask" -> 1;
            default -> 0;
        };
    }

    public int maxToolSteps(ChatCommand cmd) {
        return toolAssembly.maxToolSteps(agentOf(cmd), cmd.getSubagentDepth());
    }

    /** 用户是否已点「停止本轮」：问答侧那套把手是唯一的判据，框架引擎不另立一套停止语义。 */
    public boolean stopRequested(ChatCommand cmd) {
        return runtime.turnStopRequested(cmd.getSessionId());
    }

    /**
     * 工作流工具的发布态指纹：workflowTools 只存 id，发布/回滚/改名都不动 agent.update_time，
     * 必须由 bindingOf 并进实例键，才能让同一会话下一轮重挂工具（见 WorkflowToolService.bindingFingerprint）。
     */
    public String workflowBindingFingerprint(Agent agent) {
        return workflowToolService == null ? "" : workflowToolService.bindingFingerprint(agent);
    }

    /** 本轮的智能体行（无智能体绑定返回 null＝按平台默认档与使用者可见范围走）。 */
    public Agent agentOf(ChatCommand cmd) {
        if (cmd.getAgentId() == null || cmd.getAgentId().isBlank()) return null;
        return agentService.resolveForTurn(cmd.getAgentId(), cmd.getAgentConfigOverride());
    }

    /**
     * 平台默认作答智能体 id（未设返回 null）。
     * <p>与问答侧 {@code ChatService.resolveTurnAgent} 的回落同源：用户在问答页直接说话时
     * 请求里没有 agentId，当轮按默认智能体作答；续跑轮必须落到同一个身份上，
     * 否则同一会话会出现「用户轮 auto、目标轮逐次确认」。
     */
    public String defaultAgentId() {
        Agent a = agentService.defaultAgent();
        return a == null ? null : a.getId();
    }

    /**
     * 本轮是否「不使用知识库」（{@code c_ai_agent.knowledge_disabled=1}，纯角色智能体）。
     * <p>判据与 {@link #agentOf} 同源：无智能体绑定 = false（平台默认档照常检索）。
     * <p><b>为什么必须在挂载点收口</b>：该开关的语义是「整条跳过检索链路」（见 {@code Agent#knowledgeDisabled}
     * 字段注释）。若只把它做成一个没人读的库字段，用户勾了「不使用知识库」却照样看到检索命中与引用角标，
     * 就是「配了没生效」——本仓对这种形状的处置口径是「要么生效，要么让用户看见降级」。
     * <p><b>为什么不必担心误伤 @ 文档</b>：用户手动 @ 的文档走 {@code cmd.getHistoryRefs()} 的消息前置注入，
     * 不经知识检索工具。闸掉知识族不会把字段注释里那条「手动指定优先于配置」一起掐掉。
     */
    public boolean knowledgeDisabledFor(ChatCommand cmd) {
        return blocksKnowledge(agentOf(cmd));
    }

    /**
     * {@link #knowledgeDisabledFor} 的纯判定（智能体行 → 是否整条跳过检索链路）。
     * <p>抽出来是为了让这条口径可被单测钉住：判据只有「恰好为 1」一种，
     * {@code null}（历史行未写）/ 0 / 其它值一律按「使用知识库」——宽松解析会把「字段没写」
     * 读成「用户要求关掉检索」，那是比不生效更坏的方向。
     */
    public static boolean blocksKnowledge(Agent a) {
        return a != null && Integer.valueOf(1).equals(a.getKnowledgeDisabled());
    }

    /** 问答侧那套通道/账本/总线单点（重启续跑要拿它建一条没有观众的通道）。 */
    public ChatRunRuntime runtime() {
        return runtime;
    }

    /** 本轮的引用池（编号唯一真源仍是 {@code TurnSourceRegistry}，本类不另写一份）。 */
    public WenquSourcePool newPool(ChatCommand cmd, SseEmitter emitter) {
        return new WenquSourcePool(runtime, configService, rerankService, kbRetrievalParams, imageUrlSigner,
                cmd.getSessionId(), cmd.getQuestion(), emitter, documentMetaCache, sourceTypeResolver);
    }

    /**
     * 交给框架 Toolkit 的工具持有对象（框架反射收集 {@code @Tool}，schema 由框架生成）。
     * <p>库界口径与主链路一致：只有免登录分享轮才是权限墙，登录轮按使用者可见库收口。
     * <p>身份与配置覆盖<b>必须在发起线程（这里=问答流水线线程）取快照</b>：框架把工具派到自己的
     * 弹性线程上执行，不重放身份就是 SYSTEM → 可见库集合为空 → 检索恒空且一声不响；不重放覆盖
     * 则是设置页那份 {@code retrieval.*} 对工具侧静默失效。
     */
    public List<Object> turnTools(ChatCommand cmd, AgentxTurnSlot slot) {
        // 纯角色智能体（knowledge_disabled=1）：整条不挂知识检索工具。挂上了再指望模型不用，
        // 是「配了没生效」的另一种写法——用户勾了不使用知识库，就不该再看到检索命中与引用角标
        if (knowledgeDisabledFor(cmd)) return List.of();
        // 身份快照、配置覆盖、库界、引用注册器全部在工具调用时从当轮槽位取（见 KnowledgeTools）。
        // 建实例时只捕获会话级的 slot，不捕获那一轮的任何对象。
        return List.of(new KnowledgeTools(knowledgeRetrieval, configService, slot));
    }

    /**
     * 一位可派遣助手的登记：框架的 {@code agent_id}（逐字=问渠智能体 id）、给模型看的那一行说明、
     * 以及每次派遣现造一个子轮实现体的口子。
     */
    public record SubagentTarget(String agentId, String description,
                                 java.util.function.Function<String, io.agentscope.core.agent.Agent> factory) {
    }

    /**
     * 可派遣的助手（派遣改由框架的 {@code agent_spawn} 驱使，问渠不再自己挂一把咨询工具）。
     * <p>四样都按轮现算：台账（同一位问两次不该再花一整条子轮）、分支执行器（子轮要的是
     * 完整问答外壳）、引用登记（子轮查到的资料进<b>父轮</b>编号空间）、可见事件（「本轮咨询了谁」的
     * 徽标不能等整轮 done）。执行时从槽位现取当轮，闭包只捕获会话级的 slot——实例按会话常驻，
     * 把 pool/channel/hooks 捕进去就是「第二轮起写上一轮的池子」。
     * <p>登记时机在建实例那一轮，与清单渲染时机一致（{@code sysPrompt} 里那节也在 build 期拼），
     * 所以改了勾选要等重建才生效。
     */
    public List<SubagentTarget> consultSubagentTargets(ChatCommand cmd, ExecutionPolicy policy,
                                                       AgentxTurnSlot slot) {
        List<Agent> candidates = dispatchAllowed(cmd, policy)
                ? agentService.consultCandidates(cmd.getAgentId() == null ? "" : cmd.getAgentId())
                : List.of();
        List<SubagentTarget> out = new java.util.ArrayList<>(candidates.size());
        for (Agent a : candidates) {
            String name = a.getName() == null ? a.getId() : a.getName();
            out.add(new SubagentTarget(a.getId(), agentService.consultLine(a),
                    ignored -> new WenquConsultSubagent(a.getId(), name, consultRun(a.getId(), cmd, slot))));
        }
        return out;
    }

    /**
     * 派遣的两句纪律（助手清单本体由框架渲染在「## Subagents → Available agent ids」里，问渠不再抄一份）。
     * <p>只在真登记出了助手时写：调用方拿的是 {@link #consultSubagentTargets} 同一份结果，
     * 「提示词教了模型、能力却没挂上」是本仓反复点名的错。
     * <p>框架那段讲的是通用时机（什么时候值得拆一支），这两句讲的是问渠特有的路由纪律：
     * id 逐字取自清单不要猜、通用问题不要派——派错一位的成本是一整条子轮。
     */
    public String dispatchDiscipline() {
        return "\n\n## 派遣助手\n"
                + "遇到超出你手上资料、且明显落在某位助手职责范围内的问题，用 agent_spawn 派它去查"
                + "（agent_id 逐字取自清单里那一行，不要猜；可以派多次，每次一位；"
                + "它会带着自己的知识库查一遍再把结论与资料交回）。"
                + "通用问题（常识、写作、建议）不要派——没有对应的助手，直接自己答。";
    }

    /**
     * @ 助手提及的强制咨询指令（用户在输入框点名「这一轮先问它」的那一位）。
     * <p>派遣被关（游客/分支/审批档/只读沙盒）时返回空串——不教一件做不到的事。
     * 点名的那位存在但在当前智能体的委派清单（{@code subAgentIds} 白名单）之外时，
     * 给模型一句「如实告知未被授权委派」而不是空串：用户点了名却一声不响，是比拒绝更坏的体验。
     * 委派的 agent_delegated 事件这里不发：主路径上被点名的助手是「被咨询」不是「作答」，
     * 咨询真发生时 agent_consulted 照常下发（沿用既有口径）；引导是软约束，模型不派时用户看到的
     * 是它自己的回答，不会有假徽标。
     */
    public String agentMentionDirective(ChatCommand cmd, ExecutionPolicy policy) {
        List<com.wenqu.ai.dto.ChatRequest.Mention> ms = cmd.getMentions();
        if (ms == null || ms.isEmpty()) return "";
        String id = null;
        String name = null;
        for (com.wenqu.ai.dto.ChatRequest.Mention m : ms) {
            if (m != null && "agent".equals(m.getType()) && m.getId() != null && !m.getId().isBlank()) {
                id = m.getId();
                name = m.getName();
                break;   // 多个取第一个，与轮级委派同一口径
            }
        }
        if (id == null || !dispatchAllowed(cmd, policy)) return "";
        final String mentionedId = id;
        String shown = name == null || name.isBlank() ? id : name;
        boolean listed = agentService.consultCandidates(cmd.getAgentId() == null ? "" : cmd.getAgentId())
                .stream().anyMatch(a -> mentionedId.equals(a.getId()));
        if (!listed) {
            // 助手存在但不在当前智能体的委派清单里（subAgentIds 白名单）：用户点了名，不能一声不响
            if (agentService.get(mentionedId) == null) return "";
            return "\n（用户点名要求本轮先咨询助手「" + shown + "」，但当前智能体的委派清单里没有这一位："
                    + "请如实告诉用户它未被授权委派，不要假装咨询过它。）";
        }
        return "\n（用户点名要求本轮先咨询助手「" + shown + "」：请用 agent_spawn 把与本题相关、"
                + "落在它职责内的问题交给它查（agent_id 逐字用「" + id + "」），"
                + "拿到它的结论与资料后再综合作答；它答不了的部分你自己补。）";
    }

    /**
     * 这一位能不能派，以及派出去跑哪条通路。返回的是「给父轮模型的回报文本」，
     * 预算、去重、失败降级、引用编号回填都在 {@code SubAgentConsultService} 里（与问答侧同一个实现）。
     */
    private java.util.function.Function<String, String> consultRun(String childAgentId, ChatCommand cmd,
                                                                   AgentxTurnSlot slot) {
        return question -> {
            AgentxTurnSlot.Turn t = slot.require();
            TurnHooks hooks = t.hooks();
            // 团队模式：会话行上的 team_enabled（不是系统配置）。根派出的成员会话持久、可回消息。
            String parentSession = cmd.getSessionId();
            boolean teamMode = sessionService.isTeamEnabled(parentSession) || cmd.isTeamMode();
            String rootId = cmd.getTeamRootSessionId() != null && !cmd.getTeamRootSessionId().isBlank()
                    ? cmd.getTeamRootSessionId() : parentSession;
            com.wenqu.ai.model.Agent childAgent = agentService.get(childAgentId);
            String memberName = childAgent == null ? null : childAgent.getName();
            // 团队嵌套：孙代成员名带父路径（reviewer/checker），保证全树唯一与可寻址。
            if (memberName != null && cmd.getMemberSessionId() != null
                    && cmd.getMemberName() != null && !cmd.getMemberName().isBlank()) {
                memberName = cmd.getMemberName() + "/" + memberName;
            }
            com.wenqu.ai.agent.subagent.SubAgentConsultService.Ctx ctx =
                    new com.wenqu.ai.agent.subagent.SubAgentConsultService.Ctx(
                            parentSession, t.turnId(), cmd.getUserId(), hooks.modelRef(),
                            cmd.isGuestMode(), cmd.getSubagentDepth(),
                            cmd.getAgentId() == null ? "" : cmd.getAgentId(),
                            // @ 提及强制咨询那一位：框架引擎这一侧没有 mentions 那一段编排，如实留空
                            //（后果只是「模型可以再问一次同一位」，由台账的去重兜住）
                            "",
                            // 「父 ∪ 子」口径的父侧：父轮本轮解析出的库级墙（内置问渠=默认库+手册库；
                            // 普通登录轮=null 无墙，子轮随之不限库）
                            t.scope() == null || t.scope().kbIds() == null
                                    ? null : List.copyOf(t.scope().kbIds()),
                            (consultedId, consultedName) -> t.channel().emit(
                                    com.wenqu.ai.engine.RunEventType.AGENT_CONSULTED,
                                    com.alibaba.fastjson2.JSON.toJSONString(
                                            Map.of("id", consultedId, "name", consultedName))),
                            teamMode, rootId, memberName,
                            // fork 语义只给直接子代：孙代由各自父轮再摘要，控制 token 成本
                            (cmd.getSubagentDepth() == 0
                                    ? sessionService.parentBriefOf(parentSession, 3000) : ""));
            com.wenqu.ai.agent.subagent.BranchRunner runner = branchTurnRunner == null ? null
                    : branchRuntime.branchRunner(branchTurnRunner, cmd.getSessionId(), t.turnId(),
                            cmd.getUserId(), cmd.isGuestMode(), cmd.isApiKey(), t.channel().emitter(),
                            t.policy() == null ? null : t.policy().approvalMode(), cmd.getSource());
            // runner=null 时咨询服务自己回一句「通道未就绪」给模型（可见降级），不 NPE 也不静默。
            // asCaller 罩住整次咨询：子轮是在这条弹性线程上「再起一整轮」的，身份与覆盖得跟着进去
            return asCaller(hooks,
                    () -> consultService.consult(hooks.consultLedger(), ctx, runner,
                            t.pool()::registerConsultedSources, childAgentId, question));
        };
    }

    /**
     * 派遣这一族的装配判据（提示词与挂载共用一份，不各判一遍）：
     * <ul>
     *   <li><b>分支轮不派</b>：被派出去的那一支里再挂派遣就是乘数扇出——一支派三支、每支再各派。
     *       按 {@code !mc.branchTurn()} 判定。</li>
     *   <li><b>游客不派</b>：免登录分享保留的是「这个被发布的智能体自己会答」的语义，让它经派遣读到
     *       私有库与助手，等于把分享链接变成读别人资料的入口。</li>
     *   <li><b>审批 off / 分支+ask 不派</b>：派遣起的是完整一轮问答（模型 token 与它自己的写盘能力都在
     *       里面），与沙盒、MCP 同族待遇；分支里挂上去只会等一张永远不会来的确认卡。
     *       由这套权限档位判定。</li>
     *   <li><b>只读档不派</b>：收窄发生在装配（模型看不见），而不是执行（点了被拒）——框架的档位闸
     *       {@code AgentxFrameworkToolGate} 对未登记的框架工具也按写类 fail-closed 拒一次，两处同一判据。</li>
     *   <li><b>按名黑名单</b>：认框架的派遣工具名 {@code agent_spawn}
     *       （设置页「平台禁用工具名单」与智能体级 deny 都按名生效）。</li>
     * </ul>
     */
    private boolean dispatchAllowed(ChatCommand cmd, ExecutionPolicy policy) {
        boolean teamMember = cmd.isTeamMode()
                && cmd.getMemberSessionId() != null && !cmd.getMemberSessionId().isBlank();
        // 非团队的子轮不许再派（防乘数扇出）；团队成员允许继续派，受深度上限约束（框架 agent_spawn 硬上限 3）。
        if (cmd.isGuestMode()) return false;
        if (cmd.branchTurn() && !teamMember) return false;
        if (teamMember && cmd.getSubagentDepth() >= TEAM_MAX_DEPTH) return false;
        var mc = toolAssembly.mountContext(agentOf(cmd), cmd.getUserId(), cmd.isGuestMode(),
                cmd.branchTurn(), cmd.getWfDepth(), (code, message) -> {
                }, policy, cmd.getGuestMcpServers());
        if (!mc.masterOn() || mc.approvalOff()) return false;
        // 成员的审批档是 ask 时不因此禁止其继续派：团队里有人在看面板，不能按“没人答”处理。
        if (mc.branchTurn() && mc.approvalAsk() && !teamMember) return false;
        var p = mc.permissionPolicy();
        if (p != null && p.sandboxMode() == com.wenqu.ai.agent.permission.SandboxMode.READ_ONLY) return false;
        return !mc.deniesTool(FRAMEWORK_SPAWN_TOOL);
    }

    /** 团队嵌套深度上限：lead(0)→成员(1) 可再派一层（reviewer/checker=2），与框架 MAX_SPAWN_DEPTH=3 对齐且留余量。 */
    private static final int TEAM_MAX_DEPTH = 2;

    /** 框架派遣工具的裸名（问渠不注册它，只按名判黑名单） */
    private static final String FRAMEWORK_SPAWN_TOOL = "agent_spawn";

    private void mountFrom(com.wenqu.ai.agent.tool.ToolFamilyProvider provider,
                           com.wenqu.ai.agent.tool.ToolMountContext mc, List<Object> holders,
                           ChatCommand cmd, AgentxTurnSlot slot,
                           java.util.List<org.springframework.ai.tool.ToolCallback> rawCallbacks,
                           java.util.Set<String> mcpToolNames,
                           com.wenqu.ai.agent.permission.ExecutionPolicy policy, boolean approvalCanAsk,
                           java.util.function.Function<String, Map<String, Object>> enrichment) {
        for (org.springframework.ai.tool.ToolCallback cb : provider.callbacks(mc)) {
            String name = cb.getToolDefinition().name();
            if (AskUserTool.NAME.equals(name)) continue;   // 由框架原生 AskUserTool 顶替
            // SSH 远端命令不进 run_code 的子调用清单：脚本不能绕过审批把远端命令当子调用打出去。
            if (provider.family() != com.wenqu.ai.agent.tool.ToolFamily.SSH) rawCallbacks.add(cb);
            if (provider.family() == com.wenqu.ai.agent.tool.ToolFamily.MCP) mcpToolNames.add(name);
            // 要不要问人沿用问渠的登记（未登记一律要审批，fail-closed）——不在框架侧另判一次「像不像写操作」。
            // 唯一豁免：游客轮里发布者授权名单内的 MCP 工具按「发布者预批」处理——分享页没有审批卡
            // 通道（approvalCanAsk=false），不豁免的话名单挂上了也一把调不动，逐个 deny 白烧步数。
            // 名单外的一切照旧：装配期根本不会挂，这里也拦不到。
            boolean needsApproval = asksHuman(policy)
                    && toolRegistry.requiresApproval(name)
                    && !mc.guestPreapproved(name);
            // 只读标记同样只问登记处那一份等级（读类/纯计算=只读）。未登记的名字按非只读处理，
            // 与上面那条审批口径一致：宁可少给，不给「平台不知道它会干什么」的工具放行。
            boolean readOnly = toolRegistry.find(name)
                    .map(com.wenqu.ai.agent.tool.ToolDescriptor::readOnly).orElse(false);
            // 上下文在<b>每次调用</b>时现建：槽位里是本轮最新的 pool/channel/hooks。
            // 闭包只捕获会话级的 slot 与这个工具名，不捕获任何一轮对象。
            holders.add(new SpringAiToolAdapter(cb, readOnly,
                    () -> withBaseContext(slot.require(), enrichment.apply(name)),
                    () -> policyOf(slot, policy),
                    needsApproval, approvalCanAsk,
                    // MCP、沙盒脚本与 SSH 远端输出都是外部数据：统一加信封并标脏本轮（见适配器字段注释）。
                    // 检索/联网/文档几族的信封在各自工具内，不在这里重复。
                    provider.family() == com.wenqu.ai.agent.tool.ToolFamily.MCP
                            || provider.family() == com.wenqu.ai.agent.tool.ToolFamily.SANDBOX
                            || provider.family() == com.wenqu.ai.agent.tool.ToolFamily.SSH,
                    () -> {
                        AgentxTurnSlot.Turn t = slot.current();
                        return t == null ? null : t.taint();
                    },
                    () -> slot.toolAllowedForSession(name)));
        }
    }

    /**
     * 把「裸 @Tool holder」包成 {@link SpringAiToolAdapter} 再放进 holders。
     * <p>为什么需要：框架的 {@code Toolkit.registerTool(Object)} 只认 {@code ToolBase} 一类，
     * 直接塞一个带 {@code @Tool} 的普通对象进去不会注册任何工具（团队工具因此一直挂不上）。
     * provider 工具是经 {@code mountFrom} 包成 SpringAiToolAdapter 才生效的，这里对团队这种
     * 「按会话 new 出来的 holder」用同一套包装。
     */
    public void mountRawHolders(java.util.List<Object> holders,
                                com.wenqu.ai.agent.permission.ExecutionPolicy policy,
                                boolean approvalCanAsk, AgentxTurnSlot slot, Object... rawHolders) {
        for (Object h : rawHolders) {
            if (h == null) continue;
            for (org.springframework.ai.tool.ToolCallback cb : org.springframework.ai.support.ToolCallbacks.from(h)) {
                String name = cb.getToolDefinition().name();
                boolean readOnly = toolRegistry.find(name)
                        .map(com.wenqu.ai.agent.tool.ToolDescriptor::readOnly).orElse(false);
                holders.add(new SpringAiToolAdapter(cb, readOnly,
                        () -> withBaseContext(slot.require(), java.util.Map.of()),
                        () -> policyOf(slot, policy),
                        false, approvalCanAsk, false,
                        () -> { AgentxTurnSlot.Turn t = slot.current(); return t == null ? null : t.taint(); },
                        () -> slot.toolAllowedForSession(name)));
            }
        }
    }

    /** 调用期的档位：优先当轮那份（管理员中途改审批档也能立刻生效），轮间空档才回落到建实例那份。 */
    private static com.wenqu.ai.agent.permission.ExecutionPolicy policyOf(
            AgentxTurnSlot slot, com.wenqu.ai.agent.permission.ExecutionPolicy fallback) {
        AgentxTurnSlot.Turn t = slot.current();
        return t == null ? fallback : t.policy();
    }

    /**
     * 任务清单写入器：<b>存储落在框架的 {@code AgentState.tasksContext}</b>，问渠只保留护栏与呈现。
     *
     * <p>为什么清单不落在 {@code c_ai_session.todos}：清单的权威状态该由框架持有
     * （{@code todo_write} 工具 + {@code TaskReminderMiddleware} 每轮重述都围绕它），
     * 问渠再存一份就是两个真���源迟早对不上。问渠做的是三件框架不管的事：
     * <ol>
     *   <li><b>护栏</b>——≤{@value com.wenqu.ai.service.TodoService#MAX_ITEMS} 项、单项截断、
     *       状态归一、同时只允许一项 doing。框架的 {@code todo_write} 没有这层，模型写歪了
     *       （几十项、单项写成段落、同时三项 doing）会直接进用户可见的清单卡；</li>
     *   <li><b>呈现在线</b>——转成 {@code todo} 帧推给前端，右栏那张「任务清单」卡要实时长出来；</li>
     *   <li><b>账本留痕</b>——断线后台续跑那一轮，清清单要能事后回放。</li>
     * </ol>
     *
     * <p>写入走 {@link com.wenqu.ai.service.TodoBridge}：它把问渠的归一结果翻译成框架
     * {@code Task} 列表，落在<b>这一轮活着的</b> {@code AgentState} 上（框架收尾时自己会 checkpoint，
     * 落库不在这条路径上）。那份活状态由 {@link SpringAiToolAdapter} 按次调用放进工具上下文——
     * 不是会话投影，投影是只读快照，改完即丢。
     */
    private com.wenqu.ai.service.TodoService.WriteFn todoWriter(com.wenqu.ai.engine.RunChannel channel,
                                                                 TurnHooks hooks) {
        return (items, state) -> {
            var n = todoService.normalize(items);
            if (!n.ok()) return "错误：清单没有有效条目，每项都要写一句可验收的动作。";
            String turnId = hooks.turnId().get();
            // 存储：框架这一轮活着的 AgentState（框架自己 todo_write 的同一份 tasksContext）。
            // 写不进去就不往下走——宁可这轮没有清单，也不能出现「右栏有清单、框架那边没有」
            // 的两个真值源（下次进同一会话模型看到的还是旧的）。
            var written = todoBridge.replace(state, hooks.sessionId(), n.items());
            if (!written.ok()) {
                // 不用 logger：这个类没有 log 字段，而失败原因已经原样回给模型了（工具报错会让它重试，
                // 说清比记一行日志有用）。真要排查，模型的重试内容里就有这句话。
                return "错误：清单写入失败（" + written.reason() + "），本轮未能更新任务清单。";
            }
            String doc = todoBridge.docJson(state, n.items(), turnId);
            if (doc == null) return "错误：清单写入后读不出条目，本轮未能同步任务清单。";
            // 当前步骤：清单里唯一 doing 的那一项（归一后至多一项）。工具卡按它打 stepId，
            // 执行详情据此把工具与产物归到具体步骤；没有 doing 就归 -1（还没进到某一步）
            int active = -1;
            for (int i = 0; i < n.items().size(); i++) {
                if ("doing".equals(n.items().get(i).status())) { active = i; break; }
            }
            hooks.step().index(active);
            // 账本留痕：断线后台续跑那一轮，清单也要能事后回放
            sessionEventService.append(hooks.sessionId(), hooks.userId(), turnId, "todo", doc);
            // 会话表那一份只是重启后的回显兜底（权威真源是框架 state；见 TodoBridge 类注释）
            todoService.save(hooks.sessionId(), doc);
            if (channel != null) channel.emit(com.wenqu.ai.engine.RunEventType.TODO, doc);
            return todoService.describe(n);
        };
    }

    /**
     * 沙盒工具的本轮落点，四个都给：权限档位（写入是否落在允许区）、输出增量（转成
     * {@code tool_output} 帧，前端「看着它跑」）、基建异常（登记到本轮 {@link InfraTrack} 供
     * {@code tool/result} 定性 + 转成 {@code warn} 帧，与命令退出码非 0 是两回事）、
     * 大输出外溢（截断前把整份原文落盘，卡片才有「查看完整输出」）。
     * 载荷一律交给 fastjson 序列化——沙盒输出里出现引号与换行是常态。
     */
    private Map<String, Object> sandboxContext(String toolName, AgentxTurnSlot.Turn t) {
        TurnHooks hooks = t.hooks();
        Map<String, Object> m = new java.util.HashMap<>();
        m.put(com.wenqu.ai.service.SandboxTools.CTX_SANDBOX_POLICY, t.policy());
        // run_code 的子调用回程：每轮在 ensureRunCodeTurn 里现装（没建出来时工具自己会回一句可见降级）
        var rcTurn = hooks.runCode().turn();
        if (rcTurn != null) {
            m.put(com.wenqu.ai.service.SandboxTools.CTX_RUN_CODE_BRIDGE, subCall.runCodeBridge(rcTurn));
        }
        m.put(com.wenqu.ai.service.SandboxTools.CTX_SPILL,
                (java.util.function.BiFunction<String, String, String>)
                        (name, text) -> toolSpillService.spill(hooks.sessionId(), hooks.userId(), name, text));
        m.put(com.wenqu.ai.service.SandboxTools.CTX_SPILL_REPORT,
                (java.util.function.BiConsumer<String, String>)
                        (name, spillId) -> hooks.spills().offer(name + "|" + spillId));
        m.put(com.wenqu.ai.service.SandboxTools.CTX_OUTPUT_SINK,
                (java.util.function.Consumer<String>) delta -> t.channel().emit(
                        com.wenqu.ai.engine.RunEventType.TOOL_OUTPUT,
                        com.alibaba.fastjson2.JSON.toJSONString(Map.of("name", toolName,
                                "delta", delta == null ? "" : delta))));
        m.put(com.wenqu.ai.service.SandboxTools.CTX_INFRA_ERROR_SINK,
                (java.util.function.Consumer<String>) msg -> {
                    // 基建故障登记到本轮：引擎在紧接着的那次 tool/result 定性时取走，该工具卡落 error。
                    // WARN 帧同时发一条——排查时聊天流里也能看见这次故障。
                    t.hooks().infra().mark(toolName, msg);
                    t.channel().emit(com.wenqu.ai.engine.RunEventType.WARN, "沙盒基建异常：" + msg);
                });
        return m;
    }

    /**
     * 每次调用都带上「这一轮是谁、哪条会话、这一轮的清单往哪写」。沙盒与产物族按前两个键取会话归属
     * （{@code PresentArtifactTool.CTX_SESSION_ID/CTX_USER_ID}）：跨线程读不到 {@code RequestUser}，
     * 缺了就建不出沙盒——而它必须显式透传，绝不静默回落 SYSTEM 建沙盒（那是越权归属）。
     * <p>任务清单写入器同样放在这份共用落点上：主链路的 {@code toolContext} 就是全族一张表，
     * 按族挂会漏（{@code writeTodo} 属任务清单族而不是内置族）。
     */
    private Map<String, Object> withBaseContext(AgentxTurnSlot.Turn t, Map<String, Object> perFamily) {
        TurnHooks hooks = t.hooks();
        Map<String, Object> m = new java.util.HashMap<>(perFamily);
        m.putIfAbsent(com.wenqu.ai.service.PresentArtifactTool.CTX_SESSION_ID, hooks.sessionId());
        m.putIfAbsent(com.wenqu.ai.service.PresentArtifactTool.CTX_USER_ID,
                hooks.userId() == null ? "" : hooks.userId());
        m.putIfAbsent(com.wenqu.ai.service.TodoService.CTX_TODO, todoWriter(t.channel(), hooks));
        return m;
    }

    /** 知识检索工具：命中块当场注册进本轮引用池并拿到真实编号，正文据此标 [N]。 */
    public record KnowledgeTools(KnowledgeRetrievalTool delegate,
                                 ConfigService configService,
                                 AgentxTurnSlot slot) {

        /**
         * 追问改写生产开关：A3 评估（2026-11-16）在当前样本上未证实 recall@5 增益（0.693→0.693），
         * 拍板默认关闭。调试端点 `/debug/retrieval` 与 `/eval/followup` 仍直接调用改写器做测量；
         * 要重新启用时把这里改成 true（并确认真实流量评估结论）。
         */
        private static final boolean KNOWLEDGE_REWRITE_ENABLED = false;

        @Tool(name = "search_knowledge", readOnly = true,
                description = "精确检索知识库，返回与查询相关的知识片段（含章节路径、标题、正文）。当现有信息不足以回答、或需要核对知识库中具体功能/字段/步骤/报错处理时调用。")
        public String searchKnowledge(
                @ToolParam(name = "query", description = "检索知识库的关键词或短语，越精准越好，可含功能名/字段名/操作对象") String query,
                @ToolParam(name = "topK", required = false, description = "返回结果条数（1~5，默认 5）") Integer topK) {
            // 身份快照、配置覆盖、库界与引用注册器都按<b>本次调用</b>从槽位取：
            // 实例按会话常驻，建实例时捕获的那一份只属于第一轮。
            AgentxTurnSlot.Turn t = slot.require();
            TurnHooks hooks = t.hooks();
            // 先记账再检索：零命中也算「本轮查过知识库」，并记下库范围——
            // 未命中问题要按库归属，否则库洞察会把别的库没答上来的问题算到当前库头上
            t.pool().markRetrievalAttempted(t.scope() == null ? null : t.scope().kbIds());
            // 弹性线程复用：身份必须在 finally 复原。刻意不用 try-with-resources——AutoCloseable.close()
            // 声明受检异常，而 @Tool 方法是被反射调用的，签名不该往外抛 Exception
            AutoCloseable identityHandle = RequestUser.restore(hooks.identity());
            try {
                Map<String, String> prev = configService.currentOverrides();
                Map<String, String> prevUser = configService.currentUserOverrides();
                configService.putOverrides(hooks.overrides());
                configService.putUserOverrides(hooks.userOverrides());
                try {
                    // 本轮原问题用于多问诊断/分解；改写仍由 KNOWLEDGE_REWRITE_ENABLED 控制，默认关时不调改写器。
                    // context 构造与该开关解绑，否则关闭状态下拿不到 question（见详细实施计划 §0 第 4 条）。
                    return delegate.searchKnowledge(query, topK, t.scope(), t.pool().registrar(),
                            new KnowledgeRetrievalTool.TurnQueryContext(
                                    hooks.question(), hooks.modelRef(), hooks.userId(),
                                    hooks.sessionId(), hooks.recentHistory(), KNOWLEDGE_REWRITE_ENABLED));
                } finally {
                    configService.clearOverride();
                    configService.clearUserOverrides();
                    if (!prev.isEmpty()) configService.putOverrides(prev);
                    if (!prevUser.isEmpty()) configService.putUserOverrides(prevUser);
                }
            } finally {
                try {
                    identityHandle.close();
                } catch (Exception e) {
                    RequestUser.clear();
                }
            }
        }
    }

    /**
     * run_code 起头：开这块扇出面板（每秒把在途与已完成合成一帧下发）。
     * <p>由引擎在工具卡转 running 时调用——面板的作用域是<b>一次运行</b>，两段脚本要各自复位。
     */
    public void beginRunCode(TurnHooks hooks) {
        var turn = hooks == null ? null : hooks.runCode().turn();
        if (turn != null) subCall.startRunBoard(turn);
    }

    /**
     * run_code 收尾：停掉 ticker，并交出卡片要带的两份看板数据（扇出清单 + 这次运行的结构化结果）。
     * <p>只带终态那一份、随卡片落库——刷新后仍然点得开。
     */
    public Map<String, Object> finishRunCodeCard(TurnHooks hooks) {
        var turn = hooks == null ? null : hooks.runCode().turn();
        if (turn == null) return Map.of();
        com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime.stopRunBoardTicker(turn);
        Map<String, Object> card = new java.util.HashMap<>();
        if (!turn.board().subCallLog.isEmpty()) card.put("fanOut", subCall.buildFanOut(turn, false));
        if (turn.board().runOutcome != null) {
            card.put("run", turn.board().runOutcome);
            turn.board().runOutcome = null;
        }
        return card;
    }

    /**
     * 框架引擎这一侧的<b>工具执行上下文</b>，只服务脚本的子调用回程。
     * <p>模型直调那一路由框架自己的权限系统 + {@code SpringAiToolAdapter#checkPermissions} 管；
     * 脚本里的子调用仍走问渠那条守卫链（{@code ToolPipelineFactory.wrap}），所以需要有人把
     * 「这一轮是谁、本轮挂了哪些工具、结果往哪登记引用、进度往哪发」交出去。
     * <p><b>审批恒不问人</b>（{@code approvalMode=off}）。这不是放行：装配时已把「登记为执行前需确认」
     * 的工具从脚本清单里剔除（见 {@link #ensureRunCodeTurn}），管线自己也从不放 {@code viewerRequired}
     * 那几把进来。少任何一道都会变成「脚本绕过了人」。
     * <p>这个对象按轮新建（{@link #ensureRunCodeTurn}），字段直接取当轮的 {@code Turn}；
     * 工具面（脚本可调用的回调与 MCP 名单）来自会话级 {@code Surface}，跨轮稳定。
     */
    private final class AgentxTurnContext implements com.wenqu.ai.agent.tool.ToolTurnContext {

        private final AgentxTurnSlot.Turn turn;
        private final ChatCommand cmd;
        private final AgentxTurnSlot.Surface surface;

        AgentxTurnContext(AgentxTurnSlot.Turn turn, ChatCommand cmd, AgentxTurnSlot.Surface surface) {
            this.turn = turn;
            this.cmd = cmd;
            this.surface = surface;
        }

        @Override
        public String sessionId() {
            return turn.hooks().sessionId();
        }

        @Override
        public String userId() {
            return turn.hooks().userId();
        }

        @Override
        public String turnId() {
            return turn.turnId();
        }

        @Override
        public boolean userStopped() {
            return stopRequested(cmd);
        }

        @Override
        public int maxToolSteps() {
            return WenquAgentxTools.this.maxToolSteps(cmd);
        }

        /** 子调用有自己的那一摞计数（守卫在 {@code subCallTurnContext} 里换掉这三项），这里如实报 0。 */
        @Override
        public int stepsTaken() {
            return 0;
        }

        @Override
        public void takeStep() {
        }

        @Override
        public String approvalMode() {
            return "off";
        }

        @Override
        public boolean requiresApproval(String toolName) {
            return false;
        }

        @Override
        public long approvalTimeoutMs() {
            return configService.getInt("chat.approvalTimeoutMs", 600000);
        }

        @Override
        public com.wenqu.ai.agent.tool.ToolApprover approver() {
            return null;
        }

        @Override
        public ExecutionPolicy permissionPolicy() {
            return turn.policy();
        }

        @Override
        public Map<String, Object> baseToolContext() {
            return withBaseContext(turn, Map.of());
        }

        @Override
        public Map<com.wenqu.ai.agent.tool.ToolFamily, com.wenqu.ai.agent.tool.ToolEnrichment> enrichments() {
            return Map.of(
                    com.wenqu.ai.agent.tool.ToolFamily.KNOWLEDGE, inv -> knowledgeEntries(),
                    com.wenqu.ai.agent.tool.ToolFamily.WEBSEARCH, inv -> Map.of(
                            com.wenqu.ai.service.websearch.WebSearchTools.CTX_SINK, turn.pool().sink()),
                    com.wenqu.ai.agent.tool.ToolFamily.SANDBOX, inv -> sandboxContext(inv.toolName(), turn));
        }

        /** 知识检索工具的本轮落点：注册器=同一个引用池，库界=与主链路同一条解析（不越过智能体绑定）。
         *  追问改写默认关闭（见 {@link KnowledgeTools#KNOWLEDGE_REWRITE_ENABLED}），这里不再注入改写上下文；
         *  调试/评估走各自入口直接调用改写器。 */
        private Map<String, Object> knowledgeEntries() {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put(KnowledgeRetrievalTool.CTX_SOURCE_REGISTRAR, turn.pool().registrar());
            m.put(KnowledgeRetrievalTool.CTX_KB_SCOPE, turn.scope());
            return m;
        }

        @Override
        public String execute(org.springframework.ai.tool.ToolCallback raw, String arguments,
                              Map<String, Object> context, com.wenqu.ai.agent.tool.ToolInvocation inv,
                              int[] attemptsOut) {
            // 子调用跑在 Tomcat/容器回程线程上：身份与本轮检索覆盖不重放，工具侧就是静默空结果
            return asCaller(turn.hooks(), () -> raw.call(arguments,
                    new org.springframework.ai.chat.model.ToolContext(context)));
        }

        @Override
        public void observe(com.wenqu.ai.agent.tool.ToolInvocation inv, String status, String resultOrError) {
            // 子调用不各开顶层卡片（一段脚本扇出 20 次会把过程区刷出 20 行）：
            // 记进 run_code 那张卡的扇出清单由守卫侧的 observe 覆盖，这里不该再做一遍
        }

        @Override
        public void onOutput(com.wenqu.ai.agent.tool.ToolInvocation inv, String delta) {
            if (delta == null) return;
            turn.channel().emit(com.wenqu.ai.engine.RunEventType.TOOL_OUTPUT,
                    com.alibaba.fastjson2.JSON.toJSONString(Map.of("name", inv.toolName(), "delta", delta)));
        }

        @Override
        public String decorateResult(com.wenqu.ai.agent.tool.ToolInvocation inv, String result) {
            // MCP 结果注册引用来源：脚本依据 MCP 内容作答时正文才有可点的角标，否则是悬空角标
            if (surface.mcpToolNames().contains(inv.toolName())) {
                return turn.pool().sink().registerMcpCitations(inv.toolName(), result);
            }
            return result;
        }
    }
}
