package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.service.TenantInviteService;
import com.wenqu.ai.util.ClientIp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 租户邀请落地页：免登录。
 *
 * <p>token 即凭据（高熵随机、只存哈希）；两个端点都按 IP 限流，避免链接被拿去当枚举靶子。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/join")
@RequiredArgsConstructor
@Tag(name = "租户邀请", description = "邀请链接预览与自助注册（免登录）")
public class JoinController {

    private final TenantInviteService inviteService;
    private final RateLimitService rateLimitService;

    @Operation(summary = "邀请预览", description = "返回租户品牌、邀请人、预选角色与有效期；无效/过期/用尽一律明确报错")
    @GetMapping("/{token}")
    public ResultJson<Map<String, Object>> preview(@PathVariable("token") String token, HttpServletRequest request) {
        rateLimitService.checkRateLimit("join", "ip:" + ClientIp.of(request));
        return ResultJson.ok(inviteService.preview(token));
    }

    @Operation(summary = "接受邀请并注册",
            description = "{\"uid\":\"登录账号\",\"username\":\"显示名\",\"password\":\"密码\"}；成功后直接返回登录令牌")
    @PostMapping("/{token}")
    public ResultJson<Map<String, Object>> accept(@PathVariable("token") String token,
                                                  @RequestBody Map<String, String> body,
                                                  HttpServletRequest request) {
        rateLimitService.checkRateLimit("join", "ip:" + ClientIp.of(request));
        return ResultJson.ok(inviteService.accept(token,
                body == null ? null : body.get("uid"),
                body == null ? null : body.get("username"),
                body == null ? null : body.get("password"),
                body == null ? null : body.get("email")), "已加入");
    }
}
