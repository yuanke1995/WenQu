package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.mapper.MessageMapper;
import com.wisesoft.ai.model.Message;
import com.wisesoft.ai.service.QaLogService;
import com.wisesoft.ai.service.SessionService;
import com.wisesoft.ai.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
    private final MessageMapper messageMapper;
    private final SessionService sessionService;

    @Operation(summary = "提交回答反馈", description = "对 AI 回答进行 👍👎 评价，可选填写反馈文本（仅能评价本人会话内的消息）")
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
        sessionService.assertOwned(msg.getSessionId(), UserContext.resolve(httpRequest));
        qaLogService.feedback(messageId, rating, text);
        return ResultJson.ok("感谢反馈");
    }

    @Operation(summary = "差评样本列表", description = "👎 样本按时间倒序（含问题/回答摘要/反馈说明/引用块），供看板反馈回流：加入评估集或补知识块")
    @GetMapping("/analytics/badcases")
    public ResultJson badCases(
            @Parameter(description = "返回上限（默认 50，最大 100）")
            @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        return ResultJson.ok(qaLogService.listBadCases(limit));
    }

    @Operation(summary = "看板统计", description = "获取数据看板聚合统计：问答量、满意率、引用率、无命中率、热门问题 TOP10、无命中问题 TOP10")
    @GetMapping("/analytics/summary")
    public ResultJson analytics() {
        return ResultJson.ok(qaLogService.analyticsSummary());
    }
}
