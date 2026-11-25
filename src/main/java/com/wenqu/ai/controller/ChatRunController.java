package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.dto.SteerRequest;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 问答一轮的生命周期：开轮（SSE）、停止、运行中插话与插话队列、断线重连、每轮文件改动差异。
 * 入参校验移交 {@link ChatRequestValidator}，本地不再需要 kb/agent/document/visibility/config 等一串依赖。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "问答流", description = "SSE 流式问答、停止/插话、断线重连、每轮文件改动")
public class ChatRunController {
private final com.wenqu.ai.agent.chat.ChatRunRuntime runtime;
    /** 入参校验：图片/附件/引用/历史引用/API-Key 工作区限制（与被拆出的其余入口共用同一实现） */
    /** 开轮准入：限流/配额/字数、会话解析、模型与智能体可用性（与本控制器其余端点无关） */
    /** 插话/接流这些端点自己的入参校验（开轮那一路的校验已随编排移入 ChatTurnLauncher） */
    /** 开轮编排（准入/校验/装配命令）都在它那里，本类只做 HTTP 适配 */
    private final com.wenqu.ai.service.ChatTurnLauncher launcher;

    @Operation(summary = "SSE 流式问答",
            description = "发送问题（可含图片），通过 SSE 流式返回 AI 回答。事件类型：thinking（深度思考增量）、thinking_done（思考结束）、token（文本增量）、image（图片 URL 列表）、done（引用来源/相关推荐/消息ID/思考全文）、error（错误信息）。按用户限频（ratelimit.chatPerMinute）")
    @ApiResponse(responseCode = "200", description = "SSE 流式响应",
            content = @Content(mediaType = "text/event-stream"))
    @Permission("chat:use")
    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody @Valid ChatRequest request, HttpServletRequest httpRequest) {
        // 气泡顶部报的是「从发出提问到答完」的墙钟：起表点必须早于限流、校验、会话补建与实例装配，
        // 否则刷新后读库里那份机器耗时，数字会凭空变小。
        long receivedAtMs = System.currentTimeMillis();
        return launcher.launch(request, httpRequest, receivedAtMs);
    }

    @Operation(summary = "停止本轮", description = "服务端停止该会话正在运行的这一轮（生成中、工具执行中、"
            + "正等审批/作答/批准计划都能停）：掐断生成流、不再执行后续工具、已生成内容按「已停止本轮」落库，"
            + "并解开正在等待的人工裁决，使会话立刻可以发下一问。与前端断开连接不同——断开在等人作答时"
            + "的语义是后台继续跑完，这里是要它真的停。仅本轮发起者本人可停；没有在跑的轮或不是本人的轮"
            + "返回 stopped=false。")
    @Permission("chat:use")
    @PostMapping("/chat/stop")
    public ResultJson stopChatTurn(@RequestBody Map<String, String> body) {
        String sessionId = body == null ? null : body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) return ResultJson.error("缺少会话 ID");
        boolean stopped = runtime.stopTurn(sessionId.trim(), com.wenqu.ai.util.RequestUser.uid());
        return ResultJson.ok(java.util.Map.of("stopped", stopped));
    }

}
