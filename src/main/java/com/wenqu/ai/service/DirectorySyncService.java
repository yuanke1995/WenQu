package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.IdentityProviderMapper;
import com.wenqu.ai.mapper.SyncJobLogMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.GroupMember;
import com.wenqu.ai.mapper.GroupMemberMapper;
import com.wenqu.ai.model.IdentityProvider;
import com.wenqu.ai.model.SyncJobLog;
import com.wenqu.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * LDAP/AD 目录定时同步（diff → 建/禁/映射联动），SCIM 推送之外的另一条目录数据源。
 * <p>
 * 调度：Spring @Scheduled（60s 一跳，检查每个 enabled LDAP provider 的
 * {@code syncIntervalMinutes} 到点没有；到点即跑），另提供管理员手动触发端点。
 * <p>
 * 每轮 diff（externalId=dn 绑定 {@code c_ai_user_identity}）：
 * <ol>
 *   <li>远端有、本地无 → 建号（不设密码；default_role grant idp 行；入组落成员事实源）；</li>
 *   <li>远端有、本地有 → 同步显示名/状态；组变更按 {@link IdentityMappingService#resolveBest}
 *       裁决（priority 最大者胜，唯一胜者 grant，其余 idp 角色行回收——手动行永不动）；</li>
 *   <li>本地启用但远端消失 → 禁用（D4：disable 可恢复；最后管理员防自锁同成员管理口径）；</li>
 *   <li>一轮一条 {@code c_ai_sync_job_log}（source_type=ldap）。</li>
 * </ol>
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectorySyncService {

    private final IdentityProviderMapper providerMapper;
    private final LdapDirectoryService ldap;
    private final UserMapper userMapper;
    private final IdentityService identityService;
    private final ExternalUserService externalUserService;
    private final UserRoleService userRoleService;
    private final IdentityMappingService mappingService;
    private final GroupMemberMapper groupMemberMapper;
    private final SyncJobLogMapper syncLogMapper;

    /** 上次同步时间（providerId → epoch ms），进程内存即可（重启后重跑一轮 diff 无副作用） */
    private final Map<String, Long> lastRunAt = new HashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        log.info("[LDAP] 目录同步调度已就绪（每分钟检查 provider 的 syncIntervalMinutes）");
    }

    /** 每分钟跳一次：到点的 provider 逐个同步；单个失败不影响其他 provider */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void tick() {
        for (IdentityProvider p : enabledLdap()) {
            LdapDirectoryService.LdapConfig c;
            try {
                c = ldap.config(p);
            } catch (Exception e) {
                log.warn("[LDAP] config 解析失败，跳过 provider={}: {}", p.getId(), e.getMessage());
                continue;
            }
            long intervalMs = Math.max(5, c.syncIntervalMinutes()) * 60_000L;
            long last = lastRunAt.getOrDefault(p.getId(), 0L);
            if (System.currentTimeMillis() - last < intervalMs) continue;
            lastRunAt.put(p.getId(), System.currentTimeMillis());
            try {
                syncProvider(p);
            } catch (Exception e) {
                log.error("[LDAP] 定时同步失败 provider={}", p.getId(), e);
            }
        }
    }

    /** 管理员手动触发（「立即同步」按钮），同步完成后回日志摘要 */
    public SyncJobLog syncNow(IdentityProvider p) {
        lastRunAt.put(p.getId(), System.currentTimeMillis());
        return syncProvider(p);
    }

    // ==================== diff 同步 ====================

    private SyncJobLog syncProvider(IdentityProvider p) {
        int created = 0, updated = 0, disabled = 0;
        List<String> errors = new ArrayList<>();
        List<LdapDirectoryService.LdapUser> remote;
        try {
            remote = ldap.fetchUsers(p);
        } catch (Exception e) {
            return logResult(p, 0, 0, 0, "failed", "拉取目录失败: " + e.getMessage());
        }

        Set<String> seenExternalIds = new HashSet<>();
        for (LdapDirectoryService.LdapUser lu : remote) {
            seenExternalIds.add(lu.externalId());
            try {
                User local = findByExternal(p.getId(), lu.externalId());
                if (local == null) {
                    local = createLocalUser(p, lu);
                    created++;
                } else {
                    if (lu.displayName() != null && !lu.displayName().isBlank()
                            && !lu.displayName().equals(local.getUsername())) {
                        local.setUsername(lu.displayName());
                        userMapper.updateById(local);
                        updated++;
                    }
                    if (local.getStatus() != null && local.getStatus() == 0) {
                        local.setStatus(1); // 远端仍在 → 恢复启用（回收上一轮的离员禁用）
                        userMapper.updateById(local);
                        updated++;
                    }
                }
                applyGroups(p.getId(), local.getUid(), lu.groups());
            } catch (Exception e) {
                errors.add(lu.uid() + ": " + e.getMessage());
                log.warn("[LDAP] 用户同步失败 uid={} provider={}: {}", lu.uid(), p.getId(), e.getMessage());
            }
        }

        // 本地启用但目录里已消失 → 禁用（D4 disable；防自锁：最后管理员跳过并记明细）
        for (var binding : identityBindingsOf(p.getId())) {
            if (seenExternalIds.contains(binding.getExternalId())) continue;
            User u = userMapper.selectById(binding.getUid());
            if (u == null || (u.getStatus() != null && u.getStatus() == 0)) continue;
            if (userRoleService.isAdmin(u.getUid()) && userRoleService.isLastAdminAccount()) {
                errors.add("离员禁用跳过（最后管理员防自锁）: " + u.getUid());
                continue;
            }
            u.setStatus(0);
            userMapper.updateById(u);
            disabled++;
            log.info("[AUDIT] LDAP 离员禁用 provider={} uid={}", p.getId(), u.getUid());
        }

        String status = errors.isEmpty() ? "success" : (created + updated + disabled > 0 ? "partial" : "failed");
        String detail = "users=" + remote.size()
                + (errors.isEmpty() ? "" : "; errors: " + String.join("; ", errors));
        return logResult(p, created, updated, disabled, status, detail);
    }

    /** 组变更 → 裁决引擎（唯一胜者 grant；本 provider 其它组规则拿到的 idp 角色回收）+ 成员事实源对齐 */
    private void applyGroups(String providerId, String uid, List<String> groups) {
        // 成员事实源对齐（c_ai_group_member）：远端组 → 落行/清行
        Set<String> current = new HashSet<>();
        for (var gm : groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getProviderId, providerId).eq(GroupMember::getUid, uid))) {
            current.add(gm.getExternalGroup());
            if (groups == null || !groups.contains(gm.getExternalGroup())) {
                groupMemberMapper.deleteById(gm.getId());
            }
        }
        if (groups != null) {
            for (String gn : groups) {
                if (current.contains(gn)) continue;
                GroupMember gm = new GroupMember();
                gm.setId(UUID.randomUUID().toString());
                gm.setProviderId(providerId);
                gm.setExternalGroup(gn);
                gm.setUid(uid);
                try {
                    groupMemberMapper.insert(gm);
                } catch (org.springframework.dao.DuplicateKeyException ignored) {
                }
            }
        }
        // 裁决：唯一胜者 grant；本 provider 其他已映射组的 idp 角色行回收（手动行不动）
        IdentityMappingService.GroupDecision d = mappingService.resolveBest(providerId, groups);
        Set<String> keep = new HashSet<>();
        if (d != null) {
            userRoleService.grant(uid, d.roleCode(), "idp");
            keep.add(d.roleCode().toLowerCase());
            if (d.departmentId() != null && !d.departmentId().isBlank()) {
                User u = userMapper.selectById(uid);
                if (u != null && !d.departmentId().equals(u.getDepartmentId())) {
                    u.setDepartmentId(d.departmentId());
                    userMapper.updateById(u);
                }
            }
        }
        for (var rule : mappingService.listByProvider(providerId)) {
            String rc = rule.getRoleCode();
            if (rc == null || rc.isBlank() || keep.contains(rc.toLowerCase())) continue;
            userRoleService.revokeIdpRole(uid, rc);
        }
    }

    private User createLocalUser(IdentityProvider p, LdapDirectoryService.LdapUser lu) {
        String uid = externalUserService.ensureUniqueUid(
                externalUserService.sanitizeUid("ldap", lu.uid()), lu.externalId());
        String username = externalUserService.ensureUniqueUsername(
                lu.displayName() == null ? lu.uid() : lu.displayName(), lu.externalId());
        User u = new User();
        u.setUid(uid);
        u.setUsername(username);
        u.setStatus(1);
        u.setLoginFailCount(0);
        userMapper.insert(u);
        identityService.bind(uid, p.getId(), lu.externalId());
        String defaultRole = p.getDefaultRole();
        if (defaultRole != null && !defaultRole.isBlank()) {
            try {
                userRoleService.grant(uid, defaultRole, "idp");
            } catch (Exception e) {
                log.warn("[LDAP] 默认角色授予失败 uid={} role={}: {}", uid, defaultRole, e.getMessage());
            }
        }
        log.info("[AUDIT] LDAP 自动建档 provider={} uid={} dn={}", p.getId(), uid, lu.externalId());
        return u;
    }

    private User findByExternal(String providerId, String externalId) {
        String uid = identityService.uidOfExternal(providerId, externalId);
        return uid == null ? null : userMapper.selectById(uid);
    }

    private List<com.wenqu.ai.model.UserIdentity> identityBindingsOf(String providerId) {
        return identityService.listOfProvider(providerId);
    }

    private List<IdentityProvider> enabledLdap() {
        return providerMapper.selectList(new LambdaQueryWrapper<IdentityProvider>()
                .eq(IdentityProvider::getType, "ldap")
                .eq(IdentityProvider::getEnabled, 1));
    }

    private SyncJobLog logResult(IdentityProvider p, int created, int updated, int disabled,
                                 String status, String detail) {
        SyncJobLog l = new SyncJobLog();
        l.setId(UUID.randomUUID().toString());
        l.setSourceType("ldap");
        l.setSourceId(p.getId());
        l.setUsersCreated(created);
        l.setUsersUpdated(updated);
        l.setUsersDisabled(disabled);
        l.setStatus(status);
        l.setDetail(detail == null || detail.length() <= 2000 ? detail : detail.substring(0, 2000));
        try {
            syncLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[LDAP] 同步日志写入失败 provider={}: {}", p.getId(), e.getMessage());
        }
        log.info("[AUDIT] LDAP 同步完成 provider={} status={} created={} updated={} disabled={}",
                p.getId(), status, created, updated, disabled);
        return l;
    }
}
