package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.RateLimitService;

/**
 * 会话的运行态：模型覆盖、上下文压缩、待办清单、权限档。
 * 从 ChatSessionController 拆出：这些端点读写的是「这一轮怎么跑」，与「会话怎么组织」是两件事。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "会话运行态", description = "会话的模型覆盖、上下文压缩、待办与权限档")
public class ChatSessionStateController {

    private final com.wenqu.ai.agentx.FrameworkSessions frameworkSessions;
    private final com.wenqu.ai.service.TodoBridge todoBridge;
    private final com.wenqu.ai.service.SessionEventService sessionEvents;
    private final com.wenqu.ai.agent.chat.ChatRunRuntime runtime;
    private final SessionService sessionService;
    private final RateLimitService rateLimitService;


    @GetMapping("/session/{sessionId}/todos")
    public ResultJson getSessionTodos(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @Parameter(description = "轮次 ID（可选）：给了就只读这一轮在账本里的最后一份清单")
            @RequestParam(value = "turnId", required = false) String turnId) {
        String uid = RequestUser.uid();
        sessionService.assertOwned(sessionId, uid);
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        String doc;
        if (turnId != null && !turnId.isBlank()) {
            // 历史轮次：只认账本里那一轮的 todo 事件，不拿当前清单冒充旧进度
            doc = sessionEvents.latestTodoOfTurn(sessionId, uid, turnId.trim());
        } else {
            // 首选框架 state（权威真源）；读不到（实例不在本进程 / 重启过）才回落会话表那份
            doc = todoBridge.docJson(frameworkSessions.peek(uid, sessionId), "");
            if (doc == null || doc.isBlank()) {
                doc = sessionService.todosOf(sessionId);
            }
        }
        out.put("todos", doc == null || doc.isBlank() ? null : com.alibaba.fastjson.JSON.parse(doc));
        return ResultJson.ok(out);
    }

    @PostMapping("/session/{sessionId}/compact")
    public ResultJson compactSession(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @Parameter(description = "可选 {instruction:这一次摘要的附加要求}")
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        sessionService.assertOwned(sessionId, userId);
        // 摘要调用按 chat 桶限频，防连点刷额度（框架那一路也要过这道，不然连点能一直重建实例）
        rateLimitService.checkRateLimit("chat", RequestUser.SYSTEM.equals(userId)
                ? "ip:" + com.wenqu.ai.util.ClientIp.of(httpRequest) : "user:" + userId);
        // 这一次摘要的附加要求（摘要弹窗「带要求重新压缩」填的那句）：并进框架的摘要提示词。
        // 上限与输入框那道 maxlength 一致，超了如实报错，不静默截断半句要求
        String instruction = body == null || body.get("instruction") == null
                ? "" : body.get("instruction").trim();
        if (instruction.length() > 200) throw new BizException("摘要要求最长 200 字");
        // 会话轮级互斥：压缩与问答同一把锁。就地压缩是「读状态快照 → 压缩 → clear+addAll 写回」，
        // 快照过期不提交的守卫只有框架自动压缩有，手动这条路径没有——读与写之间若有一轮在跑
        //（插话、工具结果、半截正文正往上下文里追加），那些消息会被旧快照覆盖掉。抢不到如实说，不排队。
        if (!runtime.tryBeginTurn(sessionId)) {
            throw new BizException("这个会话上一轮还在回答中，等回答结束或先停止本轮，再压缩上下文");
        }
        try {
            // 单一引擎：会话上下文恒由 AgentScope 框架持有，压缩语义只有框架这一份（摘要提示词也是）。
            Map<String, Object> r = frameworkSessions.compactNow(userId, sessionId, instruction);
            boolean scheduled = Boolean.TRUE.equals(r.get("scheduled"));
            // 摘要落进会话行：框架只把压缩产物写在自己的会话状态里，而分隔线上「查看摘要」读的是
            // c_ai_session 那两列——不落库就是当场看得见、刷新再点进去报「这个会话还没有生成过摘要」
            if (r.get("summary") instanceof String summary && !summary.isBlank()) {
                sessionService.updateHistorySummary(sessionId, summary,
                        r.get("compressedTurns") instanceof Number n ? n.longValue() : 0L);
            }
            return ResultJson.ok(r, scheduled ? "已排到下一次提问前压缩" : "压缩完成");
        } finally {
            runtime.endTurnMutex(sessionId);
        }
    }

}
