package com.wenqu.ai.openapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 开放 API 调用的<b>归因</b>标记（B5 接入漏斗）。
 *
 * <p>为什么需要它：HTTP 状态码只能说明「失败了」，说不出「卡在哪」。同一个 403 可能是
 * 「这把 Key 没开通 runs.read」，也可能是「这个端点根本没登记进开放契约」；同一个 401 可能是
 * 「没带 Key」，也可能是「Key 过期了」。这些的解法完全不同（改 Key 授权 / 换端点 / 换 Key），
 * 所以由**拒绝发生的现场**自己写下原因，而不是事后从状态码猜。</p>
 *
 * <p>口径：一次请求只留一个原因，<b>先到先得</b>——最上游的失败点才是根因，后续（比如 Key 校验
 * 已判无效、登录门禁又补一句「请先登录」）不覆盖它。标记只挂在当前请求上，不落线程变量，
 * 不会跨请求串味。</p>
 *
 * @author yuanke
 */
public final class OpenApiCallAttr {

    /** 请求属性名：本次开放调用的归因（{@link OpenApiCallFilter} 在请求结束时读取） */
    public static final String ATTR = "wenqu.openapi.reason";

    public static final String OK = "ok";
    /** 请求没带任何可识别的凭据 */
    public static final String NO_CREDENTIAL = "no_credential";
    /** Key 形态不对 / 查不到 / 签名不匹配 */
    public static final String KEY_INVALID = "key_invalid";
    public static final String KEY_DISABLED = "key_disabled";
    public static final String KEY_EXPIRED = "key_expired";
    /** v1 路径未登记进契约（默认拒绝） */
    public static final String ENDPOINT_NOT_REGISTERED = "endpoint_not_registered";
    /** Key 拒绝名单：账号自服务与能力配置面（Key 不能管 Key） */
    public static final String KEY_DENIED = "key_denied";
    /** 缺 scope，实际写成 {@code scope_denied:<scope>} */
    public static final String SCOPE_DENIED = "scope_denied";
    public static final String RATE_LIMITED = "rate_limited";

    private OpenApiCallAttr() {
    }

    /** 打标记（首个原因生效，重复调用不改写）；不在 Web 请求里时静默忽略 */
    public static void mark(String reason) {
        HttpServletRequest req = current();
        if (req == null || reason == null || reason.isBlank()) return;
        if (req.getAttribute(ATTR) == null) req.setAttribute(ATTR, reason);
    }

    /** 缺 scope 的专用标记：把「缺哪一个」写进原因，否则看板上所有 403 长得一样 */
    public static void markScopeDenied(String scope) {
        mark(scope == null || scope.isBlank() ? SCOPE_DENIED : SCOPE_DENIED + ":" + scope);
    }

    /** 当前请求已记录的归因；没有则 null */
    public static String peek(HttpServletRequest req) {
        Object v = req == null ? null : req.getAttribute(ATTR);
        return v == null ? null : String.valueOf(v);
    }

    private static HttpServletRequest current() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }
}
