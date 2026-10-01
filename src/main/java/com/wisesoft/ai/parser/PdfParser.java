package com.wisesoft.ai.parser;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.model.Chunk;
import com.wisesoft.ai.parser.ocr.OcrEngine;
import com.wisesoft.ai.parser.ocr.PageMarkdown;
import com.wisesoft.ai.service.ConfigService;
import com.wisesoft.ai.service.VisionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PDF 解析器（PDFBox 文本抽取 + 可插拔深度解析引擎）
 * <p>
 * 按 {@code parse.ocrEngine} 路由：
 * <ul>
 *   <li><b>none（默认）</b>：PDFTextStripper 纯文本抽取（按页合并 + 超长切分）；
 *       全文少于 {@code parse.ocrMinText} 判定扫描件（图片型 PDF）→ 逐页渲染 → 视觉模型 OCR（P0-4）。</li>
 *   <li><b>vision</b>：无视文本层，整份逐页渲染 → 视觉模型 OCR（{@link com.wisesoft.ai.parser.ocr.VisionOcrEngine}）。</li>
 *   <li><b>pp_structure_v3 / mineru</b>：整份 PDF 发外部版面解析服务，换回带标题层级与表格结构的
 *       markdown 版面块（替代错位行序文本，缩小与 RAGFlow DeepDoc 的差距）。</li>
 * </ul>
 * 引擎路径统一 <b>fail-loud 不降级</b>：引擎未知 / 服务不可用 / 视觉调用失败 / 结果为空，
 * 都直接解析失败并给出原因——扫描件丢失 OCR 结果或版面"悄悄回落"等于内容静默残缺，比解析失败更糟。
 *
 * @author yuanke
 */
@Slf4j
@Component
public class PdfParser implements DocumentParser {

    /** 文本少于该长度判定为扫描件（图片型 PDF），触发 OCR；parse.ocrMinText 可调 */
    private int ocrMinText() { return configService.getInt("parse.ocrMinText", 20); }

    private final AppProperties properties;
    private final VisionService visionService;
    private final ConfigService configService;
    private final Map<String, OcrEngine> engines;

    public PdfParser(AppProperties properties, VisionService visionService,
                     ConfigService configService, List<OcrEngine> engineList) {
        this.properties = properties;
        this.visionService = visionService;
        this.configService = configService;
        this.engines = engineList.stream().collect(Collectors.toMap(OcrEngine::id, Function.identity()));
    }

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
        return parse(file, fileName, docId, null);
    }

    @Override
    public List<Chunk> parse(java.nio.file.Path file, String fileName, String docId,
                             DocumentParser.ParseProgress progress) throws Exception {
        DocumentParser.ParseProgress cb = progress != null ? progress : (p, d) -> { };
        int maxSize = configService.getInt("chunk.maxSize", properties.getChunk().getMaxSize());
        String engineId = configService.get("parse.ocrEngine");
        if (engineId == null || engineId.isBlank()) engineId = "none";

        List<Chunk> chunks;
        if ("none".equals(engineId)) {
            chunks = textLayerParse(file, fileName, maxSize, cb);
        } else if ("vision".equals(engineId)) {
            // 整份逐页视觉 OCR：文本层不参与（语义见 VisionOcrEngine）
            if (!visionService.parseVisionAvailable()) {
                throw new BizException("「" + fileName + "」指定 vision 引擎整份 OCR，"
                        + "但所属知识库未绑定图片描述模型——请在知识库编辑的「解析参数 → 图片描述模型」中选择视觉模型后重新解析");
            }
            log.info("[PDF] {} 走 vision 引擎：整份逐页视觉 OCR（不使用文本层）", fileName);
            chunks = chunksFromPages(engines.get("vision").parse(file, fileName, cb), maxSize);
        } else {
            OcrEngine engine = engine(engineId);
            String unhealthy = engine.checkHealth();
            if (unhealthy != null) {
                throw new BizException("「" + fileName + "」版面解析失败：" + unhealthy
                        + "；也可在设置页「文档解析默认模板 → PDF 解析引擎」换回 none");
            }
            log.info("[PDF] {} 走 {} 版面解析引擎", fileName, engineId);
            chunks = chunksFromPages(engine.parse(file, fileName, cb), maxSize);
        }

        if (chunks.isEmpty()) {
            throw new BizException("「" + fileName + "」解析后未得到任何内容（PDF 可能为空，或引擎未识别出文字）");
        }
        log.info("[PDF] {} 解析出 {} 个分块（引擎 {}）", fileName, chunks.size(), engineId);
        return chunks;
    }

    /** none 路径：文本层抽取 + 扫描件视觉兜底（与历史行为完全一致） */
    private List<Chunk> textLayerParse(java.nio.file.Path file, String fileName, int maxSize,
                                       DocumentParser.ParseProgress cb) throws Exception {
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
                    throw new BizException("「" + fileName + "」是扫描件/图片型 PDF，需要 OCR，"
                            + "但所属知识库未绑定图片描述模型——请在知识库编辑的「解析参数 → 图片描述模型」中选择视觉模型后重新解析");
                }
                chunks = chunksFromPages(engines.get("vision").parse(file, fileName, cb), maxSize);
                if (chunks.isEmpty()) {
                    throw new BizException("「" + fileName + "」OCR 后未识别出任何文字"
                            + "（视觉模型可能不可用或返回空），请检查知识库绑定的图片描述模型后重新解析");
                }
            } else if (pageBuffer.length() > 0) {
                chunks.add(new Chunk(pageTitle, pageBuffer.toString().trim(), List.of()));
            }
        }
        return chunks;
    }

    /** 按引擎 id 取引擎；未知配置值 fail-loud（设置页只会下发合法值，防手改库脏数据） */
    private OcrEngine engine(String id) {
        OcrEngine engine = engines.get(id);
        if (engine == null) {
            throw new BizException("未知的 PDF 解析引擎: " + id + "（可选 none / vision / pp_structure_v3 / mineru）");
        }
        return engine;
    }

    /**
     * 引擎输出的页 markdown → 分块（按 maxSize 累积切分）。
     * 标题沿用「第 N 页」页界语义：块标题取切分时的起始页；
     * MinerU 整份 markdown 无页界（约定 page=1），切出的多块标题同为「第 1 页」，
     * 属已知简化——结构感知分块（按标题层级切）见方案 S2。
     */
    private List<Chunk> chunksFromPages(List<PageMarkdown> pages, int maxSize) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int startPage = 1;
        for (PageMarkdown pm : pages) {
            String text = pm.markdown() == null ? "" : pm.markdown().trim();
            if (text.isEmpty()) continue;
            if (buf.length() + text.length() > maxSize && buf.length() > 0) {
                chunks.add(new Chunk("第 " + startPage + " 页", buf.toString().trim(), List.of()));
                buf.setLength(0);
                startPage = pm.page();
            }
            if (buf.length() > 0) buf.append("\n\n");
            buf.append(text);
        }
        if (buf.length() > 0) {
            chunks.add(new Chunk("第 " + startPage + " 页", buf.toString().trim(), List.of()));
        }
        return chunks;
    }
}
