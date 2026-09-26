package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.OidcService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * 单点登录（OIDC）端点：配置探测 / 取授权地址 / IdP 回调 / 一次性 code 兑换。
 * <p>
 * 对应平台版 {@code server/routers/auth_router.py} 的 4 个 {@code /oidc/*} 路由。
 * 这些端点在登录**之前**就必须可达（否则无法登录 ⇒ 死锁），故全部列入
 * {@code SecurityConfig.isAuthBootstrapEndpoint} 免登录门禁。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/auth/oidc")
@RequiredArgsConstructor
@Tag(name = "单点登录（OIDC）", description = "OIDC 授权码流程：登录地址 / 回调 / code 兑换")
public class OidcController {

    private final OidcService oidcService;

    @Operation(summary = "OIDC 是否可用", description = "只回 {enabled, providerName}；未启用或配置不完整时 enabled=false，前端据此隐藏登录入口")
    @GetMapping("/config")
    public ResultJson config() {
        return ResultJson.ok(oidcService.publicConfig());
    }

    @Operation(summary = "获取授权地址", description = "返回 {loginUrl}（已带一次性 state）；redirectPath 为登录成功后前端要去的路径")
    @GetMapping("/login-url")
    public ResultJson loginUrl(@RequestParam(value = "redirectPath", required = false) String redirectPath,
                               HttpServletRequest request) {
        return ResultJson.ok(Map.of("loginUrl", oidcService.loginUrl(redirectPath, request)));
    }

    /**
     * IdP 回调：成功后 302 到前端回调页并带一次性 code；失败 302 回登录页并带 oidc_error。
     * <p>参数一律非必填——IdP 拒绝授权时会带 {@code error=access_denied} 而不带 code，
     * 若声明为必填会退化成 400 报错页，用户看不懂。</p>
     */
    @Operation(summary = "OIDC 回调", description = "由 IdP 重定向到此；302 跳回前端（成功带一次性 code，失败带 oidc_error）")
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(value = "code", required = false) String code,
                                         @RequestParam(value = "state", required = false) String state,
                                         @RequestParam(value = "error", required = false) String error,
                                         @RequestParam(value = "error_description", required = false) String errorDescription,
                                         HttpServletRequest request) {
        String target;
        if (error != null && !error.isBlank()) {
            // IdP 显式回绝（如用户点了取消授权）：把它的原因带回登录页，比笼统的"未返回授权码"有用得多
            String detail = errorDescription == null || errorDescription.isBlank() ? error : errorDescription;
            target = oidcService.loginErrorRedirect("IdP 拒绝了本次登录：" + detail);
        } else {
            target = oidcService.handleCallback(code, state, request);
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @Operation(summary = "用一次性 code 兑换登录结果", description = "{\"code\":\"...\"}；返回 {token, user, redirectPath}，code 一次性且 60 秒过期")
    @PostMapping("/exchange-code")
    public ResultJson exchangeCode(@RequestBody Map<String, String> body) {
        return ResultJson.ok(oidcService.exchangeCode(body.get("code")), "登录成功");
    }
}
