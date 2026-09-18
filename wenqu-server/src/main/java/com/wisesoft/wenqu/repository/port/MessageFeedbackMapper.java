package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.MessageFeedback;
import org.apache.ibatis.annotations.Mapper;

/**
 * message_feedbacks Mapper
 */
@Mapper
public interface MessageFeedbackMapper extends BaseMapper<MessageFeedback> {
}
