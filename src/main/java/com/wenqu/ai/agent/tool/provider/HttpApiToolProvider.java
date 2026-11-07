package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.httptool.HttpToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP 工具族（OpenAPI 导入的宿主 API 工具集）。
 * <p>
 * 与 {@link McpToolProvider} 同一形状的动态族：<b>没有静态清单</b>——工具由每个智能体自己的
 * {@code http_tools} 配置动态合入，{@link #descriptors()} 空、{@code holder()} null（启动核对跳过）。
 * 挂载判据与 MCP 的三处差异，都是安全边界上的收紧：
 * <ol>
 *   <li><b>游客一律不挂</b>：HTTP 工具带着发布者配置的鉴权凭据去调业务 API，游客能触发等于把
 *       业务调用权借给匿名外人（MCP 好歹还有发布者逐 server 勾选的名单，这里 v1 直接关死，
 *       等分享安全 P3/P4 拍板后再做名单式放行）；</li>
 *   <li><b>族开关默认由全局 {@code httpTool.enabled} 管</b>：三态 1=强制开（越过全局闸）、
 *       0=强制关、NULL=跟随全局——但无论如何「没有配置工具集」的智能体都不会挂出任何工具，
 *       所以默认开闸不改变任何存量智能体的行为；</li>
 *   <li><b>配置坏了要走轮内可见降级</b>（fail-loud 到 degrade），不让「JSON 改坏了」静默变成
 *       「智能体突然不会干活」。</li>
 * </ol>
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpApiToolProvider implements ToolFamilyProvider {

    private final HttpToolService httpToolService;

    @Override
    public ToolFamily family() {
        return ToolFamily.HTTP_API;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of();   // 动态接入，无静态清单（同 MCP）
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        if (mc == null || !mc.masterOn() || !mc.approvalPolicyAllows(ToolFamily.HTTP_API)) {
            return List.of();
        }
        if (mc.guestMode()) {
            return List.of();   // 游客不挂（见类注释第 1 条）
        }
        Integer flag = mc.agentFlag(ToolFamily.HTTP_API);
        if (flag != null && flag != 1) return List.of();   // 智能体显式关
        if (flag == null && !mc.gateOn("httpTool.enabled")) return List.of();   // 跟随全局闸

        HttpToolService.Mount mount;
        try {
            mount = httpToolService.mount(mc.agentId());
        } catch (Exception e) {
            log.warn("[HTTP-TOOL] 装配失败（跳过，不影响问答）agent={}: {}", mc.agentId(), e.getMessage());
            return List.of();
        }
        if (mount.problem() != null) {
            mc.degrade("agentHttpToolsInvalid", mount.problem());
            return List.of();
        }
        // 按名黑名单：动态工具没有静态描述符，档位裁剪不到它，与 MCP 同一粒度
        List<ToolCallback> out = new ArrayList<>(mount.tools());
        out.removeIf(cb -> mc.deniesTool(cb.getToolDefinition().name()));
        return out;
    }
}
