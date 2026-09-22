package com.wisesoft.wenqu.controller;

import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.agents.BaseAgent;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.agents.AgentManager;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.AgentConfigService;
import com.wisesoft.wenqu.service.AgentRequestQueueService;
import com.wisesoft.wenqu.service.AgentRequestService;
import com.wisesoft.wenqu.service.AgentRunService;
import com.wisesoft.wenqu.service.InputMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 智能体路由，逐端点对齐参考实现 {@code server/routers/agent_router.py}（23 个端点）。
 *
 * <p>前缀 {@code /api/agent}（参考实现 {@code prefix="/agent"}，本工程统一挂 {@code /api}）。
 * 三段职责：后端能力清单（2）/ 智能体定义的增删改查与设默认（7）/ 运行与请求队列（14，
 * 含两条 SSE 流）。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()} 取 uid，再经
 *       {@link UserRepository#getByUid} 装载用户实体（参考实现的该依赖本身即完成「认证 + 装载」
 *       两步，见 {@code server/utils/auth_middleware.py}）。</li>
 *   <li>{@code Depends(get_admin_user)} / {@code Depends(get_superadmin_user)} →
 *       {@link AuthGuards#requireAdmin()} / {@link AuthGuards#requireSuperadmin()}。</li>
 *   <li>{@code Depends(get_db)} → 服务层各自的事务边界（本工程仓储/服务内部自取 Mapper），
 *       控制器不持有会话。</li>
 *   <li>请求体 pydantic 模型 → {@code Map} 读 snake_case 键（本工程控制器层既有做法）。
 *       {@code AgentCreate}/{@code AgentUpdate}/{@code AgentRunCreate} 三个模型都<b>只做类型声明、
 *       无 Field 约束</b>，故此处不做 422 形状的逐字段校验；但 {@code AgentUpdate} 依赖
 *       {@code model_fields_set} 的「显式传 null = 清空」语义与 {@code AgentCreate.set_default}
 *       的业务 422 均按原样保留（见下）。</li>
 *   <li>{@code StreamingResponse(media_type="text/event-stream")} → {@link StreamingResponseBody}；
 *       帧已由服务层经 {@code SseUtils} 生成（含 {@code data: …\n\n}），控制器只负责写出，
 *       响应头逐字对齐参考实现。</li>
 * </ul>
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ul>
 *   <li>{@code _serialize_agent} 里的 {@code filter_declared_config(..., context_schema=...)}：
 *       本工程的声明字段表是 {@link BaseContext#fields()} 这一张<b>静态表</b>，
 *       {@code context_schema} 入参仅用于签名对齐，解析结果与不传 schema 时一致
 *       （与 {@link BaseContext#filterConfigByRole} 的既有标注同源）。</li>
 *   <li>{@code backend.get_info(user_role=…, db=…, user=…)}：本工程 {@link BaseAgent#getInfo}
 *       的对应入参是 {@code AgentResourceOptionsResolver}，已由 {@code AgentBackendInfoProvider}
 *       装配为 {@code AgentContextService#resolveAgentResourceOptions(…, uid)} 的委托，
 *       候选资源按当前用户可见集合注入（见该类类注释）。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Tag(name = "agent", description = "智能体定义、运行与请求队列")
public class AgentController {

    /** 参考实现 {@code AgentCreate.backend_id} 的默认值。 */
    private static final String DEFAULT_BACKEND_ID = "ChatbotAgent";

    /** 参考实现 {@code AgentRunCreate.queue_policy} 的默认值。 */
    private static final String DEFAULT_QUEUE_POLICY = "enqueue";

    private final AgentManager agentManager;
    private final AgentRepository agentRepository;
    private final AgentConfigService agentConfigService;
    private final AgentRequestService agentRequestService;
    private final AgentRequestQueueService agentRequestQueueService;
    private final AgentRunService agentRunService;
    private final UserRepository userRepository;

    // =========================================================================
    // === 后端能力清单（2） ===
    // =========================================================================

    /** 列出全部智能体后端（对应 {@code list_agent_backends}）。 */
    @Operation(summary = "列出全部智能体后端")
    @GetMapping("/backends")
    public Map<String, Object> listAgentBackends() {
        AuthGuards.requireUser();
        List<Map<String, Object>> backends = new ArrayList<>();
        for (Map<String, Object> info : agentManager.getAgentsInfo(false)) {
            backends.add(backendInfo(info));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backends", backends);
        return result;
    }

    /** 单个智能体后端详情（对应 {@code get_agent_backend}）。 */
    @Operation(summary = "智能体后端详情")
    @GetMapping("/backends/{backend_id}")
    public Map<String, Object> getAgentBackend(@PathVariable("backend_id") String backendId) {
        User user = requireCurrentUser();
        BaseAgent backend = agentManager.getAgent(backendId);
        if (backend == null) {
            throw ApiHttpException.notFound("智能体后端 " + backendId + " 不存在");
        }
        return backendInfo(backend.getInfo(false, user.getRole(), null));
    }

    // =========================================================================
    // === 智能体定义（7） ===
    // =========================================================================

    /** 列出可见智能体（对应 {@code list_agents}）。 */
    @Operation(summary = "列出可见智能体")
    @GetMapping
    public Map<String, Object> listAgents(
            @RequestParam(value = "include_subagents", required = false, defaultValue = "false")
            boolean includeSubagents) {
        User user = requireCurrentUser();
        String uid = String.valueOf(user.getUid());
        agentRepository.ensureDefaultAgent(uid);
        List<Agent> items = agentRepository.listVisible(PermissionSubject.of(user), includeSubagents);
        Map<String, Map<String, Object>> backendInfoCache = new LinkedHashMap<>();
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Agent item : items) {
            agents.add(serializeAgent(item, user, false, backendInfoCache));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agents", agents);
        return result;
    }

    /** 默认智能体（对应 {@code get_default_agent}）。 */
    @Operation(summary = "默认智能体")
    @GetMapping("/default")
    public Map<String, Object> getDefaultAgent() {
        User user = requireCurrentUser();
        Agent item = agentRepository.ensureDefaultAgent(String.valueOf(user.getUid()));
        if (item == null || !AgentRepository.userCanAccessAgent(PermissionSubject.of(user), item)) {
            throw ApiHttpException.notFound("默认智能体不可访问");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", serializeAgent(item, user, true, null));
        return result;
    }

    /** 创建智能体（对应 {@code create_agent}）。 */
    @Operation(summary = "创建智能体")
    @PostMapping
    public Map<String, Object> createAgent(@RequestBody(required = false) Map<String, Object> body) {
        User user = requireCurrentUser();
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        String backendId = optionalText(payload, "backend_id", DEFAULT_BACKEND_ID);
        BaseAgent backend = agentManager.getAgent(backendId);
        if (backend == null) {
            throw ApiHttpException.notFound("智能体后端 " + backendId + " 不存在");
        }
        boolean setDefault = truthy(payload.get("set_default"));
        if (setDefault) {
            throw ApiHttpException.unprocessable("默认智能体已固定为内置智能助手");
        }

        try {
            AgentConfigService.WriteResult write = agentConfigService.prepareAgentConfigWrite(
                    asMap(payload.get("config_json")),
                    backend.resolveContextSchema(),
                    user);
            Agent item = agentRepository.create(
                    text(payload.get("name")),
                    backendId,
                    text(payload.get("slug")),
                    text(payload.get("description")),
                    text(payload.get("icon")),
                    toStringList(payload.get("pics")),
                    toJsonObject(write.filtered()),
                    asResourceAccess(write.resourceAccess()),
                    toJsonObject(asMapOrNull(payload.get("share_config"))),
                    setDefault,
                    payload.get("is_subagent") == null ? null : truthy(payload.get("is_subagent")),
                    String.valueOf(user.getUid()),
                    PermissionSubject.of(user));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent", serializeAgent(item, user, true, null));
            return result;
        } catch (IllegalArgumentException error) {
            throw ApiHttpException.unprocessable(String.valueOf(error.getMessage()));
        }
    }

    /** 智能体详情（对应 {@code get_agent}）。 */
    @Operation(summary = "智能体详情")
    @GetMapping("/{agent_id}")
    public Map<String, Object> getAgent(@PathVariable("agent_id") String agentId) {
        User user = requireCurrentUser();
        Agent item = agentRepository.getVisibleBySlug(
                agentId, PermissionSubject.of(user), AgentRepository.AgentEntryKind.ANY);
        if (item == null) {
            throw ApiHttpException.notFound("智能体不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", serializeAgent(item, user, true, null));
        return result;
    }

    /** 更新智能体（对应 {@code update_agent}）。 */
    @Operation(summary = "更新智能体",
            description = "显式传 description/icon 为 null 表示清空（model_fields_set 语义）")
    @PutMapping("/{agent_id}")
    public Map<String, Object> updateAgent(
            @PathVariable("agent_id") String agentId,
            @RequestBody(required = false) Map<String, Object> body) {
        User user = requireCurrentUser();
        Agent item = agentRepository.getVisibleBySlug(
                agentId, PermissionSubject.of(user), AgentRepository.AgentEntryKind.ANY);
        if (item == null) {
            throw ApiHttpException.notFound("智能体不存在");
        }
        if (!AgentRepository.userCanManageAgent(PermissionSubject.of(user), item)) {
            throw ApiHttpException.forbidden("不能编辑非自己创建的智能体");
        }

        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        try {
            // 对应 payload.model_fields_set：显式传 null 才清空。
            if (payload.containsKey("description") && payload.get("description") == null) {
                item.setDescription(null);
            }
            if (payload.containsKey("icon") && payload.get("icon") == null) {
                item.setIcon(null);
            }

            JSONObject configJson = null;
            Map<String, Collection<String>> resourceAccess = null;
            if (payload.get("config_json") != null) {
                BaseAgent backend = agentManager.getAgent(item.getBackendId());
                AgentConfigService.WriteResult write = agentConfigService.prepareAgentConfigWrite(
                        asMap(payload.get("config_json")),
                        backend == null ? null : backend.resolveContextSchema(),
                        user);
                configJson = toJsonObject(write.filtered());
                resourceAccess = asResourceAccess(write.resourceAccess());
            }

            Agent updated = agentRepository.update(
                    item,
                    text(payload.get("name")),
                    text(payload.get("description")),
                    text(payload.get("icon")),
                    toStringList(payload.get("pics")),
                    configJson,
                    resourceAccess,
                    toJsonObject(asMapOrNull(payload.get("share_config"))),
                    payload.get("is_subagent") == null ? null : truthy(payload.get("is_subagent")),
                    String.valueOf(user.getUid()),
                    PermissionSubject.of(user));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent", serializeAgent(updated, user, true, null));
            return result;
        } catch (IllegalArgumentException error) {
            throw ApiHttpException.unprocessable(String.valueOf(error.getMessage()));
        }
    }

    /** 删除智能体（对应 {@code delete_agent}）。 */
    @Operation(summary = "删除智能体")
    @DeleteMapping("/{agent_id}")
    public Map<String, Object> deleteAgent(@PathVariable("agent_id") String agentId) {
        User user = requireCurrentUser();
        Agent item = agentRepository.getVisibleBySlug(
                agentId, PermissionSubject.of(user), AgentRepository.AgentEntryKind.ANY);
        if (item == null) {
            throw ApiHttpException.notFound("智能体不存在");
        }
        if (!AgentRepository.userCanManageAgent(PermissionSubject.of(user), item)) {
            throw ApiHttpException.forbidden("不能删除非自己创建的智能体");
        }
        if (AgentRepository.isBuiltinAgent(item)) {
            throw ApiHttpException.conflict("内置智能体不能删除");
        }
        agentRepository.delete(item);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        return result;
    }

    /** 设为默认智能体（仅管理员，对应 {@code set_agent_default}）。 */
    @Operation(summary = "设为默认智能体（仅管理员）")
    @PostMapping("/{agent_id}/set_default")
    public Map<String, Object> setAgentDefault(@PathVariable("agent_id") String agentId) {
        String uid = AuthGuards.requireAdmin();
        User user = requireUser(uid);
        Agent item = agentRepository.getBySlug(agentId);
        if (item == null) {
            throw ApiHttpException.notFound("智能体不存在");
        }
        try {
            Agent updated = agentRepository.setDefault(item, uid);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent", serializeAgent(updated, user, true, null));
            return result;
        } catch (IllegalArgumentException error) {
            throw ApiHttpException.unprocessable(String.valueOf(error.getMessage()));
        }
    }

    // =========================================================================
    // === 运行与请求队列（14） ===
    // =========================================================================

    /** 创建智能体运行（普通 chat 或 resume，对应 {@code create_agent_run}）。 */
    @Operation(summary = "创建智能体运行",
            description = "body: query/agent_slug/thread_id/meta/image_content/model_spec/"
                    + "tool_approval_mode/resume/created_by_run_id/queue_policy")
    @PostMapping("/runs")
    public Map<String, Object> createAgentRun(@RequestBody(required = false) Map<String, Object> body) {
        User user = requireCurrentUser();
        String uid = String.valueOf(user.getUid());
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;

        String agentSlug = text(payload.get("agent_slug"));
        String threadId = text(payload.get("thread_id"));
        Map<String, Object> meta = asMap(payload.get("meta"));
        String queuePolicy = optionalText(payload, "queue_policy", DEFAULT_QUEUE_POLICY);
        String modelSpec = text(payload.get("model_spec"));
        String toolApprovalMode = text(payload.get("tool_approval_mode"));
        String createdByRunId = text(payload.get("created_by_run_id"));

        // resume 路径：恢复已有状态，跳过 request 入队与派发，直接新建 run。
        if (payload.get("resume") != null) {
            if (!DEFAULT_QUEUE_POLICY.equals(queuePolicy)) {
                throw ApiHttpException.unprocessable("queue_policy 仅支持普通 Chat 请求");
            }
            return agentRunService.createResumeRunView(
                    agentSlug,
                    threadId,
                    meta,
                    uid,
                    payload.get("resume"),
                    createdByRunId,
                    null,
                    null,
                    null,
                    null);
        }

        // 普通 chat 路径：写入 request + message，立即派发或入队等待。
        Map<String, Object> effectiveMeta = new LinkedHashMap<>(meta);
        String requestId = truthy(effectiveMeta.get("request_id"))
                ? String.valueOf(effectiveMeta.get("request_id"))
                : UUID.randomUUID().toString();
        effectiveMeta.put("request_id", requestId);

        InputMessageService.AgentRunInputMessage inputMessage = InputMessageService.buildChatInputMessage(
                payload.get("query") == null ? "" : String.valueOf(payload.get("query")),
                text(payload.get("image_content")));

        // 对应 request_metadata={**meta, "tool_approval_mode": payload.tool_approval_mode}
        // —— 即便 tool_approval_mode 为 None 也要写入该键。
        Map<String, Object> requestMetadata = new LinkedHashMap<>(effectiveMeta);
        requestMetadata.put("tool_approval_mode", toolApprovalMode);

        AgentRequestService.AgentRequestInput requestInput = new AgentRequestService.AgentRequestInput(
                agentSlug,
                threadId,
                requestId,
                inputMessage,
                new AgentRequestService.RunOrigin("chat", "web", null, null),
                requestMetadata,
                modelSpec,
                toolApprovalMode,
                queuePolicy,
                false,
                null,
                null);
        return agentRequestService.submitAgentRequest(requestInput, user);
    }

    /** 请求详情（对应 {@code get_request}）。 */
    @Operation(summary = "请求详情")
    @GetMapping("/requests/{request_id}")
    public Map<String, Object> getRequest(@PathVariable("request_id") String requestId) {
        String uid = AuthGuards.requireUser();
        Map<String, Object> result = agentRequestQueueService.getRequest(requestId, uid);
        if (result == null) {
            throw ApiHttpException.notFound("请求不存在");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("request", result);
        return response;
    }

    /** 线程请求队列快照（对应 {@code list_thread_requests}）。 */
    @Operation(summary = "线程请求队列快照")
    @GetMapping("/thread/{thread_id}/requests")
    public Map<String, Object> listThreadRequests(
            @PathVariable("thread_id") String threadId,
            @RequestParam("agent_slug") String agentSlug) {
        String uid = AuthGuards.requireUser();
        return agentRequestQueueService.getThreadQueueSnapshot(uid, agentSlug, threadId);
    }

    /** 继续线程队列（对应 {@code continue_thread_requests}）。 */
    @Operation(summary = "继续线程队列")
    @PostMapping("/thread/{thread_id}/requests/continue")
    public Map<String, Object> continueThreadRequests(
            @PathVariable("thread_id") String threadId,
            @RequestParam("agent_slug") String agentSlug) {
        String uid = AuthGuards.requireUser();
        AgentRequestQueueService.DispatchResult dispatch =
                agentRequestQueueService.continueThreadQueue(uid, agentSlug, threadId);
        agentRequestQueueService.finalizeDispatch(dispatch);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "dispatched");
        result.put("request_id", dispatch.requestId());
        result.put("run_id", dispatch.runId());
        return result;
    }

    /** 取消排队请求（对应 {@code cancel_request}）。 */
    @Operation(summary = "取消排队请求")
    @PostMapping("/requests/{request_id}/cancel")
    public Map<String, Object> cancelRequest(@PathVariable("request_id") String requestId) {
        String uid = AuthGuards.requireUser();
        String status = agentRequestQueueService.cancelQueuedRequest(requestId, uid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request_id", requestId);
        result.put("status", status);
        return result;
    }

    /** 抢占式接替排队请求（对应 {@code steer_request}）。 */
    @Operation(summary = "抢占式接替排队请求")
    @PostMapping("/requests/{request_id}/steer")
    public Map<String, Object> steerRequest(@PathVariable("request_id") String requestId) {
        String uid = AuthGuards.requireUser();
        return agentRequestQueueService.steerQueuedRequest(requestId, uid);
    }

    /** 请求事件流（SSE，对应 {@code stream_request_events_route}）。 */
    @Operation(summary = "请求事件流（SSE）")
    @GetMapping(value = "/requests/{request_id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamRequestEvents(
            @PathVariable("request_id") String requestId) {
        String uid = AuthGuards.requireUser();
        return sseResponse(output -> agentRequestQueueService.streamRequestEvents(requestId, uid, frame -> {
            try {
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException error) {
                throw new IllegalStateException("请求事件流写出失败", error);
            }
        }));
    }

    /** 运行详情（对应 {@code get_agent_run}）。 */
    @Operation(summary = "运行详情")
    @GetMapping("/runs/{run_id}")
    public Map<String, Object> getAgentRun(@PathVariable("run_id") String runId) {
        String uid = AuthGuards.requireUser();
        return agentRunService.getAgentRunView(runId, uid);
    }

    /** 运行结果（对应 {@code get_agent_run_result_route}）。 */
    @Operation(summary = "运行结果")
    @GetMapping("/runs/{run_id}/result")
    public Map<String, Object> getAgentRunResult(@PathVariable("run_id") String runId) {
        String uid = AuthGuards.requireUser();
        return agentRunService.getAgentRunResult(runId, uid);
    }

    /** 运行的 Langfuse 追踪链接（仅超级管理员，对应 {@code get_agent_run_langfuse_link_route}）。 */
    @Operation(summary = "运行 Langfuse 链接（仅超级管理员）")
    @GetMapping("/runs/{run_id}/langfuse")
    public Map<String, Object> getAgentRunLangfuseLink(@PathVariable("run_id") String runId) {
        String uid = AuthGuards.requireSuperadmin();
        return agentRunService.getAgentRunLangfuseLink(runId, uid);
    }

    /** 取消运行（对应 {@code cancel_agent_run}）。 */
    @Operation(summary = "取消运行")
    @PostMapping("/runs/{run_id}/cancel")
    public Map<String, Object> cancelAgentRun(@PathVariable("run_id") String runId) {
        String uid = AuthGuards.requireUser();
        return agentRunService.cancelAgentRunView(runId, uid);
    }

    /** 运行事件流（SSE，对应 {@code stream_run_events}）。 */
    @Operation(summary = "运行事件流（SSE）",
            description = "游标取 Last-Event-ID 头，缺省回退 after_seq（默认 0-0）")
    @GetMapping(value = "/runs/{run_id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamRunEvents(
            @PathVariable("run_id") String runId,
            @RequestParam(value = "after_seq", required = false, defaultValue = "0-0") String afterSeq,
            @RequestParam(value = "verbose", required = false, defaultValue = "true") boolean verbose,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        String uid = AuthGuards.requireUser();
        String cursor = (lastEventId == null || lastEventId.isEmpty()) ? afterSeq : lastEventId;
        return sseResponse(output -> agentRunService.streamAgentRunEvents(
                runId, cursor, uid, verbose, frame -> {
                    try {
                        output.write(frame.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    } catch (IOException error) {
                        throw new IllegalStateException("运行事件流写出失败", error);
                    }
                }));
    }

    /** 线程当前活动运行（对应 {@code get_thread_active_run}）。 */
    @Operation(summary = "线程当前活动运行")
    @GetMapping("/thread/{thread_id}/active_run")
    public Map<String, Object> getThreadActiveRun(@PathVariable("thread_id") String threadId) {
        String uid = AuthGuards.requireUser();
        return agentRunService.getActiveRunByThread(threadId, uid);
    }

    // =========================================================================
    // === 内部小工具 ===
    // =========================================================================

    /** 对应 {@code _backend_info}：{@code id → backend_id}，并补 {@code type}。 */
    private static Map<String, Object> backendInfo(Map<String, Object> info) {
        Map<String, Object> data = new LinkedHashMap<>(info);
        data.put("backend_id", data.remove("id"));
        data.put("type", "agent_backend");
        return data;
    }

    /** 对应 {@code _serialize_agent}：序列化后把 config_json 收敛到声明字段。 */
    private Map<String, Object> serializeAgent(
            Agent item,
            User user,
            boolean includeConfigurableItems,
            Map<String, Map<String, Object>> backendInfoCache) {
        Map<String, Object> data = agentRepository.serialize(
                item, PermissionSubject.of(user), includeConfigurableItems, backendInfoCache);
        data.put("config_json", filterAgentConfigJson(data.get("config_json")));
        return data;
    }

    /**
     * 对应 {@code _filter_agent_config_json}：仅保留 Context 已声明字段。
     *
     * <p>参考实现按 {@code backend.context_schema} 取声明集；本工程声明字段表是静态表，
     * 故直接走 {@link BaseContext#filterDeclaredConfig(Map)}（见类注释的能力差异）。
     */
    private static Map<String, Object> filterAgentConfigJson(Object configJson) {
        return BaseContext.filterDeclaredConfig(asMap(configJson));
    }

    /** 取当前用户实体（参考实现里由认证依赖一并完成）。 */
    private User requireCurrentUser() {
        return requireUser(AuthGuards.requireUser());
    }

    private User requireUser(String uid) {
        User user = userRepository.getByUid(uid);
        if (user == null) {
            throw ApiHttpException.notFound("用户不存在");
        }
        return user;
    }

    /** 组装 text/event-stream 响应，响应头逐字对齐参考实现。 */
    private static ResponseEntity<StreamingResponseBody> sseResponse(StreamingResponseBody body) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /** 宽松取字符串（非字符串值按 {@code str()} 处理，对应 pydantic 之外的既有做法）。 */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 取可选字符串字段，缺失或空串时回落默认值（对应 pydantic 的字段默认值）。 */
    private static String optionalText(Map<String, Object> payload, String field, String defaultValue) {
        Object value = payload.get(field);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? defaultValue : text;
    }

    /** {@code {**meta}} 语义：非 Map 值退化为空表（对应 {@code dict(payload.meta or {})}）。 */
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /** 与 {@link #asMap} 同义，但区分「未传」（返回 {@code null}）。 */
    private static Map<String, Object> asMapOrNull(Object value) {
        return value instanceof Map<?, ?> ? asMap(value) : null;
    }

    private static JSONObject toJsonObject(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        JSONObject result = new JSONObject();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map<String, Collection<String>> asResourceAccess(Map<String, ?> raw) {
        if (raw == null) {
            return null;
        }
        Map<String, Collection<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            Collection<String> values = new ArrayList<>();
            if (entry.getValue() instanceof Collection<?> collection) {
                for (Object item : collection) {
                    values.add(item == null ? null : String.valueOf(item));
                }
            }
            result.put(entry.getKey(), values);
        }
        return result;
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            result.add(item == null ? null : String.valueOf(item));
        }
        return result;
    }

    /** 对应 {@code AgentRunService.truthy} 的宽松真值判定。 */
    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        String text = String.valueOf(value).strip().toLowerCase();
        return !text.isEmpty() && !"false".equals(text) && !"0".equals(text) && !"none".equals(text);
    }
}
