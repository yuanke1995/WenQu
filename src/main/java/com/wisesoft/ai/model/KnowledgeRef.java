package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 知识块引用关系表（交叉引用 1-hop 扩散 + 结构上下文扩展）。
 * 纯派生数据：生命周期完全跟随知识块/文档，重解析按 doc_id 先删后插全量重建，不做逻辑删除。
 * from=A 引用 to=B：命中 A 时可带出 B（出边）；命中 B 时可带出 A（入边，默认关）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_knowledge_ref")
public class KnowledgeRef {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属文档ID（引用只在同文档内有效） */
    private String docId;

    /** 引用来源知识块ID（A） */
    private String fromKnowledgeId;

    /** 被引用目标知识块ID（B） */
    private String toKnowledgeId;

    /** 原文引用表达（如"详见 4.1.2 节"/"参见「数据字典」"） */
    private String refText;

    private LocalDateTime createTime;
}
