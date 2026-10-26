package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.mapper.UserRoleMapper;
import com.wenqu.ai.model.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户-角色多角色服务（RBAC 并集判定核心）。
 * <p>
 * 数据落在 {@code c_ai_user_role}（(uid, role_code) 复合主键），取代 {@code c_ai_user.role} 单值列。
 * 判定口径：
 * <ul>
 *   <li><b>管理员级</b>：任一角色为管理员级（{@link RoleService#isAdminCode}，含 admin_flag=1）即管理员——并集放大权限，无角色取小；</li>
 *   <li><b>主角色</b>（JWT claim / 展示 / 日志用）：首个管理员级角色，否则首个角色，否则 {@code user}——
 *       鉴权<b>不</b>依赖它，只读端点与展示用；</li>
 *   <li><b>无角色行</b>：视为 {@code user}（与单角色列时代「空=普通用户」口径一致，fail-safe）。</li>
 * </ul>
 * 缓存：60s 进程内 TTL（与 {@code UserContextInterceptor} 用户档案缓存同口径），角色变更最迟 60s 生效；
 * 本服务内所有写操作即时失效对应 uid。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoleService {

    private final UserRoleMapper mapper;
    private final RoleService roleService;
    private final UserMapper userMapper;

    /** 角色 TTL 缓存：uid → (roles, expireAt) */
    private record Cached(List<String> roles, long expireAt) {
    }

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 60_000;

    /** 用户的角色集合（无角色行 → ["user"]） */
    public List<String> rolesOf(String uid) {
        if (uid == null || uid.isBlank() || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(uid)) {
            return List.of("user");
        }
        Cached c = cache.get(uid);
        if (c != null && c.expireAt() > System.currentTimeMillis()) return c.roles();
        List<String> roles = loadRoles(uid);
        cache.put(uid, new Cached(roles, System.currentTimeMillis() + TTL_MS));
        return roles;
    }

    private List<String> loadRoles(String uid) {
        try {
            List<UserRole> rows = mapper.selectList(new LambdaQueryWrapper<UserRole>()
                    .eq(UserRole::getUid, uid));
            if (rows.isEmpty()) return List.of("user");
            // 稳定序：管理员级在前（主角色裁决），同级按授予时间
            Map<Boolean, Set<String>> split = new LinkedHashMap<>();
            split.put(Boolean.TRUE, new LinkedHashSet<>());
            split.put(Boolean.FALSE, new LinkedHashSet<>());
            for (UserRole r : rows) {
                split.get(roleService.isAdminCode(r.getRoleCode())).add(r.getRoleCode());
            }
            List<String> out = new ArrayList<>(split.get(Boolean.TRUE));
            out.addAll(split.get(Boolean.FALSE));
            return List.copyOf(out);
        } catch (Exception e) {
            // 表未就绪/查询异常：按普通用户降级，不放权（fail-closed），但留痕
            log.warn("[USER-ROLE] 角色查询失败 uid={}（按 user 降级）：{}", uid, e.getMessage());
            return List.of("user");
        }
    }

    /** 是否管理员级（任一角色命中） */
    public boolean isAdmin(String uid) {
        for (String r : rolesOf(uid)) {
            if (roleService.isAdminCode(r)) return true;
        }
        return false;
    }

    /** 主角色：首个管理员级角色，否则首个，否则 user（JWT claim / 展示 / 审计日志用） */
    public String primaryRole(String uid) {
        List<String> roles = rolesOf(uid);
        return roles.isEmpty() ? "user" : roles.get(0);
    }

    /**
     * 全量替换某用户的手动角色（成员管理编辑口径）。
     * <p>仅覆盖 {@code granted_by='manual'} 行；{@code idp} 行（组映射/目录同步授予）不动——
     * D1 决策：IdP 权威，手动角色不被静默覆盖，反之 IdP 行也不被手动编辑清掉。
     *
     * @param codes 角色编码集（须存在于角色表且启用；空集合=清空手动角色，回落 user）
     */
    @Transactional
    public void setManualRoles(String uid, Collection<String> codes) {
        if (uid == null || uid.isBlank()) throw new BizException("用户不能为空");
        Set<String> normalized = new LinkedHashSet<>();
        if (codes != null) {
            for (String c : codes) {
                if (c == null || c.isBlank()) continue;
                String code = c.trim().toLowerCase();
                if (!roleService.existsActive(code)) {
                    throw new BizException("角色「" + code + "」不存在或已停用，请在权限管理中创建或改用其它角色");
                }
                normalized.add(code);
            }
        }
        mapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUid, uid).eq(UserRole::getGrantedBy, "manual"));
        for (String code : normalized) {
            UserRole r = new UserRole();
            r.setUid(uid);
            r.setRoleCode(code);
            r.setGrantedBy("manual");
            try {
                mapper.insert(r);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 同角色已有 idp 行：复合主键冲突，跳过（幂等，不重复授）
            }
        }
        invalidate(uid);
        log.info("[AUDIT] 设置用户角色 uid={} roles={}", uid, normalized);
    }

    /** 授予单角色（服务内部建档用：OIDC 默认角色/初始化超管；granted_by 可传 manual 或 idp） */
    @Transactional
    public void grant(String uid, String roleCode, String grantedBy) {
        if (roleCode == null || roleCode.isBlank()) return;
        mapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUid, uid).eq(UserRole::getRoleCode, roleCode));
        UserRole r = new UserRole();
        r.setUid(uid);
        r.setRoleCode(roleCode.trim().toLowerCase());
        r.setGrantedBy(grantedBy == null || grantedBy.isBlank() ? "manual" : grantedBy);
        mapper.insert(r);
        invalidate(uid);
    }

    /**
     * 撤销某用户的一个 IdP 授予角色（组移除/组删除联动）：只删 {@code granted_by='idp'} 行，
     * 手动行不动——D1 决策的对偶：IdP 权威不覆盖手动，手动也不挡 IdP 回收。
     */
    @Transactional
    public void revokeIdpRole(String uid, String roleCode) {
        if (uid == null || uid.isBlank() || roleCode == null || roleCode.isBlank()) return;
        mapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUid, uid)
                .eq(UserRole::getRoleCode, roleCode.trim().toLowerCase())
                .eq(UserRole::getGrantedBy, "idp"));
        invalidate(uid);
    }

    /** 删除用户的全部角色行（账号删除/离职回收时级联清理） */
    public void removeAll(String uid) {
        if (uid == null || uid.isBlank()) return;
        mapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUid, uid));
        invalidate(uid);
    }

    /** 持有某角色的用户数（删除角色的守门依据） */
    public long countOfRole(String roleCode) {
        Long n = mapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getRoleCode, roleCode));
        return n == null ? 0 : n;
    }

    /** 持有任一给定角色的 uid 清单（按角色圈定收件人等场景；去重） */
    public List<String> uidsWithAnyRole(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return List.of();
        List<UserRole> rows = mapper.selectList(new LambdaQueryWrapper<UserRole>()
                .in(UserRole::getRoleCode, roleCodes));
        if (rows.isEmpty()) return List.of();
        Set<String> uids = new LinkedHashSet<>();
        for (UserRole r : rows) uids.add(r.getUid());
        return List.copyOf(uids);
    }

    /**
     * 是否仅剩最后一名管理员级账号（删除/降级守门）：
     * 持有任一管理员级角色（含 admin_flag=1 自定义）的用户数 ≤ 1。
     */
    public boolean isLastAdminAccount() {
        List<String> adminCodes = roleService.adminCodes();
        if (adminCodes.isEmpty()) return false;
        Long modern = mapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .in(UserRole::getRoleCode, adminCodes));
        long total = modern == null ? 0 : modern;
        if (total > 1) return false;
        // 兜底：迁移未跑/失败的存量库——旧单角色列是管理员级、且在新表无任何角色行的账号也计入，
        // 避免把唯一管理员当成可删（迁移完成后该查询恒 0，不构成热路径负担）
        try {
            Long legacy = userMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.wenqu.ai.model.User>()
                    .in(com.wenqu.ai.model.User::getRole, adminCodes)
                    .notExists("SELECT 1 FROM c_ai_user_role ur WHERE ur.uid = c_ai_user.uid"));
            total += legacy == null ? 0 : legacy;
        } catch (Exception ignored) {
            // 旧列可能已随迁移批次删除：忽略，以新表口径为准
        }
        return total <= 1;
    }

    /** 某用户角色变更后失效缓存 */
    public void invalidate(String uid) {
        if (uid != null) cache.remove(uid);
    }

    /** 全量失效（角色表结构变化等少见场景） */
    public void invalidateAll() {
        cache.clear();
    }

    /** 供判定工具使用：角色集合中任一为管理员级 */
    public boolean anyAdmin(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) return false;
        for (String r : roles) {
            if (roleService.isAdminCode(r)) return true;
        }
        return false;
    }

    /** 去重工具（保持顺序） */
    public static List<String> distinct(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) return List.of();
        Set<String> s = new LinkedHashSet<>();
        for (String r : roles) {
            if (r != null && !r.isBlank()) s.add(r);
        }
        return List.copyOf(s);
    }
}
