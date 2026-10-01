package com.wisesoft.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.wisesoft.ai.config.AppProperties;
import com.wisesoft.ai.dto.ChatRequest;
import com.wisesoft.ai.model.Agent;
import com.wisesoft.ai.model.Knowledge;
import com.wisesoft.ai.model.KnowledgeBase;
import com.wisesoft.ai.model.WorkflowRun;
import com.wisesoft.ai.service.websearch.WebSearchResult;
import com.wisesoft.ai.service.websearch.WebSearchTools;
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
     * 所有降级事件（无命中/改写失败/图片剔除/未标注引用/缓存命中等）统一由 chat.retrievalDebugEnabled 开关控制
     * （与检索调试入口/归属徽标同属调试显示，一个开关统管），默认关闭：回答区不展示任何降级提示，
     * 全部只进 [FAIL-LOUD] 日志；排障时开启「检索调试入口」才在回答中展示。
     */
    /** 引用角标 [N]（用于完成阶段校验编号是否超出来源范围，剔除 LLM 编造的无效引用） */
    private static final Pattern CITE_PATTERN = Pattern.compile("\\[(\\d+)]");

    /** fail-loud：按 code 去重添加降级事件（全部 debug 级，由 chat.retrievalDebugEnabled 开关控制，默认不展示） */
    private void addDegradation(List<Map<String, String>> list, Set<String> codes, String code, String msg) {
        if (!codes.add(code)) return;
        if (configService.getBoolean("chat.retrievalDebugEnabled")) {
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
    /** 过程独白块：工具模式下模型把推理/试错独白包进 <process> 标签，流式中整块剥离归入过程通道（不进正文） */
    private static final Pattern processPattern = Pattern.compile("<process>([\\s\\S]*?)</process>");

    /** 工具模式提示词附录（buildAnswerStream 注入）：过程叙述规范——独白进 <process> 标签分流，标签外只写正式回答 */
    private static final String PROCESS_NARRATION_GUIDE = """

            【过程叙述规范】你正在带工具的执行场景中工作。推理、试错、排查与复盘属于过程，必须包进 <process> 与 </process> 标签内；标签外只写面向用户的正式回答：
            - 调用工具前后的想法、中间检查、对工具报错的处理过程，一律写在 <process> 标签内，且同一内容只写一遍；
            - 标签外禁止复述过程：不要重复 <process> 里已说过的话，不要出现"我先/我再看看/我改用/写入被拒我改用"这类过程性叙述，也不要把工具报错与排查细节写进正文（工具卡片已展示）；
            - 标签外的每个字都会原样展示给用户、算作正式回答的一部分：发起工具调用时不要在标签外附带任何计划句、过渡句或说明句（无论中文还是英文，例如"I'll first read... / Let me search..."），要么不说，要么写进 <process>；
            - 尽量把一段完整的过程叙述写完再发起工具调用，不要在一句话中间插入工具调用；
            - 全部工具执行完后，在标签外输出完整的正式回答（结论 + 结果摘要 + 必要说明），可直接作为交付内容阅读。
            """;
    /** 全局编号后的图片占位：[图片N] 或 [图片N：描述]（描述内不含 ]；用于收集片段截取丢掉的图） */
    private static final Pattern IMG_NUMBER_PATTERN = Pattern.compile("\\[图片\\d+[^\\]]*\\]");
    /** 从图片占位中提取全局编号（[图片12：xxx] → 12） */
    private static final Pattern IMG_NUM_DIGITS_PATTERN = Pattern.compile("\\d+");
    /** 入库原文里的图片占位：[图片] 或 [图片：描述]（尚未编号；填充上下文时替换为 IMG_NUMBER_PATTERN 形态） */
    private static final Pattern IMG_PLACEHOLDER_PATTERN = Pattern.compile("\\[图片(：.*?)?\\]");

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final AppProperties properties;
    private final ImageUrlSigner imageUrlSigner;
    private final HybridRetrievalService hybridRetrievalService;
    private final RerankService rerankService;
    private final DocumentMetaCache documentMetaCache;
    /** 用户长期记忆：跨会话事实/偏好的提取与注入（memory.enabled；游客分享会话豁免——发布者记忆不外泄） */
    private final UserMemoryService userMemoryService;
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
    /** 联网搜索工具（webSearch.enabled 控制，默认关；结果注册进引用体系，与知识库来源同编号） */
    private final WebSearchTools webSearchTools;
    /** 沙盒工具（execute / read_file / write_file / ls；tool.sandbox.enabled 控制，默认关，依赖 provisioner 服务） */
    private final SandboxTools sandboxTools;
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
    /**
     * M4：工作流服务（智能体绑定工作流时取用）。走 ObjectProvider <b>惰性</b>获取而不是直接注入：
     * {@code WorkflowService → WorkflowEngine → RagService} 已是一条依赖链（子智能体节点复用问答管线），
     * 这里再直接注入就成环（Spring Boot 3 默认禁止循环依赖，启动直接失败）。惰性取用把这个
     * 「只有绑定了工作流的智能体才用得到」的反向依赖推迟到调用时，依赖图保持单向。
     */
    private final org.springframework.beans.factory.ObjectProvider<WorkflowService> workflowServiceProvider;
    private final com.wisesoft.ai.mapper.ToolApprovalMapper toolApprovalMapper;
    /** @ 引用：被引文档的块直接前置进上下文（不经检索，不参与相关性门） */
    private final com.wisesoft.ai.mapper.KnowledgeMapper knowledgeMapper;

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
                      WebSearchTools webSearchTools,
                      SandboxTools sandboxTools,
                      SkillService skillService,
                      ChatAttachmentService chatAttachmentService,
                      SubAgentOrchestrator subAgentOrchestrator,
                      AgentService agentService,
                      AgentDispatchService agentDispatchService,
                      McpClientService mcpClientService,
                      KnowledgeBaseService knowledgeBaseService,
                      com.wisesoft.ai.mapper.UserMapper userMapper,
                      ModelRegistryService modelRegistryService,
                      UserMemoryService userMemoryService,
                      org.springframework.beans.factory.ObjectProvider<WorkflowService> workflowServiceProvider,
                      com.wisesoft.ai.mapper.ToolApprovalMapper toolApprovalMapper,
                      com.wisesoft.ai.mapper.KnowledgeMapper knowledgeMapper) {
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
        this.webSearchTools = webSearchTools;
        this.sandboxTools = sandboxTools;
        this.skillService = skillService;
        this.chatAttachmentService = chatAttachmentService;
        this.subAgentOrchestrator = subAgentOrchestrator;
        this.agentService = agentService;
        this.agentDispatchService = agentDispatchService;
        this.mcpClientService = mcpClientService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.userMapper = userMapper;
        this.userMemoryService = userMemoryService;
        this.workflowServiceProvider = workflowServiceProvider;
        this.modelRegistryService = modelRegistryService;
        this.toolApprovalMapper = toolApprovalMapper;
        this.knowledgeMapper = knowledgeMapper;
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
        chat(sessionId, question, userImages, attachments, skills, deepThink,
                agentId, modelOverride, userId, emitter, false, false);
    }

    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter, boolean guestMode) {
        chat(sessionId, question, userImages, attachments, skills, deepThink,
                agentId, modelOverride, userId, emitter, guestMode, false);
    }

    /**
     * @param regenerate 重新生成/自动重试的重发标记：该问题的用户消息已随上一轮请求**即时落库**（见 runChat 0.3），
     *                   true 时跳过开始期的用户消息落库，避免重发产生重复历史行
     */
    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter,
                     boolean guestMode, boolean regenerate) {
        chat(sessionId, question, userImages, attachments, skills, null, deepThink,
                agentId, modelOverride, userId, emitter, guestMode, regenerate);
    }

    /**
     * @param mentions 输入框 @ 引用（**已由控制器完成可见性校验**——这里不再鉴权，只按语义用）：
     *                 kb=本轮检索收窄到这些库；doc=该文档的块不经检索直接前置进上下文
     */
    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills,
                     List<ChatRequest.Mention> mentions, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter,
                     boolean guestMode, boolean regenerate) {
        chat(sessionId, question, userImages, attachments, skills, mentions, deepThink,
                agentId, modelOverride, userId, emitter, guestMode, regenerate, null);
    }

    /**
     * @param replaceMessageId 重新生成时被替换的旧回答消息 ID：落库前软删它，历史里只留最新一版
     *                         （不传则新回答与旧回答并存，刷新后同一问题出现两条答案）
     */
    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills,
                     List<ChatRequest.Mention> mentions, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter,
                     boolean guestMode, boolean regenerate, String replaceMessageId) {
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
                    runChat(sessionId, question, userImages, attachments, skills, mentions, useDeepThink,
                            agentId, modelOverride, userId, emitter, guestMode, regenerate, replaceMessageId);
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
                         List<ChatRequest.Attachment> attachments, List<String> skills,
                         List<ChatRequest.Mention> mentions, boolean deepThink,
                         String agentId, String modelOverride, String userId, SseEmitter emitter,
                         boolean guestMode, boolean regenerate, String replaceMessageId) {
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
        // 本轮回答的全部降级/兜底事件（fail-loud：随 done 下发，前端渲染警示条；code 去重，同类只报一次）。
        // 声明在智能体解析之前：会话锁定的智能体若已不可用，需要就地登记提示而不是静默改用全局配置
        List<Map<String, String>> degradations = new ArrayList<>();
        Set<String> degradedCodes = new HashSet<>();
        // 智能体（4.1）：会话级绑定——已锁定的会话沿用锁定值，未锁定的按请求解析并锁定（首问路由一次）。
        // 派遣在 resolveModel 之后（用当轮生效模型判路），失败回落默认智能体
        final Agent agent = resolveSessionAgent(sessionId, agentId, question, resolvedModel, emitter,
                degradations, degradedCodes);
        if (agent != null) {
            log.info("[AGENT] 本轮使用智能体 {}（{}）", agent.getId(), agent.getName());
        }
        // M4：智能体绑定工作流（chatflow）——回答由工作流产出，整条不走常规检索/生成链路。
        // 位置在智能体解析之后：绑定是智能体的属性，会话级锁定后每轮都走同一张图（同一人设语义）。
        // 图片/附件在此路径下不参与（工作流只接收文本入参），显式登记为降级提示而不是静默丢弃。
        if (agent != null && agent.getWorkflowId() != null && !agent.getWorkflowId().isBlank()) {
            if ((userImages != null && !userImages.isEmpty()) || (attachments != null && !attachments.isEmpty())) {
                addDegradation(degradations, degradedCodes, "workflowInputIgnored",
                        "该智能体由工作流驱动，本轮的图片/附件不会传入工作流（工作流当前只接收文本入参）");
            }
            runWorkflowChat(sessionId, question, userId, emitter, startTime, degradations, degradedCodes,
                    agent, guestMode, regenerate, replaceMessageId);
            return;
        }
        // 目标知识库集合（检索按库的向量模型分组逐库查询；null=不限，全库分组检索）。
        // 按需委派开启「收窄检索范围」（agent.dispatchNarrowScope）时，本集合会在路由判定后被
        // 重赋值为「主智能体库 ∪ 被选中子智能体库」（见检索前的路由段），因此不能声明为 final
        java.util.Collection<String> scopeKbIds = scopeKbIdsOf(agent);
        // @ 引用（输入框显式指定，**优先于智能体配置**——与「手动指定优先」的既有口径一致）：
        //   kb  → 本轮检索收窄到被引库，不跑无关库、不让无关块挤占上下文名额；
        //   doc → 该文档的块不经检索直接前置进上下文（用户认为它相关，不该被相关性门/排序挡掉），
        //         检索范围同时收窄到这些文档所属库（否则无关库的块会与之竞争名额）。
        final MentionScope mentionScope = MentionScope.of(mentions);
        if (mentionScope != null) {
            Set<String> mentionKbs = new LinkedHashSet<>(mentionScope.kbIds());
            mentionKbs.addAll(mentionScope.docKbIds());
            if (!mentionKbs.isEmpty()) {
                log.info("[MENTION] 本轮按 @ 引用收窄检索范围：{} 个库；强制前置 {} 篇文档",
                        mentionKbs.size(), mentionScope.docIds().size());
                scopeKbIds = mentionKbs;
            }
        }
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
        // @ 文档时收窄到这些文档（显式指定优先）——用户就是在问这份文档，别的文档的块不该进上下文
        final Set<String> scopeDocIds = mentionScope != null && !mentionScope.docIds().isEmpty()
                ? new LinkedHashSet<>(mentionScope.docIds())
                : resolveScopeDocIds(agent);
        // 分段耗时（排障用：记的是「距开始的累计毫秒」，差值即为该阶段耗时），随问答日志落库
        final Map<String, Long> stageMs = new LinkedHashMap<>();
        // 深度思考全文（供 done 事件/持久化；lambda 中引用需 effectively final，用数组容器）
        final String[] thinkingHolder = {null};
        try {
            // 执行计划（任务清单）：只列本轮按当前配置**确定会跑**的步骤，供前端清单逐项点亮。
            // 模型临时决定的工具调用/子智能体咨询无从预知，不进计划（谎报计划比没有计划更糟），
            // 那部分由 tool_status/subagent 实时事件呈现。计划仅实时可见，不随消息持久化。
            List<String> planSteps = new ArrayList<>();
            planSteps.add("理解问题");
            if (knowledgeOff) {
                planSteps.add("生成回答");
            } else {
                if (useDeepThink && configService.getBoolean("deepReasoning.enabled")) {
                    planSteps.add("深度思考");
                }
                planSteps.add("检索知识库");
                planSteps.add("生成回答");
            }
            sendSseEvent(emitter, "plan", JSON.toJSONString(planSteps), sessionId);
            // 0. 进度提示：理解问题阶段（图片描述/改写都有耗时，先给用户反馈）
            sendSseEvent(emitter, "stage", "正在理解问题…", sessionId);
            // 前置心跳：从本轮一开始就保活（图片视觉描述/深度思考/检索都是长静默区，
            // serverless 端点冷启动时检索可达 90s+，此前该阶段无心跳 ⇒ 前端 120s 空闲看门狗
            // 掐断连接，用户看到"无输出且报错"而后端其实还在干活）。句柄在 st 创建时接管
            // （startRunHeartbeat 幂等复用），终态路径照旧停止；st 前异常/断开由发送失败自停兜住。
            final java.util.concurrent.ScheduledFuture<?> preHeartbeat =
                    scheduleKeepalive(emitter, "前置");
            // 0. 用户上传图片：并行保存+视觉描述（用于上下文与检索召回）
            List<UserImageService.UserImage> userImgs = userImageService.process(userImages, userVisionRef);
            String imgDescText = userImgs.isEmpty() ? "" : userImgs.stream()
                    .map(i -> "- " + (i.desc().isBlank() ? "（图片内容无法识别）" : i.desc()))
                    .collect(Collectors.joining("\n"));

            // 0.1 用户上传附件（文档类）：解析为纯文本注入本轮上下文。
            //     单附件解析失败以可读错误说明占位、其余照常（不拖垮整轮）；元信息（名称/体积）随消息持久化供气泡回显
            List<ChatAttachmentService.PreparedAttachment> preparedAtts =
                    chatAttachmentService.prepare(attachments, userId);
            String attachmentText = chatAttachmentService.buildContextText(preparedAtts);
            List<Map<String, Object>> attachmentsMeta = new ArrayList<>();
            if (attachments != null) {
                for (int i = 0; i < Math.min(attachments.size(), preparedAtts.size()); i++) {
                    attachmentsMeta.add(ChatAttachmentService.metaOf(attachments.get(i), preparedAtts.get(i).size()));
                }
            }

            // 0.2 用户本轮主动选用的技能（输入框「+」菜单）：全文注入本轮 system prompt
            String userSkillText = buildUserSkillText(userId, skills);

            // 0.3 用户消息即时落库（先于检索/生成，图片与附件元信息此时已就绪）：
            //     刷新页面/断线不再丢掉已发出的问题（此前只在整轮完成时落库，回答中刷新=问题彻底消失）；
            //     且后续轮次 getRecentHistory 能取到本轮问题——上一轮回答尚未完成/失败时上下文照样延续。
            //     重新生成/自动重试（regenerate=true）不重复落库：该问题已随上一轮请求入库。
            //     落库失败内部已降级 Redis（mysqlPending 补写机制），不阻断本轮回答。
            if (!regenerate) {
                List<String> earlyImgUrls = userImgs.stream().map(UserImageService.UserImage::url).toList();
                sessionService.appendMessage(sessionId, "user", question,
                        earlyImgUrls.isEmpty() ? null : earlyImgUrls, null,
                        null, null, null, null,
                        attachmentsMeta.isEmpty() ? null : JSON.toJSONString(attachmentsMeta));
            }

            // 0.4 智能体声明「不使用知识库」：跳过改写/深度思考/检索/子代理编排整条链路，
            //     直接走生成（仅 @ 引用的文档块会前置进上下文）。图片提问也不走视觉检索，
            //     但图片描述仍会随问题发给模型（多模态理解与知识库无关）。
            if (knowledgeOff) {
                log.info("[AGENT] 智能体 {} 不使用知识库，跳过检索链路", agent.getId());
                // 该分支不检索，但用户手动 @ 的文档仍按「手动指定优先」取块前置
                // （方法注释里承诺已久的语义，此前只有注释没有实现）
                String mentionText = buildMentionText(loadMentionChunks(mentionScope, degradations, degradedCodes));
                runNoKnowledgeChat(sessionId, question, userId, userImgs, imgDescText, attachmentText, userSkillText,
                        mentionText, preHeartbeat, emitter, startTime, thinkingHolder, degradations, degradedCodes,
                        agent, stageMs, resolvedModel, guestMode, replaceMessageId);
                return;
            }

            // 检索查询：多轮对话先做指代消解改写（retrieval.queryRewriteEnabled 默认开，仅多轮触发——
            // 首轮无上下文就没有指代，改写纯增加一次 LLM 调用与延迟）。改写是召回的增益优化：
            // 失败/超时/改写结果异常一律按原句继续检索（原句是保底正确行为，不 fail-loud 阻断问答），
            // 失败原因记 degradation（queryRewriteFailed）供排障面板可见。
            String retrievalQuery = question;
            List<Map<String, Object>> rewriteHistory = null;
            if (configService.getBoolean("retrieval.queryRewriteEnabled")) {
                rewriteHistory = sessionService.getRecentHistory(sessionId, 3);
            }
            if (rewriteHistory != null && rewriteHistory.size() > 1) {
                // >1 条 = 除本轮刚落库的问题外还有历史轮次，才有指代消解的上下文可用
                retrievalQuery = rewriteQueryForRetrieval(question, rewriteHistory, resolvedModel,
                        degradations, degradedCodes);
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

            // 0.5 按需委派路由提前：委派挑选（route）不依赖检索结果，放在检索前执行——
            // 开启「委派收窄检索范围」（agent.dispatchNarrowScope）时，主检索可先收窄到
            // 「主智能体库 ∪ 被选中子智能体库」再跑，避免无关库的弱相关块挤进上下文/引用
            // （路由判错的代价是漏召回，故默认关；误拦由检索本身兜底——子代理各自仍会检索自己库）。
            // 总耗时不变：原时序为 检索→route→子代理，现对调为 route→检索→子代理（串行段相同）。
            List<Agent> subCandidates = List.of();
            SubAgentOrchestrator.RouteResult routeRes = null;
            // 按需委派的路由结果（创建 AnswerStreamState 时要回填，供 done 下发与持久化）
            Map<String, Object> subagentRouteInfo = null;
            if (configService.getBoolean("agent.enabled")) {
                subCandidates = resolveSubAgents(agent);
                if (!subCandidates.isEmpty()) {
                    // 阶段文案统一：路由挑选属"理解问题"准备段（具体挑了谁由 subagent_route 事件承载）
                    sendSseEvent(emitter, "stage", "正在理解问题…", sessionId);
                    routeRes = subAgentOrchestrator.route(question, subCandidates, resolvedModel);
                    List<Agent> delegated = routeRes.picked();
                    if (delegated.size() != subCandidates.size()) {
                        log.info("[SUBAGENT] 按需委派：{} 个候选中挑选 {} 个（{}）", subCandidates.size(), delegated.size(),
                                delegated.stream().map(Agent::getName).collect(Collectors.joining("、")));
                    }
                    // 路由结果下发：让"挑选过程"可见（前端展示"从 N 个候选中挑选 M 个"）；
                    // reasons 为各被选助手的「挑选理由」（路由可解释性，模型没给理由时缺省）
                    Map<String, Object> routeInfo = new LinkedHashMap<>();
                    routeInfo.put("candidates", subCandidates.size());
                    routeInfo.put("picked", delegated.size());
                    routeInfo.put("names", delegated.stream().map(Agent::getName).toList());
                    if (!routeRes.reasons().isEmpty()) routeInfo.put("reasons", routeRes.reasons());
                    subagentRouteInfo = routeInfo;
                    sendSseEvent(emitter, "subagent_route", JSON.toJSONString(routeInfo), sessionId);
                    // 委派收窄检索范围（默认关）：仅当路由产生了**真子集**时收窄——真子集才证明路由有判别力；
                    // 全量返回（route 失败回退 / autoRoute 关 / 真全选）都视作"无范围信息"，保持原范围。
                    // 并集 = 主智能体库 ∪ 被选中子智能体库；双方都没绑库（或并集为空）时无从收窄，保持原范围。
                    if (configService.getBoolean("agent.dispatchNarrowScope")
                            && !delegated.isEmpty() && delegated.size() < subCandidates.size()) {
                        Set<String> union = new java.util.LinkedHashSet<>();
                        boolean bounded = false;
                        if (scopeKbIds != null) {
                            union.addAll(scopeKbIds);
                            bounded = true;
                        }
                        for (Agent sub : delegated) {
                            java.util.Collection<String> subKbs = scopeKbIdsOf(sub);
                            if (subKbs != null) {
                                union.addAll(subKbs);
                                bounded = true;
                            }
                        }
                        if (bounded && !union.isEmpty()) {
                            scopeKbIds = union;
                            log.info("[SUBAGENT] 委派收窄主检索范围：{} 个库（{}）", union.size(), String.join("、", union));
                        }
                    }
                }
            }
            // 检索参数覆盖：库级按本轮实际检索范围（委派收窄后；未选智能体时同样生效），智能体级在其上叠加
            applyQueryOverrides(scopeKbIds, agent);

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
            if (configService.getBoolean("agent.enabled")) {
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
                if (subCandidates.isEmpty()) {
                    // 未挂子智能体 → 原有的「多视角并行检索」
                    // 阶段文案统一：并行检索只是检索的一种实现（分支进度由 subagent 事件承载）
                    sendSseEvent(emitter, "stage", "正在检索资料…", sessionId);
                    subOutcome = subAgentOrchestrator.run(question, null, onBranch, resolvedModel);
                } else {
                    // 挂了子智能体 → 路由已在检索前完成（见 0.5 段的 subagent_route 事件），此处直接按挑选结果并行咨询；
                    // 全量为空=路由判定无需咨询任何助手（问题与各助手职责均不匹配），跳过编排
                    List<Agent> delegated = routeRes.picked();
                    if (delegated.isEmpty()) {
                        log.info("[SUBAGENT] 按需委派判定无需咨询任何助手，跳过并行编排（问题与各助手职责均不匹配）");
                    } else {
                        // 阶段文案统一：子智能体也是检索路数之一（咨询了哪几个由 subagent 事件承载）
                        sendSseEvent(emitter, "stage", "正在检索资料…", sessionId);
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
                    // 联网来源与知识库来源同一套编号：不区分对待，否则"来自联网"会成为不标注的借口
                    .append("\n若本轮提供了联网搜索资料，它与知识库资料同等对待：引用时同样在句末用 [N] 标注，"
                            + "并只能使用工具实际返回的编号；搜索未覆盖的内容如实说明未找到依据，不得凭常识补写。")
                    .append(relatedPromptLine());
            // 用户长期记忆（跨会话个性化）：注入本人记忆 + 累加使用度；游客分享会话不注入
            // （发布者的个人记忆不外泄给匿名访客）；空记忆/未开启零影响
            if (!guestMode) {
                String memoryText = userMemoryService.injectText(userId, question);
                if (memoryText != null) system.append("\n\n").append(memoryText);
            }
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
            // @ 引用的文档块优先级最高（用户显式指定）：不经检索直接置于最前，并在填充段跳过
            // 相关性门与信息增益去冗余（显式指定 = 强相关，不该被算法判掉——否则用户看到的行为就是"@ 没用"）
            Set<String> mentionKids = new HashSet<>();
            for (Knowledge k : loadMentionChunks(mentionScope, degradations, degradedCodes)) {
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
            //   ② 加权融合分（vectorWeight×vecNorm + keywordWeight×hitRate，向量单命中常态 0.1~0.4）
            //      → 门 = retrieval.minFusionScore（默认 0.25，拦跨域词面弱相关块、不误杀单路命中）。
            // 不分域的后果（修复前实测）：rerank 服务不可用或关闭时，全部融合分低于 0.6 → 上下文被门清空，
            // 检索"看似无结果"。0 = 关闭对应域的门。调值前先看检索调试面板的实际分数分布（两个域分开看）；
            // 跳过不占 docNo/extra 配额（与去冗余同语义）。
            double minRerankGate = configService.getDouble("retrieval.minContextScore", 0.6);
            double minFusionGate = configService.getDouble("retrieval.minFusionScore", 0.25);
            // 单文档块数配额（0=不限制）：docId → 已进上下文的块数
            int maxBlocksPerDoc = configService.getInt("context.maxBlocksPerDoc", 3);
            Map<String, Integer> docBlockCount = new HashMap<>();
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

                // 块内片段截取：按检索词元定位命中位置，取 ±窗口（省 token 保精度；未命中则整块）。
                // 相邻合并块豁免：多段拼接的完整流程被窗口截回第一段附近，合并就白做了——
                // 长度由预算控制兜底（超预算 break/截断），信息量不低于"3 块各截 150 字"的旧形态。
                String fullTextForImg = text; // 截取前的完整文本（占位已替换为 [图片N：desc]）
                if (snippetWindow > 0 && !adjacentMergedKids.contains(hit.knowledgeId())) {
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
            st.heartbeat = preHeartbeat; // 前置心跳句柄移交（buildAnswerStream 的 startRunHeartbeat 幂等复用，终态照旧停止）
            st.toolScopeKbIds = scopeKbIds; // 精确检索工具与主链路同库界（库隔离）
            st.toolScopeDocIds = scopeDocIds;
            st.model = resolvedModel; // 本轮生效模型（会话覆盖 > 个人默认）
            st.deepThink = useDeepThink; // 归一后的深度思考（按生效模型能力 + 用户开关）
            st.guestMode = guestMode; // 游客分享会话：工具只保留知识检索与内置项（见 enabledToolCallbacks）
            st.replaceMessageId = replaceMessageId; // 重新生成：落库前软删被替换的旧回答
            st.toolApprovalMode = agent == null ? null : agent.getToolApprovalMode(); // 有副作用工具审批模式
            st.maxToolSteps = resolveMaxToolSteps(agent); // 单轮工具步数上限（智能体覆盖 > 全局）
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
        java.util.Set<String> sensitiveTools = new java.util.HashSet<>();
        if (!configService.getBoolean("tool.enabled")) {
            st.sensitiveToolNames = sensitiveTools;
            return callbacks;
        }
        // 游客分享会话（公开链接 /s/{token}）：能力白名单收窄——只保留知识精确检索与内置工具
        // （计算器/时间等无副作用项）。沙盒/产物/MCP/技能执行都是身份敏感能力：产物归属发布者、
        // 沙盒按 uid 派生容器、MCP/技能是个人资产，一律不对匿名访客暴露。
        if (st.guestMode) {
            if (toolOn(agent, "tool.knowledgeRetrieval.enabled", agent == null ? null : agent.getToolKnowledge())) {
                callbacks.addAll(java.util.Arrays.asList(
                        org.springframework.ai.support.ToolCallbacks.from(knowledgeRetrievalTool)));
            }
            if (toolOn(agent, "tool.builtin.enabled", agent == null ? null : agent.getToolBuiltin())) {
                Set<String> onlyBuiltin = agent == null ? null : scopeOf(agent.getBuiltinTools());
                for (org.springframework.ai.tool.ToolCallback cb :
                        org.springframework.ai.support.ToolCallbacks.from(builtinTools)) {
                    if (onlyBuiltin == null || onlyBuiltin.contains(cb.getToolDefinition().name())) {
                        callbacks.add(cb);
                    }
                }
            }
            log.info("[TOOL] 游客分享会话受限模式：启用 {} 个工具（知识检索/内置）", callbacks.size());
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
        // 联网搜索工具（webSearch.enabled 控制，默认关；游客分支在上方已 return，天然不对匿名访客暴露）：
        // 命中结果注册进本轮引用体系（与知识库来源共用 [N] 编号空间），这是"可溯源的联网"的前提——
        // 不注册而让模型引用，等于放行无出处的断言。
        // 审批归属由 webSearch.requireApproval 决定（默认 false=自动执行）：它会把用户输入发到外网并产生
        // 外部计费，语义上和沙盒/MCP 同级，因此**保留**纳入 sensitiveTools 的开关，但不默认强制 ask
        // （否则每次搜索都要点确认，联网体验不可用；需要收紧时由管理员开这一项）。
        if (toolOn(agent, "webSearch.enabled", agent == null ? null : agent.getToolWebsearch())) {
            boolean requireApproval = configService.getBoolean("webSearch.requireApproval");
            for (org.springframework.ai.tool.ToolCallback cb :
                    org.springframework.ai.support.ToolCallbacks.from(webSearchTools)) {
                callbacks.add(cb);
                if (requireApproval) {
                    sensitiveTools.add(cb.getToolDefinition().name());
                }
            }
        }
        // 沙盒工具（隔离执行环境）：tool.sandbox.enabled 控制；暂无智能体级三态覆盖（toolSandbox 列未加，
        // 见 SandboxTools 类注释）——沙盒本身按会话隔离，工具一旦启用对所有会话可用
        if (toolOn(agent, "tool.sandbox.enabled", null)) {
            for (org.springframework.ai.tool.ToolCallback cb :
                    org.springframework.ai.support.ToolCallbacks.from(sandboxTools)) {
                callbacks.add(cb);
                sensitiveTools.add(cb.getToolDefinition().name());
            }
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
                for (org.springframework.ai.tool.ToolCallback cb : mcpClientService.toolCallbacks(userId, onlyMcp)) {
                    callbacks.add(cb);
                    sensitiveTools.add(cb.getToolDefinition().name());
                }
            } catch (Exception e) {
                log.warn("[MCP] 加载外部工具失败（跳过，不影响问答）: {}", e.getMessage());
            }
        }
        // 记录本轮"有副作用"的工具名单（沙盒/MCP）：智能体 toolApprovalMode=ask 时执行前需用户确认
        st.sensitiveToolNames = sensitiveTools;
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
                    String name = cb.getToolDefinition().name();                    long begin = System.currentTimeMillis();
                    // 单轮步数上限：防模型陷入"调工具→不满意→再调"的失控循环烧 token。
                    // 达到上限返回错误结果并要求模型直接作答（fail-safe 而不是无限放行）。
                    if (st.maxToolSteps > 0 && st.toolStepCount.get() >= st.maxToolSteps) {
                        recordToolStatus(st, name, toolInput, "error", "已达单轮工具调用步数上限(" + st.maxToolSteps + ")",
                                System.currentTimeMillis() - begin, 0);
                        log.warn("[TOOL] 达到单轮步数上限({})，拒绝继续调用: tool={} session={}",
                                st.maxToolSteps, name, st.sessionId);
                        return "{\"error\":\"已达到本轮工具调用步数上限(" + st.maxToolSteps + ")，不再执行工具调用。"
                                + "请基于已有信息直接给出最终回答。\"}";
                    }
                    st.toolStepCount.incrementAndGet();
                    // 心跳已升级为整轮流级（buildAnswerStream 启动、终态路径停止）：覆盖「工具执行」
                    // 与「工具结束→最终回答首 token」两段静默区，这里不再逐工具启停。
                    {
                        // 人在回路审批：有副作用工具（沙盒/MCP）+ 智能体 ask 模式 → 暂停等待用户确认。
                        // 阻塞等待有界（chat.approvalTimeoutMs，默认 120s）；拒绝/超时以错误结果回给模型，
                        // 让它基于已有信息继续而不是无限重试。游客会话本就不暴露这类工具，天然不进此分支。
                        if (st.sensitiveToolNames.contains(name) && "ask".equalsIgnoreCase(st.toolApprovalMode)) {
                            long approvalTimeout = approvalTimeoutMs();
                            String approvalId = java.util.UUID.randomUUID().toString();
                            java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
                            // 落库审批记录（持久化 + 审计；内存 future 仍负责阻塞工具线程，不可省）
                            try {
                                com.wisesoft.ai.model.ToolApproval rec = new com.wisesoft.ai.model.ToolApproval();
                                rec.setId(approvalId);
                                rec.setSessionId(st.sessionId);
                                rec.setUserId(st.userId);
                                rec.setToolName(name);
                                rec.setStatus("PENDING");
                                String argsSummary = toolInput == null ? "" : (toolInput.length() > 2000 ? toolInput.substring(0, 2000) : toolInput);
                                rec.setRequestArgs(argsSummary);
                                rec.setCreatedAt(java.time.LocalDateTime.now());
                                toolApprovalMapper.insert(rec);
                            } catch (Exception e) {
                                log.warn("[TOOL] 审批记录落库失败（不阻塞审批流程）: {}", e.getMessage());
                            }
                            PENDING_APPROVALS.put(approvalId, new PendingApproval(st.sessionId, st.userId, name, future));
                            try {
                                Map<String, Object> req = new LinkedHashMap<>();
                                req.put("approvalId", approvalId);
                                req.put("tool", name);
                                String args = toolInput == null ? "" : toolInput;
                                req.put("args", args.length() > 2000 ? args.substring(0, 2000) + "…" : args);
                                req.put("timeoutMs", approvalTimeout);
                                sendSseEvent(st.emitter, "approval_required", JSON.toJSONString(req), st.sessionId);
                                log.info("[TOOL] 等待用户确认: tool={} approvalId={} session={}", name, approvalId, st.sessionId);
                                boolean approved;
                                try {
                                    approved = future.get(approvalTimeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                                } catch (java.util.concurrent.ExecutionException ee) {
                                    approved = false;
                            } catch (java.util.concurrent.TimeoutException te) {
                                approved = false;
                                markApprovalResolved(approvalId, "TIMEOUT", st.userId);
                                log.warn("[TOOL] 审批超时，按拒绝处理: tool={} session={}", name, st.sessionId);
                            } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    approved = false;
                                }
                                if (!approved) {
                                    recordToolStatus(st, name, toolInput, "error", "用户拒绝或确认超时，未执行",
                                            System.currentTimeMillis() - begin, 0);
                                    return "{\"error\":\"该工具调用未被用户批准（拒绝或确认超时），未执行。"
                                            + "请基于已有信息继续回答，不要重复尝试调用该工具。\"}";
                                }
                                log.info("[TOOL] 用户已批准: tool={} session={}", name, st.sessionId);
                            } finally {
                                PENDING_APPROVALS.remove(approvalId);
                            }
                        }
                        recordToolStatus(st, name, toolInput, "start", null, 0, 1);
                        // 流式输出接线：execute 增强一份 toolContext，注入「本工具输出增量回调」（闭包持有工具名）。
                        // SandboxTools 检测到回调即走流式执行（后台脱离+轮询），增量经 recordToolOutput 转 SSE；
                        // 其余工具/无流上下文按原上下文透传，行为不变。
                        org.springframework.ai.chat.model.ToolContext effectiveCtx = toolContext;
                        if (SandboxTools.STREAMING_TOOL_NAME.equals(name) && toolContext != null) {
                            java.util.Map<String, Object> enhanced = new java.util.HashMap<>(toolContext.getContext());
                            enhanced.put(SandboxTools.CTX_OUTPUT_SINK,
                                    (java.util.function.Consumer<String>) delta -> recordToolOutput(st, name, delta));
                            effectiveCtx = new org.springframework.ai.chat.model.ToolContext(enhanced);
                        }
                        // 精确检索工具：注入来源注册器——命中块注册进当前流 sources 续编引用编号，
                        // 工具文本改【引用N】提示模型按编号标注，前端角标悬浮/引用弹窗因此可溯源；
                        // 同步注入本轮检索范围——工具与主链路同库界，不得越过智能体知识库绑定检索
                        boolean kbTool = "searchKnowledge".equals(name);
                        if (kbTool) {
                            KnowledgeRetrievalTool.setSourceRegistrar(st::registerToolSource);
                            KnowledgeRetrievalTool.setKbScope(st.toolScopeKbIds, st.toolScopeDocIds);
                        }
                        // 联网搜索工具：注入本轮落点（配额扣减 + 引用注册 + 降级提示）。
                        // 工具对象是全局单例，落点必须按轮注入并即时清理，否则跨会话串号。
                        boolean webTool = WebSearchTools.TOOL_NAME.equals(name);
                        if (webTool) {
                            WebSearchTools.setSink(st);
                        }
                        try {
                            int[] attempts = {0};
                            String result = callWithRetry(cb, toolInput, effectiveCtx, name, attempts);
                            recordToolStatus(st, name, toolInput, "done", result,
                                    System.currentTimeMillis() - begin, attempts[0]);
                            return result;
                        } catch (Exception e) {
                            recordToolStatus(st, name, toolInput, "error", e.getMessage(),
                                    System.currentTimeMillis() - begin, 0);
                            throw e;
                        } finally {
                            if (kbTool) {
                                KnowledgeRetrievalTool.clearSourceRegistrar();
                                KnowledgeRetrievalTool.clearKbScope();
                            }
                            if (webTool) {
                                WebSearchTools.clearSink();
                            }
                        }
                    }
                }
            });
        }
        return wrapped.toArray(new org.springframework.ai.tool.ToolCallback[0]);
    }

    // ==================== 工具执行心跳 ====================

    /** 心跳间隔 15s：小于常见中间代理 60s 读超时与前端 120s 空闲看门狗，留足余量。 */
    private static final long TOOL_HEARTBEAT_INTERVAL_MS = 15_000;

    /** 心跳调度线程（全进程共享一个，守护线程不阻塞退出）。 */
    private static final java.util.concurrent.ScheduledExecutorService TOOL_HEARTBEAT_POOL =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tool-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /**
     * 按 15s 间隔向 emitter 发 SSE 注释行（":keepalive"）保活，返回句柄供停止/移交。
     * 发送失败（客户端已断开）自停，防任务泄漏。供 startRunHeartbeat 与 runChat 前置阶段共用。
     */
    private static java.util.concurrent.ScheduledFuture<?> scheduleKeepalive(SseEmitter emitter, String phase) {
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> self =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ScheduledFuture<?> f = TOOL_HEARTBEAT_POOL.scheduleWithFixedDelay(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                log.debug("[RUN-HEARTBEAT] {}心跳下发失败（客户端可能已断开），心跳自停: {}", phase, e.getMessage());
                java.util.concurrent.ScheduledFuture<?> s = self.get();
                if (s != null) s.cancel(false);
            }
        }, TOOL_HEARTBEAT_INTERVAL_MS, TOOL_HEARTBEAT_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        self.set(f);
        return f;
    }

    /**
     * 整轮流级 SSE 心跳：覆盖「工具执行（沙盒命令/审批等待可达数分钟）」与「工具结束→
     * 最终回答首 token」两段全静默区——中间代理（vite/nginx 默认 60s 读超时）会掐断连接、
     * 前端 120s 空闲看门狗会把静默误判为失联。每 15s 发一条 SSE 注释行（":keepalive"）：
     * 中间层与看门狗都视之为存活信号，前端解析器忽略注释行、UI 零打扰。
     * <p>生命周期：runChat 在**检索/深度思考阶段前**就启动前置心跳（serverless 端点冷启动期间
     * 检索可达 90s+，此前该阶段无心跳会被前端掐断），st 创建时接管句柄（本方法幂等复用），
     * 终态路径（doOnComplete / doOnError / disposeSafe）停止；发送失败自停，防任务泄漏。
     */
    private synchronized void startRunHeartbeat(AnswerStreamState st) {
        if (st.heartbeat != null && !st.heartbeat.isDone()) return;
        st.heartbeat = scheduleKeepalive(st.emitter, "整轮");
    }

    private static void stopRunHeartbeat(AnswerStreamState st) {
        java.util.concurrent.ScheduledFuture<?> f = st.heartbeat;
        if (f != null) {
            f.cancel(false);
            st.heartbeat = null;
        }
    }

    /**
     * 工具调用瞬时故障自动重试：失败后 500ms 重试一次（共至多 2 次），attempts 记录实际尝试次数。
     * 场景是沙盒容器冷启动、网络抖动这类瞬时故障；确定性错误（路径不存在等）多付一次 500ms 代价可接受
     * ——业务层的"错误输出"（如命令退出码非 0）不是异常，不会触发重试。模型看到的只有终态与 attempts。
     * 注意不声明 throws：ToolCallback.call 只抛运行时异常，包装 checked 会破坏调用方 precise-rethrow。
     */
    private String callWithRetry(org.springframework.ai.tool.ToolCallback cb, String toolInput,
                                 org.springframework.ai.chat.model.ToolContext toolContext,
                                 String name, int[] attempts) {
        RuntimeException last = null;
        for (int i = 0; i < 2; i++) {
            if (i > 0) {
                log.warn("[TOOL] {} 第 1 次调用失败，500ms 后自动重试: {}", name,
                        last == null ? "" : last.getMessage());
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
            attempts[0]++;
            try {
                return cb.call(toolInput, toolContext);
            } catch (Exception e) {
                last = e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
            }
        }
        throw last;
    }

    /** 工具入参/结果分级截断：SSE 实时只发 200 字摘要（防超长工具 I/O 撑爆 SSE 帧与实时渲染） */
    private static final int TOOL_IO_SSE_BRIEF = 200;
    /** 终态记录（done 汇总 + 随消息持久化）保存的全文上限：前端工具卡片展开查看完整入参/输出 */
    private static final int TOOL_IO_MAX = 8 * 1024;

    private static String truncBrief(String s, int max) {
        return s == null ? "" : s.substring(0, Math.min(max, s.length()));
    }

    /** 工具实时输出增量：SSE tool_output 事件（前端在运行中的工具卡片里实时滚动）。不落库——
     *  完整输出随 done 汇总的 result 走持久化；本事件仅服务执行期的"看着它跑"。 */
    private void recordToolOutput(AnswerStreamState st, String toolName, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        String brief = delta.length() > 4096 ? delta.substring(0, 4096) : delta; // 单片上限，防异常大块撑爆 SSE 帧
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", toolName);
        rec.put("delta", brief);
        try {
            st.emitter.send(SseEmitter.event()
                    .name("tool_output")
                    .data("{\"type\":\"tool_output\",\"content\":" + JSON.toJSONString(rec)
                            + ",\"sessionId\":\"" + st.sessionId + "\"}"));
        } catch (Exception e) {
            log.debug("[TOOL-OUTPUT] SSE 下发失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    /**
     * 把锚点到 pos 之间的正文记为一个文本段（无新增正文则忽略）。工具/产物插入时间线前调用，
     * 使「正文 → 工具 → 正文」的交错顺序可被记录（只存区间，不复制正文）。
     */
    private void flushTimelineText(AnswerStreamState st, int pos) {
        int from = st.timelineAnchor.get();
        if (pos <= from) return;
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("kind", "text");
        seg.put("from", from);
        seg.put("to", pos);
        st.timeline.add(seg);
        st.timelineAnchor.set(pos);
    }

    /**
     * 过程时间线成段：锚点到 pos 之间的过程独白记为一个过程段（区间指向 processText，与正文段同构）。
     * 连续追加时锚点不前移、区间自动延伸；工具/产物插入时先成段再插卡，保持「正文—过程—工具」真实交错。
     */
    private void flushTimelineProcess(AnswerStreamState st, int pos) {
        int from = st.processAnchor.get();
        if (pos <= from) return;
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("kind", "process");
        seg.put("from", from);
        seg.put("to", pos);
        st.timeline.add(seg);
        st.processAnchor.set(pos);
    }

    /**
     * 过程独白入通道：累积 processResponse、推进时间线过程段、SSE process 实时下发。
     * 客户端断开只丢实时显示，累积与落库不受影响。
     */
    private void routeProcessText(AnswerStreamState st, String delta) {
        if (delta == null || delta.isEmpty()) return;
        st.processResponse.append(delta);
        flushTimelineProcess(st, st.processResponse.length());
        sendSseEvent(st.emitter, "process", delta, st.sessionId);
    }

    /** 产物生成时刻：正文成段后插入产物段（下标指向本轮产物清单，与 artifacts 数组同源同序） */
    private void pushTimelineArtifact(AnswerStreamState st, int artifactIndex) {
        flushTimelineText(st, st.fullResponse.length());
        flushTimelineProcess(st, st.processResponse.length());
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("kind", "artifact");
        seg.put("i", artifactIndex);
        st.timeline.add(seg);
    }

    /**
     * 落库/下发用的时间线快照：补上尾部正文并按最终正文长度夹取。
     * 引用自检可能删除若干引用标记使最终正文短于流式累积，区间按最终版收敛（不越界）；
     * 只有「单段覆盖全文」时返回空（与整段渲染等价，不必占一列存储）。
     */
    private List<Map<String, Object>> buildTimelineSnapshot(AnswerStreamState st, String answer,
                                                            int toolCount, int artifactCount) {
        int len = answer == null ? 0 : answer.length();
        int processLen = st.processResponse.length();
        flushTimelineText(st, len);
        flushTimelineProcess(st, processLen);
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (st.timeline) {
            for (Map<String, Object> seg : st.timeline) {
                if (seg == null) continue;
                Map<String, Object> copy = new LinkedHashMap<>(seg);
                if ("text".equals(copy.get("kind"))) {
                    int from = Math.min(toInt(copy.get("from")), len);
                    int to = Math.min(toInt(copy.get("to")), len);
                    if (to <= from) continue; // 空段：不渲染也不打断交错
                    copy.put("from", from);
                    copy.put("to", to);
                } else if ("process".equals(copy.get("kind"))) {
                    // 过程段区间指向 processText，按其最终长度夹取（与正文段同规则）
                    int from = Math.min(toInt(copy.get("from")), processLen);
                    int to = Math.min(toInt(copy.get("to")), processLen);
                    if (to <= from) continue;
                    copy.put("from", from);
                    copy.put("to", to);
                } else if ("tool".equals(copy.get("kind"))) {
                    // 下标段必须与终态清单一一对齐：工具并发（终态入列顺序与 start 不同）时
                    // 该段无法指回确定的工具，丢弃比张冠李戴更诚实（卡片信息不会错配）
                    int i = toInt(copy.get("i"));
                    if (i < 0 || i >= toolCount) continue;
                } else if ("artifact".equals(copy.get("kind"))) {
                    int i = toInt(copy.get("i"));
                    if (i < 0 || i >= artifactCount) continue;
                }
                out.add(copy);
            }
        }
        if (out.size() == 1 && "text".equals(out.get(0).get("kind"))
                && toInt(out.get(0).get("from")) == 0 && toInt(out.get(0).get("to")) == len) {
            return List.of();
        }
        return out;
    }

    private static int toInt(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 记录一条工具状态：实时 SSE tool_status 事件（短摘要）+ AnswerStreamState.toolCalls 累积（全文，done 汇总与持久化用） */
    private void recordToolStatus(AnswerStreamState st, String name, String input,
                                  String status, String resultOrError, long elapsedMs, int attempts) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", name);
        rec.put("status", status);
        rec.put("elapsedMs", elapsedMs);
        if (attempts > 1) {
            rec.put("attempts", attempts); // 自动重试后成功：前端显示「重试 N 次」
        }
        String argsBrief = truncBrief(input, TOOL_IO_SSE_BRIEF);
        rec.put("args", argsBrief);
        if (resultOrError != null) {
            rec.put(status.equals("error") ? "error" : "result", truncBrief(resultOrError, TOOL_IO_SSE_BRIEF));
        }
        // 工具发起时刻：锚点前已产出的正文/过程独白先各自成段，再插入工具段——
        // 记录的是「正文与过程说到多少之后发起的工具」，刷新后据此把卡片放回原位
        if ("start".equals(status)) {
            flushTimelineText(st, st.fullResponse.length());
            flushTimelineProcess(st, st.processResponse.length());
            Map<String, Object> toolSeg = new LinkedHashMap<>();
            toolSeg.put("kind", "tool");
            // 终态记录在 start 之后才入 toolCalls 列表，此处 size 正是它将落到的下标
            toolSeg.put("i", st.toolCalls.size());
            st.timeline.add(toolSeg);
        }
        // 只把终态（done/error）记入持久化列表：start 仅实时下发（前端转圈显示），
        // 否则快照里 start/done 成对存在，前端 done 汇总覆盖后工具状态行会出现重复双行
        if (!"start".equals(status)) {
            // 终态记录升级为全文（上限 8KB）：done 汇总与落库都用它，前端卡片展开可见完整入参/输出，
            // 历史恢复同样可展开（实时 SSE 副本保持短摘要，两者字段同名、前端合并时全文覆盖摘要）
            Map<String, Object> full = new LinkedHashMap<>(rec);
            full.put("args", truncBrief(input, TOOL_IO_MAX));
            if (resultOrError != null) {
                full.put(status.equals("error") ? "error" : "result", truncBrief(resultOrError, TOOL_IO_MAX));
            }
            st.toolCalls.add(full);
        }
        // 后端日志同步留痕（与 [RAG]/[CTX] 等阶段日志同级可观测）：开始/完成/失败各一行，结果截断防爆量
        if ("start".equals(status)) {
            log.info("[TOOL] {} 调用开始 args={}", name, argsBrief);
        } else if ("error".equals(status)) {
            log.warn("[TOOL] {} 调用失败 ({}ms) error={}", name, elapsedMs, rec.get("error"));
        } else {
            log.info("[TOOL] {} 调用完成 ({}ms) result={}", name, elapsedMs,
                    resultOrError == null ? "" : truncBrief(resultOrError.replace('\n', ' '), 160));
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
        // 产物生成时刻监听：把产物卡片按生成顺序插进本轮时间线（刷新后仍在原位，不再堆到气泡底部）
        artifactService.registerArtifactListener(st.sessionId, (sid, idx) -> pushTimelineArtifact(st, idx));
        // 整轮流级心跳：从这里到终态（complete/error/dispose）全程保活，覆盖工具执行
        // 与「工具结束→最终回答首 token」两段静默盲区（重试重建流时幂等复用）
        startRunHeartbeat(st);
        org.springframework.ai.tool.ToolCallback[] toolCallbacks =
                instrumentTools(enabledToolCallbacks(agent, st.userId, st), st);
        // 过程叙述规范仅工具模式注入：无工具的纯对话没有"过程"可叙，加了反而诱导模型输出标签
        String sysFinal = toolCallbacks.length == 0 ? system : system + PROCESS_NARRATION_GUIDE;
        return chatClient.prompt()
                .system(sysFinal)
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
                .toolCallbacks(toolCallbacks)
                // 工具上下文：把当前会话 ID 与用户 ID 注入，供产物交付、沙盒等工具定位会话与归属。
                // userId 必须随 toolContext 透传——工具回调跑在 Spring AI 响应式 I/O 线程上，
                // 读 RequestUser.uid()（ThreadLocal）跨线程失效会回落成 anonymous，导致沙盒建到 shared/anonymous。
                .toolContext(java.util.Map.of(PresentArtifactTool.CTX_SESSION_ID, st.sessionId,
                        PresentArtifactTool.CTX_USER_ID, st.userId))
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
                    // 存在未完整闭合的 related/process 块（开始/闭合标签被跨 token 切分也覆盖）：继续缓冲不下发
                    boolean unclosedRelated = containsUnclosedRelated(bufStr);
                    boolean unclosedProcess = containsUnclosedProcess(bufStr);
                    if (unclosedRelated || unclosedProcess) {
                        if (bufStr.length() > 3000) {
                            st.emitBuf.setLength(0);
                            if (unclosedProcess && !unclosedRelated && bufStr.contains("<process>")) {
                                // 未闭合 <process>（模型漏写闭合标签）：开口前是正文走 token，
                                // 其后剥标签整体归入过程通道（fail-loud 标记）——按原文发正文会把独白漏给用户
                                addDegradation(st.degradations, st.degradedCodes, "processMalformed",
                                        "模型输出格式异常（process 标签未闭合），开口后内容已整体归入过程叙述");
                                int pIdx = bufStr.indexOf("<process>");
                                String ansPart = bufStr.substring(0, pIdx);
                                String procPart = bufStr.substring(pIdx + "<process>".length());
                                if (!ansPart.isEmpty()) {
                                    st.fullResponse.append(ansPart);
                                    if (!sendSseEvent(emitter, "token", ansPart, st.sessionId)) {
                                        st.disposeSafe();
                                        return;
                                    }
                                }
                                routeProcessText(st, procPart.stripLeading());
                            } else {
                                // related 未闭合（或两类标签并存难以可靠拆分）：按原文发送（extractRelated 兜底清理）；fail-loud 标记
                                addDegradation(st.degradations, st.degradedCodes, "relatedMalformed",
                                        "模型输出格式异常（related 标签未闭合），已按原文清理");
                                String raw = bufStr;
                                st.fullResponse.append(raw);
                                // 客户端断开：取消流订阅立即停止模型输出（不补 error/complete）
                                if (!sendSseEvent(emitter, "token", raw, st.sessionId)) {
                                    st.disposeSafe();
                                    return;
                                }
                            }
                        }
                        return;
                    }
                    // 提取完整 process 块 → 过程通道（不进正文）：实时 SSE process 事件 + 时间线过程段
                    StringBuilder procBuf = new StringBuilder();
                    java.util.regex.Matcher pm = processPattern.matcher(bufStr);
                    // 每个块内容剥前导空白：模型写 "<process>\n内容\n</process>"，标签后的换行是格式噪声，
                    // 不剥则灰字块顶部空一行且随消息落库（多个块同批拼接时中间也会出空白行）
                    while (pm.find()) procBuf.append(pm.group(1).stripLeading());
                    String clean = bufStr.replaceAll("<process>[\\s\\S]*?</process>", "");
                    // 剥离完整 related 块，收集推荐内容
                    java.util.regex.Matcher rm = relatedPattern.matcher(clean);
                    while (rm.find()) {
                        st.relatedBlock.append(rm.group(1)).append("\n");
                    }
                    clean = clean.replaceAll("<related>[\\s\\S]*?</related>", "");
                    // 尾部只扣留「可能是标签真前缀」的后缀（通常 0 字符），其余立即下发——
                    // 此前固定扣 19 字符：正文 token 滞后下发而 tool_status/process 事件即时下发，
                    // 旁路事件越过未下发正文 ⇒ 前端实时时间线错序断词（「提|示文件已存在」被劈开）
                    st.emitBuf.setLength(0);
                    int keep = tagPrefixSuffixLen(clean);
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
                    // 过程事件在正文之后下发（同缓冲内先到的正文先行，保持流式顺序）
                    if (procBuf.length() > 0) {
                        routeProcessText(st, procBuf.toString());
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
                        // 时间线同属本轮缓冲：一并清空并回到 0 锚点（否则重试后正文区间与正文错位）
                        st.timeline.clear();
                        st.timelineAnchor.set(0);
                        st.processResponse.setLength(0);
                        st.processAnchor.set(0);
                        st.disposableRef.set(buildAnswerStream(system, user, st, agent));
                        return;
                    }
                    log.error("Stream error: {} -> {}", error.getClass().getSimpleName(), root.toString());
                    String msg = (root instanceof java.net.ConnectException)
                            ? "无法连接 AI 服务，请检查网络或 API 地址"
                            : "AI 回复失败，请稍后重试";
                    st.degradations.add(Map.of("code", "streamError", "msg", "模型输出中断：" + msg));
                    sendSseEvent(emitter, "error", msg, st.sessionId);
                    // 终态：停整轮流级心跳（error 路径）
                    stopRunHeartbeat(st);
                    completeEmitter(emitter);
                    artifactService.unregisterEmitter(st.sessionId);
                })
                .doOnComplete(() -> {
                    // 终态：先停整轮流级心跳（complete 路径），收尾阶段不再有心跳字节
                    stopRunHeartbeat(st);
                    // 下发缓冲尾部（可能残留滑动窗口），并剥离可能的不完整标签
                    if (st.emitBuf.length() > 0) {
                        String rest = st.emitBuf.toString();
                        st.emitBuf.setLength(0);
                        // 缓冲尾可能压着刚闭合/未闭合的 process 块（被扣留窗口压住）：先剥离，
                        // 正文先行下发后再路由过程通道（保持流式顺序）
                        StringBuilder procTail = new StringBuilder();
                        java.util.regex.Matcher ptm = processPattern.matcher(rest);
                        while (ptm.find()) procTail.append(ptm.group(1).stripLeading());
                        rest = rest.replaceAll("<process>[\\s\\S]*?</process>", "");
                        // 未闭合的 <process>（通常是输出触顶截断在闭合标签之前）：开口前是正文，
                        // 其后剥标签归入过程通道并 fail-loud——原样发正文会把独白漏给用户
                        int pIdx = rest.indexOf("<process");
                        if (pIdx >= 0) {
                            addDegradation(st.degradations, st.degradedCodes, "processMalformed",
                                    "模型输出格式异常（process 标签未闭合，通常是输出达到长度上限被截断），已归入过程叙述");
                            String procPart = rest.substring(pIdx);
                            int gt = procPart.indexOf('>');
                            procPart = gt >= 0 ? procPart.substring(gt + 1) : "";
                            procTail.append(procPart.stripLeading());
                            rest = rest.substring(0, pIdx);
                        }
                        // 未闭合的 <related>（模型偶发把推荐块写在回答开头，或输出触顶截断在闭合标签之前）：
                        // 原实现从 "<related" 起整段静默丢弃——模型若把正文写在标签内会陪葬，
                        // 表现为回答只剩开头几字甚至全空（realOutputTokens 已计入完整输出）。
                        // 改为剥标签本体、保留内部文字，并 fail-loud 标记。
                        String noOpen = rest.replace("<related>", "");
                        if (!noOpen.equals(rest)) {
                            rest = noOpen;
                            addDegradation(st.degradations, st.degradedCodes, "relatedMalformed",
                                    "模型输出格式异常（related 标签未闭合，通常是输出达到长度上限被截断），已保留可读内容");
                        }
                        // 尾部残留的半截标签（截断恰好停在 <related / </related / <process / </process 中间）：剥掉避免漏出标签字样
                        int lt = rest.lastIndexOf('<');
                        if (lt >= 0) {
                            String tail = rest.substring(lt);
                            if (isRelatedStart(tail) || isRelatedEndStart(tail)
                                    || isProcessStart(tail) || isProcessEndStart(tail)) {
                                rest = rest.substring(0, lt);
                            }
                        }
                        if (!rest.isEmpty()) {
                            st.fullResponse.append(rest);
                            sendSseEvent(emitter, "token", rest, st.sessionId);
                        }
                        // 正文下发后再路由过程独白（流式顺序：先到的正文先行）
                        if (procTail.length() > 0) {
                            routeProcessText(st, procTail.toString());
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
                    // 图片占位越界校验（与引用越界校验同构）：正文 [图片N] 编号必须落在本轮真实图片
                    // （imgIndex，由主链路上下文填充建立）范围内。工具模式下模型手里没有编号图片
                    // （工具返回文本不带编号、images 为空），却可能按「尽量配图」规则编造 [图片N]，
                    // 前端 images[N-1] 映射不到就把字面 [图片N] 原样显示在回答里。剔除标记 + fail-loud。
                    int maxImg = st.imgIndex.size();
                    java.util.regex.Matcher im = IMG_NUMBER_PATTERN.matcher(answer);
                    StringBuilder imgSb = new StringBuilder();
                    int bogusImgs = 0;
                    while (im.find()) {
                        java.util.regex.Matcher nm = IMG_NUM_DIGITS_PATTERN.matcher(im.group());
                        int n = nm.find() ? Integer.parseInt(nm.group()) : 0;
                        if (n >= 1 && n <= maxImg) {
                            im.appendReplacement(imgSb, java.util.regex.Matcher.quoteReplacement(im.group()));
                        } else {
                            bogusImgs++;
                            im.appendReplacement(imgSb, "");
                        }
                    }
                    im.appendTail(imgSb);
                    if (bogusImgs > 0) {
                        answer = imgSb.toString();
                        addDegradation(st.degradations, st.degradedCodes, "invalidImageRef",
                                "已移除 " + bogusImgs + " 处无效的图片标记（本轮没有对应的图片资料）");
                        log.info("[IMG-CHECK] 剔除无效图片标记 {} 处 (maxImg={})", bogusImgs, maxImg);
                    }
                    // 生成完成（引用自检前的最后一步）
                    st.stageMs.put("generate", System.currentTimeMillis() - st.startTime);
                    // 引用语义一致性自检（深度防线）：编号没越界 ≠ 内容被支撑——LLM 可能引用了一个块，
                    // 但对应句子的结论与该块无关（编号正确、语义不符）。把每个 [N] 的"前文句子"与其
                    // 来源 snippet 打包给 LLM 判"是否支撑"，不支撑的剔除标记、来源同步裁剪并重编 ref。
                    // 一次额外调用，超时/失败/无引用跳过（保持原回答）；空前文（无法界定句子）的引用放行。
                    if (configService.getBoolean("chat.citationCheckEnabled") && !sources.isEmpty()) {
                        try {
                            long citeT0 = System.currentTimeMillis();
                            CitationCheckResult ccr = citationConsistencyCheck(answer, sources, st.question, st.model);
                            // 剔除 0 处也打一条：否则"自检开了没、花了多久"完全不可观测（调参时无法确认防线在位）
                            log.info("[CITE-CHECK] 自检完成: 剔除 {} 处, 耗时 {} ms", ccr.droppedCount(), System.currentTimeMillis() - citeT0);
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

                    // 回答时间线：正文区间 + 过程区间 + 工具/产物下标，随消息持久化（刷新与历史会话据此还原交错顺序）
                    List<Map<String, Object>> timelineSnapshot =
                            buildTimelineSnapshot(st, answer, toolCallSnapshot.size(), sessionArtifacts.size());
                    String timelineJson = timelineSnapshot.isEmpty() ? null : JSON.toJSONString(timelineSnapshot);
                    // 过程独白全文（<process> 标签内，与正文分流）：随消息落库 + done 下发，前端时间线灰字弱化渲染
                    String processText = st.processResponse.toString();

                    // 记录对话历史：用户消息已在本轮开始时即时落库（runChat 0.3，regenerate 重发不重复），
                    // 完成时只补落助手消息（含引用来源/思考/产物/工具调用/用量），拿到消息ID供前端反馈
                    String sourcesJson = sources.isEmpty() ? null : JSON.toJSONString(sources);
                    // Token 用量（1.9）：持久化前先算好（随消息存 JSON，刷新/历史会话仍可回看「本次用量/会话累计」）。
                    // 输出优先用网关真实 usage（部分兼容网关末块 metadata.usage 携带），拿不到回落本地估算；
                    // 真实值不额外加估算的 10% 余量（估算才需余量防超窗，实报应如实）。
                    boolean realOutput = st.realOutputTokens > 0;
                    int outputTokens = realOutput ? st.realOutputTokens : TokenCounter.estimate(answer);
                    // 输出触顶 fail-loud：网关真实 completion_tokens 达到 maxOutputTokens 上限 ⇒ 大概率被
                    // 截断（finish_reason=length），回答/related 推荐块不完整。原实现静默落库，
                    // 用户只会看到残缺回答而无任何提示（"没有回答出内容"的帮凶之一）。
                    int maxOutput = configService.getInt("context.maxOutputTokens");
                    if (realOutput && outputTokens >= maxOutput) {
                        addDegradation(st.degradations, st.degradedCodes, "outputTruncated",
                                "回答达到输出长度上限（" + maxOutput + " tokens），可能不完整；可在设置页调大「输出限制 token」");
                    }
                    int promptTokens = st.realPromptTokens > 0 ? st.realPromptTokens : st.contextTokens;
                    // noHit 事后判定（基于本轮最终引用）：主链路检索 0 填充但工具检索（智能体知识检索工具等）
                    // 注册了来源时不算"未检索到"——生成前判定拿不到工具后续注册的引用，会出现在回答带着
                    // [N] 引用的同时提示"未检索到相关资料"的自相矛盾。最终（sources 含工具注册项）仍为空才提示。
                    if (sources.isEmpty()) {
                        addDegradation(st.degradations, st.degradedCodes, "noHit", "未检索到相关资料，回答可能缺乏依据");
                    }
                    Map<String, Object> tokens = new LinkedHashMap<>();
                    tokens.put("context", st.contextTokens);
                    tokens.put("budget", st.budgetTokens);
                    tokens.put("hits", st.contextHits);
                    tokens.put("output", outputTokens);
                    tokens.put("prompt", promptTokens);
                    tokens.put("outputIsReal", realOutput);
                    tokens.put("total", promptTokens + outputTokens);
                    // 重新生成：先软删被替换的旧回答再写新回答。放在落库这一刻而不是请求开始——
                    // 本轮失败时旧回答仍在，用户不会两头空；不删则历史里同一问题会出现两条答案
                    if (st.replaceMessageId != null && !st.replaceMessageId.isBlank()) {
                        sessionService.deleteMessage(st.replaceMessageId);
                    }
                    // 12 参重载（含 tokens）：助手消息把用量 JSON 随行落库（历史回看/会话累计的数据源）
                    String messageId = sessionService.appendMessage(st.sessionId, "assistant", answer,
                            finalImgs, sourcesJson, st.thinkingHolder[0], finalRetrievedJson,
                            sessionArtifacts.isEmpty() ? null : JSON.toJSONString(sessionArtifacts),
                            toolCallsJson, null, JSON.toJSONString(tokens), timelineJson,
                            processText.isEmpty() ? null : processText,
                            // 未使用智能体的问答 agent 为 null（会话未绑定/走全局配置）：必须判空——
                            // 此前直接 agent.getId() 在 onComplete 回调里抛 NPE，被 reactor 丢弃
                            // （onComplete 阶段抛异常无处路由），表现为「助手消息不落库 + done 永不
                            // 下发 + 前端永远转圈」，且日志只有一行 onErrorDropped 极难定位。
                            agent == null ? null : agent.getId(),
                            agent == null ? null : agent.getName());

                    // 异步落问答日志（不阻塞 SSE 完成）；messageId/agentId 随行（trace 关联键与筛选维度）
                    List<String> hitDocIds = sources.stream().map(s -> String.valueOf(s.get("docId"))).toList();
                    qaLogService.logAsync(st.sessionId, st.question, answer, hitDocIds,
                            !st.sources.isEmpty(), System.currentTimeMillis() - st.startTime,
                            st.queryForLog, st.stageMs.isEmpty() ? null : JSON.toJSONString(st.stageMs),
                            st.deepThink, messageId, agent == null ? null : agent.getId());

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
                    // 回答时间线（与落库同源）：最终正文可能与流式累积不同（引用自检改写），
                    // 前端用它替换本地累积的时间线，保证「本轮视图」与「刷新后视图」完全一致
                    donePayload.put("timeline", timelineSnapshot);
                    // 过程独白全文（与落库同源）：前端 onDone 覆盖本地累积，先于 timeline 恢复赋值
                    donePayload.put("processText", processText);
                    // Token 消耗可视化（1.9）：用量已在持久化前算好（tokens），此处随 done 下发给当轮展示
                    donePayload.put("tokens", tokens);
                    // 智能体归属与会话锁定状态（以库中绑定为准，而非本轮局部变量：绑库失败时不应误导前端）。
                    // agentLocked=true 是前端「本会话已绑定、切换智能体=新会话」的依据；
                    // agentId 为空串表示已绑定为"不使用智能体"（与未绑定的 NULL 区分），此时不下发 id/name。
                    SessionService.AgentBinding finalBinding = sessionService.getAgentBinding(st.sessionId);
                    if (finalBinding != null) {
                        donePayload.put("agentLocked", true);
                        if (!finalBinding.agentId().isEmpty()) {
                            donePayload.put("agentId", finalBinding.agentId());
                            donePayload.put("agentName",
                                    finalBinding.agentName() == null ? "" : finalBinding.agentName());
                        }
                    }
                    sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), st.sessionId);
                    completeEmitter(emitter);
                    artifactService.unregisterEmitter(st.sessionId);
                    // 记忆提取：问答完整落定后异步提炼长期记忆（服务内自判开关/游客/匿名，best-effort）；
                    // 提取调用跟随本轮生效模型（st.model，done 时 fail-loud 保证非空）
                    userMemoryService.maybeExtract(st.userId, st.sessionId, st.question,
                            st.fullResponse.toString(), st.guestMode, st.model);
                })
                .subscribe();
    }

    /**
     * 单次回答的流式状态与 complete 回调依赖（H2 重试重建流时复用同一状态，旧缓冲被清空）。
     */
    /** 非静态内部类：工具图片注册（registerToolSource）需调用外部实例的 sendSseEvent/imageUrlSigner；生命周期=单轮请求，无泄漏 */
    private final class AnswerStreamState implements WebSearchTools.WebSearchSink {
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
        /** 本轮回答时间线：正文区间段 {kind:'text',from,to} 与工具/产物下标段 {kind:'tool'|'artifact',i} 按发生
         *  顺序排列。随消息落库并在 done 下发，供刷新/历史会话还原「正文与工具卡片交错」的过程视图——
         *  此前只落整段正文 + 工具终态列表，刷新后只能整段渲染、工具与产物堆在气泡底部。 */
        final java.util.List<Map<String, Object>> timeline = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        /** 时间线锚点：下一个文本段的起点（已记入时间线的正文长度），工具/产物插入时先把锚点前的正文成段 */
        final java.util.concurrent.atomic.AtomicInteger timelineAnchor = new java.util.concurrent.atomic.AtomicInteger(0);
        /** 过程独白（<process> 标签内）：与正文分流累积，随消息落库（process_text 列）并经 SSE process 事件实时下发 */
        final StringBuilder processResponse = new StringBuilder();
        /** 过程时间线锚点：下一个过程段的起点（已记入时间线的过程独白长度），工具/产物插入时先成段 */
        final java.util.concurrent.atomic.AtomicInteger processAnchor = new java.util.concurrent.atomic.AtomicInteger(0);
        /** 引用文件名映射（docId→fileName）：主链路构建后回填，供工具命中注册来源时取文件名 */
        volatile Map<String, String> docFileNames;
        /** 工具来源引用编号 → 该来源图片的全局图片编号清单（重复注册同一块时原样返回，供工具文本重复附清单） */
        final Map<Integer, List<Integer>> toolRefImages = new java.util.HashMap<>();
        /** 本轮联网搜索已执行次数（配额 webSearch.maxCallsPerTurn；<=0 不限制） */
        final java.util.concurrent.atomic.AtomicInteger webSearchCalls = new java.util.concurrent.atomic.AtomicInteger();
        /** 已注册的联网来源（归一化 URL → 引用编号）：同一 URL 二次命中复用原编号，不重复占号 */
        final Map<String, Integer> webRefByUrl = new java.util.HashMap<>();
        /** 精确检索工具的检索范围（与主链路同库界，工具执行线程内生效）：kbIds 限定库，docIds 后过滤命中 */
        volatile java.util.Collection<String> toolScopeKbIds;
        volatile Set<String> toolScopeDocIds;
        /** 本轮生效模型（会话覆盖 > 个人默认；智能体不绑定模型。主链路解析后回填，生成流按此发送） */
        volatile String model;
        /** 归一后的深度思考（生效模型能力 + 用户开关）；随 done 写 QA 日志 deep_think */
        volatile boolean deepThink;
        /** 游客分享会话（公开链接）：工具白名单收窄为知识检索+内置项，沙盒/产物/MCP/技能不暴露 */
        volatile boolean guestMode;
        /** 本轮"有副作用"工具名单（沙盒/MCP，enabledToolCallbacks 装配时回填）：审批模式 ask 时执行前需确认 */
        volatile java.util.Set<String> sensitiveToolNames = java.util.Set.of();
        /** 工具执行审批模式（本轮智能体的 toolApprovalMode；null=auto） */
        volatile String toolApprovalMode;
        /** 单轮工具调用步数上限（agent.maxToolSteps > 全局 agent.maxToolSteps；<=0 不限制）；已执行步数 */
        volatile int maxToolSteps;
        final java.util.concurrent.atomic.AtomicInteger toolStepCount = new java.util.concurrent.atomic.AtomicInteger();
        /** 整轮流级心跳句柄（buildAnswerStream 启动、终态路径停止）：覆盖工具执行与最终回答首 token 两段静默区 */
        volatile java.util.concurrent.ScheduledFuture<?> heartbeat;
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
        /** 重新生成时被替换的旧回答消息 ID：落库前软删旧行（历史只留最新一版；null=普通问答） */
        volatile String replaceMessageId;

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
            // 客户端断开：顺带停整轮流级心跳（防断开后调度任务空转泄漏；发送失败也会自停，双保险）
            java.util.concurrent.ScheduledFuture<?> hb = heartbeat;
            if (hb != null) hb.cancel(false);
            Disposable d = disposableRef.get();
            if (d != null) d.dispose();
        }

        /**
         * 精确检索工具命中注册为引用来源：续编 ref 编号（与主链路 [1..N] 同一编号空间），
         * 同块已注册/已在主链路则复用原编号不重复注册；返回分配的编号供工具文本【引用N】提示模型标注。
         * origin=TOOL 供前端区分工具来源；synchronized 防工具线程与流回调并发追加。
         */
        /**
         * 工具命中注册：来源进 sources 续编引用编号；来源图片并入全局图片编号体系
         * （imgIndex/imgDescIndex，同一 URL 全局只编号一次，编号与主链路上下文填充连续）。
         * 描述从块原文的 [图片]/[图片：描述] 占位按出现顺序对齐（第 i 个占位 ↔ 第 i 张图，与主链路同口径），
         * 拿不到描述留空（imgFilter rebuild 对空描述放行，不会误剔）。
         * 有新增图片时重发全量 image 事件（前端 onImage 是整体替换，必须发全量已签名清单），
         * 模型后续正文才能引用真实存在的 [图片N]，前端 images[N-1] 才映射得到。
         */
        KnowledgeRetrievalTool.SourceRegistrar.Registration registerToolSource(HybridRetrievalService.Hit h, String snippet) {
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

        // ==================== 联网搜索：引用注册 / 配额 / 降级（WebSearchTools.WebSearchSink） ====================

        /**
         * 扣减一次联网搜索配额。工具是全局单例，计数必须落在流状态上——否则一次搜索会永久占掉后续问答的额度。
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
            addDegradation(degradations, degradedCodes, code, message);
        }

        /**
         * 联网结果注册为引用来源：与库内来源**共用同一编号空间**（主链路 [1..N] 续编），
         * 前端角标是按 ref 直接索引 sources 的，另起一套编号必然错位；引用自检也因此能一并校验联网引用。
         * 去重键是归一化 URL（去 utm 等跟踪参数），同一链接不重复占号。
         *
         * @return 分配的引用编号（已注册过则返回原编号）
         */
        @Override
        public int register(WebSearchResult r) {
            String key = com.wisesoft.ai.service.websearch.WebSearchService.normalizeUrl(r.url());
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
                sendSseEvent(emitter, "image", JSON.toJSONString(signed), sessionId);
            }
            return seqs;
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
    }

    /**
     * 未闭合/已闭合标签剥离后，clean 尾部可能压着「标签的真前缀」（跨 token 切分），
     * 需要扣留等下一个 token 补全。返回需扣留的后缀长度：只扣真前缀，其余全部立即下发。
     * <p>此前实现固定扣 19 字符：正文 token 恒滞后，而 tool_status/process/artifact 事件即时下发，
     * 旁路事件越过未下发正文 ⇒ 前端实时时间线错序断词。改为精确扣留后尾缀通常长 0（正文零延迟下发）。
     */
    private static int tagPrefixSuffixLen(String s) {
        if (s.isEmpty()) return 0;
        // 真前缀最长 = 闭合标签长度 - 1 = 9（"</related" / "</process"）
        int start = Math.max(0, s.length() - 9);
        for (int i = start; i < s.length(); i++) {
            String tail = s.substring(i);
            if (isTagPrefix(tail)) return s.length() - i; // 最小 i = 最长后缀，一次扣足
        }
        return 0;
    }

    /** tail 是否为四个标签中任一标签的真前缀（含单纯的 "<"） */
    private static boolean isTagPrefix(String tail) {
        return (tail.length() < "<related>".length() && "<related>".startsWith(tail))
                || (tail.length() < "</related>".length() && "</related>".startsWith(tail))
                || (tail.length() < "<process>".length() && "<process>".startsWith(tail))
                || (tail.length() < "</process>".length() && "</process>".startsWith(tail));
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
     * 判断字符串中是否存在未完整闭合的 process 块（与 containsUnclosedRelated 同规则，标签族不同）
     */
    static boolean containsUnclosedProcess(String s) {
        if (s.contains("</process>")) {
            // 已有关闭标签：剔除完整块后，剩余部分若还有 process 痕迹则视为未闭合
            String rest = s.replaceAll("<process>[\\s\\S]*?</process>", "");
            return rest.contains("<process") || isProcessStart(rest) || isProcessEndStart(rest);
        }
        return s.contains("<process") || isProcessStart(s) || isProcessEndStart(s);
    }

    /** 判断字符串末尾是否为 <process> 标签的部分前缀（捕获跨 token 分割的开始标签） */
    private static boolean isProcessStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "<process>".length() && "<process>".startsWith(tail);
    }

    /** 判断字符串末尾是否为 </process> 标签的部分前缀（捕获跨 token 分割的闭合标签） */
    private static boolean isProcessEndStart(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String tail = s.substring(lt);
        return tail.length() < "</process>".length() && "</process>".startsWith(tail);
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
     * agentId 为空（前端「默认（全局配置）」/ 调用方未指定）：有默认智能体（isDefault=1）时
     * 即用默认智能体——「全局 = 默认智能体」，未填维度仍继承系统设置（智能体覆盖机制本就如此）；
     * 未设默认智能体才走纯系统设置全局配置。首问会把解析结果锁定进会话，归属/trace 随之正确。
     */
    private Agent resolveAgent(String agentId, String question, String resolvedModel,
                               String sessionId, SseEmitter emitter) {
        if (agentId == null || agentId.isBlank()) return agentService.defaultAgent();
        if (!"auto".equals(agentId)) return agentService.get(agentId);
        List<Agent> candidates = agentService.dispatchCandidates();
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1 || !configService.getBoolean("agent.autoDispatch")) {
            Agent direct = candidates.size() == 1 ? candidates.get(0) : agentService.defaultAgent();
            emitDispatched(emitter, direct, candidates.size(), false, sessionId);
            return direct;
        }
        // 阶段文案统一：自动派遣属"理解问题"准备段（派中了谁由 dispatched 事件承载）
        sendSseEvent(emitter, "stage", "正在理解问题…", sessionId);
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

    /**
     * 会话级智能体解析（4.1：会话绑定）：智能体不再是"每轮随请求变化"，而是会话创建后首问锁定、全程一致。
     * <ul>
     *   <li>已锁定会话：一律用锁定值，请求里的 agentId 不再生效——人设/知识库/工具集全程一致，
     *       与交互约定「切换智能体 = 新会话」配套（否则就是同一个会话里悄悄换人）。</li>
     *   <li>未锁定会话：按请求解析（auto=自动派遣，只在首问路由一次），解析结果写入会话锁定。</li>
     * </ul>
     * 锁定值不可用时 fail-loud 提示（不静默回落全局配置）：那等于悄悄换人，用户无从察觉。
     */
    private Agent resolveSessionAgent(String sessionId, String requestedAgentId, String question,
                                      String resolvedModel, SseEmitter emitter,
                                      List<Map<String, String>> degradations, Set<String> degradedCodes) {
        SessionService.AgentBinding bound = sessionService.getAgentBinding(sessionId);
        if (bound != null) {
            if (bound.agentId().isEmpty()) return null; // 已决定不绑定智能体（走全局配置）
            Agent locked = agentService.get(bound.agentId());
            if (locked == null) {
                String name = (bound.agentName() == null || bound.agentName().isBlank())
                        ? bound.agentId() : bound.agentName();
                addDegradation(degradations, degradedCodes, "agentUnavailable",
                        "本会话绑定的智能体「" + name + "」已不可访问（已删除或权限变更），本轮未使用它；如需换用其它智能体请新建会话");
                log.warn("[AGENT] 会话 {} 绑定的智能体 {} 不可访问，本轮按不可用处理", sessionId, bound.agentId());
                return null;
            }
            if (requestedAgentId != null && !requestedAgentId.isBlank()
                    && !"auto".equals(requestedAgentId) && !requestedAgentId.equals(bound.agentId())) {
                log.info("[AGENT] 会话 {} 已锁定智能体 {}，忽略请求中的 agentId={}（换人请新建会话）",
                        sessionId, bound.agentId(), requestedAgentId);
            }
            return locked;
        }
        // 未锁定：按请求解析（auto → 首问自动派遣），结果即锁定，后续轮次不再重路由
        Agent picked = resolveAgent(requestedAgentId, question, resolvedModel, sessionId, emitter);
        // 用户显式指定了智能体却拿不到（已删除或无权访问）：不静默改用全局配置（等于悄悄换人），明确告知。
        // auto 派遣无候选是设计内回落（候选为空时本就走全局），不在此列。
        if (picked == null && requestedAgentId != null && !requestedAgentId.isBlank()
                && !"auto".equals(requestedAgentId)) {
            addDegradation(degradations, degradedCodes, "agentMissing",
                    "所选智能体不可用（已删除或你无访问权限），本轮按全局配置回答");
            log.warn("[AGENT] 会话 {} 请求的智能体 {} 不可解析，本轮按全局配置回答", sessionId, requestedAgentId);
        }
        boolean boundOk = sessionService.bindAgent(sessionId,
                picked == null ? "" : picked.getId(),
                picked == null ? "" : picked.getName());
        if (!boundOk) {
            // 写库未生效（并发首问各写一次 / 写库异常）：下轮会再解析一次，理论上可能换人——告警便于排查
            log.warn("[AGENT] 会话 {} 智能体绑定未写入（并发或写库失败），下一轮将重新解析", sessionId);
        } else if (picked != null) {
            log.info("[AGENT] 会话 {} 首问锁定智能体 {}（{}）", sessionId, picked.getId(), picked.getName());
        } else {
            log.info("[AGENT] 会话 {} 首问未使用智能体（锁定为全局配置）", sessionId);
        }
        // 绑定结果就地广播（不等整轮 done）：锁定发生在首问开始，前端也应在当轮就切到锁定态，
        // 否则用户要等回答结束才看到"已绑定"，误以为锁定是在会话结束时才做的。
        emitAgentBound(emitter, sessionId, picked);
        return picked;
    }

    /** 会话级绑定结果下发：{locked,agentId,agentName}（agentId 空串表示已绑定为"不使用智能体"） */
    private void emitAgentBound(SseEmitter emitter, String sessionId, Agent bound) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("locked", true);
            info.put("agentId", bound == null ? "" : bound.getId());
            info.put("agentName", bound == null ? "" : bound.getName());
            sendSseEvent(emitter, "agent_bound", JSON.toJSONString(info), sessionId);
        } catch (Exception e) {
            log.debug("[AGENT] 绑定结果事件下发失败（不影响问答）: {}", e.getMessage());
        }
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

    /**
     * @ 引用的检索范围解析（控制器已完成存在性与可见性校验，这里只做形态归类）。
     * <p>{@code docKbIds}：被引文档所属库——@ 文档时检索范围同样收窄到这些库，
     * 否则别的库的块会与「强制前置」的文档块竞争上下文名额。
     */
    private record MentionScope(Set<String> kbIds, Set<String> docIds, Set<String> docKbIds) {
        static MentionScope of(List<ChatRequest.Mention> mentions) {
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
    private List<Knowledge> loadMentionChunks(MentionScope scope, List<Map<String, String>> degradations,
                                              Set<String> degradedCodes) {
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
                addDegradation(degradations, degradedCodes, "mentionEmpty",
                        "你引用的文档还没有可用的解析内容，本轮不会把它作为参考资料");
            } else {
                log.info("[MENTION] @ 引用文档块前置 {} 块（{} 篇，每篇上限 {}）",
                        out.size(), scope.docIds().size(), perDoc);
            }
            return out;
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] @ 引用文档块加载失败: {}", e.toString());
            addDegradation(degradations, degradedCodes, "mentionLoadFailed", "引用的文档内容读取失败，本轮未带入");
            return List.of();
        }
    }

    /** @ 引用文档块的文本化（供「不使用知识库」分支直接用：该分支不检索，把被引内容作为参考资料注入问题） */
    private String buildMentionText(List<Knowledge> chunks) {
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
        String inner = null;
        if (m.find()) {
            inner = m.group(1);
        } else {
            // 未闭合兜底：思考被长度/异常截断时，检索计划恰好在末尾（<search> 有开头没结尾）——
            // 按闭合匹配会整块丢失，多路检索静默退化为单路。取最后一个开标签到文本末尾。
            Matcher om = Pattern.compile("<" + escapedTag + ">([\\s\\S]*)$").matcher(thinking == null ? "" : thinking);
            if (om.find()) {
                inner = om.group(1);
                log.info("[DEEP-THINK] 检索计划标签未闭合（思考截断），按未闭合提取");
            }
        }
        if (inner != null) {
            String[] parts = inner.split("\\|");
            // 模型不守"|"格式的常见变体：换行/分号分隔的列表——只有一段但含这些分隔符时再切一次
            if (parts.length == 1 && (inner.contains("\n") || inner.contains("；") || inner.contains(";"))) {
                parts = inner.split("\\r?\\n|；|;");
            }
            List<String> list = Arrays.stream(parts)
                    .map(s -> s.replaceFirst("^\\s*(?:\\d+[.、)）]|[-*•])\\s*", "").trim()) // 清理 "1. " / "- " 编号前缀
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

    /** 工具执行审批挂起项：approvalId → 等待用户批准（内存态；进程重启/刷新页面即失效，超时自动拒绝） */
    private record PendingApproval(String sessionId, String userId, String toolName,
                                   java.util.concurrent.CompletableFuture<Boolean> future) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, PendingApproval> PENDING_APPROVALS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 用户裁决工具审批：仅发起该轮问答的用户本人可批（uid 比对，内存态与 DB 双重校验）；
     * 内存态丢失（进程重启/已超时）时仍可更新 DB 审计记录（幂等），但无法唤醒已死的工具线程。
     */
    public boolean resolveApproval(String approvalId, boolean approved, String uid) {
        if (approvalId == null || approvalId.isBlank()) return false;
        String status = approved ? "APPROVED" : "REJECTED";
        boolean dbOk = markApprovalResolved(approvalId, status, uid);
        PendingApproval p = PENDING_APPROVALS.get(approvalId);
        if (p == null) {
            // 内存态缺失：DB 更新成功说明是合法裁决（或已被裁决过），返回 dbOk；线程已不可唤醒
            return dbOk;
        }
        if (uid == null || !uid.equals(p.userId())) {
            log.warn("[TOOL] 审批人非本轮用户，拒绝: approvalId={} by={}", approvalId, uid);
            return false;
        }
        return p.future().complete(approved);
    }

    /** 更新审批记录为终态（幂等：已非 PENDING 直接返回 false；uid 不匹配拒绝）。best-effort 不抛 */
    private boolean markApprovalResolved(String approvalId, String status, String uid) {
        try {
            com.wisesoft.ai.model.ToolApproval rec = toolApprovalMapper.selectById(approvalId);
            if (rec == null) return false;
            if (uid != null && !uid.equals(rec.getUserId())) {
                log.warn("[TOOL] 审批人非本轮用户（DB），拒绝: approvalId={} by={}", approvalId, uid);
                return false;
            }
            if (!"PENDING".equals(rec.getStatus())) return false; // 已裁决，幂等
            toolApprovalMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.wisesoft.ai.model.ToolApproval>()
                    .eq(com.wisesoft.ai.model.ToolApproval::getId, approvalId)
                    .set(com.wisesoft.ai.model.ToolApproval::getStatus, status)
                    .set(com.wisesoft.ai.model.ToolApproval::getResolvedAt, java.time.LocalDateTime.now()));
            return true;
        } catch (Exception e) {
            log.warn("[TOOL] 审批裁决落库失败: {}", e.getMessage());
            return false;
        }
    }

    /** 启动期清理遗留 PENDING（进程重启后内存态丢失，DB 中超时未裁决的记录置 TIMEOUT，避免审计永久挂起） */
    @jakarta.annotation.PostConstruct
    public void purgeStaleApprovals() {
        try {
            long timeoutSec = approvalTimeoutMs() / 1000 + 5;
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusSeconds(timeoutSec);
            toolApprovalMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.wisesoft.ai.model.ToolApproval>()
                    .eq(com.wisesoft.ai.model.ToolApproval::getStatus, "PENDING")
                    .lt(com.wisesoft.ai.model.ToolApproval::getCreatedAt, cutoff)
                    .set(com.wisesoft.ai.model.ToolApproval::getStatus, "TIMEOUT")
                    .set(com.wisesoft.ai.model.ToolApproval::getResolvedAt, java.time.LocalDateTime.now()));
        } catch (Exception e) {
            log.warn("[TOOL] 遗留审批清理失败（可忽略，下次启动重试）: {}", e.getMessage());
        }
    }

    /** 审批等待上限（chat.approvalTimeoutMs，默认 120s；阻塞工具调用线程，必须有界） */
    private long approvalTimeoutMs() {
        long t = configService.getLong("chat.approvalTimeoutMs");
        return t > 0 ? t : 120000L;
    }

    /** 单轮工具步数上限：智能体覆盖（null=继承全局；0=不限制；负数按继承处理） */
    private int resolveMaxToolSteps(Agent agent) {
        Integer v = agent == null ? null : agent.getMaxToolSteps();
        if (v == null || v < 0) v = configService.getInt("agent.maxToolSteps", 15);
        return Math.max(0, v);
    }

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
     * 多轮检索查询改写（指代消解）：把「对话历史 + 当前问题」交给对话模型改写成一个<b>自包含</b>的检索
     * query——消解「它/这个/上述」等指代、补全省略的主语与限定词。多轮追问（如先问「X 系统是什么」
     * 再问「它的部署要求」）按原句检索必然召回不准，这是召回准确性的第一断点。
     * <p>
     * 语义边界：改写是召回的<b>增益优化</b>，不是正确性依赖——超时/失败/返回空/与原句相同/异常膨胀
     * 一律按原句继续检索（原句是保底正确行为，不 fail-loud 阻断问答），失败记 queryRewriteFailed
     * degradation。隔离：{@link #rewriteExecutor} 专用线程 + 硬超时（retrieval.rewriteTimeoutMs
     * 默认 8s）——改写慢了宁可不用，不拖慢整轮问答的首字延迟。
     */
    private String rewriteQueryForRetrieval(String question, List<Map<String, Object>> history,
                                            String resolvedModel, List<Map<String, String>> degradations,
                                            Set<String> degradedCodes) {
        String historyText = formatHistory(history);
        String prompt = "你是检索查询改写器。根据对话历史，把「当前问题」改写成一个自包含的检索查询：\n"
                + "1. 消解指代与省略：「它/这个/该/上述」等替换为历史中的具体实体名；省略的主语与限定词补全。\n"
                + "2. 只做指代消解与补全，不要扩展新问题，不要回答问题本身。\n"
                + "3. 保留原问题的语言与术语。\n"
                + "4. 若当前问题本身已自包含（无指代、无省略），原样输出。\n"
                + "只输出改写后的查询文本，不要解释、不要加引号。\n\n"
                + "【对话历史】\n" + historyText + "\n【当前问题】\n" + question;
        try {
            long timeoutMs = configService.getLong("retrieval.rewriteTimeoutMs", 8000L);
            java.util.concurrent.Future<String> f = rewriteExecutor.submit(() ->
                    chatClient.prompt()
                            .system("你是检索查询改写器，只输出改写后的查询文本。")
                            .user(prompt)
                            .options(OpenAiChatOptions.builder()
                                    .model(resolvedModel)
                                    .temperature(0.0)
                                    .maxTokens(200)
                                    .build())
                            .call()
                            .content());
            String rewritten = f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (rewritten == null || rewritten.isBlank()) {
                addDegradation(degradations, degradedCodes, "queryRewriteFailed", "多轮查询改写返回空，按原句检索");
                return question;
            }
            String q = rewritten.trim().replaceAll("^[\"'「『]+|[\"'」』]+$", "");   // 去模型可能包的引号
            if (q.isBlank() || q.equals(question) || q.length() > 200) {
                // 与原句相同（模型判自包含）或异常膨胀（跑题/复读）→ 都按原句更稳
                return question;
            }
            log.info("[RETRIEVE] 多轮改写生效: \"{}\" -> \"{}\"", question, q);
            return q;
        } catch (java.util.concurrent.TimeoutException te) {
            addDegradation(degradations, degradedCodes, "queryRewriteFailed", "多轮查询改写超时（retrieval.rewriteTimeoutMs），按原句检索");
            return question;
        } catch (Exception e) {
            addDegradation(degradations, degradedCodes, "queryRewriteFailed", "多轮查询改写失败，按原句检索: " + e.getMessage());
            return question;
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

    // ==================== M4：智能体绑定工作流（chatflow 语义） ====================

    /**
     * 智能体绑定工作流后的问答路径（M4）：会话每轮把用户问题送进图，end 节点的出参即回答。
     * <p>
     * 与常规链路的分工：绑定了工作流的智能体，<b>行为由工作流定义</b>——智能体自带的人设 /
     * 知识库范围 / 工具开关都不参与（那些是"预设型智能体"的维度）。两套逻辑同时生效会让回答
     * 不可预测，故这里整条走工作流，不做混合。
     * <p>
     * 流式：llm 节点的 token 经 {@code WorkflowRunCtx#tokenSink} 透传到这里，逐块转 SSE token 事件；
     * 最终回答以 end 节点出参为准（done.finalContent 覆盖流式累积——多 llm 节点或模板聚合时
     * 出参可能不等于任一节点的原文，这是设计使然，不是不一致）。
     */
    private void runWorkflowChat(String sessionId, String question, String userId, SseEmitter emitter,
                                 long startTime, List<Map<String, String>> degradations,
                                 Set<String> degradedCodes, Agent agent, boolean guestMode, boolean regenerate,
                                 String replaceMessageId) {
        sendSseEvent(emitter, "plan", JSON.toJSONString(List.of("执行工作流")), sessionId);
        sendSseEvent(emitter, "stage", "正在执行工作流…", sessionId);
        java.util.concurrent.ScheduledFuture<?> heartbeat = scheduleKeepalive(emitter, "工作流");
        try {
            if (!regenerate) {
                sessionService.appendMessage(sessionId, "user", question, null, null, null, null, null, null, null);
            }
            Map<String, Object> inputs = buildWorkflowInputs(sessionId, question, degradations, degradedCodes);
            StringBuilder streamed = new StringBuilder();
            // 图片机制（与主链路同口径）：retrieval 节点完成 [图片N] 编号后回调累计原始 URL 列表——
            // 签名后发 SSE image 事件（时序先于 LLM token，前端按 images[N-1] 渲染回答里的 [图片N]），
            // 原始列表留给落库（历史接口存原始 URL、读时动态签名）。回调抛错不打断工作流（fail-soft 与 tokenSink 同口径）
            List<String> workflowImages = new ArrayList<>();
            WorkflowService.Principal p = WorkflowService.Principal.current();   // 流水线线程已 loadIdentity(userId)
            WorkflowRun run = workflowServiceProvider.getObject()
                    .runForAgent(agent.getWorkflowId(), inputs, p, token -> {
                        streamed.append(token);
                        sendSseEvent(emitter, "token", token, sessionId);
                    }, imgs -> {
                        if (imgs == null || imgs.isEmpty()) return;
                        synchronized (workflowImages) {
                            workflowImages.clear();
                            workflowImages.addAll(imgs);
                        }
                        List<String> signed = imgs.stream().map(imageUrlSigner::signUrl).toList();
                        sendSseEvent(emitter, "image", JSON.toJSONString(signed), sessionId);
                    });
            if (!"success".equals(run.getStatus())) {
                String reason = run.getError() == null || run.getError().isBlank()
                        ? "工作流运行未成功（状态 " + run.getStatus() + "）" : run.getError();
                log.warn("[WORKFLOW-CHAT] 工作流运行失败: session={} run={} : {}", sessionId, run.getId(), reason);
                sendSseEvent(emitter, "error", "工作流执行失败：" + reason, sessionId);
                completeEmitter(emitter);
                return;
            }
            String answer = workflowAnswerOf(run);
            Map<String, Object> tokens = workflowTokensOf(run);
            // 重新生成：先软删被替换的旧回答（与主链路口径一致，避免历史出现两条答案）
            if (replaceMessageId != null && !replaceMessageId.isBlank()) {
                sessionService.deleteMessage(replaceMessageId);
            }
            String messageId = sessionService.appendMessage(sessionId, "assistant", answer,
                    workflowImages.isEmpty() ? null : List.copyOf(workflowImages), null, null, null, null, null,
                    null, JSON.toJSONString(tokens), null, null,
                    agent.getId(), agent.getName());
            qaLogService.logAsync(sessionId, question, answer, List.of(), false,
                    System.currentTimeMillis() - startTime, question, null, false,
                    messageId, agent.getId());
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("sources", List.of());
            donePayload.put("related", List.of());
            donePayload.put("messageId", messageId);
            donePayload.put("finalContent", answer);
            // 与主链路一致地动态签名（前端 onDone 用 finalImages 覆盖流式期间已签名的 images；
            // 落库存的是原始 URL，此处仅对下发副本签名）
            donePayload.put("finalImages", workflowImages.stream().map(imageUrlSigner::signUrl).toList());
            donePayload.put("degradations", degradations);
            donePayload.put("artifacts", List.of());
            donePayload.put("toolCalls", List.of());
            donePayload.put("tokens", tokens);
            // 工作流运行信息：前端可展示"本次回答由工作流 v{n} 产出，耗时 xx"
            Map<String, Object> wfInfo = new LinkedHashMap<>();
            wfInfo.put("workflowId", run.getWorkflowId());
            wfInfo.put("version", run.getVersion());
            wfInfo.put("runId", run.getId());
            wfInfo.put("status", run.getStatus());
            wfInfo.put("durationMs", run.getDurationMs());
            donePayload.put("workflowRun", wfInfo);
            SessionService.AgentBinding finalBinding = sessionService.getAgentBinding(sessionId);
            if (finalBinding != null) {
                donePayload.put("agentLocked", true);
                if (!finalBinding.agentId().isEmpty()) {
                    donePayload.put("agentId", finalBinding.agentId());
                    donePayload.put("agentName", finalBinding.agentName() == null ? "" : finalBinding.agentName());
                }
            }
            sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), sessionId);
            completeEmitter(emitter);
            // 记忆提取：工作流节点用的是自己的模型，这里按「用户个人默认聊天模型」提取长期记忆
            // （没有配置默认模型就不提取——记忆提取必须有模型，不传空引用去让下游猜/报内部错）
            com.wisesoft.ai.model.User prefUser = loadPrefUser(userId);
            String memoryModel = prefUser == null ? null : prefUser.getDefaultModel();
            if (memoryModel != null && !memoryModel.isBlank()) {
                userMemoryService.maybeExtract(userId, sessionId, question, answer, guestMode, memoryModel);
            } else {
                log.debug("[WORKFLOW-CHAT] 未配置个人默认模型，跳过长期记忆提取: uid={}", userId);
            }
            log.info("[WORKFLOW-CHAT] 工作流回答完成: session={} run={} v={} 耗时 {}ms",
                    sessionId, run.getId(), run.getVersion(), run.getDurationMs());
        } catch (com.wisesoft.ai.common.BizException e) {
            log.warn("[WORKFLOW-CHAT] 工作流执行被拒绝: session={} : {}", sessionId, e.getMessage());
            sendSseEvent(emitter, "error", e.getMessage(), sessionId);
            completeEmitter(emitter);
        } catch (Exception e) {
            log.error("[WORKFLOW-CHAT] 工作流执行异常: session={} : {}", sessionId, e.toString());
            sendSseEvent(emitter, "error", "工作流执行异常：" + e.getMessage(), sessionId);
            completeEmitter(emitter);
        } finally {
            heartbeat.cancel(false);
        }
    }

    /**
     * 工作流开始节点的入参：固定提供 {@code question}（本轮问题）与 {@code history}（近期多轮文本）。
     * 只有 start 节点<b>声明过</b>的键才会被真正引用（引擎只校验声明项，多给的键不影响执行），
     * 因此多轮能力是"工作流声明了 history 才生效"，不必为它单独开关。
     */
    private Map<String, Object> buildWorkflowInputs(String sessionId, String question,
                                                    List<Map<String, String>> degradations,
                                                    Set<String> degradedCodes) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("question", question == null ? "" : question);
        List<Map<String, Object>> recent = sessionService.getRecentHistory(sessionId,
                configService.getInt("chat.historyRounds", 5));
        if (recent == null) {
            addDegradation(degradations, degradedCodes, "historyFailed", "会话历史读取失败，本次无多轮记忆");
            recent = List.of();
        }
        inputs.put("history", buildHistoryText(recent));
        return inputs;
    }

    /**
     * 结束节点出参 → 回答文本：优先取 {@code answer} 键；没有该键但只有唯一出参时取它；
     * 多出参且无 answer → fail-loud（不明确指定就把某个出参当回答，是猜）。
     */
    private String workflowAnswerOf(WorkflowRun run) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        try {
            outputs = JSON.parseObject(run.getOutputs() == null ? "{}" : run.getOutputs());
        } catch (Exception ignored) {
        }
        if (outputs == null || outputs.isEmpty()) {
            throw new com.wisesoft.ai.common.BizException("工作流的结束节点没有产出任何出参，无法作为回答");
        }
        Object answer = outputs.get("answer");
        if (answer == null && outputs.size() == 1) answer = outputs.values().iterator().next();
        if (answer == null) {
            throw new com.wisesoft.ai.common.BizException("工作流的结束节点未声明 answer 出参（当前出参："
                    + String.join("、", outputs.keySet()) + "）——请在结束节点里把回答映射到 answer");
        }
        String text = String.valueOf(answer);
        return text.isBlank() ? "（工作流未产出回答内容）" : text;
    }

    /** 本次运行的 token 汇总（各节点 trace 相加；拿不到算 0，不臆造数字） */
    private Map<String, Object> workflowTokensOf(WorkflowRun run) {
        int prompt = 0, completion = 0;
        try {
            JSONArray arr = JSON.parseArray(run.getNodeTraces() == null ? "[]" : run.getNodeTraces());
            if (arr != null) {
                for (Object o : arr) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Object p = m.get("promptTokens");
                    Object c = m.get("completionTokens");
                    if (p instanceof Number n) prompt += n.intValue();
                    if (c instanceof Number n) completion += n.intValue();
                }
            }
        } catch (Exception ignored) {
        }
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("prompt", prompt);
        tokens.put("output", completion);
        tokens.put("total", prompt + completion);
        return tokens;
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
                                    String mentionText,
                                    java.util.concurrent.ScheduledFuture<?> preHeartbeat,
                                    SseEmitter emitter, long startTime,
                                    String[] thinkingHolder, List<Map<String, String>> degradations,
                                    Set<String> degradedCodes, Agent agent, Map<String, Long> stageMs,
                                    String resolvedModel, boolean guestMode, String replaceMessageId) {
        try {
            // 角色段（与主链路同源）+ 明确告知模型本轮无参考资料、按自身知识作答；
            // 例外：用户 @ 了文档（mentionText 非空）时有参考资料，引用规则按主链路口径放开
            boolean hasMention = mentionText != null && !mentionText.isBlank();
            StringBuilder system = new StringBuilder(resolveSystemPrompt(agent))
                    .append("\n\n【本轮对话说明】\n")
                    .append(hasMention
                            ? "本助手未启用知识库检索，但用户本轮显式引用了指定文档（见下方【本轮显式引用的资料】）。"
                              + "回答时请以该资料为准，并在引用处标注对应的 [N] 编号。"
                            : "本助手未启用知识库检索。请基于你自身的知识与对话上下文直接回答，"
                              + "不要输出 [N] 来源标注（本轮没有参考资料）。");
            // 用户长期记忆（与主链路同口径；游客分享会话不注入）
            if (!guestMode) {
                String memoryText = userMemoryService.injectText(userId, question);
                if (memoryText != null) system.append("\n\n").append(memoryText);
            }
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
            if (hasMention) {
                userQuestion.append("\n\n【本轮显式引用的资料】用户通过 @ 指定了以下文档内容，"
                        + "回答时请优先依据这些资料，并在引用处标注 [N] 编号：\n").append(mentionText);
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
            st.guestMode = guestMode; // 游客分享会话：工具只保留知识检索与内置项
            st.replaceMessageId = replaceMessageId; // 重新生成：落库前软删被替换的旧回答
            st.toolApprovalMode = agent == null ? null : agent.getToolApprovalMode(); // 有副作用工具审批模式
            st.maxToolSteps = resolveMaxToolSteps(agent); // 单轮工具步数上限（智能体覆盖 > 全局）
            st.heartbeat = preHeartbeat; // 前置心跳句柄移交（终态照旧停止）
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
