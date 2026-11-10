package com.wenqu.ai.model;

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

    /** API Key（JWE 密文，JWE: 前缀） */
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

    /**
     * 归属用户（谁建归谁，仅归属人可见可用，不存在平台共享；管理员级保留管理全部的运维视角）。
     * 历史遗留空值 = 无主行（原平台共享），启动时回填给创建人/第一个管理员。
     */
    private String ownerUid;

    /**
     * 额度状态：null/空=正常；exhausted=额度不足（余额耗尽/配额用尽/套餐到期）。
     * 由 {@code ModelQuotaService} 在真实调用失败时登记，任何一次成功调用即清除——
     * 纯被动检测，不做定时体检，避免无谓的调用开销与花费。
     */
    private String quotaStatus;

    /** 额度异常原因（网关返回原文摘要，供界面与通知展示；正常时 NULL） */
    private String quotaMessage;

    /** 触发额度不足的模型名（裸模型名，不含 providerId 前缀；正常时 NULL） */
    private String quotaModelId;

    /** 额度状态最近一次判定时刻（正常时 NULL；超过时效未复现视为已自愈，不在界面禁选） */
    private LocalDateTime quotaCheckedAt;

    /**
     * 最近一次主动核验时刻（GET /v1/models 免费探测，由 {@code ProviderVerifyService} 写回）。
     * 被动额度登记只说「撞过墙」——坏了要用户先去对话页失败一轮才知道；这一列让列表页
     * 能回答「最后一次体检是什么时候、结论是什么」，供应商坏了 ≤5 分钟在列表可见。
     */
    private LocalDateTime lastVerifiedAt;

    /** 核验往返耗时（毫秒；未核验 NULL） */
    private Integer lastVerifyMs;

    /**
     * 核验结果：ok=可达 unreachable=网络不通 rejected=网关拒绝（鉴权/路径）expired=额度不足；
     * NULL=从未核验。与 {@code quotaStatus} 独立——后者纯被动，这里主动体检的结论。
     */
    private String verifyStatus;

    /** 核验失败原因摘要（网关返回原文截断；ok 时 NULL） */
    private String verifyMessage;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
