package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * agent_run_requests
 * <p>
 * 由参考实现的 models_business 中 AgentRunRequest 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("agent_run_requests")
public class AgentRunRequest {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("request_id")
    private String requestId;  // 唯一，非空
    private String uid;  // 非空
    @TableField("agent_slug")
    private String agentSlug;  // 非空
    @TableField("conversation_thread_id")
    private String conversationThreadId;  // 非空
    private String source;  // 非空，默认 "chat"
    private String channel;  // 非空，默认 "web"
    @TableField("external_id")
    private String externalId;
    @TableField("origin_metadata")
    private String originMetadata;  // 非空，默认 dict
    @TableField("queue_policy")
    private String queuePolicy;  // 非空，默认 "enqueue"
    private String status;  // 非空，默认 "queued"
    @TableField("input_message_id")
    private Integer inputMessageId;  // 外键 → messages.id，非空
    @TableField("dispatched_run_id")
    private String dispatchedRunId;  // 外键 → agent_runs.id
    @TableField("input_payload")
    private String inputPayload;  // 非空，默认 dict
    @TableField("error_message")
    private String errorMessage;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 非空，默认 utc_now_naive
    @TableField("dispatched_at")
    private LocalDateTime dispatchedAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 非空，默认 utc_now_naive

    /**
     * 字典投影（参考实现 {@code AgentRunRequest.to_dict()}）。
     *
     * <p>键集合与顺序逐字对齐；JSON 文本列还原为对象（空/非法 → {@code {}}），
     * 时间列按 UTC ISO 输出（{@code format_utc_datetime}）。
     */
    public java.util.Map<String, Object> toDict() {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("request_id", requestId);
        data.put("uid", uid);
        data.put("agent_slug", agentSlug);
        data.put("thread_id", conversationThreadId);
        data.put("source", source);
        data.put("channel", channel);
        data.put("external_id", externalId);
        data.put("origin_metadata", jsonColumn(originMetadata));
        data.put("queue_policy", queuePolicy);
        data.put("status", status);
        data.put("input_message_id", inputMessageId);
        data.put("dispatched_run_id", dispatchedRunId);
        data.put("error_message", errorMessage);
        data.put("created_at", com.wisesoft.wenqu.common.DateTimeUtils.formatUtcDatetime(createdAt));
        data.put("dispatched_at", com.wisesoft.wenqu.common.DateTimeUtils.formatUtcDatetime(dispatchedAt));
        data.put("updated_at", com.wisesoft.wenqu.common.DateTimeUtils.formatUtcDatetime(updatedAt));
        return data;
    }

    /** 解析 JSON 文本列（参考实现 {@code self.origin_metadata or {}}）。 */
    private static java.util.Map<String, Object> jsonColumn(String raw) {
        if (raw == null || raw.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            Object parsed = com.alibaba.fastjson2.JSON.parse(raw);
            if (parsed instanceof java.util.Map<?, ?> map) {
                java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }
        } catch (RuntimeException ignored) {
            // 与参考实现一致：非法 JSON 不抛出，按空对象处理
        }
        return new java.util.LinkedHashMap<>();
    }
}
