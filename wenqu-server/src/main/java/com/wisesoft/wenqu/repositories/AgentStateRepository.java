package com.wisesoft.wenqu.repositories;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LangGraph Agent 状态持久化边界。
 *
 * <p>由参考实现的 repositories/agent_state_repository.py 翻译：通过 canonical graph 读写
 * checkpoint，保留 reducer 与版本语义（写入只能追加 checkpoint，读取返回当前 state values）。
 *
 * <p><b>能力差异（显式标注，非遗漏）</b>：参考实现直接依赖 LangGraph 的 {@code CompiledStateGraph}
 * （{@code graph.checkpointer} / {@code aget_state} / {@code aupdate_state}），Java 侧尚无对应的
 * 编译图引擎——该引擎属于 agents 运行时模块，尚未照搬。因此本类把"图的 checkpoint 能力"收敛为
 * {@link StateGraphPort} 端口：构造时同样要求"图必须带 checkpointer"，读写走端口；待 agents
 * 运行时模块照搬后由其图实现提供该端口，本类不再改动。
 */
public class AgentStateRepository {

    private final StateGraphPort graph;
    private final String uid;
    private final String threadId;

    /** 编译图的 checkpoint 能力端口（对应 CompiledStateGraph 的 checkpointer 相关面）。 */
    public interface StateGraphPort {
        /** 图是否配置了 checkpointer。 */
        boolean hasCheckpointer();

        /** 读取 (uid, thread_id) 处当前 checkpoint 的 state values。 */
        Map<String, Object> getState(String uid, String threadId);

        /** 通过 canonical graph 追加一个 state checkpoint（交由图的 reducer 处理）。 */
        void updateState(String uid, String threadId, Map<String, Object> values);
    }

    /** @throws IllegalArgumentException 图未配置 checkpointer 时（对应参考实现的 ValueError） */
    public AgentStateRepository(StateGraphPort graph, String uid, String threadId) {
        if (graph == null || !graph.hasCheckpointer()) {
            throw new IllegalArgumentException("Agent state repository requires a graph with checkpointer");
        }
        this.graph = graph;
        this.uid = String.valueOf(uid);
        this.threadId = String.valueOf(threadId);
    }

    /** 读取当前 checkpoint 的 state values。 */
    public Map<String, Object> getValues() {
        Map<String, Object> values = graph.getState(uid, threadId);
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    /** 通过 canonical graph 追加一个 state checkpoint；空值不追加。 */
    public void update(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        graph.updateState(uid, threadId, values);
    }
}
