package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.mapper.NotificationMapper;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
        try {
            if (uid == null || uid.isBlank() || RequestUser.ANONYMOUS.equals(uid)) {
                log.debug("[NOTIFY] 跳过无归属通知 type={} title={}", type, title);
                return;
            }
            Notification n = new Notification();
            n.setUid(uid);
            n.setType(type);
            n.setTitle(abbreviate(title, 190));
            n.setContent(abbreviate(content, 990));
            n.setRefType(refType);
            n.setRefId(refId);
            n.setReadFlag(0);
            n.setCreateTime(LocalDateTime.now());
            notificationMapper.insert(n);
            log.info("[NOTIFY] {} -> {} : {}", type, uid, title);
        } catch (Exception e) {
            log.warn("[NOTIFY] 通知落库失败（不影响主链路）type={} uid={} title={} err={}",
                    type, uid, title, e.getMessage());
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

    /** 某用户的通知列表（新→旧，limit 上限 {@value #LIST_MAX_LIMIT}），附未读数 */
    public Map<String, Object> list(String uid, int limit) {
        int n = Math.max(1, Math.min(limit <= 0 ? 50 : limit, LIST_MAX_LIMIT));
        List<Notification> rows = notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUid, uid)
                .orderByDesc(Notification::getCreateTime)
                .last("LIMIT " + n));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("items", rows);
        out.put("unreadCount", unreadCount(uid));
        return out;
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
