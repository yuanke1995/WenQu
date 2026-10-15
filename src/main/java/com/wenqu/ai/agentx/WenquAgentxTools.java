package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.context.RetrievalScopeService;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.service.AgentService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.KnowledgeRetrievalTool;
import com.wenqu.ai.service.RerankService;
import com.wenqu.ai.util.RequestUser;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 问渠领域能力 → 框架工具的挂载点。
 * <p>框架没有问渠这套多路召回（向量+关键词+重排+库界+引用编号），所以检索链<b>不重造也不重写</b>：
 * 直接把现有 {@link KnowledgeRetrievalTool} 挂成框架的一等工具，工具本体、检索参数覆盖、降级登记
 * 全部沿用同一份实现。这里只负责按「这一轮」把库界与引用池接上。
 */
@Component
public class WenquAgentxTools {

    private final KnowledgeRetrievalTool knowledgeRetrieval;
    private final RetrievalScopeService retrievalScope;
    private final AgentService agentService;
    private final ChatRunRuntime runtime;
    private final ConfigService configService;
    private final RerankService rerankService;
    private final ImageUrlSigner imageUrlSigner;
    private final com.wenqu.ai.service.ToolAssemblyService toolAssembly;
    private final com.wenqu.ai.agent.tool.ToolRegistry toolRegistry;
    private final com.wenqu.ai.service.TodoService todoService;
    private final com.wenqu.ai.service.ToolSpillService toolSpillService;
    private final com.wenqu.ai.service.SessionEventService sessionEventService;
    private final com.wenqu.ai.agent.subagent.SubAgentConsultService consultService;
    private final com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branchRuntime;
    private final com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime subCall;
    /**
     * 问答侧的「跑一整轮」入口（分支执行器要它起子轮）。用函数注册而不是注入 {@code ChatService}：
     * 与 {@code chatEngines.bindLegacy(this::runLegacyTurn)} 同一套路数——并列实现认一个可调用口，
     * 不认那个上帝类本身，否则构造环与「框架引擎依赖手写运行时」这两件事同时发生。
     */
    private volatile com.wenqu.ai.agent.subagent.SubAgentBranchRuntime.TurnRunner branchTurnRunner;

    public WenquAgentxTools(KnowledgeRetrievalTool knowledgeRetrieval, RetrievalScopeService retrievalScope,
                            AgentService agentService, ChatRunRuntime runtime, ConfigService configService,
                            RerankService rerankService, ImageUrlSigner imageUrlSigner,
                            com.wenqu.ai.service.ToolAssemblyService toolAssembly,
                            com.wenqu.ai.agent.tool.ToolRegistry toolRegistry,
                            com.wenqu.ai.service.TodoService todoService,
                            com.wenqu.ai.service.ToolSpillService toolSpillService,
                            com.wenqu.ai.service.SessionEventService sessionEventService,
                            com.wenqu.ai.agent.subagent.SubAgentConsultService consultService,
                            com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branchRuntime,
                            com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime subCall) {
        this.knowledgeRetrieval = knowledgeRetrieval;
        this.retrievalScope = retrievalScope;
        this.agentService = agentService;
        this.runtime = runtime;
        this.configService = configService;
        this.rerankService = rerankService;
        this.imageUrlSigner = imageUrlSigner;
        this.toolAssembly = toolAssembly;
        this.toolRegistry = toolRegistry;
        this.todoService = todoService;
        this.toolSpillService = toolSpillService;
        this.sessionEventService = sessionEventService;
        this.consultService = consultService;
        this.branchRuntime = branchRuntime;
        this.subCall = subCall;
    }

    /** 由 {@code AgentxConfiguration} 在装配期登记：{@code ChatService::chat}（含轮级互斥外壳的那个入口）。 */
    public void bindBranchTurn(com.wenqu.ai.agent.subagent.SubAgentBranchRuntime.TurnRunner runner) {
        this.branchTurnRunner = runner;
    }

    /**
     * 本轮的「落点」四件套：会话号 + 使用者 + 轮号 + 工具大输出外溢的回收队列。
     * <p>为什么单独立一个类型：任务清单要按轮留痕（{@code turnId}）、外溢要挂回收尾那一刻的
     * 工具卡（{@code spills}），这些都是「这一轮」的东西，而框架的工具在弹性线程上跑、
     * 又没有手写运行时的 {@code TurnState}，只能按轮显式传进来——藏进单例就是别人的轮串到这一轮。
     * <p>{@code userId} 也必须显式带：{@code RequestUser} 是 ThreadLocal，在框架的弹性线程上读出来
     * 是 anonymous，留痕就会记到匿名头上（这条坑本方案已实测踩过两次）。
     */
    public record TurnHooks(String sessionId, String userId, String modelRef,
                            java.util.function.Supplier<String> turnId,
                            java.util.concurrent.ConcurrentLinkedQueue<String> spills,
                            com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger consultLedger,
                            RequestUser.Snapshot identity,
                            Map<String, String> overrides, Map<String, String> userOverrides,
                            RunCodeHolder runCode,
                            java.util.List<org.springframework.ai.tool.ToolCallback> rawCallbacks,
                            java.util.Set<String> mcpToolNames) {
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
     * 装本轮落点袋。<b>必须在发起线程调用</b>：身份与本轮检索参数覆盖都是 ThreadLocal，
     * 框架把工具派到自己的弹性线程上，不在这里取快照就拿不到（{@code KnowledgeTools} 同一口径）。
     */
    public TurnHooks newHooks(ChatCommand cmd, String modelRef, java.util.function.Supplier<String> turnId,
                              java.util.concurrent.ConcurrentLinkedQueue<String> spills,
                              com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger ledger) {
        return new TurnHooks(cmd.getSessionId(), cmd.getUserId(), modelRef, turnId, spills, ledger,
                RequestUser.snapshot(), configService.currentOverrides(), configService.currentUserOverrides(),
                new RunCodeHolder(), java.util.Collections.synchronizedList(new java.util.ArrayList<>()),
                java.util.Collections.synchronizedSet(new java.util.HashSet<>()));
    }

    /**
     * 在工具线程上复原「这一轮是谁、这一轮显式改过哪些检索参数」，用完复原回去。
     * <p>不复原的后果实测过第三种：{@code AgentService#get} 内部带登录户可读校验，弹性线程上没身份
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
    public List<Object> frameworkTools(ChatCommand cmd, com.wenqu.ai.engine.RunChannel channel,
                                       WenquSourcePool pool,
                                       com.wenqu.ai.agent.permission.ExecutionPolicy policy,
                                       java.util.function.BiConsumer<String, String> onDegrade,
                                       boolean approvalCanAsk, TurnHooks hooks) {
        var mc = toolAssembly.mountContext(agentOf(cmd), cmd.getUserId(), cmd.isGuestMode(),
                false, cmd.branchTurn(), onDegrade, policy);
        List<Object> holders = new java.util.ArrayList<>(turnTools(cmd, pool));
        for (com.wenqu.ai.agent.tool.ToolFamilyProvider provider : toolRegistry.providersInFamilyOrder()) {
            var family = provider.family();
            if (family == com.wenqu.ai.agent.tool.ToolFamily.KNOWLEDGE) {
                // 知识族自己走问渠那条 @Tool（框架侧引用注册直接给），但脚本的子调用清单里要有它——
                // 所以只把回调收进本轮原始清单，不再挂进框架 Toolkit
                provider.callbacks(mc).forEach(hooks.rawCallbacks()::add);
                continue;
            }

            // 需要「本轮落点」的三族各自给出：联网的落点是本轮引用池、沙盒是档位与输出通道、
            // 子智能体是本轮咨询台账；其余族的主链路侧不读 per-call 上下文
            // （实测 enrichments 只有 KNOWLEDGE/WEBSEARCH/SANDBOX 三条），可直接复用
            if (family == com.wenqu.ai.agent.tool.ToolFamily.WEBSEARCH) {
                mountFrom(provider, mc, holders, cmd, channel, pool, policy, approvalCanAsk,
                        name -> Map.of(com.wenqu.ai.service.websearch.WebSearchTools.CTX_SINK, pool.sink()), hooks);
                continue;
            }
            if (family == com.wenqu.ai.agent.tool.ToolFamily.SANDBOX) {
                // 沙盒本轮落点给权限档位、输出增量、基建错误、外溢落盘与 run_code 的子调用回程
                //（回程在装配末尾、清单建好之后才填进沙盒上下文——脚本要的是「本轮挂上的那些工具」）
                mountFrom(provider, mc, holders, cmd, channel, pool, policy, approvalCanAsk,
                        name -> sandboxContext(name, channel, policy, hooks), hooks);
                continue;
            }
            if (family == com.wenqu.ai.agent.tool.ToolFamily.SUBAGENT) {
                // 咨询另一位助手：执行器按轮回传，预算/去重/回报转文本都在 SubAgentConsultService 里
                // （与问答侧同一个实现）。子轮仍走「再起一轮」那条通路——它要的是完整问答外壳
                // （即时落库、轮级互斥、断线续跑），不是框架引擎里再嵌一个内层循环
                mountFrom(provider, mc, holders, cmd, channel, pool, policy, approvalCanAsk,
                        name -> consultContext(cmd, channel, pool, hooks), hooks);
                continue;
            }
            if (family == com.wenqu.ai.agent.tool.ToolFamily.BUILTIN) {
                // 提问卡换成框架原生实现（AskUserTool）：内置那个靠阻塞 future 等答案，挂起信号又会被
                // Spring AI 包掉——框架内只留一套「等人」，不并存
                holders.add(new AskUserTool());
                mountFrom(provider, mc, holders, cmd, channel, pool, policy, approvalCanAsk, name -> Map.of(), hooks);
                continue;
            }
            mountFrom(provider, mc, holders, cmd, channel, pool, policy, approvalCanAsk, name -> Map.of(), hooks);
        }
        mountRunCodeChannel(cmd, channel, pool, policy, hooks, onDegrade);
        return holders;
    }

    /**
     * run_code 的子调用通道（P3b 的最后一块）。脚本里的每次工具调用必须回到<b>同一条守卫链</b>上
     * （叫停闸、权限档位、引用登记、扇出面板），绕过去就不是「少几次模型往返」而是「少一层管控」。
     * <p>清单在各族都挂完之后才建，所以这一步放在装配末尾。
     * <p><b>脚本里没人可答</b>：登记为「执行前需确认」的工具在会问人的档位下<b>不进脚本清单</b>——
     * 与「要有人在场的工具不进分支轮」同一条口径（{@code viewerRequired} 那一道由管线自己把）。
     * 全自主档（框架侧 DONT_ASK）本来就不问人，照常可被脚本调用。
     */
    private void mountRunCodeChannel(ChatCommand cmd, com.wenqu.ai.engine.RunChannel channel,
                                     WenquSourcePool pool, ExecutionPolicy policy, TurnHooks hooks,
                                     java.util.function.BiConsumer<String, String> onDegrade) {
        boolean mounted = hooks.rawCallbacks().stream().anyMatch(cb -> cb.getToolDefinition() != null
                && com.wenqu.ai.service.SandboxTools.RUN_CODE_TOOL_NAME.equals(cb.getToolDefinition().name()));
        if (!mounted) return;
        var turn = com.wenqu.ai.agent.runcode.RunCodeTurn.ofFramework(cmd.getSessionId(), hooks.turnId().get(),
                cmd.getUserId(), new com.wenqu.ai.agent.chat.TurnRunBoard(), new java.util.LinkedHashMap<>(),
                channel::emitter, new AgentxTurnContext(cmd, channel, pool, policy, hooks));
        hooks.runCode().turn(turn);
        // 「会不会问人」认的是问渠那条审批档（auto/ask/off），不是框架的 PermissionMode：
        // 框架 DEFAULT 下模型直调照样弹卡（那是看得见的逐项确认），但脚本里没有那个卡位。
        // 而且这条判据在装配前就被本族把过——approvalMode=ask 时 run_code 根本不挂
        //（见 SandboxToolProvider#runCodeAllowed），所以这里通常是空转的兜底。
        boolean asksHuman = asksHuman(policy);
        List<org.springframework.ai.tool.ToolCallback> forScript = new java.util.ArrayList<>();
        for (org.springframework.ai.tool.ToolCallback cb : hooks.rawCallbacks()) {
            String name = cb.getToolDefinition() == null ? null : cb.getToolDefinition().name();
            if (name == null) continue;
            if (asksHuman && toolRegistry.requiresApproval(name, configService::getBoolean)) continue;
            forScript.add(cb);
        }
        subCall.prepareSubCallPipeline(turn, forScript);
        if (asksHuman) {
            onDegrade.accept("engine.runcode.subcalls",
                    "脚本可调用的工具已按档位收窄：本轮要求执行前确认，而脚本里没有能答的人"
                            + "（需确认的那几把不进脚本清单；全自主档不受此限）");
        }
    }

    /** 本轮权限档位（装配判据、执行守卫与框架权限系统三处读同一份解析，见
     * {@code PermissionPolicyResolver}）。框架引擎这一侧没有 {@code TurnState}，
     * 所以档位在这里现算——它问的是同一个解析器，不是第二套规则。
     */
    public com.wenqu.ai.agent.permission.ExecutionPolicy policyFor(
            ChatCommand cmd, java.util.function.BiConsumer<String, String> onClamp) {
        return toolAssembly.resolvePermissionPolicy(agentOf(cmd), cmd.isGuestMode(), onClamp);
    }

    /**
     * 本轮的工具步数上限（0=不限制）：与问答侧同一份解析（{@code ToolAssemblyService#maxToolSteps}），
     * 分支深度也照读 {@code cmd.getSubagentDepth()}——两边走的是同一把预算，不是各数各的。
     */
    /**
     * 「这一轮会不会问人」——判据必须与手写侧 {@code ApprovalGate} 一字不差：智能体的审批档位是
     * {@code ask} 才算会问人（{@code auto}/{@code off}/未登记都算不问），档位没解析出来时按最保守的会问人处理。
     * <p>为什么单独收成一处：框架侧原先只看了「这把工具有没有副作用」，于是 auto 档也每把写操作都弹卡
     * （双跑实测：legacy 同一智能体同一问题一句没问，框架那边 write_file 与 MCP 只读工具都要确认）。
     * 卡片没人答的代价不是「多问一句」而是整轮悬到确认超时、正文什么都没有。
     */
    public static boolean asksHuman(com.wenqu.ai.agent.permission.ExecutionPolicy policy) {
        String approval = policy == null ? "ask" : policy.approvalMode();
        return "ask".equalsIgnoreCase(approval == null ? "auto" : approval.trim());
    }

    public int maxToolSteps(ChatCommand cmd) {
        return toolAssembly.maxToolSteps(agentOf(cmd), cmd.getSubagentDepth());
    }

    /** 用户是否已点「停止本轮」：问答侧那套把手是唯一的判据，框架引擎不另立一套停止语义。 */
    public boolean stopRequested(ChatCommand cmd) {
        return runtime.turnStopRequested(cmd.getSessionId());
    }

    /** 本轮的智能体行（无智能体绑定返回 null＝按平台默认档与使用者可见范围走）。 */
    public Agent agentOf(ChatCommand cmd) {
        return cmd.getAgentId() == null || cmd.getAgentId().isBlank() ? null : agentService.get(cmd.getAgentId());
    }

    /** 问答侧那套通道/账本/总线单点（重启续跑要拿它建一条没有观众的通道）。 */
    public ChatRunRuntime runtime() {
        return runtime;
    }

    /** 本轮的引用池（编号唯一真源仍是 {@code TurnSourceRegistry}，本类不另写一份）。 */
    public WenquSourcePool newPool(ChatCommand cmd, SseEmitter emitter) {
        return new WenquSourcePool(runtime, configService, rerankService, imageUrlSigner,
                cmd.getSessionId(), cmd.getQuestion(), emitter);
    }

    /**
     * 交给框架 Toolkit 的工具持有对象（框架反射收集 {@code @Tool}，schema 由框架生成）。
     * <p>库界口径与主链路一致：只有免登录分享轮才是权限墙，登录轮按使用者可见库收口。
     * <p>身份与配置覆盖<b>必须在发起线程（这里=问答流水线线程）取快照</b>：框架把工具派到自己的
     * 弹性线程上执行，不重放身份就是 anonymous → 可见库集合为空 → 检索恒空且一声不响；不重放覆盖
     * 则是设置页那份 {@code retrieval.*} 对工具侧静默失效。两个坑都是实测踩过的。
     */
    public List<Object> turnTools(ChatCommand cmd, WenquSourcePool pool) {
        KnowledgeRetrievalTool.KbScope scope =
                retrievalScope.resolve(agentOf(cmd), cmd.getUserId(), cmd.isGuestMode());
        return List.of(new KnowledgeTools(knowledgeRetrieval, configService, scope, pool.registrar(),
                RequestUser.snapshot(), configService.currentOverrides(), configService.currentUserOverrides()));
    }

    /**
     * 本轮的咨询落点。四样都按轮现算：台账（同一位问两次不该再花一整条子轮）、分支执行器（子轮要的是
     * 完整问答外壳）、引用登记（子轮查到的资料进<b>父轮</b>编号空间，结论才点得开出处）、
     * 可见事件（「本轮咨询了谁」的徽标不能等整轮 done——用户盯着转圈的几十秒里就该看见它在问谁）。
     * <p>执行器与预算/去重/回报转文本全部复用 {@code SubAgentConsultService}：框架侧不另写一套咨询语义。
     */
    private Map<String, Object> consultContext(ChatCommand cmd, com.wenqu.ai.engine.RunChannel channel,
                                               WenquSourcePool pool, TurnHooks hooks) {
        com.wenqu.ai.agent.subagent.SubAgentConsultService.Ctx ctx =
                new com.wenqu.ai.agent.subagent.SubAgentConsultService.Ctx(
                        cmd.getSessionId(), hooks.turnId().get(), cmd.getUserId(), hooks.modelRef(),
                        cmd.isGuestMode(), cmd.getSubagentDepth(),
                        cmd.getAgentId() == null ? "" : cmd.getAgentId(),
                        // @ 提及强制咨询那一位：框架引擎这一侧没有 mentions 那一段编排，如实留空
                        //（后果只是「模型可以再问一次同一位」，由台账的去重兜住）
                        "",
                        (consultedId, consultedName) -> channel.emit(com.wenqu.ai.engine.RunEventType.AGENT_CONSULTED,
                                com.alibaba.fastjson2.JSON.toJSONString(
                                        Map.of("id", consultedId, "name", consultedName))));
        com.wenqu.ai.agent.subagent.BranchRunner runner = branchTurnRunner == null ? null
                : branchRuntime.branchRunner(branchTurnRunner, cmd.getSessionId(), hooks.turnId().get(),
                        cmd.getUserId(), cmd.isGuestMode(), channel.emitter());
        // runner=null 时咨询服务自己回一句「通道未就绪」给模型（可见降级），不 NPE 也不静默。
        // asCaller 罩住整次咨询：子轮是在这条弹性线程上「再起一整轮」的，身份与覆盖得跟着进去
        return Map.of(com.wenqu.ai.agent.tool.SubAgentTools.CTX_CONSULT,
                (com.wenqu.ai.agent.tool.SubAgentTools.ConsultFn) (agentId, question) -> asCaller(hooks,
                        () -> consultService.consult(hooks.consultLedger(), ctx, runner,
                                pool::registerConsultedSources, agentId, question)));
    }

    /**
     * 「可咨询助手」一节。判据与工具挂载<b>同源</b>（问的是同一份 {@code ToolRegistry#exposes}）：
     * 教模型用一件它手上没有的能力，是本仓反复点名的错。正文与问答侧共用
     * {@code AgentService#consultGuideSection}，两份措辞分开写早晚会对不上。
     */
    public String consultGuide(ChatCommand cmd) {
        var mc = toolAssembly.mountContext(agentOf(cmd), cmd.getUserId(), cmd.isGuestMode(),
                false, cmd.branchTurn(), (code, message) -> {
                }, null);
        if (!toolRegistry.exposes(com.wenqu.ai.agent.tool.ToolFamily.SUBAGENT, "consultAgent", mc)) return "";
        return agentService.consultGuideSection(cmd.getAgentId() == null ? "" : cmd.getAgentId());
    }

    private void mountFrom(com.wenqu.ai.agent.tool.ToolFamilyProvider provider,
                           com.wenqu.ai.agent.tool.ToolMountContext mc, List<Object> holders,
                           ChatCommand cmd, com.wenqu.ai.engine.RunChannel channel, WenquSourcePool pool,
                           com.wenqu.ai.agent.permission.ExecutionPolicy policy, boolean approvalCanAsk,
                           java.util.function.Function<String, Map<String, Object>> enrichment,
                           TurnHooks hooks) {
        mountFrom(provider, mc, holders, cmd, channel, pool, policy, approvalCanAsk, enrichment, null, hooks);
    }

    private void mountFrom(com.wenqu.ai.agent.tool.ToolFamilyProvider provider,
                           com.wenqu.ai.agent.tool.ToolMountContext mc, List<Object> holders,
                           ChatCommand cmd, com.wenqu.ai.engine.RunChannel channel, WenquSourcePool pool,
                           com.wenqu.ai.agent.permission.ExecutionPolicy policy, boolean approvalCanAsk,
                           java.util.function.Function<String, Map<String, Object>> enrichment,
                           String skipToolName, TurnHooks hooks) {
        for (org.springframework.ai.tool.ToolCallback cb : provider.callbacks(mc)) {
            String name = cb.getToolDefinition().name();
            if (AskUserTool.NAME.equals(name)) continue;   // 由框架原生 AskUserTool 顶替
            if (skipToolName != null && skipToolName.equals(name)) continue;
            hooks.rawCallbacks().add(cb);
            if (provider.family() == com.wenqu.ai.agent.tool.ToolFamily.MCP) hooks.mcpToolNames().add(name);
            // 要不要问人沿用问渠的登记（未登记一律要审批，fail-closed）——不在框架侧另判一次「像不像写操作」
            boolean needsApproval = asksHuman(policy)
                    && toolRegistry.requiresApproval(name, configService::getBoolean);
            holders.add(new SpringAiToolAdapter(cb,
                    () -> withBaseContext(enrichment.apply(name), cmd, channel, hooks), policy,
                    needsApproval, approvalCanAsk));
        }
    }

    /**
     * 任务清单写入器（与主链路 {@code ChatService.toolContext} 同一段动作：归一 → 整表落库
     * → 账本留痕 → 实时 {@code todo} 帧 → 回给模型一句「存成什么样了」）。
     * <p>不给的表现为模型每次写清单都收到「本轮未启用任务清单」，右栏永远空白——这条是真机抓到的：
     * 原先把它挂在内置族的落点上，而 {@code writeTodo} 属任务清单族，于是每次调用都取不到写入器。
     * 主链路那份 {@code toolContext} 本来就是全族共用的一张表，所以这里也挂在共用落点上。
     */
    private com.wenqu.ai.service.TodoService.WriteFn todoWriter(com.wenqu.ai.engine.RunChannel channel,
                                                                 TurnHooks hooks) {
        return items -> {
            var n = todoService.normalize(items);
            if (!n.ok()) return "错误：清单没有有效条目，每项都要写一句可验收的动作。";
            String turnId = hooks.turnId().get();
            String doc = todoService.docJson(turnId, n.items());
            todoService.save(hooks.sessionId(), doc);
            // 账本留痕：断线后台续跑那一轮，清单也要能事后回放（与手写侧同一句）
            sessionEventService.append(hooks.sessionId(), hooks.userId(), turnId, "todo", doc);
            if (channel != null) channel.emit(com.wenqu.ai.engine.RunEventType.TODO, doc);
            return todoService.describe(n);
        };
    }

    /**
     * 沙盒工具的本轮落点，四个都给：权限档位（写入是否落在允许区）、输出增量（转成
     * {@code tool_output} 帧，前端「看着它跑」）、基建异常（转成 {@code warn} 帧，与命令退出码
     * 非 0 是两回事）、大输出外溢（截断前把整份原文落盘，卡片才有「查看完整输出」）。
     * 载荷一律交给 fastjson 序列化——沙盒输出里出现引号与换行是常态。
     */
    private Map<String, Object> sandboxContext(String toolName,
                                               com.wenqu.ai.engine.RunChannel channel,
                                               ExecutionPolicy policy, TurnHooks hooks) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put(com.wenqu.ai.service.SandboxTools.CTX_SANDBOX_POLICY, policy);
        // run_code 的子调用回程：装配末尾才建好，这里逐次现取（没建出来时工具自己会回一句可见降级）
        var rcTurn = hooks == null ? null : hooks.runCode().turn();
        if (rcTurn != null) {
            m.put(com.wenqu.ai.service.SandboxTools.CTX_RUN_CODE_BRIDGE, subCall.runCodeBridge(rcTurn));
        }
        m.put(com.wenqu.ai.service.SandboxTools.CTX_SPILL,
                (java.util.function.BiFunction<String, String, String>)
                        (name, text) -> toolSpillService.spill(hooks.sessionId(), hooks.userId(), name, text));
        m.put(com.wenqu.ai.service.SandboxTools.CTX_SPILL_REPORT,
                (java.util.function.BiConsumer<String, String>)
                        (name, spillId) -> hooks.spills().offer(name + "|" + spillId));
        if (channel != null) {
            m.put(com.wenqu.ai.service.SandboxTools.CTX_OUTPUT_SINK,
                    (java.util.function.Consumer<String>) delta -> channel.emit(
                            com.wenqu.ai.engine.RunEventType.TOOL_OUTPUT,
                            com.alibaba.fastjson2.JSON.toJSONString(Map.of("name", toolName,
                                    "delta", delta == null ? "" : delta))));
            m.put(com.wenqu.ai.service.SandboxTools.CTX_INFRA_ERROR_SINK,
                    (java.util.function.Consumer<String>) msg -> channel.emit(
                            com.wenqu.ai.engine.RunEventType.WARN, "沙盒基建异常：" + msg));
        }
        return m;
    }

    /**
     * 每次调用都带上「这一轮是谁、哪条会话、这一轮的清单往哪写」。沙盒与产物族按前两个键取会话归属
     * （{@code PresentArtifactTool.CTX_SESSION_ID/CTX_USER_ID}）：跨线程读不到 {@code RequestUser}，
     * 缺了就建不出沙盒——而它必须显式透传，绝不静默回落 anonymous 建沙盒（那是越权归属）。
     * <p>任务清单写入器同样放在这份共用落点上：主链路的 {@code toolContext} 就是全族一张表，
     * 按族挂会漏（{@code writeTodo} 属任务清单族而不是内置族，真机错过一次）。
     */
    private Map<String, Object> withBaseContext(Map<String, Object> perFamily, ChatCommand cmd,
                                                com.wenqu.ai.engine.RunChannel channel, TurnHooks hooks) {
        Map<String, Object> m = new java.util.HashMap<>(perFamily);
        m.putIfAbsent(com.wenqu.ai.service.PresentArtifactTool.CTX_SESSION_ID, cmd.getSessionId());
        m.putIfAbsent(com.wenqu.ai.service.PresentArtifactTool.CTX_USER_ID,
                cmd.getUserId() == null ? "" : cmd.getUserId());
        if (hooks != null) {
            m.putIfAbsent(com.wenqu.ai.service.TodoService.CTX_TODO, todoWriter(channel, hooks));
        }
        return m;
    }

    /** 知识检索工具：命中块当场注册进本轮引用池并拿到真实编号，正文据此标 [N]。 */
    public record KnowledgeTools(KnowledgeRetrievalTool delegate,
                                 ConfigService configService,
                                 KnowledgeRetrievalTool.KbScope scope,
                                 KnowledgeRetrievalTool.SourceRegistrar registrar,
                                 RequestUser.Snapshot identity,
                                 Map<String, String> overrides,
                                 Map<String, String> userOverrides) {

        @Tool(name = "search_knowledge", readOnly = true,
                description = "精确检索知识库，返回与查询相关的知识片段（含章节路径、标题、正文）。当现有信息不足以回答、或需要核对知识库中具体功能/字段/步骤/报错处理时调用。")
        public String searchKnowledge(
                @ToolParam(name = "query", description = "检索知识库的关键词或短语，越精准越好，可含功能名/字段名/操作对象") String query,
                @ToolParam(name = "topK", required = false, description = "返回结果条数（1~5，默认 5）") Integer topK) {
            // 弹性线程复用：身份必须在 finally 复原。刻意不用 try-with-resources——AutoCloseable.close()
            // 声明受检异常，而 @Tool 方法是被反射调用的，签名不该往外抛 Exception
            AutoCloseable identityHandle = RequestUser.restore(identity);
            try {
                Map<String, String> prev = configService.currentOverrides();
                Map<String, String> prevUser = configService.currentUserOverrides();
                configService.putOverrides(overrides);
                configService.putUserOverrides(userOverrides);
                try {
                    return delegate.searchKnowledge(query, topK, scope, registrar, false);
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
     * run_code 起头：开这块扇出面板（每秒把在途与已完成合成一帧下发，见 P3b）。
     * <p>由引擎在工具卡转 running 时调用——面板的作用域是<b>一次运行</b>，两段脚本要各自复位。
     */
    public void beginRunCode(TurnHooks hooks) {
        var turn = hooks == null ? null : hooks.runCode().turn();
        if (turn != null) subCall.startRunBoard(turn);
    }

    /**
     * run_code 收尾：停掉 ticker，并交出卡片要带的两份看板数据（扇出清单 + 这次运行的结构化结果）。
     * <p>只带终态那一份、随卡片落库——刷新后仍然点得开，与手写侧那条口径一致。
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
     * 的工具从脚本清单里剔除（见 {@link #mountRunCodeChannel}），管线自己也从不放 {@code viewerRequired}
     * 那几把进来。少任何一道都会变成「脚本绕过了人」。
     */
    private final class AgentxTurnContext implements com.wenqu.ai.agent.tool.ToolTurnContext {

        private final ChatCommand cmd;
        private final com.wenqu.ai.engine.RunChannel channel;
        private final WenquSourcePool pool;
        private final ExecutionPolicy policy;
        private final TurnHooks hooks;

        AgentxTurnContext(ChatCommand cmd, com.wenqu.ai.engine.RunChannel channel, WenquSourcePool pool,
                          ExecutionPolicy policy, TurnHooks hooks) {
            this.cmd = cmd;
            this.channel = channel;
            this.pool = pool;
            this.policy = policy;
            this.hooks = hooks;
        }

        @Override
        public String sessionId() {
            return cmd.getSessionId();
        }

        @Override
        public String userId() {
            return cmd.getUserId();
        }

        @Override
        public String turnId() {
            return hooks.turnId().get();
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
            return configService.getInt("chat.approvalTimeoutMs", 120000);
        }

        @Override
        public com.wenqu.ai.agent.tool.ToolApprover approver() {
            return null;
        }

        @Override
        public ExecutionPolicy permissionPolicy() {
            return policy;
        }

        @Override
        public Map<String, Object> baseToolContext() {
            return withBaseContext(Map.of(), cmd, channel, hooks);
        }

        @Override
        public Map<com.wenqu.ai.agent.tool.ToolFamily, com.wenqu.ai.agent.tool.ToolEnrichment> enrichments() {
            return Map.of(
                    com.wenqu.ai.agent.tool.ToolFamily.KNOWLEDGE, inv -> knowledgeEntries(),
                    com.wenqu.ai.agent.tool.ToolFamily.WEBSEARCH, inv -> Map.of(
                            com.wenqu.ai.service.websearch.WebSearchTools.CTX_SINK, pool.sink()),
                    com.wenqu.ai.agent.tool.ToolFamily.SANDBOX, inv -> sandboxContext(inv.toolName(),
                            channel, policy, hooks));
        }

        /** 知识检索工具的本轮落点：注册器=同一个引用池，库界=与主链路同一条解析（不越过智能体绑定）。 */
        private Map<String, Object> knowledgeEntries() {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put(KnowledgeRetrievalTool.CTX_SOURCE_REGISTRAR, pool.registrar());
            m.put(KnowledgeRetrievalTool.CTX_KB_SCOPE,
                    retrievalScope.resolve(agentOf(cmd), cmd.getUserId(), cmd.isGuestMode()));
            m.put(KnowledgeRetrievalTool.CTX_REFLECTIVE, false);
            return m;
        }

        @Override
        public String execute(org.springframework.ai.tool.ToolCallback raw, String arguments,
                              Map<String, Object> context, com.wenqu.ai.agent.tool.ToolInvocation inv,
                              int[] attemptsOut) {
            // 子调用跑在 Tomcat/容器回程线程上：身份与本轮检索覆盖不重放，工具侧就是静默空结果
            return asCaller(hooks, () -> raw.call(arguments,
                    new org.springframework.ai.chat.model.ToolContext(context)));
        }

        @Override
        public void observe(com.wenqu.ai.agent.tool.ToolInvocation inv, String status, String resultOrError) {
            // 子调用不各开顶层卡片（一段脚本扇出 20 次会把过程区刷出 20 行）：
            // 记进 run_code 那张卡的扇出清单由守卫侧的 observe 覆盖，这里不该再做一遍
        }

        @Override
        public void onOutput(com.wenqu.ai.agent.tool.ToolInvocation inv, String delta) {
            if (channel == null || delta == null) return;
            channel.emit(com.wenqu.ai.engine.RunEventType.TOOL_OUTPUT,
                    com.alibaba.fastjson2.JSON.toJSONString(Map.of("name", inv.toolName(), "delta", delta)));
        }

        @Override
        public String decorateResult(com.wenqu.ai.agent.tool.ToolInvocation inv, String result) {
            // MCP 结果注册引用来源：脚本依据 MCP 内容作答时正文才有可点的角标，否则是悬空角标
            if (hooks.mcpToolNames().contains(inv.toolName())) {
                return pool.sink().registerMcpCitations(inv.toolName(), result);
            }
            return result;
        }

        @Override
        public String appendSteers(com.wenqu.ai.agent.tool.ToolInvocation inv, String result) {
            // 插话是给「正在出字的这一轮」的，脚本的一条子调用不是它的受众（与守卫侧同一口径）
            return result;
        }
    }
}
