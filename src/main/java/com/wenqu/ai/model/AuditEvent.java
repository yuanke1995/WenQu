package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一审计事件（Phase 3 · P11）。
 * <p>
 * 一条＝一次「谁对什么做了什么」。{@code prev_hash}/{@code row_hash} 构成<b>同副本内按 seq 排序的
 * SHA-256 哈希链</b>：改一行、删一行、插一行都会让该副本从那一处起的链验证失败
 * （导出接口与 {@code /audit/verify} 都算这个）。刻意不把自增 id 进哈希——多副本并发写入会让 id 交错，
 * 链就断了；序号由写入线程自己按副本分配，与 id 无关。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_audit_event")
public class AuditEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** 事件时刻（业务线程里取的，不是入库时刻） */
    private LocalDateTime ts;

    /** info=常规治理动作；warn=需要人工看一眼 */
    private String level;

    /** 动作名（与日志里 [AUDIT] 后那段同源） */
    private String action;

    private String actorUid;

    private String actorIp;

    private String targetType;

    private String targetId;

    private String sessionId;

    /** 其余键值（JSON，按插入序） */
    private String detail;

    private String replica;

    /** 本副本内单调序号 */
    private Long seq;

    private String prevHash;

    private String rowHash;

    private LocalDateTime createTime;
}
