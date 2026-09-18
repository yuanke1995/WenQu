package com.wisesoft.wenqu.parser;

import com.wisesoft.wenqu.config.AppProperties;
import com.wisesoft.wenqu.model.Chunk;
import com.wisesoft.wenqu.service.ConfigService;
import com.wisesoft.wenqu.service.VisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析器（PDFBox 文本抽取 + 扫描件 OCR 降级）
 * 纯文本抽取（按页合并 + 超长切分）；若整份文本极少（扫描件/图片型 PDF），
 * 降级为逐页渲染图片 → 本地视觉模型 OCR 识别文字（P0-4）
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfParser implements DocumentParser {

    /** 文本少于该长度判定为扫描件（图片型 PDF），触发 OCR；parse.ocrMinText 可调 */
    private int ocrMinText() { return configService.getInt("parse.ocrMinText", 20); }
    /** OCR 专用提示词：原样输出文字，不做描述/评论 */
    private static final String OCR_PROMPT = "请识别图片中的全部文字内容，按原文原样输出。不要描述界面、不要评论、不要输出多余内容。如果图片中几乎没有文字，返回空。";

    private final AppProperties properties;
    private final VisionService visionService;
    private final ConfigService configService;

    @Override
    public boolean supports(String ext) {
        return "pdf".equalsIgnoreCase(ext);
    }

    @Override
    public java.util.Set<String> supportedExts() {
        return java.util.Set.of("pdf");
    }

    @Override
    public List<Chunk> parse(java.nio.file.Path file, String fileName, String docId) throws Exception {
        int maxSize = properties.getChunk().getMaxSize();
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder pageBuffer = new StringBuilder();
        String pageTitle = "第 1 页";

        // File 模式随机访问加载：内存从 O(文件大小) 降为准 O(页数)，大 PDF 不再全量驻留堆
        try (PDDocument doc = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int total = doc.getNumberOfPages();
            for (int page = 1; page <= total; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc).trim();
                if (text.isEmpty()) continue;

                // 按页累积，超长切分（保证 chunk 粒度与 docx 一致）
                if (pageBuffer.length() + text.length() > maxSize && pageBuffer.length() > 0) {
                    chunks.add(new Chunk(pageTitle, pageBuffer.toString().trim(), List.of()));
                    pageBuffer.setLength(0);
                }
                if (pageBuffer.length() > 0) pageBuffer.append("\n");
                pageBuffer.append(text);
                pageTitle = "第 " + page + " 页";
            }

            // 扫描件/图片型 PDF：文本极少 → OCR 降级
            if (pageBuffer.length() < ocrMinText()) {
                log.info("[PDF] {} 文本极少({}字符)，判定为扫描件，走 OCR（每页本地视觉模型识别）", fileName, pageBuffer.length());
                chunks = ocrParse(doc, maxSize);
            } else if (pageBuffer.length() > 0) {
                chunks.add(new Chunk(pageTitle, pageBuffer.toString().trim(), List.of()));
            }
        }
        log.info("[PDF] {} 解析出 {} 个分块", fileName, chunks.size());
        return chunks;
    }

    /** OCR 降级：逐页渲染 → 视觉模型识别文字 → 按页累积切分 */
    private List<Chunk> ocrParse(PDDocument doc, int maxSize) throws Exception {
        List<Chunk> chunks = new ArrayList<>();
        PDFRenderer renderer = new PDFRenderer(doc);
        int total = doc.getNumberOfPages();
        StringBuilder buf = new StringBuilder();
        String title = "第 1 页";
        for (int page = 0; page < total; page++) {
            String text = ocrPage(renderer, page);
            if (text.isBlank()) continue;
            if (buf.length() + text.length() > maxSize && buf.length() > 0) {
                chunks.add(new Chunk(title, buf.toString().trim(), List.of()));
                buf.setLength(0);
            }
            if (buf.length() > 0) buf.append("\n");
            buf.append(text);
            title = "第 " + (page + 1) + " 页";
        }
        if (buf.length() > 0) {
            chunks.add(new Chunk(title, buf.toString().trim(), List.of()));
        }
        return chunks;
    }

    /** PDF 页 OCR 渲染 DPI（parse.ocrDpi 可配，默认 200）：提高小字识别清晰度（内存/耗时小幅增加） */
    private int ocrDpi() { return configService.getInt("parse.ocrDpi", 200); }

    private String ocrPage(PDFRenderer renderer, int page) {
        try {
            BufferedImage img = renderer.renderImageWithDPI(page, ocrDpi());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            String text = visionService.describe(bos.toByteArray(), "png", OCR_PROMPT);
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            log.warn("[PDF] OCR 第 {} 页失败: {}", page + 1, e.getMessage());
            return "";
        }
    }
}
