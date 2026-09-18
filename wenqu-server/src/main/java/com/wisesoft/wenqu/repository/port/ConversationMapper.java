package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.Conversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * conversations Mapper
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
