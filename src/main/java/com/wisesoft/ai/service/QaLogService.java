package com.wisesoft.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.mapper.MessageMapper;
import com.wisesoft.ai.mapper.QaFeedbackMapper;
import com.wisesoft.ai.mapper.QaLogMapper;
import com.wisesoft.ai.model.Message;
import com.wisesoft.ai.model.QaFeedback;
import com.wisesoft.ai.model.QaLog;
import com.wisesoft.ai.thread.ThreadPoolManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 问答数据闭环服务
 * - 落日志（异步，不阻塞 SSE 流式响应）
 * - 反馈 upsert（message_id 唯一）
 * - 统计看板聚合（热门问题 / 无命中率 / 反馈）
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaLogService {

    private final QaLogMapper qaLogMapper;
    private final QaFeedbackMapper feedbackMapper;
    private final MessageMapper messageMapper;

    /**
     * 异步落问答日志（不阻塞主流程）
     */
    public void logAsync(String sessionId, String question, String answer,
                         List<String> hitDocIds, boolean hasCitation, long elapsedMs,
                         String rewrittenQuery, String stageMsJson, boolean deepThink) {
        ThreadPoolManager.execute(() -> {
            try {
                QaLog log = new QaLog();
                log.setSessionId(sessionId);
                log.setQuestion(question == null ? "" : question.length() > 500 ? question.substring(0, 500) : question);
                log.setAnswerSummary(summary(answer));
                log.setHitDocIds(hitDocIds == null || hitDocIds.isEmpty()
                        ? null : hitDocIds.stream().distinct().collect(Collectors.joining(",")));
                log.setRewrittenQuery(rewrittenQuery == null || rewrittenQuery.equals(question)
                        ? null : (rewrittenQuery.length() > 500 ? rewrittenQuery.substring(0, 500) : rewrittenQuery));
                log.setHasCitation(hasCitation ? 1 : 0);
                log.setDeepThink(deepThink ? 1 : 0);
                log.setElapsedMs((int) Math.min(elapsedMs, Integer.MAX_VALUE));
                log.setStageMs(stageMsJson);
                qaLogMapper.insert(log);
            } catch (Exception e) {
                // L6 fail-loud：问答日志是反馈看板/知识缺口的数据源，丢失升级为 error（含 sessionId 便于排查）
                log.error("[FAIL-LOUD] 问答日志写入失败 session={}: {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * 提交/更新反馈（按 message_id upsert）
     */
    public void feedback(String messageId, int rating, String feedbackText) {
        if (messageId == null || messageId.isBlank()) {
            throw new com.wisesoft.ai.common.BizException("缺少 messageId");
        }
        if (rating != 0 && rating != 1) {
            throw new com.wisesoft.ai.common.BizException("rating 仅支持 0/1");
        }
        QaFeedback existing = feedbackMapper.selectOne(
                new LambdaQueryWrapper<QaFeedback>().eq(QaFeedback::getMessageId, messageId).last("limit 1"));
        if (existing != null) {
            existing.setRating(rating);
            existing.setFeedbackText(feedbackText);
            feedbackMapper.updateById(existing);
        } else {
            QaFeedback f = new QaFeedback();
            f.setMessageId(messageId);
            f.setRating(rating);
            f.setFeedbackText(feedbackText);
            feedbackMapper.insert(f);
        }
    }

    /**
     * 批量查询消息的既有评价（message_id → 1/0）。
     * 历史消息回显用：前端单选锁定态需要随刷新恢复，否则锁只存在于页面内存、刷新后可重新点另一边。
     */
    public Map<String, Integer> loadFeedbackRatings(java.util.Collection<String> messageIds) {
        Map<String, Integer> result = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty()) return result;
        List<String> ids = messageIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) return result;
        try {
            List<QaFeedback> list = feedbackMapper.selectList(new LambdaQueryWrapper<QaFeedback>()
                    .in(QaFeedback::getMessageId, ids));
            for (QaFeedback f : list) {
                if (f.getRating() != null) {
                    result.put(f.getMessageId(), f.getRating());
                }
            }
        } catch (Exception e) {
            // fail-loud：状态加载失败只影响锁定展示（可再次评价的旧行为兜底），不阻断历史读取
            log.warn("加载消息反馈状态失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 差评样本列表（反馈回流闭环）：
     * 👎 样本按时间倒序，联气回答消息还原「问题 + 回答摘要 + 引用块」，供看板一键加评估集 / 补知识块。
     * 消息已被对话组删除的样本跳过（feedback 行保留，不阻塞其余）。
     */
    public List<Map<String, Object>> listBadCases(int limit) {
        int n = Math.min(Math.max(1, limit), 100);
        List<QaFeedback> dislikes = feedbackMapper.selectList(new LambdaQueryWrapper<QaFeedback>()
                .eq(QaFeedback::getRating, 0)
                .orderByDesc(QaFeedback::getCreatedAt)
                .last("LIMIT " + n));
        if (dislikes.isEmpty()) return List.of();

        List<String> mids = dislikes.stream().map(QaFeedback::getMessageId).toList();
        Map<String, Message> msgById = new HashMap<>();
        messageMapper.selectBatchIds(mids).forEach(m -> msgById.put(m.getId(), m));

        // 按 session 预取 user 消息，还原每轮问题（sequence 配对，与评估生成同法）
        Set<String> sids = msgById.values().stream()
                .map(Message::getSessionId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, List<Message>> userMsgsBySession = new HashMap<>();
        if (!sids.isEmpty()) {
            messageMapper.selectList(new LambdaQueryWrapper<Message>()
                            .eq(Message::getRole, "user").in(Message::getSessionId, sids))
                    .forEach(u -> userMsgsBySession.computeIfAbsent(u.getSessionId(), k -> new ArrayList<>()).add(u));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (QaFeedback f : dislikes) {
            Message answer = msgById.get(f.getMessageId());
            if (answer == null) continue; // 消息已随对话组删除
            Message user = userMsgsBySession.getOrDefault(answer.getSessionId(), List.of()).stream()
                    .filter(u -> u.getSequence() < answer.getSequence())
                    .max(Comparator.comparingInt(Message::getSequence)).orElse(null);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("messageId", f.getMessageId());
            row.put("sessionId", answer.getSessionId());
            row.put("question", trim(user == null ? "" : user.getContent(), 100));
            row.put("answer", trim(answer.getContent(), 150));
            row.put("feedbackText", f.getFeedbackText());
            row.put("time", f.getCreatedAt());
            row.put("knowledgeIds", extractKnowledgeIds(answer.getSources()));
            rows.add(row);
        }
        return rows;
    }

    /** 从消息 sources JSON 提取引用的知识块 ID（与评估生成同语义） */
    private List<String> extractKnowledgeIds(String sourcesJson) {
        List<String> ids = new ArrayList<>();
        try {
            com.alibaba.fastjson2.JSONArray arr = com.alibaba.fastjson2.JSON.parseArray(sourcesJson);
            if (arr == null) return ids;
            for (int i = 0; i < arr.size(); i++) {
                com.alibaba.fastjson2.JSONObject o = arr.getJSONObject(i);
                if (o != null && o.getString("knowledgeId") != null) ids.add(o.getString("knowledgeId"));
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private String trim(String s, int n) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    /**
     * 统计看板：热门问题 / 无命中 / 反馈（全部走 SQL 聚合，避免全表拉取到内存）
     */
    public Map<String, Object> analyticsSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int limit = 2000; // 与原先 LIMIT 2000 语义一致
            Map<String, Object> stats = qaLogMapper.summaryStats(limit);
            long total = num(stats.get("total"));
            long noHit = num(stats.get("no_hit"));
            long cited = num(stats.get("cited"));

            result.put("topQuestions", qaLogMapper.topQuestions(limit).stream()
                    .map(m -> Map.of("question", String.valueOf(m.get("question")),
                            "count", num(m.get("cnt"))))
                    .toList());
            result.put("noHitRate", total == 0 ? 0 : Math.round(noHit * 1000.0 / total) / 10.0);
            result.put("total", total);
            result.put("citationRate", total == 0 ? 0 : Math.round(cited * 1000.0 / total) / 10.0);
            result.put("noHitQuestions", qaLogMapper.noHitQuestions(limit).stream()
                    .map(m -> Map.of("question", String.valueOf(m.get("question")),
                            "count", num(m.get("cnt"))))
                    .toList());

            // 反馈统计（COUNT 聚合，不拉全表）
            Map<String, Object> fb = feedbackMapper.feedbackStats();
            long likes = num(fb.get("likes"));
            long fbTotal = num(fb.get("total"));
            result.put("feedback", Map.of(
                    "total", fbTotal,
                    "likes", likes,
                    "dislikes", fbTotal - likes,
                    "likeRate", fbTotal == 0 ? 0 : Math.round(likes * 1000.0 / fbTotal) / 10.0));
        } catch (Exception e) {
            log.warn("统计聚合失败: {}", e.getMessage());
        }
        return result;
    }

    /** SQL 聚合返回的数值统一转 long（COUNT 为 Long，SUM(CASE...) 为 BigDecimal） */
    private long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 获取无命中问题列表（按频次降序，含最近提问时间），供前端一键入库
     */
    public List<Map<String, Object>> listUnmatched() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = qaLogMapper.noHitTop(LocalDateTime.now().minusDays(30));
            for (Map<String, Object> m : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("question", String.valueOf(m.get("question")));
                item.put("count", num(m.get("cnt")));
                Object latest = m.get("latest");
                item.put("latestTime", latest == null ? "" : String.valueOf(latest));
                result.add(item);
            }
        } catch (Exception e) {
            log.warn("查询无命中问题失败: {}", e.getMessage());
        }
        return result;
    }

    private String summary(String answer) {
        if (answer == null) return "";
        String s = answer.replaceAll("<related>[\\s\\S]*?</related>", "").replaceAll("\\[图片[^\\]]*\\]", " ").trim();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
