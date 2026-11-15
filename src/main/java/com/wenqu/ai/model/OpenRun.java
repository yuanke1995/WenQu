package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外开放的一次运行（run）映射。
 *
 * <p><b>{@code runId} 是对外独立句柄，不等于框架 {@code turnId}</b>：建轮时本服务生成 runId，
 * 流水线内部另生成 turnId，两者不经映射。方案 J1 原本设想「runId=turnId」，但按
 * {@code CollectingSseEmitter} 接缝实现后拿不到流水线生成的 turnId；实测 A1 两批的审批、
 * 产物、插话、停止都只需「runId → sessionId」即可复用现有能力，补映射列反而多一层会漂移的
 * 中间态。口径更正见 V21 迁移头注释与方案 §10.2。</p>
 *
 * <p>本表只做归属与对外状态查询，<b>不是执行状态机</b>——运行态仍在 TurnRegistry / ChatRunBus，
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
