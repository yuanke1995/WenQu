package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.service.HybridRetrievalService.Hit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库精确检索工具（Function Calling / Tool Calling）。
 * <p>
 * 让 LLM 在主链路已注入 RAG 上下文之外，仍能按需主动调用一次"精确检索知识库"，
 * 用于：1) 主链路未召回但模型判断需要补充的信息；2) 用户追问时针对特定主题的二次检索。
 * 暴露条件：工具总闸开启，且未被智能体三态/平台停用名单关闭。
 * <p>
 * 返回内容经裁剪，避免工具结果膨胀挤占上下文预算；命中内容与主链路 RAG 召回天然互补。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class KnowledgeRetrievalTool {

    /** 单次工具调用返回的命中块上限（调用方也可用 topK 显式指定，这里兜底截断） */
    private static final int MAX_HITS = 5;
    /** 单块 content 截断字符数（控制工具结果 token 量） */
    private static final int MAX_CONTENT_CHARS = 600;
    /** 悬浮/弹窗溯源摘要截断长度（注册进 sources 的 snippet） */
    private static final int SNIPPET_CHARS = 120;

    /** 引用来源注册器：流式问答期间由 ChatService 注入（instrumentTools 包装层），把工具命中注册进当前回答的来源列表并分配引用编号 */
    public interface SourceRegistrar {
        /**
         * 注册一个工具命中的知识块。
         *
         * @return 注册结果：ref=引用编号；imageSeqs=该来源图片分配到的全局图片编号（块无图则为空；
         *         重复注册同一块时返回首次分配的编号）。工具文本据此附「本段相关截图」清单，
         *         模型才能在正文中用真实存在的 [图片N] 配图，而不是编造编号。
         */
        Registration register(Hit hit, String snippet);

        /**
         * 登记一条本轮可见降级（与 ChatService.addDegradation 同口径、同一份 degradations 列表）。
         * 工具链路自己拿不到 TurnState，由注册器桥接——典型用途：重排不可用时明确告知
         * 「本轮引用未做语义筛选」，而不是让用户对着一堆弱相关引用以为是检索本来就这样。
         */
        void degrade(String code, String msg);

        /** 注册结果 */
        record Registration(int ref, java.util.List<Integer> imageSeqs) {}
    }

    /**
     * toolContext 键：本轮引用来源注册器（值为 {@link SourceRegistrar}）。
     * <p>P1 迁移前这里是两个类级 ThreadLocal（{@code setSourceRegistrar} / {@code setKbScope} +
     * finally clear）。它依赖两个不体面的前提：① setter 与工具执行在同一个线程上同步发生；
     * ② 每次 set 都配得上一次 clear。①在新版 Spring AI 允许工具并行执行时不成立；②只要有一条
     * 异常路径漏清，池化线程就把上一轮的注册器带给下一个会话——引用编号会注册进别人的回答里，
     * 用户看到的是「我引用了不存在的出处」。现在落点装进本次调用独占的上下文，作用域=这一次调用，
     * 没有跨调用的可变状态可漏清。
     */
    public static final String CTX_SOURCE_REGISTRAR = "wenqu.knowledge.sourceRegistrar";

    /**
     * 工具检索范围（与主链路同库界，2026-09-26 库隔离修复）：智能体绑定了知识库时，
     * searchKnowledge 不允许越过范围检索其他库——否则「仅挂刑法库」的智能体经工具仍能
     * 把其他库的块召回并注册进引用来源。kbIds 限定向量检索的库（null=不限，与主链路
     * scopeKbIds 同语义）；docIds 对命中做后过滤（null=不限，空集合=范围内无文档，检索为空）；
     * kbFilter 是 @ 知识库提及的收窄后过滤（null/空=不收窄）：命中块的所属库必须落在
     * 用户本轮点名的库集合里——它只收窄、不放宽（向量路的 kbIds 原样保留智能体资料域，
     * 域外被点名的库不会因提及而越界检索）。
     */
    public record KbScope(java.util.Collection<String> kbIds, java.util.Set<String> docIds,
                          java.util.Set<String> kbFilter) {
        public KbScope(java.util.Collection<String> kbIds, java.util.Set<String> docIds) {
            this(kbIds, docIds, null);
        }
    }

    /** toolContext 键：本轮检索范围（值为 {@link KbScope}，null=不限） */
    public static final String CTX_KB_SCOPE = "wenqu.knowledge.scope";

    /** 从本次调用的上下文取注册器（无上下文=非流式直接调用，退回不注册引用的旧行为） */
    private static SourceRegistrar registrarOf(org.springframework.ai.chat.model.ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get(CTX_SOURCE_REGISTRAR);
        return v instanceof SourceRegistrar r ? r : null;
    }

    private static KbScope scopeOf(org.springframework.ai.chat.model.ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get(CTX_KB_SCOPE);
        return v instanceof KbScope s ? s : null;
    }

    /**
     * 诊断用：读取 RequestUser 的身份属性（ThreadLocal）。
     *工具跑在 boundedElastic 弹性线程上，若身份上下文未随轮次传播过来，这里会是 null/异常，
     * 而 {@code HybridRetrievalService.loadVisibleKbIds()} 恰好依赖同一ThreadLocal，
     * 取空后会 fail-closed 直接返回「不发起任何向量检索」——正是「工具恒空且耗时仅十几毫秒」的成因。
     */
    private static Object safeUserAttr(String attr) {
        try {
            return switch (attr) {
                case "uid" -> com.wenqu.ai.util.RequestUser.uid();
                case "dept" -> com.wenqu.ai.util.RequestUser.departmentId();
                case "role" -> com.wenqu.ai.util.RequestUser.role();
                default -> null;
            };
        } catch (Exception e) {
            return "<" + attr + "读取异常>";
        }
    }

    private final HybridRetrievalService hybridRetrievalService;
    private final ConfigService configService;
    private final RerankService rerankService;
    /** 库级检索参数：重排模型按“本轮范围的第一个配了模型的库”收敛（一轮只有一个重排器） */
    private final KbRetrievalParamService kbRetrievalParams;

    public KnowledgeRetrievalTool(HybridRetrievalService hybridRetrievalService, ConfigService configService,
                                  RerankService rerankService, KbRetrievalParamService kbRetrievalParams) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.configService = configService;
        this.rerankService = rerankService;
        this.kbRetrievalParams = kbRetrievalParams;
    }

    /**
     * 精确检索知识库，返回与查询相关的内容片段。
     * 当你认为已有信息不足以回答用户问题，或需要核对知识库中某个具体功能/字段/步骤/报错处理时，调用本工具补充检索。
     *
     * @param query 用于检索知识库的关键词或短语（越精准召回越好，可包含功能名/字段名/操作对象）
     * @param topK  需要返回的结果条数（1~5，默认 5；数值越小返回内容越精简）
     * @return 命中的知识片段列表（含章节路径、标题与正文），未命中返回空
     */
    @Tool(description = "精确检索知识库，返回与查询相关的知识片段（含章节路径、标题、正文）。当现有信息不足以回答、或需要核对知识库中具体功能/字段/步骤/报错处理时调用。")
    public String searchKnowledge(
            @ToolParam(description = "检索知识库的关键词或短语，越精准越好，可含功能名/字段名/操作对象") String query,
            @ToolParam(description = "返回结果条数（1~5，默认 5）") Integer topK,
            org.springframework.ai.chat.model.ToolContext toolContext) {
        // 本轮的落点（引用注册器 / 检索范围 / 反思循环标记）全部来自本次调用的上下文，
        // 由问答侧的 ToolEnrichment 按次注入——工具不再读任何跨调用的静态状态
        return searchKnowledge(query, topK, scopeOf(toolContext), registrarOf(toolContext));
    }

    /**
     * 显式入参版本：非流式调用方（对外 MCP server 的平台工具）走这条，检索范围由调用方自己给。
     * <p>迁移前它靠 {@code KnowledgeRetrievalTool.setKbScope(...)} + finally clear 那对静态方法设置范围，
     * 池化线程漏清就把上一个调用者的库界留给下一个调用者——对免登录对外端点来说这是越权读库，
     * 现在只能显式传，没有「忘了传还留着上次的」这种状态可留。
     */
    public String searchKnowledge(String query, Integer topK, KbScope scope,
                                  SourceRegistrar registrar) {

        if (query == null || query.isBlank()) {
            return "查询关键词不能为空";
        }
        // 上限取配置（tool.knowledgeRetrieval.maxHits，1~5；此前该键只在 ConfigService 播种、
        // 从未被读取，等于设置页上一个改了不动的假开关）：默认与工具给模型的参数说明（1~5）一致
        int cap = Math.max(1, Math.min(MAX_HITS, configService.getInt("tool.knowledgeRetrieval.maxHits", MAX_HITS)));
        int limit = topK == null ? cap : Math.max(1, Math.min(topK, cap));
        // 范围与主链路对齐：向量路按库界检索（kbIds），命中再按文档范围后过滤（docIds，含空集合 fail-closed）
        // 透传诊断：此前工具传 null，向量/关键词路失败与库门下推失败完全不可见，
        // 排查「工具空结果但主链路 @ 引用能拿到」时只能盲猜。现在把诊断带进来并打各阶段计数日志。
        HybridRetrievalService.RetrievalDiag diag = new HybridRetrievalService.RetrievalDiag();
        List<Hit> hits;
        // 检索耗时一并记录：融合召回恒为 0 且耗时只有十几毫秒，说明根本没发起embedding 网络调用，
        // 疑似工具线程（boundedElastic-*）丢失 RequestUser ThreadLocal → loadVisibleKbIds() 取空
        // → resolveVectorStores() fail-closed 返回 List.of()。这两个值用来一眼区分「召回不到」
        // 与「压根没查」（uid=null / 耗时极短 / 有失败标记）。
        long t0 = System.currentTimeMillis();
        Object uid = safeUserAttr("uid");
        try {
            // adaptiveTopK=true：与主链路同口径，权限剔掉的名额由补采补回（工具结果直接进模型上下文，
            // 召回不足会表现为「知识库里明明有却答不出来」）
            hits = hybridRetrievalService.search(query.trim(), diag,
                    scope == null ? null : scope.kbIds(), true);
        } catch (Exception e) {
            return "知识库检索失败：" + e.getMessage();
        }
        log.info("[TOOL-DIAG] searchKnowledge query=\"{}\" 融合召回 {} 条耗时 {}ms；uid={} dept={} role={} thread={} 向量路失败={}({}) 关键词路失败={} 库门下推失败={} scopeKbIds={} scopeDocIds数={}",
                query.trim(), hits == null ? 0 : hits.size(), System.currentTimeMillis() - t0,
                uid, safeUserAttr("dept"), safeUserAttr("role"),
                Thread.currentThread().getName(),
                diag.isVectorFailed(), diag.lastError(),
                diag.isKeywordFailed(), diag.aclPushdownError(),
                scope == null ? null : scope.kbIds(),
                scope == null || scope.docIds() == null ? null : scope.docIds().size());
        if (scope != null && scope.docIds() != null) {
            int before = hits.size();
            hits = hits.stream()
                    .filter(h -> h.docId() != null && scope.docIds().contains(h.docId()))
                    .toList();
            log.info("[TOOL-DIAG] docId 后过滤：{} → {} 条（scope.docIds={}）", before, hits.size(),
                    scope.docIds());
        }
        // @ 知识库提及的收窄（两道闸的第二道）：关键词路本就不按库界走，只收窄向量路的 kbIds
        // 会留一半口子——「以为只按这个库答，别的库的命中照样进来」。所属库不在点名集合里的
        // 命中（含图扩展、手动块）一律剔除；kbId 解析不出的块宁可丢也不放（收窄语义下宁少不滥）。
        if (scope != null && scope.kbFilter() != null && !scope.kbFilter().isEmpty()) {
            int before = hits.size();
            hits = hits.stream()
                    .filter(h -> h.kbId() != null && scope.kbFilter().contains(h.kbId()))
                    .toList();
            log.info("[TOOL-DIAG] @kb 收窄后过滤：{} → {} 条（kbFilter={}）", before, hits.size(),
                    scope.kbFilter());
        }
        // 补重排（强制窗口）：search() 只出融合分，本工具此前从未重排，相关分门发生在两个分值域上——
        // 与查询仅词面重叠的无关块（如"框架/使用"命中操作手册章节）融合分可达 0.6+，而真实重排分 ~0.000x，
        // 全部混进工具结果并注册进引用面板。rankForced 不受 minHits 窗口限制（工具候选可能不足 6 条），
        // 失败时回退融合分序（rerankScore=null，走下方融合门），与主链路降级语义一致。
        // 重排模型归知识库（库主付费）：在**本轮实际命中**的库里，按范围顺序取第一个绑了模型的库；
        // 都没绑则由平台默认兜底（rerank.platformRef）。withRerankModel 只在有库级模型时临时覆盖。
        final List<Hit> candidates = hits;
        final List<String> hitKbIds = candidates.stream().map(Hit::kbId).toList();
        hits = kbRetrievalParams.withRerankModel(
                scope == null ? null : scope.kbIds(), hitKbIds,
                () -> rerankService.rankForced(candidates, query.trim()));
        // 最低相关分门（分域双门，与主链路上下文填充同款语义）：重排分走 retrieval.minContextScore（0.6），
        // 未重排（关闭/失败回退）的融合分走 retrieval.minFusionScore（0.5）——两个分值域分布不同，一个绝对门
        // 不可能同时对两者成立；重排本轮实际执行过时，未重排候选不得借融合门入场（单侧约束）。
        // 工具命中会注册进本轮引用来源，弱相关块同样不得借工具链路回流到引用面板。0=关闭对应域的门。
        boolean rerankActive = hits != null && hits.stream().anyMatch(h -> h.rerankScore() != null);
        double minRerankGate = configService.getDouble("retrieval.minContextScore", ConfigDefaults.RETRIEVAL_MIN_CONTEXT_SCORE);
        double minFusionGate = configService.getDouble("retrieval.minFusionScore", ConfigDefaults.RETRIEVAL_MIN_FUSION_SCORE);
        if (hits != null && (minRerankGate > 0 || minFusionGate > 0)) {
            int before = hits.size();
            hits = hits.stream().filter(h -> {
                if (rerankActive && h.rerankScore() == null) return false;
                double rankScore = h.rerankScore() != null ? h.rerankScore() : h.score();
                double gate = h.rerankScore() != null ? minRerankGate : minFusionGate;
                return gate <= 0 || rankScore >= gate;
            }).toList();
            log.info("[TOOL-DIAG] 重排/融合分门（rerankActive={} minRerank={} minFusion={}）：{} → {} 条",
                    rerankActive, minRerankGate, minFusionGate, before, hits.size());
        }
        if (hits == null || hits.isEmpty()) {
            // 透出诊断原因，避免「工具空结果」只能盲猜：向量/关键词路是否失败、库门下推是否失败
            StringBuilder why = new StringBuilder("未在知识库中检索到相关内容");
            List<String> reasons = new ArrayList<>();
            if (diag.isVectorFailed()) reasons.add("向量路失败" + (diag.lastError() != null ? "(" + diag.lastError() + ")" : ""));
            if (diag.isKeywordFailed()) reasons.add("关键词路降级/失败");
            if (diag.aclPushdownError() != null) reasons.add("库门下推失败(" + diag.aclPushdownError() + ")");
            if (!reasons.isEmpty()) why.append("（").append(String.join("；", reasons)).append("）");
            return why.toString();
        }
        // 注册模式（流式问答）：命中块注册进当前回答的来源列表续编引用编号，
        // 文本用【引用N】并提示模型按编号标注 → 前端角标悬浮/引用弹窗可溯源；
        // 来源图片并入全局图片编号体系后在块尾附「本段相关截图」清单（[图片N：描述]），
        // 模型按清单在正文真实位置引用编号 → 前端 images[N-1] 映射出真实截图；
        // 非注册模式（无流上下文，如直接调用）：保持旧【片段N】格式
        // 重排没执行 = 引用门退化到融合分门（minFusionScore，默认 0.5）：词面重叠的弱相关块更容易注册进引用面板。
        // 此前工具链路对此完全静默（主链路 rerankIfNeeded 有提示，但只在主链路有命中时才登记），
        // 用户看到一堆与问题不相关的引用却没有任何说明。这里按同一口径登记可见降级。
        if (!rerankActive && registrar != null) {
            String reason = rerankService.debugUnavailableReason();
            registrar.degrade("rerankUnavailable",
                    "重排不可用（" + (reason == null ? "本轮未返回重排分" : reason)
                            + "）：本轮引用未做语义筛选，相关性可能偏低");
        }
        StringBuilder sb = new StringBuilder();
        // 资料数据身份声明先于一切块进上下文：检索内容（含网页导入、外部上传的文档）是不可信数据，
        // 其中埋的指令性文字不得执行——声明与信封（UntrustedContent.envelope）同属注入防护
        sb.append(com.wenqu.ai.util.UntrustedContent.DATA_RULE).append("\n\n");
        int count = 0;
        for (Hit h : hits) {
            if (count >= limit) break;
            count++;
            String content = com.wenqu.ai.util.UntrustedContent.sanitize(h.content() == null ? "" : h.content());
            String cut = content.length() > MAX_CONTENT_CHARS ? content.substring(0, MAX_CONTENT_CHARS) + "…（已截断）" : content;
            SourceRegistrar.Registration reg = null;
            if (registrar != null) {
                String snippet = content.length() > SNIPPET_CHARS ? content.substring(0, SNIPPET_CHARS) + "…" : content;
                reg = registrar.register(h, snippet);
                sb.append("【引用").append(reg.ref()).append("】");
            } else {
                sb.append("【片段").append(count).append("】");
            }
            if (h.titlePath() != null && !h.titlePath().isBlank()) {
                sb.append("章节：").append(com.wenqu.ai.util.UntrustedContent.sanitize(h.titlePath())).append(" / ");
            }
            sb.append("标题：").append(com.wenqu.ai.util.UntrustedContent.sanitize(h.title() == null ? "" : h.title())).append("\n");
            // 正文套信封：章节/标题行是系统生成的元信息格式，正文才是不可信原文
            sb.append(com.wenqu.ai.util.UntrustedContent.envelope("知识库块", cut)).append("\n");
            // 图片清单放块尾：正文截断（MAX_CONTENT_CHARS）会丢掉原文里的图片占位，清单不受影响；
            // 描述帮助模型判断这张截图讲什么、该插在哪个步骤（与主链路 [图片N：描述] 同格式）
            if (reg != null && !reg.imageSeqs().isEmpty()) {
                sb.append("本段相关截图：");
                for (Integer seq : reg.imageSeqs()) {
                    sb.append("[图片").append(seq).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        if (registrar != null) {
            // 编号约定要说"全局"：旧措辞只讲"标注上述引用编号"，多轮检索下来模型会按自己的
            // 答题结构把来源重编一套 1..N（2026-10-18 真机现场：31 条来源被重编成 16 个角标，
            // 每个角标点开都是另一段资料）。这里把"跨结果持续有效、必须原样使用"写死在约定里。
            sb.append("（回答中引用以上内容时，请在对应句子后用方括号标注上述引用编号，如 [3]。"
                    + "引用编号是本轮全局编号：同一次回答里多次检索结果中的编号连续有效、不会再变，"
                    + "必须原样使用各结果给出的编号，不得自行重新编号或另起一套；"
                    + "若要在回答中插入截图，只能使用上列「本段相关截图」中给出的 [图片N] 编号，"
                    + "没有列出的编号一律不要输出——不存在对应的图片）");
        }
        // 检索-反思循环已随深度思考退役（2026-10）：不再注入「证据充分性自评」约束。
        return sb.toString().trim();
    }
}
