package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxProvider;
import com.wisesoft.wenqu.agents.backends.sandbox.SandboxFsBackend;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Agent 运行时 CompositeBackend 构造（对应参考实现 agents/backends/composite.py）。
 *
 * <p>逐字对齐：文件系统工具 allowlist {@code _AGENT_FS_TOOLS}（显式排除 destructive delete）、
 * 工具结果裁剪豁免集 {@code _TOOL_RESULT_EVICTION_EXEMPT_TOOLS}（在 deepagents 内建豁免之上额外
 * 豁免 open_kb_document，避免 read_file/offload 循环）、{@code _BackendScope} 数据类
 * （runtime_scope_id / workdir_relative_path / uid 三字段 + workdir_path 派生 +
 * from_sources 多源解析）、三个模块级工厂函数
 * （sync_agent_context_skills / create_agent_composite_backend / create_agent_filesystem_middleware）。
 *
 * <h3>能力差异（显式标注）</h3>
 * <ul>
 *   <li>{@code deepagents.backends.CompositeBackend} 与 {@code FilesystemMiddleware} 在本工程
 *       未照搬（框架已由 spring-ai-alibaba 桥接）。本工程的文件系统工具以注册到图引擎的
 *       ToolCallback 承载，不再有 CompositeBackend 这一概念实体；故 {@code createBackend()}
 *       直接返回 {@link ProvisionerSandboxBackend}（实现 {@link SandboxFsBackend} 契约），
 *       deepagents 的 routes / artifacts_root 包裹属能力差异（artifacts_root 仅作为
 *       {@link BackendScope#artifactsRoot()} 派生值保留，供后续 Filesystem/Summarization
 *       中间件等价体参考）。</li>
 *   <li>deepagents 内建 {@code TOOLS_EXCLUDED_FROM_EVICTION} 的具体成员未知，本工程仅落地
 *       已知的 Yuxi 增量（{@code open_kb_document}）；与 deepagents 基类的并集在引擎面
 *       FilesystemMiddleware 等价体里按框架既定豁免集处理。</li>
 *   <li>{@code refresh_user_skill_projection_async} → {@link SkillService#refreshUserSkillProjectionAsync}。</li>
 *   <li>{@code runtime_workdir_path} → {@link BackendPaths#runtimeWorkdirPath}。</li>
 * </ul>
 */
@Service
public class AgentCompositeBackend {

    /** 文件工具 allowlist：显式排除 destructive delete（参考实现未实现 delete，且删除需审批/审计）。 */
    public static final List<String> AGENT_FS_TOOLS = List.of(
            "ls", "read_file", "write_file", "edit_file", "glob", "grep", "execute");

    /**
     * 工具结果裁剪豁免集：在 deepagents 内建豁免之上额外豁免 open_kb_document。
     * 能力差异：deepagents 基类的 {@code TOOLS_EXCLUDED_FROM_EVICTION} 成员未知，此处仅落已知增量；
     * 与基类并集在引擎面 FilesystemMiddleware 等价体按框架既定豁免集处理。
     */
    public static final Set<String> TOOL_RESULT_EVICTION_EXEMPT_TOOLS =
            Collections.unmodifiableSet(new HashSet<>(Set.of("open_kb_document")));

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
    public SandboxFsBackend createAgentCompositeBackend(BaseContext context) {
        return BackendScope.fromSources(context, "agent context").createBackend(sandboxProvider);
    }

    /**
     * 构造文件系统中间件（对应 create_agent_filesystem_middleware）。
     * 在 ToolNode 注册前排除禁用工具；实际工具结果 token 裁剪由引擎面承载（见 YuxiFilesystemMiddleware）。
     */
    public YuxiFilesystemMiddleware createAgentFilesystemMiddleware(
            Integer toolTokenLimitBeforeEvict, SandboxFsBackend backend, Set<String> disabledTools) {
        Set<String> disabled = disabledTools == null ? Set.of() : disabledTools;
        List<String> tools = new ArrayList<>();
        for (String name : AGENT_FS_TOOLS) {
            if (!disabled.contains(name)) {
                tools.add(name);
            }
        }
        return new YuxiFilesystemMiddleware(backend, toolTokenLimitBeforeEvict, tools);
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

        /** 构造本 Run 独享的 backend（对应 create_backend）。 */
        public SandboxFsBackend createBackend(ProvisionerSandboxProvider sandboxProvider) {
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

    // ==================== YuxiFilesystemMiddleware ====================

    /**
     * 文件系统中间件配置载体（对应参考实现 YuxiFilesystemMiddleware，继承 deepagents FilesystemMiddleware）。
     *
     * <p>能力差异：参考实现在工具结果落入模型上下文前做 token 预算裁剪
     * （wrap_tool_call → _intercept_large_tool_result）。该裁剪由我们桥接的引擎框架承载，
     * 此处仅暴露豁免判定与预算开关，供构图时装配，不重复实现裁剪算法。
     */
    public static final class YuxiFilesystemMiddleware {
        private final SandboxFsBackend backend;
        private final Integer toolTokenLimitBeforeEvict;
        private final List<String> tools;

        public YuxiFilesystemMiddleware(
                SandboxFsBackend backend, Integer toolTokenLimitBeforeEvict, List<String> tools) {
            this.backend = backend;
            this.toolTokenLimitBeforeEvict = toolTokenLimitBeforeEvict;
            this.tools = tools;
        }

        public SandboxFsBackend getBackend() {
            return backend;
        }

        public Integer getToolTokenLimitBeforeEvict() {
            return toolTokenLimitBeforeEvict;
        }

        public List<String> getTools() {
            return tools;
        }

        /** 该工具结果是否豁免裁剪（对应 wrap_tool_call 中的豁免判断）。 */
        public boolean isEvictionExempt(String toolName) {
            return TOOL_RESULT_EVICTION_EXEMPT_TOOLS.contains(toolName);
        }

        /** 是否启用裁剪（tool_token_limit_before_evict 非空）。 */
        public boolean evictionEnabled() {
            return toolTokenLimitBeforeEvict != null;
        }
    }
}
