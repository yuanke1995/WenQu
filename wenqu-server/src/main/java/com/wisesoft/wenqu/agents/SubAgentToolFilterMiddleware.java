package com.wisesoft.wenqu.agents;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;

/**
 * 子智能体工具隐藏/拒绝中间件（对应参考实现
 * {@code agents/buildin/subagent/graph.py} 的 {@code _SubAgentToolFilterMiddleware}）。
 *
 * <p>逐字对齐：
 * <ul>
 *   <li>{@link #SUBAGENT_DISABLED_TOOLS}＝{@code _SUBAGENT_DISABLED_TOOLS}
 *       ＝{@code {"present_artifacts", "ask_user_question", "install_skill"}}；</li>
 *   <li>{@link #SUBAGENT_DISABLED_TOOLS_DEFAULT_MODE}＝{@code _SUBAGENT_DISABLED_TOOLS_DEFAULT_MODE}
 *       ＝上述集合 ∪ {@link ToolApproval#SENSITIVE_BACKEND_TOOLS}；</li>
 *   <li>{@link #disabledToolsFor(String)}＝{@code _disabled_tools_for}：只有
 *       {@code always_trust} 走基础集合，其余（含边界已 normalize 的 {@code default}）走默认模式集合；</li>
 *   <li>{@link #filterDisabledToolNames}/{@link #filterDisabledTools}＝{@code _filter_disabled_tools}
 *       （模型调用面按<b>工具名</b>过滤，构图面按<b>工具对象</b>过滤）；</li>
 *   <li>工具调用拒绝文案与 {@code _denied_tool_message} 逐字一致，
 *       且以 error 状态回传（{@code ToolCallResponse.error} ⇒ {@code status="error"}）。</li>
 * </ul>
 *
 * <h3>必要替换（平台差异）</h3>
 * <ol>
 *   <li><b>模型面与工具面拆成两个类</b>：参考实现的该类同时实现
 *       {@code wrap_model_call/awrap_model_call} 与 {@code wrap_tool_call/awrap_tool_call}；
 *       Java 的 {@code ModelInterceptor} 与 {@code ToolInterceptor} 都是抽象类，
 *       单继承下无法同体，故本类继承 {@link ModelInterceptor}，工具面经
 *       {@link #asToolInterceptor()} 暴露内部 {@link DisabledToolCallInterceptor}
 *       （与 {@code SkillsMiddleware#asToolInterceptor} 同一解法）。构图方需<b>两者都注册</b>。</li>
 *   <li><b>「工具列表隐藏不构成执行边界」的注释语义保留</b>：显式传入的禁用工具调用同样被拒绝，
 *       不因工具不在列表里就放行。</li>
 * </ol>
 */
public class SubAgentToolFilterMiddleware extends ModelInterceptor {

    /** 参考实现 {@code _SUBAGENT_DISABLED_TOOLS}（逐字、含顺序口径的不可变集合）。 */
    public static final Set<String> SUBAGENT_DISABLED_TOOLS =
            Set.of("present_artifacts", "ask_user_question", "install_skill");

    /**
     * 参考实现 {@code _SUBAGENT_DISABLED_TOOLS_DEFAULT_MODE}：
     * 默认审批模式额外隐藏敏感 backend 工具，避免子智能体绕过主线程逐项审批。
     */
    public static final Set<String> SUBAGENT_DISABLED_TOOLS_DEFAULT_MODE;

    /** 参考实现 {@code _denied_tool_message} 的文案模板（逐字，{@code %s} 为工具名）。 */
    static final String DENIED_TOOL_TEXT_TEMPLATE =
            "工具 %s 在当前审批模式下对子智能体不可用；请把结果交回主智能体，由主线程按审批流程执行该操作。";

    static {
        Set<String> union = new LinkedHashSet<>(SUBAGENT_DISABLED_TOOLS);
        union.addAll(ToolApproval.SENSITIVE_BACKEND_TOOLS);
        SUBAGENT_DISABLED_TOOLS_DEFAULT_MODE = Set.copyOf(union);
    }

    /** 本实例生效的隐藏/拒绝工具集合（构造期按审批模式选定）。 */
    private final Set<String> disabledTools;

    public SubAgentToolFilterMiddleware(String toolApprovalMode) {
        // 调用方已在边界 normalize 过 mode，这里直接按值选择隐藏集合。
        this.disabledTools = disabledToolsFor(toolApprovalMode);
    }

    /** 对应 {@code _disabled_tools_for}。 */
    public static Set<String> disabledToolsFor(String mode) {
        if ("always_trust".equals(mode)) {
            return SUBAGENT_DISABLED_TOOLS;
        }
        return SUBAGENT_DISABLED_TOOLS_DEFAULT_MODE;
    }

    /** 本实例的隐藏/拒绝工具集合（供构图方核对）。 */
    public Set<String> getDisabledTools() {
        return disabledTools;
    }

    /** 对应 {@code _tool_name}：先按 map 的 {@code "name"} 键取，再按对象名取。 */
    public static String toolName(Object tool) {
        Object name = null;
        if (tool instanceof Map<?, ?> map) {
            name = map.get("name");
        } else if (tool instanceof ToolkitsRegistry.ToolDefinition definition) {
            name = definition.getName();
        } else if (tool instanceof ToolCallback callback) {
            org.springframework.ai.tool.definition.ToolDefinition definition = callback.getToolDefinition();
            name = definition == null ? null : definition.name();
        }
        return name instanceof String text ? text : null;
    }

    /** 对应 {@code _filter_disabled_tools}：按工具对象过滤（构图面用）。 */
    public static List<Object> filterDisabledTools(List<?> tools, Set<String> disabledTools) {
        List<Object> filtered = new ArrayList<>();
        if (tools == null) {
            return filtered;
        }
        for (Object tool : tools) {
            if (!disabledTools.contains(toolName(tool))) {
                filtered.add(tool);
            }
        }
        return filtered;
    }

    /** 对应 {@code _filter_disabled_tools}：按工具名过滤（模型调用面用，{@code request.tools}）。 */
    public static List<String> filterDisabledToolNames(List<String> tools, Set<String> disabledTools) {
        List<String> filtered = new ArrayList<>();
        if (tools == null) {
            return filtered;
        }
        for (String name : tools) {
            if (!disabledTools.contains(name)) {
                filtered.add(name);
            }
        }
        return filtered;
    }

    @Override
    public String getName() {
        return "subagent_tool_filter";
    }

    /** 对应 {@code wrap_model_call}：按隐藏集合改写本次模型可见的工具列表。 */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (request == null) {
            return handler.call(null);
        }
        ModelRequest updated = ModelRequest.builder(request)
                .tools(filterDisabledToolNames(request.getTools(), disabledTools))
                .build();
        return handler.call(updated);
    }

    /**
     * 工具侧拦截器（对应参考实现同一个类的 {@code wrap_tool_call}）。
     *
     * <p>见类注释「必要替换 1」：构图方需要把本拦截器与
     * {@link SubAgentToolFilterMiddleware} 本身<b>一并注册</b>。
     */
    public ToolInterceptor asToolInterceptor() {
        return new DisabledToolCallInterceptor(this);
    }

    /** 禁用工具调用的拒绝实现（对应 {@code wrap_tool_call} 的拒绝分支）。 */
    public static final class DisabledToolCallInterceptor extends ToolInterceptor {

        private final SubAgentToolFilterMiddleware owner;

        DisabledToolCallInterceptor(SubAgentToolFilterMiddleware owner) {
            this.owner = owner;
        }

        @Override
        public String getName() {
            return "subagent_tool_filter";
        }

        @Override
        public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
            String denial = owner.deniedToolMessage(request);
            return denial == null ? handler.call(request) : ToolCallResponse.error(
                    request.getToolCallId(), request.getToolName(), denial);
        }
    }

    /** 对应 {@code _denied_tool_message}：禁用调用返回绑定原 tool call 的拒绝文本，否则 null。 */
    String deniedToolMessage(ToolCallRequest request) {
        if (request == null) {
            return null;
        }
        String name = request.getToolName();
        if (name == null || !disabledTools.contains(name)) {
            return null;
        }
        return DENIED_TOOL_TEXT_TEMPLATE.formatted(name);
    }
}
