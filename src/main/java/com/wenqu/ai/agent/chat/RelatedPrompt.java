package com.wenqu.ai.agent.chat;

/**
 * 「回答末尾用 {@code <related>}… 输出 N 条下一步建议」这条提示词的唯一出处。
 *
 * <p>为什么要收成一处：这句提示词决定模型会不会在回答尾部产出推荐块，而<b>谁提示了就得谁负责剥</b>。
 * 两条运行时各抄一份的结果是两份措辞各自演进——一条路上推荐块被剥成芯片，另一条路上标签原样印进
 * 回答里（用户读到的是 {@code <related>建议1|建议2</related>}）。
 */
public final class RelatedPrompt {

    private RelatedPrompt() {
    }

    /** @param relatedCount 推荐条数（{@code retrieval.relatedCount}，最少 1；示例数量与它保持一致） */
    public static String line(int relatedCount) {
        int n = Math.max(1, relatedCount);
        StringBuilder ex = new StringBuilder("建议1");
        for (int i = 2; i <= n; i++) ex.append("|建议").append(i);
        return "\n回答末尾用 <related>" + ex + "</related> 输出 " + n
                + " 条下一步建议（用 | 分隔）：结合本轮回答与用户目标给出，可以是想确认清楚的追问、值得深入的方向，"
                + "也可以是马上能执行的操作，几条建议的类型不要雷同；每条要具体、可直接点选，如无合适建议可不输出。";
    }
}
