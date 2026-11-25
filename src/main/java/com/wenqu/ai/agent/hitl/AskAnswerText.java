package com.wenqu.ai.agent.hitl;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 提问卡「作答 → 送模型的文本」的单一真源。
 * <p>原先这套规则在 {@code ChatController}、{@code ShareController}（经静态方法复用）与
 * {@code RunControlService}（另行复写一份）三处存在；复制品一旦漂移，同一次作答在
 * 登录端、游客端与开放运行端就会呈现给模型三种不同的形状。这里收口成一处。
 *
 * <p><b>为什么不能直接 {@code String.valueOf(answers)}</b>：Java 的 {@code List.toString()}
 * 会把中括号连同逗号一起交给模型（用户选「火锅」，模型读到 {@code [火锅]}）。
 * 多题按与问题下标对齐的 JSON 数组送——与 {@link HitlDecisionService} 的合并答案同形，
 * 问答记录卡才拆得出每题答复。
 *
 * <p><b>为什么留空要补占位句而不是过滤</b>：数组一旦变短，模型会把第 2 题的答案当成第 1 题的。
 * 那句占位前缀同时是前端把该题标成「未作答」的判据，也与超时/忽略两条出口同一个词根——
 * 三种「没答」在模型眼里不该是三种形状，空串更会被读成「答了但答不上来」。
 *
 * @author yuanke
 */
public final class AskAnswerText {

    /** 整题未作答时送出的占位句（空提交、留空补位共用） */
    public static final String UNANSWERED = "（用户未回答这一题（点了提交但没有选择答案））";

    private AskAnswerText() {
    }

    /** 作答请求体 → 与问题下标对齐的答案数组（缺省为空数组）。 */
    public static List<String> answersFromBody(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        Object raw = body.get("answers");
        if (raw instanceof List<?> list) {
            for (Object x : list) {
                if (x != null) out.add(String.valueOf(x));
            }
        }
        return out;
    }

    /** 答案数组 → 送给模型的文本：单题原样、多题 JSON 数组、空答不代编。 */
    public static String toModelText(List<String> answers) {
        if (answers == null || answers.isEmpty()) return UNANSWERED;
        List<String> one = answers.stream().map(String::trim)
                .map(x -> x.isEmpty() ? UNANSWERED : x).toList();
        return one.size() == 1 ? one.get(0) : JSON.toJSONString(one);
    }
}