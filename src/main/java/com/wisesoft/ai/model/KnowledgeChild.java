package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 子块（父子分块索引）
 * <p>
 * parse.childEnabled 开启时，超长知识块（父块）被确定性切成小子块，按子块向量化写入
 * 所属库向量索引（vector id = 本表 id）；命中子块后经 knowledge_id 取回父块完整正文进
 * 上下文与引用——小块召回精度高、大块上下文完整（父块仍存 c_ai_knowledge，本表只是索引）。
 * 行持久化与 QA 同理：重嵌/迁移可从本表恢复向量，且 RedisVectorStore 可能丢 metadata，
 * 命中解析以表为准。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_knowledge_child")
public class KnowledgeChild {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属文档ID */
    private String docId;

    /** 父块ID（命中后取回该块 title/content 进上下文与引用） */
    private String knowledgeId;

    /** 子块正文（向量化索引文本） */
    private String content;

    /** 在父块内的切片序号 */
    private Integer chunkSeq;

    /** 创建时间 */
    private LocalDateTime createTime;
}
