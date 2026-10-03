package com.wenqu.ai.service.websearch;

import java.util.List;

/**
 * 联网搜索服务商适配（SPI）。
 * <p>
 * 只负责「发一次请求 + 把响应解析成 {@link WebSearchResult} 列表」，不做配额/截断/引用注册
 * （那些是会话级语义，归 WebSearchService 与 WebSearchTools）。
 * <p>
 * 实现类以 Spring Bean 注册，{@code id()} 与配置项 {@code webSearch.provider} 对应。
 *
 * @author yuanke
 */
public interface WebSearchProvider {

    /** 服务商标识（与配置 webSearch.provider 取值一致） */
    String id();

    /**
     * 执行一次搜索。
     *
     * @param req 请求（含 query、条数、服务地址、密钥、超时）
     * @return 结果列表（可为空，表示未搜到）
     * @throws Exception 失败一律抛出（由 WebSearchService 转成可读原因，fail-loud，不做静默降级）
     */
    List<WebSearchResult> search(WebSearchRequest req) throws Exception;
}
