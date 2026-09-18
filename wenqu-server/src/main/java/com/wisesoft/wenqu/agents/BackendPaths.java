package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.workspace.WorkspacePaths;

/**
 * Agent Backend 的 Sandbox runtime 路径契约。
 *
 * <p>由参考实现的 agents/backends/paths.py 逐函数翻译：虚拟根路径、持久化 Workdir
 * 与 UserWorkspace scope 到 Sandbox runtime 的双向映射、runtime 命名空间判定。
 *
 * <p>必要替换：环境变量 {@code SANDBOX_VIRTUAL_PATH_PREFIX} 照搬；Python
 * PurePosixPath → {@link PosixPathLite}。
 */
public final class BackendPaths {

    private static final String DEFAULT_VIRTUAL_PATH_PREFIX = "/home/gem/user-data";

    /** 读取并规范化 Sandbox 的 user-data 虚拟根路径。 */
    private static String getVirtualPathPrefix() {
        String prefix = System.getenv("SANDBOX_VIRTUAL_PATH_PREFIX");
        if (prefix == null || prefix.isEmpty()) {
            prefix = DEFAULT_VIRTUAL_PATH_PREFIX;
        }
        prefix = prefix.strip();
        if (prefix.isEmpty()) {
            prefix = DEFAULT_VIRTUAL_PATH_PREFIX;
        }
        return prefix.startsWith("/") ? prefix : "/" + prefix;
    }

    public static final String VIRTUAL_PATH_PREFIX = getVirtualPathPrefix();
    public static final String VIRTUAL_SKILLS_PATH = "/home/gem/skills";
    public static final String VIRTUAL_PERSONAL_SKILLS_PATH =
            VIRTUAL_PATH_PREFIX.replaceAll("/+$", "") + "/agents/skills";
    public static final String LARGE_TOOL_RESULTS_DIR_NAME = "large_tool_results";
    public static final String CONVERSATION_HISTORY_DIR_NAME = "conversation_history";

    private BackendPaths() {}

    /** 把持久化 Workdir 标识映射到 Sandbox runtime。 */
    public static String runtimeWorkdirPath(String workdirPath) {
        return VIRTUAL_PATH_PREFIX.replaceAll("/+$", "") + "/" + WorkspacePaths.normalizeWorkdirPath(workdirPath);
    }

    /** 返回当前 Workdir runtime 的大结果与对话历史目录（二元组）。 */
    public static String[] workdirRuntimePaths(String workdirPath) {
        String normalized =
                PosixPathLite.parse(String.valueOf(workdirPath)).asPosix().replaceAll("/+$", "");
        if (!normalized.startsWith(VIRTUAL_PATH_PREFIX.replaceAll("/+$", "") + "/")) {
            throw new IllegalArgumentException("workdir_path must be a Backend runtime path");
        }
        String outputs = normalized + "/outputs";
        return new String[] {
            outputs + "/" + LARGE_TOOL_RESULTS_DIR_NAME,
            outputs + "/" + CONVERSATION_HISTORY_DIR_NAME,
        };
    }

    /** 把 UserWorkspace scope 映射到 Sandbox user-data runtime。 */
    public static String runtimeUserDataPath(String workspacePath) {
        String raw = workspacePath == null ? "" : String.valueOf(workspacePath).strip();
        PosixPathLite pure = PosixPathLite.parse(raw);
        if (!pure.isAbsolute() || pure.partsContain("..") || raw.contains("\\") || raw.contains("://")) {
            throw new IllegalArgumentException("invalid Workspace scope path");
        }
        String root = VIRTUAL_PATH_PREFIX.replaceAll("/+$", "");
        return "/".equals(pure.asPosix()) ? root : root + pure.asPosix();
    }

    /** 把 Sandbox user-data runtime 路径还原为 UserWorkspace scope。 */
    public static String workspaceScopeFromRuntimePath(String runtimePath) {
        String normalized = PosixPathLite.parse(String.valueOf(runtimePath)).asPosix();
        String root = VIRTUAL_PATH_PREFIX.replaceAll("/+$", "");
        if (normalized.equals(root)) {
            return "/";
        }
        if (!normalized.startsWith(root + "/")) {
            throw new IllegalArgumentException("runtime path is outside user-data");
        }
        return "/" + normalized.substring(root.length() + 1);
    }

    /** 把 Workdir scope 映射到 Sandbox runtime 绝对路径。 */
    public static String runtimePathForWorkdirScope(String workdirPath, String path) {
        String raw = path == null ? "" : String.valueOf(path).strip();
        if (raw.isEmpty()) {
            raw = "/";
        }
        PosixPathLite pure = PosixPathLite.parse(raw);
        if (!pure.isAbsolute() || pure.partsContain("..") || raw.contains("\\") || raw.contains("://")) {
            throw new IllegalArgumentException("invalid Workdir scope path");
        }
        String workspaceRoot = "/" + WorkspacePaths.normalizeWorkdirPath(workdirPath);
        String workspacePath = "/".equals(pure.asPosix()) ? workspaceRoot : workspaceRoot + pure.asPosix();
        return runtimeUserDataPath(workspacePath);
    }

    /** 把当前 Workdir runtime 路径还原为持久化 Workdir scope。 */
    public static String workdirScopeFromRuntimePath(String workdirPath, String runtimePath) {
        String normalized = PosixPathLite.parse(String.valueOf(runtimePath)).asPosix();
        String root = runtimeWorkdirPath(workdirPath).replaceAll("/+$", "");
        if (normalized.equals(root)) {
            return "/";
        }
        if (!normalized.startsWith(root + "/")) {
            throw new IllegalArgumentException("runtime path is outside the Workdir");
        }
        return "/" + normalized.substring(root.length() + 1);
    }

    /** 判断绝对路径是否属于 Agent Backend 的 runtime 命名空间。 */
    public static boolean isRuntimePath(String path) {
        String normalized = PosixPathLite.parse(String.valueOf(path)).asPosix();
        String[] roots = {
            VIRTUAL_PATH_PREFIX.replaceAll("/+$", ""),
            VIRTUAL_SKILLS_PATH.replaceAll("/+$", ""),
        };
        for (String root : roots) {
            if (normalized.equals(root) || normalized.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }
}
