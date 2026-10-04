package com.wenqu.ai.config;

import com.wenqu.ai.mapper.MenuApiMapper;
import com.wenqu.ai.mapper.MenuMapper;
import com.wenqu.ai.mapper.RoleMapper;
import com.wenqu.ai.mapper.RoleMenuMapper;
import com.wenqu.ai.model.Menu;
import com.wenqu.ai.model.Role;
import com.wenqu.ai.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RBAC 种子数据（幂等：角色表**仅空表**时灌入，菜单清单按 id **补齐缺失项**、退役项连行清理；
 * user 角色基础菜单**按项补齐**）：
 * <ul>
 *   <li>角色表为空 → 预置 superadmin / admin（管理员级）与 user（普通）；</li>
 *   <li>菜单清单 → 按 id **补齐**缺失的内置菜单（新增菜单随版本自动登记）：对话/智能体/知识库/我的产物/
 *       成员管理/数据看板/检索评估/权限管理/系统设置；已存在的行不动；</li>
 *   <li>user 角色基础菜单 → <b>按项补齐</b>对话/智能体/知识库/我的产物/使用统计；
 *       admin/superadmin 为管理员级，无需绑定即见全部。</li>
 * </ul>
 * <p><b>基础菜单为何不是「全空才灌」</b>（2026-10-04 修）：c_ai_role_menu 是纯关系表，
 * 「漏绑」与「管理员主动解绑」在数据上无法区分，若沿用全空守卫，存量库只要存在任意一条绑定
 * （最典型：本类曾为 menu-stats 单开一条补绑分支）就永远补不上剩余项——
 * 现象是新部署/升级环境的普通用户侧边栏<b>缺「智能体」入口</b>，而管理员级走
 * {@code allowed=null} 全量可见故完全不显现。故改为按项补齐。</p>
 * <p>该改动的代价（已知取舍，非 bug）：管理员手动解绑基础菜单后，重启会被重新绑回。
 * 两种错的代价不对等——漏绑使功能对所有人不可用，回置只是每次重启重做一次。
 * 需永久隐藏某菜单请用菜单自身的 {@code visible=0} 或停用该角色，不依赖这张关系表。</p>
 * 刻意不用 schema.sql 灌种子：spring.sql.init 每次启动都执行，INSERT IGNORE 语义会把
 * 管理员在权限页的调整覆盖回去。接口清单不在此种子——由 {@link ApiEndpointScanner}
 * 每次启动扫描登记（@Order 靠后，保证先有角色/菜单再有接口）。
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
            new String[]{"menu-artifacts", "我的产物", "FileTextOutlined", "/artifacts", "35"},
            new String[]{"menu-stats", "使用统计", "PieChartOutlined", "/stats", "38"},
            new String[]{"menu-members", "成员管理", "TeamOutlined", "/members", "40"},
            new String[]{"menu-dashboard", "数据看板", "BarChartOutlined", "/dashboard", "50"},
            new String[]{"menu-evaluation", "检索评估", "ExperimentOutlined", "/evaluation", "60"},
            new String[]{"menu-permissions", "权限管理", "SafetyOutlined", "/permissions", "70"},
            new String[]{"menu-settings", "系统设置", "SettingOutlined", "/settings", "80"}
    );

    /**
     * 已退役的内置菜单：入口已迁移（帮助中心 → 前端右下角全局悬浮按钮，见 HelpFab.vue），
     * 种子清单剔除后存量库需按 id 连行清理（菜单 + 角色绑定），否则老库侧边栏仍会显示。
     */
    private static final List<String> RETIRED_MENUS = List.of("menu-help");

    /** user 角色默认可见的内置菜单 id（个人资产类与问答类一致，默认对所有人开放） */
    private static final List<String> USER_MENUS =
            List.of("menu-chat", "menu-agents", "menu-knowledge", "menu-artifacts", "menu-stats");

    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final MenuApiMapper menuApiMapper;
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
        // 菜单清单：按 id 补齐缺失的内置菜单（新增菜单随版本自动登记，不需要手工 SQL）。
        // 与「角色-菜单绑定」刻意分开：绑定仍只在空表时灌——管理员在权限页的解绑不该被启动逻辑恢复；
        // 新菜单默认对普通角色不可见（管理员直通全部菜单），需在「权限管理」里勾选，fail-closed。
        Set<String> existingMenuIds = menuMapper.selectList(null).stream()
                .map(Menu::getId).collect(Collectors.toSet());
        // 退役菜单：种子清单里已删除的内置菜单，存量库连行清理（含角色绑定与接口归属），幂等
        int menuRetired = 0;
        for (String mid : RETIRED_MENUS) {
            if (!existingMenuIds.contains(mid)) continue;
            roleMenuMapper.unbindByMenu(mid);
            menuApiMapper.unbindByMenu(mid);
            menuMapper.deleteById(mid);
            existingMenuIds.remove(mid);
            menuRetired++;
            log.info("[RbacSeed] 已退役内置菜单 {}", mid);
        }
        if (menuRetired > 0) seeded = true;
        int menuAdded = 0;
        LocalDateTime now = LocalDateTime.now();
        for (String[] d : BUILTIN_MENUS) {
            if (existingMenuIds.contains(d[0])) continue;
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
            menuAdded++;
            // 存量库升级：使用统计是个人资产类页面（与「我的产物」同类），随 USER_MENUS
            // 一并按项补绑（原为此处一条独立分支，只服务 menu-stats 一项，是同一个
            // 「全空才灌」缺陷的两次犯——它的副作用正是让存量库先有了一条绑定，
            // 使下方基础菜单补齐永远空跑）。
        }
        if (menuAdded > 0) {
            seeded = true;
            log.info("[RbacSeed] 已补齐 {} 个内置菜单（共 {} 个）", menuAdded, BUILTIN_MENUS.size());
        }
        // user 角色基础菜单：按项补齐（此前是「全空才灌」，见方法注释）。
        // 已绑定的项不重复写，故对管理员的既有绑定零影响。
        Set<String> userMenuIds = new HashSet<>(roleMenuMapper.menuIdsOfRole("user"));
        List<String> userMenuAdded = USER_MENUS.stream().filter(userMenuIds::add).collect(Collectors.toList());
        if (!userMenuAdded.isEmpty()) {
            for (String mid : userMenuAdded) roleMenuMapper.bind("user", mid);
            roleService.invalidateCaches();
            log.info("[RbacSeed] 已为 user 角色补绑基础菜单 {}", userMenuAdded);
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
