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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户注册与成员归属（Phase 4 · P9）。
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
    private final AdminGuard adminGuard;

    @Operation(summary = "租户清单（管理）",
            description = "每个租户的名称、启停状态与成员数（成员数跨租户统计，是平台视角）")
    @GetMapping
    public ResultJson<List<Map<String, Object>>> list(HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("租户管理仅管理员可查看");
        return ResultJson.ok(tenantService.listWithCounts());
    }

    @Operation(summary = "开通租户",
            description = "{\"id\":\"标识（小写字母数字-_，2-32 位，直接作为数据归属标识）\",\"name\":\"显示名\","
                    + "\"contact\":\"联系人\",\"note\":\"备注\"}。新租户初始为空：成员要从「成员管理」里把账号归属改过来")
    @PostMapping
    public ResultJson<Map<String, Object>> create(@RequestBody Map<String, String> body,
                                                  HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("租户管理仅管理员可操作");
        var t = tenantService.create(body == null ? null : body.get("id"),
                body == null ? null : body.get("name"),
                body == null ? null : body.get("contact"),
                body == null ? null : body.get("note"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        return ResultJson.ok(out, "已开通租户");
    }

    @Operation(summary = "启停租户",
            description = "{\"enabled\":true|false}。停用只挡该租户成员登录，数据一律保留；默认租户不能停用")
    @PostMapping("/{id}/status")
    public ResultJson status(@PathVariable("id") String id, @RequestBody Map<String, Object> body,
                             HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("租户管理仅管理员可操作");
        Object enabled = body == null ? null : body.get("enabled");
        tenantService.setStatus(id, enabled == null || Boolean.parseBoolean(String.valueOf(enabled)));
        return ResultJson.ok("已更新");
    }

    @Operation(summary = "变更成员归属",
            description = "{\"uid\":\"成员账号\",\"tenantId\":\"目标租户\"}。只改账号归属："
                    + "他已产出的会话、知识库、工作流留在原租户（要合并资源走显式的资源转移，不藏在改归属里）")
    @PostMapping("/member")
    public ResultJson moveMember(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("租户管理仅管理员可操作");
        tenantService.moveMember(body == null ? null : body.get("uid"),
                body == null ? null : body.get("tenantId"));
        return ResultJson.ok("已变更归属");
    }

    // ==================== 自助开通：公开申请 + 平台级批准 ====================

    @Operation(summary = "提交租户开通申请（免登录）",
            description = "公开入口：填机构名称、联系人、邮箱与用途说明。只落一条待审记录，"
                    + "开通由平台级管理员批准——自动放行等于把「谁能在这个平台上开客户」交给互联网。"
                    + "按 IP 限流，同机构/同邮箱有 pending 单时拒绝重复提交。")
    @PostMapping("/apply")
    public ResultJson<Map<String, Object>> apply(@RequestBody Map<String, String> body,
                                                 HttpServletRequest request) {
        return ResultJson.ok(requestService.apply(
                body == null ? null : body.get("orgName"),
                body == null ? null : body.get("desiredId"),
                body == null ? null : body.get("contactName"),
                body == null ? null : body.get("contactEmail"),
                body == null ? null : body.get("purpose"),
                clientIp(request)), "申请已提交，请等平台处理");
    }

    @Operation(summary = "开通申请清单", description = "status 留空=全部（待审优先由前端排）；仅平台级管理员可看")
    @GetMapping("/requests")
    public ResultJson<List<Map<String, Object>>> requests(@RequestParam(value = "status", required = false) String status,
                                                          HttpServletRequest request) {
        requireSuper(request);
        return ResultJson.ok(requestService.list(status));
    }

    @Operation(summary = "批准开通申请",
            description = "开租户 + 建首位管理员账号（标识 <租户>-admin，随机口令只在这次结果里出现一次）。"
                    + "申请人没填期望标识时必须在这里给一个。")
    @PostMapping("/requests/{id}/approve")
    public ResultJson<Map<String, Object>> approveRequest(@PathVariable("id") String id,
                                                          @RequestBody(required = false) Map<String, String> body,
                                                          HttpServletRequest request) {
        requireSuper(request);
        return ResultJson.ok(requestService.approve(id, com.wenqu.ai.util.RequestUser.uid(),
                body == null ? null : body.get("tenantId")), "已开通");
    }

    @Operation(summary = "驳回开通申请", description = "驳回不动任何数据，只把这一单结掉并留说明")
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
        if (!adminGuard.isAdmin(request)) throw new com.wenqu.ai.common.BizException("开通申请仅管理员可处理");
        if (!com.wenqu.ai.util.RequestUser.superadmin()) {
            throw new com.wenqu.ai.common.BizException("批准租户开通申请仅平台级管理员可执行");
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String x = request.getHeader("X-Forwarded-For");
        if (x != null && !x.isBlank()) {
            int comma = x.indexOf(',');
            return (comma > 0 ? x.substring(0, comma) : x).trim();
        }
        return request.getRemoteAddr();
    }

    @Operation(summary = "租户落点清单（备份与恢复用）",
            description = "该租户在库里与盘上的全部落点：成员、按租户归属的表与行数、磁盘目录（记忆/沙盒工作区/产物/附件/外溢原文/自装技能）、"
                    + "Redis 键形态，以及不随租户走的全局部分。备份脚本按这份清单取数，路径规则不在脚本里重抄一遍。仅平台级可用。")
    @GetMapping("/{id}/inventory")
    public ResultJson<Map<String, Object>> inventory(@PathVariable("id") String id, HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("租户落点清单仅平台级管理员可查");
        if (!com.wenqu.ai.util.RequestUser.superadmin()) {
            throw new com.wenqu.ai.common.BizException("租户落点清单会摊出磁盘路径与库内行数，仅平台级管理员可查");
        }
        return ResultJson.ok(tenantInventory.of(id));
    }

}
