package com.wenqu.ai.service.websearch;

import com.wenqu.ai.service.ConfigService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 联网搜索工具（Function Calling / Tool Calling）。
 * <p>
 * 让模型在知识库覆盖不到、且问题依赖时效/外部事实（价格、版本、政策、新闻）时主动联网检索。
 * 默认关闭（tool.enabled + webSearch.enabled 两级总闸）。
 * <p>
 * **结果必须进引用体系**：命中注册为引用来源并续编 [N]（与主链路、知识检索工具共用同一编号空间），
 * 模型按编号标注后才能溯源；不注册而让模型引用，等于放行无出处的断言，与问渠的可溯源性冲突。
 * <p>
 * 线程模型：注册器走 ThreadLocal（与 KnowledgeRetrievalTool 同范式）——Spring AI 同步执行工具回调，
 * 工具对象是**全局单例**，用实例字段会跨会话串号。
 *
 * @author yuanke
 */
@Component
public class WebSearchTools {

    /** 工具名（与前端 TOOL_LABELS、装配处 sensitiveTools 判定保持一致；改名需同步） */
    public static final String TOOL_NAME = "webSearch";

    /** 单条摘要截断字符数（控制工具结果 token 量，配置 webSearch.snippetChars） */
    private static final int DEFAULT_SNIPPET_CHARS = 600;

    /**
     * 本轮问答的落点：注册引用来源 + 扣减单轮配额 + 记录降级提示。
     * 由 RagService 在流式问答期间注入（ThreadLocal），非流式直接调用时为 null。
     */
    public interface WebSearchSink {
        /** 扣减一次本轮搜索配额；false=已达 webSearch.maxCallsPerTurn，本次调用应拒绝 */
        boolean tryConsumeQuota();

        /** 注册一条结果为引用来源，返回分配的引用编号（同一 URL 已注册则复用原编号） */
        int register(WebSearchResult result);

        /** 记录一条降级提示（fail-loud：让用户看见"为什么没搜成"，而不是静默无结果） */
        void degrade(String code, String message);
    }

    private static final ThreadLocal<WebSearchSink> SINK = new ThreadLocal<>();

    public static void setSink(WebSearchSink s) {
        SINK.set(s);
    }

    public static void clearSink() {
        SINK.remove();
    }

    private final WebSearchService webSearchService;
    private final ConfigService configService;

    public WebSearchTools(WebSearchService webSearchService, ConfigService configService) {
        this.webSearchService = webSearchService;
        this.configService = configService;
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
            @ToolParam(description = "返回结果条数（1~5，默认取系统配置）", required = false) Integer maxResults) {

        if (query == null || query.isBlank()) {
            return "搜索词不能为空";
        }
        WebSearchSink sink = SINK.get();
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

        int snippetChars = configService.getInt("webSearch.snippetChars", DEFAULT_SNIPPET_CHARS);
        StringBuilder sb = new StringBuilder();
        int seq = 0;
        for (WebSearchResult r : results) {
            seq++;
            if (sink != null) {
                sb.append("【引用").append(sink.register(r)).append("】");
            } else {
                sb.append("【片段").append(seq).append("】");
            }
            sb.append("标题：").append(r.title()).append("\n");
            if (r.siteName() != null && !r.siteName().isBlank()) {
                sb.append("站点：").append(r.siteName()).append("\n");
            }
            if (r.publishedAt() != null && !r.publishedAt().isBlank()) {
                sb.append("发布：").append(r.publishedAt()).append("\n");
            }
            String snippet = r.snippet();
            if (snippet.length() > snippetChars) snippet = snippet.substring(0, snippetChars) + "…（已截断）";
            sb.append("摘要：").append(snippet).append("\n");
            sb.append("链接：").append(r.url()).append("\n\n");
        }
        if (sink != null) {
            sb.append("（回答中引用以上内容时，请在对应句子后用方括号标注上述引用编号，如 [3]；"
                    + "只能使用上列编号，未列出的编号一律不要输出。联网来源与知识库来源同等对待，都必须标注）");
        }
        return sb.toString().trim();
    }
}
