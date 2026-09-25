package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 模型供应商表（OpenAI 兼容网关档案：baseUrl + apiKey + 路径覆盖）。
 * <p>
 * 一个供应商登记一个网关，网关下的可用模型登记在 {@link ModelInfo}（按类型分类）。
 * api_key 以 RSA 密文存储（复用 ConfigCryptoService），读取时由服务层透明解密。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_provider")
public class Provider {

    /** 主键ID (UUID) */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 供应商显示名（如 DeepSeek / 智谱GLM） */
    private String name;

    /** 图标：内置图标 key（deepseek/zhipu/...）或 http(s) 图片 URL（空=前端字母头像） */
    private String icon;

    /** 网关地址（OpenAI 兼容） */
    private String baseUrl;

    /** API Key（RSA 密文，RSA: 前缀） */
    private String apiKey;

    /** 聊天补全路径（空=默认 /v1/chat/completions） */
    private String completionsPath;

    /** 向量路径（空=默认 /v1/embeddings） */
    private String embeddingsPath;

    /** 协议类型（预留: openai=OpenAI 兼容） */
    private String apiType;

    /** 启用: 1=启用 0=停用（停用后其模型不可选） */
    private Integer enabled;

    /** 备注 */
    private String remark;

    /** 排序（小在前） */
    private Integer sortOrder;

    /** 创建人 uid */
    private String createdBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
