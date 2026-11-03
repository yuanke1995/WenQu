package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.Role;
import com.wenqu.ai.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import com.wenqu.ai.security.Permission;

/**
 * 角色管理接口（权限管理页）：角色 CRUD 与菜单/接口绑定。
 * <p>不在问答用户白名单内，访问控制由拦截器按角色 RBAC 判定（fail-closed）。
 * 注意 {@code GET /options} 为精确匹配优先于 {@code GET /{code}}，不冲突。</p>
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/role")
@RequiredArgsConstructor
@Tag(name = "角色管理", description = "角色 CRUD、菜单/接口绑定（RBAC）")
public class RoleController {

    private final RoleService roleService;
    private final com.wenqu.ai.service.UserRoleService userRoleService;

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intOrNull(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> idList(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v instanceof List ? (List<String>) v : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> idMapList(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v instanceof List ? (List<Map<String, Object>>) v : null;
    }

    @Operation(summary = "角色列表", description = "内置角色在前；含停用角色（权限管理页展示全量）")
    @Permission("role:read")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(roleService.list());
    }

    @Operation(summary = "角色下拉选项", description = "仅启用角色（成员管理建档用）：code/name/adminFlag/builtin")
    @Permission("role:read")
    @GetMapping("/options")
    public ResultJson options() {
        return ResultJson.ok(roleService.options());
    }

    @Operation(summary = "新建角色", description = "body: code(小写字母开头≤20字符)/name(必填)/description/adminFlag(1=管理员级)")
    @Permission("role:manage")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(roleService.create(str(body, "code"), str(body, "name"),
                str(body, "description"), intOrNull(body, "adminFlag")), "已创建");
    }

    @Operation(summary = "编辑角色", description = "内置角色仅可改名称/描述（管理员级与状态锁定）；自定义可全改")
    @Permission("role:manage")
    @PutMapping("/{code}")
    public ResultJson update(@PathVariable("code") String code, @RequestBody Map<String, Object> body) {
        roleService.update(code, str(body, "name"), str(body, "description"),
                intOrNull(body, "adminFlag"), intOrNull(body, "status"));
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除角色", description = "内置角色与在用角色不可删；级联清菜单/接口绑定")
    @Permission("role:manage")
    @DeleteMapping("/{code}")
    public ResultJson delete(@Parameter(description = "角色编码") @PathVariable("code") String code) {
        roleService.delete(code);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "角色的权限绑定", description = "返回该角色绑定的菜单 id、接口 id 与授权点集：{menuIds, apiIds, permissions}（权限配置页回显；管理员级角色 permissions/apiIds 恒空——判定时展开）")
    @Permission("role:read")
    @GetMapping("/{code}/permissions")
    public ResultJson permissions(@PathVariable("code") String code) {
        Role r = roleService.get(code);
        if (r == null) return ResultJson.error("角色不存在");
        return ResultJson.ok(Map.of("menuIds", roleService.menuIdsOf(code),
                "apiIds", roleService.apiIdsOf(code),
                "permissions", roleService.allowPermissionsOfRole(code)));
    }

    @Operation(summary = "授权点目录", description = "全部授权点（module 分组展示用）；目录事实源在代码 @Permission 注解，扫描器启动时登记")
    @Permission("role:read")
    @GetMapping("/permission-catalog")
    public ResultJson permissionCatalog() {
        return ResultJson.ok(roleService.permissionCatalog());
    }

    @Operation(summary = "保存角色的权限绑定", description = "body: {menuIds:[...], permissions:[...]}（授权点粒度，A7 主路径；反向折算回接口绑定）或 {menuIds:[...], apiIds:[...]}（端点粒度，兼容窗口）。全量替换；管理员级角色无需绑定（天然全量）")
    @Permission("role:manage")
    @PutMapping("/{code}/permissions")
    public ResultJson savePermissions(@PathVariable("code") String code, @RequestBody Map<String, Object> body) {
        List<String> permissions = idList(body, "permissions");
        if (permissions != null) {
            roleService.savePermissionPoints(code, idList(body, "menuIds"), permissions);
        } else {
            roleService.savePermissions(code, idList(body, "menuIds"), idList(body, "apiIds"));
        }
        return ResultJson.ok("权限已保存");
    }

    @Operation(summary = "即将到期的临时授权", description = "未来 days 天内到期的角色绑定行（含已过期未清扫的），批次 C1 提醒清单；{uid, roleCode, expireAt, grantedBy}")
    @Permission("role:read")
    @GetMapping("/expiring")
    public ResultJson expiring(@Parameter(description = "未来天数，默认 7") @RequestParam(value = "days", defaultValue = "7") Integer days) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.wenqu.ai.model.UserRole r : userRoleService.expiringWithinDays(days == null ? 7 : days)) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("uid", r.getUid());
            m.put("roleCode", r.getRoleCode());
            m.put("expireAt", r.getExpireAt());
            m.put("grantedBy", r.getGrantedBy());
            out.add(m);
        }
        return ResultJson.ok(out);
    }

    @Operation(summary = "角色数据范围", description = "该角色的部门数据范围（批次 C2）：{scopeType, departmentId}；无行=own（谁建归谁）")
    @Permission("role:read")
    @GetMapping("/{code}/data-scope")
    public ResultJson dataScope(@PathVariable("code") String code) {
        Role r = roleService.get(code);
        if (r == null) return ResultJson.error("角色不存在");
        return ResultJson.ok(roleService.dataScopeOf(code));
    }

    @Operation(summary = "保存角色数据范围", description = "body: {scopeType: all|own|department|department_and_children, departmentId?}；范围授予 READ（看），管理权仍在创建者")
    @Permission("role:manage")
    @PutMapping("/{code}/data-scope")
    public ResultJson saveDataScope(@PathVariable("code") String code, @RequestBody Map<String, Object> body) {
        roleService.saveDataScope(code, str(body, "scopeType"), str(body, "departmentId"));
        return ResultJson.ok("数据范围已保存");
    }

    @Operation(summary = "各角色成员数", description = "role_code → 用户数（角色列表徽标用，批次 D-2）")
    @Permission("role:read")
    @GetMapping("/members-counts")
    public ResultJson memberCounts() {
        return ResultJson.ok(roleService.memberCountsByRole());
    }

    @Operation(summary = "角色成员清单", description = "挂着该角色的用户（uid/昵称/授予来源 granted_by=manual|idp/状态），批次 D-2；IdP 授予项在成员页只读不可改（D-5 同口径）")
    @Permission("role:read")
    @GetMapping("/{code}/members")
    public ResultJson members(@PathVariable("code") String code) {
        Role r = roleService.get(code);
        if (r == null) return ResultJson.error("角色不存在");
        return ResultJson.ok(roleService.membersOfRole(code));
    }

    @Operation(summary = "授权点 → 已授权角色", description = "permission_code → 绑定它的角色编码清单（接口页「已授权角色」列用，批次 D-1；启用与停用角色都算）")
    @Permission("role:read")
    @GetMapping("/permission-roles")
    public ResultJson permissionRoles() {
        return ResultJson.ok(roleService.rolesByPermission());
    }

    @Operation(summary = "用户的有效授权点视图", description = "给定 uid 算出其实际可用的授权点集合（各角色 allow 并集 − 全局 deny）与全局 deny 清单；管理员级返回 admin:all-allow 标记 + 对其生效的 deny。权限排查与验收用（方案 D-3）")
    @Permission("role:read")
    @GetMapping("/effective/{uid}")
    public ResultJson effective(@PathVariable("uid") String uid) {
        var roles = userRoleService.rolesOf(uid);
        var eff = roleService.effectivePermissionsOfUser(roles);
        return ResultJson.ok(Map.of(
                "uid", uid,
                "roles", roles,
                "permissions", eff.get("permissions"),
                "globalDenies", eff.get("globalDenies"),
                "mode", roleService.permissionMode()));
    }

    @Operation(summary = "角色的 deny 规则", description = "返回该角色的 deny 行：[{permissionCode, exempt}]（批次 B2；exempt=1 只在该角色内生效）")
    @Permission("role:read")
    @GetMapping("/{code}/denies")
    public ResultJson denies(@PathVariable("code") String code) {
        Role r = roleService.get(code);
        if (r == null) return ResultJson.error("角色不存在");
        return ResultJson.ok(roleService.denyRulesOfRole(code));
    }

    @Operation(summary = "保存角色的 deny 规则", description = "body: {denies:[{permissionCode, exempt}]} 全量替换（只动 deny 行，allow 绑定不受影响）。deny 优先于 allow（D3）；exempt=1 只在该角色内生效")
    @Permission("role:manage")
    @PutMapping("/{code}/denies")
    public ResultJson saveDenies(@PathVariable("code") String code, @RequestBody Map<String, Object> body) {
        roleService.saveDenyRules(code, idMapList(body, "denies"));
        return ResultJson.ok("deny 规则已保存");
    }
}
