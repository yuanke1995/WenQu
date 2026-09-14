package com.wisesoft.ai.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SubAgent 并行编排（P3：4.3）——基于 Spring AI Alibaba 的 StateGraph。
 *
 * <p>落点：**多视角并行检索**。把一个问题拆成 N 个视角的子查询（全句语义视角 / 关键词精确视角 /
 * 长问题再拆子句），每个视角交给一个子代理（SubAgent）并行执行「检索 + 提炼要点」，
 * 再由汇总节点合并——解决单路检索在复杂问题上"召回不全"的问题。
 *
 * <p>为什么用图而不是自己开线程：并行分支的调度、fan-in 汇聚、状态合并由框架负责；
 * 后续要加"条件分支（资料不足才补充检索）""人工审批"这类控制流时，图是现成的扩展点。
 *
 * <p>成本控制：子代理的要点提炼要调 LLM（非流式、短输出），因此默认关闭（agent.enabled），
 * 且子代理数、每代理取块数、整体超时都可配。关闭时对现有问答链路零影响。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SubAgentOrchestrator {

    private final HybridRetrievalService retrievalService;
    private final ConfigService configService;
    private final ChatClient chatClient;
    private final KeywordExtractor keywordExtractor;

    /** 按子代理数缓存编译后的图（图结构随 N 变化；N 通常 2~4，缓存避免每轮重建） */
    private final Map<Integer, CompiledGraph> graphCache = new ConcurrentHashMap<>();

    public SubAgentOrchestrator(HybridRetrievalService retrievalService, ConfigService configService,
                               ChatClient chatClient, KeywordExtractor keywordExtractor) {
        this.retrievalService = retrievalService;
        this.configService = configService;
        this.chatClient = chatClient;
        this.keywordExtractor = keywordExtractor;
    }

    /**
     * 编排结果。
     *
     * @param hits       全部子代理命中（已跨代理去重，保序）
     * @param digestText 各子代理的要点汇总（可为空串：未开启提炼或全部失败）
     * @param agents     实际执行的子代理数
     * @param elapsedMs  总耗时（用于日志/评估对比单路检索）
     */
    public record Outcome(List<HybridRetrievalService.Hit> hits, String digestText, int agents, long elapsedMs) {
    }

    /** 单轮执行上下文：图节点在编译期绑定，运行期参数经 ThreadLocal 传入（与 KnowledgeRetrievalTool 同模式） */
    private static final class RunCtx {
        final String question;
        final List<String> subQueries;
        final List<HybridRetrievalService.Hit> collected = new ArrayList<>();
        final Set<String> seenKids = new LinkedHashSet<>();
        final List<String> digests = new ArrayList<>();

        RunCtx(String question, List<String> subQueries) {
            this.question = question;
            this.subQueries = subQueries;
        }
    }

    private static final ThreadLocal<RunCtx> CTX = new ThreadLocal<>();

    /** 并行编排入口；任何异常都不抛出（编排是增强项，失败应降级为单路检索，不能影响问答） */
    public Outcome run(String question) {
        int agents = Math.max(2, Math.min(4, configService.getInt("agent.subAgents", 2)));
        long t0 = System.currentTimeMillis();
        RunCtx ctx = new RunCtx(question, planSubQueries(question, agents));
        CTX.set(ctx);
        try {
            CompiledGraph graph = graphCache.computeIfAbsent(agents, this::buildGraph);
            Optional<OverAllState> result = graph.invoke(Map.of("question", question));
            result.ifPresent(s -> s.value("digestText").ifPresent(v -> {
                if (ctx.digests.isEmpty()) ctx.digests.add(String.valueOf(v));
            }));
            long ms = System.currentTimeMillis() - t0;
            log.info("[SUBAGENT] 并行编排完成：{} 个子代理，命中 {} 块（去重后），要点 {} 条，耗时 {}ms",
                    agents, ctx.collected.size(), ctx.digests.size(), ms);
            return new Outcome(List.copyOf(ctx.collected), String.join("\n", ctx.digests), agents, ms);
        } catch (Exception e) {
            log.warn("[SUBAGENT] 并行编排失败（降级为单路检索）: {}", e.getMessage());
            return new Outcome(List.of(), "", 0, System.currentTimeMillis() - t0);
        } finally {
            CTX.remove();
        }
    }

    /**
     * 拆解子查询（启发式，不额外调 LLM——拆解本身若很贵就失去意义）：
     * ① 全句（语义视角）② 关键词串（精确匹配视角）③ 长问题按标点拆子句（细节视角）
     */
    List<String> planSubQueries(String question, int n) {
        List<String> out = new ArrayList<>();
        out.add(question);
        if (n <= 1) return out;
        List<String> terms = keywordExtractor.extract(question);
        if (terms != null && !terms.isEmpty()) {
            out.add(String.join(" ", terms.subList(0, Math.min(terms.size(), 12))));
        }
        if (n >= 3 && question.length() > 24) {
            for (String seg : question.split("[，。；、,.;!?！？]")) {
                String t = seg.trim();
                if (t.length() >= 6 && !out.contains(t)) out.add(t);
                if (out.size() >= n) break;
            }
        }
        while (out.size() < n) {
            out.add(question);   // 视角不足时用原句补齐（去重逻辑会保证不重复入库）
        }
        return out.subList(0, n);
    }

    /** 构建 N 个子代理并行 + 汇总的图 */
    private CompiledGraph buildGraph(int n) {
        try {
            KeyStrategyFactory ksf = () -> {
                Map<String, KeyStrategy> m = new HashMap<>();
                m.put("question", KeyStrategy.REPLACE);
                m.put("digestText", KeyStrategy.REPLACE);
                return m;
            };
            StateGraph graph = new StateGraph("subagent-rag-" + n, ksf);
            // 汇总节点：把各子代理要点拼成一段文本（写在 state 里供调用方取）
            graph.addNode("merge", AsyncNodeAction.node_async(state -> {
                RunCtx c = CTX.get();
                String text = c == null || c.digests.isEmpty() ? "" : String.join("\n", c.digests);
                return Map.of("digestText", text);
            }));
            // 子代理节点：检索 + （可选）要点提炼
            for (int i = 0; i < n; i++) {
                final int idx = i;
                graph.addNode("agent_" + i, AsyncNodeAction.node_async(state -> {
                    RunCtx c = CTX.get();
                    if (c != null) runAgent(idx, c);
                    return Map.of();   // 数据写进 RunCtx（图只负责调度与汇聚）
                }));
                graph.addEdge("agent_" + i, "merge");
            }
            // 扇出：所有子代理从 START 出发（并行）；扇入：全部完成才进 merge
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) ids.add("agent_" + i);
            graph.addEdge(StateGraph.START, ids.get(0));
            for (int i = 1; i < n; i++) graph.addEdge(StateGraph.START, ids.get(i));
            graph.addEdge("merge", StateGraph.END);
            CompiledGraph compiled = graph.compile();
            log.info("[SUBAGENT] 已编译 {}-子代理并行图", n);
            return compiled;
        } catch (Exception e) {
            throw new IllegalStateException("构建子代理图失败: " + e.getMessage(), e);
        }
    }

    /** 单个子代理：检索该视角 → 跨代理去重收集 → （可选）LLM 提炼要点 */
    private void runAgent(int idx, RunCtx ctx) {
        String subQuery = idx < ctx.subQueries.size() ? ctx.subQueries.get(idx) : ctx.question;
        try {
            int topK = Math.max(1, configService.getInt("agent.topKPerAgent", 3));
            List<HybridRetrievalService.Hit> hits = retrievalService.search(subQuery);
            List<HybridRetrievalService.Hit> fresh = new ArrayList<>();
            synchronized (ctx) {
                int added = 0;
                for (HybridRetrievalService.Hit h : hits) {
                    if (h.knowledgeId() == null || !ctx.seenKids.add(h.knowledgeId())) continue;
                    ctx.collected.add(h);
                    fresh.add(h);
                    if (++added >= topK) break;
                }
            }
            if (configService.getBoolean("agent.digestEnabled") && !fresh.isEmpty()) {
                String digest = digest(subQuery, fresh);
                if (digest != null && !digest.isBlank()) {
                    synchronized (ctx) {
                        ctx.digests.add("· " + digest.strip());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[SUBAGENT] 子代理 {} 执行失败（跳过该视角）: {}", idx, e.getMessage());
        }
    }

    /**
     * 用 LLM 把命中片段提炼成要点（非流式、短输出）：让汇总进上下文的资料更精炼，
     * 而不是把每个子代理的原始片段都塞进主链路。失败返回 null（不影响主流程）。
     */
    private String digest(String subQuery, List<HybridRetrievalService.Hit> hits) {
        try {
            StringBuilder sb = new StringBuilder();
            for (HybridRetrievalService.Hit h : hits) {
                sb.append("【").append(h.title() == null ? "" : h.title()).append("】");
                String content = h.content() == null ? "" : h.content().replaceAll("\\s+", " ");
                sb.append(content, 0, Math.min(content.length(), 400)).append("\n");
            }
            String prompt = "下面是知识库中与「" + subQuery + "」相关的资料片段：\n\n" + sb
                    + "\n请用 2~3 条要点提炼其中与问题最相关的事实（只输出要点，每条一行，不要解释、不要补充资料外内容）。";
            String out = chatClient.prompt().user(prompt).call().content();
            return out == null ? null : out.replaceAll("[\\r\\n]+", " ").trim();
        } catch (Exception e) {
            log.debug("[SUBAGENT] 要点提炼失败: {}", e.getMessage());
            return null;
        }
    }
}
