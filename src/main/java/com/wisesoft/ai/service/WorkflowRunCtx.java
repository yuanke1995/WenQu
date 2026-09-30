package com.wisesoft.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 工作流单次运行的<b>显式执行上下文</b>（对齐 {@code SubAgentOrchestrator.RunCtx} 的已验证范式）：
 * 运行期参数不走 ThreadLocal（并行分支会被框架调度到 reactor 线程池，ThreadLocal 全部失效），
 * 而是随 DSL 快照按次编译、节点动作直接闭包本对象（图不复用，故无需 ctxId 经 state 中转的注册表）。
 * <p>
 * 同时承担 trace 收集：每个节点执行完写一条（status/输入输出摘要/耗时/token），
 * 运行结束随 {@code c_ai_workflow_run.node_traces} 落库。
 *
 * @author yuanke
 */
public final class WorkflowRunCtx {

    /** 触发者 uid（检索可见性 / 模型判权 / 运行记录归属） */
    final String uid;
    final String departmentId;
    final String role;
    /** 本次运行 id（人工审核节点的 PENDING 记录以此关联归属） */
    final String runId;
    /** 触发线程的参数覆盖快照（节点线程内重放；见 ConfigService.currentOverrides 的跨线程口径） */
    final Map<String, String> baseOverrides;
    /** 开始节点入参（变量引用 {{start.key}} 的取值来源） */
    final Map<String, Object> inputs;
    /** 各 llm 节点预解析的生效模型引用（nodeId → 引用；run 前统一 fail-fast，不让模型问题拖到节点执行时才炸） */
    final Map<String, String> resolvedModels = new LinkedHashMap<>();
    /** 全局温度兜底（chat.temperature 在触发线程读好，节点内不再读 ThreadLocal 配置） */
    final double defaultTemperature;
    /** 运行期最大步数（图迭代上限，对齐 agent.maxToolSteps 语义；M1 图无环，兜底防 M3 loop 回跳失控） */
    final int maxSteps;
    /**
     * M4 对话型接入（智能体绑定）的 token 透传通道：非空时 llm 节点改走流式调用，
     * 每个文本块即时交给它（由 RagService 包装成 SSE token 事件推给前端），
     * 节点输出仍为完整文本——流式只是"边跑边看"，不改变节点语义。
     * 调试运行 / API 触发 / 恢复续跑为 null（无客户端可推，走非流式）。
     */
    final Consumer<String> tokenSink;

    // ---- M3：人工审核的挂起/恢复（快照短路重放方案） ----
    /**
     * 恢复执行时各节点上次运行的<b>全量</b>输出（nodeId → 裸键输出 map；截断前的原值）。
     * 包装层发现某节点有历史输出即短路返回（不再执行节点体）——已完成节点零成本续跑，
     * 只有挂起节点与其下游真正执行。非恢复运行恒为空。
     */
    final Map<String, Map<String, Object>> resumeOutputs = new LinkedHashMap<>();
    /** 审批裁决（nodeId → 批准与否）；审核节点体据此路由 approve/reject 分支 */
    final Map<String, Boolean> approvalDecisions = new LinkedHashMap<>();
    /**
     * 每个成功节点的全量输出登记（nodeId → 裸键输出 map，与 state 同值但不截断）。
     * 挂起时整体序列化进 run.state_snapshot，作为恢复执行的短路依据。
     */
    final Map<String, Map<String, Object>> fullOutputs = new LinkedHashMap<>();

    /** 节点 trace（完成序；同步串行图无并发写，并行图(M3)再上锁） */
    final List<Map<String, Object>> traces = new ArrayList<>();
    /** 开始时刻（run 总耗时用） */
    final long t0 = System.currentTimeMillis();

    WorkflowRunCtx(String runId, String uid, String departmentId, String role, Map<String, String> baseOverrides,
                   Map<String, Object> inputs, double defaultTemperature, int maxSteps) {
        this(runId, uid, departmentId, role, baseOverrides, inputs, defaultTemperature, maxSteps, null);
    }

    WorkflowRunCtx(String runId, String uid, String departmentId, String role, Map<String, String> baseOverrides,
                   Map<String, Object> inputs, double defaultTemperature, int maxSteps, Consumer<String> tokenSink) {
        this.runId = runId;
        this.uid = uid;
        this.departmentId = departmentId;
        this.role = role;
        this.baseOverrides = baseOverrides == null ? Map.of() : baseOverrides;
        this.inputs = inputs == null ? Map.of() : inputs;
        this.defaultTemperature = defaultTemperature;
        this.maxSteps = maxSteps;
        this.tokenSink = tokenSink;
    }

    /**
     * 写一条节点 trace（幂等键：nodeId+完成时刻；字段口径见 c_ai_workflow_run.node_traces）。
     * output 截断进摘要，避免 LLM 长回答把 trace 撑爆（完整输出在 state 里供 end 节点引用）。
     */
    synchronized void trace(String nodeId, String type, String status, Map<String, Object> input,
                            Object output, long elapsedMs, Integer promptTokens, Integer completionTokens,
                            String error) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("nodeId", nodeId);
        t.put("type", type);
        t.put("status", status);
        if (input != null && !input.isEmpty()) t.put("input", truncateForTrace(input, 400));
        if (output != null) t.put("output", truncateForTrace(output, 800));
        t.put("elapsedMs", elapsedMs);
        if (promptTokens != null) t.put("promptTokens", promptTokens);
        if (completionTokens != null) t.put("completionTokens", completionTokens);
        if (error != null && !error.isBlank()) t.put("error", abbreviate(error, 500));
        traces.add(t);
    }

    /** trace 落库前的整体快照（JSON 序列化用） */
    synchronized List<Map<String, Object>> tracesSnapshot() {
        return new ArrayList<>(traces);
    }

    /** 递归截断：字符串超限加省略号，容器逐项处理（trace 体积可控）。供引擎的输入快照复用 */
    static Object truncateForTrace(Object v, int max) {
        if (v instanceof String s) return abbreviate(s, max);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), truncateForTrace(e.getValue(), max));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(truncateForTrace(o, max));
            return out;
        }
        return v;
    }

    static String abbreviate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…（已截断）" : s;
    }
}
