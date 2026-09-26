package com.wisesoft.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wisesoft.ai.model.ScheduledJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * 定时任务 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ScheduledJobMapper extends BaseMapper<ScheduledJob> {
}
