package com.wenqu.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 游客提问请求体（/api/ai/share/{token}/chat）。
 *
 * <p><b>为什么不继续用 {@code Map<String, String>}</b>：多模态进来之前这个 body 只有
 * message / sessionId / visitorId 三个字符串，一个 Map 足够。现在要带
 * {@code images}（data URL 数组）与 {@code attachments}（对象数组），Map 的字符串值类型立刻不成立；
 * 而改用 {@code Map<String, Object>} 又意味着每个字段都要手写 {@code String.valueOf(body.get(...))}、
 * 类型错了也只在运行时才炸。声明成 DTO 之后字段名即契约，Swagger 也能正确渲染。
 *
 * <p>字段与登录态 {@link ChatRequest} 刻意保持同名同形（{@code images} / {@code attachments}），
 * 这样两类调用方共用同一套 {@link ChatRequest.Attachment} 与同一份校验口径，不产生第二套方言。
 *
 * @author yuanke
 */
@Data
@Schema(description = "游客提问请求（免登录分享链路）")
public class ShareChatRequest {

    @Schema(description = "问题原文", example = "这份合同的违约金怎么算？")
    private String message;

    @Schema(description = "游客会话 ID（空=新建一段对话）")
    private String sessionId;

    @Schema(description = "访客标识（客户端随机生成并本地持久化，用于会话归属）")
    private String visitorId;

    /**
     * 本轮图片：{@code data:image/*;base64,...} 的 data URL 数组。
     * <p>与登录态同一形态（模型侧要的就是 data URL，服务端不再转存一遍）；是否需要落盘由
     * {@code SessionService} 按既有口径决定，这里只做「发布者有没有允许」与「体积/数量上限」两道校验。
     */
    @Schema(description = "本轮图片（data:image/* 的 data URL 数组；需发布者开启「允许游客发图」）")
    private List<String> images;

    @Schema(description = "本轮文档附件（fileId 引用；需发布者开启「允许游客上传附件」）")
    private List<ChatRequest.Attachment> attachments;

    /**
     * 重新生成：该问题的用户消息已随上一轮即时落库，true 时跳过重复落库。
     * <p>与登录态同义——分享页的「重新生成」按钮复用同一条语义，否则每点一次都会在历史里多出一条重复提问。
     */
    @Schema(description = "重新生成本轮（不重复落库用户消息）", example = "false")
    private boolean regenerate;

    /**
     * 重新生成时被替换的旧回答消息 ID。
     * <p><b>归属必须由本控制器校验</b>：{@code SessionService.beginAnswerReplace} 只认消息 ID，
     * 自身不校验会话归属。游客链路若不先验证这条消息属于本访客的会话，任何人拿到分享链接就能
     * 用别人的 messageId 去软删别人的回答。
     */
    @Schema(description = "重新生成时被替换的旧回答消息 ID（须属于本访客的会话）")
    private String replaceMessageId;
}
