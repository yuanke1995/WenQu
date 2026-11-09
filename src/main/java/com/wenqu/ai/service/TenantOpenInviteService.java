package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.TenantOpenInviteMapper;
import com.wenqu.ai.model.Tenant;
import com.wenqu.ai.model.TenantOpenInvite;
import com.wenqu.ai.model.User;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 邀请式开通：平台先发一次性邀请，客户凭链接补齐信息，接受即开通。
 *
 * <p>与自助申请的审批方向相反——这一单已被平台预批准，不再进入待审队列。
 * token 只存 SHA-256；名额用 CAS 占用，两个并发请求只有一笔能开通。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantOpenInviteService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,190}$");
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,31}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantOpenInviteMapper mapper;
    private final TenantService tenantService;
    private final OrgService orgService;
    private final TenantRequestService requestService;
    private final AuditService audit;
    private final RateLimitService rateLimitService;
    private final com.wenqu.ai.service.NotificationService notificationService;

    /** 平台发邀请：返回明文 token 只在这一次 */
    public Map<String, Object> create(String orgName, String desiredId, String contactName,
                                      String contactEmail, String note, Integer maxUses, Integer expireDays) {
        String email = contactEmail == null ? "" : contactEmail.trim();
        if (email.isBlank() || !EMAIL.matcher(email).matches()) {
            throw new BizException("要填一个有效的联系邮箱（客户凭它接受邀请）");
        }
        int uses = maxUses == null ? 1 : maxUses;
        if (uses < 0 || uses > 100) throw new BizException("可用次数需在 0-100 之间（0=不限）");
        int days = expireDays == null ? 7 : expireDays;
        if (days < 1 || days > 30) throw new BizException("有效期需在 1-30 天之间");
        String desired = desiredId == null || desiredId.isBlank() ? null : desiredId.trim().toLowerCase();
        if (desired != null && !ID.matcher(desired).matches()) {
            throw new BizException("租户标识只能用 2-32 位小写字母、数字、- 或 _");
        }
        if (desired != null && (com.wenqu.ai.util.TenantContext.DEFAULT_TENANT.equals(desired)
                || tenantService.exists(desired))) {
            throw new BizException("这个标识已被占用，换一个或留空由客户填");
        }

        String token = newToken();
        TenantOpenInvite inv = new TenantOpenInvite();
        inv.setId(UUID.randomUUID().toString().replace("-", ""));
        inv.setTokenHash(sha256(token));
        inv.setTokenPrefix(token.substring(0, 8));
        inv.setOrgName(trim(orgName, 80));
        inv.setDesiredId(desired);
        inv.setContactName(trim(contactName, 60));
        inv.setContactEmail(email.toLowerCase());
        inv.setNote(trim(note, 300));
        inv.setMaxUses(uses);
        inv.setUsedCount(0);
        inv.setExpiresAt(LocalDateTime.now().plusDays(days));
        inv.setStatus("active");
        inv.setCreatedBy(com.wenqu.ai.util.RequestUser.uid());
        inv.setCreateTime(LocalDateTime.now());
        mapper.insert(inv);
        audit.record("[AUDIT] 生成开通邀请 机构={} 邮箱={} 次数={} 有效期={}天",
                inv.getOrgName(), email, uses == 0 ? "不限" : uses, days);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", inv.getId());
        out.put("path", "/open/" + token);
        out.put("tokenPrefix", inv.getTokenPrefix());
        out.put("expiresAt", inv.getExpiresAt().toString());
        out.put("maxUses", uses);
        return out;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TenantOpenInvite i : mapper.selectList(new LambdaQueryWrapper<TenantOpenInvite>()
                .orderByDesc(TenantOpenInvite::getCreateTime))) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", i.getId());
            one.put("tokenPrefix", i.getTokenPrefix());
            one.put("orgName", i.getOrgName() == null ? "" : i.getOrgName());
            one.put("desiredId", i.getDesiredId() == null ? "" : i.getDesiredId());
            one.put("contactName", i.getContactName() == null ? "" : i.getContactName());
            one.put("contactEmail", i.getContactEmail());
            one.put("note", i.getNote() == null ? "" : i.getNote());
            one.put("maxUses", i.getMaxUses() == null ? 1 : i.getMaxUses());
            one.put("usedCount", i.getUsedCount() == null ? 0 : i.getUsedCount());
            one.put("expiresAt", i.getExpiresAt() == null ? "" : i.getExpiresAt().toString());
            one.put("status", statusOf(i));
            one.put("openedTenant", i.getOpenedTenant() == null ? "" : i.getOpenedTenant());
            one.put("createTime", i.getCreateTime() == null ? "" : i.getCreateTime().toString());
            out.add(one);
        }
        return out;
    }

    public void revoke(String id) {
        TenantOpenInvite i = mapper.selectById(id);
        if (i == null) throw new BizException("邀请不存在");
        if ("revoked".equals(i.getStatus())) return;
        i.setStatus("revoked");
        mapper.updateById(i);
        audit.record("[AUDIT] 撤销开通邀请 id={} 机构={}", id, i.getOrgName());
    }

    /** 公开预览：只回预填信息，不回 token 或其它邀请 */
    public Map<String, Object> preview(String token, String clientIp) {
        rateLimitService.checkRateLimit("open-invite", "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp));
        TenantOpenInvite i = valid(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orgName", i.getOrgName() == null ? "" : i.getOrgName());
        out.put("desiredId", i.getDesiredId() == null ? "" : i.getDesiredId());
        out.put("contactName", i.getContactName() == null ? "" : i.getContactName());
        out.put("contactEmail", i.getContactEmail());
        out.put("note", i.getNote() == null ? "" : i.getNote());
        out.put("expiresAt", i.getExpiresAt() == null ? "" : i.getExpiresAt().toString());
        return out;
    }

    /** 客户接受邀请：建租户 + 建首位管理员（密码由客户自设），并发送工作区凭据 */
    public Map<String, Object> accept(String token, String orgName, String desiredId, String contactName,
                                      String email, String password, String clientIp) {
        rateLimitService.checkRateLimit("open-invite", "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp));
        TenantOpenInvite i = valid(token);
        String mail = email == null ? "" : email.trim().toLowerCase();
        if (!mail.equalsIgnoreCase(i.getContactEmail())) {
            throw new BizException("这个邀请限定给 " + i.getContactEmail() + "，请用该邮箱接受");
        }
        String org = trim(orgName, 80);
        if (org == null) org = i.getOrgName();
        if (org == null) throw new BizException("要填机构名称");
        String tid = desiredId == null || desiredId.isBlank() ? i.getDesiredId() : desiredId.trim().toLowerCase();
        if (tid == null || tid.isBlank()) throw new BizException("要给工作区起一个标识");
        if (!ID.matcher(tid).matches()) {
            throw new BizException("工作区标识只能用 2-32 位小写字母、数字、- 或 _");
        }
        if (tenantService.exists(tid)) throw new BizException("这个标识已被占用，换一个");
        if (password == null || password.length() < 6) throw new BizException("密码至少 6 位");

        int claimed = mapper.update(null, new LambdaUpdateWrapper<TenantOpenInvite>()
                .eq(TenantOpenInvite::getId, i.getId())
                .eq(TenantOpenInvite::getStatus, "active")
                .apply("(max_uses = 0 or used_count < max_uses)")
                .setSql("used_count = used_count + 1"));
        if (claimed == 0) throw new BizException("这个邀请已被使用或已失效");

        Tenant t;
        try {
            t = tenantService.create(tid, org, trim(contactName, 60) == null ? i.getContactName() : contactName,
                    "由开通邀请接受生成（邀请 " + i.getId() + "）", "standard", null);
        } catch (Exception e) {
            mapper.update(null, new LambdaUpdateWrapper<TenantOpenInvite>()
                    .eq(TenantOpenInvite::getId, i.getId())
                    .setSql("used_count = GREATEST(used_count - 1, 0)"));
            throw e;
        }

        String adminUid = tid + "-admin";
        User admin;
        try {
            admin = orgService.createUser(adminUid,
                    (org) + " 管理员", null, java.util.List.of("admin"), password);
        } catch (Exception e) {
            throw new BizException("工作区「" + t.getId() + "」已创建，但管理员账号没建成（" + e.getMessage()
                    + "）。请联系平台管理员处理，不要重复接受邀请。");
        }
        tenantService.moveMember(admin.getUid(), t.getId());

        i.setOpenedTenant(t.getId());
        int used = (i.getUsedCount() == null ? 0 : i.getUsedCount()) + 1;
        i.setUsedCount(used);
        boolean exhausted = i.getMaxUses() != null && i.getMaxUses() > 0 && used >= i.getMaxUses();
        i.setStatus(exhausted ? "exhausted" : "active");
        mapper.updateById(i);
        audit.record("[AUDIT] 开通邀请被接受 邀请={} 租户={} 管理员={}", i.getId(), t.getId(), adminUid);
        notificationService.createForAdmins(com.wenqu.ai.model.Notification.TYPE_TENANT_APPROVED,
                "租户已通过邀请开通：" + org,
                "标识 " + t.getId() + "，首位管理员 " + adminUid + "（密码由客户设置）。",
                "tenant", t.getId());

        boolean sent = requestService.sendWorkspaceReady(mail, org, t.getId(), adminUid, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", t.getId());
        out.put("adminUid", adminUid);
        out.put("emailSent", sent);
        out.put("note", sent ? "工作区已开通，凭据已发送到邮箱" : "工作区已开通，凭据邮件未发出，请记录账号");
        return out;
    }

    private TenantOpenInvite valid(String token) {
        String t = token == null ? "" : token.trim();
        if (t.isBlank()) throw new BizException("邀请链接不完整");
        TenantOpenInvite i = mapper.selectOne(new LambdaQueryWrapper<TenantOpenInvite>()
                .eq(TenantOpenInvite::getTokenHash, sha256(t)));
        if (i == null) throw new BizException("邀请链接无效或已被撤销");
        String status = statusOf(i);
        if (!"active".equals(status)) {
            throw new BizException(switch (status) {
                case "expired" -> "邀请已过期，请联系平台管理员重新发送";
                case "exhausted" -> "邀请已被使用，请联系平台管理员";
                case "revoked" -> "邀请已被撤销，请联系平台管理员";
                default -> "邀请当前不可用";
            });
        }
        return i;
    }

    private static String statusOf(TenantOpenInvite i) {
        if ("revoked".equals(i.getStatus())) return "revoked";
        if (i.getExpiresAt() != null && i.getExpiresAt().isBefore(LocalDateTime.now())) return "expired";
        if (i.getMaxUses() != null && i.getMaxUses() > 0
                && i.getUsedCount() != null && i.getUsedCount() >= i.getMaxUses()) return "exhausted";
        return "active";
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String trim(String v, int max) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        if (s.length() > max) throw new BizException("内容过长（上限 " + max + " 字）");
        return s;
    }
}
