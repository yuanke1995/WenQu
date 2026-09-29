package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流定义：DSL（JSON）是唯一真源——节点/边/变量引用/画布坐标都在其中，
 * 前端画布只是编辑器，执行引擎只认 DSL。对标 Dify Workflow / Coze 工作流。
 * <p>
 * 分期边界（排班计划 2026-09-29）：
 * <ul>
 *   <li>M0：本表 + DSL 校验/编译骨架，管理接口 CRUD + 校验；</li>
 *   <li>M1：引擎五核心节点可执行 + 运行记录（{@link AiWorkflowRun}）；</li>
 *   <li>M2：VueFlow 画布编辑器；</li>
 *   <li>M4：发布语义（status=published + 快照回滚）、智能体绑定、API 触发、共享范围启用。</li>
 * </ul>
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_workflow")
public class AiWorkflow {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 创建者 uid（M4 前仅本人可见可管，share_config 字段预留） */
    private String uid;

    /** 工作流名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 工作流 DSL（JSON：version/nodes/edges，画布坐标存 position） */
    private String dsl;

    /** 状态: draft=草稿 published=已发布（M4 启用发布语义） */
    private String status;

    /** 共享范围(JSON，与知识库/智能体同款两级可见性；空=仅本人，M4 前不启用) */
    private String shareConfig;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
