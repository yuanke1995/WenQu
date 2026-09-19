package com.wisesoft.wenqu.knowledge;

import com.wisesoft.wenqu.knowledge.parser.DocumentProcessorException;
import com.wisesoft.wenqu.knowledge.parser.DocumentProcessorFactory;
import com.wisesoft.wenqu.storage.MinioStorageClient;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Unified parser module for markdown conversion（knowledge/parser/unified.py 全量移植）。
 *
 * <p>能力差异（显式标注，非静默省略）：
 * <ul>
 *   <li>Python docling（Office 结构化文档模型 + 图片 data-uri 提取上传）无 Java 等价库。
 *       DOCX 主路径改用参考实现「python-docx 兜底」的同一算法（POI XWPF 承载：
 *       段落 + 表格 → Markdown 管道表格）；docling 的版面级解析与内嵌图片上传能力缺失，
 *       PPTX/XLSX 以 POI 文本帧/单元格提取的简化转换替代（简化标注，输出形状接近但
 *       非逐字节等价）。
 *   <li>markdownify（HTML→MD）无 Java 等价库 → jsoup 遍历的子集实现
 *       （标题/段落/列表/链接/图片/加粗斜体/删除线），复杂嵌套表与注释块未覆盖（简化标注）。
 *   <li>pypdf PdfReader → PDFBox PDFTextStripper（extraction_mode="plain" 同义）。
 *   <li>pandas read_csv + df.to_markdown(index=False) → 自实现 CSV 解析 + 管道表格
 *       （参考实现把每一行输出为一张带表头的独立表格，该行为逐字保留；pandas 的
 *       列宽对齐/数字右对齐未复刻——本机无 pandas 无法对拍，见对拍报告）。
 * </ul>
 */
@Slf4j
public final class UnifiedParser {

    private static final Pattern IMAGE_PLACEHOLDER = Pattern.compile("<!--\\s*image\\s*-->");

    /** 对应参考实现 _docling_office_lock：Office 后端串行转换。 */
    private static final ReentrantLock OFFICE_LOCK = new ReentrantLock();

    private UnifiedParser() {}

    /** 读取 PDF 文件并返回 text 文本（对应 pdfreader）。 */
    public static String pdfReader(String filePath, Map<String, Object> params) {
        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found");
        }
        if (!".pdf".equals(com.wisesoft.wenqu.common.PosixPathLite.suffixOf(filePath).toLowerCase())) {
            throw new IllegalArgumentException("File format not supported");
        }

        try (PDDocumentHolder holder = PDDocumentHolder.load(path)) {
            List<String> pageTexts = new ArrayList<>();
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            for (int i = 0; i < holder.document().getNumberOfPages(); i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(holder.document()).strip();
                pageTexts.add(text);
            }
            return String.join("\n\n", pageTexts);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** PDFBox 文档句柄（try-with-resources 等价 python with）。 */
    private static final class PDDocumentHolder implements AutoCloseable {

        private final org.apache.pdfbox.pdmodel.PDDocument document;

        private PDDocumentHolder(org.apache.pdfbox.pdmodel.PDDocument document) {
            this.document = document;
        }

        org.apache.pdfbox.pdmodel.PDDocument document() {
            return document;
        }

        static PDDocumentHolder load(Path path) throws IOException {
            return new PDDocumentHolder(org.apache.pdfbox.Loader.loadPDF(path.toFile()));
        }

        @Override
        public void close() throws IOException {
            document.close();
        }
    }

    /** 解析 PDF 文件，支持多种 OCR 方式（对应 parse_pdf）。 */
    public static String parsePdf(String file, Map<String, Object> params) {
        ResolvedOcr resolved = resolveOcrEngineParams(params);

        if ("disable".equals(resolved.engine())) {
            return pdfReader(file, resolved.params());
        }

        ImageStorage storage = resolveImageStorageParams(resolved.params());
        resolved.params().putIfAbsent("image_bucket", storage.bucket());
        resolved.params().putIfAbsent("image_prefix", storage.prefix());
        Map<String, Object> processorKwargs = popProcessorKwargs(resolved.params());

        try {
            return DocumentProcessorFactory.processFile(resolved.engine(), file, resolved.params(), processorKwargs);
        } catch (DocumentProcessorException exc) {
            log.error("文档处理失败: {} - {}", exc.getServiceName(), exc.getMessage());
            throw exc;
        } catch (RuntimeException exc) {
            log.error("PDF 解析失败: {}", exc.getMessage());
            throw new DocumentProcessorException(
                    "PDF解析失败: " + exc.getMessage(), resolved.engine(), "parsing_failed");
        }
    }

    /** 解析图像文件，支持多种 OCR 方式（对应 parse_image）。 */
    public static String parseImage(String file, Map<String, Object> params) {
        ResolvedOcr resolved = resolveOcrEngineParams(params);

        if ("disable".equals(resolved.engine())) {
            throw new IllegalArgumentException(
                    "图像文件必须启用OCR才能提取文本内容。请选择OCR方式 ("
                            + String.join("/", ParserCapabilities.getOcrEngineIds())
                            + ") 或移除该文件。");
        }

        ImageStorage storage = resolveImageStorageParams(resolved.params());
        resolved.params().putIfAbsent("image_bucket", storage.bucket());
        resolved.params().putIfAbsent("image_prefix", storage.prefix());
        Map<String, Object> processorKwargs = popProcessorKwargs(resolved.params());

        try {
            return DocumentProcessorFactory.processFile(resolved.engine(), file, resolved.params(), processorKwargs);
        } catch (DocumentProcessorException exc) {
            log.error("图像处理失败: {} - {}", exc.getServiceName(), exc.getMessage());
            throw exc;
        } catch (RuntimeException exc) {
            log.error("图像解析失败: {}", exc.getMessage());
            throw new DocumentProcessorException(
                    "图像解析失败: " + exc.getMessage(), resolved.engine(), "parsing_failed");
        }
    }

    /** 使用已解析的运行时参数，将本地或 MinIO 文件转换为 Markdown（对应 parse_resolved_document）。 */
    public static String parseResolvedDocument(String source, Map<String, Object> params) {
        String actualFilePath = source;
        Path tempPath = null;

        // 1. 如果是 MinIO URL，下载文件到临时路径
        if (KbUtils.isMinioUrl(source)) {
            log.debug("Downloading file from MinIO: {}", source);

            String sourceClean = source.contains("?") ? source.split("\\?")[0] : source;
            String originalFilename = sourceClean.substring(sourceClean.lastIndexOf('/') + 1);

            try {
                String suffix = com.wisesoft.wenqu.common.PosixPathLite.suffixOf(originalFilename);
                tempPath = Files.createTempFile("wenqu-parse-", suffix);
                String[] bucketObject = KbUtils.parseMinioUrl(source);
                MinioStorageClient minioClient = MinioStorageClient.getInstance();
                byte[] fileContent = minioClient.downloadFile(bucketObject[0], bucketObject[1]);
                Files.write(tempPath, fileContent);
                log.debug("File downloaded to temp path: {}", tempPath);
                actualFilePath = tempPath.toString();
            } catch (Exception exc) {
                if (tempPath != null) {
                    try {
                        Files.deleteIfExists(tempPath);
                    } catch (IOException ignored) {
                        // 参考实现 except: pass
                    }
                }
                log.error("Failed to download file from MinIO: {}", exc.getMessage());
                throw new IllegalArgumentException("无法从MinIO下载文件: " + exc.getMessage());
            }
        }

        // 2. 根据文件类型调用不同的解析器
        String result;
        try {
            Path filePathObj = Path.of(actualFilePath);
            String fileExt = com.wisesoft.wenqu.common.PosixPathLite
                    .suffixOf(actualFilePath).toLowerCase();

            if (".pdf".equals(fileExt)) {
                PdfUtils.validatePdfPageTreeLoadable(filePathObj.toString());
                result = parsePdf(filePathObj.toString(), params);
            } else if (".txt".equals(fileExt) || ".md".equals(fileExt)) {
                result = Files.readString(filePathObj, StandardCharsets.UTF_8);
            } else if (".docx".equals(fileExt)) {
                // 参考实现 docling 主路径 + python-docx 兜底；Java 统一为兜底算法（见类注释能力差异）
                result = convertDocxWithPoi(filePathObj);
            } else if (".pptx".equals(fileExt)) {
                result = convertPptxWithPoi(filePathObj);
            } else if (ParserCapabilities.IMAGE_FILE_EXTENSIONS.contains(fileExt)) {
                result = parseImage(filePathObj.toString(), params);
            } else if (".html".equals(fileExt) || ".htm".equals(fileExt)) {
                String content = Files.readString(filePathObj, StandardCharsets.UTF_8);
                result = convertHtmlToMarkdown(content);
            } else if (".csv".equals(fileExt)) {
                result = convertCsvToMarkdown(filePathObj);
            } else if (".xls".equals(fileExt) || ".xlsx".equals(fileExt)) {
                result = convertXlsxWithPoi(filePathObj);
            } else if (".json".equals(fileExt)) {
                String content = Files.readString(filePathObj, StandardCharsets.UTF_8);
                Object data = com.alibaba.fastjson2.JSON.parse(content);
                String jsonStr = InputMessageJson.dumps(data, 2);
                result = "```json\n" + jsonStr + "\n```";
            } else if (".zip".equals(fileExt)) {
                ImageStorage storage = resolveImageStorageParams(params);
                result = ZipUtils.processZipFile(filePathObj.toString(), storage.bucket(), storage.prefix());
            } else {
                throw new IllegalArgumentException("Unsupported file type: " + fileExt);
            }
        } catch (RuntimeException | IOException exc) {
            if (KbUtils.isMinioUrl(source) && tempPath != null && Files.exists(tempPath)) {
                try {
                    Files.deleteIfExists(tempPath);
                    log.debug("Cleaned up temp file: {}", tempPath);
                } catch (Exception cleanupExc) {
                    log.warn("Failed to clean up temp file {}: {}", tempPath, cleanupExc.getMessage());
                }
            }
            if (exc instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(exc.getMessage(), exc);
        } finally {
            if (KbUtils.isMinioUrl(source) && tempPath != null && Files.exists(tempPath)) {
                try {
                    Files.deleteIfExists(tempPath);
                    log.debug("Cleaned up temp file: {}", tempPath);
                } catch (Exception exc) {
                    log.warn("Failed to clean up temp file {}: {}", tempPath, exc.getMessage());
                }
            }
        }

        return result;
    }

    /** 解析图片存储参数（对应 _resolve_image_storage_params）。 */
    static ImageStorage resolveImageStorageParams(Map<String, Object> params) {
        Map<String, Object> effective = params == null ? Map.of() : params;

        String imageBucket = effective.get("image_bucket") == null
                ? MinioStorageClient.getInstance().KB_BUCKETS.get("images")
                : String.valueOf(effective.get("image_bucket"));
        Object imagePrefix = effective.get("image_prefix");
        if (imagePrefix != null) {
            String normalizedPrefix = String.valueOf(imagePrefix).replaceAll("^/+|/+$", "");
            if (!normalizedPrefix.isEmpty()) {
                return new ImageStorage(imageBucket, normalizedPrefix);
            }
        }
        return new ImageStorage(imageBucket, "unknown/kb-images");
    }

    /** 校验 ocr_engine 已由 parse_document() 解析（对应 _resolve_ocr_engine_params）。 */
    static ResolvedOcr resolveOcrEngineParams(Map<String, Object> params) {
        Map<String, Object> effective = params == null ? Map.of() : params;
        String engine = effective.get("ocr_engine") == null ? "" : String.valueOf(effective.get("ocr_engine")).strip();
        if (engine.isEmpty()) {
            throw new IllegalArgumentException("OCR 文件缺少已解析的 ocr_engine，请通过 parse_document() 解析");
        }

        Map<String, Object> processorParams = new java.util.LinkedHashMap<>(effective);
        processorParams.remove("ocr_engine_config");
        return new ResolvedOcr(engine, processorParams);
    }

    /** 弹出 _ocr_processor_kwargs（对应 processor_params.pop）。 */
    private static Map<String, Object> popProcessorKwargs(Map<String, Object> params) {
        Object kwargs = params.remove("_ocr_processor_kwargs");
        if (kwargs instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) map;
            return result;
        }
        return new java.util.LinkedHashMap<>();
    }

    /** OCR 解析参数对（engine + processor_params）。 */
    record ResolvedOcr(String engine, Map<String, Object> params) {}

    /** 图片存储参数对（bucket + prefix）。 */
    record ImageStorage(String bucket, String prefix) {}

    // ==================== Office 转换（POI 承载，见类注释能力差异） ====================

    /** 使用 python-docx 兜底算法解析 DOCX（POI XWPF 承载，算法逐字对齐）。 */
    static String convertDocxWithPoi(Path filePath) {
        try (org.apache.poi.xwpf.usermodel.XWPFDocument document =
                new org.apache.poi.xwpf.usermodel.XWPFDocument(Files.newInputStream(filePath))) {
            List<String> blocks = new ArrayList<>();

            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : document.getParagraphs()) {
                String text = para.getText().strip();
                if (!text.isEmpty()) {
                    blocks.add(text);
                }
            }

            for (org.apache.poi.xwpf.usermodel.XWPFTable table : document.getTables()) {
                List<List<String>> rows = new ArrayList<>();
                for (org.apache.poi.xwpf.usermodel.XWPFTableRow tableRow : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (org.apache.poi.xwpf.usermodel.XWPFTableCell cell : tableRow.getTableCells()) {
                        cells.add(cell.getText().strip().replace("\n", " "));
                    }
                    if (cells.stream().anyMatch(cell -> !cell.isEmpty())) {
                        rows.add(cells);
                    }
                }

                if (rows.isEmpty()) {
                    continue;
                }

                List<String> header = rows.get(0);
                blocks.add("| " + String.join(" | ", header) + " |");
                blocks.add("| " + String.join(" | ", header.stream().map(col -> "---").toList()) + " |");

                for (int i = 1; i < rows.size(); i++) {
                    List<String> row = new ArrayList<>(rows.get(i));
                    while (row.size() < header.size()) {
                        row.add("");
                    }
                    blocks.add("| " + String.join(" | ", row.subList(0, header.size())) + " |");
                }

                blocks.add("");
            }

            return String.join("\n\n", blocks).strip();
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** PPTX 简化转换（POI 文本帧；docling 版面能力缺失，见类注释）。 */
    static String convertPptxWithPoi(Path filePath) {
        try (org.apache.poi.xslf.usermodel.XMLSlideShow presentation =
                new org.apache.poi.xslf.usermodel.XMLSlideShow(Files.newInputStream(filePath))) {
            List<String> slideTexts = new ArrayList<>();
            for (org.apache.poi.xslf.usermodel.XSLFSlide slide : presentation.getSlides()) {
                List<String> frameTexts = new ArrayList<>();
                for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text != null && !text.strip().isEmpty()) {
                            frameTexts.add(text.strip());
                        }
                    }
                }
                if (!frameTexts.isEmpty()) {
                    slideTexts.add(String.join("\n\n", frameTexts));
                }
            }
            return String.join("\n\n", slideTexts);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    /** XLSX/XLS 简化转换（POI 单元格 → 每张 sheet 一张管道表格；docling 能力差异见类注释）。 */
    static String convertXlsxWithPoi(Path filePath) {
        try (InputStream in = Files.newInputStream(filePath);
                org.apache.poi.ss.usermodel.Workbook workbook =
                        org.apache.poi.ss.usermodel.WorkbookFactory.create(in)) {
            List<String> sheetTables = new ArrayList<>();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(s);
                List<List<String>> rows = new ArrayList<>();
                for (org.apache.poi.ss.usermodel.Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        org.apache.poi.ss.usermodel.Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : cellText(cell));
                    }
                    rows.add(cells);
                }
                // 去除全空行
                rows.removeIf(row -> row.stream().allMatch(cell -> cell.isEmpty()));
                if (rows.isEmpty()) {
                    continue;
                }
                int width = rows.stream().mapToInt(List::size).max().orElse(0);
                List<String> lines = new ArrayList<>();
                for (int r = 0; r < rows.size(); r++) {
                    List<String> padded = new ArrayList<>(rows.get(r));
                    while (padded.size() < width) {
                        padded.add("");
                    }
                    lines.add("| " + String.join(" | ", padded) + " |");
                    if (r == 0) {
                        lines.add("| " + String.join(" | ", java.util.Collections.nCopies(width, "---")) + " |");
                    }
                }
                sheetTables.add(String.join("\n", lines));
            }
            return String.join("\n\n", sheetTables);
        } catch (IOException | org.apache.poi.EncryptedDocumentException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
    }

    private static String cellText(org.apache.poi.ss.usermodel.Cell cell) {
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> {
                    double value = cell.getNumericCellValue();
                    yield value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
                }
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cell.getCellFormula();
                case BLANK -> "";
                default -> "";
            };
        } catch (RuntimeException exc) {
            return "";
        }
    }

    // ==================== HTML / CSV ====================

    /** markdownify(heading_style="ATX") 的 jsoup 子集实现（见类注释能力差异）。 */
    static String convertHtmlToMarkdown(String content) {
        org.jsoup.nodes.Document document = org.jsoup.Jsoup.parse(content);
        StringBuilder builder = new StringBuilder();
        renderNode(document.body(), builder);
        return builder.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    private static void renderNode(org.jsoup.nodes.Node node, StringBuilder builder) {
        if (node instanceof org.jsoup.nodes.TextNode textNode) {
            String text = textNode.text();
            if (!text.isEmpty()) {
                builder.append(text);
            }
            return;
        }
        if (!(node instanceof org.jsoup.nodes.Element element)) {
            node.childNodes().forEach(child -> renderNode(child, builder));
            return;
        }

        String tag = element.tagName().toLowerCase();
        switch (tag) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                builder.append("\n\n").append("#".repeat(level)).append(' ')
                        .append(element.text()).append("\n\n");
            }
            case "p" -> {
                element.childNodes().forEach(child -> renderNode(child, builder));
                builder.append("\n\n");
            }
            case "br" -> builder.append('\n');
            case "strong", "b" -> builder.append("**").append(element.text()).append("**");
            case "em", "i" -> builder.append("*").append(element.text()).append("*");
            case "del", "s", "strike" -> builder.append("~~").append(element.text()).append("~~");
            case "code" -> builder.append("`").append(element.text()).append("`");
            case "pre" -> builder.append("\n\n```\n").append(element.wholeText()).append("\n```\n\n");
            case "a" -> builder.append('[').append(element.text()).append("](").append(element.attr("href")).append(')');
            case "img" -> builder.append("![").append(element.attr("alt")).append("](")
                    .append(element.attr("src")).append(')');
            case "ul", "ol" -> {
                builder.append('\n');
                int index = 1;
                for (org.jsoup.nodes.Element li : element.children()) {
                    if ("li".equals(li.tagName().toLowerCase())) {
                        builder.append(tag.equals("ol") ? index + ". " : "- ");
                        li.childNodes().forEach(child -> renderNodeInline(child, builder));
                        builder.append('\n');
                        index++;
                    }
                }
                builder.append('\n');
            }
            case "blockquote" -> {
                builder.append("\n");
                StringBuilder inner = new StringBuilder();
                element.childNodes().forEach(child -> renderNode(child, inner));
                for (String line : inner.toString().strip().split("\n", -1)) {
                    builder.append("> ").append(line).append('\n');
                }
                builder.append('\n');
            }
            case "hr" -> builder.append("\n---\n");
            default -> node.childNodes().forEach(child -> renderNode(child, builder));
        }
    }

    private static void renderNodeInline(org.jsoup.nodes.Node node, StringBuilder builder) {
        if (node instanceof org.jsoup.nodes.TextNode textNode) {
            builder.append(textNode.text());
            return;
        }
        if (!(node instanceof org.jsoup.nodes.Element element)) {
            node.childNodes().forEach(child -> renderNodeInline(child, builder));
            return;
        }
        String tag = element.tagName().toLowerCase();
        switch (tag) {
            case "strong", "b" -> builder.append("**").append(element.text()).append("**");
            case "em", "i" -> builder.append("*").append(element.text()).append("*");
            case "del", "s", "strike" -> builder.append("~~").append(element.text()).append("~~");
            case "code" -> builder.append("`").append(element.text()).append("`");
            case "a" -> builder.append('[').append(element.text()).append("](").append(element.attr("href")).append(')');
            case "img" -> builder.append("![").append(element.attr("alt")).append("](")
                    .append(element.attr("src")).append(')');
            case "br" -> builder.append('\n');
            default -> element.childNodes().forEach(child -> renderNodeInline(child, builder));
        }
    }

    /** CSV → Markdown（参考实现逐行成表的行为保留；pandas 对齐差异见类注释）。 */
    static String convertCsvToMarkdown(Path filePath) {
        List<List<String>> records = parseCsv(filePath);
        if (records.isEmpty()) {
            return "";
        }
        List<String> header = records.get(0);
        List<String> tables = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            // 参考实现：df.iloc[[i]].to_markdown(index=False) → 每行一张带表头的独立表格
            List<String> row = records.get(i);
            StringBuilder table = new StringBuilder();
            table.append("| ").append(String.join(" | ", header)).append(" |\n");
            table.append("| ").append(String.join(" | ", header.stream().map(col -> "---").toList()))
                    .append(" |\n");
            List<String> padded = new ArrayList<>(row);
            while (padded.size() < header.size()) {
                padded.add("");
            }
            table.append("| ").append(String.join(" | ", padded.subList(0, header.size()))).append(" |");
            tables.add(table.toString());
        }
        return String.join("\n\n", tables);
    }

    /** RFC 4180 风格 CSV 解析（引号转义、跨行字段）。 */
    private static List<List<String>> parseCsv(Path filePath) {
        List<List<String>> records = new ArrayList<>();
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            List<String> fieldChars = new ArrayList<>();
            List<String> fields = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            boolean inQuotes = false;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                            fieldChars.add("\"");
                            i++;
                        } else {
                            inQuotes = false;
                        }
                    } else {
                        fieldChars.add(String.valueOf(c));
                    }
                } else if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(String.join("", fieldChars));
                    fieldChars.clear();
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                        i++;
                    }
                    fields.add(String.join("", fieldChars));
                    fieldChars.clear();
                    if (!(fields.size() == 1 && fields.get(0).isEmpty())) {
                        rows.add(fields);
                    }
                    fields = new ArrayList<>();
                } else {
                    fieldChars.add(String.valueOf(c));
                }
            }
            fields.add(String.join("", fieldChars));
            if (!(fields.size() == 1 && fields.get(0).isEmpty())) {
                rows.add(fields);
            }
            records.addAll(rows);
        } catch (IOException exc) {
            throw new IllegalStateException(exc.getMessage(), exc);
        }
        return records;
    }

    /** json.dumps(data, ensure_ascii=False, indent=2) 的等价输出（复用 InputMessageService 的转义规则）。 */
    private static final class InputMessageJson {

        static String dumps(Object value, int indent) {
            StringBuilder builder = new StringBuilder();
            dumps(builder, value, indent, 0);
            return builder.toString();
        }

        private static void dumps(StringBuilder builder, Object value, int indent, int depth) {
            String pad = " ".repeat(indent * (depth + 1));
            String padEnd = " ".repeat(indent * depth);
            if (value == null) {
                builder.append("null");
            } else if (value instanceof String text) {
                dumpsString(builder, text);
            } else if (value instanceof Boolean || value instanceof Number) {
                builder.append(value);
            } else if (value instanceof Map<?, ?> map) {
                if (map.isEmpty()) {
                    builder.append("{}");
                    return;
                }
                builder.append("{\n");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        builder.append(",\n");
                    }
                    first = false;
                    builder.append(pad);
                    dumpsString(builder, String.valueOf(entry.getKey()));
                    builder.append(": ");
                    dumps(builder, entry.getValue(), indent, depth + 1);
                }
                builder.append('\n').append(padEnd).append('}');
            } else if (value instanceof Iterable<?> iterable) {
                if (!iterable.iterator().hasNext()) {
                    builder.append("[]");
                    return;
                }
                builder.append("[\n");
                boolean first = true;
                for (Object item : iterable) {
                    if (!first) {
                        builder.append(",\n");
                    }
                    first = false;
                    builder.append(pad);
                    dumps(builder, item, indent, depth + 1);
                }
                builder.append('\n').append(padEnd).append(']');
            } else {
                dumpsString(builder, String.valueOf(value));
            }
        }

        private static void dumpsString(StringBuilder builder, String text) {
            builder.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    case '\b' -> builder.append("\\b");
                    case '\f' -> builder.append("\\f");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            builder.append(String.format("\\u%04x", (int) c));
                        } else {
                            builder.append(c);
                        }
                    }
                }
            }
            builder.append('"');
        }
    }
}
