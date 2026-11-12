package com.wenqu.ai.common;

/**
 * 租户归属无法解析：请求带真实身份，但归属查询失败或账号已无归属。
 *
 * <p>fail-closed：宁可拒绝，也不把「解析不出来」当成「未绑定」去跨租户读写。
 */
public class TenantResolutionException extends RuntimeException {

    private final int code;

    public TenantResolutionException(String message) {
        this(503, message);
    }

    public TenantResolutionException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
