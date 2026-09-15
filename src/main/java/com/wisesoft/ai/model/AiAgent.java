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
public class AiAgent {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 智能体名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 模型覆盖（空=继承全局 chat.model） */
    private String model;

    /** 系统提示词覆盖（空=继承全局 chat.systemPrompt） */
    private String systemPrompt;

    /** 知识库范围：all 或 文档 ID 逗号分隔（空=all，即继承全局全部文档） */
    private String knowledgeScope;

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
     * 技能范围（具体项筛选，对齐语析的 skills 列表语义）：
     * NULL=跟随全局（注入全部可用技能）；空串=显式不注入任何技能；逗号分隔的技能名=只注入这些。
     * 与 toolSkill 的分工：toolSkill 决定"这类能力开不开"，本字段在其开启后限定"具体用哪几个"。
     */
    private String skills;

    /** MCP Server 范围（具体项筛选）：NULL=跟随全局（连全部已启用 server）；空串=一个都不连；逗号分隔=只连这些 */
    private String mcps;

    /** 内置工具范围（具体项筛选）：NULL=跟随全局；空串=不用任何内置工具；逗号分隔=只用这些 */
    private String builtinTools;

    /** 是否默认智能体：0=否 1=是（前端下拉预选，不自动强制应用） */
    private Integer isDefault;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
