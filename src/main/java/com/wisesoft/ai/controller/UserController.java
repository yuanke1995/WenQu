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
    private final com.wisesoft.ai.service.RoleService roleService;

    @Operation(summary = "用户列表", description = "返回全部用户（按昵称升序）")
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

    @Operation(summary = "重置密码", description = "{\"password\":\"新密码\"}；同时清除登录失败锁定。"
            + "普通用户仅可改自己的密码（本端点对普通用户开放，改别人由管理员端点拦截）")
    @PutMapping("/{uid}/password")
    public ResultJson resetPassword(
            @Parameter(description = "登录账号（uid）") @PathVariable("uid") String uid,
            @RequestBody Map<String, Object> body) {
        // 自助改密：非管理员级角色只允许改自己的（uid 来自路径，但必须与登录态一致，防止代改）
        if (!roleService.isAdminCode(com.wisesoft.ai.util.RequestUser.role())
                && !com.wisesoft.ai.util.RequestUser.uid().equals(uid)) {
            return ResultJson.error(403, "仅可修改自己的密码");
        }
        authService.resetPassword(uid, str(body.get("password")));
        return ResultJson.ok("密码已重置");
    }

    @Operation(summary = "修改用户", description = "{\"username\": \"\", \"departmentId\": \"\", \"role\": \"\", \"status\": 1|0}")
    @PutMapping("/{uid}")
    public ResultJson update(
            @Parameter(description = "登录账号（uid）") @PathVariable("uid") String uid,
            @RequestBody Map<String, Object> body) {
        Integer status = body.get("status") == null ? null : Integer.parseInt(String.valueOf(body.get("status")));
        orgService.updateUser(uid, str(body.get("username")), str(body.get("departmentId")), str(body.get("role")), status);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除用户", description = "最后一名超级管理员不可删除")
    @DeleteMapping("/{uid}")
    public ResultJson delete(@Parameter(description = "登录账号（uid）") @PathVariable("uid") String uid) {
        orgService.deleteUser(uid);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "个人偏好读取", description = "本人个人设置：defaultModel（个人默认聊天模型引用，空=跟随系统全局）+ models（可用聊天模型清单，选择器数据源）")
    @GetMapping("/preference")
    public ResultJson preference() {
        return ResultJson.ok(orgService.getPreference(com.wisesoft.ai.util.RequestUser.uid(),
                com.wisesoft.ai.util.RequestUser.role()));
    }

    @Operation(summary = "设置个人偏好", description = "{\"defaultModel\":\"引用\",\"defaultVisionModel\":\"引用\",\"memoryEnabled\":true|false}；"
            + "字段缺省(null)=不修改，空串=清除；个人默认聊天模型在会话未手动切换时生效，视觉模型用于聊天上传图片理解；"
            + "memoryEnabled=用户级长期记忆自动提炼开关（仅关生成，已存记忆仍注入）。"
            + "重排/向量模型不提供个人默认（重排归知识库检索设置，向量归知识库绑定）")
    @PutMapping("/preference")
    public ResultJson setPreference(@RequestBody Map<String, Object> body) {
        orgService.setPreference(com.wisesoft.ai.util.RequestUser.uid(),
                body.containsKey("defaultModel") ? str(body.get("defaultModel")) : null,
                body.containsKey("defaultVisionModel") ? str(body.get("defaultVisionModel")) : null,
                body.containsKey("memoryEnabled") ? bool(body.get("memoryEnabled")) : null);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "修改我的昵称", description = "{\"username\":\"新昵称\"}；仅本人（uid 取登录态，不可改 uid）；"
            + "昵称即显示名称，仅作展示（登录只认 uid），需全库唯一")
    @PutMapping("/profile")
    public ResultJson updateOwnProfile(@RequestBody Map<String, Object> body) {
        orgService.updateOwnProfile(com.wisesoft.ai.util.RequestUser.uid(), str(body.get("username")));
        return ResultJson.ok("已保存");
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 布尔稳健解析：接受 true/false 与 1/0（JSON 布尔经 Map 后可能为 Boolean 或字符串） */
    private static Boolean bool(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) return Boolean.FALSE;
        throw new IllegalArgumentException("memoryEnabled 需为布尔值");
    }
}
