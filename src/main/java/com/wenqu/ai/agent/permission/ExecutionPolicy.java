package com.wenqu.ai.agent.permission;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.sandbox.SandboxPaths;

/**
 * 本轮生效的执行策略（{@link PermissionPreset} 解开之后那份可判定的东西）。
 * <p>
 * 它同时服务三处，而且必须是同一份判定，否则「工具表里没挂」与「执行时被拦」会各按一种理解走：
 * <ul>
 *   <li>装配侧（{@code ToolMountContext#policyAllows}）：档位不允许的工具<b>不进模型的工具表</b>；</li>
 *   <li>守卫侧（{@code PermissionGuard}）：没挂上却被调到了（旁路、或将来某条裸调路径）一律拒；</li>
 *   <li>沙盒工具侧（{@code SandboxTools}）：允许写、但要收束到工作区根的那些，按 path 参数逐条判。</li>
 * </ul>
 * <p>
 * 刻意不是 record 直出 JSON 的那种对象：本工程用 fastjson 序列化，它不认 record（会得到 {@code {}}），
 * 对外的档位名册一律由 {@code PermissionPolicyResolver#catalog()} 手工组装成 Map。
 *
 * @param preset       生效档位（钳制之后的那个，不是智能体原本想要的）
 * @param sandboxMode  沙盒执行档
 * @param approvalMode 生效的工具执行确认档位（auto/ask/off；null=auto）——智能体显式设置优先，
 *                     没设过才用预设的建议值
 * @param enforcement  本档约束的兑现程度（受限却拦不住时必须说出来，见 {@link SandboxEnforcement}）
 * @param writableRoot 沙盒内可写根（workspace-write 档的收束目标；空=平台没配，按不放开处理）
 * @author yuanke
 */
public record ExecutionPolicy(
        PermissionPreset preset,
        SandboxMode sandboxMode,
        String approvalMode,
        SandboxEnforcement enforcement,
        String writableRoot,
        /**
         * 本轮按名禁用的工具（跨族，来自智能体 {@code tool_deny}）。
         * <p>为什么族级开关之外还要这一层：族级三态只能整族开/关，「具体项白名单」只能反过来逐个勾——
         * 想「跟随全局挂上来的那些里去掉某一个」今天做不到，只能切到指定模式手抄一份名单，
         * 而那份名单在平台新增工具时不会自动跟上。deny 是唯一能表达"其余照旧"的形状。
         * <p>它也是 MCP 那族唯一的粒度：动态工具没有静态描述符，只能按名字判。
         */
        java.util.Set<String> deny) {

    /**
     * 由档位与智能体自己的审批设置算出本轮策略。
     *
     * @param preset            档位（null 按常规档，调用方的「没设过」与「设成常规」在这里同解）
     * @param agentApprovalMode 智能体的 {@code tool_approval_mode}（空白=未设置，落到档位的建议值）
     * @param writableRoot      沙盒可写根（{@code sandbox.virtualPathPrefix}）
     * @param denyCsv           按名禁用的工具（逗号分隔；空白项忽略，按声明名逐字匹配）
     */
    public static ExecutionPolicy of(PermissionPreset preset, String agentApprovalMode,
                                     String writableRoot, String denyCsv) {
        PermissionPreset p = preset == null ? PermissionPreset.STANDARD : preset;
        SandboxMode mode = p.sandboxMode();
        String approval = agentApprovalMode == null || agentApprovalMode.isBlank()
                ? p.approvalDefault() : agentApprovalMode.trim();
        // 命令可执行 + 要求路径收束 = 命令内部的写入拦不住，兑现程度只能是 partial；
        // 只读档不挂 execute（能力面就是约束），不受限档没有约束要兑现，两者都是 full。
        SandboxEnforcement enforcement = mode == SandboxMode.WORKSPACE_WRITE && mode.executable()
                ? SandboxEnforcement.PARTIAL : SandboxEnforcement.FULL;
        return new ExecutionPolicy(p, mode, approval, enforcement, writableRoot, parseDeny(denyCsv));
    }

    /** 逗号串 → 不可变名单（用 LinkedHashSet 保序，日志里能逐字对账） */
    private static java.util.Set<String> parseDeny(String csv) {
        if (csv == null || csv.isBlank()) return java.util.Set.of();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String t = part == null ? "" : part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return java.util.Set.copyOf(out);
    }

    /** 这个名字是否被本轮点名禁用（MCP 动态工具没有描述符，只能走这条） */
    public boolean denies(String toolName) {
        return toolName != null && deny.contains(toolName);
    }

    /**
     * 这个工具本轮在模型的工具表里出不出现。
     * <p>先过按名黑名单（跨族，管理员/成员点名禁的那个），再过档位：档位只对沙盒族做裁剪，
     * 判据用 {@link SideEffect}（工具会不会动到外部世界）而不是工具名列表——名单会在下一次加沙盒工具时
     * 忘记更新，等级是描述符上必须填的字段。
     * <p>档位对其余族不收束：外部 MCP 工具在只读档下照样挂载但要逐项确认——档位在它身上的表达是审批，
     * 因为平台无从知道一个外部工具的哪一次调用会写什么。要精确到"只禁其中某个外部工具"就走 deny。
     */
    public boolean allowsTool(ToolDescriptor d) {
        if (d == null) return true;
        if (denies(d.name())) return false;
        if (d.family() != ToolFamily.SANDBOX) return true;
        if (sandboxMode == SandboxMode.READ_ONLY) {
            return d.sideEffect() == SideEffect.READ;
        }
        return true;
    }

    /** 该工具本轮是否属于「档位不允许、不该存在」的那一类（守卫拒的正是装配已经不收的那些） */
    public boolean forbids(ToolDescriptor d) {
        return !allowsTool(d);
    }

    /** 是否要求写入收束到工作区根（不受限档不要求；只读档压根不给写） */
    public boolean confinesWrites() {
        return sandboxMode == SandboxMode.WORKSPACE_WRITE;
    }

    /**
     * 这次写入是否允许。
     * <p>可写根为空时按拒绝处理：这是「受限策略下拿不到约束依据」，此时放行就等于静默透传。
     */
    public boolean permitsWritePath(String path) {
        if (!sandboxMode.writable()) return false;
        if (sandboxMode == SandboxMode.DANGER_FULL_ACCESS) return true;
        if (writableRoot == null || writableRoot.isBlank()) return false;
        return SandboxPaths.isSameOrChild(path, writableRoot);
    }

    /** 一句话现状（本轮装配日志用；档位/沙盒/审批/兑现程度/黑名单一行看全） */
    public String describe() {
        return "档位=" + preset.code() + "(" + preset.label() + ") 沙盒=" + sandboxMode.code()
                + " 审批=" + (approvalMode == null ? "auto" : approvalMode)
                + " 约束=" + enforcement.code()
                + " 可写根=" + (writableRoot == null || writableRoot.isBlank() ? "(未配置)" : writableRoot)
                + (deny.isEmpty() ? "" : " 禁用=" + deny);
    }
}
