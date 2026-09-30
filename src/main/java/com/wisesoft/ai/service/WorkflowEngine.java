package com.wisesoft.ai.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.model.Agent;
import com.wisesoft.ai.model.ToolApproval;
import com.wisesoft.ai.service.HybridRetrievalService.Hit;
import com.wisesoft.ai.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.ai.util.SsrfGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 *   <li>M0：结构校验 + 编译骨架；</li>
 *   <li>M1：llm / retrieval / condition 真实节点 + 运行 + trace（未注册键默认 REPLACE、
 *       addConditionalEdges 形态两项已源码取证 + 实测关闭）；</li>
 *   <li>M2：VueFlow 画布（前端，后端零改动）；</li>
 *   <li>M3（当前）：节点补全——http / code / subagent / approval / loop / template。
 *       approval 采用<b>挂起-快照-短路重放</b>方案：节点落 PENDING 审批记录后抛
 *       {@link WorkflowSuspendException}，run 落 waiting_approval + 状态快照；
 *       审批接口按快照恢复（已完成节点短路回放，仅挂起节点及其下游真正执行），
 *       不依赖框架 checkpoint。loop 节点打破 DAG 限制：校验器只放行<b>源自 loop 节点</b>的回跳边，
 *       运行期 loopCount 计数 + maxSteps 双闸。</li>
 * </ul>
 * <p>
 * 结构决策（继承 SubAgentOrchestrator 的已验证结论）：图按次编译、动作闭包
 * {@link WorkflowRunCtx}；节点内身份/配置一律经 {@link #withReplay} 显式重放；
 * 输出键按 {@code wf:<nodeId>.<key>} 命名进 state；条件类节点（condition/approval/loop）
 * 在节点体内裁决路由、出边动作只读映射。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class WorkflowEngine {

    /**
     * 单节点 LLM 调用的默认结束等待上限（秒）：只防"网关连上却永不结束"的悬挂，
     * 不是节点超时策略的主体——节点 config.timeoutSeconds 可按节点覆盖（1~3600）。
     */
    private static final int LLM_DEFAULT_TIMEOUT_SECONDS = 600;

    /** LLM 节点的超时等待池：非流式调用丢进来带时限等待（daemon cached，并发 = 并发 LLM 节点数） */
    private static final java.util.concurrent.ExecutorService LLM_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "wf-llm");
                t.setDaemon(true);
                return t;
            });

    /** 节点级 LLM 超时（config.timeoutSeconds，1~3600；未声明用默认 600） */
    private static int llmTimeoutSeconds(WorkflowDsl.Node n) {
        return cfgInt(n, "timeoutSeconds", LLM_DEFAULT_TIMEOUT_SECONDS, 1, 3600);
    }

    /** 变量引用语法：{{nodeId.key}}（与校验器/表达式求值器同一口径） */
    public static final java.util.regex.Pattern VAR_REF =
            java.util.regex.Pattern.compile("\\{\\{\\s*([A-Za-z0-9_-]+)\\.([A-Za-z0-9_]+)\\s*\\}\\}");

    private final WorkflowValidator validator;
    private final ChatClient chatClient;
    private final HybridRetrievalService retrievalService;
    private final ConfigService configService;
    private final ModelRegistryService modelRegistryService;
    private final com.wisesoft.ai.mapper.UserMapper userMapper;
    private final SandboxService sandboxService;
    private final AgentService agentService;
    private final RagService ragService;
    private final SessionService sessionService;
    private final com.wisesoft.ai.mapper.ToolApprovalMapper toolApprovalMapper;

    public WorkflowEngine(WorkflowValidator validator, ChatClient chatClient,
                          HybridRetrievalService retrievalService, ConfigService configService,
                          ModelRegistryService modelRegistryService,
                          com.wisesoft.ai.mapper.UserMapper userMapper,
                          SandboxService sandboxService, AgentService agentService,
                          RagService ragService, SessionService sessionService,
                          com.wisesoft.ai.mapper.ToolApprovalMapper toolApprovalMapper) {
        this.validator = validator;
        this.chatClient = chatClient;
        this.retrievalService = retrievalService;
        this.configService = configService;
        this.modelRegistryService = modelRegistryService;
        this.userMapper = userMapper;
        this.sandboxService = sandboxService;
        this.agentService = agentService;
        this.ragService = ragService;
        this.sessionService = sessionService;
        this.toolApprovalMapper = toolApprovalMapper;
    }

    /**
     * 当前可编译执行的节点类型（M3 起 11 类全量开放；start/end 虚实各半见 buildGraph）。
     */
    public static final List<String> EXECUTABLE = List.of(
            "start", "end", "llm", "retrieval", "condition",
            "http", "code", "subagent", "approval", "loop", "template");

    /** 条件类（路由器）节点：出边携带分支键、节点体内裁决路由（condition/approval/loop 同范式） */
    public static final Set<String> ROUTER_TYPES = Set.of("condition", "approval", "loop");

    /** 节点输出在 state 中的键前缀：wf:&lt;nodeId&gt;.&lt;key&gt;（nodeId 不含 "."，键注入安全） */
    static String nsKey(String nodeId, String key) {
        return "wf:" + nodeId + "." + key;
    }

    /** 编译 dry-run（校验接口用）：结构与类型门通过即图可编译，不执行 */
    public CompiledGraph compile(WorkflowDsl dsl) {
        return buildGraph(dsl, null, 100);
    }

    /** 执行编译：节点动作闭包 ctx（trace 收集/身份重放/模型引用/恢复快照），recursionLimit 用本轮 maxSteps */
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
                throw new BizException("节点「" + n.getId() + "」的类型 " + n.getType() + " 未注册");
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

            // ---- 边翻译：start 虚拟（出边从 START 出发）；end 是真实节点，end→END 终止边统一补 ----
            for (WorkflowDsl.Node n : dsl.getNodes()) {
                if (!"end".equals(n.getType())) continue;
                graph.addEdge(n.getId(), StateGraph.END);
            }
            Set<String> translated = new LinkedHashSet<>();
            Map<String, Map<String, String>> condMappings = new LinkedHashMap<>();   // 路由节点 → branch→target
            for (WorkflowDsl.Edge e : dsl.getEdges()) {
                String from = "start".equals(byId.get(e.getFrom()).getType()) ? StateGraph.START : e.getFrom();
                if (ROUTER_TYPES.contains(byId.get(e.getFrom()).getType())) {
                    // 路由出边：不 addEdge，攒 mappings 后统一 addConditionalEdges（同一节点两种出边会被库拒）
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
                                throw new BizException("路由节点「" + en.getKey() + "」未产出路由键（节点体未执行或未命中任何分支）");
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
            throw new BizException("图编译失败：" + e.getMessage());
        }
    }

    // --------------------------------------------------------------------------------------------------
    // 节点执行体
    // --------------------------------------------------------------------------------------------------

    /** 节点体执行结果：output 进 state（裸键，包装层再加节点前缀）；traceOutput 只进 trace 不进 state */
    private record NodeOut(Map<String, Object> output, Integer promptTokens, Integer completionTokens,
                           Object traceOutput) {
        static NodeOut of(Map<String, Object> output) {
            return new NodeOut(output, null, null, output);
        }
    }

    /**
     * 节点包装层：恢复短路 + 计时 + trace + 输出键加节点前缀进 state。
     * <ul>
     *   <li><b>恢复短路</b>：resumeOutputs 含该节点（上次运行的快照）→ 直接回放输出，不执行节点体——
     *       人工审核恢复时已完成节点零成本续跑；</li>
     *   <li><b>挂起穿透</b>：{@link WorkflowSuspendException} 不按失败处理（记 waiting trace 后原样上抛，
     *       由 run 收口转 waiting_approval）；</li>
     *   <li>其余异常 fail-loud：上抛让整轮 run 落 failed。</li>
     * </ul>
     */
    private AsyncNodeAction wrap(WorkflowDsl.Node n, WorkflowRunCtx ctx, Function<OverAllState, NodeOut> body) {
        return AsyncNodeAction.node_async(state -> {
            if (ctx != null && ctx.resumeOutputs.containsKey(n.getId())) {
                return namespace(n.getId(), ctx.resumeOutputs.get(n.getId()));
            }
            long t0 = System.currentTimeMillis();
            Map<String, Object> inputSnapshot = ctx == null ? Map.of() : inputSnapshot(n, ctx, state);
            try {
                NodeOut out = body.apply(state);
                long ms = System.currentTimeMillis() - t0;
                if (ctx != null) {
                    ctx.trace(n.getId(), n.getType(), "success", inputSnapshot, out.traceOutput(), ms,
                            out.promptTokens(), out.completionTokens(), null);
                    ctx.fullOutputs.put(n.getId(), out.output());
                }
                return namespace(n.getId(), out.output());
            } catch (WorkflowSuspendException e) {
                if (ctx != null) {
                    ctx.trace(n.getId(), n.getType(), "waiting", inputSnapshot, e.traceOutput,
                            System.currentTimeMillis() - t0, null, null, null);
                }
                throw e;
            } catch (Exception e) {
                long ms = System.currentTimeMillis() - t0;
                if (ctx != null) {
                    ctx.trace(n.getId(), n.getType(), "failed", inputSnapshot, null, ms, null, null, e.getMessage());
                }
                throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
            }
        });
    }

    private static Map<String, Object> namespace(String nodeId, Map<String, Object> out) {
        Map<String, Object> namespaced = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : out.entrySet()) {
            if (e.getValue() != null) namespaced.put(nsKey(nodeId, e.getKey()), e.getValue());
        }
        return namespaced;
    }

    /** 按类型分派节点体（start 永不进入：虚拟节点不 addNode） */
    private Function<OverAllState, NodeOut> bodyOf(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return switch (n.getType()) {
            case "llm" -> llmBody(n, ctx);
            case "retrieval" -> retrievalBody(n, ctx);
            case "condition" -> routerBody(n, ctx, false);
            case "http" -> httpBody(n, ctx);
            case "code" -> codeBody(n, ctx);
            case "subagent" -> subagentBody(n, ctx);
            case "approval" -> approvalBody(n, ctx);
            case "loop" -> routerBody(n, ctx, true);
            case "template" -> templateBody(n, ctx);
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

    /** 整数配置（缺省默认值 + 范围钳制） */
    private static int cfgInt(WorkflowDsl.Node n, String key, int dft, int min, int max) {
        Object v = n.getConfig() == null ? null : n.getConfig().get(key);
        int val = v instanceof Number num ? num.intValue() : dft;
        return Math.max(min, Math.min(max, val));
    }

    /**
     * LLM 节点：渲染 prompt → ChatClient 调用（模型引用已在 run 前 assertUsable 并解析进 ctx）。
     * 显式 internalToolExecutionEnabled(false)：chatClient 挂了 ToolCall Advisor，裸调用会抛；
     * 工作流节点是确定性的单步执行，不触发工具调用。
     * <p>
     * M4 对话型接入：ctx.tokenSink 非空时改走<b>流式</b>调用，每个文本块即时交给 sink（由调用方推给前端），
     * 节点输出仍是完整文本——流式只影响"边跑边看"，不改变节点语义与下游引用。
     */
    private Function<OverAllState, NodeOut> llmBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            String prompt = render(cfgStr(n, "prompt", true), ref -> resolveRef(ref, ctx, state));
            boolean stream = ctx != null && ctx.tokenSink != null;
            if (stream) {
                try {
                    return llmStreaming(n, ctx, prompt);
                } catch (BizException e) {
                    throw e;
                } catch (Exception e) {
                    throw new BizException("节点「" + n.getId() + "」LLM 流式调用失败（"
                            + ctx.resolvedModels.get(n.getId()) + "）：" + e.getMessage());
                }
            }
            String model = ctx.resolvedModels.get(n.getId());
            Object t = n.getConfig() == null ? null : n.getConfig().get("temperature");
            double temperature = t instanceof Number num ? num.doubleValue() : ctx.defaultTemperature;
            int timeoutSeconds = llmTimeoutSeconds(n);
            String answer;
            Integer pTok = null, cTok = null;
            try {
                // 非流式调用也带硬超时（丢进等待池带时限取结果）：网关挂起不拖死整个 run
                java.util.concurrent.Future<org.springframework.ai.chat.model.ChatResponse> future =
                        LLM_POOL.submit(() -> chatClient.prompt()
                                .user(prompt)
                                .options(OpenAiChatOptions.builder()
                                        .model(model)
                                        .temperature(temperature)
                                        .internalToolExecutionEnabled(false)
                                        .build())
                                .call()
                                .chatResponse());
                org.springframework.ai.chat.model.ChatResponse resp;
                try {
                    resp = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    future.cancel(true);
                    throw new BizException("节点「" + n.getId() + "」LLM 调用超时（" + timeoutSeconds
                            + " 秒；可在节点属性里调 timeoutSeconds）");
                } catch (java.util.concurrent.ExecutionException ee) {
                    Throwable c = ee.getCause() != null ? ee.getCause() : ee;
                    throw c instanceof Exception ex ? ex : ee;
                }
                answer = resp == null || resp.getResult() == null || resp.getResult().getOutput() == null
                        ? "" : resp.getResult().getOutput().getText();
                Usage usage = resp == null || resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
                if (usage != null) {
                    pTok = usage.getPromptTokens();
                    cTok = usage.getCompletionTokens();
                }
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException("节点「" + n.getId() + "」LLM 调用失败（" + model + "）：" + e.getMessage());
            }
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("model", model);
            trace.put("answer", answer == null ? "" : answer);
            return new NodeOut(Map.of("answer", answer == null ? "" : answer), pTok, cTok, trace);
        };
    }

    /**
     * LLM 节点的流式分支（对话型接入用）：订阅 chatResponse 流，文本块即时进 sink、
     * 同时累积成完整回答；末块 metadata 有 usage 则取真实 token 数。
     * <p>
     * 用 latch 等流结束而不用 Flux 的阻塞迭代：节点体跑在图调度的线程上，阻塞式迭代
     * 在响应式调度器里会被拒（IllegalStateException），subscribe + await 是确定性做法。
     */
    private NodeOut llmStreaming(WorkflowDsl.Node n, WorkflowRunCtx ctx, String prompt) {
        String model = ctx.resolvedModels.get(n.getId());
        Object t = n.getConfig() == null ? null : n.getConfig().get("temperature");
        double temperature = t instanceof Number num ? num.doubleValue() : ctx.defaultTemperature;
        StringBuilder full = new StringBuilder();
        Integer[] tokens = new Integer[2];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        chatClient.prompt()
                .user(prompt)
                .options(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .internalToolExecutionEnabled(false)
                        .build())
                .stream()
                .chatResponse()
                .subscribe(resp -> {
                    Usage usage = resp == null || resp.getMetadata() == null ? null : resp.getMetadata().getUsage();
                    if (usage != null) {
                        if (usage.getPromptTokens() != null && usage.getPromptTokens() > 0) tokens[0] = usage.getPromptTokens();
                        if (usage.getCompletionTokens() != null && usage.getCompletionTokens() > 0) tokens[1] = usage.getCompletionTokens();
                    }
                    Object out = resp == null || resp.getResult() == null ? null : resp.getResult().getOutput();
                    String delta = (out instanceof org.springframework.ai.chat.messages.AssistantMessage am
                            ? (am.getText() == null ? "" : am.getText()) : "");
                    if (!delta.isEmpty()) {
                        full.append(delta);
                        try {
                            ctx.tokenSink.accept(delta);
                        } catch (Exception ignore) {
                            // sink 抛错（SSE 通道已断）不打断节点执行：图仍需收口，最终回答照常落库
                        }
                    }
                }, e -> {
                    failure.set(e);
                    latch.countDown();
                }, latch::countDown);
        try {
            if (!latch.await(llmTimeoutSeconds(n), java.util.concurrent.TimeUnit.SECONDS)) {
                throw new BizException("节点「" + n.getId() + "」LLM 流式调用超时（"
                        + llmTimeoutSeconds(n) + " 秒无结束信号；可在节点属性里调 timeoutSeconds）");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BizException("节点「" + n.getId() + "」LLM 流式调用被中断");
        }
        if (failure.get() != null) {
            Throwable e = failure.get();
            throw new BizException("节点「" + n.getId() + "」LLM 调用失败（" + model + "）：" + e.getMessage());
        }
        String answer = full.toString();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("model", model);
        trace.put("answer", answer);
        trace.put("streamed", true);
        return new NodeOut(Map.of("answer", answer), tokens[0], tokens[1], trace);
    }

    /** 检索节点 topK 上限（单节点召回预算；主链路上限同量级） */
    private static final int MAX_TOPK = 20;

    /**
     * 知识库检索节点：query 模板渲染 → 混合检索（kbIds 限定库界，null=全局）→ 低分门过滤 → 取前 topK。
     * 输出 chunks（结构化列表）/ text（拼接文本，直接可拼 prompt）/ count。
     * <p>
     * M5 低分门：config.minScore 声明则用它，未声明跟随全局 {@code retrieval.minContextScore}
     * （与主问答管线同键同语义：排序分 = 重排分??融合分，低于阈值的块不进结果也不占 topK 名额；
     * 0 = 不过滤）。此前工作流检索全量吐块、该门只在主链路生效——就是排班 M1 记的那笔账。
     */
    private Function<OverAllState, NodeOut> retrievalBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            String query = render(cfgStr(n, "query", true), ref -> resolveRef(ref, ctx, state));
            Collection<String> kbIds = kbIdsOf(n);
            int topK = cfgInt(n, "topK", 5, 1, MAX_TOPK);
            List<Hit> hits = withReplay(ctx, () -> retrievalService.search(query, null, kbIds));
            if (hits == null) hits = List.of();
            double minScore = resolveMinScore(n, ctx);
            List<Hit> kept = filterByMinScore(hits, minScore);
            int skipped = hits.size() - kept.size();
            List<Map<String, Object>> chunks = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            int count = 0;
            for (Hit h : kept) {
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
            if (minScore > 0) trace.put("minScore", minScore);
            if (skipped > 0) trace.put("lowScoreSkipped", skipped);
            return new NodeOut(Map.of("chunks", chunks, "text", text.toString().trim(), "count", count),
                    null, null, trace);
        };
    }

    /** 节点低分阈值：config.minScore 声明优先，未声明跟随全局 retrieval.minContextScore（节点线程内经 withReplay 重放） */
    private double resolveMinScore(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        Object v = n.getConfig() == null ? null : n.getConfig().get("minScore");
        if (v instanceof Number num) return Math.max(0, num.doubleValue());
        Double g = withReplay(ctx, () -> configService.getDouble("retrieval.minContextScore", 0.6));
        return g == null ? 0 : Math.max(0, g);
    }

    /** 低分门：排序分（重排分??融合分，与主链路同口径）低于阈值的块剔除；阈值 ≤0 原样返回（公开纯函数，供用例直接验证） */
    public static List<Hit> filterByMinScore(List<Hit> hits, double minScore) {
        if (hits == null || hits.isEmpty() || minScore <= 0) return hits;
        List<Hit> out = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            double rankScore = h.rerankScore() != null ? h.rerankScore() : h.score();
            if (rankScore >= minScore) out.add(h);
        }
        return out;
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

    // ---- M3：条件路由（condition 原语义 + loop 计数闸共用一个节点体）----

    /**
     * 路由节点体：按 config.branches 声明序求值，首个命中的分支键写进 state（wf:&lt;id&gt;.route），
     * 由出边动作映射目标。key=else 的分支视为兜底。全落空且无 else → fail-loud。
     * <p>loop 节点在同一体上叠加<b>迭代计数</b>：每次执行 loopCount+1（持久在 state，跨迭代累加），
     * 超过 config.maxLoops 即 fail-loud（编译期校验 maxLoops 声明 + 运行期计数 = 双闸；
     * 另有 recursionLimit 硬兜底）。
     */
    private Function<OverAllState, NodeOut> routerBody(WorkflowDsl.Node n, WorkflowRunCtx ctx, boolean isLoop) {
        return state -> {
            Integer loopCount = null;
            Map<String, Object> output = new LinkedHashMap<>();
            if (isLoop) {
                int maxLoops = cfgInt(n, "maxLoops", 5, 1, 100);
                int count = 1;
                try {
                    Object prev = state.value(nsKey(n.getId(), "loopCount")).orElse(null);
                    if (prev instanceof Number num) count = num.intValue() + 1;
                } catch (Exception ignored) {
                }
                if (count > maxLoops) {
                    throw new BizException("循环节点「" + n.getId() + "」已达最大迭代次数 " + maxLoops
                            + "（当前第 " + count + " 轮）——请检查回跳条件是否永远为真");
                }
                loopCount = count;
                output.put("loopCount", count);
            }
            List<Map<String, Object>> branches = branchesOf(n);
            Map<String, Object> refs = new LinkedHashMap<>();
            for (Map<String, Object> b : branches) {
                String key = String.valueOf(b.get("key"));
                String expr = b.get("expr") == null ? "" : String.valueOf(b.get("expr")).trim();
                if ("else".equals(key)) {
                    output.put("route", key);
                    return routeOut(n, output, key, "兜底分支");
                }
                for (String ref : refsOf(b.get("expr"))) refs.put(ref, resolveRef(ref, ctx, state));
                boolean hit;
                try {
                    hit = WorkflowExpr.eval(expr, refs);
                } catch (BizException e) {
                    throw new BizException((isLoop ? "循环节点" : "条件节点") + "「" + n.getId() + "」分支「" + key + "」：" + e.getMessage());
                }
                if (hit) {
                    output.put("route", key);
                    return routeOut(n, output, key, expr);
                }
            }
            throw new BizException((isLoop ? "循环节点" : "条件节点") + "「" + n.getId() + "」所有分支均未命中且未声明 else 兜底分支");
        };
    }

    private static NodeOut routeOut(WorkflowDsl.Node n, Map<String, Object> output, String key, String matchedExpr) {
        Map<String, Object> trace = new LinkedHashMap<>(output);   // loop 时带 loopCount
        trace.put("branch", key);
        trace.put("matched", WorkflowRunCtx.abbreviate(matchedExpr, 200));
        return new NodeOut(output, null, null, trace);
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

    // ---- M3：http ----

    private static final int MAX_REDIRECT_HOPS = 5;
    private static final int MAX_HTTP_BODY_BYTES = 2 * 1024 * 1024;   // 响应体读取上限（超限拒绝，防拖垮实例）
    private static final int HTTP_BODY_STATE_CHARS = 20_000;         // 进 state 的正文截断（trace 同）

    /**
     * HTTP 请求节点：方法/URL/头/体 + 超时；SSRF 复用 SsrfGuard 逐跳内网校验（fail-loud）。
     * 重定向手动跟进且每跳重新过校验（自动跟随会绕过单次校验，同 DocumentService 口径）。
     * 输出 status / body（进 state 截断 2 万字符）/ contentType。
     */
    private Function<OverAllState, NodeOut> httpBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            String method = cfgStr(n, "method", false).toUpperCase();
            if (method.isEmpty()) method = "GET";
            if (!List.of("GET", "POST", "PUT", "DELETE").contains(method)) {
                throw new BizException("节点「" + n.getId() + "」不支持的 HTTP 方法：" + method);
            }
            String url = render(cfgStr(n, "url", true), ref -> resolveRef(ref, ctx, state));
            URI uri;
            try {
                uri = URI.create(url.trim());
            } catch (Exception e) {
                throw new BizException("节点「" + n.getId() + "」URL 不合法：" + url);
            }
            int timeoutMs = cfgInt(n, "timeoutMs", 15_000, 1_000, 60_000);
            Map<String, String> headers = new LinkedHashMap<>();
            Object hCfg = n.getConfig() == null ? null : n.getConfig().get("headers");
            if (hCfg instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() != null) {
                        headers.put(String.valueOf(e.getKey()),
                                render(String.valueOf(e.getValue() == null ? "" : e.getValue()), ref -> resolveRef(ref, ctx, state)));
                    }
                }
            } else if (hCfg instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m && m.get("key") != null) {
                        headers.put(String.valueOf(m.get("key")),
                                render(String.valueOf(m.get("value") == null ? "" : m.get("value")), ref -> resolveRef(ref, ctx, state)));
                    }
                }
            }
            String reqBody = "GET".equals(method) ? null : render(cfgStr(n, "body", false), ref -> resolveRef(ref, ctx, state));

            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)   // 明文 http 的 h2c 升级坑，钉死 HTTP/1.1
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .followRedirects(HttpClient.Redirect.NEVER)   // 手动逐跳：每跳都过 SSRF 校验
                    .build();
            int status = -1;
            String contentType = "";
            String bodyText = "";
            URI current = uri;
            for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
                SsrfGuard.requirePublicHost(current);
                HttpRequest.Builder rb = HttpRequest.newBuilder(current).timeout(Duration.ofMillis(timeoutMs));
                headers.forEach(rb::header);
                HttpRequest req;
                if ("GET".equals(method) || reqBody == null) {
                    req = rb.method(method, HttpRequest.BodyPublishers.noBody()).build();
                } else {
                    req = rb.method(method, HttpRequest.BodyPublishers.ofString(reqBody)).build();
                }
                HttpResponse<byte[]> resp;
                try {
                    resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BizException("节点「" + n.getId() + "」HTTP 请求被中断");
                } catch (java.io.IOException e) {
                    throw new BizException("节点「" + n.getId() + "」HTTP 请求失败：" + e.getMessage());
                }
                status = resp.statusCode();
                if (status >= 300 && status < 400) {
                    String location = resp.headers().firstValue("Location").orElse(null);
                    if (location == null || location.isBlank()) {
                        throw new BizException("节点「" + n.getId() + "」重定向缺少 Location（HTTP " + status + "）");
                    }
                    current = current.resolve(location.trim());
                    continue;
                }
                contentType = resp.headers().firstValue("Content-Type").orElse("");
                byte[] raw = resp.body();
                if (raw != null && raw.length > MAX_HTTP_BODY_BYTES) {
                    throw new BizException("节点「" + n.getId() + "」响应体超过 2MB 上限，已拒绝");
                }
                bodyText = raw == null ? "" : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
                break;
            }
            if (bodyText.isEmpty() && status >= 300 && status < 400) {
                throw new BizException("节点「" + n.getId() + "」重定向超过 " + MAX_REDIRECT_HOPS + " 次，已停止跟进");
            }
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("url", url);
            trace.put("status", status);
            trace.put("contentType", contentType);
            trace.put("bodyChars", bodyText.length());
            String stateBody = bodyText.length() > HTTP_BODY_STATE_CHARS
                    ? bodyText.substring(0, HTTP_BODY_STATE_CHARS) + "…（已截断，全长 " + bodyText.length() + " 字符）"
                    : bodyText;
            return new NodeOut(Map.of("status", status, "body", stateBody, "contentType", contentType),
                    null, null, trace);
        };
    }

    // ---- M3：code ----

    /**
     * 代码执行节点：Python/Node，走 Docker 沙盒（scope=workflow+uid，跨运行复用同一容器）。
     * 代码<b>原样执行</b>不做变量渲染（{{}} 在代码里语义不可控；需要动态值用 template 节点上游拼接）。
     * 超时与输出截断沿用沙盒 provisioner 口径（commandTimeoutSeconds/maxOutputBytes）。
     */
    private Function<OverAllState, NodeOut> codeBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            if (!configService.getBoolean("tool.sandbox.enabled")) {
                throw new BizException("节点「" + n.getId() + "」代码执行依赖沙盒，请先在设置页开启「沙盒工具」（tool.sandbox.enabled）");
            }
            String lang = cfgStr(n, "language", true).toLowerCase();
            if (!List.of("python", "node").contains(lang)) {
                throw new BizException("节点「" + n.getId() + "」仅支持 python / node（当前 " + lang + "）");
            }
            String code = cfgStr(n, "code", true);
            int timeoutSeconds = cfgInt(n, "timeoutSeconds", 60, 1, 300);
            String file = "/tmp/wf_" + n.getId() + "_" + System.currentTimeMillis() + ("python".equals(lang) ? ".py" : ".js");
            String cmd = ("python".equals(lang) ? "python3 " : "node ") + file;
            ProvisionerSandboxBackend.ExecuteResponse resp = withReplay(ctx, () -> {
                ProvisionerSandboxBackend backend = sandboxService.backend("workflow", ctx.uid);
                backend.write(file, code);
                return backend.execute(cmd, timeoutSeconds);
            });
            String output = resp.output() == null ? "" : resp.output();
            if (resp.exitCode() == null || resp.exitCode() != 0) {
                throw new BizException("节点「" + n.getId() + "」代码执行失败（exit=" + resp.exitCode() + "）："
                        + WorkflowRunCtx.abbreviate(output, 500));
            }
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("language", lang);
            trace.put("exitCode", resp.exitCode());
            trace.put("truncated", resp.truncated());
            trace.put("output", output);
            return new NodeOut(Map.of("output", output, "exitCode", resp.exitCode()), null, null, trace);
        };
    }

    // ---- M3：subagent ----

    /**
     * 子智能体节点：委派指定智能体作答——复用 RagService.chat 全管线（智能体的知识库范围/
     * 工具/技能原样生效），身份按触发者装载（可见性按调用者），回答经 CollectingSseEmitter 收集。
     * 会话为<b>临时会话</b>：跑完即删（软删可审计），回答留在 trace 与 state，不污染会话列表。
     */
    private Function<OverAllState, NodeOut> subagentBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            String agentId = cfgStr(n, "agentId", true);
            String prompt = render(cfgStr(n, "prompt", true), ref -> resolveRef(ref, ctx, state));
            String modelRef = cfgStr(n, "modelRef", false);   // 空 → 走触发者个人默认（管线内解析）
            Agent agent = withReplay(ctx, () -> agentService.get(agentId));
            if (agent == null) {
                throw new BizException("节点「" + n.getId() + "」智能体不存在或对当前用户不可见");
            }
            int timeoutMs = withReplay(ctx, () -> Math.max(30_000, configService.getInt("workflow.subagentTimeoutMs", 180_000)));
            String sessionId = sessionService.createSession(ctx.uid);
            CollectingSseEmitter sink = new CollectingSseEmitter();
            try {
                withReplay(ctx, () -> {
                    ragService.chat(sessionId, prompt, List.of(), List.of(), List.of(), false,
                            agentId, modelRef, ctx.uid, sink, false);
                    return null;
                });
                if (!sink.awaitDone(timeoutMs)) {
                    throw new BizException("节点「" + n.getId() + "」子智能体回答超时（" + timeoutMs / 1000 + " 秒）");
                }
                if (sink.lastError() != null && !sink.lastError().isBlank()) {
                    throw new BizException("节点「" + n.getId() + "」子智能体执行失败：" + sink.lastError());
                }
                String answer = sink.answer() == null ? "" : sink.answer();
                if (answer.isBlank()) {
                    throw new BizException("节点「" + n.getId() + "」子智能体未产生回答");
                }
                Map<String, Object> trace = new LinkedHashMap<>();
                trace.put("agentId", agentId);
                trace.put("agentName", agent.getName());
                trace.put("answer", answer);
                return new NodeOut(Map.of("answer", answer), null, null, trace);
            } finally {
                // 收集型通道不触发 onCompletion，必须主动清登记；临时会话随手删（软删，审计留痕）
                RagService.forgetSseChannel(sink);
                try {
                    sessionService.deleteSession(ctx.uid, sessionId);
                } catch (Exception ignored) {
                }
            }
        };
    }

    // ---- M3：approval ----

    /**
     * 人工审核节点（挂起-快照-短路重放）：
     * <ul>
     *   <li>无裁决 → 渲染审批提示、落 PENDING 审批记录（复用 c_ai_tool_approval：
     *       sessionId 存 runId、toolName 存 workflow:节点id），抛 {@link WorkflowSuspendException}
     *       让整轮 run 挂起为 waiting_approval + 状态快照；</li>
     *   <li>有裁决（恢复执行，ctx.approvalDecisions）→ 路由 approve / reject 分支；
     *       分支声明与连线由校验器强制（approve/reject 必须都有）。</li>
     * </ul>
     */
    private Function<OverAllState, NodeOut> approvalBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            Boolean decision = ctx.approvalDecisions.get(n.getId());
            if (decision != null) {
                String route = decision ? "approve" : "reject";
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("route", route);
                Map<String, Object> trace = new LinkedHashMap<>();
                trace.put("branch", route);
                trace.put("decision", decision ? "approved" : "rejected");
                return new NodeOut(output, null, null, trace);
            }
            String prompt = render(cfgStr(n, "prompt", true), ref -> resolveRef(ref, ctx, state));
            int timeoutSeconds = cfgInt(n, "timeoutSeconds", 120, 30, 86_400);
            ToolApproval rec = new ToolApproval();
            rec.setId(java.util.UUID.randomUUID().toString());
            rec.setSessionId(ctx.runId);                 // 审批记录的 sessionId 语义 = 工作流运行 id
            rec.setUserId(ctx.uid);                      // 仅运行发起人可裁决
            rec.setToolName("workflow:" + n.getId());
            rec.setStatus("PENDING");
            rec.setRequestArgs(com.alibaba.fastjson2.JSON.toJSONString(
                    Map.of("prompt", prompt, "timeoutSeconds", timeoutSeconds)));
            rec.setCreatedAt(java.time.LocalDateTime.now());
            toolApprovalMapper.insert(rec);
            Map<String, Object> traceOut = new LinkedHashMap<>();
            traceOut.put("prompt", prompt);
            traceOut.put("approvalId", rec.getId());
            traceOut.put("timeoutSeconds", timeoutSeconds);
            throw new WorkflowSuspendException(rec.getId(), n.getId(), traceOut);
        };
    }

    // ---- M3：template ----

    /** 模板/变量聚合节点：多路输出拼 prompt——纯文本渲染，输出 {text}（多视角并行的汇总场景） */
    private Function<OverAllState, NodeOut> templateBody(WorkflowDsl.Node n, WorkflowRunCtx ctx) {
        return state -> {
            String text = render(cfgStr(n, "template", true), ref -> resolveRef(ref, ctx, state));
            return NodeOut.of(Map.of("text", text));
        };
    }

    /** 结束节点：渲染 config.outputs（值 = 模板或字面量）写进 state——到达路径与产出均可回放 */
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
     * 不同/缺失（并行分支跑在 reactor 池）则补设并在 finally 清除。
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
