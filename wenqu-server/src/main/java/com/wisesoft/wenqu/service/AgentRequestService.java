package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.AgentManager;
import com.wisesoft.wenqu.agents.BaseAgent;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.AgentRunRequest;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.Project;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.AgentRunRequestRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.repositories.ProjectRepository;
import com.wisesoft.wenqu.service.InputMessageService.AgentRunInputMessage;
import com.wisesoft.wenqu.workspace.WorkspacePaths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 统一的 AgentRun 消息提交应用服务。
 *
 * <p>由参考实现的 {@code services/agent_request_service.py} 逐行翻译。Web Chat、Agent Call 和
 * 评估入口只在路由/适配层处理各自的输入输出协议；实际的 {@code AgentRunRequest} 入队、
 * Conversation 绑定和提交后派发都从这里进入。Resume 与 Subagent 保留各自的特殊生命周期，
 * 不经过本服务。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code AsyncSession} 入参 + 调用方的 {@code db.commit()} → {@link TransactionTemplate}
 *       （{@code PROPAGATION_REQUIRES_NEW}）：参考实现的"路由层持有会话、本服务内提交"改写为
 *       "本服务自管一个提交边界"，提交后的物化与投递仍在事务外执行，顺序与语义不变。</li>
 *   <li>{@code async with db.begin_nested()} → {@code PROPAGATION_NESTED} 事务模板。</li>
 *   <li>{@code dataclass(frozen=True) RunOrigin / AgentRequestInput} → record
 *       （{@code replace(...)} 的不可变改写 → record 的 {@code with*} 便捷方法）。</li>
 *   <li>{@code db.flush()} → 空操作（本工程 Mapper 即调用即写）。</li>
 *   <li>{@code IntegrityError} → {@link DuplicateKeyException}（同一"唯一键竞争"语义）。</li>
 * </ul>
 */
@Service
public class AgentRequestService {

    /** 描述一次 Run 请求的入口来源与传输通道（对应 {@code RunOrigin}）。 */
    public record RunOrigin(String source, String channel, String externalId, Map<String, Object> metadata) {

        public RunOrigin {
            metadata = metadata == null ? new LinkedHashMap<>() : metadata;
        }

        /** 对应 {@code replace(origin, external_id=...) }。 */
        public RunOrigin withExternalId(String value) {
            return new RunOrigin(source, channel, value, metadata);
        }

        /** 对应 {@code replace(origin, external_id=..., metadata=...)}。 */
        public RunOrigin withExternalIdAndMetadata(String value, Map<String, Object> newMetadata) {
            return new RunOrigin(source, channel, value, newMetadata);
        }
    }

    /** 普通 Agent 请求的入口输入（对应 {@code AgentRequestInput}）。 */
    public record AgentRequestInput(
            String agentSlug,
            String threadId,
            String requestId,
            AgentRunInputMessage inputMessage,
            RunOrigin origin,
            Map<String, Object> requestMetadata,
            String modelSpec,
            String toolApprovalMode,
            String queuePolicy,
            boolean createConversation,
            String conversationTitle,
            String conversationProjectId) {

        /** 便捷构造：与参考实现 dataclass 的默认值一致。 */
        public static AgentRequestInput of(
                String agentSlug,
                String threadId,
                String requestId,
                AgentRunInputMessage inputMessage,
                RunOrigin origin) {
            return new AgentRequestInput(
                    agentSlug, threadId, requestId, inputMessage, origin,
                    new LinkedHashMap<>(), null, null, "enqueue", false, null, null);
        }

        public AgentRequestInput withRequestMetadata(Map<String, Object> value) {
            return new AgentRequestInput(
                    agentSlug, threadId, requestId, inputMessage, origin, value,
                    modelSpec, toolApprovalMode, queuePolicy, createConversation,
                    conversationTitle, conversationProjectId);
        }

        public AgentRequestInput withOrigin(RunOrigin value) {
            return new AgentRequestInput(
                    agentSlug, threadId, requestId, inputMessage, value, requestMetadata,
                    modelSpec, toolApprovalMode, queuePolicy, createConversation,
                    conversationTitle, conversationProjectId);
        }
    }

    private final AgentRepository agentRepository;
    private final AgentRunRepository runRepository;
    private final AgentRunRequestRepository requestRepository;
    private final ConversationRepository conversationRepository;
    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final WorkdirService workdirService;
    private final AgentManager agentManager;
    private final AgentRunService agentRunService;
    private final AgentRequestQueueService queueService;
    private final com.wisesoft.wenqu.repository.port.MessageMapper messageMapper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate nestedTransactionTemplate;

    public AgentRequestService(
            AgentRepository agentRepository,
            AgentRunRepository runRepository,
            AgentRunRequestRepository requestRepository,
            ConversationRepository conversationRepository,
            ProjectRepository projectRepository,
            ProjectService projectService,
            WorkdirService workdirService,
            AgentManager agentManager,
            AgentRunService agentRunService,
            AgentRequestQueueService queueService,
            com.wisesoft.wenqu.repository.port.MessageMapper messageMapper,
            PlatformTransactionManager transactionManager) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.requestRepository = requestRepository;
        this.conversationRepository = conversationRepository;
        this.projectRepository = projectRepository;
        this.projectService = projectService;
        this.workdirService = workdirService;
        this.agentManager = agentManager;
        this.agentRunService = agentRunService;
        this.queueService = queueService;
        this.messageMapper = messageMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.nestedTransactionTemplate = new TransactionTemplate(transactionManager);
        this.nestedTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // =========================================================================
    // === submit_agent_request
    // =========================================================================

    /**
     * 校验作用域、写入 Request 并在提交后投递消息型 AgentRun
     * （对应 {@code submit_agent_request}）。
     *
     * <p>{@code createConversation} 仅用于没有显式 Thread 的外部入口；普通 Web Chat 必须复用
     * 已经创建的 Conversation。不同入口的协议适配不应绕过这里。
     */
    public Map<String, Object> submitAgentRequest(AgentRequestInput requestInput, User currentUser) {
        RunOrigin origin = requestInput.origin();
        if (origin.source() == null || origin.source().strip().isEmpty()
                || origin.channel() == null || origin.channel().strip().isEmpty()) {
            throw ApiHttpException.unprocessable("Run origin source/channel 不能为空");
        }
        if (origin.source().length() > 32) {
            throw ApiHttpException.unprocessable("Run origin source 不能超过 32 个字符");
        }
        if (origin.channel().length() > 32) {
            throw ApiHttpException.unprocessable("Run origin channel 不能超过 32 个字符");
        }
        String externalId = origin.externalId() == null ? null : origin.externalId().strip();
        if ("".equals(externalId)) {
            externalId = null;
        }
        Map<String, Object> originMetadata = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : origin.metadata().entrySet()) {
            if (!Set.of("source", "channel", "external_id").contains(entry.getKey())) {
                originMetadata.put(entry.getKey(), entry.getValue());
            }
        }

        String uid = String.valueOf(currentUser.getUid());
        Agent agentItem = agentRepository.getVisibleBySlug(
                requestInput.agentSlug(), PermissionSubject.of(currentUser), AgentRepository.AgentEntryKind.MAIN);
        if (agentItem == null) {
            throw ApiHttpException.notFound("智能体不存在");
        }

        AgentRunRequest existingRequest = requestRepository.getByRequestId(requestInput.requestId());
        AgentRun existingRun =
                existingRequest != null ? null : runRepository.getRunByRequestId(requestInput.requestId());
        if (existingRun != null && existingRequest == null) {
            if (!uid.equals(existingRun.getUid())) {
                throw ApiHttpException.conflict("request_id 冲突");
            }
            if (!agentItem.getSlug().equals(existingRun.getAgentSlug()) || !"chat".equals(existingRun.getRunType())) {
                throw ApiHttpException.conflict("request_id 冲突");
            }
            if (requestInput.threadId() != null
                    && !requestInput.threadId().isEmpty()
                    && !requestInput.threadId().equals(existingRun.getConversationThreadId())) {
                throw ApiHttpException.conflict("request_id 冲突");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("request_id", requestInput.requestId());
            data.put("status", existingRun.getStatus());
            data.put("queue_policy", requestInput.queuePolicy());
            data.put("queue_position", 0);
            data.put("message_id", existingRun.getInputMessageId());
            data.put("run_id", existingRun.getId());
            data.put("stream_url", "/api/agent/runs/" + existingRun.getId() + "/events");
            data.put("request_events_url", null);
            data.put("thread_id", existingRun.getConversationThreadId());
            return data;
        }
        final String normalizedExternalId = externalId;
        if (existingRequest != null) {
            AgentRequestInput normalizedInput =
                    requestInput.withOrigin(origin.withExternalId(normalizedExternalId));
            validateRequestScope(existingRequest, normalizedInput, uid);
            Conversation conversation =
                    conversationRepository.getConversationByThreadId(existingRequest.getConversationThreadId());
            if (conversation == null
                    || !uid.equals(conversation.getUid())
                    || "deleted".equals(conversation.getStatus())
                    || !String.valueOf(requestInput.agentSlug()).equals(conversation.getAgentId())) {
                throw ApiHttpException.notFound("对话线程不存在");
            }
            Project project = projectRepository.getForUser(String.valueOf(conversation.getProjectId()), uid);
            if (project == null || !"active".equals(project.getStatus())) {
                throw ApiHttpException.notFound("Project 不存在或不可访问");
            }
            return queueService.requestView(requestRepository, existingRequest);
        }

        BaseAgent agentBackend = agentManager.getAgent(agentItem.getBackendId());
        if (agentBackend == null) {
            throw ApiHttpException.notFound("智能体后端 " + agentItem.getBackendId() + " 不存在");
        }

        // 事务内完成：对话可能的创建 + 请求落库 + 事务内派发；提交后再物化与投递
        DispatchHolder holder = new DispatchHolder();
        Map<String, Object> response = transactionTemplate.execute(status -> {
            Conversation conversation = conversationRepository.getConversationByThreadId(requestInput.threadId());
            Project project = null;
            if (conversation == null) {
                if (!requestInput.createConversation()) {
                    throw ApiHttpException.notFound("对话线程不存在");
                }
                Project created = nestedTransactionTemplate.execute(nestedStatus -> {
                    Project locked = null;
                    if (requestInput.conversationProjectId() != null
                            && !requestInput.conversationProjectId().isEmpty()) {
                        locked = projectRepository.lockActiveForUser(requestInput.conversationProjectId(), uid);
                        if (locked == null) {
                            throw ApiHttpException.notFound("Project 不存在或不可访问");
                        }
                    } else {
                        locked = projectService.createImplicitProject(uid, null);
                    }
                    Map<String, Object> metadata = new LinkedHashMap<>(originMetadata);
                    metadata.put("source", origin.source());
                    metadata.put("channel", origin.channel());
                    conversationRepository.addConversation(
                            uid,
                            agentItem.getSlug(),
                            requestInput.conversationTitle(),
                            requestInput.threadId(),
                            metadata,
                            locked == null ? null : String.valueOf(locked.getId()),
                            null);
                    return locked;
                });
                project = created;
                conversation = conversationRepository.getConversationByThreadId(requestInput.threadId());
            }

            Map<String, Object> requestMetadata = new LinkedHashMap<>(
                    requestInput.requestMetadata() == null ? new LinkedHashMap<>() : requestInput.requestMetadata());
            requestMetadata.put("channel", origin.channel());
            for (Map.Entry<String, Object> entry : originMetadata.entrySet()) {
                if (Set.of("source", "channel").contains(entry.getKey())) {
                    continue;
                }
                requestMetadata.putIfAbsent(entry.getKey(), entry.getValue());
            }

            Project bindingProject = project != null
                            && String.valueOf(project.getId()).equals(String.valueOf(conversation.getProjectId()))
                    ? project
                    : null;
            WorkdirService.WorkdirBinding workdirBinding =
                    workdirService.resolveConversationWorkdirBinding(conversation, uid, bindingProject);
            holder.workdirBinding = workdirBinding;

            AgentRequestInput effectiveInput = requestInput.withOrigin(
                    origin.withExternalIdAndMetadata(normalizedExternalId, originMetadata))
                    .withRequestMetadata(requestMetadata);
            PersistOutcome outcome =
                    persistRequest(effectiveInput, currentUser, agentItem, agentBackend, workdirBinding);
            Map<String, Object> view = queueService.requestView(requestRepository, outcome.request());
            holder.dispatch = outcome.dispatch();
            return view;
        });

        if (holder.workdirBinding != null && holder.workdirBinding.materializeManaged()) {
            WorkspacePaths.ensureBoundUserWorkdir(
                    holder.workdirBinding.uid(), holder.workdirBinding.workdirPath());
        }
        if (holder.dispatch != null) {
            agentRunService.enqueueAgentRun(holder.dispatch.runId());
        }
        return response;
    }

    /** 提交边界内的可变结果载体（Java 的 lamdba 需要 effectively-final 的容器）。 */
    private static final class DispatchHolder {
        private AgentRequestQueueService.DispatchResult dispatch;
        private WorkdirService.WorkdirBinding workdirBinding;
    }

    /** {@code _persist_request} 的返回对（请求行 + 本次事务实际派发的队头）。 */
    private record PersistOutcome(
            AgentRunRequest request, AgentRequestQueueService.DispatchResult dispatch) {}

    // =========================================================================
    // === _persist_request
    // =========================================================================

    /** 保存请求并返回本事务实际派发的队头，供提交后投递（对应 {@code _persist_request}）。 */
    private PersistOutcome persistRequest(
            AgentRequestInput requestInput,
            User currentUser,
            Agent agentItem,
            BaseAgent agentBackend,
            WorkdirService.WorkdirBinding initialWorkdirBinding) {
        String requestId = requestInput.requestId();
        String uid = String.valueOf(currentUser.getUid());
        String agentSlug = requestInput.agentSlug();
        String threadId = requestInput.threadId();
        String source = requestInput.origin().source();
        String channel = requestInput.origin().channel();
        String externalId = requestInput.origin().externalId();
        Map<String, Object> originMetadata = requestInput.origin().metadata();
        AgentRunInputMessage inputMessage = requestInput.inputMessage();
        String modelSpec = requestInput.modelSpec();
        String toolApprovalMode = requestInput.toolApprovalMode();
        Map<String, Object> meta = requestInput.requestMetadata();
        String policy = AgentRequestQueueService.validateQueuePolicy(requestInput.queuePolicy());
        if ("steer".equals(policy) && !Set.of("chat", "channel").contains(source)) {
            throw ApiHttpException.unprocessable("queue_policy 'steer' 仅支持主会话 Chat/Channel");
        }
        Map<String, Object> effectiveMeta = meta == null ? new LinkedHashMap<>() : meta;

        WorkdirService.WorkdirBinding[] bindingRef = new WorkdirService.WorkdirBinding[] {initialWorkdirBinding};

        // 幂等：相同 request_id 已存在时返回既有 request（含 Workdir 绑定一致性校验）
        AgentRunRequest existing = findExistingRequest(
                requestId, requestInput, uid, threadId, bindingRef[0]);
        if (existing != null) {
            return new PersistOutcome(existing, null);
        }

        Conversation conversation =
                queueService.getThreadConversation(uid, agentSlug, threadId, true);
        if (bindingRef[0] == null) {
            bindingRef[0] = workdirService.resolveConversationWorkdirBinding(conversation, uid, null);
        } else {
            WorkdirService.WorkdirBinding binding = bindingRef[0];
            if (!uid.equals(binding.uid())
                    || conversation.getId() == null
                    || binding.conversationId() != conversation.getId()
                    || !String.valueOf(binding.threadId()).equals(String.valueOf(conversation.getThreadId()))
                    || !String.valueOf(binding.projectId())
                            .equals(String.valueOf(conversation.getProjectId()))) {
                throw new IllegalStateException("传入的 Workdir 绑定与 Conversation 不一致");
            }
        }
        existing = findExistingRequest(requestId, requestInput, uid, threadId, bindingRef[0]);
        if (existing != null) {
            return new PersistOutcome(existing, null);
        }

        List<AgentRunRequest> existingRequests = requestRepository.listQueued(uid, agentSlug, threadId);
        AgentRunRequest existingHead = existingRequests.isEmpty() ? null : existingRequests.get(0);
        AgentRun activeRun = runRepository.getActiveRunByThreadForUser(agentSlug, threadId, uid);
        AgentRun latestRun = runRepository.getLatestChatOrResumeRun(uid, agentSlug, threadId);
        if (latestRun != null && "interrupted".equals(latestRun.getStatus())) {
            throw AgentRequestQueueService.queueConflict("run_interrupted", "线程正在等待用户回答或审批");
        }
        if ("steer".equals(policy) && activeRun != null && !queueService.isSteerableMessageRun(activeRun)) {
            throw AgentRequestQueueService.queueConflict("run_not_steerable", "当前运行不支持引导");
        }
        if ("steer".equals(policy)
                && requestRepository.getPendingSteer(uid, agentSlug, threadId) != null) {
            throw AgentRequestQueueService.queueConflict(
                    "steer_already_pending", "线程已有等待执行的引导请求");
        }

        // reject 表示"不能立即成为并派发 FIFO 队头就拒绝"。
        boolean rejectWithoutImmediateDispatch =
                "reject".equals(policy) && (activeRun != null || existingHead != null);
        String requestStatus;
        String deliveryStatus;
        Map<String, Object> inputPayload;
        String resolvedModelSpec = null;
        if (rejectWithoutImmediateDispatch) {
            requestStatus = AgentRequestQueueService.REQUEST_STATUS_REJECTED;
            deliveryStatus = AgentRequestQueueService.DELIVERY_STATUS_REJECTED;
            inputPayload = new LinkedHashMap<>();
        } else {
            requestStatus = AgentRequestQueueService.REQUEST_STATUS_QUEUED;
            deliveryStatus = AgentRequestQueueService.DELIVERY_STATUS_QUEUED;
            Map<String, Object> conversationExtra =
                    AgentRunService.parseJsonObject(conversation.getExtraMetadata());
            Object conversationModelSpec = conversationExtra == null ? null : conversationExtra.get("model_spec");
            String requestedModelSpec = modelSpec != null && !modelSpec.strip().isEmpty()
                    ? modelSpec
                    : (conversationModelSpec == null ? null : String.valueOf(conversationModelSpec));
            String[] resolved = agentRunService.resolveAgentRunConfig(
                    requestedModelSpec, toolApprovalMode, agentItem, agentBackend);
            resolvedModelSpec = resolved[0];
            inputPayload = new LinkedHashMap<>();
            inputPayload.put("model_spec", resolved[0]);
            inputPayload.put("tool_approval_mode", resolved[1]);
        }

        AgentRunInputMessage runInputMessage = inputMessage.withMetadata(buildMessageMetadata(
                requestId, source, inputMessage, effectiveMeta));

        AgentRunRequest persistedRequest;
        Message persistedMessage;
        final String finalRequestStatus = requestStatus;
        final String finalDeliveryStatus = deliveryStatus;
        final Map<String, Object> finalInputPayload = inputPayload;
        final boolean finalReject = rejectWithoutImmediateDispatch;
        try {
            Message[] messageRef = new Message[1];
            AgentRunRequest[] requestRef = new AgentRunRequest[1];
            nestedTransactionTemplate.executeWithoutResult(nestedStatus -> {
                List<String> attachmentFileIds = normalizeAttachmentFileIds(effectiveMeta.get("attachment_file_ids"));
                if (!finalReject && !attachmentFileIds.isEmpty()) {
                    List<Map<String, Object>> boundAttachments = conversationRepository.bindAttachmentsToRequest(
                            conversation.getId(), requestId, attachmentFileIds);
                    Set<String> boundIds = new LinkedHashSet<>();
                    for (Map<String, Object> item : boundAttachments) {
                        boundIds.add(String.valueOf(item.get("file_id")));
                    }
                    List<String> missingIds = new ArrayList<>();
                    for (String fileId : attachmentFileIds) {
                        if (!boundIds.contains(fileId)) {
                            missingIds.add(fileId);
                        }
                    }
                    if (!missingIds.isEmpty()) {
                        throw ApiHttpException.unprocessable(
                                "附件不存在、已被使用或已被删除: " + String.join(", ", missingIds));
                    }
                }
                messageRef[0] = agentRunService.createAgentRunInputMessage(
                        conversation.getId(), requestId, runInputMessage, finalDeliveryStatus);
                requestRef[0] = requestRepository.create(
                        requestId,
                        uid,
                        agentSlug,
                        threadId,
                        source,
                        channel,
                        externalId,
                        originMetadata,
                        policy,
                        messageRef[0].getId(),
                        finalInputPayload,
                        finalRequestStatus);
            });
            persistedMessage = messageRef[0];
            persistedRequest = requestRef[0];
        } catch (DuplicateKeyException exception) {
            AgentRunRequest found = findExistingRequest(requestId, requestInput, uid, threadId, bindingRef[0]);
            if (found != null) {
                return new PersistOutcome(found, null);
            }
            throw exception;
        }

        AgentRequestQueueService.DispatchResult dispatched = null;
        if (!rejectWithoutImmediateDispatch) {
            if (!"reject".equals(policy)) {
                conversationRepository.setModelSpec(conversation, resolvedModelSpec);
            }
            dispatched = queueService.dispatchReadyHead(
                    uid,
                    agentSlug,
                    threadId,
                    bindingRef[0],
                    "reject".equals(policy) ? requestId : null);
            if (dispatched != null && requestId.equals(dispatched.requestId())) {
                if ("reject".equals(policy)) {
                    conversationRepository.setModelSpec(conversation, resolvedModelSpec);
                }
                return new PersistOutcome(persistedRequest, dispatched);
            }

            if ("reject".equals(policy)) {
                persistedRequest.setStatus(AgentRequestQueueService.REQUEST_STATUS_REJECTED);
                persistedRequest.setInputPayload("{}");
                persistedRequest.setUpdatedAt(DateTimeUtils.utcNowNaive());
                requestRepository.updateQueueState(persistedRequest);
                persistedMessage.setDeliveryStatus(AgentRequestQueueService.DELIVERY_STATUS_REJECTED);
                messageMapperUpdate(persistedMessage);
            }
        }
        return new PersistOutcome(persistedRequest, dispatched);
    }

    /**
     * 幂等查询：相同 request_id 已存在时返回既有请求，不存在返回 null
     * （对应 {@code _persist_request} 内部的 {@code existing_request} 闭包）。
     */
    private AgentRunRequest findExistingRequest(
            String requestId,
            AgentRequestInput requestInput,
            String uid,
            String threadId,
            WorkdirService.WorkdirBinding binding) {
        if (binding != null
                && (!uid.equals(binding.uid()) || !String.valueOf(threadId).equals(String.valueOf(binding.threadId())))) {
            throw new IllegalStateException("传入的 Workdir 绑定与请求作用域不一致");
        }
        AgentRunRequest existing = requestRepository.getByRequestId(requestId);
        if (existing == null) {
            return null;
        }
        validateRequestScope(existing, requestInput, uid);
        return existing;
    }

    // =========================================================================
    // === 作用域校验与消息元数据
    // =========================================================================

    /** 相同 ID 只允许重放同一不可变请求作用域（对应 {@code _validate_request_scope}）。 */
    private static void validateRequestScope(
            AgentRunRequest request, AgentRequestInput requestInput, String uid) {
        RunOrigin origin = requestInput.origin();
        List<String> expectedScope = List.of(
                String.valueOf(uid),
                String.valueOf(requestInput.agentSlug()),
                String.valueOf(requestInput.threadId()),
                String.valueOf(origin.source()),
                String.valueOf(origin.channel()),
                String.valueOf(origin.externalId()));
        List<String> actualScope = List.of(
                String.valueOf(request.getUid()),
                String.valueOf(request.getAgentSlug()),
                String.valueOf(request.getConversationThreadId()),
                String.valueOf(request.getSource()),
                String.valueOf(request.getChannel()),
                String.valueOf(request.getExternalId()));
        if (!actualScope.equals(expectedScope)) {
            throw AgentRequestQueueService.queueConflict(
                    "request_id_conflict", "request_id 已用于其他请求作用域");
        }
    }

    /**
     * 构建 Message.extra_metadata：request_id + source + raw_message + 附加上下文
     * （对应 {@code _build_message_metadata}）。
     */
    private static Map<String, Object> buildMessageMetadata(
            String requestId, String source, AgentRunInputMessage inputMessage, Map<String, Object> meta) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("request_id", requestId);
        if (source != null && !source.isEmpty()) {
            metadata.put("source", source);
        }
        Object channel = meta.get("channel");
        if (channel != null && !"".equals(String.valueOf(channel)) && !Boolean.FALSE.equals(channel)) {
            metadata.put("channel", channel);
        }
        Map<String, Object> rawMessage = inputMessage.rawMessage();
        if (rawMessage != null && !rawMessage.isEmpty()) {
            metadata.put("raw_message", rawMessage);
        }
        Object attachmentFileIds = meta.get("attachment_file_ids");
        if (attachmentFileIds != null && !"".equals(String.valueOf(attachmentFileIds))
                && !Boolean.FALSE.equals(attachmentFileIds)) {
            metadata.put("attachment_file_ids", attachmentFileIds);
        }
        if (meta.get("agent_invocation_meta") instanceof Map<?, ?>) {
            metadata.put("agent_invocation_meta", meta.get("agent_invocation_meta"));
        }
        if (meta.get("tool_approval_mode") != null) {
            metadata.put("tool_approval_mode", meta.get("tool_approval_mode"));
        }
        return metadata;
    }

    /** 规范化请求附件 ID，保持原始顺序并去重（对应 {@code _normalize_attachment_file_ids}）。 */
    private static List<String> normalizeAttachmentFileIds(Object value) {
        List<String> normalized = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            return normalized;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object fileId : list) {
            String current = String.valueOf(fileId).strip();
            if (!current.isEmpty() && seen.add(current)) {
                normalized.add(current);
            }
        }
        return normalized;
    }

    /** 消息投递状态回写（参考实现改 ORM 属性 + {@code db.flush()}）。 */
    private void messageMapperUpdate(Message message) {
        messageMapper.updateById(message);
    }
}
