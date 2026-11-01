package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.ResourceRefs;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.McpClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 外部工具族（工具生态层）。
 * <p>
 * 这一族与前六族形状不同，迁移时三处得区别对待，别照着别的族抄：
 * <ol>
 *   <li><b>没有静态清单</b>：工具由每个用户自己登记的 server 动态合入，所以
 *       {@link #descriptors()} 是空、{@link #holder()} 是 null（启动核对跳过）。
 *       审批属性因此不是描述符上的字段，而由 {@code ToolRegistry.requiresApproval} 对
 *       「未登记的工具名」一律返回 true 来兜（fail-closed）。</li>
 *   <li><b>它要往本轮写两样东西</b>：具体项引用没命中时的可见降级（走
 *       {@link ToolMountContext#degrade}），以及「本轮哪些工具是 MCP 来的」——后者不在这里写，
 *       由装配循环按族收集（provider 不该反向摸问答侧的状态对象）。</li>
 *   <li><b>连接失败必须跳过而不是抛出</b>：某个用户登记的 server 挂了不能影响这一轮问答，
 *       与迁移前那段 try/catch 同口径。</li>
 * </ol>
 *
 * @author yuanke
 */
@Slf4j
@Component
public class McpToolProvider implements ToolFamilyProvider {

    private final McpClientService mcpClientService;

    public McpToolProvider(McpClientService mcpClientService) {
        this.mcpClientService = mcpClientService;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.MCP;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of();   // 动态接入，无静态清单
    }

    /**
     * 连的是**当前用户**登记的 server（连接池按 uid 分池）——游客轮的 uid 就是发布者
     * （问答侧把 userId 装成发布者），所以「发布者授权名单」天然解析得到发布者名下的连接。
     * 匿名访客默认仍一律不给；唯一例外是发布者在分享配置里按 server 粒度勾选的
     * {@code guestMcpServers} 名单——名单是发布者对自己个人资产的显式亮出，勾了才挂、
     * 挂了按 {@code guestPreapproved} 免逐次审批（分享页没有审批卡通道）。
     */
    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        if (mc == null || !mc.masterOn()
                || !mc.approvalPolicyAllows(ToolFamily.MCP)) {
            return List.of();
        }
        // 游客白名单：未授权（空名单）整族照旧不挂；授权后与智能体自己的具体项选择取交集——
        // 两道独立的闸，交集为空=没有任何 server 同时满足两者，等于不挂
        java.util.Set<String> guestAllow = mc.guestMode() ? mc.guestMcpServers() : null;
        if (guestAllow != null && guestAllow.isEmpty()) {
            log.info("[MCP] 游客轮未授权名单，MCP 不挂 uid={}", mc.userId());
            return List.of();
        }
        Integer flag = mc.agentFlag(ToolFamily.MCP);
        if (flag != null && flag != 1) return List.of();     // 智能体显式关了 MCP

        // 具体项筛选：agent.mcps 为 null → 用该用户全部已启用 server；否则只取选中的那几个
        java.util.Set<String> refs = mc.agentSelection(ToolFamily.MCP);
        ResourceRefs.Scope scope = ResourceRefs.resolve(refs, mc.userId());
        java.util.Set<String> onlyServers = scope.names();
        if (guestAllow != null) {
            java.util.Set<String> narrowed = new java.util.HashSet<>(guestAllow);
            if (onlyServers != null) narrowed.retainAll(onlyServers);
            if (narrowed.isEmpty()) {
                log.info("[MCP] 游客授权名单与智能体具体项选择交集为空，MCP 不挂 uid={} 名单={} agent选择={}",
                        mc.userId(), guestAllow, onlyServers);
                return List.of();
            }
            onlyServers = narrowed;
            log.info("[MCP] 游客轮按授权名单挂载 uid={} servers={}", mc.userId(), onlyServers);
        }
        // 别人的服务不顶替、自己没登记的要说出来，否则「智能体挂了这个外部工具」会静默变成
        // 「什么都没挂」。空集合=显式不使用，不算缺失，所以判据看引用集合而不是解析后的名字集合。
        if (refs != null && !refs.isEmpty()) {
            if (!scope.notMine().isEmpty()) {
                mc.degrade("agentMcpNotMine", "智能体指定的 MCP 服务 " + String.join("、", scope.notMine())
                        + " 属于其他用户（MCP 是个人资产），对你不可用");
            }
            List<String> missing = new ArrayList<>();
            try {
                java.util.Set<String> have = mcpClientService.serverNames(mc.userId());
                for (String n : (onlyServers == null ? java.util.Set.<String>of() : onlyServers)) {
                    if (!have.contains(n)) missing.add(n);
                }
            } catch (Exception e) {
                log.warn("[MCP] 核对用户已登记服务失败（跳过核对，不影响挂载）: {}", e.getMessage());
            }
            if (!missing.isEmpty()) {
                mc.degrade("agentMcpUnavailable", "智能体指定的 MCP 服务 " + String.join("、", missing)
                        + " 你未登记或已停用（MCP 是个人资产），本轮未接入");
                log.warn("[MCP] uid={} 指定的服务未命中: {}", mc.userId(), missing);
            }
        }
        try {
            List<ToolCallback> out = new ArrayList<>(
                    mcpClientService.toolCallbacks(mc.userId(), onlyServers, mc.agentId()));
            // 按名黑名单：本族没有静态描述符，档位裁剪不到它，唯一的粒度就是这里按名字逐字剔
            int before = out.size();
            out.removeIf(cb -> mc.deniesTool(cb.getToolDefinition().name()));
            if (out.size() < before) {
                log.info("[TOOL] MCP 按本轮黑名单剔除 {} 个外部工具（uid={}）", before - out.size(), mc.userId());
            }
            return out;
        } catch (Exception e) {
            log.warn("[MCP] 加载外部工具失败（跳过，不影响问答）: {}", e.getMessage());
            return List.of();
        }
    }
}
