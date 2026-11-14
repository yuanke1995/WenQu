package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.OrgService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

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
    private final com.wenqu.ai.service.AuthService authService;
    private final com.wenqu.ai.service.RoleService roleService;
    private final com.wenqu.ai.service.UserConfigService userConfigService;
    private final com.wenqu.ai.service.ConfigService configService;
    private final com.wenqu.ai.service.ConnectivityProbeService connectivityProbeService;
    /** 框架会话表：个人偏好里「只能在 build 期定」的那一项（自动提炼记忆）改完要让常驻实例作废。 */
    private final com.wenqu.ai.agentx.FrameworkSessions frameworkSessions;

    @Operation(summary = "用户列表", description = "返回全部用户（按昵称升序）：User 全字段 + roles（角色集）+ role（主角色）")
    @Permission("user:read")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(orgService.listUsersWithRoles());
    }

    @Operation(summary = "成员域概览", description = "成员管理页首屏统计条：在册/启用/禁用/未分配部门/今日登录/长期未登录/锁定中，"
            + "在途邀请数、7 天内临期授权数、认证方式分布、部门人数分布。只读聚合，不参与鉴权判定")
    @Permission("user:read")
    @GetMapping("/overview")
    public ResultJson overview() {
        return ResultJson.ok(orgService.memberOverview());
    }

    @Operation(summary = "成员分页查询", description = "关键字（昵称/登录账号）与部门、状态在库侧过滤，按最近登录倒序分页；"
            + "另支持会话治理的风险口径：locked=true（锁定中）、minFail（连续失败阈值）、inactiveDays（长期未登录）。"
            + "authSource=local 表示只看本地账号（无外部身份绑定），否则为认证源 id；roleSource=manual|idp 按角色授予来源过滤。"
            + "返回 {total, items, page, size}，items 字段口径同 /user/list")
    @Permission("user:read")
    @GetMapping("/page")
    public ResultJson page(@Parameter(description = "昵称或登录账号关键字") @RequestParam(value = "kw", required = false) String kw,
                           @Parameter(description = "部门 ID（空=全部）") @RequestParam(value = "departmentId", required = false) String departmentId,
                           @Parameter(description = "状态：1=启用 0=禁用（空=全部）") @RequestParam(value = "status", required = false) Integer status,
                           @Parameter(description = "true=只看锁定中的账号") @RequestParam(value = "locked", required = false) Boolean locked,
                           @Parameter(description = "只看连续失败次数 ≥ 该值的账号") @RequestParam(value = "minFail", required = false) Integer minFail,
                           @Parameter(description = "只看最近登录早于 N 天前（含从未登录）的账号") @RequestParam(value = "inactiveDays", required = false) Integer inactiveDays,
                           @Parameter(description = "true=只看今天登录过的账号") @RequestParam(value = "loggedInToday", required = false) Boolean loggedInToday,
                           @Parameter(description = "认证方式：local=本地账号（无外部绑定），否则为认证源 id") @RequestParam(value = "authSource", required = false) String authSource,
                           @Parameter(description = "角色来源：manual=手动授予 idp=组映射授予") @RequestParam(value = "roleSource", required = false) String roleSource,
                           @Parameter(description = "true=只看未分配部门的成员") @RequestParam(value = "unassigned", required = false) Boolean unassigned,
                           @RequestParam(value = "page", defaultValue = "1") int page,
                           @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResultJson.ok(orgService.pageUsers(kw, departmentId, status, locked, minFail, inactiveDays,
                loggedInToday, authSource, roleSource, Boolean.TRUE.equals(unassigned), page, size));
    }

    @Operation(summary = "批量操作成员", description = "{\"action\":\"setDepartment|addRoles|setStatus\", \"uids\":[\"a\",\"b\"], "
            + "\"departmentId\":\"d1\", \"roles\":[\"admin\"], \"status\":1, \"onlyIfBlank\":false}；"
            + "逐条独立成败，返回 {succeeded:[uid...], failed:[{uid, reason}]}。IdP 授予的角色不可改，"
            + "批量追加命中时该条失败并给出原因")
    @Permission("user:write")
    @PostMapping("/batch")
    public ResultJson batch(@RequestBody Map<String, Object> body) {
        String action = str(body.get("action"));
        List<String> uids = new ArrayList<>();
        if (body.get("uids") instanceof List<?> l) {
            for (Object x : l) {
                if (x != null) uids.add(String.valueOf(x));
            }
        }
        List<String> roles = null;
        if (body.get("roles") instanceof List<?> l) {
            roles = new ArrayList<>();
            for (Object x : l) {
                if (x != null) roles.add(String.valueOf(x));
            }
        }
        Integer status = body.get("status") == null ? null : Integer.parseInt(String.valueOf(body.get("status")));
        boolean onlyIfBlank = Boolean.parseBoolean(String.valueOf(body.get("onlyIfBlank")));
        return ResultJson.ok(orgService.batchUsers(action, uids, str(body.get("departmentId")), roles, status, onlyIfBlank));
    }

    @Operation(summary = "导出成员名单 CSV", description = "参数与 /user/page 一致，导出当前筛选条件下的全部成员；"
            + "行数上限 10000，超出即截断并在文件末尾标注")
    @Permission("user:read")
    @GetMapping("/export")
    public void export(@Parameter(description = "昵称或登录账号关键字") @RequestParam(value = "kw", required = false) String kw,
                       @Parameter(description = "部门 ID（空=全部）") @RequestParam(value = "departmentId", required = false) String departmentId,
                       @Parameter(description = "状态：1=启用 0=禁用（空=全部）") @RequestParam(value = "status", required = false) Integer status,
                       @Parameter(description = "true=只看锁定中的账号") @RequestParam(value = "locked", required = false) Boolean locked,
                       @Parameter(description = "只看连续失败次数 ≥ 该值的账号") @RequestParam(value = "minFail", required = false) Integer minFail,
                       @Parameter(description = "只看最近登录早于 N 天前（含从未登录）的账号") @RequestParam(value = "inactiveDays", required = false) Integer inactiveDays,
                       @Parameter(description = "true=只看今天登录过的账号") @RequestParam(value = "loggedInToday", required = false) Boolean loggedInToday,
                       @Parameter(description = "认证方式：local=本地账号，否则为认证源 id") @RequestParam(value = "authSource", required = false) String authSource,
                       @Parameter(description = "角色来源：manual|idp") @RequestParam(value = "roleSource", required = false) String roleSource,
                       @Parameter(description = "true=只看未分配部门的成员") @RequestParam(value = "unassigned", required = false) Boolean unassigned,
                       jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"wenqu-members-" + java.time.LocalDate.now() + ".csv\"");
        Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        orgService.exportMembersCsv(kw, departmentId, status, locked, minFail, inactiveDays, loggedInToday,
                authSource, roleSource, Boolean.TRUE.equals(unassigned), w);
        w.flush();
    }

    @Operation(summary = "新建用户", description = "{\"uid\":\"alice\",\"username\":\"Alice\",\"password\":\"必填\",\"departmentId\":\"可选\",\"roles\":[\"user\"]}")
    @Permission("user:write")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        List<String> roles = new java.util.ArrayList<>();
        if (body.get("roles") instanceof List<?> l) {
            for (Object x : l) if (x != null) roles.add(String.valueOf(x));
        }
        if (roles.isEmpty()) roles.add("user");
        return ResultJson.ok(orgService.createUser(
                str(body.get("uid")), str(body.get("username")), str(body.get("departmentId")),
                roles, str(body.get("password"))), "已创建");
    }

    @Operation(summary = "重置密码", description = "{\"password\":\"新密码\"}；同时清除登录失败锁定。"
            + "普通用户仅可改自己的密码（本端点对普通用户开放，改别人由管理员端点拦截）")
    @Permission("self:password")
    @PutMapping("/{uid}/password")
    public ResultJson resetPassword(
            @Parameter(description = "登录账号（uid）") @PathVariable("uid") String uid,
            @RequestBody Map<String, Object> body) {
        // 自助改密：非管理员级角色只允许改自己的（uid 来自路径，但必须与登录态一致，防止代改）
        if (!roleService.isAdminAny(com.wenqu.ai.util.RequestUser.roles())
                && !com.wenqu.ai.util.RequestUser.uid().equals(uid)) {
            return ResultJson.error(403, "仅可修改自己的密码");
        }
        authService.resetPassword(uid, str(body.get("password")));
        return ResultJson.ok("密码已重置");
    }

    @Operation(summary = "设置本人通知邮箱", description = "{\"email\":\"name@company.com\"}；空串=清除（不再接收邮件外发）")
    @Permission("self:preference")
    @PutMapping("/email")
    public ResultJson updateEmail(@RequestBody Map<String, Object> body) {
        orgService.updateEmail(com.wenqu.ai.util.RequestUser.uid(), str(body.get("email")));
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "修改用户", description = "{\"username\":\"\",\"departmentId\":\"\",\"roles\":[\"user\",\"admin\"],\"status\":1|0}；roles 为角色集（manual 全量替换）")
    @Permission("user:write")
    @PutMapping("/{uid}")
    public ResultJson update(
            @Parameter(description = "登录账号（uid）") @PathVariable("uid") String uid,
            @RequestBody Map<String, Object> body) {
        Integer status = body.get("status") == null ? null : Integer.parseInt(String.valueOf(body.get("status")));
        List<String> roles = null;
        Object rolesRaw = body.get("roles");
        if (rolesRaw instanceof List<?> l) {
            roles = l.stream().map(String::valueOf).toList();
        }
        // C1 临时授权：roleExpires = {角色编码: ISO 到期时刻}（可选；未提及=永久）
        java.util.Map<String, java.time.LocalDateTime> roleExpires = new java.util.LinkedHashMap<>();
        Object expRaw = body.get("roleExpires");
        if (expRaw instanceof java.util.Map<?, ?> m) {
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String v = String.valueOf(e.getValue()).trim();
                if (v.isEmpty()) continue;
                try {
                    roleExpires.put(String.valueOf(e.getKey()), java.time.LocalDateTime.parse(v.length() > 10 && v.indexOf('T') < 0 ? v.replace(' ', 'T') : v));
                } catch (Exception ignored) {
                    // 非法时间格式忽略该键（角色仍按永久授予）
                }
            }
        }
        orgService.updateUser(uid, str(body.get("username")), str(body.get("departmentId")), roles, status, roleExpires);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "删除用户", description = "最后一名超级管理员不可删除")
    @Permission("user:delete")
    @DeleteMapping("/{uid}")
    public ResultJson delete(@Parameter(description = "登录账号（uid）") @PathVariable("uid") String uid) {
        orgService.deleteUser(uid);
        return ResultJson.ok("已删除");
    }

    @Operation(summary = "解绑 OIDC 单点登录", description = "清除该账号在统一身份表的全部外部身份绑定；解绑后须重新走对应 IdP 登录才能再次绑定。仅管理员可操作他人")
    @Permission("user:delete")
    @DeleteMapping("/{uid}/oidc-binding")
    public ResultJson unbindOidc(@Parameter(description = "登录账号（uid）") @PathVariable("uid") String uid) {
        orgService.unbindOidc(uid);
        return ResultJson.ok("已解绑单点登录");
    }

    @Operation(summary = "逐源解绑身份", description = "只清除该账号在指定认证源下的外部身份绑定，其他认证源不动；解绑后须重新走对应认证源登录才能再次绑定。仅管理员可操作他人")
    @Permission("user:delete")
    @DeleteMapping("/{uid}/identity-binding/{providerId}")
    public ResultJson unbindIdentity(@Parameter(description = "登录账号（uid）") @PathVariable("uid") String uid,
                                     @Parameter(description = "认证源 id") @PathVariable("providerId") String providerId) {
        orgService.unbindIdentity(uid, providerId);
        return ResultJson.ok("已解绑");
    }

    @Operation(summary = "个人偏好读取", description = "本人个人设置：defaultModel（个人默认聊天模型引用，空=跟随系统全局）+ models（可用聊天模型清单，选择器数据源）")
    @Permission("self:preference")
    @GetMapping("/preference")
    public ResultJson preference() {
        return ResultJson.ok(orgService.getPreference(com.wenqu.ai.util.RequestUser.uid(),
                com.wenqu.ai.util.RequestUser.role()));
    }

    @Operation(summary = "设置个人偏好", description = "{\"defaultModel\":\"引用\",\"memoryEnabled\":true|false}；"
            + "字段缺省(null)=不修改，空串=清除；个人默认聊天模型在会话未手动切换时生效。"
            + "memoryEnabled=用户级长期记忆自动提炼开关（仅关生成，已存记忆仍注入）。"
            + "重排/记忆向量等「模型默认」不走本端点：重排归知识库检索设置绑定；问答对生成与 GraphRAG 抽取"
            + "已回落个人默认聊天模型（本端点的 defaultModel），无需单独配置")
    @Permission("self:preference")
    @PutMapping("/preference")
    public ResultJson setPreference(@RequestBody Map<String, Object> body) {
        String uid = com.wenqu.ai.util.RequestUser.uid();
        Boolean memoryEnabled = body.containsKey("memoryEnabled") ? bool(body.get("memoryEnabled")) : null;
        orgService.setPreference(uid,
                body.containsKey("defaultModel") ? str(body.get("defaultModel")) : null,
                memoryEnabled);
        // 提炼闸是在建实例时定的（MemoryConfig 烤进常驻实例）：不标脏，下一轮仍按旧设置跑，
        // 用户会看到「明明关了还在往记忆里写」
        if (memoryEnabled != null) frameworkSessions.markConfigStale(uid);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "修改我的资料", description = "{\"username\":\"新昵称\",\"avatar\":\"emoji 或上传图片 URL 或空串清除\"}；"
            + "仅本人（uid 取登录态，不可改 uid）；字段缺省(null)=不修改，avatar 空串=清除头像回落昵称首字。"
            + "头像图片地址须为本人上传返回的 /ai/images/avatar/ 路径（防止存入任意 URL）")
    @Permission("self:preference")
    @PutMapping("/profile")
    public ResultJson updateOwnProfile(@RequestBody Map<String, Object> body) {
        orgService.updateOwnProfile(com.wenqu.ai.util.RequestUser.uid(),
                body.containsKey("username") ? str(body.get("username")) : null,
                body.containsKey("avatar") ? str(body.get("avatar")) : null);
        return ResultJson.ok("已保存");
    }

    @Operation(summary = "上传我的头像", description = "multipart file（PNG/JPEG/GIF/WebP，≤2MB）；仅本人。"
            + "服务端校验图片类型与魔数、落盘到 images/avatar/、写入用户头像（自动清理旧上传图），返回 {url}（/ai/images/avatar/...）。"
            + "该 URL 是公开可访问的头像图（不可猜 UUID），可直接在 <img> 中加载，无需携带令牌")
    @Permission("self:preference")
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultJson uploadAvatar(
            @Parameter(description = "头像图片") @RequestParam("file") MultipartFile file) {
        String url = orgService.uploadAvatar(com.wenqu.ai.util.RequestUser.uid(), file);
        return ResultJson.ok(java.util.Map.of("url", url));
    }

    @Operation(summary = "个人偏好读取", description = "可个人设置的字段定义（config-schema.json 标记 personal）"
            + "+ 本人当前个人值 + 全局参照值（personalOnly 键无全局层，界面展示内置默认）。"
            + "原「回答偏好」五项已下线；当前在册：知识块填充上限（个人专属）、联网搜索整套"
            + "（服务商/Key/地址/请求模板——Key 密文存库不下发，敏感项以 ******** 掩码标记已设置）")
    @Permission("self:preference")
    @GetMapping("/settings")
    public ResultJson getSettings() {
        return ResultJson.ok(userConfigService.describe(com.wenqu.ai.util.RequestUser.uid()));
    }

    @Operation(summary = "联网搜索连通性探测", description = "按本人已保存的个人搜索配置（服务商/Key/地址/模板，"
            + "与运行时同口径）真实打一次 limit=1 的最小搜索，返回 {available, latencyMs, detail}；"
            + "个人页失焦即存，探测读到的就是刚保存的值。未配置任何个人搜索时探测的是平台内置 SearXNG")
    @Permission("self:preference")
    @PostMapping("/settings/websearch-probe")
    public ResultJson probeWebSearch() {
        String uid = com.wenqu.ai.util.RequestUser.uid();
        // 探测与运行时同一读取口径：装载本人个人覆盖后走平台 probe（provider/baseUrl/key 全部按
        // 覆盖链解析），探测完即清，不污染请求线程
        java.util.Map<String, String> ov = userConfigService.overrides(uid);
        configService.putUserOverrides(ov);
        try {
            return ResultJson.ok(connectivityProbeService.probe("webSearch", null, null, null, null));
        } finally {
            configService.clearUserOverrides();
        }
    }

    @Operation(summary = "保存个人偏好", description = "扁平的 {配置键: 值}（键必须在 config-schema.json 标记 personal，"
            + "当前无此类键，任何提交都会被拒）；字段缺省=不修改，空串=清除该项回落系统全局。"
            + "模型引用须归属本人登记（他人供应商拒用）且类型与字段一致；保存即生效（仅对本人问答/本人资源生效）")
    @Permission("self:preference")
    @PutMapping("/settings")
    public ResultJson saveSettings(@RequestBody Map<String, Object> body) {
        return ResultJson.ok(userConfigService.save(com.wenqu.ai.util.RequestUser.uid(),
                com.wenqu.ai.util.RequestUser.role(), body), "已保存");
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
