package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.StringUtils;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.ConversationStats;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.MessageFeedback;
import com.wisesoft.wenqu.models.ModelConstants;
import com.wisesoft.wenqu.models.SubagentThread;
import com.wisesoft.wenqu.models.ToolCall;
import com.wisesoft.wenqu.repository.port.AgentRunMapper;
import com.wisesoft.wenqu.repository.port.ConversationMapper;
import com.wisesoft.wenqu.repository.port.ConversationStatsMapper;
import com.wisesoft.wenqu.repository.port.MessageFeedbackMapper;
import com.wisesoft.wenqu.repository.port.MessageMapper;
import com.wisesoft.wenqu.repository.port.ToolCallMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话域持久化仓储。
 *
 * <p>由参考实现的 repositories/conversation_repository.py 逐方法翻译：对话与统计记录的生命周期、
 * 普通历史与审计时间线的读取口径（只允许终态 State 已证明的 Model 兼容行进入普通读模型）、
 * 置顶优先的会话列表、消息内容搜索与有界记忆历史读取、会话级附件管理。
 *
 * <p>必要替换（均已在方法或行内注释标注）：
 * <ul>
 *   <li>{@code commit: bool = True} 参数省略：事务传播 REQUIRED —— 调用方有事务即加入并在其边界提交，
 *       无事务时本方法自行开启并提交；与参考实现「默认提交 / flush-only 交外层事务」两种用法一致。
 *       需要与外层同事务的调用方直接调用即可。
 *   <li>ORM 关系预加载（selectinload Message.tool_calls / Message.feedbacks）→ 返回
 *       {@link MessageWithRelations} 记录（消息 + 两次批量查询），保持调用方可拿到同样的事实集合。
 *   <li>{@code joinedload(Conversation.project)} 不复制：经全仓核对，参考实现没有任何调用方读取
 *       {@code conversation.project} 关系（都使用 {@code conversation.project_id} 列）。
 *   <li>{@code load_only(...)} 列裁剪是读取优化，不改变事实，不复制。
 *   <li>JSON 列（extra_metadata / user_feedback）：参考实现由 ORM 序列化 dict，本层显式
 *       {@code JSON.toJSONString}；{@code flag_modified} 标脏 → 显式 UPDATE（字段级 set）。
 *   <li>{@code ilike} → {@code LOWER(col) LIKE LOWER(?) ESCAPE '\\'}（转义规则照搬）。
 *   <li>JSON 路径条件 {@code extra_metadata["source"].as_string()} →
 *       {@code JSON_UNQUOTE(JSON_EXTRACT(...))}；
 *       {@code extra_metadata["state_reconciled"].as_boolean().is_(True)} →
 *       {@code JSON_EXTRACT(..., '$.state_reconciled') = TRUE}（实测等价：JSON 布尔 true 命中，
 *       字符串 "true" 与数值不命中由写入方保证只写布尔，见参考实现的写入点）。
 *   <li>{@code uuid4} → {@code UUID.randomUUID}。
 *   <li>{@code ValueError} → {@code IllegalArgumentException}。
 * </ul>
 */
@Repository
public class ConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(ConversationRepository.class);

    public static final int MAX_CONVERSATION_TITLE_LENGTH = 255;
    public static final int MESSAGE_SEARCH_SNIPPET_RADIUS = 72;
    public static final int MESSAGE_SEARCH_SNIPPET_MAX_LENGTH = 180;
    public static final int MESSAGE_SEARCH_SNIPPETS_PER_THREAD = 2;
    public static final List<String> MESSAGE_SEARCH_ROLES = List.of("user", "assistant");
    public static final List<String> MESSAGE_SEARCH_EXCLUDED_TYPES = List.of(
            "tool_call", "tool_result", ModelConstants.MODEL_AUDIT_MESSAGE_TYPE, ModelConstants.TOOL_AUDIT_MESSAGE_TYPE);
    public static final List<String> INVOCATION_CONVERSATION_SOURCES = List.of("agent_call", "agent_evaluation");

    // ==== 历史对话检索参数 ====
    /** 单次历史搜索最多返回的消息条数。 */
    public static final int MEMORY_HISTORY_SEARCH_MAX_LIMIT = 10;
    /** 历史搜索关键词允许的最大字符数。 */
    public static final int MEMORY_HISTORY_SEARCH_QUERY_MAX_CHARS = 256;
    /** 单条搜索结果摘要的最大 UTF-8 字节数。 */
    public static final int MEMORY_HISTORY_SEARCH_SNIPPET_MAX_BYTES = 512;
    /** 历史搜索完整 JSON 响应的最大字节数。 */
    public static final int MEMORY_HISTORY_SEARCH_RESPONSE_MAX_BYTES = 16 * 1024;
    /** 单次历史读取最多返回的消息条数。 */
    public static final int MEMORY_HISTORY_READ_MAX_LIMIT = 20;
    /** 单条历史消息正文的最大 UTF-8 字节数。 */
    public static final int MEMORY_HISTORY_MESSAGE_MAX_BYTES = 8 * 1024;
    /** 一次历史读取中全部消息正文的合计字节上限。 */
    public static final int MEMORY_HISTORY_MESSAGES_MAX_BYTES = 32 * 1024;
    /** 显式读取工具调用时最多返回的记录数。 */
    public static final int MEMORY_HISTORY_TOOL_CALL_MAX_COUNT = 10;
    /** 单条工具调用序列化后的最大字节数。 */
    public static final int MEMORY_HISTORY_TOOL_CALL_MAX_BYTES = 4 * 1024;
    /** 一次历史读取中全部工具调用的合计字节上限。 */
    public static final int MEMORY_HISTORY_TOOL_CALLS_MAX_BYTES = 16 * 1024;
    /** 历史读取完整 JSON 响应的最大字节数。 */
    public static final int MEMORY_HISTORY_READ_RESPONSE_MAX_BYTES = 64 * 1024;

    /** 只允许终态 State 已证明的 Model 兼容行进入普通读模型的 SQL 片段（对应 _state_proven_model_tool_call_condition）。 */
    private static final String STATE_PROVEN_CONDITION =
            "message_type = '" + ModelConstants.MODEL_AUDIT_MESSAGE_TYPE + "'"
                    + " AND EXISTS (SELECT 1 FROM tool_calls tc WHERE tc.message_id = messages.id)"
                    + " AND JSON_EXTRACT(extra_metadata, '$.state_reconciled') = TRUE"
                    + " AND run_id IN (SELECT id FROM agent_runs WHERE status IN ('completed','failed','cancelled','interrupted'))";

    private final ConversationMapper conversationMapper;
    private final ConversationStatsMapper statsMapper;
    private final MessageMapper messageMapper;
    private final MessageFeedbackMapper feedbackMapper;
    private final ToolCallMapper toolCallMapper;
    private final AgentRunMapper runMapper;
    private final JdbcTemplate jdbc;

    public ConversationRepository(
            ConversationMapper conversationMapper,
            ConversationStatsMapper statsMapper,
            MessageMapper messageMapper,
            MessageFeedbackMapper feedbackMapper,
            ToolCallMapper toolCallMapper,
            AgentRunMapper runMapper,
            JdbcTemplate jdbc) {
        this.conversationMapper = conversationMapper;
        this.statsMapper = statsMapper;
        this.messageMapper = messageMapper;
        this.feedbackMapper = feedbackMapper;
        this.toolCallMapper = toolCallMapper;
        this.runMapper = runMapper;
        this.jdbc = jdbc;
    }

    /** 消息与其批量装载的关系事实（对应 selectinload 的两路预加载）。 */
    public record MessageWithRelations(Message message, List<ToolCall> toolCalls, List<MessageFeedback> feedbacks) {}

    /** 有界审计时间线：消息与是否被截断。 */
    public record AuditPage(List<MessageWithRelations> messages, boolean truncated) {}

    /** 消息内容搜索的单条片段。 */
    public record MessageSnippet(Integer messageId, String content, LocalDateTime createdAt) {}

    /** 消息内容搜索的单会话命中。 */
    public record ConversationSearchItem(
            Conversation conversation,
            long matchedCount,
            LocalDateTime latestMatchAt,
            Integer messageId,
            List<MessageSnippet> snippets) {}

    /** 历史读取中的轻量消息行。 */
    public record MemoryMessageRow(int id, String role, String content) {}

    // ==================== 创建与读取 ====================

    /** 创建对话和统计记录（事务边界见类注释）。 */
    @Transactional
    public Conversation addConversation(
            String uid,
            String agentId,
            String title,
            String threadId,
            Map<String, Object> metadata,
            String projectId,
            String creationRequestId) {
        String effectiveThreadId = threadId == null || threadId.isEmpty() ? UUID.randomUUID().toString() : threadId;

        Map<String, Object> effectiveMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            effectiveMetadata.putAll(metadata);
        }
        effectiveMetadata.put("attachments", new ArrayList<>());

        String normalizedTitle = normalizeTitle(title);

        LocalDateTime now = DateTimeUtils.utcNowNaive();
        Conversation conversation = new Conversation();
        conversation.setThreadId(effectiveThreadId);
        conversation.setCreationRequestId(creationRequestId);
        conversation.setUid(String.valueOf(uid));
        conversation.setAgentId(agentId);
        conversation.setTitle(normalizedTitle == null || normalizedTitle.isEmpty() ? "New Conversation" : normalizedTitle);
        conversation.setStatus("active");
        conversation.setExtraMetadata(JSON.toJSONString(effectiveMetadata));
        conversation.setLastViewedRunId(ModelConstants.UNVIEWED_RUN_MARKER);
        conversation.setProjectId(projectId);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);

        ConversationStats stats = new ConversationStats();
        stats.setConversationId(conversation.getId());
        stats.setCreatedAt(now);
        stats.setUpdatedAt(now);
        statsMapper.insert(stats);

        log.info("Created conversation: {} for user {}", conversation.getThreadId(), uid);
        return conversation;
    }

    /** 创建并提交一个完整对话，适用于不需要外层事务编排的入口。 */
    @Transactional
    public Conversation createConversation(
            String uid,
            String agentId,
            String projectId,
            String title,
            String threadId,
            Map<String, Object> metadata,
            String creationRequestId) {
        return addConversation(uid, agentId, title, threadId, metadata, projectId, creationRequestId);
    }

    public Conversation getConversationByThreadId(String threadId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>().eq(Conversation::getThreadId, threadId));
    }

    /** 按用户和创建幂等键读取 Conversation。 */
    public Conversation getConversationByCreationRequestId(String uid, String requestId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUid, String.valueOf(uid))
                        .eq(Conversation::getCreationRequestId, requestId));
    }

    /** 锁定线程根记录，串行化同一对话的调度决策。 */
    @Transactional
    public Conversation lockConversationByThreadId(String threadId) {
        List<Conversation> rows =
                jdbc.query(
                        "SELECT * FROM conversations WHERE thread_id = ? FOR UPDATE",
                        (rs, rowNum) -> mapConversationRow(rs),
                        threadId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Conversation getConversationById(Integer conversationId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>().eq(Conversation::getId, conversationId));
    }

    /** 记录用户最近查看过的顶层 run id；重复标记同一 run 时保持幂等。 */
    @Transactional
    public Conversation markThreadViewed(String threadId, String runId) {
        Conversation conversation = getConversationByThreadId(threadId);
        if (conversation == null) {
            return null;
        }
        if (!Objects.equals(conversation.getLastViewedRunId(), runId)) {
            conversation.setLastViewedRunId(runId);
            LambdaUpdateWrapper<Conversation> update =
                    new LambdaUpdateWrapper<Conversation>()
                            .eq(Conversation::getId, conversation.getId())
                            .set(Conversation::getLastViewedRunId, runId);
            conversationMapper.update(null, update);
        }
        return conversation;
    }

    /** 在当前事务内更新对话绑定模型。 */
    @Transactional
    public void setModelSpec(Conversation conversation, String modelSpec) {
        Map<String, Object> metadata = new LinkedHashMap<>(RepoValues.toJsonObject(conversation.getExtraMetadata()));
        metadata.put("model_spec", modelSpec);
        saveMetadata(conversation, metadata);
    }

    /** 锁定会话元数据，串行化同一线程的附件更新。 */
    @Transactional
    Conversation lockConversationById(Integer conversationId) {
        List<Conversation> rows =
                jdbc.query(
                        "SELECT * FROM conversations WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> mapConversationRow(rs),
                        conversationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 消息与工具调用 ====================

    @Transactional
    public Message addMessage(
            Integer conversationId,
            String role,
            String content,
            String messageType,
            Map<String, Object> extraMetadata,
            String imageContent,
            String runId,
            String requestId,
            String deliveryStatus) {
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setMessageType(messageType == null ? "text" : messageType);
        message.setExtraMetadata(JSON.toJSONString(extraMetadata == null ? new LinkedHashMap<>() : extraMetadata));
        message.setImageContent(imageContent);
        message.setRunId(runId);
        message.setRequestId(requestId);
        message.setDeliveryStatus(deliveryStatus == null ? "complete" : deliveryStatus);
        message.setCreatedAt(now);
        messageMapper.insert(message);

        Conversation conversation = getConversationById(conversationId);
        if (conversation != null) {
            conversation.setUpdatedAt(now);
            conversationMapper.update(
                    null,
                    new LambdaUpdateWrapper<Conversation>()
                            .eq(Conversation::getId, conversationId)
                            .set(Conversation::getUpdatedAt, now));
        }

        updateMessageCount(conversationId);
        log.debug("Added {} message to conversation {}", role, conversationId);
        return message;
    }

    @Transactional
    public Message addMessageByThreadId(
            String threadId,
            String role,
            String content,
            String messageType,
            Map<String, Object> extraMetadata,
            String imageContent,
            String runId,
            String requestId,
            String deliveryStatus) {
        Conversation conversation = getConversationByThreadId(threadId);
        if (conversation == null) {
            log.warn("Conversation not found for thread_id: {}", threadId);
            return null;
        }
        return addMessage(
                conversation.getId(),
                role,
                content,
                messageType,
                extraMetadata,
                imageContent,
                runId,
                requestId,
                deliveryStatus);
    }

    @Transactional
    public ToolCall addToolCall(
            Integer messageId,
            String toolName,
            Map<String, Object> toolInput,
            String toolOutput,
            String status,
            String errorMessage,
            String langgraphToolCallId) {
        if (langgraphToolCallId != null && !langgraphToolCallId.isEmpty()) {
            ToolCall existing = getToolCallByLanggraphId(langgraphToolCallId);
            if (existing != null) {
                log.debug(
                        "Tool call already exists for langgraph_tool_call_id={}, skip insert", langgraphToolCallId);
                return existing;
            }
        }
        ToolCall toolCall = new ToolCall();
        toolCall.setMessageId(messageId);
        toolCall.setToolName(toolName);
        toolCall.setToolInput(JSON.toJSONString(toolInput == null ? new LinkedHashMap<>() : toolInput));
        toolCall.setToolOutput(toolOutput);
        toolCall.setStatus(status == null ? "pending" : status);
        toolCall.setErrorMessage(errorMessage);
        toolCall.setLanggraphToolCallId(langgraphToolCallId);
        toolCall.setCreatedAt(DateTimeUtils.utcNowNaive());
        toolCallMapper.insert(toolCall);
        log.debug("Added tool call {} to message {}", toolName, messageId);
        return toolCall;
    }

    /** 把最终 AIMessage 发布到普通历史并刷新 Conversation 读模型。 */
    @Transactional
    public void publishAssistantOutput(Message message) {
        if (!"assistant".equals(message.getRole())) {
            throw new IllegalArgumentException("只有 assistant Message 可以发布为最终输出");
        }
        String messageType = message.getMessageType();
        if (ModelConstants.MODEL_AUDIT_MESSAGE_TYPE.equals(messageType)) {
            messageType = "text";
        }
        Conversation conversation = getConversationById(message.getConversationId());
        if (conversation == null) {
            throw new IllegalArgumentException("最终输出缺少 Conversation");
        }
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        conversation.setUpdatedAt(now);
        LambdaUpdateWrapper<Message> update = new LambdaUpdateWrapper<Message>().eq(Message::getId, message.getId());
        update.set(Message::getMessageType, messageType);
        message.setMessageType(messageType);
        messageMapper.update(null, update);
        conversationMapper.update(
                null,
                new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getId, conversation.getId())
                        .set(Conversation::getUpdatedAt, now));
        updateMessageCount(message.getConversationId());
    }

    /** 普通历史读取：排除审计行，除非该 Model 行已被终态 State 证明。 */
    public List<MessageWithRelations> getMessages(Integer conversationId, Integer limit, int offset) {
        LambdaQueryWrapper<Message> wrapper =
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .and(
                                w ->
                                        w.isNull(Message::getMessageType)
                                                .or()
                                                .notIn(Message::getMessageType, ModelConstants.AUDIT_MESSAGE_TYPES)
                                                .or()
                                                .apply(STATE_PROVEN_CONDITION))
                        .orderByAsc(Message::getCreatedAt);
        if (limit != null && limit != 0) {
            wrapper.last("LIMIT " + limit + " OFFSET " + Math.max(offset, 0));
        }
        List<Message> messages = messageMapper.selectList(wrapper);
        return loadRelations(messages);
    }

    /** 读取全部持久 Message 来源 ID，包括普通历史隐藏的审计行。 */
    public Set<String> getMessageSourceIdsByThreadId(String threadId) {
        Conversation conversation = getConversationByThreadId(threadId);
        if (conversation == null) {
            return Set.of();
        }
        List<Message> messages =
                messageMapper.selectList(
                        new LambdaQueryWrapper<Message>()
                                .select(Message::getExtraMetadata)
                                .eq(Message::getConversationId, conversation.getId()));
        Set<String> ids = new HashSet<>();
        for (Message message : messages) {
            JSONObject metadata = RepoValues.parseObject(message.getExtraMetadata());
            if (metadata != null && metadata.get("id") instanceof String id) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 返回有界审计时间线；operation_id 同时覆盖已发布的最终 Model。 */
    public AuditPage listMessageAudits(Integer conversationId, int limit) {
        List<Message> messages =
                jdbc.query(
                        "SELECT m.* FROM messages m JOIN agent_runs r ON r.id = m.run_id"
                                + " WHERE m.conversation_id = ? AND m.operation_id IS NOT NULL"
                                + " AND m.role IN ('assistant','tool')"
                                + " ORDER BY r.created_at DESC, r.id DESC, m.sequence DESC, m.id DESC"
                                + " LIMIT ?",
                        (rs, rowNum) -> mapMessageRow(rs),
                        conversationId,
                        limit + 1);
        boolean truncated = messages.size() > limit;
        List<Message> bounded = messages.subList(0, Math.min(limit, messages.size()));
        java.util.Collections.reverse(bounded);
        return new AuditPage(loadRelations(bounded), truncated);
    }

    /** 按历史顺序读取当前会话全部轻量 Run，包含没有消息的运行。 */
    public List<AgentRun> listAgentRunsForHistory(Integer conversationId) {
        return runMapper.selectList(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getConversationId, conversationId)
                        .orderByAsc(AgentRun::getCreatedAt)
                        .orderByAsc(AgentRun::getId));
    }

    /** 按创建顺序返回有界 AgentRun 调试事实。 */
    public record RunTracePage(List<AgentRun> runs, boolean truncated) {}

    public RunTracePage listAgentRunsForTrace(Integer conversationId, int limit) {
        List<AgentRun> runs =
                runMapper.selectList(
                        new LambdaQueryWrapper<AgentRun>()
                                .eq(AgentRun::getConversationId, conversationId)
                                .orderByDesc(AgentRun::getCreatedAt)
                                .orderByDesc(AgentRun::getId)
                                .last("LIMIT " + (limit + 1)));
        boolean truncated = runs.size() > limit;
        List<AgentRun> bounded = runs.subList(0, Math.min(limit, runs.size()));
        java.util.Collections.reverse(bounded);
        return new RunTracePage(bounded, truncated);
    }

    public List<MessageWithRelations> getMessagesByThreadId(String threadId, Integer limit, int offset) {
        Conversation conversation = getConversationByThreadId(threadId);
        if (conversation == null) {
            log.warn("Conversation not found for thread_id: {}", threadId);
            return List.of();
        }
        return getMessages(conversation.getId(), limit, offset);
    }

    // ==================== 会话列表与搜索 ====================

    /**
     * 列出对话，置顶对话永远排最前；limit 只作用于非置顶对话，保证置顶项始终可见。
     *
     * <p>limit/offset 只作用于非置顶对话，避免重复附带的置顶项改变分页游标。
     */
    public List<Conversation> listConversations(
            String uid, String agentId, String status, Integer limit, int offset, List<String> excludeSources) {
        String effectiveStatus = status == null ? "active" : status;
        StringBuilder condition = new StringBuilder("status = ?");
        List<Object> args = new ArrayList<>();
        args.add(effectiveStatus);
        if (uid != null && !uid.isEmpty()) {
            condition.append(" AND uid = ?");
            args.add(String.valueOf(uid));
        }
        if (agentId != null && !agentId.isEmpty()) {
            condition.append(" AND agent_id = ?");
            args.add(agentId);
        }
        condition.append(excludeSourceCondition(excludeSources, args));

        List<Conversation> pinned =
                jdbc.query(
                        "SELECT * FROM conversations WHERE " + condition + " AND is_pinned"
                                + " ORDER BY updated_at DESC",
                        (rs, rowNum) -> mapConversationRow(rs),
                        args.toArray());
        StringBuilder nonPinnedSql =
                new StringBuilder("SELECT * FROM conversations WHERE " + condition + " AND NOT is_pinned");
        List<Object> nonPinnedArgs = new ArrayList<>(args);
        nonPinnedSql.append(" ORDER BY updated_at DESC");
        if (offset > 0) {
            nonPinnedSql.append(" OFFSET ").append(Math.max(offset, 0));
        }
        if (limit != null) {
            nonPinnedSql.append(" LIMIT ").append(Math.max(limit, 0));
        }
        List<Conversation> nonPinned =
                jdbc.query(nonPinnedSql.toString(), (rs, rowNum) -> mapConversationRow(rs), nonPinnedArgs.toArray());

        List<Conversation> result = new ArrayList<>(pinned);
        result.addAll(nonPinned);
        return result;
    }

    /** 返回用户全部 active 对话，按最近更新时间排序。 */
    public List<Conversation> listActiveConversationsForUser(String uid) {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUid, String.valueOf(uid))
                        .eq(Conversation::getStatus, "active")
                        .orderByDesc(Conversation::getUpdatedAt));
    }

    /** 按消息内容搜索用户的 active 对话（含每会话片段）。 */
    public record ConversationSearchPage(List<ConversationSearchItem> items, boolean hasMore) {}

    public ConversationSearchPage searchConversationsByMessageContent(
            String uid,
            String query,
            String agentId,
            int limit,
            int offset,
            List<String> excludeSources) {
        String normalizedQuery = query == null ? "" : String.valueOf(query).strip();
        if (normalizedQuery.isEmpty()) {
            return new ConversationSearchPage(List.of(), false);
        }

        StringBuilder conversationCondition = new StringBuilder("c.uid = ? AND c.status = 'active'");
        List<Object> conversationArgs = new ArrayList<>();
        conversationArgs.add(String.valueOf(uid));
        if (agentId != null && !agentId.isEmpty()) {
            conversationCondition.append(" AND c.agent_id = ?");
            conversationArgs.add(agentId);
        }
        conversationCondition.append(excludeSourceConditionForAlias(excludeSources, conversationArgs, "c"));

        String messageCondition = messageSearchCondition("m", normalizedQuery, new ArrayList<>());

        String grouped =
                "SELECT m.conversation_id AS conversation_id, COUNT(m.id) AS matched_count,"
                        + " MAX(m.created_at) AS latest_match_at"
                        + " FROM messages m JOIN conversations c ON c.id = m.conversation_id"
                        + " WHERE " + conversationCondition + " AND " + messageCondition
                        + " GROUP BY m.conversation_id";
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT c2.id AS conv_id, g.matched_count AS matched_count, g.latest_match_at AS latest_match_at"
                                + " FROM conversations c2 JOIN (" + grouped + ") g ON c2.id = g.conversation_id"
                                + " ORDER BY g.latest_match_at DESC, c2.updated_at DESC, c2.id DESC"
                                + " LIMIT ? OFFSET ?",
                        limit + 1,
                        Math.max(offset, 0));
        boolean hasMore = rows.size() > limit;
        List<Map<String, Object>> bounded = rows.subList(0, Math.min(limit, rows.size()));

        List<ConversationSearchItem> items = new ArrayList<>();
        for (Map<String, Object> row : bounded) {
            Integer conversationId = ((Number) row.get("conv_id")).intValue();
            Conversation conversation = getConversationById(conversationId);
            List<Object> snippetArgs = new ArrayList<>();
            snippetArgs.add(conversationId);
            String snippetCondition = messageSearchCondition("", normalizedQuery, snippetArgs);
            List<Map<String, Object>> snippetRows =
                    jdbc.queryForList(
                            "SELECT id, content, created_at FROM messages WHERE " + snippetCondition
                                    + " ORDER BY created_at DESC, id DESC LIMIT "
                                    + MESSAGE_SEARCH_SNIPPETS_PER_THREAD,
                            snippetArgs.toArray());
            List<MessageSnippet> snippets = new ArrayList<>();
            for (Map<String, Object> snippetRow : snippetRows) {
                Integer messageId = ((Number) snippetRow.get("id")).intValue();
                String content = String.valueOf(snippetRow.get("content"));
                LocalDateTime createdAt = RepoValues.toLocalDateTime(snippetRow.get("created_at"));
                snippets.add(new MessageSnippet(messageId, buildMessageSearchSnippet(content, normalizedQuery), createdAt));
            }
            items.add(
                    new ConversationSearchItem(
                            conversation,
                            ((Number) row.get("matched_count")).longValue(),
                            RepoValues.toLocalDateTime(row.get("latest_match_at")),
                            snippets.isEmpty() ? null : snippets.get(0).messageId(),
                            snippets));
        }
        return new ConversationSearchPage(items, hasMore);
    }

    /** 搜索当前用户可见的普通主 Agent 历史消息。 */
    public Map<String, Object> searchMemoryMessages(String uid, String query, int limit) {
        String normalizedQuery = query == null ? "" : String.valueOf(query).strip();
        if (normalizedQuery.isEmpty()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (normalizedQuery.length() > MEMORY_HISTORY_SEARCH_QUERY_MAX_CHARS) {
            throw new IllegalArgumentException("query 最多 " + MEMORY_HISTORY_SEARCH_QUERY_MAX_CHARS + " 个字符");
        }
        int boundedLimit = Math.max(1, Math.min(limit, MEMORY_HISTORY_SEARCH_MAX_LIMIT));
        // 参数顺序必须与 SQL 占位符一致：uid → 调用类来源 → 搜索模式
        List<Object> memoryArgs = new ArrayList<>();
        String conversationCondition = memoryConversationCondition("c", uid, memoryArgs);
        String messageCondition = messageSearchCondition("m", normalizedQuery, memoryArgs);
        String sql =
                "SELECT c.thread_id AS thread_id, c.title AS title, m.id AS id, m.role AS role, m.content AS content"
                        + " FROM messages m JOIN conversations c ON c.id = m.conversation_id"
                        + " WHERE " + conversationCondition + " AND " + messageCondition
                        + " ORDER BY m.created_at DESC, m.id DESC LIMIT " + boundedLimit;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, memoryArgs.toArray());

        List<Map<String, Object>> items = new ArrayList<>();
        boolean responseTruncated = false;
        for (Map<String, Object> row : rows) {
            String content = String.valueOf(row.get("content"));
            StringUtils.Truncated snippet = StringUtils.truncateUtf8(
                    buildMessageSearchSnippet(content, normalizedQuery), MEMORY_HISTORY_SEARCH_SNIPPET_MAX_BYTES);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("thread_id", row.get("thread_id"));
            item.put("title", row.get("title"));
            item.put("message_id", ((Number) row.get("id")).intValue());
            item.put("role", row.get("role"));
            item.put("content", snippet.text());
            if (snippet.truncated()) {
                item.put("truncated", true);
            }
            items.add(item);
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("items", items);
            if (jsonSize(probe) > MEMORY_HISTORY_SEARCH_RESPONSE_MAX_BYTES) {
                items.remove(items.size() - 1);
                responseTruncated = true;
                break;
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", items);
        if (responseTruncated) {
            response.put("truncated", true);
        }
        return response;
    }

    /** 读取当前用户普通主 Agent 线程的有界历史。 */
    public Map<String, Object> readMemoryMessages(
            String uid, String threadId, Integer messageId, int limit, boolean includeTools) {
        int boundedLimit = Math.max(1, Math.min(limit, MEMORY_HISTORY_READ_MAX_LIMIT));
        // 参数顺序必须与 SQL 占位符一致：thread_id → uid → 调用类来源
        List<Object> convArgs = new ArrayList<>();
        convArgs.add(String.valueOf(threadId));
        String conversationCondition = memoryConversationCondition("", uid, convArgs);
        List<Map<String, Object>> conversations =
                jdbc.queryForList(
                        "SELECT id, thread_id, title FROM conversations WHERE thread_id = ? AND "
                                + conversationCondition,
                        convArgs.toArray());
        if (conversations.isEmpty()) {
            throw new IllegalArgumentException("历史线程不存在或不可见");
        }
        Map<String, Object> conversation = conversations.get(0);
        int conversationId = ((Number) conversation.get("id")).intValue();

        List<Object> messageArgs = new ArrayList<>();
        messageArgs.add(conversationId);
        String messageCondition = "conversation_id = ? AND " + memoryMessageCondition(includeTools);

        List<MemoryMessageRow> rows;
        if (messageId == null) {
            List<MemoryMessageRow> fetched =
                    jdbc.query(
                            "SELECT id, role, content FROM messages WHERE " + messageCondition
                                    + " ORDER BY created_at DESC, id DESC LIMIT " + boundedLimit,
                            (rs, rowNum) -> new MemoryMessageRow(rs.getInt("id"), rs.getString("role"), rs.getString("content")),
                            messageArgs.toArray());
            java.util.Collections.reverse(fetched);
            rows = fetched;
        } else {
            int anchorId = messageId;
            Integer anchor =
                    jdbc.query(
                            "SELECT id FROM messages WHERE id = ? AND " + messageCondition,
                            (rs, rowNum) -> rs.getInt("id"),
                            prepend(messageArgs, anchorId))
                            .stream()
                            .findFirst()
                            .orElse(null);
            if (anchor == null) {
                throw new IllegalArgumentException("历史消息不存在或不属于该线程");
            }

            int beforeLimit = (boundedLimit + 1) / 2;
            List<Object> beforeArgs = new ArrayList<>(messageArgs);
            beforeArgs.add(anchorId);
            List<MemoryMessageRow> before =
                    jdbc.query(
                            "SELECT id, role, content FROM messages WHERE " + messageCondition + " AND id <= ?"
                                    + " ORDER BY id DESC LIMIT " + beforeLimit,
                            (rs, rowNum) -> new MemoryMessageRow(rs.getInt("id"), rs.getString("role"), rs.getString("content")),
                            beforeArgs.toArray());
            java.util.Collections.reverse(before);

            int afterLimit = boundedLimit - before.size();
            List<MemoryMessageRow> after = new ArrayList<>();
            if (afterLimit > 0) {
                List<Object> afterArgs = new ArrayList<>(messageArgs);
                afterArgs.add(anchorId);
                after =
                        jdbc.query(
                                "SELECT id, role, content FROM messages WHERE " + messageCondition + " AND id > ?"
                                        + " ORDER BY id ASC LIMIT " + afterLimit,
                                (rs, rowNum) -> new MemoryMessageRow(rs.getInt("id"), rs.getString("role"), rs.getString("content")),
                                afterArgs.toArray());
            }
            List<MemoryMessageRow> combined = new ArrayList<>(before);
            combined.addAll(after);
            rows = combined;
        }

        List<Map<String, Object>> messages = serializeMemoryMessages(rows);
        List<Integer> messageIds = new ArrayList<>();
        for (Map<String, Object> item : messages) {
            messageIds.add((Integer) item.get("message_id"));
        }
        ToolCallPage toolCallPage = serializeMemoryToolCalls(messageIds, includeTools);
        boolean truncated = toolCallPage.truncated();
        for (Map<String, Object> item : messages) {
            if (Boolean.TRUE.equals(item.get("truncated"))) {
                truncated = true;
                break;
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("thread_id", conversation.get("thread_id"));
        payload.put("title", conversation.get("title"));
        payload.put("messages", messages);
        payload.put("tool_calls", toolCallPage.toolCalls());
        if (truncated) {
            payload.put("truncated", true);
        }
        fitMemoryReadResponse(payload);
        return payload;
    }

    /** 有界工具调用事实页。 */
    public record ToolCallPage(List<Map<String, Object>> toolCalls, boolean truncated) {}

    // ==================== 更新与删除 ====================

    @Transactional
    public Conversation updateConversation(
            String threadId, String title, String status, Map<String, Object> metadata, Boolean isPinned) {
        Conversation conversation = getConversationByThreadId(threadId);
        if (conversation == null) {
            return null;
        }

        LocalDateTime now = DateTimeUtils.utcNowNaive();
        LambdaUpdateWrapper<Conversation> update =
                new LambdaUpdateWrapper<Conversation>().eq(Conversation::getId, conversation.getId());
        String normalizedTitle = normalizeTitle(title);
        if (normalizedTitle != null) {
            update.set(Conversation::getTitle, normalizedTitle);
            conversation.setTitle(normalizedTitle);
        }
        if (status != null) {
            update.set(Conversation::getStatus, status);
            conversation.setStatus(status);
        }
        if (isPinned != null) {
            update.set(Conversation::getIsPinned, isPinned);
            conversation.setIsPinned(isPinned);
        }
        if (metadata != null) {
            Map<String, Object> currentMetadata =
                    new LinkedHashMap<>(RepoValues.toJsonObject(conversation.getExtraMetadata()));
            currentMetadata.putAll(metadata);
            update.set(Conversation::getExtraMetadata, JSON.toJSONString(currentMetadata));
            conversation.setExtraMetadata(JSON.toJSONString(currentMetadata));
        }
        update.set(Conversation::getUpdatedAt, now);
        conversation.setUpdatedAt(now);
        conversationMapper.update(null, update);

        log.info("Updated conversation {}", threadId);
        return conversation;
    }

    @Transactional
    public boolean deleteConversation(String threadId, boolean softDelete) {
        Conversation conversation = getConversationByThreadId(threadId);
        if (conversation == null) {
            return false;
        }
        if (softDelete) {
            conversationMapper.update(
                    null,
                    new LambdaUpdateWrapper<Conversation>()
                            .eq(Conversation::getId, conversation.getId())
                            .set(Conversation::getStatus, "deleted"));
            log.info("Soft deleted conversation {}", threadId);
        } else {
            conversationMapper.deleteById(conversation.getId());
            log.info("Permanently deleted conversation {}", threadId);
        }
        return true;
    }

    public ConversationStats getStats(Integer conversationId) {
        return statsMapper.selectOne(
                new LambdaQueryWrapper<ConversationStats>().eq(ConversationStats::getConversationId, conversationId));
    }

    @Transactional
    public ConversationStats updateStats(
            Integer conversationId, Integer tokensUsed, String modelUsed, Map<String, Object> userFeedback) {
        ConversationStats stats = getStats(conversationId);
        if (stats == null) {
            return null;
        }
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        LambdaUpdateWrapper<ConversationStats> update =
                new LambdaUpdateWrapper<ConversationStats>().eq(ConversationStats::getId, stats.getId());
        if (tokensUsed != null) {
            int total = (stats.getTotalTokens() == null ? 0 : stats.getTotalTokens()) + tokensUsed;
            stats.setTotalTokens(total);
            update.set(ConversationStats::getTotalTokens, total);
        }
        if (modelUsed != null) {
            stats.setModelUsed(modelUsed);
            update.set(ConversationStats::getModelUsed, modelUsed);
        }
        if (userFeedback != null) {
            stats.setUserFeedback(JSON.toJSONString(userFeedback));
            update.set(ConversationStats::getUserFeedback, JSON.toJSONString(userFeedback));
        }
        stats.setUpdatedAt(now);
        update.set(ConversationStats::getUpdatedAt, now);
        statsMapper.update(null, update);
        return stats;
    }

    public ToolCall getToolCallByLanggraphId(String langgraphToolCallId) {
        return toolCallMapper.selectOne(
                new LambdaQueryWrapper<ToolCall>()
                        .eq(ToolCall::getLanggraphToolCallId, langgraphToolCallId)
                        .orderByDesc(ToolCall::getCreatedAt)
                        .last("LIMIT 1"));
    }

    @Transactional
    public ToolCall updateToolCallOutput(
            String langgraphToolCallId, String toolOutput, String status, String errorMessage) {
        ToolCall toolCall = getToolCallByLanggraphId(langgraphToolCallId);
        if (toolCall == null) {
            log.warn("Tool call not found for langgraph_tool_call_id: {}", langgraphToolCallId);
            return null;
        }
        LambdaUpdateWrapper<ToolCall> update =
                new LambdaUpdateWrapper<ToolCall>().eq(ToolCall::getId, toolCall.getId());
        update.set(ToolCall::getToolOutput, toolOutput);
        toolCall.setToolOutput(toolOutput);
        update.set(ToolCall::getStatus, status == null ? "success" : status);
        toolCall.setStatus(status == null ? "success" : status);
        if (errorMessage != null && !errorMessage.isEmpty()) {
            update.set(ToolCall::getErrorMessage, errorMessage);
            toolCall.setErrorMessage(errorMessage);
        }
        toolCallMapper.update(null, update);
        log.debug("Updated tool call {} with output", langgraphToolCallId);
        return toolCall;
    }

    // ==================== 附件 ====================

    public List<Map<String, Object>> getAttachments(Integer conversationId) {
        Conversation conversation = getConversationById(conversationId);
        if (conversation == null) {
            return List.of();
        }
        return listAttachments(ensureMetadata(conversation));
    }

    /** 锁定会话并返回当前附件，用于需要检查后更新的用例。 */
    @Transactional
    public List<Map<String, Object>> lockAttachments(Integer conversationId) {
        Conversation conversation = lockConversationById(conversationId);
        if (conversation == null) {
            return List.of();
        }
        return listAttachments(ensureMetadata(conversation));
    }

    public List<Map<String, Object>> getAttachmentsByThreadId(String threadId) {
        Conversation conversation = getConversationByThreadId(threadId);
        if (conversation == null) {
            return List.of();
        }
        return getAttachments(conversation.getId());
    }

    @Transactional
    public Map<String, Object> addAttachment(Integer conversationId, Map<String, Object> attachmentInfo) {
        Conversation conversation = lockConversationById(conversationId);
        if (conversation == null) {
            return null;
        }
        Map<String, Object> metadata = ensureMetadata(conversation);
        List<Map<String, Object>> attachments = listAttachments(metadata);
        attachments.removeIf(item -> Objects.equals(item.get("file_id"), attachmentInfo.get("file_id")));
        attachments.add(attachmentInfo);
        metadata.put("attachments", attachments);
        saveMetadata(conversation, metadata);
        return attachmentInfo;
    }

    @Transactional
    public List<Map<String, Object>> addAttachments(Integer conversationId, List<Map<String, Object>> attachmentInfos) {
        Conversation conversation = lockConversationById(conversationId);
        if (conversation == null) {
            return null;
        }
        Map<String, Object> metadata = ensureMetadata(conversation);
        List<Map<String, Object>> attachments = listAttachments(metadata);
        Set<Object> incomingIds = new HashSet<>();
        for (Map<String, Object> item : attachmentInfos) {
            incomingIds.add(item.get("file_id"));
        }
        attachments.removeIf(item -> incomingIds.contains(item.get("file_id")));
        attachments.addAll(attachmentInfos);
        metadata.put("attachments", attachments);
        saveMetadata(conversation, metadata);
        return attachmentInfos;
    }

    @Transactional
    public Map<String, Object> updateAttachmentStatus(
            Integer conversationId, String fileId, String status, Map<String, Object> updateFields) {
        Conversation conversation = lockConversationById(conversationId);
        if (conversation == null) {
            return null;
        }
        Map<String, Object> metadata = ensureMetadata(conversation);
        List<Map<String, Object>> attachments = listAttachments(metadata);
        Map<String, Object> target = null;
        for (Map<String, Object> item : attachments) {
            if (Objects.equals(item.get("file_id"), fileId)) {
                item.put("status", status);
                if (updateFields != null) {
                    item.putAll(updateFields);
                }
                target = item;
                break;
            }
        }
        if (target != null) {
            metadata.put("attachments", attachments);
            saveMetadata(conversation, metadata);
        }
        return target;
    }

    @Transactional
    public List<Map<String, Object>> bindAttachmentsToRequest(
            Integer conversationId, String requestId, List<String> fileIds) {
        Conversation conversation = lockConversationById(conversationId);
        if (conversation == null || requestId == null || requestId.isEmpty() || fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        Set<String> fileIdSet = new HashSet<>();
        for (String fileId : fileIds) {
            if (fileId != null && !fileId.strip().isEmpty()) {
                fileIdSet.add(fileId.strip());
            }
        }
        if (fileIdSet.isEmpty()) {
            return List.of();
        }
        Map<String, Object> metadata = ensureMetadata(conversation);
        List<Map<String, Object>> attachments = listAttachments(metadata);
        boolean changed = false;
        for (Map<String, Object> item : attachments) {
            if (!fileIdSet.contains(item.get("file_id"))) {
                continue;
            }
            if (item.get("request_id") != null) {
                continue;
            }
            item.put("request_id", requestId);
            changed = true;
        }
        if (changed) {
            metadata.put("attachments", attachments);
            saveMetadata(conversation, metadata);
        }
        List<Map<String, Object>> bound = new ArrayList<>();
        for (Map<String, Object> item : attachments) {
            if (Objects.equals(item.get("request_id"), requestId)) {
                bound.add(new LinkedHashMap<>(item));
            }
        }
        return bound;
    }

    public List<Map<String, Object>> getAttachmentsByRequestId(Integer conversationId, String requestId) {
        List<Map<String, Object>> attachments = getAttachments(conversationId);
        List<Map<String, Object>> bound = new ArrayList<>();
        for (Map<String, Object> item : attachments) {
            if (Objects.equals(item.get("request_id"), requestId)) {
                bound.add(item);
            }
        }
        return bound;
    }

    @Transactional
    public boolean removeAttachment(Integer conversationId, String fileId) {
        Conversation conversation = lockConversationById(conversationId);
        if (conversation == null) {
            return false;
        }
        Map<String, Object> metadata = ensureMetadata(conversation);
        List<Map<String, Object>> attachments = listAttachments(metadata);
        List<Map<String, Object>> newAttachments = new ArrayList<>();
        for (Map<String, Object> item : attachments) {
            if (!Objects.equals(item.get("file_id"), fileId)) {
                newAttachments.add(item);
            }
        }
        if (newAttachments.size() == attachments.size()) {
            return false;
        }
        metadata.put("attachments", newAttachments);
        saveMetadata(conversation, metadata);
        return true;
    }

    // ==================== 私有工具 ====================

    private String normalizeTitle(String title) {
        if (title == null) {
            return null;
        }
        String normalized = title.strip();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.length() > MAX_CONVERSATION_TITLE_LENGTH) {
            log.warn(
                    "Conversation title too long ({}), truncate to {}",
                    normalized.length(),
                    MAX_CONVERSATION_TITLE_LENGTH);
            return normalized.substring(0, MAX_CONVERSATION_TITLE_LENGTH);
        }
        return normalized;
    }

    private String escapeLikeQuery(String query) {
        return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 消息搜索条件（LOWER(content) LIKE ? ESCAPE）。调用方需把唯一的占位符值加入 args。
     *
     * @param alias 消息表别名（空串表示无别名）
     * @return 形如 {@code (role IN ...) AND (type 条件) AND (LOWER(content) LIKE ? ESCAPE '\\')} 的 SQL 片段
     */
    private String messageSearchCondition(String alias, String query, List<Object> args) {
        String prefix = alias == null || alias.isEmpty() ? "" : alias + ".";
        String pattern = "%" + escapeLikeQuery(query) + "%";
        args.add(pattern);
        return prefix + "role IN ('user','assistant')"
                + " AND (" + prefix + "message_type IS NULL OR " + prefix + "message_type NOT IN ('tool_call','tool_result','"
                + ModelConstants.MODEL_AUDIT_MESSAGE_TYPE
                + "','"
                + ModelConstants.TOOL_AUDIT_MESSAGE_TYPE
                + "'))"
                + " AND LOWER(" + prefix + "content) LIKE LOWER(?) ESCAPE '\\\\'";
    }

    private String excludeSourceCondition(List<String> sources, List<Object> args) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            in.append(i == 0 ? "?" : ", ?");
            args.add(sources.get(i));
        }
        return " AND (extra_metadata IS NULL OR JSON_UNQUOTE(JSON_EXTRACT(extra_metadata, '$.source')) IS NULL"
                + " OR JSON_UNQUOTE(JSON_EXTRACT(extra_metadata, '$.source')) NOT IN (" + in + "))";
    }

    private String excludeSourceConditionForAlias(List<String> sources, List<Object> args, String alias) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            in.append(i == 0 ? "?" : ", ?");
            args.add(sources.get(i));
        }
        String source = alias + ".extra_metadata";
        return " AND (" + source + " IS NULL OR JSON_UNQUOTE(JSON_EXTRACT(" + source + ", '$.source')) IS NULL"
                + " OR JSON_UNQUOTE(JSON_EXTRACT(" + source + ", '$.source')) NOT IN (" + in + "))";
    }

    /** 构建消息搜索摘要（半径 72 字符，前后省略号，最长 180 字符）。 */
    static String buildMessageSearchSnippet(String content, String query) {
        String normalized = content == null ? "" : String.join(" ", content.trim().split("\\s+"));
        if (normalized.isEmpty()) {
            return "";
        }
        int matchIndex = normalized.toLowerCase().indexOf(query.toLowerCase());
        if (matchIndex < 0) {
            return normalized.substring(0, Math.min(normalized.length(), MESSAGE_SEARCH_SNIPPET_MAX_LENGTH));
        }
        int start = Math.max(0, matchIndex - MESSAGE_SEARCH_SNIPPET_RADIUS);
        int end = Math.min(normalized.length(), matchIndex + query.length() + MESSAGE_SEARCH_SNIPPET_RADIUS);
        String snippet = normalized.substring(start, end).strip();
        if (start > 0) {
            snippet = "..." + snippet;
        }
        if (end < normalized.length()) {
            snippet = snippet + "...";
        }
        return snippet.substring(0, Math.min(snippet.length(), MESSAGE_SEARCH_SNIPPET_MAX_LENGTH));
    }

    /**
     * 用户可见普通主 Agent Conversation 条件（对应 _memory_conversation_conditions）。
     *
     * <p>调用方需按序追加返回的参数：uid → 两个调用类来源。
     *
     * @param alias 对话表别名（空串表示无别名，此时 NOT EXISTS 用 conversations.id）
     */
    private String memoryConversationCondition(String alias, String uid, List<Object> args) {
        String prefix = alias == null || alias.isEmpty() ? "" : alias + ".";
        String conversationRef = alias == null || alias.isEmpty() ? "conversations" : alias;
        String source = prefix + "extra_metadata";
        args.add(String.valueOf(uid));
        args.add(INVOCATION_CONVERSATION_SOURCES.get(0));
        args.add(INVOCATION_CONVERSATION_SOURCES.get(1));
        return prefix + "uid = ?"
                + " AND " + prefix + "status = 'active'"
                + " AND (" + source + " IS NULL"
                + " OR JSON_UNQUOTE(JSON_EXTRACT(" + source + ", '$.source')) IS NULL"
                + " OR JSON_UNQUOTE(JSON_EXTRACT(" + source + ", '$.source')) NOT IN (?, ?))"
                + " AND NOT EXISTS (SELECT 1 FROM subagent_threads st WHERE st.child_conversation_id = "
                + conversationRef + ".id)";
    }

    /**
     * 历史读取的消息条件（无别名；含 conversation_id 之外的角色与类型过滤）。
     * includeTools 时并入「终态 State 已证明的 Model 兼容行」条件。
     */
    private String memoryMessageCondition(boolean includeTools) {
        String condition =
                "role IN ('user','assistant')"
                        + " AND (message_type IS NULL OR message_type NOT IN ('tool_call','tool_result','"
                        + ModelConstants.MODEL_AUDIT_MESSAGE_TYPE
                        + "','"
                        + ModelConstants.TOOL_AUDIT_MESSAGE_TYPE
                        + "'))";
        if (includeTools) {
            condition = "(" + condition + " OR " + STATE_PROVEN_CONDITION + ")";
        } else {
            condition = "(" + condition + ")";
        }
        return condition;
    }

    private static List<Map<String, Object>> serializeMemoryMessages(List<MemoryMessageRow> rows) {
        List<Map<String, Object>> messages = new ArrayList<>();
        int remaining = MEMORY_HISTORY_MESSAGES_MAX_BYTES;
        for (MemoryMessageRow row : rows) {
            int contentBudget = Math.min(MEMORY_HISTORY_MESSAGE_MAX_BYTES, remaining);
            StringUtils.Truncated truncatedContent = StringUtils.truncateUtf8(row.content(), contentBudget);
            String content = truncatedContent.text();
            remaining = Math.max(0, remaining - content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            Map<String, Object> msgItem = new LinkedHashMap<>();
            msgItem.put("message_id", row.id());
            msgItem.put("role", row.role());
            msgItem.put("content", content);
            if (truncatedContent.truncated()) {
                msgItem.put("truncated", true);
            }
            messages.add(msgItem);
        }
        return messages;
    }

    private ToolCallPage serializeMemoryToolCalls(List<Integer> messageIds, boolean includeTools) {
        if (!includeTools || messageIds.isEmpty()) {
            return new ToolCallPage(List.of(), false);
        }
        List<ToolCall> rows =
                toolCallMapper.selectList(
                        new LambdaQueryWrapper<ToolCall>()
                                .in(ToolCall::getMessageId, messageIds)
                                .orderByAsc(ToolCall::getCreatedAt)
                                .orderByAsc(ToolCall::getId)
                                .last("LIMIT " + (MEMORY_HISTORY_TOOL_CALL_MAX_COUNT + 1)));
        boolean truncated = rows.size() > MEMORY_HISTORY_TOOL_CALL_MAX_COUNT;
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        int usedBytes = 0;
        for (ToolCall row : rows.subList(0, Math.min(MEMORY_HISTORY_TOOL_CALL_MAX_COUNT, rows.size()))) {
            Map<String, Object> inputMap = RepoValues.toJsonObject(row.getToolInput());
            StringUtils.Truncated input =
                    StringUtils.truncateUtf8(JSON.toJSONString(inputMap), 1024);
            StringUtils.Truncated output = StringUtils.truncateUtf8(row.getToolOutput(), 2048);
            StringUtils.Truncated error = StringUtils.truncateUtf8(row.getErrorMessage(), 512);
            Map<String, Object> toolItem = new LinkedHashMap<>();
            toolItem.put("tool_call_id", row.getLanggraphToolCallId());
            toolItem.put("name", row.getToolName());
            toolItem.put("input", input.text());
            toolItem.put("output", output.text());
            toolItem.put("status", row.getStatus());
            toolItem.put("error", error.text());
            if (input.truncated() || output.truncated() || error.truncated()) {
                toolItem.put("truncated", true);
            }
            int itemSize = jsonSize(toolItem);
            if (itemSize > MEMORY_HISTORY_TOOL_CALL_MAX_BYTES
                    || usedBytes + itemSize > MEMORY_HISTORY_TOOL_CALLS_MAX_BYTES) {
                truncated = true;
                break;
            }
            usedBytes += itemSize;
            toolCalls.add(toolItem);
        }
        return new ToolCallPage(toolCalls, truncated);
    }

    /** 确保历史读取最终 JSON 响应不超过协议预算。 */
    @SuppressWarnings("unchecked")
    private static void fitMemoryReadResponse(Map<String, Object> payload) {
        List<Object> toolCalls = (List<Object>) payload.get("tool_calls");
        List<Object> messages = (List<Object>) payload.get("messages");
        while (jsonSize(payload) > MEMORY_HISTORY_READ_RESPONSE_MAX_BYTES && toolCalls != null && !toolCalls.isEmpty()) {
            toolCalls.remove(toolCalls.size() - 1);
            payload.put("truncated", true);
        }
        while (jsonSize(payload) > MEMORY_HISTORY_READ_RESPONSE_MAX_BYTES && messages != null && !messages.isEmpty()) {
            messages.remove(0);
            payload.put("truncated", true);
        }
    }

    /** 对应参考实现 _json_size：紧凑 JSON 的 UTF-8 字节数（不可序列化值退化为字符串）。 */
    static int jsonSize(Object value) {
        return JSON.toJSONString(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private Map<String, Object> ensureMetadata(Conversation conversation) {
        Map<String, Object> metadata = new LinkedHashMap<>(RepoValues.toJsonObject(conversation.getExtraMetadata()));
        Object attachments = metadata.get("attachments");
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (attachments instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    normalized.add(copy);
                }
            }
        }
        metadata.put("attachments", normalized);
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listAttachments(Map<String, Object> metadata) {
        Object attachments = metadata.get("attachments");
        List<Map<String, Object>> result = new ArrayList<>();
        if (attachments instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    result.add(copy);
                }
            }
        }
        return result;
    }

    /** 保存元数据（对应 flag_modified + flush：显式更新 extra_metadata 与 updated_at）。 */
    private void saveMetadata(Conversation conversation, Map<String, Object> metadata) {
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        conversationMapper.update(
                null,
                new LambdaUpdateWrapper<Conversation>()
                        .eq(Conversation::getId, conversation.getId())
                        .set(Conversation::getExtraMetadata, JSON.toJSONString(metadata))
                        .set(Conversation::getUpdatedAt, now));
        conversation.setExtraMetadata(JSON.toJSONString(metadata));
        conversation.setUpdatedAt(now);
    }

    /** 统计普通历史消息数并写回 stats.message_count。 */
    private void updateMessageCount(Integer conversationId) {
        ConversationStats stats = getStats(conversationId);
        if (stats == null) {
            return;
        }
        Long count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM messages WHERE conversation_id = ?"
                                + " AND (message_type IS NULL OR message_type NOT IN ('"
                                + ModelConstants.MODEL_AUDIT_MESSAGE_TYPE
                                + "','"
                                + ModelConstants.TOOL_AUDIT_MESSAGE_TYPE
                                + "'))",
                        Long.class,
                        conversationId);
        int messageCount = count == null ? 0 : count.intValue();
        statsMapper.update(
                null,
                new LambdaUpdateWrapper<ConversationStats>()
                        .eq(ConversationStats::getId, stats.getId())
                        .set(ConversationStats::getMessageCount, messageCount));
    }

    /** 批量装载消息的工具调用与反馈（对应 selectinload 的两路预加载）。 */
    private List<MessageWithRelations> loadRelations(List<Message> messages) {
        List<MessageWithRelations> result = new ArrayList<>();
        if (messages.isEmpty()) {
            return result;
        }
        List<Integer> messageIds = new ArrayList<>();
        for (Message message : messages) {
            messageIds.add(message.getId());
        }
        Map<Integer, List<ToolCall>> toolCallsByMessage = new LinkedHashMap<>();
        for (ToolCall toolCall :
                toolCallMapper.selectList(new LambdaQueryWrapper<ToolCall>().in(ToolCall::getMessageId, messageIds))) {
            toolCallsByMessage.computeIfAbsent(toolCall.getMessageId(), key -> new ArrayList<>()).add(toolCall);
        }
        Map<Integer, List<MessageFeedback>> feedbacksByMessage = new LinkedHashMap<>();
        for (MessageFeedback feedback :
                feedbackMapper.selectList(
                        new LambdaQueryWrapper<MessageFeedback>().in(MessageFeedback::getMessageId, messageIds))) {
            feedbacksByMessage.computeIfAbsent(feedback.getMessageId(), key -> new ArrayList<>()).add(feedback);
        }
        for (Message message : messages) {
            result.add(
                    new MessageWithRelations(
                            message,
                            toolCallsByMessage.getOrDefault(message.getId(), List.of()),
                            feedbacksByMessage.getOrDefault(message.getId(), List.of())));
        }
        return result;
    }

    private static List<Object> prepend(List<Object> args, Object value) {
        List<Object> result = new ArrayList<>();
        result.add(value);
        result.addAll(args);
        return result;
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(i == 0 ? "?" : ", ?");
        }
        return builder.toString();
    }

    private static Conversation mapConversationRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Conversation conversation = new Conversation();
        conversation.setId(rs.getObject("id") == null ? null : rs.getInt("id"));
        conversation.setThreadId(rs.getString("thread_id"));
        conversation.setCreationRequestId(rs.getString("creation_request_id"));
        conversation.setUid(rs.getString("uid"));
        conversation.setAgentId(rs.getString("agent_id"));
        conversation.setTitle(rs.getString("title"));
        conversation.setStatus(rs.getString("status"));
        conversation.setIsPinned(rs.getObject("is_pinned") == null ? null : rs.getBoolean("is_pinned"));
        conversation.setLastViewedRunId(rs.getString("last_viewed_run_id"));
        conversation.setProjectId(rs.getString("project_id"));
        conversation.setExtraMetadata(rs.getString("extra_metadata"));
        conversation.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        conversation.setUpdatedAt(toLocalDateTime(rs.getTimestamp("updated_at")));
        return conversation;
    }

    private static Message mapMessageRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Message message = new Message();
        message.setId(rs.getObject("id") == null ? null : rs.getInt("id"));
        message.setConversationId(rs.getObject("conversation_id") == null ? null : rs.getInt("conversation_id"));
        message.setRole(rs.getString("role"));
        message.setContent(rs.getString("content"));
        message.setMessageType(rs.getString("message_type"));
        message.setExtraMetadata(rs.getString("extra_metadata"));
        message.setImageContent(rs.getString("image_content"));
        message.setRunId(rs.getString("run_id"));
        message.setRequestId(rs.getString("request_id"));
        message.setDeliveryStatus(rs.getString("delivery_status"));
        message.setOperationId(rs.getString("operation_id"));
        message.setStartedAt(toLocalDateTime(rs.getTimestamp("started_at")));
        message.setFinishedAt(toLocalDateTime(rs.getTimestamp("finished_at")));
        message.setDurationMs(rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"));
        message.setSequence(rs.getObject("sequence") == null ? null : rs.getLong("sequence"));
        message.setExecutionStatus(rs.getString("execution_status"));
        message.setUsage(rs.getString("usage"));
        message.setTokenCount(rs.getObject("token_count") == null ? null : rs.getInt("token_count"));
        message.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
        return message;
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
