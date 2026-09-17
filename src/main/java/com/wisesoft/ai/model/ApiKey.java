package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API Key 表（对外开放问答能力用）
 * <p>
 * 安全约定：库里**只存哈希**（SHA-256，无盐但 Key 本身是 32 字节随机数，暴力不可行），
 * 明文仅在创建时返回一次；另存 prefix（前 8 位）供列表里区分"哪个 Key"而不泄露全文。
 * Key 权限固定为「问答链路」（见 SecurityConfig 白名单），不授予管理端点。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_api_key")
public class ApiKey {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 名称（用途标识，如"报表系统集成"） */
    private String name;

    /** SHA-256 哈希（十六进制小写） */
    private String keyHash;

    /** 明文前缀（展示用，如 sk-a1b2c3d4…） */
    private String keyPrefix;

    /** 是否启用（0=启用，1=停用/吊销） */
    private Integer disabled;

    /** 过期时间（null=长期有效） */
    private LocalDateTime expireAt;

    /** 最近使用时间 */
    private LocalDateTime lastUsedAt;

    /** 创建人（登录用户 uid） */
    private String createdBy;

    /** 共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见) */
    private String shareConfig;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
