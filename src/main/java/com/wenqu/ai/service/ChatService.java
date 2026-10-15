package com.wenqu.ai.service;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.TurnDeadline;
import com.wenqu.ai.agent.chat.TurnHandle;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.agent.hitl.HitlDecisionService;
import com.wenqu.ai.agent.context.RetrievalPipeline;
import com.wenqu.ai.agent.chat.TurnRecorder;
import com.wenqu.ai.agent.chat.TurnSourceRegistry;
import com.wenqu.ai.agent.chat.TurnState;
import com.wenqu.ai.agent.hitl.HitlDecisionService.QItem;
import com.wenqu.ai.agent.subagent.BranchRunner;
import com.wenqu.ai.agent.tool.ToolApprover;
import com.wenqu.ai.agent.tool.ToolEnrichment;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolInvocation;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.agent.tool.ToolPipelineFactory;
import com.wenqu.ai.agent.tool.ToolRegistry;
import com.wenqu.ai.agent.tool.ToolTurnContext;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.service.websearch.WebSearchTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 问答运行时门面（P6 刀 5d 由 {@code RagService} 更名）：一轮问答从入口到落库的编排都在这里——
 * 会话互斥与总线开轮、身份/偏好装载、检索范围裁决、上下文装配（{@link RetrievalPipeline}）、
 * 提示词装配、生成流订阅与工具循环接缝、终态落库（{@link TurnRecorder}）。
 * 检索执行在 {@code HybridRetrievalService}/{@code RerankService}，轮次活在 {@link ChatRunRuntime}，
 * 卡片在 {@code HitlDecisionService}——本类只回答「这一轮怎么把资料找出来、答案怎么生成出来」。
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








    /** 改写结果（实际检索用 query + 命中） */






    private static final Pattern relatedPattern = Pattern.compile("<related>([\\s\\S]*?)</related>");
    /** 过程独白块：工具模式下模型把推理/试错独白包进 <process> 标签，流式中整块剥离归入过程通道（不进正文） */
    private static final Pattern processPattern = Pattern.compile("<process>([\\s\\S]*?)</process>");

    /** 工具模式提示词附录（buildAnswerStream 注入）：过程叙述规范——独白进 <process> 标签分流，标签外只写正式回答 */
    private static final String PROCESS_NARRATION_GUIDE = """

            【过程叙述规范】你正在带工具的执行场景中工作。推理、试错、排查与复盘属于过程，必须包进 <process> 与 </process> 标签内；标签外只写面向用户的正式回答：
            - 调用工具前后的想法、中间检查、对工具报错的处理过程，一律写在 <process> 标签内，且同一内容只写一遍；
            - 标签外禁止复述过程：不要重复 <process> 里已说过的话，不要出现"我先/我再看看/我改用/写入被拒我改用"这类过程性叙述，也不要把工具报错与排查细节写进正文（工具卡片已展示）；
            - 标签外的每个字都会原样展示给用户、算作正式回答的一部分：发起工具调用时不要在标签外附带任何计划句、过渡句或说明句（无论中文还是英文，例如"I'll first read... / Let me search..."），要么不说，要么写进 <process>；
            - 尽量把一段完整的过程叙述写完再发起工具调用，不要在一句话中间插入工具调用；
            - 全部工具执行完后，在标签外输出完整的正式回答（结论 + 结果摘要 + 必要说明），可直接作为交付内容阅读。
            """;
    /** 推荐块条目超过此长度即视为「正文被写进 related」，并回回答而不是剥离丢弃 */
    private static final int RELATED_PROSE_MIN_CHARS = 80;

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final AppProperties properties;
    private final ImageUrlSigner imageUrlSigner;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;
    /** 用户长期记忆：跨会话事实/偏好的提取与注入（memory.enabled；游客分享会话豁免——发布者记忆不外泄） */
    private final UserMemoryService userMemoryService;
    private final QaLogService qaLogService;
    private final UserImageService userImageService;
    private final ConfigService configService;
    private final ImageFilterService imageFilterService;
    private final KeywordExtractor keywordExtractor;
    /** 知识库精确检索工具（Function Calling；由 tool.* 配置开关控制，默认开启） */
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;
    /** 产物交付服务（会话 emitter 注册表 + 文件落盘 + SSE 下发） */
    private final ArtifactService artifactService;
    /** MCP 客户端服务（外部 MCP server 工具接入；按用户隔离连接池，各人连各人的服务） */

    /** 知识库服务：解析「智能体关联的知识库 → 允许检索的文档集合」，检索按库隔离 */
    private final KnowledgeBaseService knowledgeBaseService;

    /** 角色服务：提问者是否管理员级（内置 admin/superadmin 或自定义 admin_flag 角色）——回答按角色收敛管理端内容 */
    private final RoleService roleService;

    /** 用户表（个人默认模型解析） */
    private final com.wenqu.ai.mapper.UserMapper userMapper;
    /** 产物交付工具（Function Calling；生成文件并实时推送） */
    /** 内置高频工具（计算/当前时间/日期差等，tool.builtin.enabled 控制，默认开） */
    private final BuiltinTools builtinTools;
    /** 联网搜索工具（webSearch.enabled 控制，默认关；结果注册进引用体系，与知识库来源同编号） */
    /** 沙盒工具（execute / read_file / write_file / ls；tool.sandbox.enabled 控制，默认关，依赖 provisioner 服务） */
    /** 技能（Skills）：清单注入 system prompt + readSkill 工具的服务端（技能为个人资产，按 uid 取） */
    private final SkillService skillService;
    /** 个人配置覆盖（个人设置 → 对话偏好）：流水线线程装载 ThreadLocal，本轮 configService.get 优先个人值 */
    private final UserConfigService userConfigService;
    /** 聊天附件（文档类）：解码/解析为纯文本注入本轮上下文（图片走 images 多模态，不经此服务） */
    private final ChatAttachmentService chatAttachmentService;
    /** SubAgent 并行编排（4.3）：多视角并行检索 + 要点提炼（agent.enabled 控制，默认关） */
    private final SubAgentOrchestrator subAgentOrchestrator;
    /** 智能体配置（4.1）：对话页下拉选中后，按智能体覆盖模型/提示词/工具/知识库范围 */
    private final AgentService agentService;
    /** 智能体咨询（P5 agent-as-tool）：模型在工具循环里自己决定咨询谁；台账按轮建 */
    private final com.wenqu.ai.agent.subagent.SubAgentConsultService subAgentConsult;
    private final ModelRegistryService modelRegistryService;
    /**
     * M4：工作流服务（智能体绑定工作流时取用）。走 ObjectProvider <b>惰性</b>获取而不是直接注入：
     * {@code WorkflowService → WorkflowEngine → ChatService} 已是一条依赖链（子智能体节点复用问答管线），
     * 这里再直接注入就成环（Spring Boot 3 默认禁止循环依赖，启动直接失败）。惰性取用把这个
     * 「只有绑定了工作流的智能体才用得到」的反向依赖推迟到调用时，依赖图保持单向。
     */

    /** @ 引用：被引文档的块直接前置进上下文（不经检索，不参与相关性门） */
    private final com.wenqu.ai.mapper.KnowledgeMapper knowledgeMapper;
    /** 站内通知：工具审批待决（tool.approval）通知发起人。旁路语义 */
    private final NotificationService notificationService;
    /** 会话事件账本：结构性事件按写入顺序留痕（工具/人在回路/编排/轮终态/停止）。旁路语义，不进模型输入 */
    private final SessionEventService sessionEventService;
    /** 任务清单工具（writeTodo）：整表覆盖会话当前清单，写入器经工具上下文注入本轮 */
    private final TodoService todoService;
    /**
     * 运行总线（断线重连接流）：本轮的执行与那条 SSE 通道解耦——事件先进总线再扇给此刻挂着的通道，
     * 观众走光不掐本轮（宽限到点才按中断收束），迟到的观众先拿快照再接增量。详见 {@link ChatRunBus}。
     */
    private final ChatRunBus chatRunBus;
    /**
     * 工具大输出外溢：超过展示上限的原文整份落盘，卡片只留预览 + spillId，
     * 「查看完整输出」按需读回——此前是截断即丢，尾部少了多少、少了什么，用户与排障的人都看不到。
     */
    private final ToolSpillService toolSpillService;
    /**
     * 工具调用管线（P1 seam 化）：守卫链（叫停/预算/审批）→ 上下文注入 → 执行 → 观测 → 插话附送。
     * 迁移前这一整段是 instrumentTools 里的匿名内部类，于是工作流与技能的裸调路径无从复用同一批闸。
     */
    private final ToolPipelineFactory toolPipeline;
    /** 工具注册中心：中文名、副作用等级、是否需要审批的单一真源（装配与卡片展示都读它） */
    private final ToolRegistry toolRegistry;
    /**
     * 权限档位解析（P2）：沙盒执行档 × 工具执行确认捆成的具名预设，加上平台默认与管理员上限。
     * <p>本轮只解析一次并落在 {@code TurnState} 上，装配侧与守卫侧读同一份——两处各算一次
     * 就会漂出「工具表里没收、执行时却放得过去」这类反向错配。
     */
    private final com.wenqu.ai.agent.permission.PermissionPolicyResolver permissionPolicyResolver;
    /**
     * run_code 的一次性运行凭据（P3b）。
     * <p>问答侧只在一个地方用它：用户叫停本轮时把这条轮还没撤的凭据一并撤掉。凭据的作用域本来就是
     * "这一次运行"，人已经不想要这一轮了，脚本却还能继续回调平台，那是多出来的一个窗口。
     */
    private final com.wenqu.ai.agent.runcode.RunTokenRegistry runTokens;
    /**
     * 人在回路裁决服务（P6 刀 1 抽族）：三张卡（工具执行审批 / askUser 提问 / 执行计划批准）的
     * 挂起登记、终态落库、用户裁决与跨设备恢复都在它那里。本类只留「发卡 + 阻塞等待」那一段——
     * 那一段持有的是本轮的通道与计时，而卡片本身（谁在等、等哪一张、答成什么样）与通道无关。
     */
    private final com.wenqu.ai.agent.hitl.HitlDecisionService hitl;
    /**
     * 本轮状态的运行时依赖（P6 刀 0）。
     * <p>{@code TurnState} 原先是私有内部类，隐式拿着整个问答服务——任何一处「抽服务」都会变成多个类
     * 共享同一个可变口袋。提顶层后它要什么必须写在 {@link TurnState.Deps} 里，这张清单就是唯一答案。
     * <p>那三个动作（SSE 投递、降级登记、半程落库）暂时还长在问答侧：它们都要动通道与落库，归属随
     * 刀 2「轮次控制」那一刀才搬得干净。这里交的是匿名实现而不是 this——{@code TurnState} 拿到的是
     * 三个方法，不是整个服务。
     */
    private final TurnState.Deps turnDeps;
    /**
     * 轮次控制与存活（P6 刀 2）：SSE 投递与断开跟踪、心跳与存活看门狗、停止本轮 / 运行中插话 /
     * 探测接流、断线宽限中止都在它那里。本类只剩「这一轮怎么把资料找出来、答案怎么生成出来」。
     * <p>方向单向：问答侧调用它，它不回叫问答侧。{@code chat()} 刻意留在这边（要提交流水线），
     * 而原先直接摸那三张登记表的四处改走它的具名闸口——登记表不外露，进出都得走名字。
     */
    private final ChatRunRuntime runtime;

    /**
     * run_code 的子调用通道与实时面板（P6 刀 3a）：脚本里那一路与模型直调同一条守卫链，
     * 扇出清单/在途/额度这本账也只在子调用通道里才看得全。本类只在挂了 run_code 的轮次找它。
     */
    private final com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime subCall;

    /**
     * 子智能体分支的簿记（P6 刀 3b）：派一支的通道、回报文本、引用与图片回填、给下一层的父轮上下文。
     * 「跑一整轮」仍由本类传入（{@code this::chat}），所以它不认识问答服务、也没有反向引用。
     */
    private final com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branch;

    /**
     * 对话历史的装配与滚动压缩（P6 刀 4）：本轮带多少历史、什么时候压、手动 /compact 怎么分批压完。
     * 它不认识 {@code TurnState}，只要会话、模型、预算与降级容器——所以问答侧调它，它不回叫问答侧。
     */
    private final com.wenqu.ai.agent.context.HistoryCompactionService historyCompaction;

    /**
     * 深度思考这一族（P6 刀 4）：生成前那次同步消费的思考流与从里面提出的检索计划。
     * 它不回头调问答入口，所以能做成 bean；检索与生成仍在本类（阶段3 交回这里执行）。
     */
    private final com.wenqu.ai.agent.think.DeepThinkService deepThinkService;

    /**
     * chatflow 桥（P6 刀 4）：智能体绑定了工作流的那条路——每轮把问题送进图，end 节点出参就是回答。
     * 这一条路没有生成流，所以桥完全不碰 {@code TurnState}，只吃通道与会话落库。
     */
    private final com.wenqu.ai.agent.chatflow.ChatflowBridge chatflow;

    /**
     * 计划模式这一族（P6 刀 4）：出计划、挂批准卡、按意见重出下一版、计划意图识别。
     * 它要「按与生成轮同一条口径」装配一次工具集、要弹一张与执行期同管道的提问卡——
     * 这两件事都在本类，所以经 {@link #planHooks} 交进去（同 P3a 把「跑一整轮」当参数交进分支族那条口径）。
     */
    private final com.wenqu.ai.agent.plan.PlanModeService plan;

    /**
     * 引用面终态校验（P6 刀 5a）：图片错配兜底、未标注、编号越界、图片标记越界、语义自检。
     * 它读本轮的引用池（{@code st.refs}）并交回「定稿正文 + 裁剪后的来源 + 图片清单」三件——
     * 编号重编必须与正文标记同一趟改完，所以这一族不留在流收尾回调里散着。
     */
    private final com.wenqu.ai.agent.context.CitationIntegrityService citations;

    /**
     * 上下文装配（P6 刀 5b）：@ 引用块前置、子智能体命中并入、相邻块合并、分域相关度门、
     * 单文档/单库配额、信息增益去冗余、片段截取与图片全局编号，产出参考资料正文与引用表。
     * 它不回头调问答入口，所以是纯 bean；降级登记经参数里的出口交回本轮（本类不认识状态袋）。
     */
    private final com.wenqu.ai.agent.context.RetrievalPipeline retrieval;

    /**
     * 本轮记录与落库（P6 刀 5c）：工具卡、时间线、用量快照、截断标注、半程正文与计划收尾落库。
     * 原先这条链在 done 与中断兜底两条路上各写一遍同款 21 个位置参数，少传一位的表现是
     * 「刷新后某个字段整块消失」——收在这里只剩一个出处。
     */
    private final com.wenqu.ai.agent.chat.TurnRecorder recorder;

    /** 计划族本轮要用的回call（无本轮状态，只是把本类那四个方法按接口签名摆出来） */
    private final com.wenqu.ai.agent.plan.PlanModeService.PlanTurnHooks planHooks;

    /**
     * 问答外壳与工作流桥：常规问答已全部交给 AgentScope 框架引擎（见 {@code agentx} 包），
     * 本类保留承担「轮级外壳」（会话轮级互斥、断线续跑、身份与个人覆盖装载）以及工作流（Chatflow）旁路
     * ——绑定工作流的智能体由工作流 end 节点的出参当回答，这条路由工作流执行、是 AgentScope 不提供的
     * 个性化能力，经 {@code runChat} 内的 chatflow 分支处理，不属于被框架替代的手写推理循环。
     */
    private final com.wenqu.ai.engine.ChatEngineFactory chatEngines;

    /** 本轮检索范围解析（库界+文档级细选），与框架引擎共用同一处判定 */
    private final com.wenqu.ai.agent.context.RetrievalScopeService retrievalScope;

    /** 本轮工具装配判据（挂不挂这一族）的唯一实现，与框架引擎共用；原先私有在本类 */
    private final com.wenqu.ai.service.ToolAssemblyService toolAssembly;

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
        final String modelOverride = cmd.getModelOverride();
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
        // 本轮生效模型（请求覆盖 > 会话已存覆盖 > 个人默认）。解析链与框架引擎共用 modelRefFor。
        final String resolvedModel = modelRefFor(modelOverride, sessionId, prefUser);
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
                null);
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

    /**
     * 进程收尾：把还在跑的这一轮<b>已生成的半程正文</b>落库，别让它跟着进程一起蒸发。
     * <p>为什么单列一刀：宽限中止、用户停止、流错误三条路都走 {@link #persistPartialAnswer}，只有重启不会——
     * SIGTERM 一到就 {@code shutdownNow()}，那一轮的正文与工具卡全丢，用户回来只看见自己那句问题，
     * 接流也只会回 {@code running:false}（把手是内存态）。这条不改变「跨进程续跑」的边界（跑一半的
     * LLM 流接不回来），只是把已经产出的部分按中断口径留在历史里。
     * <p>为什么走 ContextClosedEvent 而不是再加一个 @PreDestroy：同一个 bean 上多个 @PreDestroy
     * 不保证顺序，而容器关闭事件先于所有 bean 销毁——这里必须抢在 {@code pipelineExecutor} 被掐之前读到
     * 状态表，也抢在 DataSource 关掉之前写完。
     */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    void flushInFlightTurnsOnShutdown() {
        if (!TurnRegistry.hasRunning()) return;
        int saved = 0;
        for (TurnState st : TurnRegistry.runningStates()) {
            String sid = st.sessionId;
            try {
                if (st.claimTerminal()) {
                    recorder.persistPartialAnswer(st, TurnRecorder.RESTART_SUFFIX);
                    saved++;
                }
                // 账本同样要留这一笔：事后回看「这一轮为什么只有半程」，不写会以为是自己断的线
                sessionEventService.append(sid, st.userId, st.turnId, "stop",
                        "进程收尾中止：正文 " + st.fullResponse.length() + " 字、工具步 "
                                + st.tools.toolStepCount.get() + " 步");
                // 一条都没产出的轮：给那一问打上「中断」的终止原因，否则用户只看到一句孤零零的问题
                sessionService.markTurnStopped(sid, "interrupted");
                // 总线立刻收摊：接流的观众当场看到这一轮停了，而不是干等巡检
                chatRunBus.finish(sid);
            } catch (Exception e) {
                log.warn("[SHUTDOWN] 半途正文留存失败 session={}: {}", sid, e.getMessage());
            }
        }
        if (saved > 0) log.info("[SHUTDOWN] 进程收尾：{} 轮在跑的半程正文已按中断态落库", saved);
    }

    public ChatService(ChatClient chatClient,
                      SessionService sessionService,
                      AppProperties properties,
                      ImageUrlSigner imageUrlSigner,
                      HybridRetrievalService hybridRetrievalService,
                      RerankService rerankService,
                      DocumentMetaCache documentMetaCache,
                      QaLogService qaLogService,
                      UserImageService userImageService,
                      ConfigService configService,
                      ImageFilterService imageFilterService,
                      KeywordExtractor keywordExtractor,
                      KnowledgeRetrievalTool knowledgeRetrievalTool,
                      ArtifactService artifactService,
                      BuiltinTools builtinTools,
                      SkillService skillService,
                      ChatAttachmentService chatAttachmentService,
                      SubAgentOrchestrator subAgentOrchestrator,
                      AgentService agentService,
                      com.wenqu.ai.agent.subagent.SubAgentConsultService subAgentConsult,
                      KnowledgeBaseService knowledgeBaseService,
                      RoleService roleService,
                      com.wenqu.ai.mapper.UserMapper userMapper,
                      ModelRegistryService modelRegistryService,
                      UserMemoryService userMemoryService,
                      UserConfigService userConfigService,
                      com.wenqu.ai.mapper.KnowledgeMapper knowledgeMapper,
                      NotificationService notificationService,
                      SessionEventService sessionEventService,
                      TodoService todoService,
                      ChatRunBus chatRunBus,
                      ToolSpillService toolSpillService,
                      ToolPipelineFactory toolPipeline,
                      ToolRegistry toolRegistry,
                      com.wenqu.ai.agent.permission.PermissionPolicyResolver permissionPolicyResolver,
                      com.wenqu.ai.agent.runcode.RunTokenRegistry runTokens,
                      com.wenqu.ai.agent.hitl.HitlDecisionService hitl,
                      ChatRunRuntime runtime,
                      com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime subCall,
                      com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branch,
                      com.wenqu.ai.agent.context.HistoryCompactionService historyCompaction,
                      com.wenqu.ai.agent.think.DeepThinkService deepThinkService,
                      com.wenqu.ai.agent.chatflow.ChatflowBridge chatflow,
                      com.wenqu.ai.agent.plan.PlanModeService plan,
                      com.wenqu.ai.agent.context.CitationIntegrityService citations,
                      com.wenqu.ai.agent.context.RetrievalPipeline retrieval,
                      com.wenqu.ai.agent.chat.TurnRecorder recorder,
                      com.wenqu.ai.engine.ChatEngineFactory chatEngines,
                      com.wenqu.ai.agent.context.RetrievalScopeService retrievalScope,
                      com.wenqu.ai.service.ToolAssemblyService toolAssembly) {
        // 基于 DynamicOpenAiChatModel 的 ChatClient：网关地址/API Key/补全路径支持跨厂商热切换（保存即生效）
        this.chatClient = chatClient;
        this.sessionService = sessionService;
        this.properties = properties;
        this.imageUrlSigner = imageUrlSigner;
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
        this.documentMetaCache = documentMetaCache;
        this.qaLogService = qaLogService;
        this.userImageService = userImageService;
        this.configService = configService;
        this.imageFilterService = imageFilterService;
        this.keywordExtractor = keywordExtractor;
        this.knowledgeRetrievalTool = knowledgeRetrievalTool;
        this.artifactService = artifactService;
        this.builtinTools = builtinTools;
        this.skillService = skillService;
        this.chatAttachmentService = chatAttachmentService;
        this.subAgentOrchestrator = subAgentOrchestrator;
        this.agentService = agentService;
        this.subAgentConsult = subAgentConsult;
        this.knowledgeBaseService = knowledgeBaseService;
        this.roleService = roleService;
        this.userMapper = userMapper;
        this.userMemoryService = userMemoryService;
        this.userConfigService = userConfigService;
        this.modelRegistryService = modelRegistryService;
        this.knowledgeMapper = knowledgeMapper;
        this.notificationService = notificationService;
        this.sessionEventService = sessionEventService;
        this.todoService = todoService;
        this.chatRunBus = chatRunBus;
        this.toolSpillService = toolSpillService;
        this.toolPipeline = toolPipeline;
        this.toolRegistry = toolRegistry;
        this.permissionPolicyResolver = permissionPolicyResolver;
        this.runTokens = runTokens;
        this.hitl = hitl;
        this.runtime = runtime;
        this.subCall = subCall;
        this.branch = branch;
        this.historyCompaction = historyCompaction;
        this.deepThinkService = deepThinkService;
        this.chatflow = chatflow;
        this.plan = plan;
        this.citations = citations;
        this.retrieval = retrieval;
        this.recorder = recorder;
        this.chatEngines = chatEngines;
        this.retrievalScope = retrievalScope;
        this.toolAssembly = toolAssembly;
        this.planHooks = new com.wenqu.ai.agent.plan.PlanModeService.PlanTurnHooks() {
            @Override
            public java.util.List<org.springframework.ai.tool.ToolCallback> mountTools(
                    com.wenqu.ai.model.Agent agent, TurnState st) {
                return enabledToolCallbacks(agent, st.userId, st);
            }

            @Override
            public boolean todoExposed(com.wenqu.ai.model.Agent agent, TurnState st) {
                return ChatService.this.todoExposed(st.guestMode, agent, st.branchTurn);
            }

            @Override
            public void recordToolStatus(TurnState st, String name, String input, String status,
                                         String resultOrError, long elapsedMs, int attempts, boolean denied) {
                // 工具卡与时间线的记录只有一处实现，在 TurnRecorder（计划前的澄清卡走的就是这条管道）
                recorder.recordToolStatus(st, name, input, status, resultOrError, elapsedMs, attempts, denied);
            }

            @Override
            public String askUser(TurnState st, java.util.List<BuiltinTools.AskQuestion> questions) {
                return ChatService.this.doAskUserMulti(st, questions);
            }
        };
        // 本轮状态的依赖清单：必须在上面全部赋值之后再建（它要引用这些服务）。
        // 刀 5c 之后它只剩两件事：问总线「本轮还有观众吗」+ 半程正文落库（交方法引用，那层 TurnRuntime 动作接口退场）
        this.turnDeps = new TurnState.Deps(chatRunBus, recorder::persistPartialAnswer);
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
                    // legacy 那一支不能摘：它的 runChat 在 subscribe 后就返回，生成还在响应式线程上跑，
                    // 由 TurnState#claimTerminal 在真收尾时摘。
                    TurnRegistry.removeHandle(sessionId);
                    chatRunBus.finish(sessionId);
                }
                } finally {
                    // 轮次登记（TURN_STATES / TURN_HANDLES）刻意不在这里摘：runChat 在 subscribe 后就
                    // 返回了，生成与工具循环此后跑在响应式线程上，这里摘会让「停止本轮」找不到正在出字
                    // 的那一轮。摘除统一挂在 TurnState#claimTerminal()（本轮真收尾那一刻）。
                    // 反过来，前置阶段叫停的轮次要等检查点才退场，所以「这一问没有回答」的标记
                    // 只能在本线程走到底时打——早一步判会抢在落库之前，晚一步这里已经出不来了。
                    // 断线宽限到点的中止也在这一并判：那条路径由总线线程 dispose，把手当场就没了，
                    // 只看 runtime.turnStopRequested 会漏打标记，用户下次进来读到的是「问出去了却什么都没有」的空档。
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


    /**
     * 判断并返回本次问答启用的工具回调列表（统一 ToolCallback 形态）。
     * 仅当 tool.enabled（总开关）开启时才暴露工具；各子工具开关决定具体暴露哪些。
     * MCP 工具取自当前用户名下的 server（外部 server 连接失败自动跳过）。
     * 内置 @Tool 对象经 ToolCallbacks.from() 转成 MethodToolCallback——注意 ChatClient 的
     * .tools() 只接受 @Tool 注解对象，传 ToolCallback 实例会抛 IllegalStateException
     * （"No @Tool annotated methods found... use .toolCallbacks() instead"），故统一走 .toolCallbacks()。
     * 全部关闭时返回空列表（等价未配置工具，零侵入）。
     */
    /**
     * 启用工具列表（按智能体覆盖）。智能体工具开关为三态：agent 中显式设了 1/0 则强制覆盖，
     * 否则继承全局 tool.* 开关。工具总开关 tool.enabled 仍由全局控制（智能体不开关总闸）。
     * 技能与 MCP 已于 2026-09-26 下沉为个人资产，因此按 userId 取：只读**这个人**的技能、只连**这个人**的 MCP。
     */
    private java.util.List<org.springframework.ai.tool.ToolCallback> enabledToolCallbacks(Agent agent, String userId,
                                                                                          TurnState st) {
        java.util.List<org.springframework.ai.tool.ToolCallback> callbacks = new ArrayList<>(4);
        java.util.Set<String> sensitiveTools = new java.util.HashSet<>();
        if (!configService.getBoolean("tool.enabled")) {
            st.tools.sensitiveToolNames = sensitiveTools;
            return callbacks;
        }
        // 挂不挂由各族自己判（绞杀式中）：内置/检索/联网/产物/技能/任务清单六族已迁到注册中心，
        // 装配处只剩「问每个族本轮要挂什么」。检索-反思循环的强制暴露、智能体三态覆盖、
        // 具体项白名单（agent.builtinTools）、游客可见性都落在 provider 与描述符的 guestAllowed 上——
        // 迁移前这几条在游客分支与常规分支各抄了一份，于是「管理员收紧一处」时另一处照旧放行。
        ToolMountContext mc = toolMountContext(agent, userId, st);
        java.util.Set<String> mcpNames = new java.util.HashSet<>();
        for (ToolFamilyProvider p : toolRegistry.providersInFamilyOrder()) {
            java.util.List<org.springframework.ai.tool.ToolCallback> mounted = p.callbacks(mc);
            // 「本轮哪些工具来自 MCP」在这里按族收集（MCP 结果要注册引用来源，tool.mcpCiteEnabled），
            // 不让 provider 反向写问答侧的状态对象
            if (p.family() == ToolFamily.MCP) {
                for (org.springframework.ai.tool.ToolCallback cb : mounted) {
                    mcpNames.add(cb.getToolDefinition().name());
                }
            }
            callbacks.addAll(mounted);
        }
        if (!mcpNames.isEmpty()) st.tools.mcpToolNames = java.util.Set.copyOf(mcpNames);
        // 八族全部由 provider 装配（P1 seam 化的装配面到此收口）：游客收窄看描述符 guestAllowed、
        // 审批=off 的整族禁用看 ToolFamily.blockedWhenApprovalOff、开关归属与具体项白名单看各族 exposes，
        // MCP 的个人资产语义看 McpToolProvider。这里不再留任何一族的 if——迁移时最贵的错就是
        // 「provider 加一段、老分支忘删」（第二刀实际发生过 webSearch 双挂，靠去重守卫喊出来）。
        if (st.guestMode) {
            // 保留一条可 grep 的游客标记（只挂了什么由下一行那条公共日志逐字列出）
            log.info("[TOOL] 游客分享会话：只挂描述符声明 guestAllowed 的能力");
        }
        finishMounts(callbacks, sensitiveTools, st);
        return callbacks;
    }

    /**
     * 装配收尾（游客与常规两条路径共用；迁移前收尾只在常规路径跑、游客分支自己 log 一句就 return，
     * 于是游客拿不到 DSML 剥离白名单——那条差异不是设计，是复制分支时漏带的）。
     * 做三件事：同名工具去重、本轮审批名单、DSML 剥离白名单 + 挂载日志。
     * <p>去重是族迁移期的安全网：一族同时被 provider 和残留的老分支挂上时，模型的工具表里会出现两个
     * 同名项——Spring 不报错、各厂商行为不一，症状是「模型偶尔对着同一个工具反复调」，从回答里几乎
     * 看不出成因，所以宁可在这里喊出来。
     */
    private void finishMounts(java.util.List<org.springframework.ai.tool.ToolCallback> callbacks,
                              java.util.Set<String> sensitiveTools, TurnState st) {
        java.util.Set<String> seenNames = new java.util.LinkedHashSet<>();
        java.util.List<String> duplicated = new ArrayList<>(1);
        callbacks.removeIf(cb -> {
            String n = cb.getToolDefinition().name();
            if (seenNames.add(n)) return false;
            duplicated.add(n);
            return true;
        });
        if (!duplicated.isEmpty()) {
            log.error("[TOOL] 同名工具被重复挂载，已去重（族迁移期装配口径重叠，请把老分支删干净）: {}", duplicated);
        }
        // 本轮「有副作用工具」名单由注册中心的描述符判定，不再在装配处逐个 add：
        // 沙盒族恒需确认、联网搜索看 webSearch.requireApproval、内置/检索/产物/技能/清单不需要；
        // **没在注册中心登记的工具一律需要确认**（当前即 MCP 外部工具）——外部 server 的工具名前缀是
        // 用户自己起的 clientInfo 名，靠命名规律识别会漏，漏一次的结果是跳过审批，不是少问一句。
        // 此前这条规则在装配处 add 一遍、清单页硬编码一遍、前端再抄一遍，三份各自演进的直接后果
        // 是新工具的审批属性看不出来、中文名丢失。
        for (org.springframework.ai.tool.ToolCallback cb : callbacks) {
            String n = cb.getToolDefinition().name();
            if (toolRegistry.requiresApproval(n, configService::getBoolean)) {
                sensitiveTools.add(n);
            }
        }
        // 记录本轮"有副作用"的工具名单（沙盒/MCP）：智能体 toolApprovalMode=ask 时执行前需用户确认
        st.tools.sensitiveToolNames = sensitiveTools;
        // DSML 剥离白名单：本轮真实下发的工具名（小写）。部分模型在 Function Calling 之外还会把
        // 工具调用以 <searchKnowledge><parameter …></parameter></searchKnowledge> 的 DSML 文本再输出
        // 一遍（工具卡片已由 native tool_calls 生成，文本那份是重复产物），不剥离会原样漏给用户。
        // 排除与 HTML/XML 标准标签同名的工具（MCP 工具名由外部 server 自定，可能叫 link/form/table）：
        // 那类名字按标签剥会误伤模型正常写的 HTML/XML 正文，宁可漏剥离也不能吃掉用户内容。
        st.tools.dsmlToolNamesLc = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .filter(java.util.Objects::nonNull)
                .map(n -> n.toLowerCase())
                .filter(n -> !HTML_TAG_NAMES.contains(n))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        // 可观测性：本次问答暴露了哪些工具（空则不打印；模型调不调用由模型决策，但"挂了什么"要可见）
        if (!callbacks.isEmpty()) {
            String names = callbacks.stream()
                    .map(cb -> cb.getToolDefinition().name())
                    .collect(java.util.stream.Collectors.joining(", "));
            log.info("[TOOL] 本次启用 {} 个工具: [{}]", callbacks.size(), names);
        }
    }


    /**
     * 档位解析（装配判据与执行守卫的唯一入口）。
     * <p>「档位 → 智能体的哪一列」这条映射留在问答侧，与 {@link #agentFlagFor} 同一口径：
     * 通用策略层不该认识业务实体。
     */
    private com.wenqu.ai.agent.permission.ExecutionPolicy resolvePermissionPolicy(
            Agent agent, boolean guestMode, java.util.function.BiConsumer<String, String> onClamp) {
        return toolAssembly.resolvePermissionPolicy(agent, guestMode, onClamp);
    }

    /**
     * 本轮的工具装配上下文：把智能体配置、全局开关与游客态交给各族自己判挂不挂。
     * <p>「族 → c_ai_agent 的哪一列」这条映射留在问答侧：Agent 是业务实体，通用装配层不该认识它，
     * 否则下一族想加开关时得先改通用层。
     */
    private ToolMountContext toolMountContext(Agent agent, String userId, TurnState st) {
        return mountContext(agent, userId, st.guestMode, st.reflectiveRetrieval, st.branchTurn,
                (code, message) -> runtime.addDegradation(st.degradations, st.degradedCodes, code, message),
                st.tools.permissionPolicy);
    }

    /**
     * 本轮的工具装配判据上下文。
     * <p>提示词侧（能力分工段、已批准计划落成清单的那句提示）也走这里问「这个工具本轮挂不挂」——
     * 两处必须同一份判据，否则会出现「提示词教模型用某个工具，工具表里却没有它」。
     * <p>提示词侧只问判据、不装配回调：userId 传空串不影响任何判据，降级出口用 no-op
     * （今天只有 MCP 族会报降级，而它只在装配回调时被调用）。
     */
    private ToolMountContext mountContext(Agent agent, String userId, boolean guestMode, boolean reflective) {
        return toolAssembly.mountContext(agent, userId, guestMode, reflective);
    }

    /**
     * @param turnPolicy 本轮已解析的权限档位（问答侧传 {@code st.tools.permissionPolicy}，与守卫读同一份）；
     *                   null = 现算（提示词侧那条路径没有本轮状态对象）
     * @param branchTurn 本轮是否为子智能体分支（P3a）：分支里「要有人在场」的工具（提问卡、任务清单）
     *                   不进工具表，判据与装配同一条 {@link ToolMountContext#turnAllows}
     */
    private ToolMountContext mountContext(Agent agent, String userId, boolean guestMode, boolean reflective,
                                          boolean branchTurn,
                                          java.util.function.BiConsumer<String, String> degradationSink,
                                          com.wenqu.ai.agent.permission.ExecutionPolicy turnPolicy) {
        return toolAssembly.mountContext(agent, userId, guestMode, reflective, branchTurn, degradationSink, turnPolicy);
    }

    /** 族 → 智能体的「具体项」选择：实现见 {@link ToolAssemblyService}（判据只有一处） */
    private java.util.Set<String> agentSelectionFor(ToolFamily family, Agent agent) {
        return toolAssembly.agentSelectionFor(family, agent);
    }

    /** 族 → 智能体级三态列（null = 本族没有智能体级开关，跟随全局） */
    private Integer agentFlagFor(ToolFamily family, Agent agent) {
        return toolAssembly.agentFlagFor(family, agent);
    }


    // ==================== P3b：沙盒脚本的子调用回程 ====================

    /**
     * 本轮的工具执行上下文：把 TurnState 的问答态映射成 {@link ToolTurnContext} 声明的
     * 十余件事。
     * <p>
     * <b>身份快照在本方法（入口线程）捕获</b>：工具实际执行在 Reactor boundedElastic 线程上，
     * RequestUser 是流水线线程装载的 ThreadLocal，跨线程读不到会回落 anonymous——对检索类工具
     * 这不是安全降级而是功能损坏（库门恒假 → 召回 0 并耗尽工具预算，线上实测过）。
     * 放在执行点捕获同样无效，那里已经在弹性线程上。见 {@link #callWithRetry}。
     */
    private ToolTurnContext toolTurnContext(TurnState st) {
        final com.wenqu.ai.util.RequestUser.Snapshot identity = com.wenqu.ai.util.RequestUser.snapshot();
        // 本轮检索参数覆盖（库级 + 智能体级）与个人配置覆盖装在同一个 ThreadLocal 盲区里：
        // 不带进工具线程，配置页上那份 retrieval.* 对 searchKnowledge 一律静默失效，
        // 实测表现是「主链路命中并引用、工具侧恒空」——同一轮里两套检索参数，谁都没报错。
        final Map<String, String> cfgOverrides = configService.currentOverrides();
        final Map<String, String> userOverrides = configService.currentUserOverrides();
        // 本轮基础上下文：会话/用户归属 + askUser 执行器 + 任务清单写入器 + 大输出外溢信箱
        final Map<String, Object> baseCtx = java.util.Collections.unmodifiableMap(toolContext(st));
        // 按族的上下文注入：本轮落点随本次调用传递，工具侧不再读任何跨调用的静态状态
        // （迁移前这三处分别是 setSourceRegistrar/setKbScope/setReflective、setSink，
        //  以及沙盒那两个 per-call 回调——全部收进这里，作用域=本次调用）
        final Map<ToolFamily, ToolEnrichment> enrichments = Map.of(
                ToolFamily.KNOWLEDGE, inv -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    // 引用注册器：命中块注册进当前流 sources 续编引用编号，工具文本改【引用N】提示
                    // 模型按编号标注，前端角标悬浮/引用弹窗因此可溯源
                    m.put(KnowledgeRetrievalTool.CTX_SOURCE_REGISTRAR, new KnowledgeRetrievalTool.SourceRegistrar() {
                        @Override
                        public KnowledgeRetrievalTool.SourceRegistrar.Registration register(
                                HybridRetrievalService.Hit h, String snippet) {
                            return st.refs.registerToolSource(h, snippet);
                        }

                        @Override
                        public void degrade(String code, String msg) {
                            // 工具链路（重排不可用等）可写回本轮 degradations，与主链路同一份列表、同一展示位
                            runtime.addDegradation(st.degradations, st.degradedCodes, code, msg);
                        }
                    });
                    // 检索范围：工具与主链路同库界，不得越过智能体知识库绑定去检索其他库
                    m.put(KnowledgeRetrievalTool.CTX_KB_SCOPE,
                            new KnowledgeRetrievalTool.KbScope(st.tools.toolScopeKbIds, st.tools.toolScopeDocIds));
                    m.put(KnowledgeRetrievalTool.CTX_REFLECTIVE, st.reflectiveRetrieval);
                    return m;
                },
                // 联网搜索的落点=本轮引用池（刀 0 第二步后 sink 由 TurnSourceRegistry 实现，
                // 不再是整个状态袋——工具拿不到本轮的任何其它字段，也就没法往别处写）
                ToolFamily.WEBSEARCH, inv -> Map.of(WebSearchTools.CTX_SINK, st.refs),
                ToolFamily.SANDBOX, inv -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    // 本轮权限档位（P2）：沙盒工具据此判定这次写入是否落在允许区内。走上下文而不是让
                    // 工具去查配置——档位是「这一轮」的属性，同一会话的两轮可以不同档
                    m.put(SandboxTools.CTX_SANDBOX_POLICY, st.tools.permissionPolicy);
                    // run_code 的子调用回程（P3b）：只在这一轮真挂了 run_code 时才有内容，
                    // 且必须在执行侧现取——本轮的可调用清单在 st.tools.subCallTools 里，装配期还没建好
                    boolean runCode = SandboxTools.RUN_CODE_TOOL_NAME.equals(inv.toolName());
                    if (runCode) subCall.startRunBoard(st);
                    if (runCode) {
                        m.put(SandboxTools.CTX_RUN_CODE_BRIDGE, subCall.runCodeBridge(st));
                    }
                    // 输出增量回调：execute 检测到它即走流式执行（后台脱离+轮询），增量经
                    // recordToolOutput 转 SSE，前端「看着它跑」。
                    // <p>run_code 必须走同一条：它此前不在这份名单里，于是一段脚本从起跑到结束
                    // 屏幕上一个字都没有——扇出 30 份资料时那就是两三分钟的纯转圈，
                    // 用户的读法是"卡死了"，然后点停止把活儿掐掉。
                    if (SandboxTools.STREAMING_TOOL_NAME.equals(inv.toolName()) || runCode) {
                        m.put(SandboxTools.CTX_OUTPUT_SINK,
                                (java.util.function.Consumer<String>) delta ->
                                        recorder.recordToolOutput(st, inv.toolName(), delta));
                    }
                    // 基础设施故障信箱对全部沙盒工具注入：沙盒不可用时工具不抛异常（抛出会掐断整轮流、
                    // 连带已生成正文与全部工具记录一起落空），而是把失败文本回给模型 + 经此信箱上报，
                    // 让这条记录落成 error 而不是绿色对勾的 done。信箱挂在本次调用上，同名工具并发也各是各的
                    m.put(SandboxTools.CTX_INFRA_ERROR_SINK,
                            (java.util.function.Consumer<String>) msg -> inv.recordFailure(msg));
                    return m;
                });
        return new ToolTurnContext() {
            @Override
            public String sessionId() {
                return st.sessionId;
            }

            @Override
            public String userId() {
                return st.userId;
            }

            @Override
            public String turnId() {
                return st.turnId;
            }

            @Override
            public boolean userStopped() {
                return st.userStopped;
            }

            @Override
            public int maxToolSteps() {
                return st.tools.maxToolSteps;
            }

            @Override
            public int stepsTaken() {
                return st.tools.toolStepCount.get();
            }

            @Override
            public void takeStep() {
                st.tools.toolStepCount.incrementAndGet();
            }

            @Override
            public String approvalMode() {
                return st.tools.toolApprovalMode;
            }

            @Override
            public boolean requiresApproval(String toolName) {
                return st.tools.sensitiveToolNames != null && st.tools.sensitiveToolNames.contains(toolName);
            }

            @Override
            public long approvalTimeoutMs() {
                // 必须点名取外部服务的方法：匿名类里裸写 approvalTimeoutMs() 解析到的是本方法自己，
                // 审批闸一读等待上限就无限递归把工具线程栈打爆（ApprovalGate 调它即在「每次问我」档必炸）
                return hitl.approvalTimeoutMs();
            }

            @Override
            public ToolApprover approver() {
                // 子智能体分支（P3a）没有可应答通道：那条临时会话没人开着，审批卡发不出去、
                // 铃铛会指向一条跑完就删的会话，而工具线程要白等 chat.approvalTimeoutMs（默认 120s）。
                // 返回 null 走的正是 ApprovalGate 已写明的「问不到就直接拒，理由写明无人可确认」那条，
                // 覆盖装配挡不住的 MCP 动态工具（它们没有静态描述符，族级裁剪够不着名字）。
                if (st.branchTurn) return null;
                return (inv, timeoutMs) -> requestToolApproval(st, inv, timeoutMs);
            }

            @Override
            public com.wenqu.ai.agent.permission.ExecutionPolicy permissionPolicy() {
                return st.tools.permissionPolicy;
            }

            @Override
            public Map<String, Object> baseToolContext() {
                return baseCtx;
            }

            @Override
            public Map<ToolFamily, ToolEnrichment> enrichments() {
                return enrichments;
            }

            @Override
            public String execute(org.springframework.ai.tool.ToolCallback raw, String arguments,
                                  Map<String, Object> context, ToolInvocation inv, int[] attemptsOut) {
                return callWithRetry(raw, arguments,
                        new org.springframework.ai.chat.model.ToolContext(context),
                        inv.toolName(), attemptsOut, identity, cfgOverrides, userOverrides);
            }

            @Override
            public void observe(ToolInvocation inv, String status, String resultOrError) {
                boolean starting = "start".equals(status);
                recorder.recordToolStatus(st, inv.toolName(), inv.arguments(), status, resultOrError,
                        starting ? 0 : inv.elapsedMillis(), starting ? 1 : inv.attempts(), inv.denied());
            }

            @Override
            public void onOutput(ToolInvocation inv, String delta) {
                recorder.recordToolOutput(st, inv.toolName(), delta);
            }

            @Override
            public String decorateResult(ToolInvocation inv, String result) {
                // MCP 外部工具结果注册引用来源（tool.mcpCiteEnabled）：模型依据 MCP 内容作答时正文才有
                // 可点溯源的 [N]，否则是悬空角标（引用面板无对应条目）
                if (st.tools.mcpToolNames != null && st.tools.mcpToolNames.contains(inv.toolName())) {
                    return st.refs.registerMcpCitations(inv.toolName(), result);
                }
                return result;
            }

            @Override
            public String appendSteers(ToolInvocation inv, String result) {
                String steered = runtime.drainSteers(st);
                return steered.isEmpty() ? result : result + "\n\n" + steered.trim();
            }
        };
    }

    /**
     * 有副作用工具的执行确认（人在回路的问人那一段：落库审计、站内通知、SSE 审批卡、阻塞等待）。
     * <p>
     * 定性交给 {@link ToolApprover.Outcome}，不再是一个 boolean：拒绝、超时、无人可确认在迁移前都
     * 收敛成同一个 false，于是「审批卡没人点」与「用户点了拒绝」在日志和文案里分不开，
     * 用户读到的是一句含糊的「未批准」。
     * <p>
     * 阻塞等待有界（chat.approvalTimeoutMs，默认 120s）；超时/拒绝都<b>不代用户批准</b>，
     * 由守卫把原因回给模型，让它基于已有信息继续而不是反复重试。
     */
    private ToolApprover.Outcome requestToolApproval(TurnState st, ToolInvocation inv, long timeoutMs) {
        String name = inv.toolName();
        String toolInput = inv.arguments();
        String approvalId = inv.callId();
        // 挂起这张审批卡（P6 刀 1：落 PENDING 记录与登记唤醒句柄都在 HitlDecisionService 一次做完）。
        // 句柄是 AutoCloseable：离开作用域即摘登记，不会再出现「解了 future 却漏摘登记」那种半成品。
        try (var wait = hitl.openApproval(approvalId, st.sessionId, st.userId, st.turnId, name, toolInput)) {
            // 审批待决站内通知（旁路）：SSE 只能触达正开着这个会话页的人，人不在页面时铃铛是唯一可感知面。
            // 超时/裁决后通知不撤（留在列表里作审计痕迹，点了即已读）。
            notificationService.create(st.userId, com.wenqu.ai.model.Notification.TYPE_TOOL_APPROVAL,
                    "工具执行待确认：" + name,
                    "智能体正在请求执行有副作用工具「" + name + "」，请在会话中确认或拒绝（超时未确认按拒绝处理）。",
                    "session", st.sessionId, "tool:" + approvalId, approvalId);
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("approvalId", approvalId);
            req.put("tool", name);
            // 中文名来自注册中心：审批卡上写「execute」用户看不懂，而前端那份手抄映射对 MCP 动态
            // 工具和无声明工具一律失效，下发之后前端只兜底、不再是唯一来源
            toolRegistry.putDisplayFields(req, name);   // 中文名取注册中心，与工具卡同源（见 TurnRecorder）
            String args = toolInput == null ? "" : toolInput;
            req.put("args", args.length() > 2000 ? args.substring(0, 2000) + "…" : args);
            req.put("timeoutMs", timeoutMs);
            runtime.sendSseEvent(st.channel.emitter, "approval_required", JSON.toJSONString(req), st.sessionId);
            log.info("[TOOL] 等待用户确认: tool={} approvalId={} session={}", name, approvalId, st.sessionId);
            boolean approved;
            ToolApprover.Outcome timeoutOutcome = ToolApprover.Outcome.TIMED_OUT;
            TurnRegistry.beginHumanWait(st.channel.emitter);   // 用户确认时间不计入整轮机器预算（见 TurnDeadline）
            try {
                approved = wait.future().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException ee) {
                approved = false;
                timeoutOutcome = ToolApprover.Outcome.REJECTED;
            } catch (java.util.concurrent.TimeoutException te) {
                approved = false;
                hitl.markApprovalResolved(approvalId, "TIMEOUT", st.userId);
                log.warn("[TOOL] 审批超时，按拒绝处理: tool={} session={}", name, st.sessionId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                approved = false;
                timeoutOutcome = ToolApprover.Outcome.CANCELLED;
            } finally {
                TurnRegistry.endHumanWait(st.channel.emitter);
            }
            if (!approved) {
                return timeoutOutcome;
            }
            log.info("[TOOL] 用户已批准: tool={} session={}", name, st.sessionId);
            return ToolApprover.Outcome.ALLOWED;
        }
    }


    // ==================== 工具执行心跳 ====================


    // ==================== 工具调用瞬时故障重试 ====================
    // （刀 2 把心跳与存活看门狗本体搬进 ChatRunRuntime 之后，这个章节位上剩的是重试包装；
    //  标题按内容改直，别再用旧横幅误导"看门狗还在这里"）

    /**
     * 工具调用瞬时故障自动重试：失败后 500ms 重试一次（共至多 2 次），attempts 记录实际尝试次数。
     * 场景是沙盒容器冷启动、网络抖动这类瞬时故障；确定性错误（路径不存在等）多付一次 500ms 代价可接受
     * ——业务层的"错误输出"（如命令退出码非 0）不是异常，不会触发重试。模型看到的只有终态与 attempts。
     * 注意不声明 throws：ToolCallback.call 只抛运行时异常，包装 checked 会破坏调用方 precise-rethrow。
     */
    private String callWithRetry(org.springframework.ai.tool.ToolCallback cb, String toolInput,
                                 org.springframework.ai.chat.model.ToolContext toolContext,
                                 String name, int[] attempts,
                                 com.wenqu.ai.util.RequestUser.Snapshot identity,
                                 Map<String, String> overrides, Map<String, String> userOverrides) {
        // 工具执行发生在 Reactor boundedElastic 线程（Spring AI ToolCallAdvisor 对阻塞工具 subscribeOn），
        // 而 RequestUser 用裸 ThreadLocal 只在入口线程可见 → 工具内读身份会回落 anonymous。
        // 对检索类工具这不是安全降级而是功能损坏：loadVisibleKbIds() 判 anonymous 为「什么都读不到」
        // → 库门表达式恒假 → 融合召回 0（线上实测：主链路命中 31 块，工具侧恒空并耗尽 15 步工具预算）。
        // identity 由 instrumentTools 在入口线程捕获后传入——不能在执行点临时 snapshot()，
        // 那里已经在弹性线程上、捕获到的只会是 anonymous（实测修复无效）。
        // 本轮检索参数覆盖（ConfigService 的 OVERRIDE/USER_OVERRIDE 两个 ThreadLocal）同一个盲区、
        // 同一种损坏：库级/智能体级 retrieval.* 不带进来，工具就按全局参数检索，
        // 于是「主链路按 0.6 关键词权重命中、工具按 0.2 把它压出重排窗口」——两份参数同一个会话。
        // 统一恢复：一次覆盖全部工具（检索/沙盒/MCP/工作流），避免逐工具打补丁。
        RuntimeException last = null;
        for (int i = 0; i < 2; i++) {
            if (i > 0) {
                log.warn("[TOOL] {} 第 1 次调用失败，500ms 后自动重试: {}", name,
                        last == null ? "" : last.getMessage());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
            attempts[0]++;
            // restore() 返回的句柄用于 finally 复原：弹性线程会被复用，不清理会污染后续任务的身份
            try (AutoCloseable ignored = com.wenqu.ai.util.RequestUser.restore(identity)) {
                // 本轮配置覆盖同样要显式带过来，并复原成「进入前的那一份」——putOverrides 是合并语义，
                // 所以先 clear 再装回 prev，直接 putOverrides(prev) 会把本轮的键留给下一个任务
                Map<String, String> prev = configService.currentOverrides();
                Map<String, String> prevUser = configService.currentUserOverrides();
                configService.putOverrides(overrides);
                configService.putUserOverrides(userOverrides);
                try {
                    return cb.call(toolInput, toolContext);
                } finally {
                    configService.clearOverride();
                    configService.clearUserOverrides();
                    if (!prev.isEmpty()) configService.putOverrides(prev);
                    if (!prevUser.isEmpty()) configService.putUserOverrides(prevUser);
                }
            } catch (Exception e) {
                last = e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
            }
        }
        throw last;
    }



























    /** tail 是否为四个标签中任一标签的真前缀（含单纯的 "<"） */
    private static boolean isTagPrefix(String tail) {
        return (tail.length() < "<related>".length() && "<related>".startsWith(tail))
                || (tail.length() < "</related>".length() && "</related>".startsWith(tail))
                || (tail.length() < "<process>".length() && "<process>".startsWith(tail))
                || (tail.length() < "</process>".length() && "</process>".startsWith(tail));
    }

    /**
     * 判断字符串中是否存在未完整闭合的 related 块（开始/闭合标签的跨 token 片段也算）
     * package-private static：纯字符串逻辑，供单元测试直接验证（滑动窗口缓冲依赖此判定）
     */
    static boolean containsUnclosedRelated(String s) {
        if (s.contains("</related>")) {
            // 已有关闭标签：剔除完整块后，剩余部分若还有 related 痕迹则视为未闭合
            String rest = s.replaceAll("<related>[\\s\\S]*?</related>", "");
            return rest.contains("<related") || isRelatedStart(rest) || isRelatedEndStart(rest);
        }
        return s.contains("<related") || isRelatedStart(s) || isRelatedEndStart(s);
    }

    /**
     * 判断字符串末尾是否为 <related> 标签的部分前缀（捕获跨 token 分割的开始标签）
     */
    private static boolean isRelatedStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "<related>".length() && "<related>".startsWith(tail);
    }

    /**
     * 判断字符串末尾是否为 </related> 标签的部分前缀（捕获跨 token 分割的闭合标签）
     */
    private static boolean isRelatedEndStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "</related>".length() && "</related>".startsWith(tail);
    }

    /**
     * 判断字符串中是否存在未完整闭合的 process 块（与 containsUnclosedRelated 同规则，标签族不同）
     */
    static boolean containsUnclosedProcess(String s) {
        if (s.contains("</process>")) {
            // 已有关闭标签：剔除完整块后，剩余部分若还有 process 痕迹则视为未闭合
            String rest = s.replaceAll("<process>[\\s\\S]*?</process>", "");
            return rest.contains("<process") || isProcessStart(rest) || isProcessEndStart(rest);
        }
        return s.contains("<process") || isProcessStart(s) || isProcessEndStart(s);
    }

    /** 判断字符串末尾是否为 <process> 标签的部分前缀（捕获跨 token 分割的开始标签） */
    private static boolean isProcessStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "<process>".length() && "<process>".startsWith(tail);
    }

    /** 判断字符串末尾是否为 </process> 标签的部分前缀（捕获跨 token 分割的闭合标签） */
    private static boolean isProcessEndStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "</process>".length() && "</process>".startsWith(tail);
    }

    // ==================== DSML 工具调用文本剥离 ====================
    // 部分模型在 native tool_calls 之外，还会把同一次工具调用以 DSML 文本再写一遍正文：
    //   <searchKnowledge><parameter name="query">…</parameter></searchKnowledge>
    // 工具卡片已经由 native 链路渲染过，这份文本是纯重复产物，不剥离就会以标签字样漏给用户
    // （线上症状：正文里出现 <searchknowledge> / </parameter> 等裸露标签）。
    // 只按「本轮真实下发的工具名」匹配，不做通用 XML 剥离——技术问答里模型正常写的
    // HTML/XML 正文（尤其代码块内的标签）不能被吃掉。

    /** 参数标签（DSML 的参数承载形式），整体含开闭：<parameter …>…</parameter> */
    private static final Pattern dsmlParamPattern =
            Pattern.compile("<parameter\\b[^>]*>[\\s\\S]*?</parameter>", Pattern.CASE_INSENSITIVE);
    /** 残缺参数标签：未闭合的开口与跨 token 切碎的闭合（输出触顶截断时常见） */
    private static final Pattern dsmlParamLoosePattern =
            Pattern.compile("</?parameter\\b[^>]*>?", Pattern.CASE_INSENSITIVE);
    /**
     * HTML/XML 常见标准标签名（小写）：与工具名同名时不做 DSML 剥离。
     * MCP 工具名由外部 server 自定，可能撞上这些名字——按标签剥会吃掉模型正常写的正文。
     * 这些名字本来也不像 DSML 工具名（DSML 惯例是驼峰），排除掉对实际剥离效果无损失。
     */
    private static final java.util.Set<String> HTML_TAG_NAMES = java.util.Set.of(
            "a", "b", "br", "div", "p", "span", "link", "meta", "form", "input", "button",
            "table", "tr", "td", "th", "thead", "tbody", "ul", "ol", "li", "dl", "dt", "dd",
            "h1", "h2", "h3", "h4", "h5", "h6", "img", "code", "pre", "section", "article",
            "header", "footer", "nav", "aside", "main", "style", "script", "title", "head",
            "body", "html", "label", "select", "option", "textarea", "iframe", "video", "audio",
            "canvas", "svg", "path", "g", "text", "circle", "rect", "defs", "use", "symbol");

    /**
     * 剥离正文里的 DSML 工具调用文本（按本轮工具名白名单，忽略大小写）。
     * 分两轮：先整块（<tool …>…</tool>）后参数标签，避免块内多个 parameter 时残留标签字样。
     *
     * @param s     待清洗文本（一批 token 或收尾缓冲）
     * @param names 本轮工具名小写集合；空集合直接原样返回（无工具的纯对话不受影响）
     * @return 清洗后的文本
     */
    static String stripDsmlToolText(String s, java.util.Set<String> names) {
        if (s == null || s.isEmpty() || names == null || names.isEmpty()) return s;
        String out = s;
        // 整块：<tool>…</tool>。工具名逐个转义后拼正则（MCP 工具名可能含正则元字符）
        for (String n : names) {
            String q = java.util.regex.Pattern.quote(n);
            out = out.replaceAll("(?i)<" + q + "\\b[^>]*>[\\s\\S]*?</" + q + "\\s*>", "");
            // 未闭合的开口（截断在 </tool> 之前）：开口起整段丢弃，否则标签与参数值会露屏
            int lt = indexOfIgnoreCase(out, "<" + n);
            if (lt >= 0) {
                // 确认是标签开口而非正文里的同名片段（如 "<searchknowledge 很有用"）
                int gt = out.indexOf('>', lt);
                if (gt < 0 || gt - lt <= 1 + n.length() + 1) out = out.substring(0, lt);
            }
        }
        out = dsmlParamPattern.matcher(out).replaceAll("");
        out = dsmlParamLoosePattern.matcher(out).replaceAll("");
        return out;
    }

    /** 忽略大小写查找子串下标，找不到返回 -1 */
    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase().indexOf(needle.toLowerCase());
    }

    /**
     * 丢弃首个未闭合 DSML 工具调用块自开口起的全部内容（截断兜底）：
     * 返回首个未闭合开口之前的前缀。若没有未闭合块则原样返回。
     */
    static String dropUnclosedDsmlTail(String s, java.util.Set<String> names) {
        if (s == null || s.isEmpty() || names == null || names.isEmpty()) return s;
        String low = s.toLowerCase();
        int cut = -1;
        for (String n : names) {
            int oi = low.indexOf("<" + n);
            while (oi >= 0) {
                int after = oi + 1 + n.length();
                boolean boundary = after >= low.length();
                if (!boundary) {
                    char c = low.charAt(after);
                    boundary = !Character.isLetterOrDigit(c) && c != '_';
                }
                if (boundary && low.indexOf("</" + n, oi) < 0) {
                    cut = cut < 0 ? oi : Math.min(cut, oi);
                    break;
                }
                oi = low.indexOf("<" + n, oi + 1);
            }
        }
        return cut < 0 ? s : s.substring(0, cut);
    }

    /**
     * 文本里是否存在未闭合的 DSML 工具调用块（含跨 token 切分的标签片段）。
     * 用于流式缓冲：出现这种块要继续缓冲等它闭合，闭合后整块丢弃——
     * 否则闭合标签到达前已经把开口和参数值当正文发出去了（正文里出现半截标签）。
     */
    static boolean containsUnclosedDsml(String s, java.util.Set<String> names) {
        if (s == null || s.isEmpty() || names == null || names.isEmpty()) return false;
        String low = s.toLowerCase();
        for (String n : names) {
            String open = "<" + n;
            String close = "</" + n;
            int oi = low.indexOf(open);
            if (oi < 0) continue;
            // 开口后必须紧跟标签边界（空白 / > / /），否则是正文里的同名片段
            int after = oi + open.length();
            if (after < low.length()) {
                char c = low.charAt(after);
                if (Character.isLetterOrDigit(c) || c == '_') continue;
            }
            if (low.indexOf(close, oi) < 0) return true;
        }
        return false;
    }



    // ---- 智能体（P5）本轮作答方解析 ----

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


    /**
     * 内置工具本轮是否真的挂得上：本族开关（全局/智能体三态）+ 智能体的「具体项白名单」两道都过才算。
     * <p>判据本体在 {@code BuiltinToolProvider.exposes}，这里只是问答侧的查询口——装配与提示词
     * 共用同一份，否则会出现「提示词教模型用某个工具，工具表里却没有它」，模型只会对着不存在的能力空转。
     */
    private boolean builtinExposed(boolean guestMode, Agent agent, String name, boolean branchTurn) {
        return toolRegistry.exposes(ToolFamily.BUILTIN, name,
                mountContext(agent, "", guestMode, false, branchTurn, (code, message) -> {
                }, null));
    }

    /**
     * 任务清单工具本轮是否真的挂得上：总闸 + tool.todo.enabled，对匿名访客恒为否
     * （游客收窄那条写在描述符的 guestAllowed 上，不在这里另判一遍）。
     * <p>子智能体分支（branchTurn=true）这里判不出来也不该在这儿判——清单的
     * {@code viewerRequired=true} 让 {@link ToolMountContext#turnAllows} 把它收掉，
     * 与装配那处是同一条判据（在此再写一个 {@code !branchTurn} 就是第二份真源，迟早漂移）。
     */
    private boolean todoExposed(boolean guestMode, Agent agent, boolean branchTurn) {
        return toolRegistry.anyExposed(ToolFamily.TODO,
                mountContext(agent, "", guestMode, false, branchTurn, (code, message) -> {
                }, null));
    }

    /** 咨询工具本轮挂不挂（判据本体在 {@code SubAgentToolProvider.exposes}：族开关 + 游客 + 分支轮不挂） */
    private boolean consultExposed(boolean guestMode, Agent agent, boolean branchTurn) {
        return toolRegistry.exposes(ToolFamily.SUBAGENT, "consultAgent",
                mountContext(agent, "", guestMode, false, branchTurn, (code, message) -> {
                }, null));
    }







    /** 并行分支上限（与编排器同一条判据，两处都读 {@code agent.maxParallelSubAgents}） */
    private int maxParallelBranches() {
        return Math.max(1, Math.min(8, configService.getInt("agent.maxParallelSubAgents", 4)));
    }

    // ==================== P3a：子智能体真全循环 ====================

    /**
     * 「具体项范围」字段解析（与主流智能体平台的资源选择语义一致）：
     * <ul>
     *   <li>null → null：跟随全局，不做具体项筛选；</li>
     *   <li>空串 → 空集合：显式一个都不用；</li>
     *   <li>"a,b" → {a, b}：只用这些。</li>
     * </ul>
     */
    private static Set<String> scopeOf(String v) {
        if (v == null) return null;
        if (v.isBlank()) return java.util.Collections.emptySet();
        return java.util.Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }















    /**
     * 非问答轮（手动压缩 /compact）的生效模型解析：显式指定 &gt; 个人默认；返回空串表示未配置，
     * 由调用方 fail-loud 引导用户选模型（与问答轮同一解析顺序，避免两条路走进不同模型）
     */
    public String resolveChatModel(String userId, String modelOverride) {
        return resolveModel(modelOverride, loadPrefUser(userId));
    }


































    /**
     * askUser 工具执行体（一卡多问版）：向用户一次性下发多个结构化问题并阻塞等待批量作答，
     * 与工具审批同一套挂起-恢复管道（内存 future 阻塞工具线程 + DB 卡片记录 + SSE 提问卡 + 站内通知旁路）。
     * <p>返回值直接作为工具结果回给模型：每问一个答案的并行数组（多问为 JSON 数组字符串，单问退化为纯文本）。
     * <b>超时与忽略都不替用户选</b>——该题回一句「用户没有回答」，由模型自行推进（见
     * {@link com.wenqu.ai.agent.hitl.HitlDecisionService#buildCombinedAnswer}）。等待窗口 chat.askTimeoutMs
     * （默认 10 分钟）；这段时间内客户端断开
     * 不中止本轮（见 {@link TurnState#keepRunningWithoutChannel}），用户刷新或换设备后仍可按
     * DB 记录重建卡片并把答案送回来。
     */
    private String doAskUserMulti(TurnState st, java.util.List<BuiltinTools.AskQuestion> raw) {
        if (st.guestMode) {
            return "（游客会话不支持结构化提问：请改为在回答正文中直接列出候选选项，请用户回复序号或自行描述。）";
        }
        if (st.askCount.incrementAndGet() > HitlDecisionService.MAX_ASKS_PER_TURN) {
            return "（本轮提问次数已达上限 " + HitlDecisionService.MAX_ASKS_PER_TURN + " 次：请基于已有信息直接作答，不要再提问。）";
        }
        // 归一为多问题列表（每题独立校验：空问题/选项非法直接丢弃该题，不留半成品卡）
        java.util.List<QItem> qs = new java.util.ArrayList<>();
        for (BuiltinTools.AskQuestion a : raw) {
            if (a == null) continue;
            String q = a.question() == null ? "" : a.question().trim();
            if (q.isEmpty()) continue;
            if (q.length() > 500) q = q.substring(0, 500);
            String t = a.topic() == null ? "" : a.topic().trim();
            if (t.length() > 16) t = t.substring(0, 16);
            java.util.List<String> opts = new java.util.ArrayList<>();
            if (a.options() != null) {
                for (String o : a.options()) {
                    if (opts.size() >= HitlDecisionService.MAX_ASK_OPTIONS) break;
                    if (o == null) continue;
                    String s = o.trim();
                    if (s.isEmpty() || opts.contains(s)) continue;
                    opts.add(s.length() > 200 ? s.substring(0, 200) : s);
                }
            }
            if (opts.size() < 2) continue; // 少于 2 个候选不构成选择题，非法该题跳过
            qs.add(new QItem(t, q, opts));
        }
        if (qs.isEmpty()) {
            return "（无有效问题：每题须含非空的 question 与 2~" + HitlDecisionService.MAX_ASK_OPTIONS + " 个候选选项，工具未执行；请调整后重试，或直接在回答正文中列出选项提问。）";
        }
        if (qs.size() > HitlDecisionService.MAX_QUESTIONS_PER_CARD) {
            return "（本次提问共 " + qs.size() + " 个问题，超过单卡上限 " + HitlDecisionService.MAX_QUESTIONS_PER_CARD
                    + " 个，工具未执行：请把问题合并或分批，压缩到 " + HitlDecisionService.MAX_QUESTIONS_PER_CARD + " 个以内后重新提问。）";
        }
        long timeout = hitl.askTimeoutMs();
        String askId = java.util.UUID.randomUUID().toString();
        // 挂起这张提问卡：落 PENDING 记录（questions 存 request_args）+ 登记唤醒句柄，都在新服务一次做完
        try (var wait = hitl.openAsk(askId, st.sessionId, st.userId, st.turnId, qs)) {
            // 提问待答站内通知（旁路）：卡片已落库且等待期间断线不中止本轮，人不在本页时铃铛是唯一
            // 可感知面，点进去仍能把这张卡答完（会话加载时按 DB 待答记录重建卡片）。
            try {
                notificationService.create(st.userId, com.wenqu.ai.model.Notification.TYPE_TOOL_ASK,
                        qs.size() > 1 ? "智能体有 " + qs.size() + " 个问题想跟你确认" : "智能体在等你回答",
                        "回到会话里作答，它才按你的选择继续；没答的那题不会替你选。",
                        "session", st.sessionId, "ask:" + askId, askId);
            } catch (Exception e) {
                log.warn("[ASK] 提问通知写入失败（不阻塞提问）: {}", e.getMessage());
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("askId", askId);
            req.put("questions", hitl.questionsPayload(qs));
            req.put("timeoutMs", timeout);
            // 卡片下发失败（连接已断）：本轮就此转入后台续跑——提问已落库，用户从别的页面答完，
            // 回答照样生成并落库，回会话页可见。不转的话答完的第一个 token 发送失败会掐掉整轮。
            if (!runtime.sendSseEvent(st.channel.emitter, "ask_user", JSON.toJSONString(req), st.sessionId)) {
                st.channel.detached = true;
                log.info("[ASK] 提问卡通道已失效，本轮转后台续跑: askId={} session={}", askId, st.sessionId);
            }
            log.info("[ASK] 等待用户批量作答: askId={} session={} n={} 窗口={}ms",
                    askId, st.sessionId, qs.size(), timeout);
            String answer;
            TurnRegistry.beginHumanWait(st.channel.emitter);   // 用户作答时间不计入整轮机器预算（见 TurnDeadline）
            st.askWaits.incrementAndGet();
            try {
                answer = wait.future().get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                answer = hitl.buildCombinedAnswer(askId, st.userId, qs, null, "TIMEOUT");
            } catch (java.util.concurrent.ExecutionException ee) {
                hitl.markAskResolved(askId, "TIMEOUT", null, st.userId);
                answer = "（用户中止了本轮回答：请基于已有信息直接作答，不要再次提问。）";
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                hitl.markAskResolved(askId, "TIMEOUT", null, st.userId);
                answer = "（本轮问答已被中止：请基于已有信息直接作答，不要再次提问。）";
            } finally {
                st.askWaits.decrementAndGet();
                TurnRegistry.endHumanWait(st.channel.emitter);
            }
            return answer;
        }
    }

    /** 本轮工具上下文：会话/用户归属 + askUser 执行器（闭包持有 st，提问面板 SSE 与阻塞等待都靠它） */
    private java.util.Map<String, Object> toolContext(TurnState st) {
        java.util.Map<String, Object> ctx = new java.util.HashMap<>();
        ctx.put(PresentArtifactTool.CTX_SESSION_ID, st.sessionId);
        ctx.put(PresentArtifactTool.CTX_USER_ID, st.userId);
        ctx.put(BuiltinTools.CTX_ASK,
                (BuiltinTools.AskFn) qs -> doAskUserMulti(st, qs));
        // 任务清单写入器：整表落库 + 实时 SSE + 账本留痕（回给模型的文本由 TodoService 组装，
        // 让模型看见"归一/截断了什么"，而不是以为原样写进去了）
        ctx.put(TodoService.CTX_TODO, (TodoService.WriteFn) items -> {
            TodoService.Normalized n = todoService.normalize(items);
            if (!n.ok()) return "错误：清单没有有效条目，每项都要写一句可验收的动作。";
            String doc = todoService.docJson(st.turnId, n.items());
            todoService.save(st.sessionId, doc);
            sessionEventService.append(st.sessionId, st.userId, st.turnId, "todo", doc);
            runtime.sendSseEvent(st.channel.emitter, "todo", doc, st.sessionId);
            return todoService.describe(n);
        });
        // 工具大输出外溢：沙盒那层截断前把整份原文交给我们落盘，卡片给「查看完整输出」按需读回。
        // 经上下文注入而不是全局单例——工具在弹性线程上并发跑，单例的归属会串到别人的轮上
        ctx.put(SandboxTools.CTX_SPILL, (java.util.function.BiFunction<String, String, String>)
                (toolName, text) -> toolSpillService.spill(st.sessionId, st.userId, toolName, text));
        ctx.put(SandboxTools.CTX_SPILL_REPORT, (java.util.function.BiConsumer<String, String>)
                (toolName, spillId) -> st.toolSpills.offer(toolName + "|" + spillId));
        // 智能体咨询（P5 agent-as-tool）：模型自己决定咨询谁、咨询几次，回答者不变。
        // 执行器按轮回传（为什么不是 bean 见 BranchRunner 接口注释），台账与父轮上下文都取本轮快照；
        // 被咨询方查到的资料经 sink 注册进父轮引用表，所以它的结论点得开出处、模型标得出角标。
        // consultCtx 缺省（非主流水线那条路建的 st，例如二次生成路径）时如实回一句，不 NPE 也不静默。
        ctx.put(com.wenqu.ai.agent.tool.SubAgentTools.CTX_CONSULT,
                (com.wenqu.ai.agent.tool.SubAgentTools.ConsultFn) (agentId, q) -> {
                    if (st.tools.consultCtx == null) {
                        return "咨询通道本轮未就绪，请基于已有资料回答。";
                    }
                    return subAgentConsult.consult(st.tools.consultLedger, st.tools.consultCtx,
                            branch.branchRunner(this::chat, st.sessionId, st.turnId, st.userId, st.guestMode, st.channel.emitter),
                            srcs -> st.refs.registerConsultedSources(srcs), agentId, q);
                });
        return ctx;
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
            snap.put("running", false);   // 本轮已收尾（把手由 claimTerminal 摘掉）
            return snap;
        }
        TurnState st = TurnRegistry.state(sessionId);
        if (st == null) {
            // 还在前置阶段（改写/检索/深度思考/计划编排）：流状态尚未构造，此刻确实没有正文可给。
            // 阶段文案、计划步骤与任务清单由总线镜像在 snapshot.events 里，前端重放即可复现界面
            snap.put("running", true);
            snap.put("phase", "pre");
            return snap;
        }
        if (st.settled()) {
            snap.put("running", false);
            return snap;
        }
        String content = st.fullResponse.toString();
        List<Map<String, Object>> artifacts = artifactService.takeArtifacts(sessionId);
        List<Map<String, Object>> toolCalls = new ArrayList<>(st.toolCalls);
        List<Map<String, Object>> sources;
        synchronized (st.refs.sources) {
            sources = new ArrayList<>(st.refs.sources);
        }
        snap.put("running", true);
        snap.put("phase", "generating");
        // 本轮身份：接回的那颗气泡同样要能按轮回看过程（与 done/落库同源，不是新造一个 id）
        snap.put("turnId", st.turnId);
        snap.put("content", content);
        snap.put("processText", st.processResponse.toString());
        snap.put("thinking", st.thinkingHolder[0]);
        snap.put("deepThink", st.deepThink);
        snap.put("toolCalls", toolCalls);
        snap.put("artifacts", artifacts);
        snap.put("timeline", recorder.timelineSnapshot(st, content.length(), toolCalls.size(), artifacts.size(), false));
        snap.put("sources", imageUrlSigner.signSourceImages(sources));
        snap.put("images", new ArrayList<>(st.refs.imgIndex.values()).stream().map(imageUrlSigner::signUrl).toList());
        snap.put("model", st.model);
        snap.put("toolStepCount", st.tools.toolStepCount.get());
        snap.put("maxToolSteps", st.tools.maxToolSteps);
        if (!st.subagentBranches.isEmpty()) snap.put("subagentBranches", st.subagentBranches);
        if (st.subagentRoute != null) snap.put("subagentRoute", st.subagentRoute);
        if (st.planRecord != null) snap.put("planRecord", st.planRecord);
        if (st.agentName != null) {
            snap.put("agentId", st.agentId);
            snap.put("agentName", st.agentName);
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


    /**
     * 将对话历史格式化为 user/assistant 文本，用于多轮改写 prompt（M5：单条截断 200 字、总长 1500 字）
     */
    private String formatHistory(List<Map<String, Object>> history) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : history) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            if (content.length() > 200) {
                content = content.substring(0, 200) + "…";
            }
            if (role.equals("user")) {
                sb.append("用户：").append(content).append("\n");
            } else if (role.equals("assistant")) {
                sb.append("助手：").append(content).append("\n");
            }
            if (sb.length() > 1500) {
                break;
            }
        }
        return sb.toString();
    }


}
