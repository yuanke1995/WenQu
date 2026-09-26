package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时执行智能体任务：按 cron 到点后，用「智能体 + 提示词」跑一次完整问答。
 * <p>
 * 归属**用户**（个人资产，与技能/MCP/产物同一口径）；结果落进 {@link #sessionId} 指向的专属会话，
 * 一个任务一个会话、历史执行追加在里面（不像平台版每轮新建会话——那样会把会话列表刷满，
 * 而"这次定时跑出什么"恰恰需要能连起来看）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_scheduled_job")
public class ScheduledJob {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属用户（c_ai_user.uid） */
    private String uid;

    /** 任务名（同时作为结果会话的标题） */
    private String name;

    /** 执行的智能体（空 = 当轮生效的默认智能体） */
    private String agentId;

    /** 每轮发送给智能体的问题 / 指令 */
    private String prompt;

    /** cron 表达式（5 段：分 时 日 月 周） */
    private String cron;

    /** cron 的时区解释（如 Asia/Shanghai） */
    private String timezone;

    /** 本轮是否深度思考: 0=否 1=是 */
    private Integer deepThink;

    /** 模型覆盖（{providerId}/{modelId}；空 = 个人默认） */
    private String modelRef;

    /** 启用: 1=启用 0=停用（停用时 next_run_at 清空） */
    private Integer enabled;

    /** 下次执行时刻（按 timezone 计算得出；停用/删除时为 null） */
    private LocalDateTime nextRunAt;

    /** 上次触发时刻 */
    private LocalDateTime lastRunAt;

    /** 该任务的结果会话（懒创建，之后每轮追加） */
    private String sessionId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 软删: 0=正常 1=已删除 */
    private Integer deleted;
}
