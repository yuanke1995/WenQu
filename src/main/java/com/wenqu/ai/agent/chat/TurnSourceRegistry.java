package com.wenqu.ai.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.HybridRetrievalService;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.RerankService;
import com.wenqu.ai.service.KnowledgeRetrievalTool;
import com.wenqu.ai.service.SubAgentOrchestrator;
import com.wenqu.ai.service.websearch.WebSearchResult;
import com.wenqu.ai.service.websearch.WebSearchService;
import com.wenqu.ai.service.websearch.WebSearchTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本轮的<b>引用池</b>：一条编号空间（[1..N]）+ 与之绑定的来源清单、图片编号与注册入口
 * （P6 刀 0 第二步自 {@link TurnState} 的平铺字段与注册方法下沉；刀 5 以此为
 * {@code TurnSourceRegistry} 的收编对象）。
 *
 * <p><b>为什么这一族必须是一个对象而不是一袋字段</b>：库内命中、精确检索工具的结果、联网搜索结果、
 * MCP 结果文本里的链接、被咨询子智能体交回的资料——<b>五路来源共用同一个编号空间</b>。前端角标按 ref
 * 直接索引 sources，另起一套编号必然错位；引用自检也因此只能整体校验这一份清单。既然编号、去重键、
 * 图片编号体系三者必须原子地一起变（{@code synchronized (sources)} 就是这条不变量的实现），
 * 它们的所有者就该只有一个。
 *
 * <p>三条口径别在搬迁中丢：
 * <ul>
 *   <li><b>同块/同源不重复占号</b>：库内按 knowledgeId 判重，联网与 MCP 按归一化 URL 判重
 *       （{@link #webRefByUrl}），于是联网搜过的网页被 MCP 再拿到时是同一个角标；</li>
 *   <li>图片编号是<b>全局一张图一个号</b>，且必须与 {@code imgIndex}/{@code imgDescIndex} 连续，
 *       有新增要重发<b>全量</b> image 事件（前端 onImage 是整体替换）；</li>
 *   <li>只有结果文本<b>显式给出 URL</b> 的 MCP 内容才可溯源——伪造来源比没有来源更糟。</li>
 * </ul>
 *
 * <p>{@link WebSearchTools.WebSearchSink} 由本类实现（原先在 {@link TurnState} 上）：
 * 工具经 {@code ToolContext} 拿到的是"本轮的引用注册器/配额计"，不是整个状态袋。
 * <p>刀 5a 把依赖面也收窄了：池只吃配置/重排/图片签名三件无状态服务加一个投递出口，
 * 不再借 {@code TurnState.Deps} 拿运行时动作——于是那条 Deps 只剩「本轮还要不要继续跑」与
 * 「半程正文怎么落」两件事，那才是它名字该有的意思。
 *
 * @author yuanke
 */
@Slf4j
public final class TurnSourceRegistry implements WebSearchTools.WebSearchSink {

    private final ConfigService configService;
    private final RerankService rerankService;
    private final ImageUrlSigner imageUrlSigner;
    /** 投递与降级登记的出口（刀 5a：池不再借本轮状态袋的 Deps 拿运行时动作，方向仍然是池→运行时单向） */
    private final ChatRunRuntime runtime;
    private final String sessionId;
    /** 本轮问题：MCP 来源的相关度重排按它打分（与主链路同一口径） */
    private final String question;
    /** 本轮创建时的那条通道：新增图片要重发全量 image 事件 */
    private final SseEmitter emitter;
    /** 降级登记的两只容器（与本轮状态袋同一对象引用，不另建） */
    private final List<Map<String, String>> degradations;
    private final Set<String> degradedCodes;

    /** 引用来源清单（ref 编号 = 下标 + 1）：主链路填充后由本轮各工具续编 */
    public final List<Map<String, Object>> sources;
    /** 全局图片编号 → 原始 URL（主链路连续编号，工具来源在同一体系上续编） */
    public final Map<Integer, String> imgIndex;
    /** 全局图片编号 → 描述（入库原文里的 [图片：描述] 按出现顺序对齐） */
    public final Map<Integer, String> imgDescIndex;
    /** 工具来源引用编号 → 该来源图片的全局图片编号清单（重复注册同一块时原样返回，供工具文本重复附清单） */
    public final Map<Integer, List<Integer>> toolRefImages = new HashMap<>();
    /** 已注册的联网/MCP 来源（归一化 URL → 引用编号）：同一 URL 二次命中复用原编号，不重复占号 */
    public final Map<String, Integer> webRefByUrl = new HashMap<>();
    /** 引用文件名映射（docId→fileName）：主链路构建后回填，供工具命中注册来源时取文件名 */
    public volatile Map<String, String> docFileNames;
    /**
     * 文档元数据缓存：主链路的 docFileNames 只覆盖「初始检索命中的文档」，
     * 而精确检索工具可能命中本轮首次出现的文档（映射里没有）→ 用它按需补查，避免引用显示成"未知文档"。
     */
    public volatile com.wenqu.ai.service.DocumentMetaCache docMetaCache;
    /** 本轮已注册的 MCP 引用条数（上限 tool.mcpCiteMaxRefs，防引用面板被单轮刷屏） */
    public final AtomicInteger mcpCiteCount = new AtomicInteger();
    /** 本轮联网搜索已执行次数（配额 webSearch.maxCallsPerTurn；<=0 不限制） */
    public final AtomicInteger webSearchCalls = new AtomicInteger();

    public TurnSourceRegistry(ConfigService configService, RerankService rerankService,
                              ImageUrlSigner imageUrlSigner, ChatRunRuntime runtime,
                              String sessionId, String question, SseEmitter emitter,
                              List<Map<String, Object>> sources, Map<Integer, String> imgIndex,
                              Map<Integer, String> imgDescIndex,
                              List<Map<String, String>> degradations, Set<String> degradedCodes) {
        this.configService = configService;
        this.rerankService = rerankService;
        this.imageUrlSigner = imageUrlSigner;
        this.runtime = runtime;
        this.sessionId = sessionId;
        this.question = question;
        this.emitter = emitter;
        this.sources = sources;
        this.imgIndex = imgIndex;
        this.imgDescIndex = imgDescIndex;
        this.degradations = degradations;
        this.degradedCodes = degradedCodes;
    }

    /** 结果文本里的 http(s) 链接（MCP 结果常是 JSON 转义文本，换行为字面 \n 序列——字符类显式排除反斜杠，防 URL 粘上后续正文） */
    private static final Pattern MCP_URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>\\\\]+");

    // ==================== 库内 / 子智能体来源注册 ====================

    /**
     * 被咨询方交回的资料登记进父轮引用表（P5 智能体咨询）：子轮那份来源转成命中块后走
     * 与精确检索工具同一条注册路径续编号，于是父轮正文里的 [N] 指得到真身、卡片也标得出来路。
     * <p>转不成命中块（缺 knowledgeId、或该块已不可读）返回 null——调用方据此不提这一条，
     * 而不是给模型一个点开是空的角标。
     */
    public Integer registerConsultedSource(Map<String, Object> childSource) {
        HybridRetrievalService.Hit h = SubAgentOrchestrator.hitFromChildSource(childSource);
        if (h == null || h.knowledgeId() == null) return null;
        Object snippet = childSource.get("snippet");
        return registerToolSource(h, snippet == null ? "" : String.valueOf(snippet)).ref();
    }

    /**
     * 工具命中注册：来源进 sources 续编引用编号；来源图片并入全局图片编号体系
     * （imgIndex/imgDescIndex，同一 URL 全局只编号一次，编号与主链路上下文填充连续）。
     * 描述从块原文的 [图片]/[图片：描述] 占位按出现顺序对齐（第 i 个占位 ↔ 第 i 张图，与主链路同口径），
     * 拿不到描述留空（imgFilter rebuild 对空描述放行，不会误剔）。
     * 有新增图片时重发全量 image 事件（前端 onImage 是整体替换，必须发全量已签名清单），
     * 模型后续正文才能引用真实存在的 [图片N]，前端 images[N-1] 才映射得到。
     */
    public KnowledgeRetrievalTool.SourceRegistrar.Registration registerToolSource(HybridRetrievalService.Hit h, String snippet) {
        Integer existing = findExistingSourceRef(h);
        if (existing != null) {
            return new KnowledgeRetrievalTool.SourceRegistrar.Registration(existing,
                    toolRefImages.getOrDefault(existing, List.of()));
        }
        int ref;
        synchronized (sources) {
            existing = findExistingSourceRef(h); // 并发注册同一块的双检
            if (existing != null) {
                return new KnowledgeRetrievalTool.SourceRegistrar.Registration(existing,
                        toolRefImages.getOrDefault(existing, List.of()));
            }
            ref = sources.size() + 1;
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("ref", ref);
            src.put("knowledgeId", h.knowledgeId());
            src.put("docId", h.docId());
            String docName = docFileNames != null ? docFileNames.get(h.docId()) : null;
            // 工具命中的文档若不在主链路映射里（本轮首次出现），直接取是 null →
            // 前端会把来源显示成"未知文档"；这里按需补查一次（DocumentMetaCache 自带缓存，代价很低）
            if (docName == null && h.docId() != null && !h.docId().isBlank() && docMetaCache != null) {
                docName = docMetaCache.getFileNames(Set.of(h.docId())).get(h.docId());
            }
            src.put("fileName", docName);
            src.put("title", h.title());
            src.put("snippet", snippet);
            src.put("images", h.images());
            src.put("origin", "TOOL");
            // 与主链路口径一致：融合分恒有，重排分仅有则透出
            src.put("score", Math.round(h.score() * 1000) / 1000.0);
            if (h.rerankScore() != null) {
                src.put("rerankScore", Math.round(h.rerankScore() * 1000) / 1000.0);
            }
            sources.add(src);
        }
        // 图片编号并入 + SSE 在 sources 锁外（签名与网络写不应持锁）
        List<Integer> imgSeqs = assignToolImages(h);
        toolRefImages.put(ref, imgSeqs);
        return new KnowledgeRetrievalTool.SourceRegistrar.Registration(ref, imgSeqs);
    }

    /** 已注册过同一知识块 → 返回既有引用编号（未注册返回 null） */
    private Integer findExistingSourceRef(HybridRetrievalService.Hit h) {
        if (h.knowledgeId() == null) return null;
        synchronized (sources) {
            for (Map<String, Object> s : sources) {
                if (h.knowledgeId().equals(s.get("knowledgeId"))) {
                    return (Integer) s.get("ref");
                }
            }
        }
        return null;
    }

    /**
     * 来源图片并入全局编号：URL 去重（全局一张图一个编号），编号延续 imgIndex.size()+1；
     * 有新增则重发全量 image 事件（SSE 发送在 sources 锁外——签名与网络写不应持锁）。
     */
    private List<Integer> assignToolImages(HybridRetrievalService.Hit h) {
        List<String> urls = h.images();
        if (urls == null || urls.isEmpty()) return List.of();
        String[] descs = extractImgDescs(h);
        boolean added = false;
        List<Integer> seqs = new ArrayList<>();
        synchronized (sources) {
            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                int seq = -1;
                for (Map.Entry<Integer, String> e : imgIndex.entrySet()) {
                    if (url.equals(e.getValue())) { seq = e.getKey(); break; }
                }
                if (seq < 0) {
                    seq = imgIndex.size() + 1;
                    imgIndex.put(seq, url);
                    imgDescIndex.put(seq, i < descs.length && descs[i] != null ? descs[i] : "");
                    added = true;
                }
                seqs.add(seq);
            }
        }
        if (added) {
            List<String> signed = imgIndex.values().stream().map(imageUrlSigner::signUrl).toList();
            runtime.sendSseEvent(emitter, "image", JSON.toJSONString(signed), sessionId);
        }
        return seqs;
    }

    /** 从块原文按占位出现顺序提取图片描述（第 i 个 [图片：desc] ↔ 第 i 张图；占位多于图片数时多余描述丢弃） */
    private String[] extractImgDescs(HybridRetrievalService.Hit h) {
        List<String> urls = h.images();
        String[] descs = new String[urls == null ? 0 : urls.size()];
        String content = h.content() == null ? "" : h.content();
        Matcher pm = TurnState.IMG_PLACEHOLDER_PATTERN.matcher(content);
        int di = 0;
        while (pm.find() && di < descs.length) {
            String raw = pm.group();
            String d = "";
            int ci = raw.indexOf('：');
            if (ci >= 0 && raw.length() > ci + 2) {
                d = raw.substring(ci + 1, raw.length() - 1).trim();
            }
            descs[di++] = d;
        }
        return descs;
    }

    // ==================== MCP 工具结果：引用注册（origin=MCP） ====================

    /**
     * MCP 工具结果注册引用来源：扫描结果文本里的 http(s) URL 逐个注册（origin=MCP，与
     * TOOL/WEB 共用同一编号空间；归一化 URL 复用 webRefByUrl 去重——联网与 MCP 拿到同一
     * 网页合并同号）。返回在原结果后追加「引用来源清单」的文本，模型据此在正文标注 [N]；
     * 无 URL / 超上限时原样返回（纯文本结果不伪造来源，模型强编的 [N] 由终态越界校验剔除）。
     * 约定：只有结果文本显式给出 URL 的内容才可溯源——这是通用 MCP 工具唯一稳定的来源标识
     * （如 Context7 文档片段的「Source: https://…」行）。
     */
    public String registerMcpCitations(String toolName, String result) {
        if (result == null || result.isBlank()) return result;
        if (!configService.getBoolean("tool.mcpCiteEnabled")) return result;
        // MCP 结果常是 JSON 转义文本（换行为字面 \n 两字符序列）：先还原成真实空白再做
        // URL/标题/摘要提取——否则摘要把 "\n\n\n" 原样带进引用弹窗、标题按行切分失效（title 全空）。
        // 只用于提取；返回给模型的仍是原结果（转义文本可能含代码示例里的合法 \n，还原会改坏样例）。
        int perAnswerCap = configService.getInt("tool.mcpCiteMaxRefs", 10);
        String text = result.replace("\\n", "\n").replace("\\r", "\n").replace("\\t", " ");
        Matcher um = MCP_URL_PATTERN.matcher(text);
        List<String> footnotes = new ArrayList<>();
        List<String[]> fresh = new ArrayList<>();   // [url, title, snippet]
        Set<String> seenRaw = new HashSet<>();
        while (um.find() && fresh.size() < 3) {
            String url = trimUrlTail(um.group());
            if (url.length() < 12 || !seenRaw.add(url)) continue;
            String key = WebSearchService.normalizeUrl(url);
            String title = mcpTitleNear(text, um.start(), um.end(), url);
            String snippet = mcpSnippetAfter(text, um.end());
            Integer existing;
            synchronized (sources) { existing = webRefByUrl.get(key); }
            if (existing != null) {
                footnotes.add("[" + existing + "] " + title + "（" + mcpHostOf(url) + "）"); // 同源（含联网已注册的）复用原编号
                continue;
            }
            fresh.add(new String[]{url, title, snippet});
        }
        if (fresh.isEmpty() && footnotes.isEmpty()) return result;
        // 批量重排：给每条片段一个对用户问题的真实相关度（重排分 0~1，与库内来源同域透出）——
        // 否则 MCP 来源无分数（WEB 的分来自搜索引擎，MCP 没有现成分）。不可用/失败时无分注册
        // （前端不显示分数行），不阻塞引用注册本身。rankForced 返回按分排序，用 content 映射回候选。
        Map<String, Double> scoreBySnippet = new HashMap<>();
        if (!fresh.isEmpty()) {
            List<HybridRetrievalService.Hit> toRank = new ArrayList<>();
            for (String[] f : fresh) {
                toRank.add(new HybridRetrievalService.Hit(null, "", f[1], f[2],
                        List.of(), 0d, null, null, null));
            }
            for (HybridRetrievalService.Hit h : rerankService.rankForced(toRank, question)) {
                if (h.rerankScore() != null) scoreBySnippet.put(h.content(), h.rerankScore());
            }
        }
        for (String[] f : fresh) {
            if (mcpCiteCount.incrementAndGet() > perAnswerCap) {
                mcpCiteCount.decrementAndGet();
                break; // 本轮配额用尽：后续 URL 不再注册
            }
            String key = WebSearchService.normalizeUrl(f[0]);
            Double score = scoreBySnippet.get(f[2]);
            int ref;
            synchronized (sources) {
                Integer existing = webRefByUrl.get(key); // 双检（重排耗时窗口内可能并发注册同源）
                if (existing != null) {
                    ref = existing; // 同一来源（含联网搜索已注册的）复用原编号
                } else {
                    ref = sources.size() + 1;
                    Map<String, Object> src = new LinkedHashMap<>();
                    src.put("ref", ref);
                    src.put("origin", "MCP");
                    src.put("url", f[0]);
                    src.put("siteName", mcpHostOf(f[0]));
                    src.put("title", f[1]);
                    // snippet 即引用自检的证据（citationConsistencyCheck 取 sources[n-1].snippet）
                    src.put("snippet", f[2]);
                    if (score != null) {
                        src.put("rerankScore", Math.round(score * 1000) / 1000.0);
                    }
                    sources.add(src);
                    webRefByUrl.put(key, ref);
                }
            }
            footnotes.add("[" + ref + "] " + f[1] + "（" + mcpHostOf(f[0]) + "）");
        }
        if (footnotes.isEmpty()) return result;
        log.info("[MCP] 工具 {} 注册 {} 条引用来源", toolName, footnotes.size());
        return result + "\n\n（本结果包含以下可溯源来源，回答中引用其内容时，请在对应句子后标注对应编号："
                + String.join("；", footnotes) + "）";
    }

    /** 剔除 URL 匹配尾部粘连的标点/括号/引号（markdown 链接与句尾标点会粘进 \S+ 匹配） */
    private static String trimUrlTail(String raw) {
        String u = raw;
        while (!u.isEmpty() && "\"',<>)\\]}.;:!?".indexOf(u.charAt(u.length() - 1)) >= 0) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * 标题三级兜底：① URL 前方最近的「# …」标题行（Context7 片段的章节标题）；
     * ② URL 后首行正文（片段第一句——并非所有片段的 Source: 行前都有邻近标题）；
     * ③ URL 路径尾段（openai-chat.html → "Openai chat"）。保证每个来源都有名称可显示。
     */
    private static String mcpTitleNear(String text, int start, int end, String url) {
        String before = text.substring(Math.max(0, start - 300), start);
        for (String raw : before.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#")) {
                String t = line.replaceFirst("^#+\\s*", "").trim();
                if (!t.isEmpty()) return capMcpTitle(t);
            }
        }
        for (String raw : text.substring(Math.min(text.length(), end)).split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("---") || line.startsWith("![")) continue;
            return capMcpTitle(line);
        }
        return mcpSlugOf(url);
    }

    /** 标题截 80 字符（回退到词边界），避免截半个词 */
    private static String capMcpTitle(String t) {
        if (t.length() <= 80) return t;
        int cut = t.lastIndexOf(' ', 80);
        return (cut > 40 ? t.substring(0, cut) : t.substring(0, 80)) + "…";
    }

    /** URL 路径尾段转可读名：openai-chat.html → "Openai chat" */
    private static String mcpSlugOf(String url) {
        String u = url.split("\\?")[0];
        int slash = u.lastIndexOf('/');
        String seg = slash >= 0 ? u.substring(slash + 1) : u;
        int dot = seg.lastIndexOf('.');
        if (dot > 0) seg = seg.substring(0, dot);
        seg = seg.replace('-', ' ').replace('_', ' ').trim();
        return seg.isEmpty() ? "" : Character.toUpperCase(seg.charAt(0)) + seg.substring(1);
    }

    /** 摘要取 URL 之后的一段正文（折叠空白），供引用弹窗与引用自检当证据 */
    private static String mcpSnippetAfter(String text, int pos) {
        String rest = text.substring(Math.min(text.length(), pos));
        rest = rest.replaceAll("\\s+", " ").trim();
        return rest.length() > 200 ? rest.substring(0, 200) + "…" : rest;
    }

    /** 主机名（去 www.）；解析失败回落原串前 40 字符 */
    private static String mcpHostOf(String url) {
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            if (host == null) return url.length() > 40 ? url.substring(0, 40) : url;
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return url.length() > 40 ? url.substring(0, 40) : url;
        }
    }

    // ==================== 联网搜索：引用注册 / 配额 / 降级（WebSearchTools.WebSearchSink） ====================

    /**
     * 扣减一次联网搜索配额。工具是全局单例，计数必须落在本轮的引用池上——
     * 否则一次搜索会永久占掉后续问答的额度。
     */
    @Override
    public boolean tryConsumeQuota() {
        int max = configService.getInt("webSearch.maxCallsPerTurn", 2);
        if (max <= 0) return true; // 0=不限制
        return webSearchCalls.incrementAndGet() <= max;
    }

    /** 搜索失败挂一条本轮降级提示（fail-loud：让用户看见"为什么没搜成"，而不是静默无结果） */
    @Override
    public void degrade(String code, String message) {
        runtime.addDegradation(degradations, degradedCodes, code, message);
    }

    /**
     * 联网结果注册为引用来源：与库内来源<b>共用同一编号空间</b>（主链路 [1..N] 续编），
     * 前端角标是按 ref 直接索引 sources 的，另起一套编号必然错位；引用自检也因此能一并校验联网引用。
     * 去重键是归一化 URL（去 utm 等跟踪参数），同一链接不重复占号。
     *
     * @return 分配的引用编号（已注册过则返回原编号）
     */
    @Override
    public int register(WebSearchResult r) {
        String key = WebSearchService.normalizeUrl(r.url());
        synchronized (sources) {
            Integer existing = webRefByUrl.get(key);
            if (existing != null) return existing;
            int ref = sources.size() + 1;
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("ref", ref);
            src.put("origin", "WEB");
            src.put("url", r.url());
            src.put("siteName", r.siteName());
            src.put("title", r.title());
            // snippet 即引用自检的证据（citationConsistencyCheck 取 sources[n-1].snippet）——
            // 联网来源不进 images 图片编号体系（那是库内文档的签名图片，外链图片不适合复用）
            src.put("snippet", r.snippet());
            if (r.publishedAt() != null && !r.publishedAt().isBlank()) {
                src.put("publishedAt", r.publishedAt());
            }
            if (r.score() != null) {
                src.put("score", Math.round(r.score() * 1000) / 1000.0);
            }
            sources.add(src);
            webRefByUrl.put(key, ref);
            return ref;
        }
    }
}
