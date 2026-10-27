package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.UserIdentityMapper;
import com.wenqu.ai.model.UserIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 统一身份服务：本地账号 ↔ 外部身份（OIDC sub / SAML NameID / SCIM id / LDAP dn）的绑定关系维护。
 * <p>
 * 数据落在 {@code c_ai_user_identity}（(provider_id, external_id) 复合唯一），取代
 * {@code c_ai_user.oidc_sub/oidc_provider} 两列。一个账号可绑多个认证源的身份；
 * 同一认证源下同一外部身份只能绑一个账号（防冒用，唯一索引兜底）。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityService {

    private final UserIdentityMapper mapper;

    /** 外部身份 → 本地 uid；未绑定返回 null */
    public String uidOfExternal(String providerId, String externalId) {
        if (providerId == null || providerId.isBlank() || externalId == null || externalId.isBlank()) return null;
        UserIdentity i = mapper.selectOne(new LambdaQueryWrapper<UserIdentity>()
                .eq(UserIdentity::getProviderId, providerId)
                .eq(UserIdentity::getExternalId, externalId)
                .last("LIMIT 1"));
        return i == null ? null : i.getUid();
    }

    /** 某账号在某认证源下的绑定（可空） */
    public UserIdentity findOf(String uid, String providerId) {
        if (uid == null || uid.isBlank() || providerId == null || providerId.isBlank()) return null;
        return mapper.selectOne(new LambdaQueryWrapper<UserIdentity>()
                .eq(UserIdentity::getUid, uid)
                .eq(UserIdentity::getProviderId, providerId)
                .last("LIMIT 1"));
    }

    /** 某账号的全部外部身份绑定 */
    public List<UserIdentity> listOf(String uid) {
        if (uid == null || uid.isBlank()) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<UserIdentity>()
                .eq(UserIdentity::getUid, uid));
    }

    /** 某认证源下的全部绑定（LDAP 离员检测：本地绑定 ∖ 远端现存） */
    public List<UserIdentity> listOfProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<UserIdentity>()
                .eq(UserIdentity::getProviderId, providerId));
    }

    /** 绑定外部身份（幂等：已存在同 (providerId, externalId) 绑定时不重复建） */
    public void bind(String uid, String providerId, String externalId) {
        UserIdentity exist = mapper.selectOne(new LambdaQueryWrapper<UserIdentity>()
                .eq(UserIdentity::getProviderId, providerId)
                .eq(UserIdentity::getExternalId, externalId)
                .last("LIMIT 1"));
        if (exist != null) {
            if (uid.equals(exist.getUid())) return;
            throw new com.wenqu.ai.common.BizException("该外部身份已绑定其他账号，请联系管理员处理绑定冲突");
        }
        UserIdentity i = new UserIdentity();
        i.setId(UUID.randomUUID().toString());
        i.setUid(uid);
        i.setProviderId(providerId);
        i.setExternalId(externalId);
        try {
            mapper.insert(i);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发绑定同 (provider, external)：唯一索引兜底，按幂等处理
            log.info("[IDENTITY] 并发绑定冲突（唯一索引兜底）uid={} provider={} external={}",
                    uid, providerId, externalId);
        }
    }

    /** 解绑某账号的全部外部身份（离职回收/手动解绑共用） */
    public void unbindAll(String uid) {
        if (uid == null || uid.isBlank()) return;
        mapper.delete(new LambdaQueryWrapper<UserIdentity>().eq(UserIdentity::getUid, uid));
    }

    /** 解绑某账号在某认证源的绑定（逐源解绑）；绑定不存在按幂等处理 */
    public void unbind(String uid, String providerId) {
        if (uid == null || uid.isBlank() || providerId == null || providerId.isBlank()) return;
        mapper.delete(new LambdaQueryWrapper<UserIdentity>()
                .eq(UserIdentity::getUid, uid)
                .eq(UserIdentity::getProviderId, providerId));
    }

    /** 更新身份绑定行的属性快照/同步时间（SCIM 属性快照用） */
    public void updateAttrs(UserIdentity identity) {
        if (identity == null || identity.getId() == null) return;
        mapper.updateById(identity);
    }

    /** 某认证源下的绑定账号数（删除认证源的守门依据） */
    public long boundCount(String providerId) {
        Long n = mapper.selectCount(new LambdaQueryWrapper<UserIdentity>()
                .eq(UserIdentity::getProviderId, providerId));
        return n == null ? 0 : n;
    }
}
