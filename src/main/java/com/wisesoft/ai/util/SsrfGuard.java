package com.wisesoft.ai.util;

import com.wisesoft.ai.common.BizException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF 防护（服务端发起 HTTP 请求的唯一出口校验）：仅 http/https；主机解析出的所有地址
 * （含 IPv6）都不得为回环/站点内网/任意/链路本地/多播地址——服务端抓取不能变成内网探测通道。
 * <p>
 * 从 DocumentService.requirePublicHost 提取共用（M3 工作流 HTTP 节点同口径）；
 * 调用方注意：重定向必须手动逐跳跟进、每跳重新过本校验（自动跟随会绕过单次校验）。
 *
 * @author yuanke
 */
public final class SsrfGuard {

    private SsrfGuard() {
    }

    /** 校验 URI 目标为公网地址，不合法抛 BizException（fail-loud） */
    public static void requirePublicHost(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new BizException("仅支持 http/https 链接");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new BizException("URL 缺少主机名");
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new BizException("无法解析主机: " + host);
        }
        for (InetAddress addr : addrs) {
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isMulticastAddress()) {
                throw new BizException("禁止访问内网/本机地址（" + host + "）");
            }
        }
    }
}
