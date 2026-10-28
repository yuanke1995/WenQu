package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台公告（治理沟通）：停机窗口 / 系统升级 / 新功能上线，按租户发布。
 * <p>
 * 与站内通知（{@code c_ai_notification}）的分工：通知是<b>事件</b>（一人一事，读完即走），
 * 公告是<b>状态</b>（全员持续可见，直到下线）——「周六 22:00-24:00 停机维护」这种事不是某个人的一条消息，
 * 是这个租户这段时间的背景事实，横幅常显才对。
 * <p>
 * 下线（enabled=0）保留行不真删：管理员能看到发过的历史，误下线一键恢复。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_announcement")
public class Announcement {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户 ID（行级隔离：公告只对本租户成员可见） */
    private String tenantId;

    /** 标题（横幅主文本） */
    private String title;

    /** 正文（点开看详情） */
    private String content;

    /** 级别: info=通知 warn=警示 danger=紧急（横幅配色区分，danger 常驻不可关） */
    private String level;

    /** 1=发布中 0=下线（保留行，历史可查） */
    private Integer enabled;

    /** 发布人 uid */
    private String createdBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
