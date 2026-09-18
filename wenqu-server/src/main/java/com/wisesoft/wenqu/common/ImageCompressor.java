package com.wisesoft.wenqu.common;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 图片压缩工具：docx 解析压缩与"图片描述补齐"（解析后补描述回写知识块）共用。
 * <p>
 * 双图策略：压缩图只进内存供视觉模型识别（不落盘），原图落盘用于展示——
 * 因此"按 URL 补描述"时也需要先按相同规则压缩再调视觉模型。
 */
public final class ImageCompressor {

    /** 压缩结果：字节 + 输出扩展名（透明图 png，否则 jpg） */
    public record CompressedImage(byte[] bytes, String ext) {
    }

    private ImageCompressor() {
    }

    /**
     * 图片压缩：等比缩放（最长边超过 maxWidth 时）+ 重编码。
     * 任一步失败回退原图（不阻断主流程）。
     *
     * @param original 原图字节
     * @param ext      原图扩展名（png/jpg/jpeg/...）
     * @param maxWidth 最长边上限（0=不缩放）
     * @param quality  JPEG 压缩质量（0~1）
     */
    public static CompressedImage compress(byte[] original, String ext, int maxWidth, float quality) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(original));
            if (img == null) return new CompressedImage(original, ext);
            // 按最长边缩放（宽或高超限都等比缩小，竖长图不再绕过）
            int longest = Math.max(img.getWidth(), img.getHeight());
            if (maxWidth > 0 && longest > maxWidth) {
                int w = (int) Math.round(img.getWidth() * (double) maxWidth / longest);
                int h = (int) Math.round(img.getHeight() * (double) maxWidth / longest);
                int type = img.getType() == 0 ? BufferedImage.TYPE_INT_RGB : img.getType();
                BufferedImage scaled = new BufferedImage(w, h, type);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(img, 0, 0, w, h, null);
                g.dispose();
                img = scaled;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (img.getColorModel().hasAlpha()) {
                ImageIO.write(img, "png", out);
                return new CompressedImage(out.toByteArray(), "png");
            }
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(img);
            } finally {
                writer.dispose();
            }
            return new CompressedImage(out.toByteArray(), "jpg");
        } catch (Exception e) {
            return new CompressedImage(original, ext);
        }
    }
}
