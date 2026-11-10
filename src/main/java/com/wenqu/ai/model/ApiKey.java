package com.wenqu.ai.model;

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

    /**
     * Key 明文的 JWE 密文（部署密钥加密保管，列表「眼睛」回显/复制的数据源）。
     * <p>与哈希并存：哈希负责请求校验（不可逆），密文负责可回显（与模型供应商 api_key 同一套
     * {@link ConfigCryptoService}）。NULL=签发于「明文回显」上线前的存量 Key，不可回显、只能重签。
     */
    private String keyCipher;

    /** 是否启用（0=启用，1=停用/吊销） */
    private Integer disabled;

    /**
     * 能力范围（JSON 数组字符串，如 ["chat","embedding","kb.read","kb.write","workflow.run"]）。
     * <p>NULL=按 {@link com.wenqu.ai.service.ApiKeyService#DEFAULT_SCOPES} 生效——存量 Key 与
     * 未显式收窄的 Key 都落在默认集（问答/向量/知识库只读/工作流触发），写库只有显式勾选才放开。
     * scope 只在 API Key 通道参与判定；登录态（JWT）请求不受 scope 约束。
     */
    private String scopes;

    /**
     * MCP 端点：1=该 Key 可用于平台级 MCP 入口 /ai/mcp（元工具集：检索/问答/列库/列智能体）
     * <p>默认 0：Key 的权限面是「问答链路」，MCP 是另一条入口，需显式授权后才放行（最小权限）。
     */
    private Integer mcpEnabled;

    /** 过期时间（null=长期有效） */
    private LocalDateTime expireAt;

    /** 最近使用时间 */
    private LocalDateTime lastUsedAt;

    /**
     * 代表账号（集成方身份）：这把 Key 的问答请求以哪个账号执行。
     * <p>null=不代表任何账号，问答链路直接拒绝——「带对 Key 就等价于拿到某个人的身份」
     * 必须显式指定，不能靠创建人推断（创建人往往是管理员）。
     */
    private String actAsUid;

    /** 创建人（登录用户 uid） */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
