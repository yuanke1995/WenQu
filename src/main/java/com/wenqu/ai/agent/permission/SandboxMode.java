package com.wenqu.ai.agent.permission;

// [保留] 问渠权限与沙箱模型 —— 框架引擎(AgentxPermissions/WenquAgentxTools)与共享服务仍依赖，勿删。

/**
 * 沙盒执行档位（P2 权限档位的第一根旋钮；声明顺序即能力递增，{@link #rank()} 依赖它）。
 * <p>
 * 为什么要有这一档：迁移前沙盒只有一种形态——「可写 {@code sandbox.virtualPathPrefix}」，
 * 而且是<b>会话级固定</b>的：同一容器一旦建起来，这一轮想收紧也收不了（provisioner 的 create 请求
 * 只有 sandbox_id/thread_id/uid/env/workdir 六个字段，没有任何档位入口）。于是「让智能体去调研一圈，
 * 别改任何东西」这种诉求只能靠提示词恳求模型，而提示词恳求不是约束。
 * <p>
 * 三档在问渠侧能兑现到什么程度，见 {@link SandboxEnforcement}——这一条必须说清楚，
 * 因为「档位」这个词容易让人以为容器内部真的被锁住了。
 *
 * @author yuanke
 */
public enum SandboxMode {

    /**
     * 只读：沙盒里只留「读文件 / 列目录」，命令执行与任何写入都不进模型的工具表。
     * <p>这一档刻意<b>不含 execute</b>：容器内没有任何机制能让一条 shell 命令只读
     * （provisioner 不按档位建容器），而「能跑命令」就等于「能写文件」。与其挂上去再逐条拒绝，
     * 不如让模型压根看不到这个能力——这正是 DSH 的 restrict 语义：被禁的能力与不存在不可区分。
     */
    READ_ONLY("read-only", "只读", false, false),
    /**
     * 工作区可写：写/编辑/交付三类工具的路径收束到会话工作区根（{@code sandbox.virtualPathPrefix}），
     * 命令可执行。这是平台的默认档，也是问渠今天实际形态的如实命名。
     */
    WORKSPACE_WRITE("workspace-write", "工作区可写", true, true),
    /**
     * 不受限：路径不收束。给「用户自己搭的智能体要在工作区外读写」留的口，
     * 由管理员经 {@code agent.permissionPreset.max} 决定平台上准不准用这一档。
     */
    DANGER_FULL_ACCESS("danger-full-access", "不受限", true, true);

    private final String code;
    private final String label;
    private final boolean writable;
    private final boolean executable;

    SandboxMode(String code, String label, boolean writable, boolean executable) {
        this.code = code;
        this.label = label;
        this.writable = writable;
        this.executable = executable;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 该档是否允许往沙盒里写（写文件/编辑/交付产物） */
    public boolean writable() {
        return writable;
    }

    /** 该档是否允许执行命令 */
    public boolean executable() {
        return executable;
    }

    /** 能力递增序（取档位上限时的比较依据；依赖枚举声明顺序，新增档位只能加在末尾） */
    public int rank() {
        return ordinal();
    }

    public static SandboxMode fromCode(String code) {
        if (code == null) return null;
        String c = code.strip();
        for (SandboxMode m : values()) {
            if (m.code.equalsIgnoreCase(c)) return m;
        }
        return null;
    }
}
