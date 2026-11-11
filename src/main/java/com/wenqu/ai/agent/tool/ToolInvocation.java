package com.wenqu.ai.agent.tool;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次工具调用的上下文（per-invocation 那一层）。
 * <p>
 * 把「本轮的落点」装进本次调用独占的对象、经 {@code ToolContext} 递给工具：作用域就是这一次调用，
 * 没有跨调用的可变状态可漏清。类级 {@code ThreadLocal} 的写法依赖「回调与 setter 同线程同步执行」
 * 与「每次 set 都配上 clear」；工具并行执行或异常路径漏清时，池化线程会把落点带到别的会话，
 * 引用编号注册进别人的回答里。
 * <p>
 * {@link #attributes()} 是给各族 {@link ToolContextEnricher} 放私有信箱的地方，
 * 生命周期与本对象一致，同样不落到静态字段上。
 *
 * @author yuanke
 */
public final class ToolInvocation {

    /** 本次调用的标识（审批记录、日志与账本关联用；不落库，仅本轮内唯一） */
    private final String callId = UUID.randomUUID().toString();
    private final ToolDescriptor descriptor;
    private final String toolName;
    private final String arguments;
    private final long beganAtMillis = System.currentTimeMillis();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /** 自动重试次数（首次成功=1）；由执行方回填，观测层据此显示「重试 N 次」 */
    private volatile int attempts = 1;
    /**
     * 被守卫在执行前挡下（步数预算用尽、本轮已叫停、权限档位不允许）。
     * <p>与「执行了但失败」必须分得开：前者一次模型调用都没发生、工具体也没跑，把它算进
     * 「这一支跑了 N 步工具」会让编排卡读起来像"试了 N 次都不行"，而实际是平台只让它试了几次。
     */
    private volatile boolean denied;

    public ToolInvocation(ToolDescriptor descriptor, String toolName, String arguments) {
        this.descriptor = descriptor;
        this.toolName = toolName;
        this.arguments = arguments;
    }

    public String callId() {
        return callId;
    }

    /** 静态声明（MCP 动态工具可能为 null） */
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    public String toolName() {
        return toolName;
    }

    public String arguments() {
        return arguments;
    }

    public long beganAtMillis() {
        return beganAtMillis;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - beganAtMillis;
    }

    public int attempts() {
        return attempts;
    }

    public void attempts(int n) {
        this.attempts = Math.max(1, n);
    }

    public boolean denied() {
        return denied;
    }

    public void denied(boolean denied) {
        this.denied = denied;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }
}
