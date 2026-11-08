package com.wenqu.ai.controller;

import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.Announcement;
import com.wenqu.ai.service.AnnouncementService;
import com.wenqu.ai.service.AuthService;
import com.wenqu.ai.service.OpsCheckupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;
/**
 * 运维保障与治理（管理员面）：系统巡检 / 会话治理 / 平台公告。
 * <p>
 * 与「系统设置」的分工：设置页管<b>参数</b>（配什么），这里管<b>运行状态与人</b>（现在好不好、
 * 谁在线上、全员该知道什么）。三类动作都落审计——运维动作没有留痕，出了事就没法回答「当时谁干的」。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/ops")
@RequiredArgsConstructor
@Tag(name = "运维保障", description = "系统巡检 / 会话治理（强制下线）/ 平台公告")
public class OpsController {

    private final OpsCheckupService checkupService;
    private final AuthService authService;
    private final AnnouncementService announcementService;
    private final AdminGuard adminGuard;

    // ==================== 系统巡检 ====================

    @Operation(summary = "立即巡检", description = "MySQL/Redis/关键词引擎/落盘磁盘/审计链/供应商/登录安全态势 "
            + "逐项体检，overall 取最差项；报告落库并入审计，历史可回看")
    @Permission("ops:manage")
    @PostMapping("/checkup")
    public ResultJson<Map<String, Object>> runCheckup(HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:manage")) return ResultJson.error("系统巡检仅管理员可执行");
        return ResultJson.ok(checkupService.run("manual"));
    }

    @Operation(summary = "最近一次巡检报告", description = "优先读库：后端重启后仍能回看上一条；未巡检过返回 null")
    @Permission("ops:read")
    @GetMapping("/checkup/last")
    public ResultJson<Map<String, Object>> lastCheckup(HttpServletRequest request) {
        // 只读权限即可查看：只读管理员的职责就是「看得到、改不动」，这里不该用 ops:manage 挡回去
        if (!adminGuard.requirePermission(request, "ops:read")) return ResultJson.error("系统巡检仅管理员可查看");
        return ResultJson.ok(checkupService.last());
    }

    @Operation(summary = "巡检历史", description = "最近 N 天的报告摘要（不含逐项 checks），按时间倒序")
    @Permission("ops:read")
    @GetMapping("/checkup/history")
    public ResultJson<List<Map<String, Object>>> checkupHistory(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:read")) return ResultJson.error("系统巡检仅管理员可查看");
        return ResultJson.ok(checkupService.history(days, limit));
    }

    @Operation(summary = "巡检趋势", description = "按天聚合：每天跑了几次、失败/留意几次、平均耗时与最差结论")
    @Permission("ops:read")
    @GetMapping("/checkup/trend")
    public ResultJson<List<Map<String, Object>>> checkupTrend(
            @RequestParam(value = "days", defaultValue = "7") int days,
            HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:read")) return ResultJson.error("系统巡检仅管理员可查看");
        return ResultJson.ok(checkupService.trend(days));
    }

    @Operation(summary = "单次巡检详情", description = "含逐项 checks：查什么、当前值、处置建议与耗时")
    @Permission("ops:read")
    @GetMapping("/checkup/{id}")
    public ResultJson<Map<String, Object>> checkupDetail(@PathVariable("id") String id, HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:read")) return ResultJson.error("系统巡检仅管理员可查看");
        return ResultJson.ok(checkupService.detail(id));
    }

    // ==================== 会话治理 ====================

    @Operation(summary = "强制下线", description = "该账号已签发的全部令牌（所有设备）当场失效：会话代数 +1。"
            + "适用：账号疑似被盗、离职即踢。本人改密/管理员重置密码会自动做同一件事")
    @Permission("ops:manage")
    @PostMapping("/user/{uid}/force-logout")
    public ResultJson forceLogout(@PathVariable("uid") String uid, HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:manage")) return ResultJson.error("强制下线仅管理员可操作");
        authService.forceLogout(uid);
        return ResultJson.ok("已强制下线");
    }

    @Operation(summary = "批量强制下线", description = "body {uids:[...]}，单次最多 100 个账号；逐个落审计，返回成功/失败计数")
    @Permission("ops:manage")
    @PostMapping("/user/batch-force-logout")
    public ResultJson<Map<String, Object>> batchForceLogout(@RequestBody Map<String, Object> body,
                                                            HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:manage")) return ResultJson.error("批量强制下线仅管理员可操作");
        Object raw = body == null ? null : body.get("uids");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return ResultJson.error("uids 必须是非空数组");
        List<String> uids = new ArrayList<>();
        for (Object o : list) {
            String uid = o == null ? null : String.valueOf(o).trim();
            if (uid != null && !uid.isBlank() && !uids.contains(uid)) uids.add(uid);
        }
        if (uids.size() > 100) return ResultJson.error("单次最多 100 个账号（当前 " + uids.size() + " 个）");
        int ok = 0;
        List<String> errors = new ArrayList<>();
        for (String uid : uids) {
            try {
                authService.forceLogout(uid);
                ok++;
            } catch (Exception e) {
                errors.add(uid + "：" + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("successCount", ok);
        out.put("failCount", errors.size());
        out.put("errors", errors);
        return ResultJson.ok(out, "已强制下线 " + ok + " 个账号" + (errors.isEmpty() ? "" : "，失败 " + errors.size() + " 个"));
    }

    // ==================== 平台公告 ====================

    @Operation(summary = "公告管理清单", description = "含下线的（历史可查）；平台级管理员看全部租户，租户管理员看本租户")
    @Permission("ops:read")
    @GetMapping("/announcement")
    public ResultJson<List<Announcement>> listAnnouncements(HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:read")) return ResultJson.error("公告管理仅管理员可查看");
        return ResultJson.ok(com.wenqu.ai.util.TenantContext.asPlatformIfSuper(announcementService::adminList));
    }

    @Operation(summary = "发布/更新公告", description = "level: info=通知 warn=警示 danger=紧急。"
            + "tenantId 仅平台级管理员可指定（跨租户发布）；租户管理员固定发本租户")
    @Permission("ops:manage")
    @PostMapping("/announcement")
    public ResultJson<Announcement> saveAnnouncement(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:manage")) return ResultJson.error("公告管理仅管理员可操作");
        Announcement a = new Announcement();
        a.setId(str(body.get("id")));
        a.setTitle(str(body.get("title")));
        a.setContent(str(body.get("content")));
        a.setContentFormat(str(body.get("contentFormat")));
        a.setLevel(str(body.get("level")));
        a.setAudienceType(str(body.get("audienceType")));
        Object ids = body.get("audienceIds");
        if (ids instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) if (o != null && !String.valueOf(o).isBlank()) parts.add(String.valueOf(o).trim());
            a.setAudienceIds(parts.isEmpty() ? null : String.join(",", parts));
        } else {
            a.setAudienceIds(str(ids));
        }
        a.setStartTime(time(body.get("startTime")));
        a.setEndTime(time(body.get("endTime")));
        Object enabled = body.get("enabled");
        a.setEnabled(enabled == null ? 1 : Integer.parseInt(String.valueOf(enabled)));
        return ResultJson.ok(com.wenqu.ai.util.TenantContext.asPlatformIfSuper(() ->
                announcementService.save(a, str(body.get("tenantId")))));
    }

    @Operation(summary = "下线公告", description = "保留行不真删——发出去让全员看见的事要有痕迹，误下线可恢复；要彻底清除走删除端点")
    @Permission("ops:manage")
    @DeleteMapping("/announcement/{id}")
    public ResultJson deleteAnnouncement(@PathVariable("id") String id, HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:manage")) return ResultJson.error("公告管理仅管理员可操作");
        com.wenqu.ai.util.TenantContext.asPlatformIfSuper(() -> announcementService.disable(id));
        return ResultJson.ok("已下线");
    }

    @Operation(summary = "删除公告", description = "物理删除，仅已下线的公告可删（发布中的先下线再删）；删除动作带标题入审计")
    @Permission("ops:manage")
    @DeleteMapping("/announcement/{id}/purge")
    public ResultJson purgeAnnouncement(@PathVariable("id") String id, HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "ops:manage")) return ResultJson.error("公告管理仅管理员可操作");
        com.wenqu.ai.util.TenantContext.asPlatformIfSuper(() -> announcementService.purge(id));
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "当前生效公告（登录用户）", description = "本租户发布中、在时间窗口内、命中当前用户部门/角色的公告；"
            + "登录后横幅数据源；任何登录用户可读，read 标记表示当前用户是否已读过")
    @Permission("announcement:self")
    @GetMapping("/announcement/active")
    public ResultJson<List<Announcement>> activeAnnouncements(HttpServletRequest request) {
        return ResultJson.ok(announcementService.active());
    }

    @Operation(summary = "标记公告已读", description = "横幅关闭或展开详情时调用；幂等，同一公告同一人只记一次。"
            + "任何登录用户都可标记自己的已读，不需要 ops 权限")
    @Permission("announcement:self")
    @PostMapping("/announcement/{id}/read")
    public ResultJson markAnnouncementRead(@PathVariable("id") String id) {
        announcementService.markRead(id);
        return ResultJson.ok("已读");
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 公告时间：接受前端 datetime 控件的 ISO 串，也接受 yyyy-MM-dd（按当天 0 点） */
    private static java.time.LocalDateTime time(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isBlank()) return null;
        try {
            return java.time.LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception ignored) {
            try {
                return java.time.LocalDate.parse(s).atStartOfDay();
            } catch (Exception e) {
                throw new com.wenqu.ai.common.BizException("时间格式应为 yyyy-MM-ddTHH:mm:ss 或 yyyy-MM-dd");
            }
        }
    }
}
