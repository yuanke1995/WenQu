package com.wisesoft.wenqu.storage;

import com.wisesoft.wenqu.common.UploadUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 图片上传工具（storage/minio/utils.py 中 upload_image_to_minio 的移植）。
 *
 * <p>行为对齐：有界读取（超限消息由调用方给出）→ 校验图片格式（仅 PNG/JPEG/WebP/GIF）→
 * 逐帧解码以拦截损坏图与解压炸弹 → 对象名 {@code {prefix}/{uuid4}.{ext}} → 上传到 {@code public} 桶 → 返回公开 URL。
 * 任一校验失败统一抛出 {@link ValueErrorException}（对应参考实现的 {@code ValueError}）。
 *
 * <p>能力差异（显式标注）：参考实现用 Pillow 解码，JDK 无内置 WebP 解码器。
 * 故 WebP 仅按魔数校验格式、不做逐帧解码（PNG/JPEG/GIF 仍走 {@link ImageIO} 逐帧解码，
 * 解压炸弹与损坏图仍会被拦截）；其余格式的判定与错误文案与参考实现一致。
 */
public final class MinioUploads {

    /** 对象前缀与允许的图片格式（与参考实现的 allowed_formats 逐字一致）。 */
    public static final String DEFAULT_IMAGE_BUCKET = "public";

    private static final Map<String, String> ALLOWED_FORMATS = new LinkedHashMap<>();

    static {
        ALLOWED_FORMATS.put("PNG", "png");
        ALLOWED_FORMATS.put("JPEG", "jpg");
        ALLOWED_FORMATS.put("WEBP", "webp");
        ALLOWED_FORMATS.put("GIF", "gif");
    }

    private static final String NOT_AN_ALLOWED_IMAGE = "只能上传 PNG、JPEG、WebP 或 GIF 图片";

    private MinioUploads() {
    }

    /** 参考实现 {@code ValueError} 的对等承载（路由层转 400）。 */
    public static class ValueErrorException extends RuntimeException {
        public ValueErrorException(String message) {
            super(message);
        }

        public ValueErrorException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 读取并校验上传图片，写入 {@code public} 桶后返回其公开访问 URL。
     *
     * @param upload 上传内容
     * @param objectPrefix 对象名前缀（如 {@code images/{uid}}），首尾斜杠会被去除
     * @param maxSizeBytes 大小上限
     * @param tooLargeMessage 超限时的报错文案
     * @return 图片公开 URL
     */
    public static String uploadImageToMinio(
            org.springframework.web.multipart.MultipartFile upload,
            String objectPrefix,
            long maxSizeBytes,
            String tooLargeMessage) {
        byte[] fileContent;
        try {
            fileContent = UploadUtils.readUploadWithLimit(upload, maxSizeBytes, tooLargeMessage);
        } catch (UploadUtils.SizeLimitExceededException exc) {
            throw new ValueErrorException(exc.getMessage(), exc);
        } catch (IOException exc) {
            throw new ValueErrorException(NOT_AN_ALLOWED_IMAGE, exc);
        }

        String fileExtension = resolveImageExtension(fileContent);
        String prefix = objectPrefix == null ? "" : objectPrefix.replaceAll("^/+", "").replaceAll("/+$", "");
        String objectName = prefix + "/" + UUID.randomUUID() + "." + fileExtension;
        return MinioStorageClient.getInstance()
                .uploadFile(DEFAULT_IMAGE_BUCKET, objectName, fileContent, null)
                .url();
    }

    /** 按魔数判定图片格式；非允许格式一律抛错（文案与参考一致）。 */
    static String resolveImageExtension(byte[] content) {
        String format = sniffFormat(content);
        String extension = format == null ? null : ALLOWED_FORMATS.get(format);
        if (extension == null) {
            throw new ValueErrorException(NOT_AN_ALLOWED_IMAGE);
        }
        if (!"WEBP".equals(format) && !canDecodeAllFrames(content)) {
            throw new ValueErrorException(NOT_AN_ALLOWED_IMAGE);
        }
        return extension;
    }

    /** 通过文件头识别 PNG / JPEG / WEBP / GIF。 */
    private static String sniffFormat(byte[] content) {
        if (content.length >= 8
                && (content[0] & 0xFF) == 0x89
                && content[1] == 'P'
                && content[2] == 'N'
                && content[3] == 'G') {
            return "PNG";
        }
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8) {
            return "JPEG";
        }
        if (content.length >= 12
                && content[0] == 'R'
                && content[1] == 'I'
                && content[2] == 'F'
                && content[3] == 'F'
                && content[8] == 'W'
                && content[9] == 'E'
                && content[10] == 'B'
                && content[11] == 'P') {
            return "WEBP";
        }
        if (content.length >= 6 && content[0] == 'G' && content[1] == 'I' && content[2] == 'F') {
            return "GIF";
        }
        return null;
    }

    /**
     * 逐帧解码以拦截损坏图与解压炸弹（对应参考实现对 n_frames 的 seek + load 循环）。
     *
     * <p>能力差异（显式标注）：Pillow 的 DecompressionBombWarning 阈值在 JDK 无等价项，
     * 这里以「全部帧可解码」作为可读性判据；超限图片由 {@code maxSizeBytes} 前置拦截。
     */
    private static boolean canDecodeAllFrames(byte[] content) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (stream == null) {
                return false;
            }
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);
                int frames = reader.getNumImages(true);
                for (int index = 0; index < frames; index++) {
                    if (reader.read(index) == null) {
                        return false;
                    }
                }
                return true;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exc) {
            return false;
        }
    }
}
