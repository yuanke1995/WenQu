package com.wenqu.ai.sandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 沙盒路径工具（对应问渠新栈 {@code com.wenqu.wenqu.workspace.WorkspacePaths} 的那几个纯函数）。
 * <p>
 * 最初只搬两个：{@code uidDirname}（传给 provisioner 的 uid 参数，必须是路径安全的目录名）与
 * {@code normalizeWorkdirPath}（workdir 的相对路径校验）。2026-10 P2 权限档位又收进来两个绝对路径判据
 * （{@code normalizeAbsolutePath} / {@code isSameOrChild}），因为它们同时被沙盒工具执行前的写入收束
 * 和 backend 的路径校验用——两处各写一份的话，「浏览面板认为的越界」与「工具执行认为的越界」就不是
 * 同一个边界，那种不一致的表现是用户看着文件在、工具却说写不进去。
 * <p>
 * 新栈的 {@code PosixPathLite} 在本工程无对应物，故 {@code asPosix()} 那一层用「已校验的原始串」等价表达
 * ——校验已经把绝对路径、反斜杠、协议前缀、空段、.{@code .} 段全部拒掉，剩下的就是规范 POSIX 相对路径。
 *
 * @author yuanke
 */
public final class SandboxPaths {

    /** 路径安全字符集（与参考实现一致）。 */
    private static final Pattern SAFE_ID_RE = Pattern.compile("^[A-Za-z0-9_-]+$");

    private SandboxPaths() {
    }

    /**
     * 逻辑 UID → 路径安全、稳定的目录名（对应 {@code workspace_uid_dirname()}）。
     * <p>uid 可能含 {@code :}（如 OIDC 的 {@code iss:sub}）等合法身份字符但不适合做路径组件；
     * 安全字符集内的 uid 保留原名，其余用带命名空间的 SHA-256 摘要。
     */
    public static String uidDirname(String uid) {
        String value = uid == null ? "" : uid.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("uid is required");
        }
        if (SAFE_ID_RE.matcher(value).matches()) {
            return value;
        }
        return "uid-" + sha256Hex(value);
    }

    /**
     * 规范化 workdir 相对路径（对应 {@code normalize_workdir_path()}）。
     *
     * @throws IllegalArgumentException 绝对路径 / 含反斜杠 / 含 {@code ://} / 含空段或 {@code .}、{@code ..} 段
     */
    public static String normalizeWorkdirPath(String workdirPath) {
        String raw = workdirPath == null ? "" : workdirPath.strip();
        if (raw.isEmpty() || raw.startsWith("/") || raw.contains("\\") || raw.contains("://")) {
            throw new IllegalArgumentException("workdir_path must be a relative POSIX path");
        }
        for (String part : raw.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("workdir_path contains invalid path components");
            }
        }
        return raw;
    }

    /**
     * 规范化沙盒内的绝对路径（对应 {@code ProvisionerSandboxBackend.normalizePath}，实现搬到此处供两侧共用）。
     *
     * @throws IllegalArgumentException 空路径 / 非绝对路径 / {@code ..} 回溯
     */
    public static String normalizeAbsolutePath(String path) {
        String raw = path == null ? "" : path.strip();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        if (!raw.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        for (String part : raw.split("/")) {
            if ("..".equals(part)) {
                throw new IllegalArgumentException("path traversal is not allowed");
            }
        }
        return raw;
    }

    /**
     * candidate 是否为 ancestor 自身或其后裔（对应 {@code _is_same_or_child}）。
     * <p>P2 的写入收束用它：{@code workspace-write} 档下沙盒写类工具的 path 必须落在会话工作区根内。
     * 两侧共用一份实现，否则「浏览面板判的越界」和「工具执行判的越界」迟早不是同一个边界。
     */
    public static boolean isSameOrChild(String candidate, String ancestor) {
        String root = ancestor == null ? "" : ancestor.replaceAll("/+$", "");
        if (root.isEmpty()) {
            root = "/";
        }
        if ("/".equals(root)) {
            return "/".equals(candidate) || candidate.startsWith("/");
        }
        return candidate.equals(root) || candidate.startsWith(root + "/");
    }

    /** SHA-256 十六进制（小写）；本工程其它地方无此工具，故随本类携带。 */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 unavailable", exc);
        }
    }
}
