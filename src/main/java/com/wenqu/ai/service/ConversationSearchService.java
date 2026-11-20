package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 历史对话检索：在<b>当前用户自己的问渠问答记录</b>里按关键词找消息，或读取某次会话的往来。
 *
 * <p>与框架原生 {@code session_search} 的分工：框架那套搜的是 AgentScope 运行时会话日志
 * （工作区内的执行轨迹，且随记忆总闸在游客/记忆隔离轮被整组关掉）；本服务直接查
 * {@code c_ai_session}/{@code c_ai_message}，覆盖「我在问渠上问过什么」这条产品语义，
 * 在记忆隔离、游客、子智能体场景都按同一套归属规则（只给本人）工作。
 *
 * <p>安全边界：所有查询都强制 {@code user_id = 当前 uid}，且排除 {@code kind=temp}
 * （子智能体分支的临时会话不是用户对话）。读取指定会话时再核对一次归属，防止拿别人 sessionId 越权。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class ConversationSearchService {

    private static final int MAX_LIMIT = 30;
    private static final int SNIPPET_CHARS = 240;
    private static final int MAX_MESSAGE_CHARS = 4_000;

    private final SessionMapper sessionMapper;
    private final MessageMapper messageMapper;

    public ConversationSearchService(SessionMapper sessionMapper, MessageMapper messageMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * 关键词检索：返回命中的会话（每个会话带若干条命中片段），按最近命中时间倒序。
     *
     * @param days  只看最近多少天（null 或 <=0 = 不限）
     * @param limit 最多返回多少个会话（1..30）
     */
    public Map<String, Object> search(String uid, String keyword, Integer days, Integer limit) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("需要用户上下文");
        String kw = keyword == null ? "" : keyword.trim();
        int n = limit == null ? 8 : Math.max(1, Math.min(MAX_LIMIT, limit));
        LocalDateTime since = (days != null && days > 0) ? LocalDateTime.now().minusDays(days) : null;
        if (kw.isEmpty()) {
            return recent(uid, n);
        }
        // 多取一些命中行，再按会话聚拢：一个会话可能命中多轮
        List<Map<String, Object>> hits = messageMapper.searchMessagesByKeyword(uid, kw, since, n * 5);
        Map<String, Map<String, Object>> bySession = new LinkedHashMap<>();
        for (Map<String, Object> row : hits) {
            String sid = str(row.get("sessionId"));
            if (sid == null) continue;
            Map<String, Object> s = bySession.get(sid);
            if (s == null) {
                if (bySession.size() >= n) continue;
                s = new LinkedHashMap<>();
                s.put("sessionId", sid);
                s.put("title", str(row.get("title")));
                s.put("agentName", str(row.get("agentName")));
                s.put("updatedAt", str(row.get("createTime")));
                s.put("snippets", new ArrayList<Map<String, Object>>());
                bySession.put(sid, s);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> snippets = (List<Map<String, Object>>) s.get("snippets");
            if (snippets.size() < 3) {
                snippets.add(Map.of(
                        "role", str(row.get("role")),
                        "time", str(row.get("createTime")),
                        "excerpt", excerpt(str(row.get("content")), kw)));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", kw);
        out.put("count", bySession.size());
        out.put("sessions", new ArrayList<>(bySession.values()));
        return out;
    }

    /** 最近会话列表（关键词为空时给「最近和我在聊什么」，供模型先定位再读细节）。 */
    public Map<String, Object> recent(String uid, int limit) {
        List<Session> rows = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(Session::getUserId, uid)
                .and(w -> w.isNull(Session::getKind).or().ne(Session::getKind, "temp"))
                .orderByDesc(Session::getUpdateTime)
                .last("LIMIT " + limit));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Session s : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getId());
            m.put("title", s.getTitle());
            m.put("agentName", s.getAgentName());
            m.put("messageCount", s.getMessageCount());
            m.put("updatedAt", s.getUpdateTime() == null ? null : s.getUpdateTime().toString());
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("sessions", list);
        return out;
    }

    /** 读取一个会话的往来；会话不属于当前用户时返回 null（调用方回「找不到」，不泄露存在性）。 */
    public Map<String, Object> readConversation(String uid, String sessionId, Integer limit) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("需要用户上下文");
        if (sessionId == null || sessionId.isBlank()) return null;
        Session s = sessionMapper.selectById(sessionId);
        if (s == null || !uid.equals(s.getUserId()) || "temp".equals(s.getKind())) return null;
        int n = limit == null ? 40 : Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> msgs = messageMapper.readConversationMessages(sessionId, n);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : msgs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", str(row.get("role")));
            String content = str(row.get("content"));
            if (content != null && content.length() > MAX_MESSAGE_CHARS) {
                content = content.substring(0, MAX_MESSAGE_CHARS) + "…（已截断）";
            }
            m.put("content", content);
            m.put("time", str(row.get("createTime")));
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("sessionId", sessionId);
        res.put("title", s.getTitle());
        res.put("agentName", s.getAgentName());
        res.put("messageCount", s.getMessageCount());
        res.put("messages", out);
        res.put("truncated", out.size() >= n);
        return res;
    }

    /** 命中词附近摘一段：优先以关键词为中心，避免只显示消息开头而看不到命中处。 */
    static String excerpt(String content, String keyword) {
        if (content == null) return "";
        String text = content.replaceAll("\\s+", " ").trim();
        if (text.length() <= SNIPPET_CHARS) return text;
        int idx = keyword == null || keyword.isBlank() ? -1
                : text.toLowerCase().indexOf(keyword.toLowerCase());
        int from = idx < 0 ? 0 : Math.max(0, idx - SNIPPET_CHARS / 3);
        int to = Math.min(text.length(), from + SNIPPET_CHARS);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
