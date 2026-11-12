package com.wenqu.ai.service;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.TurnDeadline;
import com.wenqu.ai.agent.chat.TurnHandle;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.engine.RunEventType;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.model.Agent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 问答运行时门面：一轮问答的<b>外壳</b>都在这里——
 * 会话轮级互斥、断线续跑总线、身份/偏好装载、分派（单一 AgentScope 框架引擎）。
 * <p>推理循环、工具派发、上下文压缩、计划工具面已全部由 AgentScope 框架引擎（{@code agentx} 包）
 * 原生接管；本类不再持有任何生成侧状态，只保留轮级外壳（互斥、把手、看门狗、断线接流，
 * 经 {@link ChatRunRuntime}）。
 * <p>检索执行在 {@code HybridRetrievalService}/{@code RerankService}，卡片在
 * {@code HitlDecisionService}，会话状态在框架侧——本类只回答「这一轮谁来做」。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ChatService {

    /**
     * 智能体可覆盖的检索参数白名单：<b>只留“出水”那一层的开关</b>。
     *
     * <p>两阶段分工：知识库管“召回池”（阈值、两路权重、重排模型，见
     * {@link KbParamCodec}），智能体管“这一轮怎么用检索”（要不要重排、给模型几条）。
     * 同一个键只归一层：运行时线程覆盖会静默压掉库级配置，
     * 库主调好的阈值须从库级取。
     *
     * <p>{@code public}：设置页/默认值端点按同一白名单展示可配置项。
     */
    public static final Set<String> AGENT_QUERY_PARAM_KEYS = Set.of("rerank.enabled");

    /**
     * 不归智能体层的键：读取时静默忽略（存量 JSON 不报错，也不每轮刷 warn），保存时不写入。
     * <p>阈值/权重/召回量归知识库或全局；重排模型归知识库（资源归属：谁建库谁付费）。
     */
    private static final Set<String> AGENT_QUERY_PARAM_MOVED = Set.of(
            "retrieval.vectorWeight", "retrieval.keywordWeight", "retrieval.vecThreshold",
            "retrieval.vectorTopK", "retrieval.keywordLimit",
            "rerank.model", "rerank.baseUrl");

    /**
     * 事件账本里适合带上小对象的卡片类事件：产物、提问、审批、计划、派遣路由。
     * 只在载荷不超过 1500 字时解析——大计划不整段塞进回放响应。
     */
    private static final Set<String> LEDGER_OBJECT_EVENT_TYPES = Set.of(
            "artifact", "ask_user", "approval_required", "plan", "deep_plan_approval",
            "agent_delegated", "agent_consulted", "subagent_route");

    /**
     * 解析智能体检索参数 JSON → 覆盖表；**白名单之外的键一律丢弃**（防止借参数覆盖改到无关配置），
     * 解析失败只忽略该来源、不中断问答。
     */
    private Map<String, String> parseQueryParams(String json) {
        Map<String, String> ov = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return ov;
        try {
            Map<String, Object> m = JSON.parseObject(json);
            if (m == null) return ov;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (!AGENT_QUERY_PARAM_KEYS.contains(e.getKey())) {
                    if (AGENT_QUERY_PARAM_MOVED.contains(e.getKey())) {
                        // 已归知识库/全局的键：静默忽略（debug 便于排查"为什么配了没变"）
                        log.debug("[AGENT] 检索参数已迁出智能体层，忽略: {}", e.getKey());
                    } else {
                        log.warn("[AGENT] 检索参数键不在白名单内，已忽略: {}", e.getKey());
                    }
                    continue;
                }
                String v = String.valueOf(e.getValue()).trim();
                if (!v.isEmpty()) ov.put(e.getKey(), v);
            }
        } catch (Exception e) {
            log.warn("[AGENT] 检索参数解析失败（忽略该来源，使用上层配置）: {}", e.getMessage());
        }
        return ov;
    }

    /**
     * 把本轮智能体的检索开关覆盖装进 {@link ConfigService} 的线程作用域覆盖层。
     * <p>来源是 {@code c_ai_agent.query_params}，白名单见 {@link #AGENT_QUERY_PARAM_KEYS}——
     * 当前只有 {@code rerank.enabled}（场景性开关）。检索阈值/权重归知识库，由
     * {@link KbRetrievalParamService} 逐库生效，<b>不再经这里装载</b>：
     * 库级参数必须按库取用，压成回合级标量会在跨库检索时丢掉各库差异。
     * <p><b>无智能体绑定 / 参数为空时不装载</b>——留着 {@code OVERRIDE} 为空，检索便一路读全局设置，
     * 与「null/空=全部继承全局设置」的字段口径一致。清理由本轮 finally 的
     * {@code clearOverride()} 负责，本方法不自行清理（它在引擎跑完整轮之前不能撤）。
     */
    private void loadAgentQueryOverrides(String agentId) {
        if (agentId == null || agentId.isBlank()) return;
        Agent bound;
        try {
            bound = agentService.get(agentId);
        } catch (Exception e) {
            log.warn("[AGENT] 智能体检索开关装载失败（本轮按全局设置检索）: agent={} err={}",
                    agentId, e.getMessage());
            return;
        }
        if (bound == null) return;
        Map<String, String> ov = parseQueryParams(bound.getQueryParams());
        if (ov.isEmpty()) return;
        configService.putOverrides(ov);
        log.info("[AGENT] 本轮装载智能体检索开关覆盖: agent={} keys={}", agentId, ov.keySet());
    }


    private final SessionService sessionService;
    private final ConfigService configService;
    /** 附件元信息落库的共用口径（工作流路径用；与框架引擎同一个方法，见 metasJson 的类注释） */
    private final ChatAttachmentService chatAttachments;
    /** 被挡下的那一问也要带回图片，故与引擎一样持有图片落盘口（字段名不叫 userImages：本类里那是提问入参的惯用局部名） */
    private final UserImageService userImageService;
    /** 知识库精确检索工具（Function Calling；由 tool.* 配置开关控制，默认开启） */
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;
    /** MCP 客户端服务（外部 MCP server 工具接入；按用户隔离连接池，各人连各人的服务） */

    /** 知识库服务：解析「智能体关联的知识库 → 允许检索的文档集合」，检索按库隔离 */
    private final KnowledgeBaseService knowledgeBaseService;


    /** 用户表（个人默认模型解析） */
    private final com.wenqu.ai.mapper.UserMapper userMapper;
    /** 产物交付工具（Function Calling；生成文件并实时推送） */
    /** 提问工具（askUser，人在回路；随工具总闸启用，按智能体三态/停用名单收口） */
    private final AskUserTools askUserTools;
    /** 联网搜索工具（webSearch.enabled 控制，默认关；结果注册进引用体系，与知识库来源同编号） */
    /** 沙盒（AgentScope 原生文件/命令工具；聊天里的启停由权限档位控制） */
    /** 个人配置覆盖（schema personal 字段）：流水线线程装载 ThreadLocal，本轮 configService.get 优先个人值 */
    private final UserConfigService userConfigService;
    /** 工作区：默认助手解析（工作区页配的助手优先于平台默认智能体） */
    private final ProjectService projectService;
    /** 智能体配置（4.1）：对话页下拉选中后，按智能体覆盖模型/提示词/工具/知识库范围 */
    private final AgentService agentService;
    private final ModelRegistryService modelRegistryService;

    /** 会话事件账本：结构性事件按写入顺序留痕（工具/人在回路/编排/轮终态/停止）。旁路语义，不进模型输入 */
    private final SessionEventService sessionEventService;
    /**
     * 运行总线（断线重连接流）：本轮的执行与那条 SSE 通道解耦——事件先进总线再扇给此刻挂着的通道，
     * 观众走光不掐本轮（宽限到点才按中断收束），迟到的观众先拿快照再接增量。详见 {@link ChatRunBus}。
     */
    private final ChatRunBus chatRunBus;

    /**
     * 轮次控制与存活：SSE 投递与断开跟踪、心跳与存活看门狗、停止本轮 / 运行中插话 /
     * 探测接流、断线宽限中止都在它那里。本类只剩「这一轮怎么把资料找出来、答案怎么生成出来」。
     * <p>方向单向：问答侧调用它，它不回叫问答侧。{@code chat()} 刻意留在这边（要提交流水线），
     * 四处访问都走它暴露的具名闸口——登记表不外露，进出都得走名字。
     */
    private final ChatRunRuntime runtime;

    /** 子智能体分支簿记：顶层轮结束时把这一轮派出去、还挂在审批/执行上的分支同步收口。 */
    private final com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branchRuntime;

    /**
     * 问答外壳：常规问答已全部交给 AgentScope 框架引擎（见 {@code agentx} 包），
     * 本类保留承担「轮级外壳」（会话轮级互斥、断线续跑、身份与个人覆盖装载）。
     */
    private final com.wenqu.ai.engine.ChatEngineFactory chatEngines;
    private final UserRoleService userRoleService;
    /** 内容安全输入闸：命中即不发起本轮，与模型出口的出口闸共用一份判据 */
    private final com.wenqu.ai.service.contentsafety.ContentSafetyService contentSafety;



    /** 问答流水线线程池：图片描述 join/检索/深度思考等重活都在独立线程执行，避免占满 Tomcat 请求线程；
     *   chat.pipelineThreads 可调（保存即生效），队列有界，满时快速失败返回"系统繁忙" */
    private ThreadPoolExecutor pipelineExecutor;

    @jakarta.annotation.PostConstruct
    void initPipeline() {
        syncPipelineSize();
        warmUpToolSchemas();
    }




    /**
     * 启动期单线程预热工具 schema：@Tool 回调的 inputSchema 由 victools/Jackson 首次
     * introspection 时生成，Jackson 对同一类型的并发首采会抛
     * 「Conflicting property-based creators」（record 的 canonical 构造器被两个线程同时收集）。
     * 启动期串行生成一遍，Jackson 的 introspection 缓存填充后，后续并发生成只读缓存。
     */
    private void warmUpToolSchemas() {
        try {
            for (org.springframework.ai.tool.ToolCallback cb :
                    org.springframework.ai.support.ToolCallbacks.from(askUserTools)) {
                cb.getToolDefinition().inputSchema();
            }
            for (org.springframework.ai.tool.ToolCallback cb :
                    org.springframework.ai.support.ToolCallbacks.from(knowledgeRetrievalTool)) {
                cb.getToolDefinition().inputSchema();
            }
            log.info("[TOOL] 工具 schema 预热完成（规避并发首采竞态）");
        } catch (Exception e) {
            log.warn("[TOOL] 工具 schema 预热失败（不影响启动，仅并发首轮有竞态风险）: {}", e.getMessage());
        }
    }

    /** 提交前同步流水线线程数（chat.pipelineThreads，DB 配置保存即生效）。
     *  默认 32：8 线程 + 64 队列在百级并发下第 73 个起直接「系统繁忙」，且深度思考轮占线程
     *  可达 50s——32/200 是 8C16G 单机 100 并发生成目标的起点，压测后可在设置页继续上调 */
    private void syncPipelineSize() {
        int n = Math.max(2, configService.getInt("chat.pipelineThreads", 32));
        if (pipelineExecutor == null) {
            pipelineExecutor = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(200), r -> {
                Thread t = new Thread(r, "chat-pipeline");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
            log.info("[Chat] 问答流水线线程池创建: {} 线程", n);
        } else if (n != pipelineExecutor.getCorePoolSize()) {
            pipelineExecutor.setCorePoolSize(n);
            pipelineExecutor.setMaximumPoolSize(n);
            log.info("[Chat] 问答流水线线程数调整为 {}", n);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdownPipelineExecutor() {
        if (pipelineExecutor != null) {
            pipelineExecutor.shutdownNow();
        }
    }



    public ChatService(SessionService sessionService,
                      ConfigService configService,
                      ChatAttachmentService chatAttachments,
                      UserImageService userImageService,
                      KnowledgeRetrievalTool knowledgeRetrievalTool,
                      AskUserTools askUserTools,
                      AgentService agentService,
                      KnowledgeBaseService knowledgeBaseService,
                      com.wenqu.ai.mapper.UserMapper userMapper,
                      ModelRegistryService modelRegistryService,
                      UserConfigService userConfigService,
                      ProjectService projectService,
                      SessionEventService sessionEventService,
                      ChatRunBus chatRunBus,
                      ChatRunRuntime runtime,
                      com.wenqu.ai.engine.ChatEngineFactory chatEngines,
                      com.wenqu.ai.service.contentsafety.ContentSafetyService contentSafety,
                      UserRoleService userRoleService,
                      com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branchRuntime) {
        this.sessionService = sessionService;
        this.configService = configService;
        this.chatAttachments = chatAttachments;
        this.userImageService = userImageService;
        this.knowledgeRetrievalTool = knowledgeRetrievalTool;
        this.askUserTools = askUserTools;
        this.agentService = agentService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.userMapper = userMapper;
        this.userConfigService = userConfigService;
        this.projectService = projectService;
        this.modelRegistryService = modelRegistryService;
        this.sessionEventService = sessionEventService;
        this.chatRunBus = chatRunBus;
        this.runtime = runtime;
        this.chatEngines = chatEngines;
        this.contentSafety = contentSafety;
        this.userRoleService = userRoleService;
        this.branchRuntime = branchRuntime;
    }

    /**
     * 处理用户问题（可含上传图片与附件），通过 SSE 流式返回。
     * <p>参数统一收进 {@link ChatCommand}：避免重载层层委托（10 参一路加到 19 参），
     * 加能力不必改调用方，相邻同类型实参写反也能由类型拦住（见 ChatCommand 类注释）。
     * <p>{@code emitter} 刻意不进命令对象：它是这一轮的<b>出口</b>而不是内容——工作流子 agent 用
     * CollectingSseEmitter，定时任务与对外 MCP 端点各有自己的收集通道，与「问了什么」正交；
     * 混进同一个列表就又多一层重载。整条流水线在独立线程池执行（重活不占 Tomcat 请求线程）。
     */
    public void chat(ChatCommand cmd, SseEmitter emitter) {
        // 只解包这一段外壳真正要用的四个名字，其余字段由引擎整包收 cmd 后自取（见 ChatEngine#chat）
        final String sessionId = cmd.getSessionId();
        final String agentId = cmd.getAgentId();
        final String userId = cmd.getUserId();
        final boolean guestMode = cmd.isGuestMode();

        // 内容安全输入闸：命中 block 规则就不发起这一轮——一次模型调用都不花，
        // 用户当场看到「哪条规则拦的」。判据只有一处实现（ContentSafetyService），出口闸在模型出口
        // （MeteredModel / DynamicOpenAiChatModel），两道闸同一份规则表，与配额闸同一形态。
        String refused = contentSafety.checkInput(userId, sessionId, cmd.getQuestion());
        if (refused != null) {
            rejectTurn(emitter, cmd, sessionId, userId, null, refused);
            return;
        }

        // 会话轮级互斥：同一会话同时只允许一轮在跑。并发两问会历史交错、ArtifactService EMITTERS
        // 相互覆盖（后轮覆盖前轮，先完成者注销掉后轮的推送）、首问智能体绑定竞态。断线后台续跑
        // 与 askUser 等待期同样占坑——等待作答时再发新消息正是要挡的交错。限频/归属校验已在上游。
        if (!runtime.tryBeginTurn(sessionId)) {
            rejectTurn(emitter, cmd, sessionId, userId, null,
                    "该会话上一轮还在回答中，请等回答结束或先停止本轮，再发送新消息");
            return;
        }
        // 本轮身份在这一刻起可用（早于流状态构造）：账本的每条事件都挂得到同一个 turnId
        final String turnId = java.util.UUID.randomUUID().toString().replace("-", "");
        TurnRegistry.registerHandle(sessionId, new TurnHandle(turnId, userId, emitter));
        // 断开跟踪：登记查表项并绑定生命周期回调清理；发送失败也会打标（见 runtime.sendSseEvent），各等待点据此短路后续 LLM/检索开销
        runtime.trackChannel(emitter);
        // 运行总线登记：本轮的执行不再绑死在发起时那条通道上——刷新/换设备能接回来看它
        // 继续长出来，观众走光也只是「暂时没人看」，宽限（chat.detachGraceMs）到点才由总线回调按中断收束，
        // 走的还是既有半程截断落库那条路。刻意不登记：收集型通道（定时任务/MCP/工作流节点没有真实响应）、
        // 开关关闭（行为回到「断开即中止」）。
        // 游客分享会话<b>也登记</b>：归属用的是访客自己的身份（share-visitor:{随机}）
        // 而不是发布者——用发布者的 uid 挂归属，等于任何拿到分享链接的人都能接走别人会话的流。
        // 访客身份缺失（老链接没带 visitorId）就退回不登记，照旧按历史回显，绝不退成「按发布者放行」。
        final String runOwner = guestMode ? cmd.getGuestUid() : userId;
        if (chatRunBus.enabled() && runOwner != null && !runOwner.isBlank()
                && !(emitter instanceof CollectingSseEmitter)) {
            chatRunBus.open(sessionId, turnId, runOwner, emitter,
                    () -> resumeSnapshot(sessionId, turnId, cmd.getReceivedAtMs()),
                    () -> runtime.abortTurnWithoutViewer(sessionId),
                    () -> {
                        TurnHandle h = TurnRegistry.handle(sessionId);
                        return h != null && turnId.equals(h.turnId());
                    });
        }
        // 起表基准早帧：开流第一秒就把「这一轮从服务端收到提问起已经过了多久」交给前端。
        // 没有它，屏幕上的秒表只能从本地「按下回车」起表，收尾换成落库那份服务端墙钟时会
        // 掉一个网络往返。只给时长不给绝对时刻，
        // 两端钟差进不来。引擎开轮起表时还会再发一帧校准 prepMs（同一基准，读数不跳）。
        // 定时/目标/工作流没有请求体（receivedAtMs=0）：基准是引擎开轮时刻，由引擎自己发那一帧。
        if (cmd.getReceivedAtMs() > 0L && !(emitter instanceof CollectingSseEmitter)) {
            long elapsed = Math.max(0L, System.currentTimeMillis() - cmd.getReceivedAtMs());
            runtime.sendSseEvent(emitter, RunEventType.TURN_START.sseName(),
                    JSON.toJSONString(Map.of("wallMs", elapsed, "prepMs", elapsed)), sessionId);
        }
        // 整轮存活看门狗：emitter 无容器超时，截断由台账按机器耗时判定（人工等待不计入，见 TurnDeadline）。
        // 收集型通道（定时任务/MCP/子智能体节点）不挂：它没有真实响应、节奏由调用方 awaitDone 控制。
        TurnDeadline ledger = null;
        if (!(emitter instanceof CollectingSseEmitter)) {
            ledger = new TurnDeadline(runtime.turnMachineBudgetMs(), turnId);
            TurnRegistry.registerDeadline(emitter, ledger);
            runtime.startTurnWatchdog(emitter, sessionId, ledger);
        }
        final TurnDeadline deadline = ledger;
        emitter.onCompletion(() -> runtime.releaseSseChannel(emitter, deadline, sessionId));
        emitter.onTimeout(() -> runtime.releaseSseChannel(emitter, deadline, sessionId));
        emitter.onError(t -> runtime.releaseSseChannel(emitter, deadline, sessionId));
        syncPipelineSize();
        try {
            pipelineExecutor.execute(() -> {
                // 流水线线程**没有请求上下文**：检索的库门下推与文档可见性晚绑定
                // （HybridRetrievalService.buildAclFilter / loadVisibleDocIdsOfHits 读 RequestUser）
                // 会拿到 SYSTEM ⇒ 可见库集合为空/受限，配了共享范围（department/user）
                // 的文档被判为不可见并被过滤，表现就是"共享给我的资料，我在问答里检索不到"。
                // 这里按本轮 userId 从用户档案装载身份，让过滤按**真实用户**算（来源是用户表，
                // 与调用线程无关：网页问答的 Tomcat 线程、定时任务的池线程同一个来源）。
                boolean identity = loadIdentity(userId);
                // 个人配置覆盖（schema personal 字段）：随身份一次性载入，本轮内 configService.get 对
                // 标了 personal 的字段优先读个人值。当前 schema 里没有这类字段，
                // 这一层作为配置四档的通用机制保留，将来再有个人可覆盖项时不必重铺。
                // 与身份同生命周期：池化线程复用，finally 必须清（否则下一轮会带着上一轮用户的偏好）
                configService.putUserOverrides(userConfigService.overrides(userId));
                // 智能体检索开关覆盖（c_ai_agent.query_params）：本轮绑定的智能体只决定“这一轮怎么用检索”
                // （是否重排），阈值/权重归知识库、由检索层逐库生效，不在这里压成回合级标量。
                // 装在与个人覆盖同一段、同一生命周期——下面的 finally 早就在清它。
                // 优先级：显式覆盖(此处) > 个人覆盖 > 全局默认。
                loadAgentQueryOverrides(agentId);
                try {
                // 分派点刻意放在这一段：会话轮级互斥、turnId 登记、断线总线、看门狗、身份与个人覆盖
                // 装载都是「这一轮」的外壳，两种引擎都要；被替换的只是外壳里面那段推理循环本身。
                var dispatchChannel =
                        new com.wenqu.ai.engine.SseRunEventChannel(runtime, emitter, sessionId);
                // 摘把手 + 收总线 run 是两种引擎共同的收尾（此前只在框架引擎分支做，工作流轮跑完后
                // 把手挂在会话上——约 5 分钟后被看门狗补发一条假「已到整轮时间上限」、runStatus 长达
                // 一个宽限期报「在跑」），现在两个分支各自跑完都收，异常路径的 catch 也收。
                try {
                    // 单一问答路径：AgentScope 框架引擎。chatflow 绑定已退役，工作流只以「工具」或
                    // 自动化入口存在，不再接管某条智能体的整轮回答。
                    chatEngines.select(cmd).chat(cmd, dispatchChannel);
                    // 框架引擎同步跑完整轮：方法返回即本轮已终结（待办/计划批准期间引擎是在循环里
                    // 阻塞等待的，不会提前返回）。必须在这里摘轮次把手 + 收掉总线 run——总线 run 的
                    // turnAlive 判的是「TurnRegistry 里的把手是否还是本轮」，不摘就恒为真：会话被永久
                    // 当成「正在跑」，刷新/换设备接回来永远转圈，直到 300s 看门狗超时才收。
                    TurnRegistry.removeHandle(sessionId);
                    chatRunBus.finish(sessionId);
                } catch (Exception e) {
                    // 引擎会把可预期的失败收成 error+done 帧；走到这里的是它没接住的崩坏——
                    // 不收口的话用户对着输入框干转到 300s 看门狗兜底，把手与总线 run 也悬到宽限 sweep 才清。
                    log.error("[FAIL-LOUD] 本轮问答引擎执行异常: session={} turn={}", sessionId, turnId, e);
                    try {
                        dispatchChannel.emit(com.wenqu.ai.engine.RunEventType.ERROR,
                                "回答这一轮时服务内部出现异常，请重试；反复出现请换一个模型或新建会话");
                        dispatchChannel.emit(com.wenqu.ai.engine.RunEventType.DONE,
                                "{\"finalContent\":\"\",\"sources\":[],\"degradations\":[],\"engine\":\"agentscope\"}");
                    } catch (Exception notifyFailed) {
                        log.warn("[FAIL-LOUD] 异常收口帧没发出去（通道可能已被收）: session={}", sessionId);
                    }
                    TurnRegistry.removeHandle(sessionId);
                    chatRunBus.finish(sessionId);
                    runtime.completeEmitter(emitter);
                }
                } finally {
                    // 本轮那句失败原因落到消息行（正常轮取不到即返回）。发帧时的 completeEmitter 通常
                    // 已经落过，这一句只兜「通道没走到收尾就散了」那几条路——取走即清，两处都调不会写两遍
                    runtime.persistTurnError(emitter);
                    // 「这一问没有回答」的标记只能在本线程走到底时打——早一步判会抢在落库之前，
                    // 晚一步这里已经出不来了。断线宽限到点的中止也在这一并判：那条路径由总线线程
                    // 摘掉把手，只看 runtime.turnStopRequested 会漏打标记，用户下次进来读到的会是
                    // 「问出去了却什么都没有」的空档。
                    // 两种终止要分开写：用户点停止 ≠ 没人看超时——后者不是用户做的事，文案不许怪他。
                    // 显式停止必须排在最前：慢启动（沙盒装配卡住几分钟）时用户先点了停止，随后
                    // 宽限到点的「无观众中断」也会登记一笔——先取 bareAbort 就会把用户的动作记成
                    // 系统中断。
                    final boolean userStopped = runtime.takeStopMark(sessionId, turnId);
                    final boolean bareAbort = runtime.takeBareAbort(sessionId, turnId);
                    if (userStopped) {
                        sessionService.markTurnStopped(sessionId, "stopped");
                    } else if (bareAbort) {
                        sessionService.markTurnStopped(sessionId, "interrupted");
                    } else if (runtime.turnStopRequested(sessionId)) {
                        // 只剩「把手还挂着」的路径（如收集型通道）靠它判：stopMark 已在上面取过，
                        // 两种桥同一语义（用户点停）
                        sessionService.markTurnStopped(sessionId, "stopped");
                    }
                    // 顶层轮结束（正常/停止/异常）：这一轮派出去、还挂在审批或执行上的子分支同步收口。
                    // 分支轮本身不走这里（parentSessionId 非空），否则会把自己也停掉。
                    if (cmd.getParentSessionId() == null || cmd.getParentSessionId().isBlank()) {
                        try {
                            branchRuntime.cancelAll(sessionId);
                        } catch (Exception e) {
                            log.warn("[SUBAGENT] 父轮结束收分支失败 session={}: {}", sessionId, e.getMessage());
                        }
                    }
                    runtime.endTurnMutex(sessionId);
                    if (identity) com.wenqu.ai.util.RequestUser.clear();
                    // 智能体检索开关的作用域覆盖随本轮结束清除（ThreadLocal，池化线程复用必须清，
                    // 否则下一轮请求会继承上一轮智能体的检索开关）
                    configService.clearOverride();
                    configService.clearUserOverrides();
                }
            });
        } catch (RejectedExecutionException e) {
            // 任务未入队即拒绝：轮级互斥标记须同步放掉，否则该会话从此被永久挡在门外
            TurnRegistry.removeHandle(sessionId);
            runtime.endTurnMutex(sessionId);
            // L7 fail-loud：繁忙拒绝时告知当前队列长度（用户可感知拥堵程度）
            int queued = pipelineExecutor == null ? 0 : pipelineExecutor.getQueue().size();
            log.warn("[FAIL-LOUD] 问答流水线繁忙，拒绝请求（排队 {}）: session={}", queued, sessionId);
            rejectTurn(emitter, cmd, sessionId, userId, turnId,
                    "系统繁忙（当前排队 " + queued + " 个请求），请稍后重试");
        }
    }


    /**
     * 这一轮<b>不发起</b>就收束：模型不可用、未指定模型、同会话并发被拒、流水线繁忙——
     * 都是「一个字还没送出去就被挡下」。三件事必须一起做，少一件界面上就缺一块：
     * <ol><li><b>提问先落库</b>：不落的后果是刷新一次连「我问过什么」都读不出来，侧栏只剩标题，
     * 用户读到的是「软件吞了我一句话」；</li>
     * <li>error 帧带<b>显式</b>轮次身份记账本：这几句都发在把手登记之外（并发那一条会话上挂的还是
     * <b>另一轮</b>的把手），照把手记账会把拒绝塞进正在跑的那一轮的过程里，不记就成了谁都查不到的孤儿行；</li>
     * <li>收尾把那句原因落到消息行上（{@code completeEmitter} 顺带做），刷新后错误卡还在。</li></ol>
     *
     * @param turnId 本轮身份；并发被拒那一条此刻还没生成，传 null 就地补一颗
     */
    private void rejectTurn(SseEmitter emitter, ChatCommand cmd, String sessionId, String userId,
                            String turnId, String message) {
        final String tid = turnId == null || turnId.isBlank()
                ? java.util.UUID.randomUUID().toString().replace("-", "") : turnId;
        appendRejectedQuestion(cmd, sessionId, tid);
        log.warn("[FAIL-LOUD] 本轮不发起: session={} turn={} {}", sessionId, tid, message);
        runtime.sendSseEvent(emitter, "error", message, sessionId, userId, tid);
        runtime.completeEmitter(emitter);
    }

    /**
     * 被挡下那一问的落库：与引擎/工作流那两处同一个形状（附件、@ 引用、# 历史引用、图片都带上）。
     * <p>图片走 {@code processDisplay}：本轮压根没跑起来，谈不上「模型能不能读图」，落盘回显就够了。
     */
    private void appendRejectedQuestion(ChatCommand cmd, String sessionId, String turnId) {
        // 重新生成/插话的提问在上一句请求里已经落过，再写一条就是同一问问两遍
        if (cmd.isRegenerate() || cmd.isFromSteer()) return;
        if (cmd.getQuestion() == null || cmd.getQuestion().isBlank()) return;
        try {
            List<String> urls = userImageService.processDisplay(cmd.getUserImages()).stream()
                    .map(UserImageService.UserImage::url).toList();
            List<ChatRequest.HistoryRef> historyRefs = cmd.getHistoryRefs();
            sessionService.appendMessage(MessageWrite.builder()
                    .sessionId(sessionId)
                    .role("user")
                    .content(cmd.getQuestion())
                    .images(urls.isEmpty() ? null : urls)
                    .attachments(chatAttachments.metasJson(cmd.getAttachments(), cmd.uploadOwnerUid()))
                    .mentions(cmd.getMentions() == null || cmd.getMentions().isEmpty()
                            ? null : com.alibaba.fastjson2.JSON.toJSONString(cmd.getMentions()))
                    .historyRefs(historyRefs == null || historyRefs.isEmpty()
                            ? null : com.alibaba.fastjson2.JSON.toJSONString(historyRefs))
                    .turnId(turnId)
                    .build());
        } catch (Exception e) {
            // 问没留住不碍「这一轮被挡下」这件事本身：帧照发、日志照记，只是刷新后读不回这一句
            log.warn("[FAIL-LOUD] 被挡下的提问落库失败: session={} {}", sessionId, e.getMessage());
        }
    }


    /**
     * 同步预检（HTTP 400）就把这一问挡下时的留痕：400 照旧由调用方抛，但那一问要留在对话里并带上原因。
     * <p>与 {@code rejectTurn} 同一条口径，区别只是这条路没有 SSE 通道可发帧——前端拿 400 的 msg
     * 当场就弹得出错误卡，缺的是「刷一下页面还在」。不补这一笔，用户读到的就是「软件吞了我一句话」。
     * <p>请求体在这里按同一形状拼一份最小命令对象：预检排在命令装配之前，不为一条留痕去挪校验顺序。
     */
    public void recordRejectedQuestion(String sessionId, String userId, ChatRequest request, String message) {
        final String tid = java.util.UUID.randomUUID().toString().replace("-", "");
        appendRejectedQuestion(ChatCommand.builder()
                .sessionId(sessionId).userId(userId)
                .question(request.getQuestion())
                .userImages(request.getImages())
                .attachments(request.getAttachments())
                .mentions(request.getMentions())
                .historyRefs(request.getHistoryRefs())
                .regenerate(request.isRegenerate())
                .build(), sessionId, tid);
        sessionEventService.append(sessionId, userId, tid, "error", message);
        sessionService.saveTurnFailure(sessionId, tid, message);
    }


    // ==================== 沙盒脚本的子调用回程 ====================



    // ---- 智能体本轮作答方解析 ----

    /**
     * 本轮作答的智能体：请求带 id 就用它（分享页 / 定时任务 / 工作流 / 对外 MCP 都是显式指定），
     * 不带就用默认智能体（isDefault=1）；未设默认才落到纯系统设置的全局配置。
     * <p>这里<b>不做"会话锁定"</b>：
     * 资料域由模型经派遣助手（框架的 agent_spawn）主动派出去查，作答方不需要被锁，也没有"换人请新建会话"这回事。
     * <p>显式指定却解析不到（已删除或无权限）时 fail-loud：静默改用全局配置等于悄悄换人，用户无从察觉。
     */
    private Agent resolveTurnAgent(String requestedAgentId,
                                   List<Map<String, String>> degradations,
                                   Set<String> degradedCodes) {
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            return agentService.defaultAgent();
        }
        Agent picked = agentService.get(requestedAgentId);
        if (picked == null) {
            runtime.addDegradation(degradations, degradedCodes, "agentMissing",
                    "所选智能体不可用（已删除或你无访问权限），本轮按全局配置回答");
            log.warn("[AGENT] 请求的智能体 {} 不可解析，本轮按全局配置回答", requestedAgentId);
        }
        return picked;
    }

    /**
     * §4 轮级委派下发：{id,name,description}。用户主动 @ 的归属变化要在当轮看得见（气泡上「由 X 作答本轮」），
     * 不能像系统侧的检索细节那样归入排障开关——那是机器行为，这是用户指令。
     */
    private void emitAgentDelegated(SseEmitter emitter, Agent delegate, String sessionId) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", delegate.getId());
            info.put("name", delegate.getName());
            info.put("description", delegate.getDescription());
            runtime.sendSseEvent(emitter, "agent_delegated", JSON.toJSONString(info), sessionId);
        } catch (Exception e) {
            log.debug("[AGENT] 委派事件下发失败（不影响问答）: {}", e.getMessage());
        }
    }

    /**
     * §4 会话内 @ 智能体：从本轮 mentions 解析轮级委派的智能体（type=agent，取第一个）。
     * <p>控制器同步段已做存在性/可读性/子智能体校验；流水线异步线程仍按同一信任边界走
     * {@code agentService.get} 重新解析实体（与 kb/doc 引用同口径，不信任请求直达数据）——
     * 校验通过后至流水线执行之间被删除/改权限的罕见竞态按降级处理：回落会话绑定智能体，
     * 不终止本轮（会话智能体还能答，损失只是"委派没生效"且用户看得见原因）。
     * 多个 agent 提及：取第一个，其余登记降级提示（答一轮的语义只有单主语）。
     */
    private Agent resolveDelegatedAgent(List<ChatRequest.Mention> mentions,
                                        List<Map<String, String>> degradations, Set<String> degradedCodes) {
        if (mentions == null || mentions.isEmpty()) return null;
        Agent first = null;
        boolean hasMore = false;
        for (ChatRequest.Mention m : mentions) {
            if (m == null || !"agent".equals(m.getType()) || m.getId() == null || m.getId().isBlank()) continue;
            if (first != null) {
                hasMore = true;
                continue;
            }
            Agent a = agentService.get(m.getId());
            if (a == null) {
                String shown = (m.getName() == null || m.getName().isBlank()) ? m.getId() : m.getName();
                runtime.addDegradation(degradations, degradedCodes, "agentMentionUnavailable",
                        "你 @ 的智能体「" + shown + "」已不可访问（被删除或权限变更），本轮由会话绑定的智能体回答");
                log.warn("[AGENT] @ 提及的智能体 {} 不可解析，本轮回落会话绑定", m.getId());
            } else {
                first = a;
            }
        }
        if (first != null && hasMore) {
            runtime.addDegradation(degradations, degradedCodes, "agentMentionMultiple",
                    "一次只能由一个智能体作答：已取第一个 @ 的「" + first.getName() + "」，其余提及本轮忽略");
        }
        return first;
    }

    /**
     * 模型解析链（优先级从高到低）：会话级覆盖（聊天页手动切换）> 用户个人默认模型。
     * 值为引用（providerId/modelId）或遗留纯模型名均可，供应商网关路由由 DynamicOpenAiChatModel 按引用解析；
     * 智能体不绑定聊天模型，也没有全局兜底——均未配置时返回空串，由调用方 fail-loud 引导配置。
     */
    private String resolveModel(String modelOverride, com.wenqu.ai.model.User prefUser) {
        if (modelOverride != null && !modelOverride.isBlank()) return modelOverride.trim();
        if (prefUser != null && prefUser.getDefaultModel() != null && !prefUser.getDefaultModel().isBlank()) {
            return prefUser.getDefaultModel();
        }
        return "";
    }

    /**
     * 本轮该用哪个模型引用：请求覆盖 &gt; 会话固定 &gt; 个人默认；返回空串＝全链未配置（调用方 fail-loud）。
     * <p>公开是因为并列的第二套运行时（AgentScope 框架引擎）必须问得到<b>同一条</b>解析链：
     * 它不能只拿「请求里的 model」——用户靠个人默认作答时，框架侧同样要靠这条链解析路由。
     */
    public String modelRefFor(String modelOverride, String sessionId, com.wenqu.ai.model.User prefUser) {
        String effective = modelOverride != null && !modelOverride.isBlank()
                ? modelOverride.trim() : sessionService.sessionModelOf(sessionId);
        return resolveModel(effective, prefUser);
    }

    /** 同上，但从这一轮的命令自己算（框架引擎按轮问一次）。 */
    public String modelRefFor(ChatCommand cmd) {
        return modelRefFor(cmd.getModelOverride(), cmd.getSessionId(), loadPrefUser(cmd.getUserId()));
    }

    /**
     * 本轮生效模型，外加「这一轮该对用户交代什么」。解析次序与 {@link #modelRefFor} 完全相同
     * （请求覆盖 &gt; 会话固定 &gt; 个人默认），差别是撞上已停用的模型时不再把坏引用发给上游。
     * <ul>
     *   <li><b>会话固定失效</b>：清掉这一条会话的选择、回落个人默认，并把改动说出来。会话里钉着的
     *       是某次点选留下的串，用户早就不记得它，留着只会每轮撞一次上游报错。</li>
     *   <li><b>请求显式携带的失效</b>：不动任何已存的选择、也不换模型——那是用户这一轮点名的，
     *       替他猜等于改他的指令。</li>
     *   <li><b>个人默认失效</b>：同样不自动改（那是他显式设的），只报错并说清去哪儿改。</li>
     * </ul>
     *
     * @param ref             本轮该用的引用（空=不能用）
     * @param fallbackNotice  非空=本轮改动了用户存过的选择，必须留一行可见记录给他
     * @param unusableMessage 非空=这一轮不该发起，调用方按 fail-loud 出错并原样说出这句话
     */
    public record TurnModel(String ref, String fallbackNotice, String unusableMessage) {
        public boolean usable() {
            return unusableMessage == null && ref != null && !ref.isBlank();
        }
    }

    /**
     * 本轮模型解析入口（两条运行时按轮各问一次）。解析交给 {@link #doResolveTurnModel}；
     * 解析出可用引用时顺手把它补记为会话的模型（{@link SessionService#recordTurnModel}）——
     * 会话行上的 model 从此兼有"上一轮实际生效"的语义，右下角选择器隔天回显的就是它，
     * 下一轮解析也优先钉在它上；补记是 best-effort，写失败不影响本轮。
     */
    public TurnModel resolveTurnModel(ChatCommand cmd) {
        TurnModel turn = doResolveTurnModel(cmd);
        if (turn.usable()) sessionService.recordTurnModel(cmd.getSessionId(), turn.ref());
        return turn;
    }

    private TurnModel doResolveTurnModel(ChatCommand cmd) {
        String uid = cmd.getUserId();
        com.wenqu.ai.model.User prefUser = loadPrefUser(uid);
        // 归属这条判据只在「问的就是请求者自己能不能用这个供应商」时才成立：游客对话用的是分享主登记的
        // 模型、SYSTEM 身份的会话没有可判归属的用户，按他们的身份判必然判成「不属于你」，于是把好用的模型判死
        boolean judgeOwnership = !cmd.isGuestMode() && uid != null && !uid.isBlank()
                && !com.wenqu.ai.util.RequestUser.SYSTEM.equals(uid);
        String role = prefUser != null ? userRoleService.primaryRole(prefUser.getUid())
                : com.wenqu.ai.util.RequestUser.role();
        java.util.function.Function<String, com.wenqu.ai.service.ModelRegistryService.Usability> probe =
                ref -> modelRegistryService.referenceUsability(ref, uid, role, judgeOwnership);
        java.util.function.Function<String, String> why =
                ref -> modelRegistryService.issueMessage(probe.apply(ref));

        String override = cmd.getModelOverride() == null ? "" : cmd.getModelOverride().trim();
        String personal = prefUser == null || prefUser.getDefaultModel() == null
                ? "" : prefUser.getDefaultModel().trim();
        if (!override.isEmpty()) {
            var u = probe.apply(override);
            return u.usable() ? new TurnModel(override, null, null)
                    : new TurnModel("", null, "你选的模型" + why.apply(override)
                            + "，这一轮没有作答。请在下方重新选一个模型。");
        }
        var pin = sessionService.sessionPinOf(cmd.getSessionId());
        // 访客不认「会话钉选」：访客会话那一列是每轮 recordTurnModel 顺手写进去的机器副作用，访客从头到尾
        // 没做过任何模型决定。认了它，发布者把对话模型切回「跟随发布者个人默认模型」之后，老访客会话会一直
        // 钉在被换掉的那个模型上——而这一档要跟随的偏偏是发布者的默认，不是「这个访客第一次用了谁」。
        // （顺带：下面那行清钉选的 updateModel 按发布者 uid 校验归属，访客会话不属他 ⇒ 钉住的模型一失效就 403）
        if (!cmd.isGuestMode() && !pin.model().isEmpty()) {
            var u = probe.apply(pin.model());
            if (u.usable()) return new TurnModel(pin.model(), null, null);
            sessionService.updateModel(uid, cmd.getSessionId(), "");
            // 还没有消息的行上，这个失效值是「新建对话」复用空会话留下的历史脏值：这一轮用户什么都没
            // 选过，静默清掉就好。弹一条「已清除你上次选的东西」会让人以为自己对这条新对话做过决定
            String gone = pin.empty() ? ""
                    : "这个会话上次用的模型" + why.apply(pin.model()) + "，已清除这个会话的选择";
            if (personal.isEmpty()) {
                return new TurnModel("", null, gone.isEmpty()
                        ? "未指定模型：请在对话右上角选择模型，或在个人设置中配置默认模型"
                        : gone + "。请选一个模型作答，或在个人设置里设一个默认模型。");
            }
            var pu = probe.apply(personal);
            if (!pu.usable()) {
                String defaultDead = "你的默认模型" + why.apply(personal)
                        + "。请在个人设置里改一次，或直接在这一轮选一个模型。";
                return new TurnModel("", null, gone.isEmpty() ? defaultDead : gone + "；而" + defaultDead);
            }
            return new TurnModel(personal, gone.isEmpty() ? null
                    : gone + "，本轮改用你的默认模型「" + pu.label() + "」回答。", null);
        }
        if (personal.isEmpty()) return new TurnModel("", null, null);
        var pu = probe.apply(personal);
        return pu.usable() ? new TurnModel(personal, null, null)
                : new TurnModel("", null, "你的默认模型" + why.apply(personal)
                        + "，这一轮没有作答。请在个人设置里改一次，或直接在这一轮选一个模型。");
    }

    /** 个人偏好用户行（含三类个人默认模型）；SYSTEM/空 uid/查询失败返回 null（全部走空语义） */
    /**
     * 在流水线线程内装载本轮用户身份（uid / 部门 / 角色），供检索的文档可见性过滤按**真实用户**判定。
     * <p>背景：问答流水线跑在独立线程池、没有请求上下文，而 {@code HybridRetrievalService} 的
     * 库门下推（{@code loadVisibleKbIds}）与文档可见性晚绑定（{@code loadVisibleDocIdsOfHits}）
     * 都从 {@code RequestUser} 取身份 ⇒ 只会拿到 SYSTEM。其后果是：配了共享范围
     * （{@code access_level=department/user}）的文档，对**包括被授权者在内**的所有人都判为不可见并被过滤——
     * 也就是"共享给我、或我自己限定范围的资料，在问答里检索不到"。
     * <p>身份从用户档案读，与调用线程无关：网页问答（Tomcat 线程）与定时任务（池线程，uid 由参数传入）
     * 走同一来源；代价是每轮多一次用户表主键查询（与 runChat 内取个人偏好那次同表主键查询同一量级）。
     *
     * @return 是否已装载（未装载时调用方不需要清理）
     */
    private boolean loadIdentity(String userId) {
        if (userId == null || userId.isBlank()
                || com.wenqu.ai.util.RequestUser.SYSTEM.equals(userId)) {
            // SYSTEM / 空 uid 没有可装载的用户档案，保持无身份
            return false;
        }
        try {
            com.wenqu.ai.model.User u = userMapper.selectById(userId);
            if (u == null) return false;
            com.wenqu.ai.util.RequestUser.set(u.getUid(), u.getDepartmentId(), userRoleService.rolesOf(u.getUid()));
            return true;
        } catch (Exception e) {
            // 装载失败按 SYSTEM 继续（不打断问答），但必须留痕：否则又变成"共享资料检索不到"且无迹可查
            log.warn("[FAIL-LOUD] 流水线线程装载用户身份失败（本轮检索可见性按 SYSTEM 判定）uid={}: {}",
                    userId, e.getMessage());
            return false;
        }
    }

    private com.wenqu.ai.model.User loadPrefUser(String userId) {
        if (userId == null || userId.isBlank() || com.wenqu.ai.util.RequestUser.SYSTEM.equals(userId)) {
            return null;
        }
        try {
            return userMapper.selectById(userId);
        } catch (Exception e) {
            log.debug("[PREF] 个人偏好加载失败（视为未配置）: {}", e.getMessage());
            return null;
        }
    }


    // ==================== 子智能体真全循环 ====================


    /**
     * 非问答轮（手动压缩 /compact）的生效模型解析：显式指定 &gt; 个人默认；返回空串表示未配置，
     * 由调用方 fail-loud 引导用户选模型（与问答轮同一解析顺序，避免两条路走进不同模型）
     */
    public String resolveChatModel(String userId, String modelOverride) {
        return resolveModel(modelOverride, loadPrefUser(userId));
    }


    // ==================== 断线重连接流 ====================

    /**
     * 接流快照：从本轮的权威缓冲重建「到此刻为止看得见的东西」。
     * <p>这些缓冲本来就是 done 落库与实时流的同源数据，所以接流方拿到的形状与刷新后回看历史一致。
     * <p>不含：待裁决卡片（提问/计划/审批）——它们的时限与答案记录以 c_ai_tool_approval 为准，
     * 前端走既有的 {@code /ask-user/pending}、{@code /plan-approval/pending} 恢复入口，
     * 那里才有剩余的等待毫秒数与「唤醒句柄是否还在」（进程重启过就答不进去）；逐字增量事件也不含，
     * 正文取缓冲本身比回放上千条 token 事件快得多。
     */
    private Map<String, Object> resumeSnapshot(String sessionId, String turnId, long receivedAtMs) {
        Map<String, Object> snap = new LinkedHashMap<>();
        TurnHandle h = TurnRegistry.handle(sessionId);
        // stopped 与「把手已摘」同列：用户已经点过停止的这一轮不再是「在跑的轮」。装配期（沙盒要
        // 几十秒到几分钟）线程还卡在 sessions.open，把手与总线 run 都还在，若不在这里判 stopped，
        // 刷新就会把刚停掉的那一轮接回来继续显示「正在溯源」，直到装配超时才收。
        if (h == null || !turnId.equals(h.turnId()) || h.stopped().get()) {
            snap.put("running", false);
            return snap;
        }
        // 框架运行时没有问渠侧的状态袋：前置阶段（检索/思考）与生成中共用「pre」快照，
        // 阶段文案、计划步骤与任务清单由总线镜像在 snapshot.events 里，前端重放即可复现界面
        snap.put("running", true);
        snap.put("phase", "pre");
        // 这一拍引擎可能还没开轮（限流/校验/会话补建/实例装配要几秒，快照仍由外壳这份顶着）：
        // 不给「已跑多久」，接流方只能从刷新那一刻重新起跳，已经跑掉的准备时间在用户眼里不算数
        // （症状：刷新后「正在溯源，用时 0 秒」重新数）。按服务端收到提问起表给时长——与 done、
        // 落库 tokens.turnWallMs 同一个口径，刷新前后才接得上。
        // prepMs 此刻把已过去的全部算作准备：这一拍确实还没有开轮，事实如此；引擎开出自己的
        // 快照供给后由那份给准确值，收尾 done 再以最终值覆盖（只影响跑动中悬停的拆分）。
        if (receivedAtMs > 0L) {
            long wallMs = Math.max(0L, System.currentTimeMillis() - receivedAtMs);
            snap.put("wallMs", wallMs);
            snap.put("prepMs", wallMs);
        }
        return snap;
    }

    /**
     * 会话事件账本的<b>分轮回放</b>（{@code GET /session/{id}/events} 的实现）。
     * <p>按会话取最近 200 条（写入序倒序）→ 按 turnId 分组（最新轮在前）→ 组内翻回发生顺序。
     * <p>带 {@code turn} 时改为<b>只看那一轮</b>（逐气泡「过程」的取数，按消息行的 turnId 精确取，正序）：
     * 会话级列表回答「这几轮跑了什么」，按轮回答「这一条回答怎么跑出来的」。
     * 载荷只给 240 字摘要：这一份是给人回看「这一轮怎么跑出来的」，不是把原文再存一遍——
     * 完整工具输出走 {@link ToolSpillService} 那套按需读回。
     * <p>账本不参与模型输入（刻意边界，见 {@link SessionEventService}），读口也不改变这一点。
     */
    public Map<String, Object> listSessionEvents(String sessionId, String uid, Integer turns, String turn) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 按轮回放（逐气泡「过程」）：消息行带着 turnId，直接取那一轮、按发生顺序返回，
        // 不必先扫会话最近 200 条再分组——用户点的是这一条回答，不是整个会话
        if (turn != null && !turn.isBlank()) {
            java.util.List<Map<String, Object>> events = new java.util.ArrayList<>();
            for (com.wenqu.ai.model.SessionEvent ev : sessionEventService.listByTurn(sessionId, uid, turn, 200)) {
                events.add(eventItem(ev));
            }
            out.put("turn", turn);
            out.put("turns", events.isEmpty() ? List.of()
                    : List.of(turnGroup(turn, events)));
            out.put("scanned", events.size());
            return out;
        }
        int wantTurns = turns == null || turns <= 0 ? 5 : Math.min(turns, 20);
        java.util.List<com.wenqu.ai.model.SessionEvent> rows = sessionEventService.listRecent(sessionId, uid, 200);
        java.util.LinkedHashMap<String, java.util.List<Map<String, Object>>> groups = new java.util.LinkedHashMap<>();
        for (com.wenqu.ai.model.SessionEvent ev : rows) {
            String tid = ev.getTurnId() == null ? "" : ev.getTurnId();
            groups.computeIfAbsent(tid, k -> new java.util.ArrayList<>()).add(eventItem(ev));
        }
        java.util.List<Map<String, Object>> turnsOut = new java.util.ArrayList<>();
        for (var g : groups.entrySet()) {
            java.util.Collections.reverse(g.getValue());   // 组内回到发生顺序
            turnsOut.add(turnGroup(g.getKey(), g.getValue()));
            if (turnsOut.size() >= wantTurns) break;
        }
        out.put("turns", turnsOut);
        out.put("scanned", rows.size());
        return out;
    }

    /** 账本行 → 回放条目（id/类型/时间 + ≤240 字摘要） */
    private Map<String, Object> eventItem(com.wenqu.ai.model.SessionEvent ev) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", ev.getId());
        item.put("type", ev.getType());
        item.put("at", ev.getCreatedAt() == null ? 0L
                : ev.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        String p = ev.getPayload() == null ? "" : ev.getPayload();
        item.put("summary", p.length() > 240 ? p.substring(0, 240) + "…" : p);
        // 工具行单独把「第几步、成没成、多久」拆出来：对照视图要靠它与回答里的工具卡对上号，
        // 而摘要可能被 240 字正好切在 status 之前——那种情况下前端数不出第几步
        if ("tool".equals(ev.getType())) {
            try {
                com.alibaba.fastjson2.JSONObject o = com.alibaba.fastjson2.JSON.parseObject(p);
                if (o != null) {
                    item.put("name", o.getString("name"));
                    item.put("label", o.getString("label"));
                    item.put("hint", o.getString("hint"));
                    item.put("status", o.getString("status"));
                    if (o.get("elapsedMs") != null) item.put("elapsedMs", o.get("elapsedMs"));
                    if (o.get("stepId") != null) item.put("stepId", o.get("stepId"));
                    if (o.get("startedAtMs") != null) item.put("startedAtMs", o.get("startedAtMs"));
                    if (o.get("waitedMs") != null) item.put("waitedMs", o.get("waitedMs"));
                    // 入参与输出只给摘要：完整那份在消息表的工具卡里，回放不需要再存一遍。
                    // 结构化返回（而不是让前端从 240 字 summary 里反解），字段才不会被截断切掉。
                    // 只带完整且够短的入参：半截 JSON 前端解不出摘要，反而会把原文当「参数」铺出来
                    String args = o.getString("args");
                    if (args != null && !args.isBlank() && args.length() <= 200) {
                        item.put("argsBrief", args);
                    }
                    String result = o.getString("result");
                    if (result != null && !result.isBlank()) {
                        item.put("resultBrief", result.length() > 240 ? result.substring(0, 240) + "…" : result);
                    }
                }
            } catch (Exception ignore) {
                // 载荷不是 JSON（历史行或被截断）：照旧只给摘要，对照视图少一个编号而已
            }
        }
        // 用量/检索是「累计快照」不是单次调用：把整份数值带出来，前端才能按最新态合并、
        // 展开时列逐帧；不再让面板从 240 字摘要里猜。
        if ("usage".equals(ev.getType()) || "retrieved".equals(ev.getType())) {
            try {
                com.alibaba.fastjson2.JSONObject o = com.alibaba.fastjson2.JSON.parseObject(p);
                if (o != null) {
                    item.put("usage".equals(ev.getType()) ? "usage" : "retrieval", o);
                }
            } catch (Exception ignore) {
                // 历史行截断/非 JSON：退回 summary，界面仍能显示一行原始文本
            }
        }
        // 其余卡片类事件给小对象（有上限）：产物、提问、审批、计划、派遣路由。
        // 上限内才解析，避免把一份大计划整段塞进回放响应。
        if (p.length() <= 1500 && LEDGER_OBJECT_EVENT_TYPES.contains(ev.getType())) {
            try {
                Object o = com.alibaba.fastjson2.JSON.parse(p);
                if (o != null) item.put("data", o);
            } catch (Exception ignore) {
                // 非 JSON：保留 summary 原文
            }
        }
        // 子智能体运行过程：摘要是给人看的一行话；完整帧流整份带上——
        // 派遣卡上的「完整过程」回放就是从这里懒取的，别的类型不带全量（账本不是原文复读机）
        if ("subagent".equals(ev.getType())) {
            try {
                com.alibaba.fastjson2.JSONObject o = com.alibaba.fastjson2.JSON.parseObject(p);
                if (o != null) {
                    String name = o.getString("agentName");
                    String st = o.getString("status");
                    long ms = o.getLongValue("elapsedMs");
                    int n = o.getJSONArray("frames") == null ? 0 : o.getJSONArray("frames").size();
                    item.put("summary", "子智能体运行 · " + (name == null ? "未知助手" : name)
                            + " · " + ("done".equals(st) ? "完成" : ("timeout".equals(st) ? "超时" : "失败"))
                            + " · " + (ms < 1000 ? ms + "ms" : String.format("%.1fs", ms / 1000.0))
                            + " · " + n + " 帧"
                            + (o.getString("error") == null || o.getString("error").isBlank() ? "" : " · " + o.getString("error")));
                    item.put("agentName", name);
                    item.put("status", st);
                    item.put("elapsedMs", ms);
                    item.put("frameCount", n);
                    item.put("payload", p);
                }
            } catch (Exception ignore) {
                // 载荷解析不出（被截断的历史行）：照旧只给原始摘要
            }
        }
        return item;
    }

    /** 一组事件 → 一轮（起止时间取首末事件） */
    private Map<String, Object> turnGroup(String turnId, java.util.List<Map<String, Object>> events) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("turnId", turnId);
        t.put("startedAt", events.isEmpty() ? 0L : events.get(0).get("at"));
        t.put("endedAt", events.isEmpty() ? 0L : events.get(events.size() - 1).get("at"));
        t.put("count", events.size());
        t.put("events", events);
        return t;
    }


}
