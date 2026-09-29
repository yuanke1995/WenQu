package com.wisesoft.ai.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wisesoft.ai.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流执行引擎：DSL → StateGraph 的<b>唯一翻译层</b>（上游 API 变动只改这里）。
 * <p>
 * <b>分期口径（排班计划 2026-09-29）</b>：
 * <ul>
 *   <li>M0（当前）：结构校验 + 编译骨架——start/end 虚拟化为 StateGraph 的 START/END，
 *       无真实节点时用直通节点保证图可编译；验证"翻译层立起来"，不含执行；</li>
 *   <li>M1：llm / retrieval / condition 三类真实节点（AsyncNodeAction / addConditionalEdges）
 *       + run() 执行 + node_traces 落库。届时需确认<b>未注册状态键</b>在 KeyStrategyFactory 下的行为
 *       （节点输出键是动态的，必要时策略表对任意键默认 REPLACE）——已记入排班文档待验证项；</li>
 *   <li>M3：http / code / subagent / approval / loop / template。</li>
 * </ul>
 * <p>
 * 线程模型继承 {@code SubAgentOrchestrator} 的既有结论：并行分支跑在 reactor 线程池上，
 * ThreadLocal 不可用——M1 起节点间数据一律走图 state + RunCtx 显式传递，检索参数覆盖走
 * {@code runWithOverrides} 重放，禁止在引擎内新引 ThreadLocal 依赖。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowEngine {

    /** 变量引用语法：{{nodeId.key}}（与校验器同一口径） */
    public static final Pattern VAR_REF = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)\\.([A-Za-z0-9_]+)\\s*\\}\\}");

    private final WorkflowValidator validator;

    /**
     * 当前可编译执行的节点类型（M0：仅 start/end，二者虚拟化，无真实执行体）。
     * M1 增 llm / retrieval / condition；M3 增 http / code / subagent / approval / loop / template。
     */
    public static final List<String> EXECUTABLE = List.of("start", "end");

    /**
     * 编译 dry-run：校验 DSL → 翻译成 StateGraph → compile()。
     * M0 里 start/end 没有执行体，编译本身即"图结构合法"的最终背书；执行入口（run）M1 交付。
     */
    public CompiledGraph compile(WorkflowDsl dsl) {
        List<String> errors = validator.validate(dsl);
        if (!errors.isEmpty()) {
            throw new BizException("工作流校验未通过：" + String.join("；", errors));
        }
        for (WorkflowDsl.Node n : dsl.getNodes()) {
            if (!EXECUTABLE.contains(n.getType())) {
                throw new BizException("节点「" + n.getId() + "」的类型 " + n.getType()
                        + " 尚未开放执行（当前仅支持 " + String.join("/", EXECUTABLE)
                        + "；llm/retrieval/condition 随 M1、其余随 M3 开放）");
            }
        }
        try {
            // M0 无真实节点写状态：空策略表即可；M1 起按节点输出键登记（未注册键行为待实测，见类注）
            KeyStrategyFactory ksf = () -> new HashMap<>();
            StateGraph graph = new StateGraph("workflow", ksf);

            // 翻译：start/end 是虚拟概念——start 的出边改从 StateGraph.START 出发，
            // 入 end 的边改接到 StateGraph.END；真实节点（M1 起）才 addNode。
            boolean hasRealNodes = dsl.getNodes().stream().anyMatch(n ->
                    !"start".equals(n.getType()) && !"end".equals(n.getType()));
            if (!hasRealNodes) {
                // 无真实节点（M0 最小图 start→end）：StateGraph 对 START→END 直连的合法性未经验证，
                // 用一个直通节点兜底，保证"最小图可编译"不依赖库的边角行为。
                // 命名注意：__ 前缀（如 __END__/__START__）是库的保留字，节点名带 __ 前缀会被
                // Node.validate 以陈旧文案"END is not a valid node id!"拒绝（已实测），故用 wf_ 前缀。
                graph.addNode("wf_passthrough", AsyncNodeAction.node_async(state -> Map.of()));
                graph.addEdge(StateGraph.START, "wf_passthrough");
                graph.addEdge("wf_passthrough", StateGraph.END);
            } else {
                for (WorkflowDsl.Node n : dsl.getNodes()) {
                    if ("start".equals(n.getType()) || "end".equals(n.getType())) continue;
                    graph.addNode(n.getId(), AsyncNodeAction.node_async(state -> Map.of())); // M1 替换为真实动作
                }
                for (WorkflowDsl.Edge e : dsl.getEdges()) {
                    String from = "start".equals(e.getFrom()) ? StateGraph.START : e.getFrom();
                    String to = "end".equals(e.getTo()) ? StateGraph.END : e.getTo();
                    graph.addEdge(from, to);
                }
            }
            CompiledGraph compiled = graph.compile();
            log.info("[WORKFLOW] DSL 编译通过：{} 节点 / {} 边", dsl.getNodes().size(), dsl.getEdges().size());
            return compiled;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 库层拒绝（图结构/参数问题）：原文透出，fail-loud 不吞
            throw new BizException("图编译失败：" + e.getMessage());
        }
    }

    /**
     * 变量渲染：把模板里的 <code>{{nodeId.key}}</code> 替换为 lookup 的返回值
     * （null 渲染为空串；缺失键是运行期 fail-loud 的判定项，M1 在执行层裁决）。
     * M0 供编译期引用校验与 M1 执行共用同一语法口径。
     */
    public static String render(String template, java.util.function.Function<String, Object> lookup) {
        if (template == null || template.isEmpty()) return template;
        Matcher m = VAR_REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = lookup.apply(m.group(1) + "." + m.group(2));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 模板里的全部引用键（形如 "nodeId.key"，去重保序）——M1 执行前检查引用可解析用 */
    public static List<String> refsOf(String template) {
        List<String> out = new ArrayList<>();
        if (template == null) return out;
        Matcher m = VAR_REF.matcher(template);
        while (m.find()) {
            String ref = m.group(1) + "." + m.group(2);
            if (!out.contains(ref)) out.add(ref);
        }
        return out;
    }
}
