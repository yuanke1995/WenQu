package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentBinding;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code c_ai_agent} 能力列 ↔ {@link AgentBinding} 的唯一转换点。
 * <p>双写期两处都调它：写库时列→绑定，读库时绑定→列。两边口径只有这一份实现，
 * 迁移完成后删掉「列」那一半即可，绑定结构不用动。
 * <p>能力标识与旧列的对应关系（一个能力 = 一个 flag 列 + 至多一个 selection 列 + 至多一段 config）：
 * <pre>
 * knowledge  tool_knowledge  + knowledge_base_ids/tags/filter/scope/disabled(config)
 * skill      tool_skill      + skills
 * mcp        tool_mcp        + mcps
 * http       tool_http       + http_tools
 * workflow   tool_workflow   + workflow_tools
 * builtin    tool_builtin    + builtin_tools
 * artifact   tool_artifact
 * browser    tool_browser
 * websearch  tool_websearch
 * deny       (无 flag)       + tool_deny
 * </pre>
 */
public final class AgentBindingCodec {

    public static final String CAP_KNOWLEDGE = "knowledge";
    public static final String CAP_SKILL = "skill";
    public static final String CAP_MCP = "mcp";
    public static final String CAP_HTTP = "http";
    public static final String CAP_WORKFLOW = "workflow";
    public static final String CAP_BUILTIN = "builtin";
    public static final String CAP_ARTIFACT = "artifact";
    public static final String CAP_BROWSER = "browser";
    public static final String CAP_WEBSEARCH = "websearch";
    public static final String CAP_DENY = "deny";

    private AgentBindingCodec() {
    }

    /** Agent 行 → 绑定列表。只产出「有值」的能力行，NULL 保持「未配置」语义。 */
    public static List<AgentBinding> fromAgent(Agent a) {
        List<AgentBinding> out = new ArrayList<>();
        if (a == null || a.getId() == null) return out;

        JSONObject knowledge = new JSONObject();
        putIfPresent(knowledge, "knowledgeBaseIds", a.getKnowledgeBaseIds());
        putIfPresent(knowledge, "knowledgeTags", a.getKnowledgeTags());
        putIfPresent(knowledge, "knowledgeFilter", a.getKnowledgeFilter());
        putIfPresent(knowledge, "knowledgeScope", a.getKnowledgeScope());
        if (a.getKnowledgeDisabled() != null) knowledge.put("knowledgeDisabled", a.getKnowledgeDisabled());
        if (a.getToolKnowledge() != null || !knowledge.isEmpty()) {
            out.add(row(a.getId(), CAP_KNOWLEDGE, a.getToolKnowledge(), null,
                    knowledge.isEmpty() ? null : knowledge.toJSONString()));
        }
        add(a, out, CAP_SKILL, a.getToolSkill(), a.getSkills());
        add(a, out, CAP_MCP, a.getToolMcp(), a.getMcps());
        add(a, out, CAP_HTTP, a.getToolHttp(), a.getHttpTools());
        add(a, out, CAP_WORKFLOW, a.getToolWorkflow(), a.getWorkflowTools());
        add(a, out, CAP_BUILTIN, a.getToolBuiltin(), a.getBuiltinTools());
        add(a, out, CAP_ARTIFACT, a.getToolArtifact(), null);
        add(a, out, CAP_BROWSER, a.getToolBrowser(), null);
        add(a, out, CAP_WEBSEARCH, a.getToolWebsearch(), null);
        if (a.getToolDeny() != null) {
            out.add(row(a.getId(), CAP_DENY, null, a.getToolDeny(), null));
        }
        return out;
    }

    /** 绑定列表 → 写回 Agent 视图（读侧切表后用；缺某个能力即视为未配置）。 */
    public static void applyTo(Agent a, List<AgentBinding> bindings) {
        if (a == null || bindings == null) return;
        // 先清空，避免同一 Agent 对象被复用（resolveForTurn 的副本）时残留旧值
        clearCapabilities(a);
        for (AgentBinding b : bindings) {
            if (b == null || b.getCapability() == null) continue;
            switch (b.getCapability()) {
                case CAP_KNOWLEDGE -> {
                    a.setToolKnowledge(b.getFlag());
                    applyKnowledgeConfig(a, b.getConfig());
                }
                case CAP_SKILL -> {
                    a.setToolSkill(b.getFlag());
                    a.setSkills(b.getSelection());
                }
                case CAP_MCP -> {
                    a.setToolMcp(b.getFlag());
                    a.setMcps(b.getSelection());
                }
                case CAP_HTTP -> {
                    a.setToolHttp(b.getFlag());
                    a.setHttpTools(b.getSelection());
                }
                case CAP_WORKFLOW -> {
                    a.setToolWorkflow(b.getFlag());
                    a.setWorkflowTools(b.getSelection());
                }
                case CAP_BUILTIN -> {
                    a.setToolBuiltin(b.getFlag());
                    a.setBuiltinTools(b.getSelection());
                }
                case CAP_ARTIFACT -> a.setToolArtifact(b.getFlag());
                case CAP_BROWSER -> a.setToolBrowser(b.getFlag());
                case CAP_WEBSEARCH -> a.setToolWebsearch(b.getFlag());
                case CAP_DENY -> a.setToolDeny(b.getSelection());
                default -> { /* 未知能力：忽略，向前兼容 */ }
            }
        }
    }

    /** 能力列清空（只清能力，不碰身份/人设/策略）。 */
    public static void clearCapabilities(Agent a) {
        a.setToolKnowledge(null);
        a.setToolSkill(null);
        a.setToolMcp(null);
        a.setToolHttp(null);
        a.setToolWorkflow(null);
        a.setToolBuiltin(null);
        a.setToolArtifact(null);
        a.setToolBrowser(null);
        a.setToolWebsearch(null);
        a.setToolDeny(null);
        a.setSkills(null);
        a.setMcps(null);
        a.setBuiltinTools(null);
        a.setHttpTools(null);
        a.setWorkflowTools(null);
        a.setKnowledgeBaseIds(null);
        a.setKnowledgeTags(null);
        a.setKnowledgeFilter(null);
        a.setKnowledgeScope(null);
        a.setKnowledgeDisabled(null);
    }

    /** 差异比较用：能力列部分的稳定指纹（双写校验/测试用）。 */
    public static String fingerprint(Agent a) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (AgentBinding b : fromAgent(a)) {
            m.put(b.getCapability(), (b.getFlag() == null ? "-" : b.getFlag()) + "|"
                    + (b.getSelection() == null ? "null" : b.getSelection()) + "|"
                    + (b.getConfig() == null ? "null" : b.getConfig()));
        }
        return JSON.toJSONString(m);
    }

    private static void add(Agent a, List<AgentBinding> out, String capability, Integer flag, String selection) {
        if (flag == null && selection == null) return;
        out.add(row(a.getId(), capability, flag, selection, null));
    }

    private static AgentBinding row(String agentId, String capability, Integer flag, String selection, String config) {
        AgentBinding b = new AgentBinding();
        b.setAgentId(agentId);
        b.setCapability(capability);
        b.setFlag(flag);
        b.setSelection(selection);
        b.setConfig(config);
        b.setUpdateTime(LocalDateTime.now());
        return b;
    }

    private static void putIfPresent(JSONObject o, String key, String value) {
        if (value != null) o.put(key, value);
    }

    private static void applyKnowledgeConfig(Agent a, String config) {
        if (config == null || config.isBlank()) return;
        try {
            JSONObject o = JSON.parseObject(config);
            if (o == null) return;
            a.setKnowledgeBaseIds(o.getString("knowledgeBaseIds"));
            a.setKnowledgeTags(o.getString("knowledgeTags"));
            a.setKnowledgeFilter(o.getString("knowledgeFilter"));
            a.setKnowledgeScope(o.getString("knowledgeScope"));
            a.setKnowledgeDisabled(o.getInteger("knowledgeDisabled"));
        } catch (Exception ignored) {
            // 坏配置按「无知识范围」处理：与旧列读取期非法 JSON 的降级口径一致
        }
    }
}
