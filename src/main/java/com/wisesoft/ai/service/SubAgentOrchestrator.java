package com.wisesoft.ai.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wisesoft.ai.model.Agent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
     * @param branches   各分支最终状态（供编排视图渲染/持久化）：{id, name, status, hits, elapsedMs}
     */
    public record Outcome(List<HybridRetrievalService.Hit> hits, String digestText, int agents, long elapsedMs,
                          List<Map<String, Object>> branches) {
        public Outcome(List<HybridRetrievalService.Hit> hits, String digestText, int agents, long elapsedMs) {
            this(hits, digestText, agents, elapsedMs, List.of());
        }
    }

    /**
     * 分支进度事件（编排视图实时展示用）：status ∈ running | done | failed。
     * name 为分支显示名（委派=子智能体名，多视角=视角描述）；hits 为该分支本次命中块数。
     */
    public record BranchEvent(int idx, String name, String status, int hits, long elapsedMs, boolean delegated) {
    }

    /** 单轮执行上下文：图节点在编译期绑定，运行期参数经 ThreadLocal 传入（与 KnowledgeRetrievalTool 同模式） */
    private static final class RunCtx {
        final String question;
        final List<String> subQueries;
        /** 主智能体委派的子智能体（null/空 = 走原有的多视角策略） */
        final List<Agent> subAgents;
        final List<HybridRetrievalService.Hit> collected = new ArrayList<>();
        final Set<String> seenKids = new LinkedHashSet<>();
        final List<String> digests = new ArrayList<>();
        /** 分支进度回调（可为 null：不需要实时推送时传 null，如评估/调试场景） */
        final Consumer<BranchEvent> onBranch;
        /** 分支最终状态（保序，供 Outcome.branches 与编排视图持久化） */
        final List<Map<String, Object>> branches = new ArrayList<>();
        final long t0;
        final boolean delegated;

        RunCtx(String question, List<String> subQueries, List<Agent> subAgents, Consumer<BranchEvent> onBranch) {
            this.question = question;
            this.subQueries = subQueries;
            this.subAgents = subAgents;
            this.onBranch = onBranch;
            this.t0 = System.currentTimeMillis();
            this.delegated = subAgents != null && !subAgents.isEmpty();
        }

        /** 分支名：委派=子智能体名，多视角=该视角的查询描述 */
        String branchName(int idx) {
            if (delegated && idx < subAgents.size() && subAgents.get(idx) != null) {
                return subAgents.get(idx).getName() == null || subAgents.get(idx).getName().isBlank()
                        ? "子智能体 " + (idx + 1) : subAgents.get(idx).getName();
            }
            return "视角 " + (idx + 1) + " · " + truncate(idx < subQueries.size() ? subQueries.get(idx) : question, 16);
        }

        static String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() > max ? s.substring(0, max) + "…" : s;
        }

        /** 发一个分支进度事件（幂等：回调为空或已失败时不发） */
        void emit(BranchEvent e) {
            if (onBranch != null) {
                try {
                    onBranch.accept(e);
                } catch (Exception ignored) {
                    // 回调失败不影响编排主流程（进度展示是增强项）
                }
            }
        }
    }

    /**
     * 本轮执行上下文经 state 传递的 key。
     * <p><b>不能用 ThreadLocal</b>：并行节点由框架用 {@code Schedulers.parallel()} 调度
     * （见 NodeExecutor：{@code subscribeOn(scheduler)}），跑在 reactor 线程池的工作线程上，
     * 主线程设的 ThreadLocal 在那里 get() 返回 null → 分支静默不执行（表现为"编排完成但命中 0 块"）。
     * state 是每次 invoke 传入的、随流程流转，天然跨线程可见，故用它承载上下文。
     */
    private static final String CTX_KEY = "__runCtx";

    /** 从图节点的 state 中取本轮上下文（取不到返回 null，调用方按"跳过"处理） */
    private static RunCtx ctxOf(OverAllState state) {
        if (state == null) return null;
        try {
            return state.value(CTX_KEY).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 并行编排入口（未挂子智能体）：走原有的「多视角并行检索」 */
    public Outcome run(String question) {
        return run(question, null, null);
    }

    /** 并行编排入口（未挂子智能体，带进度回调）：走原有的「多视角并行检索」 */
    public Outcome run(String question, Consumer<BranchEvent> onBranch) {
        return run(question, null, onBranch);
    }

    /**
     * 并行编排入口；任何异常都不抛出（编排是增强项，失败应降级为单路检索，不能影响问答）
     *
     * @param subAgents 主智能体委派的子智能体。为 null 或空时走原有的多视角策略；
     *                  非空时按子智能体数并行——每个子智能体用自己的知识库范围检索、按自己的角色提示词提炼
     * @param onBranch  分支进度回调（编排视图实时展示；可为 null）
     */
    public Outcome run(String question, List<Agent> subAgents, Consumer<BranchEvent> onBranch) {
        boolean delegated = subAgents != null && !subAgents.isEmpty();
        int agents = delegated
                ? Math.min(subAgents.size(), 4)
                : Math.max(2, Math.min(4, configService.getInt("agent.subAgents", 2)));
        long t0 = System.currentTimeMillis();
        RunCtx ctx = new RunCtx(question, planSubQueries(question, agents), subAgents, onBranch);
        try {
            CompiledGraph graph = graphCache.computeIfAbsent(agents, this::buildGraph);
            // 上下文随 state 传入（并行节点跑在 reactor 线程池，不能用 ThreadLocal）
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("question", question);
            inputs.put(CTX_KEY, ctx);
            Optional<OverAllState> result = graph.invoke(inputs);
            result.ifPresent(s -> s.value("digestText").ifPresent(v -> {
                // 仅当分支未产出要点时才收 merge 节点的文本；空串不收（否则"要点 1 条"实为空内容的假象）
                String text = String.valueOf(v);
                if (ctx.digests.isEmpty() && text != null && !text.isBlank()) ctx.digests.add(text);
            }));
            long ms = System.currentTimeMillis() - t0;
            log.info("[SUBAGENT] 并行编排完成（{}）：{} 个分支，命中 {} 块（去重后），要点 {} 条，耗时 {}ms",
                    delegated ? "子智能体委派" : "多视角", agents, ctx.collected.size(), ctx.digests.size(), ms);
            return new Outcome(List.copyOf(ctx.collected), String.join("\n", ctx.digests), agents, ms,
                    List.copyOf(ctx.branches));
        } catch (Exception e) {
            log.warn("[SUBAGENT] 并行编排失败（降级为单路检索）: {}", e.getMessage());
            return new Outcome(List.of(), "", 0, System.currentTimeMillis() - t0, List.copyOf(ctx.branches));
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
                // 本轮执行上下文经 state 传递（见 CTX_KEY 注释：并行节点跑在 reactor 线程池，ThreadLocal 不可用）
                m.put(CTX_KEY, KeyStrategy.REPLACE);
                return m;
            };
            StateGraph graph = new StateGraph("subagent-rag-" + n, ksf);
            // 汇总节点：把各子代理要点拼成一段文本（写在 state 里供调用方取）
            graph.addNode("merge", AsyncNodeAction.node_async(state -> {
                RunCtx c = ctxOf(state);
                String text = c == null || c.digests.isEmpty() ? "" : String.join("\n", c.digests);
                return Map.of("digestText", text);
            }));
            // 子代理节点：检索 + （可选）要点提炼
            for (int i = 0; i < n; i++) {
                final int idx = i;
                graph.addNode("agent_" + i, AsyncNodeAction.node_async(state -> {
                    RunCtx c = ctxOf(state);
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

    /** 单个分支执行：检索 → 按各自知识库范围过滤 → 跨分支去重 →（可选）按角色提炼要点 */
    private void runAgent(int idx, RunCtx ctx) {
        try {
            Agent sub = (ctx.subAgents != null && idx < ctx.subAgents.size()) ? ctx.subAgents.get(idx) : null;
            // 委派模式下各分支都用原问题：差异体现在「各自的知识库范围」与「各自的提炼视角」上——
            // 这才是"派给某个角色去查"，而不是"同一个问题换个问法"。未委派时才走多视角子查询。
            String subQuery = (sub == null && idx < ctx.subQueries.size()) ? ctx.subQueries.get(idx) : ctx.question;
            String name = ctx.branchName(idx);
            ctx.emit(new BranchEvent(idx, name, "running", 0, System.currentTimeMillis() - ctx.t0, ctx.delegated));
            int topK = Math.max(1, configService.getInt("agent.topKPerAgent", 3));
            List<HybridRetrievalService.Hit> hits = retrievalService.search(subQuery);
            if (sub != null) hits = inScope(hits, sub.scopeDocIds());
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
                String digest = digest(subQuery, fresh, sub == null ? null : sub.getSystemPrompt());
                if (digest != null && !digest.isBlank()) {
                    synchronized (ctx) {
                        ctx.digests.add("· " + (sub == null ? "" : "【" + sub.getName() + "】") + digest.strip());
                    }
                }
            }
            long doneMs = System.currentTimeMillis() - ctx.t0;
            ctx.emit(new BranchEvent(idx, name, "done", fresh.size(), doneMs, ctx.delegated));
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("id", idx);
            branch.put("name", name);
            branch.put("status", "done");
            branch.put("hits", fresh.size());
            branch.put("elapsedMs", doneMs);
            branch.put("delegated", ctx.delegated);
            synchronized (ctx) {
                ctx.branches.add(branch);
            }
        } catch (Exception e) {
            log.warn("[SUBAGENT] 分支 {} 执行失败（跳过）: {}", idx, e.getMessage());
            long failMs = System.currentTimeMillis() - ctx.t0;
            ctx.emit(new BranchEvent(idx, ctx.branchName(idx), "failed", 0, failMs, ctx.delegated));
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("id", idx);
            branch.put("name", ctx.branchName(idx));
            branch.put("status", "failed");
            branch.put("hits", 0);
            branch.put("elapsedMs", failMs);
            branch.put("delegated", ctx.delegated);
            synchronized (ctx) {
                ctx.branches.add(branch);
            }
        }
    }

    /** 按知识库范围过滤命中（scope 为 null 表示不限制） */
    private static List<HybridRetrievalService.Hit> inScope(List<HybridRetrievalService.Hit> hits, Set<String> scope) {
        if (scope == null || hits == null) return hits;
        return hits.stream().filter(h -> h.docId() != null && scope.contains(h.docId())).toList();
    }

    /**
     * 用 LLM 把命中片段提炼成要点（非流式、短输出）：让汇总进上下文的资料更精炼，
     * 而不是把每个子代理的原始片段都塞进主链路。失败返回 null（不影响主流程）。
     */
    private String digest(String subQuery, List<HybridRetrievalService.Hit> hits, String rolePrompt) {
        try {
            StringBuilder sb = new StringBuilder();
            for (HybridRetrievalService.Hit h : hits) {
                sb.append("【").append(h.title() == null ? "" : h.title()).append("】");
                String content = h.content() == null ? "" : h.content().replaceAll("\\s+", " ");
                sb.append(content, 0, Math.min(content.length(), 400)).append("\n");
            }
            // 委派模式带上子智能体的角色设定，让提炼视角贴合它的职责
            String role = (rolePrompt == null || rolePrompt.isBlank()) ? "" : "你的分析视角：" + rolePrompt.trim() + "\n\n";
            String prompt = role + "下面是知识库中与「" + subQuery + "」相关的资料片段：\n\n" + sb
                    + "\n请用 2~3 条要点提炼其中与问题最相关的事实（只输出要点，每条一行，不要解释、不要补充资料外内容）。";
            String out = chatClient.prompt().user(prompt).call().content();
            return out == null ? null : out.replaceAll("[\\r\\n]+", " ").trim();
        } catch (Exception e) {
            log.debug("[SUBAGENT] 要点提炼失败: {}", e.getMessage());
            return null;
        }
    }
}
