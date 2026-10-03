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

    @Schema(description = "用户上传附件（文档类，非图片）：先经 /chat/attachment 上传换 fileId，这里只带引用（不内联内容）")
    private List<Attachment> attachments;

    @Schema(description = "输入框 @ 引用（本轮显式指定的资料范围）：kb=检索收窄到这些知识库；doc=该文档的块强制进上下文")
    private List<Mention> mentions;

    @Schema(description = "输入框 # 引用的会话历史消息：服务端按 messageId 从当前会话读回内容，前置进本轮上下文（不信任客户端传的任何文本）")
    private List<HistoryRef> historyRefs;

    /**
     * # 历史引用（用户从本会话历史中显式挑选的问答）：
     * messageId 必须属于当前会话（服务端同步段校验，fail-loud：不存在/跨会话直接拒绝）。
     * 与 @ doc 的差异：doc 从知识库取解析块，这里从会话消息表取原文——
     * 内容一律服务端按 id 查库回填，客户端只传引用不传文本。
     */
    @Data
    @Schema(description = "# 历史引用项")
    public static class HistoryRef {
        @Schema(description = "被引用的消息 ID（须属于当前会话）", example = "uuid-xxxx")
        private String messageId;

        @Schema(description = "角色（服务端按库回填：user / assistant），客户端传值不采信", example = "assistant")
        private String role;

        @Schema(description = "消息内容（服务端按库回填，客户端传值不采信）")
        private String content;
    }

    /**
     * @ 引用（用户显式指定，优先于智能体配置）：
     * <ul>
     *   <li>{@code kb}：本轮检索范围收窄到被引库；</li>
     *   <li>{@code doc}：该文档的内容块**不经检索直接前置**进上下文（用户认为它相关，不该被相关性门/排序挡掉）。</li>
     * </ul>
     * 服务端会按当前用户做可见性校验（fail-loud：不可见/不存在直接拒绝，不静默忽略）。
     */
    @Data
    @Schema(description = "@ 引用项")
    public static class Mention {
        @Schema(description = "类型：kb=知识库 / doc=文档", example = "doc")
        private String type;

        @Schema(description = "资源 ID", example = "uuid-xxxx")
        private String id;

        @Schema(description = "展示名（仅回显参考，服务端不信任该值，以 id 查库为准）", example = "需求说明.docx")
        private String name;

        @Schema(description = "type=doc 时由服务端回填的所属知识库 ID（检索范围据此收窄到该库）")
        private String kbId;
    }

    @Schema(description = "本轮指定使用的技能名列表（输入框「+」菜单主动选用；注入技能全文到本轮 system prompt）")
    private List<String> skills;

    /**
     * 聊天附件（文档类）：图片走 images（多模态 data URL），其余文件先上传换 fileId，
     * 服务端按 (fileId, 当前用户) 读回并解析文本注入本轮上下文。
     * <p>不再内联 base64：5×15MB 附件会打出 ~100MB 的 JSON body，弱网必挂且失败要整包重传。
     */
    @Data
    @Schema(description = "聊天附件")
    public static class Attachment {
        @Schema(description = "上传接口返回的文件标识（/chat/attachment）", example = "3f2b...c9")
        private String fileId;

        @Schema(description = "文件名（含扩展名；仅回显与类型判定用，服务端以元信息为准）", example = "需求说明.docx")
        private String name;

        @Schema(description = "MIME 类型", example = "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        private String mime;
    }

    @Schema(description = "是否深度思考（思考流式展示 + 多路检索增强）", example = "false")
    private boolean deepThink;

    @Schema(description = "本轮思考强度档位：low/medium/high/xhigh/max；须为生效模型在模型库登记的「支持档位」之一"
            + "（前端只在模型支持的档位里给出可选项）。空=用模型登记的默认档位。非法值一律忽略并回退默认档位，"
            + "不静默改写用户选择。")
    private String reasoningLevel;

    @Schema(description = "智能体 ID；\"auto\"=自动派遣（当轮生效模型按名称+描述从可见主智能体中挑选，失败回落默认智能体）；"
            + "其他非空值=该轮问答按该智能体的提示词/工具/知识库范围执行；空=继承全局配置")
    private String agentId;

    @Schema(description = "会话级模型覆盖（引用 providerId/modelId 或遗留模型名；仅用户在聊天页手动切换时传，空=个人默认，全局兜底已退役）")
    private String model;

    @Schema(description = "重新生成/自动重试标记：true=该问题的用户消息已随上一轮请求即时落库，后端跳过重复落库（防重发产生重复历史行）")
    private boolean regenerate;

    @Schema(description = "重新生成时被替换的旧回答消息 ID：落库前软删该条，历史里只留最新一版"
            + "（不传则新回答会与旧回答并存，刷新后同一问题出现两条答案）")
    private String replaceMessageId;
}