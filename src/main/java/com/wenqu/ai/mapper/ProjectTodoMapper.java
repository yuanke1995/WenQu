package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ProjectTodo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作区事项（{@code c_ai_project_todo}）的 mapper：纯 CRUD，无自定义 SQL。
 *
 * @author yuanke
 */
@Mapper
public interface ProjectTodoMapper extends BaseMapper<ProjectTodo> {
}
