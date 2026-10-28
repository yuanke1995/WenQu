package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AnnouncementMapper;
import com.wenqu.ai.model.Announcement;
import com.wenqu.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 平台公告：发布 / 下线 / 成员侧读取。
 * <p>
 * 跨租户口径与配额、成员名单一致（{@code TenantContext.asPlatformIfSuper}）：平台级管理员管全部租户的
 * 公告，租户管理员只管本租户的。成员侧读取（登录后横幅）走普通租户过滤——每人只看到本租户发布中的公告。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private final AnnouncementMapper mapper;
    private final AuditService audit;

    /** 管理清单：平台级跨租户看全量，租户管理员看本租户（下线的也在，历史可查） */
    public List<Announcement> adminList() {
        return mapper.selectList(new LambdaQueryWrapper<Announcement>()
                .orderByDesc(Announcement::getCreateTime));
    }

    /**
     * 新建或更新（同 id 覆盖）。归属租户口径与配额 save 一致：superadmin 可指定（跨租户发布），
     * 平台视图不指定兜当前租户；普通路径由行级拦截器按线程补。
     */
    public Announcement save(Announcement in, String tenantId) {
        if (in.getTitle() == null || in.getTitle().isBlank()) throw new BizException("要给公告起个标题");
        String level = in.getLevel() == null || in.getLevel().isBlank() ? "info" : in.getLevel().trim();
        if (!List.of("info", "warn", "danger").contains(level)) throw new BizException("公告级别只能是 info/warn/danger");
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
        a.setLevel(level);
        a.setEnabled(in.getEnabled() == null ? a.getEnabled() : in.getEnabled());
        if (fresh) {
            mapper.insert(a);
            audit.record("[AUDIT] 发布公告 tenant={} title={} level={} enabled={}",
                    a.getTenantId(), a.getTitle(), a.getLevel(), a.getEnabled());
        } else {
            mapper.updateById(a);
            audit.record("[AUDIT] 修改公告 id={} title={} level={} enabled={}",
                    a.getId(), a.getTitle(), a.getLevel(), a.getEnabled());
        }
        return a;
    }

    /** 下线（保留行）：误下线可一键恢复，所以不提供物理删除——发出去让全员看见的事要有痕迹 */
    public void disable(String id) {
        Announcement a = mapper.selectById(id);
        if (a == null) throw new BizException("公告不存在");
        a.setEnabled(0);
        mapper.updateById(a);
        audit.record("[AUDIT] 下线公告 id={} title={}", a.getId(), a.getTitle());
    }

    /** 成员侧：本租户发布中的公告（横幅数据源，最多 5 条，按发布时间倒序） */
    public List<Announcement> active() {
        return mapper.selectList(new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getEnabled, 1)
                .orderByDesc(Announcement::getCreateTime)
                .last("limit 5"));
    }
}
