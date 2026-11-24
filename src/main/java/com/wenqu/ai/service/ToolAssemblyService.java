package com.wenqu.ai.service;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.PermissionPolicyResolver;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.model.Agent;
import org.springframework.stereotype.Service;

import java.util.function.BiConsumer;

/**
 * 本轮工具装配判据的<b>唯一实现</b>（原先私有在 {@code ChatService} 里）。
 * <p>抽出来的理由不是好看：并列的第二套问答运行时（AgentScope 框架引擎）必须问得到<b>同一份</b>
 * 「这一族这一轮挂不挂」。留在上帝类里只有两种下场——框架侧另抄一份判据，或框架侧干脆不判。
 * 这两条都是仓库注释反复警告过的事故形状：游客可见性收窄与权限档位裁剪各写一遍时，
 * 管理员收紧一处、另一处照旧放行，后果是越权读库/越权执行，不是难看。
 * <p>「族 → {@code c_ai_agent} 的哪一列」的映射在 {@link AgentToolSelectionAdapter}（业务适配器）；
 * 本类与通用装配层都不认识 Agent 字段，下一族加开关只需改适配器。
 */
@Service
public class ToolAssemblyService {

    private final ConfigService configService;
    private final PermissionPolicyResolver permissionPolicyResolver;

    public ToolAssemblyService(ConfigService configService, PermissionPolicyResolver permissionPolicyResolver) {
        this.configService = configService;
        this.permissionPolicyResolver = permissionPolicyResolver;
    }

    /**
     * 本轮工具步数上限（助手自己设的优先；平台默认不限制，0=不限制）。
     * <p>与装配判据同源的理由相同：步数上限是「这一轮能干什么」的一部分。留在 {@code ChatService} 里
     * 就是并列的第二套运行时各数各的预算——框架侧漏接时表现为「同样的问题一边被截在一半」，
     * 而不是任何一条错误。
     * <p>分支逐层减半，且下限 3 防的是基础值很小或层数很深时 {@code >>} 直接算出 0——而 0 在本平台
     * 语义是<b>不限制</b>，递减递减出不限制是最坏的结果。智能体单独填 0（显式「不限制」）时分支不跟随：
     * 那只有人工盯着的会话里才安全，分支退回全局档减半，宁可少跑也不放飞。
     * <p>但全局本身也是 0 时没有「全局档」可退——那 0 是平台口径的不限制，必须原样传给分支。
     */
    public int maxToolSteps(Agent agent, int depth) {
        Integer v = agent == null ? null : agent.getMaxToolSteps();
        int global = ConfigDefaults.AGENT_MAX_TOOL_STEPS;
        int base = (v == null || v < 0) ? global : v;
        if (depth <= 0) return Math.max(0, base);
        if (v != null && v == 0 && global > 0) base = global;
        // 分支不再按深度做隐式右移：那是把「委派」当「降级」，真正干活的子助手先被砍。
        // 每个子轮用智能体自己的预算；需要平台级保护时用 subagentMaxToolSteps 显式封顶。
        int subCap = ConfigDefaults.AGENT_SUBAGENT_MAX_TOOL_STEPS;
        if (subCap <= 0) return Math.max(0, base);
        return base <= 0 ? subCap : Math.min(base, subCap);
    }

    /**
     * 本轮的工具装配判据。
     *
     * @param turnPolicy         本轮已解析的权限档位（问答侧传 {@code st.tools.permissionPolicy}，与守卫读同一份）；
     *                           null = 现算（没有本轮状态对象的调用路径，如提示词侧与框架引擎）
     * @param branchTurn         本轮是否为子智能体分支：分支里「要有人在场」的工具不进工具表
     * @param degradationSink    本轮可见降级的出口（fail-loud：要说清「为什么这个能力没生效」）
     */
    public ToolMountContext mountContext(Agent agent, String userId, boolean guestMode,
                                         boolean branchTurn, BiConsumer<String, String> degradationSink,
                                         ExecutionPolicy turnPolicy) {
        return mountContext(agent, userId, guestMode, branchTurn, 0, degradationSink, turnPolicy, null);
    }

    /**
     * 带工作流嵌套深度的重载（防环判据随 cmd 下传）：旧签名保留——非嵌套路径与
     * 提示词侧的判据逐字不变。
     */
    public ToolMountContext mountContext(Agent agent, String userId, boolean guestMode,
                                         boolean branchTurn, int wfDepth,
                                         BiConsumer<String, String> degradationSink,
                                         ExecutionPolicy turnPolicy) {
        return mountContext(agent, userId, guestMode, branchTurn, wfDepth, degradationSink, turnPolicy, null);
    }

    /**
     * 带游客 MCP 授权名单的重载（分享链路用）：名单非空时游客轮按它挂 MCP 并预批。
     * 不带名单的旧签名保留——非游客路径与提示词侧的判据逐字不变。
     */
    public ToolMountContext mountContext(Agent agent, String userId, boolean guestMode,
                                         boolean branchTurn, BiConsumer<String, String> degradationSink,
                                         ExecutionPolicy turnPolicy, java.util.Set<String> guestMcpServers) {
        return mountContext(agent, userId, guestMode, branchTurn, 0, degradationSink, turnPolicy, guestMcpServers);
    }

    /** 全量重载：wfDepth 为本轮工作流嵌套深度（0=顶层；工作流工具族的防环判据）。 */
    public ToolMountContext mountContext(Agent agent, String userId, boolean guestMode,
                                         boolean branchTurn, int wfDepth,
                                         BiConsumer<String, String> degradationSink,
                                         ExecutionPolicy turnPolicy, java.util.Set<String> guestMcpServers) {
        return mountContext(agent, userId, guestMode, branchTurn, wfDepth, degradationSink,
                turnPolicy, guestMcpServers, true);
    }

    /**
     * 带「本轮有没有可应答的人」的全量重载：装配期据此决定需要确认的能力挂不挂。
     * <p>与 {@code SpringAiToolAdapter} 的 {@code approvalCanAsk} 同一份判据，避免
     * 「装配说能问、执行说没人」或反过来——两条口径分家时，前者会白挂一个调不动的工具。
     */
    public ToolMountContext mountContext(Agent agent, String userId, boolean guestMode,
                                         boolean branchTurn, int wfDepth,
                                         BiConsumer<String, String> degradationSink,
                                         ExecutionPolicy turnPolicy, java.util.Set<String> guestMcpServers,
                                         boolean approvalCapable) {
        java.util.Set<String> guestAllow = guestMode && guestMcpServers != null
                ? guestMcpServers : java.util.Set.of();
        return new ToolMountContext() {
            @Override
            public String userId() {
                return userId == null ? "" : userId;
            }

            @Override
            public String agentId() {
                return agent == null || agent.getId() == null ? "" : agent.getId();
            }

            @Override
            public boolean guestMode() {
                return guestMode;
            }

            @Override
            public java.util.Set<String> guestMcpServers() {
                return guestAllow;
            }

            @Override
            public boolean branchTurn() {
                return branchTurn;
            }

            @Override
            public int turnWfDepth() {
                return wfDepth;
            }

            @Override
            public boolean gateOn(String gateKey) {
                return configService.getBoolean(gateKey);
            }

            @Override
            public boolean masterOn() {
                return configService.getBoolean("tool.enabled");
            }

            @Override
            public com.wenqu.ai.agent.tool.ToolSelection selection() {
                return AgentToolSelectionAdapter.from(agent);
            }

            @Override
            public boolean approvalOff() {
                // 工具执行确认=off（禁用）：不给模型沙盒/MCP 这类有副作用工具，与 UI/手册的语义承诺一致。
                // 联网搜索不受本档管辖（族上未标）。读本轮生效策略而不是智能体字面值：
                // 分支继承、团队根封顶都会改变实际审批档，装配与执行必须看同一份。
                return "off".equalsIgnoreCase(effectiveApprovalMode());
            }

            @Override
            public boolean approvalAsk() {
                return "ask".equalsIgnoreCase(effectiveApprovalMode());
            }

            @Override
            public boolean approvalCapable() {
                return approvalCapable;
            }

            private String effectiveApprovalMode() {
                if (turnPolicy != null && turnPolicy.approvalMode() != null
                        && !turnPolicy.approvalMode().isBlank()) {
                    return turnPolicy.approvalMode().trim();
                }
                return agent == null || agent.getToolApprovalMode() == null
                        ? "auto" : agent.getToolApprovalMode().trim();
            }

            @Override
            public ExecutionPolicy permissionPolicy() {
                if (turnPolicy != null) return turnPolicy;
                return resolvePermissionPolicy(agent, guestMode);
            }

            @Override
            public void degrade(String code, String message) {
                degradationSink.accept(code, message);
            }
        };
    }

    /** 档位解析（装配判据与执行守卫的唯一入口）。 */
    public ExecutionPolicy resolvePermissionPolicy(Agent agent, boolean guestMode) {
        return resolvePermissionPolicy(agent, null, guestMode);
    }

    /**
     * 带会话级覆盖的档位解析：会话选择器切的那一档优先于智能体。
     *
     * @param sessionPresetCode 会话级档位码（null/空=没选过，跟随智能体）
     */
    public ExecutionPolicy resolvePermissionPolicy(Agent agent, String sessionPresetCode, boolean guestMode) {
        return resolvePermissionPolicy(agent, sessionPresetCode, null, guestMode);
    }

    /** 带父轮审批档位继承的版本（分支轮用）：继承值仅在智能体未显式设置时生效。 */
    public ExecutionPolicy resolvePermissionPolicy(Agent agent, String sessionPresetCode,
                                                   String inheritedApprovalMode, boolean guestMode) {
        return permissionPolicyResolver.resolve(
                sessionPresetCode,
                agent == null ? null : agent.getPermissionPreset(),
                agent == null ? null : agent.getToolApprovalMode(),
                inheritedApprovalMode,
                guestMode,
                agent == null ? null : agent.getToolDeny());
    }

    /** 族 → 智能体级三态列（null = 本族没有智能体级开关，跟随全局） */
    // 已迁至 AgentToolSelectionAdapter：本类不再认识 Agent 的列。

}
