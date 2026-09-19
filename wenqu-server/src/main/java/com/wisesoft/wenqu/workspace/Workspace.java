package com.wisesoft.wenqu.workspace;

import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.common.SafeFiles;
import com.wisesoft.wenqu.common.SafeFiles.NotDirectoryPathException;
import com.wisesoft.wenqu.common.SafeFiles.SymlinkPathException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 当前用户持久化 UserWorkspace 的 no-follow 文件访问边界。
 *
 * <p>由参考实现的 workspace/filesystem.py 逐方法翻译：目录列举/有界扫描、
 * 有界读写与前缀读、临时文件原子替换/上传、目录创建、递归删除。
 *
 * <p>能力差异（显式标注，非遗漏）：参考实现全程使用目录 fd（{@code dir_fd + O_NOFOLLOW}）
 * 钉定父目录；Java NIO 无目录 fd，按 {@link SafeFiles} 的逐组件 NOFOLLOW 校验等价实现
 * （TOCTOU 说明见其类注解）。目录 fsync（rename 后持久化保证）Java 无公开等价物，
 * {@code replace}/{@code upload} 的父目录 fsync 省略；其余边界语义照搬：
 * 符号链接一律拒绝（{@code PermissionError} → {@link AccessDeniedException}）、
 * 仅普通文件与真实目录可访问、传输上限抛 {@link WorkspaceErrors.FileTransferLimitError}。
 */
public class Workspace {

    private static final int CHUNK_SIZE = 1024 * 1024;

    private final Path workspaceRoot;

    /** 以 uid 为边界访问持久化 UserWorkspace。 */
    public Workspace(String uid) {
        this.workspaceRoot = com.wisesoft.wenqu.workspace.WorkspacePaths.userWorkspaceDir(String.valueOf(uid));
    }

    // ==================== 列举与扫描 ====================

    /** 列出 Workdir 内的普通文件与真实目录。 */
    public List<Map<String, Object>> listAuthorizedDirectory(String path, String root) {
        requireWithin(path, root, true);
        Resolved resolved = resolvePath(path);
        Path directory = openDirectory(resolved.base(), resolved.parts(), false);
        List<Map<String, Object>> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            List<Path> names = new ArrayList<>();
            stream.forEach(names::add);
            names.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
            for (Path child : names) {
                BasicFileAttributes attrs =
                        Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!(attrs.isDirectory() || attrs.isRegularFile())) {
                    continue;
                }
                Map<String, Object> entry = metadata(attrs);
                entry.put("name", child.getFileName().toString());
                entries.add(entry);
            }
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        return entries;
    }

    /** 在授权根内执行有界实时扫描并返回文件名或路径匹配项。 */
    public List<Map<String, Object>> searchAuthorizedTree(
            String root,
            String query,
            boolean includeDirectories,
            Set<String> excludeDirectories,
            boolean excludeHidden,
            int maxResults,
            int maxDirectories,
            int maxDepth,
            int maxEntriesPerDirectory,
            int maxScannedEntries) {
        String normalizedQuery = query == null ? "" : String.valueOf(query).strip().toLowerCase();
        List<Map<String, Object>> results = new ArrayList<>();
        record Pending(String directory, int depth) {}
        List<Pending> pending = new ArrayList<>();
        pending.add(new Pending(root, 0));
        int visitedDirectories = 0;
        int scannedEntries = 0;

        while (!pending.isEmpty()
                && visitedDirectories < maxDirectories
                && scannedEntries < maxScannedEntries) {
            Pending current = pending.remove(0);
            visitedDirectories += 1;
            int remainingEntries = maxScannedEntries - scannedEntries;
            LimitedListing listing = listAuthorizedDirectoryLimited(
                    current.directory(),
                    root,
                    Math.min(maxEntriesPerDirectory, remainingEntries));
            scannedEntries += listing.examinedEntries();
            for (Map<String, Object> item : listing.entries()) {
                String name = String.valueOf(item.get("name"));
                String path = current.directory().replaceAll("/+$", "") + "/" + name;
                boolean isDir = Boolean.TRUE.equals(item.get("is_dir"));
                boolean excluded = isDir
                        && (excludeDirectories.contains(name) || (excludeHidden && name.startsWith(".")));
                if (isDir && !excluded && current.depth() < maxDepth) {
                    pending.add(new Pending(path, current.depth() + 1));
                }

                boolean matched = normalizedQuery.isEmpty()
                        || name.toLowerCase().contains(normalizedQuery)
                        || path.toLowerCase().contains(normalizedQuery);
                if (matched && !excluded && (includeDirectories || !isDir)) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("path", path);
                    result.putAll(item);
                    results.add(result);
                    if (results.size() >= maxResults) {
                        return results;
                    }
                }
            }
        }
        return results;
    }

    /** 最多检查指定数量的目录项，避免宽目录触发全量 I/O。 */
    private LimitedListing listAuthorizedDirectoryLimited(String path, String root, int maxEntries) {
        if (maxEntries < 0) {
            throw new IllegalArgumentException("directory entry limit must be non-negative");
        }
        requireWithin(path, root, true);
        Resolved resolved = resolvePath(path);
        Path directory = openDirectory(resolved.base(), resolved.parts(), false);
        List<Map<String, Object>> entries = new ArrayList<>();
        int examinedEntries = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            // 参考实现 itertools.islice：按目录返回顺序取前 max_entries 项（不预排序）
            for (Path child : stream) {
                if (examinedEntries >= maxEntries) {
                    break;
                }
                examinedEntries += 1;
                BasicFileAttributes attrs =
                        Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!(attrs.isDirectory() || attrs.isRegularFile())) {
                    continue;
                }
                Map<String, Object> entry = metadata(attrs);
                entry.put("name", child.getFileName().toString());
                entries.add(entry);
            }
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        entries.sort(Comparator.comparing(item -> String.valueOf(item.get("name")).toLowerCase()));
        return new LimitedListing(entries, examinedEntries);
    }

    private record LimitedListing(List<Map<String, Object>> entries, int examinedEntries) {}

    // ==================== 读取 ====================

    /** 把授权普通文件有界复制到服务临时文件；返回复制字节数。 */
    public long downloadAuthorizedFileToPath(String path, String targetPath, long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("file download limit must be non-negative");
        }
        Resolved resolved = resolvePath(path);
        SafeFiles.OpenedRegularFile source = openRegularFile(resolved, false);
        Path target = java.nio.file.Paths.get(targetPath);
        try (InputStream in = source.openRead()) {
            // 对应 os.O_WRONLY|O_TRUNC|O_NOFOLLOW：不跟随写目标、不自动创建
            if (Files.isSymbolicLink(target)) {
                throw new PermissionDeniedRuntime("symlink paths are not allowed", null);
            }
            try (OutputStream out = Files.newOutputStream(
                    target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[CHUNK_SIZE];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > maxBytes) {
                        throw new WorkspaceErrors.FileTransferLimitError("file exceeds transfer limit");
                    }
                    out.write(buffer, 0, read);
                }
                return total;
            }
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** 在 no-follow 边界内有界读取普通文件。 */
    public byte[] readAuthorizedFile(String path, long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("file read limit must be non-negative");
        }
        Resolved resolved = resolvePath(path);
        SafeFiles.OpenedRegularFile source = openRegularFile(resolved, false);
        if (source.attrs().size() > maxBytes) {
            throw new WorkspaceErrors.FileTransferLimitError("file exceeds transfer limit");
        }
        try (InputStream in = source.openRead()) {
            List<byte[]> chunks = new ArrayList<>();
            long total = 0;
            byte[] buffer = new byte[CHUNK_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > maxBytes) {
                    throw new WorkspaceErrors.FileTransferLimitError("file exceeds transfer limit");
                }
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                chunks.add(chunk);
            }
            return concat(chunks, total);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** 有界读取普通文件前缀，并报告内容是否被截断。 */
    public record FilePrefix(byte[] content, boolean truncated) {}

    public FilePrefix readAuthorizedFilePrefix(String path, long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("file read limit must be non-negative");
        }
        Resolved resolved = resolvePath(path);
        SafeFiles.OpenedRegularFile source = openRegularFile(resolved, false);
        try (InputStream in = source.openRead()) {
            long remaining = maxBytes + 1;
            List<byte[]> chunks = new ArrayList<>();
            long total = 0;
            while (remaining > 0) {
                int size = (int) Math.min(CHUNK_SIZE, remaining);
                byte[] buffer = new byte[size];
                int read = in.read(buffer);
                if (read <= 0) {
                    break;
                }
                total += read;
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                chunks.add(chunk);
                remaining -= read;
            }
            byte[] content = concat(chunks, total);
            byte[] prefix = content.length > maxBytes
                    ? java.util.Arrays.copyOf(content, (int) maxBytes)
                    : content;
            return new FilePrefix(prefix, content.length > maxBytes);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** 在 no-follow 边界内读取普通文件或真实目录元数据。 */
    public Map<String, Object> statAuthorizedPath(String path, String root) {
        requireWithin(path, root, true);
        Resolved resolved = resolvePath(path);
        BasicFileAttributes itemStat;
        if (resolved.parts().isEmpty()) {
            try {
                itemStat = Files.readAttributes(
                        resolved.base(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException exc) {
                throw new NoSuchFileRuntime(exc.getMessage(), exc);
            }
        } else {
            List<String> parentParts = resolved.parts().subList(0, resolved.parts().size() - 1);
            Path parent = openDirectory(resolved.base(), parentParts, false);
            Path child = parent.resolve(resolved.parts().get(resolved.parts().size() - 1));
            try {
                itemStat = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException exc) {
                throw new NoSuchFileRuntime(exc.getMessage(), exc);
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
            if (itemStat.isSymbolicLink()) {
                throw newAccessDenied("symlink paths are not allowed", null);
            }
        }
        boolean isDir = itemStat.isDirectory();
        if (!(isDir || itemStat.isRegularFile())) {
            throw newAccessDenied("only regular files and directories are allowed", null);
        }
        return metadata(itemStat);
    }

    // ==================== 写入 ====================

    /** 通过已打开的普通文件覆盖内容，拒绝 symlink 与目录；返回写后元数据。 */
    public Map<String, Object> writeAuthorizedFile(String path, byte[] content) {
        Resolved resolved = resolvePath(path);
        SafeFiles.OpenedRegularFile target = openRegularFile(resolved, true);
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                target.file(),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS)) {
            writeAll(channel, content);
            long size = channel.size();
            BasicFileAttributes attrs =
                    Files.readAttributes(target.file(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String, Object> metadata = metadata(attrs);
            metadata.put("size", size);
            return metadata;
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** 在同一目录内完整写入并原子替换普通文件。 */
    public Map<String, Object> replaceAuthorizedFile(String path, byte[] content) {
        Resolved resolved = resolvePath(path);
        List<String> parts = resolved.parts();
        if (parts.isEmpty()) {
            throw new NoSuchFileRuntime(path, null);
        }
        Path parent = openDirectory(resolved.base(), parts.subList(0, parts.size() - 1), false);
        String tempName = ".wenqu-replace-" + UUID.randomUUID().toString().replace("-", "");
        Path target = parent.resolve(parts.get(parts.size() - 1));
        Path temp = parent.resolve(tempName);
        java.nio.channels.FileChannel channel = null;
        try {
            BasicFileAttributes targetStat;
            try {
                targetStat = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException exc) {
                // 目标不存在时 rename 直接创建，无需校验既有类型
                targetStat = null;
            }
            if (targetStat != null) {
                if (targetStat.isSymbolicLink()) {
                    throw new PermissionDeniedRuntime("symlink paths are not allowed", null);
                }
                if (!targetStat.isRegularFile()) {
                    throw new PermissionDeniedRuntime("only regular files can be replaced", null);
                }
            }

            // 先以 0600 权限原子创建（对应 O_CREAT|O_EXCL），再打开写通道
            Files.createFile(
                    temp,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE);
            writeAll(channel, content);
            channel.force(true);
            long size = channel.size();
            BasicFileAttributes finalStat =
                    Files.readAttributes(temp, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            channel.close();
            channel = null;
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // 参考实现随后对父目录 fd fsync；Java 无目录 fsync 等价物（能力差异，见类注解）
            Map<String, Object> metadata = metadata(finalStat);
            metadata.put("size", size);
            return metadata;
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        } finally {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // 关闭失败不掩盖原始异常
                }
            }
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理失败不掩盖原始异常
            }
        }
    }

    /** 从受信任服务临时文件原子写入 UserWorkspace。 */
    public Map<String, Object> uploadAuthorizedFileFromPath(
            String path, String sourcePath, boolean overwrite, boolean createParents) {
        Resolved resolved = resolvePath(path);
        List<String> parts = resolved.parts();
        if (parts.isEmpty()) {
            throw new NoSuchFileRuntime(path, null);
        }
        Path parent = openDirectory(resolved.base(), parts.subList(0, parts.size() - 1), createParents);
        Path target = parent.resolve(parts.get(parts.size() - 1));
        String tempName = ".wenqu-write-" + UUID.randomUUID().toString().replace("-", "");
        Path temp = parent.resolve(tempName);
        Path source = java.nio.file.Paths.get(sourcePath);
        // 参考实现 finally 中必清理临时文件（成功路径 rename/link 后已不在，失败路径兜底删除）
        try {
            BasicFileAttributes sourceStat =
                    Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (sourceStat.isSymbolicLink() || !sourceStat.isRegularFile()) {
                throw new IllegalArgumentException("upload source is not a regular file");
            }
            // 先以 0600 权限原子创建（对应 O_CREAT|O_EXCL），再打开写通道
            Files.createFile(
                    temp,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            java.nio.channels.FileChannel channel =
                    java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE);
            try (InputStream in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    writeAll(channel, java.util.Arrays.copyOf(buffer, read));
                }
            }
            channel.force(true);
            long size = channel.size();
            BasicFileAttributes finalStat =
                    Files.readAttributes(temp, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            channel.close();
            if (overwrite) {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // 对应 os.link(...) + unlink：硬链接后删除临时名（目标已存在时失败）
                Files.createLink(target, temp);
                Files.deleteIfExists(temp);
            }
            // 参考实现随后对父目录 fd fsync；Java 无目录 fsync 等价物（能力差异，见类注解）
            Map<String, Object> metadata = metadata(finalStat);
            metadata.put("size", size);
            return metadata;
        } catch (FileAlreadyExistsException exc) {
            // 参考实现把 FileExistsError 直接上抛给调用方；Java 侧以运行时异常承载
            throw new AlreadyExistsRuntime(exc.getMessage(), exc);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理失败不掩盖原始异常
            }
        }
    }

    // ==================== 目录与删除 ====================

    /** 在 Workdir 内创建一个单层目录。 */
    public Map<String, Object> createAuthorizedDirectory(String parentPath, String name, String root) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("directory name must be one path component");
        }
        requireWithin(parentPath, root, true);
        Resolved resolved = resolvePath(parentPath);
        Path parent = openDirectory(resolved.base(), resolved.parts(), false);
        try {
            Path created = parent.resolve(name);
            Files.createDirectory(
                    created,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            BasicFileAttributes attrs =
                    Files.readAttributes(created, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return metadata(attrs);
        } catch (FileAlreadyExistsException exc) {
            // 参考实现 os.mkdir 的 FileExistsError 直接上抛
            throw new AlreadyExistsRuntime(exc.getMessage(), exc);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** 递归删除 Workdir 内的真实文件或目录，不允许删除根。 */
    public void deleteAuthorizedPath(String path, String root) {
        requireWithin(path, root, false);
        Resolved resolved = resolvePath(path);
        List<String> parts = resolved.parts();
        Path parent = openDirectory(resolved.base(), parts.subList(0, parts.size() - 1), false);
        removeEntry(parent, parts.get(parts.size() - 1));
    }

    // ==================== 内部工具 ====================

    /** 把 UserWorkspace scope 路径解析为持久化根内组件。 */
    private Resolved resolvePath(String path) {
        String raw = path == null ? "" : String.valueOf(path).strip();
        PosixPathLite pure = PosixPathLite.parse(raw);
        if (raw.isEmpty() || !pure.isAbsolute() || pure.partsContain("..") || raw.contains("\\")) {
            throw new IllegalArgumentException("invalid Workspace path");
        }
        List<String> parts = pure.parts();
        parts = parts.subList(1, parts.size()); // 去掉绝对根段（对应 pure.parts[1:]）
        return new Resolved(workspaceRoot, parts);
    }

    private record Resolved(Path base, List<String> parts) {}

    private static void requireWithin(String path, String root, boolean allowRoot) {
        String normalizedPath = PosixPathLite.parse(String.valueOf(path)).asPosix();
        String normalizedRoot = PosixPathLite.parse(String.valueOf(root)).asPosix();
        if (normalizedPath.equals(normalizedRoot)) {
            if (allowRoot) {
                return;
            }
            throw new IllegalArgumentException("operation cannot target the Workdir root");
        }
        if (normalizedRoot.equals("/")) {
            if (normalizedPath.startsWith("/")) {
                return;
            }
            throw new IllegalArgumentException("path is outside the Workdir");
        }
        String trimmedRoot = normalizedRoot.replaceAll("/+$", "");
        if (!normalizedPath.startsWith(trimmedRoot + "/")) {
            throw new IllegalArgumentException("path is outside the Workdir");
        }
    }

    private static Map<String, Object> metadata(BasicFileAttributes attrs) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean isDir = attrs.isDirectory();
        result.put("is_dir", isDir);
        result.put("size", isDir ? 0L : attrs.size());
        result.put("modified_at", attrs.lastModifiedTime().toMillis() / 1000.0);
        return result;
    }

    private SafeFiles.OpenedRegularFile openRegularFile(Resolved resolved, boolean writable) {
        try {
            return SafeFiles.openRegularFile(resolved.base(), resolved.parts(), writable);
        } catch (NoSuchFileException exc) {
            throw new NoSuchFileRuntime(exc.getMessage(), exc);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** no-follow 打开目录；符号链接统一翻译为 PermissionDeniedRuntime（PermissionError）。 */
    private Path openDirectory(Path base, List<String> parts, boolean create) {
        try {
            return SafeFiles.openDirectory(base, parts, create);
        } catch (SymlinkPathException exc) {
            throw newAccessDenied("symlink paths are not allowed", exc);
        } catch (NoSuchFileException exc) {
            throw new NoSuchFileRuntime(exc.getMessage(), exc);
        } catch (NotDirectoryPathException exc) {
            throw newAccessDenied(exc.getMessage(), exc);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** PermissionError 的运行时承载（参考实现 OS 异常直接上抛给调用方）。 */
    public static final class PermissionDeniedRuntime extends RuntimeException {
        PermissionDeniedRuntime(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * FileExistsError 的运行时承载（参考实现 OS 异常直接上抛给调用方；
     * Java 侧的 FileAlreadyExistsException 与之对应，此前被并入 IllegalStateException 而丢失了
     * 「同名冲突」与「一般 IO 故障」的区分，调用方无法按参考实现给出 400 同名提示，故单独承载）。
     */
    public static final class AlreadyExistsRuntime extends RuntimeException {
        AlreadyExistsRuntime(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static PermissionDeniedRuntime newAccessDenied(String message, Throwable cause) {
        return new PermissionDeniedRuntime(message, cause);
    }

    /** FileNotFoundError 的运行时承载（参考实现 OS 异常直接上抛给调用方）。 */
    public static final class NoSuchFileRuntime extends RuntimeException {
        NoSuchFileRuntime(String message, Throwable cause) {
            super(message, cause);
        }

        NoSuchFileException asChecked() {
            return new NoSuchFileException(getMessage() == null ? "" : getMessage());
        }
    }

    private void removeEntry(Path parent, String name) {
        Path child = parent.resolve(name);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException exc) {
            throw new NoSuchFileRuntime(exc.getMessage(), exc);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        if (attrs.isSymbolicLink()) {
            throw newAccessDenied("symlink paths are not allowed", null);
        }
        if (!attrs.isDirectory()) {
            if (!attrs.isRegularFile()) {
                throw newAccessDenied("only regular files and directories can be deleted", null);
            }
            try {
                Files.delete(child);
            } catch (IOException exc) {
                throw new IllegalStateException(exc.getMessage(), exc);
            }
            return;
        }
        Path directory = openDirectory(parent, List.of(name), false);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            List<String> children = new ArrayList<>();
            stream.forEach(p -> children.add(p.getFileName().toString()));
            for (String childName : children) {
                removeEntry(directory, childName);
            }
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        try {
            Files.delete(directory);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    private static void writeAll(java.nio.channels.FileChannel channel, byte[] content) throws IOException {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static byte[] concat(List<byte[]> chunks, long total) {
        byte[] result = new byte[(int) total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }
}
