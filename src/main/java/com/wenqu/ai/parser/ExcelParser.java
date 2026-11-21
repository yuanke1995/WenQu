package com.wenqu.ai.parser;

import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.model.Chunk;
import com.wenqu.ai.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Excel 解析器（POI）
 * 每个 sheet 转"行列文本"，超长按行切分；sheet 名作标题
 * <ul>
 *   <li>公式单元格求值（=A1+B2 输出计算结果而非公式原文；求值失败退公式原文）</li>
 *   <li>日期/时间按展示格式输出（yyyy-MM-dd[ HH:mm:ss]），不再输出 Excel 序列号</li>
 *   <li>数值去二进制噪声（0.30000000000000004 → 0.3），大整数/科学计数转普通记法</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelParser implements DocumentParser {

    private final AppProperties properties;

    /** 配置读取点：分块粒度必须走这里，知识库 parse_params 的 chunk.maxSize 覆盖经线程局部生效 */
    private final ConfigService configService;

    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单块最大字符数：全局设置，或所属知识库 parse_params 的覆盖（解析任务线程内） */
    private int maxChunkSize() {
        return configService.getInt("chunk.maxSize", properties.getChunk().getMaxSize());
    }

    @Override
    public boolean supports(String ext) {
        // 仅 xlsx：XSSFWorkbook 读不了旧版 .xls（声明支持会导致必然解析失败）
        return "xlsx".equalsIgnoreCase(ext);
    }

    @Override
    public java.util.Set<String> supportedExts() {
        return java.util.Set.of("xlsx");
    }

    @Override
    public List<Chunk> parse(java.nio.file.Path file, String fileName, String docId) throws Exception {
        int maxSize = maxChunkSize();
        List<Chunk> chunks = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(java.nio.file.Files.newInputStream(file))) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet == null) continue;
                String sheetName = sheet.getSheetName();
                Map<Integer, String> headers = null;
                for (Row row : sheet) {
                    Map<Integer, String> cells = rowCells(row, evaluator);
                    if (cells.isEmpty()) continue;
                    if (headers == null) {
                        headers = cells;
                        continue;
                    }
                    int rowNo = row.getRowNum() + 1;
                    StringBuilder line = new StringBuilder("第").append(rowNo).append("行：");
                    for (Map.Entry<Integer, String> e : cells.entrySet()) {
                        String name = headers.getOrDefault(e.getKey(), "列" + (e.getKey() + 1));
                        if (line.length() > 9) line.append(" | ");
                        line.append(name).append(": ").append(e.getValue());
                    }
                    String text = line.toString();
                    if (text.length() > maxSize) text = text.substring(0, maxSize) + "…（已截断）";
                    // 坐标走 titlePath：前端引用已支持章节路径，零 schema 改动即可定位到行
                    chunks.add(new Chunk(sheetName + " 第" + rowNo + "行", text, List.of(),
                            sheetName + "/第" + rowNo + "行"));
                }
            }
        }
        log.info("[Excel] {} 解析出 {} 个行级分块", fileName, chunks.size());
        return chunks;
    }

    /** 按物理列号保留单元格位置，空单元格不占位错列 */
    private Map<Integer, String> rowCells(Row row, FormulaEvaluator evaluator) {
        Map<Integer, String> cells = new java.util.LinkedHashMap<>();
        for (Cell cell : row) {
            String v = cellValue(cell, evaluator);
            if (!v.isEmpty()) cells.put(cell.getColumnIndex(), v);
        }
        return cells;
    }

    private String cellValue(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? formatDate(cell.getLocalDateTimeCellValue())
                    : formatNumber(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> formulaValue(cell, evaluator);
            default -> "";
        };
    }

    /**
     * 公式求值：成功按结果类型格式化（日期按日期格式），求值失败（未解析/循环引用等）退公式原文，
     * 保证"能取到值就不输出公式串"——公式原文对问答无信息量。
     */
    private String formulaValue(Cell cell, FormulaEvaluator evaluator) {
        if (evaluator == null) return cell.getCellFormula();
        try {
            CellValue cv = evaluator.evaluate(cell);
            return switch (cv.getCellType()) {
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                        ? formatDate(DateUtil.getLocalDateTime(cv.getNumberValue()))
                        : formatNumber(cv.getNumberValue());
                case STRING -> cv.getStringValue().trim();
                case BOOLEAN -> String.valueOf(cv.getBooleanValue());
                case ERROR -> "#" + FormulaError.forInt(cv.getErrorValue()).getString();
                default -> "";
            };
        } catch (Exception e) {
            return cell.getCellFormula();
        }
    }

    /** 数值去显示噪声：整数按 long；小数做 15 位有效数字舍入（消二进制误差）后去尾零；NaN/Inf 原样 */
    private String formatNumber(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
        BigDecimal bd = BigDecimal.valueOf(d).round(new MathContext(15));
        if (bd.signum() == 0) return "0";
        return bd.stripTrailingZeros().toPlainString();
    }

    /** 日期：整天输出 yyyy-MM-dd，带时刻输出到秒（xlsx 无时区，纯展示语义） */
    private String formatDate(LocalDateTime dt) {
        if (dt == null) return "";
        return dt.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)
                ? dt.toLocalDate().toString()
                : dt.format(DATETIME_FMT);
    }
}
