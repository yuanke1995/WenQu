package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 文档表
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_document")
public class AiDocument {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 文件名 */
    private String fileName;

    /** 文件类型: docx */
    private String fileType;

    /** 分块数量 */
    private Integer chunkCount;

    /** 状态: 0=生效, 1=已弃用, 2=解析中, 3=解析失败 */
    private Integer status;

    /** 解析失败原因（status=3 时可见） */
    private String failReason;

    /** 解析进度 0-100（解析中递增） */
    private Integer parseProgress;

    /** 解析阶段描述（如"向量化 128/300"） */
    private String parseDesc;

    /** 文件大小(字节) */
    private Long fileSize;

    /** 文档描述 */
    private String description;

    @TableLogic
    private Integer deleted;

    /** 当前版本号（每次成功解析+1，用于版本管理） */
    private Integer version;

    /** 创建人（登录用户 uid） */
    private String createdBy;

    /** 共享范围(JSON: {read_scope:{access_level:global|department|user,department_ids[],user_uids[]},manage_scope:{同}}; 空=全员可见) */
    private String shareConfig;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
