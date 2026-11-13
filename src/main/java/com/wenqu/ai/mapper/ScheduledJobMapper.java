package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.ScheduledJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 定时任务 Mapper
 *
 * @author yuanke
 */
@Mapper
public interface ScheduledJobMapper extends BaseMapper<ScheduledJob> {

    /**
     * 撤销删除：把软删行恢复为「停用」态。
     * <p>不能先 selectById 再 updateById——{@code deleted} 是全局逻辑删除字段，
     * 已删行查询不到，MyBatis-Plus 的 update 也会自动带上 {@code deleted=0} 条件。
     * 这条语句按 id + uid 直更，是撤销唯一的正确入口。
     */
    @Update("UPDATE c_ai_scheduled_job "
            + "SET deleted = 0, enabled = 0, next_run_at = NULL, paused_by_archive = 0, update_time = NOW() "
            + "WHERE id = #{id} AND uid = #{uid} AND deleted = 1")
    int restoreDeleted(@Param("id") String id, @Param("uid") String uid);
}
