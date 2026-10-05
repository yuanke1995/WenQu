package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.NotificationService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 站内通知接口（铃铛）：列表 / 未读数 / 已读。
 * <p>
 * 接收人一律取登录令牌 uid（{@link RequestUser}，请求线程由拦截器装载）——
 * 每人只看得到并只操作得了自己的通知，无跨人读写口。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/notification")
@RequiredArgsConstructor
@Tag(name = "站内通知", description = "解析/工作流/网页源刷新等异步事件的通知：列表、未读数、标记已读")
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "通知列表", description = "当前用户的通知（新→旧）；支持 type 类型筛选、unreadOnly 仅未读、cursor 游标分页。" +
            "返回 {items:[通知], nextCursor, hasMore, total, unreadCount}")
    @GetMapping("/list")
    public ResultJson list(@RequestParam(value = "limit", defaultValue = "50") int limit,
                           @RequestParam(value = "type", required = false) String type,
                           @RequestParam(value = "unreadOnly", required = false) Boolean unreadOnly,
                           @RequestParam(value = "cursor", defaultValue = "0") long cursor) {
        return ResultJson.ok(notificationService.list(RequestUser.uid(), limit, type, unreadOnly, cursor));
    }

    @Operation(summary = "未读数", description = "当前用户未读通知条数（铃铛 Badge 轮询用）")
    @GetMapping("/unread-count")
    public ResultJson unreadCount() {
        return ResultJson.ok(Map.of("count", notificationService.unreadCount(RequestUser.uid())));
    }

    @Operation(summary = "各类型未读计数", description = "返回 {counts:{类型:未读数}, unread:总未读}；通知中心筛选角标用")
    @GetMapping("/counts")
    public ResultJson counts() {
        Map<String, Long> counts = notificationService.countsByType(RequestUser.uid());
        return ResultJson.ok(Map.of("counts", counts, "unread", notificationService.unreadCount(RequestUser.uid())));
    }

    @Operation(summary = "标记已读", description = "body: {ids:[通知ID]}；只影响属于自己的未读通知")
    @PostMapping("/read")
    public ResultJson markRead(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        List<String> ids = raw instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return ResultJson.ok(Map.of("updated", notificationService.markRead(RequestUser.uid(), ids)));
    }

    @Operation(summary = "全部已读", description = "当前用户全部未读通知标记已读")
    @PostMapping("/read-all")
    public ResultJson markAllRead() {
        return ResultJson.ok(Map.of("updated", notificationService.markAllRead(RequestUser.uid())));
    }

    @Operation(summary = "删除通知", description = "body: {ids:[通知ID]}；只删除属于自己的通知")
    @PostMapping("/delete")
    public ResultJson delete(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        List<String> ids = raw instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        return ResultJson.ok(Map.of("deleted", notificationService.delete(RequestUser.uid(), ids)));
    }

    @Operation(summary = "清空通知", description = "body: {scope:'all'|'read'}；all=清空全部，read=仅清已读；都只清自己的")
    @PostMapping("/clear")
    public ResultJson clear(@RequestBody Map<String, Object> body) {
        String scope = body == null ? "all" : String.valueOf(body.getOrDefault("scope", "all"));
        return ResultJson.ok(Map.of("deleted", notificationService.clear(RequestUser.uid(), scope)));
    }

    @Operation(summary = "通知类型偏好（静音）", description = "返回本用户已静音的通知类型列表（类型常量数组）")
    @GetMapping("/preferences")
    public ResultJson getPreferences() {
        return ResultJson.ok(Map.of("mutedTypes", notificationService.getMutedTypes(RequestUser.uid())));
    }

    @Operation(summary = "保存通知类型偏好（静音）", description = "body: {mutedTypes:[类型常量...]}；空数组=恢复接收全部类型")
    @PutMapping("/preferences")
    public ResultJson savePreferences(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("mutedTypes");
        List<String> types = raw instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        notificationService.setMutedTypes(RequestUser.uid(), types);
        return ResultJson.ok(Map.of("mutedTypes", notificationService.getMutedTypes(RequestUser.uid())));
    }
}
