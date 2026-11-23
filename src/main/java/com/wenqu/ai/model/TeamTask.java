package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 智能体团队任务（DAG 节点）。 */
@Data
@TableName("c_ai_team_task")
public class TeamTask {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tenantId;
    private String rootSessionId;
    private String subject;
    private String description;
    private String state;
    private String ownerSessionId;
    private String ownerName;
    private Integer blockedCount;
    private String result;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
