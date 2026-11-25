package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.RelatedPrompt;
import com.wenqu.ai.agent.chat.TurnRecorder;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.plan.PlanCopy;
import com.wenqu.ai.agentx.output.ModelOutputParser;
import com.wenqu.ai.agentx.turn.TurnDonePayload;
import com.wenqu.ai.agentx.turn.TurnFollowLoop;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator.PendingKind;
import com.wenqu.ai.agentx.turn.TurnPromptAssembler;
import com.wenqu.ai.agentx.turn.TurnSessionGateway;
import com.wenqu.ai.agentx.turn.TurnTranscriptRecorder;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.engine.ChatEngine;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEvent;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.service.ChatAttachmentService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.MessageWrite;
import com.wenqu.ai.util.TextIo;
import com.wenqu.ai.util.TurnBlocks;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.DefaultJedisClientConfig;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static com.wenqu.ai.agentx.AgentxToolCardCodec.*;

/**
 * 框架引擎：这一轮的<b>控制流完全由 AgentScope 持有</b>——推理循环、工具派发、权限判定、会话状态、
 * 待办与接续执行都由框架决定。问渠自己写的只剩四件：按本轮裁决出的模型交给框架、把框架的 Session Log
 * 翻成前端既有事件名、把问渠领域能力挂成框架工具、把框架待办接到问渠的确认端点。
 * <p>执行形态是 {@code AgentSession.submit}（常驻会话实例），不是每请求 {@code streamEvents}+close：
 * 只有前者带来待办持久化、答复后由框架找回原任务与原工具调用接续执行，以及按 {@code seq} 游标读增量
 * 事件（断线续跑的等价物）。模型切换走「换模型就重建实例并接管同一条会话」——会话能否接续不依赖实例
 * 永生：换一个全新实例、同一 {@code (userId, sessionId)} 也能读到完整历史并接着答。
 * <p>框架帮不上、必须问渠写的只有两处：RAG 检索链（框架没有问渠的多路召回，包成被调用的工具）
 * 与前端 29 个事件名的词汇差异（见 {@link #translate}）。
 */
public class AgentscopeChatEngine implements ChatEngine, FrameworkSessions.ResumeHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeChatEngine.class);

    /**
     * 成员轮直播台（由装配层绑定）：成员会话的每一轮在<b>起跑这一刻</b>开台，
     * 前端成员页订阅 /team/member/{sid}/live 就能拿到逐字正文与逐步工具卡——与主问答同一条事件契约。
     * 非成员轮为 null，零开销。
     */
    private volatile com.wenqu.ai.agentx.team.TeamMemberLiveFeed memberLiveFeed;

    /** 绑定成员轮直播台（启动期一次）。 */
    public void bindMemberLiveFeed(com.wenqu.ai.agentx.team.TeamMemberLiveFeed feed) {
        this.memberLiveFeed = feed;
    }


    /** 推荐块里条目长过这个字数就按正文处理（与手写侧 {@code RELATED_PROSE_MIN_CHARS} 同值同理由）。 */
    private static final int RELATED_PROSE_MIN_CHARS = 80;

    /** 条数闸的余量：配置要 N 条，模型多给一两条还算清单，几十条那种整块文档才判成正文。 */
    private static final int RELATED_CHIPS_TOLERANCE = 2;


    /** 一次从 Session Log 读多少条事件（框架的翻页上界，见 {@link #drain}）。 */
    private static final int DRAIN_PAGE = 200;

    /**
     * 跟随循环问一次「权威状态」的最长间隔（毫秒），见 {@link AgentxTurnMirror}。
     * <p>镜像跟事件，但框架算任务状态时还看两件<b>不落事件</b>的事：租约过期（{@code receipt} 把
     * running 降级成 interrupted）和 inbox 里的 rejected。光跟事件就永远看不见这两件，所以每一跳之外
     * 再按这个间隔回查一次。取 2 秒的依据：那一跑真死掉时，最坏晚 2 秒才收，而机器预算与看门狗都在
     * 更长的线上兜着；反过来若把它压回 200ms，就把刚省下来的全量重读又请回来了。
     */
    // 权威回查间隔：tasks() 对每条历史命令各做一次从 head 倒走的全量 scan，万级事件日志上一次
    // 秒级。它只是租约过期/inbox 拒绝这类「不落事件」
    // 状态的兜底，正常结算走镜像的 run/end，十秒级的兜底延迟没有可感知的后果。
    private static final long STATE_RECHECK_MS = 10_000L;

    /** 本轮提交离队的宽限：投递循环 100ms 一跳，超过这一段还没启动就是被闸门锁住了。 */
    private static final long LAUNCH_GRACE_MS = 30_000L;

    /** 整轮不截断（{@code chat.sseTimeoutMs}=0）时，单把工具仍保留的超时（秒）。 */
    private static final int UNLIMITED_TOOL_SECONDS = 7200;

    /** 单次模型调用的静默上限默认值（毫秒），见 {@link #modelExecutionConfig}。 */
    private static final int MODEL_STALL_TIMEOUT_MS_DEFAULT = 0;
    /** 主链一次调用的尝试次数（含首次）：与框架 MODEL_DEFAULTS 同数，只是把静默上限收下来。 */
    private static final int MODEL_MAX_ATTEMPTS = 3;
    /** 辅助调用（记忆提炼／上下文压缩）的尝试次数：慢一次没人等，不值得再烧一整段静默。 */
    private static final int AUX_MODEL_MAX_ATTEMPTS = 2;

    /**
     * 「流出过正文，但上游没给末块就把连接收了」的内部指纹——它不是上游原文，所以不参与
     * 额度／限流那套文案分类，在 {@link #emitRunFailure} 里单独说一句人话。
     */
    private static final String UPSTREAM_STREAM_CUT = "wenqu:upstream-stream-cut";

    private final ModelRegistryService modelRegistry;
    /** 工作区约定：注入 system prompt 的工作背景段（无工作区/无约定时为空） */
    private final com.wenqu.ai.service.ProjectService projectService;
    /** 工作目录简报的来源：工作背景段旁边再给模型一份「目录里有什么」的清单（见 {@link #projectContext}） */
    private final com.wenqu.ai.service.ProjectFileService projectFileService;
    /** 每轮文件改动：轮前快照 / 轮末对比 / 逐文件差异（只经执行器，内容不出用户电脑） */
    private final com.wenqu.ai.service.ProjectTurnChangesService turnChangeService;
    private final ConfigService configService;
    /** 个人配置（schema personal 字段）：读「接下来可以」推荐追问开关（ui.showRelatedSuggestions，按 uid）。 */
    private final com.wenqu.ai.service.UserConfigService userConfigService;
    private final AppProperties properties;
    private final WenquAgentxTools tools;
    private final FrameworkSessions sessions;
    private final TurnSessionGateway sessionGateway = new TurnSessionGateway();
    /** 会话事件日志换代（起轮缝里做，见 {@link JournalRotation} 的成因账）。 */
    private final JournalRotation journalRotation;
    /**
     * 框架侧 agentId——同时是会话事件日志物理路径的一段（{@code SessionKey#storagePath}）。
     * 建实例与换代必须同一个值，写两处就会漂成「轮转搬走的不是问答正在写的那一份」。
     */
    static final String AGENT_ID = "wenqu";
    /**
     * 轮首用户改动对账最多等这么久：执行器拍完快照顺手 diff 很快，但大仓库可能慢；
     * 等不到就不注入（对账是增强，不能拖住每一轮）。快照本身仍在后台继续，轮末照常等齐。
     */
    private static final long DRIFT_NOTE_WAIT_MS = 3000L;
    private final com.wenqu.ai.service.SessionService sessionService;
    /** 智能体团队服务（会话级开关；成员生命周期/叫停/drain；模型工具面由框架 teamsMode 提供）。 */
    private final com.wenqu.ai.agent.team.AgentTeamService teamService;
    /** 框架团队门面与 Redis 总线：由 AgentxConfiguration 构造后注入；测试环境可为 null。 */
    private volatile com.wenqu.ai.agentx.team.WenquTeamGateway teamGateway;
    private volatile com.wenqu.ai.agentx.team.WenquRedisMessageBus teamBus;
    /** 团队是否对本轮生效（根开启 / 成员轮）。 */
    private boolean teamActive(ChatCommand cmd) {
        try {
            return cmd.isTeamMode() || sessionService.isTeamEnabled(cmd.getSessionId());
        } catch (RuntimeException e) {
            return cmd.isTeamMode();
        }
    }
    /** 框架团队门面接入（AgentxConfiguration 在 new 完引擎后调用，避开 60 参构造器继续膨胀）。 */
    public void bindTeamFramework(com.wenqu.ai.agentx.team.WenquTeamGateway gateway,
                                  com.wenqu.ai.agentx.team.WenquRedisMessageBus bus) {
        this.teamGateway = gateway;
        this.teamBus = bus;
    }
    /** 附件元信息落库的共用口径（与工作流路径同一个方法，见 metasJson 的类注释）。 */
    private final ChatAttachmentService attachments;
    private final TurnPromptAssembler promptAssembler;
    /** 模型/记忆配置的全部决定已抽出到这里（本类保留同名薄委托，调用点不动） */
    private final AgentxModelConfig modelConfig;
    /** 本轮用量统计与检索状态行 */
    private final AgentxTurnUsage turnUsage;
    /** 工具卡载荷（tool_status 帧 JSON）构建 */
    private final AgentxToolCardBuilder toolCardBuilder;
    /** 提问卡载荷（askId/questions/timeoutMs）构建 */
    private final AgentxAskCard askCardBuilder;
    /** 框架事件 → 前端事件名的翻译层 */
    private final AgentxEventTranslator eventTranslator;
    /** 聊天档/工作区档判档 */
    private final AgentxSandboxIntent sandboxIntent;
    /** 计划卡快照构建（接流/新页签重建计划卡，本类只在需要时调它） */
    private final AgentxPlanCardSnapshot planCardSnapshot;
    /**
     * 用户上传图片的落盘链路（data URL → 文件 + 回显 URL）。
     * <p><b>为什么必须有这一环</b>：{@code appendMessage} 拿到的是原始 base64 data URL，而 {@code c_ai_message.images} 是
     * {@code TEXT}(65535) 且库跑在 {@code STRICT_TRANS_TABLES} 下，一张 1280px JPEG 的 base64 约 270KB，
     * 直接落库会 {@code ERROR 1406 Data too long} 打断整轮；模型侧也需要把图片拼进本轮输入，否则图发了等于没发。
     * 本服务（落盘 + 回显 URL + 保留原图 dataURL 供直发）与 {@code ScheduleCenter} 的清理任务承担这件事。
     */
    private final com.wenqu.ai.service.UserImageService userImages;
    /** 关键词召回用的分词器（问答侧的关键词抽取，与技能无关）。 */
    private final com.wenqu.ai.service.KeywordExtractor keywordExtractor;
    private final com.wenqu.ai.service.ArtifactService artifactService;

    /** 沙盒装配：供 builder 侧 .abstractFilesystem(...) 取本轮的沙箱文件系统。 */
    private final com.wenqu.ai.service.SandboxService sandboxService;

    /** 技能仓库：无状态，按当轮 RuntimeContext 解析可见技能，故由 Spring 单例注入。 */
    private final com.wenqu.ai.skill.WenquSkillRepository skillRepository;
    /** 技能使用统计：点名注入与模型按需读取两条路径都记一笔（异步落盘，不拖慢问答）。 */
    private final com.wenqu.ai.skill.SkillStatsStore skillStatsStore;
    /** 个人偏好（目前只取「自动提炼记忆」开关，见 memoryConfig）。 */
    private final com.wenqu.ai.service.OrgService orgService;
    private final com.wenqu.ai.agent.subagent.SubAgentConsultService consultService;
    private final com.wenqu.ai.agent.context.CitationIntegrityService citations;
    /** 全局 Redis 连接（与缓存/配置广播同一套 spring.data.redis）；框架状态存储只在其上换 key 前缀 */
    private final RedisProperties redisProperties;
    /** 问答台账（看板、坏例、未命中问题都吃这张表）——分派换运行时不该把它换成只有一半流量。 */
    private final com.wenqu.ai.service.QaLogService qaLog;
    /** 收尾持久化（消息落库 + 问答台账）：两件事同一次调用，判据只在一处。 */
    private final com.wenqu.ai.agent.chat.TurnPersister persister;
    /**
     * 框架模型出口的用量台账：主对话/子智能体/记忆提炼/上下文压缩都经 {@link MeteredModel} 记一行。
     * <p>原记账出口（{@code DynamicOpenAiChatModel}）只覆盖 Spring AI 侧，
     * 框架调用一笔都没进 {@code c_ai_usage_log}；不补这一层，记忆与压缩的成本完全不可见。
     */
    private final com.wenqu.ai.service.UsageLedgerService usageLedger;
    /** 配额闸：框架模型出口前判一次，与 Spring AI 那一路共用同一个服务与同一份判据 */
    private final com.wenqu.ai.service.UsageQuotaService quotaService;
    /**
     * @ 文档提及的正文来源（文档表不存全文，解析产物就是知识块，按 chunkIndex 拼回）。
     * 注入是「@ 了就必须真的给」，取不到要如实说明而不是让模型对着空话硬编（见 docMentionText）。
     */
    private final com.wenqu.ai.mapper.KnowledgeMapper knowledgeMapper;
    /** @ 文档提及的生效态校验（解析中/失败/弃用的文档不给正文，只给一句如实的说明） */
    private final com.wenqu.ai.mapper.AiDocumentMapper documentMapper;

    /**
     * 目标模式：状态机/续跑/收尾（问渠侧全部「目标」逻辑，见 GoalService 类注释）。
     * <p>形状对齐 DSH：一个目标跨轮自动推进，助手自报完成即停；没有验收标准与人工确认。
     */
    private final com.wenqu.ai.service.GoalService goalService;

    /**
     * 轮次与目标的站内通知产生方（「跑完了 / 没跑成 / 在等人拍板」）。
     * <p>它是旁路：内部全捕获，写不进去不影响这一轮收口。归属在这里必须靠会话行，
     * 不能拿 {@code cmd.getUserId()}——续跑重建命令时那一位可能是字面量 {@code guest}。
     */
    private final com.wenqu.ai.service.TurnNotifyService turnNotify;
    /** 智能体轮终 webhook 出站（对外能力第二批，旁路）：配置了回调地址的智能体每轮结束推送结果 */
    private final com.wenqu.ai.service.webhook.AgentWebhookService agentWebhookService;

    /** 运行总线：后台续跑轮要登记上去，前端才能按 deep_turn 帧自动接流 */
    private final com.wenqu.ai.service.ChatRunBus chatRunBus;

    /**
     * 此刻有后台目标续跑轮在飞的「会话|轮」。
     * <p>用来判「用户插话要不要让位」：只有后台轮在飞时插话才打断它（用户优先），
     * 否则用户这一轮本身就是目标的一轮，打断等于「刚说完话目标就停了」。
     * <p>键必须带轮号：删掉旧目标、立刻建新目标时，旧目标那一轮收尾不能把新目标在飞的
     * 那一轮从集合里摘掉（摘掉之后用户插话就不再让位了）。
     */
    private final java.util.Set<String> backgroundGoalTurns = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 目标续跑抢不到跟随位时的延迟重试（上一轮还在收尾，不能把新目标的第一轮丢了）。 */
    private static final java.util.concurrent.ScheduledExecutorService GOAL_RETRY =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "agentx-goal-retry");
                t.setDaemon(true);
                return t;
            });

    /**
     * 目标轮异步发起池：把 {@link #continueGoalTurn} 的同步重活（openForContinuation 重建整个 AgentScope
     * 实例——模型客户端 / MCP / 记忆 / 153 篇索引 / 沙盒供给，冷启动可达数十秒）从调用方线程挪走。
     * <p>不归 Spring 管的引擎自己起一个固定池：POST /deep 建行即回包（目标条 / 角标 / 左侧会话列表立刻出现），
     * 后台轮在实例就绪后登记运行总线，由前端 2.5s 轮询接流。池宽 4：单条冷启动装配很慢，但不能让一条新目标的
     * 实例重建堵住其它目标/会话的续跑——同一会话的并发由 {@code reserveFollower} 挡住（抢不到走 GOAL_RETRY）。
     */
    private static final java.util.concurrent.ExecutorService GOAL_KICK =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "agentx-goal-kick");
                t.setDaemon(true);
                return t;
            });

    /** 重试间隔与次数：旧目标轮被打断后通常在几秒内释放跟随位，30 秒仍抢不到就如实转暂停。 */
    private static final long GOAL_RETRY_DELAY_MS = 1_500L;
    private static final int GOAL_RETRY_MAX = 20;
    /**
     * 工具展示名与悬停说明的解析入口（落库的工具卡按这里补全展示字段）。
     * <p>框架引擎把工具<b>裸名</b>当 {@code label} 下发（{@code toolCard} 里的 {@code esc(name)}），
     * 前端 {@code toolLabel} 认得出「这不是展示名」而回落本地映射，于是这一路上的卡片全靠那份手抄表撑着——
     * 表里没有的（{@code consultAgent}、{@code writeTodo}、{@code plan_*}）就现出英文裸名。
     * 问渠自己的工具归注册中心，框架挂的那几把归 {@link FrameworkToolLabels}，两处合成一个解析入口。
     */
    private final ToolDisplayNames displayNames;
    /**
     * 本轮模型的解析与失效处置（请求覆盖 &gt; 会话固定 &gt; 个人默认，撞上已停用的模型时决定
     * 清覆盖回落还是 refuse），由问答侧按函数传入。两条运行时必须问同一条链，否则
     * 「聊天页看着能答、框架那一路解析不出路由」。
     */
    private final java.util.function.Function<ChatCommand, com.wenqu.ai.service.ChatService.TurnModel> modelResolver;
    /**
     * 会话状态存储：单副本用框架自带的本地文件实现（落在受管数据目录）；多副本部署时切换到 Redis，
     * 避免「待办/续跑落在别的实例」却读不到 stateStore 而失败。解析一次后缓存，配置不常变。
     */
    private volatile AgentStateStore cachedStateStore;
    private volatile boolean stateStoreResolved;
    /** 多副本协调（StoreBackedPeriodicGate + consolidation watermark）：与 stateStore 复用同一个 Jedis。 */
    private volatile io.agentscope.harness.agent.DistributedStore cachedDistributedStore;
    private volatile boolean distributedStoreResolved;
    private volatile UnifiedJedis cachedJedis;
    private volatile boolean jedisResolved;
    /**
     * 引擎级共享的模型 HTTP 通道（OkHttp）：连接池跨会话、跨实例常驻。
     * <p>每个会话实例各建一个 transport 就等于各一份连接池：轮与轮之间连接虽能复用，
     * 但<b>池与池之间不互通</b>，空闲连接也没有保活——网关侧把空闲连接掐掉后，
     * 下一轮第一笔请求都要重新 TCP+TLS（冷连接首包比热连接多约 0.5s，是稳态问答「模型段」
     * 的最大单一开销）。共享实例 + HTTP/2 pingInterval
     * 让活跃供应商的连接始终保持热：ping 对 HTTP/2 连接生效（防网关闲除断连），
     * 对 HTTP/1.1 连接无感，openai 兼容网关两种形态都覆盖。
     */
    private volatile io.agentscope.core.model.transport.OkHttpTransport cachedModelTransport;

    /**
     * 插话待发队列的信号口（装配期由 {@code AgentxConfiguration} 登记）。
     * <p>引擎只在「框架确实开跑了」（{@code inbox/opened}）时唤醒它：早一步 steer 必被拒，
     * 队列就会白等一整轮；晚一步就错过「下一次调模型前」这个唯一送达点。
     */
    private volatile com.wenqu.ai.agent.chat.SteerQueueHooks steerQueueHooks;

    public void bindSteerQueue(com.wenqu.ai.agent.chat.SteerQueueHooks hooks) {
        this.steerQueueHooks = hooks;
    }

    /** 懒建的共享模型传输层（见字段注释）；超时口径沿用原 per-session 配置。 */
    private io.agentscope.core.model.transport.OkHttpTransport sharedModelTransport() {        io.agentscope.core.model.transport.OkHttpTransport t = cachedModelTransport;
        if (t != null) return t;
        synchronized (this) {
            if (cachedModelTransport != null) return cachedModelTransport;
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    // 连接超时 8 秒：框架默认的 30 秒是一条已经死掉的连接在慢慢超时，
                    // 重试也得等满才出字，所以把连接这一截压到 8 秒。
                    .connectTimeout(java.time.Duration.ofSeconds(8))
                    .readTimeout(java.time.Duration.ofMinutes(5))
                    .writeTimeout(java.time.Duration.ofSeconds(30))
                    .pingInterval(java.time.Duration.ofSeconds(30))
                    .connectionPool(new okhttp3.ConnectionPool(16, 5, java.util.concurrent.TimeUnit.MINUTES))
                    .build();
            cachedModelTransport = io.agentscope.core.model.transport.OkHttpTransport.builder()
                    .client(client)
                    .config(io.agentscope.core.model.transport.HttpTransportConfig.builder()
                            .connectTimeout(java.time.Duration.ofSeconds(8))
                            .build())
                    .build();
            return cachedModelTransport;
        }
    }

    public AgentscopeChatEngine(ModelRegistryService modelRegistry, ConfigService configService,
                               AppProperties properties, WenquAgentxTools tools,
                               FrameworkSessions sessions,
                               com.wenqu.ai.service.SessionService sessionService,
                               com.wenqu.ai.agent.team.AgentTeamService teamService,
                               ChatAttachmentService attachments,
                               com.wenqu.ai.service.UserImageService userImages,
                               com.wenqu.ai.service.KeywordExtractor keywordExtractor,
                               com.wenqu.ai.service.ArtifactService artifactService,
                               com.wenqu.ai.service.SandboxService sandboxService,
                               com.wenqu.ai.agent.subagent.SubAgentConsultService consultService,
                               com.wenqu.ai.agent.context.CitationIntegrityService citations,
                               RedisProperties redisProperties,
                               com.wenqu.ai.service.QaLogService qaLog,
                               com.wenqu.ai.agent.tool.ToolRegistry toolRegistry,
                               com.wenqu.ai.service.ProjectService projectService,
                               com.wenqu.ai.service.ProjectFileService projectFileService,
                               com.wenqu.ai.service.ProjectTurnChangesService turnChangeService,
                               com.wenqu.ai.skill.WenquSkillRepository skillRepository,
                               com.wenqu.ai.skill.SkillStatsStore skillStatsStore,
                               com.wenqu.ai.service.OrgService orgService,
                               com.wenqu.ai.service.UserConfigService userConfigService,
                               com.wenqu.ai.service.UsageLedgerService usageLedger,
                               com.wenqu.ai.service.UsageQuotaService quotaService,
                               com.wenqu.ai.service.GoalService goalService,
                               com.wenqu.ai.service.TurnNotifyService turnNotify,
                          com.wenqu.ai.service.webhook.AgentWebhookService agentWebhookService,
                               com.wenqu.ai.service.ChatRunBus chatRunBus,
                               com.wenqu.ai.service.McpToolLabelService mcpToolLabelService,
                               JournalRotation journalRotation,
                               com.wenqu.ai.mapper.KnowledgeMapper knowledgeMapper,
                               com.wenqu.ai.mapper.AiDocumentMapper documentMapper,
                               java.util.function.Function<ChatCommand, com.wenqu.ai.service.ChatService.TurnModel> modelResolver) {
        this.modelRegistry = modelRegistry;
        this.projectService = projectService;
        this.projectFileService = projectFileService;
        this.turnChangeService = turnChangeService;
        this.configService = configService;
        this.properties = properties;
        this.tools = tools;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.teamService = teamService;
        this.attachments = attachments;
        this.userImages = userImages;
        this.keywordExtractor = keywordExtractor;
        this.artifactService = artifactService;
        this.sandboxService = sandboxService;
        this.consultService = consultService;
        this.citations = citations;
        this.redisProperties = redisProperties;
        this.qaLog = qaLog;
        this.persister = new com.wenqu.ai.agent.chat.TurnPersister(sessionService, qaLog);
        this.displayNames = new ToolDisplayNames(toolRegistry, mcpToolLabelService);
        this.toolCardBuilder = new AgentxToolCardBuilder(displayNames);
        this.askCardBuilder = new AgentxAskCard(configService);
        this.eventTranslator = new AgentxEventTranslator(toolCardBuilder, skillStatsStore, () -> steerQueueHooks);
        this.sandboxIntent = new AgentxSandboxIntent(sessions, sandboxService, projectService);
        this.skillRepository = skillRepository;
        this.skillStatsStore = skillStatsStore;
        this.orgService = orgService;
        this.userConfigService = userConfigService;
        this.usageLedger = usageLedger;
        this.quotaService = quotaService;
        this.goalService = goalService;
        this.turnNotify = turnNotify;
        this.agentWebhookService = agentWebhookService;
        this.chatRunBus = chatRunBus;
        this.journalRotation = journalRotation;
        this.knowledgeMapper = knowledgeMapper;
        this.documentMapper = documentMapper;
        this.modelResolver = modelResolver;
        this.promptAssembler = new TurnPromptAssembler(attachments, skillRepository, knowledgeMapper, documentMapper);
        this.modelConfig = new AgentxModelConfig(modelRegistry, configService, sessions, usageLedger, quotaService, orgService);
        this.turnUsage = new AgentxTurnUsage(keywordExtractor);
        this.planCardSnapshot = new AgentxPlanCardSnapshot(sessions, configService);
    }

    /**
     * 启动自检：框架会话状态存储的 Redis 必须在服务对外之前就位。
     * <p>为什么要在启动时炸：会话状态没有第二落点，也没有本地文件兜底。等第一轮问答才发现
     * Redis 不通，用户看到的是「模型答得像不认识我」，而真正的原因（状态存储不通）被埋在日志里；
     * 启动时就失败能把信息直指 Redis。
     * <p><b>注意调用方</b>：本引擎是 {@link AgentxConfiguration} 构造器里 {@code new} 出来的，
     * 不归 Spring 管，{@code @PostConstruct} 不会执行——所以由装配处显式调本方法。
     */
    void requireRedisForSessionState() {
        stateStore();
    }

    /**
     * 记忆配置热生效：{@code memory.*} 都烤在常驻 HarnessAgent 实例里（flush 节流、consolidation 上限、
     * MEMORY.md 硬上限、日账保留期），管理员改完设置页后不重建实例就等于没改。
     * <p>监听器只标「下一轮重建」，不打断正在跑的那一轮；没有活实例的会话下次 open 本来就会按新配置建。
     */
    @jakarta.annotation.PostConstruct
    void registerMemoryConfigHotReload() {
        configService.addChangeListener(changed -> {
            if (changed == null || changed.isEmpty()) return;
            if (changed.stream().anyMatch(k -> k.startsWith("memory."))) {
                sessions.markAllConfigStale();
                log.info("[agentx] 记忆配置已变更 {}，活跃会话下一轮重建实例", changed);
            }
            // 目标模式已无平台配置项：开关只剩智能体级 goal_enabled 那一格（改它走 agent 重建那条路），
            // 续跑轮数上限是 GoalSettings.MAX_ROUNDS（恒 20，代码常量）。
        });
    }

    /**
     * 解析并缓存会话状态存储。<b>恒走 Redis</b>，没有本地文件兜底。
     * <p>会话状态（对话上下文、待办、续跑游标）落在本地文件时，容器一重建就全丢：
     * 下一轮只能看到当前这一个问题，等价于每轮独立问答。
     * <p>所以 Redis 是硬依赖：连不上就抛，<b>不静默退回本地实现</b>。
     */
    private AgentStateStore stateStore() {
        if (stateStoreResolved) return cachedStateStore;
        synchronized (this) {
            if (stateStoreResolved) return cachedStateStore;
            cachedStateStore = RedisAgentStateStore.builder()
                    .jedisClient(requireRedis())
                    .keyPrefix(AgentxTextUtil.firstNonBlank(configService.get("chat.agentscopeRedisPrefix"), "agentscope:session:"))
                    .build();
            stateStoreResolved = true;
            return cachedStateStore;
        }
    }

    /**
     * 全局 Redis 连接（stateStore 与 distributedStore 共用一条，避免一个引擎建两条连接）。
     * <p>失败即抛：会话状态没有第二落点，宁可让应用起不来，也不要带着「每轮失忆」对外服务。
     * <p>连接参数复用 {@code spring.data.redis}（{@code REDIS_HOST / REDIS_PORT / REDIS_PASSWORD}）。
     */
    private UnifiedJedis requireRedis() {
        if (jedisResolved && cachedJedis != null) return cachedJedis;
        synchronized (this) {
            if (jedisResolved && cachedJedis != null) return cachedJedis;
            String host = redisProperties.getHost() == null || redisProperties.getHost().isBlank()
                    ? "localhost" : redisProperties.getHost();
            int port = redisProperties.getPort() > 0 ? redisProperties.getPort() : 6379;
            String password = redisProperties.getPassword();
            try {
                DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder()
                        .database(Math.max(0, redisProperties.getDatabase()))
                        .ssl(redisProperties.getSsl().isEnabled());
                if (password != null && !password.isBlank()) cfg.password(password);
                UnifiedJedis jedis = new UnifiedJedis(new HostAndPort(host, port), cfg.build());
                jedis.ping();
                cachedJedis = jedis;
                jedisResolved = true;
                log.info("[agentx] 框架 Redis 已连接: {}:{} db={}", host, port, redisProperties.getDatabase());
            } catch (Throwable e) {
                // 不置 jedisResolved：下一次调用还能重试（启动自检失败后进程退出，不靠重试兜底）
                throw new IllegalStateException("框架会话状态存储依赖 Redis（" + host + ":" + port
                        + "），连接失败：" + e + "。会话状态没有本地兜底（本地文件方案已退役：容器重建即丢，"
                        + "会导致多轮对话失忆），请先恢复 Redis 再启动应用。", e);
            }
            return cachedJedis;
        }
    }

    /**
     * 多副本协调：{@link io.agentscope.extensions.redis.RedisDistributedStore} 提供跨副本的
     * {@code PeriodicGate}（flush/consolidation 节流不再各副本各跑一份）与 {@code BaseStore}
     * （consolidation watermark 的 CAS），否则多副本会同时合并同一个 {@code MEMORY.md}。
     * <p>它不搬记忆文件：{@code MEMORY.md} 与日账仍在 {@code chat.agentscopeStateDir} 本地目录，
     * 多副本必须把该目录挂成共享卷（NFS/EFS/RWX PVC），否则同一用户在不同副本看到不同记忆。
     * <p>与状态存储同源：Redis 已是硬依赖，这里不再保留「退回单副本节流」的分支——
     * 那会让同一份记忆在多副本下被并发合并。
     */
    private io.agentscope.harness.agent.DistributedStore distributedStore() {
        if (distributedStoreResolved) return cachedDistributedStore;
        synchronized (this) {
            if (distributedStoreResolved) return cachedDistributedStore;
            cachedDistributedStore = io.agentscope.extensions.redis.RedisDistributedStore
                    .fromJedis(requireRedis(), "agentscope:");
            distributedStoreResolved = true;
            log.info("[agentx] 记忆/压缩节流与 consolidation watermark 使用 Redis 跨副本协调");
            log.warn("[agentx] 多副本提示：记忆文件仍落本地 chat.agentscopeStateDir，"
                    + "请确保各副本挂同一共享卷，否则同一用户在不同副本记忆不一致");
            return cachedDistributedStore;
        }
    }


    @Override
    public String name() {
        return "agentscope";
    }

    @Override
    public void chat(ChatCommand cmd, RunChannel channel) {
        // 成员轮直播：这一轮是成员会话（成员页发起的，或框架唤醒起的）就同时抄送一份给直播台。
        // 包装只加这一条抄送——本轮的出口、收尾、看门狗、落库全部还是原来那条 channel。
        // 已有直播（唤醒路径在起跑前已开台）时直接沿用：那一条的积压里已经有开头帧，不能被换掉。
        // 入参 channel 不许重新赋值：它被后面几十个 lambda 捕获，一旦变成非 effectively final 整轮都编译不过；
        // 而下面的语句会自己把 channel 再读一遍——所以先算好「要不要包」，一次构造出当轮出口。
        final com.wenqu.ai.agentx.team.TeamMemberLiveFeed feed = memberLiveFeed;
        // 要不要直播抄送：成员会话（成员页/唤醒）或子助手分支轮（depth>0）。
        // 子轮必须也包上——它的会话行是 KIND_TEMP，单看 kind 判不出来，
        // 而它的正文正是父轮派遣卡要显示的那一段（线上实测：漏了这一条，subagent_step 只有工具步）。
        final boolean liveThisTurn = feed != null && cmd.getSessionId() != null
                && !cmd.getSessionId().isBlank()
                && (isTeamMemberSession(cmd) || cmd.getSubagentDepth() > 0);
        // 幂等：唤醒/分支那两处可能已经开过台（含更早的积压帧），这里只是确保有
        if (liveThisTurn) feed.open(cmd.getSessionId());
        final RunChannel turnChannel = liveThisTurn
                ? new com.wenqu.ai.agentx.team.TeamMemberLiveChannel(channel, feed, cmd.getSessionId())
                : channel;
        // 模型引用按问答侧同一条链解析（请求覆盖 > 会话固定 > 个人默认）：只认请求里那一份时，
        // 靠个人默认作答的轮会解析不出路由（NPE 打断整轮，连错误帧都没有）
        com.wenqu.ai.service.ChatService.TurnModel turn = modelResolver.apply(cmd);
        if (turn.unusableMessage() != null) {
            // 已停用的模型不发给上游：上游只会回一句 401/404，用户读到的是「网关坏了」，
            // 而不是「你选的这个模型已经不能用了，去这儿改」
            failLoud(cmd, turnChannel, turn.unusableMessage());
            return;
        }
        String ref = turn.ref();
        if (ref == null || ref.isBlank()) {
            failLoud(cmd, turnChannel, "未指定模型：请在对话右上角选择模型，或在个人设置中配置默认模型");
            return;
        }
        ModelRegistryService.ModelRoute route = modelRegistry.chatRoute(ref);
        if (route == null) {
            failLoud(cmd, turnChannel, "模型「" + ref + "」未登记或已不可用：请在「智能体 → 模型供应商」里确认该供应商与模型");
            return;
        }
        ExecutionPolicy policy = tools.policyFor(cmd);
        // 用户开了新的一轮：目标循环让位（用户优先）——**只有后台续跑轮在飞时**才打断它。
        // 没有后台轮时，用户这一轮就是目标的一轮（它结束后的那一拍会由目标自己续上），
        // 在那时把目标打断等于「用户刚说完话，目标就停了」。
        if (backgroundGoalTurns.stream().anyMatch(k -> k.startsWith(cmd.getSessionId() + "|"))) {
            goalService.noteUserTurn(cmd.getSessionId(), cmd.getUserId(), turnChannel);
        }
        WenquSourcePool pool = tools.newPool(cmd, turnChannel.emitter());
        // 本轮改过用户存过的选择（失效的会话模型已清除、改用他的默认模型）必须说出来。
        // 只登记降级、不再另发一条 WARN：降级随消息落库、刷新后仍在，WARN 是同一句话的第二份，
        // 两条都会渲染成警示条（同一个原因上下各一条）
        if (turn.fallbackNotice() != null) {
            pool.registrar().degrade("modelFallback", turn.fallbackNotice());
        }

        // 本轮身份（轮级互斥外壳已经登记好 turnId）：待办落库、任务清单留痕、步数预算归零都认它
        String turnId = turnIdOf(cmd);
        // 本轮生效策略一行看全：分支继承、团队根封顶、显式 auto 是否真的生效，排障时不必再从
        // 审批现象反推。description 里已含档位/沙盒/审批/约束/可写根/禁用。
        log.info("[PERM] session={} turn={} source={} depth={} agent={} {}",
                cmd.getSessionId(), turnId, cmd.getSource(), cmd.getSubagentDepth(), cmd.getAgentId(),
                policy.describe());

        // 本轮用户图片：先落盘换 URL，再决定「原图要不要进模型」。
        // 两条链路的分叉点只有一个——聊天模型能不能读图（visionCapableOf，与发布游客模型时同一个判据）：
        //   能读：processDirect（落盘 + 保留原图 dataURL 供本轮直发，不丢细节）
        //   不能：processDisplay（只落盘回显，dataURL 不占内存；本轮在问题里注入诚实说明，见 imgNote）
        // 无论哪条，**落库的一律是 URL**：data URL 永远不进库（TEXT 列放不下，见 userImages 字段注释）。
        boolean chatSeesImages = modelRegistry.visionCapableOf(ref);
        List<String> rawImages = cmd.getUserImages();
        List<com.wenqu.ai.service.UserImageService.UserImage> userImgs =
                (rawImages == null || rawImages.isEmpty())
                        ? List.of()
                        : (chatSeesImages ? userImages.processDirect(rawImages) : userImages.processDisplay(rawImages));
        List<String> userImgUrls = userImgs.stream()
                .map(com.wenqu.ai.service.UserImageService.UserImage::url).toList();
        // 模型读不了图时必须**说出来**：登记降级（受 chat.retrievalDebugEnabled 控制，与其它降级同一条口径），
        // 并把说明拼进本轮问题——后者才是生产环境下用户真正看得见的那一层：
        // 模型据此回答「我看不到图，别猜」，而不是对着看不见的图硬编一段。
        // 只在真有图片被排除时登记：本轮没传图就无所谓模型支不支持视觉，不该弹「0 张图片仅作展示」
        String imgNote = "";
        if (!userImgs.isEmpty() && !chatSeesImages) {
            imgNote = "\n\n用户上传了 " + userImgs.size()
                    + " 张图片，但当前模型不支持图片理解，图片内容对你不可见。"
                    + "请勿猜测图片内容；若图片对回答关键，可建议用户更换支持图片理解的模型后重试。";
            pool.registrar().degrade("chatModelNoVision",
                    "当前模型「" + ref.substring(ref.indexOf('/') + 1)
                            + "」不支持图片理解，本轮 " + userImgs.size()
                            + " 张图片仅作展示、未参与回答；如需识别图片请更换支持图片理解的模型");
            log.warn("[FAIL-LOUD] 聊天模型不支持图片理解，图片仅展示: session={} model={} count={}",
                    cmd.getSessionId(), ref, userImgs.size());
        } else if (!userImgs.isEmpty()) {
            log.info("[IMAGE] 聊天模型自带图片理解，图片直发模型: session={} model={} count={}",
                    cmd.getSessionId(), ref, userImgs.size());
        }

        // 问题先落库，且必须排在 sessions.open 之前：建实例会先装配沙盒，这一步可能卡很久；
        // 而侧栏只列「有消息的会话」——等建完再落库的话，用户中途停止/刷新时这一问还没进库，
        // 整条会话会被当成空会话隐藏。
        // 附件/@/#历史引用随用户消息一并持久化，刷新后回显不丢。
        String userMessageId = cmd.isRegenerate() || cmd.isFromSteer() ? null
                : appendQuestion(cmd, turnId, userImgUrls);
        // 装配前已点停止（流水线排队/建实例之前）：这一问照样留在库里，但不再建实例、不调模型。
        // 必须排在 appendQuestion 之后——早于落库就返回会让用户「问出去了，刷新后连问题都没有」。
        // 目标态的收尾照常走：onRoundSettled(stopped=true) 会把目标轮结算并转暂停，
        // 跳过它等于用户按了停止、目标还在后台接着跑。
        if (tools.stopRequested(cmd)) {
            log.info("[agentx] 本轮在装配前已被用户停止，跳过实例与模型调用: session={} turn={}",
                    cmd.getSessionId(), turnId);
            goalService.onRoundSettled(cmd.getSessionId(), cmd.getUserId(), turnId, 0L, 0,
                    channel, "", true, true, null);
            goalService.onUserTurnEnd(cmd.getSessionId(), cmd.getUserId(), turnId);
            return;
        }

        java.util.concurrent.ConcurrentLinkedQueue<String> spills = new java.util.concurrent.ConcurrentLinkedQueue<>();
        WenquAgentxTools.TurnHooks hooks = tools.newHooks(cmd, ref, () -> turnId, spills,
                consultService.newLedger());
        // 本轮落点：工具表在 build 期定型、实例按会话常驻，pool/channel/hooks 只能放进会话级槽位，
        // 由这里在 submit 之前换掉。早于 sessions.open 是为了让建实例期的降级也能发到当轮通道上。
        AgentxTurnSlot slot = sessions.turnSlot(cmd);
        slot.begin(tools.newTurn(cmd, pool, turnChannel, policy, hooks, slot.workspaceMemoryTainted()));
        // 每轮文件改动：提交前先让执行器拍轮前快照（异步入队、FIFO 先于本轮第一次写），
        // 顺带对账「用户上一次回答之后改了什么」。对账结果在下文拼进本轮输入；快照本身仍由
        // settle 在轮末等齐。非本机工作区/执行器离线都在服务内部静默跳过。
        // API Key 轮次没有文件工具，拍快照没有意义。
        com.wenqu.ai.service.ProjectTurnChangesService.Start changes =
                cmd.isApiKey() ? null : turnChangeService.start(cmd.getSessionId(), turnId);
        // 本轮智能体绑定一次解析：实例重建判据、人设、技能白名单三处共用同一份（避免一轮读三遍库，
        // 也避免「判据说没换、人设却按另一份装配」这种自相矛盾）
        boolean sandboxWanted = sandboxIntent.wants(cmd, policy);
        slot.noteProfile(sandboxWanted ? "workspace" : "chat");
        // 本机执行器离线时的兜底必须让用户看见：这一轮文件在服务器上，不在他的电脑上。
        if (sandboxWanted && sandboxService != null) {
            try {
                String wsNotice = sandboxService.sessionWorkspaceNotice(cmd.getSessionId(), cmd.getUserId());
                if (wsNotice != null) {
                    pool.registrar().degrade("workspaceFallback", wsNotice);
                    turnChannel.emit(RunEventType.WARN, wsNotice);
                }
            } catch (RuntimeException e) {
                log.warn("[PROFILE] 工作区后端提示解析失败 session={}: {}", cmd.getSessionId(), e.toString());
            }
        }
        AgentBinding binding = bindingOf(cmd, policy, ref);
        // 实例键用完整引用而不是裸模型名：同一个模型名可以挂在好几个供应商下（各自的 baseUrl 与 Key 不同），
        // 只按裸名判「模型没换」就不重建实例，于是换了供应商仍拿旧网关旧 Key 跑，且日志上一切正常。
        // 档位也进键：聊天档/工作区档的工具面与文件系统不同，切档必须重建实例。
        AgentSession session;
        // 文件系统在 build 期固定：本机目录 ↔ 云端沙盒切换必须换实例键，否则会继续用旧的那一套。
        // 同一份 EW 既出键、又喂给 buildAgent 的 FS/prompt/guard roots——不许两处各算各的。
        com.wenqu.ai.service.EffectiveWorkspace ew = (sandboxWanted && sandboxService != null)
                ? sandboxService.resolveWorkspace(cmd.getSessionId(), cmd.getUserId(), true) : null;
        String wsKey = ew == null ? "" : ew.key();
        try {
            session = sessions.open(cmd, ref,
                    binding.key() + "|sb=" + (sandboxWanted ? "1" : "0") + "|ws=" + wsKey,
                    ctx -> buildAgent(cmd, policy, route, hooks, binding, slot, sandboxWanted, ew),
                    sandboxWanted);
        } catch (RuntimeException e) {
            // 建实例失败也要把槽位收回：否则这一会话的「当前轮」永远停在一次没跑起来的轮上
            slot.end(turnId);
            throw e;
        }
        // run_code 的脚本通道按轮现装：工具面在 build 期固定，轮号/通道/台账必须是当轮的
        tools.ensureRunCodeTurn(slot, cmd);
        // 首轮 build 之后才知道系统提示词里有没有工作区记忆：补标到本轮（缓存命中时上面 begin 已带上）
        if (slot.workspaceMemoryTainted() && slot.current() != null) {
            slot.current().taint().mark("workspace-memory");
        }
        // 起轮缝里先给这条会话的事件日志换一次代：框架每轮开始要把整条事件链从头读好几遍，
        // 代价按事件数的平方算，
        // 而本轮第一个这样的读就是下面的 reconcileStalledTurn（它读 SessionTurns.read）。
        // 排在所有全量读之前，否则本轮照样白付一次旧账；上一轮留着没结算的卡或没撒手的租约时
        // 框架自己会拒换代，那一轮就照旧不换，下一轮再试。
        journalRotation.rotateIfNeeded(cmd.getUserId(), cmd.getSessionId(), AGENT_ID);
        // 上一轮只要以失败收场过，这条会话后续每一轮都不会被框架启动：开轮前先把它归档掉
        sessionGateway.reconcileStalledTurn(session, cmd.getSessionId(), sessions::abandoned);
        // 工具步数预算按轮归零：实例是常驻的，不归零就是上一轮的步数吃掉这一轮的预算
        AgentxStepBudget stepBudget = sessions.stepBudget(sessions.key(cmd));
        if (stepBudget != null) {
            stepBudget.beginTurn(turnId, tools.maxToolSteps(cmd), (code, message) -> {
                pool.registrar().degrade(code, message);
                turnChannel.emit(RunEventType.WARN, message);
            }, text -> sessions.steer(cmd.getUserId(), cmd.getSessionId(), text));
        }
        // 重复调用闸同样按轮归零：不归零就是上一轮的调用签名判这一轮
        AgentxRepeatGuard repeatGuard = sessions.repeatGuard(sessions.key(cmd));
        if (repeatGuard != null) {
            repeatGuard.beginTurn((code, message) -> {
                pool.registrar().degrade(code, message);
                turnChannel.emit(RunEventType.WARN, message);
            });
        }
        // 框架工具档位闸按轮更新标签：拒了什么要能在日志里追到是哪一轮。
        AgentxFrameworkToolGate toolGate = sessions.frameworkToolGate(sessions.key(cmd));
        if (toolGate != null) {
            toolGate.beginTurn(turnId);
        }
        // 本轮实测用量只算这一轮新增的消息：框架的会话记录跨轮常驻，整表求和会把历史轮的 token
        // 也计进这一轮的账单
        int historyBefore = session.transcript().messages().size();
        // 事件游标同理必须从「本轮开跑之前」起读：框架的 Session Log 跨轮常驻，从 0 读会把上一轮的
        // 正文与工具卡当增量再发一遍（前端按 pos 去重只护得住同一条气泡的重复片段，护不住跨轮）
        long startCursor = session.log().head().seq();

        AgentxTurnRecording recording = new AgentxTurnRecording(spills, hooks.consultLedger(), tools, hooks, displayNames,
                cmd.getUserId());
        bindFrameworkToolGate(cmd, recording);

        // 计划模式（框架原生 plan_enter/plan_write/plan_exit）按轮进入，跑完必须退出。
        // 是否进入只由两件事决定：用户显式开计划开关，或模型自己在推理中调用 plan_enter。
        // 问渠不做「按消息意图预判」，避免在模型之外多一层会误判的闸。
        boolean planWanted = cmd.isPlanMode();
        boolean planEngaged = planWanted && sessions.setPlanMode(cmd, true);
        if (planWanted && !planEngaged) {
            log.warn("[PLAN] 计划模式请求但未进入激活位（框架侧未就位），本轮不展示计划卡: session={}",
                    cmd.getSessionId());
            turnChannel.emit(RunEventType.WARN, "计划模式未能启用（框架侧未进入计划位），本轮将不使用计划卡");
        }
        // 送进模型的不只是问题原文：附件正文 / @ 引用 / # 历史引用都在这一段拼进去。
        // 只送问题原文的话，用户传的东西模型一概看不见——落库只保证人能回看到，不等于模型收到了。
        String modelInput = promptAssembler.userContentWithContext(cmd, binding.skillFilter(),
                tools.agentMentionDirective(cmd, policy)) + imgNote;
        // 用户改动对账（轮首）：只等一小会儿，快照慢就跳过——对账是增强，不能把每一轮都拖住。
        // 放在 modelInput 而不是系统提示词里：系统提示词是 build 期烤进常驻实例的，轮级事实进不去。
        String driftNote = turnChangeService.driftNote(cmd.getSessionId(), changes, DRIFT_NOTE_WAIT_MS);
        if (!driftNote.isEmpty()) modelInput = modelInput + driftNote;
        // 模型能读图时，把原图作为 image_url 内容部件随本轮一起送进去。
        // 图片在「问题正文」里没有任何落点——附件有 buildContextText 抽正文，图片没有等价的通道，
        // 不随本轮送，模型就一概看不见（只会如实回答"没有看到图片"）。
        // 目标模式：用户这一轮也带上当前目标的一句上下文（跨轮目标不能只活在更早的提示词里，
        // 压缩/换设备之后仍然要让助手知道自己正在做什么）。没有当前目标时拼空串。
        // 续跑轮不走这里（续跑提示词由 GoalPromptBuilder 自带完整目标），不会重复。
        // 必须拼在 buildMultimodalMsg 之前：多模态那一支是从 modelInput 拍的快照，晚拼就丢了。
        // 判据与界面入口同源（goalService.enabledFor）：助手关掉目标模式后，界面不再露入口，
        // 这一轮也不再把目标塞给模型——两边必须说同一句话。
        String goalNote = goalService.enabledFor(binding.row())
                ? goalService.contextNoteFor(cmd.getSessionId()) : "";
        if (!goalNote.isEmpty()) {
            modelInput = modelInput + goalNote;
        }
        // 重新计划血缘：上一版计划与那一轮执行进度必须拼进本轮用户消息，而不是只留在历史里——
        // 长会话被压缩/换设备/历史裁剪后，历史里的旧计划可能已经不在模型上下文里。
        // 只在本轮确实开计划模式时拼：普通问答带 planBaseId 没有意义，也别往上下文塞一整份旧计划。
        String planBaseNote = cmd.getPlanBaseContext();
        if (planEngaged && planBaseNote != null && !planBaseNote.isBlank()) {
            modelInput = modelInput + planBaseNote;
        }
        // 目标工具按轮下发：只有在活跃目标（用户插话轮）里才进模型工具表，普通问答不常驻。
        slot.noteGoalActive(!goalNote.isEmpty());
        // 框架原生支持多模态：submit(Msg)，Msg 里可混排 TextBlock 与 ImageBlock。
        // buildMultimodalMsg 在图片形态都不可用时会返回 null，那时退回纯文本 submit，不拿 null 去提交
        io.agentscope.core.message.Msg multimodal =
                (chatSeesImages && !userImgs.isEmpty()) ? TurnPromptAssembler.buildMultimodalMsg(modelInput, userImgs) : null;
        // 工具面按轮落到框架的组激活集上（判据仍取自老装配那一条 exposes，不另写一份），
        // 并把「配置允许的名单」与「框架真正下发给模型的工具表」逐名比对一次——不一致就发 warn，
        // 静默少一道闸正是这套机制最初要治的病。
        for (String surfaceProblem : sessions.applyToolSurface(cmd, tools.turnGroupedNames(cmd, policy), policy)) {
            turnChannel.emit(RunEventType.WARN, surfaceProblem);
        }
        // 装配期（沙盒/实例要几十秒到几分钟）用户点了停止：不再发起模型调用，直接交给下面的跟随循环
        // 按停止收口（它第一跳就会 interrupt 并退出）。不在这里 return 是因为 slot / 跟随位 / 目标接续
        // 的收尾都在 followTurn 的 finally 里，跳过去就少收一样。
        AgentSession.Task current = null;
        if (tools.stopRequested(cmd)) {
            log.info("[agentx] 装配完成时本轮已被停止，不再发起模型调用: session={} turn={}",
                    cmd.getSessionId(), turnId);
        } else {
            current = multimodal != null
                    ? session.submit(multimodal)
                    : session.submit(modelInput);
        }
        // 占住这一条会话的跟随位，收尾让位：答复端点据此判断「这次答完有没有人接着跑」
        String sessionKey = sessions.key(cmd);
        sessions.claimFollower(sessionKey);
        try {
            // 起表基准：网页/分享链路由控制器在请求入口记下 receivedAtMs；定时/目标/工作流这类
            // 没有请求体的入口以开轮这一刻为准（prepMs=0，不把到达流水线前的等待算成准备）。
            // 两种入口的整轮墙钟都从这里起表，落库、done、接流快照与前端秒表用的是同一本账。
            long startedAt = System.currentTimeMillis();
            long prepMs = cmd.getReceivedAtMs() > 0L
                    ? Math.max(0L, startedAt - cmd.getReceivedAtMs()) : 0L;
            // 起表基准早帧：外壳在请求入口已发过一帧（收到提问→此刻），这里用开轮后的准确 prepMs
            // 再校准一次；两帧同一基准，重锚不会让读数跳。没有请求体的入口这一帧就是它唯一的起表基准。
            turnChannel.emit(RunEventType.TURN_START, com.alibaba.fastjson2.JSON.toJSONString(
                    java.util.Map.of("wallMs", prepMs, "prepMs", prepMs)));
            // 本轮点名的技能：正文已由装配段注入上下文，这里记用量并给前端发命中帧——
            // 没有这一帧，「选了技能」这件事在界面上是静默的，用户永远不知道模型到底照没照做。
            emitPickedSkillHits(cmd, turnChannel);
            followTurn(session, cmd, turnChannel, pool, route, hooks, turnId, historyBefore, startCursor,
                    userMessageId, current, recording, startedAt, prepMs, binding.contextWindow(), false);
        } finally {
            sessions.releaseFollower(sessionKey);
            // 收口后清掉本轮落点：之后再有工具调用会明确失败，而不是回落到这一轮的池子里
            slot.end(turnId);
            // 异常/提前退出没走到 done 的轮也要摘收据，否则会一直挂在台账里
            com.wenqu.ai.agent.chat.SteerReceipts.clear(turnId);
            // 本轮彻底收尾（跟随位已释放）才接目标的下一轮：放在收尾里发会被「已有跟随者」挡掉。
            // 传这一轮的 turnId：服务端据此区分「用户轮收尾」（把让位停下的目标自动接上）
            // 与「目标轮收尾」（不重复接）。
            goalService.onUserTurnEnd(cmd.getSessionId(), cmd.getUserId(), turnId);
        }
    }

    /**
     * 跟着这一轮跑到收尾：有待办就等人答复，没有待办就跟到最后一条任务，任务结算后再让出一拍复查。
     * <p>独立成方法是为了让「进程重启后接回一条待办」（{@link #resume}）能复用同一段跟随逻辑——
     * 那一跑不是新问答：没有问题要落库、也没有 submit，只有框架 respond 接续出来的那条任务。
     */
    private void followTurn(AgentSession session, ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                            ModelRegistryService.ModelRoute route, WenquAgentxTools.TurnHooks hooks,
                            String turnId, int historyBefore, long startCursor, String userMessageId,
                            AgentSession.Task current,
                            AgentxTurnRecording recording, long startedAt, long prepMs, int contextWindow,
                            boolean recovered) {
        // 恢复轮只对用户轮生效：目标轮的收尾由 GoalService 记账，多落一条「重启中断」的助手行会跟目标状态打架
        boolean orphanRecovered = recovered && (turnId == null || !turnId.startsWith("goal:"));
        long machineBudgetMs = turnSeconds() * 1000L;
        // 断流对账的读数起点：主链模型包装器按会话常驻、跨轮复用，本轮的断流次数只能取收尾读数
        // 减开轮读数（见 MeteredModel.abruptCloses）。实例还没建出来时是 null——那一跑连模型都没调，
        // 也就没有「断流」可判。
        MeteredModel turnModel = sessions.chatModel(sessions.key(cmd));
        long abruptBefore = turnModel == null ? 0L : turnModel.abruptCloses();
        long modelErrBefore = turnModel == null ? 0L : turnModel.modelErrors();
        // 接流快照改由本轮自己供：外壳开轮时只有「还没出字」那份空快照，而正文/工具卡/时间线
        // 只活在这里的 AgentxTurnRecording 与引用池里。不回填的表现为——生成中刷新，气泡接回来是一片空白。
        channel.offerSnapshot(() -> turnSnapshot(cmd, channel, pool, route, recording, turnId, startedAt, prepMs));
        // 人在回路等待/超时状态机：人工等待累计、三档预算与超时决策都收在协调器里，
        // 引擎只负责 begin/end 台账与超时后的发帧/回送。
        TurnInteractionCoordinator interactions = new TurnInteractionCoordinator(
                System::currentTimeMillis, this::pendingBudgetMs);
        // 读取起点与播报起点分开定：
        //  - 读取起点是「本轮第一条事件」——续跑那一跑里批准之前跑掉的工具也属于这一轮，
        //    从接回时刻起读会让落库的工具卡只剩半截；
        //  - 播报起点是接回/开轮那一刻，历史帧不重放给观众（前端按 pos 去重只护得住同一条气泡，
        //    跨轮重放等于把上一轮的正文再打一遍）。
        TurnFollowLoop loop = new TurnFollowLoop();
        loop.seed(AgentxTextUtil.turnStartSeq(session, current == null ? null : current.turnId(), startCursor), startCursor);
        // 连续多步没有带回新信息：由引擎叫停（不再等模型自己停下来）
        // 等待预算用尽：卡片已落终态，收尾时不再对用户说「答完会自动接着跑」
        // 本轮有没有真的被框架启动。投递循环 100ms 一跳，正常几百毫秒内就离队；被闸门跳过时它会
        // 永远停在 queued，而「没启动」这件事框架自己不报错——不另设一条早于整轮预算的判据，
        // 用户看到的就只是「转了五分钟然后说超时」，一个字也没有。
        long launchDeadline = startedAt + LAUNCH_GRACE_MS;
        // 会话状态跟事件认（见 AgentxTurnMirror）。起轮种一次权威待办，之后每一跳只认这一跳读到的事件。
        AgentxTurnMirror mirror = new AgentxTurnMirror();
        mirror.resyncPending(session);
        // 兜底复查时刻：租约过期与 inbox 拒绝不落事件，只能按 STATE_RECHECK_MS 回查权威值

        // 一整轮里待办可能出好几次：批准之后模型仍可再要一次确认。所以这里不是「等一次答复」，
        // 而是跟随会话现状——有待办就等人，没待办就跟到最后一条任务，任务结算了还要再看一眼
        // 有没有新待办或接续出来的新任务，两者都没有才算这一轮跑完；只等一次就收尾的话，
        // 第二次确认的卡发不出去，正文也是空的。
        final class FollowHooks implements TurnFollowLoop.Hooks {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }

            @Override
            public long machineMs() {
                return AgentxTextUtil.machineMs(startedAt, interactions.humanWaitMs(),
                        interactions.waitingOnHuman(), interactions.waitBeganAt());
            }

            @Override
            public void pause(long millis) {
                AgentxToolCardCodec.sleep(millis);
            }

            @Override
            public long drain(long cursor, long emitFrom) {
                return AgentscopeChatEngine.this.drain(session, cursor, emitFrom, turnId, channel, recording, mirror);
            }

            @Override
            public boolean takePendingChanged() {
                return mirror.takePendingChanged();
            }

            @Override
            public boolean takeTaskChanged() {
                return mirror.takeTaskChanged();
            }

            @Override
            public boolean takeMessageChanged() {
                return mirror.takeMessageChanged();
            }

            @Override
            public void emitLiveUsage(long[] usageStamp, boolean messageChanged) {
                turnUsage.emitLiveUsage(session, cmd, pool, channel, historyBefore,
                        usageStamp, messageChanged, contextWindow);
            }

            @Override
            public int emitRetrieved(int announcedRefs) {
                return turnUsage.emitRetrieved(cmd, pool, channel, announcedRefs, recording);
            }

            @Override
            public boolean planSuperseded() {
                return "supersede".equals(sessions.peekPlanOutcome(sessions.key(cmd)));
            }

            @Override
            public void interrupt() {
                session.interrupt();
            }

            @Override
            public void announcePending(Map<String, SessionEvent> pending) {
                AgentscopeChatEngine.this.announcePending(pending, cmd, channel,
                        loop.announcedApprovals(), turnId, recording);
            }

            @Override
            public boolean stopRequested() {
                // 用户轮的停止把手 + 目标轮自己的叫停判据。目标轮没有 TurnHandle（后台起跑），
                // 只读用户那把手会让「取消目标」永远停不下来——线上那条取消后又跑了 4 分钟就是它。
                return tools.stopRequested(cmd) || goalService.goalTurnAborted(turnId);
            }

            @Override
            public void steerWrapUp() {
                sessions.steer(cmd.getUserId(), cmd.getSessionId(), AgentxTurnProgress.WRAP_UP_TEXT);
                log.info("[agentx] 连续无进展，已要求模型收尾: session={} turn={} streak={}",
                        cmd.getSessionId(), turnId, recording.progress().staleStreak());
            }

            @Override
            public boolean takeWrapUp() {
                return recording.progress().takeWrapUp();
            }

            @Override
            public boolean progressStalled() {
                return recording.progress().stalled();
            }

            @Override
            public void warn(String text) {
                channel.emit(RunEventType.WARN, text);
            }

            @Override
            public void warnStall() {
                channel.emit(RunEventType.WARN, com.wenqu.ai.agent.chat.TurnRecorder.STALL_NOTE);
                log.warn("[FAIL-LOUD] 连续无进展，已叫停框架那一跑: session={} turn={} streak={}",
                        cmd.getSessionId(), turnId, recording.progress().staleStreak());
            }

            @Override
            public boolean hasPending() {
                return mirror.hasPending();
            }

            @Override
            public Map<String, SessionEvent> pendingSnapshot() {
                return mirror.pendingSnapshot();
            }

            @Override
            public Map<String, SessionEvent> pending() {
                return session.pending();
            }

            @Override
            public boolean answerTimedOutAsk(Map<String, SessionEvent> pending) {
                return AgentscopeChatEngine.this.answerTimedOutAsk(pending, cmd, recording);
            }

            @Override
            public void beginHumanWait(Map<String, SessionEvent> pending) {
                AgentxEmitUtil.beginHumanWait(channel, recording, pending);
            }

            @Override
            public void endHumanWait() {
                AgentxEmitUtil.endHumanWait(channel, recording);
            }

            @Override
            public AgentSession.Task latestTask(AgentSession.Task known) {
                return TurnSessionGateway.latestTaskInTurn(session, known);
            }

            @Override
            public boolean activeRunsEmpty() {
                return session.inspect().activeRuns().isEmpty();
            }
        }
        TurnFollowLoop.LoopResult loopResult = loop.run(new TurnFollowLoop.RunContext(
                current, machineBudgetMs, launchDeadline, STATE_RECHECK_MS, interactions), new FollowHooks());
        current = loopResult.current();
        // 兜底：超时收尾 / stopRequested 跳出都可能停在「仍在等人」的状态上。
        // 不收口的话台账的 openWaits 永远 >0，之后这一轮算出来的机器耗时永远是负数/偏小，
        // 看门狗形同虚设；而引擎自己的墙钟已经进了下一轮比较，必须先把这段补齐再往下走。
        interactions.settle(() -> AgentxEmitUtil.endHumanWait(channel, recording));
        // 收尾把游标跑到 head：这一轮的尾巴增量不能再漏（镜像传 null——出轮之后没人读状态）
        drain(session, loop.cursor(), loop.emitFrom(), turnId, channel, recording, null);

        // 计划卡裁决的界面终态：拒绝=deep_plan_cancelled、继续对话=deep_plan_superseded。不补的表现为
        // 用户已经拒了计划，那张卡却还挂着「等待中」（控制流仍归框架判，这里只补留痕帧与 done 标记）
        String planOutcomeRaw = sessions.takePlanOutcome(sessions.key(cmd));
        TurnInteractionCoordinator.PlanOutcome planOutcome =
                TurnInteractionCoordinator.planOutcome(planOutcomeRaw);
        boolean superseded = planOutcome == TurnInteractionCoordinator.PlanOutcome.SUPERSEDED;
        if (superseded) channel.emit(RunEventType.DEEP_PLAN_SUPERSEDED, "{}");
        else if (planOutcome == TurnInteractionCoordinator.PlanOutcome.CANCELLED) {
            channel.emit(RunEventType.DEEP_PLAN_CANCELLED, "{}");
        }
        // 整轮机器预算用尽而循环是自己退的：屏幕上这一轮已经收尾，框架那一跑却还活着——不叫停，
        // 下一步与下一段钱就记在没有人跟的那一跑上。中断是协作式的：整轮那道闸打不断在途调用，
        // 在途那一步跑完之后不再起新的模型调用；已经流出半截的正文照旧走下面的收尾落库，不丢。
        // 判据用同一个 machineMs——与上面 while 的退出条件一字不差，否则「退出了」与「预算用尽」
        // 会对不上，把正常答完的那一轮也报成超时。
        boolean ranOutBudget = false;
        if (!loop.stopped() && !loop.neverLaunched() && !superseded
                && TurnFollowLoop.budgetExhausted(AgentxTextUtil.machineMs(startedAt, interactions.humanWaitMs(), interactions.waitingOnHuman(), interactions.waitBeganAt()), machineBudgetMs)) {
            session.interrupt();
            ranOutBudget = true;
            channel.emit(RunEventType.WARN, com.wenqu.ai.agent.chat.TurnRecorder.TURN_BUDGET_NOTE);
            log.warn("[FAIL-LOUD] 整轮机器预算用尽，已叫停框架那一跑: session={} turn={} 预算={}ms",
                    cmd.getSessionId(), turnId, machineBudgetMs);
        }
        // 跟随循环退场时框架那一跑还活着：这不是正常收尾。它还会继续调模型、继续计费，
        // 而下一条新轮会在它尚未提交的上下文上从零开始。
        // 预算那一条上面已经叫停过；这里兜住其余退出支路，确保「引擎的轮」与「框架的 run」同生共死。
        if (!loop.stopped() && !loop.neverLaunched() && !superseded && !ranOutBudget && !loop.timedOut()
                && (current == null || !current.settled())
                && !session.inspect().activeRuns().isEmpty()) {
            session.interrupt();
            log.warn("[FAIL-LOUD] 跟随循环退场时框架那一跑仍在执行，已叫停: session={} turn={}",
                    cmd.getSessionId(), turnId);
        }
        // 裁决过的计划卡随助手消息落 plan 列（字段与手写侧 planSnapshot 同名：plan/status/versions/auto）。
        // 不落的表现：刷新后只剩「还在等确认」的那一张（它由待办恢复端点重建），已批过的每张计划都消失，
        // 用户回头看不出这一轮是按哪一版计划跑的。
        Map<String, Object> planRecord = null;
        java.util.List<Map<String, Object>> planVersionList = recording.planVersionsCopy();
        if (planOutcomeRaw != null && !planVersionList.isEmpty()) {
            Map<String, Object> last = planVersionList.get(planVersionList.size() - 1);
            planRecord = new java.util.LinkedHashMap<>();
            planRecord.put("plan", last.get("plan"));
            planRecord.put("status", "approve".equals(planOutcomeRaw) ? "approved"
                    : "supersede".equals(planOutcomeRaw) ? "superseded" : "rejected");
            // 版本项现在带 feedback（产出该版的修改意见）：刷新后 PlanViewer 的版本轨也能逐版回看
            planRecord.put("versions", planVersionList);
            // 计划血缘：这一版自己的稳定 id + 它基于哪一版旧计划（旧计划那一轮的执行统计也落库，
            // 刷新/换设备后新卡仍能显示「调整自 v1 · 已执行 2/5」，不必再依赖本机内存）。
            if (recording.planApprovalIdValue() != null) planRecord.put("id", recording.planApprovalIdValue());
            if (cmd.getPlanBaseId() != null && !cmd.getPlanBaseId().isBlank()) {
                planRecord.put("planBaseId", cmd.getPlanBaseId());
                planRecord.put("planBaseVersion", cmd.getPlanBaseVersion());
                planRecord.put("planBaseDone", cmd.getPlanBaseDone());
                planRecord.put("planBaseTotal", cmd.getPlanBaseTotal());
                planRecord.put("planBaseFailed", cmd.getPlanBaseFailed());
            }
        }

        // 机器预算用尽退场、卡片还挂着：这一轮不会再有跟随者（超时回收要靠活着的那个循环，它已经退了）。
        // 不判终态就是两件坏事，而且第二件正是用户报的那个症状：
        //  1) 下面 `awaiting` 那一支刻意不落库 → 用户刷一下页面，整半轮过程（思考/工具卡/引用/用量）
        //     在库里根本不存在，界面上凭空消失，只剩第一句提问；
        //  2) 卡片留在 PENDING → 恢复端点还能把它重建出来，但答了没人接，成了「点了没反应」。
        // 所以按超时收口：待办落 TIMEOUT、这一轮走 timedOut 那条落库与文案（半程内容据此可见）。
        // 收尾的权威待办快照只做一次：pending() 按 256 一批从 0 倒走整条 commit 链，万级事件
        // 日志上一次就是十几秒，下面四处各读一遍等于
        // 四份白付。abandon 结算掉的卡在快照里仍挂着——那几支已经把 timedOut 置真，awaiting
        // 的 !timedOut 正好把这笔陈旧性挡掉；真还在等人的卡快照如实保留，语义不变。
        Map<String, SessionEvent> pendingAtClose = session.pending();
        if (!loop.stopped() && !loop.timedOut() && !superseded && !loop.neverLaunched() && !pendingAtClose.isEmpty()) {
            channel.emit(RunEventType.WARN, "本轮已超时结束，等你确认的那一步已作废");
            loop.timeout();
        }

        if (!loop.stopped() && !loop.timedOut() && !superseded && pendingAtClose.size() > 0) {
            // 「答完会自动接着跑」只对还活着的等待成立：超时那一支已经把待办落成终态，
            // 再说这句就是在承诺一件不会发生的事
            channel.emit(RunEventType.WARN, "有工具在等你确认，答完会自动接着跑（可稍后回来接着看它）");
        }
        // 这一轮到此不再有人跟随：还挂在人身上的待办当场收口（框架结算 + 卡片终态 + 待办行落库）。
        // neverLaunched 那一支不碰——那些待办属于**上一轮**，卡片可能正等用户答，本轮没启动不是它的错。
        if (!loop.neverLaunched()) abandonPendingInteractions(session, cmd, channel, recording, loop.stopped() || loop.stalled(), pendingAtClose);
        sessions.setPlanMode(cmd, false);
        // 正文取哪一份：优先本轮实际流出来的那一截（与发出去的 token 帧同源）；只有它为空、且这一轮
        // 本身没失败，才回退到会话里最后一条有正文的助手消息。先查会话会拿到<b>上一轮的答复</b>：
        // 被叫停的那一跑在 Transcript 里没有 committed text，等于拿别人的话给这一轮存档。
        // 流末尾留着没定性的尾巴先归位（它可能只是正文的最后几个字）
        ModelOutputParser.Delta finishTail = recording.related.finish();
        recording.answer(finishTail.text());
        recording.process(finishTail.process());
        String answer = recording.answerText();
        // 这一轮到底有没有真的流出过正文——下面撞步数上限那一段要靠它决定「补一段收尾」还是「别补」：
        // 空正文时下面那条回退取的就是框架收尾消息本身，再补一遍等于把同一段话印两次
        final boolean streamedBody = !answer.isBlank();
        // 失败判据必须排在回填前面：回填不问这一轮是不是失败的那一跑，上游 401 的轮因此会顶着
        // 「答好了」的样子把上一轮的正文存进历史，失败一个字没提。
        String failure = AgentxHistoryText.runFailure(session, current);
        // 上游把连接直接收了、没给带 finishReason/usage 的末块：框架那一跑看到的是「正常结束」，
        // executionError 为空、done 帧照发——屏幕上是一段凭空短掉的正文，刷新后连「这一轮断过」
        // 都读不出来。
        // 框架不报，就只能问渠自己报：这个判据出自模型出口那一层，不靠上游错误文本。
        if (failure == null && turnModel != null && turnModel.abruptCloses() > abruptBefore) {
            failure = UPSTREAM_STREAM_CUT;
            log.warn("[FAIL-LOUD] 本轮上游中途断流（流出过正文但没有末块）: session={} turn={}",
                    cmd.getSessionId(), turnId);
        }
        // 界面那一句人话（emitRunFailure 生成的），通知要抄它而不是抄上游原始串：
        // 原始串形如「HTTP transport error during streaming: HTTP request failed with status 403…」，
        // 是英文、带响应体碎片，用户在铃铛里读不出「该充值还是该换模型」
        String spokenFailure = null;
        // 框架把「重试烧完」压成一句 Retries exhausted: 2/2，真正的 429/额度文案留在它挂住的
        // 那次失败里；只看这句会把「额度耗尽」说成「模型无响应」，指的方向正好反了。
        // 只往<b>已判定失败</b>的那一句并原文——重试成功的那一轮同样会记一次错误，
        // 拿它当失败就是把答好了的一轮报成故障。
        // 并原文也只认「Retries exhausted」这一句本身：doOnError 对每次失败的尝试都计数，
        // 重试救回的旧错也在 modelErrors 差值里——只看差值就会把无关的 429 拼上断流轮，
        // 而 emitRunFailure 对断流哨兵是<b>全等</b>匹配，一拼就失配、断流文案永远不再出现。
        if (failure != null && turnModel != null && turnModel.modelErrors() > modelErrBefore
                && failure.toLowerCase(java.util.Locale.ROOT).contains("retries exhausted")) {
            String detail = turnModel.lastModelError();
            if (detail != null && !detail.isBlank() && !failure.contains(detail)) {
                failure = failure + " | " + detail;
            }
        }
        if (answer.isBlank() && failure == null) answer = AgentxHistoryText.stripFallbackBody(
                recording, AgentxHistoryText.lastAssistantText(session, historyBefore));
        // 框架那一跑本身失败（上游 402 额度、网关异常、运行被拒……）必须说出来：它只发一条
        // 「done + 空正文」，用户看到的是「它答了个空」，而不是「这次没答出来，原因是额度不足」。
        // 失败判据用框架自己留的 executionError / 任务终态。
        if (loop.neverLaunched()) {
            // 单独一句而不是套用「AI 回复失败，请稍后重试」：这一轮模型根本没被调用，重试同样的坑还在，
            // 得让用户知道该换个动作
            pool.registrar().degrade("frameworkNotLaunched", "框架引擎本轮未启动执行（提交停在队列里）");
            channel.emit(RunEventType.ERROR,
                    "框架引擎本轮没有开始执行（模型未被调用），请重试；反复出现请新建一条会话");
            log.warn("[FAIL-LOUD] 本轮 submit 始终未被框架启动: session={} turn={} 空等 {}ms",
                    cmd.getSessionId(), turnId, System.currentTimeMillis() - startedAt);
        } else if (!loop.stopped() && failure != null) {
            // 不看正文空不空：跑出过半截正文的那一跑同样是被 402／网关异常打死的。若只在
            // answer 空时才出声，症状是「气泡照报已完成、正文停在半句」，而错误卡与「这一轮中断」
            // 那句状态全都没长出来——用户得自己发现它没说完。
            // 叫停那一支不套用：那是用户自己按的，再说一句「AI 回复失败」就是把他的动作报成故障。
            spokenFailure = emitRunFailure(pool, channel, failure);
        }
        // 框架撞步数上限时补的那条收尾正文要接回来：它走 SUMMARY 用途，被 translate() 那道
        // 「只透传 REASONING」的闸挡在流外（那道闸是为压缩摘要与记忆提炼加的，那两样确实不该进聊天流）。
        // 不接的表现：正文停在半句，气泡照报「已完成」，而它其实一步都没做完——沙盒里已经生成的
        // 文件也没机会交付出去。只在<b>流里确有正文</b>时补：空正文那一支上面已经把这条收尾消息
        // 回退取走了（同一个 ModelOutputParser 喂两遍还会把推荐芯片数成两份）。
        String wrapUp = AgentxHistoryText.maxIterationsWrapUp(session, historyBefore);
        boolean cutByMaxIters = wrapUp != null && !loop.stopped() && !loop.timedOut() && !loop.neverLaunched() && failure == null;
        if (cutByMaxIters && streamedBody) {
            // 过同一个剥离器：这条收尾正文同样可能被模型塞进 <related>／<process>，
            // 而推荐芯片只在 related 那一份状态上算，绕过它等于这一轮最后那颗芯片凭空少一源
            String extra = AgentxHistoryText.stripFallbackBody(recording, wrapUp).strip();
            if (!extra.isEmpty() && !answer.contains(extra)) {
                String tail = "\n\n" + extra;
                answer = answer + tail;
                recording.answer(tail);
            }
        }
        if (cutByMaxIters) {
            channel.emit(RunEventType.WARN, "这一轮的推理步数已用满（上限 " + maxItersForTurn()
                    + " 步），活没干完就停了。发一句「继续」可以接着做");
        }
        // 卡片还开着、且没有被判超时：这一轮只是暂时没人跟——用户答了会有新的跟随者把它跑完并落库。
        // 这时候落一条「已中断」就是两件坏事：它说的话是假的（这轮并没有结束），等真正的答案
        // 来了历史里还多出一条气泡（同一个问题两个助手回答）。
        // 继续对话取代的那一轮不算「暂时没人跟」：中断之后不会再有跟随者，而这一版的「已被取代」
        // 必须随行落库（历史里要有这一版计划，新一轮也靠它取上一版），所以放行到 persist。
        boolean awaiting = !loop.timedOut() && !superseded && !pendingAtClose.isEmpty();
        long wallMs = System.currentTimeMillis() - startedAt;
        long machineMs = Math.max(0L, wallMs - interactions.humanWaitMs());
        // 报出去的「本轮耗时」说的是机器干活用了多久，不是这一轮在墙上挂了多久：人在计划卡上读了
        // 三分钟，读成「它答了三分多钟」就是把人自己的时间记到了机器账上。同一句里把两个数都留下，
        // 截断与卡死排障要看的是墙钟，成本与性能看的是机器时间——少一个数就必然有人拿错一个数对账。
        log.info("[TURN] 本轮收口: session={} 机器耗时={}ms 墙钟={}ms 开轮前准备={}ms 整轮墙钟={}ms 人工等待={}ms",
                cmd.getSessionId(), machineMs, wallMs, prepMs, wallMs + prepMs, interactions.humanWaitMs());
        // 目标模式轮终一拍：记账 → 判完成/受阻/安全帽 →（由 onUserTurnEnd 决定）续跑。
        // 放在 finish 之前：finish 会落库并发 done，目标态要先于那一帧定下来，前端才看得到一致的状态。
        // 工具调用数取本轮实例自己的步数闸（taken 是判过即占一步的口径，含被拒的）。
        // 本轮正文一起交给目标服务：助手在末尾写【目标完成】就是平台停下来的信号（见 GoalCopy.DONE_MARKER），
        // 写【目标受阻】则以受阻原因停住——没有验收标准，也没有让人逐条核验的中间态。
        int goalToolCalls = 0;
        AgentxStepBudget turnBudget = sessions.stepBudget(sessions.key(cmd));
        if (turnBudget != null) goalToolCalls = turnBudget.taken();
        // 没正常跑完的轮（额度/网关/没启动）不进目标续跑链：否则一次失败会被滚成无限重试。
        // 用户按了停止同理：停止就是「这一轮到此为止」的意思，不能自动再起一轮把停止撤销掉。
        boolean goalTurnHealthy = failure == null && !loop.neverLaunched();
        goalService.onRoundSettled(cmd.getSessionId(), cmd.getUserId(), turnId, machineMs, goalToolCalls,
                channel, answer, goalTurnHealthy, loop.stopped() || loop.stalled(), failure);
        // 收尾标记（【目标完成】/【目标受阻】…）是平台控制信号，落库与下发的正文里不出现：
        // 摘在 onTurnEnd 之后、finish 之前，用户读到的最后一条就是干净的交付说明。
        answer = com.wenqu.ai.service.GoalService.stripMarkers(answer);
        // 重启接续的轮：旧进程干到哪一步，新实例这边没有记录（AgentxTurnRecording 是新的一份），框架侧也没有
        // 待跑步骤——正文与过程两头空时，收尾判据会把整轮静默丢掉，历史里只剩提问、清单卡还写着已完成。
        // 这种轮必须落一句话交代清楚；本轮真跑出过东西（正文/工具/思考）就不算「空转」，交给正常收尾。
        String recoveredNote = orphanRecovered && failure == null && answer.isBlank()
                && recording.toolCalls.isEmpty() && recording.thinking.isEmpty()
                && !loop.stopped() && !loop.timedOut() && !ranOutBudget && !loop.stalled()
                && !loop.neverLaunched() && !superseded && !awaiting
                ? TurnRecorder.RECOVERED_NOTE : null;
        finish(cmd, channel, pool, route, hooks.modelRef(), contextWindow, answer, recording,
                userMessageId, loop.stopped(),
                loop.timedOut(), ranOutBudget, loop.stalled(), awaiting, startedAt, interactions.humanWaitMs(), prepMs,
                session, historyBefore, turnId, planRecord, recoveredNote);
        // 站内通知（旁路：内部全捕获，写不进去只记 WARN，绝不影响这一轮收口）：长轮跑完时
        // 人多半已经切走或锁屏，只靠屏幕看得见等于「跑完没人知道」。
        // 「提前停了」与「没跑成」分两句说：已经写出一半正文的那一轮，用户真读到了东西，
        // 把它报成「没跑成」等于说那段话不存在。
        // 铃铛只说人话：spokenFailure 就是 emitRunFailure 生成的那一句。若在它为空时退回
        // `failure`——那是上游原始串（「HTTP transport error during streaming: HTTP request failed
        // with status 403…」，英文、带响应体碎片），用户在铃铛里读不出「该充值还是该换模型」，
        // 而用户自己叫停的那一轮也会被报成故障。原始串留给日志，不进通知。
        String earlyExit = com.wenqu.ai.agent.chat.TurnCloseout.earlyExitReason(
                spokenFailure, loop.neverLaunched(), loop.timedOut(), ranOutBudget, loop.stalled(), cutByMaxIters);
        turnNotify.onTurnSettled(cmd, turnId, machineMs, recording.toolCallList().size(),
                loop.stopped() || loop.stalled(), awaiting, earlyExit, !answer.isBlank(),
                turnId != null && turnId.startsWith(com.wenqu.ai.service.GoalService.GOAL_TURN_ID_PREFIX));
    }


    /**
     * 把框架留下的失败痕迹说成用户能行动的一句话。判据与文案都取手写引擎那一组
     * （{@code ChatService} 的 stream-error 分支：额度／限流／连不上／其它）——同一件 402 在两条
     * 运行时里必须长成同一句话，否则灰度一切换，用户看到的指引就换了。
     *
     * @return 那句给用户看的话（站内通知复用它，不再抄上游原始串）
     */
    private String emitRunFailure(WenquSourcePool pool, RunChannel channel, String raw) {
        // 断流自己说一句：把它塞进上面那套「额度／限流／连不上」的分类会得到一句通用的
        // 「AI 回复失败，请稍后重试」——可那一轮其实<b>说出了半截话</b>，用户要的是「这段不完整」，
        // 不是「重试就好」。
        if (UPSTREAM_STREAM_CUT.equals(raw)) {
            String msg = "模型这一轮中途断流，上面这段可能不完整：上游没有给出结束标记就收了连接。可以发一句「继续」让它接着说";
            pool.registrar().degrade("upstreamStreamCut", msg);
            channel.emit(RunEventType.ERROR, msg);
            log.info("[agentx] 本轮断流收束: session 侧已提示不完整");
            return msg;
        }
        // 自家配额闸那句已经点名的规则、用了多少、什么时候恢复——比通用那句「额度不足去充值」准，比通用那句「额度不足去充值」准，
        // 原样交给用户（它本来就是写给人看的中文）。落到下面的通用分类会把「找管理员放宽月度预算」
        // 说成「去模型供应商充值」，指错方向。
        if (raw.startsWith(com.wenqu.ai.service.UsageQuotaService.REFUSAL_PREFIX)) {
            pool.registrar().degrade("tenantQuotaExhausted", raw);
            channel.emit(RunEventType.ERROR, raw);
            log.info("[agentx] 本轮被配额闸拦下: {}", raw);
            return raw;
        }
        boolean quota = com.wenqu.ai.service.ModelQuotaGuard.isQuotaText(raw);
        boolean limited = !quota && com.wenqu.ai.service.ModelQuotaGuard.isRateLimitedText(raw);
        // 静默判死那条（ModelUtils 的 timeout 包成 ModelException「Model request timeout after PT60S」，
        // 重试烧完再包成「Retries exhausted: 2/2」）必须单独说一句：通用那句「请稍后重试」不知道重试
        // 也是白重试——上游已经按设置的时长判死过两轮。指出的动作是「等一会再问」或「换个响应快的
        // 模型」，不是原地反复按发送。只认 timeout 这个词的话，Retries exhausted 那一支照样落进通用文案。
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        if (!quota && !limited && (lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("retries exhausted"))) {
            String msg = "模型服务没有响应：上游长时间不回内容，已按「模型无响应上限」判死并重试过仍无结果。"
                    + "稍后再问，或在「智能体 → 模型供应商」换一个响应快的模型";
            pool.registrar().degrade("modelUnresponsive", msg);
            channel.emit(RunEventType.ERROR, msg);
            log.info("[agentx] 本轮上游无响应收束: 原文={}", raw.length() > 200 ? raw.substring(0, 200) : raw);
            return msg;
        }
        boolean unreachable = !quota && !limited
                && raw.toLowerCase(java.util.Locale.ROOT).contains("connect");
        String msg = quota ? "模型服务额度不足：请到「模型供应商」充值，或改用其他模型"
                : limited ? "模型服务限流，请稍后重试"
                : unreachable ? "无法连接 AI 服务，请检查网络或 API 地址"
                : "AI 回复失败，请稍后重试";
        pool.registrar().degrade(quota ? "modelQuotaExhausted" : "streamError", "模型输出中断：" + msg);
        channel.emit(RunEventType.ERROR, msg);
        log.info("[agentx] 本轮失败收束: {} 原文={}", msg, raw.length() > 200 ? raw.substring(0, 200) : raw);
        return msg;
    }

    /** 重启后续跑的执行线程：单线程串行——同一条会话不该被两轮同时驱动。 */
    private static final java.util.concurrent.ExecutorService RESUME_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "agentx-resume");
                t.setDaemon(true);
                return t;
            });

    /** 续跑所需的一整套本轮上下文（重启接回与「答完没人跟」两条路共用同一段装配）。 */
    private record Following(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                             ModelRegistryService.ModelRoute route, WenquAgentxTools.TurnHooks hooks,
                             ExecutionPolicy policy,
                             AgentSession session, int historyBefore, long startCursor,
                             AgentxTurnRecording recording, int contextWindow, boolean recovered) {
    }

    /**
     * 为一轮续跑装配出本轮上下文：按归属重建（或复用）常驻会话实例、建通道、建引用池与本轮身份。
     * <p>通道挂在收集型 emitter 上——这一跑未必有真实观众，但事件仍走同一条 {@code sendSseEvent}
     * （账本、运行总线、落库都不变），断线重连那一份 HTTP 响应才是观众。
     *
     * @return null = 装配不动（模型解析不出来、实例建不起来），调用方按「接不动」处理
     */
    private Following openForContinuation(String sessionId, String userId, String turnId) {
        return openForContinuation(sessionId, userId, turnId, false);
    }

    /**
     * @param recover 这是不是「进程中断后的恢复轮」：恢复轮要先封存上一进程残留的会话写者、
     *                把未决工具结果标成未知，再归档遗留轮；否则框架的 inbox 会一直等那个
     *                已经不存在的 writer（2 分钟租约），表现为「目标卡在 EXECUTING 不动」。
     */
    private Following openForContinuation(String sessionId, String userId, String turnId, boolean recover) {
        return openForContinuation(sessionId, userId, turnId, recover, null, false);
    }

    /** 深度执行第 1 轮先出计划：与手动计划模式共用同一道 plan 门禁，批准后才继续执行。 */
    private Following openForContinuation(String sessionId, String userId, String turnId, boolean recover,
                                          String guestUid) {
        return openForContinuation(sessionId, userId, turnId, recover, guestUid, false);
    }

    /**
     * @param guestUid 非空=这一轮原本是<b>免登录分享会话</b>里的一轮：按访客口径挂工具面（沙盒/联网/MCP
     *                 一概不给），身份仍是发布者。少传这一项，接续出来的那一轮会比原来那一轮宽——
     *                 「访客接不回」和「访客拿到更多能力」都在这一个参数上。
     */
    private Following openForContinuation(String sessionId, String userId, String turnId, boolean recover,
                                          String guestUid, boolean planFirst) {
        return openForContinuation(sessionId, userId, turnId, recover, guestUid, planFirst, null);
    }

    /**
     * @param agentOverride 指定本轮作答方（目标续跑轮传目标行上的智能体）。空白=沿用会话列的解析。
     *                      <p>为什么目标轮必须显式带：会话列在「用户在问答页直接说话」的会话里
     *                      通常是空的（当轮才解析默认智能体），续跑轮只读会话列就会拿不到作答方，
     *                      权限档位随之掉回平台默认（用户轮 auto、目标轮逐次确认）。
     */
    private Following openForContinuation(String sessionId, String userId, String turnId, boolean recover,
                                          String guestUid, boolean planFirst, String agentOverride) {
        if (sessionId == null || sessionId.isBlank()) return null;
        String resolvedAgentId = agentOverride == null || agentOverride.isBlank()
                ? agentIdOfSession(sessionId) : agentOverride;
        ChatCommand cmd = ChatCommand.builder().sessionId(sessionId).question("")
                .userId(userId == null || userId.isBlank() ? "guest" : userId)
                .agentId(resolvedAgentId)
                .guestMode(guestUid != null && !guestUid.isBlank())
                .planMode(planFirst)
                .guestUid(guestUid)
                .build();
        com.wenqu.ai.service.ChatService.TurnModel turn = modelResolver.apply(cmd);
        String ref = turn.usable() ? turn.ref() : "";
        if (ref.isBlank()) return null;
        ModelRegistryService.ModelRoute route = modelRegistry.chatRoute(ref);
        if (route == null) return null;
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter sink =
                new com.wenqu.ai.service.CollectingSseEmitter();
        RunChannel channel = new com.wenqu.ai.engine.SseRunEventChannel(
                tools.runtime(), sink, sessionId);
        ExecutionPolicy policy = tools.policyFor(cmd);
        WenquSourcePool pool = tools.newPool(cmd, sink);
        java.util.concurrent.ConcurrentLinkedQueue<String> spills =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        WenquAgentxTools.TurnHooks hooks = tools.newHooks(cmd, ref, () -> turnId, spills,
                consultService.newLedger());
        AgentBinding binding = bindingOf(cmd, policy, ref);
        boolean sandboxWanted = sandboxIntent.wants(cmd, policy);
        AgentxTurnSlot slot = sessions.turnSlot(cmd);
        slot.noteProfile(sandboxWanted ? "workspace" : "chat");
        AgentSession session;
        com.wenqu.ai.service.EffectiveWorkspace ew = (sandboxWanted && sandboxService != null)
                ? sandboxService.resolveWorkspace(cmd.getSessionId(), cmd.getUserId(), true) : null;
        String wsKey = ew == null ? "" : ew.key();
        try {
            session = sessions.open(cmd, ref,
                    binding.key() + "|sb=" + (sandboxWanted ? "1" : "0") + "|ws=" + wsKey,
                    ctx -> buildAgent(cmd, policy, route, hooks, binding, slot, sandboxWanted, ew),
                    sandboxWanted);
        } catch (Exception e) {
            log.warn("[agentx] 重建会话失败 session={}: {}", sessionId, e.toString());
            return null;
        }
        // 续跑轮和正常轮共用同一道工具组闸：不在这里补，目标/通知续跑建出来的实例里
        // 框架文件工具没有组，档位收紧就退化成「模型看得见、调了才被拒」（巡检的 tools warn 就是它）。
        for (String surfaceProblem : sessions.applyToolSurface(cmd, tools.turnGroupedNames(cmd, policy), policy)) {
            channel.emit(RunEventType.WARN, surfaceProblem);
        }
        // 深度执行第 1 轮：进计划位（与手动计划模式同一道 plan 门禁）。这里原来只把 planFirst
        // 传下来却没进计划位，于是 UI 承诺的「先出计划」从来没有兑现过——第一轮直接开干。
        if (planFirst) {
            boolean engaged = sessions.setPlanMode(cmd, true);
            log.info("[GOAL] 第 1 轮进入计划位: session={} engaged={}", sessionId, engaged);
            if (!engaged) {
                channel.emit(RunEventType.WARN, "计划模式未能启用（框架侧未进入计划位），本轮直接执行");
            }
        }
        // 目标续跑也要先收拾「上一轮没被框架记成结束」的遗留：删掉旧目标/暂停旧目标时，
        // 它可能刚好有一轮进了收件箱还没启动；不归档，新目标的第一轮会一直排在那条死轮后面。
        if (turnId != null && turnId.startsWith("goal:")) {
            if (recover) sessionGateway.recoverInterruptedSession(session, turnId);
            sessionGateway.reconcileStalledTurn(session, cmd.getSessionId(), sessions::abandoned);
        }
        AgentxStepBudget stepBudget = sessions.stepBudget(sessions.key(cmd));
        if (stepBudget != null) {
            stepBudget.beginTurn(turnId, tools.maxToolSteps(cmd), (code, message) -> {
            }, text -> sessions.steer(cmd.getUserId(), cmd.getSessionId(), text));
        }
        // 续跑轮同样要归零重复调用闸：不归零，上一轮最后那条命令会把这一轮的第一步判成重复
        AgentxRepeatGuard repeatGuard = sessions.repeatGuard(sessions.key(cmd));
        if (repeatGuard != null) repeatGuard.beginTurn(null);
        // 接续轮同样要更新档位闸标签：否则被拒的工具在日志里挂到上一轮头上。
        AgentxFrameworkToolGate toolGate = sessions.frameworkToolGate(sessions.key(cmd));
        if (toolGate != null) {
            toolGate.beginTurn(turnId);
            // 完成检查轮只允许读工具 + goal_submit/goal_note：不靠 prompt 自觉，直接在工具闸上收口。
            toolGate.setCompletionCheck(goalService.isCompletionCheck(turnId));
        }
        AgentxTurnRecording recording = new AgentxTurnRecording(spills, hooks.consultLedger(), tools, hooks, displayNames,
                cmd.getUserId());
        bindFrameworkToolGate(cmd, recording);
        return new Following(cmd, channel, pool, route, hooks, policy, session,
                session.transcript().messages().size(), session.log().head().seq(), recording,
                binding.contextWindow(), recover);
    }

/**
 * 供 builder 用的框架文件系统。
 *
     * <p><b>为什么走 builder 而不是 RuntimeContext</b>：框架的文件工具在 build 期就绑好了文件系统
     * （{@code HarnessAgent:2895} {@code new FilesystemTool(filesystem, ...)}、
     * {@code :2903} {@code new ShellExecuteTool(sandbox)}），两者都读 builder 字段，
     * 对 {@code RuntimeContext} 里的 {@code AbstractFilesystem} 无感。
     * 只往 ctx 里 put 时，{@code execute("pwd")} 返回的是宿主项目目录。
     *
     * <p>拿不到时返回 null（沙盒不可用）——框架会退回默认文件系统，
     * 不为了「有工具就挂」而给一个指向宿主的文件系统：那会让隔离静默失效。
     */
    private io.agentscope.harness.agent.filesystem.AbstractFilesystem frameworkFilesystem(
            ChatCommand cmd, boolean unrestrictedWrites, boolean sandboxEnabled) {
        return frameworkFilesystem(cmd, unrestrictedWrites, sandboxEnabled, null);
    }

    /** ew 非空时用建实例那轮的解析结果决定文件系统（与实例键同源）；null=旁路现查。 */
    private io.agentscope.harness.agent.filesystem.AbstractFilesystem frameworkFilesystem(
            ChatCommand cmd, boolean unrestrictedWrites, boolean sandboxEnabled,
            com.wenqu.ai.service.EffectiveWorkspace ew) {
        if (sandboxService == null) return null;
        String uid = cmd.getUserId();
        String sessionId = cmd.getSessionId();
        if (uid == null || uid.isBlank() || sessionId == null || sessionId.isBlank()) return null;
        String memoryAgentId = memoryAgentIdOf(cmd);
        if (ew != null) {
            if (!sandboxEnabled || ew.kind() == com.wenqu.ai.service.EffectiveWorkspace.Kind.CHAT_SCRATCH) {
                return sandboxService.chatScratchFilesystem(sessionId, uid, memoryAgentId);
            }
            if (ew.kind() == com.wenqu.ai.service.EffectiveWorkspace.Kind.IMPLICIT_CLOUD
                    && !sessions.sandboxAttached(sessions.key(cmd))) {
                return sandboxService.chatScratchFilesystem(sessionId, uid, memoryAgentId);
            }
            return sandboxService.frameworkFilesystem(ew, unrestrictedWrites,
                    sessions.sandboxAttached(sessions.key(cmd)), memoryAgentId);
        }
        // 聊天档：宿主会话临时目录（文件工具可用，shell 不注册），不建容器。
        // 工作区档若沙盒没装配成功（超时/失败），也退到同一份临时目录，避免回落到宿主共享目录。
        boolean localProject = false;
        try {
            com.wenqu.ai.model.Project p = projectService.projectOfSession(sessionId);
            localProject = p != null && projectService.isDirect(p);
        } catch (RuntimeException e) {
            log.debug("[PROFILE] 工作区类型解析失败，按沙盒状态判文件系统 session={}: {}",
                    sessionId, e.toString());
        }
        // 隐式会话的本机后端（工作区=用户电脑）：没有 SandboxContext，用会话目录是否就绪来判
        boolean localSession = false;
        if (sandboxEnabled && !localProject) {
            try {
                localSession = sandboxService.localSessionWorkspace(sessionId, uid);
            } catch (RuntimeException e) {
                log.warn("[PROFILE] 本机会话目录解析失败，本轮按沙盒状态判文件系统 session={}: {}",
                        sessionId, e.toString());
            }
        }
        if (!sandboxEnabled || (!localProject && !localSession
                && !sessions.sandboxAttached(sessions.key(cmd)))) {
            return sandboxService.chatScratchFilesystem(sessionId, uid, memoryAgentId);
        }
        return sandboxService.frameworkFilesystem(sessionId, uid, unrestrictedWrites,
                sessions.sandboxAttached(sessions.key(cmd)), memoryAgentId);
    }

    /**
     * 把本轮的工具卡记录器交给框架工具档位闸。
     *
     * <p>框架工具走框架 Toolkit，其执行不经过问渠的 {@code rawCallbacks} 通道，
     * 工具卡只能由闸自己从 {@code ToolUseBlock} 取入参往 {@code AgentxTurnRecording} 报。
     * 闸在 build 期就挂上、AgentxTurnRecording 在建实例后才建，故这里做一次绑定而不是构造期注入。
     */
    private void bindFrameworkToolGate(ChatCommand cmd, AgentxTurnRecording recording) {
        AgentxFrameworkToolGate gate = sessions.frameworkToolGate(sessions.key(cmd));
        if (gate != null) {
            gate.bindRecording(recording);
        }
    }

    /** 占到跟随位才起这一跑：同一条会话不许有两个循环对着跟（各读一份事件流就会各落一条助手消息）。 */
    private void startFollowing(Following f, AgentSession.Task current) {
        startFollowing(f, current, () -> {
        });
    }

    /**
     * 同上，外加一条「这一跑没起来 / 已经跑完」的通知。
     * <p>后台目标轮要把自己登记在运行总线上，而总线靠 {@code turnAlive} 判生死：没有这个回调，
     * 抢位失败那一支（下面直接 return）会把登记留在总线上，前端探测到一条永远不会结束的「在跑」。
     */
    private void startFollowing(Following f, AgentSession.Task current, Runnable onSettled) {
        startFollowing(f, current, onSettled, false);
    }

    /**
     * @param alreadyReserved 调用方已经抢到跟随位（目标续跑先抢位再 submit）：这里不再抢，
     *                        失败时由调用方释放。不先抢位就 submit 的后果是——上一轮还在收尾时
     *                        submit 出去的那一轮没有人跟，事件不落库、目标轮数也白涨。
     */
    private void startFollowing(Following f, AgentSession.Task current, Runnable onSettled,
                                boolean alreadyReserved) {
        String sessionKey = sessions.key(f.cmd());
        if (!alreadyReserved && !sessions.reserveFollower(sessionKey)) {
            log.info("[agentx] 这一轮已经有跟随者在跑，不重复起 session={}", f.cmd().getSessionId());
            onSettled.run();
            return;
        }
        // 接回的那一轮也要占住槽位：工具与脚本通道读的是「当前轮」，不 begin 就会拿到上一轮
        AgentxTurnSlot slot = sessions.turnSlot(f.cmd());
        slot.begin(tools.newTurn(f.cmd(), f.pool(), f.channel(), f.policy(), f.hooks(),
                slot.workspaceMemoryTainted()));
        tools.ensureRunCodeTurn(slot, f.cmd());
        String turnId = f.hooks().turnId().get();
        if (turnId != null && turnId.startsWith("goal:")) {
            // 续跑轮的目标工具不依赖用户轮的 goalNote；槽位 begin 刚清过标记，这里补回来。
            slot.noteGoalActive(true);
        }
        RESUME_POOL.execute(() -> {
            try {
                // 用 hooks 里那一个轮号（openForContinuation 传入的那个），不重新问 TurnRegistry：
                // 后台续跑轮没有轮级外壳登记，turnIdOf 会拿不到把手而回落会话号，
                // 那一轮的账本归属就全挂错了
                followTurn(f.session(), f.cmd(), f.channel(), f.pool(), f.route(), f.hooks(),
                        turnId, f.historyBefore(), f.startCursor(), null, current,
                        // 续跑轮 prepMs 传 0：这一跑不是新问答，cmd 里那个「收到提问」的时刻属于
                        // 原来那一拍，拿它当基准会把「人隔了几天才回来答卡」算进开轮前准备。
                        f.recording(), System.currentTimeMillis(), 0L, f.contextWindow(), f.recovered());
                log.info("[agentx] 续跑已收尾 session={}", f.cmd().getSessionId());
            } catch (Exception e) {
                log.warn("[agentx] 续跑中断 session={}: {}", f.cmd().getSessionId(), e.toString());
                goalService.onRoundSettled(f.cmd().getSessionId(), f.cmd().getUserId(), turnId,
                        0, 0, f.channel(), "", false, false, "跟随循环中断: " + e.getMessage());
            } finally {
                sessions.releaseFollower(sessionKey);
                slot.end(turnId);
                // 后台轮异常/提前退出没走到 done 时也要摘收据（正常路径在 followTurn 里已摘，幂等）
                com.wenqu.ai.agent.chat.SteerReceipts.clear(turnId);
                // 后台目标轮跑完：只摘掉「这一条会话的这一个轮」——旧目标轮收尾不能把新目标在飞的那一轮摘掉
                if (turnId != null && turnId.startsWith("goal:")) {
                    backgroundGoalTurns.remove(f.cmd().getSessionId() + "|" + turnId);
                }
                // 目标轮占的是与用户轮同一把会话轮锁（GoalService.launchRound 抢的），
                // 跟着跟随循环一起还回去——不还，这条会话在 130 分钟租约内再也发不出新问题。
                if (turnId != null && turnId.startsWith("goal:")) {
                    goalService.releaseSession(f.cmd().getSessionId());
                    // 叫停标记留到这一轮真正收场之后才摘：产物结算与落库都在 finish 里，
                    // 提早摘掉会让「已取消的一轮」把产物又交付出去。
                    goalService.clearTurnAbort(turnId);
                }
                onSettled.run();
                // 跟随位释放之后再接下一轮（放在收尾里发会被「已有跟随者」挡掉，目标循环走不到第三轮）。
                // 传这一轮的 turnId，服务端据此区分用户轮收尾与目标轮收尾（让位停下的目标会自动接上）。
                if (turnId != null && turnId.startsWith("goal:")) {
                    goalService.onUserTurnEnd(f.cmd().getSessionId(), f.cmd().getUserId(), turnId);
                }
            }
        });
    }

    /**
     * 目标模式跨轮续跑（控制流归框架，问渠只数预算并发起下一轮）。
     *
     * <p>为什么复用 {@link #openForContinuation} 而不是新写一套：那一套已经解决了「无浏览器通道
     * 怎么跑一轮」——收集型 emitter 包成 RunChannel，事件照常走 sendSseEvent（账本、镜像、落库
     * 都不变），RESUME_POOL 单线程串行跟到尾。断线重连那一份 HTTP 响应才是观众。
     *
     * <p>幂等：{@code idempotencyKey = "goal:{goalId}:{n}"}，turnId 由框架按 key 派生
     * （{@code UUID.nameUUIDFromBytes}）——进程重启后重发同一 key 不会重复起轮。
     *
     * <p>接流：后台轮登记上运行总线（收集型 emitter 当发起通道），deep_turn 帧随后下发，
     * 前端据此自动 {@code GET /chat/run} 探测 + {@code GET /chat/resume} 接上下一轮。
     */
    public void continueGoalTurn(com.wenqu.ai.model.Goal goal, String input, int n) {
        continueGoalTurn(goal, input, n, 0);
    }

    /**
     * 目标轮异步发起入口（目标续跑驱动 {@code GoalTurnDriver} 绑定的就是它）：把 {@link #continueGoalTurn} 的
     * 同步重活（实例冷启动装配）挪出调用方线程——POST /deep 建行即回包，目标条 / 角标 / 左侧会话列表立即出现，
     * 后台轮在实例就绪后登记总线、由前端轮询接流。内部重试（reserveFollower 抢不到）仍走 GOAL_RETRY 调度，不经此池。
     */
    public void submitGoalTurn(com.wenqu.ai.model.Goal goal, String input, int n) {
        try {
            GOAL_KICK.execute(() -> {
                try {
                    continueGoalTurn(goal, input, n, 0);
                } catch (RuntimeException e) {
                    log.warn("[GOAL] 目标轮异步发起失败: goal={} n={} {}", goal.getId(), n, e.toString());
                    // 不把目标留在 EXECUTING 等租约过期：起轮阶段就失败，立刻转暂停让用户看见。
                    goalService.markContinueFailed(goal, e.getMessage());
                    // 起轮阶段异常可能发生在任何一步：会话锁在这里兜一次，避免整条会话被锁死
                    goalService.releaseSession(goal.getSessionId());
                }
            });
        } catch (RuntimeException rejected) {
            // 执行池已关闭（停机中）：这一轮没提交出去，锁与目标状态都要当场收掉
            log.warn("[GOAL] 目标轮执行池拒绝提交: goal={} {}", goal.getId(), rejected.toString());
            goalService.markContinueFailed(goal, rejected.getMessage());
            goalService.releaseSession(goal.getSessionId());
        }
    }

    private void continueGoalTurn(com.wenqu.ai.model.Goal goal, String input, int n, int attempt) {
        String sessionId = goal.getSessionId();
        String uid = goal.getUserId();
        String turnId = com.wenqu.ai.service.GoalService.GOAL_TURN_ID_PREFIX + goal.getId() + ":" + n;
        // 重试支路：等跟随位时把会话锁还给了用户轮，重新起跑要重新抢一次，抢不到继续让位。
        if (attempt > 0) {
            if (!goalService.acquireSessionForTurn(sessionId)) {
                if (attempt >= GOAL_RETRY_MAX) {
                    goalService.markContinueFailed(goal, "会话一直被用户轮占用，目标轮没排上");
                    return;
                }
                GOAL_RETRY.schedule(() -> continueGoalTurn(goal, input, n, attempt + 1),
                        GOAL_RETRY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            }
        }
        // 提交前复查：从「上一次收尾决定起下一轮」到这一行之间，用户可能刚停止/删除/改了目标。
        // 不复查就会把一轮已经没人要的续跑放出去（界面上表现为「删了目标它还在跑」）。
        if (!goalService.runnable(goal)) {
            log.info("[GOAL] 目标已不在进行中，取消这一轮续跑: goal={} n={}", goal.getId(), n);
            goalService.releaseSession(sessionId);
            return;
        }
        boolean recover = "ORPHANED".equals(goal.getErrorClass());
        Following f = openForContinuation(sessionId, uid, turnId, recover, null, n == 1, goalAgentId(goal));
        if (f == null) {
            goalService.markContinueFailed(goal, "会话实例装配不出来（模型/路由解析失败）");
            goalService.releaseSession(sessionId);
            return;
        }
        // 冷启动装配可能十几秒：这段时间里用户完全可能已经取消/停止。复查一次再提交，
        // 否则就是线上那条「17:48:32 取消、17:48:39 照常起跑」。
        if (!goalService.runnable(goal) || goalService.goalTurnAborted(turnId)) {
            log.info("[GOAL] 装配期间目标已被取消/停止，丢弃这一轮续跑: goal={} n={}", goal.getId(), n);
            goalService.releaseSession(sessionId);
            return;
        }
        String sessionKey = sessions.key(f.cmd());
        // 先抢跟随位再 submit：上一轮（多半是刚被停止/删除的旧目标轮）还在收尾时，
        // 直接 submit 会让这一轮没人跟——事件不落库、轮数白涨，界面上就是「目标卡在跑但没动静」。
        // 抢不到就延迟重试；旧轮通常几秒内释放，30 秒仍抢不到如实转暂停。
        if (!sessions.reserveFollower(sessionKey)) {
            if (attempt >= GOAL_RETRY_MAX) {
                log.warn("[GOAL] 续跑抢不到跟随位，转暂停: goal={} n={}", goal.getId(), n);
                goalService.markContinueFailed(goal, "上一轮还在收尾，没抢到跟随位");
                goalService.releaseSession(sessionId);
                return;
            }
            log.info("[GOAL] 上一轮还在收尾，{}/{} 次后重试续跑: goal={} n={}", attempt + 1,
                    GOAL_RETRY_MAX, goal.getId(), n);
            // 等位期间把会话锁还回去：攥着锁等 30 秒，用户这期间连一句话都发不出去。
            goalService.releaseSession(sessionId);
            GOAL_RETRY.schedule(() -> continueGoalTurn(goal, input, n, attempt + 1),
                    GOAL_RETRY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        try {
            // 提交前最后一道：跟随位是异步抢的，等位/重试期间目标可能已被取消
            if (!goalService.runnable(goal) || goalService.goalTurnAborted(turnId)) {
                log.info("[GOAL] 抢到跟随位时目标已不在进行中，取消提交: goal={} n={}", goal.getId(), n);
                sessions.releaseFollower(sessionKey);
                goalService.releaseSession(sessionId);
                return;
            }
            AgentSession.Task task = f.session().submit(
                    com.wenqu.ai.service.GoalService.GOAL_TURN_ID_PREFIX + goal.getId() + ":" + n, input);
            backgroundGoalTurns.add(sessionId + "|" + turnId);
            // 先登记总线再发帧：deep_turn 到浏览器时，它探测 /chat/run 必须已经探得到这一轮
            java.util.concurrent.atomic.AtomicBoolean alive = openGoalTurnRun(f, sessionId, uid, turnId);
            goalService.emitTurn(goal, n, turnId, f.channel());
            // 从这里起，会话锁归跟随循环的 finally 释放（与 follower 同一次收尾）
            startFollowing(f, task, () -> alive.set(false), true);
        } catch (RuntimeException e) {
            log.warn("[GOAL] 续跑轮提交失败: goal={} n={} {}", goal.getId(), n, e.toString());
            goalService.markContinueFailed(goal, e.getMessage());
            sessions.releaseFollower(sessionKey);
            goalService.releaseSession(sessionId);
        }
    }

    /**
     * 把后台目标续跑轮登记上运行总线——不登记的话这一轮只进账本，浏览器上什么都看不见，
     * 用户得刷新才读得到。
     *
     * <p>「不另开总线 run」的顾虑仍然成立：{@code ChatRunBus.open} 是同会话<b>替换</b>语义，
     * 顶掉一条活着的用户轮会把它的 done 帧与观众一起切掉。所以这里只顶「上一轮目标轮」——那一条
     * 已经发过 done、正等巡检回收；探到的是用户自己那一轮就照旧不登记，这一轮仍按账本回显。
     *
     * <p>宽限回调恒返回 false：后台轮本来就没有观众，掐它等于把用户定下的目标半路作废，
     * 该由谁收口是目标自己的预算说了算（见 {@code GoalService#kickContinuation}）。
     *
     * @return 「这一轮还活着」的开关，跟随跑完由调用方落下——总线靠它回收登记
     */
    private java.util.concurrent.atomic.AtomicBoolean openGoalTurnRun(Following f, String sessionId,
                                                                      String uid, String turnId) {
        java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        if (!chatRunBus.enabled() || uid == null || uid.isBlank()) return alive;
        com.wenqu.ai.service.ChatRunBus.Run prev = chatRunBus.running(sessionId);
        if (prev != null && !prev.turnId().startsWith("goal:")) {
            log.info("[GOAL] 用户自己那一轮还在跑，后台轮不顶总线: session={}", sessionId);
            return alive;
        }
        Map<String, Object> pending = Map.of("running", true);
        chatRunBus.open(sessionId, turnId, uid, f.channel().emitter(), () -> pending,
                () -> false, alive::get);
        return alive;
    }

    /**
     * 进程重启后接回一条待办并把它跑完（{@link FrameworkSessions.ResumeHandler} 的实现）。
     * <p>为什么能做：框架的会话状态本来就持久化在 stateStore 里，缺的只是「内存里那条常驻实例」。
     * 按表里的归属重建实例、respond 进去、跟着跑到收尾并落库，用户刷新就能看到那一轮的答案，
     * 而不是「已批准」之后什么都没发生。
     * <p>分工：respond <b>同步</b>做（控制器要当场知道这次裁决有没有接上，接不上才如实报失效），
     * 续跑<b>异步</b>做（可能跑几分钟，不能占住那次 HTTP 请求）。
     */
    @Override
    public AgentSession.Task resume(String sessionId, String userId, String turnId, String requestId,
                                    String kind, boolean approved, String reason, String answer) {
        if (requestId == null) return null;
        Following f = openForContinuation(sessionId, userId, turnId);
        if (f == null) return null;
        AgentSession.Task task;
        try {
            task = sessions.respondRaw(sessions.key(f.cmd()), requestId, kind, approved, reason, answer);
        } catch (Exception e) {
            log.warn("[agentx] 重启后 respond 失败 {} session={}: {}", requestId, sessionId, e.toString());
            return null;
        }
        if (task == null) return null;
        startFollowing(f, task);
        return task;
    }

    /**
     * 卡片已答复、但这一轮没有活着的跟随者（跟随循环撞到墙钟上限退了、或进程重启过）：补起一个跟到收尾。
     * <p>不补的表现为「答了却什么都没发生」：续跑那一跑重新执行了工具、模型也
     * 重新推理出了正文，事件全在框架的 Session Log 里，只是没有人在读，正文也就没落库。
     */
    @Override
    public void follow(String sessionId, String userId, String turnId, boolean recover, String guestUid) {
        String sessionKey = (userId == null || userId.isBlank() ? "guest" : userId) + "|" + sessionId;
        if (sessions.hasFollower(sessionKey)) {
            // 必须说出来：扫描器报「已交给引擎接续」而这里因为租约还亮着（可能属于一个刚被硬杀、
            // 来不及释放的副本）直接不看，用户的屏幕上就还是那条没人回答的问题。
            log.info("[agentx] 这一轮已有跟随者（或上一副本的租约未到期），本轮不重复接 session={} turn={}",
                    sessionId, turnId);
            return;
        }
        Following f = openForContinuation(sessionId, userId, turnId, recover, guestUid);
        if (f == null) {
            // 接不动必须说：扫描器报「已交给引擎接续 N 条」而这里悄悄返回 null，
            // 用户看到的就是「扫到了、也交接了、什么都没发生」。
            log.warn("[agentx] 这一轮接不回来（模型解析不到或会话重建失败）session={} turn={} user={}",
                    sessionId, turnId, userId);
            return;
        }
        startFollowing(f, TurnSessionGateway.taskForTurn(f.session(), turnId));
    }

    /**
     * 本轮生效的模型上下文窗口（token），落到 {@link OpenAIChatModel} 的
     * {@code contextWindowSize}。框架的压缩阈值与输入预算都由它推导：
     * <ul>
     *   <li>{@code CompactionMiddleware#onReasoning}：{@code window - reserved(20k)} 为动态压缩阈值</li>
     *   <li>{@code HarnessContextBuilder#inputLimit}：{@code window - output - safety} 为输入上限，
     *       再减去「系统提示词＋运行时投影＋工具声明」才是<b>对话</b>那一份的压缩阈值</li>
     * </ul>
     * 问渠登记的窗口优先于框架内置表：登记值是模型真实口径，框架 {@code ModelContextWindows} 按前缀
     * 猜名、对中文大模型常缺条目（{@code deepseek-v4.1-flash} 在框架的 OPENAI 表里就查不到）。
     * <p><b>返回 0 只有一种情况</b>：模型没登记窗口（或引用解析不出登记行）。这时调用方必须
     * <b>整个不调</b> {@code contextWindowSize}——该 setter 的判据是 {@code >= 0 即用显式值}，
     * 把 0 塞进去等于向框架声明「窗口=0」，框架随即改用 64000 的兜底输入上限，
     * 大窗口模型也会被压成很早就折一次上下文。
     * <p>窗口一律取模型登记上限，界面不提供「上下文窗口档位」选择，
     * 因为「选小窗口」省下的只是压缩时机，代价是检索资料被提前折掉且界面看不出差别——
     * 用户要的是「带上更多资料」，那件事该由检索与压缩参数管，不该由一个窗口下拉框代答。
     */
    private int modelContextWindow(String modelRef) {
        com.wenqu.ai.model.ModelInfo mi = modelRegistry.modelInfoOf(modelRef);
        Integer max = mi == null ? null : mi.getContextWindow();
        return max == null || max <= 0 ? 0 : max;
    }

    /**
     * 模型登记的最大输出 token（作为 max_tokens 随请求下发）；未登记返回 0=不下发，交厂商默认。
     * <p>为什么必须有这一刀：单次补全上限若只沿用厂商默认，模型把大产物内联进
     * 工具参数时输出会在参数中途被截断，框架只收到空参 {}。
     * 口径与 {@link #modelContextWindow} 同源：登记什么下发什么，登记为空行为零变化。
     */
    private int modelMaxOutput(String modelRef) {
        com.wenqu.ai.model.ModelInfo mi = modelRegistry.modelInfoOf(modelRef);
        Integer max = mi == null ? null : mi.getMaxOutput();
        return max == null || max <= 0 ? 0 : max;
    }

    /**
     * 本轮墙钟上限（秒），恒定取问答侧 SSE 超时：两套运行时共用一个天花板。
     * <p>框架（AgentScope）对单把工具有自带的 5 分钟硬超时（{@code ExecutionConfig.TOOL_DEFAULTS}，
     * 不重试），不显式覆盖就会多一道没人知道的天花板——咨询子轮、沙盒长命令都会撞上去。
     * 工具超时跟着整轮超时走：SSE 调大时长工具不会被框架固定的 5 分钟砍掉。
     * <p>{@code chat.sseTimeoutMs} 填 0 说的是「整轮不截断」，<b>不是</b>「单条命令可以挂死」：那一档
     * 仍要一个有限值，否则 {@code max(30, 0/1000)} 会把每把工具压到 30 秒，放开限制反而变成最严的天花板。
     * <p>整轮超时本来就是唯一该有的天花板，不需要再给框架引擎单配一份墙钟。
     */
    private int turnSeconds() {
        int ms = configService.getInt("chat.sseTimeoutMs", 0);
        return ms <= 0 ? UNLIMITED_TOOL_SECONDS : Math.max(30, ms / 1000);
    }

    /**
     * 框架的推理轮数上限（一次模型调用＋其工具派发为一轮）。
     * <p>0=不限制只能以大数表达：框架的 {@code ReactConfig} 构造器对 {@code maxIters<=0} 直接抛
     * {@code IllegalArgumentException}。取 {@code Integer.MAX_VALUE} 安全——框架侧只做
     * {@code iter >= maxIters} 比较与拼日志，不参与加减。
     */
    private int maxItersForTurn() {
        int v = ConfigDefaults.CHAT_AGENTSCOPE_MAX_ITERS;
        return v <= 0 ? Integer.MAX_VALUE : v;
    }


    /**
     * 开局就判不了的收场：error 说清原因，再补一条最小 done——SSE 读循环「流关闭却没有 done」
     * 会把它误报成「连接被提前关闭」，用户看到的就是莫名其妙的中断错。
     * <p>先把那一问落库再收口：这三支柱都排在正常轮次的提问落库之前，不补这一句，用户刷新一次
     * 读到的就是「我刚才问的话呢」——问没留下、答没有、当场看见的错误卡也随流走了。
     */
    private void failLoud(ChatCommand cmd, RunChannel channel, String message) {
        appendQuestionOnFailure(cmd);
        channel.emit(RunEventType.ERROR, message);
        channel.emit(RunEventType.DONE,
                "{\"finalContent\":\"\",\"sources\":[],\"degradations\":[],\"engine\":\"agentscope\"}");
        channel.complete();
        // 开局就判不了也是「没跑成」：这一支不经过 finish（那一串收口变量压根没建起来），
        // 不补这一句，模型都解析不出来的轮次是通知面上唯一的空白——而它恰恰是最该喊人的那种
        turnNotify.onTurnSettled(cmd, null, 0L, 0, false, false, message, false, false);
        // webhook 出站同步挂上（最小载荷）：开局即败的轮对宿主系统同样是「没结果」这一事实
        agentWebhookService.onTurnFinished(cmd, null, "failed", message, "", null, Map.of(), List.of(), 0L);
    }

    /**
     * 提问行的一次落库——正常轮与失败轮共用，这一行的形状只在这里定义一遍。
     * <p>编辑重发要挂上被替换旧分支的版本组键（与工作流路径同口径）：被编辑的旧分支已在控制器
     * 同步段软删留档，不挂组键的话「‹ n/N ›」切换器出不来，被替换的旧版本也无法回看。
     */
    private String appendQuestion(ChatCommand cmd, String turnId, List<String> userImgUrls) {
        String messageId = sessionService.appendMessage(MessageWrite.builder()
                .sessionId(cmd.getSessionId())
                .role("user")
                .content(cmd.getQuestion())
                .images(userImgUrls == null || userImgUrls.isEmpty() ? null : userImgUrls)
                .attachments(attachments.metasJson(cmd.getAttachments(), cmd.uploadOwnerUid()))
                .mentions(TurnPromptAssembler.mentionsJson(cmd.getMentions()))
                .historyRefs(TurnPromptAssembler.historyRefsJson(cmd.getHistoryRefs()))
                .turnId(turnId)
                .build());
        if (messageId != null && cmd.getEditVariantGroup() != null && !cmd.getEditVariantGroup().isBlank()) {
            sessionService.setMessageVariant(messageId, cmd.getEditVariantGroup());
        }
        return messageId;
    }

    /**
     * 失败轮的那一问。图片走 {@code processDisplay}（只落盘回显）：本轮压根没进模型，
     * 谈不上「这个模型读不读得懂图」，但用户贴的图该跟着他的问题一起留在对话里。
     */
    private void appendQuestionOnFailure(ChatCommand cmd) {
        if (cmd.isRegenerate() || cmd.isFromSteer()) return;   // 提问在上一句请求里已经落过
        if (cmd.getQuestion() == null || cmd.getQuestion().isBlank()) return;
        try {
            appendQuestion(cmd, turnIdOf(cmd), userImages.processDisplay(cmd.getUserImages()).stream()
                    .map(com.wenqu.ai.service.UserImageService.UserImage::url).toList());
        } catch (Exception e) {
            // 问没留住不碍「这一轮失败」这件事本身：帧照发、日志照记，只是刷新后读不回这一句
            log.warn("[FAIL-LOUD] 失败轮的提问落库失败: session={} {}", cmd.getSessionId(), e.getMessage());
        }
    }





    /** 各形态的等待预算：提问/计划/工具审批各走独立配置键（提问最需要人从容看完题面，不和审批挤短预算）。 */
    private long pendingBudgetMs(PendingKind kind) {
        return switch (kind) {
            case PLAN -> configService.getLong("chat.planTimeoutMs", 3600000L);
            case ASK -> configService.getLong("chat.askTimeoutMs", 3600000L);
            case APPROVAL -> configService.getLong("chat.approvalTimeoutMs", 600000L);
        };
    }

    /**
     * 这一轮是不是「团队成员会话」（决定要不要挂直播抄送）。
     * <p>两条判据任一成立即可：
     * <ul>
     *   <li>命令里带了成员身份（{@code memberSessionId}）——成员页自己发起的轮；</li>
     *   <li>会话行 {@code kind=team}——框架唤醒起的成员轮、以及成员页刷新后重发的那一轮。
     *       根会话（lead）的唤醒也走 {@code source=team}，但它的会话行不是 team，不该被当成成员直播。</li>
     * </ul>
     * 查库失败按「不是成员」处理：这里判错的代价只是少一条直播（前端照旧读历史），
     * 不能让它变成打断本轮的理由。
     */
    private boolean isTeamMemberSession(ChatCommand cmd) {
        if (cmd.getMemberSessionId() != null && !cmd.getMemberSessionId().isBlank()) return true;
        try {
            var s = sessionService.sessionById(cmd.getSessionId());
            return s != null && com.wenqu.ai.service.SessionService.KIND_TEAM.equals(s.getKind());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 这一轮有没有在场观众能答审批卡：网页问答（source 空/user）才有；定时任务/工作流没有。 */
    private static boolean interactiveTurn(ChatCommand cmd) {
        return AgentxTextUtil.interactiveTurn(cmd);
    }

    /**
     * 提问卡前台等待到点：把「还没收到作答，问题仍可答」送回原工具，让模型接着往下走，
     * <b>不</b>把这一轮掐掉，也<b>不</b>把这张卡作废。
     * <p>与 DSH 的 timed 提问同语义：超时是 pending，不是用户跳过——卡片留在界面上仍可作答，
     * 答案稍后作为一条新的用户消息送进会话（跑着就插话、空闲就新一轮）。写成「用户未回答」
     * 会让模型连下一轮都不再问。
     * <p>走 {@code respondRaw} 而不是 {@code respondAsk}：后者末尾会 {@code ensureFollowing} 补起跟随者，
     * 而这里本来就有一个活着的跟随循环在跑，补第二个会有两个循环读同一条会话、各落一条助手消息。
     */
    private boolean answerTimedOutAsk(Map<String, SessionEvent> pending, ChatCommand cmd, AgentxTurnRecording recording) {
        String sessionKey = sessions.key(cmd);
        for (Map.Entry<String, SessionEvent> e : pending.entrySet()) {
            Map<String, Object> data = e.getValue().data() == null ? Map.of() : e.getValue().data();
            if ("confirmation".equals(str(data.get("kind")))) continue;
            String notice = TurnInteractionCoordinator.ASK_PENDING_NOTICE;
            try {
                if (sessions.respondRaw(sessionKey, e.getKey(), "ask", true, null, notice) == null) {
                    continue;   // 这条待办接不动，试下一条
                }
            } catch (Exception ex) {
                log.warn("[agentx] 提问超时回送 pending 失败 {} session={}: {}", e.getKey(),
                        cmd.getSessionId(), ex.toString());
                continue;
            }
            sessions.markAskContinued(e.getKey(), notice);
            // 随后那条 tool/result 要带上「这张卡仍可答」，否则前端按终态把提问面板撤了
            recording.markAskContinued(e.getKey());
            log.info("[agentx] 提问前台等待结束，按 pending 送回模型继续本轮（卡片仍可答）: requestId={} session={}",
                    e.getKey(), cmd.getSessionId());
            return true;
        }
        return false;
    }

    /**
     * 这一轮不再有人跟随：把还挂在人身上的待办当场收口。三件事必须一起做，少一件就有一件用户看得见的事坏掉。
     * <ul>
     *  <li><b>卡片终态</b>：挂在提问卡上的那一次工具调用只有 {@code tool/requested}、没有结果，不补终态
     *      那张卡就永远转圈——落库那份 {@code status} 停在 start，刷新一次还是转圈。</li>
     *  <li><b>框架侧结算</b>（{@link SessionRecovery#cancelPending}）：那一跑在框架台账里留的是
     *      {@code run/end status=suspended}，而 {@code Turn.ended()} 只认 completed/cancelled——不补这一刀，
     *      这条会话之后每一轮都只进收件箱不启动（与 {@code reconcileStalledTurn} 那条 failed 轮同一种病）。</li>
     *  <li><b>待办行落终态</b>：停在 PENDING 的卡片刷新后仍会被恢复端点重建出来，用户答了才发现没人接。</li>
     * </ul>
     * <p><b>终态帧由问渠自己发，不重放框架那条合成结果</b>：框架为取消补写的 {@code tool/result} 说的是
     * 英文内部话（"Caller cancelled this pending interaction…"），而且下面 {@link AgentxTurnRecording#toolCall}
     * 已经把 start 卡关掉了，再让 {@link #translate} 读一遍会因配不上 start 而多推一张重复卡。
     *
     * @param stopped true=用户叫停，false=等待预算用尽（落库状态与卡片措辞都按它分）
     */
    private void abandonPendingInteractions(AgentSession session, ChatCommand cmd, RunChannel channel,
                                            AgentxTurnRecording recording, boolean stopped,
                                            Map<String, SessionEvent> pendingAtClose) {
        // 用收尾那份权威快照，不再自己读一遍（pending() 全量回放在万级事件日志上要十几秒）
        Map<String, SessionEvent> pending = new java.util.LinkedHashMap<>(pendingAtClose);
        if (pending.isEmpty()) return;
        String why = stopped ? "本轮已停止" : "等待超时";
        // 结构性收口排在发帧之前：通道可能已经被停止/断开收掉了，那一帧发不出去是小事，
        // 结算没做才是大事（这条会话之后就再也起不动）。
        Set<String> frameworkTurns = new LinkedHashSet<>();
        for (SessionEvent e : pending.values()) {
            if (e.turnId() != null && !e.turnId().isBlank()) frameworkTurns.add(e.turnId());
        }
        for (String fwTurn : frameworkTurns) {
            try {
                SessionRecovery.cancelPending(session.log(), fwTurn);
            } catch (RuntimeException refused) {
                log.warn("[FAIL-LOUD] 框架待办取消失败（不落 turn/cancelled 这条会话之后可能起不动）"
                        + " session={} turn={}: {}", cmd.getSessionId(), fwTurn, refused.toString());
            }
        }
        sessions.markPendingTerminal(sessions.key(cmd), stopped ? "STOPPED" : "TIMEOUT");
        for (Map.Entry<String, SessionEvent> entry : pending.entrySet()) {
            Map<String, Object> data = entry.getValue().data() == null ? Map.of() : entry.getValue().data();
            Object call = firstNonNull(data.get("call"), data);
            String name = toolNameOf(call);
            if (name.isBlank()) name = recording.lastToolName;
            if (name.isBlank()) continue;
            // 已经出过终态的那一把不补（例如 plan_exit：确认是另一件事，工具结果早到了）——
            // 补了就是 AgentxTurnRecording 找不到 start 而整张新卡，一次调用两张卡
            if (!recording.hasOpenTool(name)) continue;
            String id = call instanceof Map<?, ?> cm && cm.get("id") != null
                    ? String.valueOf(cm.get("id")) : entry.getKey();
            String callKey = AgentxTurnRecording.toolCallKey(id, name);
            long beganAt = recording.takeToolStart(callKey);
            long covered = recording.takeToolWait(callKey);
            long charged = recording.takeToolCharge(callKey);
            String args = recording.toolInputByKey.remove(callKey);
            if (args == null) args = recording.lastToolInput;
            long elapsed = beganAt <= 0L ? 0L
                    : Math.max(0L, System.currentTimeMillis() - beganAt - covered);
            // 提问卡：没答就是没答，按「未作答」收尾（那句前缀是前端 askAnswerLabel 的判据，别改词）；
            // 其它工具：那一步确实没执行，按失败收——给一个 ✓ 就是假装它跑完了
            boolean ask = AskUserTool.NAME.equals(name);
            String status = TurnInteractionCoordinator.abandonStatus(ask);
            String result = TurnInteractionCoordinator.abandonResult(ask, why);
            // 落库那份先写、无条件：刷新读的就是它（那句解释也要一起落，刷新后卡片才说得出为什么没跑）
            recording.toolCall(name, status, args, elapsed, beganAt, charged, result);
            try {
                AgentxEmitUtil.emit(channel, RunEventType.TOOL_STATUS,
                        toolCardBuilder.toolCard(recording.uid, name, status, elapsed, beganAt, charged, args, result, null,
                                recording.hooks.step().index()));
            } catch (RuntimeException gone) {
                log.info("[agentx] 待办终态帧没发出去（通道已收），落库那份仍在: session={} tool={}",
                        cmd.getSessionId(), name);
            }
        }
        log.info("[agentx] 本轮不再跟随，{} 条待办已收口: session={} 原因={}",
                pending.size(), cmd.getSessionId(), why);
    }


    /**
     * 智能体显示名随消息落库：agentId 有而名字没有，刷新后气泡上那一行「由 XX 作答」就空着。
     * <p>取不到就回 null（不编一个名字）：这里可能在流水线线程也可能在续跑线程上跑，
     * 后者没有请求身份，归属校验会把智能体判成不可读——那种情况下宁缺不假。
     */
    private String agentNameOf(ChatCommand cmd) {
        if (cmd.getAgentId() == null || cmd.getAgentId().isBlank()) return null;
        try {
            com.wenqu.ai.model.Agent a = tools.agentOf(cmd);
            return a == null ? null : a.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 本轮的角色提示词段（{@code sysPrompt} 的第一段）：智能体人设优先，为空回落平台默认。
     * <p>选段口径在 {@link RolePrompt#of}——抽成纯函数是为了让这条口径可被单测钉住。
     * <p><b>为什么吞异常</b>：人设取不到不该把整轮打挂——回落平台默认即可，
     * 与 {@code agentNameOf} 同样的降级姿态。
     */
    /**
     * 本轮智能体绑定：行、实例重建判据、技能白名单，一次解析三处共用。
     *
     * <p><b>为什么需要判据</b>：常驻实例的人设（{@code sysPrompt}）、工具挂载
     * （{@code tools.frameworkTools}）、技能白名单都是 <b>build 期</b>固化的，而界面允许在会话内换
     * 智能体（「下一问起由它作答」）。只比模型引用时，两个用同一模型的智能体之间换人，旧实例会继续用
     * 上一个人的提示词与工具表——且日志上一切正常。
     *
     * @param key 智能体 id + 配置更新时间：换人、改配置都会让它变
     */
    private record AgentBinding(Agent row, String key, io.agentscope.core.skill.SkillFilter skillFilter,
                                int contextWindow, int maxOutput) {
    }



    /**
     * 「接下来可以」推荐追问是否按本轮生成（个人设置 → 问答偏好 → 对话显示，键 ui.showRelatedSuggestions）。
     * <p>关掉 = 不再向模型要这段（省输出 token）、也不落库，而不是只在界面上隐藏。
     * 未设置 / 无 uid（游客、续跑复原）一律按开。
     */
    private boolean relatedSuggestionsEnabled(String uid) {
        String v = userConfigService.personalValue(uid, "ui.showRelatedSuggestions");
        return v == null || v.isBlank() || "true".equalsIgnoreCase(v.trim());
    }

    private AgentBinding bindingOf(ChatCommand cmd, ExecutionPolicy policy, String modelRef) {
        Agent row = null;
        try {
            row = tools.agentOf(cmd);
        } catch (RuntimeException e) {
            // 解析失败不打断本轮：按「无绑定」走平台默认档（与 rolePrompt 的兜底一致）
            log.warn("[agentx] 本轮智能体解析失败，回落平台默认档: session={} err={}",
                    cmd.getSessionId(), e.getMessage());
        }
        String key = row == null
                ? ""
                : row.getId() + "@" + (row.getUpdateTime() == null ? "" : row.getUpdateTime());
        // 执行档位也是 build 期烤进实例的（permissionContext、闸、适配器兜底），档位变了必须重建：
        // 管理员在设置页改审批档，不能等整条会话换个模型才生效。
        key = key + "|" + (policy == null ? "" : policy.preset() + "/" + policy.sandboxMode()
                + "/" + policy.approvalMode() + "/" + policy.deny().stream().sorted().toList());
        // 目标模式开关同样烤在实例里（taskContext 投影参数是 build 期装配）：智能体改了
        // goal_enabled 不重建，就会出现「库里开着、实例没投影」或反过来——界面与模型看到的不是一套
        key = key + "|goal=" + (row != null && row.getGoalEnabled() != null ? row.getGoalEnabled() : "-");
        // 本轮配置覆盖（历史版本复测/临时换资料）进实例指纹：改 revision 或 knowledgePatch 必须重建，
        // 否则工具表、技能滤镜、人设仍来自上一次 build 的实例；无 override 时不追加，旧行为逐字节不变。
        if (cmd.getAgentConfigOverride() != null && !cmd.getAgentConfigOverride().isEmpty()) {
            key = key + "|ov=" + cmd.getAgentConfigOverride().fingerprint();
        }
        // 工作流工具只存 id（Agent.java 的 workflowTools 注释承诺「重新发布即时生效」），但发布/回滚/
        // 改名不会改 agent.update_time，工具表又是 build 期固化的。把引用工作流的发布态并进指纹，
        // 让同一会话的下一次开轮重建实例并重新 mount 工具，兑现那条承诺。
        key = key + tools.workflowBindingFingerprint(row);
        // 个人「自动提炼记忆」开关也是 build 期装配（memoryConfig 还是 disableMemoryHooks）：不并进
        // 指纹的话，关掉后要等下一次重建才停，与个人设置里「关掉后下一轮起才停」的说法不符。
        key = key + "|mem=" + orgService.memoryFlushEnabled(cmd.getUserId());
        // 「接下来可以」开关也烤进系统提示词（关掉就不再挂 RelatedPrompt）：并进指纹，拨动后下一轮即重建实例生效。
        key = key + "|rel=" + relatedSuggestionsEnabled(cmd.getUserId());
        // 上下文窗口并进指纹：它决定框架的压缩阈值与输入上限，烤死在实例里。
        // 窗口恒等于模型登记上限，并进来是为了管理员改了登记值时这一轮就重建。
        int contextWindow = modelContextWindow(modelRef);
        // 最大输出同理烤在实例里（作为 max_tokens 随请求下发），登记值改了也得重建才生效。
        int maxOutput = modelMaxOutput(modelRef);
        key = key + "|ctx=" + contextWindow + "|out=" + maxOutput;
        // 思考开关仍留在指纹外：它同样烤在实例里，但续跑轮的 ChatCommand 复原不了它，并进来会让
        // 续跑按默认档重建。那一档的变更仍欠「按调用传 GenerateOptions」那一刀。
        return new AgentBinding(row, key, AgentxSkillFilter.of(row), contextWindow, maxOutput);
    }


    private String rolePrompt(Agent bound) {
        return RolePrompt.of(bound, properties.getSystemPrompt());
    }

    /**
     * 团队模式的工具使用引导：只在会话开启团队（或成员轮）时追加。
     * <p>团队工具全部由框架提供（{@code teamsMode} 注册的 {@code team} 工具 + 框架 agent_*），
     * 但模型默认不会主动用任务板/邮箱；这里用一小段说清何时用、action 叫什么。
     */
    private String teamGuidance(ChatCommand cmd) {
        if (!teamActive(cmd)) return "";
        return "\n\n## 智能体团队（已开启）\n"
                + "复杂或多步、需要协作跟踪的任务，用团队任务板（`team` 工具）："
                + "`team(action=createTask, subject=…, owner=成员名)` 建任务并可直接派给某位成员，"
                + "任务有先后时用 `team_task_depend(from, to)` 登记依赖（前置完成，后继才可认领）；"
                + "`team(action=listClaimableTasks)` 看待认领 → `team(action=claimTask)` 认领 → "
                + "`team(action=completeTask/failTask)` 完成或失败。\n"
                + "**要派出「持久成员」（会出现在右栏团队面板、可被再次唤醒），必须调用 `spawnMember` 工具"
                + "（`spawnMember(member_name=<团队内短名>, agent_ref=<真实智能体id>)`；等价于 `team` 的 spawnMember 动作）**："
                + "`member_name` 是 1–32 位小写字母/数字/连字符（如 reviewer），`agent_ref` 必须逐字取自 "
                + "「## Subagents / Available agent ids」，**不要自己编名字**；拿不准就用当前根助手自己的 id。"
                + "派出后再用 `sendMessage(to_member=<member_name>, message=…)`（或 `team(action=sendMessage, …)`）给它派活。"
                + "`agent_spawn` 只是**临时同步子轮**（跑完即散、不进团队面板、无任务板写权限），"
                + "不要用 `agent_spawn` 建团队成员，也不要用它替代 `spawnMember`。"
                + "跨成员写信同样走 `team(action=sendMessage, to_member=…)`。"
                + "简单一问一答不要建任务，也不要为凑数建任务。";
    }

    /**
     * 本轮真实挂上的工具注册名，供 {@link ToolDiscipline} 判「该不该说那句跨族分工」。
     * <p>判据与工具面装配同一份（{@link WenquAgentxTools#turnGroupedNames}），不另写一遍——
     * 两处判据分家就会漂移成「提示词教了一句本轮没有的能力」。
     * <p>取不到就按「什么都没挂」处理：少说一句分工，比让整轮因为提示词装配失败更划算。
     */
    private java.util.Set<String> mountedToolNames(ChatCommand cmd, ExecutionPolicy policy,
                                                   AgentxTurnSlot slot) {
        try {
            java.util.Set<String> out = new java.util.LinkedHashSet<>(tools.turnGroupedNames(cmd, policy));
            AgentxTurnSlot.Surface surface = slot == null ? null : slot.surface();
            if (surface != null) {
                if (surface.runCodeMounted()) out.add("run_code");
                // 框架原生工具（deliver_artifact 等）不在这个注册中心里，按工具面那份清单并进来；
                // 只读档与 API Key 的按名禁用已经落在 policy.deny 里，这里直接复用，不另写一份判据。
                java.util.Set<String> framework = java.util.Set.copyOf(
                        WenquAgentxTools.frameworkSurfaceToolNames());
                if (surface.rawCallbacks() != null
                        && policy.sandboxMode() != com.wenqu.ai.agent.permission.SandboxMode.READ_ONLY) {
                    for (var cb : surface.rawCallbacks()) {
                        if (cb == null || cb.getToolDefinition() == null) continue;
                        String name = cb.getToolDefinition().name();
                        if (framework.contains(name) && !policy.denies(name)) out.add(name);
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("[agentx] 取本轮挂载工具名失败，本轮不追加工具分工段: {}", e.toString());
            return java.util.Set.of();
        }
    }

    /**
     * 续跑时把会话绑定的智能体带回来。
     * <p>续跑入口（{@link #openForContinuation}）是用空问题重建一条 {@link ChatCommand} 的，若不显式带
     * {@code agentId}，重建出来的实例会退回平台默认人设——而用户明明是在某个智能体下问的那一轮。
     * 会话表里存着这份归属（{@code c_ai_session.agent_id}），读一次即可，读不到按默认助手继续。
     */
    private String agentIdOfSession(String sessionId) {
        try {
            com.wenqu.ai.model.Session s = sessionService.sessionById(sessionId);
            return s == null ? null : s.getAgentId();
        } catch (RuntimeException e) {
            log.warn("[agentx] 续跑解析会话归属智能体失败，按默认助手继续 session={}: {}",
                    sessionId, e.toString());
            return null;
        }
    }

    /**
     * 目标续跑轮的作答方：目标行 → 会话列 → 平台默认智能体。
     * <p>三段缺一不可：目标行是「建目标时是谁在答」，会话列是这轮问答的兜底，
     * 平台默认是最后一格。少了它，续跑轮就没有智能体绑定，权限档位与审批方式都会回落平台默认，
     * 用户看到的正是「智能体明明设了自动执行，目标模式仍然逐条弹确认卡」。
     */
    private String goalAgentId(com.wenqu.ai.model.Goal goal) {
        String id = goal == null ? null : goal.getAgentId();
        if (id == null || id.isBlank()) id = agentIdOfSession(goal == null ? null : goal.getSessionId());
        if (id == null || id.isBlank()) id = tools.defaultAgentId();
        return id;
    }

    /**
     * 本轮的**工作背景段**：工作区目录的文件简报（目录里有什么，模型才知道该去读哪份）。
     * <p>取不到就缺席：目录不共用/读失败不注入读不到的清单——不能因为工作背景读失败就把整轮打挂，
     * 也不能凭空塞「这是兜底容器」的噪声（每轮都是纯 token 成本）。
     */
    private String projectContext(ChatCommand cmd) {
        try {
            String files = projectFileService.promptBriefOfSession(cmd.getSessionId());
            return files == null ? "" : files;
        } catch (Exception e) {
            log.warn("[agentx] 工作背景读取失败（本轮不注入）session={}: {}", cmd.getSessionId(), e.getMessage());
            return "";
        }
    }

    /**
     * 工作区级记忆（独立于用户级记忆）：读工作区根目录 .wenqu/MEMORY.md 注入为一段上下文。
     * 无工作区 / 未绑目录 / 文件不存在 / 执行器离线 / 读失败 → 返回空串，绝不拖累一轮对话。
     * 写由模型经沙盒文件工具完成（edit_file / write 到 .wenqu/MEMORY.md），前端面板也可直接编辑。
     */
    /**
     * 本轮的智能体记忆域（对外能力第二批）：智能体 memory_mode=专属(1)/叠加(2) 时返回智能体 id——
     * 记忆路由换到 {@code {uid}/agents/{agentId}} 专属根（注入/工具读写/flush/合并都随根走，框架
     * WorkspaceContextMiddleware 读的是路由后的那份）；其余（null/0）返回 null = 用户级记忆。
     */
    private String memoryAgentIdOf(ChatCommand cmd) {
        Agent agent = tools.agentOf(cmd);
        if (agent == null || agent.getId() == null) return null;
        Integer mode = agent.getMemoryMode();
        if (mode == null || (mode != 1 && mode != 2)) return null;
        return agent.getId();
    }

    /**
     * 叠加档（memory_mode=2）的用户级全局记忆只读注入：框架记忆根已换到智能体专属根（活跃写），
     * 用户全局 MEMORY.md 以「资料」身份再注入一份——消毒套信封（与 workspaceMemoryContext 同一
     * 处置），并明说只读：全局记忆不因本智能体的对话改写，写类工具也落不到它（路由已换根）。
     * 专属档（1）不注入：替换语义，智能体只看自己的记忆。
     */
    private String userMemoryReadonlyContext(ChatCommand cmd) {
        try {
            Agent agent = tools.agentOf(cmd);
            if (agent == null || agent.getMemoryMode() == null || agent.getMemoryMode() != 2) return "";
            String uid = cmd.getUserId();
            if (uid == null || uid.isBlank()) return "";
            java.nio.file.Path md = com.wenqu.ai.memory.MemoryPaths.resolveContent(
                    configService, uid, null, com.wenqu.ai.memory.MemoryPaths.MEMORY_MD);
            if (md == null || !java.nio.file.Files.exists(md)) return "";
            String content = TextIo.readString(md);
            if (content == null || content.isBlank()) return "";
            String body = com.wenqu.ai.util.UntrustedContent.toolResult("用户全局记忆 MEMORY.md", content);
            return "\n\n【用户全局记忆（只读）】下面是这位用户跨智能体沉淀的长期事实，本次对话应当参考。"
                    + "它是只读背景：本助手的专属记忆在别处维护，不要试图用文件工具修改这份全局记忆。\n" + body;
        } catch (Exception e) {
            log.warn("[agentx] 用户全局记忆只读注入失败（跳过）session={}: {}", cmd.getSessionId(), e.getMessage());
            return "";
        }
    }

    private String workspaceMemoryContext(ChatCommand cmd) {
        try {
            String projectId = projectService.projectIdOfSession(cmd.getSessionId());
            if (projectId == null || projectId.isBlank()) return "";
            String content = projectFileService.readWorkspaceMemory(cmd.getUserId(), projectId);
            if (content == null || content.isBlank()) return "";
            // 记忆是用户可直接编辑、也可能被上一轮读到的外部文档污染的文件：按「资料」处理，
            // 消毒后套信封。持久注入最怕的是它长得像系统指令——信封先于攻击到达模型。
            String body = com.wenqu.ai.util.UntrustedContent.toolResult(
                    "工作区记忆 .wenqu/MEMORY.md", content);
            return "\n\n【工作区记忆】下面是本工作区长期沉淀的项目事实（存于工作区根目录 .wenqu/MEMORY.md），"
                    + "本次对话应当参考它们。\n" + body
                    + "\n\n你可以用文件编辑工具（edit_file / write）更新 .wenqu/MEMORY.md，"
                    + "把本次对话中获得、对今后在本工作区工作有价值的稳定事实（约定、决策、结构、坑、偏好）沉淀进去；"
                    + "不要写入临时性或一次性的内容。";
        } catch (Exception e) {
            log.warn("[agentx] 读取工作区记忆失败（跳过注入）session={}: {}", cmd.getSessionId(), e.getMessage());
            return "";
        }
    }

    /**
     * 本机执行器模式的真实执行环境说明。
     * <p>框架的 WorkspaceContextMiddleware 会把工作区写成服务器状态目录（{@code /app/data/agentscope}），
     * 并把 {@code ExecutorFilesystem} 当作沙盒容器渲染成「Sandbox root: /workspace」——这两个路径都在
     * 服务器上，而 execute / 文件工具实际跑在用户电脑上。不纠正的话模型第一发就去 {@code ls} 这两个
     * 不存在的路径，失败后又当成可重试，反复空转。
     * <p>取本轮实际解析结果（{@link com.wenqu.ai.service.SandboxService#sessionWorkspaceOf}）的后端与根目录，
     * 不读库里的钉住值：执行器离线那一轮实际跑在云端，再写本机路径就是骗模型。不是本机后端就整段缺席。
     */
    private String localExecutorEnvironment(ChatCommand cmd) {
        return localExecutorEnvironment(cmd, null);
    }

    /** ew 非空时用建实例那轮的解析结果（build 期消费者）；null=运行期现查。 */
    private String localExecutorEnvironment(ChatCommand cmd, com.wenqu.ai.service.EffectiveWorkspace ew) {
        if (ew != null) {
            if (ew.kind() != com.wenqu.ai.service.EffectiveWorkspace.Kind.IMPLICIT_LOCAL
                    && ew.kind() != com.wenqu.ai.service.EffectiveWorkspace.Kind.EXPLICIT_LOCAL
                    && ew.kind() != com.wenqu.ai.service.EffectiveWorkspace.Kind.EXPLICIT_SSH) return "";
            String root = ew.primaryRoot();
            if (root == null || root.isBlank()) return "";
            return AgentxTextUtil.localEnvText(root, ew.fallbackNotice());
        }
        com.wenqu.ai.service.SessionWorkspaceService.Workspace ws = sandboxService == null ? null
                : sandboxService.sessionWorkspaceOf(cmd.getSessionId(), cmd.getUserId());
        if (ws == null || !ws.local()) return "";
        String root = ws.rootPath();
        if (root == null || root.isBlank()) return "";
        return AgentxTextUtil.localEnvText(root, ws.fallbackNotice());
    }


    /**
     * 只记账不播报的通道：读历史事件时用它——事件照样进 {@link AgentxTurnRecording}（工具卡、思考全文都要落库），
     * 但不再发一遍给观众。
     */
    private static final RunChannel SILENT_CHANNEL = new RunChannel() {
        @Override
        public org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter() {
            return null;
        }

        @Override
        public String sessionId() {
            return "";
        }

        @Override
        public boolean emit(RunEvent event) {
            return true;
        }

        @Override
        public String lastWarn() {
            return null;
        }

        @Override
        public void offerSnapshot(java.util.function.Supplier<Map<String, Object>> snapshotSource) {
        }

        @Override
        public void complete() {
        }
    };


    /**
     * 按游标读增量事件并翻译，返回新游标——同一条事件不会被读第二次。
     * <p>{@code emitFrom} 之前的只记不发：续跑要把批准之前那几次工具记进这一轮的账，但那些帧已经
     * 播过一次，再发一遍等于把上一段正文重新打给观众。
     * <p><b>必须翻到读完为止</b>：{@code readAfter(after, limit)} 是「最多 limit 条」的截断语义，
     * 不是「都给你」。一次只取一页等于给两次读之间的积压设了一道隐形上限，而收尾那一次是全轮最后
     * 一次读——只走一页，此后没人再推进游标，落后的那一截永久丢掉。丢掉的是最后的正文增量：剥离器
     * 吃不到它，落库就改从框架 transcript 取（那份没经过剥离），用户读到带 {@code <related>} 原文的
     * 回答，而「接下来可以」那排芯片同时没了。
     * <p>标签到不了用户眼前，由 {@link #stripFallbackBody} 在那条回退路径上兜住。
     * <p>上界取进入时的 {@code head} 快照而不是「读到空为止」：事件在跑的过程中持续追加，
     * 没有快照这个循环可以永不返回。快照之后新到的事件交给下一拍。
     */
    private long drain(AgentSession session, long cursor, long emitFrom, String turnId, RunChannel channel,
                       AgentxTurnRecording recording, AgentxTurnMirror mirror) {
        SessionLog eventLog = session.log();
        long through = eventLog.head().seq();
        while (cursor < through) {
            List<SessionEvent> batch = eventLog.readAfter(cursor, DRAIN_PAGE);
            if (batch.isEmpty()) break;   // seq 有空洞（压缩/迁移）：游标推不动，不在这儿死循环
            for (SessionEvent event : batch) {
                cursor = Math.max(cursor, event.seq());
                eventTranslator.translate(event, event.seq() > emitFrom ? channel : SILENT_CHANNEL, turnId, recording);
                if (mirror != null) mirror.observe(event);
            }
        }
        return cursor;
    }

    /**
     * 跟随循环的<b>事件镜像</b>：会话现在处于什么状态，从已经读到的事件上认，不再每跳重读整条日志。
     *
     * <p><b>为什么必须改</b>：框架的 {@code JournalSessionLog#scan} 是从 head 沿父链<b>往回走</b>到
     * {@code after}，每跳一个 commit 文件都要 read + JSON parse——取尾部增量也是这个代价。更要命的是
     * {@code SessionInteractions#pending}（即 {@code session.pending()}）按 256 一批从 0 走到 head，
     * 等于把这条回走跑几十遍；而 {@code transcript()} 与 {@code tasks()}（后者对<b>每条历史命令</b>
     * 各做一次全量 scan）同样按全量算：每跳要做三四次这种读，正文按秒成坨落地，
     * 界面上就成了「不是流式，一下就出来」。
     *
     * <p><b>判据与框架同源，不是近似值</b>：待办＝{@code interaction/requested} 收、{@code interaction/resolved}
     * 撤（框架 {@code pending()} 就是这两类事件按 requestId 增删）；任务变化＝{@code inbox/*} 与
     * {@code run/end}（框架 {@code receipt()} 认的就是它们）；上下文条数＝{@code transcript()} 实际读的
     * 那几类事件。
     *
     * <p><b>仍然保留的两处权威读</b>：起轮时 {@link #resyncPending} 种一次（本轮游标之前挂着的待办
     * 不在 drain 的范围里），收尾那几处照旧现读——镜像只可能晚一跳看见，不会把已提交的事件读丢。
     */


    /** 本轮点名的技能：记用量 + 发 skill_hit 帧（正文由 TurnPromptAssembler 注入，这里只报结果）。 */
    private void emitPickedSkillHits(ChatCommand cmd, RunChannel channel) {
        List<String> picked = cmd.getSkills();
        if (picked == null || picked.isEmpty()) return;
        for (String raw : picked) {
            if (raw == null || raw.isBlank()) continue;
            // 智能体「指定」模式存的是 {uid}/{name} 引用串，命中帧与统计都用裸名
            String name = raw.contains("/") ? raw.substring(raw.lastIndexOf('/') + 1) : raw.trim();
            if (name.isEmpty()) continue;
            skillStatsStore.recordRead(cmd.getUserId(), name, cmd.getSessionId());
            try {
                channel.emit(RunEventType.SKILL_HIT, com.alibaba.fastjson2.JSON.toJSONString(
                        java.util.Map.of("name", name, "source", "picked")));
            } catch (RuntimeException gone) {
                log.debug("[SKILL] skill_hit 帧没发出去（通道已收）: session={} skill={}",
                        cmd.getSessionId(), name);
            }
        }
    }









    /** 框架待办（一等公民）→ 问渠确认卡；审批号↔会话的归属登记好，答复端点靠它找回。 */
    private void announcePending(Map<String, SessionEvent> pending, ChatCommand cmd, RunChannel channel,
                                 Set<String> announced, String turnId, AgentxTurnRecording recording) {
        if (pending.isEmpty()) return;
        String sessionKey = sessions.key(cmd);
        sessions.notePending(sessionKey, pending);
        for (Map.Entry<String, SessionEvent> entry : pending.entrySet()) {
            if (!announced.add(entry.getKey())) continue;
            Map<String, Object> data = entry.getValue().data() == null ? Map.of() : entry.getValue().data();
            String tool = toolNameOf(firstNonNull(data.get("call"), firstNonNull(data.get("toolUse"),
                    firstNonNull(data.get("message"), data))));
            String args = str(firstNonNull(toolInputOf(data.get("call")),
                    firstNonNull(toolInputOf(data.get("toolUse")), toolInputOf(data))));
            // 两种待办按 kind 分流（工具挂起=external_execution，权限确认=confirmation）。
            // 分错的后果是「给用户的卡答不上它真正在等的事」，所以未知 kind 也走提问卡——
            // 题面至少能呈现，绝不静默吞掉
            String kind = str(data.get("kind"));
            if ("confirmation".equals(kind) && tool.startsWith("plan_")) {
                // 计划模式的退出确认走问渠的计划卡（deep_plan_approval），不是普通工具审批卡：
                // 两者在界面上是两张不同的卡，用户在那里能看到整份计划
                Map<String, Object> planCard = new java.util.LinkedHashMap<>();
                String rawPlan = planTextOf(toolInputOf(data.get("call")),
                        recording == null ? null : recording.planMarkdown);
                if (NO_PLAN_BODY.equals(rawPlan)) {
                    // 「模型没调 plan_write」是排查用的内部原因，只进日志——它印在用户要点批准的卡片上没意义
                    log.warn("[PLAN] 本轮 plan_exit 没带计划正文（未取得 plan_write 的 content）: session={} tool={}",
                            cmd.getSessionId(), tool);
                }
                // 用户口径的保底：正文里出现沙盒/运行目录路径就收成文件名（提示词已经教过，这里防它照抄工具说明）
                String planText = PlanCopy.stripInternalPaths(rawPlan, planInternalRoots());
                planCard.put("planApprovalId", entry.getKey());
                planCard.put("plan", planText);
                planCard.put("timeoutMs", configService.getInt("chat.planTimeoutMs", 3600000));
                // 血缘随 deep_plan_approval 一起下发：这张新计划是基于哪一版旧计划、带过来多少执行进度。
                // 断线接流/换设备时没有原始 userMsg，只能靠帧自身带；待批卡则从 request_args 里取同一份。
                if (cmd.getPlanBaseId() != null && !cmd.getPlanBaseId().isBlank()) {
                    planCard.put("planBaseId", cmd.getPlanBaseId());
                    planCard.put("planBaseVersion", cmd.getPlanBaseVersion());
                    planCard.put("planBaseDone", cmd.getPlanBaseDone());
                    planCard.put("planBaseTotal", cmd.getPlanBaseTotal());
                    planCard.put("planBaseFailed", cmd.getPlanBaseFailed());
                }
                if (recording != null) {
                    // 产出这一版的意见就是用户上一次「退回重出」提交的正文；首次计划没有意见
                    String produceFeedback = sessions.peekPlanFeedback(sessionKey);
                    recording.planVersion(planText, produceFeedback, entry.getKey());
                    // 新一版已挂出待确认：退回重出状态到此结束（下一次 revise 由裁决入口重新置位）
                    sessions.markPlanPending(sessionKey);
                }
                channel.emit(RunEventType.DEEP_PLAN_APPROVAL, com.alibaba.fastjson2.JSON.toJSONString(planCard));
                // 落同一张 c_ai_tool_approval（tool_name=planApproval）：不落的话刷新后这张卡取不回来。
                // 血缘也一起存进 request_args：待批准的新计划刷新后要能显示「调整自 vN」，
                // 而此刻还没有助手消息行可挂 plan 记录（那张卡要等本轮收尾才落库）。
                Map<String, Object> pendingArgs = new java.util.LinkedHashMap<>();
                pendingArgs.put("plan", planText);
                if (cmd.getPlanBaseId() != null && !cmd.getPlanBaseId().isBlank()) {
                    pendingArgs.put("planBaseId", cmd.getPlanBaseId());
                    pendingArgs.put("planBaseVersion", cmd.getPlanBaseVersion());
                    pendingArgs.put("planBaseDone", cmd.getPlanBaseDone());
                    pendingArgs.put("planBaseTotal", cmd.getPlanBaseTotal());
                    pendingArgs.put("planBaseFailed", cmd.getPlanBaseFailed());
                }
                sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), "planApproval",
                        com.alibaba.fastjson2.JSON.toJSONString(pendingArgs));
                // 计划卡等人拍板是有时限的：不确认这一轮会超时并停止。屏幕外的人看不见那张卡，
                // 只能靠这一句把人叫回来
                turnNotify.onPlanPending(cmd, entry.getKey());
                continue;
            }
            // 提问工具被框架记成「确认」是不该发生的（AskUserTool 已声明权限放行）：真出现时留一条日志，
            // 否则用户拿到的是一张点了也恢复不了的审批卡，线上没法从现象倒推
            if ("confirmation".equals(kind) && AskUserTool.NAME.equals(tool)) {
                log.warn("[AGENTX] 提问工具被记成确认待办，按审批卡处理: session={} request={}",
                        cmd.getSessionId(), entry.getKey());
            }
            if (!"confirmation".equals(kind)) {
                Object call = firstNonNull(data.get("call"), data);
                String askPayload = askCardBuilder.askCard(entry.getKey(), toolInputOf(call), tool);
                channel.emit(RunEventType.ASK_USER, askPayload);
                // 载荷与手写侧 askArgsJson 同形（都按 {"questions":[…]} 解析），恢复端点可直接复用
                sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), "askUser", askPayload);
                // 通知正文带<b>第一题的题面</b>：手机上「智能体在等你回答」不说明在等什么，
                // 人得先点进来才知道——把那句问题直接抄进通知，看一眼就能决定要不要回来
                turnNotify.onAskPending(cmd, entry.getKey(), firstAskText(toolInputOf(call)));
                continue;
            }
            // 审批卡：展示名与工具卡同源（注册中心 → 框架工具表），不再把裸工具名当 label 下发。
            // 「重新生成后会看到同一张卡」靠的就是这份一致——前端那句「请求执行工具「X」」读的是 label
            Map<String, Object> approvalCard = new java.util.LinkedHashMap<>();
            approvalCard.put("approvalId", entry.getKey());
            approvalCard.put("tool", tool);
            approvalCard.put("args", args);
            approvalCard.put("timeoutMs", configService.getInt("chat.approvalTimeoutMs", 600000));
            // 「为什么问人」与执行期守卫同一份判据：卡上不写理由时，用户看到的就是一张无缘无故的拦截卡
            String reason = approvalReason(cmd, tool, firstNonNull(toolInputOf(data.get("call")),
                    firstNonNull(toolInputOf(data.get("toolUse")), toolInputOf(data))));
            if (!reason.isEmpty()) approvalCard.put("reason", reason);
            displayNames.putDisplayFields(approvalCard, cmd.getUserId(), tool);
            // 工具表没收过这个名字（MCP 动态工具常见）：补一句说明，让用户至少知道这张卡在问什么，
            // 而不是光秃秃一个英文名——但绝不拿工具名冒充展示名（前端会把它当「后端认真取过」）
            if (!approvalCard.containsKey("hint")) {
                approvalCard.put("hint", "执行前请确认这一步");
            }
            channel.emit(RunEventType.APPROVAL_REQUIRED,
                    com.alibaba.fastjson2.JSON.toJSONString(approvalCard));
            // 先落库、只有真的插入了才发帧：同一 approvalId 两个跟随者各宣布一次时，
            // 数据库侧本来就是幂等的，而 emit 侧原来没有闸——线上那张卡就是这么发了两遍。
            boolean inserted = sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), tool, args, reason);
            if (!inserted) {
                log.info("[AGENTX] 审批卡已存在，跳过重复下发: session={} approval={}",
                        cmd.getSessionId(), entry.getKey());
                continue;
            }
            // 审批卡同样等人：正文带展示名（与那张卡同源的一个词）。名字没收过就只报会话，
            // 绝不拿裸工具名冒充展示名
            turnNotify.onApprovalPending(cmd, entry.getKey(), str(approvalCard.get("label")));
        }
    }

    /**
     * 审批卡上的「为什么问」：与执行期守卫同一份判据——本机文件/命令工具由污点护栏接管
     * （读过外部内容后遇到删除/覆盖/外发/越界写），其余工具按本轮审批档要求确认。
     * 空串 = 没有可说清的理由，卡片沿用默认提示。
     * <p>不在这里再判一次「像不像写操作」：工具性质在登记处，强制确认在 {@link AgentxNativeToolGuard}。
     */
    @SuppressWarnings("unchecked")
    private String approvalReason(ChatCommand cmd, String tool, Object inputObj) {
        AgentxTurnSlot.Turn t = sessions.turnSlot(cmd).current();
        if (t == null) return "";
        AgentxNativeToolGuard.Kind kind = nativeGuardKind(tool);
        if (kind != null) {
            Map<String, Object> input = inputObj instanceof Map ? (Map<String, Object>) inputObj : null;
            String note = AgentxNativeToolGuard.forcedAskNote(kind, input, t.policy(), t.taint(),
                    workspaceRootsOf(cmd));
            if (note != null) return note;
        }
        // MCP 动态工具没有静态描述符，卡片上用通用话术会让人以为这条 SELECT 也在改数据。
        // 认本轮挂载面里的 mcpToolNames（与装配同一份），把「在哪里执行」说清楚。
        var surface = sessions.turnSlot(cmd).surface();
        if (surface != null && surface.mcpToolNames().contains(tool)) {
            return "MCP 外部工具：将在你登记的 MCP 服务里执行这一步。";
        }
        return "当前要求执行前确认：这一步会改动外部状态。";
    }


    /**
     * 计划批准卡的正文：<b>必须是一段 Markdown 文本</b>，不能是工具入参对象。
     * <p>框架 {@code plan_exit} 的入参只有一个可选 {@code summary}（{@code PlanExitTool} 的 inputSchema
     * 就这么声明的），整份计划正文在更早的 {@code plan_write(content=…)} 里。塞对象过去，前端
     * {@code renderMd()} 拿到非字符串就渲染成 {@code [object Object]}——用户批准的是一份看不见的计划。
     * <p>取值优先级：{@code plan_write} 攒下的正文 → {@code plan_exit} 入参里的 summary →
     * 入参本身是纯文本时的原文。三档都空则给一句说明，不让卡片空白。
     *
     * @param input        {@code plan_exit} 的工具入参（框架给的形状：{@code {summary?}}）
     * @param planMarkdown 本轮 {@code plan_write} 攒下的正文，可空（没调过 plan_write）
     */
    static String planTextOf(Object input, String planMarkdown) {
        return AgentxTextUtil.planTextOf(input, planMarkdown);
    }

    /** 计划正文取不到时给用户的那句话（同时也是 {@code announcePending} 里「该记一条日志」的判据） */
    static final String NO_PLAN_BODY = AgentxTextUtil.NO_PLAN_BODY;

    /**
     * 计划正文里算「内部路径」的根，补在 {@link PlanCopy} 内置名单之外：
     * 沙盒虚拟工作区（{@code sandbox.virtualPathPrefix}）与框架状态目录
     * （{@code chat.agentscopeStateDir}，框架的 {@code plans/PLAN.md} 就落在这里面）。
     * 相对与绝对两种写法都要给：状态目录配的是相对路径时，模型拿到的 banner 是解析成绝对路径后的样子。
     */
    private List<String> planInternalRoots() {
        List<String> roots = new ArrayList<>();
        String prefix = configService.get("sandbox.virtualPathPrefix");
        if (prefix != null && !prefix.isBlank()) roots.add(prefix.trim());
        String stateDir = configService.get("chat.agentscopeStateDir");
        if (stateDir != null && !stateDir.isBlank()) {
            roots.add(stateDir.trim());
            try {
                roots.add(Path.of(stateDir.trim()).toAbsolutePath().normalize().toString());
            } catch (Exception ignored) {
                // 配置成了非法路径：内置名单照样兜底，不为这一条把整轮打挂
            }
        }
        return roots;
    }

    /**
     * 本轮下发给模型网关的参数——与手写主链路的生成段同一条口径（{@code ChatService} 里那段
     * 「思考强度按厂商方言映射、思考开着就不发 temperature」）。
     * <p>为什么框架侧要自己接：框架把思考当成模型自己会给的东西（{@code model/chunk} 里
     * {@code type=thinking} 的块），可 qwen／GLM 这一族<b>不显式打开就根本没有思考块</b>。不接的后果
     * 是用户在界面上勾了「深度思考」而框架引擎什么都没做——那个开关成了假的。
     * <p>温度那条同理：主流网关在思考开启时忽略 temperature（不报错也不生效），与其设了静默失效，
     * 不如不下发，免得被读成「温度已按配置生效」。
     */
    // ===== 模型与记忆配置：实现已抽出到 AgentxModelConfig，这里只留薄委托（调用点不动） =====







    private HarnessAgent buildAgent(ChatCommand cmd, ExecutionPolicy policy,
                                    ModelRegistryService.ModelRoute route,
                                    WenquAgentxTools.TurnHooks hooks, AgentBinding binding,
                                    AgentxTurnSlot slot, boolean sandboxEnabled,
                                    com.wenqu.ai.service.EffectiveWorkspace ew) {
        // 状态目录：相对路径会按工作目录（user.dir）解析，等价于种子默认值 user.dir/user-data/agentscope。
        // 防御式兜底：配置被清空或缺失时退回默认，避免 Path.of("") 抛 InvalidPathException 把整轮打挂。
        String stateDirRaw = configService.get("chat.agentscopeStateDir");
        if (stateDirRaw == null || stateDirRaw.isBlank()) {
            stateDirRaw = "user-data/agentscope";
        }
        Path stateDir = Path.of(stateDirRaw);
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(cmd.getUserId() == null || cmd.getUserId().isBlank() ? "guest" : cmd.getUserId())
                .sessionId(cmd.getSessionId())
                .build();
        io.agentscope.core.tool.Toolkit toolkit = new io.agentscope.core.tool.Toolkit();
        // 框架把工具派到自己的弹性线程：身份与本轮检索开关覆盖必须在发起线程取快照、在工具内重放，
        // 不重放就是检索恒空且不报错
        java.util.List<Object> holders = tools.frameworkTools(cmd, policy, slot, (code, message) -> {
            log.info("[agentx] 降级 {}: {}", code, message);
            // 未接入的能力必须让用户看见：静默少挂一个族会被读成「配了没生效」。
            // 降级出口也按轮走：实例复用后这里的 channel 是建实例那一轮的，必须从槽位现取。
            AgentxTurnSlot.Turn t = slot.current();
            if (t != null) t.channel().emit(RunEventType.WARN, message);
        // 游客轮 approvalCanAsk=false：分享页没有审批卡通道，漏挂的需审批工具在适配器层直接 deny
        // （fail-closed 拒答，模型收到报错改路）而不是 ask 挂 120s 超时。主闸在
        // ToolMountContext.turnAllows 的 guestMode 轴，这里是兜底。
        }, !cmd.isGuestMode() && !cmd.isApiKey() && interactiveTurn(cmd),
                // 沙盒没装配成功时 run_code 也不该挂：文件系统已退回临时目录，挂一把必然失败的工具
                // 只会让模型白试一步。
                sandboxEnabled && sessions.sandboxAttached(sessions.key(cmd)),
                // MCP 是外部连接：只在工作区档（沙盒已装配）挂载，聊天档纯问答不等外部连接
                // （原 chat.mcpEnabled 调优开关已退役，按默认关收口）。仍受 mcp.turnWaitMs 有界等待。
                sandboxEnabled);
        // 目标模式的控制工具常驻注册：实例按会话常驻，目标可能在建实例之后才建立，
        // 不能等到目标建立再往 Toolkit 里加工具（那需要重建实例）。非目标轮调用会落空返回。
        // 智能体团队：模型面全部由框架提供——成员发现/消息用框架 agent_*，任务板/邮箱用
        // teamsMode 自动注册的 team 工具（见下方 build 前的 teamsMode 装配）。这里不再挂任何
        // 自研 team_* / team_task_* 工具（对齐 DSH：薄 team 层，不造第二套）。
        holders.add(new GoalTools(goalService, slot));
        // 团队任务依赖 shim：框架 TeamTool.createTask 不暴露 blockedBy，模型经这一把声明 DAG 依赖。
        if (teamActive(cmd) && teamGateway != null) {
            tools.mountRawHolders(holders, policy,
                    !cmd.isGuestMode() && !cmd.isApiKey() && interactiveTurn(cmd), slot,
                    new com.wenqu.ai.agentx.team.TeamTaskDependTool(teamGateway, sessionService, slot));
        }
        java.util.Set<String> groupedNames = tools.registerWithGroups(toolkit, holders);
        // 工具表随实例跨轮稳定，登记到槽位：脚本子调用要把框架申报只读的那几把也纳进名册
        slot.noteToolkit(toolkit);
        // 这一轮建了组的名单按会话记下来：每轮的激活集只在这份名单里增删，名单外的组（框架技能带出来的）不碰
        sessions.noteGroupedSurface(sessions.key(cmd), groupedNames);
        AgentxStepBudget stepBudget = new AgentxStepBudget();
        // 实例常驻、闸随实例走，登记到会话表好让引擎在开轮时归零它
        sessions.noteStepBudget(sessions.key(cmd), stepBudget);
        // 重复调用闸：步数预算限不了「每一步都是同一句命令」，见 AgentxRepeatGuard 类注释
        AgentxRepeatGuard repeatGuard = new AgentxRepeatGuard();
        sessions.noteRepeatGuard(sessions.key(cmd), repeatGuard);
        // 框架工具的档位闸：沙箱/文件工具由框架提供，不过问渠装配链，
        // 档位裁剪挪到执行前逐个判。策略按轮取（同实例多轮可能不同档）。
        // 游客 MCP 授权名单一并传入：名单内工具按「发布者预批」在闸上放行（只读档的
        // readOnly 判据对 MCP 恒拒，不认名单就永远调不动，见 AgentxFrameworkToolGate）。
        AgentxFrameworkToolGate toolGate = new AgentxFrameworkToolGate(() -> {
            AgentxTurnSlot.Turn t = slot.current();
            return t == null ? policy : t.policy();
        }, cmd.getGuestMcpServers());
        sessions.noteFrameworkToolGate(sessions.key(cmd), toolGate);
        // 子轮只读记忆：框架四把记忆工具打包注册，没有「只摘写」的开关；
        // 这里在执行前拒 memory_save，保留注入与 memory_search/get（只读也读得到）。
        toolGate.denyMemoryWrites(cmd.branchTurn());
        // 模型实例建两份，共用同一条 HTTP 通道（一个连接池）：
        //  · 主链那份带用户这一轮的思考档与温度；
        //  · 辅助那份只服务框架的记忆提炼与上下文压缩——它们要的是「把话折短」，不是「想清楚」，
        //    跟着主链开思考就是拿最贵的 token 去写一段算完即扔的推理。两条请求参数不同，只能是两个实例；
        //    transport 提出来复用，否则多建一个 OkHttp 客户端＝多一份连接池。
        // 两处的用量各按 kind 分开记账（chat/subagent/memory/compress），窗口都照抄登记值。
        io.agentscope.core.model.transport.OkHttpTransport modelTransport = sharedModelTransport();
        String completionsPath = route.completionsPath() == null || route.completionsPath().isBlank()
                ? "/v1/chat/completions" : route.completionsPath();
        OpenAIChatModel.Builder modelBuilder = OpenAIChatModel.builder()
                .baseUrl(route.baseUrl())
                .apiKey(route.apiKey())
                .modelName(route.modelId())
                .stream(true)
                .httpTransport(modelTransport)
                .generateOptions(modelConfig.generateOptions(cmd, hooks.modelRef(), binding.maxOutput()))
                .endpointPath(completionsPath);
        // 窗口拿得到登记值才交给模型：没拿到时一次都不调，让框架按模型名查它自己的内置表。
        // 显式传 0 会被框架当成「这条模型的窗口是 0」，输入上限直接掉到 64000 兜底值——
        // 压缩阈值随之变成 64000 减去固定开销（系统提示词＋投影＋工具声明），约 44K 就折一次。
        // 值取 binding 里那一份：它同时是实例复用指纹的一部分，两处共用一个数才不会出现
        // 「指纹判成没变、实例里的窗口却是另一个」这种自相矛盾。
        int contextWindow = binding.contextWindow();
        if (contextWindow > 0) modelBuilder.contextWindowSize(contextWindow);
        io.agentscope.core.model.Model baseModel = modelBuilder.build();
        // 辅助调用那一份：同一个 builder 换参数再 build（build() 只读字段、不复用实例），
        // 因此两条路径共用 transport 与窗口，只有请求参数不同。
        io.agentscope.core.model.Model auxModel = modelBuilder
                .generateOptions(modelConfig.auxGenerateOptions(cmd, hooks.modelRef()))
                .build();
        io.agentscope.core.model.Model chatModel = new MeteredModel(baseModel, usageLedger, quotaService,
                com.wenqu.ai.util.UsageAttr.of(cmd.getUserId(), cmd.getSessionId(), null,
                        cmd.branchTurn() ? "subagent" : "chat", cmd.getApiKeyId(), cmd.getShareId()), hooks.modelRef());
        // 主链那份包装器登记到会话表：它是本轮「断流」计数的唯一来源（实例跨轮复用，收尾读差值）
        sessions.noteChatModel(sessions.key(cmd), (MeteredModel) chatModel);
        // 可派遣的助手（派遣由框架的 agent_spawn 驱使）：这一份列表同时决定两件事——
        // 注册进框架的子智能体表（模型手上有没有这把能力），以及提示词里那两句路由纪律（教不教它用）。
        // 分成两处判就会漂移成「教了模型一件它没有的能力」。
        List<WenquAgentxTools.SubagentTarget> subagentTargets =
                tools.consultSubagentTargets(cmd, policy, slot);
        // 工作区记忆只读一次：既要拼进系统提示词，也要让本轮污点知道「上下文里已经混有可编辑资料」
        String workspaceMemory = workspaceMemoryContext(cmd);
        // 叠加档（memory_mode=2）：用户全局 MEMORY.md 以只读资料身份跟注入（专属档不注入，见方法注释）
        String userMemoryReadonly = userMemoryReadonlyContext(cmd);
        // 记忆是持久注入面：本轮后续的破坏性命令/越界写要回到人面前确认（见 AgentxTaint）。
        // 实例按会话常驻，这个标记跟着实例走；记忆文件之后新增/清空要等重建才换，方向是「更保守」。
        slot.noteWorkspaceMemoryTainted(!workspaceMemory.isEmpty() || !userMemoryReadonly.isEmpty());
        // 本轮的沙盒/工作区文件系统：build 期算一次，既喂给 builder 的 abstractFilesystem，
        // 也给读图工具（read_image）复用同一份——两处若各算一次，可能拿到不同后端、读写与读图指向不同盘。
        io.agentscope.harness.agent.filesystem.AbstractFilesystem frameworkFs = frameworkFilesystem(cmd,
                policy.sandboxMode() == com.wenqu.ai.agent.permission.SandboxMode.DANGER_FULL_ACCESS,
                sandboxEnabled, ew);
        HarnessAgent.Builder agentBuilder = HarnessAgent.builder()
                .agentId(AGENT_ID)
                .name("wenqu")
                .description("问渠·AgentScope 运行时")
                // 角色提示词归智能体管：见 rolePrompt 的分层说明。
                // 派遣纪律必须跟着挂：清单本体由框架的 ## Subagents 段渲染，但「id 逐字取自清单、
                // 通用问题不要派」这两句只有问渠说得出（派错一位的成本是一整条子轮）
                // 用户口径那条（正文/计划/清单不写沙盒路径与工具名）也要跟着挂：框架这一路没人再说第二遍，
                // 而框架的 PLAN banner 与沙盒工具说明反而主动把路径喂给模型——不挂，
                // 计划卡就变成「它打算在沙盒里怎么写文件」的说明书
                .sysPrompt(rolePrompt(binding.row()) + projectContext(cmd)
                        + workspaceMemory
                        + userMemoryReadonly
                        // 框架会把工作区渲染成服务器沙盒路径；本机执行器模式要补一段真话压住它
                        + localExecutorEnvironment(cmd, ew)
                        + (subagentTargets.isEmpty() ? "" : tools.dispatchDiscipline())
                        // 团队模式：告诉模型何时用团队任务板与成员消息（只有本会话开着团队才追加）
                        + teamGuidance(cmd)
                        // 跨族分工（「本机」在 SSH 与桌面两族之间有歧义）：一族的说明代替不了另一族，
                        // 只有这里能说清，且只在本轮真挂了相关工具时追加。
                        + ToolDiscipline.guidance(mountedToolNames(cmd, policy, slot))
                        + PlanCopy.guidance()
                        + com.wenqu.ai.agent.chat.ArtifactPrompt.guidance()
                        + com.wenqu.ai.memory.MemoryPrompts.replyDiscipline()
                        + (relatedSuggestionsEnabled(cmd.getUserId())
                            ? com.wenqu.ai.agent.chat.RelatedPrompt.line() : ""))
                // 问渠所有供应商都是 OpenAI 兼容网关。
                // 用户在界面选的上下文窗口（contextWindow 档位）落到 model 的 contextWindowSize：
                // 框架的压缩阈值/输入预算都由它推（CompactionMiddleware: window-reserved；
                // HarnessContextBuilder.inputLimit: window-output-safety），
                // 不设就按 ModelContextWindows.lookup 按模型名查内置表，问渠登记窗口≠框架内置表时
                // 会差一截。0/未登记不设（保持框架按模型名推断），避免把显式 0 塞给模型。
                .model(chatModel)
                .toolkit(toolkit)
                // 框架对「一把工具」有自带的 5 分钟硬超时（TOOL_DEFAULTS）。咨询子轮、沙盒长命令都会撞上去，
                // 而手写运行时没有这一层——不显式设，就等于框架比问答侧多一道没人知道的天花板。
                .toolExecutionConfig(io.agentscope.core.model.ExecutionConfig.builder()
                        .timeout(java.time.Duration.ofSeconds(turnSeconds()))
                        .build())
                .maxIters(maxItersForTurn())
                // 计划模式工具面常驻开启：是否进入只由用户显式开计划开关（sessions.setPlanMode）或
                // 模型自己在推理中调用 plan_enter 决定。问渠不做「按消息意图预判」，
                // 计划怎么执行全交框架的 plan_enter/write/exit + 只读拦截。
                // 历史计划卡的多版本落库仍由 AgentxTurnRecording.planVersions 负责，与意图判定无关。
                .enablePlanMode(!cmd.branchTurn())
                // 目标模式（Goal Mode）：不借框架的任务上下文投影（Scope/要求/验证）——
                // 目标由问渠自己带进模型输入（交互轮拼目标上下文，续跑轮用 GoalPromptBuilder 的续跑提示词），
                // task_requirement_propose 也不注册（模型没有地方、也没有必要「提议验收标准」）。
                // 任务清单的投影不受影响：TaskContextProjection 仍照常渲染 todo 状态，它只看自己的
                // tasksContext 存储，与这里的开关无关。
                // 任务清单：**不开**框架的 enableTaskList，只借它的 state 做存储。
                // 原因：开它会同时注册 todo_write 工具并由 TaskReminderMiddleware 每轮教模型用
                // 「in_progress/completed」这套词，而问渠挂的是 writeTodo（教的是 doing/done，
                // 且带护栏与中文展示名）。两套工具 + 两套状态词同时进模型上下文，它会挑错工具、
                // 或者把 doing 写进 todo_write 被框架判非法。storage 用框架的、tool 面用问渠的，
                // 是这里刻意的分工——框架 AgentState 才是清单的权威真源（见 TodoBridge）。
                // 真要换成框架工具面，得连护栏、中文名、前端契约一起换，不能只开这个开关。
                // 上下文压缩：只按「窗口推导出来的那道 token 线」触发（按条数触发那道已在
                // compactionConfig 里关掉，逐字保留最近 20 条仍走框架默认）。
                // 用户选的窗口已落到 model.contextWindowSize（见 .model(...) 段），压缩阈值由它推导，
                // 这里不再单独传 maxContextTokens（那是 WorkspaceContextMiddleware 的注入预算，非压缩阈值）。
                // 压缩摘要用独立的 MeteredModel（kind=compress），成本才进得了台账。
                .compaction(modelConfig.compactionConfig(cmd, auxModel, hooks.modelRef()))
                .workspace(stateDir)
                // 会话状态恒存 Redis，没有本地文件兜底（容器重建即丢，会让多轮对话退化成每轮独立问答）。
                // Redis 连不上时应用起不来——宁可启动失败，也不要带着「每轮失忆」对外服务。
                .stateStore(stateStore())
                // 权限由框架持有：档位→模式、按名黑名单→deny 规则；工具级不可绕过的判定落在
                // SpringAiToolAdapter#checkPermissions。游客 MCP 授权名单一并传入：
                // 名单非空的游客轮框架模式从 EXPLORE 提到 DEFAULT（EXPLORE 对非只读工具
                // 无条件拒，名单工具永远调不动），判定回落到工具适配器（见 AgentxPermissions）
                .permissionContext(AgentxPermissions.contextOf(policy, cmd.getGuestMcpServers()))
                // 待办持久化 + 答复后自动接续原任务，是这套执行形态的立身之处
                .enablePendingToolRecovery(true)
                // 重复调用闸：同一工具同一参数连续第 3 次起拒掉。order=2 排在步数闸外侧，
                // 空转的重复调用不占步数预算——它不该挤掉真正要做的事的那几步
                .middleware(repeatGuard)
                // 步数预算闸（对标 StepBudgetGuard）：maxIters 限的是推理轮数而不是工具调用次数，
                // 一轮里模型可以一次要好几把工具，账单闸只能在 onActing 里数
                .middleware(stepBudget)
                // 档位闸必须排在步数闸之后：先判「该不该跑」，再判「还剩几步」，
                // 否则被档位拒绝的那次会白占一步预算（AgentxStepBudget 的「判过即占一步」口径）。
                .middleware(toolGate)
                // 流式可见性闸：框架只在一次模型调用结束时才把 chunk 落盘，而观众跟的是落盘后的日志，
                // 界面上就是「正文一下粘出几百字」。这里把它拆成 200ms 一片（代价见类注释）。
                .middleware(new AgentxStreamFlushMiddleware())
                // 工具面闸：无子智能体/无活跃目标时隐藏对应的编排工具，省 schema 降误选。
                .middleware(new AgentxToolSurfaceFilter(slot, !subagentTargets.isEmpty()))
                // 框架默认装备子智能体/技能/工作区上下文/tools.json 等旁路能力。
                //
                // 子智能体由**框架驱使**：
                // 去掉 disableSubagents / disableDynamicSubagents 后框架装配 SubagentsMiddleware，
                // 注册 agent_spawn / agent_send / agent_list 三把工具，并每轮在 SYSTEM 消息前
                // 插入子智能体使用指引与异步任务摘要（框架自己从「冻结基线」重建 SYSTEM，
                // 内容不会跨轮累积——见 SubagentsMiddleware 类注释第 3 条）。
                // 问渠不再另挂一把咨询工具：每位「可咨询助手」登记成框架的子智能体
                // （见 {@link WenquAgentxTools#consultSubagentTargets}），派遣时机、并行与后台等待
                // 都归框架；子轮本体仍是问渠那条完整问答通路（临时会话、引用回填、产物收编），
                // 因为框架的子 agent 只回一段文本、没有问渠的问答外壳。
                //
                // 技能由**框架接管**：
                // WenquSkillRepository 实现 RuntimeContextSkillRepository，按当轮 uid 解析可见技能
                // （classpath 内置 + 沙盒工作区 + user-data/skills-users/{uid}/，同名用户版优先）。
                // disableDynamicSkills 去掉——那会拦掉框架的动态技能加载。
                // disableDefaultWorkspaceSkills 保留：工作区里的默认技能集与问渠的技能体系无关。
                .skillRepositories(List.of(skillRepository))
                // 智能体的「技能范围」真正生效的地方：三态 → SkillFilter。
                // 框架的 HarnessSkillMiddleware 按它裁目录（被滤掉的不进提示词、也不注册 load 工具），
                // 所以「限定用哪几个」是边界而不是建议。空白名单/关闭 = 一条都不给。
                // 注意：只能走 builder —— AgentSession 构造时把 RuntimeContext 复制了一份
                // （AgentSession:84），事后往原 ctx put SkillFilter 影响不到已建实例。
                .skillFilter(binding.skillFilter())
                // 助手侧「技能沉淀」仍交给框架 skill_manage：写沙盒工作区，随会话走。
                // 用户自己的技能安装/编辑/删除走 SkillsPage + SkillController（user-data/skills-users/{uid}，
                // 跨会话、不依赖沙箱）；两套并存，本开关只负责「模型自己起草/改写技能」这条路。
                // autoPromote=true：新技能直接落可加载目录，下一轮推理即可见。
                // 框架注释：默认会先进 drafts 目录「不自动加载」，那是给谨慎场景的默认——
                // 问渠的会话内技能本来就是当轮要用，加 drafts 只是多一步人工提升。
                .enableSkillManageTool(io.agentscope.harness.agent.tool.SkillManageConfig.builder()
                        .autoPromote(true)
                        .build())
                .disableDefaultWorkspaceSkills()
                // 记忆由框架接管：去掉 disableMemoryTools 后框架注册
                // memory_search / memory_get / memory_save / session_search 四把。
                //
                // 隔离性核对过：`WorkspaceManager#getMemoryDir(rc)` → `resolveRuntimeDataPath(rc, ...)`
                // 按 RuntimeContext 解析，本轮的 uid 不同则记忆目录不同 —— 满足问渠的 per-user 语义。
                //
                // 已接受的降级：框架 `MemorySearchTool` 是 **keyword 检索**
                // （源码注释：*"Uses keyword-based search through all memory files"*），不是向量检索；
                // 且用户没有「长期记忆」面板的手动管理入口。
                //
                // 不设置 disableWorkspaceContext：该开关会整条关掉 WorkspaceContextMiddleware，
                // 而它承担记忆「读回」的两件事——
                //   ① readMemoryMd(rc) 把 MEMORY.md 注入 HARNESS_CONTEXT（不开则模型看不到已沉淀的记忆）
                //   ② 注入 Memory Recall / Memory Persistence 使用指引（不开则模型不知道
                //      memory_search 四把工具何时用）
                // 副作用核对：该 middleware 注入的 ## Workspace 段落会描述沙箱路径，
                // 但问渠有 PlanCopy.guidance()（提示词「正文/计划/清单不写路径与工具名」）
                // + PlanCopy.stripInternalPaths（计划卡兜底剥路径）两层护栏，与它共存无冲突。
                .disableAtPathExpansion()
                // 跨能力按名禁用交给框架 ToolFilter：deny 名单从 ExecutionPolicy 来，build 期直接从
                // Toolkit 移除（schema/查找/执行一起消失）。三态与具体项仍由问渠业务适配层决定。
                .toolsConfig(tools.toolsConfig(cmd, policy, toolkit.getToolNames()))
                // 沙箱与文件系统：**框架接管**。
                // 关键：框架的工具在 **build 期**绑定文件系统——
                //   HarnessAgent:2895 `new FilesystemTool(filesystem, pathNormalizer)`
                //   HarnessAgent:2903 `new ShellExecuteTool(sandbox)`（filesystem 当沙箱用）
                // 两者都从 builder 字段取，**不看 RuntimeContext**。
                // 往 ctx 里 put AbstractFilesystem 无效，必须用 abstractFilesystem(...) 在 build 期传进去。
                .abstractFilesystem(frameworkFs)
                .artifactDeliveryTarget(new com.wenqu.ai.sandbox.WenquArtifactDeliveryTarget(artifactService))
                .disableWebTools();

        // 记忆闸：三种轮次三种口径，必须在 build 期定——MemoryConfig / disableMemoryHooks
        // 都烤在常驻实例里。
        // ① 游客/分享/MCP：userId 是发布者（检索可见性按他执行），若不关记忆，发布者的 MEMORY.md
        //    会被注入访客对话，访客的话也会写进发布者日账。两个开关必须同时关：只关 hooks 仍会注入。
        // ② 子智能体/咨询轮：只读不写——保留 MEMORY.md 注入与 memory_* 工具（子轮要用户上下文），
        //    但关掉 flush 与后台合并；否则每派一支就多一次提炼调用，内部推演还会沉淀成用户事实。
        // ③ 正常用户轮：平台总闸与个人开关都开着才挂 memoryConfig；任一关掉走 disableMemoryHooks
        //    （与②同一条路：不注册 flush/维护，模型侧也不再有「自动提炼」引导，已沉淀的仍注入）。
        // ④ 工作区记忆隔离（Project.memoryIsolated，干净房间）：本区会话不注入本人已沉淀的个人记忆、
        //    也不往里写——按①同款全关。给敏感项目/客户现场一个不串味的场子；工作区自己的上下文由
        //    约定+绑定资料域+目录文件承担。
        if (cmd.isGuestMode() || projectService.memoryIsolatedOfSession(cmd.getSessionId())) {
            agentBuilder.disableMemoryHooks().disableMemoryTools();
        } else if (cmd.branchTurn() || !modelConfig.memoryOn(cmd)) {
            agentBuilder.disableMemoryHooks();
        } else {
            agentBuilder.memory(modelConfig.memoryConfig(cmd, auxModel, hooks.modelRef()));
        }
        io.agentscope.harness.agent.DistributedStore distributedStore = distributedStore();
        if (distributedStore != null) {
            agentBuilder.distributedStore(distributedStore);
        }
        // 消息总线/异步工具注册表：非团队轮仍用无操作实现（避免沙盒文件系统版的每轮 HTTP 往返）；
        // 团队轮换成 Redis 总线，TeamsMiddleware 的 mailbox 唤醒与 InboxMiddleware 的收件箱注入才生效。
        boolean teamOn = teamActive(cmd) && teamBus != null;
        agentBuilder.messageBus(teamOn ? teamBus : NoopBusComponents.MESSAGE_BUS);
        agentBuilder.asyncToolRegistry(NoopBusComponents.ASYNC_TOOL_REGISTRY);
        // 动态子智能体声明关掉：问渠的子智能体全部走 subagentFactory 静态注册，工作区里从不放
        // subagents/*.md 声明文件——而 DynamicSubagentsMiddleware 每轮模型调用前都会 glob 扫一遍
        // 工作区（经沙盒文件系统 = 一次容器 HTTP + provisioner touch），每轮都多这一笔开销。
        // 只关「声明文件扫描」这一支，spawn 工具与静态注册的子智能体不受影响。
        agentBuilder.disableDynamicSubagents();
        // 问渠的助手登记成框架的子智能体（派遣改由框架 agent_spawn 驱使，问渠不再挂 consultAgent）。
        // agent_id 逐字用问渠的智能体 id：卡片归属、台账与提示词里的 id 才是同一个东西。
        // 工厂造出来的实现体不是框架的模型循环，而是「起一条完整问答轮」那条通路（见 WenquConsultSubagent）——
        // 子轮要的是问渠的问答外壳：即时落库、轮级互斥、断线续跑、引用回填、产物收编。
        // 框架自带的 general-purpose 一支照旧存在（dynamic 开着），它派的是通用工作者，与「咨询某位助手」
        // 不是一件事，也不共用编号体系。
        for (WenquAgentxTools.SubagentTarget target : subagentTargets) {
            agentBuilder.subagentFactory(target.agentId(), target.description(), target.factory());
        }
        // 框架原生团队模式：自动挂 TeamsMiddleware（团队提示/看板摘要/唤醒桥）并注册角色裁剪的
        // team 工具。根=lead，成员=worker；任务板/邮箱/名册全部由框架 TeamClient 承担。
        if (teamActive(cmd) && teamGateway != null) {
            String root = cmd.getTeamRootSessionId() != null && !cmd.getTeamRootSessionId().isBlank()
                    ? cmd.getTeamRootSessionId() : cmd.getSessionId();
            boolean isLead = cmd.getMemberSessionId() == null || cmd.getMemberSessionId().isBlank();
            String role = isLead ? com.wenqu.ai.agentx.team.WenquTeamGateway.LEAD : cmd.getMemberName();
            teamGateway.ensureTeam(root, cmd.getAgentId());
            agentBuilder.teamsMode(teamGateway.client(),
                    teamGateway.contextFor(root, role, isLead), cmd.getSessionId());
        }
        HarnessAgent agent = agentBuilder.build();
        AgentxToolDescription.alignWriteFileDescription(agent);
        AgentxToolDescription.alignExecuteDescription(agent);
        applyNativeToolGuards(agent, slot, cmd, ew);
        registerReadImageTool(agent, hooks, frameworkFs);
        return agent;
    }

    /**
     * 框架原生文件/命令工具的统一护栏（注入防护补全）。
     *
     * <p>与 {@link AgentxToolDescription#alignWriteFileDescription} 同一做法：build 之后按同名重新注册。框架在 build 期
     * 把 {@code FilesystemTool / ShellExecuteTool} 注册进 toolkit 的副本，问渠只能在这个副本上覆盖；
     * 包一层后名字、schema、只读位、执行全部不变，只多「结果加信封」与「污点时强制确认」。
     *
     * <p>只包会返回外部内容的几把：read/grep/glob/list/execute。write/edit 只管越界确认，
     * 不包结果（它们返回的是「成功/失败」，不是数据）。
     */
    private void applyNativeToolGuards(HarnessAgent agent, AgentxTurnSlot slot, ChatCommand cmd,
                                       com.wenqu.ai.service.EffectiveWorkspace ew) {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) return;
        List<String> roots = workspaceRootsOf(cmd, ew);
        // 游客分享/API Key 轮没有审批卡通道：污点触发时直接拒，别挂一张没人答的卡等超时
        boolean canAsk = !cmd.isGuestMode() && !cmd.isApiKey() && interactiveTurn(cmd);
        // 根会话级放行：用户在父轮点过「本会话都允许」的工具，这一支的污点确认不再问
        String grantScope = tools.toolGrantScope(cmd);
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.READ_FILE,
                AgentxNativeToolGuard.Kind.DATA, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.READ_FILE));
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.GREP_FILES,
                AgentxNativeToolGuard.Kind.DATA, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.GREP_FILES));
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.GLOB_FILES,
                AgentxNativeToolGuard.Kind.DATA, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.GLOB_FILES));
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.LIST_FILES,
                AgentxNativeToolGuard.Kind.DATA, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.LIST_FILES));
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.EXECUTE,
                AgentxNativeToolGuard.Kind.EXEC, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.EXECUTE));
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.WRITE_FILE,
                AgentxNativeToolGuard.Kind.WRITE, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.WRITE_FILE));
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.EDIT_FILE,
                AgentxNativeToolGuard.Kind.EDIT, () -> tools.toolGranted(grantScope,
                        FrameworkToolLabels.FilesystemToolNames.EDIT_FILE));
    }

    private static void wrapNative(Toolkit toolkit, AgentxTurnSlot slot, List<String> roots,
                                   boolean canAsk, String name, AgentxNativeToolGuard.Kind kind,
                                   java.util.function.BooleanSupplier sessionApproved) {
        AgentTool tool = toolkit.getTool(name);
        if (tool == null || tool instanceof AgentxNativeToolGuard) return;
        toolkit.registerAgentTool(new AgentxNativeToolGuard(tool, kind, slot, roots, canAsk, sessionApproved));
    }

    /**
     * 工作区根的绝对路径（用于「越界写」的词法判断）。
     * 绑了本机目录就取源文件夹；服务器沙盒/无工作区返回空表——空表时任何绝对路径都按越界处理，
     * 方向偏保守（最多多一张确认卡），不会把外部路径误判成工作区内。
     */
    /**
     * 读图工具（{@code read_image}）挂载：只在「本轮模型支持读图 + 有可用文件系统 + 开关开启」时注册。
     * <p>读不了图的模型挂上它只会拿到一张上游不认的图片块，与「被禁的能力与不存在不可区分」同口径，
     * 所以不收窄成「挂了再拒」，而是直接不挂（与提示词只在能读图时才塞 ImageBlock 一个判据）。
     * <p>体积上限复用对话图片上限口径（{@code ConfigDefaults.CHAT_MAX_IMAGE_MB}，夹在 1~20MB），
     * 与 ChatRunController / ShareController 的入站限制同一份真源，避免超大图撑爆上下文。
     * <p>幂等：实例按会话常驻、每轮都会走这里，已挂过就跳过，不重复注册同名工具。
     */
    private void registerReadImageTool(HarnessAgent agent, WenquAgentxTools.TurnHooks hooks,
                                       io.agentscope.harness.agent.filesystem.AbstractFilesystem fs) {
        if (agent == null || fs == null) return;
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) return;
        if (toolkit.getTool(ReadImageTool.NAME) != null) return;
        // 不配平台开关：读图是只读能力，挂载条件只有「模型这一轮能读图 + 文件系统可用」；
        // 要关就按名停用 read_image（平台停用名单/智能体 deny），与文件类工具同一口径。
        boolean vision = hooks != null && modelRegistry.visionCapableOf(hooks.modelRef());
        if (!vision) return;
        int mb = Math.max(1, Math.min(20, ConfigDefaults.CHAT_MAX_IMAGE_MB));
        toolkit.registerAgentTool(new ReadImageTool(fs, mb * 1024 * 1024));
    }

    private List<String> workspaceRootsOf(ChatCommand cmd) {
        return workspaceRootsOf(cmd, null);
    }

    /**
     * @param ew 建实例那轮的解析结果（build 期消费者传）；null=运行期现查（审批卡等），
     *           两者口径一致：都取显式工作区的全部根、主根排第一。
     */
    private List<String> workspaceRootsOf(ChatCommand cmd, com.wenqu.ai.service.EffectiveWorkspace ew) {
        List<String> out = new ArrayList<>();
        try {
            List<String> raw;
            if (ew != null && ew.explicit()) {
                // 主根排第一（ew.primaryRoot 已按 primaryRoot 标记选出）；其余按 ew.roots 顺序
                raw = new ArrayList<>();
                if (ew.primaryRoot() != null) raw.add(ew.primaryRoot());
                for (String p : ew.roots()) if (!p.equals(ew.primaryRoot())) raw.add(p);
            } else {
                com.wenqu.ai.model.Project p = projectService.projectOfSession(cmd.getSessionId());
                if (p == null || !projectService.isDirect(p)) return out;
                com.wenqu.ai.model.ProjectRoot primary = projectService.primaryRootOf(p);
                raw = new ArrayList<>();
                // 主根必须排第一：相对路径（含 ..）按主根解析，AgentxDestructiveCommands 取 roots.get(0)
                if (primary != null && primary.getPath() != null && !primary.getPath().isBlank()) {
                    raw.add(primary.getPath());
                }
                for (com.wenqu.ai.model.ProjectRoot r : projectService.rootsOf(p)) {
                    if (r == null || r.getPath() == null || r.getPath().isBlank()) continue;
                    if (primary != null && r.getPath().equals(primary.getPath())) continue;
                    raw.add(r.getPath());
                }
            }
            for (String p : raw) out.add(AgentxTextUtil.realRoot(p));
        } catch (Exception e) {
            log.warn("[agentx] 工作区根解析失败（越界写确认按无根处理）session={}: {}",
                    cmd.getSessionId(), e.getMessage());
        }
        return out;
    }



    /**
     * 收尾帧沿用前端 {@code done} 的信封字段。来源与降级都取自本轮引用池——与手写运行时同一个
     * {@code TurnSourceRegistry}，前端引用面板不需要为框架引擎写第二套渲染。
     */
    /**
     * 半程轮的用户可见标注。三种成因必须分开写（与问渠台账 {@code TurnRecorder} 同一组终态标记口径）：
     * 用户自己按了停止 ≠ 没人答卡等到超时 ≠ 连接断了或被看门狗掐——把后两种写成「已停止本轮」是在怪用户。
     * <p>措辞按卡种分：卡是提问还是审批，决定超时这件事该说成「你有机会答但没答」还是「闸门没被放行」。
     * 提问卡超时后这一轮本来就不该走到这里（走的是「按未作答继续」那条），所以这里出现的多半是
     * 计划卡或审批卡——用「确认」统称即可，不必替用户猜是哪一种。
     */
    /**
     * 接流快照：本轮到此刻已经产出的东西，一次交全给总线（见 {@code ChatRunBus#attach}）。
     * <p>字段名与前端 {@code applyTurnSnapshot} 一一对齐，且与「刷新后读历史」那份同源同形：
     * 正文/思考/工具卡/时间线出自 {@link AgentxTurnRecording#snapshotFields()}，引用出自本轮引用池，产物走
     * {@code takeArtifacts} 那份不消费的副本。缺哪一项，前端只是那一段还没长出来，不会报错。
     * <p>提问/审批卡不含：它们的时限与「唤醒句柄还在不在」以库为准，前端走既有的 pending 端点恢复；
     * 计划卡例外——它有自己的运行态（草稿/待确认/退回重出/已批准），必须随快照下发，否则新页签在
     * 计划批准后的人机等待期里整张卡都不见了。也不含逐字增量，正文取缓冲本身比回放上千条 token 帧快。
     *
     * @param startedAt 本轮开轮时刻（服务端墙上钟，见 {@code followTurn} 的入参）
     * @param prepMs    开轮前准备那一段（服务端收到提问 → 开轮起表）；0 = 这一跑没有「收到提问」基准
     */
    private Map<String, Object> turnSnapshot(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                                             ModelRegistryService.ModelRoute route, AgentxTurnRecording recording,
                                             String turnId, long startedAt, long prepMs) {
        Map<String, Object> snap = recording.snapshotFields();
        snap.put("running", true);
        snap.put("phase", "generating");
        snap.put("sources", new java.util.ArrayList<>(pool.sources()));
        // 配图清单随快照一起给：image 帧是「有新增才发全量」的状态事件，接流方漏不上那一帧就是
        // 整轮没有图位（前端按 images[N-1] 还原，缺数组则 [图片N] 原样留在正文里）
        snap.put("images", pool.signedImages());
        snap.put("artifacts", artifactService.takeArtifacts(cmd.getSessionId()));
        snap.put("model", route.modelId());
        snap.put("turnId", turnId);
        // 计划卡：草稿/待确认/退回重出/已批准都按运行时状态重建；落库的 planRecord 只在收尾有
        Map<String, Object> planCard = planCardForSnapshot(cmd, recording);
        if (planCard != null) snap.put("planCard", planCard);
        // 游客接流同样只给答案块；完整 blocks 留在服务端与登录态历史里
        if (cmd.isGuestMode() && snap.get("blocks") != null) {
            Map<String, Object> minimal = TurnBlocks.answerOnly(snap.get("blocks"));
            if (minimal == null) snap.remove("blocks");
            else snap.put("blocks", minimal);
        }
        // 到此刻已经跑掉的毫秒（每次 attach 现算）：接流那端的秒表是从接上那一刻起表的，不给这个数，
        // 刷新一下「正在溯源，用时 N 秒」就从 0 重新跳一遍——已经跑掉的那段在用户眼里不算数了。
        // 给「跑掉了多久」而不给「绝对开轮时刻」：前端本地钟与 nowTick 都在客户端钟上，跨钟相减会把
        // 两边的偏差直接算进用户眼里；给时长则只差这一帧在路上的时间。
        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        snap.put("elapsedMs", elapsedMs);
        // 整轮墙钟（含开轮前准备）与那一段准备一起给：接流那端的秒表按这一份起表，
        // 否则「刷新一次」会把排队+装配那几秒抹掉（用户看见的数凭空变小，与 done 同一件事）。
        // 没有请求体的入口 prepMs=0、基准就是开轮时刻——照样给，刷新后才接得上同一本账。
        snap.put("wallMs", elapsedMs + prepMs);
        snap.put("prepMs", prepMs);
        if (cmd.getAgentId() != null && !cmd.getAgentId().isBlank()) {
            snap.put("agentId", cmd.getAgentId());
            String name = agentNameOf(cmd);
            if (name != null) snap.put("agentName", name);
        }
        return snap;
    }

    private Map<String, Object> planCardForSnapshot(ChatCommand cmd, AgentxTurnRecording recording) {
        return planCardSnapshot.build(cmd, recording);
    }


    /**
     * 收尾：落库、下发 done 帧、进问答台账。
     *
     * @param startedAtMs 开轮那一刻的<b>绝对时刻</b>（服务端钟）。整轮用时不在这里收预采样的数：
     *                    收尾自己还会跑引用自检这类秒级 LLM 调用，起表点交进来、用时在自检之后
     *                    只取一次，done/落库/台账才读到的都是同一份含自检的墙钟
     * @param humanWaitMs 这一轮里用户读卡/答题累计的等待，随 done 下发供界面上单独说明
     * @param prepMs      开轮前准备那一段（服务端收到提问 → 开轮起表）；0 = 没有请求体的入口，
     *                    此时起表基准就是开轮时刻，整轮墙钟照常下发与落库（不含到达流水线前的等待）
     */
    private void finish(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                        ModelRegistryService.ModelRoute route, String modelRef, int contextWindow, String answer,
                        AgentxTurnRecording recording,
                        String userMessageId, boolean stopped, boolean timedOut, boolean budget, boolean stalled,
                        boolean awaiting,
                        long startedAtMs, long humanWaitMs, long prepMs,
                        AgentSession session, int historyBefore, String turnId,
                        Map<String, Object> planRecord, String recoveredNote) {
        // 落库这一轮：只发帧不落库的话，界面上点进去就是空会话。
        // 走 SessionService 同一入口，不另写一套消息表——会话列表、历史、引用面板都吃这份数据
        String body = answer == null ? "" : answer;
        // 每轮文件改动：轮末对比（轮前快照在这里等齐）。非本机工作区/失败=不出卡，绝不给收尾添错。
        Map<String, Object> turnChanges = cmd.isApiKey() ? null
                : turnChangeService.settle(cmd.getSessionId(), turnId);
        // 推荐块两道护栏，都住在 ModelOutputParser 里：条目数超过要的那 N 条＝整块是被塞进来的正文
        // （全数并回、一颗芯片不出），否则只并回长过正文量级的那几条。
        int relatedMaxChips = RelatedPrompt.COUNT + RELATED_CHIPS_TOLERANCE;
        List<String> relatedProse = recording.related.prose(RELATED_PROSE_MIN_CHARS, relatedMaxChips);
        if (!relatedProse.isEmpty()) {
            String merged = String.join("\n\n", relatedProse);
            body = body.isBlank() ? merged : body.stripTrailing() + "\n\n" + merged;
            pool.registrar().degrade("relatedHoldsAnswer",
                    "模型把正文写进了推荐块（" + relatedProse.size() + " 段），已并回回答");
            log.warn("[FAIL-LOUD] related 内含正文 {} 段（{} 字），已并回回答: session={}",
                    relatedProse.size(), merged.length(), cmd.getSessionId());
        }
        List<String> relatedChips = recording.related.chips(RELATED_PROSE_MIN_CHARS, relatedMaxChips);
        // 内部标记闸：上游偶发把工具调用当正文吐出来（本该进 tool_calls 的那一截进了 content）。
        // 剥离器已经把脏文本吞了，这里补的是「别让它静默」——这一步系统真的没执行，用户有权知道
        // 内容是缺的，而不是读到一个凭空短了一截的回答。
        // 日志只报字数不报内容：调用入参里常带 URL 与令牌，抄进日志等于换个地方泄露。
        String leakedCall = recording.related.dsml();
        if (!leakedCall.isEmpty()) {
            pool.registrar().degrade("toolCallAsText",
                    "模型把一步操作写成了文本，系统没能执行它，这一步的内容可能缺失");
            log.warn("[FAIL-LOUD] 模型把工具调用写成正文 {} 字（已剥离、未执行）: session={}",
                    leakedCall.length(), cmd.getSessionId());
        }
        // 半程轮有过程就落库、连过程都没有就不落（与手写侧 persistPartialAnswer 同一道守卫）：
        // 「正文非空」单独判会让只跑了工具的轮整条蒸发，而真空轮落一条空消息只是污染历史。
        boolean realAnswer = !body.isBlank();
        boolean hasProcess = !recording.toolCalls.isEmpty() || recording.thinking.length() > 0;
        boolean supersededRound = planRecord != null && "superseded".equals(planRecord.get("status"));
        // 尾标进了正文的那一支，同句话再结构化入一块（noticeText 非空即「本轮被中断」的事实）：
        // 两边都写：正文里留一份，blocks 里的 notice 给刷新/分享与渲染用。
        com.wenqu.ai.agent.chat.TurnCloseout.Body closeout =
                com.wenqu.ai.agent.chat.TurnCloseout.settleBody(body, hasProcess, supersededRound,
                        stopped, timedOut, budget, stalled, awaiting);
        body = closeout.text();
        String noticeText = closeout.noticeText();
        boolean persist = closeout.persist();
        // 重启接续的空转轮：没有正文也没有过程，走上面的判据就是「不落库」，用户面对一条凭空消失的回答。
        // 落一句中断说明而不是造一条空回答——「这一轮因重启没答完」是用户该知道的事实。
        if (recoveredNote != null && !persist) {
            body = recoveredNote;
            noticeText = recoveredNote;
            persist = true;
        }
        // 本轮配图清单的唯一快照：正文 [图片N] → 第 N-1 项。流式 image 帧、done.finalImages、
        // 落库 images 全部出自池的 imgIndex；这里取一次，三处共用同一份。定稿校验只读它判编号
        // 越界，绝不裁剪 / 重编 / 回写——清单一旦在定稿被改写，就会「流式有图、答完没图」。
        // 只随 SSE image 帧下发而不落库的话，刷新后会全变成 [图片1] 四个字，所以落库也必须用它。
        List<String> finalImages = pool.rawImages();
        // 引用完整性校验：悬空角标剔除、「有来源却没标 [N]」的 fail-loud、「编号对上了但语义不支撑」的
        // LLM 自检。停止轮与「只有过程没有正文」的半程轮都不跑：那两句标注不是答案，跑了对它们就是白报一条
        // 「未标注引用」（半程轮往往还带着工具召回的来源，正好满足那条 fail-loud 的触发条件）。
        if (com.wenqu.ai.agent.chat.TurnCloseout.citationsApplicable(stopped, timedOut, budget, stalled, realAnswer)) {
            // 这里必须交引用串 {providerId}/{modelId} 而不是 route.modelId()：自检那次调用走
            // DynamicOpenAiChatModel，它只按引用解析路由，裸模型名一律 fail-loud——传错就是这道防线
            // 每轮静默哑火（只剩一条 WARN，正文照旧）。落库和 done 帧用裸名是展示口径，不同。
            var outcome = citations.check(body, new com.wenqu.ai.agent.context.CitationIntegrityService.Ctx(
                    cmd.getSessionId(), cmd.getUserId(), modelRef,
                    pool.sources(), finalImages,
                    pool.degradations(), pool.degradedCodes(), new java.util.HashMap<>(), startedAtMs));
            body = outcome.text();
            // 自检会裁掉不支撑的来源并重编编号：池不回写，正文角标与引用面板就会错位。
            // 配图清单不在这里回写（Outcome 里根本没有它）。
            pool.applyCitationOutcome(outcome);
        }
        // 整轮用时只在此取一次——必须在自检之后：自检是收尾里唯一秒级的 LLM 段，用时从绝对起表点
        // 现算，done/落库/台账读到的墙钟天然含自检，屏幕上秒表不会在答完那一刻往回跳
        // （旧形状：调用方预采样 wallMs 传进来反推起表点，采样点早于自检，1:25 答完报 11.3 秒）。
        long wallMs = System.currentTimeMillis() - startedAtMs;
        long elapsedMs = Math.max(0L, wallMs - humanWaitMs);
        com.wenqu.ai.agent.chat.TurnHandle handle =
                com.wenqu.ai.agent.chat.TurnRegistry.handle(cmd.getSessionId());
        // 目标轮用自己的轮号，不借会话上那把用户轮的把手：借了之后同一 turnId 会落两条 assistant
        // （用户轮一条、目标轮一条），前端按 turnId 去重就分不开。
        String framedTurnId = turnId != null && turnId.startsWith("goal:")
                ? turnId : (handle == null ? turnId : handle.turnId());
        Map<String, Object> tokens = turnUsage.usageOf(session, historyBefore, pool, contextWindow);
        // 收尾这一发是终值（与 done 里的 tokens 同一份快照）；流式期间那几发见 emitLiveUsage，
        // 两拨都出自 usageOf，不做第二套估算
        // 整轮结束而思考段还开着（只思考没出正文、或被叫停）：照样补一条收尾帧，别让转圈永远挂着
        AgentxEmitUtil.closeThinkingFrame(channel, recording);
        // 思考字数进台账：这一族关着思考也照样回 reasoning，单列出来放量时才看得出
        // 「框架引擎多烧的那一截」是思考而不是正文（§29 成本账那条）
        tokens.put("thinkingChars", recording.thinking.length());
        // 整轮耗时随消息行落库：只活在 done 那一帧的话，刷新一次「这一轮跑了多久」就读不出来了——
        // 界面只在气泡顶部报这一个数，落不了库就等于刷新即空白。
        // 两份都要：机器时间对得上看板与台账，等待那份让前端能拼回墙钟（屏幕上跳着的是墙钟，
        // 收尾只报机器时间会让数字在答完那一刻变小），并在悬停里说清等了多久。
        // humanWaitMs 只在真等过的时候写：为 0 时不占键位。
        tokens.put("turnElapsedMs", elapsedMs);
        // 本轮档位随 tokens 落库：排障时不必再翻日志找 [PROFILE] 那一行，也方便后续按档位聚合延迟。
        String profile = sessions.turnSlot(cmd).profile();
        if (profile != null) {
            tokens.put("profile", profile);
        }
        if (humanWaitMs > 0L) tokens.put("humanWaitMs", humanWaitMs);
        // 整轮墙钟也落库：气泡顶部那条报的是「从发出提问到答完」，屏幕上跳的是这一份、刷新后读的
        // 必须还是这一份。只落机器耗时的话，刷新一次数字就变小，
        // 因为排队、限流、会话补建与实例装配那几秒不在 startedAt 之后起表。
        // 所有轮都写：没有请求体的入口 prepMs=0、基准是开轮时刻——照样有「这一轮跑了多久」，
        // 少了这个键，那些轮答完与刷新后就会各说各的（done 走本地钟回退、历史走机器耗时）。
        tokens.put("turnWallMs", wallMs + prepMs);
        tokens.put("turnPrepMs", prepMs);
        // 暗截断对账：判据在 TurnRecorder 那一处，两条运行时共用同一把尺，不复制第二份。
        // 「已交代」必须算上思考字符与工具入参——前者漏了就是轮轮误报（deepseek 系恒思考），
        // 后者漏了会把「写文件动辄几百 token」的正常轮判成内容丢失。
        long finalOutput = tokens.get("finalOutput") instanceof Number n ? n.longValue() : 0;
        // 读原文长度而不是 rec.get("args")：入参落库前已折成预览，读那一份会把写文件的轮次判成内容丢失
        int toolArgChars = (int) Math.min(Integer.MAX_VALUE, recording.toolArgRawChars());
        int accounted = (int) Math.min(Integer.MAX_VALUE,
                (long) body.length() + recording.thinking.length() + toolArgChars);
        if (!stopped && !timedOut && !budget && !stalled && !tokens.isEmpty()
                && com.wenqu.ai.agent.chat.TurnRecorder.looksLikeOutputLost(finalOutput, accounted, true)) {
            pool.registrar().degrade("outputLost", "模型本轮生成 " + finalOutput
                    + " tokens，仅 " + accounted + " 字进入回答，内容疑似丢失");
            body = body + com.wenqu.ai.agent.chat.TurnRecorder.OUTPUT_LOST_SUFFIX;
            log.warn("[FAIL-LOUD] 生成量与正文字数不符（框架引擎）: session={}, 最终轮输出={} tokens, 已交代={} 字"
                            + "（思考 {} 字/工具入参 {} 字）", cmd.getSessionId(), finalOutput, accounted,
                    recording.thinking.length(), toolArgChars);
        }
        if (!tokens.isEmpty()) channel.emit(RunEventType.USAGE, com.alibaba.fastjson2.JSON.toJSONString(tokens));
        // 本轮压过上下文 → 消息行落一个标记：流里那条线只是进度，刷新后「已压缩上下文」分隔线
        // 只有这一个数据源。挂在 usage 帧之后：预览那份只认用量，多一个非用量键会让容量面板读到一本空账。
        if (recording.compactedThisTurn) {
            tokens.put("compacted", 1);
            // 摘要文本也要落库：分隔线上的「查看摘要」按会话读，不落就是压完刷新点进去报「还没有摘要」
            persistCompactionSummary(cmd);
        }
        // 状态行既下发也落库：只随 done 发的话，刷新后「搜索 N 个关键词，参考 M 段资料」整块消失。
        // 无真实检索时 retrievedJson 返回 null——不下发、落库为 null，前端整行不渲染（见 retrievedJson）
        String lastRetrieved = turnUsage.retrievedJson(cmd, pool, recording);
        if (lastRetrieved != null) channel.emit(RunEventType.RETRIEVED, lastRetrieved);
        java.util.List<java.util.Map<String, Object>> artifactItems =
                artifactService.settleArtifacts(cmd.getSessionId());
        // 目标已被叫停的一轮不交付产物：目标取消后那一拍仍可能把在飞工具写出来的产物结算进正文，
        // 用户看到的就是「明明取消了，还是产出了东西」。产物账照常消费，只是不进这一轮交付。
        if (goalService.goalTurnAborted(turnId) && !artifactItems.isEmpty()) {
            log.info("[GOAL] 目标轮已被叫停，丢弃本轮产物交付: session={} turn={} count={}",
                    cmd.getSessionId(), turnId, artifactItems.size());
            artifactItems = List.of();
        }
        String artifactList = com.alibaba.fastjson2.JSON.toJSONString(artifactItems);
        String artifactsJson = "[]".equals(artifactList) ? null : artifactList;
        // 产物是收尾才结算的：先入 blocks 再落库，保证历史时间线里同一份产物不丢
        recording.artifacts(artifactItems);
        // 终态原因入块：刷新/分享与渲染都读这一条结构化事实，不再靠整串文案 endsWith 反推（§1 第 6 行）
        if (!noticeText.isEmpty()) {
            recording.notice(TurnTranscriptRecorder.toneOf(stopped, timedOut, budget, stalled), noticeText);
        }
        // 显示投影只算一次：落库、done、快照读同一份，刷新前后不出现两套答案
        String blocksJson = recording.blocksJson(framedTurnId, body);
        // 游客流只发答案块：工具入参/过程/思考属于运营侧内部数据，分享页也不渲染
        Map<String, Object> viewBlocks = blocksJson == null ? null
                : (cmd.isGuestMode() ? TurnBlocks.answerOnly(blocksJson)
                : com.alibaba.fastjson2.JSON.parseObject(blocksJson));
        // 重新生成的分支手术：先软删被替换的旧回答并登记
        // 版本组，新回答落库后挂同一组，刷新后才有 ‹ n/N › 可切。只在真要落库这一轮时做：
        // 等待/失败轮不落库，旧回答就不该被动。
        String replaceGroup = null;
        if (persist && cmd.getReplaceMessageId() != null && !cmd.getReplaceMessageId().isBlank()) {
            replaceGroup = sessionService.beginAnswerReplace(cmd.getReplaceMessageId());
        }
        // —— 定稿快照：一次组装，落库/done/台账/webhook 全读这一份 ——
        // 此前下游各自重读池的活引用（sources/degradations/images 在落库后又取一遍），
        // 「落库行=done 帧」是靠调用顺序兜着的约定，中间插入任何新逻辑就悄悄分叉；
        // 警示/降级/改动/版本组曾是 append 之后的四条独立 UPDATE，漏跑一条刷新即对不上账。
        // 快照取齐之后，本收尾不再触池。degradations 池给的是活引用（自检要在里面登记），这里定格成副本。
        // 「正文相对流式全文改写过」的事实由 BodyReconcile 一处裁决、随 done 宣告（bodyRewritten），
        // 前端不再拿 finalContent 与本地累积现场比对出第三套判据。
        boolean bodyRewritten = com.wenqu.ai.agentx.turn.BodyReconcile.classify(
                recording.answerText(), body) != com.wenqu.ai.agentx.turn.BodyReconcile.Verdict.UNCHANGED;
        List<Map<String, Object>> snapSources = pool.sources();
        List<Map<String, String>> snapDegrades = new java.util.ArrayList<>(pool.degradations());
        List<String> snapSignedImages = pool.sign(finalImages);
        String snapWarn = channel.lastWarn();
        boolean snapRetrievalAttempted = pool.retrievalAttempted();
        List<String> snapRetrievalKbIds = pool.retrievalKbIds();
        String snapAgentName = agentNameOf(cmd);
        String snapSourcesJson = com.alibaba.fastjson2.JSON.toJSONString(snapSources);
        String snapDegradesJson = snapDegrades.isEmpty() ? null
                : com.alibaba.fastjson2.JSON.toJSONString(snapDegrades);
        String snapChangesJson = turnChanges == null ? null
                : com.alibaba.fastjson2.JSON.toJSONString(turnChanges);
        // 消息落库与问答台账收成一次调用（TurnPersister）：两件事共用同一份定稿快照与落库判据。
        String assistantMessageId = persister.persist(com.wenqu.ai.agent.chat.TurnPersister.Input.builder()
                .sessionId(cmd.getSessionId())
                .body(body)
                .finalImages(finalImages)
                .sourcesJson(snapSourcesJson)
                .thinking(recording.thinkingText())
                .retrieved(lastRetrieved)
                .artifactsJson(artifactsJson)
                .toolCallsJson(recording.toolCallsJson())
                .tokensJson(tokens.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(tokens))
                .timelineJson(recording.timelineJson(body, recording.answerText()))
                .processText(recording.related.process().isEmpty() ? null : recording.related.process())
                .agentId(cmd.getAgentId())
                .agentName(snapAgentName)
                .model(route.modelId())
                .relatedJson(relatedChips.isEmpty() || !relatedSuggestionsEnabled(cmd.getUserId()) ? null
                        : com.alibaba.fastjson2.JSON.toJSONString(relatedChips))
                .planJson(planRecord == null ? null : com.alibaba.fastjson2.JSON.toJSONString(planRecord))
                .turnId(framedTurnId)
                .blocksJson(blocksJson)
                .warn(snapWarn)
                .degradationsJson(snapDegradesJson)
                .changesJson(snapChangesJson)
                .variantGroup(replaceGroup)
                .persist(persist)
                .realAnswer(realAnswer)
                .question(cmd.getQuestion())
                .hitDocIds(snapSources.stream().map(x -> String.valueOf(x.get("docId"))).toList())
                .hasCitation(!snapSources.isEmpty())
                .retrievalAttempted(snapRetrievalAttempted || !snapSources.isEmpty())
                .elapsedMs(elapsedMs)
                .deepThink(cmd.isDeepThink())
                .kbIds(snapRetrievalKbIds)
                .build());
        // 载荷用 JSONObject 序列化，不手工拼字符串：控制字符与非法转义序列会让整包失去合法性，
        // 本轮来源清单与外发文本会一起丢掉，「答案可溯源」在外部链路静默失效。
        // 键序与拼接版逐字一致（下游有多个消费方按名取字段，含前端与子轮回取）。
        // WriteMapNullValue 保持「显式给 null」的语义（缺键会让前端读不到）。
        List<Map<String, Object>> pendingSteers = awaiting ? null : AgentxPlanSteerUtil.unsentSteers(turnId, recording);
        if (pendingSteers != null && pendingSteers.isEmpty()) pendingSteers = null;
        String payload = TurnDonePayload.toJson(TurnDonePayload.Input.builder()
                .finalContent(body)
                .messageId(assistantMessageId)
                .userMessageId(userMessageId)
                .turnId(framedTurnId)
                .model(route.modelId())
                .agentId(cmd.getAgentId())
                .agentName(snapAgentName)
                // 全部读定稿快照：done 与刚落库的消息行必须逐字段同一份，不存在第二池次读取窗口
                .sources(snapSources)
                // 定稿配图清单（与 finalContent 的 [图片N] 编号严格同序）：与流式 image 帧同源、
                // 与落库同一份快照，定稿不再改写清单，故不会「答完图消失」
                .finalImages(snapSignedImages)
                .degradations(snapDegrades)
                // 「正文相对流式全文改写过」的权威宣告（BodyReconcile 一处裁决）：
                // 前端据此决定时间线 text 段失效与否，不再现场比对出第三套判据
                .bodyRewritten(bodyRewritten)
                .elapsedMs(elapsedMs)
                .humanWaitMs(humanWaitMs)
                // 整轮墙钟与准备段：与落库 tokens.turnWallMs/turnPrepMs 同一对变量，答完与刷新必须逐毫秒相等
                .turnWallMs(wallMs + prepMs)
                .prepMs(prepMs)
                .tokens(tokens)
                .toolCalls(recording.toolCallList())
                // 显示块：新前端据此渲染；旧前端继续读 finalContent/timeline
                .blocks(viewBlocks)
                .changes(turnChanges)
                .artifacts(artifactItems)
                .related(relatedChips)
                .thinking(recording.thinking.length() == 0 ? null : recording.thinking.toString())
                .planRecord(planRecord)
                // 说过但模型没收到的话必须交回前端补发，不许静默丢话
                .pendingSteers(pendingSteers)
                .build());
        channel.emit(RunEventType.DONE, payload);
        // pendingSteers 已经取完，收据到此作废：不收的话按轮攒着，长会话会一直涨
        com.wenqu.ai.agent.chat.SteerReceipts.clear(turnId);
        // 智能体轮终 webhook 出站（对外能力第二批，旁路）：配置了 callback_url 的智能体把本轮结果
        // 签名推给宿主系统。挂在 done 发射之后——宿主收到的时机与屏幕上「答完了」一致。
        // 状态口径：停/stall=stopped（用户侧行为，宿主同样该知道）、超时/预算耗尽=failed、
        // 其余=completed；卡片还开着等人答的轮不算终态，不发。
        if (!awaiting) {
            String webhookStatus = com.wenqu.ai.agent.chat.TurnCloseout.webhookStatus(
                    stopped, stalled, timedOut, budget);
            agentWebhookService.onTurnFinished(cmd, framedTurnId, webhookStatus, null,
                    body, assistantMessageId, tokens, artifactItems, elapsedMs);
        }
        // 目标轮收尾顺手收掉用户机器上的 headed 浏览器：它是桌面上真实存在的窗口，
        // 不收就一直挂着（执行器只有 5 分钟空闲兜底），用户体感就是「关不掉」。
        if (turnId != null && turnId.startsWith("goal:")) {
            tools.closeLocalBrowser(cmd.getUserId());
        }
        // done 之后必须收通道：手写侧是 runChat 自己 completeEmitter；不收的话帧都到了、
        // 连接却挂着直到看门狗 300s 超时截断，前端「这一轮结束」要等五分钟才落地
        channel.complete();
        // 收台：成员页手动发起的轮不经过 TeamWakeService，那里收不到尾——不收的话直播台永远
        // done=false，前端每次进页面都拿到一份「还在跑」的旧快照并反复重连（标题一直显示「重连中」）。
        // 挂在 finish 里而不是各调用方：轮结束的路径有好几条（正常/停止/超时/失败），
        // 只有引擎这一处是必经之路。
        if (memberLiveFeed != null && cmd.getSessionId() != null && !cmd.getSessionId().isBlank()) {
            try {
                memberLiveFeed.finish(cmd.getSessionId(), "这一轮已结束");
            } catch (RuntimeException e) {
                log.debug("[TEAM] 直播收台失败（不影响本轮）session={}: {}", cmd.getSessionId(), e.toString());
            }
        }
    }



    /** 本轮的 turnId（轮级互斥外壳登记的）；外壳没登记时退回会话号，只为留痕不悬空。 */
    private String turnIdOf(ChatCommand cmd) {
        com.wenqu.ai.agent.chat.TurnHandle handle =
                com.wenqu.ai.agent.chat.TurnRegistry.handle(cmd.getSessionId());
        return handle == null ? cmd.getSessionId() : handle.turnId();
    }



    /**
     * 把框架那份滚动摘要落进会话行（{@code c_ai_session.history_summary}）。
     * <p>压缩交回 AgentScope 之后摘要只活在框架的<b>会话状态</b>里：{@code CompactionMiddleware}
     * 把它写进 {@code AgentState.contextMutable()} 首位，而框架原生日志没有这条消息
     * （{@code transcript()} 只回放 user/assistant/tool 三类事件），从 transcript 头一条找不到它。
     * <p>读不到就一行不写——宁可让面板报空，也不落一份来历不明的摘要。
     */
    private void persistCompactionSummary(ChatCommand cmd) {
        FrameworkSessions.CompactionSummary live =
                sessions.liveCompactionSummary(cmd.getUserId(), cmd.getSessionId());
        if (live == null || live.text().isBlank()) {
            log.warn("[FAIL-LOUD] 本轮压过上下文但摘要没落库（框架活状态里认不出摘要消息）: session={}",
                    cmd.getSessionId());
            return;
        }
        sessionService.updateHistorySummary(cmd.getSessionId(), live.text(), live.coveredMessages());
    }






    /**
     * 提问卡载荷：前端认 {@code {askId, questions, timeoutMs}}。挂起原因里带的是
     * {@code AskQuestion} 列表的 JSON，原样转过去；解不开就退成一题纯文本，
     * 让问题至少能到用户眼前（不能因为格式就吞掉）。
     * <p>{@code timeoutMs} 必须与 {@link #pendingBudgetMs} 用的那份配置同源：前端倒计时读它，
     * 而真正决定这张卡什么时候作废的是后端那个循环。两处不同源的表现是「界面还在数 8 分钟、
     * 后端两分钟就把这一轮收了」——用户对着一个已经作废的输入框作答，提交后只得到一句失败提示。
     */
    /**
     * 提问卡<b>第一题的题面</b>（站内通知正文用）。
     * <p>形状与 {@link #askCard} 认的那一份同源：一卡多问取 {@code questions[0].question}，单题取
     * {@code question}，两者都不是（模型直接给了一段文本）就把那段当题面。取不到就交回空串——
     * 通知少一句正文，比拼出一句错话好。
     */
    static String firstAskText(Object input) {
        if (!(input instanceof Map<?, ?> in)) return input == null ? "" : String.valueOf(input).strip();
        Object qs = in.get("questions");
        if (qs instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> q) return str(q.get("question"));
            return String.valueOf(first).strip();
        }
        return str(in.get("question"));
    }




    /**
     * 工具卡载荷（{@code tool_status} 实时帧与落库那份同源，字段形状一致）。
     *
     * <p><b>展示名从 {@link ToolDisplayNames} 取，不写裸名</b>：把工具名直接塞进 {@code label} 会让前端
     * {@code toolLabel} 认出「label 就是名字」而回落那份手抄映射，表里没有的 {@code consultAgent}、
     * {@code plan_*} 就现出英文裸名。取不到展示名时<b>不写该字段</b>
     * （前端映射与「后端确实没给名字」两种情况在界面上同形，但载荷里能分辨）。
     *
     * <p>{@code startedAtMs} 用<b>服务端</b>墙上时刻：并发派发时各张卡的起止只有落在同一个钟上才减得出
     * 「从第一个操作到最后一个操作」的真实跨度；拿前端收到帧的本地时刻去减，接流/后台节流就会把跨度算歪。
     */










}
