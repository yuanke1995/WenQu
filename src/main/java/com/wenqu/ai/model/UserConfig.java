package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人配置覆盖（个人设置 → 对话偏好）。
 * <p>
 * 键限定为 config-schema.json 中标记 {@code personal} 的字段：个人值覆盖系统全局值（c_ai_config），
 * 仅对该用户本人的问答生效；清空 = 删除本行 = 回落全局。与全局配置同构（键 + 值），
 * 新增个人参数只需在 schema 里勾选 personal，无需改表。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_user_config")
public class UserConfig {

    /** 主键ID（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户（c_ai_user.uid） */
    private String uid;

    /** 配置键（config-schema.json 的 backendKey，须标记 personal） */
    private String configKey;

    /** 个人覆盖值（与全局配置同格式；空=未设置） */
    private String configValue;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
