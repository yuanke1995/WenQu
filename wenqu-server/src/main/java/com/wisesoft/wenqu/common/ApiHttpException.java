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
 *
 * <p>{@code detail} 在参考实现里可以是字符串，也可以是结构化对象（如 CLI 授权链路的
 * {@code {"error": code, "message": message}}）；故除字符串 detail 外，另设
 * {@link #getDetailObject()} 承载对象型 detail（两者互斥，对象优先）。
 */
public class ApiHttpException extends RuntimeException {

    /** HTTP 状态码（对应参考实现 HTTPException 的 status_code） */
    private final int status;

    /** 附加响应头（可为 null） */
    private final transient Map<String, String> headers;

    /** 对象型 detail（对应参考实现 HTTPException 的 dict detail；为 null 时用字符串 detail） */
    private final transient Object detailObject;

    public ApiHttpException(int status, String detail) {
        this(status, detail, null, null);
    }

    public ApiHttpException(int status, String detail, Map<String, String> headers) {
        this(status, detail, headers, null);
    }

    public ApiHttpException(int status, String detail, Map<String, String> headers, Object detailObject) {
        super(detail);
        this.status = status;
        this.headers = headers == null ? null : new LinkedHashMap<>(headers);
        this.detailObject = detailObject;
    }

    /** 对象型 detail 的构造入口（参考实现 raise HTTPException(status, detail={...})）。 */
    public static ApiHttpException objectDetail(int status, String message, Object detailObject) {
        return new ApiHttpException(status, message, null, detailObject);
    }

    // ==================== 参考实现各路由高频使用的状态码便捷构造 ====================
    // 注意：这些只是「状态码 + detail」的语法糖，等价于 new ApiHttpException(status, detail)；
    // 不要用它们去「猜」状态码，必须与参考实现 raise HTTPException(status_code=...) 的取值逐字一致。

    /** 400 Bad Request。 */
    public static ApiHttpException badRequest(String detail) {
        return new ApiHttpException(400, detail);
    }

    /** 401 Unauthorized（可带 WWW-Authenticate 等响应头）。 */
    public static ApiHttpException unauthorized(String detail, Map<String, String> headers) {
        return new ApiHttpException(401, detail, headers);
    }

    /** 403 Forbidden。 */
    public static ApiHttpException forbidden(String detail) {
        return new ApiHttpException(403, detail);
    }

    /** 404 Not Found。 */
    public static ApiHttpException notFound(String detail) {
        return new ApiHttpException(404, detail);
    }

    /** 409 Conflict（字符串 detail）。 */
    public static ApiHttpException conflict(String detail) {
        return new ApiHttpException(409, detail);
    }

    /** 409 Conflict（结构化 detail，如 {"code": ..., "message": ...}）。 */
    public static ApiHttpException conflictWithDetail(Object detailObject) {
        return new ApiHttpException(409, null, null, detailObject);
    }

    /** 422 Unprocessable Entity（参考实现参数校验失败的标准状态码）。 */
    public static ApiHttpException unprocessable(String detail) {
        return new ApiHttpException(422, detail);
    }

    /** 500 Internal Server Error。 */
    public static ApiHttpException serverError(String detail) {
        return new ApiHttpException(500, detail);
    }

    public int getStatus() {
        return status;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Object getDetailObject() {
        return detailObject;
    }
}
