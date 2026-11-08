package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户邀请（P1 多租户生命周期 · 3A）。
 *
 * <p>一次性短链：token 只存 SHA-256（明文只在生成时返回一次），注册成功即落本租户。
 * 行带 {@code tenant_id}，由租户行级过滤兜住，不在 GLOBAL_TABLES 里。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_tenant_invite")
public class TenantInvite {

    /** 所属租户（= 注册成功后的用户归属） */
    private String tenantId;

    @TableId(type = IdType.INPUT)
    private String id;

    /** 邀请 token 的 SHA-256（十六进制小写，64 位） */
    private String tokenHash;

    /** 前 8 位明文，列表辨识用（不足以还原 token） */
    private String tokenPrefix;

    /** 预选角色编码，逗号分隔；空=租户默认角色 user */
    private String roleIds;

    /** 预选部门 id */
    private String deptId;

    /** 限定邮箱（空=不限定；本版不校验邮箱归属，链接本身就是凭据） */
    private String email;

    /** 可用次数（0=不限） */
    private Integer maxUses;

    /** 已用次数 */
    private Integer usedCount;

    /** 到期时刻 */
    private LocalDateTime expiresAt;

    /** active=有效 revoked=已撤销 exhausted=已用尽 expired=已过期 */
    private String status;

    /** 创建人 uid */
    private String createdBy;

    private LocalDateTime createTime;
}
