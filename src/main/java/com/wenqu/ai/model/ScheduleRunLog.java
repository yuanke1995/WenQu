package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务执行日志：{@link com.wenqu.ai.schedule.ScheduleCenter} 每次触发完成后落一行，
 * 供设置页「定时维护」面板查看任务运行历史（上次何时跑、跑了多久、成败原因）。
 * <p>
 * 写入约定：<b>只在任务体结束后落行</b>（一次性 insert，不先插 running 再更新——
 * 高频扫描任务（如解析队列 5s 一轮）下双写纯浪费，"执行中"状态由 ScheduleCenter
 * 内存快照表达）。保留期由 {@code schedule.runLogRetentionDays} 控制，超期由
 * 注册在调度中心自己的「任务执行日志清理」任务定期物理删除。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_schedule_run")
public class ScheduleRunLog {

    /** 执行记录 ID（业务生成 UUID） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 任务名（ScheduleCenter 注册名） */
    private String taskName;

    /** 触发方式: startup=启动首轮 auto=周期触发 manual=手动触发 */
    private String triggerType;

    /** 结果: 1=成功 0=失败 */
    private Integer success;

    /** 失败原因（截断 1000 字符） */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 开始时刻 */
    private LocalDateTime startedAt;

    /** 结束时刻 */
    private LocalDateTime finishedAt;
}
