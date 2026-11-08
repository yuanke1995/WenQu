package com.wenqu.ai.agent.chat;

/**
 * 「工具调用只能落在一句话说完之后」这条提示词的唯一出处。
 *
 * <p><b>为什么要说这一句</b>：模型会在同一条 assistant 消息里先写半句、再发工具调用、拿到结果后
 * 接着把这句话写完。真机 2026-11-08：{@code 我会先查一下最} → {@code agent_spawn} →
 * {@code 近 30 天的会话和轮数数据…}。工具块会切断正文块（见 {@code TurnTranscriptRecorder.tool}），
 * 于是前半句留在过程块、后半句成了答案开头，用户读到的就是一句被工具卡劈开的话。
 * <p>根因在模型侧：调用前把话说完，或者干脆不写旁白；代码侧只在收尾时保证答案不从半句开始
 * （{@code TurnTranscriptRecorder#assignAnswerChannels}），不改写模型说过的内容。
 */
public final class ToolCallPrompt {

    private ToolCallPrompt() {
    }

    /** 挂进系统提示词的一条口径：调用工具前要么不写、要么写完整一句，拿到结果后另起一段。 */
    public static String guidance() {
        return "\n【工具调用与正文】调用工具（含派遣助手、提问、计划等）只能落在一句话说完之后："
                + "调用前可以先写一句完整的话并以句号、问号或感叹号收尾，也可以什么都不写；"
                + "不要在一句话中间、更不要在词中间调用工具。拿到工具结果后另起一段完整作答，"
                + "不要接着工具调用前那半句往下写。\n";
    }
}
