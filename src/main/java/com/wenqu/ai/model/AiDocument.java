package com.wenqu.ai.model;

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

    /** 文件类型: docx/pdf/xlsx/url（url=网页导入，源文件为 HTML 快照） */
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

    /** 网页导入的源 URL（file_type=url 时记录；普通上传为空） */
    private String sourceUrl;

    /** 源内容指纹（SHA-256 hex；官方内置库手册同步用：指纹不变跳过重建，普通上传为空） */
    private String sourceHash;

    /** 所属知识库ID（空=归入默认知识库；检索按库过滤，决定该文档能被哪些助手召回） */
    private String kbId;

    /** 文档级视觉模型覆盖（上传时指定，引用 {providerId}/{modelId}；空=跟随知识库配置。对该文档所有图片理解生效） */
    private String visionRef;

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

    /** 网页源自动刷新: 0=关闭 1=开启（仅 file_type=url 生效） */
    private Integer autoRefresh;

    /** 自动刷新 cron（5段: 分 时 日 月 周） */
    private String refreshCron;

    /** 上次自动刷新时刻 */
    private LocalDateTime lastRefreshAt;

    /** 下次自动刷新时刻（调度扫描据此触发） */
    private LocalDateTime nextRefreshAt;
}
