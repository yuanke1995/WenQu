package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.agents.ToolApproval;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.HashUtils;
import com.wisesoft.wenqu.common.LooseJson;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Project;
import com.wisesoft.wenqu.models.SubagentThread;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.repositories.ProjectRepository;
import com.wisesoft.wenqu.repositories.SubagentThreadRepository;
import com.wisesoft.wenqu.service.InputMessageService.AgentRunInputMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 子智能体 run 编排服务。
 *
 * <p>本模块拥有父子智能体线程的归属关系：判定一次任务应新建子线程还是继续已有子线程、
 * 记录 {@code SubagentThread} 关系并装配仅面向子智能体运行时的 payload。
 *
 * <p>持久化 run 的机制刻意委托给 {@link AgentRunService}：request_id 幂等、活动 run 冲突检查、
 * 输入消息落库、AgentRun 行创建与入队都留在统一的 AgentRun 生命周期边界内。
 *
 * <p>由参考实现的 services/subagent_run_service.py 逐函数翻译：{@code SubagentStartResult} /
 * {@code SubagentRunBusy} / {@code subagent_run_urls} / {@code serialize_subagent_run_state} /
 * {@code start} / {@code get_run_for_creator} / {@code _create_run_record} /
 * {@code _ensure_child_conversation} / {@code _validate_thread_relation} /
 * {@code _ensure_thread_relation}。文案、错误码、payload 键与顺序均逐字对齐。
 *
 * <h3>平台差异（显式标注，非遗漏）</h3>
 * <ul>
 *   <li>{@code @dataclass(frozen=True) class SubagentRunBusy(Exception)} → 内部类
 *       {@link SubagentRunBusy}（继承 {@link RuntimeException}）。参考实现的 {@code message} 字段
 *       由 {@link Throwable#getMessage()} 承载——Java 不允许覆盖该 final 方法，故不再单列字段；
 *       {@link SubagentRunBusy#toPayload()} 的键与取值逐字对齐（**空值也保留**）。</li>
 *   <li>{@code raise ValueError(...)} → {@link IllegalArgumentException}（同属"调用方输入不合法"，
 *       参考路由/中间件按异常类型分流，此处保持同名语义的等价类型）。</li>
 *   <li>{@code hash_id("req:", …)} / {@code subagent_child_thread_id(...)} →
 *       {@link HashUtils#hashId} / {@link HashUtils#subagentChildThreadId}。</li>
 *   <li>{@code json.dumps(detail, ensure_ascii=False)}（不排序键）→ {@code JSON.toJSONString(detail)}
 *       （fastjson2 同样保持插入顺序且不转义非 ASCII）；**不能用** {@code CanonicalJson.dumps}
 *       ——那会按 sort_keys 排序，改变错误文案。</li>
 *   <li>{@code conversation.status = "subagent"} + {@code db.flush()} →
 *       {@link ConversationRepository#updateConversation}（参考实现该列声明 {@code onupdate=utc_now_naive}，
 *       故等价更新会同步推进 {@code updated_at}）。</li>
 *   <li>{@code self.db.commit()} → 自管事务模板（{@code PROPAGATION_REQUIRES_NEW}）在 DB 阶段结束时提交；
 *       {@code enqueue_agent_run} 刻意留在事务之外，与参考实现"先 commit 再入队"的顺序一致。</li>
 * </ul>
 */
@Service
public class SubagentRunService {

    /** 启动或继续子智能体 run 的结果（对应 {@code SubagentStartResult}）。 */
    public record SubagentStartResult(AgentRun run, boolean created, boolean continuing, SubagentThread relation) {}

    /**
     * 子智能体线程被占用（对应 {@code SubagentRunBusy}）。
     *
     * <p>{@code message} 由 {@link #getMessage()} 承载（Java 的 {@code Throwable#getMessage()} 是 final）。
     */
    public static class SubagentRunBusy extends RuntimeException {

        private final String threadId;
        private final String activeRunId;
        private final String activeRunStatus;

        public SubagentRunBusy(String threadId, String activeRunId, String activeRunStatus, String message) {
            super(message);
            this.threadId = threadId;
            this.activeRunId = activeRunId;
            this.activeRunStatus = activeRunStatus;
        }

        public String getThreadId() {
            return threadId;
        }

        public String getActiveRunId() {
            return activeRunId;
        }

        public String getActiveRunStatus() {
            return activeRunStatus;
        }

        /** 对应 {@code to_payload()}：五个键恒定输出，空值不省略。 */
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "busy");
            payload.put("thread_id", threadId);
            payload.put("active_run_id", activeRunId);
            payload.put("active_run_status", activeRunStatus);
            payload.put("message", getMessage());
            return payload;
        }
    }

    private final AgentRunService agentRunService;
    private final AgentRunRepository runRepository;
    private final ConversationRepository conversationRepository;
    private final ProjectRepository projectRepository;
    private final SubagentThreadRepository subagentThreadRepository;
    private final TransactionTemplate dbTransactionTemplate;

    public SubagentRunService(
            AgentRunService agentRunService,
            AgentRunRepository runRepository,
            ConversationRepository conversationRepository,
            ProjectRepository projectRepository,
            SubagentThreadRepository subagentThreadRepository,
            PlatformTransactionManager transactionManager) {
        this.agentRunService = agentRunService;
        this.runRepository = runRepository;
        this.conversationRepository = conversationRepository;
        this.projectRepository = projectRepository;
        this.subagentThreadRepository = subagentThreadRepository;
        this.dbTransactionTemplate = new TransactionTemplate(transactionManager);
        this.dbTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 生成子智能体 run 对外暴露的事件流和结果查询 URL（对应 {@code subagent_run_urls}）。 */
    public static Map<String, String> subagentRunUrls(String runId) {
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("events_url", "/api/agent/runs/" + runId + "/events");
        urls.put("result_url", "/api/agent/runs/" + runId + "/result");
        return urls;
    }

    /**
     * 序列化给父智能体状态使用的子智能体 run 摘要（对应 {@code serialize_subagent_run_state}）。
     *
     * <p>任务描述不在此冗余存储：其唯一来源是父对话里 {@code task} 工具调用的入参，
     * 前端面板按 tool_call_id 回填展示。
     *
     * @throws IllegalArgumentException 缺少 input_payload / runtime / tool_call_id
     */
    public static Map<String, Object> serializeSubagentRunState(AgentRun run) {
        Object payload = parseJsonColumn(run.getInputPayload());
        if (!(payload instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("subagent run 缺少 input_payload");
        }
        Object runtime = ((Map<?, ?>) payload).get("runtime");
        if (!(runtime instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("subagent run 缺少 runtime");
        }
        Map<?, ?> runtimeMap = (Map<?, ?>) runtime;
        Object rawToolCallId = runtimeMap.get("tool_call_id");
        String toolCallId = AgentRunService.truthy(rawToolCallId) ? String.valueOf(rawToolCallId).strip() : "";
        if (toolCallId.isEmpty()) {
            throw new IllegalArgumentException("subagent run 缺少 tool_call_id");
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", toolCallId);
        state.put("run_id", run.getId());
        state.put("subagent_slug", run.getAgentSlug());
        state.put("subagent_name", runtimeMap.get("subagent_name"));
        state.put("child_thread_id", run.getConversationThreadId());
        state.put("status", run.getStatus());
        state.put("created_at", DateTimeUtils.formatUtcDatetime(run.getCreatedAt()));
        state.put("completed_at", DateTimeUtils.formatUtcDatetime(run.getFinishedAt()));
        state.put("error", run.getErrorMessage());
        state.putAll(subagentRunUrls(run.getId()));
        state.entrySet().removeIf(entry -> entry.getValue() == null);
        return state;
    }

    // =========================================================================
    // === start / get_run_for_creator
    // =========================================================================

    /**
     * 启动或继续一个后台子智能体 run，并在新建时入队 worker（对应 {@code start}）。
     */
    public SubagentStartResult start(
            String uid,
            String createdByRunId,
            Agent item,
            AgentRunInputMessage inputMessage,
            String toolCallId,
            String requestedThreadId) {
        String childThreadId = requestedThreadId == null ? "" : requestedThreadId.strip();
        boolean continuing = !childThreadId.isEmpty();

        // 阶段一：校验 + 关系落库 + run 记录落库（同一事务，与参考实现的单个 session 等价）
        RunCreation creation = dbTransactionTemplate.execute(status -> {
            AgentRun creatorRun = runRepository.lockRunForUser(createdByRunId, uid);
            if (creatorRun == null) {
                throw new IllegalArgumentException("父运行任务不存在");
            }
            String creatorStatus = creatorRun.getStatus() == null ? "running" : creatorRun.getStatus();
            if (!"running".equals(creatorStatus)) {
                throw new IllegalArgumentException("父运行已结束，不能再创建子智能体");
            }
            if ("subagent".equals(creatorRun.getRunType())) {
                throw new IllegalArgumentException("子智能体不能创建子智能体");
            }

            String resolvedChildThreadId = childThreadId;
            if (resolvedChildThreadId.isEmpty()) {
                resolvedChildThreadId = HashUtils.subagentChildThreadId(
                        creatorRun.getConversationThreadId(), item.getSlug(), toolCallId);
            }

            // 1. 确保子线程有对应 conversation，必要时创建 subagent 对话
            // 2. 确保父子线程关系存在，必要时创建 SubagentThread 记录；relation 是后台子 run 的线程归属来源
            SubagentThread relation = ensureThreadRelation(
                    resolvedChildThreadId, uid, item, creatorRun, continuing);

            // 参考实现的 hash_id 默认 length=48（不是 64），键形状为 "req:" + sha256(value)[:44]
            String requestId =
                    HashUtils.hashId("req:", creatorRun.getId() + ":" + resolvedChildThreadId + ":" + toolCallId, 48);
            AgentRunRepository.RunResult record;
            try {
                record = createRunRecord(
                        inputMessage, requestId, uid, creatorRun, relation, toolCallId);
            } catch (ApiHttpException error) {
                throw translateCreationError(error, resolvedChildThreadId);
            }
            return new RunCreation(record.run(), record.changed(), relation);
        });

        // 阶段二：创建成功后入队 worker 执行；幂等命中已有 run 时不重复入队。
        if (creation.created()) {
            agentRunService.enqueueAgentRun(creation.run().getId());
        }

        return new SubagentStartResult(creation.run(), creation.created(), continuing, creation.relation());
    }

    private record RunCreation(AgentRun run, boolean created, SubagentThread relation) {}

    /**
     * 在父 run 作用域内读取子智能体 run，防止工具访问其它对话的子任务
     * （对应 {@code get_run_for_creator}）。
     */
    public AgentRun getRunForCreator(String uid, String createdByRunId, String runId) {
        AgentRunRepository.SubagentRunPair pair =
                runRepository.getSubagentRunWithCreator(uid, createdByRunId, runId);
        if (pair == null) {
            throw new IllegalArgumentException("子智能体运行不存在或不属于当前父运行");
        }
        return pair.run();
    }

    /**
     * 把 {@code run_busy} 型 409 翻译成 {@link SubagentRunBusy}，其余 4xx 翻译成
     * {@link IllegalArgumentException}（对应 {@code start} 里的 except 分支）。
     */
    private static RuntimeException translateCreationError(ApiHttpException error, String childThreadId) {
        Object detail = error.getDetailObject();
        if (error.getStatus() == 409 && detail instanceof Map<?, ?> detailMap
                && "run_busy".equals(detailMap.get("code"))) {
            Object rawThreadId = detailMap.get("thread_id");
            return new SubagentRunBusy(
                    AgentRunService.truthy(rawThreadId) ? String.valueOf(rawThreadId) : childThreadId,
                    nullableString(detailMap.get("active_run_id")),
                    nullableString(detailMap.get("active_run_status")),
                    nullableString(detailMap.get("message")));
        }
        String text = error.getMessage();
        return new IllegalArgumentException(text != null ? text : JSON.toJSONString(detail));
    }

    /** 取可空字符串（对应 {@code detail.get(k)}：缺失即 None，不做字符串化）。 */
    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // =========================================================================
    // === _create_run_record
    // =========================================================================

    /**
     * 创建后台子智能体 run，并把规范化输入消息保存为该 run 的输入
     * （对应 {@code _create_run_record}）。
     */
    private AgentRunRepository.RunResult createRunRecord(
            AgentRunInputMessage inputMessage,
            String requestId,
            String currentUid,
            AgentRun creatorRun,
            SubagentThread relation,
            String toolCallId) {
        if (inputMessage.content() == null || inputMessage.content().isEmpty()) {
            throw ApiHttpException.unprocessable("input_message 不能为空");
        }

        AgentRunService.AgentRunCreationScope scope = agentRunService.prepareAgentRunCreationScope(
                relation.getSubagentSlug(),
                relation.getChildThreadId(),
                currentUid,
                requestId,
                "subagent",
                "subagent",
                creatorRun.getId(),
                relation.getId());
        if (!relation.getChildConversationId().equals(scope.conversation().getId())) {
            throw ApiHttpException.conflict("subagent thread relation 与本次运行不匹配");
        }
        if (scope.existingRun() != null) {
            return new AgentRunRepository.RunResult(scope.existingRun(), false);
        }

        if (!creatorRun.getConversationId().equals(relation.getParentConversationId())) {
            throw ApiHttpException.conflict("subagent thread relation 与本次运行不匹配");
        }

        Map<String, Object> creatorPayload = parseJsonObjectColumn(creatorRun.getInputPayload());
        com.wisesoft.wenqu.agents.BaseContext context =
                agentRunService.loadAgentRunContext(scope.agentItem(), scope.agentBackend());
        String resolvedModelSpec = agentRunService.resolveAgentRunModelSpec(
                context == null ? null : context.getString("model"),
                asString(creatorPayload.get("model_spec")));

        Map<String, Object> runtimePayload = new LinkedHashMap<>();
        runtimePayload.put("tool_call_id", toolCallId);
        runtimePayload.put("subagent_name", scope.agentItem().getName());
        runtimePayload.put("parent_thread_id", creatorRun.getConversationThreadId());
        runtimePayload.entrySet().removeIf(entry -> entry.getValue() == null);

        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("model_spec", resolvedModelSpec);
        Object approvalMode = creatorPayload.containsKey("tool_approval_mode")
                ? creatorPayload.get("tool_approval_mode")
                : ToolApproval.DEFAULT_TOOL_APPROVAL_MODE;
        inputPayload.put("tool_approval_mode", approvalMode);
        inputPayload.put("runtime", runtimePayload);

        Map<String, Object> messageMetadata = new LinkedHashMap<>();
        messageMetadata.put("request_id", requestId);
        messageMetadata.put("source", "subagent");
        messageMetadata.put("raw_message", inputMessage.rawMessage());
        AgentRunInputMessage subagentInputMessage = inputMessage.withMetadata(messageMetadata);

        com.wisesoft.wenqu.models.Message persistedInputMessage = agentRunService.createAgentRunInputMessage(
                scope.conversation().getId(), requestId, subagentInputMessage, null);

        String runtimeScopeId = creatorRun.getRuntimeScopeId() == null || creatorRun.getRuntimeScopeId().isEmpty()
                ? creatorRun.getConversationThreadId()
                : creatorRun.getRuntimeScopeId();
        return agentRunService.persistAgentRunRecord(
                relation.getSubagentSlug(),
                relation.getChildThreadId(),
                runtimeScopeId,
                currentUid,
                requestId,
                scope.conversation().getId(),
                "subagent",
                inputPayload,
                persistedInputMessage,
                creatorRun.getId(),
                relation.getId(),
                "subagent",
                "internal",
                null,
                null);
    }

    // =========================================================================
    // === 子对话与线程关系
    // =========================================================================

    /**
     * 确保子线程有对应 conversation；新线程会创建标记为 subagent 的对话
     * （对应 {@code _ensure_child_conversation}）。
     */
    private Conversation ensureChildConversation(
            String childThreadId, String uid, Agent item, AgentRun creatorRun, String parentProjectId) {
        Conversation conversation = conversationRepository.getConversationByThreadId(childThreadId);
        if (conversation != null) {
            if (!String.valueOf(uid).equals(conversation.getUid()) || "deleted".equals(conversation.getStatus())) {
                throw new IllegalArgumentException("子智能体线程不存在");
            }
            if (!"subagent".equals(conversation.getStatus())) {
                throw new IllegalArgumentException("子智能体线程 " + childThreadId + " 已被普通对话占用");
            }
            if (!item.getSlug().equals(conversation.getAgentId())) {
                throw new IllegalArgumentException(
                        "子智能体线程 " + childThreadId + " 属于智能体 " + conversation.getAgentId());
            }
            if (!parentProjectId.equals(conversation.getProjectId())) {
                throw new IllegalArgumentException("子智能体线程与父对话的 Workdir 不一致");
            }
            return conversation;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "subagent");
        metadata.put("parent_thread_id", creatorRun.getConversationThreadId());
        metadata.put("created_by_run_id", creatorRun.getId());
        metadata.put("parent_conversation_id", creatorRun.getConversationId());
        metadata.put("subagent_slug", item.getSlug());
        Conversation created = conversationRepository.addConversation(
                uid,
                item.getSlug(),
                "SubAgent: " + item.getName(),
                childThreadId,
                metadata,
                parentProjectId,
                null);
        conversationRepository.updateConversation(childThreadId, null, "subagent", null, null);
        created.setStatus("subagent");
        return created;
    }

    /** 校验已有子线程关系仍属于当前父对话和子智能体（对应 {@code _validate_thread_relation}）。 */
    private static void validateThreadRelation(
            SubagentThread relation, String childThreadId, Agent item, AgentRun creatorRun) {
        if (!creatorRun.getConversationId().equals(relation.getParentConversationId())) {
            throw new IllegalArgumentException("子智能体线程 " + childThreadId + "：线程不属于当前对话");
        }
        if (!item.getSlug().equals(relation.getSubagentSlug())) {
            String owner = relation.getSubagentSlug() == null || relation.getSubagentSlug().isEmpty()
                    ? "未知"
                    : relation.getSubagentSlug();
            throw new IllegalArgumentException("子智能体线程 " + childThreadId + " 属于子智能体 " + owner);
        }
    }

    /**
     * 读取或创建父子线程关系；relation 是后台子 run 的线程归属来源
     * （对应 {@code _ensure_thread_relation}）。
     */
    private SubagentThread ensureThreadRelation(
            String childThreadId, String uid, Agent item, AgentRun creatorRun, boolean continuing) {
        if (creatorRun.getConversationId() == null) {
            throw new IllegalArgumentException("父运行任务缺少 conversation_id，无法创建子智能体线程关系");
        }
        Conversation parentConversation =
                conversationRepository.getConversationById(creatorRun.getConversationId());
        if (parentConversation == null || !String.valueOf(uid).equals(parentConversation.getUid())) {
            throw new IllegalArgumentException("父运行任务的 Conversation 不存在");
        }
        Project parentProject =
                projectRepository.lockActiveForUser(parentConversation.getProjectId(), String.valueOf(uid));
        if (parentProject == null) {
            throw new IllegalArgumentException("父运行任务的 Project 不存在");
        }
        parentConversation = conversationRepository.lockConversationByThreadId(creatorRun.getConversationThreadId());
        if (parentConversation == null
                || !parentConversation.getId().equals(creatorRun.getConversationId())
                || !String.valueOf(uid).equals(parentConversation.getUid())
                || "deleted".equals(parentConversation.getStatus())
                || !parentProject.getId().equals(parentConversation.getProjectId())) {
            throw new IllegalArgumentException("父运行任务的 Conversation 不存在");
        }
        String parentProjectId = parentProject.getId();

        SubagentThread existing = subagentThreadRepository.getByChildThreadForUser(childThreadId, uid);
        if (existing != null) {
            validateThreadRelation(existing, childThreadId, item, creatorRun);
            Conversation childConversation =
                    conversationRepository.getConversationById(existing.getChildConversationId());
            if (childConversation == null
                    || !String.valueOf(uid).equals(childConversation.getUid())
                    || "deleted".equals(childConversation.getStatus())) {
                throw new IllegalArgumentException("子智能体线程不存在");
            }
            if (!parentProjectId.equals(childConversation.getProjectId())) {
                throw new IllegalArgumentException("子智能体线程与父对话的 Workdir 不一致");
            }
            return existing;
        }
        if (continuing) {
            throw new IllegalArgumentException(
                    "无法继续子智能体线程 " + childThreadId + "：当前对话中没有找到对应的运行记录");
        }
        Conversation childConversation =
                ensureChildConversation(childThreadId, uid, item, creatorRun, parentProjectId);
        return subagentThreadRepository.create(
                uid,
                creatorRun.getConversationId(),
                childConversation.getId(),
                childThreadId,
                item.getSlug(),
                creatorRun.getId());
    }

    // =========================================================================
    // === JSON 辅助
    // =========================================================================

    /** 解析 JSON 文本列；非 JSON 或解析失败返回 null（对应"不是 dict 就报错"的前半段判定）。 */
    private static Object parseJsonColumn(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LooseJson.parse(raw);
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 解析 JSON 对象列；空/非法/非对象一律返回空 Map（与参考实现的 {@code dict(x or {})} 一致）。 */
    private static Map<String, Object> parseJsonObjectColumn(String raw) {
        Object parsed = parseJsonColumn(raw);
        if (!(parsed instanceof Map<?, ?> parsedMap)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        parsedMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /** 取字符串值（对应 Python 里直接透传的 {@code model_spec}）。 */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
