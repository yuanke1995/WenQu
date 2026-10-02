package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流定义：DSL（JSON）是唯一真源——节点/边/变量引用/画布坐标都在其中，
 * 前端画布只是编辑器，执行引擎只认 DSL。对标 Dify Workflow / Coze 工作流。
 * <p>
 * 分期边界（排班计划 2026-09-29）：
 * <ul>
 *   <li>M0：本表 + DSL 校验/编译骨架，管理接口 CRUD + 校验；</li>
 *   <li>M1：引擎五核心节点可执行 + 运行记录（{@link WorkflowRun}）；</li>
 *   <li>M2：VueFlow 画布编辑器；</li>
 *   <li>M4：发布语义（status=published + 快照回滚）、智能体绑定、API 触发、共享范围启用。</li>
 * </ul>
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_workflow")
public class Workflow {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 创建者 uid（M4 前仅本人可见可管，share_config 字段预留） */
    private String uid;

    /** 工作流名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 工作流 DSL（JSON：version/nodes/edges，画布坐标存 position） */
    private String dsl;

    /** 状态: draft=草稿 published=已发布（M4 启用发布语义） */
    private String status;

    /** 共享范围(JSON，与知识库/智能体同款两级可见性；空=仅本人，M4 前不启用) */
    private String shareConfig;

    // ---- M4：发布与版本 ----
    /** 已发布版本锁定的 DSL（发布时从 dsl 拷贝；草稿继续改不影响已发布行为；未发布为 NULL） */
    private String publishedDsl;
    /** 当前发布版本号（对应 c_ai_workflow_version.version；未发布为 NULL） */
    private Integer publishedVersion;
    private LocalDateTime publishedAt;
    private String publishedBy;

    // ---- 第 1 期：定时触发与终态回调（自动化配置，仅创建者/管理范围可改） ----
    /** 定时触发 cron（5 段：分 时 日 月 周；NULL=未配置定时）。只跑已发布版本 */
    private String scheduleCron;
    /** 定时 cron 解释时区（NULL=Asia/Shanghai） */
    private String scheduleTimezone;
    /** 定时触发开关（1=启用） */
    private Integer scheduleEnabled;
    /** 下次定时执行时刻（扫描器据此触发；未启用为 NULL） */
    private LocalDateTime scheduleNextRunAt;
    /** 运行终态回调地址（POST JSON；NULL=不回调） */
    private String callbackUrl;
    /** 回调 HMAC-SHA256 签名密钥（敏感：出参一律置空，仅以 callbackSecretSet 告知是否已配置） */
    private String callbackSecret;

    /**
     * 最近一次运行状态（列表页展示用，非列）：由 {@code WorkflowService.listVisible} 批量回填，
     * 不落库——它是一次查询的派生信息，单独建列会与真实运行记录不一致。
     */
    @TableField(exist = false)
    private String lastRunStatus;
    @TableField(exist = false)
    private LocalDateTime lastRunAt;
    @TableField(exist = false)
    private String lastRunId;

    /** 当前登录用户对该工作流的有效权限（MANAGE/READ，非列；列表与详情据此渲染只读/可编辑） */
    @TableField(exist = false)
    private String myPermission;
    /** 是否为当前用户创建（非列）：列表分栏「我创建的 / 共享给我的」 */
    @TableField(exist = false)
    private Boolean mine;
    /** 是否已配置回调签名密钥（非列）：密钥本体不外泄，前端只需知道"已设置" */
    @TableField(exist = false)
    private Boolean callbackSecretSet;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
