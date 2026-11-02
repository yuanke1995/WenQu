package com.wenqu.ai.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析（限流身份与审计 IP 共用的唯一出处）。
 * <p>
 * 安全口径（2026-11-01 收口）：X-Forwarded-For 是<b>客户端可任意伪造</b>的请求头——即便 nginx
 * 用 {@code $proxy_add_x_forwarded_for} 追加了真实 IP，客户端自塞的首段仍原样留在链头。
 * 旧口径「无脑取首段」等于让限流身份与审计 IP 由请求方指定：伪造 XFF 即可无限轮换限流身份
 * （游客端点按 IP 限频形同虚设），审计日志里的 IP 也不可信。
 * <p>
 * 新规则：
 * <ol>
 *   <li>直连对端（remoteAddr）是回环/内网地址 → 前面确实站着我们自己的反代，
 *       取 XFF <b>最后一个</b>段——反代追加的那段才是真实客户端，首段是攻击者塞的；</li>
 *   <li>直连对端是公网地址 → 没有可信反代，XFF 整体不可信，一律用 remoteAddr。</li>
 * </ol>
 * 多层自建反代（LB → nginx → 应用）时最后一段是最内层反代看到的地址，仍在可信链内；
 * 若前面存在不受控的外部 LB，取到的是它的内网口——限流与审计宁严勿漏。
 *
 * @author yuanke
 */
public final class ClientIp {

    private ClientIp() {
    }

    /** Servlet 栈入口 */
    public static String of(HttpServletRequest request) {
        if (request == null) return "unknown";
        return resolve(request.getHeader("X-Forwarded-For"), request.getRemoteAddr());
    }

    /**
     * 核心规则：XFF 只在直连对端是可信反代时才看，且只认最后一段。
     *
     * @param xff        X-Forwarded-For 头原值（可为 null）
     * @param remoteAddr 直连对端地址（Servlet remoteAddr / reactive remoteAddress）
     */
    public static String resolve(String xff, String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) return "unknown";
        if (!isTrustedProxy(remoteAddr)) return remoteAddr.trim();
        if (xff == null || xff.isBlank()) return remoteAddr.trim();
        String[] hops = xff.split(",");
        // 从尾往前找第一个非空段：全空（畸形头）时回落 remoteAddr
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty()) return hop;
        }
        return remoteAddr.trim();
    }

    /**
     * 直连对端是否为可信反代：回环、RFC1918 内网段、链路本地（169.254/fe80）、
     * IPv6 ULA（fc/fd 开头）；IPv4-mapped IPv6（::ffff:a.b.c.d）归一到 IPv4 后同判。
     */
    public static boolean isTrustedProxy(String addr) {
        if (addr == null || addr.isBlank()) return false;
        String a = addr.trim().toLowerCase();
        if (a.startsWith("::ffff:")) a = a.substring(7);
        if (a.equals("::1") || a.equals("0:0:0:0:0:0:0:1")) return true;
        if (a.startsWith("fe80:") || a.startsWith("fc") || a.startsWith("fd")) return true;
        String[] parts = a.split("\\.");
        if (parts.length != 4) return false;
        try {
            int b1 = Integer.parseInt(parts[0]);
            int b2 = Integer.parseInt(parts[1]);
            return b1 == 127 || b1 == 10
                    || (b1 == 192 && b2 == 168)
                    || (b1 == 169 && b2 == 254)
                    || (b1 == 172 && b2 >= 16 && b2 <= 31);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
