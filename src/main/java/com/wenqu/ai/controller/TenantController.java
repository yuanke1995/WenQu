package com.wenqu.ai.controller;

import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.TenantDiskInventory;
import com.wenqu.ai.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 租户注册与成员归属。
 *
 * <p>只有管理员可用，且都是<b>平台级</b>动作：开通/停用租户、把成员挪到某个租户。
 * 成员列表本身仍是「成员管理」那一个入口（按登录者的租户过滤），这里不另开一份人名单。
 *
 * <p>刻意没有的端点：删除租户。行级隔离下删租户等于按归属批量删光它的全部数据，
 * 那不是一个应该出现在管理页上的按钮——要下线一个租户就停用它（挡登录、留数据）。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/tenant")
@RequiredArgsConstructor
@Tag(name = "租户管理", description = "多租户：开通/启停租户、成员归属变更、租户落点清单（备份与恢复）")
public class TenantController {

    private final TenantService tenantService;
    private final TenantDiskInventory tenantInventory;
    private final com.wenqu.ai.service.TenantRequestService requestService;
    private final com.wenqu.ai.service.TenantInviteService inviteService;
    private final com.wenqu.ai.service.TenantOnboardingService onboardingService;
    private final com.wenqu.ai.service.TenantMaterialService materialService;
    private final com.wenqu.ai.service.TenantOpenInviteService openInviteService;
    private final com.wenqu.ai.service.ConfigService configService;
    private final com.wenqu.ai.service.RateLimitService rateLimitService;
    private final AdminGuard adminGuard;

    @Operation(summary = "租户清单（管理）",
            description = "每个租户的名称、启停状态与成员数（成员数跨租户统计，是平台视角）。"
                    + "平台级管理员看全量；租户级管理员只看自家一行——别家客户的存在与名字不外泄")
    @Permission("tenant:read")
    @GetMapping
    public ResultJson<List<Map<String, Object>>> list(HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "tenant:read")) return ResultJson.error("租户管理仅管理员可查看");
        List<Map<String, Object>> rows = tenantService.listWithCounts();
        if (!com.wenqu.ai.util.RequestUser.superadmin()) {
            String mine = com.wenqu.ai.util.TenantContext.current();
            rows = rows.stream().filter(t -> mine != null && mine.equals(String.valueOf(t.get("id")))).toList();
        }
        return ResultJson.ok(rows);
    }

    @Operation(summary = "开通租户",
            description = "{\"id\":\"标识（小写字母数字-_，2-32 位，直接作为数据归属标识）\",\"name\":\"显示名\","
                    + "\"contact\":\"联系人\",\"note\":\"备注\",\"plan\":\"trial|standard|dedicated\",\"domain\":\"company.com\"}。"
                    + "新租户初始为空：成员要从「成员管理」里把账号归属改过来")
    @Permission("tenant:manage")
    @PostMapping
    public ResultJson<Map<String, Object>> create(@RequestBody Map<String, String> body,
                                                  HttpServletRequest request) {
        requirePlatform(request);
        var t = tenantService.create(body == null ? null : body.get("id"),
                body == null ? null : body.get("name"),
                body == null ? null : body.get("contact"),
                body == null ? null : body.get("note"),
                body == null ? null : body.get("plan"),
                body == null ? null : body.get("domain"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        return ResultJson.ok(out, "已开通租户");
    }

    @Operation(summary = "启停租户",
            description = "{\"enabled\":true|false}。停用只挡该租户成员登录，数据一律保留；默认租户不能停用")
    @Permission("tenant:manage")
    @PostMapping("/{id}/status")
    public ResultJson status(@PathVariable("id") String id, @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        requirePlatform(request);
        Object enabled = body == null ? null : body.get("enabled");
        tenantService.setStatus(id, enabled == null || Boolean.parseBoolean(String.valueOf(enabled)));
        return ResultJson.ok("已更新");
    }

    @Operation(summary = "变更成员归属",
            description = "{\"uid\":\"成员账号\",\"tenantId\":\"目标租户\"}。只改账号归属："
                    + "他已产出的会话、知识库、工作流留在原租户（要合并资源走显式的资源转移，不藏在改归属里）")
    @Permission("tenant:manage")
    @PostMapping("/member")
    public ResultJson moveMember(@RequestBody Map<String, String> body, HttpServletRequest request) {
        requirePlatform(request);
        tenantService.moveMember(body == null ? null : body.get("uid"),
                body == null ? null : body.get("tenantId"));
        return ResultJson.ok("已变更归属");
    }

    // ==================== 自助开通：公开申请 + 平台级批准 ====================

    @Operation(summary = "提交租户开通申请（免登录）",
            description = "公开入口：填机构名称、联系人、邮箱、用途、规模与可选材料。只落一条待审记录，"
                    + "开通由平台级管理员批准——自动放行等于把「谁能在这个平台上开客户」交给互联网。"
                    + "按 IP 限流，同机构/同邮箱有 pending 单时拒绝重复提交。")
    @PostMapping("/apply")
    public ResultJson<Map<String, Object>> apply(@RequestBody Map<String, Object> body,
                                                 HttpServletRequest request) {
        return ResultJson.ok(requestService.apply(
                str(body, "orgName"),
                str(body, "desiredId"),
                str(body, "contactName"),
                str(body, "contactEmail"),
                str(body, "purpose"),
                str(body, "scale"),
                str(body, "domain"),
                intOf(body.get("ssoRequested")),
                str(body, "materials"),
                str(body, "resubmitOf"),
                clientIp(request)), "申请已提交，请等平台处理");
    }

    @Operation(summary = "工作区标识可用性（免登录）", description = "公开、按 IP 限流；只返回 available/reason/suggestions")
    @GetMapping("/availability")
    public ResultJson<Map<String, Object>> availability(@RequestParam("desiredId") String desiredId,
                                                        HttpServletRequest request) {
        return ResultJson.ok(requestService.availability(desiredId, clientIp(request)));
    }

    @Operation(summary = "申请进度查询（免登录）", description = "申请编号 + 提交邮箱双因子；不匹配时统一文案，避免枚举")
    @GetMapping("/requests/status")
    public ResultJson<Map<String, Object>> requestStatus(@RequestParam("id") String id,
                                                         @RequestParam("email") String email,
                                                         HttpServletRequest request) {
        return ResultJson.ok(requestService.publicStatus(id, email, clientIp(request)));
    }

    @Operation(summary = "驳回后重新申请预填（免登录）", description = "仅已驳回的申请；仍要重新提交，不自动复活")
    @GetMapping("/requests/resubmit")
    public ResultJson<Map<String, Object>> resubmitPreview(@RequestParam("id") String id,
                                                           @RequestParam("email") String email,
                                                           HttpServletRequest request) {
        return ResultJson.ok(requestService.resubmitPreview(id, email, clientIp(request)));
    }

    @Operation(summary = "待审申请数量", description = "侧栏角标用；管理员可读，非平台级返回 0")
    @Permission("tenant:read")
    @GetMapping("/requests/pending-count")
    public ResultJson<Map<String, Object>> pendingCount(HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "tenant:read")) return ResultJson.error("无权限");
        long n = com.wenqu.ai.util.RequestUser.superadmin() ? requestService.pendingCount() : 0;
        return ResultJson.ok(Map.of("count", n));
    }

    @Operation(summary = "重发凭据 / 重置首位管理员口令",
            description = "{\"resetPassword\":true|false}。明文口令不落库，reset=false 只重发提醒；"
                    + "reset=true 生成新口令、旧口令立即失效，并写入审计")
    @Permission("tenant:manage")
    @PostMapping("/requests/{id}/deliver")
    public ResultJson<Map<String, Object>> deliver(@PathVariable("id") String id,
                                                   @RequestBody(required = false) Map<String, Object> body,
                                                   HttpServletRequest request) {
        requireSuper(request);
        boolean reset = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("resetPassword", false)));
        return ResultJson.ok(requestService.deliver(id, reset), "已处理");
    }

    @Operation(summary = "租户申请材料上传（免登录）", description = "PDF/PNG/JPG/WEBP，单份 ≤ 8MB；返回 {name,path,url,size}")
    @PostMapping(value = "/materials", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResultJson<Map<String, Object>> uploadMaterial(@RequestParam("file") MultipartFile file,
                                                          HttpServletRequest request) {
        rateLimitService.checkRateLimit("apply-material", "ip:" + clientIp(request));
        return ResultJson.ok(materialService.save(file));
    }

    @Operation(summary = "平台租户概览", description = "租户总量 / 启停归档 / 待审 / 本月新增 / 最久等待")
    @Permission("tenant:read")
    @GetMapping("/overview")
    public ResultJson<Map<String, Object>> overview(HttpServletRequest request) {
        requireSuper(request);
        return ResultJson.ok(tenantService.overview());
    }

    @Operation(summary = "租户详情", description = "注册信息 + 品牌 + 套餐 + 计数；平台级或本租户管理员")
    @Permission("tenant:read")
    @GetMapping("/{id}")
    public ResultJson<Map<String, Object>> detail(@PathVariable("id") String id, HttpServletRequest request) {
        requireTenantReadable(id, request);
        return ResultJson.ok(tenantService.detail(id));
    }

    @Operation(summary = "租户用量", description = "成员/知识库/文档/会话/本月 tokens/套餐上限；平台级或本租户管理员")
    @Permission("tenant:read")
    @GetMapping("/{id}/usage")
    public ResultJson<Map<String, Object>> usage(@PathVariable("id") String id, HttpServletRequest request) {
        requireTenantReadable(id, request);
        return ResultJson.ok(tenantService.usage(id));
    }

    @Operation(summary = "更新租户套餐与企业域名", description = "{\"plan\":\"trial|standard|dedicated\",\"domain\":\"company.com\"}；仅平台级")
    @Permission("tenant:manage")
    @PostMapping("/{id}/plan")
    public ResultJson updatePlan(@PathVariable("id") String id, @RequestBody Map<String, String> body,
                                 HttpServletRequest request) {
        requirePlatform(request);
        tenantService.updatePlan(id, body == null ? null : body.get("plan"),
                body == null ? null : body.get("domain"));
        return ResultJson.ok("已更新");
    }

    @Operation(summary = "自动开通设置", description = "{\"enabled\":true,\"domains\":\"a.com,b.com\"}；仅平台级")
    @Permission("tenant:manage")
    @GetMapping("/auto-approve")
    public ResultJson<Map<String, Object>> autoApprove(HttpServletRequest request) {
        requireSuper(request);
        return ResultJson.ok(autoApproveView());
    }

    @Operation(summary = "保存自动开通设置", description = "默认关闭；开启后命中域名白名单的申请会自动开通（口令邮件送达）")
    @Permission("tenant:manage")
    @PostMapping("/auto-approve")
    public ResultJson saveAutoApprove(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireSuper(request);
        boolean enabled = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", false)));
        String domains = body == null ? "" : String.valueOf(body.getOrDefault("domains", ""));
        domains = domains.trim().toLowerCase().replaceAll("\\s+", "");
        configService.putInternal("tenant.autoApprove.enabled", String.valueOf(enabled));
        configService.putInternal("tenant.autoApprove.domains", domains);
        return ResultJson.ok(autoApproveView(), "已保存");
    }

    @Operation(summary = "开通申请清单", description = "status 留空=全部（待审优先由前端排）；仅平台级管理员可看")
    @Permission("tenant:read")
    @GetMapping("/requests")
    public ResultJson<List<Map<String, Object>>> requests(@RequestParam(value = "status", required = false) String status,
                                                          HttpServletRequest request) {
        requireSuper(request);
        return ResultJson.ok(requestService.list(status));
    }

    @Operation(summary = "批准开通申请",
            description = "开租户 + 建首位管理员账号（标识 <租户>-admin，随机口令只在这次结果里出现一次）。"
                    + "申请人没填期望标识时必须在这里给一个。")
    @Permission("tenant:manage")
    @PostMapping("/requests/{id}/approve")
    public ResultJson<Map<String, Object>> approveRequest(@PathVariable("id") String id,
                                                          @RequestBody(required = false) Map<String, String> body,
                                                          HttpServletRequest request) {
        requireSuper(request);
        return ResultJson.ok(requestService.approve(id, com.wenqu.ai.util.RequestUser.uid(),
                body == null ? null : body.get("tenantId")), "已开通");
    }

    @Operation(summary = "驳回开通申请", description = "驳回不动任何数据，只把这一单结掉并留说明")
    @Permission("tenant:manage")
    @PostMapping("/requests/{id}/reject")
    public ResultJson<Map<String, Object>> rejectRequest(@PathVariable("id") String id,
                                                         @RequestBody(required = false) Map<String, String> body,
                                                         HttpServletRequest request) {
        requireSuper(request);
        return ResultJson.ok(requestService.reject(id, com.wenqu.ai.util.RequestUser.uid(),
                body == null ? null : body.get("note")), "已驳回");
    }

    /** 批准＝开一家新客户，只有平台级能做（租户管理员批准等于自己给自己开租户） */
    private void requireSuper(HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "tenant:manage")) throw new com.wenqu.ai.common.BizException("开通申请仅管理员可处理");
        if (!com.wenqu.ai.util.RequestUser.superadmin()) {
            throw new com.wenqu.ai.common.BizException("批准租户开通申请仅平台级管理员可执行");
        }
    }

    /**
     * 平台级租户动作（直开/启停/划人）的判据：只挡 isAdmin 等于把别家客户交给别家管理员摆布——
     * 租户甲管理员能停掉租户乙、能把租户乙的成员划归自己，
     * 故与「批准开通申请」同款判据：必须是平台级管理员。
     */
    private void requirePlatform(HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "tenant:manage")) throw new com.wenqu.ai.common.BizException("租户管理仅管理员可操作");
        if (!com.wenqu.ai.util.RequestUser.superadmin()) {
            throw new com.wenqu.ai.common.BizException("该动作是平台级租户管理，仅平台级管理员可执行");
        }
    }

    /** 客户端 IP：统一收口到 {@link com.wenqu.ai.util.ClientIp}（XFF 首段可伪造，不再盲信） */
    private static String clientIp(HttpServletRequest request) {
        return com.wenqu.ai.util.ClientIp.of(request);
    }

    @Operation(summary = "租户落点清单（备份与恢复用）",
            description = "该租户在库里与盘上的全部落点：成员、按租户归属的表与行数、磁盘目录（记忆/沙盒工作区/产物/附件/外溢原文/自装技能）、"
                    + "Redis 键形态，以及不随租户走的全局部分。备份脚本按这份清单取数，路径规则不在脚本里重抄一遍。仅平台级可用。")
    @Permission("tenant:read")
    @GetMapping("/{id}/inventory")
    public ResultJson<Map<String, Object>> inventory(@PathVariable("id") String id, HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "tenant:read")) return ResultJson.error("租户落点清单仅平台级管理员可查");
        if (!com.wenqu.ai.util.RequestUser.superadmin()) {
            throw new com.wenqu.ai.common.BizException("租户落点清单会摊出磁盘路径与库内行数，仅平台级管理员可查");
        }
        return ResultJson.ok(tenantInventory.of(id));
    }

    // ==================== 多租户生命周期：归档 / 品牌 / 邀请 / 首登引导 ====================

    @Operation(summary = "归档租户", description = "归档=只读：成员不可登录、数据保留、可导出、可恢复；默认租户不能归档")
    @Permission("tenant:manage")
    @PostMapping("/{id}/archive")
    public ResultJson archive(@PathVariable("id") String id, HttpServletRequest request) {
        requirePlatform(request);
        tenantService.setArchived(id, true);
        return ResultJson.ok("已归档");
    }

    @Operation(summary = "恢复归档租户", description = "恢复为启用状态；成员即可重新登录")
    @Permission("tenant:manage")
    @PostMapping("/{id}/restore")
    public ResultJson restore(@PathVariable("id") String id, HttpServletRequest request) {
        requirePlatform(request);
        tenantService.setArchived(id, false);
        return ResultJson.ok("已恢复");
    }

    @Operation(summary = "更新租户品牌",
            description = "{\"brandName\":\"\",\"brandLogo\":\"\",\"brandColor\":\"\",\"loginHint\":\"\"}；空串=恢复默认。"
                    + "平台级可改任意租户（需 tenant:manage）；租户管理员凭 tenant:read 只能改自己这一家")
    @Permission("tenant:read")
    @PostMapping("/{id}/brand")
    public ResultJson updateBrand(@PathVariable("id") String id, @RequestBody Map<String, String> body,
                                  HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "tenant:read")) {
            throw new com.wenqu.ai.common.BizException("租户管理仅管理员可操作");
        }
        String mine = com.wenqu.ai.util.TenantContext.current();
        if (com.wenqu.ai.util.RequestUser.superadmin()) {
            requirePlatform(request);
        } else if (mine == null || !mine.equals(id)) {
            throw new com.wenqu.ai.common.BizException("只能修改本租户的品牌");
        }
        tenantService.updateBrand(id,
                body == null ? null : body.get("brandName"),
                body == null ? null : body.get("brandLogo"),
                body == null ? null : body.get("brandColor"),
                body == null ? null : body.get("loginHint"));
        return ResultJson.ok("已更新");
    }

    // ==================== 邀请式开通（P2） ====================

    @Operation(summary = "生成开通邀请", description = "平台预批准；返回 path=/open/{token}，明文 token 仅此一次")
    @Permission("tenant:manage")
    @PostMapping("/open-invites")
    public ResultJson<Map<String, Object>> createOpenInvite(@RequestBody(required = false) Map<String, Object> body,
                                                            HttpServletRequest request) {
        requireSuper(request);
        return ResultJson.ok(openInviteService.create(
                str(body, "orgName"), str(body, "desiredId"), str(body, "contactName"),
                str(body, "contactEmail"), str(body, "note"),
                intOf(body == null ? null : body.get("maxUses")),
                intOf(body == null ? null : body.get("expireDays"))), "已生成开通邀请");
    }

    @Operation(summary = "开通邀请清单", description = "不返回 token 明文；状态按有效期/次数实时折算")
    @Permission("tenant:read")
    @GetMapping("/open-invites")
    public ResultJson<List<Map<String, Object>>> openInvites(HttpServletRequest request) {
        requireSuper(request);
        return ResultJson.ok(openInviteService.list());
    }

    @Operation(summary = "撤销开通邀请")
    @Permission("tenant:manage")
    @PostMapping("/open-invites/{id}/revoke")
    public ResultJson revokeOpenInvite(@PathVariable("id") String id, HttpServletRequest request) {
        requireSuper(request);
        openInviteService.revoke(id);
        return ResultJson.ok("已撤销");
    }

    @Operation(summary = "开通邀请预览（免登录）", description = "只回预填信息，不回 token 或其它邀请")
    @GetMapping("/open-invites/preview")
    public ResultJson<Map<String, Object>> previewOpenInvite(@RequestParam("token") String token,
                                                             HttpServletRequest request) {
        return ResultJson.ok(openInviteService.preview(token, clientIp(request)));
    }

    @Operation(summary = "接受开通邀请（免登录）", description = "建租户 + 首位管理员（密码由客户自设）并发送凭据")
    @PostMapping("/open-invites/accept")
    public ResultJson<Map<String, Object>> acceptOpenInvite(@RequestBody Map<String, String> body,
                                                            HttpServletRequest request) {
        return ResultJson.ok(openInviteService.accept(
                body == null ? null : body.get("token"),
                body == null ? null : body.get("orgName"),
                body == null ? null : body.get("desiredId"),
                body == null ? null : body.get("contactName"),
                body == null ? null : body.get("email"),
                body == null ? null : body.get("password"),
                clientIp(request)), "已开通");
    }

    /** 自动开通设置视图（配置缺失按关闭/空清单展示） */
    private Map<String, Object> autoApproveView() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", configService.getBoolean("tenant.autoApprove.enabled", false));
        String domains = configService.get("tenant.autoApprove.domains");
        out.put("domains", domains == null ? "" : domains);
        return out;
    }

    /** 平台级可读任意租户；租户管理员只能读自己这一家 */
    private void requireTenantReadable(String id, HttpServletRequest request) {
        if (!adminGuard.requirePermission(request, "tenant:read")) {
            throw new com.wenqu.ai.common.BizException("租户管理仅管理员可查看");
        }
        String mine = com.wenqu.ai.util.TenantContext.current();
        if (!com.wenqu.ai.util.RequestUser.superadmin() && (mine == null || !mine.equals(id))) {
            throw new com.wenqu.ai.common.BizException("只能查看本租户");
        }
    }

    private static String str(Map<String, ?> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @Operation(summary = "生成租户邀请",
            description = "{\"roleIds\":\"成员,知识库管理员\",\"deptId\":\"\",\"email\":\"\",\"maxUses\":1,\"expireDays\":7}；"
                    + "返回 path=/join/{token}，明文 token 仅此一次")
    @Permission("tenant:manage")
    @PostMapping("/invites")
    public ResultJson<Map<String, Object>> createInvite(@RequestBody(required = false) Map<String, Object> body) {
        return ResultJson.ok(inviteService.create(
                body == null ? null : String.valueOf(body.getOrDefault("roleIds", "")),
                body == null ? null : String.valueOf(body.getOrDefault("deptId", "")),
                body == null ? null : String.valueOf(body.getOrDefault("email", "")),
                body == null ? null : intOf(body.get("maxUses")),
                body == null ? null : intOf(body.get("expireDays"))), "已生成邀请链接");
    }

    @Operation(summary = "本租户在途邀请", description = "不返回 token 明文；过期/用尽在 status 里如实呈现")
    @Permission("tenant:read")
    @GetMapping("/invites")
    public ResultJson<List<Map<String, Object>>> invites() {
        return ResultJson.ok(inviteService.list());
    }

    @Operation(summary = "撤销邀请", description = "已用尽的链接也能撤销（仅挡未来的使用）")
    @Permission("tenant:manage")
    @PostMapping("/invites/{id}/revoke")
    public ResultJson revokeInvite(@PathVariable("id") String id) {
        inviteService.revoke(id);
        return ResultJson.ok("已撤销");
    }

    @Operation(summary = "新租户首登引导状态",
            description = "五项状态按真实数据计算：改密（审计）/ 建部门 / 邀请成员 / 绑定模型 / 建知识库。单租户部署 required=false")
    @Permission("self:preference")
    @GetMapping("/onboarding")
    public ResultJson<Map<String, Object>> onboarding() {
        return ResultJson.ok(onboardingService.status(com.wenqu.ai.util.RequestUser.uid()));
    }

    private static Integer intOf(Object v) {
        if (v == null) return null;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

}
