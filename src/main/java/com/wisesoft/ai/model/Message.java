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

    /**
     * 回答时间线 (JSON数组: [{kind:'text',from,to} | {kind:'tool',i} | {kind:'artifact',i}])。
     * 记录正文与工具卡片/产物卡片的交错顺序：文本段只存 content 的字符区间，工具段存 toolCalls 下标，
     * 产物段存 artifacts 下标。刷新/历史会话据此还原「边想边做」的交错视图（不落库则只能整段正文+底部汇总）。
     */
    private String timeline;

    /**
     * 过程独白全文 (<process> 标签内的模型思考叙述，与正文分流)。
     * 时间线 process 段 {kind:'process',from,to} 的区间指向本字段，前端灰字弱化渲染、不与正文混淆。
     */
    private String processText;

    /** Token 用量 (JSON: context/budget/hits/output/prompt/outputIsReal/total)，随助手消息落库供历史回看 */
    private String tokens;

    /** 附件元信息 (JSON数组: [{name,mime,size}]，不含内容本体，气泡回显) */
    private String attachments;

    /**
     * 本轮生效的智能体（助手消息才有值；取自会话级绑定的当轮快照）。
     * 落库意义：回答归属可追溯（"这条是谁答的"），且智能体改名/删除后历史仍如实回显。
     */
    private String agentId;

    /** 本轮智能体名称快照（与 agentId 同时落库，供气泡/历史直接展示） */
    private String agentName;

    /** 消息序号 (会话内递增) */
    private Integer sequence;

    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
