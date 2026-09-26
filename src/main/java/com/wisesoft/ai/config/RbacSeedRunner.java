package com.wisesoft.ai.config;

import com.wisesoft.ai.mapper.MenuMapper;
import com.wisesoft.ai.mapper.RoleMapper;
import com.wisesoft.ai.mapper.RoleMenuMapper;
import com.wisesoft.ai.model.Menu;
import com.wisesoft.ai.model.Role;
import com.wisesoft.ai.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RBAC 种子数据（仅**空表**时灌入，幂等且不覆盖管理员后续的解绑操作）：
 * <ul>
 *   <li>角色表为空 → 预置 superadmin / admin（管理员级）与 user（普通）；</li>
 *   <li>菜单表为空 → 预置 8 个内置菜单（固定 id：对话/智能体/知识库/成员管理/数据看板/检索评估/权限管理/系统设置）；</li>
 *   <li>角色-菜单绑定为空 → user 角色绑对话/智能体/知识库（与历史「侧边栏对所有人开放三项」一致）；
 *       admin/superadmin 为管理员级，无需绑定即见全部。</li>
 * </ul>
 * 刻意不用 schema.sql 灌种子：spring.sql.init 每次启动都执行，INSERT IGNORE 语义会把
 * 管理员在权限页解绑的项重新绑回去；「仅空表」才符合种子语义。接口清单不在此种子——
 * 由 {@link ApiEndpointScanner} 每次启动扫描登记（@Order 靠后，保证先有角色/菜单再有接口）。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RbacSeedRunner implements ApplicationRunner {

    /** 内置菜单定义：id / 名称 / 图标 / 路径 / 排序 */
    private static final List<String[]> BUILTIN_MENUS = List.of(
            new String[]{"menu-chat", "对话", "MessageOutlined", "/chat", "10"},
            new String[]{"menu-agents", "智能体", "RobotOutlined", "/agents", "20"},
            new String[]{"menu-knowledge", "知识库", "DatabaseOutlined", "/knowledge", "30"},
            new String[]{"menu-members", "成员管理", "TeamOutlined", "/members", "40"},
            new String[]{"menu-dashboard", "数据看板", "BarChartOutlined", "/dashboard", "50"},
            new String[]{"menu-evaluation", "检索评估", "ExperimentOutlined", "/evaluation", "60"},
            new String[]{"menu-permissions", "权限管理", "SafetyOutlined", "/permissions", "70"},
            new String[]{"menu-settings", "系统设置", "SettingOutlined", "/settings", "80"}
    );

    /** user 角色默认可见的内置菜单 id */
    private static final List<String> USER_MENUS =
            List.of("menu-chat", "menu-agents", "menu-knowledge");

    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final RoleService roleService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
        } catch (Exception e) {
            log.warn("[RbacSeed] 种子数据灌入失败（不阻塞启动）: {}", e.getMessage());
        }
    }

    void seed() {
        boolean seeded = false;
        if (roleMapper.selectCount(null) == 0) {
            insertRole("superadmin", "超级管理员", "内置：全部权限，含最后一名保护", 1);
            insertRole("admin", "管理员", "内置：管理端全量权限", 1);
            insertRole("user", "普通用户", "内置：问答与个人资产", 0);
            seeded = true;
            log.info("[RbacSeed] 已预置角色 superadmin/admin/user");
        }
        if (menuMapper.selectCount(null) == 0) {
            LocalDateTime now = LocalDateTime.now();
            for (String[] d : BUILTIN_MENUS) {
                Menu m = new Menu();
                m.setId(d[0]);
                m.setName(d[1]);
                m.setIcon(d[2]);
                m.setPath(d[3]);
                m.setSortOrder(Integer.valueOf(d[4]));
                m.setVisible(1);
                m.setBuiltin(1);
                m.setCreateTime(now);
                menuMapper.insert(m);
            }
            seeded = true;
            log.info("[RbacSeed] 已预置 {} 个内置菜单", BUILTIN_MENUS.size());
        }
        if (roleMenuMapper.menuIdsOfRole("user").isEmpty()) {
            // user 角色基础菜单：与历史侧边栏行为一致（对话/智能体/知识库对所有人开放）
            for (String mid : USER_MENUS) roleMenuMapper.bind("user", mid);
            roleService.invalidateCaches();
            log.info("[RbacSeed] 已为 user 角色绑定基础菜单 {}", USER_MENUS);
        }
        if (seeded) roleService.invalidateCaches();
    }

    private void insertRole(String code, String name, String desc, int adminFlag) {
        Role r = new Role();
        r.setCode(code);
        r.setName(name);
        r.setDescription(desc);
        r.setAdminFlag(adminFlag);
        r.setBuiltin(1);
        r.setStatus(1);
        roleMapper.insert(r);
    }
}
