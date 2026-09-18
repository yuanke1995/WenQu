package com.wisesoft.wenqu.model;

import java.util.List;

/**
 * 文档解析结果分块（各解析器统一产出）
 * <p>
 * content 只承载净正文：章节路径不拼进正文、分块重叠不拼进正文。
 * 上下文按用途分别拼装——向量化时拼入 embedding 文本（路径 + 重叠尾巴），
 * 问答检索时拼入上下文（路径），因此"前一块变动"不会连锁改变本块指纹。
 *
 * @param title     分块标题（docx 取标题层级，pdf 取页码/章节，excel 取 sheet 名）
 * @param content   分块净正文（可含 [图片：描述] 占位，docx 专用）
 * @param images    分块关联图片 URL 列表（docx 专用，其他格式为空）
 * @param titlePath 章节路径（docx 结构感知切分产出，如 "一级/二级/三级"；无则为空）
 */
public record Chunk(String title, String content, List<String> images, String titlePath) {

    /** 无章节路径的解析器（pdf/excel）使用 */
    public Chunk(String title, String content, List<String> images) {
        this(title, content, images, null);
    }

    /** 章节路径是否存在 */
    public boolean hasTitlePath() {
        return titlePath != null && !titlePath.isBlank();
    }
}
