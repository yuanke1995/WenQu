package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.model.WorkflowRun;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 工作流运行进度流——<b>问答那条运行总线（{@link ChatRunBus}）的第二个使用方</b>。
 * <p>一次运行对应一个总线上的 Run（键 {@code wf:{runId}}）：节点完成 → {@code trace}、状态迁移 →
 * {@code status}、收口 → {@code done} 后关闭。画布据此"边跑边亮"，不再等跑完批量回放。
 * <p>2026-10-12 从「自带一条通道」改为接进总线，补掉的是三件原来真做不到的事：
 * <ul>
 *   <li><b>多端同看</b>：旧实现一个 runId 只留一条流，后订阅顶掉先订阅——两个人（或画布与历史页）
 *       同时看一次运行，先开那个会静默失去更新；</li>
 *   <li><b>跑到一半刷新</b>：旧实现的快照取自 run 行的 {@code node_traces}，而那一列<b>只在收口时写</b>，
 *       所以中途重连看到的是「状态在跑、时间线空白」；现在快照取自内存事件缓冲（本类 {@link Buffer}），
 *       已跑过的每个节点都回放得出来；</li>
 *   <li><b>重复事件</b>：总线给每帧带本轮单调序号，前端按快照序号去重（与问答链路同一条判据）。</li>
 * </ul>
 * <p>与问答的两处刻意不同：
 * <ul>
 *   <li><b>没有宽限期</b>：观众全走也不中止——一次运行是用户显式发起的任务，跑完才有结果，
 *       「没人看」不是中止的理由（{@code onGraceExpired} 恒 false）；</li>
 *   <li>不受 {@code chat.resumeEnabled} 影响：那个开关管的是问答「断开即中止」的行为回滚，
 *       工作流的流式进度没有旧行为可退。</li>
 * </ul>
 * <p>心跳：长 LLM 节点可能几分钟无事件，按 20s 一条 {@code ping} 防中间层按空闲断连；
 * 推送失败只摘通道，<b>绝不影响运行本身</b>。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class WorkflowRunStreamService {

    /** 心跳间隔：长节点执行期间防中间层空闲断连 */
    private static final long KEEPALIVE_MS = 20_000;
    /**
     * 事件缓冲的 trace 上限：够覆盖一次运行的节点数（画布再有并行分支也到不了几百），
     * 只为兜住「有人拿一个死循环工作流把内存垫高」这种异常形态。
     */
    private static final int BUFFER_MAX_TRACES = 400;

    private final ChatRunBus bus;

    /** runId 的总线键：与问答的 sessionId 同一张表，前缀隔开（工作流没有会话概念） */
    static String key(String runId) {
        return "wf:" + runId;
    }

    /** 本次运行的事件缓冲——接流者那份快照就从这里重建 */
    private static final class Buffer {
        private final Deque<Map<String, Object>> traces = new ArrayDeque<>();
        private volatile String status = "queued";
        private volatile String message;
        private volatile Map<String, Object> done;

        synchronized void addTrace(Map<String, Object> trace) {
            traces.addLast(trace);
            while (traces.size() > BUFFER_MAX_TRACES) traces.pollFirst();
        }

        synchronized List<Map<String, Object>> traces() {
            return new ArrayList<>(traces);
        }
    }

    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();

    private ScheduledExecutorService keepalive;

    public WorkflowRunStreamService(ChatRunBus bus) {
        this.bus = bus;
    }

    @PostConstruct
    void startKeepalive() {
        keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wf-stream-keepalive");
            t.setDaemon(true);
            return t;
        });
        keepalive.scheduleWithFixedDelay(this::tickKeepalive, KEEPALIVE_MS, KEEPALIVE_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopKeepalive() {
        if (keepalive != null) keepalive.shutdownNow();
        // 进程收尾：把还挂在总线上的 run 收掉，别给重启后的实例留一条没人认领的通道
        for (String k : new ArrayList<>(buffers.keySet())) {
            try {
                bus.finish(k);
            } catch (Exception ignored) {
                // 收尾路径不再抛
            }
        }
        buffers.clear();
    }

    private void tickKeepalive() {
        for (String k : new ArrayList<>(buffers.keySet())) {
            try {
                bus.publishToSession(k, "ping", "{}");
            } catch (Exception ex) {
                log.debug("[WORKFLOW] 运行流心跳失败（已忽略）: key={} {}", k, ex.getMessage());
            }
        }
    }

    /** 运行是否已终态（终态不再有增量事件） */
    public static boolean isTerminal(String status) {
        return "success".equals(status) || "failed".equals(status)
                || "timeout".equals(status) || "cancelled".equals(status);
    }

    /**
     * 开一次运行的流式登记：建缓冲并把这一 run 挂上总线。
     * <p>已经在总线上就不重开（审批续跑回到同一个 runId）：{@code open()} 会关掉旧 Run 并 complete
     * 它的所有通道，正开着的画布流会被无端掐断。
     */
    public void beginRun(String runId, String ownerUid) {
        if (runId == null || runId.isBlank()) return;
        String k = key(runId);
        buffers.computeIfAbsent(k, x -> new Buffer());
        if (bus.running(k) != null) return;
        bus.open(k, runId, ownerUid, null,
                () -> snapshot(k),
                () -> false,                                  // 观众走光不中止：任务要跑完
                () -> buffers.containsKey(k));                // 缓冲随收口清掉，巡检据此回收残留
    }

    /** 接流者的快照：此刻为止的状态、已产出的每个节点、以及收口那份（若已结束） */
    private Map<String, Object> snapshot(String k) {
        Map<String, Object> snap = new LinkedHashMap<>();
        Buffer b = buffers.get(k);
        snap.put("running", bus.running(k) != null);
        if (b == null) return snap;
        snap.put("status", b.status);
        if (b.message != null) snap.put("message", b.message);
        snap.put("traces", b.traces());
        if (b.done != null) snap.put("done", b.done);
        return snap;
    }

    /**
     * 订阅（或断线后重新接上）某次运行的进度流。
     *
     * @param runId   运行 id
     * @param status  订阅瞬间 run 行上的状态（没有在跑时用它回放）
     * @param traces  订阅瞬间 run 行上已落库的 trace（终态回放用）
     */
    public SseEmitter subscribe(String runId, String status, List<Map<String, Object>> traces) {
        SseEmitter em = new SseEmitter(0L);   // 不设超时：运行自身有 run 级硬超时兜底
        String k = key(runId);
        ChatRunBus.Run run = bus.running(k);
        if (run == null) {
            replayFromRunRow(em, k, status, traces);
            return em;
        }
        // 锁内「取快照 + 登记订阅者」：快照与后续增量的接缝是确定的，前端按 seq 丢重放
        Map<String, Object> snap = bus.attach(run, em);
        long seq = snap.get("seq") instanceof Number n ? n.longValue() : 0L;
        try {
            em.send(SseEmitter.event().name("snapshot")
                    .data(ChatRunBus.rawFrame("snapshot", JSON.toJSONString(snap), k, seq)));
        } catch (Exception e) {
            bus.drop(em);
            try {
                em.complete();
            } catch (Exception ignored) {
                // 通道已经断了
            }
        }
        return em;
    }

    /** 没有在跑（已结束、或服务重启过）：按 run 行一次性回放时间线后关闭——与旧行为同语义 */
    private void replayFromRunRow(SseEmitter em, String k, String status, List<Map<String, Object>> traces) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("running", false);
        snap.put("status", status);
        snap.put("traces", traces == null ? List.of() : traces);
        try {
            em.send(SseEmitter.event().name("snapshot").data(ChatRunBus.rawFrame("snapshot", JSON.toJSONString(snap), k, 0)));
            em.send(SseEmitter.event().name("done").data(ChatRunBus.rawFrame("done", JSON.toJSONString(snap), k, 0)));
            em.complete();
        } catch (Exception e) {
            try {
                em.complete();
            } catch (Exception ignored) {
                // 已关闭
            }
        }
    }

    /** 节点完成事件 */
    public void publishTrace(String runId, Map<String, Object> trace) {
        Buffer b = buffers.get(key(runId));
        if (b != null) b.addTrace(trace);
        bus.publishToSession(key(runId), "trace", JSON.toJSONString(trace));
    }

    /** 状态迁移事件（queued → running 等） */
    public void publishStatus(String runId, String status, String message) {
        Buffer b = buffers.get(key(runId));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        if (message != null) m.put("message", message);
        if (b != null) {
            b.status = status;
            b.message = message;
        }
        bus.publishToSession(key(runId), "status", JSON.toJSONString(m));
    }

    /** 收口事件：推终态 run 全量信息后关闭这条 run 的所有观众 */
    public void publishDone(String runId, WorkflowRun run) {
        String k = key(runId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", run.getStatus());
        m.put("runId", run.getId());
        m.put("outputs", run.getOutputs());
        m.put("error", run.getError());
        m.put("durationMs", run.getDurationMs());
        m.put("nodeTraces", run.getNodeTraces());
        Buffer b = buffers.get(k);
        if (b != null) {
            b.status = run.getStatus();
            b.done = m;
        }
        bus.publishToSession(k, "done", JSON.toJSONString(m));
        bus.finish(k);
        buffers.remove(k);   // 缓冲一清，总线巡检认这一轮已不在（turnAlive 的判据）
    }
}
