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

    @Schema(description = "智能体 ID（P3：4.1 Agent 配置）；非空时该轮问答按智能体覆盖模型/提示词/工具/知识库范围，未填维度继承全局")
    private String agentId;
}