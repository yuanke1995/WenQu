package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.Agent;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 智能体 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AgentMapper extends BaseMapper<Agent> {
}
