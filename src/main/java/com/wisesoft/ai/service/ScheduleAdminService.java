package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wisesoft.ai.mapper.ScheduleRunLogMapper;
import com.wisesoft.ai.model.ScheduleRunLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务执行日志查询（设置页「定时维护」面板）。
 * <p>
 * 写入方是 {@link com.wisesoft.ai.schedule.ScheduleCenter}（每次任务完成后落一行），
 * 本服务只做分页查询与面向界面的行裁剪——任务列表快照/手动触发直接走 ScheduleCenter，
 * 不经过本服务（避免多一层转发）。
 *
 * @author yuanke
 */
@Service
@RequiredArgsConstructor
public class ScheduleAdminService {

    private final ScheduleRunLogMapper scheduleRunLogMapper;

    /**
     * 执行日志分页。
     *
     * @param taskName 任务名精确筛选（null/all = 全部）
     * @param success  1=成功 0=失败（null = 全部）
     */
    public Map<String, Object> listRuns(String taskName, Integer success, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(10, size), 100);
        LambdaQueryWrapper<ScheduleRunLog> qw = new LambdaQueryWrapper<>();
        if (taskName != null && !taskName.isBlank() && !"all".equals(taskName)) {
            qw.eq(ScheduleRunLog::getTaskName, taskName);
        }
        if (success != null) {
            qw.eq(ScheduleRunLog::getSuccess, success);
        }
        qw.orderByDesc(ScheduleRunLog::getStartedAt);
        Page<ScheduleRunLog> pg = scheduleRunLogMapper.selectPage(new Page<>(p, s), qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScheduleRunLog r : pg.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("taskName", r.getTaskName());
            m.put("triggerType", r.getTriggerType());
            m.put("success", r.getSuccess());
            m.put("errorMsg", r.getErrorMsg());
            m.put("durationMs", r.getDurationMs());
            m.put("startedAt", r.getStartedAt());
            m.put("finishedAt", r.getFinishedAt());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", pg.getTotal());
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    /**
     * 清空全部执行日志（立即生效，不等保留期清理任务）。高频任务数天即可累积上万行，
     * 界面提供一键清空；返回删除行数供提示。
     */
    public long clearRuns() {
        return scheduleRunLogMapper.delete(null);
    }
}
