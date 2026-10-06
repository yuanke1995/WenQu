package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知（一行 = 给某用户的一条通知）。
 * <p>
 * 产生方是各异步链路的终态收口处（解析队列 / 工作流收口 / 网页源刷新），
 * 消费方是前端铃铛（轮询未读数 + 拉列表）。通知是主链路的旁路：落库失败只告警，
 * 绝不影响解析/运行本身的收口（见 {@code NotificationService.create} 的边界说明）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_notification")
public class Notification {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 接收人（uid） */
    private String uid;

    /** 类型常量见下方 */
    private String type;

    /** 一句话标题（列表主文本） */
    private String title;

    /** 详情（块数/失败原因等） */
    private String content;

    /** 跳转目标类型: kb / workflow（空=不可跳转） */
    private String refType;

    /** 跳转目标 ID（kbId / workflowId / sessionId） */
    private String refId;

    /** 二级跳转目标（工作流审批=runId；工具审批=approvalId） */
    private String refSub;

    /** 同类事件合并计数（同 dedupKey 未读期内重复发生 +1 而非新增行） */
    private Integer hitCount;

    /** 去重键（uid+type+dedupKey 未读期内合并；null=不合并） */
    private String dedupKey;

    /** 0=未读 1=已读（read 是 MySQL 保留字，Java 字段名避开） */
    private Integer readFlag;

    /** 已读时刻 */
    private LocalDateTime readTime;

    /** 产生时刻 */
    private LocalDateTime createTime;

    // ==================== 类型常量（与 schema.sql 注释一一对应） ====================

    /** 文档解析完成 */
    public static final String TYPE_PARSE_DONE = "parse.done";
    /** 文档解析终态失败（不重试） */
    public static final String TYPE_PARSE_FAILED = "parse.failed";
    /** 工作流运行失败 */
    public static final String TYPE_WORKFLOW_FAILED = "workflow.failed";
    /** 工作流运行超时 */
    public static final String TYPE_WORKFLOW_TIMEOUT = "workflow.timeout";
    /** 工作流运行挂起待人工审核 */
    public static final String TYPE_WORKFLOW_APPROVAL = "workflow.approval";
    /** 网页源自动刷新失败 */
    public static final String TYPE_WEB_REFRESH_FAILED = "web.refresh.failed";
    /** 定时任务（用户自建的定时智能体任务）执行完成 */
    public static final String TYPE_SCHEDULE_DONE = "schedule.done";
    /** 定时任务（用户自建的定时智能体任务）执行失败 */
    public static final String TYPE_SCHEDULE_FAILED = "schedule.failed";
    /** 检索评估自动体检下滑预警（收件人为管理员级账号） */
    public static final String TYPE_EVAL_DECLINE = "eval.decline";
    /** 工具执行审批待决（ask 模式的有副作用工具挂起等人确认） */
    public static final String TYPE_TOOL_APPROVAL = "tool.approval";
    /** 智能体提问待答（askUser 工具挂起等人点选/输入，refSub=askId 供深链直达可答位置） */
    public static final String TYPE_TOOL_ASK = "tool.ask";
    /** 知识库批量解析失败告警（同库短窗内多个文档终态失败，收件人为库归属人） */
    public static final String TYPE_PARSE_BATCH_FAILED = "parse.batch.failed";
    /**
     * 模型供应商额度不足（余额耗尽/配额用尽/套餐到期），收件人为供应商归属人
     * （供应商「谁建归谁」，只有归属人能充值或换 Key——发给管理员等于把问题派给无权处理的人）。
     * 正文带引用清单（哪些知识库/哪些人的默认模型在用它），refType=provider 可跳转模型供应商页。
     */
    public static final String TYPE_MODEL_QUOTA = "model.quota";
}
