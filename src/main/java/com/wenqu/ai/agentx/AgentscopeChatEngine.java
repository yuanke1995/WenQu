package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.RelatedPrompt;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.plan.PlanCopy;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.engine.ChatEngine;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEvent;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.model.Agent;
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
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
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

    /** 条数闸的余量：配置要 N 条，模型多给一两条还算清单，几十条那种整块文档才判成正文。 */
    private static final int RELATED_CHIPS_TOLERANCE = 2;

    /**
     * 会挂「子对话」卡的框架派遣工具名。只认 {@code agent_spawn}：它的入参带 {@code agent_id}
     * （逐字=问渠的智能体 id），能与本轮台账对上；{@code agent_send} 的入参只有 {@code agent_key}/
     * {@code label}，认不出是哪一位，宁可不挂也不把上一支的子对话错配到这一张卡上。
     */
    private static final java.util.Set<String> CONSULT_CARD_TOOLS = java.util.Set.of("agent_spawn");

    /** 一次从 Session Log 读多少条事件（框架的翻页上界，见 {@link #drain}）。 */
    private static final int DRAIN_PAGE = 200;

    /**
     * 跟随循环问一次「权威状态」的最长间隔（毫秒），见 {@link TurnMirror}。
     * <p>镜像跟事件，但框架算任务状态时还看两件<b>不落事件</b>的事：租约过期（{@code receipt} 把
     * running 降级成 interrupted）和 inbox 里的 rejected。光跟事件就永远看不见这两件，所以每一跳之外
     * 再按这个间隔回查一次。取 2 秒的依据：那一跑真死掉时，最坏晚 2 秒才收，而机器预算与看门狗都在
     * 更长的线上兜着；反过来若把它压回 200ms，就把刚省下来的全量重读又请回来了。
     */
    private static final long STATE_RECHECK_MS = 2_000L;

    /** 本轮提交离队的宽限：投递循环 100ms 一跳，超过这一段还没启动就是被闸门锁住了。 */
    private static final long LAUNCH_GRACE_MS = 30_000L;

    private final ModelRegistryService modelRegistry;
    /** 工作区约定：注入 system prompt 的工作背景段（无工作区/无约定时为空） */
    private final com.wenqu.ai.service.ProjectService projectService;
    private final ConfigService configService;
    private final AppProperties properties;
    private final WenquAgentxTools tools;
    private final FrameworkSessions sessions;
    private final com.wenqu.ai.service.SessionService sessionService;
    /** 附件元信息落库的共用口径（与工作流路径同一个方法，见 metasJson 的类注释）。 */
    private final ChatAttachmentService attachments;
    /**
     * 用户上传图片的落盘链路（data URL → 文件 + 回显 URL）。
     * <p><b>为什么必须接回来</b>：手写推理循环退役时，本服务仅有的两个调用点跟着一起被删了，于是框架这条路上
     * 图片两头都断——{@code appendMessage} 拿到的是原始 base64 data URL，而 {@code c_ai_message.images} 是
     * {@code TEXT}(65535) 且库跑在 {@code STRICT_TRANS_TABLES} 下，一张 1280px JPEG 的 base64 约 270KB，
     * 实测直接 {@code ERROR 1406 Data too long} 打断整轮；同时模型侧再没人把图片拼进本轮输入，图发了等于没发。
     * 本服务（落盘 + 回显 URL + 保留原图 dataURL 供直发）与 {@code ScheduleCenter} 的清理任务本来就还在。
     */
    private final com.wenqu.ai.service.UserImageService userImages;
    /** 关键词召回用的分词器（问答侧的关键词抽取，与技能无关）。 */
    private final com.wenqu.ai.service.KeywordExtractor keywordExtractor;
    private final com.wenqu.ai.service.ArtifactService artifactService;

    /** 沙盒装配（全框架化 P1）：供 builder 侧 .abstractFilesystem(...) 取本轮的沙箱文件系统。 */
    private final com.wenqu.ai.service.SandboxService sandboxService;

    /** 技能仓库（全框架化 P3）：无状态，按当轮 RuntimeContext 解析可见技能，故由 Spring 单例注入。 */
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
     * <p>P3 换框架后原记账出口（{@code DynamicOpenAiChatModel}）只剩 Spring AI 侧，
     * 框架调用一笔都没进 {@code c_ai_usage_log}；不补这一层，记忆与压缩的成本完全不可见。
     */
    private final com.wenqu.ai.service.UsageLedgerService usageLedger;
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
    /** 多副本协调（StoreBackedPeriodicGate + consolidation watermark）：与 stateStore 复用同一个 Jedis。 */
    private volatile io.agentscope.harness.agent.DistributedStore cachedDistributedStore;
    private volatile boolean distributedStoreResolved;
    private volatile UnifiedJedis cachedJedis;
    private volatile boolean jedisResolved;

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
                               com.wenqu.ai.skill.WenquSkillRepository skillRepository,
                               com.wenqu.ai.service.OrgService orgService,
                               com.wenqu.ai.service.UsageLedgerService usageLedger,
                               java.util.function.Function<ChatCommand, com.wenqu.ai.service.ChatService.TurnModel> modelResolver) {
        this.modelRegistry = modelRegistry;
        this.projectService = projectService;
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
        this.displayNames = new ToolDisplayNames(toolRegistry);
        this.skillRepository = skillRepository;
        this.orgService = orgService;
        this.usageLedger = usageLedger;
        this.modelResolver = modelResolver;
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
        });
    }

    /**
     * 本轮「文本 + 原图」的多模态消息：正文一个 TextBlock，每张图一个 ImageBlock。
     * <p>只在模型能读图（{@code visionCapableOf}）时走这条路——读不了图还硬塞 ImageBlock，
     * 上游要么报错要么静默丢图，两种都比「明确不发 + 在问题里说明看不见」更糟。
     * <p>图片用 {@code Base64Source} 直发原始字节：data URL 已经就是 base64（拆掉
     * {@code data:<mediaType>;base64,} 前缀即可），不必再落盘读回一遍——本服务本来就是
     * 「原图直发、不丢细节」的那条链路的产物。
     */
    private io.agentscope.core.message.Msg buildMultimodalMsg(
            String text, List<com.wenqu.ai.service.UserImageService.UserImage> imgs) {
        List<io.agentscope.core.message.ContentBlock> blocks = new java.util.ArrayList<>();
        blocks.add(io.agentscope.core.message.TextBlock.builder().text(text).build());
        for (com.wenqu.ai.service.UserImageService.UserImage img : imgs) {
            String dataUrl = img.dataUrl();
            if (dataUrl == null || !dataUrl.startsWith("data:")) continue;
            int comma = dataUrl.indexOf(',');
            if (comma <= 0) continue;
            // data:<mediaType>;base64,<payload>
            String header = dataUrl.substring(5, comma);
            int semi = header.indexOf(';');
            String mediaType = semi < 0 ? header : header.substring(0, semi);
            if (mediaType.isBlank()) mediaType = "image/jpeg";
            String payload = dataUrl.substring(comma + 1);
            if (payload.isBlank()) continue;
            blocks.add(io.agentscope.core.message.ImageBlock.builder()
                    .source(new io.agentscope.core.message.Base64Source(mediaType, payload))
                    .build());
        }
        // 一张都没解析出来（data URL 形态异常）就退回纯文本：绝不 submit 一个只有 TextBlock 以外的空壳
        if (blocks.size() == 1) return null;
        return io.agentscope.core.message.Msg
                .builderForRole(io.agentscope.core.message.MsgRole.USER)
                .content(blocks).build();
    }

    /** @ 引用 / # 历史引用的消息级常驻标注（刷新/历史回显保留）。 */
    private String mentionsJson(List<ChatRequest.Mention> mentions) {
        if (mentions == null || mentions.isEmpty()) return null;
        return com.alibaba.fastjson2.JSON.toJSONString(mentions);
    }

    private String historyRefsJson(List<ChatRequest.HistoryRef> historyRefs) {
        if (historyRefs == null || historyRefs.isEmpty()) return null;
        return com.alibaba.fastjson2.JSON.toJSONString(historyRefs);
    }

    /**
     * 本轮「指定技能」的正文注入。
     * <p>框架的 {@code HarnessSkillMiddleware} 每轮会把<b>全部</b>可见技能的名称+描述注入系统提示，
     * 正文由模型按需取；而用户在输入框里点名了某几把技能，意思是「这一轮就照这几条做」。
     * 点名不生效时，用户看到的是「我选了技能，它答得跟没选一样」，所以这里把正文直接送进本轮上下文
     * （与工作流技能节点同一口径：{@code WenquSkillRepository.getSkill(name, ctx)} 按归属人解析）。
     * <p>取不到必须写进正文而不是静默跳过：技能被删掉或改名时，让模型如实回答「这条技能当前不可用」，
     * 比让它按记忆里的旧做法作答更诚实。
     */
    private String pickedSkillsText(ChatCommand cmd, io.agentscope.core.skill.SkillFilter filter) {
        List<String> picked = cmd.getSkills();
        if (picked == null || picked.isEmpty()) return "";
        String uid = cmd.getUserId();
        if (uid == null || uid.isBlank()) return "";
        int maxChars = com.wenqu.ai.config.ConfigDefaults.SKILL_MAX_FILE_CHARS;
        RuntimeContext ctx = RuntimeContext.builder().userId(uid).sessionId(cmd.getSessionId()).build();
        StringBuilder sb = new StringBuilder();
        for (String raw : picked) {
            if (raw == null || raw.isBlank()) continue;
            // 智能体「指定」模式存的是引用串 {uid}/{name}（个人技能要精确到归属人），输入框点名给的是裸名
            String name = raw.contains("/") ? raw.substring(raw.lastIndexOf('/') + 1) : raw;
            sb.append("\n\n【本轮指定技能：").append(name).append("】\n");
            // 智能体的「技能范围」是能力边界，不是建议：点名也不能越过它。
            // 只由框架 skillFilter 拦的话，这里仍会把正文直接拼进上下文，白名单等于被点名这条路绕过。
            if (filter != null && !filter.isAllowed(name)) {
                sb.append("（本智能体没有启用这条技能：它的「技能范围」未选中「").append(name)
                        .append("」。请如实告知用户，改用本智能体在用的做法作答，不要按这条技能的内容作答）");
                continue;
            }
            try {
                io.agentscope.core.skill.AgentSkill sk = skillRepository.getSkill(name, ctx);
                String body = sk == null ? null : sk.getSkillContent();
                if (body == null || body.isBlank()) {
                    sb.append("（这条技能当前对当前用户不可用：可能已被删除或改名。请如实告知用户，不要凭记忆代答）");
                } else {
                    sb.append(body.length() > maxChars ? body.substring(0, maxChars) + "\n…（正文超长已截断）" : body);
                }
            } catch (RuntimeException e) {
                log.warn("[SKILL] 读取指定技能失败 name={}: {}", name, e.toString());
                sb.append("（这条技能正文读取失败：").append(e.getMessage()).append("）");
            }
        }
        return sb.toString();
    }

    /**
     * 本轮随行内容 → 送进模型的用户文本。
     * <p><b>为什么必须在这里装配</b>：附件正文 / @ 引用 / # 历史引用此前**只落库、不入上下文**——
     * 用户上传了文件、在输入框里 @ 了知识库、# 了历史问答，模型却一个字都收不到，于是如实回答
     * 「没有看到您上传的文件」。落库与回显只是给人看的，模型要答得对，内容必须真的送到它眼前。
     * <p>三段各自独立、任一为空就不追加段落；附件解析失败由 {@link ChatAttachmentService#prepare}
     * 自己兜成一段可读的错误说明（fail-loud 到用户可见文案，而不是静默丢）。
     */
    private String userContentWithContext(ChatCommand cmd, io.agentscope.core.skill.SkillFilter skillFilter) {
        StringBuilder sb = new StringBuilder();
        String q = cmd.getQuestion() == null ? "" : cmd.getQuestion();
        // 插话没赶上本轮、随收尾交回后作为新一轮补发的那一条：不带这句声明，模型看到的是孤零零一句
        // 新用户消息，会当成「用户又问了一遍」重新起题。与送进框架会话的那一条同一个出口（ChatCommand）
        sb.append(cmd.isFromSteer() ? ChatCommand.decorateSteerQuestion(q) : q);
        // 1) 文档附件正文（当轮看一眼的轻路径：只抽文本，超长截断，不进向量库）
        if (cmd.getAttachments() != null && !cmd.getAttachments().isEmpty()) {
            List<ChatAttachmentService.PreparedAttachment> prepared =
                    // 按上传归属者读（免登录分享时是访客，不是发布者）——见 ChatCommand#uploadOwnerUid
                    attachments.prepare(cmd.getAttachments(), cmd.uploadOwnerUid());
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
        // 4) 本轮指定技能：点名了就把正文送进去，否则「选了技能」这件事对模型完全不可见
        sb.append(pickedSkillsText(cmd, skillFilter));
        return sb.toString();
    }

    /**
     * 解析并缓存会话状态存储。默认本地文件；仅当 {@code chat.agentscopeRedisEnabled=true} 时尝试 Redis。
     * <p>Redis 路径全程兜底：连不上、鉴权失败、甚至 Jedis 类缺失（被仓库既有 Jedis 声明压成低版本）
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
        UnifiedJedis jedis = redisJedis();
        if (jedis == null) {
            return new JsonFileAgentStateStore(stateDir);
        }
        String prefix = firstNonBlank(configService.get("chat.agentscopeRedisPrefix"), "agentscope:session:");
        return RedisAgentStateStore.builder().jedisClient(jedis).keyPrefix(prefix).build();
    }

    /**
     * 全局 Redis 连接（stateStore 与 distributedStore 共用，避免一个引擎建两条连接）。
     * <p>连不上/鉴权失败/类缺失都返回 null，由调用方退回本地实现——绝不因为开了多副本开关把引擎打挂。
     */
    private UnifiedJedis redisJedis() {
        if (!"true".equalsIgnoreCase(configService.get("chat.agentscopeRedisEnabled"))) return null;
        if (jedisResolved) return cachedJedis;
        synchronized (this) {
            if (jedisResolved) return cachedJedis;
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
                log.info("[agentx] 框架 Redis 已连接: {}:{} db={}", host, port, redisProperties.getDatabase());
            } catch (Throwable e) {
                cachedJedis = null;
                log.warn("[agentx] Redis 不可用（{}），框架状态/协调退回本地实现", e.toString());
            } finally {
                jedisResolved = true;
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
     */
    private io.agentscope.harness.agent.DistributedStore distributedStore() {
        if (distributedStoreResolved) return cachedDistributedStore;
        synchronized (this) {
            if (distributedStoreResolved) return cachedDistributedStore;
            try {
                UnifiedJedis jedis = redisJedis();
                if (jedis == null) {
                    cachedDistributedStore = null;
                } else {
                    cachedDistributedStore = io.agentscope.extensions.redis.RedisDistributedStore
                            .fromJedis(jedis, "agentscope:");
                    log.info("[agentx] 记忆/压缩节流与 consolidation watermark 使用 Redis 跨副本协调");
                    log.warn("[agentx] 多副本提示：记忆文件仍落本地 chat.agentscopeStateDir，"
                            + "请确保各副本挂同一共享卷，否则同一用户在不同副本记忆不一致");
                }
            } catch (Throwable e) {
                cachedDistributedStore = null;
                log.warn("[agentx] 分布式协调装配失败（{}），退回单副本节流", e.toString());
            } finally {
                distributedStoreResolved = true;
            }
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
            failLoud(channel, "模型「" + ref + "」未登记或已不可用：请在「智能体 → 模型供应商」里确认该供应商与模型");
            return;
        }
        ExecutionPolicy policy = tools.policyFor(cmd,
                (code, message) -> channel.emit(RunEventType.WARN, message));
        WenquSourcePool pool = tools.newPool(cmd, channel.emitter());
        // 本轮改过用户存过的选择（失效的会话模型已清除、改用他的默认模型）必须说出来。
        // 只登记降级、不再另发一条 WARN：降级随消息落库、刷新后仍在，WARN 是同一句话的第二份，
        // 两条都会渲染成警示条（实测同一个原因上下各一条）
        if (turn.fallbackNotice() != null) {
            pool.registrar().degrade("modelFallback", turn.fallbackNotice());
        }

        // 本轮身份（轮级互斥外壳已经登记好 turnId）：待办落库、任务清单留痕、步数预算归零都认它
        String turnId = turnIdOf(cmd);
        java.util.concurrent.ConcurrentLinkedQueue<String> spills = new java.util.concurrent.ConcurrentLinkedQueue<>();
        WenquAgentxTools.TurnHooks hooks = tools.newHooks(cmd, ref, () -> turnId, spills,
                consultService.newLedger());
        // 本轮落点：工具表在 build 期定型、实例按会话常驻，pool/channel/hooks 只能放进会话级槽位，
        // 由这里在 submit 之前换掉。早于 sessions.open 是为了让建实例期的降级也能发到当轮通道上。
        AgentxTurnSlot slot = sessions.turnSlot(cmd);
        slot.begin(tools.newTurn(cmd, pool, channel, policy, hooks));
        // 本轮智能体绑定一次解析：实例重建判据、人设、技能白名单三处共用同一份（避免一轮读三遍库，
        // 也避免「判据说没换、人设却按另一份装配」这种自相矛盾）
        AgentBinding binding = bindingOf(cmd, policy);
        // 实例键用完整引用而不是裸模型名：同一个模型名可以挂在好几个供应商下（各自的 baseUrl 与 Key 不同），
        // 只按裸名判「模型没换」就不重建实例，于是换了供应商仍拿旧网关旧 Key 跑，且日志上一切正常
        AgentSession session;
        try {
            session = sessions.open(cmd, ref, binding.key(),
                    ctx -> buildAgent(cmd, policy, route, hooks, binding, slot));
        } catch (RuntimeException e) {
            // 建实例失败也要把槽位收回：否则这一会话的「当前轮」永远停在一次没跑起来的轮上
            slot.end(turnId);
            throw e;
        }
        // run_code 的脚本通道按轮现装：工具面在 build 期固定，轮号/通道/台账必须是当轮的
        tools.ensureRunCodeTurn(slot, cmd);
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
        // 框架工具档位闸按轮更新标签（全框架化 P1）：拒了什么要能在日志里追到是哪一轮。
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

        // 问题先落库：这一轮哪怕中途停掉或出错，界面上也不该出现「问出去了却什么都没有」的空档
        // （与手写运行时的即时落库同一条口径）。附件/@/#历史引用随用户消息一并持久化，刷新后回显不丢。
        String userMessageId = cmd.isRegenerate() || cmd.isFromSteer() ? null
                : sessionService.appendMessage(cmd.getSessionId(), "user", cmd.getQuestion(),
                        userImgUrls.isEmpty() ? null : userImgUrls, null, null, null, null, null,
                        attachments.metasJson(cmd.getAttachments(), cmd.uploadOwnerUid()),
                        null, null, null, null, null, null, null,
                        mentionsJson(cmd.getMentions()),
                        historyRefsJson(cmd.getHistoryRefs()),
                        null, turnId);
        Recording recording = new Recording(spills, hooks.consultLedger(), tools, hooks, displayNames);
        bindFrameworkToolGate(cmd, recording);

        // 计划模式（框架原生 plan_enter/plan_write/plan_exit）按轮进入，跑完必须退出。
        // 是否进入只由两件事决定：用户显式开计划开关，或模型自己在推理中调用 plan_enter。
        // 问渠不再做「按消息意图预判」——预判器已删除，避免在模型之外多一层会误判的闸。
        boolean planWanted = cmd.isPlanMode();
        boolean planEngaged = planWanted && sessions.setPlanMode(cmd, true);
        if (planWanted && !planEngaged) {
            log.warn("[PLAN] 计划模式请求但未进入激活位（框架侧未就位），本轮不展示计划卡: session={}",
                    cmd.getSessionId());
            channel.emit(RunEventType.WARN, "计划模式未能启用（框架侧未进入计划位），本轮将不使用计划卡");
        }
        // 送进模型的不只是问题原文：附件正文 / @ 引用 / # 历史引用都在这一段拼进去。
        // 此前这里直接送 cmd.getQuestion()，于是用户传的东西模型一概看不见（附件问题表现为
        // 它如实回答「没有看到您上传的文件」）——落库只保证人能回看到，不等于模型收到了。
        String modelInput = userContentWithContext(cmd, binding.skillFilter()) + imgNote;
        // 模型能读图时，把原图作为 image_url 内容部件随本轮一起送进去。此前这里只有 submit(String)，
        // 图片在「问题正文」里没有任何落点——附件有 buildContextText 抽正文，图片没有等价的通道，
        // 于是发了图模型也一概看不见（它只会如实回答"没有看到图片"）。
        // 框架原生支持多模态：submit(Msg)，Msg 里可混排 TextBlock 与 ImageBlock。
        // buildMultimodalMsg 在图片形态都不可用时会返回 null，那时退回纯文本 submit，不拿 null 去提交
        io.agentscope.core.message.Msg multimodal =
                (chatSeesImages && !userImgs.isEmpty()) ? buildMultimodalMsg(modelInput, userImgs) : null;
        // 工具面按轮落到框架的组激活集上（判据仍取自老装配那一条 exposes，不另写一份），
        // 并把「配置允许的名单」与「框架真正下发给模型的工具表」逐名比对一次——不一致就发 warn，
        // 静默少一道闸正是这套机制最初要治的病。
        for (String surfaceProblem : sessions.applyToolSurface(cmd, tools.turnGroupedNames(cmd, policy))) {
            channel.emit(RunEventType.WARN, surfaceProblem);
        }
        AgentSession.Task current = multimodal != null
                ? session.submit(multimodal)
                : session.submit(modelInput);
        // 占住这一条会话的跟随位，收尾让位：答复端点据此判断「这次答完有没有人接着跑」
        String sessionKey = sessions.key(cmd);
        sessions.claimFollower(sessionKey);
        try {
            followTurn(session, cmd, channel, pool, route, hooks, turnId, historyBefore, startCursor,
                    userMessageId, current, recording, System.currentTimeMillis());
        } finally {
            sessions.releaseFollower(sessionKey);
            // 收口后清掉本轮落点：之后再有工具调用会明确失败，而不是回落到这一轮的池子里
            slot.end(turnId);
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
                            Recording recording, long startedAt) {
        long machineBudgetMs = turnSeconds() * 1000L;
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
        // 会话状态改为跟事件认（见 TurnMirror）。起轮种一次权威待办，之后每一跳只认这一跳读到的事件。
        TurnMirror mirror = new TurnMirror();
        mirror.resyncPending(session);
        // 兜底复查时刻：租约过期与 inbox 拒绝不落事件，只能按 STATE_RECHECK_MS 回查权威值
        long lastStateProbeAt = 0;

        // 一整轮里待办可能出好几次：批准之后模型仍可再要一次确认。所以这里不是「等一次答复」，
        // 而是跟随会话现状——有待办就等人，没待办就跟到最后一条任务，任务结算了还要再看一眼
        // 有没有新待办或接续出来的新任务，两者都没有才算这一轮跑完（真机踩过：只等一次就收尾，
        // 第二次确认的卡永远发不出去，正文也是空的）
        while (machineMs(startedAt, humanWaitMs, waitingOnHuman, waitBeganAt) < machineBudgetMs) {
            if (!launched && current != null && !"queued".equals(current.status())) launched = true;
            cursor = drain(session, cursor, emitFrom, channel, recording, mirror);
            final boolean pendingChanged = mirror.takePendingChanged();
            final boolean taskChanged = mirror.takeTaskChanged();
            // 用量帧只在上下文真的添了行时才算：算它要先取 transcript，那是一次全量读
            emitLiveUsage(session, cmd, pool, channel, historyBefore, usageStamp, mirror.takeMessageChanged());
            announcedRefs = emitRetrieved(cmd, pool, channel, announcedRefs, recording);
            // 用户点了「继续对话」：这一轮就此收口——修改意见由前端作为一条用户消息开出完整新一轮
            // （与手写引擎同一契约）。框架那一跑是被 reject 唤醒的，它会自己往下重出一版计划，
            // 不掐断就是把那一跑的帧漏进下一轮；而前端紧跟着发的新请求还会撞上会话轮级互斥，
            // 用户看到的是「生成失败」。排在 announcePending 之前：否则掐断之前先给用户多弹一张卡。
            if ("supersede".equals(sessions.peekPlanOutcome(sessions.key(cmd)))) {
                session.interrupt();
                break;
            }
            if (pendingChanged) {
                announcePending(mirror.pendingSnapshot(), cmd, channel, announcedApprovals,
                        turnId, recording);
            }
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
                    && !mirror.hasPending() && session.inspect().activeRuns().isEmpty()) {
                neverLaunched = true;
                break;
            }
            if (mirror.hasPending()) {
                Map<String, SessionEvent> waiting = mirror.pendingSnapshot();
                if (pendingSince == 0) {
                    pendingSince = System.currentTimeMillis();
                    // 人在回路的等待登记进共享台账（与 ChatService 同一入口）：
                    // 这一段是「用户在看卡/在答题」，不是机器在干活，不该消耗整轮机器预算。
                    // 不登记的后果是本轮必然在 300s 线上被看门狗误截断——真机 2026-10-15 13:59:53 实测：
                    // 计划卡 13:57:46 挂出、用户还没点批准就被判「机器耗时 300087ms / 预算 300000ms
                    // （人工等待 0ms 不计入）」，界面上就是「一直 loading 到超时」。
                    // 引擎自己的机器预算同理要按同一口径扣掉，否则卡一挂出这一轮就只剩几十秒。
                    beginHumanWait(channel, recording, waiting);
                    waitBeganAt = System.currentTimeMillis();
                    waitingOnHuman = true;
                }
                // 三张卡三档等待预算（计划/提问/工具审批），判据与 announcePending 分流同源
                PendingKind kind = pendingKind(waiting);
                long budget = pendingBudgetMs(kind);
                if (System.currentTimeMillis() - pendingSince > budget) {
                    if (kind == PendingKind.ASK && answerTimedOutAsk(waiting, cmd)) {
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
                    // 超时一律不替用户代答（既有口径）：卡片作废，这一轮按未完成收。
                    // 待办的终态（框架结算 + 卡片收口 + 落库）统一由循环外的 abandonPendingInteractions 做
                    channel.emit(RunEventType.WARN, kind == PendingKind.PLAN
                            ? "计划等待超时，本轮未继续执行"
                            : "确认等待超时，本轮未继续执行该工具");
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
            // 任务状态只在「事件说它变了」或到了兜底间隔时才问权威值：tasks() 对每条历史命令各做一次
            // 全量 scan，旧循环每一跳都问一遍，长会话里光这一问就是几百毫秒（见 TurnMirror）
            if (taskChanged || System.currentTimeMillis() - lastStateProbeAt >= STATE_RECHECK_MS) {
                lastStateProbeAt = System.currentTimeMillis();
                AgentSession.Task latest = latestTaskInTurn(session, current);
                if (latest != null) current = latest;
            }
            if (current == null || current.settled()) {
                // 结算与待办之间没有原子边界：先让出一拍再确认一遍，避免抢在下一张卡之前收尾
                sleep(150);
                cursor = drain(session, cursor, emitFrom, channel, recording, mirror);
                announcedRefs = emitRetrieved(cmd, pool, channel, announcedRefs, recording);
                // 收尾这一支现读权威值、不用镜像：整轮只走得到这一次，而「少弹一张卡」是用户
                // 看得见的事，镜像最多晚一跳，这里不给它晚的机会
                Map<String, SessionEvent> latePending = session.pending();
                if (!latePending.isEmpty()) {
                    announcePending(latePending, cmd, channel, announcedApprovals, turnId, recording);
                    continue;
                }
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
        // 收尾把游标跑到 head：这一轮的尾巴增量不能再漏（镜像传 null——出轮之后没人读状态）
        drain(session, cursor, emitFrom, channel, recording, null);

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
        }

        // 机器预算用尽退场、卡片还挂着：这一轮不会再有跟随者（超时回收要靠活着的那个循环，它已经退了）。
        // 不判终态就是两件坏事，而且第二件正是用户报的那个症状：
        //  1) 下面 `awaiting` 那一支刻意不落库 → 用户刷一下页面，整半轮过程（思考/工具卡/引用/用量）
        //     在库里根本不存在，界面上凭空消失，只剩第一句提问；
        //  2) 卡片留在 PENDING → 恢复端点还能把它重建出来，但答了没人接，成了「点了没反应」。
        // 所以按超时收口：待办落 TIMEOUT、这一轮走 timedOut 那条落库与文案（半程内容据此可见）。
        if (!stopped && !timedOut && !superseded && !neverLaunched && !session.pending().isEmpty()) {
            channel.emit(RunEventType.WARN, "本轮已超时结束，等你确认的那一步已作废");
            timedOut = true;
        }

        if (!stopped && !timedOut && !superseded && session.pending().size() > 0) {
            // 「答完会自动接着跑」只对还活着的等待成立：超时那一支已经把待办落成终态，
            // 再说这句就是在承诺一件不会发生的事
            channel.emit(RunEventType.WARN, "有工具在等你确认，答完会自动接着跑（可稍后回来接着看它）");
        }
        // 这一轮到此不再有人跟随：还挂在人身上的待办当场收口（框架结算 + 卡片终态 + 待办行落库）。
        // neverLaunched 那一支不碰——那些待办属于**上一轮**，卡片可能正等用户答，本轮没启动不是它的错。
        if (!neverLaunched) abandonPendingInteractions(session, cmd, channel, recording, stopped);
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
        if (answer.isBlank() && failure == null) answer = stripFallbackBody(recording, lastAssistantText(session));
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
     * <p>只在「那一轮确实不会再动了」时动手：还在跑（activeRuns）或有待对账的工具结果
     * （uncertainToolCalls，框架要求先 reconcile）时一律不碰。挂着待办（pending）的那一轮分两种：
     * 卡片还挂在 PENDING = 真的在等人，不碰；卡片已随叫停/超时落终态 = 没人会再答它，
     * 那一轮同样永远不会结束（{@code run/end status=suspended}），照归。
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
        if (!view.activeRuns().isEmpty() || !view.uncertainToolCalls().isEmpty()) return;
        // 挂在人身上那张卡的轮也算「遗留」：卡片在问渠侧已经落终态（叫停/超时都落），没人能再答它，
        // 而框架那一轮停在 suspended 不算结束——不放过这一条，这条会话之后就再也起不动了。
        // 还挂在 PENDING 的照旧不碰：那一张真的在等人。
        if (!session.pending().isEmpty() && !sessions.abandoned(session.pending().keySet())) return;
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
                             ExecutionPolicy policy,
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
                .userId(userId == null || userId.isBlank() ? "guest" : userId)
                .agentId(agentIdOfSession(sessionId))
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
        AgentBinding binding = bindingOf(cmd, policy);
        AgentxTurnSlot slot = sessions.turnSlot(cmd);
        AgentSession session;
        try {
            session = sessions.open(cmd, ref, binding.key(),
                    ctx -> buildAgent(cmd, policy, route, hooks, binding, slot));
        } catch (Exception e) {
            log.warn("[agentx] 重建会话失败 session={}: {}", sessionId, e.toString());
            return null;
        }
        AgentxStepBudget stepBudget = sessions.stepBudget(sessions.key(cmd));
        if (stepBudget != null) {
            stepBudget.beginTurn(turnId, tools.maxToolSteps(cmd), (code, message) -> {
            });
        }
        // 接续轮同样要更新档位闸标签（全框架化 P1）：否则被拒的工具在日志里挂到上一轮头上。
        AgentxFrameworkToolGate toolGate = sessions.frameworkToolGate(sessions.key(cmd));
        if (toolGate != null) {
            toolGate.beginTurn(turnId);
        }
        Recording recording = new Recording(spills, hooks.consultLedger(), tools, hooks, displayNames);
        bindFrameworkToolGate(cmd, recording);
        return new Following(cmd, channel, pool, route, hooks, policy, session,
                session.transcript().messages().size(), session.log().head().seq(), recording);
    }

/**
 * 供 builder 用的框架文件系统（全框架化 P1）。
 *
     * <p><b>为什么走 builder 而不是 RuntimeContext</b>：框架的文件工具在 build 期就绑好了文件系统
     * （{@code HarnessAgent:2895} {@code new FilesystemTool(filesystem, ...)}、
     * {@code :2903} {@code new ShellExecuteTool(sandbox)}），两者都读 builder 字段，
     * 对 {@code RuntimeContext} 里的 {@code AbstractFilesystem} 无感。
     * 真机验证过这个差别：只往 ctx 里put 时，{@code execute("pwd")} 返回宿主项目目录。
     *
     * <p>拿不到时返回 null（沙盒不可用）——框架会退回默认文件系统，形态与 P1 之前一致，
     * 不为了「有工具就挂」而给一个指向宿主的文件系统：那会让隔离静默失效。
     */
    private io.agentscope.harness.agent.filesystem.AbstractFilesystem frameworkFilesystem(ChatCommand cmd) {
        if (sandboxService == null) return null;
        String uid = cmd.getUserId();
        String sessionId = cmd.getSessionId();
        if (uid == null || uid.isBlank() || sessionId == null || sessionId.isBlank()) return null;
        return sandboxService.frameworkFilesystem(sessionId, uid, /* unrestrictedWrites */ false);
    }

    /**
     * 把本轮的工具卡记录器交给框架工具档位闸（全框架化 P1）。
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
        String sessionKey = sessions.key(f.cmd());
        if (!sessions.reserveFollower(sessionKey)) {
            log.info("[agentx] 这一轮已经有跟随者在跑，不重复起 session={}", f.cmd().getSessionId());
            return;
        }
        // 接回的那一轮也要占住槽位：工具与脚本通道读的是「当前轮」，不 begin 就会拿到上一轮
        AgentxTurnSlot slot = sessions.turnSlot(f.cmd());
        slot.begin(tools.newTurn(f.cmd(), f.pool(), f.channel(), f.policy(), f.hooks()));
        tools.ensureRunCodeTurn(slot, f.cmd());
        String turnId = f.hooks().turnId().get();
        RESUME_POOL.execute(() -> {
            try {
                followTurn(f.session(), f.cmd(), f.channel(), f.pool(), f.route(), f.hooks(),
                        turnIdOf(f.cmd()), f.historyBefore(), f.startCursor(), null, current,
                        f.recording(), System.currentTimeMillis());
                log.info("[agentx] 续跑已收尾 session={}", f.cmd().getSessionId());
            } catch (Exception e) {
                log.warn("[agentx] 续跑中断 session={}: {}", f.cmd().getSessionId(), e.toString());
            } finally {
                sessions.releaseFollower(sessionKey);
                slot.end(turnId);
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

    /**
     * 用户在本轮显式选过的模型上下文窗口（token），落到 {@link OpenAIChatModel} 的
     * {@code contextWindowSize}。框架的压缩阈值与输入预算都由它推导：
     * <ul>
     *   <li>{@code CompactionMiddleware#onReasoning}：{@code window - reserved(20k)} 为动态压缩阈值</li>
     *   <li>{@code HarnessContextBuilder#inputLimit}：{@code window - output - safety} 为输入上限</li>
     * </ul>
     * 未选 / 未登记（≤0）返回 0，调用方不设（模型按名查框架内置 {@code ModelContextWindows} 表）；
     * 不能把 0 显式塞给模型——{@code OpenAIChatModel} 的 builder 只在 {@code >=0} 时用显式值，
     * 0 会被当成「窗口=0」喂给压缩逻辑（每轮都压）。用户选过的窗口优先于框架内置表：
     * 问渠登记的窗口是模型真实口径，框架表按前缀猜名、对中文大模型常缺条目。
     */
    private int modelContextWindow(ChatCommand cmd) {
        Integer requested = cmd.getRequestedContextWindow();
        return requested == null || requested <= 0 ? 0 : requested;
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
     * 本轮到目前为止花掉的「机器时间」：墙钟减去人在回路的等待，<b>含正挂在卡上的这一段</b>。
     * <p>少了在途那一段就是拿墙钟当机器预算用：{@code humanWaitMs} 只在待办清空时才累加，用户对着
     * 计划卡读五分钟，循环下一跳算出来的机器时间就已经涨满 300s，整轮在人还没裁决时被静默收口
     * （真机 2026-10-16 19:56:33：机器耗时 59790ms／人工等待 333422ms 的一轮在卡上被判跑完，
     * 而 awaiting 让那一轮整条不落库，刷新后只剩第一句提问）。
     */
    private static long machineMs(long startedAt, long humanWaitMs, boolean waitingOnHuman, long waitBeganAt) {
        long now = System.currentTimeMillis();
        long waiting = humanWaitMs + (waitingOnHuman && waitBeganAt > 0 ? now - waitBeganAt : 0);
        return now - startedAt - waiting;
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

    /** 这一批待办按哪种形态处理：只要有提问卡就按提问走（提问最需要的是不替用户做决定）。 */
    private static PendingKind pendingKind(Map<String, SessionEvent> pending) {
        java.util.List<Map<String, Object>> datas = new java.util.ArrayList<>();
        for (SessionEvent e : pending.values()) {
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
     *      那张卡就永远转圈（真机 2026-10-16 21:19：用户早已叫停、正文也落了「已停止本轮」，那张
     *      「向用户提问」还在转圈——因为落库那份 {@code status} 就停在 start，刷新一次还是转圈）。</li>
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
                                            Recording recording, boolean stopped) {
        Map<String, SessionEvent> pending = new java.util.LinkedHashMap<>(session.pending());
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
            String status = ask ? "done" : "error";
            String result = ask ? "（用户未回答这一题（" + why + "））" : why + "，这一步没有执行";
            // 落库那份先写、无条件：刷新读的就是它
            recording.toolCall(name, status, args, elapsed, beganAt, charged);
            try {
                emit(channel, RunEventType.TOOL_STATUS,
                        toolCard(name, status, elapsed, beganAt, charged, args, result));
            } catch (RuntimeException gone) {
                log.info("[agentx] 待办终态帧没发出去（通道已收），落库那份仍在: session={} tool={}",
                        cmd.getSessionId(), name);
            }
        }
        log.info("[agentx] 本轮不再跟随，{} 条待办已收口: session={} 原因={}",
                pending.size(), cmd.getSessionId(), why);
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
         * 本轮<b>已经被框架写进会话上下文</b>的插话收据号（凭证是 {@code inbox/applied} 事件里的 commandIds）。
         * <p>记在这里而不是只记在 {@code TurnMirror} 上：收尾那次全量补读传的镜像是 null（出轮之后没人再读
         * 会话状态），而那恰恰是最后一条插话最可能被应用的时刻。
         */
        private final Set<String> appliedSteers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /**
         * 这一跑是否已经真的结束（看到过 {@code run/end}）。
         * <p>插话对账要用它：跟随循环撞到墙钟上限退出时，框架那一跑<b>还在后台继续</b>，那条插话会在
         * 下一次调模型前被正常应用。那时候交回前端等于把同一句话发两遍——所以只认「已经收尾、
         * 且没应用成」的那几条。
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
        }

        /**
         * 落库用时间线：工具段按调用顺序夹在正文段之间，末段补到 body 全长（含追加的相关推荐/中断标记）。
         * <p>body 若被引用自检整体替换过（{@code outcome.text()}），正文区间就对不上了——此时<b>只丢 text 段</b>，
         * 保留思考/过程/工具段：正文由前端按整段接在末尾（{@code timelineView} 的尾段），过程与工具卡仍点得开。
         * 整条返回 null 的旧口径会让前端在 done 那一步把时间线清空：用户刚看着跑完的几次检索/咨询，
         * 在答完的同一秒整体消失（真机：3 次 search_knowledge + 1 次咨询，点开「已完成」只剩「深度思考」）。
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
                    // 咨询卡片挂那一问一答（与手写侧同一个实现与同一把长度尺）：刷新后仍点得开。
                    // 派遣改由框架驱使后工具名换成 agent_spawn，认哪几把见 CONSULT_CARD_TOOLS
                    if (CONSULT_CARD_TOOLS.contains(name)) {
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
     * 本轮的角色提示词段（{@code sysPrompt} 的第一段）：智能体人设优先，为空回落平台默认。
     * <p>选段口径在 {@link RolePrompt#of}——抽成纯函数是为了让这条口径可被单测钉住，
     * 它此前是「注释写着归智能体管、实现里没人读」的形状。
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
    private record AgentBinding(Agent row, String key, io.agentscope.core.skill.SkillFilter skillFilter) {
    }

    private AgentBinding bindingOf(ChatCommand cmd, ExecutionPolicy policy) {
        Agent row = null;
        try {
            row = tools.agentOf(cmd);
        } catch (RuntimeException e) {
            // 解析失败不打断本轮：按「无绑定」走平台默认档（与改前 rolePrompt 的兜底一致）
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
        // 注意：思考开关/上下文窗口档也烤在实例里，但续跑入口（openForContinuation）重建的
        // ChatCommand 不带这两个值，若把它们并进 key，续跑会按默认档重建、丢掉用户原来的选择。
        // 因此这里只并入可复原的档位；思考/窗口档的变更留给后续「按调用传 GenerateOptions」那一刀。
        return new AgentBinding(row, key, skillFilterOf(row));
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
     * 本轮的**工作背景段**：该工作区在工作区页写的约定，对工作区内所有会话全程有效。
     * <p>取不到/没写约定一律返回空串——不能因为工作背景读失败就把整轮打挂，
     * 也不能凭空塞一段「这是兜底容器」的噪声（每轮都是纯 token 成本）。
     */
    private String projectContext(ChatCommand cmd) {
        try {
            String ctx = projectService.projectContextOfSession(cmd.getSessionId());
            return ctx == null ? "" : ctx;
        } catch (Exception e) {
            log.warn("[agentx] 工作背景读取失败（本轮不注入）session={}: {}", cmd.getSessionId(), e.getMessage());
            return "";
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
     * 回答，而「接下来可以」那排芯片同时没了（真机 2026-10-18 20:20 那轮，全库 502 条助手消息中 3 条）。
     * <p>这条按「隐形上限必须拆掉」改，不当它是已定案的根因：要触发得让每一拍慢到追不上增量，照同一
     * 族问题跑 7 轮（每轮 1350–1776 帧）没能复现。标签到不了用户眼前由 {@link #stripFallbackBody}
     * 在那条回退路径上兜死，不依赖这里的因果。
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
     * 各做一次全量 scan）同样按全量算。旧循环名义上 200ms 一跳，实际每一跳要做三四次这种读，
     * 真机实测一跳 1.05 秒：正文按秒成坨落地，界面上就成了「不是流式，一下就出来」
     * （2026-10-18 实测一轮 30.7s／977 帧，token 只分 3 簇：70／394／228 字）。
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
         * 种完把脏位置真，让第一跳照旧把这一批卡报出去——那是改前就有的行为，别顺手改掉。
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
                if (recording != null) {
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
     * 记忆提炼配置：两层闸都落在这里——系统设置「用户长期记忆 → 总开关」关掉时全平台不再提炼，
     * 个人设置里那个开关关掉时只对本人生效。关掉的都只是每轮收尾的那次提炼
     * （框架 {@code FlushTrigger.never()} 注释原文：*"disables only the per-call flush"*），
     * 已经沉淀下来的 {@code MEMORY.md} 仍照常注入——与两处界面「仅关生成，不关注入」的说明对齐。
     *
     * <p>P5 追加三件：① default 的每轮 ALWAYS 改成可配节流（默认 10 分钟），否则每轮问答
     * 都多一次主模型提炼调用；② prompt 追加问渠规则（中文、只记持久事实、不记密钥/探针）；
     * ③ 记忆模型包 MeteredModel（kind=memory），成本进台账。{@code MEMORY.md} 的硬上限
     * 不在这里——它落在写入文件系统那一层（{@code MemoryCappedFilesystem}）。
     */
    private io.agentscope.harness.agent.memory.MemoryConfig memoryConfig(
            ChatCommand cmd, io.agentscope.core.model.Model baseModel, String modelRef) {
        boolean on = configService.getBoolean("memory.enabled", true)
                && orgService.memoryFlushEnabled(cmd.getUserId());
        io.agentscope.core.model.Model memoryModel = new MeteredModel(baseModel, usageLedger,
                com.wenqu.ai.util.UsageAttr.of(cmd.getUserId(), cmd.getSessionId(), null, "memory"), modelRef);
        io.agentscope.harness.agent.memory.MemoryConfig.Builder b =
                io.agentscope.harness.agent.memory.MemoryConfig.builder()
                        .model(memoryModel)
                        .flushPrompt(com.wenqu.ai.memory.MemoryPrompts.flushPrompt())
                        .consolidationPrompt(com.wenqu.ai.memory.MemoryPrompts.consolidationPrompt())
                        .consolidationMaxTokens(Math.max(500, configService.getInt(
                                "memory.consolidationMaxTokens",
                                com.wenqu.ai.config.ConfigDefaults.MEMORY_CONSOLIDATION_MAX_TOKENS)))
                        .dailyFileRetentionDays(Math.max(7, configService.getInt(
                                "memory.dailyRetentionDays",
                                com.wenqu.ai.config.ConfigDefaults.MEMORY_DAILY_RETENTION_DAYS)));
        if (!on) {
            return b.flushTrigger(
                    io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger.never()).build();
        }
        int minutes = configService.getInt("memory.flushThrottleMinutes",
                com.wenqu.ai.config.ConfigDefaults.MEMORY_FLUSH_THROTTLE_MINUTES);
        return b.flushTrigger(minutes <= 0
                        ? io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger.always()
                        : io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger.throttled(
                                java.time.Duration.ofMinutes(minutes)))
                .build();
    }

    /**
     * 压缩配置。框架默认（不配＝动态 token 阈值或 50 条消息触发、逐字留最近 20 条、prune 开）
     * 本身就是一条会跑的滚动压缩，问渠不再另算一份摘要——两边各算就是两份「已压缩上下文」互相冒充。
     * <p>手动 {@code /compact} 在这一路会话上留过请求时，本次建实例强制「有一条就先压」，
     * 让那次点击真的在下一次提问前把旧对话折进去（框架没有「就地压一次」的入口，压缩长在推理循环前）。
     * <p>此前框架默认阈值另有可配项（chat.agentscopeCompactTriggerMessages，0＝框架默认），默认态
     * 与框架默认逐字相同，属「框架已有限制、参数只留个默认就用不上的改口」，已取消。
     * <p>摘要模型包 MeteredModel（kind=compress）：压缩平时不常跑，但一跑就是一次不小的输入，
     * 不记账就永远不知道它花在哪。
     */
    private io.agentscope.harness.agent.memory.compaction.CompactionConfig compactionConfig(
            ChatCommand cmd, io.agentscope.core.model.Model baseModel, String modelRef) {
        io.agentscope.core.model.Model compressModel = new MeteredModel(baseModel, usageLedger,
                com.wenqu.ai.util.UsageAttr.of(cmd.getUserId(), cmd.getSessionId(), null, "compress"),
                modelRef);
        io.agentscope.harness.agent.memory.compaction.CompactionConfig.Builder b =
                io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder().model(compressModel);
        String key = sessions.key(cmd);
        if (!sessions.takeCompactRequest(key)) {
            return b.build();
        }
        // 强制压缩只应作用「下一次提问」那一个实例：实例按会话常驻，triggerMessages(1) 若不复位会被
        // 这一条会话的后续每一轮继承（每轮都压）。这里标「下次开轮重建」，让下一轮回到默认压缩配置。
        sessions.markRebuildAfterCompact(key);
        return b.triggerMessages(1).build();
    }

    private HarnessAgent buildAgent(ChatCommand cmd, ExecutionPolicy policy,
                                    ModelRegistryService.ModelRoute route,
                                    WenquAgentxTools.TurnHooks hooks, AgentBinding binding,
                                    AgentxTurnSlot slot) {
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
        // 不重放就是检索恒空且不报错（Phase 0 与本刀都实测过这个形状）
        java.util.Set<String> groupedNames = tools.registerWithGroups(toolkit, tools.frameworkTools(cmd, policy, slot, (code, message) -> {
            log.info("[agentx] 降级 {}: {}", code, message);
            // 未接入的能力必须让用户看见：静默少挂一个族会被读成「配了没生效」。
            // 降级出口也按轮走：实例复用后这里的 channel 是建实例那一轮的，必须从槽位现取。
            AgentxTurnSlot.Turn t = slot.current();
            if (t != null) t.channel().emit(RunEventType.WARN, message);
        }, true));
        // 这一轮建了组的名单按会话记下来：每轮的激活集只在这份名单里增删，名单外的组（框架技能带出来的）不碰
        sessions.noteGroupedSurface(sessions.key(cmd), groupedNames);
        AgentxStepBudget stepBudget = new AgentxStepBudget();
        // 实例常驻、闸随实例走，登记到会话表好让引擎在开轮时归零它
        sessions.noteStepBudget(sessions.key(cmd), stepBudget);
        // 框架工具的档位闸（全框架化 P1）：沙箱/文件工具改由框架提供后不再过问渠装配链，
        // 档位裁剪挪到执行前逐个判。策略按轮取（同实例多轮可能不同档）。
        AgentxFrameworkToolGate toolGate = new AgentxFrameworkToolGate(() -> {
            AgentxTurnSlot.Turn t = slot.current();
            return t == null ? policy : t.policy();
        });
        sessions.noteFrameworkToolGate(sessions.key(cmd), toolGate);
        // 子轮只读记忆：框架四把记忆工具打包注册，没有「只摘写」的开关；
        // 这里在执行前拒 memory_save，保留注入与 memory_search/get（只读也读得到）。
        toolGate.denyMemoryWrites(cmd.branchTurn());
        // 主模型只建一次：主对话、记忆提炼、上下文压缩各包一层 MeteredModel（按 kind 分开记账），
        // 底层仍是同一个 OpenAIChatModel。包装器只旁路 usage，不改变请求参数与上下文窗口。
        io.agentscope.core.model.Model baseModel = OpenAIChatModel.builder()
                .baseUrl(route.baseUrl())
                .apiKey(route.apiKey())
                .modelName(route.modelId())
                .stream(true)
                .generateOptions(generateOptions(cmd, hooks.modelRef()))
                .endpointPath(route.completionsPath() == null || route.completionsPath().isBlank()
                        ? "/v1/chat/completions" : route.completionsPath())
                .contextWindowSize(modelContextWindow(cmd))
                .build();
        io.agentscope.core.model.Model chatModel = new MeteredModel(baseModel, usageLedger,
                com.wenqu.ai.util.UsageAttr.of(cmd.getUserId(), cmd.getSessionId(), null,
                        cmd.branchTurn() ? "subagent" : "chat"), hooks.modelRef());
        // 可派遣的助手（派遣由框架的 agent_spawn 驱使）：这一份列表同时决定两件事——
        // 注册进框架的子智能体表（模型手上有没有这把能力），以及提示词里那两句路由纪律（教不教它用）。
        // 分成两处判就会漂移成「教了模型一件它没有的能力」，这条坑本仓点过名。
        List<WenquAgentxTools.SubagentTarget> subagentTargets =
                tools.consultSubagentTargets(cmd, policy, slot);
        HarnessAgent.Builder agentBuilder = HarnessAgent.builder()
                .agentId("wenqu")
                .name("wenqu")
                .description("问渠·AgentScope 运行时")
                // 角色提示词归智能体管（chat.systemPrompt 已退役）：见 rolePrompt 的分层说明。
                // 派遣纪律必须跟着挂：清单本体由框架的 ## Subagents 段渲染，但「id 逐字取自清单、
                // 通用问题不要派」这两句只有问渠说得出（派错一位的成本是一整条子轮）
                // 用户口径那条（正文/计划/清单不写沙盒路径与工具名）也要跟着挂：它原先长在已退役的手写
                // 计划阶段里，框架这一路没人再说第二遍，而框架的 PLAN banner 与沙盒工具说明反而主动把
                // 路径喂给模型——不挂回去，计划卡就变成「它打算在沙盒里怎么写文件」的说明书
                .sysPrompt(rolePrompt(binding.row()) + projectContext(cmd)
                        + (subagentTargets.isEmpty() ? "" : tools.dispatchDiscipline())
                        + PlanCopy.guidance()
                        + com.wenqu.ai.agent.chat.RelatedPrompt.line())
                // 问渠所有供应商都是 OpenAI 兼容网关（c_ai_provider.base_url 实测全是 compatible-mode）。
                // 用户在界面选的上下文窗口（contextWindow 档位）落到 model 的 contextWindowSize：
                // 框架的压缩阈值/输入预算都由它推（CompactionMiddleware: window-reserved；
                // HarnessContextBuilder.inputLimit: window-output-safety），
                // 不设就按 ModelContextWindows.lookup 按模型名查内置表，问渠登记窗口≠框架内置表时
                // 会差一截。0/未登记不设（保持框架按模型名推断），避免把显式 0 塞给模型。
                .model(chatModel)
                .toolkit(toolkit)
                // 框架对「一把工具」有自带的 5 分钟硬超时（TOOL_DEFAULTS）。咨询子轮、沙盒长命令都会撞上去，
                // 而手写运行时没有这一层——不显式设，就等于框架比问答侧多一道没人知道的天花板（实测踩过：
                // 咨询跑满 5 分钟被判工具失败，父轮只收到一句 Tool execution timeout）
                .toolExecutionConfig(io.agentscope.core.model.ExecutionConfig.builder()
                        .timeout(java.time.Duration.ofSeconds(turnSeconds()))
                        .build())
                .maxIters(configService.getInt("chat.agentscopeMaxIters", 12))
                // 计划模式工具面常驻开启：是否进入只由用户显式开计划开关（sessions.setPlanMode）或
                // 模型自己在推理中调用 plan_enter 决定。问渠不再做「按消息意图预判」——PlanModeService
                // 与 chat.planAutoIntent 已删除，计划怎么执行全交框架的 plan_enter/write/exit + 只读拦截。
                // 历史计划卡的多版本落库仍由 Recording.planVersions 负责，与意图判定无关。
                .enablePlanMode()
                // 任务清单：**不开**框架的 enableTaskList，只借它的 state 做存储。
                // 原因：开它会同时注册 todo_write 工具并由 TaskReminderMiddleware 每轮教模型用
                // 「in_progress/completed」这套词，而问渠挂的是 writeTodo（教的是 doing/done，
                // 且带护栏与中文展示名）。两套工具 + 两套状态词同时进模型上下文，它会挑错工具、
                // 或者把 doing 写进 todo_write 被框架判非法。storage 用框架的、tool 面用问渠的，
                // 是这里刻意的分工——框架 AgentState 才是清单的权威真源（见 TodoBridge）。
                // 真要换成框架工具面，得连护栏、中文名、前端契约一起换，不能只开这个开关。
                // 上下文压缩：框架默认就开着（动态 token 阈值或 50 条消息触发、逐字保留最近 20 条）。
                // 用户选的窗口已落到 model.contextWindowSize（见 .model(...) 段），压缩阈值由它推导，
                // 这里不再单独传 maxContextTokens（那是 WorkspaceContextMiddleware 的注入预算，非压缩阈值）。
                // 压缩摘要用独立的 MeteredModel（kind=compress），成本才进得了台账。
                .compaction(compactionConfig(cmd, baseModel, hooks.modelRef()))
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
                // 档位闸必须排在步数闸之后：先判「该不该跑」，再判「还剩几步」，
                // 否则被档位拒绝的那次会白占一步预算（AgentxStepBudget 的「判过即占一步」口径）。
                .middleware(toolGate)
                // 流式可见性闸：框架只在一次模型调用结束时才把 chunk 落盘，而观众跟的是落盘后的日志，
                // 界面上就是「正文一下粘出几百字」。这里把它拆成 200ms 一片（代价见类注释）。
                .middleware(new AgentxStreamFlushMiddleware())
                // Phase 0 实测：框架默认装备子智能体/技能/工作区上下文/tools.json 等旁路能力。
                //
                // 子智能体由**框架驱使**（2026-10-18 全框架化迁移 P0 启用，2026-10-20 收口成一条）：
                // 去掉 disableSubagents / disableDynamicSubagents 后框架装配 SubagentsMiddleware，
                // 注册 agent_spawn / agent_send / agent_list 三把工具，并每轮在 SYSTEM 消息前
                // 插入子智能体使用指引与异步任务摘要（框架自己从「冻结基线」重建 SYSTEM，
                // 内容不会跨轮累积——见 SubagentsMiddleware 类注释第 3 条）。
                // 问渠不再另挂一把咨询工具：每位「可咨询助手」登记成框架的子智能体
                // （见 {@link WenquAgentxTools#consultSubagentTargets}），派遣时机、并行与后台等待
                // 都归框架；子轮本体仍是问渠那条完整问答通路（临时会话、引用回填、产物收编），
                // 因为框架的子 agent 只回一段文本、没有问渠的问答外壳。
                //
                // 技能已改为**框架接管**（2026-10-18，全框架化迁移 P3）：
                // WenquSkillRepository 实现 RuntimeContextSkillRepository，按当轮 uid 解析可见技能
                // （classpath 内置 + 沙盒工作区 + user-data/skills-users/{uid}/，同名用户版优先）。
                // disableDynamicSkills 去掉——那会拦掉框架的动态技能加载。
                // disableDefaultWorkspaceSkills 保留：工作区里的默认技能集与问渠的技能体系无关。
                .skillRepositories(List.of(skillRepository))
                // 智能体的「技能范围」真正生效的地方（全框架化收口）：三态 → SkillFilter。
                // 框架的 HarnessSkillMiddleware 按它裁目录（被滤掉的不进提示词、也不注册 load 工具），
                // 所以「限定用哪几个」是边界而不是建议。空白名单/关闭 = 一条都不给。
                // 注意：只能走 builder —— AgentSession 构造时把 RuntimeContext 复制了一份
                // （AgentSession:84），事后往原 ctx put SkillFilter 影响不到已建实例。
                .skillFilter(binding.skillFilter())
                // 助手侧「技能沉淀」仍交给框架 skill_manage（全框架化 P3）：写沙盒工作区，随会话走。
                // 用户自己的技能安装/编辑/删除走 SkillsPage + SkillController（user-data/skills-users/{uid}，
                // 跨会话、不依赖沙箱）；两套并存，本开关只负责「模型自己起草/改写技能」这条路。
                // autoPromote=true：新技能直接落可加载目录，下一轮推理即可见。
                // 框架注释：默认会先进 drafts 目录「不自动加载」，那是给谨慎场景的默认——
                // 问渠的会话内技能本来就是当轮要用，加 drafts 只是多一步人工提升。
                .enableSkillManageTool(io.agentscope.harness.agent.tool.SkillManageConfig.builder()
                        .autoPromote(true)
                        .build())
                .disableDefaultWorkspaceSkills()
                // 记忆改由框架接管（全框架化 P3）：去掉 disableMemoryTools 后框架注册
                // memory_search / memory_get / memory_save / session_search 四把。
                //
                // 隔离性核对过：`WorkspaceManager#getMemoryDir(rc)` → `resolveRuntimeDataPath(rc, ...)`
                // 按 RuntimeContext 解析，本轮的 uid 不同则记忆目录不同 —— 满足问渠的 per-user 语义。
                //
                // 已接受的降级（换框架的必然结果）：框架 `MemorySearchTool` 是 **keyword 检索**
                // （源码注释：*"Uses keyword-based search through all memory files"*），
                // 问渠原实现是 embedding 向量检索；且用户失去「长期记忆」面板的手动管理入口。
                //
                // disableWorkspaceContext 已去掉（全框架化 P4 修正）：该开关会整条关掉
                // WorkspaceContextMiddleware，而它承担 P3 记忆「读回」的两件事——
                //   ① readMemoryMd(rc) 把 MEMORY.md 注入 HARNESS_CONTEXT（不开则模型看不到已沉淀的记忆）
                //   ② 注入 Memory Recall / Memory Persistence 使用指引（不开则模型不知道
                //      memory_search 四把工具何时用）
                // P3 之前它留着是因为当时记忆/技能还是自研体系（HEAD 注释：关掉框架自带的
                // memory_* 四把工具防第二套记忆）；P3 把记忆换成框架后这一条已不成立，
                // 继续关着 = 记忆只写不读（flush hook 照常跑、注入却断了）。
                // 副作用核对：该 middleware 注入的 ## Workspace 段落会描述沙箱路径，
                // 但问渠有 PlanCopy.guidance()（提示词「正文/计划/清单不写路径与工具名」）
                // + PlanCopy.stripInternalPaths（计划卡兜底剥路径）两层护栏，与它共存无冲突。
                .disableAtPathExpansion()
                .disableToolsConfig()
                // 沙箱与文件系统：**框架接管**（全框架化P1）。
                // 关键：框架的工具在 **build 期**绑定文件系统——
                //   HarnessAgent:2895 `new FilesystemTool(filesystem, pathNormalizer)`
                //   HarnessAgent:2903 `new ShellExecuteTool(sandbox)`（filesystem 当沙箱用）
                // 两者都从 builder 字段取，**不看 RuntimeContext**。
                // 所以此前每轮往 ctx 里 put AbstractFilesystem 是无效动作（真机验证已证：
                // execute("pwd") 返回宿主项目目录）。必须用 abstractFilesystem(...) 在 build 期传进去。
                .abstractFilesystem(frameworkFilesystem(cmd))
                .artifactDeliveryTarget(new com.wenqu.ai.sandbox.WenquArtifactDeliveryTarget(artifactService))
                .disableWebTools();

        // 记忆闸（P5 收口）：三种轮次三种口径，必须在 build 期定——MemoryConfig 烤在常驻实例里。
        // ① 游客/分享/MCP：userId 是发布者（检索可见性按他执行），若不关记忆，发布者的 MEMORY.md
        //    会被注入访客对话，访客的话也会写进发布者日账。两个开关必须同时关：只关 hooks 仍会注入。
        // ② 子智能体/咨询轮：只读不写——保留 MEMORY.md 注入与 memory_* 工具（子轮要用户上下文），
        //    但关掉 flush 与后台合并；否则每派一支就多一次提炼调用，内部推演还会沉淀成用户事实。
        // ③ 正常用户轮：平台/个人开关 + 节流 + 硬上限（见 memoryConfig）。
        if (cmd.isGuestMode()) {
            agentBuilder.disableMemoryHooks().disableMemoryTools();
        } else if (cmd.branchTurn()) {
            agentBuilder.disableMemoryHooks();
        } else {
            agentBuilder.memory(memoryConfig(cmd, baseModel, hooks.modelRef()));
        }
        io.agentscope.harness.agent.DistributedStore distributedStore = distributedStore();
        if (distributedStore != null) {
            agentBuilder.distributedStore(distributedStore);
        }
        // 问渠的助手登记成框架的子智能体（派遣改由框架 agent_spawn 驱使，问渠不再挂 consultAgent）。
        // agent_id 逐字用问渠的智能体 id：卡片归属、台账与提示词里的 id 才是同一个东西。
        // 工厂造出来的实现体不是框架的模型循环，而是「起一条完整问答轮」那条通路（见 WenquConsultSubagent）——
        // 子轮要的是问渠的问答外壳：即时落库、轮级互斥、断线续跑、引用回填、产物收编。
        // 框架自带的 general-purpose 一支照旧存在（dynamic 开着），它派的是通用工作者，与「咨询某位助手」
        // 不是一件事，也不共用编号体系。
        for (WenquAgentxTools.SubagentTarget target : subagentTargets) {
            agentBuilder.subagentFactory(target.agentId(), target.description(), target.factory());
        }
        return agentBuilder.build();
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
        // 配图清单随快照一起给：image 帧是「有新增才发全量」的状态事件，接流方漏不上那一帧就是
        // 整轮没有图位（前端按 images[N-1] 还原，缺数组则 [图片N] 原样留在正文里）
        snap.put("images", pool.signedImages());
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
        // 推荐块两道护栏，都住在 RelatedStreamFilter 里：条目数超过要的那 N 条＝整块是被塞进来的正文
        // （全数并回、一颗芯片不出），否则只并回长过正文量级的那几条。真机 2026-10-18 21:00 那轮模型把
        // 摘要骨架整块写进 {@code <related>}，71 条短行条条过单条长度闸，界面上是一整屏绿芯片。
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
        // 本轮正文配图清单：正文里的 [图片N] 就是按这份的下标还原的（N → 第 N-1 项）。
        // 它此前只活在 SSE image 帧里、从不落库，表现是「当场看得见图，一刷新全变成 [图片1] 四个字」。
        List<String> turnImages = pool.rawImages();
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
            // 自检剔图后编号是重编过的（[图片3] 可能已变成 [图片2]）：清单必须换成 outcome 那一份，
            // 否则落库/下发的是剔除前的顺序，正文第 N 个标记会配到另一张图
            turnImages = outcome.images();
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
        // 整轮耗时随消息行落库：它此前只活在 done 那一帧，刷新一次「这一轮跑了多久」就读不出来了——
        // 而界面已经改成只在气泡顶部报这一个数（原先每张卡各挂一个），落不了库就等于刷新即空白。
        // 两份都要：机器时间对得上看板与台账，等待那份让前端能拼回墙钟（屏幕上跳着的是墙钟，
        // 收尾只报机器时间会让数字在答完那一刻变小），并在悬停里说清等了多久。
        // humanWaitMs 只在真等过的时候写：为 0 时不占键位。
        tokens.put("turnElapsedMs", elapsedMs);
        if (humanWaitMs > 0L) tokens.put("humanWaitMs", humanWaitMs);
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
                body, turnImages,
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
        // 载荷改为 JSONObject 序列化，**不再手工拼字符串**。
        // 原先按 "..." + json(x) 手工拼接，json() 只转义 \\ " \n \r \t——其余控制字符与非法转义
        // 序列会让整包失去合法性。真机实测两轮 MCP 调用都命中：报 "illegal input" 且偏移正好落在
        // 字符串末尾，危害是**本轮来源清单与外发文本一起丢掉**，「答案可溯源」在外部链路静默失效。
        // 键序与拼接版逐字一致（下游有多个消费方按名取字段，含前端与子轮回取）。
        // WriteMapNullValue 保持「显式给 null」的旧语义（原先 json(null) 产出空串，缺键会让前端读不到）。
        com.alibaba.fastjson2.JSONObject done = new com.alibaba.fastjson2.JSONObject();
        done.put("finalContent", body);
        done.put("messageId", assistantMessageId);
        done.put("userMessageId", userMessageId);
        done.put("turnId", framedTurnId);
        done.put("model", route.modelId());
        // 作答方归属随 done 一起回：这两个键本来就落在消息行上（下面 saveMessage 同一对值），
        // 此前只有刷新那条路给得出——「由「X」回答」实时看不见、刷一下才冒出来。前端 done 分支
        // 一直在读 p.agentName，缺的是这一侧，不是那边
        done.put("agentId", cmd.getAgentId());
        done.put("agentName", agentNameOf(cmd));
        done.put("sources", pool.sources());
        // 定稿配图清单（与 finalContent 里那套 [图片N] 编号严格同序）：流式期间的 image 帧是
        // 剔图重编之前那一份，不覆盖就会让正文第 N 个标记配到另一张图。
        // 被咨询那支也按这个字段回取子轮图片（SubAgentBranchRuntime 剥签名后续编父轮编号）
        done.put("finalImages", pool.sign(turnImages));
        done.put("degradations", pool.degradations());
        done.put("elapsedMs", elapsedMs);
        done.put("humanWaitMs", humanWaitMs);
        done.put("tokens", tokens);
        done.put("toolCalls", recording.toolCallList());
        done.put("artifacts", artifactsJson == null ? List.of()
                : com.alibaba.fastjson2.JSON.parseArray(artifactsJson));
        done.put("related", relatedChips);
        done.put("thinking", recording.thinking.length() == 0 ? null : recording.thinking.toString());
        if (planRecord != null && "superseded".equals(planRecord.get("status"))) {
            done.put("planSuperseded", true);
        }
        if (planRecord != null && "rejected".equals(planRecord.get("status"))) {
            done.put("planCancelled", true);
        }
        if (planRecord != null) done.put("planRecord", planRecord);
        // 「说过但模型没收到」的插话必须随每一条 done 交回前端补发——不许静默丢话是这条机制的底线。
        // 判据用框架自己留的账：插话在下一次调模型前被写进会话上下文并记一条 inbox/applied。
        // 会没收上的只有三种收尾——撞了步数上限（框架那时直接走 summarizing，不再应用插话）、被叫停、
        // 以及跟随循环撞到墙钟上限退出后那一跑没走到 completed。
        // awaiting（卡片还开着、这一轮只是暂时没人跟）那一支不报：用户答完之后框架那一跑接着走，
        // 下一次调模型前照样把这句应用上；在这里交回前端等于替用户又问一遍，还会撞上后台那一跑。
        if (!awaiting) {
            List<Map<String, Object>> unsentSteers = unsentSteers(handle, turnId, recording);
            if (!unsentSteers.isEmpty()) done.put("pendingSteers", unsentSteers);
        }
        // BrowserCompatible：把尖括号/引号/非 ASCII 一并输出成 Unicode 转义序列。这不是为了浏览器，
        // 而是**结构性免疫**——凡是能漏进载荷的字符都会被规范化成转义序列，不存在"某个字段忘了
        // 转义控制字符"这种事。真机为此付过代价：thinking 文本里的 U+001A 让整包 JSON 非法，
        // 而它在终端里不可见，排查绕了三轮（U+001A）。
        String payload = com.alibaba.fastjson2.JSON.toJSONString(done,
                com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue,
                com.alibaba.fastjson2.JSONWriter.Feature.BrowserCompatible);
        channel.emit(RunEventType.DONE, payload);
        // done 之后必须收通道：手写侧是 runChat 自己 completeEmitter，框架引擎原先漏了这一步，
        // 表现是帧都到了、连接却挂着直到看门狗 300s 超时截断（真机实测：done 在 01:20:32，
        // 通道 01:25:20 才被判超时），前端「这一轮结束」要等五分钟才落地
        channel.complete();
    }

    /**
     * 本轮送进过框架会话、但<b>没被写进上下文</b>的插话（要交回前端补发的那几条）。
     * <p>按把手取：收据与落库的那条用户消息同行，都在本轮的把手上；把手已经换成别的轮（新轮已经开始、
     * 或已经被摘掉）时一条都不报——把上一轮的插话挂到这一轮的 done 上，前端会把它再发一遍。
     * <p>只在「这一跑真的结束了」之后认账（{@code run/end}）：墙钟到点退出的那一支里框架那一跑还在
     * 后台继续，插话照样会在它下一次调模型前被应用——在这里交回前端等于让用户同一句话讲两遍。
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
     * 200ms 一跳空转会把跟随线程烧在数 token 上。变化本身由 {@link TurnMirror} 从事件上认，
     * 因为「问它变了几条」要先做一次全量读——那比数 token 还贵。发不出去只是容量圆环晚一帧，不该带走这一轮的答案，
     * 所以异常只告警不上抛。
     */
    private void emitLiveUsage(AgentSession session, ChatCommand cmd, WenquSourcePool pool,
                               RunChannel channel, int historyBefore, long[] stamp, boolean transcriptChanged) {
        // 上下文没添新行就不必读全量日志去问「添了几条」：transcript() 走的是从 head 回走的整条 commit 链
        if (!transcriptChanged) return;
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
     * <p>上下文预算（{@code budget}）框架不给：窗口值（{@code modelContextWindow}）是「模型允许的
     * 上下文上限」，不是问渠「给这一轮留了多少」的预算，报上去会让右栏占用条按一个没人承诺过的数
     * 算百分比。前端对缺字段是「不显示该行」，不填比填个假预算诚实。
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
        int window = modelContextWindow(cmd);
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
     * 回退那一份正文的收尾处理：它出自框架的 transcript，从没进过流式剥离器（{@code model/chunk}
     * 的增量才是它的正常入口），所以标签得在这儿剥掉。
     * <p>不剥的表现为两件事同时发生：{@code <related>…</related>} 原文印进回答，而「接下来可以」
     * 那排芯片一个也没有——芯片正是从这块里拆出来的（真机 2026-10-18 20:20 那轮）。
     * <p>过同一个 {@link Recording#related} 而不是在这里补一条正则：推荐条数、「模型把正文写进
     * 推荐块时并回回答」那道护栏、以及 {@code <process>} 分流都住在它身上，同一件事不该有第二份口径。
     */
    private static String stripFallbackBody(Recording recording, String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // 只取正文、不补发 token 帧：done 的 finalContent 就在下一毫秒，重放一遍等于把同一截话发两次
        return recording.related.feed(raw) + recording.related.finish();
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
                    // 真机实测（2026-10-16）：模型思考文本里出现 U+001A（SUB），上面那个 esc() 放它
                    // 过去，整份 done 载荷变成非法 JSON → MCP 取不到来源清单 → 「答案可溯源」在外部
                    // 链路静默失效。报错是 `illegal input, offset <len>, char \x1a`，而这个字符在
                    // 终端与文本工具里根本显示不出来，定位成本极高。
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
