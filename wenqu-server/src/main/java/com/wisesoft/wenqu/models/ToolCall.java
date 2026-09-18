package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * tool_calls
 * <p>
 * 由参考实现的 models_business 中 ToolCall 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("tool_calls")
public class ToolCall {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    @TableField("message_id")
    private Integer messageId;  // 外键 → messages.id，非空
    @TableField("langgraph_tool_call_id")
    private String langgraphToolCallId;
    @TableField("tool_name")
    private String toolName;  // 非空
    @TableField("tool_input")
    private String toolInput;
    @TableField("tool_output")
    private String toolOutput;
    private String status;  // 默认 "pending"
    @TableField("error_message")
    private String errorMessage;
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
}
