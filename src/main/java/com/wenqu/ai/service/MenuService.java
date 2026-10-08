package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.MenuApiMapper;
import com.wenqu.ai.mapper.MenuMapper;
import com.wenqu.ai.mapper.RoleMenuMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Menu;
import com.wenqu.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 菜单服务：侧边栏菜单数据源的 CRUD 与「按角色下发」解析。
 * <p>
 * 下发规则（{@link #visibleMenusFor}）：管理员级角色（含 superadmin）可见全部启显菜单；
 * 普通角色按 {@code c_ai_role_menu} 绑定 ∩ 可见菜单；组树后仅保留「根到叶有效」的节点
 * （父菜单未授权但子菜单授权时，父节点自动随子下放——侧边栏需要父级才能渲染层级）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuMapper menuMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final MenuApiMapper menuApiMapper;
    private final RoleService roleService;
    private final UserMapper userMapper;

    /** 偏好 JSON 解析专用：字段名固定、类型固定，不受全局 ObjectMapper 配置影响 */
    private static final ObjectMapper PREF_MAPPER = new ObjectMapper();

    // ==================== 下发（/auth/me 热路径） ====================

    /**
     * 当前角色可见的菜单树（仅 visible=1），按 sortOrder 升序；节点含 children。
     * 输出为 LinkedHashMap 列表（Jackson 直接序列化）。
     */
    public List<Map<String, Object>> visibleMenusFor(String role) {
        return visibleMenusFor(role, null);
    }

    /**
     * 个人偏好版：{@code prefJson} 为空/解析失败时行为与 {@link #visibleMenusFor(String)} 一致。
     * <p>
     * <b>越权防护（关键）</b>：偏好只在「RBAC 授权结果」之后生效 —— 先按角色算出可见集合，
     * 再在集合内按个人 order 重排、剔除 hidden。偏好里出现未授权菜单的 id 一律忽略，
     * 因此无法借此看到未授权菜单。<b>RBAC 是唯一的可见性来源，个人偏好只做二次过滤。</b>
     * <p>
     * <b>容错</b>：偏好脏数据（JSON 坏、id 已删除、角色变更后授权收回）一律静默忽略：
     * order 里失效的 id 跳过、剩余按菜单表原序补后，绝不丢菜单或清空侧栏。
     */
    public List<Map<String, Object>> visibleMenusFor(String role, String prefJson) {
        List<Menu> all = menuMapper.selectList(new LambdaQueryWrapper<Menu>()
                .eq(Menu::getVisible, 1)
                .orderByAsc(Menu::getSortOrder)
                .orderByAsc(Menu::getCreateTime));
        if (all.isEmpty()) return List.of();

        Set<String> allowed;
        if (roleService.isAdminCode(role)) {
            allowed = null; // 管理员级：全部可见
        } else {
            allowed = new HashSet<>(roleService.menuIdsOf(role));
            if (allowed.isEmpty()) return List.of();
        }

        // 组树：id → 节点；授权判定沿父链（父未授权但子授权 → 父随子下放）
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<String, List<String>> childrenOf = new LinkedHashMap<>();
        Map<String, String> parentOf = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (Menu m : all) {
            nodes.put(m.getId(), toNode(m));
            parentOf.put(m.getId(), m.getParentId());
            childrenOf.computeIfAbsent(m.getParentId() == null ? "" : m.getParentId(),
                    k -> new ArrayList<>()).add(m.getId());
        }
        for (String id : nodes.keySet()) {
            String pid = parentOf.get(id);
            boolean root = pid == null || !nodes.containsKey(pid);
            if (root) roots.add(id);
        }
        // 个人偏好：重排 + 隐藏（只作用于上面算出的授权可见集合）
        applyPref(roots, nodes, childrenOf, prefJson);

        List<Map<String, Object>> out = new ArrayList<>();
        for (String rootId : roots) {
            Map<String, Object> node = buildBranch(rootId, nodes, childrenOf, allowed);
            if (node != null) out.add(node);
        }
        return out;
    }

    /**
     * 施加个人偏好到顶级 id 列表（原地重排 + 剔除）：
     * <ul>
     *   <li>hidden 中的 id 从 roots 移除；其直接子级上提为顶级（父被隐藏不应连带子级消失）；</li>
     *   <li>order 中不在 roots 的 id 忽略（未授权 / 已隐藏 / 已删除），其余按 order 排、剩余补后；</li>
     *   <li>解析失败 → 不施加，走菜单表默认。</li>
     * </ul>
     */
    private void applyPref(List<String> roots, Map<String, Map<String, Object>> nodes,
                           Map<String, List<String>> childrenOf, String prefJson) {
        if (prefJson == null || prefJson.isBlank()) return;
        MenuPref pref;
        try {
            pref = PREF_MAPPER.readValue(prefJson, MenuPref.class);
        } catch (Exception e) {
            log.warn("[MENU] 个人侧栏偏好解析失败，按默认下发：{}", e.toString());
            return;
        }
        if (pref == null) return;

        if (pref.hidden != null && !pref.hidden.isEmpty()) {
            for (String id : pref.hidden) {
                if (id == null || !roots.remove(id)) continue;
                // 子级上提：仅 sidebar/group 型（tab 的宿主被隐藏时 Tab 跟着消失——
                // 用户隐藏「智能体」入口绝不是想让 6 个 Tab 爬上侧栏；hidden 容器同理）
                for (String kid : childrenOf.getOrDefault(id, List.of())) {
                    Map<String, Object> node = nodes.get(kid);
                    String kidRa = node == null || node.get("renderAs") == null
                            ? "sidebar" : String.valueOf(node.get("renderAs"));
                    if (("sidebar".equals(kidRa) || "group".equals(kidRa)) && !roots.contains(kid)) {
                        roots.add(kid);
                    }
                }
            }
        }
        if (pref.order != null && !pref.order.isEmpty()) {
            List<String> reordered = new ArrayList<>();
            for (String id : pref.order) {
                if (id != null && roots.contains(id) && !reordered.contains(id)) reordered.add(id);
            }
            for (String id : roots) {
                if (!reordered.contains(id)) reordered.add(id);
            }
            roots.clear();
            roots.addAll(reordered);
        }
    }

    /** 个人侧栏偏好载荷：{@code {"order":[id…],"hidden":[id…]}} */
    public static class MenuPref {
        public List<String> order;
        public List<String> hidden;
    }

    /**
     * 解析偏好 JSON 供 /menu/my-layout 回显（与 {@link #applyPref} 同口径，避免控制器另建 ObjectMapper）。
     * 返回 null 表示「未自定义或脏数据」，由调用方按 customized=false 处理。
     */
    public MenuPref parsePref(String prefJson) {
        if (prefJson == null || prefJson.isBlank()) return null;
        try {
            MenuPref p = PREF_MAPPER.readValue(prefJson, MenuPref.class);
            if (p == null) return null;
            // order/hidden 归一为空列表而非 null：调用方直接遍历，不用到处判空
            if (p.order == null) p.order = List.of();
            if (p.hidden == null) p.hidden = List.of();
            return p;
        } catch (Exception e) {
            log.warn("[MENU] 个人侧栏偏好解析失败，按默认下发：{}", e.toString());
            return null;
        }
    }

    /**
     * 供 /auth/me 用的便捷入口：按当前用户（uid + role）下发菜单，自动叠加其个人偏好。
     * 偏好列不存在/为空/坏JSON 时静默退化为菜单表默认。
     */
    public List<Map<String, Object>> menusForUser(String uid, String role) {
        String pref = null;
        if (uid != null && !uid.isBlank()) {
            User u = userMapper.selectById(uid);
            pref = u == null ? null : u.getMenuPref();
        }
        return visibleMenusFor(role, pref);
    }

    /**
     * 「我的侧栏布局」抽屉的行清单：<b>不施加个人偏好</b>的侧栏可调项全集。
     * <p>
     * 为什么不直接用 {@code /auth/me} 的 menus：那份已按个人偏好剔除 hidden —— 用户隐藏过的菜单
     * 会从列表里消失，于是抽屉再也无法把它打开回来（只剩「恢复默认」整组重置一条路）。
     * 抽屉要的是「我能调的开关清单」，含已关闭的开关，与侧栏实际渲染结果是两件事。
     * <p>
     * 可见性仍只由 RBAC 决定（这里传 null 偏好，集合与授权菜单完全一致），
     * 范围同样只取 sidebar/group 顶级项 —— tab 由宿主页渲染、hidden 是纯权限容器，两者不进侧栏。
     * <p>
     * 返回的是<b>只含平铺字段的新节点</b>，不带 children：抽屉是开关清单，读到 children
     * 只会让「智能体」带着 6 个 Tab 子节点进响应（实测普通用户响应里就有）。直接复用
     * visibleMenusFor 的节点会把子树一起带出去，故逐字段重建。
     */
    public List<Map<String, Object>> adjustableSidebarMenus(String role) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : visibleMenusFor(role, null)) {
            String ra = m.get("renderAs") == null ? "sidebar" : String.valueOf(m.get("renderAs"));
            if (!"sidebar".equals(ra) && !"group".equals(ra)) continue;
            Map<String, Object> flat = new LinkedHashMap<>();
            flat.put("id", m.get("id"));
            flat.put("name", m.get("name"));
            flat.put("icon", m.get("icon"));
            flat.put("path", m.get("path"));
            flat.put("renderAs", ra);
            out.add(flat);
        }
        return out;
    }

    // ==================== 个人偏好读写 ====================

    /**
     * 保存本人侧栏偏好。只允许操作「本人有权看到」的菜单 —— order/hidden 里的陌生 id
     * （含未授权菜单、被删除菜单）直接丢弃，不入库，避免偏好成为越权探测通道。
     *
     * @param reset true=恢复默认（列置 null）
     */
    @Transactional
    public void saveMyPref(String uid, List<String> order, List<String> hidden, boolean reset) {
        User u = userMapper.selectById(uid);
        if (u == null) throw new BizException("用户不存在");
        LambdaUpdateWrapper<User> set = new LambdaUpdateWrapper<User>().eq(User::getUid, uid);
        if (reset) {
            // 置 null 恢复默认：MP 默认 NOT_NULL 策略会跳过 null 字段，须用 UpdateWrapper 显式 set
            userMapper.update(null, set.set(User::getMenuPref, null));
            log.info("[AUDIT] 用户 {} 恢复默认侧栏布局", uid);
            return;
        }
        // 白名单：当前角色可见的菜单 id（RBAC 结果，管理员级 = 全部 visible）
        Set<String> visible = visibleMenuIdsOf(u.getRole());
        MenuPref pref = new MenuPref();
        pref.order = sanitize(order, visible);
        pref.hidden = sanitize(hidden, visible);
        if (pref.order.isEmpty() && pref.hidden.isEmpty()) {
            // 与默认无异，直接置 null 省掉一次无意义的 JSON
            userMapper.update(null, set.set(User::getMenuPref, null));
            return;
        }
        userMapper.update(null, set.set(User::getMenuPref, writeJson(pref)));
        log.info("[AUDIT] 用户 {} 保存侧栏布局：重排 {} 项，隐藏 {} 项", uid, pref.order.size(), pref.hidden.size());
    }

    private static List<String> sanitize(List<String> ids, Set<String> visible) {
        if (ids == null) return List.of();
        return ids.stream().filter(id -> id != null && visible.contains(id)).distinct().toList();
    }

    private static String writeJson(Object o) {
        try {
            return PREF_MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException("保存失败：偏好序列化异常");
        }
    }

    /** 当前角色可见的菜单 id 集合（visible=1 ∩ 角色授权；管理员级 = 全部 visible） */
    public Set<String> visibleMenuIdsOf(String role) {
        List<Menu> all = menuMapper.selectList(new LambdaQueryWrapper<Menu>()
                .eq(Menu::getVisible, 1).select(Menu::getId));
        Set<String> ids = new LinkedHashSet<>();
        for (Menu m : all) ids.add(m.getId());
        if (roleService.isAdminCode(role)) return ids;
        ids.retainAll(new HashSet<>(roleService.menuIdsOf(role)));
        return ids;
    }

    /** 递归构建分支：无授权（allowed 判定）且无可见子 → 剪掉返回 null */
    private Map<String, Object> buildBranch(String id, Map<String, Map<String, Object>> nodes,
                                            Map<String, List<String>> childrenOf, Set<String> allowed) {
        Map<String, Object> node = nodes.get(id);
        boolean selfAllowed = allowed == null || allowed.contains(id);
        List<Map<String, Object>> kids = new ArrayList<>();
        for (String childId : childrenOf.getOrDefault(id, List.of())) {
            Map<String, Object> child = buildBranch(childId, nodes, childrenOf, allowed);
            if (child != null) kids.add(child);
        }
        if (!selfAllowed && kids.isEmpty()) return null;
        if (!kids.isEmpty()) node.put("children", kids);
        return node;
    }

    private static Map<String, Object> toNode(Menu m) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", m.getId());
        n.put("parentId", m.getParentId());
        n.put("name", m.getName());
        n.put("icon", m.getIcon());
        n.put("path", m.getPath());
        n.put("sortOrder", m.getSortOrder());
        n.put("visible", m.getVisible());
        n.put("renderAs", m.getRenderAs() == null ? "sidebar" : m.getRenderAs());
        n.put("builtin", m.getBuiltin());
        return n;
    }

    // ==================== CRUD（权限管理页） ====================

    /** 全量菜单（含隐藏，平铺；前端组树） */
    public List<Menu> list() {
        return menuMapper.selectList(new LambdaQueryWrapper<Menu>()
                .orderByAsc(Menu::getSortOrder)
                .orderByAsc(Menu::getCreateTime));
    }

    public Menu get(String id) {
        return menuMapper.selectById(id);
    }

    @Transactional
    public Menu create(String parentId, String name, String icon, String path, Integer sortOrder, Integer visible, String renderAs) {
        String n = normalizeName(name);
        String ra = normalizeRenderAs(renderAs);
        // tab 必须有宿主：没有父菜单的 tab 无处渲染，属于配置错误而非「顶级 tab」
        String p = validateParent(parentId, null);
        if ("tab".equals(ra)) {
            if (p == null) throw new BizException("Tab 型菜单必须指定父菜单（页内 Tab 需要宿主页面）");
            if (trimOrNull(path) == null) throw new BizException("Tab 型菜单必须配置跳转路径（如 /agents?tab=xxx）");
        }
        if ("group".equals(ra) && trimOrNull(path) != null) {
            throw new BizException("分组标题不可点击，不能配置路径");
        }
        ensurePathUnique(path, null);
        Menu m = new Menu();
        m.setParentId(p);
        m.setName(n);
        m.setIcon(trimOrNull(icon));
        m.setPath(trimOrNull(path));
        m.setRenderAs(ra);
        m.setSortOrder(sortOrder == null ? 0 : sortOrder);
        m.setVisible(visible == null || visible == 1 ? 1 : 0);
        m.setBuiltin(0);
        menuMapper.insert(m);
        log.info("[AUDIT] 新建菜单 id={} name={} path={} renderAs={}", m.getId(), n, m.getPath(), ra);
        return m;
    }

    @Transactional
    public void update(String id, String parentId, String name, String icon, String path,
                       Integer sortOrder, Integer visible, String renderAs) {
        Menu m = menuMapper.selectById(id);
        if (m == null) throw new BizException("菜单不存在");
        if (name != null) m.setName(normalizeName(name));
        if (icon != null) m.setIcon(trimOrNull(icon));
        if (path != null) {
            ensurePathUnique(path, id);
            m.setPath(trimOrNull(path));
        }
        if (parentId != null) m.setParentId(validateParent(parentId, id));
        if (sortOrder != null) m.setSortOrder(sortOrder);
        if (visible != null) {
            if (visible != 0 && visible != 1) throw new BizException("非法可见性");
            m.setVisible(visible);
        }
        // renderAs 的字段级校验在「合并后的最终态」上做：单独把 tab 改成 group（残留 path）、
        // 或单独清 parentId（tab 失去宿主）都会产生非法组合，逐字段校验拦不住
        if (renderAs != null) m.setRenderAs(normalizeRenderAs(renderAs));
        validateRenderState(m);
        menuMapper.updateById(m);
        log.info("[AUDIT] 编辑菜单 id={} name={} renderAs={}", id, m.getName(), m.getRenderAs());
    }

    /**
     * 渲染位置归一：空值回落 sidebar（存量行/未传时保持历史行为），非法值 fail-loud。
     * 取值即前端渲染分支的枚举，多一个错别字就多一个「菜单静默消失」的悬案，必须当场报错。
     */
    private static String normalizeRenderAs(String renderAs) {
        String ra = trimOrNull(renderAs);
        if (ra == null) return "sidebar";
        if (!List.of("sidebar", "tab", "group", "hidden").contains(ra)) {
            throw new BizException("非法渲染位置（sidebar/tab/group/hidden）");
        }
        return ra;
    }

    /** 渲染位置与父子/path 的组合校验（create 组装完与 update 改完各跑一次） */
    private void validateRenderState(Menu m) {
        String ra = m.getRenderAs() == null ? "sidebar" : m.getRenderAs();
        if ("tab".equals(ra)) {
            if (trimOrNull(m.getParentId()) == null) {
                throw new BizException("Tab 型菜单必须指定父菜单（页内 Tab 需要宿主页面）");
            }
            // path 是宿主页组件映射的桥梁（/agents?tab=xxx → tabKey），没有它 Tab 渲染不出来
            if (trimOrNull(m.getPath()) == null) {
                throw new BizException("Tab 型菜单必须配置跳转路径（如 /agents?tab=xxx）");
            }
        }
        if ("group".equals(ra) && trimOrNull(m.getPath()) != null) {
            throw new BizException("分组标题不可点击，不能配置路径");
        }
    }

    /** 删除菜单：内置不可删；有子菜单不可删；级联清角色绑定与接口归属 */
    @Transactional
    public void delete(String id) {
        Menu m = menuMapper.selectById(id);
        if (m == null) throw new BizException("菜单不存在");
        if (m.getBuiltin() != null && m.getBuiltin() == 1) throw new BizException("内置菜单不可删除");
        Long children = menuMapper.selectCount(new LambdaQueryWrapper<Menu>().eq(Menu::getParentId, id));
        if (children != null && children > 0) {
            throw new BizException("该菜单下仍有 " + children + " 个子菜单，请先删除子菜单");
        }
        menuMapper.deleteById(id);
        roleMenuMapper.unbindByMenu(id);
        menuApiMapper.unbindByMenu(id);
        log.info("[AUDIT] 删除菜单 id={} name={}", id, m.getName());
    }

    // ==================== 校验工具 ====================

    /** 父菜单校验：存在性 + 环检测（沿父链上溯，回到自身即环） */
    private String validateParent(String parentId, String selfId) {
        String p = trimOrNull(parentId);
        if (p == null) return null;
        if (p.equals(selfId)) throw new BizException("父菜单不能是自己");
        Menu parent = menuMapper.selectById(p);
        if (parent == null) throw new BizException("指定的父菜单不存在");
        // 环检测：从新父节点沿 parent_id 上溯（深度硬上限 50，防脏数据成环死循环）
        String cursor = p;
        for (int i = 0; i < 50 && cursor != null; i++) {
            if (cursor.equals(selfId)) throw new BizException("父菜单不能是其自身的后代（会成环）");
            Menu cur = menuMapper.selectById(cursor);
            cursor = cur == null ? null : cur.getParentId();
        }
        return p;
    }

    private void ensurePathUnique(String path, String excludeId) {
        String p = trimOrNull(path);
        if (p == null) return;
        Long c = menuMapper.selectCount(new LambdaQueryWrapper<Menu>().eq(Menu::getPath, p));
        if (c == null || c == 0) return;
        if (excludeId == null) throw new BizException("该路径已被其他菜单使用");
        Menu same = menuMapper.selectOne(new LambdaQueryWrapper<Menu>().eq(Menu::getPath, p).last("LIMIT 1"));
        if (same != null && !same.getId().equals(excludeId)) {
            throw new BizException("该路径已被其他菜单使用");
        }
    }

    private static String normalizeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new BizException("菜单名称不能为空");
        if (n.length() > 50) throw new BizException("菜单名称过长（最多 50 字）");
        return n;
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
