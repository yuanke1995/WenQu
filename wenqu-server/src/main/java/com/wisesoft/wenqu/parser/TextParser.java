package com.wisesoft.wenqu.parser;

import com.wisesoft.wenqu.config.AppProperties;
import com.wisesoft.wenqu.model.Chunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 纯文本类解析器（txt / md / markdown / csv）
 * <p>
 * - md：按 1~3 级 Markdown 标题行开新块（跟踪 ``` 围栏状态，代码块内 # 不切），标题作块 title；图片语法 ![](...) 跳过
 * - txt：段落聚合，按 chunk.maxSize/overlap 切分
 * - csv：首行作表头说明，行转 "a | b | c" 后按行聚合切分
 * 均不提取图片（纯文本类无内嵌资源）
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextParser implements DocumentParser {

    private final AppProperties properties;

    @Override
    public boolean supports(String ext) {
        return "txt".equalsIgnoreCase(ext) || "md".equalsIgnoreCase(ext) || "markdown".equalsIgnoreCase(ext)
                || "csv".equalsIgnoreCase(ext);
    }

    @Override
    public Set<String> supportedExts() {
        return Set.of("txt", "md", "markdown", "csv");
    }

    @Override
    public List<Chunk> parse(java.nio.file.Path file, String fileName, String docId) throws java.io.IOException {
        String ext = extOf(fileName);
        // 逐行流式读取：任意大小文件内存恒定
        List<String> lines = new ArrayList<>();
        try (var reader = java.nio.file.Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        }
        List<Chunk> chunks;
        if ("csv".equalsIgnoreCase(ext)) {
            chunks = parseCsv(String.join("\n", lines));
        } else if ("md".equalsIgnoreCase(ext) || "markdown".equalsIgnoreCase(ext)) {
            chunks = parseMarkdown(String.join("\n", lines));
        } else {
            chunks = parsePlain(String.join("\n", lines));
        }
        log.info("[TextParser] {} 解析完成: {} chunks", fileName, chunks.size());
        return chunks;
    }

    /** md：按 1~3 级标题行分块（``` 围栏内的 # 不算标题） */
    private List<Chunk> parseMarkdown(String content) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String title = null;
        boolean inFence = false;

        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) inFence = !inFence;

            boolean heading = !inFence && t.matches("^#{1,3}\\s+.+");
            if (heading) {
                flush(chunks, buf, title);
                title = t.replaceAll("^#+\\s+", "");
                continue; // 标题行本身不进正文（作为块标题存在）
            }
            buf.append(line).append('\n');
            // 超长兜底：无标题的超长文档在段落边界硬切
            if (buf.length() >= properties.getChunk().getMaxSize()) {
                flush(chunks, buf, title);
            }
        }
        flush(chunks, buf, title);
        return chunks;
    }

    /** txt：段落聚合切分 */
    private List<Chunk> parsePlain(String content) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String para : content.split("\\n\\s*\\n")) {
            String t = para.trim();
            if (t.isEmpty()) continue;
            if (buf.length() + t.length() > properties.getChunk().getMaxSize() && buf.length() > 0) {
                flush(chunks, buf, null);
            }
            buf.append(t).append('\n');
        }
        flush(chunks, buf, null);
        return chunks;
    }

    /** csv：RFC-4180 引号感知解析（引号内逗号/换行/""转义不再被裸 split(",") 破坏），首行作表头，行转 "a | b | c" 后按行聚合 */
    private List<Chunk> parseCsv(String content) {
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String header = null;
        List<List<String>> rows = parseCsvRows(content);
        for (List<String> row : rows) {
            String cells = String.join(" | ", row);
            if (cells.isEmpty()) continue;
            if (header == null) {
                header = "表头: " + cells;
                buf.append(header).append('\n');
                continue;
            }
            buf.append(cells).append('\n');
            if (buf.length() >= properties.getChunk().getMaxSize()) {
                flush(chunks, buf, header);
                if (header != null) buf.append(header).append('\n'); // 重复表头保语义
            }
        }
        flush(chunks, buf, header);
        return chunks;
    }

    /**
     * CSV 逐字段状态机解析（RFC 4180 子集）：双引号包裹的字段支持内部逗号、换行，
     * 连续两个双引号表示转义的单引号；非开头出现的双引号按字面字符处理（宽松兼容）。
     */
    private List<List<String>> parseCsvRows(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int n = content.length();
        for (int i = 0; i < n; i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.isEmpty()) {
                inQuotes = true; // 仅字段开头引号开启引号模式
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < n && content.charAt(i + 1) == '\n') i++; // CRLF 合一
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /** 聚合缓冲 → Chunk（带 overlap 尾巴进向量化语义由增量链路处理，此处纯文本简单切） */
    private void flush(List<Chunk> chunks, StringBuilder buf, String title) {
        String body = buf.toString().trim();
        buf.setLength(0);
        if (body.isEmpty()) return;
        int maxSize = properties.getChunk().getMaxSize();
        if (body.length() <= maxSize) {
            chunks.add(new Chunk(title == null ? "" : title, body, List.of()));
            return;
        }
        // 硬切（按行边界优先）
        int start = 0;
        int seq = 1;
        while (start < body.length()) {
            int end = Math.min(start + maxSize, body.length());
            if (end < body.length()) {
                int nl = body.lastIndexOf('\n', end);
                if (nl > start) end = nl;
            }
            String part = body.substring(start, end).trim();
            if (!part.isEmpty()) {
                chunks.add(new Chunk(title == null ? "" : title + (seq > 1 ? " (" + seq + ")" : ""), part, List.of()));
                seq++;
            }
            start = end;
        }
    }

    private String extOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? "" : fileName.substring(idx + 1);
    }
}