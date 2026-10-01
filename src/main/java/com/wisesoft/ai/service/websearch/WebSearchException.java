package com.wisesoft.ai.service.websearch;

/**
 * 联网搜索失败（fail-loud）。
 * <p>
 * 与解析队列的 ParseFatalException 同思路：原因要能直接回给模型与用户，
 * 「搜不到」和「服务没配好」必须分得开，不能统一吞成"没有结果"。
 */
public class WebSearchException extends Exception {

    /** 区分"服务不可用"与"配置缺失"，供上层决定是否记 degradation */
    private final boolean configIssue;

    public WebSearchException(String message) {
        this(message, false);
    }

    public WebSearchException(String message, boolean configIssue) {
        super(message);
        this.configIssue = configIssue;
    }

    /** true=配置问题（未启用/缺 key/未知服务商），此类失败属平台侧，不该让模型反复重试 */
    public boolean isConfigIssue() {
        return configIssue;
    }
}
