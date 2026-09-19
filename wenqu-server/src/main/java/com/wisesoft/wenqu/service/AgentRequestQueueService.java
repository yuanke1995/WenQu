package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.SseUtils;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.AgentRunRequest;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.repository.port.MessageMapper;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.AgentRunRequestRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.workspace.WorkspacePaths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agent 请求队列服务。
 *
 * <p>由参考实现的 {@code services/agent_request_queue_service.py} 逐函数翻译：FIFO 派发、取消、
 * 引导与恢复扫描；普通请求提交由 {@link AgentRequestService} 拥有（本类不调用
 * {@link AgentRunService} 的私有函数）。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code pg_manager.get_async_session_context()} 自管会话（{@code dispatch_next_request} /
 *       {@code recover_pending_dispatches} / {@code should_end_run_for_steer}）→
 *       {@link TransactionTemplate}（{@code PROPAGATION_REQUIRES_NEW}）：参考实现在这些位置
 *       "开一个会话、做完提交"，本工程用独立事务承载同一提交边界。</li>
 *   <li>{@code async with db.begin_nested()} → {@code PROPAGATION_NESTED} 事务模板
 *       （与 {@link AgentRunService} 同一口径）。</li>
 *   <li>{@code db.flush()} → 空操作：本工程 Mapper 即调用即写，flush 无对应动作。</li>
 *   <li>{@code asyncio.gather(..., return_exceptions=True)} → 顺序循环 + 逐项捕获异常
 *       （参考实现并发扫描，本实现串行；结果处理与日志语义一致）。</li>
 *   <li>{@code async generator … yield} → 回调 sink（{@code Consumer<String>}），调用方线程顺序消费。</li>
 *   <li>{@code asyncio.sleep} → {@link Thread#sleep}；{@code asyncio.CancelledError} →
 *       调用中断（{@link InterruptedException}）直接返回，语义等价"客户端断开即结束流"。</li>
 *   <li>{@code IntegrityError} 的约束名判定 → {@link DuplicateKeyException}
 *       消息中包含 {@code uq_agent_runs_one_active_per_thread} 即视为同型冲突。</li>
 *   <li>{@code utc_now_naive()} → {@link DateTimeUtils#utcNowNaive()}。</li>
 * </ul>
 */
@Service
public class AgentRequestQueueService {

    private static final Logger log = LoggerFactory.getLogger(AgentRequestQueueService.class);

    /** 支持的排队策略（参考实现 SUPPORTED_QUEUE_POLICIES，条目与顺序逐字保留）。 */
    public static final List<String> SUPPORTED_QUEUE_POLICIES = List.of("enqueue", "reject", "steer");

    /** 暂未实现的排队策略（参考实现 NOT_IMPLEMENTED_QUEUE_POLICIES）。 */
    public static final List<String> NOT_IMPLEMENTED_QUEUE_POLICIES = List.of("guided", "bridge");

    // Request 生命周期状态（参考实现同名常量，取值逐字保留）
    public static final String REQUEST_STATUS_QUEUED = "queued";
    public static final String REQUEST_STATUS_DISPATCHED = "dispatched";
    public static final String REQUEST_STATUS_CANCELLED = "cancelled";
    public static final String REQUEST_STATUS_REJECTED = "rejected";
    public static final String REQUEST_STATUS_FAILED = "failed";
    public static final Set<String> REQUEST_TERMINAL_STATUSES = Set.of(
            REQUEST_STATUS_CANCELLED, REQUEST_STATUS_REJECTED, REQUEST_STATUS_FAILED);

    // 消息投递状态（与 messages.delivery_status 对齐）
    public static final String DELIVERY_STATUS_QUEUED = "queued";
    public static final String DELIVERY_STATUS_DISPATCHED = "dispatched";
    public static final String DELIVERY_STATUS_REJECTED = "rejected";

    /** 运行表上的"单线程单活跃 run"唯一约束名（参考实现按名判定冲突）。 */
    private static final String ONE_ACTIVE_PER_THREAD_CONSTRAINT = "uq_agent_runs_one_active_per_thread";

    /** 一次已提交前的 FIFO 队头派发结果（对应 {@code DispatchResult}）。 */
    public record DispatchResult(String requestId, String runId, WorkdirService.WorkdirBinding workdirBinding) {}

    /** 队列状态与最小元数据（对应 {@code _get_queue_state} 的二元返回）。 */
    public record QueueState(String status, Map<String, Object> metadata) {}

    private final AgentRunRepository runRepository;
    private final AgentRunRequestRepository requestRepository;
    private final ConversationRepository conversationRepository;
    private final WorkdirService workdirService;
    private final AgentRunService agentRunService;
    private final MessageMapper messageMapper;
    private final TransactionTemplate selfManagedTransactionTemplate;
    private final TransactionTemplate nestedTransactionTemplate;

    public AgentRequestQueueService(
            AgentRunRepository runRepository,
            AgentRunRequestRepository requestRepository,
            ConversationRepository conversationRepository,
            WorkdirService workdirService,
            AgentRunService agentRunService,
            MessageMapper messageMapper,
            PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.requestRepository = requestRepository;
        this.conversationRepository = conversationRepository;
        this.workdirService = workdirService;
        this.agentRunService = agentRunService;
        this.messageMapper = messageMapper;
        this.selfManagedTransactionTemplate = new TransactionTemplate(transactionManager);
        this.selfManagedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.nestedTransactionTemplate = new TransactionTemplate(transactionManager);
        this.nestedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // ==================== 策略校验与冲突 ====================

    /** 校验 queue_policy，对未实现策略返回 422（对应 {@code validate_queue_policy}）。 */
    public static String validateQueuePolicy(String queuePolicy) {
        if (NOT_IMPLEMENTED_QUEUE_POLICIES.contains(queuePolicy)) {
            throw ApiHttpException.unprocessable("queue_policy '" + queuePolicy + "' 暂未实现");
        }
        if (!SUPPORTED_QUEUE_POLICIES.contains(queuePolicy)) {
            throw ApiHttpException.unprocessable("不支持的 queue_policy: " + queuePolicy);
        }
        return queuePolicy;
    }

    /** 409 队列冲突（对应 {@code queue_conflict}）。 */
    public static ApiHttpException queueConflict(String code, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("message", message);
        return ApiHttpException.conflictWithDetail(detail);
    }

    // ==================== 引导与取消 ====================

    /** 把普通 Chat 排队请求提升为下一条执行的 Steer（对应 {@code steer_queued_request}）。 */
    public Map<String, Object> steerQueuedRequest(String requestId, String currentUid) {
        AgentRunRequest existing = requestRepository.getByRequestId(requestId);
        if (existing == null || !String.valueOf(currentUid).equals(existing.getUid())) {
            throw ApiHttpException.objectDetail(404, null, requestNotFoundDetail());
        }

        getThreadConversation(existing.getUid(), existing.getAgentSlug(), existing.getConversationThreadId(), true);

        AgentRunRequest request = requestRepository.lockByRequestId(requestId);
        if (request == null || !String.valueOf(currentUid).equals(request.getUid())) {
            throw ApiHttpException.objectDetail(404, null, requestNotFoundDetail());
        }
        if ("steer".equals(request.getQueuePolicy()) && REQUEST_STATUS_QUEUED.equals(request.getStatus())) {
            return requestView(requestRepository, request);
        }
        if (!REQUEST_STATUS_QUEUED.equals(request.getStatus())
                || !"enqueue".equals(request.getQueuePolicy())
                || !"chat".equals(request.getSource())) {
            throw queueConflict("request_not_queued", "只有普通 Chat 排队请求可以升级为引导");
        }

        AgentRunRequest pendingSteer = requestRepository.getPendingSteer(
                request.getUid(), request.getAgentSlug(), request.getConversationThreadId());
        if (pendingSteer != null && !requestId.equals(pendingSteer.getRequestId())) {
            throw queueConflict("steer_already_pending", "线程已有等待执行的引导请求");
        }

        AgentRun activeRun = runRepository.getActiveRunByThreadForUser(
                request.getAgentSlug(), request.getConversationThreadId(), request.getUid());
        if (activeRun == null || !isSteerableMessageRun(activeRun)) {
            throw queueConflict("run_not_steerable", "当前运行不支持引导");
        }

        request.setQueuePolicy("steer");
        request.setUpdatedAt(DateTimeUtils.utcNowNaive());
        requestRepository.lockByRequestId(requestId);
        persistRequestFields(request);
        return requestView(requestRepository, request);
    }

    /** 判断当前 Chat Run 是否应在模型调用前让位给 Steer（对应 {@code should_end_run_for_steer}）。 */
    public boolean shouldEndRunForSteer(String runId) {
        return Boolean.TRUE.equals(selfManagedTransactionTemplate.execute(status -> {
            AgentRun run = runRepository.getRun(runId);
            if (run == null || !isSteerableMessageRun(run)) {
                return false;
            }
            AgentRunRequest request = requestRepository.getPendingSteer(
                    run.getUid(), run.getAgentSlug(), run.getConversationThreadId());
            return request != null;
        }));
    }

    /** 提交事务并物化 Workdir，随后才把已创建的 run 投递给队列（对应 {@code finalize_dispatch}）。 */
    public void finalizeDispatch(DispatchResult dispatch) {
        // 参考实现此处是"提交调用方事务"；本工程调用方的写操作已逐条落库，
        // 故提交边界由调用方的事务模板承担，这里只做提交后的物化与投递。
        WorkdirService.WorkdirBinding binding = dispatch.workdirBinding();
        if (binding.materializeManaged()) {
            WorkspacePaths.ensureBoundUserWorkdir(binding.uid(), binding.workdirPath());
        }
        agentRunService.enqueueAgentRun(dispatch.runId());
    }

    /** 取消一个 queued 请求；已 dispatched 的不可取消（对应 {@code cancel_queued_request}）。 */
    public String cancelQueuedRequest(String requestId, String currentUid) {
        AgentRunRequest existing = requestRepository.getByRequestId(requestId);
        if (existing == null || !String.valueOf(currentUid).equals(existing.getUid())) {
            throw ApiHttpException.notFound("请求不存在");
        }

        getThreadConversation(existing.getUid(), existing.getAgentSlug(), existing.getConversationThreadId(), true);

        AgentRunRequest request = requestRepository.lockByRequestId(requestId);
        if (request == null || !String.valueOf(currentUid).equals(request.getUid())) {
            throw ApiHttpException.notFound("请求不存在");
        }
        if (REQUEST_STATUS_DISPATCHED.equals(request.getStatus())) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("code", "request_already_dispatched");
            detail.put("message", "请求已派发，请通过 run 取消接口取消正在进行的运行");
            detail.put("run_id", request.getDispatchedRunId());
            throw ApiHttpException.conflictWithDetail(detail);
        }
        if (REQUEST_TERMINAL_STATUSES.contains(request.getStatus())) {
            return request.getStatus();
        }
        if ("steer".equals(request.getQueuePolicy())) {
            AgentRun activeRun = runRepository.getActiveRunByThreadForUser(
                    request.getAgentSlug(), request.getConversationThreadId(), request.getUid());
            if (activeRun != null) {
                throw queueConflict("steer_in_progress", "引导已等待当前运行结束，暂时不能取消");
            }
        }
        request.setStatus(REQUEST_STATUS_CANCELLED);
        request.setUpdatedAt(DateTimeUtils.utcNowNaive());
        persistRequestFields(request);
        return REQUEST_STATUS_CANCELLED;
    }

    // ==================== 查询 ====================

    /** 按 request_id 查询请求（含 uid 归属校验）（对应 {@code get_request}）。 */
    public Map<String, Object> getRequest(String requestId, String uid) {
        AgentRunRequest request = requestRepository.getByRequestId(requestId);
        if (request == null || !String.valueOf(uid).equals(request.getUid())) {
            return null;
        }
        return request.toDict();
    }

    /** 读取队列请求与最小状态投影（对应 {@code get_thread_queue_snapshot}）。 */
    public Map<String, Object> getThreadQueueSnapshot(String uid, String agentSlug, String threadId) {
        getThreadConversation(uid, agentSlug, threadId, false);
        List<AgentRunRequest> items = requestRepository.listQueued(uid, agentSlug, threadId);

        List<Integer> messageIds = new ArrayList<>();
        for (AgentRunRequest request : items) {
            if (request.getInputMessageId() != null) {
                messageIds.add(request.getInputMessageId());
            }
        }
        Map<Integer, String> contents = new LinkedHashMap<>();
        if (!messageIds.isEmpty()) {
            List<Message> messages = messageMapper.selectBatchIds(messageIds);
            for (Message message : messages) {
                contents.put(message.getId(), message.getContent());
            }
        }

        List<Map<String, Object>> requests = new ArrayList<>();
        int position = 0;
        for (AgentRunRequest request : items) {
            position += 1;
            Map<String, Object> data = request.toDict();
            if (request.getInputMessageId() != null) {
                String content = contents.get(request.getInputMessageId());
                data.put("content", content == null ? "" : content);
            }
            data.put("queue_position", position);
            requests.add(data);
        }
        QueueState state = getQueueState(uid, agentSlug, threadId, items.isEmpty() ? null : items.get(0));

        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("status", state.status());
        queue.putAll(state.metadata());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", requests);
        result.put("queue", queue);
        return result;
    }

    /** 在同一事务内确认 paused 状态并派发 FIFO 队头（对应 {@code continue_thread_queue}）。 */
    public DispatchResult continueThreadQueue(String uid, String agentSlug, String threadId) {
        Conversation conversation = getThreadConversation(uid, agentSlug, threadId, true);
        AgentRunRequest head = requestRepository.getQueueHead(uid, agentSlug, threadId);
        if (head == null) {
            throw queueConflict("queue_empty", "队列为空");
        }

        QueueState state = getQueueState(uid, agentSlug, threadId, head);
        if ("running".equals(state.status())) {
            throw queueConflict("run_active", "线程已有正在执行的运行");
        }
        if ("interrupted".equals(state.status())) {
            throw queueConflict("run_interrupted", "线程正在等待用户回答或审批");
        }
        if (!"paused".equals(state.status())) {
            throw queueConflict("queue_not_paused", "当前队列不需要人工继续");
        }

        WorkdirService.WorkdirBinding workdirBinding =
                workdirService.resolveConversationWorkdirBinding(conversation, uid, null);
        DispatchResult dispatched = dispatchLockedHead(head, workdirBinding);
        if (dispatched != null) {
            return dispatched;
        }

        AgentRun activeRun = runRepository.getActiveRunByThreadForUser(agentSlug, threadId, uid);
        if (activeRun != null) {
            throw queueConflict("run_active", "线程已有正在执行的运行");
        }
        throw queueConflict("queue_not_paused", "当前队列状态已变化");
    }

    /** Request SSE：发送 queued 心跳、位置变化，dispatched 时发送 run_created 并结束。 */
    public void streamRequestEvents(String requestId, String uid, Consumer<String> sink) {
        LocalDateTime startedAt = DateTimeUtils.utcNowNaive();
        LocalDateTime lastHeartbeat = startedAt;
        int lastPosition = -1;
        try {
            while (true) {
                AgentRunRequest request = requestRepository.getByRequestId(requestId);
                if (request == null || !String.valueOf(uid).equals(request.getUid())) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("request_id", requestId);
                    data.put("message", "请求不存在");
                    sink.accept(SseUtils.formatSse(data, "error", null));
                    return;
                }

                if (REQUEST_STATUS_DISPATCHED.equals(request.getStatus())) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("request_id", requestId);
                    data.put("run_id", request.getDispatchedRunId());
                    data.put("stream_url", "/api/agent/runs/" + request.getDispatchedRunId() + "/events");
                    sink.accept(SseUtils.formatSse(data, "run_created", null));
                    return;
                }

                if (REQUEST_TERMINAL_STATUSES.contains(request.getStatus())) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("request_id", requestId);
                    data.put("status", request.getStatus());
                    sink.accept(SseUtils.formatSse(data, request.getStatus(), null));
                    return;
                }

                // queued: 用 COUNT 查询位置（O(1)），仅在变化时上报
                int position = requestRepository.getQueuePositionFor(request);
                if (position != lastPosition) {
                    lastPosition = position;
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("request_id", requestId);
                    data.put("status", REQUEST_STATUS_QUEUED);
                    data.put("position", position);
                    sink.accept(SseUtils.formatSse(data, REQUEST_STATUS_QUEUED, null));
                }

                LocalDateTime now = DateTimeUtils.utcNowNaive();
                if (java.time.Duration.between(lastHeartbeat, now).getSeconds() >= SseUtils.SSE_HEARTBEAT_SECONDS) {
                    sink.accept(SseUtils.formatHeartbeat());
                    lastHeartbeat = now;
                }
                if (java.time.Duration.between(startedAt, now).getSeconds()
                        >= (long) SseUtils.SSE_MAX_CONNECTION_MINUTES * 60L) {
                    return;
                }
                Thread.sleep((long) (SseUtils.SSE_POLL_INTERVAL_SECONDS * 1000L));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 从持久化请求投影提交和排队操作的响应（对应 {@code request_view}）。 */
    public Map<String, Object> requestView(AgentRunRequestRepository repo, AgentRunRequest request) {
        String runId = request.getDispatchedRunId();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("request_id", request.getRequestId());
        data.put("status", request.getStatus());
        data.put("queue_policy", request.getQueuePolicy());
        data.put(
                "queue_position",
                "queued".equals(request.getStatus()) ? repo.getQueuePosition(request.getRequestId()) : null);
        data.put("message_id", request.getInputMessageId());
        data.put("run_id", runId);
        data.put("stream_url", runId == null ? null : "/api/agent/runs/" + runId + "/events");
        data.put(
                "request_events_url",
                "queued".equals(request.getStatus())
                        ? "/api/agent/requests/" + request.getRequestId() + "/events"
                        : null);
        data.put("thread_id", request.getConversationThreadId());
        return data;
    }

    /** 确认 Run 正在运行且来自支持 Steer 的消息入口（对应 {@code is_steerable_message_run}）。 */
    public boolean isSteerableMessageRun(AgentRun run) {
        if (!"running".equals(run.getStatus()) || !"chat".equals(run.getRunType())) {
            return false;
        }
        AgentRunRequest request = requestRepository.getByRequestId(run.getRequestId());
        return request != null && Set.of("chat", "channel").contains(request.getSource());
    }

    /** 线程归属校验 + 可选加锁读取（对应 {@code get_thread_conversation}）。 */
    public Conversation getThreadConversation(String uid, String agentSlug, String threadId, boolean lock) {
        Conversation conversation = lock
                ? conversationRepository.lockConversationByThreadId(threadId)
                : conversationRepository.getConversationByThreadId(threadId);
        if (conversationMatches(conversation, uid, agentSlug)) {
            return conversation;
        }
        throw ApiHttpException.notFound("对话线程不存在");
    }

    // ==================== 派发 ====================

    /** 派发线程队头请求（对应 {@code dispatch_next_request}；自管事务，提交后投递）。 */
    public String dispatchNextRequest(String uid, String agentSlug, String threadId) {
        WorkdirService.WorkdirBinding[] bindingHolder = new WorkdirService.WorkdirBinding[1];
        String runId = selfManagedTransactionTemplate.execute(status -> {
            Conversation conversation = conversationRepository.lockConversationByThreadId(threadId);
            if (!conversationMatches(conversation, uid, agentSlug)) {
                return null;
            }
            WorkdirService.WorkdirBinding workdirBinding =
                    workdirService.resolveConversationWorkdirBinding(conversation, uid, null);
            bindingHolder[0] = workdirBinding;
            AgentRun activeRun = runRepository.getActiveRunByThreadForUser(agentSlug, threadId, uid);
            if (activeRun != null) {
                return "pending".equals(activeRun.getStatus()) ? activeRun.getId() : null;
            }
            DispatchResult dispatch = dispatchReadyHead(uid, agentSlug, threadId, workdirBinding, null);
            return dispatch == null ? null : dispatch.runId();
        });

        if (runId != null) {
            WorkdirService.WorkdirBinding binding = bindingHolder[0];
            if (binding == null) {
                throw new IllegalStateException("Conversation " + threadId + " 缺少 Workdir 绑定，无法派发 Run");
            }
            if (binding.materializeManaged()) {
                WorkspacePaths.ensureBoundUserWorkdir(binding.uid(), binding.workdirPath());
            }
            agentRunService.enqueueAgentRun(runId);
            return runId;
        }
        return null;
    }

    /** 恢复 pending 投递及 completed hook 留下的 ready 队列（对应 {@code recover_pending_dispatches}）。 */
    public void recoverPendingDispatches() {
        List<String[]> scopes = selfManagedTransactionTemplate.execute(status -> {
            // 参考实现先取 pending run 的三元组，再并上 queued request 的三元组（去重）
            Set<String> seen = new LinkedHashSet<>();
            List<String[]> collected = new ArrayList<>();
            for (String[] scope : runRepository.listPendingDispatchScopes()) {
                if (seen.add(String.join("\u0000", scope))) {
                    collected.add(scope);
                }
            }
            for (String[] scope : requestRepository.listQueuedScopes()) {
                if (seen.add(String.join("\u0000", scope))) {
                    collected.add(scope);
                }
            }
            return collected;
        });

        if (scopes == null) {
            return;
        }
        for (String[] scope : scopes) {
            String runId;
            try {
                runId = dispatchNextRequest(scope[0], scope[1], scope[2]);
            } catch (RuntimeException error) {
                log.error("Failed to recover pending run scope: {}", error.toString());
                continue;
            }
            if (runId != null) {
                log.info("Recovered pending run or queue: {}", runId);
            }
        }
    }

    /** 只在 ready 状态派发 FIFO 队头（对应 {@code dispatch_ready_head}）。 */
    public DispatchResult dispatchReadyHead(
            String uid,
            String agentSlug,
            String threadId,
            WorkdirService.WorkdirBinding workdirBinding,
            String expectedRequestId) {
        AgentRunRequest head = requestRepository.getQueueHead(uid, agentSlug, threadId);
        if (head == null) {
            return null;
        }
        if (expectedRequestId != null && !expectedRequestId.equals(head.getRequestId())) {
            return null;
        }
        QueueState state = getQueueState(uid, agentSlug, threadId, head);
        if (!"ready".equals(state.status())) {
            return null;
        }
        return dispatchLockedHead(head, workdirBinding);
    }

    /** 将已锁定的 queued 队头转换为 AgentRun，不提交事务（对应 {@code _dispatch_locked_head}）。 */
    private DispatchResult dispatchLockedHead(
            AgentRunRequest head, WorkdirService.WorkdirBinding workdirBinding) {
        String runId = UUID.randomUUID().toString();
        try {
            nestedTransactionTemplate.executeWithoutResult(status -> {
                Map<String, Object> inputPayload = AgentRunService.parseJsonObject(head.getInputPayload());
                Map<String, Object> originMetadata = AgentRunService.parseJsonObject(head.getOriginMetadata());
                runRepository.createRun(
                        runId,
                        head.getConversationThreadId(),
                        head.getConversationThreadId(),
                        head.getAgentSlug(),
                        head.getUid(),
                        head.getRequestId(),
                        inputPayload == null ? new LinkedHashMap<>() : inputPayload,
                        head.getSource(),
                        head.getChannel(),
                        head.getExternalId(),
                        originMetadata == null ? new LinkedHashMap<>() : originMetadata,
                        workdirBinding.conversationId(),
                        null,
                        null,
                        "chat",
                        head.getInputMessageId());
                Message message = messageMapper.selectById(head.getInputMessageId());
                if (message != null) {
                    message.setRunId(runId);
                    message.setDeliveryStatus(DELIVERY_STATUS_DISPATCHED);
                    messageMapper.updateById(message);
                }
                requestRepository.markDispatched(head.getRequestId(), runId);
            });
        } catch (DuplicateKeyException exception) {
            if (!isOneActivePerThreadViolation(exception)) {
                throw exception;
            }
            log.info("Dispatch conflict for request {}, keeping queued", head.getRequestId());
            return null;
        }
        return new DispatchResult(head.getRequestId(), runId, workdirBinding);
    }

    /**
     * 判定唯一键冲突是否来自"单线程单活跃 run"约束。
     *
     * <p>参考实现读 {@code exc.orig.constraint_name}（或异常链上同名属性）；JDBC 层未把约束名
     * 结构化暴露，故退化为在异常消息里匹配约束名——判等语义（"是否该约束"）保持不变。
     */
    private static boolean isOneActivePerThreadViolation(DuplicateKeyException exception) {
        String message = exception.getMessage();
        if (message != null && message.contains(ONE_ACTIVE_PER_THREAD_CONSTRAINT)) {
            return true;
        }
        Throwable cause = exception.getMostSpecificCause();
        return cause != null
                && cause.getMessage() != null
                && cause.getMessage().contains(ONE_ACTIVE_PER_THREAD_CONSTRAINT);
    }

    // ==================== 内部 ====================

    /**
     * 基于队头、active run 与最新顶层 run 派生队列状态（对应 {@code _get_queue_state}）。
     */
    private QueueState getQueueState(
            String uid, String agentSlug, String threadId, AgentRunRequest head) {
        if (head == null) {
            return new QueueState("idle", queueStateMetadata(null, null, false));
        }

        AgentRun activeRun = runRepository.getActiveRunByRuntimeScopeForUser(threadId, uid);
        if (activeRun != null) {
            return new QueueState("running", queueStateMetadata(null, null, false));
        }

        AgentRun latestRun = runRepository.getLatestChatOrResumeRun(uid, agentSlug, threadId);
        if (latestRun != null && "interrupted".equals(latestRun.getStatus())) {
            return new QueueState("interrupted", queueStateMetadata(null, latestRun.getId(), false));
        }

        boolean terminal = latestRun != null
                && ("failed".equals(latestRun.getStatus()) || "cancelled".equals(latestRun.getStatus()));
        if (terminal && latestRun.getFinishedAt() == null) {
            throw new IllegalStateException("Terminal run " + latestRun.getId() + " is missing finished_at");
        }
        if (terminal
                && head.getCreatedAt() != null
                && latestRun.getFinishedAt() != null
                && !head.getCreatedAt().isAfter(latestRun.getFinishedAt())) {
            return new QueueState("paused", queueStateMetadata(latestRun.getStatus(), latestRun.getId(), true));
        }

        return new QueueState("ready", queueStateMetadata(null, null, false));
    }

    private static Map<String, Object> queueStateMetadata(
            String pausedReason, String blockingRunId, boolean canContinue) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("paused_reason", pausedReason);
        metadata.put("blocking_run_id", blockingRunId);
        metadata.put("can_continue", canContinue);
        return metadata;
    }

    /** 线程归属校验：存在、未删除、归属当前用户与 agent（对应 {@code _conversation_matches}）。 */
    private static boolean conversationMatches(Conversation conversation, String uid, String agentSlug) {
        return conversation != null
                && String.valueOf(uid).equals(conversation.getUid())
                && !"deleted".equals(conversation.getStatus())
                && String.valueOf(agentSlug).equals(conversation.getAgentId());
    }

    private static Map<String, Object> requestNotFoundDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", "request_not_found");
        detail.put("message", "请求不存在");
        return detail;
    }

    /** 就地更新请求的可变字段（对应参考实现改属性 + {@code db.flush()}）。 */
    private void persistRequestFields(AgentRunRequest request) {
        requestRepository.updateQueueState(request);
    }
}
