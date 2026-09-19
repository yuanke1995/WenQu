package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import com.wisesoft.wenqu.model.Chunk;
import com.wisesoft.wenqu.service.ChunkPresets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 验证 DocumentService 接入点：解析器结构化块 → 重建 markdown → RAGFlow 预设重切。 */
public class DocumentRechunkTest {
    public static void main(String[] args) {
        int fail = 0;

        // 构造一个"解析器产物"：结构化块，带标题 + 章节路径 + 正文
        List<Chunk> parsed = new ArrayList<>();
        parsed.add(new Chunk("总则", "第一条 本规定适用于全体成员。", List.of(), "第一章/总则"));
        parsed.add(new Chunk("细则", "第二十一条 实施细则如下：\n- 按时报到\n- 遵守纪律\n- 完成任务", List.of(), "第二章/细则"));
        parsed.add(new Chunk("附表", "| 序号 | 事项 |\n| 1 | 报到 |\n| 2 | 值班 |", List.of(), "附录/附表"));

        // 镜像 DocumentService.rebuildMarkdownFromChunks 的逻辑
        String markdown = rebuildMarkdownFromChunks(parsed);
        System.out.println("=== rebuilt markdown ===");
        System.out.println(markdown);
        if (!markdown.contains("# 第一章") || !markdown.contains("第一条 本规定") || !markdown.contains("| 序号 |")) {
            System.out.println("  FAIL: markdown rebuild missing structure");
            fail++;
        }

        // 预设解析 + dispatcher 路由：分别走 4 个预设，确认都产出块且不同
        Map<String, Object> kbParams = new LinkedHashMap<>();
        kbParams.put("chunk_preset_id", "laws");
        kbParams.put("chunk_parser_config", new LinkedHashMap<>(Map.of("chunk_token_num", 256)));
        Map<String, Object> resolved = ChunkPresets.resolveChunkProcessingParams(kbParams, null, null);
        System.out.println("=== resolved preset=" + resolved.get("chunk_preset_id")
                + " parserId=" + ChunkPresets.mapToInternalParserId((String) resolved.get("chunk_preset_id")));
        if (!"laws".equals(resolved.get("chunk_preset_id"))
                || !"laws".equals(ChunkPresets.mapToInternalParserId((String) resolved.get("chunk_preset_id")))) {
            System.out.println("  FAIL: preset resolution/routing wrong");
            fail++;
        }

        // 4 个预设各跑一遍，验证都产出块
        String[] presets = {"laws", "book", "separator", "semantic"};
        for (String p : presets) {
            Map<String, Object> rp = new LinkedHashMap<>();
            rp.put("chunk_preset_id", p);
            rp.put("chunk_parser_config", new LinkedHashMap<>(Map.of("chunk_token_num", 256)));
            Map<String, Object> rr = ChunkPresets.resolveChunkProcessingParams(rp, null, null);
            List<Map<String, Object>> records = RagflowChunkDispatcher.chunkMarkdown(markdown, "doc1", "t.md", rr);
            long nonEmpty = records.stream().filter(r -> !r.get("content").toString().isBlank()).count();
            System.out.println("[" + p + "] chunkMarkdown records=" + records.size() + " nonEmpty=" + nonEmpty);
            if (nonEmpty == 0) {
                System.out.println("  FAIL: preset " + p + " produced no non-empty chunks");
                fail++;
            }
        }

        // 默认 general → naive，走解析器分块（不触发 RAGFlow）
        String defaultParser = ChunkPresets.mapToInternalParserId(null);
        System.out.println("=== default preset parserId=" + defaultParser);
        if (!"naive".equals(defaultParser)) {
            System.out.println("  FAIL: default should map to naive");
            fail++;
        }

        System.out.println(fail == 0 ? "\nALL PASS" : "\n" + fail + " FAILURES");
    }

    /** 与 DocumentService.rebuildMarkdownFromChunks 一致的逻辑。 */
    private static String rebuildMarkdownFromChunks(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            if (c.titlePath() != null && !c.titlePath().isBlank()) {
                String[] parts = c.titlePath().split("/");
                for (int i = 0; i < parts.length; i++) {
                    String p = parts[i].strip();
                    if (!p.isEmpty()) sb.append("#".repeat(Math.min(i + 1, 6))).append(" ").append(p).append("\n");
                }
            }
            if (c.title() != null && !c.title().isBlank()) sb.append("# ").append(c.title()).append("\n");
            sb.append(c.content()).append("\n\n");
        }
        return sb.toString();
    }
}
