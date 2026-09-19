package com.wisesoft.wenqu.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 上传读取工具（utils/upload_utils.py 的 read_upload_with_limit 全量移植）。
 *
 * <p>能力差异（显式标注）：FastAPI {@code UploadFile}（可 seek 的临时文件句柄）→
 * Spring {@code MultipartFile}（内容已在内存/临时文件，无需 seek(0)）。
 * {@code write_upload_to_buffer}/{@code write_upload_to_path} 的目标端
 * （MinIO / Workdir 原子上传）由调用方按各自入口完成，此处提供统一的有界读取。
 */
public final class UploadUtils {

    /** 参考实现模块常量。 */
    public static final int MAX_UPLOAD_SIZE_BYTES = 100 * 1024 * 1024;
    public static final int CHUNK_SIZE = 1024 * 1024;

    private UploadUtils() {}

    /** 以固定分块读取上传内容，超过上限即抛错（消息与参考一致）。 */
    public static byte[] readUploadWithLimit(
            InputStream upload, long maxSizeBytes, String tooLargeMessage) throws IOException {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK_SIZE];
        long written = 0;
        int read;
        while ((read = upload.read(buffer)) > 0) {
            written += read;
            if (written > maxSizeBytes) {
                throw new SizeLimitExceededException(tooLargeMessage);
            }
            chunks.write(buffer, 0, read);
        }
        return chunks.toByteArray();
    }

    /** Spring MultipartFile 便捷入口。 */
    public static byte[] readUploadWithLimit(
            org.springframework.web.multipart.MultipartFile upload,
            long maxSizeBytes,
            String tooLargeMessage) throws IOException {
        try (InputStream in = upload.getInputStream()) {
            return readUploadWithLimit(in, maxSizeBytes, tooLargeMessage);
        }
    }

    /** Python ValueError 的对等承载（调用方转 400）。 */
    public static class SizeLimitExceededException extends RuntimeException {
        public SizeLimitExceededException(String message) {
            super(message);
        }
    }
}
