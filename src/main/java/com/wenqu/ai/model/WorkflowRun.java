package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流运行记录：每次运行把当时的 DSL <b>快照</b>下来（执行不可变），
 * 改画布不影响历史运行的可回放性（对齐 Coze 的 run 语义）。
 * <p>
 * {@code nodeTraces} 是节点级 trace（JSON 数组：nodeId/type/status/输入输出摘要/耗时），
 * 是 M1 调试运行与后续 Trace 运营闭环（P1 另一件）的共同地基。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_workflow_run")
public class WorkflowRun {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 工作流 ID */
    private String workflowId;

    /** 本次运行锁定的 DSL 快照（执行不可变） */
    private String dslSnapshot;

    /** 触发方式: manual=调试 agent=智能体绑定 api=外部Key */
    private String triggerType;

    /** 触发者 uid */
    private String triggeredBy;

    /** 状态: running/success/failed/timeout/waiting_approval */
    private String status;

    /** 开始节点入参（JSON） */
    private String inputs;

    /** 结束节点出参（JSON） */
    private String outputs;

    /** 节点级 trace（JSON 数组：nodeId/type/status/输入输出摘要/耗时） */
    private String nodeTraces;

    /** 人工审核挂起时的执行快照（已完成节点全量输出 + 挂起节点；恢复时短路重放用，终态运行置空） */
    private String stateSnapshot;

    /** M4：本次运行基于的发布版本号（NULL = 草稿调试运行） */
    private Integer version;

    /** M4：本次运行用的 DSL 来源: draft=草稿 published=已发布版本 */
    private String dslSource;

    /** M4：API 触发所用的 Key id（人工/智能体触发为 NULL） */
    private String apiKeyId;

    /** 失败原因（截断 1000 字符） */
    private String error;

    /** 开始时刻 */
    private LocalDateTime startedAt;

    /** 结束时刻 */
    private LocalDateTime finishedAt;

    /** 总耗时（毫秒） */
    private Long durationMs;
}
