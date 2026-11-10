package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GraphRAG 实体（按知识库隔离）：name_norm 归一键去重，aliases 合并同义写法，
 * mention_count 做图扩展排序的弱权重。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_graph_entity")
public class GraphEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String kbId;

    /** 实体显示名（首次见到的写法） */
    private String name;

    /** 归一名（去空白/全角转半角/小写） */
    private String nameNorm;

    /** 别名 JSON 数组（同一实体的其它写法） */
    private String aliases;

    /**
     * 实体类型：人物 / 机构 / 法规 / 概念 / 产品 / 地点 / 时间 / 其他。
     * 由抽取模型判定；空=存量未分类（展示时归入「其他」）。
     */
    private String type;

    /** 提及次数 */
    private Integer mentionCount;

    private LocalDateTime createTime;
}
