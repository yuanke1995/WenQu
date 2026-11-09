package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.model.Message;
import com.wenqu.ai.service.QaLogService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 问答反馈与数据看板
 * <p>
 * 权限：feedback（普通用户问答链路的一部分，需归属校验）公开；analytics/** 为管理端点（拦截器管理员判定）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "反馈与看板", description = "回答反馈、数据统计看板")
public class QaController {

    private final QaLogService qaLogService;
    private final com.wenqu.ai.service.DashboardService dashboardService;
    private final MessageMapper messageMapper;
    private final SessionService sessionService;

    @Operation(summary = "提交回答反馈", description = "对 AI 回答进行 👍👎 评价，可选填写反馈文本（仅能评价本人会话内的消息）")
    @Permission("chat:use")
    @PostMapping("/feedback")
    public ResultJson feedback(
            @Parameter(description = "{\"messageId\": \"消息ID\", \"rating\": 1|0, \"feedbackText\": \"可选反馈文本\"}")
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        String messageId = body.get("messageId") == null ? null : String.valueOf(body.get("messageId"));
        if (messageId == null || messageId.isBlank()) {
            throw new BizException("缺少 messageId");
        }
        int rating = body.get("rating") == null ? 0 : Integer.parseInt(String.valueOf(body.get("rating")));
        String text = body.get("feedbackText") == null ? null : String.valueOf(body.get("feedbackText"));
        // 归属校验：只能评价本人会话内的消息（防用他人 messageId 灌反馈/探测）
        Message msg = messageMapper.selectByIdIgnoreDeleted(messageId);
        if (msg == null) {
            throw new BizException(404, "消息不存在或已过撤销期");
        }
        sessionService.assertOwned(msg.getSessionId(), RequestUser.uid());
        qaLogService.feedback(messageId, rating, text);
        return ResultJson.ok("感谢反馈");
    }

    @Operation(summary = "差评样本列表", description = "👎 样本按时间倒序（含问题/回答摘要/反馈说明/引用块），供看板反馈回流：加入评估集或补知识块")
    @Permission("stats:read")
    @GetMapping("/analytics/badcases")
    public ResultJson badCases(
            @Parameter(description = "返回上限（默认 50，最大 100）")
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        return ResultJson.ok(qaLogService.listBadCases(limit));
    }

    @Operation(summary = "看板统计",
            description = "按时间范围聚合：问答量、满意率、引用率、无命中率、热门/无命中 TOP10、"
                    + "上期对比（prev）、每日趋势（trend）与健康度（health）。range=7/30/90，0=全部")
    @Permission("stats:read")
    @GetMapping("/analytics/summary")
    public ResultJson analytics(
            @Parameter(description = "统计范围（天）：7 / 30 / 90；0=全部")
            @RequestParam(value = "range", required = false, defaultValue = "30") Integer range) {
        return ResultJson.ok(dashboardService.summary(range));
    }

    @Operation(summary = "待办闭环队列",
            description = "差评 / 知识缺口 / 待标注三源合一：counts=各类计数与本周已闭环数，rows=按时间倒序的待处理项")
    @Permission("stats:read")
    @GetMapping("/analytics/actions")
    public ResultJson actions(
            @Parameter(description = "统计范围（天）：7 / 30 / 90；0=全部")
            @RequestParam(value = "range", required = false, defaultValue = "30") Integer range) {
        return ResultJson.ok(dashboardService.actions(range));
    }

    @Operation(summary = "看板阈值告警",
            description = "按近 7 天窗口计算无命中率 / 满意率是否越过阈值，并附带最近体检状态；供页头预警横幅与定时任务共用")
    @Permission("stats:read")
    @GetMapping("/analytics/alerts")
    public ResultJson alerts() {
        return ResultJson.ok(dashboardService.alerts());
    }
}
