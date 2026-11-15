package com.wenqu.ai.service.openrun;

/**
 * v1 run 的稳定错误码（方案 §3「run.error 稳定 code」）。
 *
 * <p><b>分级的边界要说明白</b>：native SSE 的 error 帧 content 只是一句人话（没有 code 字段，
 * 见 {@code RunEvent.of} / {@code ChatRunRuntime.sendSseEvent}），所以从 native 失败能得到的
 * 只有「启发式分类 + 兜底」。真正确定的码来自 v1 自己产生的帧：{@code resume_expired}（回放
 * 缓冲过期）、{@code approval_timeout}（审批卡超时过程事件）、以及 HTTP 层的 400/401/403/429
 * （由 {@code GlobalExceptionHandler} 出 OpenAI 形状，不进 SSE）。</p>
 *
 * <p>契约保证的是「码集合只增不删、未知一律 internal_error」，不是「每个码都能精确归因」。
 * 调用方拿到 internal_error 时应看 message 与 request_id，不要拿 message 做分支。</p>
 */
public final class OpenRunErrorCode {

    /** 兜底：无法稳定归类的引擎失败 */
    public static final String INTERNAL_ERROR = "internal_error";
    /** 事件回放缓冲已过期，客户端应重拉 GET /runs/{id}（v1 自己产生，精确） */
    public static final String RESUME_EXPIRED = "resume_expired";
    /** 审批/计划卡等待超时（v1 自己产生，精确；同时发 approval.timed_out 过程事件） */
    public static final String APPROVAL_TIMEOUT = "approval_timeout";
    /** 限流（启发式：native 文案含限流语义） */
    public static final String RATE_LIMITED = "rate_limited";
    /** 智能体不存在（启发式；正常路径已在建轮时拦成 HTTP 400） */
    public static final String AGENT_NOT_FOUND = "agent_not_found";
    /** 请求参数不合法（HTTP 400，不进 SSE） */
    public static final String INVALID_REQUEST = "invalid_request";

    private OpenRunErrorCode() {
    }

    /** native 失败文案 → 稳定码。判据刻意保守：拿不准就 internal_error，不按子串乱猜。 */
    public static String classify(String nativeMessage) {
        if (nativeMessage == null || nativeMessage.isBlank()) return INTERNAL_ERROR;
        String m = nativeMessage.toLowerCase();
        if (m.contains("限流") || m.contains("请求过于频繁") || m.contains("频率")
                || m.contains("too many") || m.contains("rate limit") || m.contains("429")) {
            return RATE_LIMITED;
        }
        if (m.contains("智能体不存在") || m.contains("agent not found")) return AGENT_NOT_FOUND;
        return INTERNAL_ERROR;
    }

    /**
     * 是否是「审批/计划等待超时」的 warn 文案。
     * <p>原生 {@code TurnInteractionCoordinator.timeoutWarning} 有两种收敛文案（「计划等待超时…
     * 未继续执行」「确认等待超时…未继续执行该工具」），而 ask 卡前台等待结束那条是
     * 「提问前台等待结束，本轮先继续」——那是继续不是超时，必须排除，否则会把正常轮标成超时。</p>
     */
    public static boolean isApprovalTimeoutWarn(String text) {
        if (text == null || text.isBlank()) return false;
        return text.contains("等待超时") && text.contains("未继续执行");
    }
}
