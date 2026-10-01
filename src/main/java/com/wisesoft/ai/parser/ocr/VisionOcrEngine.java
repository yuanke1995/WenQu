package com.wisesoft.ai.parser.ocr;

import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.VisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 视觉模型 OCR 引擎（parse.ocrEngine = vision）：整份 PDF 逐页渲染 → 多模态 LLM 识别。
 * <p>
 * 与 none 路径的「扫描件兜底」共用同一识别链路（{@link VisionService#describeOcr}，strict 通道），
 * 区别在触发语义：vision 引擎<b>无视文本层</b>，适合压根不想用文本层（版面乱、双栏严重）
 * 或文本层与扫描内容不一致的 PDF。单页调用失败抛异常 → 整份解析失败（不丢页，不降级）。
 * <p>
 * 健康检查恒为可用：视觉模型绑定是知识库维度（解析期 visionRef），在 PdfParser 的
 * 调用点按既有 fail-loud 文案校验，这里无从判断。
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionOcrEngine implements OcrEngine {

    /** OCR 专用提示词：原样输出文字，不做描述/评论 */
    static final String OCR_PROMPT = "请识别图片中的全部文字内容，按原文原样输出。不要描述界面、不要评论、不要输出多余内容。如果图片中几乎没有文字，返回空。";

    private final VisionService visionService;
    private final ConfigService configService;

    @Override
    public String id() {
        return "vision";
    }

    @Override
    public String checkHealth() {
        // 没有固定服务地址可探：视觉模型按库绑定，可用性在解析调用点校验（fail-loud 文案见 PdfParser）
        return null;
    }

    @Override
    public List<PageMarkdown> parse(Path pdf, String fileName) throws Exception {
        return parse(pdf, fileName, null);
    }

    @Override
    public List<PageMarkdown> parse(Path pdf, String fileName,
                                    com.wisesoft.ai.parser.DocumentParser.ParseProgress progress) throws Exception {
        com.wisesoft.ai.parser.DocumentParser.ParseProgress cb =
                progress != null ? progress : (p, d) -> { };
        int dpi = configService.getInt("parse.ocrDpi", 200);
        List<PageMarkdown> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int total = doc.getNumberOfPages();
            log.info("[vision-OCR] {} 共 {} 页，渲染 DPI={}，逐页视觉模型识别", fileName, total, dpi);
            for (int page = 0; page < total; page++) {
                // 逐页上报（区间 10→28，30 是 DocumentService 的「分块完成」刻度）：每页一次 LLM 调用，真实进度
                cb.onProgress(10 + (int) (18.0 * page / Math.max(1, total)),
                        "视觉 OCR 第 " + (page + 1) + "/" + total + " 页");
                BufferedImage img = renderer.renderImageWithDPI(page, dpi);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", bos);
                // describeOcr strict：调用失败抛异常（→ 整个解析失败）；返回空串 = 模型确认本页无文字（空白页，合法跳过）
                String text = visionService.describeOcr(bos.toByteArray(), "png", OCR_PROMPT);
                text = text == null ? "" : text.trim();
                if (!text.isBlank()) pages.add(new PageMarkdown(page + 1, text));
            }
        }
        return pages;
    }
}
