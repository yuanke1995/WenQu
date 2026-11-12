package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.common.TenantResolutionException;
import com.wenqu.ai.mapper.TenantMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Tenant;
import com.wenqu.ai.model.User;
import com.wenqu.ai.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 租户注册与归属。
 *
 * <p>三件事：保证 {@code default} 租户永远在（单租户部署也走同一套，不留「无租户」这条第二路径）；
 * 给管理端开通/停用租户；把成员挪到某个租户。
 *
 * <p><b>成员归属变更只改账号，不搬资源</b>：他名下的会话、知识库、工作流留在原租户。
 * 自动搬资源听着贴心，实际是把「谁的文件归谁」变成一次点击就能改写的隐式行为——真要合并，
 * 走显式的资源转移，不藏在改归属里。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    /** 租户标识：小写字母数字开头，允许 -_，长度 2-32（它就是行内 tenant_id 的取值，别放奇形怪状的字符） */
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,31}$");
    /** 成员归属缓存 TTL：与请求身份缓存同档（60 秒），改归属是低频动作，最迟一分钟后各副本跟上 */
    private static final long CACHE_TTL_MS = 60_000;

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final AuditService audit;
    private final JdbcTemplate jdbcTemplate;
    private final com.wenqu.ai.startup.ContentSafetySeedRunner contentSafetySeed;
    private final com.wenqu.ai.startup.RbacSeedRunner rbacSeedRunner;
    private final com.wenqu.ai.service.contentsafety.ContentSafetyService contentSafety;

    private record Cached(String tenantId, long expireAt) {
    }

    private final Map<String, Cached> userTenantCache = new ConcurrentHashMap<>();

    /** 默认租户永远在：单租户部署也占这一行，行为与加列前一致（谓词恒为 tenant_id='default'） */
    public void ensureDefault() {
        if (tenantMapper.selectById(TenantContext.DEFAULT_TENANT) != null) return;
        Tenant t = new Tenant();
        t.setId(TenantContext.DEFAULT_TENANT);
        t.setName("默认租户");
        t.setStatus(1);
        t.setNote("单租户部署的全部数据都归这里；存量行加列时按默认值回填，无需搬迁");
        tenantMapper.insert(t);
        log.info("[TENANT] 已登记默认租户（{}）：单租户部署不需要做任何迁移", TenantContext.DEFAULT_TENANT);
    }

    /**
     * 租户清单（含成员数），管理页用。
     * <p>整段跑在平台视图里：成员数要跨租户统计 {@code c_ai_user}，不切视图就只数得见自己一家。
     */
    public List<Map<String, Object>> listWithCounts() {
        return TenantContext.asPlatform(this::listWithCountsPlatformView);
    }

    private List<Map<String, Object>> listWithCountsPlatformView() {
        List<Tenant> tenants = tenantMapper.selectList(new QueryWrapper<Tenant>().orderByAsc("create_time"));
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> tokens = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : tenantMapper.memberCounts()) {
                Object k = row.get("tenantId");
                Object c = row.get("members");
                if (k != null) counts.put(String.valueOf(k), c instanceof Number n ? n.longValue() : 0L);
            }
        } catch (Exception e) {
            log.warn("[TENANT] 成员数统计失败（清单照出，计数留空）: {}", e.toString());
        }
        try {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select tenant_id as tenantId, coalesce(sum(total_tokens), 0) as tokens from c_ai_usage_log "
                            + "where create_time >= DATE_FORMAT(CURDATE(), '%Y-%m-01') group by tenant_id")) {
                Object k = row.get("tenantId");
                Object v = row.get("tokens");
                if (k != null) tokens.put(String.valueOf(k), v instanceof Number n ? n.longValue() : 0L);
            }
        } catch (Exception e) {
            log.warn("[TENANT] 本月用量统计失败（清单照出，用量留空）: {}", e.toString());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tenant t : tenants) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", t.getId());
            one.put("name", t.getName());
            one.put("status", t.getStatus() == null ? 1 : t.getStatus());
            one.put("contact", t.getContact() == null ? "" : t.getContact());
            one.put("note", t.getNote() == null ? "" : t.getNote());
            one.put("brandName", t.getBrandName() == null ? "" : t.getBrandName());
            one.put("brandLogo", t.getBrandLogo() == null ? "" : t.getBrandLogo());
            one.put("brandColor", t.getBrandColor() == null ? "" : t.getBrandColor());
            one.put("loginHint", t.getLoginHint() == null ? "" : t.getLoginHint());
            one.put("plan", t.getPlan() == null ? "standard" : t.getPlan());
            one.put("domain", t.getDomain() == null ? "" : t.getDomain());
            one.put("archiveTime", t.getArchiveTime() == null ? "" : t.getArchiveTime().toString());
            one.put("archiveBy", t.getArchiveBy() == null ? "" : t.getArchiveBy());
            one.put("members", counts.getOrDefault(t.getId(), 0L));
            one.put("tokensThisMonth", tokens.getOrDefault(t.getId(), 0L));
            one.put("createTime", t.getCreateTime() == null ? "" : t.getCreateTime().toString());
            out.add(one);
        }
        return out;
    }

    /** 开通租户（id 由管理员起，直接就是行内 tenant_id 的取值） */
    public Tenant create(String id, String name, String contact, String note) {
        return create(id, name, contact, note, null, null);
    }

    /** 开通租户（可带套餐与企业域名；P2 套餐与 SSO 用） */
    public Tenant create(String id, String name, String contact, String note, String plan, String domain) {
        String tid = id == null ? "" : id.trim().toLowerCase();
        if (!ID.matcher(tid).matches()) {
            throw new BizException("租户标识要用 2-32 位小写字母、数字、- 或 _（它直接作为数据归属的标识）");
        }
        if (tenantMapper.selectById(tid) != null) throw new BizException("租户「" + tid + "」已存在");
        if (name == null || name.isBlank()) throw new BizException("要给租户起个显示名");
        Tenant t = new Tenant();
        t.setId(tid);
        t.setName(name.trim());
        t.setContact(contact == null ? null : contact.trim());
        t.setNote(note == null || note.isBlank() ? null : note.trim());
        t.setPlan(normalizePlan(plan));
        t.setDomain(blankToNull(domain));
        t.setStatus(1);
        tenantMapper.insert(t);
        // 新租户开通即种入角色域（内置三件套 + user 基础菜单 + 受限管理员预设）——
        // 不种的话该租户用户一登录就是零角色（角色表按租户过滤，default 套里的角色看不见）
        try {
            rbacSeedRunner.seedTenantRoles(t.getId(), java.util.Set.of());
        } catch (Exception e) {
            log.warn("[TENANT] 租户 {} 的角色种子失败（可在权限页手工补建）: {}", t.getId(), e.getMessage());
        }
        // 词表按租户各一份，所以新租户一开就给它登记首批只告警示例：
        // 空表的新租户看不出闸门在不在跑，管理员只能靠真发一句话去猜。失败不阻断开通——
        // 规则随时能在界面上补，租户开不出来才是事故。
        try {
            contentSafetySeed.seedBaseline(tid);
            contentSafety.invalidateRules();
        } catch (Exception e) {
            log.warn("[TENANT] 新租户首批内容安全规则登记失败 tenant={}（可在「权限管理 → 内容安全」里手工补）: {}",
                    tid, e.toString());
        }
        audit.record("[AUDIT] 租户开通 租户={} 名称={} 联系人={}", tid, t.getName(), nullToEmpty(contact));
        applyPlanQuota(t.getId(), t.getPlan());
        return t;
    }

    /** 启停租户：停用只挡登录，数据一律保留（误停可恢复，删租户才是不可逆动作，本平台不提供） */
    public void setStatus(String id, boolean enabled) {
        if (TenantContext.DEFAULT_TENANT.equals(id) && !enabled) {
            throw new BizException("默认租户不能停用：单租户部署的全部数据都挂在它名下，停了等于停掉整个平台");
        }
        Tenant t = tenantMapper.selectById(id);
        if (t == null) throw new BizException("租户不存在：" + id);
        t.setStatus(enabled ? 1 : 0);
        tenantMapper.updateById(t);
        statusCache.remove(id);   // 本平台刚按下的那一下要立刻生效；别的副本最迟一分钟后跟上
        audit.record("[AUDIT] 租户启停 租户={} 状态={}", id, enabled ? "启用" : "停用");
    }

    /**
     * 归档 / 恢复。
     * <p>归档 = 状态 2：成员不可登录、数据只读、可导出、可恢复；彻底清除仍走运维脚本。
     * 刻意不做「删除租户」按钮——行级隔离下删租户等于按归属删光它的全部数据。
     */
    public void setArchived(String id, boolean archived) {
        if (TenantContext.DEFAULT_TENANT.equals(id)) {
            throw new BizException("默认租户不能归档：单租户部署的全部数据都挂在它名下");
        }
        Tenant t = tenantMapper.selectById(id);
        if (t == null) throw new BizException("租户不存在：" + id);
        // updateById 默认跳过 null 字段：恢复时必须显式 set null，否则 archive_time/archive_by 永远留着
        tenantMapper.update(null, new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, id)
                .set(Tenant::getStatus, archived ? 2 : 1)
                .set(Tenant::getArchiveTime, archived ? java.time.LocalDateTime.now() : null)
                .set(Tenant::getArchiveBy, archived ? com.wenqu.ai.util.RequestUser.uid() : null));
        statusCache.remove(id);
        audit.record("[AUDIT] 租户{} 租户={}", archived ? "归档" : "恢复", id);
    }

    /**
     * 这个租户当前是否可用（1=启用；行不存在按不可用处理——归属指向一个查无此家的租户，
     * 不能算「可以进来」）。默认租户恒可用：它不能停用，也就不必为每次登录多查一行。
     * <p>「停用只挡该租户成员登录」是手册与接口描述写下的承诺，承诺要有<b>一个</b>落点：
     * 散到密码登录、SSO 认领、SCIM 推送、目录同步四处各写一遍，漏一处就等于那条路照常进人。
     * <p>读的是 60 秒缓存（与成员归属同一档）：登录是每个成员每次都问，启停是极低频动作；
     * 代价写明白——在别的管理员副本上刚改的启停，最迟 60 秒后才在这里咬住。
     */
    public boolean isActive(String tenantId) {
        String t = tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT_TENANT : tenantId.trim();
        if (TenantContext.DEFAULT_TENANT.equals(t)) return true;
        CachedStatus hit = statusCache.get(t);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expireAt() > now) return hit.active();
        Tenant row = tenantMapper.selectById(t);
        boolean active = row != null && row.getStatus() != null && row.getStatus() == 1;
        statusCache.put(t, new CachedStatus(active, now + CACHE_TTL_MS));
        return active;
    }

    /** {@link #isActive} 的守门版：文案要点名是租户被平台停用，别让人以为是自己密码敲错 */
    public void requireActive(String tenantId) {
        String t = tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT_TENANT : tenantId.trim();
        if (!isActive(t)) {
            throw new BizException("该租户（" + t + "）已被平台停用，成员暂时无法登录，请联系平台管理员");
        }
    }

    private record CachedStatus(boolean active, long expireAt) {
    }

    private final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();

    /**
     * 归一并校验要写进 {@code tenant_id} 的归属（成员、认证源等资源类行共用这一条判据）。
     * <p>留空＝沿用当前线程的租户（单租户部署下永远是 default，界面上不必出现这个选择）；
     * 指向别的租户只有平台级管理员能做——租户管理员把资源挪出本租户等于把数据送出去。
     * 表 {@code c_ai_tenant} 属平台全局表，这里不需要切平台视图，直接按 id 判存在。
     */
    public String requireAssignable(String tenantId, String currentOwner) {
        if (tenantId == null || tenantId.isBlank()) {
            String bound = TenantContext.current();
            if (bound != null) return bound;
            return currentOwner == null || currentOwner.isBlank()
                    ? TenantContext.DEFAULT_TENANT : currentOwner;
        }
        String t = tenantId.trim();
        if (tenantMapper.selectById(t) == null) throw new BizException("租户不存在：" + t);
        String bound = TenantContext.current();
        if (bound != null && !bound.equals(t) && !com.wenqu.ai.util.RequestUser.superadmin()) {
            throw new BizException("把资源归属到其它租户仅平台级管理员可执行");
        }
        return t;
    }

    /** 是否存在第二个租户（界面与报表据此决定「租户」这一维要不要出现） */
    public boolean multiTenant() {
        try {
            return tenantMapper.selectCount(null) > 1;
        } catch (Exception e) {
            log.warn("[TENANT] 租户计数失败（按单租户处理）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 把成员挪到某个租户（平台视图：目标租户的行本来就看不见，不切视图连读都读不到）。
     * <p>只改账号归属，不动他已产出的资源——理由见类注释。
     */
    public void moveMember(String uid, String tenantId) {
        if (uid == null || uid.isBlank()) throw new BizException("要给一位成员改归属");
        if (tenantMapper.selectById(tenantId) == null) throw new BizException("租户不存在：" + tenantId);
        TenantContext.asPlatform(() -> {
            User u = userMapper.selectById(uid);
            if (u == null) throw new BizException("成员不存在：" + uid);
            String from = u.getTenantId() == null || u.getTenantId().isBlank()
                    ? TenantContext.DEFAULT_TENANT : u.getTenantId();
            if (tenantId.equals(from)) return null;
            u.setTenantId(tenantId);
            userMapper.updateById(u);
            invalidate(uid);
            // 逐出请求侧的用户档案缓存：那里缓存的是整行，不逐出就有 60 秒「人已经走了、
            // 读的还是旧租户口径」的跨租户窗口（判据见 UserContextInterceptor.evict）
            com.wenqu.ai.config.UserContextInterceptor.evict(uid);
            audit.record("[AUDIT] 成员归属变更 成员={} 从租户={} 到租户={}", uid, from, tenantId);
            return null;
        });
    }

    /**
     * 某成员属于哪个租户；查不到这个人返回 null（区别于「有这个人但没填归属」）。
     * <p>这是 {@link TenantContext} 的兜底解析口：线程上只要重放过身份，租户就跟着走，
     * 不要求每个重放点都记得再绑一次（漏一处等于那条线程能看全平台，正是要防的静默错位）。
     * <p>查得到但归属为空时按 {@code default} 处理——存量行加列时由数据库默认值回填，真出现空值
     * 也是「这个账号还没搬」，不是「它可以看所有租户」。
     * <p><b>查不动归属时 fail-closed</b>：不缓存、不返回 null，直接抛出
     * {@link TenantResolutionException}。返回 null 会让调用方按「未绑定」放行，等于一次 DB 抖动
     * 换来整条请求跨租户读写；账号确实不存在时同样不缓存负结果，让下一次请求重新判定。
     */
    public String tenantOf(String uid) {
        if (uid == null || uid.isBlank() || com.wenqu.ai.util.RequestUser.SYSTEM.equals(uid)) return null;
        Cached hit = userTenantCache.get(uid);
        if (hit != null && hit.expireAt() > System.currentTimeMillis()) {
            return hit.tenantId();
        }
        User u;
        try {
            u = userMapper.selectById(uid);
        } catch (Exception e) {
            log.error("[TENANT] 成员归属查询失败，拒绝本次请求 uid={}", uid, e);
            throw new TenantResolutionException("租户归属解析失败，请稍后重试");
        }
        String tid = u == null ? null
                : (u.getTenantId() == null || u.getTenantId().isBlank()
                ? TenantContext.DEFAULT_TENANT : u.getTenantId());
        if (tid != null) {
            userTenantCache.put(uid, new Cached(tid, System.currentTimeMillis() + CACHE_TTL_MS));
        }
        return tid;
    }

    /** 装上身份 → 租户的兜底解析口（启动期一次） */
    @jakarta.annotation.PostConstruct
    public void installResolver() {
        TenantContext.setResolver(this::tenantOf);
    }

    /** 标识是否已被占用（开通与申请批准共用的判据：tenant_id 直接写在业务行上，撞了就串家） */
    public boolean exists(String id) {
        return id != null && !id.isBlank() && tenantMapper.selectById(id.trim()) != null;
    }

    /** 租户显示名（界面上一句「你现在看的是哪个租户的数据」）；查不到时回标识本身 */
    public String nameOf(String id) {
        if (id == null || id.isBlank()) return TenantContext.DEFAULT_TENANT;
        Tenant t = tenantMapper.selectById(id);
        return t == null || t.getName() == null || t.getName().isBlank() ? id : t.getName();
    }

    /**
     * 登录页品牌：匿名单入口。
     * <p>不存在 / 停用 / 归档的租户回落 {@code default} 的品牌——登录页不能因为一个错子域就打不开；
     * 是否可用仍由登录口令校验后的 {@link #requireActive} 咬住。
     */
    public Map<String, Object> brandOf(String tenantId) {
        String id = tenantId == null || tenantId.isBlank() ? TenantContext.DEFAULT_TENANT : tenantId.trim().toLowerCase();
        Tenant t = tenantMapper.selectById(id);
        boolean fallback = t == null || (t.getStatus() != null && t.getStatus() != 1);
        if (fallback) {
            id = TenantContext.DEFAULT_TENANT;
            t = tenantMapper.selectById(id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", id);
        out.put("name", t == null || t.getName() == null ? "" : t.getName());
        out.put("brandName", t == null || t.getBrandName() == null ? "" : t.getBrandName());
        out.put("brandLogo", t == null || t.getBrandLogo() == null ? "" : t.getBrandLogo());
        out.put("brandColor", t == null || t.getBrandColor() == null ? "" : t.getBrandColor());
        out.put("loginHint", t == null || t.getLoginHint() == null ? "" : t.getLoginHint());
        out.put("available", !fallback);
        return out;
    }

    /** 更新租户品牌（平台级管理员；空串=恢复默认） */
    public void updateBrand(String id, String brandName, String brandLogo, String brandColor, String loginHint) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) throw new BizException("租户不存在：" + id);
        // 显式 set null：updateById 会跳过 null，空串就清不掉旧品牌，登录页会一直挂着上一次的配置
        tenantMapper.update(null, new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, id)
                .set(Tenant::getBrandName, blankToNull(brandName))
                .set(Tenant::getBrandLogo, blankToNull(brandLogo))
                .set(Tenant::getBrandColor, blankToNull(brandColor))
                .set(Tenant::getLoginHint, blankToNull(loginHint)));
        audit.record("[AUDIT] 更新租户品牌 租户={}", id);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isBlank() ? null : s.trim();
    }

    /** 归属变更或成员不存在时清缓存，避免改完一分钟看不到 */
    public void invalidate(String uid) {
        if (uid != null) userTenantCache.remove(uid);
    }

    // ==================== 平台概览 / 租户详情 / 用量 / 套餐 ====================

    /** 平台首页指标：租户总量、启停归档、待审申请、本月新增 */
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", countOf("select count(*) from c_ai_tenant"));
        out.put("active", countOf("select count(*) from c_ai_tenant where status = 1"));
        out.put("suspended", countOf("select count(*) from c_ai_tenant where status = 0"));
        out.put("archived", countOf("select count(*) from c_ai_tenant where status = 2"));
        out.put("pendingRequests", countOf("select count(*) from c_ai_tenant_request where status = 'pending'"));
        out.put("newThisMonth", countOf(
                "select count(*) from c_ai_tenant where create_time >= DATE_FORMAT(CURDATE(), '%Y-%m-01')"));
        out.put("oldestPendingHours", countOf(
                "select coalesce(timestampdiff(hour, min(create_time), now()), 0) "
                        + "from c_ai_tenant_request where status = 'pending'"));
        return out;
    }

    /** 租户详情：注册信息 + 品牌 + 套餐 + 计数（平台视图，跨租户统计是显式平台动作） */
    public Map<String, Object> detail(String id) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) throw new BizException("租户不存在：" + id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("name", t.getName());
        out.put("status", t.getStatus() == null ? 1 : t.getStatus());
        out.put("contact", t.getContact() == null ? "" : t.getContact());
        out.put("note", t.getNote() == null ? "" : t.getNote());
        out.put("plan", normalizePlan(t.getPlan()));
        out.put("domain", t.getDomain() == null ? "" : t.getDomain());
        out.put("ssoRequested", t.getSsoRequested() != null && t.getSsoRequested() == 1);
        out.put("brandName", t.getBrandName() == null ? "" : t.getBrandName());
        out.put("brandLogo", t.getBrandLogo() == null ? "" : t.getBrandLogo());
        out.put("brandColor", t.getBrandColor() == null ? "" : t.getBrandColor());
        out.put("loginHint", t.getLoginHint() == null ? "" : t.getLoginHint());
        out.put("archiveTime", t.getArchiveTime() == null ? "" : t.getArchiveTime().toString());
        out.put("archiveBy", t.getArchiveBy() == null ? "" : t.getArchiveBy());
        out.put("createTime", t.getCreateTime() == null ? "" : t.getCreateTime().toString());
        out.putAll(usageOf(id));
        out.put("plan", normalizePlan(t.getPlan()));
        out.put("limits", planLimits(t.getPlan()));
        return out;
    }

    /** 租户维度用量（成员 / 知识库 / 文档 / 会话 / 本月 tokens / 最近活跃） */
    public Map<String, Object> usage(String id) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) throw new BizException("租户不存在：" + id);
        Map<String, Object> out = usageOf(id);
        out.put("plan", normalizePlan(t.getPlan()));
        out.put("limits", planLimits(t.getPlan()));
        return out;
    }

    private Map<String, Object> usageOf(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("members", tenantCount("c_ai_user", id));
        out.put("knowledgeBases", tenantCount("c_ai_knowledge_base", id));
        out.put("documents", tenantCount("c_ai_document", id));
        out.put("sessions", tenantCount("c_ai_session", id));
        out.put("tokensThisMonth", countOf(
                "select coalesce(sum(total_tokens), 0) from c_ai_usage_log "
                        + "where tenant_id = ? and create_time >= DATE_FORMAT(CURDATE(), '%Y-%m-01')", id));
        Object last = oneOf("select max(create_time) from c_ai_usage_log where tenant_id = ?", id);
        out.put("lastActive", last == null ? "" : String.valueOf(last));
        return out;
    }

    /** 开通时标记「需要单点登录」，详情页据此提示去认证源配置 */
    public void markSsoRequested(String tenantId) {
        Tenant t = tenantMapper.selectById(tenantId);
        if (t == null) return;
        t.setSsoRequested(1);
        tenantMapper.updateById(t);
    }

    /** 套餐与企业域名（P2）：套餐同时落到租户级月配额，保证「显示的上限」和「真正拦的上限」是同一个数 */
    public void updatePlan(String id, String plan, String domain) {
        Tenant t = tenantMapper.selectById(id);
        if (t == null) throw new BizException("租户不存在：" + id);
        String p = normalizePlan(plan);
        tenantMapper.update(null, new LambdaUpdateWrapper<Tenant>()
                .eq(Tenant::getId, id)
                .set(Tenant::getPlan, p)
                .set(Tenant::getDomain, blankToNull(domain)));
        applyPlanQuota(id, p);
        audit.record("[AUDIT] 租户套餐更新 租户={} 套餐={} 域名={}", id, p,
                domain == null || domain.isBlank() ? "（空）" : domain.trim());
    }

    /** 套餐 → 租户级月 token 配额（upsert；已有人工规则时保留其金额/用途，只对齐 token 上限） */
    private void applyPlanQuota(String tenantId, String plan) {
        long limit = ((Number) planLimits(plan).getOrDefault("monthlyTokens", 5_000_000L)).longValue();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select id from c_ai_quota where tenant_id = ? and scope = 'tenant' and period = 'month' "
                            + "order by (scope_id is null or scope_id = '') desc limit 1", tenantId);
            if (rows.isEmpty()) {
                // scope_id 用租户标识：既不影响 matchesScope（tenant 档不看 scope_id），
                // 又能避开存量唯一键 (scope, scope_id, period) 不包含 tenant_id 带来的跨租户冲突
                jdbcTemplate.update("insert into c_ai_quota (tenant_id, id, scope, scope_id, period, token_limit, "
                                + "enforce, remark, create_time, update_time) values (?,?, 'tenant', ?, 'month', ?, 1, ?, now(), now())",
                        tenantId, java.util.UUID.randomUUID().toString().replace("-", ""), tenantId, limit,
                        "套餐自动配额（" + plan + "）");
            } else {
                jdbcTemplate.update("update c_ai_quota set token_limit = ?, remark = ?, update_time = now() where id = ?",
                        limit, "套餐自动配额（" + plan + "）", String.valueOf(rows.get(0).get("id")));
            }
        } catch (Exception e) {
            log.warn("[TENANT] 套餐配额落库失败（展示不受影响，拦截仍按原配额）tenant={} plan={}: {}",
                    tenantId, plan, e.toString());
        }
    }

    /** 套餐档位：token/成员上限。trial 用于试用，dedicated 用于专属部署口径 */
    public Map<String, Object> planLimits(String plan) {
        String p = normalizePlan(plan);
        Map<String, Object> m = new LinkedHashMap<>();
        switch (p) {
            case "trial" -> {
                m.put("label", "试用");
                m.put("monthlyTokens", 500_000L);
                m.put("members", 10);
            }
            case "dedicated" -> {
                m.put("label", "专属");
                m.put("monthlyTokens", 50_000_000L);
                m.put("members", 1000);
            }
            default -> {
                m.put("label", "标准");
                m.put("monthlyTokens", 5_000_000L);
                m.put("members", 100);
            }
        }
        return m;
    }

    private static String normalizePlan(String plan) {
        String p = plan == null ? "" : plan.trim().toLowerCase();
        return switch (p) {
            case "trial", "dedicated" -> p;
            default -> "standard";
        };
    }

    private long tenantCount(String table, String tenantId) {
        return countOf("select count(*) from `" + table + "` where tenant_id = ?", tenantId);
    }

    private long countOf(String sql, Object... args) {
        try {
            Number n = jdbcTemplate.queryForObject(sql, Number.class, args);
            return n == null ? 0L : n.longValue();
        } catch (Exception e) {
            log.warn("[TENANT] 统计失败（按 0 处理）: {} - {}", sql, e.getMessage());
            return 0L;
        }
    }

    private Object oneOf(String sql, Object... args) {
        try {
            return jdbcTemplate.queryForObject(sql, Object.class, args);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
