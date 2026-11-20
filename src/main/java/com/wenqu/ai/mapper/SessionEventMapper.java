package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.SessionEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话事件账本 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface SessionEventMapper extends BaseMapper<SessionEvent> {

    // ==================== 历史对话「回溯」（按过程事件检索，与框架 session_search 不同源） ====================

    /**
     * 在用户自己的会话事件账本里按关键词/类型检索（命中 {@code payload}），返回轻量行。
     * <p>只查 {@code kind != 'temp'} 的真实用户会话、会话侧过滤逻辑删除；按写入顺序倒序限量。
     * 关键词为空时退化为「最近的、该类型的事件」（LIKE '%%' 命中全部）。
     */
    @Select("<script>SELECT e.id AS id, e.session_id AS sessionId, e.turn_id AS turnId, "
            + "e.type AS type, e.payload AS payload, e.created_at AS createdAt, s.title AS title "
            + "FROM c_ai_session_event e JOIN c_ai_session s ON s.id = e.session_id "
            + "WHERE s.user_id = #{userId} AND s.deleted = 0 "
            + "AND (s.kind IS NULL OR s.kind != 'temp') "
            + "<if test='type != null and type != \"\"'>AND e.type = #{type} </if>"
            + "<if test='since != null'>AND e.created_at &gt;= #{since} </if>"
            + "AND e.payload LIKE CONCAT('%', #{keyword}, '%') "
            + "ORDER BY e.id DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> searchEventsByKeyword(
            @Param("userId") String userId, @Param("keyword") String keyword,
            @Param("type") String type, @Param("since") LocalDateTime since,
            @Param("limit") int limit);
}
