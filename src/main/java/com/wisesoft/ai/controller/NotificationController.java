package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.NotificationService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @Operation(summary = "通知列表", description = "当前用户的通知（新→旧），body: {items:[通知], unreadCount:N}；limit 默认 50 上限 200")
    @GetMapping("/list")
    public ResultJson list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ResultJson.ok(notificationService.list(RequestUser.uid(), limit));
    }

    @Operation(summary = "未读数", description = "当前用户未读通知条数（铃铛 Badge 轮询用）")
    @GetMapping("/unread-count")
    public ResultJson unreadCount() {
        return ResultJson.ok(Map.of("count", notificationService.unreadCount(RequestUser.uid())));
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
}
