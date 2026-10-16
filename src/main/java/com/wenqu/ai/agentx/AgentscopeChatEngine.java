package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.plan.PlanCopy;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.engine.ChatEngine;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEvent;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.service.ChatAttachmentService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionInbox;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecovery;
import io.agentscope.core.session.SessionTurns;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.DefaultJedisClientConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 框架引擎：这一轮的<b>控制流完全由 AgentScope 持有</b>——推理循环、工具派发、权限判定、会话状态、
 * 待办与接续执行都由框架决定。问渠自己写的只剩四件：按本轮裁决出的模型交给框架、把框架的 Session Log
 * 翻成前端既有事件名、把问渠领域能力挂成框架工具、把框架待办接到问渠的确认端点。
 * <p>执行形态是 {@code AgentSession.submit}（常驻会话实例），不是每请求 {@code streamEvents}+close：
 * 只有前者带来待办持久化、答复后由框架找回原任务与原工具调用接续执行，以及按 {@code seq} 游标读增量
 * 事件（断线续跑的等价物）。模型切换走「换模型就重建实例并接管同一条会话」——会话能否接续不依赖实例
 * 永生：Phase 0 实测换一个全新实例、同一 {@code (userId, sessionId)} 能读到完整历史并接着答。
 * <p>框架帮不上、必须问渠写的只有两处：RAG 检索链（框架没有问渠的多路召回，包成被调用的工具）
 * 与前端 29 个事件名的词汇差异（见 {@link #translate}）。
 */
public class AgentscopeChatEngine implements ChatEngine, FrameworkSessions.ResumeHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentscopeChatEngine.class);

    /** 推荐块里条目长过这个字数就按正文处理（与手写侧 {@code RELATED_PROSE_MIN_CHARS} 同值同理由）。 */
    private static final int RELATED_PROSE_MIN_CHARS = 80;

    /** 本轮提交离队的宽限：投递循环 100ms 一跳，超过这一段还没启动就是被闸门锁住了。 */
    private static final long LAUNCH_GRACE_MS = 30_000L;

    private final ModelRegistryService modelRegistry;
    private final ConfigService configService;
    private final AppProperties properties;
    private final WenquAgentxTools tools;
    private final FrameworkSessions sessions;
    private final com.wenqu.ai.service.SessionService sessionService;
    /** 附件元信息落库的共用口径（与工作流路径同一个方法，见 metasJson 的类注释）。 */
    private final ChatAttachmentService attachments;
    /** 技能清单注入 system prompt（正文仍由模型按需 readSkill 取，不塞进上下文）。 */
    private final com.wenqu.ai.service.SkillService skills;
    private final com.wenqu.ai.service.KeywordExtractor keywordExtractor;
    private final com.wenqu.ai.service.ArtifactService artifactService;
    private final com.wenqu.ai.agent.subagent.SubAgentConsultService consultService;
    private final com.wenqu.ai.agent.context.CitationIntegrityService citations;
    private final com.wenqu.ai.agent.plan.PlanModeService plan;
    /** 问答台账（看板、坏例、未命中问题都吃这张表）——分派换运行时不该把它换成只有一半流量。 */
    private final com.wenqu.ai.service.QaLogService qaLog;
    /**
     * 工具展示名与悬停说明的解析入口（与手写侧 {@code TurnRecorder#recordToolStatus} 同一个来源）。
     * <p>框架引擎原先把工具<b>裸名</b>当 {@code label} 下发（{@code toolCard} 里的 {@code esc(name)}），
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

    public AgentscopeChatEngine(ModelRegistryService modelRegistry, ConfigService configService,
                               AppProperties properties, WenquAgentxTools tools,
                               FrameworkSessions sessions,
                               com.wenqu.ai.service.SessionService sessionService,
                               ChatAttachmentService attachments,
                               com.wenqu.ai.service.SkillService skills,
                               com.wenqu.ai.service.KeywordExtractor keywordExtractor,
                               com.wenqu.ai.service.ArtifactService artifactService,
                               com.wenqu.ai.agent.subagent.SubAgentConsultService consultService,
                               com.wenqu.ai.agent.context.CitationIntegrityService citations,
                               com.wenqu.ai.agent.plan.PlanModeService plan,
                               com.wenqu.ai.service.QaLogService qaLog,
                               com.wenqu.ai.agent.tool.ToolRegistry toolRegistry,
                               java.util.function.Function<ChatCommand, com.wenqu.ai.service.ChatService.TurnModel> modelResolver) {
        this.modelRegistry = modelRegistry;
        this.configService = configService;
        this.properties = properties;
        this.tools = tools;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.attachments = attachments;
        this.skills = skills;
        this.keywordExtractor = keywordExtractor;
        this.artifactService = artifactService;
        this.consultService = consultService;
        this.citations = citations;
        this.plan = plan;
        this.qaLog = qaLog;
        this.displayNames = new ToolDisplayNames(toolRegistry);
        this.modelResolver = modelResolver;
    }

    /** @ 引用 / # 历史引用的消息级常驻标注（刷新/历史回显保留）。 */
    private String mentionsJson(List<ChatRequest.Mention> mentions) {        if (mentions == null || mentions.isEmpty()) return null;
        return com.alibaba.fastjson2.JSON.toJSONString(mentions);
    }

    private String historyRefsJson(List<ChatRequest.HistoryRef> historyRefs) {
        if (historyRefs == null || historyRefs.isEmpty()) return null;
        return com.alibaba.fastjson2.JSON.toJSONString(historyRefs);
    }

    /**
     * 技能清单（注入 system prompt）：本轮用户选用了哪些技能，就只列这些；一个都没选则列出全部启用的
     * 技能，让模型仍能主动发现可用做法。
     * <p>只列名称与描述，正文由模型按需调 {@code readSkill} 取——这是技能设计的本意（见
     * {@code SkillService.promptBlock}），把全文塞进每次请求会把上下文吃光。
     */
    private String skillPrompt(ChatCommand cmd) {
        try {
            List<String> only = cmd.getSkills();
            Set<String> picked = (only == null || only.isEmpty()) ? null : new java.util.LinkedHashSet<>(only);
            return skills.promptBlock(cmd.getUserId(), 1200, picked);
        } catch (Exception e) {
            log.warn("[SKILL] 技能清单注入失败（不影响本轮作答）: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 本轮随行内容 → 送进模型的用户文本。
     * <p><b>为什么必须在这里装配</b>：附件正文 / @ 引用 / # 历史引用此前**只落库、不入上下文**——
     * 用户上传了文件、在输入框里 @ 了知识库、# 了历史问答，模型却一个字都收不到，于是如实回答
     * 「没有看到您上传的文件」。落库与回显只是给人看的，模型要答得对，内容必须真的送到它眼前。
     * <p>三段各自独立、任一为空就不追加段落；附件解析失败由 {@link ChatAttachmentService#prepare}
     * 自己兜成一段可读的错误说明（fail-loud 到用户可见文案，而不是静默丢）。
     */
    private String userContentWithContext(ChatCommand cmd) {
        StringBuilder sb = new StringBuilder();
        String q = cmd.getQuestion() == null ? "" : cmd.getQuestion();
        sb.append(q);
        // 1) 文档附件正文（当轮看一眼的轻路径：只抽文本，超长截断，不进向量库）
        if (cmd.getAttachments() != null && !cmd.getAttachments().isEmpty()) {
            List<ChatAttachmentService.PreparedAttachment> prepared =
                    attachments.prepare(cmd.getAttachments(), cmd.getUserId());
            String text = attachments.buildContextText(prepared);
            if (!text.isBlank()) sb.append('\n').append(text);
        }
        // 2) @ 引用：kb=收窄检索范围（由检索侧按 mentions 生效，无需在这里重复正文）；
        //    doc=该文档内容**直接带入上下文**，必须由这段送进去，否则 @ 了等于没 @；
        //    agent=强制咨询，由工具侧处理，这里只说明本轮有一支会被咨询，避免模型自答。
        if (cmd.getMentions() != null && !cmd.getMentions().isEmpty()) {
            sb.append("\n【本轮 @ 引用】\n");
            for (ChatRequest.Mention m : cmd.getMentions()) {
                if (m == null) continue;
                sb.append("- ").append(m.getType()).append("：").append(m.getName()).append('\n');
            }
            boolean hasDoc = cmd.getMentions().stream()
                    .anyMatch(m -> m != null && "doc".equals(m.getType()));
            if (hasDoc) {
                sb.append("（doc 类型的内容已由系统直接前置到本轮上下文，请据此作答，勿声称没有看到）\n");
            }
        }
        // 3) # 引用：把被引用的历史问答原文带过来，否则模型无从知道"这一句"在说什么
        if (cmd.getHistoryRefs() != null && !cmd.getHistoryRefs().isEmpty()) {
            sb.append("\n【本轮引用的历史问答】\n");
            for (ChatRequest.HistoryRef h : cmd.getHistoryRefs()) {
                if (h == null || h.getContent() == null || h.getContent().isBlank()) continue;
                sb.append("- ").append("assistant".equals(h.getRole()) ? "答" : "问").append("：")
                        .append(h.getContent()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 解析并缓存会话状态存储。默认本地文件；仅当 {@code chat.agentscopeRedisEnabled=true} 时尝试 Redis。
     * <p>Redis 路径全程兜底：连不上、鉴权失败、甚至 Jedis 类缺失（被项目既有 Jedis 声明压成低版本）
     * 都退回到本地文件并告警——绝不让「开个多副本开关」把框架引擎整体打挂。
     */
    private AgentStateStore stateStore(Path stateDir) {
        if (stateStoreResolved) return cachedStateStore;
        synchronized (this) {
            if (stateStoreResolved) return cachedStateStore;
            cachedStateStore = resolveStateStore(stateDir);
            stateStoreResolved = true;
            return cachedStateStore;
        }
    }

    private AgentStateStore resolveStateStore(Path stateDir) {
        boolean redisOn = "true".equalsIgnoreCase(configService.get("chat.agentscopeRedisEnabled"));
        if (!redisOn) {
            return new JsonFileAgentStateStore(stateDir);
        }
        String host = firstNonBlank(configService.get("chat.agentscopeRedisHost"), "localhost");
        int port = toInt(configService.get("chat.agentscopeRedisPort"), 6379);
        String password = firstNonBlank(configService.get("chat.agentscopeRedisPassword"), null);
        String prefix = firstNonBlank(configService.get("chat.agentscopeRedisPrefix"), "agentscope:session:");
        try {
            UnifiedJedis jedis = password == null || password.isBlank()
                    ? new UnifiedJedis(new HostAndPort(host, port))
                    : new UnifiedJedis(new HostAndPort(host, port),
                            DefaultJedisClientConfig.builder().password(password).build());
            // 连通性探针：连不上就退回本地文件，不让框架引擎整体挂掉
            jedis.ping();
            log.info("[agentx] 会话状态存储使用 Redis: {}:{} prefix={}", host, port, prefix);
            return RedisAgentStateStore.builder().jedisClient(jedis).keyPrefix(prefix).build();
        } catch (Throwable e) {
            log.warn("[agentx] Redis 状态存储不可用（{}），退回本地文件: {}", e.toString(), stateDir);
            return new JsonFileAgentStateStore(stateDir);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static int toInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String name() {
        return "agentscope";
    }

    @Override
    public void chat(ChatCommand cmd, RunChannel channel) {
        // 模型引用按问答侧同一条链解析（请求覆盖 > 会话固定 > 个人默认）：只认请求里那一份时，
        // 靠个人默认作答的轮会解析不出路由（实测是 NPE 打断整轮，连错误帧都没有）
        com.wenqu.ai.service.ChatService.TurnModel turn = modelResolver.apply(cmd);
        if (turn.unusableMessage() != null) {
            // 已停用的模型不发给上游：上游只会回一句 401/404，用户读到的是「网关坏了」，
            // 而不是「你选的这个模型已经不能用了，去这儿改」
            failLoud(channel, turn.unusableMessage());
            return;
        }
        String ref = turn.ref();
        if (ref == null || ref.isBlank()) {
            failLoud(channel, "未指定模型：请在对话右上角选择模型，或在个人设置中配置默认模型");
            return;
        }
        ModelRegistryService.ModelRoute route = modelRegistry.chatRoute(ref);
        if (route == null) {
            failLoud(channel, "模型「" + ref + "」未登记或已不可用：请在模型管理里确认该供应商与模型");
            return;
        }
        ExecutionPolicy policy = tools.policyFor(cmd,
                (code, message) -> channel.emit(RunEventType.WARN, message));
        // 计划意图识别：问的是手写侧同一个判断器、同一条判据（用户没开计划开关时，按消息意图决定本轮要不要
        // 先出计划）。在这里发起是为了与后面建实例、装工具并行跑；开轮前取结果，判定超时按不开（同一口径）。
        java.util.concurrent.CompletableFuture<Boolean> planIntent = plan.startIntentCheck(
                channel.emitter(), cmd.isGuestMode(), cmd.isPlanMode(), cmd.isRegenerate(),
                cmd.getSessionId(), cmd.getQuestion(), ref,
                (cmd.getUserImages() != null && !cmd.getUserImages().isEmpty())
                        || (cmd.getAttachments() != null && !cmd.getAttachments().isEmpty()));
        WenquSourcePool pool = tools.newPool(cmd, channel.emitter());
        // 本轮改过用户存过的选择（失效的会话模型已清除、改用他的默认模型）必须说出来。
        // 只登记降级、不再另发一条 WARN：降级随消息落库、刷新后仍在，WARN 是同一句话的第二份，
        // 两条都会渲染成警示条（实测同一个原因上下各一条）
        if (turn.fallbackNotice() != null) {
            pool.registrar().degrade("modelFallback", turn.fallbackNotice());
        }
        channel.emit(RunEventType.STAGE, "框架引擎（AgentScope）正在回答…");

        // 本轮身份（轮级互斥外壳已经登记好 turnId）：待办落库、任务清单留痕、步数预算归零都认它
        String turnId = turnIdOf(cmd);
        java.util.concurrent.ConcurrentLinkedQueue<String> spills = new java.util.concurrent.ConcurrentLinkedQueue<>();
        WenquAgentxTools.TurnHooks hooks = tools.newHooks(cmd, ref, () -> turnId, spills,
                consultService.newLedger());
        // 实例键用完整引用而不是裸模型名：同一个模型名可以挂在好几个供应商下（各自的 baseUrl 与 Key 不同），
        // 只按裸名判「模型没换」就不重建实例，于是换了供应商仍拿旧网关旧 Key 跑，且日志上一切正常
        AgentSession session = sessions.open(cmd, ref,
                ctx -> buildAgent(cmd, channel, pool, policy, route, hooks));
        // 上一轮只要以失败收场过，这条会话后续每一轮都不会被框架启动：开轮前先把它归档掉
        reconcileStalledTurn(session, cmd);
        // 工具步数预算按轮归零：实例是常驻的，不归零就是上一轮的步数吃掉这一轮的预算
        AgentxStepBudget stepBudget = sessions.stepBudget(sessions.key(cmd));
        if (stepBudget != null) {
            stepBudget.beginTurn(turnId, tools.maxToolSteps(cmd), (code, message) -> {
                pool.registrar().degrade(code, message);
                channel.emit(RunEventType.WARN, message);
            });
        }
        // 本轮实测用量只算这一轮新增的消息：框架的会话记录跨轮常驻，整表求和会把历史轮的 token
        // 也计进这一轮的账单
        int historyBefore = session.transcript().messages().size();
        // 事件游标同理必须从「本轮开跑之前」起读：框架的 Session Log 跨轮常驻，从 0 读会把上一轮的
        // 正文与工具卡当增量再发一遍（前端按 pos 去重只护得住同一条气泡的重复片段，护不住跨轮）
        long startCursor = session.log().head().seq();

        // 问题先落库：这一轮哪怕中途停掉或出错，界面上也不该出现「问出去了却什么都没有」的空档
        // （与手写运行时的即时落库同一条口径）。附件/@/#历史引用随用户消息一并持久化，刷新后回显不丢。
        String userMessageId = cmd.isRegenerate() || cmd.isFromSteer() ? null
                : sessionService.appendMessage(cmd.getSessionId(), "user", cmd.getQuestion(),
                        cmd.getUserImages(), null, null, null, null, null,
                        attachments.metasJson(cmd.getAttachments(), cmd.getUserId()),
                        null, null, null, null, null, null, null,
                        mentionsJson(cmd.getMentions()),
                        historyRefsJson(cmd.getHistoryRefs()),
                        null, turnId);
        Recording recording = new Recording(spills, hooks.consultLedger(), tools, hooks, displayNames);

        // 计划模式（框架原生 plan_enter/plan_write/plan_exit）按轮进入，跑完必须退出。
        // 开关没开时问一次意图判断器——不这么做的话「按意图自动开计划」这条手写侧的能力在框架下就是空的
        //（真机实测：只认用户开关时，planMode=false 的一轮即使需要方向对齐也不会出计划卡）
        boolean planRound = cmd.isPlanMode();
        boolean planAuto = !planRound && plan.awaitIntent(planIntent);
        if (planAuto) {
            log.info("[PLAN] 意图识别命中，本轮自动开启计划模式（框架引擎）: session={}", cmd.getSessionId());
        }
        // 计划模式是否真进了激活位：没进的话 plan_* 工具会被框架的 PlanModeMiddleware 滤掉，
        // 症状是「空补全」而非报错——必须显式告警，否则用户以为开了计划其实没生效。
        boolean planWanted = planRound || planAuto;
        boolean planEngaged = planWanted && sessions.setPlanMode(cmd, true);
        if (planWanted && !planEngaged) {
            log.warn("[PLAN] 计划模式请求但未进入激活位（框架侧未就位），本轮不展示计划卡: session={}",
                    cmd.getSessionId());
            channel.emit(RunEventType.WARN, "计划模式未能启用（框架侧未进入计划位），本轮将不使用计划卡");
        }
        // 送进模型的不只是问题原文：附件正文 / @ 引用 / # 历史引用都在这一段拼进去。
        // 此前这里直接送 cmd.getQuestion()，于是用户传的东西模型一概看不见（附件问题表现为
        // 它如实回答「没有看到您上传的文件」）——落库只保证人能回看到，不等于模型收到了。
        String modelInput = userContentWithContext(cmd);
        AgentSession.Task current = session.submit(modelInput);
        // 占住这一条会话的跟随位，收尾让位：答复端点据此判断「这次答完有没有人接着跑」
        String sessionKey = sessions.key(cmd);
        sessions.claimFollower(sessionKey);
        try {
            followTurn(session, cmd, channel, pool, route, hooks, turnId, historyBefore, startCursor,
                    userMessageId, current, planAuto, recording, System.currentTimeMillis());
        } finally {
            sessions.releaseFollower(sessionKey);
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
                            boolean planAuto, Recording recording, long startedAt) {
        long turnDeadline = startedAt + turnSeconds() * 1000L;
        // 接流快照改由本轮自己供：外壳开轮时只有「还没出字」那份空快照，而正文/工具卡/时间线
        // 只活在这里的 Recording 与引用池里。不回填的表现为——生成中刷新，气泡接回来是一片空白。
        channel.offerSnapshot(() -> turnSnapshot(cmd, channel, pool, route, recording, turnId));
        // 人在回路的累计等待（ms）：引擎自己的墙钟上限与共享台账都按「墙钟 − 这段」判超时。
        // 踩过一次——计划卡挂出后用户还在读卡，机器预算却被这段等待吃光，本轮在用户点批准之前
        // 就被判超时截断（真机 2026-10-15 13:59:53，日志写明「人工等待 0ms 不计入」）。
        long humanWaitMs = 0;
        long waitBeganAt = 0;
        boolean waitingOnHuman = false;
        // 用户在场的预算从「卡出现」那一刻起算，不能从轮开始起算：人在回路不计入机器耗时
        // （与手写运行时 TurnDeadline 同一口径）。踩过一次——计划卡 55 秒被批准，但因预算从开轮
        // 起算而在 120 秒线上被判超时，接续执行白跑。也必须有界：否则没人答的卡占死流水线线程
        long pendingSince = 0;
        // 读取起点与播报起点分开定：
        //  - 读取起点是「本轮第一条事件」——续跑那一跑里批准之前跑掉的工具也属于这一轮，
        //    从接回时刻起读会让落库的工具卡只剩半截；
        //  - 播报起点是接回/开轮那一刻，历史帧不重放给观众（前端按 pos 去重只护得住同一条气泡，
        //    跨轮重放等于把上一轮的正文再打一遍）。
        long cursor = turnStartSeq(session, current == null ? null : current.turnId(), startCursor);
        long emitFrom = startCursor;
        int announcedRefs = 0;
        Set<String> announcedApprovals = new LinkedHashSet<>();
        // 流式用量帧的「上一次算过的 transcript 条数」，见 emitLiveUsage
        long[] usageStamp = { -1 };
        boolean stopped = false;
        // 等待预算用尽：卡片已落终态，收尾时不再对用户说「答完会自动接着跑」
        boolean timedOut = false;
        // 本轮有没有真的被框架启动。投递循环 100ms 一跳，正常几百毫秒内就离队；被闸门跳过时它会
        // 永远停在 queued，而「没启动」这件事框架自己不报错——不另设一条早于整轮预算的判据，
        // 用户看到的就只是「转了五分钟然后说超时」，一个字也没有（真机 sbx-3 三连中）。
        boolean launched = false;
        boolean neverLaunched = false;
        long launchDeadline = startedAt + LAUNCH_GRACE_MS;

        // 一整轮里待办可能出好几次：批准之后模型仍可再要一次确认。所以这里不是「等一次答复」，
        // 而是跟随会话现状——有待办就等人，没待办就跟到最后一条任务，任务结算了还要再看一眼
        // 有没有新待办或接续出来的新任务，两者都没有才算这一轮跑完（真机踩过：只等一次就收尾，
        // 第二次确认的卡永远发不出去，正文也是空的）
        while (System.currentTimeMillis() - humanWaitMs < turnDeadline) {
            if (!launched && current != null && !"queued".equals(current.status())) launched = true;
            cursor = drain(session, cursor, emitFrom, channel, recording);
            emitLiveUsage(session, cmd, pool, channel, historyBefore, usageStamp);
            announcedRefs = emitRetrieved(cmd, pool, channel, announcedRefs, recording);
            // 用户点了「继续对话」：这一轮就此收口——修改意见由前端作为一条用户消息开出完整新一轮
            // （与手写引擎同一契约）。框架那一跑是被 reject 唤醒的，它会自己往下重出一版计划，
            // 不掐断就是把那一跑的帧漏进下一轮；而前端紧跟着发的新请求还会撞上会话轮级互斥，
            // 用户看到的是「生成失败」。排在 announcePending 之前：否则掐断之前先给用户多弹一张卡。
            if ("supersede".equals(sessions.peekPlanOutcome(sessions.key(cmd)))) {
                session.interrupt();
                break;
            }
            announcePending(session, cmd, channel, announcedApprovals, planAuto, turnId, recording);
            if (tools.stopRequested(cmd)) {
                // 框架的中断是协作式的：在飞工具会跑到结束（Phase 0 实测）。这里只发中断，
                // 「本轮已停止」的留痕仍由问答侧那套收束逻辑做——不假装能当场掐断工具。
                session.interrupt();
                stopped = true;
                break;
            }
            // 只有「确实没人在跑这一条会话」才算没启动：另一线程正驱动它时，本轮排在队列里是正常状态。
            // current 为 null 是续跑那一跑（不是新提问，手里没有本轮命令），对它没有「启动」可判
            if (!launched && current != null && System.currentTimeMillis() > launchDeadline
                    && session.pending().isEmpty() && session.inspect().activeRuns().isEmpty()) {
                neverLaunched = true;
                break;
            }
            if (!session.pending().isEmpty()) {
                if (pendingSince == 0) {
                    pendingSince = System.currentTimeMillis();
                    // 人在回路的等待登记进共享台账（与手写侧 PlanModeService/ChatService 同一入口）：
                    // 这一段是「用户在看卡/在答题」，不是机器在干活，不该消耗整轮机器预算。
                    // 不登记的后果是本轮必然在 300s 线上被看门狗误截断——真机 2026-10-15 13:59:53 实测：
                    // 计划卡 13:57:46 挂出、用户还没点批准就被判「机器耗时 300087ms / 预算 300000ms
                    // （人工等待 0ms 不计入）」，界面上就是「一直 loading 到超时」。
                    // 引擎自己的 turnDeadline 同理要在下面按同一口径扣掉，否则卡一挂出这一轮就只剩几十秒。
                    beginHumanWait(channel, recording, session);
                    waitBeganAt = System.currentTimeMillis();
                    waitingOnHuman = true;
                }
                // 三张卡三档等待预算（计划/提问/工具审批），判据与 announcePending 分流同源
                PendingKind kind = pendingKind(session);
                long budget = pendingBudgetMs(kind);
                if (System.currentTimeMillis() - pendingSince > budget) {
                    if (kind == PendingKind.ASK && answerTimedOutAsk(session, cmd)) {
                        // 提问卡超时**不收这一轮**：用户暂时不在 ≠ 用户拒绝。把「这一题没回答」如实送回
                        // 工具，模型基于已有信息继续推进（与手写侧 askTimeoutMs 分支同一语义）。
                        // 送不出去（会话已不在）才落到下面那条收尾。
                        channel.emit(RunEventType.WARN,
                                "提问等待超时，已按「未作答」继续（不会替你选答案）");
                        pendingSince = 0;
                        humanWaitMs += System.currentTimeMillis() - waitBeganAt;
                        waitBeganAt = 0;
                        waitingOnHuman = false;
                        endHumanWait(channel, recording);
                        continue;
                    }
                    // 超时一律不替用户代答（既有口径）：卡片作废，这一轮按未完成收
                    channel.emit(RunEventType.WARN, kind == PendingKind.PLAN
                            ? "计划等待超时，本轮未继续执行"
                            : "确认等待超时，本轮未继续执行该工具");
                    sessions.markPendingTimeout(sessions.key(cmd));
                    timedOut = true;
                    break;
                }
                sleep(300);
                continue;
            }
            // 待办清空 = 用户答复完毕，这段等待从此刻起不再计入预算（台账与引擎墙钟同一口径）
            if (waitingOnHuman) {
                humanWaitMs += System.currentTimeMillis() - waitBeganAt;
                waitBeganAt = 0;
                waitingOnHuman = false;
                endHumanWait(channel, recording);
            }
            pendingSince = 0;
            AgentSession.Task latest = latestTaskInTurn(session, current);
            if (latest != null) current = latest;
            if (current == null || current.settled()) {
                // 结算与待办之间没有原子边界：先让出一拍再确认一遍，避免抢在下一张卡之前收尾
                sleep(150);
                cursor = drain(session, cursor, emitFrom, channel, recording);
                announcedRefs = emitRetrieved(cmd, pool, channel, announcedRefs, recording);
                announcePending(session, cmd, channel, announcedApprovals, planAuto, turnId, recording);
                if (!session.pending().isEmpty()) continue;
                AgentSession.Task newer = latestTaskInTurn(session, current);
                if (newer != null && !newer.settled()) {
                    current = newer;
                    continue;
                }
                if (newer != null) current = newer;
                // 迟到的待办：任务判「结算」与待办出现之间没有原子边界，只复查 150ms 那一次会漏。
                // 漏掉的后果是这张卡永远悬着——用户答了也没有活着的循环去接续（框架侧那轮已经收）。
                // 所以这里宁可再等一拍：等下去也有预算封顶（approvalTimeoutMs），不会无限挂着。
                if (!session.pending().isEmpty()) {
                    sleep(200);
                    continue;
                }
                break;
            }
            sleep(200);
            current = session.task(current.commandId());
        }
        // 兜底：超时收尾 / stopRequested 跳出都可能停在「仍在等人」的状态上。
        // 不收口的话台账的 openWaits 永远 >0，之后这一轮算出来的机器耗时永远是负数/偏小，
        // 看门狗形同虚设；而引擎自己的墙钟已经进了下一轮比较，必须先把这段补齐再往下走。
        if (waitingOnHuman) {
            humanWaitMs += System.currentTimeMillis() - waitBeganAt;
            waitBeganAt = 0;
            waitingOnHuman = false;
            endHumanWait(channel, recording);
        }
        drain(session, cursor, emitFrom, channel, recording);

        // 计划卡裁决的界面终态：拒绝=plan_cancelled、继续对话=plan_superseded。不补的表现为
        // 用户已经拒了计划，那张卡却还挂着「等待中」（控制流仍归框架判，这里只补留痕帧与 done 标记）
        String planOutcome = sessions.takePlanOutcome(sessions.key(cmd));
        boolean superseded = "supersede".equals(planOutcome);
        if (superseded) channel.emit(RunEventType.PLAN_SUPERSEDED, "{}");
        else if ("reject".equals(planOutcome)) channel.emit(RunEventType.PLAN_CANCELLED, "{}");
        // 裁决过的计划卡随助手消息落 plan 列（字段与手写侧 planSnapshot 同名：plan/status/versions/auto）。
        // 不落的表现：刷新后只剩「还在等确认」的那一张（它由待办恢复端点重建），已批过的每张计划都消失，
        // 用户回头看不出这一轮是按哪一版计划跑的。
        Map<String, Object> planRecord = null;
        if (planOutcome != null && !recording.planVersions.isEmpty()) {
            Map<String, Object> last = recording.planVersions.get(recording.planVersions.size() - 1);
            planRecord = new java.util.LinkedHashMap<>();
            planRecord.put("plan", last.get("plan"));
            planRecord.put("status", "approve".equals(planOutcome) ? "approved"
                    : "supersede".equals(planOutcome) ? "superseded" : "rejected");
            planRecord.put("versions", new java.util.ArrayList<>(recording.planVersions));
            if (recording.planAuto) planRecord.put("auto", true);
        }

        if (!stopped && !timedOut && session.pending().size() > 0) {
            // 「答完会自动接着跑」只对还活着的等待成立：超时那一支已经把待办落成终态，
            // 再说这句就是在承诺一件不会发生的事
            channel.emit(RunEventType.WARN, "有工具在等你确认，答完会自动接着跑（可稍后回来接着看它）");
        }
        sessions.setPlanMode(cmd, false);
        // 正文取哪一份：优先本轮实际流出来的那一截（与发出去的 token 帧同源）；只有它为空、且这一轮
        // 本身没失败，才回退到会话里最后一条有正文的助手消息。反过来先查会话会出两件坏事：被叫停的那一跑在 Transcript 里
        // 没有 committed text，取到的是<b>上一轮的答复</b>——拿别人的话给这一轮存档；真机踩过停止轮
        // 库里 0 行，就是这个取序的另一面（那一会话恰好只有一轮，所以表现为空而不是串轮）。
        // 流末尾留着没定性的尾巴先归位（它可能只是正文的最后几个字）
        recording.answer(recording.related.finish());
        String answer = recording.answerText();
        // 失败判据必须排在回填前面：回填不问这一轮是不是失败的那一跑，上游 401 的轮因此会顶着
        // 「答好了」的样子把上一轮的正文存进历史——真机 2026-10-15 20:00:49 那轮 401，库里多出一条
        // 597 字助手行、model 记的正是失败那个模型，界面上只收到一个正常 done，失败一个字没提。
        String failure = runFailure(session, current);
        if (answer.isBlank() && failure == null) answer = lastAssistantText(session);
        // 框架那一跑本身失败（上游 402 额度、网关异常、运行被拒……）必须说出来的那一件事：双跑实测——
        // 同一问题 legacy 发 error 帧，框架只发了一条「done + 空正文」，用户看到的是「它答了个空」，
        // 而不是「这次没答出来，原因是额度不足」。失败判据用框架自己留的 executionError / 任务终态。
        if (neverLaunched) {
            // 单独一句而不是套用「AI 回复失败，请稍后重试」：这一轮模型根本没被调用，重试同样的坑还在，
            // 得让用户知道该换个动作
            pool.registrar().degrade("frameworkNotLaunched", "框架引擎本轮未启动执行（提交停在队列里）");
            channel.emit(RunEventType.ERROR,
                    "框架引擎本轮没有开始执行（模型未被调用），请重试；反复出现请新建一条会话");
            log.warn("[FAIL-LOUD] 本轮 submit 始终未被框架启动: session={} turn={} 空等 {}ms",
                    cmd.getSessionId(), turnId, System.currentTimeMillis() - startedAt);
        } else if (!stopped && failure != null && answer.isBlank()) {
            emitRunFailure(pool, channel, failure);
        }
        // 卡片还开着、且没有被判超时：这一轮只是暂时没人跟——用户答了会有新的跟随者把它跑完并落库。
        // 这时候落一条「已中断」就是两件坏事：它说的话是假的（这轮并没有结束），等真正的答案
        // 来了历史里还多出一条气泡（同一个问题两个助手回答）。
        // 继续对话取代的那一轮不算「暂时没人跟」：中断之后不会再有跟随者，而这一版的「已被取代」
        // 必须随行落库（历史里要有这一版计划，新一轮也靠它取上一版），所以放行到 persist。
        boolean awaiting = !timedOut && !superseded && !session.pending().isEmpty();
        long wallMs = System.currentTimeMillis() - startedAt;
        // 报出去的「本轮耗时」说的是机器干活用了多久，不是这一轮在墙上挂了多久：人在计划卡上读了
        // 三分钟，读成「它答了三分多钟」就是把人自己的时间记到了机器账上。同一句里把两个数都留下，
        // 截断与卡死排障要看的是墙钟，成本与性能看的是机器时间——少一个数就必然有人拿错一个数对账。
        log.info("[TURN] 本轮收口: session={} 机器耗时={}ms 墙钟={}ms 人工等待={}ms",
                cmd.getSessionId(), Math.max(0L, wallMs - humanWaitMs), wallMs, humanWaitMs);
        finish(cmd, channel, pool, route, hooks.modelRef(), answer, recording, userMessageId, stopped,
                timedOut, awaiting, Math.max(0L, wallMs - humanWaitMs), humanWaitMs, wallMs,
                session, historyBefore, turnId, planRecord);
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
     * 把框架留下的失败痕迹说成用户能行动的一句话。判据与文案都取 legacy 那一组
     * （{@code ChatService} 的 stream-error 分支：额度／限流／连不上／其它）——同一件 402 在两条
     * 运行时里必须长成同一句话，否则灰度一切换，用户看到的指引就换了。
     */
    private void emitRunFailure(WenquSourcePool pool, RunChannel channel, String raw) {
        boolean quota = com.wenqu.ai.service.ModelQuotaGuard.isQuotaText(raw);
        boolean limited = !quota && com.wenqu.ai.service.ModelQuotaGuard.isRateLimitedText(raw);
        boolean unreachable = !quota && !limited
                && raw.toLowerCase(java.util.Locale.ROOT).contains("connect");
        String msg = quota ? "模型服务额度不足：请到「模型供应商」充值，或改用其他模型"
                : limited ? "模型服务限流，请稍后重试"
                : unreachable ? "无法连接 AI 服务，请检查网络或 API 地址"
                : "AI 回复失败，请稍后重试";
        pool.registrar().degrade(quota ? "modelQuotaExhausted" : "streamError", "模型输出中断：" + msg);
        channel.emit(RunEventType.ERROR, msg);
        log.info("[agentx] 本轮失败收束: {} 原文={}", msg, raw.length() > 200 ? raw.substring(0, 200) : raw);
    }

    /**
     * 归档「以失败收场却没被框架当成结束」的上一轮，否则这条会话从那一刻起每一轮都不会启动。
     *
     * <p>框架的投递循环只在上一轮 {@code ended()} 时才 launch 新的 submit，而它的 ended 判据只认
     * {@code completed}／{@code cancelled}——上游一次 401 留下的 {@code run/end status=failed}
     * 因此永远不算结束。真机踩过（session=sbx-3，2026-10-15 19:33 那轮 401）：此后同一条会话的每一轮
     * 都只写进收件箱（{@code inbox/accepted}）而没有一条 {@code inbox/started}，模型请求一次也没发出，
     * 界面上是「转到 300s 被看门狗截断、正文一个字也没有」，日志里连 {@code PRE_CALL} 都没有。
     *
     * <p>只在「那一轮确实不会再动了」时动手：还在跑（activeRuns）、挂着人在回路的待办（pending）、
     * 或有待对账的工具结果（uncertainToolCalls，框架要求先 reconcile）时一律不碰。
     */
    private void reconcileStalledTurn(AgentSession session, ChatCommand cmd) {
        SessionLog journal = session.log();
        SessionTurns.Turn latest;
        SessionProjection view;
        try {
            latest = SessionTurns.read(journal).latest();
            if (latest == null || latest.ended()) return;
            view = session.inspect();
        } catch (RuntimeException unreadable) {
            log.warn("[agentx] 读会话轮次台账失败 session={}: {}", cmd.getSessionId(), unreadable.toString());
            return;
        }
        if (!view.activeRuns().isEmpty() || !view.uncertainToolCalls().isEmpty()
                || !session.pending().isEmpty()) return;
        // 先丢弃积压再归档：闸门还关着时投递循环不会 launch，这一步才有竞争安全性。
        // 反过来先归档，那些旧提交会在几十毫秒内被倒着补跑，正文灌进正在跟的这一轮的气泡里。
        discardUnstartedSubmits(journal, cmd);
        try {
            SessionRecovery.cancelPending(journal, latest.turnId());
        } catch (RuntimeException refused) {
            log.warn("[agentx] 遗留轮 {} 归档失败 session={}: {}",
                    latest.turnId(), cmd.getSessionId(), refused.toString());
            return;
        }
        log.warn("[FAIL-LOUD] 上一轮以 {} 收场未被框架记成结束，已按取消归档（不归档则这条会话之后每轮都不启动）:"
                + " session={} turn={}", latest.status(), cmd.getSessionId(), latest.turnId());
    }

    /**
     * 作废「进了收件箱但从未启动」的旧提问。
     * <p>它们是上一轮卡住时被判超时的提问，用户侧已经收到过「超时截断」；留着不管，闸门一开就会被补跑，
     * 后果是几分钟后凭空冒出旧问题的回答。
     */
    private void discardUnstartedSubmits(SessionLog journal, ChatCommand cmd) {
        try {
            new SessionInbox(journal).locked(tx -> {
                SessionInbox.Snapshot snapshot = tx.snapshot();
                Set<String> started = SessionTurns.read(journal).started();
                for (SessionInbox.Command command : snapshot.commands()) {
                    if (!"submit".equals(command.kind()) || started.contains(command.id())
                            || snapshot.rejected().containsKey(command.id())) continue;
                    tx.reject(command, "上一轮遗留、从未启动，已作废");
                    log.warn("[FAIL-LOUD] 作废一条从未启动的积压提问 turn={} session={}",
                            command.turnId(), cmd.getSessionId());
                }
                return null;
            });
        } catch (RuntimeException failed) {
            log.warn("[agentx] 积压提问清理失败 session={}: {}", cmd.getSessionId(), failed.toString());
        }
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
                             AgentSession session, int historyBefore, long startCursor,
                             Recording recording) {
    }

    /**
     * 为一轮续跑装配出本轮上下文：按归属重建（或复用）常驻会话实例、建通道、建引用池与本轮身份。
     * <p>通道挂在收集型 emitter 上——这一跑未必有真实观众，但事件仍走同一条 {@code sendSseEvent}
     * （账本、运行总线、落库都不变），断线重连那一份 HTTP 响应才是观众。
     *
     * @return null = 装配不动（模型解析不出来、实例建不起来），调用方按「接不动」处理
     */
    private Following openForContinuation(String sessionId, String userId, String turnId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        ChatCommand cmd = ChatCommand.builder().sessionId(sessionId).question("")
                .userId(userId == null || userId.isBlank() ? "guest" : userId).build();
        com.wenqu.ai.service.ChatService.TurnModel turn = modelResolver.apply(cmd);
        String ref = turn.usable() ? turn.ref() : "";
        if (ref.isBlank()) return null;
        ModelRegistryService.ModelRoute route = modelRegistry.chatRoute(ref);
        if (route == null) return null;
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter sink =
                new com.wenqu.ai.service.CollectingSseEmitter();
        RunChannel channel = new com.wenqu.ai.engine.SseRunEventChannel(
                tools.runtime(), sink, sessionId, false);
        ExecutionPolicy policy = tools.policyFor(cmd, (code, message) -> {
        });
        WenquSourcePool pool = tools.newPool(cmd, sink);
        java.util.concurrent.ConcurrentLinkedQueue<String> spills =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        WenquAgentxTools.TurnHooks hooks = tools.newHooks(cmd, ref, () -> turnId, spills,
                consultService.newLedger());
        AgentSession session;
        try {
            session = sessions.open(cmd, ref,
                    ctx -> buildAgent(cmd, channel, pool, policy, route, hooks));
        } catch (Exception e) {
            log.warn("[agentx] 重建会话失败 session={}: {}", sessionId, e.toString());
            return null;
        }
        AgentxStepBudget stepBudget = sessions.stepBudget(sessions.key(cmd));
        if (stepBudget != null) {
            stepBudget.beginTurn(turnId, tools.maxToolSteps(cmd), (code, message) -> {
            });
        }
        Recording recording = new Recording(spills, hooks.consultLedger(), tools, hooks, displayNames);
        // respond 之后框架才会往日志里追加接续那一段的事件，所以这里的 head 就是「接回之前」
        return new Following(cmd, channel, pool, route, hooks, session,
                session.transcript().messages().size(), session.log().head().seq(), recording);
    }

    /** 占到跟随位才起这一跑：同一条会话不许有两个循环对着跟（各读一份事件流就会各落一条助手消息）。 */
    private void startFollowing(Following f, AgentSession.Task current) {
        String sessionKey = sessions.key(f.cmd());
        if (!sessions.reserveFollower(sessionKey)) {
            log.info("[agentx] 这一轮已经有跟随者在跑，不重复起 session={}", f.cmd().getSessionId());
            return;
        }
        RESUME_POOL.execute(() -> {
            try {
                followTurn(f.session(), f.cmd(), f.channel(), f.pool(), f.route(), f.hooks(),
                        turnIdOf(f.cmd()), f.historyBefore(), f.startCursor(), null, current, false,
                        f.recording(), System.currentTimeMillis());
                log.info("[agentx] 续跑已收尾 session={}", f.cmd().getSessionId());
            } catch (Exception e) {
                log.warn("[agentx] 续跑中断 session={}: {}", f.cmd().getSessionId(), e.toString());
            } finally {
                sessions.releaseFollower(sessionKey);
            }
        });
    }

    /**
     * 进程重启后接回一条待办并把它跑完（{@link FrameworkSessions.ResumeHandler} 的实现）。
     * <p>为什么能做：框架的会话状态本来就持久化在 stateStore 里，缺的只是「内存里那条常驻实例」。
     * 按表里的归属重建实例、respond 进去、跟着跑到收尾并落库，用户刷新就能看到那一轮的答案——
     * 而不是像现在这样，一次「已批准」之后什么都没发生。
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
     * <p>不补的表现为「答了却什么都没发生」——真机实测到的正是这一面：续跑那一跑重新执行了工具、模型也
     * 重新推理出了正文，事件全在框架的 Session Log 里，只是没有人在读，正文也就没落库。
     */
    @Override
    public void follow(String sessionId, String userId, String turnId) {
        if (sessions.hasFollower((userId == null || userId.isBlank() ? "guest" : userId) + "|" + sessionId)) {
            return;
        }
        Following f = openForContinuation(sessionId, userId, turnId);
        if (f == null) return;
        startFollowing(f, taskForTurn(f.session(), turnId));
    }

    /** 这一轮此刻最后那条任务（接续执行起的是新任务，同轮号不同 runId）。 */
    private static AgentSession.Task taskForTurn(AgentSession session, String turnId) {
        AgentSession.Task last = null;
        for (AgentSession.Task t : session.tasks()) {
            last = t;
            if (turnId != null && turnId.equals(t.turnId())) return t;
        }
        return last;
    }

    /** 这一轮的上下文预算（token）：用户在这一轮显式选过的窗口优先，未选交给框架默认。 */
    private int contextTokens(ChatCommand cmd) {
        Integer requested = cmd.getRequestedContextWindow();
        return requested == null || requested <= 0 ? configService.getInt("chat.agentscopeMaxContextTokens", 0)
                : requested;
    }

    /**
     * 本轮墙钟上限（秒），恒定取问答侧 SSE 超时：两套运行时共用一个天花板。
     * <p>框架（AgentScope）对单把工具有自带的 5 分钟硬超时（{@code ExecutionConfig.TOOL_DEFAULTS}，
     * 不重试），不显式覆盖就会多一道没人知道的天花板——咨询子轮、沙盒长命令都会撞上去
     * （实测：咨询跑满 5 分钟被判工具失败，父轮只收到一句 Tool execution timeout）。
     * 工具超时跟着整轮超时走：SSE 调大时长工具不会被框架固定的 5 分钟砍掉。
     * <p>此前这里是可配项（chat.agentscopeTurnTimeoutSeconds，0=沿用 SSE 超时），默认态两者同值，
     * 属「框架已有限制、参数只留个默认就用不上的改口」，已取消——需要单独给框架引擎配墙钟的
     * 场景不存在：整轮超时本来就是唯一该有的天花板。
     */
    private int turnSeconds() {
        return Math.max(30, configService.getInt("chat.sseTimeoutMs", 300000) / 1000);
    }

    /**
     * 开局就判不了的收场：error 说清原因，再补一条最小 done——SSE 读循环「流关闭却没有 done」
     * 会把它误报成「连接被提前关闭」，用户看到的就是莫名其妙的中断错。
     */
    private void failLoud(RunChannel channel, String message) {
        channel.emit(RunEventType.ERROR, message);
        channel.emit(RunEventType.DONE,
                "{\"finalContent\":\"\",\"sources\":[],\"degradations\":[],\"engine\":\"agentscope\"}");
        channel.complete();
    }

    /**
     * 待办的三种形态：决定用哪一份等待预算、超时说哪句话、能不能当提问处理。
     * <p>为什么必须分清「提问卡」：提问与审批在产品里是<b>两件事</b>——审批是「这道闸你放不放行」，
     * 放着不动等于默认拦；提问是「这句话我需要你拍板」，人暂时不在不该被当成拒绝，更不该把整轮掐掉。
     * 混为一谈的后果是真机 2026-10-15 复现的那一条：模型问「你打算把问渠部署到什么环境」，
     * 用户两分钟没回（读文档、开会），这一轮就被判超时收尾，只留下一句
     * 「等待确认超时，这一轮没有继续执行」——本该由模型自己拿默认值继续答完。
     */
    private enum PendingKind { ASK, PLAN, APPROVAL }

    /** 供回归测试断言用（枚举本身是私有的，测的是「哪种卡归哪一档」这个判据）。 */
    static String pendingKindName(java.util.List<Map<String, Object>> datas) {
        return pendingKindOf(datas).name();
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
    private static void beginHumanWait(RunChannel channel, Recording recording, AgentSession session) {
        if (channel.emitter() != null) TurnRegistry.beginHumanWait(channel.emitter());
        recording.beginToolWait(pendingCallKeys(session));
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
    private static java.util.List<String> pendingCallKeys(AgentSession session) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SessionEvent e : session.pending().values()) {
            Map<String, Object> data = e.data() == null ? Map.of() : e.data();
            Object call = firstNonNull(data.get("call"), data);
            String name = toolNameOf(call);
            String id = call instanceof Map<?, ?> cm && cm.get("id") != null ? String.valueOf(cm.get("id")) : null;
            if (name.isBlank() && (id == null || id.isBlank())) continue;
            out.add(Recording.toolCallKey(id, name));
        }
        return out;
    }

    /** 这一批待办按哪种形态处理：只要有提问卡就按提问走（提问最需要的是不替用户做决定）。 */
    private static PendingKind pendingKind(AgentSession session) {
        java.util.List<Map<String, Object>> datas = new java.util.ArrayList<>();
        for (SessionEvent e : session.pending().values()) {
            datas.add(e.data() == null ? Map.of() : e.data());
        }
        return pendingKindOf(datas);
    }

    /**
     * 形态判定（与 {@link #announcePending} 的分流同源，判据不能两处各写一份）。
     * <p>拆成纯函数是为了能单独验：这一段历史上唯一的 bug 就是「只分计划与其余」，
     * 而它一旦退化，表现是「用户两分钟没答一句提问，整轮就没了」——只在真机上看得见，编译与单测都拦不住。
     *
     * @param datas 框架待办各自的 {@code data}（{@link SessionEvent#data()}）
     */
    static PendingKind pendingKindOf(java.util.List<Map<String, Object>> datas) {
        for (Map<String, Object> data : datas == null ? java.util.List.<Map<String, Object>>of() : datas) {
            String kind = str(data.get("kind"));
            if ("confirmation".equals(kind)
                    && toolNameOf(firstNonNull(data.get("call"), data)).startsWith("plan_")) {
                return PendingKind.PLAN;
            }
            // 非 confirmation 的挂起（实测 kind=external_execution）就是人在回路的提问卡；
            // 未知 kind 也归提问——宁可多等、也别把人的一句话当成拒绝
            if (!"confirmation".equals(kind)) return PendingKind.ASK;
        }
        return PendingKind.APPROVAL;
    }

    /**
     * 各形态的等待预算，与手写侧同一组配置键（{@code HitlDecisionService} 的三个读取口）：
     * 提问 {@code chat.askTimeoutMs}、计划 {@code chat.planTimeoutMs}、工具审批 {@code chat.approvalTimeoutMs}。
     * <p>此前这一段只分「计划 vs 其余」，提问卡被塞进了 {@code approvalTimeoutMs}（默认 120s）——
     * 比它本该有的 10 分钟短了 5 倍，而提问恰恰是最需要人从容看完题面的一类。
     */
    private long pendingBudgetMs(PendingKind kind) {
        return switch (kind) {
            case PLAN -> configService.getLong("chat.planTimeoutMs", 600000L);
            case ASK -> configService.getLong("chat.askTimeoutMs", 600000L);
            case APPROVAL -> configService.getLong("chat.approvalTimeoutMs", 120000L);
        };
    }

    /**
     * 提问卡等满预算：把「这一题用户没回答」当成答案送回原工具，让模型接着往下走，
     * <b>不</b>把这一轮掐掉。
     * <p>与手写侧 {@code ChatService} 的超时分支同一语义（那边是
     * {@code buildCombinedAnswer(..., "TIMEOUT")} 回一句「用户未回答这一题」）：超时是「用户不在」，
     * 不是「用户拒绝」，也不是「这一轮作废」。此前框架侧只有「超时即收轮」一条路，
     * 于是没人答一次提问，整轮就没了——真机实测到的正是这一条。
     * <p>走 {@code respondRaw} 而不是 {@code respondAsk}：后者末尾会 {@code ensureFollowing} 补起跟随者，
     * 而这里本来就有一个活着的跟随循环在跑，补第二个会有两个循环读同一条会话、各落一条助手消息。
     */
    private boolean answerTimedOutAsk(AgentSession session, ChatCommand cmd) {
        String sessionKey = sessions.key(cmd);
        for (Map.Entry<String, SessionEvent> e : session.pending().entrySet()) {
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

    /** 同一轮里最后出现的那条任务：{@code respond} 接续出来的是新任务（同 turnId、新 runId）。 */
    private static AgentSession.Task latestTaskInTurn(AgentSession session, AgentSession.Task known) {
        if (known == null) return null;
        AgentSession.Task latest = null;
        for (AgentSession.Task t : session.tasks()) {
            if (known.turnId().equals(t.turnId())) latest = t;
        }
        return latest;
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
         * 助手消息」拿到的是<b>上一轮的答复</b>，等于拿别人的话给这一轮存档（真机踩过：停止后库里 0 行，
         * 而同一份代码在多轮会话里就会落成上一轮的内容）。
         */
        private final StringBuffer answerText = new StringBuffer();
        private final java.util.List<java.util.Map<String, Object>> toolCalls =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
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
         * tool/decision 与 tool/result 的载荷不一定再带工具名，从 requested 帧沿用（实测）。
         * <p>记在这里而不是引擎字段上：引擎 bean 是单例、两条会话可以同时在跑，挂在实例上就是
         * 甲轮的工具卡收了乙轮的尾巴。
         */
        private String lastToolName = "";
        private String lastToolInput = "";
        /**
         * 每次调用的入参，按 {@link #toolCallKey} 配回<b>它自己那一次</b>调用。
         * <p>为什么不能共用 {@link #lastToolInput} 那一个字段：模型一步里可以同时点两把工具（框架并发派发），
         * 后到的 requested 会盖掉先到的入参，先那把的 done 帧就带着别人的参数下发。而前端
         * {@code onToolStatus} 收终态是「按名字找最近那张 start 卡并把 args 写回去」，于是那张卡的
         * 「入参」整段变成另一个工具的参数（真机：整理任务清单的卡显示成 {@code {"query":"断线重连"}}）。
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
         * 本轮 {@code plan_write} 工具写入的计划正文（框架 {@code PlanWriteTool} 的入参 {@code content}）。
         * <p><b>为什么必须单独攒</b>：框架的计划正文走 {@code plan_write} 落到工作区 {@code plans/PLAN.md}，
         * 而 {@code plan_exit} 的入参只有一个可选的 {@code summary}（一句话摘要，见 {@code PlanExitTool}
         * 的 inputSchema）——正文不在退出确认的入参里。问渠的计划卡要展示「整份计划」，
         * 早前直接拿 {@code plan_exit} 的入参当 plan 字段，用户看到的就是 {@code [object Object]}。
         * <p>同轮多次 {@code plan_write} 是覆盖语义（框架侧 replace the file），保留最后一份即可。
         */
        private String planMarkdown = "";
        /** 已实时下发过的过程独白长度：{@code related.process()} 是累积值，按长度差取增量发 PROCESS。 */
        private int lastProcessLen;
        /** 每次调用的开始时刻（与入参同一个配对键），终态时算耗时下发——前端工具卡读 elapsedMs，缺它就显示 0ms。 */
        private final Map<String, Long> toolStartMs = new java.util.LinkedHashMap<>();
        /**
         * 在飞工具各自要<b>扣掉</b>的「等人」时长（配对键 → 累计毫秒）。
         * <p>提问卡/计划卡/审批卡挂出到用户点完，那一段既在工具的开始-结束窗口里、又不是机器在干活。
         * 不拆出去的后果：一张让人思考了三分钟的提问卡，卡片自己写着「3m12s」，读起来就是「这个工具慢得离谱」。
         * <p>扣账摊给<b>等待期间仍在飞的每一把</b>，不是只摊给挂卡那一把：人在回路的这道闸门卡住的是
         * 整个工具步，同一步里别家的 result 也要等用户点完才到。真机 08:55 那一轮的账：
         * {@code plan_write} 的窗口 160610ms，其中 160288ms 是用户在读 {@code plan_exit} 的批准卡，
         * 而 plan_write 自己只跑了三百毫秒——只摊给挂卡那一把时，这张卡就顶着 2m40s 上界面。
         */
        private final Map<String, Long> toolWaitDeduct = new java.util.LinkedHashMap<>();
        /**
         * 每段等待<b>记在哪一张卡上</b>（对外下发与落库的那份 {@code waitedMs}）。
         * <p>一张闸门只记一笔：前端整段跨度算的是「首尾之差 − 各张卡 waitedMs 之和」，同一段时间摊到两张
         * 卡上就扣了两遍——真机那一轮两个 160288 相加 320576ms，比整个 169901ms 的窗口还长，跨度被夹成 0。
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
         * 回答时间线（与 legacy {@code TurnRecorder} 同一套段格式：正文区间段 / 工具下标段 / 思考区间段）。
         * <p>不落库的后果：刷新后前端拿不到交错点，{@code hasTimelineBlocks} 为假，工具卡掉进
         * 「历史恢复兜底」列表、沉到正文下方——实时看着对、刷新就错位。
         */
        private final List<Map<String, Object>> timeline =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /** 正文区间段的锚点（已成段的正文长度），与 legacy {@code TurnRecorder.timelineAnchor} 同义。 */
        private int timelineAnchor;

        /** 把锚点到 pos 之间的正文记为一个文本段（无新增则忽略）；插工具段前调用，保持「正文—工具」交错。 */
        private void flushTimelineText(int pos) {
            if (pos <= timelineAnchor) return;
            timeline.add(Map.of("kind", "text", "from", timelineAnchor, "to", pos));
            timelineAnchor = pos;
        }

        /**
         * 落库用时间线：工具段按调用顺序夹在正文段之间，末段补到 body 全长（含追加的相关推荐/中断标记）。
         * <p>body 若被引用自检整体替换过（{@code outcome.text()}），正文区间就对不上了——此时返回 null，
         * 让前端走「历史恢复兜底」，宁可不交错也不能把正文切错。
         */
        String timelineJson(String body, String answerText) {
            if (body == null || !body.startsWith(answerText == null ? "" : answerText)) return null;
            flushTimelineText(body.length());
            return timeline.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(timeline);
        }

        /**
         * 思考段是否还开着：框架没有「思考阶段结束」这个事件，只能在正文块或工具调用到来时补收尾帧。
         * <p>{@code thinkFrom} 记这一段在累计全文里的起点——一次问答常有多轮思考（每轮工具调用前各一次），
         * 只交全文会让它们首尾粘成一块；按轮成段写进时间线，前端才能把「它在哪一步想了什么」就地渲染。
         */
        private boolean thinkingOpen;
        private int thinkFrom;
        /**
         * {@code <related>}／{@code <process>} 的流式剥离器：正文增量先过它再下发。
         * <p>不接的表现为推荐块标签原文印在回答里——那句「回答末尾用 &lt;related&gt;…」的提示词
         * 是两条运行时共用的（{@code RelatedPrompt} 一处出处），提示了就得负责剥。
         */
        private final RelatedStreamFilter related = new RelatedStreamFilter();
        /**
         * 本轮出过的计划卡（按版本顺序）。裁决之后随助手消息落 {@code plan} 列——
         * 否则刷新后只剩「还在等确认」的那张（它由待办恢复端点重建），已批过的每张计划都消失，
         * 用户回头看不出这一轮到底是按哪一版计划跑的。
         */
        private final java.util.List<Map<String, Object>> planVersions = new java.util.ArrayList<>();
        private boolean planAuto;

        Recording(java.util.Queue<String> spills,
                  com.wenqu.ai.agent.subagent.SubAgentConsultService.Ledger consultLedger,
                  WenquAgentxTools tools, WenquAgentxTools.TurnHooks hooks,
                  ToolDisplayNames displayNames) {
            this.spills = spills;
            this.consultLedger = consultLedger;
            this.tools = tools;
            this.hooks = hooks;
            this.displayNames = displayNames;
        }

        void thinking(String delta) {
            if (delta == null || delta.isEmpty()) return;
            if (!thinkingOpen) {
                thinkingOpen = true;
                thinkFrom = thinking.length();
            }
            thinking.append(delta);
        }

        /** 正文增量：与发给前端的 {@code token} 帧同源，中断轮靠这一份存档（见 {@link #answerText}）。 */
        void answer(String delta) {
            if (delta != null) answerText.append(delta);
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
            return thinking.toString();
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
         */
        void toolCall(String name, String status, String args, long elapsedMs, long beganAtMs, long waitedMs) {
            if (name == null || name.isBlank()) return;
            boolean runCode = com.wenqu.ai.service.SandboxTools.RUN_CODE_TOOL_NAME.equals(name);
            for (int i = toolCalls.size() - 1; i >= 0; i--) {
                java.util.Map<String, Object> prior = toolCalls.get(i);
                if (name.equals(prior.get("name")) && "start".equals(prior.get("status"))) {
                    prior.put("status", status);
                    putTiming(prior, elapsedMs, beganAtMs, waitedMs);
                    String spill = takeSpill(name);
                    if (spill != null) prior.put("spill", spill);
                    // 咨询卡片挂那一问一答（与手写侧同一个实现与同一把长度尺）：刷新后仍点得开
                    if ("consultAgent".equals(name)) {
                        Map<String, Object> subChat = com.wenqu.ai.agent.chat.TurnRecorder
                                .consultCardSubChat(consultLedger, args);
                        if (subChat != null) prior.put("subChat", subChat);
                    }
                    // run_code 终态：停面板 ticker 并把扇出清单与这次运行的结构化结果挂上卡片
                    if (runCode) prior.putAll(tools.finishRunCodeCard(hooks));
                    return;
                }
            }
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("name", name);
            // 展示名与悬停说明随后端下发（与手写侧 TurnRecorder#recordToolStatus 同源）：
            // 落库的这份是刷新后重建工具卡的唯一依据，写裸名等于把中文名全押在前端那份手抄映射上
            displayNames.putDisplayFields(entry, name);
            entry.put("status", status);
            // 入参随卡片落库：前端刷新后的工具卡、咨询卡「被咨询那一位的名字」、
            // 以及暗截断对账里的「工具入参字符数」都读这一列（缺它等于三处一起退化）
            if (args != null && !args.isBlank()) entry.put("args", args);
            // 耗时与开始时刻同样落库：此前只有 SSE 那一帧带 elapsedMs，刷新后每张卡都没有数，
            // 「执行了 N 个操作」后面跟着的是各张卡之和（全为 0）——当场看着有、回头读成 0ms。
            putTiming(entry, elapsedMs, beganAtMs > 0L ? beganAtMs : System.currentTimeMillis(), waitedMs);
            // 时间线：先把这把工具之前的正文成段，再插工具段（下标=它在 toolCalls 里的位置），
            // 刷新后前端据此把工具卡还原到正文之间的原位，而不是一律沉底。
            flushTimelineText(answerText.length());
            timeline.add(Map.of("kind", "tool", "i", toolCalls.size()));
            toolCalls.add(entry);
            // 脚本开始跑：开这块实时面板（作用域是一次运行，两段脚本各自复位）
            if (runCode) tools.beginRunCode(hooks);
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

        String thinkingJson() {
            return thinking.length() == 0 ? null : com.alibaba.fastjson2.JSON.toJSONString(thinking.toString());
        }

        String toolCallsJson() {
            return toolCalls.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(toolCalls);
        }

        java.util.List<java.util.Map<String, Object>> toolCallList() {
            return toolCalls;
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
            String body = answerText.toString();
            snap.put("content", body);
            snap.put("processText", related.process());
            if (thinking.length() > 0) snap.put("thinking", thinking.toString());
            snap.put("toolCalls", new java.util.ArrayList<>(toolCalls));
            java.util.List<Map<String, Object>> tl = new java.util.ArrayList<>(timeline);
            if (body.length() > timelineAnchor) {
                tl.add(Map.of("kind", "text", "from", timelineAnchor, "to", body.length()));
            }
            snap.put("timeline", tl);
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
        public boolean deepThink() {
            return false;
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
     */
    private long drain(AgentSession session, long cursor, long emitFrom, RunChannel channel, Recording recording) {
        for (SessionEvent event : session.log().readAfter(cursor, 200)) {
            cursor = Math.max(cursor, event.seq());
            translate(event, event.seq() > emitFrom ? channel : SILENT_CHANNEL, recording);
        }
        return cursor;
    }

    /**
     * 框架 Session Log 的事件词汇 → 问渠前端事件名。
     * <p>刻意不发：{@code run/*}、{@code step/*}、{@code state/checkpoint}、{@code model/dispatch} 这些
     * 没有前端对应物的帧——前端对认不出的事件名会打 warn（{@code api.js:158}），不如不发。
     */
    private void translate(SessionEvent event, RunChannel channel, Recording recording) {
        Map<String, Object> data = event.data() == null ? Map.of() : event.data();
        switch (event.type()) {
            case "model/chunk" -> {
                if (!(data.get("chunk") instanceof Map<?, ?> chunk)) return;
                if (!(chunk.get("content") instanceof List<?> blocks)) return;
                for (Object block : blocks) {
                    if (!(block instanceof Map<?, ?> b)) continue;
                    if ("thinking".equals(b.get("type"))) {
                        String thinkingDelta = str(b.get("thinking"));
                        recording.thinking(thinkingDelta);
                        emit(channel, RunEventType.THINKING, thinkingDelta);
                    } else if ("text".equals(b.get("type"))) {
                        // 正文开始 = 这一段思考收场：补一条 thinking_done，前端据此停转圈并折叠就地那段
                        closeThinkingFrame(channel, recording);
                        String clean = recording.related.feed(str(b.get("text")));
                        if (!clean.isEmpty()) {
                            recording.answer(clean);
                            emit(channel, RunEventType.TOKEN, clean);
                        }
                        // 过程独白（<process> 标签内）按增量实时下发 PROCESS：正文走 TOKEN，
                        // 过程另走一条通道，前端 onProcess 累积成「执行过程」灰字块。收尾帧在 finish() 里补齐。
                        String proc = recording.related.process();
                        if (proc != null && proc.length() > recording.lastProcessLen) {
                            emit(channel, RunEventType.PROCESS,
                                    proc.substring(recording.lastProcessLen));
                            recording.lastProcessLen = proc.length();
                        }
                    }
                }
            }
            case "tool/requested" -> {
                // 调工具也是「这一段想完了」的信号：不在这儿收场，思考段会一直挂着转圈直到下一段正文出现，
                // 而时间线里这段思考也会压到工具卡后面（区间是按关闭时刻写的，顺序即到达顺序）
                closeThinkingFrame(channel, recording);
                // 载荷形状（实测）：{toolCallId, call:{type,id,name,input}} —— 名字在 call.name 里，
                // 不在顶层；按顶层取会拿到空串，工具卡就成了没有名字的空白行
                recording.lastToolName = toolNameOf(data.get("call"));
                Object requestedInput = toolInputOf(data.get("call"));
                // 必须序列化成 JSON：咨询卡片按入参里的 agentId 取那一份子对话，
                // 用 Map.toString() 就得到 "{agentId=xxx}" 这种 Java 形状，解析必然失败（实测卡片没了）
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
                recording.toolCall(recording.lastToolName, "start", recording.lastToolInput, 0L, beganAt, 0L);
                // 进行中必须是 "start"（不是 "running"）：前端 onToolStatus 只认 status==='start' 才建/合并
                // 实时工具卡。发 "running" 会让前端把这张当终态卡 push 出去，随后的 done 又找不到可合并的
                // start 卡再 push 一张——一次调用渲染成两张，其中停在 "running" 的那张显示为失败(❌)。
                emit(channel, RunEventType.TOOL_STATUS,
                        toolCard(recording.lastToolName, "start", 0L, beganAt, 0L, recording.lastToolInput, null));
            }
            case "tool/result" -> {
                String name = toolNameOf(data.get("message"));
                if (name.isBlank()) name = recording.lastToolName;
                // 入参与耗时都按这一次调用取回：读共用那份就是「甲张卡的入参是乙次调用的参数」
                String callKey = Recording.toolCallKey(toolCallIdOf(data), name);
                String args = recording.toolInputByKey.remove(callKey);
                if (args == null) args = recording.lastToolInput;   // 配不上对应的 requested（如从游标中途接流）才沿用
                // 失败判定：优先读 ToolResultBlock 结构化的 state 字段（JSON 值 "error"），
                // 不再依赖 Map.toString() 拼出的 "STATE=ERROR" 字符串形状（序列化一变就漏判）。
                boolean failed = toolResultFailed(data);
                // 耗时＝墙钟窗口扣掉这段等待：提问卡挂三分钟不是「这个工具跑三分钟」，是「用户读了三分钟」。
                // 扣掉的那段另发一份 waitedMs：整段跨度减它才剩机器时间，只在单卡上扣会留在区间中间出不去。
                long beganAt = recording.takeToolStart(callKey);
                // 扣账与记账分开：这张卡自己的耗时要把盖住它的等待减干净（同一步里别家挂卡也一样被闸门盖住），
                // 而对外那份 waitedMs 只在挂卡那一张上出现——跨度按各张之和减，记两张就扣两遍。
                long covered = recording.takeToolWait(callKey);
                long charged = recording.takeToolCharge(callKey);
                long elapsed = beganAt <= 0L ? 0L
                        : Math.max(0L, System.currentTimeMillis() - beganAt - covered);
                recording.toolCall(name, failed ? "error" : "done", args, elapsed, beganAt, charged);
                emit(channel, RunEventType.TOOL_STATUS, toolCard(name, failed ? "error" : "done",
                        elapsed, beganAt, charged, args, toolResultText(data)));
            }
            case "tool/decision" -> {
                // 权限判定由框架说出（实测字段 decision=allowed|denied）：拒绝必须让用户看见，
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
                    emit(channel, RunEventType.PLAN_DELTA, str(plan));
                }
            }
            case "turn/interrupted" -> channel.emit(RunEventType.WARN, "本轮已被中断");
            default -> {
            }
        }
    }

    /**
     * 取工具名。两条事件把名字放在不同层级（实测）：{@code tool/requested} 在 {@code call.name}，
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
    private void announcePending(AgentSession session, ChatCommand cmd, RunChannel channel, Set<String> announced,
                                 boolean planAuto, String turnId, Recording recording) {
        Map<String, SessionEvent> pending = session.pending();
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
            // 两种待办按 kind 分流（实测：工具挂起=external_execution，权限确认=confirmation）。
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
                planCard.put("timeoutMs", configService.getInt("chat.planTimeoutMs", 600000));
                // 按意图自动开启的那一轮：卡片要标「自动开启」（与手写侧同一个字段名），
                // 否则用户会以为是自己的开关点错了
                if (planAuto) planCard.put("auto", true);
                if (recording != null) {
                    recording.planAuto = planAuto;
                    Map<String, Object> version = new java.util.LinkedHashMap<>();
                    version.put("plan", planText);
                    recording.planVersions.add(version);
                }
                channel.emit(RunEventType.PLAN_APPROVAL, com.alibaba.fastjson2.JSON.toJSONString(planCard));
                // 落同一张 c_ai_tool_approval（tool_name=planApproval）：不落的话刷新后这张卡取不回来
                sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), "planApproval",
                        com.alibaba.fastjson2.JSON.toJSONString(Map.of("plan", planText)));
                continue;
            }
            if (!"confirmation".equals(kind)) {
                Object call = firstNonNull(data.get("call"), data);
                String askPayload = askCard(entry.getKey(), toolInputOf(call), tool);
                channel.emit(RunEventType.ASK_USER, askPayload);
                // 载荷与手写侧 askArgsJson 同形（都按 {"questions":[…]} 解析），恢复端点可直接复用
                sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), "askUser", askPayload);
                continue;
            }
            // 审批卡：展示名与工具卡同源（注册中心 → 框架工具表），不再把裸工具名当 label 下发。
            // 「重新生成后会看到同一张卡」靠的就是这份一致——前端那句「请求执行工具「X」」读的是 label
            Map<String, Object> approvalCard = new java.util.LinkedHashMap<>();
            approvalCard.put("approvalId", entry.getKey());
            approvalCard.put("tool", tool);
            approvalCard.put("args", args);
            approvalCard.put("timeoutMs", configService.getInt("chat.approvalTimeoutMs", 120000));
            displayNames.putDisplayFields(approvalCard, tool);
            // 工具表没收过这个名字（MCP 动态工具常见）：补一句说明，让用户至少知道这张卡在问什么，
            // 而不是光秃秃一个英文名——但绝不拿工具名冒充展示名（前端会把它当「后端认真取过」）
            if (!approvalCard.containsKey("hint")) {
                approvalCard.put("hint", "执行前请确认这一步");
            }
            channel.emit(RunEventType.APPROVAL_REQUIRED,
                    com.alibaba.fastjson2.JSON.toJSONString(approvalCard));
            sessions.recordPending(cmd, sessionKey, turnId, entry.getKey(), tool, args);
        }
    }

    /**
     * 计划批准卡的正文：<b>必须是一段 Markdown 文本</b>，不能是工具入参对象。
     * <p>真机踩过：早前直接 {@code planCard.put("plan", toolInputOf(call))}，而框架 {@code plan_exit}
     * 的入参只有一个可选 {@code summary}（{@code PlanExitTool} 的 inputSchema 就这么声明的），
     * 整份计划正文在更早的 {@code plan_write(content=…)} 里。塞对象过去，前端 {@code renderMd()} 拿到
     * 非字符串就渲染成 {@code [object Object]}——用户批准的是一份看不见的计划。
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
    private io.agentscope.core.model.GenerateOptions generateOptions(ChatCommand cmd, String ref) {
        io.agentscope.core.model.GenerateOptions.Builder b =
                io.agentscope.core.model.GenerateOptions.builder();
        String thinkLevel = cmd.getReasoningLevel() != null ? cmd.getReasoningLevel()
                : modelRegistry.defaultReasoningLevelOf(ref);
        boolean thinkingOn = cmd.isDeepThink()
                || ModelRegistryService.THINK_ALWAYS.equals(modelRegistry.referenceThinking(ref));
        if (!thinkingOn) {
            return b.temperature(configService.getDouble("chat.temperature")).build();
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
     * 压缩配置。框架默认（不配＝动态 token 阈值或 50 条消息触发、逐字留最近 20 条、prune 开）
     * 本身就是一条会跑的滚动压缩，问渠不再另算一份摘要——两边各算就是两份「已压缩上下文」互相冒充。
     * <p>手动 {@code /compact} 在这一路会话上留过请求时，本次建实例强制「有一条就先压」，
     * 让那次点击真的在下一次提问前把旧对话折进去（框架没有「就地压一次」的入口，压缩长在推理循环前）。
     * <p>此前框架默认阈值另有可配项（chat.agentscopeCompactTriggerMessages，0＝框架默认），默认态
     * 与框架默认逐字相同，属「框架已有限制、参数只留个默认就用不上的改口」，已取消。
     */
    private io.agentscope.harness.agent.memory.compaction.CompactionConfig compactionConfig(ChatCommand cmd) {
        String key = sessions.key(cmd);
        if (!sessions.takeCompactRequest(key)) {
            return io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder().build();
        }
        // 强制压缩只应作用「下一次提问」那一个实例：实例按会话常驻，triggerMessages(1) 若不复位会被
        // 这一条会话的后续每一轮继承（每轮都压）。这里标「下次开轮重建」，让下一轮回到默认压缩配置。
        sessions.markRebuildAfterCompact(key);
        return io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder()
                .triggerMessages(1)
                .build();
    }

    private HarnessAgent buildAgent(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                                    ExecutionPolicy policy, ModelRegistryService.ModelRoute route,
                                    WenquAgentxTools.TurnHooks hooks) {
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
        // 框架把工具派到自己的弹性线程：身份与本轮检索参数覆盖必须在发起线程取快照、在工具内重放，
        // 不重放就是检索恒空且不报错（Phase 0 与本刀都实测过这个形状）
        for (Object holder : tools.frameworkTools(cmd, channel, pool, policy, (code, message) -> {
            log.info("[agentx] 降级 {}: {}", code, message);
            // 未接入的能力必须让用户看见：静默少挂一个族会被读成「配了没生效」
            channel.emit(RunEventType.WARN, message);
        }, true, hooks)) {
            toolkit.registerTool(holder);
        }
        AgentxStepBudget stepBudget = new AgentxStepBudget();
        // 实例常驻、闸随实例走，登记到会话表好让引擎在开轮时归零它
        sessions.noteStepBudget(sessions.key(cmd), stepBudget);
        return HarnessAgent.builder()
                .agentId("wenqu")
                .name("wenqu")
                .description("问渠·AgentScope 运行时")
                // 角色提示词归智能体管（chat.systemPrompt 已退役）：本轮无智能体绑定时用内置默认。
                // 「可咨询助手」一节必须跟着挂：工具挂上了却不给清单，模型只能猜 id（判据与挂载同源）
                // 用户口径那条（正文/计划/清单不写沙盒路径与工具名）也要跟着挂：它原先长在已退役的手写
                // 计划阶段里，框架这一路没人再说第二遍，而框架的 PLAN banner 与沙盒工具说明反而主动把
                // 路径喂给模型——不挂回去，计划卡就变成「它打算在沙盒里怎么写文件」的说明书
                .sysPrompt(properties.getSystemPrompt() + tools.consultGuide(cmd) + PlanCopy.guidance()
                        + skillPrompt(cmd)
                        + com.wenqu.ai.agent.chat.RelatedPrompt.line(
                                configService.getInt("retrieval.relatedCount", 3)))
                // 问渠所有供应商都是 OpenAI 兼容网关（c_ai_provider.base_url 实测全是 compatible-mode）
                .model(OpenAIChatModel.builder()
                        .baseUrl(route.baseUrl())
                        .apiKey(route.apiKey())
                        .modelName(route.modelId())
                        .stream(true)
                        .generateOptions(generateOptions(cmd, hooks.modelRef()))
                        .endpointPath(route.completionsPath() == null || route.completionsPath().isBlank()
                                ? "/v1/chat/completions" : route.completionsPath())
                        .build())
                .toolkit(toolkit)
                // 框架对「一把工具」有自带的 5 分钟硬超时（TOOL_DEFAULTS）。咨询子轮、沙盒长命令都会撞上去，
                // 而手写运行时没有这一层——不显式设，就等于框架比问答侧多一道没人知道的天花板（实测踩过：
                // 咨询跑满 5 分钟被判工具失败，父轮只收到一句 Tool execution timeout）
                .toolExecutionConfig(io.agentscope.core.model.ExecutionConfig.builder()
                        .timeout(java.time.Duration.ofSeconds(turnSeconds()))
                        .build())
                .maxIters(configService.getInt("chat.agentscopeMaxIters", 12))
                // 计划模式工具面常驻开启，具体某一轮进不进由 enterPlanMode 决定（实例按会话复用）。
                //
                // 这里与 PlanModeService **不是双跑**：那套在框架路径下只被用来做「意图判断」
                // （startIntentCheck/awaitIntent，决定这一轮要不要先出计划卡），而
                // approvalGate/refsDigest 那些计划版本链、澄清提问轮、计划快照落库的逻辑框架路径下
                // 并不调用。分工是「问渠判要不要出计划 → 框架管计划怎么执行」：
                //   问渠 PlanModeService.startIntentCheck/awaitIntent —— 意图闸（用户开关之外还有按消息意图自动开）
                //   框架 enablePlanMode + PlanModeMiddleware        —— plan_enter/write/exit 三把工具 + 计划位拦截写操作
                // 别把它当成「两套计划机制同时跑」去"修"——那是分工，真合并要先决定计划版本链怎么在框架的
                // plan 文件上表达（框架只有单份 plan 文件，问渠那套支持多版本取代），不是删代码能解决的。
                .enablePlanMode()
                // 任务清单：**不开**框架的 enableTaskList，只借它的 state 做存储。
                // 原因：开它会同时注册 todo_write 工具并由 TaskReminderMiddleware 每轮教模型用
                // 「in_progress/completed」这套词，而问渠挂的是 writeTodo（教的是 doing/done，
                // 且带护栏与中文展示名）。两套工具 + 两套状态词同时进模型上下文，它会挑错工具、
                // 或者把 doing 写进 todo_write 被框架判非法。storage 用框架的、tool 面用问渠的，
                // 是这里刻意的分工——框架 AgentState 才是清单的权威真源（见 TodoBridge）。
                // 真要换成框架工具面，得连护栏、中文名、前端契约一起换，不能只开这个开关。
                .maxContextTokens(contextTokens(cmd))
                // 上下文压缩：框架默认就开着（50 条消息或动态 token 阈值触发、逐字保留最近 20 条）。
                // 这里只留两个改口的地方——常规阈值按配置，手动 /compact 在框架引擎下落到「下一次建实例先压」
                .compaction(compactionConfig(cmd))
                .workspace(stateDir)
                // 框架默认把状态写到 ~/.agentscope/state/**（在问渠数据目录之外，容器里等于丢）：
                // 换成受管数据目录的文件实现；多副本（chat.agentscopeRedisEnabled=true）时自动切 Redis
                .stateStore(stateStore(stateDir))
                // 权限由框架持有：档位→模式、按名黑名单→deny 规则；工具级不可绕过的判定落在
                // SpringAiToolAdapter#checkPermissions
                .permissionContext(AgentxPermissions.contextOf(policy))
                // 待办持久化 + 答复后自动接续原任务，是这套执行形态的立身之处
                .enablePendingToolRecovery(true)
                // 步数预算闸（对标 StepBudgetGuard）：maxIters 限的是推理轮数而不是工具调用次数，
                // 一轮里模型可以一次要好几把工具，账单闸只能在 onActing 里数
                .middleware(stepBudget)
                // Phase 0 实测：框架默认装备子智能体/技能/工作区上下文/tools.json 等旁路能力，
                // 不关净时模型会自主派子智能体去翻工作区，与问渠自己的体系打架
                .disableSubagents()
                .disableDynamicSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableWorkspaceContext()
                // 框架自带的 memory_search / memory_get / memory_save / session_search 一律关掉：
                // 它们读的是框架工作区里那份 MEMORY.md，而问渠的长期记忆与历史有自己的表、自己的面板。
                // 留着就是第二套记忆体系——模型会去查一份用户在界面上既看不见也管不着的记忆
                //（实测一轮里跑了 3 次 session_search/memory_search，白占三步预算）
                .disableMemoryTools()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .disableFilesystemTools()
                .disableShellTool()
                .disableWebTools()
                .build();
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
    private static String interruptMarker(boolean stopped, boolean timedOut) {
        if (stopped) return com.wenqu.ai.agent.chat.TurnRecorder.STOP_SUFFIX.trim();
        if (timedOut) return "\n\n> ⏹ 等待确认超时，这一轮没有继续执行";
        return com.wenqu.ai.agent.chat.TurnRecorder.TRUNCATION_SUFFIX;
    }

    /**
     * 接流快照：本轮到此刻已经产出的东西，一次交全给总线（见 {@code ChatRunBus#attach}）。
     * <p>字段名与前端 {@code applyTurnSnapshot} 一一对齐，且与「刷新后读历史」那份同源同形：
     * 正文/思考/工具卡/时间线出自 {@link Recording#snapshotFields()}，引用出自本轮引用池，产物走
     * {@code takeArtifacts} 那份不消费的副本。缺哪一项，前端只是那一段还没长出来，不会报错。
     * <p>不含待裁决的卡片（提问/计划/审批）：它们的时限与「唤醒句柄还在不在」以库为准，前端走既有的
     * pending 端点恢复；也不含逐字增量，正文取缓冲本身比回放上千条 token 帧快。
     */
    private Map<String, Object> turnSnapshot(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                                             ModelRegistryService.ModelRoute route, Recording recording,
                                             String turnId) {
        Map<String, Object> snap = recording.snapshotFields();
        snap.put("running", true);
        snap.put("phase", "generating");
        snap.put("sources", new java.util.ArrayList<>(pool.sources()));
        snap.put("artifacts", artifactService.takeArtifacts(cmd.getSessionId()));
        snap.put("model", route.modelId());
        snap.put("turnId", turnId);
        if (cmd.getAgentId() != null && !cmd.getAgentId().isBlank()) {
            snap.put("agentId", cmd.getAgentId());
            String name = agentNameOf(cmd);
            if (name != null) snap.put("agentName", name);
        }
        return snap;
    }

    /**
     * 收尾：落库、下发 done 帧、进问答台账。
     *
     * @param elapsedMs   机器耗时（墙钟扣掉人在回路的等待）——界面、看板、台账都说这个数
     * @param humanWaitMs 这一轮里用户读卡/答题累计的等待，随 done 下发供界面上单独说明
     * @param wallMs      整轮墙钟（开轮那一刻到收尾），只用来反推本轮真实开始时刻与写日志
     */
    private void finish(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                        ModelRegistryService.ModelRoute route, String modelRef, String answer, Recording recording,
                        String userMessageId, boolean stopped, boolean timedOut, boolean awaiting,
                        long elapsedMs, long humanWaitMs, long wallMs,
                        AgentSession session, int historyBefore, String turnId,
                        Map<String, Object> planRecord) {
        // 落库这一轮：框架引擎此前只发帧不落库，界面上点进去是空会话（真机被这个抓到）。
        // 走 SessionService 同一入口，不另写一套消息表——会话列表、历史、引用面板都吃这份数据
        String body = answer == null ? "" : answer;
        // 推荐块里的长条目按正文并回（与手写侧同一道护栏）：实测过模型把整段正文写进
        // {@code <related>} 里，此时「剥掉推荐块」等于把回答吃掉——长的并进正文，短的才当芯片。
        List<String> relatedProse = recording.related.prose(RELATED_PROSE_MIN_CHARS);
        if (!relatedProse.isEmpty()) {
            String merged = String.join("\n\n", relatedProse);
            body = body.isBlank() ? merged : body.stripTrailing() + "\n\n" + merged;
            pool.registrar().degrade("relatedHoldsAnswer",
                    "模型把正文写进了推荐块（" + relatedProse.size() + " 段），已并回回答");
            log.warn("[FAIL-LOUD] related 内含正文 {} 段（{} 字），已并回回答: session={}",
                    relatedProse.size(), merged.length(), cmd.getSessionId());
        }
        List<String> relatedChips = recording.related.chips(RELATED_PROSE_MIN_CHARS);
        boolean realAnswer = !body.isBlank();
        // 半程轮有过程就落库、连过程都没有就不落（与手写侧 persistPartialAnswer 同一道守卫）：
        // 「正文非空」单独判会让只跑了工具的轮整条蒸发，而真空轮落一条空消息只是污染历史——
        // 真机实测到的正是后者：确认卡没人答、撞到墙钟上限收尾，历史里多出一条空白助手消息
        boolean hasProcess = !recording.toolCalls.isEmpty() || recording.thinking.length() > 0;
        boolean supersededRound = planRecord != null && "superseded".equals(planRecord.get("status"));
        if (!realAnswer && hasProcess) {
            // 被「继续对话」取代的那一轮正文留空：计划卡角标已经写着「已被取代」，再补一句中断
            // 标注就是把同一件事说两遍（与手写引擎同一口径）
            body = supersededRound ? "" : interruptMarker(stopped, timedOut);
        } else if (realAnswer && (stopped || timedOut)) {
            // 半程正文：标注追加在尾部（与手写侧同一口径——用户要看得见「这句只有半截」）
            body = body + interruptMarker(stopped, timedOut);
        }
        boolean persist = realAnswer || (hasProcess && !awaiting);
        // 引用完整性防线：悬空角标剔除、「有来源却没标 [N]」的 fail-loud、「编号对上了但语义不支撑」的
        // LLM 自检——与手写侧同一个实现。换了运行时不该把这道答案质量防线摘掉。
        // 停止轮与「只有过程没有正文」的半程轮都不跑：那两句标注不是答案，跑了对它们就是白报一条
        // 「未标注引用」（半程轮往往还带着工具召回的来源，正好满足那条 fail-loud 的触发条件）。
        if (!stopped && !timedOut && realAnswer) {
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
            // 自检会裁掉不支撑的来源并重编编号：池不回写，正文角标与引用面板就会错位
            pool.applyCitationOutcome(outcome);
        }
        com.wenqu.ai.agent.chat.TurnHandle handle =
                com.wenqu.ai.agent.chat.TurnRegistry.handle(cmd.getSessionId());
        String framedTurnId = handle == null ? turnId : handle.turnId();
        Map<String, Object> tokens = usageOf(session, historyBefore, pool, cmd);
        // 收尾这一发是终值（与 done 里的 tokens 同一份快照）；流式期间那几发见 emitLiveUsage，
        // 两拨都出自 usageOf，不做第二套估算
        // 整轮结束而思考段还开着（只思考没出正文、或被叫停）：照样补一条收尾帧，别让转圈永远挂着
        closeThinkingFrame(channel, recording);
        // 思考字数进台账：这一族关着思考也照样回 reasoning，单列出来放量时才看得出
        // 「框架引擎多烧的那一截」是思考而不是正文（§29 成本账那条）
        tokens.put("thinkingChars", recording.thinking.length());
        // 暗截断对账：判据在 TurnRecorder 那一处，两条运行时共用同一把尺，不复制第二份。
        // 「已交代」必须算上思考字符与工具入参——前者漏了就是轮轮误报（deepseek 系恒思考），
        // 后者漏了会把「写文件动辄几百 token」的正常轮判成内容丢失。
        long finalOutput = tokens.get("finalOutput") instanceof Number n ? n.longValue() : 0;
        int toolArgChars = 0;
        for (java.util.Map<String, Object> rec : recording.toolCallList()) {
            Object args = rec.get("args");
            if (args != null) toolArgChars += String.valueOf(args).length();
        }
        int accounted = body.length() + recording.thinking.length() + toolArgChars;
        if (!stopped && !timedOut && !tokens.isEmpty()
                && com.wenqu.ai.agent.chat.TurnRecorder.looksLikeOutputLost(finalOutput, accounted, true)) {
            pool.registrar().degrade("outputLost", "模型本轮生成 " + finalOutput
                    + " tokens，仅 " + accounted + " 字进入回答，内容疑似丢失");
            body = body + com.wenqu.ai.agent.chat.TurnRecorder.OUTPUT_LOST_SUFFIX;
            log.warn("[FAIL-LOUD] 生成量与正文字数不符（框架引擎）: session={}, 最终轮输出={} tokens, 已交代={} 字"
                            + "（思考 {} 字/工具入参 {} 字）", cmd.getSessionId(), finalOutput, accounted,
                    recording.thinking.length(), toolArgChars);
        }
        if (!tokens.isEmpty()) channel.emit(RunEventType.USAGE, com.alibaba.fastjson2.JSON.toJSONString(tokens));
        // 状态行既下发也落库：只随 done 发的话，刷新后「搜索 N 个关键词，参考 M 段资料」整块消失
        String lastRetrieved = retrievedJson(cmd, pool, recording);
        channel.emit(RunEventType.RETRIEVED, lastRetrieved);
        String artifactList = com.alibaba.fastjson2.JSON.toJSONString(
                artifactService.takeArtifacts(cmd.getSessionId()));
        String artifactsJson = "[]".equals(artifactList) ? null : artifactList;
        String assistantMessageId = persist ? sessionService.appendMessage(cmd.getSessionId(), "assistant",
                body, null,
                com.alibaba.fastjson2.JSON.toJSONString(pool.sources()), recording.thinkingJson(),
                lastRetrieved, artifactsJson,
                recording.toolCallsJson(), null,
                tokens.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(tokens),
                recording.timelineJson(body, recording.answerText()),
                recording.related.process().isEmpty() ? null : recording.related.process(),
                cmd.getAgentId(), agentNameOf(cmd), route.modelId(),
                relatedChips.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(relatedChips),
                null, null,
                planRecord == null ? null : com.alibaba.fastjson2.JSON.toJSONString(planRecord),
                framedTurnId) : null;
        // 警示条也要活过刷新：它此前只随 warn 帧下发一次，用户当场看见「沙盒写入未执行」，
        // 刷新一次就等于没发生过——而这一轮确实被沙盒挡下过一次（与 degradations 那条同一个错法）
        sessionService.saveMessageWarn(assistantMessageId, channel.lastWarn());
        // 问答台账：与手写侧 doOnComplete 同一处时机（半程轮不落台账，那是中断轮的收尾语义）。
        // 不接的表现为看板与「未命中问题」列表只统计到 legacy 那部分流量——分派切了运行时，
        // 运营侧的数却悄悄少了一半，而且没有任何一处会报错。
        if (persist && realAnswer) {
            qaLog.logAsync(cmd.getSessionId(), cmd.getQuestion(), body,
                    pool.sources().stream().map(x -> String.valueOf(x.get("docId"))).toList(),
                    !pool.sources().isEmpty(), elapsedMs, cmd.getQuestion(), null,
                    cmd.isDeepThink(), assistantMessageId, cmd.getAgentId());
        }
        String payload = "{\"finalContent\":" + json(body)
                + ",\"messageId\":" + json(assistantMessageId)
                + ",\"userMessageId\":" + json(userMessageId)
                + ",\"turnId\":" + json(framedTurnId)
                + ",\"model\":" + json(route.modelId())
                + ",\"sources\":" + com.alibaba.fastjson2.JSON.toJSONString(pool.sources())
                + ",\"degradations\":" + com.alibaba.fastjson2.JSON.toJSONString(pool.degradations())
                + ",\"elapsedMs\":" + elapsedMs
                + ",\"humanWaitMs\":" + humanWaitMs
                + ",\"tokens\":" + com.alibaba.fastjson2.JSON.toJSONString(tokens)
                + ",\"toolCalls\":" + (recording.toolCallsJson() == null ? "[]" : recording.toolCallsJson())
                + ",\"artifacts\":" + (artifactsJson == null ? "[]" : artifactsJson)
                + ",\"related\":" + com.alibaba.fastjson2.JSON.toJSONString(relatedChips)
                + ",\"thinking\":" + json(recording.thinking.length() == 0 ? null : recording.thinking.toString())
                + (planRecord != null && "superseded".equals(planRecord.get("status"))
                        ? ",\"planSuperseded\":true" : "")
                + (planRecord != null && "rejected".equals(planRecord.get("status"))
                        ? ",\"planCancelled\":true" : "")
                + (planRecord == null ? "" : ",\"planRecord\":"
                        + com.alibaba.fastjson2.JSON.toJSONString(planRecord))
                + ",\"engine\":\"agentscope\"}";
        channel.emit(RunEventType.DONE, payload);
        // done 之后必须收通道：手写侧是 runChat 自己 completeEmitter，框架引擎原先漏了这一步，
        // 表现是帧都到了、连接却挂着直到看门狗 300s 超时截断（真机实测：done 在 01:20:32，
        // 通道 01:25:20 才被判超时），前端「这一轮结束」要等五分钟才落地
        channel.complete();
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
    private int emitRetrieved(ChatCommand cmd, WenquSourcePool pool, RunChannel channel, int announced, Recording recording) {
        int refs = pool.sources().size();
        if (refs == announced) return announced;
        channel.emit(RunEventType.RETRIEVED, retrievedJson(cmd, pool, recording));
        return refs;
    }

    /**
     * 状态行载荷（「搜索 N 个关键词，参考 M 段资料」）。
     * <p>抽成一处是为了<b>同一份也落库</b>：只随 done 下发的话，刷新后这一行整块消失——
     * 手写侧是把 {@code retrievedJson} 存进消息行的，两条运行时得给用户同一个观感。
     * <p>{@code terms} 优先取模型本轮实际发出的检索词（{@code search_knowledge} 的 {@code query} 入参，
     * 见 {@code Recording#searchQueries}）——它比从用户原问句抽的关键词更贴近「模型真搜了什么」；
     * 收集不到（没走检索工具，或工具名/入参形状变了）才退回问题关键词，保证状态行永不空。
     */
    private String retrievedJson(ChatCommand cmd, WenquSourcePool pool, Recording recording) {
        List<String> terms;
        Set<String> actual = recording == null ? null : recording.searchQueries;
        if (actual != null && !actual.isEmpty()) {
            terms = new java.util.ArrayList<>(actual);
        } else {
            terms = keywordExtractor.extractMain(cmd.getQuestion());
        }
        return com.alibaba.fastjson2.JSON.toJSONString(Map.of(
                "keywords", terms.size(), "refs", pool.sources().size(), "terms", terms));
    }

    /**
     * 流式期的用量帧：本轮每落地一条新消息就重发一份 {@code usage}，让输入框工具栏的容量圆环
     * 在第一次模型回包后就点亮，而不是等整轮收尾才出现（收尾那一发离结束只剩一帧，等于没有）。
     * <p>与 {@link #finish} 里的终值同源同算法（都走 {@link #usageOf}），前端只把它挂在
     * {@code tokensPreview} 上：落库、累计、输出侧仍只认 done 的 tokens，这里不另立口径。
     * <p>按 transcript 条数变化触发，不按循环节拍：分类拆分要对全量上下文做 BPE 计数，
     * 200ms 一跳空转会把跟随线程烧在数 token 上。发不出去只是容量圆环晚一帧，不该带走这一轮的答案，
     * 所以异常只告警不上抛。
     */
    private void emitLiveUsage(AgentSession session, ChatCommand cmd, WenquSourcePool pool,
                               RunChannel channel, int historyBefore, long[] stamp) {
        int size = session.transcript().messages().size();
        if (size == stamp[0]) return;
        stamp[0] = size;
        try {
            Map<String, Object> tokens = usageOf(session, historyBefore, pool, cmd);
            // 还没有任何一次模型回包带 usage：此刻报不出占用，等下一跳（空表发出去只会让前端清空预览）
            if (tokens.isEmpty()) return;
            channel.emit(RunEventType.USAGE, com.alibaba.fastjson2.JSON.toJSONString(tokens));
        } catch (Exception e) {
            log.warn("[USAGE] 流式用量帧发不出来，容量圆环要等本轮收尾: session={}, {}",
                    cmd.getSessionId(), e.toString());
        }
    }

    /**
     * 本轮实测用量：只累计这一轮新增消息的 {@link io.agentscope.core.model.ChatUsage}。
     * <p>字段名与主链路 {@code TurnRecorder.buildUsageSnapshot} 对齐（前端容量面板只认这套键）；
     * 分类拆分（{@code parts}）按<b>框架自己的消息结构</b>产出，见 {@link FrameworkCtxParts}——
     * 手写侧那十类靠拼装 system 时的分段位置切，框架侧没有那四个标记，不套用。
     * <p>上下文预算（{@code budget}）框架不给：{@code maxContextTokens} 是给框架做压缩用的阈值，
     * 不是问渠「给这一轮留了多少」的预算，报上去会让右栏占用条按一个没人承诺过的数算百分比。
     * 前端对缺字段是「不显示该行」，不填比填个假预算诚实。
     */
    private Map<String, Object> usageOf(AgentSession session, int historyBefore,
                                        WenquSourcePool pool, ChatCommand cmd) {
        List<Msg> messages = session.transcript().messages();
        int from = Math.min(Math.max(historyBefore, 0), messages.size());
        long prompt = 0;
        long output = 0;
        long cached = 0;
        int lastInput = 0;
        long lastOutput = 0;
        int lastCached = 0;
        boolean any = false;
        for (int i = from; i < messages.size(); i++) {
            io.agentscope.core.model.ChatUsage usage = messages.get(i).getUsage();
            if (usage == null) continue;
            any = true;
            prompt += usage.getInputTokens();
            output += usage.getOutputTokens();
            cached += usage.getCachedTokens();
            // 最后一轮的 prompt≈本轮结束时的上下文占用（工具结果逐轮进上下文），与主链路口径同源
            lastInput = usage.getInputTokens();
            lastOutput = usage.getOutputTokens();
            // 末轮命中量：容量面板的「缓存命中率」分母是「此刻上下文」（lastInput），
            // 分子必须是同一时刻的命中量——用各轮命中之和（cached）配它会算出 >100% 的命中率
            lastCached = usage.getCachedTokens();
        }
        if (!any) return new java.util.LinkedHashMap<>();
        Map<String, Object> tokens = new java.util.LinkedHashMap<>();
        tokens.put("prompt", prompt);
        tokens.put("output", output);
        // 最终那一轮的输出：暗截断对账只看它（各轮工具调用的输出不参与触顶与丢失判定，与主链路同口径）
        tokens.put("finalOutput", lastOutput);
        tokens.put("total", prompt + output);
        tokens.put("cached", cached);
        tokens.put("cachedLast", lastCached);
        tokens.put("context", lastInput);
        tokens.put("outputIsReal", true);
        tokens.put("hits", pool.sources().size());
        int window = contextTokens(cmd);
        if (window > 0) {
            tokens.put("window", window);
            tokens.put("windowSource", "framework");
        }
        // 分类用量：按框架消息结构切分，并按本轮真实 prompt（最终轮输入）校准。
        // 分类拆的是「本轮结束时的上下文」，所以校准基准用 lastInput 而不是各轮 prompt 之和——
        // 工具调用会让 prompt 逐轮递增，sum 是账单口径，拿它当基准会把容量条撑到虚高。
        // 传 historyBefore 而非上面夹取过的 from：分类器要靠「index < 该值」认出哪些是历史消息，
        // 传 from 会被 clamp 截断（historyBefore 超出 transcript 长度时）而误把整段当本轮新增。
        Map<String, Integer> parts = FrameworkCtxParts.of(messages, historyBefore, toolSchemasOf(cmd), lastInput);
        if (!parts.isEmpty()) tokens.put("parts", parts);
        return tokens;
    }

    /**
     * 本轮请求下发的工具声明（{@code tools} 字段）：容量面板的「工具声明」一段按它估算。
     * <p>取不到就返回 null（分类器按「无工具」处理，该段不出现）——工具声明是这轮上下文的固定开销，
     * 拿不到时宁可少画一段，也不能拿上一轮的工具集冒充本轮。
     */
    private List<io.agentscope.core.model.ToolSchema> toolSchemasOf(ChatCommand cmd) {
        try {
            return sessions.toolSchemas(cmd);
        } catch (Exception e) {
            log.warn("[CTX-PARTS] 工具声明读不出来，本轮容量面板少「工具声明」一段: session={}, {}",
                    cmd.getSessionId(), e.toString());
            return null;
        }
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

    /** 最终正文取自会话记录（框架已把它落成一条 assistant 消息），不靠适配器攒字符串。 */
    private static String lastAssistantText(AgentSession session) {
        List<Msg> messages = session.transcript().messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if ("ASSISTANT".equals(String.valueOf(m.getRole()))) {
                String text = m.getTextContent();
                if (text != null && !text.isBlank()) return text;
            }
        }
        return "";
    }

    /**
     * 提问卡载荷：前端认 {@code {askId, questions, timeoutMs}}。挂起原因里带的是
     * {@code AskQuestion} 列表的 JSON，原样转过去；解不开就退成一题纯文本，
     * 让问题至少能到用户眼前（不能因为格式就吞掉）。
     * <p>{@code timeoutMs} 必须与 {@link #pendingBudgetMs} 用的那份配置同源：前端倒计时读它，
     * 而真正决定这张卡什么时候作废的是后端那个循环。两处不同源的表现是「界面还在数 8 分钟、
     * 后端两分钟就把这一轮收了」——用户对着一个已经作废的输入框作答，提交后只得到一句失败提示。
     */
    private String askCard(String askId, Object input, String toolName) {
        Object questions;
        if (input instanceof Map<?, ?> in) {
            Object multi = in.get("questions");
            if (multi instanceof List<?> list && !list.isEmpty()) {
                questions = list;
            } else {
                Map<String, Object> one = new java.util.LinkedHashMap<>();
                String inTopic = str(in.get("topic"));
                one.put("topic", !inTopic.isBlank() ? inTopic
                        : (toolName == null || toolName.isBlank() ? "确认" : toolName));
                one.put("question", str(in.get("question")));
                Object options = in.get("options");
                one.put("options", options instanceof List<?> l ? l : List.of());
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

    /**
     * 工具卡载荷（{@code tool_status} 实时帧与落库那份同源，字段形状一致）。
     *
     * <p><b>展示名从 {@link ToolDisplayNames} 取，不写裸名</b>：原先这里 {@code esc(name)} 直接把
     * 工具名塞进 {@code label}，前端 {@code toolLabel} 认得出「label 就是名字」而回落它那份手抄映射，
     * 表里没有的 {@code consultAgent}、{@code plan_*} 就现出英文裸名。取不到展示名时<b>不写该字段</b>
     * （前端映射与「后端确实没给名字」两种情况在界面上同形，但载荷里能分辨）。
     *
     * <p>{@code startedAtMs} 用<b>服务端</b>墙上时刻：并发派发时各张卡的起止只有落在同一个钟上才减得出
     * 「从第一个操作到最后一个操作」的真实跨度；拿前端收到帧的本地时刻去减，接流/后台节流就会把跨度算歪。
     */
    private String toolCard(String name, String status, long elapsedMs, long startedAtMs, long waitedMs,
                            String args, String result) {
        StringBuilder sb = new StringBuilder("{\"name\":\"").append(esc(name)).append("\"");
        String label = displayNames.label(name);
        if (label != null) sb.append(",\"label\":\"").append(esc(label)).append("\"");
        String hint = displayNames.hint(name);
        if (hint != null) sb.append(",\"hint\":\"").append(esc(hint)).append("\"");
        sb.append(",\"status\":\"").append(status)
                .append("\",\"elapsedMs\":").append(elapsedMs);
        // 只在真有值时写：缺它 = 这一把没计时（前端不显示），写成 0 = 谎称这一把从 1970 年跑起 / 跑了 0 毫秒
        if (startedAtMs > 0L) sb.append(",\"startedAtMs\":").append(startedAtMs);
        if (waitedMs > 0L) sb.append(",\"waitedMs\":").append(waitedMs);
        // 入参随卡片下发：前端工具卡展开要显示「这把工具拿什么去做的」，缺它展开就是一张空卡。
        // 必须按字符串下发（和落库那份同形，见 Recording#toolCall）：前端读 args 的四处全是
        // JSON.parse(t.args)，裸拼对象会让流式时的标题行与入参段都成 [object Object]（刷新读落库那份却正常），
        // 还顺带废掉「同一次 start 只建一张卡」的入参比对（两个对象永不 ===）
        if (args != null && !args.isBlank()) sb.append(",\"args\":").append(com.alibaba.fastjson2.JSON.toJSONString(args));
        // 输出（截断）：展开卡的「输出」段读它；缺了只剩入参，看不到工具到底返回了什么
        if (result != null && !result.isBlank()) sb.append(",\"result\":").append(com.alibaba.fastjson2.JSON.toJSONString(result));
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
        return s.length() > 2000 ? s.substring(0, 2000) + "…（已截断）" : s;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static String json(String s) {
        return s == null ? "\"\"" : "\"" + esc(s).replace("\t", "\\t") + "\"";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
