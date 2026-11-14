package com.wenqu.ai.startup;

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
 * <p><b>基础菜单为何按项补齐</b>：c_ai_role_menu 是纯关系表，「漏绑」与「管理员主动解绑」
 * 在数据上无法区分；若按「表空才灌」，库中只要存在任意一条绑定就永远补不上剩余项——
 * 普通用户侧边栏会缺菜单入口，而管理员级走 {@code allowed=null} 全量可见故完全不显现。</p>
 * <p>代价（已知取舍，非 bug）：管理员手动解绑基础菜单后，重启会被重新绑回。
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
@Order(2)
@RequiredArgsConstructor
public class RbacSeedRunner implements ApplicationRunner {

    /**
     * 内置菜单定义：id / 名称 / 图标 / 路径 / 排序 / 默认可见性（1=显示，0=隐藏）。
     * <p>
     * {@code menu-chat} 默认 0：侧栏顶部已有「新建对话」，会话本身从工作区树进入，
     * 「对话」这一项不再占侧栏位。但**行必须留着**——它承载 Chat 接口的归属（{@code c_ai_menu_api}），
     * 删行会连带清掉绑定（见 {@link #RETIRED_MENUS} 那段）。
     * 隐藏写成菜单数据而不是前端硬过滤：管理员在「权限管理 → 编辑菜单」把「显示」打开即可恢复，
     * 不必改代码；反过来若前端按路径开例外，这里改了也没效果。
     */
    private static final List<String[]> BUILTIN_MENUS = List.of(
            new String[]{"menu-chat", "对话", "MessageOutlined", "/chat", "10", "0"},
            new String[]{"menu-agents", "智能体", "RobotOutlined", "/agents", "20", "1"},
            // 市场（技能市场）：发现/安装同事上架的技能，或把自己的技能上架——与技能同为个人资产口径
            new String[]{"menu-market", "市场", "AppstoreOutlined", "/market", "25", "1"},
            new String[]{"menu-knowledge", "知识库", "DatabaseOutlined", "/knowledge", "30", "1"},
            new String[]{"menu-artifacts", "我的产物", "FileTextOutlined", "/artifacts", "35", "1"},
            new String[]{"menu-stats", "使用统计", "PieChartOutlined", "/stats", "38", "1"},
            new String[]{"menu-members", "成员管理", "TeamOutlined", "/members", "40", "1"},
            new String[]{"menu-tenants", "租户与开通", "ApartmentOutlined", "/tenants", "45", "1"},
            new String[]{"menu-security", "安全与运维", "SafetyCertificateOutlined", "/security", "46", "1"},
            new String[]{"menu-evaluation", "质量", "ExperimentOutlined", "/quality", "50", "1"},
            new String[]{"menu-permissions", "权限管理", "SafetyOutlined", "/permissions", "70", "1"},
            new String[]{"menu-settings", "系统设置", "SettingOutlined", "/settings", "80", "1"}
    );

    /** 挂「权限管理」下的页签：认证源、审计事件。 */
    private static final String[] IDENTITY_TAB_MENU =
            {"menu-identity-providers", "认证源", "/permissions?tab=identity", "15", "menu-permissions"};

    /** 挂「安全与运维」下的页签：审计事件 */
    private static final String[] AUDIT_TAB_MENU =
            {"menu-audit-events", "审计事件", "/security?tab=audit", "40", "menu-security"};

    /**
     * 服务账号：机器身份的管理面板，挂在「认证源」旁边——同一层「谁能调用」的授权对象。
     * 排序 25 让它紧跟认证源（15）、在审计事件之前。
     */
    private static final String[] SERVICE_ACCOUNT_TAB_MENU =
            {"menu-service-accounts", "服务账号", "/permissions?tab=service", "25", "menu-permissions"};

    /**
     * 内置 Tab 菜单（renderAs=tab）：id / 名称 / path / 排序 / 父菜单。
     * <p>
     * 把页内 Tab 收编进菜单表：每个 Tab 可独立授权，顺序与增减也由数据驱动。
     * 组件映射（tabKey → Vue 组件）仍在宿主页组件里：菜单管「有哪些/叫什么/什么顺序」，
     * 代码管「渲染成什么」，两者职责不同，不要试图把组件类名塞进菜单表。
     * <p>path 存完整跳转 {@code /agents?tab=key} —— 与 sidebar 型「path=点击后去哪」语义一致；
     * 宿主页从 path 的 query 解析 tabKey 映射组件。icon 留空（Tab 条本就是纯文字）。
     * <p>这张表也是「一个独立管理页并入他页 Tab」的落点：独立页收编为宿主页的页内 Tab 后
     * 仍是独立菜单行，接口归属（{@code c_ai_menu_api}）与角色授权粒度都按独立菜单行处理
     * ——只是渲染位置从侧栏换成了页签。
     * <p><b>「可单独授权」管的是宿主页里这个页签出不出现，不管进不进得了宿主页</b>：路由守卫按
     * {@code menuHasPath} 精确匹配菜单 path 与路由 path，而 tab 型存的是 {@code /permissions?tab=identity}
     * 带查询串的那一份，对不上 {@code /permissions}。故只勾 Tab 不勾宿主菜单的角色仍会被挡在门外——
     * 并入一个页面就意味着接受该页面的准入条件。
     */
    private static final List<String[]> BUILTIN_TAB_MENUS = List.of(
            new String[]{"menu-agents-tab-providers", "模型供应商", "/agents?tab=providers", "10", "menu-agents"},
            new String[]{"menu-agents-tab-agents", "智能体", "/agents?tab=agents", "20", "menu-agents"},
            new String[]{"menu-agents-tab-skills", "技能 Skills", "/agents?tab=skills", "30", "menu-agents"},
            new String[]{"menu-agents-tab-mcp", "MCP 外部工具", "/agents?tab=mcp", "40", "menu-agents"},
            new String[]{"menu-agents-tab-workflow", "工作流", "/agents?tab=workflow", "50", "menu-agents"},
            new String[]{"menu-agents-tab-scheduled", "定时任务", "/agents?tab=scheduled", "60", "menu-agents"},
            IDENTITY_TAB_MENU,
            SERVICE_ACCOUNT_TAB_MENU,
            AUDIT_TAB_MENU
    );

    /**
     * 种子清单已剔除的内置菜单：存量库需按 id 连行清理
     * （菜单 + 角色绑定 + 接口归属），否则老库仍会显示。
     * <p>
     * {@code menu-help}：帮助中心 → 前端右下角全局悬浮按钮（HelpFab.vue）。
     * {@code menu-oidc-providers}：OIDC 专属管理页（「单点登录」Tab）→ 由「认证源」统一页签取代
     * （OIDC/SAML/SCIM/LDAP 四类一起管，同一份数据），不做兼容。
     * {@code menu-content-safety}：内容安全整体退役（2026-11-14），页签与规则清单一并下线。
     */
    private static final List<String> RETIRED_MENUS =
            List.of("menu-help", "menu-oidc-providers", "menu-content-safety");

    /**
     * 质量页的页签菜单（renderAs=tab）：检索评估 / 智能体评测 / 数据看板。
     * <p>评测与数据看板原分别挂在智能体页、顶级行；并入「质量」后页签清单由本表驱动，
     * {@code menu-agents-tab-eval} 沿用旧 id——接口归属（{@code c_ai_menu_api}）随行保留。</p>
     */
    private static final List<String[]> QUALITY_TAB_MENUS = List.of(
            new String[]{"menu-quality-tab-retrieval", "检索评估", "/quality?view=eval", "10", "menu-evaluation"},
            new String[]{"menu-agents-tab-eval", "智能体评测", "/quality?view=agent", "20", "menu-evaluation"},
            new String[]{"menu-dashboard", "数据看板", "/quality?view=dashboard", "30", "menu-evaluation"}
    );

    /**
     * 企业面专属菜单：standard 档不下发、也不出现在菜单管理页（V3 列 enterprise_only）。
     * 判据与后端 {@code @EnterpriseOnly} 对齐：多租户（/tenants）、安全运维（审计）、服务账号。
     */
    private static final Set<String> ENTERPRISE_MENUS = Set.of(
            "menu-tenants", "menu-security", "menu-audit-events", "menu-service-accounts");

    /** user 角色默认可见的内置菜单 id（个人资产类与问答类一致，默认对所有人开放） */
    private static final List<String> USER_MENUS =
            List.of("menu-chat", "menu-agents", "menu-market", "menu-knowledge", "menu-artifacts", "menu-stats",
                    "menu-evaluation",
                    // 智能体的 Tab：按项绑定，进智能体即默认可见；
                    // 「智能体评测」已并入「质量」页，菜单行 id 沿用 menu-agents-tab-eval，绑定照旧
                    "menu-agents-tab-providers", "menu-agents-tab-agents", "menu-agents-tab-skills",
                    "menu-agents-tab-mcp", "menu-agents-tab-workflow", "menu-agents-tab-scheduled");

    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final MenuApiMapper menuApiMapper;
    private final RoleService roleService;
    private final com.wenqu.ai.mapper.ApiEndpointMapper apiEndpointMapper;
    private final com.wenqu.ai.mapper.RolePermissionMapper rolePermissionMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        // 「质量」页签搬家只做一次：旧库里评测行的父还挂在 menu-agents。以旧父为判据，
        // 结构同步 + 角色绑定迁移跑完后不再触发——管理员后续在权限页的解绑不会被重启恢复。
        com.wenqu.ai.model.Menu legacyEvalTab = menuMapper.selectById("menu-agents-tab-eval");
        boolean qualityTabsMoved = legacyEvalTab != null && "menu-agents".equals(legacyEvalTab.getParentId());
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
            m.setVisible(Integer.valueOf(d[5]));
            m.setBuiltin(1);
            m.setEnterpriseOnly(ENTERPRISE_MENUS.contains(d[0]) ? 1 : 0);
            m.setCreateTime(now);
            menuMapper.insert(m);
            menuAdded++;
            // 使用统计是个人资产类页面（与「我的产物」同类），随 USER_MENUS 一并按项补绑。
        }
        if (menuAdded > 0) {
            seeded = true;
            log.info("[RbacSeed] 已补齐 {} 个内置菜单（共 {} 个）", menuAdded, BUILTIN_MENUS.size());
        }
        // 内置 Tab 菜单：与侧栏菜单同一补齐语义（按 id 补缺失，存在的不动）。
        // 父菜单（menu-agents / menu-permissions / menu-evaluation）一定先于本循环存在，无需再验父存在性。
        int tabAdded = 0;
        List<String[]> tabMenus = new java.util.ArrayList<>(BUILTIN_TAB_MENUS);
        tabMenus.addAll(QUALITY_TAB_MENUS);
        for (String[] d : tabMenus) {
            if (existingMenuIds.contains(d[0])) continue;
            Menu m = new Menu();
            m.setId(d[0]);
            m.setName(d[1]);
            m.setPath(d[2]);
            m.setParentId(d[4]);
            m.setRenderAs("tab");
            m.setSortOrder(Integer.valueOf(d[3]));
            m.setVisible(1);
            m.setBuiltin(1);
            m.setEnterpriseOnly(ENTERPRISE_MENUS.contains(d[0]) ? 1 : 0);
            m.setCreateTime(now);
            menuMapper.insert(m);
            existingMenuIds.add(d[0]);
            tabAdded++;
        }
        if (tabAdded > 0) {
            seeded = true;
            log.info("[RbacSeed] 已补齐 {} 个内置 Tab 菜单（共 {} 个）", tabAdded, tabMenus.size());
        }
        // 内置 Tab 行只在缺失时插入，存量库那一行可能仍是旧 path/parent；
        // 这里做一次幂等同步（只改结构两列，不删角色绑定）。
        if (syncBuiltinTab(AUDIT_TAB_MENU)) seeded = true;
        // 质量页合并：menu-evaluation 原地复用为「质量」（保留 id 与 API 归属）。
        if (syncBuiltinMenu(new String[]{"menu-evaluation", "质量", "ExperimentOutlined", "/quality", "50", "1"})) {
            seeded = true;
        }
        // 企业面标记：按 id 校正存量行（新增内置菜单若属于企业面，不依赖手写 SQL）
        for (String mid : ENTERPRISE_MENUS) {
            Menu m = menuMapper.selectById(mid);
            if (m != null && !Integer.valueOf(1).equals(m.getEnterpriseOnly())) {
                m.setEnterpriseOnly(1);
                menuMapper.updateById(m);
                seeded = true;
                log.info("[RbacSeed] 已标记企业面专属菜单 {}", mid);
            }
        }
        // 质量页签：存量库把评测/数据看板两行搬到「质量」下并打开可见（只在搬家那一次动 visible），
        // 新增的「检索评估」行由上面的插入循环补上。
        int tabsSynced = 0;
        for (String[] d : QUALITY_TAB_MENUS) {
            if (syncQualityTab(d, qualityTabsMoved)) tabsSynced++;
        }
        if (tabsSynced > 0) {
            seeded = true;
            log.info("[RbacSeed] 已同步 {} 条质量页签菜单", tabsSynced);
        }
        // 质量页签改为菜单驱动：原先页签是硬编码的，所有能进 /quality 的角色都默认看到「智能体评测」。
        // 现在按绑定下发，为存量角色补绑一次（INSERT IGNORE，仅搬家那一次触发，不恢复管理员后来的解绑）。
        if (qualityTabsMoved) {
            int bound = 0;
            for (String tenantId : distinctTenants()) {
                try {
                    com.wenqu.ai.util.TenantContext.bind(tenantId);
                    for (String roleCode : roleMenuMapper.roleCodesOfMenu("menu-evaluation")) {
                        bound += roleMenuMapper.bind(roleCode, "menu-agents-tab-eval");
                    }
                } finally {
                    com.wenqu.ai.util.TenantContext.bind(null);
                }
            }
            if (bound > 0) {
                seeded = true;
                log.info("[RbacSeed] 质量页签改为菜单驱动：已为 {} 条角色授权补绑「智能体评测」", bound);
            }
        }
        // user 角色基础菜单：按项补齐。已绑定的项不重复写，故对管理员的既有绑定零影响。
        Set<String> userMenuIds = new HashSet<>(roleMenuMapper.menuIdsOfRole("user"));
        List<String> userMenuAdded = USER_MENUS.stream().filter(userMenuIds::add).collect(Collectors.toList());
        if (!userMenuAdded.isEmpty()) {
            for (String mid : userMenuAdded) roleMenuMapper.bind("user", mid);
            roleService.invalidateCaches();
            log.info("[RbacSeed] 已为 user 角色补绑基础菜单 {}", userMenuAdded);
        }
        // 角色按租户各一套——遍历现存租户（default 恒在）逐租户种角色/绑定/预设。
        // 菜单是全局共享的（上面种一次），角色域在租户上下文内写入，tenant_id 由插件按当前上下文盖戳。
        Set<String> existingMenuIdsFinal = existingMenuIds;
        for (String tenantId : distinctTenants()) {
            try {
                com.wenqu.ai.util.TenantContext.bind(tenantId);
                seeded |= seedTenantRoles(tenantId, existingMenuIdsFinal);
            } finally {
                com.wenqu.ai.util.TenantContext.bind(null);
            }
        }
        if (seeded) roleService.invalidateCaches();
    }

    /**
     * 幂等同步一个内置 Tab 菜单的结构字段（path / parent / sort）。
     * <p>只服务「页签搬家」这类结构性升级：内置菜单只在缺失时插入，存量行不会自己改 path，
     * 不同步就会出现「菜单表里写着旧路径、代码里只认新宿主页」的半程态。只改结构，不碰角色绑定。
     */
    private boolean syncBuiltinTab(String[] d) {
        Menu m = menuMapper.selectById(d[0]);
        if (m == null) return false;
        int sort = Integer.parseInt(d[3]);
        if (java.util.Objects.equals(m.getPath(), d[2])
                && java.util.Objects.equals(m.getParentId(), d[4])
                && java.util.Objects.equals(m.getSortOrder(), sort)) {
            return false;
        }
        m.setPath(d[2]);
        m.setParentId(d[4]);
        m.setSortOrder(sort);
        menuMapper.updateById(m);
        log.info("[RbacSeed] 已同步内置 Tab 菜单 {} -> {}（父 {}）", d[0], d[2], d[4]);
        return true;
    }

    /**
     * 同步「质量」页签的结构字段（名称/路径/父/排序/渲染位置），并在搬家那一次打开可见性。
     * <p>之后不再动 visible——管理员在权限页的隐藏调整不该被每次重启恢复。</p>
     */
    private boolean syncQualityTab(String[] d, boolean turnVisibleOn) {
        Menu m = menuMapper.selectById(d[0]);
        if (m == null) return false;
        int sort = Integer.parseInt(d[3]);
        boolean changed = false;
        if (!java.util.Objects.equals(m.getName(), d[1])) { m.setName(d[1]); changed = true; }
        if (!java.util.Objects.equals(m.getPath(), d[2])) { m.setPath(d[2]); changed = true; }
        if (!java.util.Objects.equals(m.getParentId(), d[4])) { m.setParentId(d[4]); changed = true; }
        if (!"tab".equals(m.getRenderAs())) { m.setRenderAs("tab"); changed = true; }
        if (!java.util.Objects.equals(m.getSortOrder(), sort)) { m.setSortOrder(sort); changed = true; }
        if (turnVisibleOn && !Integer.valueOf(1).equals(m.getVisible())) { m.setVisible(1); changed = true; }
        if (!changed) return false;
        menuMapper.updateById(m);
        log.info("[RbacSeed] 已同步质量页签菜单 {} -> {}（父 {}）", d[0], d[2], d[4]);
        return true;
    }

    /** 幂等同步内置侧栏菜单的结构字段（名称/图标/路径/排序/可见性）；只改元数据，不碰角色绑定。 */
    private boolean syncBuiltinMenu(String[] d) {
        Menu m = menuMapper.selectById(d[0]);
        if (m == null) return false;
        int sort = Integer.parseInt(d[4]);
        int visible = Integer.parseInt(d[5]);
        boolean changed = !java.util.Objects.equals(m.getName(), d[1])
                || !java.util.Objects.equals(m.getIcon(), d[2])
                || !java.util.Objects.equals(m.getPath(), d[3])
                || !java.util.Objects.equals(m.getSortOrder(), sort)
                || !java.util.Objects.equals(m.getVisible(), visible);
        if (!changed) return false;
        m.setName(d[1]);
        m.setIcon(d[2]);
        m.setPath(d[3]);
        m.setSortOrder(sort);
        m.setVisible(visible);
        menuMapper.updateById(m);
        log.info("[RbacSeed] 已同步内置菜单 {} -> {}（{}）", d[0], d[3], d[1]);
        return true;
    }

    /** 全部租户 id（c_ai_user 的 distinct tenant_id ∪ default）——租户域角色要按租户各一套 */
    private List<String> distinctTenants() {
        Set<String> out = new java.util.LinkedHashSet<>();
        out.add(com.wenqu.ai.util.TenantContext.DEFAULT_TENANT);
        try {
            for (String t : jdbc.queryForList("SELECT DISTINCT tenant_id FROM c_ai_user", String.class)) {
                if (t != null && !t.isBlank()) out.add(t);
            }
        } catch (Exception e) {
            log.warn("[RbacSeed] 租户清单查询失败（按 default 单租户种）: {}", e.getMessage());
        }
        return List.copyOf(out);
    }

    /**
     * 单租户角色种子：内置三件套（该租户没有才建）+ user 基础菜单绑定 + 受限管理员预设。
     * 幂等：绑定按项补齐、角色按 id 查缺，管理员在权限页的调整不被启动逻辑恢复。
     *
     * @return 是否发生了写入（供外层决定是否失效缓存）
     */
    public boolean seedTenantRoles(String tenantId, Set<String> validMenuIds) {
        // 开通动作跑在平台视图里（asPlatform）：本方法必须自己绑到目标租户——
        // 否则存在性检查会看到 default 套的角色而整段跳过，INSERT 也盖错租户戳
        try {
            com.wenqu.ai.util.TenantContext.bind(tenantId);
            return seedTenantRolesBound(tenantId, validMenuIds);
        } finally {
            com.wenqu.ai.util.TenantContext.bind(null);
        }
    }

    private boolean seedTenantRolesBound(String tenantId, Set<String> validMenuIds) {
        boolean changed = false;
        for (String[] trio : new String[][]{
                {"superadmin", "超级管理员", "内置：全部权限，含最后一名保护", "1"},
                {"admin", "管理员", "内置：管理端全量权限", "1"},
                {"user", "普通用户", "内置：问答与个人资产", "0"}}) {
            // 显式按 (tenant_id, code) 查：平台视图下 selectById 不带谓词会误判"已存在"
            Role exist = roleMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Role>()
                    .eq(Role::getCode, trio[0]).apply("tenant_id = {0}", tenantId));
            if (exist != null) continue;
            insertRole(trio[0], trio[1], trio[2], Integer.parseInt(trio[3]));
            changed = true;
            log.info("[RbacSeed] 租户 {} 已预置角色 {}", tenantId, trio[0]);
        }
        // user 基础菜单绑定：按项补齐（每租户的 user 角色各绑各的）
        Set<String> userMenuIds = new HashSet<>(roleMenuMapper.menuIdsOfRole("user"));
        List<String> userMenuAdded = USER_MENUS.stream().filter(userMenuIds::add).collect(Collectors.toList());
        if (!userMenuAdded.isEmpty()) {
            for (String mid : userMenuAdded) roleMenuMapper.bind("user", mid);
            log.info("[RbacSeed] 租户 {} 的 user 角色补绑基础菜单 {}", tenantId, userMenuAdded);
            changed = true;
        }
        seedRestrictedAdmins(validMenuIds);
        // 种子写完后当前租户快照作废（本租户判定立刻读新数据）
        roleService.invalidateCaches();
        return changed;
    }

    /**
     * 受限管理员预设：「只读管理员」「运维管理员」。
     * 授权点集合是数据不是代码——管理员可在权限页随时调整；种子只负责首次建角色。
     * 均为非管理员级（admin_flag=0）：判定按授权点走，不再依赖 admin_flag 直通。
     */
    private void seedRestrictedAdmins(Set<String> validMenuIds) {
        // 只读管理员：全部「只读型」授权点（名下端点全为 GET）+ 个人资产 self 点；
        // 看得见全部页面（全菜单绑定），改不动任何东西（没有 write/delete/publish/manage 允许行）
        List<String> readonlyPerms = new java.util.ArrayList<>();
        java.util.Map<String, List<com.wenqu.ai.model.ApiEndpoint>> byPerm = new java.util.LinkedHashMap<>();
        for (com.wenqu.ai.model.ApiEndpoint api : apiEndpointMapper.selectList(null)) {
            if (api.getPermissionCode() == null || api.getPermissionCode().isBlank()) continue;
            byPerm.computeIfAbsent(api.getPermissionCode(), k -> new java.util.ArrayList<>()).add(api);
        }
        for (java.util.Map.Entry<String, List<com.wenqu.ai.model.ApiEndpoint>> e : byPerm.entrySet()) {
            boolean allReadonly = e.getKey().startsWith("self:");
            if (!allReadonly) {
                allReadonly = true;
                for (com.wenqu.ai.model.ApiEndpoint api : e.getValue()) {
                    if (!"GET".equalsIgnoreCase(api.getMethod()) && !"ALL".equalsIgnoreCase(api.getMethod())) {
                        allReadonly = false;
                        break;
                    }
                }
            }
            if (allReadonly) readonlyPerms.add(e.getKey());
        }
        // 运维管理员：运维域整包（数据清单；运行期可在权限页增删）
        List<String> opsPerms = List.of("ops:read", "ops:manage", "trace:read", "trace:manage", "audit:read",
                "audit:verify", "stats:read", "search-index:manage", "desc-cache:manage", "config:read",
                "content-safety:manage", "quota:read", "manual:sync", "retrieval-debug:run",
                // 会话治理与公告定向要读成员/部门/角色；只读权限，不含任何成员写操作
                "user:read", "department:read", "role:read");
        seedPresetRole("readonly-admin", "只读管理员", "内置：全部页面可见，全部动作只读（写类端点 403）",
                readonlyPerms, validMenuIds, validMenuIds);
        seedPresetRole("ops-admin", "运维管理员", "内置：运维域（巡检/追踪/审计/统计/索引维护），不碰业务配置",
                opsPerms, java.util.Set.of("menu-settings", "menu-audit-events", "menu-stats",
                        "menu-evaluation", "menu-security"), validMenuIds);
    }

    /** 单个受限管理员预设：角色缺失才建（幂等），建完绑菜单（限定在现存菜单内）与授权点 */
    private void seedPresetRole(String code, String name, String desc, List<String> perms,
                                java.util.Set<String> wantedMenus, Set<String> validMenuIds) {
        // 同上：显式 (tenant_id, code) 查缺，防平台视图下的全局可见性误判
        Role presetExist = roleMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Role>()
                .eq(Role::getCode, code).apply("tenant_id = {0}", com.wenqu.ai.util.TenantContext.current()));
        if (presetExist != null) return;
        Role r = new Role();
        r.setCode(code);
        r.setName(name);
        r.setDescription(desc);
        r.setAdminFlag(0);
        r.setBuiltin(1);
        r.setStatus(1);
        r.setCreateTime(java.time.LocalDateTime.now());
        roleMapper.insert(r);
        int pm = 0, pp = 0;
        if (wantedMenus != null) {
            for (String mid : wantedMenus) {
                if (validMenuIds != null && !validMenuIds.contains(mid)) continue; // 预设菜单已退役则跳过
                pm += roleMenuMapper.bind(code, mid);
            }
        }
        if (perms != null) {
            for (String perm : perms) {
                pp += rolePermissionMapper.bindAllow(code, perm);
            }
        }
        log.info("[RbacSeed] 已预置受限管理员角色 {}（菜单 {} 条，授权点 {} 个）", code, pm, pp);
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
