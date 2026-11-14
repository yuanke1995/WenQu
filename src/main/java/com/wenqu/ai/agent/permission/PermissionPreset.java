package com.wenqu.ai.agent.permission;

// [保留] 问渠权限与沙箱模型 —— 框架引擎(AgentxPermissions/WenquAgentxTools)与共享服务仍依赖，勿删。

/**
 * 权限档位预设：把「沙盒能干什么」和「要不要逐项问用户」这两根旋钮捆成一句人话。
 * <p>
 * 为什么捆成预设而不是两个独立下拉：两根旋钮有 3×3 种组合，其中大部分是自相矛盾的
 * （「只读沙盒 + 每个副作用工具都要确认」里那条审批永远不会触发；「不受限 + 逐项确认」把
 * 收束的责任推给一次点击）。对标 DSH 的做法也是 preset 优先、单旋钮只做例外覆盖。
 * <p>
 * 审批这根旋钮在问渠已有既有载体：{@code c_ai_agent.tool_approval_mode}（auto/ask/off 三态），
 * 落库、界面、手册、审批闸都在它上面。本枚举<b>不另起一套审批词表</b>，只声明
 * {@link #approvalDefault()}——智能体自己显式设过就以它为准（三态语义与全仓一致的「显式覆盖 &gt; 继承」），
 * 没设过才用预设的建议值。常规档刻意返回 null（= 完全跟随现状），这一条是「默认档位等于当前行为」
 * 承诺的落点：把档位这套机制整个删掉，常规档每一轮的挂载集合与审批行为都不该有一个字符的变化。
 *
 * @author yuanke
 */
public enum PermissionPreset {

    /** 只读调研：沙盒只留读文件与列目录，命令执行、写入、交付产物、写长期记忆与外部工具（联网搜索、MCP）都不进工具表 */
    RESEARCH("research", "仅可查看", SandboxMode.READ_ONLY, "ask",
            "可读取文件与目录，不能修改或执行命令"),
    /** 常规：平台默认档，沙盒写操作收束在沙盒目录内；默认逐次确认，智能体显式设 auto 才免确认 */
    STANDARD("standard", "工作区内修改", SandboxMode.WORKSPACE_WRITE, "ask",
            "可在工作区内读写与执行命令，默认逐次确认"),
    /** 全自主：写入位置不再由平台收束（沙盒层的可写根一并放开）、命令不逐项确认 */
    AUTONOMOUS("autonomous", "完全权限", SandboxMode.DANGER_FULL_ACCESS, "auto",
            "可读写工作区外的路径，命令不逐项确认");

    private final String code;
    private final String label;
    private final SandboxMode sandboxMode;
    /** 审批档位的建议值（auto/ask/off；null=跟随智能体的 tool_approval_mode） */
    private final String approvalDefault;
    private final String summary;

    PermissionPreset(String code, String label, SandboxMode sandboxMode, String approvalDefault, String summary) {
        this.code = code;
        this.label = label;
        this.sandboxMode = sandboxMode;
        this.approvalDefault = approvalDefault;
        this.summary = summary;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public SandboxMode sandboxMode() {
        return sandboxMode;
    }

    /** 预设建议的审批档位；null = 不动智能体自己的「工具执行确认」设置 */
    public String approvalDefault() {
        return approvalDefault;
    }

    /** 一句话说明（配置页与档位名册端点共用，避免前端再抄一份） */
    public String summary() {
        return summary;
    }

    /** 能力递增序（依赖枚举声明顺序，新增档位只能加在末尾） */
    public int rank() {
        return ordinal();
    }

    /** 严格解析：认不出返回 null，由调用方决定回落哪一档（绝不默认到最高档） */
    public static PermissionPreset fromCode(String code) {
        if (code == null || code.isBlank()) return null;
        String t = code.trim();
        for (PermissionPreset p : values()) {
            if (p.code.equalsIgnoreCase(t)) return p;
        }
        return null;
    }
}
