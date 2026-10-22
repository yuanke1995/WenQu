package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 目标的一轮（任务运行时的 attempt）。
 *
 * <p>为什么单独一张表：续跑幂等键 {@code goal:{id}:{n}} 需要一个可审计的落点。崩溃恢复时，
 * 只看 {@link Goal#getTurnCounter()} 无法判断「这一轮到底跑没跑、停在哪」；轮次行给出
 * 输入快照、开始/结束、错误分类与用量，恢复可以先对账再决定重跑还是暂停。
 *
 * <p>状态：{@code RUNNING}（已提交，等待收尾）/ {@code SETTLED}（正常收尾）/
 * {@code FAILED}（失败收尾）/ {@code INTERRUPTED}（进程崩溃或租约过期，结果未知）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_goal_round")
public class GoalRound {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属目标 */
    private String goalId;

    /** 第几轮（从 1 起；幂等键里的 n） */
    private Integer n;

    /** RUNNING / SETTLED / FAILED / INTERRUPTED */
    private String status;

    /** 应用层轮号 {@code goal:{id}:{n}} */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String turnId;

    /** 起这一轮的执行者：租约被接管后，旧实例迟到的回调据此拒绝写状态（fencing） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaseOwner;

    /** 本轮输入快照的 sha256（同 key 不同输入即冲突，用来提前发现改目标撞上在飞轮） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String inputHash;

    /** 本轮完整输入（审计/恢复用，不参与模型上下文） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String prompt;

    /** 开跑时刻 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    /** 收尾时刻 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime endedAt;

    /** 失败分类（见 GoalErrorClass） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String errorClass;

    /** 本轮 token（按用量台账时间窗聚合） */
    private Long usedTokens;

    /** 本轮机器毫秒 */
    private Long usedMs;

    /** 本轮工具调用数 */
    private Integer usedToolCalls;

    /** 本轮交付说明（报告正文或 goal_submit.summary） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summary;

    /** 模型提交的完成申请 JSON（goal_submit） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String proposalJson;

    /** 本轮证据快照 JSON */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String evidenceJson;

    /** 创建时刻 */
    private LocalDateTime createdAt;

    public static final String RUNNING = "RUNNING";
    public static final String SETTLED = "SETTLED";
    public static final String FAILED = "FAILED";
    public static final String INTERRUPTED = "INTERRUPTED";
}
