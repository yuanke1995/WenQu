package com.wisesoft.ai.model;

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
 *   <li><b>文档容器</b>：文档必归属于某个知识库（{@code c_ai_document.kb_id}，历史空值由启动迁移归入默认库）。</li>
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

    /** 知识库业务ID：表主键列为 {@code kb_id}（{@code c_ai_document.kb_id} 的外键指向该列），
     *  必须显式指定列名——默认按字段名映射到 {@code id} 列会读不到业务ID。 */
    @TableId(value = "kb_id", type = IdType.ASSIGN_UUID)
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
     * 解析参数（JSON：{"chunk.maxSize":"800","chunk.overlap":"100",...,"visionRef":"providerId/modelId"}）。
     * 空=全部继承全局解析设置（chunk.*）；仅对之后解析/重解析的文档生效；
     * visionRef 为本库图片描述用的视觉模型（文档解析把图片转文字供向量召回），空=解析时跳过图片描述。
     */
    private String parseParams;

    /** 本库绑定向量模型（引用 providerId/modelId；必填——向量空间与索引一一对应，
     * 没有可用的"运行时跟随全局"兜底，历史空值由启动迁移回填）。
     * 向量一致性约束是"单库"级：同库所有块必须同一向量模型，换模型触发本库重嵌入。 */
    private String embeddingRef;

    /** 本库向量索引维度（绑定/切换向量模型重嵌后回写；空=未记录） */
    private Integer embeddingDimensions;

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
