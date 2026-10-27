package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.IdentityProvider;
import com.wenqu.ai.model.SyncJobLog;
import com.wenqu.ai.service.DirectorySyncService;
import com.wenqu.ai.service.IdentityMappingService;
import com.wenqu.ai.service.IdentityProviderService;
import com.wenqu.ai.service.LdapDirectoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.SyncJobLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 认证源与身份同步管理（管理员端点，RBAC fail-closed——不在任何白名单，未授权即 403）：
 * 统一认证源（SAML/SCIM/LDAP）CRUD、组映射规则、手动同步、同步日志。
 * OIDC 管理端点仍在 /api/ai/oidc/**（旧表迁移期并存，退役见任务 #7）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/identity")
@RequiredArgsConstructor
@Tag(name = "认证源与身份同步", description = "SAML/SCIM/LDAP 认证源管理、组→角色映射规则、目录同步")
public class IdentityAdminController {

    private final IdentityProviderService providerService;
    private final IdentityMappingService mappingService;
    private final DirectorySyncService directorySyncService;
    private final LdapDirectoryService ldapDirectoryService;
    private final SyncJobLogMapper syncLogMapper;

    // ==================== 认证源 CRUD ====================

    @Operation(summary = "认证源清单", description = "SAML/SCIM/LDAP；config 敏感字段掩码回显；含绑定账号数")
    @GetMapping("/providers")
    public ResultJson list() {
        return ResultJson.ok(providerService.list());
    }

    @Operation(summary = "新建认证源", description = "type=saml|scim|ldap；config 敏感字段整块 RSA 加密入库；"
            + "tenantId 留空＝归当前管理员的租户，归到别的租户仅平台级管理员可执行")
    @PostMapping("/providers")
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(providerService.toView(providerService.create(
                str(body.get("type")), str(body.get("name")),
                intOrNull(body.get("enabled")), str(body.get("defaultRole")),
                str(body.get("defaultDepartment")), intOrNull(body.get("autoCreateUser")),
                intOrNull(body.get("sortOrder")), mapOf(body.get("config")),
                str(body.get("tenantId")))), "已创建");
    }

    @Operation(summary = "编辑认证源", description = "config 中敏感字段为 \"******\" 时保留原值不覆盖；tenantId 留空＝归属不动")
    @PutMapping("/providers/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResultJson.ok(providerService.toView(providerService.update(
                id, str(body.get("name")), intOrNull(body.get("enabled")), str(body.get("defaultRole")),
                str(body.get("defaultDepartment")), intOrNull(body.get("autoCreateUser")),
                intOrNull(body.get("sortOrder")), mapOf(body.get("config")),
                str(body.get("tenantId")))), "已保存");
    }

    @Operation(summary = "删除认证源", description = "仍有用户绑定即拒")
    @DeleteMapping("/providers/{id}")
    public ResultJson delete(@PathVariable String id) {
        providerService.delete(id);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "测试 LDAP 连接", description = "bind + baseDn 可搜索即通过；仅 ldap 类型")
    @PostMapping("/providers/{id}/test")
    public ResultJson test(@PathVariable String id) {
        IdentityProvider p = providerService.getByIdForAdmin(id);
        if (p == null) throw new BizException("认证源不存在");
        if (!"ldap".equals(p.getType())) throw new BizException("连接测试当前仅支持 LDAP 认证源");
        try {
            ldapDirectoryService.testConnection(p);
            return ResultJson.ok(Map.of("ok", true), "连接成功");
        } catch (IllegalArgumentException e) {
            return ResultJson.ok(Map.of("ok", false, "error", e.getMessage()), "连接失败");
        }
    }

    // ==================== 组映射规则 ====================

    @Operation(summary = "组映射规则清单", description = "某认证源下的 组→角色/部门 规则（priority 大者优先）")
    @GetMapping("/mappings")
    public ResultJson mappings(@RequestParam("providerId") String providerId) {
        return ResultJson.ok(mappingService.listByProvider(providerId));
    }

    @Operation(summary = "新建映射规则", description = "roleCode 可空（组先登记、规则后配）；同认证源下组名唯一")
    @PostMapping("/mappings")
    public ResultJson createMapping(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(mappingService.create(str(body.get("providerId")), str(body.get("externalGroup")),
                str(body.get("roleCode")), str(body.get("departmentId")), intOrNull(body.get("priority"))), "已创建");
    }

    @Operation(summary = "编辑映射规则", description = "角色/部门变更只影响之后的裁决；要立即重算请触发手动同步")
    @PutMapping("/mappings/{id}")
    public ResultJson updateMapping(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return ResultJson.ok(mappingService.update(id, str(body.get("roleCode")),
                str(body.get("departmentId")), intOrNull(body.get("priority"))), "已保存");
    }

    @Operation(summary = "删除映射规则", description = "顺带回收按该规则拿到角色的用户的 idp 角色行（手动角色不动）")
    @DeleteMapping("/mappings/{id}")
    public ResultJson deleteMapping(@PathVariable String id) {
        mappingService.delete(id);
        return ResultJson.ok("已删除");
    }

    // ==================== 同步 ====================

    @Operation(summary = "立即同步（LDAP）", description = "diff：远端建号/恢复/离员禁用 + 组映射裁决；返回本轮日志")
    @PostMapping("/sync/{providerId}")
    public ResultJson syncNow(@PathVariable String providerId) {
        IdentityProvider p = providerService.getByIdForAdmin(providerId);
        if (p == null) throw new BizException("认证源不存在");
        if (!"ldap".equals(p.getType())) throw new BizException("手动同步当前仅支持 LDAP 认证源（SCIM 由 IdP 推送）");
        SyncJobLog l = directorySyncService.syncNow(p);
        return ResultJson.ok(l, "同步完成");
    }

    @Operation(summary = "同步日志", description = "某认证源的 c_ai_sync_job_log 最近记录（新→旧，默认 20 条）")
    @GetMapping("/sync/logs")
    public ResultJson syncLogs(@RequestParam("providerId") String providerId,
                               @RequestParam(value = "limit", defaultValue = "20") int limit) {
        // 日志按 provider 归属：管理员此刻可能绑着自己的租户，看别家认证源的日志要切到它自己的租户里取
        IdentityProvider p = providerService.getByIdForAdmin(providerId);
        String tenant = p == null || p.getTenantId() == null || p.getTenantId().isBlank()
                ? com.wenqu.ai.util.TenantContext.DEFAULT_TENANT : p.getTenantId();
        List<SyncJobLog> logs = com.wenqu.ai.util.TenantContext.as(tenant, () ->
                syncLogMapper.selectList(new LambdaQueryWrapper<SyncJobLog>()
                        .eq(SyncJobLog::getSourceId, providerId)
                        .orderByDesc(SyncJobLog::getCreateTime)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 100))));
        return ResultJson.ok(logs);
    }

    // ==================== 杂项 ====================

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOrNull(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
