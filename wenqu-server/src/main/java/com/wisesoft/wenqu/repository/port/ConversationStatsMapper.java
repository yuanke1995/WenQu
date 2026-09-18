package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.ConversationStats;
import org.apache.ibatis.annotations.Mapper;

/**
 * conversation_stats Mapper
 */
@Mapper
public interface ConversationStatsMapper extends BaseMapper<ConversationStats> {
}
