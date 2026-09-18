package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * users Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
