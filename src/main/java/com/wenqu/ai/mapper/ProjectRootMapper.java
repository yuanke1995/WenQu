package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ProjectRoot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作区源文件夹（{@code c_ai_project_root}）的 mapper：纯 CRUD，无自定义 SQL。
 *
 * @author yuanke
 */
@Mapper
public interface ProjectRootMapper extends BaseMapper<ProjectRoot> {
}
