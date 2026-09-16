package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.AuthService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 登录鉴权：首次运行初始化、登录、退出、本人改密。
 * <p>
 * {@code /auth/login}、{@code /auth/first-run}、{@code /auth/initialize}、{@code /auth/logout}
 * 为公开端点（见 SecurityConfig 白名单）；其余按登录身份判定。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/auth")
@RequiredArgsConstructor
@Tag(name = "登录鉴权", description = "本地登录：初始化管理员 / 登录 / 退出 / 改密")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "是否首次运行", description = "{needsInitialize:true} 表示尚无任何已设密码的账号，前端应引导初始化管理员")
    @GetMapping("/first-run")
    public ResultJson firstRun() {
        return ResultJson.ok(Map.of("needsInitialize", authService.needsInitialize()));
    }

    @Operation(summary = "初始化管理员", description = "仅在首次运行时可用：{\"uid\":\"admin\",\"username\":\"管理员\",\"password\":\"...\"}；成功后直接返回登录令牌")
    @PostMapping("/initialize")
    public ResultJson initialize(@RequestBody Map<String, String> body) {
        return ResultJson.ok(
                authService.initializeAdmin(body.get("uid"), body.get("username"), body.get("password")),
                "初始化成功");
    }

    @Operation(summary = "登录", description = "{\"identifier\":\"uid 或用户名\",\"password\":\"...\"}；返回 {token, user}")
    @PostMapping("/login")
    public ResultJson login(@RequestBody Map<String, String> body) {
        String id = body.get("identifier");
        if (id == null || id.isBlank()) id = body.get("uid");
        if (id == null || id.isBlank()) id = body.get("username");
        return ResultJson.ok(authService.login(id, body.get("password")), "登录成功");
    }

    @Operation(summary = "退出登录", description = "令牌为无状态，前端清除本地令牌即可；此处仅作语义化端点")
    @PostMapping("/logout")
    public ResultJson logout() {
        return ResultJson.ok("已退出");
    }

    @Operation(summary = "修改本人密码", description = "{\"oldPassword\":\"...\",\"newPassword\":\"...\"}")
    @PostMapping("/password")
    public ResultJson changePassword(@RequestBody Map<String, String> body) {
        authService.changePassword(RequestUser.uid(), body.get("oldPassword"), body.get("newPassword"));
        return ResultJson.ok("密码已修改");
    }
}
