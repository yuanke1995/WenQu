package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GraphRAG 抽取记录（账本）：块 content_hash 一致即跳过（哈希增量），
 * failed 不自动重试（成本闸），失败率与产量从这里统计。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_graph_extract")
public class GraphExtract {

    @TableId(type = IdType.INPUT)
    private String id;

    private String docId;

    private String chunkId;

    /** 抽取时的块内容哈希 */
    private String contentHash;

    /** done / failed */
    private String status;

    /** 抽出三元组条数 */
    private Integer tripleCount;

    private LocalDateTime createdAt;
}
