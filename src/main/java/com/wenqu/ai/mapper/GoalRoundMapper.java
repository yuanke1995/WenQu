package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.GoalRound;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 目标轮次数据访问。除 BaseMapper 外只加两条按目标/轮号的定位查询：
 * 收尾与恢复都先按 {@code (goal_id, n)} 或 {@code turn_id} 找那一行，避免全表扫。
 */
public interface GoalRoundMapper extends BaseMapper<GoalRound> {

    @Select("SELECT * FROM c_ai_goal_round WHERE goal_id = #{goalId} AND n = #{n} LIMIT 1")
    GoalRound byGoalAndN(@Param("goalId") String goalId, @Param("n") int n);

    @Select("SELECT * FROM c_ai_goal_round WHERE turn_id = #{turnId} ORDER BY n DESC LIMIT 1")
    GoalRound byTurnId(@Param("turnId") String turnId);

    @Select("SELECT * FROM c_ai_goal_round WHERE goal_id = #{goalId} ORDER BY n ASC")
    List<GoalRound> listByGoal(@Param("goalId") String goalId);

    @Select("SELECT * FROM c_ai_goal_round WHERE goal_id = #{goalId} AND status = 'RUNNING' ORDER BY n DESC LIMIT 1")
    GoalRound runningOf(@Param("goalId") String goalId);

    @Select("SELECT COALESCE(MAX(n), 0) FROM c_ai_goal_round WHERE goal_id = #{goalId}")
    int maxN(@Param("goalId") String goalId);
}
