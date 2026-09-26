package com.wisesoft.ai.service;

import com.wisesoft.ai.common.BizException;
import com.wisesoft.ai.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 聊天附件解析：把用户在输入框上传的文档类附件（data URL/base64）解码并抽取纯文本，
 * 注入本轮问答上下文（图片走 images 多模态链路，不经此服务）。
 *
 * <p>与知识库解析器（{@link com.wisesoft.ai.parser.DocumentParser}）的差别：知识库解析为建库服务、
 * 可 OCR 可切块；聊天附件是「当轮看一眼」的轻路径——只做文本抽取（PDF 不触发扫描件 OCR），
 * 超长截断（chat.attachmentMaxChars / chat.attachmentTotalChars），不落盘、不进向量库。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentService {

    private final ConfigService configService;

    /** 文本类扩展名（UTF-8 直读；代码/配置/标记语言都按纯文本对待） */
    private static final Set<String> TEXT_EXTS = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "log", "xml", "yml", "yaml", "html", "htm",
            "java", "js", "ts", "jsx", "tsx", "vue", "py", "sql", "sh", "bat", "c", "h", "cpp", "hpp",
            "cs", "go", "rs", "rb", "php", "css", "scss", "less", "properties", "ini", "conf", "toml");
    /** 文件名净化：去路径分隔与控制字符（只用于展示与日志，不参与落盘） */
    private static final String FORBIDDEN_NAME_CHARS = "[\\r\\n\\t\\x00-\\x1f]";

    /** 解析完成的附件（注入上下文用） */
    public record PreparedAttachment(String name, String mime, long size, String text, boolean truncated) {
    }

    /** 附件元信息（随消息持久化，前端气泡回显）：不含内容本体 */
    public static Map<String, Object> metaOf(ChatRequest.Attachment a, long size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.getName());
        m.put("mime", a.getMime() == null ? "" : a.getMime());
        m.put("size", size);
        return m;
    }

    /** 前端上传校验用：扩展名是否受支持（不受支持的在发送前就拒绝，避免白传 base64） */
    public static boolean supportedExt(String name) {
        return extOf(name) != null;
    }

    /**
     * 解析全部附件（数量/体积校验在控制器已完成）：单附件失败不拖垮整轮——
     * 该附件文本置为可读错误说明，其余附件照常注入（fail-loud 到用户可见文案而非静默丢弃）。
     */
    public List<PreparedAttachment> prepare(List<ChatRequest.Attachment> attachments) {
        List<PreparedAttachment> out = new ArrayList<>();
        if (attachments == null || attachments.isEmpty()) return out;
        int maxChars = Math.max(500, configService.getInt("chat.attachmentMaxChars", 8000));
        for (ChatRequest.Attachment a : attachments) {
            String name = sanitizeName(a.getName());
            try {
                byte[] bytes = decode(a.getData());
                out.add(new PreparedAttachment(name, a.getMime(), bytes.length,
                        extractText(name, bytes, maxChars), false));
            } catch (Exception e) {
                log.warn("[ATTACH] 附件解析失败（以错误说明注入）: {} - {}", name, e.getMessage());
                out.add(new PreparedAttachment(name, a.getMime(), 0,
                        "（附件解析失败：" + e.getMessage() + "）", false));
            }
        }
        return out;
    }

    /**
     * 把解析结果拼成注入用户消息的文本段（空附件列表返回空串）。
     * 总量按 chat.attachmentTotalChars 截断，超限时尾部附件整段舍弃并注明。
     */
    public String buildContextText(List<PreparedAttachment> prepared) {
        if (prepared == null || prepared.isEmpty()) return "";
        int totalMax = Math.max(1000, configService.getInt("chat.attachmentTotalChars", 24000));
        StringBuilder sb = new StringBuilder();
        int used = 0;
        List<PreparedAttachment> dropped = new ArrayList<>();
        for (PreparedAttachment p : prepared) {
            String block = "\n▶ 附件：" + p.name() + "\n" + p.text() + "\n";
            if (used + block.length() > totalMax) {
                dropped.add(p);
                continue;
            }
            sb.append(block);
            used += block.length();
        }
        if (!dropped.isEmpty()) {
            sb.append("\n（以下附件过长未注入：")
                    .append(dropped.stream().map(PreparedAttachment::name)
                            .reduce((a, b) -> a + "、" + b).orElse(""))
                    .append("，如需引用请单独提问）");
        }
        return sb.toString().isBlank() ? "" : sb.toString();
    }

    /** 按扩展名分派抽取：pdf/docx/xls(x)/pptx 用对应库，文本类直读，其余拒绝 */
    private String extractText(String name, byte[] bytes, int maxChars) throws Exception {
        String ext = extOf(name);
        if (ext == null) {
            throw new BizException("不支持的附件类型（支持 PDF / Word / Excel / PPT / 文本与代码文件）");
        }
        String text;
        switch (ext) {
            case "pdf" -> text = pdfText(bytes);
            // 老格式 .doc/.ppt 依赖 poi-scratchpad（未引入），只支持 OOXML：docx/xlsx/xls/pptx
            case "docx" -> text = wordText(bytes);
            case "xls", "xlsx" -> text = excelText(bytes);
            case "pptx" -> text = pptText(bytes);
            default -> text = new String(bytes, StandardCharsets.UTF_8);
        }
        boolean truncated = false;
        if (text != null && text.length() > maxChars) {
            text = text.substring(0, maxChars);
            truncated = true;
        }
        if (text == null || text.isBlank()) {
            return "（未能从文件中提取到文本内容" + ("pdf".equals(ext) ? "，可能为扫描件/图片型 PDF）" : "）");
        }
        return truncated ? text + "\n…（内容过长已截断，仅前 " + maxChars + " 字符）" : text.strip();
    }

    private String pdfText(byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String wordText(byte[] bytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private String excelText(byte[] bytes) throws Exception {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : wb) {
                sb.append("【工作表：").append(sheet.getSheetName()).append("】\n");
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        String v = cell == null ? "" : cellText(cell);
                        if (c > 0) line.append(" | ");
                        line.append(v);
                    }
                    if (!line.isEmpty()) sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private String cellText(Cell cell) {
        if (cell.getCellType() == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            return d == Math.floor(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    private String pptText(byte[] bytes) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            for (var slide : ppt.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) {
                        String t = ts.getText();
                        if (t != null && !t.isBlank()) sb.append(t.strip()).append('\n');
                    }
                }
            }
            return sb.toString();
        }
    }

    /** base64 解码：接受 data URL（data:*;base64,xxx）或裸 base64 */
    private byte[] decode(String data) {
        if (data == null || data.isBlank()) throw new BizException("附件内容为空");
        String b64 = data;
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) b64 = data.substring(comma + 1);
        try {
            return Base64.getMimeDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new BizException("附件内容不是有效的 base64 数据");
        }
    }

    private static String extOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (TEXT_EXTS.contains(ext) || ext.equals("pdf") || ext.equals("docx")
                || ext.equals("xls") || ext.equals("xlsx") || ext.equals("pptx")) {
            return ext;
        }
        return null;
    }

    private static String sanitizeName(String name) {
        String n = name == null ? "未命名附件" : name.replaceAll(FORBIDDEN_NAME_CHARS, "");
        return n.isBlank() ? "未命名附件" : (n.length() > 120 ? n.substring(n.length() - 120) : n);
    }
}
