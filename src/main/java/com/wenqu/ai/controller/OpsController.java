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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
            + "逐项体检，overall 取最差项；报告入审计，进程内保留最近一次供回看")
    @PostMapping("/checkup")
    public ResultJson<Map<String, Object>> runCheckup(HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("系统巡检仅管理员可执行");
        return ResultJson.ok(checkupService.run());
    }

    @Operation(summary = "最近一次巡检报告", description = "进程内缓存：后端重启后为空（如实显示「未巡检过」，不假装记得）")
    @GetMapping("/checkup/last")
    public ResultJson<Map<String, Object>> lastCheckup(HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("系统巡检仅管理员可查看");
        return ResultJson.ok(checkupService.last());
    }

    // ==================== 会话治理 ====================

    @Operation(summary = "强制下线", description = "该账号已签发的全部令牌（所有设备）当场失效：会话代数 +1。"
            + "适用：账号疑似被盗、离职即踢。本人改密/管理员重置密码会自动做同一件事")
    @PostMapping("/user/{uid}/force-logout")
    public ResultJson forceLogout(@PathVariable("uid") String uid, HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("强制下线仅管理员可操作");
        authService.forceLogout(uid);
        return ResultJson.ok("已强制下线");
    }

    // ==================== 平台公告 ====================

    @Operation(summary = "公告管理清单", description = "含下线的（历史可查）；平台级管理员看全部租户，租户管理员看本租户")
    @GetMapping("/announcement")
    public ResultJson<List<Announcement>> listAnnouncements(HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("公告管理仅管理员可查看");
        return ResultJson.ok(com.wenqu.ai.util.TenantContext.asPlatformIfSuper(announcementService::adminList));
    }

    @Operation(summary = "发布/更新公告", description = "level: info=通知 warn=警示 danger=紧急。"
            + "tenantId 仅平台级管理员可指定（跨租户发布）；租户管理员固定发本租户")
    @PostMapping("/announcement")
    public ResultJson<Announcement> saveAnnouncement(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("公告管理仅管理员可操作");
        Announcement a = new Announcement();
        a.setId(str(body.get("id")));
        a.setTitle(str(body.get("title")));
        a.setContent(str(body.get("content")));
        a.setLevel(str(body.get("level")));
        Object enabled = body.get("enabled");
        a.setEnabled(enabled == null ? 1 : Integer.parseInt(String.valueOf(enabled)));
        return ResultJson.ok(com.wenqu.ai.util.TenantContext.asPlatformIfSuper(() ->
                announcementService.save(a, str(body.get("tenantId")))));
    }

    @Operation(summary = "下线公告", description = "保留行不真删——发出去让全员看见的事要有痕迹，误下线可恢复")
    @DeleteMapping("/announcement/{id}")
    public ResultJson deleteAnnouncement(@PathVariable("id") String id, HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("公告管理仅管理员可操作");
        com.wenqu.ai.util.TenantContext.asPlatformIfSuper(() -> announcementService.disable(id));
        return ResultJson.ok("已下线");
    }

    @Operation(summary = "当前生效公告（登录用户）", description = "本租户发布中的公告，登录后横幅数据源；任何登录用户可读")
    @GetMapping("/announcement/active")
    public ResultJson<List<Announcement>> activeAnnouncements(HttpServletRequest request) {
        return ResultJson.ok(announcementService.active());
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
