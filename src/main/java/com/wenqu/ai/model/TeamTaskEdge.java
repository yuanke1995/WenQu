package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 团队任务依赖边：from 是前置，to 是后继。 */
@Data
@TableName("c_ai_team_task_edge")
public class TeamTaskEdge {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tenantId;
    private String rootSessionId;
    private String fromTaskId;
    private String toTaskId;
    private LocalDateTime createdAt;
}
