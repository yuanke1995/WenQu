package com.wisesoft.wenqu.common;

import lombok.Getter;

/**
 * 业务异常
 * 携带语义化错误码与用户可读消息，由全局异常处理器统一转为 JSON 响应
 *
 * @author yuanke
 */
@Getter
public class BizException extends RuntimeException {

    /** 语义化业务错误码（对应 ResultJson.code） */
    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
