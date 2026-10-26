package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.GroupRoleMapping;
import org.apache.ibatis.annotations.Mapper;

/** 组→角色/部门映射规则（c_ai_group_role_mapping） */
@Mapper
public interface GroupRoleMappingMapper extends BaseMapper<GroupRoleMapping> {
}
