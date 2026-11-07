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

    /** 正文格式: plain=纯文本 markdown=Markdown（横幅按对应渲染器展示） */
    private String contentFormat;

    /** 级别: info=通知 warn=警示 danger=紧急（横幅配色区分，danger 常驻不可关） */
    private String level;

    /** 展示对象: all=全员 department=指定部门 role=指定角色 */
    private String audienceType;

    /** 展示对象 ID 清单（部门 ID / 角色 code，逗号分隔；all 时为空） */
    @com.baomidou.mybatisplus.annotation.TableField(
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String audienceIds;

    /** 生效开始（空=发布时间起） */
    @com.baomidou.mybatisplus.annotation.TableField(
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private LocalDateTime startTime;

    /** 生效结束（空=直到手动下线） */
    @com.baomidou.mybatisplus.annotation.TableField(
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private LocalDateTime endTime;

    /** 1=发布中 0=下线（保留行，历史可查） */
    private Integer enabled;

    /** 发布人 uid */
    private String createdBy;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---- 以下字段不落库：由管理清单/成员横幅按需填充 ----

    /** 管理清单的已读人数（按 ann_id 聚合回执，仅 ops:read 列表填充） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long readCount;

    /** 管理清单的受众总数（全员/部门/角色口径；已读进度条的分母） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long audienceCount;

    /** 成员横幅：当前用户是否已读（active 列表填充；管理清单为空） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean read;
}
