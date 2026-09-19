package com.wisesoft.wenqu.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 与存储和 HTTP 无关的文件预览结果（utils/filepreview.py 的 PreviewResult 数据类移植）。
 *
 * <p>content 在运行期可能是 {@code String}（文本/ Markdown/ HTML）、{@code byte[]}（图片/ PDF）
 * 或 {@code null}（不支持预览）。{@link #payload()} 返回文本或不支持预览场景使用的字典，
 * 与参考实现保持一致。
 */
public class PreviewResult {

    private final Object content;
    private final String previewType;
    private final boolean supported;
    private final String mediaType;
    private final String filename;
    private final String message;
    private final boolean truncated;
    private final Integer limit;

    public PreviewResult(Object content, String previewType, boolean supported,
                         String mediaType, String filename, String message,
                         boolean truncated, Integer limit) {
        this.content = content;
        this.previewType = previewType;
        this.supported = supported;
        this.mediaType = mediaType;
        this.filename = filename;
        this.message = message;
        this.truncated = truncated;
        this.limit = limit;
    }

    public Object getContent() {
        return content;
    }

    public String getPreviewType() {
        return previewType;
    }

    public boolean isSupported() {
        return supported;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getFilename() {
        return filename;
    }

    public String getMessage() {
        return message;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public Integer getLimit() {
        return limit;
    }

    /** 返回文本或不支持预览使用的数据（参考实现 PreviewResult.payload）。 */
    public Map<String, Object> payload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("content", content instanceof String ? content : null);
        map.put("preview_type", previewType);
        map.put("supported", supported);
        map.put("message", message);
        map.put("truncated", truncated);
        map.put("limit", limit);
        return map;
    }
}
