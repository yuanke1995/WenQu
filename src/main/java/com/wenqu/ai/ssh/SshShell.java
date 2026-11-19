package com.wenqu.ai.ssh;

/**
 * POSIX shell 引号工具：SSH 远端命令拼装时只接受单引号包裹 + 内部单引号转义，
 * 绝不把用户输入直接拼进双引号/未引号上下文。
 *
 * @author yuanke
 */
public final class SshShell {

    private SshShell() {
    }

    /** 单引号安全包裹：{@code a'b} → {@code 'a'\''b'}。 */
    public static String quote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 把路径/字符串里的换行压掉（用于日志显示，不用于命令拼接）。 */
    public static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\r', ' ').replace('\n', ' ');
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }
}
