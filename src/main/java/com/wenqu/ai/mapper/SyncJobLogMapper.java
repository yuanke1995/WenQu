package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.SyncJobLog;
import org.apache.ibatis.annotations.Mapper;

/** 身份同步作业日志（c_ai_sync_job_log） */
@Mapper
public interface SyncJobLogMapper extends BaseMapper<SyncJobLog> {
}
