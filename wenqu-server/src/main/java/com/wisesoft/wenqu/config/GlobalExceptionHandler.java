package com.wisesoft.wenqu.config;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.BizException;
import com.wisesoft.wenqu.common.ResultJson;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
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
 * <p>两条错误契约并存（与 {@code controller/} 下的两套路由一一对应）：
 * <ol>
 *   <li><b>本产品既有契约</b>（{@code /api/ai/**}）：{@link BizException} → {@code {success:false, code, msg}}；</li>
 *   <li><b>参考实现契约</b>（{@code /api/mentions}、{@code /api/knowledge/**} 等路由层）：
 *       {@link ApiHttpException} → {@code {"detail": "..."}}，对应参考实现 FastAPI 的 HTTPException
 *       默认响应体形状。</li>
 * </ol>
 *
 * <p>本类为参考实现所无（FastAPI 内建），属平台差异载体；既有契约部分逐函数对齐本项目原
 * {@code GlobalExceptionHandler}（该文件此前未随 controller 一起搬入本工程，本次补齐）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 参考实现契约：HTTPException(status_code, detail, headers) → {@code {"detail": ...}} + 状态码 + 响应头。
     *
     * <p>{@code detail} 可为字符串或结构化对象（对象优先，对应参考实现 CLI 授权链路的 dict detail）。
     */
    @ExceptionHandler(ApiHttpException.class)
    public ResponseEntity<Map<String, Object>> handleApiHttp(ApiHttpException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", e.getDetailObject() != null ? e.getDetailObject() : e.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.getStatus());
        if (e.getHeaders() != null) {
            e.getHeaders().forEach(builder::header);
        }
        return builder.body(body);
    }

    /**
     * 必填请求参数缺失（对应 FastAPI 必填 Query/Form 缺失的 422）。
     *
     * <p>平台差异：此处不引入本处理器时 Spring 会落到 {@code Exception} 兜底返回 500，
     * 与参考实现的 422 不符，故显式映射；detail 为文本（参考实现为结构化列表，见类注解）。
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("detail", "缺少必填参数: " + e.getParameterName()));
    }

    /** 请求参数类型不匹配（对应 FastAPI 的 422）。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("detail", "参数类型错误: " + e.getName()));
    }

    /** 请求体缺失或无法解析（对应 FastAPI 的 422）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("请求体无法解析: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("detail", "请求体缺失或格式错误"));
    }

    /**
     * 参数校验失败（{@code @Min/@Max} 等 Jakarta 约束，对应参考实现 Query(ge/le)）。
     * <p>平台差异：参考实现由 FastAPI 返回 422 + 结构化 detail 列表；此处返回 422 + 文本 detail，
     * 约束生效范围与参考一致（待逐字对齐结构化 detail 时再补）。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException e) {
        String detail = "参数校验失败";
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            if (violation.getMessage() != null) {
                detail = violation.getPropertyPath() + ": " + violation.getMessage();
                break;
            }
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", detail));
    }

    /**
     * 业务异常（本产品既有契约）。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ResultJson<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(e.getCode() >= 400 && e.getCode() < 600
                        ? e.getCode() : HttpStatus.BAD_REQUEST.value())
                .body(ResultJson.error(e.getCode(), e.getMessage()));
    }

    /**
     * 参数校验失败（@Valid）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResultJson<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = "参数错误";
        FieldError fe = e.getBindingResult().getFieldError();
        if (fe != null && fe.getDefaultMessage() != null) {
            msg = fe.getDefaultMessage();
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultJson.error(400, msg));
    }

    /**
     * 上传文件超过大小限制（spring.servlet.multipart.*）。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ResultJson<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过大小限制: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ResultJson.error(413, "文件大小超过上传限制"));
    }

    /**
     * 资源不存在。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ResultJson<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ResultJson.error(404, "请求的资源不存在"));
    }

    /**
     * 请求体不是 multipart（如未携带文件字段/Content-Type 错误）：400 而非 500 兜底。
     */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ResultJson<Void>> handleMultipart(org.springframework.web.multipart.MultipartException e) {
        log.debug("非法 multipart 请求: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResultJson.error(400, "请求格式错误：需要 multipart/form-data 文件上传"));
    }

    /**
     * 兜底：未知异常，不向客户端泄露内部信息。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResultJson<Void>> handleUnknown(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResultJson.error(500, "系统繁忙，请稍后重试"));
    }
}
