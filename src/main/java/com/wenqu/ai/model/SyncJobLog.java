package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 身份同步作业日志（SCIM 推送 / LDAP 目录同步 / 销户回收的可追溯记录）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_sync_job_log")
public class SyncJobLog {

    /** 主键 (UUID) */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 租户ID（Phase4 启用，当前 NULL） */
    private String tenantId;

    /** 来源类型：scim | ldap | saml | oidc */
    private String sourceType;

    /** 对应 c_ai_identity_provider.id */
    private String sourceId;

    /** 本次新建账号数 */
    private Integer usersCreated;

    /** 本次更新账号数 */
    private Integer usersUpdated;

    /** 本次禁用账号数 */
    private Integer usersDisabled;

    /** success | partial | failed */
    private String status;

    /** 摘要/错误信息 */
    private String detail;

    private LocalDateTime createTime;
}
