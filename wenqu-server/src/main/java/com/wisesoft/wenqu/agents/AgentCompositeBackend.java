package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Agent 运行时 CompositeBackend 构造（对应参考实现 agents/backends/composite.py）。
 *
 * <p>逐字对齐：文件系统工具 allowlist {@code _AGENT_FS_TOOLS}（显式排除 destructive delete）、
 * {@code _BackendScope} 数据类（runtime_scope_id / workdir_relative_path / uid 三字段 +
 * workdir_path / artifacts_root 两个派生 + from_sources 多源解析）、三个模块级工厂函数
 * （sync_agent_context_skills / create_agent_composite_backend / create_agent_filesystem_middleware）。
 * 工具结果裁剪豁免集 {@code _TOOL_RESULT_EVICTION_EXEMPT_TOOLS} 落在
 * {@link FilesystemMiddleware#TOOL_RESULT_EVICTION_EXEMPT_TOOLS}（该类即参考实现的
 * {@code FilesystemMiddleware}，同属 composite.py 模块）。
 *
 * <h3>构件映射（引擎面已具备等价构件，故本模块为「真接线」而非配置载体）</h3>
 * <table border="1">
 *   <caption>参考实现 → 本工程</caption>
 *   <tr><th>参考实现（deepagents / 本模块）</th><th>本工程</th></tr>
 *   <tr><td>{@code CompositeBackend(default=..., routes={}, artifacts_root=...)}</td>
 *       <td>default → {@link ProvisionerSandboxBackend}；{@code artifacts_root} →
 *           {@link BackendScope#artifactsRoot()}；{@code routes={}} 为空路由表，
 *           引擎面无 CompositeBackend 实体亦无语义损失（见下能力差异 1）</td></tr>
 *   <tr><td>{@code FilesystemMiddleware}（工具供给 + 大结果裁剪）</td>
 *       <td>{@link FilesystemMiddleware}（裁剪，ToolInterceptor）+ 工具注册处（工具供给，见能力差异 2）</td></tr>
 *   <tr><td>{@code FilesystemMiddleware.wrap_tool_call}</td>
 *       <td>{@code ToolInterceptor.interceptToolCall}（框架 spring-ai-alibaba agent-framework）</td></tr>
 *   <tr><td>{@code refresh_user_skill_projection_async}</td>
 *       <td>{@link SkillService#refreshUserSkillProjectionAsync}</td></tr>
 *   <tr><td>{@code runtime_workdir_path}</td><td>{@link BackendPaths#runtimeWorkdirPath}</td></tr>
 * </table>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li>{@code deepagents.backends.CompositeBackend} 未照搬：本调用点 {@code routes={}}，
 *       其唯一实际作用是「default + artifacts_root」聚合，已由
 *       {@link BackendScope#createBackend} 与 {@link BackendScope#artifactsRoot()} 分别承载；
 *       若将来出现非空 routes，需另建路由 backend（当前无此需求）。</li>
 *   <li>{@code deepagents.middleware.filesystem.FilesystemMiddleware} 未照搬：其工具本体（绑定
 *       backend 的 ls/read_file/write_file/edit_file/glob/grep/execute）属第三方实现；框架的
 *       {@code FilesystemInterceptor} 提供的是<b>本地文件系统</b>工具且 builder 的 backend 字段未被使用，
 *       不能承载沙盒 backend，故不采用。本模块保留 {@code _AGENT_FS_TOOLS} 声明（去 disabled_tools）
 *       交给工具注册处，由后者构建绑定 {@link ProvisionerSandboxBackend} 的回调。</li>
 *   <li>{@code create_agent_filesystem_middleware} 的返回对象在参考实现里一个中间件同时承担
 *       「工具供给」与「大结果裁剪」；本工程拆为两处（裁剪在
 *       {@link FilesystemMiddleware}，工具供给在注册处），故本方法只返回裁剪拦截器。</li>
 * </ol>
 */
@Service
public class AgentCompositeBackend {

    /** 文件工具 allowlist：显式排除 destructive delete（参考实现未实现 delete，且删除需审批/审计）。 */
    public static final List<String> AGENT_FS_TOOLS = List.of(
            "ls", "read_file", "write_file", "edit_file", "glob", "grep", "execute");

    private final SkillService skillService;
    private final ProvisionerSandboxProvider sandboxProvider;

    public AgentCompositeBackend(SkillService skillService, ProvisionerSandboxProvider sandboxProvider) {
        this.skillService = skillService;
        this.sandboxProvider = sandboxProvider;
    }

    /** 在 Agent Run 初始化时同步当前用户获授权的共享 Skill 投影（对应 sync_agent_context_skills）。 */
    public void syncAgentContextSkills(BaseContext context) {
        BackendScope scope = BackendScope.fromSources(context, "runtime context");
        skillService.refreshUserSkillProjectionAsync(scope.getUid());
    }

    /** 按已准备的 Agent context 构造本 Run 独享的 backend（对应 create_agent_composite_backend）。 */
    public ProvisionerSandboxBackend createAgentCompositeBackend(BaseContext context) {
        return BackendScope.fromSources(context, "agent context").createBackend(sandboxProvider);
    }

    /**
     * 取当前 context 的 {@code artifacts_root}（= {@code <runtime workdir>/outputs}）。
     * 参考实现从 CompositeBackend 实例读取该值，本工程无该实体，故显式暴露给中间件装配处。
     */
    public static String artifactsRoot(BaseContext context) {
        return BackendScope.fromSources(context, "agent context").artifactsRoot();
    }

    /**
     * 构造文件系统中间件（对应 create_agent_filesystem_middleware），在工具注册前排除禁用工具。
     *
     * @param toolTokenLimitBeforeEvict 大工具结果裁剪预算；null 表示关闭裁剪
     * @param backend 本 Run 独享的沙盒 backend（来自 {@link #createAgentCompositeBackend}）
     * @param artifactsRoot 本 Run 的 artifacts 根（来自 {@link #artifactsRoot}）
     * @param disabledTools 需排除的文件工具名集合（参考实现 {@code disabled_tools=frozenset()}）
     */
    public FilesystemMiddleware createAgentFilesystemMiddleware(
            Integer toolTokenLimitBeforeEvict,
            ProvisionerSandboxBackend backend,
            String artifactsRoot,
            Set<String> disabledTools) {
        Set<String> disabled = disabledTools == null ? Set.of() : disabledTools;
        List<String> tools = new ArrayList<>();
        for (String name : AGENT_FS_TOOLS) {
            if (!disabled.contains(name)) {
                tools.add(name);
            }
        }
        return new FilesystemMiddleware(toolTokenLimitBeforeEvict, backend, artifactsRoot, tools);
    }

    // ==================== _BackendScope ====================

    /** 对应参考实现 _BackendScope（frozen dataclass）。 */
    public static final class BackendScope {
        private final String runtimeScopeId;
        private final String workdirRelativePath;
        private final String uid;

        public BackendScope(String runtimeScopeId, String workdirRelativePath, String uid) {
            this.runtimeScopeId = runtimeScopeId;
            this.workdirRelativePath = workdirRelativePath;
            this.uid = uid;
        }

        public String getRuntimeScopeId() {
            return runtimeScopeId;
        }

        public String getWorkdirRelativePath() {
            return workdirRelativePath;
        }

        public String getUid() {
            return uid;
        }

        /** 对应 workdir_path property：runtime 绝对路径。 */
        public String workdirPath() {
            return BackendPaths.runtimeWorkdirPath(workdirRelativePath);
        }

        /** 对应 artifacts_root：workdir 下的 outputs 目录（供 Filesystem/Summarization 中间件派生前缀）。 */
        public String artifactsRoot() {
            return workdirPath().replaceAll("/+$", "") + "/outputs";
        }

        /**
         * 从上下文抽取 thread_id / uid / runtime_scope_id / workdir_relative_path。
         * 对应 from_sources(*sources, error_context=...)。
         */
        public static BackendScope fromSources(BaseContext context, String errorContext) {
            String threadId = stringValue(context, "thread_id");
            if (threadId == null) {
                throw new IllegalArgumentException("thread_id is required in " + errorContext);
            }
            String uid = stringValue(context, "uid");
            if (uid == null) {
                throw new IllegalArgumentException("uid is required in " + errorContext);
            }
            String runtimeScopeId = stringValue(context, "runtime_scope_id");
            if (runtimeScopeId == null) {
                runtimeScopeId = threadId;
            }
            String relativePath = stringValue(context, "workdir_relative_path");
            if (relativePath == null) {
                relativePath = "";
            }
            return new BackendScope(runtimeScopeId, relativePath, uid);
        }

        /**
         * 构造本 Run 独享的 backend（对应 create_backend）。
         *
         * <p>参考实现此处 {@code create_if_missing=True}，{@code inherit_env} 取构造默认值 True，
         * 两者等价于本工程的 {@code (true, true)}。
         */
        public ProvisionerSandboxBackend createBackend(ProvisionerSandboxProvider sandboxProvider) {
            if (workdirRelativePath == null || workdirRelativePath.isEmpty()) {
                throw new IllegalArgumentException("workdir path is required in runtime context");
            }
            return new ProvisionerSandboxBackend(sandboxProvider, runtimeScopeId, uid, workdirRelativePath, true, true);
        }

        private static String stringValue(BaseContext source, String key) {
            String value = source.getString(key);
            if (value == null) {
                return null;
            }
            value = value.strip();
            return value.isEmpty() ? null : value;
        }
    }
}
