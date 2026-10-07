package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigDefaults;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.wenqu.ai.model.Agent;
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

    /** 委派路由专用线程池（带超时，避免路由模型卡住拖垮整轮问答；daemon 不阻碍 JVM 退出）。
     *  固定 2 线程：超时路径必须 cancel(true) 中断底层 LLM 调用（WebClient block 可被 interrupt 唤醒），
     *  否则思考型模型的慢路由把池占满后，所有用户的委派路由都会超时回退全选 */
    private static final java.util.concurrent.ThreadPoolExecutor ROUTE_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "subagent-router");
                t.setDaemon(true);
                return t;
            });

    public SubAgentOrchestrator(HybridRetrievalService retrievalService, ConfigService configService,
                               ChatClient chatClient, KeywordExtractor keywordExtractor) {
        this.retrievalService = retrievalService;
        this.configService = configService;
        this.chatClient = chatClient;
        this.keywordExtractor = keywordExtractor;
    }

    /**
     * 按需委派路由：让主模型先从候选子智能体里挑出与问题相关的，只跑选中的。
     * <p>背景：主智能体挂了 N 个子智能体时，若每轮全部并行，会出现"派了完全无关的角色"
     * （如问表单操作却去查《刑法》），既浪费检索与提炼开销，也让编排卡片充满 0 命中的噪音。
     * <p>返回 {@link RouteResult}：选中名单 + 各自的「挑选理由」——理由随 subagent_route 事件
     * 下发前端，让"为什么派它"可见（编排可解释性）。路由失败/超时/解析失败
     * → 回退全部候选（编排是增强项，宁可多跑也不能缺失）。判定"都不需要"时 picked 为空。
     */
    public record RouteResult(List<Agent> picked, Map<String, String> reasons) {
        public RouteResult(List<Agent> picked) {
            this(picked, Map.of());
        }
    }

    public RouteResult route(String question, List<Agent> candidates, String resolvedModel) {
        if (candidates == null || candidates.isEmpty()) return new RouteResult(List.of());
        if (candidates.size() == 1 || !configService.getBoolean("agent.autoRoute")) return new RouteResult(candidates);
        try {
            StringBuilder sb = new StringBuilder();
            for (Agent a : candidates) {
                sb.append("- ").append(a.getId()).append(" | ").append(a.getName()).append("：")
                        .append(a.getDescription() == null ? "（无描述）" : a.getDescription()).append("\n");
            }
            String prompt = "你是任务分派员。可咨询的助手清单如下（每行：id | 名称：职责）：\n" + sb
                    + "\n用户问题：" + question
                    + "\n\n请判断回答该问题需要咨询上述哪些助手，只选职责确实相关的（宁缺毋滥）。"
                    + "\n只输出一个 JSON 数组，元素为对象 {\"id\":\"助手id\",\"reason\":\"挑选理由（15字内，说明它职责与问题的关联）\"}；"
                    + "若都不相关则输出 []。不要输出任何解释文字。";
            int timeoutMs = Math.max(1000, configService.getInt("agent.routeTimeoutMs", ConfigDefaults.AGENT_ROUTE_TIMEOUT_MS));
            // 用量归属：路由调用跑在专属线程池（无请求上下文），把调用线程上的用户身份显式带进去，
            // 这笔开销才落在提问者的台账上（模型路由出口按 UsageAttr 记账）
            String billingUid = com.wenqu.ai.util.RequestUser.uid();
            java.util.concurrent.CompletableFuture<String> routeFuture = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(
                                com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                                null, null, "subagent"));
                        try {
                            return chatClient.prompt()
                                    .user(prompt)
                                    .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                                            .model(resolvedModel)
                                            .temperature(0.0)
                                            .internalToolExecutionEnabled(false)
                                            .build())
                                    .call()
                                    .content();
                        } finally {
                            com.wenqu.ai.util.UsageAttr.clear();
                        }
                    }, ROUTE_EXECUTOR);
            String out;
            try {
                out = routeFuture.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                routeFuture.cancel(true);
                log.warn("[SUBAGENT] 委派路由超时（{}ms），已中断底层调用并回退全部候选（{} 个，池活跃 {}/2）",
                        timeoutMs, candidates.size(), ROUTE_EXECUTOR.getActiveCount(), ROUTE_EXECUTOR.getMaximumPoolSize());
                return new RouteResult(candidates);
            }
            return parseRouteResult(out, candidates);
        } catch (Exception e) {
            // CompletableFuture.get 的 ExecutionException 自身 message 常为 null，真因在 cause——透出真因，不吞
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[SUBAGENT] 委派路由失败，回退为全部候选（{} 个）: {}", candidates.size(), cause.toString());
            return new RouteResult(candidates);
        }
    }

    /**
     * 解析路由结果：新格式为 JSON 数组 [{id, reason}]（带挑选理由），
     * 兼容旧格式（纯 id/名称字符串数组）——reason 缺省为空，前端不展示理由行。
     * 解析不出任何有效项时回退全部候选（宁可多跑不可漏）。
     */
    RouteResult parseRouteResult(String out, List<Agent> candidates) {
        if (out == null || out.isBlank()) return new RouteResult(candidates);
        try {
            // 容忍 markdown 代码块包裹与前后缀文本：取第一个 [ 到最后一个 ]
            String s = out.trim();
            int lb = s.indexOf('[');
            int rb = s.lastIndexOf(']');
            if (lb < 0 || rb <= lb) return new RouteResult(candidates);
            String arr = s.substring(lb, rb + 1);
            List<Object> items = com.alibaba.fastjson2.JSON.parseArray(arr, Object.class);
            if (items == null) return new RouteResult(candidates);
            if (items.isEmpty()) return new RouteResult(List.of());   // 模型明确判定"都不需要"
            Map<String, Agent> byKey = new LinkedHashMap<>();
            for (Agent a : candidates) {
                if (a.getId() != null) byKey.putIfAbsent(a.getId(), a);
                if (a.getName() != null) byKey.putIfAbsent(a.getName(), a);
            }
            List<Agent> picked = new ArrayList<>();
            Map<String, String> reasons = new LinkedHashMap<>();
            for (Object o : items) {
                String id = null;
                String reason = null;
                if (o instanceof com.alibaba.fastjson2.JSONObject obj) {
                    id = obj.getString("id");
                    reason = obj.getString("reason");
                } else if (o != null) {
                    id = String.valueOf(o);   // 旧格式：纯字符串（id 或名称）
                }
                if (id == null || id.isBlank()) continue;
                Agent a = byKey.get(id.trim());
                if (a != null && !picked.contains(a)) {
                    picked.add(a);
                    if (reason != null && !reason.isBlank()) reasons.put(a.getName(), reason.strip());
                }
            }
            // 模型返回了非空数组但一个都对不上（可能编了名字）→ 回退全部，避免"以为派了实际没派"
            if (!picked.isEmpty()) return new RouteResult(picked, reasons);
            return new RouteResult(candidates);
        } catch (Exception e) {
            log.warn("[SUBAGENT] 路由结果解析失败，回退为全部候选: {}", e.getMessage());
            return new RouteResult(candidates);
        }
    }

    /**
     * 编排结果。
     *
     * @param hits       全部子代理命中（已跨代理去重，保序）
     * @param digestText 各子代理的要点汇总（可为空串：未开启提炼或全部失败）
     * @param agents     实际执行的子代理数
     * @param elapsedMs  总耗时（用于日志/评估对比单路检索）
     * @param branches   各分支最终状态（供编排视图渲染/持久化）：
     *                    {id, name, status, hits, elapsedMs, delegated, digest, description}
     */
    public record Outcome(List<HybridRetrievalService.Hit> hits, String digestText, int agents, long elapsedMs,
                          List<Map<String, Object>> branches) {
        public Outcome(List<HybridRetrievalService.Hit> hits, String digestText, int agents, long elapsedMs) {
            this(hits, digestText, agents, elapsedMs, List.of());
        }
    }

    /**
     * 分支进度事件（编排视图实时展示用）：status ∈ running | done | failed。
     * name 为分支显示名（委派=子智能体名，多视角=视角描述）；hits 为该分支本次命中块数；
     * description 为"派它去干嘛"的任务描述；digest 为要点结果（仅 done 时有值）。
     */
    public record BranchEvent(int idx, String name, String status, int hits, long elapsedMs, boolean delegated,
                              String description, String digest) {
        public BranchEvent(int idx, String name, String status, int hits, long elapsedMs, boolean delegated) {
            this(idx, name, status, hits, elapsedMs, delegated, "", "");
        }
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
        /** 主链路已解析的本轮生效模型（会话覆盖 > 个人默认），供要点提炼等辅助调用复用 */
        final String resolvedModel;
        /**
         * 创建上下文时父线程（问答流水线）的检索参数覆盖快照（全局 &lt; 知识库 &lt; 智能体的合并结果）。
         * <p>并行分支由框架用 {@code Schedulers.parallel()} 调度，ThreadLocal 不跨线程继承 ⇒ 必须在
         * 分支线程内重放这份快照，否则分支检索（topK / 权重 / 阈值）静默退化为全局配置。
         */
        final Map<String, String> baseOverrides;
        /** 创建上下文时父线程的个人配置覆盖快照（个人设置 → 对话偏好）：与 baseOverrides 同理，
         *  分支线程内需重放（分支的要点提炼读 chat.temperature 等配置）。 */
        final Map<String, String> baseUserOverrides;
        /** 创建上下文时父线程（问答流水线）捕获的用量归属 uid（null=无用户上下文）：分支线程的
         *  ThreadLocal 不可见，要点提炼/supervisor 聚合等辅助调用的记账归属从这里取。 */
        final String billingUid;
        /** 创建上下文时父线程捕获的<b>检索身份三元组</b>（uid/部门/角色）：分支跑在 reactor 线程池，
         *  {@code RequestUser} ThreadLocal 不可见 ⇒ 库门编译与文档可见性晚绑定都会拿到 anonymous，
         *  导致「共享给我的资料在子代理分支里检索不到」（与 billingUid 同一个 ThreadLocal 盲区，但影响检索正确性）。
         *  部门/角色一并带：可见性判定要按部门/角色判，缺一项就会把本该可见的文档判为不可见。 */
        final String identityUid;
        final String identityDept;
        final String identityRole;

        RunCtx(String question, List<String> subQueries, List<Agent> subAgents, Consumer<BranchEvent> onBranch,
               String resolvedModel, Map<String, String> baseOverrides, Map<String, String> baseUserOverrides,
               String billingUid) {
            this(question, subQueries, subAgents, onBranch, resolvedModel, baseOverrides, baseUserOverrides,
                    billingUid, null, null, null);
        }

        RunCtx(String question, List<String> subQueries, List<Agent> subAgents, Consumer<BranchEvent> onBranch,
               String resolvedModel, Map<String, String> baseOverrides, Map<String, String> baseUserOverrides,
               String billingUid, String identityUid, String identityDept, String identityRole) {
            this.question = question;
            this.subQueries = subQueries;
            this.subAgents = subAgents;
            this.onBranch = onBranch;
            this.t0 = System.currentTimeMillis();
            this.delegated = subAgents != null && !subAgents.isEmpty();
            this.resolvedModel = resolvedModel;
            this.baseOverrides = baseOverrides == null ? Map.of() : baseOverrides;
            this.baseUserOverrides = baseUserOverrides == null ? Map.of() : baseUserOverrides;
            this.billingUid = billingUid;
            this.identityUid = identityUid;
            this.identityDept = identityDept;
            this.identityRole = identityRole;
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
     * 本轮执行上下文在 state 中的「钥匙」key（值是 ctxId 字符串，不是对象本身）。
     * <p>两点约束，缺一不可（都是实测踩出来的）：
     * <ol>
     *   <li><b>不能用 ThreadLocal</b>：并行节点由框架用 {@code Schedulers.parallel()} 调度
     *       （见 NodeExecutor：{@code subscribeOn(scheduler)}），跑在 reactor 线程池的工作线程上，
     *       主线程设的 ThreadLocal 在那里 get() 返回 null → 分支静默不执行（"编排完成但命中 0 块"）。</li>
     *   <li><b>不能把 RunCtx 直接放进 state</b>：框架会对 state 做 Jackson 序列化，而 RunCtx 含
     *       onBranch 回调（lambda 捕获了 RagService → chatClient → 整条 Spring bean 链），
     *       序列化时抛 InvalidDefinitionException（jdk.proxy2 无法构造 BeanSerializer）→ 整轮编排失败降级。
     *       故 state 只放可安全序列化的 ctxId 字符串，真正的上下文存在 {@link #CTX_REGISTRY}。</li>
     * </ol>
     */
    private static final String CTX_KEY = "__runCtxId";

    /** ctxId → 本轮上下文（仅存活于单次 run 期间，finally 中移除） */
    private final Map<String, RunCtx> CTX_REGISTRY = new ConcurrentHashMap<>();

    /** 从图节点的 state 中取本轮上下文（取不到返回 null，调用方按"跳过"处理） */
    private RunCtx ctxOf(OverAllState state) {
        if (state == null) return null;
        try {
            Object v = state.value(CTX_KEY).orElse(null);
            String id = v == null ? null : String.valueOf(v);
            return id == null ? null : CTX_REGISTRY.get(id);
        } catch (Exception e) {
            return null;
        }
    }

    /** 并行编排入口（未挂子智能体）：走原有的「多视角并行检索」 */
    public Outcome run(String question) {
        return run(question, null, null, "");
    }

    /** 并行编排入口（未挂子智能体，带进度回调）：走原有的「多视角并行检索」 */
    public Outcome run(String question, Consumer<BranchEvent> onBranch) {
        return run(question, null, onBranch, "");
    }

    /**
     * 并行编排入口；任何异常都不抛出（编排是增强项，失败应降级为单路检索，不能影响问答）
     *
     * @param subAgents 主智能体委派的子智能体。为 null 或空时走原有的多视角策略；
     *                  非空时按子智能体数并行——每个子智能体用自己的知识库范围检索、按自己的角色提示词提炼
     * @param onBranch  分支进度回调（编排视图实时展示；可为 null）
     */
    public Outcome run(String question, List<Agent> subAgents, Consumer<BranchEvent> onBranch, String resolvedModel) {
        boolean delegated = subAgents != null && !subAgents.isEmpty();
        int agents = delegated
                ? Math.min(subAgents.size(), 4)
                : Math.max(2, Math.min(4, configService.getInt("agent.subAgents", 2)));
        long t0 = System.currentTimeMillis();
        // 归属 uid 只在入口线程（问答流水线）可见，RunCtx 带进分支线程供辅助调用记账
        String entryUid = com.wenqu.ai.util.RequestUser.uid();
        // 检索身份三元组：同样只在入口线程可见，但分支检索（库门下推 + 文档可见性晚绑定）要按真实用户判，
        // 不带则分支拿到 anonymous → 配了共享范围（department/user）的文档被判不可见，
        // 表现为「主链路能引用的资料，子代理分支里检索不到」。anonymous 时整体置 null（保持既有 fail-closed 行为）。
        String entryDept = com.wenqu.ai.util.RequestUser.departmentId();
        String entryRole = com.wenqu.ai.util.RequestUser.role();
        boolean anonymous = com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(entryUid);
        RunCtx ctx = new RunCtx(question, planSubQueries(question, agents), subAgents, onBranch, resolvedModel,
                configService.currentOverrides(), configService.currentUserOverrides(),
                anonymous ? null : entryUid,
                anonymous ? null : entryUid, entryDept, entryRole);
        // 上下文注册到注册表，state 里只带可安全序列化的 id（框架会序列化 state，见 CTX_KEY 注释）
        String ctxId = java.util.UUID.randomUUID().toString();
        CTX_REGISTRY.put(ctxId, ctx);
        try {
            CompiledGraph graph = graphCache.computeIfAbsent(agents, this::buildGraph);
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("question", question);
            inputs.put(CTX_KEY, ctxId);
            Optional<OverAllState> result = graph.invoke(inputs);
            result.ifPresent(s -> s.value("digestText").ifPresent(v -> {
                // 仅当分支未产出要点时才收 merge 节点的文本；空串不收（否则"要点 1 条"实为空内容的假象）
                String text = String.valueOf(v);
                if (ctx.digests.isEmpty() && text != null && !text.isBlank()) ctx.digests.add(text);
            }));
            long ms = System.currentTimeMillis() - t0;
            // rerank 聚合：各分支命中合并后按重排分（无重排分回退相关分）降序——
            // 主链路按顺序做价值驱动填充，排序后高分块优先占用上下文预算，低分块不再挤占。
            // 此时全部分支已汇合（fan-in 完成），collected 无并发写入，可安全原位排序
            if ("rerank".equals(configService.get("agent.aggregateMode")) && ctx.collected.size() > 1) {
                ctx.collected.sort(java.util.Comparator.comparingDouble(
                        (HybridRetrievalService.Hit h) -> h.rerankScore() != null ? h.rerankScore() : h.score())
                        .reversed());
                log.info("[SUBAGENT] rerank 聚合：{} 块按重排分降序合并", ctx.collected.size());
            }
            String digestText = aggregate(ctx, resolvedModel);
            log.info("[SUBAGENT] 并行编排完成（{}）：{} 个分支，命中 {} 块（去重后），要点 {} 条，耗时 {}ms",
                    delegated ? "子智能体委派" : "多视角", agents, ctx.collected.size(), ctx.digests.size(), ms);
            return new Outcome(List.copyOf(ctx.collected), digestText, agents, ms,
                    List.copyOf(ctx.branches));
        } catch (Exception e) {
            log.warn("[SUBAGENT] 并行编排失败（降级为单路检索）: {}", e.getMessage());
            return new Outcome(List.of(), "", 0, System.currentTimeMillis() - t0, List.copyOf(ctx.branches));
        } finally {
            CTX_REGISTRY.remove(ctxId);
        }
    }

    /**
     * 结果聚合（可配置策略）：把各分支产物合并成进主链路的要点段。
     *
     * <p>输入：各分支要点（ctx.digests，完成序）+ 失败分支（ctx.branches 里 status=failed）。
     * <ul>
     *   <li><b>失败占位</b>（agent.aggregateMarkFailed，默认开）：失败分支在要点段显式标注
     *       「未返回结果」，让主模型知道该视角无资料、可在回答里声明"该方面资料不足"，
     *       而不是静默跳过让主模型误以为资料齐全；</li>
     *   <li><b>supervisor 二次聚合</b>（agent.aggregateMode）：全部要点产出后由模型再聚合一轮——
     *       去重合并、按对回答的价值排序、结论矛盾显式标注冲突点、压缩进要点预算。
     *       放在图外而不是 merge 节点里做：图按分支数缓存（graphCache），聚合进图就得连缓存键
     *       一起改，而图外调用语义完全等价；失败回退 concat（聚合是增强项，不影响问答）；</li>
     *   <li><b>预算</b>（agent.digestMaxChars）：concat/rerank 模式下要点段超限截断
     *       （fail-loud：截断必留 warn，防止"要点被悄悄砍半"无人知晓）。</li>
     * </ul>
     */
    private String aggregate(RunCtx ctx, String resolvedModel) {
        List<String> parts = new ArrayList<>(ctx.digests);
        if (configService.getBoolean("agent.aggregateMarkFailed")) {
            for (Map<String, Object> b : ctx.branches) {
                if ("failed".equals(b.get("status"))) {
                    parts.add("· 【" + b.get("name") + "】未返回结果（该视角执行失败，无资料）");
                }
            }
        }
        if (parts.isEmpty()) return "";
        String joined = String.join("\n", parts);
        int budget = Math.max(200, configService.getInt("agent.digestMaxChars", 1500));
        if ("supervisor".equals(configService.get("agent.aggregateMode"))
                && (parts.size() > 1 || joined.length() > budget)) {
            String sup = superviseAggregate(joined, ctx.question, resolvedModel, budget, ctx.billingUid);
            if (sup != null) return sup;
            // superviseAggregate 内部已留 warn；此处回退 concat，继续走预算截断
        }
        if (joined.length() > budget) {
            log.warn("[SUBAGENT] 要点段超预算：{} 字 > 预算 {} 字，已截断（可调 agent.digestMaxChars 或改用 supervisor 模式）",
                    joined.length(), budget);
            joined = joined.substring(0, budget);
        }
        return joined;
    }

    /**
     * supervisor 二次聚合：把各分支要点合并为一份清单（去重、排序、标注冲突、压缩进预算）。
     * 失败返回 null（调用方回退 concat 直拼，不影响问答主链路）。
     */
    private String superviseAggregate(String joined, String question, String resolvedModel, int budget,
                                      String billingUid) {
        try {
            String prompt = "你是检索监督者（supervisor），负责汇总多个并行检索视角的要点。用户问题：" + question
                    + "\n\n各视角要点（【】内为来源角色/视角）：\n" + joined
                    + "\n\n请聚合为一份最终要点清单：\n"
                    + "1. 合并重复内容，删掉与问题无关的空话；\n"
                    + "2. 按对回答该问题的价值从高到低排序，价值很低的可舍弃；\n"
                    + "3. 不同视角的结论相互矛盾时，单独标注一行「⚠ 冲突：矛盾点说明」；\n"
                    + "4. 保留要点的【来源】前缀，让回答能区分视角；\n"
                    + "5. 全文控制在 " + budget + " 字以内。\n"
                    + "只输出要点清单本身，不要任何解释。";
            // 与 digest() 同一约束：chatClient 挂了 ToolCall Advisor，必须显式设 options 并关工具执行
            // 用量归属：与 digest() 同理，并行线程池上 ThreadLocal 不可见——hold 显式带上
            com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(billingUid, null, null, "subagent"));
            String out;
            try {
                out = chatClient.prompt()
                        .user(prompt)
                        .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .model(resolvedModel)
                                .temperature(configService.getDouble("chat.temperature"))
                                .internalToolExecutionEnabled(false)
                                .build())
                        .call()
                        .content();
            } finally {
                com.wenqu.ai.util.UsageAttr.clear();
            }
            if (out == null || out.isBlank()) {
                log.warn("[SUBAGENT] supervisor 聚合返回空（回退直拼）");
                return null;
            }
            String text = out.strip();
            if (text.length() > budget) {
                log.warn("[SUBAGENT] supervisor 聚合结果 {} 字超预算 {} 字，已截断", text.length(), budget);
                text = text.substring(0, budget);
            }
            log.info("[SUBAGENT] supervisor 聚合：{} 字 → {} 字", joined.length(), text.length());
            return text;
        } catch (Exception e) {
            // fail-loud：聚合失败回退直拼必须留 warn 线索（静默会表现为"要点变回直拼"无人知晓）
            log.warn("[SUBAGENT] supervisor 二次聚合失败，回退直拼: {}", e.getMessage());
            return null;
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
                    if (c != null) runWithOverrides(c, () -> runAgent(idx, c));
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

    /**
     * 在并行分支线程内重放本轮检索参数覆盖与<b>检索身份</b>后执行分支主体。
     * <p>分支跑在 reactor 线程池（{@code Schedulers.parallel()}），拿不到问答流水线线程的 ThreadLocal
     * ⇒ 不重放的话分支检索按全局参数跑（"编排卡片命中块数与主链路不一致"的隐藏原因），
     * 且身份为 anonymous（"共享给我的资料子代理检索不到"的根因）。
     * 线程池复用，finally 必须清，避免把本轮策略/身份泄漏给下一个任务。
     */
    private void runWithOverrides(RunCtx ctx, Runnable task) {
        Map<String, String> ov = ctx.baseOverrides;
        Map<String, String> uv = ctx.baseUserOverrides;
        boolean hasOv = ov != null && !ov.isEmpty();
        boolean hasUv = uv != null && !uv.isEmpty();
        // 身份重放与配置覆盖相互独立：即使没有任何配置覆盖，身份也必须重放（否则检索按 anonymous 判权限）
        String curUid = com.wenqu.ai.util.RequestUser.uid();
        boolean foreignIdentity = ctx.identityUid != null && !ctx.identityUid.equals(curUid);
        if (foreignIdentity) {
            com.wenqu.ai.util.RequestUser.set(ctx.identityUid, ctx.identityDept, ctx.identityRole);
        }
        if (!hasOv && !hasUv) {
            try {
                task.run();
            } finally {
                if (foreignIdentity) com.wenqu.ai.util.RequestUser.clear();
            }
            return;
        }
        if (hasOv) configService.putOverrides(ov);
        if (hasUv) configService.putUserOverrides(uv);
        try {
            task.run();
        } finally {
            if (hasOv) configService.clearOverride();
            if (hasUv) configService.clearUserOverrides();
            if (foreignIdentity) com.wenqu.ai.util.RequestUser.clear();
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
            String desc = branchDescription(sub, subQuery, ctx);
            ctx.emit(new BranchEvent(idx, name, "running", 0, System.currentTimeMillis() - ctx.t0, ctx.delegated, desc, ""));
            int topK = Math.max(1, configService.getInt("agent.topKPerAgent", 3));
            // 分支按各自知识库的向量模型分组检索（未挂库的分支查全局索引组）
            java.util.Collection<String> branchKbIds = sub == null || sub.getKnowledgeBaseIds() == null
                    || sub.getKnowledgeBaseIds().isBlank()
                    ? null : KnowledgeBaseService.splitIds(sub.getKnowledgeBaseIds());
            // adaptiveTopK=true：子代理与主链路同口径，让权限有效召回参与 topK 配额
            // （子代理只取 topKPerAgent 条，权限剔掉的名额会直接让分支结果变少）
            List<HybridRetrievalService.Hit> hits = retrievalService.search(subQuery, null, branchKbIds, true);
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
            // 本分支的要点提炼结果（编排视图展示"这个角色查到了什么"；汇总文本仍并入 system 供主模型参考）
            String branchDigest = "";
            if (configService.getBoolean("agent.digestEnabled") && !fresh.isEmpty()) {
                String digest = digest(subQuery, fresh, sub == null ? null : sub.getSystemPrompt(),
                        ctx.resolvedModel, ctx.billingUid);
                if (digest != null && !digest.isBlank()) {
                    branchDigest = digest.strip();
                    synchronized (ctx) {
                        ctx.digests.add("· " + (sub == null ? "" : "【" + sub.getName() + "】") + branchDigest);
                    }
                }
            }
            long doneMs = System.currentTimeMillis() - ctx.t0;
            ctx.emit(new BranchEvent(idx, name, "done", fresh.size(), doneMs, ctx.delegated, desc, branchDigest));
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("id", idx);
            branch.put("name", name);
            branch.put("status", "done");
            branch.put("hits", fresh.size());
            branch.put("elapsedMs", doneMs);
            branch.put("delegated", ctx.delegated);
            branch.put("digest", branchDigest);
            // 任务描述（子任务卡片的核心信息：让用户看懂"派它去干嘛"）
            branch.put("description", branchDescription(sub, subQuery, ctx));
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
            branch.put("digest", "");
            branch.put("description", "");
            synchronized (ctx) {
                ctx.branches.add(branch);
            }
        }
    }

    /**
     * 分支的「任务描述」（编排视图展示，对齐通用智能体平台的子任务卡片）：
     * 委派模式用子智能体的职责（描述/名称），多视角模式用该视角的检索词——让用户看懂这一路在查什么。
     */
    private static String branchDescription(Agent sub, String subQuery, RunCtx ctx) {
        if (sub != null) {
            String d = sub.getDescription() == null ? "" : sub.getDescription().trim();
            if (!d.isEmpty()) return d.length() > 60 ? d.substring(0, 60) + "…" : d;
            return "按「" + ctx.question + "」在其知识库范围内检索";
        }
        return "按视角检索：" + RunCtx.truncate(subQuery, 30);
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
    private String digest(String subQuery, List<HybridRetrievalService.Hit> hits, String rolePrompt,
                          String resolvedModel, String billingUid) {
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
            // 必须设 options：chatClient 上注册了 ToolCall Advisor，裸调用会抛
            // "ToolCall Advisor requires ToolCallingChatOptions to be set"（要点提炼曾长期静默失败）。
            // 提炼要点是纯文本任务，显式关闭工具执行——避免子分支误触发工具调用、也省掉工具相关开销。
            // 用量归属：分支跑在框架并行线程池上，ThreadLocal 不可见——hold 显式带上，finally 清理。
            com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(billingUid, null, null, "subagent"));
            String out;
            try {
                out = chatClient.prompt()
                        .user(prompt)
                        .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .model(resolvedModel)
                                .temperature(configService.getDouble("chat.temperature"))
                                .internalToolExecutionEnabled(false)
                                .build())
                        .call()
                        .content();
            } finally {
                com.wenqu.ai.util.UsageAttr.clear();
            }
            return out == null ? null : out.replaceAll("[\\r\\n]+", " ").trim();
        } catch (Exception e) {
            // fail-loud：提炼失败用户侧表现为"卡片无要点"，必须留 warn 线索（debug 级别等于静默）
            log.warn("[SUBAGENT] 要点提炼失败（卡片将无要点，不影响主链路）: {}", e.getMessage());
            return null;
        }
    }
}
