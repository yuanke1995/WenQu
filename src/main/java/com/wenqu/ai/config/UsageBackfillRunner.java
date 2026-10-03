package com.wenqu.ai.config;

import com.wenqu.ai.mapper.UsageLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 台账历史回补：把「消息时代」已落库的用量一次性搬进 c_ai_usage_log，保证统计切换数据源后
 * 历史不断档（否则切台账当天统计卡会清零，用户会以为数据丢了）。
 * <p>
 * 幂等守卫：回补行必带 message_id，表内已存在带 message_id 的行就不再回补。
 * <p>
 * 口径说明：回补的是每条回答当年记到的用量（旧口径只含主链路那一轮）， historical 数字
 * 因此偏小于真实账单——这是既往记账缺失的如实结果，不是新链路的误差；新产生的用量都是全轮
 * 逐个请求记账，二者在页面上是相加的连续序列。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(200)
public class UsageBackfillRunner implements ApplicationRunner {

    private final UsageLogMapper usageLogMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 一条回答一行：题选自 assistants 消息里带合法 tokens JSON 的行，
     * uid 随会话归属；model 为空（模型字段上线前）则如实留 NULL。
     */
    private static final String BACKFILL_SQL =
            "INSERT INTO c_ai_usage_log (id, uid, session_id, message_id, model, kind, "
                    + "prompt_tokens, completion_tokens, cached_tokens, total_tokens, create_time) "
                    + "SELECT REPLACE(UUID(), '-', ''), s.user_id, m.session_id, m.id, m.model, 'chat', "
                    + "  CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.tokens, '$.prompt')), '0') AS UNSIGNED), "
                    + "  CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.tokens, '$.output')), '0') AS UNSIGNED), "
                    + "  CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.tokens, '$.cached')), '0') AS UNSIGNED), "
                    + "  CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.tokens, '$.total')), '0') AS UNSIGNED), "
                    + "  m.create_time "
                    + "FROM c_ai_message m JOIN c_ai_session s ON m.session_id = s.id "
                    + "WHERE m.deleted = 0 AND s.deleted = 0 AND m.role = 'assistant' "
                    + "  AND m.tokens IS NOT NULL AND JSON_VALID(m.tokens) "
                    + "  AND CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(m.tokens, '$.total')), '0') AS UNSIGNED) > 0 "
                    + "  AND s.user_id IS NOT NULL AND s.user_id <> 'anonymous'";

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (usageLogMapper.countBackfilled() > 0) {
                log.info("[UsageBackfill] 台账已含历史数据，跳过回补");
                return;
            }
            int n = jdbcTemplate.update(BACKFILL_SQL);
            log.info("[UsageBackfill] 历史用量回补完成：{} 条回答转为台账流水", n);
        } catch (Exception e) {
            // 回补失败不能阻塞启动（台账本身的实时记账不受影响），但必须显形
            log.warn("[UsageBackfill] 历史用量回补失败（不影响新用量记账）: {}", e.getMessage());
        }
    }
}
