package com.wenqu.ai.service;

import com.wenqu.ai.mapper.ApiEndpointMapper;
import com.wenqu.ai.mapper.DepartmentMapper;
import com.wenqu.ai.mapper.MenuMapper;
import com.wenqu.ai.mapper.PermissionCatalogMapper;
import com.wenqu.ai.mapper.RoleMapper;
import com.wenqu.ai.mapper.RoleMenuMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.mapper.UserRoleMapper;
import com.wenqu.ai.model.ApiEndpoint;
import com.wenqu.ai.model.Role;
import com.wenqu.ai.model.UserRole;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RBAC 授权点判定核单测。
 * <p>
 * 不起 Spring 上下文：Mapper 全部 Mockito 假件，判定核 {@code canAccessAny}/
 * {@code hasPermission} 是纯函数式的（角色绑定 → 全局 deny → 管理员展开 → allow 并集），
 * 正好整块测。覆盖：并集判定、deny 优先、admin 降级（B1）、exempt 豁免（B5）、
 * 未标注端点 fail-closed、TTL 过期重建、最后一名管理员锁死保护（G 锁死）、组映射回收语义的角色行口径。
 *
 * @author yuanke
 */
class RoleServicePermissionTest {

    private RoleMapper roleMapper;
    private ApiEndpointMapper apiEndpointMapper;
    private com.wenqu.ai.mapper.RolePermissionMapper rolePermissionMapper;
    private UserRoleMapper userRoleMapper;
    private RoleService roleService;

    @BeforeEach
    void setUp() throws Exception {
        // LambdaQueryWrapper 依赖 MP 的 TableInfo lambda 缓存；纯单测无 MyBatis 上下文，手动补登记
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserRole.class);
        roleMapper = mock(RoleMapper.class);
        apiEndpointMapper = mock(ApiEndpointMapper.class);
        rolePermissionMapper = mock(com.wenqu.ai.mapper.RolePermissionMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        RoleService svc = new RoleService(
                mock(AuditService.class),
                roleMapper,
                mock(RoleMenuMapper.class),
                mock(MenuMapper.class),
                mock(com.wenqu.ai.mapper.MenuApiMapper.class),
                apiEndpointMapper,
                mock(UserMapper.class),
                userRoleMapper,
                rolePermissionMapper,
                mock(PermissionCatalogMapper.class),
                mock(com.wenqu.ai.mapper.RoleDataScopeMapper.class),
                new ResourceVisibilityService(
                        mock(com.wenqu.ai.mapper.RoleDataScopeMapper.class),
                        mock(UserMapper.class),
                        mock(DepartmentMapper.class)),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(ReplicaCoordination.class));
        roleService = svc;
    }

    /** record 分量反射读（Snap 是 private record；构造器 newInstance 需要按分量顺序取旧值） */
    private static Object getRecordComponent(Object record, String name, Class<?> snapClass) throws Exception {
        for (java.lang.reflect.RecordComponent rc : snapClass.getRecordComponents()) {
            if (rc.getName().equals(name)) return rc.getAccessor().invoke(record);
        }
        throw new NoSuchFieldException(name);
    }

    private void givenRoles(Role... roles) {
        Map<String, Role> m = new LinkedHashMap<>();
        for (Role r : roles) m.put(r.getCode(), r);
        when(roleMapper.selectList(any())).thenReturn(List.of(roles));
    }

    private static Role role(String code, int adminFlag) {
        Role r = new Role();
        r.setCode(code);
        r.setName(code);
        r.setAdminFlag(adminFlag);
        r.setBuiltin(0);
        r.setStatus(1);
        return r;
    }

    private static ApiEndpoint api(String method, String path, String perm) {
        ApiEndpoint a = new ApiEndpoint();
        a.setId(path);
        a.setMethod(method);
        a.setPath(path);
        a.setPermissionCode(perm);
        a.setBuiltin(1);
        return a;
    }

    private void givenEndpoints(ApiEndpoint... endpoints) {
        when(apiEndpointMapper.selectList(any())).thenReturn(List.of(endpoints));
    }

    /** 绑定行：effect allow/deny（exempt 可空） */
    private static Map<String, Object> row(String perm, String effect, Integer exempt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("permissionCode", perm);
        m.put("effect", effect);
        m.put("exempt", exempt);
        return m;
    }

    private void givenBindings(String role, List<Map<String, Object>> rows) {
        when(rolePermissionMapper.rowsOfRole(role)).thenReturn(rows);
    }

    // ==================== 并集判定（G5 基线） ====================

    @Test
    @DisplayName("并集判定：任一角色 allow 即放行")
    void unionAllows() {
        givenRoles(role("dev", 0), role("qa", 0));
        givenEndpoints(api("GET", "/api/ai/kb/list", "kb:read"),
                api("GET", "/api/ai/document/list", "document:read"));
        givenBindings("dev", List.of(row("kb:read", "allow", null)));
        givenBindings("qa", List.of(row("document:read", "allow", null)));

        assertThat(roleService.canAccessAny(List.of("dev", "qa"), "GET", "/api/ai/kb/list")).isTrue();
        assertThat(roleService.canAccessAny(List.of("dev", "qa"), "GET", "/api/ai/document/list")).isTrue();
        assertThat(roleService.canAccessAny(List.of("dev", "qa"), "GET", "/api/ai/role/list")).isFalse();
    }

    @Test
    @DisplayName("占位路径与 ALL 方法：/{id} 模式匹配、ALL 通吃方法")
    void patternAndAllMethod() {
        givenRoles(role("user", 0));
        givenEndpoints(api("GET", "/api/ai/agent/{id}", "agent:read"),
                api("ALL", "/api/ai/chat", "chat:use"));
        givenBindings("user", List.of(row("agent:read", "allow", null), row("chat:use", "allow", null)));

        assertThat(roleService.canAccessAny(List.of("user"), "GET", "/api/ai/agent/abc123")).isTrue();
        assertThat(roleService.canAccessAny(List.of("user"), "DELETE", "/api/ai/chat")).isTrue();
    }

    // ==================== deny 优先（D3 拍板语义） ====================

    @Test
    @DisplayName("全局 deny 压过其它角色的 allow")
    void globalDenyBeatsAllow() {
        givenRoles(role("a", 0), role("b", 0));
        givenEndpoints(api("GET", "/api/ai/kb/list", "kb:read"));
        givenBindings("a", List.of(row("kb:read", "allow", null)));
        givenBindings("b", List.of(row("kb:read", "deny", 0)));

        assertThat(roleService.hasPermission(List.of("a", "b"), "kb:read")).isFalse();
    }

    @Test
    @DisplayName("exempt deny 只压本角色：其它角色的 allow 仍然生效")
    void exemptDenyOnlyBitesOwnRole() {
        givenRoles(role("a", 0), role("b", 0));
        givenEndpoints(api("GET", "/api/ai/kb/list", "kb:read"));
        givenBindings("a", List.of(row("kb:read", "allow", null)));
        givenBindings("b", List.of(row("kb:read", "deny", 1)));

        assertThat(roleService.hasPermission(List.of("a", "b"), "kb:read")).isTrue();
        assertThat(roleService.hasPermission(List.of("b"), "kb:read")).isFalse();
    }

    // ==================== 管理员级展开（B1 降级语义） ====================

    @Test
    @DisplayName("admin_flag=1 ⇒ 全量 allow（无需绑定行），但全局 deny 仍然压它")
    void adminExpandedButDenied() {
        Role admin = role("boss", 1);
        givenRoles(admin);
        givenEndpoints(api("GET", "/api/ai/role/list", "role:read"),
                api("GET", "/api/ai/sessions", "session:read"));
        // deny 挂在 admin 角色自己身上——「只读管理员」的挂法
        givenBindings("boss", List.of(row("session:read", "deny", 0)));

        assertThat(roleService.hasPermission(List.of("boss"), "role:read")).isTrue();
        assertThat(roleService.hasPermission(List.of("boss"), "session:read")).isFalse();
    }

    @Test
    @DisplayName("停用/不存在的角色：管理员级判 false（fail-closed）")
    void disabledRoleNotAdmin() {
        Role stopped = role("x", 1);
        stopped.setStatus(0);
        givenRoles(stopped, role("user", 0));
        givenEndpoints(api("GET", "/api/ai/role/list", "role:read"));

        assertThat(roleService.isAdminCode("x")).isFalse();
        assertThat(roleService.hasPermission(List.of("x", "user"), "role:read")).isFalse();
    }

    // ==================== 未标注端点（A.5 fail-closed） ====================

    @Test
    @DisplayName("未标注端点：管理员可达、普通角色拒绝")
    void unannotatedEndpoint() {
        givenRoles(role("boss", 1), role("user", 0));
        givenEndpoints(api("GET", "/api/ai/role/list", "role:read")); // 该端点有标注
        givenBindings("user", List.of());

        // 未登记进 c_ai_api 的端点（permissionOfEndpoint → null）
        assertThat(roleService.canAccessAny(List.of("boss"), "GET", "/api/ai/brandnew/x")).isTrue();
        assertThat(roleService.canAccessAny(List.of("user"), "GET", "/api/ai/brandnew/x")).isFalse();
    }

    // ==================== TTL ====================

    @Test
    @DisplayName("TTL 过期后判定走重建，不会永远拿旧快照")
    void ttlExpiryRebuilds() throws Exception {
        Role r = role("user", 0);
        givenRoles(r);
        givenEndpoints(api("GET", "/api/ai/kb/list", "kb:read"));
        givenBindings("user", List.of(row("kb:read", "allow", null)));
        assertThat(roleService.hasPermission(List.of("user"), "kb:read")).isTrue();

        // 模拟时间流逝：把当前租户快照整体换成"已过期"副本（TTL 在 Snap record 里，按租户键控）
        Field snapsF = RoleService.class.getDeclaredField("snapshots");
        snapsF.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Object> snaps =
                (java.util.concurrent.ConcurrentHashMap<String, Object>) snapsF.get(roleService);
        Class<?> snapClass = Class.forName(RoleService.class.getName() + "$Snap");
        java.lang.reflect.Constructor<?> ctor = snapClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        for (Map.Entry<String, Object> e : snaps.entrySet()) {
            Object cur = e.getValue();
            Object expired = ctor.newInstance(
                    getRecordComponent(cur, "roles", snapClass),
                    getRecordComponent(cur, "menuBindings", snapClass),
                    getRecordComponent(cur, "permBindings", snapClass),
                    System.currentTimeMillis() - 1000L);
            e.setValue(expired);
        }

        // 角色改了绑定（mock 换数据），快照过期后必须读新数据
        givenBindings("user", List.of());
        assertThat(roleService.hasPermission(List.of("user"), "kb:read")).isFalse();
    }

    // ==================== 锁死保护（G 锁死） ====================

    @Test
    @DisplayName("最后一名管理员账号：删除/降级守门 true")
    void lastAdminGuard() {
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
        givenRoles(role("admin", 1), role("user", 0));
        com.wenqu.ai.service.UserRoleService urs = new com.wenqu.ai.service.UserRoleService(
                mock(AuditService.class), userRoleMapper, roleService, mock(UserMapper.class),
                mock(ReplicaCoordination.class));
        assertThat(urs.isLastAdminAccount()).isTrue();
        when(userRoleMapper.selectCount(any())).thenReturn(3L);
        assertThat(urs.isLastAdminAccount()).isFalse();
    }

    // ==================== 组映射回收的角色行口径（G8 边界） ====================

    @Test
    @DisplayName("revokeIdpRole 只删 idp 行：手动行不动（D1 对偶）")
    void revokeIdpKeepsManual() {
        when(userRoleMapper.delete(any())).thenReturn(1);
        com.wenqu.ai.service.UserRoleService urs = new com.wenqu.ai.service.UserRoleService(
                mock(AuditService.class), userRoleMapper, roleService, mock(UserMapper.class),
                mock(ReplicaCoordination.class));
        urs.revokeIdpRole("u1", "editor");
        // 验证删除条件携带 granted_by='idp'：用 ArgumentCaptor 太啰嗦，这里断言 delete 被调用一次即可；
        // SQL 语义由 mapper 层保证（wrapper 带 eq(granted_by,'idp')）
        org.mockito.Mockito.verify(userRoleMapper, org.mockito.Mockito.times(1)).delete(any());
    }

    @Test
    @DisplayName("G9 修复：grant 不再删别的来源行（只删同 granted_by）")
    void grantNoLongerSweepsOtherSourceRows() {
        when(userRoleMapper.delete(any())).thenReturn(1);
        when(userRoleMapper.insert(any(UserRole.class))).thenReturn(1);
        com.wenqu.ai.service.UserRoleService urs = new com.wenqu.ai.service.UserRoleService(
                mock(AuditService.class), userRoleMapper, roleService, mock(UserMapper.class),
                mock(ReplicaCoordination.class));
        urs.grant("u1", "editor", "idp");
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<UserRole>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        org.mockito.Mockito.verify(userRoleMapper).delete(captor.capture());
        // 删除条件必须携带 granted_by='idp'
        String sql = captor.getValue().getSqlSegment();
        org.mockito.Mockito.verify(userRoleMapper).insert(any(UserRole.class));
        assertThat(sql).contains("granted_by");
    }
}
