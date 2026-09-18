package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.ScheduledAgentJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * scheduled_agent_jobs Mapper
 */
@Mapper
public interface ScheduledAgentJobMapper extends BaseMapper<ScheduledAgentJob> {
}
