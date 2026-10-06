package com.wenqu.ai.service;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 模型额度不足识别（<b>单一判定出口</b>）：把「这个模型/供应商没钱了」这件事从一堆异常里认出来。
 *
 * <h3>为什么必须集中在一处</h3>
 * 各家网关报额度不足的形态差异极大：HTTP 402（Payment Required，部分厂商）、HTTP 429 但 code 是
 * {@code insufficient_quota}（OpenAI 系，429 另一个含义是瞬时限流）、HTTP 403 且文案是
 * 「model not in account / 余额不足」（国内厂商常见，甚至不是余额而是套餐到期）、
 * 纯文案无状态码（如 DeepSeek 的「Insufficient Balance」）。此前这些形态全部原样冒泡：
 * 聊天侧被压成「AI 回复失败，请稍后重试」、向量侧在异步线程里只记一行日志——用户既没收到通知，
 * 也不知道该去哪里改。
 *
 * <h3>与「瞬时限流」的边界（关键）</h3>
 * 两者都可能返回 429，但性质完全相反：限流是<b>等一会就好</b>，额度不足是<b>等多久都不会好</b>——
 * 必须充值或换供应商。如果把限流也登记成额度不足，用户的模型会在一个正常的抖动后被永久标红、
 * 从选择器里禁选，反而制造出原本不存在的问题。因此判据不只是看状态码，<b>必须有额度语义</b>
 * （错误码、余额/配额类文案、或 402）才认定为额度不足；裸 429 明确判为限流，不登记。
 *
 * <h3>设计口径</h3>
 * <ul>
 *   <li><b>纯静态、无状态</b>：纯函数式判定，供聊天侧（{@link DynamicOpenAiChatModel}）与
 *       向量侧（{@link DynamicEmbeddingModel}）共用，避免两处各写一套口径必然漂移；</li>
 *   <li><b>识别 ≠ 处置</b>：这里只回答「是不是额度不足」，登记与通知由
 *       {@link ModelQuotaService} 负责——识别逻辑被多处调用，副作用必须收在一处；</li>
 *   <li><b>只认明确的额度语义</b>：宁可漏判（退化成原有的通用报错）也不误判——误判的代价是
 *       误禁一个能用的模型，漏判的代价只是少一条通知。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
public final class ModelQuotaGuard {

    private ModelQuotaGuard() {
    }

    /** 判据一：明确的额度/账务语义错误码（各家 OpenAI 兼容网关的 code 字段） */
    private static final String[] QUOTA_CODES = {
            "insufficient_quota",       // OpenAI / Azure：额度或余额不足（Azure 实名场景常见）
            "billing_hard_limit_reached",// OpenAI：已达到账单硬上限
            "billing_not_active",        // OpenAI：账单未激活（Azure 免费额度用尽即此码）
            "exceeded_quota",            // 阿里/腾讯等厂商的配额耗尽
            "quota_exhausted",
            "account_depleted",          // 部分网关：账户已耗尽
            "insufficient_balance",      // DeepSeek 系直接用此码/文案
            "arrearage",                 // 欠费
            "out_of_credit"
    };

    /** 判据二：额度语义文案（中英文都收——国产网关多无标准 code，只给中文提示） */
    private static final String[] QUOTA_TEXTS = {
            "余额不足", "额度不足", "配额不足", "配额耗尽", "额度耗尽", "额度已用尽", "配额已用尽",
            "账户余额", "账号余额", "余额已用尽", "欠费", "已欠费", "计费额度", "免费额度",
            "insufficient balance", "insufficient quota", "exceeded your current quota",
            "quota exceeded", "out of credit", "billing", "arrearage", "payment required",
            "account balance", "not in account",   // 「model not in account」：套餐内无此模型权限，实质同为额度问题
            "insufficient permissions to query the model"  // 同上，OpenAI 表述
    };

    /** 限流语义（与额度不足共用 429，必须靠它把瞬时限流排除出去） */
    private static final String[] RATE_LIMIT_TEXTS = {
            "rate limit", "rate_limit", "too many requests", "请求过于频繁", "触发限流",
            "rpm limit", "tpm limit", "concurrent"
    };

    /**
     * 判定是否为「额度不足」（余额不足 / 配额耗尽 / 套餐到期）。
     *
     * @param throwable 网关调用异常（可为任意 Throwable，内部剥到根因取文案）
     * @return true=额度不足；false=其它失败（含瞬时限流）
     */
    public static boolean isQuotaExhausted(Throwable throwable) {
        String text = allTextOf(throwable);
        if (text.isEmpty()) return false;

        // 先按错误码与额度文案认——这两者语义最硬
        if (containsAny(text, QUOTA_CODES) || containsAny(text, QUOTA_TEXTS)) return true;

        // 裸 402（Payment Required）就是账务语义，不需要文案佐证
        if (statusCodeOf(throwable) == 402) return true;

        // 到这里说明没有任何额度语义：哪怕是 429 也只是限流，等一会就好，不能登记成额度不足
        return false;
    }

    /**
     * 是否为「瞬时限流」——与 {@link #isQuotaExhausted} 互斥。
     * 用于给用户不同的文案指引：限流是「稍后重试」，额度不足是「去充值或换模型」。
     */
    public static boolean isRateLimited(Throwable throwable) {
        if (isQuotaExhausted(throwable)) return false;
        String text = allTextOf(throwable);
        if (statusCodeOf(throwable) == 429) return true;
        return containsAny(text, RATE_LIMIT_TEXTS);
    }

    /**
     * 用户可见文案（单一出口，保证前端各处口径一致）。
     *
     * @param providerName 供应商名（可空）
     * @return 额度不足时给出可行动指引；限流时说明等待即可
     */
    public static String userMessage(String providerName) {
        String who = providerName == null || providerName.isBlank() ? "模型服务" : ("「" + providerName.trim() + "」");
        return who + "账户额度不足或套餐已到期，请到「模型供应商」充值，或改用其他模型后重试";
    }

    /**
     * 剥到根因后收集全链路文案（异常链每层的 message + class 简单名）。
     * <p>为什么要全链路而不是只根因：Spring AI 的重试包装会让根因变成
     * {@code NonTransientAiException} 这类泛化异常，真正的 {@code insufficient_quota}
     * 往往留在中间层或原始响应体里，只看根因会漏判。
     */
    private static String allTextOf(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder(256);
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 12) {
            String msg = cur.getMessage();
            if (msg != null && !msg.isBlank()) sb.append(msg).append('\n');
            sb.append(cur.getClass().getSimpleName()).append('\n');
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 从异常链里找 HTTP 状态码。Spring AI / RestClient 的包装类各有各的取法，
     * 这里按已知类型逐个尝试；取不到返回 -1（判定时按「无状态码」处理）。
     */
    private static int statusCodeOf(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 12) {
            if (cur instanceof org.springframework.web.client.HttpStatusCodeException h) {
                return h.getStatusCode().value();
            }
            if (cur instanceof org.springframework.web.server.ResponseStatusException r) {
                return r.getStatusCode().value();
            }
            if (cur instanceof org.springframework.web.client.RestClientResponseException r) {
                return r.getStatusCode().value();
            }
            // OpenAI SDK / Spring AI 的客户端错误异常类名含 ClientErrorException，消息里带状态码
            String msg = cur.getMessage();
            if (msg != null) {
                int v = statusFromMessage(msg);
                if (v > 0) return v;
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return -1;
    }

    /** 从消息文本里解析状态码：命中 {@code 402}、{@code status:402}、{@code statusCode=429} 等常见写法 */
    private static int statusFromMessage(String msg) {
        String lower = msg.toLowerCase(Locale.ROOT);
        for (int code : new int[]{402, 429, 403}) {
            String[] patterns = {"status " + code, "status:" + code, "status=" + code,
                    "statuscode " + code, "statuscode=" + code, "statuscode:" + code,
                    "code " + code, "http " + code};
            for (String p : patterns) {
                if (lower.contains(p)) return code;
            }
        }
        return -1;
    }

    private static boolean containsAny(String text, String[] needles) {
        for (String n : needles) {
            if (!n.isEmpty() && text.contains(n)) return true;
        }
        return false;
    }
}