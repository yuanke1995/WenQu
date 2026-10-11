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
 * 六个工具全部 requiresApproval=true：这是照迁移前的行为如实记录（装配处把整族回调一律计入有副作用
 * 名单）。{@code SideEffect} 的 READ/MUTATE 之分从 P2 起<b>真的参与裁剪</b>了：只读档（read-only）下
 * 只留 read_file 与 ls，execute 与三个写类工具不进模型的工具表——不是「点了被拒」，是看不见。
 * <p>
 * execute 在只读档必须一起摘掉：容器没有任何按档位收束命令的机制（provisioner 建容器时不接档位），
 * 「能跑命令」就等于「能写文件」，留着它再逐条拒绝就是静默透传。
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
        return List.of(
                d("execute", "执行沙盒命令", "运行命令", "在隔离的云端沙盒里执行命令行（不影响本机）", SideEffect.MUTATE),
                d("read_file", "读取沙盒文件", "读取文件", "在云端沙盒里读取文件内容", SideEffect.READ),
                d("write_file", "写入沙盒文件", "写入文件", "在云端沙盒里新建或覆盖文件", SideEffect.MUTATE),
                d("edit_file", "编辑沙盒文件", "编辑文件", "在云端沙盒里修改文件内容", SideEffect.MUTATE),
                d("ls", "查看沙盒目录", "查看文件列表", "列出云端沙盒里某个目录下的文件", SideEffect.READ),
                d("deliver_artifact", "交付沙盒产物", "保存生成的文件",
                        "把云端沙盒里生成的文件存进会话，可查看下载", SideEffect.MUTATE),
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

    private static ToolDescriptor d(String name, String label, String cardLabel, String cardHint, SideEffect se) {
        return new ToolDescriptor(name, ToolFamily.SANDBOX, label, cardLabel, cardHint,
                se, "tool.sandbox.enabled", false, true, null);
    }

    /**
     * 三族之外的最后一族开关：{@code tool.sandbox.enabled} 全局一档，暂无智能体级三态
     * （c_ai_agent 未建 toolSandbox 列，见 SandboxTools 类注释）——沙盒本身按会话隔离，
     * 工具一旦启用对该智能体的所有会话可用。
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
