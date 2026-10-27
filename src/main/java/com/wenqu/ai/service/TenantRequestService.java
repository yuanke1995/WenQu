package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.TenantRequestMapper;
import com.wenqu.ai.model.Tenant;
import com.wenqu.ai.model.TenantRequest;
import com.wenqu.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 租户自助开通（Phase 4 刀三）：公开入口收申请，平台级管理员批准后才真的开租户。
 *
 * <p><b>为什么留一道人工闸</b>：开通租户会连带建出首位管理员账号，而管理员能看本租户全部成员与数据。
 * 无人审批就自动放行，等于把「谁能在这个平台上开一家客户」交给互联网。
 * 所以自助的部分是<b>提交与材料</b>，批准的部分仍然是人。
 *
 * <p><b>批准时顺手建首位管理员</b>：只建租户不建账号，申请人登录后一无所有，
 * 还得让平台管理员再手工补一次账号——两趟流程迟早会漏掉第二趟。
 * 口令随机生成、只在这次操作的结果里出现一次（库里只有哈希），要求首次登录后自行改密。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantRequestService {

    /** 与 TenantService 同一形态：租户标识直接作为行内 tenant_id 的取值 */
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,31}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,190}$");
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRequestMapper mapper;
    private final TenantService tenantService;
    private final OrgService orgService;
    private final RateLimitService rateLimitService;
    private final AuditService audit;
    private final NotificationService notificationService;

    /** 公开入口：提交一份开通申请 */
    public Map<String, Object> apply(String orgName, String desiredId, String contactName,
                                     String contactEmail, String purpose, String clientIp) {
        rateLimitService.checkRateLimit("apply", "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp));
        String org = require(orgName, "要填机构名称", 80);
        String name = trimTo(contactName, 60);
        String email = trimTo(contactEmail, 160);
        if (email == null || !EMAIL.matcher(email).matches()) {
            throw new BizException("联系邮箱格式不对（开通结果会发到这里登记的联系人）");
        }
        String desired = trimTo(desiredId, 32);
        if (desired != null) {
            desired = desired.toLowerCase();
            if (!ID.matcher(desired).matches()) {
                throw new BizException("租户标识只能用 2-32 位小写字母、数字、- 或 _，且以字母或数字开头");
            }
            if (com.wenqu.ai.util.TenantContext.DEFAULT_TENANT.equals(desired) || tenantService.exists(desired)) {
                throw new BizException("这个标识已经被占用，换一个或留空由平台管理员定");
            }
        }
        String why = require(purpose, "要说一句打算用它做什么（审批要看这条）", 500);
        if (pendingOn(org, email)) {
            throw new BizException("同名机构或这个邮箱已有待审申请，请等处理结果");
        }

        TenantRequest r = new TenantRequest();
        r.setOrgName(org);
        r.setDesiredId(desired);
        r.setContactName(name);
        r.setContactEmail(email);
        r.setPurpose(why);
        r.setStatus("pending");
        r.setCreateTime(LocalDateTime.now());
        r.setUpdateTime(r.getCreateTime());
        mapper.insert(r);

        audit.record("[AUDIT] 租户开通申请 机构={} 邮箱={} 期望标识={}", org, email, desired == null ? "（未填）" : desired);
        notificationService.createForSuperadmins("tenant-request",
                "新的租户开通申请：" + org,
                "联系人 " + (name == null ? "（未填）" : name) + "（" + email + "），期望标识 "
                        + (desired == null ? "（未填）" : desired) + "。用途：" + why
                        + "。请到「成员管理 → 租户管理 → 开通申请」处理。",
                "tenant-request", r.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("status", r.getStatus());
        return out;
    }

    private boolean pendingOn(String org, String email) {
        Long n = mapper.selectCount(new LambdaQueryWrapper<TenantRequest>()
                .eq(TenantRequest::getStatus, "pending")
                .and(w -> w.eq(TenantRequest::getOrgName, org).or().eq(TenantRequest::getContactEmail, email)));
        return n != null && n > 0;
    }

    public List<Map<String, Object>> list(String status) {
        LambdaQueryWrapper<TenantRequest> q = new LambdaQueryWrapper<TenantRequest>()
                .orderByDesc(TenantRequest::getCreateTime);
        if (status != null && !status.isBlank()) {
            q.eq(TenantRequest::getStatus, status.trim().toLowerCase());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TenantRequest r : mapper.selectList(q)) {
            out.add(toView(r));
        }
        return out;
    }

    public long pendingCount() {
        try {
            Long n = mapper.selectCount(new LambdaQueryWrapper<TenantRequest>().eq(TenantRequest::getStatus, "pending"));
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("[TENANT] 待审申请计数失败（角标按 0 显示，不影响申请本身）: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 批准：开租户 + 建首位管理员账号。返回的口令<b>只在这一次</b>出现（库里只有哈希，事后查不到）。
     *
     * @param tenantId 批准人最终定的标识（申请里填了就先按它来，空着必须在这里给）
     */
    public Map<String, Object> approve(String id, String reviewer, String tenantId) {
        TenantRequest r = requirePending(id);
        String tid = tenantId == null || tenantId.isBlank() ? r.getDesiredId() : tenantId.trim().toLowerCase();
        if (tid == null || tid.isBlank()) {
            throw new BizException("申请人没填期望标识，批准时要给一个租户标识（小写字母、数字、- 或 _，2-32 位）");
        }
        if (!ID.matcher(tid).matches()) {
            throw new BizException("租户标识只能用 2-32 位小写字母、数字、- 或 _，且以字母或数字开头");
        }
        if (tenantService.exists(tid)) {
            throw new BizException("租户「" + tid + "」已存在，换一个标识再批准");
        }
        Tenant t = tenantService.create(tid, r.getOrgName(), r.getContactName(),
                "由开通申请批准生成（申请单 " + r.getId() + "）");

        String adminUid = tid + "-admin";
        String password = randomPassword();
        User admin;
        try {
            admin = orgService.createUser(adminUid,
                    (r.getContactName() == null || r.getContactName().isBlank() ? r.getOrgName() : r.getContactName()) + " 管理员",
                    null, "admin", password);
        } catch (Exception e) {
            // 租户已开出来，账号没建成：把两件事分开说清楚，别让人以为整单失败了去重复批准
            r.setStatus("approved");
            r.setOpenedTenant(t.getId());
            r.setReviewer(reviewer);
            r.setUpdateTime(LocalDateTime.now());
            mapper.updateById(r);
            audit.record("[AUDIT] 租户开通申请批准但首位管理员建号失败 申请={} 租户={} 原因={}",
                    r.getId(), t.getId(), e.getMessage());
            throw new BizException("租户「" + t.getId() + "」已开通，但首位管理员账号没建成（" + e.getMessage()
                    + "）。请到「成员管理」手工建号，别重复批准这一单。");
        }
        // 建号时落在批准人自己的租户（superadmin 走平台视图，插入按默认值落 default），
        // 这里显式把它挪进新租户——与界面上「先建成员再改归属」同一条路，不另开第二套写法
        tenantService.moveMember(admin.getUid(), t.getId());

        r.setStatus("approved");
        r.setOpenedTenant(t.getId());
        r.setAdminUid(admin.getUid());
        r.setReviewer(reviewer);
        r.setUpdateTime(LocalDateTime.now());
        mapper.updateById(r);
        audit.record("[AUDIT] 租户开通申请批准 申请={} 租户={} 首位管理员={} 审批人={}",
                r.getId(), t.getId(), admin.getUid(), reviewer);
        notificationService.createForAdmins("tenant-request",
                "租户已开通：" + r.getOrgName(),
                "标识 " + t.getId() + "，首位管理员 " + admin.getUid() + "（口令已发给申请人，本平台不保存明文）。",
                "tenant", t.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", t.getId());
        out.put("adminUid", admin.getUid());
        out.put("password", password);
        out.put("note", "口令只显示这一次；请要求对方首次登录后立刻改密。");
        return out;
    }

    public Map<String, Object> reject(String id, String reviewer, String note) {
        TenantRequest r = requirePending(id);
        r.setStatus("rejected");
        r.setReviewer(reviewer);
        r.setReviewNote(trimTo(note, 500));
        r.setUpdateTime(LocalDateTime.now());
        mapper.updateById(r);
        audit.record("[AUDIT] 租户开通申请驳回 申请={} 审批人={} 说明={}", id, reviewer, r.getReviewNote());
        return toView(r);
    }

    private TenantRequest requirePending(String id) {
        TenantRequest r = mapper.selectById(id);
        if (r == null) throw new BizException("申请单不存在");
        if (!"pending".equals(r.getStatus())) {
            throw new BizException("这一单已经处理过了（当前状态：" + statusLabel(r.getStatus()) + "），不重复处理");
        }
        return r;
    }

    private Map<String, Object> toView(TenantRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("orgName", r.getOrgName());
        m.put("desiredId", r.getDesiredId());
        m.put("contactName", r.getContactName());
        m.put("contactEmail", r.getContactEmail());
        m.put("purpose", r.getPurpose());
        m.put("status", r.getStatus());
        m.put("statusLabel", statusLabel(r.getStatus()));
        m.put("openedTenant", r.getOpenedTenant());
        m.put("adminUid", r.getAdminUid());
        m.put("reviewer", r.getReviewer());
        m.put("reviewNote", r.getReviewNote());
        m.put("createTime", r.getCreateTime());
        return m;
    }

    private static String statusLabel(String s) {
        return switch (s == null ? "" : s) {
            case "pending" -> "待审";
            case "approved" -> "已开通";
            case "rejected" -> "已驳回";
            default -> s;
        };
    }

    /** 随机口令：去掉易混字符（1/l/I/0/O），长度 14 */
    private static String randomPassword() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String require(String v, String msg, int max) {
        String s = trimTo(v, max);
        if (s == null) throw new BizException(msg);
        return s;
    }

    private static String trimTo(String v, int max) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty()) return null;
        if (s.length() > max) throw new BizException("内容过长（上限 " + max + " 字）");
        return s;
    }
}
