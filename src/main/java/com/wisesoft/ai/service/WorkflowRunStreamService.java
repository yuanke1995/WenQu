package com.wisesoft.ai.service;

import com.wisesoft.ai.model.WorkflowRun;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 工作流运行进度流（第 2 期：实时进度）。
 * <p>
 * 一次运行对应一条 SSE 流：节点完成 → {@code trace} 事件、状态迁移 → {@code status} 事件、
 * 收口 → {@code done} 事件（带终态 run 全量信息）后关闭。画布据此"边跑边亮"，不再等跑完批量回放。
 * <p>
 * 关键取舍：
 * <ul>
 *   <li><b>单订阅者</b>：一个 runId 只保留最新一条流（画布/历史页各自看各自的 run）；重复订阅顶掉旧的，
 *       不维护订阅者列表——多端同看同一 run 不是当前场景；</li>
 *   <li><b>不落服务端缓冲</b>：断线重连或迟到订阅时，用<b>订阅瞬间的 run 快照</b>（状态 + 已产生 trace）
 *       一次性回放；已终态则直接回放 + done 后关闭。前端不必维护"补发游标"；</li>
 *   <li><b>心跳</b>：长 LLM 节点可能几分钟无事件，20s 发一次注释行防中间层按空闲断连；</li>
 *   <li>推送失败只摘除该流，<b>绝不影响运行本身</b>（调用方在 trace 回调里已兜异常）。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class WorkflowRunStreamService {

    /** 心跳间隔：长节点执行期间防中间层空闲断连 */
    private static final long KEEPALIVE_MS = 20_000;

    /** runId → 当前订阅的流（单订阅者，后订阅顶掉先订阅） */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wf-stream-keepalive");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void startKeepalive() {
        keepalive.scheduleWithFixedDelay(this::tickKeepalive, KEEPALIVE_MS, KEEPALIVE_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopKeepalive() {
        keepalive.shutdownNow();
    }

    private void tickKeepalive() {
        for (Map.Entry<String, SseEmitter> e : emitters.entrySet()) {
            try {
                e.getValue().send(SseEmitter.event().comment("keepalive"));
            } catch (Exception ex) {
                emitters.remove(e.getKey(), e.getValue());
            }
        }
    }

    /** 运行是否已终态（终态不再有增量事件） */
    public static boolean isTerminal(String status) {
        return "success".equals(status) || "failed".equals(status)
                || "timeout".equals(status) || "cancelled".equals(status);
    }

    /**
     * 订阅某次运行的进度流。
     *
     * @param runId  运行 id
     * @param status 订阅瞬间的运行状态（快照）
     * @param traces 订阅瞬间已产生的节点 trace（快照；回放用，保证订阅前完成的节点不丢）
     */
    public SseEmitter subscribe(String runId, String status, List<Map<String, Object>> traces) {
        SseEmitter em = new SseEmitter(0L);   // 不设超时：运行自身有 run 级硬超时兜底
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status);
        snapshot.put("traces", traces == null ? List.of() : traces);
        if (isTerminal(status)) {
            // 已结束：一次性回放后关闭（断线重连 / 迟到订阅都能拿到完整时间线）
            try {
                em.send(SseEmitter.event().name("snapshot").data(snapshot));
                em.send(SseEmitter.event().name("done").data(snapshot));
                em.complete();
            } catch (Exception e) {
                try { em.complete(); } catch (Exception ignored) { /* 已关闭 */ }
            }
            return em;
        }
        SseEmitter prev = emitters.put(runId, em);
        if (prev != null) {
            try { prev.complete(); } catch (Exception ignored) { /* 顶掉旧流 */ }
        }
        em.onCompletion(() -> emitters.remove(runId, em));
        em.onTimeout(() -> emitters.remove(runId, em));
        em.onError(t -> emitters.remove(runId, em));
        try {
            em.send(SseEmitter.event().name("snapshot").data(snapshot));
        } catch (Exception e) {
            emitters.remove(runId, em);
        }
        return em;
    }

    /** 节点完成事件 */
    public void publishTrace(String runId, Map<String, Object> trace) {
        send(runId, "trace", trace);
    }

    /** 状态迁移事件（queued → running 等） */
    public void publishStatus(String runId, String status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        if (message != null) m.put("message", message);
        send(runId, "status", m);
    }

    /** 收口事件：推终态 run 全量信息后关闭该流（无订阅者时静默返回） */
    public void publishDone(String runId, WorkflowRun run) {
        SseEmitter em = emitters.remove(runId);
        if (em == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", run.getStatus());
        m.put("runId", run.getId());
        m.put("outputs", run.getOutputs());
        m.put("error", run.getError());
        m.put("durationMs", run.getDurationMs());
        m.put("nodeTraces", run.getNodeTraces());
        try {
            em.send(SseEmitter.event().name("done").data(m));
            em.complete();
        } catch (Exception e) {
            try { em.complete(); } catch (Exception ignored) { /* 已关闭 */ }
        }
    }

    private void send(String runId, String event, Object data) {
        SseEmitter em = emitters.get(runId);
        if (em == null) return;
        try {
            em.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            // 客户端断开：摘除该流即可，不影响运行
            emitters.remove(runId, em);
            log.debug("[WORKFLOW] 运行流推送失败（已摘除）：run={} event={} err={}", runId, event, e.getMessage());
        }
    }
}
