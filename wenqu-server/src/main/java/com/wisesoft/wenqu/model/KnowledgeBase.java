package com.wisesoft.wenqu.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库：文档的容器，也是检索的作用域与检索参数的归属。
 * <p>
 * 定位（对齐成熟知识库产品的模型）：
 * <ul>
 *   <li><b>文档容器</b>：文档归属于某个知识库（{@code c_ai_document.kb_id}），未指定时归入默认库。</li>
 *   <li><b>检索作用域</b>：检索按知识库过滤，不同库之间互不干扰（法律库与手册库各自召回）。</li>
 *   <li><b>参数归属</b>：检索参数（{@code queryParams}）存在库上，而非全局——
 *       不同资料性质可配不同策略（法律库提高阈值保精度、手册库放宽阈值保召回）；
 *       留空表示继承「系统设置 → 检索设置」的全局值。</li>
 * </ul>
 * 与智能体的关系：智能体关联若干知识库（{@code Agent.knowledgeBaseIds}），
 * 从而决定该助手能看到哪些资料；这是把过去「智能体直接挂文档」提升为「智能体挂知识库」。
 *
 * @author yuanke
 */
@Data
@TableName("c_ai_knowledge_base")
public class KnowledgeBase {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 知识库名称 */
    private String name;

    /** 描述 */
    private String description;

    /**
     * 检索参数（JSON：{"retrieval.vecThreshold":"0.3","retrieval.vectorTopK":"30",...}）。
     * 空=全部继承全局检索设置；只允许检索/重排类的键（白名单在读取处校验）。
     */
    private String queryParams;

    /**
     * 分块参数（JSON：{"chunk.maxChunks":"300","chunk.overlap":"80",...}）。
     * 空=继承全局分块设置。与 queryParams 分列：一个管"怎么切块"，一个管"怎么召回"。
     */
    private String chunkParams;

    /** 是否默认库：1=默认（新建文档默认归属、未指定库时的兜底） */
    private Integer isDefault;

    /** 创建人（登录用户 uid；未登录为 anonymous） */
    private String createdBy;

    /** 共享范围(JSON: {read_scope:{...},manage_scope:{...}}; 空=全员可见) */
    private String shareConfig;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除: 0=未删除, 1=已删除 */
    private Integer deleted;
}
