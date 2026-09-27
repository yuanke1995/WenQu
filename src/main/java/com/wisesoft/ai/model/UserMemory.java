package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户长期记忆（跨会话）
 * <p>
 * 来源两条路：① 问答结束后异步 LLM 提炼（source=auto，记录来源会话便于溯源删除）；
 * ② 用户在个人设置里手动添加/编辑（source=manual）。注入时只取**本人**的记忆追加进
 * system prompt（游客分享会话不注入——发布者的个人记忆不外泄给匿名访客）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_user_memory")
public class UserMemory {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属用户 */
    private String uid;

    /** 记忆内容（一句独立可读的事实/偏好） */
    private String content;

    /** 类别: fact=事实偏好 instruction=指令约定 project=项目背景 */
    private String category;

    /** 来源: auto=问答后自动提取 manual=用户手动添加 */
    private String source;

    /** 来源会话ID（auto 时记录） */
    private String sourceSessionId;

    /** 被注入次数（使用度） */
    private Integer hitCount;

    /** 最近一次被注入时间 */
    private LocalDateTime lastHitAt;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
