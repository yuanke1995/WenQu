package com.wenqu.ai.service.websearch;

/**
 * 一次搜索请求的入参（服务商无关）。
 *
 * @param baseUrl    服务地址（空=用该服务商的默认地址）
 * @param apiKey     API Key（generic 自建服务可为空）
 * @param timeoutMs  本次请求超时
 * @param query      搜索词
 * @param maxResults 期望返回条数（服务商可能返回更少）
 */
public record WebSearchRequest(
        String baseUrl,
        String apiKey,
        int timeoutMs,
        String query,
        int maxResults
) {
}
