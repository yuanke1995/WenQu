package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.SessionEventMapper;
import com.wenqu.ai.model.SessionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
