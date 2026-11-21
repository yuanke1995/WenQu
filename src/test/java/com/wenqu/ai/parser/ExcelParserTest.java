package com.wenqu.ai.parser;

import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.model.Chunk;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExcelParserTest {

    @Test
    void excelChunksAreRowLevelWithHeaderNames() throws Exception {
        AppProperties properties = new AppProperties();
        ConfigService configService = mock(ConfigService.class);
        when(configService.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        ExcelParser parser = new ExcelParser(properties, configService);

        Path file = Files.createTempFile("excel-parser", ".xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("员工");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("姓名");
            head.createCell(1).setCellValue("城市");
            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("张三");
            first.createCell(1).setCellValue("北京");
            Row second = sheet.createRow(2);
            second.createCell(0).setCellValue("李四");
            second.createCell(1).setCellValue("上海");
            try (var out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }

        List<Chunk> chunks = parser.parse(file, "员工表.xlsx", "doc1");
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).content().contains("姓名: 张三"));
        assertTrue(chunks.get(0).content().contains("城市: 北京"));
        assertEquals("员工/第2行", chunks.get(0).titlePath());
        assertEquals("员工/第3行", chunks.get(1).titlePath());
        Files.deleteIfExists(file);
    }
}
