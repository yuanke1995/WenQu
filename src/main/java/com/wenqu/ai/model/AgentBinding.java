package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体能力绑定：一行 = 一个智能体的一种能力。
 * <p>它是 {@code c_ai_agent} 上那批能力列的权威存储。三态语义与旧列一一对应：
 * {@code flag} 1=开 / 0=关 / NULL=跟随全局；{@code selection} NULL=未指定（全挂）、
 * 空串=显式不用、非空=具体项（逗号串或 JSON）。
 * <p>{@code config} 放能力私有配置（目前是知识库范围/标签/过滤条件/是否禁用检索的 JSON），
 * 避免为每种能力再开一列。
 */
@Data
@TableName("c_ai_agent_binding")
public class AgentBinding {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 智能体 ID */
    private String agentId;

    /** 能力标识：knowledge/skill/mcp/http/workflow/builtin/artifact/browser/websearch/deny */
    private String capability;

    /** 三态开关：1=开 0=关 NULL=跟随全局 */
    private Integer flag;

    /** 具体项：逗号串或 JSON；NULL=未指定，空串=显式不用 */
    private String selection;

    /** 能力私有配置（JSON；目前用于 knowledge） */
    private String config;

    private LocalDateTime updateTime;
}
