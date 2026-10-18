package com.wenqu.ai.sandbox;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import java.util.function.Supplier;

/**
 * 框架 {@link SandboxClientOptions} 的问渠实现 —— 承载「这一轮沙盒是谁」的信息。
 *
 * <p>框架契约（源码注释）：{@code createClient()} 由 {@code HarnessAgent.Builder} 在未显式给client 时调用，
 * 使调用方只需配options 对象。据此，问渠把scope 三元组（threadId / uid / workdirPath）与
 * 建沙盒时的行为开关（createIfMissing / inheritEnv / unrestrictedWrites）都收进本类，
 * builder 侧只需 {@code new ProvisionerSandboxClientOptions(...)}。
 *
 * <p><b>工作区根必须覆写</b>：框架默认 {@code /workspace}，问渠沙盒的数据根是
 * {@code /home/gem/user-data}（见 {@code ProvisionerSandboxBackend.WORKSPACE_ROOT}）。
 * 不覆写会让框架的 shell 命令发到错误的目录——而那目录没挂载，文件写了也随容器销毁而丢。
 */
public class ProvisionerSandboxClientOptions extends SandboxClientOptions {

    /** JSON type discriminator，与 {@code @JsonSubTypes} 注册名一致。 */
    public static final String TYPE = "provisioner";

    private final String threadId;
    private final String uid;
    private final String workdirPath;
    private final boolean createIfMissing;
    private final boolean inheritEnv;
    private final boolean unrestrictedWrites;
    private final Supplier<ProvisionerSandboxProvider> providerSupplier;

    public ProvisionerSandboxClientOptions(
            String threadId,
            String uid,
            String workdirPath,
            boolean createIfMissing,
            boolean inheritEnv,
            boolean unrestrictedWrites,
            Supplier<ProvisionerSandboxProvider> providerSupplier) {
        this.threadId = threadId == null ? "" : threadId.strip();
        this.uid = uid == null ? "" : uid.strip();
        this.workdirPath = workdirPath == null ? "" : workdirPath.strip();
        this.createIfMissing = createIfMissing;
        this.inheritEnv = inheritEnv;
        this.unrestrictedWrites = unrestrictedWrites;
        this.providerSupplier = providerSupplier;
        if (this.threadId.isEmpty()) {
            throw new IllegalArgumentException("threadId is required for ProvisionerSandboxClientOptions");
        }
        if (this.uid.isEmpty()) {
            throw new IllegalArgumentException("uid is required for ProvisionerSandboxClientOptions");
        }
    }

    /**
     * 兜底选项：会话级 scope、不继承环境变量。
     *
     * <p>仅在 options 缺失时使用（框架 create 允许 options 为 null）。
     * 默认 threadId/uid 用非空占位，避免构造期直接抛 —— 真正建沙盒前
     * {@link ProvisionerSandboxClient#create} 会把空 scope 拦在 provider 层。
     */
    public static ProvisionerSandboxClientOptions defaults() {
        return new ProvisionerSandboxClientOptions(
                "anonymous", "anonymous", null, true, false, false, null);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        return new ProvisionerSandboxClient(provider());
    }

    /**
     * 问渠沙盒的数据根（框架默认是 {@code /workspace}，问渠是 provisioner bind 挂载的那个）。
     */
    @Override
    public String getWorkspaceRoot() {
        return "/home/gem/user-data";
    }

    private ProvisionerSandboxProvider provider() {
        if (providerSupplier == null) {
            throw new IllegalStateException(
                    "no ProvisionerSandboxProvider bound to these options");
        }
        return providerSupplier.get();
    }

    public String threadId() {
        return threadId;
    }

    public String uid() {
        return uid;
    }

    public String workdirPath() {
        return workdirPath == null || workdirPath.isEmpty() ? null : workdirPath;
    }

    public boolean createIfMissing() {
        return createIfMissing;
    }

    public boolean inheritEnv() {
        return inheritEnv;
    }

    public boolean unrestrictedWrites() {
        return unrestrictedWrites;
    }
}