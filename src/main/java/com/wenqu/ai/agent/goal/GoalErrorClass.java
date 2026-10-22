package com.wenqu.ai.agent.goal;

import java.util.Locale;

/**
 * 失败分类（2.6.1）：不同失败要用不同恢复策略，盲目重试只会增加成本和风险。
 *
 * <p>分类是保守的：识别不出来就 {@link #UNKNOWN}，由调用方转暂停而不是无限重试。
 * 瞬时错误才允许有界退避；配置/权限/业务缺口都应停下来让人看见。
 */
public enum GoalErrorClass {

    /** 网络/限流/5xx/超时：有界退避后可重试。 */
    TRANSIENT,
    /** 模型/密钥/路由/额度配置问题：重试同样的坑还在，必须停下。 */
    CONFIG,
    /** 沙盒/工作区/依赖不可用：先重建环境，再决定是否重试。 */
    ENVIRONMENT,
    /** 权限/审批拒绝：等授权或终止。 */
    PERMISSION,
    /** 业务条件不满足/缺资料：明确缺口后转人工。 */
    BUSINESS,
    /** 进程崩溃/租约过期：结果未知，先对账再恢复。 */
    ORPHANED,
    /** 识别不出来：转暂停，不自动重试。 */
    UNKNOWN;

    /** 按异常/失败文案做关键词分类；宁可 UNKNOWN，不把配置错误当瞬时错误重试。 */
    public static GoalErrorClass of(String failure) {
        if (failure == null || failure.isBlank()) return UNKNOWN;
        String s = failure.toLowerCase(Locale.ROOT);
        if (containsAny(s, "rate limit", "429", "timeout", "timed out", "502", "503", "504",
                "connection reset", "connection refused", "temporarily unavailable", "overloaded")) {
            return TRANSIENT;
        }
        if (containsAny(s, "401", "403", "invalid api key", "unauthorized", "quota", "余额",
                "额度", "欠费", "model not found", "no route")) {
            return CONFIG;
        }
        if (containsAny(s, "sandbox", "docker", "no such container", "workspace", "disk",
                "沙盒", "工作区", "磁盘")) {
            return ENVIRONMENT;
        }
        if (containsAny(s, "permission", "approval", "denied", "forbidden", "not allowed",
                "权限", "审批", "拒绝")) {
            return PERMISSION;
        }
        if (containsAny(s, "missing", "not found", "缺少", "缺失", "资料", "依赖")) {
            return BUSINESS;
        }
        return UNKNOWN;
    }

    public boolean retryable() {
        return this == TRANSIENT || this == ENVIRONMENT;
    }

    private static boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }
}
