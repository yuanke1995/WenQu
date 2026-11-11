package com.wenqu.ai.config;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.openapi.OpenApiRegistry;
import com.wenqu.ai.openapi.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：统一转换为 JSON 响应。
 *
 * <p><b>两套响应形状按命名空间分流，不再让 SDK 在客户端猜</b>：
 * {@code /api/ai/v1/**} 一律回 OpenAI 形状 {@code {error:{message,type,code,request_id}}}，
 * 其余内部端点保持平台包络 {@code {success,msg}}。分流判据就是路径前缀，
 * 与 {@link SecurityConfig} 的鉴权错误体同一口径。</p>
 *
 * <p>错误信息仍不泄露内部细节：BizException 带的是业务文案，未知异常只回通用文案 + 日志。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<?> handleBiz(BizException e, HttpServletRequest request) {
        int code = e.getCode() >= 400 && e.getCode() < 600 ? e.getCode() : HttpStatus.BAD_REQUEST.value();
        return error(request, code, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = "参数错误";
        FieldError fe = e.getBindingResult().getFieldError();
        if (fe != null && fe.getDefaultMessage() != null) msg = fe.getDefaultMessage();
        return error(request, HttpStatus.BAD_REQUEST.value(), msg);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSize(MaxUploadSizeExceededException e, HttpServletRequest request) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return error(request, HttpStatus.PAYLOAD_TOO_LARGE.value(), "文件大小超过上传限制");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNotFound(NoResourceFoundException e, HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND.value(), "请求的资源不存在");
    }

    /** 405：WARN 记录方法与路径便于归因（多为外部扫描/误用），不走系统异常兜底 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                      HttpServletRequest request) {
        log.warn("请求方法不支持: {} {}", request.getMethod(), request.getRequestURI());
        return error(request, HttpStatus.METHOD_NOT_ALLOWED.value(), "请求方法不支持");
    }

    /** 请求体不是 multipart：400 而非 500 兜底 */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<?> handleMultipart(org.springframework.web.multipart.MultipartException e,
                                             HttpServletRequest request) {
        log.debug("非法 multipart 请求: {}", e.getMessage());
        return error(request, HttpStatus.BAD_REQUEST.value(), "请求格式错误：需要 multipart/form-data 文件上传");
    }

    /** 业务校验类非法参数：service 层广泛用它携带用户可读的拒绝原因，按 400 + 原文案返回 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        log.debug("业务校验拒绝: {}", e.getMessage());
        return error(request, HttpStatus.BAD_REQUEST.value(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnknown(Exception e, HttpServletResponse response, HttpServletRequest request) {
        log.error("系统异常", e);
        // SSE 通道（或响应已提交）写不回 JSON：强行序列化必然再抛一次转换失败并刷屏，只记日志
        if (isEventStream(response) || response.isCommitted()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR.value(), "系统繁忙，请稍后重试");
    }

    /** 异步请求已失效（客户端断开后容器通知写失败）：没有响应可写，静默收尾 */
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
    public void handleAsyncClientGone(org.springframework.web.context.request.async.AsyncRequestNotUsableException e) {
        log.debug("客户端已断开，异步请求失效: {}", e.getMessage());
    }

    /** 命名空间决定形状：v1 走 OpenAI 错误体，内部端点走平台包络 */
    private ResponseEntity<?> error(HttpServletRequest request, int status, String message) {
        String path = request == null ? "" : request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(OpenApiRegistry.PREFIX)) {
            return ResponseEntity.status(status).body(ResultJson.error(status, message));
        }
        String type = typeOf(status);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message);
        err.put("type", type);
        err.put("code", type);
        String rid = RequestIdFilter.current();
        err.put("request_id", rid == null ? "" : rid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", err);
        return ResponseEntity.status(status).body(out);
    }

    private static String typeOf(int status) {
        return switch (status) {
            case 400, 405, 413 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            default -> "api_error";
        };
    }

    /** 响应是否为 SSE 流（Content-Type 由 handler 预设，异常分支上仍能读到） */
    private static boolean isEventStream(HttpServletResponse response) {
        String ct = response.getContentType();
        return ct != null && ct.contains("text/event-stream");
    }
}
