package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.RetrievalEvalResultMapper;
import com.wenqu.ai.mapper.RetrievalEvalRunMapper;
import com.wenqu.ai.mapper.RetrievalEvalSetMapper;
import com.wenqu.ai.model.RetrievalEvalCase;
import com.wenqu.ai.model.RetrievalEvalResult;
import com.wenqu.ai.model.RetrievalEvalRun;
import com.wenqu.ai.model.RetrievalEvalSet;
import com.wenqu.ai.util.RequestUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索评估运行编排：异步实验、历史、基线、跨次对比、灰度应用与总览聚合。
 * <p>计算仍由 {@link RetrievalEvaluationService} 负责；本类只解决「留得住、比得了、能处置」。
 */
@Slf4j
@Service
public class RetrievalEvalRunService {

    private final RetrievalEvalRunMapper runMapper;
    private final RetrievalEvalResultMapper resultMapper;
    private final RetrievalEvalSetMapper setMapper;
    private final RetrievalEvalCatalogService catalog;
    private final RetrievalEvaluationService evaluationService;
    private final RetrievalEvalRunner runner;
    private final ConfigService configService;

    public RetrievalEvalRunService(RetrievalEvalRunMapper runMapper,
                                   RetrievalEvalResultMapper resultMapper,
                                   RetrievalEvalSetMapper setMapper,
                                   RetrievalEvalCatalogService catalog,
                                   RetrievalEvaluationService evaluationService,
                                   RetrievalEvalRunner runner,
                                   ConfigService configService) {
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.setMapper = setMapper;
        this.catalog = catalog;
        this.evaluationService = evaluationService;
        this.runner = runner;
        this.configService = configService;
    }

    // ==================== 启动运行 ====================

    public Map<String, Object> startRun(JSONObject body) {
        RetrievalEvalSet set = catalog.requireSet(body == null ? null : body.getString("setId"));
        List<RetrievalEvalCase> all = catalog.casesOf(set.getId(), 1);
        if (all.isEmpty()) throw new BizException("评估集为空或没有启用用例，请先生成/启用评估集");
        List<String> caseIds = body == null || body.getJSONArray("caseIds") == null ? null
                : body.getJSONArray("caseIds").toJavaList(String.class);
        List<RetrievalEvalCase> cases = caseIds == null || caseIds.isEmpty() ? all
                : all.stream().filter(c -> caseIds.contains(c.getId())).toList();
        if (cases.isEmpty()) throw new BizException("所选用例不存在或已停用");
        if (cases.size() > 500) throw new BizException("单次运行用例上限 500 条，请缩小范围");

        List<RetrievalEvaluationService.EvalParams> groups = parseGroups(body);
        if (groups.isEmpty()) {
            groups = List.of(new RetrievalEvaluationService.EvalParams(
                    "当前配置", "normal", null, null, null, null, null, null, null, null));
        }
        if (groups.size() > 8) throw new BizException("参数组最多 8 组");
        List<Integer> ks = parseKs(body);
        boolean includeJudge = body != null && Boolean.TRUE.equals(body.getBoolean("includeJudge"));

        RetrievalEvalRun run = new RetrievalEvalRun();
        run.setSetId(set.getId());
        run.setSetVersion(set.getVersion());
        run.setTriggerKind("manual");
        run.setStatus("running");
        run.setTotal(cases.size() * groups.size());
        run.setDone(0);
        run.setKList(JSON.toJSONString(ks));
        run.setParamsId(fingerprint(groups));
        run.setGroupsJson(JSON.toJSONString(groups.stream().map(this::paramMap).toList()));
        run.setStaleExpected(0);
        run.setBaselineRunId(set.getBaselineRunId());
        run.setJudgeModel(includeJudge ? configService.get("eval.judgeModel") : null);
        run.setStartedAt(LocalDateTime.now());
        run.setCreatedBy(RequestUser.uid());
        runMapper.insert(run);
        runner.submit(run, cases, groups, ks, includeJudge);
        log.info("[Eval] 异步运行启动 {}：{} 组 × {} 条", run.getId(), groups.size(), cases.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("caseCount", cases.size());
        out.put("groupCount", groups.size());
        out.put("total", run.getTotal());
        out.put("includeJudge", includeJudge);
        return out;
    }

    /** 定时体检：复用既有护栏与报告，落一条运行历史供趋势查看。 */
    public Map<String, Object> runScheduledCheck() {
        Map<String, Object> report = evaluationService.runAutoCheck();
        try {
            RetrievalEvalSet set = catalog.activeSet(null);
            if (set != null && report != null && report.get("metrics") instanceof Map<?, ?> metrics && !metrics.isEmpty()) {
                RetrievalEvalRun run = new RetrievalEvalRun();
                run.setSetId(set.getId());
                run.setSetVersion(set.getVersion());
                run.setTriggerKind("scheduled");
                run.setStatus("decline".equals(report.get("status")) ? "partial" : "succeeded");
                int caseCount = report.get("caseCount") instanceof Number n ? n.intValue() : 0;
                run.setTotal(caseCount);
                run.setDone(caseCount);
                run.setKList(JSON.toJSONString(List.of(5, 10)));
                Map<String, Object> groupSummary = new LinkedHashMap<>();
                groupSummary.put("name", "当前配置");
                groupSummary.put("mode", "normal");
                groupSummary.put("metrics", metrics);
                groupSummary.put("caseCount", caseCount);
                groupSummary.put("judgeScore", report.get("judgeScore"));
                run.setGroupsJson(JSON.toJSONString(List.of(groupSummary)));
                run.setMetricsJson(JSON.toJSONString(metrics));
                run.setStaleExpected(report.get("staleExpected") instanceof Number n ? n.intValue() : 0);
                run.setWarning(report.get("message") == null ? null : String.valueOf(report.get("message")));
                run.setElapsedMs(report.get("elapsedMs") instanceof Number n ? n.longValue() : null);
                run.setStartedAt(LocalDateTime.now().minusNanos(
                        report.get("elapsedMs") instanceof Number n ? n.longValue() * 1_000_000 : 0));
                run.setFinishedAt(LocalDateTime.now());
                run.setCreatedBy(RequestUser.SYSTEM);
                runMapper.insert(run);
                if (set.getBaselineRunId() == null) {
                    set.setBaselineRunId(run.getId());
                    setMapper.updateById(set);
                }
                report.put("runId", run.getId());
            }
        } catch (Exception e) {
            log.warn("[Eval] 定时体检历史落库失败（不影响报告）: {}", e.getMessage());
        }
        return report;
    }

    // ==================== 用例列表 ====================

    /** 用例表格：搜索/筛选（落空/低相关/失效/待确认/停用）+ 最近一次运行的 recall@5。 */
    public Map<String, Object> listCases(String setId, String filter, String keyword, int page, int size) {
        RetrievalEvalSet set = setId == null || setId.isBlank() ? catalog.activeSet(null) : setMapper.selectById(setId);
        if (set == null) {
            // 无评估集时 set 必须回 null：Map.of 不收 null 值（线上空库走进这里会直接 NPE 500）
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("total", 0);
            empty.put("page", 1);
            empty.put("size", size);
            empty.put("rows", List.of());
            empty.put("set", null);
            empty.put("counts", Map.of());
            return empty;
        }
        List<RetrievalEvalCase> cases = catalog.casesOf(set.getId(), null);
        Map<String, Map<String, Object>> lastMetrics = lastMetricsMap(set);
        List<Map<String, Object>> rows = catalog.caseRows(cases, lastMetrics);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("all", rows.size());
        counts.put("miss", (int) rows.stream().filter(r -> num(r.get("lastRecall5")) != null && num(r.get("lastRecall5")) == 0).count());
        counts.put("low", (int) rows.stream().filter(r -> {
            Double v = num(r.get("lastRecall5"));
            return v != null && v > 0 && v < 0.5;
        }).count());
        counts.put("stale", (int) rows.stream().filter(r -> "stale".equals(r.get("quality"))).count());
        counts.put("unverified", (int) rows.stream().filter(r -> "unverified".equals(r.get("quality"))).count());
        counts.put("verified", (int) rows.stream().filter(r -> "verified".equals(r.get("quality"))).count());
        counts.put("disabled", (int) rows.stream().filter(r -> Integer.valueOf(0).equals(r.get("enabled"))).count());

        String f = filter == null || filter.isBlank() ? "all" : filter;
        String kw = keyword == null ? "" : keyword.trim();
        List<Map<String, Object>> filtered = rows.stream().filter(r -> {
            boolean match = switch (f) {
                case "miss" -> num(r.get("lastRecall5")) != null && num(r.get("lastRecall5")) == 0;
                case "low" -> {
                    Double v = num(r.get("lastRecall5"));
                    yield v != null && v > 0 && v < 0.5;
                }
                case "stale" -> "stale".equals(r.get("quality"));
                case "unverified" -> "unverified".equals(r.get("quality"));
                case "verified" -> "verified".equals(r.get("quality"));
                case "disabled" -> Integer.valueOf(0).equals(r.get("enabled"));
                default -> true;
            };
            if (!match || kw.isEmpty()) return match;
            if (String.valueOf(r.get("question")).contains(kw)) return true;
            Object titles = r.get("expectedTitles");
            return titles instanceof List<?> list && list.stream().anyMatch(t -> String.valueOf(t).contains(kw));
        }).toList();
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 200);
        int from = Math.min(filtered.size(), (p - 1) * s);
        int to = Math.min(filtered.size(), from + s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", filtered.size());
        out.put("page", p);
        out.put("size", s);
        out.put("rows", filtered.subList(from, to));
        out.put("set", set);
        out.put("counts", counts);
        return out;
    }

    private Map<String, Map<String, Object>> lastMetricsMap(RetrievalEvalSet set) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        List<RetrievalEvalRun> runs = runMapper.selectList(new LambdaQueryWrapper<RetrievalEvalRun>()
                .eq(RetrievalEvalRun::getSetId, set.getId())
                .ne(RetrievalEvalRun::getStatus, "running")
                .orderByDesc(RetrievalEvalRun::getCreateTime)
                .last("LIMIT 1"));
        if (runs.isEmpty()) return out;
        RetrievalEvalRun run = runs.get(0);
        String g = firstGroupName(run);
        for (RetrievalEvalResult r : resultMapper.selectList(new LambdaQueryWrapper<RetrievalEvalResult>()
                .eq(RetrievalEvalResult::getRunId, run.getId())
                .eq(g != null, RetrievalEvalResult::getGroupName, g))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recall5", recall5(r));
            m.put("firstRank", r.getFirstRank());
            out.put(r.getCaseId(), m);
        }
        return out;
    }

    // ==================== 历史 / 详情 ====================

    public Map<String, Object> listRuns(int page, int size, String setId) {
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        RetrievalEvalSet set = setId == null || setId.isBlank() ? catalog.activeSet(null) : setMapper.selectById(setId);
        LambdaQueryWrapper<RetrievalEvalRun> qw = new LambdaQueryWrapper<>();
        if (set != null) qw.eq(RetrievalEvalRun::getSetId, set.getId());
        long total = runMapper.selectCount(qw);
        List<RetrievalEvalRun> rows = runMapper.selectList(qw
                .orderByDesc(RetrievalEvalRun::getCreateTime)
                .last("LIMIT " + ((p - 1) * s) + "," + s));
        List<Map<String, Object>> outRows = rows.stream().map(r -> {
            Map<String, Object> row = runSummary(r);
            row.put("baseline", set != null && r.getId().equals(set.getBaselineRunId()));
            return row;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", outRows);
        out.put("set", set);
        return out;
    }

    public Map<String, Object> runDetail(String runId) {
        RetrievalEvalRun run = runMapper.selectById(runId);
        if (run == null) throw new BizException(404, "运行不存在");
        RetrievalEvalSet set = setMapper.selectById(run.getSetId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("set", set);
        out.put("groups", parseArray(run.getGroupsJson()));
        out.put("metrics", parseObject(run.getMetricsJson()));
        out.put("segments", parseObject(run.getSegmentsJson()));
        out.put("timedOutGroups", parseArray(run.getTimedOutGroups()));
        out.put("progress", runner.progress(runId));
        out.put("refTitles", refTitlesOfSet(set));
        out.put("done", run.getDone());
        out.put("total", run.getTotal());
        return out;
    }

    /** 某组逐题结果（分页），供实验明细与跨次对比使用。 */
    public Map<String, Object> resultsOf(String runId, String group, int page, int size) {
        RetrievalEvalRun run = runMapper.selectById(runId);
        if (run == null) throw new BizException(404, "运行不存在");
        String g = group;
        if (g == null || g.isBlank()) g = firstGroupName(run);
        LambdaQueryWrapper<RetrievalEvalResult> qw = new LambdaQueryWrapper<RetrievalEvalResult>()
                .eq(RetrievalEvalResult::getRunId, runId)
                .eq(g != null, RetrievalEvalResult::getGroupName, g);
        long total = resultMapper.selectCount(qw);
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 500);
        List<RetrievalEvalResult> rows = resultMapper.selectList(qw
                .orderByAsc(RetrievalEvalResult::getCreateTime)
                .last("LIMIT " + ((p - 1) * s) + "," + s));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("group", g);
        out.put("rows", rows.stream().map(this::resultRow).toList());
        out.put("refTitles", refTitlesOfSet(setMapper.selectById(run.getSetId())));
        return out;
    }

    public Map<String, Object> cancelRun(String runId) {
        RetrievalEvalRun run = runMapper.selectById(runId);
        if (run == null) throw new BizException(404, "运行不存在");
        if (!"running".equals(run.getStatus())) throw new BizException("该运行已结束（" + run.getStatus() + "）");
        boolean active = runner.cancel(runId);
        if (!active) {
            run.setStatus("cancelled");
            run.setError("已取消：运行不在当前实例（可能刚重启），已完成结果保留");
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
        }
        return Map.of("cancelled", true, "runId", runId);
    }

    public Map<String, Object> setBaseline(String runId) {
        RetrievalEvalRun run = runMapper.selectById(runId);
        if (run == null) throw new BizException(404, "运行不存在");
        if (!"succeeded".equals(run.getStatus()) && !"partial".equals(run.getStatus())) {
            throw new BizException("基线必须是一次已完成且有结果的运行");
        }
        RetrievalEvalSet set = setMapper.selectById(run.getSetId());
        if (set == null) throw new BizException(404, "评估集不存在");
        set.setBaselineRunId(runId);
        setMapper.updateById(set);
        return Map.of("baselineRunId", runId);
    }

    // ==================== 对比 ====================

    public Map<String, Object> compare(String runAId, String runBId, String groupName) {
        RetrievalEvalRun a = runMapper.selectById(runAId);
        RetrievalEvalRun b = runMapper.selectById(runBId);
        if (a == null || b == null) throw new BizException(404, "要对比的运行不存在");
        String ga = groupName == null || groupName.isBlank() ? firstGroupName(a) : groupName;
        String gb = groupName == null || groupName.isBlank() ? firstGroupName(b) : groupName;
        Map<String, RetrievalEvalResult> ra = resultsByQuestion(a.getId(), ga);
        Map<String, RetrievalEvalResult> rb = resultsByQuestion(b.getId(), gb);
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(ra.keySet());
        keys.addAll(rb.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        int better = 0;
        int worse = 0;
        int flat = 0;
        for (String key : keys) {
            RetrievalEvalResult x = ra.get(key);
            RetrievalEvalResult y = rb.get(key);
            int ar = x == null || x.getFirstRank() == null ? 0 : x.getFirstRank();
            int br = y == null || y.getFirstRank() == null ? 0 : y.getFirstRank();
            String verdict = RetrievalEvalMetrics.verdict(ar, br);
            if ("better".equals(verdict)) better++;
            else if ("worse".equals(verdict)) worse++;
            else flat++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", (y != null ? y.getQuestion() : x.getQuestion()));
            row.put("aRank", ar);
            row.put("bRank", br);
            row.put("aRecall5", recall5(x));
            row.put("bRecall5", recall5(y));
            row.put("verdict", verdict);
            row.put("note", y != null && y.getError() != null ? y.getError() : (x == null ? null : x.getError()));
            rows.add(row);
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> r) -> "better".equals(r.get("verdict")) ? 1
                : "worse".equals(r.get("verdict")) ? 0 : 2));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runA", runSummary(a));
        out.put("runB", runSummary(b));
        out.put("rows", rows);
        out.put("counts", Map.of("better", better, "worse", worse, "flat", flat));
        out.put("setChanged", !Objects.equals(a.getSetId(), b.getSetId()));
        String conclusion;
        if (better == 0 && worse == 0) conclusion = "两次运行逐题排名持平";
        else if (worse == 0) conclusion = "B 有 " + better + " 题变好，没有题目变差";
        else if (better == 0) conclusion = "B 有 " + worse + " 题变差，建议先定位退化题";
        else conclusion = "B 改善 " + better + " 题、退化 " + worse + " 题，需结合题重要性判断";
        out.put("conclusion", conclusion);
        return out;
    }

    // ==================== 总览 ====================

    public Map<String, Object> overview(String scopeKbId) {
        RetrievalEvalSet set = catalog.activeSet(scopeKbId);
        Map<String, Object> detail = catalog.detail(set, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set", set);
        out.put("caseCount", detail.get("caseCount"));
        out.put("verifiedCount", detail.get("verifiedCount"));
        out.put("weakCount", detail.get("weakCount"));
        out.put("withExpectedCount", detail.get("withExpectedCount"));
        out.put("staleExpected", detail.get("staleExpected"));
        out.put("qualityScore", detail.get("qualityScore"));
        out.put("judgeModel", configService.get("eval.judgeModel"));
        Map<String, Object> auto = evaluationService.lastAutoReport();
        out.put("lastAutoReport", auto);
        out.put("paramsStale", auto != null && Boolean.TRUE.equals(auto.get("paramsStale")));
        out.put("schedule", Map.of(
                "intervalMs", configService.getInt("eval.autoIntervalMs", 86_400_000),
                "thresholdPct", configService.getDouble("eval.autoThresholdPct", 10.0)));

        if (set == null) {
            out.put("lastRun", null);
            out.put("baselineRun", null);
            out.put("trend", List.of());
            out.put("issues", Map.of("miss", List.of(), "low", List.of(), "stale", List.of(), "unverifiedCount", 0));
            return out;
        }
        List<RetrievalEvalRun> runs = runMapper.selectList(new LambdaQueryWrapper<RetrievalEvalRun>()
                .eq(RetrievalEvalRun::getSetId, set.getId())
                .orderByDesc(RetrievalEvalRun::getCreateTime)
                .last("LIMIT 20"));
        RetrievalEvalRun last = runs.isEmpty() ? null : runs.get(0);
        RetrievalEvalRun baseline = set.getBaselineRunId() == null ? null : runMapper.selectById(set.getBaselineRunId());
        out.put("lastRun", last == null ? null : runSummary(last));
        out.put("baselineRun", baseline == null ? null : runSummary(baseline));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = runs.size() - 1; i >= 0; i--) {
            RetrievalEvalRun r = runs.get(i);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("runId", r.getId());
            t.put("time", r.getCreateTime());
            t.put("status", r.getStatus());
            t.put("trigger", r.getTriggerKind());
            Map<String, Object> m = parseObject(r.getMetricsJson());
            t.put("recall5", m.get("recall@5"));
            t.put("mrr", m.get("MRR"));
            t.put("hitRate", m.get("hitRate"));
            trend.add(t);
        }
        out.put("trend", trend);

        Map<String, Map<String, Object>> lastMetrics = new HashMap<>();
        List<Map<String, Object>> miss = new ArrayList<>();
        List<Map<String, Object>> low = new ArrayList<>();
        if (last != null && !"running".equals(last.getStatus())) {
            String g = firstGroupName(last);
            List<RetrievalEvalResult> rows = resultMapper.selectList(new LambdaQueryWrapper<RetrievalEvalResult>()
                    .eq(RetrievalEvalResult::getRunId, last.getId())
                    .eq(g != null, RetrievalEvalResult::getGroupName, g)
                    .orderByAsc(RetrievalEvalResult::getCreateTime));
            for (RetrievalEvalResult r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("recall5", recall5(r));
                m.put("firstRank", r.getFirstRank());
                lastMetrics.put(r.getCaseId(), m);
                if (r.getFirstRank() == null || r.getFirstRank() == 0) {
                    miss.add(issueRow(r));
                } else if (recall5(r) < 0.5) {
                    low.add(issueRow(r));
                }
            }
        }
        List<RetrievalEvalCase> caseEntities = catalog.casesOf(set.getId(), null);
        List<Map<String, Object>> rows = catalog.caseRows(caseEntities, lastMetrics);
        List<Map<String, Object>> stale = rows.stream().filter(r -> "stale".equals(r.get("quality"))).toList();
        int unverified = (int) rows.stream().filter(r -> "unverified".equals(r.get("quality"))).count();
        Map<String, Object> issues = new LinkedHashMap<>();
        issues.put("miss", miss.stream().limit(10).toList());
        issues.put("low", low.stream().limit(10).toList());
        issues.put("stale", stale.stream().limit(10).toList());
        issues.put("unverifiedCount", unverified);
        out.put("issues", issues);
        out.put("lastRunMetrics", last == null ? Map.of() : parseObject(last.getMetricsJson()));
        out.put("runningRun", last != null && "running".equals(last.getStatus()) ? runner.progress(last.getId()) : null);
        return out;
    }

    // ==================== 导出 ====================
    // （参数组「灰度应用」已随检索全局默认值层退役（2026-11-14）：全局槽位没了，apply 无处可写。
    //   评估扫参本身保留——扫参走线程覆盖，对库级参数仍然有效。）

    public Map<String, Object> exportRun(String runId, String format) {
        RetrievalEvalRun run = runMapper.selectById(runId);
        if (run == null) throw new BizException(404, "运行不存在");
        List<RetrievalEvalResult> rows = resultMapper.selectList(new LambdaQueryWrapper<RetrievalEvalResult>()
                .eq(RetrievalEvalResult::getRunId, runId)
                .orderByAsc(RetrievalEvalResult::getGroupName)
                .orderByAsc(RetrievalEvalResult::getCreateTime));
        String content;
        String filename;
        if ("md".equalsIgnoreCase(format)) {
            StringBuilder sb = new StringBuilder();
            sb.append("# 检索评估报告\n\n");
            sb.append("- 运行：").append(run.getId()).append("\n");
            sb.append("- 时间：").append(run.getCreateTime()).append("\n");
            sb.append("- 状态：").append(run.getStatus()).append("\n");
            sb.append("- 指标：").append(run.getMetricsJson()).append("\n\n");
            sb.append("| 参数组 | 问题 | 命中 | recall@5 | MRR | 首个命中排名 |\n|---|---|---|---|---|---|\n");
            for (RetrievalEvalResult r : rows) {
                sb.append("| ").append(safe(r.getGroupName()))
                        .append(" | ").append(safe(r.getQuestion()))
                        .append(" | ").append(r.getHitCount()).append("/").append(r.getExpectedCount())
                        .append(" | ").append(recall5(r))
                        .append(" | ").append(r.getMrr())
                        .append(" | ").append(r.getFirstRank()).append(" |\n");
            }
            content = sb.toString();
            filename = "retrieval-eval-" + runId + ".md";
        } else {
            StringBuilder sb = new StringBuilder("\ufeff参数组,问题,期望块数,命中数,recall@5,MRR,首个命中排名,充分性,备注\n");
            for (RetrievalEvalResult r : rows) {
                sb.append(csv(r.getGroupName())).append(',')
                        .append(csv(r.getQuestion())).append(',')
                        .append(r.getExpectedCount()).append(',')
                        .append(r.getHitCount()).append(',')
                        .append(recall5(r)).append(',')
                        .append(r.getMrr()).append(',')
                        .append(r.getFirstRank()).append(',')
                        .append(r.getJudgeCovered() == null ? "" : (r.getJudgeCovered() == 1 ? "足以" : "不足")).append(',')
                        .append(csv(r.getError())).append('\n');
            }
            content = sb.toString();
            filename = "retrieval-eval-" + runId + ".csv";
        }
        return Map.of("filename", filename, "content", content);
    }

    // ==================== 工具 ====================

    private List<RetrievalEvaluationService.EvalParams> parseGroups(JSONObject body) {
        List<RetrievalEvaluationService.EvalParams> groups = new ArrayList<>();
        JSONArray arr = body == null ? null : body.getJSONArray("groups");
        if (arr == null) return groups;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            groups.add(new RetrievalEvaluationService.EvalParams(
                    o.getString("name") == null ? "组" + (i + 1) : o.getString("name"),
                    o.getString("mode") == null ? "normal" : o.getString("mode"),
                    o.getDouble("vectorWeight"), o.getDouble("keywordWeight"),
                    o.getDouble("vecThreshold"), o.getInteger("keywordLimit"), o.getInteger("topK"),
                    o.getBoolean("rerankEnabled"), o.getString("lowConfidencePolicy"), o.getDouble("top1Floor")));
        }
        return groups;
    }

    private List<Integer> parseKs(JSONObject body) {
        List<Integer> ks = new ArrayList<>();
        if (body != null && body.getJSONArray("kList") != null) {
            for (Object k : body.getJSONArray("kList")) ks.add(Integer.parseInt(String.valueOf(k)));
        }
        if (ks.isEmpty()) ks = List.of(5, 10, 20);
        return ks.stream().distinct().sorted().toList();
    }

    private Map<String, Object> runSummary(RetrievalEvalRun r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("setId", r.getSetId());
        row.put("setVersion", r.getSetVersion());
        row.put("triggerKind", r.getTriggerKind());
        row.put("status", r.getStatus());
        row.put("total", r.getTotal());
        row.put("done", r.getDone());
        row.put("elapsedMs", r.getElapsedMs());
        row.put("warning", r.getWarning());
        row.put("error", r.getError());
        row.put("staleExpected", r.getStaleExpected());
        row.put("timedOutGroups", parseArray(r.getTimedOutGroups()));
        row.put("judgeModel", r.getJudgeModel());
        row.put("createTime", r.getCreateTime());
        row.put("finishedAt", r.getFinishedAt());
        row.put("metrics", parseObject(r.getMetricsJson()));
        JSONArray gs = parseArray(r.getGroupsJson());
        row.put("groups", gs == null ? List.of() : gs);
        row.put("bestGroupName", bestGroupName(gs));
        return row;
    }

    private String bestGroupName(JSONArray groups) {
        if (groups == null || groups.isEmpty()) return null;
        String best = null;
        double bestScore = -1;
        for (int i = 0; i < groups.size(); i++) {
            JSONObject g = groups.getJSONObject(i);
            JSONObject m = g.getJSONObject("metrics");
            if (m == null) continue;
            double score = m.getDoubleValue("recall@5");
            if (score > bestScore) {
                bestScore = score;
                best = g.getString("name");
            }
        }
        return best;
    }

    private String firstGroupName(RetrievalEvalRun run) {
        JSONArray groups = parseArray(run.getGroupsJson());
        if (groups == null || groups.isEmpty()) return null;
        return groups.getJSONObject(0).getString("name");
    }

    private Map<String, RetrievalEvalResult> resultsByQuestion(String runId, String group) {
        Map<String, RetrievalEvalResult> out = new LinkedHashMap<>();
        for (RetrievalEvalResult r : resultMapper.selectList(new LambdaQueryWrapper<RetrievalEvalResult>()
                .eq(RetrievalEvalResult::getRunId, runId)
                .eq(group != null, RetrievalEvalResult::getGroupName, group)
                .orderByAsc(RetrievalEvalResult::getCreateTime))) {
            out.putIfAbsent(r.getQuestion(), r);
        }
        return out;
    }

    private Map<String, Object> resultRow(RetrievalEvalResult r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", r.getId());
        row.put("groupName", r.getGroupName());
        row.put("groupMode", r.getGroupMode());
        row.put("caseId", r.getCaseId());
        row.put("question", r.getQuestion());
        row.put("expectedIds", parseArray(r.getExpectedIds()));
        row.put("hits", parseArray(r.getHitsJson()));
        row.put("recallAtK", parseObject(r.getRecallJson()));
        row.put("mrr", r.getMrr());
        row.put("firstRank", r.getFirstRank());
        row.put("expectedCount", r.getExpectedCount());
        row.put("hitCount", r.getHitCount());
        row.put("judgeCovered", r.getJudgeCovered());
        row.put("judgeNote", r.getJudgeNote());
        row.put("error", r.getError());
        row.put("latencyMs", r.getLatencyMs());
        return row;
    }

    private Map<String, Object> issueRow(RetrievalEvalResult r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("caseId", r.getCaseId());
        row.put("question", r.getQuestion());
        row.put("recall5", recall5(r));
        row.put("firstRank", r.getFirstRank());
        row.put("expectedCount", r.getExpectedCount());
        return row;
    }

    private Map<String, Object> refTitlesOfSet(RetrievalEvalSet set) {
        if (set == null) return Map.of();
        Set<String> knowledgeIds = new LinkedHashSet<>();
        Set<String> docIds = new LinkedHashSet<>();
        for (RetrievalEvalCase c : catalog.casesOf(set.getId(), null)) {
            knowledgeIds.addAll(catalog.parseIds(c.getExpectedKnowledgeIds()));
            docIds.addAll(catalog.parseIds(c.getExpectedDocIds()));
        }
        return catalog.resolveTitles(knowledgeIds, docIds);
    }

    private Map<String, Object> paramMap(RetrievalEvaluationService.EvalParams p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("mode", p.mode());
        m.put("vectorWeight", p.vectorWeight());
        m.put("keywordWeight", p.keywordWeight());
        m.put("vecThreshold", p.vecThreshold());
        m.put("keywordLimit", p.keywordLimit());
        m.put("topK", p.topK());
        // 只有显式 false 才下发覆盖（见 RetrievalEvaluationService.toOverrides）：true 与 null
        // 的运行行为完全相同，把三态都写进指纹会让等价参数算出不同的 paramsId
        m.put("rerankEnabled", Boolean.FALSE.equals(p.rerankEnabled()) ? Boolean.FALSE : null);
        m.put("lowConfidencePolicy", p.lowConfidencePolicy());
        m.put("top1Floor", p.top1Floor());
        return m;
    }

    private String fingerprint(List<RetrievalEvaluationService.EvalParams> groups) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(JSON.toJSONString(groups.stream().map(this::paramMap).toList())
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(groups.hashCode());
        }
    }

    private static double recall5(RetrievalEvalResult r) {
        if (r == null) return 0;
        JSONObject o = parseObjectStatic(r.getRecallJson());
        return o == null ? 0 : o.getDoubleValue("recall@5");
    }

    private static JSONArray parseArray(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.parseArray(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject parseObject(String json) {
        JSONObject o = parseObjectStatic(json);
        return o == null ? new JSONObject() : o;
    }

    private static JSONObject parseObjectStatic(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }

    private static String csv(String s) {
        return "\"" + String.valueOf(s == null ? "" : s).replace("\"", "\"\"") + "\"";
    }

    private static Double num(Object o) {
        if (o == null) return null;
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
