package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.ApiEndpointMapper;
import com.wenqu.ai.mapper.MenuApiMapper;
import com.wenqu.ai.mapper.MenuMapper;
import com.wenqu.ai.mapper.RoleApiMapper;
import com.wenqu.ai.model.ApiEndpoint;
import com.wenqu.ai.model.Menu;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 接口登记服务：RBAC 鉴权数据源（c_ai_api）的增删改查。
 * <p>
 * 数据主体来自启动期 {@code ApiEndpointScanner} 自动扫描（{@code builtin=1}）；
 * 本服务支持手工登记（如网关代理的非 Spring 端点）与名称/模块/菜单归属维护。
 * 菜单归属存 {@code c_ai_menu_api}（多归属，仅权限页分组展示与勾选联动，不参与鉴权）。
 * 任何写操作后需 {@link RoleService#invalidateApiPatterns()} 失效鉴权缓存。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiEndpointService {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "ALL");

    private final ApiEndpointMapper apiEndpointMapper;
    private final RoleApiMapper roleApiMapper;
    private final MenuApiMapper menuApiMapper;
    private final MenuMapper menuMapper;
    private final RoleService roleService;

    /** 接口列表（module 升序 → method 升序 → path 升序；keyword 模糊匹配路径/名称；含菜单归属回填） */
    public List<ApiEndpoint> list(String module, String keyword) {
        LambdaQueryWrapper<ApiEndpoint> q = new LambdaQueryWrapper<>();
        if (module != null && !module.isBlank() && !"all".equals(module)) {
            q.eq(ApiEndpoint::getModule, module.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            q.and(w -> w.like(ApiEndpoint::getPath, k).or().like(ApiEndpoint::getName, k));
        }
        q.orderByAsc(ApiEndpoint::getModule)
                .orderByAsc(ApiEndpoint::getMethod)
                .orderByAsc(ApiEndpoint::getPath);
        List<ApiEndpoint> out = apiEndpointMapper.selectList(q);
        // 归属聚合回填（表小，一次全量查询后按 api 分组）
        Map<String, List<String>> menusOfApi = new HashMap<>();
        for (Map<String, Object> row : menuApiMapper.all()) {
            String apiId = String.valueOf(row.get("api_id"));
            String menuId = String.valueOf(row.get("menu_id"));
            menusOfApi.computeIfAbsent(apiId, k -> new ArrayList<>()).add(menuId);
        }
        for (ApiEndpoint a : out) a.setMenuIds(menusOfApi.getOrDefault(a.getId(), List.of()));
        return out;
    }

    /** 手工登记接口 */
    @Transactional
    public ApiEndpoint create(String method, String path, String name, String module, List<String> menuIds) {
        String m = normalizeMethod(method);
        String p = normalizePath(path);
        ensureMethodPathUnique(m, p, null);
        ApiEndpoint api = new ApiEndpoint();
        api.setMethod(m);
        api.setPath(p);
        api.setName(trimOrNull(name));
        api.setModule(trimOrNull(module));
        api.setBuiltin(0);
        apiEndpointMapper.insert(api);
        bindMenus(api.getId(), menuIds);
        roleService.invalidateApiPatterns();
        log.info("[AUDIT] 手工登记接口 id={} {} {} menus={}", api.getId(), m, p, menuIds == null ? 0 : menuIds.size());
        return api;
    }

    /** 编辑接口：builtin 仅可改名称/模块/菜单归属（method/path 以代码扫描为准）；手工的可全改 */
    @Transactional
    public void update(String id, String method, String path, String name, String module, List<String> menuIds) {
        ApiEndpoint api = apiEndpointMapper.selectById(id);
        if (api == null) throw new BizException("接口不存在");
        boolean builtin = api.getBuiltin() != null && api.getBuiltin() == 1;
        if (!builtin) {
            if (method != null) api.setMethod(normalizeMethod(method));
            if (path != null) {
                String p = normalizePath(path);
                ensureMethodPathUnique(api.getMethod(), p, id);
                api.setPath(p);
            }
        } else if ((method != null && !api.getMethod().equalsIgnoreCase(method))
                || (path != null && !api.getPath().equals(path.trim()))) {
            throw new BizException("扫描登记的接口不可修改方法与路径（以代码为准）");
        }
        if (name != null) api.setName(trimOrNull(name));
        if (module != null) api.setModule(trimOrNull(module));
        apiEndpointMapper.updateById(api);
        // 菜单归属：null=不动；[]=清空（显示在「其他接口」；重启扫描会按默认对照重新补齐）
        if (menuIds != null) {
            menuApiMapper.unbindByApi(id);
            bindMenus(id, menuIds);
        }
        roleService.invalidateApiPatterns();
        log.info("[AUDIT] 编辑接口 id={} {} {}", id, api.getMethod(), api.getPath());
    }

    /**
     * 删除接口并清理角色绑定与菜单归属。扫描登记的接口若代码中仍存在，下次重启会重新登记
     * （重启前该接口对所有非管理员角色 403，fail-closed）；已下线的接口删除后不会复活。
     */
    @Transactional
    public void delete(String id) {
        ApiEndpoint api = apiEndpointMapper.selectById(id);
        if (api == null) throw new BizException("接口不存在");
        apiEndpointMapper.deleteById(id);
        roleApiMapper.unbindByApi(id);
        menuApiMapper.unbindByApi(id);
        roleService.invalidateApiPatterns();
        log.info("[AUDIT] 删除接口 id={} {} {}", id, api.getMethod(), api.getPath());
    }

    /** 绑定菜单归属（校验菜单存在；忽略空项与重复项） */
    private void bindMenus(String apiId, List<String> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) return;
        Set<String> valid = new HashSet<>();
        for (Menu m : menuMapper.selectList(null)) valid.add(m.getId());
        Set<String> seen = new HashSet<>();
        for (String raw : menuIds) {
            if (raw == null) continue;
            String menuId = raw.trim();
            if (menuId.isEmpty() || !valid.contains(menuId) || !seen.add(menuId)) continue;
            menuApiMapper.bind(menuId, apiId);
        }
    }

    // ==================== 校验工具 ====================

    private static String normalizeMethod(String method) {
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(m)) throw new BizException("非法 HTTP 方法（GET/POST/PUT/DELETE/PATCH/ALL）");
        return m;
    }

    private static String normalizePath(String path) {
        String p = path == null ? "" : path.trim();
        if (p.isEmpty()) throw new BizException("接口路径不能为空");
        if (!p.startsWith("/api/")) throw new BizException("接口路径须以 /api/ 开头（应用内路径）");
        if (p.length() > 200) throw new BizException("接口路径过长");
        return p;
    }

    private void ensureMethodPathUnique(String method, String path, String excludeId) {
        Long c = apiEndpointMapper.selectCount(new LambdaQueryWrapper<ApiEndpoint>()
                .eq(ApiEndpoint::getMethod, method).eq(ApiEndpoint::getPath, path));
        if (c != null && c > 0) {
            if (excludeId == null) throw new BizException("该「方法 + 路径」已登记");
            ApiEndpoint same = apiEndpointMapper.selectOne(new LambdaQueryWrapper<ApiEndpoint>()
                    .eq(ApiEndpoint::getMethod, method).eq(ApiEndpoint::getPath, path).last("LIMIT 1"));
            if (same != null && !same.getId().equals(excludeId)) throw new BizException("该「方法 + 路径」已登记");
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
