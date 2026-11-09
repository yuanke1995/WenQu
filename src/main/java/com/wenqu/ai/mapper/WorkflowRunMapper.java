package com.wenqu.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wenqu.ai.model.WorkflowRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流运行记录 Mapper
 *
 * @author yuanke
 */
public interface WorkflowRunMapper extends BaseMapper<WorkflowRun> {

    /** 区间运行汇总：总数 / 成功数（执行记录页签的成功率口径） */
    @Select("<script>SELECT COUNT(*) AS total," +
            " COALESCE(SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END), 0) AS success" +
            " FROM c_ai_workflow_run" +
            " <where><if test='from != null'>started_at &gt;= #{from}</if></where></script>")
    Map<String, Object> summarySince(@Param("from") LocalDateTime from);

    /** 区间按天运行量与成功数（执行记录趋势） */
    @Select("SELECT DATE_FORMAT(started_at, '%Y-%m-%d') AS d, COUNT(*) AS total," +
            " COALESCE(SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END), 0) AS success" +
            " FROM c_ai_workflow_run WHERE started_at >= #{from}" +
            " GROUP BY d ORDER BY d")
    List<Map<String, Object>> dailySince(@Param("from") LocalDateTime from);
}
