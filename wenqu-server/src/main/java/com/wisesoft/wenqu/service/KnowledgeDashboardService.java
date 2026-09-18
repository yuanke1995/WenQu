package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.repositories.KnowledgeBaseRepository;
import com.wisesoft.wenqu.repositories.KnowledgeFileRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 知识域 Dashboard 统计用例。
 *
 * <p>由参考实现的 services/knowledge_dashboard_service.py 逐行翻译。
 */
@Service
public class KnowledgeDashboardService {

    /** 文件类型 → 展示名。 */
    static final Map<String, String> FILE_TYPE_MAPPING = Map.ofEntries(
            Map.entry("txt", "文本文件"),
            Map.entry("pdf", "PDF文档"),
            Map.entry("docx", "Word文档"),
            Map.entry("doc", "Word文档"),
            Map.entry("md", "Markdown"),
            Map.entry("html", "HTML网页"),
            Map.entry("htm", "HTML网页"),
            Map.entry("json", "JSON数据"),
            Map.entry("csv", "CSV表格"),
            Map.entry("xlsx", "Excel表格"),
            Map.entry("xls", "Excel表格"),
            Map.entry("pptx", "PowerPoint"),
            Map.entry("ppt", "PowerPoint"),
            Map.entry("png", "PNG图片"),
            Map.entry("jpg", "JPEG图片"),
            Map.entry("jpeg", "JPEG图片"),
            Map.entry("gif", "GIF图片"),
            Map.entry("svg", "SVG图片"),
            Map.entry("mp4", "MP4视频"),
            Map.entry("mp3", "MP3音频"),
            Map.entry("zip", "ZIP压缩包"),
            Map.entry("rar", "RAR压缩包"),
            Map.entry("7z", "7Z压缩包"));

    /** 数据库类型 → 展示名。 */
    static final Map<String, String> DATABASE_TYPE_MAPPING = Map.of(
            "faiss", "FAISS",
            "milvus", "Milvus",
            "dify", "Dify",
            "qdrant", "Qdrant",
            "elasticsearch", "Elasticsearch",
            "unknown", "未知类型");

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeFileRepository knowledgeFileRepository;

    public KnowledgeDashboardService(
            KnowledgeBaseRepository knowledgeBaseRepository, KnowledgeFileRepository knowledgeFileRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeFileRepository = knowledgeFileRepository;
    }

    /** 通过单批 SQL 聚合高效汇总知识库、文件类型与存储大小统计。 */
    public Map<String, Object> getKnowledgeStats() {
        Map<String, Long> databasesByType = new LinkedHashMap<>();
        Map<String, Long> filesByType = new LinkedHashMap<>();
        long totalDatabases = 0;
        long totalFiles = 0;
        long totalNodes = 0;
        long totalStorageSize = 0;

        for (Map.Entry<String, Long> entry : knowledgeBaseRepository.countByType()) {
            String kbType = entry.getKey();
            long count = entry.getValue();
            String dbType = kbType == null ? "" : kbType.toLowerCase();
            String displayType = DATABASE_TYPE_MAPPING.getOrDefault(dbType, kbType == null || kbType.isEmpty() ? "未知类型" : kbType);
            databasesByType.merge(displayType, count, Long::sum);
            totalDatabases += count;
        }

        for (Object[] row : knowledgeFileRepository.aggregateDashboardStats()) {
            String fileType = (String) row[0];
            long count = ((Number) row[1]).longValue();
            long size = ((Number) row[2]).longValue();
            long nodes = ((Number) row[3]).longValue();
            String ext = fileType == null ? "" : fileType.toLowerCase();
            String displayName;
            if (FILE_TYPE_MAPPING.containsKey(ext)) {
                displayName = FILE_TYPE_MAPPING.get(ext);
            } else {
                boolean known = ext != null && !ext.isEmpty() && !"unknown".equals(ext);
                displayName = known ? ext.toUpperCase() + "文件" : "其他";
            }
            filesByType.merge(displayName, count, Long::sum);
            totalFiles += count;
            totalStorageSize += size;
            totalNodes += nodes;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_databases", totalDatabases);
        result.put("total_files", totalFiles);
        result.put("total_nodes", totalNodes);
        result.put("total_storage_size", totalStorageSize);
        result.put("databases_by_type", databasesByType);
        result.put("file_type_distribution", filesByType);
        return result;
    }
}
