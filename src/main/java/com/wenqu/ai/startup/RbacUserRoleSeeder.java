package com.wenqu.ai.startup;

import com.wenqu.ai.config.SecurityConfig;
import com.wenqu.ai.mapper.ApiEndpointMapper;
import com.wenqu.ai.mapper.RolePermissionMapper;
import com.wenqu.ai.model.ApiEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通用户角色（{@code user}）的授权点种子——新库开箱即用，不依赖历史 dump。
 * <p>入选规则：一个授权点入选 ⇔ 它名下<b>全部</b>端点都在问答白名单内
 * （{@link SecurityConfig#isPublicUserEndpoint}；方法为 ALL 的绑定行按 GET 核对）。
 * 只要有一个端点是管理面的，这个点就不能给普通用户（fail-closed）。
 * <p>幂等：该租户的 {@code user} 角色已有任何授权点行则整租户跳过——管理员后来的调整不会被启动逻辑恢复。
 * 管理员级角色不落行（判定按 admin_flag 展开）。
 * <p>排在 {@link ApiEndpointScanner}（@Order(10)）之后：推导依赖扫描器写入的
 * {@code c_ai_api.permission_code}，早于它跑等于按空表推导。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(12)
public class RbacUserRoleSeeder implements ApplicationRunner {

    private final ApiEndpointMapper apiEndpointMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final JdbcTemplate jdbc;

    public RbacUserRoleSeeder(ApiEndpointMapper apiEndpointMapper,
                              RolePermissionMapper rolePermissionMapper,
                              JdbcTemplate jdbc) {
        this.apiEndpointMapper = apiEndpointMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (String tenantId : distinctTenants()) {
                try {
                    com.wenqu.ai.util.TenantContext.bind(tenantId);
                    seedTenant();
                } finally {
                    com.wenqu.ai.util.TenantContext.bind(null);
                }
            }
        } catch (Exception e) {
            log.warn("[RbacUser] user 角色授权点种子失败（不阻塞启动）: {}", e.getMessage());
        }
    }

    /** 全部租户 id（c_ai_user 的 distinct tenant_id ∪ default），与 RbacSeedRunner 同口径 */
    private List<String> distinctTenants() {
        Set<String> out = new LinkedHashSet<>();
        out.add(com.wenqu.ai.util.TenantContext.DEFAULT_TENANT);
        try {
            for (String t : jdbc.queryForList("SELECT DISTINCT tenant_id FROM c_ai_user", String.class)) {
                if (t != null && !t.isBlank()) out.add(t);
            }
        } catch (Exception e) {
            log.warn("[RbacUser] 租户清单查询失败（按 default 单租户处理）: {}", e.getMessage());
        }
        return List.copyOf(out);
    }

    private void seedTenant() {
        if (!rolePermissionMapper.rowsOfRole("user").isEmpty()) return;
        List<ApiEndpoint> apis = apiEndpointMapper.selectList(null);
        Set<String> perms = deriveUserPermissions(apis);
        if (perms.isEmpty()) {
            log.info("[RbacUser] 租户={} 无可推导的授权点（端点尚未标注？）", com.wenqu.ai.util.TenantContext.current());
            return;
        }
        int n = 0;
        for (String perm : perms) n += rolePermissionMapper.bindAllow("user", perm);
        log.info("[RbacUser] 租户={} user 角色按问答白名单种子授权点 {} 个（写入 {} 行）",
                com.wenqu.ai.util.TenantContext.current(), perms.size(), n);
    }

    /** 白名单推导：授权点入选 ⇔ 名下全部端点都满足 {@code isPublicUserEndpoint} */
    static Set<String> deriveUserPermissions(List<ApiEndpoint> apis) {
        Map<String, List<ApiEndpoint>> byPerm = new LinkedHashMap<>();
        for (ApiEndpoint api : apis) {
            if (api.getPermissionCode() == null || api.getPermissionCode().isBlank()) continue;
            byPerm.computeIfAbsent(api.getPermissionCode(), k -> new ArrayList<>()).add(api);
        }
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, List<ApiEndpoint>> e : byPerm.entrySet()) {
            boolean allPublic = true;
            for (ApiEndpoint api : e.getValue()) {
                String m = "ALL".equalsIgnoreCase(api.getMethod()) ? "GET" : api.getMethod();
                if (!SecurityConfig.isPublicUserEndpoint(m, api.getPath())) {
                    allPublic = false;
                    break;
                }
            }
            if (allPublic) out.add(e.getKey());
        }
        return out;
    }
}
