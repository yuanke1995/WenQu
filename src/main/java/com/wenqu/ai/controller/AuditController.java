package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.service.AuditService;
import com.wenqu.ai.service.AuditService.TimeRange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一审计事件的查询、导出与链校验（Phase 3 · P11）。
 * <p>
 * 全部仅管理员：审计里含他人姓名、IP 与操作对象，普通成员看自己的用量走「使用统计」，
 * 不能从这里读别人的操作轨迹。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/audit")
@RequiredArgsConstructor
@Tag(name = "审计事件", description = "统一审计事件查询、合规导出与哈希链校验")
public class AuditController {

    private final AuditService auditService;
    private final AdminGuard adminGuard;

    @Operation(summary = "审计事件查询",
            description = "按时间/操作人/动作/关键字分页查（时间倒序）。默认只看得到<b>本租户</b>的事件；"
                    + "平台级（superadmin）可带 tenant=某租户 跨租户审阅。actions 给下拉选项，"
                    + "droppedSinceStart=本进程因队列满未入库的条数（非 0 说明审计有缺口，日志里仍有全文）")
    @GetMapping
    public ResultJson<Map<String, Object>> list(@RequestParam(value = "days", defaultValue = "7") int days,
                                                @RequestParam(value = "actor", required = false) String actor,
                                                @RequestParam(value = "action", required = false) String action,
                                                @RequestParam(value = "keyword", required = false) String keyword,
                                                @RequestParam(value = "page", defaultValue = "1") int page,
                                                @RequestParam(value = "size", defaultValue = "50") int size,
                                                @RequestParam(value = "tenant", required = false) String tenant,
                                                HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("审计事件仅管理员可查");
        TimeRange range = rangeOf(days);
        String t = crossTenant(tenant, request);
        if (t == null) {
            return ResultJson.ok(auditService.query(range.from(), range.to(), blankToNull(actor),
                    blankToNull(action), keyword, page, size));
        }
        return ResultJson.ok(com.wenqu.ai.util.TenantContext.asPlatform(() -> auditService.query(
                range.from(), range.to(), blankToNull(actor), blankToNull(action), keyword, t, page, size)));
    }

    @Operation(summary = "合规导出（CSV）",
            description = "按同一套查询条件导出 CSV（最多 10 万行，逐批写出）。导出头部含留存期与链校验结论，"
                    + "接收方能判断这份材料是否完整可信")
    @GetMapping("/export")
    public void export(@RequestParam(value = "days", defaultValue = "30") int days,
                       @RequestParam(value = "actor", required = false) String actor,
                       @RequestParam(value = "action", required = false) String action,
                       @RequestParam(value = "keyword", required = false) String keyword,
                       @RequestParam(value = "tenant", required = false) String tenant,
                       HttpServletRequest request,
                       jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        if (!adminGuard.isAdmin(request)) {
            response.setStatus(403);
            response.getWriter().write("审计事件仅管理员可导出");
            return;
        }
        TimeRange range = rangeOf(days);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"wenqu-audit-" + LocalDate.now() + ".csv\"");
        Writer w = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        String t = crossTenant(tenant, request);
        long rows = t == null
                ? auditService.exportCsv(range.from(), range.to(), blankToNull(actor),
                blankToNull(action), keyword, w)
                : com.wenqu.ai.util.TenantContext.asPlatform(() -> {
                    try {
                        return auditService.exportCsv(range.from(), range.to(), blankToNull(actor),
                                blankToNull(action), keyword, t, w);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
        auditService.record("导出审计事件 operator={} days={} rows={}", requestUid(), days, rows);
        w.flush();
    }

    @Operation(summary = "哈希链校验",
            description = "逐副本按序号验审计链：报告序号断裂、链头不接、内容被改写三类断点。"
                    + "problems 为空＝这份审计自校验通过（留存清理删掉的最老一批不算断点）")
    @GetMapping("/verify")
    public ResultJson<Map<String, Object>> verify(@RequestParam(value = "maxRows", defaultValue = "50000") int maxRows,
                                                  HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("仅管理员可校验审计链");
        List<Map<String, Object>> problems = auditService.verify(maxRows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", problems.isEmpty());
        out.put("problems", problems);
        out.put("droppedSinceStart", auditService.droppedCount());
        return ResultJson.ok(out);
    }

    private static TimeRange rangeOf(int days) {
        int d = Math.max(1, Math.min(3650, days));
        return new TimeRange(LocalDate.now().minusDays(d - 1L).atStartOfDay(), LocalDateTime.now());
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * 跨租户审阅的准入判定（Phase 4）：带 {@code tenant} 且调用者是平台级（superadmin）才生效，
     * 返回该租户标识；其余情况返回 null＝按登录者自己的租户看（不是「看全部」）。
     * <p>租户管理员点名别人的租户要报错而不是静默回本租户——静默会让他在自己的账里找别人的事件，
     * 找不到就以为平台没记。
     */
    private String crossTenant(String tenant, HttpServletRequest request) {
        if (tenant == null || tenant.isBlank()) return null;
        if (!com.wenqu.ai.util.RequestUser.superadmin()) {
            throw new com.wenqu.ai.common.BizException("跨租户审阅审计事件仅平台级管理员可用");
        }
        return tenant.trim();
    }

    private static String requestUid() {
        try {
            return com.wenqu.ai.util.RequestUser.uid();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
