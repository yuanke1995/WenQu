package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.AiAgent;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 智能体 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface AiAgentMapper extends BaseMapper<AiAgent> {
}
