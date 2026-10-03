package com.wenqu.ai.config;

import com.wenqu.ai.service.ImageUrlSigner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 图片静态资源鉴权拦截器
 * 生产开启图片鉴权（AI_IMAGES_AUTH_ENABLED=true）时，校验请求中的 expire + sig 签名；
 * 未开启时直接放行（本地开发）
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class ImageAuthInterceptor implements HandlerInterceptor {

    private final ImageUrlSigner signer;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!signer.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        String expireStr = request.getParameter("expire");
        String sig = request.getParameter("sig");
        long expire = 0;
        if (expireStr != null && expireStr.matches("\\d+")) {
            expire = Long.parseLong(expireStr);
        }
        // 签名基准是**原始路径**（如 /ai/artifacts/…/xxx_2026年度销售额.csv），而浏览器对路径里的
        // 中文/空格做百分号编码，getRequestURI() 拿到的是编码后的串 → 直接比对必然失配 401
        // （症状：纯 ASCII 文件名能下载、中文文件名"无法下载"）。编码与不编码各验一次，任一匹配即放行。
        if (signer.verify(path, expire, sig) || verifyDecoded(path, expire, sig)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/plain;charset=UTF-8");
        try {
            response.getWriter().write("图片链接无效或已过期");
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 解码后再验一次（URLDecoder 把 + 转空格，但路径里浏览器本就不编码 +，原始串那次校验已覆盖） */
    private boolean verifyDecoded(String path, long expire, String sig) {
        try {
            return signer.verify(java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8),
                    expire, sig);
        } catch (Exception e) {
            return false;
        }
    }
}
