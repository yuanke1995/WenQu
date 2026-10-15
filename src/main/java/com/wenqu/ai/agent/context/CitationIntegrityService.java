package com.wenqu.ai.agent.context;

// [保留] 问渠检索 / 上下文个性化 —— 框架引擎与共享服务仍依赖，勿删。

import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.TurnSourceRegistry;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ImageFilterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回答定稿前的<b>引用面校验</b>（P6 刀 5a，自 {@code ChatService} 的流收尾回调里收口）。
 *
 * <p>四道检查按由浅到深排：<b>没标引用</b>（有来源却一个 [N] 都没有）→ <b>编号越界</b>（[N] 落不在
 * 来源表里，LLM 编造）→ <b>图片标记越界</b>（[图片N] 落不在本轮图片清单里）→ <b>语义不支撑</b>
 * （编号合法、句子却与那条 snippet 无关，要一次 LLM 判定）。前三道是纯字符串活儿；第四道才是深度防线。
 *
 * <p>为什么必须与本轮的引用池放在一起看：裁剪之后<b>编号要重编</b>，而正文里的 [N] 与 sources 的
 * 顺序必须<b>同时</b>改——分开在两处改就是"角标点开是另一段资料"。所以这个类的入参是本轮状态，
 * 出参是「定稿正文 + 裁剪后的来源 + 裁剪后的图片清单」三件一起交回调用方。
 *
 * <p>三条口径别丢：
 * <ul>
 *   <li><b>代码段里的 [0]/[1] 是字面量</b>（下标、切片），既不剔除也不重编——无差别处理会改坏代码示例；</li>
 *   <li>来源为空时越界校验<b>照样执行</b>（本轮没有任何可溯源来源，正文不允许残留 [N]；修复前这段被
 *       `!sources.isEmpty()` 跳过，模型给 MCP 资料自编的 [1][2] 全部悬空）；</li>
 *   <li>语义自检<b>失败就保持原回答</b>：它是增强，不是必选防线，不能把已经到手的回答卡死。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class CitationIntegrityService {

    /** 正文里的引用标注 [N] */
    private static final Pattern CITE_PATTERN = Pattern.compile("\\[(\\d+)]");
    /** 代码段（``` 围栏与行内反引号）：其中的 [N] 是字面量，不参与引用判定 */
    private static final Pattern CODE_SEGMENT = Pattern.compile("```.*?(?:```|\\Z)|`[^`\n]*`", Pattern.DOTALL);
    private static final Pattern IMG_NUM_DIGITS_PATTERN = Pattern.compile("\\d+");

    private final ConfigService configService;
    private final ChatClient chatClient;
    private final ImageFilterService imageFilterService;
    private final AppProperties properties;
    private final ChatRunRuntime runtime;

    public CitationIntegrityService(ConfigService configService,
                                    ChatClient chatClient,
                                    ImageFilterService imageFilterService,
                                    AppProperties properties,
                                    ChatRunRuntime runtime) {
        this.configService = configService;
        this.chatClient = chatClient;
        this.imageFilterService = imageFilterService;
        this.properties = properties;
        this.runtime = runtime;
    }

    /** 校验结果：text=定稿正文，sources=裁剪并重编后的来源，images=按 keptImageSeqs 重取后的图片原始 URL 清单 */
    public record Outcome(String text, List<Map<String, Object>> sources, List<String> images) {}

    /**
     * 本轮引用完整性所需的最小状态。
     * <p>刻意不认运行时的状态袋：这套校验（悬空角标剔除、未标注 fail-loud、语义一致性自检）是
     * <b>答案质量防线</b>，不该因为运行时换了就缺席——两条运行时各自按自己的结构搬一份 {@link Ctx} 进来。
     *
     * @param sources 本轮引用池（活引用：语义自检会重编它，调用方按返回的 Outcome 回写）
     * @param model   本轮生效模型的<b>引用串</b> {@code {providerId}/{modelId}}——自检那次调用经
     *                {@code DynamicOpenAiChatModel} 只按引用解析路由，传裸模型名会 fail-loud，
     *                这道防线就整轮静默缺席（只剩一条 WARN）
     */
    public record Ctx(String sessionId, String userId, String question, String model,
                      List<Map<String, Object>> sources, Map<Integer, String> imgIndex,
                      Map<Integer, String> imgDescIndex, List<Map<String, String>> degradations,
                      Set<String> degradedCodes, Map<String, Long> stageMs, long startTime) {
    }

    /**
     * 跑完四道检查并把结果一次交回。降级登记走本轮的 degradations（与调用方同一份列表、同一展示位）。
     *
     * @param answer 生成流定稿的正文（尚未做截断标注）
     */
    public Outcome check(String answer, Ctx ctx) {
        List<Map<String, Object>> sources = ctx.sources();
        List<String> images = new ArrayList<>(ctx.imgIndex().values());

        // 1) 图片相关性兜底：剔除与描述不匹配的 [图片N] 并重建编号（LLM 偶发错配）
        if (properties.getImages().getImageFilter().isEnabled() && !ctx.imgIndex().isEmpty()) {
            ImageFilterService.RebuildResult rr = imageFilterService.rebuild(answer, ctx.imgDescIndex(), ctx.question(),
                    properties.getImages().getImageFilter().getMinHits(),
                    properties.getImages().getImageFilter().getPreContextChars());
            if (!rr.dropped().isEmpty()) {
                // M7 fail-loud：图片被剔除后回答仍引用旧编号，需告知
                degrade(ctx, "imgFilterDropped",
                        "已剔除 " + rr.dropped().size() + " 张与回答内容不匹配的图片引用");
                log.info("[IMG-FILTER] 图片错配剔除 {} 个: {}", rr.dropped().size(), rr.dropped());
                answer = rr.text();
                images = rr.keptSeq().stream().map(ctx.imgIndex()::get).toList();
            }
        }

        // 2) L4 fail-loud：有引用来源但回答未标注任何 [N]（溯源缺失）
        // 注意用 find() 而非 matches()：matches 全串锚定且 . 不跨行，多行回答永远误判为未标注
        if (!sources.isEmpty() && !CITE_PATTERN.matcher(answer).find()) {
            degrade(ctx, "noCitation", "回答未标注引用来源");
        }

        // 3) 引用编号越界：剔除超出来源范围的 [N]（LLM 偶发编造编号，用户点击角标无溯源）。
        //    来源为空（maxRef=0）时同样执行——本轮没有任何可溯源来源，正文不允许残留任何 [N]；
        //    修复前 sources 为空时整段校验被跳过，模型给 MCP 资料自编的 [1][2] 全部悬空（面板无对应条目可点）。
        //    代码段（``` 围栏/行内反引号）跳过：代码里的 [0] 是字面量下标，无差别剔除会改坏代码示例。
        int maxRef = sources.size();
        List<int[]> codeSegs = codeRanges(answer);
        Matcher cm = CITE_PATTERN.matcher(answer);
        StringBuilder cb = new StringBuilder();
        int invalidRefs = 0;
        while (cm.find()) {
            int n = Integer.parseInt(cm.group(1));
            if (!inCodeSegment(codeSegs, cm.start()) && (n < 1 || n > maxRef)) {
                invalidRefs++;
                cm.appendReplacement(cb, "");
            } else {
                cm.appendReplacement(cb, Matcher.quoteReplacement(cm.group()));
            }
        }
        cm.appendTail(cb);
        if (invalidRefs > 0) {
            answer = cb.toString();
            degrade(ctx, "invalidCitation",
                    "已移除 " + invalidRefs + " 处无效的引用标注（编号超出来源范围）");
            log.info("[CITE-CHECK] 剔除越界引用 {} 处 (maxRef={})", invalidRefs, maxRef);
        }

        // 4) 图片标记越界（与引用越界同构）：正文 [图片N] 必须落在本轮真实图片（imgIndex）范围内。
        //    工具模式下模型手里没有编号图片（工具返回文本不带编号、images 为空），却可能按「尽量配图」
        //    规则编造 [图片N]，前端 images[N-1] 映射不到就把字面 [图片N] 原样显示在回答里。剔除 + fail-loud。
        int maxImg = ctx.imgIndex().size();
        Matcher im = TurnSourceRegistry.IMG_NUMBER_PATTERN.matcher(answer);
        StringBuilder imgSb = new StringBuilder();
        int bogusImgs = 0;
        while (im.find()) {
            Matcher nm = IMG_NUM_DIGITS_PATTERN.matcher(im.group());
            int n = nm.find() ? Integer.parseInt(nm.group()) : 0;
            if (n >= 1 && n <= maxImg) {
                im.appendReplacement(imgSb, Matcher.quoteReplacement(im.group()));
            } else {
                bogusImgs++;
                im.appendReplacement(imgSb, "");
            }
        }
        im.appendTail(imgSb);
        if (bogusImgs > 0) {
            answer = imgSb.toString();
            degrade(ctx, "invalidImageRef",
                    "已移除 " + bogusImgs + " 处无效的图片标记（本轮没有对应的图片资料）");
            log.info("[IMG-CHECK] 剔除无效图片标记 {} 处 (maxImg={})", bogusImgs, maxImg);
        }

        // 生成完成（引用语义自检之前的最后一步计时；自检本身另记 citation）
        ctx.stageMs().put("generate", System.currentTimeMillis() - ctx.startTime());

        // 5) 语义一致性自检（深度防线）：编号没越界 ≠ 内容被支撑——LLM 可能引用了一个块，
        //    但对应句子的结论与该块无关（编号正确、语义不符）。把每个 [N] 的"前文句子"与其
        //    来源 snippet 打包给 LLM 判"是否支撑"，不支撑的剔除标记、来源同步裁剪并重编 ref。
        //    一次额外调用，超时/失败/无引用跳过（保持原回答）；空前文（无法界定句子）的引用放行。
        if (configService.getBoolean("chat.citationCheckEnabled") && !sources.isEmpty()) {
            // 用量归属：自检跑在流收尾回调线程上，ThreadLocal 身份已不可见——hold 显式带上
            com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(
                    ctx.userId(), ctx.sessionId(), null, "citecheck"));
            try {
                long citeT0 = System.currentTimeMillis();
                CheckResult cr = consistencyCheck(answer, sources, ctx.question(), ctx.model());
                // 剔除 0 处也打一条：否则"自检开了没、花了多久"完全不可观测（调参时无法确认防线在位）
                log.info("[CITE-CHECK] 自检完成: 剔除 {} 处, 耗时 {} ms", cr.droppedCount(),
                        System.currentTimeMillis() - citeT0);
                if (cr.droppedCount() > 0) {
                    answer = cr.text();
                    sources = cr.sources();
                    degrade(ctx, "citationUnsupported",
                            "已剔除 " + cr.droppedCount() + " 处与引用内容不匹配的引用标注");
                    log.info("[CITE-CHECK] 引用语义一致性剔除 {} 处: {}", cr.droppedCount(), cr.dropped());
                }
            } catch (Exception e) {
                // fail-loud：自检失败保持原回答（校验是增强，不是必选防线）
                log.warn("[FAIL-LOUD] 引用一致性自检失败（保持原回答）: {}", e.getMessage());
            } finally {
                com.wenqu.ai.util.UsageAttr.clear();
            }
            ctx.stageMs().put("citation", System.currentTimeMillis() - ctx.startTime());
        }
        return new Outcome(answer, sources, images);
    }

    /** 引用语义校验结果：text=重建后回答，sources=裁剪后的引用来源（ref 已重编），dropped=被剔除编号 */
    private record CheckResult(String text, List<Map<String, Object>> sources,
                               Set<Integer> dropped, int droppedCount) {}

    private void degrade(Ctx ctx, String code, String msg) {
        runtime.addDegradation(ctx.degradations(), ctx.degradedCodes(), code, msg);
    }

    private CheckResult consistencyCheck(String answer, List<Map<String, Object>> sources,
                                         String question, String resolvedModel) {
        // 1. 收集每个编号首次出现的"前文句子"（重复引用按首次判定；代码段里的 [数字] 是字面量，不参与判定）
        //    代码段范围必须按<b>当前这份正文</b>重算：前面那道越界剔除删过字符，偏移已经变了
        List<int[]> codeSegs = codeRanges(answer);
        Map<Integer, String> sentenceByRef = new LinkedHashMap<>();
        Matcher m = CITE_PATTERN.matcher(answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (inCodeSegment(codeSegs, m.start())) continue;
            if (n < 1 || n > sources.size() || sentenceByRef.containsKey(n)) continue;
            String ctx = imageFilterService.precedingContext(answer, m.start(), 80);
            if (ctx.isBlank()) continue; // 无前文无法界定句子 → 放行
            sentenceByRef.put(n, ctx);
        }
        if (sentenceByRef.isEmpty()) return new CheckResult(answer, sources, Set.of(), 0);

        // 2. 打包给 LLM 批量判定（单次调用，temperature=0 保证稳定；只要求输出编号）
        StringBuilder prompt = new StringBuilder();
        prompt.append("判断回答中的每条引用编号标注的句子，其内容是否被给出的\"证据片段\"直接支撑。\n");
        prompt.append("规则：证据片段必须能支撑句子的核心结论（操作步骤/定义/规则/数值）。\n");
        prompt.append("仅相关但支撑不了结论，也算\"不支撑\"。无法确定时算\"支撑\"。\n");
        prompt.append("只输出\"不支撑\"的编号，用英文逗号分隔；全部支撑则输出\"无\"。不要输出其他内容。\n\n");
        for (Map.Entry<Integer, String> e : sentenceByRef.entrySet()) {
            String snip = String.valueOf(sources.get(e.getKey() - 1).getOrDefault("snippet", ""));
            String sent = e.getValue().length() > 100 ? e.getValue().substring(0, 100) : e.getValue();
            if (snip.length() > 180) snip = snip.substring(0, 180);
            prompt.append("[").append(e.getKey()).append("] 句子：").append(sent).append("\n");
            prompt.append("   证据：").append(snip).append("\n");
        }
        String judge = chatClient.prompt()
                .system("你是回答引用的质检员，只判断引用是否被证据直接支撑，输出最简结果。")
                .user(prompt.toString())
                .options(OpenAiChatOptions.builder()
                        .model(resolvedModel)
                        .temperature(0.0)
                        .maxTokens(64)
                        .build())
                .call()
                .content();

        // 3. 解析被剔除编号（容错：非数字/超范围忽略）
        Set<Integer> dropped = new HashSet<>();
        if (judge != null && !"无".equals(judge.trim()) && !judge.isBlank()) {
            for (String tok : judge.split("[,，、;；\\s]+")) {
                try {
                    int n = Integer.parseInt(tok.trim());
                    if (n >= 1 && n <= sources.size()) dropped.add(n);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (dropped.isEmpty()) return new CheckResult(answer, sources, Set.of(), 0);

        // 4. 重建：剔除标记、保留的重编 1..M、来源同步裁剪
        Map<Integer, Integer> renum = new HashMap<>();
        List<Map<String, Object>> newSources = new ArrayList<>();
        int seq = 0;
        for (int i = 0; i < sources.size(); i++) {
            int ref = i + 1;
            if (dropped.contains(ref)) continue;
            renum.put(ref, ++seq);
            Map<String, Object> ns = new LinkedHashMap<>(sources.get(i));
            ns.put("ref", seq);
            newSources.add(ns);
        }
        Matcher cm = CITE_PATTERN.matcher(answer);
        StringBuffer sb = new StringBuffer();
        while (cm.find()) {
            String rep;
            if (inCodeSegment(codeSegs, cm.start())) {
                rep = cm.group(); // 代码字面量原样保留（既不重编也不剔除）
            } else {
                Integer nn = renum.get(Integer.parseInt(cm.group(1)));
                rep = nn == null ? "" : "[" + nn + "]";
            }
            cm.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        cm.appendTail(sb);
        return new CheckResult(sb.toString(), newSources, dropped, dropped.size());
    }

    /** 代码段范围（``` 围栏与行内反引号）；未闭合的围栏吃到文末（流式截断的正文本来就残缺） */
    private static List<int[]> codeRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        Matcher m = CODE_SEGMENT.matcher(text);
        while (m.find()) ranges.add(new int[]{m.start(), m.end()});
        return ranges;
    }

    private static boolean inCodeSegment(List<int[]> ranges, int pos) {
        for (int[] r : ranges) {
            if (pos >= r[0] && pos < r[1]) return true;
        }
        return false;
    }
}
