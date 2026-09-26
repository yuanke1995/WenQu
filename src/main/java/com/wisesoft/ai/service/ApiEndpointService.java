package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.ApiEndpointMapper;
import com.wisesoft.ai.mapper.RoleApiMapper;
import com.wisesoft.ai.model.ApiEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 接口登记服务：RBAC 鉴权数据源（c_ai_api）的增删改查。
 * <p>
 * 数据主体来自启动期 {@code ApiEndpointScanner} 自动扫描（{@code builtin=1}）；
 * 本服务支持手工登记（如网关代理的非 Spring 端点）与名称/模块维护。
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
    private final RoleService roleService;

    /** 接口列表（module 升序 → method 升序 → path 升序；keyword 模糊匹配路径/名称） */
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
        return apiEndpointMapper.selectList(q);
    }

    /** 手工登记接口 */
    @Transactional
    public ApiEndpoint create(String method, String path, String name, String module) {
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
        roleService.invalidateApiPatterns();
        log.info("[AUDIT] 手工登记接口 id={} {} {}", api.getId(), m, p);
        return api;
    }

    /** 编辑接口：builtin 仅可改名称/模块（method/path 以代码扫描为准）；手工的可全改 */
    @Transactional
    public void update(String id, String method, String path, String name, String module) {
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
        roleService.invalidateApiPatterns();
        log.info("[AUDIT] 编辑接口 id={} {} {}", id, api.getMethod(), api.getPath());
    }

    /**
     * 删除接口并清理角色绑定。扫描登记的接口若代码中仍存在，下次重启会重新登记
     * （重启前该接口对所有非管理员角色 403，fail-closed）；已下线的接口删除后不会复活。
     */
    @Transactional
    public void delete(String id) {
        ApiEndpoint api = apiEndpointMapper.selectById(id);
        if (api == null) throw new BizException("接口不存在");
        apiEndpointMapper.deleteById(id);
        roleApiMapper.unbindByApi(id);
        roleService.invalidateApiPatterns();
        log.info("[AUDIT] 删除接口 id={} {} {}", id, api.getMethod(), api.getPath());
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
