package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Menu;
import com.wenqu.ai.model.User;
import com.wenqu.ai.service.MenuService;
import com.wenqu.ai.util.RequestUser;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final UserMapper userMapper;

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

    @Operation(summary = "新建菜单",
            description = "body: parentId(可选)/name(必填)/icon/path/sortOrder/visible(1显0隐)/renderAs(sidebar|tab|group|hidden)。"
                    + "tab 型必须指定父菜单；group 型不可配路径")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        Menu m = menuService.create(str(body, "parentId"), str(body, "name"), str(body, "icon"),
                str(body, "path"), intOrNull(body, "sortOrder"), intOrNull(body, "visible"), str(body, "renderAs"));
        return ResultJson.ok(m, "已创建");
    }

    @Operation(summary = "编辑菜单", description = "仅更新 body 中出现的字段；父级变更做环检测；renderAs 组合校验在合并后的最终态上做")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable("id") String id, @RequestBody Map<String, Object> body) {
        menuService.update(id, str(body, "parentId"), str(body, "name"), str(body, "icon"),
                str(body, "path"), intOrNull(body, "sortOrder"), intOrNull(body, "visible"), str(body, "renderAs"));
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除菜单", description = "内置菜单与含子菜单的不可删；级联清理角色绑定")
    @DeleteMapping("/{id}")
    public ResultJson delete(@Parameter(description = "菜单 ID") @PathVariable("id") String id) {
        menuService.delete(id);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "我的侧栏布局读取",
            description = "返回 {customized, order:[menuId…], hidden:[menuId…], menus:[侧栏可调项全集]}。"
                    + "customized=false 表示走菜单表默认；menus 是**未施加个人偏好**的 sidebar/group 顶级项"
                    + "（含已被自己隐藏的），抽屉据此渲染开关行——侧栏实际渲染的 /auth/me menus 里没有隐藏项")
    @GetMapping("/my-layout")
    public ResultJson myLayout() {
        User u = userMapper.selectById(RequestUser.uid());
        String prefJson = u == null ? null : u.getMenuPref();
        MenuService.MenuPref p = menuService.parsePref(prefJson);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customized", p != null);
        out.put("menus", menuService.adjustableSidebarMenus(RequestUser.role()));
        out.put("order", p == null ? List.of() : p.order);
        out.put("hidden", p == null ? List.of() : p.hidden);
        return ResultJson.ok(out);
    }

    @Operation(summary = "我的侧栏布局保存",
            description = "body: { order:[menuId…], hidden:[menuId…], reset?:true }。"
                    + "只接受本人有权看到的菜单（越权/已删除的 id 静默丢弃）；"
                    + "reset=true 恢复默认顺序与全部显示。纯个人偏好，不改变其他人侧栏")
    @PutMapping("/my-layout")
    public ResultJson saveMyLayout(@RequestBody Map<String, Object> body) {
        menuService.saveMyPref(RequestUser.uid(), strList(body.get("order")),
                strList(body.get("hidden")), Boolean.TRUE.equals(body.get("reset")));
        return ResultJson.ok("已保存");
    }

    /** List&lt;?&gt; → 去空去重的字符串列表；非 List 返回 null（=不修改该字段） */
    private static List<String> strList(Object v) {
        if (!(v instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            String s = o == null ? null : String.valueOf(o).trim();
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }
}
