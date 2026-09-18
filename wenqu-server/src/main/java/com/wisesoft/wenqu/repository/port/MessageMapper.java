package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.Message;
import org.apache.ibatis.annotations.Mapper;

/**
 * messages Mapper
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
