package com.wisesoft.wenqu.controller;

import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.config.AuthGuards;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseException;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部知识库（CLI 只读浏览）路由，逐端点对齐参考实现
 * {@code server/routers/external_kb_router.py}（前缀 {@code /knowledge}）。
 *
 * <p>响应契约照搬：列表 {@code {"databases":[...]}}（每项五个键，{@code kb_type} 已小写）；
 * 文件列表/检索/打开/查找直接透传知识库运行时的高层方法返回值。
 *
 * <p>权限与错误语义照搬：
 * <ul>
 *   <li>全部端点要求登录（{@link AuthGuards#requireUser()}），仅返回当前用户可见知识库；</li>
 *   <li>知识库不可见 → 404 {@code 知识库 {kb_id} 不存在或无权访问}；</li>
 *   <li>不支持文档全文操作的类型（如只读连接器）→ 400 {@code {name 或 kb_type} 只支持检索，不支持{操作}}；</li>
 *   <li>{@code retrieve} 的 {@code KBNotFoundError} → 404 {@code str(e)}，其余异常 → 400
 *       {@code 知识库查询失败: {原因}}；</li>
 *   <li>{@code open}/{@code find} 的 {@code ValueError} → 400 {@code str(e)}，其余异常 → 400
 *       固定文案（{@code 打开知识库文件失败} / {@code 知识库文件内检索失败}，不带原因）。</li>
 * </ul>
 *
 * <p>平台差异（必要替换）：{@code Depends(get_required_user)} → {@link AuthGuards#requireUser()}；
 * pydantic 模型 {@code ExternalRetrieveRequest}/{@code ExternalFindRequest} → 以
 * {@code Map<String, Object>} 承接并按 snake_case 键读取（本工程路由请求体的既有惯例），
 * 其中的必填与类型约束 → 显式校验；{@code Query(ge/le)} 约束 → 显式校验并抛 422；
 * Python {@code ValueError} ↔ Java {@link IllegalArgumentException}（知识库能力校验的抛出类型）。
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
@Tag(name = "Knowledge", description = "外部知识库只读浏览")
public class ExternalKbController {

    /** 文件列表分页上限（对应 Query(limit, ge=1, le=500)）。 */
    private static final int FILE_LIST_LIMIT_MAX = 500;

    /** 文件打开时的行窗口上限（对应 Query(limit, ge=1, le=1800)）。 */
    private static final int FILE_OPEN_LIMIT_MAX = 1800;

    private final KnowledgeBaseManager knowledgeBaseManager;

    /** 列出当前登录用户可见的知识库，供 CLI 选择与展示。 */
    @Operation(summary = "列出外部知识库", description = "当前用户可见的知识库，供 CLI 选择")
    @GetMapping("/databases/external")
    public Map<String, Object> listExternalDatabases() {
        String uid = AuthGuards.requireUser();
        List<KnowledgeBaseSummary> databases = knowledgeBaseManager.getDatabasesByUid(uid);
        List<Map<String, Object>> items = new ArrayList<>();
        for (KnowledgeBaseSummary database : databases) {
            String kbType = database.kbType() == null ? "" : database.kbType().toLowerCase();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kb_id", database.kbId());
            item.put("name", database.name());
            item.put("description", database.description() == null ? "" : database.description());
            item.put("kb_type", kbType);
            item.put("supports_documents", knowledgeBaseManager.databaseTypeSupportsDocuments(kbType));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("databases", items);
        return result;
    }

    /** 列出或搜索知识库文件，供 CLI 浏览与定位。 */
    @Operation(summary = "列出外部知识库文件", description = "按文件名关键词搜索，支持分页与状态筛选")
    @GetMapping("/databases/external/{kb_id}/files")
    public Map<String, Object> listExternalFiles(
            @PathVariable("kb_id") String kbId,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "status", defaultValue = "all") String status) {
        String uid = AuthGuards.requireUser();
        if (offset < 0) {
            throw new ApiHttpException(422, "offset 不能小于 0");
        }
        if (limit < 1 || limit > FILE_LIST_LIMIT_MAX) {
            throw new ApiHttpException(422, "limit 必须在 1-" + FILE_LIST_LIMIT_MAX + " 之间");
        }
        KnowledgeBaseSummary database = knowledgeBaseManager.getAccessibleDatabaseInfoByUid(uid, kbId);
        if (database == null) {
            throw new ApiHttpException(404, "知识库 " + kbId + " 不存在或无权访问");
        }
        if (!knowledgeBaseManager.databaseTypeSupportsDocuments(database.kbType())) {
            throw new ApiHttpException(400, documentSupportError(database, "文档查看"));
        }
        List<Map<String, Object>> knowledgeBases = new ArrayList<>();
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("kb_id", database.kbId());
        target.put("name", database.name());
        knowledgeBases.add(target);
        return knowledgeBaseManager.searchDocumentFiles(knowledgeBases, query, offset, limit, status,
                true, true);
    }

    /** 对知识库执行检索查询，返回结构化结果。 */
    @Operation(summary = "外部知识库检索", description = "body: query 必填，file_name / options 可选")
    @PostMapping("/databases/external/{kb_id}/retrieve")
    public Map<String, Object> retrieveExternal(
            @PathVariable("kb_id") String kbId,
            @RequestBody(required = false) Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        Map<String, Object> payload = body == null ? Map.of() : body;
        // 参考实现的 pydantic 模型在进入函数体前完成校验，故此处先按模型取全部字段（类型不符 → 422），
        // 再走函数体自身的检查顺序：query 非空 → 知识库可见性/能力。
        String query = asString(payload.get("query"));
        Map<String, Object> options = asOptionsMap(payload.get("options"));
        String fileName = asString(payload.get("file_name"));
        if (query == null || query.isEmpty()) {
            throw new ApiHttpException(400, "query is required");
        }
        requireAccessibleKb(kbId, uid, false, "文档查看");
        if (fileName != null && !fileName.isEmpty()) {
            options.put("file_name", fileName);
        }
        try {
            return knowledgeBaseManager.retrieve(kbId, query, options);
        } catch (KnowledgeBaseException.KBNotFoundError exc) {
            throw new ApiHttpException(404, exc.getMessage());
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("external 知识库查询失败 {}", exc.getMessage(), exc);
            throw new ApiHttpException(400, "知识库查询失败: " + exc.getMessage());
        }
    }

    /** 按行窗口打开文件解析后的 Markdown 内容。 */
    @Operation(summary = "打开外部知识库文件", description = "按行窗口返回解析后的 Markdown 内容")
    @GetMapping("/databases/external/{kb_id}/files/{file_id}/open")
    public Map<String, Object> openExternalFile(
            @PathVariable("kb_id") String kbId,
            @PathVariable("file_id") String fileId,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        String uid = AuthGuards.requireUser();
        if (offset < 0) {
            throw new ApiHttpException(422, "offset 不能小于 0");
        }
        if (limit < 1 || limit > FILE_OPEN_LIMIT_MAX) {
            throw new ApiHttpException(422, "limit 必须在 1-" + FILE_OPEN_LIMIT_MAX + " 之间");
        }
        requireAccessibleKb(kbId, uid, true, "文档查看");
        try {
            return knowledgeBaseManager.openDocument(kbId, fileId, offset, limit);
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("external 打开知识库文件失败 {}", exc.getMessage(), exc);
            throw new ApiHttpException(400, "打开知识库文件失败");
        }
    }

    /** 在指定文件内做关键词或正则定位，返回匹配窗口。 */
    @Operation(summary = "外部知识库文件内查找", description = "body: patterns 必填，其余可选")
    @PostMapping("/databases/external/{kb_id}/files/{file_id}/find")
    public Map<String, Object> findExternalFile(
            @PathVariable("kb_id") String kbId,
            @PathVariable("file_id") String fileId,
            @RequestBody(required = false) Map<String, Object> body) {
        String uid = AuthGuards.requireUser();
        Map<String, Object> payload = body == null ? Map.of() : body;
        // pydantic 先校验模型（patterns 必填 + 类型），再进函数体；函数体内顺序为
        // 知识库可见性/能力 → patterns 非空。
        List<String> patterns = asStringList(payload.get("patterns"), "patterns");
        boolean useRegex = asBoolean(payload.get("use_regex"), false, "use_regex");
        boolean caseSensitive = asBoolean(payload.get("case_sensitive"), false, "case_sensitive");
        int maxWindows = asInt(payload.get("max_windows"), 5, "max_windows");
        int windowSize = asInt(payload.get("window_size"), 80, "window_size");
        requireAccessibleKb(kbId, uid, true, "文档查找");
        if (patterns.isEmpty()) {
            throw new ApiHttpException(400, "patterns 不能为空");
        }
        try {
            return knowledgeBaseManager.findInDocument(kbId, fileId, patterns, useRegex, caseSensitive,
                    maxWindows, windowSize);
        } catch (IllegalArgumentException exc) {
            throw new ApiHttpException(400, exc.getMessage());
        } catch (ApiHttpException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("external 知识库文件内检索失败 {}", exc.getMessage(), exc);
            throw new ApiHttpException(400, "知识库文件内检索失败");
        }
    }

    // =============================================================================
    // 内部工具
    // =============================================================================

    /** 校验知识库对 uid 可见，必要时同时校验文档能力（对应 _require_accessible_kb）。 */
    private KnowledgeBaseSummary requireAccessibleKb(String kbId, String uid, boolean requireDocuments,
                                                     String operation) {
        KnowledgeBaseSummary database = knowledgeBaseManager.getAccessibleDatabaseInfoByUid(
                uid, kbId == null ? "" : kbId.strip());
        if (database == null) {
            throw new ApiHttpException(404, "知识库 " + kbId + " 不存在或无权访问");
        }
        if (requireDocuments && !knowledgeBaseManager.databaseTypeSupportsDocuments(database.kbType())) {
            throw new ApiHttpException(400, documentSupportError(database, operation));
        }
        return database;
    }

    /** 组装「只支持检索」错误文案（对应 {database.name or kb_type} 只支持检索，不支持{operation}）。 */
    private static String documentSupportError(KnowledgeBaseSummary database, String operation) {
        String kbType = database.kbType() == null ? "" : database.kbType().toLowerCase();
        String name = database.name() == null || database.name().isEmpty() ? kbType : database.name();
        return name + " 只支持检索，不支持" + operation;
    }

    /** 读取可选的对象型 options 字段；非对象（且非 null）按 pydantic 类型错误处理。 */
    private static Map<String, Object> asOptionsMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value == null) {
            return result;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new ApiHttpException(422, "options 必须是对象");
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /** 读取必填的字符串列表字段（pydantic list[str] 必填语义：缺失或类型不符 → 422）。 */
    private static List<String> asStringList(Object value, String field) {
        if (value == null) {
            throw new ApiHttpException(422, field + " 不能为空");
        }
        if (!(value instanceof List<?> raw)) {
            throw new ApiHttpException(422, field + " 必须是字符串数组");
        }
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (item == null) {
                throw new ApiHttpException(422, field + " 的元素不能为 null");
            }
            result.add(String.valueOf(item));
        }
        return result;
    }

    /** 读取带默认值的布尔字段。 */
    private static boolean asBoolean(Object value, boolean defaultValue, String field) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new ApiHttpException(422, field + " 必须是布尔值");
    }

    /** 读取带默认值的整数字段。 */
    private static int asInt(Object value, int defaultValue, String field) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new ApiHttpException(422, field + " 必须是整数");
    }

    /** 仅接受字符串类型的取值（pydantic str 语义：非字符串 → 422）。 */
    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new ApiHttpException(422, "字段值必须是字符串");
    }
}
