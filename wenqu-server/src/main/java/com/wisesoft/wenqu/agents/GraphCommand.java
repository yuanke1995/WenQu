package com.wisesoft.wenqu.agents;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LangGraph {@code Command} 的最小承载（能力差异，显式标注）。
 *
 * <p>参考实现里 {@code write_todos} / {@code task} 等工具返回
 * {@code langgraph.types.Command}（携带 {@code update} 增量状态），
 * 该类型由 LangGraph 引擎提供。本工程图引擎尚未照搬，故由本类承载其数据面：
 * {@code update} 对应 {@code Command.update}。
 *
 * <p>消费点见 {@link BaseAgent#normalizeToolEventData(java.util.Map)}。
 */
public final class GraphCommand implements ModelDumpable {

    private final Map<String, Object> update;

    private GraphCommand(Map<String, Object> update) {
        this.update = update;
    }

    /** {@code Command(update={...})}。 */
    public static GraphCommand of(Map<String, Object> update) {
        return new GraphCommand(update == null ? new LinkedHashMap<>() : update);
    }

    /** {@code Command.update}；参考实现里可能不是 dict，这里统一为 dict（空 dict 表示无增量）。 */
    public Map<String, Object> getUpdate() {
        return update;
    }

    @Override
    public Map<String, Object> modelDump() {
        Map<String, Object> dump = new LinkedHashMap<>();
        dump.put("update", update);
        return dump;
    }
}
