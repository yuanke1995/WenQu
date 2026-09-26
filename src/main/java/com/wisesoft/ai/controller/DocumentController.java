package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.DocumentService;
import com.wisesoft.ai.service.RateLimitService;
import com.wisesoft.ai.util.RequestUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理控制器
 * 支持：单/批量上传（异步解析）、列表、删除、启停用、重解析
 * 文档为共享知识库（不做用户隔离）；管理操作记录操作者（登录用户 uid）便于审计追溯
 *
 * @author yuanke
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/document")
@RequiredArgsConstructor
@Tag(name = "文档管理", description = "文档上传/解析、列表、启停用、重解析、批量操作、命中统计")
public class DocumentController {

    private final DocumentService documentService;
    private final ConfigService configService;
    private final RateLimitService rateLimitService;
    private final com.wisesoft.ai.service.ResourceVisibilityService visibility;
    private final com.wisesoft.ai.service.KnowledgeBaseService kbService;

    // ==================== 资源级权限（普通用户自建自管，管理员全量） ====================

    private boolean admin() {
        return com.wisesoft.ai.service.AuthService.isAdminRole(RequestUser.role());
    }

    private com.wisesoft.ai.service.ResourceVisibilityService.Principal principal() {
        return new com.wisesoft.ai.service.ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    /** 文档管理权：普通用户=创建者（文档 share_config 的 manage 命中亦可）；库语义 KNOWLEDGE_BASE */
    private void requireDocManage(com.wisesoft.ai.model.AiDocument doc) {
        if (admin()) return;
        if (doc == null) throw new BizException("文档不存在");
        if (!visibility.canManage(principal(), doc.getShareConfig(), doc.getCreatedBy(),
                com.wisesoft.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
            throw new BizException("仅可管理自己上传或被授权管理的文档");
        }
    }

    /** 文档可读：文档自身 + 其所属库都在共享范围内（不可见按不存在处理，不泄露存在性） */
    private void requireDocRead(com.wisesoft.ai.model.AiDocument doc) {
        if (doc == null) throw new BizException("文档不存在");
        if (admin()) return;
        var p = principal();
        var kind = com.wisesoft.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE;
        if (!visibility.canRead(p, doc.getShareConfig(), doc.getCreatedBy(), kind)) {
            throw new BizException("文档不存在");
        }
        if (doc.getKbId() != null) {
            var kb = kbService.get(doc.getKbId());
            if (kb == null || !visibility.canRead(p, kb.getShareConfig(), kb.getCreatedBy(), kind)) {
                throw new BizException("文档不存在");
            }
        }
    }

    /** 目标库管理权（上传/移动的落点校验；kbId 空=默认库，同样按其库配置判定） */
    private void requireKbManage(String kbId) {
        if (admin()) return;
        String id = (kbId == null || kbId.isBlank()) ? kbService.defaultId() : kbId;
        var kb = id == null ? null : kbService.get(id);
        if (kb == null || !visibility.canManage(principal(), kb.getShareConfig(), kb.getCreatedBy(),
                com.wisesoft.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
            throw new BizException("仅可向自己创建或被授权管理的知识库上传文档");
        }
    }

    /** 上传大小业务校验（DB 配置 upload.maxFileSize，保存即生效；multipart 物理上限由容器兜底） */
    private void checkUploadSize(MultipartFile file) {
        long limit = configService.getLong("upload.maxFileSize");
        if (limit > 0 && file.getSize() > limit) {
            throw new BizException("文件大小超过上限 " + fmtSize(limit) + "!");
        }
    }

    private String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.0fMB", bytes / (1024.0 * 1024));
    }

    @Operation(summary = "上传文档", description = "上传单个文档（docx/pdf/xlsx），异步解析，返回文档 ID 和解析状态；按用户限频（ratelimit.uploadPerMinute）")
    @PostMapping("/upload")
    public ResultJson upload(
            @Parameter(description = "文档文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "文档描述（可选）") @RequestParam(value = "description", required = false) String description,
            @Parameter(description = "目标知识库（可选；空=默认知识库）") @RequestParam(value = "kbId", required = false) String kbId,
            HttpServletRequest httpRequest) throws Exception {
        rateLimitService.checkRateLimit("upload", rateIdentity(httpRequest));
        checkUploadSize(file);
        if (description != null && description.length() > 500) {
            throw new BizException("文档描述过长（最多 500 字）");
        }
        requireKbManage(kbId);   // 往哪个库传，就要对这个库有管理权
        var doc = documentService.upload(file, description, kbId);
        log.info("[AUDIT] 上传文档 operator={} docId={} file={} size={}", RequestUser.uid(),
                doc.getId(), file.getOriginalFilename(), file.getSize());
        return ResultJson.ok(doc, "已提交解析");
    }

    @Operation(summary = "批量上传文档", description = "批量上传多个文档，逐个提交异步解析，返回每个文件的上传结果；按用户限频（ratelimit.uploadPerMinute）")
    @PostMapping("/upload/batch")
    public ResultJson uploadBatch(
            @Parameter(description = "文档文件列表") @RequestParam("file") MultipartFile[] files,
            @Parameter(description = "批量描述（可选，应用到所有文件）") @RequestParam(value = "description", required = false) String description,
            @Parameter(description = "目标知识库（可选；空=默认知识库）") @RequestParam(value = "kbId", required = false) String kbId,
            HttpServletRequest httpRequest) {
        rateLimitService.checkRateLimit("upload", rateIdentity(httpRequest));
        if (description != null && description.length() > 500) {
            throw new BizException("文档描述过长（最多 500 字）");
        }
        requireKbManage(kbId);   // 批量上传同样先校验目标库
        List<Map<String, Object>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", file.getOriginalFilename());
            try {
                checkUploadSize(file);
                var doc = documentService.upload(file, (description == null || description.isBlank()) ? null : description, kbId);
                item.put("docId", doc.getId());
                item.put("success", true);
                item.put("msg", "已提交解析");
            } catch (Exception e) {
                item.put("success", false);
                item.put("msg", e.getMessage());
            }
            results.add(item);
        }
        log.info("[AUDIT] 批量上传 operator={} count={}", RequestUser.uid(), files.length);
        return ResultJson.ok(results);
    }

    @Operation(summary = "文档列表", description = "获取文档列表（含解析状态、分块数、文件大小等）；"
            + "kbId 传知识库 ID 时只返回该库文档（默认库含 kb_id 为空的历史文档），不传返回全部；"
            + "普通用户仅返回共享范围内可见的库与文档")
    @GetMapping("/list")
    public ResultJson list(@Parameter(description = "知识库 ID（可选）") @RequestParam(value = "kbId", required = false) String kbId) {
        List<com.wisesoft.ai.model.AiDocument> docs = documentService.list(kbId);
        if (admin()) return ResultJson.ok(docs);
        // 普通用户：库可见 + 文档自身可见，双重过滤（库不可见时按空列表处理，不泄露存在性）
        var p = principal();
        var kind = com.wisesoft.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE;
        if (kbId != null && !kbId.isBlank()) {
            var kb = kbService.get(kbId);
            if (kb == null || !visibility.canRead(p, kb.getShareConfig(), kb.getCreatedBy(), kind)) {
                return ResultJson.ok(List.of());
            }
        }
        var visibleKbIds = new java.util.HashSet<String>();
        for (var kb : kbService.list()) {
            if (visibility.canRead(p, kb.getShareConfig(), kb.getCreatedBy(), kind)) visibleKbIds.add(kb.getId());
        }
        List<com.wisesoft.ai.model.AiDocument> out = docs.stream()
                .filter(d -> visibility.canRead(p, d.getShareConfig(), d.getCreatedBy(), kind))
                // kb_id 为空=默认库语义（与检索侧一致）；连默认库都不存在时保守过滤掉
                .filter(d -> {
                    String kid = d.getKbId() == null ? kbService.defaultId() : d.getKbId();
                    return kid != null && visibleKbIds.contains(kid);
                })
                .toList();
        return ResultJson.ok(out);
    }

    @Operation(summary = "下载源文件", description = "取回上传的原始文件（个人文件区：备份/本地查看用）")
    @GetMapping("/{id}/source")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadSource(
            @Parameter(description = "文档 ID") @PathVariable("id") String id) {
        requireDocRead(documentService.getDoc(id));   // 共享范围内才可取源文件
        DocumentService.SourceFile sf = documentService.sourceFileForDownload(id);
        // 文件名 URL 编码（RFC 5987）：中文名在 Content-Disposition 里必须编码，否则部分客户端乱码
        String encoded = java.net.URLEncoder.encode(sf.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
                .body(new org.springframework.core.io.FileSystemResource(sf.path()));
    }

    @Operation(summary = "删除文档", description = "删除指定文档（同时清理向量、知识块、图片、源文件）")
    @DeleteMapping("/{id}")
    public ResultJson delete(
            @Parameter(description = "文档 ID") @PathVariable("id") String id,
            HttpServletRequest httpRequest) {
        requireDocManage(documentService.getDoc(id));
        documentService.delete(id);
        log.info("[AUDIT] 删除文档 operator={} docId={}", RequestUser.uid(), id);
        return ResultJson.ok("删除成功");
    }

    @Operation(summary = "启停用文档", description = "设置文档状态：0=生效（参与检索），1=弃用（不参与检索）")
    @PutMapping("/{id}/status")
    public ResultJson updateStatus(
            @Parameter(description = "文档 ID") @PathVariable("id") String id,
            @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null) throw new BizException("缺少 status 参数");
        requireDocManage(documentService.getDoc(id));
        documentService.updateStatus(id, status);
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "设置文档共享范围", description = "写入 share_config（JSON，snake_case：read_scope/manage_scope 各含 access_level/global|department|user、department_ids、user_uids）。空串=恢复全局可见。写入时强校验 manage⊆read")
    @PutMapping("/{id}/share")
    public ResultJson updateShare(
            @Parameter(description = "文档 ID") @PathVariable("id") String id,
            @Parameter(description = "{\"shareConfig\": \"...\"}（空串=全局）")
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {
        String shareConfig = body.get("shareConfig");
        requireDocManage(documentService.getDoc(id));
        documentService.updateShareConfig(id, shareConfig, RequestUser.uid());
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "重解析文档", description = "复用源文件重新解析+向量化，适用于文档内容更新后重新入库")
    @PostMapping("/{id}/reparse")
    public ResultJson reparse(
            @Parameter(description = "文档 ID") @PathVariable("id") String id) {
        requireDocManage(documentService.getDoc(id));
        documentService.reparse(id);
        return ResultJson.ok("已重新提交解析");
    }

    @Operation(summary = "补齐图片描述", description = "对解析时未描述成功的图片后台补描述并回写知识块（重新向量化+索引同步）")
    @PostMapping("/{id}/backfill-descriptions")
    public ResultJson backfillDescriptions(
            @Parameter(description = "文档 ID") @PathVariable("id") String id) {
        requireDocManage(documentService.getDoc(id));
        documentService.backfillImageDescriptions(id);
        return ResultJson.ok("已提交图片描述补齐任务");
    }

    @Operation(summary = "批量删除文档", description = "批量删除多个文档")
    @PostMapping("/batch/delete")
    public ResultJson batchDelete(
            @Parameter(description = "{\"ids\": [\"docId1\", \"docId2\"]}")
            @RequestBody Map<String, List<String>> body,
            HttpServletRequest httpRequest) {
        List<String> ids = body.getOrDefault("ids", List.of());
        // 逐个校验管理权：无权限的文档跳过并在结果中说明，不让批量操作变成越权通道
        List<String> allowed = new ArrayList<>();
        for (String docId : ids) {
            if (admin()) { allowed.add(docId); continue; }
            var doc = documentService.getDoc(docId);
            if (doc != null && visibility.canManage(principal(), doc.getShareConfig(), doc.getCreatedBy(),
                    com.wisesoft.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
                allowed.add(docId);
            } else {
                log.warn("[AUDIT] 批量删除跳过无权限文档 operator={} docId={}", RequestUser.uid(), docId);
            }
        }
        documentService.batchDelete(allowed);
        log.info("[AUDIT] 批量删除文档 operator={} count={} ids={}", RequestUser.uid(), allowed.size(), allowed);
        return ResultJson.ok("删除成功（" + allowed.size() + "/" + ids.size() + "，无权限的已跳过）");
    }

    @Operation(summary = "批量重解析", description = "批量复用源文件重新解析+向量化，逐个返回结果（解析中的文档跳过并提示）")
    @PostMapping("/batch/reparse")
    public ResultJson batchReparse(
            @Parameter(description = "{\"ids\": [\"docId1\", \"docId2\"]}")
            @RequestBody Map<String, List<String>> body,
            HttpServletRequest httpRequest) {
        List<String> ids = body.getOrDefault("ids", List.of());
        List<Map<String, Object>> results = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            try {
                requireDocManage(documentService.getDoc(id));
                documentService.reparse(id);
                item.put("success", true);
                item.put("msg", "已提交解析");
            } catch (Exception e) {
                item.put("success", false);
                item.put("msg", e.getMessage());
            }
            results.add(item);
        }
        long ok = results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count();
        log.info("[AUDIT] 批量重解析 operator={} total={} ok={}", RequestUser.uid(), ids.size(), ok);
        return ResultJson.ok(results, "已提交 " + ok + "/" + ids.size() + " 个重解析");
    }

    @Operation(summary = "批量启停用", description = "批量设置多个文档的启用/弃用状态")
    @PostMapping("/batch/status")
    public ResultJson batchStatus(
            @Parameter(description = "{\"ids\": [\"docId1\"], \"status\": 0|1}")
            @RequestBody Map<String, Object> body) {
        Object idsObj = body.get("ids");
        List<String> ids = idsObj instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        int status = body.get("status") == null ? 0 : Integer.parseInt(String.valueOf(body.get("status")));
        // 逐个校验管理权：无权限的文档跳过（批量启停用不该成为越权通道）
        List<String> allowed = new ArrayList<>();
        for (String docId : ids) {
            if (admin()) { allowed.add(docId); continue; }
            var doc = documentService.getDoc(docId);
            if (doc != null && visibility.canManage(principal(), doc.getShareConfig(), doc.getCreatedBy(),
                    com.wisesoft.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
                allowed.add(docId);
            }
        }
        documentService.batchUpdateStatus(allowed, status);
        return ResultJson.ok("操作成功（" + allowed.size() + "/" + ids.size() + "，无权限的已跳过）");
    }

    @Operation(summary = "文档命中统计", description = "从问答日志聚合各文档的命中次数（跨资源视图，仅管理员）")
    @GetMapping("/stats")
    public ResultJson stats() {
        if (!admin()) throw new BizException("仅管理员可查看文档命中统计");
        return ResultJson.ok(documentService.statsHitCounts());
    }

    @Operation(summary = "文档版本列表", description = "获取文档的历史版本列表（倒序）；共享范围内可见")
    @GetMapping("/{id}/versions")
    public ResultJson versions(
            @Parameter(description = "文档 ID") @PathVariable("id") String id) {
        requireDocRead(documentService.getDoc(id));
        return ResultJson.ok(documentService.listVersions(id));
    }

    @Operation(summary = "回滚文档版本", description = "回滚到指定版本：重建该版本的知识块与向量（历史引用仍可溯源）")
    @PostMapping("/{id}/rollback")
    public ResultJson rollback(
            @Parameter(description = "文档 ID") @PathVariable("id") String id,
            @Parameter(description = "{\"version\": 2}") @RequestBody Map<String, Integer> body,
            HttpServletRequest httpRequest) {
        Integer version = body.get("version");
        if (version == null) throw new BizException("缺少 version 参数");
        requireDocManage(documentService.getDoc(id));
        documentService.rollback(id, version);
        log.info("[AUDIT] 回滚文档版本 operator={} docId={} version={}", RequestUser.uid(), id, version);
        return ResultJson.ok("回滚成功");
    }

    /** 限流维度标识：有用户身份用 user:xxx，否则落到 IP 维度 */
    private String rateIdentity(HttpServletRequest request) {
        String uid = RequestUser.uid();
        if (!RequestUser.ANONYMOUS.equals(uid)) return "user:" + uid;
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        return "ip:" + ip;
    }
}