package com.wisesoft.ai.controller;

import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.KnowledgeBase;
import com.wisesoft.ai.service.DocumentService;
import com.wisesoft.ai.service.KnowledgeBaseService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final DocumentService documentService;

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

    @Operation(summary = "新建知识库", description = "body: name(必填)/description/embeddingRef(必填,绑定向量模型)/queryParams/parseParams/isDefault/shareConfig；"
            + "queryParams/parseParams 为 JSON 字符串，留空表示继承全局检索/解析设置")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        if (body.get("name") == null || String.valueOf(body.get("name")).isBlank()) {
            return ResultJson.error("知识库名称不能为空");
        }
        return ResultJson.ok(kbService.create(body, null));
    }

    @Operation(summary = "编辑知识库", description = "仅更新 body 中出现的字段；queryParams/parseParams 传 null/空串表示清空并恢复继承全局；"
            + "embeddingRef（绑定向量模型）必填，变更时自动按库重嵌入（异步，模型不可达则保持原绑定）")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        KnowledgeBase before = kbService.get(id);
        Object kb = kbService.update(id, body);
        if (kb == null) return ResultJson.error("知识库不存在");
        // 向量模型绑定变更：异步按库重嵌（旧向量随旧索引清除，全部块按新模型写回）
        if (before != null) {
            String oldRef = before.getEmbeddingRef() == null ? "" : before.getEmbeddingRef();
            String newRef = ((KnowledgeBase) kb).getEmbeddingRef() == null ? "" : ((KnowledgeBase) kb).getEmbeddingRef();
            if (!oldRef.equals(newRef)) {
                List<String> docIds = new java.util.ArrayList<>(kbService.docIdsOf(java.util.List.of(id)));
                documentService.reembedKbAsync(id, docIds, oldRef, newRef);
            }
        }
        return ResultJson.ok(kb);
    }

    @Operation(summary = "删除知识库", description = "逻辑删除；默认库、以及库下仍有文档时拒绝删除（避免文档失去归属导致检索范围突变）")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        String reason = kbService.delete(id);
        if (reason != null) return ResultJson.error(reason);
        return ResultJson.ok(Map.of("id", id));
    }

    @Operation(summary = "移动文档到知识库", description = "body: {kbId}——传空表示移回默认库（即 kb_id 置空）；"
            + "前后两库向量模型不同时自动异步迁移该文档向量")
    @PutMapping("/doc/{docId}")
    public ResultJson moveDoc(@PathVariable String docId, @RequestBody Map<String, Object> body) {
        Object kbId = body.get("kbId");
        String toKbId = kbId == null ? null : String.valueOf(kbId);
        // 记录迁移前归属（含历史文档 kb_id 为空=默认库），移动后按前后两库的向量模型判断是否迁移向量
        com.wisesoft.ai.model.AiDocument before = documentService.getDoc(docId);
        String fromKbId = before == null ? null : before.getKbId();
        boolean ok = kbService.moveDoc(docId, toKbId);
        if (ok) {
            documentService.migrateDocAsync(docId, fromKbId, toKbId);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("docId", docId);
        r.put("kbId", kbId);
        return ok ? ResultJson.ok(r) : ResultJson.error("文档不存在");
    }
}
