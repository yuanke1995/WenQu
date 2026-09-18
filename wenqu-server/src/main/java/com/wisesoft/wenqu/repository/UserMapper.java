package com.wisesoft.wenqu.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.model.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 用户 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
