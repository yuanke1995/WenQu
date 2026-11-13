package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ScheduledRun;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 定时任务执行记录 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ScheduledRunMapper extends BaseMapper<ScheduledRun> {

    /**
     * 清理执行记录：早于 cutoff 的全删，外加每个任务只保留最近 keepPerJob 条。
     * <p>窗口函数包在派生表里，绕开 MySQL「不能在同一张表上边查边删」的限制。
     *
     * @return 本轮删除行数
     */
    @Delete("DELETE FROM c_ai_scheduled_run "
            + "WHERE create_time < #{cutoff} "
            + "   OR id IN (SELECT id FROM ("
            + "       SELECT id, ROW_NUMBER() OVER (PARTITION BY job_id ORDER BY create_time DESC) rn "
            + "       FROM c_ai_scheduled_run"
            + "   ) t WHERE t.rn > #{keepPerJob}) "
            + "LIMIT #{batch}")
    int purgeExpired(@Param("cutoff") LocalDateTime cutoff,
                     @Param("keepPerJob") int keepPerJob,
                     @Param("batch") int batch);
}
