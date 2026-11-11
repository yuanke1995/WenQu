package com.wenqu.ai.agentx.turn;

/**
 * 「定稿正文相对流式全文改写到什么程度」的唯一裁决器。
 * <p>这一族 bug（角标与面板错位、时间线失效、blocks 与正文两套账）的共同根因是同一个事实
 * 由多个消费方各自现场推导：{@code Recording.timelineJson} 用 {@code body.startsWith(answerText)}、
 * {@code TurnTranscriptRecorder.reconcileBody} 用「拼接全等 → 前缀 → 整体替换」、前端用
 * {@code finalContent !== content} 现场比对——判据边界只要有一处不一致，就会出现
 * 「blocks 对账成功、timeline 判成作废」或反之的错位。本类把裁决收敛成一处，
 * 所有消费方只许调用 {@link #classify}，不许再自行推导。
 */
public final class BodyReconcile {

    /** 定稿正文与流式全文的关系。 */
    public enum Verdict {
        /** 完全一致：没有改写发生，流式期间的一切区间/下标继续有效。 */
        UNCHANGED,
        /** 只在流式全文之后追加了尾段（related 并回、中断尾标、暗截断后缀）：前缀区间仍有效。 */
        APPENDED,
        /** 与流式全文分叉（引用自检剔角标/重编、回退正文）：按流式全文推出的正文区间全部作废。 */
        REPLACED
    }

    /**
     * 裁决：{@code streamedText} 是流式期间累计的全文（时间线/块投影各自的那份「答案全文」），
     * {@code finalBody} 是定稿正文。null 一律按空串处理，与两侧旧实现的空值语义一致。
     */
    public static Verdict classify(String streamedText, String finalBody) {
        String stream = streamedText == null ? "" : streamedText;
        String body = finalBody == null ? "" : finalBody;
        if (stream.equals(body)) return Verdict.UNCHANGED;
        return body.startsWith(stream) ? Verdict.APPENDED : Verdict.REPLACED;
    }

    private BodyReconcile() {}
}
