package com.wisesoft.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 聊天中的「@ 引用」项：用户在输入框用 @ 手动指定参考资料（当前支持整篇文档）。
 *
 * <p>语义：被 @ 的文档，其知识块会被强制取回并前置到问答上下文（优先级高于普通检索命中），
 * 保证"用户指定必被参考"。与关键词/向量检索结果走同一套上下文填充（图片占位、片段截取、
 * token 预算、去冗余）逻辑，只是排序在最前、且不受 maxContextHits 之外的额外限额挤压。
 *
 * @author yuanke
 */
@Data
@Schema(description = "@ 引用项")
public class ChatRef {

    @Schema(description = "引用类型：doc=整篇文档（chunk=指定知识块，暂未开放）", example = "doc")
    private String type;

    @Schema(description = "引用目标 ID（doc 时为文档 ID）", example = "7f60ac4462cfb6092dc1eac78926624b")
    private String id;

    @Schema(description = "展示名（文档名），仅用于日志与前端回显", example = "操作手册.docx")
    private String name;
}
