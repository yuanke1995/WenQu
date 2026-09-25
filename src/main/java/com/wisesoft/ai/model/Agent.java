package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 智能体配置表（P3：4.1 Agent 配置——模型/知识库/工具/提示词）。
 * <p>
 * 智能体 = 一个可调用的预设：把「模型 / 系统提示词 / 知识库范围 / 工具开关」打包成命名配置，
 * 用户在对话页下拉切换；选中后该轮问答按智能体覆盖全局配置（未填的维度继承全局）。
 * 工具开关为三态：1=强制开、0=强制关、NULL=继承全局对应开关。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_agent")
public class Agent {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 智能体名称 */
    private String name;

    /** 描述 */
    private String description;


    /** 系统提示词覆盖（空=继承全局 chat.systemPrompt） */
    private String systemPrompt;

    /** 知识库范围：all 或 文档 ID 逗号分隔（空=all，即继承全局全部文档） */
    private String knowledgeScope;

    /**
     * 关联的知识库ID（逗号分隔）：决定该助手能看到哪些资料——**这是知识库范围的主路径**。
     * <p>空/NULL = 使用默认知识库；文档归属由 {@code c_ai_document.kb_id} 决定，
     * 因此不需要（也不应该）再由智能体逐个指定文档。
     * 原 {@link #knowledgeScope} 降级为「库内再细选文档」的可选项：先按库过滤，再按文档 ID 取交集。
     */
    private String knowledgeBaseIds;

    /**
     * 不使用知识库：0=使用（默认，按 knowledgeScope 约束范围）；1=纯角色智能体，整条跳过检索链路
     * （改写/深度思考检索/命中填充/子代理编排都不跑；用户手动 @ 的文档仍会被前置——手动指定优先于配置）。
     * 用于「通用法律顾问 / 写作助手」这类不挂资料的角色；与 knowledgeScope 互斥，开启时忽略范围。
     */
    private Integer knowledgeDisabled;

    /** 内置标记：1=系统内置（如默认「知识库助手」），禁止删除；0/NULL=普通智能体 */
    private Integer isBuiltin;

    /** 知识库检索工具：1=开 0=关 NULL=继承 */
    private Integer toolKnowledge;

    /** 内置工具：1=开 0=关 NULL=继承 */
    private Integer toolBuiltin;

    /** 技能工具（readSkill）：1=开 0=关 NULL=继承 */
    private Integer toolSkill;

    /** 产物交付工具：1=开 0=关 NULL=继承 */
    private Integer toolArtifact;

    /** MCP 工具：1=开 0=关 NULL=继承 */
    private Integer toolMcp;

    /**
     * 技能范围（具体项筛选，对齐通用智能体平台的 skills 列表语义）：
     * NULL=跟随全局（注入全部可用技能）；空串=显式不注入任何技能；逗号分隔的技能名=只注入这些。
     * 与 toolSkill 的分工：toolSkill 决定"这类能力开不开"，本字段在其开启后限定"具体用哪几个"。
     */
    private String skills;

    /** MCP Server 范围（具体项筛选）：NULL=跟随全局（连全部已启用 server）；空串=一个都不连；逗号分隔=只连这些 */
    private String mcps;

    /** 内置工具范围（具体项筛选）：NULL=跟随全局；空串=不用任何内置工具；逗号分隔=只用这些 */
    private String builtinTools;

    /**
     * 是否子智能体：0=主智能体（可在对话页直接选用）；1=子智能体（不直接选用，供主智能体委派）。
     * 子智能体说明：子智能体是同一张表里的一级智能体，只是用途不同。
     */
    private Integer isSubagent;

    /**
     * 主智能体可委派的子智能体 ID 列表：
     * NULL 或空串 → 不启用委派，编排仍走原有的「多视角并行检索」；
     * 逗号分隔 → 并行执行这些子智能体（各自用自己的提示词与知识库范围）。
     */
    private String subAgentIds;

    /**
     * 知识库范围解析：空 → null（不限制，用全部文档）；非空 → 允许的 docId 集合。
     * 子智能体编排需要按各自的范围过滤命中，故与字段放在一起，免得调用方各写一遍解析。
     */
    public java.util.Set<String> scopeDocIds() {
        if (knowledgeScope == null || knowledgeScope.isBlank()) return null;
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (String part : knowledgeScope.split(",")) {
            String t = part == null ? "" : part.trim();
            if (!t.isEmpty()) ids.add(t);
        }
        return ids.isEmpty() ? null : ids;
    }

    /** 是否默认智能体：0=否 1=是（前端下拉预选，不自动强制应用） */
    private Integer isDefault;

    /** 创建人（登录用户 uid） */
    private String createdBy;

    /** 共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见) */
    private String shareConfig;

    /**
     * 本智能体的检索参数覆盖（JSON，键为 retrieval.* / rerank.* 的短名，如
     * {"vectorWeight":0.8,"vecThreshold":0.35}；null/空=全部继承全局设置）。
     * 用途：不同智能体可按自己的场景定制检索策略（如法律助手提高阈值保精度、手册助手放宽保召回）。
     */
    private String queryParams;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
