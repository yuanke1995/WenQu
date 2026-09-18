package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ResultJson;
import com.wisesoft.wenqu.service.HybridRetrievalService;
import com.wisesoft.wenqu.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wisesoft.wenqu.model.KnowledgeBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库接口（管理员）：列表 / 详情 / 新建 / 编辑 / 删除 / 文档归属移动。
 * <p>
 * 知识库是检索的作用域与检索参数的归属：文档归属于库，智能体关联库，
 * 检索按库过滤、参数取库上的配置（留空继承全局）。
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/api/ai/kb")
@RequiredArgsConstructor
@Tag(name = "知识库", description = "文档的容器；检索按库隔离，检索参数随库")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;

    /** 检索链路：检索测试接口直接暴露召回结果，便于验证「按库隔离」是否真的生效 */
    private final HybridRetrievalService retrievalService;

    @Operation(summary = "知识库列表", description = "含每个库的文档数量；默认库排在最前")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(kbService.listWithCounts());
    }

    @Operation(summary = "知识库详情")
    @GetMapping("/{id}")
    public ResultJson get(@PathVariable String id) {
        Object kb = kbService.get(id);
        return kb == null ? ResultJson.error("知识库不存在") : ResultJson.ok(kb);
    }

    @Operation(summary = "新建知识库", description = "body: name(必填)/description/queryParams/isDefault/shareConfig；"
            + "queryParams 为 JSON 字符串，留空表示继承全局检索设置")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        if (body.get("name") == null || String.valueOf(body.get("name")).isBlank()) {
            return ResultJson.error("知识库名称不能为空");
        }
        return ResultJson.ok(kbService.create(body, null));
    }

    @Operation(summary = "编辑知识库", description = "仅更新 body 中出现的字段；queryParams 传 null/空串表示清空并恢复继承全局")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object kb = kbService.update(id, body);
        return kb == null ? ResultJson.error("知识库不存在") : ResultJson.ok(kb);
    }

    @Operation(summary = "删除知识库", description = "逻辑删除；默认库、以及库下仍有文档时拒绝删除（避免文档失去归属导致检索范围突变）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        String reason = kbService.delete(id);
        if (reason != null) return ResultJson.error(reason);
        return ResultJson.ok(Map.of("id", id));
    }

    @Operation(summary = "移动文档到知识库", description = "body: {kbId}——传空表示移回默认库（即 kb_id 置空）")
    @PutMapping("/doc/{docId}")
    public ResultJson moveDoc(@PathVariable String docId, @RequestBody Map<String, Object> body) {
        Object kbId = body.get("kbId");
        boolean ok = kbService.moveDoc(docId, kbId == null ? null : String.valueOf(kbId));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("docId", docId);
        r.put("kbId", kbId);
        return ok ? ResultJson.ok(r) : ResultJson.error("文档不存在");
    }

    /**
     * 检索测试：不经问答链路，直接返回该知识库范围内的召回片段与分数。
     * <p>用于验证两件事：①按库隔离是否生效（换库后命中集合应不同）；
     * ②库上的检索参数是否作用于检索（改阈值后命中数应变化）。
     * <p>范围口径与问答链路一致：库内文档集合（含默认库时并上 kb_id 为空的文档）；
     * 库 ID 配错时得到空集合，不会退化为全库。
     */
    @Operation(summary = "检索测试", description = "body: {query(必填), topK(可选，默认 10)}；"
            + "返回该库范围内的召回片段与分数，用于验证按库隔离与库级检索参数")
    @PostMapping("/{id}/retrieve")
    public ResultJson retrieve(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object q = body == null ? null : body.get("query");
        String query = q == null ? "" : String.valueOf(q).trim();
        if (query.isEmpty()) return ResultJson.error("query 不能为空");
        KnowledgeBase kb = kbService.get(id);
        if (kb == null) return ResultJson.error("知识库不存在");
        int topK = 10;
        Object tk = body == null ? null : body.get("topK");
        if (tk instanceof Number n) topK = Math.max(1, Math.min(50, n.intValue()));

        Set<String> scope = kbService.docIdsOf(List.of(id));
        List<HybridRetrievalService.Hit> hits = retrievalService.search(query);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HybridRetrievalService.Hit h : hits) {
            if (h.docId() == null || !scope.contains(h.docId())) continue;   // 按库过滤
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("knowledgeId", h.knowledgeId());
            m.put("docId", h.docId());
            m.put("title", h.title());
            m.put("titlePath", h.titlePath());
            m.put("chunkIndex", h.chunkIndex());
            m.put("score", h.score());
            String c = h.content() == null ? "" : h.content();
            m.put("content", c.length() > 300 ? c.substring(0, 300) + "…" : c);
            rows.add(m);
            if (rows.size() >= topK) break;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kbId", id);
        r.put("kbName", kb.getName());
        r.put("query", query);
        r.put("scopeDocCount", scope.size());   // 本库范围内的文档数（0 = 库是空的）
        r.put("recalledTotal", hits.size());    // 过滤前总召回（用于对比隔离效果）
        r.put("hitCount", rows.size());
        r.put("hits", rows);
        return ResultJson.ok(r);
    }
}
