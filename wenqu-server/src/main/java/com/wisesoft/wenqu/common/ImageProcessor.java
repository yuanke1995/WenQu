package com.wisesoft.wenqu.common;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图片处理（utils/image_processor.py 全量移植）：格式校验、EXIF 方向修正、缩略图生成、
 * 超限压缩，返回 base64 内容 + 元信息。
 *
 * <p>被 {@code ChatController} 的 {@code POST /image/upload} 使用；与早期那份
 * {@code ImageCompressor}（docx 解析与图片描述补齐共用，失败回退原图、无质量/尺寸递降）
 * 语义不同，故<b>不复用</b>后者 —— 后者已随旧文档解析链一并移除。
 *
 * <h3>必要替换（Pillow → JDK ImageIO）</h3>
 * <ul>
 *   <li>{@code PIL.Image.open(...).format} → {@link ImageReader#getFormatName()}，再按 PIL 的
 *       大小写归一：{@code JPG→JPEG}、{@code WEBP→WebP}，其余取大写。故
 *       {@code TIFF}/{@code WBMP}（JDK 可读、Pillow 白名单里没有）仍按「不支持的图片格式」
 *       拒绝 —— <b>白名单必须显式判定，不能依赖「JDK 读不出来」</b>，否则会比参考实现更宽松。</li>
 *   <li>{@code img._getexif()} → 手写 JPEG APP1/Exif 段扫描（见 {@link #readExifOrientation}）。
 *       JDK 的 {@code IIOMetadata} 也能取，但需要逐层解 {@code javax_imageio_jpeg_image_1.0}
 *       的 unknown marker 节点，可读性更差。</li>
 *   <li>{@code img.rotate(angle, expand=True)} → {@link #rotateCounterClockwise}（Graphics2D
 *       仿射变换；PIL 的 {@code rotate} 为逆时针，Java 的 y 轴向下故取负角）。</li>
 *   <li>{@code Image.Resampling.LANCZOS} → {@code VALUE_INTERPOLATION_BICUBIC}（JDK 无
 *       Lanczos，重采样核不同 → 同尺寸下像素有细微差异）。</li>
 *   <li>{@code save(..., optimize=True)}：Pillow 专有参数，JDK 无对应项，忽略。</li>
 *   <li>{@code Image.alpha_composite} 白底合成 → {@link #toRgbForExport}（先铺白底再画原图，
 *       等价于「透明像素按白底合成」）。</li>
 * </ul>
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ul>
 *   <li><b>WebP</b>：JDK {@code ImageIO} 的读写格式集为 JPEG/TIFF/BMP/GIF/WBMP/PNG，
 *       <b>不含 WebP</b>（本机 {@code ~/.m2} 亦无 TwelveMonkeys 等插件）。
 *       参考实现经 Pillow 可读 WebP；本工程<b>不静默降级</b>：识别到 WebP 魔数且无可用
 *       reader 时，返回与「不支持的图片格式」同形的错误，文案中显式指出需安装插件。</li>
 *   <li>Pillow 对 PNG 忽略 {@code quality} 参数（PNG 走 {@code compress_level}）。本实现同样不设
 *       PNG 压缩级，故 {@link #compressImage} 的质量递降循环对 PNG 产出相同字节 —— 与参考实现
 *       「循环仍跑但每轮结果不变、最终靠缩放收敛」的<b>行为一致</b>（不是简化）。</li>
 * </ul>
 */
public final class ImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessor.class);

    /** 支持的图片格式（与参考实现同名同值；注意 {@code WebP} 的大小写）。 */
    public static final Set<String> SUPPORTED_FORMATS = Set.of("JPEG", "PNG", "WebP", "GIF", "BMP");

    /** 最大文件大小（5MB）。 */
    public static final int MAX_FILE_SIZE = 5 * 1024 * 1024;

    /** 缩略图尺寸上界（对应参考实现的 {@code THUMBNAIL_SIZE = (200, 200)}）。 */
    public static final int THUMBNAIL_MAX_SIDE = 200;

    /** JPEG 编码初始质量 / 递降步长（对应参考实现的 {@code quality = 85} 与 {@code -= 10}）。 */
    private static final int INITIAL_QUALITY = 85;
    private static final int QUALITY_STEP = 10;
    private static final int QUALITY_FLOOR = 10;

    /** 质量递降后仍超限时的缩放序列（对应参考实现的 {@code 0.9 → 0.3}，步长 {@code 0.1}）。 */
    private static final double SCALE_START = 0.9;
    private static final double SCALE_FLOOR = 0.3;
    private static final double SCALE_STEP = 0.1;

    /** EXIF 方向标签号（TIFF tag 0x0112）。 */
    private static final int EXIF_ORIENTATION_TAG = 0x0112;

    private ImageProcessor() {}

    /** 处理上传的图片（对应参考实现 {@code process_uploaded_image(image_data, filename="")}）。 */
    public static Map<String, Object> processUploadedImage(byte[] imageData) {
        return processUploadedImage(imageData, "");
    }

    /**
     * 处理上传的图片。
     *
     * <p>失败不抛错，返回 {@code {success: false, error: <消息>}} —— 与参考实现的
     * {@code except Exception → return {"success": False, "error": str(e)}} 一致；
     * 由调用方（控制器）据 {@code success} 决定是否 400。
     */
    public static Map<String, Object> processUploadedImage(byte[] imageData, String originalFilename) {
        try {
            ImageFormat detected = detectImageFormat(imageData);
            if (!SUPPORTED_FORMATS.contains(detected.name())) {
                throw new IllegalArgumentException("不支持的图片格式: " + detected.name());
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                throw new IllegalArgumentException("无效的图片格式: 无法解码图片数据");
            }

            // 处理 EXIF 方向信息（失败仅告警，沿用原始方向）
            image = fixImageOrientation(image, imageData);

            // 生成缩略图（内部自带失败兜底：1x1 白色 JPEG）
            byte[] thumbnailData = generateThumbnail(image);

            // 压缩主图片（如果需要）
            CompressedImage compressed = compressImage(image, detected.name());
            byte[] processedData = compressed.data();

            String base64Data = Base64.getEncoder().encodeToString(processedData);
            String base64Thumbnail = Base64.getEncoder().encodeToString(thumbnailData);

            int width = image.getWidth();
            int height = image.getHeight();
            String finalFormat = compressed.format();
            String mimeType = "image/" + finalFormat.toLowerCase(Locale.ROOT);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("image_content", base64Data);
            result.put("thumbnail_content", base64Thumbnail);
            result.put("width", width);
            result.put("height", height);
            result.put("format", finalFormat);
            result.put("mime_type", mimeType);
            result.put("size_bytes", processedData.length);
            // 参考实现同样回传该项；router 的 response_model 会丢弃它（本工程控制器层同样投影掉）
            result.put("original_filename", originalFilename == null ? "" : originalFilename);
            return result;

        } catch (RuntimeException exc) {
            log.error("图片处理失败: {}", exc.getMessage());
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("success", false);
            failure.put("error", exc.getMessage());
            return failure;
        } catch (IOException exc) {
            log.error("图片处理失败: {}", exc.getMessage());
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("success", false);
            failure.put("error", exc.getMessage());
            return failure;
        }
    }

    // =====================================================================
    // === 格式校验 ===
    // =====================================================================

    /** 探测到的容器格式（{@code name} 已按 PIL 口径归一）。 */
    private record ImageFormat(String name) {}

    /**
     * 格式校验（对应参考实现 {@code _validate_image_format}）。
     *
     * <p>与参考实现的差异：Pillow 用 magic 嗅探并直接给出格式名；本实现先取 JDK 注册的
     * reader 的格式名，若 reader 缺失（如 WebP）则回退到内置魔数表 —— 这样「JDK 读不出」
     * 仍能被翻译成参考实现同形的「不支持的图片格式」，而不是含糊的解码失败。
     */
    private static ImageFormat detectImageFormat(byte[] imageData) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(imageData))) {
            if (stream == null) {
                throw new IllegalArgumentException("无效的图片格式: 无法读取字节流");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    return new ImageFormat(normalizeFormatName(reader.getFormatName()));
                } finally {
                    reader.dispose();
                }
            }
        } catch (java.io.IOException exc) {
            throw new IllegalArgumentException("无效的图片格式: " + exc.getMessage());
        }

        String sniffed = sniffFormatByMagic(imageData);
        if (sniffed != null) {
            if ("WebP".equals(sniffed)) {
                // 能力差异显式暴露：不静默当作「无效图片」，也不假装能处理
                throw new IllegalArgumentException(
                        "不支持的图片格式: WebP（JVM 未注册 WebP 解码器，需安装 ImageIO WebP 插件）");
            }
            return new ImageFormat(sniffed);
        }
        throw new IllegalArgumentException("无效的图片格式: 无法识别的图片数据");
    }

    /** 把 JDK 的格式名归一为 PIL 口径（{@code img.format}）。 */
    private static String normalizeFormatName(String jdkName) {
        String upper = jdkName == null ? "" : jdkName.toUpperCase(Locale.ROOT);
        if ("JPG".equals(upper)) {
            return "JPEG";
        }
        if ("WEBP".equals(upper)) {
            return "WebP";
        }
        return upper;
    }

    /** 内置魔数表（仅覆盖参考实现白名单里的格式）。 */
    private static String sniffFormatByMagic(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return "JPEG";
        }
        if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "PNG";
        }
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') {
            return "GIF";
        }
        if (data[0] == 'B' && data[1] == 'M') {
            return "BMP";
        }
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "WebP";
        }
        return null;
    }

    // =====================================================================
    // === EXIF 方向修正 ===
    // =====================================================================

    /**
     * 按 EXIF 方向修正图片（对应参考实现 {@code _fix_image_orientation}）。
     *
     * <p>只处理 3 / 6 / 8 三个值（与参考实现一致：1/2/4/5/7 不做变换）；
     * 任何异常仅告警并沿用原始方向。
     */
    private static BufferedImage fixImageOrientation(BufferedImage image, byte[] imageData) {
        try {
            Integer orientation = readExifOrientation(imageData);
            if (orientation == null) {
                return image;
            }
            return switch (orientation) {
                case 3 -> rotateCounterClockwise(image, 180);
                case 6 -> rotateCounterClockwise(image, 270);
                case 8 -> rotateCounterClockwise(image, 90);
                default -> image;
            };
        } catch (RuntimeException exc) {
            log.warn("修正图片方向失败，使用原始方向: {}", exc.getMessage());
            return image;
        }
    }

    /**
     * 从 JPEG 的 APP1/Exif 段读取 Orientation（TIFF tag 0x0112）。
     *
     * <p>非 JPEG 或没有 EXIF 时返回 {@code null}。
     */
    static Integer readExifOrientation(byte[] data) {
        if (data == null || data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return null;
        }
        int offset = 2;
        while (offset + 4 <= data.length) {
            if ((data[offset] & 0xFF) != 0xFF) {
                return null;
            }
            int marker = data[offset + 1] & 0xFF;
            // SOS(0xDA) / EOI(0xD9) 之后不再有元数据段；0xFF 填充字节按 JPEG 规范跳过
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0xFF) {
                offset += 2;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return null;
            }
            int segmentLength = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
            if (segmentLength < 2 || offset + 2 + segmentLength > data.length) {
                return null;
            }
            // APP1 且以 "Exif\0\0" 开头 → TIFF 头从段内偏移 6 开始
            if (marker == 0xE1
                    && segmentLength >= 8
                    && data[offset + 4] == 'E' && data[offset + 5] == 'x' && data[offset + 6] == 'i'
                    && data[offset + 7] == 'f' && data[offset + 8] == 0 && data[offset + 9] == 0) {
                return parseTiffOrientation(data, offset + 4 + 6, offset + 2 + segmentLength);
            }
            offset += 2 + segmentLength;
        }
        return null;
    }

    /** 解析 TIFF 头并查找 Orientation 标签（只走 IFD0，与 Pillow 的 {@code _getexif()} 一致）。 */
    private static Integer parseTiffOrientation(byte[] data, int tiffStart, int tiffEnd) {
        if (tiffStart + 8 > tiffEnd || tiffStart + 8 > data.length) {
            return null;
        }
        boolean littleEndian;
        if (data[tiffStart] == 'I' && data[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (data[tiffStart] == 'M' && data[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return null;
        }
        int ifdOffset = readInt(data, tiffStart + 4, littleEndian, tiffEnd);
        if (ifdOffset <= 0) {
            return null;
        }
        int entryCountOffset = tiffStart + ifdOffset;
        int entryCount = readShort(data, entryCountOffset, littleEndian, tiffEnd);
        if (entryCount <= 0) {
            return null;
        }
        for (int index = 0; index < entryCount; index++) {
            int entryOffset = entryCountOffset + 2 + index * 12;
            if (entryOffset + 12 > tiffEnd || entryOffset + 12 > data.length) {
                return null;
            }
            int tag = readShort(data, entryOffset, littleEndian, tiffEnd);
            if (tag != EXIF_ORIENTATION_TAG) {
                continue;
            }
            // Orientation 是 SHORT(3)，值内联在条目末尾 4 字节的开头 2 字节
            return readShort(data, entryOffset + 8, littleEndian, tiffEnd);
        }
        return null;
    }

    private static int readShort(byte[] data, int offset, boolean littleEndian, int limit) {
        if (offset + 2 > limit || offset + 2 > data.length) {
            return -1;
        }
        int first = data[offset] & 0xFF;
        int second = data[offset + 1] & 0xFF;
        return littleEndian ? (first | (second << 8)) : ((first << 8) | second);
    }

    private static int readInt(byte[] data, int offset, boolean littleEndian, int limit) {
        if (offset + 4 > limit || offset + 4 > data.length) {
            return -1;
        }
        if (littleEndian) {
            return (data[offset] & 0xFF)
                    | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 24);
        }
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /** 逆时针旋转（对应 PIL {@code Image.rotate(angle, expand=True)}，角度为逆时针度数）。 */
    private static BufferedImage rotateCounterClockwise(BufferedImage source, int degrees) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage target;
        Graphics2D graphics;
        if (degrees == 180) {
            target = new BufferedImage(width, height, bufferType(source));
            graphics = target.createGraphics();
            graphics.rotate(Math.PI, width / 2.0, height / 2.0);
        } else if (degrees == 90) {
            target = new BufferedImage(height, width, bufferType(source));
            graphics = target.createGraphics();
            graphics.translate(height, 0);
            graphics.rotate(-Math.PI / 2);
        } else if (degrees == 270) {
            target = new BufferedImage(height, width, bufferType(source));
            graphics = target.createGraphics();
            graphics.translate(0, width);
            graphics.rotate(Math.PI / 2);
        } else {
            return source;
        }
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    // =====================================================================
    // === 缩略图 ===
    // =====================================================================

    /** 生成缩略图（对应参考实现 {@code _generate_thumbnail}）。 */
    private static byte[] generateThumbnail(BufferedImage image) {
        try {
            BufferedImage rgb = toRgbForExport(image);
            BufferedImage scaled = scaleToFit(rgb, THUMBNAIL_MAX_SIDE);
            return encodeJpeg(scaled, INITIAL_QUALITY);
        } catch (RuntimeException exc) {
            log.error("生成缩略图失败: {}", exc.getMessage());
            // 兜底：1x1 白色 JPEG（参考实现同款兜底）
            BufferedImage empty = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = empty.createGraphics();
            try {
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, 1, 1);
            } finally {
                graphics.dispose();
            }
            return encodeJpeg(empty, INITIAL_QUALITY);
        }
    }

    // =====================================================================
    // === 压缩 ===
    // =====================================================================

    /** 压缩结果（数据 + 最终格式名）。 */
    private record CompressedImage(byte[] data, String format) {}

    /**
     * 压缩图片（对应参考实现 {@code _compress_image}）：先降质量，仍超限再逐步缩小尺寸。
     *
     * <p>目标格式：原格式为 PNG 则保 PNG，其余一律 JPEG（与参考实现一致）。
     */
    private static CompressedImage compressImage(BufferedImage image, String originalFormat) {
        BufferedImage processed = toRgbForExport(image);
        String targetFormat = "PNG".equals(originalFormat) ? "PNG" : "JPEG";

        int quality = INITIAL_QUALITY;
        byte[] compressedData = encode(processed, targetFormat, quality);
        if (compressedData.length <= MAX_FILE_SIZE) {
            return new CompressedImage(compressedData, targetFormat);
        }

        while (compressedData.length > MAX_FILE_SIZE && quality > QUALITY_FLOOR) {
            quality -= QUALITY_STEP;
            compressedData = encode(processed, targetFormat, quality);
        }

        if (compressedData.length > MAX_FILE_SIZE) {
            double scaleFactor = SCALE_START;
            while (compressedData.length > MAX_FILE_SIZE && scaleFactor > SCALE_FLOOR) {
                int newWidth = (int) (processed.getWidth() * scaleFactor);
                int newHeight = (int) (processed.getHeight() * scaleFactor);
                if (newWidth < 1 || newHeight < 1) {
                    break;
                }
                BufferedImage resized = resize(processed, newWidth, newHeight);
                compressedData = encode(resized, targetFormat, INITIAL_QUALITY);
                scaleFactor -= SCALE_STEP;
            }
        }

        return new CompressedImage(compressedData, targetFormat);
    }

    // =====================================================================
    // === 像素工具 ===
    // =====================================================================

    /**
     * 转为 RGB，透明像素按白底合成（对应参考实现 {@code _convert_to_rgb_for_export}）。
     *
     * <p>「先铺白底再画原图」等价于 Pillow 的 {@code background.alpha_composite(rgba_img)}，
     * 且不会把隐藏颜色透出来。
     */
    static BufferedImage toRgbForExport(BufferedImage image) {
        boolean hasAlpha = image.getColorModel().hasAlpha();
        if (!hasAlpha) {
            if (image.getType() == BufferedImage.TYPE_INT_RGB) {
                return copy(image);
            }
            return convertToType(image, BufferedImage.TYPE_INT_RGB, false);
        }
        return convertToType(image, BufferedImage.TYPE_INT_RGB, true);
    }

    private static BufferedImage copy(BufferedImage image) {
        BufferedImage target = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static BufferedImage convertToType(BufferedImage image, int type, boolean whiteBackground) {
        BufferedImage target = new BufferedImage(image.getWidth(), image.getHeight(), type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            if (whiteBackground) {
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            }
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** 等比缩放到最长边不超过 {@code maxSide}（Pillow 的 {@code thumbnail} 只缩不放）。 */
    private static BufferedImage scaleToFit(BufferedImage image, int maxSide) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest <= maxSide || longest == 0) {
            return image;
        }
        int newWidth = (int) Math.round(image.getWidth() * (double) maxSide / longest);
        int newHeight = (int) Math.round(image.getHeight() * (double) maxSide / longest);
        return resize(image, Math.max(newWidth, 1), Math.max(newHeight, 1));
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static int bufferType(BufferedImage image) {
        int type = image.getType();
        return type == 0 ? BufferedImage.TYPE_INT_ARGB : type;
    }

    // =====================================================================
    // === 编码 ===
    // =====================================================================

    /** JPEG 编码（质量按 0~1 归一）。 */
    private static byte[] encodeJpeg(BufferedImage image, int qualityPercent) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("JVM 未注册 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(qualityPercent / 100f);
            writer.setOutput(imageOutput);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (java.io.IOException exc) {
            throw new IllegalStateException("JPEG 编码失败: " + exc.getMessage(), exc);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    /**
     * 按目标格式编码。
     *
     * <p>PNG 不设压缩质量（Pillow 对 PNG 同样忽略 {@code quality}），因此质量递降循环对 PNG
     * 产出相同字节 —— 与参考实现行为一致。
     */
    private static byte[] encode(BufferedImage image, String targetFormat, int qualityPercent) {
        if ("JPEG".equals(targetFormat)) {
            return encodeJpeg(image, qualityPercent);
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("JVM 未注册 PNG 编码器");
            }
            return output.toByteArray();
        } catch (java.io.IOException exc) {
            throw new IllegalStateException("PNG 编码失败: " + exc.getMessage(), exc);
        }
    }
}
