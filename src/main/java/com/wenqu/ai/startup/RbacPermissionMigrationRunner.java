package com.wenqu.ai.startup;

import com.wenqu.ai.config.SecurityConfig;
import com.wenqu.ai.mapper.RoleApiMapper;
import com.wenqu.ai.mapper.RoleMapper;
import com.wenqu.ai.mapper.RolePermissionMapper;
import com.wenqu.ai.model.ApiEndpoint;
import com.wenqu.ai.model.Role;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权点迁移（RBAC 升级批次 A6，方案 docs/rbac-upgrade-plan.md §3 / §8 M2）——<b>一次性</b>启动迁移：
 * <ol>
 *   <li><b>存量折算</b>：{@code c_ai_role_api}（角色→接口 id）→ {@code c_ai_role_permission}
 *       （角色→授权点 allow）。幂等：目标角色已有授权点行则跳过（管理员后来的解绑不被启动逻辑恢复，
 *       与 {@code RbacSeedRunner} 菜单绑定同一口径）。绑定行指向未标注端点时无从折算，留待
 *       shadow 差异观察——不猜、不补。</li>
 *   <li><b>user 角色种子（G4）</b>：{@code role_api} 与 {@code role_permission} 双空时按
 *       {@code SecurityConfig.isPublicUserEndpoint} 白名单推导授权点集合灌入——新库开箱即用，
 *       不依赖历史 dump；A4 收口白名单后普通用户不断网。升级库走折算路径并<b>并集</b>白名单推导集
 *       （存量 user 绑定来自历史 dump，白名单覆盖的能力 enforce 下不能丢）。</li>
 * </ol>
 * 管理员级角色（admin_flag=1）不落授权点行——判定按 {@code admin_flag} 展开为全量 allow（方案 D5，
 * 新增授权点不漏配）。
 * <p>
 * 本 Runner 属于 M2 双跑窗口的机制：enforce 稳定、{@code c_ai_role_api} 退役后<b>整类删除</b>
 * （启动迁移用完即删的既定口径）。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(11)
public class RbacPermissionMigrationRunner implements ApplicationRunner {

    private final RoleMapper roleMapper;
    private final RoleApiMapper roleApiMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final com.wenqu.ai.mapper.ApiEndpointMapper apiEndpointMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public RbacPermissionMigrationRunner(RoleMapper roleMapper,
                                         RoleApiMapper roleApiMapper,
                                         RolePermissionMapper rolePermissionMapper,
                                         com.wenqu.ai.mapper.ApiEndpointMapper apiEndpointMapper,
                                         org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.roleMapper = roleMapper;
        this.roleApiMapper = roleApiMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.apiEndpointMapper = apiEndpointMapper;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            migrate();
        } catch (Exception e) {
            log.warn("[RbacPerm] 授权点迁移失败（不阻塞启动；legacy/shadow 判定不受影响）: {}", e.getMessage());
        }
    }

    void migrate() {
        // 批次 F：折算/种子按租户跑——绑定行要盖上各自租户的戳，default 一把梭会把
        // huazhang/lanwan 租户的折算行全归错租户（读侧按租户过滤后等于没折算）
        for (String tenantId : distinctTenants()) {
            try {
                com.wenqu.ai.util.TenantContext.bind(tenantId);
                migrateTenant();
            } finally {
                com.wenqu.ai.util.TenantContext.bind(null);
            }
        }
    }

    /** 全部租户 id（c_ai_user 的 distinct tenant_id ∪ default），与 RbacSeedRunner 同口径 */
    private List<String> distinctTenants() {
        Set<String> out = new java.util.LinkedHashSet<>();
        out.add(com.wenqu.ai.util.TenantContext.DEFAULT_TENANT);
        try {
            for (String t : jdbc.queryForList("SELECT DISTINCT tenant_id FROM c_ai_user", String.class)) {
                if (t != null && !t.isBlank()) out.add(t);
            }
        } catch (Exception e) {
            log.warn("[RbacPerm] 租户清单查询失败（按 default 单租户折算）: {}", e.getMessage());
        }
        return List.copyOf(out);
    }

    private void migrateTenant() {
        List<ApiEndpoint> apis = apiEndpointMapper.selectList(null);
        // 端点 → 授权点映射（扫描器已在本 Runner 之前写入 permission_code）
        Map<String, String> apiId2Perm = new LinkedHashMap<>();
        for (ApiEndpoint api : apis) {
            if (api.getPermissionCode() != null && !api.getPermissionCode().isBlank()) {
                apiId2Perm.put(api.getId(), api.getPermissionCode());
            }
        }

        // user 角色的白名单推导集（G4）：一个授权点入选当且仅当它名下全部端点都在白名单内——
        // 只要有一个端点是管理面的，这个点就不能给普通用户（fail-closed）
        Set<String> whitelistPerms = deriveUserPermissions(apis);

        int convertedRoles = 0, convertedRows = 0;
        List<Role> tenantRoles = roleMapper.selectList(null);
        log.info("[RbacPerm][DBG] 租户={} 角色数={} 白名单点数={}", com.wenqu.ai.util.TenantContext.current(),
                tenantRoles.size(), whitelistPerms.size());
        for (Role role : tenantRoles) {
            String code = role.getCode();
            boolean adminLevel = role.getAdminFlag() != null && role.getAdminFlag() == 1;
            if (adminLevel && !"user".equals(code)) {
                continue; // 管理员级角色不落行（D5：判定时展开为全量 allow）
            }
            boolean alreadyMigrated = !rolePermissionMapper.rowsOfRole(code).isEmpty();
            if ("user".equals(code)) {
                if (alreadyMigrated || whitelistPerms.isEmpty()) continue;
                for (String perm : whitelistPerms) {
                    convertedRows += rolePermissionMapper.bindAllow(code, perm);
                }
                convertedRoles++;
                log.info("[RbacPerm] user 角色按问答白名单推导授权点 {} 个（G4：新库开箱即用）", whitelistPerms.size());
                continue;
            }
            List<String> apiIds = roleApiMapper.apiIdsOfRole(code);
            if (apiIds.isEmpty() || alreadyMigrated) continue;
            Set<String> perms = new LinkedHashSet<>();
            for (String id : apiIds) {
                String perm = apiId2Perm.get(id);
                if (perm != null) perms.add(perm);
            }
            if (perms.isEmpty()) {
                log.info("[RbacPerm] 角色 {} 的 {} 条接口绑定均指向未标注端点，无从折算（留待 shadow 观察）", code, apiIds.size());
                continue;
            }
            for (String perm : perms) {
                convertedRows += rolePermissionMapper.bindAllow(code, perm);
            }
            convertedRoles++;
        }
        if (convertedRoles > 0) {
            log.info("[RbacPerm] 授权点迁移完成：折算/种子 {} 个角色的 {} 条 allow 绑定", convertedRoles, convertedRows);
        } else {
            log.info("[RbacPerm] 授权点迁移检查完成：无需折算（已迁移过或无存量绑定）");
        }
    }


    /**
     * 白名单推导：授权点入选 ⇔ 名下<b>全部</b>端点都满足 {@code isPublicUserEndpoint}
     * （方法为 ALL 的绑定行按 GET 核对——白名单本就覆盖只读语义，ALL 行取最宽方法不会误收管理端点）。
     */
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
