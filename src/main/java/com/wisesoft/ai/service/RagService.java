package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.config.AiAppProperties;
import com.wisesoft.ai.model.AiAnswerCache;
import com.wisesoft.ai.util.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 问答服务
 * 混合检索（向量+关键词）→ 重排 → 构建上下文（含 [图片N] 位置标记 + 引用来源）
 * → LLM 流式回答，SSE 输出 token/图片/done(含引用与相关推荐)
 * 流式采用 subscribe 异步订阅，前端断开/超时时 dispose 实现停止生成
 *
 * @author yuanke
 */
@Slf4j
@Service
public class RagService {

    /** 重排触发区间（候选太少/太多无需重排）；rerank.minHits/maxHits 可调 */
    private int rerankMinHits() { return configService.getInt("rerank.minHits", 6); }
    private int rerankMaxHits() { return configService.getInt("rerank.maxHits", 15); }
    /** 主 LLM 流式中断（未输出 token 时）自动重试次数（chat.streamRetryCount，默认 1；0=关闭） */
    private int streamRetryCount() { return configService.getInt("chat.streamRetryCount", 1); }

    /**
     * 所有降级事件（无命中/改写失败/图片剔除/未标注引用/缓存命中等）统一由 chat.showDebugDegradations 开关控制，
     * 默认关闭：回答区不展示任何降级提示，全部只进 [FAIL-LOUD] 日志；排障/调试时开启开关才在回答中展示。
     */
    /** 引用角标 [N]（用于完成阶段校验编号是否超出来源范围，剔除 LLM 编造的无效引用） */
    private static final Pattern CITE_PATTERN = Pattern.compile("\\[(\\d+)]");

    /** fail-loud：按 code 去重添加降级事件（全部 debug 级，由 chat.showDebugDegradations 开关控制，默认不展示） */
    private void addDegradation(List<Map<String, String>> list, Set<String> codes, String code, String msg) {
        if (!codes.add(code)) return;
        if (configService.getBoolean("chat.showDebugDegradations")) {
            list.add(Map.of("code", code, "msg", msg, "level", "debug"));
        }
    }

    /** 重排（候选在区间内且服务可用才执行；不可用/不满足区间 → 保持融合分排序并 fail-loud 标记） */
    private List<HybridRetrievalService.Hit> rerankIfNeeded(List<HybridRetrievalService.Hit> hits, String query,
                                                             List<Map<String, String>> degradations, Set<String> degradedCodes) {
        if (hits.size() > rerankMinHits() && hits.size() <= rerankMaxHits()) {
            String reason = rerankService.debugUnavailableReason();
            if (reason != null) {
                addDegradation(degradations, degradedCodes, "rerankUnavailable",
                        "重排不可用（" + reason + "），按融合分排序");
            } else {
                hits = rerankService.rank(hits, query);
            }
        }
        return hits;
    }
    /** 引用摘要截断长度 */
    private static final int SNIPPET_LEN = 80;

    /**
     * 语义缓存命中直出：跳过检索与 LLM，把历史回答作为完整回答一次性下发（SSE 事件序列与正常路径一致）。
     * 会话历史与问答日志照常落库，保证会话恢复/反馈/看板链路不受影响。
     */
    private void serveFromCache(String sessionId, String question, AiAnswerCache cached, SseEmitter emitter, long startTime) {
        try {
            List<Map<String, Object>> sources = cached.getSources() == null ? List.of()
                    : JSON.parseObject(cached.getSources(), new com.alibaba.fastjson2.TypeReference<List<Map<String, Object>>>() {
                    });
            List<String> images = cached.getImages() == null ? List.of()
                    : JSON.parseArray(cached.getImages(), String.class);
            List<String> related = cached.getRelated() == null ? List.of()
                    : JSON.parseArray(cached.getRelated(), String.class);
            // 图片 URL 动态签名（与正常路径一致，避免签名过期 401）
            if (!images.isEmpty()) {
                List<String> signed = images.stream().map(imageUrlSigner::signUrl).toList();
                sendSseEvent(emitter, "image", JSON.toJSONString(signed), sessionId);
            }
            sendSseEvent(emitter, "token", cached.getAnswer(), sessionId);
            // 会话历史 + 问答日志照常落库（用新建消息 ID 作为本次 messageId 回传——
            // 反馈/删除轮/导出都应以"本条新消息"为准，而非缓存写入时的历史消息；
            // cached.messageId 仅作缓存来源追溯。appendMessage 返回 null=MySQL 降级窗口，
            // 此时无持久消息可关联，回退缓存来源 ID 保证前端反馈不因空值报错）
            sessionService.appendMessage(sessionId, "user", question, null, null);
            String assistantMsgId = sessionService.appendMessage(sessionId, "assistant", cached.getAnswer(), images, cached.getSources());
            List<String> hitDocIds = sources.stream().map(s -> String.valueOf(s.get("docId"))).toList();
            qaLogService.logAsync(sessionId, question, cached.getAnswer(), hitDocIds,
                    !sources.isEmpty(), System.currentTimeMillis() - startTime, question);
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("sources", sources);
            donePayload.put("related", related);
            donePayload.put("messageId", assistantMsgId != null ? assistantMsgId : cached.getMessageId());
            donePayload.put("finalContent", cached.getAnswer());
            donePayload.put("finalImages", images);
            // 缓存命中提示同样收进调试开关（默认不展示；开启后才提示"已复用相似回答"）
            donePayload.put("degradations", configService.getBoolean("chat.showDebugDegradations")
                    ? List.of(Map.of("code", "cacheHit",
                    "msg", "已复用相似问题「" + cached.getQuestion() + "」的回答（知识库未变化时的加速策略）", "level", "debug"))
                    : List.of());
            sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), sessionId);
        } catch (Exception e) {
            log.warn("[ANSWER-CACHE] 缓存回答下发失败: {}", e.getMessage());
            sendSseEvent(emitter, "error", "回答下发失败，请重试", sessionId);
        } finally {
            completeEmitter(emitter);
        }
    }

    private static final Pattern relatedPattern = Pattern.compile("<related>([\\s\\S]*?)</related>");
    /** 全局编号后的图片占位：[图片N] 或 [图片N：描述]（描述内不含 ]；用于收集片段截取丢掉的图） */
    private static final Pattern IMG_NUMBER_PATTERN = Pattern.compile("\\[图片\\d+[^\\]]*\\]");

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final AiAppProperties properties;
    private final ImageUrlSigner imageUrlSigner;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;
    private final QaLogService qaLogService;
    private final UserImageService userImageService;
    private final ConfigService configService;
    private final ImageFilterService imageFilterService;
    private final KeywordExtractor keywordExtractor;
    private final KnowledgeRefService knowledgeRefService;
    private final AnswerCacheService answerCacheService;

    /** M1：查询改写专用线程池（隔离超时任务，避免占用公共池/无限堆积） */
    private final ExecutorService rewriteExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "rewrite");
        t.setDaemon(true);
        return t;
    });

    /** 问答流水线线程池：图片描述 join/改写/检索/深度思考等重活都在独立线程执行，避免占满 Tomcat 请求线程；
     *   chat.pipelineThreads 可调（保存即生效），队列有界，满时快速失败返回"系统繁忙" */
    private ThreadPoolExecutor pipelineExecutor;

    @jakarta.annotation.PostConstruct
    void initPipeline() {
        syncPipelineSize();
    }

    /** 提交前同步流水线线程数（chat.pipelineThreads，DB 配置保存即生效） */
    private void syncPipelineSize() {
        int n = Math.max(2, configService.getInt("chat.pipelineThreads", 8));
        if (pipelineExecutor == null) {
            pipelineExecutor = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(64), r -> {
                Thread t = new Thread(r, "chat-pipeline");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
            log.info("[Chat] 问答流水线线程池创建: {} 线程", n);
        } else if (n != pipelineExecutor.getCorePoolSize()) {
            pipelineExecutor.setCorePoolSize(n);
            pipelineExecutor.setMaximumPoolSize(n);
            log.info("[Chat] 问答流水线线程数调整为 {}", n);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdownRewriteExecutor() {
        rewriteExecutor.shutdownNow();
        if (pipelineExecutor != null) {
            pipelineExecutor.shutdownNow();
        }
    }

    public RagService(ChatClient chatClient,
                      SessionService sessionService,
                      AiAppProperties properties,
                      ImageUrlSigner imageUrlSigner,
                      HybridRetrievalService hybridRetrievalService,
                      RerankService rerankService,
                      DocumentMetaCache documentMetaCache,
                      QaLogService qaLogService,
                      UserImageService userImageService,
                      ConfigService configService,
                      ImageFilterService imageFilterService,
                      KeywordExtractor keywordExtractor,
                      KnowledgeRefService knowledgeRefService,
                      AnswerCacheService answerCacheService) {
        // 基于 DynamicOpenAiChatModel 的 ChatClient：网关地址/API Key/补全路径支持跨厂商热切换（保存即生效）
        this.chatClient = chatClient;
        this.sessionService = sessionService;
        this.properties = properties;
        this.imageUrlSigner = imageUrlSigner;
        this.hybridRetrievalService = hybridRetrievalService;
        this.rerankService = rerankService;
        this.documentMetaCache = documentMetaCache;
        this.qaLogService = qaLogService;
        this.userImageService = userImageService;
        this.configService = configService;
        this.imageFilterService = imageFilterService;
        this.keywordExtractor = keywordExtractor;
        this.knowledgeRefService = knowledgeRefService;
        this.answerCacheService = answerCacheService;
    }

    /**
     * 处理用户问题（可含上传图片），通过 SSE 流式返回。
     * deepThink=true 时先流式输出思考过程（thinking 事件），提取检索计划后多路检索再回答。
     * 整条流水线在独立线程池执行（重活不占 Tomcat 请求线程），控制器返回后 SSE 由流水线线程驱动。
     */
    public void chat(String sessionId, String question, List<String> userImages, boolean deepThink, SseEmitter emitter) {
        // 断开跟踪：登记查表项并绑定生命周期回调清理；发送失败也会打标（见 sendSseEvent），各等待点据此短路后续 LLM/检索开销
        ACTIVE_SSE.put(emitter, new java.util.concurrent.atomic.AtomicBoolean());
        emitter.onCompletion(() -> ACTIVE_SSE.remove(emitter));
        emitter.onTimeout(() -> ACTIVE_SSE.remove(emitter));
        emitter.onError(t -> ACTIVE_SSE.remove(emitter));
        syncPipelineSize();
        try {
            pipelineExecutor.execute(() -> runChat(sessionId, question, userImages, deepThink, emitter));
        } catch (RejectedExecutionException e) {
            // L7 fail-loud：繁忙拒绝时告知当前队列长度（用户可感知拥堵程度）
            int queued = pipelineExecutor == null ? 0 : pipelineExecutor.getQueue().size();
            log.warn("[FAIL-LOUD] 问答流水线繁忙，拒绝请求（排队 {}）: session={}", queued, sessionId);
            sendSseEvent(emitter, "error", "系统繁忙（当前排队 " + queued + " 个请求），请稍后重试", sessionId);
            completeEmitter(emitter);
        }
    }

    /**
     * 问答流水线主体（独立线程执行）：图片处理 → 改写 → 检索/深度思考 → 上下文构建 → LLM 流式输出
     */
    private void runChat(String sessionId, String question, List<String> userImages, boolean deepThink, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        // 深度思考全文（供 done 事件/持久化；lambda 中引用需 effectively final，用数组容器）
        final String[] thinkingHolder = {null};
        // 本轮回答的全部降级/兜底事件（fail-loud：随 done 下发，前端渲染警示条；code 去重，同类只报一次）
        List<Map<String, String>> degradations = new ArrayList<>();
        Set<String> degradedCodes = new HashSet<>();
        try {
            // 0. 进度提示：理解问题阶段（图片描述/改写/缓存查询都有耗时，先给用户反馈）
            sendSseEvent(emitter, "stage", "正在理解问题…", sessionId);
            // 0. 用户上传图片：并行保存+视觉描述（用于上下文与检索召回）
            List<UserImageService.UserImage> userImgs = userImageService.process(userImages);
            String imgDescText = userImgs.isEmpty() ? "" : userImgs.stream()
                    .map(i -> "- " + (i.desc().isBlank() ? "（图片内容无法识别）" : i.desc()))
                    .collect(Collectors.joining("\n"));

            // 0. 相似问题语义缓存：命中直接返回历史答案（跳过改写/检索/LLM；带图片的提问不走缓存）
            if (userImgs.isEmpty()) {
                AiAnswerCache cached = answerCacheService.lookup(question);
                if (cached != null) {
                    serveFromCache(sessionId, question, cached, emitter, startTime);
                    return;
                }
            }

            // 0. 查询改写（支持多轮历史上下文；失败降级为原始问题并上报 fail-loud；M2：关闭时跳过历史查询）
            String retrievalQuery = question;
            if (properties.getQueryRewrite().isEnabled()) {
                List<Map<String, Object>> recentHistory = sessionService.getRecentHistory(sessionId, properties.getQueryRewrite().getHistoryRounds());
                if (recentHistory == null) {
                    // M6 fail-loud：历史读取失败 → 本次无多轮记忆
                    addDegradation(degradations, degradedCodes, "historyFailed", "会话历史读取失败，本次无多轮记忆");
                    recentHistory = List.of();
                }
                // 客户端断开短路：改写是 LLM 调用，断开后不再发起
                if (clientDisconnected(emitter)) {
                    log.info("[SSE] 客户端断开，跳过查询改写: session={}", sessionId);
                    return;
                }
                RewriteResult rr = rewriteQuery(question, recentHistory);
                retrievalQuery = rr.query();
                if (rr.degraded()) {
                    addDegradation(degradations, degradedCodes, "rewriteFailed", "问题改写失败，使用原问题检索");
                }
            }
            // 图片描述参与检索：识别界面时描述含组件名，能显著提升召回
            if (!userImgs.isEmpty()) {
                String descJoin = userImgs.stream().map(UserImageService.UserImage::desc)
                        .filter(d -> !d.isBlank()).collect(Collectors.joining(" "));
                if (!descJoin.isBlank()) {
                    retrievalQuery = question + " " + descJoin;
                }
            }

            // 日志记录用（final 副本，lambda 中引用需要 effectively final）
            final String queryForLog = retrievalQuery;

            // 客户端断开短路（图片视觉处理/改写期间断开已在此打标）：思考与检索都是成本操作，不再发起
            if (clientDisconnected(emitter)) {
                log.info("[SSE] 客户端断开，终止本轮问答（检索前）: session={}", sessionId);
                return;
            }

            // 1. 深度思考（可选）：思考流式 → 提取检索计划 → 多路检索。
            //    失败/超时/提取失败 → 降级为原始 question 单路检索并 fail-loud（thinkingHolder 保留已收集增量，可为空）
            List<HybridRetrievalService.Hit> hits = null;
            HybridRetrievalService.RetrievalDiag retrievalDiag = new HybridRetrievalService.RetrievalDiag();
            if (deepThink && configService.getBoolean("deepReasoning.enabled")) {
                DeepThinkResult dr = runDeepThinking(sessionId, question, imgDescText, emitter);
                thinkingHolder[0] = dr.thinking();
                if (dr.ok()) {
                    String rankQuery;
                    // 多路检索：精化 query + 子问题并行召回合并；开关关闭时单路精化 query
                    sendSseEvent(emitter, "stage", "正在检索资料…", sessionId);
                    if (configService.getBoolean("deepReasoning.multiRetrieval")) {
                        List<String> queries = new ArrayList<>();
                        queries.add(dr.refinedQuery());
                        queries.addAll(dr.subQueries());
                        hits = hybridRetrievalService.searchMulti(queries, retrievalDiag);
                        rankQuery = dr.refinedQuery();
                    } else {
                        retrievalQuery = dr.refinedQuery();
                        hits = hybridRetrievalService.search(retrievalQuery, retrievalDiag);
                        rankQuery = retrievalQuery;
                    }
                    // 与普通路径一致：命中数在重排区间内时重排（多路合并后同样重排，保持两路行为一致）
                    hits = rerankIfNeeded(hits, rankQuery, degradations, degradedCodes);
                    log.info("[DEEP-THINK] 检索计划: refined={}, subQueries={}, hits={}",
                            dr.refinedQuery(), dr.subQueries(), hits.size());
                } else {
                    // M2 fail-loud：深度思考降级（思考阶段失败/超时/未提取到检索计划）——用户可读措辞，技术原因留日志
                    addDegradation(degradations, degradedCodes, "deepThinkDegraded", "深度思考未完成，已转为普通回答");
                }
            }
            // 深度思考期间断开（thinking 发送失败即中止思考流并打标）：降级路径同样不再继续检索
            if (clientDisconnected(emitter)) {
                log.info("[SSE] 客户端断开，终止本轮问答（思考后）: session={}", sessionId);
                return;
            }
            // 降级/未开启深度思考：走普通单路检索
            if (hits == null) {
                sendSseEvent(emitter, "stage", "正在检索资料…", sessionId);
                hits = hybridRetrievalService.search(retrievalQuery, retrievalDiag);
                hits = rerankIfNeeded(hits, retrievalQuery, degradations, degradedCodes);
            }
            // M4/M13/L1 fail-loud：检索单路失败/降级透传（keywordFallback 仅调试展示，不扰用户）
            if (retrievalDiag.isVectorFailed()) {
                addDegradation(degradations, degradedCodes, "vectorFailed", "向量检索失败，本次仅关键词召回");
            }
            if (retrievalDiag.isKeywordFailed()) {
                addDegradation(degradations, degradedCodes, "keywordDegraded", "关键词引擎不可用，已降级 MySQL 检索");
            }
            if (retrievalDiag.isKeywordBusy()) {
                addDegradation(degradations, degradedCodes, "keywordBusy", "关键词检索繁忙/超时，本次仅向量召回");
            }
            if (retrievalDiag.isMultiTimeout()) {
                addDegradation(degradations, degradedCodes, "multiTimeout", "多路检索超时，仅用已完成结果");
            }
            log.info("[RAG] 检索命中 {} 块, query={}", hits.size(), retrievalQuery);

            // 2. System 提示：角色段（DB 可编辑，保存即生效；空则用代码默认值兜底）+ 规则段（代码固定，与解析器耦合）
            //    + 对话历史（单条截断 + token 上限 + 图片标记剥离）
            String rolePart = configService.get("chat.systemPrompt");
            if (rolePart == null || rolePart.isBlank()) {
                rolePart = properties.getSystemPrompt();
            }
            StringBuilder system = new StringBuilder(rolePart)
                    .append("\n\n【规则】\n")
                    .append("参考资料中以 [1][2] 编号标注来源，回答引用了某个资料时，在对应句末用 [N] 标注（如\"评分组件支持自定义总分[1]\"）。")
                    .append("\n参考资料中图片标记格式为 [图片N：图片内容描述]（冒号后是这张截图的实际内容）。")
                    .append("\n回答操作步骤、界面操作、配置方法类问题时，应尽量在对应步骤处配图：从参考资料中选择描述与该步骤界面/弹窗/页面匹配的 [图片N]，"
                            + "例如回答\"评分组件\"时，只能选择描述里含有\"评分/五星/星级\"等词的 [图片N]，"
                            + "绝不能使用描述与回答内容无关的编号（如描述是下拉列表、日期、JSON 数据的图片），也不要编造不存在的编号。")
                    .append("\n选定后把 [图片N] 输出在对应步骤的准确位置（例如\"点击左上角的'+'新建分类[图片1]\"），"
                            + "不要把图片标记堆到回答结尾。")
                    .append("\n注意：插入 [图片N] 时，标记前后不要紧贴任何标点，[图片N] 应独立成行；"
                            + "若句末需要标点，放在标记之前的文字末尾，如\"布局组件[图片1]\"，不要写成\"布局组件[图片1]、\"。")
                    .append("\n参考资料中包含表格时（以 | 分隔的 Markdown 表格），若回答涉及表格内容，请用同样的 Markdown 表格格式呈现，不要改写成一长串用竖线连起来的文字。")
                    .append("\n回答末尾用 <related>问题1|问题2|问题3</related> 输出 3 个用户可能追问的相关问题（用 | 分隔），如无合适问题可不输出。");
            List<Map<String, Object>> recentHistory = sessionService.getRecentHistory(sessionId, configService.getInt("chat.historyRounds", 5));
            if (recentHistory == null) {
                // M6 fail-loud：历史读取失败 → 本次对话无历史注入
                addDegradation(degradations, degradedCodes, "historyFailed", "会话历史读取失败，本次无多轮记忆");
                recentHistory = List.of();
            }
            String historyText = buildHistoryText(recentHistory);
            if (!historyText.isEmpty()) {
                system.append("\n\n对话历史：\n").append(historyText);
            }

            // 用户上传图片描述拼入问题（主 LLM 结合图片内容回答）
            StringBuilder userQuestion = new StringBuilder(question);
            if (!imgDescText.isBlank()) {
                userQuestion.append("\n\n用户上传了图片，图片内容描述如下（请结合图片内容回答问题）：\n").append(imgDescText);
            }

            // 3. 价值驱动填充：预算 = min(窗口×系数−输出, 成本上限)；减去 system/问题固定部分后，按相关度累积填充知识块
            int budget = resolveContextBudget();
            int fixedTokens = TokenCounter.estimate(system.toString()) + TokenCounter.estimate(userQuestion.toString());
            int remainTokens = Math.max(configService.getInt("chat.remainTokenFloor", 800), budget - fixedTokens);

            Map<Integer, String> imgIndex = new LinkedHashMap<>();
            // 全局图片编号 → 描述（图片相关性校验用：LLM 输出标记后逐图比对）
            Map<Integer, String> imgDescIndex = new HashMap<>();
            Pattern imgPattern = Pattern.compile("\\[图片(：.*?)?\\]");
            StringBuilder context = new StringBuilder();
            List<Map<String, Object>> sources = new ArrayList<>();
            int docNo = 1;
            int usedTokens = 0;
            List<String> retrievalTerms = keywordExtractor.extract(retrievalQuery);
            int maxContextHits = configService.getInt("context.maxContextHits");
            int snippetWindow = configService.getInt("context.snippetWindowChars");
            // 引用扩散 + 结构上下文扩展：命中 A → 带出被引块 B / 引用块 C（默认关）/ 父章节块。
            // 扩散块以 Hit 形态混入同一上下文循环，复用图片占位/截取/预算逻辑；任一环节失败降级为不扩散
            KnowledgeRefService.ExpandResult expandResult = knowledgeRefService.expand(hits);
            List<HybridRetrievalService.Hit> extraHits = expandResult.extra();
            Map<String, String> refOrigins = expandResult.origins();
            List<HybridRetrievalService.Hit> allHits = new ArrayList<>(hits);
            allHits.addAll(extraHits);
            int maxExtraHits = Math.max(1, configService.getInt("retrieval.refExpandMaxHits", 3));
            int maxExtraTokens = configService.getInt("retrieval.refExpandMaxTokens", 800);
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
            List<Set<String>> selectedTermSets = new ArrayList<>();
            List<String> selectedPaths = new ArrayList<>();
            for (int hi = 0; hi < allHits.size(); hi++) {
                HybridRetrievalService.Hit hit = allHits.get(hi);
                boolean isExtra = hi >= hits.size();
                if (isExtra) {
                    // 扩散块是可舍弃的增强：数量/token 双上限，超限直接跳过（不做首块硬截断）
                    if (extraUsed >= maxExtraHits || extraTokensUsed >= maxExtraTokens) break;
                } else {
                    if (docNo > maxContextHits) break;
                }
                // 信息增益去冗余：与已选块语义重叠过高则跳过（不占 docNo/extra 配额，只是不再进上下文）
                if (dedupEnabled && !selectedTermSets.isEmpty()
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
                Matcher matcher = imgPattern.matcher(text);
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

                // 块内片段截取：按检索词元定位命中位置，取 ±窗口（省 token 保精度；未命中则整块）
                String fullTextForImg = text; // 截取前的完整文本（占位已替换为 [图片N：desc]）
                if (snippetWindow > 0) {
                    text = extractHitSnippet(fullTextForImg, retrievalTerms, snippetWindow);
                    // 片段截取会丢掉窗口外的 [图片N：描述] 占位 → LLM 看不到图、漏配图。
                    // 把片段里没有的图片占位（描述截断到 60 字符）追加到片段末尾，保证 LLM 有完整配图依据
                    List<String> cutImgs = new ArrayList<>();
                    Matcher im = IMG_NUMBER_PATTERN.matcher(fullTextForImg);
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
                    addDegradation(degradations, degradedCodes, "contextTruncated",
                            "资料内容较长，仅截取部分，细节可能缺失");
                    text = truncateChars(text, Math.max(configService.getInt("chat.truncateFallbackChars", 200), remainTokens - usedTokens));
                    tokens = TokenCounter.estimate(text);
                }

                // 引用来源（ref 与上下文编号对应，回答中 [1] 即可溯源）
                Map<String, Object> src = new LinkedHashMap<>();
                src.put("ref", docNo);
                src.put("knowledgeId", hit.knowledgeId());
                src.put("docId", hit.docId());
                src.put("fileName", fileNameMap.get(hit.docId()));
                src.put("title", hit.title());
                src.put("snippet", snippet(text)); // 用截取后的片段做溯源摘要（更贴近命中内容）
                src.put("images", hit.images()); // 关联文档截图（原始URL，前端经 /proxy 访问）
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

            // 3.5 检索状态行（豆包式，回答上方常驻）：搜索 N 个关键词，参考 M 段资料
            // 只展示主词元（jieba 有效词），2-gram/4-gram 子词元仅参与召回、不展示给用户
            List<String> searchTerms = keywordExtractor.extractMain(retrievalQuery);
            String retrievedJson = JSON.toJSONString(Map.of(
                    "keywords", searchTerms.size(), "refs", docNo - 1, "terms", searchTerms));
            sendSseEvent(emitter, "retrieved", retrievedJson, sessionId);

            // 4. SSE 先发图片 URL 列表（编号顺序，生产开启鉴权时动态签名）
            if (!imgIndex.isEmpty()) {
                List<String> signedUrls = imgIndex.values().stream().map(imageUrlSigner::signUrl).toList();
                sendSseEvent(emitter, "image", JSON.toJSONString(signedUrls), sessionId);
            }

            String user = context.length() == 0
                    ? userQuestion.toString()
                    : userQuestion + "\n\n参考资料：\n" + context;
            if (context.length() == 0) {
                addDegradation(degradations, degradedCodes, "noHit", "未检索到相关资料，回答可能缺乏依据");
            }

            // 5. 异步流式生成（缓冲过滤 <related> 块：跨 token 分割也能正确剥离，前端不会看到标签原文）
            // 生成前最后一道短路检查：流式回答是最长成本段，断开即不再发起（生成中断开由 doOnNext 发送失败取消订阅）
            if (clientDisconnected(emitter)) {
                log.info("[SSE] 客户端断开，终止本轮问答（生成前）: session={}", sessionId);
                return;
            }
            sendSseEvent(emitter, "stage", "正在生成回答…", sessionId);
            AnswerStreamState st = new AnswerStreamState(sessionId, question, emitter,
                    imgIndex, imgDescIndex, sources, userImgs, startTime, queryForLog, thinkingHolder,
                    degradations, degradedCodes, retrievedJson);
            st.disposableRef.set(buildAnswerStream(system.toString(), user, st));

            // 前端断开/超时时停止生成；超时先发 warn（fail-loud：回答被截断必须告知，不留静默半截）
            emitter.onCompletion(() -> st.disposeSafe());
            emitter.onTimeout(() -> {
                log.warn("[FAIL-LOUD] SSE 超时，回答被截断: session={}", sessionId);
                sendSseEvent(emitter, "warn", "回答超时已截断，请重试或缩短问题", sessionId);
                st.disposeSafe();
                completeEmitter(emitter);
            });
            emitter.onError(t -> st.disposeSafe());

        } catch (Exception e) {
            log.error("Chat error", e);
            sendSseEvent(emitter, "error", "系统处理异常，请稍后重试", sessionId);
            completeEmitter(emitter);
        }
    }

    /**
     * 构建并订阅主 LLM 流式回答（H2：未输出任何 token 时中断自动重试，次数 chat.streamRetryCount 可配）。
     * 可变状态与 complete 回调依赖收敛在 AnswerStreamState；重试时重建全新流并丢弃旧缓冲。
     */
    private Disposable buildAnswerStream(String system, String user, AnswerStreamState st) {
        SseEmitter emitter = st.emitter;
        return chatClient.prompt()
                .system(system)
                .user(user)
                // 模型配置界面：per-request 动态覆盖模型名与温度（保存即生效）；maxTokens 限制输出长度（防失控长文/成本）
                .options(OpenAiChatOptions.builder()
                        .model(configService.get("chat.model"))
                        .temperature(configService.getDouble("chat.temperature"))
                        .maxTokens(configService.getInt("context.maxOutputTokens"))
                        .build())
                .stream()
                .content()
                .doOnNext(token -> {
                    st.emitBuf.append(token);
                    String bufStr = st.emitBuf.toString();
                    // 存在未完整闭合的 related 块（开始/闭合标签被跨 token 切分也覆盖）：继续缓冲不下发
                    if (containsUnclosedRelated(bufStr)) {
                        if (bufStr.length() > 3000) {
                            // 异常兜底：模型未闭合标签，直接按原文发送（extractRelated 兜底清理）；fail-loud 标记
                            addDegradation(st.degradations, st.degradedCodes, "relatedMalformed",
                                    "模型输出格式异常（related 标签未闭合），已按原文清理");
                            String raw = bufStr;
                            st.emitBuf.setLength(0);
                            st.fullResponse.append(raw);
                            // 客户端断开：取消流订阅立即停止模型输出（不补 error/complete）
                            if (!sendSseEvent(emitter, "token", raw, st.sessionId)) {
                                st.disposeSafe();
                                return;
                            }
                        }
                        return;
                    }
                    // 剥离完整 related 块，收集推荐内容
                    java.util.regex.Matcher rm = relatedPattern.matcher(bufStr);
                    while (rm.find()) {
                        st.relatedBlock.append(rm.group(1)).append("\n");
                    }
                    String clean = bufStr.replaceAll("<related>[\\s\\S]*?</related>", "");
                    // 尾部保留 19 字符滑动窗口（可能是不完整标签片段），其余下发
                    st.emitBuf.setLength(0);
                    int keep = Math.min(19, clean.length());
                    String sendPart = clean.substring(0, clean.length() - keep);
                    String tailKeep = clean.substring(clean.length() - keep);
                    st.emitBuf.append(tailKeep);
                    if (!sendPart.isEmpty()) {
                        st.fullResponse.append(sendPart);
                        // 客户端断开：取消流订阅立即停止模型输出（不补 error/complete）
                        if (!sendSseEvent(emitter, "token", sendPart, st.sessionId)) {
                            st.disposeSafe();
                        }
                    }
                })
                .doOnError(error -> {
                    // 客户端已断开：不重试也不报错（管道已不在），直接收尾
                    if (clientDisconnected(st.emitter)) {
                        st.disposeSafe();
                        return;
                    }
                    Throwable root = error;
                    while (root.getCause() != null) root = root.getCause();
                    boolean noTokenYet = st.fullResponse.length() == 0 && st.emitBuf.length() == 0;
                    if (noTokenYet && st.retried.getAndIncrement() < streamRetryCount()) {
                        log.warn("[FAIL-LOUD] 主 LLM 流式中断且未输出 token，自动重试第 {} 次: {} -> {}",
                                st.retried.get(), error.getClass().getSimpleName(), root);
                        // 重建全新流，丢弃旧缓冲（避免重试拼接出重复内容）
                        st.fullResponse.setLength(0);
                        st.emitBuf.setLength(0);
                        st.relatedBlock.setLength(0);
                        st.disposableRef.set(buildAnswerStream(system, user, st));
                        return;
                    }
                    log.error("Stream error: {} -> {}", error.getClass().getSimpleName(), root.toString());
                    String msg = (root instanceof java.net.ConnectException)
                            ? "无法连接 AI 服务，请检查网络或 API 地址"
                            : "AI 回复失败，请稍后重试";
                    st.degradations.add(Map.of("code", "streamError", "msg", "模型输出中断：" + msg));
                    sendSseEvent(emitter, "error", msg, st.sessionId);
                    completeEmitter(emitter);
                })
                .doOnComplete(() -> {
                    // 下发缓冲尾部（可能残留滑动窗口），并剥离可能的不完整标签
                    if (st.emitBuf.length() > 0) {
                        String rest = st.emitBuf.toString().replaceAll("<related>[\\s\\S]*?</related>", "")
                                .replaceAll("<related[\\s\\S]*$", "");
                        st.emitBuf.setLength(0);
                        if (!rest.isEmpty()) {
                            st.fullResponse.append(rest);
                            sendSseEvent(emitter, "token", rest, st.sessionId);
                        }
                    }
                    // 相关推荐：优先用流式收集的块内容；兜底再对完整回答剥离一次（防 </related> 缺失等异常）
                    List<String> related = parseRelatedBlock(st.relatedBlock);
                    if (related.isEmpty()) {
                        related = extractRelated(st.fullResponse);
                    }
                    String answer = st.fullResponse.toString();
                    // 引用来源（局部可变：语义一致性自检会剔除不支撑的条目并重编 ref，替换新列表）
                    List<Map<String, Object>> sources = st.sources;

                    // 图片相关性校验兜底：剔除与描述不匹配的 [图片N] 标记并重建编号（LLM 偶发错配）
                    List<String> finalImgs = new ArrayList<>(st.imgIndex.values());
                    if (properties.getImages().getImageFilter().isEnabled() && !st.imgIndex.isEmpty()) {
                        ImageFilterService.RebuildResult rr = imageFilterService.rebuild(answer, st.imgDescIndex, st.question,
                                properties.getImages().getImageFilter().getMinHits(),
                                properties.getImages().getImageFilter().getPreContextChars());
                        if (!rr.dropped().isEmpty()) {
                            // M7 fail-loud：图片被剔除后回答仍引用旧编号，需告知
                            addDegradation(st.degradations, st.degradedCodes, "imgFilterDropped",
                                    "已剔除 " + rr.dropped().size() + " 张与回答内容不匹配的图片引用");
                            log.info("[IMG-FILTER] 图片错配剔除 {} 个: {}", rr.dropped().size(), rr.dropped());
                            answer = rr.text();
                            finalImgs = rr.keptSeq().stream().map(st.imgIndex::get).toList();
                        }
                    }
                    // L4 fail-loud：有引用来源但回答未标注任何 [N]（溯源缺失）
                    // 注意用 find() 而非 matches()：matches 全串锚定且 . 不跨行，多行回答永远误判为未标注
                    if (!sources.isEmpty() && !CITE_PATTERN.matcher(answer).find()) {
                        addDegradation(st.degradations, st.degradedCodes, "noCitation", "回答未标注引用来源");
                    }
                    // 引用编号越界校验：剔除超出来源范围的 [N]（LLM 偶发编造编号，用户点击角标无溯源）
                    int maxRef = sources.size();
                    if (maxRef > 0) {
                        java.util.regex.Matcher cm = CITE_PATTERN.matcher(answer);
                        StringBuilder cb = new StringBuilder();
                        int invalidRefs = 0;
                        while (cm.find()) {
                            int n = Integer.parseInt(cm.group(1));
                            if (n < 1 || n > maxRef) {
                                invalidRefs++;
                                cm.appendReplacement(cb, "");
                            } else {
                                cm.appendReplacement(cb, java.util.regex.Matcher.quoteReplacement(cm.group()));
                            }
                        }
                        cm.appendTail(cb);
                        if (invalidRefs > 0) {
                            answer = cb.toString();
                            addDegradation(st.degradations, st.degradedCodes, "invalidCitation",
                                    "已移除 " + invalidRefs + " 处无效的引用标注（编号超出来源范围）");
                            log.info("[CITE-CHECK] 剔除越界引用 {} 处 (maxRef={})", invalidRefs, maxRef);
                        }
                    }
                    // 引用语义一致性自检（深度防线）：编号没越界 ≠ 内容被支撑——LLM 可能引用了一个块，
                    // 但对应句子的结论与该块无关（编号正确、语义不符）。把每个 [N] 的"前文句子"与其
                    // 来源 snippet 打包给 LLM 判"是否支撑"，不支撑的剔除标记、来源同步裁剪并重编 ref。
                    // 一次额外调用，超时/失败/无引用跳过（保持原回答）；空前文（无法界定句子）的引用放行。
                    if (configService.getBoolean("chat.citationCheckEnabled") && !sources.isEmpty()) {
                        try {
                            CitationCheckResult ccr = citationConsistencyCheck(answer, sources, st.question);
                            if (ccr.droppedCount() > 0) {
                                answer = ccr.text();
                                sources = ccr.sources();
                                addDegradation(st.degradations, st.degradedCodes, "citationUnsupported",
                                        "已剔除 " + ccr.droppedCount() + " 处与引用内容不匹配的引用标注");
                                log.info("[CITE-CHECK] 引用语义一致性剔除 {} 处: {}", ccr.droppedCount(), ccr.dropped());
                            }
                        } catch (Exception e) {
                            // fail-loud：自检失败保持原回答（校验是增强，不是必选防线）
                            log.warn("[FAIL-LOUD] 引用一致性自检失败（保持原回答）: {}", e.getMessage());
                        }
                    }
                    // 引用来源被裁剪后，检索状态行的 refs 需同步（否则"参考 N 段资料"与展开明细不一致，
                    // 且该值会随消息持久化、历史恢复时同样错位）。terms/keywords 不受影响；
                    // 正常未裁剪时 sources.size() 与 refs 相等，重算后值不变。
                    String finalRetrievedJson = st.retrievedJson;
                    try {
                        Map<String, Object> rj = JSON.parseObject(st.retrievedJson);
                        if (rj != null) {
                            Integer oldRefs = rj.get("refs") instanceof Number n ? n.intValue() : null;
                            if (oldRefs == null || oldRefs != sources.size()) {
                                rj.put("refs", sources.size());
                                finalRetrievedJson = JSON.toJSONString(rj);
                            }
                        }
                    } catch (Exception ignored) {
                    }

                    // 记录对话历史（含图片与引用来源），拿到消息ID供前端反馈
                    String sourcesJson = sources.isEmpty() ? null : JSON.toJSONString(sources);
                    List<String> userImgUrls = st.userImgs.stream().map(UserImageService.UserImage::url).toList();
                    sessionService.appendMessage(st.sessionId, "user", st.question,
                            userImgUrls.isEmpty() ? null : userImgUrls, null);
                    String messageId = sessionService.appendMessage(st.sessionId, "assistant", answer,
                            finalImgs, sourcesJson, st.thinkingHolder[0], finalRetrievedJson);

                    // 异步落问答日志（不阻塞 SSE 完成）
                    List<String> hitDocIds = sources.stream().map(s -> String.valueOf(s.get("docId"))).toList();
                    qaLogService.logAsync(st.sessionId, st.question, answer, hitDocIds,
                            !st.sources.isEmpty(), System.currentTimeMillis() - st.startTime,
                            st.queryForLog);

                    // 相似问题语义缓存写入（带图片提问/流式中断的回答不入缓存；异步不阻塞）
                    if (st.userImgs.isEmpty() && !st.degradedCodes.contains("streamError")) {
                        answerCacheService.storeAsync(st.question, answer, sourcesJson, finalImgs, related, messageId);
                    }

                    // done 事件：引用来源/相关推荐/消息ID + 校验修正后的内容/图片 + 思考全文 + 本轮全部降级事件（fail-loud）
                    Map<String, Object> donePayload = new LinkedHashMap<>();
                    donePayload.put("sources", sources);
                    donePayload.put("related", related);
                    donePayload.put("messageId", messageId);
                    donePayload.put("finalContent", answer);
                    donePayload.put("finalImages", finalImgs);
                    donePayload.put("thinking", st.thinkingHolder[0]);
                    donePayload.put("degradations", st.degradations);
                    sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), st.sessionId);
                    completeEmitter(emitter);
                })
                .subscribe();
    }

    /**
     * 单次回答的流式状态与 complete 回调依赖（H2 重试重建流时复用同一状态，旧缓冲被清空）。
     */
    private static final class AnswerStreamState {
        final String sessionId;
        final String question;
        final SseEmitter emitter;
        final Map<Integer, String> imgIndex;
        final Map<Integer, String> imgDescIndex;
        final List<Map<String, Object>> sources;
        final List<UserImageService.UserImage> userImgs;
        final long startTime;
        final String queryForLog;
        final String[] thinkingHolder;
        final List<Map<String, String>> degradations;
        final Set<String> degradedCodes;
        final String retrievedJson;
        final StringBuilder fullResponse = new StringBuilder();
        final StringBuilder relatedBlock = new StringBuilder();
        final StringBuilder emitBuf = new StringBuilder();
        final AtomicInteger retried = new AtomicInteger();
        final AtomicReference<Disposable> disposableRef = new AtomicReference<>();

        AnswerStreamState(String sessionId, String question, SseEmitter emitter,
                          Map<Integer, String> imgIndex, Map<Integer, String> imgDescIndex,
                          List<Map<String, Object>> sources, List<UserImageService.UserImage> userImgs,
                          long startTime, String queryForLog, String[] thinkingHolder,
                          List<Map<String, String>> degradations, Set<String> degradedCodes, String retrievedJson) {
            this.sessionId = sessionId;
            this.question = question;
            this.emitter = emitter;
            this.imgIndex = imgIndex;
            this.imgDescIndex = imgDescIndex;
            this.sources = sources;
            this.userImgs = userImgs;
            this.startTime = startTime;
            this.queryForLog = queryForLog;
            this.thinkingHolder = thinkingHolder;
            this.degradations = degradations;
            this.degradedCodes = degradedCodes;
            this.retrievedJson = retrievedJson;
        }

        void disposeSafe() {
            Disposable d = disposableRef.get();
            if (d != null) d.dispose();
        }
    }

    /**
     * 判断字符串中是否存在未完整闭合的 related 块（开始/闭合标签的跨 token 片段也算）
     * package-private static：纯字符串逻辑，供单元测试直接验证（滑动窗口缓冲依赖此判定）
     */
    static boolean containsUnclosedRelated(String s) {
        if (s.contains("</related>")) {
            // 已有关闭标签：剔除完整块后，剩余部分若还有 related 痕迹则视为未闭合
            String rest = s.replaceAll("<related>[\\s\\S]*?</related>", "");
            return rest.contains("<related") || isRelatedStart(rest) || isRelatedEndStart(rest);
        }
        return s.contains("<related") || isRelatedStart(s) || isRelatedEndStart(s);
    }

    /**
     * 判断字符串末尾是否为 <related> 标签的部分前缀（捕获跨 token 分割的开始标签）
     */
    private static boolean isRelatedStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "<related>".length() && "<related>".startsWith(tail);
    }

    /**
     * 判断字符串末尾是否为 </related> 标签的部分前缀（捕获跨 token 分割的闭合标签）
     */
    private static boolean isRelatedEndStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "</related>".length() && "</related>".startsWith(tail);
    }

    /**
     * 解析流式收集的 <related> 块内容（已去标签）为推荐问题列表
     */
    private List<String> parseRelatedBlock(StringBuilder block) {
        List<String> related = new ArrayList<>();
        if (block == null || block.length() == 0) return related;
        for (String q : block.toString().split("[|\n]")) {
            String t = q.trim();
            if (!t.isEmpty()) related.add(t);
        }
        return related;
    }

    /**
     * 从回答中提取并剥离 <related> 块，返回推荐问题列表（兜底用）
     */
    private List<String> extractRelated(StringBuilder sb) {
        List<String> related = new ArrayList<>();
        String answer = sb.toString();
        Matcher m2 = Pattern.compile("<related>([\\s\\S]*?)</related>").matcher(answer);
        if (m2.find()) {
            String block = m2.group(1).trim();
            for (String q : block.split("[|\n]")) {
                String t = q.trim();
                if (!t.isEmpty()) related.add(t);
            }
            sb.setLength(0);
            sb.append(answer.substring(0, m2.start())).append(answer.substring(m2.end()));
        }
        return related;
    }

    /**
     * 计算上下文预算（token）：min(模型窗口 × 安全系数 − 预留输出, 成本软上限)
     * 模型窗口按当前 chat.model 子串匹配 model-windows 映射，未匹配用默认窗口
     * 参数走 ConfigService（DB 设置页保存即生效，yml 兜底）
     */
    private int resolveContextBudget() {
        String modelWindows = configService.get("context.modelWindows");
        int defaultWindow = configService.getInt("context.defaultWindowTokens");
        double safetyFactor = configService.getDouble("context.safetyFactor");
        int maxOutput = configService.getInt("context.maxOutputTokens");
        int costCap = configService.getInt("context.costCapTokens");

        int window = defaultWindow;
        String model = configService.get("chat.model");
        if (model != null && !model.isBlank() && modelWindows != null) {
            for (String entry : modelWindows.split(",")) {
                String[] kv = entry.trim().split("=");
                if (kv.length == 2 && model.contains(kv[0].trim())) {
                    try {
                        window = Integer.parseInt(kv[1].trim());
                        break;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        int budget = (int) (window * Math.max(0.1, Math.min(1, safetyFactor))) - maxOutput;
        if (costCap > 0 && budget > costCap) {
            budget = costCap;
        }
        return Math.max(budget, 1000); // 至少保留 1000 token
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

    /**
     * 构建注入主回答的对话历史：单条截断 + 剥离 [图片N] 标记 + token 总上限
     */
    private String buildHistoryText(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return "";
        int perMsgChars = configService.getInt("context.historyPerMsgChars");
        int maxTokens = configService.getInt("context.historyMaxTokens");
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map<String, Object> msg : history) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            content = stripImageMarks(content).trim();
            if (content.isEmpty()) continue;
            if (content.length() > perMsgChars) {
                content = content.substring(0, perMsgChars) + "…";
            }
            String line = role + ": " + content + "\n";
            int tokens = TokenCounter.estimate(line);
            if (total + tokens > maxTokens) {
                break; // 超总上限，丢弃更早的历史
            }
            sb.append(line);
            total += tokens;
        }
        return sb.toString();
    }

    /**
     * 剥离文本中的图片标记 [图片N] / [图片N：描述] / [图片：描述]（用于注入历史，避免编号与本轮冲突）
     */
    private String stripImageMarks(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("\\[图片\\d*(?:[：:][^\\]]*)?\\]", " ").replaceAll("\\s+", " ").trim();
    }

    private String snippet(String content) {
        if (content == null) return "";
        // 剥离图片标记与【上下文】章节路径前缀（结构切分注入，不展示给用户）
        String s = content.replaceAll("\\[图片[^\\]]*\\]|【上下文】[^\\n]*\\n?", " ").trim();
        return s.length() > SNIPPET_LEN ? s.substring(0, SNIPPET_LEN) + "…" : s;
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

    /**
     * 引用语义一致性自检：把回答中每个 [N] 首次出现的"前文句子"与其来源 snippet 打包给 LLM，
     * 让模型输出"不支撑该句子结论"的编号；剔除这些编号的标记、来源同步裁剪并重编 ref。
     * <p>
     * 复用 ImageFilterService.precedingContext 取 [N] 前文（[N] 通常在句末，前文即"这句话"）；
     * 前文为空（无法界定句子）的引用放行（宁漏勿杀）。失败/超时由调用方 catch 保持原回答。
     */
    private CitationCheckResult citationConsistencyCheck(String answer, List<Map<String, Object>> sources, String question) {
        // 1. 收集每个编号首次出现的"前文句子"（重复引用按首次判定）
        Map<Integer, String> sentenceByRef = new LinkedHashMap<>();
        Matcher m = CITE_PATTERN.matcher(answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > sources.size() || sentenceByRef.containsKey(n)) continue;
            String ctx = imageFilterService.precedingContext(answer, m.start(), 80);
            if (ctx.isBlank()) continue; // 无前文无法界定句子 → 放行
            sentenceByRef.put(n, ctx);
        }
        if (sentenceByRef.isEmpty()) return new CitationCheckResult(answer, sources, Set.of(), 0);

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
                        .model(configService.get("chat.model"))
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
        if (dropped.isEmpty()) return new CitationCheckResult(answer, sources, Set.of(), 0);

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
            Integer nn = renum.get(Integer.parseInt(cm.group(1)));
            cm.appendReplacement(sb, Matcher.quoteReplacement(nn == null ? "" : "[" + nn + "]"));
        }
        cm.appendTail(sb);
        return new CitationCheckResult(sb.toString(), newSources, dropped, dropped.size());
    }

    /** 引用语义校验结果：text=重建后回答，sources=裁剪后的引用来源（ref 已重编），dropped=被剔除编号 */
    private record CitationCheckResult(String text, List<Map<String, Object>> sources, Set<Integer> dropped, int droppedCount) {
    }

    // ==================== 深度思考（生产级） ====================

    /** 深度思考结果：ok=是否成功（失败降级时 refinedQuery 无意义），thinking=剥离 <search> 后的展示文本，reason=失败原因（fail-loud） */
    private record DeepThinkResult(boolean ok, String thinking, String refinedQuery, List<String> subQueries, String reason) {
    }

    /**
     * 深度思考三阶段：
     * 阶段1 流式思考（SSE thinking 增量；thinkingMode=model 从 reasoning_content 提取，prompt 从 content 提取）
     * 阶段2 提取 <search> 检索计划（精化 query + 子问题）
     * 阶段3 由调用方执行多路检索（本方法只返回计划）
     * 失败/超时/提取失败 → 返回 ok=false + 已收集思考增量，调用方降级单路检索
     */
    private DeepThinkResult runDeepThinking(String sessionId, String question, String imgDescText, SseEmitter emitter) {
        String thinkingMode = configService.get("deepReasoning.thinkingMode");
        boolean enableThinking = configService.getBoolean("deepReasoning.enableThinking");
        boolean multiRetrieval = configService.getBoolean("deepReasoning.multiRetrieval");
        int timeoutMillis = configService.getInt("deepReasoning.timeoutMillis");
        int maxThinkingTokens = configService.getInt("deepReasoning.maxThinkingTokens");

        // 思考 system = 思考引导 prompt + 对话历史（复用 buildHistoryText 裁剪）
        StringBuilder system = new StringBuilder(configService.get("deepReasoning.prompt"));
        String historyText = buildHistoryText(sessionService.getRecentHistory(sessionId, 2));
        if (!historyText.isEmpty()) {
            system.append("\n\n对话历史：\n").append(historyText);
        }
        // user = 原始问题 + 图片描述（若有）
        StringBuilder user = new StringBuilder(question);
        if (imgDescText != null && !imgDescText.isBlank()) {
            user.append("\n\n用户上传了图片，图片内容描述如下（仅用于辅助思考，不用输出图片标记）：\n").append(imgDescText);
        }

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(configService.get("chat.model"))
                .temperature(configService.getDouble("chat.temperature"));
        // qwen 思考模式 max_tokens 会导致空输出：默认不设，仅显式配置 >0 时才设
        if (maxThinkingTokens > 0) {
            optionsBuilder.maxTokens(maxThinkingTokens);
        }
        // thinkingMode=model：extraBody 透传 enable_thinking，思考从 reasoning_content 提取
        if ("model".equals(thinkingMode) && enableThinking) {
            optionsBuilder.extraBody(Map.of("enable_thinking", true));
        }

        StringBuilder thinking = new StringBuilder();
        try {
            chatClient.prompt()
                    .system(system.toString())
                    .user(user.toString())
                    .options(optionsBuilder.build())
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        String delta = extractThinkingDelta(resp, thinkingMode);
                        if (delta != null && !delta.isBlank()) {
                            thinking.append(delta);
                            // 客户端断开：抛异常中止同步消费（blockLast），省余下思考输出；调用方检查点兜底不再检索
                            if (!sendSseEvent(emitter, "thinking", delta, sessionId)) {
                                throw new SseClientGoneException();
                            }
                        }
                    })
                    .blockLast(Duration.ofMillis(Math.max(1000, timeoutMillis)));

            // 阶段2：提取检索计划（剥离 <search> 块用于展示/持久化）
            SearchPlan plan = extractSearchPlan(thinking.toString(), question);
            String display = stripSearchBlock(thinking.toString(), plan.searchTag());
            String status = multiRetrieval && !plan.subQueries().isEmpty() ? "ok" : "ok";
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("status", status);
            done.put("thinking", display);
            sendSseEvent(emitter, "thinking_done", JSON.toJSONString(done), sessionId);
            log.info("[DEEP-THINK] 思考完成 {} 字, refined={}, subs={}", display.length(), plan.refinedQuery(), plan.subQueries());
            return new DeepThinkResult(true, display, plan.refinedQuery(), plan.subQueries(), null);
        } catch (SseClientGoneException e) {
            log.info("[SSE] 客户端断开，深度思考终止: session={}", sessionId);
            return new DeepThinkResult(false, stripSearchBlock(thinking.toString(), "search"),
                    question, List.of(), "disconnected");
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 深度思考失败/超时，降级普通检索: {}", e.getMessage());
            String display = stripSearchBlock(thinking.toString(), "search");
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("status", "degraded");
            done.put("thinking", display);
            sendSseEvent(emitter, "thinking_done", JSON.toJSONString(done), sessionId);
            return new DeepThinkResult(false, display, question, List.of(), e.getMessage());
        }
    }

    /** 检索计划：精化 query + 子问题列表 */
    private record SearchPlan(String refinedQuery, List<String> subQueries, String searchTag) {
    }

    /** 从思考文本提取 <search>精化query|子问题1|子问题2</search>；未命中返回原始问题 */
    private SearchPlan extractSearchPlan(String thinking, String fallback) {
        String tag = configService.get("deepReasoning.searchTag");
        if (tag == null || tag.isBlank()) tag = "search";
        String escapedTag = Pattern.quote(tag);
        Pattern p = Pattern.compile("<" + escapedTag + ">([\\s\\S]*?)</" + escapedTag + ">");
        Matcher m = p.matcher(thinking == null ? "" : thinking);
        if (m.find()) {
            String[] parts = m.group(1).split("\\|");
            List<String> list = Arrays.stream(parts)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
            if (!list.isEmpty()) {
                int maxSub = configService.getInt("deepReasoning.maxSubQueries");
                List<String> subs = list.size() > 1
                        ? list.subList(1, Math.min(list.size(), 1 + Math.max(0, maxSub)))
                        : List.of();
                return new SearchPlan(list.get(0), subs, tag);
            }
        }
        return new SearchPlan(fallback, List.of(), tag);
    }

    /** 剥离 <search>...</search> 块（思考展示/持久化不含检索计划） */
    private String stripSearchBlock(String thinking, String tag) {
        if (thinking == null || thinking.isBlank()) return "";
        String escapedTag = Pattern.quote(tag == null || tag.isBlank() ? "search" : tag);
        return thinking.replaceAll("<" + escapedTag + ">[\\s\\S]*?</" + escapedTag + ">", "").trim();
    }

    /** 按 thinkingMode 从 ChatResponse 提取思考增量：model=metadata.reasoningContent（回退 text）；prompt=text */
    private String extractThinkingDelta(ChatResponse resp, String thinkingMode) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) return null;
        Object output = resp.getResult().getOutput();
        if (!(output instanceof AssistantMessage am)) return null;
        if ("model".equals(thinkingMode)) {
            Object rc = am.getMetadata().get("reasoningContent");
            if (rc != null && !String.valueOf(rc).isBlank()) {
                return String.valueOf(rc);
            }
        }
        // prompt 模式或 metadata 无思考内容：回退 content 文本
        String text = am.getText();
        return (text == null || text.isBlank()) ? null : text;
    }

    /** 活跃 SSE 通道的断开标记（emitter → 已断开）。仅内部查表用；发送失败即打标、完成回调即移除，不会长期滞留 */
    private static final java.util.concurrent.ConcurrentHashMap<SseEmitter, java.util.concurrent.atomic.AtomicBoolean> ACTIVE_SSE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 客户端是否已断开（供各等待点短路，避免断开后继续跑 LLM 调用与检索） */
    private static boolean clientDisconnected(SseEmitter emitter) {
        java.util.concurrent.atomic.AtomicBoolean dead = ACTIVE_SSE.get(emitter);
        return dead != null && dead.get();
    }

    /** 客户端断开信号：思考流 doOnNext 内中断同步消费（blockLast）用 */
    private static final class SseClientGoneException extends RuntimeException {
    }

    /**
     * SSE 发送。返回 false = 客户端已断开（本次发送失败或此前已打标）；断开只记日志不抛错
     * （fail-loud），调用方在关键发送点按返回值短路后续 LLM/检索开销。
     */
    private boolean sendSseEvent(SseEmitter emitter, String type, String content, String sessionId) {
        java.util.concurrent.atomic.AtomicBoolean dead = ACTIVE_SSE.get(emitter);
        if (dead != null && dead.get()) return false;
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data("{\"type\":\"" + type + "\",\"content\":" +
                            JSON.toJSONString(content) +
                            ",\"sessionId\":\"" + sessionId + "\"}"));
            return true;
        } catch (IOException e) {
            ACTIVE_SSE.computeIfAbsent(emitter, k -> new java.util.concurrent.atomic.AtomicBoolean()).set(true);
            log.warn("[FAIL-LOUD] SSE 客户端断开 (type={}, session={}): {}", type, sessionId, e.getMessage());
            return false;
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            // 忽略
        }
    }

    /** 查询改写结果：query + 是否降级（fail-loud：改写失败/空结果标记 degraded，调用方上报） */
    public record RewriteResult(String query, boolean degraded) {
    }

    /**
     * LLM 改写用户问题，优化检索精准度。支持多轮对话上下文（追问场景）。
     * 失败/超时/空结果时降级为原始问题并标记 degraded（不再静默）。
     */
    private RewriteResult rewriteQuery(String question, List<Map<String, Object>> history) {
        if (!properties.getQueryRewrite().isEnabled()) {
            return new RewriteResult(question, false);
        }
        try {
            // 构建 system prompt：根据是否有足够历史选择单轮或多轮改写
            String systemPrompt;
            boolean hasHistory = history != null && history.size() >= 2;
            if (hasHistory) {
                String historyText = formatHistory(history);
                String template = properties.getQueryRewrite().getPromptMultiTurn();
                systemPrompt = template.replace("%s", historyText);
            } else {
                systemPrompt = properties.getQueryRewrite().getPrompt();
            }

            String rewritten = CompletableFuture
                    .supplyAsync(() -> chatClient.prompt()
                            .system(systemPrompt)
                            .user(question)
                            .options(OpenAiChatOptions.builder()
                                    .model(configService.get("chat.model"))
                                    .temperature(configService.getDouble("chat.temperature"))
                                    .build())
                            .call()
                            .content(), rewriteExecutor)
                    .get(configService.getInt("retrieval.rewriteTimeoutMs",
                            properties.getQueryRewrite().getTimeoutMillis()), TimeUnit.MILLISECONDS);
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("[FAIL-LOUD] 查询改写返回空，使用原问题检索");
                return new RewriteResult(question, true);
            }
            String trimmed = rewritten.trim();
            log.info("[rewrite] history={} {} -> {}", hasHistory, question, trimmed);
            return new RewriteResult(trimmed, false);
        } catch (TimeoutException e) {
            // TimeoutException.getMessage() 为 null → 明确报超时（本地模型响应慢的常见场景），排障不再看到裸 null
            log.warn("[FAIL-LOUD] 查询改写超时（{}ms），使用原问题检索",
                    configService.getInt("retrieval.rewriteTimeoutMs", properties.getQueryRewrite().getTimeoutMillis()));
            return new RewriteResult(question, true);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 查询改写失败（{}），使用原问题检索: {}",
                    e.getClass().getSimpleName(), e.getMessage() == null ? "无详情" : e.getMessage());
            log.debug("[rewrite] 改写失败详情", e);
            return new RewriteResult(question, true);
        }
    }

    /**
     * 将对话历史格式化为 user/assistant 文本，用于多轮改写 prompt（M5：单条截断 200 字、总长 1500 字）
     */
    private String formatHistory(List<Map<String, Object>> history) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : history) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            if (content.length() > 200) {
                content = content.substring(0, 200) + "…";
            }
            if (role.equals("user")) {
                sb.append("用户：").append(content).append("\n");
            } else if (role.equals("assistant")) {
                sb.append("助手：").append(content).append("\n");
            }
            if (sb.length() > 1500) {
                break;
            }
        }
        return sb.toString();
    }
}
