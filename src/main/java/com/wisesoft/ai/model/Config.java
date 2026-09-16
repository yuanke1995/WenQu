package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 模型配置表（key-value，模型配置界面用）
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_config")
public class Config {

    /** 配置键（主键，如 chat.model；列名 config_key 避开 MySQL 保留字 key） */
    @TableId(type = IdType.INPUT)
    private String configKey;

    /** 配置值（列名 config_value 避开 MySQL/OceanBase 保留字 value） */
    private String configValue;

    /** 说明 */
    private String remark;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
