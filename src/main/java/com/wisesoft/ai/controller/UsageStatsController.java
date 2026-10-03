package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.UsageStatsService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 使用统计（个人用量）控制器。
 * 数据口径：登录用户自己会话内的助手回答（tokens JSON + model 字段）；
 * 未登录（匿名）无可归属的个人用量，返回空结构而不展示匿名兼容池的数据。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "使用统计", description = "个人 Token 用量统计：统计卡/活动热力图/每日趋势/模型占比")
public class UsageStatsController {

    private final UsageStatsService usageStatsService;

    @Operation(summary = "个人用量统计",
            description = "cards=全时段统计卡（累计/峰值 Token、最长聊天时长、当前/最长连续天数）；"
                    + "heatmap=近365天稀疏日清单；trend/models=时间范围内每日×模型趋势与模型占比")
    @GetMapping("/stats/usage")
    public ResultJson<Map<String, Object>> usage(
            @Parameter(description = "趋势与模型占比的时间范围（天）：7 或 30") @RequestParam(defaultValue = "30") Integer range) {
        String uid = RequestUser.uid();
        if (RequestUser.ANONYMOUS.equals(uid)) {
            return ResultJson.ok(UsageStatsService.empty());
        }
        return ResultJson.ok(usageStatsService.usage(uid, range));
    }
}
