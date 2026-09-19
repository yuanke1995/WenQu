package com.wisesoft.wenqu.agents;

import com.alibaba.cloud.ai.graph.agent.extension.interceptor.LargeResultEvictionInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.wenqu.agents.backends.sandbox.SandboxFilesystemBackendAdapter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文件系统中间件（对应参考实现 {@code agents/backends/composite.py} 的
 * {@code YuxiFilesystemMiddleware}，类名去掉参考实现品牌前缀）。
 *
 * <p>参考实现继承第三方 {@code deepagents.middleware.filesystem.FilesystemMiddleware}，
 * 覆写 {@code wrap_tool_call} / {@code awrap_tool_call}，在工具结果进入模型上下文前做预算裁剪，
 * 并对豁免工具集直接放行。本工程没有该框架，映射到引擎面（spring-ai-alibaba agent-framework）
 * 的等价构件：
 *
 * <table border="1">
 *   <caption>构件映射</caption>
 *   <tr><th>参考实现</th><th>本工程</th></tr>
 *   <tr><td>{@code wrap_tool_call(request, handler)}</td>
 *       <td>{@link ToolInterceptor#interceptToolCall(ToolCallRequest, ToolCallHandler)}
 *           （{@code ToolCallHandler} 即 {@code handler}）</td></tr>
 *   <tr><td>{@code request.tool_call["name"]}</td>
 *       <td>{@link ToolCallRequest#getToolName()}</td></tr>
 *   <tr><td>{@code self._tool_token_limit_before_evict}</td>
 *       <td>{@link #toolTokenLimitBeforeEvict}（构造参数）</td></tr>
 *   <tr><td>{@code self._intercept_large_tool_result(result)}</td>
 *       <td>{@link LargeResultEvictionInterceptor#interceptToolCall}（框架对同一 Python 语义的实现：
 *           「首次 10 行 + 行号 + 落盘 + 指针文案」）</td></tr>
 *   <tr><td>{@code awrap_tool_call}</td>
 *       <td>无独立异步面：引擎的 {@code ToolCallHandler} 无 async 变体（工具调用在图内同步完成）</td></tr>
 * </table>
 *
 * <p>{@code interceptToolCall} 的分支顺序与参考实现逐行对齐：先取结果，再判豁免集，再判预算开关，
 * 最后才做裁剪。委托框架裁剪时把下游 handler 换成「返回已算好的结果」，<b>避免工具被重复执行</b>
 * （对应参考实现直接传 {@code tool_result} 而非再次调用 {@code handler}）。
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>豁免集来源</b>：参考实现的 {@code TOOLS_EXCLUDED_FROM_EVICTION} 属 deepagents 私有常量，
 *       源码不可得。框架侧对同一 Python 行为的移植声明了 6 个内建文件工具
 *       （{@code excludeFilesystemTools()}：ls / read_file / write_file / edit_file / glob / grep，
 *       <b>不含 execute</b>）。本类以该 6 项 + 本项目增量 {@code open_kb_document} 作为豁免集，
 *       并以同一集合配置框架裁剪器，两层判定一致。</li>
 *   <li><b>工具供给未随本类落地</b>：参考实现里 {@code tools=[...]}` 由被继承的
 *       FilesystemMiddleware 实例化工具本体（绑定 backend）。框架的 {@code FilesystemInterceptor}
 *       提供的是<b>本地文件系统</b>工具（{@code LocalFilesystemBackend} / {@code FileSystemTools}），
 *       其 builder 的 {@code backend} 字段未被使用，无法承载沙盒 backend；故本类不包裹它，
 *       只保留 {@link #getTools()} 这一「允许/禁用工具」声明（{@code _AGENT_FS_TOOLS} 去 disabled），
 *       供工具注册处构建<b>绑定 {@link ProvisionerSandboxBackend} 的</b>工具回调时消费。
 *       模型可见工具集因此由注册处收敛，而非由本拦截器收敛。</li>
 *   <li><b>delete 不在允许集</b>：参考实现显式排除 destructive delete；框架文件工具集本身无 delete
 *       （{@code _AGENT_FS_TOOLS} 里的 {@code execute} 亦不在框架文件工具集内），两者方向一致。</li>
 *   <li><b>裁剪器落盘路径</b>：框架 {@code LargeResultEvictionInterceptor} 的落盘目录硬编码为
 *       {@code user.dir/large_tool_results/}，经 {@link SandboxFilesystemBackendAdapter} 重映射到
 *       {@code <artifacts_root>/large_tool_results/}（详见该适配器注释）。</li>
 * </ol>
 */
public class FilesystemMiddleware extends ToolInterceptor {

    /**
     * 工具结果裁剪豁免集（对应参考实现 {@code _TOOL_RESULT_EVICTION_EXEMPT_TOOLS}）。
     *
     * <p>＝ deepagents 内建豁免集（此处取框架 {@code excludeFilesystemTools()} 声明的 6 个文件工具）
     * ∪ 本项目增量 {@code open_kb_document}。豁免知识库文档工具是为了避免 read_file/offload 循环：
     * 该工具自带分页与引用语义。
     */
    public static final Set<String> TOOL_RESULT_EVICTION_EXEMPT_TOOLS;

    /** 本项目在内建豁免集之上追加的工具（参考实现里的字面增量）。 */
    public static final Set<String> EXTRA_TOOL_RESULT_EVICTION_EXEMPT_TOOLS = Set.of("open_kb_document");

    static {
        Set<String> exempt = new LinkedHashSet<>(List.of(
                "ls", "read_file", "write_file", "edit_file", "glob", "grep"));
        exempt.addAll(EXTRA_TOOL_RESULT_EVICTION_EXEMPT_TOOLS);
        TOOL_RESULT_EVICTION_EXEMPT_TOOLS = Collections.unmodifiableSet(exempt);
    }

    /** 对应参考实现基类属性 {@code _tool_token_limit_before_evict}；null 表示关闭裁剪。 */
    private final Integer toolTokenLimitBeforeEvict;

    /** 框架侧「大工具结果裁剪」实现（等价 {@code _intercept_large_tool_result}）。 */
    private final LargeResultEvictionInterceptor evictionInterceptor;

    /** 沙盒 backend 适配器（承载落盘路径与 backend 句柄，供诊断与后续工具注册处复用）。 */
    private final SandboxFilesystemBackendAdapter backend;

    /** 允许模型使用的文件工具（{@code _AGENT_FS_TOOLS} 去掉 disabled_tools）。 */
    private final List<String> tools;

    public FilesystemMiddleware(
            Integer toolTokenLimitBeforeEvict,
            ProvisionerSandboxBackend backend,
            String artifactsRoot,
            List<String> tools) {
        this.toolTokenLimitBeforeEvict = toolTokenLimitBeforeEvict;
        this.backend = new SandboxFilesystemBackendAdapter(backend, artifactsRoot);
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.evictionInterceptor = LargeResultEvictionInterceptor.builder()
                .toolTokenLimitBeforeEvict(toolTokenLimitBeforeEvict)
                .excludeFilesystemTools()
                .excludeTool("open_kb_document")
                .backend(this.backend)
                .build();
    }

    /**
     * 重写工具调用拦截（对应参考实现 {@code wrap_tool_call}）：
     * 取结果 → 豁免集放行 → 预算开关放行 → 裁剪。分支顺序与参考实现逐行对齐。
     */
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        ToolCallResponse toolResult = handler.call(request);

        if (TOOL_RESULT_EVICTION_EXEMPT_TOOLS.contains(request.getToolName())) {
            return toolResult;
        }
        if (toolTokenLimitBeforeEvict == null) {
            return toolResult;
        }

        // 下游 handler 换成「返回已算好的结果」，避免工具被重复执行（对应 _intercept_large_tool_result(tool_result)）。
        return evictionInterceptor.interceptToolCall(request, ignored -> toolResult);
    }

    @Override
    public String getName() {
        // 参考实现继承 deepagents FilesystemMiddleware，名字沿用 "Filesystem"。
        return "Filesystem";
    }

    public Integer getToolTokenLimitBeforeEvict() {
        return toolTokenLimitBeforeEvict;
    }

    /** 允许模型使用的文件工具（供绑定沙盒 backend 的工具注册处消费）。 */
    public List<String> getTools() {
        return tools;
    }

    public SandboxFilesystemBackendAdapter getBackend() {
        return backend;
    }

    /** 该工具结果是否豁免裁剪（对应参考实现豁免判定）。 */
    public static boolean isEvictionExempt(String toolName) {
        return TOOL_RESULT_EVICTION_EXEMPT_TOOLS.contains(toolName);
    }

    /** 是否启用裁剪（tool_token_limit_before_evict 非空）。 */
    public boolean evictionEnabled() {
        return toolTokenLimitBeforeEvict != null;
    }
}
