package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.mapper.UserRoleMapper;
import com.wenqu.ai.model.UserRole;
import jakarta.annotation.PostConstruct;
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
 * <p>
 * 多副本一致性：写操作（{@code setManualRoles}/{@code grant}/{@code revokeIdpRole}/{@code removeAll}）
 * 经 {@code ReplicaCoordination} 广播让其它副本当场失效对应 uid——SCIM/LDAP 自动授权与成员管理
 * 不再依赖 60s TTL 或重启才在别的副本生效。广播失败不阻断写操作（TTL 兜底）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoleService {
    /** 统一审计事件（Phase 3）：同一份格式串派生日志与结构化事件，不再两处抄 */
    private final AuditService audit;


    private final UserRoleMapper mapper;
    private final RoleService roleService;
    private final UserMapper userMapper;
    private final ReplicaCoordination replicaCoordination;

    /** 跨副本用户角色失效广播频道（payload.uid；本副本自己发的已被原语层滤掉） */
    public static final String CHANNEL_USER_ROLE_INVALIDATE = "rbac:user-role-invalidate";

    /** 角色 TTL 缓存：uid → (roles, expireAt) */
    private record Cached(List<String> roles, long expireAt) {
    }

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 60_000;

    /** 启动即订阅：别的副本改了某人的角色，本副本当场逐出该 uid（订阅失败只告警，退化为仅 TTL） */
    @PostConstruct
    void subscribeReplicaInvalidation() {
        try {
            replicaCoordination.listen(CHANNEL_USER_ROLE_INVALIDATE, (payload, from) -> {
                String uid = payload == null ? null : payload.getString("uid");
                if (uid == null || uid.isBlank()) return;
                cache.remove(uid);
                log.debug("[USER-ROLE] 收到副本 {} 的角色失效广播 uid={}，本地缓存已逐出", from, uid);
            });
        } catch (Exception e) {
            log.warn("[USER-ROLE] 订阅跨副本角色失效广播失败（退化为仅 TTL 兜底）: {}", e.getMessage());
        }
    }

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
            // C1：过期行当场不认（定时回收是 5 分钟粒度的清理，判定必须即刻生效——回收窗口内不给僵尸角色续命）
            rows.removeIf(r -> r.getExpireAt() != null && r.getExpireAt().isBefore(java.time.LocalDateTime.now()));
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
        setManualRoles(uid, codes, java.util.Map.of());
    }

    /**
     * 全量替换手动角色（C1 重载：带到期时刻）。{@code expires} 键=角色编码、值=到期时刻；
     * 未提及的角色行永久（expire_at=NULL）。临时授权回收三重保险：读侧即滤（loadRoles）、
     * 定时清行（{@link #sweepExpired}）、广播让各副本当场逐出缓存。
     */
    @Transactional
    public void setManualRoles(String uid, Collection<String> codes, Map<String, java.time.LocalDateTime> expires) {
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
            java.time.LocalDateTime expireAt = expires.get(code);
            if (expireAt != null) r.setExpireAt(expireAt);
            try {
                mapper.insert(r);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 同角色已有 idp 行：复合主键冲突，跳过（幂等，不重复授）
            }
        }
        invalidateAndBroadcast(uid);
        // C1：临时授权在审计里显形（哪天到期一清二楚）
        Map<String, java.time.LocalDateTime> tmp = new java.util.LinkedHashMap<>(expires);
        tmp.keySet().retainAll(normalized);
        audit.record("[AUDIT] 设置用户角色 uid={} roles={} expires={}", uid, normalized,
                tmp.isEmpty() ? "永久" : tmp.toString());
    }

    /**
     * 授予单角色（服务内部建档用：OIDC 默认角色/初始化超管；granted_by 可传 manual 或 idp）。
     * G9 修复（2026-11）：delete 不再不限 granted_by 通吃——只删同来源行；
     * 异源行冲突（复合主键）时按 D1 语义静默保位：IdP 权威不覆盖手动，手动授权遇到同点 idp 行
     * 则角色已在位（结果等价，来源保持 idp，成员页按只读口径展示）。
     */
    @Transactional
    public void grant(String uid, String roleCode, String grantedBy) {
        if (roleCode == null || roleCode.isBlank()) return;
        String src = grantedBy == null || grantedBy.isBlank() ? "manual" : grantedBy.trim().toLowerCase();
        mapper.delete(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getUid, uid)
                .eq(UserRole::getRoleCode, roleCode.trim().toLowerCase())
                .eq(UserRole::getGrantedBy, src));
        UserRole r = new UserRole();
        r.setUid(uid);
        r.setRoleCode(roleCode.trim().toLowerCase());
        r.setGrantedBy(src);
        try {
            mapper.insert(r);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 同点异源行已在位：按 D1 不覆盖（IdP 不吃手动、手动也不吃 IdP），角色结果等价
            log.info("[USER-ROLE] grant 遇同点异源行（uid={} code={} 本来源={}），按 D1 保位不覆盖", uid, roleCode, src);
        }
        invalidateAndBroadcast(uid);
        // G12：SCIM/LDAP/OIDC 的自动授权变更此前完全无留痕——补上（grantedBy 区分来源）
        audit.record("[AUDIT] 授予角色 uid={} code={} grantedBy={}", uid, r.getRoleCode(), r.getGrantedBy());
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
        invalidateAndBroadcast(uid);
        // G12 对偶：IdP 自动回收（组移除/组删除联动）同样补留痕
        audit.record("[AUDIT] 撤销 IdP 角色 uid={} code={}", uid, roleCode.trim().toLowerCase());
    }

    /** 删除用户的全部角色行（账号删除/离职回收时级联清理） */
    public void removeAll(String uid) {
        if (uid == null || uid.isBlank()) return;
        mapper.delete(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUid, uid));
        invalidateAndBroadcast(uid);
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
        Long n = mapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .in(UserRole::getRoleCode, adminCodes));
        return n != null && n <= 1;
    }

    /**
     * C1：定时回收已到期角色行（对齐既有 @Scheduled 基建，5 分钟节拍）。
     * 先取受影响 uid 再删：删完逐个广播逐出各副本缓存；审计记条数不记明细（明细在删之前已过期，无泄漏价值）。
     *
     * @return 本轮回收的 (uid, role) 行数
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public int sweepExpired() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        List<UserRole> expired = mapper.selectList(new LambdaQueryWrapper<UserRole>()
                .isNotNull(UserRole::getExpireAt).lt(UserRole::getExpireAt, now));
        if (expired.isEmpty()) return 0;
        Set<String> uids = new LinkedHashSet<>();
        for (UserRole r : expired) uids.add(r.getUid());
        mapper.delete(new LambdaQueryWrapper<UserRole>()
                .isNotNull(UserRole::getExpireAt).lt(UserRole::getExpireAt, now));
        for (String uid : uids) invalidateAndBroadcast(uid);
        audit.record("[AUDIT] 临时授权到期回收 rows={} uids={}", expired.size(), uids);
        return expired.size();
    }

    /** 即将过期清单（C1 提醒）：未来 days 天内到期的行（含已过期未清扫的） */
    public List<UserRole> expiringWithinDays(int days) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        return mapper.selectList(new LambdaQueryWrapper<UserRole>()
                .isNotNull(UserRole::getExpireAt)
                .lt(UserRole::getExpireAt, now.plusDays(Math.max(1, days)))
                .orderByAsc(UserRole::getExpireAt));
    }

    /** 某用户角色变更后失效缓存（仅本地；跨副本写路径请走 {@link #invalidateAndBroadcast}） */
    public void invalidate(String uid) {
        if (uid != null) cache.remove(uid);
    }

    /**
     * 角色写路径的统一收口：本地逐出 + 用户档案缓存顺手逐出（{@code setManualRoles} 有不经过
     * {@code OrgService.updateUserScoped} 的调用方——SCIM/目录同步/租户成员变更，那条路原来
     * 不逐档案缓存）+ 广播其它副本当场失效。广播失败只 debug（对方最迟 60s 由 TTL 兜底）。
     */
    private void invalidateAndBroadcast(String uid) {
        invalidate(uid);
        if (uid == null || uid.isBlank()) return;
        try {
            com.wenqu.ai.config.UserContextInterceptor.evict(uid);
        } catch (Exception ignored) {
            // 档案缓存逐出失败不影响角色缓存正确性
        }
        try {
            replicaCoordination.broadcast(CHANNEL_USER_ROLE_INVALIDATE, Map.of("uid", uid));
        } catch (Exception e) {
            log.debug("[USER-ROLE] 跨副本失效广播失败 uid={}（对方最迟 60s 由 TTL 兜底）: {}", uid, e.getMessage());
        }
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
