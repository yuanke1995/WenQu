package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外开放的一次运行（run）映射。
 *
 * <p>{@code runId} 直接用 {@code turnId}：消息、审批卡、事件账本、webhook 早已挂在 turnId 上，
 * 另造一个 UUID 只会多一层映射，还会和框架「一 turn 多 executionRunId」的概念混淆。
 * 本表只做归属与对外状态查询，<b>不是执行状态机</b>——运行态仍在 TurnRegistry / ChatRunBus，
 * 终态由投影层在 done/error 帧到达时回填。</p>
 */
@Data
@TableName("c_ai_open_run")
public class OpenRun {

    @TableId(type = IdType.INPUT)
    private String runId;

    private String sessionId;

    private String uid;

    private String agentId;

    private String apiKeyId;

    private String idempotencyKey;

    private String status;

    private String errorCode;

    private String errorMessage;

    private String answer;

    private Integer idempotentHit;

    private LocalDateTime createTime;

    private LocalDateTime finishTime;
}
