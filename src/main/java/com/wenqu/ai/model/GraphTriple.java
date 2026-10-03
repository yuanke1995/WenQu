package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GraphRAG 三元组：LLM 从知识块抽取的「主体-关系-客体」，仅收原文明确陈述；
 * chunk_id/doc_id 溯源（检索扩展反查真实块 + 按文档清图）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_graph_triple")
public class GraphTriple {

    @TableId(type = IdType.INPUT)
    private String id;

    private String kbId;

    /** 主体实体 ID */
    private String subjectId;

    /** 关系谓词（如 母公司/位于/成立于） */
    private String predicate;

    /** 客体实体 ID */
    private String objectId;

    /** 溯源知识块 ID */
    private String chunkId;

    /** 溯源文档 ID */
    private String docId;

    private LocalDateTime createTime;
}
