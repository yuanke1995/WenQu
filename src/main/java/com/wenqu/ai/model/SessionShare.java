package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话只读分享（/shared/{token} 只读页）。
 * <p>
 * 与 {@link AgentShare}（智能体分享，访客可继续对话）的差别：这里分享的是**一段已经发生的对话**，
 * 访客只能看、不能续聊——所以不涉及"以谁的身份执行检索"这类授权语义，只需要 token 有效且未被停止。
 * <p>
 * 公开面收窄：只读页给的是消息正文与引用来源的**文档名/章节/相关度**，不给知识块全文
 * （链接一旦外流，片段全文等于把知识库正文带出去）；工具调用过程、思考全文、Token 用量等
 * 内部信息一律不下发。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_session_share")
public class SessionShare {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 被分享的会话ID（一个会话一条，唯一索引保证） */
    private String sessionId;

    /** 公开访问令牌（URL 路径段；重新开启会换新 token，旧链接立即失效） */
    private String token;

    /** 启用: 1=链接可访问, 0=已停止 */
    private Integer enabled;

    /**
     * 会话标题快照（分享时刻）
     * <p>
     * 会话本身可以被改名或删除，列表页不能只靠 join 现查——会话删掉后行会整条消失，
     * 用户反而"看不出这里曾经分享过东西"。落一份标题快照，让已删除的会话仍能以
     * "(会话已删除)" 的形态被看见并停用。
     */
    private String titleSnapshot;

    /** 发起分享的 uid（仅会话所有者可分享） */
    private String createdBy;

    /** 链接访问次数 */
    private Integer visitCount;

    /** 最近访问时刻 */
    private LocalDateTime lastVisitAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
