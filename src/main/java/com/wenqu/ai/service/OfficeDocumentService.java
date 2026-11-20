package com.wenqu.ai.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Office 文档处理内核：格式转换、公式重算、渲染预览。
 *
 * <p>所有方法都是「字节进、字节出」，不碰文件系统——文件读写、归属与交付由
 * {@link OfficeTools} 通过当前会话的工作区/沙盒文件系统完成。这样内核可在单测里
 * 用 POI 现造的文档直接验证，不依赖执行器或外部服务。
 *
 * <p>能力边界（如实标注，不假装无所不能）：
 * <ul>
 *   <li><b>纯 POI 路径</b>：docx/xlsx/pptx → txt / csv / html；xlsx 公式重算。无需外部依赖。</li>
 *   <li><b>需要 LibreOffice</b>：任意 Office 格式 → pdf，以及 docx/xlsx/pptx → png（先转 pdf 再按页栅格化）。
 *       找不到 {@code soffice} 时 fail-loud，提示安装 LibreOffice 或用 {@code WENQU_SOFFICE} 指定，
 *       绝不静默降级成「看起来成功但文件是空的」。</li>
 *   <li><b>PDF → txt / png</b>：PDFBox 直接处理，不依赖 LibreOffice。</li>
 * </ul>
 *
 * @author yuanke
 */
@Service
public class OfficeDocumentService {

    /** 单次处理的输入上限：再大就不是「在对话里看一份文档」，会拖垮堆与响应时间。 */
    public static final long MAX_INPUT_BYTES = 30L * 1024 * 1024;
    /** 渲染 PNG 的页数上限（防一次把一份 300 页 PPT 全栅格化）。 */
    public static final int MAX_RENDER_PAGES = 20;

    /** 可由纯 POI/PDFBox 直接转换出的目标格式。 */
    public static final List<String> PURE_TARGETS = List.of("txt", "csv", "html");

    public record RecalcResult(byte[] bytes, int formulaCells, int errors, List<String> failedCells) {
    }

    public record RenderResult(List<byte[]> pages, String format) {
    }

    /** 扩展名（小写、不含点）；识别不出返回空串。 */
    public static String extOf(String filename) {
        if (filename == null) return "";
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        int dot = filename.lastIndexOf('.');
        return dot <= slash ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 是否是可处理的 Office/PDF/文本格式。 */
    public static boolean supported(String ext) {
        return switch (ext) {
            case "docx", "xlsx", "pptx", "pdf", "txt", "md", "csv", "html", "htm" -> true;
            default -> false;
        };
    }

    // ==================== 转换 ====================

    /**
     * 把一份文档转成目标格式。
     *
     * @param targetFormat txt / csv / html / pdf（pdf 需要 LibreOffice，pdf 输入转 pdf 直接返回）
     */
    public byte[] convert(byte[] input, String srcExt, String targetFormat) throws IOException {
        requireInput(input);
        String target = targetFormat == null ? "" : targetFormat.trim().toLowerCase(Locale.ROOT);
        String src = srcExt == null ? "" : srcExt.toLowerCase(Locale.ROOT);
        if (!supported(src)) throw new IllegalArgumentException("不支持的源格式：" + src);
        if (target.isEmpty()) throw new IllegalArgumentException("缺少目标格式（txt/csv/html/pdf）");
        if (target.equals(src)) return input;

        if ("pdf".equals(target)) {
            if ("pdf".equals(src)) return input;
            return toPdf(input, src);
        }
        if ("png".equals(target)) {
            throw new IllegalArgumentException("png 请用 officeRender（可指定页码与清晰度）");
        }
        return switch (src) {
            case "docx" -> switch (target) {
                case "txt" -> docxToText(input).getBytes(StandardCharsets.UTF_8);
                case "html" -> docxToHtml(input).getBytes(StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("docx 不能直接转 " + target + "（可用 txt/html/pdf）");
            };
            case "xlsx" -> switch (target) {
                case "txt" -> xlsxToText(input).getBytes(StandardCharsets.UTF_8);
                case "csv" -> xlsxToCsv(input).getBytes(StandardCharsets.UTF_8);
                case "html" -> xlsxToHtml(input).getBytes(StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("xlsx 不能直接转 " + target + "（可用 txt/csv/html/pdf）");
            };
            case "pptx" -> switch (target) {
                case "txt" -> pptxToText(input).getBytes(StandardCharsets.UTF_8);
                case "html" -> pptxToHtml(input).getBytes(StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("pptx 不能直接转 " + target + "（可用 txt/html/pdf）");
            };
            case "pdf" -> switch (target) {
                case "txt" -> pdfToText(input).getBytes(StandardCharsets.UTF_8);
                default -> throw new IllegalArgumentException("pdf 不能直接转 " + target + "（可用 txt/png）");
            };
            case "txt", "md", "csv", "html", "htm" -> {
                String text = new String(input, StandardCharsets.UTF_8);
                yield "html".equals(target) || "htm".equals(target)
                        ? textToHtml(text).getBytes(StandardCharsets.UTF_8)
                        : text.getBytes(StandardCharsets.UTF_8);
            }
            default -> throw new IllegalArgumentException("不支持的转换：" + src + " → " + target);
        };
    }

    private static void requireInput(byte[] input) {
        if (input == null || input.length == 0) throw new IllegalArgumentException("文件内容为空");
        if (input.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("文件过大（" + (input.length / 1024 / 1024) + "MB），上限 "
                    + (MAX_INPUT_BYTES / 1024 / 1024) + "MB");
        }
    }

    // -------- docx --------

    String docxToText(byte[] input) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(input))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) sb.append(t).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell c : row.getTableCells()) cells.add(c.getText());
                    sb.append(String.join("\t", cells)).append('\n');
                }
            }
            return sb.toString();
        }
    }

    String docxToHtml(byte[] input) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(input))) {
            StringBuilder sb = new StringBuilder("<article>");
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t == null || t.isBlank()) continue;
                sb.append("<p>").append(escapeHtml(t)).append("</p>");
            }
            for (XWPFTable table : doc.getTables()) {
                sb.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">");
                for (XWPFTableRow row : table.getRows()) {
                    sb.append("<tr>");
                    for (XWPFTableCell c : row.getTableCells()) {
                        sb.append("<td>").append(escapeHtml(c.getText())).append("</td>");
                    }
                    sb.append("</tr>");
                }
                sb.append("</table>");
            }
            sb.append("</article>");
            return page(sb.toString());
        }
    }

    // -------- xlsx --------

    String xlsxToText(byte[] input) throws IOException {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(input))) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                sb.append("### ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) cells.add(fmt.formatCellValue(cell));
                    sb.append(String.join("\t", cells)).append('\n');
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    String xlsxToCsv(byte[] input) throws IOException {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(input))) {
            Sheet sheet = wb.getSheetAt(0);
            StringBuilder sb = new StringBuilder();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (Cell cell : row) cells.add(csvCell(fmt.formatCellValue(cell)));
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                    cells.remove(cells.size() - 1);
                }
                if (cells.isEmpty()) continue;
                sb.append(String.join(",", cells)).append('\n');
            }
            return sb.toString();
        }
    }

    String xlsxToHtml(byte[] input) throws IOException {
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(input))) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                sb.append("<h3>").append(escapeHtml(sheet.getSheetName())).append("</h3>");
                sb.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">");
                for (Row row : sheet) {
                    sb.append("<tr>");
                    for (Cell cell : row) {
                        sb.append("<td>").append(escapeHtml(fmt.formatCellValue(cell))).append("</td>");
                    }
                    sb.append("</tr>");
                }
                sb.append("</table>");
            }
            return page(sb.toString());
        }
    }

    private static String csvCell(String v) {
        String s = v == null ? "" : v;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // -------- pptx --------

    String pptxToText(byte[] input) throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(input))) {
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                sb.append("### 第 ").append(idx++).append(" 页\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape t) {
                        String text = t.getText();
                        if (text != null && !text.isBlank()) sb.append(text).append('\n');
                    }
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    String pptxToHtml(byte[] input) throws IOException {
        try (XMLSlideShow slideShow = new XMLSlideShow(new ByteArrayInputStream(input))) {
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (XSLFSlide slide : slideShow.getSlides()) {
                sb.append("<section><h3>第 ").append(idx++).append(" 页</h3>");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape t) {
                        String text = t.getText();
                        if (text == null || text.isBlank()) continue;
                        for (String line : text.split("\\r?\\n")) {
                            sb.append("<p>").append(escapeHtml(line)).append("</p>");
                        }
                    }
                }
                sb.append("</section>");
            }
            return page(sb.toString());
        }
    }

    // -------- pdf --------

    String pdfToText(byte[] input) throws IOException {
        try (PDDocument doc = Loader.loadPDF(input)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    // ==================== 公式重算 ====================

    /**
     * 重算 xlsx 全部公式并回写缓存值。返回新的 xlsx 字节与统计。
     * <p>POI 的求值器覆盖常用函数；个别函数（如部分财务/外部引用）求不出时**不吞掉**：
     * 计入 {@code failedCells} 原样返回，让模型/用户知道「这几格没算出来」，而不是显示旧值当成功。
     */
    public RecalcResult recalculateXlsx(byte[] input) throws IOException {
        requireInput(input);
        List<String> failed = new ArrayList<>();
        int formulaCells = 0;
        int errors = 0;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(input))) {
            FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() != CellType.FORMULA) continue;
                        formulaCells++;
                        String ref = sheet.getSheetName() + "!" + cell.getAddress();
                        try {
                            CellValue v = evaluator.evaluate(cell);
                            if (v != null && v.getCellType() == CellType.ERROR) {
                                errors++;
                                failed.add(ref + " → " + v.getErrorValue());
                            }
                        } catch (RuntimeException ex) {
                            errors++;
                            failed.add(ref + " → " + ex.getClass().getSimpleName()
                                    + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
                        }
                    }
                }
            }
            // 让 Excel/WPS 打开时也强制重算一次（缓存值只是本次结果）
            wb.setForceFormulaRecalculation(true);
            try {
                evaluator.evaluateAll();
            } catch (RuntimeException ignore) {
                // 个别不受支持的函数已在逐格统计里计入 failedCells；evaluateAll 再抛不能把整份文件判失败
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new RecalcResult(out.toByteArray(), formulaCells, errors, failed);
        }
    }

    // ==================== 渲染 ====================

    /**
     * 渲染成 PDF 或 PNG（每页一张）。
     *
     * @param format pdf / png
     * @param pages  要渲染的页码（1-based）；null/空 = 第 1 页（pdf 输出时为全部）
     * @param dpi    PNG 清晰度，默认 144，限制 72~300
     */
    public RenderResult render(byte[] input, String srcExt, String format, List<Integer> pages, Integer dpi)
            throws IOException {
        requireInput(input);
        String fmt = format == null ? "png" : format.trim().toLowerCase(Locale.ROOT);
        String src = srcExt == null ? "" : srcExt.toLowerCase(Locale.ROOT);
        byte[] pdf;
        if ("pdf".equals(src)) pdf = input;
        else if ("txt".equals(src) || "md".equals(src) || "csv".equals(src) || "html".equals(src) || "htm".equals(src)) {
            throw new IllegalArgumentException("文本类文件没有版面可渲染（可直接读取内容）");
        } else {
            pdf = toPdf(input, src);
        }
        if ("pdf".equals(fmt)) {
            return new RenderResult(List.of(pdf), "pdf");
        }
        if (!"png".equals(fmt)) throw new IllegalArgumentException("渲染格式只支持 pdf / png");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int total = doc.getNumberOfPages();
            List<Integer> wanted = (pages == null || pages.isEmpty())
                    ? List.of(1) : pages.stream().filter(p -> p != null && p >= 1 && p <= total)
                    .distinct().limit(MAX_RENDER_PAGES).toList();
            if (wanted.isEmpty()) throw new IllegalArgumentException("页码超出范围（共 " + total + " 页）");
            float d = dpi == null ? 144f : Math.max(72f, Math.min(300f, dpi));
            List<byte[]> out = new ArrayList<>();
            for (int p : wanted) {
                BufferedImage img = renderer.renderImageWithDPI(p - 1, d);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", bos);
                out.add(bos.toByteArray());
            }
            return new RenderResult(out, "png");
        }
    }

    // ==================== LibreOffice ====================

    /** 任意受支持格式 → pdf，依赖 LibreOffice（soffice）。 */
    private byte[] toPdf(byte[] input, String srcExt) throws IOException {
        String soffice = findSoffice();
        if (soffice == null) {
            throw new IllegalStateException("未找到 LibreOffice（soffice）：转 PDF / 渲染 PNG 需要它。"
                    + "请安装 LibreOffice（Docker 镜像默认已装），或用环境变量 WENQU_SOFFICE 指向 soffice 可执行文件。");
        }
        Path dir = Files.createTempDirectory("wenqu-office-");
        try {
            Path in = dir.resolve("input." + srcExt);
            Files.write(in, input);
            Path profile = dir.resolve("lo-profile");
            List<String> cmd = new ArrayList<>(List.of(soffice, "--headless", "--norestore", "--nolockcheck",
                    "-env:UserInstallation=" + profile.toUri(),
                    "--convert-to", "pdf", "--outdir", dir.toString(), in.toString()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log;
            try (InputStream is = p.getInputStream()) {
                log = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("LibreOffice 转换超时（120s）");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("LibreOffice 转换失败（exit=" + p.exitValue() + "）：" + tail(log));
            }
            Path pdf = dir.resolve("input.pdf");
            if (!Files.exists(pdf) || Files.size(pdf) == 0) {
                throw new IllegalStateException("LibreOffice 未产出 PDF：" + tail(log));
            }
            return Files.readAllBytes(pdf);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LibreOffice 转换被中断");
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * 找到 soffice：部署级 {@code WENQU_SOFFICE}（或 JVM 属性 {@code office.sofficePath}）优先，
     * 其次常见安装位置与 PATH。刻意不放进系统设置页——这是「这台服务器上装在哪」的部署事实，
     * 不是每次对话要调的行为参数，多一个设置项就多一处要维护的面。
     */
    public String findSoffice() {
        String env = System.getenv("WENQU_SOFFICE");
        if (env != null && !env.isBlank() && new File(env).canExecute()) return env;
        String sys = System.getProperty("office.sofficePath");
        if (sys != null && !sys.isBlank() && new File(sys).canExecute()) return sys;
        List<String> candidates = List.of(
                "/Applications/LibreOffice.app/Contents/MacOS/soffice",
                "/usr/bin/soffice",
                "/usr/local/bin/soffice",
                "/opt/homebrew/bin/soffice",
                "C:\\Program Files\\LibreOffice\\program\\soffice.exe");
        for (String c : candidates) {
            if (new File(c).canExecute()) return c;
        }
        // PATH 兜底
        for (String dir : String.valueOf(System.getenv("PATH")).split(File.pathSeparator)) {
            File f = new File(dir, "soffice");
            if (f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static void deleteQuietly(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) return;
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        } catch (IOException ignored) {
            // 临时目录清理失败不影响业务结果
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= 500 ? t : t.substring(t.length() - 500);
    }

    // ==================== 小工具 ====================

    private static String page(String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>" + body + "</body></html>";
    }

    private static String textToHtml(String text) {
        return page("<pre>" + escapeHtml(text) + "</pre>");
    }

    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** 供工具层复用的转换摘要（不改变行为）。 */
    public static Map<String, Object> describe(RenderResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", r.format());
        m.put("pages", r.pages().size());
        m.put("bytes", r.pages().stream().mapToInt(b -> b.length).sum());
        return m;
    }
}
