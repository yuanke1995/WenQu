package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.UsageLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 推理用量台账：写入（BaseMapper）+ 统计聚合。<p>
 * 所有账目口径均取自网关 usage（spring 侧累加前的 native 原始值），
 * 与供应商侧计费总量一致。
 */
public interface UsageLogMapper extends BaseMapper<UsageLog> {

    /** 每日总量清单（uid=null 视为系统侧不入个人统计；since 为空则全量） */
    @Select("<script>SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d, "
            + "SUM(total_tokens) AS t, COUNT(*) AS c "
            + "FROM c_ai_usage_log "
            + "WHERE uid = #{uid} "
            + "<if test='since != null'>AND create_time &gt;= #{since}</if> "
            + "GROUP BY d ORDER BY d</script>")
    List<Map<String, Object>> statDailyTokens(@Param("uid") String uid,
                                              @Param("since") LocalDateTime since);

    /** 每日×模型（近 N 日趋势与模型占比共用；模型为 NULL 归入 NULL key，聚合层映射为「未记录」） */
    @Select("<script>SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d, model AS model, "
            + "SUM(total_tokens) AS t FROM c_ai_usage_log "
            + "WHERE uid = #{uid} "
            + "<if test='since != null'>AND create_time &gt;= #{since}</if> "
            + "GROUP BY d, model ORDER BY d</script>")
    List<Map<String, Object>> statModelDaily(@Param("uid") String uid,
                                             @Param("since") LocalDateTime since);

    /** 活跃日期清单（统计卡连续天数：以「当天有用量」为准，比「当天有回答」更贴合实际使用） */
    @Select("SELECT DISTINCT DATE_FORMAT(create_time, '%Y-%m-%d') AS d FROM c_ai_usage_log "
            + "WHERE uid = #{uid} ORDER BY d")
    List<String> statActiveDates(@Param("uid") String uid);

    /** 台账是否已包含历史回补数据（回补幂等守卫：回补行必带 message_id） */
    @Select("SELECT COUNT(*) FROM c_ai_usage_log WHERE message_id IS NOT NULL")
    int countBackfilled();
}
