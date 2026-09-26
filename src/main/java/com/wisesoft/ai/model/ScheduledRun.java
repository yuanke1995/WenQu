package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务的一轮执行记录。回答正文在结果会话里，这里只留状态与摘录（列表快速预览）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_scheduled_run")
public class ScheduledRun {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 任务ID（c_ai_scheduled_job.id） */
    private String jobId;

    /** 归属用户（冗余存储，便于按人查执行历史而不必联表） */
    private String uid;

    /** 执行结果会话 */
    private String sessionId;

    /** 触发方式: scheduled | manual */
    private String triggerType;

    /** 状态: running | succeeded | failed */
    private String status;

    /** 回答摘录（前 1000 字符） */
    private String answer;

    /** 失败原因 */
    private String error;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createTime;
}
