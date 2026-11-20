package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.util.FileNameSanitizer;
import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.Knowledge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文档源文件与图片文件的落盘、取回与清理。
 * <p>
 * 从 {@link DocumentService} 抽出：只做文件系统操作 + 按 docId 取引用关系，不参与解析流程与状态机，
 * 因此可被解析链路、回收站彻底删除、孤儿清扫三处复用。
 * 目录约定：源文件 data/files/{docId}/{fileName}，图片 data/images/{docId}/。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentFileStore {

    private final AppProperties properties;
    private final AiDocumentMapper documentMapper;
    private final KnowledgeMapper knowledgeMapper;

    public Path saveSourceFile(MultipartFile file, String docId, String fileName) throws IOException {
        Path dir = Paths.get(properties.getImages().getDir(), "files", docId);
        Files.createDirectories(dir);
        Path target = dir.resolve(sanitize(fileName));
        file.transferTo(target.toFile());
        return target;
    }

    /** 源文件（下载用）：文件名 + 磁盘路径 */
    public record SourceFile(String fileName, Path path) {
    }

    /**
     * 取源文件供下载（个人文件区：用户上传的原始文件可取回）。
     * 文档不存在或源文件缺失（旧数据未保留）时抛可读业务异常。
     */
    public SourceFile sourceFileForDownload(String docId) {
        AiDocument doc = documentMapper.selectById(docId);
        if (doc == null) throw new BizException("文档不存在");
        Path p = sourceFile(docId, doc.getFileName());
        if (!Files.exists(p)) throw new BizException("源文件缺失（旧数据可能未保留源文件，请重新上传该文档）");
        return new SourceFile(doc.getFileName(), p);
    }

    public Path sourceFile(String docId, String fileName) {
        return Paths.get(properties.getImages().getDir(), "files", docId, sanitize(fileName));
    }

    public void cleanupSourceFile(String docId) {
        Path dir = Paths.get(properties.getImages().getDir(), "files", docId);
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            // fail-loud：清理失败升级 error（遗留磁盘文件属数据一致性风险）
            log.error("[FAIL-LOUD] 清理源文件目录失败: {}", e.getMessage());
        }
    }

    public void cleanupImages(String docId) {
        Path dir = Paths.get(properties.getImages().getDir(), "images", docId);
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.error("[FAIL-LOUD] 清理图片目录失败: {}", e.getMessage());
        }
    }

    /**
     * 孤儿图片清扫：删除 data/images/{docId}/ 下未被任何剩余知识块 images 字段引用的文件
     * （内容寻址文件名下，被删图/变更图的旧文件在这里回收；未变图片保留供复用块引用，保证 URL 稳定）
     *
     * @return true=正常完成（含无孤儿）；false=清扫失败（fail-loud：调用方把失败写进终态 desc）
     */
    public boolean sweepOrphanImages(String docId) {
        Path dir = Paths.get(properties.getImages().getDir(), "images", docId);
        if (!Files.exists(dir)) return true;
        try {
            List<Knowledge> blocks = knowledgeMapper.selectList(
                    new LambdaQueryWrapper<Knowledge>().eq(Knowledge::getDocId, docId));
            Set<String> referenced = new HashSet<>();
            for (Knowledge b : blocks) {
                if (b.getImages() == null || b.getImages().isBlank()) continue;
                try {
                    JSON.parseArray(b.getImages(), String.class).forEach(u -> {
                        String fn = u.substring(u.lastIndexOf('/') + 1);
                        if (!fn.isBlank()) referenced.add(fn);
                    });
                } catch (Exception ignored) {
                }
            }
            try (var stream = Files.list(dir)) {
                stream.filter(p -> Files.isRegularFile(p) && !referenced.contains(p.getFileName().toString()))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                log.warn("[{}] 孤儿图片删除失败: {}", docId, e.getMessage());
                            }
                        });
            }
            return true;
        } catch (IOException e) {
            log.error("[FAIL-LOUD] [{}] 孤儿图片清扫失败: {}", docId, e.getMessage());
            return false;
        }
    }

    /** 文件名清洗：非法字符、Windows 保留名、尾部点/空格、长度统一由 FileNameSanitizer 负责。 */
    public String sanitize(String fileName) {
        return FileNameSanitizer.sanitize(fileName);
    }
}
