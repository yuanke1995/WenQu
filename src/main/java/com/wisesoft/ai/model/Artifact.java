package com.wisesoft.ai.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 产物交付（present_artifacts）：模型在回答中生成的可下载文件。
 * <p>
 * 归属**用户**（不是会话）：会话可以删、可以清理，但产出的成果仍归人生。
 * 文件本体落在 {@code {images.dir}/artifacts/{uid}/{yyyyMM}/{id}_{名称}}，
 * 本表只存元数据与相对路径（{@link #objectKey}），下载走签名 URL（与图片同惯例）。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_artifact")
public class Artifact {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属用户（c_ai_user.uid） */
    private String uid;

    /** 产生该产物的会话（可空：会话被清理后成果仍归属用户） */
    private String sessionId;

    /** 产物文件名（已净化，含扩展名） */
    private String filename;

    /** 扩展名（小写，无点） */
    private String ext;

    /** 字节数 */
    private Integer size;

    /** 存储相对路径：artifacts/{uid}/{yyyyMM}/{id}_{filename} */
    private String objectKey;

    /** 给用户的产物说明（工具调用时由模型给出） */
    private String description;

    /** 软删: 0=正常 1=已删除（文件同时删除） */
    private Integer deleted;

    private LocalDateTime createTime;
}
