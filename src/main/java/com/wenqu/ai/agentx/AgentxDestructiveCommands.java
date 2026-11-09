package com.wenqu.ai.agentx;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
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
 * <p><b>先中和再判</b>（{@link #scan}）：单引号串、双引号串里的字面段、heredoc 正文
 * 都不会被 shell 当成命令/运算符解析。直接拿正则扫原文会把 `python3 - <<'EOF'` 脚本里的
 * `'>'`（如 `endswith('&lt;/html&gt;')`）当成输出重定向，误挂确认卡。
 * 先把这些惰性文本中和掉，只留下真正会执行的命令骨架再判；引号里的字母与路径保留，
 * `sh -c "…"` 这类把命令藏进引号里的写法仍按字面处理（粗判不追，交给沙盒写收束与执行器）。
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

    /**
     * find -delete / find -exec rm / xargs rm / 命令替换里藏删除。
     * <p>{@code \b} 只放在需要「从单词开头起算」的两支里：放到整个分组前面时，{@code $} 与
     * 它前面的引号/空白都是非单词字符、中间没有边界，{@code $(rm …)} 这一支会永远不命中。
     */
    private static final Pattern INDIRECT_DELETE = Pattern.compile(
            "(?is)\\b(?:find\\b[^;&|\\n]*\\s-delete"
                    + "|find\\b[^;&|\\n]*\\s-exec(?:dir)?\\s+(?:rm|shred|unlink)\\b"
                    + "|xargs\\b[^;&|\\n]*\\b(?:rm|shred|unlink)\\b)"
                    + "|(?:\\$\\(|`)[^)`\\n]*\\b(?:rm|shred|unlink)\\b");

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
        String code = scan(command, false);
        if (code.isBlank()) return false;
        if (EXFIL.matcher(code).find()) return true;
        String paths = scan(command, true);
        for (String segment : paths.split("(?i)&&|\\|\\||[;\\n]")) {
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
        String code = scan(command, false).trim();
        if (code.isEmpty()) return false;
        if (INDIRECT_DELETE.matcher(code).find()) return true;
        // 重定向目标可能是引号里的路径：用保留引号文本的那份视图判，才能把目标取出来做越界判断
        String paths = scan(command, true);
        if (hasFileRedirect(paths)) return true;
        for (String segment : code.split("(?i)&&|\\|\\||[;\\n]")) {
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
        String code = scan(command, true);
        if (code.isBlank()) return false;
        Matcher r = REDIRECT.matcher(code);
        while (r.find()) {
            String target = unquote(r.group(1));
            if (isDeviceOrFd(target)) continue;
            if (outside(target, roots)) return true;
        }
        Matcher dd = DD_OF.matcher(code);
        while (dd.find()) {
            String target = unquote(dd.group(1));
            if (isDeviceOrFd(target)) continue;
            if (outside(target, roots)) return true;
        }
        for (String segment : code.split("(?i)&&|\\|\\||[;\\n]")) {
            if (!COPY_LIKE.matcher(segment).find()) continue;
            String last = lastWord(segment);
            if (last.isEmpty() || isDeviceOrFd(last)) continue;
            if (outside(last, roots)) return true;
        }
        return false;
    }

    // ==================== 惰性文本中和 ====================

    /**
     * 引号/heredoc 正文里被换成的占位字符：既不是 shell 运算符，也不是空白——原文里的 `>`、`;`
     * 因此不会被 {@link #REDIRECT} 等正则命中，而路径里的字母与斜杠原样保留（越界判断还要用）。
     */
    private static final char INERT = '\u0001';

    /** 一个 heredoc 的定界信息：定界符、正文是否按字面处理（引号/反斜杠定界即字面）、是否 `<<-`。 */
    private record Heredoc(String delimiter, boolean literal, boolean stripTabs) {
    }

    /**
     * 只保留「shell 真正会解析成命令/运算符」的部分，其余惰性文本换成 {@link #INERT}。
     * <p>惰性文本包括：单引号串（整体）、双引号串里的字面段、heredoc 正文（`<<'EOF'` 引号定界
     * 整段都是字面；`<<EOF` 未引用时正文里只有 {@code $(...)} 与反引号会执行，其余字面）、
     * 行注释。命令替换（{@code $(...)}、反引号）无论出现在哪都递归按代码处理，删除/外发藏在
     * 替换里照样能命中。
     * <p>刻意<b>不</b>追的：`sh -c "…"` 这类把命令塞进引号再交给另一个 shell 的写法按字面处理——
     * 粗判不是解释器（base64、脚本文件同样绕得过），这一支由沙盒写收束与执行器兜底。
     *
     * @param keepQuotedText 引号里的字面文本是否保留。判「命令/外发」时给 false——引号里的字母
     *                       也不该被当成命令；判「重定向目标/写入路径」时给 true，否则
     *                       {@code > "/etc/secret"} 这种引号路径取不出来做越界判断。
     *                       两种视图里引号内的运算符（{@code >}、{@code ;}、{@code &}、
     *                       {@code |}、换行）一律换成 {@link #INERT}，惰性文本永远不会被当成运算符。
     */
    private static String scan(String command, boolean keepQuotedText) {
        if (command == null || command.isEmpty()) return "";
        StringBuilder out = new StringBuilder(command.length());
        Deque<Heredoc> heredocs = new ArrayDeque<>();
        int i = 0;
        int n = command.length();
        while (i < n) {
            char c = command.charAt(i);
            if (c == '\n' && !heredocs.isEmpty()) {
                out.append('\n');
                i = consumeHeredocBodies(command, i + 1, heredocs, out, keepQuotedText);
                continue;
            }
            if (c == '\'') {
                int end = command.indexOf('\'', i + 1);
                int stop = end < 0 ? n : end;
                out.append('\'');
                for (int k = i + 1; k < stop; k++) {
                    out.append(keepQuotedText ? inertInSingleQuote(command.charAt(k)) : INERT);
                }
                if (end >= 0) {
                    out.append('\'');
                    i = end + 1;
                } else {
                    i = n;
                }
                continue;
            }
            if (c == '"') {
                i = copyDoubleQuoted(command, i, out, keepQuotedText);
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                char next = command.charAt(i + 1);
                out.append('\\').append(isOperatorChar(next) ? INERT : next);
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < n && command.charAt(i + 1) == '(') {
                int end = matchingParen(command, i + 1);
                if (end > 0) {
                    out.append("$(").append(scan(command.substring(i + 2, end), keepQuotedText)).append(')');
                    i = end + 1;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            if (c == '`') {
                int end = matchingBacktick(command, i + 1);
                if (end > 0) {
                    out.append('`').append(scan(command.substring(i + 1, end), keepQuotedText)).append('`');
                    i = end + 1;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            if (c == '#' && (i == 0 || isWordBoundary(command.charAt(i - 1)))) {
                int end = command.indexOf('\n', i);
                int stop = end < 0 ? n : end;
                for (int k = i; k < stop; k++) out.append(INERT);
                i = stop;
                continue;
            }
            if (c == '(' && i + 1 < n && command.charAt(i + 1) == '(') {
                // 算术命令 ((…))：里面没有重定向/命令，`>` 是比大小、`<<` 是移位
                int end = matchingParen(command, i);
                if (end > 0) {
                    out.append("((");
                    appendOperatorLiteralBody(command, i + 2, Math.max(i + 2, end - 1), out, keepQuotedText);
                    out.append("))");
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[' && i + 1 < n && command.charAt(i + 1) == '['
                    && !(i + 2 < n && command.charAt(i + 2) == '[')) {
                // bash 的 [[ … ]]：里面的 > 是字符串比较，不是重定向
                int end = command.indexOf("]]", i + 2);
                if (end > 0) {
                    out.append("[[");
                    appendOperatorLiteralBody(command, i + 2, end, out, keepQuotedText);
                    out.append("]]");
                    i = end + 2;
                    continue;
                }
            }
            if (c == '<' && i + 1 < n && command.charAt(i + 1) == '<'
                    && (i + 2 >= n || command.charAt(i + 2) != '<')) {
                int[] cursor = { i + 2 };
                Heredoc heredoc = heredocAt(command, cursor);
                if (heredoc != null) {
                    out.append(command, i, cursor[0]);
                    heredocs.add(heredoc);
                    i = cursor[0];
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 双引号串：字面段按视图处理；`$(...)`/反引号仍会执行，递归按代码保留。 */
    private static int copyDoubleQuoted(String s, int start, StringBuilder out, boolean keepQuotedText) {
        int n = s.length();
        out.append('"');
        int i = start + 1;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char next = s.charAt(i + 1);
                out.append('\\').append(isOperatorChar(next) ? INERT : next);
                i += 2;
                continue;
            }
            if (c == '"') {
                out.append('"');
                return i + 1;
            }
            if (c == '$' && i + 1 < n && s.charAt(i + 1) == '(') {
                int end = matchingParen(s, i + 1);
                if (end > 0) {
                    out.append("$(").append(scan(s.substring(i + 2, end), keepQuotedText)).append(')');
                    i = end + 1;
                    continue;
                }
            }
            if (c == '`') {
                int end = matchingBacktick(s, i + 1);
                if (end > 0) {
                    out.append('`').append(scan(s.substring(i + 1, end), keepQuotedText)).append('`');
                    i = end + 1;
                    continue;
                }
            }
            out.append(keepQuotedText ? inertInDoubleQuote(c) : INERT);
            i++;
        }
        return i;
    }

    /** heredoc 正文按定界符顺序消费；`<<-` 允许定界行带前导制表符。 */
    private static int consumeHeredocBodies(String s, int from, Deque<Heredoc> heredocs, StringBuilder out,
                                            boolean keepQuotedText) {
        int n = s.length();
        int i = from;
        while (!heredocs.isEmpty()) {
            Heredoc h = heredocs.poll();
            while (true) {
                int lineEnd = s.indexOf('\n', i);
                boolean last = lineEnd < 0;
                int stop = last ? n : lineEnd;
                boolean delimiterLine = delimiterAt(s, i, stop, h);
                if (h.literal() || delimiterLine) {
                    for (int k = i; k < stop; k++) out.append(INERT);
                } else {
                    appendExpandedBodyLine(s, i, stop, out, keepQuotedText);
                }
                i = last ? n : lineEnd + 1;
                if (last) break;
                out.append('\n');
                if (delimiterLine) break;
            }
        }
        return i;
    }

    private static boolean delimiterAt(String s, int from, int to, Heredoc h) {
        int start = from;
        if (h.stripTabs()) {
            while (start < to && s.charAt(start) == '\t') start++;
        }
        return s.substring(start, to).equals(h.delimiter());
    }

    /** 未引用定界的 heredoc 正文：字面段中和，`$(...)`/反引号仍会展开执行，递归保留。 */
    private static void appendExpandedBodyLine(String s, int from, int to, StringBuilder out,
                                               boolean keepQuotedText) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '$' && i + 1 < to && s.charAt(i + 1) == '(') {
                int end = matchingParen(s, i + 1);
                if (end > 0 && end < to) {
                    out.append("$(").append(scan(s.substring(i + 2, end), keepQuotedText)).append(')');
                    i = end + 1;
                    continue;
                }
            }
            if (c == '`') {
                int end = matchingBacktick(s, i + 1);
                if (end > 0 && end < to) {
                    out.append('`').append(scan(s.substring(i + 1, end), keepQuotedText)).append('`');
                    i = end + 1;
                    continue;
                }
            }
            out.append(INERT);
            i++;
        }
    }

    /**
     * 算术 {@code ((…))} 与 {@code [[ … ]]} 的正文：这里的 {@code >}、{@code <}、{@code ;} 是运算符
     * 而不是重定向/命令分隔。字母与数字原样保留（不影响可读性），命令替换仍递归按代码处理。
     */
    private static void appendOperatorLiteralBody(String s, int from, int to, StringBuilder out,
                                                  boolean keepQuotedText) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '$' && i + 1 < to && s.charAt(i + 1) == '(') {
                int end = matchingParen(s, i + 1);
                if (end > 0 && end < to) {
                    out.append("$(").append(scan(s.substring(i + 2, end), keepQuotedText)).append(')');
                    i = end + 1;
                    continue;
                }
            }
            if (c == '`') {
                int end = matchingBacktick(s, i + 1);
                if (end > 0 && end < to) {
                    out.append('`').append(scan(s.substring(i + 1, end), keepQuotedText)).append('`');
                    i = end + 1;
                    continue;
                }
            }
            out.append(switch (c) {
                case '>', '<', ';', '&', '|', '\n', '\r' -> INERT;
                default -> c;
            });
            i++;
        }
    }

    /** 认 `<<EOF` / `<<'EOF'` / `<<-EOF` / `<<\EOF`；`<<<`（herestring）与 `<< 2` 这类不算。 */
    private static Heredoc heredocAt(String s, int[] cursor) {
        int n = s.length();
        int i = cursor[0];
        boolean stripTabs = false;
        if (i < n && s.charAt(i) == '-') {
            stripTabs = true;
            i++;
        }
        while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        if (i >= n) return null;
        char c = s.charAt(i);
        if (c == '\'' || c == '"') {
            int end = s.indexOf(c, i + 1);
            if (end <= i + 1) return null;
            cursor[0] = end + 1;
            return new Heredoc(s.substring(i + 1, end), true, stripTabs);
        }
        if (c == '\\') {
            int start = i + 1;
            int end = start;
            while (end < n && isNameChar(s.charAt(end))) end++;
            if (end == start) return null;
            cursor[0] = end;
            return new Heredoc(s.substring(start, end), true, stripTabs);
        }
        int start = i;
        while (i < n && isNameChar(s.charAt(i))) i++;
        if (i == start || !isNameStart(s.charAt(start))) return null;
        cursor[0] = i;
        return new Heredoc(s.substring(start, i), false, stripTabs);
    }

    /** 从 {@code open}（指向 `(`）找配对的 `)`；跳过引号与转义，找不到返回 -1。 */
    private static int matchingParen(String s, int open) {
        int depth = 0;
        int i = open;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\'') {
                int end = s.indexOf('\'', i + 1);
                if (end < 0) return -1;
                i = end + 1;
                continue;
            }
            if (c == '"') {
                int end = doubleQuoteEnd(s, i + 1);
                if (end < 0) return -1;
                i = end + 1;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    private static int doubleQuoteEnd(String s, int from) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') return i;
            i++;
        }
        return -1;
    }

    private static int matchingBacktick(String s, int from) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '`') return i;
            i++;
        }
        return -1;
    }

    private static char inertInSingleQuote(char c) {
        return switch (c) {
            case '>', ';', '&', '|', '$', '`', '\n', '\r', '\t', ' ' -> INERT;
            default -> c;
        };
    }

    private static char inertInDoubleQuote(char c) {
        return switch (c) {
            case '>', ';', '&', '|', '\n', '\r', '\t', ' ' -> INERT;
            default -> c;
        };
    }

    private static boolean isOperatorChar(char c) {
        return c == '>' || c == ';' || c == '&' || c == '|' || c == '\n';
    }

    private static boolean isWordBoundary(char c) {
        return Character.isWhitespace(c) || c == ';' || c == '|' || c == '&'
                || c == '(' || c == ')' || c == '{' || c == '}';
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
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
