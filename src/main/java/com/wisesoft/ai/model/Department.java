package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 部门表（树形分组：parent_id 空=根；供共享范围的 department 维度与成员管理页使用）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_department")
public class Department {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 父部门ID（空=根节点；2026-09-26 升级树形） */
    private String parentId;

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
