package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.ssh.SshChatTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SSH 远程命令族：普通会话里说「ssh wenqu 上去看看」时用的那把工具。
 *
 * <p>与工作区里的 {@code ssh_alias} 后端共享同一条链路（本机 agent → 本机 ssh），
 * 但不再要求先建一个特殊工作区。审批与档位沿用问渠注册中心那一份口径：
 * {@code requiresApproval=true}、只读档整族不挂（见 {@code ExecutionPolicy#allowsTool}）。
 *
 * @author yuanke
 */
@Component
public class SshToolProvider implements ToolFamilyProvider {

    private final SshChatTool tool;

    public SshToolProvider(SshChatTool tool) {
        this.tool = tool;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.SSH;
    }

    @Override
    public Object holder() {
        return tool;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(new ToolDescriptor(
                "ssh_exec", ToolFamily.SSH,
                "SSH 远程命令", "连接远端主机执行", "用你本机的 OpenSSH 连过去看一眼、跑条命令",
                SideEffect.MUTATE, null, false, true));
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> exposes(d, mc));
    }
}
