package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.mapper.AgentMapper;
import com.wisesoft.ai.mapper.MessageMapper;
import com.wisesoft.ai.mapper.QaFeedbackMapper;
import com.wisesoft.ai.mapper.QaLogMapper;
import com.wisesoft.ai.mapper.TraceSampleMapper;
import com.wisesoft.ai.mapper.WorkflowMapper;
import com.wisesoft.ai.mapper.WorkflowRunMapper;
import com.wisesoft.ai.model.Message;
import com.wisesoft.ai.model.Agent;
import com.wisesoft.ai.model.QaFeedback;
import com.wisesoft.ai.model.QaLog;
import com.wisesoft.ai.model.TraceSample;
import com.wisesoft.ai.model.Workflow;
import com.wisesoft.ai.model.WorkflowRun;
import com.wisesoft.ai.util.RequestUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * P1 Trace 与运营闭环（Coze Loop 模式）：
 * <ul>
 *   <li><b>统一执行 trace</b>：对话型（qa_log + 回答消息全过程 + 反馈）与工作流型（run 节点级 trace）
 *       一个口径看——列表同构、详情按类型展开；这是把散在看板/会话/运行历史三处的执行记录聚成一处；</li>
 *   <li><b>线上自动采样 → 标注 → 回流评测集</b>：定时任务按「差评必采 + 无引用 N 条 + 随机 N 条」入池，
 *       运营在池里标定期望命中的知识块后 {@link RetrievalEvaluationService#addCase} 回流（评测集版本化机制不动）。</li>
 * </ul>
 * <p>
 * 归属口径：trace 视图是<b>运营功能</b>（对标 Coze Loop），端点不进普通用户白名单——RBAC 下管理员直通、
 * 普通角色默认 403，工作流 run 详情也不做逐条归属校验（运营视角看全部，与看板统计同权限面）。
 * <p>
 * 数据边界（如实）：message_id/agent_id 两列上线后才有值，存量 qa_log 行为 NULL——
 * 详情按「摘要视图」降级展示（不带全过程），采样池只收 <b>有 message_id 关联</b>的新日志。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {

    private final QaLogMapper qaLogMapper;
    private final QaFeedbackMapper feedbackMapper;
    private final MessageMapper messageMapper;
    private final TraceSampleMapper sampleMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowMapper workflowMapper;
    private final AgentMapper agentMapper;
    private final RetrievalEvaluationService evalService;
    private final ConfigService configService;

    // --------------------------------------------------------------------------------------------------
    // 统一执行 trace
    // --------------------------------------------------------------------------------------------------

    /**
     * 执行列表（kind=chat 对话 / workflow 工作流；两类各自独立分页，不做跨类归一——
     * 归一分页要在 SQL 层 union 两种截然不同的行结构，复杂度买不来运营价值，前端用类型筛选切换）。
     *
     * @param keyword     问题/入参关键词（like）
     * @param hasCitation 对话型：有无引用（0/1）；工作流忽略
     * @param rating      对话型：评分（1 赞 / 0 差评，按反馈表 message_id 关联）；工作流忽略
     * @param days        只看最近 N 天（0=不限）
     */
    public Map<String, Object> list(String kind, int page, int size, String keyword,
                                    Integer hasCitation, Integer rating, Integer days, String agentId) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(10, size), 100);
        if ("workflow".equals(kind)) {
            return listWorkflowRuns(p, s, keyword, days);
        }
        return listChat(p, s, keyword, hasCitation, rating, days, agentId);
    }

    private Map<String, Object> listChat(int page, int size, String keyword,
                                         Integer hasCitation, Integer rating, Integer days, String agentId) {
        LambdaQueryWrapper<QaLog> qw = new LambdaQueryWrapper<QaLog>();
        if (keyword != null && !keyword.isBlank()) qw.like(QaLog::getQuestion, keyword.trim());
        if (hasCitation != null) qw.eq(QaLog::getHasCitation, hasCitation);
        if (agentId != null && !agentId.isBlank()) qw.eq(QaLog::getAgentId, agentId);
        if (days != null && days > 0) qw.ge(QaLog::getCreatedAt, LocalDateTime.now().minusDays(days));
        // 评分筛选：反馈表按 rating 拿 messageIds（新日志才有 message_id 关联，存量行不参与评分筛选）
        if (rating != null) {
            List<QaFeedback> fbs = feedbackMapper.selectList(new LambdaQueryWrapper<QaFeedback>()
                    .eq(QaFeedback::getRating, rating).orderByDesc(QaFeedback::getCreatedAt).last("LIMIT 2000"));
            List<String> mids = fbs.stream().map(QaFeedback::getMessageId).filter(Objects::nonNull).toList();
            qw.in(!mids.isEmpty(), QaLog::getMessageId, mids);
            if (mids.isEmpty()) {
                return pageOf(List.of(), 0, page, size);
            }
        }
        qw.orderByDesc(QaLog::getCreatedAt);
        Page<QaLog> pg = qaLogMapper.selectPage(new Page<>(page, size), qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QaLog l : pg.getRecords()) {
            Map<String, Object> r = baseRow(l.getId(), "chat", l.getCreatedAt(), l.getQuestion(),
                    l.getAnswerSummary(), l.getElapsedMs() == null ? null : l.getElapsedMs().longValue());
            r.put("agentId", l.getAgentId());
            r.put("hasCitation", l.getHasCitation());
            r.put("deepThink", l.getDeepThink());
            r.put("hitDocCount", l.getHitDocIds() == null || l.getHitDocIds().isBlank()
                    ? 0 : l.getHitDocIds().split(",").length);
            r.put("detailLinked", l.getMessageId() != null);
            rows.add(r);
        }
        // 批量补评分（分页行数少，直接 in 查）
        Map<String, Integer> ratings = feedbackRatings(rows.stream()
                .map(r -> (String) r.get("messageId")).filter(Objects::nonNull).toList());
        for (Map<String, Object> r : rows) {
            String mid = (String) r.get("messageId");
            r.put("rating", mid == null ? null : ratings.get(mid));
        }
        // 批量补智能体名（一页一次 in 查询，非逐行查表；未绑定留空由前端显示「全局」，已删除的回退显示 id）
        Map<String, String> agentNames = agentNames(rows.stream()
                .map(r -> (String) r.get("agentId"))
                .filter(a -> a != null && !a.isBlank()).distinct().toList());
        for (Map<String, Object> r : rows) {
            String aid = (String) r.get("agentId");
            if (aid != null && !aid.isBlank()) r.put("agentName", agentNames.getOrDefault(aid, aid));
        }
        return pageOf(rows, pg.getTotal(), page, size);
    }

    private Map<String, Object> listWorkflowRuns(int page, int size, String keyword, Integer days) {
        LambdaQueryWrapper<WorkflowRun> qw = new LambdaQueryWrapper<WorkflowRun>();
        if (keyword != null && !keyword.isBlank()) qw.like(WorkflowRun::getInputs, keyword.trim());
        if (days != null && days > 0) qw.ge(WorkflowRun::getStartedAt, LocalDateTime.now().minusDays(days));
        qw.orderByDesc(WorkflowRun::getStartedAt);
        Page<WorkflowRun> pg = runMapper.selectPage(new Page<>(page, size), qw);
        Map<String, String> wfNames = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkflowRun r : pg.getRecords()) {
            String wfName = wfNames.computeIfAbsent(r.getWorkflowId(), this::workflowName);
            Map<String, Object> row = baseRow(r.getId(), "workflow", r.getStartedAt(),
                    wfName, firstInput(r.getInputs()), r.getDurationMs());
            row.put("status", r.getStatus());
            row.put("triggerType", r.getTriggerType());
            row.put("version", r.getVersion());
            row.put("workflowId", r.getWorkflowId());
            rows.add(row);
        }
        return pageOf(rows, pg.getTotal(), page, size);
    }

    /** 对话型详情：日志 + 回答消息全过程（sources/toolCalls/tokens/timeline）+ 反馈 + 采样状态 */
    public Map<String, Object> chatDetail(String logId) {
        QaLog l = qaLogMapper.selectById(logId);
        if (l == null) throw new BizException(404, "执行记录不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "chat");
        out.put("log", l);
        if (l.getMessageId() != null && !l.getMessageId().isBlank()) {
            Message m = messageMapper.selectById(l.getMessageId());
            if (m != null) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("id", m.getId());
                msg.put("content", m.getContent());
                msg.put("sources", parseList(m.getSources()));
                msg.put("toolCalls", parseList(m.getToolCalls()));
                msg.put("tokens", parseMap(m.getTokens()));
                msg.put("timeline", parseList(m.getTimeline()));
                msg.put("agentName", m.getAgentName());
                out.put("message", msg);
                QaFeedback fb = feedbackMapper.selectOne(new LambdaQueryWrapper<QaFeedback>()
                        .eq(QaFeedback::getMessageId, m.getId()).last("LIMIT 1"));
                if (fb != null) {
                    out.put("feedback", Map.of("rating", fb.getRating(), "text", fb.getFeedbackText() == null ? "" : fb.getFeedbackText()));
                }
            }
        }
        TraceSample sample = sampleMapper.selectOne(new LambdaQueryWrapper<TraceSample>()
                .eq(TraceSample::getQaLogId, l.getId()).last("LIMIT 1"));
        out.put("sample", sample);
        return out;
    }

    /** 工作流型详情：run 全量（含 node_traces）+ 工作流名（运营视角不做归属校验，见类注释） */
    public Map<String, Object> workflowDetail(String runId) {
        WorkflowRun r = runMapper.selectById(runId);
        if (r == null) throw new BizException(404, "运行记录不存在");
        Workflow wf = workflowMapper.selectById(r.getWorkflowId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "workflow");
        out.put("run", r);
        out.put("workflowName", wf == null ? r.getWorkflowId() : wf.getName());
        return out;
    }

    // --------------------------------------------------------------------------------------------------
    // 采样池（线上自动采样 → 标注 → 回流评测集）
    // --------------------------------------------------------------------------------------------------

    /**
     * 每日采样（ScheduleCenter 周期调用，间隔 trace.samplingIntervalMs，≤0 暂停；唯一键兜底幂等）：
     * ① 差评必采（近 24h 的 rating=0 反馈，能关联到日志的）；② 无引用采 N 条；③ 随机常规采 N 条。
     * 只收 <b>有 message_id 关联</b>的日志——存量行无关联键，标注时还原不了全过程，进池只有摘要没有价值。
     */
    public synchronized Map<String, Object> sampleDaily() {
        int randomN = Math.max(0, configService.getInt("trace.sampleRandomDaily", 20));
        int nohitN = Math.max(0, configService.getInt("trace.sampleNoHitDaily", 10));
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        int bad = sampleBad(since);
        int nohit = sampleByCitation(since, nohitN);
        int random = sampleRandom(since, randomN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bad", bad);
        out.put("nohit", nohit);
        out.put("random", random);
        out.put("total", bad + nohit + random);
        if ((bad + nohit + random) > 0) {
            log.info("[TRACE] 线上采样完成：差评 {} / 无引用 {} / 随机 {}", bad, nohit, random);
        }
        return out;
    }

    private int sampleBad(LocalDateTime since) {
        List<QaFeedback> dislikes = feedbackMapper.selectList(new LambdaQueryWrapper<QaFeedback>()
                .eq(QaFeedback::getRating, 0).ge(QaFeedback::getCreatedAt, since));
        int n = 0;
        for (QaFeedback f : dislikes) {
            QaLog l = qaLogMapper.selectOne(new LambdaQueryWrapper<QaLog>()
                    .eq(QaLog::getMessageId, f.getMessageId()).last("LIMIT 1"));
            if (l == null) continue;   // 存量行无关联键（或消息已删）
            n += insertSample(l.getId(), l.getMessageId(), "bad", f.getFeedbackText()) ? 1 : 0;
        }
        return n;
    }

    private int sampleByCitation(LocalDateTime since, int limit) {
        if (limit <= 0) return 0;
        List<QaLog> candidates = qaLogMapper.selectList(new LambdaQueryWrapper<QaLog>()
                .eq(QaLog::getHasCitation, 0).isNotNull(QaLog::getMessageId)
                .ge(QaLog::getCreatedAt, since)
                .orderByDesc(QaLog::getCreatedAt).last("LIMIT " + (limit * 3)));
        return insertUnsampled(candidates, "nohit", limit);
    }

    private int sampleRandom(LocalDateTime since, int limit) {
        if (limit <= 0) return 0;
        List<QaLog> candidates = qaLogMapper.selectList(new LambdaQueryWrapper<QaLog>()
                .isNotNull(QaLog::getMessageId).ge(QaLog::getCreatedAt, since)
                .orderByDesc(QaLog::getCreatedAt).last("LIMIT " + (limit * 3)));
        // 候选倒序取前 3N 再"隔行跳跃"取 N 条——比 ORDER BY RAND() 便宜，分布也够散（运营采样不需要随机数级的均匀）
        List<QaLog> spread = new ArrayList<>();
        int step = Math.max(1, candidates.size() / Math.max(1, limit));
        for (int i = 0; i < candidates.size() && spread.size() < limit; i += step) {
            spread.add(candidates.get(i));
        }
        return insertUnsampled(spread, "random", limit);
    }

    /** 逐条入池（uk_qalog 唯一键挡重复），返回成功条数 */
    private int insertUnsampled(List<QaLog> candidates, String source, int limit) {
        int n = 0;
        for (QaLog l : candidates) {
            if (n >= limit) break;
            n += insertSample(l.getId(), l.getMessageId(), source, null) ? 1 : 0;
        }
        return n;
    }

    private boolean insertSample(String qaLogId, String messageId, String source, String note) {
        try {
            TraceSample s = new TraceSample();
            s.setId(UUID.randomUUID().toString());
            s.setQaLogId(qaLogId);
            s.setMessageId(messageId);
            s.setSource(source);
            s.setStatus("pending");
            s.setNote(note);
            s.setCreatedAt(LocalDateTime.now());
            sampleMapper.insert(s);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;   // 已在池里（唯一键兜底，幂等）
        }
    }

    /** 采样池列表（status 空=全部） */
    public Map<String, Object> pool(String status, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(10, size), 100);
        LambdaQueryWrapper<TraceSample> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank() && !"all".equals(status)) qw.eq(TraceSample::getStatus, status);
        qw.orderByDesc(TraceSample::getCreatedAt);
        Page<TraceSample> pg = sampleMapper.selectPage(new Page<>(p, s), qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TraceSample t : pg.getRecords()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", t.getId());
            r.put("source", t.getSource());
            r.put("status", t.getStatus());
            r.put("note", t.getNote());
            r.put("createdAt", t.getCreatedAt());
            r.put("labeledAt", t.getLabeledAt());
            r.put("labeledBy", t.getLabeledBy());
            QaLog l = qaLogMapper.selectById(t.getQaLogId());
            if (l != null) {
                r.put("question", l.getQuestion());
                r.put("answerSummary", l.getAnswerSummary());
                r.put("elapsedMs", l.getElapsedMs());
            }
            rows.add(r);
        }
        return pageOf(rows, pg.getTotal(), p, s);
    }

    /**
     * 标注并回流评测集：期望命中的知识块至少 1 个（与差评样本「加入评估集」同门槛——
     * 评测的语义是"这些块应当被检索到"，空集没有可判定的期望）；回流成功才置 labeled。
     */
    public Map<String, Object> label(String id, List<String> knowledgeIds, String note) {
        TraceSample t = sampleMapper.selectById(id);
        if (t == null) throw new BizException(404, "采样记录不存在");
        if (!"pending".equals(t.getStatus())) throw new BizException("该样本已处理（" + t.getStatus() + "）");
        List<String> kbs = knowledgeIds == null ? List.of() : knowledgeIds.stream()
                .filter(k -> k != null && !k.isBlank()).distinct().toList();
        if (kbs.isEmpty()) throw new BizException("请至少选择 1 个期望命中的知识块（评测语义是「这些块应当被检索到」）");
        QaLog l = qaLogMapper.selectById(t.getQaLogId());
        if (l == null) throw new BizException("关联的问答日志已不存在，无法标注");
        Map<String, Object> res = evalService.addCase(l.getQuestion(), kbs);
        t.setStatus("labeled");
        t.setNote(note == null || note.isBlank() ? t.getNote() : note.trim());
        t.setLabeledBy(RequestUser.uid());
        t.setLabeledAt(LocalDateTime.now());
        sampleMapper.updateById(t);
        log.info("[TRACE] 样本回流评测集：sample={} question={} 知识块 {} 个", id, l.getQuestion(), kbs.size());
        return Map.of("labeled", true, "eval", res);
    }

    /** 忽略（不回流） */
    public Map<String, Object> dismiss(String id) {
        TraceSample t = sampleMapper.selectById(id);
        if (t == null) throw new BizException(404, "采样记录不存在");
        if (!"pending".equals(t.getStatus())) throw new BizException("该样本已处理（" + t.getStatus() + "）");
        t.setStatus("dismissed");
        t.setLabeledBy(RequestUser.uid());
        t.setLabeledAt(LocalDateTime.now());
        sampleMapper.updateById(t);
        return Map.of("dismissed", true);
    }

    /** 池子概览（看板小卡）：各状态计数 + 最近入池时间 */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String st : List.of("pending", "labeled", "dismissed")) {
            out.put(st, sampleMapper.selectCount(new LambdaQueryWrapper<TraceSample>()
                    .eq(TraceSample::getStatus, st)));
        }
        TraceSample latest = sampleMapper.selectOne(new LambdaQueryWrapper<TraceSample>()
                .orderByDesc(TraceSample::getCreatedAt).last("LIMIT 1"));
        out.put("lastSampledAt", latest == null ? null : latest.getCreatedAt());
        return out;
    }

    // --------------------------------------------------------------------------------------------------
    // 内部
    // --------------------------------------------------------------------------------------------------

    private Map<String, Object> baseRow(String id, String kind, LocalDateTime time,
                                        String title, String sub, Long elapsedMs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("kind", kind);
        r.put("time", time);
        r.put("title", title);
        r.put("sub", sub);
        r.put("elapsedMs", elapsedMs);
        return r;
    }

    private Map<String, Integer> feedbackRatings(List<String> messageIds) {
        Map<String, Integer> out = new HashMap<>();
        if (messageIds == null || messageIds.isEmpty()) return out;
        for (QaFeedback f : feedbackMapper.selectList(new LambdaQueryWrapper<QaFeedback>()
                .in(QaFeedback::getMessageId, messageIds))) {
            out.put(f.getMessageId(), f.getRating());
        }
        return out;
    }

    /** 批量解析智能体名（一次 in 查询；名称异常的行跳过，调用方对查不到的回退显示 id） */
    private Map<String, String> agentNames(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return Map.of();
        return agentMapper.selectBatchIds(agentIds).stream()
                .filter(a -> a.getName() != null && !a.getName().isBlank())
                .collect(Collectors.toMap(Agent::getId, Agent::getName, (a, b) -> a));
    }

    private String workflowName(String workflowId) {
        Workflow w = workflowMapper.selectById(workflowId);
        return w == null ? workflowId : w.getName();
    }

    private static String firstInput(String inputsJson) {
        try {
            Map<String, Object> m = JSON.parseObject(inputsJson == null ? "{}" : inputsJson);
            Object q = m.get("question");
            if (q == null && !m.isEmpty()) q = m.values().iterator().next();
            return q == null ? "" : String.valueOf(q);
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Object> parseList(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : JSON.parseArray(json);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Map<String, Object> parseMap(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : JSON.parseObject(json);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, Object> pageOf(List<?> rows, long total, int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        return out;
    }
}
