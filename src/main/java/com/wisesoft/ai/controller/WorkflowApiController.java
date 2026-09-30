package com.wisesoft.ai.controller;

import com.wisesoft.ai.config.SecurityConfig;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.WorkflowRun;
import com.wisesoft.ai.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对外工作流 API（M4）：持 API Key 的外部系统触发<b>已发布</b>工作流。
 * <p>
 * 鉴权沿用 API Key 体系（{@code X-Api-Key}，与问答链路同一套凭据，见
 * {@link com.wisesoft.ai.service.ApiKeyService#verify}）；登录用户也可直接调用（身份取登录令牌）。
 * 权限面：工作流对该身份可读（创建者或共享范围命中）才可触发，不可见一律 404；
 * 未发布 → 400 fail-loud；含人工审核节点 → 400 fail-loud（外部调用方没有审批交互面）。
 * <p>
 * 路径刻意放 {@code /api/ai/v1/**}：与内部管理端点 {@code /api/ai/workflow/**} 分开，
 * 便于网关按前缀对外暴露（后续如果只对外开放 v1 前缀，内部端点天然不被带出去）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/v1/workflows")
@RequiredArgsConstructor
@Tag(name = "工作流开放接口", description = "外部系统持 API Key 触发已发布工作流（M4）")
public class WorkflowApiController {

    private final WorkflowService workflowService;

    @Operation(summary = "触发运行（同步）", description = "body: {inputs?}（开始节点入参，对应 {{start.key}}）。"
            + "跑的是<b>已发布版本</b>的 DSL（草稿改动不影响），返回 run 全量信息："
            + "status / outputs（结束节点出参）/ nodeTraces（节点级输入输出与耗时）/ durationMs。"
            + "失败不抛 500——返回 status=failed 的 run，error 与 trace 可定位失败节点。")
    @PostMapping("/{id}/run")
    public ResultJson run(@PathVariable("id") String id, @RequestBody(required = false) Map<String, Object> body,
                          HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = body == null || !(body.get("inputs") instanceof Map) ? Map.of()
                : (Map<String, Object>) body.get("inputs");
        // API Key 归属：拦截器已校验过 Key 有效性并把 id 放进请求属性（未带 Key 时为 null → 按登录用户）
        Object keyId = request.getAttribute(SecurityConfig.ATTR_API_KEY_ID);
        WorkflowRun run = workflowService.runByApi(id, inputs, keyId == null ? null : String.valueOf(keyId));
        return ResultJson.ok(run);
    }

    @Operation(summary = "查询运行结果", description = "按 runId 查本次运行的状态与结果（触发方与归属一致的才可见）；"
            + "用于外部系统异步核对（同步触发已直接返回，此接口供补查/回溯）")
    @GetMapping("/{id}/run/{runId}")
    public ResultJson runDetail(@PathVariable("id") String id, @PathVariable("runId") String runId) {
        return ResultJson.ok(workflowService.getRun(id, runId));
    }
}
