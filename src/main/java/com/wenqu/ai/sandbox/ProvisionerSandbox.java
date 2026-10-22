package com.wenqu.ai.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

/**
 * 框架 {@link Sandbox} 的问渠实现 —— 把「问渠 provisioner 管理的沙盒」包装成框架认识的自管沙箱。
 *
 * <p><b>定位</b>：全框架化迁移（P1）的适配层。框架的
 * {@code io.agentscope.harness.agent.filesystem.sandbox.BaseSandboxFilesystem} 以
 * {@code execute} 为主数据面（框架注释：*"This class provides default implementations for all
 * {@code AbstractFilesystem} methods by delegating to shell commands via execute()"*），
 * 而问渠 provisioner 本身就提供 {@code POST /v1/shell/exec}——两边语义天然对齐，
 * 故本类只做转发，不重实现任何文件语义。
 *
 * <h3>生命周期（框架 {@code Sandbox} 注释原文）</h3>
 * <pre>
 * create/resume → start() → 使用 → stop() → shutdown()
 * stop(): persist snapshot only — safe for both self-managed and user-managed sandboxes
 * shutdown(): destroy backend resources — only called on self-managed sandboxes
 * </pre>
 * 问渠的容器由 {@link ProvisionerSandboxProvider} 按 scope 缓存，
 * <b>生命周期归问渠管</b>，因此本类：
 * <ul>
 *   <li>{@link #start()}：向 provider 取/建连接，置 {@code started}</li>
 *   <li>{@link #stop()}：<b>不动容器</b>（框架注释说 stop 只持久化快照；问渠的持久化是
 *       provisioner bind 宿主目录，不需要框架这层快照）</li>
 *   <li>{@link #shutdown()}：<b>不覆写</b>——框架默认实现是 no-op，对应
 *       {@code containerOwned=false} 语义（框架注释：*"When false, the container was injected
 *       by the developer and will not be removed"*）。误覆写会导致框架删掉问渠在管的容器。</li>
 * </ul>
 *
 * <h3>工作目录</h3>
 * {@link ProvisionerSandboxBackend#execute} 内部已用
 * {@code cd /home/gem/user-data && { ... }} 包装（见其 {@code inWorkspace}），
 * 因此本类转发的命令天然落在沙盒工作区内——框架发来的相对路径不会丢到容器临时层。
 */
public class ProvisionerSandbox implements Sandbox {

    private final ProvisionerSandboxBackend backend;
    private final ProvisionerSandboxState state;

    private volatile boolean started;

    public ProvisionerSandbox(ProvisionerSandboxBackend backend, ProvisionerSandboxState state) {
        this.backend = Objects.requireNonNull(backend, "backend is required");
        this.state = Objects.requireNonNull(state, "state is required");
    }

    // ==================== 生命周期 ====================

    @Override
    public void start() {
        // provider.get 内部已做：缓存命中 → touch keepalive；未命中 → 按 createIfMissing 建容器。
        // 这里只需确保本实例持有的 backend 能拿到连接（其 getClient 走 provider）。
        backend.getClient();
        started = true;
    }

    @Override
    public void stop() {
        // 不释放容器：问渠按 scope 缓存沙盒，跨轮复用是既定语义（同工作区多会话共享）。
        // 真正的回收由 ProvisionerSandboxProvider#release / releaseIdle 走 keepalive 节流。
        started = false;
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    @Override
    public SandboxState getState() {
        return state;
    }

    // ==================== 数据面 ====================

    /**
     * 执行一条 shell 命令（框架文件工具的主数据面）。
     *
     * <p>直接委托 {@link ProvisionerSandboxBackend#execute(String, Integer)}，因为它已完成：
     * <ul>
     *   <li>{@code cd /home/gem/user-data && { ... }} 工作目录包装（相对路径文件不丢）</li>
     *   <li>输出按 {@code maxOutputBytes} 截断（与问渠问答卡片口径一致）</li>
     *   <li>异常 → {@code ExecuteResponse(infraError=true)}，不抛</li>
     *   <li>底层 client 带带外删除自愈（{@code sandbox not found} → 摘缓存重建重试一次）</li>
     * </ul>
     *
     * <p><b>stdout/stderr 的划分</b>：provisioner 的 {@code /v1/shell/exec} 把两流合并返回
     * （{@code SandboxRuntimeClient.SandboxExecResult.output}），框架 {@link ExecResult} 却分两字段。
     * 无法拆分，故合并文本放 stdout、stderr 留空——框架的 {@code combinedOutput()} 在 stderr 为空时
     * 直接返回 stdout，行为等价。
     */
    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        ProvisionerSandboxBackend.ExecuteResponse r =
                backend.execute(command, ProvisionerSandboxBackend.floorToolTimeout(timeoutSeconds));
        String output = r.output == null ? "" : r.output;
        // 与 ProvisionerSandboxFilesystem#execute 同一口径：没拿到退出码记 124，不映射成 0
        int exitCode = r.exitCode == null ? ProvisionerSandboxBackend.EXIT_NOT_COMPLETED : r.exitCode;
        return new ExecResult(exitCode, output, "", r.truncated);
    }

    /**
     * 工作区快照 —— <b>刻意不实现真实语义</b>。
     *
     * <p>问渠的持久化由 provisioner bind 宿主目录承担（容器销毁文件仍在，已实测），
     * 不是框架这种 tar 快照。这里返回空流而不是打包全量，避免每次 call 结束多传一次大 tar。
     * {@link #hydrateWorkspace(InputStream)} 同理为空实现。
     *
     * <p>若将来要启用框架快照，需先评估 tar 传输与现有 bind mount 的优先级——
     * 两者同时开启会出现「同一文件两个真源」。
     */
    @Override
    public InputStream persistWorkspace() {
        return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void hydrateWorkspace(InputStream archive) {
        // 见 persistWorkspace：无操作，且刻意保持为空实现。
    }

    @Override
    public void close() {
        // 框架默认 close = stop + shutdown；问渠 shutdown 是 no-op，故这里只停本实例标记。
        stop();
    }

    /** 供上层诊断：当前作用域对应的沙盒 id。 */
    public String sandboxId() {
        return state.getSandboxId();
    }
}