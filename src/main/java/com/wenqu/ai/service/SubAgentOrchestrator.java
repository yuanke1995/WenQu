package com.wenqu.ai.service;

import com.wenqu.ai.agent.subagent.BranchMode;
import com.wenqu.ai.agent.subagent.BranchReport;
import com.wenqu.ai.agent.subagent.BranchRequest;
import com.wenqu.ai.agent.subagent.BranchRunner;
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
 * <p>P3a 起，被委派的子智能体有两种跑法（每个子智能体自己选，见 {@code agent.subagentMode} 与
 * {@code c_ai_agent.subagent_mode}）：
 * <ul>
 *   <li><b>digest（检索要点）</b>：本类原本那条路——分支直接调混合检索 + 一次短 LLM 提炼。
 *       它快、便宜，但子智能体的工具/技能/沙盒/产物一概不参与；多视角检索用它更省；</li>
 *   <li><b>full（真全循环）</b>：分支跑一整轮完整问答（{@code BranchRunner} 交给问答管线，
 *       自己的知识库范围、工具、技能、沙盒都按它本人的配置生效），于是"派它去把这份报表跑出来"
 *       才真的成立。代价是每条分支等于一次问答的时延与花费，所以它<b>按子智能体逐个开</b>，
 *       并且受嵌套深度上限与单支超时两道闸约束（见 {@link #modeOf}）。</li>
 * </ul>
 * 平台默认档是 digest：不配置时行为与本类引入 P3a 之前逐字相同。
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
     * @param hits       全部子代理命中（已跨代理去重，保序）——全循环分支里就是它这一轮实际参考过的资料，
     *                   并进主链路同一套 [N] 编号空间
     * @param digestText 各子代理的要点汇总（可为空串：未开启提炼或全部失败；全循环分支不走这一段）
     * @param agents     实际执行的子代理数
     * @param elapsedMs  总耗时（用于日志/评估对比单路检索）
     * @param branches   各分支最终状态（供编排视图渲染/持久化）：
     *                    {id, name, status, hits, elapsedMs, delegated, digest, description, mode, steps, answerPreview}
     * @param fulls      全循环分支的完整回报（按分支下标），供问答侧把里面的 [N]/[图片N] 续编成父轮编号
     *                    ——digest 分支为空，主链路那段文本继续走 {@code digestText}
     */
    public record Outcome(List<HybridRetrievalService.Hit> hits, String digestText, int agents, long elapsedMs,
                          List<Map<String, Object>> branches, List<FullBranch> fulls) {
        public Outcome(List<HybridRetrievalService.Hit> hits, String digestText, int agents, long elapsedMs) {
            this(hits, digestText, agents, elapsedMs, List.of(), List.of());
        }
    }

    /**
     * 一条全循环分支的最终回报（带分支归属，问答侧据此把子轮编号映射到父轮编号）。
     *
     * @param idx    分支下标（与 {@code branches} 里的 id 对齐）
     * @param name   分支显示名（子智能体名）
     * @param report 回报本体（回答正文仍是<b>子轮自己的编号</b>，引用与图片的真身在 report 的两份表里）
     */
    public record FullBranch(int idx, String name, BranchReport report) {
    }

    /**
     * 本轮分支执行的入参（P3a：与 {@code ChatCommand} 同一个理由——这些字段全是同类型字符串，
     * 位置写错编译器不拦，而分支跑在并行线程上，错了的表现是"拿着父会话 id 去问自己的问题"）。
     *
     * @param question        用户问题（委派模式下各分支都用原问题）
     * @param subAgents       主智能体委派的子智能体（null/空 = 走多视角策略）
     * @param onBranch        分支进度回调（可为 null）
     * @param resolvedModel   主链路本轮已解析的生效模型（辅助调用与分支都复用它，不重新裁决）
     * @param depth           本轮处于第几层（0 = 用户在对话里发起的那一轮；&ge;1 是子智能体分支）
     * @param parentSessionId 父会话 id（分支的临时会话要往哪儿收养产物）
     * @param parentTurnId    父轮 id
     * @param userId          分支的归属用户（可见性、个人资产、台账都按他）
     * @param parentBrief     父轮上下文摘要（问题 + 最近对话，预算已按深度裁剪；可为空）
     * @param guestMode       免登录分享会话：分支不放开全循环（没有观众，也不给沙盒/MCP）
     * @param runner          全循环分支的执行器；null = 本轮不允许全循环（老调用方、评估链路）
     */
    public record BranchSpec(String question, List<Agent> subAgents, Consumer<BranchEvent> onBranch,
                             String resolvedModel, int depth, String parentSessionId, String parentTurnId,
                             String userId, String parentBrief, boolean guestMode, BranchRunner runner) {
    }

    /**
     * 分支进度事件（编排视图实时展示用）：status ∈ running | done | failed。
     * name 为分支显示名（委派=子智能体名，多视角=视角描述）；hits 为该分支本次命中块数；
     * description 为"派它去干嘛"的任务描述；digest 为要点结果（仅 done 时有值）；
     * mode 为该分支的执行形态（digest|full，让用户看得见"这一支是交资料还是真干活"）；
     * steps 为全循环分支实际跑的工具步数（digest 分支恒 0）。
     */
    public record BranchEvent(int idx, String name, String status, int hits, long elapsedMs, boolean delegated,
                              String description, String digest, String mode, int steps) {
        public BranchEvent(int idx, String name, String status, int hits, long elapsedMs, boolean delegated,
                           String description, String digest) {
            this(idx, name, status, hits, elapsedMs, delegated, description, digest, BranchMode.DIGEST.code(), 0);
        }

        public BranchEvent(int idx, String name, String status, int hits, long elapsedMs, boolean delegated) {
            this(idx, name, status, hits, elapsedMs, delegated, "", "", BranchMode.DIGEST.code(), 0);
        }
    }

    /** 单轮执行上下文：图节点在编译期绑定，运行期参数经 ThreadLocal 传入（与 KnowledgeRetrievalTool 同模式） */
    private static final class RunCtx {
        /** 本轮分支执行入参（问题/候选/回调/深度/归属/执行器全在这里，不再摊成构造参数） */
        final BranchSpec spec;
        final String question;
        final List<String> subQueries;
        /** 主智能体委派的子智能体（null/空 = 走原有的多视角策略） */
        final List<Agent> subAgents;
        final List<HybridRetrievalService.Hit> collected = new ArrayList<>();
        final Set<String> seenKids = new LinkedHashSet<>();
        final List<String> digests = new ArrayList<>();
        /** 全循环分支的完整回报（保序，按分支下标）；问答侧用它把子轮编号续编成父轮编号 */
        final List<FullBranch> fulls = new ArrayList<>();
        /** 分支进度回调（可为 null：不需要实时推送时传 null，如评估/调试场景） */
        final Consumer<BranchEvent> onBranch;
        /** 分支最终状态（保序，供 Outcome.branches 与编排视图持久化） */
        final List<Map<String, Object>> branches = new ArrayList<>();
        final long t0;
        final boolean delegated;
        /** 主链路已解析的本轮生效模型（会话覆盖 > 个人默认），供要点提炼等辅助调用复用 */
        final String resolvedModel;
        /**
         * 各分支的执行形态（下标对齐 subAgents）。装配在 {@code run} 入口一次算完：
         * 档位、深度上限、是否分享会话这三条判据都跟线程无关，逐分支重复问一遍反而会让同一轮的
         * 分支在不同线程上判出不一致的结果。
         */
        final List<BranchMode> modes = new ArrayList<>();
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

        RunCtx(BranchSpec spec, List<String> subQueries, Map<String, String> baseOverrides,
               Map<String, String> baseUserOverrides, String billingUid, String identityUid,
               String identityDept, String identityRole) {
            this.spec = spec;
            this.question = spec.question();
            this.subQueries = subQueries;
            this.subAgents = spec.subAgents();
            this.onBranch = spec.onBranch();
            this.t0 = System.currentTimeMillis();
            this.delegated = spec.subAgents() != null && !spec.subAgents().isEmpty();
            this.resolvedModel = spec.resolvedModel();
            this.baseOverrides = baseOverrides == null ? Map.of() : baseOverrides;
            this.baseUserOverrides = baseUserOverrides == null ? Map.of() : baseUserOverrides;
            this.billingUid = billingUid;
            this.identityUid = identityUid;
            this.identityDept = identityDept;
            this.identityRole = identityRole;
        }

        /** 本轮分支里是否有全循环分支（有则整轮换用专属线程池跑，见 {@link #FULL_EXECUTOR} 注释） */
        boolean anyFull() {
            return modes.contains(BranchMode.FULL);
        }

        BranchMode modeOf(int idx) {
            return idx < modes.size() ? modes.get(idx) : BranchMode.DIGEST;
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
     * 并行编排入口（老签名，恒走「检索 + 要点提炼」那条轻量分支）。
     * <p>保留给评估/调试这类不需要全循环的调用方：{@code runner} 传 null 就等于宣告
     * "本轮不许往下派完整问答"，于是档位无论怎么配都只会跑 digest——回滚面只剩这一个参数。
     */
    public Outcome run(String question, List<Agent> subAgents, Consumer<BranchEvent> onBranch, String resolvedModel) {
        return run(new BranchSpec(question, subAgents, onBranch, resolvedModel, 0, null, null,
                null, null, false, null));
    }

    /**
     * 并行编排入口；任何异常都不抛出（编排是增强项，失败应降级为单路检索，不能影响问答）
     */
    public Outcome run(BranchSpec spec) {
        boolean delegated = spec.subAgents() != null && !spec.subAgents().isEmpty();
        int maxParallel = maxParallelBranches();
        int agents = delegated
                ? Math.min(spec.subAgents().size(), maxParallel)
                : Math.max(2, Math.min(maxParallel, configService.getInt("agent.subAgents", 2)));
        long t0 = System.currentTimeMillis();
        // 归属 uid 只在入口线程（问答流水线）可见，RunCtx 带进分支线程供辅助调用记账
        String entryUid = com.wenqu.ai.util.RequestUser.uid();
        // 检索身份三元组：同样只在入口线程可见，但分支检索（库门下推 + 文档可见性晚绑定）要按真实用户判，
        // 不带则分支拿到 anonymous → 配了共享范围（department/user）的文档被判不可见，
        // 表现为「主链路能引用的资料，子代理分支里检索不到」。anonymous 时整体置 null（保持既有 fail-closed 行为）。
        String entryDept = com.wenqu.ai.util.RequestUser.departmentId();
        String entryRole = com.wenqu.ai.util.RequestUser.role();
        boolean anonymous = com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(entryUid);
        RunCtx ctx = new RunCtx(spec, planSubQueries(spec.question(), agents),
                configService.currentOverrides(), configService.currentUserOverrides(),
                anonymous ? null : entryUid,
                anonymous ? null : entryUid, entryDept, entryRole);
        // 各分支的执行形态在入口一次判完（同一份档位与深度判据，避免并发线程各判各的）
        for (int i = 0; i < agents; i++) ctx.modes.add(modeOf(spec, i));
        int fullCount = 0;
        for (BranchMode m : ctx.modes) if (m == BranchMode.FULL) fullCount++;
        if (delegated) {
            log.info("[SUBAGENT] 分支执行方式：{} 支全循环 / {} 支检索要点（深度 {}，嵌套上限 {}，并行上限 {}）",
                    fullCount, agents - fullCount, spec.depth(), maxDepth(), maxParallel);
        }
        // 上下文注册到注册表，state 里只带可安全序列化的 id（框架会序列化 state，见 CTX_KEY 注释）
        String ctxId = java.util.UUID.randomUUID().toString();
        CTX_REGISTRY.put(ctxId, ctx);
        try {
            if (ctx.anyFull()) {
                // 有全循环分支 ⇒ 整轮换到分支专属线程池跑（不再用图）：全循环分支要等一整轮问答，
                // 挂在框架的 Schedulers.parallel() 上会把 reactor 的共享并行池占死，
                // 连累同一 JVM 里其它流的并行算子（见 FULL_EXECUTOR 注释）。
                // digest 分支在同一条路上照旧跑 runAgent，两种形态能混在一轮里。
                runBranchesOnExecutor(ctx, agents);
            } else {
                CompiledGraph graph = graphCache.computeIfAbsent(agents, this::buildGraph);
                Map<String, Object> inputs = new HashMap<>();
                inputs.put("question", spec.question());
                inputs.put(CTX_KEY, ctxId);
                Optional<OverAllState> result = graph.invoke(inputs);
                result.ifPresent(s -> s.value("digestText").ifPresent(v -> {
                    // 仅当分支未产出要点时才收 merge 节点的文本；空串不收（否则"要点 1 条"实为空内容的假象）
                    String text = String.valueOf(v);
                    if (ctx.digests.isEmpty() && text != null && !text.isBlank()) ctx.digests.add(text);
                }));
            }
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
            String digestText = aggregate(ctx, spec.resolvedModel());
            log.info("[SUBAGENT] 并行编排完成（{}）：{} 个分支，命中 {} 块（去重后），要点 {} 条，全循环回报 {} 条，耗时 {}ms",
                    delegated ? "子智能体委派" : "多视角", agents, ctx.collected.size(), ctx.digests.size(),
                    ctx.fulls.size(), ms);
            return new Outcome(List.copyOf(ctx.collected), digestText, agents, ms,
                    List.copyOf(ctx.branches), List.copyOf(ctx.fulls));
        } catch (Exception e) {
            log.warn("[SUBAGENT] 并行编排失败（降级为单路检索）: {}", e.getMessage());
            return new Outcome(List.of(), "", 0, System.currentTimeMillis() - t0,
                    List.copyOf(ctx.branches), List.copyOf(ctx.fulls));
        } finally {
            CTX_REGISTRY.remove(ctxId);
        }
    }

    // ==================== P3a：全循环分支 ====================

    /**
     * 分支专属线程池（全循环分支等待整轮问答用）。
     * <p>为什么不用 Spring AI Alibaba 的那张图（digest 分支仍在图上跑）：图把并行节点调度在
     * {@code Schedulers.parallel()} 上，那是 reactor 全 JVM 共享的固定并行池（线程数=CPU 核数）。
     * 检索+提炼是百毫秒级，占一占无所谓；全循环分支要等一整轮问答（几十秒），几条分支就能把
     * 那个池占满，被连累的是同进程里所有用 parallel scheduler 的流——这不是"慢一点"，是无关功能跟着堵。
     * <p>容量按 {@code agent.maxParallelSubAgents}：分支数就是这个池的大小，多出来的分支排队等前一条收工，
     * 而排队时间同样计入单支超时（超时按失败降级，不会无声卡住）。池随配置联动扩容，见 {@link #syncFullPoolSize}。
     */
    private static final java.util.concurrent.ThreadPoolExecutor FULL_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "subagent-branch");
                t.setDaemon(true);
                return t;
            });

    /** 并行分支上限（原来硬编码 4，委派名单也按 4 截断；现在配置项管，1~8） */
    private int maxParallelBranches() {
        return Math.max(1, Math.min(8, configService.getInt("agent.maxParallelSubAgents", 4)));
    }

    /** 嵌套深度硬顶（默认 2）：到顶后不再往下派全循环，那一支回落成检索要点 */
    private int maxDepth() {
        return Math.max(1, configService.getInt("agent.subagentMaxDepth", 2));
    }

    /** 单条全循环分支的等待上限（下限 15s，避免配成 0 让分支必判失败） */
    private long branchTimeoutMs() {
        return Math.max(15_000L, configService.getLong("agent.subagentTimeoutMs", 240000L));
    }

    /**
     * 这一支怎么跑：档位（子智能体自己选的 &gt; 平台默认档）之上还压着三条否决——
     * <ul>
     *   <li><b>没有执行器</b>（老签名调用、评估链路）：本轮压根不允许派完整问答；</li>
     *   <li><b>深度到顶</b>：分支自己也能编排，配置成环时并发是乘法往下长，且不报错、只表现为额度被吃满，
     *       所以这一层必须由平台拦；</li>
     *   <li><b>免登录分享会话</b>：访客那一轮没有观众、也不挂沙盒与 MCP，派一条完整问答出去
     *       只会得到一条没人看的挂起分支。</li>
     * </ul>
     */
    private BranchMode modeOf(BranchSpec spec, int idx) {
        if (spec.runner() == null || spec.subAgents() == null || idx >= spec.subAgents().size()) {
            return BranchMode.DIGEST;
        }
        if (spec.depth() >= maxDepth()) return BranchMode.DIGEST;
        if (spec.guestMode()) return BranchMode.DIGEST;
        Agent sub = spec.subAgents().get(idx);
        BranchMode platformDefault = BranchMode.of(configService.get("agent.subagentMode"), BranchMode.DIGEST);
        return BranchMode.of(sub == null ? null : sub.getSubagentMode(), platformDefault);
    }

    /**
     * 在分支专属池上并行跑完所有分支（fan-in 由 {@code allOf} 承担），并给整体等待加一道兜底闸。
     * <p>超时兜底是必需的而不是可选的：分支内部的超时管的是"子轮回答不出来"，管不到
     * "问答流水线线程池被排满、子轮根本没开始跑"那种情况——没有这道闸，父轮会无限期挂在
     * {@code join()} 上，用户看到的是整轮卡死且没有任何报错。
     */
    private void runBranchesOnExecutor(RunCtx ctx, int agents) {
        syncFullPoolSize(Math.max(agents, maxParallelBranches()));
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < agents; i++) {
            final int idx = i;
            futures.add(java.util.concurrent.CompletableFuture.runAsync(
                    () -> runWithOverrides(ctx, () -> runBranch(idx, ctx)), FULL_EXECUTOR));
        }
        long slack = 15_000L;
        long overall = branchTimeoutMs() + slack;
        try {
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(overall, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            // 只报还没汇合的那几支：已完成的分支状态已由 runBranch 自己写过，不能覆成失败
            int stuck = 0;
            for (int i = 0; i < futures.size(); i++) {
                if (!futures.get(i).isDone()) {
                    futures.get(i).cancel(true);
                    stuck++;
                    markBranchTimeout(ctx, i);
                }
            }
            log.warn("[FAIL-LOUD] 分支整体等待超时（{}ms，{} 支未汇合，池活跃 {}/{}）——未汇合按失败降级",
                    overall, stuck, FULL_EXECUTOR.getActiveCount(), FULL_EXECUTOR.getMaximumPoolSize());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            for (java.util.concurrent.CompletableFuture<Void> f : futures) f.cancel(true);
            log.warn("[SUBAGENT] 分支等待被中断（父轮已退场），未汇合分支按失败处理");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            // runBranch 内部已吞掉分支异常，走到这里说明是调度层的问题，必须留线索不能静默
            log.warn("[SUBAGENT] 分支并行调度异常（已完成的分支结果保留）: {}", cause.toString());
        }
    }

    /** 超时/中断后补一条分支终态（编排视图不能留着"正在跑"的那张卡） */
    private void markBranchTimeout(RunCtx ctx, int idx) {
        long ms = System.currentTimeMillis() - ctx.t0;
        String name = ctx.branchName(idx);
        ctx.emit(new BranchEvent(idx, name, "failed", 0, ms, ctx.delegated, "", "",
                ctx.modeOf(idx).code(), 0));
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("id", idx);
        branch.put("name", name);
        branch.put("status", "failed");
        branch.put("hits", 0);
        branch.put("elapsedMs", ms);
        branch.put("delegated", ctx.delegated);
        branch.put("digest", "");
        branch.put("description", "分支整体等待超时，未返回结果");
        branch.put("mode", ctx.modeOf(idx).code());
        branch.put("steps", 0);
        synchronized (ctx) {
            ctx.branches.add(branch);
        }
    }

    /** 线程数随 agent.maxParallelSubAgents 联动（维持 max ≥ core 的不变量，先抬 max 再归位 core） */
    private static void syncFullPoolSize(int n) {
        if (n <= 0 || n == FULL_EXECUTOR.getCorePoolSize()) return;
        FULL_EXECUTOR.setMaximumPoolSize(Math.max(FULL_EXECUTOR.getMaximumPoolSize(), n));
        FULL_EXECUTOR.setCorePoolSize(n);
        FULL_EXECUTOR.setMaximumPoolSize(n);
        log.info("[SUBAGENT] 分支线程池调整为 {} 线程", n);
    }

    /** 一条分支的派发点：按本轮判好的档位走全循环或检索要点 */
    private void runBranch(int idx, RunCtx ctx) {
        if (ctx.modeOf(idx) == BranchMode.FULL) {
            runFullAgent(idx, ctx);
        } else {
            runAgent(idx, ctx);
        }
    }

    /**
     * 全循环分支：把这一支交回问答管线跑完整一轮，回收回报。
     * <p>与 digest 分支的分工：本方法只负责"派出去、等回来、把状态写成编排视图认的形状"。
     * 会话、超时、产物收养、编号都在 {@code BranchRunner} 的实现（RagService）里——那里才有
     * 本轮的会话服务与引用列表，编排器无从介入，硬要在此处编号就会把问答侧的知识抄一份到编排侧。
     * <p>失败不抛：回报状态写成 failed/timeout，主链路照常作答，并在要点段留一行占位
     * （沿用 {@code agent.aggregateMarkFailed} 那条"失败要看得见"的口径）。
     */
    private void runFullAgent(int idx, RunCtx ctx) {
        Agent sub = ctx.subAgents != null && idx < ctx.subAgents.size() ? ctx.subAgents.get(idx) : null;
        String name = ctx.branchName(idx);
        if (sub == null || ctx.spec.runner() == null) {
            // 判据与执行必须一致：档位判成 full 却没得可跑，宁可按 digest 跑完也不能空转一支
            runAgent(idx, ctx);
            return;
        }
        long start = System.currentTimeMillis();
        ctx.emit(new BranchEvent(idx, name, "running", 0, start - ctx.t0, ctx.delegated,
                fullBranchDescription(sub, ctx.question), "", BranchMode.FULL.code(), 0));
        BranchRequest req = new BranchRequest(ctx.spec.parentSessionId(), ctx.spec.parentTurnId(),
                sub.getId(), name, ctx.question, ctx.spec.parentBrief(), ctx.spec.depth() + 1,
                ctx.spec.userId(), ctx.resolvedModel, ctx.spec.guestMode(), branchTimeoutMs());
        BranchReport report;
        try {
            report = ctx.spec.runner().run(req);
        } catch (Exception e) {
            // Runner 的契约是不外抛；真抛了说明实现破了契约，这里兜住并留线索（不能让整轮编排跟着炸）
            log.warn("[SUBAGENT] 全循环分支执行器异常（按失败降级）: {}", e.toString());
            report = BranchReport.failed(BranchReport.FAILED, e.toString(), System.currentTimeMillis() - start);
        }
        long ms = System.currentTimeMillis() - start;
        boolean ok = report.usable();
        List<HybridRetrievalService.Hit> fresh = new ArrayList<>();
        if (ok) {
            // 子轮实际参考过的资料并进主链路的命中池（与 digest 分支同一条去重规则）：
            // 这是父轮能给出可点开引用的前提——只把子轮的结论抄进上下文，用户就得到一个指不回资料的回答。
            int topK = Math.max(1, configService.getInt("agent.topKPerAgent", 3));
            synchronized (ctx) {
                int added = 0;
                for (Map<String, Object> s : report.sourcesOrEmpty()) {
                    HybridRetrievalService.Hit h = hitFromChildSource(s);
                    if (h == null || h.knowledgeId() == null || !ctx.seenKids.add(h.knowledgeId())) continue;
                    ctx.collected.add(h);
                    fresh.add(h);
                    if (++added >= topK) break;
                }
                ctx.fulls.add(new FullBranch(idx, name, report));
            }
        }
        String preview = ok ? RunCtx.truncate(report.answerOrEmpty().replaceAll("\\s+", " ").trim(), 120) : "";
        ctx.emit(new BranchEvent(idx, name, ok ? "done" : "failed", fresh.size(), ms, ctx.delegated,
                fullBranchDescription(sub, ctx.question), preview, BranchMode.FULL.code(), report.toolSteps()));
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("id", idx);
        branch.put("name", name);
        branch.put("status", ok ? "done" : "failed");
        branch.put("hits", fresh.size());
        branch.put("elapsedMs", ms);
        branch.put("delegated", ctx.delegated);
        branch.put("digest", preview);
        branch.put("description", fullBranchDescription(sub, ctx.question));
        // 编排视图用它区分两种分支：全循环那一支显示"跑了 N 步工具 + 交付了什么"，而不是"命中几块"
        branch.put("mode", BranchMode.FULL.code());
        branch.put("steps", report.toolSteps());
        if (!report.artifactFilesOrEmpty().isEmpty()) branch.put("artifacts", report.artifactFilesOrEmpty());
        if (!ok && report.error() != null) branch.put("error", report.error());
        // 回报全文（编排卡片的下钻）：卡片那一行摘要只有 120 字，用户想知道"它到底交回了什么"
        // 时不该去翻日志。上限 2000 字：这一份要随消息落库，全循环分支一次可以写几千字，
        // 不设上限就是每条分支都在过程 JSON 里存一篇长文，历史越多的会话加载越慢
        if (ok) {
            String full = report.answerOrEmpty().replaceAll("[ \\t]+", " ").trim();
            branch.put("report", full.length() > 2000 ? full.substring(0, 2000) + "…" : full);
        }
        synchronized (ctx) {
            ctx.branches.add(branch);
        }
    }

    /**
     * 子轮的引用来源 → 主链路可用的命中块。
     * <p>{@code content} 取子轮的 snippet（父轮的上下文填充段本就按片段窗口截取，拿不到整块也不影响
     * 引用展示）；{@code chunkIndex}/{@code titlePath} 子轮没下发，置空即可——主链路只有相邻块合并用得到它们。
     */
    @SuppressWarnings("unchecked")
    private static HybridRetrievalService.Hit hitFromChildSource(Map<String, Object> src) {
        if (src == null) return null;
        String kid = str(src.get("knowledgeId"));
        if (kid == null || kid.isBlank()) return null;
        List<String> images = src.get("images") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        double score = num(src.get("score"), 0.0);
        Double rerank = src.get("rerankScore") == null ? null
                : Double.valueOf(String.valueOf(src.get("rerankScore")));
        return new HybridRetrievalService.Hit(kid, str(src.get("docId")), str(src.get("title")),
                str(src.get("snippet")), images, score, null, null, rerank);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static double num(Object v, double fallback) {
        if (v == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 全循环分支的任务描述：委派语按"它去做这件事"来写，而不是"按它检索" */
    private static String fullBranchDescription(Agent sub, String question) {
        String d = sub == null || sub.getDescription() == null ? "" : sub.getDescription().trim();
        if (!d.isEmpty()) return d.length() > 60 ? d.substring(0, 60) + "…" : d;
        return "由「" + (sub == null ? "子智能体" : sub.getName()) + "」完整办一遍：" + RunCtx.truncate(question, 30);
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
