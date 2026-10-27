package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户开通申请（Phase 4 刀三 · 自助开通）。
 *
 * <p>申请发生在<b>还没有租户</b>的时候，所以这张表没有 {@code tenant_id}（在
 * {@code WenquTenantLineHandler} 的平台全局表清单里）：给一条还没有归属的行硬编一个归属，
 * 等于把「谁申请的」这件事写成一个假值。
 *
 * <p>自助不等于自动放行：申请只落一条待审记录，开通由平台级管理员批准。
 * 无人审批就自动建租户，等于把「谁能在这个平台上开一家客户」交给互联网。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_tenant_request")
public class TenantRequest {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 客户/机构名称（批准后就成为租户显示名） */
    private String orgName;

    /** 期望的租户标识；留空＝由批准人定 */
    private String desiredId;

    /** 联系人 */
    private String contactName;

    /** 联系邮箱（申请单只回显给管理员；申请人自己凭提交后的编号跟进） */
    private String contactEmail;

    /** 用途说明：要解决什么问题、大概多少人 */
    private String purpose;

    /** pending=待审 approved=已开通 rejected=已驳回 */
    private String status;

    /** 批准后的租户标识（驳回或待审为 null）。刻意不叫 tenant_id：那是「行归属」的名字，这一列只是审批结果 */
    private String openedTenant;

    /** 批准时建出的首位管理员账号（口令只在那一次界面上出现，不存明文） */
    private String adminUid;

    /** 审批人与结论说明 */
    private String reviewer;

    private String reviewNote;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
