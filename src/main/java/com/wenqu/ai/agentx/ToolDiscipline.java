package com.wenqu.ai.agentx;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 跨工具分工段：一族的说明写不了「另一族更适合这类活」，这几句只在提示词里说一次。
 *
 * <p>「本机」在 SSH 与桌面两族之间指向不同机器：`ssh_*` 连的是远端主机，`cua*` 动的才是用户
 * 执行器所在的那台电脑。这条歧义、以及「查什么走哪一路」「单件还是批量」「拍板还是进度」
 * 「现写内容还是已落盘文件」五对边界，都不属于任何一把工具的说明——一族替另一族交代，
 * 只会两处都写、两处都漏。
 *
 * <p>工具自己的边界仍写在 {@code @Tool} 说明里：问渠没有 per-tool 提示词小节，说明是唯一通道。
 * 这里只放跨工具的「该用谁」，按本轮真挂了哪些工具现拼，<b>绝不提本轮不存在的工具名</b>，
 * 与 {@link WenquAgentxTools#dispatchDiscipline()} 同一口径。
 *
 * <p>纯函数，判据只有 {@code Set<工具名>}，可被单测钉住（{@code ToolDisciplineTest}）。
 *
 * @author yuanke
 */
public final class ToolDiscipline {

    private static final String SSH = "ssh_";
    private static final String DESKTOP = "cua";
    private static final String KNOWLEDGE = "search_knowledge";
    private static final String WEB = "webSearch";
    private static final String WEB_FETCH = "webFetch";
    private static final String HISTORY_CHAT = "searchConversations";
    private static final String HISTORY_EVENT = "session_event_search";
    private static final String RUN_CODE = "run_code";
    private static final String ASK_USER = "askUser";
    private static final String WRITE_TODO = "writeTodo";
    private static final String PRESENT_ARTIFACT = "presentArtifact";
    private static final String DELIVER_ARTIFACT = "deliver_artifact";

    private ToolDiscipline() {
    }

    /**
     * 本轮要追加进系统提示词的分工段（含前导空行；一条都不适用时返回空串）。
     *
     * @param mounted 本轮真实可调用的注册名（{@code turnGroupedNames} ∪ 已激活的框架工具名）
     */
    public static String guidance(Set<String> mounted) {
        if (mounted == null || mounted.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();

        // ① 本机 vs 远端：两族指向不同的机器
        if (hasPrefix(mounted, SSH)) {
            lines.add("「本机」「我本地」指跑着本机执行器的那台电脑。ssh_* 只连远端主机"
                    + (hasPrefix(mounted, DESKTOP)
                        ? "；开软件、点屏幕、截图用 cua_*，不要拿 ssh 别名绕过去。"
                        : "，不能打开或操作那台电脑上的软件。"));
        }

        // ② 查什么走哪一路：正文 / 时效 / 原文 / 历史正文 / 历史过程，几把工具的边界谁都替不了谁
        List<String> lookup = new ArrayList<>();
        if (has(mounted, KNOWLEDGE)) lookup.add("业务知识与文档用 search_knowledge");
        if (has(mounted, WEB)) lookup.add("时效与外部事实用 webSearch");
        if (has(mounted, WEB_FETCH)) lookup.add("已有网址要读原文用 webFetch");
        if (has(mounted, HISTORY_CHAT)) lookup.add("「我之前问过什么」用 searchConversations");
        if (has(mounted, HISTORY_EVENT)) lookup.add("「哪一轮跑过什么」用 session_event_search");
        if (!lookup.isEmpty()) {
            lines.add(String.join("；", lookup) + "。");
        }

        // ③ 批量 vs 单件
        if (has(mounted, RUN_CODE)) {
            lines.add("单件活直接调对应工具；run_code 只用于循环、批量、多件同类。");
        }

        // ④ 拍板 vs 进度
        if (has(mounted, ASK_USER) && has(mounted, WRITE_TODO)) {
            lines.add("要用户拍板才能继续用 askUser；只是让用户看进度用 writeTodo，别拿提问当清单。");
        }

        // ⑤ 现写的内容 vs 已落盘的文件
        if (has(mounted, PRESENT_ARTIFACT) && has(mounted, DELIVER_ARTIFACT)) {
            lines.add("现写的内容用 presentArtifact；已在工作区/沙盒里的文件用 deliver_artifact 按路径交付。");
        }

        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n\n## 工具分工\n");
        for (String line : lines) {
            out.append("- ").append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean has(Set<String> names, String exact) {
        return names.contains(exact);
    }

    private static boolean hasPrefix(Set<String> names, String prefix) {
        for (String n : names) {
            if (n != null && n.startsWith(prefix)) return true;
        }
        return false;
    }
}
