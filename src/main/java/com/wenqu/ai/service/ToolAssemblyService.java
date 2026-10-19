package com.wenqu.ai.service;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.PermissionPolicyResolver;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.model.Agent;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * 本轮工具装配判据的<b>唯一实现</b>（原先私有在 {@code ChatService} 里）。
 * <p>抽出来的理由不是好看：并列的第二套问答运行时（AgentScope 框架引擎）必须问得到<b>同一份</b>
 * 「这一族这一轮挂不挂」。留在上帝类里只有两种下场——框架侧另抄一份判据，或框架侧干脆不判。
 * 这两条都是仓库注释反复警告过的事故形状：游客可见性收窄与权限档位裁剪各写一遍时，
 * 管理员收紧一处、另一处照旧放行，后果是越权读库/越权执行，不是难看。
 * <p>「族 → {@code c_ai_agent} 的哪一列」这条映射仍然留在问答侧（service 层）而不是通用装配层
 * （{@code agent/tool}）：Agent 是业务实体，通用层不该认识它，否则下一族想加开关得先改通用层。
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
     * 本轮的工具步数上限（{@code agent.maxToolSteps} > 全局 {@code agent.maxToolSteps}；0=不限制）。
     * <p>与装配判据同源的理由相同：步数上限是「这一轮能干什么」的一部分。留在 {@code ChatService} 里
     * 就是并列的第二套运行时各数各的预算——框架侧漏接时表现为「同样的问题一边被截在一半」，
     * 而不是任何一条错误。
     * <p>分支逐层减半，且下限 3 防的是基础值很小或层数很深时 {@code >>} 直接算出 0——而 0 在本平台
     * 语义是<b>不限制</b>，递减递减出不限制是最坏的结果。基础值为 0（智能体显式「不限制」）时分支不跟随：
     * 那只有人工盯着的会话里才安全，分支按全局档减半处理，宁可少跑也不放飞。
     */
    public int maxToolSteps(Agent agent, int depth) {
        Integer v = agent == null ? null : agent.getMaxToolSteps();
        int global = configService.getInt("agent.maxToolSteps", 15);
        boolean unlimited = v != null && v == 0;
        int base = (v == null || v < 0) ? global : v;
        if (depth <= 0) return Math.max(0, base);
        if (unlimited) base = Math.max(1, global);
        return Math.max(3, base >> depth);
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
        return new ToolMountContext() {
            @Override
            public String userId() {
                return userId == null ? "" : userId;
            }

            @Override
            public boolean guestMode() {
                return guestMode;
            }

            @Override
            public boolean branchTurn() {
                return branchTurn;
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
            public Integer agentFlag(ToolFamily family) {
                return agentFlagFor(family, agent);
            }

            @Override
            public Set<String> agentSelection(ToolFamily family) {
                return agentSelectionFor(family, agent);
            }

            @Override
            public boolean approvalOff() {
                // 工具执行确认=off（禁用）：不给模型沙盒/MCP 这类有副作用工具。UI 与手册均按此语义承诺，
                // 此前仅 ask 分支有实现、off 静默等同 auto——按承诺补齐。联网搜索不受本档管辖（族上未标）
                return agent != null && "off".equalsIgnoreCase(agent.getToolApprovalMode());
            }

            @Override
            public boolean approvalAsk() {
                return agent != null && "ask".equalsIgnoreCase(agent.getToolApprovalMode());
            }

            @Override
            public ExecutionPolicy permissionPolicy() {
                if (turnPolicy != null) return turnPolicy;
                return resolvePermissionPolicy(agent, guestMode, degradationSink);
            }

            @Override
            public void degrade(String code, String message) {
                degradationSink.accept(code, message);
            }
        };
    }

    /** 档位解析（装配判据与执行守卫的唯一入口）。 */
    public ExecutionPolicy resolvePermissionPolicy(Agent agent, boolean guestMode,
                                                   BiConsumer<String, String> onClamp) {
        return permissionPolicyResolver.resolve(
                agent == null ? null : agent.getPermissionPreset(),
                agent == null ? null : agent.getToolApprovalMode(),
                guestMode,
                agent == null ? null : agent.getToolDeny(),
                onClamp);
    }

    /** 族 → 智能体级三态列（null = 本族没有智能体级开关，跟随全局） */
    public Integer agentFlagFor(ToolFamily family, Agent agent) {
        if (agent == null) return null;
        return switch (family) {
            case BUILTIN -> agent.getToolBuiltin();
            case KNOWLEDGE -> agent.getToolKnowledge();
            case WEBSEARCH -> agent.getToolWebsearch();
            case ARTIFACT -> agent.getToolArtifact();
            case SKILL -> agent.getToolSkill();
            case SANDBOX -> null;   // 沙盒暂无智能体级列（c_ai_agent 未建 toolSandbox，见 SandboxTools 类注释）
            // 智能体咨询（P5）同理：c_ai_agent 没有 toolSubagent 列，本族只受工具总闸与
            // 平台停用名单（tool.deny 按工具名 consultAgent）收口。要按人/按智能体收口的话，
            // 加列要走配置页 schema，别在这里塞"看起来能配"的 null。
            case SUBAGENT -> null;
            case TODO, MCP -> null;
        };
    }

    /** 族 → 智能体的「具体项」选择（null=未指定，本族全挂）；只有内置与 MCP 有这一层 */
    public Set<String> agentSelectionFor(ToolFamily family, Agent agent) {
        if (agent == null) return null;
        return switch (family) {
            case BUILTIN -> scopeOf(agent.getBuiltinTools());
            case MCP -> scopeOf(agent.getMcps());
            default -> null;
        };
    }

    /**
     * 「具体项范围」字段解析：null=跟随全局不筛具体项；空串=显式一个都不用；"a,b"=只用这些。
     */
    public static Set<String> scopeOf(String v) {
        if (v == null) return null;
        if (v.isBlank()) return java.util.Collections.emptySet();
        return Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
