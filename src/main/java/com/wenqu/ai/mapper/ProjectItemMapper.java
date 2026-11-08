package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ProjectItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作区事项清单：纯 CRUD，租户与归属过滤由拦截器/服务层承担。
 *
 * @author yuanke
 */
@Mapper
public interface ProjectItemMapper extends BaseMapper<ProjectItem> {
}
