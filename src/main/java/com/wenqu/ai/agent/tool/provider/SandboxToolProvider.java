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
 * 六个工具全部 requiresApproval=true，**包括只读的 read_file 与 ls**：这是照迁移前的行为如实记录
 * （装配处把整族回调一律计入有副作用名单），不是我的判断。把 read_file 标成 SideEffect.READ
 * 正是为了让 P2 能把它分开——只读档（read-only）下「查看沙盒里生成了什么」不该也要点确认，
 * 但那是 P2 按档位收紧时要动的口径，本次一刀不改行为。
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
     * 不再写在装配处的 if 里。
     */
    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> exposes(d, mc));
    }
}
