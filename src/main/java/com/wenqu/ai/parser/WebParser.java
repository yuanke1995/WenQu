package com.wenqu.ai.parser;

import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.model.Chunk;
import com.wenqu.ai.service.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
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

    /**
     * 块级后代探针：含这些标签<b>后代</b>的元素视为容器（文本由后代叶子产出，避免重复计数）。
     * <p>
     * <b>判据必须排除元素自身</b>：jsoup 的 {@code Element.select()} 会把元素自己也纳入结果集，
     * 而本集合里含 p/div/li/td 这些叶子标签本身——用 select 直接判会导致每个叶子元素都把自己
     * 判成容器而整段跳过，最终正文恒为空（表现为「文档未解析出任何内容」）。
     */
    private static final Set<String> BLOCK_CHILD_PROBE = Set.of(
            "p", "li", "ul", "ol", "table", "pre", "blockquote", "div", "section", "article",
            "h1", "h2", "h3", "h4", "h5", "h6", "tr", "td", "dl");

    /**
     * 骨架文本长度上限：短于此且链接密集 → 判为页面骨架（导航条/工具栏），不计入正文。
     * 取 200 字：正文段落普遍远超此值，导航/工具条普遍远小于此值。
     */
    private static final int SKELETON_MAX_TEXT = 200;

    /**
     * 版面碎片长度上限：短于此且不以句读收尾 → 判为碎片（来源行/版权行/提示行），不计入正文。
     * 正常正文段落即便很短也是完整句子（以句号等收尾）。
     */
    private static final int FRAGMENT_MAX_TEXT = 40;

    /**
     * 章节标题文本长度上限：超过此长度视为注释性副标题，不作章节名。
     * 正常章节标题都是短名（十几个字）；几十上百字的是「（根据…决定修正）」「发布日期…」这类附注。
     * <p>
     * 取 50：中文网页里 50 字以上的标题基本都是附注（法条修正说明、版权声明之类），
     * 拿来当块标题会盖掉真正的章节名，还会把每块的标题灌进 embedding 文本、稀释语义。
     */
    private static final int HEADING_MAX_TEXT = 50;

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
                // 超长标题（超过 HEADING_MAX_TEXT 字）是注释性副标题（如法条的「（2003年…修正）」），不是章节名：
                // 拿它当块标题会盖掉真正的章节标题，标题路径也被这条长句灌满
                boolean annotation = text.length() > HEADING_MAX_TEXT;
                if (!annotation) {
                    flush(chunks, buf, currentTitle(headings, pageTitle), titlePath(headings));
                    int level = tag.charAt(1) - '0';
                    while (!headings.isEmpty() && Integer.parseInt(headings.peekLast()[0]) >= level) headings.removeLast();
                    headings.addLast(new String[]{String.valueOf(level), text});
                }
                continue;
            }
            if (!LEAF_TAGS.contains(tag)) continue;
            if (hasBlockDescendant(el)) continue; // 容器：交给子元素产出（判据只看后代，不含自身）
            if (isPageSkeleton(el)) continue;      // 页面骨架（导航条/字号栏/来源行）：不是正文
            String text = extractText(el);
            if (text.isEmpty()) continue;
            // 逐行入缓冲：<br> 换行保留下来，段落边界不丢（否则整页正文粘成一行，切分在句子中间断）
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                buf.append(t).append('\n');
            }
            if (buf.length() >= maxChunkSize()) {
                flush(chunks, buf, currentTitle(headings, pageTitle), titlePath(headings));
            }
        }
        flush(chunks, buf, currentTitle(headings, pageTitle), titlePath(headings));
        report(progress, 90, "分块完成 " + chunks.size() + " 块");
        log.info("[WebParser] {} 解析完成: {} chunks", fileName, chunks.size());
        return chunks;
    }

    /**
     * 是否含块级<b>后代</b>（不含自身）——容器判据。
     * <p>
     * 不能用 {@code el.select(BLOCK_CHILD_PROBE)}：jsoup 的 select 会把元素自身也纳入结果，
     * 于是 p/div/li/td 这些叶子标签会把自己判成容器而被跳过，正文恒为空。
     */
    private static boolean hasBlockDescendant(Element el) {
        for (Element c : el.getAllElements()) {
            if (c == el) continue; // 自身不算
            if (BLOCK_CHILD_PROBE.contains(c.tagName())) return true;
        }
        return false;
    }

    /**
     * 页面骨架判据：短行 + 链接密集 = 导航/工具条，不是正文。
     * <p>
     * 用结构信号而非页面特定的 class 名（各站 class 命名千差万别，写死必然只对一个站生效）：
     * 真正的正文段落是成句的长文本，而面包屑、字号切换、打印/关闭按钮这类骨架是「一行几十字
     * 且其中大半是链接文字」。链接密度高 + 文本短 = 骨架；长段落里偶带一两个链接不算。
     */
    private static boolean isPageSkeleton(Element el) {
        String text = el.text().trim();
        if (text.isEmpty()) return true;
        if (text.length() > SKELETON_MAX_TEXT) return false; // 长文本：即便有链接也是正文
        // 链接文字占全文比例过半 → 导航目录/工具条
        int linkChars = 0;
        for (Element a : el.select("a")) linkChars += a.text().trim().length();
        if (linkChars * 2 >= text.length()) return true;
        // 含交互控件（打印/关闭/翻页/登录等）→ 工具条
        if (!el.select("button, input, select, [onclick]").isEmpty()) return true;
        // 短且不是完整句子（无句读收尾）→ 版面碎片（来源行、版权行、字号提示）
        return text.length() <= FRAGMENT_MAX_TEXT && !text.matches(".*[。！？；.!?;]$");
    }

    /**
     * 取元素正文文本：{@code <br>} 记为换行（网页正文常靠 br 分行/分段，
     * jsoup 的 {@code text()} 会把它折成空格，整段正文粘成一行，切分时从句子中间断开）。
     */
    private static String extractText(Element el) {
        if ("pre".equals(el.tagName())) return el.wholeText(); // pre 保留原始空白
        StringBuilder sb = new StringBuilder();
        collectText(el, sb);
        return sb.toString().replaceAll("[ \\t\\u00a0]+", " ");
    }

    /** 递归收集文本：{@code <br>} 转换行，其余按文本节点拼接 */
    private static void collectText(Node node, StringBuilder sb) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode tn) {
                sb.append(tn.getWholeText());
            } else if (child instanceof Element e) {
                if ("br".equals(e.tagName())) {
                    sb.append('\n');
                } else if (!STRIP_TAGS.contains(e.tagName())) {
                    collectText(e, sb);
                }
            }
        }
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
