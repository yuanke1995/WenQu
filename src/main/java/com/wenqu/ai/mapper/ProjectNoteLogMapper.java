package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ProjectNoteLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作区约定留痕（{@code c_ai_project_note_log}）的 mapper：纯 CRUD，无自定义 SQL。
 *
 * @author yuanke
 */
@Mapper
public interface ProjectNoteLogMapper extends BaseMapper<ProjectNoteLog> {
}
