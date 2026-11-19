package com.wenqu.ai.ssh;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * SSH 远端路径围栏（服务端侧第一道；远端 realpath 校验是第二道，见 {@code SshRemoteExecutor}）。
 *
 * <p>远端是 POSIX 路径：一律用 {@code /}，禁止 NUL，必须绝对路径。先把 {@code //}、{@code .}、{@code ..}
 * 词法折叠掉，再做前缀包含判断——{@code /a/bc} 不属于 {@code /a/b}（必须按路径段边界比，不能裸 startsWith）。
 *
 * @author yuanke
 */
public final class SshPathGuard {

    private SshPathGuard() {
    }

    /** 源文件夹（与 ExecutorFilesystem.Root 同形，独立一份避免跨包耦合）。 */
    public record Root(String alias, String path, boolean primary) {
    }

    /**
     * 词法规整为绝对 POSIX 路径。
     *
     * @throws IllegalArgumentException 空值/相对路径/含 NUL
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("路径不能为空");
        if (raw.indexOf('\u0000') >= 0) throw new IllegalArgumentException("非法路径");
        String p = raw.trim().replace('\\', '/');
        if (!p.startsWith("/")) throw new IllegalArgumentException("远端路径必须是绝对路径：" + raw);
        Deque<String> stack = new ArrayDeque<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!stack.isEmpty()) stack.removeLast();
                continue;
            }
            stack.addLast(seg);
        }
        StringBuilder sb = new StringBuilder("/");
        boolean first = true;
        for (String seg : stack) {
            if (!first) sb.append('/');
            sb.append(seg);
            first = false;
        }
        return sb.toString();
    }

    /** path 是否等于 root 或位于 root 之下（均为已规整的绝对路径）。 */
    public static boolean isSameOrChild(String path, String root) {
        String p = trimTrailing(path);
        String r = trimTrailing(root);
        if ("/".equals(r)) return p.startsWith("/");
        return p.equals(r) || p.startsWith(r + "/");
    }

    private static String trimTrailing(String p) {
        String s = p == null ? "" : p;
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * 校验路径落在某个源文件夹内。
     *
     * @param path         待校验路径（原始值，内部规整）
     * @param roots        源文件夹
     * @param unrestricted danger-full-access 档：只规整，不做包含校验
     * @return 规整后的绝对路径
     * @throws PathOutsideException 越界
     */
    public static String check(String path, List<Root> roots, boolean unrestricted) {
        String normalized = normalize(path);
        if (unrestricted) return normalized;
        if (roots == null || roots.isEmpty()) throw new PathOutsideException("工作区没有源文件夹：" + normalized);
        for (Root r : roots) {
            if (r == null || r.path() == null || r.path().isBlank()) continue;
            String root = normalize(r.path());
            if (isSameOrChild(normalized, root)) return normalized;
            // 软链形态的源文件夹：词法上可能对不上，交给远端 realpath 二次校验（由执行器补齐）
        }
        throw new PathOutsideException("路径越出工作区源文件夹：" + normalized);
    }

    /** 可读地相对化到某个 root（审计用）；无法相对化返回原绝对路径。 */
    public static String relativeTo(String absolute, List<Root> roots) {
        if (absolute == null) return null;
        String p;
        try {
            p = normalize(absolute);
        } catch (RuntimeException e) {
            return absolute;
        }
        for (Root r : roots == null ? List.<Root>of() : roots) {
            if (r == null || r.path() == null || r.path().isBlank()) continue;
            String root;
            try {
                root = normalize(r.path());
            } catch (RuntimeException e) {
                continue;
            }
            if (isSameOrChild(p, root)) {
                String rel = p.equals(root) ? "" : p.substring(root.length() + (root.endsWith("/") ? 0 : 1));
                String alias = r.alias() == null || r.alias().isBlank() ? root : r.alias();
                return rel.isEmpty() ? alias : alias + "/" + rel;
            }
        }
        return p;
    }

    /** 越界异常（由执行器映射为稳定错误码 E_PATH_OUTSIDE）。 */
    public static class PathOutsideException extends IllegalArgumentException {
        public PathOutsideException(String message) {
            super(message);
        }
    }
}
