package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.RetrievalEvalCaseMapper;
import com.wenqu.ai.mapper.RetrievalEvalSetMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.Message;
import com.wenqu.ai.model.RetrievalEvalCase;
import com.wenqu.ai.model.RetrievalEvalSet;
import com.wenqu.ai.util.RequestUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 检索评估集与用例的持久化目录。
 * <p>评估集从「本地 JSON 文件」升级为数据库资产：每次生成产生新版本，用例可增删改、
 * 导入导出、人工确认；运行只引用版本快照，不再受部署目录/容器重建影响。
 */
@Slf4j
@Service
public class RetrievalEvalCatalogService {

    private final RetrievalEvalSetMapper setMapper;
    private final RetrievalEvalCaseMapper caseMapper;
    private final MessageMapper messageMapper;
    private final KnowledgeMapper knowledgeMapper;
    private final AiDocumentMapper documentMapper;

    public RetrievalEvalCatalogService(RetrievalEvalSetMapper setMapper,
                                       RetrievalEvalCaseMapper caseMapper,
                                       MessageMapper messageMapper,
                                       KnowledgeMapper knowledgeMapper,
                                       AiDocumentMapper documentMapper) {
        this.setMapper = setMapper;
        this.caseMapper = caseMapper;
        this.messageMapper = messageMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.documentMapper = documentMapper;
    }

    // ==================== 评估集 ====================

    /** 当前作用域的最新评估集；scopeKbId 为空表示全库评估集。 */
    public RetrievalEvalSet activeSet(String scopeKbId) {
        LambdaQueryWrapper<RetrievalEvalSet> qw = new LambdaQueryWrapper<>();
        if (scopeKbId == null || scopeKbId.isBlank()) {
            qw.isNull(RetrievalEvalSet::getScopeKbId);
        } else {
            qw.eq(RetrievalEvalSet::getScopeKbId, scopeKbId);
        }
        qw.orderByDesc(RetrievalEvalSet::getCreateTime).last("LIMIT 1");
        return setMapper.selectOne(qw);
    }

    public List<RetrievalEvalSet> listSets() {
        return setMapper.selectList(new LambdaQueryWrapper<RetrievalEvalSet>()
                .orderByDesc(RetrievalEvalSet::getCreateTime));
    }

    public List<RetrievalEvalCase> casesOf(String setId, Integer enabled) {
        if (setId == null || setId.isBlank()) return List.of();
        LambdaQueryWrapper<RetrievalEvalCase> qw = new LambdaQueryWrapper<RetrievalEvalCase>()
                .eq(RetrievalEvalCase::getSetId, setId)
                .orderByAsc(RetrievalEvalCase::getCreateTime);
        if (enabled != null) qw.eq(RetrievalEvalCase::getEnabled, enabled);
        return caseMapper.selectList(qw);
    }

    /** 评估集详情：用例 + 期望块标题 + 失效标签 + 质量分。 */
    public Map<String, Object> detail(RetrievalEvalSet set, boolean withCases) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (set == null) {
            out.put("set", null);
            out.put("cases", List.of());
            out.put("caseCount", 0);
            out.put("qualityScore", 0);
            return out;
        }
        List<RetrievalEvalCase> cases = casesOf(set.getId(), null);
        Set<String> allExpected = new HashSet<>();
        for (RetrievalEvalCase c : cases) allExpected.addAll(parseIds(c.getExpectedKnowledgeIds()));
        Set<String> existing = loadExistingKnowledgeIds(allExpected);
        int stale = 0;
        int verified = 0;
        int withExpected = 0;
        for (RetrievalEvalCase c : cases) {
            if (c.getHumanVerified() != null && c.getHumanVerified() == 1) verified++;
            List<String> ids = parseIds(c.getExpectedKnowledgeIds());
            if (!ids.isEmpty()) withExpected++;
            stale += (int) ids.stream().filter(id -> !existing.contains(id)).count();
        }
        out.put("set", set);
        if (withCases) out.put("cases", caseRows(cases, null));
        out.put("caseCount", cases.size());
        out.put("verifiedCount", verified);
        out.put("weakCount", Math.max(0, cases.size() - verified));
        out.put("withExpectedCount", withExpected);
        out.put("staleExpected", stale);
        out.put("qualityScore", RetrievalEvalMetrics.qualityScore(cases.size(), verified, withExpected, stale));
        return out;
    }

    /** 用例行（联表标题、失效标记、质量标记）。 */
    public List<Map<String, Object>> caseRows(List<RetrievalEvalCase> cases, Map<String, Map<String, Object>> lastMetrics) {
        Map<String, String> titles = new HashMap<>();
        Map<String, String> docNames = new HashMap<>();
        Set<String> kbIds = new LinkedHashSet<>();
        Set<String> docIds = new LinkedHashSet<>();
        for (RetrievalEvalCase c : cases) {
            kbIds.addAll(parseIds(c.getExpectedKnowledgeIds()));
            docIds.addAll(parseIds(c.getExpectedDocIds()));
        }
        fillTitles(kbIds, docIds, titles, docNames);
        Set<String> existing = loadExistingKnowledgeIds(kbIds);

        List<Map<String, Object>> out = new ArrayList<>();
        for (RetrievalEvalCase c : cases) {
            List<String> expected = parseIds(c.getExpectedKnowledgeIds());
            List<String> expectedDocs = parseIds(c.getExpectedDocIds());
            List<String> stale = expected.stream().filter(id -> !existing.contains(id)).toList();
            List<Map<String, Object>> expectedTitles = new ArrayList<>();
            for (String id : expected) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", id);
                t.put("title", titles.getOrDefault(id, "（已失效）"));
                t.put("missing", !titles.containsKey(id));
                expectedTitles.add(t);
            }
            for (String id : expectedDocs) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", id);
                t.put("title", docNames.getOrDefault(id, "（已失效）"));
                t.put("doc", true);
                t.put("missing", !docNames.containsKey(id));
                expectedTitles.add(t);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("setId", c.getSetId());
            row.put("question", c.getQuestion());
            row.put("expectedKnowledgeIds", expected);
            row.put("expectedDocIds", expectedDocs);
            row.put("expectedTitles", expectedTitles);
            row.put("staleIds", stale);
            row.put("note", c.getNote());
            row.put("tags", parseIds(c.getTags()));
            row.put("source", c.getSource());
            row.put("humanVerified", c.getHumanVerified());
            row.put("enabled", c.getEnabled());
            row.put("quality", !stale.isEmpty() ? "stale" : (c.getHumanVerified() != null && c.getHumanVerified() == 1 ? "verified" : "unverified"));
            row.put("createTime", c.getCreateTime());
            Map<String, Object> lm = lastMetrics == null ? null : lastMetrics.get(c.getId());
            if (lm != null) {
                row.put("lastRecall5", lm.get("recall5"));
                row.put("lastFirstRank", lm.get("firstRank"));
            }
            out.add(row);
        }
        return out;
    }

    // ==================== 生成 / 导入 ====================

    /** 从历史问答回放生成新版本评估集；scopeKbId 非空时只保留该库的期望块。 */
    public synchronized Map<String, Object> generate(int maxCases, String scopeKbId) {
        int limit = Math.min(Math.max(1, maxCases), 500);
        int version = nextVersion(scopeKbId);
        List<Message> assistants = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getRole, "assistant")
                .isNotNull(Message::getSources)
                .ne(Message::getSources, "")
                .orderByDesc(Message::getCreateTime)
                .last("LIMIT " + limit));
        Set<String> sessionIds = assistants.stream().map(Message::getSessionId).collect(Collectors.toSet());
        Map<String, List<Message>> bySession = sessionIds.isEmpty() ? Map.of()
                : messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getRole, "user")
                        .in(Message::getSessionId, sessionIds)
                        .orderByAsc(Message::getSequence))
                .stream().collect(Collectors.groupingBy(Message::getSessionId));

        Set<String> candidateIds = new HashSet<>();
        for (Message a : assistants) candidateIds.addAll(extractKnowledgeIds(a.getSources()));
        Set<String> existing = loadExistingKnowledgeIds(candidateIds);
        Set<String> inScope = scopeKbId == null || scopeKbId.isBlank() ? existing : knowledgeIdsOfKb(existing, scopeKbId);

        List<RetrievalEvalCase> cases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int stale = 0;
        for (Message a : assistants) {
            Message prevUser = null;
            for (Message u : bySession.getOrDefault(a.getSessionId(), List.of())) {
                if (u.getSequence() != null && a.getSequence() != null && u.getSequence() < a.getSequence()) {
                    prevUser = u;
                }
            }
            if (prevUser == null || prevUser.getContent() == null || prevUser.getContent().isBlank()) continue;
            String question = prevUser.getContent().trim();
            List<String> expected = extractKnowledgeIds(a.getSources());
            // stale 只统计「已失效」；作用域外的块是刻意排除，不算标签失效
            stale += (int) expected.stream().filter(id -> !existing.contains(id)).count();
            List<String> valid = expected.stream()
                    .filter(existing::contains)
                    .filter(inScope::contains)
                    .distinct().toList();
            if (valid.isEmpty() || !seen.add(question)) continue;
            cases.add(newCase(null, truncate(question, 200), valid, List.of(), "", List.of(),
                    "session", 0));
        }
        RetrievalEvalSet set = createSet(version, scopeKbId, "generated", stale);
        for (RetrievalEvalCase c : cases) {
            c.setSetId(set.getId());
            caseMapper.insert(c);
        }
        log.info("[Eval] 评估集生成 v{}（{} 条，取自 {} 条引用消息，剔除失效/越界 {} 个）",
                version, cases.size(), assistants.size(), stale);
        Map<String, Object> out = detail(set, true);
        out.put("generated", cases.size());
        out.put("staleExpected", stale);
        out.put("sourceMessages", assistants.size());
        return out;
    }

    /** 差评回流：追加单条用例（按问题去重）。 */
    public synchronized Map<String, Object> addCase(String question, List<String> knowledgeIds) {
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) throw new BizException("问题不能为空");
        if (q.length() > 200) q = q.substring(0, 200);
        RetrievalEvalSet set = activeSet(null);
        if (set == null) set = createSet(1, null, "manual", 0);
        for (RetrievalEvalCase c : casesOf(set.getId(), null)) {
            if (q.equals(c.getQuestion())) {
                return Map.of("added", false, "reason", "评估集已存在相同问题");
            }
        }
        Set<String> ids = new HashSet<>(knowledgeIds == null ? List.of() : knowledgeIds);
        Set<String> existing = loadExistingKnowledgeIds(ids);
        List<String> valid = ids.stream().filter(existing::contains).distinct().toList();
        RetrievalEvalCase c = newCase(set.getId(), q, valid, List.of(), "差评回流", List.of(),
                "feedback", 1);
        caseMapper.insert(c);
        return Map.of("added", true, "caseId", c.getId(), "expected", valid.size());
    }

    // ==================== 用例维护 ====================

    public Map<String, Object> createCase(JSONObject body) {
        RetrievalEvalSet set = requireActiveSet();
        String q = body == null ? null : body.getString("question");
        if (q == null || q.isBlank()) throw new BizException("问题不能为空");
        for (RetrievalEvalCase c : casesOf(set.getId(), null)) {
            if (q.trim().equals(c.getQuestion())) throw new BizException("评估集已存在相同问题");
        }
        List<String> expected = validIds(body.getJSONArray("expectedKnowledgeIds"));
        List<String> docs = body.getJSONArray("expectedDocIds") == null ? List.of()
                : body.getJSONArray("expectedDocIds").toJavaList(String.class);
        RetrievalEvalCase c = newCase(set.getId(), truncate(q.trim(), 200), expected, docs,
                body.getString("note"), body.getJSONArray("tags") == null ? List.of()
                        : body.getJSONArray("tags").toJavaList(String.class),
                body.getString("source") == null ? "manual" : body.getString("source"),
                body.getIntValue("humanVerified", 0));
        caseMapper.insert(c);
        return Map.of("id", c.getId());
    }

    public Map<String, Object> updateCase(String id, JSONObject body) {
        RetrievalEvalCase c = caseMapper.selectById(id);
        if (c == null) throw new BizException(404, "用例不存在");
        if (body != null) {
            if (body.containsKey("question")) {
                String q = body.getString("question");
                if (q == null || q.isBlank()) throw new BizException("问题不能为空");
                c.setQuestion(truncate(q.trim(), 200));
            }
            if (body.containsKey("expectedKnowledgeIds")) {
                c.setExpectedKnowledgeIds(JSON.toJSONString(validIds(body.getJSONArray("expectedKnowledgeIds"))));
            }
            if (body.containsKey("expectedDocIds")) {
                c.setExpectedDocIds(JSON.toJSONString(body.getJSONArray("expectedDocIds") == null ? List.of()
                        : body.getJSONArray("expectedDocIds").toJavaList(String.class)));
            }
            if (body.containsKey("note")) c.setNote(body.getString("note"));
            if (body.containsKey("tags")) {
                c.setTags(JSON.toJSONString(body.getJSONArray("tags") == null ? List.of()
                        : body.getJSONArray("tags").toJavaList(String.class)));
            }
            if (body.containsKey("humanVerified")) c.setHumanVerified(body.getIntValue("humanVerified", 0));
            if (body.containsKey("enabled")) c.setEnabled(body.getIntValue("enabled", 1));
            if (body.containsKey("source")) c.setSource(body.getString("source"));
        }
        caseMapper.updateById(c);
        return Map.of("updated", true);
    }

    public Map<String, Object> deleteCase(String id) {
        RetrievalEvalCase c = caseMapper.selectById(id);
        if (c == null) throw new BizException(404, "用例不存在");
        caseMapper.deleteById(id);
        return Map.of("deleted", true);
    }

    public Map<String, Object> importCases(JSONObject body) {
        RetrievalEvalSet set = requireActiveSet();
        JSONArray arr = body == null ? null : body.getJSONArray("cases");
        if (arr == null || arr.isEmpty()) throw new BizException("导入内容为空");
        Set<String> existingQuestions = casesOf(set.getId(), null).stream()
                .map(RetrievalEvalCase::getQuestion).collect(Collectors.toSet());
        int added = 0;
        int skipped = 0;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject c = arr.getJSONObject(i);
            String q = c.getString("question");
            if (q == null || q.isBlank() || !existingQuestions.add(q.trim())) {
                skipped++;
                continue;
            }
            List<String> expected = validIds(c.getJSONArray("expectedKnowledgeIds"));
            List<String> docs = c.getJSONArray("expectedDocIds") == null ? List.of()
                    : c.getJSONArray("expectedDocIds").toJavaList(String.class);
            RetrievalEvalCase entity = newCase(set.getId(), truncate(q.trim(), 200), expected, docs,
                    c.getString("note"), c.getJSONArray("tags") == null ? List.of()
                            : c.getJSONArray("tags").toJavaList(String.class),
                    c.getString("source") == null ? "import" : c.getString("source"),
                    c.getIntValue("humanVerified", 0));
            caseMapper.insert(entity);
            added++;
        }
        return Map.of("added", added, "skipped", skipped);
    }

    /** 导出为可再导入的 JSON。 */
    public Map<String, Object> exportCases(String setId) {
        RetrievalEvalSet set = setId == null || setId.isBlank() ? requireActiveSet() : setMapper.selectById(setId);
        if (set == null) throw new BizException(404, "评估集不存在");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RetrievalEvalCase c : casesOf(set.getId(), null)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", c.getQuestion());
            row.put("expectedKnowledgeIds", parseIds(c.getExpectedKnowledgeIds()));
            row.put("expectedDocIds", parseIds(c.getExpectedDocIds()));
            row.put("note", c.getNote());
            row.put("tags", parseIds(c.getTags()));
            row.put("source", c.getSource());
            row.put("humanVerified", c.getHumanVerified());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", set.getVersion());
        out.put("generatedAt", set.getGeneratedAt());
        out.put("scopeKbId", set.getScopeKbId());
        out.put("cases", rows);
        return out;
    }

    // ==================== 工具 ====================

    public RetrievalEvalSet requireActiveSet() {
        RetrievalEvalSet set = activeSet(null);
        if (set == null) throw new BizException("评估集为空，请先生成评估集");
        return set;
    }

    public RetrievalEvalSet requireSet(String setId) {
        RetrievalEvalSet set = setId == null || setId.isBlank() ? activeSet(null) : setMapper.selectById(setId);
        if (set == null) throw new BizException(404, "评估集不存在");
        return set;
    }

    public Set<String> staleIds(RetrievalEvalCase c) {
        List<String> expected = parseIds(c.getExpectedKnowledgeIds());
        if (expected.isEmpty()) return Set.of();
        Set<String> existing = loadExistingKnowledgeIds(new HashSet<>(expected));
        return expected.stream().filter(id -> !existing.contains(id)).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> loadExistingKnowledgeIds(Set<String> ids) {
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

    /** 把知识块 ID 映射回知识库范围（生成按库评估集时过滤）。 */
    private Set<String> knowledgeIdsOfKb(Set<String> knowledgeIds, String kbId) {
        if (knowledgeIds == null || knowledgeIds.isEmpty()) return Set.of();
        try {
            List<Knowledge> ks = knowledgeMapper.selectBatchIds(knowledgeIds);
            Set<String> docIds = ks.stream().map(Knowledge::getDocId).filter(x -> x != null).collect(Collectors.toSet());
            if (docIds.isEmpty()) return Set.of();
            Map<String, String> docToKb = documentMapper.selectBatchIds(docIds).stream()
                    .filter(d -> d.getKbId() != null)
                    .collect(Collectors.toMap(AiDocument::getId, AiDocument::getKbId, (a, b) -> a));
            return ks.stream()
                    .filter(k -> kbId.equals(docToKb.get(k.getDocId())))
                    .map(Knowledge::getId)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("[Eval] 知识库范围过滤失败，按全库处理: {}", e.getMessage());
            return knowledgeIds;
        }
    }

    public Map<String, Object> resolveTitles(Collection<String> knowledgeIds, Collection<String> docIds) {
        Map<String, String> titles = new HashMap<>();
        Map<String, String> docs = new HashMap<>();
        fillTitles(knowledgeIds, docIds, titles, docs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("knowledge", titles);
        out.put("docs", docs);
        return out;
    }

    private void fillTitles(Collection<String> knowledgeIds, Collection<String> docIds,
                            Map<String, String> titles, Map<String, String> docNames) {
        if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
            try {
                List<String> ids = new ArrayList<>(knowledgeIds);
                for (int i = 0; i < ids.size(); i += 500) {
                    List<String> batch = ids.subList(i, Math.min(i + 500, ids.size()));
                    knowledgeMapper.selectBatchIds(batch).forEach(k ->
                            titles.put(k.getId(), k.getTitle() == null || k.getTitle().isBlank() ? k.getId() : k.getTitle()));
                }
            } catch (Exception ignored) {
            }
        }
        if (docIds != null && !docIds.isEmpty()) {
            try {
                documentMapper.selectBatchIds(docIds).forEach(d ->
                        docNames.put(d.getId(), d.getFileName() == null ? d.getId() : d.getFileName()));
            } catch (Exception ignored) {
            }
        }
    }

    private RetrievalEvalSet createSet(int version, String scopeKbId, String source, int stale) {
        RetrievalEvalSet set = new RetrievalEvalSet();
        set.setName(scopeKbId == null ? "默认评估集" : "知识库评估集");
        set.setVersion(version);
        set.setScopeKbId(scopeKbId == null || scopeKbId.isBlank() ? null : scopeKbId);
        set.setGeneratedAt(LocalDateTime.now());
        set.setSource(source);
        set.setStaleExpected(stale);
        set.setCreatedBy(RequestUser.uid());
        setMapper.insert(set);
        return set;
    }

    private int nextVersion(String scopeKbId) {
        RetrievalEvalSet latest = activeSet(scopeKbId);
        return latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1;
    }

    private RetrievalEvalCase newCase(String setId, String question, List<String> expected,
                                      List<String> docs, String note, List<String> tags,
                                      String source, int humanVerified) {
        RetrievalEvalCase c = new RetrievalEvalCase();
        c.setId(UUID.randomUUID().toString().replace("-", ""));
        c.setSetId(setId);
        c.setQuestion(question);
        c.setExpectedKnowledgeIds(JSON.toJSONString(expected == null ? List.of() : expected));
        c.setExpectedDocIds(JSON.toJSONString(docs == null ? List.of() : docs));
        c.setNote(note);
        c.setTags(JSON.toJSONString(tags == null ? List.of() : tags));
        c.setSource(source == null ? "manual" : source);
        c.setHumanVerified(humanVerified);
        c.setEnabled(1);
        return c;
    }

    public List<String> parseIds(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            JSONArray arr = JSON.parseArray(json);
            return arr == null ? new ArrayList<>() : arr.toJavaList(String.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<String> validIds(JSONArray arr) {
        if (arr == null) return List.of();
        Set<String> ids = new HashSet<>(arr.toJavaList(String.class));
        Set<String> existing = loadExistingKnowledgeIds(ids);
        return ids.stream().filter(existing::contains).distinct().toList();
    }

    private List<String> extractKnowledgeIds(String sourcesJson) {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray arr = JSON.parseArray(sourcesJson);
            if (arr == null) return ids;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o != null && o.getString("knowledgeId") != null) ids.add(o.getString("knowledgeId"));
            }
        } catch (Exception ignored) {
        }
        return ids;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
