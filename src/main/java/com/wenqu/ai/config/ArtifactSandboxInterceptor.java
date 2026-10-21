package com.wenqu.ai.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 产物响应隔离拦截器。
 * <p>产物正文是模型生成的，HTML 里可以带脚本。而 {@code /ai/artifacts/**} 与前端同域——用户一旦把产物
 * 地址直接开成一个标签页，那份脚本就跑在本域源上，能读 {@code localStorage.ai_token}（登录令牌），
 * 等于把「模型输出」升级成存储型 XSS。
 * <p>{@code Content-Security-Policy: sandbox} 让<b>顶层导航</b>到的这份文档落进不透明源：碰不到父窗口、
 * 本域存储与 cookie 作用域下的身份，脚本仍可运行（产物页里的动效因此还在）。刻意不给 {@code allow-same-origin}——
 * 它与 {@code allow-scripts} 同时出现等于让内容自己摘掉沙箱。
 * <p>该指令只作用于文档级响应：同一地址被 {@code <a download>} 下载、被 {@code <img>} 当图片引用时不受影响
 * （浏览器忽略非文档响应的 sandbox），所以不需要为预览另开一条路。
 *
 * @author yuanke
 */
public class ArtifactSandboxInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Content-Security-Policy", "sandbox allow-scripts");
        // 不嗅探类型：扩展名给的是什么就是什么，避免文本类产物被猜成 HTML 后当页面跑
        response.setHeader("X-Content-Type-Options", "nosniff");
        return true;
    }
}
