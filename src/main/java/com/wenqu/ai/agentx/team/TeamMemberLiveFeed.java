package com.wenqu.ai.agentx.team;

import com.wenqu.ai.engine.RunEvent;
import com.wenqu.ai.engine.RunEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>后台轮直播台</b>：把一条 headless 起跑的后台轮变成像主问答一样可订阅的 SSE，
 * 并保留一段有界积压，供「进页面晚了几秒」的观众补齐。
 *
 * <p>两个使用者共用同一份实现，因为它们是同一类东西：
 * <ul>
 *   <li><b>团队成员轮</b>——框架唤醒起的成员轮（{@link TeamWakeService}），观众是成员会话页；</li>
 *   <li><b>子助手分支</b>——{@code SubAgentBranchRuntime} 起的分支轮，观众是父轮的派遣卡。
 *       它的过程帧本来就经 {@code forwardProcess} 上翻给父轮，但<b>正文 token 从来不在那份白名单里</b>
 *       ——父轮看得到「它在干活」，看不到「它在写什么」。直播台补的正是这一段。</li>
 * </ul>
 *
 * <h2>为什么需要这一层</h2>
 * 主问答的直播靠两条东西：本轮那条 {@code SseEmitter}（观众在场时直接写）和 {@code ChatRunBus}
 * （断线/换设备的观众接回来）。成员轮两条都缺：它由框架的唤醒回调在无请求线程上起跑，
 * 用的是一次性的 {@code CollectingSseEmitter}——事件写进去就丢了，不落总线（总线只接真实响应），
 * 于是成员会话页只能等整轮跑完读历史，看到的是「一次性蹦出来的结果」。
 * <p>
 * 这一层不改引擎、不动通道语义：收集型通道仍然是那一轮的出口（收尾、看门狗、落库都照旧），
 * 只是每发一帧同时<b>抄送</b>给直播台。观众走一条独立的 SSE：
 * <pre>
 *   前端订阅  →  先回一帧 snapshot（积压的帧按原顺序重放）  →  之后逐帧实时
 * </pre>
 * 与主问答的 {@code /chat/resume} 同一形状：先快照、后增量，前端两条路各走各的，语义一致。
 *
 * <h2>边界</h2>
 * <ul>
 *   <li>只抄<b>可重建界面</b>的那几类帧（正文/思考/过程/工具/卡片/产物/警示…），心跳与终帧自己发；</li>
 *   <li>积压有上限（{@link #MAX_BACKLOG}）：长轮里没人看时它只负责「最近能看到的那一屏」，不无限吃内存；</li>
 *   <li>轮结束（或超时）后保留一小段时间再回收：刚跑完就进页面的观众仍能补齐这一轮，
 *       超时后前端退回历史回显（历史是权威，落库那份此时已经写完）。</li>
 *   <li>跨副本不做投递：成员轮一定跑在「唤醒它的那个副本」上，订阅也落在同一副本；
 *       命中不到就是没有直播，前端如实退回历史。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Component
public class TeamMemberLiveFeed {

    /** 观众不在时最多留多少帧（够重放「这一轮跑到现在」的可视过程，不是整轮原始流水）。 */
    static final int MAX_BACKLOG = 800;

    /** 结束后积压保留多久：刚跑完进页面的人还能补齐，之后交回历史。 */
    static final long RETAIN_AFTER_DONE_MS = 120_000L;

    /** 单次 SSE 超时：整轮可能跑很久，但观众连接由心跳维持；这里给足一整轮的预算。 */
    static final long VIEWER_TIMEOUT_MS = 30 * 60_000L;

    /** 心跳间隔：不写就等着浏览器/代理按空闲断开。 */
    private static final long HEARTBEAT_MS = 15_000L;

    /**
     * 抄送只认这些帧：它们合起来能把一条进行中的回答<b>完整</b>重建出来——
     * 正文与思考是逐字的（主问答有，后台轮此前恰恰缺这两样），工具卡/审批卡/提问卡/计划卡
     * 是能就地操作的三张卡，产物与来源是交付物。
     * <p>不抄的：心跳、snapshot（它是本类的出口不是入口）、done/error（本类自己发）。
     */
    private static final java.util.Set<RunEventType> RELAY = java.util.EnumSet.of(
            RunEventType.TURN_START, RunEventType.TOKEN, RunEventType.PROCESS, RunEventType.THINKING,
            RunEventType.THINKING_DONE, RunEventType.TOOL_STATUS, RunEventType.TOOL_OUTPUT,
            RunEventType.RUNCODE_PROGRESS, RunEventType.SUBAGENT_STEP, RunEventType.AGENT_DELEGATED,
            RunEventType.AGENT_CONSULTED, RunEventType.APPROVAL_REQUIRED, RunEventType.ASK_USER,
            RunEventType.PLAN, RunEventType.DEEP_PLAN_DELTA, RunEventType.DEEP_PLAN_APPROVAL,
            RunEventType.DEEP_PLAN_CANCELLED, RunEventType.DEEP_PLAN_SUPERSEDED, RunEventType.TODO,
            RunEventType.DEEP_STATE, RunEventType.RETRIEVED, RunEventType.SKILL_HIT,
            RunEventType.USAGE, RunEventType.COMPACT_STATE, RunEventType.IMAGE, RunEventType.ARTIFACT,
            RunEventType.BROWSER_SNAPSHOT, RunEventType.WARN, RunEventType.STEER);

    /** 一帧：SSE 名 + 载荷（与主问答同一条契约，前端同一个派发器认它）。 */
    public record Frame(String event, String data, long seq) {
    }

    /** 一条成员会话上的直播。 */
    public static final class Feed {
        private final String sessionId;
        private final List<Frame> backlog = new ArrayList<>();
        private final List<Viewer> viewers = new CopyOnWriteArrayList<>();
        private final AtomicLong seq = new AtomicLong();
        private volatile boolean done;
        private volatile long doneAt;
        private volatile long lastActiveAt = System.currentTimeMillis();

        private Feed(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    /** 一个观众：一条 SSE 连接 + 它自己的心跳。 */
    private record Viewer(SseEmitter emitter, ScheduledFuture<?> heartbeat) {
    }

    private final Map<String, Feed> feeds = new ConcurrentHashMap<>();
    /** 一个 daemon 线程足够：心跳与回收都是低频小活。 */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "team-live-feed");
        t.setDaemon(true);
        return t;
    });

    public TeamMemberLiveFeed() {
        // 回收：结束后过保留期就整条摘掉；观众各自已收到 done，不会被掐
        timer.scheduleWithFixedDelay(this::evict, 30_000L, 30_000L, TimeUnit.MILLISECONDS);
    }

    // ==================== 生产者：成员轮每发一帧 ====================

    /**
     * 开一条直播（后台轮开始）。<b>按会话幂等</b>：已经在直播就直接复用，不重建。
     * <p>为什么幂等：开台有三个入口——唤醒执行器、分支执行器、引擎轮首各调一次，
     * 而它们对「谁先到」没有约定。若重复开就替换，后到的那个会把先前观众的连接掐掉、
     * 积压里已经收到的开头帧也一起丢掉（观众看到的是从半截开始的一场直播）。
     * 由本方法自己保证「一条会话只有一场直播」，三个入口就不必知道彼此的先后。
     *
     * @return 这场直播（可能是已有的那一场）
     */
    public Feed open(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return feeds.computeIfAbsent(sessionId, Feed::new);
    }

    /**
     * 抄送一帧：记进积压并推给所有在场观众。
     * <p>不在直播名单里的帧（心跳/终帧/未知类型）静默跳过——这一层不替引擎决定「什么该上屏」，
     * 只决定「哪些能重建界面」。
     */
    public void relay(String sessionId, RunEvent event) {
        if (event == null || event.type() == null) return;
        Feed f = sessionId == null ? null : feeds.get(sessionId);
        if (f == null || f.done) return;
        if (!RELAY.contains(event.type())) return;
        f.lastActiveAt = System.currentTimeMillis();
        Frame frame = new Frame(event.type().sseName(),
                event.content() == null ? "" : event.content(), f.seq.incrementAndGet());
        boolean overflow;
        synchronized (f) {
            f.backlog.add(frame);
            // 超上限丢最早的：观众先拿 snapshot 再过增量，丢掉的只影响「重放多完整」，
            // 完整过程另有会话事件账本可查（账本不受这里影响）。
            overflow = f.backlog.size() > MAX_BACKLOG;
            if (overflow) f.backlog.subList(0, f.backlog.size() - MAX_BACKLOG).clear();
        }
        push(f, frame);
    }

    /** 成员轮收场：推 done 帧、摘掉在场观众的心跳。积压留着等迟到的人。 */
    public void finish(String sessionId, String message) {
        Feed f = sessionId == null ? null : feeds.get(sessionId);
        if (f == null || f.done) return;
        f.done = true;
        f.doneAt = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("message", message == null ? "" : message);
        payload.put("sessionId", f.sessionId);
        Frame done = new Frame(RunEventType.DONE.sseName(), toJson(payload), f.seq.incrementAndGet());
        synchronized (f) {
            f.backlog.add(done);
        }
        for (Viewer v : f.viewers) {
            sendRaw(v.emitter(), done.event(), done.data());
            if (v.heartbeat() != null) v.heartbeat().cancel(false);
            try {
                v.emitter().complete();
            } catch (RuntimeException ignored) {
                // 已断开：complete 再抛一次不影响任何状态
            }
        }
        f.viewers.clear();
    }

    // ==================== 消费者：成员会话页订阅 ====================

    /**
     * 订阅某条成员会话的直播。
     * <p>第一帧永远是 {@code snapshot}（含积压的帧与 done 标记），前端据此要么重放成进行中的气泡、
     * 要么退回历史。之后逐帧增量，收尾照主问答走 done。
     *
     * @return null = 这条会话此刻没有直播（别的副本起的、进程重启过、或早过了保留期）——
     *         调用方按「没有直播」处理，不要当成错误
     */
    public SseEmitter subscribe(String sessionId) {
        Feed f = sessionId == null ? null : feeds.get(sessionId);
        if (f == null) return null;
        SseEmitter emitter = new SseEmitter(VIEWER_TIMEOUT_MS);
        List<Frame> replay;
        boolean finished;
        // 先登记再发快照：两步之间产生的帧会同时出现在积压里与实时推送上，前端按 seq 去重
        // （与 ChatRunBus 同一套去重口径）
        synchronized (f) {
            f.viewers.add(new Viewer(emitter, null));
            replay = new ArrayList<>(f.backlog);
            finished = f.done;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sessionId", f.sessionId);
        snapshot.put("done", finished);
        List<Map<String, Object>> frames = new ArrayList<>(replay.size());
        for (Frame fr : replay) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("event", fr.event());
            one.put("data", fr.data());
            one.put("seq", fr.seq());
            frames.add(one);
        }
        snapshot.put("frames", frames);
        sendRaw(emitter, RunEventType.SNAPSHOT.sseName(), toJson(snapshot));
        if (finished) {
            // 快照里已带 done：前端走历史回显，这条连接当场收掉，不留空转
            drop(f, emitter);
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // 已收尾
            }
            return emitter;
        }
        ScheduledFuture<?> hb = timer.scheduleWithFixedDelay(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception dead) {
                drop(f, emitter);
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        swapHeartbeat(f, emitter, hb);
        emitter.onCompletion(() -> drop(f, emitter));
        emitter.onTimeout(() -> drop(f, emitter));
        emitter.onError(t -> drop(f, emitter));
        log.info("[TEAM] 直播观众接入: session={} 重放{}帧 在场={}", sessionId, replay.size(), f.viewers.size());
        return emitter;
    }

    // ==================== 包内可见的排障/测试钩子 ====================
    // 只给同包测试看「积压里现在是什么、有几个观众」——生产代码不读这两份，
    // 打开它们的代价是一处公开（package-private）的只读视图，换掉的是「直播对不对只能靠猜」。

    /** 该会话积压的帧快照（按原顺序）。 */
    List<Frame> backlogOf(String sessionId) {
        Feed f = sessionId == null ? null : feeds.get(sessionId);
        if (f == null) return List.of();
        synchronized (f) {
            return List.copyOf(f.backlog);
        }
    }

    /** 该会话当前在场的观众数。 */
    int viewersOf(String sessionId) {
        Feed f = sessionId == null ? null : feeds.get(sessionId);
        return f == null ? 0 : f.viewers.size();
    }

    // ==================== 内部 ====================

    private void swapHeartbeat(Feed f, SseEmitter emitter, ScheduledFuture<?> hb) {
        for (Viewer v : f.viewers) {
            if (v.emitter() == emitter && v.heartbeat() == null) {
                f.viewers.remove(v);
                f.viewers.add(new Viewer(emitter, hb));
                return;
            }
        }
    }

    private void drop(Feed f, SseEmitter emitter) {
        if (f == null) return;
        for (Viewer v : f.viewers) {
            if (v.emitter() != emitter) continue;
            f.viewers.remove(v);
            if (v.heartbeat() != null) v.heartbeat().cancel(false);
        }
    }

    private void push(Feed f, Frame frame) {
        for (Viewer v : f.viewers) sendRaw(v.emitter(), frame.event(), frame.data());
    }

    private void sendRaw(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data == null ? "" : data));
        } catch (Exception dead) {
            // 观众断了：只摘它自己，不中断成员轮（成员轮继续跑完并落库，历史照样有）
            try {
                emitter.completeWithError(dead);
            } catch (RuntimeException ignored) {
                // 已经收尾过
            }
        }
    }

    private static String toJson(Object o) {
        try {
            return com.alibaba.fastjson2.JSON.toJSONString(o);
        } catch (RuntimeException e) {
            return "{}";
        }
    }

    /** 结束后的保留期清理。 */
    private void evict() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Feed> e : feeds.entrySet()) {
            Feed f = e.getValue();
            if (!f.done) {
                // 没结束但很久没动（异常残留，如进程内留下的半截轮）：一并清掉，避免内存里挂着死直播
                if (now - f.lastActiveAt > RETAIN_AFTER_DONE_MS * 30L) feeds.remove(e.getKey(), f);
                continue;
            }
            if (now - f.doneAt > RETAIN_AFTER_DONE_MS) feeds.remove(e.getKey(), f);
        }
    }
}
