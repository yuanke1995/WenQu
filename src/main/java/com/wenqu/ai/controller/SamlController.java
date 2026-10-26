package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.IdentityProvider;
import com.wenqu.ai.service.SamlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SAML 2.0 单点登录端点：公开配置 / 登录入口。
 * <p>
 * 兑换端点复用 {@code POST /api/ai/auth/oidc/exchange-code}（SSO 一次性 code 兑换，与协议无关，
 * 存储在 {@code SsoLoginCodeService}）；ACS 端点（{@code /login/saml2/sso/{id}}）不在
 * {@code /api/**} 拦截器范围内，由 {@code SamlSecurityConfig} 的 SAML 过滤链处理。
 * 这些端点发生在登录**之前**，列入 {@code SecurityConfig.isAuthBootstrapEndpoint} 免登录门禁。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/auth/saml")
@RequiredArgsConstructor
@Tag(name = "单点登录（SAML）", description = "SAML 2.0 登录入口与公开配置；回调由 Spring Security SAML2 过滤链处理")
public class SamlController {

    private final SamlService samlService;

    @Operation(summary = "SAML 可用认证源清单", description = "回 {providers:[{id,name}…]}：仅 enabled 且配置完整的认证源；清单为空时前端不显示 SAML 登录入口")
    @GetMapping("/config")
    public ResultJson config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providers", samlService.listPublic());
        return ResultJson.ok(m);
    }

    /**
     * 登录入口：校验认证源可用后 302 到 {@code /saml2/authenticate/{id}}（Spring Security
     * 生成 AuthnRequest 并重定向 IdP）。redirectPath 暂存 session，ACS 成功后原样带回前端。
     */
    @Operation(summary = "发起 SAML 登录", description = "302 到 IdP 的 SSO 地址；redirectPath 在登录成功后由前端接续跳转")
    @GetMapping("/login/{providerId}")
    public ResponseEntity<Void> login(@PathVariable String providerId,
                                      @RequestParam(value = "redirectPath", required = false) String redirectPath,
                                      HttpServletRequest request) {
        IdentityProvider p;
        String error = null;
        try {
            p = samlService.loadUsable(providerId);
        } catch (BizException e) {
            p = null;
            error = e.getMessage();
        }
        if (p == null) {
            log.warn("[SAML] 登录入口被拒 provider={}: {}", providerId, error);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(samlService.loginErrorRedirect(error == null ? "该认证源不可用或已停用" : error)))
                    .build();
        }
        // AuthnRequest 的 InResponseTo 状态在 Spring Security 侧存 session，这里同 session 暂存 redirectPath
        HttpSession session = request.getSession(true);
        session.setAttribute("saml.redirectPath." + providerId,
                redirectPath == null || redirectPath.isBlank() ? "/" : redirectPath);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/saml2/authenticate/" + providerId))
                .build();
    }
}
