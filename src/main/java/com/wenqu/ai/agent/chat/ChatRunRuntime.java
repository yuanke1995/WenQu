package com.wenqu.ai.agent.chat;

// [保留] 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件，勿当作已退役的控制流删除。

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.service.ChatRunBus;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.SessionEventService;
import com.wenqu.ai.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轮次控制与存活（P6 刀 2，自 {@code ChatService} 抽出）：一条问答轮从「占住会话」到「通道收摊」
 * 的全部生命周期都在这里——SSE 投递与断开跟踪、事件账本留痕、运行总线的接流与宽限中止、
 * 停止本轮 / 运行中插话 / 探测接流三个入口、整轮心跳与存活看门狗。
 * <p><b>它不知道流水线</b>：正文怎么检索、上下文怎么拼、模型怎么调，一概在 {@code ChatService}。
 * 反过来那边要发一个事件、要问一句「这轮还活着吗」、要叫停，都到这里来。这条边界是刀 2 的全部意义：
 * 原先这两件事挤在同一个 8700 行的类里，于是「通道断了该不该掐本轮」这种判断只能靠读全文才能确定
 * 影响到哪一段。
 * <p>{@code chat()} 的入口仍留在问答侧（它要提交流水线），会话互斥、通道登记、宽限中止这三件事
 * 经下面四个小闸口（{@link #tryBeginTurn} / {@link #endTurnMutex} / {@link #trackChannel} /
 * {@link #takeBareAbort}）在这里落地——登记表不外露，进出都得走名字。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ChatRunRuntime {

    private final ConfigService configService;
    private final ChatRunBus chatRunBus;
    private final SessionEventService sessionEventService;
    private final SessionService sessionService;

    public ChatRunRuntime(ConfigService configService,
                          ChatRunBus chatRunBus,
                          SessionEventService sessionEventService,
                          SessionService sessionService) {
        this.configService = configService;
        this.chatRunBus = chatRunBus;
        this.sessionEventService = sessionEventService;
        this.sessionService = sessionService;
    }

    // ==================== 给 chat() 入口用的四个闸口 ====================

    /**
     * 占住会话轮级互斥。同一会话同时只允许一轮在跑：并发两问会历史交错、产物推送互相覆盖
     * （后轮覆盖前轮、先完成者注销掉后轮的推送）、首问智能体绑定竞态。断线后台续跑与 askUser
     * 等待期同样占坑——等待作答时再发新消息正是要挡的那种交错。
     *
     * @return true=占到了；false=上一轮还在跑（调用方按「上一轮还在回答中」回错）
     */
    public boolean tryBeginTurn(String sessionId) {
        return TURN_IN_FLIGHT.putIfAbsent(sessionId, Boolean.TRUE) == null;
    }

    /** 放开会话互斥（流水线线程走到底时调；被拒的请求没占到坑，也就不调） */
    public void endTurnMutex(String sessionId) {
        TURN_IN_FLIGHT.remove(sessionId);
    }

    /** 登记通道的断开标记（发送失败与生命周期回调都改它，各等待点据此短路后续 LLM/检索开销） */
    public void trackChannel(SseEmitter emitter) {
        ACTIVE_SSE.put(emitter, new AtomicBoolean());
    }

    /**
     * 摘掉「被宽限中止」的登记并告诉调用方：这一问是不是真的被那条路掐掉的。
     * <p>摘的动作放在这里，是因为判「这一问有没有产生回答」只能由流水线线程走到到底时做——
     * 早一步会抢在落库之前，晚一步那个线程已经出不来了。
     */
    public boolean takeBareAbort(String sessionId, String turnId) {
        return BARE_ABORTS.remove(sessionId, turnId);
    }

    /**
     * fail-loud：按 code 去重添加降级事件（全部 debug 级，由 chat.retrievalDebugEnabled 开关控制，默认不展示）。
     * <p>并行工具调用会同时走到这里（典型：同一轮 4 路 search_knowledge 各自发现重排不可用）：
     * {@code Set.add} 与 {@code List.add} 都必须串行，否则两个普通集合会被并发写坏。
     * 锁放在 {@code codes} 上，与 {@code WenquSourcePool} 读取快照用的是同一把。
     */
    public void addDegradation(List<Map<String, String>> list, Set<String> codes, String code, String msg) {
        synchronized (codes) {
            if (!codes.add(code)) return;
            if (configService.getBoolean("chat.retrievalDebugEnabled")) {
                list.add(Map.of("code", code, "msg", msg, "level", "debug"));
            }
        }
    }

    /**
     * 服务端停止本轮：按会话找到正在跑的轮次，置把手标志 + 关通道，让流水线线程在下一个检查点退出
     * 并释放会话轮级互斥（下一问马上能发，不必等审批/提问超时）。
     * <p>不依赖 SSE 通道是否还在：前端 abort 只断开连接，通道断开在「正等人作答」的语义下是
     * <b>继续后台跑完</b>，那不是用户按下停止想要的意思；换设备打开同一会话时也没有通道可断。
     * 故单独一条按 sessionId 的服务端路径。
     * <p>正在执行中的那一个工具调用无法半途掐死（沙盒命令已在容器里跑），它在下一步闸口被拦住。
     * <p>归属只认发起本轮的那个 uid：只认把手（{@link TurnRegistry#handle}），别人停不动。
     *
     * @return true=确实停掉了一轮；false=该会话没有在跑的轮，或这不是本人的轮（越权 fail-loud 拒绝）
     */
    public boolean stopTurn(String sessionId, String userId) {
        TurnHandle h = sessionId == null ? null : TurnRegistry.handle(sessionId);
        if (h == null || userId == null || !userId.equals(h.userId())) return false;
        h.stopped().set(true);
        sessionEventService.append(sessionId, h.userId(), h.turnId(), "stop", "用户停止本轮");
        completeEmitter(h.emitter());
        // 不在这里给那一问打终止标记：本轮此刻还没真正退场（引擎线程要等下一个检查点才断），
        // 此刻判「有没有回答」会抢在落库之前。标记统一由 chat 流水线 finally 打（见该方法注释）。
        log.info("[SSE] 用户停止本轮: session={}", sessionId);
        return true;
    }

    /** 心跳间隔 15s：小于常见中间代理 60s 读超时与前端 120s 空闲看门狗，留足余量。 */
    private static final long TOOL_HEARTBEAT_INTERVAL_MS = 15_000;

    /** SSE 心跳调度池（2 线程，守护）：仅供 15s keepalive 注释行下发。与看门狗分池——心跳是
     *  高频周期任务（百级并发 = 每 15s 约两百次 send），看门狗是整轮截断的安全网：同队时一个
     *  慢客户端卡住的 send 会连看门狗一起停摆，全平台的截断机制失效 */
    private static final java.util.concurrent.ScheduledExecutorService TOOL_HEARTBEAT_POOL =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sse-keepalive");
                t.setDaemon(true);
                return t;
            });

    /** 整轮存活看门狗（独立单线程，与心跳分池见上）：检查本身无 IO。到点的截断收尾虽有阻塞
     *  send，但失联客户端快速抛错、健康客户端 send 极短，独占线程不与两百次/15s 的心跳互相放大 */
    private static final java.util.concurrent.ScheduledExecutorService TURN_WATCHDOG_POOL =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "turn-watchdog");
                t.setDaemon(true);
                return t;
            });

    /**
     * 按 15s 间隔向 emitter 发 SSE 注释行（":keepalive"）保活，返回句柄供停止/移交。
     * 发送失败（客户端已断开）自停，防任务泄漏。供 startRunHeartbeat 与 runChat 前置阶段共用。
     */
    public static java.util.concurrent.ScheduledFuture<?> scheduleKeepalive(SseEmitter emitter, String phase) {
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> self =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ScheduledFuture<?> f = TOOL_HEARTBEAT_POOL.scheduleWithFixedDelay(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                log.debug("[RUN-HEARTBEAT] {}心跳下发失败（客户端可能已断开），心跳自停: {}", phase, e.getMessage());
                java.util.concurrent.ScheduledFuture<?> s = self.get();
                if (s != null) s.cancel(false);
            }
        }, TOOL_HEARTBEAT_INTERVAL_MS, TOOL_HEARTBEAT_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        self.set(f);
        return f;
    }

    /**
     * 整轮流级 SSE 心跳：覆盖「工具执行（沙盒命令/审批等待可达数分钟）」与「工具结束→
     * 最终回答首 token」两段全静默区——中间代理（vite/nginx 默认 60s 读超时）会掐断连接、
     * 前端 120s 空闲看门狗会把静默误判为失联。每 15s 发一条 SSE 注释行（":keepalive"）：
     * 中间层与看门狗都视之为存活信号，前端解析器忽略注释行、UI 零打扰。
     * <p>各运行时的静默段自行起停（今天框架引擎未接这条心跳，见清理记录）；发送失败自停，防任务泄漏。
     */
    public static java.util.concurrent.ScheduledFuture<?> startRunHeartbeat(SseEmitter emitter) {
        return scheduleKeepalive(emitter, "整轮");
    }

    /** 整轮机器耗时预算（chat.sseTimeoutMs，默认 5 分钟） */
    public long turnMachineBudgetMs() {
        long t = configService.getLong("chat.sseTimeoutMs");
        return t > 0 ? t : 300000L;
    }

    /**
     * 整轮存活看门狗：每 {@link #TOOL_HEARTBEAT_INTERVAL_MS} 检查一次「墙钟 − 人工等待」是否越过预算。
     * 到点先发 warn（此刻 emitter 还活着，前端气泡真能看到「回答超时已截断」），再 dispose 停流
     * （半程正文按截断态落库）并 complete。失联兜底另有 15s 心跳、前端 120s 空闲看门狗与各阶段自身
     * 超时，本看门狗只管「整轮做机器工作太久」。
     */
    public java.util.concurrent.ScheduledFuture<?> startTurnWatchdog(SseEmitter emitter, String sessionId,
                                                                      TurnDeadline d) {
        java.util.concurrent.ScheduledFuture<?> f = TURN_WATCHDOG_POOL.scheduleWithFixedDelay(() -> {
            long machineMs = d.machineMs();
            if (machineMs <= d.budgetMs) return;
            if (!d.fired.compareAndSet(false, true)) return;
            log.warn("[FAIL-LOUD] 本轮问答超时截断: session={} 机器耗时 {}ms / 预算 {}ms（人工等待 {}ms 不计入）",
                    sessionId, machineMs, d.budgetMs, d.humanWaitMs.get());
            try {
                sendSseEvent(emitter, "warn", "回答超时已截断，请重试或缩短问题", sessionId);
                completeEmitter(emitter);
            } finally {
                TurnRegistry.stopDeadline(emitter);
            }
        }, TOOL_HEARTBEAT_INTERVAL_MS, TOOL_HEARTBEAT_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        d.watchdog = f;
        return f;
    }

    /**
     * 通道生命周期回调（onCompletion/onTimeout/onError）：清断开登记并回收整轮台账。
     * <p>例外：这一轮还挂在运行总线上（宽限期内可能随时有人接流回来）时，
     * <b>必须留着台账与看门狗</b>——那是后台轮唯一的机器耗时上界，随通道一起回收等于放它无界跑下去。
     */
    public void releaseSseChannel(SseEmitter emitter, TurnDeadline deadline, String sessionId) {
        ACTIVE_SSE.remove(emitter);
        // 通道走完：从本轮的观众里摘掉（总线语义与 ACTIVE_SSE 的区别就在这——这里只少了一个观众，
        // 本轮该继续跑；观众走光多久之后才掐，由 chat.detachGraceMs 决定）
        chatRunBus.drop(emitter);
        if (deadline != null && chatRunBus.holdsRun(sessionId)) {
            log.info("[ASK] 客户端断开但本轮仍挂在运行总线上（后台续跑/宽限期接流），整轮台账与看门狗保留: session={}",
                    sessionId);
            return;
        }
        TurnRegistry.stopDeadline(emitter);
    }

    /** 活跃 SSE 通道的断开标记（emitter → 已断开）。仅内部查表用；发送失败即打标、完成回调即移除，不会长期滞留 */
    private static final java.util.concurrent.ConcurrentHashMap<SseEmitter, java.util.concurrent.atomic.AtomicBoolean> ACTIVE_SSE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 会话轮级互斥标记：sessionId → 在跑。随轮进出（chat 入口/流水线 finally），进程重启即清空
     *  （与挂起恢复管道同为内存态，语义一致） */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> TURN_IN_FLIGHT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 本轮 turnId（消息落库时挂的关联键）：取自 {@code chat()} 入口登记的把手。
     * <p>把手已随终态摘除时返回 null——这条消息就没有过程可回放，前端按「本行无过程记录」处理，
     * 不去猜一个别的轮的 id（猜错是比缺失更坏的账本）。
     */
    public String turnIdOf(String sessionId) {
        TurnHandle h = sessionId == null ? null : TurnRegistry.handle(sessionId);
        return h == null ? null : h.turnId();
    }

    /**
     * 被「断线宽限到点」掐掉的轮次登记（sessionId → turnId）。
     * <p>为什么单记一笔：中止发生在总线线程上，那一刻把手随即被摘掉，
     * 而「这一问没产生回答」的标记要由流水线线程走到到底时才打（早一步会抢在落库之前，见 {@code chat()}
     * 那段注释），届时 {@code turnStopRequested} 已经查不到把手了。没有这一笔，用户回来只会看到
     * 一句没有任何交代的提问。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> BARE_ABORTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 运行中插话（steer）：用户在本轮还在跑时补一句方向性意见，交给<b>框架会话自己</b>送进正在跑的那一轮。
     * <p><b>为什么必须走框架</b>：插话的送达点在框架自己的推理循环里——它每次调模型前把待送达的插话写进
     * 会话上下文（{@code ReActAgent#reasoning} → {@code SessionExecution#beforeStep}），并且
     * <b>本轮没读到这句就不收尾</b>。问渠原先那条「附在下一个工具结果尾部」的路依赖自己的守卫链，
     * 而框架引擎的模型直调工具不经过那条链：真机结果是这句话只落进历史、模型一个字没收到，收尾也不会
     * 交回前端（{@code docs/chat-turn-pipeline.md} 遗留下第 7 条记的正是这件事）。
     * <p>做不到的是「掐掉正在执行的那一步」——那由框架持有，它自己也是协作式中断。所以插话生效于
     * <b>下一次模型调用之前</b>，不是当场改写已在飞的那一次。
     * <p><b>落库为一条普通用户消息</b>：用户按了发送、话是自己说的，界面与历史里它就该长成用户消息
     * 的样子（前端据此推一条气泡；刷新后由历史接口按同一个 ID 回显同一条）。不引入任何标记列——
     * 它不需要"身份"，它本来就是一条用户消息。顺序是<b>先送框架、后落库</b>：框架不收（这一轮已经
     * 不在跑）就一个字都不写，界面上不会多出一条模型从来没见过的用户消息。
     *
     * @return 落库后的消息 ID（前端挂到那条气泡上，刷新后同一条）；没有在跑的轮 / 越权 / 框架拒收返回 null
     */
    public String steerTurn(String sessionId, String userId, String text) {
        TurnHandle h = sessionId == null ? null : TurnRegistry.handle(sessionId);
        if (h == null || text == null || text.isBlank() || h.stopped().get()) return null;
        if (userId == null || !userId.equals(h.userId())) {
            log.warn("[FAIL-LOUD] 插话被拒（非本人轮次）: session={} uid={}", sessionId, userId);
            return null;
        }
        String clean = text.strip();
        String capped = clean.length() > STEER_MAX_CHARS ? clean.substring(0, STEER_MAX_CHARS) + "…" : clean;
        SteerDelivery d = steerDelivery;
        if (d == null) return null;
        // 送进去的那一份带身份声明，落库与界面给的那一份是用户原话：声明是给模型看的，不是给用户看的
        String receiptId = d.deliver(userId, sessionId, ChatCommand.decorateSteerQuestion(capped));
        if (receiptId == null) {
            log.info("[STEER] 插话未被接收（这一轮不在执行中）: session={} 字数={}", sessionId, capped.length());
            return null;
        }
        String messageId = sessionService.appendMessage(sessionId, "user", capped, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, h.turnId());
        h.steerAcks().add(new TurnHandle.SteerAck(receiptId, capped, messageId));
        sessionEventService.append(sessionId, h.userId(), h.turnId(), "steer", capped);
        log.info("[STEER] 插话已送进框架会话（下一次模型调用前生效）: session={} 字数={} msgId={} receipt={}",
                sessionId, capped.length(), messageId, receiptId);
        return messageId;
    }

    /**
     * 插话的送达口，由框架引擎在装配期登记（见 {@code AgentxConfiguration}）。
     * <p>为什么不直接在这里调框架的会话：那条通道归 {@code agentx}，而本类归 {@code agent.chat}，
     * 引擎已经反向依赖这里（{@code ChatCommand}/{@code TurnRegistry}）。与 {@code bindResume} 同一个
     * 做法——按登记方向留口，不在两处各写一遍送达逻辑。
     *
     * @return 框架的收据号；null=这一轮不收插话（没在跑、已结算、租约过期）
     */
    public interface SteerDelivery {
        String deliver(String userId, String sessionId, String modelText);
    }

    private volatile SteerDelivery steerDelivery;

    public void bindSteer(SteerDelivery delivery) {
        this.steerDelivery = delivery;
    }

    /** 单条插话字数上限（超出按普通消息发更合适：那句话已经长过一句"方向"该有的体量） */
    private static final int STEER_MAX_CHARS = 800;

    /**
     * 本轮是否已被用户叫停。标志在把手上（{@link TurnHandle#stopped()}）：从轮级互斥抢到那一刻起，
     * 任何阶段（检索/思考/生成/等人裁决）都认同一个标志——所以它独立于任何流式结构存在。
     */
    public static boolean turnStopRequested(String sessionId) {
        TurnHandle h = sessionId == null ? null : TurnRegistry.handle(sessionId);
        return h != null && h.stopped().get();
    }

    /**
     * 本轮还要不要往下跑：客户端已断开，或用户已叫停。
     * <p>两者都必须一起判——{@code complete()} 会走 onCompletion 把断开标记整条摘掉，
     * 只看 {@link #clientDisconnected} 的话，被服务端叫停的轮会穿过检查点继续去检索、生成。
     */
    public boolean turnAborted(String sessionId, SseEmitter emitter) {
        return clientDisconnected(emitter) || turnStopRequested(sessionId);
    }

    /**
     * 会话事件账本收录的「结构性事件」白名单（其余 SSE 类型不落账本）。
     * <p>刻意不含 {@code done}：轮终态快照本就随助手消息落库（正文/时间线/工具列表/用量），
     * 账本再存一份只是把大 JSON 截断成半截文本，没有增量信息。也不含 token/thinking/process/
     * stage/image/plan_delta/keepalive 这类高频增量——它们会让账本行数淹没真正的执行轨迹。
     * （stage 通道已整体退役：一轮的进行中提示只剩「整轮用时」那一条。）
     */
    private static final java.util.Set<String> LEDGER_SSE_TYPES = java.util.Set.of(
            "approval_required", "ask_user", "plan", "plan_approval", "plan_cancelled", "plan_superseded",
            "artifact", "agent_delegated", "agent_consulted",
            "retrieved", "usage", "error", "warn");

    /** 客户端是否已断开（供各等待点短路，避免断开后继续跑 LLM 调用与检索） */
    public static boolean clientDisconnected(SseEmitter emitter) {
        java.util.concurrent.atomic.AtomicBoolean dead = ACTIVE_SSE.get(emitter);
        return dead != null && dead.get();
    }

    /**
     * 清除某通道的断开标记登记。**无客户端的收集型通道**（定时执行智能体用的 CollectingSseEmitter）
     * 用完必须主动调用：它不会触发 onCompletion（没有真实响应可完成），否则 ACTIVE_SSE 与整轮台账
     * 会一直持有该 emitter 引用不放（看门狗也停不下来）。
     */
    public static void forgetSseChannel(SseEmitter emitter) {
        if (emitter != null) {
            ACTIVE_SSE.remove(emitter);
            TurnRegistry.stopDeadline(emitter);
        }
    }

    /**
     * SSE 发送。返回 false = 通道已不可用（客户端断开，或 emitter 已完成后仍在发送）；
     * 失败只记日志不抛错（fail-loud），调用方在关键发送点按返回值短路后续 LLM/检索开销。
     */
    public boolean sendSseEvent(SseEmitter emitter, String type, String content, String sessionId) {
        // 账本按「事件是否发生」记，不按「是否送达」记：断线转后台续跑的那一轮，恰恰是最需要事后回放的
        // 过程（卡片、编排、裁决都在那一刻之后产生，而通道已经没了）。只收结构性事件
        // （{@link #LEDGER_SSE_TYPES}）——token/thinking/plan_delta 这类增量与心跳没有超出消息表的信息量。
        if (LEDGER_SSE_TYPES.contains(type)) {
            TurnHandle handle = sessionId == null ? null : TurnRegistry.handle(sessionId);
            sessionEventService.append(sessionId, handle == null ? null : handle.userId(),
                    handle == null ? null : handle.turnId(), type, content);
        }
        // 运行总线接管（本轮已登记且未终结）：事件按「这一轮」扇给此刻挂着的所有通道，没人看也照发。
        // 返回 true 的含义是「本轮还活着，别把没人看当成客户端断开」——各发送点那条
        // `if (!sendSseEvent(...) && !keepRunningWithoutChannel()) disposeSafe()` 兜底，因此不会在用户
        // 刷新页面的那一刻掐掉正在出字的轮；真正的中止由总线按宽限期回调统一做（见 abortTurnWithoutViewer）。
        if (chatRunBus.publish(emitter, type, content, sessionId, textDeltaPos(emitter, type, sessionId))) {
            return true;
        }
        java.util.concurrent.atomic.AtomicBoolean dead = ACTIVE_SSE.get(emitter);
        if (dead != null && dead.get()) return false;
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data("{\"type\":\"" + type + "\",\"content\":" +
                            JSON.toJSONString(content) +
                            ",\"sessionId\":\"" + sessionId + "\"}"));
            return true;
        } catch (IOException e) {
            markSseDead(emitter);
            log.warn("[FAIL-LOUD] SSE 客户端断开 (type={}, session={}): {}", type, sessionId, e.getMessage());
            return false;
        } catch (IllegalStateException e) {
            // "ResponseBodyEmitter has already completed"：终态/截断之后仍有发送（如迟到的流增量）。
            // 必须在这里咽掉——异常冒出会走 DispatcherServlet 的异步派发错误分支，
            // 全局异常处理器再往 text/event-stream 里写 ResultJson，必然二次失败并刷屏。
            markSseDead(emitter);
            log.debug("[SSE] 通道已关闭，事件丢弃 (type={}, session={}): {}", type, sessionId, e.getMessage());
            return false;
        }
    }

    private static void markSseDead(SseEmitter emitter) {
        ACTIVE_SSE.computeIfAbsent(emitter,
                k -> new java.util.concurrent.atomic.AtomicBoolean()).set(true);
    }

    /**
     * 把这一轮的权威快照供给换成引擎那份（正文/工具卡/时间线只有跑出它的那条运行时看得见）。
     * 本轮没挂在总线上时是空操作，见 {@code ChatRunBus#offerSnapshot}。
     */
    public void offerSnapshot(String sessionId, java.util.function.Supplier<Map<String, Object>> snapshotSource) {
        chatRunBus.offerSnapshot(sessionId, snapshotSource);
    }

    public void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 文本型增量的<b>绝对下标</b>：应用这条增量之后本轮权威缓冲有多长。
     * <p>接流的观众先拿快照再接增量，而「正文已进缓冲、事件号还没推进」那一条恰好可能被快照覆盖一次
     * ——重复的那片字由前端按 pos 丢弃（pos 不大于本地已有长度即已存在）。只有 token/process 这两类
     * 逐字增量需要它；结构性事件靠快照重建，done 又是权威全文，缺口在那一刻归零。
     * <p>框架运行时的正文权威缓冲在引擎侧（AgentScope 会话状态），问渠这一侧没有可对账的缓冲，
     * 一律给 null——前端对 null pos 的帧按「无下标」处理（增量仍可追加，仅失去这一层去重）。
     */
    private Integer textDeltaPos(SseEmitter emitter, String type, String sessionId) {
        return null;
    }

    /**
     * 探测：这个会话是否有一轮还在跑。前端切会话时问一句，有才值得开一条接流通道。
     * <p>归属只认发起本轮的那个 uid：查不到 ≠ 不存在，而是「不归你看」——别人的会话不在跑这件事也不告诉你。
     * <p>判据与快照同源：框架引擎同步跑完整轮，run 存在即真在跑（落库收尾到总线关闭之间那一拍
     * 由总线的 finish 时序兜底，见 {@code ChatService} 分派点注释）。
     */
    public Map<String, Object> runStatus(String sessionId, String userId) {
        ChatRunBus.Run run = chatRunBus.running(sessionId);
        boolean mine = run != null && userId != null && userId.equals(run.userId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", mine);
        if (mine) {
            out.put("turnId", run.turnId());
            out.put("beganAt", run.beganAt());
            out.put("viewers", run.viewers());
        }
        return out;
    }

    /**
     * 接流：把一条新通道挂到该会话正在跑的那一轮上。
     * <p>先送一份权威快照（已产出的正文/过程独白/工具卡片/时间线/引用/产物/计划/清单 + 总线镜像的阶段文案），
     * 此后只收快照之后的增量——与「一直看着这一轮」的观感等价，缺的那段是「没看见前面逐字流出来」，
     * 而不是「前面那段没了」。本轮已收尾、不属于本人、或没在跑（含服务重启后）一律 {@code running:false}
     * 后立即关闭，前端按历史回显即可。
     */
    public void resumeTurn(String sessionId, String userId, SseEmitter emitter) {
        ChatRunBus.Run run = chatRunBus.running(sessionId);
        if (run == null || userId == null || !userId.equals(run.userId())) {
            sendFrame(emitter, "snapshot", JSON.toJSONString(Map.of("running", false)), sessionId);
            completeEmitter(emitter);
            return;
        }
        // 这条通道走完就摘掉订阅（本轮该继续跑还是继续跑，只是少了一个观众）
        emitter.onCompletion(() -> chatRunBus.drop(emitter));
        emitter.onTimeout(() -> chatRunBus.drop(emitter));
        emitter.onError(t -> chatRunBus.drop(emitter));
        Map<String, Object> snapshot = chatRunBus.attach(run, emitter);
        if (!sendFrame(emitter, "snapshot", JSON.toJSONString(snapshot), sessionId, snapshot.get("seq"))) {
            chatRunBus.drop(emitter);
            completeEmitter(emitter);
            return;
        }
    }

    /**
     * 宽限到点：没人看的那一轮按<b>中断</b>收束（不是用户停止——账本与终止文案都得如实区分）。
     * <p>框架引擎同步跑完整轮，中止靠把手标志 + 关通道：引擎在下一个检查点见到 stopped 即退出，
     * 半程内容由引擎自己的收尾路径落库（终止标记由 chat 流水线 finally 打）。
     *
     * @return true=本轮已终结（总线随之关闭）；false=该会话没有在跑的轮
     */
    public boolean abortTurnWithoutViewer(String sessionId) {
        TurnHandle h = TurnRegistry.handle(sessionId);
        if (h == null) return true;   // 本轮早没了，总线直接关
        // 记一笔，让流水线线程走到到底时写「已中断」而不是「已停止」
        BARE_ABORTS.put(sessionId, h.turnId());
        h.stopped().set(true);
        completeEmitter(h.emitter());
        return true;
    }

    /** 直接往一条通道送一帧（不经总线：接流的第一帧快照本身） */
    private boolean sendFrame(SseEmitter emitter, String type, String content, String sessionId) {
        return sendFrame(emitter, type, content, sessionId, null);
    }

    private boolean sendFrame(SseEmitter emitter, String type, String content, String sessionId, Object seq) {
        try {
            StringBuilder sb = new StringBuilder(96);
            sb.append("{\"type\":").append(JSON.toJSONString(type))
                    .append(",\"content\":").append(JSON.toJSONString(content == null ? "" : content))
                    .append(",\"sessionId\":").append(JSON.toJSONString(sessionId == null ? "" : sessionId));
            if (seq instanceof Number n) sb.append(",\"seq\":").append(n.longValue());
            sb.append('}');
            emitter.send(SseEmitter.event().name(type).data(sb.toString()));
            return true;
        } catch (Exception e) {
            log.debug("[RUN] 接流首帧发送失败（观众可能又走了）: session={} {}", sessionId, e.getMessage());
            return false;
        }
    }
}
