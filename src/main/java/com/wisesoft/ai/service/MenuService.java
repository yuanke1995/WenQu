package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.MenuMapper;
import com.wisesoft.ai.mapper.RoleMenuMapper;
import com.wisesoft.ai.model.Menu;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private final RoleService roleService;

    // ==================== 下发（/auth/me 热路径） ====================

    /**
     * 当前角色可见的菜单树（仅 visible=1），按 sortOrder 升序；节点含 children。
     * 输出为 LinkedHashMap 列表（Jackson 直接序列化）。
     */
    public List<Map<String, Object>> visibleMenusFor(String role) {
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
        List<Map<String, Object>> out = new ArrayList<>();
        for (String rootId : roots) {
            Map<String, Object> node = buildBranch(rootId, nodes, childrenOf, allowed);
            if (node != null) out.add(node);
        }
        return out;
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
    public Menu create(String parentId, String name, String icon, String path, Integer sortOrder, Integer visible) {
        String n = normalizeName(name);
        String p = validateParent(parentId, null);
        ensurePathUnique(path, null);
        Menu m = new Menu();
        m.setParentId(p);
        m.setName(n);
        m.setIcon(trimOrNull(icon));
        m.setPath(trimOrNull(path));
        m.setSortOrder(sortOrder == null ? 0 : sortOrder);
        m.setVisible(visible == null || visible == 1 ? 1 : 0);
        m.setBuiltin(0);
        menuMapper.insert(m);
        log.info("[AUDIT] 新建菜单 id={} name={} path={}", m.getId(), n, m.getPath());
        return m;
    }

    @Transactional
    public void update(String id, String parentId, String name, String icon, String path,
                       Integer sortOrder, Integer visible) {
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
        menuMapper.updateById(m);
        log.info("[AUDIT] 编辑菜单 id={} name={}", id, m.getName());
    }

    /** 删除菜单：内置不可删；有子菜单不可删；级联清角色绑定 */
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
