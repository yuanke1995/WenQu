package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 一轮问答的<b>运行总线</b>（P0 最后一刀：断线重连接流）。
 *
 * <p>要解决的问题：{@code SseEmitter} 是一次性单向通道，页面刷新、切设备、网络抖动之后，
 * 那条还在逐字出答案的连接接不回来了——用户只能干等本轮落库后回看半程正文
 * （{@code web/src/api.js} 里「刻意不做自动重连」那条注释记的就是这个约束）。
 *
 * <p>做法是把「一轮的执行」和「一条连接」解耦：事件先进总线（按 sessionId 归属，一轮一条），
 * 由它扇出给<b>当前挂着的所有通道</b>；通道全没了也不掐本轮，只在<b>宽限期</b>到点后回调中止
 * （中止走 RagService 既有的半程截断落库路径，落库结果与今天「断开即停」完全一致）。
 * 迟到的观众先拿一份<b>权威快照</b>（正文/过程独白/工具卡片/时间线/清单……），此后只收快照之后的增量。
 *
 * <p>为什么不缓冲逐字增量：正文本身已经在 {@code AnswerStreamState.fullResponse} 这类权威缓冲里，
 * 总线再存一份等于同一串字存两遍，而快照取缓冲比回放上千条 token 事件快得多。
 * 快照与增量的接缝靠「事件序号 + 文本增量的绝对下标 pos」收口：接流时在锁内同时取快照并登记订阅者，
 * 序号不大于快照时刻的事件不再发给它；「正文已进缓冲、事件号还没推进」那唯一一条可能重复的增量
 * 由前端按 pos 丢弃（{@code done} 带权威全文，任何残余缺口在那一刻归零）。
 *
 * <p>刻意保留的边界：
 * <ul>
 *   <li><b>人在回路的等待不受宽限约束</b>——挂在提问卡/计划卡/审批上的轮，关掉页面本来就一直在等人
 *       （见 {@code AnswerStreamState#keepRunningWithoutChannel}），宽限不许把它掐了：那是对用户承诺过的行为；</li>
 *   <li><b>只在内存</b>：进程重启后轮已经没了，此时接流方拿 {@code running:false}，按历史落库回显（与今天一致）；</li>
 *   <li>游客分享会话与工作流/定时任务的收集型通道（{@code CollectingSseEmitter}）不登记总线；</li>
 *   <li>开关 {@code chat.resumeEnabled} 关掉后一条事件都不接，行为回到「断开即中止、刷新看不到在跑的轮」。</li>
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
     * 与实时下发时喂给回调的值同源（stage 是文案，plan/todo/retrieved/usage 是 JSON 串）。
     */
    private static final List<String> MIRROR_TYPES = List.of("stage", "plan", "todo", "retrieved", "usage");

    private final ConfigService configService;

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
        /** 已发出的事件序号。与 {@link #subs}、{@link #bareSince} 的变更同锁（见 {@link #bump}） */
        long seq;
        /** 最后一个观众离开的时刻（0=还有观众） */
        long bareSince;
        long lastKeepaliveAt = System.currentTimeMillis();
        boolean closed;
        /** 权威快照供给（正文/工具/时间线这些仍归本轮状态所有，由 RagService 提供） */
        final Supplier<Map<String, Object>> snapshotSource;
        /** 宽限到点：由 RagService 掐本轮并走半程落库。返回 false=本轮不归宽限管（例如正等人工裁决），顺延 */
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

    /** sessionId → 在跑的轮（会话轮级互斥保证一会话至多一轮，见 {@code RagService#chat}） */
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
     * {@code 0}=最后一个观众一走就掐（旧的成本口径，接流只在窗口内有效）；
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
        log.debug("[RUN] 总线登记本轮: session={} turn={} grace={}ms", sessionId, turnId, graceMs());
    }

    /** 本会话是否有在跑的轮 */
    public Run running(String sessionId) {
        Run run = sessionId == null ? null : runs.get(sessionId);
        return run != null && !run.closed ? run : null;
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
     * 发一条载荷本身就是 JSON 值的事件（tool_status / tool_output 的 content 是对象而不是字符串）。
     * <p>不镜像：这两类不在 {@link #MIRROR_TYPES} 里，接流方靠快照的 toolCalls 清单重建工具卡片。
     */
    public boolean publishRaw(SseEmitter emitter, String type, String rawJson, String sessionId) {
        Run run = owned(emitter);
        if (run == null) return false;
        long seq = bump(run, type, null);
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
        long seq = bump(run, type, null);
        if (seq < 0) return false;
        return fan(run, seq, rawFrame(type, rawJson, sessionId, seq), type);
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
            return seq;
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
                // 单条通道失败只摘它，本轮继续跑——「观众走光」不等于「停止执行」，这正是这次改造的要害
                sub.dead.set(true);
                removeSub(run, sub);
                log.debug("[RUN] 通道推送失败已摘除: session={} type={} {}", run.sessionId, type, e.getMessage());
            }
        }
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
        snap.put("seq", run.seq);
        snap.put("beganAt", run.beganAt);
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
        if (run.subs.remove(sub) && run.subs.isEmpty()) {
            synchronized (run) {
                if (run.subs.isEmpty() && run.bareSince == 0) run.bareSince = System.currentTimeMillis();
            }
        }
    }

    /** 本轮收尾：调用方发完 done/error 之后关闭（幂等）。忘了也有巡检按把手回收 */
    public void finish(String sessionId) {
        Run run = sessionId == null ? null : runs.get(sessionId);
        if (run != null) close(run);
    }

    private void close(Run run) {
        synchronized (run) {
            if (run.closed) return;
            run.closed = true;
        }
        runs.remove(run.sessionId, run);
        channels.values().removeIf(r -> r == run);
        // 每个观众的连接都要关掉：本轮结束而通道留着不 complete，那个异步请求会一直挂着
        // （emitter 不带容器超时，接流方自己也只会转圈到空闲看门狗超时）
        for (Sub sub : run.subs) {
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
                if (run.subs.isEmpty()) {
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
        } catch (Exception e) {
            log.warn("[RUN] 总线巡检异常（不中断）: {}", e.getMessage());
        }
    }

    /**
     * 组装 data 帧：{@code {"type":..,"content":"..","sessionId":..,"seq":..,"pos":..}}。
     * <p>content 一律按字符串转义，与 {@code RagService.sendSseEvent} 原有的手工拼接同形；
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

    /** 运维读口：当前挂着几轮（面板没做，先留一手） */
    public int liveRuns() {
        return runs.size();
    }
}
