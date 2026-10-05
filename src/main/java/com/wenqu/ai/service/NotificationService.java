package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.mapper.NotificationMapper;
import com.wenqu.ai.mapper.UserConfigMapper;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.UserConfig;
import com.wenqu.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 站内通知服务：异步事件终态的用户可感知面。
 * <p>
 * - 产生：各异步链路的终态收口处调用 {@link #create}（解析队列 / 工作流收口 / 网页源刷新）；
 *   接收人一律取自数据本身（文档 createdBy、run triggeredBy），与请求上下文无关——
 *   触发点都在后台线程（ThreadLocal 无登录态），不能读 {@code RequestUser}；
 * - 消费：铃铛接口（列表 / 未读数 / 已读），只按接收人 uid 过滤，别人不可见也不可标；
 * - 清理：{@link #cleanupExpired} 由 ScheduleCenter 按保留期物理删除（通知的生命周期与
 *   其指向的运行记录/解析任务同量级，过期即失去回溯价值）。
 * <p>
 * <b>旁路边界（设计语义，不是吞错误）</b>：通知是主链路（解析成功/运行终态/刷新收口）的
 * 附加产物，create 内部捕获一切异常只记 WARN——通知写不进去不允许拖死解析收口；
 * 排查入口是 [NOTIFY] 前缀日志，主链路错误照旧向上抛。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final UserConfigMapper userConfigMapper;
    private final ConfigService configService;
    /** 管理员判定（eval.decline 这类平台级预警的收件人范围） */
    private final RoleService roleService;
    /** 用户表（createForAdmins 解析管理员级账号清单） */
    private final com.wenqu.ai.mapper.UserMapper userMapper;

    /** 列表单次上限（防止一次拉爆；前端铃铛面板一屏足够） */
    private static final int LIST_MAX_LIMIT = 200;

    /**
     * 产生一条通知（旁路语义：内部全捕获，失败只记 WARN 不向上抛）。
     *
     * @param uid      接收人（必填，空/anonymous 直接跳过——没有归属人的事件没有通知对象）
     * @param type     类型常量（Notification.TYPE_*）
     * @param title    一句话标题
     * @param content  详情（可空；超 990 字符截断，与列宽 1000 留余量）
     * @param refType  跳转目标类型（可空）
     * @param refId    跳转目标 ID（可空）
     */
    public void create(String uid, String type, String title, String content, String refType, String refId) {
        create(uid, type, title, content, refType, refId, null, null);
    }

    /**
     * 带去重键与二级跳转目标的重载（其余参数同上）。
     *
     * @param dedupKey 去重键：同 (uid,type,dedupKey) 在 {@link #dedupWindowMin()} 窗口内且仍<b>未读</b>的，
     *                合并到已有那条（hitCount+1、刷新文案与跳转、置顶），而非新增行——同一工作流反复失败
     *                不会被刷屏。传 null = 不去重（如解析成功这类希望逐条可见的事件）。
     * @param refSub   二级跳转目标（工作流审批=runId；工具审批=approvalId），供前端直达可裁决位置。
     */
    public void create(String uid, String type, String title, String content, String refType,
                        String refId, String dedupKey, String refSub) {
        try {
            if (uid == null || uid.isBlank() || RequestUser.ANONYMOUS.equals(uid)) {
                log.debug("[NOTIFY] 跳过无归属通知 type={} title={}", type, title);
                return;
            }
            if (isMuted(uid, type)) {
                log.debug("[NOTIFY] 类型已静音（个人设置），跳过 type={} uid={} title={}", type, uid, title);
                return;
            }
            // 同类未读合并：窗口内有同 dedupKey 的未读通知 → 累加计数并刷新到最新，避免刷屏
            if (dedupKey != null && dedupWindowMin() > 0) {
                Notification exist = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUid, uid)
                        .eq(Notification::getDedupKey, dedupKey)
                        .eq(Notification::getReadFlag, 0)
                        .orderByDesc(Notification::getCreateTime)
                        .last("LIMIT 1"));
                if (exist != null) {
                    exist.setHitCount((exist.getHitCount() == null ? 1 : exist.getHitCount()) + 1);
                    exist.setTitle(abbreviate(title, 190));
                    exist.setContent(abbreviate(content, 990));
                    exist.setRefType(refType);
                    exist.setRefId(refId);
                    exist.setRefSub(refSub);
                    exist.setCreateTime(LocalDateTime.now());
                    notificationMapper.updateById(exist);
                    log.info("[NOTIFY] 合并到已有通知 type={} uid={} dedup={} hit={} : {}",
                            type, uid, dedupKey, exist.getHitCount(), title);
                    return;
                }
            }
            Notification n = new Notification();
            n.setUid(uid);
            n.setType(type);
            n.setTitle(abbreviate(title, 190));
            n.setContent(abbreviate(content, 990));
            n.setRefType(refType);
            n.setRefId(refId);
            n.setRefSub(refSub);
            n.setDedupKey(dedupKey);
            n.setHitCount(1);
            n.setReadFlag(0);
            n.setCreateTime(LocalDateTime.now());
            notificationMapper.insert(n);
            log.info("[NOTIFY] {} -> {} : {}", type, uid, title);
        } catch (Exception e) {
            log.warn("[NOTIFY] 通知落库失败（不影响主链路）type={} uid={} title={} err={}",
                    type, uid, title, e.getMessage());
        }
    }

    /** 去重窗口（分钟）：同 dedupKey 未读通知在此窗口内合并；≤0 关闭合并。默认 1 天 */
    private int dedupWindowMin() {
        return configService.getInt("notification.dedupWindow", 1440);
    }

    /** 该用户是否静音了此类型通知（个人设置 notification.mutedTypes 逗号列表；读取失败回落不过滤） */
    private boolean isMuted(String uid, String type) {
        try {
            UserConfig row = userConfigMapper.selectOne(new LambdaQueryWrapper<UserConfig>()
                    .eq(UserConfig::getUid, uid)
                    .eq(UserConfig::getConfigKey, MUTED_TYPES_KEY));
            if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) return false;
            for (String t : row.getConfigValue().split(",")) {
                if (t.trim().equals(type)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 个人设置键：静音的通知类型（逗号分隔的类型常量） */
    public static final String MUTED_TYPES_KEY = "notification.mutedTypes";

    /** 读取该用户静音的类型列表（个人设置；空=不过滤） */
    public List<String> getMutedTypes(String uid) {
        if (uid == null || uid.isBlank()) return List.of();
        try {
            UserConfig row = userConfigMapper.selectOne(new LambdaQueryWrapper<UserConfig>()
                    .eq(UserConfig::getUid, uid).eq(UserConfig::getConfigKey, MUTED_TYPES_KEY));
            if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) return List.of();
            return Arrays.stream(row.getConfigValue().split(","))
                    .map(String::trim).filter(t -> !t.isBlank()).toList();
        } catch (Exception e) { return List.of(); }
    }

    /** 写入该用户静音的类型列表（去重/去空；空列表=清除该项=回落不过滤） */
    public void setMutedTypes(String uid, List<String> types) {
        if (uid == null || uid.isBlank()) return;
        String v = (types == null ? "" : types.stream()
                .filter(Objects::nonNull).map(String::trim).filter(t -> !t.isBlank())
                .distinct().collect(Collectors.joining(",")));
        try {
            UserConfig row = userConfigMapper.selectOne(new LambdaQueryWrapper<UserConfig>()
                    .eq(UserConfig::getUid, uid).eq(UserConfig::getConfigKey, MUTED_TYPES_KEY));
            if (v.isEmpty()) {
                if (row != null) userConfigMapper.deleteById(row.getId());
                return;
            }
            if (row == null) {
                row = new UserConfig();
                row.setUid(uid);
                row.setConfigKey(MUTED_TYPES_KEY);
                row.setConfigValue(v);
                row.setUpdateTime(LocalDateTime.now());
                userConfigMapper.insert(row);
            } else {
                row.setConfigValue(v);
                row.setUpdateTime(LocalDateTime.now());
                userConfigMapper.updateById(row);
            }
        } catch (Exception e) {
            log.warn("[NOTIFY] 静音类型写入失败 uid={}: {}", uid, e.getMessage());
        }
    }

    /**
     * 给全部管理员级账号各发一条平台级预警（eval.decline 等：事件没有单一归属人，责任面是管理员）。
     * 同样是旁路语义：解析收件人失败/无管理员时静默跳过，绝不上抛。
     */
    public void createForAdmins(String type, String title, String content, String refType, String refId) {
        try {
            List<String> codes = roleService.adminCodes();
            if (codes.isEmpty()) return;
            List<com.wenqu.ai.model.User> admins = userMapper.selectList(
                    new LambdaQueryWrapper<com.wenqu.ai.model.User>().in(com.wenqu.ai.model.User::getRole, codes));
            for (com.wenqu.ai.model.User u : admins) {
                if (u.getStatus() == null || u.getStatus() == 1) {
                    create(u.getUid(), type, title, content, refType, refId);
                }
            }
        } catch (Exception e) {
            log.warn("[NOTIFY] 管理员通知组装失败（不影响主链路）type={} title={} err={}", type, title, e.getMessage());
        }
    }

    /**
     * 某用户的通知列表（新→旧，支持类型筛选/仅未读/游标分页）。
     *
     * @param limit     单页条数（默认 50，上限 {@value #LIST_MAX_LIMIT}）
     * @param type      类型筛选（可空 = 全部）
     * @param unreadOnly 仅未读（可空 = 全部）
     * @param cursor    游标（已加载条数 offset；首屏传 0）
     * @return {items, nextCursor, hasMore, total, unreadCount}；unreadCount 为该用户总未读（不受筛选影响）
     */
    /** 列表/计数共用的基础筛选（按接收人 + 可选类型/仅未读） */
    private LambdaQueryWrapper<Notification> baseWrap(String uid, String type, Boolean unreadOnly) {
        LambdaQueryWrapper<Notification> w = new LambdaQueryWrapper<>();
        w.eq(Notification::getUid, uid);
        if (type != null && !type.isBlank()) w.eq(Notification::getType, type);
        if (Boolean.TRUE.equals(unreadOnly)) w.eq(Notification::getReadFlag, 0);
        return w;
    }

    public Map<String, Object> list(String uid, int limit, String type, Boolean unreadOnly, long cursor) {
        int n = Math.max(1, Math.min(limit <= 0 ? 50 : limit, LIST_MAX_LIMIT));
        // 多取 1 条判断是否还有下一页
        LambdaQueryWrapper<Notification> w = baseWrap(uid, type, unreadOnly);
        w.orderByDesc(Notification::getCreateTime).orderByDesc(Notification::getId);
        w.last("LIMIT " + (n + 1) + " OFFSET " + Math.max(0L, cursor));
        List<Notification> rows = notificationMapper.selectList(w);
        boolean hasMore = rows.size() > n;
        if (hasMore) rows = rows.subList(0, n);
        long total = notificationMapper.selectCount(baseWrap(uid, type, unreadOnly));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("items", rows);
        out.put("nextCursor", hasMore ? (Math.max(0L, cursor) + n) : null);
        out.put("hasMore", hasMore);
        out.put("total", total);
        out.put("unreadCount", unreadCount(uid));
        return out;
    }

    /** 各类型未读计数（铃铛/通知中心的筛选角标用）；返回 {类型: 未读数} */
    public Map<String, Long> countsByType(String uid) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        List<Notification> rows = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUid, uid).eq(Notification::getReadFlag, 0));
        for (Notification r : rows) {
            out.merge(r.getType(), 1L, Long::sum);
        }
        return out;
    }

    /** 删除指定通知（只删属于自己的；ids 为空返回 0） */
    public int delete(String uid, List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUid, uid).in(Notification::getId, ids));
    }

    /** 清空通知：scope=read 仅清已读；scope=all 清全部（都只清自己的） */
    public int clear(String uid, String scope) {
        LambdaQueryWrapper<Notification> w = new LambdaQueryWrapper<Notification>().eq(Notification::getUid, uid);
        if ("read".equalsIgnoreCase(scope)) w.eq(Notification::getReadFlag, 1);
        return notificationMapper.delete(w);
    }

    /** 未读数（铃铛 Badge 轮询用；只数自己的） */
    public long unreadCount(String uid) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUid, uid)
                .eq(Notification::getReadFlag, 0));
    }

    /** 标记已读（ids 内属于自己的才生效——update 带(uid, in ids) 双条件，别人的 id 传进来也不动） */
    public int markRead(String uid, List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUid, uid)
                .eq(Notification::getReadFlag, 0)
                .in(Notification::getId, ids)
                .set(Notification::getReadFlag, 1)
                .set(Notification::getReadTime, LocalDateTime.now()));
    }

    /** 全部已读（只动自己的未读） */
    public int markAllRead(String uid) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUid, uid)
                .eq(Notification::getReadFlag, 0)
                .set(Notification::getReadFlag, 1)
                .set(Notification::getReadTime, LocalDateTime.now()));
    }

    /**
     * 超期清理（ScheduleCenter 周期调用）：按产生时间物理删除，不区分已读未读——
     * 通知指向的解析任务/运行记录本身也有保留期，通知活得比它们久没有意义。
     *
     * @return 删除条数
     */
    public int cleanupExpired(int retentionDays) {
        LocalDateTime threshold = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        return notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .lt(Notification::getCreateTime, threshold));
    }

    /** 截断（appendOnly 简版：超长截 + 省略号） */
    private static String abbreviate(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
