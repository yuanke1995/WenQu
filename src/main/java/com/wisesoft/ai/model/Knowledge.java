package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 知识片段表
 * 文本元数据存 MySQL，向量存 Redis（vectorId 关联）
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_knowledge")
public class Knowledge {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 启停用: 0=生效, 1=停用（停用块不参与召回，用于块级诊断屏蔽坏块） */
    private Integer status;

    /** 所属文档ID */
    private String docId;

    /** 片段标题 */
    private String title;

    /** 片段正文（净内容：不含章节路径前缀、不含分块重叠尾巴） */
    private String content;

    /** 关联图片URL列表(JSON数组字符串)，如 ["/ai/images/xxx/1.png", ...] */
    private String images;

    /** 片段序号 */
    private Integer chunkIndex;

    /** Redis 向量库中的文档ID */
    private String vectorId;

    /** 内容指纹(SHA-256: title+titlePath+净content+images)，重解析增量对比用 */
    private String contentHash;

    /**
     * 章节路径（如 "安装部署/环境准备/依赖安装"）。
     * 正文只存净内容、路径独立存储：向量化时拼入 embedding 文本，检索时拼入上下文。
     */
    private String titlePath;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;

    // ===== 关键词检索临时统计（非表字段） =====

    /** 命中的词元数 */
    @TableField(exist = false)
    private Integer hitTerms;

    /** 总词元数 */
    @TableField(exist = false)
    private Integer totalTerms;

    /** 标题是否命中 */
    @TableField(exist = false)
    private boolean titleHit;

    /** 关键词加权分（tf×idf 归一化到 0~1，A3 检索排序用） */
    @TableField(exist = false)
    private double kwScore;
}