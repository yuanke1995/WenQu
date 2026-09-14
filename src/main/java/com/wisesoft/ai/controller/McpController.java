package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.McpClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 外部工具管理接口（设置页 MCP 面板）
 *
 * <p>只做「看得见 + 能重连」：连接状态、工具数、手动重连。server 的增删改仍走
 * 配置接口（mcp.enabled / mcp.servers），避免两处维护同一份配置。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/mcp")
@RequiredArgsConstructor
@Tag(name = "MCP 外部工具", description = "MCP server 连接状态与重连")
public class McpController {

    private final McpClientService mcpClientService;
    private final ConfigService configService;

    @Operation(summary = "MCP 连接状态", description = "总开关 + 每个 server 的地址/类型/连接状态/可用工具数（连接失败带原因）")
    @GetMapping("/status")
    public ResultJson status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", configService.getBoolean("mcp.enabled"));
        data.put("servers", mcpClientService.serverStatuses());
        return ResultJson.ok(data);
    }

    @Operation(summary = "重连 MCP", description = "重建全部连接（配置改动或失败重试），返回最新状态")
    @PostMapping("/reload")
    public ResultJson reload() {
        mcpClientService.reload();
        return status();
    }

    @Operation(summary = "测试连接", description = "临时连接一个 MCP 服务（不落配置），返回是否可达与工具清单；用于\"先测再存\"")
    @PostMapping("/probe")
    public ResultJson probe(@RequestBody Map<String, String> body) {
        return ResultJson.ok(mcpClientService.probe(body.get("url"), body.get("type")));
    }
}
