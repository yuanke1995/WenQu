package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.TenantInviteMapper;
import com.wenqu.ai.mapper.TenantMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Tenant;
import com.wenqu.ai.model.TenantInvite;
import com.wenqu.ai.model.User;
import com.wenqu.ai.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 租户邀请。
 *
 * <p>一次性短链：管理员生成链接 → 对方匿名打开 → 填账号密码即落本租户。
 * token 只存 SHA-256，明文只在生成时返回一次（与对外 API Key 同一口径）。
 *
 * <p>并发名额用「先 CAS 占名额、创建失败再归还」：两笔同时点同一条一次性链接，
 * 只有一笔能占到 {@code used_count}，另一笔当场收到「已用尽」，不会出现两个账号都建成。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantInviteService {

    /** 登录账号：与成员管理同口径的宽松集合（不含空白与路径字符） */
    private static final Pattern UID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantInviteMapper inviteMapper;
    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final TenantService tenantService;
    private final UserRoleService userRoleService;
    private final AuthService authService;
    private final AuditService audit;

    /**
     * 生成邀请（租户管理员）。
     * <p>返回 {@code url} 明文 token 仅此一次；列表接口只回 prefix。
     */
    public Map<String, Object> create(String roleIds, String deptId, String email,
                                      Integer maxUses, Integer expireDays) {
        String tenant = TenantContext.current();
        if (tenant == null || tenant.isBlank()) throw new BizException("未绑定租户，无法生成邀请");
        Tenant t = tenantMapper.selectById(tenant);
        if (t == null) throw new BizException("租户不存在：" + tenant);

        int uses = maxUses == null ? 1 : maxUses;
        if (uses < 0) throw new BizException("可用次数不能为负数（0 表示不限）");
        if (uses > 1000) throw new BizException("可用次数上限 1000");
        int days = expireDays == null ? 7 : expireDays;
        if (days < 1 || days > 30) throw new BizException("有效期需在 1-30 天之间");

        List<String> roles = splitRoles(roleIds);
        String normalizedEmail = email == null || email.isBlank() ? null : email.trim().toLowerCase();
        if (normalizedEmail != null && !normalizedEmail.contains("@")) throw new BizException("限定邮箱格式不正确");

        String token = newToken();
        TenantInvite inv = new TenantInvite();
        inv.setTenantId(tenant);
        inv.setId(UUID.randomUUID().toString().replace("-", ""));
        inv.setTokenHash(sha256(token));
        inv.setTokenPrefix(token.substring(0, 8));
        inv.setRoleIds(roles.isEmpty() ? null : String.join(",", roles));
        inv.setDeptId(deptId == null || deptId.isBlank() ? null : deptId.trim());
        inv.setEmail(normalizedEmail);
        inv.setMaxUses(uses);
        inv.setUsedCount(0);
        inv.setExpiresAt(LocalDateTime.now().plusDays(days));
        inv.setStatus("active");
        inv.setCreatedBy(com.wenqu.ai.util.RequestUser.uid());
        inviteMapper.insert(inv);

        audit.record("[AUDIT] 生成租户邀请 租户={} 角色={} 次数={} 有效期={}天", tenant,
                inv.getRoleIds() == null ? "user" : inv.getRoleIds(), uses == 0 ? "不限" : uses, days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", inv.getId());
        out.put("path", "/join/" + token);
        out.put("tokenPrefix", inv.getTokenPrefix());
        out.put("expiresAt", inv.getExpiresAt().toString());
        out.put("maxUses", uses);
        return out;
    }

    /** 本租户的在途邀请（不返回 token 明文） */
    public List<Map<String, Object>> list() {
        List<TenantInvite> rows = inviteMapper.selectList(new LambdaQueryWrapper<TenantInvite>()
                .orderByDesc(TenantInvite::getCreateTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TenantInvite r : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", r.getId());
            one.put("tokenPrefix", r.getTokenPrefix());
            one.put("roleIds", r.getRoleIds() == null ? "" : r.getRoleIds());
            one.put("deptId", r.getDeptId() == null ? "" : r.getDeptId());
            one.put("email", r.getEmail() == null ? "" : r.getEmail());
            one.put("maxUses", r.getMaxUses() == null ? 1 : r.getMaxUses());
            one.put("usedCount", r.getUsedCount() == null ? 0 : r.getUsedCount());
            one.put("expiresAt", r.getExpiresAt() == null ? "" : r.getExpiresAt().toString());
            one.put("status", effectiveStatus(r));
            one.put("createdBy", r.getCreatedBy() == null ? "" : r.getCreatedBy());
            one.put("createTime", r.getCreateTime() == null ? "" : r.getCreateTime().toString());
            out.add(one);
        }
        return out;
    }

    /**
     * 在途邀请数（成员管理页概览条用）：还能用的才算在途。
     * <p>口径与 {@link #effectiveStatus} 一致——已撤销 / 已过期 / 已用尽都不算。
     */
    public long countPending() {
        long n = 0;
        for (TenantInvite r : inviteMapper.selectList(new LambdaQueryWrapper<TenantInvite>())) {
            if ("active".equals(effectiveStatus(r))) n++;
        }
        return n;
    }

    public void revoke(String id) {
        TenantInvite inv = inviteMapper.selectById(id);
        if (inv == null) throw new BizException("邀请不存在或已失效");
        if ("revoked".equals(inv.getStatus())) return;
        TenantInvite patch = new TenantInvite();
        patch.setId(id);
        patch.setStatus("revoked");
        inviteMapper.updateById(patch);
        audit.record("[AUDIT] 撤销租户邀请 租户={} 邀请={}", inv.getTenantId(), id);
    }

    /** 匿名预览：只回品牌、邀请人、预选角色与有效期，不回任何 token 之外的敏感信息 */
    public Map<String, Object> preview(String token) {
        TenantInvite inv = requireInvite(token);
        Tenant t = TenantContext.asPlatform(() -> tenantMapper.selectById(inv.getTenantId()));
        if (t == null) throw new BizException("邀请所属租户不存在");
        String inviter = null;
        try {
            User u = TenantContext.asPlatform(() -> userMapper.selectById(inv.getCreatedBy()));
            if (u != null) inviter = u.getUsername() == null || u.getUsername().isBlank() ? u.getUid() : u.getUsername();
        } catch (Exception ignored) {
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", t.getId());
        out.put("tenantName", t.getName());
        out.put("brandName", t.getBrandName() == null || t.getBrandName().isBlank() ? t.getName() : t.getBrandName());
        out.put("brandLogo", t.getBrandLogo() == null ? "" : t.getBrandLogo());
        out.put("brandColor", t.getBrandColor() == null ? "" : t.getBrandColor());
        out.put("inviterName", inviter == null ? "" : inviter);
        out.put("roles", splitRoles(inv.getRoleIds()));
        out.put("expiresAt", inv.getExpiresAt() == null ? "" : inv.getExpiresAt().toString());
        out.put("emailBound", inv.getEmail() != null && !inv.getEmail().isBlank());
        out.put("maxUses", inv.getMaxUses() == null ? 1 : inv.getMaxUses());
        out.put("usedCount", inv.getUsedCount() == null ? 0 : inv.getUsedCount());
        return out;
    }

    /**
     * 接受邀请：建账号落本租户，直接签发登录令牌。
     * <p>顺序：占名额 → 建账号 → 登录；建账号失败时归还名额，不会白耗一次一次性链接。
     */
    public Map<String, Object> accept(String token, String uid, String username, String password, String email) {
        TenantInvite inv = requireInvite(token);
        String u = uid == null ? "" : uid.trim();
        if (!UID.matcher(u).matches()) {
            throw new BizException("登录账号需为 1-64 位字母、数字、点、下划线或短横线");
        }
        String mail = email == null || email.isBlank() ? null : email.trim().toLowerCase();
        if (mail != null && (!mail.contains("@") || mail.contains(" ") || mail.length() > 160)) {
            throw new BizException("邮箱格式不正确");
        }
        authService.hashNewPassword(password);   // 先按平台密码策略校验，避免占完名额才发现密码不合规

        int claimed = inviteMapper.update(null, new LambdaUpdateWrapper<TenantInvite>()
                .eq(TenantInvite::getId, inv.getId())
                .eq(TenantInvite::getStatus, "active")
                .apply("(max_uses = 0 or used_count < max_uses)")
                .setSql("used_count = used_count + 1"));
        if (claimed == 0) throw new BizException("该邀请已被使用完毕或已失效");

        try {
            createUserInTenant(u, username, password, inv, mail);
        } catch (RuntimeException e) {
            releaseSlot(inv.getId());
            throw e;
        }
        // 用满即置 exhausted（不限次数的邀请不置）
        if (inv.getMaxUses() != null && inv.getMaxUses() > 0) {
            TenantInvite patch = new TenantInvite();
            patch.setId(inv.getId());
            patch.setStatus("exhausted");
            inviteMapper.update(patch, new LambdaUpdateWrapper<TenantInvite>()
                    .eq(TenantInvite::getId, inv.getId())
                    .eq(TenantInvite::getStatus, "active")
                    .apply("used_count >= max_uses"));
        }
        audit.record("[AUDIT] 接受租户邀请 租户={} 成员={} 邀请={}", inv.getTenantId(), u, inv.getId());
        Map<String, Object> out = authService.login(u, password);
        out.put("tenant", inv.getTenantId());
        out.put("tenantName", tenantService.nameOf(inv.getTenantId()));
        return out;
    }

    // ==================== 内部 ====================

    private void createUserInTenant(String uid, String username, String password, TenantInvite inv, String email) {
        String name = username == null || username.isBlank() ? null : username.trim();
        // 账号标识与显示名都是平台级命名空间：跨租户唯一（用户表 uk_username / 主键 uid）
        TenantContext.asPlatform(() -> {
            if (userMapper.selectById(uid) != null) {
                throw new BizException("该登录账号在本平台内已被占用，请换一个");
            }
            if (name != null) {
                Long n = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, name));
                if (n != null && n > 0) throw new BizException("该昵称已被占用，请换一个");
            }
            return null;
        });
        List<String> roles = splitRoles(inv.getRoleIds());
        String dept = inv.getDeptId();
        TenantContext.as(inv.getTenantId(), () -> {
            User user = new User();
            user.setUid(uid);
            user.setUsername(name);
            user.setEmail(email);
            user.setDepartmentId(dept);
            user.setStatus(1);
            user.setPasswordHash(authService.hashNewPassword(password));
            user.setLoginFailCount(0);
            // 不显式 set tenantId：插入由租户拦截器按当前绑定注入，显式写会与框架补列冲突
            userMapper.insert(user);
            List<String> rs = roles.isEmpty() ? List.of("user") : roles;
            for (String r : rs) userRoleService.grant(uid, r, "manual");
            return null;
        });
        tenantService.invalidate(uid);
        com.wenqu.ai.config.UserContextInterceptor.evict(uid);
    }

    private void releaseSlot(String inviteId) {
        try {
            inviteMapper.update(null, new LambdaUpdateWrapper<TenantInvite>()
                    .eq(TenantInvite::getId, inviteId)
                    .setSql("used_count = case when used_count > 0 then used_count - 1 else 0 end"));
            inviteMapper.update(null, new LambdaUpdateWrapper<TenantInvite>()
                    .eq(TenantInvite::getId, inviteId)
                    .eq(TenantInvite::getStatus, "exhausted")
                    .apply("used_count < max_uses")
                    .set(TenantInvite::getStatus, "active"));
        } catch (Exception e) {
            log.warn("[TENANT-INVITE] 归还邀请名额失败 id={}: {}", inviteId, e.toString());
        }
    }

    /** 按 token 取有效邀请；过期当场落库标记（列表与再次打开都显示 expired） */
    private TenantInvite requireInvite(String token) {
        if (token == null || token.isBlank() || token.length() > 128) throw new BizException("邀请链接无效");
        TenantInvite inv = TenantContext.asPlatform(() -> inviteMapper.selectOne(
                new LambdaQueryWrapper<TenantInvite>().eq(TenantInvite::getTokenHash, sha256(token.trim()))));
        if (inv == null) throw new BizException("邀请链接无效或已被撤销");
        String status = effectiveStatus(inv);
        if ("revoked".equals(status)) throw new BizException("该邀请已被撤销");
        if ("expired".equals(status)) throw new BizException("该邀请已过期，请联系管理员重新邀请");
        if ("exhausted".equals(status)) throw new BizException("该邀请已被使用完毕");
        return inv;
    }

    private String effectiveStatus(TenantInvite r) {
        if (r == null) return "expired";
        if ("revoked".equals(r.getStatus())) return "revoked";
        if (r.getExpiresAt() != null && r.getExpiresAt().isBefore(LocalDateTime.now())) return "expired";
        if (r.getMaxUses() != null && r.getMaxUses() > 0
                && r.getUsedCount() != null && r.getUsedCount() >= r.getMaxUses()) return "exhausted";
        return r.getStatus() == null ? "active" : r.getStatus();
    }

    private static List<String> splitRoles(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : raw.split(",")) {
            String v = s == null ? "" : s.trim().toLowerCase();
            if (!v.isEmpty() && v.length() <= 40 && set.size() < 5) set.add(v);
        }
        return new ArrayList<>(set);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
