package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.service.ReplicaCoordination;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 框架会话的<b>跨副本协调</b>（Phase 0 · P2）：三件事外置，实例本体不动。
 *
 * <ul>
 *   <li><b>实例租约</b> {@code wenqu:agentx:held:{会话键}}：常驻的 {@code HarnessAgent}/{@code AgentSession}
 *       绑在跑出它的那个进程（框架实例带着工作区、传输层与会话写租约，两个进程同时持同一条会话必然撞
 *       "Session already has an active writer"）。租约让别的副本知道「这条会话归他管」。</li>
 *   <li><b>跟随位</b> {@code wenqu:agentx:follow:{会话键}}：一条会话同时只许有一个「把这一轮跟到收尾并落库」的循环。
 *       原来是进程内 {@code putIfAbsent}，多副本各占各的 ⇒ 两个循环对着同一条会话、各落一条助手消息。</li>
 *   <li><b>答复转投</b> {@code wenqu:agentx:answer}：人在回路的卡片（确认/提问/计划）可以在<b>任意</b>副本被答复；
 *       唤醒句柄在持有实例的那个进程里，所以答复要转投给持有者执行，而不是在本地重建第二个实例。</li>
 * </ul>
 *
 * <p>待办归属本身以 {@code c_ai_tool_approval} 为真相源（{@code HitlDecisionService.findApproval} 能按 id
 * 取回 uid/sessionId/turnId），所以这里<b>不再加一份 Redis 归属表</b>：本进程的 {@code pendingOwner}
 * 只是快路径，查不到就问库——多出来的这份真相源依赖是刻意的，重复登记只会漂移。
 *
 * <p>Redis 不可用时租约判据一律「没人持有」，本副本按单副本语义继续跑；不伪造「归我」，
 * 否则两个副本会同时认为自己持有同一条会话。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class AgentxCluster {

    private static final String HELD_KEY = "wenqu:agentx:held:";
    private static final String FOLLOW_KEY = "wenqu:agentx:follow:";
    private static final String CH_ANSWER = "wenqu:agentx:answer";
    private static final String CH_ANSWER_REP = "wenqu:agentx:answer:rep:";

    /** 实例租约存活时间：每次 open/接管都续；进程没了最迟这么久别人可接管重建 */
    private static final Duration HELD_TTL = Duration.ofMinutes(30);
    /** 跟随位存活时间：跟的是「一轮跑到收尾」，取问答墙钟上限的上一档，退出时显式释放 */
    private static final Duration FOLLOW_TTL = Duration.ofMinutes(30);
    /** 转投答复的等待上限：持有者就在同一台 Redis 上，正常是毫秒级；超时按「没送达到」如实报错 */
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(5);

    private final ReplicaCoordination coord;
    /** kind → 持有者侧执行方（由 FrameworkSessions 装配） */
    private final Map<String, java.util.function.Function<JSONObject, Boolean>> answerHandlers = new ConcurrentHashMap<>();
    private volatile String myReplyChannel;

    public AgentxCluster(ReplicaCoordination coord) {
        this.coord = coord;
    }

    @PostConstruct
    void start() {
        myReplyChannel = coord.replyChannel(CH_ANSWER_REP);
        coord.listen(CH_ANSWER, this::onAnswer);
    }

    public String replicaId() {
        return coord.replicaId();
    }

    /** 装配一种答复的执行方（approval/ask/plan）；同 kind 重复注册说明分派写重了 */
    public void bindAnswer(String kind, java.util.function.Function<JSONObject, Boolean> handler) {
        if (answerHandlers.putIfAbsent(kind, handler) != null) {
            throw new IllegalStateException("答复 kind 已有执行方: " + kind);
        }
    }

    // ==================== 实例租约 ====================

    /** 本副本建好/接管了这条会话的实例：占住租约（覆盖写，重建不换位） */
    public void claimHeld(String sessionKey) {
        coord.claim(HELD_KEY + sessionKey, HELD_TTL);
    }

    public void releaseHeld(String sessionKey) {
        coord.release(HELD_KEY + sessionKey);
    }

    /** 谁持有这条会话的实例；null=没人（含 Redis 不可用） */
    public String heldBy(String sessionKey) {
        return sessionKey == null ? null : coord.holder(HELD_KEY + sessionKey);
    }

    /** 别的副本正持有这条会话的实例 */
    public boolean heldElsewhere(String sessionKey) {
        String holder = heldBy(sessionKey);
        return holder != null && !coord.replicaId().equals(holder);
    }

    // ==================== 跟随位 ====================

    /** 抢到跟随位才该起一轮续跑；抢不到=已有人在跟（可能在本进程，也可能在别的副本） */
    public boolean acquireFollow(String sessionKey) {
        return sessionKey != null && coord.acquire(FOLLOW_KEY + sessionKey, FOLLOW_TTL);
    }

    /** 新一轮的提问者就是权威跟随者：强制占位，覆盖上一轮还没让位的跟随 */
    public void claimFollow(String sessionKey) {
        if (sessionKey != null) coord.claim(FOLLOW_KEY + sessionKey, FOLLOW_TTL);
    }

    public void releaseFollow(String sessionKey) {
        if (sessionKey != null) coord.release(FOLLOW_KEY + sessionKey);
    }

    /** 此刻是否有人（本副本或别的副本）在跟这一轮 */
    public boolean followHeld(String sessionKey) {
        return sessionKey != null && coord.held(FOLLOW_KEY + sessionKey);
    }

    // ==================== 答复转投 ====================

    /**
     * 把一次人工裁决转投给持有实例的副本执行。
     *
     * @return true=持有者确认已送达并执行；false=没有持有者、送达超时或持有者执行失败
     */
    public boolean forwardAnswer(String sessionKey, String kind, String requestId,
                                 boolean approved, String reason, String answer, String outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionKey", sessionKey);
        payload.put("kind", kind);
        payload.put("requestId", requestId);
        payload.put("approved", approved);
        payload.put("reason", reason == null ? "" : reason);
        payload.put("answer", answer == null ? "" : answer);
        payload.put("outcome", outcome == null ? "" : outcome);
        payload.put("_replyTo", myReplyChannel);
        JSONObject reply = coord.request(CH_ANSWER, payload, ANSWER_TIMEOUT);
        if (reply == null) {
            log.warn("[AGENTX] 答复转投无回话: kind={} id={} host={}", kind, requestId, heldBy(sessionKey));
            return false;
        }
        return Boolean.TRUE.equals(reply.getBoolean("ok"));
    }

    private void onAnswer(JSONObject req, String from) {
        String sessionKey = req.getString("sessionKey");
        if (!coord.replicaId().equals(heldBy(sessionKey))) {
            return;   // 不是我持有的实例，不回话（发起方按无持有者处理，走重建接续）
        }
        java.util.function.Function<JSONObject, Boolean> handler = answerHandlers.get(req.getString("kind"));
        if (handler == null) {
            log.warn("[AGENTX] 收到没人认领的答复类型: {}", req.getString("kind"));
            return;
        }
        boolean ok;
        try {
            ok = Boolean.TRUE.equals(handler.apply(req));
        } catch (Exception e) {
            log.warn("[AGENTX] 持有者执行答复失败: kind={} id={} {}",
                    req.getString("kind"), req.getString("requestId"), e.toString());
            ok = false;
        }
        coord.respond(req, Map.of("ok", ok));
    }
}
