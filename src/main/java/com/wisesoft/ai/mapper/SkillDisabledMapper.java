package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.SkillDisabled;
import org.apache.ibatis.annotations.Mapper;

/**
 * 内置技能个人停用标记 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface SkillDisabledMapper extends BaseMapper<SkillDisabled> {
}
