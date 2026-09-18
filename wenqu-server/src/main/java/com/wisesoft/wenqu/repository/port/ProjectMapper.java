package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.Project;
import org.apache.ibatis.annotations.Mapper;

/**
 * projects Mapper
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
