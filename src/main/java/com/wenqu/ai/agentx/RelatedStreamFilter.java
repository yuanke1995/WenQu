package com.wenqu.ai.agentx;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code <related>}／{@code <process>} 两个推荐块的流式剥离器（框架引擎侧）。
 *
 * <p>为什么框架侧要有自己的这一份：主链路的系统提示词里带一句「回答末尾用 &lt;related&gt;…&lt;/related&gt;
 * 输出 N 条下一步建议」（条数与文本走 {@code RelatedPrompt} 同一处，两条运行时不改两份）。模型照做之后，
 * 谁把这一段从正文里剥出来、把它变成前端那排「接下来可以」的问句，就是这条能力归谁的问题——
 * 框架引擎不接的后果不是少几颗芯片，而是<b>标签原文直接印在回答里</b>，用户看到的是一段
 * {@code <related>建议1|建议2</related>} 的脏文本。
 *
 * <p>扣留规则与手写侧同一条：只扣「确实是标签真前缀」的尾巴（最长 9 字符，如 {@code </related}），
 * 其余立即下发。固定扣 19 字符的老写法让正文恒滞后一个标签长度，而旁路事件（工具卡、产物）即时下发，
 * 实时时间线就会错序断词——这里不复现那个坑。
 *
 * <p>{@code <process>} 一并处理：块内内容进 {@link #process()}（落库的过程独白列 + 实时 {@code process} 帧），
 * 不混进正文。
 */
public final class RelatedStreamFilter {

    private static final String REL_OPEN = "<related>";
    private static final String REL_CLOSE = "</related>";
    private static final String PROC_OPEN = "<process>";
    private static final String PROC_CLOSE = "</process>";

    private final StringBuilder body = new StringBuilder();
    private final StringBuilder related = new StringBuilder();
    private final StringBuilder process = new StringBuilder();
    /** 尚未定性的一段尾巴：可能是标签的一部分，留着等下一个增量。 */
    private final StringBuilder pending = new StringBuilder();
    private Mode mode = Mode.BODY;

    private enum Mode {BODY, RELATED, PROCESS}

    /**
     * 吃进一段正文增量，返回此刻可以下发的部分（可能为空）。
     */
    public String feed(String delta) {
        if (delta == null || delta.isEmpty()) return "";
        pending.append(delta);
        StringBuilder out = new StringBuilder();
        while (pending.length() > 0) {
            String s = pending.toString();
            if (mode == Mode.BODY) {
                int r = s.indexOf(REL_OPEN);
                int p = s.indexOf(PROC_OPEN);
                int cut = firstHit(r, p);
                if (cut < 0) {
                    // 没有完整开标签：能确定不是标签前缀的部分立刻走，其余留着
                    int hold = prefixHoldLen(s);
                    if (hold < 0) {           // 整段尾巴都像标签前缀，全留
                        return out.toString();
                    }
                    out.append(s, 0, s.length() - hold);
                    pending.setLength(0);
                    pending.append(s.substring(s.length() - hold));
                    return out.toString();
                }
                boolean isRelated = r >= 0 && (p < 0 || r <= p);
                out.append(s, 0, cut);
                pending.setLength(0);
                pending.append(s.substring(cut + (isRelated ? REL_OPEN.length() : PROC_OPEN.length())));
                mode = isRelated ? Mode.RELATED : Mode.PROCESS;
            } else if (mode == Mode.RELATED) {
                int end = s.indexOf(REL_CLOSE);
                if (end < 0) {
                    int hold = closePrefixHold(s, REL_CLOSE);
                    if (hold < 0) return out.toString();
                    related.append(s, 0, s.length() - hold);
                    pending.setLength(0);
                    pending.append(s.substring(s.length() - hold));
                    return out.toString();
                }
                related.append(s, 0, end);
                pending.setLength(0);
                pending.append(s.substring(end + REL_CLOSE.length()));
                mode = Mode.BODY;
            } else {
                int end = s.indexOf(PROC_CLOSE);
                if (end < 0) {
                    int hold = closePrefixHold(s, PROC_CLOSE);
                    if (hold < 0) return out.toString();
                    process.append(s, 0, s.length() - hold);
                    pending.setLength(0);
                    pending.append(s.substring(s.length() - hold));
                    return out.toString();
                }
                process.append(s, 0, end);
                pending.setLength(0);
                pending.append(s.substring(end + PROC_CLOSE.length()));
                mode = Mode.BODY;
            }
        }
        return out.toString();
    }

    /**
     * 流收尾：把留着没定性的尾巴归位。开标签来了而闭合标签始终没来（输出触顶或被截断），
     * 剩下的就按它所在的那一块算——与手写侧「未闭合标签从开标签处整段剥掉」同一口径，
     * 否则用户读到的是半截标签。
     */
    public String finish() {
        String tail = pending.toString();
        pending.setLength(0);
        if (mode == Mode.RELATED) {
            related.append(tail);
            return "";
        }
        if (mode == Mode.PROCESS) {
            process.append(tail);
            return "";
        }
        // 正文里最后这点尾巴不是正文（它像标签），但它没长成开标签时才是正文
        if (looksLikeTagStart(tail)) {
            if (tail.startsWith(REL_OPEN)) return feed(tail.substring(REL_OPEN.length()));
            if (tail.startsWith(PROC_OPEN)) return feed(tail.substring(PROC_OPEN.length()));
            return "";
        }
        body.append(tail);
        return tail;
    }

    /** 已剥出的正文（不含推荐块与过程块）。 */
    public String body() {
        return body.toString();
    }

    /** 已剥出的过程独白。 */
    public String process() {
        return process.toString();
    }

    /**
     * 推荐问句（按 {@code |} 与换行拆条）。护栏与手写侧同一条：条目长过正文量级说明模型把
     * 正文写进了推荐块，「剥离」等于把回答吃掉——这种条目不当芯片，由调用方并回正文。
     */
    public List<String> chips(int proseMinChars) {
        List<String> chips = new ArrayList<>();
        for (String q : related.toString().split("[|\n]")) {
            String t = q.trim();
            if (t.isEmpty()) continue;
            if (t.length() > proseMinChars) continue;
            chips.add(t);
        }
        return chips;
    }

    /** 推荐块里那些「其实是正文」的长条目（要并回回答的那些）。 */
    public List<String> prose(int proseMinChars) {
        List<String> proseItems = new ArrayList<>();
        for (String q : related.toString().split("[|\n]")) {
            String t = q.trim();
            if (!t.isEmpty() && t.length() > proseMinChars) proseItems.add(t);
        }
        return proseItems;
    }

    public boolean hasRelated() {
        return related.length() > 0;
    }

    private static int firstHit(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 尾巴里「可能是标签真前缀」的那段长度；返回 -1 表示整段都在疑似状态。 */
    private int prefixHoldLen(String s) {
        int max = REL_OPEN.length() - 1;
        for (int i = Math.max(0, s.length() - max); i < s.length(); i++) {
            String tail = s.substring(i);
            if (isProperPrefix(tail, REL_OPEN) || isProperPrefix(tail, PROC_OPEN)) {
                return s.length() - i;
            }
        }
        return s.isEmpty() ? -1 : 0;
    }

    /** 块内只可能出现闭合标签：扣留长度同理，返回 -1 表示整段都留着。 */
    private int closePrefixHold(String s, String closeTag) {
        int max = closeTag.length() - 1;
        for (int i = Math.max(0, s.length() - max); i < s.length(); i++) {
            if (isProperPrefix(s.substring(i), closeTag)) return s.length() - i;
        }
        return s.isEmpty() ? -1 : 0;
    }

    private static boolean isProperPrefix(String tail, String tag) {
        return !tail.isEmpty() && tail.length() < tag.length() && tag.startsWith(tail);
    }

    private static boolean looksLikeTagStart(String tail) {
        return tail.startsWith("<") && (isProperPrefix(tail, REL_OPEN) || isProperPrefix(tail, PROC_OPEN)
                || tail.startsWith(REL_OPEN) || tail.startsWith(PROC_OPEN));
    }
}
