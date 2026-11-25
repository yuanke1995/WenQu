package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.RateLimitService;

/**
会话级覆盖项：模型选择与权限档。
从 ChatSessionStateController 拆出：这两个端点写的是「这个会话以后怎么跑」的持久配置，
与压缩/待办/历史摘要那种「这一轮跑出了什么」的只读视图是两类东西。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Tag(name = "会话覆盖", description = "会话级模型选择与权限档")
public class ChatSessionOverrideController {

    /** 会话级工具授权面（权限档切换时按档重算） */
    private final com.wenqu.ai.agentx.SessionToolGrants sessionToolGrants;
    private final SessionService sessionService;
    private final com.wenqu.ai.service.ModelRegistryService modelRegistryService;

    @GetMapping("/session/{sessionId}/model")
    public ResultJson getSessionModel(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            HttpServletRequest httpRequest) {
        sessionService.assertOwned(sessionId, RequestUser.uid());
        String model = sessionService.sessionModelOf(sessionId);
        String issue = model.isBlank() ? ""
                : modelRegistryService.issueText(modelRegistryService
                        .referenceUsability(model, null, null, false).issue());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("model", model);
        data.put("modelIssue", issue);
        return ResultJson.ok(data);
    }

    @PutMapping("/session/{sessionId}/model")
    public ResultJson setSessionModel(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        String userId = RequestUser.uid();
        Map<String, Object> payload = body == null ? Map.of() : body;
        if (payload.containsKey("model")) {
            String model = payload.get("model") == null
                    ? "" : String.valueOf(payload.get("model")).trim();
            if (!model.isBlank()) {
                // 与 /chat 同口径的 fail-loud：普通用户只能存自己可用的模型；引用须真的可用
                // （已停用/已从模型列表移除也算不可用——存进会话后每轮都要到回答期才撞上上游报错，
                // 用户无从定位）。遗留裸模型名维持放行
                String issue = modelRegistryService.issueMessage(model, userId, RequestUser.role());
                if (!issue.isEmpty()) {
                    throw new BizException("模型" + issue + "，请重新选择");
                }
            }
            sessionService.updateModel(userId, sessionId, model);
        }
        return ResultJson.ok("操作成功");
    }


    @PutMapping("/session/{sessionId}/permission")
    public ResultJson setSessionPermission(
            @Parameter(description = "会话 ID") @PathVariable("sessionId") String sessionId,
            @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("preset");
        String applied = sessionService.setPermissionPreset(RequestUser.uid(), sessionId,
                raw == null ? "" : String.valueOf(raw));
        // 用户显式改档位 = 重新做权限决定：旧的「本次会话都允许」不跨档位继承
        sessionToolGrants.clearForSession(RequestUser.uid(), sessionId);
        java.util.Map<String, Object> out = new java.util.HashMap<>();
        out.put("permissionPreset", applied == null ? "" : applied);
        return ResultJson.ok(out);
    }
}
