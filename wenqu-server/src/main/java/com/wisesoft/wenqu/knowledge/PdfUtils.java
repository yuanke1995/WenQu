package com.wisesoft.wenqu.knowledge;

import com.wisesoft.wenqu.knowledge.parser.DocumentParserException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析前置检查（knowledge/utils/pdf_utils.py 全量移植）。
 *
 * <p>能力差异（显式标注）：参考实现用 pypdfium2 逐页槽触发 {@code page.get_size()}
 * 的基础解析；Java 用 PDFBox 逐页 {@code getPage(i)} 访问页槽——两者都在解析器之前
 * 暴露 null 页槽/非 Page 对象/循环引用类结构异常，但底层库报错文案不同
 * （issue.message 为 PDFBox 原文，仅进入「底层错误」详情字段，不影响状态码与用户文案）。
 */
@Slf4j
public final class PdfUtils {

    /** PDF 页面加载异常位置（对应 PDFPageLoadIssue dataclass）。 */
    public record PdfPageLoadIssue(int pageNumber, String message) {}

    private PdfUtils() {}

    /** 格式化页码列表，避免异常信息过长。 */
    static String formatPageNumbers(List<Integer> pageNumbers, int limit) {
        List<Integer> visible = pageNumbers.subList(0, Math.min(limit, pageNumbers.size()));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < visible.size(); i++) {
            if (i > 0) {
                result.append("、");
            }
            result.append(visible.get(i));
        }
        if (pageNumbers.size() > limit) {
            result.append(" 等 ").append(pageNumbers.size()).append(" 个");
        }
        return result.toString();
    }

    /** 校验 PDF 页树中的每一个页槽都能作为页面加载。 */
    public static void validatePdfPageTreeLoadable(String filePath) {
        File path = new File(filePath);

        PDDocument doc;
        try {
            doc = org.apache.pdfbox.Loader.loadPDF(path);
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException exc) {
            // 参考实现按错误消息含 password 识别加密 PDF
            throw new DocumentParserException(
                    "PDF 文件已加密或需要密码，无法进入文档解析流程",
                    "pdf_preflight",
                    "encrypted_pdf");
        } catch (IOException exc) {
            throw new DocumentParserException(
                    "PDF 文件结构异常，无法打开页面目录: " + exc.getMessage(),
                    "pdf_preflight",
                    "invalid_pdf_structure");
        }

        try {
            int pageCount = doc.getNumberOfPages();
            if (pageCount <= 0) {
                throw new DocumentParserException(
                        "PDF 文件没有可解析页面",
                        "pdf_preflight",
                        "empty_pdf");
            }

            List<PdfPageLoadIssue> issues = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                try {
                    PDPage page = doc.getPage(pageIndex);
                    // 访问页面尺寸会触发页面对象基础解析，能提前暴露 null/非 Page 页槽
                    page.getMediaBox().getWidth();
                } catch (RuntimeException exc) {
                    issues.add(new PdfPageLoadIssue(pageIndex + 1, String.valueOf(exc.getMessage())));
                }
            }

            if (!issues.isEmpty()) {
                List<Integer> badPages = issues.stream().map(PdfPageLoadIssue::pageNumber).toList();
                String firstError = issues.get(0).message() == null || issues.get(0).message().isEmpty()
                        ? "页面对象无法加载"
                        : issues.get(0).message();
                throw new DocumentParserException(
                        "PDF 页面结构异常："
                                + "声明页数为 " + pageCount + "，但第 " + formatPageNumbers(badPages, 8)
                                + " 个页槽不是可加载页面对象。底层错误：" + firstError
                                + "。请先用 Acrobat、打印为 PDF、qpdf 或 mutool 等工具重写 PDF 后再上传。",
                        "pdf_preflight",
                        "invalid_pdf_page_tree");
            }
        } finally {
            try {
                doc.close();
            } catch (IOException exc) {
                log.warn("PDF 预检文档关闭失败: {}", exc.getMessage());
            }
        }
    }
}
