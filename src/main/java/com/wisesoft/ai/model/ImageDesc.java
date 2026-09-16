package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图片描述缓存（内容寻址，持久化）
 * cache_key = v{版本}_{sha256(model+prompt+图片字节)}；版本号变化 → 前缀变化 → 全量重新描述
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_image_desc")
public class ImageDesc {

    @TableId
    private String cacheKey;

    /** 图片描述 */
    private String description;

    /** 生成描述的视觉模型名（运维可查） */
    private String model;

    private LocalDateTime createTime;

    /** 最近命中时间（TTL 新鲜度基准） */
    private LocalDateTime updateTime;
}
