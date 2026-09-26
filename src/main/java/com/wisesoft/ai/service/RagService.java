package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.dto.ChatRequest;
import com.wisesoft.ai.model.Agent;
import com.wisesoft.ai.model.KnowledgeBase;
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
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * 智能体/知识库可覆盖的检索参数白名单（只有这些键生效，防止越权改其他配置）；
     * 与设置页「检索设置 / 重排服务」暴露的项保持一致。
     * <p>public：知识库新建表单的"默认值模板"端点需要按同一白名单取全局值（见 KnowledgeBaseController）。
     */
    public static final Set<String> AGENT_QUERY_PARAM_KEYS = Set.of(
            "retrieval.vectorWeight", "retrieval.keywordWeight", "retrieval.vecThreshold",
            "retrieval.vectorTopK", "retrieval.keywordLimit",
            "rerank.enabled", "rerank.model", "rerank.baseUrl");

    /**
     * 本轮检索参数覆盖，优先级：**全局设置 &lt; 知识库 &lt; 智能体**（后者覆盖前者）。
     * <p>知识库级来源 = <b>本轮实际参与检索的库</b>（{@code kbIds}；为空=不限库，取全部未删除库，
     * 与检索范围同一口径）。不再从"智能体绑定的库"派生——不选智能体、或智能体未绑库时，
     * 库自己的检索策略同样必须生效（知识库是检索参数的归属，不该依赖另一个资源才可用）。
     * 智能体级是"调用方覆盖"，仅在有智能体时叠加。未配置的项一律继承更外层。
     * <p>用线程局部覆盖而不是改检索方法签名：检索在本轮问答线程内同步执行，覆盖值可见；
     * 多路并行检索跑在池化线程，由 HybridRetrievalService 取快照后在子线程内重放覆盖。
     */
    private void applyQueryOverrides(java.util.Collection<String> kbIds, Agent agent) {
        Map<String, String> ov = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>();
        // 1) 知识库级：本轮检索涉及的每个库各自的检索策略
        for (KnowledgeBase kb : resolveQueryParamKbs(kbIds)) {
            if (kb.getQueryParams() == null || kb.getQueryParams().isBlank()) continue;
            Map<String, String> p = parseQueryParams(kb.getQueryParams());
            if (p.isEmpty()) continue;
            // 多库对同一键配置不同值：按库顺序取后者，并留日志（不静默取错）
            for (Map.Entry<String, String> e : p.entrySet()) {
                String prev = ov.get(e.getKey());
                if (prev != null && !prev.equals(e.getValue())) {
                    log.warn("[KB] 多个知识库对 {} 配置了不同值（{} → {}），按库顺序取后者",
                            e.getKey(), prev, e.getValue());
                }
            }
            ov.putAll(p);
            sources.add(kb.getName() == null ? kb.getId() : kb.getName());
        }
        // 2) 智能体级：覆盖库级
        if (agent != null) ov.putAll(parseQueryParams(agent.getQueryParams()));
        if (!ov.isEmpty()) {
            configService.putOverrides(ov);
            log.info("[RAG] 应用检索参数覆盖 {} 项（知识库 {} + 智能体 {}）: {}", ov.size(),
                    sources.isEmpty() ? "无" : String.join("、", sources),
                    agent == null ? "无" : agent.getId(), ov.keySet());
        }
    }

    /**
     * 本轮检索参数涉及的库：kbIds 非空按其逐个取库；为空（不限库）取全部未删除库
     * （{@code KnowledgeBaseService.list()} 已按默认库优先、创建时间升序，与"全库检索"的范围一致）。
     */
    private List<KnowledgeBase> resolveQueryParamKbs(java.util.Collection<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) return knowledgeBaseService.list();
        List<KnowledgeBase> out = new ArrayList<>();
        for (String kbId : kbIds) {
            KnowledgeBase kb = knowledgeBaseService.get(kbId);
            if (kb != null) out.add(kb);
        }
        return out;
    }

    /**
     * 解析检索参数 JSON → 覆盖表；**白名单之外的键一律丢弃**（防止借参数覆盖改到无关配置），
     * 解析失败只忽略该来源、不中断问答。
     */
    private Map<String, String> parseQueryParams(String json) {
        Map<String, String> ov = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return ov;
        try {
            Map<String, Object> m = JSON.parseObject(json);
            if (m == null) return ov;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                if (!AGENT_QUERY_PARAM_KEYS.contains(e.getKey())) {
                    log.warn("[AGENT] 检索参数键不在白名单内，已忽略: {}", e.getKey());
                    continue;
                }
                String v = String.valueOf(e.getValue()).trim();
                if (!v.isEmpty()) ov.put(e.getKey(), v);
            }
        } catch (Exception e) {
            log.warn("[AGENT] 检索参数解析失败（忽略该来源，使用上层配置）: {}", e.getMessage());
        }
        return ov;
    }

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

    /**
     * 重排（重排模型归知识库检索设置：queryParams["rerank.model"] 经线程局部覆盖生效）；
     * 服务可用就执行；不可用 → 保持融合分排序并 fail-loud 标记。
     */
    private List<HybridRetrievalService.Hit> rerankIfNeeded(List<HybridRetrievalService.Hit> hits, String query,
                                                             List<Map<String, String>> degradations, Set<String> degradedCodes) {
        if (!hits.isEmpty()) {
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

    /** 改写结果（实际检索用 query + 命中） */

    /** 引用摘要截断长度 */
    private static final int SNIPPET_LEN = 80;

    /**
     * 深度思考自动路由（autoRoute 开启时）：短问直接答；长问（≥25 字）或含多条件/对比/递进词的复杂问题自动开思考。
     * 保守启发式——只对明显复杂的问题路由，避免常见"如何/怎么"类问题全量思考导致成本与延迟翻倍。
     */
    /** 自动路由阈值（设置页 deepReasoning.autoRoute* 可配）：短于下限不思考，达到上限或命中触发词才思考 */
    private boolean shouldAutoDeepThink(String question) {
        if (question == null) return false;
        String q = question.trim();
        int minChars = Math.max(1, configService.getInt("deepReasoning.autoRouteMinChars", 8));
        int longChars = Math.max(minChars, configService.getInt("deepReasoning.autoRouteLongChars", 25));
        if (q.length() < minChars) return false;
        if (q.length() >= longChars) return true;
        for (String w : autoRouteKeywords()) {
            if (q.contains(w)) return true;
        }
        return false;
    }

    /** 自动路由触发词（逗号分隔，deepReasoning.autoRouteKeywords 可配；留空=只按长度判断） */
    private String[] autoRouteKeywords() {
        String cfg = configService.get("deepReasoning.autoRouteKeywords");
        if (cfg == null || cfg.isBlank()) return new String[0];
        List<String> words = new ArrayList<>();
        for (String w : cfg.split("[,，]")) {
            String t = w.trim();
            if (!t.isEmpty()) words.add(t);
        }
        return words.toArray(new String[0]);
    }

    /** <related> 追问推荐数（retrieval.relatedCount 可配，默认 3）：提示词里的示例与数量保持一致 */
    private String relatedPromptLine() {
        int n = Math.max(1, configService.getInt("retrieval.relatedCount", 3));
        StringBuilder ex = new StringBuilder("问题1");
        for (int i = 2; i <= n; i++) ex.append("|问题").append(i);
        return "\n回答末尾用 <related>" + ex + "</related> 输出 " + n
                + " 个用户可能追问的相关问题（用 | 分隔），如无合适问题可不输出。";
    }

    /**
     * 思考关键词增强：从思考全文提取词元（injectKeywords 开关），供多路检索补充一条 query / 失败降级增强。
     * 思考链里往往出现关键实体与限定词（如"必填、数据唯一、审批流"），补进检索可提升召回。
     */
    private List<String> thinkingEnhanceTerms(String thinking) {
        if (!configService.getBoolean("deepReasoning.injectKeywords") || thinking == null || thinking.isBlank()) {
            return List.of();
        }
        List<String> terms = keywordExtractor.extract(thinking);
        if (terms.isEmpty()) return List.of();
        int max = Math.max(1, configService.getInt("deepReasoning.injectKeywordsMax", 5));
        return terms.stream().limit(max).toList();
    }

    private static final Pattern relatedPattern = Pattern.compile("<related>([\\s\\S]*?)</related>");
    /** 全局编号后的图片占位：[图片N] 或 [图片N：描述]（描述内不含 ]；用于收集片段截取丢掉的图） */
    private static final Pattern IMG_NUMBER_PATTERN = Pattern.compile("\\[图片\\d+[^\\]]*\\]");
    /** 入库原文里的图片占位：[图片] 或 [图片：描述]（尚未编号；填充上下文时替换为 IMG_NUMBER_PATTERN 形态） */
    private static final Pattern IMG_PLACEHOLDER_PATTERN = Pattern.compile("\\[图片(：.*?)?\\]");

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final AppProperties properties;
    private final ImageUrlSigner imageUrlSigner;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;
    private final QaLogService qaLogService;
    private final UserImageService userImageService;
    private final ConfigService configService;
    private final ImageFilterService imageFilterService;
    private final KeywordExtractor keywordExtractor;
    /** 知识库精确检索工具（Function Calling；由 tool.* 配置开关控制，默认关闭） */
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;
    /** 产物交付服务（会话 emitter 注册表 + 文件落盘 + SSE 下发） */
    private final ArtifactService artifactService;
    /** MCP 客户端服务（外部 MCP server 工具接入；按用户隔离连接池，各人连各人的服务） */
    private final McpClientService mcpClientService;

    /** 知识库服务：解析「智能体关联的知识库 → 允许检索的文档集合」，检索按库隔离 */
    private final KnowledgeBaseService knowledgeBaseService;

    /** 用户表（个人默认模型解析） */
    private final com.wisesoft.ai.mapper.UserMapper userMapper;
    /** 产物交付工具（Function Calling；生成文件并实时推送） */
    private final PresentArtifactTool presentArtifactTool;
    /** 内置高频工具（计算/当前时间/日期差等，tool.builtin.enabled 控制，默认关） */
    private final BuiltinTools builtinTools;
    /** 技能（Skills）：清单注入 system prompt + readSkill 工具的服务端（技能为个人资产，按 uid 取） */
    private final SkillService skillService;
    /** 聊天附件（文档类）：解码/解析为纯文本注入本轮上下文（图片走 images 多模态，不经此服务） */
    private final ChatAttachmentService chatAttachmentService;
    /** SubAgent 并行编排（4.3）：多视角并行检索 + 要点提炼（agent.enabled 控制，默认关） */
    private final SubAgentOrchestrator subAgentOrchestrator;
    /** 智能体配置（4.1）：对话页下拉选中后，按智能体覆盖模型/提示词/工具/知识库范围 */
    private final AgentService agentService;
    /** 自动派遣（agentId="auto"）：按名称+描述从可见主智能体中路由本轮智能体 */
    private final AgentDispatchService agentDispatchService;
    private final ModelRegistryService modelRegistryService;

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
                      AppProperties properties,
                      ImageUrlSigner imageUrlSigner,
                      HybridRetrievalService hybridRetrievalService,
                      RerankService rerankService,
                      DocumentMetaCache documentMetaCache,
                      QaLogService qaLogService,
                      UserImageService userImageService,
                      ConfigService configService,
                      ImageFilterService imageFilterService,
                      KeywordExtractor keywordExtractor,
                      KnowledgeRetrievalTool knowledgeRetrievalTool,
                      ArtifactService artifactService,
                      PresentArtifactTool presentArtifactTool,
                      BuiltinTools builtinTools,
                      SkillService skillService,
                      ChatAttachmentService chatAttachmentService,
                      SubAgentOrchestrator subAgentOrchestrator,
                      AgentService agentService,
                      AgentDispatchService agentDispatchService,
                      McpClientService mcpClientService,
                      KnowledgeBaseService knowledgeBaseService,
                      com.wisesoft.ai.mapper.UserMapper userMapper,
                      ModelRegistryService modelRegistryService) {
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
        this.knowledgeRetrievalTool = knowledgeRetrievalTool;
        this.artifactService = artifactService;
        this.presentArtifactTool = presentArtifactTool;
        this.builtinTools = builtinTools;
        this.skillService = skillService;
        this.chatAttachmentService = chatAttachmentService;
        this.subAgentOrchestrator = subAgentOrchestrator;
        this.agentService = agentService;
        this.agentDispatchService = agentDispatchService;
        this.mcpClientService = mcpClientService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.userMapper = userMapper;
        this.modelRegistryService = modelRegistryService;
    }

    /**
     * 处理用户问题（可含上传图片与附件），通过 SSE 流式返回。
     * deepThink=true 时先流式输出思考过程（thinking 事件），提取检索计划后多路检索再回答。
     * attachments 为文档类附件（服务端解析为文本注入本轮上下文）；skills 为用户本轮主动选用的技能名（全文注入 system prompt）。
     * modelOverride 为聊天页的会话级模型覆盖（引用或遗留名，仅用户手动切换时传）；userId 用于解析个人默认模型。
     * 整条流水线在独立线程池执行（重活不占 Tomcat 请求线程），控制器返回后 SSE 由流水线线程驱动。
     */
    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter) {
        // 自动路由：未手动开启深度思考时，按问题特征（长度/多条件/对比）自动判断是否需要思考（autoRoute 默认关）
        if (!deepThink && configService.getBoolean("deepReasoning.autoRoute")) {
            deepThink = shouldAutoDeepThink(question);
        }
        // lambda 引用需 effectively final：自动路由改写后用局部副本传递
        final boolean useDeepThink = deepThink;
        // 断开跟踪：登记查表项并绑定生命周期回调清理；发送失败也会打标（见 sendSseEvent），各等待点据此短路后续 LLM/检索开销
        ACTIVE_SSE.put(emitter, new java.util.concurrent.atomic.AtomicBoolean());
        emitter.onCompletion(() -> ACTIVE_SSE.remove(emitter));
        emitter.onTimeout(() -> ACTIVE_SSE.remove(emitter));
        emitter.onError(t -> ACTIVE_SSE.remove(emitter));
        syncPipelineSize();
        try {
            pipelineExecutor.execute(() -> {
                // 流水线线程**没有请求上下文**：检索的文档可见性过滤（HybridRetrievalService
                // .loadNonVisibleDocIds 读 RequestUser）会拿到 anonymous ⇒ 配了共享范围（department/user）
                // 的文档被判为不可见并被过滤，表现就是"共享给我的资料，我在问答里检索不到"。
                // 这里按本轮 userId 从用户档案装载身份，让过滤按**真实用户**算（来源是用户表，
                // 与调用线程无关：网页问答的 Tomcat 线程、定时任务的池线程同一个来源）。
                boolean identity = loadIdentity(userId);
                try {
                    runChat(sessionId, question, userImages, attachments, skills, useDeepThink,
                            agentId, modelOverride, userId, emitter);
                } finally {
                    if (identity) com.wisesoft.ai.util.RequestUser.clear();
                    // 智能体检索参数的作用域覆盖随本轮结束清除（ThreadLocal，池化线程复用必须清，
                    // 否则下一轮请求会继承上一轮智能体的检索策略）
                    configService.clearOverride();
                }
            });
        } catch (RejectedExecutionException e) {
            // L7 fail-loud：繁忙拒绝时告知当前队列长度（用户可感知拥堵程度）
            int queued = pipelineExecutor == null ? 0 : pipelineExecutor.getQueue().size();
            log.warn("[FAIL-LOUD] 问答流水线繁忙，拒绝请求（排队 {}）: session={}", queued, sessionId);
            sendSseEvent(emitter, "error", "系统繁忙（当前排队 " + queued + " 个请求），请稍后重试", sessionId);
            completeEmitter(emitter);
        }
    }

    /**
     * 问答流水线主体（独立线程执行）：图片/附件处理 → 改写 → 检索/深度思考 → 上下文构建 → LLM 流式输出
     */
    private void runChat(String sessionId, String question, List<String> userImages,
                         List<ChatRequest.Attachment> attachments, List<String> skills, boolean deepThink,
                         String agentId, String modelOverride, String userId, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        // 个人偏好一次取齐：聊天模型（resolveModel 用）+ 个人默认视觉模型（本轮图片理解用）
        final com.wisesoft.ai.model.User prefUser = loadPrefUser(userId);
        // 本轮生效模型（会话覆盖 > 个人默认，全局兜底已移除），回填进流式状态供 buildAnswerStream 使用；
        // 全部未配置时 fail-loud：引导用户配置，而不是发空 model 到网关
        final String resolvedModel = resolveModel(modelOverride, prefUser);
        if (resolvedModel.isBlank()) {
            log.warn("[FAIL-LOUD] 未配置任何模型（会话覆盖/个人默认均未指定）: session={}", sessionId);
            sendSseEvent(emitter, "error",
                    "未指定模型：请在对话右上角选择模型，或在个人设置中配置默认模型", sessionId);
            completeEmitter(emitter);
            return;
        }
        final String userVisionRef = prefUser == null || prefUser.getDefaultVisionModel() == null
                ? "" : prefUser.getDefaultVisionModel();
        // 智能体（4.1）：agentId="auto" 走自动派遣（按名称+描述路由）；无效/缺失视为无覆盖（继承全局）。
        // 派遣在 resolveModel 之后（用当轮生效模型判路），失败回落默认智能体
        final Agent agent = resolveAgent(agentId, question, resolvedModel, sessionId, emitter);
        if (agent != null) {
            log.info("[AGENT] 本轮使用智能体 {}（{}）", agent.getId(), agent.getName());
        }
        // 目标知识库集合（检索按库的向量模型分组逐库查询；null=不限，全库分组检索）
        final java.util.Collection<String> scopeKbIds = scopeKbIdsOf(agent);
        // 检索参数覆盖：库级按本轮实际检索范围（未选智能体时同样生效），智能体级在其上叠加
        applyQueryOverrides(scopeKbIds, agent);
        // 深度思考按生效模型的能力归一：none=不支持强制关、always=恒思考强制开、switchable=用户开关
        final String modelThinking = modelRegistryService.referenceThinking(resolvedModel);
        final boolean useDeepThink;
        if (ModelRegistryService.THINK_NONE.equals(modelThinking)) {
            if (deepThink) log.info("[THINK] 模型 {} 不支持思考，深度思考已忽略", resolvedModel);
            useDeepThink = false;
        } else if (ModelRegistryService.THINK_ALWAYS.equals(modelThinking)) {
            useDeepThink = true;
        } else {
            useDeepThink = deepThink;
        }
        // 「不使用知识库」的纯角色智能体：整条跳过检索链路（改写/深度思考检索/命中填充/子代理编排都不跑，
        // 省掉整轮检索+重排成本）；用户手动 @ 的文档仍会前置进上下文（手动指定优先于智能体配置）。
        final boolean knowledgeOff = agent != null && Integer.valueOf(1).equals(agent.getKnowledgeDisabled());
        // 检索范围：智能体关联的知识库（主路径）→ 库内文档；knowledgeScope 降级为「库内再细选文档」
        final Set<String> scopeDocIds = resolveScopeDocIds(agent);
        // 分段耗时（排障用：记的是「距开始的累计毫秒」，差值即为该阶段耗时），随问答日志落库
        final Map<String, Long> stageMs = new LinkedHashMap<>();
        // 深度思考全文（供 done 事件/持久化；lambda 中引用需 effectively final，用数组容器）
        final String[] thinkingHolder = {null};
        // 本轮回答的全部降级/兜底事件（fail-loud：随 done 下发，前端渲染警示条；code 去重，同类只报一次）
        List<Map<String, String>> degradations = new ArrayList<>();
        Set<String> degradedCodes = new HashSet<>();
        try {
            // 0. 进度提示：理解问题阶段（图片描述/改写都有耗时，先给用户反馈）
            sendSseEvent(emitter, "stage", "正在理解问题…", sessionId);
            // 0. 用户上传图片：并行保存+视觉描述（用于上下文与检索召回）
            List<UserImageService.UserImage> userImgs = userImageService.process(userImages, userVisionRef);
            String imgDescText = userImgs.isEmpty() ? "" : userImgs.stream()
                    .map(i -> "- " + (i.desc().isBlank() ? "（图片内容无法识别）" : i.desc()))
                    .collect(Collectors.joining("\n"));

            // 0.1 用户上传附件（文档类）：解析为纯文本注入本轮上下文。
            //     单附件解析失败以可读错误说明占位、其余照常（不拖垮整轮）；元信息（名称/体积）随消息持久化供气泡回显
            List<ChatAttachmentService.PreparedAttachment> preparedAtts = chatAttachmentService.prepare(attachments);
            String attachmentText = chatAttachmentService.buildContextText(preparedAtts);
            List<Map<String, Object>> attachmentsMeta = new ArrayList<>();
            if (attachments != null) {
                for (int i = 0; i < Math.min(attachments.size(), preparedAtts.size()); i++) {
                    attachmentsMeta.add(ChatAttachmentService.metaOf(attachments.get(i), preparedAtts.get(i).size()));
                }
            }

            // 0.2 用户本轮主动选用的技能（输入框「+」菜单）：全文注入本轮 system prompt
            String userSkillText = buildUserSkillText(userId, skills);

            // 0.4 智能体声明「不使用知识库」：跳过改写/深度思考/检索/子代理编排整条链路，
            //     直接走生成（仅 @ 引用的文档块会前置进上下文）。图片提问也不走视觉检索，
            //     但图片描述仍会随问题发给模型（多模态理解与知识库无关）。
            if (knowledgeOff) {
                log.info("[AGENT] 智能体 {} 不使用知识库，跳过检索链路", agent.getId());
                runNoKnowledgeChat(sessionId, question, userId, userImgs, imgDescText, attachmentText, userSkillText,
                        attachmentsMeta, emitter, startTime, thinkingHolder, degradations, degradedCodes,
                        agent, stageMs, resolvedModel);
                return;
            }

            // 检索查询：直接使用原问题（不再做查询改写）
            String retrievalQuery = question;
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
            //    失败/超时/未提取到计划 → 降级检索，但已收集的思考内容（thinking 词元）参与增强，不白费
            List<HybridRetrievalService.Hit> hits = null;
            HybridRetrievalService.RetrievalDiag retrievalDiag = new HybridRetrievalService.RetrievalDiag();
            // 思考链注入参考（深度思考成功后把推理过程截断注入回答 prompt，让"想过的"作用于"答"）
            String thinkingInject = "";
            // 思考关键词增强（从思考全文提取词元补充检索；深度思考失败时也用它增强降级检索）
            List<String> thinkTerms = List.of();
            if (useDeepThink && configService.getBoolean("deepReasoning.enabled")) {
                DeepThinkResult dr = runDeepThinking(sessionId, question, imgDescText, attachmentText, emitter, resolvedModel);
                thinkingHolder[0] = dr.thinking();
                thinkTerms = thinkingEnhanceTerms(dr.thinking());
                if (configService.getBoolean("deepReasoning.injectThinking") && dr.thinking() != null && !dr.thinking().isBlank()) {
                    int maxChars = Math.max(100, configService.getInt("deepReasoning.injectThinkingMaxChars", 800));
                    String t = dr.thinking();
                    thinkingInject = t.length() > maxChars ? t.substring(0, maxChars) + "…" : t;
                }
                if (dr.ok()) {
                    String rankQuery;
                    // 多路检索：精化 query + 子问题并行召回合并 + 思考词元增强路；开关关闭时单路精化 query
                    sendSseEvent(emitter, "stage", "正在检索资料…", sessionId);
                    if (configService.getBoolean("deepReasoning.multiRetrieval")) {
                        List<String> queries = new ArrayList<>();
                        queries.add(dr.refinedQuery());
                        queries.addAll(dr.subQueries());
                        if (!thinkTerms.isEmpty()) {
                            queries.add(String.join(" ", thinkTerms));
                        }
                        hits = hybridRetrievalService.searchMulti(queries, retrievalDiag, scopeKbIds);
                        rankQuery = dr.refinedQuery();
                    } else {
                        retrievalQuery = thinkTerms.isEmpty()
                                ? dr.refinedQuery()
                                : dr.refinedQuery() + " " + String.join(" ", thinkTerms);
                        hits = hybridRetrievalService.search(retrievalQuery, retrievalDiag, scopeKbIds);
                        rankQuery = retrievalQuery;
                    }
                    // 与普通路径一致：命中数在重排区间内时重排（多路合并后同样重排，保持两路行为一致）
                    hits = rerankIfNeeded(hits, rankQuery, degradations, degradedCodes);
                    hits = applyScope(hits, scopeDocIds); // 智能体知识库范围约束
                    log.info("[DEEP-THINK] 检索计划: refined={}, subQueries={}, thinkTerms={}, hits={}",
                            dr.refinedQuery(), dr.subQueries(), thinkTerms, hits.size());
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
                // 深度思考失败但产生了思考内容：用"原问题 + 思考词元"检索，思考不算白费（比纯普通检索召回更好）
                if (useDeepThink && !thinkTerms.isEmpty()) {
                    retrievalQuery = question + " " + String.join(" ", thinkTerms);
                    hits = hybridRetrievalService.search(retrievalQuery, retrievalDiag, scopeKbIds);
                    hits = rerankIfNeeded(hits, retrievalQuery, degradations, degradedCodes);
                } else {
                    hits = hybridRetrievalService.search(retrievalQuery, retrievalDiag, scopeKbIds);
                }
                hits = rerankIfNeeded(hits, retrievalQuery, degradations, degradedCodes);
                hits = applyScope(hits, scopeDocIds); // 智能体知识库范围约束
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

            // SubAgent 并行编排（4.3，默认关）：多视角并行检索 + 要点提炼。
            // 放在检索之后、system 构建之前——子代理命中要并入上下文，要点要注入 system。
            // 增强项：编排内部已把所有异常降级为空结果，失败不影响主链路
            SubAgentOrchestrator.Outcome subOutcome = null;
            // 按需委派的路由结果（声明在 if 外：创建 AnswerStreamState 时要回填，供 done 下发与持久化）
            Map<String, Object> subagentRouteInfo = null;
            if (configService.getBoolean("agent.enabled")) {
                List<Agent> candidates = resolveSubAgents(agent);
                // 编排视图：分支进度实时推 subagent 事件（前端渲染子智能体卡片）
                Consumer<SubAgentOrchestrator.BranchEvent> onBranch = branch -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", branch.idx());
                    m.put("name", branch.name());
                    m.put("status", branch.status());
                    m.put("hits", branch.hits());
                    m.put("elapsedMs", branch.elapsedMs());
                    m.put("delegated", branch.delegated());
                    m.put("description", branch.description());
                    m.put("digest", branch.digest());
                    sendSseEvent(emitter, "subagent", JSON.toJSONString(m), sessionId);
                };
                if (candidates.isEmpty()) {
                    // 未挂子智能体 → 原有的「多视角并行检索」
                    sendSseEvent(emitter, "stage", "正在并行检索多个视角…", sessionId);
                    subOutcome = subAgentOrchestrator.run(question, null, onBranch, resolvedModel);
                } else {
                    // 挂了子智能体 → 先由主模型按需挑选：只咨询与问题相关的角色，
                    // 避免"全派"导致无关角色白跑（0 命中噪音 + 多余的检索与提炼开销）
                    sendSseEvent(emitter, "stage", "正在判断需要咨询哪些助手…", sessionId);
                    List<Agent> delegated = subAgentOrchestrator.route(question, candidates, resolvedModel);
                    if (delegated.size() != candidates.size()) {
                        log.info("[SUBAGENT] 按需委派：{} 个候选中挑选 {} 个（{}）", candidates.size(), delegated.size(),
                                delegated.stream().map(Agent::getName).collect(Collectors.joining("、")));
                    }
                    // 路由结果下发：让"挑选过程"可见（前端展示"从 N 个候选中挑选 M 个"）
                    Map<String, Object> routeInfo = new LinkedHashMap<>();
                    routeInfo.put("candidates", candidates.size());
                    routeInfo.put("picked", delegated.size());
                    routeInfo.put("names", delegated.stream().map(Agent::getName).toList());
                    subagentRouteInfo = routeInfo;
                    sendSseEvent(emitter, "subagent_route", JSON.toJSONString(routeInfo), sessionId);
                    if (delegated.isEmpty()) {
                        log.info("[SUBAGENT] 按需委派判定无需咨询任何助手，跳过并行编排（问题与各助手职责均不匹配）");
                    } else {
                        sendSseEvent(emitter, "stage", "正在并行咨询 " + delegated.size() + " 个子智能体…", sessionId);
                        subOutcome = subAgentOrchestrator.run(question, delegated, onBranch, resolvedModel);
                    }
                }
                if (subOutcome != null && !subOutcome.hits().isEmpty()) {
                    log.info("[SUBAGENT] 命中并入主链路 {} 块（子代理 {} 个，耗时 {}ms）",
                            subOutcome.hits().size(), subOutcome.agents(), subOutcome.elapsedMs());
                }
            }

            // 2. System 提示：角色段（DB 可编辑，保存即生效；空则用代码默认值兜底）+ 规则段（代码固定，与解析器耦合）
            //    + 对话历史（单条截断 + token 上限 + 图片标记剥离）
            String rolePart = resolveSystemPrompt(agent);
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
                    .append(relatedPromptLine());
            // 技能（Skills）渐进披露：只放「技能名 + 描述」清单，正文由模型按需 readSkill 取回。
            // 清单为空的段落不追加（没装技能时对提示词零影响）；注入的是本用户自己的技能。
            if (skillOn(agent)) {
                // 智能体级筛选：skills 为 null → 注入全部启用技能；空串 → 一个都不注入；逗号串 → 只注入这些。
                // 注入条件同时跟随 toolSkill 三态，与 enabledToolCallbacks 里 readSkill 的开关保持一致，
                // 否则会出现「清单里列出了技能、却没有读它的工具」的矛盾状态。
                Set<String> skillRefs = agent == null ? null : scopeOf(agent.getSkills());
                RefScope skillScope = resolveRefs(skillRefs, userId);
                Set<String> onlySkills = skillScope.names();
                // fail-loud：智能体的「指定技能」是引用而非普通名字——
                // ①引用指向别人的个人技能：直接不可用（个人资产不外借），绝不拿当前用户的同名技能顶替；
                // ②引用指向的技能在当前用户名下不存在/已停用：说明该智能体的技能意图对这个人根本不成立。
                // 两种情况都必须说出来，静默当成"没装技能"会让人误以为智能体能力已生效。
                // 空集合=智能体显式「不使用任何技能」，是明确意图，不算缺失。
                // 注意判定用「引用集合」而非解析后的名字集合：智能体只指定了别人的技能时，
                // 解析结果会是空集合，用后者判断会把「属于其他用户」的提示也一起吞掉。
                if (skillRefs != null && !skillRefs.isEmpty()) {
                    if (!skillScope.notMine().isEmpty()) {
                        addDegradation(degradations, degradedCodes, "agentSkillNotMine",
                                "智能体指定的技能 " + String.join("、", skillScope.notMine())
                                        + " 属于其他用户（技能是个人资产），对你不可用");
                    }
                    Set<String> haveSkills = skillService.listWithState(userId).stream()
                            .filter(st -> !st.disabled())
                            .map(st -> st.skill().name())
                            .collect(java.util.stream.Collectors.toSet());
                    List<String> missSkills = (onlySkills == null ? Set.<String>of() : onlySkills).stream()
                            .filter(n -> !haveSkills.contains(n)).toList();
                    if (!missSkills.isEmpty()) {
                        addDegradation(degradations, degradedCodes, "agentSkillUnavailable",
                                "智能体指定的技能 " + String.join("、", missSkills)
                                        + " 在你名下不存在或已停用（技能是个人资产），本轮未生效");
                        log.warn("[SKILL] uid={} 智能体 {} 指定的技能未命中: {}", userId,
                                agent == null ? "-" : agent.getId(), missSkills);
                    }
                }
                String skillBlock = skillService.promptBlock(userId,
                        configService.getInt("skill.injectMaxChars", 1200), onlySkills);
                if (!skillBlock.isEmpty()) {
                    system.append("\n\n").append(skillBlock);
                    log.info("[SKILL] 已注入技能清单（{} 个启用技能）",
                            skillBlock.split("\n- ").length - 1);
                }
            }
            // 用户本轮主动选用的技能：全文注入（区别于上面的清单渐进披露），与全局/智能体的技能开关无关——
            // 用户显式选择是明确的当轮意图，优先于智能体的技能范围筛选
            if (!userSkillText.isBlank()) {
                system.append(userSkillText);
            }
            // SubAgent 并行检索要点：作为补充资料段（不占 [N] 引用编号空间，仅辅助生成）
            if (subOutcome != null && !subOutcome.digestText().isBlank()) {
                system.append("\n\n【并行检索要点】\n").append(subOutcome.digestText());
                stageMs.put("subagent", subOutcome.elapsedMs());
            }
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
            // 用户上传附件文本拼入问题（主 LLM 结合附件内容回答）
            if (!attachmentText.isBlank()) {
                userQuestion.append("\n\n用户上传了附件，内容如下（请结合附件内容回答问题，引用时注明来自哪个附件）：\n")
                        .append(attachmentText);
            }
            // 思考链注入：把深度思考的推理过程（截断）作为参考注入，让"想过的"作用于"答"；
            // 明确说明必须以参考资料为准，思考只是辅助拆解
            if (!thinkingInject.isBlank()) {
                userQuestion.append("\n\n【你的思考过程】以下是本问题此前生成的深度分析过程，供参考其中的拆解与判断，"
                        + "但最终回答必须以下方参考资料为准：\n").append(thinkingInject);
            }

            // 3. 价值驱动填充：预算 = min(窗口×系数−输出, 成本上限)；减去 system/问题固定部分后，按相关度累积填充知识块
            int budget = resolveContextBudget(resolvedModel);
            int fixedTokens = TokenCounter.estimate(system.toString()) + TokenCounter.estimate(userQuestion.toString());
            int remainTokens = Math.max(configService.getInt("chat.remainTokenFloor", 800), budget - fixedTokens);

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
            // 子代理命中优先级仅次于检索命中（针对性视角检索，价值高于部分普通召回）；同样受智能体知识库范围约束
            if (subOutcome != null) {
                for (HybridRetrievalService.Hit h : subOutcome.hits()) {
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
                Matcher matcher = IMG_PLACEHOLDER_PATTERN.matcher(text);
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
            // 检索 + 重排 + 上下文填充全部完成（生成前的最后一个阶段）
            stageMs.put("retrieve", System.currentTimeMillis() - startTime);

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
            AnswerStreamState st = new AnswerStreamState(sessionId, question, userId, emitter,
                    imgIndex, imgDescIndex, sources, userImgs, startTime, queryForLog, thinkingHolder,
                    degradations, degradedCodes, retrievedJson);
            st.docFileNames = fileNameMap; // 工具命中注册来源时取文件名（悬浮提示/引用弹窗展示用）
            st.docMetaCache = documentMetaCache; // 映射覆盖不到的文档（工具本轮首次命中）按需补查
            st.toolScopeKbIds = scopeKbIds; // 精确检索工具与主链路同库界（库隔离）
            st.toolScopeDocIds = scopeDocIds;
            st.model = resolvedModel; // 本轮生效模型（会话覆盖 > 个人默认）
            st.deepThink = useDeepThink; // 归一后的深度思考（按生效模型能力 + 用户开关）
            st.userAttachments = attachmentsMeta; // 附件元信息（随用户消息持久化，气泡回显）
            // Token 消耗可视化回填：上下文实际用量/预算/填充块数（输出侧在 done 时用回答正文估算）
            st.contextTokens = usedTokens + fixedTokens;
            st.budgetTokens = budget;
            st.contextHits = docNo - 1;
            // 编排视图：回填分支最终状态（subOutcome.branches 来自编排完成后的 ctx.branches，含各分支命中数）
            if (subOutcome != null && !subOutcome.branches().isEmpty()) {
                st.subagentBranches = subOutcome.branches();
            }
            st.subagentRoute = subagentRouteInfo;
            // 合并主流程已记录的分段（改写 / 检索），后续生成与自检由流回调继续写入 st.stageMs
            st.stageMs.putAll(stageMs);
            st.disposableRef.set(buildAnswerStream(system.toString(), user, st, agent));

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
     * 判断并返回本次问答启用的工具回调列表（统一 ToolCallback 形态）。
     * 仅当 tool.enabled（总开关）开启时才暴露工具；各子工具开关决定具体暴露哪些。
     * MCP 工具取自当前用户名下的 server（外部 server 连接失败自动跳过）。
     * 内置 @Tool 对象经 ToolCallbacks.from() 转成 MethodToolCallback——注意 ChatClient 的
     * .tools() 只接受 @Tool 注解对象，传 ToolCallback 实例会抛 IllegalStateException
     * （"No @Tool annotated methods found... use .toolCallbacks() instead"），故统一走 .toolCallbacks()。
     * 全部关闭时返回空列表（等价未配置工具，零侵入）。
     */
    /**
     * 启用工具列表（按智能体覆盖）。智能体工具开关为三态：agent 中显式设了 1/0 则强制覆盖，
     * 否则继承全局 tool.* 开关。工具总开关 tool.enabled 仍由全局控制（智能体不开关总闸）。
     * 技能与 MCP 已于 2026-09-26 下沉为个人资产，因此按 userId 取：只读**这个人**的技能、只连**这个人**的 MCP。
     */
    private java.util.List<org.springframework.ai.tool.ToolCallback> enabledToolCallbacks(Agent agent, String userId,
                                                                                          AnswerStreamState st) {
        java.util.List<org.springframework.ai.tool.ToolCallback> callbacks = new ArrayList<>(4);
        if (!configService.getBoolean("tool.enabled")) {
            return callbacks;
        }
        if (toolOn(agent, "tool.knowledgeRetrieval.enabled", agent == null ? null : agent.getToolKnowledge())) {
            callbacks.addAll(java.util.Arrays.asList(
                    org.springframework.ai.support.ToolCallbacks.from(knowledgeRetrievalTool)));
        }
        if (toolOn(agent, "tool.builtin.enabled", agent == null ? null : agent.getToolBuiltin())) {
            // 具体项筛选：agent.builtinTools 为 null → 挂全部内置工具；否则只挂选中的那几个（按工具名匹配）
            Set<String> onlyBuiltin = agent == null ? null : scopeOf(agent.getBuiltinTools());
            for (org.springframework.ai.tool.ToolCallback cb :
                    org.springframework.ai.support.ToolCallbacks.from(builtinTools)) {
                if (onlyBuiltin == null || onlyBuiltin.contains(cb.getToolDefinition().name())) {
                    callbacks.add(cb);
                }
            }
        }
        // 技能取回工具（Skills 渐进披露的取回端）：智能体未指定时按本人是否有可用技能判定。
        // 技能无全局开关了（谁装谁用、停用由本人控），工具实例按本次问答用户创建——技能是个人资产，不能跨账户读取。
        boolean skillToolOn = (agent != null && agent.getToolSkill() != null)
                ? agent.getToolSkill() == 1
                : configService.getBoolean("tool.enabled");
        if (skillToolOn) {
            callbacks.addAll(java.util.Arrays.asList(
                    org.springframework.ai.support.ToolCallbacks.from(new SkillTools(skillService, userId))));
        }
        if (toolOn(agent, "tool.artifact.enabled", agent == null ? null : agent.getToolArtifact())) {
            callbacks.addAll(java.util.Arrays.asList(
                    org.springframework.ai.support.ToolCallbacks.from(presentArtifactTool)));
        }
        // MCP 外部工具（工具生态层）：连的是**当前用户**登记的 server（连接池按 uid 分池），失败自动跳过
        if (agent == null || agent.getToolMcp() == null || agent.getToolMcp() == 1) {
            // 具体项筛选：agent.mcps 为 null → 用该用户全部已启用 server；否则只取选中的那几个
            Set<String> mcpRefs = agent == null ? null : scopeOf(agent.getMcps());
            RefScope mcpScope = resolveRefs(mcpRefs, userId);
            Set<String> onlyMcp = mcpScope.names();
            // 与技能同理（MCP Server 是个人资产、按引用精确绑定）：
            // 别人的服务不顶替、自己没登记的要说出来，否则"智能体挂了这个外部工具"会静默变成"什么都没挂"。
            // 空集合=显式不使用，不算缺失。判定用引用集合（理由同技能：只指定了别人的服务时也要提示）。
            if (mcpRefs != null && !mcpRefs.isEmpty()) {
                if (!mcpScope.notMine().isEmpty()) {
                    addDegradation(st.degradations, st.degradedCodes, "agentMcpNotMine",
                            "智能体指定的 MCP 服务 " + String.join("、", mcpScope.notMine())
                                    + " 属于其他用户（MCP 是个人资产），对你不可用");
                }
                Set<String> haveMcp = mcpClientService.serverNames(userId);
                List<String> missMcp = (onlyMcp == null ? Set.<String>of() : onlyMcp).stream()
                        .filter(n -> !haveMcp.contains(n)).toList();
                if (!missMcp.isEmpty()) {
                    addDegradation(st.degradations, st.degradedCodes, "agentMcpUnavailable",
                            "智能体指定的 MCP 服务 " + String.join("、", missMcp)
                                    + " 你未登记或已停用（MCP 是个人资产），本轮未接入");
                    log.warn("[MCP] uid={} 智能体 {} 指定的服务未命中: {}", userId,
                            agent == null ? "-" : agent.getId(), missMcp);
                }
            }
            try {
                callbacks.addAll(mcpClientService.toolCallbacks(userId, onlyMcp));
            } catch (Exception e) {
                log.warn("[MCP] 加载外部工具失败（跳过，不影响问答）: {}", e.getMessage());
            }
        }
        // 可观测性：本次问答暴露了哪些工具（空则不打印；模型调不调用由模型决策，但"挂了什么"要可见）
        if (!callbacks.isEmpty()) {
            String names = callbacks.stream()
                    .map(cb -> cb.getToolDefinition().name())
                    .collect(java.util.stream.Collectors.joining(", "));
            log.info("[TOOL] 本次启用 {} 个工具: [{}]", callbacks.size(), names);
        }
        return callbacks;
    }

    /**
     * 工具调用过程追踪：把启用列表里的 ToolCallback 包一层（call 前后发 tool_status SSE（start/done/error）
     * 并记录耗时与结果摘要），供 done 事件汇总与历史持久化（工具调用状态展示）。
     * 返回 ToolCallback[]（供 ChatClient .toolCallbacks() 使用）。
     */
    private org.springframework.ai.tool.ToolCallback[] instrumentTools(
            java.util.List<org.springframework.ai.tool.ToolCallback> rawTools, AnswerStreamState st) {
        List<org.springframework.ai.tool.ToolCallback> wrapped = new ArrayList<>(rawTools.size());
        for (org.springframework.ai.tool.ToolCallback cb : rawTools) {
            wrapped.add(new org.springframework.ai.tool.ToolCallback() {
                @Override
                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return cb.getToolDefinition();
                }

                @Override
                public String call(String toolInput) {
                    return cb.call(toolInput);
                }

                @Override
                public String call(String toolInput, org.springframework.ai.chat.model.ToolContext toolContext) {
                    String name = cb.getToolDefinition().name();
                    long begin = System.currentTimeMillis();
                    recordToolStatus(st, name, toolInput, "start", null, 0);
                    // 精确检索工具：注入来源注册器——命中块注册进当前流 sources 续编引用编号，
                    // 工具文本改【引用N】提示模型按编号标注，前端角标悬浮/引用弹窗因此可溯源；
                    // 同步注入本轮检索范围——工具与主链路同库界，不得越过智能体知识库绑定检索
                    boolean kbTool = "searchKnowledge".equals(name);
                    if (kbTool) {
                        KnowledgeRetrievalTool.setSourceRegistrar(st::registerToolSource);
                        KnowledgeRetrievalTool.setKbScope(st.toolScopeKbIds, st.toolScopeDocIds);
                    }
                    try {
                        String result = cb.call(toolInput, toolContext);
                        recordToolStatus(st, name, toolInput, "done", result, System.currentTimeMillis() - begin);
                        return result;
                    } catch (Exception e) {
                        recordToolStatus(st, name, toolInput, "error", e.getMessage(), System.currentTimeMillis() - begin);
                        throw e;
                    } finally {
                        if (kbTool) {
                            KnowledgeRetrievalTool.clearSourceRegistrar();
                            KnowledgeRetrievalTool.clearKbScope();
                        }
                    }
                }
            });
        }
        return wrapped.toArray(new org.springframework.ai.tool.ToolCallback[0]);
    }

    /** 记录一条工具状态：实时 SSE tool_status 事件 + AnswerStreamState.toolCalls 累积（done 汇总与持久化用） */
    private void recordToolStatus(AnswerStreamState st, String name, String input,
                                  String status, String resultOrError, long elapsedMs) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", name);
        rec.put("status", status);
        rec.put("elapsedMs", elapsedMs);
        // 入参/结果截断（防超长工具 I/O 撑爆 SSE 与库）
        String argsBrief = input == null ? "" : input.substring(0, Math.min(200, input.length()));
        rec.put("args", argsBrief);
        if (resultOrError != null) {
            String brief = resultOrError.substring(0, Math.min(200, resultOrError.length()));
            rec.put(status.equals("error") ? "error" : "result", brief);
        }
        // 只把终态（done/error）记入持久化列表：start 仅实时下发（前端转圈显示），
        // 否则快照里 start/done 成对存在，前端 done 汇总覆盖后工具状态行会出现重复双行
        if (!"start".equals(status)) {
            st.toolCalls.add(rec);
        }
        // 后端日志同步留痕（与 [RAG]/[CTX] 等阶段日志同级可观测）：开始/完成/失败各一行，结果截断防爆量
        if ("start".equals(status)) {
            log.info("[TOOL] {} 调用开始 args={}", name, argsBrief);
        } else if ("error".equals(status)) {
            log.warn("[TOOL] {} 调用失败 ({}ms) error={}", name, elapsedMs, rec.get("error"));
        } else {
            log.info("[TOOL] {} 调用完成 ({}ms) result={}", name, elapsedMs,
                    resultOrError == null ? "" : resultOrError.substring(0, Math.min(160, resultOrError.length())).replace('\n', ' '));
        }
        try {
            st.emitter.send(SseEmitter.event()
                    .name("tool_status")
                    .data("{\"type\":\"tool_status\",\"content\":" + JSON.toJSONString(rec)
                            + ",\"sessionId\":\"" + st.sessionId + "\"}"));
        } catch (Exception e) {
            log.debug("[TOOL-STATUS] SSE 下发失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /**
     * 构建并订阅主 LLM 流式回答（H2：未输出任何 token 时中断自动重试，次数 chat.streamRetryCount 可配）。
     * 可变状态与 complete 回调依赖收敛在 AnswerStreamState；重试时重建全新流并丢弃旧缓冲。
     */
    private Disposable buildAnswerStream(String system, String user, AnswerStreamState st, Agent agent) {
        SseEmitter emitter = st.emitter;
        // 登记产物 emitter：供 PresentArtifactTool 在流式执行中实时下发 artifact 事件（结束/出错时清理）
        artifactService.registerEmitter(st.sessionId, emitter);
        return chatClient.prompt()
                .system(system)
                .user(user)
                // 模型配置界面：per-request 动态覆盖模型名与温度（保存即生效）；maxTokens 限制输出长度（防失控长文/成本）
                // st.model 为本轮解析好的模型（引用或遗留名，供应商路由由 DynamicOpenAiChatModel 按引用完成）
                .options(OpenAiChatOptions.builder()
                        .model(st.model)
                        .temperature(configService.getDouble("chat.temperature"))
                        .maxTokens(configService.getInt("context.maxOutputTokens"))
                        .build())
                // 工具调用（Function Calling）：默认关闭（tool.* 配置）；总开关+各子工具开关均开启时，
                // 模型可在回答中主动调用工具（精确检索知识库、交付文件产物），补充主链路未召回的上下文。
                // 传空数组等价未配置工具，不影响现有行为（零侵入）。
                // instrumentTools 包装：工具执行前后发 tool_status SSE 并记录过程（状态展示）。
                // 必须用 .toolCallbacks()：.tools() 只接受 @Tool 注解对象，传 ToolCallback 实例会抛
                // IllegalStateException（Spring AI 1.1.8 实测坑）。
                .toolCallbacks(instrumentTools(enabledToolCallbacks(agent, st.userId, st), st))
                // 工具上下文：把当前会话 ID 注入，供产物交付等工具定位会话并实时下发 SSE
                .toolContext(java.util.Map.of(PresentArtifactTool.CTX_SESSION_ID, st.sessionId))
                .stream()
                // 用 chatResponse 而非 content：流式中顺便捕获网关返回的真实 token usage（部分兼容网关
                // 在末块 metadata.usage 里给出 completion_tokens；拿不到则回落 TokenCounter 估算）。
                // 用 map 抽出 content 字符串，下游 doOnNext 逻辑（related 缓冲/剥离/发送）完全不变。
                .chatResponse()
                .map(resp -> {
                    org.springframework.ai.chat.metadata.Usage usage = resp.getMetadata() == null
                            ? null : resp.getMetadata().getUsage();
                    if (usage != null) {
                        Integer completion = usage.getCompletionTokens();
                        if (completion != null && completion > 0) st.realOutputTokens = completion;
                        Integer prompt = usage.getPromptTokens();
                        if (prompt != null && prompt > 0) st.realPromptTokens = prompt;
                    }
                    Object output = resp.getResult() == null ? null : resp.getResult().getOutput();
                    String delta = (output instanceof org.springframework.ai.chat.messages.AssistantMessage am
                            ? (am.getText() == null ? "" : am.getText()) : "");
                    return delta;
                })
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
                        st.disposableRef.set(buildAnswerStream(system, user, st, agent));
                        return;
                    }
                    log.error("Stream error: {} -> {}", error.getClass().getSimpleName(), root.toString());
                    String msg = (root instanceof java.net.ConnectException)
                            ? "无法连接 AI 服务，请检查网络或 API 地址"
                            : "AI 回复失败，请稍后重试";
                    st.degradations.add(Map.of("code", "streamError", "msg", "模型输出中断：" + msg));
                    sendSseEvent(emitter, "error", msg, st.sessionId);
                    completeEmitter(emitter);
                    artifactService.unregisterEmitter(st.sessionId);
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
                    // 生成完成（引用自检前的最后一步）
                    st.stageMs.put("generate", System.currentTimeMillis() - st.startTime);
                    // 引用语义一致性自检（深度防线）：编号没越界 ≠ 内容被支撑——LLM 可能引用了一个块，
                    // 但对应句子的结论与该块无关（编号正确、语义不符）。把每个 [N] 的"前文句子"与其
                    // 来源 snippet 打包给 LLM 判"是否支撑"，不支撑的剔除标记、来源同步裁剪并重编 ref。
                    // 一次额外调用，超时/失败/无引用跳过（保持原回答）；空前文（无法界定句子）的引用放行。
                    if (configService.getBoolean("chat.citationCheckEnabled") && !sources.isEmpty()) {
                        try {
                            CitationCheckResult ccr = citationConsistencyCheck(answer, sources, st.question, st.model);
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
                        st.stageMs.put("citation", System.currentTimeMillis() - st.startTime);
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
                            }
                            // 编排视图：分支最终状态 + 按需委派路由结果随 retrieved 持久化（历史回显用）
                            if (!st.subagentBranches.isEmpty()) {
                                rj.put("branches", st.subagentBranches);
                            }
                            if (st.subagentRoute != null) {
                                rj.put("route", st.subagentRoute);
                            }
                            finalRetrievedJson = JSON.toJSONString(rj);
                        }
                    } catch (Exception ignored) {
                    }

                    // 产物交付：本轮生成的文件清单（原始 URL 落库，展示层签名；done 事件与消息持久化共用）
                    List<Map<String, Object>> sessionArtifacts = artifactService.takeArtifacts(st.sessionId);

                    // 工具调用过程（状态展示）：done 汇总 + 随消息持久化（tool_status SSE 已实时下发）
                    List<Map<String, Object>> toolCallSnapshot = new ArrayList<>(st.toolCalls);
                    String toolCallsJson = toolCallSnapshot.isEmpty() ? null : JSON.toJSONString(toolCallSnapshot);

                    // 记录对话历史（含图片/附件与引用来源），拿到消息ID供前端反馈
                    String sourcesJson = sources.isEmpty() ? null : JSON.toJSONString(sources);
                    List<String> userImgUrls = st.userImgs.stream().map(UserImageService.UserImage::url).toList();
                    String attachmentsJson = (st.userAttachments == null || st.userAttachments.isEmpty())
                            ? null : JSON.toJSONString(st.userAttachments);
                    // 10 参重载（含 attachments）：显式传 null 占位，避免误绑定到 thinking 参数的旧重载
                    sessionService.appendMessage(st.sessionId, "user", st.question,
                            userImgUrls.isEmpty() ? null : userImgUrls, null,
                            null, null, null, null, attachmentsJson);
                    String messageId = sessionService.appendMessage(st.sessionId, "assistant", answer,
                            finalImgs, sourcesJson, st.thinkingHolder[0], finalRetrievedJson,
                            sessionArtifacts.isEmpty() ? null : JSON.toJSONString(sessionArtifacts),
                            toolCallsJson);

                    // 异步落问答日志（不阻塞 SSE 完成）
                    List<String> hitDocIds = sources.stream().map(s -> String.valueOf(s.get("docId"))).toList();
                    qaLogService.logAsync(st.sessionId, st.question, answer, hitDocIds,
                            !st.sources.isEmpty(), System.currentTimeMillis() - st.startTime,
                            st.queryForLog, st.stageMs.isEmpty() ? null : JSON.toJSONString(st.stageMs),
                            st.deepThink);

                    // done 事件：引用来源/相关推荐/消息ID + 校验修正后的内容/图片 + 思考全文 + 本轮全部降级事件（fail-loud）
                    Map<String, Object> donePayload = new LinkedHashMap<>();
                    // 引用来源内的 images 同样动态签名（引用弹窗直接加载；原始 URL 存库，签名仅作用于下发副本）
                    donePayload.put("sources", imageUrlSigner.signSourceImages(sources));
                    donePayload.put("related", related);
                    donePayload.put("messageId", messageId);
                    donePayload.put("finalContent", answer);
                    // 必须与前面的 image 事件一致地动态签名：前端 onDone 会用 finalImages 覆盖流式期间已签名的
                    // images，若此处下发原始 URL，回答完成瞬间图片立即 401（"图片链接无效或已过期"），
                    // 刷新页面经 getHistory 重新签名才恢复。注意 finalImgs 本身已用于落库/缓存（存原始 URL），
                    // 故仅对下发的副本签名，不改动原列表。
                    donePayload.put("finalImages",
                            finalImgs.stream().map(imageUrlSigner::signUrl).toList());
                    donePayload.put("thinking", st.thinkingHolder[0]);
                    donePayload.put("degradations", st.degradations);
                    // 编排视图：分支最终状态（前端 onDone 用它覆盖实时 subagent 事件、收敛到最终态）
                    if (!st.subagentBranches.isEmpty()) {
                        donePayload.put("subagentBranches", st.subagentBranches);
                    }
                    // 按需委派路由结果（前端展示"从 N 个候选中挑选 M 个"；picked=0 时提示未咨询）
                    if (st.subagentRoute != null) {
                        donePayload.put("subagentRoute", st.subagentRoute);
                    }
                    // 产物交付汇总（流式 artifact 事件已实时下发；此处兜底保证不丢失，url 已重新签名）
                    donePayload.put("artifacts", sessionArtifacts.isEmpty()
                            ? List.of() : artifactService.takeArtifacts(st.sessionId));
                    // 工具调用过程汇总（实时 tool_status 已逐条下发；此处兜底，前端 onDone 覆盖渲染）
                    donePayload.put("toolCalls", toolCallSnapshot);
                    // Token 消耗可视化（1.9）：上下文实际/预算/填充块数 + 输出。
                    // 输出优先用网关真实 usage（部分兼容网关末块 metadata.usage 携带），拿不到回落本地估算；
                    // 真实值不额外加估算的 10% 余量（估算才需余量防超窗，实报应如实）。
                    Map<String, Object> tokens = new LinkedHashMap<>();
                    boolean realOutput = st.realOutputTokens > 0;
                    int outputTokens = realOutput ? st.realOutputTokens : TokenCounter.estimate(answer);
                    int promptTokens = st.realPromptTokens > 0 ? st.realPromptTokens : st.contextTokens;
                    tokens.put("context", st.contextTokens);
                    tokens.put("budget", st.budgetTokens);
                    tokens.put("hits", st.contextHits);
                    tokens.put("output", outputTokens);
                    tokens.put("prompt", promptTokens);
                    tokens.put("outputIsReal", realOutput);
                    tokens.put("total", promptTokens + outputTokens);
                    donePayload.put("tokens", tokens);
                    sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), st.sessionId);
                    completeEmitter(emitter);
                    artifactService.unregisterEmitter(st.sessionId);
                })
                .subscribe();
    }

    /**
     * 单次回答的流式状态与 complete 回调依赖（H2 重试重建流时复用同一状态，旧缓冲被清空）。
     */
    private static final class AnswerStreamState {
        final String sessionId;
        final String question;
        /** 本轮所属用户 uid：技能/MCP 都是个人资产，取工具与取技能时只认它 */
        final String userId;
        final SseEmitter emitter;
        final Map<Integer, String> imgIndex;
        final Map<Integer, String> imgDescIndex;
        final List<Map<String, Object>> sources;
        final List<UserImageService.UserImage> userImgs;
        final long startTime;
        /** 分段耗时（距开始的累计 ms）：改写/检索由主流程 putAll 合并进来，生成/自检由流回调写入 */
        final Map<String, Long> stageMs = new LinkedHashMap<>();
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
        /** 本轮问答的工具调用过程记录（name/args摘要/status/耗时），实时发 tool_status SSE + done 汇总 + 持久化 */
        final java.util.List<Map<String, Object>> toolCalls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /** 引用文件名映射（docId→fileName）：主链路构建后回填，供工具命中注册来源时取文件名 */
        volatile Map<String, String> docFileNames;
        /** 精确检索工具的检索范围（与主链路同库界，工具执行线程内生效）：kbIds 限定库，docIds 后过滤命中 */
        volatile java.util.Collection<String> toolScopeKbIds;
        volatile Set<String> toolScopeDocIds;
        /** 本轮生效模型（会话覆盖 > 个人默认；智能体不绑定模型。主链路解析后回填，生成流按此发送） */
        volatile String model;
        /** 附件元信息（[{name,mime,size}]，主链路解析后回填）：随用户消息持久化，气泡回显 */
        volatile List<Map<String, Object>> userAttachments;
        /** 归一后的深度思考（生效模型能力 + 用户开关）；随 done 写 QA 日志 deep_think */
        volatile boolean deepThink;
        /**
         * 文档元数据缓存：主链路的 docFileNames 只覆盖「初始检索命中的文档」，
         * 而精确检索工具可能命中本轮首次出现的文档（映射里没有）→ 用它按需补查，避免引用显示成"未知文档"。
         */
        DocumentMetaCache docMetaCache;
        /** 本轮上下文 token 用量（主链路构建后回填）：实际/预算/填充块数，供 done 下发做 Token 消耗可视化 */
        volatile int contextTokens;
        volatile int budgetTokens;
        volatile int contextHits;
        /** 网关返回的真实 usage（部分兼容网关末块携带；拿不到保持 0，回落 TokenCounter 估算） */
        volatile int realPromptTokens;
        volatile int realOutputTokens;
        /** 编排视图：各子代理分支的最终状态（主链路编排完成后回填，随 done 下发并持久化） */
        volatile java.util.List<Map<String, Object>> subagentBranches = List.of();
        /** 按需委派的路由结果（{candidates,picked,names}；未启用路由时为 null），随 done 下发并持久化 */
        volatile Map<String, Object> subagentRoute = null;

        AnswerStreamState(String sessionId, String question, String userId, SseEmitter emitter,
                          Map<Integer, String> imgIndex, Map<Integer, String> imgDescIndex,
                          List<Map<String, Object>> sources, List<UserImageService.UserImage> userImgs,
                          long startTime, String queryForLog, String[] thinkingHolder,
                          List<Map<String, String>> degradations, Set<String> degradedCodes, String retrievedJson) {
            this.sessionId = sessionId;
            this.question = question;
            this.userId = userId;
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

        /**
         * 精确检索工具命中注册为引用来源：续编 ref 编号（与主链路 [1..N] 同一编号空间），
         * 同块已注册/已在主链路则复用原编号不重复注册；返回分配的编号供工具文本【引用N】提示模型标注。
         * origin=TOOL 供前端区分工具来源；synchronized 防工具线程与流回调并发追加。
         */
        int registerToolSource(HybridRetrievalService.Hit h, String snippet) {
            synchronized (sources) {
                if (h.knowledgeId() != null) {
                    for (Map<String, Object> s : sources) {
                        if (h.knowledgeId().equals(s.get("knowledgeId"))) {
                            return (Integer) s.get("ref");
                        }
                    }
                }
                int ref = sources.size() + 1;
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
                sources.add(src);
                return ref;
            }
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
     * 模型窗口按本轮生效模型（resolvedModel）子串匹配 model-windows 映射，未匹配用默认窗口
     * 参数走 ConfigService（DB 设置页保存即生效，yml 兜底）
     */
    // ---- 智能体（4.1）覆盖解析辅助 ----

    /**
     * 智能体解析：agentId="auto" → 自动派遣（当轮生效模型按名称+描述从可见主智能体中挑选；
     * 单候选直接命中；超时/失败/无命中回落默认智能体，agent.autoDispatch 关闭时直接回落默认）。
     * 派遣结果经 SSE agent_dispatched 下发（路由过程对用户可见）。
     * 其余 agentId 走 agentService.get（无效/不可读返回 null，继承全局）。
     */
    private Agent resolveAgent(String agentId, String question, String resolvedModel,
                               String sessionId, SseEmitter emitter) {
        if (agentId == null || agentId.isBlank()) return null;
        if (!"auto".equals(agentId)) return agentService.get(agentId);
        List<Agent> candidates = agentService.dispatchCandidates();
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1 || !configService.getBoolean("agent.autoDispatch")) {
            Agent direct = candidates.size() == 1 ? candidates.get(0) : agentService.defaultAgent();
            emitDispatched(emitter, direct, candidates.size(), false, sessionId);
            return direct;
        }
        sendSseEvent(emitter, "stage", "正在派遣智能体…", sessionId);
        // 最近两轮对话参与路由（追问如"之前说的退款进度"要靠上下文才能派对）
        String recentContext = "";
        try {
            recentContext = buildHistoryText(sessionService.getRecentHistory(sessionId, 2));
        } catch (Exception e) {
            log.debug("[DISPATCH] 会话历史读取失败（无上下文路由）: {}", e.getMessage());
        }
        long t0 = System.currentTimeMillis();
        Agent picked = agentDispatchService.dispatch(question, candidates, resolvedModel, recentContext);
        boolean fallback = picked == null;
        if (fallback) picked = agentService.defaultAgent();
        log.info("[DISPATCH] 自动派遣: {} 个候选 → {}（{}，{}ms）", candidates.size(),
                picked == null ? "无（继承全局）" : picked.getName(),
                fallback ? "回落默认" : "路由命中", System.currentTimeMillis() - t0);
        emitDispatched(emitter, picked, candidates.size(), fallback, sessionId);
        return picked;
    }

    /** 派遣结果下发（复用编排卡片风格：候选数/命中/名称，前端在输入区上方展示） */
    private void emitDispatched(SseEmitter emitter, Agent picked, int candidates, boolean fallback, String sessionId) {
        if (picked == null) return;
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("candidates", candidates);
            info.put("id", picked.getId());
            info.put("name", picked.getName());
            info.put("description", picked.getDescription());
            info.put("fallback", fallback);
            sendSseEvent(emitter, "agent_dispatched", JSON.toJSONString(info), sessionId);
        } catch (Exception e) {
            log.debug("[DISPATCH] 派遣结果事件下发失败（不影响问答）: {}", e.getMessage());
        }
    }

    /**
     * 模型解析链（优先级从高到低）：会话级覆盖（聊天页手动切换）> 用户个人默认模型。
     * 值为引用（providerId/modelId）或遗留纯模型名均可，供应商网关路由由 DynamicOpenAiChatModel 按引用解析；
     * 智能体不再绑定聊天模型、全局 chat.model 兜底已移除——均未配置时返回空串，由调用方 fail-loud 引导配置。
     */
    private String resolveModel(String modelOverride, com.wisesoft.ai.model.User prefUser) {
        if (modelOverride != null && !modelOverride.isBlank()) return modelOverride.trim();
        if (prefUser != null && prefUser.getDefaultModel() != null && !prefUser.getDefaultModel().isBlank()) {
            return prefUser.getDefaultModel();
        }
        return "";
    }

    /** 个人偏好用户行（含三类个人默认模型）；匿名/未登录/查询失败返回 null（全部走空语义） */
    /**
     * 在流水线线程内装载本轮用户身份（uid / 部门 / 角色），供检索的文档可见性过滤按**真实用户**判定。
     * <p>背景：问答流水线跑在独立线程池、没有请求上下文，而 {@code HybridRetrievalService.loadNonVisibleDocIds()}
     * 是从 {@code RequestUser} 取身份的 ⇒ 只会拿到 anonymous。其后果是：配了共享范围
     * （{@code access_level=department/user}）的文档，对**包括被授权者在内**的所有人都判为不可见并被过滤——
     * 也就是"共享给我、或我自己限定范围的资料，在问答里检索不到"。
     * <p>身份从用户档案读，与调用线程无关：网页问答（Tomcat 线程）与定时任务（池线程，uid 由参数传入）
     * 走同一来源；代价是每轮多一次用户表主键查询（与 runChat 内取个人偏好那次同表主键查询同一量级）。
     *
     * @return 是否已装载（未装载时调用方不需要清理）
     */
    private boolean loadIdentity(String userId) {
        if (userId == null || userId.isBlank()
                || com.wisesoft.ai.util.RequestUser.ANONYMOUS.equals(userId)) {
            // 匿名池（历史兼容会话）：保持 anonymous 语义，不做任何提升
            return false;
        }
        try {
            com.wisesoft.ai.model.User u = userMapper.selectById(userId);
            if (u == null) return false;
            com.wisesoft.ai.util.RequestUser.set(u.getUid(), u.getDepartmentId(), u.getRole());
            return true;
        } catch (Exception e) {
            // 装载失败按匿名继续（不打断问答），但必须留痕：否则又变成"共享资料检索不到"且无迹可查
            log.warn("[FAIL-LOUD] 流水线线程装载用户身份失败（本轮检索可见性按匿名判定）uid={}: {}",
                    userId, e.getMessage());
            return false;
        }
    }

    private com.wisesoft.ai.model.User loadPrefUser(String userId) {
        if (userId == null || userId.isBlank() || com.wisesoft.ai.util.RequestUser.ANONYMOUS.equals(userId)) {
            return null;
        }
        try {
            return userMapper.selectById(userId);
        } catch (Exception e) {
            log.debug("[PREF] 个人偏好加载失败（视为未配置）: {}", e.getMessage());
            return null;
        }
    }

    /** 系统提示词：智能体显式填写则用智能体提示词，否则继承全局（空时回落代码默认值） */
    private String resolveSystemPrompt(Agent agent) {
        String p = (agent != null && agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank())
                ? agent.getSystemPrompt() : configService.get("chat.systemPrompt");
        return (p == null || p.isBlank()) ? properties.getSystemPrompt() : p;
    }

    /** 工具开关三态解析：智能体显式设了 1/0 则强制覆盖，否则继承全局开关 */
    private boolean toolOn(Agent agent, String globalKey, Integer agentFlag) {
        if (agent != null && agentFlag != null) return agentFlag == 1;
        return configService.getBoolean(globalKey);
    }

    /**
     * 技能开关三态解析（区别于 toolOn）：技能现在是**个人资产**，没有全局「总开关」——
     * 用户装了技能就是要用的，是否停用由用户在自己的技能列表里决定（见 SkillService.setDisabled）。
     * 因此智能体未显式指定时一律注入；只有智能体显式设了 0/1 才覆盖。
     */
    private boolean skillOn(Agent agent) {
        if (agent != null && agent.getToolSkill() != null) return agent.getToolSkill() == 1;
        return true;
    }

    /**
     * 解析主智能体委派的子智能体（4.1 与 4.3 打通）。
     * <p>未配置或配置为空 → 返回空列表，编排走原有的多视角策略；
     * 配置了 ID → 逐个加载，跳过已删除或已改回普通智能体的（避免残留 ID 造成空转）。</p>
     */
    private List<Agent> resolveSubAgents(Agent agent) {
        if (agent == null || agent.getSubAgentIds() == null || agent.getSubAgentIds().isBlank()) {
            return List.of();
        }
        Set<String> ids = scopeOf(agent.getSubAgentIds());
        if (ids == null || ids.isEmpty()) return List.of();
        List<Agent> subs = new ArrayList<>();
        for (String id : ids) {
            Agent s = agentService.get(id);
            if (s == null) {
                log.warn("[AGENT] 委派的子智能体 {} 已不存在（跳过）", id);
                continue;
            }
            if (!Integer.valueOf(1).equals(s.getIsSubagent())) {
                log.warn("[AGENT] 智能体 {} 已不是子智能体，不再作为委派对象（跳过）", id);
                continue;
            }
            subs.add(s);
        }
        if (subs.size() > 4) {
            log.warn("[AGENT] 委派子智能体 {} 个，超出并行上限，仅取前 4 个", subs.size());
            return subs.subList(0, 4);
        }
        return subs;
    }

    /**
     * 「具体项范围」字段解析（与主流智能体平台的资源选择语义一致）：
     * <ul>
     *   <li>null → null：跟随全局，不做具体项筛选；</li>
     *   <li>空串 → 空集合：显式一个都不用；</li>
     *   <li>"a,b" → {a, b}：只用这些。</li>
     * </ul>
     */
    private static Set<String> scopeOf(String v) {
        if (v == null) return null;
        if (v.isBlank()) return java.util.Collections.emptySet();
        return java.util.Arrays.stream(v.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 智能体上「个人资源引用」的解析结果。
     *
     * @param names   参与匹配的资源名（归属当前用户，或内置/裸名这类"人人都有"的引用）
     * @param notMine 明确属于**其他人**的引用（个人资产不外借，直接判不可用，不再拿同名资源顶上）
     */
    private record RefScope(Set<String> names, List<String> notMine) {
    }

    /**
     * 解析智能体上的个人资源引用（技能 / MCP 同构）。
     * <p>
     * 技能与 MCP 都是**个人资产**，而智能体是公共对象，裸名字不是全局唯一键——
     * 只按名字匹配会出现「使用者装了同名但内容不同的资源，被当成管理员指定的那个来用」（张冠李戴）。
     * 因此引用分两种形态：
     * <ul>
     *   <li>{@code {uid}/{name}}：精确绑定某个人的资源。归属人是当前用户 → 参与匹配；
     *       不是 → 直接判不可用（不拿当前用户的同名资源顶替）。</li>
     *   <li>裸 {@code name}：存量配置的弱匹配（按名字在当前用户自己的资源里找）；
     *       内置技能也走这条——它随版本分发、人人都有，按名字匹配才是对的。</li>
     * </ul>
     */
    private static RefScope resolveRefs(Set<String> refs, String uid) {
        if (refs == null) return new RefScope(null, List.of());
        Set<String> names = new LinkedHashSet<>();
        List<String> notMine = new ArrayList<>();
        for (String r : refs) {
            int slash = r.indexOf('/');
            if (slash < 0) {
                names.add(r);            // 裸名：内置技能 / 存量配置
                continue;
            }
            String owner = r.substring(0, slash).trim();
            String name = r.substring(slash + 1).trim();
            if (name.isEmpty()) continue;
            if (owner.isEmpty() || owner.equals(uid)) names.add(name);
            else notMine.add(name);      // 别人的个人资产：不可用
        }
        return new RefScope(names, notMine);
    }

    /** 智能体绑定的知识库集合（主链路检索与精确检索工具共用；null=智能体未绑定，不限库） */
    private static java.util.Collection<String> scopeKbIdsOf(Agent agent) {
        return (agent == null || agent.getKnowledgeBaseIds() == null || agent.getKnowledgeBaseIds().isBlank())
                ? null : KnowledgeBaseService.splitIds(agent.getKnowledgeBaseIds());
    }

    /**
     * 解析本轮允许检索的文档集合（返回 null = 不额外限制，仍受可见性约束）。
     * <p>两级范围，概念上不重叠：
     * <ol>
     *   <li><b>知识库级（主路径）</b>：智能体关联的 {@code knowledgeBaseIds} → 各库文档 ID 的并集。
     *       文档归谁由它属于哪个库决定，不需要再由智能体逐个指定文档。</li>
     *   <li><b>文档级细选（可选）</b>：{@code knowledgeScope} 非空时，与库级结果取交集，
     *       用于"库内只要某几个文档"的场景。</li>
     * </ol>
     * 注意：库 ID 配错时得到的是空集合，检索结果自然为空——**不会退化为全库**，
     * 避免配置错误静默放宽检索范围。
     */
    private Set<String> resolveScopeDocIds(Agent agent) {
        if (agent == null) return null;
        Set<String> byKb = null;
        String kbIds = agent.getKnowledgeBaseIds();
        if (kbIds != null && !kbIds.isBlank()) {
            Set<String> ids = KnowledgeBaseService.splitIds(kbIds);
            if (!ids.isEmpty()) byKb = knowledgeBaseService.docIdsOf(ids);
        }
        // 文档级细选：沿用原语义（null/空/all = 不限制）
        Set<String> fine = null;
        String scope = agent.getKnowledgeScope();
        if (scope != null && !scope.isBlank() && !"all".equalsIgnoreCase(scope.trim())) {
            fine = Arrays.stream(scope.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        }
        if (byKb != null && fine != null) {
            Set<String> merged = new java.util.HashSet<>(byKb);
            merged.retainAll(fine);
            return merged;
        }
        return byKb != null ? byKb : fine;
    }

    /** 知识库范围约束：scopeDocIds 为空（all）则原样返回；否则仅保留命中块中 docId 在范围内的 */
    private List<HybridRetrievalService.Hit> applyScope(List<HybridRetrievalService.Hit> hits, Set<String> scopeDocIds) {
        if (scopeDocIds == null || hits == null) return hits;
        List<HybridRetrievalService.Hit> scoped = hits.stream()
                .filter(h -> h.docId() != null && scopeDocIds.contains(h.docId()))
                .collect(Collectors.toList());
        if (scoped.size() < hits.size()) {
            log.info("[AGENT] 知识库范围约束：命中 {} → 范围内 {} 块", hits.size(), scoped.size());
        }
        return scoped;
    }

    private int resolveContextBudget(String resolvedModel) {
        String modelWindows = configService.get("context.modelWindows");
        int defaultWindow = configService.getInt("context.defaultWindowTokens");
        double safetyFactor = configService.getDouble("context.safetyFactor");
        int maxOutput = configService.getInt("context.maxOutputTokens");
        int costCap = configService.getInt("context.costCapTokens");

        int window = defaultWindow;
        String model = resolvedModel;
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
    private CitationCheckResult citationConsistencyCheck(String answer, List<Map<String, Object>> sources,
                                                         String question, String resolvedModel) {
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
     * 失败/超时/未提取到计划 → 返回 ok=false + 已收集思考增量（调用方用思考词元增强降级检索）
     * 思考长度护栏（maxThinkingChars）：超限中断思考流但保留已收集内容继续走计划提取，不整段丢弃
     */
    private DeepThinkResult runDeepThinking(String sessionId, String question, String imgDescText,
                                            String attachmentText,
                                            SseEmitter emitter, String resolvedModel) {
        String thinkingMode = configService.get("deepReasoning.thinkingMode");
        boolean enableThinking = configService.getBoolean("deepReasoning.enableThinking");
        boolean multiRetrieval = configService.getBoolean("deepReasoning.multiRetrieval");
        int timeoutMillis = configService.getInt("deepReasoning.timeoutMillis");
        int maxThinkingTokens = configService.getInt("deepReasoning.maxThinkingTokens");
        int maxThinkingChars = configService.getInt("deepReasoning.maxThinkingChars", 3000);

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
        // 附件内容随思考上下文（附件是回答素材，思考阶段就应看到）
        if (attachmentText != null && !attachmentText.isBlank()) {
            user.append("\n\n用户上传了附件，内容如下（仅用于辅助思考）：\n").append(attachmentText);
        }

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(resolvedModel)
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
                            // 思考长度护栏：超上限即中断思考流（保留已收集部分），避免刷爆上下文/token
                            if (maxThinkingChars > 0 && thinking.length() + delta.length() > maxThinkingChars) {
                                throw new ThinkingCappedException();
                            }
                            thinking.append(delta);
                            // 客户端断开：抛异常中止同步消费（blockLast），省余下思考输出；调用方检查点兜底不再检索
                            if (!sendSseEvent(emitter, "thinking", delta, sessionId)) {
                                throw new SseClientGoneException();
                            }
                        }
                    })
                    .blockLast(Duration.ofMillis(Math.max(1000, timeoutMillis)));

            return finishDeepThinking(thinking.toString(), question, sessionId, emitter, true);
        } catch (SseClientGoneException e) {
            log.info("[SSE] 客户端断开，深度思考终止: session={}", sessionId);
            return new DeepThinkResult(false, stripSearchBlock(thinking.toString(), "search"),
                    question, List.of(), "disconnected");
        } catch (ThinkingCappedException e) {
            // 思考长度达上限：用已收集内容继续（可能提取到计划则 ok，否则由调用方用思考词元增强降级）
            log.info("[DEEP-THINK] 思考长度达上限截断（{} 字），用已收集内容继续", thinking.length());
            return finishDeepThinking(thinking.toString(), question, sessionId, emitter, false);
        } catch (Exception e) {
            // 思考流异常/超时：若已收集内容含检索计划仍可用，否则降级（调用方用思考词元增强）
            log.warn("[FAIL-LOUD] 深度思考失败/超时，降级检索: {}", e.getMessage());
            return finishDeepThinking(thinking.toString(), question, sessionId, emitter, false);
        }
    }

    /**
     * 思考收尾公共逻辑：提取检索计划、剥离 <search> 块、下发 thinking_done。
     * ok = 流正常完成，或虽中断但已收集内容中提取到了有效检索计划（refinedQuery 非原问题或子问题非空）——
     * 这样长度截断/部分异常时思考内容不白费，能继续多路检索。
     */
    private DeepThinkResult finishDeepThinking(String rawThinking, String question, String sessionId, SseEmitter emitter, boolean streamOk) {
        SearchPlan plan = extractSearchPlan(rawThinking, question);
        String display = stripSearchBlock(rawThinking, plan.searchTag());
        boolean hasPlan = !plan.subQueries().isEmpty()
                || (plan.refinedQuery() != null && !plan.refinedQuery().equals(question));
        boolean ok = streamOk || hasPlan;
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", ok ? "ok" : "degraded");
        done.put("thinking", display);
        sendSseEvent(emitter, "thinking_done", JSON.toJSONString(done), sessionId);
        log.info("[DEEP-THINK] 思考结束 {} 字, ok={}, refined={}, subs={}", display.length(), ok, plan.refinedQuery(), plan.subQueries());
        return new DeepThinkResult(ok, display, plan.refinedQuery(), plan.subQueries(), null);
    }

    /** 思考流长度达上限的中断信号（内部异常，不走 fail-loud） */
    private static final class ThinkingCappedException extends RuntimeException {
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

    /**
     * 清除某通道的断开标记登记。**无客户端的收集型通道**（定时执行智能体用的 CollectingSseEmitter）
     * 用完必须主动调用：它不会触发 onCompletion（没有真实响应可完成），否则 ACTIVE_SSE 会一直
     * 持有该 emitter 引用不放。
     */
    static void forgetSseChannel(SseEmitter emitter) {
        if (emitter != null) {
            ACTIVE_SSE.remove(emitter);
        }
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

    /**
     * 用户本轮主动选用的技能（输入框「+」菜单）：把技能全文注入本轮 system prompt。
     * 与 promptBlock 的清单渐进披露互补：清单靠模型按需 readSkill，这里是用户显式点名、直接全文前置。
     * 只接受**本用户**名下存在且未停用的技能（按 frontmatter 名匹配，输入框技能列表同源）。
     */
    private String buildUserSkillText(String userId, List<String> skills) {
        if (skills == null || skills.isEmpty()) return "";
        Set<String> wanted = new HashSet<>(skills);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (SkillService.SkillState st : skillService.listWithState(userId)) {
            if (!wanted.contains(st.skill().name()) || st.disabled()) continue;
            String content = skillService.readContent(userId, st.skill().dirName());
            if (content.startsWith("错误：")) {
                log.warn("[SKILL] 用户指定技能 {} 内容读取失败，跳过: {}", st.skill().name(), content);
                continue;
            }
            sb.append("\n### 技能：").append(st.skill().name()).append("\n\n").append(content).append('\n');
            n++;
        }
        if (n == 0) return "";
        log.info("[SKILL] 用户指定技能注入 {} 个: {}", n, skills);
        return "\n\n【本轮指定技能】用户在本轮消息中主动选用了以下技能，请先完整阅读其说明，再严格按技能要求处理本轮问题："
                + sb;
    }

    /**
     * 「不使用知识库」分支（智能体 knowledgeDisabled=1）：纯角色对话，不跑改写/深度思考/检索/子代理编排。
     * 与闲聊分支的差异：① 多轮历史照常注入；② 用户手动 @ 的文档仍取块前置（手动指定优先于智能体配置）；
     * ③ 图片提问时图片描述随问题发给模型（多模态理解，不参与检索）。
     * 复用主回答流：sources/retrieved 均空 → 前端检索状态行与引用区天然不渲染。
     */
    private void runNoKnowledgeChat(String sessionId, String question, String userId,
                                    List<UserImageService.UserImage> userImgs,
                                    String imgDescText, String attachmentText, String userSkillText,
                                    List<Map<String, Object>> attachmentsMeta, SseEmitter emitter, long startTime,
                                    String[] thinkingHolder, List<Map<String, String>> degradations,
                                    Set<String> degradedCodes, Agent agent, Map<String, Long> stageMs,
                                    String resolvedModel) {
        try {
            // 角色段（与主链路同源）+ 明确告知模型本轮无参考资料、按自身知识作答
            StringBuilder system = new StringBuilder(resolveSystemPrompt(agent))
                    .append("\n\n【本轮对话说明】\n")
                    .append("本助手未启用知识库检索。请基于你自身的知识与对话上下文直接回答，")
                    .append("不要输出 [N] 来源标注（本轮没有参考资料）。");
            // 用户本轮主动选用的技能：与主链路口径一致，全文注入
            if (userSkillText != null && !userSkillText.isBlank()) {
                system.append(userSkillText);
            }
            List<Map<String, Object>> recentHistory = sessionService.getRecentHistory(sessionId,
                    configService.getInt("chat.historyRounds", 5));
            if (recentHistory == null) {
                addDegradation(degradations, degradedCodes, "historyFailed", "会话历史读取失败，本次无多轮记忆");
                recentHistory = List.of();
            }
            String historyText = buildHistoryText(recentHistory);
            if (!historyText.isEmpty()) {
                system.append("\n\n对话历史：\n").append(historyText);
            }

            // 拼装用户消息：问题 + 图片描述（本轮无知识库，无参考资料段）+ 附件内容
            StringBuilder userQuestion = new StringBuilder(question);
            if (imgDescText != null && !imgDescText.isBlank()) {
                userQuestion.append("\n\n用户上传了图片，图片内容描述如下（请结合图片内容回答问题）：\n").append(imgDescText);
            }
            if (attachmentText != null && !attachmentText.isBlank()) {
                userQuestion.append("\n\n用户上传了附件，内容如下（请结合附件内容回答问题）：\n").append(attachmentText);
            }
            String user = userQuestion.toString();

            if (clientDisconnected(emitter)) {
                log.info("[SSE] 客户端断开，终止本轮问答（无知识库分支，生成前）: session={}", sessionId);
                return;
            }
            sendSseEvent(emitter, "stage", "正在生成回答…", sessionId);
            stageMs.put("total", System.currentTimeMillis() - startTime);
            AnswerStreamState st = new AnswerStreamState(sessionId, question, userId, emitter,
                    new LinkedHashMap<>(), new HashMap<>(), new ArrayList<>(), userImgs,
                    startTime, question, thinkingHolder, degradations, degradedCodes, null);
            st.docMetaCache = documentMetaCache;
            // 本轮无知识库检索，但工具仍可能被模型调用：范围同样跟随智能体库绑定（库隔离）
            st.toolScopeKbIds = scopeKbIdsOf(agent);
            st.toolScopeDocIds = resolveScopeDocIds(agent);
            // 本轮生效模型（会话覆盖 > 个人默认）：不赋值会让 buildAnswerStream 发出无 model 的请求，
            // DynamicOpenAiChatModel 落到遗留全局网关且 model 为空 → 网关 400（2026-09-25 通用助手实测）
            st.model = resolvedModel;
            st.userAttachments = attachmentsMeta; // 附件元信息随用户消息持久化（气泡回显）
            st.contextTokens = 0;
            st.budgetTokens = 0;
            st.contextHits = 0;
            st.stageMs.putAll(stageMs);
            st.disposableRef.set(buildAnswerStream(system.toString(), user, st, agent));
            emitter.onCompletion(() -> st.disposeSafe());
            emitter.onTimeout(() -> {
                log.warn("[FAIL-LOUD] SSE 超时，回答被截断: session={}", sessionId);
                sendSseEvent(emitter, "warn", "回答超时已截断，请重试或缩短问题", sessionId);
                st.disposeSafe();
                completeEmitter(emitter);
            });
            emitter.onError(t -> st.disposeSafe());
        } catch (Exception e) {
            log.error("No-knowledge chat error", e);
            sendSseEvent(emitter, "error", "系统处理异常，请稍后重试", sessionId);
            completeEmitter(emitter);
        }
    }
}
