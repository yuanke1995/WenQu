package com.wisesoft.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M5 模板库：内置工作流模板（排班计划 2026-09-29「M5 运营增强」第 1 项）。
 * <p>
 * 模板是<b>可运行的 DSL</b>（不是截图示例）：选用即创建工作流并进画布，用户改配置
 * （模型/知识库/措辞）后直接调试运行。三个内置模板对齐排班计划原文：
 * <ol>
 *   <li><b>检索问答</b>：start → retrieval → llm → end——最常用的知识库问答最小闭环；</li>
 *   <li><b>并行多视角</b>：start → llm×2（并行）→ template 聚合 → llm 总结 → end——
 *       对齐主问答管线「多视角并行检索」的工作流形态（llm 而非 subagent，模板开箱即跑，
 *       不依赖用户先建子智能体）；</li>
 *   <li><b>审核流水线</b>：start → retrieval → llm → approval →（approve→end 输出回答 /
 *       reject→end 输出拒绝说明）——人在回路的最小可跑样例。</li>
 * </ol>
 * <p>
 * 实现口径：模板 DSL 用代码构造（非字符串拼接），坐标瀑布排布（x 递增 260）；llm 节点
 * {@code modelRef} 留空（运行时回落触发者个人默认模型）、retrieval 的 {@code kbIds} 空数组
 * （全局检索）——保证「选用即能跑通」的最小前提，个性化配置留给画布。
 * 构造结果经 {@link WorkflowValidator} 全量校验（用例覆盖），坏模板不会进清单。
 *
 * @author yuanke
 */
public final class WorkflowTemplates {

    private WorkflowTemplates() {
    }

    /** 一个内置模板：key（前端 stable 标识）/ 名称 / 描述 / DSL（JSON 字符串，画布可直接加载） */
    public record Template(String key, String name, String description, int nodeCount, String dsl) {
    }

    /**
     * 全部内置模板。顺序即展示顺序。dsl 字段是画布/创建接口直接可用的 JSON 文本。
     */
    public static List<Template> list() {
        List<Template> out = new ArrayList<>();
        out.add(new Template("retrieval-qa", "检索问答",
                "知识库问答最小闭环：用户问题 → 知识库检索 → LLM 依据检索内容回答。选用后在检索节点挑知识库、llm 节点挑模型即可调试。",
                4, buildRetrievalQa()));
        out.add(new Template("multi-view", "并行多视角",
                "两个 LLM 分别从优势与风险视角并行分析同一问题，模板节点聚合两路输出，再由总结节点给出综合结论。",
                6, buildMultiView()));
        out.add(new Template("approval-flow", "审核流水线",
                "检索作答后交人工审核：批准则输出回答，拒绝则输出未通过说明。选用后可在画布调试里体验审批卡（批准/拒绝续跑）。",
                6, buildApprovalFlow()));
        return out;
    }

    // --------------------------------------------------------------------------------------------------
    // 模板构造（画布坐标瀑布排布：x 从 80 起每节点 +260，y 居中 180）
    // --------------------------------------------------------------------------------------------------

    /** 模板一：检索问答（start → retrieval → llm → end） */
    private static String buildRetrievalQa() {
        List<WorkflowDsl.Node> nodes = new ArrayList<>();
        nodes.add(node("start", "start", 0, Map.of(
                "inputs", List.of(input("question", true)))));
        nodes.add(node("retrieval_1", "retrieval", 1, Map.of(
                "query", "{{start.question}}",
                "kbIds", new ArrayList<>(),
                "topK", 5)));
        nodes.add(node("llm_1", "llm", 2, Map.of(
                "prompt", "请依据以下检索到的资料回答用户问题。资料中没有的信息不要编造。\n\n【检索资料】\n{{retrieval_1.text}}\n\n【用户问题】\n{{start.question}}",
                "temperature", 0.7)));
        nodes.add(node("end_1", "end", 3, Map.of(
                "outputs", linkedMap("answer", "{{llm_1.answer}}"))));
        return toJson(nodes, straightEdges(nodes));
    }

    /** 模板二：并行多视角（start → llm_1/llm_2 并行 → template 聚合 → llm_3 总结 → end） */
    private static String buildMultiView() {
        List<WorkflowDsl.Node> nodes = new ArrayList<>();
        nodes.add(node("start", "start", 0, Map.of(
                "inputs", List.of(input("question", true)))));
        // 并行扇出：start 出两条边（StateGraph.START 扇出已生产验证），两 llm 汇入 template（扇入）
        nodes.add(node("llm_pro", "llm", 1, Map.of(
                "prompt", "你是优势分析视角。请只从「机会与优势」的角度分析下面的问题，给出 2~4 条要点：\n\n{{start.question}}",
                "temperature", 0.7)));
        nodes.add(node("llm_risk", "llm", 1, Map.of(
                "prompt", "你是风险分析视角。请只从「风险与隐患」的角度分析下面的问题，给出 2~4 条要点：\n\n{{start.question}}",
                "temperature", 0.7)));
        nodes.add(node("template_1", "template", 2, Map.of(
                "template", "【优势视角】\n{{llm_pro.answer}}\n\n【风险视角】\n{{llm_risk.answer}}")));
        nodes.add(node("llm_sum", "llm", 3, Map.of(
                "prompt", "以下是同一问题的两路分析：\n\n{{template_1.text}}\n\n请综合两个视角给出平衡的最终结论（先各用一句话概括两路要点，再给结论）。",
                "temperature", 0.5)));
        nodes.add(node("end_1", "end", 4, Map.of(
                "outputs", linkedMap("answer", "{{llm_sum.answer}}"))));
        List<WorkflowDsl.Edge> edges = new ArrayList<>();
        edges.add(edge("start", "llm_pro"));
        edges.add(edge("start", "llm_risk"));
        edges.add(edge("llm_pro", "template_1"));
        edges.add(edge("llm_risk", "template_1"));
        edges.add(edge("template_1", "llm_sum"));
        edges.add(edge("llm_sum", "end_1"));
        return toJson(nodes, edges);
    }

    /** 模板三：审核流水线（start → retrieval → llm → approval → approve: end 回答 / reject: end 拒绝说明） */
    private static String buildApprovalFlow() {
        List<WorkflowDsl.Node> nodes = new ArrayList<>();
        nodes.add(node("start", "start", 0, Map.of(
                "inputs", List.of(input("question", true)))));
        nodes.add(node("retrieval_1", "retrieval", 1, Map.of(
                "query", "{{start.question}}",
                "kbIds", new ArrayList<>(),
                "topK", 5)));
        nodes.add(node("llm_1", "llm", 2, Map.of(
                "prompt", "请依据以下检索到的资料起草一份供人工审核的回答：\n\n【检索资料】\n{{retrieval_1.text}}\n\n【用户问题】\n{{start.question}}",
                "temperature", 0.7)));
        // approval：approve/reject 两分支必须声明且都连线（校验器强制）
        nodes.add(node("approval_1", "approval", 3, Map.of(
                "prompt", "请审核以下待发出的回答（来自知识库检索作答）：\n\n{{llm_1.answer}}",
                "timeoutSeconds", 120,
                "branches", List.of(
                        branch("approve", "审核通过"),
                        branch("reject", "审核不通过")))));
        // 批准 → 输出原回答；拒绝 → 输出未通过说明（两个 end，路径产出各自可回放）
        nodes.add(node("end_ok", "end", 4, Map.of(
                "outputs", linkedMap("answer", "{{llm_1.answer}}"))));
        nodes.add(node("end_reject", "end", 4, Map.of(
                "outputs", linkedMap("answer", "该回答未通过人工审核，已被驳回。"))));
        List<WorkflowDsl.Edge> edges = new ArrayList<>();
        edges.add(edge("start", "retrieval_1"));
        edges.add(edge("retrieval_1", "llm_1"));
        edges.add(edge("llm_1", "approval_1"));
        edges.add(edge("approval_1", "end_ok", "approve"));
        edges.add(edge("approval_1", "end_reject", "reject"));
        return toJson(nodes, edges);
    }

    // ---- 构造工具（坐标瀑布排布与节点/边的小工厂） ----

    private static WorkflowDsl.Node node(String id, String type, int col, Map<String, Object> config) {
        WorkflowDsl.Node n = new WorkflowDsl.Node();
        n.setId(id);
        n.setType(type);
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", 80 + col * 260);
        pos.put("y", 180);
        n.setPosition(pos);
        n.setConfig(new LinkedHashMap<>(config));
        return n;
    }

    private static WorkflowDsl.Edge edge(String from, String to) {
        return edge(from, to, null);
    }

    private static WorkflowDsl.Edge edge(String from, String to, String branch) {
        WorkflowDsl.Edge e = new WorkflowDsl.Edge();
        e.setFrom(from);
        e.setTo(to);
        e.setBranch(branch);
        return e;
    }

    /** 直线串联：nodes 依序首尾相连 */
    private static List<WorkflowDsl.Edge> straightEdges(List<WorkflowDsl.Node> nodes) {
        List<WorkflowDsl.Edge> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size() - 1; i++) {
            edges.add(edge(nodes.get(i).getId(), nodes.get(i + 1).getId()));
        }
        return edges;
    }

    private static Map<String, Object> input(String key, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("type", "string");
        m.put("required", required);
        return m;
    }

    private static Map<String, Object> branch(String key, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        return m;
    }

    private static Map<String, Object> linkedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static String toJson(List<WorkflowDsl.Node> nodes, List<WorkflowDsl.Edge> edges) {
        WorkflowDsl dsl = new WorkflowDsl();
        dsl.setNodes(nodes);
        dsl.setEdges(edges);
        return dsl.toJson();
    }
}
