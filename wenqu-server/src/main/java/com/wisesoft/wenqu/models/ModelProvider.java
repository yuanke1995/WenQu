package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * model_providers
 * <p>
 * 由参考实现的 models_business 中 ModelProvider 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("model_providers")
public class ModelProvider {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("provider_id")
    private String providerId;  // 唯一，非空
    @TableField("display_name")
    private String displayName;  // 非空
    @TableField("provider_type")
    private String providerType;  // 非空，默认 "openai"
    @TableField("default_protocol")
    private String defaultProtocol;
    @TableField("base_url")
    private String baseUrl;  // 非空
    @TableField("embedding_base_url")
    private String embeddingBaseUrl;
    @TableField("rerank_base_url")
    private String rerankBaseUrl;
    @TableField("models_endpoint")
    private String modelsEndpoint;
    @TableField("embedding_models_endpoint")
    private String embeddingModelsEndpoint;
    @TableField("rerank_models_endpoint")
    private String rerankModelsEndpoint;
    @TableField("api_key_env")
    private String apiKeyEnv;
    @TableField("api_key")
    private String apiKey;
    private String capabilities;  // 非空，默认 list
    @TableField("enabled_models")
    private String enabledModels;  // 非空，默认 list
    @TableField("headers_json")
    private String headersJson;
    @TableField("extra_json")
    private String extraJson;
    @TableField("is_enabled")
    private Boolean isEnabled;  // 非空，默认 True
    @TableField("is_builtin")
    private Boolean isBuiltin;  // 非空，默认 False
    @TableField("created_by")
    private String createdBy;
    @TableField("updated_by")
    private String updatedBy;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
}
