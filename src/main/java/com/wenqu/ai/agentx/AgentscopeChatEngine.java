package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.RelatedPrompt;
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
     * 聊天档/工作区档的意图词。命中就走工作区档（建沙盒、全量工具），否则走聊天档
     * （宿主临时目录 + 文件工具，无容器、无 shell）。
     *
     * <p>这一版刻意「宁可多判工作区」：误判工作区只是多付一次 3 秒装配，误判聊天会让
     * 「跑/构建/改文件」这类请求失去能力。纯问答（例如 {@code 1+1=?}）不含这些词，直接走聊天档。
     * 后续可按 shadow 日志收敛词表，或换成显式 UI 选择。
     */
    private static final Pattern SANDBOX_INTENT_EN = Pattern.compile(
            "(?i)\\b(file|folder|directory|project|code|script|run|execute|build|compile|install|deploy|debug|terminal|command|read|write|save|edit|export|package|zip|excel|csv|json|python|node|npm|bash|shell|git|docker)\\b");
    private static final Pattern SANDBOX_INTENT_EXT = Pattern.compile(
            "(?i)\\.(py|js|ts|java|html|css|json|csv|md|xlsx|zip|sh|sql)\\b");
    private static final String[] SANDBOX_INTENT_ZH = {
            "文件", "目录", "文件夹", "项目", "代码", "脚本", "运行", "执行", "构建", "编译",
            "安装", "部署", "调试", "终端", "命令行", "读取", "写入", "保存", "编辑", "修改",
            "导出", "打包", "压缩", "解压", "表格", "数据库"
    };

    /** 推荐块里条目长过这个字数就按正文处理（与手写侧 {@code RELATED_PROSE_MIN_CHARS} 同值同理由）。 */
    private static final int RELATED_PROSE_MIN_CHARS = 80;

    /** 条数闸的余量：配置要 N 条，模型多给一两条还算清单，几十条那种整块文档才判成正文。 */
    private static final int RELATED_CHIPS_TOLERANCE = 2;

    /**
     * 会挂「子对话」卡的框架派遣工具名。只认 {@code agent_spawn}：它的入参带 {@code agent_id}
     * （逐字=问渠的智能体 id），能与本轮台账对上；{@code agent_send} 的入参只有 {@code agent_key}/
     * {@code label}，认不出是哪一位，宁可不挂也不把上一支的子对话错配到这一张卡上。
     */
    private static final java.util.Set<String> CONSULT_CARD_TOOLS = java.util.Set.of("agent_spawn");

    /**
     * 早产工具卡：模型一边流式生成工具入参，界面一边就能看到「这一步在跑」。
     * <p>框架只在 assistant 消息完整后才写 {@code tool/requested}：长入参生成期间前端一个工具都没有，
     * 界面就是一段纯静默。{@code model/chunk} 里带 {@code tool_use} 增量块（首块有 name/id，后续块是参数片段），
     * 据此提前发一张 {@code tool_status start} 卡即可把这段静默变成可见的进行中。
     * <p>短入参的检索类调用不值得闪一下：累计到这个字数、或生成超过这个毫秒数才发第一帧。
     */
    private static final int PREPARING_MIN_CHARS = 500;
    private static final long PREPARING_MIN_MS = 600L;
    /** 早产卡进度帧的最小间隔（字数只用于行尾摘要，不必逐块刷）。 */
    private static final long PREPARING_EVERY_MS = 1500L;
    /** 框架 OpenAI 适配层给「同一次调用的后续参数片段」占位的工具名（OpenAIResponseParser.FRAGMENT_PLACEHOLDER）。 */
    private static final String PREPARING_FRAGMENT_NAME = "__fragment__";
    /** 从入参前缀里捞一个能给用户看的摘要（文件名/路径/检索词/命令）；值还没生成完整时不命中。 */
    private static final Pattern PARTIAL_ARG_BRIEF = Pattern.compile(
            "\"(filename|filePath|path|dir|query|command)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 一次从 Session Log 读多少条事件（框架的翻页上界，见 {@link #drain}）。 */
    private static final int DRAIN_PAGE = 200;

    /**
     * 跟随循环问一次「权威状态」的最长间隔（毫秒），见 {@link TurnMirror}。
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
    /** 附件元信息落库的共用口径（与工作流路径同一个方法，见 metasJson 的类注释）。 */
    private final ChatAttachmentService attachments;
    private final TurnPromptAssembler promptAssembler;
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
    /** 个人偏好（目前只取「自动提炼记忆」开关，见 memoryConfig）。 */
    private final com.wenqu.ai.service.OrgService orgService;
    private final com.wenqu.ai.agent.subagent.SubAgentConsultService consultService;
    private final com.wenqu.ai.agent.context.CitationIntegrityService citations;
    /** 全局 Redis 连接（与缓存/配置广播同一套 spring.data.redis）；框架状态存储只在其上换 key 前缀 */
    private final RedisProperties redisProperties;
    /** 问答台账（看板、坏例、未命中问题都吃这张表）——分派换运行时不该把它换成只有一半流量。 */
    private final com.wenqu.ai.service.QaLogService qaLog;
    /**
     * 框架模型出口的用量台账：主对话/子智能体/记忆提炼/上下文压缩都经 {@link MeteredModel} 记一行。
     * <p>原记账出口（{@code DynamicOpenAiChatModel}）只覆盖 Spring AI 侧，
     * 框架调用一笔都没进 {@code c_ai_usage_log}；不补这一层，记忆与压缩的成本完全不可见。
     */
    private final com.wenqu.ai.service.UsageLedgerService usageLedger;
    /** 配额闸：框架模型出口前判一次，与 Spring AI 那一路共用同一个服务与同一份判据 */
    private final com.wenqu.ai.service.UsageQuotaService quotaService;
    /** 内容安全出口闸：同样挂在模型出口，与 Spring AI 那一路共用一份判据 */
    private final com.wenqu.ai.service.contentsafety.ContentSafetyService contentSafety;
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

    /** 运行总线：后台续跑轮要登记上去，前端才能按 goal_turn 帧自动接流 */
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
     * <p>不归 Spring 管的引擎自己起一个固定池：POST /goal 建行即回包（目标条 / 角标 / 左侧会话列表立刻出现），
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

    /** 懒建的共享模型传输层（见字段注释）；超时口径沿用原 per-session 配置。 */
    private io.agentscope.core.model.transport.OkHttpTransport sharedModelTransport() {
        io.agentscope.core.model.transport.OkHttpTransport t = cachedModelTransport;
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
                               com.wenqu.ai.service.OrgService orgService,
                               com.wenqu.ai.service.UsageLedgerService usageLedger,
                               com.wenqu.ai.service.UsageQuotaService quotaService,
                               com.wenqu.ai.service.contentsafety.ContentSafetyService contentSafety,
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
        this.attachments = attachments;
        this.userImages = userImages;
        this.keywordExtractor = keywordExtractor;
        this.artifactService = artifactService;
        this.sandboxService = sandboxService;
        this.consultService = consultService;
        this.citations = citations;
        this.redisProperties = redisProperties;
        this.qaLog = qaLog;
        this.displayNames = new ToolDisplayNames(toolRegistry, mcpToolLabelService);
        this.skillRepository = skillRepository;
        this.orgService = orgService;
        this.usageLedger = usageLedger;
        this.quotaService = quotaService;
        this.contentSafety = contentSafety;
        this.goalService = goalService;
        this.turnNotify = turnNotify;
        this.agentWebhookService = agentWebhookService;
        this.chatRunBus = chatRunBus;
        this.journalRotation = journalRotation;
        this.knowledgeMapper = knowledgeMapper;
        this.documentMapper = documentMapper;
        this.modelResolver = modelResolver;
        this.promptAssembler = new TurnPromptAssembler(attachments, skillRepository, knowledgeMapper, documentMapper);
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
            // 续跑轮数上限是 GoalSettings（chat.goal.maxRounds，默认 40）。
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
                    .keyPrefix(firstNonBlank(configService.get("chat.agentscopeRedisPrefix"), "agentscope:session:"))
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

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    @Override
    public String name() {
        return "agentscope";
    }

    @Override
    public void chat(ChatCommand cmd, RunChannel channel) {
        // 模型引用按问答侧同一条链解析（请求覆盖 > 会话固定 > 个人默认）：只认请求里那一份时，
        // 靠个人默认作答的轮会解析不出路由（NPE 打断整轮，连错误帧都没有）
        com.wenqu.ai.service.ChatService.TurnModel turn = modelResolver.apply(cmd);
        if (turn.unusableMessage() != null) {
            // 已停用的模型不发给上游：上游只会回一句 401/404，用户读到的是「网关坏了」，
            // 而不是「你选的这个模型已经不能用了，去这儿改」
            failLoud(cmd, channel, turn.unusableMessage());
            return;
        }
        String ref = turn.ref();
        if (ref == null || ref.isBlank()) {
            failLoud(cmd, channel, "未指定模型：请在对话右上角选择模型，或在个人设置中配置默认模型");
            return;
        }
        ModelRegistryService.ModelRoute route = modelRegistry.chatRoute(ref);
        if (route == null) {
            failLoud(cmd, channel, "模型「" + ref + "」未登记或已不可用：请在「智能体 → 模型供应商」里确认该供应商与模型");
            return;
        }
        ExecutionPolicy policy = tools.policyFor(cmd,
                (code, message) -> channel.emit(RunEventType.WARN, message));
        // 用户开了新的一轮：目标循环让位（用户优先）——**只有后台续跑轮在飞时**才打断它。
        // 没有后台轮时，用户这一轮就是目标的一轮（它结束后的那一拍会由目标自己续上），
        // 在那时把目标打断等于「用户刚说完话，目标就停了」。
        if (backgroundGoalTurns.stream().anyMatch(k -> k.startsWith(cmd.getSessionId() + "|"))) {
            goalService.noteUserTurn(cmd.getSessionId(), cmd.getUserId(), channel);
        }
        WenquSourcePool pool = tools.newPool(cmd, channel.emitter());
        // 本轮改过用户存过的选择（失效的会话模型已清除、改用他的默认模型）必须说出来。
        // 只登记降级、不再另发一条 WARN：降级随消息落库、刷新后仍在，WARN 是同一句话的第二份，
        // 两条都会渲染成警示条（同一个原因上下各一条）
        if (turn.fallbackNotice() != null) {
            pool.registrar().degrade("modelFallback", turn.fallbackNotice());
        }

        // 本轮身份（轮级互斥外壳已经登记好 turnId）：待办落库、任务清单留痕、步数预算归零都认它
        String turnId = turnIdOf(cmd);

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
        slot.begin(tools.newTurn(cmd, pool, channel, policy, hooks, slot.workspaceMemoryTainted()));
        // 每轮文件改动：提交前先让执行器拍轮前快照（异步入队、FIFO 先于本轮第一次写），
        // 顺带对账「用户上一次回答之后改了什么」。对账结果在下文拼进本轮输入；快照本身仍由
        // settle 在轮末等齐。非本机工作区/执行器离线都在服务内部静默跳过。
        // API Key 轮次没有文件工具，拍快照没有意义。
        com.wenqu.ai.service.ProjectTurnChangesService.Start changes =
                cmd.isApiKey() ? null : turnChangeService.start(cmd.getSessionId(), turnId);
        // 本轮智能体绑定一次解析：实例重建判据、人设、技能白名单三处共用同一份（避免一轮读三遍库，
        // 也避免「判据说没换、人设却按另一份装配」这种自相矛盾）
        boolean sandboxWanted = wantsSandbox(cmd, policy);
        slot.noteProfile(sandboxWanted ? "workspace" : "chat");
        // 本机执行器离线时的兜底必须让用户看见：这一轮文件在服务器上，不在他的电脑上。
        if (sandboxWanted && sandboxService != null) {
            try {
                String wsNotice = sandboxService.sessionWorkspaceNotice(cmd.getSessionId(), cmd.getUserId());
                if (wsNotice != null) {
                    pool.registrar().degrade("workspaceFallback", wsNotice);
                    channel.emit(RunEventType.WARN, wsNotice);
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
        // 文件系统在 build 期固定：本机目录 ↔ 云端沙盒切换必须换实例键，否则会继续用旧的那一套
        String wsKey = (sandboxWanted && sandboxService != null)
                ? sandboxService.sessionWorkspaceKey(cmd.getSessionId(), cmd.getUserId()) : "";
        try {
            session = sessions.open(cmd, ref,
                    binding.key() + "|sb=" + (sandboxWanted ? "1" : "0") + "|ws=" + wsKey,
                    ctx -> buildAgent(cmd, policy, route, hooks, binding, slot, sandboxWanted),
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
                channel.emit(RunEventType.WARN, message);
            }, text -> sessions.steer(cmd.getUserId(), cmd.getSessionId(), text));
        }
        // 重复调用闸同样按轮归零：不归零就是上一轮的调用签名判这一轮
        AgentxRepeatGuard repeatGuard = sessions.repeatGuard(sessions.key(cmd));
        if (repeatGuard != null) {
            repeatGuard.beginTurn((code, message) -> {
                pool.registrar().degrade(code, message);
                channel.emit(RunEventType.WARN, message);
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

        Recording recording = new Recording(spills, hooks.consultLedger(), tools, hooks, displayNames,
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
            channel.emit(RunEventType.WARN, "计划模式未能启用（框架侧未进入计划位），本轮将不使用计划卡");
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
                (chatSeesImages && !userImgs.isEmpty()) ? promptAssembler.buildMultimodalMsg(modelInput, userImgs) : null;
        // 工具面按轮落到框架的组激活集上（判据仍取自老装配那一条 exposes，不另写一份），
        // 并把「配置允许的名单」与「框架真正下发给模型的工具表」逐名比对一次——不一致就发 warn，
        // 静默少一道闸正是这套机制最初要治的病。
        for (String surfaceProblem : sessions.applyToolSurface(cmd, tools.turnGroupedNames(cmd, policy), policy)) {
            channel.emit(RunEventType.WARN, surfaceProblem);
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
            channel.emit(RunEventType.TURN_START, com.alibaba.fastjson2.JSON.toJSONString(
                    java.util.Map.of("wallMs", prepMs, "prepMs", prepMs)));
            followTurn(session, cmd, channel, pool, route, hooks, turnId, historyBefore, startCursor,
                    userMessageId, current, recording, startedAt, prepMs, binding.contextWindow());
        } finally {
            sessions.releaseFollower(sessionKey);
            // 收口后清掉本轮落点：之后再有工具调用会明确失败，而不是回落到这一轮的池子里
            slot.end(turnId);
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
                            Recording recording, long startedAt, long prepMs, int contextWindow) {
        long machineBudgetMs = turnSeconds() * 1000L;
        // 断流对账的读数起点：主链模型包装器按会话常驻、跨轮复用，本轮的断流次数只能取收尾读数
        // 减开轮读数（见 MeteredModel.abruptCloses）。实例还没建出来时是 null——那一跑连模型都没调，
        // 也就没有「断流」可判。
        MeteredModel turnModel = sessions.chatModel(sessions.key(cmd));
        long abruptBefore = turnModel == null ? 0L : turnModel.abruptCloses();
        long modelErrBefore = turnModel == null ? 0L : turnModel.modelErrors();
        // 接流快照改由本轮自己供：外壳开轮时只有「还没出字」那份空快照，而正文/工具卡/时间线
        // 只活在这里的 Recording 与引用池里。不回填的表现为——生成中刷新，气泡接回来是一片空白。
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
        loop.seed(turnStartSeq(session, current == null ? null : current.turnId(), startCursor), startCursor);
        // 连续多步没有带回新信息：由引擎叫停（不再等模型自己停下来）
        // 等待预算用尽：卡片已落终态，收尾时不再对用户说「答完会自动接着跑」
        // 本轮有没有真的被框架启动。投递循环 100ms 一跳，正常几百毫秒内就离队；被闸门跳过时它会
        // 永远停在 queued，而「没启动」这件事框架自己不报错——不另设一条早于整轮预算的判据，
        // 用户看到的就只是「转了五分钟然后说超时」，一个字也没有。
        long launchDeadline = startedAt + LAUNCH_GRACE_MS;
        // 会话状态跟事件认（见 TurnMirror）。起轮种一次权威待办，之后每一跳只认这一跳读到的事件。
        TurnMirror mirror = new TurnMirror();
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
                return AgentscopeChatEngine.machineMs(startedAt, interactions.humanWaitMs(),
                        interactions.waitingOnHuman(), interactions.waitBeganAt());
            }

            @Override
            public void pause(long millis) {
                AgentscopeChatEngine.sleep(millis);
            }

            @Override
            public long drain(long cursor, long emitFrom) {
                return AgentscopeChatEngine.this.drain(session, cursor, emitFrom, channel, recording, mirror);
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
                AgentscopeChatEngine.this.emitLiveUsage(session, cmd, pool, channel, historyBefore,
                        usageStamp, messageChanged, contextWindow);
            }

            @Override
            public int emitRetrieved(int announcedRefs) {
                return AgentscopeChatEngine.this.emitRetrieved(cmd, pool, channel, announcedRefs, recording);
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
                return tools.stopRequested(cmd);
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
                return AgentscopeChatEngine.this.answerTimedOutAsk(pending, cmd);
            }

            @Override
            public void beginHumanWait(Map<String, SessionEvent> pending) {
                AgentscopeChatEngine.beginHumanWait(channel, recording, pending);
            }

            @Override
            public void endHumanWait() {
                AgentscopeChatEngine.endHumanWait(channel, recording);
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
        interactions.settle(() -> endHumanWait(channel, recording));
        // 收尾把游标跑到 head：这一轮的尾巴增量不能再漏（镜像传 null——出轮之后没人读状态）
        drain(session, loop.cursor(), loop.emitFrom(), channel, recording, null);

        // 计划卡裁决的界面终态：拒绝=plan_cancelled、继续对话=plan_superseded。不补的表现为
        // 用户已经拒了计划，那张卡却还挂着「等待中」（控制流仍归框架判，这里只补留痕帧与 done 标记）
        String planOutcomeRaw = sessions.takePlanOutcome(sessions.key(cmd));
        TurnInteractionCoordinator.PlanOutcome planOutcome =
                TurnInteractionCoordinator.planOutcome(planOutcomeRaw);
        boolean superseded = planOutcome == TurnInteractionCoordinator.PlanOutcome.SUPERSEDED;
        if (superseded) channel.emit(RunEventType.PLAN_SUPERSEDED, "{}");
        else if (planOutcome == TurnInteractionCoordinator.PlanOutcome.CANCELLED) {
            channel.emit(RunEventType.PLAN_CANCELLED, "{}");
        }
        // 整轮机器预算用尽而循环是自己退的：屏幕上这一轮已经收尾，框架那一跑却还活着——不叫停，
        // 下一步与下一段钱就记在没有人跟的那一跑上。中断是协作式的：整轮那道闸打不断在途调用，
        // 在途那一步跑完之后不再起新的模型调用；已经流出半截的正文照旧走下面的收尾落库，不丢。
        // 判据用同一个 machineMs——与上面 while 的退出条件一字不差，否则「退出了」与「预算用尽」
        // 会对不上，把正常答完的那一轮也报成超时。
        boolean ranOutBudget = false;
        if (!loop.stopped() && !loop.neverLaunched() && !superseded
                && TurnFollowLoop.budgetExhausted(machineMs(startedAt, interactions.humanWaitMs(), interactions.waitingOnHuman(), interactions.waitBeganAt()), machineBudgetMs)) {
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
        String failure = runFailure(session, current);
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
        if (answer.isBlank() && failure == null) answer = stripFallbackBody(
                recording, lastAssistantText(session, historyBefore));
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
        String wrapUp = maxIterationsWrapUp(session, historyBefore);
        boolean cutByMaxIters = wrapUp != null && !loop.stopped() && !loop.timedOut() && !loop.neverLaunched() && failure == null;
        if (cutByMaxIters && streamedBody) {
            // 过同一个剥离器：这条收尾正文同样可能被模型塞进 <related>／<process>，
            // 而推荐芯片只在 related 那一份状态上算，绕过它等于这一轮最后那颗芯片凭空少一源
            String extra = stripFallbackBody(recording, wrapUp).strip();
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
        finish(cmd, channel, pool, route, hooks.modelRef(), contextWindow, answer, recording,
                userMessageId, loop.stopped(),
                loop.timedOut(), ranOutBudget, loop.stalled(), awaiting, machineMs, interactions.humanWaitMs(), wallMs, prepMs,
                session, historyBefore, turnId, planRecord);
        // 站内通知（旁路：内部全捕获，写不进去只记 WARN，绝不影响这一轮收口）：长轮跑完时
        // 人多半已经切走或锁屏，只靠屏幕看得见等于「跑完没人知道」。
        // 「提前停了」与「没跑成」分两句说：已经写出一半正文的那一轮，用户真读到了东西，
        // 把它报成「没跑成」等于说那段话不存在。
        // 铃铛只说人话：spokenFailure 就是 emitRunFailure 生成的那一句。若在它为空时退回
        // `failure`——那是上游原始串（「HTTP transport error during streaming: HTTP request failed
        // with status 403…」，英文、带响应体碎片），用户在铃铛里读不出「该充值还是该换模型」，
        // 而用户自己叫停的那一轮也会被报成故障。原始串留给日志，不进通知。
        String earlyExit = spokenFailure;
        if (earlyExit == null && loop.neverLaunched()) earlyExit = "这一轮模型没有被调用，请重试或新建一条会话再问";
        if (earlyExit == null && loop.timedOut()) earlyExit = "等你确认的那一步超时，这一轮没有继续执行";
        if (earlyExit == null && ranOutBudget) {
            earlyExit = com.wenqu.ai.agent.chat.TurnRecorder.TURN_BUDGET_NOTE;
        }
        if (earlyExit == null && loop.stalled()) {
            earlyExit = com.wenqu.ai.agent.chat.TurnRecorder.STALL_NOTE;
        }
        if (earlyExit == null && cutByMaxIters) earlyExit = "推理步数已用满，活没干完就停了";
        turnNotify.onTurnSettled(cmd, turnId, machineMs, recording.toolCallList().size(),
                loop.stopped() || loop.stalled(), awaiting, earlyExit, !answer.isBlank(),
                turnId != null && turnId.startsWith(com.wenqu.ai.service.GoalService.GOAL_TURN_ID_PREFIX));
    }

    /**
     * 这一跑是不是「失败收场」：框架把执行异常留在 {@code executionError()}，被拒的命令留在任务状态里。
     *
     * @return 给用户看的失败原因；没有失败痕迹时返回 null（那是「还在等/已停止/正常跑完」）
     */
    private static String runFailure(AgentSession session, AgentSession.Task current) {
        String error = session.executionError();
        if (error != null && !error.isBlank()) {
            return error.length() > 200 ? error.substring(0, 200) : error;
        }
        if (current != null && "rejected".equals(current.status())) {
            return current.error() == null ? "运行被框架拒绝" : current.error();
        }
        return null;
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
                             Recording recording, int contextWindow) {
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
        return openForContinuation(sessionId, userId, turnId, recover, null);
    }

    /**
     * @param guestUid 非空=这一轮原本是<b>免登录分享会话</b>里的一轮：按访客口径挂工具面（沙盒/联网/MCP
     *                 一概不给），身份仍是发布者。少传这一项，接续出来的那一轮会比原来那一轮宽——
     *                 「访客接不回」和「访客拿到更多能力」都在这一个参数上。
     */
    private Following openForContinuation(String sessionId, String userId, String turnId, boolean recover,
                                          String guestUid) {
        if (sessionId == null || sessionId.isBlank()) return null;
        ChatCommand cmd = ChatCommand.builder().sessionId(sessionId).question("")
                .userId(userId == null || userId.isBlank() ? "guest" : userId)
                .agentId(agentIdOfSession(sessionId))
                .guestMode(guestUid != null && !guestUid.isBlank())
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
        ExecutionPolicy policy = tools.policyFor(cmd, (code, message) -> {
        });
        WenquSourcePool pool = tools.newPool(cmd, sink);
        java.util.concurrent.ConcurrentLinkedQueue<String> spills =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        WenquAgentxTools.TurnHooks hooks = tools.newHooks(cmd, ref, () -> turnId, spills,
                consultService.newLedger());
        AgentBinding binding = bindingOf(cmd, policy, ref);
        boolean sandboxWanted = wantsSandbox(cmd, policy);
        AgentxTurnSlot slot = sessions.turnSlot(cmd);
        slot.noteProfile(sandboxWanted ? "workspace" : "chat");
        AgentSession session;
        String wsKey = (sandboxWanted && sandboxService != null)
                ? sandboxService.sessionWorkspaceKey(cmd.getSessionId(), cmd.getUserId()) : "";
        try {
            session = sessions.open(cmd, ref,
                    binding.key() + "|sb=" + (sandboxWanted ? "1" : "0") + "|ws=" + wsKey,
                    ctx -> buildAgent(cmd, policy, route, hooks, binding, slot, sandboxWanted),
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
        Recording recording = new Recording(spills, hooks.consultLedger(), tools, hooks, displayNames,
                cmd.getUserId());
        bindFrameworkToolGate(cmd, recording);
        return new Following(cmd, channel, pool, route, hooks, policy, session,
                session.transcript().messages().size(), session.log().head().seq(), recording,
                binding.contextWindow());
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
        if (sandboxService == null) return null;
        String uid = cmd.getUserId();
        String sessionId = cmd.getSessionId();
        if (uid == null || uid.isBlank() || sessionId == null || sessionId.isBlank()) return null;
        // 聊天档：宿主会话临时目录（文件工具可用，shell 不注册），不建容器。
        // 工作区档若沙盒没装配成功（超时/失败），也退到同一份临时目录，避免回落到宿主共享目录。
        boolean localProject = false;
        try {
            com.wenqu.ai.model.Project p = projectService.projectOfSession(sessionId);
            localProject = p != null && projectService.isLocal(p);
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
        String memoryAgentId = memoryAgentIdOf(cmd);
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
     * 工具卡只能由闸自己从 {@code ToolUseBlock} 取入参往 {@code Recording} 报。
     * 闸在 build 期就挂上、Recording 在建实例后才建，故这里做一次绑定而不是构造期注入。
     */
    private void bindFrameworkToolGate(ChatCommand cmd, Recording recording) {
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
                        f.recording(), System.currentTimeMillis(), 0L, f.contextWindow());
                log.info("[agentx] 续跑已收尾 session={}", f.cmd().getSessionId());
            } catch (Exception e) {
                log.warn("[agentx] 续跑中断 session={}: {}", f.cmd().getSessionId(), e.toString());
                goalService.onRoundSettled(f.cmd().getSessionId(), f.cmd().getUserId(), turnId,
                        0, 0, f.channel(), "", false, false, "跟随循环中断: " + e.getMessage());
            } finally {
                sessions.releaseFollower(sessionKey);
                slot.end(turnId);
                // 后台目标轮跑完：只摘掉「这一条会话的这一个轮」——旧目标轮收尾不能把新目标在飞的那一轮摘掉
                if (turnId != null && turnId.startsWith("goal:")) {
                    backgroundGoalTurns.remove(f.cmd().getSessionId() + "|" + turnId);
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
     * <p>接流：后台轮登记上运行总线（收集型 emitter 当发起通道），goal_turn 帧随后下发，
     * 前端据此自动 {@code GET /chat/run} 探测 + {@code GET /chat/resume} 接上下一轮。
     */
    public void continueGoalTurn(com.wenqu.ai.model.Goal goal, String input, int n) {
        continueGoalTurn(goal, input, n, 0);
    }

    /**
     * 目标轮异步发起入口（目标续跑驱动 {@code GoalTurnDriver} 绑定的就是它）：把 {@link #continueGoalTurn} 的
     * 同步重活（实例冷启动装配）挪出调用方线程——POST /goal 建行即回包，目标条 / 角标 / 左侧会话列表立即出现，
     * 后台轮在实例就绪后登记总线、由前端轮询接流。内部重试（reserveFollower 抢不到）仍走 GOAL_RETRY 调度，不经此池。
     */
    public void submitGoalTurn(com.wenqu.ai.model.Goal goal, String input, int n) {
        GOAL_KICK.execute(() -> {
            try {
                continueGoalTurn(goal, input, n, 0);
            } catch (RuntimeException e) {
                log.warn("[GOAL] 目标轮异步发起失败: goal={} n={} {}", goal.getId(), n, e.toString());
                // 不把目标留在 EXECUTING 等租约过期：起轮阶段就失败，立刻转暂停让用户看见。
                goalService.markContinueFailed(goal, e.getMessage());
            }
        });
    }

    private void continueGoalTurn(com.wenqu.ai.model.Goal goal, String input, int n, int attempt) {
        String sessionId = goal.getSessionId();
        String uid = goal.getUserId();
        String turnId = com.wenqu.ai.service.GoalService.GOAL_TURN_ID_PREFIX + goal.getId() + ":" + n;
        // 提交前复查：从「上一次收尾决定起下一轮」到这一行之间，用户可能刚停止/删除/改了目标。
        // 不复查就会把一轮已经没人要的续跑放出去（界面上表现为「删了目标它还在跑」）。
        if (!goalService.runnable(goal)) {
            log.info("[GOAL] 目标已不在进行中，取消这一轮续跑: goal={} n={}", goal.getId(), n);
            return;
        }
        boolean recover = "ORPHANED".equals(goal.getErrorClass());
        Following f = openForContinuation(sessionId, uid, turnId, recover);
        if (f == null) {
            goalService.markContinueFailed(goal, "会话实例装配不出来（模型/路由解析失败）");
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
                return;
            }
            log.info("[GOAL] 上一轮还在收尾，{}/{} 次后重试续跑: goal={} n={}", attempt + 1,
                    GOAL_RETRY_MAX, goal.getId(), n);
            GOAL_RETRY.schedule(() -> continueGoalTurn(goal, input, n, attempt + 1),
                    GOAL_RETRY_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        try {
            AgentSession.Task task = f.session().submit(
                    com.wenqu.ai.service.GoalService.GOAL_TURN_ID_PREFIX + goal.getId() + ":" + n, input);
            backgroundGoalTurns.add(sessionId + "|" + turnId);
            // 先登记总线再发帧：goal_turn 到浏览器时，它探测 /chat/run 必须已经探得到这一轮
            java.util.concurrent.atomic.AtomicBoolean alive = openGoalTurnRun(f, sessionId, uid, turnId);
            goalService.emitTurn(goal, n, turnId, f.channel());
            startFollowing(f, task, () -> alive.set(false), true);
        } catch (RuntimeException e) {
            log.warn("[GOAL] 续跑轮提交失败: goal={} n={} {}", goal.getId(), n, e.toString());
            goalService.markContinueFailed(goal, e.getMessage());
            sessions.releaseFollower(sessionKey);
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
     * 本轮到目前为止花掉的「机器时间」：墙钟减去人在回路的等待，<b>含正挂在卡上的这一段</b>。
     * <p>少了在途那一段就是拿墙钟当机器预算用：{@code humanWaitMs} 只在待办清空时才累加，用户对着
     * 计划卡读五分钟，循环下一跳算出来的机器时间就已经涨满 300s，整轮在人还没裁决时被静默收口。
     */
    private static long machineMs(long startedAt, long humanWaitMs, boolean waitingOnHuman, long waitBeganAt) {
        long now = System.currentTimeMillis();
        long waiting = humanWaitMs + (waitingOnHuman && waitBeganAt > 0 ? now - waitBeganAt : 0);
        return now - startedAt - waiting;
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

    /**
     * 人在回路等待的登记/收口，都收敛到这两个入口。
     * <p>收集型通道（定时任务/MCP/子智能体节点）的 {@code emitter()} 为 null，台账里没有它那一行，
     * 直接跳过——{@code ConcurrentHashMap.get(null)} 会抛 NPE，所以这里的判空不能省。
     * <p>成对出现是硬要求：只 begin 不 end，台账的 {@code openWaits} 会永久停在 1，之后这一轮算出来的
     * 机器耗时一直偏小，看门狗形同虚设；只 end 不 begin 则这段等待照扣预算，正是本次修的那个 bug。
     * <p>整轮台账与工具卡计时必须同时开始、同时结束，所以两处只留这一个入口：分头调用的下一次漂移，
     * 表现是「看门狗不截断了，但提问卡上写着 3m12s」——同一个数在两个地方各扣各的。
     */
    private static void beginHumanWait(RunChannel channel, Recording recording,
                                       Map<String, SessionEvent> pending) {
        if (channel.emitter() != null) TurnRegistry.beginHumanWait(channel.emitter());
        recording.beginToolWait(pendingCallKeys(pending));
    }

    private static void endHumanWait(RunChannel channel, Recording recording) {
        if (channel.emitter() != null) TurnRegistry.endHumanWait(channel.emitter());
        recording.endToolWait();
    }

    /**
     * 现在挂着的那（几）张卡属于哪一次工具调用：配对键与 {@link Recording#toolCallKey} 同一套算法，
     * 这样「这段等待」对外记在挂卡那一张头上（每张卡自己的耗时另有按在飞清单摊的扣账，见
     * {@code Recording#endToolWait}）。
     * <p>认不出来（字段形状变了）时返回空集合，由 {@link Recording#endToolWait} 兜底记给在飞的第一把。
     */
    private static java.util.List<String> pendingCallKeys(Map<String, SessionEvent> pending) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SessionEvent e : pending.values()) {
            Map<String, Object> data = e.data() == null ? Map.of() : e.data();
            Object call = firstNonNull(data.get("call"), data);
            String name = toolNameOf(call);
            String id = call instanceof Map<?, ?> cm && cm.get("id") != null ? String.valueOf(cm.get("id")) : null;
            if (name.isBlank() && (id == null || id.isBlank())) continue;
            out.add(Recording.toolCallKey(id, name));
        }
        return out;
    }

    /**
     * 各形态的等待预算，与手写侧同一组配置键（{@code HitlDecisionService} 的三个读取口）：
     * 提问 {@code chat.askTimeoutMs}、计划 {@code chat.planTimeoutMs}、工具审批 {@code chat.approvalTimeoutMs}。
     * <p>提问不能和工具审批挤同一份短预算：它恰恰是最需要人从容看完题面的一类。
     */
    private long pendingBudgetMs(PendingKind kind) {
        return switch (kind) {
            case PLAN -> configService.getLong("chat.planTimeoutMs", 3600000L);
            case ASK -> configService.getLong("chat.askTimeoutMs", 3600000L);
            case APPROVAL -> configService.getLong("chat.approvalTimeoutMs", 600000L);
        };
    }

    /** 这一轮有没有在场观众能答审批卡：网页问答（source 空/user）才有；定时任务/工作流没有。 */
    private static boolean interactiveTurn(ChatCommand cmd) {
        String src = cmd == null ? null : cmd.getSource();
        return src == null || src.isBlank() || "user".equalsIgnoreCase(src);
    }

    /**
     * 提问卡等满预算：把「这一题用户没回答」当成答案送回原工具，让模型接着往下走，
     * <b>不</b>把这一轮掐掉。
     * <p>与手写侧 {@code ChatService} 的超时分支同一语义（那边是
     * {@code buildCombinedAnswer(..., "TIMEOUT")} 回一句「用户未回答这一题」）：超时是「用户不在」，
     * 不是「用户拒绝」，也不是「这一轮作废」；否则一次提问没人答，整轮就没了。
     * <p>走 {@code respondRaw} 而不是 {@code respondAsk}：后者末尾会 {@code ensureFollowing} 补起跟随者，
     * 而这里本来就有一个活着的跟随循环在跑，补第二个会有两个循环读同一条会话、各落一条助手消息。
     */
    private boolean answerTimedOutAsk(Map<String, SessionEvent> pending, ChatCommand cmd) {
        String sessionKey = sessions.key(cmd);
        for (Map.Entry<String, SessionEvent> e : pending.entrySet()) {
            Map<String, Object> data = e.getValue().data() == null ? Map.of() : e.getValue().data();
            if ("confirmation".equals(str(data.get("kind")))) continue;
            String unanswered = "（用户未回答这一题（等待超时，界面没有收到作答）。"
                    + "请基于已有信息自行判断，不要替用户假定选择，也不要重复提问。）";
            try {
                if (sessions.respondRaw(sessionKey, e.getKey(), "ask", true, null, unanswered) == null) {
                    continue;   // 这条待办接不动，试下一条
                }
            } catch (Exception ex) {
                log.warn("[agentx] 提问超时回送未作答失败 {} session={}: {}", e.getKey(),
                        cmd.getSessionId(), ex.toString());
                continue;
            }
            sessions.markAskTimedOut(e.getKey(), unanswered);
            log.info("[agentx] 提问等待超时，按未作答送回模型继续本轮: requestId={} session={}",
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
     * 英文内部话（"Caller cancelled this pending interaction…"），而且下面 {@link Recording#toolCall}
     * 已经把 start 卡关掉了，再让 {@link #translate} 读一遍会因配不上 start 而多推一张重复卡。
     *
     * @param stopped true=用户叫停，false=等待预算用尽（落库状态与卡片措辞都按它分）
     */
    private void abandonPendingInteractions(AgentSession session, ChatCommand cmd, RunChannel channel,
                                            Recording recording, boolean stopped,
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
            // 补了就是 Recording 找不到 start 而整张新卡，一次调用两张卡
            if (!recording.hasOpenTool(name)) continue;
            String id = call instanceof Map<?, ?> cm && cm.get("id") != null
                    ? String.valueOf(cm.get("id")) : entry.getKey();
            String callKey = Recording.toolCallKey(id, name);
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
                emit(channel, RunEventType.TOOL_STATUS,
                        toolCard(recording.uid, name, status, elapsed, beganAt, charged, args, result, null,
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
     * 这一轮的可见轨迹累计（思考正文 + 工具调用清单），只用于落库。
     * <p>没有复用 {@code TurnRecorder}：那条链整个绑在 {@code TurnState}（手写运行时的状态袋）上，
     * 框架引擎没有它。这里只攒落库要的两样，不另造一套轮次状态。
     */
    static final class Recording {
        // 这几份是「本轮已经产出什么」的唯一真源，而接流那条线程会与流水线线程同时读它们：
        // StringBuffer 的 toString/append 自带同步，两份清单靠 snapshotFields() 取快照副本，
        // 于是刷新时重建气泡不会读到半截（手写运行时那一份状态袋同样是被两条线程共读的）。
        private final StringBuffer thinking = new StringBuffer();
        /**
         * 本轮实际流出的正文（与发给前端的 {@code token} 帧同源）。
         * <p>为什么不只取会话里那条最终 assistant 消息：框架把「一条完整的助手消息」在跑完时才写进
         * Transcript，被叫停或等待超时的那一跑根本没有 committed text——那时去取「最后一条有正文的
         * 助手消息」拿到的是<b>上一轮的答复</b>，等于拿别人的话给这一轮存档；多轮会话里
         * 这一取法就会落成上一轮的内容。
         */
        private final StringBuffer answerText = new StringBuffer();
        /**
         * 本轮已处理过的事件 seq（见 translate() 的幂等闸）。
         * <p>两条跟随路径读同一条 Session Log，重投件靠它拦下；HashSet 按轮新建、轮末即弃，
         * 一轮几千条的量级没有内存顾虑。synchronizedSet：两条路径并发 add 各自的 seq。
         */
        private final java.util.Set<Long> seenEventSeqs =
                java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        /** 首次见到这条事件返回 true；重投（另一条跟随路径已处理过）返回 false，调用方整条跳过。 */
        boolean firstDelivery(long seq) {
            return seenEventSeqs.add(seq);
        }

        /** 本轮的进度账：读它判「该收尾/该终止」，写它只在 {@code tool/result} 一处。 */
        AgentxTurnProgress progress() {
            return progress;
        }

        /** 已记录正文是否包含该片段（收尾聚合帧的重复识别用；全文 indexOf，按帧头 60 字粗筛 + 全文确认）。 */
        boolean answerContains(String piece) {
            String full = answerText.toString();
            if (piece.isEmpty() || piece.length() > full.length()) return false;
            String probe = piece.length() > 60 ? piece.substring(0, 60) : piece;
            int at = full.indexOf(probe);
            return at >= 0 && full.regionMatches(at, piece, 0, piece.length());
        }

        /** 已记录思考是否包含该片段：收尾聚合帧对思考的重复识别，与 {@link #answerContains} 同一把尺。 */
        boolean thinkingContains(String piece) {
            String full = thinking.toString();
            if (piece.isEmpty() || piece.length() > full.length()) return false;
            String probe = piece.length() > 60 ? piece.substring(0, 60) : piece;
            int at = full.indexOf(probe);
            return at >= 0 && full.regionMatches(at, piece, 0, piece.length());
        }

        private final java.util.List<java.util.Map<String, Object>> toolCalls =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /**
         * 本轮工具入参的<b>原文</b>长度合计（折叠展示之前）。
         * <p>入参落库/下发前会把超长字段折成预览（见 {@link #toolArgsForDisplay}），而「暗截断对账」判的是
         * 模型这一轮到底生成了多少字——读折叠后的那一份，会把「写文件」这类正常轮误判成内容丢失。
         */
        private long toolArgRawChars;
        /**
         * 本轮「有没有前进」的账（工具结果喂它）：连续无进展由引擎收口，见 {@link AgentxTurnProgress}。
         * 与工具卡同一份结果文本，避免两处各判一次「这步算不算白做」。
         */
        private final AgentxTurnProgress progress = new AgentxTurnProgress();
        /** 工具大输出外溢的落盘标识（{@code 工具名|spillId}），收尾时挂回对应工具卡，卡片才有「查看完整输出」。 */
        private final java.util.Queue<String> spills;
        /** 本轮的咨询台账（卡片要挂的那份「子对话」从这里取）。 */
        private final com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger consultLedger;
        /** 本轮落点与工具装配口（run_code 的扇出面板从这里取）。 */
        private final WenquAgentxTools tools;
        private final WenquAgentxTools.TurnHooks hooks;
        /**
         * 工具卡展示名解析口：落库的这份必须与实时 {@code tool_status} 帧同源，
         * 否则会出现「实时看着是中文、刷新后变英文名」——那张卡在两个时刻由两段代码生成，
         * 展示名一旦分开取，两边迟早漂移。
         */
        private final ToolDisplayNames displayNames;
        /**
         * 本轮归属用户：MCP 动态工具的中文名按它查（MCP 是个人资产，翻译缓存跟着归属走）。
         * 记在轮上而不是引擎实例上，与 {@link #lastToolName} 同一条纪律——引擎是单例，
         * 挂实例上就是甲轮的卡查了乙户的缓存。
         */
        private final String uid;
        /**
         * tool/decision 与 tool/result 的载荷不一定再带工具名，从 requested 帧沿用。
         * <p>记在这里而不是引擎字段上：引擎 bean 是单例、两条会话可以同时在跑，挂在实例上就是
         * 甲轮的工具卡收了乙轮的尾巴。
         */
        private String lastToolName = "";
        /**
         * 本轮内框架自动压缩<b>完成</b>过（{@code compaction/end} 且 status=completed）。
         * <p>收尾要拿它往消息行的 tokens 里落一个标记：那条「已压缩上下文」分隔线只有这一个数据源，
         * 不落库就只在流里闪一下——刷新即无痕，用户回头看不出这一轮压过上下文。
         * 失败/取消的压缩不算：那一次什么都没折进摘要，留痕就是留假痕。
         */
        private volatile boolean compactedThisTurn;
        private String lastToolInput = "";
        // ---- 早产工具卡：模型还在流式生成 tool_use 入参时，先把「这一步在跑」亮出来 ----
        // 只在内存里用、不落库：正式 tool/requested 到达后这张卡被前端原地升级，落库那份从一开始就是正式卡。
        /** 当前正在生成入参的那次调用名（空=没有） */
        private String preparingName = "";
        /** 那次调用的 id（模型一步里并发点名同名工具时用它区分；空 id 只按名字走） */
        private String preparingCallId = "";
        private long preparingStartedAt;
        private long preparingChars;
        private long preparingLastFrameAt;
        private boolean preparingShown;
        /** 入参前缀（只留前 {@link #PREPARING_MIN_CHARS} 个字符，用来捞文件名这类摘要；不攒全文） */
        private final StringBuilder preparingHead = new StringBuilder();

        /** 一次 tool_use 增量累积后的进度；只为「该发帧」的那几拍产出。 */
        record PreparingTick(String name, long chars, String brief, long startedAtMs) {}

        /**
         * 累积一次 {@code tool_use} 参数增量（首块带 name/id，后续块是 {@code __fragment__} 片段）。
         * <p>返回 null = 这一拍不值得发帧（还没到门槛，或进度帧间隔没到）。
         */
        synchronized PreparingTick noteToolUseDelta(String name, String id, String content, long now) {
            String name0 = name == null ? "" : name;
            String id0 = id == null ? "" : id;
            boolean named = !name0.isBlank() && !PREPARING_FRAGMENT_NAME.equals(name0);
            // 新的一次调用：名字变了，或同名的另一把（id 不同）
            boolean newCall = named && (!name0.equals(preparingName)
                    || (!id0.isBlank() && !id0.equals(preparingCallId)));
            if (newCall) {
                preparingName = name0;
                preparingCallId = id0;
                preparingStartedAt = now;
                preparingChars = 0L;
                preparingLastFrameAt = 0L;
                preparingShown = false;
                preparingHead.setLength(0);
            }
            if (preparingName.isEmpty()) return null;
            String piece = content == null ? "" : content;
            preparingChars += piece.length();
            if (preparingHead.length() < PREPARING_MIN_CHARS) {
                preparingHead.append(piece, 0,
                        Math.min(piece.length(), PREPARING_MIN_CHARS - preparingHead.length()));
            }
            boolean due = preparingShown
                    ? (now - preparingLastFrameAt >= PREPARING_EVERY_MS)
                    : (preparingChars >= PREPARING_MIN_CHARS || now - preparingStartedAt >= PREPARING_MIN_MS);
            if (!due) return null;
            preparingShown = true;
            preparingLastFrameAt = now;
            return new PreparingTick(preparingName, preparingChars,
                    partialArgBrief(preparingHead.toString()), preparingStartedAt);
        }

        /** 正式 {@code tool/requested} 到了：早产状态收摊，后面的入参片段不再发进度帧。 */
        synchronized void clearPreparing() {
            preparingName = "";
            preparingCallId = "";
            preparingChars = 0L;
            preparingShown = false;
            preparingHead.setLength(0);
        }
        /**
         * 每次调用的入参，按 {@link #toolCallKey} 配回<b>它自己那一次</b>调用。
         * <p>为什么不能共用 {@link #lastToolInput} 那一个字段：模型一步里可以同时点两把工具（框架并发派发），
         * 后到的 requested 会盖掉先到的入参，先那把的 done 帧就带着别人的参数下发。而前端
         * {@code onToolStatus} 收终态是「按名字找最近那张 start 卡并把 args 写回去」，于是那张卡的
         * 「入参」整段变成另一个工具的参数。
         * 落库那份是在 start 时写的、本来就是对的，所以症状是「实时看到的入参是错的、刷新又好了」。
         */
        private final Map<String, String> toolInputByKey = new java.util.LinkedHashMap<>();
        /**
         * 收集模型实际发出的检索词：{@code search_knowledge} 工具的 {@code query} 入参即它真去搜的短语
         * （模型自己怎么改写的检索短语，比从用户原问句抽关键词更贴近「它真搜了什么」）。
         * 状态行 {@code retrieved.terms} 优先用这一份，收集不到才退回问题关键词（见 {@link #retrievedJson}）。
         */
        private final Set<String> searchQueries = new LinkedHashSet<>();
        /**
         * 本轮<b>已经被框架写进会话上下文</b>的插话收据号（凭证是 {@code inbox/applied} 事件里的 commandIds）。
         * <p>记在这里而不是只记在 {@code TurnMirror} 上：收尾那次全量补读传的镜像是 null（出轮之后没人再读
         * 会话状态），而那恰恰是最后一条插话最可能被应用的时刻。
         */
        private final Set<String> appliedSteers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /**
         * 这一跑是否已经真的结束（看到过 {@code run/end}）。
         * <p>插话对账要用它。撞到整轮机器预算的那一支现在会当场叫停那一跑（见 followTurn 的
         * {@code ranOutBudget}），所以框架会写下终态的 {@code run/end}——那句「模型还没收到这句话」
         * 从此可以如实报出来；没等到 {@code run/end} 就说明那一跑还活着，插话会在下一次调模型前
         * 正常应用，这时候交回前端等于把同一句话发两遍。
         */
        private volatile boolean runEnded;

        /** 记一笔「这条插话已经送达模型」——收尾按它认账，没到账的交回前端补发。
         *  @return false = 这条收据已经记过（收尾那次全量补读会把同一事件再读一遍），别重复留痕 */
        boolean markSteerApplied(String receiptId) {
            return receiptId != null && !receiptId.isBlank() && appliedSteers.add(receiptId);
        }
        /**
         * 本轮 {@code plan_write} 工具写入的计划正文（框架 {@code PlanWriteTool} 的入参 {@code content}）。
         * <p><b>为什么必须单独攒</b>：框架的计划正文走 {@code plan_write} 落到工作区 {@code plans/PLAN.md}，
         * 而 {@code plan_exit} 的入参只有一个可选的 {@code summary}（一句话摘要，见 {@code PlanExitTool}
         * 的 inputSchema）——正文不在退出确认的入参里。问渠的计划卡要展示「整份计划」，
         * 若直接拿 {@code plan_exit} 的入参当 plan 字段，用户看到的就是 {@code [object Object]}。
         * <p>同轮多次 {@code plan_write} 是覆盖语义（框架侧 replace the file），保留最后一份即可。
         */
        private String planMarkdown = "";
        /**
         * 当前这一版计划正在流式产出的正文（{@code plan/changed} 的 PLAN_DELTA 增量累积）。
         * <p>与 {@link #planMarkdown} 不同：后者是 plan_write 入参里的整份稿，只有工具请求那一刻才有；
         * 接流快照要回答「此刻屏幕上那张计划卡里写到哪了」，只能用这份增量缓冲。
         * 新的 plan_approval 落地后清空（权威稿以 planVersions 最后一版为准）。
         */
        private final StringBuffer planDraft = new StringBuffer();
        /** 正在等用户裁决最新一版计划（快照据此区分「生成中」与「待确认」） */
        private volatile boolean planPending;
        /** 最新一版计划挂出待确认的时刻（快照算 remainingMs 用，只给时长不给绝对时刻） */
        private volatile long planPendingAt;
        /** 每次调用的开始时刻（与入参同一个配对键），终态时算耗时下发——前端工具卡读 elapsedMs，缺它就显示 0ms。 */
        private final Map<String, Long> toolStartMs = new java.util.LinkedHashMap<>();
        /**
         * 在飞工具各自要<b>扣掉</b>的「等人」时长（配对键 → 累计毫秒）。
         * <p>提问卡/计划卡/审批卡挂出到用户点完，那一段既在工具的开始-结束窗口里、又不是机器在干活。
         * 不拆出去的后果：一张让人思考了三分钟的提问卡，卡片自己写着「3m12s」，读起来就是「这个工具慢得离谱」。
         * <p>扣账摊给<b>等待期间仍在飞的每一把</b>，不是只摊给挂卡那一把：人在回路的这道闸门卡住的是
         * 整个工具步，同一步里别家的 result 也要等用户点完才到；只摊给挂卡那一把时，
         * 那张卡就会顶着一整段等待时长上界面。
         */
        private final Map<String, Long> toolWaitDeduct = new java.util.LinkedHashMap<>();
        /**
         * 每段等待<b>记在哪一张卡上</b>（对外下发与落库的那份 {@code waitedMs}）。
         * <p>一张闸门只记一笔：前端整段跨度算的是「首尾之差 − 各张卡 waitedMs 之和」，同一段时间摊到两张
         * 卡上就扣了两遍，跨度会被夹成 0。
         */
        private final Map<String, Long> toolWaitCharge = new java.util.LinkedHashMap<>();
        /** 当前这段等待的起点（0 = 此刻没在等人）；与 followTurn 里的 {@code waitBeganAt} 同步进出。 */
        private long toolWaitBeganAt;

        /**
         * 一次工具调用的配对键：有 {@code toolCallId} 就按它（并发派发时唯一不串号的标识，
         * 框架两侧都带：requested 在顶层、result 在结果块里），没有才退回按工具名配。
         */
        static String toolCallKey(String toolCallId, String name) {
            return toolCallId == null || toolCallId.isBlank() ? (name == null ? "" : name) : toolCallId;
        }

        /** 登记开始时刻并把它回给调用方：同一个时刻既要进等待账、也要写进卡片那份 {@code startedAtMs}。 */
        long markToolStart(String key) {
            long at = System.currentTimeMillis();
            if (key != null && !key.isBlank()) toolStartMs.put(key, at);
            return at;
        }

        /** 取回这一次调用的开始时刻；配不上 start（如从游标中途接流）时为 0，调用方按「没有计时」处理，不许当 0 毫秒。 */
        long takeToolStart(String key) {
            Long t = key == null ? null : toolStartMs.remove(key);
            return t == null ? 0L : t;
        }

        /** 挂卡那几把调用的配对键：这段等待要<b>记账</b>在其中第一张上（认不出来时兜底给在飞的第一把）。 */
        private final java.util.List<String> toolWaitKeys = new java.util.ArrayList<>();

        /** 等待开始：记下挂的是哪几把调用的卡；扣账在收口时按当时在飞的清单现算。 */
        void beginToolWait(java.util.Collection<String> keys) {
            toolWaitBeganAt = System.currentTimeMillis();
            toolWaitKeys.clear();
            if (keys != null) toolWaitKeys.addAll(keys);
        }

        /**
         * 等待收口：<b>扣账摊给此刻仍在飞的每一把</b>（闸门卡住整个工具步，谁的窗口都盖住了这段），
         * <b>记账只落挂卡那一张</b>（跨度按各张 waitedMs 之和来减，记两张等于同一段时间扣两遍）。
         * <p>一把都没在飞（本轮只等人、没有未结算的调用）时两处都不记，这段只进整轮那本账。
         */
        void endToolWait() {
            if (toolWaitBeganAt <= 0L) return;
            long wait = System.currentTimeMillis() - toolWaitBeganAt;
            toolWaitBeganAt = 0L;
            java.util.List<String> inflight = new java.util.ArrayList<>(toolStartMs.keySet());
            if (inflight.isEmpty()) {
                toolWaitKeys.clear();
                return;
            }
            for (String key : inflight) toolWaitDeduct.merge(key, wait, Long::sum);
            String chargeKey = null;
            for (String key : toolWaitKeys) {
                if (toolStartMs.containsKey(key)) { chargeKey = key; break; }
            }
            if (chargeKey == null) chargeKey = inflight.get(0);   // 归属认不出来（字段形状变了）才兜底
            toolWaitCharge.merge(chargeKey, wait, Long::sum);
            toolWaitKeys.clear();
        }

        /**
         * 这一次调用的窗口里被「等人」盖住了多久（用于算它自己的 {@code elapsedMs}），同时销掉这笔扣账。
         * <p>与 {@link #takeToolCharge} 分开的理由见那两个字段各自的注释：一张卡该报真实干活时长，
         * 而整段跨度只按挂卡那一张减一次。
         */
        long takeToolWait(String key) {
            // 等待账跟着这次结算一起销掉：键是一次性的，留着只会让本轮的表越长越大
            long wait = key == null ? 0L : toolWaitDeduct.getOrDefault(key, 0L);
            if (key != null) toolWaitDeduct.remove(key);
            return wait;
        }

        /** 这一段等待对外记在哪张卡上的那份数（只有挂卡那把拿得到非零值），同时销掉这笔记账。 */
        long takeToolCharge(String key) {
            long wait = key == null ? 0L : toolWaitCharge.getOrDefault(key, 0L);
            if (key != null) toolWaitCharge.remove(key);
            return wait;
        }
        /**
         * 回答时间线（与 {@code TurnRecorder} 同一套段格式：正文区间段 / 工具下标段 / 思考区间段）。
         * <p>不落库的后果：刷新后前端拿不到交错点，{@code hasTimelineBlocks} 为假，工具卡掉进
         * 「历史恢复兜底」列表、沉到正文下方——实时看着对、刷新就错位。
         */
        private final List<Map<String, Object>> timeline =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /** 正文区间段的锚点（已成段的正文长度），与 {@code TurnRecorder.timelineAnchor} 同义。 */
        private int timelineAnchor;

        /** 把锚点到 pos 之间的正文记为一个文本段（无新增则忽略）；插工具段前调用，保持「正文—工具」交错。 */
        private void flushTimelineText(int pos) {
            if (pos <= timelineAnchor) return;
            timeline.add(Map.of("kind", "text", "from", timelineAnchor, "to", pos));
            timelineAnchor = pos;
        }

        /**
         * 插话留痕：这一轮<b>在这一刻把用户补的那句听进了上下文</b>（框架 {@code inbox/applied} 的时机），
         * 所以它夹在正文与工具之间，而不是整段回答之前或之后。与工具段同一手法：先把此前的正文成段，
         * 再插段，交错顺序如实保留。
         * <p>只在这句话真被应用时才留痕：没赶上的那句由 {@code done.pendingSteers} 交回前端，
         * 作为新一轮的问题重新出现——它没进过这一轮，就不该长在这一轮的回答里。
         */
        void steer(String text, String messageId) {
            flushTimelineText(answerText.length());
            Map<String, Object> seg = new java.util.LinkedHashMap<>();
            seg.put("kind", "steer");
            seg.put("text", text);
            seg.put("messageId", messageId == null ? "" : messageId);
            timeline.add(seg);
            transcript.steer(text, messageId);
        }

        /**
         * 落库用时间线：工具段按调用顺序夹在正文段之间，末段补到 body 全长（含追加的相关推荐/中断标记）。
         * <p>body 若被引用自检整体替换过（{@code outcome.text()}），正文区间就对不上了——此时<b>只丢 text 段</b>，
         * 保留思考/过程/工具段：正文由前端按整段接在末尾（{@code timelineView} 的尾段），过程与工具卡仍点得开。
         * 整条返回 null 会让前端在 done 那一步把时间线清空：用户刚看着跑完的几次检索/咨询，
         * 在答完的同一秒整体消失。
         */
        String timelineJson(String body, String answerText) {
            if (body == null || !body.startsWith(answerText == null ? "" : answerText)) {
                java.util.List<Map<String, Object>> kept = new java.util.ArrayList<>();
                synchronized (timeline) {
                    for (Map<String, Object> seg : timeline) {
                        if (seg != null && !"text".equals(seg.get("kind"))) kept.add(seg);
                    }
                }
                return kept.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(kept);
            }
            flushTimelineText(body.length());
            return timeline.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(timeline);
        }

        /**
         * 思考段是否还开着：框架没有「思考阶段结束」这个事件，只能在正文块或工具调用到来时补收尾帧。
         * <p>{@code thinkFrom} 记这一段在累计全文里的起点——一次问答常有多轮思考（每轮工具调用前各一次），
         * 只交全文会让它们首尾粘成一块；按轮成段写进时间线，前端才能把「它在哪一步想了什么」就地渲染。
         */
        private volatile boolean thinkingOpen;
        private int thinkFrom;
        /**
         * {@code <related>}／{@code <process>} 的流式剥离器：正文增量先过它再下发。
         * <p>不接的表现为推荐块标签原文印在回答里——那句「回答末尾用 &lt;related&gt;…」的提示词
         * 是两条运行时共用的（{@code RelatedPrompt} 一处出处），提示了就得负责剥。
         */
        private final ModelOutputParser related = new ModelOutputParser();
        private final TurnTranscriptRecorder transcript = new TurnTranscriptRecorder();
        /**
         * 本轮出过的计划卡（按版本顺序）。裁决之后随助手消息落 {@code plan} 列——
         * 否则刷新后只剩「还在等确认」的那张（它由待办恢复端点重建），已批过的每张计划都消失，
         * 用户回头看不出这一轮到底是按哪一版计划跑的。
         */
        private final java.util.List<Map<String, Object>> planVersions =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /**
         * 最后一版计划卡的批准 ID（随助手消息落 plan 列）：血缘需要一个稳定锚点——
         * 旧卡要能被新卡指回、刷新后「查看新一轮」要能按 id 定位；只有正文的 plan 记录做不到这一点。
         */
        private volatile String planApprovalId;

        /** 计划草稿增量（{@code plan/changed} 的 PLAN_DELTA）：新草稿一出现就不再是「待确认」状态。 */
        void planDelta(String delta) {
            if (delta == null || delta.isEmpty()) return;
            synchronized (planDraft) {
                planDraft.append(delta);
            }
            planPending = false;
        }

        /** 一版计划挂出待确认：记下权威稿与产出它的意见，清空草稿缓冲。 */
        void planVersion(String plan, String feedback, String approvalId) {
            Map<String, Object> version = new java.util.LinkedHashMap<>();
            version.put("plan", plan == null ? "" : plan);
            version.put("feedback", feedback == null ? "" : feedback);
            if (approvalId != null) version.put("planApprovalId", approvalId);
            synchronized (planVersions) {
                planVersions.add(version);
            }
            synchronized (planDraft) {
                planDraft.setLength(0);
            }
            if (approvalId != null) planApprovalId = approvalId;
            planPending = true;
            planPendingAt = System.currentTimeMillis();
        }

        /** 快照读取：计划版本链副本（调用方按返回副本渲染，不直接暴露内部列表） */
        java.util.List<Map<String, Object>> planVersionsCopy() {
            synchronized (planVersions) {
                return new java.util.ArrayList<>(planVersions);
            }
        }

        String planDraftText() {
            synchronized (planDraft) {
                return planDraft.toString();
            }
        }

        boolean hasPlanActivity() {
            return planPending || !planDraftText().isEmpty() || !planVersionsCopy().isEmpty();
        }

        boolean planPendingNow() {
            return planPending;
        }

        long planPendingAtMs() {
            return planPendingAt;
        }

        String planApprovalIdValue() {
            return planApprovalId;
        }

        Recording(java.util.Queue<String> spills,
                  com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger consultLedger,
                  WenquAgentxTools tools, WenquAgentxTools.TurnHooks hooks,
                  ToolDisplayNames displayNames, String uid) {
            this.spills = spills;
            this.consultLedger = consultLedger;
            this.tools = tools;
            this.hooks = hooks;
            this.displayNames = displayNames;
            this.uid = uid;
        }

        void thinking(String delta) {
            if (delta == null || delta.isEmpty()) return;
            if (!thinkingOpen) {
                thinkingOpen = true;
                // 先把此前已出的正文落成段：思考段要等 closeThinking 那一刻才追加进时间线，
                // 而末尾正文只在轮末 timelineJson 才补段。不先落段，这段思考就会记到正文前面，
                // 刷新后从答案下方（实时按到达顺序）跳到答案上方——工具/插话入口本来就有同样的 flush。
                flushTimelineText(answerText.length());
                transcript.thinkingStart();
                thinkFrom = thinking.length();
            }
            thinking.append(delta);
            transcript.thinkingDelta(delta);
        }

        /** 正文增量：与发给前端的 {@code token} 帧同源，中断轮靠这一份存档（见 {@link #answerText}）。 */
        void answer(String delta) {
            if (delta == null) return;
            // 正文缓冲与 blocks 必须同拍：快照线程按「缓冲 + blocks」取一致点，分开写会补出重复正文
            synchronized (transcript) {
                answerText.append(delta);
                transcript.textDelta(delta);
            }
        }

        /** 过程独白增量：正文走 TOKEN、过程走 PROCESS，blocks 里按落点成段。 */
        void process(String delta) {
            transcript.processDelta(delta);
        }

        String blocksJson(String turnId, String body) {
            return transcript.toJson(turnId, body);
        }

        /** 终态提示块：结构化原因随 blocks 落库与下发；正文尾标在双写窗口内照旧写进 content。 */
        void notice(String tone, String text) {
            transcript.notice(tone, text);
        }

        void artifacts(java.util.List<java.util.Map<String, Object>> artifacts) {
            transcript.artifacts(artifacts);
        }

        String answerText() {
            return answerText.toString();
        }

        /**
         * 思考段收尾：把这一段的区间记进时间线，并交出累计全文（前端据此停转圈并折叠这一段）。
         * 没开过返回 null（不发空帧）。
         * <p>框架把思考当流内块、没有「思考阶段结束」这件事，只能在正文块/工具调用到来或整轮收尾时补这一帧。
         * 全文不清空、继续累加：落库的 {@code thinking} 列与暗截断对账都要按「本轮一共想了多少字」算，
         * 分段只靠区间，不做第二份存储。
         */
        String closeThinking() {
            if (!thinkingOpen) return null;
            thinkingOpen = false;
            int end = thinking.length();
            if (end > thinkFrom) timeline.add(Map.of("kind", "think", "from", thinkFrom, "to", end));
            transcript.thinkingEnd();
            return thinking.toString();
        }

        /** 快照线程读「思考段是否还开着」：与 thinking()/closeThinking() 同一把锁，避免读到断段瞬间 */
        synchronized boolean thinkingOpenSnapshot() {
            return thinkingOpen;
        }

        /**
         * 工具卡登记：start 建一张带本次入参的开始时刻，终态按名找最近那张 start 改状态并补耗时。
         *
         * @param args      这一次调用自己的入参（JSON 文本）。并发派发时它必须由调用方按
         *                  {@link #toolCallKey} 配好传进来，读共用字段就是甲张卡收乙次的参数
         * @param elapsedMs 这一次调用的机器耗时（盖住它的等待已扣干净，同一步里别家挂的卡也算）；
         *                  0 = 没有可报的数，不写这个字段
         * @param beganAtMs 服务端开始时刻；0 = 配不上 start，落库那份改记当下
         * @param waitedMs  这段等待<b>记本账的那一笔</b>：只有挂卡那一张拿到非零值，整段跨度按它减一次；
         *                  0 = 这张不是挂卡那张（它的等待已经体现在 elapsedMs 里，但跨度不该重复减）
         * @param result    这一步的输出文本（{@link #toolResultText} 那份，已按 2000 字截断）。
         *                  <b>必须和 SSE 那一帧同形落库</b>：落库那份若不带 result，刷新后
         *                  就只剩入参——工具卡上是一个看不出原因的红叉，「这步为什么失败」只能回去翻服务端日志
         */
        // 返回这一把（若是咨询类工具）的 subChat，供实时 tool_status 终态帧一并下发——
        // 只落库不实时发的话，咨询卡的结果（问/答/资料/子对话）直播看不见、刷新才冒出来。
        java.util.Map<String, Object> toolCall(String name, String status, String args, long elapsedMs, long beganAtMs, long waitedMs,
                      String result) {
            if (name == null || name.isBlank()) return null;
            boolean runCode = com.wenqu.ai.service.SandboxTools.RUN_CODE_TOOL_NAME.equals(name);
            for (int i = toolCalls.size() - 1; i >= 0; i--) {
                java.util.Map<String, Object> prior = toolCalls.get(i);
                if (name.equals(prior.get("name")) && "start".equals(prior.get("status"))) {
                    prior.put("status", status);
                    putTiming(prior, elapsedMs, beganAtMs, waitedMs);
                    if (result != null && !result.isBlank()) prior.put("result", result);
                    String spill = takeSpill(name);
                    if (spill != null) prior.put("spill", spill);
                    // 咨询卡片挂那一问一答（与手写侧同一个实现与同一把长度尺）：刷新后仍点得开。
                    // 派遣改由框架驱使后工具名换成 agent_spawn，认哪几把见 CONSULT_CARD_TOOLS
                    // 同一份 subChat 既落库也通过下方返回的引用交回实时帧：直播与刷新同构，
                    // 不再出现「当场只见一张空卡、刷新后才长出结果」的错位
                    java.util.Map<String, Object> subChat = null;
                    if (CONSULT_CARD_TOOLS.contains(name)) {
                        subChat = com.wenqu.ai.agent.chat.TurnRecorder
                                .consultCardSubChat(consultLedger, args);
                        if (subChat != null) prior.put("subChat", subChat);
                    }
                    // run_code 终态：停面板 ticker 并把扇出清单与这次运行的结构化结果挂上卡片
                    if (runCode) prior.putAll(tools.finishRunCodeCard(hooks));
                    transcript.tool(prior);
                    return subChat;
                }
            }
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("name", name);
            // 展示名与悬停说明随后端下发：
            // 落库的这份是刷新后重建工具卡的唯一依据，写裸名等于把中文名全押在前端那份手抄映射上
            displayNames.putDisplayFields(entry, uid, name);
            entry.put("status", status);
            // 步骤归属随卡落库（取值时刻＝开工这一刻）：刷新后执行详情仍能把工具归到原步骤。
            // 终态回填时不覆盖——那时清单可能已经推进到下一步，拿它盖会把这一步的工具挪到下一步去
            int stepId = hooks.step().index();
            if (stepId >= 0) entry.put("stepId", stepId);
            // 入参随卡片落库：前端刷新后的工具卡与咨询卡「被咨询那一位的名字」都读这一列（缺它两处一起退化）。
            // 超长字段在这里折成预览：整份文件正文（presentArtifact/write_file 的 content）原样进库，
            // 只会把消息行和每次刷新的历史回显一起撑肥，而完整正文本来就在产物库/沙箱文件里。
            // 原文长度单独计入 toolArgRawChars——暗截断对账要的是模型生成了多少字，不能读折叠后的这份。
            if (args != null && !args.isBlank()) {
                toolArgRawChars += args.length();
                entry.put("args", toolArgsForDisplay(args));
            }
            // 输出与入参同一条规则：只在有内容时写，且与 SSE 帧同一个键名（前端读 t.result）
            if (result != null && !result.isBlank()) entry.put("result", result);
            // 耗时与开始时刻同样落库：只靠 SSE 那一帧带 elapsedMs，刷新后每张卡都没有数，
            // 「执行了 N 个操作」后面跟着的是各张卡之和（全为 0）——当场看着有、回头读成 0ms。
            putTiming(entry, elapsedMs, beganAtMs > 0L ? beganAtMs : System.currentTimeMillis(), waitedMs);
            // 时间线：先把这把工具之前的正文成段，再插工具段（下标=它在 toolCalls 里的位置），
            // 刷新后前端据此把工具卡还原到正文之间的原位，而不是一律沉底。
            flushTimelineText(answerText.length());
            timeline.add(Map.of("kind", "tool", "i", toolCalls.size()));
            toolCalls.add(entry);
            // 脚本开始跑：开这块实时面板（作用域是一次运行，两段脚本各自复位）
            if (runCode) tools.beginRunCode(hooks);
            // 终态事件先于 start 到达（如从游标中途接流）时这条新卡也照挂 subChat，免得直播缺这块
            if (CONSULT_CARD_TOOLS.contains(name)) {
                java.util.Map<String, Object> subChat = com.wenqu.ai.agent.chat.TurnRecorder
                        .consultCardSubChat(consultLedger, args);
                if (subChat != null) entry.put("subChat", subChat);
                transcript.tool(entry);
                return subChat;
            }
            transcript.tool(entry);
            return null;
        }

        /**
         * 这个名字的工具当前有没有还挂着「进行中」的卡。
         * <p>判据与 {@link #toolCall} 的合并规则同源（按名字找最后一条 start）：只有它返回 true，
         * 补一份终态才是「把这张卡关掉」，否则就是凭空多推一张卡——同一次调用渲染成两张。
         */
        boolean hasOpenTool(String name) {
            if (name == null || name.isBlank()) return false;
            for (int i = toolCalls.size() - 1; i >= 0; i--) {
                java.util.Map<String, Object> prior = toolCalls.get(i);
                if (name.equals(prior.get("name"))) return "start".equals(prior.get("status"));
            }
            return false;
        }

        /**
         * 计时字段只在有值时才写：「没有这条计时」与「跑了 0 毫秒」在两处消费者（工具卡、时间跨度）
         * 那里必须长得不一样，都写成 0 就等于用假数字填掉了缺口。
         */
        private static void putTiming(java.util.Map<String, Object> entry, long elapsedMs,
                                      long beganAtMs, long waitedMs) {
            if (beganAtMs > 0L) entry.put("startedAtMs", beganAtMs);
            if (elapsedMs > 0L) entry.put("elapsedMs", elapsedMs);
            if (waitedMs > 0L) entry.put("waitedMs", waitedMs);
        }

        /** 按工具名取最早那条未消费的外溢标识（与手写侧 {@code TurnRecorder.takeToolSpill} 同一口径）。 */
        private String takeSpill(String name) {
            if (spills == null) return null;
            for (String e : spills) {
                int bar = e.indexOf('|');
                if (bar > 0 && name.equals(e.substring(0, bar)) && spills.remove(e)) {
                    return e.substring(bar + 1);
                }
            }
            return null;
        }

        /**
         * 落 {@code c_ai_message.thinking} 的那一份：纯文本，不是 JSON。
         * <p>时间线里的 think 段存的是「from/to 指向本文偏移」，前端历史回看按 {@code slice(from,to)} 切段，
         * 所以这一列必须与算偏移时那份**逐字符相同**——套一层 JSON 转义会让每个换行多占两字符、
         * 整列偏移错位，界面上还把 \n 打成字面量。
         */
        String thinkingText() {
            return thinking.length() == 0 ? null : thinking.toString();
        }

        String toolCallsJson() {
            return toolCalls.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(toolCalls);
        }

        java.util.List<java.util.Map<String, Object>> toolCallList() {
            return toolCalls;
        }

        /** 本轮工具入参原文字符数（不受展示折叠影响），供暗截断对账使用。 */
        long toolArgRawChars() {
            return toolArgRawChars;
        }

        /**
         * 接流快照里属于本轮状态的几份内容（正文 / 过程独白 / 思考全文 / 工具卡 / 时间线），全部取副本。
         * <p>读它的是「刷新进来接流」那条线程，写它的始终是本轮流水线线程：两份清单用同步容器、
         * 两段全文用 {@link StringBuffer}，就是为了这一刻不读到半截。
         * <p>时间线末尾那段正文在这里现算（不回填 {@link #timeline}）：工具段按下标指向同一时刻的
         * {@code toolCalls}，所以两份必须出自同一次快照，错开一步前端就把卡片插到别的位置上。
         */
        Map<String, Object> snapshotFields() {
            Map<String, Object> snap = new java.util.LinkedHashMap<>();
            String body;
            String blocksJson;
            // 正文与 blocks 在同一把锁里取：两块来自同一次「已经追加到哪」的真值
            synchronized (transcript) {
                body = answerText.toString();
                blocksJson = transcript.snapshotJson(null);
            }
            snap.put("content", body);
            snap.put("processText", related.process());
            if (thinking.length() > 0) snap.put("thinking", thinking.toString());
            // 思考段还开着吗：前端据此续写同一段，而不是把下一片增量另起一段
            snap.put("thinkingOpen", thinkingOpenSnapshot());
            snap.put("toolCalls", new java.util.ArrayList<>(toolCalls));
            java.util.List<Map<String, Object>> tl = new java.util.ArrayList<>(timeline);
            if (body.length() > timelineAnchor) {
                tl.add(Map.of("kind", "text", "from", timelineAnchor, "to", body.length()));
            }
            snap.put("timeline", tl);
            // 接流时新前端按 blocks 重建；此刻正文还不是终稿，答案通道按当前末尾正文先标
            if (blocksJson != null) {
                snap.put("blocks", com.alibaba.fastjson2.JSON.parseObject(blocksJson));
            }
            return snap;
        }
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
     * 本轮是否走工作区档（建沙盒、全量工具）。聊天档不建容器，直接建实例。
     *
     * <p>判据只有三类：
     * <ol>
     *   <li>会话绑了显式工作区（本机目录或用户建的工作区）——能力必须完整；</li>
     *   <li>续跑/空问题——有常驻实例就沿用上一次的档位，没有则保守走工作区；</li>
     *   <li>问题里有明确的执行/文件意图——走工作区；其余默认聊天。</li>
     * </ol>
     * 默认聊天是这刀的目的：{@code 1+1=?} 这种问题不该为「可能用到文件工具」先付 3 秒沙盒。
     */
    private boolean wantsSandbox(ChatCommand cmd, ExecutionPolicy policy) {
        if (!configService.getBoolean("chat.profileAuto", true)) {
            log.info("[PROFILE] profile=workspace reason=auto-off session={}", cmd.getSessionId());
            return true;
        }
        String key = sessions.key(cmd);
        // 粘性：已经建过工作区档实例的会话，不因为下一句普通问题降回聊天档——
        // 否则整条会话会在两档之间反复重建，工具面和文件系统也跟着来回换。
        // 本机会话目录同样算「已进入工作区档」：它没有 SandboxContext，sandboxAttached 永远是 false。
        boolean pinnedLocal = sandboxService != null && sandboxService.pinnedLocalSessionWorkspace(cmd.getSessionId());
        if (sessions.hasHeld(key) && (sessions.sandboxAttached(key) || pinnedLocal)) {
            log.info("[PROFILE] profile=workspace reason=sticky session={}", cmd.getSessionId());
            return true;
        }
        try {
            com.wenqu.ai.model.Project p = projectService.projectOfSession(cmd.getSessionId());
            if (p != null && (!com.wenqu.ai.model.Project.SELECTION_IMPLICIT.equals(p.getSelectionStatus())
                    || projectService.isLocal(p))) {
                log.info("[PROFILE] profile=workspace reason=bound-project session={}",
                        cmd.getSessionId());
                return true;
            }
        } catch (RuntimeException e) {
            log.debug("[PROFILE] 工作区解析失败，按问题意图判档 session={}: {}",
                    cmd.getSessionId(), e.toString());
        }
        String q = cmd.getQuestion();
        if (q == null || q.isBlank()) {
            boolean sandbox = sessions.hasHeld(key)
                    ? (sessions.sandboxAttached(key)
                        || (sandboxService != null && sandboxService.pinnedLocalSessionWorkspace(cmd.getSessionId())))
                    : true;
            log.info("[PROFILE] profile={} reason=continuation session={}",
                    sandbox ? "workspace" : "chat", cmd.getSessionId());
            return sandbox;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        String reason = null;
        if (SANDBOX_INTENT_EXT.matcher(q).find() || SANDBOX_INTENT_EN.matcher(lower).find()) {
            reason = "intent-en";
        } else {
            for (String word : SANDBOX_INTENT_ZH) {
                if (q.contains(word)) {
                    reason = "intent-zh:" + word;
                    break;
                }
            }
        }
        if (reason == null) {
            log.info("[PROFILE] profile=chat reason=no-intent session={} q={}",
                    cmd.getSessionId(), briefQuestion(q));
            return false;
        }
        // 隐式会话的异步升级：首轮先走聊天档（宿主 scratch，不阻塞），后台建沙盒；
        // 下一轮 isReady 命中后自动切工作区档。默认关，配置 chat.asyncWorkspaceUpgrade 打开。
        if (configService.getBoolean("chat.asyncWorkspaceUpgrade", false)) {
            String uid = cmd.getUserId();
            if (sandboxService.isReady(cmd.getSessionId(), uid)) {
                log.info("[PROFILE] profile=workspace reason=async-ready:{} session={} q={}",
                        reason, cmd.getSessionId(), briefQuestion(q));
                return true;
            }
            sandboxService.provisionAsync(cmd.getSessionId(), uid);
            sessions.turnSlot(cmd).noteAsyncPending(true);
            log.info("[PROFILE] profile=chat reason=async-provisioning:{} session={} q={}",
                    reason, cmd.getSessionId(), briefQuestion(q));
            return false;
        }
        log.info("[PROFILE] profile=workspace reason={} session={} q={}",
                reason, cmd.getSessionId(), briefQuestion(q));
        return true;
    }

    private static String briefQuestion(String q) {
        String s = q.replaceAll("\\s+", " ").trim();
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
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
        // 个人「自动提炼记忆」开关也是 build 期装配（memoryConfig 还是 disableMemoryHooks）：不并进
        // 指纹的话，关掉后要等下一次重建才停，与个人设置里「关掉后下一轮起才停」的说法不符。
        key = key + "|mem=" + orgService.memoryFlushEnabled(cmd.getUserId());
        // 上下文窗口并进指纹：它决定框架的压缩阈值与输入上限，烤死在实例里。
        // 窗口恒等于模型登记上限，并进来是为了管理员改了登记值时这一轮就重建。
        int contextWindow = modelContextWindow(modelRef);
        // 最大输出同理烤在实例里（作为 max_tokens 随请求下发），登记值改了也得重建才生效。
        int maxOutput = modelMaxOutput(modelRef);
        key = key + "|ctx=" + contextWindow + "|out=" + maxOutput;
        // 思考开关仍留在指纹外：它同样烤在实例里，但续跑轮的 ChatCommand 复原不了它，并进来会让
        // 续跑按默认档重建。那一档的变更仍欠「按调用传 GenerateOptions」那一刀。
        return new AgentBinding(row, key, skillFilterOf(row), contextWindow, maxOutput);
    }

    /**
     * 智能体「技能范围」三态 → 框架技能滤镜。
     * <ul>
     *   <li>{@code null} = 跟随全局：不限（{@code SkillFilter.all()}）</li>
     *   <li>{@code 0} = 关闭：一条都不给（{@code SkillFilter.none()}）</li>
     *   <li>{@code 1} = 指定：只给名单里那几条（{@code SkillFilter.only}）。名单为空就是一条都不给
     *       ——用户点的是「指定」，空名单是有意的「不给」，不是「没选所以全给」</li>
     * </ul>
     * 名单里个人技能存的是 {@code {uid}/{name}} 引用串，框架按 {@code skill.getName()} 比对，故剥成裸名。
     */
    private static io.agentscope.core.skill.SkillFilter skillFilterOf(Agent a) {
        if (a == null || a.getToolSkill() == null) {
            return io.agentscope.core.skill.SkillFilter.all();
        }
        if (a.getToolSkill() == 0) {
            return io.agentscope.core.skill.SkillFilter.none();
        }
        Set<String> names = skillNames(a.getSkills());
        return io.agentscope.core.skill.SkillFilter.only(names.toArray(new String[0]));
    }

    /** 技能引用串列表 → 裸名集合（与只读清单、点名注入同一套剥法）。 */
    private static Set<String> skillNames(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("[\\r\\n,，;；]+")) {
            String ref = line.trim();
            if (ref.isEmpty()) continue;
            int slash = ref.lastIndexOf('/');
            String name = slash >= 0 ? ref.substring(slash + 1) : ref;
            if (!name.isBlank()) out.add(name.trim());
        }
        return out;
    }

    private String rolePrompt(Agent bound) {
        return RolePrompt.of(bound, properties.getSystemPrompt());
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
     * 本轮的智能体记忆域（对外能力第二批）：智能体 memory_mode=专属(1)/叠加(2) 且平台总闸开着时
     * 返回智能体 id——记忆路由换到 {@code {uid}/agents/{agentId}} 专属根（注入/工具读写/flush/合并
     * 都随根走，框架 WorkspaceContextMiddleware 读的是路由后的那份）；其余（null/0 或总闸关）返回
     * null = 用户级记忆，行为逐字不变。
     */
    private String memoryAgentIdOf(ChatCommand cmd) {
        if (!configService.getBoolean("agentMemory.enabled", true)) return null;
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
            String content = java.nio.file.Files.readString(md);
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
     * <p>只读库取根目录（{@link com.wenqu.ai.service.SandboxService#pinnedLocalWorkspaceRoot}），
     * 不在装配期建目录；不是本机后端就整段缺席。
     */
    private String localExecutorEnvironment(ChatCommand cmd) {
        String root = sandboxService == null ? null
                : sandboxService.pinnedLocalWorkspaceRoot(cmd.getSessionId());
        if (root == null || root.isBlank()) return "";
        return "\n## 执行环境（本机执行器，以此段为准）\n"
                + "文件与命令工具都在用户自己的电脑上执行，不在服务器容器里。"
                + "当前工作区根目录：" + root + "\n"
                + "- `execute` 的 pwd 就是这个目录，相对路径都从这里解析；路径含空格/括号时要加引号。\n"
                + "- 其它段落若把工作区写成 `/workspace` 或 `/app/data/agentscope`（服务器沙盒/框架状态目录），"
                + "那些路径在这台电脑上不存在，不要用 execute / read_file 去读。\n"
                + "- 交付产物用 presentArtifact（返回值即访问地址），不要在磁盘上找 artifacts 目录。\n"
                + "- 复合命令的退出码由最后一条子命令决定：不要用 2>/dev/null 掩盖失败；"
                + "可能不存在的目录单独探测或加 `|| true`。\n";
    }

    /**
     * 只记账不播报的通道：读历史事件时用它——事件照样进 {@link Recording}（工具卡、思考全文都要落库），
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
     * 本轮在框架 Session Log 里的起点：按框架自己的 turnId 找最早那条属于本轮的事件。
     * <p>为什么不能只按「开轮/接回时刻的 head」定：续跑那一跑里批准之前跑掉的那几次工具同属这一轮，
     * 从接回时刻起读会让落库的工具卡只剩批准之后的半截。找不到（这一轮还没写过事件）就退回 fallback。
     */
    private static long turnStartSeq(AgentSession session, String frameworkTurnId, long fallback) {
        if (frameworkTurnId == null || frameworkTurnId.isBlank()) return fallback;
        long first = Long.MAX_VALUE;
        try {
            for (SessionEvent e : session.log().scan(0, session.log().head().seq())) {
                if (frameworkTurnId.equals(e.turnId()) && e.seq() < first) first = e.seq();
            }
        } catch (Exception e) {
            return fallback;
        }
        return first == Long.MAX_VALUE ? fallback : first;
    }

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
    private long drain(AgentSession session, long cursor, long emitFrom, RunChannel channel, Recording recording,
                       TurnMirror mirror) {
        SessionLog eventLog = session.log();
        long through = eventLog.head().seq();
        while (cursor < through) {
            List<SessionEvent> batch = eventLog.readAfter(cursor, DRAIN_PAGE);
            if (batch.isEmpty()) break;   // seq 有空洞（压缩/迁移）：游标推不动，不在这儿死循环
            for (SessionEvent event : batch) {
                cursor = Math.max(cursor, event.seq());
                translate(event, event.seq() > emitFrom ? channel : SILENT_CHANNEL, recording);
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
    private static final class TurnMirror {
        private final Map<String, SessionEvent> pending = new java.util.LinkedHashMap<>();
        private boolean pendingChanged;
        private boolean taskChanged;
        private boolean messageChanged;

        void observe(SessionEvent event) {
            switch (event.type()) {
                case "interaction/requested", "interaction/resolved" -> {
                    Map<String, Object> data = event.data() == null ? Map.of() : event.data();
                    String requestId = String.valueOf(data.get("requestId"));
                    if ("interaction/requested".equals(event.type())) pending.put(requestId, event);
                    else pending.remove(requestId);
                    pendingChanged = true;
                }
                case "inbox/accepted", "inbox/started", "inbox/closed", "inbox/handled", "inbox/rejected",
                     "run/end" -> taskChanged = true;
                case "message/user", "message/assistant", "tool/result", "turn/output", "migration/baseline" ->
                        messageChanged = true;
                default -> {
                }
            }
        }

        /** 取走并清空「本跳有没有……」——取走即清，循环里那几条 continue 才不会把脏位带进下一跳。 */
        boolean takePendingChanged() {
            boolean v = pendingChanged;
            pendingChanged = false;
            return v;
        }

        boolean takeTaskChanged() {
            boolean v = taskChanged;
            taskChanged = false;
            return v;
        }

        boolean takeMessageChanged() {
            boolean v = messageChanged;
            messageChanged = false;
            return v;
        }

        boolean hasPending() {
            return !pending.isEmpty();
        }

        /**
         * 交给外部的一份副本。
         * <p>不递内部那张表：{@code sessions.notePending} 存下的引用会被恢复端点跨线程读，
         * 而镜像在同一个循环的下一跳还会改它。
         */
        Map<String, SessionEvent> pendingSnapshot() {
            return new java.util.LinkedHashMap<>(pending);
        }

        /**
         * 用权威值重种待办表：代价是一次全量读，一轮只做一次。
         * <p>起轮时本轮游标之前挂着的待办（上一轮没答完的那张卡）不在 drain 的范围里，不种就看不见；
         * 种完把脏位置真，让第一跳照旧把这一批卡报出去。
         */
        void resyncPending(AgentSession session) {
            pending.clear();
            pending.putAll(session.pending());
            pendingChanged = true;
        }
    }

    /**
     * 框架 Session Log 的事件词汇 → 问渠前端事件名。
     * <p>刻意不发：{@code run/*}、{@code step/*}、{@code state/checkpoint}、{@code model/dispatch} 这些
     * 没有前端对应物的帧——前端对认不出的事件名会打 warn（{@code api.js:158}），不如不发。
     */
    private void translate(SessionEvent event, RunChannel channel, Recording recording) {
        // 事件幂等闸：同一轮可能有两条跟随路径（原跟随循环 + ensureFollowing 补位）各自从 Session Log
        // 读事件喂进来，重投的那份若照收，正文就会被整段记录/重发两遍。
        // 框架事件自带轮内单调 seq，这里按「本轮已见 seq」跳过重投件：谁先到谁处理，后到的直接丢。
        if (!recording.firstDelivery(event.seq())) return;
        Map<String, Object> data = event.data() == null ? Map.of() : event.data();
        switch (event.type()) {
            case "model/chunk" -> {
                // 辅助模型调用（记忆提炼 / 压缩摘要，payload.purpose=SUMMARY）与主推理走同一条
                // Session Log：框架的 SessionModels.Recorded 把 purpose 放进每个 chunk 载荷，不认它
                // 的表现是提炼/摘要的正文（「## Memory Flush —（本轮新增）」「## SESSION INTENT /
                // SUMMARY / ARTIFACTS / NEXT STEPS」）被当成用户正文发 TOKEN 帧并落库，
                // 其 thinking 块同时把本轮思考撑爆 TEXT 列 → 整条消息落库失败。只透传
                // 主推理（REASONING）；SUMMARY 的账另有去处（记忆文件/压缩摘要消息），不进聊天流。
                if (!"REASONING".equals(String.valueOf(data.get("purpose")))) return;
                if (!(data.get("chunk") instanceof Map<?, ?> chunk)) return;
                if (!(chunk.get("content") instanceof List<?> blocks)) return;
                // 收尾聚合帧拦截：框架在流末会再发一条携带 finishReason 的
                // 「聚合后段」帧——正文在 ~2 万字处被一分为二，delta 流已完整覆盖全文，聚合帧只重发后段；
                // 照收等于尾部整段重投 + 接缝错位。识别规则：
                // finishReason 在场且帧文本已被已记录正文包含（纯重复）→ 整块跳过；正常收尾帧
                // （空正文/真正的续写）不受影响。
                boolean aggregateTail = chunk.get("finishReason") != null;
                for (Object block : blocks) {
                    if (!(block instanceof Map<?, ?> b)) continue;
                    if ("thinking".equals(b.get("type"))) {
                        String thinkingDelta = str(b.get("thinking"));
                        // 聚合帧同样会重发思考的尾段：正文有上面那道去重闸，思考没有，尾段就会在
                        // 回答末尾多出一行「深度思考」。
                        // 已记录过的一律跳过——它不是新一步的思考，只是同一段的尾巴。
                        if (aggregateTail && recording.thinkingContains(thinkingDelta)) {
                            log.info("[TIMELINE] 流末聚合帧重复思考尾段已跳过 {} 字: session={}",
                                    thinkingDelta.length(), recording.uid);
                            continue;
                        }
                        recording.thinking(thinkingDelta);
                        emit(channel, RunEventType.THINKING, thinkingDelta);
                    } else if ("text".equals(b.get("type"))) {
                        String rawText = str(b.get("text"));
                        if (aggregateTail && rawText.length() > 0 && recording.answerContains(rawText)) {
                            continue;
                        }
                        // 正文开始 = 这一段思考收场：补一条 thinking_done，前端据此停转圈并折叠就地那段
                        closeThinkingFrame(channel, recording);
                        ModelOutputParser.Delta parsed = recording.related.feed(rawText);
                        // 同一个 delta 里可能同时有过程与正文：必须按原文先后下发（processFirst），
                        // 否则「过程独白紧跟正文」被拆到正文后面，过程块就劈成两段。
                        boolean processFirst = parsed.processFirst();
                        if (processFirst) emitProcess(channel, recording, parsed.process());
                        if (!parsed.text().isEmpty()) {
                            recording.answer(parsed.text());
                            emit(channel, RunEventType.TOKEN, parsed.text());
                        }
                        // 过程独白（<process> 标签内）按增量实时下发 PROCESS：正文走 TOKEN，
                        // 过程另走一条通道，前端 onProcess 累积成「执行过程」灰字块。收尾帧在 finish() 里补齐。
                        if (!processFirst) emitProcess(channel, recording, parsed.process());
                    } else if ("tool_use".equals(b.get("type"))) {
                        // 入参还在流式生成：先把「这一步在跑」发给前端（见 PREPARING_MIN_CHARS 那段说明）。
                        // 真正的 tool/requested 随后到达时，前端把同名早产卡升级成正式卡，不会再推第二张。
                        noteToolUseDelta(channel, recording, b);
                    }
                }
            }
            case "tool/requested" -> {
                // 正式调用来了：早产卡状态收摊，后面不再发进度帧（前端收到本帧会把那张卡升级成正式卡）
                recording.clearPreparing();
                // 调工具也是「这一段想完了」的信号：不在这儿收场，思考段会一直挂着转圈直到下一段正文出现，
                // 而时间线里这段思考也会压到工具卡后面（区间是按关闭时刻写的，顺序即到达顺序）
                closeThinkingFrame(channel, recording);
                // 载荷形状：{toolCallId, call:{type,id,name,input}} —— 名字在 call.name 里，
                // 不在顶层；按顶层取会拿到空串，工具卡就成了没有名字的空白行
                recording.lastToolName = toolNameOf(data.get("call"));
                Object requestedInput = toolInputOf(data.get("call"));
                // 必须序列化成 JSON：咨询卡片按入参里的 agentId 取那一份子对话，
                // 用 Map.toString() 就得到 "{agentId=xxx}" 这种 Java 形状，解析必然失败
                recording.lastToolInput = com.alibaba.fastjson2.JSON.toJSONString(requestedInput);
                // 按这一次调用登记入参与开始时刻：一步里并发两把工具时，共用字段会被后到的盖掉（见 toolInputByKey）
                String callKey = Recording.toolCallKey(toolCallIdOf(data), recording.lastToolName);
                recording.toolInputByKey.put(callKey, recording.lastToolInput);
                // 收集模型实际发出的检索词：search_knowledge 的 query 入参即它真去搜的短语
                if (requestedInput instanceof Map<?, ?> in) {
                    if ("search_knowledge".equals(recording.lastToolName)) {
                        Object q = in.get("query");
                        if (q != null && !String.valueOf(q).isBlank()) recording.searchQueries.add(String.valueOf(q).trim());
                    }
                    // 计划正文只出现在 plan_write 的 content 入参里（plan_exit 只有一句 summary）
                    if ("plan_write".equals(recording.lastToolName)) {
                        String content = str(in.get("content")).trim();
                        if (!content.isEmpty()) recording.planMarkdown = content;
                    }
                }
                long beganAt = recording.markToolStart(callKey);
                recording.toolCall(recording.lastToolName, "start", recording.lastToolInput, 0L, beganAt, 0L, null);
                // 进行中必须是 "start"（不是 "running"）：前端 onToolStatus 只认 status==='start' 才建/合并
                // 实时工具卡。发 "running" 会让前端把这张当终态卡 push 出去，随后的 done 又找不到可合并的
                // start 卡再 push 一张——一次调用渲染成两张，其中停在 "running" 的那张显示为失败(❌)。
                emit(channel, RunEventType.TOOL_STATUS,
                        toolCard(recording.uid, recording.lastToolName, "start", 0L, beganAt, 0L, recording.lastToolInput, null, null,
                                recording.hooks.step().index()));
            }
            case "tool/result" -> {
                // 兜底收摊：没有配上的 tool/requested 时（从游标中途接流）也别让早产进度帧继续发
                recording.clearPreparing();
                String name = toolNameOf(data.get("message"));
                if (name.isBlank()) name = recording.lastToolName;
                // 入参与耗时都按这一次调用取回：读共用那份就是「甲张卡的入参是乙次调用的参数」
                String callKey = Recording.toolCallKey(toolCallIdOf(data), name);
                String args = recording.toolInputByKey.remove(callKey);
                if (args == null) args = recording.lastToolInput;   // 配不上对应的 requested（如从游标中途接流）才沿用
                // 失败判定：优先读 ToolResultBlock 结构化的 state 字段（JSON 值 "error"），
                // 不再依赖 Map.toString() 拼出的 "STATE=ERROR" 字符串形状（序列化一变就漏判）。
                boolean failed = toolResultFailed(data);
                // 输出文本只取一次：落库那份和 SSE 那一帧必须是同一串，否则刷新前后两张卡说的不是一件事
                String resultText = toolResultText(data);
                // 进度账：失败/被拒的一步没有让任何东西向前走——换一个参数再失败也还是原地踏步
                recording.progress().note(name, args, resultText, failed || toolResultDenied(data));
                // 耗时＝墙钟窗口扣掉这段等待：提问卡挂三分钟不是「这个工具跑三分钟」，是「用户读了三分钟」。
                // 扣掉的那段另发一份 waitedMs：整段跨度减它才剩机器时间，只在单卡上扣会留在区间中间出不去。
                long beganAt = recording.takeToolStart(callKey);
                // 扣账与记账分开：这张卡自己的耗时要把盖住它的等待减干净（同一步里别家挂卡也一样被闸门盖住），
                // 而对外那份 waitedMs 只在挂卡那一张上出现——跨度按各张之和减，记两张就扣两遍。
                long covered = recording.takeToolWait(callKey);
                long charged = recording.takeToolCharge(callKey);
                long elapsed = beganAt <= 0L ? 0L
                        : Math.max(0L, System.currentTimeMillis() - beganAt - covered);
                java.util.Map<String, Object> subChat = recording.toolCall(name, failed ? "error" : "done", args, elapsed, beganAt, charged, resultText);
                emit(channel, RunEventType.TOOL_STATUS, toolCard(recording.uid, name, failed ? "error" : "done",
                        elapsed, beganAt, charged, args, resultText, subChat, recording.hooks.step().index()));
            }
            case "tool/decision" -> {
                // 权限判定由框架说出（字段 decision=allowed|denied）：拒绝必须让用户看见，
                // 否则「这个能力没生效」会被读成没反应
                if (str(data.get("decision")).toUpperCase().contains("DENY")) {
                    channel.emit(RunEventType.WARN, "该操作被权限档位拒绝：" + recording.lastToolName);
                }
            }
            case "plan/changed" -> {
                // 问渠的计划卡认两种帧：plan（整份步骤列表）与 plan_delta（流式追加）。
                // 框架这里给的是变更后的计划，能解析成列表就发整份，否则退成增量文本
                Object plan = firstNonNull(data.get("steps"), firstNonNull(data.get("plan"), data.get("content")));
                if (plan instanceof List<?> list && !list.isEmpty()) {
                    channel.emit(RunEventType.PLAN, com.alibaba.fastjson2.JSON.toJSONString(list));
                } else if (!str(plan).isBlank()) {
                    recording.planDelta(str(plan));
                    emit(channel, RunEventType.PLAN_DELTA, str(plan));
                }
            }
            case "run/end" -> recording.runEnded = true;
            case "inbox/applied" -> {
                // 框架把插话写进会话上下文的凭证（载荷是 {commandIds:[...]}，一次可能收好几条）。
                // 记在 recording 上而不是只记在镜像里：收尾那一次全量补读传的镜像是 null，
                // 而那正是最后一条插话最可能被应用的时刻。
                // 同一刻还要给它留痕（回答时间线里的一段 + 一帧 steer）：界面上那句该长在
                // 「模型真听到它」的位置，而不是整段回答的上面或下面。
                if (data.get("commandIds") instanceof List<?> ids) {
                    for (Object id : ids) {
                        String receiptId = String.valueOf(id);
                        if (!recording.markSteerApplied(receiptId)) continue;   // 补读会重读同一条
                        com.wenqu.ai.agent.chat.TurnHandle.SteerAck ack =
                                steerAckOf(channel.sessionId(), receiptId);
                        if (ack == null) continue;
                        recording.steer(ack.text(), ack.messageId());
                        Map<String, Object> frame = new java.util.LinkedHashMap<>();
                        frame.put("text", ack.text());
                        frame.put("messageId", ack.messageId() == null ? "" : ack.messageId());
                        emit(channel, RunEventType.STEER, com.alibaba.fastjson2.JSON.toJSONString(frame));
                    }
                }
            }
            case "turn/interrupted" -> channel.emit(RunEventType.WARN, "本轮已被中断");
            // 轮内自动压缩：它跑的是两次 SUMMARY 用途的模型调用，思考与正文都被上面的 purpose 闸
            // 滤掉，不补这两帧，那二十多秒在界面上就是纯静默。
            case "compaction/start" -> channel.emit(RunEventType.COMPACT_STATE, "{\"phase\":\"start\"}");
            case "compaction/end" -> {
                // status 必须带给前端：压完了那条线要原地换成「已压缩上下文」，压砸了则整条退场——
                // 只发「结束」不发「结束得怎么样」，界面上就只能一律报喜。
                // 框架载荷是 {compactionId, status=completed|failed|cancelled}，缺键按 failed 处理。
                String status = str(data.get("status"));
                boolean done = "completed".equalsIgnoreCase(status);
                if (done) recording.compactedThisTurn = true;
                Map<String, Object> frame = new java.util.LinkedHashMap<>();
                frame.put("phase", "end");
                frame.put("status", done ? "completed" : (status.isBlank() ? "failed" : status));
                channel.emit(RunEventType.COMPACT_STATE, com.alibaba.fastjson2.JSON.toJSONString(frame));
            }
            default -> {
            }
        }
    }

    /**
     * 取工具名。两条事件把名字放在不同层级：{@code tool/requested} 在 {@code call.name}，
     * {@code tool/result} 在 {@code message.content[].name}——而 {@code message.name} 是<b>智能体名</b>，
     * 先取它会得到 "wenqu" 这种空白归属，工具卡就认不出是哪把工具收的尾。
     */
    /**
     * 工具结果是否失败：框架的 {@code tool/result} 事件载荷是 {@code {message: Msg}}，Msg 的
     * {@code content} 里是 {@code ToolResultBlock}，其 {@code state} 字段的 JSON 值为 {@code "error"}
     * （{@code ToolResultState#ERROR}，而非 {@code "ERROR"}）。
     * <p>优先按结构化字段判定；退化路径才用旧的 {@code Map.toString()} 拼出的 {@code STATE=ERROR}
     * 形状兜底（序列化实现一变就漏判，正是原实现的隐患）。
     */
    private static boolean toolResultFailed(Map<String, Object> data) {
        Object top = data.get("state");
        if (top != null && "error".equalsIgnoreCase(String.valueOf(top))) return true;
        Object message = data.get("message");
        if (message instanceof Map<?, ?> msg) {
            Object mt = msg.get("state");
            if (mt != null && "error".equalsIgnoreCase(String.valueOf(mt))) return true;
            Object content = msg.get("content");
            if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm) {
                        Object s = bm.get("state");
                        if (s != null && "error".equalsIgnoreCase(String.valueOf(s))) return true;
                    }
                }
            }
        }
        return str(data).toUpperCase().contains("STATE=ERROR");
    }

    /** 被权限档位/重复闸/步数闸拒掉的一步：结果不是干活的产出，不能算作前进。 */
    private static boolean toolResultDenied(Map<String, Object> data) {
        Object top = data.get("state");
        if (top != null && "denied".equalsIgnoreCase(String.valueOf(top))) return true;
        Object message = data.get("message");
        if (message instanceof Map<?, ?> msg) {
            Object mt = msg.get("state");
            if (mt != null && "denied".equalsIgnoreCase(String.valueOf(mt))) return true;
            Object content = msg.get("content");
            if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm) {
                        Object s = bm.get("state");
                        if (s != null && "denied".equalsIgnoreCase(String.valueOf(s))) return true;
                    }
                }
            }
        }
        return false;
    }

    private static String toolNameOf(Object node) {
        if (!(node instanceof Map<?, ?> map)) return "";
        Object content = map.get("content");
        if (content instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> bm && bm.get("name") != null) return String.valueOf(bm.get("name"));
            }
        }
        Object name = map.get("name");
        return name == null ? "" : String.valueOf(name);
    }

    /** 从 tool_use 块里取本次调用的入参（确认卡要展示「要执行什么」，否则用户只能凭空批）。 */
    private static Object toolInputOf(Object node) {
        if (node instanceof Map<?, ?> map) {
            Object input = map.get("input");
            if (input != null) return input;
            Object content = map.get("content");
            if (content instanceof List<?> blocks) {
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm && bm.get("input") != null) return bm.get("input");
                }
            }
        }
        return null;
    }

    /** 框架待办（一等公民）→ 问渠确认卡；审批号↔会话的归属登记好，答复端点靠它找回。 */
    private void announcePending(Map<String, SessionEvent> pending, ChatCommand cmd, RunChannel channel,
                                 Set<String> announced, String turnId, Recording recording) {
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
                // 计划模式的退出确认走问渠的计划卡（plan_approval），不是普通工具审批卡：
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
                // 血缘随 plan_approval 一起下发：这张新计划是基于哪一版旧计划、带过来多少执行进度。
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
                channel.emit(RunEventType.PLAN_APPROVAL, com.alibaba.fastjson2.JSON.toJSONString(planCard));
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
            if (!"confirmation".equals(kind)) {
                Object call = firstNonNull(data.get("call"), data);
                String askPayload = askCard(entry.getKey(), toolInputOf(call), tool);
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
            displayNames.putDisplayFields(approvalCard, cmd.getUserId(), tool);
            // 工具表没收过这个名字（MCP 动态工具常见）：补一句说明，让用户至少知道这张卡在问什么，
            // 而不是光秃秃一个英文名——但绝不拿工具名冒充展示名（前端会把它当「后端认真取过」）
            if (!approvalCard.containsKey("hint")) {
                approvalCard.put("hint", "执行前请确认这一步");
            }
            channel.emit(RunEventType.APPROVAL_REQUIRED,
                    com.alibaba.fastjson2.JSON.toJSONString(approvalCard));
            sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), tool, args);
            // 审批卡同样等人：正文带展示名（与那张卡同源的一个词）。名字没收过就只报会话，
            // 绝不拿裸工具名冒充展示名
            turnNotify.onApprovalPending(cmd, entry.getKey(), str(approvalCard.get("label")));
        }
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
        if (planMarkdown != null && !planMarkdown.isBlank()) return planMarkdown.trim();
        String fallback;
        if (input instanceof Map<?, ?> in) {
            fallback = str(in.get("summary")).trim();
        } else {
            // 入参本身就是一段文字（框架改了 inputSchema，或模型把正文直接塞进了 plan_exit）
            fallback = str(input).trim();
        }
        if (!fallback.isEmpty()) return fallback;
        // 有 plan_exit 但没落过 plan_write：说清「这里没有计划可看」就行，内部原因是哪个工具没调
        // 归日志（见 announcePending），不印在用户要点批准的卡片上
        return NO_PLAN_BODY;
    }

    /** 计划正文取不到时给用户的那句话（同时也是 {@code announcePending} 里「该记一条日志」的判据） */
    static final String NO_PLAN_BODY = "（这一轮没有取到计划内容，可以直接重试，或把要求写得更具体一些）";

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
    private io.agentscope.core.model.GenerateOptions generateOptions(ChatCommand cmd, String ref, int maxOutput) {
        io.agentscope.core.model.GenerateOptions.Builder b =
                io.agentscope.core.model.GenerateOptions.builder()
                        .executionConfig(modelExecutionConfig(MODEL_MAX_ATTEMPTS));
        // 登记了最大输出才下发 max_tokens（0=未登记，交厂商默认）：不设的话单次补全上限就是
        // 厂商默认（token-plan 族为 8192），模型内联大产物必被截断成残缺参数。
        if (maxOutput > 0) b.maxTokens(maxOutput);
        String thinkLevel = cmd.getReasoningLevel() != null ? cmd.getReasoningLevel()
                : modelRegistry.defaultReasoningLevelOf(ref);
        boolean thinkingOn = cmd.isDeepThink()
                || ModelRegistryService.THINK_ALWAYS.equals(modelRegistry.referenceThinking(ref));
        if (!thinkingOn) {
            b.temperature(configService.getDouble("chat.temperature"));
            // 未勾深度思考 ≠ 一言不发：DeepSeek V4／豆包这类网关<b>默认开思考</b>的模型，
            // 不显式下发关闭字段就吃网关默认——界面上「思考强度：未开启」、实际单轮照烧
            // 几万 reasoning token。与辅助链路（auxGenerateOptions）同一条口径：
            // reasoningOffBody 只对登记为「可开关思考」的模型给关闭方言；恒思考（R1/QwQ）、
            // 未识别方言、OpenAI 系与 GLM（拒绝 disabled，400）都返回空 Map，行为与不下发一致。
            for (Map.Entry<String, Object> e : modelRegistry.reasoningOffBody(ref).entrySet()) {
                b.additionalBodyParam(e.getKey(), e.getValue());
            }
            return b.build();
        }
        if (thinkLevel == null) {
            // 与手写侧同一件事：没登记档位就没有可映射的方言字段（思考开关本身走模型的默认行为）
            return b.build();
        }
        // reasoning_effort 走原生 setter：框架把它放进 additionalBodyParams 会和内部序列化撞成重复字段
        String effort = modelRegistry.reasoningEffortValue(ref, thinkLevel, true);
        if (effort != null) b.reasoningEffort(effort);
        Map<String, Object> thinkBody = modelRegistry.reasoningExtraBody(ref, thinkLevel, true);
        for (Map.Entry<String, Object> e : thinkBody.entrySet()) {
            b.additionalBodyParam(e.getKey(), e.getValue());
        }
        if (effort != null || !thinkBody.isEmpty()) {
            log.info("[REASONING] 思考强度已下发（框架引擎）: model={} level={} effort={} extra={}",
                    ref, thinkLevel, effort, thinkBody.keySet());
        } else {
            log.warn("[FAIL-LOUD] 模型登记了思考强度档位但网关无强度方言，未下发: model={} level={}",
                    ref, thinkLevel);
        }
        return b.build();
    }

    /**
     * 辅助调用（框架的记忆提炼与上下文压缩）那一份请求参数：<b>把思考关掉</b>，其余照最小集。
     * <p>为什么要单独一份：这两类调用由框架直接拿建实例时那份模型去发（调用点传的是
     * {@code options=null}，只吃模型实例的 configuredOptions），所以「用户这一轮开了深度思考」会
     * 一并带进摘要与记忆里，凭空多出一大段 reasoning token，折完就扔。
     * 摘要要的是把话压短，不是想清楚。
     * <p>{@code reasoningOffBody} 只在模型登记为「可开关思考」时给关闭方言；<b>恒思考那一族
     * （R1／deepseek-flash 等）压根没有关的开关</b>，返回空 Map 时这里就只发最小集（不猜方言字段，
     * 发了会被网关 400 拒掉）。这一族确实省不掉，至少不再
     * 把主链的「思考强度档位」也叠上去。
     * <p>温度沿用设置页那一个值：这两类调用本来就跟着问答走，不为它们另开一档。
     * <p><b>刻意不下发登记的 max_tokens</b>（与主链 {@code generateOptions} 唯一的参数差）：
     * 摘要/压缩的输出天然短，厂商默认上限（8192 级）在这里是 runaway 护栏而非瓶颈——
     * 放大到登记值只会在恒思考族上给失控推理更大的燃烧空间，换不来任何收益。
     */
    private io.agentscope.core.model.GenerateOptions auxGenerateOptions(ChatCommand cmd, String ref) {
        io.agentscope.core.model.GenerateOptions.Builder b =
                io.agentscope.core.model.GenerateOptions.builder()
                        .temperature(configService.getDouble("chat.temperature"))
                        // 辅助调用少一次重试就少挂一整段静默：主链撞 429 值得等，摘要晚一帧没人等
                        .executionConfig(modelExecutionConfig(AUX_MODEL_MAX_ATTEMPTS));
        int offApplied = 0;
        for (Map.Entry<String, Object> e : modelRegistry.reasoningOffBody(ref).entrySet()) {
            b.additionalBodyParam(e.getKey(), e.getValue());
            offApplied++;
        }
        if (offApplied == 0 && ModelRegistryService.THINK_ALWAYS.equals(modelRegistry.referenceThinking(ref))) {
            // 关不掉且这一族恒思考：说清楚它在，别让它冒充「已经省下了」
            log.info("[agentx] 模型 {} 恒思考、无关闭方言，辅助调用（记忆/压缩）仍会带 reasoning", ref);
        }
        return b.build();
    }

    /**
     * 单次模型调用的超时与重试（框架原生口子 {@code GenerateOptions.executionConfig}）。
     * <p><b>这条闸管的是「静默」不是「总时长」</b>：框架把它挂在响应流的 {@code timeout()} 上
     * （{@code ModelUtils.applyTimeoutAndRetry}），逐块复位——上游还在吐字就不算超时，停住不动才算。
     * <p>为什么要显式设：框架默认 {@code MODEL_DEFAULTS} 的静默上限是 5 分钟，而本机事件日志里
     * 26,850 个真实块间隔的最大值是 <b>0.891 秒</b>（p99=90ms，没有一段超过 30 秒）——5 分钟那道
     * 默认线保护不了任何人，只会把「这条流已经死了」拖成用户屏幕上五分钟的空转，再乘上重试次数。
     * <p>{@code mergeConfigs(这份, MODEL_DEFAULTS)} 只覆盖我点名的三项，退避曲线与重试判据仍取框架
     * 那份（{@code RETRYABLE_ERRORS}：429/5xx/超时/IO 才重试，400/401 直接失败——把 401 重试三遍
     * 只是让额度不足晚三分钟才说出口）。重试还有一条框架自带的保护：已经流出过可见内容就不重试，
     * 免得同一句话在气泡里印两遍。
     */
    private io.agentscope.core.model.ExecutionConfig modelExecutionConfig(int maxAttempts) {
        io.agentscope.core.model.ExecutionConfig.Builder b =
                io.agentscope.core.model.ExecutionConfig.builder()
                        .maxAttempts(maxAttempts)
                        .retryOn(io.agentscope.core.model.ExecutionConfig.RETRYABLE_ERRORS);
        int stallMs = configService.getInt("chat.modelStallTimeoutMs", MODEL_STALL_TIMEOUT_MS_DEFAULT);
        if (stallMs > 0) b.timeout(java.time.Duration.ofMillis(stallMs));
        // 0=不限制：这一项留给框架 MODEL_DEFAULTS 那 5 分钟，主链完全不设静默闸的场景是「上游就是慢」
        return io.agentscope.core.model.ExecutionConfig.mergeConfigs(
                b.build(), io.agentscope.core.model.ExecutionConfig.MODEL_DEFAULTS);
    }

    /**
     * 记忆提炼配置：只在平台总闸与个人开关都开着时装配（见 {@code buildAgent} 的记忆闸）。
     * 任一关掉时走框架 {@code disableMemoryHooks()}：flush 与后台维护都不注册，模型侧也不会
     * 再收到「问答后自动提炼」的引导——只把 {@code FlushTrigger} 改成 never 会留下这句假承诺，
     * 后台合并也照跑。已经沉淀下来的 {@code MEMORY.md} 仍照常注入，与两处界面
     * 「仅关生成，不关注入」的说明对齐。
     *
     * <p>三处口径：① default 的每轮 ALWAYS 走可配节流（默认 10 分钟），否则每轮问答
     * 都多一次主模型提炼调用；② prompt 追加问渠规则（中文、只记持久事实、不记密钥/探针）；
     * ③ 记忆模型包 MeteredModel（kind=memory），成本进台账。{@code MEMORY.md} 的硬上限
     * 不在这里——它落在写入文件系统那一层（{@code MemoryCappedFilesystem}）。
     * <p>进来的模型是 {@code auxModel}（关思考那一份，见 {@link #auxGenerateOptions}）：提炼一段
     * 记忆要的是把话记下来，不需要它先推理两千 token。
     *
     * <p>容量上限固定 3000 token：consolidation 的提示词目标按它的 2/3
     * 推导（3000 → 2000），不单列「目标上限」——两个数要人肉保证大小关系，配反了
     * 每次合并都会被裁。日账保留期同理沿用框架默认 90 天，不单独配置。
     */
    private io.agentscope.harness.agent.memory.MemoryConfig memoryConfig(
            ChatCommand cmd, io.agentscope.core.model.Model auxModel, String modelRef) {
        io.agentscope.core.model.Model memoryModel = new MeteredModel(auxModel, usageLedger, quotaService, contentSafety,
                com.wenqu.ai.util.UsageAttr.of(cmd.getUserId(), cmd.getSessionId(), null, "memory",
                        null, cmd.getShareId()), modelRef);
        int maxMdTokens = Math.max(500, ConfigDefaults.MEMORY_MAX_MD_TOKENS);
        int minutes = ConfigDefaults.MEMORY_FLUSH_THROTTLE_MINUTES;
        return io.agentscope.harness.agent.memory.MemoryConfig.builder()
                .model(memoryModel)
                .flushPrompt(com.wenqu.ai.memory.MemoryPrompts.flushPrompt())
                .consolidationPrompt(com.wenqu.ai.memory.MemoryPrompts.consolidationPrompt())
                // 目标值由硬上限推导（默认 3000 → 2000），留 1/3 余量；下限 500 防 0。
                .consolidationMaxTokens(Math.max(500, maxMdTokens * 2 / 3))
                .flushTrigger(minutes <= 0
                        ? io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger.always()
                        : io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger.throttled(
                                java.time.Duration.ofMinutes(minutes)))
                .build();
    }

    /** 长期记忆写入口径：平台总闸 ∧ 个人开关（游客/子轮在建实例时另有硬闸，不走这里）。 */
    private boolean memoryOn(ChatCommand cmd) {
        return configService.getBoolean("memory.enabled", true)
                && orgService.memoryFlushEnabled(cmd.getUserId());
    }

    /**
     * 压缩配置。问渠只把「按消息条数触发」这一道关掉（{@code triggerMessages(0)}），其余走框架默认
     * （动态 token 阈值、逐字留最近 20 条、prune 开）——框架那条本身就是一条会跑的滚动压缩，
     * 问渠不再另算一份摘要，两边各算就是两份「已压缩上下文」互相冒充。
     * <p><b>为什么取消条数那道线</b>：它数的是整条会话累计的对话消息（一次工具调用＝ASSISTANT 调用
     * ＋TOOL 结果两条），25 次工具调用就撞满 50 条——而它和「窗口还剩多少」没有关系。窗口那道线
     * 算出来是 95.6 万 token（1M 档），条数那道线总是先把它抢掉，结果是长会话被反复折成摘要、
     * 而容量条上明明还空着。折完尾部只留 ≤8K token，条数又从个位数重新数起，等于越干越勤。
     * 真把上游窗口顶爆了有兜底：{@code CompactionMiddleware#overflowRecovery} 认 provider 的
     * context-overflow 错，当场强制折一次并重试同一轮，所以取消条数那道线不会变成「直接报错」。
     * <p>手动 {@code /compact} 在这一路会话上留过请求时，本次建实例强制「有一条就先压」，
     * 让那次点击真的在下一次提问前把旧对话折进去（框架没有「就地压一次」的入口，压缩长在推理循环前）。
     * <p>记忆关闭（平台/个人）或游客/子轮时显式 {@code flushBeforeCompact(false)}：框架的
     * {@code FlushTrigger.never()} 只管每轮收尾，压缩触发时仍会按默认 {@code true} 提炼一次
     * （{@code MemoryFlushMiddleware} 注释原文：*"The CompactionMiddleware and overflow-recovery
     * paths still run their own flush when they fire."*）。压缩前的 flush 不走 MemoryConfig，
     * 只能在这里关；溢出兜底在 CompactionMiddleware 内复制同一开关，因此一并生效。
     * <p>摘要模型包 MeteredModel（kind=compress）：压缩平时不常跑，但一跑就是一次不小的输入，
     * 不记账就永远不知道它花在哪。进来的模型是 {@code auxModel}（关思考那一份，见
     * {@link #auxGenerateOptions}）——辅助调用带上 reasoning 也是折完就扔的。
     */
    private io.agentscope.harness.agent.memory.compaction.CompactionConfig compactionConfig(
            ChatCommand cmd, io.agentscope.core.model.Model auxModel, String modelRef) {
        io.agentscope.core.model.Model compressModel = new MeteredModel(auxModel, usageLedger, quotaService, contentSafety,
                com.wenqu.ai.util.UsageAttr.of(cmd.getUserId(), cmd.getSessionId(), null, "compress",
                        null, cmd.getShareId()),
                modelRef);
        io.agentscope.harness.agent.memory.compaction.CompactionConfig.Builder b =
                io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder()
                        .model(compressModel)
                        // 0=不按条数触发（框架判据是 triggerMessages > 0 才比条数），只留窗口那道线
                        .triggerMessages(0);
        // 只关 MemoryConfig 的 per-call flush 会漏掉压缩前这一次；游客/子轮本就不写记忆，一并关。
        if (cmd.isGuestMode() || cmd.branchTurn() || !memoryOn(cmd)) {
            b.flushBeforeCompact(false);
        }
        String key = sessions.key(cmd);
        String forcedInstruction = sessions.takeCompactRequest(key);
        if (forcedInstruction == null) {
            return b.build();
        }
        // 强制压缩只应作用「下一次提问」那一个实例：实例按会话常驻，triggerMessages(1) 若不复位会被
        // 这一条会话的后续每一轮继承（每轮都压）。这里标「下次开轮重建」，让下一轮回到默认压缩配置。
        // 摘要要求走同一道生命周期：它烤进这一个实例的提示词，实例一换就回默认，不会串到之后的轮次。
        sessions.markRebuildAfterCompact(key);
        if (!forcedInstruction.isBlank()) {
            b.summaryPrompt(FrameworkSessions.summaryPromptWith(forcedInstruction));
        }
        return b.triggerMessages(1).build();
    }

    private HarnessAgent buildAgent(ChatCommand cmd, ExecutionPolicy policy,
                                    ModelRegistryService.ModelRoute route,
                                    WenquAgentxTools.TurnHooks hooks, AgentBinding binding,
                                    AgentxTurnSlot slot, boolean sandboxEnabled) {
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
                // MCP 是外部连接：聊天档默认不挂（纯问答不等外部连接）；需要外部工具的部署可开
                // chat.mcpEnabled，仍受 mcp.turnWaitMs 有界等待。
                sandboxEnabled || configService.getBoolean("chat.mcpEnabled", false));
        // 目标模式的控制工具常驻注册：实例按会话常驻，目标可能在建实例之后才建立，
        // 不能等到目标建立再往 Toolkit 里加工具（那需要重建实例）。非目标轮调用会落空返回。
        holders.add(new GoalTools(goalService, slot));
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
                .generateOptions(generateOptions(cmd, hooks.modelRef(), binding.maxOutput()))
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
                .generateOptions(auxGenerateOptions(cmd, hooks.modelRef()))
                .build();
        io.agentscope.core.model.Model chatModel = new MeteredModel(baseModel, usageLedger, quotaService, contentSafety,
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
                        + localExecutorEnvironment(cmd)
                        + (subagentTargets.isEmpty() ? "" : tools.dispatchDiscipline())
                        + PlanCopy.guidance()
                        + com.wenqu.ai.agent.chat.ArtifactPrompt.guidance()
                        + com.wenqu.ai.memory.MemoryPrompts.replyDiscipline()
                        + com.wenqu.ai.agent.chat.RelatedPrompt.line())
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
                // 历史计划卡的多版本落库仍由 Recording.planVersions 负责，与意图判定无关。
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
                .compaction(compactionConfig(cmd, auxModel, hooks.modelRef()))
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
                // 工具说明精简：换的是请求里那份 ToolSchema 的描述文本，注册表、参数 schema、
                // 只读档位与权限判定一律不动（关掉即恢复完整说明）。开关每次调用现读，
                // 因为实例按会话常驻、middleware 只能在 build 期挂上——见类注释。
                .middleware(new AgentxToolDescriptionCompact(
                        () -> configService.getBoolean("chat.toolDescriptionCompact", true)))
                // 前缀稳定：框架把会话 id / 容器 id 拼在 system prompt 里，每个新会话都会让
                // 供应商的 prefix cache 从这里断开。只改模型看到的文本，不动运行时状态。
                .middleware(new PrefixStabilizerMiddleware(
                        () -> configService.getBoolean("chat.prefixStabilizer", true)))
                // 异步升级首轮：工作区还没好，不把文件/命令工具暴露给模型，避免 scratch 写了带不进沙盒。
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
                .disableToolsConfig()
                // 沙箱与文件系统：**框架接管**。
                // 关键：框架的工具在 **build 期**绑定文件系统——
                //   HarnessAgent:2895 `new FilesystemTool(filesystem, pathNormalizer)`
                //   HarnessAgent:2903 `new ShellExecuteTool(sandbox)`（filesystem 当沙箱用）
                // 两者都从 builder 字段取，**不看 RuntimeContext**。
                // 往 ctx 里 put AbstractFilesystem 无效，必须用 abstractFilesystem(...) 在 build 期传进去。
                .abstractFilesystem(frameworkFilesystem(cmd,
                        policy.sandboxMode() == com.wenqu.ai.agent.permission.SandboxMode.DANGER_FULL_ACCESS,
                        sandboxEnabled))
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
        } else if (cmd.branchTurn() || !memoryOn(cmd)) {
            agentBuilder.disableMemoryHooks();
        } else {
            agentBuilder.memory(memoryConfig(cmd, auxModel, hooks.modelRef()));
        }
        io.agentscope.harness.agent.DistributedStore distributedStore = distributedStore();
        if (distributedStore != null) {
            agentBuilder.distributedStore(distributedStore);
        }
        // 消息总线/异步工具注册表换成无操作实现：问渠不使用这两个通道（steer 走 SessionInbox，
        // 异步工具未启用），不换的话框架回落到沙盒文件系统版——InboxMiddleware 每轮在模型调用前
        // 做 inboxDrain + findStale，两次判定各打一次到沙盒容器的同步 HTTP，
        // 全部换回空列表（NoopBusComponents 类注释有完整机制说明）。
        agentBuilder.messageBus(NoopBusComponents.MESSAGE_BUS);
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
        HarnessAgent agent = agentBuilder.build();
        alignWriteFileDescription(agent);
        alignExecuteDescription(agent);
        applyNativeToolGuards(agent, slot, cmd);
        return agent;
    }

    /**
     * 框架原生文件/命令工具的统一护栏（注入防护补全）。
     *
     * <p>与 {@link #alignWriteFileDescription} 同一做法：build 之后按同名重新注册。框架在 build 期
     * 把 {@code FilesystemTool / ShellExecuteTool} 注册进 toolkit 的副本，问渠只能在这个副本上覆盖；
     * 包一层后名字、schema、只读位、执行全部不变，只多「结果加信封」与「污点时强制确认」。
     *
     * <p>只包会返回外部内容的几把：read/grep/glob/list/execute。write/edit 只管越界确认，
     * 不包结果（它们返回的是「成功/失败」，不是数据）。
     */
    private void applyNativeToolGuards(HarnessAgent agent, AgentxTurnSlot slot, ChatCommand cmd) {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) return;
        List<String> roots = workspaceRootsOf(cmd);
        // 游客分享/API Key 轮没有审批卡通道：污点触发时直接拒，别挂一张没人答的卡等超时
        boolean canAsk = !cmd.isGuestMode() && !cmd.isApiKey() && interactiveTurn(cmd);
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.READ_FILE,
                AgentxNativeToolGuard.Kind.DATA);
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.GREP_FILES,
                AgentxNativeToolGuard.Kind.DATA);
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.GLOB_FILES,
                AgentxNativeToolGuard.Kind.DATA);
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.LIST_FILES,
                AgentxNativeToolGuard.Kind.DATA);
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.EXECUTE,
                AgentxNativeToolGuard.Kind.EXEC);
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.WRITE_FILE,
                AgentxNativeToolGuard.Kind.WRITE);
        wrapNative(toolkit, slot, roots, canAsk, FrameworkToolLabels.FilesystemToolNames.EDIT_FILE,
                AgentxNativeToolGuard.Kind.EDIT);
    }

    private static void wrapNative(Toolkit toolkit, AgentxTurnSlot slot, List<String> roots,
                                   boolean canAsk, String name, AgentxNativeToolGuard.Kind kind) {
        AgentTool tool = toolkit.getTool(name);
        if (tool == null || tool instanceof AgentxNativeToolGuard) return;
        toolkit.registerAgentTool(new AgentxNativeToolGuard(tool, kind, slot, roots, canAsk));
    }

    /**
     * 工作区根的绝对路径（用于「越界写」的词法判断）。
     * 绑了本机目录就取源文件夹；服务器沙盒/无工作区返回空表——空表时任何绝对路径都按越界处理，
     * 方向偏保守（最多多一张确认卡），不会把外部路径误判成工作区内。
     */
    private List<String> workspaceRootsOf(ChatCommand cmd) {
        List<String> out = new ArrayList<>();
        try {
            com.wenqu.ai.model.Project p = projectService.projectOfSession(cmd.getSessionId());
            if (p == null || !projectService.isLocal(p)) return out;
            com.wenqu.ai.model.ProjectRoot primary = projectService.primaryRootOf(p);
            // 主根必须排第一：相对路径（含 ..）按主根解析，AgentxDestructiveCommands 取 roots.get(0)
            if (primary != null && primary.getPath() != null && !primary.getPath().isBlank()) {
                out.add(realRoot(primary.getPath()));
            }
            for (com.wenqu.ai.model.ProjectRoot r : projectService.rootsOf(p)) {
                if (r == null || r.getPath() == null || r.getPath().isBlank()) continue;
                if (primary != null && r.getPath().equals(primary.getPath())) continue;
                out.add(realRoot(r.getPath()));
            }
        } catch (Exception e) {
            log.warn("[agentx] 工作区根解析失败（越界写确认按无根处理）session={}: {}",
                    cmd.getSessionId(), e.getMessage());
        }
        return out;
    }

    private static String realRoot(String raw) {
        String p = raw == null ? "" : raw.trim();
        if (p.isEmpty()) return p;
        try {
            return Path.of(p).toRealPath().toString();
        } catch (Exception e) {
            return Path.of(p).toAbsolutePath().normalize().toString();
        }
    }

    /**
     * 把 {@code write_file} 的 schema 说明改成与问渠实际行为一致的那句。
     *
     * <p>框架 2.1.0-BETA1 的说明仍写着 "Write content to a <b>new</b> file"，而问渠侧把
     * {@code write} 定为「无则新建、有则整体替换」（{@code ProvisionerSandboxFilesystem#write}，
     * 手册 {@code 09-技能与沙盒} 同一口径）。说明与行为不一致不是显示问题：模型是照着说明行事的，
     * "new file" 等于叫它换路径，一份交付会被拆成多段留在沙盒里。
     *
     * <p><b>只能在 build 之后改</b>：框架 {@code HarnessAgent:2530} 先 {@code this.toolkit.copy()}，
     * 再往那个副本上注册文件工具（{@code :2895}）——build 前注册的同名工具会被这次注册盖掉
     * （{@code ToolRegistry#registerTool} 是后写赢）。{@code getToolkit()} 返回的正是那个副本，
     * 此处按同名重新注册即完成替换。
     *
     * <p>{@link DescriptionOverrideTool} 只改 {@code getDescription()}，名字、入参表、只读位、
     * 执行本身全部转给框架原实现——控制流仍归框架，问渠不另造一把写文件的工具。
     * 说明已是我们要的那句时直接跳过：本方法每轮都会走，不挡住就会一层层套娃。
     */
    private static void alignWriteFileDescription(HarnessAgent agent) {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            return;
        }
        AgentTool writeFile = toolkit.getTool(FrameworkToolLabels.FilesystemToolNames.WRITE_FILE);
        if (writeFile == null || WRITE_FILE_DESCRIPTION.equals(writeFile.getDescription())) {
            return;
        }
        toolkit.registerAgentTool(new DescriptionOverrideTool(writeFile, WRITE_FILE_DESCRIPTION));
    }

    /** {@code write_file} 该对模型说的那句（与问渠实现同真）。 */
    private static final String WRITE_FILE_DESCRIPTION =
            "Write content to a file, creating parent directories if needed. "
                    + "If the file already exists its whole content is replaced - use edit_file to change part of it.";

    /**
     * 给框架 {@code execute} 的说明补一句「退出码取整条命令链的最后一条」。
     *
     * <p>模型删完文件后用 {@code ls <path>} 验证，文件不在时 {@code ls} 退出 1，
     * 整条命令因此被判失败（卡片红色），而删除其实已经成功。工具状态只能看退出码，
     * 不能从输出里猜「其实成功了」——所以把正确的验证写法教给模型：
     * {@code test ! -e <path>} 在文件不存在时退出 0。
     *
     * <p>顺带说明工作区根：执行器/Sandbox 都以工作区根为 cwd，模型不必每条命令都前缀
     * {@code cd <workspace> &&}；要换目录用 {@code working_directory} 参数。
     *
     * <p>与 {@link #alignWriteFileDescription} 同一时机（build 之后、护栏之前）：
     * 先换说明，再让 {@link AgentxNativeToolGuard} 包住它；反过来会把护栏包在里面，
     * 下一轮 {@code wrapNative} 就认不出「已经包过」而重复套壳。
     */
    private static void alignExecuteDescription(HarnessAgent agent) {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) return;
        AgentTool execute = toolkit.getTool(FrameworkToolLabels.FilesystemToolNames.EXECUTE);
        if (execute == null) return;
        String description = execute.getDescription();
        if (description != null && description.contains(EXECUTE_DESCRIPTION_MARKER)) return;
        toolkit.registerAgentTool(new DescriptionOverrideTool(execute,
                (description == null || description.isBlank() ? "" : description + "\n\n")
                        + EXECUTE_DESCRIPTION_GUIDANCE));
    }

    /** 用来判断说明是否已经补过，避免每轮 build 一层层套娃。 */
    private static final String EXECUTE_DESCRIPTION_MARKER =
            "Exit status is the exit status of the LAST command";

    private static final String EXECUTE_DESCRIPTION_GUIDANCE =
            EXECUTE_DESCRIPTION_MARKER + " in the chain. "
                    + "After deleting a file, do not verify with `ls <path>`: when the path is gone `ls` exits 1 "
                    + "and the whole call is reported as failed even though the delete succeeded. "
                    + "Verify absence with `test ! -e <path> && echo deleted` (exit 0 when the path is absent). "
                    + "Commands already run at the workspace root, so a `cd <workspace>` prefix is unnecessary; "
                    + "use working_directory when a different relative directory is needed.";

    /** 只替换工具说明、其余行为原样委托的包装（见 {@link #alignWriteFileDescription}）。 */
    private record DescriptionOverrideTool(AgentTool delegate, String description) implements AgentTool {

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getParameters() {
            return delegate.getParameters();
        }

        @Override
        public Boolean getStrict() {
            return delegate.getStrict();
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return delegate.getOutputSchema();
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
        }

        @Override
        public boolean isReturnDirect() {
            return delegate.isReturnDirect();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return delegate.callAsync(param);
        }
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
    private static String interruptMarker(boolean stopped, boolean timedOut, boolean budget, boolean stalled) {
        if (stopped) return com.wenqu.ai.agent.chat.TurnRecorder.STOP_SUFFIX;
        if (budget) return com.wenqu.ai.agent.chat.TurnRecorder.BUDGET_SUFFIX;
        if (stalled) return com.wenqu.ai.agent.chat.TurnRecorder.STALL_SUFFIX;
        if (timedOut) return "\n\n> ⏹ 等待确认超时，这一轮没有继续执行";
        return com.wenqu.ai.agent.chat.TurnRecorder.TRUNCATION_SUFFIX;
    }

    /**
     * 接流快照：本轮到此刻已经产出的东西，一次交全给总线（见 {@code ChatRunBus#attach}）。
     * <p>字段名与前端 {@code applyTurnSnapshot} 一一对齐，且与「刷新后读历史」那份同源同形：
     * 正文/思考/工具卡/时间线出自 {@link Recording#snapshotFields()}，引用出自本轮引用池，产物走
     * {@code takeArtifacts} 那份不消费的副本。缺哪一项，前端只是那一段还没长出来，不会报错。
     * <p>提问/审批卡不含：它们的时限与「唤醒句柄还在不在」以库为准，前端走既有的 pending 端点恢复；
     * 计划卡例外——它有自己的运行态（草稿/待确认/退回重出/已批准），必须随快照下发，否则新页签在
     * 计划批准后的人机等待期里整张卡都不见了。也不含逐字增量，正文取缓冲本身比回放上千条 token 帧快。
     *
     * @param startedAt 本轮开轮时刻（服务端墙上钟，见 {@code followTurn} 的入参）
     * @param prepMs    开轮前准备那一段（服务端收到提问 → 开轮起表）；0 = 这一跑没有「收到提问」基准
     */
    private Map<String, Object> turnSnapshot(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                                             ModelRegistryService.ModelRoute route, Recording recording,
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

    /**
     * 计划卡的接流快照：把 Recording 的运行态翻译成前端 PlanCard 的字段形状。
     * <p>判定顺序：
     * <ol>
     *   <li>用户已裁决（approve/reject/supersede）→ 终态卡，版本链取已产各版；</li>
     *   <li>草稿非空（首版生成中 / 退回重出新稿流式）→ drafting；</li>
     *   <li>等待裁决 → pending；若 FrameworkSessions 标着 revising（修改已提交、新稿还没开始）→
     *       仍显示原稿，但锁住裁决控件并标“修改中”；</li>
     *   <li>都没有 → 不下发。</li>
     * </ol>
     * <p>{@code remainingMs} 只给时长，由前端按本机钟换算 deadline，避免两端钟差。
     */
    private Map<String, Object> planCardForSnapshot(ChatCommand cmd, Recording recording) {
        if (recording == null || !recording.hasPlanActivity()) return null;
        String sessionKey = sessions.key(cmd);
        String outcome = sessions.peekPlanOutcome(sessionKey);
        String revisionFeedback = sessions.peekPlanFeedback(sessionKey);
        boolean revising = sessions.isPlanRevising(sessionKey);
        long timeoutMs = configService.getInt("chat.planTimeoutMs", 3600000);
        java.util.List<Map<String, Object>> all = recording.planVersionsCopy();
        String draft = recording.planDraftText();

        Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("id", recording.planApprovalIdValue() == null ? "" : recording.planApprovalIdValue());
        card.put("auto", false);
        card.put("busy", false);
        card.put("editText", "");
        card.put("revising", false);
        card.put("revisingStarted", false);
        card.put("pendingFeedback", "");
        card.put("timeoutMs", timeoutMs);
        card.put("remainingMs", 0L);

        boolean finalOutcome = "approve".equals(outcome) || "supersede".equals(outcome)
                || "reject".equals(outcome);

        java.util.List<Map<String, Object>> prior = new java.util.ArrayList<>();
        if (finalOutcome) {
            if (all.isEmpty()) return null;
            Map<String, Object> last = all.get(all.size() - 1);
            for (int i = 0; i < all.size() - 1; i++) prior.add(planVersionOut(all.get(i)));
            card.put("status", "approve".equals(outcome) ? "approved"
                    : "supersede".equals(outcome) ? "superseded" : "rejected");
            card.put("plan", str(last.get("plan")));
            card.put("feedback", str(last.get("feedback")));
            card.put("versions", prior);
        } else if (!draft.isEmpty()) {
            for (Map<String, Object> v : all) prior.add(planVersionOut(v));
            card.put("status", "drafting");
            card.put("plan", draft);
            card.put("feedback", revising ? (revisionFeedback == null ? "" : revisionFeedback) : "");
            card.put("versions", prior);
            if (revising) {
                card.put("revising", true);
                card.put("revisingStarted", true);
                card.put("pendingFeedback", revisionFeedback == null ? "" : revisionFeedback);
                card.put("busy", true);
            }
        } else if (recording.planPendingNow() && !all.isEmpty()) {
            Map<String, Object> last = all.get(all.size() - 1);
            for (int i = 0; i < all.size() - 1; i++) prior.add(planVersionOut(all.get(i)));
            card.put("status", "pending");
            card.put("plan", str(last.get("plan")));
            card.put("feedback", str(last.get("feedback")));
            card.put("versions", prior);
            if (revising) {
                card.put("revising", true);
                card.put("revisingStarted", false);
                card.put("pendingFeedback", revisionFeedback == null ? "" : revisionFeedback);
                card.put("busy", true);
            } else {
                long remaining = Math.max(0L, recording.planPendingAtMs() + timeoutMs - System.currentTimeMillis());
                card.put("remainingMs", remaining);
            }
        } else {
            return null;
        }

        if (cmd.getPlanBaseId() != null && !cmd.getPlanBaseId().isBlank()) {
            Map<String, Object> from = new java.util.LinkedHashMap<>();
            from.put("cardId", cmd.getPlanBaseId());
            from.put("version", cmd.getPlanBaseVersion());
            from.put("done", cmd.getPlanBaseDone());
            from.put("total", cmd.getPlanBaseTotal());
            from.put("failed", cmd.getPlanBaseFailed());
            card.put("adjustFrom", from);
        }
        return card;
    }

    /** 版本链下发形状：只有 PlanCard/PlanViewer 读的正文与意见，不把内部的批准 ID 摆到界面数据里 */
    private static Map<String, Object> planVersionOut(Map<String, Object> v) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("plan", v == null ? "" : str(v.get("plan")));
        out.put("feedback", v == null ? "" : str(v.get("feedback")));
        return out;
    }

    /**
     * 收尾：落库、下发 done 帧、进问答台账。
     *
     * @param elapsedMs   机器耗时（墙钟扣掉人在回路的等待）——看板与台账说这个数
     * @param humanWaitMs 这一轮里用户读卡/答题累计的等待，随 done 下发供界面上单独说明
     * @param wallMs      本轮墙钟（开轮那一刻到收尾），只用来反推本轮真实开始时刻与写日志
     * @param prepMs      开轮前准备那一段（服务端收到提问 → 开轮起表）；0 = 没有请求体的入口，
     *                    此时起表基准就是开轮时刻，整轮墙钟照常下发与落库（不含到达流水线前的等待）
     */
    private void finish(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                        ModelRegistryService.ModelRoute route, String modelRef, int contextWindow, String answer,
                        Recording recording,
                        String userMessageId, boolean stopped, boolean timedOut, boolean budget, boolean stalled,
                        boolean awaiting,
                        long elapsedMs, long humanWaitMs, long wallMs, long prepMs,
                        AgentSession session, int historyBefore, String turnId,
                        Map<String, Object> planRecord) {
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
        boolean realAnswer = !body.isBlank();
        // 半程轮有过程就落库、连过程都没有就不落（与手写侧 persistPartialAnswer 同一道守卫）：
        // 「正文非空」单独判会让只跑了工具的轮整条蒸发，而真空轮落一条空消息只是污染历史。
        boolean hasProcess = !recording.toolCalls.isEmpty() || recording.thinking.length() > 0;
        boolean supersededRound = planRecord != null && "superseded".equals(planRecord.get("status"));
        // 尾标进了正文的那一支，同句话再结构化入一块（noticeText 非空即「本轮被中断」的事实）：
        // 两边都写：正文里留一份，blocks 里的 notice 给刷新/分享与渲染用。
        String noticeText = "";
        if (!realAnswer && hasProcess) {
            // 被「继续对话」取代的那一轮正文留空：计划卡角标已经写着「已被取代」，再补一句中断
            // 标注就是把同一件事说两遍（与手写引擎同一口径）
            // 只有正文本来就空的那一支才去分隔符：非空正文必须带着「\n\n」拼在尾段，
            // 少了它引用块会粘回最后一句、">" 原样印在屏幕上
            if (!supersededRound) {
                noticeText = interruptMarker(stopped, timedOut, budget, stalled).trim();
                body = noticeText;
            }
        } else if (realAnswer && (stopped || timedOut || budget || stalled)) {
            // 半程正文：标注追加在尾部（与手写侧同一口径——用户要看得见「这句只有半截」）
            String marker = interruptMarker(stopped, timedOut, budget, stalled);
            body = body + marker;
            noticeText = marker;
        }
        boolean persist = realAnswer || (hasProcess && !awaiting);
        // 本轮正文配图清单：正文里的 [图片N] 就是按这份的下标还原的（N → 第 N-1 项）。
        // 只随 SSE image 帧下发而不落库的话，表现是「当场看得见图，一刷新全变成 [图片1] 四个字」。
        List<String> turnImages = pool.rawImages();
        // 引用完整性防线：悬空角标剔除、「有来源却没标 [N]」的 fail-loud、「编号对上了但语义不支撑」的
        // LLM 自检——与手写侧同一个实现。换了运行时不该把这道答案质量防线摘掉。
        // 停止轮与「只有过程没有正文」的半程轮都不跑：那两句标注不是答案，跑了对它们就是白报一条
        // 「未标注引用」（半程轮往往还带着工具召回的来源，正好满足那条 fail-loud 的触发条件）。
        if (!stopped && !timedOut && !budget && !stalled && realAnswer) {
            // 反推本轮真实开始时刻必须用墙钟：拿机器耗时去减会把开始点往后挪，挪进用户读卡那几分钟里，
            // 这道防线读到的「本轮」就成了后半截
            long startedAtMs = System.currentTimeMillis() - wallMs;
            // 这里必须交引用串 {providerId}/{modelId} 而不是 route.modelId()：自检那次调用走
            // DynamicOpenAiChatModel，它只按引用解析路由，裸模型名一律 fail-loud——传错就是这道防线
            // 每轮静默哑火（只剩一条 WARN，正文照旧）。落库和 done 帧用裸名是展示口径，不同。
            var outcome = citations.check(body, new com.wenqu.ai.agent.context.CitationIntegrityService.Ctx(
                    cmd.getSessionId(), cmd.getUserId(), cmd.getQuestion(), modelRef,
                    pool.sources(), pool.imgIndex(), pool.imgDescIndex(),
                    pool.degradations(), pool.degradedCodes(), new java.util.HashMap<>(), startedAtMs));
            body = outcome.text();
            // 自检剔图后编号是重编过的（[图片3] 可能已变成 [图片2]）：清单必须换成 outcome 那一份，
            // 否则落库/下发的是剔除前的顺序，正文第 N 个标记会配到另一张图
            turnImages = outcome.images();
            // 自检会裁掉不支撑的来源并重编编号：池不回写，正文角标与引用面板就会错位
            pool.applyCitationOutcome(outcome);
        }
        com.wenqu.ai.agent.chat.TurnHandle handle =
                com.wenqu.ai.agent.chat.TurnRegistry.handle(cmd.getSessionId());
        String framedTurnId = handle == null ? turnId : handle.turnId();
        Map<String, Object> tokens = usageOf(session, historyBefore, pool, contextWindow);
        // 收尾这一发是终值（与 done 里的 tokens 同一份快照）；流式期间那几发见 emitLiveUsage，
        // 两拨都出自 usageOf，不做第二套估算
        // 整轮结束而思考段还开着（只思考没出正文、或被叫停）：照样补一条收尾帧，别让转圈永远挂着
        closeThinkingFrame(channel, recording);
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
        String lastRetrieved = retrievedJson(cmd, pool, recording);
        if (lastRetrieved != null) channel.emit(RunEventType.RETRIEVED, lastRetrieved);
        java.util.List<java.util.Map<String, Object>> artifactItems =
                artifactService.settleArtifacts(cmd.getSessionId());
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
        // 重新生成的分支手术（与工作流路径 ChatflowBridge 同口径）：先软删被替换的旧回答并登记
        // 版本组，新回答落库后挂同一组，刷新后才有 ‹ n/N › 可切。只在真要落库这一轮时做：
        // 等待/失败轮不落库，旧回答就不该被动。
        String replaceGroup = null;
        if (persist && cmd.getReplaceMessageId() != null && !cmd.getReplaceMessageId().isBlank()) {
            replaceGroup = sessionService.beginAnswerReplace(cmd.getReplaceMessageId());
        }
        String assistantMessageId = persist ? sessionService.appendMessage(MessageWrite.builder()
                .sessionId(cmd.getSessionId())
                .role("assistant")
                .content(body)
                .images(turnImages)
                .sources(com.alibaba.fastjson2.JSON.toJSONString(pool.sources()))
                .thinking(recording.thinkingText())
                .retrieved(lastRetrieved)
                .artifacts(artifactsJson)
                .toolCalls(recording.toolCallsJson())
                .tokens(tokens.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(tokens))
                .timeline(recording.timelineJson(body, recording.answerText()))
                .processText(recording.related.process().isEmpty() ? null : recording.related.process())
                .agentId(cmd.getAgentId())
                .agentName(agentNameOf(cmd))
                .model(route.modelId())
                .related(relatedChips.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(relatedChips))
                .plan(planRecord == null ? null : com.alibaba.fastjson2.JSON.toJSONString(planRecord))
                .turnId(framedTurnId)
                .blocks(blocksJson)
                .build()) : null;
        if (replaceGroup != null && assistantMessageId != null) {
            sessionService.setMessageVariant(assistantMessageId, replaceGroup);
        }
        // 每轮文件改动随消息行落库：只存相对路径与行数，点开差异时再找执行器现算
        if (persist && assistantMessageId != null && turnChanges != null) {
            sessionService.setMessageChanges(assistantMessageId,
                    com.alibaba.fastjson2.JSON.toJSONString(turnChanges));
        }
        // 警示条也要活过刷新：只随 warn 帧下发的话，用户当场看见「沙盒写入未执行」，
        // 刷新后就没了——而这一轮确实被沙盒挡下过一次（与 degradations 那条同一个错法）
        sessionService.saveMessageWarn(assistantMessageId, channel.lastWarn());
        // 降级条与警示条同一个错法：只随 done 帧下发一次，刷新后「本轮没咨询成其他助手」「模型换过路」
        // 整块消失。列在这里已定型：引用自检那道防线跑在落库之前。
        sessionService.persistDegradations(assistantMessageId, pool.degradations());
        // 问答台账：与手写侧 doOnComplete 同一处时机（半程轮不落台账，那是中断轮的收尾语义）。
        // 不接的表现为看板与「未命中问题」列表只统计到切换前那部分流量——分派切了运行时，
        // 运营侧的数却悄悄少了一半，而且没有任何一处会报错。
        if (persist && realAnswer) {
            qaLog.logAsync(cmd.getSessionId(), cmd.getQuestion(), body,
                    pool.sources().stream().map(x -> String.valueOf(x.get("docId"))).toList(),
                    !pool.sources().isEmpty(), elapsedMs,
                    cmd.isDeepThink(), assistantMessageId, cmd.getAgentId());
        }
        // 载荷用 JSONObject 序列化，不手工拼字符串：控制字符与非法转义序列会让整包失去合法性，
        // 本轮来源清单与外发文本会一起丢掉，「答案可溯源」在外部链路静默失效。
        // 键序与拼接版逐字一致（下游有多个消费方按名取字段，含前端与子轮回取）。
        // WriteMapNullValue 保持「显式给 null」的语义（缺键会让前端读不到）。
        List<Map<String, Object>> pendingSteers = awaiting ? null : unsentSteers(handle, turnId, recording);
        if (pendingSteers != null && pendingSteers.isEmpty()) pendingSteers = null;
        String payload = TurnDonePayload.toJson(TurnDonePayload.Input.builder()
                .finalContent(body)
                .messageId(assistantMessageId)
                .userMessageId(userMessageId)
                .turnId(framedTurnId)
                .model(route.modelId())
                .agentId(cmd.getAgentId())
                .agentName(agentNameOf(cmd))
                .sources(pool.sources())
                // 定稿配图清单（与 finalContent 的 [图片N] 编号严格同序）：流式 image 帧是剔图重编前那一份
                .finalImages(pool.sign(turnImages))
                .degradations(pool.degradations())
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
        // 智能体轮终 webhook 出站（对外能力第二批，旁路）：配置了 callback_url 的智能体把本轮结果
        // 签名推给宿主系统。挂在 done 发射之后——宿主收到的时机与屏幕上「答完了」一致。
        // 状态口径：停/stall=stopped（用户侧行为，宿主同样该知道）、超时/预算耗尽=failed、
        // 其余=completed；卡片还开着等人答的轮不算终态，不发。
        if (!awaiting) {
            String webhookStatus = (stopped || stalled) ? "stopped"
                    : (timedOut || budget ? "failed" : "completed");
            agentWebhookService.onTurnFinished(cmd, framedTurnId, webhookStatus, null,
                    body, assistantMessageId, tokens, artifactItems, elapsedMs);
        }
        // done 之后必须收通道：手写侧是 runChat 自己 completeEmitter；不收的话帧都到了、
        // 连接却挂着直到看门狗 300s 超时截断，前端「这一轮结束」要等五分钟才落地
        channel.complete();
    }

    /**
     * 本轮送进过框架会话、但<b>没被写进上下文</b>的插话（要交回前端补发的那几条）。
     * <p>按把手取：收据与落库的那条用户消息同行，都在本轮的把手上；把手已经换成别的轮（新轮已经开始、
     * 或已经被摘掉）时一条都不报——把上一轮的插话挂到这一轮的 done 上，前端会把它再发一遍。
     * <p>只在「这一跑真的结束了」之后认账（{@code run/end}）：那一跑还活着时，插话会在它下一次
     * 调模型前被应用——在这里交回前端等于让用户同一句话讲两遍。撞整轮机器预算退出的那一支现在
     * 当场就把它叫停了（见 {@code ranOutBudget}），所以那种收尾也拿得到 {@code run/end}。
     */
    /** 按框架收据找回那句插话（用户原话＋它落库的消息 ID）。收据号本轮唯一，配不上就是本轮没这句话。 */
    private static com.wenqu.ai.agent.chat.TurnHandle.SteerAck steerAckOf(String sessionId, String receiptId) {
        com.wenqu.ai.agent.chat.TurnHandle h = sessionId == null ? null : TurnRegistry.handle(sessionId);
        if (h == null) return null;
        for (com.wenqu.ai.agent.chat.TurnHandle.SteerAck a : h.steerAcks()) {
            if (a != null && receiptId.equals(a.receiptId())) return a;
        }
        return null;
    }

    private static List<Map<String, Object>> unsentSteers(
            com.wenqu.ai.agent.chat.TurnHandle handle, String turnId, Recording recording) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (!recording.runEnded) return out;
        if (handle == null || turnId == null || !turnId.equals(handle.turnId())) return out;
        for (com.wenqu.ai.agent.chat.TurnHandle.SteerAck ack : handle.steerAcks()) {
            if (ack == null || ack.receiptId() == null) continue;
            if (recording.appliedSteers.contains(ack.receiptId())) continue;
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("text", ack.text());
            // 随它落库的那条用户消息 ID：前端复用同一颗气泡补发，不另推一条，刷新后也不会变两条
            item.put("messageId", ack.messageId() == null ? "" : ack.messageId());
            out.add(item);
        }
        if (!out.isEmpty()) {
            log.info("[STEER] 本轮收下的插话有 {} 条没进上下文，交回前端续发: session={} turn={}",
                    out.size(), handle.userId(), turnId);
        }
        return out;
    }

    /** 本轮的 turnId（轮级互斥外壳登记的）；外壳没登记时退回会话号，只为留痕不悬空。 */
    private String turnIdOf(ChatCommand cmd) {
        com.wenqu.ai.agent.chat.TurnHandle handle =
                com.wenqu.ai.agent.chat.TurnRegistry.handle(cmd.getSessionId());
        return handle == null ? cmd.getSessionId() : handle.turnId();
    }

    /**
     * 检索状态行（豆包式，回答上方常驻「搜索 N 个关键词，参考 M 段资料」）。
     * <p>框架引擎没有问渠的预检索这一步，命中全靠模型自己调工具——所以这一帧的时机只能跟着
     * 引用池增长走（每次工具注册进新引用就补发一帧），而不是像主链路那样在生成前一次性给出。
     * 不发的后果是前端缺引用预览、且那一轮的「参考 N 段资料」永远空白。
     *
     * @param announced 上一次已发的引用数；传 -1 表示无条件补发（收尾时用，保证终值与落库一致）
     * @return 已发的引用数
     */
    /** 过程独白增量：空串不发帧也不入块（同一 delta 只有正文时不该多一帧空气）。 */
    private static void emitProcess(RunChannel channel, Recording recording, String delta) {
        if (delta == null || delta.isEmpty()) return;
        channel.emit(RunEventType.PROCESS, delta);
        recording.process(delta);
    }

    private int emitRetrieved(ChatCommand cmd, WenquSourcePool pool, RunChannel channel, int announced, Recording recording) {
        int refs = pool.sources().size();
        if (refs == announced) return announced;
        // 无真实检索时 retrievedJson 返回 null：本轮尚未（也不必有）召回，跳过这一帧
        String json = retrievedJson(cmd, pool, recording);
        if (json != null) channel.emit(RunEventType.RETRIEVED, json);
        return refs;
    }

    /**
     * 状态行载荷（「搜索 N 个关键词，参考 M 段资料」）。
     * <p>抽成一处是为了<b>同一份也落库</b>：只随 done 下发的话，刷新后这一行整块消失——
     * 手写侧是把 {@code retrievedJson} 存进消息行的，两条运行时得给用户同一个观感。
     * <p>{@code terms} 优先取模型本轮实际发出的检索词（{@code search_knowledge} 的 {@code query} 入参，
     * 见 {@code Recording#searchQueries}）——它比从用户原问句抽的关键词更贴近「模型真搜了什么」。
     * 模型确实检索过、但没捕获到短语（工具名/入参形状变了、或引用池已有片段）时，退回从问句抽主词元作近似。
     * <p><b>没有真实检索就不出状态行</b>：智能体未配知识库、或本轮没调检索工具时既无检索短语也无召回片段，
     * 返回 {@code null}——前端据此整行不渲染，避免把问句关键词当成「检索词」展示、误导用户以为搜了库。
     *
     * @return 状态行 JSON；无真实检索时返回 null
     */
    private String retrievedJson(ChatCommand cmd, WenquSourcePool pool, Recording recording) {
        Set<String> actual = recording == null ? null : recording.searchQueries;
        int refs = pool.sources().size();
        // 既没发出检索短语、也没召回任何片段：视为本轮未检索知识库，不出状态行
        if ((actual == null || actual.isEmpty()) && refs == 0) return null;
        List<String> terms;
        if (actual != null && !actual.isEmpty()) {
            terms = new java.util.ArrayList<>(actual);
        } else {
            terms = keywordExtractor.extractMain(cmd.getQuestion());
        }
        return com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                "keywords", terms.size(), "refs", refs, "terms", terms));
    }

    /**
     * 流式期的用量帧：本轮每落地一条新消息就重发一份 {@code usage}，让输入框工具栏的容量圆环
     * 在第一次模型回包后就点亮，而不是等整轮收尾才出现（收尾那一发离结束只剩一帧，等于没有）。
     * <p>与 {@link #finish} 里的终值同源同算法（都走 {@link #usageOf}），前端只把它挂在
     * {@code tokensPreview} 上：落库、累计、输出侧仍只认 done 的 tokens，这里不另立口径。
     * <p>按 transcript 条数变化触发，不按循环节拍：分类拆分要对全量上下文做 BPE 计数，
     * 200ms 一跳空转会把跟随线程烧在数 token 上。变化本身由 {@link TurnMirror} 从事件上认，
     * 因为「问它变了几条」要先做一次全量读——那比数 token 还贵。发不出去只是容量圆环晚一帧，不该带走这一轮的答案，
     * 所以异常只告警不上抛。
     */
    private void emitLiveUsage(AgentSession session, ChatCommand cmd, WenquSourcePool pool,
                               RunChannel channel, int historyBefore, long[] stamp, boolean transcriptChanged,
                               int contextWindow) {
        // 上下文没添新行就不必读全量日志去问「添了几条」：transcript() 走的是从 head 回走的整条 commit 链
        if (!transcriptChanged) return;
        // 墙钟节流（stamp[1]）：tool/result 会让 transcriptChanged 每步都真，大表轮 20 来步就是
        // 20 来次全量读，全折进轮内延迟。容量圆环 4 秒刷一次没有可感知损失。
        long now = System.currentTimeMillis();
        if (stamp[1] > 0 && now - stamp[1] < 4_000L) return;
        stamp[1] = now;
        int size = session.transcript().messages().size();
        if (size == stamp[0]) return;
        stamp[0] = size;
        try {
            Map<String, Object> tokens = usageOf(session, historyBefore, pool, contextWindow);
            // 还没有任何一次模型回包带 usage：此刻报不出占用，等下一跳（空表发出去只会让前端清空预览）
            if (tokens.isEmpty()) return;
            channel.emit(RunEvent.of(RunEventType.USAGE, com.alibaba.fastjson2.JSON.toJSONString(tokens), false));
        } catch (Exception e) {
            log.warn("[USAGE] 流式用量帧发不出来，容量圆环要等本轮收尾: session={}, {}",
                    cmd.getSessionId(), e.toString());
        }
    }

    /**
     * 本轮实测用量：只累计这一轮新增消息的 {@link io.agentscope.core.model.ChatUsage}。
     * <p><b>出去的每一个数都是网关回传的实测值，没有一个是本地估的。</b>要按类别拆窗口占用（系统提示词
     * 多少、工具声明多少）得有<b>这个模型自己的分词器</b>——问渠只持有 OpenAI 那套 BPE 词表，对不上
     * 词表的模型只能估；估出来再摊平到实测总数上，每一格看着严丝合缝、其实都不是实测。
     * 所以这里只报网关给得起的口径：输入、输出、缓存读取、缓存写入、思考。
     * <p>占用报 {@code context}（最终轮输入）而不是 {@code prompt}（各轮输入之和）：工具往返会把同一份
     * 上下文反复送入，几轮下来后者能到前者的两三倍，拿它算占用百分比会把容量条撑到虚高。
     * <p>上下文预算（{@code budget}）框架不给：窗口值（{@code modelContextWindow}）是「模型允许的
     * 上下文上限」，不是问渠「给这一轮留了多少」的预算，报上去会让右栏占用条按一个没人承诺过的数
     * 算百分比。前端对缺字段是「不显示该行」，不填比填个假预算诚实。
     */
    private Map<String, Object> usageOf(AgentSession session, int historyBefore,
                                        WenquSourcePool pool, int contextWindow) {
        List<Msg> messages = session.transcript().messages();
        int from = Math.min(Math.max(historyBefore, 0), messages.size());
        long prompt = 0;
        long output = 0;
        long cached = 0;
        long cacheWrite = 0;
        long reasoning = 0;
        int lastInput = 0;
        long lastOutput = 0;
        boolean any = false;
        for (int i = from; i < messages.size(); i++) {
            io.agentscope.core.model.ChatUsage usage = messages.get(i).getUsage();
            if (usage == null) continue;
            any = true;
            prompt += usage.getInputTokens();
            output += usage.getOutputTokens();
            cached += usage.getCachedTokens();
            cacheWrite += usage.getCacheCreationTokens();
            reasoning += usage.getReasoningTokens();
            // 最后一轮的 prompt≈本轮结束时的上下文占用（工具结果逐轮进上下文），与主链路口径同源
            lastInput = usage.getInputTokens();
            lastOutput = usage.getOutputTokens();
        }
        if (!any) return new java.util.LinkedHashMap<>();
        Map<String, Object> tokens = new java.util.LinkedHashMap<>();
        tokens.put("prompt", prompt);
        tokens.put("output", output);
        // 最终那一轮的输出：暗截断对账只看它（各轮工具调用的输出不参与触顶与丢失判定，与主链路同口径）
        tokens.put("finalOutput", lastOutput);
        tokens.put("total", prompt + output);
        // 缓存三项是网关分别回传的实测值：读取（cached）、写入（cacheWrite）、未命中即 prompt-cached
        tokens.put("cached", cached);
        tokens.put("cacheWrite", cacheWrite);
        tokens.put("reasoning", reasoning);
        tokens.put("context", lastInput);
        tokens.put("outputIsReal", true);
        tokens.put("hits", pool.sources().size());
        // 窗口取实例真正烤进去的那一份（与压缩阈值同源）：报别的数，容量条的百分比就和
        // 「什么时候会折上下文」各说各话
        if (contextWindow > 0) {
            tokens.put("window", contextWindow);
            tokens.put("windowSource", "framework");
        }
        return tokens;
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

    private static void emit(RunChannel channel, RunEventType type, String content) {
        if (content != null && !content.isEmpty()) channel.emit(type, content);
    }

    /**
     * 收场当前这一段思考：记时间线区间 + 补一条 {@code thinking_done}（带本轮累计全文）。
     * 没在思考中就是空操作，不发空帧。
     */
    private static void closeThinkingFrame(RunChannel channel, Recording recording) {
        String closed = recording.closeThinking();
        if (closed == null) return;
        channel.emit(RunEventType.THINKING_DONE,
                com.alibaba.fastjson2.JSON.toJSONString(Map.of("thinking", closed)));
    }

    /**
     * 本轮流式一截正文都没攒到时的回退取法：取会话记录里最后一条有正文的 assistant 消息。
     * <p>最后一条若是框架被叫停时补的那句 recovery 文本（"I noticed that you have interrupted me…"，
     * generateReason=INTERRUPTED）就**停在这里返回空**，不再往前翻：它是这一跑留在上下文里的最后一条，
     * 前面那条属于**上一轮**，捞出来就是拿别人的话给这一轮存档（上面那条注释记过的旧坑）。
     * 空正文的停止轮会落到「只有过程、没有正文」那一支——界面上只剩工具卡与「已停止本轮」标注，
     * 这才是这一轮真实留下的东西。
     * 判据用框架自己留的 generateReason，不比对文案。
     * <p><b>只看这一跑添的那几行</b>（与 {@link #usageOf} 同一个 {@code historyBefore} 口径）：
     * 回退要补的是「模型答了、正文没进过流式剥离器」，而这一跑一个字也没添时，transcript 尾部那条
     * 必然属于上一轮——拿它当本轮正文存档，就是把别人的话记在这一轮名下。实例被重建过尤其如此：
     * 框架的执行异常留在旧实例里，新实例读不到失败痕迹，光靠 {@code failure == null} 挡不住。
     */
    private static String lastAssistantText(AgentSession session, int historyBefore) {
        List<Msg> messages = session.transcript().messages();
        if (messages.size() <= historyBefore) return "";
        // 下界必须停在 historyBefore：submit 后用户行必然已 commit，本轮 transcript 里再没有
        // 可用的 assistant 正文时（思考吃光 max_tokens 的 0-token 轮、出字前被叫停），
        // 一路扫到 0 就穿透进上一轮——把上一轮的回答当本轮正文存档
        for (int i = messages.size() - 1; i >= historyBefore; i--) {
            Msg m = messages.get(i);
            if (!"ASSISTANT".equals(String.valueOf(m.getRole()))) continue;
            if (GenerateReason.INTERRUPTED == m.getGenerateReason()) return "";
            String text = m.getTextContent();
            if (text != null && !text.isBlank()) return text;
        }
        return "";
    }

    /**
     * 框架因<b>步数上限</b>收场时补的那条收尾正文（会话记录里最后一条助手消息带
     * {@code generateReason=MAX_ITERATIONS}）；这一轮不是撞上限收场就返回 null。
     * <p>判据只用框架自己留在消息上的 generateReason，不比对文案——那句
     * "You have failed to generate response within the maximum iterations" 是框架的内部提示词，
     * 跟着文案走等于把判据挂在别人会改的东西上。
     * <p>只看<b>最后一条</b>助手消息、不往前翻：往前翻会把上一轮撞上限的那条捞给这一轮存档
     * （{@link #lastAssistantText} 的注释记过同一类串轮坑）。同一条护栏：这一跑没往 transcript
     * 添过行就没有「本轮的收尾」可取。
     */
    private static String maxIterationsWrapUp(AgentSession session, int historyBefore) {
        List<Msg> messages = session.transcript().messages();
        if (messages.size() <= historyBefore) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (!"ASSISTANT".equals(String.valueOf(m.getRole()))) continue;
            if (GenerateReason.MAX_ITERATIONS != m.getGenerateReason()) return null;
            String text = m.getTextContent();
            return text == null || text.isBlank() ? null : text.strip();
        }
        return null;
    }

    /**
     * 回退那一份正文的收尾处理：它出自框架的 transcript，从没进过流式剥离器（{@code model/chunk}
     * 的增量才是它的正常入口），所以标签得在这儿剥掉。
     * <p>不剥的表现为两件事同时发生：{@code <related>…</related>} 原文印进回答，而「接下来可以」
     * 那排芯片一个也没有——芯片正是从这块里拆出来的。
     * <p>过同一个 {@link Recording#related} 而不是在这里补一条正则：推荐条数、「模型把正文写进
     * 推荐块时并回回答」那道护栏、以及 {@code <process>} 分流都住在它身上，同一件事不该有第二份口径。
     */
    private static String stripFallbackBody(Recording recording, String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // 只取正文、不补发 token 帧：done 的 finalContent 就在下一毫秒，重放一遍等于把同一截话发两次
        ModelOutputParser.Delta fed = recording.related.feed(raw);
        ModelOutputParser.Delta finished = recording.related.finish();
        recording.process(fed.process() + finished.process());
        return fed.text() + finished.text();
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

    private String askCard(String askId, Object input, String toolName) {
        Object questions;
        if (input instanceof Map<?, ?> in) {
            Object askList = in.get("questions");
            if (askList instanceof List<?> list && !list.isEmpty()) {
                // 一卡多问：每题自带 multi，原样透传（这里不替它判单选）
                questions = list;
            } else {
                Map<String, Object> one = new java.util.LinkedHashMap<>();
                String inTopic = str(in.get("topic"));
                one.put("topic", !inTopic.isBlank() ? inTopic
                        : (toolName == null || toolName.isBlank() ? "确认" : toolName));
                one.put("question", str(in.get("question")));
                Object options = in.get("options");
                one.put("options", options instanceof List<?> l ? l : List.of());
                // multi 只在为真时写：省略=单选，别让用户侧多认一种「显式 false」的写法
                if (boolOf(in.get("multi"))) one.put("multi", true);
                questions = List.of(one);
            }
        } else {
            questions = List.of(Map.of("question", str(input)));
        }
        // 交给 fastjson 序列化而不是手拼转义：题面里出现引号或换行是常态，手拼必出错
        return com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                "askId", askId, "questions", questions,
                "timeoutMs", pendingBudgetMs(PendingKind.ASK)));
    }

    /** 工具入参在展示（落库 + SSE）里的单字段上限：超过就折成预览 + 省略字数。 */
    private static final int TOOL_ARG_PREVIEW_CHARS = 2000;

    /**
     * 工具入参的展示形态：超长字符串字段折成「前 2000 字 + 已省略 N 字」，其余原样。
     * <p>触发点是文件类工具：{@code presentArtifact} / {@code write_file} 的 {@code content} 是整份正文
     * （几万字的 HTML 报告很常见），原样回放会同时撑肥消息行、每帧 SSE 与历史回显，界面上则是一屏转义源码。
     * 完整正文仍在产物库/沙箱文件里，展示层只需要交代「这一步写了什么」。
     * <p>折叠只改字符串值、保持 JSON 结构：前端读 args 的 {@code JSON.parse} 与入参摘要都不受影响。
     */
    static String toolArgsForDisplay(String args) {
        if (args == null || args.length() <= TOOL_ARG_PREVIEW_CHARS) return args;
        Object parsed;
        try {
            parsed = com.alibaba.fastjson2.JSON.parse(args);
        } catch (Exception e) {
            return clipArgText(args); // 不是 JSON（MCP 兼容的纯文本入参）：按整段折
        }
        if (!(parsed instanceof java.util.Map) && !(parsed instanceof java.util.List)) return clipArgText(args);
        return com.alibaba.fastjson2.JSON.toJSONString(trimLongArgText(parsed));
    }

    private static Object trimLongArgText(Object v) {
        if (v instanceof String s) {
            return s.length() <= TOOL_ARG_PREVIEW_CHARS ? s : clipArgText(s);
        }
        if (v instanceof java.util.Map<?, ?> m) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), trimLongArgText(e.getValue()));
            }
            return out;
        }
        if (v instanceof java.util.List<?> l) {
            java.util.List<Object> out = new java.util.ArrayList<>(l.size());
            for (Object o : l) out.add(trimLongArgText(o));
            return out;
        }
        return v;
    }

    private static String clipArgText(String s) {
        return s.substring(0, TOOL_ARG_PREVIEW_CHARS) + "…（已省略 " + (s.length() - TOOL_ARG_PREVIEW_CHARS) + " 字）";
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
    private String toolCard(String uid, String name, String status, long elapsedMs, long startedAtMs, long waitedMs,
                            String args, String result, java.util.Map<String, Object> subChat, int stepId) {
        return toolCard(uid, name, status, elapsedMs, startedAtMs, waitedMs, args, result, subChat, stepId,
                false, 0L, null);
    }

    /**
     * 工具卡载荷的完整版：在上一份的基础上多一组「早产进度」字段（{@code preparing/chars/brief}）。
     * <p>{@code preparing=true} 表示这次调用还在生成入参、工具尚未开始执行；前端把这张卡渲染成进行中，
     * 并在正式 {@code tool/requested} 帧到达时原地升级（不新增第二张卡）。
     */
    private String toolCard(String uid, String name, String status, long elapsedMs, long startedAtMs, long waitedMs,
                            String args, String result, java.util.Map<String, Object> subChat, int stepId,
                            boolean preparing, long chars, String brief) {
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(esc(name)).append("\"");
        String label = displayNames.label(uid, name);
        if (label != null) sb.append(",\"label\":\"").append(esc(label)).append("\"");
        String hint = displayNames.hint(uid, name);
        if (hint != null) sb.append(",\"hint\":\"").append(esc(hint)).append("\"");
        sb.append(",\"status\":\"").append(status)
                .append("\",\"elapsedMs\":").append(elapsedMs);
        // 步骤归属：清单里当时唯一 doing 的那一项（-1=还没进到某一步，不写字段）。
        // 执行详情靠它把工具与产物归到具体步骤；缺了这块，前端只能按调用顺序猜，串步是常态。
        if (stepId >= 0) sb.append(",\"stepId\":").append(stepId);
        // 只在真有值时写：缺它 = 这一把没计时（前端不显示），写成 0 = 谎称这一把从 1970 年跑起 / 跑了 0 毫秒
        if (startedAtMs > 0L) sb.append(",\"startedAtMs\":").append(startedAtMs);
        if (waitedMs > 0L) sb.append(",\"waitedMs\":").append(waitedMs);
        // 早产卡：这次调用还在生成入参（工具尚未执行），字数用于「正在生成 N 字」那一行摘要
        if (preparing) {
            sb.append(",\"preparing\":true");
            if (chars > 0L) sb.append(",\"chars\":").append(chars);
            if (brief != null && !brief.isBlank()) sb.append(",\"brief\":\"").append(esc(brief)).append("\"");
        }
        // 入参随卡片下发：前端工具卡展开要显示「这把工具拿什么去做的」，缺它展开就是一张空卡。
        // 必须按字符串下发（和落库那份同形，见 Recording#toolCall）：前端读 args 的四处全是
        // JSON.parse(t.args)，裸拼对象会让流式时的标题行与入参段都成 [object Object]（刷新读落库那份却正常），
        // 还顺带废掉「同一次 start 只建一张卡」的入参比对（两个对象永不 ===）
        // 与落库那份同一把尺：超长字段折成预览，否则整份文件正文会随每一帧 SSE 重发一遍
        if (args != null && !args.isBlank()) {
            sb.append(",\"args\":").append(com.alibaba.fastjson2.JSON.toJSONString(toolArgsForDisplay(args)));
        }
        // 输出（截断）：展开卡的「输出」段读它；缺了只剩入参，看不到工具到底返回了什么
        if (result != null && !result.isBlank()) sb.append(",\"result\":").append(com.alibaba.fastjson2.JSON.toJSONString(result));
        // 咨询卡（子智能体/取数助手等）的终态一问一答：直播与刷新同一份 subChat（落库那份同源），
        // 漏发就是「直播只见一张空卡、刷新才长出结果」——与下方 done 帧、刷新读库三条路径必须同构
        if (subChat != null && !subChat.isEmpty()) sb.append(",\"subChat\":").append(com.alibaba.fastjson2.JSON.toJSONString(subChat));
        return sb.append("}").toString();
    }

    /**
     * 一次调用的标识：{@code tool/requested} 在顶层带 {@code toolCallId}，{@code tool/result} 只在
     * 结果块里（{@code content[].id}，与它对应的那个 ToolUseBlock 同一个 id）。两侧都拿不到时返回空串，
     * 调用方按 {@link Recording#toolCallKey} 退回按工具名配对。
     */
    private static String toolCallIdOf(Map<String, Object> data) {
        Object top = data.get("toolCallId");
        if (top != null && !String.valueOf(top).isBlank()) return String.valueOf(top);
        if (data.get("message") instanceof Map<?, ?> msg && msg.get("content") instanceof List<?> blocks) {
            for (Object b : blocks) {
                if (b instanceof Map<?, ?> bm && bm.get("name") != null && bm.get("id") != null
                        && !String.valueOf(bm.get("id")).isBlank()) {
                    return String.valueOf(bm.get("id"));
                }
            }
        }
        return "";
    }

    /**
     * 从 {@code tool/result} 事件里取出工具输出文本（{@code message.content[].output[].text}），
     * 截断到 2000 字符——与手写侧 SSE 的输出摘要同量级，整份塞进卡片会把会话行撑爆。
     */
    private static String toolResultText(Map<String, Object> data) {
        Object message = data.get("message");
        if (!(message instanceof Map<?, ?> msg)) return null;
        if (!(msg.get("content") instanceof List<?> blocks)) return null;
        StringBuilder sb = new StringBuilder();
        for (Object b : blocks) {
            if (!(b instanceof Map<?, ?> bm)) continue;
            Object output = bm.get("output");
            if (output instanceof List<?> outs) {
                for (Object o : outs) {
                    if (o instanceof Map<?, ?> om) {
                        Object t = om.get("text") != null ? om.get("text") : om.get("content");
                        if (t != null) sb.append(t);
                    }
                }
            } else if (bm.get("text") != null) {
                sb.append(bm.get("text"));
            }
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) return null;
        // 工具卡展示的是结果本身，不是防护措辞：信封/数据声明只服务模型上下文
        s = com.wenqu.ai.util.UntrustedContent.displayText(s);
        if (s.isEmpty()) return null;
        return s.length() > 2000 ? s.substring(0, 2000) + "…（已截断）" : s;
    }

    /**
     * 早产工具卡：把一次 {@code tool_use} 入参增量交给 {@link Recording#noteToolUseDelta}，
     * 够门槛就发一帧 {@code tool_status start}（带 {@code preparing/chars/brief}）。
     * <p>前端据 preparing 标记把这张卡渲染成「进行中」，正式 {@code tool/requested} 到达时原地升级，
     * 所以这里不需要记卡、也不需要等工具真正开始执行。
     */
    private void noteToolUseDelta(RunChannel channel, Recording recording, Map<?, ?> block) {
        Recording.PreparingTick tick = recording.noteToolUseDelta(
                str(block.get("name")), str(block.get("id")), str(block.get("content")),
                System.currentTimeMillis());
        if (tick == null) return;
        try {
            emit(channel, RunEventType.TOOL_STATUS,
                    toolCard(recording.uid, tick.name(), "start", 0L, tick.startedAtMs(), 0L,
                            null, null, null, recording.hooks.step().index(),
                            true, tick.chars(), tick.brief()));
        } catch (RuntimeException gone) {
            // 通道已收（用户切走/刷新）：这一帧发不出去不影响正文与工具结果，别让跟随循环因此收场
            log.debug("[agentx] 早产工具卡帧没发出去（通道已收）: session={} tool={}",
                    channel.sessionId(), tick.name());
        }
    }

    /**
     * 从流式入参前缀捞一个用户看得懂的摘要（文件名/路径/检索词/命令）。
     * <p>只在值已经生成完整时命中：半截的 {@code "filename": "ind} 不会误报成 {@code ind}。
     */
    static String partialArgBrief(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var m = PARTIAL_ARG_BRIEF.matcher(raw);
        if (!m.find()) return null;
        String v = m.group(2)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", " ")
                .replace("\\r", "")
                .replace("\\t", " ")
                .trim();
        if (v.isEmpty()) return null;
        return v.length() > 40 ? v.substring(0, 40) + "…" : v;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 入参布尔位：模型偶尔把布尔写成字符串（"true"），两种形状都认，写歪了按「不勾」处理 */
    private static boolean boolOf(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && "true".equalsIgnoreCase(String.valueOf(o).trim());
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // **JSON 规范要求 U+0000–U+001F 全部转义**，不只是 \n \r \t。
                    // 控制字符（如 U+001A）漏出去会让整份 done 载荷变成非法 JSON，下游拿不到来源清单，
                    // 且这类字符在终端里显示不出来，排查成本极高。
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
