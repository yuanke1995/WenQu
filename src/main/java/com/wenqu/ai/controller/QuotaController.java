package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.service.UsageQuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 推理配额与预算（Phase 2 计量计费）。
 * <p>
 * 与「使用统计」的分工：那一份是<b>看账</b>（谁花了多少），这一份是<b>设闸</b>（谁最多能花多少）。
 * 两个入口都放在看板同侧，因为管理员的动作顺序就是「先看数、再设限」。
 * <p>
 * 成员自查自己受哪几条规则管、用了多少，走 {@code GET /api/ai/stats/quota}（与个人用量同一档权限），
 * 不在这里开一个「人人可读」的口子——配额规则里含他人姓名与部门，属于治理数据。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/quota")
@RequiredArgsConstructor
@Tag(name = "推理配额", description = "平台/部门/成员三档 token 与费用上限、超额熔断与告警")
public class QuotaController {

    private final UsageQuotaService quotaService;
    private final AdminGuard adminGuard;

    @Operation(summary = "配额清单（管理）",
            description = "每条规则连同此刻的用量与进度：usedTokens/usedCost 是本周期按台账现算的值，"
                    + "tokenPercent/costPercent 为百分比（null=该维度不限额），breach=到第几档（token/cost/null）。"
                    + "金额只累计已配单价的模型，未配单价的模型不进金额口径。")
    @GetMapping
    public ResultJson<List<Map<String, Object>>> list(HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("配额管理仅管理员可查看");
        return ResultJson.ok(quotaService.listWithUsage());
    }

    @Operation(summary = "新建或修改配额",
            description = "同作用域同周期唯一：已存在的那条会被覆盖。scope=tenant|department|user，"
                    + "tenant 不填 scopeId（=本租户全体）；tokenLimit=0 且 costLimit 空＝这条规则不限任何东西（会被拒）。"
                    + "kinds 留空＝只拦用户主动发起的调用（对话/子智能体/工作流），辅助调用不拦；"
                    + "enforce=0 只告警不拦。")
    @PostMapping
    public ResultJson<Map<String, Object>> save(@RequestBody Map<String, Object> body,
                                                HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("配额管理仅管理员可修改");
        Long tokenLimit = body.get("tokenLimit") == null ? 0L
                : ((Number) body.get("tokenLimit")).longValue();
        BigDecimal costLimit = body.get("costLimit") == null || String.valueOf(body.get("costLimit")).isBlank()
                ? null : new BigDecimal(String.valueOf(body.get("costLimit")));
        Integer enforce = body.get("enforce") == null ? 1 : ((Number) body.get("enforce")).intValue();
        var saved = quotaService.save(str(body.get("id")), str(body.get("scope")), str(body.get("scopeId")),
                str(body.get("period")), tokenLimit, costLimit, str(body.get("kinds")), enforce,
                str(body.get("remark")));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", saved.getId());
        return ResultJson.ok(out, "已保存");
    }

    @Operation(summary = "删除配额", description = "删除即解除该档约束（正在跑的调用不受影响，下一道闸判时已看不到这条规则）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable("id") String id, HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) return ResultJson.error("配额管理仅管理员可删除");
        quotaService.delete(id);
        return ResultJson.ok("已删除");
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
