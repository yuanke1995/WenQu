package com.wenqu.ai.util;

import com.wenqu.ai.common.BizException;

/**
 * MCP 服务登记规则的单一事实源：个人登记（{@code McpController}）与市场 MCP 目录
 * （{@code MarketService} 的 mcp 物品）共用同一套名称/地址/传输类型口径，
 * 避免两处各说各话后目录装出来的服务在 MCP 页过不了校验。
 *
 * @author yuanke
 */
public final class McpServerRules {

    private McpServerRules() {
    }

    /** 每个用户最多登记的 server 数（连接池上限同源，防止一次接入几十个外部服务拖垮问答） */
    public static final int MAX_SERVERS_PER_USER = 10;

    /**
     * 校验服务名与地址：名称必填、≤40 字、不含 {@code /}（服务名参与智能体引用串
     * {@code {uid}/{name}} 的拆分，含 {@code /} 会让引用解析错位）；地址必须 http(s)。
     */
    public static void validate(String name, String url) {
        if (name == null || name.isBlank()) throw new BizException("请填写服务名称");
        if (name.length() > 40) throw new BizException("服务名称不超过 40 个字符");
        if (name.contains("/")) throw new BizException("服务名称不能包含 /");
        if (url == null || url.isBlank()) throw new BizException("请填写服务地址");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BizException("服务地址需以 http:// 或 https:// 开头");
        }
    }

    /** 传输类型归一：空/未知按 streamable 兜底（标准 MCP 传输默认端点 /mcp；sse 是旧版传输） */
    public static String normalizeType(String type) {
        if (type == null || type.isBlank()) return "streamable";
        return "sse".equalsIgnoreCase(type.trim()) ? "sse" : "streamable";
    }
}
