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
                        "把云端沙盒里生成的文件存进会话，可查看下载", SideEffect.MUTATE));
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
