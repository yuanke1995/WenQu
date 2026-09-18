package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.MinioUrls;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.ConversationStats;
import com.wisesoft.wenqu.models.ToolCall;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository.MessageWithRelations;
import com.wisesoft.wenqu.repositories.DashboardRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Dashboard 统计与监控业务用例服务。
 *
 * <p>由参考实现的 services/dashboard_service.py 逐方法翻译：封装 Dashboard 统计读模型、
 * 会话查询、反馈列表与时间序列分析。
 */
@Service
public class DashboardService {

    private final DashboardRepository repo;
    private final ConversationRepository convRepo;

    public DashboardService(DashboardRepository repo, ConversationRepository convRepo) {
        this.repo = repo;
        this.convRepo = convRepo;
    }

    /** 读取基础统计指标（会话数、消息数、用户数、满意度）。 */
    public Map<String, Object> getBasicStats() {
        return repo.getBasicStats();
    }

    /** 统计用户总量与活跃趋势。 */
    public Map<String, Object> getUserActivityStats(LocalDateTime now) {
        return repo.getUserActivityStats(now);
    }

    /** 统计工具调用总量、成功率与分布。 */
    public Map<String, Object> getToolCallStats(LocalDateTime now) {
        return repo.getToolCallStats(now);
    }

    /** 汇总智能体对话、满意度与工具使用情况。 */
    public Map<String, Object> getAgentAnalytics() {
        return repo.getAgentAnalytics();
    }

    /** 按可选评分和智能体过滤并装配用户反馈列表。 */
    public List<Map<String, Object>> getFeedbacks(String rating, String agentId) {
        List<DashboardRepository.FeedbackRow> rows = repo.listFeedbacks(rating, agentId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (DashboardRepository.FeedbackRow row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.feedback().getId());
            item.put("message_id", row.feedback().getMessageId());
            item.put("uid", row.feedback().getUid());
            item.put("username", row.user() != null ? row.user().getUsername() : null);
            item.put(
                    "avatar",
                    row.user() != null && row.user().getAvatar() != null
                            ? MinioUrls.normalizePublicMinioUrl(row.user().getAvatar())
                            : null);
            item.put("rating", row.feedback().getRating());
            item.put("reason", row.feedback().getReason());
            item.put(
                    "created_at",
                    row.feedback().getCreatedAt() != null ? row.feedback().getCreatedAt().format(NAIVE_ISO) : "");
            item.put("message_content", row.message() != null ? row.message().getContent() : "");
            item.put("conversation_title", row.conversation() != null ? row.conversation().getTitle() : null);
            item.put(
                    "agent_id",
                    row.conversation() != null && row.conversation().getAgentId() != null
                            ? row.conversation().getAgentId()
                            : "");
            items.add(item);
        }
        return items;
    }

    /** 查询调用分析时间序列。 */
    public Map<String, Object> getCallTimeseries(String metricType, String timeRange) {
        return repo.getCallTimeseries(metricType, timeRange == null ? "14days" : timeRange, null, null);
    }

    /** 汇总会话（Thread）多维分析统计。 */
    public Map<String, Object> getThreadAnalytics(String timeRange, String agentId, boolean includeSubagents) {
        return repo.getThreadAnalytics(timeRange == null ? "30days" : timeRange, agentId, includeSubagents, null);
    }

    /** 读取会话审计用户与 Agent 筛选项。 */
    public Map<String, List<Map<String, Object>>> getConversationFilterOptions() {
        return repo.getConversationFilterOptions();
    }

    /** 分页查询并组装 Dashboard 对话列表。 */
    public Map<String, Object> listConversations(
            String uid, String agentId, String status, String search, int limit, int offset) {
        return repo.listConversations(uid, agentId, status, search, limit, offset);
    }

    /** 获取指定会话完整消息流水与统计。 */
    public Map<String, Object> getConversationDetail(String threadId) {
        Conversation conversation = convRepo.getConversationByThreadId(threadId);
        if (conversation == null) {
            return null;
        }

        List<MessageWithRelations> messages = convRepo.getMessages(conversation.getId(), null, 0);
        ConversationStats stats = convRepo.getStats(conversation.getId());
        Map<String, Object> auditMetadata = repo.getConversationAuditMetadata(conversation);
        List<Map<String, Object>> messageList = new ArrayList<>();
        for (MessageWithRelations wrapper : messages) {
            var message = wrapper.message();
            Map<String, Object> messageData = new LinkedHashMap<>();
            messageData.put("id", message.getId());
            messageData.put("role", message.getRole());
            messageData.put("content", message.getContent());
            messageData.put("message_type", message.getMessageType());
            messageData.put(
                    "created_at",
                    message.getCreatedAt() != null ? message.getCreatedAt().format(NAIVE_ISO) : "");
            messageData.put("token_count", message.getTokenCount());
            if (!wrapper.toolCalls().isEmpty()) {
                List<Map<String, Object>> toolCalls = new ArrayList<>();
                for (ToolCall toolCall : wrapper.toolCalls()) {
                    Map<String, Object> toolCallData = new LinkedHashMap<>();
                    toolCallData.put("id", toolCall.getId());
                    toolCallData.put("tool_name", toolCall.getToolName());
                    toolCallData.put("tool_input", toolCall.getToolInput());
                    toolCallData.put("tool_output", toolCall.getToolOutput());
                    toolCallData.put("status", toolCall.getStatus());
                    toolCallData.put("error_message", toolCall.getErrorMessage());
                    toolCalls.add(toolCallData);
                }
                messageData.put("tool_calls", toolCalls);
            }
            messageList.add(messageData);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("thread_id", conversation.getThreadId());
        result.put("uid", conversation.getUid());
        result.put("username", auditMetadata.get("username"));
        result.put("user_avatar", auditMetadata.get("user_avatar"));
        result.put("user_deleted", auditMetadata.get("user_deleted"));
        result.put("agent_id", conversation.getAgentId());
        result.put("agent_name", auditMetadata.get("agent_name"));
        result.put("agent_avatar", auditMetadata.get("agent_avatar"));
        result.put("agent_deleted", auditMetadata.get("agent_deleted"));
        result.put("title", conversation.getTitle());
        result.put("status", conversation.getStatus());
        result.put("is_pinned", Boolean.TRUE.equals(conversation.getIsPinned()));
        result.put(
                "message_count",
                stats != null && stats.getMessageCount() != null ? stats.getMessageCount() : messageList.size());
        result.put(
                "created_at",
                conversation.getCreatedAt() != null ? conversation.getCreatedAt().format(NAIVE_ISO) : "");
        result.put(
                "updated_at",
                conversation.getUpdatedAt() != null ? conversation.getUpdatedAt().format(NAIVE_ISO) : "");
        result.putAll(repo.getConversationTokenUsage(conversation.getId()));
        result.put("messages", messageList);
        return result;
    }

    private static final java.time.format.DateTimeFormatter NAIVE_ISO =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
}
