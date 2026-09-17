package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 用户表（画像/归属 + 登录凭据：密码以 PBKDF2 加盐哈希存储，不明文落库）。
 * <p>
 * uid 即登录账号，由管理员在「成员管理」建档（不再自动建档，避免未知身份混入）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_user")
public class User {

    /** 用户标识（登录账号） */
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
