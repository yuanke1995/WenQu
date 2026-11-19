package com.wenqu.ai.ssh;

import com.wenqu.ai.executor.ExecutorRegistry;
import org.springframework.stereotype.Component;

/**
 * 虚拟执行器按需装配：把 {@code ssh-alias:<deviceId>:<alias>} 解析成
 * {@link SshAliasVirtualExecutor}（连接发生在用户本机执行器上，不在本服务进程）。
 *
 * <p>多副本部署下每个副本都能按需装配，不需要把会话粘在某个副本上。
 *
 * @author yuanke
 */
@Component
public class SshVirtualExecutorProvider implements ExecutorRegistry.VirtualExecutorProvider {

    private final SshAuditService auditService;
    /** 惰性取：避免 provider ↔ ExecutorRegistry 的构造器环（registry 用 setter 装 provider）。 */
    private final org.springframework.beans.factory.ObjectProvider<ExecutorRegistry> registryProvider;

    public SshVirtualExecutorProvider(SshAuditService auditService,
                                      org.springframework.beans.factory.ObjectProvider<ExecutorRegistry> registryProvider) {
        this.auditService = auditService;
        this.registryProvider = registryProvider;
    }

    @Override
    public ExecutorRegistry.VirtualExecutor resolve(String virtualId) {
        SshAliasIds.Parsed alias = SshAliasIds.parse(virtualId);
        if (alias == null) return null;
        ExecutorRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) return null;
        return new SshAliasVirtualExecutor(alias.executorId(), alias.alias(), registry, auditService);
    }
}
