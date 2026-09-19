package com.wisesoft.wenqu.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangGraph {@code StateSnapshot} 的最小承载（能力差异，显式标注）。
 *
 * <p>参考实现 {@code CompiledStateGraph.aget_state(config)} 返回 {@code StateSnapshot}，
 * 其消费面（实测于 {@code chat_service.py}）为：
 * <ul>
 *   <li>{@code state.values.get("messages", [])} —— 线程完整历史；</li>
 *   <li>{@code state.values.get("__interrupt__")} —— 中断载荷；</li>
 *   <li>{@code state.tasks} → {@code task.interrupts[0]} —— Human-in-the-loop 中断信息
 *       （{@code _extract_interrupt_info}）。</li>
 * </ul>
 *
 * <p>本工程图引擎由 spring-ai-alibaba graph-core 提供，`CompiledGraph.getState(config)`
 * 返回 {@code StateSnapshot}（同名的 Java 类型），但字段面不同。故本类承载参考实现
 * 侧真正被消费的那几面：{@link #values()} / {@link #tasks()} / {@link #next()} /
 * {@link #config()}；未提供的面不造键。
 *
 * <p>用途：作为 {@code BaseAgent.AgentsGraphPort#agetState} 的返回体，并被
 * {@code BaseAgent.streamInputWithState} 以 {@code checkpoint} 事件下发。
 */
public final class GraphStateSnapshot {

    private final Map<String, Object> values;
    private final List<Map<String, Object>> tasks;
    private final List<String> next;
    private final Map<String, Object> config;

    private GraphStateSnapshot(
            Map<String, Object> values,
            List<Map<String, Object>> tasks,
            List<String> next,
            Map<String, Object> config) {
        this.values = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        this.tasks = tasks == null ? new ArrayList<>() : new ArrayList<>(tasks);
        this.next = next == null ? new ArrayList<>() : new ArrayList<>(next);
        this.config = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
    }

    /** 空快照（对应 {@code aget_state} 无 checkpoint 时的空态）。 */
    public static GraphStateSnapshot empty() {
        return new GraphStateSnapshot(null, null, null, null);
    }

    public static GraphStateSnapshot of(
            Map<String, Object> values,
            List<Map<String, Object>> tasks,
            List<String> next,
            Map<String, Object> config) {
        return new GraphStateSnapshot(values, tasks, next, config);
    }

    /** {@code state.values}（线程完整历史）。 */
    public Map<String, Object> values() {
        return new LinkedHashMap<>(values);
    }

    /** {@code state.values} 中某个键（不存在返回 null）。 */
    public Object value(String key) {
        return values.get(key);
    }

    /** {@code state.tasks}（每个 task 可含 {@code interrupts}）。 */
    public List<Map<String, Object>> tasks() {
        return new ArrayList<>(tasks);
    }

    /** {@code state.next}（下一批待执行节点）。 */
    public List<String> next() {
        return new ArrayList<>(next);
    }

    /** {@code state.config}（快照对应的 configurable）。 */
    public Map<String, Object> config() {
        return new LinkedHashMap<>(config);
    }

    /** 是否为空快照（无 checkpoint）。 */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * {@code _extract_interrupt_info}：从 tasks 或 {@code values["__interrupt__"]} 提取中断信息。
     *
     * <p>顺序与参考实现一致：先 tasks → {@code task["interrupts"][0]}，再回落到
     * {@code values["__interrupt__"][0]}；都没有返回 null。
     */
    public Object extractInterruptInfo() {
        for (Map<String, Object> task : tasks) {
            Object interrupts = task.get("interrupts");
            if (interrupts instanceof List<?> list && !list.isEmpty()) {
                return list.get(0);
            }
        }
        Object interruptData = values.get("__interrupt__");
        if (interruptData instanceof List<?> list && !list.isEmpty()) {
            return list.get(0);
        }
        return null;
    }

    /** 序列化（供 jsonSafe 兜底；键序与消费面一致）。 */
    public Map<String, Object> toDict() {
        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("values", values());
        dict.put("next", new ArrayList<>(next));
        dict.put("config", config());
        dict.put("tasks", tasks());
        return dict;
    }

    @Override
    public String toString() {
        return "GraphStateSnapshot" + toDict();
    }
}
