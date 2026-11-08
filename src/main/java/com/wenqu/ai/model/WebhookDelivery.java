package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体轮终 webhook 出站投递记录（对外能力第二批）：
 * 每次出站先落行再发——进程死在投递中途，扫描任务按 {@code next_retry_at} 把 pending 行补发出去，
 * 语义是<b>至少一次</b>（接收方要能用幂等键去重；payload 里的 turnId/messageId 就是天然的幂等键）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_webhook_delivery")
public class WebhookDelivery {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_EXHAUSTED = "exhausted";

    private String id;
    /** 归属租户（行级过滤随 c_ai_agent 同口径） */
    private String tenantId;
    /** 投递归属人（智能体主人；exhausted 通知发给谁） */
    private String uid;
    private String agentId;
    private String turnId;
    private String sessionId;
    /** 事件名（X-Wenqu-Event 头同源；v1 恒为 agent.turn.finished） */
    private String event;
    private String url;
    /** 出站 JSON 原文（重试时原样重发，不重算——同一笔投递内容恒定） */
    private String payload;
    /** pending / success / exhausted */
    private String status;
    /** 已尝试次数（含首发；≥ 上限后置 exhausted） */
    private Integer attempts;
    /** 下次重试时刻（pending 行的扫描判据） */
    private LocalDateTime nextRetryAt;
    private Integer lastStatus;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}
