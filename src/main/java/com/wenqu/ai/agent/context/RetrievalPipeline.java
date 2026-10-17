package com.wenqu.ai.agent.context;

// [保留] 问渠检索 / 上下文个性化 —— 框架引擎与共享服务仍依赖，勿删。

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.TurnSourceRegistry;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.mapper.KnowledgeMapper;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.DocumentMetaCache;
import com.wenqu.ai.service.HybridRetrievalService;
import com.wenqu.ai.service.ImageFilterService;
import com.wenqu.ai.service.KeywordExtractor;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.model.ModelInfo;
import com.wenqu.ai.service.RerankService;
import com.wenqu.ai.util.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * <b>上下文装配</b>这一族（P6 刀 5b，自 {@code ChatService#runChat} 的三段 inline 收口）：
 * 把「已经检索到的块」装配成模型真正看到的那份参考资料——@ 引用块前置、子智能体命中强制重排后并入、
 * 相邻块合并、相关度分域门与单文档/单库配额、信息增益去冗余、块内片段截取、
 * 图片占位换成全局编号，最后产出 {@code sources}（引用表）与 {@code context}（参考资料正文）。
 *
 * <p><b>为什么这一族可以做成 bean 而计划族不行</b>：它不回头调问答入口（P6 拦路点 2 的那条判据）。
 * 进它的东西全在参数上（命中块、预算、固定部分的 token 数、降级出口），
 * 出它的东西全在一个 {@link Assembled} 里——流水线拿走之后才去回填分支编号、发 retrieved/image 事件、建本轮状态。
 *
 * <p>四条口径别在改动中丢：
 * <ul>
 *   <li><b>分域双门</b>：重排分走 {@code retrieval.minContextScore}、融合分走 {@code retrieval.minFusionScore}，
 *       两域分布不同，一个绝对门必然清空其中一个域；而<b>重排本轮实际执行过时，未重排的候选不许改走融合门入场</b>
 *       （否则"重排判定无相关资料"会被尾部词面噪声推翻）；</li>
 *   <li><b>@ 引用块用户说了算</b>：豁免相关度门、去冗余与单文档/单库配额——否则用户看到的行为就是"@ 没用"；</li>
 *   <li><b>子智能体命中要强制重排</b>（≤6 块走不了 {@code rank()} 的 minHits 窗口，必须 {@code rankForced}），
 *       不补重排它就会以"无重排分"身份借融合门混进来，绕过主链路那道 0.6 门；</li>
 *   <li><b>图片编号是全局连续的一套</b>（与工具命中注册共用 {@code imgIndex}），
 *       预筛把与问题无关的图降级成裸 {@code [图片]}，让模型从一开始就引用不到它，
 *       而不是"先输出后剔除"闪一下再消失。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RetrievalPipeline {

    /** 引用摘要截断长度 */
    private static final int SNIPPET_LEN = 80;
    /** 知识块填充的预算保留下限（token）：固定部分（system+问题）吃满预算时，至少留这点空间给首块资料。
     *  原设置页参数 chat.remainTokenFloor 已退役——装配工程细节易被误解为压缩参数，固定为常量（压缩与其无关） */
    private static final int REMAIN_TOKEN_FLOOR = 800;
    /** 首块资料超预算硬截断时至少保留的字符数（原 chat.truncateFallbackChars，随设置页一并退役为常量） */
    private static final int TRUNCATE_FALLBACK_CHARS = 200;

    private final ConfigService configService;
    private final KeywordExtractor keywordExtractor;
    private final RerankService rerankService;
    private final ImageFilterService imageFilterService;
    private final DocumentMetaCache documentMetaCache;
    private final ModelRegistryService modelRegistryService;
    private final KnowledgeMapper knowledgeMapper;

    public RetrievalPipeline(ConfigService configService,
                             KeywordExtractor keywordExtractor,
                             RerankService rerankService,
                             ImageFilterService imageFilterService,
                             DocumentMetaCache documentMetaCache,
                             ModelRegistryService modelRegistryService,
                             KnowledgeMapper knowledgeMapper) {
        this.configService = configService;
        this.keywordExtractor = keywordExtractor;
        this.rerankService = rerankService;
        this.imageFilterService = imageFilterService;
        this.documentMetaCache = documentMetaCache;
        this.modelRegistryService = modelRegistryService;
        this.knowledgeMapper = knowledgeMapper;
    }

    /**
     * 装配入参。
     *
     * @param onDegrade 降级登记出口（调用方绑本轮的 degradations/degradedCodes）——
     *                  本类不认识本轮状态袋，只把"该喊一声"这件事交回调用方
     */
    public record Request(String retrievalQuery, List<HybridRetrievalService.Hit> hits,
                          List<HybridRetrievalService.Hit> subHits, MentionScope mentionScope,
                          Set<String> scopeDocIds, boolean rerankActive, int budget,
                          String systemText, String userQuestionText,
                          BiConsumer<String, String> onDegrade) {
    }

    /**
     * 装配结果：{@code hitsUsed} 是主链路填充的块数（done 的"参考 N 段资料"按它，
     * 不是 sources.size()——分支回报回填还会往引用表里追加条目）；
     * {@code fixedTokens}/{@code usedTokens} 供容量面板分类。
     */
    public record Assembled(String context, List<Map<String, Object>> sources,
                            Map<Integer, String> imgIndex, Map<Integer, String> imgDescIndex,
                            Map<String, String> fileNames, int hitsUsed,
                            int usedTokens, int fixedTokens, int remainTokens) {
    }

    public Assembled assemble(Request req) {
        String retrievalQuery = req.retrievalQuery();
        List<HybridRetrievalService.Hit> hits = req.hits();
        List<HybridRetrievalService.Hit> subHits = req.subHits();
        MentionScope mentionScope = req.mentionScope();
        Set<String> scopeDocIds = req.scopeDocIds();
        boolean rerankActive = req.rerankActive();
        int budget = req.budget();
        StringBuilder system = new StringBuilder(req.systemText());
        StringBuilder userQuestion = new StringBuilder(req.userQuestionText());
        BiConsumer<String, String> onDegrade = req.onDegrade();

        // 5. 价值驱动填充：预算已在上方解析；减去 system/问题固定部分后，按相关度累积填充知识块。
        // 保留下限是装配工程细节（平台常量，不进设置页）：保证固定部分吃满预算时至少还有一小段空间
        // 给首块资料（截断兜底），与历史压缩无关——压缩由 assembleHistory 按 budget×compressRatio 触发
        int fixedTokens = TokenCounter.estimate(system.toString()) + TokenCounter.estimate(userQuestion.toString());
        int remainTokens = Math.max(REMAIN_TOKEN_FLOOR, budget - fixedTokens);

        Map<Integer, String> imgIndex = new LinkedHashMap<>();
        // 全局图片编号 → 描述（图片相关性校验用：LLM 输出标记后逐图比对）
        Map<Integer, String> imgDescIndex = new HashMap<>();
        StringBuilder context = new StringBuilder();
        List<Map<String, Object>> sources = new ArrayList<>();
        int docNo = 1;
        int usedTokens = 0;
        List<String> retrievalTerms = keywordExtractor.extract(retrievalQuery);
        int maxContextHits = configService.getInt("context.maxContextHits");
        int snippetWindow = configService.getInt("context.snippetWindowChars");
        // 引用扩散 + 结构上下文扩展：命中 A → 带出被引块 B / 引用块 C（默认关）/ 父章节块。
        // 扩散块以 Hit 形态混入同一上下文循环，复用图片占位/截取/预算逻辑；任一环节失败降级为不扩散
        // （subOutcome 在检索阶段就已产出：子代理命中并入 mainHits、要点注入 system）
        List<HybridRetrievalService.Hit> mainHits = new ArrayList<>();
        Set<String> seenKid = new HashSet<>();
        // @ 引用的文档块优先级最高（用户显式指定）：不经检索直接置于最前，并在填充段跳过
        // 相关性门与信息增益去冗余（显式指定 = 强相关，不该被算法判掉——否则用户看到的行为就是"@ 没用"）
        Set<String> mentionKids = new HashSet<>();
        for (Knowledge k : loadMentionChunks(mentionScope, onDegrade)) {
            if (k.getId() == null || !seenKid.add(k.getId())) continue;
            mentionKids.add(k.getId());
            List<String> kImgs = List.of();
            try {
                if (k.getImages() != null && !k.getImages().isBlank()) {
                    kImgs = JSON.parseArray(k.getImages(), String.class);
                }
            } catch (Exception ignored) {
                // 图片解析失败不影响文本入上下文（图片是增强项）
            }
            mainHits.add(new HybridRetrievalService.Hit(k.getId(), k.getDocId(), k.getTitle(), k.getContent(),
                    kImgs, 1.0, k.getChunkIndex(), k.getTitlePath(), null));
        }
        // 子代理命中优先级仅次于检索命中（针对性视角检索，价值高于部分普通召回）；同样受智能体知识库范围约束。
        // 分支检索不重排（只带融合分）：不补重排直接并入，会以「无重排分」身份走融合门（minFusionScore，
        // 默认 0.5）入场，绕过主链路 0.6 重排门（修复前无关块即由此混进上下文与引用来源）——
        // 与主链路同 query 小批量强制重排（≤6 块低于 minHits 窗口，rank() 会整批跳过，须走 rankForced），
        // 让子代理块与主链路块同分域、同门槛；重排不可用时原样并入（与主链路「重排不可用」同语义，走融合门）
        if (!subHits.isEmpty()) {
            
            if (rerankService.debugUnavailableReason() == null) {
                subHits = rerankService.rankForced(new ArrayList<>(subHits), retrievalQuery);
            } else {
                onDegrade.accept("rerankUnavailable",
                        "重排不可用，子代理命中按融合分参与填充");
            }
            for (HybridRetrievalService.Hit h : subHits) {
                if (h.knowledgeId() != null && seenKid.add(h.knowledgeId())
                        && (scopeDocIds == null || (h.docId() != null && scopeDocIds.contains(h.docId())))) {
                    mainHits.add(h);
                }
            }
        }
        for (HybridRetrievalService.Hit h : hits) {
            // 子代理块与检索命中重复时只保留前置位置（避免同一块在上下文出现两次、重复占号）
            if (h.knowledgeId() == null || seenKid.add(h.knowledgeId())) mainHits.add(h);
        }
        // 相邻块合并（进上下文前的组装策略，不改检索结果本身）：同文档 chunk 序号连续的命中块
        // 拼成一块再填充——切块常把一段完整流程切成相邻几片，逐块独立既碎片化又互相挤名额；
        // 合并后一块讲完整个流程，引用编号也更省。@ 引用块不参与（保持"显式指定优先展示"语义）。
        Set<String> adjacentMergedKids = new HashSet<>();
        if (configService.getBoolean("context.adjacentMergeEnabled")) {
            List<HybridRetrievalService.Hit> mergedList = mergeAdjacentHits(mainHits, mentionKids,
                    Math.max(2, configService.getInt("context.adjacentMergeMaxChunks", 3)));
            if (mergedList != mainHits) {
                // 合并块豁免片段截取（见填充循环）：相邻块是连续流程，150 字窗口恰好把完整性切没；
                // 长度由 token 预算兜底（超预算 break / 截断，见预算控制段）
                for (HybridRetrievalService.Hit h : mergedList) {
                    if (!mentionKids.contains(h.knowledgeId())) adjacentMergedKids.add(h.knowledgeId());
                }
                log.debug("[CTX] 相邻块合并: {} 块 → {} 块", mainHits.size(), mergedList.size());
                mainHits = mergedList;
            }
        }
        // 关联块扩散已移除（对齐精简检索链路）：只使用主检索命中，不做引用关系扩散与父章节带出
        Map<String, String> refOrigins = new HashMap<>();
        List<HybridRetrievalService.Hit> allHits = new ArrayList<>(mainHits);
        int maxExtraHits = 0;
        int maxExtraTokens = 0;
        int extraUsed = 0;
        int extraTokensUsed = 0;
        // 批量预取引用文件名（原始命中 + 扩散块都可能有引用展示；冷缓存时一次 selectBatchIds）
        Set<String> refDocIds = allHits.stream().map(HybridRetrievalService.Hit::docId)
                .filter(d -> d != null && !d.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> fileNameMap = documentMetaCache.getFileNames(refDocIds);

        // 信息增益去冗余（MMR 轻量版）：候选块与已选块词元重叠过高 → 视为同一信息的重复切片，跳过。
        // 同一操作被切成多块时，重复块一起进上下文既浪费预算，又让模型在不同表述间自相矛盾。
        // 章节路径相同的块是同一章节相邻切片（高概率讲同一内容），重叠阈值放更低更易剪。
        // 仅影响问答上下文填充，不改检索结果本身（评估/调试面板看到的是原始召回）。
        boolean dedupEnabled = configService.getBoolean("context.dedupEnabled");
        double dedupThreshold = configService.getDouble("context.dedupThreshold", 0.45);
        double dedupPathThreshold = configService.getDouble("context.dedupPathThreshold", 0.28);
        // 最低相关分门（对齐 Dify/Coze 的 Score 阈值标配）——<b>分域双门</b>：排序分有两个来源域，
        // 数值分布完全不同，一个绝对门不可能同时对两者成立：
        //   ① rerank 分（0~1 相关性分，重排开启且命中数在重排区间时）→ 门 = retrieval.minContextScore（默认 0.6）；
        //   ② 加权融合分（vectorWeight×vecNorm + keywordWeight×hitRate；评测基线：期望块中位 0.745、无关块中位 0.469）
        //      → 门 = retrieval.minFusionScore（默认 0.5，拦跨域词面弱相关块；0.25 旧值对无关块拦截率不足 18%）。
        // 不分域的后果（修复前实测）：rerank 服务不可用或关闭时，全部融合分低于 0.6 → 上下文被门清空，
        // 检索"看似无结果"。0 = 关闭对应域的门。调值前先看检索调试面板的实际分数分布（两个域分开看）；
        // 跳过不占 docNo/extra 配额（与去冗余同语义）。
        // 另有单侧约束：重排本轮实际执行过（存在重排分）时，未重排的候选不得借融合门入场（见下方门代码）——
        // 否则"重排判定无相关资料"会被融合序尾部的词面噪声推翻。
        double minRerankGate = configService.getDouble("retrieval.minContextScore", ConfigDefaults.RETRIEVAL_MIN_CONTEXT_SCORE);
        double minFusionGate = configService.getDouble("retrieval.minFusionScore", ConfigDefaults.RETRIEVAL_MIN_FUSION_SCORE);
        // 单文档块数配额（0=不限制）：docId → 已进上下文的块数
        int maxBlocksPerDoc = configService.getInt("context.maxBlocksPerDoc", 3);
        Map<String, Integer> docBlockCount = new HashMap<>();
        // 每库配额（P5 解绑后的配套件）：主检索不再被智能体绑定的库限住，一轮可能同时面对十几个库。
        // 没有这一条，块数最多、词面最泛的那个库会把上下文名额占满——症状从"别的库查不到"变成
        // "别的库查到了但挤不进来"，一样是跨主题答不好，而且更难归因。0=不限制。
        int maxBlocksPerKb = configService.getInt("context.maxBlocksPerKb", 4);
        Map<String, Integer> kbBlockCount = new HashMap<>();
        Map<String, String> hitKbIds = maxBlocksPerKb > 0
                ? documentMetaCache.getKbIds(allHits.stream().map(HybridRetrievalService.Hit::docId)
                .filter(d -> d != null && !d.isBlank()).collect(Collectors.toSet()))
                : Map.of();
        List<Set<String>> selectedTermSets = new ArrayList<>();
        List<String> selectedPaths = new ArrayList<>();
        for (int hi = 0; hi < allHits.size(); hi++) {
            HybridRetrievalService.Hit hit = allHits.get(hi);
            boolean isExtra = hi >= mainHits.size();
            if (isExtra) {
                // 扩散块是可舍弃的增强：数量/token 双上限，超限直接跳过（不做首块硬截断）
                if (extraUsed >= maxExtraHits || extraTokensUsed >= maxExtraTokens) break;
            } else {
                if (docNo > maxContextHits) break;
            }
            // 最低相关分门：排序分（重排分??融合分，与填充顺序同口径）低于阈值的块不进上下文/引用，不占名额
            // @ 引用块（用户显式指定）豁免门与去冗余：用户说它相关，算法没有否决权
            boolean mentioned = hit.knowledgeId() != null && mentionKids.contains(hit.knowledgeId());
            // 单文档配额：同一文档最多进 maxBlocksPerDoc 块（0=不限制）——一个文档讲不清的问题才需要
            // 多来源；单文档刷屏挤掉的正是「其它文档的视角」。@ 引用块豁免（显式指定不受算法约束）。
            if (!mentioned && maxBlocksPerDoc > 0 && hit.docId() != null && !hit.docId().isBlank()
                    && docBlockCount.merge(hit.docId(), 1, Integer::sum) > maxBlocksPerDoc) {
                log.debug("[CTX] 单文档配额跳过: docId={} kid={} title={}",
                        hit.docId(), hit.knowledgeId(), hit.title());
                continue;
            }
            // 单库配额：同一知识库最多进 maxBlocksPerKb 块。@ 引用块豁免（用户显式指定优先于算法配额）；
            // 库取不到（缓存回源失败/文档已删）时放行——配额是防挤占的，不该让一次查库失败吃掉资料。
            if (!mentioned && maxBlocksPerKb > 0 && hit.docId() != null) {
                String hitKbId = hitKbIds.get(hit.docId());
                if (hitKbId != null && kbBlockCount.merge(hitKbId, 1, Integer::sum) > maxBlocksPerKb) {
                    log.debug("[CTX] 单库配额跳过: kbId={} kid={} title={}",
                            hitKbId, hit.knowledgeId(), hit.title());
                    continue;
                }
            }
            // 重排已实际执行 → 相关性结论以重排分为准：未被重排的候选（超出重排区间的主链路尾部）
            // 不得改走更低的融合门入场，否则「重排判定无相关资料」会被尾部词面噪声推翻
            // （修复前无关块即以此侧门混进上下文/引用来源）。重排未执行（关闭/不可用）时维持原融合门语义。
            if (!mentioned && rerankActive && hit.rerankScore() == null) {
                log.debug("[CTX] 重排已执行，未重排块不走融合门跳过: kid={} title={} score={}",
                        hit.knowledgeId(), hit.title(), hit.score());
                continue;
            }
            double rankScore = hit.rerankScore() != null ? hit.rerankScore() : hit.score();
            // 分域取门：rerank 分走 minContextScore，融合分走 minFusionScore（两域分布不同，见上方说明）
            double gate = hit.rerankScore() != null ? minRerankGate : minFusionGate;
            if (!mentioned && gate > 0 && rankScore < gate) {
                log.debug("[CTX] 低于最低相关分跳过: kid={} title={} rankScore={} gate={} (rerank={} score={})",
                        hit.knowledgeId(), hit.title(), rankScore, gate, hit.rerankScore(), hit.score());
                continue;
            }
            // 信息增益去冗余：与已选块语义重叠过高则跳过（不占 docNo/extra 配额，只是不再进上下文）
            if (!mentioned && dedupEnabled && !selectedTermSets.isEmpty()
                    && isRedundantHit(hit, selectedTermSets, selectedPaths, dedupThreshold, dedupPathThreshold)) {
                log.debug("[CTX] 信息增益去冗余跳过: kid={} title={}", hit.knowledgeId(), hit.title());
                continue;
            }
            String text = hit.content();
            List<String> urls = hit.images();

            // 将正文中的 [图片] / [图片：xxx] 替换为全局编号 [图片N]，保留描述。
            // 预筛（生成时即避免错配）：与本次检索问题相关性不足的图不编号（替换为裸 [图片]，LLM 不可引用），
            // 避免"先输出后剔除"导致图闪一下再消失；图片仍留在 sources.images 供引用弹窗查看。
            boolean imgPrefilter = retrievalQuery != null && retrievalQuery.length() >= 2;
            int imgIdxForChunk = 0;
            Matcher matcher = TurnSourceRegistry.IMG_PLACEHOLDER_PATTERN.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                if (imgIdxForChunk < urls.size()) {
                    String raw = matcher.group();
                    String desc = "";
                    int colonIdx = raw.indexOf("：");
                    if (colonIdx >= 0 && raw.length() > colonIdx + 2) {
                        desc = raw.substring(colonIdx + 1, raw.length() - 1).trim();
                    }
                    // 与问题无关的图（描述与检索 query 无共同主题词）→ 不编号
                    if (imgPrefilter && !desc.isEmpty()
                            && !imageFilterService.relevant(retrievalQuery, desc, 1)) {
                        log.debug("[IMG-PRE] 与问题相关性不足，图不编号（{}）: {}", imgIdxForChunk,
                                desc.length() > 24 ? desc.substring(0, 24) + "…" : desc);
                        matcher.appendReplacement(sb, Matcher.quoteReplacement("[图片]"));
                        imgIdxForChunk++;
                        continue;
                    }
                    int globalSeq = imgIndex.size() + 1;
                    imgIndex.put(globalSeq, urls.get(imgIdxForChunk));
                    String replacement = desc.isEmpty()
                            ? "[图片" + globalSeq + "]"
                            : "[图片" + globalSeq + "：" + desc + "]";
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    imgDescIndex.put(globalSeq, desc);
                    imgIdxForChunk++;
                } else {
                    matcher.appendReplacement(sb, matcher.group());
                }
            }
            matcher.appendTail(sb);
            text = sb.toString();

            // 块内片段截取：按检索词元定位命中位置，取 ±窗口（省 token 保精度；未命中则整块）。
            // 相邻合并块豁免：多段拼接的完整流程被窗口截回第一段附近，合并就白做了——
            // 长度由预算控制兜底（超预算 break/截断），信息量不低于"3 块各截 150 字"的旧形态。
            String fullTextForImg = text; // 截取前的完整文本（占位已替换为 [图片N：desc]）
            if (snippetWindow > 0 && !adjacentMergedKids.contains(hit.knowledgeId())) {
                text = extractHitSnippet(fullTextForImg, retrievalTerms, snippetWindow);
                // 片段截取会丢掉窗口外的 [图片N：描述] 占位 → LLM 看不到图、漏配图。
                // 把片段里没有的图片占位（描述截断到 60 字符）追加到片段末尾，保证 LLM 有完整配图依据
                List<String> cutImgs = new ArrayList<>();
                Matcher im = TurnSourceRegistry.IMG_NUMBER_PATTERN.matcher(fullTextForImg);
                while (im.find()) {
                    String g = im.group();
                    if (!text.contains(g)) {
                        cutImgs.add(g.length() > 60 ? g.substring(0, 60) + "]" : g);
                    }
                }
                if (!cutImgs.isEmpty()) {
                    text = text + "\n（本块其他截图：" + String.join(" ", cutImgs) + "）";
                }
            }

            // 章节路径前置：入库只存净正文，路径在检索时拼入上下文（供 LLM 理解内容出处）。
            // 放在片段截取之后，保证路径不被窗口截掉、也不占窗口预算。
            if (hit.titlePath() != null && !hit.titlePath().isBlank()) {
                text = "【上下文】" + hit.titlePath() + "\n\n" + text;
            }

            // 预算控制：累积填充；除第一块外超预算即停止；第一块超预算则截断兜底（保证至少 1 块）
            int tokens = TokenCounter.estimate(text);
            if (docNo > 1 && usedTokens + tokens > remainTokens) {
                log.debug("[CTX] 预算用尽停止填充: used={}/{} token, 已塞 {} 块", usedTokens, remainTokens, docNo - 1);
                break;
            }
            if (usedTokens + tokens > remainTokens) {
                // M5 fail-loud：首块超预算被硬截断（高相关块信息可能丢失），不再只记 debug
                onDegrade.accept("contextTruncated",
                        "资料内容较长，仅截取部分，细节可能缺失");
                text = truncateChars(text, Math.max(TRUNCATE_FALLBACK_CHARS, remainTokens - usedTokens));
                tokens = TokenCounter.estimate(text);
            }

            // 引用来源（ref 与上下文编号对应，回答中 [1] 即可溯源）
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("ref", docNo);
            // 来源出处：MENTION=用户 @ 显式引用（前端标注「你引用的」）；RETRIEVAL=常规检索命中
            src.put("origin", mentioned ? "MENTION" : "RETRIEVAL");
            src.put("knowledgeId", hit.knowledgeId());
            src.put("docId", hit.docId());
            src.put("fileName", fileNameMap.get(hit.docId()));
            src.put("title", hit.title());
            src.put("snippet", snippet(text)); // 用截取后的片段做溯源摘要（更贴近命中内容）
            src.put("images", hit.images()); // 关联文档截图（原始URL，前端经 /proxy 访问）
            // 相关度：score=检索融合分（恒有）；rerankScore=重排模型相关度（重排实际执行才有），
            // 3 位小数仅为展示整洁，数据本身真实
            src.put("score", Math.round(hit.score() * 1000) / 1000.0);
            if (hit.rerankScore() != null) {
                src.put("rerankScore", Math.round(hit.rerankScore() * 1000) / 1000.0);
            }
            // 扩散块来源标注：REF_OUT（被引用）/ REF_IN（引用者）/ PARENT（父章节上下文），前端引用弹窗可区分
            String refOrigin = refOrigins.get(hit.knowledgeId());
            if (refOrigin != null) src.put("origin", refOrigin);
            sources.add(src);

            context.append("[").append(docNo++).append("] ").append(text).append("\n");
            usedTokens += tokens;
            if (isExtra) {
                extraUsed++;
                extraTokensUsed += tokens;
            }
            // 记录已选块特征（词元集合 + 章节路径），供后续候选去冗余判定
            String featureSrc = (hit.title() == null ? "" : hit.title()) + " "
                    + (hit.content() == null ? "" : hit.content());
            if (featureSrc.length() > 400) featureSrc = featureSrc.substring(0, 400);
            Set<String> terms = new HashSet<>(keywordExtractor.extract(featureSrc));
            if (!terms.isEmpty()) {
                selectedTermSets.add(terms);
                if (hit.titlePath() != null && !hit.titlePath().isBlank()) {
                    selectedPaths.add(hit.titlePath());
                }
            }

            // 图片数量多于正文占位时，剩余补在末尾
            for (int i = imgIdxForChunk; i < urls.size(); i++) {
                int globalSeq = imgIndex.size() + 1;
                imgIndex.put(globalSeq, urls.get(i));
                imgDescIndex.put(globalSeq, "");
                context.append("[图片").append(globalSeq).append("]\n");
            }
        }
        log.info("[CTX] 上下文填充 {} 块（含扩散 {}）, 总用 {} / 预算 {} token", docNo - 1, extraUsed, usedTokens + fixedTokens, budget);

        return new Assembled(context.toString(), sources, imgIndex, imgDescIndex, fileNameMap,
                docNo - 1, usedTokens, fixedTokens, remainTokens);

    }

/**
 * @ 引用的检索范围解析（控制器已完成存在性与可见性校验，这里只做形态归类）。
 * <p>{@code docKbIds}：被引文档所属库——@ 文档时检索范围同样收窄到这些库，
 * 否则别的库的块会与「强制前置」的文档块竞争上下文名额。
 */
    public record MentionScope(Set<String> kbIds, Set<String> docIds, Set<String> docKbIds) {
        public static MentionScope of(List<ChatRequest.Mention> mentions) {
            if (mentions == null || mentions.isEmpty()) return null;
            Set<String> kbs = new LinkedHashSet<>();
            Set<String> docs = new LinkedHashSet<>();
            Set<String> docKbs = new LinkedHashSet<>();
            for (ChatRequest.Mention m : mentions) {
                if (m == null || m.getId() == null || m.getId().isBlank()) continue;
                if ("kb".equals(m.getType())) {
                    kbs.add(m.getId());
                } else if ("doc".equals(m.getType())) {
                    docs.add(m.getId());
                    if (m.getKbId() != null && !m.getKbId().isBlank()) docKbs.add(m.getKbId());
                }
            }
            return (kbs.isEmpty() && docs.isEmpty()) ? null : new MentionScope(kbs, docs, docKbs);
        }
    }

    /**
     * 加载 @ 引用文档的内容块（每篇前 N 块，chunkIndex 升序，仅 status=0 的生效块）。
     * 用户显式指定 = 强相关：这些块**不走检索、不参与相关性门与信息增益去冗余**
     * （见填充段的 mentionKids 判定），保证「我 @ 的文档一定被读到」。
     */
    public List<Knowledge> loadMentionChunks(MentionScope scope, BiConsumer<String, String> onDegrade) {
        if (scope == null || scope.docIds().isEmpty()) return List.of();
        int perDoc = Math.max(1, configService.getInt("retrieval.mentionDocChunks", 3));
        try {
            List<Knowledge> all = knowledgeMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Knowledge>()
                            .in(Knowledge::getDocId, scope.docIds())
                            .eq(Knowledge::getStatus, 0)
                            .orderByAsc(Knowledge::getChunkIndex));
            Map<String, Integer> taken = new HashMap<>();
            List<Knowledge> out = new ArrayList<>();
            for (Knowledge k : all) {
                if (taken.merge(k.getDocId(), 1, Integer::sum) <= perDoc) out.add(k);
            }
            if (out.isEmpty()) {
                // 显式 @ 了文档却取不到内容（未解析/解析失败/全部停用）——不能静默变成"什么都没引用"
                log.warn("[FAIL-LOUD] @ 引用的文档没有可用内容块（未解析或全部停用）: docs={}", scope.docIds());
                onDegrade.accept("mentionEmpty",
                        "你引用的文档还没有可用的解析内容，本轮不会把它作为参考资料");
            } else {
                log.info("[MENTION] @ 引用文档块前置 {} 块（{} 篇，每篇上限 {}）",
                        out.size(), scope.docIds().size(), perDoc);
            }
            return out;
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] @ 引用文档块加载失败: {}", e.toString());
            onDegrade.accept("mentionLoadFailed", "引用的文档内容读取失败，本轮未带入");
            return List.of();
        }
    }

    /** @ 引用文档块的文本化（供「不使用知识库」分支直接用：该分支不检索，把被引内容作为参考资料注入问题） */
    public String buildMentionText(List<Knowledge> chunks) {
        if (chunks == null || chunks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Knowledge k : chunks) {
            sb.append("\n[").append(i++).append("] ");
            if (k.getTitlePath() != null && !k.getTitlePath().isBlank()) sb.append(k.getTitlePath());
            if (k.getTitle() != null && !k.getTitle().isBlank()) {
                if (k.getTitlePath() != null && !k.getTitlePath().isBlank()) sb.append(" / ");
                sb.append(k.getTitle());
            }
            sb.append('\n').append(k.getContent() == null ? "" : k.getContent()).append('\n');
        }
        return sb.toString();
    }

    /** 知识库范围约束：scopeDocIds 为空（all）则原样返回；否则仅保留命中块中 docId 在范围内的 */
    public List<HybridRetrievalService.Hit> applyScope(List<HybridRetrievalService.Hit> hits, Set<String> scopeDocIds) {
        if (scopeDocIds == null || hits == null) return hits;
        List<HybridRetrievalService.Hit> scoped = hits.stream()
                .filter(h -> h.docId() != null && scopeDocIds.contains(h.docId()))
                .collect(Collectors.toList());
        if (scoped.size() < hits.size()) {
            log.info("[AGENT] 知识库范围约束：命中 {} → 范围内 {} 块", hits.size(), scoped.size());
        }
        return scoped;
    }

    /** 上下文预算解析结果：窗口与最大输出均来自「用户所选/模型行声明」（模型管理页维护），供触顶提示与 max_tokens 下发区分来源 */
    public record CtxBudget(int window, int maxOutput, int budget, String windowSource) {}

    public CtxBudget resolveContextBudget(String resolvedModel, Integer requestedWindow,
                                          BiConsumer<String, String> onDegrade) {
        // 窗口/最大输出是模型固有属性：模型行声明了就用模型行（模型管理页维护）。
        // 安全系数为平台固定策略（ModelRegistryService.CONTEXT_SAFETY_FACTOR），不再作为设置页配置项。
        ModelInfo mi = modelRegistryService.modelInfoOf(resolvedModel);
        Integer declaredWindow = mi == null ? null : mi.getContextWindow();
        Integer declaredMin = mi == null ? null : mi.getContextWindowMin();
        Integer declaredOutput = mi == null ? null : mi.getMaxOutput();
        boolean windowFromModel = declaredWindow != null && declaredWindow > 0;
        // 最大输出未声明 = 不主动限制（不下发 max_tokens，交由厂商默认）；预算公式按 0 处理
        int maxOutput = declaredOutput != null && declaredOutput > 0 ? declaredOutput : 0;
        if (!windowFromModel) {
            onDegrade.accept("ctxWindowUndeclared",
                    "该模型未声明上下文窗口，检索资料将无法填入；请在「模型供应商」里为 " + resolvedModel + " 声明窗口");
            log.warn("[CTX] 模型 {} 未声明上下文窗口，检索预算托底 1000", resolvedModel);
            return new CtxBudget(0, maxOutput, 1000, "未声明");
        }

        int window = declaredWindow;
        // 窗口来源两态：用户在聊天面板所选档位 > 模型声明。区间可调 = 模型登记了
        // 「最小窗口~窗口」且 min<max（min==max 等价于不可调）；请求值越界收敛进区间，模型不可调时忽略
        String windowSource = "模型声明窗口";
        boolean adjustable = declaredMin != null && declaredMin > 0 && declaredMin < declaredWindow;
        if (requestedWindow != null && requestedWindow > 0 && adjustable) {
            window = Math.max(declaredMin, Math.min(declaredWindow, requestedWindow));
            windowSource = "用户所选窗口";
        }

        int windowBudget = (int) (window * ModelRegistryService.CONTEXT_SAFETY_FACTOR);
        int budget = windowBudget - maxOutput;
        // fail-loud：模型声明的最大输出 ≥ 模型窗口预算时预算算出负数，被下面的 1000 托底——不能静默，
        // 否则用户只看到「预算 1.0k」却不知道是最大输出把检索资料的预算吃光了。修复入口在模型管理。
        if (maxOutput > 0 && budget <= 0) {
            boolean fromUser = "用户所选窗口".equals(windowSource);
            onDegrade.accept("contextBudgetFloored",
                    "该模型声明的最大输出 " + maxOutput + " 不小于" + windowSource + "预算（" + windowSource + " " + window
                            + " × 安全系数 " + ModelRegistryService.CONTEXT_SAFETY_FACTOR + " = " + windowBudget + "），"
                            + "上下文预算被托底为 1000，检索资料将无法填入；请在"
                            + (fromUser ? "聊天页把该模型的上下文窗口档位调大" : "「模型供应商」里调小该模型的最大输出"));
            log.warn("[CTX] 最大输出 {} ≥ 窗口预算 {}（窗口 {}，来源={}），上下文预算托底 1000",
                    maxOutput, windowBudget, window, windowSource);
        }
        return new CtxBudget(window, maxOutput, Math.max(budget, 1000), windowSource);
    }

    /** 生效最大输出 token：以模型行声明为准（模型管理页维护）；未声明返回 0 = 不下发 max_tokens，交由厂商默认（无知识库分支的 max_tokens 下发与触顶判定共用） */
    public int effectiveMaxOutputOf(String resolvedModel) {
        ModelInfo mi = modelRegistryService.modelInfoOf(resolvedModel);
        Integer declared = mi == null ? null : mi.getMaxOutput();
        return declared != null && declared > 0 ? declared : 0;
    }

    /**
     * 块内片段截取：按检索词元在文本中定位第一个命中位置，取 ±window 字符窗口。
     * 未命中（纯向量命中）返回整块。边界加省略号提示，且避免从 [图片N] 标记中间切断。
     */
    private String extractHitSnippet(String text, List<String> terms, int window) {
        if (text == null || text.isEmpty() || terms == null || terms.isEmpty()) {
            return text;
        }
        int bestIdx = -1;
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            int idx = text.indexOf(term);
            if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                bestIdx = idx;
            }
        }
        if (bestIdx < 0) {
            return text;
        }
        int start = Math.max(0, bestIdx - window);
        int end = Math.min(text.length(), bestIdx + window);
        // 窗口边界避免切断 [图片N] 标记：若边界落在 "[图片" 中，向前/向后对齐
        start = adjustBoundaryStart(text, start);
        end = adjustBoundaryEnd(text, end);
        // 窗口边界对齐行边界：截断点落在行中间时（长代码行/长命令），推进到整行末尾，
        // 避免把 pip install 之类的长命令拦腰切断产生 "open..." 半截内容
        if (end < text.length()) {
            int nl = text.indexOf('\n', end);
            if (nl >= 0 && nl - end <= window) {
                end = nl + 1; // 对齐到该行行尾（代价小于一个窗口则接受）
            }
        }
        // 起点对齐到行首：让截断片段从完整行开始，避免从行中间开始
        if (start > 0) {
            int ls = text.lastIndexOf('\n', start);
            if (start - ls <= window) {
                start = ls + 1;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (start > 0) sb.append("…");
        sb.append(text, start, end);
        if (end < text.length()) sb.append("…");
        return sb.toString();
    }

    private int adjustBoundaryStart(String text, int idx) {
        if (idx <= 0) return idx;
        // 若窗口起点落在 [图片N] 标记中间（'[' 前有标记内容），回退到标记开头
        if (text.charAt(idx) == ']' || (text.charAt(idx) == '片' && idx > 0 && text.charAt(idx - 1) == '图')) {
            int open = text.lastIndexOf('[', idx);
            int close = text.indexOf(']', idx);
            if (open >= 0 && close > open && close - open < 20) {
                return open;
            }
        }
        return idx;
    }

    private int adjustBoundaryEnd(String text, int idx) {
        if (idx >= text.length()) return idx;
        // 若窗口终点落在 [图片N] 标记中间，对齐到标记结束
        if (text.charAt(idx) == '[' || text.charAt(idx) == '图') {
            int close = text.indexOf(']', idx);
            if (close >= 0 && close - idx < 20) {
                return close + 1;
            }
        }
        return idx;
    }

    /**
     * 按字符数截断文本，尽量在最近分句符（。！？；\n）处断句
     */
    private String truncateChars(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        String cut = text.substring(0, maxChars);
        int lastBreak = Math.max(cut.lastIndexOf('\n'),
                Math.max(cut.lastIndexOf('。'),
                        Math.max(cut.lastIndexOf('！'), Math.max(cut.lastIndexOf('？'), cut.lastIndexOf('；')))));
        if (lastBreak > maxChars / 2) {
            cut = cut.substring(0, lastBreak + 1);
        }
        return cut + "…";
    }

    private String snippet(String content) {
        if (content == null) return "";
        // 剥离图片标记与【上下文】章节路径前缀（结构切分注入，不展示给用户）
        String s = content.replaceAll("\\[图片[^\\]]*\\]|【上下文】[^\\n]*\\n?", " ").trim();
        return s.length() > SNIPPET_LEN ? s.substring(0, SNIPPET_LEN) + "…" : s;
    }

    /**
     * 相邻块合并（纯函数，供单测）：把同文档 chunk 序号连续（间隔 ≤1）的命中块按序拼成一块。
     * <ul>
     *   <li>豁免块（@ 引用等显式指定）不参与合并、原位保留；</li>
     *   <li>合并块内容按 chunkIndex 升序以空行拼接，分数/重排分取 run 内最高（合并块代表整段
     *       流程的相关性，不该低于任何成员，避免被相关性门误杀）；图片列表按序拼接；</li>
     *   <li>run 长度受 maxChunks 限制——超长 run 截断后，剩余成员从断点继续扫描（可再成新 run），
     *       防止把整章拼成一块吃掉全部上下文预算；</li>
     *   <li>合并块占用 run 首块在原列表中的位置（不打乱原相关性排序），其余成员移除。</li>
     * </ul>
     * 只影响上下文组装，不改检索结果本身（评估/调试面板看到的是原始召回）。
     */
    public static List<HybridRetrievalService.Hit> mergeAdjacentHits(
            List<HybridRetrievalService.Hit> hits, Set<String> exemptKids, int maxChunks) {
        if (hits == null || hits.size() < 2 || maxChunks < 2) return hits;
        List<HybridRetrievalService.Hit> sortable = new ArrayList<>();
        for (HybridRetrievalService.Hit h : hits) {
            boolean exempt = h.knowledgeId() != null && exemptKids.contains(h.knowledgeId());
            if (!exempt && h.docId() != null && !h.docId().isBlank()
                    && h.chunkIndex() != null && h.content() != null && !h.content().isBlank()) {
                sortable.add(h);
            }
        }
        if (sortable.size() < 2) return hits;
        sortable.sort(java.util.Comparator
                .comparing((HybridRetrievalService.Hit h) -> h.docId())
                .thenComparingInt(HybridRetrievalService.Hit::chunkIndex));
        Map<String, HybridRetrievalService.Hit> mergedByKid = new HashMap<>();
        Set<String> consumedKids = new HashSet<>();
        int i = 0;
        while (i < sortable.size()) {
            HybridRetrievalService.Hit first = sortable.get(i);
            int j = i + 1;
            while (j < sortable.size()
                    && sortable.get(j).docId().equals(first.docId())
                    && sortable.get(j).chunkIndex() - sortable.get(j - 1).chunkIndex() == 1
                    && j - i < maxChunks) {
                j++;
            }
            if (j - i >= 2) {
                StringBuilder sb = new StringBuilder();
                double bestScore = 0, bestRerank = -1;
                boolean hasRerank = false;
                List<String> mergedImgs = new ArrayList<>();
                for (int t = i; t < j; t++) {
                    HybridRetrievalService.Hit part = sortable.get(t);
                    if (t > i) sb.append("\n\n");
                    sb.append(part.content());
                    bestScore = Math.max(bestScore, part.score());
                    if (part.rerankScore() != null) {
                        hasRerank = true;
                        bestRerank = Math.max(bestRerank, part.rerankScore());
                    }
                    if (part.images() != null) mergedImgs.addAll(part.images());
                    if (t > i) consumedKids.add(part.knowledgeId());
                }
                mergedByKid.put(first.knowledgeId(), new HybridRetrievalService.Hit(
                        first.knowledgeId(), first.docId(), first.title(), sb.toString(),
                        mergedImgs, bestScore, first.chunkIndex(), first.titlePath(),
                        hasRerank ? bestRerank : null));
            }
            i = j;
        }
        if (mergedByKid.isEmpty()) return hits;
        List<HybridRetrievalService.Hit> rebuilt = new ArrayList<>(hits.size());
        for (HybridRetrievalService.Hit h : hits) {
            if (consumedKids.contains(h.knowledgeId())) continue;
            HybridRetrievalService.Hit m = mergedByKid.get(h.knowledgeId());
            rebuilt.add(m != null ? m : h);
        }
        return rebuilt;
    }

    /**
     * 信息增益去冗余判定：候选块词元与任一已选块的 Jaccard 重叠 ≥ 阈值即视为冗余。
     * 同章节路径（titlePath 互为前缀/相等）的块按更低阈值判定——同一章节的相邻切片几乎总讲同一内容。
     * 只算前 300 字特征控开销；词元提取失败（空）不判冗余（放行，宁漏勿误杀）。
     */
    private boolean isRedundantHit(HybridRetrievalService.Hit hit, List<Set<String>> selectedTermSets,
                                   List<String> selectedPaths, double threshold, double pathThreshold) {
        String title = hit.title() == null ? "" : hit.title();
        String content = hit.content() == null ? "" : hit.content();
        if (content.length() > 300) content = content.substring(0, 300);
        Set<String> terms = new HashSet<>(keywordExtractor.extract(title + " " + content));
        if (terms.isEmpty()) return false;
        boolean samePath = hit.titlePath() != null && !hit.titlePath().isBlank()
                && selectedPaths.stream().anyMatch(p -> p != null && (p.equals(hit.titlePath())
                        || p.startsWith(hit.titlePath()) || hit.titlePath().startsWith(p)));
        double useThreshold = samePath ? pathThreshold : threshold;
        for (Set<String> s : selectedTermSets) {
            if (s.isEmpty()) continue;
            int inter = 0;
            for (String t : terms) if (s.contains(t)) inter++;
            Set<String> union = new HashSet<>(s);
            union.addAll(terms);
            if (union.isEmpty()) continue;
            if ((double) inter / union.size() >= useThreshold) return true;
        }
        return false;
    }
    }
