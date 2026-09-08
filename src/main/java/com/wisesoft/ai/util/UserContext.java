package com.wisesoft.ai.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户身份解析：平台网关完成 JWT 鉴权后透传用户标识（请求头 X-User-Id）。
 * <p>
 * - 无网关的本地/内部调试场景：请求未携带该头时统一归属 {@link #ANONYMOUS}（历史兼容池，
 *   anonymous 名下会话默认对所有用户可见（存量升级兼容），由 session.anonymous-shared 配置控制；
 *   关闭后该池仅 anonymous 调用方可访问）
 * - 生产环境网关必须覆盖/剥离客户端自带的 X-User-Id，防止身份伪造
 * - 解析结果做长度截断 + 字符白名单，防脏数据进 DB 与日志
 *
 * @author yuanke
 */
public final class UserContext {

    /** 网关透传用户标识的请求头名 */
    public static final String HEADER = "X-User-Id";

    /** 匿名兜底身份（无网关场景；该名下会话为全局共享的历史兼容池） */
    public static final String ANONYMOUS = "anonymous";

    private UserContext() {
    }

    /**
     * 从请求解析用户标识：X-User-Id（清洗后）→ anonymous
     */
    public static String resolve(HttpServletRequest request) {
        if (request == null) return ANONYMOUS;
        String uid = request.getHeader(HEADER);
        if (uid == null || uid.isBlank()) return ANONYMOUS;
        uid = uid.trim();
        if (uid.length() > 64) uid = uid.substring(0, 64);
        // 字符白名单：字母数字与常用分隔符（拒绝控制字符/中文/空格等，防注入与日志污染）
        if (!uid.matches("[A-Za-z0-9_@.\\-]+")) return ANONYMOUS;
        return uid;
    }
}