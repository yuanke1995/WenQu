package com.wisesoft.ai.parser;

import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.model.Chunk;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.VisionService;
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
 * PDF 解析器（PDFBox 文本抽取 + 扫描件 OCR）
 * 纯文本抽取（按页合并 + 超长切分）；若整份文本极少（扫描件/图片型 PDF），
 * 逐页渲染图片 → 视觉模型 OCR 识别文字（P0-4）。
 * <p>
 * OCR 是 <b>fail-loud 不降级</b>：所属知识库未绑定图片描述模型 / 视觉调用失败 / 全文识别为空，
 * 都直接解析失败并给出原因——扫描件丢失 OCR 结果等于内容静默残缺，比解析失败更糟。
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

            // 扫描件/图片型 PDF：文本极少 → OCR（fail-loud：未绑定视觉模型先置失败，不白渲染不静默跳过）
            if (pageBuffer.length() < ocrMinText()) {
                log.info("[PDF] {} 文本极少({}字符)，判定为扫描件，走 OCR（每页视觉模型识别）", fileName, pageBuffer.length());
                if (!visionService.parseVisionAvailable()) {
                    throw new com.wisesoft.ai.common.BizException("「" + fileName + "」是扫描件/图片型 PDF，需要 OCR，"
                            + "但所属知识库未绑定图片描述模型——请在知识库编辑的「解析参数 → 图片描述模型」中选择视觉模型后重新解析");
                }
                chunks = ocrParse(doc, maxSize);
                if (chunks.isEmpty()) {
                    throw new com.wisesoft.ai.common.BizException("「" + fileName + "」OCR 后未识别出任何文字"
                            + "（视觉模型可能不可用或返回空），请检查知识库绑定的图片描述模型后重新解析");
                }
            } else if (pageBuffer.length() > 0) {
                chunks.add(new Chunk(pageTitle, pageBuffer.toString().trim(), List.of()));
            }
        }
        log.info("[PDF] {} 解析出 {} 个分块", fileName, chunks.size());
        return chunks;
    }

    /** OCR：逐页渲染 → 视觉模型识别文字 → 按页累积切分；单页调用失败直接抛出（不丢页） */
    private List<Chunk> ocrParse(PDDocument doc, int maxSize) throws Exception {
        List<Chunk> chunks = new ArrayList<>();
        PDFRenderer renderer = new PDFRenderer(doc);
        int total = doc.getNumberOfPages();
        StringBuilder buf = new StringBuilder();
        String title = "第 1 页";
        for (int page = 0; page < total; page++) {
            // describeOcr strict：调用失败抛异常（→ 整个解析失败）；返回空串 = 模型确认本页无文字（空白页，合法跳过）
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

    private String ocrPage(PDFRenderer renderer, int page) throws Exception {
        BufferedImage img = renderer.renderImageWithDPI(page, ocrDpi());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        String text = visionService.describeOcr(bos.toByteArray(), "png", OCR_PROMPT);
        return text == null ? "" : text.trim();
    }
}
