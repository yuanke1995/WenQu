package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.OrgService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    private final com.wisesoft.ai.service.UserConfigService userConfigService;

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

    @Operation(summary = "设置个人偏好", description = "{\"defaultModel\":\"引用\",\"memoryEnabled\":true|false}；"
            + "字段缺省(null)=不修改，空串=清除；个人默认聊天模型在会话未手动切换时生效。"
            + "memoryEnabled=用户级长期记忆自动提炼开关（仅关生成，已存记忆仍注入）。"
            + "重排/记忆向量等「模型默认」不走本端点：重排归知识库检索设置绑定；问答对生成与 GraphRAG 抽取"
            + "已回落个人默认聊天模型（本端点的 defaultModel），无需单独配置")
    @PutMapping("/preference")
    public ResultJson setPreference(@RequestBody Map<String, Object> body) {
        orgService.setPreference(com.wisesoft.ai.util.RequestUser.uid(),
                body.containsKey("defaultModel") ? str(body.get("defaultModel")) : null,
                body.containsKey("memoryEnabled") ? bool(body.get("memoryEnabled")) : null);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "修改我的资料", description = "{\"username\":\"新昵称\",\"avatar\":\"emoji 或上传图片 URL 或空串清除\"}；"
            + "仅本人（uid 取登录态，不可改 uid）；字段缺省(null)=不修改，avatar 空串=清除头像回落昵称首字。"
            + "头像图片地址须为本人上传返回的 /ai/images/avatar/ 路径（防止存入任意 URL）")
    @PutMapping("/profile")
    public ResultJson updateOwnProfile(@RequestBody Map<String, Object> body) {
        orgService.updateOwnProfile(com.wisesoft.ai.util.RequestUser.uid(),
                body.containsKey("username") ? str(body.get("username")) : null,
                body.containsKey("avatar") ? str(body.get("avatar")) : null);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "上传我的头像", description = "multipart file（PNG/JPEG/GIF/WebP，≤2MB）；仅本人。"
            + "服务端校验图片类型与魔数、落盘到 images/avatar/、写入用户头像（自动清理旧上传图），返回 {url}（/ai/images/avatar/...）。"
            + "该 URL 是公开可访问的头像图（不可猜 UUID），可直接在 <img> 中加载，无需携带令牌")
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultJson uploadAvatar(
            @Parameter(description = "头像图片") @RequestParam("file") MultipartFile file) {
        String url = orgService.uploadAvatar(com.wisesoft.ai.util.RequestUser.uid(), file);
        return ResultJson.ok(java.util.Map.of("url", url));
    }

    @Operation(summary = "个人对话偏好读取", description = "个人设置 → 对话偏好：可个人覆盖的字段定义（config-schema.json 标记 personal）"
            + "+ 本人当前个人值 + 系统全局值（界面展示「跟随系统」参照）。体验类如 chat.temperature / chat.historyRounds / "
            + "retrieval.relatedCount / chat.userSystemPrompt。无模型类个人字段：问答对生成与 GraphRAG 抽取"
            + "均回落个人默认聊天模型（defaultModel）；重排已归知识库检索设置绑定")
    @GetMapping("/settings")
    public ResultJson getSettings() {
        return ResultJson.ok(userConfigService.describe(com.wisesoft.ai.util.RequestUser.uid()));
    }

    @Operation(summary = "保存个人对话偏好", description = "扁平的 {配置键: 值}（键必须在 config-schema.json 标记 personal）；"
            + "字段缺省=不修改，空串=清除该项回落系统全局（模型类字段即清除个人模型）。"
            + "模型引用须归属本人登记（他人供应商拒用）且类型与字段一致；保存即生效（仅对本人问答/本人资源生效）")
    @PutMapping("/settings")
    public ResultJson saveSettings(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(userConfigService.save(com.wisesoft.ai.util.RequestUser.uid(),
                com.wisesoft.ai.util.RequestUser.role(), body), "已保存");
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
