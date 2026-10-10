package com.wenqu.ai.agent.tool;

import java.util.Map;

/**
 * 按族的工具上下文注入器（本轮独占）。
 * <p>
 * 干什么的：某些工具执行时需要「本轮的落点」才能干活——知识检索要把命中块注册进当前回答的引用编号
 * 体系、联网搜索要扣本轮配额、沙盒要把输出增量推成 SSE。迁移前这些落点走类级 ThreadLocal
 * （set → 执行 → finally clear），现在改成每次调用现造一份条目、由管线合进 {@code ToolContext}
 * 递给工具，工具自己从上下文读。
 * <p>
 * 为什么放在本轮上下文里而不是做成独立 bean：这些闭包天生握着本轮问答的状态（引用列表、配额计数、
 * SSE emitter），搬到 seam 包就等于让通用层认识问答流水线的内部结构。族迁移到「挂载也走注册中心」
 * 之后，这些注入器再升为 bean 才有意义。
 */
public interface ToolEnrichment {

    /** 本次调用要合入 ToolContext 的条目（键名 = 各工具类自己声明的 CTX_* 常量，语义由工具类解释） */
    Map<String, Object> entriesFor(ToolInvocation inv);
}
