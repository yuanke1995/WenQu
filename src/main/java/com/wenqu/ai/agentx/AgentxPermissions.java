package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.PermissionPreset;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;

/**
 * 问渠权限档位 → AgentScope 权限系统（方案 §4 P1「把现有规则翻译进框架权限配置」这一条）。
 * <p>翻的是<b>档位语义</b>，不是再造一层判定：档位的解析仍只有
 * {@code PermissionPolicyResolver}（经 {@code ToolAssemblyService.resolvePermissionPolicy}）一处，
 * 它同时喂给问答侧的执行守卫与框架侧的权限系统，两边读同一份 {@link ExecutionPolicy}。
 * <p>三档映射（刻意不映射成 BYPASS）：
 * <ul>
 *   <li>{@code research 只读调研} → {@code EXPLORE}：只读面，写操作与有副作用工具本来就不该出现；</li>
 *   <li>{@code standard 常规} → {@code DEFAULT}：按工具自报的性质决定要不要问人；</li>
 *   <li>{@code autonomous 全自主} → {@code DONT_ASK}：不再问人，但 {@code checkPermissions} <b>照跑</b>。</li>
 * </ul>
 * 之所以不用 {@code BYPASS}：那是连检查一起跳过，等于把「管理员已经把某一档禁掉的能力」在这个
 * 运行时里放回给模型——档位收紧必须与「该能力不存在」不可区分，这条口径与问答侧一致。
 */
public final class AgentxPermissions {

    private AgentxPermissions() {
    }

    /** 档位 → 框架权限模式（null 策略按最保守的只读档）。 */
    public static PermissionMode modeOf(ExecutionPolicy policy) {
        if (policy == null || policy.preset() == null) return PermissionMode.EXPLORE;
        return switch (policy.preset()) {
            case RESEARCH -> PermissionMode.EXPLORE;
            case STANDARD -> PermissionMode.DEFAULT;
            case AUTONOMOUS -> PermissionMode.DONT_ASK;
        };
    }

    /**
     * 按名黑名单落成框架的 deny 规则（MCP 那族没有静态描述符，只能按名字判，这条与问答侧同口径）。
     * <p>{@code ask} 规则不在这里生成：要不要问人取决于「这个工具本轮挂没挂、登记过没有」，
     * 那份判据在 {@code ToolRegistry#requiresApproval} 上，由工具自己带进
     * {@link SpringAiToolAdapter#checkPermissions}，避免这里再抄一份登记表面。
     */
    public static PermissionContextState contextOf(ExecutionPolicy policy) {
        PermissionContextState.Builder builder = PermissionContextState.builder().mode(modeOf(policy));
        if (policy != null && policy.deny() != null) {
            for (String name : policy.deny()) {
                if (name == null || name.isBlank()) continue;
                builder.addDenyRule(name, new PermissionRule(name, null, PermissionBehavior.DENY, "wenqu-tier"));
            }
        }
        return builder.build();
    }
}
