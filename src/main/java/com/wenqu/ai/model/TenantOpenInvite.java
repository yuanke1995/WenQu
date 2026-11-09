package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邀请式开通：平台先发一次性邀请，客户凭链接补齐机构与管理员信息，接受即开通。
 *
 * <p>与自助申请的差别是审批方向：这一单已经被平台预批准，不再进入待审队列。
 * token 只存 SHA-256，明文只在生成时返回一次。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_tenant_open_invite")
public class TenantOpenInvite {

    @TableId(type = IdType.INPUT)
    private String id;

    /** 邀请 token 的 SHA-256 */
    private String tokenHash;

    /** 前 8 位，列表辨识用 */
    private String tokenPrefix;

    /** 预填机构名称（空=客户自填） */
    private String orgName;

    /** 预填租户标识（空=客户自填） */
    private String desiredId;

    /** 预填联系人 */
    private String contactName;

    /** 限定联系邮箱（空=不限定） */
    private String contactEmail;

    /** 给客户的说明 */
    private String note;

    private Integer maxUses;

    private Integer usedCount;

    private LocalDateTime expiresAt;

    /** active=有效 revoked=已撤销 exhausted=已用尽 expired=已过期 */
    private String status;

    /** 接受后开出的租户标识 */
    private String openedTenant;

    private String createdBy;

    private LocalDateTime createTime;
}
