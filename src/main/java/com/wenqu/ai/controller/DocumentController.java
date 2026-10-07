package com.wenqu.ai.controller;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.dto.ResultJson;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.DocumentService;
import com.wenqu.ai.service.RateLimitService;
import com.wenqu.ai.util.RequestUser;
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
    private final com.wenqu.ai.service.ResourceVisibilityService visibility;
    private final com.wenqu.ai.service.KnowledgeBaseService kbService;
    private final com.wenqu.ai.service.RoleService roleService;
    /** 向量索引注册中心：运维端点用它做索引诊断（ACL 字段就绪 / 存量标签回填状态） */
    private final com.wenqu.ai.service.KbVectorStoreRegistry kbVectorStores;

    // ==================== 资源级权限（自建自管，数据按 userId 隔离，无角色直通） ====================

    private boolean admin() {
        return roleService.isAdminCode(RequestUser.role());
    }

    private com.wenqu.ai.service.ResourceVisibilityService.Principal principal() {
        return new com.wenqu.ai.service.ResourceVisibilityService.Principal(
                RequestUser.uid(), RequestUser.departmentId(), RequestUser.role());
    }

    /** 文档管理权：创建者（文档 share_config 的 manage 命中亦可）；库语义 KNOWLEDGE_BASE */
    private void requireDocManage(com.wenqu.ai.model.AiDocument doc) {
        if (doc == null) throw new BizException("文档不存在");
        if (!visibility.canManage(principal(), doc.getShareConfig(), doc.getCreatedBy(),
                com.wenqu.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
            throw new BizException("仅可管理自己上传或被授权管理的文档");
        }
    }

    /**
     * 文档可读：所属库可见（个人默认库=归属人私有），且文档显式配置了共享时按配置判
     * （未配置=跟随库——谁建归谁语义下，文档分享是显式例外）。不可见按不存在处理，不泄露存在性。
     */
    private void requireDocRead(com.wenqu.ai.model.AiDocument doc) {
        if (doc == null) throw new BizException("文档不存在");
        var p = principal();
        var kind = com.wenqu.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE;
        if (!visibility.canReadDocFollowKb(p, doc.getShareConfig(), doc.getCreatedBy())) {
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
        String id = (kbId == null || kbId.isBlank()) ? kbService.defaultId(RequestUser.uid()) : kbId;
        var kb = id == null ? null : kbService.get(id);
        // 官方内置库在权限上本就无人可管（createdBy=system），这里给出专属原因，避免误读成权限配置问题
        if (kb != null && kb.getBuiltin() != null && kb.getBuiltin() == 1) {
            throw new BizException("「" + kb.getName() + "」为官方内置知识库，内容由系统随版本同步，不接受上传");
        }
        if (kb == null || !visibility.canManage(principal(), kb.getShareConfig(), kb.getCreatedBy(),
                com.wenqu.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
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

    @Operation(summary = "上传文档", description = "上传单个文档（docx/pdf/xlsx），异步解析，返回文档 ID 和解析状态；按用户限频（ratelimit.uploadPerMinute）。visionRef 可选：文档级视觉模型覆盖（引用 {providerId}/{modelId}），对该文档所有图片理解生效；空=跟随知识库")
    @PostMapping("/upload")
    public ResultJson upload(
            @Parameter(description = "文档文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "文档描述（可选）") @RequestParam(value = "description", required = false) String description,
            @Parameter(description = "目标知识库（可选；空=默认知识库）") @RequestParam(value = "kbId", required = false) String kbId,
            @Parameter(description = "文档级视觉模型覆盖（可选；空=跟随知识库）") @RequestParam(value = "visionRef", required = false) String visionRef,
            HttpServletRequest httpRequest) throws Exception {
        rateLimitService.checkRateLimit("upload", rateIdentity(httpRequest));
        checkUploadSize(file);
        if (description != null && description.length() > 500) {
            throw new BizException("文档描述过长（最多 500 字）");
        }
        requireKbManage(kbId);   // 往哪个库传，就要对这个库有管理权
        var doc = documentService.upload(file, description, kbId, visionRef);
        log.info("[AUDIT] 上传文档 operator={} docId={} file={} size={}", RequestUser.uid(),
                doc.getId(), file.getOriginalFilename(), file.getSize());
        return ResultJson.ok(doc, "已提交解析");
    }

    @Operation(summary = "网页 URL 导入", description = "抓取网页 HTML 快照入库（file_type=url），异步走与上传相同的解析/向量化链路；"
            + "仅支持 http/https 公网地址，重定向逐跳校验（防内网探测）；同名页面重复导入按替换语义走增量重解析")
    @PostMapping("/import-url")
    public ResultJson importUrl(
            @Parameter(description = "网页 URL") @RequestParam("url") String url,
            @Parameter(description = "文档描述（可选）") @RequestParam(value = "description", required = false) String description,
            @Parameter(description = "目标知识库（可选；空=默认知识库）") @RequestParam(value = "kbId", required = false) String kbId,
            HttpServletRequest httpRequest) throws Exception {
        rateLimitService.checkRateLimit("upload", rateIdentity(httpRequest));
        if (url == null || url.isBlank()) throw new BizException("URL 不能为空");
        if (description != null && description.length() > 500) {
            throw new BizException("文档描述过长（最多 500 字）");
        }
        requireKbManage(kbId);
        var doc = documentService.importFromUrl(url, description, kbId);
        log.info("[AUDIT] 网页导入 operator={} docId={} url={}", RequestUser.uid(), doc.getId(), url);
        return ResultJson.ok(doc, "已提交解析");
    }

    @Operation(summary = "批量上传文档", description = "批量上传多个文档，逐个提交异步解析，返回每个文件的上传结果；按用户限频（ratelimit.uploadPerMinute）。visionRef 可选：文档级视觉模型覆盖（应用到所有文件；空=跟随知识库）")
    @PostMapping("/upload/batch")
    public ResultJson uploadBatch(
            @Parameter(description = "文档文件列表") @RequestParam("file") MultipartFile[] files,
            @Parameter(description = "批量描述（可选，应用到所有文件）") @RequestParam(value = "description", required = false) String description,
            @Parameter(description = "目标知识库（可选；空=默认知识库）") @RequestParam(value = "kbId", required = false) String kbId,
            @Parameter(description = "文档级视觉模型覆盖（可选，应用到所有文件；空=跟随知识库）") @RequestParam(value = "visionRef", required = false) String visionRef,
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
                var doc = documentService.upload(file, (description == null || description.isBlank()) ? null : description, kbId, visionRef);
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

    @Operation(summary = "解析队列状态", description = "当前解析队列的排队数/执行数/终态数（前端展示「排队 N / 执行 M」，并对齐轮询）；"
            + "kbId 传知识库 ID 时只统计该库的任务（文档管理页页头用，默认库含 kb_id 为空的历史任务），不传=全平台")
    @GetMapping("/queue/stats")
    public ResultJson queueStats(@Parameter(description = "知识库 ID（可选）") @RequestParam(value = "kbId", required = false) String kbId) {
        return ResultJson.ok(documentService.queueStats(kbId), "解析队列状态");
    }

    @Operation(summary = "文档列表", description = "获取文档列表（含解析状态、分块数、文件大小等）；"
            + "kbId 传知识库 ID 时只返回该库文档（默认库含 kb_id 为空的历史文档），不传返回全部；"
            + "普通用户仅返回自己创建的、默认库与共享范围内的库及其中文档")
    @GetMapping("/list")
    public ResultJson list(@Parameter(description = "知识库 ID（可选）") @RequestParam(value = "kbId", required = false) String kbId) {
        List<com.wenqu.ai.model.AiDocument> docs = documentService.list(kbId);
        // 所有人：库可见（个人默认库=归属人私有）+ 文档显式共享判定（未配置=跟随库），双重过滤（库不可见时按空列表处理，不泄露存在性）
        var p = principal();
        var kind = com.wenqu.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE;
        if (kbId != null && !kbId.isBlank()) {
            var kb = kbService.get(kbId);
            if (kb == null || !visibility.canRead(p, kb.getShareConfig(), kb.getCreatedBy(), kind)) {
                return ResultJson.ok(List.of());
            }
        }
        var visibleKbIds = new java.util.HashSet<String>();
        for (var kb : kbService.list()) {
            if (visibility.canRead(p, kb.getShareConfig(), kb.getCreatedBy(), kind)) {
                visibleKbIds.add(kb.getId());
            }
        }
        List<com.wenqu.ai.model.AiDocument> out = docs.stream()
                .filter(d -> visibility.canReadDocFollowKb(p, d.getShareConfig(), d.getCreatedBy()))
                // kb_id 为空=默认库语义（与检索侧一致）；连默认库都不存在时保守过滤掉
                .filter(d -> {
                    String kid = d.getKbId() == null ? kbService.defaultId(RequestUser.uid()) : d.getKbId();
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

    @Operation(summary = "设置网页源自动刷新", description = "为 file_type=url 的文档配置自动刷新开关与 cron（5段，如 0 3 * * *）；" +
            "开启后由调度中心按 cron 重新抓网重建，关闭则清空下次刷新时刻")
    @PutMapping("/{id}/refresh-config")
    public ResultJson refreshConfig(
            @Parameter(description = "文档 ID") @PathVariable("id") String id,
            @Parameter(description = "autoRefresh: 0/1") @RequestParam("autoRefresh") int autoRefresh,
            @Parameter(description = "refreshCron（5段，开启时必填）") @RequestParam(value = "refreshCron", required = false) String refreshCron) {
        requireDocManage(documentService.getDoc(id));
        documentService.setRefreshConfig(id, autoRefresh, refreshCron);
        return ResultJson.ok("已更新自动刷新配置");
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
            var doc = documentService.getDoc(docId);
            if (doc != null && visibility.canManage(principal(), doc.getShareConfig(), doc.getCreatedBy(),
                    com.wenqu.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
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
            var doc = documentService.getDoc(docId);
            if (doc != null && visibility.canManage(principal(), doc.getShareConfig(), doc.getCreatedBy(),
                    com.wenqu.ai.service.ResourceVisibilityService.ResourceKind.KNOWLEDGE_BASE)) {
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

    @Operation(summary = "向量索引诊断", description = "诊断某知识库向量索引：ACL 可过滤字段是否就绪、存量向量是否已带 ACL 标签"
            + "（两者含义不同——字段齐备≠标签已回填，FT.ALTER 只改 schema 不动历史 JSON）。仅管理员")
@GetMapping("/vector-diagnose")
public ResultJson vectorDiagnose(
        @Parameter(description = "知识库 ID") @RequestParam("kbId") String kbId) {
    if (!admin()) throw new BizException("仅管理员可查看向量索引诊断");
    return ResultJson.ok(kbVectorStores.diagnose(kbId));
}

@Operation(summary = "同模型重建库向量", description = "不换向量模型，DROP 该库索引后按当前绑定重新向量化写回（异步）。"
            + "用途：存量向量补 ACL 标签（让检索期过滤下推生效）、修复被动过的索引 schema。仅管理员")
@PostMapping("/rebuild-kb")
public ResultJson rebuildKb(
        @Parameter(description = "知识库 ID") @RequestParam("kbId") String kbId) {
    if (!admin()) throw new BizException("仅管理员可重建库向量");
    var kb = kbService.get(kbId);
    if (kb == null) throw new BizException("知识库不存在");
    if (kb.getEmbeddingRef() == null || kb.getEmbeddingRef().isBlank()) {
        throw new BizException("该知识库未绑定向量模型，无法重建");
    }
    documentService.rebuildKbVectorsAsync(kbId);
    return ResultJson.ok("已提交重建任务（异步执行，耗时取决于块数与嵌入模型速度）");
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