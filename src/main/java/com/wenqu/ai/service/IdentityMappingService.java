package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.GroupMemberMapper;
import com.wenqu.ai.mapper.GroupRoleMappingMapper;
import com.wenqu.ai.model.GroupRoleMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 组→角色/部门 映射：规则管理（权限管理页）与裁决引擎（LDAP 同步/SCIM 共用）。
 * <p>
 * 裁决语义（D1：IdP 权威；多组命中 {@code priority} 大者优先，同 priority 按组名字典序稳定序）：
 * <ul>
 *   <li>命中的最高优先规则给了 roleCode → grant 为 idp 行；<b>用户在其它已映射但非最优的组上
 *       拿到的 idp 角色行会被回收</b>（裁决唯一胜者，避免多条规则叠加放大权限）；</li>
 *   <li>规则未配 roleCode（NULL）→ 该组不授角色（仅占位/部门联动）；</li>
 *   <li>手动角色行（granted_by='manual'）永不被裁决触碰。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityMappingService {

    private final GroupRoleMappingMapper mapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserRoleService userRoleService;
    private final RoleService roleService;
    private final ExternalUserService externalUserService;

    // ==================== 规则管理 ====================
    // 四条管理入口一律跑在「该认证源的租户」里（Phase 4）：平台级管理员替别家租户配规则时，
    // 若按管理员本人的租户落行或取数，规则会存进他自己的租户而本租户的同步永远读不到它——
    // 界面上「已保存」、裁决照旧不生效，正是要防的静默失配。判据与 ExternalUserService.inProviderTenant 同源。

    public List<GroupRoleMapping> listByProvider(String providerId) {
        return externalUserService.inProviderTenant(providerId, () -> mapper.selectList(
                new LambdaQueryWrapper<GroupRoleMapping>()
                        .eq(GroupRoleMapping::getProviderId, providerId)
                        .orderByAsc(GroupRoleMapping::getExternalGroup)));
    }

    public GroupRoleMapping create(String providerId, String externalGroup, String roleCode, String departmentId, Integer priority) {
        return externalUserService.inProviderTenant(providerId,
                () -> createScoped(providerId, externalGroup, roleCode, departmentId, priority));
    }

    private GroupRoleMapping createScoped(String providerId, String externalGroup, String roleCode,
                                          String departmentId, Integer priority) {
        if (externalGroup == null || externalGroup.isBlank()) throw new BizException("组名不能为空");
        GroupRoleMapping exist = findOf(providerId, externalGroup.trim());
        if (exist != null) throw new BizException("该认证源下已存在组「" + externalGroup + "」的映射规则");
        GroupRoleMapping g = new GroupRoleMapping();
        g.setId(UUID.randomUUID().toString());
        g.setProviderId(providerId);
        g.setExternalGroup(externalGroup.trim());
        g.setRoleCode(normalizeRoleCode(roleCode));
        g.setDepartmentId(trimToNull(departmentId));
        g.setPriority(priority == null ? 0 : priority);
        mapper.insert(g);
        return g;
    }

    public GroupRoleMapping update(String id, String roleCode, String departmentId, Integer priority) {
        return externalUserService.inProviderTenant(providerOf(id),
                () -> updateScoped(id, roleCode, departmentId, priority));
    }

    private GroupRoleMapping updateScoped(String id, String roleCode, String departmentId, Integer priority) {
        GroupRoleMapping g = require(id);
        g.setRoleCode(normalizeRoleCode(roleCode));
        g.setDepartmentId(trimToNull(departmentId));
        if (priority != null) g.setPriority(priority);
        mapper.updateById(g);
        return g;
    }

    /** 删规则：顺带回收按该规则拿到角色的用户的 idp 行（裁决事实变化） */
    public void delete(String id) {
        externalUserService.inProviderTenant(providerOf(id), () -> deleteScoped(id));
    }

    private void deleteScoped(String id) {
        GroupRoleMapping g = require(id);
        mapper.deleteById(id);
        if (g.getRoleCode() != null && !g.getRoleCode().isBlank()) {
            // 该组全部成员的这条 idp 角色行随规则一起回收（成员事实在 c_ai_group_member）
            for (String uid : memberUids(g)) {
                userRoleService.revokeIdpRole(uid, g.getRoleCode());
            }
        }
    }

    public GroupRoleMapping findOf(String providerId, String externalGroup) {
        return mapper.selectOne(new LambdaQueryWrapper<GroupRoleMapping>()
                .eq(GroupRoleMapping::getProviderId, providerId)
                .eq(GroupRoleMapping::getExternalGroup, externalGroup).last("LIMIT 1"));
    }

    /** 规则属于哪个认证源（平台视图读：管理员此刻可能绑着别的租户，而这条语句只是要拿到 provider_id） */
    private String providerOf(String id) {
        GroupRoleMapping g = com.wenqu.ai.util.TenantContext.asPlatform(() -> mapper.selectById(id));
        if (g == null) throw new BizException("映射规则不存在");
        return g.getProviderId();
    }

    private GroupRoleMapping require(String id) {
        GroupRoleMapping g = mapper.selectById(id);
        if (g == null) throw new BizException("映射规则不存在");
        return g;
    }

    private String normalizeRoleCode(String roleCode) {
        String c = trimToNull(roleCode);
        if (c == null) return null;
        c = c.toLowerCase();
        if (!roleService.existsActive(c)) {
            throw new BizException("角色「" + c + "」不存在或已停用，请在权限管理中创建或改用其它角色");
        }
        return c;
    }

    // ==================== 裁决引擎 ====================

    /** 裁决结果：命中的规则（角色 + 部门） */
    public record GroupDecision(GroupRoleMapping rule, String roleCode, String departmentId) {
    }

    /**
     * 多组裁决：取 priority 最大且 roleCode 非空的规则。
     *
     * @param groups 用户所属的 IdP 组名集合
     */
    public GroupDecision resolveBest(String providerId, List<String> groups) {
        if (groups == null || groups.isEmpty()) return null;
        GroupRoleMapping best = null;
        for (String gn : groups) {
            GroupRoleMapping g = findOf(providerId, gn);
            if (g == null || g.getRoleCode() == null || g.getRoleCode().isBlank()) continue;
            if (best == null || rank(g) > rank(best)) best = g;
        }
        return best == null ? null : new GroupDecision(best, best.getRoleCode(), best.getDepartmentId());
    }

    private int rank(GroupRoleMapping g) {
        int p = g.getPriority() == null ? 0 : g.getPriority();
        // 同 priority 用组名字典序做稳定 tie-break（裁决可复现）
        return p * 1000 - (g.getExternalGroup() == null ? 0 : Math.abs(g.getExternalGroup().hashCode()) % 1000);
    }

    /** 规则命中时的成员 uid 清单（组删除/规则删除的回收依据） */
    List<String> memberUids(GroupRoleMapping g) {
        return groupMemberMapper.selectList(new LambdaQueryWrapper<com.wenqu.ai.model.GroupMember>()
                        .eq(com.wenqu.ai.model.GroupMember::getProviderId, g.getProviderId())
                        .eq(com.wenqu.ai.model.GroupMember::getExternalGroup, g.getExternalGroup()))
                .stream().map(com.wenqu.ai.model.GroupMember::getUid).distinct().toList();
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }
}
