package com.wenqu.ai.controller;

import com.wenqu.ai.config.AdminGuard;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.UsageStatsService;
import com.wenqu.ai.util.RequestUser;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 使用统计（个人用量）控制器。
 * 数据口径：推理用量台账 c_ai_usage_log（所有经多供应商路由的 LLM 调用统一记账）；
 * 未登录（匿名）无可归属的个人用量，返回空结构而不展示匿名兼容池的数据。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "使用统计", description = "个人 Token 用量统计：统计卡/活动热力图/每日趋势/模型占比；管理侧费用报表")
public class UsageStatsController {

    private final UsageStatsService usageStatsService;
    private final AdminGuard adminGuard;
    private final com.wenqu.ai.service.UsageQuotaService usageQuota;

    @Operation(summary = "个人用量统计",
            description = "cards=全时段统计卡（累计/峰值 Token、最长聊天时长、当前/最长连续天数、费用）；"
                    + "heatmap=近365天稀疏日清单；trend/models=时间范围内每日×模型趋势与模型占比（models 含按模型费用）；"
                    + "kinds=同窗口按用途占比（对话问答 / 子智能体分支 / 历史压缩 / "
                    + "引用自检 / 记忆提取 / 智能体咨询）——辅助调用的开销没有对应消息，只有这里看得见")
    @GetMapping("/stats/usage")
    public ResultJson<Map<String, Object>> usage(
            @Parameter(description = "趋势与模型占比的时间范围（天）：7 或 30")
            @RequestParam(value = "range", defaultValue = "30") Integer range) {
        String uid = RequestUser.uid();
        if (RequestUser.ANONYMOUS.equals(uid)) {
            return ResultJson.ok(UsageStatsService.empty());
        }
        return ResultJson.ok(usageStatsService.usage(uid, range));
    }

    @Operation(summary = "我此刻受哪些配额管着",
            description = "返回约束到本人的规则（平台/本部门/本人三档）与各档本周期已用量。"
                    + "问答被配额拦下时给出的那句提示，与这里读的是同一份数——两处数字不一致会让人怀疑闸门在说谎。")
    @GetMapping("/stats/quota")
    public ResultJson<List<Map<String, Object>>> myQuota() {
        String uid = RequestUser.uid();
        if (RequestUser.ANONYMOUS.equals(uid)) return ResultJson.ok(List.of());
        return ResultJson.ok(usageQuota.mine(uid));
    }

    @Operation(summary = "费用报表（管理）",
            description = "全员台账 × 模型登记单价（元/百万 tokens，输入/输出分开）：total=区间费用合计；"
                    + "byUser/byModel=按用户/按模型两维度（token 明细与费用，未登记单价的模型费用为 null）；"
                    + "unpricedTokens=未计价模型的 token 总量（费用合计不含它，避免误读为全部真实成本）；"
                    + "unpricedModels=在册但没配单价的模型（按展示名合并、token 降序，refs=该名下在册供应商引用数）；"
                    + "unregisteredTokens/unregisteredCount=模型记录已被删除或早期未记模型的台账，"
                    + "无处登记单价，故不进 unpricedModels")
    @GetMapping("/stats/cost")
    public ResultJson<Map<String, Object>> cost(
            @Parameter(description = "统计范围（天）：7 或 30")
            @RequestParam(value = "range", defaultValue = "30") Integer range,
            HttpServletRequest request) {
        if (!adminGuard.isAdmin(request)) {
            return ResultJson.error("费用报表仅管理员可查看");
        }
        return ResultJson.ok(usageStatsService.adminCost(range));
    }
}
