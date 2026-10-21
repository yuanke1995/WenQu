package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.SandboxTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 沙盒执行族。
 * <p>
 * 本族只提供 {@code run_code}（旧六把工具里的读写与列目录已由框架自带工具承担），它 requiresApproval=true：
 * 装配处把整族回调一律计入有副作用名单。{@code SideEffect} 的 READ/MUTATE 之分从 P2 起<b>真的参与裁剪</b>：
 * 只读档（read-only）下这一把不进模型的工具表——不是「点了被拒」，是看不见。
 * <p>
 * 只读档必须整族摘掉：容器没有按档位收束命令的机制（provisioner 建容器时不接档位），
 * 「能跑代码」就等于「能写文件」，留着它再逐条拒绝就是静默透传。
 */
@Component
public class SandboxToolProvider implements ToolFamilyProvider {

    private final SandboxTools sandboxTools;

    public SandboxToolProvider(SandboxTools sandboxTools) {
        this.sandboxTools = sandboxTools;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.SANDBOX;
    }

    @Override
    public Object holder() {
        return sandboxTools;
    }

@Override
public List<ToolDescriptor> descriptors() {
        // 全框架化 P1：execute / read_file / write_file / edit_file / ls 五把已退役，
        // 改由框架 FilesystemTool + ShellExecuteTool 提供（工具名相同，模型侧无感）；
        // deliver_artifact 已退役（P1-c），改由框架的 ArtifactDeliveryTool 提供——
        // 框架注释明确「配了 ArtifactDeliveryTarget 才注册该工具」，注册点见
        // AgentscopeChatEngine#buildAgent 的 .artifactDeliveryTarget(...)。
        // 留着会与框架的同名工具同时挂在 toolkit 上，模型面对重名工具会二选一——
        // 那既不是「用框架」，也不是「用问渠」，是最坏的一种中间态。
        // 本族只剩 run_code：框架确无对应工具（core/tool/coding 只到 ShellCommandTool）。
        return List.of(
                new ToolDescriptor("run_code", ToolFamily.SANDBOX,
                        "沙盒脚本（一次做完一批）", "运行脚本",
                        "在云端沙盒里跑一段 Python：脚本可以自己循环取资料、批量处理，只把结果带回来",
                        SideEffect.MUTATE, "tool.runcode.enabled", false, true, null));
    }

    /**
     * 本族自己的挂不挂判据；{@code run_code} 在此多两道闸。
     * <p>第一道是它自己的开关 {@code tool.runcode.enabled}（默认关）：这是个能力形态变化而不是 bug 修复，
     * 上了就该由管理员决定谁先吃螃蟹。
     * <p>第二道是<b>「工具执行确认=每次问我」时不给</b>。理由不是麻烦，是语义：脚本里的子调用要逐个过
     * 审批闸，一次扇出 20 次读取就是 20 张卡，而人点第一张卡时并不能预见后面 19 次要读什么——
     * "逐项确认"这个承诺在脚本形态下兑现不了。与其给一个会把人淹掉的能力，不如不提供：
     * 要跑脚本请先选自动执行，要逐项确认就回到逐个调工具（那时每次调用都是看得见的）。
     * <p>档位裁剪（只读档不给写类工具）不在这里：那是跨族判据，走 {@code ExecutionPolicy.allowsTool}。
     */
    @Override
    public boolean exposes(ToolDescriptor d, com.wenqu.ai.agent.tool.ToolMountContext mc) {
        if (d != null && "run_code".equals(d.name()) && !runCodeAllowed(mc)) return false;
        return ToolFamilyProvider.super.exposes(d, mc);
    }

    private static boolean runCodeAllowed(com.wenqu.ai.agent.tool.ToolMountContext mc) {
        if (!mc.gateOn("tool.runcode.enabled")) return false;
        com.wenqu.ai.agent.permission.ExecutionPolicy p = mc.permissionPolicy();
        // 生效审批档以本轮解析出来的策略为准（智能体没设过时它来自档位建议值）——直接问 agent 列会漏掉那种情况
        String approval = p != null ? p.approvalMode() : (mc.approvalAsk() ? "ask" : "auto");
        return !"ask".equalsIgnoreCase(approval == null ? "auto" : approval.trim());
    }

    /**
     * 本族只剩 {@code run_code}，开关是 {@code tool.runcode.enabled}。{@code tool.sandbox.enabled}
     * 只控制沙盒 API 与工作流沙盒节点，不是聊天工具的启停键（框架原生文件/命令工具由权限档位管）。
     * <p>审批档位=off 时整族不挂由 {@code ToolFamily.SANDBOX.blockedWhenApprovalOff()} 统一管，
     * 不再写在装配处的 if 里。<b>权限档位的裁剪也不在本族</b>：只读档下写类工具与 execute 不进工具表
     * 这条判据在 {@code ExecutionPolicy.allowsTool}（按描述符的 SideEffect），由装配样板统一过——
     * 它是跨族的，抄进本族就成了第二份真源，改档位时必漏。
     */
    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> exposes(d, mc));
    }
}
