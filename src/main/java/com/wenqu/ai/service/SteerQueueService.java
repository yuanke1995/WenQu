package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.SteerQueueHooks;
import com.wenqu.ai.model.SteerItem;
import com.wenqu.ai.mapper.SteerItemMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运行中插话的待发队列：服务端持有、前端读投影。
 * <p><b>为什么不留在前端</b>：队列原来只活在前端内存，刷新即丢；「等这一轮开跑再送」也就只能是
 * 一个只在本页有效的承诺。
 * <p><b>为什么不会变成第二套轮次控制</b>：这里只做一件事——把排队的话 {@code steer} 进<b>已经活着的轮</b>，
 * 永不 {@code submit}。轮什么时候开跑由既有的用户轮/目标轮决定，队列只在「这一轮真的开跑」时被唤醒
 * （{@link SteerQueueHooks#drain(String)}）。
 * <p><b>状态机</b>：PENDING（排队）→ STEERED（已进框架 inbox，等模型听到）→ APPLIED（{@code inbox/applied} 到了）；
 * DROPPED 是用户删除或会话/目标终态收敛。所有流转都是 CAS，多副本同时 drain 只有一个能抢到同一行。
 */
@Slf4j
@Service
public class SteerQueueService implements SteerQueueHooks {

    /** 与 {@code /chat/steer} 的单条上限同一个尺子 */
    private static final int MAX_CHARS = 800;
    /** 一次 drain 最多送几条：队列是用户手打的短句，不该在一拍里刷出几十条 */
    private static final int MAX_DRAIN = 20;
    /** 抢到之后进程没了：超过这么久仍停在 STEERED 的行退回排队（可能就是重复一句的窗口） */
    private static final int STALE_SECONDS = 120;

    private final SteerItemMapper mapper;
    private final SessionService sessions;
    private final ChatRunRuntime runtime;

    public SteerQueueService(SteerItemMapper mapper, SessionService sessions, ChatRunRuntime runtime) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.runtime = runtime;
    }

    /** 入队：只落库，不发请求。图片按原始 data URL 存，排队期间刷新也能恢复并在送达时进模型。 */
    public Map<String, Object> enqueue(String sessionId, String userId, String text, List<String> images) {
        sessions.assertOwned(sessionId, userId);
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty()) throw new IllegalArgumentException("缺少插话内容");
        if (clean.length() > MAX_CHARS) clean = clean.substring(0, MAX_CHARS) + "…";
        SteerItem item = new SteerItem();
        item.setId(UUID.randomUUID().toString().replace("-", ""));
        item.setSessionId(sessionId);
        item.setUserId(userId);
        item.setText(clean);
        List<String> imgs = images == null ? List.of()
                : images.stream().filter(u -> u != null && u.startsWith("data:")).toList();
        item.setImages(imgs.isEmpty() ? null : JSON.toJSONString(imgs));
        item.setStatus("PENDING");
        item.setAttempts(0);
        // 显式给时间：插入回包要直接给前端排序用，DB 默认值不会回填到实体
        item.setCreatedAt(java.time.LocalDateTime.now());
        mapper.insert(item);
        log.info("[STEER-QUEUE] 入队: session={} item={} 字数={} 图={}", sessionId, item.getId(), clean.length(), imgs.size());
        return view(item);
    }

    /** 队列投影（刷新/换设备恢复）：还在排队与已送出等听到的都回。 */
    public List<Map<String, Object>> list(String sessionId, String userId) {
        sessions.assertOwned(sessionId, userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SteerItem it : mapper.pendingOf(sessionId)) {
            if (userId.equals(it.getUserId())) out.add(view(it));
        }
        return out;
    }

    /** 立即发送：抢这一行再送；送不进去退回排队并把原因写在行上（界面看得见，不静默吞）。 */
    public Map<String, Object> steerNow(String sessionId, String userId, String itemId) {
        sessions.assertOwned(sessionId, userId);
        SteerItem item = itemId == null ? null : mapper.byId(itemId);
        if (item == null || !sessionId.equals(item.getSessionId()) || !userId.equals(item.getUserId())) {
            return Map.of("accepted", false, "reason", "notFound");
        }
        if (!"PENDING".equals(item.getStatus())) {
            return Map.of("accepted", false, "reason", "notPending", "status", item.getStatus());
        }
        boolean sent = deliver(item, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", sent);
        out.put("reason", sent ? "" : "noLiveRun");
        return out;
    }

    /** 丢弃：只允许丢还在排队的项（已送出的等模型听到，不能假装撤回）。 */
    public boolean drop(String sessionId, String userId, String itemId) {
        sessions.assertOwned(sessionId, userId);
        return mapper.drop(itemId, userId, sessionId) > 0;
    }

    /**
     * 这一轮开跑了：把该会话排队的插话逐个送进去。
     * <p>由引擎在框架 {@code inbox/opened} 时调用——那一刻 run 才真的活着，早一步 steer 必被拒。
     */
    @Override
    public void drain(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        String uid = runtime.activeTurnOwner(sessionId);
        if (uid == null) return;
        // 崩溃窗口兜底：抢到之后进程没了、又没有回执的行，重新排队（宁可重发也不能静默丢）
        mapper.reviveStale(sessionId, STALE_SECONDS);
        List<SteerItem> items = mapper.pendingOf(sessionId);
        if (items.isEmpty()) return;
        log.info("[STEER-QUEUE] 本轮开跑，处理排队插话: session={} owner={} pending={}",
                sessionId, uid, items.size());
        int sent = 0;
        for (SteerItem it : items) {
            if (!"PENDING".equals(it.getStatus()) || !uid.equals(it.getUserId())) continue;
            if (sent >= MAX_DRAIN) break;
            if (deliver(it, uid)) sent++;
        }
        if (sent > 0) log.info("[STEER-QUEUE] 本轮开跑，已送达 {} 条排队插话: session={}", sent, sessionId);
    }

    /** 框架确认某条插话写进了上下文：按收据把行置 APPLIED。 */
    @Override
    public void applied(String sessionId, String receiptId) {
        if (receiptId == null || receiptId.isBlank()) return;
        try {
            mapper.applied(receiptId);
        } catch (RuntimeException e) {
            log.warn("[STEER-QUEUE] 标记已应用失败 receipt={}: {}", receiptId, e.getMessage());
        }
    }

    /** 会话/目标终态：还排着的项不能再假装「等轮开跑」。 */
    public int dropAllPending(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return 0;
        return mapper.dropAllPending(sessionId);
    }

    /** 抢行 → 送达；失败退回排队（CAS，多副本只有一个能送同一行）。 */
    private boolean deliver(SteerItem item, String uid) {
        if (mapper.claim(item.getId(), null) == 0) return false;
        try {
            ChatRunRuntime.SteerOutcome o = runtime.steerTurnDetailed(
                    item.getSessionId(), uid, item.getText(), imagesOf(item));
            if (o == null) {
                mapper.release(item.getId(), "这一轮不收插话");
                return false;
            }
            mapper.setReceipt(item.getId(), o.receiptId(), o.turnId());
            return true;
        } catch (RuntimeException e) {
            mapper.release(item.getId(), trim(e.getMessage()));
            log.warn("[STEER-QUEUE] 送达异常 item={}: {}", item.getId(), e.getMessage());
            return false;
        }
    }

    private static List<String> imagesOf(SteerItem item) {
        String raw = item.getImages();
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return JSON.parseArray(raw, String.class);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String trim(String s) {
        if (s == null) return "送达失败";
        return s.length() > 190 ? s.substring(0, 190) : s;
    }

    private static Map<String, Object> view(SteerItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("text", it.getText());
        m.put("images", imagesOf(it));
        m.put("status", it.getStatus());
        m.put("attempts", it.getAttempts() == null ? 0 : it.getAttempts());
        m.put("lastError", it.getLastError() == null ? "" : it.getLastError());
        m.put("createdAt", it.getCreatedAt() == null ? 0L : it.getCreatedAt().toInstant(java.time.ZoneOffset.UTC).toEpochMilli());
        return m;
    }
}
