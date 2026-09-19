package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import com.wisesoft.wenqu.knowledge.chunking.ragflow.parser.BookChunkParser;
import com.wisesoft.wenqu.knowledge.chunking.ragflow.parser.GeneralChunkParser;
import com.wisesoft.wenqu.knowledge.chunking.ragflow.parser.LawsChunkParser;
import com.wisesoft.wenqu.knowledge.chunking.ragflow.parser.QaChunkParser;
import com.wisesoft.wenqu.knowledge.chunking.ragflow.parser.SeparatorChunkParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAGFlow 分块调度器（对应参考实现 {@code knowledge/chunking/ragflow_like/dispatcher.py}）。
 * <p>
 * 逐函数移植：{@code chunk_markdown} / {@code chunk_file} / {@code _build_chunk_records} /
 * {@code _dispatch_markdown_parser}。按预设 id 路由到对应的解析器实现；
 * 预设 id 由 {@link com.wisesoft.wenqu.service.ChunkPresets#mapToInternalParserId} 解析。
 * <p>
 * 说明：{@code chunk_file} 与参考实现一致——入库前文本均已转 markdown，故与 {@code chunk_markdown} 同实现。
 */
public final class RagflowChunkDispatcher {

    private RagflowChunkDispatcher() {
    }

    /** 构建 chunk 记录（对应 _build_chunk_records）。 */
    public static List<Map<String, Object>> buildChunkRecords(
            List<String> textChunks, String fileId, String filename, String sourceText) {
        List<Map<String, Object>> records = new ArrayList<>();
        int searchFrom = 0;
        int idx = 0;
        for (String chunkContent : textChunks) {
            String text = (chunkContent == null ? "" : chunkContent).strip();
            if (text.isEmpty()) {
                continue;
            }
            Integer startCharPos = null;
            Integer endCharPos = null;
            if (sourceText != null) {
                int foundAt = sourceText.indexOf(text, searchFrom);
                if (foundAt >= 0) {
                    startCharPos = foundAt;
                    endCharPos = foundAt + text.length();
                    searchFrom = endCharPos;
                }
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("id", fileId + "_chunk_" + idx);
            record.put("content", text);
            record.put("file_id", fileId);
            record.put("filename", filename);
            record.put("chunk_index", idx);
            record.put("source", filename);
            record.put("chunk_id", fileId + "_chunk_" + idx);
            record.put("start_char_pos", startCharPos);
            record.put("end_char_pos", endCharPos);
            record.put("start_token_pos", null);
            record.put("end_token_pos", null);
            record.put("extraction_result", null);
            records.add(record);
            idx++;
        }
        return records;
    }

    /** 按预设路由到解析器（对应 _dispatch_markdown_parser）。 */
    public static List<String> dispatchMarkdownParser(
            String presetId, String filename, String markdownContent, Map<String, Object> parserConfig) {
        String parserId = com.wisesoft.wenqu.service.ChunkPresets.mapToInternalParserId(presetId);
        switch (parserId) {
            case "qa":
                return QaChunkParser.chunkMarkdown(filename, markdownContent, parserConfig);
            case "book":
                return BookChunkParser.chunkMarkdown(markdownContent, parserConfig);
            case "laws":
                return LawsChunkParser.chunkMarkdown(filename, markdownContent, parserConfig);
            case "separator":
                return SeparatorChunkParser.chunkMarkdown(markdownContent, parserConfig);
            case "semantic":
                // semantic 解析器为下一批移植项；未就绪时回落通用切分（与参考 dispatcher 默认分支一致）
                return GeneralChunkParser.chunkMarkdown(markdownContent, parserConfig);
            case "naive":
            default:
                return GeneralChunkParser.chunkMarkdown(markdownContent, parserConfig);
        }
    }

    /** 返回 chunk 记录（对应 chunk_markdown）。 */
    public static List<Map<String, Object>> chunkMarkdown(
            String markdownContent, String fileId, String filename, Map<String, Object> processingParams) {
        Map<String, Object> params = processingParams == null ? new LinkedHashMap<>() : new LinkedHashMap<>(processingParams);
        String presetId = com.wisesoft.wenqu.service.ChunkPresets.normalizeChunkPresetId(
                (String) params.get("chunk_preset_id"));
        Object cfg = params.get("chunk_parser_config");
        Map<String, Object> parserConfig = (cfg instanceof Map) ? (Map<String, Object>) cfg : new LinkedHashMap<>();

        List<String> textChunks = dispatchMarkdownParser(presetId, filename, markdownContent, parserConfig);
        return buildChunkRecords(textChunks, fileId, filename, markdownContent);
    }

    /** 文件级分块（对应 chunk_file，与 chunk_markdown 同实现）。 */
    public static List<Map<String, Object>> chunkFile(
            String fileContent, String fileId, String filename, Map<String, Object> processingParams) {
        return chunkMarkdown(fileContent, fileId, filename, processingParams);
    }
}
