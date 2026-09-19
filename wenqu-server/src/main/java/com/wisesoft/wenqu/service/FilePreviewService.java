package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.common.PreviewResult;
import com.wisesoft.wenqu.workspace.WorkspacePreview;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 把中立 Workspace preview 结果装配为 HTTP 响应（services/file_preview.py 全量移植）。
 *
 * <p>必要替换 / 能力差异标注：
 * <ul>
 *   <li>FastAPI {@code StreamingResponse}（二进制流）→ 本项目 {@link BinaryPreview} 承载；
 *       文本/ 不支持预览仍返回 {@code Map}（对应参考实现返回 dict）。
 *       因此 {@link #renderFilePreview} 的返回在运行期为
 *       {@code Map<String,Object>} 或 {@link BinaryPreview} 二选一，调用方按 {@code instanceof} 区分。
 *   <li>{@code urllib.parse.quote} → {@link #urlEncodeUtf8}（UTF-8 百分号编码，保留 RFC 3986 非保留字符）。
 *   <li>响应头按本产品命名改为 {@code X-WenQu-Preview-Type} / {@code X-WenQu-Preview-Filename}
 *       （对应参考实现原有的预览类型/文件名响应头）。
 * </ul>
 */
@Service
public class FilePreviewService {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * 把持久文件预览结果转换为 Workspace/Viewer HTTP 响应载荷。
     *
     * @return 文本/ 不支持预览场景为 {@code Map<String,Object>}；图片/ PDF 二进制为 {@link BinaryPreview}。
     */
    public Object renderFilePreview(String path, byte[] rawContent, String officeCacheKey) {
        PreviewResult result = WorkspacePreview.previewWorkspaceFile(path, rawContent, officeCacheKey);
        return previewResponse(result);
    }

    private static Object previewResponse(PreviewResult result) {
        if (result.getContent() instanceof byte[]) {
            byte[] content = (byte[]) result.getContent();
            String filename = result.getFilename() != null ? result.getFilename() : "preview";
            return new BinaryPreview(
                    content,
                    result.getMediaType() != null ? result.getMediaType() : "application/octet-stream",
                    filename,
                    result.getPreviewType());
        }
        return result.payload();
    }

    /** 对应参考实现 StreamingResponse：承载二进制预览流与响应头元数据。 */
    public static final class BinaryPreview {
        private final byte[] content;
        private final String mediaType;
        private final String filename;
        private final String previewType;

        public BinaryPreview(byte[] content, String mediaType, String filename, String previewType) {
            this.content = content;
            this.mediaType = mediaType;
            this.filename = filename;
            this.previewType = previewType;
        }

        public byte[] getContent() {
            return content;
        }

        public String getMediaType() {
            return mediaType;
        }

        public String getFilename() {
            return filename;
        }

        public String getPreviewType() {
            return previewType;
        }

        /** Content-Disposition: inline; filename*=UTF-8''<percent-encoded>（参考实现 urllib.parse.quote）。 */
        public String contentDisposition() {
            return "inline; filename*=UTF-8''" + urlEncodeUtf8(filename);
        }

        public String previewTypeHeaderName() {
            return "X-WenQu-Preview-Type";
        }

        public String previewFilenameHeaderName() {
            return "X-WenQu-Preview-Filename";
        }

        public String previewFilenameHeaderValue() {
            return urlEncodeUtf8(filename);
        }
    }

    /** 对应 urllib.parse.quote：UTF-8 百分号编码，保留 RFC 3986 非保留字符。 */
    public static String urlEncodeUtf8(String value) {
        StringBuilder sb = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(HEX[(c >> 4) & 0xF]);
                sb.append(HEX[c & 0xF]);
            }
        }
        return sb.toString();
    }
}
