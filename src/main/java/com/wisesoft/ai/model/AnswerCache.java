package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 相似问题答案缓存表（语义缓存：相似问题直接复用历史回答，省检索+LLM 成本）
 * 知识库变更（解析成功/删除/回滚/启停用/手动补块）时整体失效清空
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_answer_cache")
public class AnswerCache {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 原始问题 */
    private String question;

    /** 问题向量（JSON float 数组，与知识块同 embedding 模型） */
    private String embedding;

    /** 完整回答（含 [N] 引用与 [图片N] 标记） */
    private String answer;

    /** 引用来源 JSON（done 事件 payload 的 sources 字段） */
    private String sources;

    /** 关联图片 URL JSON 数组 */
    private String images;

    /** 相关追问 JSON 数组 */
    private String related;

    /** 关联消息 ID（反馈闭环用） */
    private String messageId;

    /** 命中次数 */
    private Integer hitCount;

    private LocalDateTime createTime;
}