package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.ScheduledRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * 定时任务执行记录 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ScheduledRunMapper extends BaseMapper<ScheduledRun> {
}
