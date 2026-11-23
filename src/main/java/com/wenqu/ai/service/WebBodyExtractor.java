package com.wenqu.ai.service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * 网页正文抽取：把抓取到的 HTML 收敛成一段可读正文，供模型直接引用。
 *
 * <p><b>为什么要有这一层</b>：{@link WebPageFetcher} 只负责「拿到字节」并挡住 SSRF／超大页面，
 * 原始 HTML 里导航、脚本、样式、推荐位占掉大半篇幅；整页塞给模型既费 token，又让真正的正文
 * 被噪声淹没。这里在字节到模型之间补一层结构抽取：先去噪，再优先取语义正文容器，最后压成
 * 保留段落换行的纯文本并封顶。
 *
 * <p><b>抽取口径是有界的</b>：靠标签语义与文本量选容器，不引入机器学习模型，也不做跨站规则库。
 * 选不出正文容器时退回 {@code body}（去噪后），宁可多带一点噪声，也不静默丢掉正文。
 * 超长一律<b>显式截断并留提示</b>，不让模型把半篇当完整原文。
 *
 * <p>纯静态、无状态，输入只有 HTML 字节与上限；注入防护归调用方
 * （{@code com.wenqu.ai.util.UntrustedContent}），这里不重复。
 *
 * @author yuanke
 */
public final class WebBodyExtractor {

    /** 默认正文上限（字符）：够读一篇长文，又不让单次工具结果吃掉整轮上下文 */
    public static final int DEFAULT_MAX_CHARS = 12000;

    /** 优先取用的正文容器选择器，按可信度排序；文本太短的不算正文容器 */
    private static final String[] CONTENT_SELECTORS = {
            "article", "main", "[role=main]", "#content", "#article",
            ".article-content", ".post-content", ".entry-content", ".content"
    };

    /** 正文容器至少要这么多字，短了就退回 body（导航条也常被塞进 article 里） */
    private static final int MIN_CONTENT_CHARS = 100;

    /** 噪声节点：脚本/样式/表单/页面框架与明显的辅助区 */
    private static final String NOISE_SELECTOR = String.join(", ",
            "script", "style", "noscript", "iframe", "object", "embed", "svg",
            "form", "button", "template", "select", "textarea",
            "nav", "header", "footer", "aside",
            "[aria-hidden=true]", "[role=navigation]", "[role=complementary]",
            "[role=banner]", "[role=contentinfo]");

    private WebBodyExtractor() {
    }

    /** 抓取结果直接用这个：charset 交给 jsoup 按 BOM / meta 自动探测 */
    public static String extract(byte[] bytes, int maxChars) {
        if (bytes == null || bytes.length == 0) return "";
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(bytes), null, "");
        } catch (java.io.IOException e) {
            return "";
        }
        return extractFrom(doc, maxChars);
    }

    /** 字符串入口（测试与已有 HTML 文本复用）；上限非正数时取默认值 */
    public static String extract(String html, int maxChars) {
        if (html == null || html.isBlank()) return "";
        return extractFrom(Jsoup.parse(html), maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS);
    }

    public static String extract(byte[] bytes) {
        return extract(bytes, DEFAULT_MAX_CHARS);
    }

    private static String extractFrom(Document doc, int maxChars) {
        int cap = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
        stripNoise(doc);

        Element root = pickContentRoot(doc);
        String text = renderText(root);
        if (text.length() < MIN_CONTENT_CHARS && root != doc.body()) {
            text = renderText(doc.body());
        }
        text = normalize(text);
        if (text.length() <= cap) return text;
        return text.substring(0, cap) + "\n…（正文已截断，未抓取完整内容）";
    }

    /** 去噪：删元素与注释。注释里的「指令」是间接注入的常见藏身处。 */
    private static void stripNoise(Document doc) {
        doc.select(NOISE_SELECTOR).remove();
        for (Element el : doc.select("*")) {
            for (Node child : new ArrayList<>(el.childNodes())) {
                if (child instanceof Comment) child.remove();
            }
        }
    }

    /**
     * 选正文容器：语义标签优先；同一选择器命中多个时取文本最长的那个。
     * 都不够长就退回 body——去噪已经做过了，body 仍是最稳的兜底。
     */
    private static Element pickContentRoot(Document doc) {
        for (String selector : CONTENT_SELECTORS) {
            Elements hits = doc.select(selector);
            Element best = null;
            int bestLen = 0;
            for (Element el : hits) {
                int len = el.text().length();
                if (len > bestLen) {
                    best = el;
                    bestLen = len;
                }
            }
            if (best != null && bestLen >= MIN_CONTENT_CHARS) return best;
        }
        return doc.body();
    }

    /** 块级元素前后补换行，文本节点原样拼；不折叠词间空白，交给 normalize 统一处理 */
    private static String renderText(Element root) {
        StringBuilder sb = new StringBuilder();
        render(root, sb);
        return sb.toString();
    }

    private static void render(Node node, StringBuilder sb) {
        if (node instanceof TextNode text) {
            sb.append(text.getWholeText());
            return;
        }
        if (node instanceof Comment) return;
        if (!(node instanceof Element el)) return;
        // jsoup 的 br 不算 block，显式当换行处理，否则「第二行<br>第三行」会粘成一行
        if ("br".equals(el.tagName())) {
            sb.append('\n');
            return;
        }
        if (el.isBlock()) sb.append('\n');
        for (Node child : el.childNodes()) render(child, sb);
        if (el.isBlock()) sb.append('\n');
    }

    /** 每行内空白折叠、空行丢弃，段与段之间保留一个换行 */
    private static String normalize(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String clean = line.replaceAll("\\s+", " ").trim();
            if (!clean.isEmpty()) lines.add(clean);
        }
        return String.join("\n", lines);
    }
}
