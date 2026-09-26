package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 消息表实体
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_message")
public class Message {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属会话ID */
    private String sessionId;

    /** 角色: user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** 思考过程全文（深度思考） */
    private String thinking;

    /** 关联图片URL (JSON数组字符串) */
    private String images;

    /** 引用来源 (JSON数组字符串: [{ref,knowledgeId,docId,fileName,title,snippet}]) */
    private String sources;
    /** 检索状态行数据 (JSON: keywords/refs/terms) */
    private String retrieved;
    /** 产物交付 (JSON数组: [{url,filename,size,description}]，原始 URL 存库、展示层签名) */
    private String artifacts;

    /** 工具调用过程 (JSON数组: [{name,status,elapsedMs,args,result|error}]) */
    private String toolCalls;

    /** 附件元信息 (JSON数组: [{name,mime,size}]，不含内容本体，气泡回显) */
    private String attachments;

    /** 消息序号 (会话内递增) */
    private Integer sequence;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
