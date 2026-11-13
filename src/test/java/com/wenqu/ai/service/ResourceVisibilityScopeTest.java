package com.wenqu.ai.service;

import com.wenqu.ai.mapper.DepartmentMapper;
import com.wenqu.ai.mapper.RoleDataScopeMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Department;
import com.wenqu.ai.model.RoleDataScope;
import com.wenqu.ai.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 角色数据范围判定单测（批次 C2）：resolve = 创建者 MANAGE ∪ 角色范围 READ。
 * 覆盖：范围秩并集、本部门/含下级、归属人无部门、范围只授读不授管、内置库豁免不受影响。
 *
 * @author yuanke
 */
class ResourceVisibilityScopeTest {

    private RoleDataScopeMapper roleDataScopeMapper;
    private UserMapper userMapper;
    private DepartmentMapper departmentMapper;
    private ResourceVisibilityService visibility;

    @BeforeEach
    void setUp() {
        roleDataScopeMapper = mock(RoleDataScopeMapper.class);
        userMapper = mock(UserMapper.class);
        departmentMapper = mock(DepartmentMapper.class);
        visibility = new ResourceVisibilityService(roleDataScopeMapper, userMapper, departmentMapper);
    }

    private void givenScopes(RoleDataScope... scopes) {
        when(roleDataScopeMapper.selectList(any())).thenReturn(List.of(scopes));
    }

    private static RoleDataScope scope(String role, String type, String deptId) {
        RoleDataScope ds = new RoleDataScope();
        ds.setRoleCode(role);
        ds.setScopeType(type);
        ds.setDepartmentId(deptId);
        return ds;
    }

    private void givenDepartments(String... ids) {
        Map<String, Department> m = new LinkedHashMap<>();
        for (String id : ids) {
            Department d = new Department();
            d.setId(id);
            // 约定 id 形如 "root" / "root-child"：parent = 前缀截到最后一个 '-' 之前，无 '-' 则为根
            d.setParentId(id.contains("-") ? id.substring(0, id.lastIndexOf('-')) : null);
            m.put(id, d);
        }
        when(departmentMapper.selectList(any())).thenReturn(List.copyOf(m.values()));
    }

    private void givenOwnerDept(String ownerUid, String deptId) {
        User u = new User();
        u.setUid(ownerUid);
        u.setDepartmentId(deptId);
        when(userMapper.selectById(ownerUid)).thenReturn(u);
    }

    private void loggedInAsThread(String uid, String deptId, List<String> roles) {
        // RequestUser 是 ThreadLocal：resolve 内部直读，测试线程直接 set 即可
        com.wenqu.ai.util.RequestUser.set(uid, deptId, roles);
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        com.wenqu.ai.util.RequestUser.clear();
    }

    @Test
    @DisplayName("创建者恒 MANAGE（范围再小也管自己的东西）")
    void creatorAlwaysManages() {
        givenScopes(); // 无任何范围行
        var p = new ResourceVisibilityService.Principal("u1", null, "user");
        assertThat(visibility.resolve(p, "u1")).isEqualTo(ResourceVisibilityService.Permission.MANAGE);
    }

    @Test
    @DisplayName("scope=all 的角色：任何归属人的资源可读、不可管")
    void scopeAllReads() {
        givenScopes(scope("viewer", "all", null));
        com.wenqu.ai.util.RequestUser.set("u1", null, List.of("viewer"));
        givenOwnerDept("boss", "d1");
        var p = new ResourceVisibilityService.Principal("u1", null, "user");
        assertThat(visibility.resolve(p, "boss")).isEqualTo(ResourceVisibilityService.Permission.READ);
        assertThat(visibility.canManage(p, "boss")).isFalse();
    }

    @Test
    @DisplayName("scope=department：同部门可读、异部门不可读")
    void scopeDepartment() {
        givenScopes(scope("dept-viewer", "department", null));
        com.wenqu.ai.util.RequestUser.set("u1", "d1", List.of("dept-viewer"));
        givenDepartments("d1", "d2");
        givenOwnerDept("boss", "d1");
        var same = new ResourceVisibilityService.Principal("u1", "d1", "user");
        var other = new ResourceVisibilityService.Principal("u2", "d2", "user");
        // other 的角色集合同为 dept-viewer（同角色不同人）：RequestUser 在同线程只能装一份，
        // 异部门判定用直接构造 Principal 的 departmentId（resolve 读的是 Principal.dept + ThreadLocal.roles），
        // 这里换装线程态再验一次
        com.wenqu.ai.util.RequestUser.set("u2", "d2", List.of("dept-viewer"));
        assertThat(visibility.canRead(same, "boss")).isTrue();
        assertThat(visibility.canRead(other, "boss")).isFalse();
    }

    @Test
    @DisplayName("scope=department_and_children：未配锚点=看自己部门及其下级")
    void scopeChildren() {
        givenScopes(scope("lead", "department_and_children", null));
        givenDepartments("d1", "d1-sub", "d2");
        givenOwnerDept("boss", "d1-sub");
        com.wenqu.ai.util.RequestUser.set("u1", "d1", List.of("lead"));
        assertThat(visibility.canRead(new ResourceVisibilityService.Principal("u1", "d1", "user"), "boss")).isTrue();
        com.wenqu.ai.util.RequestUser.set("u2", "d2", List.of("lead"));
        assertThat(visibility.canRead(new ResourceVisibilityService.Principal("u2", "d2", "user"), "boss")).isFalse();
    }

    @Test
    @DisplayName("配了锚点：看的是那个部门（含下级），成员自己不必在其中")
    void anchoredScopeIgnoresCallerOwnDept() {
        // 审计岗形态：角色写死「财务部及其下级」，挂在谁身上都只看得到财务口的资源
        givenScopes(scope("audit", "department_and_children", "d1"));
        givenDepartments("d1", "d1-sub", "d2");
        givenOwnerDept("boss", "d1-sub");
        givenOwnerDept("peer", "d2-sub");
        com.wenqu.ai.util.RequestUser.set("u9", "d2", List.of("audit"));
        var p = new ResourceVisibilityService.Principal("u9", "d2", "user");
        assertThat(visibility.canRead(p, "boss")).isTrue();
        assertThat(visibility.canRead(p, "peer")).isFalse();
    }

    @Test
    @DisplayName("服务账号（没有部门）：配了锚点才套得上部门范围，没配就套不上")
    void anchorIsTheOnlyWayForIdentityWithoutDept() {
        givenDepartments("d1");
        givenOwnerDept("boss", "d1");
        com.wenqu.ai.util.RequestUser.set("svc:1", null, List.of("svc-role"));
        var svc = new ResourceVisibilityService.Principal("svc:1", null, "user");
        // 没配锚点：调用方没有部门可比 → 无范围可套
        givenScopes(scope("svc-role", "department", null));
        assertThat(visibility.canRead(svc, "boss")).isFalse();
        // 配了锚点：范围由角色行说清楚，与调用方自己的部门无关（快照 60s，改配置走写路径失效）
        givenScopes(scope("svc-role", "department", "d1"));
        visibility.invalidateScopes();
        assertThat(visibility.canRead(svc, "boss")).isTrue();
    }

    @Test
    @DisplayName("scope=all 不看归属人的部门：没配部门的人，其资源对 all 角色仍可见")
    void scopeAllDoesNotNeedOwnerDept() {
        givenScopes(scope("viewer", "all", null));
        com.wenqu.ai.util.RequestUser.set("u1", null, List.of("viewer"));
        User noDept = new User();
        noDept.setUid("boss");
        noDept.setDepartmentId(null);
        when(userMapper.selectById("boss")).thenReturn(noDept);
        var p = new ResourceVisibilityService.Principal("u1", null, "user");
        // 旧顺序把「归属人无部门」挡在 scope=all 之前，症状是范围配了 all 却看不见这批人的东西
        assertThat(visibility.canRead(p, "boss")).isTrue();
    }

    @Test
    @DisplayName("归属人无部门：部门范围套不上，NONE（fail-closed）")
    void ownerWithoutDeptFailsClosed() {
        givenScopes(scope("viewer", "department", null));
        com.wenqu.ai.util.RequestUser.set("u1", "d1", List.of("viewer"));
        User noDept = new User();
        noDept.setUid("boss");
        noDept.setDepartmentId(null);
        when(userMapper.selectById("boss")).thenReturn(noDept);
        var p = new ResourceVisibilityService.Principal("u1", "d1", "user");
        assertThat(visibility.canRead(p, "boss")).isFalse();
    }

    @Test
    @DisplayName("快照按租户键控：租户 B 不复用租户 A 建好的范围配置")
    void snapshotIsTenantKeyed() {
        // 复现批次 F 之后漏修的那一处：c_ai_role_data_scope 已按租户过滤，快照若是全局一份，
        // A 的请求建好快照后，B 在 60s 内会直接拿 A 的范围配置判定
        RoleDataScope a = scope("admin", "own", null);
        RoleDataScope b = scope("admin", "all", null);
        givenDepartments("d1");
        givenOwnerDept("boss", "d1");
        com.wenqu.ai.util.RequestUser.set("u-a", "d1", List.of("admin"));
        var pa = new ResourceVisibilityService.Principal("u-a", "d1", "user");
        try {
            com.wenqu.ai.util.TenantContext.bind("tenant-a");
            when(roleDataScopeMapper.selectList(any())).thenReturn(List.of(a));
            assertThat(visibility.canRead(pa, "boss")).isFalse();   // A：own → 别人的资源不可读

            com.wenqu.ai.util.TenantContext.bind("tenant-b");
            when(roleDataScopeMapper.selectList(any())).thenReturn(List.of(b));
            com.wenqu.ai.util.RequestUser.set("u-b", "d1", List.of("admin"));
            var pb = new ResourceVisibilityService.Principal("u-b", "d1", "user");
            assertThat(visibility.canRead(pb, "boss")).isTrue();    // B：all → 若串了 A 的缓存就是 false
        } finally {
            com.wenqu.ai.util.TenantContext.clear();
        }
    }

    @Test
    @DisplayName("多角色范围取最大：一个 all 压过其它 own")
    void unionTakesMax() {
        givenScopes(scope("limited", "own", null), scope("wide", "all", null));
        givenOwnerDept("boss", "d1");
        // RequestUser.roles() 是 ThreadLocal：登录态里带两个角色
        com.wenqu.ai.util.RequestUser.set("u1", null, List.of("limited", "wide"));
        var p = new ResourceVisibilityService.Principal("u1", null, "user");
        assertThat(visibility.canRead(p, "boss")).isTrue();
    }

    @Test
    @DisplayName("SYSTEM（无真实用户）：NONE（fail-closed）")
    void systemNone() {
        givenScopes(scope("viewer", "all", null));
        com.wenqu.ai.util.RequestUser.clear();
        givenOwnerDept("boss", "d1");
        assertThat(visibility.resolve(ResourceVisibilityService.Principal.SYSTEM, "boss"))
                .isEqualTo(ResourceVisibilityService.Permission.NONE);
    }

    @Test
    @DisplayName("内置手册库豁免不受范围影响（登录即可读）")
    void builtinKbExempt() {
        givenScopes(); // 无范围
        var p = new ResourceVisibilityService.Principal("u1", null, "user");
        assertThat(visibility.canReadBuiltinKb(p, 1, "boss")).isTrue();
        assertThat(visibility.canReadBuiltinKb(p, 0, "boss")).isFalse();
    }

    @Test
    @DisplayName("管理侧：内置手册库仅管理员可见（createdBy=system 不再把管理员挡在外面）")
    void builtinKbVisibleToAdminOnManagementSide() {
        givenScopes(); // 管理员同样没有范围行：createdBy=system 在谁建归谁下恒 NONE
        var admin = new ResourceVisibilityService.Principal("u1", null, "admin");
        var user = new ResourceVisibilityService.Principal("u2", null, "user");
        assertThat(visibility.canReadKbManagement(admin, 1, "system", true)).isTrue();
        assertThat(visibility.canReadKbManagement(user, 1, "system", false)).isFalse();
        // 非内置库仍按谁建归谁
        assertThat(visibility.canReadKbManagement(user, 0, "u2", false)).isTrue();
        assertThat(visibility.canReadKbManagement(user, 0, "boss", false)).isFalse();
        // 消费侧口径不变：内置库登录即可读
        assertThat(visibility.canReadBuiltinKb(user, 1, "system")).isTrue();
    }
}
