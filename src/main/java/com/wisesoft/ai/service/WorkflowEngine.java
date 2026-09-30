package com.wisesoft.ai.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.service.HybridRetrievalService.Hit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 工作流执行引擎：DSL → StateGraph 的<b>唯一翻译层</b>（上游 API 变动只改这里）。
 * <p>
 * <b>分期口径（排班计划 2026-09-29）</b>：
 * <ul>
 *   <li>M0（已完成）：结构校验 + 编译骨架；</li>
 *   <li>M1（当前）：llm / retrieval / condition 三类真实节点（AsyncNodeAction / addConditionalEdges）
 *       + 运行执行 + 节点 trace 收集。M0 两个待验证项已源码取证：
 *       ① 未注册状态键默认 {@code KeyStrategy.REPLACE}、不丢弃不报错（OverAllState.updateState），
 *       故策略表留空即可——节点输出键按 {@code wf:<nodeId>.<key>} 命名进 state，天然无冲突；
 *       ② 条件边 API 为 {@code addConditionalEdges(source, AsyncEdgeAction, Map<branchKey,target>)}；
 *   <li>M3：http / code / subagent / approval / loop / template。</li>
 * </ul>
 * <p>
 * 结构决策（继承 SubAgentOrchestrator 的已验证结论）：
 * <ul>
 *   <li><b>图按次编译、动作闭包绑定 RunCtx</b>：每次运行拿 DSL 快照现编译（不缓存，DSL 各异），
 *       节点动作直接闭包 {@link WorkflowRunCtx}，无需 ctxId 经 state 中转的注册表范式；
 *       顺序执行时节点跑在调用线程，M3 引入并行分支后 ThreadLocal 依旧不可用——
 *       节点内身份/配置一律经 {@link #withReplay} 显式重放，禁止新引 ThreadLocal 依赖；</li>
 *   <li><b>end 是真实节点</b>（渲染 config.outputs 进 state，trace 可见、可达路径可追溯），
 *       start 是虚拟概念（出边改从 StateGraph.START 出发）；</li>
 *   <li><b>条件节点先在节点体内裁决路由</b>（trace 记录命中分支），出边动作只读 state 里的
 *       路由键做映射——路由判定与 trace 归属同一节点，不产生重复 trace 条目；</li>
 *   <li>条件表达式求值走 {@link WorkflowExpr}（SpEL + SimpleEvaluationContext，禁 T()/构造器）。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class WorkflowEngine {

    /** 变量引用语法：{{nodeId.key}}（与校验器/表达式求值器同一口径） */
    public static final java.util.regex.Pattern VAR_REF =
            java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)\\.([A-Za-z0-9_]+)\\s*\\}\\}");

    private final WorkflowValidator validator;
    private final ChatClient chatClient;
    private final HybridRetrievalService retrievalService;
    private final ConfigService configService;
    private final ModelRegistryService modelRegistryService;
    private final com.wisesoft.ai.mapper.UserMapper userMapper;

    public WorkflowEngine(WorkflowValidator validator, ChatClient chatClient,
                          HybridRetrievalService retrievalService, ConfigService configService,
                          ModelRegistryService modelRegistryService,
                          com.wisesoft.ai.mapper.UserMapper userMapper) {
        this.validator = validator;
        this.chatClient = chatClient;
        this.retrievalService = retrievalService;
        this.configService = configService;
        this.modelRegistryService = modelRegistryService;
        this.userMapper = userMapper;
    }

    /**
     * 当前可编译执行的节点类型（M1：start/end 虚实各半 + llm/retrieval/condition 三类真实节点）。
     * M3 增 http / code / subagent / approval / loop / template。
     */
    public static final List<String> EXECUTABLE = List.of("start", "end", "llm", "retrieval", "condition");

    /** 节点输出在 state 中的键前缀：wf:&lt;nodeId&gt;.&lt;key&gt;（nodeId 不含 "."，键注入安全） */
    static String nsKey(String nodeId, String key) {
        return "wf:" + nodeId + "." + key;
    }

    /** 编译 dry-run（校验接口用）：结构与类型门通过即图可编译，不执行 */
    public CompiledGraph compile(WorkflowDsl dsl) {
        return buildGraph(dsl, null, 100);
    }

    /** 执行编译：节点动作闭包 ctx（trace 收集/身份重放/模型引用），recursionLimit 用本轮 maxSteps */
    public CompiledGraph compile(WorkflowDsl dsl, WorkflowRunCtx ctx) {
        return buildGraph(dsl, ctx, Math.max(1, ctx.maxSteps));
    }

    private CompiledGraph buildGraph(WorkflowDsl dsl, WorkflowRunCtx ctx, int maxSteps) {
        List<String> errors = validator.validate(dsl);
        if (!errors.isEmpty()) {
            throw new BizException("工作流校验未通过：" + String.join("；", errors));
        }
        for (WorkflowDsl.Node n : dsl.getNodes()) {
            if (!EXECUTABLE.contains(n.getType())) {
                throw new BizException("节点「" + n.getId() + "」的类型 " + n.getType()
                        + " 尚未开放执行（当前支持 " + String.join("/", EXECUTABLE)
                        + "；http/code/subagent/approval/loop/template 随 M3 开放）");
            }
        }
        try {
            // 未注册键默认 REPLACE（OverAllState.updateState 源码口径）——节点输出键 wf:* 动态生长，无需预登记
            KeyStrategyFactory ksf = () -> new HashMap<>();
            StateGraph graph = new StateGraph("workflow", ksf);

            // ---- 真实节点（end 也是真实节点：渲染 outputs 进 state）----
            Map<String, WorkflowDsl.Node> byId = new LinkedHashMap<>();
            for (WorkflowDsl.Node n : dsl.getNodes()) byId.put(n.getId(), n);
            for (WorkflowDsl.Node n : dsl.getNodes()) {
                if ("start".equals(n.getType())) continue;   // 虚拟：出边改从 START 出发
                graph.addNode(n.getId(), wrap(n, ctx, bodyOf(n, ctx)));
            }

            // ---- 边翻译：start 虚拟（出边从 START 出发）；end 是真实节点（先 addNode 过），
            //      指向 end 的边保持原样，end→END 的终止边在下方统一补——
            //      因此 M0 时代的 passthrough 兜底不再需要（start→end 直连即 START→end→END）----
            for (WorkflowDsl.Node n : dsl.getNodes()) {
                if (!"end".equals(n.getType())) continue;
                graph.addEdge(n.getId(), StateGraph.END);
            }
            Set<String> translated = new LinkedHashSet<>();   // 翻译后去重（同一源点多条无条件出边汇同一条时报可读错）
            Map<String, Map<String, String>> condMappings = new LinkedHashMap<>();   // 条件节点 → branch→target
            for (WorkflowDsl.Edge e : dsl.getEdges()) {
                String from = "start".equals(byId.get(e.getFrom()).getType()) ? StateGraph.START : e.getFrom();
                if ("condition".equals(byId.get(e.getFrom()).getType())) {
                    // 条件出边：不 addEdge，攒 mappings 后统一 addConditionalEdges（同一节点两种出边会被库拒）
                    condMappings.computeIfAbsent(e.getFrom(), k -> new LinkedHashMap<>()).put(e.getBranch(), e.getTo());
                    continue;
                }
                if (!translated.add(from + ">" + e.getTo())) {
                    throw new BizException("节点「" + e.getFrom() + "」有多条无条件出边指向「" + e.getTo()
                            + "」：重复连线（多个后继请改用条件分支区分路径）");
                }
                graph.addEdge(from, e.getTo());
            }
            for (Map.Entry<String, Map<String, String>> en : condMappings.entrySet()) {
                graph.addConditionalEdges(en.getKey(),
                        com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(state -> {
                            Object route = state.value(nsKey(en.getKey(), "route")).orElse(null);
                            if (route == null) {
                                throw new BizException("条件节点「" + en.getKey() + "」未产出路由键（节点体未执行或未命中任何分支）");
                            }
                            return String.valueOf(route);
                        }),
                        en.getValue());
            }
            CompiledGraph compiled = graph.compile(CompileConfig.builder().recursionLimit(maxSteps).build());
            log.info("[WORKFLOW] DSL 编译通过：{} 节点 / {} 边（maxSteps={}）", dsl.getNodes().size(), dsl.getEdges().size(), maxSteps);
            return compiled;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 库层拒绝（图结构/参数问题）：原文透出，fail-loud 不吞
            throw new BizException("图编译失败：" + e.getMessage());
        }
    }

    // --------------------------------------------------------------------------------------------------
    // 节点执行体
    // --------------------------------------------------------------------------------------------------

    /** 节点体执行结果：output 进 state（裸键，包装层再加节点前缀）；traceExtra 只进 trace 不进 state */
    private record NodeOut(Map<String, Object> output, Integer promptTokens, Integer completionTokens,
                           Object traceOutput) {
        static NodeOut of(Map<String, Object> output) {
            return new NodeOut(output, null, null, output);
        }
    }

    /**
     * 节点包装层：计时 + trace（输入=配置引用渲染快照，输出=节点体结果摘要）+
     * 输出键加节点前缀进 state（wf:&lt;id&gt;.&lt;key&gt;，避免不同节点同名输出在 state 里互相覆盖）。
     * 失败 fail-loud：异常上抛让整轮 run 落 failed，trace 记 error。
     */
    private AsyncNodeAction wrap(WorkflowDsl.Node n, WorkflowRunCtx ctx, Function<OverAllState, NodeOut> body) {
        return AsyncNodeAction.node_async(state -> {
            long t0 = System.currentTimeMillis();
            Map<String, Object> inputSnapshot = ctx == null ? Map.of() : inputSnapshot(n, ctx, state);
            try {
                NodeOut out = body.apply(state);
                long ms = System.currentTimeMillis() - t0;
                if (ctx != null) {
                    ctx.trace(n.getId(), n.getType(), "success", inputSnapshot, out.traceOutput(), ms,
                            out.promptTokens(), out.completionTokens(), null);
                }
                Map<String, Object> namespaced = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : out.output().entrySet()) {
                    if (e.getValue() != null) namespaced.put(nsKey(n.getId(), e.getKey()), e.getValue());
                }
                return namespaced;
            } catch (Exception e) {
                long ms = System.currentTimeMillis() - t0;
                if (ctx != null) {
                    ctx.trace(n.getId(), n.getType(), "failed", inputSnapshot, null, ms, null, null, e.getMessage());
                }
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            }
        });
    }

    /** 按类型分派节点体（start 永不进入：虚拟节点不 addNode） */
    private Function<OverAllState, NodeOut> bodyOf(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return switch (n.getType()) {
            case "llm" -> llmBody(n, ctx);
            case "retrieval" -> retrievalBody(n, ctx);
            case "condition" -> conditionBody(n, ctx);
            case "end" -> endBody(n, ctx);
            default -> throw new BizException("节点「" + n.getId() + "」类型 " + n.getType() + " 无执行体");
        };
    }

    /** 配置里声明的输入键（cfg.key）取值；未填且必填 → fail-loud（错误信息带节点 id） */
    private static String cfgStr(WorkflowDsl.Node n, String key, boolean required) {
        Object v = n.getConfig() == null ? null : n.getConfig().get(key);
        String s = v == null ? "" : String.valueOf(v).trim();
        if (required && s.isEmpty()) {
            throw new BizException("节点「" + n.getId() + "」缺少必填配置 " + key);
        }
        return s;
    }

    /**
     * LLM 节点：渲染 prompt → ChatClient 非流式调用（模型引用已在 run 前 assertUsable 并解析进 ctx）。
     * 显式 internalToolExecutionEnabled(false)：chatClient 挂了 ToolCall Advisor，裸调用会抛；
     * 工作流节点是确定性的单步执行，不触发工具调用。
     */
    private Function<OverAllState, NodeOut> llmBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            String prompt = render(cfgStr(n, "prompt", true), ref -> resolveRef(ref, ctx, state));
            String model = ctx.resolvedModels.get(n.getId());
            Object t = n.getConfig() == null ? null : n.getConfig().get("temperature");
            double temperature = t instanceof Number num ? num.doubleValue() : ctx.defaultTemperature;
            String answer;
            Integer pTok = null, cTok = null;
            try {
                var resp = chatClient.prompt()
                        .user(prompt)
                        .options(OpenAiChatOptions.builder()
                                .model(model)
                                .temperature(temperature)
                                .internalToolExecutionEnabled(false)
                                .build())
                        .call()
                        .chatResponse();
                answer = resp == null || resp.getResult() == null || resp.getResult().getOutput() == null
                        ? "" : resp.getResult().getOutput().getText();
                Usage usage = resp == null || resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
                if (usage != null) {
                    pTok = usage.getPromptTokens();
                    cTok = usage.getCompletionTokens();
                }
            } catch (Exception e) {
                throw new BizException("节点「" + n.getId() + "」LLM 调用失败（" + model + "）：" + e.getMessage());
            }
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("model", model);
            trace.put("answer", answer == null ? "" : answer);
            return new NodeOut(Map.of("answer", answer == null ? "" : answer), pTok, cTok, trace);
        };
    }

    /** 检索节点 topK 上限（单节点召回预算；主链路上限同量级） */
    private static final int MAX_TOPK = 20;

    /**
     * 知识库检索节点：query 模板渲染 → 混合检索（kbIds 限定库界，null=全局）→ 取前 topK。
     * 输出 chunks（结构化列表，供后续节点/trace 查看）与 text（拼接文本，直接可拼 prompt）。
     * 身份与检索参数经 withReplay 显式重放（节点可能不在触发线程上）。
     */
    private Function<OverAllState, NodeOut> retrievalBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            String query = render(cfgStr(n, "query", true), ref -> resolveRef(ref, ctx, state));
            Collection<String> kbIds = kbIdsOf(n);
            Object t = n.getConfig() == null ? null : n.getConfig().get("topK");
            int topK = t instanceof Number num ? num.intValue() : 5;
            topK = Math.max(1, Math.min(topK, MAX_TOPK));
            List<Hit> hits = withReplay(ctx, () -> retrievalService.search(query, null, kbIds));
            if (hits == null) hits = List.of();
            List<Map<String, Object>> chunks = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            int count = 0;
            for (Hit h : hits) {
                if (count >= topK) break;
                count++;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("title", h.title());
                c.put("titlePath", h.titlePath());
                c.put("content", h.content());
                c.put("score", h.rerankScore() != null ? h.rerankScore() : h.score());
                chunks.add(c);
                if (h.title() != null && !h.title().isBlank()) text.append("【").append(h.title()).append("】");
                if (h.titlePath() != null && !h.titlePath().isBlank()) text.append(h.titlePath()).append("\n");
                if (text.length() > 0 && !text.toString().endsWith("\n")) text.append("\n");
                text.append(h.content() == null ? "" : h.content()).append("\n\n");
            }
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("query", query);
            trace.put("kbIds", kbIds == null ? "全部知识库" : kbIds);
            trace.put("topK", topK);
            trace.put("count", count);
            return new NodeOut(Map.of("chunks", chunks, "text", text.toString().trim(), "count", count),
                    null, null, trace);
        };
    }

    /** config.kbIds：JSON 数组 → Collection；空/null → null（全局检索） */
    private static Collection<String> kbIdsOf(WorkflowDsl.Node n) {
        Object v = n.getConfig() == null ? null : n.getConfig().get("kbIds");
        if (!(v instanceof List<?> list) || list.isEmpty()) return null;
        List<String> ids = new ArrayList<>();
        for (Object o : list) {
            if (o != null && !String.valueOf(o).isBlank()) ids.add(String.valueOf(o).trim());
        }
        return ids.isEmpty() ? null : ids;
    }

    /**
     * 条件分支节点：按 config.branches 声明序求值，首个命中的分支键写进 state（wf:&lt;id&gt;.route），
     * 由出边动作映射目标。key=else 的分支视为兜底（不写 expr 或 expr 恒空）。
     * 全部落空且无 else → fail-loud（图终止，run 落 failed，错误信息讲明该补 else）。
     */
    private Function<OverAllState, NodeOut> conditionBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            List<Map<String, Object>> branches = branchesOf(n);
            Map<String, Object> refs = new LinkedHashMap<>();
            for (Map<String, Object> b : branches) {
                String key = String.valueOf(b.get("key"));
                String expr = b.get("expr") == null ? "" : String.valueOf(b.get("expr")).trim();
                if ("else".equals(key)) return route(n, key, "兜底分支");
                for (String ref : refsOf(b.get("expr"))) refs.put(ref, resolveRef(ref, ctx, state));
                boolean hit;
                try {
                    hit = WorkflowExpr.eval(expr, refs);
                } catch (BizException e) {
                    throw new BizException("条件节点「" + n.getId() + "」分支「" + key + "」：" + e.getMessage());
                }
                if (hit) return route(n, key, expr);
            }
            throw new BizException("条件节点「" + n.getId() + "」所有分支均未命中且未声明 else 兜底分支");
        };
    }

    private static NodeOut route(WorkflowDsl.Node n, String key, String matchedExpr) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("branch", key);
        trace.put("matched", WorkflowRunCtx.abbreviate(matchedExpr, 200));
        return new NodeOut(Map.of("route", key), null, null, trace);
    }

    /** config.branches：[{key, expr}, ...]（结构校验已保证声明与出边对账，这里只做形状兜底） */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> branchesOf(WorkflowDsl.Node n) {
        Object v = n.getConfig() == null ? null : n.getConfig().get("branches");
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /**
     * 结束节点（真实节点）：渲染 config.outputs（值 = 模板或字面量）写进 state——
     * 哪条路径到达哪个 end、产出了什么，trace 与 state 都可回放。
     */
    private Function<OverAllState, NodeOut> endBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            Object outputs = n.getConfig() == null ? null : n.getConfig().get("outputs");
            Map<String, Object> rendered = new LinkedHashMap<>();
            if (outputs instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    Object v = e.getValue();
                    rendered.put(key, v instanceof String s ? render(s, ref -> resolveRef(ref, ctx, state)) : v);
                }
            }
            return NodeOut.of(rendered);
        };
    }

    // --------------------------------------------------------------------------------------------------
    // 运行支持：身份/配置重放、变量解析渲染、输入快照、出参提取、模型预解析
    // --------------------------------------------------------------------------------------------------

    /**
     * 节点体内的身份与配置重放：线程上已有**同一**身份（顺序执行=触发线程）则不动；
     * 不同/缺失（M3 并行分支跑在 reactor 池）则补设并在 finally 清除。
     * 参数覆盖先存线程现状、finally 原样恢复——不吞触发线程已有的覆盖。
     */
    private <T> T withReplay(WorkflowRunCtx ctx, java.util.function.Supplier<T> body) {
        if (ctx == null) return body.get();
        boolean foreignIdentity = !ctx.uid.equals(com.wisesoft.ai.util.RequestUser.uid());
        if (foreignIdentity) {
            com.wisesoft.ai.util.RequestUser.set(ctx.uid, ctx.departmentId, ctx.role);
        }
        Map<String, String> prev = configService.currentOverrides();
        configService.clearOverride();
        configService.putOverrides(ctx.baseOverrides);
        try {
            return body.get();
        } finally {
            configService.clearOverride();
            configService.putOverrides(prev);
            if (foreignIdentity) com.wisesoft.ai.util.RequestUser.clear();
        }
    }

    /** 引用解析（渲染与输入快照共用）：start.* 取入参，其余取 state 里目标节点的输出键 */
    private static Object resolveRef(String ref, WorkflowRunCtx ctx, OverAllState state) {
        int dot = ref.indexOf('.');
        String owner = ref.substring(0, dot);
        String key = ref.substring(dot + 1);
        if (WorkflowValidator.START_SEMANTIC.equals(owner)) {
            return ctx.inputs.get(key);
        }
        try {
            return state.value(nsKey(owner, key)).orElse(null);
        } catch (Exception e) {
            return null;   // state 异常按缺值渲染为空（校验器已在结构期拦过悬空引用）
        }
    }

    /** 模板渲染（节点体共用）：{{nodeId.key}} → 引用值（null → 空串） */
    static String render(String template, Function<String, Object> lookup) {
        if (template == null || template.isEmpty()) return template;
        java.util.regex.Matcher m = VAR_REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = lookup.apply(m.group(1) + "." + m.group(2));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 模板/配置片段里的全部引用键（形如 "nodeId.key"，去重保序）——输入快照与条件求值共用 */
    static List<String> refsOf(Object v) {
        List<String> out = new ArrayList<>();
        collectRefs(v, out);
        return out;
    }

    private static void collectRefs(Object v, List<String> out) {
        if (v instanceof String s) {
            java.util.regex.Matcher m = VAR_REF.matcher(s);
            while (m.find()) {
                String ref = m.group(1) + "." + m.group(2);
                if (!out.contains(ref)) out.add(ref);
            }
        } else if (v instanceof Map<?, ?> map) {
            for (Object o : map.values()) collectRefs(o, out);
        } else if (v instanceof List<?> list) {
            for (Object o : list) collectRefs(o, out);
        }
    }

    /** 节点输入快照：配置声明的每个引用 → 渲染时刻的值（trace 展示"这一步拿到的输入"） */
    private static Map<String, Object> inputSnapshot(WorkflowDsl.Node n, WorkflowRunCtx ctx, OverAllState state) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String ref : refsOf(n.getConfig())) {
            snapshot.put(ref, WorkflowRunCtx.truncateForTrace(resolveRef(ref, ctx, state), 200));
        }
        return snapshot;
    }

    /**
     * 从终态提取结束节点出参：按 DSL 中 end 节点顺序收集 state 里该节点的输出键（wf:&lt;id&gt;.&lt;key&gt;）。
     * 未被执行到的 end（state 无其键）自然不出现——多 end 工作流按实际路径产出。
     */
    public Map<String, Object> extractOutputs(WorkflowDsl dsl, OverAllState finalState) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (finalState == null) return outputs;
        for (WorkflowDsl.Node n : dsl.getNodes()) {
            if (!"end".equals(n.getType())) continue;
            Object outputsCfg = n.getConfig() == null ? null : n.getConfig().get("outputs");
            if (outputsCfg instanceof Map<?, ?> m) {
                for (Object k : m.keySet()) {
                    try {
                        finalState.value(nsKey(n.getId(), String.valueOf(k)))
                                .ifPresent(v -> outputs.put(String.valueOf(k), v));
                    } catch (Exception ignored) {
                        // 单键读取失败不拖垮整体出参
                    }
                }
            }
        }
        return outputs;
    }

    /**
     * run 前的模型预解析（fail-fast：不让模型问题拖到节点执行时才炸）：
     * llm 节点 config.modelRef 为空时回落触发者个人默认聊天模型；均无 → 报错引导配置。
     * 统一过 assertUsable（他人个人级供应商拒用）与类型判定（非 chat 引用拒用）。
     */
    public void resolveModels(WorkflowDsl dsl, WorkflowRunCtx ctx) {
        for (WorkflowDsl.Node n : dsl.getNodes()) {
            if (!"llm".equals(n.getType())) continue;
            Object v = n.getConfig() == null ? null : n.getConfig().get("modelRef");
            String ref = v == null ? "" : String.valueOf(v).trim();
            if (ref.isEmpty()) {
                com.wisesoft.ai.model.User pref = ctx.uid == null || ctx.uid.isBlank()
                        || com.wisesoft.ai.util.RequestUser.ANONYMOUS.equals(ctx.uid)
                        ? null : userMapper.selectById(ctx.uid);
                ref = pref == null || pref.getDefaultModel() == null ? "" : pref.getDefaultModel().trim();
            }
            if (ref.isEmpty()) {
                throw new BizException("节点「" + n.getId() + "」未指定模型，且当前账号未配置个人默认模型"
                        + "（个人设置页 → 模型，或在该节点属性里填 modelRef）");
            }
            modelRegistryService.assertUsable(ref, ctx.uid, ctx.role);
            String type = modelRegistryService.referenceType(ref);
            if (type != null && !"chat".equals(type)) {
                throw new BizException("节点「" + n.getId() + "」引用的不是对话模型（" + ref + "，类型 " + type + "）");
            }
            ctx.resolvedModels.put(n.getId(), ref);
        }
    }

    /** 开始节点入参校验：required 且缺失（null/空串）→ fail-loud，逐项报 */
    public static void checkStartInputs(WorkflowDsl dsl, Map<String, Object> inputs) {
        Map<String, Object> provided = inputs == null ? Map.of() : inputs;
        for (WorkflowDsl.Node n : dsl.getNodes()) {
            if (!"start".equals(n.getType())) continue;
            Object cfg = n.getConfig() == null ? null : n.getConfig().get("inputs");
            if (!(cfg instanceof List<?> list)) continue;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object key = m.get("key");
                if (key == null) continue;
                boolean required = Boolean.TRUE.equals(m.get("required"));
                Object val = provided.get(String.valueOf(key));
                if (required && (val == null || (val instanceof String s && s.isBlank()))) {
                    throw new BizException("开始节点缺少必填入参「" + key + "」");
                }
            }
        }
    }
}
