package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.Message;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * AI 消息 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /** 按 ID 查询（忽略逻辑删除标记，撤销删除时定位已软删消息用；自定义 SQL 不经 @TableLogic 改写） */
    @Select("SELECT * FROM c_ai_message WHERE id = #{id}")
    Message selectByIdIgnoreDeleted(@Param("id") String id);

    /** 按会话+序号+角色查询（忽略逻辑删除，撤销删除时配对同组用户问题用） */
    @Select("SELECT * FROM c_ai_message WHERE session_id = #{sessionId} AND sequence = #{seq} AND role = #{role} " +
            "ORDER BY create_time DESC LIMIT 1")
    Message selectBySeqIgnoreDeleted(@Param("sessionId") String sessionId,
                                       @Param("seq") int seq,
                                       @Param("role") String role);

    /** 恢复单条软删除消息（撤销删除用） */
    @Update("UPDATE c_ai_message SET deleted = 0 WHERE id = #{id}")
    int restoreById(@Param("id") String id);

    /** 会话内最大序号（物理查询：含已软删行）。删除轮次后序号不复用，撤销按 seq-1 精确配对依赖序号单调唯一 */
    @Select("SELECT COALESCE(MAX(sequence), 0) FROM c_ai_message WHERE session_id = #{sessionId}")
    int maxSequencePhysical(@Param("sessionId") String sessionId);

    /** 批量查询已存在消息 ID（忽略逻辑删除：软删消息仍在撤销窗口内，降级补写不得用同 ID 重插撞主键） */
    @Select("<script>SELECT id FROM c_ai_message WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    java.util.List<String> selectExistingIdsIncludingDeleted(@Param("ids") java.util.Collection<String> ids);

    /** 物理删除逻辑删除标记且创建时间早于截止时间的消息（过期数据清理；物理清除即"撤销删除"窗口终点，幂等） */
    @Delete("DELETE FROM c_ai_message WHERE deleted = 1 AND create_time < #{cutoff}")
    int purgeDeletedOlderThan(@Param("cutoff") LocalDateTime cutoff);

    // ==================== 使用统计聚合（按用户，经会话表过滤归属） ====================
    // tokens 为 JSON 字符串，总额取 $.total（缺失/非法 JSON 行被 JSON_VALID 与 CAST NULL 自然剔除）。
    // 自定义 SQL 不经 @TableLogic 改写，逻辑删除必须显式过滤（会话与消息两侧都要）。

    /** 每日 token 总量（role=assistant 且带合法 tokens；since 为 null 则不限时间，全量日清单供峰值/热力图共用） */
    @Select("<script>SELECT DATE_FORMAT(m.create_time, '%Y-%m-%d') AS d, "
            + "SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(m.tokens, '$.total')) AS UNSIGNED)) AS t "
            + "FROM c_ai_message m JOIN c_ai_session s ON m.session_id = s.id "
            + "WHERE s.user_id = #{userId} AND s.deleted = 0 AND m.deleted = 0 "
            + "AND m.role = 'assistant' AND m.tokens IS NOT NULL AND JSON_VALID(m.tokens) "
            + "<if test='since != null'>AND m.create_time &gt;= #{since}</if> "
            + "GROUP BY d ORDER BY d</script>")
    java.util.List<java.util.Map<String, Object>> statDailyTokens(@Param("userId") String userId,
                                                                  @Param("since") LocalDateTime since);

    /** 每日×模型 token 总量（趋势图与模型用量占比共用；model 为 NULL 的存量行原样返回，展示层归入「未知」） */
    @Select("SELECT DATE_FORMAT(m.create_time, '%Y-%m-%d') AS d, m.model AS model, "
            + "SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(m.tokens, '$.total')) AS UNSIGNED)) AS t "
            + "FROM c_ai_message m JOIN c_ai_session s ON m.session_id = s.id "
            + "WHERE s.user_id = #{userId} AND s.deleted = 0 AND m.deleted = 0 "
            + "AND m.role = 'assistant' AND m.tokens IS NOT NULL AND JSON_VALID(m.tokens) "
            + "AND m.create_time >= #{since} "
            + "GROUP BY d, model ORDER BY d")
    java.util.List<java.util.Map<String, Object>> statModelDaily(@Param("userId") String userId,
                                                                 @Param("since") LocalDateTime since);

    /** 有回答消息的活跃日期清单（全量，行数≤活跃天数，连续天数计算的原始数据） */
    @Select("SELECT DISTINCT DATE_FORMAT(m.create_time, '%Y-%m-%d') AS d "
            + "FROM c_ai_message m JOIN c_ai_session s ON m.session_id = s.id "
            + "WHERE s.user_id = #{userId} AND s.deleted = 0 AND m.deleted = 0 "
            + "AND m.role = 'assistant' ORDER BY d")
    java.util.List<String> statActiveDates(@Param("userId") String userId);

    /** 最长单会话时长（秒）：会话内首末消息时间差的最大值（跨天挂着的会话按真实跨度计） */
    @Select("SELECT COALESCE(MAX(t.span), 0) FROM ("
            + "SELECT TIMESTAMPDIFF(SECOND, MIN(m.create_time), MAX(m.create_time)) AS span "
            + "FROM c_ai_message m JOIN c_ai_session s ON m.session_id = s.id "
            + "WHERE s.user_id = #{userId} AND s.deleted = 0 AND m.deleted = 0 "
            + "GROUP BY m.session_id) t")
    Long statLongestSessionSeconds(@Param("userId") String userId);
}