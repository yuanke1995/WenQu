package com.wisesoft.wenqu.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 层异常：参考实现 {@code fastapi.HTTPException(status_code, detail, headers)} 的 Java 载体。
 *
 * <p>与 {@link BizException} 的分工（两者并存，不要互相替换）：
 * <ul>
 *   <li>{@link BizException} —— 本产品既有契约：响应体 {@code {success:false, code, msg}}，
 *       由 {@code GlobalExceptionHandler} 按 code 映射 HTTP 状态码；</li>
 *   <li>{@code ApiHttpException} —— 参考实现 {@code server/routers} 下各路由显式 raise 的
 *       {@code HTTPException}：响应体 {@code {"detail": "..."}}，HTTP 状态码即 raise 时给定的值，
 *       可携带响应头（如 {@code WWW-Authenticate}、{@code X-Lock-Remaining}）。</li>
 * </ul>
 *
 * <p>平台差异说明：FastAPI 的 HTTPException 是框架内建能力，Java/Spring MVC 无对应类型，
 * 故新增本类 + {@code GlobalExceptionHandler} 的分支来承载同样的「状态码 + detail + headers」语义，
 * **不改变任何路由的错误码与错误文案**。
 */
public class ApiHttpException extends RuntimeException {

    /** HTTP 状态码（对应参考实现 HTTPException 的 status_code） */
    private final int status;

    /** 附加响应头（可为 null） */
    private final transient Map<String, String> headers;

    public ApiHttpException(int status, String detail) {
        this(status, detail, null);
    }

    public ApiHttpException(int status, String detail, Map<String, String> headers) {
        super(detail);
        this.status = status;
        this.headers = headers == null ? null : new LinkedHashMap<>(headers);
    }

    public int getStatus() {
        return status;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
