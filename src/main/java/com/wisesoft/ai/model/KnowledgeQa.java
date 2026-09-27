package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识块问答对（QA 增强检索）
 * <p>
 * 解析期（parse.qaEnabled 开启）对每个知识块用对话模型生成 N 个问法，
 * 按「问法」向量化写入所属库向量索引（vector id = 本表 id）；
 * 命中问答对后经 knowledge_id 取回来源块正文进上下文——问法命中、块内容作答。
 * 行持久化是刻意设计：重嵌/跨库迁移可从本表恢复向量，无需再调 LLM。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_knowledge_qa")
public class KnowledgeQa {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属文档ID */
    private String docId;

    /** 来源知识块ID（命中后取回该块 title/content 进上下文与引用） */
    private String knowledgeId;

    /** 生成的问法（向量化索引文本） */
    private String question;

    /** 生成的答案（排查展示用，不参与召回） */
    private String answer;

    /** 创建时间 */
    private LocalDateTime createTime;
}
