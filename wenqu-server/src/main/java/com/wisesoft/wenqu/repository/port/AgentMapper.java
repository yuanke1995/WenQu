package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.Agent;
import org.apache.ibatis.annotations.Mapper;

/**
 * agents Mapper
 */
@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
}
