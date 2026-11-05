package com.wenqu.ai.agentx;

import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 框架 MessageBus / AsyncToolRegistry 的无操作实现（问答延迟治理）。
 *
 * <h3>为什么需要它</h3>
 * 框架 {@code InboxMiddleware} 挂在 {@code onReasoning} 链上，<b>每一轮模型调用前</b>都会
 * {@code inboxDrain} + {@code findStale}。wenqu 不注入自定义实现时，框架回落到
 * <b>沙盒文件系统版</b>（{@code WorkspaceMessageBus} / {@code WorkspaceAsyncToolRegistry}）——
 * 这两份实现把队列落在沙盒容器文件系统上，每个判定都要一次
 * {@code CompositeFilesystem.exists / ls}，即一次到沙盒容器的同步 HTTP 往返
 * （线上线程栈实测：{@code WorkspaceMessageBus.listSorted → exists → execCommand} 与
 * {@code WorkspaceAsyncToolRegistry.findStale → exists → execCommand} 各一处，都在
 * PRE_REASONING 与模型首包之间的关键路径上）。而问渠<b>从不使用</b>这两个通道：
 * <ul>
 *   <li>运行中插话（steer）走框架 {@code AgentSession.steer → SessionInbox}（内存 + SessionLog），
 *       与 {@code MessageBus} 无关；</li>
 *   <li>异步工具注册表（{@code AsyncToolRegistry}）问渠从未启用；</li>
 *   <li>wakeup / 跨副本 HintBlock 注入问渠也未使用。</li>
 * </ul>
 * 于是每轮付两次容器往返，换回来的一定是空列表。本类把这两个口子换成零成本的空实现：
 * 判定结果恒为「无消息 / 无过期」，链路照常走通（{@code InboxMiddleware} 的 zip 语义要求
 * 返回 {@code Mono.just(空列表)} 而不是 {@code Mono.empty()}——后者会让 zip 直接空完成，
 * 本轮推理被静默吞掉）。
 *
 * <h3>什么时候要换回真实现</h3>
 * 一旦问渠接上框架的跨副本消息注入（{@code inboxPush}）或异步工具，必须换成持久化实现
 * （Redis 版 {@code MessageBus}）；届时这两处每轮开销就变成其真实成本。
 */
public final class NoopBusComponents {

    private NoopBusComponents() {
    }

    /** 无状态，全局共用一份。 */
    public static final MessageBus MESSAGE_BUS = new NoopMessageBus();

    /** 无状态，全局共用一份。 */
    public static final AsyncToolRegistry ASYNC_TOOL_REGISTRY = new NoopAsyncToolRegistry();

    /** 判定结果恒为空的 MessageBus。所有写操作静默成功（问渠没有调用方，无语义损失）。 */
    static final class NoopMessageBus implements MessageBus {

        @Override
        public Mono<String> queuePush(String key, Map<String, Object> payload) {
            return Mono.just("noop");
        }

        @Override
        public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<Void> queueDelete(String key) {
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> queuePeek(String key) {
            return Mono.just(Boolean.FALSE);
        }

        @Override
        public Mono<Boolean> inboxHasMessages(String sessionId) {
            return Mono.just(Boolean.FALSE);
        }

        @Override
        public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
            return Mono.just("noop");
        }

        @Override
        public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<Void> logTrim(String key) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> publish(String key, Map<String, Object> payload) {
            return Mono.empty();
        }

        @Override
        public Flux<Map<String, Object>> subscribe(String key) {
            return Flux.empty();
        }

        @Override
        public Mono<Void> inboxPush(String sessionId, Map<String, Object> msg) {
            return Mono.empty();
        }

        @Override
        public Mono<List<BusEntry>> inboxDrain(String sessionId, int maxCount) {
            // 必须是「空列表」而不是 Mono.empty()：InboxMiddleware 用 zip 汇聚 inbox 与 stale
            // 两路，empty 会让 zip 空完成、本轮推理被静默吞掉。
            return Mono.just(List.of());
        }
    }

    /** 判定结果恒为「无运行中工具」的 AsyncToolRegistry。 */
    static final class NoopAsyncToolRegistry implements AsyncToolRegistry {

        @Override
        public Mono<Void> register(AsyncToolRecord record) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> complete(String id, String result) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> fail(String id, String error) {
            return Mono.empty();
        }

        @Override
        public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration ttl) {
            return Mono.just(List.of());
        }

        @Override
        public Mono<Void> markTimeout(String id) {
            return Mono.empty();
        }
    }
}
