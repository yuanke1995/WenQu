package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.McpClientService;
import com.wenqu.ai.service.McpResourceTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 资源族：把外部 MCP Server 的 resources / resource templates 暴露给模型。
 *
 * <p>与 {@link McpToolProvider} 分属两族而不是塞进 MCP 族：MCP 族的工具名由外部 server 自定、
 * 无静态清单；资源面这三把是平台自己的固定工具，有静态描述符、能被档位与开关精确裁剪。
 * 合成一族就会出现「动态工具 + 静态工具」两种装配口径挤在同一个 {@code callbacks()} 里。
 * <p>不新增平台开关：本轮没有登记任何 MCP 服务时整族自动不挂（并给一条可见降级），
 * 要精确关某一族里的某把就用停用名单。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class McpResourceProvider implements ToolFamilyProvider {

    private final McpResourceTools tools;
    private final McpClientService mcpClientService;

    public McpResourceProvider(McpResourceTools tools, McpClientService mcpClientService) {
        this.tools = tools;
        this.mcpClientService = mcpClientService;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.MCP_RESOURCE;
    }

    @Override
    public Object holder() {
        return tools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                new ToolDescriptor("mcpListResources", ToolFamily.MCP_RESOURCE,
                        "MCP 资源清单", "列出 MCP 资源",
                        "列出你登记的 MCP 服务里可读的文档/数据集等资源",
                        SideEffect.EXTERNAL, null, false, false),
                new ToolDescriptor("mcpListResourceTemplates", ToolFamily.MCP_RESOURCE,
                        "MCP 资源模板", "列出 MCP 资源模板",
                        "列出 MCP 服务里带参数的资源 URI 模板",
                        SideEffect.EXTERNAL, null, false, false),
                new ToolDescriptor("mcpReadResource", ToolFamily.MCP_RESOURCE,
                        "读取 MCP 资源", "读取 MCP 资源",
                        "按 server + uri 读取一个 MCP 资源的正文",
                        SideEffect.EXTERNAL, null, false, false));
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        if (mc == null || !mc.masterOn()) return List.of();
        if (mc.guestMode()) return List.of();   // 资源属个人资产，游客不挂
        try {
            if (mcpClientService.serverNames(mc.userId()).isEmpty()) {
                // 没有登记任何 MCP 服务时挂上去只会让模型反复调空；如实说一句为什么没挂
                mc.degrade("mcpResourceNoServer", "你还没有登记任何 MCP 服务，MCP 资源工具本轮未启用");
                return List.of();
            }
        } catch (Exception e) {
            log.debug("[MCP-RESOURCE] 核对已登记服务失败（照常挂载）: {}", e.getMessage());
        }
        return mountFiltered(mc, d -> true);
    }
}
