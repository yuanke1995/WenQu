package com.wenqu.ai.agent.subagent;

/**
 * 一位被派遣助手的<b>连续对话</b>句柄。
 *
 * <p>框架的 {@code agent_send} 语义是「接着上次跟同一位助手说」，而不是重新派一位新的。问渠这边
 * 每次咨询都会起一整条子轮；如果每次都新建子会话，第二次就只剩父轮摘要、没有上一轮结论，
 * 于是重新探查、重复烧步数，用户看到的就是「派遣是单线的，还老在原地打转」。
 *
 * <p>句柄按「框架创建的 SpawnedAgent」粒度持有（见 {@code WenquConsultSubagent}）：同一个 agent_key
 * 的 spawn/send 拿的是同一个实例，第一次跑出来的子会话 id 记在这里，下一次直接续那条会话。
 * 独立的第二次 spawn 是新实例、新句柄，不会把两条并行任务并成一条。
 *
 * <p>只存 id，不存会话对象：子轮可能在另一个副本上、也可能已被回收，使用前由分支执行器按库验存。
 */
public final class SubagentConversation {

    private volatile String childSessionId;

    public String childSessionId() {
        return childSessionId;
    }

    public void childSessionId(String id) {
        if (id != null && !id.isBlank()) this.childSessionId = id;
    }
}