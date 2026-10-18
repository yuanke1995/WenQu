package com.wenqu.ai.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 框架 {@link SandboxClient} 的问渠实现 —— 让框架把「问渠 provisioner 管理的沙盒」当成一等沙箱后端。
 *
 * <p><b>定位</b>：全框架化迁移（P1）的客户端侧适配。官方文档「实现自己的沙箱后端」一节：
 * *"不需要改 Harness 源码——实现几个契约接口然后传给 {@code filesystem(...)} 就行"*。
 * BETA1 的 {@code impl/} 下只有 docker 后端，故问渠的跨语言 provisioner 只能走这条自建路径。
 *
 * <h3>与官方 Docker 后端的差别</h3>
 * {@code DockerSandboxClient} 自己调 {@code docker} CLI；本类不碰容器编排，
 * 一律经 {@link ProvisionerSandboxProvider}（它背后才是手写 FastAPI provisioner）。
 * 沙箱身份也不是框架默认的随机 UUID，而是问渠的 {@code sha256(uid:scopeId)}。
 *
 * <h3>生命周期归属</h3>
 * 按官方文档「自管沙箱实例」：{@code externalSandbox} 模式下框架只在 call 期间
 * {@code stop()}，<b>不调 {@code shutdown()}</b>，由应用自己收。故本类的 {@link #delete(Sandbox)}
 * 与 {@link ProvisionerSandbox#shutdown()}（保持框架默认 no-op）都不会真删容器；
 * 真正的回收走 {@link ProvisionerSandboxProvider#release} 的 keepalive 节流。
 *
 * <h3>并发</h3>
 * 官方 {@code IsolationScope} 在并发下是 *sequential-reuse*（并发同scope 各拿独立容器，
 * 末次快照收敛），与问渠「同 scope 共享一个活容器 + ProjectTurnMutex 轮次互斥」不同，
 * 故问渠<b>不使用</b> isolationScope，由provider 的 scope 缓存保证单活容器。
 */
public class ProvisionerSandboxClient implements SandboxClient<ProvisionerSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(ProvisionerSandboxClient.class);

    private final ProvisionerSandboxProvider provider;
    private final ObjectMapper objectMapper;

    public ProvisionerSandboxClient(ProvisionerSandboxProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider is required");
        // 框架注释（DockerSandboxClient 同款）：反序列化 SandboxState 需注册
        // HarnessSandboxJacksonModule 与自定义 state 子类，否则 @JsonTypeInfo 找不到 type 会报错。
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.objectMapper.registerModule(new HarnessSandboxJacksonModule());
        // registerSubtypes 返回 void（链式写法在此不成立），故单独一步。
        this.objectMapper.registerSubtypes(ProvisionerSandboxState.class);
    }

    /**
     * 官方注释：*"Returned in a pre-start state; call {@link Sandbox#start()} before use."*
     *
     * <p>问渠侧「创建」实为向 provider 取/建 scope 连接——缓存命中即复用既有容器，
     * 未命中才按 {@code createIfMissing} 建。沙箱身份来自 provider（sha256(uid:scopeId)），
     * 不是本方法生成的。
     */
    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            ProvisionerSandboxClientOptions options) {
        ProvisionerSandboxClientOptions o =
                options == null ? ProvisionerSandboxClientOptions.defaults() : options;
        ProvisionerSandboxState state = new ProvisionerSandboxState();
        state.setThreadId(o.threadId());
        state.setUid(o.uid());
        state.setWorkdirPath(o.workdirPath());
        state.setWorkspaceSpec(workspaceSpec);
        if (workspaceSpec != null) {
            state.setWorkspaceRootReady(true);
        }
        String sandboxId = ProvisionerSandboxProvider.sandboxIdForThread(o.threadId(), o.uid());
        state.setSandboxId(sandboxId);
        // sessionId 位承载问渠的沙箱 id，框架侧若按 session 找state 能对上同一份工作区。
        state.setSessionId(sandboxId);

        ProvisionerSandboxBackend backend =
                new ProvisionerSandboxBackend(
                        provider,
                        o.threadId(),
                        o.uid(),
                        o.workdirPath(),
                        o.createIfMissing(),
                        o.inheritEnv(),
                        o.unrestrictedWrites());
        return new ProvisionerSandbox(backend, state);
    }

    /**
     * 从既有state 恢复。
     *
     * <p>问渠的「恢复」= 按 state 里的 scope 再取一次连接：容器还在就接着用，
     * 不在就重建（provider 内部判定）。这与官方语义一致——
     * *"容器还在 + 工作区还在 → 直接接着用；容器没了 → 拿快照重新起一个"*。
     */
    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof ProvisionerSandboxState wenquState)) {
            // 不静默降级：拿不到问渠的 scope 信息就无法定位沙箱，直接失败比建错沙箱强。
            throw new IllegalArgumentException(
                    "resume requires ProvisionerSandboxState, got " + state.getClass().getName());
        }
        ProvisionerSandboxBackend backend =
                new ProvisionerSandboxBackend(
                        provider,
                        wenquState.getThreadId(),
                        wenquState.getUid(),
                        wenquState.getWorkdirPath(),
                        /* createIfMissing */ true,
                        /* inheritEnv */ true,
                        /* unrestrictedWrites */ false);
        return new ProvisionerSandbox(backend, wenquState);
    }

    /**
     * 官方语义里 delete 是「销毁后端资源」。问渠的容器由 provider 持有并跨轮复用，
     * 在 call 结束时删它会打断同工作区的其他会话，故按官方 {@code externalSandbox} 约定
     * <b>不真删</b>，只记日志——回收由 {@link ProvisionerSandboxProvider#release} 按keepalive 节流。
     */
    @Override
    public void delete(Sandbox sandbox) {
        if (sandbox instanceof ProvisionerSandbox wenqu) {
            log.debug("[SANDBOX] 框架请求 delete，按自管沙箱约定不真删容器 scope={}", wenqu.sandboxId());
        }
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            // 状态序列化失败 = 跨轮 resume 能力受损，属真故障，上抛而非静默返回 null
            throw new IllegalStateException("failed to serialize ProvisionerSandboxState", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize ProvisionerSandboxState", e);
        }
    }
}