package com.wisesoft.wenqu.common;

import lombok.Data;

/**
 * 统一响应包装：前端只需判断 {@code success}，错误信息统一放 {@code msg}。
 */
@Data
public class ResultJson<T> {

    private boolean success = true;
    private int code = 200;
    private String msg;
    private T data;

    public static <T> ResultJson<T> ok(T data) {
        ResultJson<T> r = new ResultJson<>();
        r.setData(data);
        return r;
    }

    public static <T> ResultJson<T> ok(T data, String msg) {
        ResultJson<T> r = ok(data);
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
