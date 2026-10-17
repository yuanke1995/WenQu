package com.wenqu.ai.service;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.TurnDeadline;
import com.wenqu.ai.agent.chat.TurnHandle;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.KnowledgeBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 问答运行时门面（P6 刀 5d 由 {@code RagService} 更名）：一轮问答的<b>外壳</b>都在这里——
 * 会话轮级互斥、断线续跑总线、身份/偏好装载、分派（工作流旁路 / AgentScope 框架引擎）。
 * <p>推理循环、工具派发、上下文压缩、计划工具面已全部由 AgentScope 框架引擎（{@code agentx} 包）
 * 原生接管（2026-10-15 单引擎化收口）；本类不再持有任何生成侧状态，只保留两件框架不提供的事：
 * ① 轮级外壳（互斥、把手、看门狗、断线接流，经 {@link ChatRunRuntime}）；
 * ② 工作流旁路（{@code ChatflowBridge}）——绑定工作流的智能体由 end 节点出参当回答。
 * <p>检索执行在 {@code HybridRetrievalService}/{@code RerankService}，卡片在
 * {@code HitlDecisionService}，会话状态在框架侧——本类只回答「这一轮谁来做、从哪条路做」。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ChatService {

    /**
     * 智能体/知识库可覆盖的检索参数白名单（只有这些键生效，防止越权改其他配置）；
     * 与设置页「检索设置 / 重排服务」暴露的项保持一致。
     * <p>public：知识库新建表单的"默认值模板"端点需要按同一白名单取全局值（见 KnowledgeBaseController）。
     */
    public static final Set<String> AGENT_QUERY_PARAM_KEYS = Set.of(
            "retrieval.vectorWeight", "retrieval.keywordWeight", "retrieval.vecThreshold",
            "retrieval.vectorTopK", "retrieval.keywordLimit",
            "rerank.enabled", "rerank.model", "rerank.baseUrl");


    /**
     * 本轮检索参数涉及的库：kbIds 非空按其逐个取库；为空（不限库）取全部未删除库
     * （{@code KnowledgeBaseService.list()} 已按默认库优先、创建时间升序，与"全库检索"的范围一致）。
     */
    private List<KnowledgeBase> resolveQueryParamKbs(java.util.Collection<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return knowledgeBaseService.list();
        List<KnowledgeBase> out = new ArrayList<>();
        for (String kbId : kbIds) {
            KnowledgeBase kb = knowledgeBaseService.get(kbId);
            if (kb != null) out.add(kb);
        }
        return out;
    }

    /**
     * 解析检索参数 JSON → 覆盖表；**白名单之外的键一律丢弃**（防止借参数覆盖改到无关配置），
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
                    log.warn("[AGENT] 检索参数键不在白名单内，已忽略: {}", e.getKey());
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


    private final SessionService sessionService;
    private final ConfigService configService;
    /** 附件元信息落库的共用口径（工作流路径用；与框架引擎同一个方法，见 metasJson 的类注释） */
    private final ChatAttachmentService chatAttachments;
    /** 知识库精确检索工具（Function Calling；由 tool.* 配置开关控制，默认开启） */
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;
    /** MCP 客户端服务（外部 MCP server 工具接入；按用户隔离连接池，各人连各人的服务） */

    /** 知识库服务：解析「智能体关联的知识库 → 允许检索的文档集合」，检索按库隔离 */
    private final KnowledgeBaseService knowledgeBaseService;


    /** 用户表（个人默认模型解析） */
    private final com.wenqu.ai.mapper.UserMapper userMapper;
    /** 产物交付工具（Function Calling；生成文件并实时推送） */
    /** 内置高频工具（计算/当前时间/日期差等，tool.builtin.enabled 控制，默认开） */
    private final BuiltinTools builtinTools;
    /** 联网搜索工具（webSearch.enabled 控制，默认关；结果注册进引用体系，与知识库来源同编号） */
    /** 沙盒工具（execute / read_file / write_file / ls；tool.sandbox.enabled 控制，默认关，依赖 provisioner 服务） */
    /** 个人配置覆盖（个人设置 → 对话偏好）：流水线线程装载 ThreadLocal，本轮 configService.get 优先个人值 */
    private final UserConfigService userConfigService;
    /** 智能体配置（4.1）：对话页下拉选中后，按智能体覆盖模型/提示词/工具/知识库范围 */
    private final AgentService agentService;
    private final ModelRegistryService modelRegistryService;
    /**
     * M4：工作流服务（智能体绑定工作流时取用）。走 ObjectProvider <b>惰性</b>获取而不是直接注入：
     * {@code WorkflowService → WorkflowEngine → ChatService} 已是一条依赖链（子智能体节点复用问答管线），
     * 这里再直接注入就成环（Spring Boot 3 默认禁止循环依赖，启动直接失败）。惰性取用把这个
     * 「只有绑定了工作流的智能体才用得到」的反向依赖推迟到调用时，依赖图保持单向。
     */

    /** 会话事件账本：结构性事件按写入顺序留痕（工具/人在回路/编排/轮终态/停止）。旁路语义，不进模型输入 */
    private final SessionEventService sessionEventService;
    /**
     * 运行总线（断线重连接流）：本轮的执行与那条 SSE 通道解耦——事件先进总线再扇给此刻挂着的通道，
     * 观众走光不掐本轮（宽限到点才按中断收束），迟到的观众先拿快照再接增量。详见 {@link ChatRunBus}。
     */
    private final ChatRunBus chatRunBus;

    /**
     * 轮次控制与存活（P6 刀 2）：SSE 投递与断开跟踪、心跳与存活看门狗、停止本轮 / 运行中插话 /
     * 探测接流、断线宽限中止都在它那里。本类只剩「这一轮怎么把资料找出来、答案怎么生成出来」。
     * <p>方向单向：问答侧调用它，它不回叫问答侧。{@code chat()} 刻意留在这边（要提交流水线），
     * 而原先直接摸那三张登记表的四处改走它的具名闸口——登记表不外露，进出都得走名字。
     */
    private final ChatRunRuntime runtime;



    /**
     * 对话历史的装配与滚动压缩（P6 刀 4）：本轮带多少历史、什么时候压、手动 /compact 怎么分批压完。
     * 它不认识 {@code TurnState}，只要会话、模型、预算与降级容器——所以问答侧调它，它不回叫问答侧。
     */
    private final com.wenqu.ai.agent.context.HistoryCompactionService historyCompaction;



    /**
     * chatflow 桥（P6 刀 4）：智能体绑定了工作流的那条路——每轮把问题送进图，end 节点出参就是回答。
     * 这一条路没有生成流，所以桥完全不碰任何运行时状态袋，只吃通道与会话落库。
     */
    private final com.wenqu.ai.agent.chatflow.ChatflowBridge chatflow;


    /**
     * 问答外壳与工作流桥：常规问答已全部交给 AgentScope 框架引擎（见 {@code agentx} 包），
     * 本类保留承担「轮级外壳」（会话轮级互斥、断线续跑、身份与个人覆盖装载）以及工作流（Chatflow）旁路
     * ——绑定工作流的智能体由工作流 end 节点的出参当回答，这条路由工作流执行、是 AgentScope 不提供的
     * 个性化能力，经 {@link #runWorkflowBoundTurn} 处理，不属于被框架替代的手写推理循环。
     */
    private final com.wenqu.ai.engine.ChatEngineFactory chatEngines;



    /** M1：查询改写专用线程池（隔离超时任务，避免占用公共池/无限堆积）。
     *  线程数随 chat.pipelineThreads 联动扩容（syncPipelineSize）：改写是每轮必经的短 LLM 调用
     *  （≤8s），固定 2 线程在百级并发下排队到超时、改写静默降级为原句——不报错但召回质量塌方 */
    private ThreadPoolExecutor rewriteExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "rewrite");
        t.setDaemon(true);
        return t;
    });


    /** 问答流水线线程池：图片描述 join/改写/检索/深度思考等重活都在独立线程执行，避免占满 Tomcat 请求线程；
     *   chat.pipelineThreads 可调（保存即生效），队列有界，满时快速失败返回"系统繁忙" */
    private ThreadPoolExecutor pipelineExecutor;

    @jakarta.annotation.PostConstruct
    void initPipeline() {
        syncPipelineSize();
        warmUpToolSchemas();
    }

    /**
     * 这个智能体是否由工作流产出回答（绑定是智能体的属性；未指定智能体/默认入口一律不算）。
     * <p>读不到绑定时按「没绑」处理并 fail-loud：宁可让分派照常选引擎，也不要把一轮钉死在旧运行时上——
     * 猜错的两种后果里，「图被跳过」是看得见的错，「引擎不该换却没换」只是少了一半灰度。
     */
    private boolean workflowBoundOf(String agentId) {
        if (agentId == null || agentId.isBlank()) return false;
        try {
            com.wenqu.ai.model.Agent a = agentService.get(agentId);
            return a != null && a.getWorkflowId() != null && !a.getWorkflowId().isBlank();
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 智能体的工作流绑定读不出来，本轮按常规分派: agent={} {}", agentId, e.toString());
            return false;
        }
    }

    /**
     * 工作流驱动的那一轮：解析本轮身份（模型/作答方/@咨询/降级），然后把整轮交给
     * {@link ChatflowBridge} 跑那张图 —— <b>之后不再有任何推理逻辑</b>。
     *
     * <p>为什么单独成方法：这段原本是手写推理循环 {@code runChat} 的前导段，而推理循环本体
     * （{@code runChat} 的后半段 + {@code buildAnswerStream}）在单引擎化之后<b>已经不可达</b>——
     * 能进 {@code runChat} 的前提是「智能体绑了工作流」（分派点判的就是它），而 {@code runChat}
     * 内部第一个分支判的又是同一个条件并直接 return。把前导段抽出来，那个推理循环整体消失。
     *
     * <p>出口仍用本轮那条 emitter，深度思考由入口处裁决好传入（工作流节点用自己的模型）。
     */
    private void runWorkflowBoundTurn(ChatCommand cmd, com.wenqu.ai.engine.RunChannel channel) {
        final SseEmitter emitter = channel.emitter();
        final String sessionId = cmd.getSessionId();
        final String question = cmd.getQuestion();
        final List<String> userImages = cmd.getUserImages();
        final List<ChatRequest.Attachment> attachments = cmd.getAttachments();
        final List<ChatRequest.Mention> mentions = cmd.getMentions();
        final String agentId = cmd.getAgentId();
        final String userId = cmd.getUserId();
        final boolean guestMode = cmd.isGuestMode();
        final boolean regenerate = cmd.isRegenerate();
        final String replaceMessageId = cmd.getReplaceMessageId();
        final String editVariantGroup = cmd.getEditVariantGroup();
        final boolean planMode = cmd.isPlanMode();
        final boolean branchTurn = cmd.branchTurn();

        final long startTime = System.currentTimeMillis();
        // 本轮身份：取自 chat() 入口登记的把手，随消息落库挂上——逐气泡的「过程」按它取这一轮的事件账本
        final String turnId = runtime.turnIdOf(sessionId);
        // 个人偏好一次取齐（工作流的 memoryModel 用）
        final com.wenqu.ai.model.User prefUser = loadPrefUser(userId);
        // 本轮生效模型（请求覆盖 > 会话已存覆盖 > 个人默认）。解析链与框架引擎共用 resolveTurnModel，
        // 撞上一个已经不能用的模型时它负责决定「清掉会话选择并说明」还是「不发起、告诉用户去哪儿改」。
        final TurnModel turnModel = resolveTurnModel(cmd);
        if (turnModel.unusableMessage() != null) {
            log.warn("[FAIL-LOUD] 本轮模型已不可用: session={} {}", sessionId, turnModel.unusableMessage());
            runtime.sendSseEvent(emitter, "error", turnModel.unusableMessage(), sessionId);
            runtime.completeEmitter(emitter);
            return;
        }
        final String resolvedModel = turnModel.ref();
        if (resolvedModel.isBlank()) {
            log.warn("[FAIL-LOUD] 未配置任何模型（会话覆盖/个人默认均未指定）: session={}", sessionId);
            runtime.sendSseEvent(emitter, "error",
                    "未指定模型：请在对话右上角选择模型，或在个人设置中配置默认模型", sessionId);
            runtime.completeEmitter(emitter);
            return;
        }
        // 本轮全部降级/兜底事件（fail-loud：随 done 下发，前端渲染警示条；code 去重，同类只报一次）。
        // 声明在智能体解析之前：会话锁定的智能体若已不可用，需要就地登记提示而不是静默改用全局配置
        List<Map<String, String>> degradations = new ArrayList<>();
        Set<String> degradedCodes = new HashSet<>();
        if (turnModel.fallbackNotice() != null) {
            runtime.addDegradation(degradations, degradedCodes, "modelFallback", turnModel.fallbackNotice());
        }
        // @ 智能体（P5 换芯）：不换成它作答，而是要求它被咨询一次。
        // 用户显式点名的咨询不受 agent.enabled 影响：那是平台侧编排开关，这条是用户指令。
        final Agent forcedConsult = resolveDelegatedAgent(mentions, degradations, degradedCodes);
        final Agent agent = resolveTurnAgent(agentId, degradations, degradedCodes);
        final boolean consultSelf = forcedConsult != null && agent != null
                && forcedConsult.getId().equals(agent.getId());
        if (forcedConsult != null && !consultSelf) {
            log.info("[AGENT] 会话 {} 本轮强制咨询 @ 提及的智能体 {}（{}）",
                    sessionId, forcedConsult.getId(), forcedConsult.getName());
            emitAgentDelegated(emitter, forcedConsult, sessionId);
        } else if (consultSelf) {
            runtime.addDegradation(degradations, degradedCodes, "consultSelf",
                    "你 @ 的就是本轮作答的助手，无需再咨询自己，已直接按它的资料回答");
        }
        if (agent != null) {
            log.info("[AGENT] 本轮使用智能体 {}（{}）", agent.getId(), agent.getName());
            // 会话行记一笔"最近作答方"（供分享页按智能体列会话与访客回收用）
            sessionService.recordTurnAgent(sessionId, agent.getId(), agent.getName());
        }
        // M4：智能体绑定工作流（chatflow）——回答由工作流产出，整条不走常规检索/生成链路。
        // 图片/附件在此路径下不参与（工作流只接收文本入参），显式登记为降级提示而不是静默丢弃。
        if ((userImages != null && !userImages.isEmpty()) || (attachments != null && !attachments.isEmpty())) {
            runtime.addDegradation(degradations, degradedCodes, "workflowInputIgnored",
                    "该智能体由工作流驱动，本轮的图片/附件不会传入工作流（工作流当前只接收文本入参）");
        }
        if (planMode) {
            runtime.addDegradation(degradations, degradedCodes, "planWorkflowUnsupported",
                    "该智能体由工作流驱动，计划模式不生效，本轮按工作流直接执行");
        }
        // memoryModel 用本轮已经取到的那份个人偏好（工作流节点用自己的模型，长期记忆按个人默认提取）
        chatflow.runWorkflowChat(sessionId, question, userId,
                prefUser == null ? null : prefUser.getDefaultModel(),
                emitter, startTime, degradations, degradedCodes,
                agent, guestMode, branchTurn, regenerate, replaceMessageId, editVariantGroup,
                null,
                // 同上：上传归属者（免登录分享时是访客）
                chatAttachments.metasJson(attachments, cmd.uploadOwnerUid()),
                (mentions == null || mentions.isEmpty()) ? null : com.alibaba.fastjson2.JSON.toJSONString(mentions));
    }

    /**
     * 启动期单线程预热工具 schema：@Tool 回调的 inputSchema 由 victools/Jackson 首次
     * introspection 时生成，而 Jackson 对同一类型的并发首采会抛
     * 「Conflicting property-based creators」（record 的 canonical 构造器被两个线程同时收集，
     * 8091 百并发压测实测复现：重启后头一批并发问答随机「系统处理异常」）。
     * 启动期串行生成一遍，Jackson 的 introspection 缓存填充后，后续并发生成只读缓存。
     */
    private void warmUpToolSchemas() {
        try {
            for (org.springframework.ai.tool.ToolCallback cb :
                    org.springframework.ai.support.ToolCallbacks.from(builtinTools)) {
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
        // 辅助池联动：改写=每轮一次的短调用给一半流水线并发，压缩=偶发长调用给四分之一。
        // 不联动的话，流水线扩到百级而辅助池固定 2/1，改写与压缩成片超时静默降级
        resizePool(rewriteExecutor, Math.max(4, n / 2));
        historyCompaction.syncPoolSize(Math.max(2, n / 4));   // 压缩池归历史族自己管（刀 4）
    }

    /** 有界线程池安全调容：全程维持 max ≥ core 的不变量（先抬 max、core 归位后再收 max） */
    private static void resizePool(java.util.concurrent.ThreadPoolExecutor pool, int n) {
        if (n <= 0 || n == pool.getCorePoolSize()) return;
        pool.setMaximumPoolSize(Math.max(pool.getMaximumPoolSize(), n));
        pool.setCorePoolSize(n);
        pool.setMaximumPoolSize(n);
    }

    @jakarta.annotation.PreDestroy
    void shutdownRewriteExecutor() {
        rewriteExecutor.shutdownNow();
        if (pipelineExecutor != null) {
            pipelineExecutor.shutdownNow();
        }
    }



    public ChatService(SessionService sessionService,
                      ConfigService configService,
                      ChatAttachmentService chatAttachments,
                      KnowledgeRetrievalTool knowledgeRetrievalTool,
                      BuiltinTools builtinTools,
                      AgentService agentService,
                      KnowledgeBaseService knowledgeBaseService,
                      com.wenqu.ai.mapper.UserMapper userMapper,
                      ModelRegistryService modelRegistryService,
                      UserConfigService userConfigService,
                      SessionEventService sessionEventService,
                      ChatRunBus chatRunBus,
                      ChatRunRuntime runtime,
                      com.wenqu.ai.agent.context.HistoryCompactionService historyCompaction,
                      com.wenqu.ai.agent.chatflow.ChatflowBridge chatflow,
                      com.wenqu.ai.engine.ChatEngineFactory chatEngines) {
        this.sessionService = sessionService;
        this.configService = configService;
        this.chatAttachments = chatAttachments;
        this.knowledgeRetrievalTool = knowledgeRetrievalTool;
        this.builtinTools = builtinTools;
        this.agentService = agentService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.userMapper = userMapper;
        this.userConfigService = userConfigService;
        this.modelRegistryService = modelRegistryService;
        this.sessionEventService = sessionEventService;
        this.chatRunBus = chatRunBus;
        this.runtime = runtime;
        this.historyCompaction = historyCompaction;
        this.chatflow = chatflow;
        this.chatEngines = chatEngines;

    }

    /**
     * 处理用户问题（可含上传图片与附件），通过 SSE 流式返回。
     * <p>P1 seam 化：这里原本是 10 个 telescoping 重载层层委托（10 参一路加到 19 参），
     * 现收成 {@link ChatCommand} 一个入参对象。加能力不再动调用方，相邻同类型实参写反也不再
     * 只能靠人眼拦（见 ChatCommand 类注释）。
     * <p>{@code emitter} 刻意不进命令对象：它是这一轮的<b>出口</b>而不是内容——工作流子 agent 用
     * CollectingSseEmitter，定时任务与对外 MCP 端点各有自己的收集通道，与「问了什么」正交；
     * 混进同一个列表就会出现第 11 个重载。整条流水线在独立线程池执行（重活不占 Tomcat 请求线程）。
     */
    public void chat(ChatCommand cmd, SseEmitter emitter) {
        // 解包成本轮内的局部名：流水线体量大（互斥登记、总线开轮、状态构造、异步提交），
        // 逐处改成 cmd.getX() 的收益是零、风险是把一个位置写错的 bug 埋进八千行里
        final String sessionId = cmd.getSessionId();
        final String question = cmd.getQuestion();
        final List<String> userImages = cmd.getUserImages();
        final List<ChatRequest.Attachment> attachments = cmd.getAttachments();
        final List<String> skills = cmd.getSkills();
        final List<ChatRequest.Mention> mentions = cmd.getMentions();
        final List<ChatRequest.HistoryRef> historyRefs = cmd.getHistoryRefs();
        final boolean deepThink = cmd.isDeepThink();
        final String agentId = cmd.getAgentId();
        final String modelOverride = cmd.getModelOverride();
        final String userId = cmd.getUserId();
        final String reasoningLevel = cmd.getReasoningLevel();
        final Integer requestedContextWindow = cmd.getRequestedContextWindow();
        final boolean guestMode = cmd.isGuestMode();
        final boolean regenerate = cmd.isRegenerate();
        final String replaceMessageId = cmd.getReplaceMessageId();
        final String editVariantGroup = cmd.getEditVariantGroup();
        final boolean planMode = cmd.isPlanMode();
        final boolean fromSteer = cmd.isFromSteer();

        // 是否深度思考由用户自己决定（对话页 per-model 开关）+ 模型能力决定，平台不代为路由：
        // 管理员侧的自动路由已删除——它会覆盖用户显式关闭的选择、强制消耗用户的 token。
        final boolean useDeepThink = deepThink;
        // 会话轮级互斥：同一会话同时只允许一轮在跑。并发两问会历史交错、ArtifactService EMITTERS
        // 相互覆盖（后轮覆盖前轮，先完成者注销掉后轮的推送）、首问智能体绑定竞态。断线后台续跑
        // 与 askUser 等待期同样占坑——等待作答时再发新消息正是要挡的交错。限频/归属校验已在上游。
        if (!runtime.tryBeginTurn(sessionId)) {
            log.warn("[FAIL-LOUD] 同会话并发问答被拒: session={}", sessionId);
            runtime.sendSseEvent(emitter, "error", "该会话上一轮还在回答中，请等回答结束或先停止本轮，再发送新消息", sessionId);
            runtime.completeEmitter(emitter);
            return;
        }
        // 本轮身份从这一刻起可用（早于流状态构造）：账本的每条事件都挂得到同一个 turnId
        final String turnId = java.util.UUID.randomUUID().toString().replace("-", "");
        TurnRegistry.registerHandle(sessionId, new TurnHandle(turnId, userId, emitter));
        // 断开跟踪：登记查表项并绑定生命周期回调清理；发送失败也会打标（见 runtime.sendSseEvent），各等待点据此短路后续 LLM/检索开销
        runtime.trackChannel(emitter);
        // 运行总线登记（P0 断线重连接流）：本轮的执行不再绑死在发起时那条通道上——刷新/换设备能接回来看它
        // 继续长出来，观众走光也只是「暂时没人看」，宽限（chat.detachGraceMs）到点才由总线回调按中断收束，
        // 走的还是既有半程截断落库那条路。刻意不登记：收集型通道（定时任务/MCP/工作流节点没有真实响应）、
        // 开关关闭（行为回到「断开即中止」）。
        // 游客分享会话<b>也登记</b>（2026-10-12 补全）：归属用的是访客自己的身份（share-visitor:{随机}）
        // 而不是发布者——用发布者的 uid 挂归属，等于任何拿到分享链接的人都能接走别人会话的流。
        // 访客身份缺失（老链接没带 visitorId）就退回不登记，照旧按历史回显，绝不退成「按发布者放行」。
        final String runOwner = guestMode ? cmd.getGuestUid() : userId;
        if (chatRunBus.enabled() && runOwner != null && !runOwner.isBlank()
                && !(emitter instanceof CollectingSseEmitter)) {
            chatRunBus.open(sessionId, turnId, runOwner, emitter,
                    () -> resumeSnapshot(sessionId, turnId),
                    () -> runtime.abortTurnWithoutViewer(sessionId),
                    () -> {
                        TurnHandle h = TurnRegistry.handle(sessionId);
                        return h != null && turnId.equals(h.turnId());
                    });
        }
        // 整轮存活看门狗：emitter 无容器超时，截断由台账按机器耗时判定（人工等待不计入，见 TurnDeadline）。
        // 收集型通道（定时任务/MCP/子智能体节点）不挂：它没有真实响应、节奏由调用方 awaitDone 控制。
        TurnDeadline ledger = null;
        if (!(emitter instanceof CollectingSseEmitter)) {
            ledger = new TurnDeadline(runtime.turnMachineBudgetMs());
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
                // 会拿到 anonymous ⇒ 可见库集合为空/受限，配了共享范围（department/user）
                // 的文档被判为不可见并被过滤，表现就是"共享给我的资料，我在问答里检索不到"。
                // 这里按本轮 userId 从用户档案装载身份，让过滤按**真实用户**算（来源是用户表，
                // 与调用线程无关：网页问答的 Tomcat 线程、定时任务的池线程同一个来源）。
                boolean identity = loadIdentity(userId);
                // 个人配置覆盖（个人设置 → 对话偏好）：随身份一次性载入，本轮内 configService.get 对
                // personal 字段优先读个人值（相关建议条数等）。
                // 与身份同生命周期：池化线程复用，finally 必须清（否则下一轮会带着上一轮用户的偏好）
                configService.putUserOverrides(userConfigService.overrides(userId));
                try {
                // 分派点刻意放在这一段：会话轮级互斥、turnId 登记、断线总线、看门狗、身份与个人覆盖
                // 装载都是「这一轮」的外壳，两种引擎都要；被替换的只是外壳里面那段推理循环本身。
                var dispatchChannel =
                        new com.wenqu.ai.engine.SseRunEventChannel(runtime, emitter, sessionId, useDeepThink);
                // 绑定了工作流的智能体<b>不进任何一条推理循环</b>：那条路由工作流 end 节点的出参当回答
                // （ChatflowBridge 的口径是「整条不走常规检索/生成，人设/库界/工具都不参与」）。
                // 分派原先只看「引擎选哪个」，于是切到框架引擎后这张图被整个跳过——用户配的是「图的答案」，
                // 拿到的却是模型自己答的一句。这不是观测出来的差异，是 Phase 5 排工作流用例时读代码照出来的。
                if (workflowBoundOf(cmd.getAgentId())) {
                    runWorkflowBoundTurn(cmd, dispatchChannel);
                } else {
                    chatEngines.select(cmd).chat(cmd, dispatchChannel);
                    // 框架引擎同步跑完整轮：方法返回即本轮已终结（待办/计划批准期间引擎是在循环里
                    // 阻塞等待的，不会提前返回）。必须在这里摘轮次把手 + 收掉总线 run——总线 run 的
                    // turnAlive 判的是「TurnRegistry 里的把手是否还是本轮」，不摘就恒为真：会话被永久
                    // 当成「正在跑」，刷新/换设备接回来永远转圈，直到 300s 看门狗超时才收（实测踩过）。
                    TurnRegistry.removeHandle(sessionId);
                    chatRunBus.finish(sessionId);
                }
                } finally {
                    // 「这一问没有回答」的标记只能在本线程走到底时打——早一步判会抢在落库之前，
                    // 晚一步这里已经出不来了。断线宽限到点的中止也在这一并判：那条路径由总线线程
                    // 摘掉把手，只看 runtime.turnStopRequested 会漏打标记，用户下次进来读到的会是
                    // 「问出去了却什么都没有」的空档。
                    // 两种终止要分开写：用户点停止 ≠ 没人看超时——后者不是用户做的事，文案不许怪他
                    if (runtime.takeBareAbort(sessionId, turnId)) {
                        sessionService.markTurnStopped(sessionId, "interrupted");
                    } else if (runtime.turnStopRequested(sessionId)) {
                        sessionService.markTurnStopped(sessionId, "stopped");
                    }
                    runtime.endTurnMutex(sessionId);
                    if (identity) com.wenqu.ai.util.RequestUser.clear();
                    // 智能体检索参数的作用域覆盖随本轮结束清除（ThreadLocal，池化线程复用必须清，
                    // 否则下一轮请求会继承上一轮智能体的检索策略）
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
            runtime.sendSseEvent(emitter, "error", "系统繁忙（当前排队 " + queued + " 个请求），请稍后重试", sessionId);
            runtime.completeEmitter(emitter);
        }
    }


    // ==================== P3b：沙盒脚本的子调用回程 ====================



    // ---- 智能体（P5）本轮作答方解析 ----    // ---- 智能体（P5）本轮作答方解析 ----

    /**
     * 本轮作答的智能体：请求带 id 就用它（分享页 / 定时任务 / 工作流 / 对外 MCP 都是显式指定），
     * 不带就用默认智能体（isDefault=1）；未设默认才落到纯系统设置的全局配置。
     * <p>P5 起这里<b>不再有"会话锁定"</b>。过去首问把智能体锁进会话，是为了让同一会话里人设、知识库、
     * 工具集全程一致；代价是跨主题的问题只能对着自己那套库空转——"上一轮问手册、这一轮问法律就查不到"。
     * 现在资料域由模型经 consultAgent 主动派出去查，作答方不需要被锁，也没有"换人请新建会话"这回事。
     * <p>显式指定却解析不到（已删除或无权限）时 fail-loud：静默改用全局配置等于悄悄换人，用户无从察觉。
     */
    private Agent resolveTurnAgent(String requestedAgentId, List<Map<String, String>> degradations,
                                   Set<String> degradedCodes) {
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            // 个人默认助手（设置页「对话偏好」里选的那位）：流水线线程已装载个人覆盖，直接 get 就是本人值。
            // 它为替代被删掉的"会话锁定"而存在——整天只跟一位助手聊的人不必每会话重挑一次。
            String personal = configService.get("agent.personalDefault");
            if (personal != null && !personal.isBlank()) {
                Agent mine = agentService.get(personal.trim());
                if (mine != null) return mine;
                // 选了之后被删/失去共享：如实说一句再回落，而不是让人以为自己的默认还生效
                runtime.addDegradation(degradations, degradedCodes, "personalAgentGone",
                        "你的默认助手已不可用（被删除或不再共享给你），本轮已改用平台默认智能体");
            }
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
     * 智能体不再绑定聊天模型、全局 chat.model 兜底已移除——均未配置时返回空串，由调用方 fail-loud 引导配置。
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
     * 它原先只拿「请求里的 model」，用户靠个人默认作答时框架侧就解析不出路由（实测是 NPE 打断整轮）。
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
     *       是某次点选留下的串，用户早就不记得它，留着只会每轮撞一次上游报错（真机 sbx-3：
     *       模型停用后连续几轮 401，之后整条会话不再启动）。</li>
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

    public TurnModel resolveTurnModel(ChatCommand cmd) {
        String uid = cmd.getUserId();
        com.wenqu.ai.model.User prefUser = loadPrefUser(uid);
        // 归属这条判据只在「问的就是请求者自己能不能用这个供应商」时才成立：游客对话用的是分享主登记的
        // 模型、匿名兼容池的会话没有 uid，按他们的身份判必然判成「不属于你」，于是把好用的模型判死
        boolean judgeOwnership = !cmd.isGuestMode() && uid != null && !uid.isBlank()
                && !com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(uid);
        String role = prefUser != null ? prefUser.getRole() : com.wenqu.ai.util.RequestUser.role();
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
        if (!pin.model().isEmpty()) {
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

    /** 个人偏好用户行（含三类个人默认模型）；匿名/未登录/查询失败返回 null（全部走空语义） */
    /**
     * 在流水线线程内装载本轮用户身份（uid / 部门 / 角色），供检索的文档可见性过滤按**真实用户**判定。
     * <p>背景：问答流水线跑在独立线程池、没有请求上下文，而 {@code HybridRetrievalService} 的
     * 库门下推（{@code loadVisibleKbIds}）与文档可见性晚绑定（{@code loadVisibleDocIdsOfHits}）
     * 都从 {@code RequestUser} 取身份 ⇒ 只会拿到 anonymous。其后果是：配了共享范围
     * （{@code access_level=department/user}）的文档，对**包括被授权者在内**的所有人都判为不可见并被过滤——
     * 也就是"共享给我、或我自己限定范围的资料，在问答里检索不到"。
     * <p>身份从用户档案读，与调用线程无关：网页问答（Tomcat 线程）与定时任务（池线程，uid 由参数传入）
     * 走同一来源；代价是每轮多一次用户表主键查询（与 runChat 内取个人偏好那次同表主键查询同一量级）。
     *
     * @return 是否已装载（未装载时调用方不需要清理）
     */
    private boolean loadIdentity(String userId) {
        if (userId == null || userId.isBlank()
                || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(userId)) {
            // 匿名池（历史兼容会话）：保持 anonymous 语义，不做任何提升
            return false;
        }
        try {
            com.wenqu.ai.model.User u = userMapper.selectById(userId);
            if (u == null) return false;
            com.wenqu.ai.util.RequestUser.set(u.getUid(), u.getDepartmentId(), u.getRole());
            return true;
        } catch (Exception e) {
            // 装载失败按匿名继续（不打断问答），但必须留痕：否则又变成"共享资料检索不到"且无迹可查
            log.warn("[FAIL-LOUD] 流水线线程装载用户身份失败（本轮检索可见性按匿名判定）uid={}: {}",
                    userId, e.getMessage());
            return false;
        }
    }

    private com.wenqu.ai.model.User loadPrefUser(String userId) {
        if (userId == null || userId.isBlank() || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(userId)) {
            return null;
        }
        try {
            return userMapper.selectById(userId);
        } catch (Exception e) {
            log.debug("[PREF] 个人偏好加载失败（视为未配置）: {}", e.getMessage());
            return null;
        }
    }


    // ==================== P3a：子智能体真全循环 ====================


    /**
     * 非问答轮（手动压缩 /compact）的生效模型解析：显式指定 &gt; 个人默认；返回空串表示未配置，
     * 由调用方 fail-loud 引导用户选模型（与问答轮同一解析顺序，避免两条路走进不同模型）
     */
    public String resolveChatModel(String userId, String modelOverride) {
        return resolveModel(modelOverride, loadPrefUser(userId));
    }


    // ==================== 断线重连接流（P0 最后一刀，配合 ChatRunBus） ====================

    /**
     * 接流快照：从本轮的权威缓冲重建「到此刻为止看得见的东西」。
     * <p>这些缓冲本来就是 done 落库与实时流的同源数据，所以接流方拿到的形状与刷新后回看历史一致。
     * <p>不含：待裁决卡片（提问/计划/审批）——它们的时限与答案记录以 c_ai_tool_approval 为准，
     * 前端走既有的 {@code /ask-user/pending}、{@code /plan-approval/pending} 恢复入口，
     * 那里才有剩余的等待毫秒数与「唤醒句柄是否还在」（进程重启过就答不进去）；逐字增量事件也不含，
     * 正文取缓冲本身比回放上千条 token 事件快得多。
     */
    private Map<String, Object> resumeSnapshot(String sessionId, String turnId) {
        Map<String, Object> snap = new LinkedHashMap<>();
        TurnHandle h = TurnRegistry.handle(sessionId);
        if (h == null || !turnId.equals(h.turnId())) {
            snap.put("running", false);   // 本轮已收尾（把手已摘）
            return snap;
        }
        // 框架运行时没有问渠侧的状态袋：前置阶段（检索/思考）与生成中共用「pre」快照，
        // 阶段文案、计划步骤与任务清单由总线镜像在 snapshot.events 里，前端重放即可复现界面
        snap.put("running", true);
        snap.put("phase", "pre");
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
                    item.put("status", o.getString("status"));
                    if (o.get("elapsedMs") != null) item.put("elapsedMs", o.get("elapsedMs"));
                }
            } catch (Exception ignore) {
                // 载荷不是 JSON（历史行或被截断）：照旧只给摘要，对照视图少一个编号而已
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
