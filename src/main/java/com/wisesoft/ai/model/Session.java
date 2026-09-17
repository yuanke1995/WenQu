package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话表实体
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_session")
public class Session {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属用户（登录用户 uid；anonymous=历史兼容池，全局可见） */
    private String userId;

    /** 会话标题（取自首条用户问题前50字） */
    private String title;

    /** 消息条数 */
    private Integer messageCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    /** 是否置顶: 0=否,1=是 */
    private Integer isPinned;

    /** 是否收藏: 0=否,1=是 */
    private Integer isFavorite;
}
