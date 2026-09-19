package com.wisesoft.wenqu.knowledge.chunking.ragflow;

import com.wisesoft.wenqu.knowledge.chunking.ragflow.RagflowSemanticUtils.EmbeddingFunction;
import com.wisesoft.wenqu.knowledge.chunking.ragflow.RagflowSemanticUtils.TokenCounter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义分块解析器（对应参考实现 {@code ragflow_like/parsers/semantic.py}）。
 * <p>
 * 逐函数移植 {@code chunk_markdown} / {@code _flush_content} / {@code _handle_image_caption}：
 * 用 {@link RagflowMdTokenizer} 产出 token 流，按 token type 分派（标题/表格/段落/围栏/列表/
 * HTML 块/数学块），并借助已移植的 {@link RagflowMdParserUtils} 与 {@link RagflowSemanticUtils}。
 * <p>
 * 能力差异：
 * <ul>
 *   <li>markdown-it + dollarmath_plugin → {@link RagflowMdTokenizer}（纯扫描近似）；</li>
 *   <li>bs4（table_utils）→ {@link RagflowTableUtils}（纯字符串解析）；</li>
 *   <li>embed_fn：参考在未注入时从配置加载 embedding 模型、失败则降级 {@code None}；
 *       本实现不自动加载外部模型，默认传 {@code null} → 语义切分走「按 token 合并」降级路径
 *       （与参考 embed_fn=None 分支一致）。</li>
 * </ul>
 */
public final class SemanticChunkParser {

    private static final String SEPARATOR = "-".repeat(10);
    private static final Pattern IMAGE_PATTERN = Pattern.compile("^!\\[.*?\\]\\(.*?\\)\\s*$");
    private static final Pattern CAPTION_PATTERN = Pattern.compile("^(?:Figure|图|Fig\\.|表|Table)\\s*[\\d\\w\\.]+");
    private static final Pattern IMG_AT_START = Pattern.compile("^(!\\[.*?\\]\\(.*?\\))");
    private static final Pattern IMG_FULL = Pattern.compile("^(!\\[.*?\\]\\(.*?\\))\\s*$");

    private SemanticChunkParser() {
    }

    /**
     * 语义化切分 Markdown 内容（对应 {@code chunk_markdown}）。
     *
     * @param markdownContent 待切分的 Markdown 文本
     * @param parserConfig    切分参数（chunk_token_num 等）
     * @param embedFn         可选嵌入函数；为 {@code null} 时走按 token 合并的降级切分
     */
    public static List<String> chunkMarkdown(
            String markdownContent, Map<String, Object> parserConfig, EmbeddingFunction embedFn) {
        Map<String, Object> config = parserConfig == null ? new LinkedHashMap<>() : parserConfig;
        int maxLength = (int) num(config.get("chunk_token_num"), 512);
        EmbeddingFunction effEmbed = embedFn;
        if (effEmbed == null) {
            // 参考实现此处尝试从配置加载 embedding 模型、失败降级 None；
            // 本实现不自动加载外部模型，直接按 embed_fn=None 降级处理。
            effEmbed = null;
        }

        List<MdToken> tokens = RagflowMdTokenizer.parse(markdownContent == null ? "" : markdownContent);
        String md = markdownContent == null ? "" : markdownContent;
        List<String> originalLines = new ArrayList<>(List.of(md.split("\n", -1)));

        List<String> result = new ArrayList<>();
        List<String> currentContent = new ArrayList<>();
        List<String> titleStack = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            titleStack.add("");
        }

        TokenCounter tokenCounter = RagflowNlp::countTokens;

        int i = 0;
        while (i < tokens.size()) {
            MdToken token = tokens.get(i);
            String type = token.type();
            switch (type) {
                case "heading_open" -> {
                    MdToken inline = (i + 1 < tokens.size()) ? tokens.get(i + 1) : null;
                    String fullTitle = (inline != null && "inline".equals(inline.type()))
                            ? (inline.content() == null ? "" : inline.content()).strip() : "";
                    if (fullTitle.isEmpty()) {
                        i += 3;
                        continue;
                    }
                    flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter, null, false);
                    int level = parseHeadingLevel(token.tag());
                    titleStack.set(level - 1, fullTitle);
                    for (int j = level; j < 6; j++) {
                        titleStack.set(j, "");
                    }
                    i += 3;
                }
                case "table_open" -> {
                    flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter, null, false);
                    RagflowMdParserUtils.TableBlock tb = RagflowMdParserUtils.extractTableBlock(tokens, i, originalLines);
                    currentContent.add(tb.content());
                    flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter, "Table", false);
                    i = tb.closeIndex() + 1;
                }
                case "paragraph_open" -> {
                    HandledResult hr = handleImageCaption(tokens, i, result, currentContent, titleStack,
                            maxLength, effEmbed, tokenCounter);
                    if (hr.handled()) {
                        i = hr.newIndex();
                        continue;
                    }
                    MdToken inline = (i + 1 < tokens.size()) ? tokens.get(i + 1) : null;
                    if (inline != null && "inline".equals(inline.type())) {
                        currentContent.add(inline.content() == null ? "" : inline.content().strip());
                    }
                    i += 3;
                }
                case "fence" -> {
                    currentContent.add("```\n" + (token.content() == null ? "" : token.content()) + "\n```");
                    i += 1;
                }
                case "ordered_list_open" -> {
                    List<String> listContent = new ArrayList<>();
                    int j = i + 1;
                    int counter = 1;
                    while (j < tokens.size() && !"ordered_list_close".equals(tokens.get(j).type())) {
                        if ("list_item_open".equals(tokens.get(j).type())) {
                            int k = j + 1;
                            while (k < tokens.size() && !"list_item_close".equals(tokens.get(k).type())) {
                                if ("paragraph_open".equals(tokens.get(k).type()) && k + 1 < tokens.size()
                                        && "inline".equals(tokens.get(k + 1).type())) {
                                    String content = tokens.get(k + 1).content() == null ? ""
                                            : tokens.get(k + 1).content().strip();
                                    listContent.add(counter + ". " + content);
                                    counter++;
                                }
                                k++;
                            }
                        }
                        j++;
                    }
                    if (!listContent.isEmpty()) {
                        currentContent.addAll(listContent);
                        flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter,
                                token.type(), false);
                    }
                    i = j + 1;
                }
                case "bullet_list_open" -> {
                    List<String> listContent = new ArrayList<>();
                    int j = i + 1;
                    while (j < tokens.size() && !"bullet_list_close".equals(tokens.get(j).type())) {
                        if ("list_item_open".equals(tokens.get(j).type())) {
                            int k = j + 1;
                            while (k < tokens.size() && !"list_item_close".equals(tokens.get(k).type())) {
                                if ("paragraph_open".equals(tokens.get(k).type()) && k + 1 < tokens.size()
                                        && "inline".equals(tokens.get(k + 1).type())) {
                                    String content = tokens.get(k + 1).content() == null ? ""
                                            : tokens.get(k + 1).content().strip();
                                    listContent.add("- " + content);
                                }
                                k++;
                            }
                        }
                        j++;
                    }
                    if (!listContent.isEmpty()) {
                        currentContent.addAll(listContent);
                        flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter,
                                token.type(), false);
                    }
                    i = j + 1;
                }
                case "html_block" -> {
                    flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter, null, false);
                    String content = token.content() == null ? "" : token.content().strip();
                    boolean isConvertedTable = false;
                    if (content.toLowerCase().contains("<table")) {
                        try {
                            List<String> kvList = RagflowTableUtils.htmlTableToKeyValue(content);
                            if (!kvList.isEmpty()) {
                                List<String> prefixed = new ArrayList<>();
                                for (String item : kvList) {
                                    prefixed.add("- " + item);
                                }
                                content = String.join("\n", prefixed);
                                isConvertedTable = true;
                            }
                        } catch (Exception ignored) {
                            // 参考实现仅打 warning 日志；此处吞掉以保持切分不中断
                        }
                    }
                    currentContent.add(content);
                    if (isConvertedTable) {
                        flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter,
                                "Table KV", true);
                    } else {
                        flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter,
                                token.type(), false);
                    }
                    i += 1;
                }
                case "math_block" -> {
                    currentContent.add("$ " + (token.content() == null ? "" : token.content()) + " $");
                    flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter,
                            "Math Block", false);
                    i += 1;
                }
                default -> {
                    // list_item_close / *list_close / list_item_open 等：跳过
                    i += 1;
                }
            }
        }

        flushContent(result, currentContent, titleStack, maxLength, effEmbed, tokenCounter, null, false);

        List<String> chunks = new ArrayList<>();
        List<String> currentChunkParts = new ArrayList<>();
        for (String item : result) {
            if (SEPARATOR.equals(item)) {
                if (!currentChunkParts.isEmpty()) {
                    chunks.add(String.join("\n", currentChunkParts).strip());
                    currentChunkParts.clear();
                }
            } else {
                currentChunkParts.add(item);
            }
        }
        if (!currentChunkParts.isEmpty()) {
            chunks.add(String.join("\n", currentChunkParts).strip());
        }
        return chunks;
    }

    // ==================== _flush_content ====================

    private static void flushContent(
            List<String> result, List<String> currentContent, List<String> titleStack, int maxLength,
            EmbeddingFunction embedFn, TokenCounter tokenCounter, String specialElement, boolean allowSplit) {
        if (currentContent.isEmpty()) {
            return;
        }
        String content = String.join("\n", currentContent).strip();
        if (content.isEmpty()) {
            currentContent.clear();
            return;
        }
        int level = 1;
        for (int i = 5; i >= 0; i--) {
            if (titleStack.get(i) != null && !titleStack.get(i).isEmpty()) {
                level = i + 1;
                break;
            }
        }
        String titlePath = RagflowMdParserUtils.getTitlePath(titleStack);

        if (specialElement != null && !allowSplit) {
            String header = titlePath.isEmpty()
                    ? ("#".repeat(level) + " " + specialElement)
                    : ("#".repeat(level) + " " + titlePath + "|" + specialElement);
            result.add(header);
            result.add(content);
            result.add(SEPARATOR);
        } else {
            if (tokenCounter.count(content) > maxLength) {
                List<String> chunks = RagflowMdParserUtils.splitTextByLengthAndNewline(
                        content, maxLength, embedFn, tokenCounter);
                int idx = 1;
                for (String chunk : chunks) {
                    String baseHeader = titlePath.isEmpty()
                            ? ("#".repeat(level))
                            : ("#".repeat(level) + " " + titlePath);
                    String header;
                    if (specialElement != null) {
                        header = baseHeader + "|" + specialElement + "|Part " + idx;
                    } else {
                        header = baseHeader + "|Part " + idx;
                    }
                    result.add(header);
                    result.add(chunk);
                    result.add(SEPARATOR);
                    idx++;
                }
            } else {
                String baseHeader = titlePath.isEmpty()
                        ? ("#".repeat(level))
                        : ("#".repeat(level) + " " + titlePath);
                String header;
                if (specialElement != null) {
                    header = baseHeader + "|" + specialElement;
                } else {
                    header = baseHeader;
                }
                if (!header.isEmpty()) {
                    result.add(header);
                    result.add("");
                }
                result.add(content);
                result.add(SEPARATOR);
            }
        }
        currentContent.clear();
    }

    // ==================== _handle_image_caption ====================

    private record HandledResult(boolean handled, int newIndex) {
    }

    private static HandledResult handleImageCaption(
            List<MdToken> tokens, int i, List<String> result, List<String> currentContent,
            List<String> titleStack, int maxLength, EmbeddingFunction embedFn, TokenCounter tokenCounter) {
        MdToken token = tokens.get(i);
        if (!"paragraph_open".equals(token.type())) {
            return new HandledResult(false, i);
        }
        MdToken inlineToken = (i + 1 < tokens.size()) ? tokens.get(i + 1) : null;
        if (inlineToken == null || !"inline".equals(inlineToken.type())) {
            return new HandledResult(false, i);
        }
        String content = inlineToken.content() == null ? "" : inlineToken.content().strip();

        // 图片 + 同行题注：![]() 后跟 Figure/图/Table 等
        Matcher imgStart = IMG_AT_START.matcher(content);
        if (imgStart.find()) {
            String rest = content.substring(imgStart.end()).strip();
            if (!rest.isEmpty() && CAPTION_PATTERN.matcher(rest).find()) {
                flushContent(result, currentContent, titleStack, maxLength, embedFn, tokenCounter, null, false);
                currentContent.add(content);
                String captionTitle = rest.split("\n")[0].strip();
                flushContent(result, currentContent, titleStack, maxLength, embedFn, tokenCounter, captionTitle, false);
                return new HandledResult(true, i + 3);
            }
        }

        // 纯图片行，且下一段是题注
        if (IMAGE_PATTERN.matcher(content).matches()) {
            int nextPIdx = i + 3;
            if (nextPIdx + 1 < tokens.size() && "paragraph_open".equals(tokens.get(nextPIdx).type())) {
                MdToken nextInline = tokens.get(nextPIdx + 1);
                if ("inline".equals(nextInline.type())) {
                    String nextContent = nextInline.content() == null ? "" : nextInline.content().strip();
                    if (CAPTION_PATTERN.matcher(nextContent).find()) {
                        flushContent(result, currentContent, titleStack, maxLength, embedFn, tokenCounter, null, false);
                        currentContent.add(content);
                        currentContent.add(nextContent);
                        flushContent(result, currentContent, titleStack, maxLength, embedFn, tokenCounter,
                                nextContent, false);
                        return new HandledResult(true, i + 6);
                    }
                }
            }
        }

        // 当前行是题注，而前一段是图片
        if (!currentContent.isEmpty() && CAPTION_PATTERN.matcher(content).find()) {
            String lastItem = currentContent.get(currentContent.size() - 1).strip();
            if (IMG_FULL.matcher(lastItem).matches()) {
                String imageTag = currentContent.remove(currentContent.size() - 1);
                flushContent(result, currentContent, titleStack, maxLength, embedFn, tokenCounter, null, false);
                currentContent.add(imageTag);
                currentContent.add(content);
                flushContent(result, currentContent, titleStack, maxLength, embedFn, tokenCounter, content, false);
                return new HandledResult(true, i + 3);
            }
        }

        return new HandledResult(false, i);
    }

    private static int parseHeadingLevel(String tag) {
        if (tag != null && tag.length() > 1) {
            try {
                return Integer.parseInt(tag.substring(1));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private static double num(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}
