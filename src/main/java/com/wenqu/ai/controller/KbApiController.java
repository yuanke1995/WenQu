package com.wenqu.ai.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.service.ApiKeyService;
import com.wenqu.ai.service.DocumentService;
import com.wenqu.ai.service.HybridRetrievalService;
import com.wenqu.ai.service.KnowledgeBaseService;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.service.ResourceVisibilityService;
import com.wenqu.ai.service.RoleService;
import com.wenqu.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.wenqu.ai.openapi.OpenApi;
import com.wenqu.ai.openapi.OpenApiScopes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库对外 API（v1 命名空间）：检索（只读）+ 库管理（读/写按 scope 分层）。
 * <p>
 * 与网页端点同一套服务层与可见性判定——对外通道不放宽任何资源级权限：
 * <ul>
 *   <li>检索：{@code POST /api/ai/v1/retrieval}（scope kb.read）——混合检索直通，
 *       调用方拿到的知识块与本人在网页上可见的范围一致（不可见文档两路召回统一剔除）；</li>
 *   <li>读库：{@code GET /api/ai/v1/kbs}、{@code GET /api/ai/v1/kbs/{id}}、
 *       {@code GET /api/ai/v1/kbs/{id}/documents}（scope kb.read）；</li>
 *   <li>写库：{@code POST /api/ai/v1/kbs}、{@code POST /api/ai/v1/kbs/{id}/documents}
 *       （scope kb.write——默认不放开，Key 编辑里显式勾选）。</li>
 * </ul>
 * 解析/向量化走与网页上传同一条后台队列（{@code DocumentService.upload}），
 * 响应同步返回文档行（status=2 解析中），进度轮询 {@code GET /api/ai/v1/kbs/{id}/documents}。
 * <p>
 * 鉴权：API Key 通道（X-Api-Key）与 JWT 通道同判——scope 只约束 Key 通道，
 * 登录态是本人、不受 scope 影响。错误走 {@link BizException}（400/403/404 由全局异常处理器
 * 映射 HTTP 状态，响应体保持 {success,msg} 包络）。
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库对外 API", description = "外部项目检索/管理问渠知识库（v1，按 scope 分层）")
public class KbApiController {

    private final HybridRetrievalService hybridRetrievalService;
    private final KnowledgeBaseService kbService;
    private final DocumentService documentService;
    private final ApiKeyService apiKeyService;
    private final RateLimitService rateLimitService;
    private final ResourceVisibilityService visibility;
    private final RoleService roleService;

    /** 检索返回条数上限（对外通道单轮上限；内部问答按配置取，这里取小的确定性口径） */
    private static final int RETRIEVAL_TOP_K_MAX = 50;
    private static final int RETRIEVAL_TOP_K_DEFAULT = 20;

    private ResourceVisibilityService.Principal principal() {
        return new ResourceVisibilityService.Principal(RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    private String apiKeyIdOf() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        Object v = attrs == null ? null : attrs.getAttribute(
                com.wenqu.ai.config.SecurityConfig.ATTR_API_KEY_ID,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return v == null ? null : String.valueOf(v);
    }

    // ==================== 检索（只读） ====================

    @Operation(summary = "知识检索（混合检索直通）",
            description = "body: {\"query\": 必填, \"knowledgeBaseIds\": [\"kbId\"](可选，空=本人可见全部库), "
                    + "\"topK\": 1-50(默认 20), \"minScore\": 0-1(可选，按融合分过滤)}。"
                    + "返回按融合分降序的知识块（含来源文档/库/块序号），不执行重排。"
                    + "API Key 需开通 kb.read scope")
    @OpenApi(scope = OpenApiScopes.KB_READ, summary = "知识检索（混合检索直通）")
    @PostMapping("/api/ai/v1/retrieval")
    public ResultJson retrieval(@RequestBody JSONObject body) {
        apiKeyService.requireScope(apiKeyIdOf(), ApiKeyService.SCOPE_KB_READ);
        String uid = RequestUser.uid();
        rateLimitService.checkRateLimit("chat", uid);

        String query = body.getString("query");
        if (query == null || query.isBlank()) throw new BizException("query 不能为空");
        if (query.length() > 8000) throw new BizException("query 过长（上限 8000 字）");

        List<String> kbIds = new ArrayList<>();
        JSONArray arr = body.getJSONArray("knowledgeBaseIds");
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                String id = arr.getString(i);
                if (id != null && !id.isBlank()) kbIds.add(id.trim());
            }
        }
        // 显式点名的库先过可见性：不可见的库当不存在（不泄露存在性，与库详情同口径）
        var p = principal();
        List<String> visibleKbIds = new ArrayList<>();
        for (String id : kbIds) {
            KnowledgeBase kb = kbService.get(id);
            if (kb == null) continue;
            if (kb.getBuiltin() != null && kb.getBuiltin() == 1 && !roleService.isAdminAny(RequestUser.roles()))
                continue;
            if (visibility.canRead(p, kb.getCreatedBy())) visibleKbIds.add(id);
        }
        if (!kbIds.isEmpty() && visibleKbIds.isEmpty()) {
            throw new BizException("指定的知识库均不存在或不可见");
        }

        int topK = Math.max(1, Math.min(body.getIntValue("topK", RETRIEVAL_TOP_K_DEFAULT), RETRIEVAL_TOP_K_MAX));
        Double minScore = body.getDouble("minScore");

        HybridRetrievalService.RetrievalDiag diag = new HybridRetrievalService.RetrievalDiag();
        List<HybridRetrievalService.Hit> hits =
                hybridRetrievalService.search(query, diag, visibleKbIds.isEmpty() ? null : visibleKbIds, false);
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (HybridRetrievalService.Hit h : hits) {
            if (minScore != null && h.score() < minScore) continue;
            if (chunks.size() >= topK) break;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", h.knowledgeId());
            c.put("text", h.content());
            c.put("score", h.score());
            c.put("title", h.title());
            c.put("titlePath", h.titlePath());
            c.put("docId", h.docId());
            c.put("kbId", h.kbId());
            c.put("chunkIndex", h.chunkIndex());
            c.put("images", h.images() == null ? List.of() : h.images());
            chunks.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunks", chunks);
        out.put("total", chunks.size());
        // 检索链路降级要如实告知（与问答链路同一诊断对象，对外只给可行动摘要）
        List<String> warnings = new ArrayList<>();
        if (diag.isVectorPartialFailed()) warnings.add("向量检索部分失败，结果可能不完整");
        if (diag.isKeywordFailed()) warnings.add("关键词检索失败，仅向量结果");
        if (diag.isKeywordFallback()) warnings.add("关键词引擎降级为数据库匹配");
        if (!warnings.isEmpty()) out.put("warnings", warnings);
        return ResultJson.ok(out);
    }

    // ==================== 库管理（读） ====================

    @Operation(summary = "知识库列表",
            description = "本人可见的知识库（谁建归谁，含个人默认库；官方内置库仅管理员级可见），"
                    + "含每库文档数。API Key 需 kb.read")
    @OpenApi(scope = OpenApiScopes.KB_READ, summary = "知识库列表")
    @GetMapping("/api/ai/v1/kbs")
    public ResultJson listKbs() {
        apiKeyService.requireScope(apiKeyIdOf(), ApiKeyService.SCOPE_KB_READ);
        var p = principal();
        List<Map<String, Object>> all = kbService.listWithCounts().stream()
                .filter(m -> visibility.canRead(p, (String) m.get("createdBy")))
                .filter(m -> !(Integer.valueOf(1).equals(m.get("builtin")))
                        || roleService.isAdminAny(RequestUser.roles()))
                .map(this::kbBrief)
                .toList();
        return ResultJson.ok(Map.of("knowledgeBases", all, "total", all.size()));
    }

    @Operation(summary = "知识库详情",
            description = "仅可见库可查（不可见按不存在处理，不泄露存在性）。返回字段为对外精简视图。API Key 需 kb.read")
    @OpenApi(scope = OpenApiScopes.KB_READ, summary = "知识库详情")
    @GetMapping("/api/ai/v1/kbs/{id}")
    public ResultJson getKb(@PathVariable("id") String id) {
        apiKeyService.requireScope(apiKeyIdOf(), ApiKeyService.SCOPE_KB_READ);
        KnowledgeBase kb = mustReadable(id);
        if (kb == null) throw new BizException(404, "知识库不存在");
        return ResultJson.ok(kbBrief(toMap(kb)));
    }

    @Operation(summary = "库内文档列表",
            description = "该库下本人可见的文档（状态：0 生效 / 1 弃用 / 2 解析中 / 3 解析失败，含进度与失败原因）。"
                    + "API Key 需 kb.read")
    @OpenApi(scope = OpenApiScopes.KB_READ, summary = "知识库文档列表与解析进度")
    @GetMapping("/api/ai/v1/kbs/{id}/documents")
    public ResultJson listDocs(@PathVariable("id") String id) {
        apiKeyService.requireScope(apiKeyIdOf(), ApiKeyService.SCOPE_KB_READ);
        KnowledgeBase kb = mustReadable(id);
        if (kb == null) throw new BizException(404, "知识库不存在");
        var p = principal();
        List<Map<String, Object>> docs = documentService.list(id).stream()
                .filter(d -> visibility.canRead(p, d.getCreatedBy()))
                .map(this::docBrief)
                .toList();
        return ResultJson.ok(Map.of("documents", docs, "total", docs.size()));
    }

    // ==================== 库管理（写，kb.write） ====================

    @Operation(summary = "创建知识库",
            description = "body: {\"name\": 必填, \"description\": 可选, \"embeddingRef\": 必填（providerId/modelId，"
                    + "见 GET /api/ai/v1/models/embeddings）, \"icon\"/\"queryParams\"/\"parseParams\": 可选}。"
                    + "创建人=Key 签发人。API Key 需 kb.write")
    @OpenApi(scope = OpenApiScopes.KB_WRITE, summary = "创建知识库")
    @PostMapping("/api/ai/v1/kbs")
    public ResultJson createKb(@RequestBody Map<String, Object> body) {
        apiKeyService.requireScope(apiKeyIdOf(), ApiKeyService.SCOPE_KB_WRITE);
        if (body.get("name") == null || String.valueOf(body.get("name")).isBlank()) {
            throw new BizException("知识库名称不能为空");
        }
        KnowledgeBase kb = kbService.create(body, RequestUser.uid());
        log.info("[KB-API] 建库 kb={} name={} by={} key={}", kb.getId(), kb.getName(), RequestUser.uid(), apiKeyIdOf());
        return ResultJson.ok(kbBrief(toMap(kb)));
    }

    @Operation(summary = "上传文档到知识库",
            description = "multipart 传 file（docx/pdf/xlsx/ppt/pptx/txt/md/csv，与网页上传同一格式与大小闸）。"
                    + "同步返回文档行（status=2 解析中），解析/分块/向量化走后台队列，"
                    + "进度轮询 GET /api/ai/v1/kbs/{id}/documents。仅可向本人可管的库上传。API Key 需 kb.write")
    @OpenApi(scope = OpenApiScopes.KB_WRITE, summary = "上传文档")
    @PostMapping("/api/ai/v1/kbs/{id}/documents")
    public ResultJson uploadDoc(@PathVariable("id") String id,
                                @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                @RequestParam(value = "description", required = false) String description) throws Exception {
        String apiKeyId = apiKeyIdOf();
        apiKeyService.requireScope(apiKeyId, ApiKeyService.SCOPE_KB_WRITE);
        KnowledgeBase kb = kbService.get(id);
        if (kb == null || (kb.getBuiltin() != null && kb.getBuiltin() == 1)
                || !visibility.canManage(principal(), kb.getCreatedBy())) {
            // 不可见与不可管同一文案：不泄露存在性，也不误导成「库不存在」
            throw new BizException(403, "知识库不存在或该身份不可向其上传文档");
        }
        if (description != null && description.length() > 500) throw new BizException("文档描述过长（最多 500 字）");
        AiDocument doc = documentService.upload(file, description, id, null);
        log.info("[KB-API] 上传文档 kb={} doc={} file={} by={} key={}", id, doc.getId(),
                file.getOriginalFilename(), RequestUser.uid(), apiKeyId);
        return ResultJson.ok(docBrief(doc));
    }

    // ==================== 内部工具 ====================

    /** 可读判定（含官方内置库仅管理员级可见的口径）；不可见返回 null（按不存在处理） */
    private KnowledgeBase mustReadable(String id) {
        KnowledgeBase kb = kbService.get(id);
        if (kb == null) return null;
        if (kb.getBuiltin() != null && kb.getBuiltin() == 1
                && !roleService.isAdminAny(RequestUser.roles())) return null;
        if (!visibility.canRead(principal(), kb.getCreatedBy())) return null;
        return kb;
    }

    /** 对外精简视图：只给集成需要的字段，解析/检索参数原文与库内配置不外泄 */
    private Map<String, Object> kbBrief(Map<String, Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", raw.get("id"));
        m.put("name", raw.get("name"));
        m.put("description", raw.get("description"));
        m.put("embeddingRef", raw.get("embeddingRef"));
        m.put("docCount", raw.get("docCount"));
        m.put("isDefault", raw.get("isDefault"));
        m.put("createTime", raw.get("createTime"));
        m.put("updateTime", raw.get("updateTime"));
        return m;
    }

    private Map<String, Object> toMap(KnowledgeBase kb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kb.getId());
        m.put("name", kb.getName());
        m.put("description", kb.getDescription());
        m.put("embeddingRef", kb.getEmbeddingRef());
        m.put("isDefault", kb.getIsDefault());
        m.put("builtin", kb.getBuiltin());
        m.put("createTime", kb.getCreateTime());
        m.put("updateTime", kb.getUpdateTime());
        return m;
    }

    private Map<String, Object> docBrief(AiDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("kbId", d.getKbId());
        m.put("fileName", d.getFileName());
        m.put("fileType", d.getFileType());
        m.put("status", d.getStatus());
        m.put("statusText", statusText(d.getStatus()));
        m.put("chunkCount", d.getChunkCount());
        m.put("parseProgress", d.getParseProgress());
        m.put("parseDesc", d.getParseDesc());
        m.put("failReason", d.getFailReason());
        m.put("fileSize", d.getFileSize());
        m.put("description", d.getDescription());
        m.put("tags", d.getTags());
        m.put("version", d.getVersion());
        m.put("createTime", d.getCreateTime());
        m.put("updateTime", d.getUpdateTime());
        return m;
    }

    private static String statusText(Integer status) {
        if (status == null) return "未知";
        return switch (status) {
            case 0 -> "生效";
            case 1 -> "已弃用";
            case 2 -> "解析中";
            case 3 -> "解析失败";
            default -> "未知";
        };
    }
}
