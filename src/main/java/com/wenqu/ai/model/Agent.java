package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /**
     * 图标：'wenqu'=问渠品牌标（BrandMark）；其它非空值=emoji 字符，原样交给前端渲染；
     * NULL/空=默认展示——内置「问渠」默认用问渠品牌标，其余智能体用机器人图标。
     */
    private String icon;

    /** 描述 */
    private String description;


    /** 系统提示词（提示词归智能体管；空=使用内置默认提示词） */
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

    /**
     * 内置标记：1=系统内置「问渠」智能体——<b>全局唯一</b>（启动时由 AgentService 维护：多余降级、缺失播种），
     * 所有登录用户可读可用，仅管理员级可配置、不可删除；0/NULL=普通智能体。
     */
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

    /** 联网搜索工具（webSearch）：1=开 0=关 NULL=继承 */
    private Integer toolWebsearch;

    /**
     * 有副作用工具（沙盒/MCP）执行审批（人在回路）：auto=自动执行（默认）、
     * ask=模型每次调用前暂停等用户确认（拒绝/超时以错误结果回给模型继续）、off=禁用这两类工具。
     * NULL=auto。游客分享会话本就不暴露这两类工具，与审批模式无关。
     */
    private String toolApprovalMode;

    /**
     * 单轮工具调用步数上限（全部工具合计）：NULL=用全局 agent.maxToolSteps；0=不限制。
     * 防模型陷入"调用工具→不满意→再调用"的失控循环烧 token；达到上限后模型收到
     * 错误结果并被要求直接给出最终回答。
     */
    private Integer maxToolSteps;

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

    /**
     * M4：绑定的工作流 ID（chatflow 语义）。非空时该智能体的回答<b>由工作流产出</b>——
     * 会话每轮把用户问题送进图（start 节点入参），end 节点的出参即回答；
     * 智能体自带的人设/知识库/工具配置在该模式下<b>不参与</b>（逻辑由工作流定义）。
     * 跑的是工作流的<b>已发布版本</b>（绑定与运行时都会校验，未发布 fail-loud）。
     */
    private String workflowId;

    /** 是否默认智能体：0=否 1=是（前端下拉预选，不自动强制应用） */
    private Integer isDefault;

    /** 创建人（登录用户 uid） */
    private String createdBy;

    /** 共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=私有，仅创建者与管理员级可见) */
    private String shareConfig;

    /**
     * 当前请求者是否可管理（列表接口按登录态回填，仅供前端收起配置/共享/发布/删除入口；
     * 真正的判权在写路径按 ResourceVisibilityService 再走一遍，此处不做授权依据）。
     */
    @TableField(exist = false)
    private Integer manageable;

    /**
     * 公开发布状态（列表接口按 c_ai_agent_share 批量回填，仅供前端卡片标「已发布」）：
     * 1=发布中（share 行存在且 enabled=1）；0/NULL=未发布或已停用。
     * 真正的游客判权走 {@link AgentShareService#resolveGuest}，此处不做授权依据。
     */
    @TableField(exist = false)
    private Integer published;

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
