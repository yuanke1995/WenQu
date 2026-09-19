package com.wisesoft.wenqu.agents;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子智能体上下文（对应参考实现 {@code agents/buildin/subagent/context.py} 的
 * {@code SubAgentContext}）。
 *
 * <p>参考实现在 {@code BaseContext} 上叠加两个字段：
 * <ul>
 *   <li>{@code parent_thread_id}（默认 {@code None}，非可配置、隐藏）—— 父线程 ID；</li>
 *   <li>{@code is_subagent_runtime}（默认 {@code False}，非可配置、隐藏）—— 子智能体运行态标记，
 *       {@code install_skill} 等工具据此拒绝在子智能体中执行（见
 *       {@link SkillInstallTool#runInstallTask}）。</li>
 * </ul>
 *
 * <p>平台差异（必要替换）：同 {@link ChatBotContext} —— 经
 * {@link BaseContext#declareExtraField} 在构造期登记，不污染基类声明表。
 */
public class SubAgentContext extends BaseContext {

    /** 参考实现 {@code SubAgentContext.parent_thread_id} 字段名（逐字）。 */
    public static final String PARENT_THREAD_ID = "parent_thread_id";

    /** 参考实现 {@code SubAgentContext.is_subagent_runtime} 字段名（逐字）。 */
    public static final String IS_SUBAGENT_RUNTIME = "is_subagent_runtime";

    public SubAgentContext() {
        Map<String, Object> parentMetadata = new LinkedHashMap<>();
        parentMetadata.put("name", "父线程ID");
        parentMetadata.put("configurable", false);
        parentMetadata.put("hide", true);
        declareExtraField(PARENT_THREAD_ID, null, parentMetadata);

        Map<String, Object> runtimeMetadata = new LinkedHashMap<>();
        runtimeMetadata.put("name", "子智能体运行态");
        runtimeMetadata.put("configurable", false);
        runtimeMetadata.put("hide", true);
        declareExtraField(IS_SUBAGENT_RUNTIME, false, runtimeMetadata);
    }

    public String getParentThreadId() {
        return getString(PARENT_THREAD_ID);
    }

    public void setParentThreadId(String parentThreadId) {
        set(PARENT_THREAD_ID, parentThreadId);
    }

    /** 子智能体运行态（对应 {@code context.is_subagent_runtime}）。 */
    public boolean isSubagentRuntime() {
        return Boolean.TRUE.equals(get(IS_SUBAGENT_RUNTIME));
    }

    public void setSubagentRuntime(boolean value) {
        set(IS_SUBAGENT_RUNTIME, value);
    }
}
