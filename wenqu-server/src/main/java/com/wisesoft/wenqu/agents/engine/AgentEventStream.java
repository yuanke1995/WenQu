package com.wisesoft.wenqu.agents.engine;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.wisesoft.wenqu.agents.BaseAgent;
import com.wisesoft.wenqu.agents.ToolMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * {@link BaseAgent.EventStream} 的实现：把 spring-ai-alibaba 的 {@link NodeOutput} 流
 * 合成为参考实现所消费的 LangGraph v3 事件词汇。
 *
 * <h3>数据源：{@link StreamingOutput#message()}</h3>
 * <p>参考实现消费 {@code graph.astream_events(version="v3")}，其 {@code messages} 事件载荷为
 * {@code (message_chunk, metadata)} 二元组；{@code tools} 事件载荷为
 * {@code {event, name, tool_call_id, input, output}}。
 *
 * <p>spring-ai-alibaba 没有同构的事件总线，但把"本节点产出的消息"直接挂在
 * {@link StreamingOutput} 上（{@link NodeOutput} 的子类）：
 * <ul>
 *   <li>{@code _AGENT_MODEL_} 节点 → {@code message()} 是 {@link AssistantMessage}
 *       （逐分片，等价于参考实现的 {@code AIMessageChunk}）；</li>
 *   <li>{@code _AGENT_TOOL_} 节点 → {@code message()} 是 {@link ToolResponseMessage}；</li>
 *   <li>引擎的<b>完成信号</b>节点 → {@code message()} 为 {@code null}
 *       （{@code NodeExecutor.java:350} 注释：{@code use null message to prevent chunk content}）
 *       —— 本类据此跳过，不作为事件产出。</li>
 * </ul>
 *
 * <p><b>不要读 {@code NodeOutput.state()} 来取消息</b>（本类早期实现踩过）：该 state 是
 * <b>共享可变引用</b>而非快照，实测模型节点的多次 {@code NodeOutput} 共享同一个
 * {@code OverAllState} 实例，内容随读取时机变化（同一节点在无 instruction 时读到
 * {@code [User]}、有 instruction 时读到 {@code [User, Instruction, Assistant]}）。
 * {@link StreamingOutput#message()} 是节点自己的产出，与步进时机无关。
 *
 * <h3>粒度与形态差异（显式标注，非遗漏）</h3>
 * <ol>
 *   <li><b>messages 分片粒度取决于模型是否流式</b>：模型走流式时逐分片产出（与参考实现一致）；
 *       非流式模型（一次返回完整 {@code ChatResponse}）则一条即全量。</li>
 *   <li><b>tools 的 started/finished 成对紧随</b>：引擎只在节点完成后才暴露工具结果，
 *       故两个事件背靠背产出（参考实现二者之间有真实时间间隔）。</li>
 *   <li><b>tools 事件的 {@code input} 恒为空表</b>：工具入参在<b>前一条</b>
 *       {@code AssistantMessage} 的 {@code tool_calls} 里（下游按 {@code tool_call_id} 关联），
 *       工具节点自身的输出不含入参。</li>
 *   <li><b>values 只在图结束时产出一次</b>：参考实现每个 super-step 都产出；
 *       {@code checkpoint} 事件另行下发快照（见 {@code BaseAgent.streamInputWithState}）。</li>
 *   <li>hook 节点（{@code _AGENT_HOOK_*.beforeModel} 等）是引擎内部节点，其
 *       {@code StreamingOutput.message()} 恒为 null，故不产出事件；参考实现的对应中间件
 *       同样不产生 {@code astream_events} 事件，一致。</li>
 * </ol>
 *
 * <h3>线程语义</h3>
 * <p>{@link #subagents()} 恒定返回空表：子智能体流需要子图（{@code buildin/subagent/graph}）
 * 落地后由其节点命名空间产出；参考实现在无子智能体时同样是空
 * （{@code getattr(run, "subagents", None) is None} 分支）。
 */
public final class AgentEventStream implements BaseAgent.EventStream {

    private static final Logger log = LoggerFactory.getLogger(AgentEventStream.class);
    private static final List<String> ROOT_NAMESPACE = List.of();

    private final Flux<NodeOutput> outputs;
    private final String threadId;

    private int seq;
    private volatile boolean closed;

    public AgentEventStream(Flux<NodeOutput> outputs, RunnableConfig runnableConfig) {
        this.outputs = outputs;
        this.threadId = runnableConfig == null ? null : runnableConfig.threadId().orElse(null);
    }

    @Override
    public void forEachEvent(Consumer<Map<String, Object>> consumer) {
        if (outputs == null) {
            return;
        }
        for (NodeOutput output : outputs.toIterable()) {
            if (closed) {
                return;
            }
            try {
                emit(output, consumer);
            } catch (RuntimeException error) {
                // 参考实现的消费循环在单事件异常时仅记录，不中断整条流
                log.debug("synthesize stream event failed at node {}: {}",
                        output == null ? null : output.node(), error.getMessage());
            }
        }
    }

    /** 逐个 {@link NodeOutput} 合成事件。 */
    private void emit(NodeOutput output, Consumer<Map<String, Object>> consumer) {
        if (output == null) {
            return;
        }
        if (output.isSTART()) {
            emit(consumer, "lifecycle", lifecycleData("on_chain_start", StateGraph.START));
            return;
        }
        if (output.isEND()) {
            emit(consumer, "lifecycle", lifecycleData("on_chain_end", StateGraph.END));
            emit(consumer, "values", GraphCodec.stateToMap(output.state()));
            return;
        }
        if (!(output instanceof StreamingOutput<?> streaming)) {
            return;
        }
        Object message = streaming.message();
        if (message == null) {
            // 引擎的完成信号（NodeExecutor 以 null message 区分），不产出事件
            return;
        }
        String node = output.node();
        if (message instanceof AssistantMessage assistant) {
            emit(consumer, "messages", messageData(assistant, node, streaming));
            return;
        }
        if (message instanceof ToolResponseMessage responses) {
            for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
                emit(consumer, "tools", toolEvent("tool-started", response, null));
                emit(consumer, "tools", toolEvent("tool-finished", response,
                        ToolMessage.of(response.responseData(), response.id(), response.name())));
            }
        }
    }

    /** messages 事件载荷：{@code data = [message, metadata]}（与参考实现的二元组一致）。 */
    private List<Object> messageData(
            AssistantMessage assistant, String node, StreamingOutput<?> streaming) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("langgraph_node", node);
        metadata.put("tags", new ArrayList<>());
        Map<String, Object> configurable = new LinkedHashMap<>();
        if (threadId != null) {
            configurable.put("thread_id", threadId);
            metadata.put("thread_id", threadId);
        }
        configurable.put("checkpoint_ns", "");
        metadata.put("configurable", configurable);
        // 引擎把本分片的 ChatResponse 挂在 originData 上（含 token usage），对齐参考实现
        // messages 事件 metadata 里的 usage 事实源。
        if (streaming.getOriginData() instanceof ChatResponse chatResponse
                && chatResponse.getMetadata() != null
                && chatResponse.getMetadata().getUsage() != null) {
            metadata.put("usage", chatResponse.getMetadata().getUsage().getTotalTokens());
        }

        List<Object> data = new ArrayList<>();
        data.add(GraphCodec.toCarrier(assistant));
        data.add(metadata);
        return data;
    }

    /** tools 事件载荷：{@code {event, name, tool_call_id, input, output}}。 */
    private Map<String, Object> toolEvent(
            String event, ToolResponseMessage.ToolResponse response, ToolMessage output) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", event);
        data.put("name", response.name());
        data.put("tool_call_id", response.id());
        data.put("input", new LinkedHashMap<String, Object>());
        data.put("output", output);
        return data;
    }

    private Map<String, Object> lifecycleData(String event, String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", event);
        data.put("name", name);
        data.put("run_id", threadId);
        return data;
    }

    private void emit(Consumer<Map<String, Object>> consumer, String method, Object data) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", new ArrayList<>(ROOT_NAMESPACE));
        params.put("data", data);
        params.put("timestamp", System.currentTimeMillis());
        if (threadId != null) {
            params.put("thread_id", threadId);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("method", method);
        event.put("params", params);
        event.put("seq", seq++);
        consumer.accept(event);
    }

    @Override
    public List<Map<String, Object>> subagents() {
        // 子智能体流待 buildin/subagent 子图落地后由其命名空间产出（见类注释）
        return List.of();
    }

    @Override
    public void close() {
        closed = true;
    }
}
