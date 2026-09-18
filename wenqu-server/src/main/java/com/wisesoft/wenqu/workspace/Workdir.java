package com.wisesoft.wenqu.workspace;

import com.wisesoft.wenqu.common.PosixPathLite;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 以一个持久化 Project Workdir 为根的文件视图。
 *
 * <p>由参考实现的 workspace/workdir.py 逐方法翻译：把浏览 scope {@code /...}
 * 固定到一个持久化 Workspace 目录，所有访问经 scope 转换后委托 Workspace
 * 的 no-follow 边界。
 */
public final class Workdir {

    private final String relativePath;
    private final Workspace workspace;

    /**
     * @param relativePath 规范化前的相对路径（构造时归一化，对应 __post_init__）
     * @throws IllegalArgumentException 路径不是合法的相对 POSIX 路径
     */
    public Workdir(String relativePath, Workspace workspace) {
        this.relativePath = WorkspacePaths.normalizeWorkdirPath(relativePath);
        this.workspace = workspace;
    }

    /** 打开已存在的持久化 Workdir，并返回受限文件 capability。 */
    public static Workdir openExisting(String uid, String workdirPath) {
        Workdir workdir = new Workdir(workdirPath, new Workspace(String.valueOf(uid)));
        if (!Boolean.TRUE.equals(workdir.stat("/").get("is_dir"))) {
            throw new IllegalArgumentException("workdir_path does not reference an existing directory");
        }
        return workdir;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    /** 持久化 Workspace 内的绝对路径根。 */
    public String rootPath() {
        return "/" + relativePath;
    }

    /** 把 Workdir scope 路径解析为 UserWorkspace scope 路径。 */
    public String resolvePath(String path) {
        String raw = path == null ? "" : String.valueOf(path).strip();
        if (raw.isEmpty()) {
            raw = "/";
        }
        PosixPathLite pure = PosixPathLite.parse(raw);
        if (!pure.isAbsolute() || pure.partsContain("..") || raw.contains("\\") || raw.contains("://")) {
            throw new IllegalArgumentException("invalid Workdir scope path");
        }
        String normalized = pure.asPosix();
        if (normalized.equals("/")) {
            return rootPath();
        }
        return rootPath() + normalized;
    }

    /** 把当前 Workdir 内的 UserWorkspace 路径转换为浏览 scope。 */
    public String scopePath(String workspacePath) {
        String normalized = PosixPathLite.parse(String.valueOf(workspacePath)).asPosix();
        String root = rootPath().replaceAll("/+$", "");
        if (normalized.equals(root)) {
            return "/";
        }
        if (!normalized.startsWith(root + "/")) {
            throw new IllegalArgumentException("path is outside the Workdir");
        }
        return "/" + normalized.substring(root.length() + 1);
    }

    /** 列出目录（scope 路径）。 */
    public List<Map<String, Object>> listDirectory(String path) {
        return workspace.listAuthorizedDirectory(resolvePath(path), rootPath());
    }

    /** 授权根内有界实时搜索；结果路径换算回浏览 scope。 */
    public List<Map<String, Object>> search(
            String query,
            boolean includeDirectories,
            Set<String> excludeDirectories,
            boolean excludeHidden,
            int maxResults,
            int maxDirectories,
            int maxDepth,
            int maxEntriesPerDirectory,
            int maxScannedEntries) {
        List<Map<String, Object>> matches = workspace.searchAuthorizedTree(
                rootPath(),
                query,
                includeDirectories,
                excludeDirectories,
                excludeHidden,
                maxResults,
                maxDirectories,
                maxDepth,
                maxEntriesPerDirectory,
                maxScannedEntries);
        List<Map<String, Object>> scoped = new java.util.ArrayList<>();
        for (Map<String, Object> item : matches) {
            Map<String, Object> converted = new LinkedHashMap<>(item);
            converted.put("path", scopePath(String.valueOf(item.get("path"))));
            scoped.add(converted);
        }
        return scoped;
    }

    /** 有界读取普通文件。 */
    public byte[] readFile(String path, long maxBytes) {
        return workspace.readAuthorizedFile(resolvePath(path), maxBytes);
    }

    /** 有界读取普通文件前缀（content, truncated）。 */
    public Workspace.FilePrefix readFilePrefix(String path, long maxBytes) {
        return workspace.readAuthorizedFilePrefix(resolvePath(path), maxBytes);
    }

    /** 覆盖写普通文件。 */
    public Map<String, Object> writeFile(String path, byte[] content) {
        return workspace.writeAuthorizedFile(resolvePath(path), content);
    }

    /** 读取文件/目录元数据。 */
    public Map<String, Object> stat(String path) {
        return workspace.statAuthorizedPath(resolvePath(path), rootPath());
    }

    /** 把 Workdir 内文件有界复制到服务临时文件。 */
    public long copyFileToPath(String path, String targetPath, long maxBytes) {
        return workspace.downloadAuthorizedFileToPath(resolvePath(path), targetPath, maxBytes);
    }

    /** 从受信任服务临时文件原子写入 Workdir。 */
    public Map<String, Object> copyFileFromPath(String path, String sourcePath, boolean overwrite) {
        return workspace.uploadAuthorizedFileFromPath(resolvePath(path), sourcePath, overwrite, true);
    }

    /** 在 Workdir 内创建单层目录。 */
    public Map<String, Object> createDirectory(String parentPath, String name) {
        return workspace.createAuthorizedDirectory(resolvePath(parentPath), name, rootPath());
    }

    /** 删除 Workdir 内文件或目录。 */
    public void delete(String path) {
        workspace.deleteAuthorizedPath(resolvePath(path), rootPath());
    }
}
