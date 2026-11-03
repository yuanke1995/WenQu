package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.ServiceAccountMapper;
import com.wenqu.ai.model.ServiceAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 服务账号服务（批次 C3）：CRUD + 令牌签发/校验 + 到期回收。
 * <p>
 * 令牌形态 {@code svcq-<32 hex>}：明文只在创建响应里出现一次，库里只存 SHA-256——
 * 与「密钥不落明文」的平台口径一致，泄露处置 = 停用 + 重建（不提供解密回显）。
 * 校验热路径（每请求一次）走内存缓存（60s TTL + 写失效 + 广播，与角色缓存同口径）。
 * <p>
 * 角色绑定复用 {@link UserRoleService}（uid = {@code svc:{id}}，granted_by='service'）：
 * 授权点判定、deny、到期回收、跨副本失效全部原样生效。服务账号不是用户：
 * 不在 {@code c_ai_user} 表，登录链路天然不可达。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceAccountService {

    private final ServiceAccountMapper mapper;
    private final UserRoleService userRoleService;
    private final AuditService audit;
    private final ReplicaCoordination replicaCoordination;

    /** 服务账号在用户-角色表里的 uid 前缀（与真实用户 uid 空间隔离） */
    public static final String UID_PREFIX = "svc:";
    public static final String GRANTED_BY = "service";

    /** 令牌 TTL 缓存：tokenHash → (account, expireAt)；写路径失效 + 广播 */
    private record Cached(ServiceAccount account, long expireAt) {
    }

    private final java.util.concurrent.ConcurrentHashMap<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TTL_MS = 60_000;

    /** 跨副本失效广播频道（全量失效即可，账号表极小） */
    public static final String CHANNEL_SVC_INVALIDATE = "rbac:service-account-invalidate";

    @jakarta.annotation.PostConstruct
    void subscribe() {
        try {
            replicaCoordination.listen(CHANNEL_SVC_INVALIDATE, (payload, from) -> {
                cache.clear();
                log.debug("[SVC-ACCOUNT] 收到副本 {} 的服务账号失效广播，本地缓存已清", from);
            });
        } catch (Exception e) {
            log.warn("[SVC-ACCOUNT] 订阅跨副本失效广播失败（退化为仅 TTL 兜底）: {}", e.getMessage());
        }
    }

    // ==================== 校验（拦截器热路径） ====================

    /** 是否本服务的令牌形态（拦截器快速分流用，避免给每请求做哈希） */
    public static boolean isServiceToken(String token) {
        return token != null && token.startsWith("svcq-");
    }

    /**
     * 校验令牌并返回账号（状态/到期双重门；失败返回 null，调用方统一 401，不区分「不存在/停用/过期」防枚举）。
     * 缓存 60s + 写失效 + 跨副本广播，与角色缓存同口径。
     */
    public ServiceAccount verify(String token) {
        if (!isServiceToken(token)) return null;
        String hash = sha256(token);
        Cached c = cache.get(hash);
        long now = System.currentTimeMillis();
        if (c != null && c.expireAt() > now) return usable(c.account());
        ServiceAccount acc;
        try {
            acc = mapper.selectOne(new LambdaQueryWrapper<ServiceAccount>()
                    .eq(ServiceAccount::getTokenHash, hash));
        } catch (Exception e) {
            log.warn("[SVC-ACCOUNT] 令牌查询失败（按未认证处理）: {}", e.getMessage());
            return null;
        }
        if (acc == null) return null;
        cache.put(hash, new Cached(acc, now + TTL_MS));
        return usable(acc);
    }

    private static ServiceAccount usable(ServiceAccount acc) {
        if (acc == null) return null;
        if (acc.getStatus() == null || acc.getStatus() != 1) return null;
        if (acc.getExpireAt() != null && acc.getExpireAt().isBefore(LocalDateTime.now())) return null;
        return acc;
    }

    /** 服务账号在判定侧的 uid（c_ai_user_role 绑定键） */
    public static String uidOf(ServiceAccount acc) {
        return UID_PREFIX + acc.getId();
    }

    // ==================== CRUD ====================

    public List<ServiceAccount> list() {
        return mapper.selectList(new LambdaQueryWrapper<ServiceAccount>()
                .orderByDesc(ServiceAccount::getCreateTime));
    }

    /**
     * 创建并签发令牌（明文只此一次）；角色绑定走 {@link UserRoleService#grant}（granted_by='service'）。
     *
     * @return [account, token]——token 为明文，调用方必须当场返回给管理员
     */
    @Transactional
    public Object[] create(String name, String description, List<String> roleCodes, LocalDateTime expireAt) {
        if (name == null || name.isBlank()) throw new BizException("名称不能为空");
        String token = "svcq-" + UUID.randomUUID().toString().replace("-", "");
        ServiceAccount acc = new ServiceAccount();
        acc.setName(name.trim());
        acc.setDescription(description == null || description.isBlank() ? null : description.trim());
        acc.setTokenHash(sha256(token));
        acc.setStatus(1);
        acc.setExpireAt(expireAt);
        acc.setCreatedBy(currentUid());
        mapper.insert(acc);
        if (roleCodes != null) {
            for (String code : roleCodes) {
                userRoleService.grant(uidOf(acc), code, GRANTED_BY);
            }
        }
        audit.record("[AUDIT] 创建服务账号 name={} roles={} expireAt={}", acc.getName(),
                roleCodes, expireAt == null ? "永久" : expireAt.toString());
        return new Object[]{acc, token};
    }

    @Transactional
    public void update(String id, String name, String description, Integer status, LocalDateTime expireAt) {
        ServiceAccount acc = mapper.selectById(id);
        if (acc == null) throw new BizException("服务账号不存在");
        if (name != null && !name.isBlank()) acc.setName(name.trim());
        if (description != null) acc.setDescription(description.isBlank() ? null : description.trim());
        if (status != null) {
            if (status != 0 && status != 1) throw new BizException("非法状态");
            acc.setStatus(status);
        }
        if (expireAt != null) acc.setExpireAt(expireAt);
        mapper.updateById(acc);
        invalidate(acc);
        audit.record("[AUDIT] 编辑服务账号 id={} name={} status={} expireAt={}", id, acc.getName(),
                acc.getStatus(), expireAt == null ? "不变" : expireAt.toString());
    }

    /** 轮换令牌：旧明文立刻作废，新明文只此一次返回 */
    @Transactional
    public String rotateToken(String id) {
        ServiceAccount acc = mapper.selectById(id);
        if (acc == null) throw new BizException("服务账号不存在");
        String oldHash = acc.getTokenHash();
        String token = "svcq-" + UUID.randomUUID().toString().replace("-", "");
        acc.setTokenHash(sha256(token));
        mapper.updateById(acc);
        // 旧哈希的缓存条目必须按【旧哈希】逐出——按新哈希删等于没删，旧令牌在 TTL 内继续有效
        cache.remove(oldHash);
        cache.remove(acc.getTokenHash());
        try {
            replicaCoordination.broadcast(CHANNEL_SVC_INVALIDATE, java.util.Map.of("id", acc.getId()));
        } catch (Exception e) {
            log.debug("[SVC-ACCOUNT] 轮换广播失败（对方最迟 60s 由 TTL 兜底）: {}", e.getMessage());
        }
        audit.record("[AUDIT] 轮换服务账号令牌 id={} name={}", id, acc.getName());
        return token;
    }

    /** 删除账号（角色绑定行级联清理——c_ai_user_role 里 uid=svc:{id} 的行不清理会在重建同名账号时继承授权） */
    @Transactional
    public void delete(String id) {
        ServiceAccount acc = mapper.selectById(id);
        if (acc == null) throw new BizException("服务账号不存在");
        mapper.deleteById(id);
        userRoleService.removeAll(uidOf(acc));
        invalidate(acc);
        audit.record("[AUDIT] 删除服务账号 id={} name={}", id, acc.getName());
    }

    private void invalidate(ServiceAccount acc) {
        if (acc == null || acc.getTokenHash() == null) return;
        cache.remove(acc.getTokenHash());
        try {
            replicaCoordination.broadcast(CHANNEL_SVC_INVALIDATE, java.util.Map.of("id", acc.getId()));
        } catch (Exception e) {
            log.debug("[SVC-ACCOUNT] 广播失效失败（对方最迟 60s 由 TTL 兜底）: {}", e.getMessage());
        }
    }

    private static String currentUid() {
        try {
            String uid = com.wenqu.ai.util.RequestUser.uid();
            return uid == null || uid.isBlank() || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(uid) ? null : uid;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
