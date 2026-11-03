package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.RoleDataScope;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色数据范围 Mapper（c_ai_role_data_scope，每角色一行，批次 C2）。
 *
 * @author yuanke
 */
@Mapper
public interface RoleDataScopeMapper extends BaseMapper<RoleDataScope> {
}
