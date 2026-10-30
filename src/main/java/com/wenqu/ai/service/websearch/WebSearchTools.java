package com.wenqu.ai.service.websearch;

import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.HybridRetrievalService;
import com.wenqu.ai.service.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网搜索工具（Function Calling / Tool Calling）。
 * <p>
 * 让模型在知识库覆盖不到、且问题依赖时效/外部事实（价格、版本、政策、新闻）时主动联网检索。
 * 默认关闭（tool.enabled + webSearch.enabled 两级总闸）。
 * <p>
 * **结果必须进引用体系**：命中注册为引用来源并续编 [N]（与主链路、知识检索工具共用同一编号空间），
 * 模型按编号标注后才能溯源；不注册而让模型引用，等于放行无出处的断言，与问渠的可溯源性冲突。
 * <p>
 * **结果必须过相关度门**（与库内来源同一把尺）：搜索返回后按<b>本轮问题</b>重排，低于
 * {@code retrieval.minContextScore} 的不注册、不列给模型。修复前这里零门禁——本地 SearXNG
 * 引擎降级时会把百度首页、小红书首页、机场交通页当搜索结果返回（其 score 是 1.0/0.5/0.333
 * 位置衰减，毫无相关性含义），原样进引用面板就是「检索内容页」一半是垃圾。
 * 判据取 sink 上的本轮问题（改写后的检索查询），取不到时退化为工具查询本身。
 * <p>
 * 线程模型：本轮落点（配额、引用注册、降级）经 {@code ToolContext} 随本次调用传入，工具对象自身
 * 不持有任何跨调用的可变状态。迁移前这里是一对静态 ThreadLocal（setSink / clearSink），
 * 注释自陈「否则跨会话串号」——它靠时间窗而不是作用域保证正确性，漏清一次就把上一轮的落点
 * 留给下一个会话。见 P1 seam 化：docs/agent-runtime-gap-2026q4.md §三 P1。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class WebSearchTools {

    /** 工具名（与前端 TOOL_LABELS、装配处 sensitiveTools 判定保持一致；改名需同步） */
    public static final String TOOL_NAME = "webSearch";

    /** 单条摘要截断字符数（控制工具结果 token 量，配置 webSearch.snippetChars） */
    private static final int DEFAULT_SNIPPET_CHARS = 600;

    /** 重排没跑成时的词面下限（配置 webSearch.fallbackMinOverlap；写 0 = 退回「整批发出不过滤」的旧行为） */
    private static final double DEFAULT_FALLBACK_MIN_OVERLAP = 0.2;

    /**
     * 按本轮问题重排这批结果，返回「片段 → 相关度分」；重排实际没跑成时返回空表（调用方按兜底走）。
     */
    private Map<String, Double> rankByQuery(List<WebSearchResult> results, String gateQuery) {
        List<HybridRetrievalService.Hit> toRank = new ArrayList<>();
        for (WebSearchResult r : results) {
            toRank.add(new HybridRetrievalService.Hit(null, "", r.title(), r.snippet(),
                    List.of(), 0d, null, null, null, null));
        }
        Map<String, Double> scoreBySnippet = new HashMap<>();
        for (HybridRetrievalService.Hit h : rerankService.rankForced(toRank, gateQuery)) {
            if (h.rerankScore() != null) scoreBySnippet.put(h.content(), h.rerankScore());
        }
        return scoreBySnippet;
    }

    /**
     * 词面重叠度（0~1）：问题里的词有多少出现在标题+摘要里。
     * <p>中文按<b>二元字组</b>——单字太泛（「法」「条」「题」几乎无处不在），整句又要求逐字一致；
     * 英文与数字按词，忽略标点与空白。
     * <p>它<b>不是</b>语义判断，只能剔掉明显不沾边的（本机实测：中文法条查询下引擎返回的
     * researchgate 树木论文标题与摘要重叠为 0），所以只在重排没跑成时上场，也别拿搜索结果自带的
     * {@code score} 当相关性——那是位置衰减（1.0/0.5/0.333），毫无相关性含义。
     */
    static double lexicalOverlap(String query, String title, String snippet) {
        java.util.Set<String> q = new java.util.LinkedHashSet<>();
        collect(query, q);
        if (q.isEmpty()) return 1d;     // 问题拆不出可比对的词：不裁，交回原口径
        java.util.Set<String> d = new java.util.HashSet<>();
        collect(title, d);
        collect(snippet, d);
        int hit = 0;
        for (String g : q) {
            if (d.contains(g)) hit++;
        }
        return (double) hit / q.size();
    }

    /** 切词面：汉字串按相邻二元组，字母数字串按整词，其余字符是分隔符。 */
    private static void collect(String text, java.util.Set<String> out) {
        if (text == null) return;
        StringBuilder word = new StringBuilder();
        char prevHan = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isHan(c)) {
                if (word.length() > 0) {
                    out.add(word.toString());
                    word.setLength(0);
                }
                if (prevHan != 0) out.add("" + prevHan + c);
                prevHan = c;
            } else if (Character.isLetterOrDigit(c)) {
                prevHan = 0;
                word.append(Character.toLowerCase(c));
            } else {
                if (word.length() > 0) {
                    out.add(word.toString());
                    word.setLength(0);
                }
                prevHan = 0;
            }
        }
        if (word.length() > 0) out.add(word.toString());
    }

    /** 基本汉字区就够：判据要比对的是中文网页标题与摘要，日文假名那类不参与。 */
    private static boolean isHan(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /**
     * 本轮问答的落点：注册引用来源 + 扣减单轮配额 + 记录降级提示 + 提供本轮问题（相关度门判据）。
     * 由问答侧经 {@code ToolContext} 随本次调用传入（见 {@link #CTX_SINK}），非流式直接调用时为 null。
     */
    public interface WebSearchSink {
        /** 扣减一次本轮搜索配额；false=已达 webSearch.maxCallsPerTurn，本次调用应拒绝 */
        boolean tryConsumeQuota();

        /** 本轮问题（相关度门的判据）：问答侧传入的是改写后的检索查询，取不到返回 null */
        String question();

        /** 注册一条结果为引用来源，返回分配的引用编号（同一 URL 已注册则复用原编号） */
        int register(WebSearchResult result);

        /** 注册并携带按本轮问题重排出的相关度分（供引用面板展示；null = 未重排） */
        int register(WebSearchResult result, Double rerankScore);

        /** 记录一条降级提示（fail-loud：让用户看见"为什么没搜成"，而不是静默无结果） */
        void degrade(String code, String message);
    }

    /**
     * toolContext 键：本次调用的落点（值为 {@link WebSearchSink}，即问答侧那一轮的引用注册器/配额计）。
     * <p>非流式直接调用（无上下文）时为 null：照样能搜，只是结果不进引用体系、不扣本轮配额。
     */
    public static final String CTX_SINK = "wenqu.websearch.sink";

    private static WebSearchSink sinkOf(org.springframework.ai.chat.model.ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get(CTX_SINK);
        return v instanceof WebSearchSink s ? s : null;
    }

    private final WebSearchService webSearchService;
    private final ConfigService configService;
    private final RerankService rerankService;

    public WebSearchTools(WebSearchService webSearchService, ConfigService configService,
                          RerankService rerankService) {
        this.webSearchService = webSearchService;
        this.configService = configService;
        this.rerankService = rerankService;
    }

    /**
     * 联网搜索，返回与查询相关的网页结果（标题、链接、摘要）。
     * 当知识库资料不足以回答、或问题涉及时效信息（最新价格/版本/政策/新闻/外部事实）时调用。
     *
     * @param query      搜索关键词或短句（像在搜索引擎里那样写，不要写完整句子）
     * @param maxResults 需要返回的结果条数（1~5，默认取配置值）
     * @return 命中的网页结果（含引用编号），未命中或失败时返回可说明原因的文本
     */
    @Tool(description = "联网搜索，返回与查询相关的网页结果（标题、链接、摘要）。"
            + "当知识库资料不足以回答、或问题涉及时效信息（最新价格、版本、政策、新闻、外部事实）时调用本工具。"
            + "不要用它回答知识库已覆盖的内容，也不要凭记忆编造搜索结果。")
    public String webSearch(
            @ToolParam(description = "搜索关键词或短句，像在搜索引擎里那样写，不要写完整句子") String query,
            @ToolParam(description = "返回结果条数（1~5，默认取系统配置）", required = false) Integer maxResults,
            org.springframework.ai.chat.model.ToolContext toolContext) {
        return webSearch(query, maxResults, sinkOf(toolContext));
    }

    /** 显式传落点的版本（无引用体系的调用方传 null） */
    public String webSearch(String query, Integer maxResults, WebSearchSink sink) {

        if (query == null || query.isBlank()) {
            return "搜索词不能为空";
        }
        if (sink != null && !sink.tryConsumeQuota()) {
            return "已达到本轮联网搜索次数上限，不再执行搜索。请基于已有信息直接给出最终回答，"
                    + "并如实说明这部分内容未经过联网核实。";
        }
        List<WebSearchResult> results;
        try {
            results = webSearchService.search(query.trim(), maxResults);
        } catch (WebSearchException e) {
            // fail-loud：把原因说给模型，再由模型转告用户；同时挂一条本轮降级提示。
            // 区分"配置缺失"与"服务临时故障"，前者不该让模型反复重试。
            if (sink != null) {
                sink.degrade("webSearchUnavailable", "联网搜索不可用：" + e.getMessage());
            }
            return "联网搜索失败：" + e.getMessage()
                    + (e.isConfigIssue()
                    ? "（属平台配置问题，重试不会成功；请如实说明无法联网核实，不要编造结果）"
                    : "（可稍后再试一次；若仍失败，请如实说明无法联网核实）");
        }
        if (results == null || results.isEmpty()) {
            return "未检索到相关网页结果。请如实说明没有找到可依据的联网资料，不要凭记忆编写内容。";
        }

        // 相关度门：按本轮问题重排，低于 retrieval.minContextScore 的结果不注册、不列给模型。
        // 判据优先取 sink 上的本轮问题（多轮指代消解后的检索查询，与主管线判相关同口径），
        // 取不到（非流式直调）退化为工具查询本身。重排实际没跑成（不可用/请求失败）时不过门、
        // 全量保留并挂降级——宁可让用户看见"没过滤"，也不把一次瞬时故障演变成"什么都搜不到"。
        String gateQuery = sink != null ? sink.question() : null;
        if (gateQuery == null || gateQuery.isBlank()) gateQuery = query.trim();
        List<WebSearchResult> kept = results;
        int filtered = 0;
        String unavailable = rerankService.debugUnavailableReason();
        Map<String, Double> scoreBySnippet = unavailable == null
                ? rankByQuery(results, gateQuery) : Map.of();
        if (!scoreBySnippet.isEmpty()) {
            double minGate = configService.getDouble("retrieval.minContextScore",
                    ConfigDefaults.RETRIEVAL_MIN_CONTEXT_SCORE);
            kept = new ArrayList<>();
            for (WebSearchResult r : results) {
                Double rr = scoreBySnippet.get(r.snippet());
                if (rr != null && minGate > 0 && rr >= minGate) {
                    kept.add(new WebSearchResult(r.title(), r.url(), r.snippet(), r.siteName(),
                            r.publishedAt(), r.score(), rr));
                } else {
                    filtered++;
                    log.debug("[WEB-SEARCH] 相关度不足不过门：{}（rerank={}）", r.title(), rr);
                }
            }
        } else {
            // 重排没跑成（服务不可用，或这批一条都没拿到分）。此时原样发出去等于把引擎返回的
            // 首页/机场页当资料——法律这类场景「搜到垃圾」比「搜不到」更危险，所以按词面下限
            // 先剔掉明显不沾边的；下限写 0 = 退回旧行为（整批发出 + 一句没过滤）。
            double floor = configService.getDouble("webSearch.fallbackMinOverlap",
                    DEFAULT_FALLBACK_MIN_OVERLAP);
            log.warn("[WEB-SEARCH] 重排不可用（{}），本轮改用词面下限 {}",
                    unavailable == null ? "这批结果没有返回重排分" : unavailable, floor);
            if (floor > 0) {
                List<WebSearchResult> pass = new ArrayList<>();
                for (WebSearchResult r : results) {
                    if (lexicalOverlap(gateQuery, r.title(), r.snippet()) >= floor) pass.add(r);
                    else filtered++;
                }
                kept = pass;
                if (sink != null) {
                    sink.degrade("webSearchLexicalOnly", "重排不可用，本轮按关键词匹配过滤（不是语义相关度），"
                            + "已滤掉 " + filtered + " 条与问题不沾边的结果");
                }
            } else if (sink != null) {
                sink.degrade("webSearchUnfiltered", "重排不可用，联网搜索结果未经相关性过滤，可能包含不相关来源");
            }
        }
        if (kept.isEmpty()) {
            return "未检索到与问题相关的网页结果（" + filtered + " 条搜索结果相关度不足，已过滤）。"
                    + "请如实说明没有找到可依据的联网资料，不要凭记忆编写内容。";
        }

        int snippetChars = configService.getInt("webSearch.snippetChars", DEFAULT_SNIPPET_CHARS);
        StringBuilder sb = new StringBuilder();
        // 联网摘要与知识库正文同为不可信数据：信封+数据声明与检索工具同口径（防网页内容埋指令）
        sb.append(com.wenqu.ai.util.UntrustedContent.DATA_RULE).append("\n\n");
        int seq = 0;
        for (WebSearchResult r : kept) {
            seq++;
            if (sink != null) {
                sb.append("【引用").append(sink.register(r, r.rerankScore())).append("】");
            } else {
                sb.append("【片段").append(seq).append("】");
            }
            sb.append("标题：").append(com.wenqu.ai.util.UntrustedContent.sanitize(r.title() == null ? "" : r.title())).append("\n");
            if (r.siteName() != null && !r.siteName().isBlank()) {
                sb.append("站点：").append(com.wenqu.ai.util.UntrustedContent.sanitize(r.siteName())).append("\n");
            }
            if (r.publishedAt() != null && !r.publishedAt().isBlank()) {
                sb.append("发布：").append(com.wenqu.ai.util.UntrustedContent.sanitize(r.publishedAt())).append("\n");
            }
            String snippet = com.wenqu.ai.util.UntrustedContent.sanitize(r.snippet() == null ? "" : r.snippet());
            if (snippet.length() > snippetChars) snippet = snippet.substring(0, snippetChars) + "…（已截断）";
            // 摘要套信封（source=链接）：网页内容是注入防护里最不可信的一档
            sb.append("摘要：\n").append(com.wenqu.ai.util.UntrustedContent.envelope(r.url(), snippet)).append("\n");
            sb.append("链接：").append(r.url()).append("\n\n");
        }
        if (filtered > 0) {
            sb.append("（另有 ").append(filtered).append(" 条搜索结果与问题相关度不足，未列为引用，不要使用。）\n\n");
        }
        if (sink != null) {
            sb.append("（回答中引用以上内容时，请在对应句子后用方括号标注上述引用编号，如 [3]；"
                    + "只能使用上列编号，未列出的编号一律不要输出。联网来源与知识库来源同等对待，都必须标注）");
        }
        return sb.toString().trim();
    }
}
