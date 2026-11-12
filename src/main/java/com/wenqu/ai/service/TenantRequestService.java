package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigKeys;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.TenantRequestMapper;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.Tenant;
import com.wenqu.ai.model.TenantRequest;
import com.wenqu.ai.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 租户自助开通：公开入口收申请，平台级管理员批准后才真的开租户。
 *
 * <p><b>为什么留一道人工闸</b>：开通租户会连带建出首位管理员账号，而管理员能看本租户全部成员与数据。
 * 无人审批就自动放行，等于把「谁能在这个平台上开一家客户」交给互联网。
 * 所以自助的部分是<b>提交与材料</b>，批准的部分仍然是人；只有平台显式开启域名白名单时，
 * 命中白名单的申请才会自动开通（默认关闭）。
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
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9]([a-z0-9.-]{1,188})[a-z0-9]$");
    private static final List<String> SCALES = List.of("1-50", "51-200", "201-1000", "1000+");
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRequestMapper mapper;
    private final TenantService tenantService;
    private final OrgService orgService;
    private final RateLimitService rateLimitService;
    private final AuditService audit;
    private final NotificationService notificationService;
    private final MailDispatchService mailDispatch;
    private final ConfigService configService;
    private final AuthService authService;
    private final ImageUrlSigner imageUrlSigner;

    /** 兼容旧调用：不带规模/域名/材料 */
    public Map<String, Object> apply(String orgName, String desiredId, String contactName,
                                     String contactEmail, String purpose, String clientIp) {
        return apply(orgName, desiredId, contactName, contactEmail, purpose, null, null,
                0, null, null, clientIp);
    }

    /** 公开入口：提交一份开通申请 */
    public Map<String, Object> apply(String orgName, String desiredId, String contactName,
                                     String contactEmail, String purpose, String scale, String domain,
                                     Integer ssoRequested, String materials, String resubmitOf,
                                     String clientIp) {
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
        String scaleValue = normalizeScale(scale);
        String domainValue = normalizeDomain(domain);
        String materialJson = normalizeMaterials(materials);
        String resubmit = trimTo(resubmitOf, 50);
        if (pendingOn(org, email)) {
            throw new BizException("同名机构或这个邮箱已有待审申请，请等处理结果");
        }

        TenantRequest r = new TenantRequest();
        r.setOrgName(org);
        r.setDesiredId(desired);
        r.setContactName(name);
        r.setContactEmail(email);
        r.setPurpose(why);
        r.setScale(scaleValue);
        r.setDomain(domainValue);
        r.setSsoRequested(ssoRequested != null && ssoRequested == 1 ? 1 : 0);
        r.setMaterials(materialJson);
        r.setResubmitOf(resubmit);
        r.setStatus("pending");
        r.setCreateTime(LocalDateTime.now());
        r.setUpdateTime(r.getCreateTime());
        mapper.insert(r);

        audit.record("[AUDIT] 租户开通申请 机构={} 邮箱={} 期望标识={} 域名={} 规模={}",
                org, email, desired == null ? "（未填）" : desired,
                domainValue == null ? "（未填）" : domainValue, scaleValue == null ? "（未填）" : scaleValue);
        boolean autoHit = autoApproveHit(domainValue, email);
        if (!autoHit) {
            notificationService.createForSuperadmins(Notification.TYPE_TENANT_REQUEST,
                    "新的租户开通申请：" + org,
                    "联系人 " + (name == null ? "（未填）" : name) + "（" + email + "），期望标识 "
                            + (desired == null ? "（未填）" : desired) + "。规模 "
                            + (scaleValue == null ? "（未填）" : scaleValue) + "。用途：" + why
                            + "。请到「租户与开通 → 开通申请」处理。",
                    "tenant-request", r.getId());
        }

        // 回执邮件：不阻塞提交；失败只留日志，申请人仍可凭编号查进度
        sendAsync(email, "已收到你的开通申请：" + org, receiptMail(r));

        // 域名白名单自动开通：默认关闭，命中才走；开通失败退回待审，不把错误抛给申请人
        if (autoHit) {
            try {
                Map<String, Object> delivery = provision(r, "自动开通（域名白名单）", null, "trial");
                boolean sent = deliverMail(r, delivery, true);
                if (sent) {
                    r.setDeliveredAt(LocalDateTime.now());
                    mapper.updateById(r);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", r.getId());
                out.put("status", "approved");
                out.put("autoApproved", true);
                out.put("tenantId", delivery.get("tenantId"));
                out.put("emailSent", sent);
                out.put("message", sent
                        ? "已自动开通，工作区地址与管理员凭据已发送到 " + email
                        : "已自动开通，但凭据邮件未发出，请联系平台管理员");
                return out;
            } catch (Exception e) {
                log.warn("[TENANT] 自动开通失败，申请退回待审 id={}: {}", r.getId(), e.toString());
                audit.record("[AUDIT] 自动开通失败转待审 申请={} 原因={}", r.getId(), e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("status", r.getStatus());
        out.put("autoApproved", false);
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

    // ==================== 公开查询：标识可用性 / 申请进度 / 重提预填 ====================

    /** 标识可用性（公开、限流）：只返回「能不能用」与原因，不暴露任何租户数据 */
    public Map<String, Object> availability(String desiredId, String clientIp) {
        rateLimitService.checkRateLimit("apply-check", "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp));
        String id = trimTo(desiredId, 32);
        Map<String, Object> out = new LinkedHashMap<>();
        if (id == null) {
            out.put("available", false);
            out.put("reason", "请先填写工作区标识");
            return out;
        }
        id = id.toLowerCase();
        if (!ID.matcher(id).matches()) {
            out.put("available", false);
            out.put("reason", "只能用 2–32 位小写字母、数字、- 或 _，且以字母或数字开头");
            return out;
        }
        if (com.wenqu.ai.util.TenantContext.DEFAULT_TENANT.equals(id)) {
            out.put("available", false);
            out.put("reason", "default 是平台保留标识，请换一个");
            return out;
        }
        if (tenantService.exists(id)) {
            out.put("available", false);
            out.put("reason", "这个标识已被占用");
            out.put("suggestions", suggestions(id));
            return out;
        }
        out.put("available", true);
        out.put("reason", id + " 可用");
        return out;
    }

    /** 申请人凭「编号 + 邮箱」查询进度；不匹配时统一文案，避免枚举 */
    public Map<String, Object> publicStatus(String id, String email, String clientIp) {
        rateLimitService.checkRateLimit("apply-status", "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp));
        TenantRequest r = match(id, email);
        return toPublicView(r);
    }

    /** 驳回后重新申请：带回原单已填信息（仍需重新提交，不自动复活） */
    public Map<String, Object> resubmitPreview(String id, String email, String clientIp) {
        rateLimitService.checkRateLimit("apply-status", "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp));
        TenantRequest r = match(id, email);
        if (!"rejected".equals(r.getStatus())) {
            throw new BizException("只有已驳回的申请可以重新提交");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("orgName", r.getOrgName());
        out.put("desiredId", r.getDesiredId());
        out.put("status", r.getStatus());
        out.put("contactName", r.getContactName());
        out.put("contactEmail", r.getContactEmail());
        out.put("purpose", r.getPurpose());
        out.put("scale", r.getScale());
        out.put("domain", r.getDomain());
        out.put("ssoRequested", r.getSsoRequested());
        out.put("materials", signedMaterials(r.getMaterials()));
        out.put("reviewNote", r.getReviewNote());
        return out;
    }

    private TenantRequest match(String id, String email) {
        String rid = trimTo(id, 50);
        String mail = trimTo(email, 160);
        if (rid == null || mail == null) throw new BizException("要同时填申请编号与提交时的邮箱");
        TenantRequest r = mapper.selectById(rid);
        if (r == null || !mail.equalsIgnoreCase(r.getContactEmail())) {
            throw new BizException("没有匹配的申请，请核对编号与邮箱");
        }
        return r;
    }

    // ==================== 批准 / 驳回 / 交付 ====================

    /**
     * 批准：开租户 + 建首位管理员账号。返回的口令<b>只在这一次</b>出现（库里只有哈希，事后查不到）。
     *
     * @param tenantId 批准人最终定的标识（申请里填了就先按它来，空着必须在这里给）
     */
    public Map<String, Object> approve(String id, String reviewer, String tenantId) {
        TenantRequest r = requirePending(id);
        Map<String, Object> delivery = provision(r, reviewer, tenantId, "standard");
        boolean sent = deliverMail(r, delivery, true);
        if (sent) {
            r.setDeliveredAt(LocalDateTime.now());
            mapper.updateById(r);
        }
        delivery.put("emailSent", sent);
        delivery.put("email", r.getContactEmail());
        delivery.put("note", sent
                ? "已发送到申请人邮箱；口令只显示这一次，请要求对方首次登录后立刻改密。"
                : "邮件未发出（SMTP 未配置或发送失败），请复制凭据交给申请人；口令只显示这一次。");
        return delivery;
    }

    /** 开租户 + 建管理员 + 落审批结果（自动开通与人工批准共用） */
    private Map<String, Object> provision(TenantRequest r, String reviewer, String tenantId, String plan) {
        String tid = tenantId == null || tenantId.isBlank() ? r.getDesiredId() : tenantId.trim().toLowerCase();
        if (tid == null || tid.isBlank()) {
            tid = resolveTenantId(r);
        }
        if (!ID.matcher(tid).matches()) {
            throw new BizException("租户标识只能用 2-32 位小写字母、数字、- 或 _，且以字母或数字开头");
        }
        if (tenantService.exists(tid)) {
            throw new BizException("租户「" + tid + "」已存在，换一个标识再批准");
        }
        Tenant t = tenantService.create(tid, r.getOrgName(), r.getContactName(),
                "由开通申请批准生成（申请单 " + r.getId() + "）", plan, r.getDomain());
        if (r.getSsoRequested() != null && r.getSsoRequested() == 1) {
            tenantService.markSsoRequested(t.getId());
        }

        String adminUid = tid + "-admin";
        String password = randomPassword();
        User admin;
        try {
            admin = orgService.createUser(adminUid,
                    (r.getContactName() == null || r.getContactName().isBlank() ? r.getOrgName() : r.getContactName()) + " 管理员",
                    null, java.util.List.of("admin"), password);
        } catch (Exception e) {
            // 租户已开出来，账号没建成：把两件事分开说清楚，别让人以为整单失败了去重复批准
            r.setStatus("approved");
            r.setOpenedTenant(t.getId());
            r.setReviewer(reviewer);
            r.setReviewedAt(LocalDateTime.now());
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
        r.setReviewedAt(LocalDateTime.now());
        r.setUpdateTime(LocalDateTime.now());
        mapper.updateById(r);
        audit.record("[AUDIT] 租户开通申请批准 申请={} 租户={} 首位管理员={} 审批人={}",
                r.getId(), t.getId(), admin.getUid(), reviewer);
        notificationService.createForAdmins(Notification.TYPE_TENANT_APPROVED,
                "租户已开通：" + r.getOrgName(),
                "标识 " + t.getId() + "，首位管理员 " + admin.getUid()
                        + "。交付凭据请到「租户与开通」查看或重发，本平台不保存明文口令。",
                "tenant", t.getId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", t.getId());
        out.put("adminUid", admin.getUid());
        out.put("password", password);
        out.put("tenantName", t.getName());
        out.put("adminName", admin.getUsername());
        return out;
    }

    public Map<String, Object> reject(String id, String reviewer, String note) {
        TenantRequest r = requirePending(id);
        r.setStatus("rejected");
        r.setReviewer(reviewer);
        r.setReviewNote(trimTo(note, 500));
        r.setReviewedAt(LocalDateTime.now());
        r.setUpdateTime(LocalDateTime.now());
        mapper.updateById(r);
        audit.record("[AUDIT] 租户开通申请驳回 申请={} 审批人={} 说明={}", id, reviewer, r.getReviewNote());
        sendAsync(r.getContactEmail(), "你的开通申请未通过：" + r.getOrgName(), rejectMail(r));
        return toView(r);
    }

    /**
     * 交付：重发凭据邮件（reset=false 只提醒，不含口令）或重置首位管理员口令后重发（reset=true）。
     * 明文口令不落库，所以「重发原件」在密码学上做不到——遗失只能重置。
     */
    public Map<String, Object> deliver(String id, boolean resetPassword) {
        TenantRequest r = mapper.selectById(id);
        if (r == null) throw new BizException("申请单不存在");
        if (!"approved".equals(r.getStatus()) || r.getOpenedTenant() == null) {
            throw new BizException("这一单还未开通，不能交付凭据");
        }
        String adminUid = r.getAdminUid() == null || r.getAdminUid().isBlank()
                ? r.getOpenedTenant() + "-admin" : r.getAdminUid();
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("tenantId", r.getOpenedTenant());
        delivery.put("adminUid", adminUid);
        delivery.put("tenantName", tenantService.nameOf(r.getOpenedTenant()));
        String password = null;
        if (resetPassword) {
            password = randomPassword();
            authService.resetPassword(adminUid, password);
            delivery.put("password", password);
            audit.record("[AUDIT] 重置首位管理员口令 申请={} 租户={} 管理员={}",
                    r.getId(), r.getOpenedTenant(), adminUid);
        }
        boolean sent = deliverMail(r, delivery, false);
        if (sent) {
            r.setDeliveredAt(LocalDateTime.now());
            r.setUpdateTime(LocalDateTime.now());
            mapper.updateById(r);
        }
        delivery.put("emailSent", sent);
        delivery.put("email", r.getContactEmail());
        delivery.put("passwordReset", resetPassword);
        delivery.put("note", resetPassword
                ? "已重置口令并发送到申请人邮箱；旧口令立即失效。"
                : "已重发凭据提醒；未重置口令（明文不落库，遗失请选择重置）。");
        return delivery;
    }

    private TenantRequest requirePending(String id) {
        TenantRequest r = mapper.selectById(id);
        if (r == null) throw new BizException("申请单不存在");
        if (!"pending".equals(r.getStatus())) {
            throw new BizException("这一单已经处理过了（当前状态：" + statusLabel(r.getStatus()) + "），不重复处理");
        }
        return r;
    }

    // ==================== 邮件 ====================

    private boolean deliverMail(TenantRequest r, Map<String, Object> delivery, boolean includePassword) {
        if (r.getContactEmail() == null || r.getContactEmail().isBlank()) return false;
        String password = includePassword ? String.valueOf(delivery.getOrDefault("password", "")) : null;
        return sendWorkspaceReady(r.getContactEmail(), r.getOrgName(),
                String.valueOf(delivery.get("tenantId")), String.valueOf(delivery.get("adminUid")), password);
    }

    /** 邀请式开通/自动开通共用：把工作区地址、管理员账号与初始口令发给客户 */
    public boolean sendWorkspaceReady(String email, String orgName, String tenantId, String adminUid, String password) {
        if (email == null || email.isBlank()) return false;
        String subject = "工作区已开通：" + orgName;
        String body = "<p>你的独立工作区已经开通。</p>"
                + "<table style=\"border-collapse:collapse;font-size:13px;line-height:2\">"
                + row("工作区地址", loginUrl(tenantId))
                + row("工作区标识", tenantId)
                + row("管理员账号", adminUid)
                + (password == null || password.isBlank() ? "" : row("初始口令", password))
                + "</table>"
                + (password == null || password.isBlank()
                ? "<p>请使用你已有的密码登录；如忘记可联系平台管理员重置。</p>"
                : "<p>初始口令只出现在这封邮件里，登录后请立刻修改。</p>");
        return mailDispatch.sendTo(email, subject, mailWrap(subject, body));
    }

    private void sendAsync(String to, String subject, String html) {
        if (to == null || to.isBlank()) return;
        CompletableFuture.runAsync(() -> {
            try {
                mailDispatch.sendTo(to, subject, html);
            } catch (Exception e) {
                log.debug("[TENANT] 申请人邮件发送失败 to={}: {}", to, e.toString());
            }
        });
    }

    private String receiptMail(TenantRequest r) {
        String subject = "已收到你的开通申请：" + r.getOrgName();
        String body = "<p>我们已收到「" + escape(r.getOrgName()) + "」的开通申请，申请编号：</p>"
                + "<p style=\"font-family:monospace;font-size:15px;background:#f6f8fa;border:1px solid #e6e9ed;border-radius:6px;padding:10px 12px\">"
                + escape(r.getId()) + "</p>"
                + "<p>平台审核后会通过本邮箱把工作区地址与首个管理员凭据发给你。审核期间可以凭「申请编号 + 本邮箱」查询进度。</p>"
                + "<p>当前状态：<b>待审核</b></p>";
        return mailWrap(subject, body);
    }

    private String rejectMail(TenantRequest r) {
        String subject = "你的开通申请未通过：" + r.getOrgName();
        String body = "<p>很抱歉，这次开通申请没有通过。</p>"
                + (r.getReviewNote() == null || r.getReviewNote().isBlank()
                ? "" : "<p>说明：" + escape(r.getReviewNote()) + "</p>")
                + "<p>你可以补充资料后重新提交；原申请编号：" + escape(r.getId()) + "</p>";
        return mailWrap(subject, body);
    }

    private String mailWrap(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>"
                + "<body style=\"margin:0;padding:24px;background:#f7f8fa;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif\">"
                + "<div style=\"max-width:600px;margin:0 auto;background:#fff;border:1px solid #e6e9ed;border-radius:10px;overflow:hidden\">"
                + "<div style=\"padding:20px 28px 0;color:#98a0aa;font-size:12px\">问渠 · 工作区开通</div>"
                + "<div style=\"padding:10px 28px 0;font-size:18px;font-weight:600;color:#1f2329\">" + escape(title) + "</div>"
                + "<div style=\"padding:12px 28px 24px;font-size:13px;line-height:1.8;color:#5f6570\">" + body + "</div>"
                + "<div style=\"padding:14px 28px 20px;border-top:1px solid #e6e9ed;color:#98a0aa;font-size:11.5px;line-height:1.8\">"
                + "这封邮件由问渠租户开通流程自动发送，请勿直接回复。</div></div></body></html>";
    }

    private static String row(String k, String v) {
        return "<tr><td style=\"color:#98a0aa;padding:2px 16px 2px 0;white-space:nowrap\">" + escape(k)
                + "</td><td style=\"color:#1f2329;font-family:monospace\">" + escape(v) + "</td></tr>";
    }

    private String loginUrl(String tenantId) {
        String base = configService.get("notify.mail.webBaseUrl");
        if (base == null || base.isBlank()) base = "";
        base = base.trim().replaceAll("/+$", "");
        return base + "/login" + (tenantId == null || tenantId.isBlank() ? "" : "?tenant=" + tenantId);
    }

    // ==================== 自动开通 / SLA ====================

    private boolean autoApproveHit(String domain, String email) {
        if (!configService.getBoolean(ConfigKeys.TENANT_AUTO_APPROVE_ENABLED, false)) return false;
        String raw = configService.get("tenant.autoApprove.domains");
        if (raw == null || raw.isBlank()) return false;
        String candidate = domain;
        if (candidate == null || candidate.isBlank()) {
            int at = email == null ? -1 : email.indexOf('@');
            candidate = at < 0 ? null : email.substring(at + 1).toLowerCase();
        }
        if (candidate == null) return false;
        for (String d : raw.split(",")) {
            if (d.trim().equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    /** 自动开通兜底标识：期望标识 → 域名首段 → org + 随机后缀 */
    private String resolveTenantId(TenantRequest r) {
        String base = r.getDesiredId();
        if (base == null || base.isBlank()) {
            String d = r.getDomain();
            if (d == null || d.isBlank()) {
                int at = r.getContactEmail() == null ? -1 : r.getContactEmail().indexOf('@');
                d = at < 0 ? null : r.getContactEmail().substring(at + 1);
            }
            if (d != null) base = d.split("\\.")[0];
        }
        if (base == null || base.isBlank()) base = "tenant";
        base = base.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if (base.length() < 2) base = "tenant";
        if (base.length() > 28) base = base.substring(0, 28);
        String candidate = base;
        for (int i = 2; i < 10 && tenantService.exists(candidate); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    /** 每天 9 点催办：待审超过 24 小时且 24 小时内未提醒过的申请，汇总一条通知给平台管理员 */
    @Scheduled(cron = "0 0 9 * * *")
    public void slaReminder() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<TenantRequest> rows = mapper.selectList(new LambdaQueryWrapper<TenantRequest>()
                    .eq(TenantRequest::getStatus, "pending")
                    .lt(TenantRequest::getCreateTime, now.minusHours(24))
                    .and(w -> w.isNull(TenantRequest::getRemindedAt)
                            .or().lt(TenantRequest::getRemindedAt, now.minusHours(24))));
            if (rows == null || rows.isEmpty()) return;
            long oldest = rows.stream().map(TenantRequest::getCreateTime).filter(java.util.Objects::nonNull)
                    .mapToLong(t -> Duration.between(t, now).toHours()).max().orElse(0);
            notificationService.createForSuperadmins(Notification.TYPE_TENANT_REQUEST,
                    "有 " + rows.size() + " 条开通申请等待超过 24 小时",
                    "最久已等待 " + oldest + " 小时。请到「租户与开通 → 开通申请」处理。",
                    "tenant-request", "sla");
            for (TenantRequest r : rows) {
                r.setRemindedAt(now);
                mapper.updateById(r);
            }
            audit.record("[AUDIT] 租户申请 SLA 催办 数量={} 最久={}小时", rows.size(), oldest);
        } catch (Exception e) {
            log.warn("[TENANT] SLA 催办失败（不影响申请本身）: {}", e.toString());
        }
    }

    // ==================== 视图 ====================

    private Map<String, Object> toView(TenantRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("orgName", r.getOrgName());
        m.put("desiredId", r.getDesiredId());
        m.put("contactName", r.getContactName());
        m.put("contactEmail", r.getContactEmail());
        m.put("purpose", r.getPurpose());
        m.put("scale", r.getScale());
        m.put("domain", r.getDomain());
        m.put("ssoRequested", r.getSsoRequested() != null && r.getSsoRequested() == 1);
        m.put("materials", signedMaterials(r.getMaterials()));
        m.put("resubmitOf", r.getResubmitOf());
        m.put("status", r.getStatus());
        m.put("statusLabel", statusLabel(r.getStatus()));
        m.put("openedTenant", r.getOpenedTenant());
        m.put("adminUid", r.getAdminUid());
        m.put("reviewer", r.getReviewer());
        m.put("reviewNote", r.getReviewNote());
        m.put("createTime", r.getCreateTime());
        m.put("reviewedAt", r.getReviewedAt());
        m.put("deliveredAt", r.getDeliveredAt());
        m.put("waitingHours", r.getCreateTime() == null ? 0
                : Math.max(0, Duration.between(r.getCreateTime(), LocalDateTime.now()).toHours()));
        return m;
    }

    private Map<String, Object> toPublicView(TenantRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("orgName", r.getOrgName());
        m.put("desiredId", r.getDesiredId());
        m.put("status", r.getStatus());
        m.put("statusLabel", statusLabel(r.getStatus()));
        m.put("reviewNote", r.getReviewNote());
        m.put("openedTenant", r.getOpenedTenant());
        m.put("createTime", r.getCreateTime());
        m.put("reviewedAt", r.getReviewedAt());
        m.put("deliveredAt", r.getDeliveredAt());
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

    private static String normalizeScale(String scale) {
        if (scale == null || scale.isBlank()) return null;
        String s = scale.trim();
        if (!SCALES.contains(s)) throw new BizException("团队规模只能选：" + String.join(" / ", SCALES));
        return s;
    }

    private static String normalizeDomain(String domain) {
        if (domain == null || domain.isBlank()) return null;
        String d = domain.trim().toLowerCase()
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "");
        int slash = d.indexOf('/');
        if (slash >= 0) d = d.substring(0, slash);
        if (!DOMAIN.matcher(d).matches() || !d.contains(".")) {
            throw new BizException("域名格式不对，例如 company.com");
        }
        return d;
    }

    /** 材料只接受本站上传通道产生的对象：name + path（/ai/images/tenant-materials/…）+ size，最多 5 份 */
    private static String normalizeMaterials(String materials) {
        if (materials == null || materials.isBlank()) return null;
        try {
            JSONArray arr = JSON.parseArray(materials);
            if (arr == null || arr.isEmpty()) return null;
            if (arr.size() > 5) throw new BizException("最多上传 5 份材料");
            JSONArray out = new JSONArray();
            for (Object o : arr) {
                if (!(o instanceof JSONObject src)) continue;
                String path = src.getString("path");
                if (path == null || !path.startsWith("/ai/images/tenant-materials/")) continue;
                JSONObject one = new JSONObject();
                one.put("name", trimTo(src.getString("name"), 120));
                one.put("path", path);
                one.put("size", src.getLongValue("size"));
                out.add(one);
            }
            return out.isEmpty() ? null : out.toJSONString();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("材料信息格式不对，请重新上传");
        }
    }

    /** 出参时按当前鉴权开关重新签名（入库的是纯路径，签名会过期） */
    private JSONArray signedMaterials(String json) {
        if (json == null || json.isBlank()) return new JSONArray();
        try {
            JSONArray arr = JSON.parseArray(json);
            for (Object o : arr) {
                if (o instanceof JSONObject m) {
                    String path = m.getString("path");
                    if (path != null && !path.isBlank()) m.put("url", imageUrlSigner.signUrl(path));
                }
            }
            return arr;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static List<String> suggestions(String id) {
        List<String> out = new ArrayList<>();
        for (int i = 2; i <= 3; i++) {
            String cand = id.length() > 28 ? id.substring(0, 28) + "-" + i : id + "-" + i;
            out.add(cand);
        }
        return out;
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

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
