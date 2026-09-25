package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 模型库表：供应商网关下的可用模型，按类型（chat/vision/embedding/rerank/other）分类登记。
 * <p>
 * model_id 为调用 API 时原样透传的模型名；对外引用格式为 {@code {providerId}/{modelId}}
 * （由服务层拼装，本表不存冗余引用列）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_model")
public class ModelInfo {

    /** 主键ID (UUID) */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属供应商（c_ai_provider.id） */
    private String providerId;

    /** 模型名（调用 API 时 model 参数原样透传） */
    private String modelId;

    /** 展示名（空=同 model_id） */
    private String displayName;

    /** 类型: chat=聊天 vision=视觉 embedding=向量 rerank=重排 other=其他 */
    private String modelType;

    /** 思考能力: auto=按模型名判定 none=不支持 switchable=可开关 always=恒思考（仅聊天模型有意义） */
    private String thinking;

    /** 启用: 1=启用 0=停用 */
    private Integer enabled;

    /** 备注（如上下文窗口说明） */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
