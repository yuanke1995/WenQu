package com.wisesoft.wenqu.models;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * agent_envs
 * <p>
 * 由参考实现的 models_business 中 AgentEnv 逐字段翻译（字段名照搬，类型做 Java 映射）。
 */
@Data
@TableName("agent_envs")
public class AgentEnv {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;  // 主键
    private String uid;  // 唯一，外键 → users.uid，非空
    private String env;  // 非空，默认 dict
    @TableField("created_at")
    private LocalDateTime createdAt;  // 默认 utc_now_naive
    @TableField("updated_at")
    private LocalDateTime updatedAt;  // 默认 utc_now_naive
}
