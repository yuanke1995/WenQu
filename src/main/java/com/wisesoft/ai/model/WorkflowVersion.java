package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流发布版本（M4）：每次发布落一行——DSL 快照 + 版本号 + 发布说明。
 * <p>
 * 语义约定：
 * <ul>
 *   <li>发布 = 把当前草稿 DSL 存为新版本，并把 {@code c_ai_workflow.published_dsl} 切到该版本；
 *       草稿（{@code dsl}）继续可改，不影响已发布行为；</li>
 *   <li>回滚 = 以历史某版本的 DSL <b>再发一版</b>（版本号继续递增）——历史不被改写，
 *       回滚动作本身也可被再次回滚（比"把指针拨回去"更可审计）；</li>
 *   <li>发布后触发的运行（API / 智能体）跑 {@code published_dsl}，调试运行跑草稿 {@code dsl}。</li>
 * </ul>
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_workflow_version")
public class WorkflowVersion {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 工作流 ID */
    private String workflowId;

    /** 版本号（同一工作流内递增，从 1 起） */
    private Integer version;

    /** 该版本锁定的 DSL 快照 */
    private String dsl;

    /** 发布说明 */
    private String note;

    /** 发布者 uid */
    private String publishedBy;

    /** 发布时间 */
    private LocalDateTime publishedAt;
}
