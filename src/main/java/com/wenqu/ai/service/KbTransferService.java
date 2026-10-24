package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeBaseMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 知识库导出 / 导入（迁移与备份用）。
 * <p>
 * <b>导出</b>：单库打包为 zip——manifest.json（库元信息 + 文档清单含标签/描述/url 源）+
 * files/ 下各文档的源文件。**只带源料不带产物**（向量/知识块/图片描述不出包）：产物可由源重算，
 * 且向量绑定目标库的向量模型，跨实例环境不同，重放旧向量只会有害。
 * <p>
 * <b>导入</b>：建（或选）目标库后逐文档走既有上传/URL 导入管线（解析/分块/向量化/索引全自动），
 * 向量按目标库绑定的向量模型重新嵌入。同名 upsert 语义与网页上传一致——重复导入同一包是安全的。
 * 解析队列背压兜底：入队失败逐个计数返回，不中断其余文档。
 * <p>
 * 安全：导入 zip 内条目名经 {@link #safeEntryName} 防 Zip Slip 路径穿越；文件大小/个数有上限；
 * 只认 manifest 登记 + files/ 前缀的条目，其余跳过。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbTransferService {

    private static final String MANIFEST = "manifest.json";
    private static final String FILES_DIR = "files/";
    /** 单文档源文件打包上限（与 upload.maxFileSize 无关：导出读盘、导入走 MultipartFile 内存桥） */
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    /** 单包文档数上限（防畸形包撑爆解析队列） */
    private static final int MAX_DOCS = 500;

    private final KnowledgeBaseMapper kbMapper;
    private final AiDocumentMapper documentMapper;
    private final DocumentService documentService;
    private final AppProperties properties;

    /** 管理权/存在性校验后的目标库（导入侧） */
    public KnowledgeBase kbOf(String kbId) {
        KnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null || (kb.getDeleted() != null && kb.getDeleted() == 1)) {
            throw new BizException("知识库不存在或已删除");
        }
        return kb;
    }

    // ==================== 导出 ====================

    /**
     * 导出知识库为 zip 写入输出流（生效文档，含回收站外全部；url 文档不带源文件、带源地址）。
     * 字节流式写，不落盘中转；单个源文件缺失跳过并在 manifest 标 missingSourceFile（导入侧按说明重建）。
     */
    public String exportZip(String kbId, OutputStream out) {
        KnowledgeBase kb = kbOf(kbId);
        List<AiDocument> docs = documentMapper.selectList(new LambdaQueryWrapper<AiDocument>()
                .eq(AiDocument::getKbId, kbId)
                .eq(AiDocument::getDeleted, 0)
                .orderByAsc(AiDocument::getCreateTime));
        int exported = 0;
        int skippedFiles = 0;
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            JSONArray docArr = new JSONArray();
            for (AiDocument d : docs) {
                JSONObject o = new JSONObject(new LinkedHashMap<>());
                o.put("fileName", d.getFileName());
                o.put("fileType", d.getFileType());
                o.put("description", d.getDescription());
                o.put("tags", d.getTags());
                o.put("sourceUrl", d.getSourceUrl());
                o.put("fileSize", d.getFileSize());
                String entry = null;
                if (d.getFileType() != null && !"url".equalsIgnoreCase(d.getFileType())) {
                    Path src = Paths.get(properties.getImages().getDir(), "files", d.getId(), safeFileName(d.getFileName()));
                    if (Files.exists(src) && Files.size(src) <= MAX_FILE_BYTES) {
                        entry = FILES_DIR + d.getId() + "/" + safeFileName(d.getFileName());
                        zip.putNextEntry(new ZipEntry(entry));
                        Files.copy(src, zip);
                        zip.closeEntry();
                    } else {
                        skippedFiles++;
                        o.put("missingSourceFile", true);
                    }
                }
                o.put("entry", entry);
                docArr.add(o);
                exported++;
            }
            JSONObject manifest = new JSONObject(new LinkedHashMap<>());
            manifest.put("format", "wenqu-kb-export");
            manifest.put("version", 1);
            manifest.put("exportedAt", java.time.LocalDateTime.now().toString());
            manifest.put("kb", Map.of(
                    "name", kb.getName() == null ? "" : kb.getName(),
                    "description", kb.getDescription() == null ? "" : kb.getDescription()));
            manifest.put("documents", docArr);
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(JSON.toJSONString(manifest, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat)
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new BizException("导出打包失败：" + e.getMessage());
        }
        log.info("[AUDIT] 知识库导出 operator={} kbId={} docs={} skippedFiles={}",
                com.wenqu.ai.util.RequestUser.uid(), kbId, exported, skippedFiles);
        return "已导出 " + exported + " 个文档" + (skippedFiles > 0 ? "（" + skippedFiles + " 个源文件缺失未打包）" : "");
    }

    // ==================== 导入 ====================

    /**
     * 从导出包导入到目标库（同步逐个提交解析，解析本身异步）：逐文档走既有 upload / importFromUrl
     * 管线。返回逐条结果（成功/失败原因），调用方在响应里透出。
     */
    public Map<String, Object> importZip(MultipartFile file, String kbId) {
        kbOf(kbId);
        if (file == null || file.isEmpty()) throw new BizException("请选择导出包（zip）");
        byte[] manifestRaw = null;
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = safeEntryName(e.getName());
                if (name == null) continue; // 路径穿越/非法条目名：拒绝收录
                if (name.equals(MANIFEST)) {
                    manifestRaw = zip.readAllBytes();
                } else if (name.startsWith(FILES_DIR)) {
                    if (files.size() >= MAX_DOCS) break;
                    files.put(name, zip.readNBytes((int) MAX_FILE_BYTES + 1));
                }
            }
        } catch (IOException e) {
            throw new BizException("读取导出包失败：" + e.getMessage());
        }
        if (manifestRaw == null) throw new BizException("导出包缺少 manifest.json（可能不是问渠导出的知识库包）");
        JSONObject manifest = JSON.parseObject(new String(manifestRaw, StandardCharsets.UTF_8));
        if (!"wenqu-kb-export".equals(manifest.getString("format"))) {
            throw new BizException("不是问渠导出的知识库包（manifest format 不符）");
        }
        JSONArray docs = manifest.getJSONArray("documents");
        if (docs == null || docs.isEmpty()) throw new BizException("导出包内没有文档");

        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        for (int i = 0; i < docs.size(); i++) {
            JSONObject d = docs.getJSONObject(i);
            String fileName = d.getString("fileName");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fileName", fileName);
            try {
                String type = d.getString("fileType");
                if ("url".equalsIgnoreCase(type)) {
                    String url = d.getString("sourceUrl");
                    if (url == null || url.isBlank()) throw new BizException("url 文档缺 sourceUrl");
                    documentService.importFromUrl(url, d.getString("description"), kbId);
                } else {
                    String entry = d.getString("entry");
                    byte[] bytes = entry == null ? null : files.get(entry);
                    if (bytes == null) throw new BizException("源文件缺失（导出时未打包）");
                    if (bytes.length > MAX_FILE_BYTES) throw new BizException("文件超过大小上限");
                    org.springframework.web.multipart.MultipartFile mf =
                            new com.wenqu.ai.util.InMemoryMultipartFile("file", fileName, bytes);
                    AiDocument doc = documentService.upload(mf, d.getString("description"), kbId, null);
                    // 标签导入后落库（upload 不带标签参数，避免动它的签名）
                    String tags = d.getString("tags");
                    if (tags != null && !tags.isBlank()) {
                        documentService.setTags(doc.getId(), tags);
                    }
                }
                ok++;
                item.put("success", true);
            } catch (Exception ex) {
                failed++;
                item.put("success", false);
                item.put("msg", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
            results.add(item);
        }
        log.info("[AUDIT] 知识库导入 operator={} kbId={} ok={} failed={}",
                com.wenqu.ai.util.RequestUser.uid(), kbId, ok, failed);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", docs.size());
        out.put("ok", ok);
        out.put("failed", failed);
        out.put("results", results);
        return out;
    }

    /** zip 条目名安全化：拒绝绝对路径/../ 穿越、盘符与反斜杠；返回 null 表示拒收 */
    private String safeEntryName(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.contains("..") || n.contains(":")) return null;
        return n;
    }

    /** 导出条目文件名清洗（与 DocumentService.sanitize 同规则，目录结构 files/{docId}/{name}） */
    private String safeFileName(String fileName) {
        return (fileName == null ? "unnamed" : fileName).replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
