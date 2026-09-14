package com.wisesoft.ai.controller;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ResultJson;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.DocumentService;
import com.wisesoft.ai.service.RateLimitService;
import com.wisesoft.ai.util.UserContext;
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
 * 文档为共享知识库（不做用户隔离）；管理操作记录操作者（X-User-Id）便于审计追溯
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
            HttpServletRequest httpRequest) throws Exception {
        rateLimitService.checkRateLimit("upload", rateIdentity(httpRequest));
        checkUploadSize(file);
        if (description != null && description.length() > 500) {
            throw new BizException("文档描述过长（最多 500 字）");
        }
        var doc = documentService.upload(file, description);
        log.info("[AUDIT] 上传文档 operator={} docId={} file={} size={}", UserContext.resolve(httpRequest),
                doc.getId(), file.getOriginalFilename(), file.getSize());
        return ResultJson.ok(doc, "已提交解析");
    }

    @Operation(summary = "批量上传文档", description = "批量上传多个文档，逐个提交异步解析，返回每个文件的上传结果；按用户限频（ratelimit.uploadPerMinute）")
    @PostMapping("/upload/batch")
    public ResultJson uploadBatch(
            @Parameter(description = "文档文件列表") @RequestParam("file") MultipartFile[] files,
            @Parameter(description = "批量描述（可选，应用到所有文件）") @RequestParam(value = "description", required = false) String description,
            HttpServletRequest httpRequest) {
        rateLimitService.checkRateLimit("upload", rateIdentity(httpRequest));
        if (description != null && description.length() > 500) {
            throw new BizException("文档描述过长（最多 500 字）");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (MultipartFile file : files) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", file.getOriginalFilename());
            try {
                checkUploadSize(file);
                var doc = documentService.upload(file, (description == null || description.isBlank()) ? null : description);
                item.put("docId", doc.getId());
                item.put("success", true);
                item.put("msg", "已提交解析");
            } catch (Exception e) {
                item.put("success", false);
                item.put("msg", e.getMessage());
            }
            results.add(item);
        }
        log.info("[AUDIT] 批量上传 operator={} count={}", UserContext.resolve(httpRequest), files.length);
        return ResultJson.ok(results);
    }

    @Operation(summary = "文档列表", description = "获取所有文档列表（含解析状态、分块数、文件大小等）")
    @GetMapping("/list")
    public ResultJson list() {
        return ResultJson.ok(documentService.list());
    }

    @Operation(summary = "下载源文件", description = "取回上传的原始文件（个人文件区：备份/本地查看用）")
    @GetMapping("/{id}/source")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> downloadSource(
            @Parameter(description = "文档 ID") @PathVariable("id") String id) {
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
        documentService.delete(id);
        log.info("[AUDIT] 删除文档 operator={} docId={}", UserContext.resolve(httpRequest), id);
        return ResultJson.ok("删除成功");
    }

    @Operation(summary = "启停用文档", description = "设置文档状态：0=生效（参与检索），1=弃用（不参与检索）")
    @PutMapping("/{id}/status")
    public ResultJson updateStatus(
            @Parameter(description = "文档 ID") @PathVariable("id") String id,
            @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null) throw new BizException("缺少 status 参数");
        documentService.updateStatus(id, status);
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "重解析文档", description = "复用源文件重新解析+向量化，适用于文档内容更新后重新入库")
    @PostMapping("/{id}/reparse")
    public ResultJson reparse(
            @Parameter(description = "文档 ID") @PathVariable("id") String id) {
        documentService.reparse(id);
        return ResultJson.ok("已重新提交解析");
    }

    @Operation(summary = "补齐图片描述", description = "对解析时未描述成功的图片后台补描述并回写知识块（重新向量化+索引同步）")
    @PostMapping("/{id}/backfill-descriptions")
    public ResultJson backfillDescriptions(
            @Parameter(description = "文档 ID") @PathVariable("id") String id) {
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
        documentService.batchDelete(ids);
        log.info("[AUDIT] 批量删除文档 operator={} count={} ids={}", UserContext.resolve(httpRequest), ids.size(), ids);
        return ResultJson.ok("删除成功");
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
        log.info("[AUDIT] 批量重解析 operator={} total={} ok={}", UserContext.resolve(httpRequest), ids.size(), ok);
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
        documentService.batchUpdateStatus(ids, status);
        return ResultJson.ok("操作成功");
    }

    @Operation(summary = "文档命中统计", description = "从问答日志聚合各文档的命中次数")
    @GetMapping("/stats")
    public ResultJson stats() {
        return ResultJson.ok(documentService.statsHitCounts());
    }

    @Operation(summary = "文档版本列表", description = "获取文档的历史版本列表（倒序）")
    @GetMapping("/{id}/versions")
    public ResultJson versions(
            @Parameter(description = "文档 ID") @PathVariable("id") String id) {
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
        documentService.rollback(id, version);
        log.info("[AUDIT] 回滚文档版本 operator={} docId={} version={}", UserContext.resolve(httpRequest), id, version);
        return ResultJson.ok("回滚成功");
    }

    /** 限流维度标识：有用户身份用 user:xxx，否则落到 IP 维度 */
    private String rateIdentity(HttpServletRequest request) {
        String uid = UserContext.resolve(request);
        if (!UserContext.ANONYMOUS.equals(uid)) return "user:" + uid;
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        return "ip:" + ip;
    }
}