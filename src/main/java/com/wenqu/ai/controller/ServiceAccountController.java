package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.ServiceAccount;
import com.wenqu.ai.security.Permission;
import com.wenqu.ai.service.ServiceAccountService;
import com.wenqu.ai.service.UserRoleService;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务账号管理接口（批次 C3）：非人类身份的 CRUD / 令牌签发与轮换 / 角色绑定。
 * <p>不在问答用户白名单内，访问控制由拦截器按角色 RBAC 判定（fail-closed）；
 * 令牌明文只在创建/轮换响应里出现一次——落地即只有哈希，泄露处置 = 轮换。</p>
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/service-account")
@RequiredArgsConstructor
@Tag(name = "服务账号", description = "非人类身份：外部系统调用管理面的账号与令牌（RBAC 批次 C3）")
public class ServiceAccountController {

    private final ServiceAccountService serviceAccountService;
    private final UserRoleService userRoleService;

    @Operation(summary = "服务账号清单", description = "全量（含停用）；角色绑定在成员口径里按 uid=svc:{id} 反查")
    @Permission("service-account:read")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(serviceAccountService.list());
    }

    @Operation(summary = "创建服务账号", description = "body: {name, description?, roles:[角色编码], expireAt?(ISO)}；响应含明文令牌（只此一次），角色绑定 granted_by='service'")
    @Permission("service-account:manage")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        List<String> roles = strList(body.get("roles"));
        LocalDateTime expireAt = parseTime(body.get("expireAt"));
        Object[] r = serviceAccountService.create(str(body.get("name")), str(body.get("description")), roles, expireAt);
        ServiceAccount acc = (ServiceAccount) r[0];
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", acc.getId());
        out.put("name", acc.getName());
        out.put("token", String.valueOf(r[1]));
        out.put("tokenHint", "明文只此一次：立即保存，关闭后只能轮换重发");
        return ResultJson.ok(out, "已创建");
    }

    @Operation(summary = "编辑服务账号", description = "body: {name?, description?, status?(0/1), expireAt?(ISO)}；停用即全部调用 401")
    @Permission("service-account:manage")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        serviceAccountService.update(id, str(body.get("name")), str(body.get("description")),
                body.get("status") == null ? null : Integer.parseInt(String.valueOf(body.get("status"))),
                parseTime(body.get("expireAt")));
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "轮换令牌", description = "旧明文立刻作废，响应含新明文令牌（只此一次）")
    @Permission("service-account:manage")
    @PostMapping("/{id}/rotate")
    public ResultJson rotate(@PathVariable("id") String id) {
        return ResultJson.ok(Map.of("token", serviceAccountService.rotateToken(id)), "已轮换");
    }

    @Operation(summary = "删除服务账号", description = "角色绑定行级联清理（uid=svc:{id} 的 c_ai_user_role 行）")
    @Permission("service-account:manage")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id) {
        serviceAccountService.delete(id);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "服务账号的角色绑定", description = "该账号挂的角色（与成员管理同口径；granted_by=service）")
    @Permission("service-account:read")
    @GetMapping("/{id}/roles")
    public ResultJson roles(@PathVariable("id") String id) {
        return ResultJson.ok(userRoleService.rolesOf(ServiceAccountService.UID_PREFIX + id));
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> l)) return null;
        return l.stream().map(String::valueOf).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static LocalDateTime parseTime(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return LocalDateTime.parse(s.contains("T") ? s : s.replace(' ', 'T'));
        } catch (Exception e) {
            throw new BizException("时间格式不合法（应为 ISO，如 2026-12-31T23:59:59）");
        }
    }
}
