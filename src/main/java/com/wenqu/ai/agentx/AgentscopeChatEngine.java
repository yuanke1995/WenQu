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
    /** 本轮智能体绑定解析与实例指纹 */
    private final AgentxAgentBinding agentBinding;
    /** 收尾待办卡收口 */
    private final AgentxPendingAbandoner pendingAbandoner;
    /** 待办卡宣布（计划/提问/审批三种卡） */
    private final AgentxPendingAnnouncer pendingAnnouncer;
    /** 接流快照构建 */
    private final AgentxTurnSnapshot turnSnapshotBuilder;
    /** 一轮里的通用小动作：问题落库/提问超时回送/挂载工具名/技能命中帧 */
    private final AgentxTurnSupport turnSupport;
    /** 框架基础设施单例：Redis / 会话状态存储 / distributed store / 模型传输 */
    private final AgentxFrameworkInfra infra;
    /** system prompt 上下文装配 */
    private final AgentxPromptContext promptCtx;
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
    /** 一轮的收尾：落库、done 帧、台账、目标终拍、webhook */
    private AgentxTurnFinisher finisher;
    /** 跟着一轮跑到收尾 */
    private AgentxFollowRunner followRunner;
    /** 一轮跟随的结算（正文回填/失败判定/目标终拍/落库通知） */
    private AgentxTurnSettler turnSettler;
    /** 一轮的落库与下发（快照/消息行/done 帧/webhook） */
    private AgentxTurnPersister turnPersister;
    /** 跟随循环的驱动 */
    private AgentxLoopRunner loopRunner;
    /** 按游标读增量事件并翻译 */
    private AgentxLogDrain logDrain;
    /** 为一轮续跑装配上下文 */
    private AgentxContinuationOpener continuationOpener;
    /** 起续跑跟随（单线程池 + 收尾释放） */
    private AgentxResumeStarter resumeStarter;
    /** 目标轮续跑 */
    private AgentxGoalRunner goalRunner;
    /** 重启接续与补跟 */
    private AgentxResumeHandler resumeHandler;
    /** 建实例脚手架（状态目录/运行时上下文/工具装配） */
    private AgentxAgentScaffold agentScaffold;
    /** 建实例期的模型构建（主链/辅助链两份） */
    private AgentxModelFactory modelFactory;
    /** 建实例收尾（记忆闸/总线/子智能体/团队/护栏） */
    private AgentxAgentFinalize agentFinalize;
    /** 建实例前的准备（三闸/模型/助手清单/记忆快照） */
    private AgentxBuildPrep buildPrep;
    /** 建实例的 builder 链 */
    private AgentxAgentBuilder agentBuilderFactory;
    private AgentxChatPrologue chatPrologue;
    private AgentxImageIntake imageIntake;
    private AgentxTurnPrelude turnPrelude;
    private AgentxTurnSlotBinder slotBinder;
    private AgentxSessionOpener sessionOpener;
    private AgentxTurnContext turnContext;
    private AgentxTurnSubmit turnSubmit;
    private AgentxTurnLauncher turnLauncher;
    private AgentxSessionWarmup sessionWarmup;
    private AgentxTurnRecordingFactory recordingFactory;
    private AgentxTurnGateReset gateReset;
    private AgentxTurnInput turnInput;
    private AgentxFilesystemResolver fsResolver;
    private AgentxToolGuard toolGuard;
    private AgentxFailLoud failLoudHandler;
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
    /** 多副本协调（StoreBackedPeriodicGate + consolidation watermark）：与 stateStore 复用同一个 Jedis。 */
    /**
     * 引擎级共享的模型 HTTP 通道（OkHttp）：连接池跨会话、跨实例常驻。
     * <p>每个会话实例各建一个 transport 就等于各一份连接池：轮与轮之间连接虽能复用，
     * 但<b>池与池之间不互通</b>，空闲连接也没有保活——网关侧把空闲连接掐掉后，
     * 下一轮第一笔请求都要重新 TCP+TLS（冷连接首包比热连接多约 0.5s，是稳态问答「模型段」
     * 的最大单一开销）。共享实例 + HTTP/2 pingInterval
     * 让活跃供应商的连接始终保持热：ping 对 HTTP/2 连接生效（防网关闲除断连），
     * 对 HTTP/1.1 连接无感，openai 兼容网关两种形态都覆盖。
     */

    /**
     * 插话待发队列的信号口（装配期由 {@code AgentxConfiguration} 登记）。
     * <p>引擎只在「框架确实开跑了」（{@code inbox/opened}）时唤醒它：早一步 steer 必被拒，
     * 队列就会白等一整轮；晚一步就错过「下一次调模型前」这个唯一送达点。
     */
    private volatile com.wenqu.ai.agent.chat.SteerQueueHooks steerQueueHooks;

    public void bindSteerQueue(com.wenqu.ai.agent.chat.SteerQueueHooks hooks) {
        this.steerQueueHooks = hooks;
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
        this.logDrain = new AgentxLogDrain(eventTranslator);
        this.sandboxIntent = new AgentxSandboxIntent(sessions, sandboxService, projectService);
        this.agentBinding = new AgentxAgentBinding(tools, orgService, userConfigService, modelRegistry);
        this.pendingAbandoner = new AgentxPendingAbandoner(sessions, toolCardBuilder);
        this.pendingAnnouncer = new AgentxPendingAnnouncer(sessions, askCardBuilder, configService, displayNames, turnNotify, this::workspaceRootsOf);
        this.infra = new AgentxFrameworkInfra(configService, redisProperties);
        this.promptCtx = new AgentxPromptContext(properties, projectFileService, projectService, tools,
                configService, sessionService, cmd -> teamActive(cmd));
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
        this.turnSnapshotBuilder = new AgentxTurnSnapshot(agentBinding, artifactService, planCardSnapshot);
        this.turnSupport = new AgentxTurnSupport(attachments, sessionService, sessions, tools, skillStatsStore, configService);
        this.turnPersister = new AgentxTurnPersister(turnChangeService, turnUsage, persister, artifactService,
                goalService, agentBinding, agentWebhookService, sessions, sessionService, tools, () -> memberLiveFeed);
        this.finisher = new AgentxTurnFinisher(turnChangeService, citations, turnUsage, goalService, agentBinding,
                sessions, turnPersister);
        this.recordingFactory = new AgentxTurnRecordingFactory(tools, displayNames, this::bindFrameworkToolGate);
        this.continuationOpener = new AgentxContinuationOpener(
                new AgentxContinuationPrep(sessions, tools, modelRegistry, new AgentxResumeCommand(promptCtx, tools),
                        agentBinding, sandboxIntent, modelResolver,
                        c2 -> sandboxService.resolveWorkspace(c2.getSessionId(), c2.getUserId(), true),
                        consultService),
                new AgentxContinuationSession(sessions, tools, goalService, sessionGateway),
                recordingFactory, tools,
                (c2, pol, rt, hk, bd, sl, sb, ews) -> buildAgent(c2, pol, rt, hk, bd, sl, sb, ews));
        this.turnSettler = new AgentxTurnSettler(finisher, sessions, goalService, turnNotify, this::maxItersForTurn, pendingAbandoner);
        this.loopRunner = new AgentxLoopRunner(sessions, tools, goalService, turnUsage, pendingAnnouncer, turnSupport,
                turnSnapshotBuilder, logDrain, () -> pendingBudgetMs(PendingKind.ASK), this::turnSeconds);
        this.followRunner = new AgentxFollowRunner(sessions, tools, goalService, turnNotify, turnUsage,
                pendingAnnouncer, pendingAbandoner, turnSupport, turnSnapshotBuilder, logDrain, finisher, turnSettler,
                () -> pendingBudgetMs(PendingKind.ASK), this::turnSeconds, this::maxItersForTurn, loopRunner);
        this.resumeStarter = new AgentxResumeStarter(sessions, tools, goalService, backgroundGoalTurns, RESUME_POOL,
                (sess, c2, ch, pl, rt, hk, tid, hb, sc, um, cur, rec, sa, pm, cw, rc) ->
                        followTurn(sess, c2, ch, pl, rt, hk, tid, hb, sc, um, cur, rec, sa, pm, cw, rc));
        this.resumeHandler = new AgentxResumeHandler(sessions, continuationOpener, resumeStarter);
        this.goalRunner = new AgentxGoalRunner(sessions, goalService, chatRunBus, promptCtx, GOAL_RETRY,
                continuationOpener, resumeStarter, backgroundGoalTurns, GOAL_KICK);
        this.imageIntake = new AgentxImageIntake(modelRegistry, userImages);
        this.turnPrelude = new AgentxTurnPrelude(tools, goalService, turnSupport);
        this.slotBinder = new AgentxTurnSlotBinder(tools, sessions, turnChangeService);
        this.sessionOpener = new AgentxSessionOpener(sessions, tools, sandboxService, sandboxIntent, agentBinding);
        this.sessionWarmup = new AgentxSessionWarmup(journalRotation, sessionGateway);
        this.gateReset = new AgentxTurnGateReset(sessions);
        this.turnInput = new AgentxTurnInput(goalService, promptAssembler, c -> tools.agentMentionDirective(c, tools.policyFor(c)));
        this.turnContext = new AgentxTurnContext(recordingFactory, turnInput, sessions, tools, gateReset, sessionWarmup);
        this.turnSubmit = new AgentxTurnSubmit(sessions, tools);
        this.fsResolver = new AgentxFilesystemResolver(sessions, sandboxService, projectService, promptCtx);
        this.toolGuard = new AgentxToolGuard(tools, modelRegistry);
        this.chatPrologue = new AgentxChatPrologue(modelRegistry, tools, goalService, backgroundGoalTurns,
                modelResolver, this::isTeamMemberSession, () -> memberLiveFeed);
        this.failLoudHandler = new AgentxFailLoud(tools, turnSupport, userImages, turnNotify,
                agentWebhookService, this::turnIdOf);
        this.agentScaffold = new AgentxAgentScaffold(configService, tools, sessions, goalService, sessionService);
        this.modelFactory = new AgentxModelFactory(modelConfig, modelRegistry, usageLedger, quotaService, sessions);
        this.buildPrep = new AgentxBuildPrep(sessions, tools, modelFactory, promptCtx, infra);
        this.agentBuilderFactory = new AgentxAgentBuilder(modelConfig, infra, tools, agentBinding, turnSupport,
                skillRepository, artifactService, promptCtx, this::turnSeconds, this::maxItersForTurn, new AgentxMiddlewareSet(), new AgentxModelWiring(modelConfig, this::turnSeconds, this::maxItersForTurn));
        this.agentFinalize = new AgentxAgentFinalize(new AgentxMemoryGate(modelConfig, projectService), new AgentxTeamMode(), infra, tools, agentBinding, new AgentxToolFinisher(toolGuard, this::workspaceRootsOf));
        this.turnLauncher = new AgentxTurnLauncher(sessions, goalService, turnSupport,
                (sess, c2, ch, pl, rt, hk, tid, hb, sc, um, cur, rec, sa, pm, cw, rc) ->
                        followTurn(sess, c2, ch, pl, rt, hk, tid, hb, sc, um, cur, rec, sa, pm, cw, rc));
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
        infra.stateStore();
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

    @Override
    public String name() {
        return "agentscope";
    }

    @Override
    public void chat(ChatCommand cmd, RunChannel channel) {
        AgentxChatPrologue.Prologue prologue = chatPrologue.begin(cmd, channel, this::failLoud);
        RunChannel turnChannel = prologue.channel();
        String ref = prologue.ref();
        if (ref == null) return;
        ModelRegistryService.ModelRoute route = prologue.route();
        ExecutionPolicy policy = prologue.policy();
        WenquSourcePool pool = tools.newPool(cmd, turnChannel.emitter());
        // 本轮改过用户存过的选择必须说出来；只登记降级不另发 WARN（否则同一原因上下各一条警示条）
        if (prologue.fallbackNotice() != null) {
            pool.registrar().degrade("modelFallback", prologue.fallbackNotice());
        }

        // 本轮身份（轮级互斥外壳已经登记好 turnId）：待办落库、任务清单留痕、步数预算归零都认它
        String turnId = turnIdOf(cmd);
        // 本轮生效策略一行看全（description 里已含档位/沙盒/审批/约束/可写根/禁用）
        log.info("[PERM] session={} turn={} source={} depth={} agent={} {}",
                cmd.getSessionId(), turnId, cmd.getSource(), cmd.getSubagentDepth(), cmd.getAgentId(),
                policy.describe());

        // 用户图片：落盘换 URL 并决定「原图要不要进模型」（判据与说明见 AgentxImageIntake）
        AgentxImageIntake.Intake intake = imageIntake.process(cmd, ref, pool);
        boolean chatSeesImages = intake.chatSeesImages();
        List<com.wenqu.ai.service.UserImageService.UserImage> userImgs = intake.images();
        List<String> userImgUrls = intake.urls();
        String imgNote = intake.imgNote();

        // 问题先落库（必须早于 sessions.open，否则中途停止/刷新时会话被当成空会话隐藏）
        AgentxTurnPrelude.Result prelude = turnPrelude.prepare(cmd, channel, turnId, userImgUrls);
        if (prelude.stopped()) return;
        String userMessageId = prelude.userMessageId();

        AgentxTurnSlotBinder.Bound bound = slotBinder.bind(cmd, turnChannel, pool, policy, ref, turnId,
                () -> consultService.newLedger());
        AgentxTurnSlot slot = bound.slot();
        WenquAgentxTools.TurnHooks hooks = bound.hooks();
        com.wenqu.ai.service.ProjectTurnChangesService.Start changes = bound.changes();
        // 智能体绑定一次解析：实例重建判据、人设、技能白名单三处共用同一份
        AgentxSessionOpener.Opened opened = sessionOpener.open(cmd, ref, route, policy, hooks, slot, turnId,
                pool, turnChannel, (c2, pol, rt, hk, bd, sl, sb, ews) -> buildAgent(c2, pol, rt, hk, bd, sl, sb, ews));
        AgentSession session = opened.session();
        AgentBinding binding = opened.binding();
        com.wenqu.ai.service.EffectiveWorkspace ew = opened.ew();
        sessionWarmup.warm(cmd, session, sessions::abandoned);
        gateReset.reset(cmd, turnId, tools, pool, turnChannel);
        AgentxTurnContext.Assembled assembled = turnContext.assemble(cmd, session, turnChannel, hooks,
                bound.spills(), binding, turnId, imgNote, chatSeesImages, userImgs, pool,
                () -> turnChangeService.driftNote(cmd.getSessionId(), changes, DRIFT_NOTE_WAIT_MS));
        AgentxTurnRecording recording = assembled.recording();
        int historyBefore = assembled.historyBefore();
        long startCursor = assembled.startCursor();
        String modelInput = assembled.modelInput();
        io.agentscope.core.message.Msg multimodal = assembled.multimodal();
        slot.noteGoalActive(assembled.goalActive());
        AgentSession.Task current = turnSubmit.submit(cmd, session, turnChannel, policy, turnId, modelInput, multimodal);
        turnLauncher.launch(session, cmd, turnChannel, pool, route, hooks, turnId, historyBefore, startCursor,
                userMessageId, current, recording, slot, binding.contextWindow());
    }

    private void followTurn(AgentSession session, ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                            ModelRegistryService.ModelRoute route, WenquAgentxTools.TurnHooks hooks,
                            String turnId, int historyBefore, long startCursor, String userMessageId,
                            AgentSession.Task current,
                            AgentxTurnRecording recording, long startedAt, long prepMs, int contextWindow,
                            boolean recovered) {
        followRunner.followTurn(new AgentxTurnRun(session, cmd, channel, pool, route, hooks, turnId, historyBefore,
                startCursor, userMessageId, current, recording, startedAt, prepMs, contextWindow, recovered));
    }

    /** 重启后续跑的执行线程：单线程串行——同一条会话不该被两轮同时驱动。 */
    private static final java.util.concurrent.ExecutorService RESUME_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "agentx-resume");
                t.setDaemon(true);
                return t;
            });

    /** 续跑所需的一整套本轮上下文（重启接回与「答完没人跟」两条路共用同一段装配）。 */

    /**
     * 为一轮续跑装配出本轮上下文：按归属重建（或复用）常驻会话实例、建通道、建引用池与本轮身份。
     * <p>通道挂在收集型 emitter 上——这一跑未必有真实观众，但事件仍走同一条 {@code sendSseEvent}
     * （账本、运行总线、落库都不变），断线重连那一份 HTTP 响应才是观众。
     *
     * @return null = 装配不动（模型解析不出来、实例建不起来），调用方按「接不动」处理
     */
    private AgentxFollowing openForContinuation(String sessionId, String userId, String turnId) {
        return openForContinuation(sessionId, userId, turnId, false);
    }

    /**
     * @param recover 这是不是「进程中断后的恢复轮」：恢复轮要先封存上一进程残留的会话写者、
     *                把未决工具结果标成未知，再归档遗留轮；否则框架的 inbox 会一直等那个
     *                已经不存在的 writer（2 分钟租约），表现为「目标卡在 EXECUTING 不动」。
     */
    private AgentxFollowing openForContinuation(String sessionId, String userId, String turnId, boolean recover) {
        return openForContinuation(sessionId, userId, turnId, recover, null, false);
    }

    /** 深度执行第 1 轮先出计划：与手动计划模式共用同一道 plan 门禁，批准后才继续执行。 */
    private AgentxFollowing openForContinuation(String sessionId, String userId, String turnId, boolean recover,
                                          String guestUid) {
        return openForContinuation(sessionId, userId, turnId, recover, guestUid, false);
    }

    /**
     * @param guestUid 非空=这一轮原本是<b>免登录分享会话</b>里的一轮：按访客口径挂工具面（沙盒/联网/MCP
     *                 一概不给），身份仍是发布者。少传这一项，接续出来的那一轮会比原来那一轮宽——
     *                 「访客接不回」和「访客拿到更多能力」都在这一个参数上。
     */
    private AgentxFollowing openForContinuation(String sessionId, String userId, String turnId, boolean recover,
                                          String guestUid, boolean planFirst) {
        return openForContinuation(sessionId, userId, turnId, recover, guestUid, planFirst, null);
    }

    /**
     * @param agentOverride 指定本轮作答方（目标续跑轮传目标行上的智能体）。空白=沿用会话列的解析。
     *                      <p>为什么目标轮必须显式带：会话列在「用户在问答页直接说话」的会话里
     *                      通常是空的（当轮才解析默认智能体），续跑轮只读会话列就会拿不到作答方，
     *                      权限档位随之掉回平台默认（用户轮 auto、目标轮逐次确认）。
     */
    private AgentxFollowing openForContinuation(String sessionId, String userId, String turnId, boolean recover,
                                          String guestUid, boolean planFirst, String agentOverride) {
        return continuationOpener.open(sessionId, userId, turnId, recover, guestUid, planFirst, agentOverride);
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
        return fsResolver.resolve(cmd, unrestrictedWrites, sandboxEnabled, ew);
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
    private void startFollowing(AgentxFollowing f, AgentSession.Task current) {
        startFollowing(f, current, () -> {
        });
    }

    /**
     * 同上，外加一条「这一跑没起来 / 已经跑完」的通知。
     * <p>后台目标轮要把自己登记在运行总线上，而总线靠 {@code turnAlive} 判生死：没有这个回调，
     * 抢位失败那一支（下面直接 return）会把登记留在总线上，前端探测到一条永远不会结束的「在跑」。
     */
    private void startFollowing(AgentxFollowing f, AgentSession.Task current, Runnable onSettled) {
        startFollowing(f, current, onSettled, false);
    }

    /**
     * @param alreadyReserved 调用方已经抢到跟随位（目标续跑先抢位再 submit）：这里不再抢，
     *                        失败时由调用方释放。不先抢位就 submit 的后果是——上一轮还在收尾时
     *                        submit 出去的那一轮没有人跟，事件不落库、目标轮数也白涨。
     */
    private void startFollowing(AgentxFollowing f, AgentSession.Task current, Runnable onSettled,
                                boolean alreadyReserved) {
        resumeStarter.start(f, current, onSettled, alreadyReserved);
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

    public void submitGoalTurn(com.wenqu.ai.model.Goal goal, String input, int n) {
        goalRunner.submit(goal, input, n);
    }

    private void continueGoalTurn(com.wenqu.ai.model.Goal goal, String input, int n, int attempt) {
        goalRunner.continueTurn(goal, input, n, attempt);
    }

    private java.util.concurrent.atomic.AtomicBoolean openGoalTurnRun(AgentxFollowing f, String sessionId,
                                                                      String uid, String turnId) {
        return goalRunner.openTurnRun(f, sessionId, uid, turnId);
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
        return resumeHandler.resume(sessionId, userId, turnId, requestId, kind, approved, reason, answer);
    }

    @Override
    public void follow(String sessionId, String userId, String turnId, boolean recover, String guestUid) {
        resumeHandler.follow(sessionId, userId, turnId, recover, guestUid);
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
        failLoudHandler.fail(cmd, channel, message);
    }

    /**
     * 失败轮的那一问。图片走 {@code processDisplay}（只落盘回显）：本轮压根没进模型，
     * 谈不上「这个模型读不读得懂图」，但用户贴的图该跟着他的问题一起留在对话里。
     */
    private void appendQuestionOnFailure(ChatCommand cmd) {
        if (cmd.isRegenerate() || cmd.isFromSteer()) return;   // 提问在上一句请求里已经落过
        if (cmd.getQuestion() == null || cmd.getQuestion().isBlank()) return;
        try {
            turnSupport.appendQuestion(cmd, turnIdOf(cmd), userImages.processDisplay(cmd.getUserImages()).stream()
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
        return logDrain.drain(session, cursor, emitFrom, turnId, channel, recording, mirror);
    }

    /**
     * 审批卡上的「为什么问」：与执行期守卫同一份判据——本机文件/命令工具由污点护栏接管
     * （读过外部内容后遇到删除/覆盖/外发/越界写），其余工具按本轮审批档要求确认。
     * 空串 = 没有可说清的理由，卡片沿用默认提示。
     * <p>不在这里再判一次「像不像写操作」：工具性质在登记处，强制确认在 {@link AgentxNativeToolGuard}。
     */
    @SuppressWarnings("unchecked")

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
        AgentxAgentScaffold.Scaffold scaffold = agentScaffold.build(cmd, policy, slot, sandboxEnabled,
                () -> teamActive(cmd), teamGateway);
        Path stateDir = scaffold.stateDir();
        RuntimeContext ctx = scaffold.ctx();
        io.agentscope.core.tool.Toolkit toolkit = scaffold.toolkit();
        AgentxBuildPrep.Prepared prep = buildPrep.prepare(cmd, policy, route, binding, hooks, slot);
        AgentxStepBudget stepBudget = prep.stepBudget();
        AgentxRepeatGuard repeatGuard = prep.repeatGuard();
        AgentxFrameworkToolGate toolGate = prep.toolGate();
        io.agentscope.core.model.Model chatModel = prep.chatModel();
        io.agentscope.core.model.Model auxModel = prep.auxModel();
        List<WenquAgentxTools.SubagentTarget> subagentTargets = prep.subagentTargets();
        String workspaceMemory = prep.workspaceMemory();
        String userMemoryReadonly = prep.userMemoryReadonly();
        // 本轮的沙盒/工作区文件系统：build 期算一次，既喂给 builder 的 abstractFilesystem，
        // 也给读图工具（read_image）复用同一份——两处若各算一次，可能拿到不同后端、读写与读图指向不同盘。
        io.agentscope.harness.agent.filesystem.AbstractFilesystem frameworkFs = frameworkFilesystem(cmd,
                policy.sandboxMode() == com.wenqu.ai.agent.permission.SandboxMode.DANGER_FULL_ACCESS,
                sandboxEnabled, ew);
        HarnessAgent.Builder agentBuilder = agentBuilderFactory.builder(new AgentxBuildInput(
                cmd, policy, route, hooks, binding, slot, stateDir, toolkit, chatModel, auxModel,
                subagentTargets, stepBudget, repeatGuard, toolGate, frameworkFs,
                workspaceMemory, userMemoryReadonly, localExecutorEnvironment(cmd, ew),
                promptCtx.teamGuidance(cmd)));

        return agentFinalize.finalize(new AgentxAgentFinalize.Input(agentBuilder, cmd, route, binding, hooks, slot,
                auxModel, frameworkFs, subagentTargets, teamActive(cmd), teamBus, teamGateway, ew));
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
        toolGuard.apply(agent, slot, cmd, workspaceRootsOf(cmd, ew));
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
        toolGuard.registerReadImage(agent, hooks, fs);
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

    private void finish(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                        ModelRegistryService.ModelRoute route, String modelRef, int contextWindow, String answer,
                        AgentxTurnRecording recording,
                        String userMessageId, boolean stopped, boolean timedOut, boolean budget, boolean stalled,
                        boolean awaiting,
                        long startedAtMs, long humanWaitMs, long prepMs,
                        AgentSession session, int historyBefore, String turnId,
                        Map<String, Object> planRecord, String recoveredNote) {
        finisher.finish(cmd, channel, pool, route, modelRef, contextWindow, answer, recording, userMessageId,
                stopped, timedOut, budget, stalled, awaiting, startedAtMs, humanWaitMs, prepMs,
                session, historyBefore, turnId, planRecord, recoveredNote);
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

}
