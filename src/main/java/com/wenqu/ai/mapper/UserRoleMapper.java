package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.UserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户-角色多对多 Mapper（复合主键，走 Wrapper 操作）
 *
 * @author yuanke
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {
}
