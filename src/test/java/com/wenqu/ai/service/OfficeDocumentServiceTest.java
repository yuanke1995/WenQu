package com.wenqu.ai.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Office 处理内核单测：不依赖 Spring、LibreOffice 或执行器，用 POI 现造文档验证
 * 纯转换与公式重算这两条「一定能在本机跑通」的路径。
 *
 * @author yuanke
 */
class OfficeDocumentServiceTest {

    private final OfficeDocumentService svc = new OfficeDocumentService();

    private static byte[] xlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("数据");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue(1);
            r0.createCell(1).setCellValue(2);
            r0.createCell(2).setCellValue("备注");
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue(10);
            r1.createCell(1).setCellValue(20);
            Cell f = r1.createCell(2);
            f.setCellFormula("SUM(A2:B2)");
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph p = doc.createParagraph();
            p.createRun().setText("问渠 文档处理 第一段");
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("xlsx → csv：取第一张表，逗号分隔，公式列按当前缓存值输出")
    void xlsxToCsv() throws Exception {
        String csv = svc.xlsxToCsv(xlsx());
        assertThat(csv).contains("1,2,备注");
        assertThat(csv).contains("10,20");
    }

    @Test
    @DisplayName("docx → txt：段落文本进结果")
    void docxToText() throws Exception {
        String text = svc.docxToText(docx());
        assertThat(text).contains("问渠 文档处理 第一段");
    }

    @Test
    @DisplayName("xlsx 公式重算：SUM 出 30，回写后重新打开仍可求值")
    void recalculate() throws Exception {
        OfficeDocumentService.RecalcResult r = svc.recalculateXlsx(xlsx());
        assertThat(r.formulaCells()).isEqualTo(1);
        assertThat(r.errors()).isZero();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(r.bytes()))) {
            Sheet s = wb.getSheetAt(0);
            Cell f = s.getRow(1).getCell(2);
            assertThat(f.getCellType()).isEqualTo(CellType.FORMULA);
            var v = wb.getCreationHelper().createFormulaEvaluator().evaluate(f);
            assertThat(v.getNumberValue()).isEqualTo(30d);
        }
    }

    @Test
    @DisplayName("docx → html：段落转 <p>，标签被转义")
    void docxToHtml() throws Exception {
        String html = new String(svc.convert(docx(), "docx", "html"), StandardCharsets.UTF_8);
        assertThat(html).contains("<p>问渠 文档处理 第一段</p>");
    }

    @Test
    @DisplayName("不支持的转换 fail-loud")
    void unsupported() {
        assertThatThrownBy(() -> svc.convert(docx(), "docx", "csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docx 不能直接转 csv");
    }

    @Test
    @DisplayName("超过体积上限直接拒绝")
    void tooLarge() {
        byte[] big = new byte[(int) OfficeDocumentService.MAX_INPUT_BYTES + 1];
        assertThatThrownBy(() -> svc.convert(big, "xlsx", "csv"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件过大");
    }
}
