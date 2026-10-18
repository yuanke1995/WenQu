package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Spring AI 的 {@link ToolCallback} → AgentScope 一等工具的适配器。
 * <p>存在的理由：问渠九个工具族（内置/知识/MCP/沙盒/技能/子智能体/待办/联网/产物）现在统一产出
 * {@code ToolCallback}（schema 由 Spring AI 反射 {@code @Tool} 生成，见 {@code ToolRegistry}）。
 * 框架引擎要拿到<b>同一批</b>工具，只该有这一处翻译，而不是把九族各重写成框架注解方法——
 * 重写会立刻造成两套工具定义漂移，而工具名/说明/参数正是审批、黑名单、权限档位共同的判据。
 * <p>继承 {@link ToolBase} 而不是只实现 {@code AgentTool}：框架对工具自己的
 * {@code checkPermissions} 是不可绕过的，档位与黑名单要落在这里才真拦得住（落在校验前置的
 * 装配层只决定「模型看不看得见」，看得见而绕过装配的路径仍然存在）。
 * <p>身份与本轮配置覆盖的重放不在这里做：由各工具自己按既有约定处理（见 {@code WenquAgentxTools}），
 * 本适配器只负责协议搬运与权限挂载，避免把「一轮的上下文」藏进一个通用组件里。
 */
public final class SpringAiToolAdapter extends ToolBase {

    /**
     * 工具上下文键：本轮框架<b>活</b>的 {@link AgentState}（任务清单的权威存储，见 {@code TodoBridge}）。
     * <p>为什么由本适配器来填：框架自己的工具是靠 {@code @Tool(stateInjected=true)} 让
     * {@code ToolMethodInvoker} 把 {@code RuntimeContext} 上那份 call 级状态绑进方法参数，
     * 而问渠的工具是 Spring AI 的 {@code ToolCallback}，框架不认识它们的签名，绑不进去。
     * 这里是唯一还拿得到 {@code ToolCallParam} 的地方，所以状态也从这里递进 {@code ToolContext}。
     * <p>不能改用 {@code AgentSession.inspect()} 那份代替：它是从会话日志重放出来的<b>只读快照</b>，
     * 改完不回写、也看不到本轮还没收尾时写进去的条目（真机踩过：清单每轮都报「回读失败」）。
     */
    public static final String CTX_AGENT_STATE = "agentscopeAgentState";

    private final ToolCallback callback;
    /** 本次调用的落点（引用注册器/库界/权限档位等 per-call 上下文），刻意不做跨调用静态状态。 */
    private final Supplier<Map<String, Object>> contextSupplier;
    /** 本轮策略：按调用取（实例常驻，轮与轮之间档位可能变）；引擎传 slot 的当前轮，测试可传常量。 */
    private final Supplier<ExecutionPolicy> policy;
    /** 本轮是否要求「执行前问人」（智能体档位的审批模式为 ask 且该工具登记为需要审批）。 */
    private final boolean approvalRequired;
    /**
     * 框架引擎这条路上「问人」这件事是否真的有人能答。
     * <p>人在回路还没接框架确认事件之前必须为 false：返回 {@code ask} 会挂出一张没人能批的卡，
     * 整轮悬到超时——实测就是这个形状（无正文、无终态）。接上 {@code AgentSession.respond} 之后
     * 由引擎传 true，权限判定本身不改。
     */
    private final boolean approvalCanAsk;

    /** 固定档位入口（测试与「实例只活一轮」的调用方用）：等价于 {@code () -> policy}。 */
    public SpringAiToolAdapter(ToolCallback callback, Supplier<Map<String, Object>> contextSupplier,
                               ExecutionPolicy policy, boolean approvalRequired, boolean approvalCanAsk) {
        this(callback, contextSupplier, () -> policy, approvalRequired, approvalCanAsk);
    }

    public SpringAiToolAdapter(ToolCallback callback, Supplier<Map<String, Object>> contextSupplier,
                               Supplier<ExecutionPolicy> policy, boolean approvalRequired,
                               boolean approvalCanAsk) {
        super(callback.getToolDefinition().name(), callback.getToolDefinition().description(),
                schemaOf(callback), false, true, false, null, false, false);
        this.callback = callback;
        this.contextSupplier = contextSupplier;
        this.policy = policy;
        this.approvalRequired = approvalRequired;
        this.approvalCanAsk = approvalCanAsk;
    }

    /** 保守按「可能有副作用」登记（readOnly=false）：在这里猜只读的代价是越权写被当只读放行。 */
    private static Map<String, Object> schemaOf(ToolCallback callback) {
        String schema = callback.getToolDefinition().inputSchema();
        if (schema == null || schema.isBlank()) return Map.of("type", "object", "properties", Map.of());
        try {
            return JSON.parseObject(schema, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("工具 " + callback.getToolDefinition().name()
                    + " 的 inputSchema 不是合法 JSON: " + truncate(schema), e);
        }
    }

    /**
     * 本轮权限档位对本次调用的裁决，读的是问答侧同一份 {@link ExecutionPolicy}。
     * <p>没有档位一律拒绝（fail-closed）：框架引擎的调用路径上没有本轮状态对象，
     * 忘记传档位是最容易犯的错，而它的默认后果不该是「放行」。
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        ExecutionPolicy current = policy == null ? null : policy.get();
        if (current == null) {
            return Mono.just(PermissionDecision.deny("本轮未解析权限档位，已按最保守处理：" + getName()));
        }
        if (current.denies(getName())) {
            return Mono.just(PermissionDecision.deny("该工具已被本轮档位点名禁用：" + getName()));
        }
        PermissionMode mode = context == null ? null : context.getMode();
        if (mode == PermissionMode.EXPLORE) {
            // 只读调研档：本适配器一律按「可能有副作用」登记（readOnly 不猜），所以这一档里它不该跑。
            // 档位收紧的能力必须与「不存在」不可区分——这里拒掉，模型反复重试也拿不到结果。
            return Mono.just(PermissionDecision.deny("只读调研档不允许执行有副作用的工具：" + getName()));
        }
        if (approvalRequired && mode != PermissionMode.DONT_ASK) {
            if (!approvalCanAsk) {
                return Mono.just(PermissionDecision.deny("该工具需要执行前确认，而本轮回合没有可应答的在场通道："
                        + getName() + "（请改用无需确认的能力完成）"));
            }
            return Mono.just(PermissionDecision.ask("执行 " + getName() + " 需要你在场确认"));
        }
        return Mono.just(PermissionDecision.allow("允许"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
                    Map<String, Object> input = param.getInput() == null
                            ? Collections.emptyMap() : param.getInput();
                    // 每次调用现搭一份上下文：这把工具在同一轮里可能被并发调多次，
                    // 共用一个 Map 就是甲次调用读到乙次的框架状态
                    Map<String, Object> context = new HashMap<>(contextSupplier.get());
                    AgentState state = RuntimeContext.resolveAgentState(
                            param.getRuntimeContext(), param.getAgent());
                    if (state != null) context.put(CTX_AGENT_STATE, state);
                    String result = callback.call(JSON.toJSONString(input), new ToolContext(context));
                    return ToolResultBlock.text(result);
                })
                // 工具是真阻塞调用（检索/HTTP/沙盒等待），必须离开框架的响应式线程
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> e instanceof io.agentscope.core.tool.ToolSuspendException
                        // 挂起是框架的「等人回答」信号，必须原样交回框架去记待办；
                        // 在这里吞掉就变成一条普通工具失败，待办与接续执行都不会发生
                        ? Mono.error(e) : Mono.just(ToolResultBlock.error(rootMessage(e))));
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    private static String truncate(String s) {
        return s != null && s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}
