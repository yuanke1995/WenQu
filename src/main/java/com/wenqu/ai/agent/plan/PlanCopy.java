package com.wenqu.ai.agent.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划正文的「给用户看」口径：一句提示词（主修）+ 一道剥内部路径的兜底（保底）。
 *
 * <p><b>为什么要有这一处</b>：手写推理循环退役后，计划轮改由框架的
 * {@code plan_enter/plan_write/plan_exit} 产出，而问渠原先那条「计划只写用户关心的结果，
 * 不写工具名、能力名、内部做法与执行细节」的指令随手写阶段一起删了。框架这一路反而<b>主动</b>把
 * 内部路径喂给模型：{@code PlanModeMiddleware} 的 PLAN MODE banner 里带 {@code Plan file: <路径>}、
 * {@code plan_enter} 的工具结果里带「record your plan at "&lt;路径&gt;"」，
 * 沙盒几把工具的参数说明又举着 {@code /home/gem/user-data/report.md} 这种例子。
 * 模型照抄进计划正文，用户批准的就不是「它给我做成什么样」，而是「它在沙盒里怎么落地文件」。
 * <p>真机账（本机库 {@code c_ai_tool_approval.tool_name='planApproval'}）：2026-10-15 21:41/21:55/21:57
 * 三条计划卡印着 {@code /home/gem/user-data/index.html}，其中两条还写着
 * {@code 用 deliver_artifact 交付}；2026-10-14 及更早（还带那条指令的一族）一条都没有。
 * <p><b>两处各自管什么</b>：{@link #guidance()} 让模型不写；{@link #stripInternalPaths} 在模型仍写时
 * 保证用户看不见。兜底只作用于<b>权威稿</b>（计划卡正文、落库的 {@code plan} 列与版本链）——
 * 流式预览（{@code plan_delta}）是按字节切片的，逐片替换会把半截路径拼成乱码，
 * 而前端在 {@code plan_approval} 到达时以权威稿覆盖累积流式内容，所以预览段不处理，交给提示词。
 * <p>不剥工具名：正则猜「哪个词是工具名」要靠名单，名单必然随工具增减而漂移；工具名归提示词管。
 *
 * @author yuanke
 */
public final class PlanCopy {

    private PlanCopy() {
    }

    /**
     * 挂进系统提示词的一条口径（与 {@code RelatedPrompt.line} 同一种装配法：提示它 + 由代码兜底）。
     * <p>口径本身不是新规矩，是 2026-10-08 定案的那条「计划＝成品描述，不写工具名与内部做法」，
     * 只是框架路径下没人再说一遍。
     */
    public static String guidance() {
        return "\n【写给用户看的文字】回答正文、计划、任务清单都只讲用户看得懂的结果：不要出现沙盒或服务器上的"
                + "路径与工作目录（形如 /home/…、user-data/…、…/plans/PLAN.md），不要出现工具名、命令与执行细节。"
                + "要指代交付物就说它的名称和形态（如「单个网页文件 index.html」「一份可下载的 PDF」）——"
                + "文件存在哪个目录、用哪把工具、跑什么命令属于内部做法，用户不需要知道，写出来只会让他看不出"
                + "「他会拿到什么」。\n";
    }

    /** 路径样式的词元：至少两段、以 / 分隔（绝对路径以空段开头，故首段允许为空） */
    private static final Pattern PATH_TOKEN =
            Pattern.compile("[A-Za-z0-9_.+@~%-]*(?:/[A-Za-z0-9_.+@~%-]+)+/?");

    /** 沙盒里的工作区根（模型会照抄进计划的那些）：整词命中时换成用户读得懂的说法 */
    private static final List<String> SANDBOX_ROOTS = List.of(
            "/home/gem/user-data", "/home/gem", "/workspace", "/app/user-data", "user-data");

    /** 问渠自己的运行目录（框架状态目录、计划文件所在处） */
    private static final List<String> RUNTIME_ROOTS = List.of(
            "user-data/agentscope", "/.agentscope-runtime", "plans");

    private static final String WORKSPACE_WORD = "沙盒工作区";
    private static final String RUNTIME_WORD = "问渠的运行目录";
    private static final String PLAN_FILE_WORD = "本轮计划";

    /**
     * 兜底：把计划正文里的内部路径换成用户看得懂的说法，只保留交付物自身的名称。
     * <p>{@code /home/gem/user-data/index.html} → {@code index.html}（用户要认的就是这个文件名）；
     * {@code /home/gem/user-data} 这种「目录本身」→ 「沙盒工作区」；
     * {@code …/plans/PLAN.md} → 「本轮计划」。
     * <p>不动的东西：网址（{@code https://…} 与 {@code //cdn…} 这类，前一个字符是冒号或斜杠即跳过）、
     * 没有扩展名的一律当接口路径或分数（{@code /api/chat}、{@code 768/1023}）保持原样。
     *
     * @param text       计划权威稿正文（{@code planTextOf} 的产物）
     * @param extraRoots 配置里的同类根（沙盒虚拟根、框架状态目录），空或 null 只按内置名单
     */
    public static String stripInternalPaths(String text, List<String> extraRoots) {
        if (text == null || text.indexOf('/') < 0) return text;
        List<String> sandbox = merge(extraRoots, SANDBOX_ROOTS);
        List<String> runtime = merge(extraRoots, RUNTIME_ROOTS);
        Matcher m = PATH_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        while (m.find()) {
            String repl = rewrite(text, m.start(), m.end(), sandbox, runtime);
            if (repl == null) continue;
            out.append(text, copied, m.start()).append(repl);
            copied = m.end();
        }
        out.append(text, copied, text.length());
        return out.toString().replaceAll(" {2,}", " ");
    }

    /** 配置根排在内置根之前，整体按长度降序：长根先配才不会被它的父目录抢走 */
    private static List<String> merge(List<String> extra, List<String> builtin) {
        List<String> all = new ArrayList<>();
        if (extra != null) {
            for (String r : extra) if (r != null && !r.isBlank()) all.add(r.trim());
        }
        all.addAll(builtin);
        all.sort((a, b) -> b.length() - a.length());
        return all;
    }

    /** @return 替换文本；null=这个词元不动（网址、接口路径、分数之类） */
    private static String rewrite(String text, int start, int end,
                                  List<String> sandbox, List<String> runtime) {
        // 紧邻在前是冒号或斜杠＝网址的一段（https://host/a.png 里那截 //host/a.png 不是路径）
        if (start > 0) {
            char prev = text.charAt(start - 1);
            if (prev == ':' || prev == '/') return null;
        }
        String token = text.substring(start, end);
        if (token.contains("://")) return null;
        String path = token.endsWith("/") ? token.substring(0, token.length() - 1) : token;
        if (path.isBlank() || path.equals("/")) return null;

        // 框架那份计划文件：不是交付物，是内部稿
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.equalsIgnoreCase("PLAN.md") || path.contains("/plans/") || path.startsWith("plans/")) {
            return PLAN_FILE_WORD;
        }
        String hit = matchRoot(path, sandbox);
        if (hit == null) hit = matchRoot(path, runtime);
        if (hit != null) {
            boolean justRoot = path.equals(hit);
            if (justRoot) return hit.contains("agentscope") || hit.contains("plans") ? RUNTIME_WORD : WORKSPACE_WORD;
            return basename(path);   // 根之下的文件：留文件名，用户认得出的就是它
        }
        // 没落在名单里的绝对路径：只有末尾像文件名（带扩展名）才收成文件名，否则当接口路径放过
        if (path.startsWith("/") && last.contains(".") && last.matches(".*[A-Za-z].*")) return basename(path);
        return null;
    }

    /** 命中的根（等值或作为前缀目录）；都没命中返回 null */
    private static String matchRoot(String path, List<String> roots) {
        for (String root : roots) {
            if (path.equals(root) || path.startsWith(root + "/")) return root;
        }
        return null;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
