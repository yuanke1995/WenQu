package com.wenqu.ai.agent.permission;

// [保留] 问渠权限与沙箱模型 —— 框架引擎(AgentxPermissions/WenquAgentxTools)与共享服务仍依赖，勿删。

/**
 * 沙盒档位的<b>兑现程度</b>（这条记录的是「平台实际拦住了什么」，不是「档位名字听起来拦住了什么」）。
 * <p>
 * 为什么必须把它做成一个显式字段而不是注释：受限档位下「拦不住但照跑」就是静默透传，
 * 而静默透传是权限系统唯一不可接受的失效形态——用户以为选了只读，命令却在容器里写成了文件。
 * 拦不住就要<b>说出来</b>，说出来的地方有两处：本轮装配日志（排障时对着看）与工具卡片。
 * <p>
 * 问渠侧的真实边界：沙盒容器按会话懒建、跨轮复用，provisioner 的 create 入参里没有任何档位字段，
 * 所以「容器只读」这件事平台做不到。能做到的两件事是——
 * <ul>
 *   <li><b>能力面收窄</b>：档位不允许的工具根本不挂（模型看不见，无从调用）；</li>
 *   <li><b>路径收束</b>：写/编辑/交付三类工具的 path 参数由平台校验，越界即拒（这一条是真拦得住）。</li>
 * </ul>
 * 于是 {@link #PARTIAL} 只出现在一种组合上：档位允许执行命令、又要求收束路径。shell 命令里的写操作
 * 无法靠 path 参数收束（那是 {@code execute} 的整条命令行），这是本工程的既有形态，不是漏项。
 *
 * @author yuanke
 */
public enum SandboxEnforcement {

    /** 档位承诺的约束全部由平台兑现（只读档=写类能力压根不挂；不受限档=没有约束可兑现） */
    FULL("full", "全部兑现"),
    /**
     * 文件类工具的写入路径被收束，但 {@code execute} 里的写操作不受收束（容器内命令无法逐条解析）。
     * <p>出现在 {@link SandboxMode#WORKSPACE_WRITE} 且本轮挂了 execute 的组合上。
     */
    PARTIAL("partial", "路径收束（命令内写入不受束）");

    private final String code;
    private final String label;

    SandboxEnforcement(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
