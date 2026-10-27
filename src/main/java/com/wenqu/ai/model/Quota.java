package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 推理配额与预算上限（Phase 2 计量计费）。
 * <p>
 * 只存<b>上限</b>：用量本身以 {@code c_ai_usage_log} 为唯一事实源，金额按 {@code c_ai_model}
 * 登记的单价现算（单价改了历史费用跟着重算，这是既有报表口径，配额不另存一份快照）。
 * <p>
 * 三档作用域各自独立成行（同作用域同周期唯一）：一个成员可以同时受「本租户月预算 + 本部门日配额 +
 * 个人月配额」三道闸，哪一道先到就按哪一道说清楚——报「你超额」却不说是哪条规则，是没法追问的。
 *
 * <p>作用域是 {@code tenant}（本租户全体）而不是"全平台"：配额行本身按租户归属（Phase 4 行级隔离），
 * 一条规则管不到别的租户；要给某个客户设上限，就在那个租户里设，或由平台级管理员逐租户设。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_quota")
public class Quota {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户 ID（Phase 4 多租户启用，当前为空） */
    private String tenantId;

    /** 作用域：tenant=本租户全体 / department=某部门 / user=某成员 */
    private String scope;

    /** 作用域标识：tenant 留空；department=部门 id；user=成员 uid */
    private String scopeId;

    /** 周期：day=自然日 / month=自然月（到点自动归零） */
    private String period;

    /** 周期 token 上限（0=不按 token 限） */
    private Long tokenLimit;

    /** 周期费用上限（元；NULL=不按金额限；未配单价的模型不计入金额） */
    private BigDecimal costLimit;

    /**
     * 受限用途（逗号分隔的 kind）。
     * <p>留空＝只限用户主动发起的调用（对话 / 子智能体 / 工作流）；<b>历史压缩、记忆提炼、
     * 引用自检这类辅助调用一律不拦</b>——拦住它们不是省钱，是把上下文撑爆的另一种坏。
     */
    private String kinds;

    /** 1=到额即熔断；0=只告警不拦（新策略先观察一个周期再收紧） */
    private Integer enforce;

    /** 备注：为什么给这个人/部门设这个数（审计要看得见依据） */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
