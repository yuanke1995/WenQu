package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 用户 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
