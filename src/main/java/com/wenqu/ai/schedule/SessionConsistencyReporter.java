package com.wenqu.ai.schedule;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 会话消息对账：只报不修。
 *
 * <p>消息写入改为「MySQL 唯一事实源」后，仍然需要一条独立链路检查历史漂移：
 * {@code c_ai_session.message_count} 是消息序号高水位（append 时取物理 max+1），
 * 与 {@code c_ai_message.sequence} 的最大值应一致。不一致说明过去某次写入/删除链路
 * 只改了其中一边——报告出来由人来查，不在这里自动补写（自动修会把一次漂移变成两次）。
 *
 * <p>跑在无租户身份的系统线程里，按全租户扫描；只取最近一天有更新的会话，避免全表扫。
 */
@Slf4j
@Component
public class SessionConsistencyReporter {

    private static final String SQL = """
            select s.id as sessionId, s.tenant_id as tenantId,
                   s.message_count as messageCount,
                   coalesce(max(m.`sequence`), 0) as actualSeq
            from c_ai_session s
            left join c_ai_message m on m.session_id = s.id
            where s.deleted = 0 and s.update_time >= now() - interval 1 day
            group by s.id, s.tenant_id, s.message_count
            having coalesce(s.message_count, 0) <> coalesce(max(m.`sequence`), 0)
            limit 50
            """;

    private final JdbcTemplate jdbc;

    public SessionConsistencyReporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void report() {
        try {
            List<Map<String, Object>> mismatches = jdbc.queryForList(SQL);
            if (!mismatches.isEmpty()) {
                log.error("[ALERT][CONSISTENCY] 会话消息高水位不一致 {} 条（只报不修）: {}",
                        mismatches.size(), mismatches);
            }
        } catch (Exception e) {
            log.error("[ALERT][CONSISTENCY] 会话消息对账任务执行失败", e);
        }
    }
}
