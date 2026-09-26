package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.Menu;
import com.wisesoft.ai.service.MenuService;
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

import java.util.Map;

/**
 * 菜单管理接口（权限管理页）：侧边栏菜单数据源的增删改查。
 * <p>不在问答用户白名单内，访问控制由拦截器按角色 RBAC 判定（fail-closed）。</p>
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/menu")
@RequiredArgsConstructor
@Tag(name = "菜单管理", description = "侧边栏菜单数据源（RBAC 按角色绑定下发）")
public class MenuController {

    private final MenuService menuService;

    /** 从 Map body 取字符串（缺失/空串归一为 null） */
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
            throw new BizException("字段 " + key + " 须为整数");
        }
    }

    @Operation(summary = "菜单列表", description = "全量平铺（含隐藏），按 sortOrder 升序；前端组树")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(menuService.list());
    }

    @Operation(summary = "新建菜单", description = "body: parentId(可选)/name(必填)/icon/path/sortOrder/visible(1显0隐)")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        Menu m = menuService.create(str(body, "parentId"), str(body, "name"), str(body, "icon"),
                str(body, "path"), intOrNull(body, "sortOrder"), intOrNull(body, "visible"));
        return ResultJson.ok(m, "已创建");
    }

    @Operation(summary = "编辑菜单", description = "仅更新 body 中出现的字段；父级变更做环检测")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        menuService.update(id, str(body, "parentId"), str(body, "name"), str(body, "icon"),
                str(body, "path"), intOrNull(body, "sortOrder"), intOrNull(body, "visible"));
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除菜单", description = "内置菜单与含子菜单的不可删；级联清理角色绑定")
    @DeleteMapping("/{id}")
    public ResultJson delete(@Parameter(description = "菜单 ID") @PathVariable String id) {
        menuService.delete(id);
        return ResultJson.ok("已删除");
    }
}
