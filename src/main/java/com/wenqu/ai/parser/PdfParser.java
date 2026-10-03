package com.wenqu.ai.parser;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.model.Chunk;
import com.wenqu.ai.parser.ocr.OcrEngine;
import com.wenqu.ai.parser.ocr.PageMarkdown;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.VisionService;
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
 *   <li><b>vision</b>：无视文本层，整份逐页渲染 → 视觉模型 OCR（{@link com.wenqu.ai.parser.ocr.VisionOcrEngine}）。</li>
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
    private final com.wenqu.ai.parser.ocr.OcrEngineGate gate;

    public PdfParser(AppProperties properties, VisionService visionService,
                     ConfigService configService, List<OcrEngine> engineList,
                     com.wenqu.ai.parser.ocr.OcrEngineGate gate) {
        this.properties = properties;
        this.visionService = visionService;
        this.configService = configService;
        this.engines = engineList.stream().collect(Collectors.toMap(OcrEngine::id, Function.identity()));
        this.gate = gate;
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
            // 并发闸：版面服务是 CPU 自托管单实例，批量解析并发打满后健康检查都会超时——
            // 排队不 fail（进度回调每 5s 反馈已等秒数），持锁后再健康检查（此时服务必然空闲）
            gate.acquire(engineId, displayName(engineId), fileName, cb);
            try {
                String unhealthy = engine.checkHealth();
                if (unhealthy != null) {
                    throw new BizException("「" + fileName + "」版面解析失败：" + unhealthy
                            + "；也可在设置页「文档解析默认模板 → PDF 解析引擎」换回 none");
                }
                log.info("[PDF] {} 走 {} 版面解析引擎", fileName, engineId);
                chunks = chunksFromPages(engine.parse(file, fileName, cb), maxSize);
            } finally {
                gate.release(engineId);
            }
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

                // 按页累积，超长切分（保证 chunk 粒度与 docx 一致）；单页本身超限的稠密页
                // 按段落→句→字符硬切（兜底 embedding token 上限，与版面路径同一套边界语义）
                for (String piece : text.length() > maxSize
                        ? DocxParser.splitByBoundaries(text, maxSize) : List.of(text)) {
                    if (pageBuffer.length() + piece.length() > maxSize && pageBuffer.length() > 0) {
                        chunks.add(new Chunk(pageTitle, pageBuffer.toString().trim(), List.of()));
                        pageBuffer.setLength(0);
                    }
                    if (pageBuffer.length() > 0) pageBuffer.append("\n");
                    pageBuffer.append(piece);
                    pageTitle = "第 " + page + " 页";
                }
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

    /** ATX 标题行（# ~ ######），行尾可带闭合 #（ATX 规范允许） */
    private static final java.util.regex.Pattern ATX_HEADING =
            java.util.regex.Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");

    /** 版面节：页号 + 最近标题（无则 null，块标题回退「第 N 页」）+ 章节路径（栈深≥2 时非空）+ 节体 */
    private record MdSection(int page, String title, String titlePath, String body) {}

    /** 引擎展示名（排队进度文案/日志用） */
    private static String displayName(String engineId) {
        return switch (engineId) {
            case "mineru" -> "MinerU";
            case "pp_structure_v3" -> "PP-StructureV3";
            case "vision" -> "视觉模型";
            default -> engineId;
        };
    }

    /**
     * 引擎输出的页 markdown → 分块（结构感知，方案 S2）。
     * <p>
     * ① 页内按 ATX 标题（#~######）切节，标题栈按级别维护（对齐 docx/WebParser 语义：title=最近标题，
     * titlePath=栈深≥2 时「 &gt; 」连接；MinerU 实测标题全为 h1 平铺，路径退化为空属预期，标题本身仍入 embedding）；
     * 标题行不重复进正文——content 只承载净正文，路径/标题由 embedding 组装时拼接。
     * ② 节内按空行分段；Markdown 表格行连续天然同段不跨块。
     * ③ 顺序贪心累积到 maxSize 成块；单独超限的段：表格走 splitTableRows（每段重复表头）、
     * 其余走 splitByBoundaries（段落→句→字符硬切）——兜底 embedding token 上限。
     * <p>
     * 缺陷背景：MinerU 整份 markdown 是单一「页」（约定 page=1），旧实现只在页间累积切分、
     * 单页超限从不切，22047 字符整块入库直接撑爆向量化（Tokens exceeds maximum allowed）。
     * 包内可见供冒烟验证。
     */
    List<Chunk> chunksFromPages(List<PageMarkdown> pages, int maxSize) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String bufTitle = null;
        String bufPath = null;

        for (PageMarkdown pm : pages) {
            String md = pm.markdown() == null ? "" : pm.markdown().trim();
            if (md.isEmpty()) continue;
            for (MdSection sec : splitSections(md, pm.page())) {
                for (String block : blocksOf(sec.body())) {
                    for (String piece : fitBlock(block, maxSize)) {
                        int sep = buf.length() > 0 ? 2 : 0;   // 块间 "\n\n"
                        if (buf.length() + sep + piece.length() > maxSize && buf.length() > 0) {
                            chunks.add(new Chunk(bufTitle, buf.toString().trim(), List.of(), bufPath));
                            buf.setLength(0);
                        }
                        if (buf.length() == 0) {
                            bufTitle = sec.title() != null ? sec.title() : "第 " + sec.page() + " 页";
                            bufPath = sec.titlePath();
                        }
                        if (buf.length() > 0) buf.append("\n\n");
                        buf.append(piece);
                    }
                }
            }
        }
        if (buf.length() > 0) {
            chunks.add(new Chunk(bufTitle, buf.toString().trim(), List.of(), bufPath));
        }
        return chunks;
    }

    /** 页内按 ATX 标题切节：标题行进标题栈（含自身）但不进正文；纯标题无正文的节不产出（标题经栈传递给后续节） */
    private List<MdSection> splitSections(String md, int page) {
        List<MdSection> out = new ArrayList<>();
        String[] stack = new String[6];
        StringBuilder body = new StringBuilder();
        String title = null, path = null;
        for (String line : md.split("\n", -1)) {
            java.util.regex.Matcher m = ATX_HEADING.matcher(line.trim());
            if (m.matches()) {
                if (body.length() > 0) {
                    out.add(new MdSection(page, title, path, body.toString().trim()));
                    body.setLength(0);
                }
                int depth = m.group(1).length();
                stack[depth - 1] = m.group(2).trim();
                for (int i = depth; i < 6; i++) stack[i] = null;
                List<String> levels = new ArrayList<>();
                for (String s : stack) if (s != null) levels.add(s);
                title = levels.get(levels.size() - 1);
                path = levels.size() >= 2 ? String.join(" > ", levels) : null;
            } else {
                body.append(line).append('\n');
            }
        }
        if (body.length() > 0) out.add(new MdSection(page, title, path, body.toString().trim()));
        return out;
    }

    /** 节体按空行分段（表格行/列表行连续不跨段，保持整块） */
    private static List<String> blocksOf(String body) {
        List<String> out = new ArrayList<>();
        for (String p : body.split("\\n\\s*\\n")) {
            String t = p.strip();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 单段超限的两种形态：Markdown 管道表格走 splitTableRows（每段重复表头+分隔行）；
     * 其余（含 PP-StructureV3 的 HTML 表格，超限时按段落→句→字符硬切，表格完整性让位于 token 上限）
     * 走 splitByBoundaries。均复用 DocxParser 同一套边界语义。
     */
    private static List<String> fitBlock(String block, int maxSize) {
        if (block.length() <= maxSize) return List.of(block);
        boolean table = block.lines().allMatch(l -> {
            String t = l.trim();
            return t.isEmpty() || t.startsWith("|");
        });
        return table ? DocxParser.splitTableRows(block, maxSize)
                     : DocxParser.splitByBoundaries(block, maxSize);
    }
}
