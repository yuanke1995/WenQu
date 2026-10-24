package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作区事项：这个场子里「要做的事」，工作区从「归档柜」走向「推进器」的第一块载体。
 *
 * <p><b>为什么不是复用会话级待办（框架 Todo）</b>：那是单轮/单会话内的执行清单，跟着会话生灭；
 * 工作区事项跨会话常驻——「把周报模板定稿」不属于哪一次对话，它属于这个场子。
 * <p><b>为什么刻意做薄</b>：不做 deadline、依赖、负责人这类机器可读结构——单人工作区的
 * 事项是清单不是项目管理。闭环只有三步：记下（手填/「去聊」派发）→ 做完 → 勾掉。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_project_todo")
public class ProjectTodo {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属用户（与工作区同口径；查询按 project 走，uid 做双保险） */
    private String uid;

    /** 所属工作区（c_ai_project.id） */
    private String projectId;

    /** 事项内容（一句话，≤200 字） */
    private String title;

    /** {@link #STATUS_OPEN} / {@link #STATUS_DONE} */
    private String status;

    /** 完成时刻（null=未完成） */
    private LocalDateTime doneTime;

    /** 逻辑删除（MyBatis-Plus 全局 logic-delete-field） */
    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static final String STATUS_OPEN = "open";

    public static final String STATUS_DONE = "done";
}
