package com.wenqu.ai.parser;

import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.model.Chunk;
import com.wenqu.ai.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * 网页解析器（网页 URL 导入知识库；file_type=url，源文件为抓取时的 HTML 快照）
 * <p>
 * - 正文提取：先剥离 script/style/nav/footer 等非正文标签，再按文档序遍历 DOM，
 *   叶子文本块（p/li/pre/td/div 等，含块级子元素的不重复计）产出正文行
 * - 分块：标题层级（h1~h6）开新块并维护章节路径 titlePath（与 docx 结构感知切分同语义）；
 *   无标题的页面按段落聚合，页内超长按行边界硬切（同 TextParser）
 * - 源文件即 HTML 快照：重解析（reparse）读快照离线提取，不重新抓网（可预期、可回放）
 * - 表格：按单元格逐行提取（不做行列聚合，P0 保内容不保表结构）
 *
 * @author yuanke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebParser implements DocumentParser {

    private final AppProperties properties;

    /** 配置读取点：分块粒度必须走这里，知识库 parse_params 的 chunk.maxSize 覆盖经线程局部生效 */
    private final ConfigService configService;

    /** 剥离的非正文标签：脚本/样式/隐藏资源 + 页面骨架（导航/页头页脚/侧栏/表单），保正文信噪比 */
    private static final Set<String> STRIP_TAGS = Set.of(
            "script", "style", "noscript", "template", "iframe", "svg", "canvas", "link", "meta",
            "nav", "header", "footer", "aside", "form", "button", "select", "input", "dialog");

    /** 叶子文本块候选：自身含块级子元素时跳过（交给子元素产出，避免容器文本重复计数） */
    private static final Set<String> LEAF_TAGS = Set.of(
            "p", "li", "dd", "dt", "pre", "blockquote", "figcaption", "td", "th", "div", "section", "article");

    private static final Set<String> BLOCK_CHILD_PROBE = Set.of(
            "p", "li", "ul", "ol", "table", "pre", "blockquote", "div", "section", "article",
            "h1", "h2", "h3", "h4", "h5", "h6", "tr", "td", "dl");

    private int maxChunkSize() {
        return configService.getInt("chunk.maxSize", properties.getChunk().getMaxSize());
    }

    @Override
    public boolean supports(String ext) {
        return "url".equalsIgnoreCase(ext) || "html".equalsIgnoreCase(ext) || "htm".equalsIgnoreCase(ext);
    }

    @Override
    public List<Chunk> parse(java.nio.file.Path file, String fileName, String docId) throws Exception {
        return parse(file, fileName, docId, null);
    }

    @Override
    public List<Chunk> parse(java.nio.file.Path file, String fileName, String docId, ParseProgress progress) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        // charsetName=null：jsoup 自动探测（BOM / meta charset），失败回退 UTF-8
        Document js = Jsoup.parse(new ByteArrayInputStream(bytes), null, "");
        js.outputSettings().prettyPrint(false);
        js.select(String.join(",", STRIP_TAGS)).remove();

        String pageTitle = js.title() == null ? "" : js.title().trim();
        Element body = js.body();
        List<Chunk> chunks = new ArrayList<>();
        if (body == null) {
            log.warn("[WebParser] {} 无 body 元素，解析为空", fileName);
            return chunks;
        }
        report(progress, 55, "正文提取中");

        Deque<String[]> headings = new ArrayDeque<>(); // 每项 {level, text}
        StringBuilder buf = new StringBuilder();

        for (Element el : body.getAllElements()) { // 文档序（先序遍历）
            String tag = el.tagName();
            if (STRIP_TAGS.contains(tag) || el.parents().stream().anyMatch(p -> STRIP_TAGS.contains(p.tagName()))) {
                continue;
            }
            if (tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6') {
                String text = el.text().trim();
                if (text.isEmpty()) continue;
                flush(chunks, buf, currentTitle(headings, pageTitle), titlePath(headings));
                int level = tag.charAt(1) - '0';
                while (!headings.isEmpty() && Integer.parseInt(headings.peekLast()[0]) >= level) headings.removeLast();
                headings.addLast(new String[]{String.valueOf(level), text});
                continue;
            }
            if (!LEAF_TAGS.contains(tag)) continue;
            if (!el.select(String.join(",", BLOCK_CHILD_PROBE)).isEmpty()) continue; // 容器：交给子元素
            String text = "pre".equals(tag) ? el.wholeText() : el.text();
            text = text.replaceAll("[ \\t\\u00a0]+", " ").trim();
            if (text.isEmpty()) continue;
            buf.append(text).append('\n');
            if (buf.length() >= maxChunkSize()) {
                flush(chunks, buf, currentTitle(headings, pageTitle), titlePath(headings));
            }
        }
        flush(chunks, buf, currentTitle(headings, pageTitle), titlePath(headings));
        report(progress, 90, "分块完成 " + chunks.size() + " 块");
        log.info("[WebParser] {} 解析完成: {} chunks", fileName, chunks.size());
        return chunks;
    }

    /** 当前块标题：最近一级标题；整页无标题时用页面 title（保证检索/引用有可读标题） */
    private String currentTitle(Deque<String[]> headings, String pageTitle) {
        if (!headings.isEmpty()) return headings.peekLast()[1];
        return pageTitle;
    }

    /** 章节路径（一级/二级/…；无标题层级为空，与 docx 语义一致） */
    private String titlePath(Deque<String[]> headings) {
        if (headings.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (String[] h : headings) parts.add(h[1]);
        return String.join("/", parts);
    }

    /** 聚合缓冲 → Chunk（超长按行边界硬切，同 TextParser） */
    private void flush(List<Chunk> chunks, StringBuilder buf, String title, String titlePath) {
        String body = buf.toString().trim();
        buf.setLength(0);
        if (body.isEmpty()) return;
        String t = title == null ? "" : title;
        String path = titlePath == null || titlePath.isBlank() ? null : titlePath;
        int maxSize = maxChunkSize();
        if (body.length() <= maxSize) {
            chunks.add(new Chunk(t, body, List.of(), path));
            return;
        }
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
                chunks.add(new Chunk(t + (seq > 1 ? " (" + seq + ")" : ""), part, List.of(), path));
                seq++;
            }
            start = end;
        }
    }

    private void report(ParseProgress progress, int percent, String desc) {
        if (progress != null) progress.onProgress(percent, desc);
    }
}
