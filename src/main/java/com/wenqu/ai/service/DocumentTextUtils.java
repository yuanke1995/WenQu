package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;

import java.util.List;

/**
 * 文档文本处理纯函数：内容指纹、embedding 文本拼装、images 字段解析。
 * <p>
 * 从 {@link DocumentService} 抽出：无状态、无依赖，解析链路与向量重建链路共用同一口径，
 * 避免两处各写一份导致「指纹算法漂移 → 增量对比永远失配」。
 */
public final class DocumentTextUtils {

    private DocumentTextUtils() {
    }

    /**
     * 内容指纹：SHA-256(title + "\n" + titlePath + "\n" + content + "\n" + images)
     * 重解析增量对比（未变块保留向量）、知识块编辑/新增维护用。
     * 含章节路径（章节改名重嵌该章，语义正确）、含 images（图片删除/替换 → 块走新增，避免引用已删图片）。
     * 不含分块重叠尾巴：前块变动不会连锁改变本块指纹。
     */
    public static String contentHash(String title, String content) {
        return contentHash(title, null, content, List.of());
    }

    public static String contentHash(String title, String titlePath, String content, List<String> images) {
        try {
            String img = images == null || images.isEmpty() ? "" : String.join(",", images);
            String path = titlePath == null ? "" : titlePath;
            return com.wenqu.ai.util.Hashes.sha256Hex(title + "\n" + path + "\n" + content + "\n" + img);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 向量化/embedding 文本统一拼装：title + 【上下文】章节路径 + 重叠尾巴 + 净正文。
     * 入库 content 只存净正文；路径与重叠仅作为向量语义的上下文输入，检索时路径另由 ChatService 拼装。
     */
    public static String buildEmbedText(String title, String titlePath, String content, String overlapPrefix) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) sb.append(title).append("\n");
        if (titlePath != null && !titlePath.isBlank()) sb.append("【上下文】").append(titlePath).append("\n\n");
        if (overlapPrefix != null && !overlapPrefix.isBlank()) sb.append(overlapPrefix);
        sb.append(content);
        return sb.toString();
    }

    /** 知识块 images 字段（JSON 数组串）→ List<String>（空/解析失败返回空列表） */
    public static List<String> parseImages(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 错误信息截断：统一 200 字上限，null 归「未知错误」 */
    public static String truncate(String s) {
        if (s == null) return "未知错误";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
