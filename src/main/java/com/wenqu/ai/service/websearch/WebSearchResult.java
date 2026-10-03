package com.wenqu.ai.service.websearch;

/**
 * 联网搜索单条结果（服务商无关的中间模型）。
 * <p>
 * 各服务商（Tavily / 博查 / 自建 SearXNG）的响应字段不同，统一收敛到这里后再进引用体系，
 * 避免上层（WebSearchTools / RagService）感知服务商差异。
 *
 * @param title       结果标题（展示与引用用）
 * @param url         结果链接（溯源的锚点，也是同一来源去重的键）
 * @param snippet     摘要/正文片段（**引用自检的证据来源**，比整页正文更适合做 [N] 证据）
 * @param siteName    站点名（展示用；服务商不提供时由 url 的 host 兜底）
 * @param publishedAt 发布时间（服务商不提供则 null，不猜测、不补当前时间）
 * @param score       服务商相关性分（仅透出做展示，不参与门限判定）
 */
public record WebSearchResult(
        String title,
        String url,
        String snippet,
        String siteName,
        String publishedAt,
        Double score
) {
}
