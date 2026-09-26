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

    /** 个人默认聊天模型（引用 providerId/modelId；空=不设默认，对话时手动选择）。
     *  updateStrategy=ALWAYS：清空默认需把该列置 null，MP 默认策略会跳过 null 字段导致清空失效 */
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String defaultModel;

    /** 个人默认视觉模型（聊天上传图片理解；空=未设默认，解析不描述图片）。清空同上需 ALWAYS */
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private String defaultVisionModel;

    // 个人默认重排模型已退役（重排归知识库检索设置；存量库该列无害保留）

    /** OIDC 身份标识（IdP 的 sub，唯一索引）：空=未绑定单点登录。
     *  <p>蓝本用「占位用户 oidc:{sub}:{userId}」记录绑定，其注释自述原因是"在不修改表结构的前提下"
     *  保存绑定关系；本工程有 SchemaMigrator 自动加列，故直接用列承载，不再制造假用户行。</p> */
    private String oidcSub;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
