package com.wenqu.ai.sandbox;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.sandbox.SandboxState;

/**
 * 框架侧沙箱状态（{@link SandboxState} 的问渠实现）。
 *
 * <p><b>为什么需要它</b>：框架 {@code SandboxState} 是抽象类，用 {@code @JsonTypeInfo} 做多态序列化
 * （框架注释：*"Concrete subtypes are registered for Jackson via HarnessSandboxJacksonModule ... not via
 * {@code @JsonSubTypes} on this class"*）。问渠的沙箱身份不是框架默认的随机 UUID sessionId，
 * 而是 {@code sha256(uid + ":" + scopeId)} 前 12 位（见
 * {@link ProvisionerSandboxProvider#sandboxIdForThread}），且需要记住
 * {@code threadId} / {@code uid} / {@code workdirPath} 才能把 scope接回provider。
 * 这些字段框架基类没有，故子类化承载。
 *
 * <h3>身份承载方式</h3>
 * {@link #sandboxId} 是权威值；{@code threadId} / {@code uid} / {@code workdirPath}
 * 一并落库，使 {@code resume} 时不必再从 sessionId 反解。
 *
 * <p><b>注意</b>：本类需在 ObjectMapper 上注册（{@code registerSubtypes}），
 * 否则 Jackson 反序列化时找不到type 标识会报错。
 */
public class ProvisionerSandboxState extends SandboxState {

    /** 问渠沙箱 id：{@code sha256(uid:scopeId)} 前 12 位，与 provider 缓存键同源。 */
    @JsonProperty("wenquSandboxId")
    private String sandboxId;

    /** 本轮作用域 id（会话 id，或工作区共用时的项目 id）。 */
    @JsonProperty("wenquThreadId")
    private String threadId;

    /** 沙箱归属用户；provider 按它做归属校验，防止跨 uid 复用。 */
    @JsonProperty("wenquUid")
    private String uid;

    /** 工作区相对路径；{@code projectWorkdirEnabled} 开启时为 {@code projects/{id}}，否则为 null。 */
    @JsonProperty("wenquWorkdirPath")
    private String workdirPath;

    /** provisioner 返回的容器 generation，用于识别被重建过的容器。 */
    @JsonProperty("wenquGeneration")
    private String generation;

    public String getSandboxId() {
        return sandboxId;
    }

    public void setSandboxId(String sandboxId) {
        this.sandboxId = sandboxId;
    }

    public String getThreadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getWorkdirPath() {
        return workdirPath;
    }

    public void setWorkdirPath(String workdirPath) {
        this.workdirPath = workdirPath;
    }

    public String getGeneration() {
        return generation;
    }

    public void setGeneration(String generation) {
        this.generation = generation;
    }
}