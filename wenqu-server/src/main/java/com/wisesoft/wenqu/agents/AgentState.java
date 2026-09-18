package com.wisesoft.wenqu.agents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 状态结构定义。
 *
 * <p>由参考实现的 agents/state.py 逐段翻译：merge_artifacts reducer、BaseState、
 * AgentStatePayload（前端消费的序列化结构）。
 *
 * <p>能力差异（显式标注，非遗漏）：参考实现 BaseState 继承 LangChain 的 AgentState
 * TypedDict（messages/todos/files 等字段的 Annotated reducer 语义由 LangGraph 引擎
 * 承担）；Java 侧引擎尚未照搬，本类先落自有字段与 reducer，字段以字符串常量承载
 * （状态本体为 Map），引擎照搬时按同一键名接入。
 */
public final class AgentState {

    /** BaseState 自有字段：artifacts（merge_artifacts reducer）。 */
    public static final String ARTIFACTS = "artifacts";

    private AgentState() {}

    /** 合并 artifact 文件路径：保序去重（list(dict.fromkeys(...)) 语义）。 */
    public static List<String> mergeArtifacts(List<String> existing, List<String> newItems) {
        if (existing == null) {
            return newItems == null ? new ArrayList<>() : new ArrayList<>(newItems);
        }
        if (newItems == null) {
            return new ArrayList<>(existing);
        }
        List<String> merged = new ArrayList<>(existing);
        merged.addAll(newItems);
        List<String> result = new ArrayList<>();
        for (String item : merged) {
            if (!result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    /** AgentStatePayload 的键集（对应参考实现 TypedDict 字段名与类型形状）。 */
    public static final class AgentStatePayload {

        public static final String TODOS = "todos";                 // list
        public static final String FILES = "files";                 // dict
        public static final String ARTIFACTS = "artifacts";         // list[str]
        public static final String SUBAGENT_RUNS = "subagent_runs"; // list[dict]
        public static final String TOKEN_USAGE = "token_usage";     // dict | None

        private AgentStatePayload() {}

        /** 按参考实现键序构造空载荷。 */
        public static Map<String, Object> empty() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(TODOS, new ArrayList<>());
            payload.put(FILES, new LinkedHashMap<>());
            payload.put(ARTIFACTS, new ArrayList<>());
            payload.put(SUBAGENT_RUNS, new ArrayList<>());
            payload.put(TOKEN_USAGE, null);
            return payload;
        }
    }
}
