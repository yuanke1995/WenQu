package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.mapper.RetrievalEvalResultMapper;
import com.wenqu.ai.mapper.RetrievalEvalRunMapper;
import com.wenqu.ai.mapper.UserMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.model.RetrievalEvalCase;
import com.wenqu.ai.model.RetrievalEvalResult;
import com.wenqu.ai.model.RetrievalEvalRun;
import com.wenqu.ai.model.User;
import com.wenqu.ai.util.RequestUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 检索评估异步执行器：每组一个任务，逐题落库并推进度，可取消。
 * <p>与智能体评测不同，这里不跑完整问答，只跑检索；单题毫秒级，因此逐题落库
 * 既能让界面实时看到进度，也能在取消/重启时保留已完成结果。
 */
@Slf4j
@Service
public class RetrievalEvalRunner {

    private final RetrievalEvalRunMapper runMapper;
    private final RetrievalEvalResultMapper resultMapper;
    private final RetrievalEvaluationService evaluationService;
    private final RetrievalEvalApplyService applyService;
    private final ConfigService configService;
    private final NotificationService notificationService;
    private final UserMapper userMapper;
    private final UserRoleService userRoleService;
    private final KnowledgeMapper knowledgeMapper;
    private final AiDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "retrieval-eval-run");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Ctx> active = new ConcurrentHashMap<>();

    private static final class Ctx {
        final String uid;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicInteger done = new AtomicInteger();
        volatile int total;

        Ctx(String uid) {
            this.uid = uid;
        }
    }

    private record GroupExec(String name, String mode,
                             RetrievalEvaluationService.EvalParams params,
                             RetrievalEvaluationService.GroupResult result,
                             long elapsedMs, Double judgeScore, boolean timedOut, String error) {
    }

    public RetrievalEvalRunner(RetrievalEvalRunMapper runMapper,
                               RetrievalEvalResultMapper resultMapper,
                               RetrievalEvaluationService evaluationService,
                               RetrievalEvalApplyService applyService,
                               ConfigService configService,
                               NotificationService notificationService,
                               UserMapper userMapper,
                               UserRoleService userRoleService,
                               KnowledgeMapper knowledgeMapper,
                               AiDocumentMapper documentMapper,
                               KnowledgeBaseMapper knowledgeBaseMapper) {
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.evaluationService = evaluationService;
        this.applyService = applyService;
        this.configService = configService;
        this.notificationService = notificationService;
        this.userMapper = userMapper;
        this.userRoleService = userRoleService;
        this.knowledgeMapper = knowledgeMapper;
        this.documentMapper = documentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    /** 提交运行（RunService 已完成参数校验与 run 落库）。 */
    public void submit(RetrievalEvalRun run, List<RetrievalEvalCase> caseEntities,
                       List<RetrievalEvaluationService.EvalParams> groups, List<Integer> ks,
                       boolean includeJudge) {
        Ctx ctx = new Ctx(run.getCreatedBy() == null ? RequestUser.uid() : run.getCreatedBy());
        active.put(run.getId(), ctx);
        pool.submit(() -> {
            try {
                execute(run, caseEntities, groups, ks, includeJudge, ctx);
            } catch (Exception e) {
                log.warn("[FAIL-LOUD] 检索评估运行 {} 异常: {}", run.getId(), e.getMessage(), e);
                failRun(run.getId(), "执行异常：" + trim(String.valueOf(e.getMessage()), 300));
            } finally {
                active.remove(run.getId());
            }
        });
    }

    /** 取消：置标记，runGroup 在每题之间检查并停止。 */
    public boolean cancel(String runId) {
        Ctx ctx = active.get(runId);
        if (ctx == null) return false;
        ctx.cancelled.set(true);
        return true;
    }

    /** 进度（活动运行读内存；已结束读 done 字段）。 */
    public Map<String, Object> progress(String runId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Ctx ctx = active.get(runId);
        if (ctx != null) {
            out.put("running", true);
            out.put("done", ctx.done.get());
            out.put("total", ctx.total);
        } else {
            RetrievalEvalRun run = runMapper.selectById(runId);
            out.put("running", run != null && "running".equals(run.getStatus()));
            out.put("done", run == null || run.getDone() == null ? 0 : run.getDone());
            out.put("total", run == null || run.getTotal() == null ? 0 : run.getTotal());
        }
        return out;
    }

    // ==================== 执行 ====================

    private void execute(RetrievalEvalRun run, List<RetrievalEvalCase> caseEntities,
                         List<RetrievalEvaluationService.EvalParams> groups, List<Integer> ks,
                         boolean includeJudge, Ctx ctx) {
        long start = System.currentTimeMillis();
        String uid = ctx.uid;
        User u = userMapper.selectById(uid);
        String dept = u == null ? null : u.getDepartmentId();
        String role = u == null ? "user" : userRoleService.primaryRole(uid);

        List<RetrievalEvaluationService.EvalCase> cases = caseEntities.stream()
                .map(c -> new RetrievalEvaluationService.EvalCase(
                        c.getId(), c.getQuestion(),
                        parseIds(c.getExpectedKnowledgeIds()), c.getNote()))
                .toList();
        RetrievalEvaluationService.Revalidated revalidated = evaluationService.revalidateExpected(cases);
        List<RetrievalEvaluationService.EvalCase> liveCases = revalidated.cases();
        if (liveCases.isEmpty()) {
            failRun(run.getId(), "没有可执行的用例（评估集为空或期望块全部失效）");
            return;
        }
        Set<String> deprecated = evaluationService.loadDeprecatedKnowledgeIds();
        List<String> violations = Collections.synchronizedList(new ArrayList<>());
        ctx.total = liveCases.size() * groups.size();
        updateRunProgress(run.getId(), ctx.total);

        List<Future<GroupExec>> futures = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            RetrievalEvaluationService.EvalParams g = groups.get(i);
            boolean judgeThis = includeJudge && i == 0;
            futures.add(pool.submit(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    RequestUser.set(uid, dept, role);
                    configService.putOverrides(g.toOverrides());
                    RetrievalEvaluationService.GroupResult result = evaluationService.runGroup(
                            g, liveCases, ks, deprecated, violations, ctx.cancelled::get,
                            c -> persistCase(run, g, c, ctx));
                    Double judgeScore = null;
                    if (judgeThis) {
                        try {
                            judgeScore = evaluationService.judgeCoverage(liveCases);
                        } catch (Exception e) {
                            log.debug("[Eval] 充分性评判失败（不影响运行）: {}", e.getMessage());
                        }
                    }
                    return new GroupExec(g.name(), g.mode(), g, result,
                            System.currentTimeMillis() - t0, judgeScore, false, null);
                } catch (Exception e) {
                    return new GroupExec(g.name(), g.mode(), g, null,
                            System.currentTimeMillis() - t0, null, false, trim(String.valueOf(e.getMessage()), 300));
                } finally {
                    configService.clearOverride();
                    RequestUser.clear();
                }
            }));
        }

        List<GroupExec> execs = new ArrayList<>();
        List<String> timedOut = new ArrayList<>();
        long timeoutMs = 60_000L + liveCases.size() * 3_000L;
        for (int i = 0; i < futures.size(); i++) {
            String name = groups.get(i).name();
            try {
                execs.add(futures.get(i).get(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                timedOut.add(name);
                execs.add(new GroupExec(name, groups.get(i).mode(), groups.get(i), null,
                        timeoutMs, null, true, "参数组超时"));
                log.warn("[Eval] 参数组[{}]评估超时({}ms)，已取消", name, timeoutMs);
            } catch (Exception e) {
                execs.add(new GroupExec(name, groups.get(i).mode(), groups.get(i), null,
                        0, null, false, "评估失败：" + trim(String.valueOf(e.getMessage()), 200)));
            }
        }

        Map<String, String> caseToTag = new HashMap<>();
        CaseSegments caseSeg = caseSegments(caseEntities);
        for (RetrievalEvalCase c : caseEntities) {
            List<String> tags = parseIds(c.getTags());
            caseToTag.put(c.getId(), tags.isEmpty() ? "未分类" : tags.get(0));
        }

        List<Map<String, Object>> summaries = new ArrayList<>();
        Map<String, Object> allSegments = new LinkedHashMap<>();
        for (GroupExec exec : execs) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", exec.name());
            summary.put("mode", exec.mode());
            summary.put("params", paramsMap(exec.params()));
            summary.put("elapsedMs", exec.elapsedMs());
            summary.put("timedOut", exec.timedOut());
            summary.put("error", exec.error());
            if (exec.result() != null) {
                summary.put("metrics", exec.result().metrics());
                summary.put("caseCount", exec.result().cases().size());
                summary.put("ci", RetrievalEvalMetrics.bootstrapCi(exec.result().cases(), ks, 400));
                summary.put("judgeScore", exec.judgeScore());
                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("byTag", RetrievalEvalMetrics.segments(exec.result().cases(), caseToTag, ks));
                seg.put("byKb", RetrievalEvalMetrics.segments(exec.result().cases(), caseSeg.kb(), ks));
                seg.put("byDocType", RetrievalEvalMetrics.segments(exec.result().cases(), caseSeg.docType(), ks));
                allSegments.put(exec.name(), seg);
            } else {
                summary.put("metrics", Map.of());
                summary.put("ci", Map.of());
                summary.put("caseCount", 0);
            }
            summaries.add(summary);
        }

        Map<String, Object> bestMetrics = new LinkedHashMap<>();
        for (GroupExec exec : execs) {
            if (exec.result() != null && !exec.result().metrics().isEmpty()) {
                bestMetrics.putAll(exec.result().metrics());
                break;
            }
        }
        boolean anySuccess = execs.stream().anyMatch(e -> e.result() != null);
        boolean cancelled = ctx.cancelled.get();

        RetrievalEvalRun update = runMapper.selectById(run.getId());
        if (update == null) return;
        update.setStatus(cancelled ? "cancelled" : (anySuccess ? (timedOut.isEmpty() ? "succeeded" : "partial") : "failed"));
        update.setDone(ctx.done.get());
        update.setTotal(ctx.total);
        update.setElapsedMs(System.currentTimeMillis() - start);
        update.setFinishedAt(LocalDateTime.now());
        update.setStaleExpected(revalidated.stale());
        update.setTimedOutGroups(JSON.toJSONString(timedOut));
        update.setGroupsJson(JSON.toJSONString(summaries));
        update.setMetricsJson(JSON.toJSONString(bestMetrics));
        update.setSegmentsJson(JSON.toJSONString(allSegments));
        if (cancelled) update.setWarning("已取消：已完成 " + ctx.done.get() + "/" + ctx.total + " 条，结果保留");
        else if (revalidated.stale() > 0 || revalidated.skipped() > 0) {
            update.setWarning("失效期望块 " + revalidated.stale() + " 个已剔除"
                    + (revalidated.skipped() > 0 ? "，" + revalidated.skipped() + " 条用例因期望块全失效跳过" : ""));
        } else if (!timedOut.isEmpty()) {
            update.setWarning("超时参数组：" + String.join("、", timedOut));
        }
        if (!anySuccess && !cancelled) {
            update.setStatus("failed");
            update.setError("所有参数组均执行失败");
        }
        runMapper.updateById(update);

        try {
            applyService.observe(Map.of("metrics", bestMetrics));
        } catch (Exception e) {
            log.debug("[Eval] 灰度应用观察失败（不影响运行）: {}", e.getMessage());
        }
        notifyOutcome(update, bestMetrics);
        log.info("[Eval] 运行 {} 结束：status={} {}/{} elapsed={}ms",
                run.getId(), update.getStatus(), ctx.done.get(), ctx.total, update.getElapsedMs());
    }

    private void persistCase(RetrievalEvalRun run, RetrievalEvaluationService.EvalParams group,
                             RetrievalEvaluationService.CaseResult c, Ctx ctx) {
        try {
            RetrievalEvalResult r = new RetrievalEvalResult();
            r.setRunId(run.getId());
            r.setGroupName(group.name());
            r.setGroupMode(group.mode());
            r.setCaseId(c.id());
            r.setQuestion(trim(c.question(), 2000));
            r.setExpectedIds(JSON.toJSONString(c.expected()));
            r.setHitsJson(JSON.toJSONString(c.hits().stream().limit(20).map(h -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("knowledgeId", h.knowledgeId());
                m.put("docId", h.docId());
                m.put("title", h.title());
                m.put("score", h.score());
                return m;
            }).toList()));
            r.setRecallJson(JSON.toJSONString(c.recallAtK()));
            r.setMrr(c.mrr());
            r.setFirstRank(c.firstRank());
            r.setExpectedCount(c.expected().size());
            r.setHitCount(c.hitRanks().size());
            resultMapper.insert(r);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 检索评估逐题结果落库失败 run={} case={}: {}",
                    run.getId(), c.id(), e.getMessage());
        }
        ctx.done.incrementAndGet();
    }

    private void updateRunProgress(String runId, int total) {
        try {
            RetrievalEvalRun run = runMapper.selectById(runId);
            if (run == null) return;
            run.setTotal(total);
            run.setDone(0);
            runMapper.updateById(run);
        } catch (Exception ignored) {
        }
    }

    private void failRun(String runId, String error) {
        try {
            RetrievalEvalRun run = runMapper.selectById(runId);
            if (run == null) return;
            run.setStatus("failed");
            run.setError(trim(error, 900));
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 检索评估失败收口失败 run={}: {}", runId, e.getMessage());
        }
    }

    private void notifyOutcome(RetrievalEvalRun run, Map<String, Object> metrics) {
        try {
            String title = switch (run.getStatus()) {
                case "succeeded" -> "检索评估完成";
                case "partial" -> "检索评估完成（部分组超时）";
                case "cancelled" -> "检索评估已取消";
                default -> "检索评估失败";
            };
            String content = "运行 " + shortId(run.getId()) + " · " + run.getTotal() + " 条，"
                    + (metrics.isEmpty() ? "无有效指标" : "基线 recall@5 " + metrics.getOrDefault("recall@5", "—")
                    + " / MRR " + metrics.getOrDefault("MRR", "—"))
                    + "。点击查看逐题结果。";
            notificationService.create(run.getCreatedBy(),
                    "failed".equals(run.getStatus()) ? Notification.TYPE_EVAL_FAILED : Notification.TYPE_EVAL_DONE,
                    title, content, "eval", run.getId());
        } catch (Exception e) {
            log.debug("[Eval] 站内通知发送失败（不影响收口）: {}", e.getMessage());
        }
    }

    private record CaseSegments(Map<String, String> kb, Map<String, String> docType) {
    }

    private CaseSegments caseSegments(List<RetrievalEvalCase> cases) {
        Map<String, String> kbOut = new HashMap<>();
        Map<String, String> typeOut = new HashMap<>();
        try {
            Set<String> knowledgeIds = new LinkedHashSet<>();
            for (RetrievalEvalCase c : cases) knowledgeIds.addAll(parseIds(c.getExpectedKnowledgeIds()));
            if (knowledgeIds.isEmpty()) return new CaseSegments(kbOut, typeOut);
            List<Knowledge> ks = knowledgeMapper.selectBatchIds(knowledgeIds);
            Map<String, String> kidToDoc = ks.stream()
                    .filter(k -> k.getDocId() != null)
                    .collect(Collectors.toMap(Knowledge::getId, Knowledge::getDocId, (a, b) -> a));
            Set<String> docIds = new LinkedHashSet<>(kidToDoc.values());
            if (docIds.isEmpty()) return new CaseSegments(kbOut, typeOut);
            List<AiDocument> docs = documentMapper.selectBatchIds(docIds);
            Map<String, String> docToKb = docs.stream()
                    .filter(d -> d.getKbId() != null)
                    .collect(Collectors.toMap(AiDocument::getId, AiDocument::getKbId, (a, b) -> a));
            Map<String, String> docToType = docs.stream()
                    .filter(d -> d.getFileType() != null)
                    .collect(Collectors.toMap(AiDocument::getId, AiDocument::getFileType, (a, b) -> a));
            Set<String> kbIds = new LinkedHashSet<>(docToKb.values());
            Map<String, String> kbNames = kbIds.isEmpty() ? Map.of()
                    : knowledgeBaseMapper.selectBatchIds(kbIds).stream()
                    .collect(Collectors.toMap(KnowledgeBase::getId,
                            k -> k.getName() == null ? k.getId() : k.getName(), (a, b) -> a));
            for (RetrievalEvalCase c : cases) {
                List<String> expected = parseIds(c.getExpectedKnowledgeIds());
                String kbName = "无期望";
                String docType = "无期望";
                if (!expected.isEmpty()) {
                    String doc = kidToDoc.get(expected.get(0));
                    String kb = doc == null ? null : docToKb.get(doc);
                    kbName = kb == null ? "未知知识库" : kbNames.getOrDefault(kb, kb);
                    docType = doc == null ? "未知文档" : docToType.getOrDefault(doc, "未知文档");
                }
                kbOut.put(c.getId(), kbName);
                typeOut.put(c.getId(), docType);
            }
        } catch (Exception ignored) {
        }
        return new CaseSegments(kbOut, typeOut);
    }

    private static Map<String, Object> paramsMap(RetrievalEvaluationService.EvalParams p) {
        if (p == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vectorWeight", p.vectorWeight());
        m.put("keywordWeight", p.keywordWeight());
        m.put("vecThreshold", p.vecThreshold());
        m.put("keywordLimit", p.keywordLimit());
        m.put("topK", p.topK());
        m.put("rerankEnabled", p.rerankEnabled());
        m.put("lowConfidencePolicy", p.lowConfidencePolicy());
        m.put("top1Floor", p.top1Floor());
        return m;
    }

    public static List<String> parseIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.parseArray(json).toJavaList(String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String shortId(String id) {
        return id == null ? "—" : id.length() <= 8 ? id : id.substring(0, 8);
    }
}
