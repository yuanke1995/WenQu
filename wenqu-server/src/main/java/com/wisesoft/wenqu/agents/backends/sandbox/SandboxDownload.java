package com.wisesoft.wenqu.agents.backends.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 沙盒目录下载工具（对应参考实现 backends/sandbox/download.py）。
 *
 * <p>逐字对齐：四个上限常量、{@code _relative_sandbox_path} 的词法逃逸拒绝、
 * {@code download_sandbox_directory} 的递归收集 + 限额校验 + 落盘 + 失败清理。
 *
 * <p>必要替换：Python {@code PurePosixPath.relative_to} → 本工程的等价纯字符串实现
 * {@link #relativeSandboxPath}（严格按分段比较，不解析 {@code ..}，与参考实现语义一致）；
 * {@code backend.ls} / {@code backend.download_files} → {@link SandboxFsBackend} 接口，
 * 具体 backend 实现见 {@link ProvisionerSandboxBackend}（能力差异：未部署 provisioner 时抛错）。
 */
public final class SandboxDownload {

    public static final int MAX_SANDBOX_TREE_FILES = 1000;
    public static final int MAX_SANDBOX_TREE_BYTES = 100 * 1024 * 1024;
    public static final int MAX_SANDBOX_TREE_ENTRIES = 2000;
    public static final int MAX_SANDBOX_TREE_DEPTH = 64;

    private SandboxDownload() {}

    /**
     * 返回 Sandbox 根目录内的相对路径，并拒绝词法逃逸（对应 {@code _relative_sandbox_path}）。
     *
     * @throws IllegalArgumentException 路径不在 remoteRoot 内，或含 {@code ..} 段，或相对路径为空
     */
    public static String relativeSandboxPath(String path, String remoteRoot) {
        String[] pathParts = splitPosix(path);
        String[] rootParts = splitPosix(remoteRoot);
        if (pathParts.length < rootParts.length) {
            throw new IllegalArgumentException("Sandbox 返回了越界文件路径");
        }
        for (int i = 0; i < rootParts.length; i++) {
            if (!rootParts[i].equals(pathParts[i])) {
                throw new IllegalArgumentException("Sandbox 返回了越界文件路径");
            }
        }
        List<String> rel = new ArrayList<>();
        for (int i = rootParts.length; i < pathParts.length; i++) {
            rel.add(pathParts[i]);
        }
        if (rel.isEmpty()) {
            throw new IllegalArgumentException("Sandbox 返回了越界文件路径");
        }
        if (rel.contains("..")) {
            throw new IllegalArgumentException("Sandbox 返回了越界文件路径");
        }
        return String.join("/", rel);
    }

    /**
     * 校验并下载 Sandbox 目录到新建的宿主目录（对应 {@code download_sandbox_directory}）。
     *
     * @param emptyMessage 目录无任何文件时抛出的文案
     * @throws IllegalArgumentException 越界 / 限额超限 / 下载失败 / 目录已存在
     */
    public static void downloadSandboxDirectory(
            SandboxFsBackend backend, String remoteDir, Path targetDir, String emptyMessage) {
        String remoteRoot = stripTrailingSlash(remoteDir);
        List<String[]> files = new ArrayList<>();
        Set<String> visitedDirs = new HashSet<>();
        long[] totals = {0L, 0L}; // [totalSize, totalEntries]

        collect(backend, remoteDir, 0, remoteRoot, visitedDirs, files, totals);
        if (files.isEmpty()) {
            throw new IllegalArgumentException(emptyMessage);
        }

        if (Files.exists(targetDir)) {
            throw new IllegalStateException("target directory already exists: " + targetDir);
        }
        try {
            Files.createDirectories(targetDir);
            long downloadedSize = 0;
            for (String[] pair : files) {
                SandboxFsBackend.DownloadResult response = backend.downloadFiles(List.of(pair[0])).get(0);
                if (response.hasError() || response.content == null) {
                    String reason = response.error != null ? response.error : "empty_content";
                    throw new IllegalArgumentException("下载沙盒文件失败: " + pair[0] + " (" + reason + ")");
                }
                byte[] content = response.content;
                downloadedSize += content.length;
                if (downloadedSize > MAX_SANDBOX_TREE_BYTES) {
                    throw new IllegalArgumentException("Skill 目录总大小超过限制（最多 100 MB）");
                }
                Path localPath = targetDir.resolve(pair[1]);
                Path parent = localPath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(localPath, content);
            }
        } catch (Exception e) {
            rmtree(targetDir);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

    private static void collect(
            SandboxFsBackend backend,
            String currentDir,
            int depth,
            String remoteRoot,
            Set<String> visitedDirs,
            List<String[]> files,
            long[] totals) {
        if (depth > MAX_SANDBOX_TREE_DEPTH) {
            throw new IllegalArgumentException("Skill 目录嵌套层级超过限制（最多 " + MAX_SANDBOX_TREE_DEPTH + " 层）");
        }
        if (visitedDirs.contains(currentDir)) {
            throw new IllegalArgumentException("Sandbox 返回了重复目录路径");
        }
        visitedDirs.add(currentDir);

        SandboxFsBackend.LsResult result = backend.ls(currentDir);
        if (result.hasError()) {
            throw new IllegalArgumentException(result.error);
        }
        for (SandboxFsBackend.FsEntry entry : result.entries) {
            totals[1]++;
            if (totals[1] > MAX_SANDBOX_TREE_ENTRIES) {
                throw new IllegalArgumentException("Skill 目录条目数超过限制（最多 " + MAX_SANDBOX_TREE_ENTRIES + " 个条目）");
            }
            String path = entry.path;
            String relativePath = relativeSandboxPath(path, remoteRoot);
            if (entry.isDir) {
                collect(backend, path, depth + 1, remoteRoot, visitedDirs, files, totals);
                continue;
            }
            if (files.size() >= MAX_SANDBOX_TREE_FILES) {
                throw new IllegalArgumentException("Skill 目录文件数超过限制（最多 " + MAX_SANDBOX_TREE_FILES + " 个文件）");
            }
            Long size = entry.size;
            if (size == null || size < 0) {
                throw new IllegalArgumentException("无法确认 Sandbox 文件大小: " + path);
            }
            totals[0] += size;
            if (totals[0] > MAX_SANDBOX_TREE_BYTES) {
                throw new IllegalArgumentException("Skill 目录总大小超过限制（最多 100 MB）");
            }
            files.add(new String[] {path, relativePath});
        }
    }

    // ==================== 路径工具 ====================

    /** 按 POSIX 分段切分绝对路径，丢弃空段与 {@code .} 段（不解析 {@code ..}）。 */
    private static String[] splitPosix(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Sandbox 路径必须是绝对路径: " + path);
        }
        String[] raw = path.split("/");
        List<String> parts = new ArrayList<>();
        for (String segment : raw) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            parts.add(segment);
        }
        return parts.toArray(new String[0]);
    }

    private static String stripTrailingSlash(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.replaceAll("/+$", "");
    }

    /** 递归删除目录（对应 {@code shutil.rmtree(target_dir, ignore_errors=True)}）。 */
    private static void rmtree(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // ignore_errors=True 语义
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // ignore_errors=True 语义
        }
    }
}
