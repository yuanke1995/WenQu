package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.dto.SteerRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.wenqu.ai.security.Permission;

/**
 * 运行中插话（steer）：本轮还在跑时补一句方向，以及「生成中先入队、刷新可恢复」的服务端队列。
 *
 * <p>从 ChatRunController 拆出：插话要处理「排队 / 立即送 / 丢弃」三种动作和一份持久队列，
 * 与「开轮」「停止本轮」是三条独立的控制路径，合在一个类里只会让每边都带着另一边的依赖。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "运行中插话", description = "插话直送、插话队列的入队/投影/发送/丢弃")
public class ChatSteerController {

    private final com.wenqu.ai.service.SteerQueueService steerQueue;
    private final com.wenqu.ai.agent.chat.ChatRunRuntime runtime;
    /** 插话与开轮共用同一套入参校验（图片上限、附件归属、API-Key 工作区限制） */
    private final ChatRequestValidator validator;

    @Operation(summary = "运行中插话", description = "本轮还在跑时补一句方向（steer）：这句话交给框架会话自己送进<b>正在跑的那一轮</b>，"
            + "生效点是下一次模型调用之前——工具循环由框架持有，做不到掐掉正在执行的这一步。"
            + "它同时被落成一条普通用户消息（用户按了发送、话是自己说的，历史里就该长成用户消息的样子），"
            + "返回的 messageId 供前端挂到那颗气泡上。本轮收尾时还没被模型收上的，随 done 事件回落由前端作为同一句话续发。"
            + "单条最长 800 字；可带图片与文档附件（与正常提问同一套校验）：附件正文按 fileId 现读现抽，"
            + "随这句话在下一次模型调用前写进本轮上下文。"
            + "没有在跑的轮 / 不是本人的轮 / 框架拒收（这一轮已经不在执行中）时返回 accepted=false，前端把内容放回输入框即可。")
    @Permission("chat:use")
    @PostMapping("/chat/steer")
    public ResultJson steerChatTurn(@RequestBody SteerRequest body, HttpServletRequest httpRequest) {
        String sessionId = body == null ? null : body.getSessionId();
        String text = body == null ? null : body.getText();
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            return ResultJson.error("缺少会话 ID 或插话内容");
        }
        List<String> images = body.getImages();
        validator.validateImages(images);
        validator.rejectApiKeyForWorkspaceSession(httpRequest, sessionId.trim());
        String uid = com.wenqu.ai.util.RequestUser.uid();
        List<ChatRequest.Attachment> attachments;
        try {
            attachments = validator.normalizeAttachments(body.getAttachments(), uid);
        } catch (BizException e) {
            return ResultJson.error(e.getMessage());
        }
        String messageId = runtime.steerTurn(sessionId.trim(), uid, text, images, attachments);
        return ResultJson.ok(java.util.Map.of("accepted", messageId != null,
                "messageId", messageId == null ? "" : messageId));
    }

    @Operation(summary = "插话排队（服务端持有，刷新可恢复）", description = "生成中先入队、不发请求；"
            + "drain 由引擎在「这一轮真的开跑」时唤醒，只 steer 进活着的轮，绝不另起一轮。"
            + "入队时同步校验传图（排队期间不会有人再替它过一遍）。")
    @Permission("chat:use")
    @PostMapping("/chat/steer/queue")
    public ResultJson enqueueSteer(@RequestBody SteerRequest body, HttpServletRequest httpRequest) {
        String sessionId = body == null ? null : body.getSessionId();
        String text = body == null ? null : body.getText();
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            return ResultJson.error("缺少会话 ID 或插话内容");
        }
        validator.validateImages(body.getImages());
        validator.rejectApiKeyForWorkspaceSession(httpRequest, sessionId.trim());
        try {
            return ResultJson.ok(steerQueue.enqueue(sessionId.trim(), com.wenqu.ai.util.RequestUser.uid(),
                    text, body.getImages(),
                    validator.normalizeAttachments(body.getAttachments(), com.wenqu.ai.util.RequestUser.uid())));
        } catch (IllegalArgumentException | BizException e) {
            return ResultJson.error(e.getMessage());
        }
    }

    @Operation(summary = "插话队列投影", description = "还在排队（PENDING）与已送出等模型听到（STEERED）的条目；刷新/换设备据此恢复。")
    @Permission("chat:use")
    @GetMapping("/chat/steer/queue")
    public ResultJson steerQueue(@Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return ResultJson.error("缺少会话 ID");
        return ResultJson.ok(steerQueue.list(sessionId.trim(), com.wenqu.ai.util.RequestUser.uid()));
    }

    @Operation(summary = "插话队列·立即发送", description = "把排队的一条送进正在跑的那一轮；没有活轮时返回 accepted=false，"
            + "条目留在队列里等下一次轮开跑（不退回输入框、也不静默丢）。")
    @Permission("chat:use")
    @PostMapping("/chat/steer/queue/{itemId}/steer")
    public ResultJson steerQueued(@PathVariable("itemId") String itemId,
                                  @RequestBody(required = false) Map<String, String> body) {
        String sessionId = body == null ? null : body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) return ResultJson.error("缺少会话 ID");
        return ResultJson.ok(steerQueue.steerNow(sessionId.trim(), com.wenqu.ai.util.RequestUser.uid(), itemId));
    }

    @Operation(summary = "插话队列·丢弃", description = "只对还在排队（PENDING）的条目生效；已送出的等模型听到，不能假装撤回。")
    @Permission("chat:use")
    @DeleteMapping("/chat/steer/queue/{itemId}")
    public ResultJson dropQueued(@PathVariable("itemId") String itemId,
                                 @Parameter(description = "会话 ID") @RequestParam("sessionId") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return ResultJson.error("缺少会话 ID");
        return ResultJson.ok(java.util.Map.of("ok",
                steerQueue.drop(sessionId.trim(), com.wenqu.ai.util.RequestUser.uid(), itemId)));
    }
}
