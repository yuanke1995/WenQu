package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 团队任务事件时间线（节点详情回放）。 */
@Data
@TableName("c_ai_team_task_event")
public class TeamTaskEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String taskId;
    private String kind;
    private String detail;
    private LocalDateTime createdAt;
}
