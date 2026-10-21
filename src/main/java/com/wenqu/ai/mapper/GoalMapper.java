package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.Goal;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 目标模式数据访问。聚合查询（token 账）走 {@code @Select}：本仓 mapper 无 XML，
 * 与 {@code UsageLogMapper} 同一形状（返回 Map 列表，交由 service 组装）。
 */
public interface GoalMapper extends BaseMapper<Goal> {

    /**
     * 一个会话在目标运行期内的模型用量（按 kind 聚合，含 chat/compress/subagent）。
     * <p>为什么必须含 compress 与 subagent：压缩摘要与子智能体分支都花的是这个目标的token，
     * 只数 chat 会让预算账比真实成本小一大截（deepseek-flash 恒思考那条对账纪律同源）。
     *
     * @param sessionId 会话
     * @param since     起始时刻（目标 startedAt；NULL=不限）
     * @return [{kind, tokens}]；空列表=还没有用量
     */
    @Select({
            "<script>",
            "SELECT kind, COALESCE(SUM(total_tokens), 0) AS tokens",
            "FROM c_ai_usage_log",
            "WHERE session_id = #{sessionId}",
            "  AND kind IN ('chat', 'compress', 'subagent')",
            "<if test='since != null'> AND create_time &gt;= #{since}</if>",
            "GROUP BY kind",
            "</script>"
    })
    List<Map<String, Object>> sumSessionTokens(@Param("sessionId") String sessionId,
                                               @Param("since") java.time.LocalDateTime since);
}
