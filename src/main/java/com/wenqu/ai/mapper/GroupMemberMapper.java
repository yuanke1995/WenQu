package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.GroupMember;
import org.apache.ibatis.annotations.Mapper;

/** 组⇄用户成员关系（c_ai_group_member） */
@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMember> {
}
