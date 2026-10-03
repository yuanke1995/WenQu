package com.wenqu.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推理用量台账流水
 * <p>
 * 每一笔经多供应商路由发出的 LLM 请求记账一行（含工具调用循环的每一轮），
 * 是「个人使用统计」与「供应商账单对账」的唯一数据源。以前三期 statistics 直接读
 * c_ai_message.tokens，只能覆盖主链路回答那一轮的用量，与主账单差 4 倍。
 * <p>
 * uid 可为 NULL：索引/评测/探测等无用户上下文的系统调用如实记为空归属
 * （总账单仍包含它们，个人侧只看自己的行），避免在缺失上下文时伪造归属。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_usage_log")
public class UsageLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属用户 uid（NULL=无用户上下文的系统调用） */
    private String uid;

    /** 触发该次调用的会话 id（可空） */
    private String sessionId;

    /** 触发该次调用的回答消息 id（可空：调用先于消息落库或无归属回答） */
    private String messageId;

    /** 模型引用 providerId/modelId（迁移前遗留名原样） */
    private String model;

    /** 调用用途：chat / dispatch / subagent / memory / workflow / graphrag / index / probe / eval / other */
    private String kind;

    /** 输入 token（含缓存命中部分，与网关 usage.prompt_tokens 同口径） */
    private Long promptTokens;

    /** 输出 token（含思考过程与工具调用参数生成） */
    private Long completionTokens;

    /** 命中 prompt 缓存的输入 token */
    private Long cachedTokens;

    /** prompt + completion，与供应商计费总量同口径 */
    private Long totalTokens;

    private LocalDateTime createTime;
}
