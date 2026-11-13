package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知（一行 = 给某用户的一条通知）。
 * <p>
 * 产生方是各异步链路的终态收口处（解析队列 / 工作流收口 / 网页源刷新 / 定时任务 / 对话轮与目标收口
 * / 人在回路待决），
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

    /**
     * 列表返回时动态判定（不入库）：这条「待处理」指向的审批/运行是否仍在等人处理。
     * <p>false = 底层已经处理完（通过/拒绝/超时/已续跑），前端不再显示「去处理」。
     * 判定走底层表的实时状态，因此历史存量通知也能自动纠正，不需要回填。
     */
    @TableField(exist = false)
    private Boolean actionable;

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
    public static final String TYPE_SCHEDULE_EXPIRED = "schedule.expired";
    /** 检索评估自动体检下滑预警（收件人为管理员级账号） */
    public static final String TYPE_EVAL_DECLINE = "eval.decline";
    /** 检索评估手动/定时运行完成 */
    public static final String TYPE_EVAL_DONE = "eval.done";
    /** 检索评估运行失败 */
    public static final String TYPE_EVAL_FAILED = "eval.failed";
    /** 智能体评测回归完成/取消（与检索评估 eval.* 是两条线，前缀区分） */
    public static final String TYPE_AGENT_EVAL_DONE = "agentEval.done";
    /** 智能体评测回归失败（总预算用尽/执行异常） */
    public static final String TYPE_AGENT_EVAL_FAILED = "agentEval.failed";
    /** 智能体轮终 webhook 重试耗尽（对外能力第二批）：接收方一直打不通，主人得知道结果没送达 */
    public static final String TYPE_AGENT_WEBHOOK_FAILED = "agent.webhook.failed";
    /** 工具执行审批待决（ask 模式的有副作用工具挂起等人确认） */
    public static final String TYPE_TOOL_APPROVAL = "tool.approval";
    /** 智能体提问待决（askUser 结构化提问卡挂起等用户作答，超时/忽略都不替用户选） */
    public static final String TYPE_TOOL_ASK = "tool.ask";
    /** 执行计划待确认（计划卡挂起等用户拍板；确认或继续对话都算答复） */
    public static final String TYPE_PLAN_APPROVAL = "plan.approval";
    /** 知识库批量解析失败告警（同库短窗内多个文档终态失败，收件人为库归属人） */
    public static final String TYPE_PARSE_BATCH_FAILED = "parse.batch.failed";
    /**
     * 模型供应商额度不足（余额耗尽/配额用尽/套餐到期），收件人为供应商归属人
     * （供应商「谁建归谁」，只有归属人能充值或换 Key——发给管理员等于把问题派给无权处理的人）。
     * 正文带引用清单（哪些知识库/哪些人的默认模型在用它），refType=provider 可跳转模型供应商页。
     */
    public static final String TYPE_MODEL_QUOTA = "model.quota";
    /**
     * 一轮对话跑完了（只给「跑得够久、人多半已经离开屏幕」的那类轮，
     * 耗时门槛固定 60 秒）。短轮不发：答案当场就到了，再钉一条通知是刷屏。
     */
    public static final String TYPE_CHAT_TURN_DONE = "chat.turn.done";
    /**
     * 一轮对话没跑成（上游额度/网关异常、整轮时间上限、推理步数用满）。
     * 与 {@link #TYPE_CHAT_TURN_DONE} 不同档：<b>不受时长阈值约束</b>——失败了就该让人知道，
     * 哪怕只跑了三秒。
     */
    public static final String TYPE_CHAT_TURN_FAILED = "chat.turn.failed";
    /** 一个横跨多轮的目标达成了（此时不再逐轮发 {@link #TYPE_CHAT_TURN_DONE}，见 TurnNotifyService） */
    public static final String TYPE_GOAL_ACHIEVED = "goal.achieved";
    /** 一个目标停住了（预算耗尽、连续失败、受阻）——与「达成」分两类：一个是好消息，一个要人处理 */
    public static final String TYPE_GOAL_FAILED = "goal.failed";
    /** 系统巡检发现必须处理的问题（收件人为管理员级账号；按巡检项去重，恢复后不再重复提醒） */
    public static final String TYPE_OPS_ALERT = "ops.alert";
    /** 新的租户开通申请（收件人为平台级管理员，需要批准或驳回） */
    public static final String TYPE_TENANT_REQUEST = "tenant.request";
    /** 租户已开通（给管理员的结果回执，不需要再处理） */
    public static final String TYPE_TENANT_APPROVED = "tenant.approved";
    /** 用量配额/预算触顶（收件人为管理员；quota 为存量类型值，不改名以免历史行失联） */
    public static final String TYPE_USAGE_QUOTA = "quota";
    /** 数据看板阈值告警（无命中率偏高 / 满意率偏低；收件人为管理员，按告警项去重） */
    public static final String TYPE_DASHBOARD_ALERT = "dashboard.alert";
    /** 数据看板周报（每周一份质量 + 待办摘要；收件人为管理员） */
    public static final String TYPE_DASHBOARD_WEEKLY = "dashboard.weekly";
}
