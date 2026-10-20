package com.wenqu.ai.agent.chat;

// [保留] 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件，勿当作已退役的控制流删除。

/**
 * 「回答末尾用 {@code <related>}… 输出 N 条下一步建议」这条提示词的唯一出处。
 *
 * <p>为什么要收成一处：这句提示词决定模型会不会在回答尾部产出推荐块，而<b>谁提示了就得谁负责剥</b>。
 * 两条运行时各抄一份的结果是两份措辞各自演进——一条路上推荐块被剥成芯片，另一条路上标签原样印进
 * 回答里（用户读到的是 {@code <related>建议1|建议2</related>}）。
 */
public final class RelatedPrompt {

    /**
     * 下一步建议的条数：提示词里要几条、剥推荐块时就按这个数判超限。
     * <p>刻意做成常量而不是配置项——它是回答格式的一部分，不是一个可调的偏好；两处读取点
     * （挂提示词、{@code RelatedStreamFilter} 的条数闸）分头取值时，改一处会让另一处判错。
     */
    public static final int COUNT = 3;

    private RelatedPrompt() {
    }

    /** 建议条数与 {@link #COUNT} 同源，示例数量也跟着它 */
    public static String line() {
        int n = COUNT;
        StringBuilder ex = new StringBuilder("建议1");
        for (int i = 2; i <= n; i++) ex.append("|建议").append(i);
        return "\n回答末尾用 <related>" + ex + "</related> 输出 " + n
                + " 条下一步建议（用 | 分隔）：结合本轮回答与用户目标给出，可以是想确认清楚的追问、值得深入的方向，"
                + "也可以是马上能执行的操作，几条建议的类型不要雷同；每条要具体、可直接点选，如无合适建议可不输出。";
    }
}
