package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AnnouncementMapper;
import com.wenqu.ai.model.Announcement;
import com.wenqu.ai.util.RequestUser;
import com.wenqu.ai.util.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 平台公告：发布 / 下线 / 删除 / 成员侧读取 / 已读回执。
 * <p>
 * 跨租户口径与配额、成员名单一致（{@code TenantContext.asPlatformIfSuper}）：平台级管理员管全部租户的
 * 公告，租户管理员只管本租户的。成员侧读取（登录后横幅）走普通租户过滤——每人只看到本租户发布中、
 * 且在时间窗口内、命中自己部门/角色的公告。
 * <p>
 * 公告支持定时上下线、按部门/角色定向、已读回执与正文格式。
 * 已读口径刻意不取「渲染即已读」：横幅渲染只证明它出现过，关闭或展开详情
 * 才证明人真的处理过。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementMapper mapper;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    /** 管理清单：平台级跨租户看全量，租户管理员看本租户（下线的也在，历史可查） */
    public List<Announcement> adminList() {
        List<Announcement> list = mapper.selectList(new LambdaQueryWrapper<Announcement>()
                .orderByDesc(Announcement::getCreateTime));
        Map<String, Long> readCounts = readCountsOf(list);
        for (Announcement a : list) {
            a.setReadCount(readCounts.getOrDefault(a.getId(), 0L));
            a.setAudienceCount(audienceCountOf(a));
        }
        return list;
    }

    /**
     * 新建或更新（同 id 覆盖）。归属租户口径与配额 save 一致：superadmin 可指定（跨租户发布），
     * 平台视图不指定兜当前租户；普通路径由行级拦截器按线程补。
     */
    public Announcement save(Announcement in, String tenantId) {
        if (in.getTitle() == null || in.getTitle().isBlank()) throw new BizException("要给公告起个标题");
        String level = in.getLevel() == null || in.getLevel().isBlank() ? "info" : in.getLevel().trim();
        if (!List.of("info", "warn", "danger").contains(level)) throw new BizException("公告级别只能是 info/warn/danger");
        String contentFormat = in.getContentFormat() == null || in.getContentFormat().isBlank()
                ? "plain" : in.getContentFormat().trim();
        if (!List.of("plain", "markdown").contains(contentFormat)) throw new BizException("公告正文格式只能是 plain/markdown");
        String audienceType = in.getAudienceType() == null || in.getAudienceType().isBlank()
                ? "all" : in.getAudienceType().trim();
        if (!List.of("all", "department", "role").contains(audienceType)) {
            throw new BizException("展示对象只能是 all/department/role");
        }
        String audienceIds = normalizeAudienceIds(audienceType, in.getAudienceIds());
        LocalDateTime start = in.getStartTime();
        LocalDateTime end = in.getEndTime();
        if (start != null && end != null && !end.isAfter(start)) {
            throw new BizException("公告结束时间必须晚于开始时间");
        }
        boolean fresh = in.getId() == null || in.getId().isBlank();
        Announcement a;
        if (fresh) {
            a = new Announcement();
            a.setId(UUID.randomUUID().toString());
            if (RequestUser.superadmin()) {
                String own = tenantId != null && !tenantId.isBlank() ? tenantId.trim()
                        : com.wenqu.ai.util.TenantContext.current();
                a.setTenantId(own == null || own.isBlank() ? com.wenqu.ai.util.TenantContext.DEFAULT_TENANT : own);
            }
            a.setCreatedBy(RequestUser.uid());
            a.setEnabled(in.getEnabled() == null ? 1 : in.getEnabled());
            a.setCreateTime(java.time.LocalDateTime.now());
        } else {
            a = mapper.selectById(in.getId());
            if (a == null) throw new BizException("公告不存在");
        }
        a.setTitle(in.getTitle().trim());
        a.setContent(in.getContent());
        a.setContentFormat(contentFormat);
        a.setLevel(level);
        a.setAudienceType(audienceType);
        a.setAudienceIds(audienceIds);
        a.setStartTime(start);
        a.setEndTime(end);
        a.setEnabled(in.getEnabled() == null ? a.getEnabled() : in.getEnabled());
        if (fresh) {
            mapper.insert(a);
            audit.record("[AUDIT] 发布公告 tenant={} title={} level={} audience={} enabled={}",
                    a.getTenantId(), a.getTitle(), a.getLevel(), audienceType, a.getEnabled());
        } else {
            mapper.updateById(a);
            audit.record("[AUDIT] 修改公告 id={} title={} level={} audience={} enabled={}",
                    a.getId(), a.getTitle(), a.getLevel(), audienceType, a.getEnabled());
        }
        return a;
    }

    /** 下线（保留行）：误下线可一键恢复；要彻底清除走 {@link #purge}（同样入审计） */
    public void disable(String id) {
        Announcement a = mapper.selectById(id);
        if (a == null) throw new BizException("公告不存在");
        a.setEnabled(0);
        mapper.updateById(a);
        audit.record("[AUDIT] 下线公告 id={} title={}", a.getId(), a.getTitle());
    }

    /**
     * 物理删除（仅已下线的公告可删）：发布中的先下线再删，避免横幅正挂着被直接抽走。
     * 删掉后行没了，痕迹全靠审计链——所以审计里带齐 id/租户/标题/级别。
     * 已读回执随公告一起清掉：公告都没了，回执只是孤儿行。
     */
    public void purge(String id) {
        Announcement a = mapper.selectById(id);
        if (a == null) throw new BizException("公告不存在");
        if (a.getEnabled() != null && a.getEnabled() == 1) throw new BizException("发布中的公告请先下线再删除");
        mapper.deleteById(id);
        try {
            jdbc.update("delete from c_ai_announcement_read where ann_id = ?", id);
        } catch (Exception e) {
            log.warn("[ANNOUNCEMENT] 删除公告已读回执失败 id={}：{}", id, e.getMessage());
        }
        audit.record("[AUDIT] 删除公告 id={} tenant={} title={} level={}",
                a.getId(), a.getTenantId(), a.getTitle(), a.getLevel());
    }

    /**
     * 成员侧：本租户发布中、时间窗口内、命中当前用户部门/角色的公告（最多 5 条，按发布时间倒序）。
     * <p>定向在 Java 侧过滤而不是拼 SQL：audience_ids 是逗号分隔清单，SQL 里做「包含」判断既难读
     * 又容易在多值边界出错；公告量本身很小，读出来再筛更直白。
     */
    public List<Announcement> active() {
        LocalDateTime now = LocalDateTime.now();
        List<Announcement> candidates = mapper.selectList(new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getEnabled, 1)
                .and(w -> w.isNull(Announcement::getStartTime).or().le(Announcement::getStartTime, now))
                .and(w -> w.isNull(Announcement::getEndTime).or().ge(Announcement::getEndTime, now))
                .orderByDesc(Announcement::getCreateTime));
        List<Announcement> visible = new ArrayList<>();
        for (Announcement a : candidates) {
            if (audienceMatch(a)) visible.add(a);
            if (visible.size() >= 5) break;
        }
        Map<String, Boolean> readFlags = readFlagsOf(visible);
        for (Announcement a : visible) a.setRead(readFlags.getOrDefault(a.getId(), false));
        return visible;
    }

    /** 记当前用户已读（幂等：同一公告同一人只留一行） */
    public void markRead(String id) {
        Announcement a = mapper.selectById(id);
        if (a == null) throw new BizException("公告不存在");
        String uid = RequestUser.uid();
        if (RequestUser.SYSTEM.equals(uid)) return;
        String tenant = a.getTenantId();
        if (tenant == null || tenant.isBlank()) tenant = TenantContext.DEFAULT_TENANT;
        try {
            jdbc.update("insert ignore into c_ai_announcement_read (tenant_id, ann_id, uid, read_time) "
                    + "values (?, ?, ?, now())", tenant, id, uid);
        } catch (Exception e) {
            log.warn("[ANNOUNCEMENT] 写已读回执失败 ann={} uid={}：{}", id, uid, e.getMessage());
        }
    }

    // ==================== 辅助 ====================

    private String normalizeAudienceIds(String type, String raw) {
        if ("all".equals(type)) return null;
        String ids = raw == null ? "" : raw.trim();
        if (ids.isBlank()) throw new BizException("定向发布要选择至少一个部门/角色");
        String joined = Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).distinct()
                .collect(Collectors.joining(","));
        if (joined.isBlank()) throw new BizException("定向发布要选择至少一个部门/角色");
        if (joined.length() > 1000) throw new BizException("定向对象过多（最多 1000 字符），请改按角色发布");
        return joined;
    }

    private boolean audienceMatch(Announcement a) {
        String type = a.getAudienceType();
        if (type == null || type.isBlank() || "all".equals(type)) return true;
        Set<String> ids = splitIds(a.getAudienceIds());
        if (ids.isEmpty()) return true; // 存量行没配定向时按全员可见，避免公告凭空消失
        if ("department".equals(type)) {
            String dept = RequestUser.departmentId();
            return dept != null && ids.contains(dept);
        }
        if ("role".equals(type)) {
            return RequestUser.roles().stream().anyMatch(ids::contains);
        }
        return true;
    }

    private static Set<String> splitIds(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, Long> readCountsOf(List<Announcement> list) {
        if (list == null || list.isEmpty()) return Map.of();
        List<String> ids = list.stream().map(Announcement::getId).collect(Collectors.toList());
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : jdbc.queryForList(
                    "select ann_id, count(*) cnt from c_ai_announcement_read group by ann_id")) {
                String annId = String.valueOf(row.get("ann_id"));
                if (!ids.contains(annId)) continue;
                out.put(annId, ((Number) row.get("cnt")).longValue());
            }
        } catch (Exception e) {
            log.warn("[ANNOUNCEMENT] 读取公告已读计数失败：{}", e.getMessage());
        }
        return out;
    }

    private Map<String, Boolean> readFlagsOf(List<Announcement> list) {
        if (list == null || list.isEmpty()) return Map.of();
        String uid = RequestUser.uid();
        if (RequestUser.SYSTEM.equals(uid)) return Map.of();
        List<String> ids = list.stream().map(Announcement::getId).collect(Collectors.toList());
        Map<String, Boolean> out = new LinkedHashMap<>();
        try {
            for (String annId : jdbc.queryForList(
                    "select ann_id from c_ai_announcement_read where uid = ?", String.class, uid)) {
                if (ids.contains(annId)) out.put(annId, true);
            }
        } catch (Exception e) {
            log.warn("[ANNOUNCEMENT] 读取公告已读标记失败 uid={}：{}", uid, e.getMessage());
        }
        return out;
    }

    /**
     * 公告受众总数（已读进度条的分母）。
     * <p>按行归属租户统计启用账号：全员=本租户启用账号；部门=命中部门的启用账号；
     * 角色=持有任一目标角色且未过期的账号。统计失败返回 null，前端不渲染进度条而不是显示 0。
     */
    private Long audienceCountOf(Announcement a) {
        String tenant = a.getTenantId();
        if (tenant == null || tenant.isBlank()) tenant = TenantContext.DEFAULT_TENANT;
        String type = a.getAudienceType();
        Set<String> ids = splitIds(a.getAudienceIds());
        try {
            if ("department".equals(type) && !ids.isEmpty()) {
                String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
                Object[] args = new Object[ids.size() + 1];
                args[0] = tenant;
                int i = 1;
                for (String id : ids) args[i++] = id;
                Long n = jdbc.queryForObject("select count(*) from c_ai_user where tenant_id = ? "
                        + "and status = 1 and department_id in (" + placeholders + ")", Long.class, args);
                return n == null ? 0L : n;
            }
            if ("role".equals(type) && !ids.isEmpty()) {
                String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
                Object[] args = new Object[ids.size() + 1];
                args[0] = tenant;
                int i = 1;
                for (String id : ids) args[i++] = id;
                Long n = jdbc.queryForObject("select count(distinct u.uid) from c_ai_user u "
                        + "join c_ai_user_role r on r.uid = u.uid "
                        + "where u.tenant_id = ? and u.status = 1 and r.role_code in (" + placeholders + ") "
                        + "and (r.expire_at is null or r.expire_at > now())", Long.class, args);
                return n == null ? 0L : n;
            }
            Long n = jdbc.queryForObject(
                    "select count(*) from c_ai_user where tenant_id = ? and status = 1", Long.class, tenant);
            return n == null ? 0L : n;
        } catch (Exception e) {
            log.warn("[ANNOUNCEMENT] 统计公告受众失败 id={}：{}", a.getId(), e.getMessage());
            return null;
        }
    }
}
