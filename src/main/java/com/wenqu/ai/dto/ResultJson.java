package com.wenqu.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 统一响应
 *
 * @author yuanke
 */
@Data
@Schema(description = "统一响应格式")
public class ResultJson<T> {
    @Schema(description = "是否成功", example = "true")
    private boolean success = true;
    @Schema(description = "业务状态码", example = "200")
    private int code = 200;
    @Schema(description = "提示信息", example = "请求成功")
    private String msg = "请求成功";
    @Schema(description = "响应数据")
    private T data;

    public static <T> ResultJson<T> ok(T data) {
        ResultJson<T> r = new ResultJson<>();
        r.setData(data);
        return r;
    }

    public static <T> ResultJson<T> ok(T data, String msg) {
        ResultJson<T> r = new ResultJson<>();
        r.setData(data);
        r.setMsg(msg);
        return r;
    }

    public static <T> ResultJson<T> error(String msg) {
        return error(500, msg);
    }

    public static <T> ResultJson<T> error(int code, String msg) {
        ResultJson<T> r = new ResultJson<>();
        r.setSuccess(false);
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }
}