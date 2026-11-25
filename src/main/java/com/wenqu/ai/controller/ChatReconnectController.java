package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.wenqu.ai.security.Permission;
import com.wenqu.ai.controller.ChatRequestValidator;

/**
断线重连：探测某个会话是否还有一轮在跑，以及把新通道挂回那一轮。
从 ChatRunController 拆出：它关心的是「这一轮的通道还在不在」，而不是「这一轮怎么开」。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "断线重连", description = "运行中轮的探测与接流")
public class ChatReconnectController {

    private final com.wenqu.ai.agent.chat.ChatRunRuntime runtime;
    private final ChatRequestValidator validator;

    @Operation(summary = "断线重连·探测", description = "问这个会话是否还有一轮正在跑（只在内存里判，服务重启后没有）。"
            + "running=true 时前端再开一条 GET /chat/resume 接回这一轮。归属只认发起本轮的那个 uid："
            + "别人的会话既查不到有没有在跑，也接不走。")
    @Permission("chat:use")
    @GetMapping("/chat/run")
    public ResultJson chatRunStatus(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return ResultJson.error("缺少会话 ID");
        return ResultJson.ok(runtime.runStatus(sessionId.trim(), com.wenqu.ai.util.RequestUser.uid()));
    }

    @Operation(summary = "断线重连接流", description = "把一条新的 SSE 通道挂到该会话正在跑的这一轮上："
            + "先送一条 snapshot 事件（到此刻为止已产出的正文/过程独白/思考/工具卡片/时间线/引用/产物/计划，"
            + "外加阶段文案与任务清单这类状态事件的最后一份），此后只送快照之后的增量事件，本轮收尾照常规送 done/error。"
            + "文本增量带 pos（该增量应用后的正文字符数）供前端去重。没有在跑、或不是本人发起的轮，"
            + "送 snapshot{running:false} 后立即关闭，前端按历史回显即可。")
    @ApiResponse(responseCode = "200", description = "SSE 流式响应",
            content = @Content(mediaType = "text/event-stream"))
    @Permission("chat:use")
    @GetMapping("/chat/resume")
    public SseEmitter resumeChatTurn(
            @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        SseEmitter emitter = new SseEmitter(0L);
        if (sessionId == null || sessionId.isBlank()) {
            emitter.complete();
            return emitter;
        }
        validator.rejectApiKeyForWorkspaceSession(httpRequest, sessionId.trim());
        runtime.resumeTurn(sessionId.trim(), com.wenqu.ai.util.RequestUser.uid(), emitter);
        return emitter;
    }
}
