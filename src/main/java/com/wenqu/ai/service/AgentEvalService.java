package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.config.ConfigKeys;
import com.wenqu.ai.mapper.AgentEvalCaseMapper;
import com.wenqu.ai.mapper.AgentEvalResultMapper;
import com.wenqu.ai.mapper.AgentEvalRunMapper;
import com.wenqu.ai.mapper.AgentEvalSuiteMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.QaFeedbackMapper;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.AgentEvalCase;
import com.wenqu.ai.model.AgentEvalResult;
import com.wenqu.ai.model.AgentEvalRun;
import com.wenqu.ai.model.AgentEvalSuite;
import com.wenqu.ai.model.Message;
import com.wenqu.ai.model.QaFeedback;
import com.wenqu.ai.util.RequestUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能体评测编排：套件/用例 CRUD、会话采样、运行启动与闸门、门禁状态、运行对比、保存前检查。
 * <p>
 * 口径（智能体评测立项方案.md §3.2/§3.3/§6.1）：门禁状态五态
 * {@code none(无套件) / unevaluated / changed(有变更未评测) / running / pass / below}；
 * 「有变更未评测」= 当前版本号 &gt; 最近成功运行快照的版本号；归属校验：读走
 * {@link AgentService#get}（可读即可见），写与运行走 {@link AgentService#manageable}（谁建归谁）。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class AgentEvalService {

    private final AgentEvalSuiteMapper suiteMapper;
    private final AgentEvalCaseMapper caseMapper;
    private final AgentEvalRunMapper runMapper;
    private final AgentEvalResultMapper resultMapper;
    private final AgentService agentService;
    private final AgentEvalRunner runner;
    private final AgentEvalScorer scorer;
    private final MessageMapper messageMapper;
    private final QaFeedbackMapper feedbackMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final com.wenqu.ai.mapper.AiDocumentMapper documentMapper;
    private final ConfigService configService;

    public AgentEvalService(AgentEvalSuiteMapper suiteMapper, AgentEvalCaseMapper caseMapper,
                            AgentEvalRunMapper runMapper, AgentEvalResultMapper resultMapper,
                            AgentService agentService, AgentEvalRunner runner, AgentEvalScorer scorer,
                            MessageMapper messageMapper, QaFeedbackMapper feedbackMapper,
                            KnowledgeMapper knowledgeMapper, com.wenqu.ai.mapper.AiDocumentMapper documentMapper,
                            ConfigService configService) {
        this.suiteMapper = suiteMapper;
        this.caseMapper = caseMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.agentService = agentService;
        this.runner = runner;
        this.scorer = scorer;
        this.messageMapper = messageMapper;
        this.feedbackMapper = feedbackMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.documentMapper = documentMapper;
        this.configService = configService;
    }

    // ==================== 套件 ====================

    /** 套件（可空）：无套件 = 该智能体「未评测」 */
    public AgentEvalSuite suiteOf(String agentId) {
        return suiteMapper.selectOne(new LambdaQueryWrapper<AgentEvalSuite>()
                .eq(AgentEvalSuite::getAgentId, agentId).last("LIMIT 1"));
    }

    /** 套件与用例清单（评测集 Tab）；可读校验与其他读端点同源——不可见的智能体一律 404，不回落空套件 */
    public Map<String, Object> suiteDetail(String agentId) {
        mustReadable(agentId);
        AgentEvalSuite suite = suiteOf(agentId);
        List<AgentEvalCase> cases = suite == null ? List.of() : casesOf(suite.getId(), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suite", suite);
        out.put("cases", cases);
        out.put("caseCount", cases.size());
        return out;
    }

    /** 创建/更新套件设置（门槛/通过分/启停/名称）；首个用例建议走 cases 接口 */
    public AgentEvalSuite saveSuite(String agentId, Map<String, Object> body) {
        AgentEvalSuite suite = suiteOf(agentId);
        boolean create = suite == null;
        if (create) {
            suite = new AgentEvalSuite();
            suite.setAgentId(agentId);
            suite.setCreatedBy(RequestUser.uid());
        }
        if (body != null) {
            if (body.containsKey("name")) suite.setName(trimStr(body.get("name"), 100));
            if (body.containsKey("thresholdRate")) suite.setThresholdRate(clampRate(body.get("thresholdRate"), 80));
            if (body.containsKey("passScore")) suite.setPassScore(clampRate(body.get("passScore"), 60));
            if (body.containsKey("enabled")) suite.setEnabled(triInt(body.get("enabled"), 1));
        }
        if (suite.getThresholdRate() == null) suite.setThresholdRate(80.0);
        if (suite.getPassScore() == null) suite.setPassScore(60.0);
        if (suite.getEnabled() == null) suite.setEnabled(1);
        if (create) suiteMapper.insert(suite);
        else suiteMapper.updateById(suite);
        return suite;
    }

    // ==================== 用例 ====================

    /** 用例清单（enabledFilter: null=全部 / 1=启用） */
    public List<AgentEvalCase> casesOf(String suiteId, Integer enabledFilter) {
        LambdaQueryWrapper<AgentEvalCase> qw = new LambdaQueryWrapper<AgentEvalCase>()
                .eq(AgentEvalCase::getSuiteId, suiteId)
                .orderByAsc(AgentEvalCase::getCreateTime);
        if (enabledFilter != null) qw.eq(AgentEvalCase::getEnabled, enabledFilter);
        return caseMapper.selectList(qw);
    }

    /** 批量用例维护：{add:[...], update:[...], delete:[ids]}，一端点收口（前端评测集 Tab 增删改） */
    public Map<String, Object> mutateCases(String agentId, JSONObject body) {
        AgentEvalSuite suite = mustSuite(agentId);
        Map<String, Object> out = new LinkedHashMap<>();
        int added = 0;
        int updated = 0;
        int deleted = 0;
        if (body != null) {
            JSONArray add = body.getJSONArray("add");
            if (add != null) {
                for (int i = 0; i < add.size(); i++) {
                    JSONObject c = add.getJSONObject(i);
                    AgentEvalCase entity = new AgentEvalCase();
                    entity.setSuiteId(suite.getId());
                    entity.setAgentId(agentId);
                    applyCaseFields(entity, c, true);
                    if (entity.getQuestion() == null || entity.getQuestion().isBlank()) {
                        throw new BizException("用例问题不能为空");
                    }
                    caseMapper.insert(entity);
                    added++;
                }
            }
            JSONArray update = body.getJSONArray("update");
            if (update != null) {
                for (int i = 0; i < update.size(); i++) {
                    JSONObject c = update.getJSONObject(i);
                    String id = c.getString("id");
                    AgentEvalCase existing = id == null ? null : caseMapper.selectById(id);
                    if (existing == null || !suite.getId().equals(existing.getSuiteId())) {
                        throw new BizException("用例不存在或不属于该评测集");
                    }
                    applyCaseFields(existing, c, false);
                    caseMapper.updateById(existing);
                    updated++;
                }
            }
            JSONArray del = body.getJSONArray("delete");
            if (del != null) {
                for (int i = 0; i < del.size(); i++) {
                    String id = del.getString(i);
                    AgentEvalCase existing = id == null ? null : caseMapper.selectById(id);
                    if (existing != null && suite.getId().equals(existing.getSuiteId())) {
                        caseMapper.deleteById(existing.getId());
                        deleted++;
                    }
                }
            }
        }
        out.put("added", added);
        out.put("updated", updated);
        out.put("deleted", deleted);
        out.put("caseCount", casesOf(suite.getId(), null).size());
        return out;
    }

    /**
     * 从会话采样生成用例（plan §3.6 会话采样）：智能体最近的真实问答里挑「有引用」的轮次，
     * 问题 + 该轮实际引用的知识块/文档作为期望引用；差评（rating=0）优先。同问题去重。
     */
    public Map<String, Object> generateFromSessions(String agentId, JSONObject body) {
        AgentEvalSuite suite = mustSuite(agentId);
        int maxCases = Math.max(1, ConfigDefaults.AGENT_EVAL_MAX_CASES);
        int count = Math.min(Math.max(1, body == null ? 10 : body.getIntValue("count", 10)), maxCases);
        boolean onlyBad = body != null && Boolean.TRUE.equals(body.getBoolean("onlyBad"));

        List<Message> answers = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getAgentId, agentId)
                .eq(Message::getRole, "assistant")
                .eq(Message::getDeleted, 0)
                .isNotNull(Message::getSources)
                .orderByDesc(Message::getCreateTime)
                .last("LIMIT 300"));
        // 差评置顶：rating=0 的反馈挂在 assistant 消息上
        Set<String> badMessageIds = new HashSet<>();
        if (!answers.isEmpty()) {
            List<String> ids = answers.stream().map(Message::getId).toList();
            for (QaFeedback f : feedbackMapper.selectList(new LambdaQueryWrapper<QaFeedback>()
                    .in(QaFeedback::getMessageId, ids).eq(QaFeedback::getRating, 0))) {
                badMessageIds.add(f.getMessageId());
            }
        }
        List<Message> ordered = new ArrayList<>();
        for (Message m : answers) if (badMessageIds.contains(m.getId())) ordered.add(m);
        if (!onlyBad) {
            for (Message m : answers) if (!badMessageIds.contains(m.getId())) ordered.add(m);
        }

        Set<String> existingQuestions = new HashSet<>();
        for (AgentEvalCase c : casesOf(suite.getId(), null)) {
            existingQuestions.add(normalizeQuestion(c.getQuestion()));
        }
        int added = 0;
        for (Message m : ordered) {
            if (added >= count) break;
            Message q = precedingQuestion(m);
            if (q == null || q.getContent() == null || q.getContent().isBlank()) continue;
            String question = trimStr(q.getContent(), 2000);
            String norm = normalizeQuestion(question);
            if (norm.isEmpty() || existingQuestions.contains(norm)) continue;
            // 期望引用 = 该轮实际引用的知识块/文档（去重，各截前 5 条）
            Set<String> kb = new LinkedHashSet<>();
            Set<String> doc = new LinkedHashSet<>();
            collectExpected(m.getSources(), kb, doc, 5);
            AgentEvalCase entity = new AgentEvalCase();
            entity.setSuiteId(suite.getId());
            entity.setAgentId(agentId);
            entity.setQuestion(question);
            entity.setExpectedKnowledgeIds(kb.isEmpty() ? null : JSON.toJSONString(new ArrayList<>(kb)));
            entity.setExpectedDocIds(doc.isEmpty() ? null : JSON.toJSONString(new ArrayList<>(doc)));
            entity.setNote(badMessageIds.contains(m.getId()) ? "差评回流" : "会话采样");
            entity.setSource("session");
            entity.setEnabled(1);
            caseMapper.insert(entity);
            existingQuestions.add(norm);
            added++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("requested", count);
        out.put("caseCount", casesOf(suite.getId(), null).size());
        return out;
    }

    // ==================== 运行 ====================

    /** 启动回归（闸门：总开关 / 套件 / 用例数 / 每智能体 1 个 / 全局并发）；返回 run 行 */
    public AgentEvalRun startRun(String agentId, String trigger, List<String> caseIds, boolean allowSideEffects) {
        if (!configService.getBoolean("agentEval.enabled", true)) {
            throw new BizException("智能体评测功能已被管理员停用");
        }
        if (allowSideEffects) {
            // 安全边界（plan §10）：默认只读评测；「允许副作用工具」需要显式开关——当前仅支持只读，
            // 打开即拒，宁可报错也不静默放宽。
            throw new BizException("评测默认只读，暂不支持放开有副作用的工具");
        }
        Agent agent = mustManageable(agentId);
        AgentEvalSuite suite = mustSuite(agentId);

        List<AgentEvalCase> cases;
        if (caseIds != null && !caseIds.isEmpty()) {
            cases = new ArrayList<>();
            for (String id : caseIds) {
                AgentEvalCase c = caseMapper.selectById(id);
                if (c != null && suite.getId().equals(c.getSuiteId())
                        && (c.getEnabled() == null || c.getEnabled() == 1)) {
                    cases.add(c);
                }
            }
        } else {
            cases = casesOf(suite.getId(), 1);
        }
        if (cases.isEmpty()) throw new BizException("评测集没有可运行的用例，先添加或启用用例");
        int maxCases = ConfigDefaults.AGENT_EVAL_MAX_CASES;
        if (cases.size() > maxCases) {
            throw new BizException("单次最多跑 " + maxCases + " 条（当前勾选 " + cases.size() + " 条），请减少勾选");
        }
        Long runningOfAgent = runMapper.selectCount(new LambdaQueryWrapper<AgentEvalRun>()
                .eq(AgentEvalRun::getAgentId, agentId).eq(AgentEvalRun::getStatus, "running"));
        if (runningOfAgent != null && runningOfAgent > 0) {
            throw new BizException("该智能体已有评测在跑，等它结束或取消后再试");
        }
        int concurrency = Math.max(1, ConfigDefaults.AGENT_EVAL_CONCURRENCY);
        Long runningGlobal = runMapper.selectCount(new LambdaQueryWrapper<AgentEvalRun>()
                .eq(AgentEvalRun::getStatus, "running"));
        if (runningGlobal != null && runningGlobal >= concurrency) {
            throw new BizException("评测并发已满（" + concurrency + " 个在跑），稍后再试");
        }

        AgentEvalRun run = new AgentEvalRun();
        run.setSuiteId(suite.getId());
        run.setAgentId(agentId);
        run.setAgentVersion(agentService.currentVersionNumber(agentId));
        run.setTriggerKind(trigger == null || trigger.isBlank() ? "manual" : trigger);
        run.setStatus("running");
        run.setTotal(cases.size());
        run.setJudgeModel(scorer.judgeModel());
        run.setThresholdRate(suite.getThresholdRate());
        run.setPassScore(suite.getPassScore());
        run.setStartedAt(LocalDateTime.now());
        run.setCreatedBy(RequestUser.uid());
        runMapper.insert(run);
        runner.submit(run, suite, agent.getName(), cases);
        log.info("[AGENT-EVAL] 运行已启动 run={} agent={} {} 条（judge={}）",
                run.getId(), agent.getName(), cases.size(), run.getJudgeModel() == null ? "未配置" : run.getJudgeModel());
        return run;
    }

    /** 运行历史（分页，McpController 同款手工 offset 口径） */
    public Map<String, Object> runsOf(String agentId, int page, int size) {
        mustReadable(agentId);
        long total = runMapper.selectCount(new LambdaQueryWrapper<AgentEvalRun>()
                .eq(AgentEvalRun::getAgentId, agentId));
        int p = Math.max(1, page);
        int s = Math.min(Math.max(1, size), 100);
        List<AgentEvalRun> rows = runMapper.selectList(new LambdaQueryWrapper<AgentEvalRun>()
                .eq(AgentEvalRun::getAgentId, agentId)
                .orderByDesc(AgentEvalRun::getCreateTime)
                .last("LIMIT " + ((p - 1) * s) + "," + s));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows);
        return out;
    }

    /** 单次运行详情（逐条结果 + 期望引用标题对照，供失败展开直接看「为什么」） */
    public Map<String, Object> runDetail(String runId) {
        AgentEvalRun run = runMapper.selectById(runId);
        if (run == null) throw new BizException(404, "运行不存在");
        mustReadable(run.getAgentId());
        List<AgentEvalResult> results = resultMapper.selectList(new LambdaQueryWrapper<AgentEvalResult>()
                .eq(AgentEvalResult::getRunId, runId).orderByAsc(AgentEvalResult::getCreateTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("results", results);
        out.put("done", results.size());
        out.put("running", "running".equals(run.getStatus()));
        // 期望引用标题对照（失败展开卡显示「未命中」的书名而不是裸 ID）：知识块与文档分开查
        Set<String> kbIds = new LinkedHashSet<>();
        Set<String> docIds = new LinkedHashSet<>();
        for (AgentEvalCase c : caseMapper.selectList(new LambdaQueryWrapper<AgentEvalCase>()
                .eq(AgentEvalCase::getAgentId, run.getAgentId()))) {
            collectIds(c.getExpectedKnowledgeIds(), kbIds);
            collectIds(c.getExpectedDocIds(), docIds);
        }
        Map<String, String> refTitles = new HashMap<>();
        try {
            if (!kbIds.isEmpty()) {
                knowledgeMapper.selectBatchIds(kbIds).forEach(k ->
                        refTitles.put(k.getId(), k.getTitle() == null ? k.getId() : k.getTitle()));
            }
            if (!docIds.isEmpty()) {
                documentMapper.selectBatchIds(docIds).forEach(d ->
                        refTitles.put(d.getId(), d.getFileName() == null ? d.getId() : d.getFileName()));
            }
        } catch (Exception ignored) {
            // 标题对照是展示增强，拿不到就回落 ID
        }
        out.put("refTitles", refTitles);
        return out;
    }

    /** 取消运行：置标记 + 叫停在跑题；运行不在本实例时把残留 running 行就地收口 */
    public Map<String, Object> cancelRun(String runId) {
        AgentEvalRun run = runMapper.selectById(runId);
        if (run == null) throw new BizException(404, "运行不存在");
        if (!RequestUser.uid().equals(run.getCreatedBy())) mustManageable(run.getAgentId());
        if (!"running".equals(run.getStatus())) {
            throw new BizException("该运行已结束（" + run.getStatus() + "），无需取消");
        }
        boolean active = runner.cancel(runId);
        if (!active) {
            run.setStatus("cancelled");
            run.setError("已取消：运行不在当前实例（可能刚重启），已完成结果保留");
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", true);
        out.put("runId", runId);
        return out;
    }

    // ==================== 对比 ====================

    /** 两次运行对比（plan §6.4 口径：两次运行逐用例对比，不比较绝对版本配置） */
    public Map<String, Object> compare(String agentId, String runAId, String runBId) {
        mustReadable(agentId);
        AgentEvalRun a = runMapper.selectById(runAId);
        AgentEvalRun b = runMapper.selectById(runBId);
        if (a == null || b == null) throw new BizException(404, "要对比的运行不存在");
        if (!agentId.equals(a.getAgentId()) || !agentId.equals(b.getAgentId())) {
            throw new BizException("只能对比同一智能体的两次运行");
        }
        Map<String, AgentEvalResult> ra = resultsByCase(runAId);
        Map<String, AgentEvalResult> rb = resultsByCase(runBId);
        Set<String> caseIds = new LinkedHashSet<>();
        caseIds.addAll(ra.keySet());
        caseIds.addAll(rb.keySet());

        List<Map<String, Object>> rows = new ArrayList<>();
        int worse = 0;
        int better = 0;
        int onlyBFailed = 0;
        int changed = 0;
        for (String cid : caseIds) {
            AgentEvalResult x = ra.get(cid);
            AgentEvalResult y = rb.get(cid);
            Double sa = x == null ? null : x.getScore();
            Double sb = y == null ? null : y.getScore();
            Double delta = (sa == null || sb == null) ? null : round2(sb - sa);
            boolean aPassed = x != null && x.getPassed() != null && x.getPassed() == 1;
            boolean bPassed = y != null && y.getPassed() != null && y.getPassed() == 1;
            String verdict;
            if (x == null) {
                verdict = "new";          // B 新增用例
            } else if (y == null) {
                verdict = "removed";      // B 已删除用例
            } else if (aPassed && !bPassed) {
                verdict = "onlyBFailed";
                onlyBFailed++;
            } else if (delta != null && delta <= -1) {
                verdict = "worse";
                worse++;
            } else if (delta != null && delta >= 1) {
                verdict = "better";
                better++;
            } else {
                verdict = "flat";
            }
            if (!"flat".equals(verdict) && !"removed".equals(verdict)) changed++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", cid);
            row.put("question", (y != null ? y : x).getQuestion());
            row.put("aScore", sa);
            row.put("bScore", sb);
            row.put("delta", delta);
            row.put("aPassed", aPassed);
            row.put("bPassed", bPassed);
            row.put("verdict", verdict);
            row.put("note", y != null && y.getFailureReason() != null ? y.getFailureReason()
                    : (x != null && x.getFailureReason() != null ? x.getFailureReason() : null));
            rows.add(row);
        }
        rows.sort((r1, r2) -> {
            Double d1 = (Double) r1.get("delta");
            Double d2 = (Double) r2.get("delta");
            double v1 = d1 == null ? -999 : d1;
            double v2 = d2 == null ? -999 : d2;
            return Double.compare(v1, v2);   // 掉分最多的排最前（最该看的在前）
        });

        Double raRate = a.getPassRate();
        Double rbRate = b.getPassRate();
        String conclusion;
        if (raRate == null || rbRate == null) conclusion = "两次运行有未完成的一方，结论不可比";
        else if (rbRate < raRate - 0.01) conclusion = "本版整体变差，不建议直接发布";
        else if (rbRate > raRate + 0.01) conclusion = "本版整体变好";
        else conclusion = "整体持平";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runA", a);
        out.put("runB", b);
        // 指标可空（评判不可用的运行没有综合分、失败运行没有耗时）——Map.of 不吃 null，
        // 这里必须用允许 null 的 LinkedHashMap。
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("passRateA", raRate);
        metrics.put("passRateB", rbRate);
        metrics.put("avgScoreA", a.getAvgScore());
        metrics.put("avgScoreB", b.getAvgScore());
        metrics.put("avgLatencyA", a.getAvgLatencyMs());
        metrics.put("avgLatencyB", b.getAvgLatencyMs());
        metrics.put("avgTokensA", perCaseTokens(a));
        metrics.put("avgTokensB", perCaseTokens(b));
        out.put("metrics", metrics);
        out.put("rows", rows);
        out.put("counts", Map.of("changed", changed, "worse", worse, "better", better, "onlyBFailed", onlyBFailed));
        out.put("conclusion", conclusion);
        return out;
    }

    /** 设为基线：把指定成功运行标记为该智能体「较上版」的比较锚点（落在最近一次成功运行上） */
    public Map<String, Object> setBaseline(String agentId, String runId) {
        mustManageable(agentId);
        AgentEvalRun target = runMapper.selectById(runId);
        if (target == null || !agentId.equals(target.getAgentId())
                || !"succeeded".equals(target.getStatus())) {
            throw new BizException("基线必须是一次已完成的成功运行");
        }
        AgentEvalRun latest = latestRun(agentId, "succeeded");
        if (latest == null) throw new BizException("该智能体还没有成功运行，无法设基线");
        latest.setBaselineRunId(runId);
        runMapper.updateById(latest);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baselineRunId", runId);
        out.put("appliedToRunId", latest.getId());
        return out;
    }

    // ==================== 门禁 ====================

    /** 门禁状态（列表与详情共用判据，编辑器保存前查询同源） */
    public Map<String, Object> gateStatus(String agentId) {
        mustReadable(agentId);
        return gateOf(agentId);
    }

    /**
     * 保存/发布前的服务端门禁：block 档下「低于门槛 / 有变更未重测」一律拒绝。
     * <p>前端检查弹窗只是提示，API 层必须同判据兜底，否则绕过页面直接改配置即可跳过评测。
     */
    public void assertSaveAllowed(String agentId) {
        if (agentId == null || agentId.isBlank()) return;
        String reason = blockReason(gateMode(), gateStatus(agentId));
        if (reason != null) throw new BizException(409, reason);
    }

    /** 纯判据：block 档只拦 below/changed；hint 档永不拦。 */
    static String blockReason(String mode, Map<String, Object> gate) {
        if (!"block".equalsIgnoreCase(mode)) return null;
        String state = gate == null ? "" : String.valueOf(gate.get("gate"));
        if ("below".equals(state)) return "评测门禁未通过：通过率低于门槛，请先重跑评测再保存";
        if ("changed".equals(state)) return "评测门禁未通过：智能体有变更尚未重测，请先运行评测再保存";
        return null;
    }

    /** 当前门禁档位：只认 block，其余一律按 hint（默认）。 */
    private String gateMode() {
        String mode = configService.get(ConfigKeys.AGENT_EVAL_GATE_MODE);
        return "block".equalsIgnoreCase(mode == null ? "" : mode.trim()) ? "block" : "hint";
    }

    /** 保存前检查：门禁状态 + 本次保存的字段级 diff（不落任何数据） */
    public Map<String, Object> gateCheck(String agentId, Map<String, Object> payload) {
        mustManageable(agentId);
        Map<String, Object> out = gateOf(agentId);
        out.put("changes", agentService.diffForGate(agentId, payload));
        return out;
    }

    /** 门禁推导核心：五态判据（plan §3.2 表） */
    private Map<String, Object> gateOf(String agentId) {
        Map<String, Object> out = new LinkedHashMap<>();
        AgentEvalSuite suite = suiteOf(agentId);
        out.put("hasSuite", suite != null);
        out.put("gateMode", gateMode());
        out.put("thresholdRate", suite == null ? null : suite.getThresholdRate());
        int version = agentService.currentVersionNumber(agentId);
        out.put("agentVersion", version);

        AgentEvalRun running = latestRun(agentId, "running");
        if (running != null) {
            long done = resultMapper.selectCount(new LambdaQueryWrapper<AgentEvalResult>()
                    .eq(AgentEvalResult::getRunId, running.getId()));
            out.put("gate", "running");
            out.put("runningRun", Map.of("id", running.getId(), "done", done, "total", running.getTotal()));
            return out;
        }
        if (suite == null) {
            out.put("gate", "none");
            return out;
        }
        AgentEvalRun last = latestRun(agentId, "succeeded");
        out.put("caseCount", casesOf(suite.getId(), 1).size());
        if (last == null) {
            out.put("gate", "unevaluated");
            return out;
        }
        out.put("lastRun", runBrief(last));
        if (last.getAgentVersion() != null && version > last.getAgentVersion()) {
            out.put("gate", "changed");
            return out;
        }
        boolean severe = resultMapper.selectCount(new LambdaQueryWrapper<AgentEvalResult>()
                .eq(AgentEvalResult::getRunId, last.getId())
                .eq(AgentEvalResult::getPassed, 0)
                .eq(AgentEvalResult::getScore, 0)) > 0;
        double threshold = suite.getThresholdRate() == null ? 80 : suite.getThresholdRate();
        Double rate = last.getPassRate();
        boolean ok = rate != null && rate >= threshold && !severe;
        out.put("gate", ok ? "pass" : "below");
        out.put("severe", severe);
        return out;
    }

    // ==================== 总览 ====================

    /** 评测总览：可见智能体逐行门禁状态 + 汇总统计 + 近 6 周趋势 + 最近回归 */
    public Map<String, Object> summary() {
        List<Agent> agents = agentService.list().stream().filter(agentService::readable).toList();
        LocalDateTime weekStart = LocalDateTime.now().minus(Duration.ofDays(7));
        LocalDateTime sixWeeksAgo = LocalDateTime.now().minus(Duration.ofDays(42));

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> dist = new LinkedHashMap<>();
        for (String k : new String[]{"pass", "changed", "below", "none", "unevaluated", "running"}) dist.put(k, 0);
        int belowCount = 0;
        double rateSum = 0;
        int rateCount = 0;
        double prevRateSum = 0;
        int prevRateCount = 0;
        List<Map<String, Object>> todos = new ArrayList<>();

        for (Agent a : agents) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", a.getId());
            row.put("agentName", a.getName());
            row.put("agentIcon", a.getIcon());
            row.put("isBuiltin", a.getIsBuiltin());
            row.put("agentVersion", agentService.currentVersionNumber(a.getId()));
            Map<String, Object> gate = gateOf(a.getId());
            String status = String.valueOf(gate.get("gate"));
            row.put("gate", gate);
            dist.merge(status, 1, Integer::sum);
            AgentEvalSuite suite = suiteOf(a.getId());
            row.put("suite", suite == null ? null : Map.of(
                    "id", suite.getId(),
                    // Map.of 不吃 null：套件三设置字段做兜底（saveSuite 会填默认，防手工改库/老行）
                    "thresholdRate", suite.getThresholdRate() == null ? 80.0 : suite.getThresholdRate(),
                    "passScore", suite.getPassScore() == null ? 60.0 : suite.getPassScore(),
                    "enabled", suite.getEnabled() == null ? 1 : suite.getEnabled()));
            row.put("caseCount", gate.get("caseCount"));
            AgentEvalRun last = latestRun(a.getId(), "succeeded");
            if (last != null) {
                row.put("lastRun", runBrief(last));
                if (last.getPassRate() != null) {
                    rateSum += last.getPassRate();
                    rateCount++;
                }
                AgentEvalRun prev = previousSucceeded(a.getId(), last);
                if (prev != null && prev.getPassRate() != null && last.getPassRate() != null) {
                    row.put("deltaRate", round2(last.getPassRate() - prev.getPassRate()));
                    prevRateSum += prev.getPassRate();
                    prevRateCount++;
                }
                if ("below".equals(status)) {
                    belowCount++;
                    Map<String, Object> todo = new LinkedHashMap<>();
                    todo.put("agentId", a.getId());
                    todo.put("agentName", a.getName());
                    todo.put("kind", "below");
                    todo.put("text", "低于门槛 · " + AgentEvalScorer.trimNum(last.getPassRate() == null ? 0 : last.getPassRate())
                            + "% / " + AgentEvalScorer.trimNum(suite.getThresholdRate() == null ? 80 : suite.getThresholdRate()) + "%");
                    todos.add(todo);
                }
            }
            AgentEvalRun running = latestRun(a.getId(), "running");
            if (running != null) {
                long done = resultMapper.selectCount(new LambdaQueryWrapper<AgentEvalResult>()
                        .eq(AgentEvalResult::getRunId, running.getId()));
                row.put("runningRun", Map.of("id", running.getId(), "done", done, "total", running.getTotal()));
            }
            if ("none".equals(status)) {
                Map<String, Object> todo = new LinkedHashMap<>();
                todo.put("agentId", a.getId());
                todo.put("agentName", a.getName());
                todo.put("kind", "none");
                todo.put("text", "尚未建立评测集");
                todos.add(todo);
            } else if ("changed".equals(status)) {
                Map<String, Object> todo = new LinkedHashMap<>();
                todo.put("agentId", a.getId());
                todo.put("agentName", a.getName());
                todo.put("kind", "changed");
                todo.put("text", "有变更未评测 · v" + row.get("agentVersion"));
                todos.add(todo);
            }
            rows.add(row);
        }

        // 本周回归 + 近 6 周趋势（周桶内成功运行的通过率均值）
        List<AgentEvalRun> recentAll = List.of();
        if (!agents.isEmpty()) {
            recentAll = runMapper.selectList(new LambdaQueryWrapper<AgentEvalRun>()
                    .in(AgentEvalRun::getAgentId, agents.stream().map(Agent::getId).toList())
                    .ge(AgentEvalRun::getCreateTime, sixWeeksAgo)
                    .orderByDesc(AgentEvalRun::getCreateTime)
                    .last("LIMIT 500"));
        }
        long weekRuns = recentAll.stream().filter(r -> r.getCreateTime() != null
                && r.getCreateTime().isAfter(weekStart)).count();
        List<Map<String, Object>> trend = weeklyTrend(recentAll, 6);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (AgentEvalRun r : recentAll) {
            if (recent.size() >= 3) break;
            Map<String, Object> m = runBrief(r);
            m.put("agentName", agentNameOf(agents, r.getAgentId()));
            recent.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agents", rows);
        out.put("distribution", dist);
        out.put("suiteCount", rows.size() - (int) dist.get("none"));
        out.put("agentCount", rows.size());
        out.put("belowCount", belowCount);
        out.put("avgPassRate", rateCount == 0 ? null : round2(rateSum / rateCount));
        out.put("avgPassRateDelta", rateCount == 0 || prevRateCount == 0 ? null
                : round2(rateSum / rateCount - prevRateSum / prevRateCount));
        out.put("weekRuns", weekRuns);
        out.put("trend", trend);
        out.put("recentRuns", recent);
        out.put("todos", todos.size() > 5 ? todos.subList(0, 5) : todos);
        out.put("gateMode", gateMode());
        return out;
    }

    // ==================== 启动清理 ====================

    /** 重启残留：running → failed（进程没了，闸门与轮次都不在了；结果保留已完成部分） */
    public int markInterruptedRuns() {
        List<AgentEvalRun> stale = runMapper.selectList(new LambdaQueryWrapper<AgentEvalRun>()
                .eq(AgentEvalRun::getStatus, "running"));
        int n = 0;
        for (AgentEvalRun r : stale) {
            r.setStatus("failed");
            r.setError("服务重启，运行中断；已完成用例结果保留");
            r.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(r);
            n++;
        }
        if (n > 0) log.warn("[AGENT-EVAL] 启动清理：{} 条残留 running 运行已置 failed", n);
        return n;
    }

    // ==================== 内部 ====================

    private Agent mustReadable(String agentId) {
        Agent a = agentService.get(agentId);
        if (a == null) throw new BizException(404, "智能体不存在或对当前用户不可见");
        return a;
    }

    private Agent mustManageable(String agentId) {
        Agent a = mustReadable(agentId);
        if (!agentService.manageable(a)) {
            throw new BizException(403, "仅可管理自己创建或被授权管理的智能体");
        }
        return a;
    }

    private AgentEvalSuite mustSuite(String agentId) {
        AgentEvalSuite s = suiteOf(agentId);
        if (s == null) throw new BizException("尚未建立评测集，先创建评测集再继续");
        return s;
    }

    private List<AgentEvalResult> resultsOf(String runId) {
        return resultMapper.selectList(new LambdaQueryWrapper<AgentEvalResult>()
                .eq(AgentEvalResult::getRunId, runId));
    }

    private Map<String, AgentEvalResult> resultsByCase(String runId) {
        Map<String, AgentEvalResult> out = new LinkedHashMap<>();
        for (AgentEvalResult r : resultsOf(runId)) {
            if (r.getCaseId() != null) out.putIfAbsent(r.getCaseId(), r);
        }
        return out;
    }

    private AgentEvalRun latestRun(String agentId, String status) {
        return runMapper.selectOne(new LambdaQueryWrapper<AgentEvalRun>()
                .eq(AgentEvalRun::getAgentId, agentId)
                .eq(AgentEvalRun::getStatus, status)
                .orderByDesc(AgentEvalRun::getCreateTime)
                .last("LIMIT 1"));
    }

    private AgentEvalRun previousSucceeded(String agentId, AgentEvalRun after) {
        return runMapper.selectOne(new LambdaQueryWrapper<AgentEvalRun>()
                .eq(AgentEvalRun::getAgentId, agentId)
                .eq(AgentEvalRun::getStatus, "succeeded")
                .ne(AgentEvalRun::getId, after.getId())
                .orderByDesc(AgentEvalRun::getCreateTime)
                .last("LIMIT 1"));
    }

    private Map<String, Object> runBrief(AgentEvalRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("status", r.getStatus());
        m.put("passRate", r.getPassRate());
        m.put("passed", r.getPassed());
        m.put("total", r.getTotal());
        m.put("avgScore", r.getAvgScore());
        m.put("avgLatencyMs", r.getAvgLatencyMs());
        m.put("agentVersion", r.getAgentVersion());
        m.put("trigger", r.getTriggerKind());
        m.put("startedAt", r.getStartedAt());
        m.put("finishedAt", r.getFinishedAt());
        return m;
    }

    private List<Map<String, Object>> weeklyTrend(List<AgentEvalRun> runs, int weeks) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (int i = weeks - 1; i >= 0; i--) {
            LocalDateTime end = now.minus(Duration.ofDays(7L * i));
            LocalDateTime start = end.minus(Duration.ofDays(7));
            double sum = 0;
            int n = 0;
            for (AgentEvalRun r : runs) {
                if (r.getCreateTime() == null || r.getPassRate() == null) continue;
                if (!r.getCreateTime().isBefore(start) && r.getCreateTime().isBefore(end)) {
                    sum += r.getPassRate();
                    n++;
                }
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("label", (i == 0) ? "本周" : (i == 1 ? "上周" : i + " 周前"));
            p.put("value", n == 0 ? null : round2(sum / n));
            out.add(p);
        }
        return out;
    }

    private String agentNameOf(List<Agent> agents, String agentId) {
        for (Agent a : agents) if (a.getId().equals(agentId)) return a.getName();
        return agentId;
    }

    private double perCaseTokens(AgentEvalRun r) {
        if (r.getTotalTokens() == null || r.getTotal() == null || r.getTotal() == 0) return 0;
        return round2(r.getTotalTokens() / (double) r.getTotal());
    }

    /** 前一条用户消息（turn 邻接：同会话内 sequence 更小的最近一条 user） */
    private Message precedingQuestion(Message answer) {
        List<Message> qs = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getSessionId, answer.getSessionId())
                .eq(Message::getRole, "user")
                .eq(Message::getDeleted, 0)
                .lt(answer.getSequence() != null, Message::getSequence, answer.getSequence() == null ? 0 : answer.getSequence())
                .orderByDesc(Message::getSequence)
                .last("LIMIT 1"));
        return qs.isEmpty() ? null : qs.get(0);
    }

    private void collectExpected(String sourcesJson, Set<String> kb, Set<String> doc, int capEach) {
        try {
            JSONArray arr = JSON.parseArray(sourcesJson);
            if (arr == null) return;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject s = arr.getJSONObject(i);
                if (s == null) continue;
                String k = s.getString("knowledgeId");
                String d = s.getString("docId");
                if (k != null && !k.isBlank() && kb.size() < capEach) kb.add(k.trim());
                if (d != null && !d.isBlank() && doc.size() < capEach) doc.add(d.trim());
            }
        } catch (Exception ignored) {
            // 坏 sources JSON 按无期望处理
        }
    }

    private void collectIds(String json, Set<String> out) {
        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr == null) return;
            for (int i = 0; i < arr.size(); i++) {
                String v = arr.getString(i);
                if (v != null && !v.isBlank()) out.add(v.trim());
            }
        } catch (Exception ignored) {
        }
    }

    private void applyCaseFields(AgentEvalCase entity, JSONObject c, boolean create) {
        if (c == null) return;
        if (c.containsKey("question")) entity.setQuestion(trimStr(c.get("question"), 2000));
        if (c.containsKey("expectedKnowledgeIds")) {
            entity.setExpectedKnowledgeIds(idsToJson(c.get("expectedKnowledgeIds")));
        }
        if (c.containsKey("expectedDocIds")) entity.setExpectedDocIds(idsToJson(c.get("expectedDocIds")));
        if (c.containsKey("expectedTools")) entity.setExpectedTools(idsToJson(c.get("expectedTools")));
        if (c.containsKey("note")) entity.setNote(trimStr(c.get("note"), 500));
        if (c.containsKey("tags")) entity.setTags(idsToJson(c.get("tags")));
        if (c.containsKey("source") && create) entity.setSource(trimStr(c.get("source"), 16));
        if (c.containsKey("enabled")) entity.setEnabled(triInt(c.get("enabled"), 1));
        if (create && entity.getSource() == null) entity.setSource("manual");
        if (entity.getEnabled() == null) entity.setEnabled(1);
    }

    private String idsToJson(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.isBlank() ? null : s;
        if (v instanceof JSONArray arr) return arr.isEmpty() ? null : arr.toJSONString();
        if (v instanceof List<?> list) return list.isEmpty() ? null : JSON.toJSONString(list);
        return String.valueOf(v);
    }

    private String normalizeQuestion(String q) {
        return q == null ? "" : q.replaceAll("\\s+", "").toLowerCase();
    }

    private static String trimStr(Object v, int max) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : (s.length() > max ? s.substring(0, max) : s);
    }

    private static Double clampRate(Object v, double def) {
        if (v == null) return def;
        try {
            double d = Double.parseDouble(String.valueOf(v));
            if (Double.isNaN(d) || Double.isInfinite(d)) return def;
            return Math.round(Math.min(100, Math.max(0, d)) * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Integer triInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b ? 1 : 0;
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
