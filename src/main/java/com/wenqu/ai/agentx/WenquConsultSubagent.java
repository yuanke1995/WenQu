package com.wenqu.ai.agentx;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.Function;

/**
 * 一位「可咨询助手」在框架派遣体系里的登记形态（派遣改由框架 {@code agent_spawn} 驱使）。
 *
 * <p><b>为什么它是一个 Agent 而不是一个工具</b>：框架的 {@code DefaultAgentManager#invokeAgent}
 * 对非 ReAct／非 Harness 的 Agent 走 {@code agent.call(List.of(msg))} 这一条回落，
 * 于是问渠可以交一个「自己不跑模型循环、而是起一条完整问答轮」的实现——
 * 框架负责**什么时候派、派谁、同步等还是后台等、结果怎么回灌父轮上下文**，
 * 问渠负责**那一轮里到底发生了什么**（即时落库、轮级互斥、断线续跑、引用回填、产物收编）。
 *
 * <p><b>刻意不实现成一个真跑模型的 HarnessAgent</b>：子轮要的是问渠的问答外壳，
 * 在框架引擎里再嵌一层内层循环就拿不到那条轮的临时会话、卡片与台账。
 *
 * <p>结构化输出与流式两条路按「同一份回报」实现：框架的同步与后台任务路径都只读
 * {@code call(List<Msg>)} 的文本；{@code stream(...)} 那条挂在 {@code SubagentEventBus} 上
 * （问渠不设置那个 reactor context key，走不到），仍按一次成结果给出一帧，避免留一个会抛错的方法。
 *
 * <p>{@code interrupt()} 仍是空实现，但<b>不会留下孤儿</b>：框架的
 * {@code AgentSpawnTool.interruptAgent} 只对 {@code ReActAgent} 调用 {@code interrupt()}
 * （本类只实现 {@code Agent}，调用不到），所以真正的叫停放在
 * {@code SubAgentBranchRuntime}——它按平台固定 5 分钟咨询预算与审批剩余预算收口，
 * 且强制同步等待的截止时间留出余量，保证父轮报超时之前这一支已经停下。
 *
 * @author yuanke
 */
final class WenquConsultSubagent implements Agent {

    /** 框架的 {@code agent_id}，逐字等于问渠的智能体 id：卡片归属、台账与提示词里的 id 才是同一个东西 */
    private final String agentId;
    private final String displayName;
    /** 问题 → 给父轮模型的回报文本（预算、去重、引用编号回填都在 {@code SubAgentConsultService} 里） */
    private final Function<String, String> consult;

    WenquConsultSubagent(String agentId, String displayName, Function<String, String> consult) {
        this.agentId = agentId;
        this.displayName = displayName;
        this.consult = consult;
    }

    /**
     * 跑这一支。子轮是一条完整问答轮（模型 token、被咨询方自己的工具与写盘能力都在里面），
     * 必须离开框架的事件线程去跑，否则整条引擎的流都被这一支占住。
     */
    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return Mono.<Msg>fromCallable(() -> new AssistantMessage(consult.apply(questionOf(msgs))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredModel) {
        return call(msgs);
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return call(msgs);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return call(msgs).map(msg -> new Event(EventType.AGENT_RESULT, msg, true)).flux();
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return stream(msgs, options);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return stream(msgs, options);
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return Mono.empty();
    }

    @Override
    public void interrupt() {
        // 见类注释：框架的 interruptAgent 只认 ReActAgent，本类收不到；
        // 真正的叫停在 SubAgentBranchRuntime（consultTimeout + 审批预算，且早于 force_sync 截止）
    }

    @Override
    public void interrupt(Msg msg) {
        interrupt();
    }

    @Override
    public String getAgentId() {
        return agentId;
    }

    @Override
    public String getName() {
        return displayName;
    }

    @Override
    public String getDescription() {
        return displayName;
    }

    /** 取派遣任务：框架把 task 作为最后一条用户消息交进来；没有消息就当空问（咨询服务自己会回一句「question 不能为空」） */
    private static String questionOf(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) return "";
        Msg last = msgs.get(msgs.size() - 1);
        String text = last == null ? null : last.getTextContent();
        return text == null ? "" : text;
    }
}
