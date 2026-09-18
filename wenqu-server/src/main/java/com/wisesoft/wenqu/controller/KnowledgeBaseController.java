package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ResultJson;
import com.wisesoft.wenqu.model.KnowledgeBase;
import com.wisesoft.wenqu.service.ChunkPresets;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库接口。
 * <p>
 * 路径、请求字段与响应结构均与参考实现的 knowledge_router 对齐：
 * <pre>
 * GET    /knowledge/databases                        列表（{"databases":[...]}）
 * POST   /knowledge/databases                        新建（database_name/description/kb_type/
 *                                                    additional_params/embedding_model_spec/
 *                                                    llm_model_spec/share_config）
 * GET    /knowledge/databases/accessible             可访问列表（供智能体配置）
 * GET    /knowledge/databases/{kb_id}                详情
 * PUT    /knowledge/databases/{kb_id}                编辑（additional_params 为**合并**语义）
 * DELETE /knowledge/databases/{kb_id}                删除
 * GET    /knowledge/chunk-presets                    分块预设（{"chunk_presets":[...],"message":"success"}）
 * GET    /knowledge/databases/{kb_id}/query-params   读检索参数
 * PUT    /knowledge/databases/{kb_id}/query-params   写检索参数
 * POST   /knowledge/databases/{kb_id}/query-test     检索测试
 * </pre>
 *
 * @author yuanke
 */
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
@Tag(name = "知识库", description = "文档的容器、检索的作用域与参数归属")
public class KnowledgeBaseController {

    private final KnowledgeBaseService kbService;
    private final HybridRetrievalService retrievalService;

    // ==================== 知识库 ====================

    @Operation(summary = "知识库列表", description = "响应结构 {\"databases\":[...]}")
    @GetMapping("/databases")
    public ResultJson listDatabases() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KnowledgeBase kb : kbService.list()) {
            rows.add(kbService.serializeKnowledgeBase(kb));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("databases", rows);
        return ResultJson.ok(body);
    }

    @Operation(summary = "新建知识库", description = "body: database_name(必填)/description/kb_type/"
            + "additional_params/embedding_model_spec/llm_model_spec/share_config")
    @PostMapping("/databases")
    public ResultJson createDatabase(@RequestBody Map<String, Object> body) {
        String name = str(body.get("database_name"));
        if (name == null || name.isBlank()) name = str(body.get("name"));
        if (name == null || name.isBlank()) return ResultJson.error("database_name 不能为空");
        KnowledgeBase kb = kbService.create(body, name);
        Map<String, Object> r = kbService.serializeKnowledgeBase(kb);
        r.put("files", new LinkedHashMap<>());   // 与参考实现一致：新建响应带空 files
        return ResultJson.ok(r);
    }

    @Operation(summary = "可访问的知识库", description = "供智能体配置使用；响应 {\"databases\":[...]}")
    @GetMapping("/databases/accessible")
    public ResultJson accessibleDatabases() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KnowledgeBase kb : kbService.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", kb.getName());
            m.put("kb_id", kb.getKbId());
            m.put("description", kb.getDescription() == null ? "" : kb.getDescription());
            m.put("created_by", kb.getCreatedBy());
            m.put("kb_type", kb.getKbType() == null || kb.getKbType().isBlank() ? "milvus" : kb.getKbType());
            m.put("supports_documents", true);
            rows.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("databases", rows);
        return ResultJson.ok(body);
    }

    @Operation(summary = "知识库详情")
    @GetMapping("/databases/{kbId}")
    public ResultJson getDatabase(@PathVariable String kbId) {
        KnowledgeBase kb = kbService.get(kbId);
        return kb == null ? ResultJson.error("知识库不存在") : ResultJson.ok(kbService.serializeKnowledgeBase(kb));
    }

    @Operation(summary = "编辑知识库", description = "仅更新 body 中出现的字段；"
            + "additional_params 为**合并**语义（保留未提及的键，如 chunk_preset_id）")
    @PutMapping("/databases/{kbId}")
    public ResultJson updateDatabase(@PathVariable String kbId, @RequestBody Map<String, Object> body) {
        KnowledgeBase kb = kbService.update(kbId, body);
        return kb == null ? ResultJson.error("知识库不存在") : ResultJson.ok(kbService.serializeKnowledgeBase(kb));
    }

    @Operation(summary = "删除知识库", description = "默认库、以及库下仍有文档时拒绝删除")
    @DeleteMapping("/databases/{kbId}")
    public ResultJson deleteDatabase(@PathVariable String kbId) {
        String reason = kbService.delete(kbId);
        if (reason != null) return ResultJson.error(reason);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kb_id", kbId);
        return ResultJson.ok(r);
    }

    // ==================== 分块预设 ====================

    @Operation(summary = "分块预设清单", description = "响应 {\"chunk_presets\":[{value,label,description}],\"message\":\"success\"}")
    @GetMapping("/chunk-presets")
    public ResultJson chunkPresets() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chunk_presets", ChunkPresets.getChunkPresetOptions());
        body.put("message", "success");
        return ResultJson.ok(body);
    }

    // ==================== 检索参数与检索测试 ====================

    @Operation(summary = "读检索参数", description = "库级检索参数（空 = 未配置，回落全局）")
    @GetMapping("/databases/{kbId}/query-params")
    public ResultJson getQueryParams(@PathVariable String kbId) {
        KnowledgeBase kb = kbService.get(kbId);
        if (kb == null) return ResultJson.error("知识库不存在");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kb_id", kbId);
        body.put("query_params", kbService.parseQueryParamsPublic(kb.getQueryParams()));
        return ResultJson.ok(body);
    }

    @Operation(summary = "写检索参数", description = "body 直接为参数对象（如 {\"retrieval.vecThreshold\":\"0.3\"}）；"
            + "传空对象表示清空、恢复继承全局")
    @PutMapping("/databases/{kbId}/query-params")
    public ResultJson putQueryParams(@PathVariable String kbId, @RequestBody Map<String, Object> body) {
        KnowledgeBase kb = kbService.saveQueryParams(kbId, body);
        if (kb == null) return ResultJson.error("知识库不存在");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kb_id", kbId);
        r.put("query_params", kbService.parseQueryParamsPublic(kb.getQueryParams()));
        return ResultJson.ok(r);
    }

    @Operation(summary = "检索测试", description = "不经问答链路，直接返回该库范围内的召回片段与分数；"
            + "body: {query(必填), topK(可选，默认 10)}")
    @PostMapping("/databases/{kbId}/query-test")
    public ResultJson queryTest(@PathVariable String kbId, @RequestBody Map<String, Object> body) {
        Object q = body == null ? null : body.get("query");
        String query = q == null ? "" : String.valueOf(q).trim();
        if (query.isEmpty()) return ResultJson.error("query 不能为空");
        KnowledgeBase kb = kbService.get(kbId);
        if (kb == null) return ResultJson.error("知识库不存在");
        int topK = 10;
        Object tk = body == null ? null : body.get("topK");
        if (tk instanceof Number n) topK = Math.max(1, Math.min(50, n.intValue()));

        Set<String> scope = kbService.docIdsOf(List.of(kbId));
        List<HybridRetrievalService.Hit> hits = retrievalService.search(query);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HybridRetrievalService.Hit h : hits) {
            if (h.docId() == null || !scope.contains(h.docId())) continue;
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
        r.put("kb_id", kbId);
        r.put("name", kb.getName());
        r.put("query", query);
        r.put("scopeDocCount", scope.size());
        r.put("recalledTotal", hits.size());
        r.put("hitCount", rows.size());
        r.put("hits", rows);
        return ResultJson.ok(r);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
