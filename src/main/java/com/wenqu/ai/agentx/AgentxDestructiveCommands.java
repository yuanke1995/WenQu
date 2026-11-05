package com.wenqu.ai.agentx;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「这条命令会不会破坏数据 / 写到工作区外」的粗判，只服务 {@link AgentxNativeToolGuard} 的强制确认。
 *
 * <p><b>为什么是粗判</b>：shell 图灵完备，任何静态分析都能被绕过（base64、脚本文件、别名）。
 * 这里的目标不是拦全所有攻击，而是把「读了外部资料后顺手 rm -rf」这条最低成本的路径抬到人面前。
 * 判错方向的代价只有一次可见的确认卡；判漏的代价是用户文件。因此宁可多问。
 *
 * <p>不判读操作（cat/curl 下载到本地）：读工作区外的文件本身不破坏数据，强制确认只会把正常排查
 * 淹在卡里；写工作区外、删除/覆盖、以及把本地内容发到外部才在这里命中。
 *
 * @author yuanke
 */
public final class AgentxDestructiveCommands {

    private AgentxDestructiveCommands() {
    }

    /** 段首即删除类命令（大小写不敏感）。 */
    private static final Set<String> DELETE_COMMANDS = Set.of(
            "rm", "rmdir", "rd", "del", "erase", "unlink", "shred", "srm", "truncate", "format",
            "mkfs", "diskpart", "fdisk", "parted", "dd", "shutdown", "reboot", "halt", "poweroff");

    /** git 里会丢工作树改动的子命令（按 shell 分段判，`cd x && git reset --hard` 也要命中）。 */
    private static final Pattern GIT_DESTRUCTIVE = Pattern.compile(
            "(?is)^\\s*(?:git\\s+(?:-C\\s+\\S+\\s+)?)?(?:reset\\s+--hard|clean\\s+-[a-z]*f[a-z]*|"
                    + "checkout\\s+--\\s|restore\\s|branch\\s+-D|push\\s+(?:-f|--force))\\b");

    /** find -delete / find -exec rm / xargs rm / 命令替换里藏删除。 */
    private static final Pattern INDIRECT_DELETE = Pattern.compile(
            "(?is)\\b(?:find\\b[^;&|\\n]*\\s-delete"
                    + "|find\\b[^;&|\\n]*\\s-exec(?:dir)?\\s+(?:rm|shred|unlink)\\b"
                    + "|xargs\\b[^;&|\\n]*\\b(?:rm|shred|unlink)\\b"
                    + "|(?:\\$\\(|`)[^)`\\n]*\\b(?:rm|shred|unlink)\\b)");

    /** 输出重定向到文件（不是 /dev/null、不是 fd 复制）：覆盖写，算破坏性。 */
    private static final Pattern REDIRECT = Pattern.compile(
            "(?:^|[^>\\d])>{1,2}\\s*(\"[^\"]*\"|'[^']*'|[^\\s;&|()]+)");

    /** 会写目标位置的命令（目标按最后一段粗判）。 */
    private static final Pattern COPY_LIKE = Pattern.compile(
            "(?is)^\\s*(?:sudo\\s+)?(?:cp|mv|install|rsync|ln|tee)\\b");

    private static final Pattern DD_OF = Pattern.compile("(?is)\\bdd\\b[^;&|\\n]*\\bof=(\"[^\"]*\"|'[^']*'|\\S+)");

    /** 会把数据发到外部的常见形态：上传参数的 curl/wget、nc 家族、远端 scp/rsync。 */
    private static final Pattern EXFIL = Pattern.compile(
            "(?is)\\bcurl\\b[^;&|\\n]*(?:-d|--data(?:-raw|-binary|-urlencode)?|-F|--form|-T|--upload-file)\\b"
                    + "|\\bwget\\b[^;&|\\n]*--post-(?:data|file)\\b"
                    + "|\\b(?:nc|ncat|netcat)\\b");

    /**
     * 这条命令是否会把本地内容发到外部。只覆盖最常见的几种；GET 类 curl/wget 不算（正常抓资料）。
     * <p>scp/rsync 只有目标带 {@code host:} 才算——本地复制不该因为污点被拦。
     */
    public static boolean exfiltrates(String command) {
        if (command == null || command.isBlank()) return false;
        if (EXFIL.matcher(command).find()) return true;
        for (String segment : command.split("(?i)&&|\\|\\||[;\\n]")) {
            String s = segment.trim().toLowerCase(Locale.ROOT);
            if (!(s.startsWith("scp ") || s.startsWith("rsync "))) continue;
            String last = unquote(lastWord(segment));
            // host:/path（排除 Windows 的 C:/...）
            if (last.matches("^[^/\\\\:]+:.*") && !last.matches("^[A-Za-z]:[/\\\\].*")) return true;
        }
        return false;
    }

    /**
     * 这条命令是否带有删除/覆盖/不可逆操作。
     * <p>按 {@code && || ; 换行} 切段后看段首命令，避免把 `echo "rm -rf"` 这类文本当成命令；
     * 重定向与 git 子命令另按正则命中。
     */
    public static boolean isDestructive(String command) {
        if (command == null || command.isBlank()) return false;
        String c = command.trim();
        if (INDIRECT_DELETE.matcher(c).find()) return true;
        if (hasFileRedirect(c)) return true;
        for (String segment : c.split("(?i)&&|\\|\\||[;\\n]")) {
            String s = segment.trim();
            if (GIT_DESTRUCTIVE.matcher(s).find()) return true;
            String first = firstWord(s);
            if (first.isEmpty()) continue;
            if (DELETE_COMMANDS.contains(first)) return true;
        }
        return false;
    }

    /**
     * 这条命令是否把内容写到工作区之外。覆盖 {@code >/>>}、{@code dd of=}、{@code tee}、
     * {@code cp/mv/install/rsync/ln} 的目标段（最后一段）。相对路径一律视为在工作区内。
     */
    public static boolean writesOutside(String command, List<String> roots) {
        if (command == null || command.isBlank()) return false;
        Matcher r = REDIRECT.matcher(command);
        while (r.find()) {
            String target = unquote(r.group(1));
            if (isDeviceOrFd(target)) continue;
            if (outside(target, roots)) return true;
        }
        Matcher dd = DD_OF.matcher(command);
        while (dd.find()) {
            String target = unquote(dd.group(1));
            if (isDeviceOrFd(target)) continue;
            if (outside(target, roots)) return true;
        }
        for (String segment : command.split("(?i)&&|\\|\\||[;\\n]")) {
            if (!COPY_LIKE.matcher(segment).find()) continue;
            String last = lastWord(segment);
            if (last.isEmpty() || isDeviceOrFd(last)) continue;
            if (outside(last, roots)) return true;
        }
        return false;
    }

    /** 绝对路径且不在任何一个工作区根下 => 越界；相对路径没有 .. 视为工作区内。 */
    public static boolean outside(String raw, List<String> roots) {
        String value = unquote(raw);
        if (value.isEmpty()) return false;
        String p = value.replace('\\', '/').trim();
        boolean absolute = p.startsWith("/") || p.matches("^[A-Za-z]:/.*");
        if (!absolute) {
            // 相对路径按主根解析：只有带 .. 的才可能跑出工作区（工具与 shell 的 cwd 都是主根）
            if (!p.contains("..")) return false;
            if (roots == null || roots.isEmpty()) return true;
            p = roots.get(0).replace('\\', '/').trim() + "/" + p;
        }
        String candidate = normalize(p);
        if (roots == null || roots.isEmpty()) return true;
        for (String root : roots) {
            if (root == null || root.isBlank()) continue;
            String r = normalize(root.replace('\\', '/').trim());
            if (candidate.equals(r) || candidate.startsWith(r.endsWith("/") ? r : r + "/")) return false;
        }
        return true;
    }

    private static boolean hasFileRedirect(String command) {
        Matcher m = REDIRECT.matcher(command);
        while (m.find()) {
            String target = unquote(m.group(1));
            if (!isDeviceOrFd(target)) return true;
        }
        return false;
    }

    private static boolean isDeviceOrFd(String target) {
        String t = target == null ? "" : target.trim();
        return t.isEmpty() || t.startsWith("/dev/null") || t.matches("&\\d+") || t.startsWith("&");
    }

    private static String firstWord(String segment) {
        if (segment == null) return "";
        String s = segment.trim();
        // 跳过 sudo 与环境变量前缀；`cd /x` 这类段首命令本身不破坏数据
        while (true) {
            if (s.startsWith("sudo ")) {
                s = s.substring(5).trim();
                continue;
            }
            int eq = s.indexOf('=');
            int space = s.indexOf(' ');
            if (eq > 0 && (space < 0 || eq < space) && !s.startsWith("=")) {
                s = s.substring(space < 0 ? s.length() : space).trim();
                continue;
            }
            break;
        }
        String[] parts = s.split("\\s+");
        if (parts.length == 0) return "";
        String w = parts[0].toLowerCase(Locale.ROOT);
        int slash = w.lastIndexOf('/');
        return slash >= 0 ? w.substring(slash + 1) : w;
    }

    private static String lastWord(String segment) {
        String s = segment == null ? "" : segment.trim();
        String[] parts = s.split("\\s+");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private static String unquote(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** 词法规范化（不碰磁盘）：去掉 . 段与重复分隔符，保留 .. 交给调用方按前缀判断。 */
    private static String normalize(String p) {
        String s = p;
        try {
            s = Path.of(p).normalize().toString().replace('\\', '/');
        } catch (Exception ignored) {
            // 非法路径字符：退回原始串比较，宁可判越界（多问一次）
        }
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
