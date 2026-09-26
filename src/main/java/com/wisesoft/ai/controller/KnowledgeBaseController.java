package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.model.KnowledgeBase;
import com.wisesoft.ai.service.AuthService;
import com.wisesoft.ai.service.DocumentService;
import com.wisesoft.ai.service.KnowledgeBaseService;
import com.wisesoft.ai.service.ResourceVisibilityService;
import com.wisesoft.ai.util.RequestUser;
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
 * 知识库接口：**用户可自建自管**（2026-09-26 从"仅管理员"放开）。
 * <p>
 * 数据隔离口径（{@link ResourceVisibilityService}，资源类型 KNOWLEDGE_BASE）：
 * <ul>
 *   <li>创建者 → 可管理自己的库（新建/编辑/删除/移文档/传文档）；</li>
 *   <li>他人 → 按库的 share_config 共享范围可见；普通用户对别人的库**封顶只读**（看文档、检索引用），不能改；</li>
 *   <li>share_config 未配置 = 全局可见（兼容存量数据）。</li>
 * </ul>
 * 管理员照旧全量可见可管。检索链路的可见性过滤由既有检索层负责，与本接口口径同源。
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
    private final ResourceVisibilityService visibility;

    private boolean admin() {
        return AuthService.isAdminRole(RequestUser.role());
    }

    private ResourceVisibilityService.Principal principal() {
        return new ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    /** 非管理员时校验"能管理该库"，否则抛业务异常（前端显示具体原因，不触发全局 403 提示） */
    private void requireManage(KnowledgeBase kb) {
        if (admin()) return;
        if (kb == null || !visibility.canManage(principal(), kb.getShareConfig(), kb.getCreatedBy(),
                ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
            throw new BizException("仅可管理自己创建或被授权管理的知识库");
        }
    }

    private KnowledgeBase mustGet(String id) {
        KnowledgeBase kb = kbService.get(id);
        if (kb == null) throw new BizException("知识库不存在");
        return kb;
    }

    @Operation(summary = "知识库列表", description = "含每个库的文档数量；默认库排在最前；普通用户只返回共享范围内可见的库")
    @GetMapping("/list")
    public ResultJson list() {
        List<Map<String, Object>> all = kbService.listWithCounts();
        if (!admin()) {
            var p = principal();
            all = all.stream().filter(m -> visibility.canRead(p,
                    (String) m.get("shareConfig"), (String) m.get("createdBy"),
                    ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)).toList();
        }
        return ResultJson.ok(all);
    }

    @Operation(summary = "知识库详情", description = "普通用户仅共享范围内可见的库可查（不可见按不存在处理，不泄露存在性）")
    @GetMapping("/{id}")
    public ResultJson get(@PathVariable String id) {
        KnowledgeBase kb = kbService.get(id);
        if (kb == null) return ResultJson.error("知识库不存在");
        if (!admin() && !visibility.canRead(principal(), kb.getShareConfig(), kb.getCreatedBy(),
                ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
            return ResultJson.error("知识库不存在");
        }
        return ResultJson.ok(kb);
    }

    @Operation(summary = "新建知识库", description = "body: name(必填)/description/embeddingRef(必填,绑定向量模型)/queryParams/parseParams/isDefault/shareConfig；"
            + "queryParams/parseParams 为 JSON 字符串，留空表示继承全局检索/解析设置；创建人=当前用户（默认库标记仅管理员可设）")
    @PostMapping
    public ResultJson create(@RequestBody Map<String, Object> body) {
        if (body.get("name") == null || String.valueOf(body.get("name")).isBlank()) {
            return ResultJson.error("知识库名称不能为空");
        }
        // isDefault（设默认库）是全局动作：普通用户建库一律不带默认标记
        if (!admin()) body.put("isDefault", 0);
        return ResultJson.ok(kbService.create(body, RequestUser.uid()));
    }

    @Operation(summary = "编辑知识库", description = "仅更新 body 中出现的字段；queryParams/parseParams 传 null/空串表示清空并恢复继承全局；"
            + "embeddingRef（绑定向量模型）必填，变更时自动按库重嵌入（异步，模型不可达则保持原绑定）；仅创建者/被授权人/管理员可改")
    @PutMapping("/{id}")
    public ResultJson update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        KnowledgeBase before = mustGet(id);
        requireManage(before);
        Object kb = kbService.update(id, body);
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

    @Operation(summary = "删除知识库", description = "逻辑删除；默认库、以及库下仍有文档时拒绝删除（避免文档失去归属导致检索范围突变）；仅创建者/被授权人/管理员可删")
    @DeleteMapping("/{id}")
    public ResultJson delete(@PathVariable String id) {
        requireManage(mustGet(id));
        String reason = kbService.delete(id);
        if (reason != null) return ResultJson.error(reason);
        return ResultJson.ok(Map.of("id", id));
    }

    @Operation(summary = "移动文档到知识库", description = "body: {kbId}——传空表示移回默认库（即 kb_id 置空）；"
            + "前后两库向量模型不同时自动异步迁移该文档向量；需要对源、目标两个库都有管理权")
    @PutMapping("/doc/{docId}")
    public ResultJson moveDoc(@PathVariable String docId, @RequestBody Map<String, Object> body) {
        Object kbId = body.get("kbId");
        String toKbId = kbId == null ? null : String.valueOf(kbId);
        // 记录迁移前归属（含历史文档 kb_id 为空=默认库），移动后按前后两库的向量模型判断是否迁移向量
        com.wisesoft.ai.model.AiDocument before = documentService.getDoc(docId);
        if (before == null) return ResultJson.error("文档不存在");
        String fromKbId = before.getKbId();
        if (!admin()) {
            // 文档本身也要有管理权（创建者或共享 manage 命中），防止拿别人的文档当搬运工
            if (!visibility.canManage(principal(), before.getShareConfig(), before.getCreatedBy(),
                    ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
                throw new BizException("仅可管理自己上传或被授权管理的文档");
            }
            // 源库/目标库都要有管理权（kbId 为空=默认库语义，同样按其库配置判定）
            requireManage(fromKbId == null ? null : kbService.get(fromKbId));
            String defId = kbService.defaultId();
            requireManage(toKbId == null ? (defId == null ? null : kbService.get(defId)) : kbService.get(toKbId));
        }
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
