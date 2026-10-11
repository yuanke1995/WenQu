package com.wenqu.ai.agent.tool;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次工具调用的上下文（P1 seam 的「per-invocation」那一层）。
 * <p>
 * 它替代的是这一类写法：{@code KnowledgeRetrievalTool.setSourceRegistrar(...)} /
 * {@code WebSearchTools.setSink(...)} 把「本轮的落点」写进**类级 ThreadLocal**，再在 finally 里清。
 * 迁移前那套能工作，靠的是两个不体面的前提：① 工具回调与 setter 在同一个线程上同步执行；
 * ② 每一次 set 都配得上一次 clear。前提①在新版 Spring AI 允许工具并行执行时不成立，
 * 前提②只要有一条异常路径漏清，池化线程就把上一轮的注册器带给下一个会话——引用编号会
 * 注册进别人的回答里。把落点装进本次调用独占的对象、经 {@code ToolContext} 递给工具，
 * 两个前提都不再需要：作用域就是这一次调用，没有跨调用的可变状态可漏清。
 * <p>
 * {@link #attributes()} 是给各族 {@link ToolContextEnricher} 放私有信箱的地方（例如沙盒工具
 * 上报基础设施故障的那个引用），生命周期与本对象一致，同样不落到静态字段上。
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
    /** 首个失败上报即定性（单调：后续上报不覆盖），见 {@link #recordFailure(String)} */
    private volatile String failureNote;

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

    /**
     * 上报「这次调用实际失败了」（工具照常把失败文本返回给模型，但卡片不能显示成绿色成功）。
     * <p>典型场景：provisioner 连不上——沙盒工具不抛异常（抛出会掐断整轮流、连带已生成正文与
     * 全部工具记录一起落空），失败文本回给模型是对的，但这条记录必须落成 error。
     * <p>单调：只认第一份上报，后续不覆盖。否则「基础设施没通」会被紧随其后的业务失败文本顶掉，
     * 排查时看不出沙盒压根没起来。
     */
    public void recordFailure(String reason) {
        if (reason == null || reason.isBlank()) return;
        failureNote = failureNote == null ? reason : failureNote;
    }

    public String failureNote() {
        return failureNote;
    }
}
