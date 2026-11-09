package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.mapper.MailDeliveryMapper;
import com.wenqu.ai.mapper.NotificationMapper;
import com.wenqu.ai.mapper.UserConfigMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.MailDelivery;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.User;
import com.wenqu.ai.model.UserConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * 邮件外发通道。
 *
 * <p>站内通知落库后由 {@link NotificationService} 旁路调用 {@link #dispatch}：
 * 只做「判规则 + 落台账」（快），真正的 SMTP 发送由后台扫描任务完成——通知主链路不能被
 * 一次慢连接拖住，重试也天然由扫描任务承担。
 *
 * <p>三条硬边界：总闸关 → 不落表不发；个人未勾选 → skipped；SMTP 失败只影响这一封，
 * 站内通知与 WebPush 零变化。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class MailDispatchService {

    /** 个人偏好键：允许邮件提醒的通知类型（逗号分隔；默认空=不发） */
    public static final String MAIL_TYPES_KEY = "notification.mailTypes";
    /** SMTP 口令：键名以 Password 结尾 → ConfigService 自动 RSA 加密入库、快照脱敏 */
    private static final String PASSWORD_KEY = "notify.mail.smtpPassword";
    private static final String DEFAULT_TYPES =
            "tool.approval,tool.ask,plan.approval,workflow.approval,tenant.request,model.quota,quota";

    private final MailDeliveryMapper deliveryMapper;
    private final UserMapper userMapper;
    private final UserConfigMapper userConfigMapper;
    private final NotificationMapper notificationMapper;
    private final ConfigService configService;
    private final ConfigCryptoService crypto;
    private final TenantService tenantService;
    private final UserRoleService userRoleService;
    /** 耗尽通知：延迟获取，避免 NotificationService ↔ MailDispatchService 构造循环 */
    private final ObjectProvider<NotificationService> notificationProvider;

    public MailDispatchService(MailDeliveryMapper deliveryMapper, UserMapper userMapper,
                               UserConfigMapper userConfigMapper, NotificationMapper notificationMapper,
                               ConfigService configService, ConfigCryptoService crypto,
                               TenantService tenantService, UserRoleService userRoleService,
                               ObjectProvider<NotificationService> notificationProvider) {
        this.deliveryMapper = deliveryMapper;
        this.userMapper = userMapper;
        this.userConfigMapper = userConfigMapper;
        this.notificationMapper = notificationMapper;
        this.configService = configService;
        this.crypto = crypto;
        this.tenantService = tenantService;
        this.userRoleService = userRoleService;
        this.notificationProvider = notificationProvider;
    }

    /** 邮件通道是否已开启（设置页与前端判据共用一处） */
    public boolean enabled() {
        return configService.getBoolean("notify.mail.enabled", false);
    }

    /** 个人已开启邮件提醒的通知类型（空=不发） */
    public List<String> getSelfMailTypes(String uid) {
        if (uid == null || uid.isBlank()) return List.of();
        try {
            UserConfig row = userConfigMapper.selectOne(new LambdaQueryWrapper<UserConfig>()
                    .eq(UserConfig::getUid, uid).eq(UserConfig::getConfigKey, MAIL_TYPES_KEY));
            if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) return List.of();
            return java.util.Arrays.stream(row.getConfigValue().split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 保存个人邮件提醒类型（空列表=清除该项=不再收邮件） */
    public void setSelfMailTypes(String uid, List<String> types) {
        if (uid == null || uid.isBlank()) return;
        String value = types == null ? "" : types.stream()
                .filter(java.util.Objects::nonNull).map(String::trim).filter(s -> !s.isBlank())
                .distinct().collect(java.util.stream.Collectors.joining(","));
        try {
            UserConfig row = userConfigMapper.selectOne(new LambdaQueryWrapper<UserConfig>()
                    .eq(UserConfig::getUid, uid).eq(UserConfig::getConfigKey, MAIL_TYPES_KEY));
            if (value.isEmpty()) {
                if (row != null) userConfigMapper.deleteById(row.getId());
                return;
            }
            if (row == null) {
                row = new UserConfig();
                row.setUid(uid);
                row.setConfigKey(MAIL_TYPES_KEY);
                row.setConfigValue(value);
                userConfigMapper.insert(row);
            } else {
                row.setConfigValue(value);
                userConfigMapper.updateById(row);
            }
        } catch (Exception e) {
            log.warn("[MAIL] 个人邮件偏好保存失败 uid={}: {}", uid, e.toString());
        }
    }

    /** 判规则并落台账（旁路语义：内部全捕获，绝不向上抛） */
    public void dispatch(Notification n) {
        try {
            if (n == null || n.getUid() == null || n.getUid().isBlank()) return;
            if (!enabled()) return;
            String type = n.getType();
            if (!allowedType(type)) return;

            String to = emailOf(n.getUid());
            if (to == null) return;   // 无邮箱：不发，也不必落 skipped（默认部署下几乎人人无邮箱，落表会白涨）

            if (!selfEnabled(n.getUid(), type)) {
                recordSkipped(n, to, "个人未开启该类型的邮件提醒");
                return;
            }
            String dedup = n.getDedupKey();
            if (dedup != null && !dedup.isBlank() && withinAggregateWindow(n.getUid(), type, dedup)) {
                recordSkipped(n, to, "同类通知在聚合窗口内，已合并");
                return;
            }

            MailDelivery d = new MailDelivery();
            d.setId(UUID.randomUUID().toString().replace("-", ""));
            d.setUid(n.getUid());
            d.setNotificationId(n.getId());
            d.setNotifyType(type);
            d.setDedupKey(dedup);
            d.setToAddr(to);
            d.setSubject(subjectOf(type, n.getTitle()));
            d.setStatus("pending");
            d.setAttempts(0);
            d.setNextRetryAt(LocalDateTime.now());
            deliveryMapper.insert(d);
        } catch (Exception e) {
            log.warn("[MAIL] 投递判定失败（不影响站内通知）uid={} type={} err={}",
                    n == null ? null : n.getUid(), n == null ? null : n.getType(), e.toString());
        }
    }

    /** 测试发送（管理员设置页用）：同步发一封，失败原因直接回给调用方 */
    public void sendTest(String to, String subject, String body) {
        SmtpConfig cfg = smtp();
        if (cfg.host == null || cfg.host.isBlank()) throw new IllegalArgumentException("SMTP 主机还没填");
        if (to == null || to.isBlank()) throw new IllegalArgumentException("当前管理员没有配置邮箱地址，无法接收测试邮件");
        try {
            doSend(cfg, to, subject, body);
        } catch (Exception e) {
            throw new IllegalArgumentException("发送失败：" + rootMessage(e));
        }
    }

    /**
     * 后台投递：每 30 秒扫一批到期的 pending 行。
     * <p>多副本并存时同一行可能被两个副本同时取到——SMTP 是幂等的「再发一次」，
     * 相比引入分布式租约，这里接受极少量重复（通知类邮件重复一次无实质损害）。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void deliverDue() {
        if (!enabled()) return;
        List<MailDelivery> due;
        try {
            due = deliveryMapper.selectList(new LambdaQueryWrapper<MailDelivery>()
                    .eq(MailDelivery::getStatus, "pending")
                    .le(MailDelivery::getNextRetryAt, LocalDateTime.now())
                    .orderByAsc(MailDelivery::getNextRetryAt)
                    .last("LIMIT 20"));
        } catch (Exception e) {
            log.warn("[MAIL] 扫描待投递失败: {}", e.toString());
            return;
        }
        if (due == null || due.isEmpty()) return;
        SmtpConfig cfg = smtp();
        for (MailDelivery d : due) {
            try {
                doSend(cfg, d.getToAddr(), d.getSubject(), renderBody(d));
                markSuccess(d);
            } catch (Exception e) {
                markFailure(d, rootMessage(e));
            }
        }
    }

    // ==================== 判定与发送 ====================

    private boolean allowedType(String type) {
        if (type == null) return false;
        String raw = configService.get("notify.mail.types");
        if (raw == null || raw.isBlank()) raw = DEFAULT_TYPES;
        for (String t : raw.split(",")) {
            if (t.trim().equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    private boolean selfEnabled(String uid, String type) {
        try {
            UserConfig row = userConfigMapper.selectOne(new LambdaQueryWrapper<UserConfig>()
                    .eq(UserConfig::getUid, uid).eq(UserConfig::getConfigKey, MAIL_TYPES_KEY));
            if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) return false;
            for (String t : row.getConfigValue().split(",")) {
                if (t.trim().equalsIgnoreCase(type)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean withinAggregateWindow(String uid, String type, String dedup) {
        int min = Math.max(0, ConfigDefaults.NOTIFY_MAIL_MIN_INTERVAL_MS);
        if (min <= 0) return false;
        LocalDateTime since = LocalDateTime.now().minusNanos(min * 1_000_000L);
        try {
            Long n = deliveryMapper.selectCount(new LambdaQueryWrapper<MailDelivery>()
                    .eq(MailDelivery::getUid, uid)
                    .eq(MailDelivery::getNotifyType, type)
                    .eq(MailDelivery::getDedupKey, dedup)
                    .in(MailDelivery::getStatus, List.of("pending", "success"))
                    .ge(MailDelivery::getCreateTime, since));
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String emailOf(String uid) {
        try {
            User u = userMapper.selectById(uid);
            String e = u == null ? null : u.getEmail();
            return e == null || e.isBlank() ? null : e.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void recordSkipped(Notification n, String to, String reason) {
        try {
            MailDelivery d = new MailDelivery();
            d.setId(UUID.randomUUID().toString().replace("-", ""));
            d.setUid(n.getUid());
            d.setNotificationId(n.getId());
            d.setNotifyType(n.getType());
            d.setDedupKey(n.getDedupKey());
            d.setToAddr(to);
            d.setSubject(subjectOf(n.getType(), n.getTitle()));
            d.setStatus("skipped");
            d.setAttempts(0);
            d.setLastError(reason);
            deliveryMapper.insert(d);
        } catch (Exception e) {
            log.debug("[MAIL] skipped 留痕失败: {}", e.toString());
        }
    }

    private void markSuccess(MailDelivery d) {
        try {
            MailDelivery patch = new MailDelivery();
            patch.setId(d.getId());
            patch.setStatus("success");
            patch.setAttempts((d.getAttempts() == null ? 0 : d.getAttempts()) + 1);
            patch.setLastError(null);
            deliveryMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("[MAIL] 更新投递状态失败 id={}: {}", d.getId(), e.toString());
        }
    }

    private void markFailure(MailDelivery d, String err) {
        int attempts = (d.getAttempts() == null ? 0 : d.getAttempts()) + 1;
        int[] delays = retryDelays();
        boolean exhausted = attempts > delays.length;
        try {
            LambdaUpdateWrapper<MailDelivery> w = new LambdaUpdateWrapper<MailDelivery>()
                    .eq(MailDelivery::getId, d.getId())
                    .set(MailDelivery::getAttempts, attempts)
                    .set(MailDelivery::getLastError, trunc(err, 500));
            if (exhausted) {
                w.set(MailDelivery::getStatus, "exhausted");
                w.set(MailDelivery::getNextRetryAt, null);
            } else {
                w.set(MailDelivery::getStatus, "pending");
                w.set(MailDelivery::getNextRetryAt, LocalDateTime.now().plusSeconds(delays[attempts - 1]));
            }
            deliveryMapper.update(null, w);
        } catch (Exception e) {
            log.warn("[MAIL] 更新失败状态失败 id={}: {}", d.getId(), e.toString());
        }
        if (exhausted) {
            log.warn("[MAIL] 投递重试耗尽 id={} uid={} to={}: {}", d.getId(), d.getUid(), d.getToAddr(), err);
            notifyAdmin(d, err);
        } else {
            log.info("[MAIL] 投递失败将重试 id={} attempt={} err={}", d.getId(), attempts, err);
        }
    }

    private void notifyAdmin(MailDelivery d, String err) {
        try {
            NotificationService ns = notificationProvider.getIfAvailable();
            if (ns == null) return;
            List<String> admins = com.wenqu.ai.util.TenantContext.asPlatform(() -> {
                List<String> out = new ArrayList<>();
                for (User u : userMapper.selectList(null)) {
                    if (userRoleService.isAdmin(u.getUid())) out.add(u.getUid());
                }
                return out;
            });
            for (String uid : admins) {
                ns.create(uid, Notification.TYPE_OPS_ALERT, "邮件外发失败：重试已耗尽",
                        "收件人 " + d.getToAddr() + " · " + trunc(err, 200), "settings", "notify-mail", null, null);
            }
        } catch (Exception e) {
            log.debug("[MAIL] 耗尽通知管理员失败: {}", e.toString());
        }
    }

    private SmtpConfig smtp() {
        SmtpConfig c = new SmtpConfig();
        c.host = configService.get("notify.mail.host");
        c.port = configService.getInt("notify.mail.port", 587);
        c.security = configService.get("notify.mail.security");
        c.username = configService.get("notify.mail.username");
        c.from = configService.get("notify.mail.from");
        c.fromName = configService.get("notify.mail.fromName");
        c.password = decryptPassword();
        return c;
    }

    private String decryptPassword() {
        try {
            String stored = configService.get(PASSWORD_KEY);
            return stored == null || stored.isBlank() ? null : stored;
        } catch (Exception e) {
            log.warn("[MAIL] SMTP 口令读取失败: {}", e.toString());
            return null;
        }
    }

    private void doSend(SmtpConfig cfg, String to, String subject, String html) throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(cfg.host);
        sender.setPort(cfg.port <= 0 ? 587 : cfg.port);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (cfg.username != null && !cfg.username.isBlank()) {
            sender.setUsername(cfg.username);
            sender.setPassword(cfg.password == null ? "" : cfg.password);
        }
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(cfg.username != null && !cfg.username.isBlank()));
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.writetimeout", "15000");
        String sec = cfg.security == null ? "starttls" : cfg.security.trim().toLowerCase();
        if ("ssl".equals(sec)) {
            p.put("mail.smtp.ssl.enable", "true");
        } else if ("starttls".equals(sec)) {
            p.put("mail.smtp.starttls.enable", "true");
        }
        MimeMessageHelper msg = new MimeMessageHelper(sender.createMimeMessage(), false, StandardCharsets.UTF_8.name());
        String from = cfg.from == null || cfg.from.isBlank() ? cfg.username : cfg.from;
        if (from == null || from.isBlank()) throw new IllegalStateException("发件人地址还没填");
        String name = cfg.fromName == null || cfg.fromName.isBlank() ? "问渠" : cfg.fromName;
        msg.setFrom(from, name);
        msg.setTo(to);
        // MimeMessageHelper 已在构造时指定 UTF-8，主题走单参重载（双参重载在当前 Spring 版本不存在）
        msg.setSubject(subject);
        msg.setText(html, true);
        sender.send(msg.getMimeMessage());
    }

    private String renderBody(MailDelivery d) {
        String tenantName = tenantNameSafe();
        String content = "";
        try {
            if (d.getNotificationId() != null && !d.getNotificationId().isBlank()) {
                Notification n = notificationMapper.selectById(d.getNotificationId());
                if (n != null && n.getContent() != null) content = n.getContent();
            }
        } catch (Exception ignored) {
        }
        String link = deepLink(d);
        String prefix = hasPrefix(d.getSubject()) ? "" : "";
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>")
                .append("<body style=\"margin:0;padding:24px;background:#f7f8fa;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif\">")
                .append("<div style=\"max-width:600px;margin:0 auto;background:#fff;border:1px solid #e6e9ed;border-radius:10px;overflow:hidden\">")
                .append("<div style=\"padding:20px 28px 0;color:#98a0aa;font-size:12px\">").append(escape(tenantName)).append(" · 问渠</div>")
                .append("<div style=\"padding:14px 28px 0;font-size:18px;font-weight:600;color:#1f2329\">")
                .append(escape(stripPrefix(d.getSubject()))).append("</div>")
                .append("<div style=\"padding:10px 28px 0;font-size:13px;line-height:1.8;color:#5f6570\">")
                .append(escape(trunc(content, 300))).append("</div>");
        if (link != null) {
            sb.append("<div style=\"padding:20px 28px 0\"><a href=\"").append(escape(link))
                    .append("\" style=\"display:inline-block;height:36px;line-height:36px;padding:0 20px;border-radius:7px;")
                    .append("background:#2e6be6;color:#fff;font-size:13px;text-decoration:none\">去处理</a></div>");
        }
        sb.append("<div style=\"margin:24px 0 0;padding:16px 28px 20px;border-top:1px solid #e6e9ed;color:#98a0aa;font-size:11.5px;line-height:1.8\">")
                .append("你收到这封邮件，是因为你在个人设置 → 通知偏好中开启了该类型的邮件提醒。<br>")
                .append("可随时在那里关闭；站内通知不受影响。</div></div></body></html>");
        return sb.toString();
    }

    private String deepLink(MailDelivery d) {
        String base = configService.get("notify.mail.webBaseUrl");
        if (base == null || base.isBlank()) return null;
        base = base.trim().replaceAll("/+$", "");
        String type = d.getNotifyType() == null ? "" : d.getNotifyType();
        String path;
        if (type.startsWith("tool.") || type.startsWith("plan.") || type.startsWith("workflow.")) path = "/notifications";
        else if (type.startsWith("parse.")) path = "/knowledge";
        else if (type.startsWith("model.") || type.equals("quota")) path = "/agents?tab=providers";
        else path = "/notifications";
        return base + path;
    }

    private String tenantNameSafe() {
        try {
            String t = com.wenqu.ai.util.TenantContext.current();
            return tenantService.nameOf(t == null ? com.wenqu.ai.util.TenantContext.DEFAULT_TENANT : t);
        } catch (Exception e) {
            return "问渠";
        }
    }

    private String subjectOf(String type, String title) {
        String t = title == null ? "通知" : title;
        if (type == null) return t;
        if (type.equals("tool.approval") || type.equals("tool.ask") || type.equals("plan.approval")
                || type.equals("workflow.approval") || type.equals("tenant.request")) {
            return hasPrefix(t) ? t : "待处理：" + t;
        }
        if (type.endsWith(".failed") || type.endsWith(".timeout")) {
            return hasPrefix(t) ? t : "失败：" + t;
        }
        if (type.equals("model.quota") || type.equals("quota")) {
            return hasPrefix(t) ? t : "额度：" + t;
        }
        return t;
    }

    private static boolean hasPrefix(String s) {
        return s != null && (s.startsWith("待处理：") || s.startsWith("失败：") || s.startsWith("额度："));
    }

    private static String stripPrefix(String s) {
        if (s == null) return "";
        return s.replaceFirst("^(待处理：|失败：|额度：)", "");
    }

    private int[] retryDelays() {
        String raw = ConfigDefaults.NOTIFY_MAIL_RETRY_DELAYS_SECONDS;
        if (raw == null || raw.isBlank()) raw = "60,300,900";
        List<Integer> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            try {
                int v = Integer.parseInt(s.trim());
                if (v > 0) out.add(v);
            } catch (Exception ignored) {
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** SMTP 配置快照（每次发送按当前配置构建，不在启动期固化） */
    private static final class SmtpConfig {
        private String host;
        private int port = 587;
        private String security = "starttls";
        private String username;
        private String password;
        private String from;
        private String fromName;
    }
}
