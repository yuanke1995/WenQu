package com.wisesoft.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 聊天请求 DTO
 *
 * @author yuanke
 */
@Data
@Schema(description = "聊天请求")
public class ChatRequest {
    @Schema(description = "会话 ID（为空则自动创建新会话）", example = "uuid-xxxx")
    private String sessionId;

    @NotBlank(message = "请输入问题")
    @Size(max = 8000, message = "问题过长（最多 8000 字）")
    @Schema(description = "用户问题", example = "如何创建评分组件？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String question;

    @Schema(description = "用户上传图片（data URL 格式，如 data:image/jpeg;base64,xxx）")
    private List<String> images;

    @Schema(description = "是否深度思考（思考流式展示 + 多路检索增强）", example = "false")
    private boolean deepThink;

    @Schema(description = "智能体 ID；\"auto\"=自动派遣（当轮生效模型按名称+描述从可见主智能体中挑选，失败回落默认智能体）；"
            + "其他非空值=该轮问答按该智能体的提示词/工具/知识库范围执行；空=继承全局配置")
    private String agentId;

    @Schema(description = "会话级模型覆盖（引用 providerId/modelId 或遗留模型名；仅用户在聊天页手动切换时传，空=个人默认，全局兜底已退役）")
    private String model;
}