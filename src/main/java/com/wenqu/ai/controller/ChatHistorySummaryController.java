package com.wenqu.ai.controller;

import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 对话历史的只读摘要（存量会话分隔线展示）。
 * <p>压缩本身已交 AgentScope 框架，这里只读它留下的摘要；
 * 从 ChatSessionStateController 拆出：它是「读一段已发生的历史」，不是「改这个会话的运行配置」。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "对话历史摘要", description = "存量会话的历史压缩摘要")
public class ChatHistorySummaryController {

    private final com.wenqu.ai.agent.context.HistoryCompactionService historyCompaction;
    private final SessionService sessionService;

    @GetMapping("/session/{sessionId}/history-summary")
    public ResultJson historySummary(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId) {
        String userId = RequestUser.uid();
        sessionService.assertOwned(sessionId, userId);
        return ResultJson.ok(historyCompaction.currentSummary(sessionId));
    }
}
