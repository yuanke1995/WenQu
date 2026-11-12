package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wenqu.ai.config.DeploymentGuard;
import com.wenqu.ai.mapper.MenuApiMapper;
import com.wenqu.ai.mapper.MenuMapper;
import com.wenqu.ai.mapper.RoleMenuMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Menu;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 菜单数据源必须与部署档位一致：standard 档不下发企业面菜单，也不让它在菜单管理页出现
 * （否则管理员能看到并授权一个点进去就 404 的入口）。
 */
class MenuEnterpriseScopeTest {

    private final MenuMapper menuMapper = mock(MenuMapper.class);
    private final RoleMenuMapper roleMenuMapper = mock(RoleMenuMapper.class);
    private final MenuApiMapper menuApiMapper = mock(MenuApiMapper.class);
    private final RoleService roleService = mock(RoleService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final UserRoleService userRoleService = mock(UserRoleService.class);
    private final AuditService audit = mock(AuditService.class);

    private MenuService service(String profile) {
        return new MenuService(audit, menuMapper, roleMenuMapper, menuApiMapper, roleService,
                userMapper, userRoleService, new DeploymentGuard("single", profile));
    }

    private static Menu menu(String id, Integer enterpriseOnly) {
        Menu m = new Menu();
        m.setId(id);
        m.setName(id);
        m.setPath("/" + id);
        m.setSortOrder(10);
        m.setVisible(1);
        m.setBuiltin(1);
        m.setEnterpriseOnly(enterpriseOnly);
        return m;
    }

    private void givenMenus() {
        when(menuMapper.selectList(any())).thenReturn(List.of(
                menu("menu-agents", 0),
                menu("menu-tenants", 1),
                menu("menu-security", 1)));
        when(roleService.isAdminAny(any())).thenReturn(true);
    }

    private static Set<String> idsOf(List<Map<String, Object>> tree) {
        return tree.stream().map(n -> String.valueOf(n.get("id"))).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("standard 档：侧栏下发不含企业面菜单")
    void standardHidesEnterpriseMenusFromSidebar() {
        givenMenus();
        List<Map<String, Object>> tree = service("standard").visibleMenusFor(List.of("admin"));
        assertThat(idsOf(tree)).containsExactly("menu-agents");
    }

    @Test
    @DisplayName("enterprise 档：侧栏下发包含企业面菜单")
    void enterpriseKeepsEnterpriseMenus() {
        givenMenus();
        List<Map<String, Object>> tree = service("enterprise").visibleMenusFor(List.of("admin"));
        assertThat(idsOf(tree)).containsExactlyInAnyOrder("menu-agents", "menu-tenants", "menu-security");
    }

    @Test
    @DisplayName("standard 档：菜单管理列表不含企业面菜单，避免误授权")
    void standardHidesEnterpriseMenusFromAdminList() {
        givenMenus();
        assertThat(service("standard").list())
                .extracting(Menu::getId)
                .containsExactly("menu-agents");
        assertThat(idsOf(service("standard").visibleMenusFor(List.of("admin")))).doesNotContain("menu-tenants");
    }

    @Test
    @DisplayName("可见菜单 id 集合同样按档位过滤（路由/授权共用这份口径）")
    void visibleMenuIdsFollowProfile() {
        givenMenus();
        assertThat(service("standard").visibleMenuIdsOf(List.of("admin")))
                .containsExactly("menu-agents");
        assertThat(service("enterprise").visibleMenuIdsOf(List.of("admin")))
                .containsExactlyInAnyOrder("menu-agents", "menu-tenants", "menu-security");
    }
}
