package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.TenantMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.Tenant;
import com.wenqu.ai.model.User;
import com.wenqu.ai.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 租户注册与归属（Phase 4 · P9）。
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
    /** 缓存里「查过，确实没有这个账号」的记号（不缓存 null，否则每次都重查一遍） */
    private static final String MISS = "__no_such_user__";

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final AuditService audit;
    private final com.wenqu.ai.startup.ContentSafetySeedRunner contentSafetySeed;
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
        try {
            for (Map<String, Object> row : tenantMapper.memberCounts()) {
                Object k = row.get("tenantId");
                Object c = row.get("members");
                if (k != null) counts.put(String.valueOf(k), c instanceof Number n ? n.longValue() : 0L);
            }
        } catch (Exception e) {
            log.warn("[TENANT] 成员数统计失败（清单照出，计数留空）: {}", e.toString());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tenant t : tenants) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", t.getId());
            one.put("name", t.getName());
            one.put("status", t.getStatus() == null ? 1 : t.getStatus());
            one.put("contact", t.getContact() == null ? "" : t.getContact());
            one.put("note", t.getNote() == null ? "" : t.getNote());
            one.put("members", counts.getOrDefault(t.getId(), 0L));
            one.put("createTime", t.getCreateTime() == null ? "" : t.getCreateTime().toString());
            out.add(one);
        }
        return out;
    }

    /** 开通租户（id 由管理员起，直接就是行内 tenant_id 的取值） */
    public Tenant create(String id, String name, String contact, String note) {
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
        t.setStatus(1);
        tenantMapper.insert(t);
        // 词表按租户各一份（Phase 4），所以新租户一开就给它登记首批只告警示例：
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
        audit.record("[AUDIT] 租户启停 租户={} 状态={}", id, enabled ? "启用" : "停用");
    }

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
     */
    public String tenantOf(String uid) {
        if (uid == null || uid.isBlank() || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(uid)) return null;
        Cached hit = userTenantCache.get(uid);
        if (hit != null && hit.expireAt() > System.currentTimeMillis()) {
            return MISS.equals(hit.tenantId()) ? null : hit.tenantId();
        }
        String tid = null;
        try {
            User u = userMapper.selectById(uid);
            if (u != null) {
                tid = u.getTenantId() == null || u.getTenantId().isBlank()
                        ? TenantContext.DEFAULT_TENANT : u.getTenantId();
            }
        } catch (Exception e) {
            // 查不动归属时不猜：留 null 让这条线程按未绑定走（与改造前一致），并留痕可查
            log.warn("[TENANT] 成员归属查询失败（本轮按未绑定处理）uid={} {}", uid, e.toString());
        }
        userTenantCache.put(uid, new Cached(tid == null ? MISS : tid, System.currentTimeMillis() + CACHE_TTL_MS));
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

    /** 归属变更或成员不存在时清缓存，避免改完一分钟看不到 */
    public void invalidate(String uid) {
        if (uid != null) userTenantCache.remove(uid);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
