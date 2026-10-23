package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.OidcProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * OIDC 认证源管理（多 IdP）：CRUD + 启用停用。
 * <p>
 * 认证源是平台级资源（全平台共用一套登录入口），故写操作走管理员判定（fail-closed），
 * 不在 {@code SecurityConfig.isPublicUserEndpoint} 白名单中。路由挂在 {@code /api/ai/oidc/}
 * （非 {@code /auth/oidc/}），不会误触发登录引导端点的免登录豁免。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/oidc")
@RequiredArgsConstructor
@Tag(name = "OIDC 认证源", description = "单点登录多认证源管理（多 IdP）")
public class OidcProviderController {

    private final OidcProviderService providerService;

    @Operation(summary = "认证源列表", description = "全部认证源（按排序），clientSecret 脱敏为后 4 位")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(providerService.listMasked());
    }

    @Operation(summary = "新建认证源", description = "{\"name\":\"企业微信登录\",\"issuerUrl\":\"...\",\"clientId\":\"...\",\"clientSecret\":\"明文（保存时 RSA 加密）\",...}；默认角色须已启用")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(providerService.save(null, str(body.get("name")), bool(body.get("enabled")),
                str(body.get("issuerUrl")), str(body.get("clientId")), str(body.get("clientSecret")),
                str(body.get("redirectUri")), str(body.get("frontendBaseUrl")),
                str(body.get("authorizationEndpoint")), str(body.get("tokenEndpoint")),
                str(body.get("userinfoEndpoint")), str(body.get("scopes")), bool(body.get("forcePromptLogin")),
                str(body.get("usernameClaim")), str(body.get("nameClaim")), bool(body.get("useRawUsername")),
                bool(body.get("autoCreateUser")), str(body.get("defaultRole")), str(body.get("defaultDepartment")),
                bool(body.get("fetchDepartmentInfo")), str(body.get("departmentClaim")), intOrNull(body.get("sortOrder"))),
                "已创建");
    }

    @Operation(summary = "更新认证源", description = "字段同新建；clientSecret 留空或 **** 掩码表示不修改已存密钥")
    @PutMapping("/{id}")
    public ResultJson update(
            @Parameter(description = "认证源ID") @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        return ResultJson.ok(providerService.save(id, str(body.get("name")), bool(body.get("enabled")),
                str(body.get("issuerUrl")), str(body.get("clientId")), str(body.get("clientSecret")),
                str(body.get("redirectUri")), str(body.get("frontendBaseUrl")),
                str(body.get("authorizationEndpoint")), str(body.get("tokenEndpoint")),
                str(body.get("userinfoEndpoint")), str(body.get("scopes")), bool(body.get("forcePromptLogin")),
                str(body.get("usernameClaim")), str(body.get("nameClaim")), bool(body.get("useRawUsername")),
                bool(body.get("autoCreateUser")), str(body.get("defaultRole")), str(body.get("defaultDepartment")),
                bool(body.get("fetchDepartmentInfo")), str(body.get("departmentClaim")), intOrNull(body.get("sortOrder"))),
                "已保存");
    }

    @Operation(summary = "启用/停用认证源", description = "{\"enabled\":true|false}；停用后登录页不显示该入口")
    @PutMapping("/{id}/enabled")
    public ResultJson setEnabled(
            @Parameter(description = "认证源ID") @PathVariable("id") String id,
            @RequestBody Map<String, Object> body) {
        providerService.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
        return ResultJson.ok("已更新");
    }

    @Operation(summary = "删除认证源", description = "仍有账号绑定（成员管理）时拒绝，提示先解绑")
    @DeleteMapping("/{id}")
    public ResultJson delete(
            @Parameter(description = "认证源ID") @PathVariable("id") String id) {
        providerService.delete(id);
        return ResultJson.ok("已删除");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer bool(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) return 1;
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) return 0;
        return null;
    }

    private static Integer intOrNull(Object o) {
        try {
            return o == null ? null : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
