package com.wenqu.ai.service;

import com.wenqu.ai.mapper.AiDocumentMapper;
import com.wenqu.ai.model.AiDocument;
import com.wenqu.ai.model.KnowledgeBase;
import org.springframework.stereotype.Component;

/**
 * 引用来源类型：官方 / 外部 / 用户上传 / 内部。
 * 内部与外部的划分按库归属口径（与库级 ACL 三档对齐）：库内文档一律内部，只有网页导入算外部。
 */
@Component
public class SourceTypeResolver {

    public static final String OFFICIAL = "official";
    public static final String EXTERNAL = "external";
    public static final String UPLOAD = "upload";
    public static final String INTERNAL = "internal";

    private final KnowledgeBaseService knowledgeBaseService;
    private final AiDocumentMapper documentMapper;

    public SourceTypeResolver(KnowledgeBaseService knowledgeBaseService, AiDocumentMapper documentMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentMapper = documentMapper;
    }

    public String resolve(String kbId, String docId) {
        KnowledgeBase kb = kbId == null || kbId.isBlank() ? null : knowledgeBaseService.get(kbId);
        if (kb != null && (Integer.valueOf(1).equals(kb.getBuiltin())
                || "system".equals(kb.getCreatedBy()))) {
            return OFFICIAL;
        }
        AiDocument doc = docId == null || docId.isBlank() ? null : documentMapper.selectById(docId);
        if (doc != null) {
            String fileType = doc.getFileType() == null ? "" : doc.getFileType().toLowerCase();
            if ("url".equals(fileType) || (doc.getSourceUrl() != null && !doc.getSourceUrl().isBlank())) {
                return EXTERNAL;
            }
            if (doc.getCreatedBy() != null && !doc.getCreatedBy().isBlank()
                    && !"system".equals(doc.getCreatedBy())) {
                return UPLOAD;
            }
        }
        return INTERNAL;
    }
}
