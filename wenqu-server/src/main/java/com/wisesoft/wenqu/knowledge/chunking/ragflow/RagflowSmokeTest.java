package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 临时功能验证：RAGFlow 分块家族 + 语义解析器。 */
public class RagflowSmokeTest {
    public static void main(String[] args) {
        int fail = 0;

        // 1) semantic 解析器：标题 + 段落 + 列表 + 表格 + 数学 + HTML 表格
        String md = "# 第一章 总则\n\n这是第一段内容，用于测试语义切分是否正常。\n\n## 1.1 条款\n\n- 条目甲\n- 条目乙\n- 条目丙\n\n| 姓名 | 年龄 |\n|------|------|\n| 张三 | 25 |\n| 李四 | 30 |\n\n$$ x^2 $$ \n\n<表>\n<table><tr><th>k</th><th>v</th></tr><tr><td>a</td><td>1</td></tr></table>\n\n结尾段落。";
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("chunk_token_num", 200);
        List<String> chunks = SemanticChunkParser.chunkMarkdown(md, cfg, null);
        System.out.println("[semantic] chunks=" + chunks.size());
        if (chunks.isEmpty()) {
            System.out.println("  FAIL: semantic produced no chunks");
            fail++;
        } else {
            for (String c : chunks) {
                System.out.println("  -- chunk(" + c.length() + ") --\n" + (c.length() > 160 ? c.substring(0, 160) + "…" : c));
            }
        }

        // 2) dispatcher 路由 semantic 预设
        List<String> dispatched = RagflowChunkDispatcher.dispatchMarkdownParser("semantic", "t.md", md, cfg);
        System.out.println("[dispatch semantic] chunks=" + dispatched.size());
        if (dispatched.isEmpty()) {
            System.out.println("  FAIL: dispatcher semantic returned nothing");
            fail++;
        }

        // 3) table_utils 纯 Java 表格解析
        String html = "<table><tr><th>姓名</th><th>年龄</th><th>性别</th></tr>"
                + "<tr><td>张三</td><td>25</td><td>男</td></tr>"
                + "<tr><td>李四</td><td>30</td><td>女</td></tr></table>";
        List<String> kv = RagflowTableUtils.htmlTableToKeyValue(html);
        System.out.println("[table_utils] kv=" + kv);
        if (kv.size() != 2 || !kv.get(0).contains("姓名：张三") || !kv.get(0).contains("年龄：25")) {
            System.out.println("  FAIL: table_utils KV wrong");
            fail++;
        }

        // 4) rowspan/colspan 合并单元格（姓名经 rowspan 下延到 row1 的 col0 → 键"姓名"值"姓名"）
        String merged = "<table><tr><th rowspan=\"2\">姓名</th><th>年龄</th></tr>"
                + "<tr><td>25</td></tr></table>";
        List<String> mkv = RagflowTableUtils.htmlTableToKeyValue(merged);
        System.out.println("[table_utils merged] kv=" + mkv);
        if (mkv.size() != 1 || !mkv.get(0).equals("姓名：姓名；年龄：25；")) {
            System.out.println("  FAIL: merged cell KV wrong: " + mkv);
            fail++;
        }

        // 5) nlp 核心：countTokens / treeMerge
        int n = RagflowNlp.countTokens("Hello world 你好世界 123");
        System.out.println("[nlp countTokens] n=" + n);
        if (n < 4) {
            System.out.println("  FAIL: countTokens too small");
            fail++;
        }

        System.out.println(fail == 0 ? "\nALL PASS" : "\n" + fail + " FAILURES");
    }
}
