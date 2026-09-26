package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.OrgService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户管理：列表供「共享范围」选择器使用，增删改供「成员管理」页使用。
 * <p>
 * 用户以 uid 作为登录账号，由管理员在「成员管理」建档；本接口维护画像/归属（部门、角色、状态）并可新建/重置密码（PBKDF2 加盐哈希）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/user")
@RequiredArgsConstructor
@Tag(name = "用户管理", description = "用户增删改查")
public class UserController {

    private final OrgService orgService;
    private final com.wisesoft.ai.service.AuthService authService;

    @Operation(summary = "用户列表", description = "返回全部用户（按用户名升序）")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(orgService.listUsers());
    }

    @Operation(summary = "新建用户", description = "{\"uid\":\"alice\",\"username\":\"Alice\",\"password\":\"必填\",\"departmentId\":\"可选\",\"role\":\"user|admin|superadmin\"}")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(orgService.createUser(
                str(body.get("uid")), str(body.get("username")), str(body.get("departmentId")),
                str(body.get("role")), str(body.get("password"))), "已创建");
    }

    @Operation(summary = "重置密码", description = "{\"password\":\"新密码\"}；同时清除登录失败锁定")
    @PutMapping("/{uid}/password")
    public ResultJson resetPassword(
            @Parameter(description = "用户标识") @PathVariable("uid") String uid,
            @RequestBody Map<String, Object> body) {
        authService.resetPassword(uid, str(body.get("password")));
        return ResultJson.ok("密码已重置");
    }

    @Operation(summary = "修改用户", description = "{\"username\": \"\", \"departmentId\": \"\", \"role\": \"\", \"status\": 1|0}")
    @PutMapping("/{uid}")
    public ResultJson update(
            @Parameter(description = "用户标识") @PathVariable("uid") String uid,
            @RequestBody Map<String, Object> body) {
        Integer status = body.get("status") == null ? null : Integer.parseInt(String.valueOf(body.get("status")));
        orgService.updateUser(uid, str(body.get("username")), str(body.get("departmentId")), str(body.get("role")), status);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除用户", description = "最后一名超级管理员不可删除")
    @DeleteMapping("/{uid}")
    public ResultJson delete(@Parameter(description = "用户标识") @PathVariable("uid") String uid) {
        orgService.deleteUser(uid);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "个人偏好读取", description = "本人个人设置：defaultModel（个人默认聊天模型引用，空=跟随系统全局）+ models（可用聊天模型清单，选择器数据源）")
    @GetMapping("/preference")
    public ResultJson preference() {
        return ResultJson.ok(orgService.getPreference(com.wisesoft.ai.util.RequestUser.uid()));
    }

    @Operation(summary = "设置个人默认模型", description = "{\"defaultModel\":\"引用\",\"defaultVisionModel\":\"引用\"}；"
            + "字段缺省(null)=不修改，空串=清除；个人默认聊天模型在会话未手动切换时生效，视觉模型用于聊天上传图片理解。"
            + "重排/向量模型不提供个人默认（重排归知识库检索设置，向量归知识库绑定）")
    @PutMapping("/preference")
    public ResultJson setPreference(@RequestBody Map<String, Object> body) {
        orgService.setPreference(com.wisesoft.ai.util.RequestUser.uid(),
                body.containsKey("defaultModel") ? str(body.get("defaultModel")) : null,
                body.containsKey("defaultVisionModel") ? str(body.get("defaultVisionModel")) : null);
        return ResultJson.ok("已保存");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
