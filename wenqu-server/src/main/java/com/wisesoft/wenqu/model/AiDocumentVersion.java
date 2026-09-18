package com.wisesoft.wenqu.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 文档版本快照表实体
 * 快照存知识块元数据 JSON（[{id,title,content,images}]），不含向量（回滚时重新向量化）
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_document_version")
public class AiDocumentVersion {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 文档ID */
    private String docId;

    /** 版本号 */
    private Integer version;

    /** 该版本知识块数量 */
    private Integer chunkCount;

    /** 知识块快照(JSON数组) */
    private String snapshotJson;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
