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

    /** 是否默认智能体：0=否 1=是（前端下拉预选，不自动强制应用） */
    private Integer isDefault;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
