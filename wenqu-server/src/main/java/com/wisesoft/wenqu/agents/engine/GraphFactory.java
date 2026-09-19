package com.wisesoft.wenqu.agents.engine;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.wisesoft.wenqu.agents.BaseContext;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * 图装配工厂：把参考实现的
 * {@code create_agent(model=…, tools=…, system_prompt=…, middleware=…, state_schema=…, checkpointer=…)}
 * 参数面翻译为 spring-ai-alibaba 的 {@code ReactAgent.Builder}。
 *
 * <h3>逐参对齐</h3>
 * <table>
 *   <tr><th>{@code create_agent}</th><th>本工厂</th></tr>
 *   <tr><td>{@code model=load_chat_model(…)}</td><td>{@link Builder#model(ChatModel)}</td></tr>
 *   <tr><td>{@code tools=resolve_configured_runtime_tools(context)}</td>
 *       <td>{@link Builder#tools(List)}（{@link ToolCallback}）</td></tr>
 *   <tr><td>{@code system_prompt=build_prompt_with_context(context)}</td>
 *       <td>{@link Builder#systemPrompt(String)}（引擎侧由 {@code InstructionAgentHook} 注入）</td></tr>
 *   <tr><td>{@code middleware=[…]}</td>
 *       <td>{@link Builder#hooks(List)} + {@link Builder#interceptors(List)}：
 *           LangChain 的 {@code AgentMiddleware} 在 Java 侧拆成 hook（生命周期位置）
 *           与 interceptor（模型/工具调用包裹）两类，见 {@code agents/middlewares} 的逐项映射</td></tr>
 *   <tr><td>{@code state_schema=ChatBotState}</td>
 *       <td>引擎侧 {@code ReactAgent} 固定 {@code messages}+{@code AppendStrategy}；
 *           本项目额外 state 键（{@code artifacts}/{@code todos}/…）随 middlewares 装配时注册
 *           {@code KeyStrategy}，见 {@code AgentState}</td></tr>
 *   <tr><td>{@code checkpointer=await self._get_checkpointer()}</td>
 *       <td>{@link Builder#saver(BaseCheckpointSaver)}（{@code MysqlSaver}）</td></tr>
 * </table>
 *
 * <h3>必要替换（平台差异，显式标注）</h3>
 * <ul>
 *   <li><b>递归上限必须在这里设，不能逐次调用传</b>：{@code create_agent} 的
 *       {@code recursion_limit} 由 LangGraph 在 invoke 时读取 config；
 *       spring-ai-alibaba 只有编译期 {@link CompileConfig#recursionLimit()}。
 *       故 {@link Builder#recursionLimit(int)} 的取值来源是
 *       {@code BaseAgent.recursionLimitFromContext(context, DEFAULT_MAX_EXECUTION_STEPS)}
 *       （即参考实现放进 config 的同一个值），由调用方在建图时传入。</li>
 *   <li><b>默认值差异</b>：本工厂默认 {@link BaseContext#DEFAULT_MAX_EXECUTION_STEPS}（300）
 *       以保持本工程既有口径；LangGraph 自带默认是 25。
 *       另注意 {@code ReactAgent.Builder} 在未显式给 {@link CompileConfig} 时会把上限设成
 *       {@code Integer.MAX_VALUE} —— 本工厂<b>总是</b>显式给 config，避免落到该默认。</li>
 *   <li><b>{@code CompositeBackend} 一类 backend factory 参数</b>在 DeepAgents 0.7 已移除，
 *       参考实现改为"每次构图创建 Run 独享 backend 实例"；本工厂同样按"每次构图一个新实例"
 *       的调用约定工作，不持有 backend。</li>
 * </ul>
 */
public final class GraphFactory {

    private GraphFactory() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@code create_agent(...)} 的等价入口。 */
    public static final class Builder {

        private String name;
        private String description;
        private String instruction;
        private String systemPrompt;
        private ChatModel model;
        private ChatOptions chatOptions;
        private final List<ToolCallback> tools = new ArrayList<>();
        private final List<Hook> hooks = new ArrayList<>();
        private final List<Interceptor> interceptors = new ArrayList<>();
        private BaseCheckpointSaver saver;
        private int recursionLimit = BaseContext.DEFAULT_MAX_EXECUTION_STEPS;
        private ObservationRegistry observationRegistry;

        /** 对应 {@code create_agent} 的 graph 名（用于事件命名空间）。 */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** 对应 {@code ChatbotAgent.instruction} / {@code Agent.instruction}。 */
        public Builder instruction(String instruction) {
            this.instruction = instruction;
            return this;
        }

        /** 对应 {@code create_agent(system_prompt=…)}。 */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        public Builder chatOptions(ChatOptions chatOptions) {
            this.chatOptions = chatOptions;
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            if (tools != null) {
                this.tools.addAll(tools);
            }
            return this;
        }

        /** LangChain {@code AgentMiddleware} 中"生命周期位置"那一面（before/after agent|model）。 */
        public Builder hooks(List<? extends Hook> hooks) {
            if (hooks != null) {
                this.hooks.addAll(hooks);
            }
            return this;
        }

        /** LangChain {@code AgentMiddleware} 中"调用包裹"那一面（wrap_model_call / wrap_tool_call）。 */
        public Builder interceptors(List<? extends Interceptor> interceptors) {
            if (interceptors != null) {
                this.interceptors.addAll(interceptors);
            }
            return this;
        }

        /** 对应 {@code checkpointer=await self._get_checkpointer()}。 */
        public Builder saver(BaseCheckpointSaver saver) {
            this.saver = saver;
            return this;
        }

        /**
         * 递归上限（<b>编译期</b>生效）。
         *
         * @see GraphFactory 类注释「必要替换」
         */
        public Builder recursionLimit(int recursionLimit) {
            if (recursionLimit <= 0) {
                throw new IllegalArgumentException("recursionLimit 必须为正数");
            }
            this.recursionLimit = recursionLimit;
            return this;
        }

        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
            return this;
        }

        /** 构图并编译，返回可直接驱动 {@code BaseAgent} 的端口实现。 */
        public GraphPort build() {
            if (model == null) {
                throw new IllegalArgumentException("model 不能为空（对应 create_agent 的 model 必填）");
            }
            CompileConfig.Builder compileConfig = CompileConfig.builder()
                    .recursionLimit(recursionLimit);
            if (saver != null) {
                compileConfig.saverConfig(SaverConfig.builder().register(saver).build());
            }
            if (observationRegistry != null) {
                compileConfig.observationRegistry(observationRegistry);
            }

            // ReactAgent.builder() 返回的是 graph.agent 包下的抽象 Builder（非 ReactAgent 内部类）；
            // 用全限定名以免与本工厂的嵌套 Builder 同名遮蔽。
            com.alibaba.cloud.ai.graph.agent.Builder agentBuilder = ReactAgent.builder()
                    .name(name)
                    .description(description)
                    .model(model)
                    .systemPrompt(systemPrompt)
                    .instruction(instruction)
                    .compileConfig(compileConfig.build());
            if (!tools.isEmpty()) {
                agentBuilder.tools(tools);
            }
            if (!hooks.isEmpty()) {
                agentBuilder.hooks(hooks);
            }
            if (!interceptors.isEmpty()) {
                agentBuilder.interceptors(interceptors);
            }
            if (chatOptions != null) {
                agentBuilder.chatOptions(chatOptions);
            }
            // 引擎 CompileConfig 默认就带 MemorySaver，无法自证是否显式注册过持久化 saver，
            // 故由这里把"传了 saver"这一事实显式传下去（见 hasPersistentCheckpointer）。
            return GraphPort.of(agentBuilder.build(), saver != null);
        }
    }
}
