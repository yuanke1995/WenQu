package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 一轮问答的<b>运行总线</b>（断线重连接流）。
 *
 * <p>要解决的问题：{@code SseEmitter} 是一次性单向通道，页面刷新、切设备、网络抖动之后，
 * 那条还在逐字出答案的连接接不回来了——用户只能干等本轮落库后回看半程正文
 * （{@code web/src/api.js} 里「刻意不做自动重连」那条注释记的就是这个约束）。
 *
 * <p>做法是把「一轮的执行」和「一条连接」解耦：事件先进总线（按 sessionId 归属，一轮一条），
 * 由它扇出给<b>当前挂着的所有通道</b>；通道全没了也不掐本轮，只在<b>宽限期</b>到点后回调中止
 * （中止走 ChatService 既有的半程截断落库路径，落库结果与「断开即停」完全一致）。
 * 迟到的观众先拿一份<b>权威快照</b>（正文/过程独白/工具卡片/时间线/清单……），此后只收快照之后的增量。
 *
 * <p>为什么不缓冲逐字增量：正文的权威缓冲在运行时自己手里（框架引擎是 AgentScope 的会话状态），
 * 总线再存一份等于同一串字存两遍，而快照取缓冲比回放上千条 token 事件快得多。
 * 快照与增量的接缝靠「事件序号 + 文本增量的绝对下标 pos」收口：接流时在锁内同时取快照并登记订阅者，
 * 序号不大于快照时刻的事件不再发给它；「正文已进缓冲、事件号还没推进」那唯一一条可能重复的增量
 * 由前端按 pos 丢弃（{@code done} 带权威全文，任何残余缺口在那一刻归零）。
 *
 * <p>刻意保留的边界：
 * <ul>
 *   <li><b>人在回路的等待不受宽限约束</b>——挂在提问卡/计划卡/审批上的轮，关掉页面本来就一直在等人，
 *       宽限不许把它掐了：那是对用户承诺过的行为（判据在框架会话侧的挂起态，见 {@code FrameworkSessions}）；</li>
 *   <li><b>只在内存</b>：进程重启后轮已经没了，此时接流方拿 {@code running:false}，按历史落库回显；</li>
 *   <li>游客分享会话与工作流/定时任务的收集型通道（{@code CollectingSseEmitter}）不登记总线；</li>
 *   <li>开关 {@code chat.resumeEnabled}（设置页不暴露，仅留配置行供排障）关掉后一条事件都不接，
 *       行为回到「断开即中止、刷新看不到在跑的轮」。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRunBus {

    /** 巡检周期：宽限判定、终态回收与静默期心跳都挂在这一个 tick 上（一轮最多延迟一个周期） */
    private static final long SWEEP_MS = 5_000;
    /** 心跳间隔：工具执行这类长时间静默里防中间层与前端 120s 空闲看门狗按失联掐连接 */
    private static final long KEEPALIVE_MS = 15_000;
    /**
     * 镜像给迟到者的「状态型」事件：只留最后一份值，随快照一起给（给它们发增量没有意义）。
     * <p>前端把 {@code snapshot.events} 按普通事件重放，所以这里存的必须是<b>事件原文的 content</b>，
     * 与实时下发时喂给回调的值同源（plan/todo/retrieved/usage/warn 是 JSON 串）。
     * <p>compact_state 进镜像的理由与 deep_state 同一条：压缩是那二十多秒里唯一的进度说明，
     * 中途刷新的人只拿快照重建，不镜像就会接上一个「正在生成回答却一个字没有」的轮，
     * 而这条轮其实还在压缩。
     */
    /** 进断线镜像的事件名由 RunEventType 单点声明。 */
    private static final java.util.Set<String> MIRROR_TYPES = com.wenqu.ai.engine.RunEventType.mirrorNames();

    /** 工具实时输出只保留尾部这么多字符（与前端 liveOutput 的展示上限同一个量级） */
    private static final int TOOL_OUTPUT_KEEP = 65_536;
    /** 子智能体直播帧上限（与前端 _frames 的截断口径一致：超出丢最早） */
    private static final int SUBAGENT_FRAME_MAX = 240;
    /** 单帧超过这个长度就不进快照：前端按 JSON 解析帧载荷，截断会变成非法 JSON */
    private static final int SUBAGENT_FRAME_CHARS = 16_000;
    /** run_code 的工具名（终态时清掉扇出镜像，避免下一跑接到上一跑的板） */
    private static final String RUN_CODE_TOOL = "run_code";

    private final ConfigService configService;
    /** 跨副本层：注册表、事件帧广播、远端观众与快照请求 */
    private final RunBusCluster cluster;

    /** 一条在跑的轮：订阅者 + 事件序号 + 宽限状态 */
    public static final class Run {
        final String sessionId;
        final String turnId;
        final String userId;
        final long beganAt = System.currentTimeMillis();
        /** 当前挂着的通道（多标签/多设备同看一轮是常态，不顶掉旧的） */
        final List<Sub> subs = new CopyOnWriteArrayList<>();
        /** 镜像事件的最后一份 content */
        final Map<String, String> mirror = new ConcurrentHashMap<>();
        /**
         * 工具实时输出（按工具名累积的最后一段）：tool_output 是增量帧、不是状态帧，镜像只留最后一份
         * 没有意义，必须在总线上攒成一段，接流方才能看到「工具跑到一半时的输出」。
         * <p>只在总线里、进程内；本轮关闭随 Run 一起回收。
         */
        final Map<String, StringBuilder> toolOutputs = new HashMap<>();
        /** 上面那份是否发生过截断（前端据此显示「前面输出已省略」） */
        final Map<String, Boolean> toolOutputTruncated = new HashMap<>();
        /** 早产工具卡的最后一帧（入参还在生成时）：正式 start 一到就清掉同名项 */
        final Map<String, String> preparing = new HashMap<>();
        /** 子智能体直播帧（原样 content，前端按 subagent_step 逐帧重放） */
        final List<String> subagentSteps = new ArrayList<>();
        /** 已发出的事件序号。与 {@link #subs}、{@link #bareSince} 的变更同锁（见 {@link #bump}） */
        long seq;
        /** 最后一个观众离开的时刻（0=还有观众） */
        long bareSince;
        long lastKeepaliveAt = System.currentTimeMillis();
        boolean closed;

        /**
         * 收下一条「只能靠缓冲重建」的增量事件。调用方必须持有 run 锁（见 {@link #bump}）。
         * <p>tool_output / tool_status / subagent_step 是逐帧增量：镜像只留最后一份会丢掉过程，
         * 必须在这里按工具名/帧序列攒起来。任何解析失败都只是这一帧不参与快照，不影响扇出。
         */
        void observe(String type, String content) {
            if (type == null || content == null) return;
            try {
                if ("tool_output".equals(type)) {
                    com.alibaba.fastjson2.JSONObject o = JSON.parseObject(content);
                    String name = o == null ? null : o.getString("name");
                    String delta = o == null ? null : o.getString("delta");
                    if (name == null || delta == null) return;
                    StringBuilder buf = toolOutputs.computeIfAbsent(name, k -> new StringBuilder());
                    buf.append(delta);
                    if (buf.length() > TOOL_OUTPUT_KEEP) {
                        buf.delete(0, buf.length() - TOOL_OUTPUT_KEEP);
                        toolOutputTruncated.put(name, true);
                    }
                    return;
                }
                if ("tool_status".equals(type)) {
                    com.alibaba.fastjson2.JSONObject o = JSON.parseObject(content);
                    String name = o == null ? null : o.getString("name");
                    if (name == null || name.isBlank()) return;
                    boolean preparingFrame = Boolean.TRUE.equals(o.get("preparing"));
                    String status = o.getString("status");
                    if (preparingFrame) {
                        preparing.put(name, content);
                    } else {
                        preparing.remove(name);
                        // 正式 start / 终态都意味着上一段输出到此为止：清掉同名缓冲，
                        // 免得下一次同名调用接上来时快照把上一跑的输出挂到新卡上
                        if ("start".equals(status) || "done".equals(status) || "error".equals(status)) {
                            toolOutputs.remove(name);
                            toolOutputTruncated.remove(name);
                        }
                    }
                    if (RUN_CODE_TOOL.equals(name) && ("start".equals(status) || "done".equals(status)
                            || "error".equals(status))) {
                        mirror.remove("runcode_progress");
                    }
                    return;
                }
                if ("subagent_step".equals(type)) {
                    if (content.length() > SUBAGENT_FRAME_CHARS) return;
                    if (subagentSteps.size() >= SUBAGENT_FRAME_MAX) subagentSteps.remove(0);
                    subagentSteps.add(content);
                }
            } catch (Exception e) {
                // 帧形状变了：这一帧不进快照，实时扇出照常进行
                log.debug("[RUN] 快照缓冲跳过异常帧: type={} {}", type, e.getMessage());
            }
        }

        /** 快照读取（调用方须持有 run 锁）：把运行期缓冲转成下发副本 */
        Map<String, Object> liveBuffers() {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> outputs = new LinkedHashMap<>();
            for (Map.Entry<String, StringBuilder> e : toolOutputs.entrySet()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("text", e.getValue().toString());
                one.put("truncated", Boolean.TRUE.equals(toolOutputTruncated.get(e.getKey())));
                outputs.put(e.getKey(), one);
            }
            out.put("toolOutputs", outputs);
            out.put("preparing", new ArrayList<>(preparing.values()));
            out.put("subagentSteps", new ArrayList<>(subagentSteps));
            return out;
        }

        /** 轮次控制在 {@code agent.chat.ChatRunRuntime}，这里跨包读这两个字段，
         *  表本身仍不外露——只给读口。 */
        public String userId() {
            return userId;
        }

        public long beganAt() {
            return beganAt;
        }
        /** 权威快照供给（正文/工具/时间线这些仍归本轮状态所有，由跑出这一轮的引擎提供） */
        private volatile Supplier<Map<String, Object>> snapshotSource;
        /** 宽限到点：由 ChatService 掐本轮并走半程落库。返回 false=本轮不归宽限管（例如正等人工裁决），顺延 */
        final BooleanSupplier onGraceExpired;
        /** 本轮是否还在跑（把手还在且是同一 turnId）：终态后由巡检回收，免得每条终态路径都得记得关总线 */
        final BooleanSupplier turnAlive;

        Run(String sessionId, String turnId, String userId, Supplier<Map<String, Object>> snapshotSource,
            BooleanSupplier onGraceExpired, BooleanSupplier turnAlive) {
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.userId = userId;
            this.snapshotSource = snapshotSource;
            this.onGraceExpired = onGraceExpired;
            this.turnAlive = turnAlive;
        }

        public String turnId() {
            return turnId;
        }

        /**
         * 换成引擎那份「有内容」的快照供给。
         * <p>开轮那一刻外壳只有「还没开始出字」的空快照，跑出这一轮的运行时才有正文与工具卡；
         * 换供给与取快照都在 {@code run} 这把锁里（见 {@link #attach}），不会读到半新半旧那一份。
         */
        void offerSnapshot(Supplier<Map<String, Object>> src) {
            if (src != null) this.snapshotSource = src;
        }

        /** 当前观众数（日志与「别人也在看」的提示用） */
        public int viewers() {
            return subs.size();
        }
    }

    private static final class Sub {
        final SseEmitter emitter;
        /** 只收序号大于它的事件：快照已覆盖到它为止 */
        final long fromSeq;
        final AtomicBoolean dead = new AtomicBoolean();

        Sub(SseEmitter emitter, long fromSeq) {
            this.emitter = emitter;
            this.fromSeq = fromSeq;
        }
    }

    /** sessionId → 在跑的轮（会话轮级互斥保证一会话至多一轮，见 {@code ChatService#chat}） */
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    /**
     * 通道 → 它所属的那一轮。流水线闭包里握着的是<b>发起时</b>那条 emitter，
     * 观众换了几轮之后事件仍要送得出去，所以按 emitter 查回这一轮、再扇给此刻挂着的通道。
     */
    private final Map<SseEmitter, Run> channels = new ConcurrentHashMap<>();

    private ScheduledExecutorService sweeper;

    @PostConstruct
    void start() {
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-run-bus");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleWithFixedDelay(this::tick, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS);
        // 宿主侧能力挂给跨副本层：远端观众要快照、别的副本要叫停，都得由本副本（这一轮的主人）来答
        cluster.bind(this::snapshotForRemote, this::onRemoteCommand,
                (session, left) -> reportViewers(session));
    }

    @PreDestroy
    void stop() {
        if (sweeper != null) sweeper.shutdownNow();
    }

    /** 总开关：关掉即完全不登记（行为回到「断开即中止、刷新看不到在跑的轮」） */
    public boolean enabled() {
        return configService.getBoolean("chat.resumeEnabled", true);
    }

    /**
     * 宽限期（毫秒）。三档语义一个旋钮覆盖：
     * {@code >0}=没人看这么久就掐本轮（默认 180000，刷新/换设备都在这窗口里接得回来）；
     * {@code 0}=最后一个观众一走就掐（接流只在窗口内有效）；
     * {@code <0}=一律转后台跑完。
     */
    public long graceMs() {
        return configService.getLong("chat.detachGraceMs", 180_000L);
    }

    /**
     * 开一轮：登记总线并把发起时那条通道挂上。
     *
     * @param emitter 本轮一开始那条 SSE 通道（此后事件按它路由回这一轮，观众换了也照样送）
     */
    public void open(String sessionId, String turnId, String userId, SseEmitter emitter,
                     Supplier<Map<String, Object>> snapshotSource,
                     BooleanSupplier onGraceExpired, BooleanSupplier turnAlive) {
        if (sessionId == null || sessionId.isBlank()) return;
        Run prev = runs.remove(sessionId);
        if (prev != null) close(prev);   // 上一轮残留（会话互斥下不该出现，兜住引用不放）
        Run run = new Run(sessionId, turnId, userId, snapshotSource, onGraceExpired, turnAlive);
        runs.put(sessionId, run);
        if (emitter != null) {
            channels.put(emitter, run);
            run.subs.add(new Sub(emitter, 0));
        }
        cluster.registerRun(sessionId, turnId, userId);
        cluster.reportViewerCount(sessionId, run.subs.size());
        log.debug("[RUN] 总线登记本轮: session={} turn={} grace={}ms", sessionId, turnId, graceMs());
    }

    /** 本会话是否有在跑的轮 */
    public Run running(String sessionId) {
        Run run = sessionId == null ? null : runs.get(sessionId);
        return run != null && !run.closed ? run : null;
    }

    /**
     * 换上一份权威快照供给（由跑出这一轮的引擎在状态袋建好时调用）。
     * <p>本轮不在总线上（接流关闭、收集型通道、开关关掉）就什么都不做——那种情况下没有迟到观众要接。
     */
    public void offerSnapshot(String sessionId, Supplier<Map<String, Object>> snapshotSource) {
        Run run = running(sessionId);
        if (run != null) run.offerSnapshot(snapshotSource);
    }

    /**
     * 该会话这一轮还挂在总线上：通道断了也先别回收整轮台账与看门狗
     * （宽限期里它仍是后台那台机器的耗时上界，随通道一起放手等于让它无界跑下去）。
     */
    public boolean holdsRun(String sessionId) {
        return running(sessionId) != null;
    }

    /**
     * 发一条事件：进本轮、推进序号、扇给此刻挂着的通道。
     *
     * @param pos 文本型增量的<b>绝对下标</b>（本轮权威缓冲应用该增量之后的长度）。迟到观众拿快照后
     *            可能重复收到快照已含的那一片，前端按 pos 丢弃；结构性事件传 null
     * @return true=本轮由总线接管且还活着（调用方<b>不要</b>按「发送失败=客户端断开」掐本轮）；
     *         false=本轮不在总线上或已终结（调用方走原有的断开兜底）
     */
    public boolean publish(SseEmitter emitter, String type, String content, String sessionId, Integer pos) {
        Run run = owned(emitter);
        if (run == null) return false;
        long seq = bump(run, type, content);
        if (seq < 0) return false;
        return fan(run, seq, frame(type, content, sessionId, seq, pos), type);
    }

    /**
     * 发一条载荷本身就是 JSON 值的事件（tool_status / tool_output / runcode_progress 的 content 是对象而不是字符串）。
     * <p>tool_status / tool_output 不进 {@link #MIRROR_TYPES}：接流方靠快照的 toolCalls 清单重建工具卡片，
     * 运行中的输出与早产进度由 {@link Run#observe} 另行缓冲；{@code runcode_progress} 是状态型，进镜像。
     */
    public boolean publishRaw(SseEmitter emitter, String type, String rawJson, String sessionId) {
        Run run = owned(emitter);
        if (run == null) return false;
        long seq = bump(run, type, rawJson);
        if (seq < 0) return false;
        return fan(run, seq, rawFrame(type, rawJson, sessionId, seq), type);
    }

    /**
     * 按会话发一条事件（产物工具侧只拿得到 sessionId）。
     *
     * @return true=本轮由总线接管（调用方不必再自己发）
     */
    public boolean publishToSession(String sessionId, String type, String rawJson) {
        Run run = running(sessionId);
        if (run == null) return false;
        long seq = bump(run, type, rawJson);
        if (seq < 0) return false;
        return fan(run, seq, rawFrame(type, rawJson, sessionId, seq), type);
    }

    /**
     * 按会话发一条<b>镜像型</b>事件（目标态变更走这条：控制器侧没有通道，但迟到观众
     * 接流时要能凭快照里的镜像重建目标面板，所以必须同时进 {@code mirror}）。
     *
     * @return true=本轮由总线接管（调用方不必再自己发）
     */
    public boolean publishMirrorToSession(String sessionId, String type, String content) {
        Run run = running(sessionId);
        if (run == null) return false;
        long seq = bump(run, type, content);
        if (seq < 0) return false;
        return fan(run, seq, frame(type, content, sessionId, seq, null), type);
    }

    /** 该通道所属且仍活着的轮；null=不归总线管（游客会话、收集型通道、开关关闭、本轮已终结） */
    private Run owned(SseEmitter emitter) {
        Run run = emitter == null ? null : channels.get(emitter);
        return run == null || run.closed ? null : run;
    }

    /** 推进事件序号并镜像。<b>必须在 run 锁内</b>：attach 也在同一把锁里取快照+登记订阅者，
     *  这样「快照之后收到快照已含的事件」只剩一条可能，且由文本增量的 pos 兜住 */
    private long bump(Run run, String type, String mirrorValue) {
        synchronized (run) {
            if (run.closed) return -1;
            long seq = ++run.seq;
            if (mirrorValue != null && MIRROR_TYPES.contains(type)) {
                run.mirror.put(type, mirrorValue);
            }
            // 增量型运行态（工具输出/早产进度/子智能体直播）在这里攒成快照缓冲；
            // 与序号推进同锁，快照不会读到半截
            run.observe(type, mirrorValue);
            return seq;
        }
    }

    /** 本地扇出之后：这一轮若有别的副本上的观众，同一条帧原样广播出去（序号沿用本副本宿主的） */
    private void broadcastRemote(Run run, long seq, String frame) {
        if (cluster.shouldBroadcast(run.sessionId)) {
            cluster.publishFrame(run.sessionId, seq, frame);
        }
    }

    private boolean fan(Run run, long seq, String frame, String type) {
        for (Sub sub : run.subs) {
            if (sub.fromSeq >= seq) continue;   // 快照已覆盖到它
            if (sub.dead.get()) {
                removeSub(run, sub);
                continue;
            }
            try {
                sub.emitter.send(SseEmitter.event().name(type).data(frame));
            } catch (Exception e) {
                // 单条通道失败只摘它，本轮继续跑——「观众走光」不等于「停止执行」
                sub.dead.set(true);
                removeSub(run, sub);
                log.debug("[RUN] 通道推送失败已摘除: session={} type={} {}", run.sessionId, type, e.getMessage());
            }
        }
        broadcastRemote(run, seq, frame);
        return !run.closed;
    }

    /**
     * 观众接流：锁内「取快照 + 登记订阅者」，两者之间插不进任何一次发布（发布也在同一把锁里推序号），
     * 所以快照与后续增量的接缝是确定的。
     *
     * @return 快照（含 {@code running} 与本轮到此刻的全部可重建内容）
     */
    public Map<String, Object> attach(Run run, SseEmitter emitter) {
        Map<String, Object> snapshot;
        synchronized (run) {
            snapshot = safeSnapshot(run);
            if (!run.closed) {
                channels.put(emitter, run);
                run.subs.add(new Sub(emitter, run.seq));
                run.bareSince = 0;   // 有观众了，宽限重新计时
            }
        }
        reportViewers(run.sessionId);
        log.info("[RUN] 观众接流: session={} turn={} 现有观众={}", run.sessionId, run.turnId, run.subs.size());
        return snapshot;
    }

    /** 本轮的快照（含镜像事件）；调用方须持有 run 锁 */
    private Map<String, Object> safeSnapshot(Run run) {
        Map<String, Object> snap = new LinkedHashMap<>();
        try {
            Map<String, Object> src = run.snapshotSource.get();
            if (src != null) snap.putAll(src);
        } catch (Exception e) {
            // 快照失败就不接流：宁可让用户按历史回显，也不要一个「接上了却什么都没有」的空气泡
            log.warn("[RUN] 快照构建失败: session={} {}", run.sessionId, e.getMessage());
            snap.put("running", false);
        }
        snap.putIfAbsent("running", !run.closed);
        snap.put("turnId", run.turnId);
        snap.put("uid", run.userId);
        snap.put("seq", run.seq);
        snap.put("beganAt", run.beganAt);
        // 工具实时输出 / 早产工具卡 / 子智能体直播帧：状态型镜像装不下这些增量，单独给缓冲副本
        snap.putAll(run.liveBuffers());
        Map<String, String> events = new LinkedHashMap<>();
        for (String type : MIRROR_TYPES) {
            String v = run.mirror.get(type);
            if (v != null) events.put(type, v);
        }
        snap.put("events", events);
        return snap;
    }

    /**
     * 通道走完/被掐：摘掉这个<b>订阅</b>（本轮不受影响；观众走光时开始宽限计时）。
     * <p>刻意不清 {@code channels} 里那条「通道→本轮」的路由：流水线闭包里握着的永远是发起时那条通道，
     * 客户端断开时容器会回调它的 onCompletion —— 那时若把路由一起摘掉，后续事件就找不到这一轮了，
     * 调用方会把「发送失败」当成「客户端断开」掐掉正在出字的轮，接流的意义当场失效。
     * 路由随本轮关闭一起回收（见 {@link #close}）。
     */
    public void drop(SseEmitter emitter) {
        if (emitter == null) return;
        Run run = channels.get(emitter);
        if (run == null) return;
        for (Sub sub : run.subs) {
            if (sub.emitter == emitter) removeSub(run, sub);
        }
    }

    private void removeSub(Run run, Sub sub) {
        if (run.subs.remove(sub)) {
            reportViewers(run.sessionId);
        }
        if (run.subs.isEmpty() && !run.closed) {
            synchronized (run) {
                if (run.subs.isEmpty() && run.bareSince == 0) run.bareSince = System.currentTimeMillis();
            }
        }
    }

    /** 本轮收尾：调用方发完 done/error 之后关闭（幂等）。忘了也有巡检按把手回收 */
    public void finish(String sessionId) {
        finish(sessionId, null);
    }

    /**
     * 收这一轮的总线，但让 {@code keepOpen} 那一只观众的连接留着。
     * <p>用在「用户点停止」这一条：done 才是终态事实的载体（finalContent/blocks/notice/messageId），
     * 把发起者一并关掉会让那一轮只剩半截正文——接流方与别的标签页仍然立刻收尾，
     * 装配期卡住不发的兜底由 {@code ChatRunRuntime} 的宽限期定时器掐。
     */
    public void finish(String sessionId, SseEmitter keepOpen) {
        Run run = sessionId == null ? null : runs.get(sessionId);
        if (run != null) close(run, keepOpen);
    }

    private void close(Run run) {
        close(run, null);
    }

    private void close(Run run, SseEmitter keepOpen) {
        synchronized (run) {
            if (run.closed) return;
            run.closed = true;
        }
        runs.remove(run.sessionId, run);
        channels.values().removeIf(r -> r == run);
        // 别的副本上可能也挂着这一轮的观众：先通知他们收尾，再摘注册表（顺序反了他们就等 TTL 才发现）
        cluster.publishClosed(run.sessionId, run.seq);
        cluster.unregisterRun(run.sessionId);
        // 每个观众的连接都要关掉：本轮结束而通道留着不 complete，那个异步请求会一直挂着
        // （emitter 不带容器超时，接流方自己也只会转圈到空闲看门狗超时）
        for (Sub sub : run.subs) {
            if (keepOpen != null && sub.emitter == keepOpen) continue;
            try {
                sub.emitter.complete();
            } catch (Exception ignored) {
                // 已断开/已完成：无需处理
            }
        }
        run.subs.clear();
    }

    /**
     * 巡检：回收已终态的轮、宽限到点掐本轮、静默期发心跳。
     * <p>心跳在这里而不是交给各阶段：总线接管后原发起通道可能一条都送不出去（工具跑五分钟），
     * 接流的观众同样需要这条注释行活着，而这不该由本轮的执行代码操心。
     */
    private void tick() {
        try {
            long now = System.currentTimeMillis();
            long grace = graceMs();
            for (Run run : runs.values()) {
                if (run.closed) {
                    runs.remove(run.sessionId, run);
                    continue;
                }
                // 本轮已收尾（把手被 claimTerminal 摘掉）：总线跟着关，不必等调用方记得调 finish
                if (!run.turnAlive.getAsBoolean()) {
                    close(run);
                    continue;
                }
                cluster.hostPulse(run.sessionId, run.subs.size());
                cluster.refreshRun(run.sessionId, run.seq);
                // 「没人看」必须连同别的副本上的观众一起判，否则 A 副本一刷新就会把 B 副本正在看的轮掐掉
                if (run.subs.isEmpty() && cluster.remoteViewerCount(run.sessionId) == 0) {
                    if (run.bareSince == 0) run.bareSince = now;
                    if (grace >= 0 && now - run.bareSince >= grace) {
                        boolean aborted = false;
                        try {
                            aborted = run.onGraceExpired.getAsBoolean();
                        } catch (Exception e) {
                            log.warn("[RUN] 宽限到点中止失败: session={} {}", run.sessionId, e.getMessage());
                        }
                        if (aborted) {
                            log.info("[RUN] 断线超过宽限，本轮按中断收束: session={} 无观众 {}s",
                                    run.sessionId, (now - run.bareSince) / 1000);
                            close(run);
                        } else {
                            // 回调说掐不得（正等人工裁决）：宽限重新计时，下一周期再判
                            synchronized (run) {
                                run.bareSince = System.currentTimeMillis();
                            }
                        }
                    }
                } else if (now - run.lastKeepaliveAt >= KEEPALIVE_MS) {
                    run.lastKeepaliveAt = now;
                    for (Sub sub : run.subs) {
                        try {
                            sub.emitter.send(SseEmitter.event().comment("keepalive"));
                        } catch (Exception e) {
                            sub.dead.set(true);
                            removeSub(run, sub);
                        }
                    }
                }
            }
            // 本副本不主持、却在替别的副本服务观众的那些会话：续存在性 + 心跳，否则它们的通道会静默失联
            for (String session : cluster.sessionsWithRemoteSubs()) {
                if (running(session) != null) continue;
                cluster.reportViewerCount(session, cluster.remoteSubCountHere(session));
                cluster.keepaliveRemote(session);
            }
        } catch (Exception e) {
            log.warn("[RUN] 总线巡检异常（不中断）: {}", e.getMessage());
        }
    }

    /** 本副本此刻的观众总数（本地通道 + 本副本代管的远端通道）写进跨副本存在性表 */
    private void reportViewers(String sessionId) {
        Run run = runs.get(sessionId);
        int local = run == null ? 0 : run.subs.size();
        cluster.reportViewerCount(sessionId, local + cluster.remoteSubCountHere(sessionId));
    }

    // ==================== 跨副本 ====================

    /** 只取快照不登记观众：供别的副本上的接流方使用（它们要的是「此刻为止看得见的东西」） */
    private Map<String, Object> snapshotForRemote(String sessionId) {
        Run run = running(sessionId);
        if (run == null) return null;
        synchronized (run) {
            return safeSnapshot(run);
        }
    }

    /** 别的副本转过来的指令：交回给登记过该动作的执行方（叫停/中止都在轮次控制侧） */
    private void onRemoteCommand(String op, com.alibaba.fastjson2.JSONObject payload) {
        BiConsumer<String, com.alibaba.fastjson2.JSONObject> handler = commandHandlers.get(op);
        if (handler == null) {
            log.warn("[RUN] 收到没人认领的跨副本指令: op={} {}", op, payload);
            return;
        }
        handler.accept(payload.getString("session"), payload);
    }

    private final Map<String, BiConsumer<String, com.alibaba.fastjson2.JSONObject>> commandHandlers = new ConcurrentHashMap<>();

    /** 注册一个跨副本指令的处理方（同 op 覆盖）；由轮次控制在装配时调用 */
    public void registerCommandHandler(String op, BiConsumer<String, com.alibaba.fastjson2.JSONObject> handler) {
        commandHandlers.put(op, handler);
    }

    /** 本副本没有这一轮、但别的副本有：向宿主要快照并把这条通道代管起来 */
    public Map<String, Object> attachRemote(String sessionId, String userId, SseEmitter emitter) {
        if (!enabled() || sessionId == null) return null;
        if (!cluster.hostedElsewhere(sessionId)) return null;
        Map<String, String> host = cluster.lookupRun(sessionId);
        Map<String, Object> snapshot = cluster.requestSnapshot(sessionId);
        if (snapshot == null || !Boolean.TRUE.equals(snapshot.get("running"))) return null;
        if (userId == null || !userId.equals(String.valueOf(snapshot.get("uid")))) {
            // 快照里没有归属就不代管：宁可接不上，也不能让任何人接走别人的流
            log.warn("[RUN] 远端快照缺归属信息，拒绝代管: session={}", sessionId);
            return null;
        }
        long fromSeq = snapshot.get("seq") instanceof Number n ? n.longValue() : 0L;
        cluster.addRemoteViewer(sessionId, emitter, userId, fromSeq);
        log.info("[RUN] 观众接流（跨副本）: session={} host={} 本副本代管观众={}",
                sessionId, host == null ? "?" : host.get("replica"), cluster.remoteSubCountHere(sessionId));
        return snapshot;
    }

    /** 通道走完：本地订阅与本副本代管的远端订阅都要摘 */
    public void dropRemote(SseEmitter emitter, String sessionId) {
        if (emitter == null) return;
        if (sessionId != null) {
            cluster.removeRemoteSub(sessionId, emitter);
        }
    }

    /** 跨副本探测：本副本没有这一轮时，问注册表 */
    public Map<String, String> remoteRunInfo(String sessionId) {
        return cluster.hostedElsewhere(sessionId) ? cluster.lookupRun(sessionId) : null;
    }

    /** 把叫停转给宿主副本执行 */
    public boolean forwardStop(String sessionId, String userId) {
        return cluster.forwardCommand(sessionId, "stop",
                Map.of("session", sessionId, "userId", userId == null ? "" : userId));
    }

    /** 宿主副本 id（前端提示「这一轮在别的节点上」用） */
    public String replicaId() {
        return cluster.replicaId();
    }

    /**
     * 组装 data 帧：{@code {"type":..,"content":"..","sessionId":..,"seq":..,"pos":..}}。
     * <p>content 一律按字符串转义，与 {@code ChatService.sendSseEvent} 的手工拼接同形；
     * 多出的 seq/pos 对老前端无感（只读 data 行、按 type 派发）。
     */
    public static String frame(String type, String content, String sessionId, long seq, Integer pos) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"type\":").append(JSON.toJSONString(type))
                .append(",\"content\":").append(JSON.toJSONString(content == null ? "" : content))
                .append(",\"sessionId\":").append(JSON.toJSONString(sessionId == null ? "" : sessionId))
                .append(",\"seq\":").append(seq);
        if (pos != null) sb.append(",\"pos\":").append(pos);
        sb.append('}');
        return sb.toString();
    }

    /** 载荷本身已是 JSON 值的事件帧（content 原样插入，不二次转义） */
    public static String rawFrame(String type, String rawJson, String sessionId, long seq) {
        return "{\"type\":" + JSON.toJSONString(type)
                + ",\"content\":" + (rawJson == null || rawJson.isBlank() ? "null" : rawJson)
                + ",\"sessionId\":" + JSON.toJSONString(sessionId == null ? "" : sessionId)
                + ",\"seq\":" + seq + "}";
    }

    /** 运维读口：当前挂着几轮 */
    public int liveRuns() {
        return runs.size();
    }
}
