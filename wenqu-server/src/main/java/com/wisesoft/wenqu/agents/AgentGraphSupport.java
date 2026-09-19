package com.wisesoft.wenqu.agents;

import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxBackend;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

/**
 * 内置智能体构图装配的公共小工具（对应参考实现
 * {@code agents/buildin/chatbot/graph.py} 与 {@code agents/buildin/subagent/graph.py}
 * 两个 {@code get_graph} 里重复出现的那几行）。
 *
 * <p>本类是<b>纯抽取</b>，不引入新的语义：每个方法都指明它对应参考实现的哪一行。
 *
 * <h3>逐条对位</h3>
 * <ul>
 *   <li>{@link #isRuntimePrepared} ← {@code if not getattr(context, "_runtime_prepared", False)}</li>
 *   <li>{@link #toolTokenLimitBeforeEvict} ←
 *       {@code getattr(context, "tool_token_limit", DEFAULT_TOOL_RESULT_EVICTION_K_TOKENS) * 1024}</li>
 *   <li>{@link #loadModel} ← {@code load_chat_model(fully_specified_name=resolve_chat_model_spec(context.model), session_id=context.thread_id)}</li>
 *   <li>{@link #promptContext} ← {@code build_prompt_with_context(context)} 读取的字段面</li>
 *   <li>{@link #toToolCallbacks} ← {@code tools=await resolve_configured_runtime_tools(context)} 的返回值</li>
 * </ul>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>{@code tool_token_limit} 缺字段时取默认值而非报错</b>：参考实现用
 *       {@code getattr(..., DEFAULT)}，但若该属性存在且值为 {@code None}，
 *       {@code None * 1024} 会抛 {@code TypeError}；本方法对非数值一律回落
 *       {@link BaseContext#DEFAULT_TOOL_RESULT_EVICTION_K_TOKENS}，避免构图因脏配置崩溃。</li>
 *   <li><b>{@code toToolCallbacks} 会丢弃非 {@link ToolCallback} 的工具</b>：参考实现的工具
 *       对象天然可被 langchain 执行；本工程只有实现 Spring AI {@link ToolCallback} 的
 *       {@link ToolkitsRegistry.ToolDefinition} 才能进图，其余（纯声明）需由工具注册处补齐，
 *       此处仅做投影并<b>不静默补默认实现</b>。</li>
 * </ol>
 */
final class AgentGraphSupport {

    private AgentGraphSupport() {
    }

    /** 构图前置条件：Context 必须已准备（对应 {@code _runtime_prepared} 检查）。 */
    static boolean isRuntimePrepared(BaseContext context) {
        return context != null
                && Boolean.TRUE.equals(
                        context.getDynamic(AgentContextService.RUNTIME_PREPARED_ATTR, false));
    }

    /**
     * 大工具结果裁剪预算（token）。
     *
     * @see AgentGraphSupport 能力差异 1
     */
    static Integer toolTokenLimitBeforeEvict(BaseContext context) {
        Object raw = context == null ? null : context.getDynamic("tool_token_limit", null);
        int kTokens = raw instanceof Number number
                ? number.intValue()
                : BaseContext.DEFAULT_TOOL_RESULT_EVICTION_K_TOKENS;
        return kTokens * 1024;
    }

    /** 本次 Run 的模型（对应 {@code resolve_chat_model_spec} + {@code load_chat_model} 两行）。 */
    static ChatModel loadModel(AgentChatModel agentChatModel, BaseContext context) {
        String spec = AgentChatModel.resolveChatModelSpec(
                context == null ? null : context.getString("model"), null);
        return agentChatModel.loadChatModel(spec, context == null ? null : context.getString("thread_id"));
    }

    /** {@code build_prompt_with_context(context)} 读取的字段面适配（{@code workdir_path} / {@code system_prompt}）。 */
    static ChatbotPrompt.PromptContext promptContext(BaseContext context) {
        return new ChatbotPrompt.PromptContext() {
            @Override
            public String getWorkdirPath() {
                return context == null ? null : context.getString("workdir_path");
            }

            @Override
            public String getSystemPrompt() {
                return context == null ? null : context.getString("system_prompt");
            }
        };
    }

    /**
     * 把已解析的运行时工具投影为图可执行的回调列表。
     *
     * @see AgentGraphSupport 能力差异 2
     */
    static List<ToolCallback> toToolCallbacks(List<?> tools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (tools == null) {
            return callbacks;
        }
        for (Object tool : tools) {
            if (tool instanceof ToolCallback callback) {
                callbacks.add(callback);
            }
        }
        return callbacks;
    }

    /** 收集拦截器的可读名称（供构图日志/核对用，顺序与注册顺序一致）。 */
    static List<String> interceptorNames(List<? extends Interceptor> interceptors) {
        List<String> names = new ArrayList<>();
        for (Interceptor interceptor : interceptors) {
            names.add(interceptor.getName());
        }
        return names;
    }

    /** 文件系统中间件注册所需的沙盒 backend 断言（构图期把 null 提前拦成明确错误）。 */
    static ProvisionerSandboxBackend requireBackend(ProvisionerSandboxBackend backend) {
        if (backend == null) {
            throw new IllegalStateException("构图需要本 Run 独享的沙盒 backend（create_agent_composite_backend 返回空）");
        }
        return backend;
    }
}
