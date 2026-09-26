package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.Role;
import com.wisesoft.ai.service.RoleService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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

    @Operation(summary = "角色列表", description = "内置角色在前；含停用角色（权限管理页展示全量）")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(roleService.list());
    }

    @Operation(summary = "角色下拉选项", description = "仅启用角色（成员管理建档用）：code/name/adminFlag/builtin")
    @GetMapping("/options")
    public ResultJson options() {
        return ResultJson.ok(roleService.options());
    }

    @Operation(summary = "新建角色", description = "body: code(小写字母开头≤20字符)/name(必填)/description/adminFlag(1=管理员级)")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(roleService.create(str(body, "code"), str(body, "name"),
                str(body, "description"), intOrNull(body, "adminFlag")), "已创建");
    }

    @Operation(summary = "编辑角色", description = "内置角色仅可改名称/描述（管理员级与状态锁定）；自定义可全改")
    @PutMapping("/{code}")
    public ResultJson update(@PathVariable String code, @RequestBody Map<String, Object> body) {
        roleService.update(code, str(body, "name"), str(body, "description"),
                intOrNull(body, "adminFlag"), intOrNull(body, "status"));
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除角色", description = "内置角色与在用角色不可删；级联清菜单/接口绑定")
    @DeleteMapping("/{code}")
    public ResultJson delete(@Parameter(description = "角色编码") @PathVariable String code) {
        roleService.delete(code);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "角色的菜单绑定", description = "返回该角色绑定的菜单 id 列表")
    @GetMapping("/{code}/menus")
    public ResultJson menus(@PathVariable String code) {
        Role r = roleService.get(code);
        if (r == null) return ResultJson.error("角色不存在");
        return ResultJson.ok(roleService.menuIdsOf(code));
    }

    @Operation(summary = "保存角色的菜单绑定", description = "body: {menuIds:[...]} 全量替换；管理员级角色无需绑定（天然全量）")
    @PutMapping("/{code}/menus")
    public ResultJson saveMenus(@PathVariable String code, @RequestBody Map<String, Object> body) {
        roleService.saveMenus(code, idList(body, "menuIds"));
        return ResultJson.ok("菜单权限已保存");
    }

    @Operation(summary = "角色的接口绑定", description = "返回该角色绑定的接口 id 列表")
    @GetMapping("/{code}/apis")
    public ResultJson apis(@PathVariable String code) {
        Role r = roleService.get(code);
        if (r == null) return ResultJson.error("角色不存在");
        return ResultJson.ok(roleApiIds(code));
    }

    private List<String> roleApiIds(String code) {
        return roleService.apiIdsOf(code);
    }

    @Operation(summary = "保存角色的接口绑定", description = "body: {apiIds:[...]} 全量替换；管理员级角色无需绑定（天然放行）")
    @PutMapping("/{code}/apis")
    public ResultJson saveApis(@PathVariable String code, @RequestBody Map<String, Object> body) {
        roleService.saveApis(code, idList(body, "apiIds"));
        return ResultJson.ok("接口权限已保存");
    }
}
