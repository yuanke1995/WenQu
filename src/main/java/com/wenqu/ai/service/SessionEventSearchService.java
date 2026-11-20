package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.SessionEventMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.model.SessionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史对话「回溯」：在<b>当前用户自己</b>的会话事件账本（{@code c_ai_session_event}）里
 * 按关键词/类型检索事件，或读取、还原某一轮的执行轨迹（工具调用、审批、人在回路、错误、终态…）。
 *
 * <p>与 {@link ConversationSearchService} 的分工：
 * <ul>
 *   <li>那个按<b>正文</b>搜「我问过/聊过什么」（消息表）；</li>
 *   <li>本类按<b>过程事件</b>答「这一轮到底怎么跑出来的」（事件账本，{@code Tool、Approval、Error…}）。</li>
 * </ul>
 * 两者合起来才是「会话检索 + 回溯」：先定位到会话/轮，再回放那一轮的执行轨迹。
 *
 * <p>安全边界与 {@link ConversationSearchService} 完全一致：所有查询都强制
 * {@code user_id = 当前 uid}、排除 {@code kind=temp}（子智能体临时会话不是用户对话）；
 * 读取指定会话时再核对一次归属，拿别人的 sessionId 只会得到「找不到」。
 *
 * <p>账本行载荷是写入时的 JSON 摘要（见 {@link SessionEventService}），本类只做只读整形，
 * 不改变「账本不进模型输入」那条边界——这里是模型主动来查，不是把账本塞进上下文。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SessionEventSearchService {

    private static final int MAX_LIMIT = 50;
    private static final int SNIPPET_CHARS = 300;
    private static final int TRACE_PAYLOAD_CHARS = 400;
    private static final int MAX_EVENT_CHARS = 4_000;

    private final SessionMapper sessionMapper;
    private final SessionEventMapper eventMapper;
    private final SessionEventService eventService;

    public SessionEventSearchService(SessionMapper sessionMapper, SessionEventMapper eventMapper,
                                     SessionEventService eventService) {
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
        this.eventService = eventService;
    }

    /**
     * 关键词/类型检索：返回命中的事件（带会话标题与轮 id），按写入顺序倒序。
     * 关键词为空时退化为「最近的工具/审批等过程事件」，便于模型先看到最近都在跑什么。
     *
     * @param type  事件类型过滤（tool/approval/ask_user/plan/artifact/subagent/error/done…）；空=不限
     * @param days  只看最近多少天（null 或 <=0 = 不限）
     * @param limit 最多返回多少条事件（1..50）
     */
    public Map<String, Object> search(String uid, String keyword, String type, Integer days, Integer limit) {
        requireUid(uid);
        String kw = keyword == null ? "" : keyword.trim();
        String ty = type == null ? "" : type.trim();
        int n = limit == null ? 12 : Math.max(1, Math.min(MAX_LIMIT, limit));
        LocalDateTime since = (days != null && days > 0) ? LocalDateTime.now().minusDays(days) : null;

        List<Map<String, Object>> rows = eventMapper.searchEventsByKeyword(uid, kw, ty, since, n);
        List<Map<String, Object>> events = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", str(row.get("sessionId")));
            m.put("title", str(row.get("title")));
            m.put("turnId", str(row.get("turnId")));
            m.put("type", str(row.get("type")));
            m.put("at", str(row.get("createdAt")));
            String payload = str(row.get("payload"));
            m.put("excerpt", kw.isEmpty() ? abbreviate(payload, SNIPPET_CHARS) : excerpt(payload, kw));
            events.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyword", kw);
        out.put("type", ty);
        out.put("count", events.size());
        out.put("events", events);
        return out;
    }

    /**
     * 读取某个会话（或其中某一轮）的事件清单。
     * <p>带 {@code turnId} 时只读那一轮、按发生顺序；否则读会话最近的事件（倒序）。
     *
     * @return 会话不属于当前用户/不存在/是临时会话时返回 null（调用方回「找不到」，不泄露存在性）
     */
    public Map<String, Object> read(String uid, String sessionId, String turnId, Integer limit) {
        requireUid(uid);
        Session s = ownedSession(uid, sessionId);
        if (s == null) return null;
        int n = limit == null ? 100 : Math.max(1, Math.min(500, limit));
        List<SessionEvent> rows = (turnId != null && !turnId.isBlank())
                ? eventService.listByTurn(sessionId, uid, turnId, n)
                : eventService.listRecent(sessionId, uid, n);
        List<Map<String, Object>> events = new ArrayList<>();
        for (SessionEvent ev : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ev.getId());
            m.put("turnId", ev.getTurnId());
            m.put("type", ev.getType());
            m.put("at", ev.getCreatedAt() == null ? null : ev.getCreatedAt().toString());
            String payload = ev.getPayload() == null ? "" : ev.getPayload();
            m.put("payload", payload.length() > MAX_EVENT_CHARS
                    ? payload.substring(0, MAX_EVENT_CHARS) + "…（已截断）" : payload);
            events.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("title", s.getTitle());
        out.put("turnId", turnId == null || turnId.isBlank() ? null : turnId);
        out.put("count", events.size());
        out.put("events", events);
        return out;
    }

    /**
     * 还原某一轮的执行轨迹（模型可读的紧凑文本）：按发生顺序列出每个结构性事件，
     * 工具行解析出「第几步 · 成没成 · 耗时 · 入参/结果摘要」。这是「这一条回答怎么跑出来的」。
     *
     * @return 会话不属于当前用户/不存在/是临时会话时返回一句「找不到」；该轮没有事件时如实说明
     */
    public String trace(String uid, String sessionId, String turnId) {
        requireUid(uid);
        if (sessionId == null || sessionId.isBlank()) return "缺少会话 id。";
        Session s = ownedSession(uid, sessionId);
        if (s == null) return "找不到这个会话（不存在，或不属于你）。";
        if (turnId == null || turnId.isBlank()) return "缺少轮次 id（先用 session_event_read 或 session_search 拿到 turnId）。";
        List<SessionEvent> rows = eventService.listByTurn(sessionId, uid, turnId, 500);
        if (rows.isEmpty()) {
            return "这一轮没有可回放的过程事件（可能是旧数据，或该轮没触发任何结构性事件）。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("会话《").append(s.getTitle() == null ? s.getId() : s.getTitle())
                .append("》轮次 ").append(turnId)
                .append(" 的执行轨迹（共 ").append(rows.size()).append(" 个事件）：\n");
        int step = 0;
        for (SessionEvent ev : rows) {
            String type = ev.getType() == null ? "?" : ev.getType();
            String payload = ev.getPayload() == null ? "" : ev.getPayload();
            if ("tool".equals(type)) {
                step++;
                sb.append(step).append(". [工具] ").append(toolLine(payload)).append('\n');
            } else {
                sb.append("· [").append(type).append("] ").append(abbreviate(payload, TRACE_PAYLOAD_CHARS)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 工具事件载荷 → 「名字 · 状态 · 耗时 · 入参/结果摘要」一行。载荷不是 JSON 时退化为摘要。 */
    static String toolLine(String payload) {
        try {
            JSONObject o = JSON.parseObject(payload);
            if (o == null) return abbreviate(payload, TRACE_PAYLOAD_CHARS);
            StringBuilder sb = new StringBuilder();
            String label = o.getString("label");
            String name = o.getString("name");
            sb.append(label != null && !label.isBlank() ? label : (name == null ? "工具" : name));
            if (name != null && !name.isBlank() && label != null && !label.equals(name)) {
                sb.append('(').append(name).append(')');
            }
            String status = o.getString("status");
            if (status != null) sb.append(" · ").append(status);
            Object elapsed = o.get("elapsedMs");
            if (elapsed != null) sb.append(" · ").append(elapsed).append("ms");
            String args = o.getString("args");
            if (args != null && !args.isBlank()) sb.append(" · 入参=").append(abbreviate(args, 160));
            String result = o.getString("result");
            if (result != null && !result.isBlank()) sb.append(" · 结果=").append(abbreviate(result, 200));
            String error = o.getString("error");
            if (error != null && !error.isBlank()) sb.append(" · 错误=").append(abbreviate(error, 200));
            return sb.toString();
        } catch (Exception ignore) {
            return abbreviate(payload, TRACE_PAYLOAD_CHARS);
        }
    }

    /** 只认「属于当前用户、非临时」的真实会话；否则 null（不泄露存在性）。 */
    private Session ownedSession(String uid, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Session s = sessionMapper.selectById(sessionId);
        if (s == null || !uid.equals(s.getUserId()) || "temp".equals(s.getKind())) return null;
        return s;
    }

    private static void requireUid(String uid) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("需要用户上下文");
    }

    /** 命中词附近摘一段（与 ConversationSearchService 同口径，避免只显示开头看不到命中处）。 */
    static String excerpt(String text, String keyword) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= SNIPPET_CHARS) return flat;
        int idx = keyword == null || keyword.isBlank() ? -1 : flat.toLowerCase().indexOf(keyword.toLowerCase());
        int from = idx < 0 ? 0 : Math.max(0, idx - SNIPPET_CHARS / 3);
        int to = Math.min(flat.length(), from + SNIPPET_CHARS);
        return (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
