package com.wenqu.ai.agent.chat;

// [保留] 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件，勿当 legacy 控制流删除。

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.HybridRetrievalService;
import com.wenqu.ai.service.ImageUrlSigner;
import com.wenqu.ai.service.RerankService;
import com.wenqu.ai.service.KnowledgeRetrievalTool;
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
import java.util.stream.Collectors;

/**
 * 本轮的<b>引用池</b>：一条编号空间（[1..N]）+ 与之绑定的来源清单、图片编号与注册入口
 * （P6 刀 0 第二步自手写运行时 {@code TurnState} 的平铺字段与注册方法下沉；刀 5 以此为
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
 * <p>{@link WebSearchTools.WebSearchSink} 由本类实现（原先在手写运行时的状态袋上）：
 * 工具经 {@code ToolContext} 拿到的是"本轮的引用注册器/配额计"，不是整个状态袋。
 * <p>刀 5a 把依赖面也收窄了：池只吃配置/重排/图片签名三件无状态服务加一个投递出口，
 * 不再借本轮状态袋的 Deps 拿运行时动作——方向保持池→运行时单向。
 *
 * @author yuanke
 */
@Slf4j
public final class TurnSourceRegistry implements WebSearchTools.WebSearchSink {

    /** 入库原文里的图片占位：[图片] 或 [图片：描述]（尚未编号；填充上下文时替换成编号形态） */
    public static final Pattern IMG_PLACEHOLDER_PATTERN = Pattern.compile("\\[图片(：.*?)?\\]");

    /**
     * 已编号的图片标记：[图片N] 或 [图片N：描述]。上下文填充与终态越界校验都认这一个语法（单源）——
     * 与引用编号 [N] 是两套空间，共用一个类是因为图片编号本来就在引用池里（imgIndex）。
     */
    public static final Pattern IMG_NUMBER_PATTERN = Pattern.compile("\\[图片\\d+[^\\]]*\\]");

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
    /** 串行化 image 事件（整体替换语义，见 {@link #publishImageEvent()}）；加锁顺序固定为 imageEventLock → sources */
    private final Object imageEventLock = new Object();
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
     * 被咨询方交回的资料<b>整批</b>登记进父轮引用表（P5 智能体咨询）。
     * <p>子轮那份来源转成命中块后走与精确检索工具同一条注册路径续编号，于是父轮正文里的 [N]
     * 指得到真身、卡片也标得出来路。转不成命中块（缺 knowledgeId）的丢弃——调用方据此不提这一条，
     * 而不是给模型一个点开是空的角标。
     * <p><b>为什么必须在这里加门</b>：子轮的门是<b>子轮问题</b>的门（它的检索工具按它自己的提问
     * 过滤过），父轮引用面板的门必须是<b>父轮问题</b>的门。修复前这里零门禁全量搬入——
     * 2026-10-13「我想开发一个考勤系统」会话实测：主模型锚偏后咨询了智报平台手册智能体，
     * 对方 15 步调研史（28 条报表手册块，重排分 0.98 是对它自己问题的分）原样进面板，
     * 45 条引用里 35 条与考勤无关。同批块在主管线按用户问题重排后全部门掉（retrieved refs=0），
     * 两条路标准不一才是病根。
     * <p>门与配额和主管线上下文填充逐条同口径：按父轮问题批量重排（{@code rankForced}），
     * 重排分走 {@code retrieval.minContextScore}、未重排的融合分走 {@code retrieval.minFusionScore}，
     * 重排实际执行过则窗口外尾部不借融合门入场；再叠单文档 {@code context.maxBlocksPerDoc}、
     * 单库 {@code context.maxBlocksPerKb}（面板已有来源也占配额）与单次咨询总量
     * {@code context.maxContextHits}。重排不可用时不臆造分数，按融合门 + 配额兜底（与主管线
     * 「重排不可用」同语义），并留 info 日志说明本轮未做相关度重排。
     *
     * @return 实际登记（或复用既有编号）的条目，供回报文本列给模型；被门/配额滤掉的不出现在内
     */
    public List<com.wenqu.ai.agent.subagent.SubAgentConsultService.SourceSink.Registered>
    registerConsultedSources(List<Map<String, Object>> childSources) {
        if (childSources == null || childSources.isEmpty()) return List.of();
        List<HybridRetrievalService.Hit> candidates = new ArrayList<>();
        for (Map<String, Object> src : childSources) {
            HybridRetrievalService.Hit h = hitFromChildSource(src);
            if (h != null && h.knowledgeId() != null) candidates.add(h);
        }
        if (candidates.isEmpty()) return List.of();

        // 1. 按父轮问题批量重排（与主管线 subHits 并入前同口径）
        boolean rerankActive = false;
        if (question != null && !question.isBlank() && rerankService.debugUnavailableReason() == null) {
            candidates = rerankService.rankForced(candidates, question);
            rerankActive = candidates.stream().anyMatch(h -> h.rerankScore() != null);
        } else {
            log.info("[CONSULT] 重排不可用，咨询来源按融合分门 + 配额登记（{} 条候选）", candidates.size());
        }

        // 2. 分域门 + 配额（数值与主管线上下文填充一致：一个面板一把尺）
        double minRerankGate = configService.getDouble("retrieval.minContextScore", ConfigDefaults.RETRIEVAL_MIN_CONTEXT_SCORE);
        double minFusionGate = configService.getDouble("retrieval.minFusionScore", ConfigDefaults.RETRIEVAL_MIN_FUSION_SCORE);
        int maxBlocksPerDoc = configService.getInt("context.maxBlocksPerDoc", 3);
        int maxBlocksPerKb = configService.getInt("context.maxBlocksPerKb", 4);
        int maxPerConsult = Math.max(1, configService.getInt("context.maxContextHits", 8));
        Map<String, Integer> docCount = new HashMap<>();
        Map<String, Integer> kbCount = new HashMap<>();
        // 面板已有来源也占配额：不清零计数，按现有 sources 播种（否则"检索占满 + 咨询再补"同库仍会刷屏）
        if (maxBlocksPerDoc > 0 || maxBlocksPerKb > 0) {
            synchronized (sources) {
                Set<String> docIds = new HashSet<>();
                for (Map<String, Object> s : sources) {
                    Object d = s.get("docId");
                    if (d != null && !String.valueOf(d).isBlank()) docIds.add(String.valueOf(d));
                }
                if (!docIds.isEmpty() && docMetaCache != null) {
                    for (Map.Entry<String, String> e : docMetaCache.getKbIds(docIds).entrySet()) {
                        kbCount.merge(e.getValue(), 1, Integer::sum);
                    }
                }
                for (Map<String, Object> s : sources) {
                    Object d = s.get("docId");
                    if (d != null && !String.valueOf(d).isBlank()) docCount.merge(String.valueOf(d), 1, Integer::sum);
                }
            }
        }
        // 候选的 kbId 一次批量取（逐条查缓存也是一人一次 IO，批量是一轮一次）
        Map<String, String> candKbIds = docMetaCache != null
                ? docMetaCache.getKbIds(candidates.stream()
                        .map(HybridRetrievalService.Hit::docId)
                        .filter(d -> d != null && !d.isBlank())
                        .collect(Collectors.toSet()))
                : Map.of();

        List<com.wenqu.ai.agent.subagent.SubAgentConsultService.SourceSink.Registered> out = new ArrayList<>();
        int filtered = 0;
        for (HybridRetrievalService.Hit h : candidates) {
            if (out.size() >= maxPerConsult) {
                log.info("[CONSULT] 单次咨询引用达上限 {}，剩余不再登记", maxPerConsult);
                break;
            }
            Double rr = h.rerankScore();
            if (rr == null && rerankActive) {   // 窗口外未重排的尾部：不借融合门入场（同主管线）
                filtered++;
                continue;
            }
            double rankScore = rr != null ? rr : h.score();
            double gate = rr != null ? minRerankGate : minFusionGate;
            if (gate > 0 && rankScore < gate) {
                filtered++;
                continue;
            }
            String docId = h.docId();
            if (maxBlocksPerDoc > 0 && docId != null && !docId.isBlank()
                    && docCount.merge(docId, 1, Integer::sum) > maxBlocksPerDoc) {
                docCount.merge(docId, -1, Integer::sum);
                filtered++;
                continue;
            }
            String kbId = docId == null ? null : candKbIds.get(docId);
            if (maxBlocksPerKb > 0 && kbId != null && kbCount.merge(kbId, 1, Integer::sum) > maxBlocksPerKb) {
                kbCount.merge(kbId, -1, Integer::sum);
                if (docId != null && !docId.isBlank()) docCount.merge(docId, -1, Integer::sum);
                filtered++;
                continue;
            }
            KnowledgeRetrievalTool.SourceRegistrar.Registration reg =
                    registerToolSource(h, h.content() == null ? "" : h.content());
            out.add(new com.wenqu.ai.agent.subagent.SubAgentConsultService.SourceSink.Registered(
                    reg.ref(), h.title() == null ? "资料" : h.title()));
        }
        log.info("[CONSULT] 咨询来源按父轮问题过滤：候选 {} → 登记 {}（滤掉 {}，rerankActive={}，问题=\"{}\"）",
                candidates.size(), out.size(), filtered, rerankActive, question);
        return out;
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
            // 图片清单与来源同锁写入：这里读的也持锁，避免另一路正在给别的来源 put 时读到半更新状态
            synchronized (sources) {
                return new KnowledgeRetrievalTool.SourceRegistrar.Registration(existing,
                        toolRefImages.getOrDefault(existing, List.of()));
            }
        }
        int ref;
        List<Integer> imgSeqs;
        boolean imagesAdded;
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
            // 图片编号与来源编号在同一把锁内落定：assignToolImages 要遍历 imgIndex（LinkedHashMap），
            // 而并行工具调用会同时往 imgIndex 写图 —— 在锁外遍历就是 fail-fast，真机现场
            // search_knowledge 并发 4 路时抛 ConcurrentModificationException，整路工具结果变成错误。
            ImageAssignment imgs = assignToolImages(h);
            imgSeqs = imgs.seqs();
            // 与 sources.add 同一把锁：否则并发注册撞到「来源已占号、图片清单还没写入」的窗口，
            // 后到的那一路命中同一块时会拿到空清单（正文配图丢失而非报错，更难发现）。
            toolRefImages.put(ref, imgSeqs);
            imagesAdded = imgs.added();
        }
        // 签名与 SSE 发送仍在 sources 锁外（网络写不应持锁）；image 事件是「全量替换」，
        // 必须串行且每次重读最新快照，否则先快照后发送的两路可能把较短的旧清单盖回去。
        if (imagesAdded) {
            publishImageEvent();
        }
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
     * 来源图片并入全局编号：URL 去重（全局一张图一个编号），编号延续 imgIndex.size()+1。
     * <p><b>调用方必须持有 {@code sources} 锁</b>：本方法要遍历 imgIndex，而并发工具调用会同时写它。
     * 2026-10-18 真机现场 4 路 {@code search_knowledge} 并发注册，锁外遍历 {@code imgIndex.values()}
     * 抛 {@code ConcurrentModificationException}，该路工具结果整条变成
     * {@code Error: Tool execution failed: ConcurrentModificationException}。
     * <p>SSE 发送不在这里做（签名与网络写不应持锁），由调用方在锁外以 {@link #publishImageEvent()} 发全量清单。
     *
     * @return 本次命中的图片编号，以及是否真的新增了图片（决定要不要重发 image 事件）
     */
    private ImageAssignment assignToolImages(HybridRetrievalService.Hit h) {
        List<String> urls = h.images();
        if (urls == null || urls.isEmpty()) return new ImageAssignment(List.of(), false);
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
        return new ImageAssignment(seqs, added);
    }

    /** 一次图片并入的结果：编号清单 + 是否新增（新增才需要重发全量 image 事件）。 */
    private record ImageAssignment(List<Integer> seqs, boolean added) {}

    /**
     * 重发全量图片清单。image 帧是「整体替换」语义，所以发送必须串行：
     * 两路工具并发新增图片时，先取快照的一路若后发，会把更长的新清单盖回旧长度，前端直接丢图。
     * 串行块内重读最新快照（读也持 {@code sources} 锁），后发的一定不短于先发的。
     */
    private void publishImageEvent() {
        synchronized (imageEventLock) {
            List<String> raw;
            synchronized (sources) {
                raw = List.copyOf(imgIndex.values());
            }
            List<String> signed = raw.stream().map(imageUrlSigner::signUrl).toList();
            runtime.sendSseEvent(emitter, "image", JSON.toJSONString(signed), sessionId);
        }
    }

    /** 从块原文按占位出现顺序提取图片描述（第 i 个 [图片：desc] ↔ 第 i 张图；占位多于图片数时多余描述丢弃） */
    private String[] extractImgDescs(HybridRetrievalService.Hit h) {
        List<String> urls = h.images();
        String[] descs = new String[urls == null ? 0 : urls.size()];
        String content = h.content() == null ? "" : h.content();
        Matcher pm = IMG_PLACEHOLDER_PATTERN.matcher(content);
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
     * 本轮问题（工具侧相关度门的判据）：联网搜索结果按它重排后过门，与库内来源同一把尺。
     * <p>由问答侧传入的是<b>改写后的检索查询</b>（多轮指代消解的结果），与主管线判相关的口径一致；
     * 无知识库分支等没有改写查询的路径退化为原句。
     */
    @Override
    public String question() {
        return question;
    }

    /**
     * 联网结果注册为引用来源：与库内来源<b>共用同一编号空间</b>（主链路 [1..N] 续编），
     * 前端角标是按 ref 直接索引 sources 的，另起一套编号必然错位；引用自检也因此能一并校验联网引用。
     * 去重键是归一化 URL（去 utm 等跟踪参数），同一链接不重复占号。
     *
     * @param rerankScore 工具侧按本轮问题重排出的相关度分（ null = 未重排/重排不可用）
     * @return 分配的引用编号（已注册过则返回原编号）
     */
    @Override
    public int register(WebSearchResult r, Double rerankScore) {
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
            if (rerankScore != null) {
                src.put("rerankScore", Math.round(rerankScore * 1000) / 1000.0);
            }
            sources.add(src);
            webRefByUrl.put(key, ref);
            return ref;
        }
    }

    @Override
    public int register(WebSearchResult r) {
        return register(r, null);
    }

    /**
     * 子轮交回的来源（{@code Map}）→ 父轮可登记的命中块。
     * <p>子轮来源只有 {@code knowledgeId/docId/title/snippet/images/score} 这几项（重排分可选、
     * 有则带上供父轮二次过门与展示）；{@code chunkIndex}/{@code titlePath} 子轮没下发，置空即可——
     * 主链路只有相邻块合并用得到它们。
     * <p>原先挂在 {@code SubAgentOrchestrator} 上，而那是 P5 之后已被 {@code consultAgent} 取代的
     * 编排器（连同它的 StateGraph 一起成了孤儿）。这条映射是纯转换、与编排无关，且此处是唯一调用方，
     * 故随编排器退役一并收归本类，避免为一个静态工具继续保留一个 1142 行的已死服务。
     *
     * @return 命中块；{@code knowledgeId} 缺失时返回 null（没有知识块身份的来源无法进引用编号体系）
     */
    static HybridRetrievalService.Hit hitFromChildSource(Map<String, Object> src) {
        if (src == null) return null;
        String kid = str(src.get("knowledgeId"));
        if (kid == null || kid.isBlank()) return null;
        List<String> images = src.get("images") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        double score = num(src.get("score"), 0.0);
        Double rerank = src.get("rerankScore") == null ? null
                : Double.valueOf(String.valueOf(src.get("rerankScore")));
        return new HybridRetrievalService.Hit(kid, str(src.get("docId")), str(src.get("title")),
                str(src.get("snippet")), images, score, null, null, rerank);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static double num(Object v, double fallback) {
        if (v == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
