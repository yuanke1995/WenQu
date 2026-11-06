package com.wenqu.ai.agentx;

import java.util.HashSet;
import java.util.Set;

/**
 * 一轮的「有没有前进」账本：不看调用了多少次，看工具有没有带回新东西。
 * <p>
 * 为什么不能按「同一调用连续出现几次」判失控：模型换一个参数、换一把工具就能把「连续」打断，
 * 而整轮仍在原地。线上 2026-11-06 那一轮就是这样——`read_file` 同一段窗口读 30 次、中间夹一句
 * `grep`，9 分半跑了 154 次工具调用；2026-11-03 那一轮更直接：同一个 `ls` 原样跑了 33 次，
 * 目录里 192292 字节的 PNG 每次都出现在结果里。
 * <p>
 * 判据改成<b>结果有没有新信息</b>：
 * <ul>
 *   <li>读/查类工具：结果内容没出现过才算前进——换路径读到同一段内容不算；</li>
 *   <li>写/执行类工具：这次调用（工具名 + 参数 + 结果）没出现过才算前进——同样参数再写一次不推进；</li>
 *   <li>失败或被权限/闸门拒掉的一步不算前进：换一个参数再失败也还是原地踏步。</li>
 * </ul>
 * <p>
 * 两道阈值分开：先到 {@link #WRAP_UP_AT} 时由引擎插一句要求模型收尾；仍不动，到
 * {@link #STALL_AT} 就由引擎叫停这一轮。只拒单步挡不住绕闸，只提醒一次也挡不住不听的模型，
 * 所以「提醒 → 终止」必须是两条独立的动作，不能合成一个阈值。
 */
final class AgentxTurnProgress {

    /** 连续几步没有新信息就提醒模型收尾。 */
    static final int WRAP_UP_AT = 3;

    /** 连续几步没有新信息就判定这一轮失控。 */
    static final int STALL_AT = 5;

    /** 收尾提醒走框架 steer，下一次模型调用前生效。 */
    static final String WRAP_UP_TEXT =
            "你已经连续 3 步没有获得任何新信息（同样的调用返回同样的结果）。停止调用工具："
                    + "基于已有信息直接作答；已经完成的任务直接交付产物，不要再安排新的确认步骤。";

    /**
     * 读/查类工具：前进与否只看「读到了什么」。其余工具按「这次调用有没有出现过」判——
     * 写文件的结果文案永远一样，只有参数才能说明内容变了。
     */
    private static final Set<String> INFO_TOOLS = Set.of(
            "read_file", "list_files", "glob_files", "grep_files",
            "search_knowledge", "webSearch", "session_search", "session_list", "session_history",
            "memory_get", "memory_search",
            "w_q_resolve_library_id", "w_q_query_docs", "w_q_read_wiki_contents",
            "w_q_read_wiki_structure", "w_q_ask_wiki_question", "w_q_db_overview", "w_q_sql_query");

    /** 本轮见过的结果内容（读/查类）。 */
    private final Set<String> seenContent = new HashSet<>();
    /** 本轮见过的完整调用（写/执行类）：工具名 + 参数 + 结果。 */
    private final Set<String> seenCalls = new HashSet<>();

    private int staleStreak;
    private boolean wrapUpSent;

    /**
     * 记一条工具结果。
     *
     * @param name   工具名
     * @param args   这次调用的入参（JSON 文本）
     * @param result 输出文本（与工具卡落库同一份，已截断）
     * @param failed 失败或被拒：这一步没有让任何东西向前走
     */
    void note(String name, String args, String result, boolean failed) {
        if (name == null || name.isBlank()) return;
        if (novel(name, args, result, failed)) {
            staleStreak = 0;
        } else {
            staleStreak++;
        }
    }

    private boolean novel(String name, String args, String result, boolean failed) {
        if (failed) return false;
        String text = result == null ? "" : result;
        if (INFO_TOOLS.contains(name)) {
            return seenContent.add(text);
        }
        return seenCalls.add(name + '\u0000' + (args == null ? "" : args) + '\u0000' + text);
    }

    /** 当前连续无进展步数（排障与单测看）。 */
    int staleStreak() {
        return staleStreak;
    }

    /** 收尾提醒每轮只发一次：到了第一道阈值才返回 true。 */
    boolean takeWrapUp() {
        if (wrapUpSent || staleStreak < WRAP_UP_AT) return false;
        wrapUpSent = true;
        return true;
    }

    /** 已经连续无进展到该收口了。 */
    boolean stalled() {
        return staleStreak >= STALL_AT;
    }
}
