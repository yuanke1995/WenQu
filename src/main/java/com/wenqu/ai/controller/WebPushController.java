package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web Push 订阅接口（把站内通知推到浏览器系统级通知）。
 *
 * <p>订阅/退订/测试都只作用于<b>当前登录用户自己的设备</b>；{@code /keys} 仅管理员，
 * 且只<b>生成并回显</b>一对新密钥——服务端不保存，必须贴进 .env 重启才生效
 * （私钥不进 DB、不进设置页，理由见 {@link com.wenqu.ai.service.WebPushNotifier}）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/webpush")
@RequiredArgsConstructor
@Tag(name = "Web Push", description = "浏览器系统级推送：订阅设备、退订、发测试；VAPID 密钥走环境变量")
public class WebPushController {

    private final com.wenqu.ai.service.WebPushNotifier webPush;
    private final AdminGuard adminGuard;

    @Operation(summary = "能力与开关", description = "前端据此决定设置页显示什么：平台是否配了密钥、总闸是否开、"
            + "给浏览器的 applicationServerKey（base64url）。未配置时 publicKey 为 null，前端不该尝试订阅")
    @GetMapping("/config")
    public ResultJson config() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ready = webPush.keysReady();
        out.put("configured", ready);
        out.put("enabled", ready && webPush.enabled());
        out.put("publicKey", ready ? webPush.browserPublicKey() : null);
        out.put("devices", webPush.listDevices(RequestUser.uid()));
        return ResultJson.ok(out);
    }

    @Operation(summary = "订阅本机", description = "body: {endpoint, keys:{p256dh, auth}}；同一地址重复提交即刷新。"
            + "归属跟着当前登录用户走（同一浏览器换账号登录不会收到上一个人的通知）")
    @PostMapping("/subscribe")
    public ResultJson subscribe(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Object keys = body == null ? null : body.get("keys");
        String p256dh = keys instanceof Map<?, ?> m ? str(m.get("p256dh")) : "";
        String auth = keys instanceof Map<?, ?> m ? str(m.get("auth")) : "";
        webPush.subscribe(RequestUser.uid(), str(body == null ? null : body.get("endpoint")),
                p256dh, auth, request.getHeader("User-Agent"));
        return ResultJson.ok(Map.of("subscribed", true, "devices", webPush.listDevices(RequestUser.uid())));
    }

    @Operation(summary = "退订本机", description = "body: {endpoint}（本机 PushManager 自己那份订阅的地址）；"
            + "只删本人名下那一行，别人的地址删不动")
    @PostMapping("/unsubscribe")
    public ResultJson unsubscribe(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(Map.of("removed", webPush.unsubscribe(RequestUser.uid(), str(body.get("endpoint"))),
                "devices", webPush.listDevices(RequestUser.uid())));
    }

    @Operation(summary = "发一条测试推送", description = "给本人所有已订阅设备各发一条，逐台回报 sent/expired/failed"
            + "（同步发送，点一下就知道成不成）")
    @PostMapping("/test")
    public ResultJson test() {
        return ResultJson.ok(Map.of("results", webPush.sendTest(RequestUser.uid())));
    }

    @Operation(summary = "生成一对 VAPID 密钥（仅管理员）", description = "只回显不落库：把 publicKey/privateKey "
            + "贴进 .env 的 AI_VAPID_PUBLIC_KEY / AI_VAPID_PRIVATE_KEY 并重启；多副本必须同一对")
    @PostMapping("/keys")
    public ResultJson keys(HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) {
            throw new BizException(403, "仅管理员可生成 VAPID 密钥");
        }
        return ResultJson.ok(webPush.generateKeys());
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
