package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 资源工具：读取外部 MCP Server 暴露的 resources / resource templates。
 *
 * <p>与 MCP 工具族（{@link com.wenqu.ai.agent.tool.provider.McpToolProvider}）的区别：
 * 工具面走 {@code tools/list} + {@code tools/call}，资源面走 {@code resources/list} +
 * {@code resources/read}，是 MCP 协议的两条不同能力。此前问渠只接了工具面，模型看不到
 * 「某个 server 里有哪些文档/数据集」这类只读资源。
 *
 * <p>归属与安全：连接池按 uid 分池，工具从 {@link ToolContext} 取本轮 uid
 * （跨线程读不到 RequestUser，见 {@link PresentArtifactTool#CTX_USER_ID}）。
 * 结果按 server 归属回传，读单个资源必须显式给 server 名。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class McpResourceTools {

    /** 返回给模型的资源条目上限：资源清单可能很长，超出只报条数并提示用 server 收窄。 */
    private static final int MAX_ITEMS = 50;
    /** 单条文本资源回给模型的字符上限（防止把整本书塞进上下文）。 */
    private static final int MAX_TEXT_CHARS = 60_000;

    private final McpClientService mcp;

    public McpResourceTools(McpClientService mcp) {
        this.mcp = mcp;
    }

    @Tool(name = "mcpListResources",
            description = "列出你登记的 MCP 服务暴露的资源（resources）：文档、数据集、文件等只读条目。"
                    + "返回每条的 server / uri / name / mimeType；要读正文再用 mcpReadResource。"
                    + "server 省略=列出全部已连接服务。")
    public String mcpListResources(
            @ToolParam(description = "只列某个 MCP 服务的资源（服务名）；省略=全部", required = false) String server,
            ToolContext ctx) {
        return list(ctx, server, false);
    }

    @Tool(name = "mcpListResourceTemplates",
            description = "列出 MCP 服务的资源模板（resource templates，带 {参数} 的 URI 模板）。"
                    + "模板本身没有正文，按模板拼出具体 uri 后再用 mcpReadResource 读取。")
    public String mcpListResourceTemplates(
            @ToolParam(description = "只列某个 MCP 服务的模板；省略=全部", required = false) String server,
            ToolContext ctx) {
        return list(ctx, server, true);
    }

    @Tool(name = "mcpReadResource",
            description = "读取一个 MCP 资源的正文（按 server + uri）。二进制资源只回 base64 前 64KB。")
    public String mcpReadResource(
            @ToolParam(description = "资源所属的 MCP 服务名（mcpListResources 返回的 server 字段）") String server,
            @ToolParam(description = "资源 uri（mcpListResources 返回的 uri 字段，或按模板拼出的具体 uri）") String uri,
            ToolContext ctx) {
        String uid = uid(ctx);
        if (uid == null) return "无法定位当前用户，MCP 资源读取失败";
        if (server == null || server.isBlank()) return "缺少 server 参数：请先用 mcpListResources 查到资源归属的服务名。";
        if (uri == null || uri.isBlank()) return "缺少 uri 参数。";
        try {
            List<Map<String, Object>> rows = mcp.readResource(uid, server.trim(), uri.trim());
            if (rows.isEmpty()) return "资源 " + uri + " 没有返回内容。";
            for (Map<String, Object> row : rows) {
                Object text = row.get("text");
                if (text != null && text.toString().length() > MAX_TEXT_CHARS) {
                    row.put("text", text.toString().substring(0, MAX_TEXT_CHARS));
                    row.put("truncated", true);
                }
            }
            return JSON.toJSONString(Map.of("server", server, "uri", uri, "contents", rows));
        } catch (Exception e) {
            log.warn("[MCP] 读取资源失败 uid={} server={} uri={}: {}", uid, server, uri, e.toString());
            return "读取 MCP 资源失败：" + e.getMessage();
        }
    }

    private String list(ToolContext ctx, String server, boolean templates) {
        String uid = uid(ctx);
        if (uid == null) return "无法定位当前用户，MCP 资源列表读取失败";
        try {
            List<Map<String, Object>> rows = mcp.listResources(uid,
                    server == null || server.isBlank() ? null : java.util.Set.of(server.trim()), templates);
            if (rows.isEmpty()) {
                return templates
                        ? "没有可用的 MCP 资源模板（未登记 MCP 服务，或这些服务不支持 resources）。"
                        : "没有可用的 MCP 资源（未登记 MCP 服务，或这些服务不支持 resources）。";
            }
            boolean truncated = rows.size() > MAX_ITEMS;
            List<Map<String, Object>> shown = truncated ? rows.subList(0, MAX_ITEMS) : rows;
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("count", rows.size());
            payload.put("shown", shown.size());
            payload.put("truncated", truncated);
            payload.put(templates ? "templates" : "resources", shown);
            return JSON.toJSONString(payload);
        } catch (Exception e) {
            log.warn("[MCP] 列资源失败 uid={} server={}: {}", uid, server, e.toString());
            return "读取 MCP 资源列表失败：" + e.getMessage();
        }
    }

    private static String uid(ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get(PresentArtifactTool.CTX_USER_ID);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }
}
