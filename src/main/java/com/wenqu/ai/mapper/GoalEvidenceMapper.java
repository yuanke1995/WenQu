package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.GoalEvidence;

/**
 * 目标证据数据访问（按 goal_id + round_n 查询由 BaseMapper 的 LambdaQueryWrapper 承担）。
 */
public interface GoalEvidenceMapper extends BaseMapper<GoalEvidence> {
}
