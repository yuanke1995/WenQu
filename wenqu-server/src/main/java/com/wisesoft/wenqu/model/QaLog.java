package com.wisesoft.wenqu.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 问答日志表（用于数据闭环：无命中分析/热门问题）
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_qa_log")
public class QaLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String sessionId;

    private String question;

    /** 回答摘要（前 500 字） */
    private String answerSummary;

    /** 改写后的检索用问题 */
    private String rewrittenQuery;

    /** 命中文档 ID 列表（逗号分隔） */
    private String hitDocIds;

    /** 是否有引用标注 */
    private Integer hasCitation;

    /** 回答耗时(ms) */
    private Integer elapsedMs;

    /**
     * 分段耗时（JSON）：{"rewrite":1200,"retrieve":3100,"generate":28000,"citation":9000}
     * 值为「距开始的累计毫秒」，相邻两项相减才是该阶段自身耗时。
     * 缓存命中的回答无分段（null）。
     */
    @com.baomidou.mybatisplus.annotation.TableField("stage_ms")
    private String stageMs;

    private LocalDateTime createdAt;
}
