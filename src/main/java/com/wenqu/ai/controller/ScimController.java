package com.wenqu.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenqu.ai.model.GroupRoleMapping;
import com.wenqu.ai.model.IdentityProvider;
import com.wenqu.ai.model.User;
import com.wenqu.ai.service.ScimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SCIM 2.0 入站端点（RFC 7644，供 Entra/Okta 等 IdP 推送用户与组）。
 * <p>
 * 路径挂 {@code /api/ai/scim/v2/**}——刻意不在 {@code /api/ai/auth/} 前缀下（P7 后 auth 前缀
 * 只做精确枚举）；鉴权不走登录令牌：整条拦截链对本路径豁免，由 {@link ScimService#authenticate}
 * 在每个入口用独立 Bearer（认证源 config.bearerToken）强校验，认不出即 401。
 * 响应是 SCIM JSON（不是本系统的 ResultJson 包装），状态码按 RFC 7644（201/200/204/400/404/409）。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping(ScimService.BASE_PATH)
@RequiredArgsConstructor
@Tag(name = "SCIM 2.0（IdP 入站）", description = "用户与组的 SCIM 协议端点；独立 Bearer 凭据，供 Entra/Okta 推送")
public class ScimController {

    private final ScimService scimService;

    // ==================== 发现端点 ====================

    @Operation(summary = "ServiceProviderConfig", description = "SCIM 能力声明（filter 支持、PATCH 支持）")
    @GetMapping("/ServiceProviderConfig")
    public ResponseEntity<Map<String, Object>> serviceProviderConfig(jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("patch", Map.of("supported", true));
            m.put("filter", Map.of("supported", true, "maxResults", 200));
            m.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
            m.put("etag", Map.of("supported", false));
            m.put("authenticationSchemes", List.of(Map.of(
                    "type", "oauthbearertoken",
                    "name", "OAuth Bearer Token",
                    "description", "认证源配置中的 SCIM Bearer Token")));
            return m;
        });
    }

    @Operation(summary = "Schemas", description = "支持的资源 schema 声明")
    @GetMapping("/Schemas")
    public ResponseEntity<Map<String, Object>> schemas(jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> Map.of(
                "itemsPerPage", 2, "totalResults", 2, "startIndex", 1,
                "Resources", List.of(
                        Map.of("id", ScimService.SCIM_USER_SCHEMA, "name", "User",
                                "endpoint", "/Users", "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:Schema")),
                        Map.of("id", ScimService.SCIM_GROUP_SCHEMA, "name", "Group",
                                "endpoint", "/Groups", "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:Schema")))));
    }

    @Operation(summary = "ResourceTypes", description = "User/Group 资源类型声明")
    @GetMapping("/ResourceTypes")
    public ResponseEntity<Map<String, Object>> resourceTypes(jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> Map.of(
                "itemsPerPage", 2, "totalResults", 2, "startIndex", 1,
                "Resources", List.of(
                        Map.of("id", "User", "name", "User", "endpoint", "/Users",
                                "schema", ScimService.SCIM_USER_SCHEMA,
                                "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType")),
                        Map.of("id", "Group", "name", "Group", "endpoint", "/Groups",
                                "schema", ScimService.SCIM_GROUP_SCHEMA,
                                "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType")))));
    }

    // ==================== Users ====================

    @Operation(summary = "查询用户列表", description = "支持 filter=userName eq \"x\" / externalId eq \"x\"，startIndex/count 分页")
    @GetMapping("/Users")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(value = "count", defaultValue = "100") int count,
            jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            List<Map<String, Object>> resources = new java.util.ArrayList<>();
            User hit = scimService.findByFilter(p, filter);
            if (hit != null) resources.add(scimService.toScimUser(hit));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schemas", List.of(ScimService.SCIM_LIST_SCHEMA));
            out.put("startIndex", startIndex);
            out.put("itemsPerPage", resources.size());
            out.put("totalResults", resources.size());
            out.put("Resources", resources);
            return out;
        });
    }

    @Operation(summary = "按 id 查用户")
    @GetMapping("/Users/{id}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            User u = require(scimService, p, id);
            return scimService.toScimUser(u);
        });
    }

    @Operation(summary = "建号（POST /Users）", description = "userName 必填；不设密码；默认角色取认证源配置；active=false 直接建成禁用")
    @PostMapping("/Users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody JsonNode body, jakarta.servlet.http.HttpServletRequest request) {
        return handleStatus(request, HttpStatus.CREATED, p ->
                scimService.toScimUser(scimService.createScimUser(p, body)));
    }

    @Operation(summary = "全量更新用户（PUT /Users/{id}）")
    @PutMapping("/Users/{id}")
    public ResponseEntity<Map<String, Object>> putUser(@PathVariable String id, @RequestBody JsonNode body,
                                                       jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            require(scimService, p, id);
            return scimService.toScimUser(scimService.updateScimUser(p, id, body));
        });
    }

    @Operation(summary = "增量更新用户（PATCH /Users/{id}）", description = "支持 replace active（Entra/Okta 禁用/启用走这里）与 displayName")
    @PatchMapping("/Users/{id}")
    public ResponseEntity<Map<String, Object>> patchUser(@PathVariable String id, @RequestBody JsonNode body,
                                                         jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            require(scimService, p, id);
            return scimService.toScimUser(scimService.patchScimUser(p, id, body));
        });
    }

    @Operation(summary = "销户（DELETE /Users/{id}）", description = "D4 决策：禁用（可恢复）而非物理删除；身份绑定保留")
    @DeleteMapping("/Users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            require(scimService, p, id);
            scimService.deleteScimUser(p, id);
            return null; // 204
        });
    }

    // ==================== Groups ====================

    @Operation(summary = "查询组列表")
    @GetMapping("/Groups")
    public ResponseEntity<Map<String, Object>> listGroups(
            @RequestParam(value = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(value = "count", defaultValue = "100") int count,
            jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            List<Map<String, Object>> resources = scimService.listGroups(p).stream()
                    .map(scimService::toScimGroup).toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schemas", List.of(ScimService.SCIM_LIST_SCHEMA));
            out.put("startIndex", startIndex);
            out.put("itemsPerPage", resources.size());
            out.put("totalResults", resources.size());
            out.put("Resources", resources);
            return out;
        });
    }

    @Operation(summary = "按 id 查组", description = "组 id = IdP 侧组名（displayName）")
    @GetMapping("/Groups/{id}")
    public ResponseEntity<Map<String, Object>> getGroup(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> scimService.toScimGroup(scimService.requireGroup(p, id)));
    }

    @Operation(summary = "建组（幂等）：登记组名 + 成员落事实源；已配置映射则即时授角色")
    @PostMapping("/Groups")
    public ResponseEntity<Map<String, Object>> createGroup(@RequestBody JsonNode body, jakarta.servlet.http.HttpServletRequest request) {
        return handleStatus(request, HttpStatus.CREATED, p ->
                scimService.toScimGroup(scimService.createGroup(p, body)));
    }

    @Operation(summary = "全量对齐组（PUT /Groups/{id}）：members diff 增删")
    @PutMapping("/Groups/{id}")
    public ResponseEntity<Map<String, Object>> putGroup(@PathVariable String id, @RequestBody JsonNode body,
                                                        jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            requireGroup(scimService, p, id);
            return scimService.toScimGroup(scimService.updateGroup(p, id, body));
        });
    }

    @Operation(summary = "增量更新组（PATCH /Groups/{id}）：add/remove members", description = "Entra/Okta 的成员变更推送")
    @PatchMapping("/Groups/{id}")
    public ResponseEntity<Map<String, Object>> patchGroup(@PathVariable String id, @RequestBody JsonNode body,
                                                          jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            requireGroup(scimService, p, id);
            return scimService.toScimGroup(scimService.patchGroup(p, id, body));
        });
    }

    @Operation(summary = "删组：清映射与成员，回收该组授予的 idp 角色")
    @DeleteMapping("/Groups/{id}")
    public ResponseEntity<Map<String, Object>> deleteGroup(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        return handle(request, p -> {
            requireGroup(scimService, p, id);
            scimService.deleteGroup(p, id);
            return null; // 204
        });
    }

    // ==================== 骨架 ====================

    private interface ScimHandler {
        Map<String, Object> apply(IdentityProvider p);
    }

    /** 统一入口：Bearer 鉴权 → 业务 → 200/204；异常转 SCIM error JSON */
    private ResponseEntity<Map<String, Object>> handle(jakarta.servlet.http.HttpServletRequest request, ScimHandler h) {
        return handleStatus(request, HttpStatus.OK, h);
    }

    private ResponseEntity<Map<String, Object>> handleStatus(jakarta.servlet.http.HttpServletRequest request,
                                                             HttpStatus success, ScimHandler h) {
        IdentityProvider p;
        try {
            p = scimService.authenticate(request.getHeader("Authorization"));
        } catch (ScimService.ScimException e) {
            return error(e);
        }
        try {
            Map<String, Object> body = h.apply(p);
            return body == null
                    ? ResponseEntity.status(HttpStatus.NO_CONTENT).build()
                    : ResponseEntity.status(success).body(body);
        } catch (ScimService.ScimException e) {
            return error(e);
        } catch (Exception e) {
            log.error("[SCIM] 处理异常 provider={}", p.getId(), e);
            return error(new ScimService.ScimException(500, "SCIM 处理异常: " + e.getMessage()));
        }
    }

    private static ResponseEntity<Map<String, Object>> error(ScimService.ScimException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemas", List.of(ScimService.SCIM_ERROR_SCHEMA));
        m.put("status", String.valueOf(e.status));
        m.put("detail", e.getMessage());
        return ResponseEntity.status(e.status).body(m);
    }

    private static User require(ScimService s, IdentityProvider p, String id) {
        User u = id == null ? null : s.findUserById(id);
        if (u == null) throw new ScimService.ScimException(404, "用户不存在: " + id);
        return u;
    }

    private static GroupRoleMapping requireGroup(ScimService s, IdentityProvider p, String id) {
        return s.requireGroup(p, id);
    }
}
