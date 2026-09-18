package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.common.MinioUrls;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.Message;
import com.wisesoft.wenqu.models.MessageFeedback;
import com.wisesoft.wenqu.models.User;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Dashboard 统计读模型的数据访问层。
 *
 * <p>由参考实现的 repositories/dashboard_repository.py 逐方法翻译：集中封装 Dashboard 的跨表
 * 统计查询与读模型聚合（会话审计列表、用户/智能体/工具调用统计、调用分析时间序列、
 * 会话（Thread）分析）。
 *
 * <p>必要替换（已在类或方法注释标注）：
 * <ul>
 *   <li>PostgreSQL {@code to_char(col + INTERVAL '8 hours', 'YYYY-MM-DD HH24:00' / 'YYYY-IW')} →
 *       MySQL {@code DATE_FORMAT(DATE_ADD(col, INTERVAL 8 HOUR), '%Y-%m-%d %H:00' / '%Y-%v')}。
 *       两者都是「日历年 + ISO 周号」（IW 与 %v 同为 ISO 周序号），14weeks 的日期键随后都会
 *       在应用侧按 isocalendar 重算，语义一致。
 *   <li>{@code func.date(col + INTERVAL '8 hours')}（上海日历日）→
 *       {@code DATE(DATE_ADD(col, INTERVAL 8 HOUR))}（SQLite 分支不保留）。
 *   <li>JSON 路径取值/断言：{@code token_usage[...]as_integer()} →
 *       {@code CAST(JSON_UNQUOTE(JSON_EXTRACT(...)) AS SIGNED)}；
 *       {@code as_boolean().is_(True)} → {@code JSON_EXTRACT(...) = TRUE}（实测等价）。
 *   <li>时间缺省值：{@code utc_now()} → {@link DateTimeUtils#utcNow()}；
 *       {@code shanghai_now()} → {@link DateTimeUtils#shanghaiNow()}。
 *   <li>参考实现由 ORM 装载关联实体后访问属性，本工程以 JdbcTemplate 行映射 + 记录类型承载
 *       同样的事实集合；{@code isoformat()} → {@code yyyy-MM-dd'T'HH:mm:ss}（Python 对零秒
 *       也输出秒位，Java 的 ISO_LOCAL_DATE_TIME 会省略，故固定格式）。
 *   <li>只读聚合，全部走 JdbcTemplate；无事务要求。
 * </ul>
 */
@Repository
public class DashboardRepository {

    /** 有效用户与非删除（非子线程）会话的公共过滤条件（别名固定 c/u/a）。 */
    private static final String VALID_FILTER =
            "c.status NOT IN ('deleted','subagent') AND u.is_deleted = 0";

    /** 普通消息排除审计行。 */
    private static final String AUDIT_EXCLUSION =
            "(m.message_type IS NULL OR m.message_type NOT IN ('"
                    + com.wisesoft.wenqu.models.ModelConstants.MODEL_AUDIT_MESSAGE_TYPE
                    + "','"
                    + com.wisesoft.wenqu.models.ModelConstants.TOOL_AUDIT_MESSAGE_TYPE
                    + "'))";

    private static final DateTimeFormatter NAIVE_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final AgentRepository agentRepository;

    public DashboardRepository(JdbcTemplate jdbc, AgentRepository agentRepository) {
        this.jdbc = jdbc;
        this.agentRepository = agentRepository;
    }

    // ==================== 会话 Token 汇总 ====================

    /**
     * 按会话汇总 Run 实测用量，保留缺失标记和无 Run 历史汇总。
     *
     * <p>对应参考实现 {@code _conversation_token_totals}：Run 侧「已报告调用计数 &gt; 0 或 complete
     * 标记为真」才计入 total；MIN(complete) 表达「全部 Run 的用量都已完整」；
     * 无 Run 历史时退回 conversation_stats.total_tokens（0 视为缺失）。
     *
     * @param conversationIds 会话集合；null 表示不过滤（供整体聚合复用）
     */
    private String conversationTokenTotals(List<Integer> conversationIds, List<Object> args) {
        String reported =
                "CAST(JSON_UNQUOTE(JSON_EXTRACT(ar.token_usage, '$.usage_reported_call_count')) AS SIGNED) > 0";
        String complete = "JSON_EXTRACT(ar.token_usage, '$.complete') = TRUE";
        String value = "CAST(JSON_UNQUOTE(JSON_EXTRACT(ar.token_usage, '$.total.total_tokens')) AS SIGNED)";
        String runFilter = "";
        if (conversationIds != null) {
            runFilter = " WHERE ar.conversation_id IN (" + placeholders(conversationIds.size()) + ")";
            args.addAll(conversationIds);
        }
        String runTotals =
                "SELECT ar.conversation_id AS conversation_id,"
                        + " SUM(CASE WHEN (" + reported + " OR " + complete + ") THEN " + value + " END) AS total_tokens,"
                        + " MIN(CASE WHEN " + complete + " AND " + value + " IS NOT NULL THEN 1 ELSE 0 END) AS complete"
                        + " FROM agent_runs ar" + runFilter + " GROUP BY ar.conversation_id";
        String convFilter = "";
        if (conversationIds != null) {
            convFilter = " WHERE c.id IN (" + placeholders(conversationIds.size()) + ")";
            args.addAll(conversationIds);
        }
        return "SELECT c.id AS conversation_id,"
                + " CASE WHEN rt.conversation_id IS NOT NULL THEN rt.total_tokens ELSE NULLIF(cs.total_tokens, 0) END"
                + "   AS total_tokens,"
                + " CASE WHEN rt.conversation_id IS NOT NULL THEN (rt.complete = 1)"
                + "   ELSE (NULLIF(cs.total_tokens, 0) IS NOT NULL) END AS complete"
                + " FROM conversations c"
                + " LEFT JOIN (" + runTotals + ") rt ON c.id = rt.conversation_id"
                + " LEFT JOIN conversation_stats cs ON c.id = cs.conversation_id"
                + convFilter;
    }

    /** 读取与会话列表一致的实测 Token 汇总。 */
    public Map<String, Object> getConversationTokenUsage(Integer conversationId) {
        List<Object> args = new ArrayList<>();
        String sql = conversationTokenTotals(List.of(conversationId), args)
                + " AND c.id = ?";
        // conversationTokenTotals 的 convFilter 以 WHERE 开头，此处追加 AND 条件
        args.add(conversationId);
        Map<String, Object> row = jdbc.queryForMap(sql, args.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_tokens", row.get("total_tokens"));
        result.put("token_usage_complete", Boolean.TRUE.equals(row.get("complete")) || Integer.valueOf(1).equals(row.get("complete")));
        return result;
    }

    // ==================== 会话审计 ====================

    /** 会话审计列表的单行。 */
    public record ConversationAuditItem(
            String threadId,
            String uid,
            String username,
            String userAvatar,
            boolean userDeleted,
            String agentId,
            String agentName,
            String agentAvatar,
            boolean agentDeleted,
            String title,
            String status,
            boolean isPinned,
            Integer messageCount,
            Object totalTokens,
            boolean tokenUsageComplete,
            String createdAt,
            String updatedAt) {}

    /** 分页查询 Dashboard 对话，并装配用户与 Agent 展示名称。 */
    public Map<String, Object> listConversations(
            String uid, String agentId, String status, String search, int limit, int offset) {
        StringBuilder filter = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (uid != null && !uid.isEmpty()) {
            filter.append(" AND c.uid = ?");
            args.add(uid);
        }
        if (agentId != null && !agentId.isEmpty()) {
            filter.append(" AND c.agent_id = ?");
            args.add(agentId);
        }
        if (status != null && !status.isEmpty() && !"all".equals(status)) {
            filter.append(" AND c.status = ?");
            args.add(status);
        } else {
            filter.append(" AND c.status != 'deleted'");
        }
        if (search != null && !search.isEmpty()) {
            // 参考实现不转义 LIKE 通配符，照搬
            String searchTerm = "%" + search.strip() + "%";
            filter.append(
                    " AND (LOWER(c.title) LIKE LOWER(?) OR LOWER(c.thread_id) LIKE LOWER(?)"
                            + " OR LOWER(c.uid) LIKE LOWER(?) OR LOWER(u.username) LIKE LOWER(?))");
            for (int i = 0; i < 4; i++) {
                args.add(searchTerm);
            }
        }

        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(c.id) FROM conversations c LEFT JOIN users u ON c.uid = u.uid WHERE 1=1"
                                + filter,
                        Long.class,
                        args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(Math.max(offset, 0));
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT c.*, cs.message_count AS stats_message_count, u.username AS username,"
                                + " u.avatar AS user_avatar, u.is_deleted AS user_is_deleted"
                                + " FROM conversations c"
                                + " LEFT JOIN conversation_stats cs ON c.id = cs.conversation_id"
                                + " LEFT JOIN users u ON c.uid = u.uid"
                                + " WHERE 1=1" + filter
                                + " ORDER BY c.updated_at DESC LIMIT ? OFFSET ?",
                        pageArgs.toArray());

        List<Integer> conversationIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            conversationIds.add(((Number) row.get("id")).intValue());
        }
        Map<String, Map<String, Object>> usageByConversation = new LinkedHashMap<>();
        if (!conversationIds.isEmpty()) {
            List<Object> usageArgs = new ArrayList<>();
            String usageSql = conversationTokenTotals(conversationIds, usageArgs);
            for (Map<String, Object> row : jdbc.queryForList(usageSql, usageArgs.toArray())) {
                usageByConversation.put(
                        String.valueOf(row.get("conversation_id")),
                        row);
            }
        }
        List<String> agentSlugs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String slug = (String) row.get("agent_id");
            if (slug != null && !agentSlugs.contains(slug)) {
                agentSlugs.add(slug);
            }
        }
        Map<String, Agent> agentsBySlug = new LinkedHashMap<>();
        if (!agentSlugs.isEmpty()) {
            for (Agent agent : agentRepository.listBySlugs(agentSlugs)) {
                agentsBySlug.put(agent.getSlug(), agent);
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Integer conversationId = ((Number) row.get("id")).intValue();
            Map<String, Object> usage = usageByConversation.get(String.valueOf(conversationId));
            Agent agent = agentsBySlug.get((String) row.get("agent_id"));
            String username = (String) row.get("username");
            boolean userDeleted = username == null || Boolean.TRUE.equals(row.get("user_is_deleted"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("thread_id", row.get("thread_id"));
            item.put("uid", row.get("uid"));
            item.put("username", username != null ? username : row.get("uid"));
            String userAvatar = (String) row.get("user_avatar");
            item.put("user_avatar", userAvatar != null ? MinioUrls.normalizePublicMinioUrl(userAvatar) : null);
            item.put("user_deleted", userDeleted);
            item.put("agent_id", row.get("agent_id"));
            item.put("agent_name", agent != null ? agent.getName() : row.get("agent_id"));
            item.put(
                    "agent_avatar",
                    agent != null && agent.getIcon() != null ? MinioUrls.normalizePublicMinioUrl(agent.getIcon()) : null);
            item.put("agent_deleted", agent == null);
            item.put("title", row.get("title"));
            item.put("status", row.get("status"));
            item.put("is_pinned", Boolean.TRUE.equals(row.get("is_pinned")) || Integer.valueOf(1).equals(row.get("is_pinned")));
            item.put(
                    "message_count",
                    row.get("stats_message_count") == null ? 0 : ((Number) row.get("stats_message_count")).intValue());
            item.put("total_tokens", usage == null ? null : usage.get("total_tokens"));
            item.put(
                    "token_usage_complete",
                    usage != null
                            && (Boolean.TRUE.equals(usage.get("complete"))
                                    || Integer.valueOf(1).equals(usage.get("complete"))));
            item.put("created_at", naiveIso(row.get("created_at")));
            item.put("updated_at", naiveIso(row.get("updated_at")));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("limit", limit);
        result.put("offset", offset);
        return result;
    }

    /** 读取完整会话审计可用的用户与 Agent 筛选项。 */
    public Map<String, List<Map<String, Object>>> getConversationFilterOptions() {
        List<Map<String, Object>> userRows =
                jdbc.queryForList(
                        "SELECT DISTINCT c.uid AS uid, u.username AS username, u.avatar AS avatar,"
                                + " u.is_deleted AS is_deleted"
                                + " FROM conversations c LEFT JOIN users u ON c.uid = u.uid");
        List<Map<String, Object>> agentRows =
                jdbc.queryForList(
                        "SELECT DISTINCT c.agent_id AS agent_id, a.name AS name, a.icon AS icon"
                                + " FROM conversations c LEFT JOIN agents a ON c.agent_id = a.slug");

        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> row : userRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uid", row.get("uid"));
            item.put("username", row.get("username") != null ? row.get("username") : row.get("uid"));
            String avatar = (String) row.get("avatar");
            item.put("avatar", avatar != null ? MinioUrls.normalizePublicMinioUrl(avatar) : null);
            item.put("is_deleted", row.get("username") == null || Boolean.TRUE.equals(row.get("is_deleted")));
            users.add(item);
        }
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Map<String, Object> row : agentRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agent_id", row.get("agent_id"));
            item.put("agent_name", row.get("name") != null ? row.get("name") : row.get("agent_id"));
            String icon = (String) row.get("icon");
            item.put("avatar", icon != null ? MinioUrls.normalizePublicMinioUrl(icon) : null);
            item.put("is_deleted", row.get("name") == null);
            agents.add(item);
        }
        users.sort(
                (left, right) -> {
                    int byDeleted = Boolean.compare(
                            (Boolean) left.get("is_deleted"), (Boolean) right.get("is_deleted"));
                    if (byDeleted != 0) {
                        return byDeleted;
                    }
                    return String.valueOf(left.get("username")).toLowerCase(Locale.ROOT)
                            .compareTo(String.valueOf(right.get("username")).toLowerCase(Locale.ROOT));
                });
        agents.sort(
                (left, right) -> {
                    int byDeleted = Boolean.compare(
                            (Boolean) left.get("is_deleted"), (Boolean) right.get("is_deleted"));
                    if (byDeleted != 0) {
                        return byDeleted;
                    }
                    return String.valueOf(left.get("agent_name")).toLowerCase(Locale.ROOT)
                            .compareTo(String.valueOf(right.get("agent_name")).toLowerCase(Locale.ROOT));
                });
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("users", users);
        result.put("agents", agents);
        return result;
    }

    /** 读取会话关联用户与 Agent 的当前审计状态。 */
    public Map<String, Object> getConversationAuditMetadata(Conversation conversation) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "SELECT u.username AS username, u.avatar AS user_avatar, u.is_deleted AS user_is_deleted,"
                                + " a.name AS agent_name, a.icon AS agent_icon"
                                + " FROM conversations c"
                                + " LEFT JOIN users u ON c.uid = u.uid"
                                + " LEFT JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE c.id = ?",
                        conversation.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", row.get("username") != null ? row.get("username") : conversation.getUid());
        String userAvatar = (String) row.get("user_avatar");
        result.put("user_avatar", userAvatar != null ? MinioUrls.normalizePublicMinioUrl(userAvatar) : null);
        result.put(
                "user_deleted", row.get("username") == null || Boolean.TRUE.equals(row.get("user_is_deleted")));
        result.put("agent_name", row.get("agent_name") != null ? row.get("agent_name") : conversation.getAgentId());
        String agentIcon = (String) row.get("agent_icon");
        result.put("agent_avatar", agentIcon != null ? MinioUrls.normalizePublicMinioUrl(agentIcon) : null);
        result.put("agent_deleted", row.get("agent_name") == null);
        return result;
    }

    // ==================== 用户与工具调用统计 ====================

    /** 统计用户总量与近期开启对话的活跃用户。 */
    public Map<String, Object> getUserActivityStats(LocalDateTime now) {
        LocalDateTime queryNow = now != null ? now : DateTimeUtils.utcNowNaive();

        Long totalUsers =
                jdbc.queryForObject("SELECT COUNT(id) FROM users WHERE is_deleted = 0", Long.class);
        String activeSql =
                "SELECT COUNT(DISTINCT u.id) FROM conversations c"
                        + " JOIN users u ON c.uid = u.uid"
                        + " JOIN agents a ON c.agent_id = a.slug"
                        + " WHERE " + VALID_FILTER;
        Long active24h =
                jdbc.queryForObject(
                        activeSql + " AND c.updated_at >= ?",
                        Long.class,
                        queryNow.minusDays(1));
        Long active30d =
                jdbc.queryForObject(
                        activeSql + " AND c.updated_at >= ?",
                        Long.class,
                        queryNow.minusDays(30));
        List<Map<String, Object>> dailyRows =
                jdbc.queryForList(
                        "SELECT DATE(DATE_ADD(c.updated_at, INTERVAL 8 HOUR)) AS date, COUNT(DISTINCT u.id) AS active_users"
                                + " FROM conversations c"
                                + " JOIN users u ON c.uid = u.uid"
                                + " JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + VALID_FILTER
                                + " AND c.updated_at >= ? AND c.updated_at < ?"
                                + " GROUP BY DATE(DATE_ADD(c.updated_at, INTERVAL 8 HOUR))",
                        queryNow.minusDays(120),
                        queryNow);
        Map<String, Long> dailyActiveByDate = new LinkedHashMap<>();
        for (Map<String, Object> row : dailyRows) {
            dailyActiveByDate.put(
                    String.valueOf(row.get("date")).substring(0, 10),
                    row.get("active_users") == null ? 0L : ((Number) row.get("active_users")).longValue());
        }
        LocalDate localToday = queryNow.plusHours(8).toLocalDate();
        List<Map<String, Object>> dailyActiveUsers = new ArrayList<>();
        for (int dayOffset = 119; dayOffset >= 0; dayOffset--) {
            String date = localToday.minusDays(dayOffset).toString();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date);
            item.put("active_users", dailyActiveByDate.getOrDefault(date, 0L));
            dailyActiveUsers.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_users", totalUsers == null ? 0 : totalUsers);
        result.put("active_users_24h", active24h == null ? 0 : active24h);
        result.put("active_users_30d", active30d == null ? 0 : active30d);
        result.put("daily_active_users", dailyActiveUsers);
        return result;
    }

    /** 统计有效用户与非删除会话中的工具调用。 */
    public Map<String, Object> getToolCallStats(LocalDateTime now) {
        LocalDateTime queryNow = now != null ? now : DateTimeUtils.utcNowNaive();
        String validFilters =
                "c.status NOT IN ('deleted','subagent') AND u.is_deleted = 0";
        String join =
                " FROM tool_calls tc"
                        + " JOIN messages m ON tc.message_id = m.id"
                        + " JOIN conversations c ON m.conversation_id = c.id"
                        + " JOIN users u ON c.uid = u.uid"
                        + " JOIN agents a ON c.agent_id = a.slug";
        Long totalCalls =
                jdbc.queryForObject("SELECT COUNT(tc.id)" + join + " WHERE " + validFilters, Long.class);
        Long successfulCalls =
                jdbc.queryForObject(
                        "SELECT COUNT(tc.id)" + join + " WHERE " + validFilters + " AND tc.status = 'success'",
                        Long.class);
        long total = totalCalls == null ? 0 : totalCalls;
        long successful = successfulCalls == null ? 0 : successfulCalls;

        List<Map<String, Object>> mostUsedRows =
                jdbc.queryForList(
                        "SELECT tc.tool_name AS tool_name, COUNT(tc.id) AS count" + join
                                + " WHERE " + validFilters
                                + " GROUP BY tc.tool_name ORDER BY COUNT(tc.id) DESC LIMIT 10");
        List<Map<String, Object>> errorRows =
                jdbc.queryForList(
                        "SELECT tc.tool_name AS tool_name, COUNT(tc.id) AS error_count" + join
                                + " WHERE " + validFilters + " AND tc.status = 'error'"
                                + " GROUP BY tc.tool_name");

        List<Map<String, Object>> dailyToolCalls = new ArrayList<>();
        for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
            LocalDateTime dayStart = queryNow.minusDays(dayOffset + 1);
            LocalDateTime dayEnd = queryNow.minusDays(dayOffset);
            Long count =
                    jdbc.queryForObject(
                            "SELECT COUNT(tc.id)" + join + " WHERE " + validFilters
                                    + " AND tc.created_at >= ? AND tc.created_at < ?",
                            Long.class,
                            dayStart,
                            dayEnd);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", dayStart.toLocalDate().toString());
            item.put("call_count", count == null ? 0 : count);
            dailyToolCalls.add(item);
        }

        Map<String, Object> toolErrorDistribution = new LinkedHashMap<>();
        for (Map<String, Object> row : errorRows) {
            toolErrorDistribution.put(
                    String.valueOf(row.get("tool_name")), ((Number) row.get("error_count")).longValue());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_calls", total);
        result.put("successful_calls", successful);
        result.put("failed_calls", total - successful);
        result.put("success_rate", total == 0 ? 0 : Math.round(successful * 10000.0 / total) / 100.0);
        List<Map<String, Object>> mostUsed = new ArrayList<>();
        for (Map<String, Object> row : mostUsedRows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool_name", row.get("tool_name"));
            item.put("count", ((Number) row.get("count")).longValue());
            mostUsed.add(item);
        }
        result.put("most_used_tools", mostUsed);
        result.put("tool_error_distribution", toolErrorDistribution);
        java.util.Collections.reverse(dailyToolCalls);
        result.put("daily_tool_calls", dailyToolCalls);
        return result;
    }

    /** 汇总仍存在 Agent 在有效用户与非删除会话中的使用情况。 */
    public Map<String, Object> getAgentAnalytics() {
        List<Agent> agents =
                jdbc.query(
                        "SELECT * FROM agents ORDER BY name ASC",
                        (rs, rowNum) -> {
                            Agent agent = new Agent();
                            agent.setSlug(rs.getString("slug"));
                            agent.setName(rs.getString("name"));
                            agent.setIcon(rs.getString("icon"));
                            return agent;
                        });
        String validFilters = "c.status NOT IN ('deleted','subagent') AND u.is_deleted = 0";

        Map<String, Long> conversationCounts = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT c.agent_id AS agent_id, COUNT(c.id) AS count FROM conversations c"
                                + " JOIN users u ON c.uid = u.uid JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + validFilters + " GROUP BY c.agent_id")) {
            conversationCounts.put(
                    String.valueOf(row.get("agent_id")),
                    row.get("count") == null ? 0L : ((Number) row.get("count")).longValue());
        }

        Map<String, long[]> feedbackByAgent = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT c.agent_id AS agent_id, COUNT(mf.id) AS total,"
                                + " SUM(CASE WHEN mf.rating = 'like' THEN 1 ELSE 0 END) AS positive"
                                + " FROM message_feedbacks mf"
                                + " JOIN messages m ON mf.message_id = m.id"
                                + " JOIN conversations c ON m.conversation_id = c.id"
                                + " JOIN users u ON c.uid = u.uid JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + validFilters + " AND " + AUDIT_EXCLUSION
                                + " GROUP BY c.agent_id")) {
            feedbackByAgent.put(
                    String.valueOf(row.get("agent_id")),
                    new long[] {
                            row.get("total") == null ? 0 : ((Number) row.get("total")).longValue(),
                            row.get("positive") == null ? 0 : ((Number) row.get("positive")).longValue()
                    });
        }

        Map<String, Long> toolCounts = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT c.agent_id AS agent_id, COUNT(tc.id) AS count FROM tool_calls tc"
                                + " JOIN messages m ON tc.message_id = m.id"
                                + " JOIN conversations c ON m.conversation_id = c.id"
                                + " JOIN users u ON c.uid = u.uid JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + validFilters + " GROUP BY c.agent_id")) {
            toolCounts.put(
                    String.valueOf(row.get("agent_id")),
                    row.get("count") == null ? 0L : ((Number) row.get("count")).longValue());
        }

        List<Map<String, Object>> conversationStats = new ArrayList<>();
        List<Map<String, Object>> satisfactionStats = new ArrayList<>();
        List<Map<String, Object>> toolUsage = new ArrayList<>();
        for (Agent agent : agents) {
            long conversationCount = conversationCounts.getOrDefault(agent.getSlug(), 0L);
            long[] feedback = feedbackByAgent.getOrDefault(agent.getSlug(), new long[] {0, 0});
            double satisfactionRate =
                    feedback[0] == 0 ? 100 : Math.round(feedback[1] * 10000.0 / feedback[0]) / 100.0;
            Map<String, Object> convItem = new LinkedHashMap<>();
            convItem.put("agent_id", agent.getSlug());
            convItem.put("conversation_count", conversationCount);
            conversationStats.add(convItem);
            Map<String, Object> satItem = new LinkedHashMap<>();
            satItem.put("agent_id", agent.getSlug());
            satItem.put("satisfaction_rate", satisfactionRate);
            satItem.put("total_feedbacks", feedback[0]);
            satisfactionStats.add(satItem);
            Map<String, Object> toolItem = new LinkedHashMap<>();
            toolItem.put("agent_id", agent.getSlug());
            toolItem.put("tool_usage_count", toolCounts.getOrDefault(agent.getSlug(), 0L));
            toolUsage.add(toolItem);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_agents", agents.size());
        result.put("agent_conversation_counts", conversationStats);
        result.put("agent_satisfaction_rates", satisfactionStats);
        result.put("agent_tool_usage", toolUsage);
        Map<String, String> agentNames = new LinkedHashMap<>();
        for (Agent agent : agents) {
            agentNames.put(agent.getSlug(), agent.getName());
        }
        result.put("agent_names", agentNames);
        return result;
    }

    /** 读取有效用户与非删除会话的 Dashboard 基础计数。 */
    public Map<String, Object> getBasicStats() {
        String validFilters = "c.status NOT IN ('deleted','subagent') AND u.is_deleted = 0";
        String convJoin =
                " FROM conversations c"
                        + " JOIN users u ON c.uid = u.uid"
                        + " JOIN agents a ON c.agent_id = a.slug";
        Long totalConversations =
                jdbc.queryForObject("SELECT COUNT(c.id)" + convJoin + " WHERE " + validFilters, Long.class);
        Long activeConversations =
                jdbc.queryForObject(
                        "SELECT COUNT(c.id)" + convJoin + " WHERE " + validFilters + " AND c.status = 'active'",
                        Long.class);
        Long totalMessages =
                jdbc.queryForObject(
                        "SELECT COUNT(m.id) FROM messages m"
                                + " JOIN conversations c ON m.conversation_id = c.id"
                                + " JOIN users u ON c.uid = u.uid"
                                + " JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + validFilters + " AND " + AUDIT_EXCLUSION,
                        Long.class);
        Long totalUsers = jdbc.queryForObject("SELECT COUNT(id) FROM users WHERE is_deleted = 0", Long.class);
        Long totalFeedbacks =
                jdbc.queryForObject(
                        "SELECT COUNT(mf.id) FROM message_feedbacks mf"
                                + " JOIN messages m ON mf.message_id = m.id"
                                + " JOIN conversations c ON m.conversation_id = c.id"
                                + " JOIN users u ON c.uid = u.uid"
                                + " JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + validFilters + " AND " + AUDIT_EXCLUSION,
                        Long.class);
        Long likeCount =
                jdbc.queryForObject(
                        "SELECT COUNT(mf.id) FROM message_feedbacks mf"
                                + " JOIN messages m ON mf.message_id = m.id"
                                + " JOIN conversations c ON m.conversation_id = c.id"
                                + " JOIN users u ON c.uid = u.uid"
                                + " JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + validFilters + " AND " + AUDIT_EXCLUSION
                                + " AND mf.rating = 'like'",
                        Long.class);
        long feedbacks = totalFeedbacks == null ? 0 : totalFeedbacks;
        long likes = likeCount == null ? 0 : likeCount;

        Map<String, Object> feedbackStats = new LinkedHashMap<>();
        feedbackStats.put("total_feedbacks", feedbacks);
        feedbackStats.put("satisfaction_rate", feedbacks == 0 ? 100 : Math.round(likes * 10000.0 / feedbacks) / 100.0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_conversations", totalConversations == null ? 0 : totalConversations);
        result.put("active_conversations", activeConversations == null ? 0 : activeConversations);
        result.put("total_messages", totalMessages == null ? 0 : totalMessages);
        result.put("total_users", totalUsers == null ? 0 : totalUsers);
        result.put("feedback_stats", feedbackStats);
        return result;
    }

    /** 反馈关联数据行。 */
    public record FeedbackRow(MessageFeedback feedback, Message message, Conversation conversation, User user) {}

    /** 按可选评分和智能体过滤反馈关联数据。 */
    public List<FeedbackRow> listFeedbacks(String rating, String agentId) {
        StringBuilder sql = new StringBuilder(
                "SELECT mf.*, m.content AS m_content, m.role AS m_role, m.message_type AS m_message_type,"
                        + " m.created_at AS m_created_at, m.conversation_id AS m_conversation_id,"
                        + " c.thread_id AS c_thread_id, c.uid AS c_uid, c.agent_id AS c_agent_id, c.title AS c_title,"
                        + " c.status AS c_status, u.id AS u_id, u.username AS username, u.avatar AS u_avatar,"
                        + " u.is_deleted AS u_is_deleted"
                        + " FROM message_feedbacks mf"
                        + " JOIN messages m ON mf.message_id = m.id"
                        + " JOIN conversations c ON m.conversation_id = c.id"
                        + " JOIN users u ON mf.uid = u.uid"
                        + " JOIN agents a ON c.agent_id = a.slug"
                        + " WHERE c.status NOT IN ('deleted','subagent') AND u.is_deleted = 0 AND " + AUDIT_EXCLUSION);
        List<Object> args = new ArrayList<>();
        if (rating != null && !rating.isEmpty() && ("like".equals(rating) || "dislike".equals(rating))) {
            sql.append(" AND mf.rating = ?");
            args.add(rating);
        }
        if (agentId != null && !agentId.isEmpty()) {
            sql.append(" AND c.agent_id = ?");
            args.add(agentId);
        }
        sql.append(" ORDER BY mf.created_at DESC");
        return jdbc.query(
                sql.toString(),
                (rs, rowNum) -> {
                    MessageFeedback feedback = new MessageFeedback();
                    feedback.setId(rs.getObject("id") == null ? null : rs.getInt("id"));
                    feedback.setMessageId(rs.getObject("message_id") == null ? null : rs.getInt("message_id"));
                    feedback.setUid(rs.getString("uid"));
                    feedback.setRating(rs.getString("rating"));
                    feedback.setReason(rs.getString("reason"));
                    feedback.setCreatedAt(toLocalDateTime(rs.getTimestamp("created_at")));
                    Message message = new Message();
                    message.setId(feedback.getMessageId());
                    message.setConversationId(rs.getObject("m_conversation_id") == null ? null : rs.getInt("m_conversation_id"));
                    message.setRole(rs.getString("m_role"));
                    message.setContent(rs.getString("m_content"));
                    message.setMessageType(rs.getString("m_message_type"));
                    message.setCreatedAt(toLocalDateTime(rs.getTimestamp("m_created_at")));
                    Conversation conversation = new Conversation();
                    conversation.setId(rs.getObject("m_conversation_id") == null ? null : rs.getInt("m_conversation_id"));
                    conversation.setThreadId(rs.getString("c_thread_id"));
                    conversation.setUid(rs.getString("c_uid"));
                    conversation.setAgentId(rs.getString("c_agent_id"));
                    conversation.setTitle(rs.getString("c_title"));
                    conversation.setStatus(rs.getString("c_status"));
                    User user = null;
                    if (rs.getObject("u_id") != null) {
                        user = new User();
                        user.setId(rs.getInt("u_id"));
                        user.setUsername(rs.getString("username"));
                        user.setAvatar(rs.getString("u_avatar"));
                        user.setIsDeleted(rs.getObject("u_is_deleted") == null ? null : rs.getInt("u_is_deleted"));
                    }
                    return new FeedbackRow(feedback, message, conversation, user);
                },
                args.toArray());
    }

    // ==================== 调用分析时间序列 ====================

    /** 生成使用上海时区显示的时间分组表达式（对应 _time_group_format）。 */
    private static String timeGroupFormat(String column, String timeRange) {
        String shifted = "DATE_ADD(" + column + ", INTERVAL 8 HOUR)";
        if ("14hours".equals(timeRange)) {
            return "DATE_FORMAT(" + shifted + ", '%Y-%m-%d %H:00')";
        }
        if ("14weeks".equals(timeRange)) {
            return "DATE_FORMAT(" + shifted + ", '%Y-%v')";
        }
        return "DATE_FORMAT(" + shifted + ", '%Y-%m-%d')";
    }

    /** 查询并补齐十四个时间区间的调用分析序列。 */
    public Map<String, Object> getCallTimeseries(
            String metricType, String timeRange, OffsetDateTime now, ZonedDateTime localNow) {
        OffsetDateTime queryNow = now != null ? now : DateTimeUtils.utcNow();
        ZonedDateTime queryLocalNow = localNow != null ? localNow : DateTimeUtils.shanghaiNow();
        int intervals = 14;

        ZonedDateTime baseLocalTime;
        LocalDateTime queryStartTime;
        if ("14hours".equals(timeRange)) {
            OffsetDateTime startUtc = queryNow.minusHours(intervals - 1L);
            queryStartTime = startUtc.atZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
            baseLocalTime = DateTimeUtils.ensureShanghai(startUtc);
        } else if ("14weeks".equals(timeRange)) {
            ZonedDateTime base =
                    queryLocalNow.minusWeeks(intervals - 1L).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            base = base.toLocalDate().atStartOfDay(base.getZone());
            baseLocalTime = base;
            queryStartTime = base.withZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
        } else {
            OffsetDateTime startUtc = queryNow.minusDays(intervals - 1L);
            queryStartTime = startUtc.atZoneSameInstant(ZoneId.of("UTC")).toLocalDateTime();
            baseLocalTime = DateTimeUtils.ensureShanghai(startUtc);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        String validFilters = "c.status NOT IN ('deleted','subagent') AND u.is_deleted = 0";
        if ("models".equals(metricType)) {
            String messageGroup = timeGroupFormat("m.created_at", timeRange);
            rows =
                    jdbc.queryForList(
                            "SELECT " + messageGroup + " AS date, COUNT(m.id) AS count,"
                                    + " JSON_UNQUOTE(JSON_EXTRACT(m.extra_metadata, '$.response_metadata.model_name')) AS category"
                                    + " FROM messages m"
                                    + " JOIN conversations c ON m.conversation_id = c.id"
                                    + " JOIN users u ON c.uid = u.uid"
                                    + " JOIN agents a ON c.agent_id = a.slug"
                                    + " WHERE m.role = 'assistant' AND " + AUDIT_EXCLUSION
                                    + " AND m.created_at >= ? AND m.extra_metadata IS NOT NULL"
                                    + " AND " + validFilters
                                    + " GROUP BY " + messageGroup
                                    + ", JSON_UNQUOTE(JSON_EXTRACT(m.extra_metadata, '$.response_metadata.model_name'))"
                                    + " ORDER BY " + messageGroup,
                            queryStartTime);
        } else if ("agents".equals(metricType)) {
            String conversationGroup = timeGroupFormat("c.updated_at", timeRange);
            rows =
                    jdbc.queryForList(
                            "SELECT " + conversationGroup + " AS date, COUNT(c.id) AS count, c.agent_id AS category"
                                    + " FROM conversations c"
                                    + " JOIN users u ON c.uid = u.uid"
                                    + " JOIN agents a ON c.agent_id = a.slug"
                                    + " WHERE c.updated_at IS NOT NULL AND c.updated_at >= ?"
                                    + " AND " + validFilters
                                    + " GROUP BY " + conversationGroup + ", c.agent_id"
                                    + " ORDER BY " + conversationGroup,
                            queryStartTime);
        } else if ("tokens".equals(metricType)) {
            String messageGroup = timeGroupFormat("m.created_at", timeRange);
            for (String tokenName : List.of("input_tokens", "output_tokens")) {
                rows.addAll(
                        jdbc.queryForList(
                                "SELECT " + messageGroup + " AS date,"
                                        + " SUM(COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(m.extra_metadata, ?)) AS SIGNED), 0)) AS count,"
                                        + " ? AS category"
                                        + " FROM messages m"
                                        + " JOIN conversations c ON m.conversation_id = c.id"
                                        + " JOIN users u ON c.uid = u.uid"
                                        + " JOIN agents a ON c.agent_id = a.slug"
                                        + " WHERE m.created_at >= ? AND " + AUDIT_EXCLUSION
                                        + " AND m.extra_metadata IS NOT NULL"
                                        + " AND JSON_EXTRACT(m.extra_metadata, '$.usage_metadata') IS NOT NULL"
                                        + " AND " + validFilters
                                        + " GROUP BY " + messageGroup
                                        + " ORDER BY " + messageGroup,
                                "$.usage_metadata." + tokenName,
                                tokenName,
                                queryStartTime));
            }
        } else {
            String toolGroup = timeGroupFormat("tc.created_at", timeRange);
            rows =
                    jdbc.queryForList(
                            "SELECT " + toolGroup + " AS date, COUNT(tc.id) AS count, tc.tool_name AS category"
                                    + " FROM tool_calls tc"
                                    + " JOIN messages m ON tc.message_id = m.id"
                                    + " JOIN conversations c ON m.conversation_id = c.id"
                                    + " JOIN users u ON c.uid = u.uid"
                                    + " JOIN agents a ON c.agent_id = a.slug"
                                    + " WHERE tc.created_at >= ? AND " + validFilters
                                    + " GROUP BY " + toolGroup + ", tc.tool_name"
                                    + " ORDER BY " + toolGroup,
                            queryStartTime);
        }

        TreeSet<String> categorySet = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            Object category = row.get("category");
            if (category != null) {
                categorySet.add(String.valueOf(category));
            }
        }
        List<String> categories = new ArrayList<>(categorySet);
        if (categories.isEmpty()) {
            categories =
                    switch (metricType) {
                        case "models" -> List.of("unknown_model");
                        case "agents" -> List.of("unknown_agent");
                        case "tokens" -> List.of("input_tokens", "output_tokens");
                        default -> List.of("unknown_tool");
                    };
        }

        Map<String, String> agentNames = null;
        if ("agents".equals(metricType)) {
            List<String> agentSlugs = new ArrayList<>(categories);
            agentSlugs.removeIf(String::isEmpty);
            if (!agentSlugs.isEmpty()) {
                agentNames = new LinkedHashMap<>();
                for (Agent agent : agentRepository.listBySlugs(agentSlugs)) {
                    agentNames.put(agent.getSlug(), agent.getName());
                }
            }
        }

        Map<String, Map<String, Long>> timeData = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String dateKey = String.valueOf(row.get("date"));
            if ("14weeks".equals(timeRange)) {
                dateKey = isoWeekKey(dateKey);
            }
            timeData
                    .computeIfAbsent(dateKey, key -> new LinkedHashMap<>())
                    .put(String.valueOf(row.get("category")), ((Number) row.get("count")).longValue());
        }

        List<Map<String, Object>> data = new ArrayList<>();
        ZonedDateTime currentTime = baseLocalTime;
        for (int i = 0; i < intervals; i++) {
            String dateKey;
            if ("14hours".equals(timeRange)) {
                dateKey = currentTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00"));
            } else if ("14weeks".equals(timeRange)) {
                LocalDate date = currentTime.toLocalDate();
                dateKey = String.format(
                        "%d-%02d",
                        date.get(WeekFields.ISO.weekBasedYear()),
                        date.get(WeekFields.ISO.weekOfWeekBasedYear()));
            } else {
                dateKey = currentTime.toLocalDate().toString();
            }
            Map<String, Long> intervalData =
                    new LinkedHashMap<>(timeData.getOrDefault(dateKey, new LinkedHashMap<>()));
            long intervalTotal = 0;
            for (Long value : intervalData.values()) {
                intervalTotal += value == null ? 0 : value;
            }
            for (String category : categories) {
                intervalData.putIfAbsent(category, 0L);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", dateKey);
            item.put("data", intervalData);
            item.put("total", intervalTotal);
            data.add(item);
            if ("14hours".equals(timeRange)) {
                currentTime = currentTime.plusHours(1);
            } else if ("14weeks".equals(timeRange)) {
                currentTime = currentTime.plusWeeks(1);
            } else {
                currentTime = currentTime.plusDays(1);
            }
        }

        long totalCount;
        if ("tools".equals(metricType)) {
            Long total =
                    jdbc.queryForObject(
                            "SELECT COUNT(tc.id) FROM tool_calls tc"
                                    + " JOIN messages m ON tc.message_id = m.id"
                                    + " JOIN conversations c ON m.conversation_id = c.id"
                                    + " JOIN users u ON c.uid = u.uid"
                                    + " JOIN agents a ON c.agent_id = a.slug"
                                    + " WHERE c.status NOT IN ('deleted','subagent') AND u.is_deleted = 0",
                            Long.class);
            totalCount = total == null ? 0 : total;
        } else {
            totalCount = 0;
            for (Map<String, Object> item : data) {
                totalCount += ((Number) item.get("total")).longValue();
            }
        }
        Map<String, Object> peak = new LinkedHashMap<>();
        peak.put("total", 0L);
        peak.put("date", "");
        for (Map<String, Object> item : data) {
            if (((Number) item.get("total")).longValue() > ((Number) peak.get("total")).longValue()) {
                peak = item;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("categories", categories);
        result.put("total_count", totalCount);
        result.put("average_count", intervals == 0 ? 0 : Math.round(totalCount * 100.0 / intervals) / 100.0);
        result.put("peak_count", peak.get("total"));
        result.put("peak_date", peak.get("date"));
        result.put("agent_names", agentNames);
        return result;
    }

    /**
     * 参考实现的 14weeks 日期键转换：strptime("{date_key}-1", "%Y-%W-%w") 后取 isocalendar。
     * %W 为以周一为首周日的年内周序号（0 起，首周之前的日期计入第 0 周）；%w=1 表示周一，
     * 故结果即「第 W 周的周一」的 ISO 年-周。
     */
    static String isoWeekKey(String dateKey) {
        String[] parts = dateKey.split("-");
        int year = Integer.parseInt(parts[0]);
        int week = Integer.parseInt(parts[1]);
        LocalDate firstMonday = LocalDate.of(year, 1, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
        LocalDate date = firstMonday.plusWeeks(Math.max(week - 1L, 0));
        return String.format(
                "%d-%02d",
                date.get(WeekFields.ISO.weekBasedYear()),
                date.get(WeekFields.ISO.weekOfWeekBasedYear()));
    }

    // ==================== 会话（Thread）分析 ====================

    /** 统计会话（Thread）汇总、每日趋势、消息深度、Agent 与用户分布。 */
    public Map<String, Object> getThreadAnalytics(
            String timeRange, String agentId, boolean includeSubagents, LocalDateTime now) {
        LocalDateTime queryNow = now != null ? now : DateTimeUtils.utcNowNaive();
        int days = switch (timeRange == null ? "30days" : timeRange) {
            case "7days" -> 7;
            case "14days" -> 14;
            case "90days" -> 90;
            default -> 30;
        };
        LocalDateTime localStartDay = queryNow.plusHours(8)
                .withHour(0).withMinute(0).withSecond(0).withNano(0)
                .minusDays(days - 1L);
        LocalDateTime queryStartTime = localStartDay.minusHours(8);

        String statusFilter = includeSubagents ? "c.status != 'deleted'" : "c.status NOT IN ('deleted','subagent')";
        String conversationFilters =
                "c.created_at IS NOT NULL AND " + statusFilter + " AND u.is_deleted = 0"
                        + (agentId != null && !agentId.isEmpty() ? " AND c.agent_id = ?" : "");
        List<Object> filterArgs = new ArrayList<>();
        if (agentId != null && !agentId.isEmpty()) {
            filterArgs.add(agentId);
        }
        String convJoin =
                " FROM conversations c"
                        + " JOIN users u ON c.uid = u.uid"
                        + " JOIN agents a ON c.agent_id = a.slug";

        Map<String, Object> summaryRow =
                jdbc.queryForMap(
                        "SELECT COUNT(c.id) AS total_threads,"
                                + " SUM(CASE WHEN c.is_pinned THEN 1 ELSE 0 END) AS pinned_threads"
                                + convJoin + " WHERE " + conversationFilters,
                        filterArgs.toArray());

        Map<String, Object> messageSummaryRow =
                jdbc.queryForMap(
                        "SELECT COUNT(m.id) AS total_messages,"
                                + " COUNT(DISTINCT CASE WHEN m.created_at >= ? THEN m.conversation_id END) AS active_threads"
                                + " FROM messages m"
                                + " JOIN conversations c ON m.conversation_id = c.id"
                                + " JOIN users u ON c.uid = u.uid"
                                + " JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + conversationFilters + " AND " + AUDIT_EXCLUSION,
                        prepend(filterArgs, queryStartTime).toArray());

        List<Object> tokenArgs = new ArrayList<>();
        String tokenTotals = conversationTokenTotals(null, tokenArgs);
        Long totalTokens =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(tt.total_tokens), 0) FROM (" + tokenTotals + ") tt"
                                + " JOIN conversations c ON tt.conversation_id = c.id"
                                + " JOIN users u ON c.uid = u.uid"
                                + " JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + conversationFilters,
                        Long.class,
                        filterArgs.toArray());

        long totalThreads = asLong(summaryRow.get("total_threads"));
        long activeThreads = asLong(messageSummaryRow.get("active_threads"));
        long pinnedThreads = asLong(summaryRow.get("pinned_threads"));
        long totalMessages = asLong(messageSummaryRow.get("total_messages"));

        String newThreadDate = "DATE(DATE_ADD(c.created_at, INTERVAL 8 HOUR))";
        Map<String, Long> newThreadsByDate = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT " + newThreadDate + " AS date, COUNT(c.id) AS count" + convJoin
                                + " WHERE " + conversationFilters
                                + " AND c.created_at >= ? AND c.created_at <= ?"
                                + " GROUP BY " + newThreadDate,
                        append(filterArgs, queryStartTime, queryNow).toArray())) {
            newThreadsByDate.put(
                    String.valueOf(row.get("date")).substring(0, 10),
                    row.get("count") == null ? 0L : ((Number) row.get("count")).longValue());
        }

        String messageDate = "DATE(DATE_ADD(m.created_at, INTERVAL 8 HOUR))";
        Map<String, Map<String, Long>> activityByDate = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT " + messageDate + " AS date, COUNT(DISTINCT m.conversation_id) AS active_threads,"
                                + " COUNT(m.id) AS message_count"
                                + " FROM messages m"
                                + " JOIN conversations c ON m.conversation_id = c.id"
                                + " JOIN users u ON c.uid = u.uid"
                                + " JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE m.created_at >= ? AND m.created_at <= ?"
                                + " AND " + AUDIT_EXCLUSION + " AND " + conversationFilters
                                + " GROUP BY " + messageDate,
                        // 占位符顺序：created_at >= ?、created_at <= ?、会话过滤条件
                        append(List.of(queryStartTime, queryNow), filterArgs.toArray()).toArray())) {
            Map<String, Long> activity = new LinkedHashMap<>();
            activity.put(
                    "active_threads", row.get("active_threads") == null ? 0L : ((Number) row.get("active_threads")).longValue());
            activity.put(
                    "message_count", row.get("message_count") == null ? 0L : ((Number) row.get("message_count")).longValue());
            activityByDate.put(String.valueOf(row.get("date")).substring(0, 10), activity);
        }

        List<Map<String, Object>> dailyTrends = new ArrayList<>();
        for (int dayOffset = 0; dayOffset < days; dayOffset++) {
            String dateKey = localStartDay.plusDays(dayOffset).toLocalDate().toString();
            Map<String, Long> activity = activityByDate.getOrDefault(dateKey, new LinkedHashMap<>());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", dateKey);
            item.put("new_threads", newThreadsByDate.getOrDefault(dateKey, 0L));
            item.put("active_threads", activity.getOrDefault("active_threads", 0L));
            item.put("message_count", activity.getOrDefault("message_count", 0L));
            dailyTrends.add(item);
        }

        // 3. 消息深度分布 (0条, 1-2条, 3-5条, 6-10条, 11-20条, 20+条)
        String messageCount = "COALESCE(cs.message_count, 0)";
        Map<String, Object> depthRow =
                jdbc.queryForMap(
                        "SELECT"
                                + " SUM(CASE WHEN " + messageCount + " = 0 THEN 1 ELSE 0 END) AS d0,"
                                + " SUM(CASE WHEN " + messageCount + " >= 1 AND " + messageCount + " <= 2 THEN 1 ELSE 0 END) AS d1_2,"
                                + " SUM(CASE WHEN " + messageCount + " >= 3 AND " + messageCount + " <= 5 THEN 1 ELSE 0 END) AS d3_5,"
                                + " SUM(CASE WHEN " + messageCount + " >= 6 AND " + messageCount + " <= 10 THEN 1 ELSE 0 END) AS d6_10,"
                                + " SUM(CASE WHEN " + messageCount + " >= 11 AND " + messageCount + " <= 20 THEN 1 ELSE 0 END) AS d11_20,"
                                + " SUM(CASE WHEN " + messageCount + " > 20 THEN 1 ELSE 0 END) AS d20_plus"
                                + " FROM conversations c"
                                + " LEFT JOIN conversation_stats cs ON c.id = cs.conversation_id"
                                + " JOIN users u ON c.uid = u.uid"
                                + " JOIN agents a ON c.agent_id = a.slug"
                                + " WHERE " + conversationFilters,
                        filterArgs.toArray());
        Map<String, Object> depthDistribution = new LinkedHashMap<>();
        depthDistribution.put("0 条", asLong(depthRow.get("d0")));
        depthDistribution.put("1-2 条", asLong(depthRow.get("d1_2")));
        depthDistribution.put("3-5 条", asLong(depthRow.get("d3_5")));
        depthDistribution.put("6-10 条", asLong(depthRow.get("d6_10")));
        depthDistribution.put("11-20 条", asLong(depthRow.get("d11_20")));
        depthDistribution.put("20+ 条", asLong(depthRow.get("d20_plus")));

        // 4. 各智能体会话分布
        List<Object> agentArgs = new ArrayList<>(filterArgs);
        String agentGroupSql =
                "SELECT c.agent_id AS agent_id, COUNT(c.id) AS thread_count,"
                        + " COALESCE(SUM(cs.message_count), 0) AS message_count,"
                        + " COALESCE(SUM(tt.total_tokens), 0) AS token_count"
                        + " FROM conversations c"
                        + " JOIN (" + conversationTokenTotals(null, agentArgs) + ") tt ON c.id = tt.conversation_id"
                        + " LEFT JOIN conversation_stats cs ON c.id = cs.conversation_id"
                        + " JOIN users u ON c.uid = u.uid"
                        + " JOIN agents a ON c.agent_id = a.slug"
                        + " WHERE " + conversationFilters
                        + " GROUP BY c.agent_id ORDER BY COUNT(c.id) DESC";
        List<Map<String, Object>> agentRows = jdbc.queryForList(agentGroupSql, agentArgs.toArray());
        List<String> agentSlugs = new ArrayList<>();
        for (Map<String, Object> row : agentRows) {
            String slug = (String) row.get("agent_id");
            if (slug != null && !slug.isEmpty()) {
                agentSlugs.add(slug);
            }
        }
        Map<String, String> agentNamesMap = new LinkedHashMap<>();
        Map<String, String> agentAvatarsMap = new LinkedHashMap<>();
        if (!agentSlugs.isEmpty()) {
            for (Agent agent : agentRepository.listBySlugs(agentSlugs)) {
                agentNamesMap.put(agent.getSlug(), agent.getName());
                agentAvatarsMap.put(
                        agent.getSlug(),
                        agent.getIcon() != null ? MinioUrls.normalizePublicMinioUrl(agent.getIcon()) : null);
            }
        }
        List<Map<String, Object>> agentDistribution = new ArrayList<>();
        for (Map<String, Object> row : agentRows) {
            String slug = String.valueOf(row.get("agent_id"));
            long threadCount = asLong(row.get("thread_count"));
            long messageCountValue = asLong(row.get("message_count"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agent_id", row.get("agent_id"));
            item.put("agent_name", agentNamesMap.getOrDefault(slug, slug));
            item.put("agent_avatar", agentAvatarsMap.get(slug));
            item.put("thread_count", threadCount);
            item.put("message_count", messageCountValue);
            item.put("token_count", asLong(row.get("token_count")));
            item.put(
                    "avg_messages",
                    threadCount == 0 ? 0.0 : Math.round(messageCountValue * 10.0 / threadCount) / 10.0);
            agentDistribution.add(item);
        }

        // 5. 高频用户活跃排行
        List<Map<String, Object>> topUsers = new ArrayList<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT c.uid AS uid, u.username AS username, u.avatar AS avatar,"
                                + " COUNT(c.id) AS thread_count,"
                                + " COALESCE(SUM(cs.message_count), 0) AS message_count,"
                                + " MAX(c.updated_at) AS last_active_at"
                                + " FROM conversations c"
                                + " JOIN users u ON c.uid = u.uid"
                                + " JOIN agents a ON c.agent_id = a.slug"
                                + " LEFT JOIN conversation_stats cs ON c.id = cs.conversation_id"
                                + " WHERE " + conversationFilters
                                + " GROUP BY c.uid, u.username, u.avatar"
                                + " ORDER BY COUNT(c.id) DESC LIMIT 10",
                        filterArgs.toArray())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uid", row.get("uid"));
            item.put("username", row.get("username") != null ? row.get("username") : row.get("uid"));
            String avatar = (String) row.get("avatar");
            item.put("avatar", avatar != null ? MinioUrls.normalizePublicMinioUrl(avatar) : null);
            item.put("thread_count", asLong(row.get("thread_count")));
            item.put("message_count", asLong(row.get("message_count")));
            LocalDateTime lastActiveAt = toLocalDateTime(row.get("last_active_at"));
            item.put("last_active_at", lastActiveAt == null ? null : lastActiveAt.format(NAIVE_ISO));
            topUsers.add(item);
        }

        // 6. 状态分布
        Map<String, Object> statusDistribution = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "SELECT c.status AS status, COUNT(c.id) AS count" + convJoin
                                + " WHERE " + conversationFilters + " GROUP BY c.status",
                        filterArgs.toArray())) {
            statusDistribution.put(
                    row.get("status") == null ? "unknown" : String.valueOf(row.get("status")),
                    asLong(row.get("count")));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_threads", totalThreads);
        summary.put("active_threads", activeThreads);
        summary.put("total_messages", totalMessages);
        summary.put("total_tokens", totalTokens == null ? 0 : totalTokens);
        summary.put(
                "avg_messages_per_thread",
                totalThreads == 0 ? 0.0 : Math.round(totalMessages * 10.0 / totalThreads) / 10.0);
        summary.put(
                "avg_tokens_per_thread",
                totalThreads == 0 ? 0.0 : (double) Math.round((totalTokens == null ? 0 : totalTokens) * 1.0 / totalThreads));
        summary.put("pinned_threads", pinnedThreads);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("daily_trends", dailyTrends);
        result.put("depth_distribution", depthDistribution);
        result.put("agent_distribution", agentDistribution);
        result.put("top_users", topUsers);
        result.put("status_distribution", statusDistribution);
        return result;
    }

    // ==================== 内部工具 ====================

    private static long asLong(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String naiveIso(Object value) {
        LocalDateTime time = toLocalDateTime(value);
        return time == null ? "" : time.format(NAIVE_ISO);
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    private static List<Object> prepend(List<Object> args, Object value) {
        List<Object> result = new ArrayList<>();
        result.add(value);
        result.addAll(args);
        return result;
    }

    /** 合并参数列表：先取前缀集合，再并入若干值（用于按 SQL 占位符顺序拼参数）。 */
    private static List<Object> append(List<Object> prefix, Object... values) {
        List<Object> result = new ArrayList<>(prefix);
        for (Object value : values) {
            if (value instanceof Object[] array) {
                for (Object item : array) {
                    result.add(item);
                }
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(i == 0 ? "?" : ", ?");
        }
        return builder.toString();
    }
}
