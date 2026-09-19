package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.KnowledgeResponseSerializer;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseDetail;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseSummary;
import com.wisesoft.wenqu.knowledge.graphs.MilvusGraphService;
import com.wisesoft.wenqu.permissions.KnowledgePermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱路由，逐端点对齐参考实现 {@code server/routers/graph_router.py}。
 *
 * <p>权限与错误语义照搬：列表端点需管理员（{@code get_admin_user}）；子图/标签/统计端点
 * 走知识库读权限（{@code require_knowledge_base_read} = 管理员 + 知识库 READ）；
 * 知识库不存在 404 {@code Knowledge base not found}；非 Milvus 类型 404
 * {@code Graph API only supports Milvus knowledge bases}。
 *
 * <p>平台差异（必要替换）：参考实现按请求构造 {@code MilvusGraphService(kb_id=kb_id)}，
 * Java 侧复用容器内单例 {@link MilvusGraphService} 并把 kbId 作为方法入参（该服务两种构造形式
 * 都已提供，语义一致）。
 */
@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
@Tag(name = "graph", description = "图谱查询与管理")
public class GraphController {

    private final MilvusGraphService milvusGraphService;
    private final KnowledgeBaseManager knowledgeBaseManager;

    /** 取图谱服务的前置校验（对应 _get_graph_service）。 */
    private String requireMilvusKnowledgeBase(String kbId) {
        KnowledgeBaseDetail dbInfo = knowledgeBaseManager.getDatabaseInfo(kbId, false);
        if (dbInfo == null) {
            throw new ApiHttpException(404, "Knowledge base not found");
        }
        String kbType = dbInfo.kbType() == null ? "" : dbInfo.kbType().toLowerCase();
        if (!"milvus".equals(kbType)) {
            throw new ApiHttpException(404, "Graph API only supports Milvus knowledge bases");
        }
        return kbId;
    }

    @Operation(summary = "图谱知识库列表", description = "支持图谱能力的 Milvus 知识库列表（管理员权限）")
    @GetMapping("/list")
    public Map<String, Object> getGraphs() {
        String uid = AuthGuards.requireAdmin();
        try {
            List<Map<String, Object>> graphs = new ArrayList<>();
            for (KnowledgeBaseSummary db : knowledgeBaseManager.getDatabasesByUid(uid)) {
                String kbType = db.kbType() == null ? "" : db.kbType().toLowerCase();
                if (!"milvus".equals(kbType)) {
                    continue;
                }
                Map<String, Object> serialized = KnowledgeResponseSerializer.serializeKnowledgeBase(db);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", db.kbId());
                item.put("name", db.name());
                item.put("type", "milvus");
                item.put("description", db.description());
                item.put("status", "已连接");
                item.put("created_at", serialized.get("created_at"));
                item.put("metadata", serialized);
                graphs.add(item);
            }
            return success(graphs);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to list graphs: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "Failed to list graphs: " + exc.getMessage());
        }
    }

    @Operation(summary = "图谱子图查询", description = "查询 Milvus 知识库图谱子图（需知识库读权限）")
    @GetMapping("/subgraph")
    public Map<String, Object> getSubgraph(
            @Parameter(description = "Milvus 知识库ID") @RequestParam("kb_id") String kbId,
            @Parameter(description = "节点标签或查询关键词") @RequestParam(value = "node_label", defaultValue = "*") String nodeLabel,
            @Parameter(description = "最大深度 1..5") @RequestParam(value = "max_depth", defaultValue = "2") int maxDepth,
            @Parameter(description = "最大节点数 1..1000") @RequestParam(value = "max_nodes", defaultValue = "100") int maxNodes,
            @Parameter(description = "是否排除 Chunk 节点") @RequestParam(value = "exclude_chunk", defaultValue = "false") boolean excludeChunk) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            log.info("Querying subgraph - kb_id: {}, label: {}", kbId, nodeLabel);
            String boundKbId = requireMilvusKnowledgeBase(kbId);
            Map<String, Object> resultData = milvusGraphService.queryNodes(
                    boundKbId,
                    nodeLabel,
                    Math.min(5, Math.max(1, maxDepth)),
                    Math.min(1000, Math.max(1, maxNodes)),
                    excludeChunk);
            return success(resultData);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to get subgraph: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "Failed to get subgraph: " + exc.getMessage());
        }
    }

    @Operation(summary = "图谱标签列表", description = "获取 Milvus 知识库图谱的所有标签（需知识库读权限）")
    @GetMapping("/labels")
    public Map<String, Object> getGraphLabels(
            @Parameter(description = "Milvus 知识库ID") @RequestParam("kb_id") String kbId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            String boundKbId = requireMilvusKnowledgeBase(kbId);
            List<String> labels = milvusGraphService.getLabels(boundKbId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("labels", labels);
            return success(data);
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to get labels: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "Failed to get labels: " + exc.getMessage());
        }
    }

    @Operation(summary = "图谱统计", description = "获取 Milvus 知识库图谱统计信息（需知识库读权限）")
    @GetMapping("/stats")
    public Map<String, Object> getGraphStats(
            @Parameter(description = "Milvus 知识库ID") @RequestParam("kb_id") String kbId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            String boundKbId = requireMilvusKnowledgeBase(kbId);
            return success(milvusGraphService.getStats(boundKbId));
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("Failed to get stats: {}", exc.getMessage(), exc);
            throw new ApiHttpException(500, "Failed to get stats: " + exc.getMessage());
        }
    }

    /** 统一 {"success": true, "data": ...} 外壳（与参考实现一致）。 */
    private static Map<String, Object> success(Object data) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("data", data);
        return response;
    }
}
