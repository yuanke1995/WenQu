package com.wisesoft.wenqu.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 文件系统安全辅助。
 *
 * <p>由参考实现的 utils/paths.py 逐函数翻译：从可信根逐层 no-follow 打开路径、
 * no-follow 打开普通文件并在同一句柄上校验类型、越界拒绝。
 *
 * <p>能力差异（显式标注，非遗漏）：参考实现用目录 fd（{@code os.open(dir_fd)} +
 * {@code O_NOFOLLOW}）钉定已打开的父目录，天然免疫打开过程中的 TOCTOU 替换；
 * Java NIO 无目录 fd 等价能力，改为<b>逐组件</b>以 {@link LinkOption#NOFOLLOW_LINKS}
 * 校验（存在性/目录/非符号链接）后再下行。安全性质（拒绝符号链接与中间非目录组件）
 * 在无对抗并发的前提下等价；对并发替换敏感的部署需在挂载层面另行保证。
 *
 * <p>错误映射：ELOOP → {@link SymlinkPathException}；ENOTDIR → {@link NotDirectoryPathException}。
 */
public final class SafeFiles {

    private SafeFiles() {}

    /** 路径组件是符号链接（对应 errno ELOOP）。 */
    public static class SymlinkPathException extends java.io.IOException {
        public SymlinkPathException(String part) {
            super("symlink path component: " + part);
        }
    }

    /** 路径组件不是目录（对应 errno ENOTDIR）。 */
    public static class NotDirectoryPathException extends java.io.IOException {
        public NotDirectoryPathException(String part) {
            super("not a directory: " + part);
        }
    }

    /**
     * 从可信目录逐层 no-follow 打开路径。
     *
     * <p>参考实现返回调用方负责关闭的 fd；Java 侧返回最终目录（父层已全部
     * no-follow 校验），无句柄需要关闭。
     */
    public static Path openDirectory(Path root, java.util.List<String> parts, boolean create)
            throws java.io.IOException {
        Path current = root;
        for (String part : parts) {
            Path child = current.resolve(part);
            if (create) {
                try {
                    java.nio.file.Files.createDirectory(
                            child,
                            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // 与参考实现 FileExistsError 分支一致
                }
            }
            current = verifyDirectoryComponent(child, part);
        }
        return current;
    }

    private static Path verifyDirectoryComponent(Path child, String part) throws java.io.IOException {
        java.nio.file.attribute.BasicFileAttributes attrs;
        try {
            attrs = java.nio.file.Files.readAttributes(
                    child, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException exc) {
            throw new java.nio.file.NoSuchFileException(part);
        }
        if (attrs.isSymbolicLink()) {
            throw new SymlinkPathException(part);
        }
        if (!attrs.isDirectory()) {
            throw new NotDirectoryPathException(part);
        }
        return child;
    }

    /** no-follow 打开的普通文件（对应参考实现 yield 的 (fd, stat) 二元组）。 */
    public record OpenedRegularFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs, boolean writable) {

        /** 打开读取流（NOFOLLOW）。 */
        public java.io.InputStream openRead() throws java.io.IOException {
            return java.nio.file.Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        }

        /** 打开写入流（NOFOLLOW）。 */
        public java.io.OutputStream openWrite() throws java.io.IOException {
            return java.nio.file.Files.newOutputStream(
                    file,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    /**
     * 从可信根 no-follow 打开普通文件并在同一句柄上校验类型。
     *
     * <p>parts 为空抛 {@link java.io.FileNotFoundException}（对应 IsADirectoryError(root)）；
     * 目录抛同类型；符号链接与非普通文件抛 {@link java.io.FileNotFoundException} 语义的
     * IOException（对应 PermissionError）。Java 侧由调用方负责关闭返回的流。
     */
    public static OpenedRegularFile openRegularFile(Path root, java.util.List<String> parts, boolean writable)
            throws java.io.IOException {
        if (parts.isEmpty()) {
            throw new java.io.FileNotFoundException(root.toString());
        }
        Path parent;
        try {
            parent = openDirectory(root, parts.subList(0, parts.size() - 1), false);
        } catch (SymlinkPathException exc) {
            throw new java.io.FileNotFoundException("symlink paths are not allowed");
        }
        String name = parts.get(parts.size() - 1);
        Path file = parent.resolve(name);
        java.nio.file.attribute.BasicFileAttributes attrs;
        try {
            attrs = java.nio.file.Files.readAttributes(
                    file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.NoSuchFileException exc) {
            throw new java.nio.file.NoSuchFileException(name);
        }
        if (attrs.isSymbolicLink()) {
            throw new java.io.FileNotFoundException("symlink paths are not allowed");
        }
        if (attrs.isDirectory()) {
            throw new java.io.FileNotFoundException(name);
        }
        if (!attrs.isRegularFile()) {
            throw new java.io.FileNotFoundException("only regular files are allowed");
        }
        return new OpenedRegularFile(file, attrs, writable);
    }

    /** 确认真实路径位于指定根目录内，否则拒绝越界访问。 */
    public static Path ensureWithinRoot(Path path, Path root, String errorMessage) {
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException(errorMessage);
        }
        return path;
    }

    /** 供路径工具复用的 SHA-256 十六进制摘要。 */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 不可用", exc);
        }
    }

    /** PurePosixPath 解析入口（路径组件语义见 {@link PosixPathLite}）。 */
    public static PosixPathLite posixPath(String value) {
        return PosixPathLite.parse(value);
    }

    /** 便捷入口：把字符串路径转成 Path（等价 Path(value)）。 */
    public static Path path(String value) {
        return Paths.get(value);
    }
}
