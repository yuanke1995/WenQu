package com.wisesoft.wenqu.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.wisesoft.wenqu.common.BizException;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.MessageFeedback;
import com.wisesoft.wenqu.models.ModelConstants;
import com.wisesoft.wenqu.models.Project;
import com.wisesoft.wenqu.models.ToolCall;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.models.ModelUtils;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository.AuditPage;
import com.wisesoft.wenqu.repositories.ConversationRepository.MessageWithRelations;
import com.wisesoft.wenqu.repositories.ConversationRepository.RunTracePage;
import com.wisesoft.wenqu.repositories.ProjectRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.workspace.Workdir;
import com.wisesoft.wenqu.workspace.WorkspacePaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 对话线程服务（services/conversation_service.py 全量移植）。
 *
 * <p>必要替换：
 * <ul>
 *   <li>FastAPI {@code HTTPException(status, detail)} → {@link BizException} 同码。
 *   <li>SQLAlchemy 显式 {@code db.commit()/rollback()} → 仓储方法各自的 Spring 事务；
 *       IntegrityError 竞态恢复改为捕获 {@link DataIntegrityViolationException} 后走同一恢复路径
 *       （服务方法不整体加 @Transactional，保持参考实现的分段提交粒度——能力差异标注）。
 *   <li>{@code conv.project.workdir_path}（SQLAlchemy relationship）→ 显式
 *       {@code projectRepository.getForUser(...)} 联查；缺失时传 null（参考实现会
 *       AttributeError，此处不崩——防御差异）。
 * </ul>
 */
@Slf4j
@Service
public class ConversationService {

    public static final int MESSAGE_AUDIT_LIMIT = 500;
    public static final int AGENT_RUN_TRACE_LIMIT = 500;
    public static final Set<String> MODEL_HISTORY_METADATA_KEYS = Set.of(
            "attachments", "source", "error_type", "error_message", "langfuse_trace_id", "model");

    /** 参考实现 role → type 映射（get_thread_history_view 内）。 */
    private static final Map<String, String> ROLE_TYPE_MAP = Map.of(
            "user", "human", "assistant", "ai", "tool", "tool", "system", "system");

    private final ConversationRepository conversationRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentRepository agentRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final WorkdirService workdirService;
    private final ProjectService projectService;

    public ConversationService(
            ConversationRepository conversationRepository,
            AgentRunRepository agentRunRepository,
            AgentRepository agentRepository,
            ProjectRepository projectRepository,
            UserRepository userRepository,
            WorkdirService workdirService,
            ProjectService projectService) {
        this.conversationRepository = conversationRepository;
        this.agentRunRepository = agentRunRepository;
        this.agentRepository = agentRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.workdirService = workdirService;
        this.projectService = projectService;
    }

    public Conversation requireUserConversation(String threadId, String uid) {
        Conversation conversation = conversationRepository.getConversationByThreadId(threadId);
        if (conversation == null
                || !String.valueOf(conversation.getUid()).equals(uid)
                || "deleted".equals(conversation.getStatus())) {
            throw new BizException(404, "对话线程不存在");
        }
        return conversation;
    }

    /** 返回当前用户线程内最新的有界 Model/Tool 审计时间线（get_thread_message_audits_view）。 */
    public Map<String, Object> getThreadMessageAuditsView(String threadId, String currentUid) {
        Conversation conversation = requireUserConversation(threadId, currentUid);
        AuditPage audits =
                conversationRepository.listMessageAudits(conversation.getId(), MESSAGE_AUDIT_LIMIT);
        RunTracePage runTraces =
                conversationRepository.listAgentRunsForTrace(conversation.getId(), AGENT_RUN_TRACE_LIMIT);

        List<Map<String, Object>> auditList = new ArrayList<>();
        for (MessageWithRelations message : audits.messages()) {
            auditList.add(serializeMessageAudit(message));
        }
        List<Map<String, Object>> runList = new ArrayList<>();
        for (AgentRun run : runTraces.runs()) {
            runList.add(serializeRunTrace(run));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("audits", auditList);
        result.put("runs", runList);
        result.put("runs_truncated", runTraces.truncated());
        result.put("truncated", audits.truncated());
        return result;
    }

    /** 创建对话线程（create_thread_view：幂等重放 + 隐式 Project + 竞态恢复）。 */
    public Map<String, Object> createThreadView(
            String agentSlug,
            String requestId,
            String title,
            Map<String, Object> metadata,
            String projectId,
            String currentUid) {
        if (metadata != null && metadata.containsKey("attachments")) {
            throw new BizException(400, "metadata.attachments 是服务端保留字段");
        }

        User currentUser = userRepository.getByUid(currentUid);
        if (currentUser == null) {
            throw new BizException(404, "用户不存在");
        }

        Agent agentItem =
                agentRepository.getVisibleBySlug(agentSlug, PermissionSubject.of(currentUser), AgentRepository.AgentEntryKind.MAIN);
        if (agentItem == null) {
            throw new BizException(404, "智能体不存在");
        }

        String normalizedRequestId = requestId == null || requestId.strip().isEmpty() ? null : requestId.strip();
        if (normalizedRequestId != null) {
            Conversation existing =
                    conversationRepository.getConversationByCreationRequestId(currentUid, normalizedRequestId);
            if (existing != null) {
                Project existingProject = projectRepository.getForUser(existing.getProjectId(), currentUid);
                requireMatchingThreadCreationIntent(existing, existingProject, agentItem.getSlug(), projectId);
                WorkdirService.WorkdirBinding workdirBinding =
                        workdirService.workdirBindingFromProject(existing, currentUid, existingProject);
                workdirService.ensureConversationWorkdirAvailable(existing, currentUid, workdirBinding);
                return serializeThread(
                        existing, "done", workdirBinding.workdirPath());
            }
        }

        String threadId = UUID.randomUUID().toString();
        Map<String, Object> threadMetadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        threadMetadata.put("backend_id", agentItem.getBackendId());
        Project project;
        if (projectId != null) {
            project = projectRepository.lockActiveSelectableForUser(projectId, currentUid);
            if (project == null) {
                throw new BizException(404, "Project 不存在");
            }
            try {
                Workdir.openExisting(currentUid, project.getWorkdirPath());
            } catch (RuntimeException exc) {
                // 参考实现 except (FileNotFoundError, NotADirectoryError, PermissionError, OSError, ValueError)
                throw new BizException(409, "项目目录不可用");
            }
        } else {
            try {
                project = projectService.createImplicitProject(
                        currentUid, normalizedRequestId == null ? null : "thread:" + normalizedRequestId);
            } catch (DataIntegrityViolationException exc) {
                // 参考实现 rollback 后按幂等键恢复
                if (normalizedRequestId == null) {
                    throw exc;
                }
                project = projectRepository.getByIdempotencyKey("thread:" + normalizedRequestId, currentUid);
                if (project == null || !"implicit".equals(project.getSelectionStatus())) {
                    throw new BizException(409, "request_id 已用于其他 Conversation 创建意图");
                }
            }
        }
        Conversation conversation;
        try {
            conversation = conversationRepository.addConversation(
                    currentUid,
                    agentItem.getSlug(),
                    title == null || title.isEmpty() ? "新的对话" : title,
                    threadId,
                    threadMetadata,
                    project.getId(),
                    normalizedRequestId);
        } catch (DataIntegrityViolationException exc) {
            if (normalizedRequestId == null) {
                throw exc;
            }
            conversation =
                    conversationRepository.getConversationByCreationRequestId(currentUid, normalizedRequestId);
            if (conversation == null) {
                Project implicitProject =
                        projectRepository.getByIdempotencyKey("thread:" + normalizedRequestId, currentUid);
                if (implicitProject == null || projectId != null) {
                    throw exc;
                }
                project = implicitProject;
                conversation = conversationRepository.addConversation(
                        currentUid,
                        agentItem.getSlug(),
                        title == null || title.isEmpty() ? "新的对话" : title,
                        threadId,
                        threadMetadata,
                        project.getId(),
                        normalizedRequestId);
            }
            Project existingProject = projectRepository.getForUser(conversation.getProjectId(), currentUid);
            requireMatchingThreadCreationIntent(conversation, existingProject, agentItem.getSlug(), projectId);
            project = existingProject;
        }

        WorkdirService.WorkdirBinding workdirBinding =
                workdirService.workdirBindingFromProject(conversation, currentUid, project);
        try {
            if (workdirBinding.materializeManaged()) {
                WorkspacePaths.ensureBoundUserWorkdir(workdirBinding.uid(), workdirBinding.workdirPath());
            }
        } catch (RuntimeException exc) {
            // 参考实现 except (FileNotFoundError, NotADirectoryError, OSError, ValueError) → 400 str(exc)
            throw new BizException(400, String.valueOf(exc.getMessage()));
        }

        return serializeThread(conversation, "done", workdirBinding.workdirPath());
    }

    /** 列出对话线程（list_threads_view）。 */
    public List<Map<String, Object>> listThreadsView(String agentSlug, String currentUid, Integer limit, int offset) {
        List<Conversation> conversations = conversationRepository.listConversations(
                currentUid, agentSlug, "active", limit, offset, ConversationRepository.INVOCATION_CONVERSATION_SOURCES);

        Map<String, String[]> runMap =
                agentRunRepository.getLatestTopLevelRunsForThreads(currentUid, threadIds(conversations));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Conversation conv : conversations) {
            String[] latestRun = runMap.get(conv.getThreadId());
            String runId = latestRun == null ? null : latestRun[0];
            String runStatus = latestRun == null ? null : latestRun[1];
            // 参考实现经 relationship 取 conv.project.workdir_path；Java 显式联查
            Project project = projectRepository.getForUser(conv.getProjectId(), currentUid);
            items.add(serializeThread(
                    conv,
                    threadStatus(runId, runStatus, conv.getLastViewedRunId()),
                    project == null ? null : project.getWorkdirPath()));
        }
        return items;
    }

    private static List<String> threadIds(List<Conversation> conversations) {
        List<String> ids = new ArrayList<>();
        for (Conversation conv : conversations) {
            ids.add(conv.getThreadId());
        }
        return ids;
    }

    /** 按消息内容搜索对话线程（search_threads_view）。 */
    public Map<String, Object> searchThreadsView(
            String query, String agentId, String currentUid, int limit, int offset) {
        String normalizedQuery = query == null ? "" : query.strip();
        if (normalizedQuery.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("items", List.of());
            empty.put("has_more", false);
            empty.put("limit", limit);
            empty.put("offset", offset);
            return empty;
        }

        ConversationRepository.ConversationSearchPage page =
                conversationRepository.searchConversationsByMessageContent(
                        currentUid,
                        normalizedQuery,
                        agentId,
                        limit,
                        offset,
                        ConversationRepository.INVOCATION_CONVERSATION_SOURCES);

        List<Map<String, Object>> items = new ArrayList<>();
        for (ConversationRepository.ConversationSearchItem item : page.items()) {
            Conversation conv = item.conversation();
            List<Map<String, Object>> snippets = new ArrayList<>();
            for (ConversationRepository.MessageSnippet snippet : item.snippets()) {
                Map<String, Object> snippetMap = new LinkedHashMap<>();
                snippetMap.put("message_id", snippet.messageId());
                snippetMap.put("content", snippet.content() == null ? "" : snippet.content());
                snippetMap.put("created_at", DateTimeUtils.formatUtcDatetime(snippet.createdAt()));
                snippets.add(snippetMap);
            }
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("id", conv.getThreadId());
            itemMap.put("thread_id", conv.getThreadId());
            itemMap.put("uid", conv.getUid());
            itemMap.put("agent_id", conv.getAgentId());
            itemMap.put("title", conv.getTitle());
            itemMap.put("is_pinned", Boolean.TRUE.equals(conv.getIsPinned()));
            itemMap.put("created_at", DateTimeUtils.formatUtcDatetime(conv.getCreatedAt()));
            itemMap.put("updated_at", DateTimeUtils.formatUtcDatetime(conv.getUpdatedAt()));
            itemMap.put("metadata", parseMetadata(conv.getExtraMetadata()));
            itemMap.put("matched_count", item.matchedCount());
            itemMap.put("message_id", item.messageId());
            itemMap.put("latest_match_at", DateTimeUtils.formatUtcDatetime(item.latestMatchAt()));
            itemMap.put("snippets", snippets);
            items.add(itemMap);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("has_more", page.hasMore());
        result.put("limit", limit);
        result.put("offset", offset);
        return result;
    }

    /** 删除对话线程（delete_thread_view，软删）。 */
    public Map<String, Object> deleteThreadView(String threadId, String currentUid) {
        requireUserConversation(threadId, currentUid);
        boolean deleted = conversationRepository.deleteConversation(threadId, true);
        if (!deleted) {
            throw new BizException(404, "对话线程不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "删除成功");
        return result;
    }

    /** 更新对话线程（update_thread_view：置顶/标题/审批模式）。 */
    public Map<String, Object> updateThreadView(
            String threadId, String title, Boolean isPinned, String toolApprovalMode, String currentUid) {
        requireUserConversation(threadId, currentUid);
        Map<String, Object> metadata =
                toolApprovalMode != null ? Map.of("tool_approval_mode", toolApprovalMode) : null;
        Conversation updatedConv = conversationRepository.updateConversation(threadId, title, null, metadata, isPinned);
        if (updatedConv == null) {
            throw new BizException(500, "更新失败");
        }

        Map<String, String[]> runMap = agentRunRepository.getLatestTopLevelRunsForThreads(
                currentUid, List.of(updatedConv.getThreadId()));
        String[] latestRun = runMap.get(updatedConv.getThreadId());
        String runId = latestRun == null ? null : latestRun[0];
        String runStatus = latestRun == null ? null : latestRun[1];

        return serializeThread(
                updatedConv,
                threadStatus(runId, runStatus, updatedConv.getLastViewedRunId()),
                null);
    }

    /** 记录用户已查看该线程的最新顶层 run（mark_thread_viewed_view）。 */
    public Map<String, Object> markThreadViewedView(String threadId, String currentUid) {
        Conversation conversation = requireUserConversation(threadId, currentUid);

        Map<String, String[]> runMap =
                agentRunRepository.getLatestTopLevelRunsForThreads(currentUid, List.of(threadId));
        String[] latestRun = runMap.get(threadId);
        String runId = latestRun == null ? null : latestRun[0];
        String runStatus = latestRun == null ? null : latestRun[1];

        if (runId != null && ModelConstants.AGENT_RUN_TERMINAL_STATUSES.contains(runStatus)) {
            conversation = conversationRepository.markThreadViewed(threadId, runId);
        }

        return serializeThread(
                conversation,
                threadStatus(runId, runStatus, conversation.getLastViewedRunId()),
                null);
    }

    /** 读取线程、Run 与历史消息（get_thread_history_view）。 */
    public Map<String, Object> getThreadHistoryView(String threadId, String currentUid) {
        Conversation conversation = conversationRepository.getConversationByThreadId(threadId);
        if (conversation == null
                || !String.valueOf(conversation.getUid()).equals(currentUid)
                || "deleted".equals(conversation.getStatus())) {
            throw new BizException(404, "对话线程不存在");
        }

        List<MessageWithRelations> messagesWithRelations =
                conversationRepository.getMessages(conversation.getId(), null, 0);
        messagesWithRelations.removeIf(message -> {
            Message msg = message.message();
            return "user".equals(msg.getRole())
                    && ("queued".equals(msg.getDeliveryStatus())
                            || "cancelled".equals(msg.getDeliveryStatus())
                            || "rejected".equals(msg.getDeliveryStatus()));
        });

        List<AgentRun> runs = conversationRepository.listAgentRunsForHistory(conversation.getId());
        Map<String, LocalDateTime> runCreatedAt = new LinkedHashMap<>();
        for (AgentRun run : runs) {
            runCreatedAt.put(run.getId(), run.getCreatedAt());
        }
        AgentRun latestRun = null;
        for (int i = runs.size() - 1; i >= 0; i--) {
            AgentRun run = runs.get(i);
            if ("chat".equals(run.getRunType()) || "resume".equals(run.getRunType())) {
                latestRun = run;
                break;
            }
        }
        Map<String, Object> thread = serializeThread(
                conversation,
                threadStatus(
                        latestRun == null ? null : latestRun.getId(),
                        latestRun == null ? null : latestRun.getStatus(),
                        conversation.getLastViewedRunId()),
                null);

        // 参考实现：按 (run 创建时间, user 优先, created_at, id) 排序
        messagesWithRelations.sort((left, right) -> {
            Message l = left.message();
            Message r = right.message();
            LocalDateTime lKey = l.getRunId() != null && runCreatedAt.containsKey(l.getRunId())
                    ? runCreatedAt.get(l.getRunId())
                    : l.getCreatedAt();
            LocalDateTime rKey = r.getRunId() != null && runCreatedAt.containsKey(r.getRunId())
                    ? runCreatedAt.get(r.getRunId())
                    : r.getCreatedAt();
            int cmp = lKey.compareTo(rKey);
            if (cmp != 0) {
                return cmp;
            }
            int lRole = "user".equals(l.getRole()) ? 0 : 1;
            int rRole = "user".equals(r.getRole()) ? 0 : 1;
            cmp = Integer.compare(lRole, rRole);
            if (cmp != 0) {
                return cmp;
            }
            cmp = l.getCreatedAt().compareTo(r.getCreatedAt());
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(l.getId(), r.getId());
        });

        Set<String> messageRequestIds = new HashSet<>();
        for (MessageWithRelations wrapper : messagesWithRelations) {
            Message msg = wrapper.message();
            Map<String, Object> metadata = parseMetadata(msg.getExtraMetadata());
            Object requestId = metadata.get("request_id");
            if ("user".equals(msg.getRole()) && requestId != null) {
                messageRequestIds.add(String.valueOf(requestId));
            }
        }
        Map<String, List<Map<String, Object>>> attachmentsByRequestId = new LinkedHashMap<>();
        if (!messageRequestIds.isEmpty()) {
            for (Map<String, Object> attachment : conversationRepository.getAttachments(conversation.getId())) {
                Object requestId = attachment.get("request_id");
                if (requestId == null || !messageRequestIds.contains(String.valueOf(requestId))) {
                    continue;
                }
                attachmentsByRequestId
                        .computeIfAbsent(String.valueOf(requestId), key -> new ArrayList<>())
                        .add(AttachmentService.serializeAttachment(attachment, threadId));
            }
        }

        List<Map<String, Object>> history = new ArrayList<>();
        for (MessageWithRelations wrapper : messagesWithRelations) {
            Message msg = wrapper.message();
            Map<String, Object> userFeedback = null;
            for (MessageFeedback feedback : wrapper.feedbacks()) {
                if (String.valueOf(feedback.getUid()).equals(currentUid)) {
                    userFeedback = new LinkedHashMap<>();
                    userFeedback.put("id", feedback.getId());
                    userFeedback.put("rating", feedback.getRating());
                    userFeedback.put("reason", feedback.getReason());
                    userFeedback.put("created_at", DateTimeUtils.localIsoformat(feedback.getCreatedAt()));
                    break;
                }
            }

            Map<String, Object> extraMetadata = serializeHistoryMetadata(msg);
            Object requestId = extraMetadata.get("request_id");
            if ("user".equals(msg.getRole()) && requestId != null && !extraMetadata.containsKey("attachments")) {
                List<Map<String, Object>> attachments =
                        attachmentsByRequestId.get(String.valueOf(requestId));
                extraMetadata.put("attachments", attachments == null ? List.of() : attachments);
            }

            Map<String, Object> msgDict = new LinkedHashMap<>();
            msgDict.put("id", msg.getId());
            msgDict.put("type", ROLE_TYPE_MAP.getOrDefault(msg.getRole(), msg.getRole()));
            msgDict.put("content", msg.getContent());
            msgDict.put("created_at", DateTimeUtils.localIsoformat(msg.getCreatedAt()));
            msgDict.put("run_id", msg.getRunId());
            msgDict.put("request_id", msg.getRequestId());
            msgDict.put("delivery_status", msg.getDeliveryStatus());
            msgDict.put("error_type", extraMetadata.get("error_type"));
            msgDict.put("error_message", extraMetadata.get("error_message"));
            msgDict.put("extra_metadata", extraMetadata);
            msgDict.put("message_type", msg.getMessageType());
            msgDict.put("image_content", msg.getImageContent());
            msgDict.put("feedback", userFeedback);

            if ("assistant".equals(msg.getRole())) {
                msgDict.putAll(ModelUtils.parseAssistantMessageBody(msg.getContent(), parseMetadata(msg.getExtraMetadata())));
            }

            if (!wrapper.toolCalls().isEmpty()) {
                List<Map<String, Object>> toolCallMaps = new ArrayList<>();
                for (ToolCall toolCall : wrapper.toolCalls()) {
                    toolCallMaps.add(serializeToolCall(toolCall));
                }
                msgDict.put("tool_calls", toolCallMaps);
            }

            history.add(msgDict);
        }

        log.info("Loaded {} messages with feedback for thread {}", history.size(), threadId);
        List<Map<String, Object>> runList = new ArrayList<>();
        for (AgentRun run : runs) {
            Map<String, Object> runMap = serializeRunTrace(run);
            runMap.put("request_id", run.getRequestId());
            runMap.put("run_type", run.getRunType());
            runMap.put("created_by_run_id", run.getCreatedByRunId());
            runList.add(runMap);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thread", thread);
        result.put("runs", runList);
        result.put("history", history);
        return result;
    }

    /** 将线程最新顶层 run 与查看记录映射为侧边栏三态（_thread_status）。 */
    static String threadStatus(String runId, String runStatus, String lastViewedRunId) {
        if (runId == null) {
            return "done";
        }
        if (runStatus == null || !ModelConstants.AGENT_RUN_TERMINAL_STATUSES.contains(runStatus)) {
            return "loading";
        }
        if (runId.equals(lastViewedRunId)) {
            return "done";
        }
        return "ready";
    }

    /** 序列化线程（_serialize_thread）。 */
    private Map<String, Object> serializeThread(Conversation conversation, String threadStatus, String workdirPath) {
        String resolvedWorkdirPath = workdirPath;
        if (resolvedWorkdirPath == null) {
            resolvedWorkdirPath = workdirService.resolveConversationWorkdirPath(
                    conversation, String.valueOf(conversation.getUid()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", conversation.getThreadId());
        result.put("uid", conversation.getUid());
        result.put("agent_id", conversation.getAgentId());
        result.put("title", conversation.getTitle());
        result.put("is_pinned", Boolean.TRUE.equals(conversation.getIsPinned()));
        result.put("project_id", conversation.getProjectId());
        result.put("workdir_path", resolvedWorkdirPath);
        result.put("created_at", DateTimeUtils.localIsoformat(conversation.getCreatedAt()));
        result.put("updated_at", DateTimeUtils.localIsoformat(conversation.getUpdatedAt()));
        result.put("metadata", parseMetadata(conversation.getExtraMetadata()));
        result.put("thread_status", threadStatus);
        return result;
    }

    /** 要求已有 Conversation 仍有效且匹配当前幂等创建意图（_require_matching_thread_creation_intent）。 */
    private static void requireMatchingThreadCreationIntent(
            Conversation conversation, Project project, String agentSlug, String projectId) {
        if ("deleted".equals(conversation.getStatus())
                || project == null
                || "deleted".equals(project.getStatus())) {
            throw new BizException(409, "request_id 已用于已删除的 Conversation");
        }
        boolean sameProjectIntent = projectId != null
                ? conversation.getProjectId().equals(projectId)
                : project != null && "implicit".equals(project.getSelectionStatus());
        if (!conversation.getAgentId().equals(agentSlug) || !sameProjectIntent) {
            throw new BizException(409, "request_id 已用于其他 Conversation 创建意图");
        }
    }

    /** 从普通 History 中移除 Model lifecycle 内部字段（_serialize_history_metadata）。 */
    private static Map<String, Object> serializeHistoryMetadata(Message message) {
        Map<String, Object> metadata = parseMetadata(message.getExtraMetadata());
        if (message.getOperationId() == null) {
            return metadata;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String key : MODEL_HISTORY_METADATA_KEYS) {
            if (metadata.containsKey(key)) {
                filtered.put(key, metadata.get(key));
            }
        }
        return filtered;
    }

    /** 序列化普通历史和审计共用的 ToolCall 结构（_serialize_tool_call）。 */
    static Map<String, Object> serializeToolCall(ToolCall toolCall) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "id",
                toolCall.getLanggraphToolCallId() != null
                        ? toolCall.getLanggraphToolCallId()
                        : String.valueOf(toolCall.getId()));
        result.put("name", toolCall.getToolName());
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", toolCall.getToolName());
        result.put("function", function);
        result.put("args", parseMetadata(toolCall.getToolInput()));
        Map<String, Object> toolCallResult = new LinkedHashMap<>();
        if ("success".equals(toolCall.getStatus())) {
            toolCallResult.put("content", toolCall.getToolOutput() == null ? "" : toolCall.getToolOutput());
            result.put("tool_call_result", toolCallResult);
        } else {
            result.put("tool_call_result", null);
        }
        result.put("status", toolCall.getStatus());
        result.put("error_message", toolCall.getErrorMessage());
        return result;
    }

    /** 将 Message 审计事实分派到显式 Model/Tool DTO（_serialize_message_audit）。 */
    private static Map<String, Object> serializeMessageAudit(MessageWithRelations message) {
        if ("tool".equals(message.message().getRole())) {
            return serializeToolAudit(message);
        }
        return serializeModelAudit(message);
    }

    /** 从 AgentRun Owner 投影调试面板所需的状态与阶段时间（_serialize_run_trace）。 */
    static Map<String, Object> serializeRunTrace(AgentRun run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run_id", run.getId());
        result.put("status", run.getStatus());
        result.put("timing", ModelConstants.buildAgentRunTiming(
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getPreparedAt(),
                run.getFirstOutputAt(),
                run.getFinishedAt(),
                run.getFirstModelRequestAt()));
        return result;
    }

    /** 将 Model 审计事实收敛为前端调试 DTO（_serialize_model_audit）。 */
    private static Map<String, Object> serializeModelAudit(MessageWithRelations wrapper) {
        Message message = wrapper.message();
        Map<String, Object> metadata = parseMetadata(message.getExtraMetadata());
        Object contentBlocks = metadata.get("content");
        Object modelRunId = metadata.get("model_run_id");
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(serializeAuditBase(message, metadata));
        result.putAll(ModelUtils.parseAssistantMessageBody(message.getContent(), metadata));
        result.put("type", "ai");
        result.put("usage", parseJsonMap(message.getUsage()));
        result.put("model_run_id", modelRunId instanceof String text ? text : null);
        result.put("content_blocks", contentBlocks instanceof List<?> list ? list : List.of());
        List<Map<String, Object>> toolCallMaps = new ArrayList<>();
        for (ToolCall toolCall : wrapper.toolCalls()) {
            toolCallMaps.add(serializeToolCall(toolCall));
        }
        result.put("tool_calls", toolCallMaps);
        return result;
    }

    /** 将 ToolMessage 审计事实收敛为前端调试 DTO（_serialize_tool_audit）。 */
    private static Map<String, Object> serializeToolAudit(MessageWithRelations wrapper) {
        Message message = wrapper.message();
        Map<String, Object> metadata = parseMetadata(message.getExtraMetadata());
        Map<String, Object> result = new LinkedHashMap<>();
        result.putAll(serializeAuditBase(message, metadata));
        result.put("type", "tool");
        result.put("tool_call_id", metadata.get("tool_call_id"));
        result.put("tool_name", metadata.get("tool_name"));
        result.put("tool_input", metadata.get("input") instanceof Map<?, ?> map ? new LinkedHashMap<>(map) : Map.of());
        result.put("tool_output", metadata.get("output"));
        result.put("error_message", metadata.get("error_message"));
        result.put("source_model_operation_id", metadata.get("source_model_operation_id"));
        result.put("usage", null);
        return result;
    }

    /** 序列化 Model/Tool 审计共有字段（_serialize_audit_base）。 */
    private static Map<String, Object> serializeAuditBase(Message message, Map<String, Object> metadata) {
        Object namespace = metadata.get("namespace");
        Object finishedSequenceObj = metadata.get("finished_sequence");
        Integer finishedSequence =
                finishedSequenceObj instanceof Integer integer ? integer : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", message.getId());
        result.put("content", message.getContent());
        result.put("created_at", DateTimeUtils.formatUtcDatetime(message.getCreatedAt()));
        result.put("run_id", message.getRunId());
        result.put("request_id", message.getRequestId());
        result.put("message_type", message.getMessageType());
        result.put("operation_id", message.getOperationId());
        result.put("started_at", DateTimeUtils.formatUtcDatetime(message.getStartedAt()));
        result.put("finished_at", DateTimeUtils.formatUtcDatetime(message.getFinishedAt()));
        result.put("duration_ms", message.getDurationMs());
        result.put("sequence", message.getSequence());
        result.put("finished_sequence", finishedSequence);
        result.put("execution_status", message.getExecutionStatus());
        List<String> namespaceList = new ArrayList<>();
        if (namespace instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String text) {
                    namespaceList.add(text);
                }
            }
        }
        result.put("namespace", namespaceList);
        return result;
    }

    /** JSON 字符串 → Map（容错：非法 JSON 视为空表）。 */
    static Map<String, Object> parseMetadata(String value) {
        if (value == null || value.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(
                    value, new TypeReference<LinkedHashMap<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (RuntimeException exc) {
            log.warn("metadata JSON 解析失败，按空表处理: {}", exc.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, Object> parseJsonMap(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return JSON.parseObject(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (RuntimeException exc) {
            return null;
        }
    }
}
