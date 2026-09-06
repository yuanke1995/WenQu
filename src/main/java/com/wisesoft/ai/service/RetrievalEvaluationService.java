package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.mapper.AiDocumentMapper;
import com.wisesoft.ai.mapper.AiKnowledgeMapper;
import com.wisesoft.ai.mapper.AiMessageMapper;
import com.wisesoft.ai.model.AiDocument;
import com.wisesoft.ai.model.AiKnowledge;
import com.wisesoft.ai.model.AiMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 检索量化评估：评估集（问题→期望知识块）→ 批量参数组对比 → recall@k/MRR/命中率
 * + 弃用过滤正确性断言 + 深度思考多路检索（normal vs multi）对比。
 * 参数覆盖走 ConfigService 线程局部 override，不写 DB 不污染当前配置。
 */
@Slf4j
@Service
public class RetrievalEvaluationService {

    // ==================== 数据模型 ====================

    public record EvalCase(String id, String question, List<String> expectedKnowledgeIds, String note) {}

    /** 评估集（staleExpected=生成时剔除的已失效期望知识块数，供判断标签质量） */
    public record EvalSet(int version, String generatedAt, List<EvalCase> cases, int staleExpected) {}

    /** 评估参数组（null 表示不覆盖该项，用当前配置） */
    public record EvalParams(String name, String mode,
                             Double vectorWeight, Double keywordWeight, Double titleBonus,
                             Double vecThreshold, Integer keywordLimit, Integer topK,
                             Integer rerankMinHits, Integer rerankMaxHits, Boolean rerankEnabled) {
        /** 转 ConfigService 线程局部 override map（null 项跳过） */
        public Map<String, String> toOverrides() {
            Map<String, String> m = new LinkedHashMap<>();
            if (vectorWeight != null) m.put("retrieval.vectorWeight", String.valueOf(vectorWeight));
            if (keywordWeight != null) m.put("retrieval.keywordWeight", String.valueOf(keywordWeight));
            if (titleBonus != null) m.put("retrieval.titleBonus", String.valueOf(titleBonus));
            if (vecThreshold != null) m.put("retrieval.vecThreshold", String.valueOf(vecThreshold));
            if (keywordLimit != null) m.put("retrieval.keywordLimit", String.valueOf(keywordLimit));
            if (topK != null) m.put("retrieval.vectorTopK", String.valueOf(topK));
            if (rerankMinHits != null) m.put("rerank.minHits", String.valueOf(rerankMinHits));
            if (rerankMaxHits != null) m.put("rerank.maxHits", String.valueOf(rerankMaxHits));
            if (rerankEnabled != null) m.put("rerank.enabled", String.valueOf(rerankEnabled));
            return m;
        }
    }

    public record HitInfo(String knowledgeId, String docId, String title, double score) {}

    public record CaseResult(String id, String question, List<String> expected,
                             List<HitInfo> hits, Map<String, Double> recallAtK, double mrr) {}

    public record GroupResult(String name, String mode, EvalParams params,
                              Map<String, Double> metrics, List<CaseResult> cases) {}

    public record EvalResult(List<GroupResult> groups, List<Integer> kList,
                             Map<String, Object> deprecatedCheck, List<String> timedOutGroups, long elapsedMs) {}

    // ==================== 依赖 ====================

    private final HybridRetrievalService retrievalService;
    private final ConfigService configService;
    private final KeywordExtractor keywordExtractor;
    private final RerankService rerankService;
    private final AiMessageMapper messageMapper;
    private final AiKnowledgeMapper knowledgeMapper;
    private final AiDocumentMapper documentMapper;
    private final AiAppProperties properties;
    private final ChatClient chatClient;

    /** 评估执行线程池（daemon；每组参数一个任务，线程局部 override 不污染主线程） */
    private final ExecutorService evalPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "retrieval-eval");
        t.setDaemon(true);
        return t;
    });

    public RetrievalEvaluationService(HybridRetrievalService retrievalService, ConfigService configService,
                                      KeywordExtractor keywordExtractor, RerankService rerankService,
                                      AiMessageMapper messageMapper, AiKnowledgeMapper knowledgeMapper,
                                      AiDocumentMapper documentMapper, AiAppProperties properties,
                                      ChatClient chatClient) {
        this.retrievalService = retrievalService;
        this.configService = configService;
        this.keywordExtractor = keywordExtractor;
        this.rerankService = rerankService;
        this.messageMapper = messageMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.documentMapper = documentMapper;
        this.properties = properties;
        this.chatClient = chatClient;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        evalPool.shutdownNow();
    }

    // ==================== 评估集 ====================

    private Path evalDir() {
        return Paths.get(properties.getImages().getDir(), "eval");
    }

    private Path evalFile() {
        return evalDir().resolve("retrieval-eval.json");
    }

    /** 从历史问答（c_ai_message.sources 含 knowledgeId）回放生成评估集 */
    public synchronized EvalSet generate(int maxCases) {
        int limit = Math.min(Math.max(1, maxCases), 500);
        // 取最近 N 条带引用的 assistant 消息（逻辑删除由 @TableLogic 自动过滤）
        List<AiMessage> assistants = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getRole, "assistant")
                .isNotNull(AiMessage::getSources)
                .ne(AiMessage::getSources, "")
                .orderByDesc(AiMessage::getCreateTime)
                .last("LIMIT " + limit));
        if (assistants.isEmpty()) return new EvalSet(1, now(), List.of(), 0);

        // 按 session 预取 user 消息，避免逐条查库
        Set<String> sessionIds = assistants.stream().map(AiMessage::getSessionId).collect(Collectors.toSet());
        List<AiMessage> userMsgs = messageMapper.selectList(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getRole, "user")
                .in(AiMessage::getSessionId, sessionIds)
                .orderByAsc(AiMessage::getSequence));
        Map<String, List<AiMessage>> bySession = userMsgs.stream()
                .collect(Collectors.groupingBy(AiMessage::getSessionId));

        // 批量校验 expected 知识块仍存在（文档删除/重解析后旧 ID 失效，会让 recall 永远到不了 1，生成时剔除并计数）
        Set<String> candidateIds = new HashSet<>();
        for (AiMessage a : assistants) candidateIds.addAll(extractKnowledgeIds(a.getSources()));
        Set<String> existingIds = loadExistingKnowledgeIds(candidateIds);

        List<EvalCase> cases = new ArrayList<>();
        Set<String> seenQuestions = new HashSet<>();
        int seq = 0;
        int stale = 0;
        for (AiMessage a : assistants) {
            // 该 assistant 消息之前的最近一条 user 消息
            List<AiMessage> users = bySession.getOrDefault(a.getSessionId(), List.of());
            AiMessage prevUser = null;
            for (AiMessage u : users) {
                if (u.getSequence() != null && a.getSequence() != null && u.getSequence() < a.getSequence()) {
                    prevUser = u;
                }
            }
            if (prevUser == null || prevUser.getContent() == null || prevUser.getContent().isBlank()) continue;
            String question = prevUser.getContent().trim();
            List<String> expected = extractKnowledgeIds(a.getSources());
            List<String> valid = expected.stream().filter(existingIds::contains).distinct().toList();
            stale += expected.size() - valid.size();
            if (valid.isEmpty() || !seenQuestions.add(question)) continue;
            cases.add(new EvalCase("c" + (++seq), truncate(question, 200), valid, ""));
        }
        EvalSet set = new EvalSet(1, now(), cases, stale);
        try {
            Files.createDirectories(evalDir());
            Files.writeString(evalFile(), JSON.toJSONString(set, JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[Eval] 评估集写盘失败: {}", e.getMessage());
        }
        log.info("[Eval] 评估集生成 {} 条（取自 {} 条引用消息，剔除失效期望块 {} 个）", cases.size(), assistants.size(), stale);
        return set;
    }

    /** 批量查询仍存在的知识块 ID（评估集期望标签的存在性校验，分批避免 IN 列表过大） */
    private Set<String> loadExistingKnowledgeIds(Set<String> ids) {
        Set<String> existing = new HashSet<>();
        if (ids == null || ids.isEmpty()) return existing;
        List<String> all = new ArrayList<>(ids);
        for (int i = 0; i < all.size(); i += 500) {
            List<String> batch = all.subList(i, Math.min(i + 500, all.size()));
            try {
                knowledgeMapper.selectBatchIds(batch).forEach(k -> existing.add(k.getId()));
            } catch (Exception e) {
                log.warn("[Eval] 知识块存在性校验失败: {}", e.getMessage());
            }
        }
        return existing;
    }

    /** 解析 sources JSON 数组里的 knowledgeId */
    private List<String> extractKnowledgeIds(String sourcesJson) {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray arr = JSON.parseArray(sourcesJson);
            if (arr == null) return ids;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o != null && o.getString("knowledgeId") != null) {
                    ids.add(o.getString("knowledgeId"));
                }
            }
        } catch (Exception e) {
            log.debug("[Eval] sources 解析失败: {}", e.getMessage());
        }
        return ids;
    }

    /** 读取评估集（文件不存在返回空集） */
    public EvalSet load() {
        try {
            if (Files.exists(evalFile())) {
                String json = Files.readString(evalFile(), StandardCharsets.UTF_8);
                JSONObject o = JSON.parseObject(json);
                if (o == null) return new EvalSet(1, "", List.of(), 0);
                List<EvalCase> cases = new ArrayList<>();
                JSONArray arr = o.getJSONArray("cases");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        JSONObject c = arr.getJSONObject(i);
                        cases.add(new EvalCase(
                                c.getString("id"),
                                c.getString("question"),
                                c.getJSONArray("expectedKnowledgeIds") == null ? List.of()
                                        : c.getJSONArray("expectedKnowledgeIds").toJavaList(String.class),
                                c.getString("note")));
                    }
                }
                return new EvalSet(o.getIntValue("version", 1), o.getString("generatedAt"), cases,
                        o.getIntValue("staleExpected", 0));
            }
        } catch (Exception e) {
            log.warn("[Eval] 评估集读取失败: {}", e.getMessage());
        }
        return new EvalSet(1, "", List.of(), 0);
    }

    /**
     * 追加单条评估 case（差评回流：问题 → 引用过的知识块）。
     * synchronized：与 generate 串行化避免写盘互踩；按 question 去重；失效期望块自动剔除。
     *
     * @return added=false 表示已存在相同问题（幂等）
     */
    public synchronized Map<String, Object> addCase(String question, List<String> knowledgeIds) {
        String q0 = question == null ? "" : question.trim();
        if (q0.isEmpty()) throw new com.wisesoft.ai.common.BizException("问题不能为空");
        final String q = q0.length() > 200 ? q0.substring(0, 200) : q0;

        EvalSet set = load();
        if (set.cases().stream().anyMatch(c -> q.equals(c.question()))) {
            return Map.of("added", false, "reason", "评估集已存在相同问题");
        }
        List<String> expected = knowledgeIds == null ? List.of()
                : knowledgeIds.stream().filter(loadExistingKnowledgeIds(new HashSet<>(knowledgeIds))::contains).distinct().toList();
        int maxSeq = set.cases().stream()
                .mapToInt(c -> {
                    try { return Integer.parseInt(c.id().replaceFirst("^c", "")); }
                    catch (Exception e) { return 0; }
                }).max().orElse(0);
        List<EvalCase> cases = new ArrayList<>(set.cases());
        cases.add(new EvalCase("c" + (maxSeq + 1), q, expected, "差评回流"));
        try {
            Files.createDirectories(evalDir());
            Files.writeString(evalFile(), JSON.toJSONString(
                    new EvalSet(set.version(), now(), cases, set.staleExpected()),
                    JSONWriter.Feature.PrettyFormat), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new com.wisesoft.ai.common.BizException("评估集写盘失败: " + e.getMessage());
        }
        log.info("[Eval] 差评回流追加 case：question=[{}] expected={}（失效剔除 {}）", q, expected.size(), knowledgeIds == null ? 0 : knowledgeIds.size() - expected.size());
        return Map.of("added", true, "expected", expected.size());
    }

    // ==================== 运行评估 ====================

    /**
     * 批量运行评估：每组参数一个线程，线程内 putOverrides → 逐 case 检索 → finally clear。
     * mode=normal 单路检索；mode=multi 多路（确定性拆子问题）合并，模拟深度思考检索阶段。
     */
    public EvalResult run(List<EvalCase> cases, List<EvalParams> groups, List<Integer> kList) {
        long start = System.currentTimeMillis();
        List<Integer> ks = kList == null || kList.isEmpty() ? List.of(5, 10, 20) : kList.stream().distinct().sorted().toList();
        List<EvalParams> gs = groups == null || groups.isEmpty()
                ? List.of(new EvalParams("当前配置", "normal", null, null, null, null, null, null, null, null, null))
                : groups;

        // 弃用文档的知识块集合（断言它们不得出现在任何命中）
        Set<String> deprecatedIds = loadDeprecatedKnowledgeIds();
        List<String> violations = Collections.synchronizedList(new ArrayList<>());

        List<Future<GroupResult>> futures = new ArrayList<>();
        for (EvalParams g : gs) {
            futures.add(evalPool.submit(() -> {
                try {
                    configService.putOverrides(g.toOverrides());
                    return runGroup(g, cases, ks, deprecatedIds, violations);
                } finally {
                    configService.clearOverride();
                }
            }));
        }

        List<GroupResult> results = new ArrayList<>();
        List<String> timedOut = new ArrayList<>();
        // 超时按工作量估算：基础 60s + 每 case 3s 余量（multi 模式每 case 最多 3 路检索，关键词路最坏 800ms/路）
        long timeoutMs = 60_000L + cases.size() * 3_000L;
        for (int i = 0; i < futures.size(); i++) {
            Future<GroupResult> f = futures.get(i);
            String name = gs.get(i).name();
            try {
                results.add(f.get(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                f.cancel(true); // 终止任务避免继续占用评估线程；任务 finally 仍会清理 ThreadLocal
                timedOut.add(name);
                log.warn("[Eval] 参数组[{}]评估超时({}ms)，已取消", name, timeoutMs);
            } catch (Exception e) {
                log.warn("[Eval] 参数组[{}]评估失败: {}", name, e.getMessage());
            }
        }

        Map<String, Object> deprecatedCheck = Map.of(
                "ok", violations.isEmpty(),
                "violations", List.copyOf(violations));
        return new EvalResult(results, ks, deprecatedCheck, timedOut, System.currentTimeMillis() - start);
    }

    private GroupResult runGroup(EvalParams params, List<EvalCase> cases, List<Integer> ks,
                                 Set<String> deprecatedIds, List<String> violations) {
        List<CaseResult> caseResults = new ArrayList<>();
        for (EvalCase c : cases) {
            List<HitInfo> hits = searchHits(c.question(), params.mode());
            // 弃用断言：命中里出现已弃用文档的知识块 → violation
            for (HitInfo h : hits) {
                if (deprecatedIds.contains(h.knowledgeId())) {
                    violations.add(c.id() + "(" + truncate(c.question(), 30) + ") 命中已弃用知识块 " + h.knowledgeId());
                }
            }
            caseResults.add(computeCase(c, hits, ks));
        }
        return new GroupResult(params.name(), params.mode(), params, computeMetrics(caseResults, ks), caseResults);
    }

    /** 执行检索（normal 单路 / multi 多路合并），可选重排 */
    private List<HitInfo> searchHits(String question, String mode) {
        List<HybridRetrievalService.Hit> merged;
        if ("multi".equalsIgnoreCase(mode)) {
            merged = multiSearch(question);
        } else {
            merged = retrievalService.search(question);
        }
        // 重排：命中数在 (minHits, maxHits] 且开启才触发（评估默认不开启，避免共享 rerank 状态干扰生产）
        boolean rerankOn = configService.getBoolean("rerank.enabled");
        if (rerankOn && merged.size() > configService.getInt("rerank.minHits", 6)
                && merged.size() <= configService.getInt("rerank.maxHits", 15)) {
            try {
                merged = rerankService.rank(merged, question);
            } catch (Exception e) {
                log.debug("[Eval] 重排失败回退: {}", e.getMessage());
            }
        }
        return merged.stream()
                .map(h -> new HitInfo(h.knowledgeId(), h.docId(), h.title(), h.score()))
                .toList();
    }

    /** 深度思考多路近似：确定性拆子问题逐 query 检索，按 knowledgeId 保留最高分合并（等价 searchMulti 语义） */
    private List<HybridRetrievalService.Hit> multiSearch(String question) {
        List<String> queries = subQueries(question);
        Map<String, HybridRetrievalService.Hit> merged = new LinkedHashMap<>();
        for (String q : queries) {
            try {
                for (HybridRetrievalService.Hit h : retrievalService.search(q)) {
                    merged.merge(h.knowledgeId(), h, (a, b) -> a.score() >= b.score() ? a : b);
                }
            } catch (Exception e) {
                log.debug("[Eval] 多路检索子路失败: {}", e.getMessage());
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(HybridRetrievalService.Hit::score).reversed())
                .toList();
    }

    /** 子问题拆分：含连接词拆段；否则主问题 + 关键词词元组合（确定性近似，不调 LLM） */
    private List<String> subQueries(String question) {
        List<String> parts = new ArrayList<>();
        for (String sep : List.of("和", "与", "、", "及", "，" , ",")) {
            if (question.contains(sep)) {
                for (String p : question.split(sep)) {
                    if (!p.isBlank()) parts.add(p.trim());
                }
                break;
            }
        }
        if (parts.isEmpty()) {
            parts.add(question);
            List<String> terms = keywordExtractor.extract(question);
            if (terms.size() >= 2) {
                parts.add(String.join(" ", terms.subList(0, Math.min(2, terms.size()))));
            }
        }
        return parts.stream().distinct().toList();
    }

    private CaseResult computeCase(EvalCase c, List<HitInfo> hits, List<Integer> ks) {
        Set<String> expected = new HashSet<>(c.expectedKnowledgeIds());
        Map<String, Double> recallAtK = new LinkedHashMap<>();
        for (int k : ks) {
            int hit = 0;
            int limit = Math.min(k, hits.size());
            for (int i = 0; i < limit; i++) {
                if (expected.contains(hits.get(i).knowledgeId())) hit++;
            }
            recallAtK.put("recall@" + k, expected.isEmpty() ? 1.0 : (double) hit / expected.size());
        }
        double mrr = 0;
        for (int i = 0; i < hits.size(); i++) {
            if (expected.contains(hits.get(i).knowledgeId())) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }
        return new CaseResult(c.id(), c.question(), c.expectedKnowledgeIds(), hits, recallAtK, mrr);
    }

    private Map<String, Double> computeMetrics(List<CaseResult> caseResults, List<Integer> ks) {
        Map<String, Double> metrics = new LinkedHashMap<>();
        if (caseResults.isEmpty()) return metrics;
        for (int k : ks) {
            double avg = caseResults.stream().mapToDouble(c -> c.recallAtK().getOrDefault("recall@" + k, 0.0)).average().orElse(0);
            metrics.put("recall@" + k, round3(avg));
        }
        double avgMrr = caseResults.stream().mapToDouble(CaseResult::mrr).average().orElse(0);
        metrics.put("MRR", round3(avgMrr));
        long hitCases = caseResults.stream().filter(c -> c.mrr() > 0).count();
        metrics.put("hitRate", round3((double) hitCases / caseResults.size()));
        return metrics;
    }

    /** 已弃用文档（status=1）的知识块 ID 集合 */
    private Set<String> loadDeprecatedKnowledgeIds() {
        try {
            List<AiDocument> deprecated = documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                    .eq(AiDocument::getStatus, 1));
            if (deprecated.isEmpty()) return Set.of();
            List<String> docIds = deprecated.stream().map(AiDocument::getId).toList();
            List<AiKnowledge> ks = knowledgeMapper.selectList(new LambdaQueryWrapper<AiKnowledge>()
                    .in(AiKnowledge::getDocId, docIds));
            return ks.stream().map(AiKnowledge::getId).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[Eval] 弃用知识块加载失败: {}", e.getMessage());
            return Set.of();
        }
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    // ==================== 自动体检（周期任务 + 看板手动触发共用） ====================

    /**
     * 用"当前线上参数"跑全量评估集，与上期报告（config 键 eval.lastReport）对比核心指标：
     * 任一指标相对跌幅超阈值（eval.autoThresholdPct，默认 10%）→ status=decline（看板红黄灯依据）。
     * 评估集为空/无上期基线时不判退化，只记录现状。结果写回 eval.lastReport 供看板展示。
     */
    public synchronized Map<String, Object> runAutoCheck() {
        long start = System.currentTimeMillis();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runTime", now());
        try {
            EvalSet set = load();
            if (set.cases().isEmpty()) {
                report.put("status", "empty");
                report.put("caseCount", 0);
                report.put("message", "评估集为空：先在「检索评估」页生成评估集，自动体检才会生效");
            } else {
                // 单组"当前配置"（null 覆盖 = 完全跟随线上参数），固定 k=5/10 控制耗时
                EvalResult result = run(set.cases(), null, List.of(5, 10));
                GroupResult group = result.groups().isEmpty() ? null : result.groups().get(0);
                if (group == null) {
                    report.put("status", "error");
                    report.put("message", "体检执行失败：参数组无结果（检索服务异常？）");
                } else {
                    Map<String, Double> metrics = group.metrics();
                    report.put("status", "ok");
                    report.put("caseCount", group.cases().size());
                    report.put("metrics", metrics);
                    // LLM 评判检索充分性（可选增强，eval.judgeEnabled；对每个 case 判 top 命中是否足以回答）
                    if (configService.getBoolean("eval.judgeEnabled")) {
                        try {
                            Double coverage = judgeCoverage(set.cases());
                            if (coverage != null) report.put("judgeScore", coverage);
                        } catch (Exception e) {
                            log.warn("[Eval] 检索充分性评判失败: {}", e.getMessage());
                        }
                    }
                    Object deprecatedOk = result.deprecatedCheck() == null ? null : result.deprecatedCheck().get("ok");
                    report.put("deprecatedOk", deprecatedOk == null || Boolean.TRUE.equals(deprecatedOk));
                    if (!result.timedOutGroups().isEmpty()) report.put("timedOut", result.timedOutGroups());

                    // 与上期对比：同键指标相对变化（%），跌幅超阈值置 decline。
                    // 基线护栏：对比仅在"评估集未变更"时有效——评估集是 generate 从历史问答回放生成的，
                    // 重新生成后样本与上期不同，delta 失去可比性（曾误报 recall@5 下滑）。用
                    // generatedAt+version+caseCount 作为评估集标识，标识不同则本期记为新的基线，不做 delta 判定。
                    String evalSetId = set.generatedAt() + "|v" + set.version() + "|" + set.cases().size();
                    report.put("evalSetId", evalSetId);
                    Map<String, Object> prev = parseReport(configService.get("eval.lastReport"));
                    String prevEvalSetId = prev == null ? null : String.valueOf(prev.get("evalSetId"));
                    Map<String, Double> prevMetrics = prev == null ? null : castMetrics(prev.get("metrics"));
                    double thresholdPct = Math.max(1, configService.getDouble("eval.autoThresholdPct", 10.0));
                    if (prevEvalSetId != null && !prevEvalSetId.equals(evalSetId)) {
                        // 评估集已变更：基线不可比，本期直接作为新基线（metrics 照常记录供展示）
                        report.put("message", "评估集已变更（生成于 " + set.generatedAt() + "，共 " + set.cases().size()
                                + " 条），本期作为新基线，下次起在此样本上对比");
                        log.info("[Eval] 评估集已变更，本期作为新基线: {} -> {}", prevEvalSetId, evalSetId);
                    } else if (prevMetrics == null || prevMetrics.isEmpty()) {
                        report.put("message", "首期体检完成（暂无上期基线，" + metrics.size() + " 项指标）");
                    } else {
                        Map<String, Object> delta = new LinkedHashMap<>();
                        List<String> declinedItems = new ArrayList<>();
                        for (Map.Entry<String, Double> e : metrics.entrySet()) {
                            Double base = prevMetrics.get(e.getKey());
                            if (base == null || base <= 0) continue;
                            double rel = (e.getValue() - base) / base * 100.0;
                            delta.put(e.getKey(), round3(rel));
                            if (rel < -thresholdPct) {
                                declinedItems.add(e.getKey() + " -" + round3(Math.abs(rel)) + "%");
                            }
                        }
                        report.put("deltaPct", delta.isEmpty() ? null : delta);
                        if (!declinedItems.isEmpty()) {
                            report.put("status", "decline");
                            report.put("message", "检索质量下滑（较上期）：" + String.join("、", declinedItems));
                        } else {
                            report.put("message", "各项指标较上期无显著下滑（阈值 " + (long) thresholdPct + "%）");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 检索评估自动体检失败: {}", e.getMessage());
            report.put("status", "error");
            report.put("message", "自动体检执行异常：" + e.getMessage());
        }
        report.put("elapsedMs", System.currentTimeMillis() - start);
        configService.putInternal("eval.lastReport", JSON.toJSONString(report));
        log.info("[Eval] 自动体检完成: status={}, caseCount={}, elapsed={}ms",
                report.get("status"), report.get("caseCount"), report.get("elapsedMs"));
        return report;
    }

    /** 最近一次自动体检报告（从未跑过返回 null） */
    public Map<String, Object> lastAutoReport() {
        return parseReport(configService.get("eval.lastReport"));
    }

    private Map<String, Object> parseReport(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> castMetrics(Object obj) {
        if (!(obj instanceof Map)) return null;
        Map<String, Double> out = new LinkedHashMap<>();
        ((Map<String, Object>) obj).forEach((k, v) -> {
            try {
                out.put(String.valueOf(k), Double.parseDouble(String.valueOf(v)));
            } catch (Exception ignored) {
            }
        });
        return out;
    }

    /**
     * LLM 评判检索充分性：对每个 case 用当前线上参数检索 top 命中，由评判模型判"命中资料是否足以直接回答该问题"，
     * 汇总为 judgeScore（0~1）。这是对 recall@k 的补充：recall 度量"期望块是否在结果里"，judge 度量"用户能否从结果得到答案"。
     * 单 case 单次调用（temperature=0）；失败的单 case 跳过不计。返回 null 表示无法计算（空集）。
     */
    private Double judgeCoverage(List<EvalCase> cases) {
        if (cases.isEmpty()) return null;
        int covered = 0;
        int judged = 0;
        for (EvalCase c : cases) {
            try {
                List<HybridRetrievalService.Hit> hits = retrievalService.search(c.question());
                if (hits.isEmpty()) { judged++; continue; } // 无命中 = 不足以回答
                StringBuilder prompt = new StringBuilder();
                prompt.append("判断下面的资料片段是否足以直接回答用户问题。\n");
                prompt.append("资料中能找到答案要点（操作步骤/定义/规则/数值）就算\"足以\"；资料完全不相关或只有只言片语算\"不足以\"。\n");
                prompt.append("只输出一个词：是 或 否。\n\n用户问题：").append(c.question()).append("\n\n资料片段：\n");
                for (int i = 0; i < Math.min(3, hits.size()); i++) {
                    HybridRetrievalService.Hit h = hits.get(i);
                    String t = h.title() == null ? "" : h.title();
                    String body = h.content() == null ? "" : h.content();
                    if (body.length() > 200) body = body.substring(0, 200);
                    prompt.append("[").append(i + 1).append("] ").append(t).append("：").append(body).append("\n");
                }
                String judge = chatClient.prompt()
                        .system("你是检索质量评估员，只判断资料是否足以回答用户问题，输出\"是\"或\"否\"。")
                        .user(prompt.toString())
                        .options(OpenAiChatOptions.builder()
                                .model(configService.get("chat.model"))
                                .temperature(0.0)
                                .maxTokens(8)
                                .build())
                        .call()
                        .content();
                judged++;
                if (judge != null && judge.contains("是") && !judge.contains("否")) covered++;
            } catch (Exception e) {
                log.warn("[Eval] 检索充分性评判失败（跳过该 case）: {}", e.getMessage());
            }
        }
        return judged == 0 ? null : round3((double) covered / judged);
    }
}
