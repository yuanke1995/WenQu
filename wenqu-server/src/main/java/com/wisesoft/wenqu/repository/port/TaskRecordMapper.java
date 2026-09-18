package com.wisesoft.wenqu.repository.port;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.wenqu.models.TaskRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * tasks Mapper
 */
@Mapper
public interface TaskRecordMapper extends BaseMapper<TaskRecord> {
}
