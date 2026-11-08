package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区事项清单（C2）：工作区里「要做什么」的一句话。
 * <p>状态只由用户显式变更（勾选/对话内确认），不自动推断——清单不是进度展示，
 * 不做「编造进度」那件事。去聊由前端带 ask 进聊天输入框，不自动发送。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_project_item")
public class ProjectItem {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_DOING = "doing";
    public static final String STATUS_DONE = "done";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属用户（c_ai_user.uid） */
    private String uid;

    /** 所属工作区（c_ai_project.id） */
    private String projectId;

    private String title;

    private String note;

    /** open / doing / done */
    private String status;

    /** 最近关联会话；空=去聊时新建对话 */
    private String sessionId;

    private Integer sortNo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
