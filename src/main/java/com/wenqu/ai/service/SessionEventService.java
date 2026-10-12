package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.SessionEventMapper;
import com.wenqu.ai.model.Message;
import com.wenqu.ai.model.SessionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话事件账本（结构性事件的旁路写入）。
 * <p>
 * 为什么要单独一张表：一轮问答的真实执行是「检索 → 工具若干步 → 人在回路裁决 → 生成 → 终态」，
 * 而这些过程此前只活在两条地方——SSE 流（断开即蒸发）和助手消息里的 JSON blob（只有终态快照，
 * 没有先后与耗时口径）。有了按写入顺序可枚举的事件行，停止/断线重连/过程回放才有共同的底座可挂。
 * <p>
 * 边界（刻意的）：<b>只服务回放与审计，不进模型输入</b>；正文与 token/thinking/plan_delta 这类
 * 高频增量不落本表，仍由消息表承担。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionEventService {

    /** 载荷摘要上限（与审批入参同量级：审计够用，又不至于让账本行比消息本身还大） */
    private static final int PAYLOAD_MAX = 4000;

    private final SessionEventMapper sessionEventMapper;
    private final MessageMapper messageMapper;
    private final ConfigService configService;

    /**
     * 记一条事件。<b>写失败绝不影响问答</b>：吞掉异常只留一行 warn（与产物、站内通知那类旁路同口径）。
     * <p>开关 {@code trace.sessionEventEnabled} 默认开；关掉后一行都不写，行为回到有账本之前。
     */
    public void append(String sessionId, String userId, String turnId, String type, String payload) {
        if (sessionId == null || sessionId.isBlank() || type == null || type.isBlank()) return;
        if (!configService.getBoolean("trace.sessionEventEnabled", true)) return;
        try {
            SessionEvent event = new SessionEvent();
            event.setSessionId(sessionId);
            event.setUserId(userId);
            event.setTurnId(turnId);
            event.setType(type);
            event.setPayload(payload == null || payload.length() <= PAYLOAD_MAX
                    ? payload : payload.substring(0, PAYLOAD_MAX) + "…");
            event.setCreatedAt(LocalDateTime.now());
            sessionEventMapper.insert(event);
        } catch (Exception e) {
            log.warn("[EVENT] 会话事件落库失败（不影响问答）: type={} session={} {}", type, sessionId, e.getMessage());
        }
    }

    /**
     * 读一个会话最近的事件（过程回放的取数）：按写入顺序<b>倒序</b>取，调用方再自行分组/翻正。
     * <p>归属只认 {@code user_id}——账本按人隔离，别人的会话查不到（连「有没有事件」都不告诉）。
     * 一期这张表只写不读，界面上「这一轮怎么跑出来的」只能靠通知与产物倒推；有了读口才能做回放。
     */
    public java.util.List<SessionEvent> listRecent(String sessionId, String userId, int limit) {
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()) {
            return java.util.List.of();
        }
        int cap = limit <= 0 ? 200 : Math.min(limit, 500);
        try {
            return sessionEventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                    .eq(SessionEvent::getSessionId, sessionId)
                    .eq(SessionEvent::getUserId, userId)
                    .orderByDesc(SessionEvent::getId)
                    .last("limit " + cap));
        } catch (Exception e) {
            log.warn("[EVENT] 会话事件读取失败 session={}: {}", sessionId, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * 读<b>某一轮</b>的事件（按写入顺序<b>正序</b>，即发生顺序）：逐气泡「过程」的取数口径。
     * <p>与 {@link #listRecent} 的分工：那个是「这个会话最近跑过哪几轮」的会话级回放，
     * 这个是「这一条回答是怎么跑出来的」——消息行带着 turnId，按它精确取本行的那一轮，
     * 不用先扫 200 条再分组（用户点的是这一条，不是整个会话）。
     * <p>归属同样只认 {@code user_id}：别人的轮次查不到，也不告诉查不查得到。
     */
    public java.util.List<SessionEvent> listByTurn(String sessionId, String userId, String turnId, int limit) {
        if (sessionId == null || sessionId.isBlank() || userId == null || userId.isBlank()
                || turnId == null || turnId.isBlank()) {
            return java.util.List.of();
        }
        int cap = limit <= 0 ? 200 : Math.min(limit, 500);
        try {
            return sessionEventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                    .eq(SessionEvent::getSessionId, sessionId)
                    .eq(SessionEvent::getUserId, userId)
                    .eq(SessionEvent::getTurnId, turnId)
                    .orderByAsc(SessionEvent::getId)
                    .last("limit " + cap));
        } catch (Exception e) {
            log.warn("[EVENT] 按轮读取会话事件失败 session={} turn={}: {}", sessionId, turnId, e.getMessage());
            return java.util.List.of();
        }
    }

    /** 问题行落库在那一轮第一条事件<b>之前</b>最多容许的间隔（前置阶段：改写/检索/思考都要时间） */
    private static final Duration BEFORE_FIRST_EVENT = Duration.ofMinutes(2);
    /** 回答行在最后一条事件<b>之后</b>最多容许的间隔（落库要走完引用自检与图片过滤） */
    private static final Duration AFTER_LAST_EVENT = Duration.ofMinutes(2);

    /**
     * 启动时按开关跑一次存量轮身份回填（默认关——见 {@code trace.turnIdBackfill} 的说明）。
     * <p>为什么是启动跑而不是随时点：这件事只针对「账本上线之前」那批消息，是一次性的补账动作，
     * 不值得为它做一个带权限的运维端点；开关关掉后重启即不再扫。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillTurnIdsIfEnabled() {
        if (!configService.getBoolean("trace.turnIdBackfill", false)) return;
        try {
            Map<String, Object> r = backfillTurnIds(false, 500);
            log.info("[回填] 存量消息轮身份补写完成：会话 {} 个 · 轮窗口 {} 个 · 补 {} 行 · 因二义跳过 {} 个。"
                            + "这是一次性动作，请在系统设置里把「存量消息补轮身份」关回。",
                    r.get("sessions"), r.get("windows"), r.get("filled"), r.get("skipped"));
        } catch (Exception e) {
            log.warn("[回填] 存量消息轮身份补写失败（不影响问答）: {}", e.getMessage());
        }
    }

    /**
     * 给<b>账本上线之前</b>的历史消息补轮身份（{@code c_ai_message.turn_id} 为 NULL 的那些行）。
     * <p>能锚定的只有事件行的 {@code (turn_id, created_at)}——账本不存消息 id，也不存问题原文，
     * 所以判据收紧成「一个轮窗口里恰好只对上一问一答」：窗口内出现第二条问题或第二条回答就整窗跳过
     * （一问多答的重新生成、编辑重发、一轮多问都会撞出二义）。<b>挂错轮比不挂更坏</b>：
     * 那时用户点「看这一轮的过程」看到的是别的轮的账本，回放功能最难查的错就是这个——界面上什么都不缺。
     * <p>可补的量本来就小：账本自 2026-10-09 才开写，消息行的 turn_id 列 10-10 上线，
     * 两者的交集还要没被 30 天保留期清掉。这也是它默认关、而不是做成长期任务的原因。
     *
     * @param dryRun       true=只统计不写库（先看清能补多少）
     * @param sessionLimit 最多处理几个会话（0=默认 500，上限 5000）
     * @return {sessions, windows, filled, skipped, dryRun}
     */
    public Map<String, Object> backfillTurnIds(boolean dryRun, int sessionLimit) {
        int cap = sessionLimit <= 0 ? 500 : Math.min(sessionLimit, 5000);
        Map<String, Object> out = new LinkedHashMap<>();
        int sessions = 0, windows = 0, filled = 0, skipped = 0;
        try {
            List<String> sessionIds = distinctSessionsWithTurns(cap);
            for (String sessionId : sessionIds) {
                sessions++;
                Map<String, LocalDateTime[]> byTurn = turnWindows(sessionId);
                if (byTurn.isEmpty()) continue;
                List<Message> nulls = messagesWithoutTurn(sessionId);
                if (nulls.isEmpty()) continue;
                Set<String> claimed = new LinkedHashSet<>();
                for (Map.Entry<String, LocalDateTime[]> e : byTurn.entrySet()) {
                    windows++;
                    LocalDateTime from = e.getValue()[0].minus(BEFORE_FIRST_EVENT);
                    LocalDateTime to = e.getValue()[1].plus(AFTER_LAST_EVENT);
                    List<Message> asks = new ArrayList<>();
                    List<Message> answers = new ArrayList<>();
                    for (Message m : nulls) {
                        if (claimed.contains(m.getId()) || m.getCreateTime() == null) continue;
                        if (m.getCreateTime().isBefore(from) || m.getCreateTime().isAfter(to)) continue;
                        if ("user".equals(m.getRole())) asks.add(m);
                        else if ("assistant".equals(m.getRole()) || "ai".equals(m.getRole())) answers.add(m);
                    }
                    if (asks.size() != 1 || answers.size() > 1) {
                        skipped++;   // 二义：这一窗对不上唯一的一问
                        continue;
                    }
                    claimed.add(asks.get(0).getId());
                    if (!answers.isEmpty()) claimed.add(answers.get(0).getId());
                    if (dryRun) {
                        filled += 1 + answers.size();
                        continue;
                    }
                    for (Message m : answers) {
                        if (writeTurnId(m.getId(), e.getKey())) filled++;
                    }
                    if (writeTurnId(asks.get(0).getId(), e.getKey())) filled++;
                }
            }
        } catch (Exception e) {
            log.warn("[回填] 存量轮身份补写中断（已补 {} 行）: {}", filled, e.getMessage());
        }
        out.put("sessions", sessions);
        out.put("windows", windows);
        out.put("filled", filled);
        out.put("skipped", skipped);
        out.put("dryRun", dryRun);
        return out;
    }

    /** 有账本事件的会话（按会话去重，最多取 cap 个） */
    private List<String> distinctSessionsWithTurns(int cap) {
        return sessionEventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                        .select(SessionEvent::getSessionId)
                        .isNotNull(SessionEvent::getTurnId)
                        .groupBy(SessionEvent::getSessionId)
                        .orderByAsc(SessionEvent::getSessionId)
                        .last("limit " + cap))
                .stream().map(SessionEvent::getSessionId).filter(java.util.Objects::nonNull).toList();
    }

    /** 该会话每一轮的时间窗（[首条事件, 末条事件]，按写入顺序而非时间戳定首末） */
    private Map<String, LocalDateTime[]> turnWindows(String sessionId) {
        Map<String, LocalDateTime[]> win = new LinkedHashMap<>();
        for (SessionEvent ev : sessionEventMapper.selectList(new LambdaQueryWrapper<SessionEvent>()
                .eq(SessionEvent::getSessionId, sessionId)
                .isNotNull(SessionEvent::getTurnId)
                .orderByAsc(SessionEvent::getId))) {
            if (ev.getTurnId() == null || ev.getTurnId().isBlank() || ev.getCreatedAt() == null) continue;
            LocalDateTime[] pair = win.get(ev.getTurnId());
            if (pair == null) win.put(ev.getTurnId(), new LocalDateTime[]{ev.getCreatedAt(), ev.getCreatedAt()});
            else pair[1] = ev.getCreatedAt();
        }
        return win;
    }

    /** 该会话还没有轮身份的消息行（按写入顺序） */
    private List<Message> messagesWithoutTurn(String sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, sessionId)
                .isNull(Message::getTurnId)
                .orderByAsc(Message::getSequence));
    }

    private boolean writeTurnId(String messageId, String turnId) {
        try {
            return messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                    .eq(Message::getId, messageId)
                    .isNull(Message::getTurnId)          // 只补没挂过的行：挂过的轮身份是当轮写的，比推断可信
                    .set(Message::getTurnId, turnId)) > 0;
        } catch (Exception e) {
            log.warn("[回填] 单行补写失败 message={}: {}", messageId, e.getMessage());
            return false;
        }
    }

    /**
     * 删除超过保留期的事件（≤0 = 不清理）。
     * <p>账本比会话表长得快——一轮十几条，只写不删迟早吃掉存储；与「过期会话/消息清理」挂同一个周期任务。
     * 会话被硬删后其事件会留到保留期结束（不做联表级联删）：这些行按 session_id 归属，
     * 保留期一过同样消失，代价只是窗口期内多存一段已删会话的过程记录。
     */
    public int purgeExpired(int retentionDays) {
        if (retentionDays <= 0) return 0;
        try {
            int n = sessionEventMapper.delete(new LambdaQueryWrapper<SessionEvent>()
                    .lt(SessionEvent::getCreatedAt, LocalDateTime.now().minusDays(retentionDays)));
            if (n > 0) {
                log.info("[CLEANUP] 超期会话事件已清理 {} 条（保留 {} 天）", n, retentionDays);
            }
            return n;
        } catch (Exception e) {
            log.warn("[CLEANUP] 超期会话事件清理失败: {}", e.getMessage());
            return 0;
        }
    }
}
