package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 用户表（画像/归属；鉴权由前置网关完成，透传 X-User-Id，无密码字段）。
 * <p>
 * uid 与网关鉴权标识一致，由服务层在首次出现未知 X-User-Id 时自动建档。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_user")
public class AiUser {

    /** 用户标识（网关透传 X-User-Id） */
    @TableId(type = IdType.INPUT)
    private String uid;

    /** 显示名称 */
    private String username;

    /** 所属部门ID（c_ai_department.id，可空=未分配） */
    private String departmentId;

    /** 角色: superadmin | admin | user */
    private String role;

    /** 状态: 1=启用, 0=禁用 */
    private Integer status;

    /** 密码哈希（PBKDF2；空=未设置，不能本地登录） */
    private String passwordHash;

    /** 连续登录失败次数 */
    private Integer loginFailCount;

    /** 锁定至（失败过多时；空=未锁定） */
    private LocalDateTime lockedUntil;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
