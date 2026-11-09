package com.wenqu.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.QaFeedbackMapper;
import com.wenqu.ai.mapper.QaLogMapper;
import com.wenqu.ai.mapper.TraceSampleMapper;
import com.wenqu.ai.mapper.WorkflowRunMapper;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.QaLog;
import com.wenqu.ai.model.TraceSample;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数据看板聚合服务（P0/P2）。
 * <p>
 * 把散落的问答日志、反馈、工作流运行、采样池按<b>同一时间窗口</b>聚合成看板需要的形状：
 * <ul>
 *   <li>{@link #summary(int)}：区间指标 + 上期对比 + 趋势 + 健康度；</li>
 *   <li>{@link #actions(int)}：差评 / 知识缺口 / 待标注三源合一的待办队列；</li>
 *   <li>{@link #traceOverview(int)}：执行记录页签的指标条与每日趋势；</li>
 *   <li>{@link #alerts()} 与定时任务：阈值告警与周报（通知中心 / Web Push / 邮件规则统一走 NotificationService）。</li>
 * </ul>
 * 口径：问答类指标一律来自 c_ai_qa_log / c_ai_qa_feedback，工作流指标来自 c_ai_workflow_run；
 * 时间窗口左闭右开，上期窗口与本期间隔一个 range，保证「较上期」不会重叠。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 允许的时间范围（天）：0 = 全部；其余为近 N 天 */
    private static final Set<Integer> RANGES = Set.of(0, 7, 30, 90);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int TOP_LIMIT = 10;
    private static final int ACTION_LIMIT = 12;
    /** 告警最小样本量：样本太少时比例没有意义，宁可不说 */
    private static final long ALERT_MIN_QA = 20;
    private static final long ALERT_MIN_FEEDBACK = 10;

    private final QaLogMapper qaLogMapper;
    private final QaFeedbackMapper feedbackMapper;
    private final WorkflowRunMapper runMapper;
    private final TraceSampleMapper sampleMapper;
    private final QaLogService qaLogService;
    private final RetrievalEvaluationService evalService;
    private final ConfigService configService;
    private final NotificationService notificationService;
    /** 周报里的费用摘要：与费用页同源（台账 × 登记单价） */
    private final UsageStatsService usageStatsService;

    // ==================================================================================================
    // 总览：指标 + 上期 + 趋势 + 健康度
    // ==================================================================================================

    public Map<String, Object> summary(int range) {
        int r = normalizeRange(range);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = r == 0 ? null : now.minusDays(r);

        Map<String, Object> stats = qaLogMapper.summaryBetween(from, null);
        long total = num(stats.get("total"));
        long noHit = num(stats.get("no_hit"));
        long cited = num(stats.get("cited"));
        double noHitRate = pct(noHit, total);
        double citationRate = pct(cited, total);

        Map<String, Object> fb = feedbackMapper.feedbackStatsBetween(from, null);
        long fbTotal = num(fb.get("total"));
        long likes = num(fb.get("likes"));
        Double likeRate = fbTotal == 0 ? null : pct(likes, fbTotal);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", r);
        out.put("total", total);
        out.put("noHitRate", round1(noHitRate));
        out.put("citationRate", round1(citationRate));
        out.put("feedback", feedback(likes, fbTotal, likeRate));
        out.put("topQuestions", questions(qaLogMapper.topQuestionsBetween(from, null, TOP_LIMIT)));
        out.put("noHitQuestions", questions(qaLogMapper.noHitQuestionsBetween(from, null, TOP_LIMIT)));
        out.put("trend", trend(r, now));
        out.put("health", health(total, noHitRate, citationRate, likeRate));
        out.put("prev", r == 0 ? null : prevSummary(now, r));
        LocalDateTime last = qaLogMapper.lastCreatedAt();
        out.put("dataThrough", last == null ? now : last);
        return out;
    }

    private Map<String, Object> prevSummary(LocalDateTime now, int r) {
        LocalDateTime prevFrom = now.minusDays(2L * r);
        LocalDateTime prevUntil = now.minusDays(r);
        Map<String, Object> s = qaLogMapper.summaryBetween(prevFrom, prevUntil);
        long total = num(s.get("total"));
        long noHit = num(s.get("no_hit"));
        long cited = num(s.get("cited"));
        Map<String, Object> fb = feedbackMapper.feedbackStatsBetween(prevFrom, prevUntil);
        long fbTotal = num(fb.get("total"));
        long likes = num(fb.get("likes"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("noHitRate", round1(pct(noHit, total)));
        out.put("citationRate", round1(pct(cited, total)));
        out.put("feedback", feedback(likes, fbTotal, fbTotal == 0 ? null : pct(likes, fbTotal)));
        return out;
    }

    private Map<String, Object> feedback(long likes, long total, Double likeRate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("likes", likes);
        m.put("dislikes", Math.max(0, total - likes));
        m.put("likeRate", likeRate == null ? null : round1(likeRate));
        return m;
    }

    /**
     * 健康度：命中 40% + 引用 20% + 满意 30% + 体检 10%。
     * 反馈样本为空时满意项按中性值 88 计（没有反馈不等于客户不满意，不能把缺数据读成差评）。
     */
    private Map<String, Object> health(long total, double noHitRate, double citationRate, Double likeRate) {
        String checkup = checkupStatus();
        double checkupPart = switch (checkup) {
            case "ok" -> 100;
            case "decline" -> 45;
            case "error" -> 50;
            default -> 80;   // empty / 未跑过：中性，不奖不罚
        };
        double satisfaction = likeRate == null ? 88 : likeRate;
        double hitRate = 100 - noHitRate;
        double raw = hitRate * 0.4 + citationRate * 0.2 + satisfaction * 0.3 + checkupPart * 0.1;
        double score = total <= 0 ? 0 : Math.round(raw * 10) / 10.0;
        String status = total <= 0 ? "empty" : score >= 80 ? "good" : score >= 60 ? "warn" : "bad";

        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("hitRate", round1(hitRate));
        parts.put("citationRate", round1(citationRate));
        parts.put("likeRate", likeRate == null ? null : round1(likeRate));
        parts.put("checkup", checkup);

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("score", score);
        health.put("status", status);
        health.put("parts", parts);
        return health;
    }

    private String checkupStatus() {
        try {
            Map<String, Object> report = evalService.lastAutoReport();
            if (report == null || report.isEmpty()) return "empty";
            Object st = report.get("status");
            return st == null ? "empty" : String.valueOf(st);
        } catch (Exception e) {
            log.warn("[DASH] 读取体检报告失败: {}", e.getMessage());
            return "error";
        }
    }

    /** 区间趋势（缺失日期补零，前端直接画连续折线） */
    private List<Map<String, Object>> trend(int range, LocalDateTime now) {
        int days = range == 0 || range > 90 ? 30 : range;
        LocalDate today = LocalDate.now();
        LocalDateTime from = today.minusDays(days - 1L).atStartOfDay();
        Map<String, Map<String, Object>> byDate = new HashMap<>();
        for (Map<String, Object> row : qaLogMapper.dailyBetween(from, null)) {
            byDate.put(String.valueOf(row.get("d")), row);
        }
        Map<String, Map<String, Object>> fbByDate = new HashMap<>();
        for (Map<String, Object> row : feedbackMapper.dailyBetween(from, null)) {
            fbByDate.put(String.valueOf(row.get("d")), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = today.minusDays(days - 1L - i);
            String key = d.format(DAY);
            Map<String, Object> row = byDate.get(key);
            Map<String, Object> fb = fbByDate.get(key);
            long fbTotal = fb == null ? 0 : num(fb.get("total"));
            long fbLikes = fb == null ? 0 : num(fb.get("likes"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", key);
            item.put("total", row == null ? 0 : num(row.get("total")));
            item.put("noHit", row == null ? 0 : num(row.get("no_hit")));
            item.put("cited", row == null ? 0 : num(row.get("cited")));
            item.put("feedback", fbTotal);
            item.put("likeRate", fbTotal == 0 ? null : round1(pct(fbLikes, fbTotal)));
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> questions(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) return out;
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("question", String.valueOf(row.get("question")));
            item.put("count", num(row.get("cnt")));
            out.add(item);
        }
        return out;
    }

    // ==================================================================================================
    // 待办闭环：差评 / 知识缺口 / 待标注 三源合一
    // ==================================================================================================

    public Map<String, Object> actions(int range) {
        int r = normalizeRange(range);
        LocalDateTime now = LocalDateTime.now();
        // 0=全部：待办也按全时段统计（TOP 明细仍只取最近 10 条，计数是真实的）
        LocalDateTime from = r == 0 ? null : now.minusDays(r);

        List<Map<String, Object>> rows = new ArrayList<>();

        // ① 差评样本（复用现有回流口径：含 messageId / knowledgeIds / 反馈说明）
        List<Map<String, Object>> badCases = qaLogService.listBadCases(100);
        for (Map<String, Object> bc : badCases) {
            LocalDateTime at = timeOf(bc.get("time"));
            if (from != null && at != null && at.isBefore(from)) continue;
            Map<String, Object> row = actionRow("bad", bc.get("question"), at);
            row.put("meta", (bc.get("feedbackText") == null || String.valueOf(bc.get("feedbackText")).isBlank()
                    ? "差评未填原因" : String.valueOf(bc.get("feedbackText"))) + " · 命中 "
                    + sizeOf(bc.get("knowledgeIds")) + " 个知识块");
            row.put("messageId", bc.get("messageId"));
            row.put("knowledgeIds", bc.get("knowledgeIds"));
            row.put("answer", bc.get("answer"));
            rows.add(row);
        }

        // ② 知识缺口（区间内真调过检索但无命中的问题）
        for (Map<String, Object> gap : qaLogMapper.gapQuestionsBetween(from, null, TOP_LIMIT)) {
            Map<String, Object> row = actionRow("gap", gap.get("question"), timeOf(gap.get("latest")));
            long cnt = num(gap.get("cnt"));
            row.put("count", cnt);
            row.put("meta", "区间内被问 " + cnt + " 次 · 均无命中");
            rows.add(row);
        }

        // ③ 待标注样本（采样池 backlog，不受 range 限制——待办本来就是跨期积压）
        List<TraceSample> samples = sampleMapper.selectList(new LambdaQueryWrapper<TraceSample>()
                .eq(TraceSample::getStatus, "pending").orderByDesc(TraceSample::getCreatedAt).last("LIMIT " + TOP_LIMIT));
        Map<String, QaLog> logs = new HashMap<>();
        List<String> logIds = samples.stream().map(TraceSample::getQaLogId).filter(Objects::nonNull).distinct().toList();
        if (!logIds.isEmpty()) {
            for (QaLog l : qaLogMapper.selectBatchIds(logIds)) logs.put(l.getId(), l);
        }
        for (TraceSample s : samples) {
            QaLog l = logs.get(s.getQaLogId());
            String q = l == null ? "（关联问答已删除）" : l.getQuestion();
            Map<String, Object> row = actionRow("pool", q, s.getCreatedAt());
            row.put("sampleId", s.getId());
            row.put("qaLogId", s.getQaLogId());
            row.put("answerSummary", l == null ? "" : l.getAnswerSummary());
            row.put("source", s.getSource());
            row.put("meta", ("bad".equals(s.getSource()) ? "差评采样"
                    : "nohit".equals(s.getSource()) ? "无引用采样" : "随机采样")
                    + (s.getNote() == null || s.getNote().isBlank() ? "" : " · " + s.getNote()));
            rows.add(row);
        }

        rows.sort(Comparator.comparing((Map<String, Object> m) -> (LocalDateTime) m.get("at"),
                Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Object> counts = new LinkedHashMap<>();
        // 差评总数由 total - likes 得到（反馈表只有两种评分；聚合一次即可，不拉明细）
        Map<String, Object> fb = feedbackMapper.feedbackStatsBetween(from, null);
        long bad = num(fb.get("total")) - num(fb.get("likes"));
        long gap = qaLogMapper.distinctNoHitBetween(from, null);
        long pool = sampleMapper.selectCount(new LambdaQueryWrapper<TraceSample>()
                .eq(TraceSample::getStatus, "pending"));
        long closed = sampleMapper.selectCount(new LambdaQueryWrapper<TraceSample>()
                .in(TraceSample::getStatus, "labeled", "dismissed")
                .ge(TraceSample::getLabeledAt, now.minusDays(7)));
        counts.put("bad", Math.max(0, bad));
        counts.put("gap", gap);
        counts.put("pool", pool);
        counts.put("total", Math.max(0, bad) + gap + pool);
        counts.put("closedThisWeek", closed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", r);
        out.put("counts", counts);
        out.put("rows", rows.subList(0, Math.min(ACTION_LIMIT, rows.size())));
        out.put("dataThrough", now);
        return out;
    }

    private Map<String, Object> actionRow(String type, Object question, LocalDateTime at) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("question", question == null ? "" : String.valueOf(question));
        row.put("at", at);
        return row;
    }

    // ==================================================================================================
    // 执行记录页签：指标条 + 每日趋势
    // ==================================================================================================

    public Map<String, Object> traceOverview(int days) {
        int d = days == 1 || days == 7 || days == 30 || days == 90 ? days : 7;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusDays(d);

        Map<String, Object> qa = qaLogMapper.summaryBetween(from, null);
        long chat = num(qa.get("total"));
        long noHit = num(qa.get("no_hit"));
        long cited = num(qa.get("cited"));
        double citationRate = pct(cited, chat);

        Map<String, Object> runs = runMapper.summarySince(from);
        long wf = num(runs.get("total"));
        long wfOk = num(runs.get("success"));
        double successRate = wf == 0 ? 100 : pct(wfOk, wf);

        List<Integer> elapsed = qaLogMapper.elapsedBetween(from, null, 5000);
        long p95 = p95(elapsed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("chat", chat);
        out.put("workflow", wf);
        out.put("total", chat + wf);
        out.put("successRate", round1(successRate));
        out.put("p95Ms", p95);
        out.put("noCitationRate", round1(100 - citationRate));
        out.put("noHitRate", round1(pct(noHit, chat)));
        out.put("trend", traceTrend(d, now));
        return out;
    }

    private List<Map<String, Object>> traceTrend(int days, LocalDateTime now) {
        LocalDate today = LocalDate.now();
        LocalDateTime from = today.minusDays(days - 1L).atStartOfDay();
        Map<String, Map<String, Object>> chatByDate = new HashMap<>();
        for (Map<String, Object> row : qaLogMapper.dailyBetween(from, null)) {
            chatByDate.put(String.valueOf(row.get("d")), row);
        }
        Map<String, Map<String, Object>> wfByDate = new HashMap<>();
        for (Map<String, Object> row : runMapper.dailySince(from)) {
            wfByDate.put(String.valueOf(row.get("d")), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = today.minusDays(days - 1L - i);
            String key = date.format(DAY);
            Map<String, Object> c = chatByDate.get(key);
            Map<String, Object> w = wfByDate.get(key);
            long chat = c == null ? 0 : num(c.get("total"));
            long chatCited = c == null ? 0 : num(c.get("cited"));
            long wf = w == null ? 0 : num(w.get("total"));
            long wfOk = w == null ? 0 : num(w.get("success"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", key);
            item.put("chat", chat);
            item.put("workflow", wf);
            item.put("successRate", wf == 0 ? null : round1(pct(wfOk, wf)));
            item.put("noCitationRate", chat == 0 ? null : round1(100 - pct(chatCited, chat)));
            out.add(item);
        }
        return out;
    }

    private long p95(List<Integer> desc) {
        if (desc == null || desc.isEmpty()) return 0;
        int idx = Math.min(desc.size() - 1, (int) Math.floor(desc.size() * 0.05));
        return desc.get(idx) == null ? 0 : desc.get(idx);
    }

    // ==================================================================================================
    // P2：阈值告警与周报
    // ==================================================================================================

    public Map<String, Object> alerts() {
        Map<String, Object> s = summary(7);
        long total = num(s.get("total"));
        double noHitRate = asDouble(s.get("noHitRate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fb = (Map<String, Object>) s.get("feedback");
        long fbTotal = num(fb == null ? null : fb.get("total"));
        Double likeRate = fb == null || fb.get("likeRate") == null ? null : asDouble(fb.get("likeRate"));

        int noHitLimit = Math.max(1, configService.getInt("dashboard.noHitAlertRate", 15));
        int likeLimit = Math.max(1, configService.getInt("dashboard.likeAlertRate", 80));
        List<Map<String, Object>> items = new ArrayList<>();

        if (total >= ALERT_MIN_QA && noHitRate > noHitLimit) {
            items.add(alertItem("nohit", "warn", "无命中率偏高",
                    "近 7 天无命中率 " + round1(noHitRate) + "%，高于阈值 " + noHitLimit + "%（样本 " + total + " 条）"));
        }
        if (fbTotal >= ALERT_MIN_FEEDBACK && likeRate != null && likeRate < likeLimit) {
            items.add(alertItem("like", "warn", "反馈满意率偏低",
                    "近 7 天满意率 " + round1(likeRate) + "%，低于阈值 " + likeLimit + "%（反馈 " + fbTotal + " 条）"));
        }
        String checkup = checkupStatus();
        if ("decline".equals(checkup)) {
            items.add(alertItem("checkup", "err", "检索质量下滑", "最近一次自动体检检测到指标下滑，请前往「检索评估」查看报告"));
        }

        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("noHitRate", noHitLimit);
        thresholds.put("likeRate", likeLimit);
        thresholds.put("minQaSamples", ALERT_MIN_QA);
        thresholds.put("minFeedbackSamples", ALERT_MIN_FEEDBACK);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("triggered", !items.isEmpty());
        out.put("items", items);
        out.put("thresholds", thresholds);
        out.put("windowDays", 7);
        out.put("checkedAt", LocalDateTime.now());
        return out;
    }

    /**
     * 定时任务入口：告警只推「管理员」，同类未读合并（dedupKey），避免每 6 小时刷一遍铃铛。
     *
     * @return 本轮触发条数（0 = 无事发生）
     */
    public int checkAlertsAndNotify() {
        try {
            Map<String, Object> a = alerts();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) a.get("items");
            for (Map<String, Object> item : items) {
                String key = String.valueOf(item.get("key"));
                // 体检下滑已由 RetrievalEvaluationService 在体检收口时通知管理员，这里不再重复推一条；
                // 页面横幅仍会展示（alerts() 的 items 不过滤）
                if ("checkup".equals(key)) continue;
                notificationService.createForAdmins(Notification.TYPE_DASHBOARD_ALERT,
                        "看板预警 · " + item.get("title"), String.valueOf(item.get("detail")),
                        "dashboard", key, "dashboard:alert:" + key);
            }
            return items.size();
        } catch (Exception e) {
            log.warn("[DASH] 阈值告警执行失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 周报：跑在定时任务线程（无登录态），收件人按管理员角色圈定；没有问答数据时不发，避免空响。
     * 内容固定为「健康度 + 待办 + 费用」三块（与方案验收项一致）。
     *
     * @return 1=已发 / 0=跳过
     */
    public int sendWeeklyReport() {
        try {
            Map<String, Object> s = summary(7);
            long total = num(s.get("total"));
            if (total <= 0) return 0;
            Map<String, Object> a = actions(7);
            @SuppressWarnings("unchecked")
            Map<String, Object> counts = (Map<String, Object>) a.get("counts");
            @SuppressWarnings("unchecked")
            Map<String, Object> health = (Map<String, Object>) s.get("health");
            @SuppressWarnings("unchecked")
            Map<String, Object> fb = (Map<String, Object>) s.get("feedback");

            // 费用摘要单独兜底：费用读取失败不能让整份周报发不出去
            String costText = "费用暂无数据";
            try {
                Map<String, Object> cost = usageStatsService.adminCost(7);
                long unpriced = num(cost.get("unpricedTokens"));
                costText = "近 7 天费用 ¥" + String.format("%.2f", asDouble(cost.get("total")))
                        + (unpriced > 0 ? "（另有 " + unpriced + " tokens 未计价）" : "");
            } catch (Exception e) {
                log.warn("[DASH] 周报费用摘要读取失败: {}", e.getMessage());
            }

            String content = "近 7 天问答 " + total + " 次，无命中率 " + s.get("noHitRate") + "%，引用率 "
                    + s.get("citationRate") + "%，满意率 "
                    + (fb == null || fb.get("likeRate") == null ? "暂无反馈" : fb.get("likeRate") + "%")
                    + "；健康度 " + (health == null ? "—" : health.get("score")) + " 分。"
                    + "待办：" + (counts == null ? "—" : "差评 " + counts.get("bad") + " / 知识缺口 "
                    + counts.get("gap") + " / 待标注 " + counts.get("pool"))
                    + "，本周已闭环 " + (counts == null ? 0 : counts.get("closedThisWeek")) + "。"
                    + costText + "。";
            notificationService.createForAdmins(Notification.TYPE_DASHBOARD_WEEKLY,
                    "数据看板周报", content, "dashboard", "overview", "dashboard:weekly:" + LocalDate.now().format(DAY));
            return 1;
        } catch (Exception e) {
            log.warn("[DASH] 周报生成失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 周一发送的入口（调度任务每天检查一次）：只在周一、且当天未发过时生成周报。
     * 用内部键记「最后发送日」，避免应用重启后同一天重复推送。
     *
     * @return 1=已发 / 0=未到时间或已发过
     */
    public int sendWeeklyReportIfDue() {
        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek() != java.time.DayOfWeek.MONDAY) return 0;
        String key = "dashboard.weeklyReportLastSent";
        if (today.format(DAY).equals(configService.get(key))) return 0;
        int sent = sendWeeklyReport();
        if (sent > 0) {
            try {
                configService.putInternal(key, today.format(DAY));
            } catch (Exception e) {
                log.warn("[DASH] 周报发送日期记录失败: {}", e.getMessage());
            }
        }
        return sent;
    }

    private Map<String, Object> alertItem(String key, String level, String title, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("level", level);
        item.put("title", title);
        item.put("detail", detail);
        return item;
    }

    // ==================================================================================================
    // 工具
    // ==================================================================================================

    private static int normalizeRange(int range) {
        return RANGES.contains(range) ? range : 30;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static double pct(long part, long total) {
        return total <= 0 ? 0 : Math.round(part * 1000.0 / total) / 10.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static int sizeOf(Object list) {
        return list instanceof List<?> l ? l.size() : 0;
    }

    private static LocalDateTime timeOf(Object o) {
        if (o instanceof LocalDateTime t) return t;
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        return null;
    }
}
