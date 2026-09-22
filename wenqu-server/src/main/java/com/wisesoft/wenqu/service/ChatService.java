package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wisesoft.wenqu.agents.AIMessage;
import com.wisesoft.wenqu.agents.AgentManager;
import com.wisesoft.wenqu.agents.AgentState;
import com.wisesoft.wenqu.agents.BackendPaths;
import com.wisesoft.wenqu.agents.BaseAgent;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.agents.GraphStateSnapshot;
import com.wisesoft.wenqu.agents.HumanMessage;
import com.wisesoft.wenqu.agents.ModelDumpable;
import com.wisesoft.wenqu.agents.engine.GraphCodec;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.JsonValues;
import com.wisesoft.wenqu.common.QuestionUtils;
import com.wisesoft.wenqu.common.ThreadUtils;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.ModelConstants;
import com.wisesoft.wenqu.models.SubagentThread;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.repositories.ModelMessageAuditRepository;
import com.wisesoft.wenqu.repositories.SubagentThreadRepository;
import com.wisesoft.wenqu.repositories.ToolMessageAuditRepository;
import com.wisesoft.wenqu.service.AgentRunManifestService.PreparedRunExecution;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agent 运行时流式服务（对应参考实现的 {@code services/chat_service.py}）。
 *
 * <p>本模块是 {@code AgentRun} 创建之后 worker 走的执行路径：恢复输入消息、构造运行上下文、
 * 流式产出模型/工具事件、持久化 assistant 输出、抽取面向前端的 agent state。
 *
 * <p>运行创建、请求幂等、排队与对外调用响应格式化<b>不在</b>本类（分别归
 * {@code AgentRunService} 与 Invocation HTTP 适配层）；这样普通 chat、resume 与 subagent
 * 运行在抵达 worker 后共享同一套运行时行为。
 *
 * <h3>必要替换（平台差异，显式标注）</h3>
 * <ol>
 *   <li><b>async generator → 回调 sink</b>：参考实现的 {@code stream_agent_chat} /
 *       {@code stream_agent_resume} 是异步生成器（逐块 yield）；Java 侧收敛为
 *       {@code Consumer<String> sink}，由调用方线程顺序消费（与
 *       {@code AgentRequestQueueService#streamRequestEvents} 同一口径）。
 *       sink 抛出 {@link UncheckedIOException} 时等价于参考实现的
 *       {@code ConnectionError}（客户端断开）。</li>
 *   <li><b>{@code asyncio.CancelledError}</b> 无 Java 对位（Java 无协程取消传播）：
 *       参考实现在该分支发 {@code interrupted="对话已中断"}；本工程仅保留
 *       {@code ConnectionError} 分支，任务取消由线程中断承担（能力差异，非遗漏）。</li>
 *   <li><b>会话/事务</b>：参考实现用显式 DB 会话（{@code db.commit()} / {@code db.rollback()}
 *       / 异常路径另开新会话）。本工程：
 *       <ul>
 *         <li>流执行期间"不持有事务"由本类方法级别的 {@link TransactionTemplate} 天然保证
 *             （参考实现是显式 {@code await db.commit()} 结束预处理事务）；</li>
 *         <li>{@code save_partial_message} / {@code save_messages_from_langgraph_state} 的
 *             "原子写入 + 失败回滚 + 可吞异常"语义用 {@link TransactionTemplate} 承载：
 *             回调内抛错自动回滚，再由外层 catch 决定重抛（{@code interrupt_run}）或吞掉
 *             （返回 {@code null}）——与参考实现逐分支一致。</li>
 *       </ul></li>
 *   <li><b>{@code model_request_recorder} 参数已并入中间件</b>：参考实现在这里
 *       "收集 {@code FirstModelRequestRecorder} → 终态前调用其 {@code persist}"。本工程的
 *       对应物是 {@code agents.callbacks.ModelRequestTimingMiddleware}（{@code ModelInterceptor}），
 *       它在首次模型调用时原子完成"记录 + 持久化"，故本类<b>不再</b>持有该参数，
 *       也没有 {@code _persist_model_request_timing} 的同名调用点（能力差异，非遗漏）。</li>
 *   <li><b>checkpoint 读取入口</b>：参考实现的 {@code _read_checkpoint_state} 直接取进程级
 *       checkpointer（{@code pg_manager.get_langgraph_checkpointer()}）的 {@code aget_tuple}，
 *       <b>不经构图</b>。本工程对应 {@link BaseAgent#readCheckpointSnapshot}：取 saver 的
 *       {@code get(config)}（引擎 {@code CompiledGraph.getState} 在无 checkpoint 时会抛
 *       {@code Missing Checkpoint!}，与参考实现的 {@code saved is None} 分支不同型，故不走它），
 *       无 checkpoint 时返回空 values + 无中断。
 *       <p><b>未接线（非本路径缺陷）</b>：{@code BaseAgent.checkpointerProvider} 当前未装配，
 *       引擎缺省 saver 是"每次构图新建"的 {@code MemorySaver}（进程内无共享存储），
 *       因此该读取实际恒为空快照 —— 待装配进程级（持久化）checkpointer 后自动生效。</li>
 *   <li><b>中断信息取值面</b>：参考实现从 {@code saved.pending_writes} 里找
 *       {@code __interrupt__} channel；本工程 {@link GraphStateSnapshot#extractInterruptInfo()}
 *       先看 {@code tasks} 再回落 {@code values["__interrupt__"]}（引擎 {@code tasks} 恒空，
 *       故实际等价于该回落分支）。该差异已在 {@code GraphPort} 侧标注。</li>
 * </ol>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 参考实现 {@code _normalize_agent_artifact_path} 里的历史根前缀（字面值，勿改）。 */
    private static final String LEGACY_ARTIFACT_ROOT = "/home/gem/user-data";

    /** 参考实现 {@code _normalize_agent_artifact_path} 遍历的命名空间（顺序照搬）。 */
    private static final List<String> LEGACY_ARTIFACT_NAMESPACES = List.of("uploads", "outputs");

    private final AgentRunRepository agentRunRepository;
    private final ConversationRepository conversationRepository;
    private final AgentRepository agentRepository;
    private final AgentManager agentManager;
    private final AgentRunService agentRunService;
    private final WorkdirService workdirService;
    private final LangfuseService langfuseService;
    private final RunQueueService runQueueService;
    private final SubagentThreadRepository subagentThreadRepository;
    private final ModelMessageAuditRepository modelMessageAuditRepository;
    private final ToolMessageAuditRepository toolMessageAuditRepository;
    private final TransactionTemplate transactionTemplate;

    public ChatService(
            AgentRunRepository agentRunRepository,
            ConversationRepository conversationRepository,
            AgentRepository agentRepository,
            AgentManager agentManager,
            AgentRunService agentRunService,
            WorkdirService workdirService,
            LangfuseService langfuseService,
            RunQueueService runQueueService,
            SubagentThreadRepository subagentThreadRepository,
            ModelMessageAuditRepository modelMessageAuditRepository,
            ToolMessageAuditRepository toolMessageAuditRepository,
            PlatformTransactionManager transactionManager) {
        this.agentRunRepository = agentRunRepository;
        this.conversationRepository = conversationRepository;
        this.agentRepository = agentRepository;
        this.agentManager = agentManager;
        this.agentRunService = agentRunService;
        this.workdirService = workdirService;
        this.langfuseService = langfuseService;
        this.runQueueService = runQueueService;
        this.subagentThreadRepository = subagentThreadRepository;
        this.modelMessageAuditRepository = modelMessageAuditRepository;
        this.toolMessageAuditRepository = toolMessageAuditRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    // =========================================================================
    // === 载荷构造（对应模块级 chunk 工厂与协议事件转换） ===
    // =========================================================================

    /** 对应参考实现的 {@code make_chunk(content=None, **kwargs)}（两个流各有一份同形实现）。 */
    @FunctionalInterface
    public interface ChunkFactory {

        /**
         * @param content 对应 {@code content}（写入 chunk 的 {@code response} 字段）
         * @param kwargs  其余命名参数；其中 {@code thread_id} 会被优先用作 chunk 的 thread_id
         */
        String make(Object content, Map<String, Object> kwargs);
    }

    /**
     * 构造与参考实现同形的 chunk 工厂：{@code {request_id, response, thread_id, **kwargs}}，
     * 其中 {@code thread_id} 取 {@code kwargs.thread_id or meta.thread_id or thread_id}（{@code or} 真值语义）。
     */
    private static ChunkFactory chunkFactory(Map<String, Object> meta, String threadId) {
        return (content, kwargs) -> {
            Map<String, Object> extra = kwargs == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(kwargs);
            Object explicitThreadId = extra.remove("thread_id");
            Object chunkThreadId = JsonValues.or(explicitThreadId, JsonValues.or(meta.get("thread_id"), threadId));
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("request_id", meta.get("request_id"));
            payload.put("response", content);
            payload.put("thread_id", chunkThreadId);
            payload.putAll(extra);
            return JSON.toJSONString(payload) + "\n";
        };
    }

    /** 便捷构造 kwargs（保持与参考实现同名关键字一致）。 */
    private static Map<String, Object> kwargs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            map.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return map;
    }

    /**
     * 把来源 Map 复制为 {@code Map<String, Object>}。
     *
     * <p>存在理由：Java 的 {@code new LinkedHashMap<String, Object>(src)} 拷贝构造器要求
     * {@code Map<? extends String, ? extends Object>}，**不接受通配类型** {@code Map<?, ?>}
     * ——而本类大量分支先做 {@code x instanceof Map<?, ?> m} 模式匹配再复制（对应 Python 的
     * {@code dict(x)}，Python 侧无此限制）。故统一走本方法，语义与 {@code dict(x)} 等价：
     * 键经 {@link java.util.Objects#toString(Object, String)} 归一为字符串，值原样保留。
     *
     * @param source 来源 Map，可为 {@code null}（返回空 Map，与 {@code dict(None)} 的分支判空一致）
     */
    static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (source != null) {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                copy.put(Objects.toString(entry.getKey(), null), entry.getValue());
            }
        }
        return copy;
    }

    // =========================================================================
    // === 输入与上下文装配 ===
    // =========================================================================

    /**
     * 把线程附件路径追加到本轮模型输入，不污染持久化用户消息
     * （对应 {@code _with_attachment_context}）。
     */
    static HumanMessage withAttachmentContext(HumanMessage message, List<Map<String, Object>> attachments) {
        List<String> attachmentLines = new ArrayList<>();
        for (Map<String, Object> item : attachments == null ? List.<Map<String, Object>>of() : attachments) {
            Object path = item.get("path");
            if (path instanceof String text && !text.trim().isEmpty()) {
                Object fileName = item.get("file_name");
                attachmentLines.add("- " + JsonValues.or(fileName, "未知文件") + ": " + text);
            }
        }
        if (attachmentLines.isEmpty()) {
            return message;
        }

        List<String> contextParts = new ArrayList<>();
        contextParts.add("<attachment_context>");
        contextParts.add("以下是本线程当前可用的历史附件。需要内容时，请使用 read_file 读取对应路径：");
        contextParts.addAll(attachmentLines);
        contextParts.add("</attachment_context>");
        String context = String.join("\n", contextParts);

        Object content;
        if (message.getContent() instanceof String text) {
            content = text + "\n\n" + context;
        } else {
            List<Object> blocks = new ArrayList<>();
            Object existing = message.getContent();
            if (existing instanceof List<?> list) {
                blocks.addAll(list);
            }
            blocks.add(kwargs("type", "text", "text", context));
            content = blocks;
        }
        return HumanMessage.of(content);
    }

    /**
     * 构造 Langfuse 运行上下文；请求来自智能体评测时追加评测 metadata 与 tags
     * （对应 {@code _build_langfuse_run_context}）。
     */
    private LangfuseService.LangfuseRunContext buildLangfuseRunContext(
            User currentUser,
            String threadId,
            String agentId,
            String requestId,
            String operation,
            String backendId,
            String messageType,
            Map<String, Object> meta) {
        Map<String, Object> extraMetadata = null;
        List<String> extraTags = null;
        Object invocationMeta = meta == null ? null : meta.get("agent_invocation_meta");
        Object evaluation = invocationMeta instanceof Map<?, ?> invocationMap
                ? invocationMap.get("evaluation")
                : null;
        if ("agent_evaluation".equals(meta == null ? null : meta.get("source"))
                || (evaluation instanceof Map<?, ?> evaluationMap && !evaluationMap.isEmpty())) {
            extraMetadata = new LinkedHashMap<String, Object>();
            extraMetadata.put("source", "agent_evaluation");
            extraMetadata.put("feature", "agent_evaluation");
            extraTags = new ArrayList<>();
            extraTags.add("agent_evaluation");
            if (evaluation instanceof Map<?, ?> evaluationMap) {
                Object datasetName = evaluationMap.get("dataset_name");
                Object experimentName = evaluationMap.get("experiment_name");
                for (String key : List.of("dataset_name", "dataset_item_id", "experiment_name")) {
                    Object value = evaluationMap.get(key);
                    if (JsonValues.truthy(value)) {
                        extraMetadata.put("evaluation_" + key, String.valueOf(value));
                    }
                }
                if (JsonValues.truthy(datasetName)) {
                    extraTags.add("dataset:" + datasetName);
                }
                if (JsonValues.truthy(experimentName)) {
                    extraTags.add("experiment:" + experimentName);
                }
            }
        }

        return langfuseService.buildRunContext(
                String.valueOf(currentUser.getUid()),
                threadId,
                agentId,
                requestId,
                operation,
                backendId,
                messageType,
                currentUser.getUsername(),
                currentUser.getUid(),
                currentUser.getDepartmentId(),
                extraMetadata,
                extraTags);
    }

    /** 仅为具备完整 AgentRun 因果归属的 worker 流创建 Model 审计器（对应 {@code _build_model_message_audit_collector}）。 */
    private ModelMessageAuditCollector buildModelMessageAuditCollector(
            Map<String, Object> meta, String threadId) {
        if (meta == null) {
            return null;
        }
        String runId = JsonValues.text(meta.get("run_id")).trim();
        String requestId = JsonValues.text(meta.get("request_id")).trim();
        String workerId = JsonValues.text(meta.get("worker_id")).trim();
        if (runId.isEmpty() || requestId.isEmpty() || workerId.isEmpty()) {
            return null;
        }
        return new ModelMessageAuditCollector(
                modelMessageAuditRepository, runId, requestId, threadId, workerId);
    }

    /** 复用已校验的 AgentRun 因果归属创建 ToolMessage 审计器（对应 {@code _build_tool_message_audit_collector}）。 */
    private ToolMessageAuditCollector buildToolMessageAuditCollector(ModelMessageAuditCollector modelAudit) {
        if (modelAudit == null) {
            return null;
        }
        return new ToolMessageAuditCollector(
                toolMessageAuditRepository,
                modelAudit.runId(),
                modelAudit.requestId(),
                modelAudit.threadId(),
                modelAudit.workerId());
    }

    /** 只接受根 StreamMux 或已明确路由回当前线程的 Tool lifecycle（对应 {@code _is_root_tool_audit_event}）。 */
    private static boolean isRootToolAuditEvent(Map<String, Object> event, String threadId) {
        Object namespace = event == null ? null : event.get("namespace");
        boolean namespaceEmpty = !(namespace instanceof List<?> list) || list.isEmpty();
        Object eventThreadId = event == null ? null : event.get("thread_id");
        return Objects.equals(eventThreadId, threadId) || (namespaceEmpty && eventThreadId == null);
    }

    /**
     * 在模型执行前用独立短事务固化 Run 的 Langfuse trace
     * （对应 {@code _persist_agent_run_langfuse_trace}）。
     */
    public void persistAgentRunLangfuseTrace(Map<String, Object> meta, LangfuseService.LangfuseRunContext runContext) {
        if (meta == null || runContext == null) {
            return;
        }
        String runId = JsonValues.text(meta.get("run_id"));
        String workerId = JsonValues.text(meta.get("worker_id"));
        String traceId = runContext.traceId();
        if (runId.isEmpty() || workerId.isEmpty() || traceId == null || traceId.isEmpty()) {
            return;
        }
        AgentRun run = agentRunRepository.setLangfuseTraceId(runId, traceId, workerId, null);
        if (run == null) {
            throw new IllegalArgumentException("AgentRun 不存在: " + runId);
        }
    }

    /** 把历史 artifact 根前缀改写为当前 Workdir 前缀（对应 {@code _normalize_agent_artifact_path}）。 */
    private static Object normalizeAgentArtifactPath(Object path, String workdirPath) {
        if (!(path instanceof String text) || !JsonValues.truthy(workdirPath)) {
            return path;
        }
        for (String namespace : LEGACY_ARTIFACT_NAMESPACES) {
            String prefix = LEGACY_ARTIFACT_ROOT + "/" + namespace;
            if (text.equals(prefix) || text.startsWith(prefix + "/")) {
                return workdirPath + text.substring(LEGACY_ARTIFACT_ROOT.length());
            }
        }
        return path;
    }

    // =========================================================================
    // === Agent state 抽取 ===
    // =========================================================================

    /** 从运行 state 中提取面向前端的 agent 状态（对应 {@code extract_agent_state}）。 */
    public Map<String, Object> extractAgentState(Map<String, Object> values, String workdirPath) {
        if (values == null) {
            return AgentState.AgentStatePayload.empty();
        }

        Object todos = values.get("todos");
        Object artifacts = values.get("artifacts");
        Object subagentRuns = values.get("subagent_runs");
        Object tokenUsage = values.get("token_usage");

        List<Object> todoList = new ArrayList<>();
        if (todos instanceof List<?> list) {
            // 参考实现 list(todos)[:20]：只截取前 20 条。
            for (Object item : list) {
                if (todoList.size() >= 20) {
                    break;
                }
                todoList.add(item);
            }
        }
        Map<String, Object> files = JsonValues.asMap(values.get("files"));
        List<Object> artifactList = new ArrayList<>();
        if (artifacts instanceof List<?> list) {
            for (Object path : list) {
                artifactList.add(normalizeAgentArtifactPath(path, workdirPath));
            }
        }
        List<Object> subagentRunList = subagentRuns instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(AgentState.AgentStatePayload.TODOS, todoList);
        result.put(AgentState.AgentStatePayload.FILES, files);
        result.put(AgentState.AgentStatePayload.ARTIFACTS, artifactList);
        result.put(AgentState.AgentStatePayload.SUBAGENT_RUNS, subagentRunList);
        result.put(
                AgentState.AgentStatePayload.TOKEN_USAGE,
                tokenUsage instanceof Map<?, ?> map ? copyMap(map) : null);
        return result;
    }

    /** agent state 的稳定签名（用于去重推送）。 */
    private static String agentStateSignature(Map<String, Object> agentState) {
        if (agentState == null || agentState.isEmpty()) {
            return "";
        }
        try {
            return canonicalJson(agentState);
        } catch (RuntimeException error) {
            return String.valueOf(agentState);
        }
    }

    /** {@code json.dumps(..., ensure_ascii=False, sort_keys=True)} 的等价。 */
    private static String canonicalJson(Object payload) {
        return AgentRunManifestService.canonicalJson(payload);
    }

    /** 提取只属于当前 Run 的用量；缺失时保留明确的不可用事实（对应 {@code _current_run_token_usage}）。 */
    static Map<String, Object> currentRunTokenUsage(Map<String, Object> agentState, String runId) {
        Object tokenUsage = agentState == null ? null : agentState.get("token_usage");
        if (tokenUsage instanceof Map<?, ?> usageMap
                && JsonValues.truthy(runId)
                && Objects.equals(usageMap.get("current_run_id"), runId)) {
            Object runUsage = usageMap.get("run");
            if (runUsage instanceof Map<?, ?> runUsageMap) {
                return copyMap(runUsageMap);
            }
        }
        Map<String, Object> unavailable = new LinkedHashMap<String, Object>();
        unavailable.put("available", false);
        return unavailable;
    }

    /** 取事件 metadata 的 namespace（对应 {@code _metadata_namespace}）。 */
    private static List<String> metadataNamespace(Map<String, Object> metadata) {
        if (metadata == null) {
            return new ArrayList<>();
        }
        Object namespace = metadata.get("namespace");
        if (namespace instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return new ArrayList<>();
    }

    /** 确保 SubAgent 只读取同一 Project 根 Conversation 的附件（对应 {@code _validate_subagent_attachment_root}）。 */
    private static void validateSubagentAttachmentRoot(
            Conversation rootConversation, Conversation conversation, String uid) {
        if (rootConversation == null
                || !Objects.equals(rootConversation.getUid(), uid)
                || !Objects.equals(rootConversation.getProjectId(), conversation.getProjectId())) {
            throw new IllegalArgumentException("子智能体根 Conversation 的 Project Workdir 不可用");
        }
    }

    // =========================================================================
    // === 流式事件 → 前端事件 ===
    // =========================================================================

    /** 流内消息的稳定来源键（对应 {@code _stream_message_key} 的二元组）。 */
    private static String[] streamMessageKey(
            Map<String, Object> metadata, List<String> namespace, String threadId) {
        String joinedNamespace = String.join("/", namespace);
        if (metadata == null) {
            return new String[] {threadId == null ? "" : threadId, joinedNamespace};
        }
        Object route = JsonValues.or(metadata.get("run_id"), metadata.get("langgraph_node"));
        return new String[] {
            threadId == null ? "" : threadId,
            JsonValues.truthy(route) ? String.valueOf(route) : joinedNamespace
        };
    }

    /** 取（或分配）该来源键对应的 message_id（对应 {@code _stream_message_id}）。 */
    private static String streamMessageId(Map<String, String> messageIds, String[] key, String preferred) {
        String cacheKey = key[0] + "\u0000" + key[1];
        if (JsonValues.truthy(preferred)) {
            messageIds.put(cacheKey, preferred);
            return preferred;
        }
        String existing = messageIds.get(cacheKey);
        if (existing != null) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        messageIds.put(cacheKey, generated);
        return generated;
    }

    /** AIMessageChunk → {@code message_delta} / {@code tool_call_delta} 事件（对应 {@code _message_chunk_yuxi_events}）。 */
    private static List<Map<String, Object>> messageChunkEvents(
            Map<String, Object> msgDict, String messageId, String threadId, List<String> namespace) {
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> route = kwargs("thread_id", threadId, "namespace", namespace);
        Map<String, String> body = com.wisesoft.wenqu.models.ModelUtils.parseAssistantMessageBody(
                msgDict.get("content"), msgDict);

        Map<String, Object> messageEvent = new LinkedHashMap<String, Object>();
        messageEvent.put("type", "message_delta");
        messageEvent.put("message_id", messageId);
        messageEvent.putAll(route);
        for (Map.Entry<String, String> entry : body.entrySet()) {
            // 参考实现 {k: v for k, v in body.items() if v}：仅保留真值字段。
            if (JsonValues.truthy(entry.getValue())) {
                messageEvent.put(entry.getKey(), entry.getValue());
            }
        }
        if (messageEvent.size() > 4) {
            events.add(messageEvent);
        }

        Object toolCallChunks = msgDict.get("tool_call_chunks");
        if (toolCallChunks instanceof List<?> chunkList) {
            for (Object rawChunk : chunkList) {
                if (!(rawChunk instanceof Map<?, ?> toolCallChunk)) {
                    continue;
                }
                Object rawArgs = toolCallChunk.get("args");
                String argsDelta;
                if (rawArgs == null) {
                    argsDelta = "";
                } else if (rawArgs instanceof String text) {
                    argsDelta = text;
                } else {
                    argsDelta = JSON.toJSONString(rawArgs);
                }
                boolean hasId = JsonValues.truthy(toolCallChunk.get("id"));
                boolean hasName = JsonValues.truthy(toolCallChunk.get("name"));
                if (!hasId && !hasName && argsDelta.isEmpty()) {
                    continue;
                }
                Map<String, Object> toolEvent = new LinkedHashMap<String, Object>();
                toolEvent.put("type", "tool_call_delta");
                toolEvent.put("message_id", messageId);
                toolEvent.put("tool_call_id", toolCallChunk.get("id"));
                toolEvent.put("name", hasName ? toolCallChunk.get("name") : null);
                toolEvent.put("args_delta", argsDelta);
                toolEvent.put(
                        "index",
                        toolCallChunk.get("index") != null ? toolCallChunk.get("index") : 0);
                toolEvent.putAll(route);
                events.add(toolEvent);
            }
        }
        return events;
    }

    /** 协议层事件（{@code content-block-*}）→ 前端事件（对应 {@code _protocol_event_yuxi_event}）。 */
    private static Map<String, Object> protocolEventToStreamEvent(
            Map<String, Object> event, String messageId, String threadId, List<String> namespace) {
        Object eventName = event.get("event");
        if (Set.of("message-start", "content-block-start", "message-finish").contains(eventName)
                || !JsonValues.truthy(messageId)) {
            return null;
        }

        Map<String, Object> route = kwargs("thread_id", threadId, "namespace", namespace);
        if ("content-block-delta".equals(eventName)) {
            Map<String, Object> delta = event.get("delta") instanceof Map<?, ?> deltaMap
                    ? copyMap(deltaMap)
                    : new LinkedHashMap<String, Object>();
            Object text = delta.get("text");
            if ("text-delta".equals(delta.get("type")) && text instanceof String textValue && !textValue.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("type", "message_delta");
                result.put("message_id", messageId);
                result.put("content", textValue);
                result.putAll(route);
                return result;
            }
            Object reasoning = delta.get("reasoning");
            if ("reasoning-delta".equals(delta.get("type"))
                    && reasoning instanceof String reasoningValue
                    && !reasoningValue.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("type", "message_delta");
                result.put("message_id", messageId);
                result.put("reasoning_content", reasoningValue);
                result.putAll(route);
                return result;
            }
            return null;
        }

        if ("content-block-finish".equals(eventName)) {
            Map<String, Object> content = event.get("content") instanceof Map<?, ?> contentMap
                    ? copyMap(contentMap)
                    : new LinkedHashMap<String, Object>();
            boolean isToolCall = "tool_call".equals(content.get("type"));
            if (!isToolCall
                    || (!JsonValues.truthy(content.get("id")) && !JsonValues.truthy(content.get("name")))) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("type", "tool_call");
            result.put("message_id", messageId);
            result.put("tool_call_id", content.get("id"));
            result.put("name", content.get("name"));
            result.put("args", content.get("args") != null ? content.get("args") : new LinkedHashMap<String, Object>());
            result.put("index", event.get("index") != null ? event.get("index") : 0);
            result.putAll(route);
            return result;
        }

        return null;
    }

    /**
     * 压缩事件载荷识别（对应 {@code _context_compression_payload}）。
     *
     * <p><b>品牌前缀必要替换</b>：参考实现的事件类型字面量是 {@code "yuxi.context_compression"}
     * （生产者 {@code agents/middlewares/summary.py} 的 {@code _emit_compression}）。生产者与
     * 消费者同处后端，且本工程前端（{@code web/src}）对两个字面量均无引用 —— 已核查，
     * 与 {@link com.wisesoft.wenqu.service.AgentRunService#AGENT_STATE_EVENT_NAME} 同口径，
     * 故为纯品牌替换，改用 {@code "wenqu.context_compression"}。
     *
     * <p><b>接线约束</b>：{@link com.wisesoft.wenqu.agents.middlewares.SummaryMiddleware.CompressionEventSink}
     * 的实现必须发射同一字面量，否则压缩事件会被本方法静默丢弃（收到事件但不转发）。
     */
    private static Map<String, Object> contextCompressionPayload(Object payload) {
        if (payload instanceof Map<?, ?> map && "wenqu.context_compression".equals(map.get("type"))) {
            return copyMap(map);
        }
        return null;
    }

    /** 流事件的 {@code response} 文本（对应 {@code _stream_event_response}）。 */
    private static String streamEventResponse(Map<String, Object> event) {
        if (event == null || !"message_delta".equals(event.get("type"))) {
            return "";
        }
        Object content = event.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    /** 单条流消息 → 前端事件列表（对应 {@code _message_payload_yuxi_events}）。 */
    private static List<Map<String, Object>> messagePayloadEvents(
            Object message,
            Map<String, Object> metadata,
            List<String> namespace,
            String threadId,
            Map<String, String> protocolMessageIds) {
        String[] messageKey = streamMessageKey(metadata, namespace, threadId);

        if (message instanceof Map<?, ?> messageMap && messageMap.get("event") instanceof String) {
            String preferred = null;
            if ("message-start".equals(messageMap.get("event")) && JsonValues.truthy(messageMap.get("id"))) {
                preferred = String.valueOf(messageMap.get("id"));
            }
            String messageId = streamMessageId(protocolMessageIds, messageKey, preferred);
            Map<String, Object> streamEvent = protocolEventToStreamEvent(
                    copyMap(messageMap), messageId, threadId, namespace);
            return streamEvent == null ? List.of() : List.of(streamEvent);
        }

        Map<String, Object> msgDict = toMessageDict(message);
        Object rawId = msgDict.get("id");
        String messageId = JsonValues.truthy(rawId)
                ? String.valueOf(rawId)
                : streamMessageId(protocolMessageIds, messageKey, null);
        return messageChunkEvents(msgDict, messageId, threadId, namespace);
    }

    /**
     * 消息对象 → 字典：{@code model_dump()} > {@code dict} > {@code {"content": str(msg)}}
     * （对应参考实现的 {@code hasattr(msg, "model_dump")} 分支序）。
     */
    private static Map<String, Object> toMessageDict(Object message) {
        if (message instanceof ModelDumpable dumpable) {
            return dumpable.modelDump();
        }
        if (message instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        fallback.put("content", String.valueOf(message));
        return fallback;
    }

    // =========================================================================
    // === 消息持久化 ===
    // =========================================================================

    /** 提取 AIMessage 可展示正文和兼容 ToolCall 投影（对应 {@code _ai_message_content_and_tool_calls}）。 */
    private static Object[] aiMessageContentAndToolCalls(Map<String, Object> msgDict) {
        Object content = msgDict.get("content") == null ? "" : msgDict.get("content");
        Object rawToolCalls = msgDict.get("tool_calls");
        List<Object> toolCallsData = rawToolCalls instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
        String text;
        if (content instanceof List<?> blocks) {
            if (toolCallsData.isEmpty()) {
                for (Object item : blocks) {
                    if (item instanceof Map<?, ?> itemMap && "tool_call".equals(itemMap.get("type"))) {
                        toolCallsData.add(kwargs(
                                "id", itemMap.get("id"),
                                "name", itemMap.get("name"),
                                "args", itemMap.get("args") != null ? itemMap.get("args") : new LinkedHashMap<String, Object>()));
                    }
                }
            }
            List<String> parts = new ArrayList<>();
            for (Object item : blocks) {
                if (item instanceof Map<?, ?> itemMap && itemMap.get("text") instanceof String part) {
                    parts.add(part);
                }
            }
            text = String.join("\n", parts);
        } else if (content instanceof String stringContent) {
            text = stringContent;
        } else {
            text = String.valueOf(content);
        }
        return new Object[] {text, toolCallsData};
    }

    /** 从 AIMessage 单向投影阶段二仍需兼容的 ToolCall（对应 {@code _project_ai_tool_calls}）。 */
    private void projectAiToolCalls(Integer messageId, List<Object> toolCallsData) {
        for (Object rawToolCall : toolCallsData) {
            if (!(rawToolCall instanceof Map<?, ?> toolCall)) {
                continue;
            }
            Object name = toolCall.get("name");
            Object args = toolCall.get("args");
            Object callId = toolCall.get("id");
            conversationRepository.addToolCall(
                    messageId,
                    JsonValues.truthy(name) ? String.valueOf(name) : "unknown",
                    args instanceof Map<?, ?> argsMap ? copyMap(argsMap) : new LinkedHashMap<String, Object>(),
                    null,
                    "pending",
                    null,
                    callId == null ? null : String.valueOf(callId));
        }
    }

    /** 写入 assistant 消息并按需投影 ToolCall（对应 {@code _save_ai_message}）。 */
    private Message saveAiMessage(
            String threadId,
            Map<String, Object> msgDict,
            Map<String, Object> traceInfo,
            String runId,
            String requestId,
            boolean projectToolCalls) {
        Object[] extracted = aiMessageContentAndToolCalls(msgDict);
        String content = (String) extracted[0];
        @SuppressWarnings("unchecked")
        List<Object> toolCallsData = (List<Object>) extracted[1];

        Map<String, Object> extraMetadata = new LinkedHashMap<String, Object>(msgDict);
        if (traceInfo != null && !traceInfo.isEmpty()) {
            extraMetadata.putAll(traceInfo);
        }

        Message aiMessage = conversationRepository.addMessageByThreadId(
                threadId, "assistant", content, "text", extraMetadata, null, runId, requestId, null);

        if (aiMessage != null && !toolCallsData.isEmpty() && projectToolCalls) {
            projectAiToolCalls(aiMessage.getId(), toolCallsData);
        }
        return aiMessage;
    }

    /** 用 ToolMessage 更新兼容 ToolCall 的输出（对应 {@code _save_tool_message}）。 */
    private void saveToolMessage(Map<String, Object> msgDict) {
        Object toolCallId = msgDict.get("tool_call_id");
        Object content = msgDict.get("content") == null ? "" : msgDict.get("content");

        if (!JsonValues.truthy(toolCallId)) {
            return;
        }

        String toolOutput;
        if (content instanceof List<?> list) {
            toolOutput = list.isEmpty() ? "" : JSON.toJSONString(list);
        } else {
            toolOutput = String.valueOf(content);
        }

        conversationRepository.updateToolCallOutput(String.valueOf(toolCallId), toolOutput, "success", null);
    }

    /**
     * 保存 Run 的部分输出（错误/中断路径）。
     *
     * <p>{@code _run_id} 存在时必须带 worker/request 因果归属，先锁定 attempt 再写入；
     * {@code interrupt_run} 时同一事务内落 {@code interrupted} 终态并级联取消执行树后代。
     * 失败路径：回滚；{@code interrupt_run} 时重抛，否则吞掉返回 {@code null}
     * （对应 {@code save_partial_message}）。
     */
    public Message savePartialMessage(
            String threadId,
            Object fullMessage,
            String errorMessage,
            String errorType,
            Map<String, Object> traceInfo,
            String runId,
            String requestId,
            String workerId,
            boolean interruptRun) {
        try {
            return transactionTemplate.execute(status -> doSavePartialMessage(
                    threadId, fullMessage, errorMessage, errorType, traceInfo,
                    runId, requestId, workerId, interruptRun));
        } catch (RuntimeException error) {
            log.error("Error saving message: {}", error.getMessage(), error);
            if (interruptRun) {
                throw error;
            }
            return null;
        }
    }

    private Message doSavePartialMessage(
            String threadId,
            Object fullMessage,
            String errorMessage,
            String errorType,
            Map<String, Object> traceInfo,
            String runId,
            String requestId,
            String workerId,
            boolean interruptRun) {
        Map<String, Object> extraMetadata = new LinkedHashMap<String, Object>();
        extraMetadata.put("error_type", errorType);
        extraMetadata.put("is_error", true);
        extraMetadata.put("error_message", JsonValues.truthy(errorMessage) ? errorMessage : "发生错误: " + errorType);

        String content;
        if (fullMessage != null) {
            Map<String, Object> msgDict = toMessageDict(fullMessage);
            Object rawContent = fullMessage instanceof ModelDumpable dumpable
                    ? dumpable.modelDump().get("content")
                    : (fullMessage instanceof Map<?, ?> map ? map.get("content") : String.valueOf(fullMessage));
            content = rawContent == null ? "" : String.valueOf(rawContent);
            Map<String, Object> merged = new LinkedHashMap<String, Object>(msgDict);
            merged.putAll(extraMetadata);
            extraMetadata = merged;
        } else {
            content = "";
        }

        if (traceInfo != null && !traceInfo.isEmpty()) {
            extraMetadata.putAll(traceInfo);
        }

        boolean hasRun = JsonValues.truthy(runId);
        if (hasRun) {
            if (!JsonValues.truthy(workerId) || !JsonValues.truthy(requestId)) {
                throw new IllegalArgumentException("持久化 AgentRun 部分输出需要当前 worker 和 request");
            }
            AgentRun lockedRun =
                    agentRunRepository.lockOutputPersistence(runId, workerId, threadId, requestId, null);
            if (lockedRun == null) {
                throw new IllegalArgumentException("AgentRun 不存在: " + runId);
            }
        }

        Message message = conversationRepository.addMessageByThreadId(
                threadId, "assistant", content, "text", extraMetadata, null, runId, requestId, null);
        if (hasRun && message != null) {
            agentRunRepository.setOutputMessage(runId, message.getId(), workerId, null);
            if (interruptRun) {
                AgentRunRepository.RunResult terminal =
                        agentRunRepository.setTerminalStatus(
                                runId,
                                "interrupted",
                                errorType,
                                errorMessage,
                                tokenUsageUnavailable(),
                                workerId,
                                null);
                if (terminal == null || terminal.run() == null || !terminal.changed()) {
                    throw new IllegalArgumentException(
                            "AgentRun 部分输出已写入但 interrupted 终态未能在同一事务提交");
                }
                publishCancelledDescendants(agentRunRepository.cancelActiveExecutionTreeDescendants(terminal.run()));
            }
        } else if (hasRun) {
            throw new IllegalArgumentException("AgentRun 中断输出消息未能持久化");
        }
        return message;
    }

    /** {@code {"available": False}}（参考实现的中断终态用量事实）。 */
    private static Map<String, Object> tokenUsageUnavailable() {
        Map<String, Object> usage = new LinkedHashMap<String, Object>();
        usage.put("available", false);
        return usage;
    }

    /** {@code publish_cancel_signals([run_id for run_id, _thread_id in cancelled_descendants])}。 */
    private void publishCancelledDescendants(List<String[]> cancelledDescendants) {
        if (cancelledDescendants == null || cancelledDescendants.isEmpty()) {
            return;
        }
        List<String> runIds = new ArrayList<>();
        for (String[] pair : cancelledDescendants) {
            if (pair != null && pair.length > 0) {
                runIds.add(pair[0]);
            }
        }
        runQueueService.publishCancelSignals(runIds);
    }

    /**
     * 用终态 State 补全同一稳定来源键的 Model 审计消息
     * （对应 {@code _reconcile_model_audit_message}）。
     */
    private Message reconcileModelAuditMessage(
            String runId,
            String operationId,
            Map<String, Object> msgDict,
            Map<String, Object> traceInfo) {
        Message message = modelMessageAuditRepository.get(runId, operationId);
        if (message == null) {
            return null;
        }

        Object[] extracted = aiMessageContentAndToolCalls(msgDict);
        String content = (String) extracted[0];
        @SuppressWarnings("unchecked")
        List<Object> toolCallsData = (List<Object>) extracted[1];

        Map<String, Object> metadata = parseJsonMap(message.getExtraMetadata());
        metadata.putAll(msgDict);
        if (traceInfo != null && !traceInfo.isEmpty()) {
            metadata.putAll(traceInfo);
        }
        metadata.put("state_reconciled", true);

        Message reconciled = modelMessageAuditRepository.reconcileFromState(runId, operationId, content, metadata);
        if (reconciled != null && !toolCallsData.isEmpty()) {
            projectAiToolCalls(reconciled.getId(), toolCallsData);
        }
        return reconciled;
    }

    /** 用终态 State 补全等待 Run 裁决的 Tool error（对应 {@code _reconcile_tool_error_from_state}）。 */
    private void reconcileToolErrorFromState(
            String runId,
            String requestId,
            String threadId,
            String workerId,
            String toolCallId,
            Map<String, Object> msgDict) {
        if (!JsonValues.truthy(requestId) || !JsonValues.truthy(workerId)) {
            throw new IllegalArgumentException("ToolMessage 对账需要 worker、thread 和 request 因果归属");
        }
        String content = toolMessageContent(msgDict.get("content"));
        toolMessageAuditRepository.fail(
                runId,
                requestId,
                threadId,
                workerId,
                toolCallId,
                JsonValues.truthy(content) ? content : "Tool 执行失败",
                BaseAgent.jsonSafe(msgDict),
                content,
                DateTimeUtils.utcNowNaive(),
                null,
                null);
    }

    /** ToolMessage content → 兼容 ToolCall 的稳定文本（对应 {@code _tool_message_content}）。 */
    private static String toolMessageContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        return JSON.toJSONString(content);
    }

    /** 只用终态 State 补全仍等待 Run 裁决的 Tool error（对应 {@code _should_reconcile_tool_state}）。 */
    private static boolean shouldReconcileToolState(Message audit, Map<String, Object> toolMessage) {
        if (!"running".equals(audit.getExecutionStatus())) {
            return false;
        }
        JSONObject metadata = parseJsonObject(audit.getExtraMetadata());
        return Boolean.TRUE.equals(metadata.get("awaiting_run_terminal"))
                && "error".equals(toolMessage.get("status"));
    }

    /**
     * 在有效 lease 锁内原子写入消息与完成或中断终态
     * （对应 {@code save_messages_from_langgraph_state}）。
     *
     * @return 是否落定了终态（{@code terminal_status is not None}）
     */
    public boolean saveMessagesFromLanggraphState(
            GraphStateSnapshot state,
            String threadId,
            Map<String, Object> traceInfo,
            String runId,
            String requestId,
            String workerId,
            boolean completeRun,
            boolean interruptRun,
            String interruptErrorType,
            String interruptErrorMessage,
            Map<String, Object> tokenUsage) {
        if (completeRun && interruptRun) {
            throw new IllegalArgumentException("AgentRun 不能同时完成和中断");
        }
        Boolean committed = transactionTemplate.execute(status -> doSaveMessagesFromLanggraphState(
                state, threadId, traceInfo, runId, requestId, workerId,
                completeRun, interruptRun, interruptErrorType, interruptErrorMessage, tokenUsage));
        return Boolean.TRUE.equals(committed);
    }

    private boolean doSaveMessagesFromLanggraphState(
            GraphStateSnapshot state,
            String threadId,
            Map<String, Object> traceInfo,
            String runId,
            String requestId,
            String workerId,
            boolean completeRun,
            boolean interruptRun,
            String interruptErrorType,
            String interruptErrorMessage,
            Map<String, Object> tokenUsage) {
        boolean hasRun = JsonValues.truthy(runId);
        if (hasRun) {
            if (!JsonValues.truthy(workerId) || !JsonValues.truthy(requestId)) {
                throw new IllegalArgumentException("持久化 AgentRun 输出需要 worker、thread 和 request 因果归属");
            }
            AgentRun lockedRun =
                    agentRunRepository.lockOutputPersistence(runId, workerId, threadId, requestId, null);
            if (lockedRun == null) {
                throw new IllegalArgumentException("AgentRun 不存在: " + runId);
            }
        }

        List<Object> messages = new ArrayList<>();
        Object rawMessages = state == null ? null : state.value("messages");
        if (rawMessages instanceof List<?> list) {
            messages.addAll(list);
        }
        Set<String> existingIds = conversationRepository.getMessageSourceIdsByThreadId(threadId);
        List<Message> currentModelAudits = hasRun ? modelMessageAuditRepository.listForRun(runId) : new ArrayList<>();
        Set<String> currentAuditOperationIds = new LinkedHashSet<>();
        for (Message audit : currentModelAudits) {
            if (JsonValues.truthy(audit.getOperationId())) {
                currentAuditOperationIds.add(audit.getOperationId());
            }
        }
        List<Message> currentToolAudits = hasRun ? toolMessageAuditRepository.listForRun(runId) : new ArrayList<>();
        Map<String, Message> currentToolAuditsByOperation = new LinkedHashMap<String, Message>();
        for (Message audit : currentToolAudits) {
            if (JsonValues.truthy(audit.getOperationId())) {
                currentToolAuditsByOperation.put(audit.getOperationId(), audit);
            }
        }
        Set<String> currentToolOperationIds = new LinkedHashSet<>(currentToolAuditsByOperation.keySet());
        // 本项目增量：收集本次 Run 检索结果里的知识库截图代理 URL 及其上下文（保底图文交错用）
        LinkedHashMap<String, String> kbImageContexts = new LinkedHashMap<>();
        Map<String, Message> reconciledAudits = new LinkedHashMap<String, Message>();
        Map<String, Map<String, Object>> stateModelMessages =
                new LinkedHashMap<String, Map<String, Object>>();
        Map<String, Map<String, Object>> stateToolMessages =
                new LinkedHashMap<String, Map<String, Object>>();
        String lastStateAiId = null;
        Message lastAiMessage = null;

        int stateIndex = -1;
        for (Object msg : messages) {
            stateIndex++;
            Map<String, Object> msgDict;
            if (msg instanceof ModelDumpable dumpable) {
                msgDict = dumpable.modelDump();
            } else if (msg instanceof org.springframework.ai.chat.messages.Message springAiMessage) {
                // 引擎 checkpoint 的 state.messages 存的是 Spring AI 原生消息（UserMessage /
                // AssistantMessage / ToolResponseMessage），不是 LangChain 的 model_dump() 形态。
                // 用同一套桥接转成承载类再 dump；否则整条消息会落入下面的 else 被静默丢弃，
                // 末条 AIMessage 随之丢失，completed 时无 assistant 输出可绑定
                //（"AgentRun 完成前必须绑定同一 Run 的有效 assistant 输出消息"）。
                Object carrier = GraphCodec.toCarrier(springAiMessage);
                if (!(carrier instanceof ModelDumpable carrierDumpable)) {
                    continue;
                }
                msgDict = carrierDumpable.modelDump();
            } else if (msg instanceof Map<?, ?> map) {
                msgDict = copyMap(map);
            } else {
                continue;
            }
            // Spring AI 的 AbstractMessage 没有 id 字段，而参考实现的「已持久化来源键」去重依赖
            // msg_dict["id"]（能力差异，显式补齐）：按 state 内位置派生一个跨 replay 复现的稳定键。
            // 用位置而非内容，避免相同答复被误判为已存在而跳过末条输出。
            if (msgDict.get("id") == null) {
                msgDict.put("id", "lg:" + stateIndex);
            }

            String msgType = msgDict.get("type") == null ? "unknown" : String.valueOf(msgDict.get("type"));
            if ("unknown".equals(msgType)) {
                Object role = msgDict.get("role");
                if ("assistant".equals(role) || "ai".equals(role)) {
                    msgType = "ai";
                } else if ("user".equals(role) || "human".equals(role)) {
                    msgType = "human";
                } else if ("tool".equals(role)) {
                    msgType = "tool";
                }
            }

            Object rawId = msg instanceof Message message ? message.getId() : null;
            if (rawId == null) {
                rawId = msgDict.get("id");
            }
            String msgId = rawId == null ? null : String.valueOf(rawId);
            if ("human".equals(msgType)) {
                continue;
            }

            if ("ai".equals(msgType)) {
                lastStateAiId = msgId;
                if (hasRun && JsonValues.truthy(msgId) && currentAuditOperationIds.contains(msgId)) {
                    // Checkpoint 包含线程完整历史；同一来源键只对账最后一次 AIMessage。
                    stateModelMessages.put(msgId, msgDict);
                    continue;
                }
                if (!currentModelAudits.isEmpty() || existingIds.contains(msgId)) {
                    continue;
                }
                lastAiMessage = saveAiMessage(
                        threadId, msgDict, traceInfo, runId, requestId, !hasRun);
            } else if ("tool".equals(msgType)) {
                collectKbImageContexts(msgDict, kbImageContexts);
                String toolCallId = JsonValues.text(msgDict.get("tool_call_id"));
                if (hasRun && currentToolOperationIds.contains(toolCallId)) {
                    // Checkpoint 包含线程完整历史；同一来源键只对账最后一次 ToolMessage。
                    stateToolMessages.put(toolCallId, msgDict);
                } else if (!hasRun && !existingIds.contains(msgId)) {
                    saveToolMessage(msgDict);
                }
            }
        }

        if (hasRun) {
            for (Map.Entry<String, Map<String, Object>> entry : stateModelMessages.entrySet()) {
                Message reconciled = reconcileModelAuditMessage(
                        runId, entry.getKey(), entry.getValue(), traceInfo);
                if (reconciled != null) {
                    reconciledAudits.put(entry.getKey(), reconciled);
                }
            }
            Message reconciledLast = reconciledAudits.get(lastStateAiId == null ? "" : lastStateAiId);
            lastAiMessage = reconciledLast != null ? reconciledLast : lastAiMessage;

            for (Map.Entry<String, Map<String, Object>> entry : stateToolMessages.entrySet()) {
                Message audit = currentToolAuditsByOperation.get(entry.getKey());
                if (audit == null) {
                    continue;
                }
                if (interruptRun || !shouldReconcileToolState(audit, entry.getValue())) {
                    continue;
                }
                reconcileToolErrorFromState(
                        runId, requestId, threadId, workerId, entry.getKey(), entry.getValue());
            }

            if (!currentModelAudits.isEmpty() && (completeRun || interruptRun)) {
                Message terminalAiMessage = reconciledAudits.get(lastStateAiId == null ? "" : lastStateAiId);
                if (completeRun && terminalAiMessage == null) {
                    throw new IllegalArgumentException(
                            "最终 State AIMessage 无法与当前 Run 的 Model lifecycle 事实关联");
                }
                lastAiMessage = terminalAiMessage;
            }
            if (lastAiMessage != null) {
                if (completeRun && !kbImageContexts.isEmpty()) {
                    interleaveKbImages(lastAiMessage, kbImageContexts);
                }
                JSONObject lastMetadata = parseJsonObject(lastAiMessage.getExtraMetadata());
                boolean hasToolCalls = JsonValues.truthy(lastMetadata.get("tool_calls"));
                boolean shouldPublish = !ModelConstants.MODEL_AUDIT_MESSAGE_TYPE.equals(lastAiMessage.getMessageType())
                        || completeRun
                        || (interruptRun && !hasToolCalls);
                if (shouldPublish) {
                    conversationRepository.publishAssistantOutput(lastAiMessage);
                }
                agentRunRepository.setOutputMessage(runId, lastAiMessage.getId(), workerId, null);
            }

            String terminalStatus = completeRun ? "completed" : (interruptRun ? "interrupted" : null);
            if (terminalStatus != null) {
                AgentRunRepository.RunResult terminal = agentRunRepository.setTerminalStatus(
                        runId,
                        terminalStatus,
                        interruptRun ? interruptErrorType : null,
                        interruptRun ? interruptErrorMessage : null,
                        tokenUsage == null ? tokenUsageUnavailable() : tokenUsage,
                        workerId,
                        null);
                if (terminal == null || terminal.run() == null || !terminal.changed()) {
                    throw new IllegalArgumentException(
                            "AgentRun 输出已写入但 " + terminalStatus + " 终态未能在同一事务提交");
                }
                publishCancelledDescendants(agentRunRepository.cancelActiveExecutionTreeDescendants(terminal.run()));
            }
            return terminalStatus != null;
        }
        return false;
    }

    // =========================================================================
    // === 本项目增量：回答补附知识库截图 ===
    // =========================================================================

    private static final java.util.regex.Pattern KB_IMAGE_URL_PATTERN =
            java.util.regex.Pattern.compile(
                    "(?i)/api/knowledge/databases/[^\"\\\\\\s<>)]+/images/[^\"\\\\\\s<>)]+"
                            + "\\.(?:jpg|jpeg|png|gif|webp|bmp)");

    private static final int KB_IMAGE_MAX = 6;

    /** 从工具结果（query_kb / search_file 返回的 chunk 正文）收集手册截图代理 URL，
     * 并记录 URL 前面的上下文文字（用于在回答里定位贴图位置），按出现顺序去重。 */
    private static void collectKbImageContexts(Map<String, Object> msgDict, LinkedHashMap<String, String> target) {
        if (target.size() >= KB_IMAGE_MAX || msgDict == null) {
            return;
        }
        String raw = String.valueOf(msgDict);
        java.util.regex.Matcher matcher = KB_IMAGE_URL_PATTERN.matcher(raw);
        while (matcher.find() && target.size() < KB_IMAGE_MAX) {
            String url = matcher.group();
            if (target.containsKey(url)) {
                continue;
            }
            int from = Math.max(0, matcher.start() - 160);
            String context = raw.substring(from, matcher.start());
            context = context.replace("\\\"", "\"").replace("\\n", "\n");
            context = context.replaceAll("<[^>]*>", " ");
            context = context.replaceAll("[^\\u4e00-\\u9fffA-Za-z0-9]+", " ").trim();
            if (context.length() > 60) {
                context = context.substring(context.length() - 60);
            }
            target.put(url, context);
        }
    }

    /**
     * 本项目增量（用户需求：回答需<b>图文交错</b>贴出操作手册截图；参考实现无此行为，
     * 模型侧 KB_IMAGE_PROMPT 引导其就近贴图，但模型执行不稳定，本方法保底）。
     *
     * <p>每张截图在 chunk 里都带着它前面的说明文字；把该文字与回答的各段落做
     * CJK 二元组重合度匹配，将截图插入重合度最高（≥0.3）的段落后——即「哪段在讲这张图，
     * 图就贴在哪段后面」。已由模型贴过的 URL 跳过；标题/分隔线/表格块不插图；
     * 找不到合适位置的截图才落到结尾「相关操作截图」。
     */
    private void interleaveKbImages(Message lastAiMessage, LinkedHashMap<String, String> kbImageContexts) {
        String content = lastAiMessage.getContent();
        if (content == null || content.isBlank() || kbImageContexts.isEmpty()) {
            return;
        }
        List<String> blocks = new ArrayList<>(List.of(content.split("\n\n+")));
        boolean changed = false;
        List<String> leftovers = new ArrayList<>();
        for (Map.Entry<String, String> entry : kbImageContexts.entrySet()) {
            String url = entry.getKey();
            if (content.contains(url)) {
                continue; // 模型已自行贴过这张
            }
            Set<String> snippet = kbImageBigrams(entry.getValue());
            int bestIndex = -1;
            double bestScore = 0;
            for (int i = 0; i < blocks.size(); i++) {
                String block = blocks.get(i);
                if (block.startsWith("#") || block.startsWith("---") || block.startsWith("|")
                        || block.contains("<img")) {
                    continue; // 标题/分隔线/表格/已有图的块不插
                }
                double score = kbImageOverlap(snippet, kbImageBigrams(block));
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }
            String img = "<img src=\"" + url + "\" width=\"70%\" />";
            if (bestIndex >= 0 && bestScore >= 0.3) {
                blocks.add(bestIndex + 1, img);
                changed = true;
            } else {
                leftovers.add(url);
            }
        }
        if (!changed && leftovers.isEmpty()) {
            return;
        }
        StringBuilder updated = new StringBuilder(String.join("\n\n", blocks));
        if (!leftovers.isEmpty()) {
            updated.append("\n\n---\n\n**相关操作截图**\n");
            for (String url : leftovers) {
                updated.append("\n<img src=\"").append(url).append("\" width=\"70%\" />");
            }
        }
        lastAiMessage.setContent(updated.toString());
        conversationRepository.updateMessageContent(lastAiMessage.getId(), lastAiMessage.getContent());
    }

    /** 中文/字母数字二元组（中文无分词，二元组重合度足够定位同主题段落）。 */
    private static Set<String> kbImageBigrams(String text) {
        String t = text == null ? "" : text.replaceAll("[^\\u4e00-\\u9fffA-Za-z0-9]+", "");
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) {
            set.add(t.substring(i, i + 2));
        }
        return set;
    }

    /** 重合系数：交集 / 较小集合大小（短上文 vs 长段落，避免长文稀释）。 */
    private static double kbImageOverlap(Set<String> snippet, Set<String> block) {
        if (snippet.isEmpty() || block.isEmpty()) {
            return 0;
        }
        int hit = 0;
        for (String gram : snippet) {
            if (block.contains(gram)) {
                hit++;
            }
        }
        return (double) hit / Math.min(snippet.size(), block.size());
    }

    // =========================================================================
    // === 中断 ===
    // =========================================================================

    /** 将中断对象转换为 dict 结构（对应 {@code _coerce_interrupt_payload}）。 */
    private static Map<String, Object> coerceInterruptPayload(Object info) {
        if (info instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Object questions = reflectionGet(info, "getQuestions");
        Object source = reflectionGet(info, "getSource");
        if (questions instanceof List<?> list) {
            result.put("questions", list);
        }
        if (source instanceof String text && !text.trim().isEmpty()) {
            result.put("source", text);
        }
        return result;
    }

    /** 反射读取中断对象属性（Java 侧无 {@code getattr} 的 duck typing，收敛为一个取值接缝）。 */
    private static Object reflectionGet(Object target, String accessor) {
        if (target == null) {
            return null;
        }
        if ("getQuestions".equals(accessor) && target instanceof Map<?, ?> map) {
            return map.get("questions");
        }
        try {
            return target.getClass().getMethod(accessor).invoke(target);
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    /** 构造 ask_user_question_required 载荷（对应 {@code _build_ask_user_question_payload}）。 */
    static Map<String, Object> buildAskUserQuestionPayload(Map<String, Object> payload, String threadId) {
        List<Map<String, Object>> questions = QuestionUtils.normalizeQuestions(payload.get("questions"));

        if (questions == null || questions.isEmpty()) {
            questions = new ArrayList<>();
            questions.add(kwargs(
                    "question_id", UUID.randomUUID().toString(),
                    "question", "请选择一个选项",
                    "options", new ArrayList<>(),
                    "multi_select", false,
                    "allow_other", true));
        }

        Object source = JsonValues.or(payload.get("source"), JsonValues.or(payload.get("tool_name"), "interrupt"));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("questions", questions);
        result.put("source", String.valueOf(source));
        result.put("thread_id", threadId);
        return result;
    }

    /** 构造 tool_approval_required 载荷（对应 {@code _build_tool_approval_payload}）。 */
    static Map<String, Object> buildToolApprovalPayload(Map<String, Object> payload, String threadId) {
        Object actionRequests = payload.get("action_requests");
        Object reviewConfigs = payload.get("review_configs");
        if (!(actionRequests instanceof List<?> actionList) || !(reviewConfigs instanceof List<?> reviewList)) {
            return null;
        }
        if (actionList.isEmpty() || actionList.size() != reviewList.size()) {
            return null;
        }
        Map<String, Object> approval = new LinkedHashMap<String, Object>();
        approval.put("action_requests", BaseAgent.jsonSafe(actionRequests));
        approval.put("review_configs", BaseAgent.jsonSafe(reviewConfigs));

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("approval", approval);
        result.put("thread_id", threadId);
        return result;
    }

    /** checkpoint 中断信息 → 前端可恢复的统一载荷（对应 {@code _build_pending_interrupt_payload}）。 */
    static Map<String, Object> buildPendingInterruptPayload(Object info, String threadId) {
        Map<String, Object> coerced = coerceInterruptPayload(info);
        Map<String, Object> approvalPayload = buildToolApprovalPayload(coerced, threadId);
        if (approvalPayload != null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "human_approval_required");
            result.putAll(approvalPayload);
            return result;
        }
        Map<String, Object> questionPayload = buildAskUserQuestionPayload(coerced, threadId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "ask_user_question_required");
        result.putAll(questionPayload);
        return result;
    }

    /** 从待发送中断 chunk 提取持久终态的错误类型与摘要（对应 {@code _interrupt_terminal_details}）。 */
    static String[] interruptTerminalDetails(String chunk) {
        JSONObject payload;
        try {
            payload = JSON.parseObject(chunk);
        } catch (RuntimeException error) {
            return new String[] {"interrupted", "等待用户交互"};
        }
        if (payload == null) {
            return new String[] {"interrupted", "等待用户交互"};
        }
        String status = payload.get("status") == null ? "interrupted" : String.valueOf(payload.get("status"));
        if ("human_approval_required".equals(status)) {
            return new String[] {status, "需要用户审批工具操作"};
        }
        Object questions = payload.get("questions");
        if (questions instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object question = first.get("question");
            if (question != null && !String.valueOf(question).trim().isEmpty()) {
                return new String[] {status, String.valueOf(question).trim()};
            }
        }
        Object message = payload.get("message");
        return new String[] {
            status, message == null ? "需要用户回答问题" : String.valueOf(message)
        };
    }

    // =========================================================================
    // === 运行上下文解析 ===
    // =========================================================================

    /** {@code _resolve_agent_runtime} 的返回体（agent_item / backend / context / conversation）。 */
    public record ResolvedRuntime(Agent agentItem, BaseAgent backend, BaseContext context, Conversation conversation) {}

    /**
     * 校验执行时的线程与 Agent 权限，使用 worker 已固化的配置
     * （对应 {@code _resolve_agent_runtime}）。
     */
    public ResolvedRuntime resolveAgentRuntime(
            User user,
            String requestedAgentSlug,
            String threadId,
            PreparedRunExecution preparedExecution,
            String agentKind) {
        Conversation conversation = conversationRepository.getConversationByThreadId(threadId);
        if (conversation == null
                || !Objects.equals(conversation.getUid(), String.valueOf(user.getUid()))
                || "deleted".equals(conversation.getStatus())) {
            throw new IllegalArgumentException("对话线程不存在");
        }
        // Conversation.agent_id 是历史字段名，实际保存的是 Agent.slug。
        if (JsonValues.truthy(requestedAgentSlug) && !requestedAgentSlug.equals(conversation.getAgentId())) {
            throw new IllegalArgumentException("已有线程已绑定智能体，不能切换");
        }
        workdirService.resolveConversationWorkdirPath(conversation, String.valueOf(user.getUid()));

        AgentRepository.AgentEntryKind kind = "subagent".equals(agentKind)
                ? AgentRepository.AgentEntryKind.SUBAGENT
                : AgentRepository.AgentEntryKind.MAIN;
        Agent agentItem = agentRepository.getVisibleBySlug(
                conversation.getAgentId(), PermissionSubject.of(user), kind);
        if (agentItem == null) {
            throw new IllegalArgumentException("智能体不存在或无权限访问");
        }

        BaseAgent backend = agentManager.getAgent(agentItem.getBackendId());
        if (backend == null) {
            throw new IllegalArgumentException("智能体后端 " + agentItem.getBackendId() + " 不存在");
        }

        if (!Objects.equals(agentItem.getBackendId(), preparedExecution.backendId())) {
            throw new IllegalArgumentException("智能体后端在执行准备后发生变化");
        }
        return new ResolvedRuntime(agentItem, backend, preparedExecution.context(), conversation);
    }

    /**
     * 从本轮已读取的最终 checkpoint 生成中断事件
     * （对应 {@code check_and_handle_interrupts}）。
     *
     * @return 是否产生了中断事件
     */
    public boolean checkAndHandleInterrupts(
            GraphStateSnapshot state,
            ChunkFactory makeChunk,
            Map<String, Object> meta,
            String threadId,
            Consumer<String> sink) {
        try {
            if (state == null || state.isEmpty()) {
                return false;
            }

            Object interruptInfo = state.extractInterruptInfo();
            if (interruptInfo == null) {
                return false;
            }
            Map<String, Object> pendingInterrupt = buildPendingInterruptPayload(interruptInfo, threadId);
            Object status = pendingInterrupt.remove("status");
            meta.put("interrupt", pendingInterrupt);

            Map<String, Object> extra = kwargs("status", status, "meta", meta);
            extra.putAll(pendingInterrupt);
            sink.accept(makeChunk.make(null, extra));
            return true;

        } catch (RuntimeException error) {
            log.error("Error checking interrupts: {}", error.getMessage(), error);
            return false;
        }
    }

    // =========================================================================
    // === 执行流 ===
    // =========================================================================

    /**
     * 执行已持久化的 Run 输入，沿用 worker 固化的配置快照
     * （对应 {@code stream_agent_chat}）。
     */
    public void streamAgentChat(
            String agentSlug,
            String threadId,
            Map<String, Object> metaInput,
            InputMessageService.AgentRunInputMessage inputMessage,
            User currentUser,
            PreparedRunExecution preparedExecution,
            Runnable onPrepared,
            Consumer<String> sink) {
        double startTime = System.nanoTime() / 1_000_000_000.0;
        Map<String, Object> meta = new LinkedHashMap<String, Object>(metaInput == null ? Map.of() : metaInput);
        ChunkFactory makeChunk = chunkFactory(meta, threadId);

        if (!JsonValues.truthy(threadId) || !JsonValues.truthy(meta.get("request_id"))) {
            throw new IllegalArgumentException("执行需要已持久化的 thread_id 和 request_id");
        }
        String uid = String.valueOf(currentUser.getUid());

        String query = inputMessage.content();
        String imageContent = inputMessage.imageContent();
        HumanMessage humanMessage = inputMessage.requireLangchainMessage();
        String messageType = inputMessage.messageType();

        ResolvedRuntime runtime;
        try {
            runtime = resolveAgentRuntime(
                    currentUser,
                    agentSlug,
                    threadId,
                    preparedExecution,
                    "subagent".equals(meta.get("run_type")) ? "subagent" : "main");
        } catch (IllegalArgumentException error) {
            sink.accept(makeChunk.make(
                    null,
                    kwargs(
                            "status", "error",
                            "error_type", "invalid_agent",
                            "error_message", error.getMessage(),
                            "meta", meta)));
            return;
        }

        meta.put("query", query);
        meta.put("agent_slug", runtime.agentItem().getSlug());
        meta.put("backend_id", runtime.agentItem().getBackendId());
        meta.put("thread_id", threadId);
        meta.put("uid", currentUser.getUid());
        meta.put("has_image", JsonValues.truthy(imageContent));

        List<String> accumulatedContent = new ArrayList<>();
        Map<String, Object> traceInfo = new LinkedHashMap<String, Object>();
        AtomicReference<String> lastAgentStateSignature = new AtomicReference<>("");
        LangfuseService.LangfuseRunContext langfuseRun = null;

        try {
            String runtimeScopeId = runtime.context().getString("runtime_scope_id");
            langfuseRun = buildLangfuseRunContext(
                    currentUser,
                    threadId,
                    runtime.agentItem().getSlug(),
                    runtime.agentItem().getBackendId(),
                    JsonValues.text(meta.get("request_id")),
                    "agent_chat_stream",
                    messageType,
                    meta);
            persistAgentRunLangfuseTrace(meta, langfuseRun);

            Conversation attachmentConversation = runtime.conversation();
            if ("subagent".equals(meta.get("run_type"))) {
                attachmentConversation = conversationRepository.getConversationByThreadId(runtimeScopeId);
                validateSubagentAttachmentRoot(attachmentConversation, runtime.conversation(), uid);
            }
            List<Map<String, Object>> threadAttachmentRecords =
                    conversationRepository.getAttachments(attachmentConversation.getId());
            List<Map<String, Object>> requestAttachmentRecords = new ArrayList<>();
            for (Map<String, Object> attachment : threadAttachmentRecords) {
                if (Objects.equals(attachment.get("request_id"), meta.get("request_id"))) {
                    requestAttachmentRecords.add(attachment);
                }
            }
            List<Map<String, Object>> requestAttachments = new ArrayList<>();
            for (Map<String, Object> attachment : requestAttachmentRecords) {
                requestAttachments.add(AttachmentService.serializeAttachment(attachment, threadId));
            }
            List<Map<String, Object>> threadAttachments = new ArrayList<>();
            for (Map<String, Object> attachment : threadAttachmentRecords) {
                threadAttachments.add(AttachmentService.serializeAttachment(attachment, threadId));
            }
            List<Object> messages = new ArrayList<>();
            messages.add(withAttachmentContext(humanMessage, threadAttachments));

            Map<String, Object> initMsg = new LinkedHashMap<String, Object>();
            initMsg.put("role", "user");
            initMsg.put("content", query);
            initMsg.put("type", "human");
            initMsg.put("message_type", messageType);
            Map<String, Object> initMetadata = new LinkedHashMap<String, Object>();
            initMetadata.put("request_id", meta.get("request_id"));
            initMetadata.put("attachments", requestAttachments);
            initMsg.put("extra_metadata", initMetadata);
            if (JsonValues.truthy(imageContent)) {
                initMsg.put("image_content", imageContent);
            }
            sink.accept(makeChunk.make(null, kwargs("status", "init", "meta", meta, "msg", initMsg)));

            // 智能体流式执行期间不访问业务数据库（各仓储方法自带事务边界）。

            GraphStateSnapshot[] finalState = new GraphStateSnapshot[1];
            Map<String, String> protocolMessageIds = new LinkedHashMap<String, String>();
            ModelMessageAuditCollector modelAudit = buildModelMessageAuditCollector(meta, threadId);
            ToolMessageAuditCollector toolAudit = buildToolMessageAuditCollector(modelAudit);

            List<Object> callbacks = new ArrayList<>(langfuseRun.callbacks());
            BaseAgent.StreamOptions options = new BaseAgent.StreamOptions(
                    callbacks, langfuseRun.metadata(), langfuseRun.tags(), onPrepared);

            runtime.backend().streamMessagesWithState(
                    messages,
                    runtime.context(),
                    options,
                    (mode, payload) -> handleChatStreamEvent(
                            mode,
                            payload,
                            meta,
                            threadId,
                            runtime,
                            makeChunk,
                            sink,
                            finalState,
                            protocolMessageIds,
                            modelAudit,
                            toolAudit,
                            traceInfo,
                            accumulatedContent,
                            lastAgentStateSignature));

            if (finalState[0] == null) {
                throw new IllegalArgumentException("Agent 执行流缺少最终 checkpoint");
            }
            traceInfo.putAll(langfuseService.getTraceInfo(langfuseRun));

            boolean interrupted = checkAndHandleInterrupts(finalState[0], makeChunk, meta, threadId, sink);
            String interruptErrorType = null;
            String interruptErrorMessage = null;
            if (interrupted) {
                String[] details = interruptTerminalDetails(meta.get("interrupt_chunk") == null
                        ? ""
                        : String.valueOf(meta.get("interrupt_chunk")));
                interruptErrorType = details[0];
                interruptErrorMessage = details[1];
            }

            meta.put("time_cost", System.nanoTime() / 1_000_000_000.0 - startTime);
            Map<String, Object> agentState =
                    extractAgentState(finalState[0].values(), runtime.context().getString("workdir_path"));

            String finalSignature = agentStateSignature(agentState);
            if (!finalSignature.isEmpty() && !finalSignature.equals(lastAgentStateSignature.get())) {
                lastAgentStateSignature.set(finalSignature);
                sink.accept(makeChunk.make(null, kwargs("status", "agent_state", "agent_state", agentState, "meta", meta)));
            }

            boolean terminalCommitted;
            try {
                terminalCommitted = saveMessagesFromLanggraphState(
                        finalState[0],
                        threadId,
                        traceInfo,
                        JsonValues.truthy(meta.get("run_id")) ? String.valueOf(meta.get("run_id")) : null,
                        JsonValues.truthy(meta.get("request_id")) ? String.valueOf(meta.get("request_id")) : null,
                        JsonValues.truthy(meta.get("worker_id")) ? String.valueOf(meta.get("worker_id")) : null,
                        !interrupted,
                        interrupted,
                        interruptErrorType,
                        interruptErrorMessage,
                        currentRunTokenUsage(agentState, JsonValues.text(meta.get("run_id"))));
            } catch (RuntimeException error) {
                log.error("Error saving messages from LangGraph state: {}", error.getMessage(), error);
                sink.accept(makeChunk.make(
                        null,
                        kwargs(
                                "status", "error",
                                "error_type", "output_persistence_error",
                                "error_message", "最终输出持久化或绑定失败",
                                "meta", meta)));
                return;
            }

            if (interrupted) {
                return;
            }

            sink.accept(makeChunk.make(
                    null, kwargs("status", "finished", "meta", meta, "terminal_committed", terminalCommitted)));

        } catch (UncheckedIOException error) {
            log.warn("Client disconnected, cancelling stream: {}", error.getMessage());
            sink.accept(makeChunk.make(null, kwargs("status", "interrupted", "message", "对话已中断", "meta", meta)));

        } catch (RuntimeException error) {
            log.error("Error streaming messages: {}", error.getMessage(), error);

            String errorMessage = "Error streaming messages: " + error;
            String errorType = "unexpected_error";

            Object fullMessage = accumulatedContent.isEmpty()
                    ? null
                    : AIMessage.of(String.join("", accumulatedContent));

            savePartialMessage(
                    threadId,
                    fullMessage,
                    errorMessage,
                    errorType,
                    traceInfo,
                    JsonValues.truthy(meta.get("run_id")) ? String.valueOf(meta.get("run_id")) : null,
                    JsonValues.truthy(meta.get("request_id")) ? String.valueOf(meta.get("request_id")) : null,
                    JsonValues.truthy(meta.get("worker_id")) ? String.valueOf(meta.get("worker_id")) : null,
                    false);

            sink.accept(makeChunk.make(
                    null,
                    kwargs("status", "error", "error_type", errorType, "error_message", errorMessage, "meta", meta)));
        } finally {
            langfuseService.flushLangfuse();
        }
    }

    /** chat 流的事件分发（对应 {@code async for mode, payload in stream_source} 的循环体）。 */
    private void handleChatStreamEvent(
            String mode,
            Object payload,
            Map<String, Object> meta,
            String threadId,
            ResolvedRuntime runtime,
            ChunkFactory makeChunk,
            Consumer<String> sink,
            GraphStateSnapshot[] finalState,
            Map<String, String> protocolMessageIds,
            ModelMessageAuditCollector modelAudit,
            ToolMessageAuditCollector toolAudit,
            Map<String, Object> traceInfo,
            List<String> accumulatedContent,
            AtomicReference<String> lastAgentStateSignature) {
        if ("checkpoint".equals(mode)) {
            finalState[0] = payload instanceof GraphStateSnapshot snapshot ? snapshot : null;
            return;
        }
        if ("values".equals(mode)) {
            Map<String, Object> values = payload instanceof Map<?, ?> map ? copyMap(map) : new LinkedHashMap<String, Object>();
            Map<String, Object> agentState = extractAgentState(values, runtime.context().getString("workdir_path"));
            String signature = agentStateSignature(agentState);
            if (!signature.isEmpty() && !signature.equals(lastAgentStateSignature.get())) {
                lastAgentStateSignature.set(signature);
                sink.accept(makeChunk.make(null, kwargs("status", "agent_state", "agent_state", agentState, "meta", meta)));
            }
            return;
        }
        if ("custom".equals(mode)) {
            Map<String, Object> compression = contextCompressionPayload(payload);
            if (compression != null) {
                sink.accept(makeChunk.make(
                        null, kwargs("status", "context_compression", "compression", compression, "meta", meta)));
            }
            return;
        }
        if ("stream_event".equals(mode)) {
            Map<String, Object> eventPayload = payload instanceof Map<?, ?> map
                    ? copyMap(map)
                    : new LinkedHashMap<String, Object>();
            Object eventNamespace = eventPayload.get("namespace");
            Object eventThreadId = eventPayload.get("thread_id");
            if (toolAudit != null
                    && "tools".equals(eventPayload.get("method"))
                    && isRootToolAuditEvent(eventPayload, threadId)) {
                toolAudit.consume(eventPayload);
            }
            Map<String, Object> extra = kwargs(
                    "status", "stream_event",
                    "event", eventPayload,
                    "namespace", eventNamespace,
                    "meta", meta);
            extra.put("thread_id", eventThreadId);
            sink.accept(makeChunk.make(null, extra));
            return;
        }

        Map<String, Object> pair = payload instanceof Map<?, ?> map ? copyMap(map) : new LinkedHashMap<String, Object>();
        Object message = pair.get("message");
        Object rawMetadata = pair.get("metadata");
        Map<String, Object> metadata = rawMetadata instanceof Map<?, ?> metadataMap
                ? copyMap(metadataMap)
                : new LinkedHashMap<String, Object>();
        List<String> namespace = metadataNamespace(metadata);
        String chunkThreadId = ThreadUtils.extractThreadId(metadata, namespace.isEmpty() ? threadId : null);
        if (!namespace.isEmpty() && !JsonValues.truthy(chunkThreadId)) {
            return;
        }

        boolean isSubagentChunk = JsonValues.truthy(chunkThreadId) && !chunkThreadId.equals(threadId);
        if (modelAudit != null && !isSubagentChunk) {
            modelAudit.consume(message, metadata);
        }
        List<Map<String, Object>> streamEvents = messagePayloadEvents(
                message, metadata, namespace, chunkThreadId, protocolMessageIds);

        for (Map<String, Object> streamEvent : streamEvents) {
            String content = streamEventResponse(streamEvent);
            if (!isSubagentChunk && !content.isEmpty()) {
                traceInfo.putAll(langfuseService.getTraceInfo(runtimeTraceContext(traceInfo)));
                accumulatedContent.add(content);
            }

            Map<String, Object> extra = kwargs(
                    "content", content,
                    "stream_event", streamEvent,
                    "metadata", metadata,
                    "status", "loading");
            extra.put("thread_id", chunkThreadId);
            sink.accept(makeChunk.make(null, extra));
        }
    }

    /**
     * {@code get_trace_info(langfuse_run)} 的取值入口。
     *
     * <p>参考实现按捕获的 {@code langfuse_run} 对象取；Java 侧在事件回调里没有该局部变量，
     * 故通过运行时上下文透传（见 {@link #TRACE_CONTEXT_KEY}）。
     */
    @SuppressWarnings("unchecked")
    private static LangfuseService.LangfuseRunContext runtimeTraceContext(Map<String, Object> traceInfo) {
        Object value = traceInfo.get(TRACE_CONTEXT_KEY);
        return value instanceof LangfuseService.LangfuseRunContext context ? context : null;
    }

    /** {@code trace_info} 里承载 Langfuse 运行上下文的内部键（Java 侧透传用，不外发）。 */
    static final String TRACE_CONTEXT_KEY = "__langfuse_run_context__";

    /**
     * 执行已持久化的 resume 输入，沿用 worker 固化的配置快照
     * （对应 {@code stream_agent_resume}）。
     */
    public void streamAgentResume(
            String threadId,
            Object resumeInput,
            Map<String, Object> metaInput,
            User currentUser,
            PreparedRunExecution preparedExecution,
            Runnable onPrepared,
            Consumer<String> sink) {
        double startTime = System.nanoTime() / 1_000_000_000.0;
        Map<String, Object> meta = new LinkedHashMap<String, Object>(metaInput == null ? Map.of() : metaInput);
        ChunkFactory makeChunk = chunkFactory(meta, threadId);

        if (!JsonValues.truthy(threadId) || !JsonValues.truthy(meta.get("request_id"))) {
            throw new IllegalArgumentException("执行需要已持久化的 thread_id 和 request_id");
        }
        sink.accept(makeChunk.make(null, kwargs("status", "init", "meta", meta)));

        ResolvedRuntime runtime;
        try {
            runtime = resolveAgentRuntime(currentUser, null, threadId, preparedExecution, "main");
        } catch (IllegalArgumentException error) {
            sink.accept(makeChunk.make(
                    null,
                    kwargs(
                            "status", "error",
                            "error_type", "invalid_agent",
                            "error_message", error.getMessage(),
                            "meta", meta)));
            return;
        }

        Map<String, Object> resumeCommand = new LinkedHashMap<String, Object>();
        resumeCommand.put("resume", resumeInput);

        meta.put("agent_slug", runtime.agentItem().getSlug());
        meta.put("backend_id", runtime.agentItem().getBackendId());
        LangfuseService.LangfuseRunContext langfuseRun = buildLangfuseRunContext(
                currentUser,
                threadId,
                runtime.agentItem().getSlug(),
                runtime.agentItem().getBackendId(),
                JsonValues.text(meta.get("request_id")),
                "agent_chat_resume",
                "resume",
                meta);
        try {
            persistAgentRunLangfuseTrace(meta, langfuseRun);
        } catch (RuntimeException error) {
            log.error("Error persisting langfuse trace during resume: {}", error.getMessage(), error);
        }

        Map<String, Object> traceInfo = new LinkedHashMap<String, Object>();
        traceInfo.put(TRACE_CONTEXT_KEY, langfuseRun);
        AtomicReference<String> lastAgentStateSignature = new AtomicReference<>("");
        GraphStateSnapshot[] finalState = new GraphStateSnapshot[1];

        try {
            List<Object> callbacks = new ArrayList<>(langfuseRun.callbacks());
            BaseAgent.StreamOptions options = new BaseAgent.StreamOptions(
                    callbacks, langfuseRun.metadata(), langfuseRun.tags(), onPrepared);
            Map<String, String> protocolMessageIds = new LinkedHashMap<String, String>();
            ModelMessageAuditCollector modelAudit = buildModelMessageAuditCollector(meta, threadId);
            ToolMessageAuditCollector toolAudit = buildToolMessageAuditCollector(modelAudit);

            runtime.backend().streamResumeWithState(
                    resumeCommand,
                    runtime.context(),
                    options,
                    (mode, payload) -> handleResumeStreamEvent(
                            mode,
                            payload,
                            meta,
                            threadId,
                            runtime,
                            makeChunk,
                            sink,
                            finalState,
                            protocolMessageIds,
                            modelAudit,
                            toolAudit,
                            traceInfo,
                            lastAgentStateSignature));

            if (finalState[0] == null) {
                throw new IllegalArgumentException("Agent 执行流缺少最终 checkpoint");
            }

            boolean interrupted = false;
            String interruptErrorType = null;
            String interruptErrorMessage = null;
            Object interruptInfo = finalState[0].extractInterruptInfo();
            if (interruptInfo != null) {
                Map<String, Object> pendingInterrupt = buildPendingInterruptPayload(interruptInfo, threadId);
                Object status = pendingInterrupt.remove("status");
                meta.put("interrupt", pendingInterrupt);
                Map<String, Object> extra = kwargs("status", status, "meta", meta);
                extra.putAll(pendingInterrupt);
                String chunk = makeChunk.make(null, extra);
                interrupted = true;
                String[] details = interruptTerminalDetails(chunk);
                interruptErrorType = details[0];
                interruptErrorMessage = details[1];
                sink.accept(chunk);
            }

            meta.put("time_cost", System.nanoTime() / 1_000_000_000.0 - startTime);
            Map<String, Object> agentState =
                    extractAgentState(finalState[0].values(), runtime.context().getString("workdir_path"));

            String finalSignature = agentStateSignature(agentState);
            if (!finalSignature.isEmpty() && !finalSignature.equals(lastAgentStateSignature.get())) {
                sink.accept(makeChunk.make(null, kwargs("status", "agent_state", "agent_state", agentState, "meta", meta)));
            }

            boolean terminalCommitted;
            try {
                terminalCommitted = saveMessagesFromLanggraphState(
                        finalState[0],
                        threadId,
                        traceInfo,
                        JsonValues.truthy(meta.get("run_id")) ? String.valueOf(meta.get("run_id")) : null,
                        JsonValues.truthy(meta.get("request_id")) ? String.valueOf(meta.get("request_id")) : null,
                        JsonValues.truthy(meta.get("worker_id")) ? String.valueOf(meta.get("worker_id")) : null,
                        !interrupted,
                        interrupted,
                        interruptErrorType,
                        interruptErrorMessage,
                        currentRunTokenUsage(agentState, JsonValues.text(meta.get("run_id"))));
            } catch (RuntimeException error) {
                log.error("Error saving messages from LangGraph state: {}", error.getMessage(), error);
                sink.accept(makeChunk.make(
                        null,
                        kwargs(
                                "status", "error",
                                "error_type", "output_persistence_error",
                                "error_message", "最终输出持久化或绑定失败",
                                "meta", meta)));
                return;
            }

            if (interrupted) {
                return;
            }

            sink.accept(makeChunk.make(
                    null, kwargs("status", "finished", "meta", meta, "terminal_committed", terminalCommitted)));

        } catch (UncheckedIOException error) {
            log.warn("Client disconnected during resume: {}", error.getMessage());
            sink.accept(makeChunk.make(null, kwargs("status", "interrupted", "message", "对话恢复已中断", "meta", meta)));

        } catch (RuntimeException error) {
            log.error("Error during resume: {}", error.getMessage(), error);

            savePartialMessage(
                    threadId,
                    null,
                    "Error during resume: " + error,
                    "resume_error",
                    traceInfo,
                    JsonValues.truthy(meta.get("run_id")) ? String.valueOf(meta.get("run_id")) : null,
                    JsonValues.truthy(meta.get("request_id")) ? String.valueOf(meta.get("request_id")) : null,
                    JsonValues.truthy(meta.get("worker_id")) ? String.valueOf(meta.get("worker_id")) : null,
                    false);

            sink.accept(makeChunk.make(
                    null, kwargs("message", "Error during resume: " + error, "status", "error")));
        } finally {
            langfuseService.flushLangfuse();
        }
    }

    /** resume 流的事件分发（对应 {@code stream_agent_resume} 的循环体）。 */
    private void handleResumeStreamEvent(
            String mode,
            Object payload,
            Map<String, Object> meta,
            String threadId,
            ResolvedRuntime runtime,
            ChunkFactory makeChunk,
            Consumer<String> sink,
            GraphStateSnapshot[] finalState,
            Map<String, String> protocolMessageIds,
            ModelMessageAuditCollector modelAudit,
            ToolMessageAuditCollector toolAudit,
            Map<String, Object> traceInfo,
            AtomicReference<String> lastAgentStateSignature) {
        if ("checkpoint".equals(mode)) {
            finalState[0] = payload instanceof GraphStateSnapshot snapshot ? snapshot : null;
            return;
        }
        if ("values".equals(mode)) {
            Map<String, Object> values = payload instanceof Map<?, ?> map ? copyMap(map) : new LinkedHashMap<String, Object>();
            Map<String, Object> agentState = extractAgentState(values, runtime.context().getString("workdir_path"));
            String signature = agentStateSignature(agentState);
            if (!signature.isEmpty() && !signature.equals(lastAgentStateSignature.get())) {
                lastAgentStateSignature.set(signature);
                sink.accept(makeChunk.make(null, kwargs("status", "agent_state", "agent_state", agentState, "meta", meta)));
            }
            return;
        }
        if ("stream_event".equals(mode)) {
            Map<String, Object> eventPayload = payload instanceof Map<?, ?> map
                    ? copyMap(map)
                    : new LinkedHashMap<String, Object>();
            Object eventNamespace = eventPayload.get("namespace");
            Object eventThreadId = eventPayload.get("thread_id");
            if (toolAudit != null
                    && "tools".equals(eventPayload.get("method"))
                    && isRootToolAuditEvent(eventPayload, threadId)) {
                toolAudit.consume(eventPayload);
            }
            Map<String, Object> extra = kwargs(
                    "status", "stream_event",
                    "event", eventPayload,
                    "namespace", eventNamespace,
                    "meta", meta);
            extra.put("thread_id", eventThreadId);
            sink.accept(makeChunk.make(null, extra));
            return;
        }
        if ("custom".equals(mode)) {
            Map<String, Object> compression = contextCompressionPayload(payload);
            if (compression != null) {
                sink.accept(makeChunk.make(
                        null, kwargs("status", "context_compression", "compression", compression, "meta", meta)));
            }
            return;
        }
        if (!"messages".equals(mode)) {
            return;
        }

        Map<String, Object> pair = payload instanceof Map<?, ?> map ? copyMap(map) : new LinkedHashMap<String, Object>();
        Object message = pair.get("message");
        Object rawMetadata = pair.get("metadata");
        Map<String, Object> metadata = rawMetadata instanceof Map<?, ?> metadataMap
                ? copyMap(metadataMap)
                : new LinkedHashMap<String, Object>();
        List<String> namespace = metadataNamespace(metadata);
        String chunkThreadId = ThreadUtils.extractThreadId(metadata, namespace.isEmpty() ? threadId : null);
        if (!namespace.isEmpty() && !JsonValues.truthy(chunkThreadId)) {
            return;
        }

        if (Objects.equals(chunkThreadId, threadId)) {
            traceInfo.putAll(langfuseService.getTraceInfo(runtimeTraceContext(traceInfo)));
            if (modelAudit != null) {
                modelAudit.consume(message, metadata);
            }
        }

        List<Map<String, Object>> streamEvents = messagePayloadEvents(
                message, metadata, namespace, chunkThreadId, protocolMessageIds);
        for (Map<String, Object> streamEvent : streamEvents) {
            String content = streamEventResponse(streamEvent);
            Map<String, Object> extra = kwargs(
                    "content", content,
                    "stream_event", streamEvent,
                    "metadata", metadata,
                    "status", "loading");
            extra.put("thread_id", chunkThreadId);
            sink.accept(makeChunk.make(null, extra));
        }
    }

    // =========================================================================
    // === Agent state 面板 ===
    // =========================================================================

    /** 序列化 checkpoint 快照里的消息列表（对应 {@code _serialize_state_messages}）。 */
    private static List<Map<String, Object>> serializeStateMessages(Map<String, Object> values) {
        Object messages = values == null ? null : values.get("messages");
        if (!(messages instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (Object message : list) {
            if (message instanceof ModelDumpable dumpable) {
                serialized.add(dumpable.modelDump());
            } else if (message instanceof Map<?, ?> map) {
                serialized.add(copyMap(map));
            } else {
                Map<String, Object> fallback = new LinkedHashMap<String, Object>();
                fallback.put("type", "unknown");
                fallback.put("content", String.valueOf(message));
                serialized.add(fallback);
            }
        }
        return serialized;
    }

    /** checkpoint 快照读取结果（对应 {@code _read_checkpoint_state} 的二元组）。 */
    public record CheckpointState(Map<String, Object> values, Object interruptInfo) {}

    /**
     * 读取完整 checkpoint 快照与同批中断；调用方先校验线程可见性
     * （对应 {@code _read_checkpoint_state}）。
     *
     * <p>不经构图，直读进程级 checkpointer（{@link BaseAgent#readCheckpointSnapshot}，见类注释第 5 条）；
     * Agent 解析保留为"该对话的智能体仍存在且可见"的校验。
     */
    public CheckpointState readCheckpointState(String uid, String threadId, Conversation conversation, User user) {
        Agent agentItem = agentRepository.getVisibleBySlug(
                conversation.getAgentId(), PermissionSubject.of(user), AgentRepository.AgentEntryKind.ANY);
        if (agentItem == null) {
            throw new IllegalStateException("智能体不存在，无法读取 checkpoint: " + conversation.getAgentId());
        }
        BaseAgent backend = agentManager.getAgent(agentItem.getBackendId());
        if (backend == null) {
            throw new IllegalStateException("智能体后端不存在，无法读取 checkpoint: " + agentItem.getBackendId());
        }
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        Map<String, Object> configurable = new LinkedHashMap<String, Object>();
        configurable.put("uid", uid);
        configurable.put("thread_id", threadId);
        configurable.put("checkpoint_ns", "");
        config.put("configurable", configurable);

        // 不经构图：参考实现的读取只依赖进程级 checkpointer（_read_checkpoint_state），
        // 构图会连带 sandbox / 工具 / skill 解析，且要求 context 已 _runtime_prepared。
        GraphStateSnapshot snapshot = backend.readCheckpointSnapshot(config);
        // 面板只展示完整快照，pending writes 中的业务增量留给执行图合并。
        return new CheckpointState(snapshot.values(), snapshot.extractInterruptInfo());
    }

    /**
     * 线程的 agent state 面板视图（对应 {@code get_agent_state_view}）。
     *
     * <p>线程不存在/无权访问时抛 {@code HTTPException(404)}（本工程映射为
     * {@link ApiHttpException#notFound}）。
     */
    public Map<String, Object> getAgentStateView(
            String threadId,
            User currentUser,
            boolean includeMessages,
            boolean includeRelations) {
        String currentUid = String.valueOf(currentUser.getUid());
        Conversation conversation = conversationRepository.getConversationByThreadId(threadId);
        if (conversation != null) {
            if (!Objects.equals(conversation.getUid(), currentUid) || "deleted".equals(conversation.getStatus())) {
                throw ApiHttpException.notFound("对话线程不存在");
            }

            AgentRun latestRun = agentRunRepository.getLatestRunByThreadForUser(threadId, currentUid);
            String workdirPath = workdirService.resolveConversationWorkdirPath(conversation, currentUid);
            String runtimeWorkdir = BackendPaths.runtimeWorkdirPath(workdirPath);
            CheckpointState checkpoint = readCheckpointState(currentUid, threadId, conversation, currentUser);

            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("agent_state", extractAgentState(checkpoint.values(), runtimeWorkdir));

            if (latestRun != null
                    && "interrupted".equals(latestRun.getStatus())
                    && checkpoint.interruptInfo() != null) {
                Map<String, Object> interrupt =
                        new LinkedHashMap<String, Object>(buildPendingInterruptPayload(checkpoint.interruptInfo(), threadId));
                interrupt.put("run_id", latestRun.getId());
                response.put("interrupt", interrupt);
            }
            if (includeRelations) {
                SubagentThread relation =
                        subagentThreadRepository.getByChildConversationForUser(conversation.getId(), currentUid);
                if (relation != null) {
                    Conversation parentConversation =
                            conversationRepository.getConversationById(relation.getParentConversationId());
                    if (parentConversation == null
                            || !Objects.equals(parentConversation.getUid(), currentUid)
                            || "deleted".equals(parentConversation.getStatus())) {
                        throw ApiHttpException.notFound("父对话线程不存在");
                    }
                    response.put("parent_thread_id", parentConversation.getThreadId());
                    response.put("subagent_thread", relation.toDict());
                    AgentRun latestSubagentRun =
                            agentRunRepository.getLatestSubagentRunByThreadForUser(threadId, currentUid);
                    if (latestSubagentRun != null) {
                        try {
                            response.put(
                                    "subagent_run",
                                    SubagentRunService.serializeSubagentRunState(latestSubagentRun));
                        } catch (IllegalArgumentException error) {
                            log.error(
                                    "子智能体运行记录格式异常: thread_id={}, run_id={}, {}",
                                    threadId,
                                    latestSubagentRun.getId(),
                                    error.getMessage());
                            throw new ApiHttpException(500, "子智能体运行记录格式异常");
                        }
                    }
                }
            }
            if (includeMessages) {
                response.put("messages", serializeStateMessages(checkpoint.values()));
            }
            return response;
        }

        // 子智能体线程在创建时必然同时写入子对话与线程关系（见 SubagentRunService.start），
        // 由上面的 conversation 分支统一处理；走到这里说明该 thread 没有对应对话，即线程不存在。
        throw ApiHttpException.notFound("对话线程不存在");
    }

    // =========================================================================
    // === JSON 辅助 ===
    // =========================================================================

    /** 解析 JSON 列 → Map（非对象或空值返回空表）。 */
    private static Map<String, Object> parseJsonMap(String raw) {
        return new LinkedHashMap<String, Object>(parseJsonObject(raw));
    }

    /** 解析 JSON 列 → JSONObject（非对象或空值返回空对象）。 */
    private static JSONObject parseJsonObject(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            JSONObject parsed = JSON.parseObject(raw);
            return parsed == null ? new JSONObject() : parsed;
        } catch (RuntimeException error) {
            return new JSONObject();
        }
    }
}
