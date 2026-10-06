package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
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

    /** 类型: chat=聊天 vision=视觉 embedding=向量 rerank=重排 audio=语音 omni=全模态 other=其他 */
    private String modelType;

    /** 图片理解能力（三态，可与聊天类型并存）: NULL=按类型与模型名自动判定 1=支持 0=不支持 */
    private Integer visionCapable;

    /** 工具调用能力（三态，仅对话类类型有意义）: NULL=按类型与模型名自动判定 1=支持 0=不支持（不支持时不得下发 tools，否则网关 400） */
    private Integer toolCapable;

    /** 该模型支持的思考强度档位（逗号分隔 low,medium,high,xhigh,max；空=只支持思考开关，不支持强度调节） */
    private String reasoningLevels;

    /** 默认思考强度档位（须在本模型 reasoningLevels 内；空=不指定，由请求层/网关默认决定） */
    private String defaultReasoningLevel;

    /** 思考能力: auto=按模型名判定 none=不支持 switchable=可开关 always=恒思考（仅聊天模型有意义） */
    private String thinking;

    /** 上下文窗口上限 token（对话类模型必填，无全局兜底；即用户可选区间的最大值/默认值；上下文预算 = 窗口×安全系数−输出限制） */
    private Integer contextWindow;

    /** 上下文窗口下限 token（NULL=不可调；与 contextWindow 构成聊天页用户可选区间 [min,max]，默认取 max） */
    private Integer contextWindowMin;

    /** 最大输出 token（NULL=未声明，不下发 max_tokens 交由厂商默认；作为 max_tokens 随请求下发，同时从窗口预算中预留） */
    private Integer maxOutput;

    /** 输入单价（元/百万 tokens；NULL=未配置，费用报表对该模型显示「未计价」；0=免费模型，合法值） */
    private BigDecimal inputPrice;

    /** 输出单价（元/百万 tokens；NULL=未配置；输出通常比输入贵，两价独立登记） */
    private BigDecimal outputPrice;

    /** 启用: 1=启用 0=停用 */
    private Integer enabled;

    /** 备注（如上下文窗口说明） */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
