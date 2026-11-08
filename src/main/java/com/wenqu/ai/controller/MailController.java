package com.wenqu.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.mapper.MailDeliveryMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.MailDelivery;
import com.wenqu.ai.model.User;
import com.wenqu.ai.security.Permission;
import com.wenqu.ai.service.MailDispatchService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知外发（邮件）：测试发送与投递台账。
 * <p>配置项本身走 {@code /api/ai/config} 的通用读写（schema 面板 notify-mail）；
 * 这里只放两个「动作」端点。测试邮件固定发到当前管理员自己的邮箱，不做成开放中继。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/notify/mail")
@RequiredArgsConstructor
@Tag(name = "通知外发", description = "邮件通道测试与投递台账")
public class MailController {

    private static final int PAGE_MAX = 200;

    private final MailDispatchService mailDispatch;
    private final UserMapper userMapper;
    private final MailDeliveryMapper deliveryMapper;

    @Operation(summary = "发送测试邮件",
            description = "发到当前管理员自己的邮箱（个人资料里配置的 email）；失败原因直接返回，便于当场排 SMTP")
    @Permission("config:manage")
    @PostMapping("/test")
    public ResultJson test() {
        String uid = RequestUser.uid();
        User u = uid == null ? null : userMapper.selectById(uid);
        String email = u == null ? null : u.getEmail();
        if (email == null || email.isBlank()) {
            return ResultJson.error("当前账号还没有配置邮箱地址，请先在成员管理里补上你的邮箱");
        }
        String html = "<div style=\"font-family:-apple-system,'PingFang SC',sans-serif;font-size:13px;line-height:1.8\">"
                + "<p>这是一封来自问渠的测试邮件。</p>"
                + "<p>收到它说明 SMTP 配置可用。此后平台产生的待处理与失败类通知，会按个人设置 → 通知偏好里的勾选投递到该邮箱。</p>"
                + "</div>";
        mailDispatch.sendTest(email, "问渠 · 邮件通道测试", html);
        return ResultJson.ok(Map.of("to", email), "测试邮件已发送");
    }

    @Operation(summary = "邮件投递台账", description = "status 可选 pending/success/exhausted/skipped；按时间倒序，默认 50 条")
    @Permission("config:read")
    @GetMapping("/deliveries")
    public ResultJson<List<Map<String, Object>>> deliveries(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        int size = Math.min(Math.max(limit, 1), PAGE_MAX);
        List<MailDelivery> rows = deliveryMapper.selectList(new LambdaQueryWrapper<MailDelivery>()
                .eq(status != null && !status.isBlank(), MailDelivery::getStatus, status)
                .orderByDesc(MailDelivery::getCreateTime)
                .last("LIMIT " + size));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MailDelivery d : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", d.getId());
            one.put("uid", d.getUid());
            one.put("to", d.getToAddr());
            one.put("subject", d.getSubject());
            one.put("type", d.getNotifyType() == null ? "" : d.getNotifyType());
            one.put("status", d.getStatus());
            one.put("attempts", d.getAttempts() == null ? 0 : d.getAttempts());
            one.put("lastError", d.getLastError() == null ? "" : d.getLastError());
            one.put("createTime", d.getCreateTime() == null ? "" : d.getCreateTime().toString());
            out.add(one);
        }
        return ResultJson.ok(out);
    }
}
