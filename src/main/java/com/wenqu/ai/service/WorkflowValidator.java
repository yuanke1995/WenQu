package com.wenqu.ai.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流 DSL 结构校验器：节点注册表、引用完整性、分支键对账、成环检测、可达性、变量引用检查。
 * 全部 fail-loud——每条问题独立成句报出（节点 id 带上），供编辑器逐项定位。
 * <p>
 * <b>分期口径</b>：结构校验对全部已登记类型一视同仁（画布可以先画 M3 的节点）；
 * 能不能执行由 {@link WorkflowEngine#EXECUTABLE} 决定（编译期拒绝未开放类型）。
 *
 * @author yuanke
 */
@Component
public class WorkflowValidator {

    /** 节点 id 约束：字母数字下划线连字符（变量引用 {{id.key}} 以它寻址，不能含 . 与 {}） */
    private static final Pattern NODE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** 变量引用：{{nodeId.key}}（key 检查在 M1 节点输出类型化后加严，这里先查节点存在性） */
    private static final Pattern VAR_REF = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)\\.([A-Za-z0-9_]+)\\s*\\}\\}");

    /** 规模上限：防误操作拖出巨图拖垮校验/编译（真实场景几十个节点封顶） */
    private static final int MAX_NODES = 100;
    private static final int MAX_EDGES = 300;

    /** 节点类型注册表（全部规划类型；executable 与否见引擎） */
    public static final Set<String> KNOWN_TYPES = Set.of(
            "start", "end", "llm", "retrieval", "condition",
            "http", "code", "subagent", "approval", "loop", "template",
            "skill", "mcp");
    /** 开始节点的固定 id 语义：变量引用 {{start.xxx}} 指向入参 */
    public static final String START_SEMANTIC = "start";
    public static final String END_TYPE = "end";
    public static final String CONDITION_TYPE = "condition";
    /** 路由器节点：出边携带分支键、声明分支必须连线（condition/approval/loop 同范式，与引擎 ROUTER_TYPES 同口径） */
    public static final Set<String> ROUTER_TYPES = Set.of(CONDITION_TYPE, "approval", "loop");

    /** 校验全部通过返回空列表；否则每条问题一句（可多句） */
    public List<String> validate(WorkflowDsl dsl) {
        List<String> errors = new ArrayList<>();
        if (dsl == null || dsl.getNodes() == null || dsl.getNodes().isEmpty()) {
            errors.add("DSL 中没有任何节点");
            return errors;
        }
        if (dsl.getNodes().size() > MAX_NODES) {
            errors.add("节点数超过上限 " + MAX_NODES + "（当前 " + dsl.getNodes().size() + "）");
            return errors;
        }
        // ---- 节点：id 合法性 / 唯一性 / 类型注册表 ----
        Map<String, WorkflowDsl.Node> byId = new LinkedHashMap<>();
        Set<String> startIds = new LinkedHashSet<>();
        Set<String> endIds = new LinkedHashSet<>();
        for (WorkflowDsl.Node n : dsl.getNodes()) {
            String where = "节点[" + safeRef(n) + "]";
            if (n.getId() == null || n.getId().isBlank()) {
                errors.add(where + " 缺少 id");
                continue;
            }
            if (!NODE_ID.matcher(n.getId()).matches()) {
                errors.add(where + " 的 id「" + n.getId() + "」只允许字母/数字/下划线/连字符（1~64 位）");
                continue;
            }
            // __ 前缀是 StateGraph 的保留字（__START__/__END__）：带 __ 前缀的节点 addNode 能进、
            // compile() 才被 Node.validate 拒绝且文案陈旧——在校验期就拦下并讲清原因（实测结论）
            if (n.getId().startsWith("__")) {
                errors.add(where + " 的 id「" + n.getId() + "」不能以 __ 开头（执行引擎保留前缀）");
                continue;
            }
            if (byId.containsKey(n.getId())) {
                errors.add("节点 id「" + n.getId() + "」重复");
                continue;
            }
            if (n.getType() == null || !KNOWN_TYPES.contains(n.getType())) {
                errors.add(where + " 类型「" + n.getType() + "」未注册（可用：" + String.join(", ", KNOWN_TYPES) + "）");
                continue;
            }
            byId.put(n.getId(), n);
            if ("start".equals(n.getType())) startIds.add(n.getId());
            if (END_TYPE.equals(n.getType())) endIds.add(n.getId());
        }
        if (startIds.size() != 1) {
            errors.add("必须有且只有一个开始（start）节点（当前 " + startIds.size() + " 个）");
        }
        if (endIds.isEmpty()) {
            errors.add("至少需要一个结束（end）节点");
        }
        if (dsl.getEdges() != null && dsl.getEdges().size() > MAX_EDGES) {
            errors.add("连线数超过上限 " + MAX_EDGES + "（当前 " + dsl.getEdges().size() + "）");
        }

        // ---- 边：端点存在性 / 分支键对账 / 重复 ----
        Set<String> edgeKeys = new HashSet<>();
        List<WorkflowDsl.Edge> edges = dsl.getEdges() == null ? List.of() : dsl.getEdges();
        for (WorkflowDsl.Edge e : edges) {
            String where = "连线[" + nz(e.getFrom()) + " → " + nz(e.getTo()) + "]";
            if (e.getFrom() == null || e.getFrom().isBlank() || e.getTo() == null || e.getTo().isBlank()) {
                errors.add(where + " 起点或终点为空");
                continue;
            }
            if (!byId.containsKey(e.getFrom())) {
                errors.add(where + " 起点节点「" + e.getFrom() + "」不存在");
                continue;
            }
            if (!byId.containsKey(e.getTo())) {
                errors.add(where + " 终点节点「" + e.getTo() + "」不存在");
                continue;
            }
            boolean fromRouter = ROUTER_TYPES.contains(byId.get(e.getFrom()).getType());
            if (fromRouter) {
                if (e.getBranch() == null || e.getBranch().isBlank()) {
                    errors.add(where + " 来自路由节点（条件/审核/循环），必须携带分支键（branch）");
                } else if (!declaredBranches(byId.get(e.getFrom())).contains(e.getBranch())) {
                    errors.add(where + " 的分支键「" + e.getBranch() + "」未在该节点 branches 里声明");
                }
            } else if (e.getBranch() != null && !e.getBranch().isBlank()) {
                errors.add(where + " 只有条件类节点（condition/approval/loop）的出边才允许携带分支键");
            }
            if (!edgeKeys.add(e.getFrom() + ">" + e.getBranch() + ">" + e.getTo())) {
                errors.add(where + " 重复连线");
            }
            // start 是虚拟入口 / end 是执行终点：它们的方向性由引擎翻译层依赖（start 出边改从
            // StateGraph.START 出发、end 承接终止边），反向边在翻译层没有语义，结构期拦下讲清楚
            if ("start".equals(byId.get(e.getTo()).getType())) {
                errors.add(where + " 开始节点（start）不能作为连线的终点");
            }
            if (END_TYPE.equals(byId.get(e.getFrom()).getType())) {
                errors.add(where + " 结束节点（end）不能作为连线的起点");
            }
        }
        // 路由节点专项：声明了分支但没有出边 → 显式报（否则运行到该节点会"无路可走"静默卡死）；
        // approval 必须声明 approve/reject 两条分支；loop 必须声明 maxLoops（1~100）
        for (WorkflowDsl.Node n : byId.values()) {
            if (!ROUTER_TYPES.contains(n.getType())) continue;
            Set<String> declared = declaredBranches(n);
            if (declared.isEmpty()) {
                errors.add("路由节点「" + n.getId() + "」（" + n.getType() + "）未声明任何分支（config.branches）");
                continue;
            }
            if ("approval".equals(n.getType())) {
                if (!declared.contains("approve") || !declared.contains("reject")) {
                    errors.add("审核节点「" + n.getId() + "」必须声明 approve 与 reject 两条分支（当前："
                            + String.join("、", declared) + "）");
                }
            }
            if ("loop".equals(n.getType())) {
                Object ml = n.getConfig() == null ? null : n.getConfig().get("maxLoops");
                if (!(ml instanceof Number num) || num.intValue() < 1 || num.intValue() > 100) {
                    errors.add("循环节点「" + n.getId() + "」必须声明 maxLoops（1~100 的整数，防回跳失控）");
                }
            }
            Set<String> wired = new HashSet<>();
            for (WorkflowDsl.Edge e : edges) {
                if (n.getId().equals(e.getFrom()) && e.getBranch() != null) wired.add(e.getBranch());
            }
            for (String b : declared) {
                if (!wired.contains(b)) {
                    errors.add("路由节点「" + n.getId() + "」（" + n.getType() + "）的分支「" + b + "」声明了但没有连线");
                }
            }
        }

        // ---- 图结构：从 start 可达 / 回跳规则（M3）：环允许存在，但每条回边必须源自 loop 节点 ----
        String start = startIds.size() == 1 ? startIds.iterator().next() : null;
        if (start != null) {
            for (String id : reachable(byId, edges, start)) {
                errors.add("节点「" + id + "」从开始节点不可达（悬空）");
            }
            for (String[] back : findBackEdges(byId, edges, start)) {
                String u = back[0];
                String v = back[1];
                if (!"loop".equals(byId.get(u).getType())) {
                    errors.add("连线「" + u + " → " + v + "」构成回跳，但只有循环节点（loop）可以发起回跳"
                            + "（把这段逻辑改画为：loop 节点声明分支，回跳边从 loop 节点引出）");
                }
            }
        }

        // ---- 变量引用：{{nodeId.key}} 的节点必须存在 ----
        for (WorkflowDsl.Node n : byId.values()) {
            for (String ref : collectVarRefs(n.getConfig())) {
                String owner = ref.substring(0, ref.indexOf('.'));
                if (START_SEMANTIC.equals(owner)) continue;
                if (!byId.containsKey(owner)) {
                    errors.add("节点「" + n.getId() + "」的配置里引用了不存在的节点 {{" + ref + "}}");
                }
            }
        }
        return errors;
    }

    /** 条件节点的分支键声明（config.branches = [{key,...},...]），取不到返回空集（缺 branches 由主校验报） */
    @SuppressWarnings("unchecked")
    private static Set<String> declaredBranches(WorkflowDsl.Node n) {
        Set<String> out = new LinkedHashSet<>();
        Object branches = n.getConfig() == null ? null : n.getConfig().get("branches");
        if (branches instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("key") != null) out.add(String.valueOf(m.get("key")));
                else if (o instanceof String s && !s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }

    /** 从 start 做 DFS，返回「不可达」节点集合（其余可达） */
    private static Set<String> reachable(Map<String, WorkflowDsl.Node> byId, List<WorkflowDsl.Edge> edges, String start) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);
        visited.add(start);
        Map<String, List<String>> adj = adjacency(edges);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            for (String next : adj.getOrDefault(cur, List.of())) {
                if (byId.containsKey(next) && visited.add(next)) stack.push(next);
            }
        }
        Set<String> unreachable = new HashSet<>(byId.keySet());
        unreachable.removeAll(visited);
        return unreachable;
    }

    /**
     * 三色 DFS 找<b>全部回边</b>（u→v，v 在当前 DFS 栈上即回边）：
     * M3 起 loop 节点打破 DAG 限制，成环检查从"拒绝一切环"放宽为"回边必须源自 loop 节点"，
     * 所以这里不再还原环路径，而是逐条返回回边供规则裁决。
     */
    private static List<String[]> findBackEdges(Map<String, WorkflowDsl.Node> byId, List<WorkflowDsl.Edge> edges, String start) {
        Map<String, List<String>> adj = adjacency(edges);
        Map<String, Integer> color = new LinkedHashMap<>();   // 0=未访 1=在栈 2=完成
        List<String[]> backs = new ArrayList<>();
        cycleDfs(start, adj, color, backs);
        return backs;
    }

    /** 三色 DFS；发现回边（下一跳在栈上）即记入 backs */
    private static void cycleDfs(String cur, Map<String, List<String>> adj,
                                 Map<String, Integer> color, List<String[]> backs) {
        color.put(cur, 1);
        for (String next : adj.getOrDefault(cur, List.of())) {
            Integer c = color.get(next);
            if (c != null && c == 1) {
                backs.add(new String[]{cur, next});
                continue;
            }
            if (c == null) {
                cycleDfs(next, adj, color, backs);
            }
        }
        color.put(cur, 2);
    }

    private static Map<String, List<String>> adjacency(List<WorkflowDsl.Edge> edges) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (WorkflowDsl.Edge e : edges) {
            if (e.getFrom() == null || e.getTo() == null) continue;
            adj.computeIfAbsent(e.getFrom(), k -> new ArrayList<>()).add(e.getTo());
        }
        return adj;
    }

    /** 递归收集 config 里所有字符串值中的变量引用（去重，形如 "nodeId.key"） */
    private static Set<String> collectVarRefs(Object config) {
        Set<String> out = new LinkedHashSet<>();
        collect(config, out);
        return out;
    }

    private static void collect(Object v, Set<String> out) {
        if (v instanceof String s) {
            Matcher m = VAR_REF.matcher(s);
            while (m.find()) out.add(m.group(1) + "." + m.group(2));
        } else if (v instanceof Map<?, ?> map) {
            for (Object o : map.values()) collect(o, out);
        } else if (v instanceof List<?> list) {
            for (Object o : list) collect(o, out);
        }
    }

    private static String safeRef(WorkflowDsl.Node n) {
        return n.getId() == null ? (n.getType() == null ? "未命名" : n.getType()) : n.getId();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "?" : s;
    }
}
