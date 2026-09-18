package com.wisesoft.wenqu.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 部门表（用户分组，供共享范围的 department 维度使用）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_department")
public class Department {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 部门名称 */
    private String name;

    /** 部门描述 */
    private String description;

    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
