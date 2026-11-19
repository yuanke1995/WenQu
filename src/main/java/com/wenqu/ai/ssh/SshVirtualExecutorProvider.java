package com.wenqu.ai.ssh;

import com.wenqu.ai.executor.ExecutorRegistry;
import org.springframework.stereotype.Component;

/**
 * 虚拟执行器按需装配：把 {@code ssh:<hostId>} 解析成一个把 SFTP/SSH 翻译成 op 的 {@link SshRemoteExecutor}。
 *
 * <p>为什么按需而不是启动时全量注册：多副本部署下每个副本都可能在任意时刻接到同一会话的工具调用；
 * 按需从库里取主机配置，任意副本都能就地装配，不需要把 SSH 会话粘在某个副本上。
 *
 * @author yuanke
 */
@Component
public class SshVirtualExecutorProvider implements ExecutorRegistry.VirtualExecutorProvider {

    private final SshHostService hostService;
    private final SshConnectionManager connectionManager;
    private final SshAuditService auditService;
    /** 惰性取：避免 provider ↔ ExecutorRegistry 的构造器环（registry 用 setter 装 provider）。 */
    private final org.springframework.beans.factory.ObjectProvider<ExecutorRegistry> registryProvider;

    public SshVirtualExecutorProvider(SshHostService hostService,
                                      SshConnectionManager connectionManager,
                                      SshAuditService auditService,
                                      org.springframework.beans.factory.ObjectProvider<ExecutorRegistry> registryProvider) {
        this.hostService = hostService;
        this.connectionManager = connectionManager;
        this.auditService = auditService;
        this.registryProvider = registryProvider;
    }

    @Override
    public ExecutorRegistry.VirtualExecutor resolve(String virtualId) {
        // 本机 OpenSSH 别名：ssh-alias:<deviceId>:<alias>（连接在用户电脑上，不在本进程）
        SshAliasIds.Parsed alias = SshAliasIds.parse(virtualId);
        if (alias != null) {
            ExecutorRegistry registry = registryProvider.getIfAvailable();
            if (registry == null) return null;
            return new SshAliasVirtualExecutor(alias.executorId(), alias.alias(), registry, auditService);
        }
        // 服务端 SSH 主机：ssh:<hostId>
        String hostId = SshHost.hostIdOf(virtualId);
        if (hostId == null) return null;
        if (hostService.byId(hostId) == null) return null;
        return new SshRemoteExecutor(hostId, hostService, connectionManager, auditService);
    }
}
