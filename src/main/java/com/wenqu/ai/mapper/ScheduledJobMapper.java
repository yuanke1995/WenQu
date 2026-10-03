package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ScheduledJob;
import org.apache.ibatis.annotations.Mapper;

/**
 * 定时任务 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ScheduledJobMapper extends BaseMapper<ScheduledJob> {
}
