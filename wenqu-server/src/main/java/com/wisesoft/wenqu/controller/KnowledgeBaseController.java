package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.common.KnowledgeResponseSerializer;
import com.wisesoft.wenqu.common.RequestUser;
import com.wisesoft.wenqu.common.UrlQuote;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.config.OptionsService;
import com.wisesoft.wenqu.knowledge.KbUtils;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseDetail;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseException;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.knowledge.KnowledgeContentService;
import com.wisesoft.wenqu.knowledge.KnowledgeUrlFetcher;
import com.wisesoft.wenqu.knowledge.ParserCapabilities;
import com.wisesoft.wenqu.knowledge.graphs.MilvusGraphService;
import com.wisesoft.wenqu.permissions.KnowledgePermissions;
import com.wisesoft.wenqu.permissions.ResourcePermission;
import com.wisesoft.wenqu.service.ChunkPresets;
import com.wisesoft.wenqu.service.KnowledgeFolderService;
import com.wisesoft.wenqu.service.KnowledgeRouteSupport;
import com.wisesoft.wenqu.service.ModelSelectors;
import com.wisesoft.wenqu.service.OcrService;
import com.wisesoft.wenqu.service.TaskService;
import com.wisesoft.wenqu.service.WorkspaceService;
import com.wisesoft.wenqu.storage.MinioStorageClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 知识库接口，逐端点对齐参考实现 {@code server/routers/knowledge_router.py}
 * （路由器前缀 {@code /knowledge}，聚合后对外为 {@code /api/knowledge}）。
 *
 * <h3>56 个端点</h3>
 * <pre>
 * 知识库管理
 *   GET    /databases                                     列表（{"databases":[...]}）
 *   POST   /databases                                     新建
 *   GET    /databases/accessible                          可访问列表（供智能体配置）
 *   GET    /databases/{kb_id}                             详情（include_files 默认 false）
 *   PUT    /databases/{kb_id}                             编辑（additional_params 为**合并**语义）
 *   DELETE /databases/{kb_id}                             删除
 *   POST   /databases/{kb_id}/stats/repair                修复历史统计
 *   GET    /databases/{kb_id}/export                      导出（csv/xlsx/md/txt）
 * 思维导图
 *   GET    /mindmap/databases                             概览（供导图界面选库）
 *   GET    /databases/{kb_id}/mindmap/files               候选文件
 *   POST   /databases/{kb_id}/mindmap/generate            生成/增量更新
 *   GET    /databases/{kb_id}/mindmap                     读取导图
 *   GET    /databases/{kb_id}/mindmap/diff                变更差异
 * 图谱构建
 *   GET    /databases/{kb_id}/graph-build/status
 *   POST   /databases/{kb_id}/graph-build/config
 *   POST   /databases/{kb_id}/graph-build/index
 *   GET    /databases/{kb_id}/graph-build/failed-chunks
 *   POST   /databases/{kb_id}/graph-build/reset
 *   POST   /databases/{kb_id}/graph-build/reconcile
 * 文档管理
 *   GET    /databases/{kb_id}/documents                   分页列表
 *   GET    /databases/{kb_id}/documents/search            按文件名搜索
 *   GET    /databases/{kb_id}/documents/exists            存在性检查
 *   POST   /databases/{kb_id}/documents                   上传→解析→可选入库（异步任务）
 *   POST   /databases/{kb_id}/documents/add               仅登记已上传文件
 *   POST   /databases/{kb_id}/documents/parse             指定文件解析
 *   POST   /databases/{kb_id}/documents/parse-pending     全量待解析
 *   POST   /databases/{kb_id}/documents/index             指定文件入库
 *   POST   /databases/{kb_id}/documents/index-pending     全量待入库
 *   GET    /databases/{kb_id}/documents/{doc_id}          详情（元数据 + 内容）
 *   GET    /databases/{kb_id}/documents/{doc_id}/basic    仅元数据
 *   GET    /databases/{kb_id}/documents/{doc_id}/content  解析内容与分块
 *   GET    /databases/{kb_id}/documents/{doc_id}/download 下载原始文件
 *   DELETE /databases/{kb_id}/documents/batch             批量删除
 *   DELETE /databases/{kb_id}/documents/{doc_id}          单个删除
 *   GET    /databases/{kb_id}/images/{object_path}        图片鉴权代理
 * 文件夹与虚拟文件夹
 *   POST   /databases/{kb_id}/folders                     建文件夹
 *   PUT    /databases/{kb_id}/folders/{folder_id}/rename  重命名
 *   PUT    /databases/{kb_id}/documents/{doc_id}/move     移动
 *   GET    /databases/{kb_id}/virtual-folders/detect      检测历史路径型目录
 *   POST   /databases/{kb_id}/virtual-folders/migrate     提交迁移任务
 *   GET    /databases/{kb_id}/virtual-folders/migrations/{task_id}/events  SSE 进度
 * 检索
 *   POST   /databases/{kb_id}/query                       检索
 *   POST   /databases/{kb_id}/query-test                  检索测试
 *   GET    /databases/{kb_id}/query-params                读库级检索参数
 *   PUT    /databases/{kb_id}/query-params                写库级检索参数
 * 示例问题
 *   POST   /databases/{kb_id}/sample-questions            生成
 *   GET    /databases/{kb_id}/sample-questions            读取
 * 文件与类型
 *   POST   /files/fetch-url                               抓取 URL 入库暂存区
 *   POST   /files/import-workspace                        从工作区导入
 *   POST   /files/upload                                  上传
 *   GET    /files/supported-types                         支持的文件类型
 *   POST   /files/markdown                                解析为 markdown
 *   GET    /types                                         支持的知识库类型
 *   GET    /chunk-presets                                 分块预设
 *   GET    /stats                                         统计
 *   POST   /generate-description                          AI 生成/优化描述
 * </pre>
 *
 * <p>成功响应体为参考实现的原始 JSON（<b>非</b>本产品既有 {@code ResultJson} 契约）；
 * 失败响应体由 {@link ApiHttpException} → {@code {"detail": ...}}。
 *
 * <h3>平台差异（必要替换）</h3>
 * <ul>
 *   <li>{@code Depends(get_admin_user|get_required_user|require_knowledge_base_read|manage)} →
 *       {@link AuthGuards#requireAdmin()} / {@link AuthGuards#requireUser()} /
 *       {@link KnowledgePermissions#requireKnowledgeBaseRead} / {@link KnowledgePermissions#requireKnowledgeBaseManage}。</li>
 *   <li>参考实现单文件里 9 个模块级函数与 5 个常量 → {@link KnowledgeRouteSupport}
 *       （多控制器共享，避免各写一份导致错误码分叉）。</li>
 *   <li>{@code select_model(...)|[]} 取 {@code default_model} → {@link OptionsService#SYSTEM_OPTIONS}
 *       同名键 + {@link ModelSelectors#selectModel}。</li>
 *   <li>pydantic 请求模型校验 → 方法内显式校验并抛 422（{@code loc=["body",字段]}，
 *       与 {@code SkillController} / {@code KnowledgeEvalController} 既有标注一致）。</li>
 * </ul>
 *
 * <h3>能力差异（已标注，不新增/不删除端点）</h3>
 * <ul>
 *   <li>{@code agent_manager.reload_all()}（新建/删除知识库后刷新智能体工具）——本工程
 *       {@code agents/buildin} 的智能体管理器尚未移植（清单 §三），故两处调用省略；
 *       本工程工具解析是运行期按需完成，无陈旧缓存问题。待 §三 落地后需回填。</li>
 *   <li>{@code FileResponse} → {@link ResponseEntity} 携带同款 {@code content-disposition}
 *       （非 ASCII 文件名走 RFC 5987，与 starlette 一致）。</li>
 *   <li>{@code StreamingResponse(media_type="text/event-stream")} → {@link StreamingResponseBody}
 *       手写 {@code data: {json}\n\n} 帧，逐字节对齐参考实现的帧格式。</li>
 *   <li>{@code export_data} 在参考实现里是基线类空实现（{@code pass}，Milvus 未覆写）；
 *       本工程 {@code KnowledgeBaseRuntime.exportData} 抛 {@link UnsupportedOperationException}，
 *       由本控制器按参考实现声明的 {@code except NotImplementedError} 分支映射为 <b>501</b>。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Tag(name = "knowledge", description = "知识库（文档、检索、思维导图、图谱构建）")
public class KnowledgeBaseController {

    /** 参考实现 {@code Query(ge=1, le=500)} 的每页上限。 */
    private static final int PAGE_SIZE_MAX = 500;

    /** {@code search_documents} 的 {@code limit: Query(100, ge=1, le=500)}。 */
    private static final int SEARCH_LIMIT_MAX = 500;

    /** {@code generate-description} 的 {@code file_list} 展示上限。 */
    private static final int DESCRIPTION_FILE_LIMIT = 50;

    /** {@code documents/{doc_id}/content} 需剔除的图谱内部字段。 */
    private static final Set<String> INTERNAL_GRAPH_FIELDS =
            Set.of("ent_id", "ent_ids", "extraction_result");

    private final KnowledgeRouteSupport support;
    private final KnowledgeContentService contentService;
    private final KnowledgeFolderService knowledgeFolderService;
    private final WorkspaceService workspaceService;
    private final OcrService ocrService;
    private final OptionsService optionsService;
    private final ModelSelectors modelSelectors;

    private KnowledgeBaseManager manager() {
        return support.knowledgeBase();
    }

    // =========================================================================
    // === 知识库管理分组 ===
    // =========================================================================

    /** 获取所有知识库（根据用户权限过滤）。 */
    @Operation(summary = "知识库列表", description = "响应 {\"databases\":[...]}")
    @GetMapping("/databases")
    public Object getDatabases() {
        String uid = AuthGuards.requireAdmin();
        try {
            return KnowledgeResponseSerializer.serializeKnowledgeBaseList(
                    manager().getDatabasesByUid(uid));
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取数据库列表失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取数据库列表失败");
        }
    }

    /** 创建知识库。 */
    @Operation(summary = "新建知识库", description = "body: database_name/description/kb_type/"
            + "embedding_model_spec/additional_params/llm_model_spec/share_config")
    @PostMapping("/databases")
    public Object createDatabase(@RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        String uid = AuthGuards.requireAdmin();
        String databaseName = requireString(body, "database_name");
        String description = requireString(body, "description");
        String embeddingModelSpec = optionalString(body, "embedding_model_spec", null);
        String kbType = optionalString(body, "kb_type", "milvus");
        Map<String, Object> additionalParams = optionalStringKeyedMap(body, "additional_params");
        String llmModelSpec = optionalString(body, "llm_model_spec", null);
        Map<String, Object> shareConfig = optionalStringKeyedMap(body, "share_config");

        log.debug("Create database {} with kb_type {}, additional_params {}, llm_model_spec {}, "
                        + "embedding_model_spec {}, share_config {}",
                databaseName, kbType, additionalParams, llmModelSpec, embeddingModelSpec, shareConfig);
        try {
            KnowledgeBaseDetail databaseInfo = manager().createDatabase(
                    databaseName, description, kbType, embeddingModelSpec, llmModelSpec,
                    shareConfig, uid, departmentIdOrNull(), additionalParams);

            // 参考实现在此调用 agent_manager.reload_all()（工具刷新后重载智能体）；
            // 本工程智能体管理器尚未移植（清单 §三），工具解析为运行期按需完成，无陈旧缓存。
            Map<String, Object> response = KnowledgeResponseSerializer.serializeKnowledgeBase(
                    databaseInfo, null, false, false);
            response.put("files", new LinkedHashMap<>());
            return response;
        } catch (KnowledgeBaseException.KBNameConflictError conflict) {
            throw new ApiHttpException(409, conflict.getMessage());
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("创建数据库失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(400, "创建数据库失败: " + exception.getMessage());
        }
    }

    /** 获取当前用户有权访问的知识库列表（用于智能体配置）。 */
    @Operation(summary = "可访问的知识库", description = "响应 {\"databases\":[...]}")
    @GetMapping("/databases/accessible")
    public Object getAccessibleDatabases() {
        String uid = AuthGuards.requireUser();
        try {
            List<Map<String, Object>> accessible = new ArrayList<>();
            for (var database : manager().getDatabasesByUid(uid)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", database.name());
                item.put("kb_id", database.kbId());
                item.put("description", database.description() == null ? "" : database.description());
                item.put("created_by", database.createdBy());
                item.put("kb_type", database.kbType());
                item.put("supports_documents", manager().databaseTypeSupportsDocuments(database.kbType()));
                accessible.add(item);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("databases", accessible);
            return body;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取可访问知识库列表失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取可访问知识库列表失败");
        }
    }

    /** 获取知识库详细信息。 */
    @Operation(summary = "知识库详情", description = "include_files 默认 false，避免大知识库响应过大")
    @GetMapping("/databases/{kbId}")
    public Object getDatabaseInfo(
            @PathVariable String kbId,
            @RequestParam(name = "include_files", defaultValue = "false") boolean includeFiles) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        KnowledgeBaseDetail database = manager().getDatabaseInfo(kbId, includeFiles);
        if (database == null) {
            throw new ApiHttpException(404, "Database not found");
        }
        ResourcePermission permission = database.effectivePermission();
        return KnowledgeResponseSerializer.serializeKnowledgeBase(
                database, permission, permission != ResourcePermission.MANAGE, false);
    }

    /** 修复知识库历史文件缺失的 Chunk/Token 统计。 */
    @Operation(summary = "修复知识库统计")
    @PostMapping("/databases/{kbId}/stats/repair")
    public Object repairDatabaseStats(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "统计修复");
        try {
            return manager().repairMissingFileStats(kbId);
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("修复知识库统计失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "修复知识库统计失败: " + exception.getMessage());
        }
    }

    /** 更新知识库信息。 */
    @Operation(summary = "编辑知识库", description = "additional_params 为**合并**语义；"
            + "llm_model_spec 仅在 body 出现该键时更新")
    @PutMapping("/databases/{kbId}")
    public Object updateDatabaseInfo(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        String name = requireString(body, "name");
        String description = requireString(body, "description");
        boolean updateLlmModelSpec = body.containsKey("llm_model_spec");
        String llmModelSpec = optionalString(body, "llm_model_spec", null);
        Map<String, Object> additionalParams = optionalStringKeyedMap(body, "additional_params");
        Map<String, Object> shareConfig = optionalStringKeyedMap(body, "share_config");

        log.debug("[update_database_info] 接收到的参数: name={}, llm_model_spec={}, "
                        + "additional_params={}, share_config={}",
                name, llmModelSpec, additionalParams, shareConfig);
        try {
            KnowledgeBaseDetail database = manager().updateDatabase(
                    kbId, name, description, llmModelSpec, updateLlmModelSpec,
                    additionalParams, shareConfig, uid, departmentIdOrNull());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "更新成功");
            result.put("database", KnowledgeResponseSerializer.serializeKnowledgeBase(
                    database, null, false, false));
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("更新数据库失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(400, "更新数据库失败: " + exception.getMessage());
        }
    }

    /** 删除知识库。 */
    @Operation(summary = "删除知识库")
    @DeleteMapping("/databases/{kbId}")
    public Object deleteDatabase(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        log.debug("Delete database {}", kbId);
        try {
            manager().deleteDatabase(kbId);
            // 参考实现在此调用 agent_manager.reload_all()，同 createDatabase 的说明。
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "删除成功");
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("删除数据库失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(400, "删除数据库失败: " + exception.getMessage());
        }
    }

    /** 导出知识库数据。 */
    @Operation(summary = "导出知识库", description = "format ∈ {csv,xlsx,md,txt}")
    @GetMapping("/databases/{kbId}/export")
    public ResponseEntity<byte[]> exportDatabase(
            @PathVariable String kbId,
            @RequestParam(name = "format", defaultValue = "csv") String format,
            @RequestParam(name = "include_vectors", defaultValue = "false") boolean includeVectors) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        log.debug("Exporting database {} with format {}", kbId, format);
        try {
            String filePath = manager().exportData(kbId, format);
            Path path = filePath == null ? null : Path.of(filePath);
            if (path == null || !Files.exists(path)) {
                throw new ApiHttpException(404, "Exported file not found.");
            }
            String filename = path.getFileName() == null ? "" : path.getFileName().toString();
            String mediaType = KnowledgeRouteSupport.mediaTypeFor("." + format);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachmentHeader(filename))
                    .contentType(MediaType.parseMediaType(mediaType))
                    .body(Files.readAllBytes(path));
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (UnsupportedOperationException notImplemented) {
            // 参考实现在此分支返回 501（except NotImplementedError）
            log.warn("A disabled feature was accessed: {}", notImplemented.getMessage());
            throw new ApiHttpException(501, notImplemented.getMessage());
        } catch (Exception exception) {
            log.error("导出数据库失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "导出数据库失败: " + exception.getMessage());
        }
    }

    // =========================================================================
    // === 思维导图分组 ===
    // =========================================================================

    /** 获取所有知识库的概览信息，用于思维导图界面选择。 */
    @Operation(summary = "思维导图用知识库概览")
    @GetMapping("/mindmap/databases")
    public Object getMindmapDatabases() {
        String uid = AuthGuards.requireAdmin();
        try {
            return contentService.getMindmapDatabasesOverview(uid);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取知识库列表失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取知识库列表失败: " + exception.getMessage());
        }
    }

    /** 获取指定知识库的所有文件列表。 */
    @Operation(summary = "导图候选文件")
    @GetMapping("/databases/{kbId}/mindmap/files")
    public Object getDatabaseMindmapFiles(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            return contentService.getMindmapDatabaseFiles(kbId);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取文件列表失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取文件列表失败: " + exception.getMessage());
        }
    }

    /** 使用 AI 分析知识库文件，生成思维导图结构。支持增量更新模式。 */
    @Operation(summary = "生成思维导图", description = "body: {file_ids, user_prompt, incremental}")
    @PostMapping("/databases/{kbId}/mindmap/generate")
    public Object generateMindmap(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        List<String> fileIds = body.get("file_ids") == null
                ? null : KnowledgeRouteSupport.asStringList(body.get("file_ids"));
        String userPrompt = optionalString(body, "user_prompt", "");
        boolean incremental = optionalBoolean(body, "incremental", false);
        try {
            return contentService.generateDatabaseMindmap(kbId, fileIds, userPrompt, incremental);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("生成思维导图失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "生成思维导图失败: " + exception.getMessage());
        }
    }

    /** 获取知识库关联的思维导图。 */
    @Operation(summary = "读取思维导图")
    @GetMapping("/databases/{kbId}/mindmap")
    public Object getDatabaseMindmap(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            return contentService.getDatabaseMindmapData(kbId);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取知识库思维导图失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取知识库思维导图失败: " + exception.getMessage());
        }
    }

    /** 检测思维导图与知识库文件的变更差异。 */
    @Operation(summary = "思维导图变更差异")
    @GetMapping("/databases/{kbId}/mindmap/diff")
    public Object getMindmapDiff(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            return contentService.getMindmapDiff(kbId);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("检测思维导图变更失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "检测思维导图变更失败: " + exception.getMessage());
        }
    }

    // =========================================================================
    // === 图谱构建分组 ===
    // =========================================================================

    /** 图谱构建状态。 */
    @Operation(summary = "图谱构建状态")
    @GetMapping("/databases/{kbId}/graph-build/status")
    public Object getGraphBuildStatus(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            return support.graphService().getStatus(kbId, support.tasker());
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取图谱构建状态失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取图谱构建状态失败: " + exception.getMessage());
        }
    }

    /** 配置（锁定）图谱抽取器。 */
    @Operation(summary = "配置图谱构建", description = "body: {extractor_type, extractor_options}")
    @PostMapping("/databases/{kbId}/graph-build/config")
    public Object configureGraphBuild(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        try {
            Map<String, Object> config = support.graphService().configure(
                    kbId,
                    body.get("extractor_type") == null ? null : String.valueOf(body.get("extractor_type")),
                    KnowledgeRouteSupport.asStringKeyedMap(body.get("extractor_options")),
                    uid);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "图谱抽取配置已锁定");
            result.put("status", "success");
            result.put("config", config);
            return result;
        } catch (IllegalArgumentException illegalArgument) {
            int status = String.valueOf(illegalArgument.getMessage()).contains("已锁定") ? 409 : 400;
            throw new ApiHttpException(status, illegalArgument.getMessage());
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("配置图谱构建失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "配置图谱构建失败: " + exception.getMessage());
        }
    }

    /** 提交图谱构建任务。 */
    @Operation(summary = "提交图谱构建任务")
    @PostMapping("/databases/{kbId}/graph-build/index")
    public Object indexGraphBuild(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        try {
            if (support.hasRunningGraphBuildTask(kbId)) {
                throw new ApiHttpException(409, "该知识库已有正在运行的图谱构建任务");
            }

            KnowledgeBaseDetail database = manager().getDatabaseInfo(kbId, false);
            if (database == null) {
                throw new ApiHttpException(404, "知识库 " + kbId + " 不存在");
            }

            Map<String, Object> graphStatus = support.graphService().getStatus(kbId, null);
            if (!KnowledgeRouteSupport.truthy(graphStatus.get("locked"))) {
                throw new ApiHttpException(400, "请先确认并锁定图谱抽取配置");
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kb_id", kbId);
            payload.put("action", "build");
            TaskService.EnqueueResult enqueued = support.tasker().enqueueUniqueByPayload(
                    "图谱构建 (" + database.name() + ")",
                    MilvusGraphService.GRAPH_TASK_TYPE,
                    payload,
                    Map.of("kb_id", kbId),
                    null);
            if (!enqueued.created()) {
                throw new ApiHttpException(409, "该知识库已有正在运行的图谱构建任务");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "图谱构建任务已提交");
            result.put("status", "queued");
            result.put("task_id", enqueued.task().id);
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        } catch (Exception exception) {
            log.error("提交图谱构建任务失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "提交图谱构建任务失败: " + exception.getMessage());
        }
    }

    /** 图谱抽取失败 Chunk 样例。 */
    @Operation(summary = "图谱抽取失败样例", description = "limit 取 max(1, min(limit, 10))")
    @GetMapping("/databases/{kbId}/graph-build/failed-chunks")
    public Object getGraphBuildFailedChunks(
            @PathVariable String kbId, @RequestParam(name = "limit", defaultValue = "10") int limit) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            return support.graphService().getFailedChunkSamples(kbId, Math.max(1, Math.min(limit, 10)));
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取图谱抽取失败 Chunk 样例失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取图谱抽取失败 Chunk 样例失败: " + exception.getMessage());
        }
    }

    /** 重置图谱构建状态。 */
    @Operation(summary = "重置图谱构建状态",
            description = "body: {clear_extraction_result(默认 true), clear_config(默认 false)}")
    @PostMapping("/databases/{kbId}/graph-build/reset")
    public Object resetGraphBuild(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        Map<String, Object> data = body == null ? Map.of() : body;
        try {
            if (support.hasRunningGraphBuildTask(kbId)) {
                throw new ApiHttpException(409, "该知识库存在正在运行的图谱构建任务，无法重置");
            }
            return support.graphService().reset(
                    kbId,
                    optionalBoolean(data, "clear_extraction_result", true),
                    optionalBoolean(data, "clear_config", false));
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        } catch (Exception exception) {
            log.error("重置图谱构建状态失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "重置图谱构建状态失败: " + exception.getMessage());
        }
    }

    /** 图谱向量索引修复。 */
    @Operation(summary = "图谱向量索引修复", description = "body: {mode: failed|all_vectors}")
    @PostMapping("/databases/{kbId}/graph-build/reconcile")
    public Object reconcileGraphBuild(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        Map<String, Object> data = body == null ? Map.of() : body;
        String mode = data.get("mode") == null ? "failed" : String.valueOf(data.get("mode"));
        if (!Set.of("failed", "all_vectors").contains(mode)) {
            throw new ApiHttpException(400, "mode 必须是 failed 或 all_vectors");
        }
        try {
            if (support.hasRunningGraphBuildTask(kbId)) {
                throw new ApiHttpException(409, "该知识库已有正在运行的图谱构建任务");
            }

            KnowledgeBaseDetail database = manager().getDatabaseInfo(kbId, false);
            if (database == null) {
                throw new ApiHttpException(404, "知识库 " + kbId + " 不存在");
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kb_id", kbId);
            payload.put("action", "reconcile");
            payload.put("reconcile_mode", mode);
            TaskService.EnqueueResult enqueued = support.tasker().enqueueUniqueByPayload(
                    "图谱向量索引修复 (" + database.name() + ")",
                    MilvusGraphService.GRAPH_TASK_TYPE,
                    payload,
                    Map.of("kb_id", kbId),
                    null);
            if (!enqueued.created()) {
                throw new ApiHttpException(409, "该知识库已有正在运行的图谱构建任务");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "图谱向量索引修复任务已提交");
            result.put("status", "queued");
            result.put("task_id", enqueued.task().id);
            result.put("mode", mode);
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        } catch (Exception exception) {
            log.error("提交图谱向量索引修复任务失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "提交图谱向量索引修复任务失败: " + exception.getMessage());
        }
    }

    // =========================================================================
    // === 知识库文档管理分组 ===
    // =========================================================================

    /** 分页获取知识库文件列表。 */
    @Operation(summary = "文档列表", description = "page_size ∈ [1,500]")
    @GetMapping("/databases/{kbId}/documents")
    public Object listDocuments(
            @PathVariable String kbId,
            @RequestParam(name = "parent_id", required = false) String parentId,
            @RequestParam(name = "path_prefix", required = false) String pathPrefix,
            @RequestParam(name = "status", defaultValue = "all") String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "100") int pageSize,
            @RequestParam(name = "recursive", defaultValue = "false") boolean recursive) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档查看");
        requireRange(page, 1, Integer.MAX_VALUE, "page");
        requireRange(pageSize, 1, PAGE_SIZE_MAX, "page_size");
        try {
            return manager().listDocumentFiles(
                    kbId, parentId, pathPrefix, status, page, pageSize, recursive, false, true);
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        }
    }

    /** 按文件名搜索知识库文件（仅匹配文件名，不搜索文件内容）。 */
    @Operation(summary = "按文件名搜索文档")
    @GetMapping("/databases/{kbId}/documents/search")
    public Object searchDocuments(
            @PathVariable String kbId,
            @RequestParam(name = "query", defaultValue = "") String query,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        requireRange(offset, 0, Integer.MAX_VALUE, "offset");
        requireRange(limit, 1, SEARCH_LIMIT_MAX, "limit");
        KnowledgeBaseDetail database = manager().getDatabaseInfo(kbId, false);
        if (database == null) {
            throw new ApiHttpException(404, "知识库 " + kbId + " 不存在或无权访问");
        }
        if (!manager().databaseTypeSupportsDocuments(database.kbType())) {
            String kbType = database.kbType() == null ? "" : database.kbType().toLowerCase();
            throw new ApiHttpException(400, (database.name() == null || database.name().isEmpty()
                    ? kbType : database.name()) + " 只支持检索，不支持文档搜索");
        }
        String normalizedQuery = query == null ? "" : query.strip();
        if (normalizedQuery.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("files", new ArrayList<>());
            empty.put("total", 0);
            empty.put("offset", 0);
            empty.put("limit", limit);
            empty.put("has_more", false);
            return empty;
        }
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("kb_id", database.kbId());
        scope.put("name", database.name());
        return manager().searchDocumentFiles(
                List.of(scope), normalizedQuery, offset, limit, null, false, true);
    }

    /** 检查知识库中是否已存在指定文件名或相对路径的文件。 */
    @Operation(summary = "文档存在性检查")
    @GetMapping("/databases/{kbId}/documents/exists")
    public Object documentFileExists(
            @PathVariable String kbId, @RequestParam(name = "filename") String filename) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档存在性检查");
        String normalizedFilename = filename == null ? "" : filename.strip();
        if (normalizedFilename.isEmpty()) {
            throw new ApiHttpException(400, "filename is required");
        }
        boolean exists;
        try {
            exists = manager().documentFileExists(kbId, normalizedFilename);
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kb_id", kbId);
        result.put("filename", normalizedFilename);
        result.put("exists", exists);
        return result;
    }

    /** 添加文档到知识库（上传 -> 解析 -> 可选入库）。 */
    @Operation(summary = "添加文档（异步任务）", description = "body: {items: [MinIO URL], params}")
    @PostMapping("/databases/{kbId}/documents")
    public Object addDocuments(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档添加/解析/入库");

        List<String> items = requireStringList(body, "items");
        Map<String, Object> params = requireStringKeyedMap(body, "params");
        String contentType = params.get("content_type") == null
                ? "file" : String.valueOf(params.get("content_type"));
        if ("url".equals(contentType)) {
            throw new ApiHttpException(400, "URL 处理方式已变更，请使用 fetch-url 接口先获取内容");
        }
        if (!"file".equals(contentType)) {
            throw new ApiHttpException(400, "Unsupported content_type: " + contentType);
        }
        support.validateUploadedDocumentItems(items, params);

        log.debug("Add documents for kb_id {}: {} params={}", kbId, items, params);
        try {
            KnowledgeBaseDetail database = manager().getDatabaseInfo(kbId, false);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kb_id", kbId);
            payload.put("items", items);
            payload.put("params", params);
            payload.put("content_type", contentType);
            payload.put("operator_id", uid);
            TaskService.Task task = support.tasker().enqueue(
                    "知识库文档处理 (" + (database == null ? "" : database.name()) + ")",
                    "knowledge_ingest",
                    payload,
                    null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "任务已提交，请在任务中心查看进度");
            result.put("status", "queued");
            result.put("task_id", task.id);
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("Failed to enqueue {}s: {}", contentType, exception.getMessage(), exception);
            throw new ApiHttpException(500, "Failed to enqueue task: " + exception.getMessage());
        }
    }

    /** 将已上传的 MinIO 文件同步添加为知识库文档记录，不解析、不入库。 */
    @Operation(summary = "登记已上传文件", description = "body: {items, params}")
    @PostMapping("/databases/{kbId}/documents/add")
    public Object addUploadedDocuments(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档添加");

        List<String> items = requireStringList(body, "items");
        Map<String, Object> params = optionalStringKeyedMap(body, "params");
        if (params == null) {
            params = new LinkedHashMap<>();
        }
        String contentType = params.get("content_type") == null
                ? "file" : String.valueOf(params.get("content_type"));
        if ("url".equals(contentType)) {
            throw new ApiHttpException(400, "URL 处理方式已变更，请使用 fetch-url 接口先获取内容");
        }
        if (!"file".equals(contentType)) {
            throw new ApiHttpException(400, "Unsupported content_type: " + contentType);
        }
        support.validateUploadedDocumentItems(items, params);

        log.debug("Add uploaded documents for kb_id {}: {} params={}", kbId, items, params);
        List<Map<String, Object>> addedItems = new ArrayList<>();
        List<Map<String, Object>> failedItems = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            String item = items.get(index);
            try {
                Map<String, Object> fileMeta = manager().addFileRecord(
                        kbId, item, KbUtils.paramsForUploadedDocument(item, params), uid);
                Map<String, Object> added = new LinkedHashMap<>();
                added.put("index", index);
                added.put("item", item);
                added.put("file_id", fileMeta.get("file_id"));
                added.put("status", fileMeta.get("status"));
                added.put("file_meta", fileMeta);
                addedItems.add(added);
            } catch (Exception addError) {
                log.error("添加文件记录失败 {}: {}", item, addError.getMessage());
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("index", index);
                failed.put("item", item);
                failed.put("status", "failed");
                failed.put("error", "添加记录失败: " + addError.getMessage());
                failed.put("error_type", "add_failed");
                failedItems.add(failed);
            }
        }

        int failedCount = failedItems.size();
        int addedCount = addedItems.size();
        String status;
        String message;
        if (failedCount == 0) {
            status = "success";
            message = "已添加 " + addedCount + " 个文件";
        } else if (addedCount == 0) {
            status = "failed";
            message = "文件添加失败，失败 " + failedCount + " 个";
        } else {
            status = "partial_failed";
            message = "已添加 " + addedCount + " 个文件，失败 " + failedCount + " 个";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("status", status);
        result.put("items", addedItems);
        result.put("failed_items", failedItems);
        result.put("added", addedCount);
        result.put("failed", failedCount);
        return result;
    }

    /** 手动触发文档解析（body 可为 {@code {file_ids, params}} 或裸 {@code [file_id]} 数组）。 */
    @Operation(summary = "指定文件解析")
    @PostMapping("/databases/{kbId}/documents/parse")
    public Object parseDocuments(
            @PathVariable String kbId, @RequestBody(required = false) Object rawBody) {
        requireBody(rawBody);
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);

        List<String> fileIds;
        Map<String, Object> params;
        if (rawBody instanceof List<?>) {
            fileIds = KnowledgeRouteSupport.asStringList(rawBody);
            params = null;
        } else if (rawBody instanceof Map<?, ?> rawMap) {
            Map<String, Object> body = KnowledgeRouteSupport.asStringKeyedMap(rawMap);
            fileIds = body.get("file_ids") == null
                    ? new ArrayList<>() : KnowledgeRouteSupport.asStringList(body.get("file_ids"));
            params = optionalStringKeyedMap(body, "params");
        } else {
            throw validationError("body", "file_ids", "Input should be a valid list");
        }
        fileIds = support.validateDirectDocumentActionFileIds(fileIds);
        log.debug("Parse documents for kb_id {}: {} params={}", kbId, fileIds, params);
        KnowledgeBaseDetail dbInfo = support.ensureDatabaseSupportsDocuments(kbId, "文档解析");
        return support.enqueueDocumentActionTask(
                kbId, fileIds, params == null ? new LinkedHashMap<>() : params, uid, dbInfo, "parse");
    }

    /** 按状态手动触发全部待解析文档解析。 */
    @Operation(summary = "全量待解析", description = "body: {params}")
    @PostMapping("/databases/{kbId}/documents/parse-pending")
    public Object parsePendingDocuments(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        Map<String, Object> params = optionalStringKeyedMap(body, "params");
        log.debug("Parse pending documents for kb_id {}: params={}", kbId, params);
        KnowledgeBaseDetail dbInfo = support.ensureDatabaseSupportsDocuments(kbId, "文档解析");
        return support.enqueuePendingDocumentActionTask(
                kbId, params == null ? new LinkedHashMap<>() : params, uid, dbInfo, "parse");
    }

    /** 手动触发文档入库（Indexing），支持更新参数。 */
    @Operation(summary = "指定文件入库", description = "body: {file_ids, params}")
    @PostMapping("/databases/{kbId}/documents/index")
    public Object indexDocuments(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        List<String> fileIds = requireStringList(body, "file_ids");
        Map<String, Object> params = optionalStringKeyedMap(body, "params");
        fileIds = support.validateDirectDocumentActionFileIds(fileIds);
        log.debug("Index documents for kb_id {}: {} params={}", kbId, fileIds, params);
        KnowledgeBaseDetail dbInfo = support.ensureDatabaseSupportsDocuments(kbId, "文档入库");
        return support.enqueueDocumentActionTask(
                kbId, fileIds, params == null ? new LinkedHashMap<>() : params, uid, dbInfo, "index");
    }

    /** 按状态手动触发全部待入库文档入库。 */
    @Operation(summary = "全量待入库", description = "body: {params}")
    @PostMapping("/databases/{kbId}/documents/index-pending")
    public Object indexPendingDocuments(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        Map<String, Object> params = optionalStringKeyedMap(body, "params");
        log.debug("Index pending documents for kb_id {}: params={}", kbId, params);
        KnowledgeBaseDetail dbInfo = support.ensureDatabaseSupportsDocuments(kbId, "文档入库");
        return support.enqueuePendingDocumentActionTask(
                kbId, params == null ? new LinkedHashMap<>() : params, uid, dbInfo, "index");
    }

    /** 获取文档详细信息（包含基本信息和内容信息）。 */
    @Operation(summary = "文档详情")
    @GetMapping("/databases/{kbId}/documents/{docId}")
    public Object getDocumentInfo(@PathVariable String kbId, @PathVariable String docId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档查看");
        log.debug("GET document {} info in {}", docId, kbId);
        try {
            return manager().getFileInfo(kbId, docId);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("Failed to get file info, {}", exception.getMessage(), exception);
            return failure("Failed to get file info");
        }
    }

    /** 获取文档基本信息（仅元数据）。 */
    @Operation(summary = "文档基本信息")
    @GetMapping("/databases/{kbId}/documents/{docId}/basic")
    public Object getDocumentBasicInfo(@PathVariable String kbId, @PathVariable String docId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档查看");
        log.debug("GET document {} basic info in {}", docId, kbId);
        try {
            return manager().getFileBasicInfo(kbId, docId);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("Failed to get file basic info, {}", exception.getMessage(), exception);
            return failure("Failed to get file basic info");
        }
    }

    /** 获取文档内容信息（chunks 和 lines）。 */
    @Operation(summary = "文档解析内容", description = "line 中剔除 ent_id/ent_ids/extraction_result")
    @GetMapping("/databases/{kbId}/documents/{docId}/content")
    public Object getDocumentContent(@PathVariable String kbId, @PathVariable String docId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档查看");
        log.debug("GET document {} content in {}", docId, kbId);
        try {
            Map<String, Object> info = manager().getFileContent(kbId, docId);
            if (info.get("lines") instanceof List<?> lines) {
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Object line : lines) {
                    if (line instanceof Map<?, ?> rawLine) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : rawLine.entrySet()) {
                            String key = String.valueOf(entry.getKey());
                            if (!INTERNAL_GRAPH_FIELDS.contains(key)) {
                                row.put(key, entry.getValue());
                            }
                        }
                        filtered.add(row);
                    }
                }
                info.put("lines", filtered);
            }
            return info;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("Failed to get file content, {}", exception.getMessage(), exception);
            return failure("Failed to get file content");
        }
    }

    /** 批量删除文档或文件夹。 */
    @Operation(summary = "批量删除文档", description = "body 为裸 file_id 数组")
    @DeleteMapping("/databases/{kbId}/documents/batch")
    public Object batchDeleteDocuments(
            @PathVariable String kbId, @RequestBody(required = false) List<String> fileIds) {
        requireBody(fileIds);
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "批量文档删除");
        log.debug("BATCH DELETE documents {} in {}", fileIds, kbId);

        int deletedCount = 0;
        List<Map<String, Object>> failedItems = new ArrayList<>();
        List<KnowledgeContentService.Removal> mindmapRemovals = new ArrayList<>();

        for (String docId : fileIds) {
            try {
                Map<String, Object> fileMetaInfo = manager().getFileBasicInfo(kbId, docId);
                Map<String, Object> meta = metaOf(fileMetaInfo);

                if (Boolean.TRUE.equals(meta.get("is_folder"))) {
                    manager().deleteFolder(kbId, docId);
                    deletedCount++;
                    continue;
                }

                String filePath = KnowledgeRouteSupport.text(meta.get("path"));
                support.deleteDocumentStorageObjects(kbId, docId, filePath);

                // 无论MinIO删除是否成功，都继续从知识库删除
                manager().deleteFile(kbId, docId);
                deletedCount++;

                // 只有成功删除的文件才同步从导图快照移除，避免部分失败导致导图与文件表失同步
                String removedFilename = KnowledgeRouteSupport.text(meta.get("filename"));
                if (!removedFilename.isEmpty()) {
                    mindmapRemovals.add(new KnowledgeContentService.Removal(docId, removedFilename));
                }
            } catch (Exception exception) {
                log.error("批量删除过程中删除文档 {} 失败: {}", docId, exception.getMessage(), exception);
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("doc_id", docId);
                failed.put("error", exception.getMessage());
                failedItems.add(failed);
            }
        }

        // 同步清理导图快照，移除已删除文件对应的叶子节点
        contentService.batchRemoveFilesFromMindmap(kbId, mindmapRemovals);

        if (!failedItems.isEmpty()) {
            if (deletedCount == 0) {
                throw new ApiHttpException(
                        400, "批量删除失败: 所有 " + failedItems.size() + " 个文件均未删除。");
            }
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put("message", "部分删除成功: 已删除 " + deletedCount + " 个文件，失败 "
                    + failedItems.size() + " 个");
            partial.put("deleted_count", deletedCount);
            partial.put("failed_items", failedItems);
            return partial;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "批量删除成功: 已删除 " + deletedCount + " 个文件");
        result.put("deleted_count", deletedCount);
        return result;
    }

    /** 删除文档或文件夹。 */
    @Operation(summary = "删除文档")
    @DeleteMapping("/databases/{kbId}/documents/{docId}")
    public Object deleteDocument(@PathVariable String kbId, @PathVariable String docId) {
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档删除");
        log.debug("DELETE document {} info in {}", docId, kbId);
        try {
            Map<String, Object> fileMetaInfo = manager().getFileBasicInfo(kbId, docId);
            Map<String, Object> meta = metaOf(fileMetaInfo);

            if (Boolean.TRUE.equals(meta.get("is_folder"))) {
                manager().deleteFolder(kbId, docId);
                Map<String, Object> folderResult = new LinkedHashMap<>();
                folderResult.put("message", "文件夹删除成功");
                return folderResult;
            }

            String filePath = KnowledgeRouteSupport.text(meta.get("path"));
            support.deleteDocumentStorageObjects(kbId, docId, filePath);

            // 无论MinIO删除是否成功，都继续从知识库删除
            manager().deleteFile(kbId, docId);

            // 同步清理导图快照，移除已删除文件对应的叶子节点
            String removedFilename = KnowledgeRouteSupport.text(meta.get("filename"));
            contentService.removeFileFromMindmap(kbId, docId, removedFilename);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "删除成功");
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("删除文档失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(400, "删除文档失败: " + exception.getMessage());
        }
    }

    /** 下载原始文件。 */
    @Operation(summary = "下载原始文件", description = "URL 类型文件不支持下载")
    @GetMapping("/databases/{kbId}/documents/{docId}/download")
    public ResponseEntity<byte[]> downloadDocument(
            @PathVariable String kbId, @PathVariable String docId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档下载");
        log.debug("Download document {} from {}", docId, kbId);
        try {
            Map<String, Object> fileInfo = manager().getFileBasicInfo(kbId, docId);
            Map<String, Object> fileMeta = metaOf(fileInfo);

            String fileType = fileMeta.get("file_type") == null
                    ? "file" : String.valueOf(fileMeta.get("file_type"));
            String filePath = KnowledgeRouteSupport.text(fileMeta.get("path"));
            String filename = fileMeta.get("filename") == null
                    ? "file" : String.valueOf(fileMeta.get("filename"));

            // URL 类型文件没有原始文件可下载
            if ("url".equals(fileType)) {
                throw new ApiHttpException(400, "URL 类型文件不支持下载原始文件");
            }
            log.debug("File path from database: {}", filePath);
            log.debug("Original filename from database: {}", filename);

            String decodedFilename;
            try {
                decodedFilename = KnowledgeRouteSupport.unquote(filename);
            } catch (Exception decodeError) {
                log.debug("Failed to decode filename {}: {}", filename, decodeError.getMessage());
                decodedFilename = filename;
            }

            String mediaType = KnowledgeRouteSupport.mediaTypeFor(
                    KnowledgeRouteSupport.splitExt(decodedFilename)[1]);

            if (!KbUtils.isMinioUrl(filePath)) {
                throw new ApiHttpException(400, "文件路径必须是 MinIO URL");
            }

            log.debug("Downloading from MinIO: {}", filePath);
            String[] parsed = KbUtils.parseMinioUrl(filePath);
            log.debug("Parsed bucket_name: {}, object_name: {}", parsed[0], parsed[1]);
            byte[] content = MinioStorageClient.getMinioClient().downloadFile(parsed[0], parsed[1]);
            log.debug("Successfully downloaded object: {}", parsed[1]);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachmentHeader(decodedFilename))
                    .contentType(MediaType.parseMediaType(mediaType))
                    .body(content);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("下载文件失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "下载失败: " + exception.getMessage());
        }
    }

    /** 经鉴权代理读取知识库图片（图片存放在私有 bucket，禁止匿名访问）。 */
    @Operation(summary = "知识库图片代理", description = "object_path 必须以 kb-images/ 开头")
    @GetMapping("/databases/{kbId}/images/{*objectPath}")
    public ResponseEntity<byte[]> getKbImage(
            @PathVariable String kbId, @PathVariable String objectPath) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        String normalizedPath = objectPath != null && objectPath.startsWith("/")
                ? objectPath.substring(1) : objectPath;
        if (normalizedPath == null || !normalizedPath.startsWith("kb-images/")) {
            throw new ApiHttpException(400, "非法的知识库图片路径");
        }
        if (normalizedPath.contains("..") || normalizedPath.contains("\\")) {
            throw new ApiHttpException(400, "非法的知识库图片路径");
        }

        String objectName = kbId + "/" + normalizedPath;
        byte[] content;
        try {
            content = MinioStorageClient.getMinioClient().downloadFile(
                    MinioStorageClient.KB_BUCKETS.get("images"), objectName);
        } catch (MinioStorageClient.StorageError storageError) {
            if (String.valueOf(storageError.getMessage()).contains("不存在")) {
                throw new ApiHttpException(404, "图片不存在");
            }
            log.error("读取知识库图片失败 {}: {}", objectName, storageError.getMessage());
            throw new ApiHttpException(500, "读取图片失败");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    // =========================================================================
    // === 知识库查询分组 ===
    // =========================================================================

    /** 查询知识库。 */
    @Operation(summary = "检索知识库", description = "body: {query, meta}")
    @PostMapping("/databases/{kbId}/query")
    public Object queryKnowledgeBase(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        String query = requireString(body, "query");
        Map<String, Object> meta = requireStringKeyedMap(body, "meta");
        log.debug("Query knowledge base {}: {}", kbId, query);
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result", manager().aquery(query, kbId, meta));
            result.put("status", "success");
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("知识库查询失败 {}", exception.getMessage(), exception);
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("message", "知识库查询失败: " + exception.getMessage());
            failed.put("status", "failed");
            return failed;
        }
    }

    /** 测试查询知识库。 */
    @Operation(summary = "检索测试", description = "body: {query, meta}，直接返回原始召回结果")
    @PostMapping("/databases/{kbId}/query-test")
    public Object queryTest(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        String query = requireString(body, "query");
        Map<String, Object> meta = requireStringKeyedMap(body, "meta");
        log.debug("Query test in {}: {}", kbId, query);
        try {
            return manager().aquery(query, kbId, meta);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("测试查询失败 {}", exception.getMessage(), exception);
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("message", "测试查询失败: " + exception.getMessage());
            failed.put("status", "failed");
            return failed;
        }
    }

    /** 更新知识库查询参数配置（合并语义）。 */
    @Operation(summary = "写检索参数", description = "body 直接为参数对象")
    @PutMapping("/databases/{kbId}/query-params")
    public Object updateKnowledgeBaseQueryParams(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        try {
            manager().updateKbQueryParams(kbId, body);
            log.info("更新知识库 {} 查询参数: {}", kbId, body);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", "success");
            result.put("data", body);
            return result;
        } catch (KnowledgeBaseException.KBNotFoundError notFound) {
            throw new ApiHttpException(404, notFound.getMessage());
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("更新知识库查询参数失败: {}", exception.getMessage());
            throw new ApiHttpException(500, "更新查询参数失败: " + exception.getMessage());
        }
    }

    /** 获取知识库类型特定的查询参数。 */
    @Operation(summary = "读检索参数", description = "响应 {\"params\":..., \"message\":\"success\"}")
    @GetMapping("/databases/{kbId}/query-params")
    public Object getKnowledgeBaseQueryParams(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("params", manager().getKbQueryParamsConfig(kbId));
            result.put("message", "success");
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取知识库查询参数失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, exception.getMessage());
        }
    }

    // =========================================================================
    // === AI 生成示例问题 ===
    // =========================================================================

    /** AI 生成针对知识库的测试问题。 */
    @Operation(summary = "生成示例问题", description = "body: {count(默认 10)}")
    @PostMapping("/databases/{kbId}/sample-questions")
    public Object generateSampleQuestions(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        Object rawCount = body.get("count");
        int count = rawCount == null ? 10 : toInt(rawCount, "count");
        try {
            return contentService.generateDatabaseSampleQuestions(kbId, count);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("生成知识库问题失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "生成问题失败: " + exception.getMessage());
        }
    }

    /** 获取知识库的测试问题。 */
    @Operation(summary = "读取示例问题")
    @GetMapping("/databases/{kbId}/sample-questions")
    public Object getSampleQuestions(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        try {
            return contentService.getDatabaseSampleQuestions(kbId);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取知识库问题失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取问题失败: " + exception.getMessage());
        }
    }

    // =========================================================================
    // === 文件管理分组 ===
    // =========================================================================

    /** 创建文件夹。 */
    @Operation(summary = "创建文件夹", description = "body: {folder_name, parent_id}")
    @PostMapping("/databases/{kbId}/folders")
    public Object createFolder(
            @PathVariable String kbId, @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        String folderName = requireString(body, "folder_name");
        String parentId = optionalString(body, "parent_id", null);
        try {
            support.ensureDatabaseSupportsDocuments(kbId, "文件夹创建");
            return manager().createFolder(kbId, folderName, parentId, uid);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("创建文件夹失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, exception.getMessage());
        }
    }

    /** 检测知识库中的历史路径型虚拟文件夹。 */
    @Operation(summary = "检测虚拟文件夹")
    @GetMapping("/databases/{kbId}/virtual-folders/detect")
    public Object detectVirtualFolders(@PathVariable String kbId) {
        KnowledgePermissions.requireKnowledgeBaseRead(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "虚拟文件夹检测");
        return knowledgeFolderService.detectVirtualFolderData(kbId);
    }

    /** 创建与 SSE 连接生命周期无关的历史目录迁移任务。 */
    @Operation(summary = "提交虚拟文件夹迁移任务")
    @PostMapping("/databases/{kbId}/virtual-folders/migrate")
    public Object startVirtualFolderMigration(@PathVariable String kbId) {
        String uid = KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "虚拟文件夹转换");

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kb_id", kbId);
            payload.put("operator_id", uid);
            TaskService.EnqueueResult enqueued = support.tasker().enqueueUniqueByPayload(
                    "转换知识库历史虚拟文件夹",
                    KnowledgeRouteSupport.VIRTUAL_FOLDER_MIGRATION_TASK_TYPE,
                    payload,
                    Map.of("kb_id", kbId),
                    null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("task_id", enqueued.task().id);
            result.put("created", enqueued.created());
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("提交虚拟文件夹迁移任务失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, exception.getMessage());
        }
    }

    /** 流式返回迁移任务快照，断开连接不取消任务。 */
    @Operation(summary = "虚拟文件夹迁移进度", description = "SSE；帧格式 data: {json}\\n\\n")
    @GetMapping("/databases/{kbId}/virtual-folders/migrations/{taskId}/events")
    public ResponseEntity<StreamingResponseBody> streamVirtualFolderMigration(
            @PathVariable String kbId, @PathVariable String taskId) {
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);

        Map<String, Object> task = support.tasker().getTask(taskId);
        if (task == null
                || !KnowledgeRouteSupport.VIRTUAL_FOLDER_MIGRATION_TASK_TYPE.equals(task.get("type"))
                || !kbId.equals(payloadKbId(task))) {
            throw new ApiHttpException(404, "Migration task not found");
        }

        StreamingResponseBody stream = output -> {
            while (true) {
                Map<String, Object> snapshot = support.tasker().getTask(taskId);
                if (snapshot == null) {
                    break;
                }
                Map<String, Object> publicSnapshot = new LinkedHashMap<>(snapshot);
                publicSnapshot.remove("payload");
                String frame = "data: " + com.alibaba.fastjson2.JSON.toJSONString(publicSnapshot)
                        + "\n\n";
                output.write(frame.getBytes(StandardCharsets.UTF_8));
                output.flush();
                Object status = snapshot.get("status");
                if (status != null && Set.of("success", "failed", "cancelled")
                        .contains(String.valueOf(status))) {
                    break;
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    /** 重命名真实文件夹。 */
    @Operation(summary = "重命名文件夹", description = "body: {folder_name}")
    @PutMapping("/databases/{kbId}/folders/{folderId}/rename")
    public Object renameFolder(
            @PathVariable String kbId, @PathVariable String folderId,
            @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        String folderName = requireString(body, "folder_name");
        try {
            support.ensureDatabaseSupportsDocuments(kbId, "文件夹重命名");
            return manager().renameFolder(kbId, folderId, folderName);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        } catch (Exception exception) {
            log.error("重命名文件夹失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, exception.getMessage());
        }
    }

    /** 移动文件或文件夹。 */
    @Operation(summary = "移动文件", description = "body: {new_parent_id}")
    @PutMapping("/databases/{kbId}/documents/{docId}/move")
    public Object moveDocument(
            @PathVariable String kbId, @PathVariable String docId,
            @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        if (!body.containsKey("new_parent_id")) {
            throw validationError("body", "new_parent_id", "field required");
        }
        KnowledgePermissions.requireKnowledgeBaseManage(kbId);
        String newParentId = optionalString(body, "new_parent_id", null);
        log.debug("Move document {} to {} in {}", docId, newParentId, kbId);
        try {
            support.ensureDatabaseSupportsDocuments(kbId, "文件移动");
            return manager().moveFile(kbId, docId, newParentId);
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (IllegalArgumentException illegalArgument) {
            throw new ApiHttpException(400, illegalArgument.getMessage());
        } catch (Exception exception) {
            log.error("移动文件失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, exception.getMessage());
        }
    }

    /** 抓取 URL 内容并上传到 MinIO。 */
    @Operation(summary = "抓取 URL", description = "body: {url, kb_id}；内容存为 {hash}.html")
    @PostMapping("/files/fetch-url")
    public Object fetchUrl(@RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        AuthGuards.requireAdmin();
        String url = requireString(body, "url");
        String kbId = optionalString(body, "kb_id", null);
        log.debug("Fetching URL: {} for kb_id: {}", url, kbId);
        try {
            support.requireManagePermissionIfKbId(kbId);

            // 1. 下载内容 (包含白名单校验、大小限制、类型检查)
            KnowledgeUrlFetcher.FetchedContent fetched = KnowledgeUrlFetcher.fetchUrlContent(url);

            // 2. 计算 Hash
            String contentHash = KbUtils.calculateContentHash(fetched.content());

            // 检查是否已存在相同内容的文件
            if (kbId != null && !kbId.isEmpty() && manager().fileExistedInDb(kbId, contentHash)) {
                throw new ApiHttpException(409, "数据库中已经存在了相同内容文件");
            }

            // 3. 上传到 MinIO
            MinioStorageClient minioClient = MinioStorageClient.getMinioClient();
            String bucketName = MinioStorageClient.KB_BUCKETS.get("documents");
            minioClient.ensureBucketExists(bucketName);

            String folder = kbId == null || kbId.isEmpty() ? "unknown" : kbId;
            String objectName = folder + "/upload/" + contentHash + ".html";
            MinioStorageClient.UploadResult uploadResult = minioClient.uploadFile(
                    bucketName, objectName, fetched.content(), "text/html");

            // 检测同名文件（URL即为文件名）
            List<Map<String, Object>> sameNameFiles = new ArrayList<>();
            boolean hasSameName = false;
            if (kbId != null && !kbId.isEmpty()) {
                sameNameFiles = manager().getSameNameFiles(kbId, url);
                hasSameName = !sameNameFiles.isEmpty();
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("file_path", uploadResult.url());
            result.put("minio_url", uploadResult.url());
            result.put("content_hash", contentHash);
            result.put("filename", url); // 原始 URL 作为文件名
            result.put("final_url", fetched.finalUrl());
            result.put("size", fetched.content().length);
            result.put("has_same_name", hasSameName);
            result.put("same_name_files", sameNameFiles);
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (IllegalArgumentException validation) {
            log.warn("URL fetch validation failed: {}", validation.getMessage());
            throw new ApiHttpException(400, validation.getMessage());
        } catch (Exception exception) {
            log.error("Failed to fetch URL {}: {}", url, exception.getMessage(), exception);
            throw new ApiHttpException(500, "Failed to fetch URL: " + exception.getMessage());
        }
    }

    /** 将当前用户工作区文件导入 MinIO，返回与普通文件上传一致的预处理结果。 */
    @Operation(summary = "从工作区导入", description = "body: {kb_id, paths}")
    @PostMapping("/files/import-workspace")
    public Object importWorkspaceFiles(@RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        String uid = AuthGuards.requireAdmin();
        String kbId = requireString(body, "kb_id").strip();
        List<String> paths = new ArrayList<>();
        for (String path : KnowledgeRouteSupport.asStringList(body.get("paths"))) {
            if (path != null && !path.strip().isEmpty()) {
                paths.add(path);
            }
        }
        if (kbId.isEmpty()) {
            throw new ApiHttpException(400, "kb_id is required");
        }
        if (paths.isEmpty()) {
            throw new ApiHttpException(400, "请选择至少一个工作区文件");
        }

        support.requireManagePermissionIfKbId(kbId);
        support.ensureDatabaseSupportsDocuments(kbId, "文档添加/解析/入库");

        String bucketName = MinioStorageClient.KB_BUCKETS.get("documents");
        List<Map<String, Object>> results = new ArrayList<>();
        for (String workspacePath : paths) {
            WorkspaceService.WorkspaceFileBytes fileBytes =
                    workspaceService.readWorkspaceFileBytes(workspacePath, uid);
            String filename = fileBytes.filename();
            byte[] content = fileBytes.content();
            String ext = KnowledgeRouteSupport.splitExt(filename)[1].toLowerCase();
            if (!ParserCapabilities.isSupportedFileExtension(filename)) {
                throw new ApiHttpException(400, "Unsupported file type: " + ext);
            }

            String contentHash = KbUtils.calculateContentHash(content);

            if (manager().fileExistedInDb(kbId, contentHash)) {
                throw new ApiHttpException(409, "数据库中已经存在了相同内容文件: " + filename);
            }

            String[] split = KnowledgeRouteSupport.splitExt(filename);
            String basename = split[0];
            String suffix = split[1];
            long timestamp = System.currentTimeMillis();
            String minioFilename = basename + "_" + timestamp + suffix;
            String objectName = kbId + "/upload/" + minioFilename;
            String minioUrl = MinioStorageClient.uploadFileToMinio(bucketName, objectName, content);

            String normalizedFilename = filename.toLowerCase();
            List<Map<String, Object>> sameNameFiles =
                    manager().getSameNameFiles(kbId, normalizedFilename);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("message", "Workspace file successfully imported");
            item.put("file_path", minioUrl);
            item.put("minio_path", minioUrl);
            item.put("kb_id", kbId);
            item.put("content_hash", contentHash);
            item.put("filename", normalizedFilename);
            item.put("original_filename", basename);
            item.put("size", content.length);
            item.put("minio_filename", minioFilename);
            item.put("object_name", objectName);
            item.put("bucket_name", bucketName);
            item.put("workspace_path", workspacePath);
            item.put("same_name_files", sameNameFiles);
            item.put("has_same_name", !sameNameFiles.isEmpty());
            results.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("items", results);
        return result;
    }

    /** 上传文件。 */
    @Operation(summary = "上传文件", description = "multipart file + query kb_id（可选）")
    @PostMapping("/files/upload")
    public Object uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "kb_id", required = false) String kbId) {
        AuthGuards.requireAdmin();
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new ApiHttpException(400, "No selected file");
        }

        if (kbId != null && !kbId.isEmpty()) {
            support.requireManagePermissionIfKbId(kbId);
            support.ensureDatabaseSupportsDocuments(kbId, "文档上传");
        }

        log.debug("Received upload file with filename: {}", originalFilename);

        String[] split = KnowledgeRouteSupport.splitExt(originalFilename);
        String basename = split[0];
        String ext = split[1].toLowerCase();

        if (!ParserCapabilities.isSupportedFileExtension(originalFilename)) {
            throw new ApiHttpException(400, "Unsupported file type: " + ext);
        }

        // 直接使用原始文件名（小写）
        String filename = (basename + ext).toLowerCase();

        byte[] fileBytes;
        try {
            fileBytes = com.wisesoft.wenqu.common.UploadUtils.readUploadWithLimit(
                    file, com.wisesoft.wenqu.common.UploadUtils.MAX_UPLOAD_SIZE_BYTES,
                    "文件过大，当前仅支持 100 MB 以内的文件");
        } catch (com.wisesoft.wenqu.common.UploadUtils.SizeLimitExceededException tooLarge) {
            throw new ApiHttpException(400, tooLarge.getMessage());
        } catch (IOException ioException) {
            throw new ApiHttpException(400, ioException.getMessage());
        }

        String contentHash = KbUtils.calculateContentHash(fileBytes);

        if (manager().fileExistedInDb(kbId, contentHash)) {
            throw new ApiHttpException(
                    409,
                    "数据库中已经存在了相同内容文件，File with the same content already exists in this database");
        }

        // 直接上传到MinIO，添加时间戳区分版本
        long timestamp = System.currentTimeMillis();
        String minioFilename = basename + "_" + timestamp + ext;

        String bucketName = MinioStorageClient.KB_BUCKETS.get("documents");
        String folder = kbId == null || kbId.isEmpty() ? "unknown" : kbId;
        String objectName = folder + "/upload/" + minioFilename;

        // 上传到MinIO
        String minioUrl = MinioStorageClient.uploadFileToMinio(bucketName, objectName, fileBytes);

        // 检测同名文件（基于原始文件名）
        List<Map<String, Object>> sameNameFiles = manager().getSameNameFiles(kbId, filename);
        boolean hasSameName = !sameNameFiles.isEmpty();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "File successfully uploaded");
        result.put("file_path", minioUrl); // MinIO路径作为主要路径
        result.put("minio_path", minioUrl); // MinIO路径
        result.put("kb_id", kbId);
        result.put("content_hash", contentHash);
        result.put("filename", filename); // 原始文件名（小写）
        result.put("original_filename", basename); // 原始文件名（去掉后缀）
        result.put("size", fileBytes.length);
        result.put("minio_filename", minioFilename); // MinIO中的文件名（带时间戳）
        result.put("object_name", objectName);
        result.put("bucket_name", bucketName); // MinIO存储桶名称
        result.put("same_name_files", sameNameFiles); // 同名文件列表
        result.put("has_same_name", hasSameName); // 是否包含同名文件标志
        return result;
    }

    /** 获取当前支持的文件类型。 */
    @Operation(summary = "支持的文件类型", description = "按字典序排序")
    @GetMapping("/files/supported-types")
    public Object getSupportedFileTypes() {
        AuthGuards.requireAdmin();
        List<String> fileTypes = new ArrayList<>(ParserCapabilities.SUPPORTED_FILE_EXTENSIONS);
        java.util.Collections.sort(fileTypes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "success");
        result.put("file_types", fileTypes);
        return result;
    }

    /** 调用统一 Parser 将文件解析为 markdown，需要管理员权限。 */
    @Operation(summary = "解析为 markdown", description = "multipart file；临时文件解析后即删")
    @PostMapping("/files/markdown")
    public Object markItDown(@RequestParam("file") MultipartFile file) {
        AuthGuards.requireAdmin();
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new ApiHttpException(400, "无法识别文件名");
        }

        String suffix = KnowledgeRouteSupport.splitExt(originalFilename)[1].toLowerCase();
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile("wenqu-kb-", suffix);
            com.wisesoft.wenqu.common.UploadUtils.writeUploadToPath(
                    file, tempPath, com.wisesoft.wenqu.common.UploadUtils.MAX_UPLOAD_SIZE_BYTES,
                    "文件过大，当前仅支持 100 MB 以内的文件");

            String markdownContent = ocrService.parseDocument(tempPath.toString(), null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("markdown_content", markdownContent);
            result.put("message", "success");
            return result;
        } catch (com.wisesoft.wenqu.common.UploadUtils.SizeLimitExceededException
                 | IllegalArgumentException validation) {
            throw new ApiHttpException(400, validation.getMessage());
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("文件解析失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "文件解析失败");
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (Exception cleanupError) {
                    log.warn("临时文件清理失败 {}: {}", tempPath, cleanupError.getMessage());
                }
            }
        }
    }

    // =========================================================================
    // === 知识库类型分组 ===
    // =========================================================================

    /** 获取支持的知识库类型。 */
    @Operation(summary = "知识库类型")
    @GetMapping("/types")
    public Object getKnowledgeBaseTypes() {
        AuthGuards.requireAdmin();
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kb_types", manager().getSupportedKbTypes());
            result.put("message", "success");
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取知识库类型失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取知识库类型失败");
        }
    }

    /** 获取支持的知识库分块策略。 */
    @Operation(summary = "分块预设", description = "响应 {\"chunk_presets\":[...],\"message\":\"success\"}")
    @GetMapping("/chunk-presets")
    public Object getKnowledgeChunkPresets() {
        AuthGuards.requireAdmin();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunk_presets", ChunkPresets.getChunkPresetOptions());
        result.put("message", "success");
        return result;
    }

    /** 获取知识库统计信息。 */
    @Operation(summary = "知识库统计")
    @GetMapping("/stats")
    public Object getKnowledgeBaseStatistics() {
        AuthGuards.requireAdmin();
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("stats", manager().getStatistics());
            result.put("message", "success");
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("获取知识库统计失败 {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "获取知识库统计失败");
        }
    }

    // =========================================================================
    // === 知识库 AI 辅助功能分组 ===
    // =========================================================================

    /** 使用 LLM 生成或优化知识库描述。 */
    @Operation(summary = "AI 生成知识库描述",
            description = "body: {name, current_description, file_list}；描述将作为智能体工具描述")
    @PostMapping("/generate-description")
    public Object generateDescription(
            @RequestBody(required = false) Map<String, Object> body) {
        requireBody(body);
        AuthGuards.requireAdmin();
        String name = requireString(body, "name");
        String currentDescription = optionalString(body, "current_description", "");
        List<String> fileList = body.get("file_list") == null
                ? new ArrayList<>() : KnowledgeRouteSupport.asStringList(body.get("file_list"));

        log.debug("Generating description for knowledge base: {}, files: {}", name, fileList.size());

        // 构建文件列表文本
        if (!fileList.isEmpty()) {
            // 限制文件数量，避免 prompt 过长
            List<String> displayFiles = fileList.subList(0, Math.min(fileList.size(), DESCRIPTION_FILE_LIMIT));
            StringBuilder filesBuilder = new StringBuilder();
            for (String file : displayFiles) {
                filesBuilder.append("- ").append(file).append("\n");
            }
            String filesStr = filesBuilder.toString();
            if (filesStr.endsWith("\n")) {
                filesStr = filesStr.substring(0, filesStr.length() - 1);
            }
            String moreText = fileList.size() > DESCRIPTION_FILE_LIMIT
                    ? "\n... (还有 " + (fileList.size() - DESCRIPTION_FILE_LIMIT) + " 个文件)" : "";
            currentDescription = (currentDescription == null ? "" : currentDescription)
                    + "\n\n知识库包含的文件:\n" + filesStr + moreText;
        }

        if (currentDescription == null || currentDescription.isEmpty()) {
            currentDescription = "暂无描述";
        }

        // 构建提示词（逐字对齐参考实现的 textwrap.dedent(...).strip()）
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请帮我优化以下知识库的描述。\n\n");
        promptBuilder.append("知识库名称: ").append(name).append("\n");
        promptBuilder.append("当前描述: ").append(currentDescription).append("\n\n");
        promptBuilder.append("要求:\n");
        promptBuilder.append("1. 这个描述将作为智能体工具的描述使用\n");
        promptBuilder.append("2. 智能体会根据知识库的标题和描述来选择合适的工具\n");
        promptBuilder.append("3. 所以描述需要清晰、具体，说明该知识库包含什么内容、适合解答什么类型的问题\n");
        promptBuilder.append("4. 描述应该简洁有力，通常 2-4 句话即可\n");
        promptBuilder.append("5. 不要使用 Markdown 格式\n");
        if (!fileList.isEmpty()) {
            promptBuilder.append("6. 请参考提供的文件列表来准确概括知识库内容\n");
        }
        promptBuilder.append("\n请直接输出优化后的描述，不要有任何前缀说明。");
        String prompt = promptBuilder.toString().strip();

        try {
            Map<String, Object> systemOptions = optionsService.get(OptionsService.SYSTEM_OPTIONS);
            String defaultModel = systemOptions.get("default_model") == null
                    ? null : String.valueOf(systemOptions.get("default_model"));
            ModelSelectors.GeneralResponse response =
                    modelSelectors.selectModel(defaultModel).call(prompt);
            String description = response == null || response.content == null
                    ? "" : response.content.strip();
            log.debug("Generated description: {}", description);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("description", description);
            result.put("status", "success");
            return result;
        } catch (ApiHttpException httpException) {
            throw httpException;
        } catch (Exception exception) {
            log.error("生成描述失败: {}", exception.getMessage(), exception);
            throw new ApiHttpException(500, "生成描述失败: " + exception.getMessage());
        }
    }

    // =========================================================================
    // === 共享私有工具 ===
    // =========================================================================

    /** {@code {"message": "...", "status": "failed"}}（参考实现在读取失败时返回该体而非抛出）。 */
    private static Map<String, Object> failure(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("status", "failed");
        return result;
    }

    /** 从 {@code {"meta": {...}}} 取 meta（缺失时返回空表，与参考实现的 {@code .get("meta", {})} 一致）。 */
    private static Map<String, Object> metaOf(Map<String, Object> fileInfo) {
        if (fileInfo == null) {
            return new LinkedHashMap<>();
        }
        return KnowledgeRouteSupport.asStringKeyedMap(fileInfo.get("meta"));
    }

    /** SSE 快照的 {@code payload.kb_id} 取值（用于任务归属校验）。 */
    private static String payloadKbId(Map<String, Object> task) {
        Object payload = task.get("payload");
        if (payload instanceof Map<?, ?> payloadMap) {
            Object kbId = payloadMap.get("kb_id");
            return kbId == null ? null : String.valueOf(kbId);
        }
        return null;
    }

    /** 当前用户部门 ID（参考实现的 {@code current_user.department_id}），无法解析时返回 null。 */
    private static Integer departmentIdOrNull() {
        String departmentId = RequestUser.departmentId();
        if (departmentId == null || departmentId.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(departmentId.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * {@code content-disposition} 响应头。
     *
     * <p>与 starlette 一致：纯 ASCII 用 {@code filename="..."}，否则走 RFC 5987 的
     * {@code filename*=UTF-8''<pct-encoded>}（{@code urllib.parse.quote} 的 safe 默认值是 {@code /}）。
     */
    private static String attachmentHeader(String filename) {
        String safeName = filename == null ? "" : filename;
        boolean ascii = safeName.chars().allMatch(value -> value < 128);
        if (ascii) {
            return "attachment; filename=\"" + safeName + "\"";
        }
        return "attachment; filename*=UTF-8''" + UrlQuote.quote(safeName, "/");
    }

    /** 整数型数值字段归一（pydantic 对 {@code int} 不做隐式转换，非整数 → 422）。 */
    private static int toInt(Object value, String field) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw validationError("body", field, "Input should be a valid integer");
    }

    // -------------------- 请求校验（对齐 pydantic / Query(ge/le)） --------------------

    /** 请求体必填校验（{@code loc=["body"]}、{@code type=missing}）。 */
    private static void requireBody(Object body) {
        if (body == null) {
            throw ApiHttpException.objectDetail(
                    422,
                    "请求参数校验失败",
                    List.of(Map.of("loc", List.of("body"), "msg", "Field required", "type", "missing")));
        }
    }

    /** 请求校验失败 → 422（对应 pydantic 的请求体校验错误）。 */
    private static ApiHttpException validationError(String location, String field, String message) {
        return ApiHttpException.objectDetail(
                422,
                "请求参数校验失败",
                List.of(Map.of("loc", List.of(location, field), "msg", message, "type", "value_error")));
    }

    /** 必填 {@code str} 字段（空串合法，交由服务层判定）。 */
    private static String requireString(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            throw validationError("body", field, "field required");
        }
        if (value instanceof String text) {
            return text;
        }
        throw validationError("body", field, "Input should be a valid string");
    }

    /** 可选 {@code str | None} 字段：缺失/显式 null 取默认值。 */
    private static String optionalString(Map<String, Object> body, String field, String defaultValue) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String text) {
            return text;
        }
        throw validationError("body", field, "Input should be a valid string");
    }

    /** 可选 {@code bool} 字段：缺失取默认值。 */
    private static boolean optionalBoolean(Map<String, Object> body, String field, boolean defaultValue) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw validationError("body", field, "Input should be a valid boolean");
    }

    /** 必填 {@code list[str]} 字段。 */
    private static List<String> requireStringList(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            throw validationError("body", field, "field required");
        }
        if (!(value instanceof List<?> rawList)) {
            throw validationError("body", field, "Input should be a valid list");
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item == null) {
                throw validationError("body", field, "Input should be a valid string");
            }
            result.add(String.valueOf(item));
        }
        return result;
    }

    /** 必填 {@code dict} 字段（非对象 → 422，与 pydantic 一致）。 */
    private static Map<String, Object> requireStringKeyedMap(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            throw validationError("body", field, "field required");
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw validationError("body", field, "Input should be a valid dictionary");
        }
        return KnowledgeRouteSupport.asStringKeyedMap(rawMap);
    }

    /** 可选 {@code dict | None} 字段：缺失/显式 null → null；非对象 → 422。 */
    private static Map<String, Object> optionalStringKeyedMap(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw validationError("body", field, "Input should be a valid dictionary");
        }
        return KnowledgeRouteSupport.asStringKeyedMap(rawMap);
    }

    /** {@code Query(ge, le)} 约束（越界 → 422，与全局约束处理同口径）。 */
    private static void requireRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new ApiHttpException(422, "参数校验失败: " + field + " 超出允许范围");
        }
    }
}
