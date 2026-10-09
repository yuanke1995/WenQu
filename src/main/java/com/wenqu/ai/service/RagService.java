package com.wenqu.ai.service;

import com.wenqu.ai.config.ConfigDefaults;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.config.AppProperties;
import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.model.Knowledge;
import com.wenqu.ai.model.KnowledgeBase;
import com.wenqu.ai.model.ModelInfo;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.model.WorkflowRun;
import com.wenqu.ai.service.websearch.WebSearchResult;
import com.wenqu.ai.service.websearch.WebSearchTools;
import com.wenqu.ai.util.TokenCounter;
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
import java.util.concurrent.LinkedBlockingQueue;
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

    /**
     * 代码段（``` 围栏块或行内反引号）：其中的 [数字] 是代码字面量（数组下标、字面量标注等），
     * 不是引用标注——引用越界剔除/重编必须跳过，否则会把 `args[0]` 这类代码改坏。
     * 未闭合围栏消费到串尾（按代码处理，宁可不剔不可误改）。
     */
    private static final Pattern CODE_SEGMENT = Pattern.compile("```.*?(?:```|\\Z)|`[^`\n]*`", Pattern.DOTALL);

    /** 代码段区间列表（[start,end)），供引用标注处理跳过代码字面量 */
    private static List<int[]> codeRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        if (text == null || text.isEmpty()) return ranges;
        Matcher m = CODE_SEGMENT.matcher(text);
        while (m.find()) ranges.add(new int[]{m.start(), m.end()});
        return ranges;
    }

    /** 位置是否落在代码段区间内 */
    private static boolean inCodeSegment(List<int[]> ranges, int pos) {
        for (int[] r : ranges) {
            if (pos >= r[0] && pos < r[1]) return true;
        }
        return false;
    }

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
                // 文案说清后果而不只是机制：「按融合分排序」用户看不懂，
                // 真正影响是引用没过语义筛选，弱相关块会混进上下文与引用面板
                addDegradation(degradations, degradedCodes, "rerankUnavailable",
                        "重排不可用（" + reason + "）：本轮按融合分排序，引用未做语义筛选，相关性可能偏低");
            } else {
                hits = rerankService.rank(hits, query);
            }
        }
        return hits;
    }

    /** 改写结果（实际检索用 query + 命中） */

    /** 引用摘要截断长度 */
    private static final int SNIPPET_LEN = 80;

    /** 知识块填充的预算保留下限（token）：固定部分（system+问题）吃满预算时，至少留这点空间给首块资料。
     *  原设置页参数 chat.remainTokenFloor 已退役——装配工程细节易被误解为压缩参数，固定为常量（压缩与其无关） */
    private static final int REMAIN_TOKEN_FLOOR = 800;

    /** 首块资料超预算硬截断时至少保留的字符数（原 chat.truncateFallbackChars，随设置页一并退役为常量） */
    private static final int TRUNCATE_FALLBACK_CHARS = 200;

    /** <related> 下一步建议条数（retrieval.relatedCount 可配，默认 3）：提示词里的示例与数量保持一致 */
    private String relatedPromptLine() {
        int n = Math.max(1, configService.getInt("retrieval.relatedCount", 3));
        StringBuilder ex = new StringBuilder("建议1");
        for (int i = 2; i <= n; i++) ex.append("|建议").append(i);
        return "\n回答末尾用 <related>" + ex + "</related> 输出 " + n
                + " 条下一步建议（用 | 分隔）：结合本轮回答与用户目标给出，可以是想确认清楚的追问、值得深入的方向、"
                + "也可以是马上能执行的操作，几条建议的类型不要雷同；每条要具体、可直接点选，如无合适建议可不输出。";
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
    /** 中断兜底落库时追加在正文尾部的截断标记：刷新/历史可见的 fail-loud 提示。
     *  落在时间线区间之外，前端 restore 后由尾段兜底渲染成独立的引用块 */
    private static final String TRUNCATION_SUFFIX = "\n\n> ⏹ 回答在此处被中断，以上为已生成的部分";
    /**
     * 用户主动叫停本轮时追加在正文尾部的标记。与 TRUNCATION_SUFFIX 区分：那是通道断开/看门狗掐的，
     * 这是用户按下的停止——历史里要能看出「这一轮是我让它停的」，而不是「回答自己断了」。
     */
    private static final String STOP_SUFFIX = "\n\n> ⏹ 已停止本轮，以上为已生成的部分";
    /**
     * 整轮失败（模型流抛异常 / 工具失败冒泡）时追加在正文尾部的标注。
     * 与 TRUNCATION_SUFFIX 区分：中断是「本来还能继续」，失败是「这次没能答完」。
     * 工具卡片已经记录了失败原因，这行只交代正文本身的完整性，刷新后仍在。
     */
    private static final String STREAM_ERROR_SUFFIX =
            "\n\n> ⚠️ 本轮回答未能完成，以上为已生成的部分（失败的工具调用记录见下方）";
    /** 网关 finish_reason=length（输出达长度上限）时的用户可见标注：正文尾部直书，刷新后仍在 */
    private static final String LENGTH_TRUNCATED_SUFFIX =
            "\n\n> ⏹ 回答写到长度上限被截断，可让我接着说完，或重新生成";
    /** 生成量与正文字数严重不符（内容在生成链路里丢失）时的用户可见标注 */
    private static final String OUTPUT_LOST_SUFFIX =
            "\n\n> ⚠️ 这次回答不完整，有内容在生成过程中丢失，建议重新生成";
    /** 推荐块条目超过此长度即视为「正文被写进 related」，并回回答而不是剥离丢弃 */
    private static final int RELATED_PROSE_MIN_CHARS = 80;
    /** 生成量对账门槛：最终轮输出不足此 token 数时不参与对账（短回答的字符/token 比波动太大） */
    private static final int OUTPUT_LOSS_MIN_TOKENS = 150;
    /** 生成量对账比例：中文字符/token 实测 0.7~1.4，低于 0.15 才认定有内容丢失（留足误判余量） */
    private static final double OUTPUT_LOSS_CHAR_PER_TOKEN = 0.15;

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
    /** 知识库精确检索工具（Function Calling；由 tool.* 配置开关控制，默认开启） */
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;
    /** 产物交付服务（会话 emitter 注册表 + 文件落盘 + SSE 下发） */
    private final ArtifactService artifactService;
    /** MCP 客户端服务（外部 MCP server 工具接入；按用户隔离连接池，各人连各人的服务） */
    private final McpClientService mcpClientService;

    /** 知识库服务：解析「智能体关联的知识库 → 允许检索的文档集合」，检索按库隔离 */
    private final KnowledgeBaseService knowledgeBaseService;

    /** 角色服务：提问者是否管理员级（内置 admin/superadmin 或自定义 admin_flag 角色）——回答按角色收敛管理端内容 */
    private final RoleService roleService;

    /** 用户表（个人默认模型解析） */
    private final com.wenqu.ai.mapper.UserMapper userMapper;
    /** 产物交付工具（Function Calling；生成文件并实时推送） */
    private final PresentArtifactTool presentArtifactTool;
    /** 内置高频工具（计算/当前时间/日期差等，tool.builtin.enabled 控制，默认开） */
    private final BuiltinTools builtinTools;
    /** 联网搜索工具（webSearch.enabled 控制，默认关；结果注册进引用体系，与知识库来源同编号） */
    private final WebSearchTools webSearchTools;
    /** 沙盒工具（execute / read_file / write_file / ls；tool.sandbox.enabled 控制，默认关，依赖 provisioner 服务） */
    private final SandboxTools sandboxTools;
    /** 技能（Skills）：清单注入 system prompt + readSkill 工具的服务端（技能为个人资产，按 uid 取） */
    private final SkillService skillService;
    /** 个人配置覆盖（个人设置 → 对话偏好）：流水线线程装载 ThreadLocal，本轮 configService.get 优先个人值 */
    private final UserConfigService userConfigService;
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
    private final com.wenqu.ai.mapper.ToolApprovalMapper toolApprovalMapper;
    /** @ 引用：被引文档的块直接前置进上下文（不经检索，不参与相关性门） */
    private final com.wenqu.ai.mapper.KnowledgeMapper knowledgeMapper;
    /** 站内通知：工具审批待决（tool.approval）通知发起人。旁路语义 */
    private final NotificationService notificationService;
    /** 会话事件账本：结构性事件按写入顺序留痕（工具/人在回路/编排/轮终态/停止）。旁路语义，不进模型输入 */
    private final SessionEventService sessionEventService;

    /** M1：查询改写专用线程池（隔离超时任务，避免占用公共池/无限堆积）。
     *  线程数随 chat.pipelineThreads 联动扩容（syncPipelineSize）：改写是每轮必经的短 LLM 调用
     *  （≤8s），固定 2 线程在百级并发下排队到超时、改写静默降级为原句——不报错但召回质量塌方 */
    private ThreadPoolExecutor rewriteExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "rewrite");
        t.setDaemon(true);
        return t;
    });

    /** 历史压缩专用线程池：与改写池分开（同轮可能先压缩再改写，共用会互相排队拉长首字时延）。
     *  线程数随 chat.pipelineThreads 联动（syncPipelineSize）：压缩是偶发的长 LLM 调用（≤20s），
     *  排队只延迟压缩不影响本轮可答性，给流水线并发的四分之一即可。
     *  直接构造 ThreadPoolExecutor（Executors 工厂返回不可转型的包装类，调容需 setCore/Max） */
    private ThreadPoolExecutor compressExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "history-compress");
        t.setDaemon(true);
        return t;
    });

    /** 问答流水线线程池：图片描述 join/改写/检索/深度思考等重活都在独立线程执行，避免占满 Tomcat 请求线程；
     *   chat.pipelineThreads 可调（保存即生效），队列有界，满时快速失败返回"系统繁忙" */
    private ThreadPoolExecutor pipelineExecutor;

    @jakarta.annotation.PostConstruct
    void initPipeline() {
        syncPipelineSize();
        warmUpToolSchemas();
    }

    /**
     * 启动期单线程预热工具 schema：@Tool 回调的 inputSchema 由 victools/Jackson 首次
     * introspection 时生成，而 Jackson 对同一类型的并发首采会抛
     * 「Conflicting property-based creators」（record 的 canonical 构造器被两个线程同时收集，
     * 8091 百并发压测实测复现：重启后头一批并发问答随机「系统处理异常」）。
     * 启动期串行生成一遍，Jackson 的 introspection 缓存填充后，后续并发生成只读缓存。
     */
    private void warmUpToolSchemas() {
        try {
            for (org.springframework.ai.tool.ToolCallback cb :
                    org.springframework.ai.support.ToolCallbacks.from(builtinTools)) {
                cb.getToolDefinition().inputSchema();
            }
            for (org.springframework.ai.tool.ToolCallback cb :
                    org.springframework.ai.support.ToolCallbacks.from(knowledgeRetrievalTool)) {
                cb.getToolDefinition().inputSchema();
            }
            log.info("[TOOL] 工具 schema 预热完成（规避并发首采竞态）");
        } catch (Exception e) {
            log.warn("[TOOL] 工具 schema 预热失败（不影响启动，仅并发首轮有竞态风险）: {}", e.getMessage());
        }
    }

    /** 提交前同步流水线线程数（chat.pipelineThreads，DB 配置保存即生效）。
     *  默认 32：8 线程 + 64 队列在百级并发下第 73 个起直接「系统繁忙」，且深度思考轮占线程
     *  可达 50s——32/200 是 8C16G 单机 100 并发生成目标的起点，压测后可在设置页继续上调 */
    private void syncPipelineSize() {
        int n = Math.max(2, configService.getInt("chat.pipelineThreads", 32));
        if (pipelineExecutor == null) {
            pipelineExecutor = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(200), r -> {
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
        // 辅助池联动：改写=每轮一次的短调用给一半流水线并发，压缩=偶发长调用给四分之一。
        // 不联动的话，流水线扩到百级而辅助池固定 2/1，改写与压缩成片超时静默降级
        resizePool(rewriteExecutor, Math.max(4, n / 2));
        resizePool(compressExecutor, Math.max(2, n / 4));
    }

    /** 有界线程池安全调容：全程维持 max ≥ core 的不变量（先抬 max、core 归位后再收 max） */
    private static void resizePool(java.util.concurrent.ThreadPoolExecutor pool, int n) {
        if (n <= 0 || n == pool.getCorePoolSize()) return;
        pool.setMaximumPoolSize(Math.max(pool.getMaximumPoolSize(), n));
        pool.setCorePoolSize(n);
        pool.setMaximumPoolSize(n);
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
                      RoleService roleService,
                      com.wenqu.ai.mapper.UserMapper userMapper,
                      ModelRegistryService modelRegistryService,
                      UserMemoryService userMemoryService,
                      UserConfigService userConfigService,
                      org.springframework.beans.factory.ObjectProvider<WorkflowService> workflowServiceProvider,
                      com.wenqu.ai.mapper.ToolApprovalMapper toolApprovalMapper,
                      com.wenqu.ai.mapper.KnowledgeMapper knowledgeMapper,
                      NotificationService notificationService,
                      SessionEventService sessionEventService) {
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
        this.roleService = roleService;
        this.userMapper = userMapper;
        this.userMemoryService = userMemoryService;
        this.userConfigService = userConfigService;
        this.workflowServiceProvider = workflowServiceProvider;
        this.modelRegistryService = modelRegistryService;
        this.toolApprovalMapper = toolApprovalMapper;
        this.knowledgeMapper = knowledgeMapper;
        this.notificationService = notificationService;
        this.sessionEventService = sessionEventService;
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
        chat(sessionId, question, userImages, attachments, skills, mentions, deepThink,
                agentId, modelOverride, userId, emitter, guestMode, regenerate, replaceMessageId, null);
    }

    /**
     * @param historyRefs 输入框 # 引用的会话历史消息（**已由控制器完成归属校验并按库回填内容**——
     *                    这里不再校验，只负责把内容前置进本轮上下文）
     */
    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills,
                     List<ChatRequest.Mention> mentions, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter,
                     boolean guestMode, boolean regenerate, String replaceMessageId,
                     List<ChatRequest.HistoryRef> historyRefs) {
        chat(sessionId, question, userImages, attachments, skills, mentions, deepThink,
                agentId, modelOverride, userId, emitter, guestMode, regenerate, replaceMessageId,
                historyRefs, null);
    }

    /**
     * @param reasoningLevel 本轮思考强度档位（low/medium/high/xhigh/max）：用户在本轮显式选的档位。
     *                      优先级高于模型登记的默认档位；非法/不在模型支持档位内 → 回落模型默认档位
     *                      （前端只给支持档位，后端仍做归一，避免手改请求下发越级档位）。
     *                      null/空=沿用模型登记的默认档位。
     */
    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills,
                     List<ChatRequest.Mention> mentions, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter,
                     boolean guestMode, boolean regenerate, String replaceMessageId,
                     List<ChatRequest.HistoryRef> historyRefs, String reasoningLevel) {
        // 程序化调用（定时任务/工作流/MCP/分享页）没有用户窗口档位概念：窗口一律走模型登记上限/全局默认
        chat(sessionId, question, userImages, attachments, skills, mentions, deepThink,
                agentId, modelOverride, userId, emitter, guestMode, regenerate, replaceMessageId,
                historyRefs, reasoningLevel, null);
    }

    /**
     * @param requestedContextWindow 本轮上下文窗口 token（聊天页档位所选）：仅当生效模型登记了
     *                               「最小窗口~窗口」区间时生效，越界值收敛到区间内；null 或模型未登记区间
     *                               = 用模型登记窗口（默认上限），行为与未传一致。
     */
    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills,
                     List<ChatRequest.Mention> mentions, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter,
                     boolean guestMode, boolean regenerate, String replaceMessageId,
                     List<ChatRequest.HistoryRef> historyRefs, String reasoningLevel,
                     Integer requestedContextWindow) {
        chat(sessionId, question, userImages, attachments, skills, mentions, deepThink,
                agentId, modelOverride, userId, emitter, guestMode, regenerate, replaceMessageId,
                historyRefs, reasoningLevel, requestedContextWindow, null);
    }

    /**
     * @param editVariantGroup 编辑重发：被编辑消息所在分支的版本组键（控制器同步段 prepareEditBranch 产出）。
     *                         新用户消息落库后挂同一组，与被替换的旧分支构成可切换的版本序列；null=普通问答
     */
    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills,
                     List<ChatRequest.Mention> mentions, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter,
                     boolean guestMode, boolean regenerate, String replaceMessageId,
                     List<ChatRequest.HistoryRef> historyRefs, String reasoningLevel,
                     Integer requestedContextWindow, String editVariantGroup) {
        // 程序化调用（定时任务/工作流/MCP/分享页）没有计划模式概念：一律普通回答
        chat(sessionId, question, userImages, attachments, skills, mentions, deepThink,
                agentId, modelOverride, userId, emitter, guestMode, regenerate, replaceMessageId,
                historyRefs, reasoningLevel, requestedContextWindow, editVariantGroup, false);
    }

    /**
     * @param planMode 计划模式（人在回路）：true=先产出执行计划（计划轮不执行任何工具）交用户批准/编辑，
     *                 批准后的计划注入执行轮 system 再正式回答；拒绝/超时=本轮终止。
     *                 本轮没有启用工具、或工作流驱动/游客会话时自动降级为普通回答（登记降级提示）。
     */
    public void chat(String sessionId, String question, List<String> userImages,
                     List<ChatRequest.Attachment> attachments, List<String> skills,
                     List<ChatRequest.Mention> mentions, boolean deepThink,
                     String agentId, String modelOverride, String userId, SseEmitter emitter,
                     boolean guestMode, boolean regenerate, String replaceMessageId,
                     List<ChatRequest.HistoryRef> historyRefs, String reasoningLevel,
                     Integer requestedContextWindow, String editVariantGroup, boolean planMode) {
        // 是否深度思考由用户自己决定（对话页 per-model 开关）+ 模型能力决定，平台不代为路由：
        // 管理员侧的自动路由已删除——它会覆盖用户显式关闭的选择、强制消耗用户的 token。
        final boolean useDeepThink = deepThink;
        // 会话轮级互斥：同一会话同时只允许一轮在跑。并发两问会历史交错、ArtifactService EMITTERS
        // 相互覆盖（后轮覆盖前轮，先完成者注销掉后轮的推送）、首问智能体绑定竞态。断线后台续跑
        // 与 askUser 等待期同样占坑——等待作答时再发新消息正是要挡的交错。限频/归属校验已在上游。
        if (TURN_IN_FLIGHT.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            log.warn("[FAIL-LOUD] 同会话并发问答被拒: session={}", sessionId);
            sendSseEvent(emitter, "error", "该会话上一轮还在回答中，请等回答结束或先停止本轮，再发送新消息", sessionId);
            completeEmitter(emitter);
            return;
        }
        // 本轮身份从这一刻起可用（早于流状态构造）：账本的每条事件都挂得到同一个 turnId
        TURN_HANDLES.put(sessionId, new TurnHandle(
                java.util.UUID.randomUUID().toString().replace("-", ""), userId));
        // 断开跟踪：登记查表项并绑定生命周期回调清理；发送失败也会打标（见 sendSseEvent），各等待点据此短路后续 LLM/检索开销
        ACTIVE_SSE.put(emitter, new java.util.concurrent.atomic.AtomicBoolean());
        // 整轮存活看门狗：emitter 无容器超时，截断由台账按机器耗时判定（人工等待不计入，见 TurnDeadline）。
        // 收集型通道（定时任务/MCP/子智能体节点）不挂：它没有真实响应、节奏由调用方 awaitDone 控制。
        TurnDeadline ledger = null;
        if (!(emitter instanceof CollectingSseEmitter)) {
            ledger = new TurnDeadline(turnMachineBudgetMs());
            TURN_DEADLINES.put(emitter, ledger);
            startTurnWatchdog(emitter, sessionId, ledger);
        }
        final TurnDeadline deadline = ledger;
        emitter.onCompletion(() -> releaseSseChannel(emitter, deadline, sessionId));
        emitter.onTimeout(() -> releaseSseChannel(emitter, deadline, sessionId));
        emitter.onError(t -> releaseSseChannel(emitter, deadline, sessionId));
        syncPipelineSize();
        try {
            pipelineExecutor.execute(() -> {
                // 流水线线程**没有请求上下文**：检索的库门下推与文档可见性晚绑定
                // （HybridRetrievalService.buildAclFilter / loadVisibleDocIdsOfHits 读 RequestUser）
                // 会拿到 anonymous ⇒ 可见库集合为空/受限，配了共享范围（department/user）
                // 的文档被判为不可见并被过滤，表现就是"共享给我的资料，我在问答里检索不到"。
                // 这里按本轮 userId 从用户档案装载身份，让过滤按**真实用户**算（来源是用户表，
                // 与调用线程无关：网页问答的 Tomcat 线程、定时任务的池线程同一个来源）。
                boolean identity = loadIdentity(userId);
                // 个人配置覆盖（个人设置 → 对话偏好）：随身份一次性载入，本轮内 configService.get 对
                // personal 字段优先读个人值（相关建议条数等）。
                // 与身份同生命周期：池化线程复用，finally 必须清（否则下一轮会带着上一轮用户的偏好）
                configService.putUserOverrides(userConfigService.overrides(userId));
                try {
                runChat(sessionId, question, userImages, attachments, skills, mentions, useDeepThink,
                        agentId, modelOverride, userId, emitter, guestMode, regenerate, replaceMessageId,
                        historyRefs, reasoningLevel, requestedContextWindow, editVariantGroup, planMode);
                } finally {
                    // 轮次登记（TURN_STATES / TURN_HANDLES）刻意不在这里摘：runChat 在 subscribe 后就
                    // 返回了，生成与工具循环此后跑在响应式线程上，这里摘会让「停止本轮」找不到正在出字
                    // 的那一轮。摘除统一挂在 AnswerStreamState#claimTerminal()（本轮真收尾那一刻）。
                    TURN_IN_FLIGHT.remove(sessionId);
                    if (identity) com.wenqu.ai.util.RequestUser.clear();
                    // 智能体检索参数的作用域覆盖随本轮结束清除（ThreadLocal，池化线程复用必须清，
                    // 否则下一轮请求会继承上一轮智能体的检索策略）
                    configService.clearOverride();
                    configService.clearUserOverrides();
                }
            });
        } catch (RejectedExecutionException e) {
            // 任务未入队即拒绝：轮级互斥标记须同步放掉，否则该会话从此被永久挡在门外
            TURN_HANDLES.remove(sessionId);
            TURN_IN_FLIGHT.remove(sessionId);
            // L7 fail-loud：繁忙拒绝时告知当前队列长度（用户可感知拥堵程度）
            int queued = pipelineExecutor == null ? 0 : pipelineExecutor.getQueue().size();
            log.warn("[FAIL-LOUD] 问答流水线繁忙，拒绝请求（排队 {}）: session={}", queued, sessionId);
            sendSseEvent(emitter, "error", "系统繁忙（当前排队 " + queued + " 个请求），请稍后重试", sessionId);
            completeEmitter(emitter);
        }
    }

    /**
     * 服务端停止本轮：按会话找到正在跑的轮次，掐断生成流、把已生成内容按「已停止」落库、解开正在等人
     * 裁决的挂起项，让流水线线程立刻退出并释放会话轮级互斥（下一问马上能发，不必等审批/提问超时）。
     * <p>不依赖 SSE 通道是否还在：前端 abort 只断开连接，通道断开在「正等人作答」的语义下是
     * <b>继续后台跑完</b>（见 {@link AnswerStreamState#keepRunningWithoutChannel}），那不是用户按下停止
     * 想要的意思；换设备打开同一会话时也没有通道可断。故单独一条按 sessionId 的服务端路径。
     * <p>正在执行中的那一个工具调用无法半途掐死（沙盒命令已在容器里跑），它在下一步闸口被拦住。
     *
     * @return true=确实停掉了一轮；false=该会话没有在跑的轮，或这不是本人的轮（越权 fail-loud 拒绝）
     */
    public boolean stopTurn(String sessionId, String userId) {
        AnswerStreamState st = sessionId == null ? null : TURN_STATES.get(sessionId);
        if (st == null) return false;
        // 归属：与 resolveApproval/resolveAsk 同口径——只认发起本轮的那个 uid，别人停不动
        if (userId == null || !userId.equals(st.userId)) {
            log.warn("[FAIL-LOUD] 停止本轮被拒（非本人轮次）: session={} uid={}", sessionId, userId);
            return false;
        }
        if (st.settled()) {
            // 已经收过尾的轮次（登记残留一拍）：按「没有在跑的轮」答复，并顺手摘掉这条陈旧登记，
            // 不给一次并不存在的停止记账
            TURN_STATES.remove(sessionId, st);
            return false;
        }
        st.userStopped = true;
        // 账本先记停止本身：disposeSafe 会把通道与心跳一并收掉，之后再想还原「是谁、在什么进度上
        // 叫停的这一轮」就没有依据了
        sessionEventService.append(sessionId, st.userId, st.turnId, "stop",
                "用户停止本轮：正文=" + st.fullResponse.length() + "字 工具步=" + st.toolStepCount.get()
                        + (st.detached ? " 通道=已断开(后台续跑)" : " 通道=在线"));
        st.disposeSafe();
        // 关闭通道：disposeSafe 只掐上游、不 complete emitter——从另一台设备或直接用接口叫停时，
        // 正开着这个会话的页面会一直挂在「正在生成」里，直到前端 120s 空闲看门狗才自己收束。
        completeEmitter(st.emitter);
        // 解开人在回路的阻塞等待（放最后：正文已按停止态落库，线程再往下走时幂等闸已占住，不会二次落库）
        releaseHumanWaits(sessionId);
        log.info("[SSE] 用户停止本轮: session={} 正文={}字 工具步={} 通道={}",
                sessionId, st.fullResponse.length(), st.toolStepCount.get(),
                st.detached ? "已断开(后台)" : "在线");
        return true;
    }

    /**
     * 放弃该会话所有挂在人身上的等待：审批按拒绝、提问按中止（走既有 ExecutionException 分支，
     * 回给模型「用户中止了本轮回答」）、计划按未批准终止本轮。这些 future 不解开，流水线线程会一直
     * 挂在 {@code future.get(timeout)} 上，会话互斥要等到超时才释放——「停了还不能马上问下一句」。
     */
    private void releaseHumanWaits(String sessionId) {
        PENDING_APPROVALS.values().stream()
                .filter(p -> sessionId.equals(p.sessionId()))
                .forEach(p -> p.future().complete(false));
        PENDING_ASKS.values().stream()
                .filter(p -> sessionId.equals(p.sessionId()))
                .forEach(p -> p.future().completeExceptionally(
                        new java.util.concurrent.CancellationException("用户已停止本轮")));
        PENDING_PLANS.values().stream()
                .filter(p -> sessionId.equals(p.sessionId()))
                .forEach(p -> p.future().complete(PlanDecision.reject()));
    }

    /**
     * 问答流水线主体（独立线程执行）：图片/附件处理 → 改写 → 检索/深度思考 → 上下文构建 → LLM 流式输出
     */
    private void runChat(String sessionId, String question, List<String> userImages,
                         List<ChatRequest.Attachment> attachments, List<String> skills,
                         List<ChatRequest.Mention> mentions, boolean deepThink,
                         String agentId, String modelOverride, String userId, SseEmitter emitter,
                         boolean guestMode, boolean regenerate, String replaceMessageId,
                         List<ChatRequest.HistoryRef> historyRefs, String reasoningLevel,
                         Integer requestedContextWindow, String editVariantGroup, boolean planMode) {
        long startTime = System.currentTimeMillis();
        // 个人偏好一次取齐：聊天模型（resolveModel 用）
        final com.wenqu.ai.model.User prefUser = loadPrefUser(userId);
        // 本轮生效模型（请求覆盖 > 会话已存覆盖 > 个人默认，全局兜底已移除），回填进流式状态供 buildAnswerStream 使用。
        // 请求未带覆盖时回落会话已存值：聊天页切换模型已由 /chat 落库，老标签页/重新生成等不发 model 的轮次
        // 沿用用户此前切的选择（刷新/换端不丢）。覆盖的落库在 ChatController 同步段——这里只读不写：
        // 游客分享（会话归访客、身份是发布者）/定时任务（任务级模型）/工作流等旁路调用不带「手动切换」语义，不能改写会话。
        final String effectiveOverride = modelOverride != null && !modelOverride.isBlank()
                ? modelOverride.trim() : sessionService.sessionModelOf(sessionId);
        // 全部未配置时 fail-loud：引导用户配置，而不是发空 model 到网关
        final String resolvedModel = resolveModel(effectiveOverride, prefUser);
        if (resolvedModel.isBlank()) {
            log.warn("[FAIL-LOUD] 未配置任何模型（会话覆盖/个人默认均未指定）: session={}", sessionId);
            sendSseEvent(emitter, "error",
                    "未指定模型：请在对话右上角选择模型，或在个人设置中配置默认模型", sessionId);
            completeEmitter(emitter);
            return;
        }
        // 本轮回答的全部降级/兜底事件（fail-loud：随 done 下发，前端渲染警示条；code 去重，同类只报一次）。
        // 声明在智能体解析之前：会话锁定的智能体若已不可用，需要就地登记提示而不是静默改用全局配置
        List<Map<String, String>> degradations = new ArrayList<>();
        Set<String> degradedCodes = new HashSet<>();
        // §4 会话内 @ 智能体：消息里 @ 了其他主智能体 = 本轮临时委派它作答——人设/知识库/工具集整轮按它执行，
        // 会话绑定不变（不落 session，仅本轮覆盖）。先于绑定解析：委派生效时请求里的 "auto" 不再触发
        // 派遣路由（显式指定优先于路由，不为注定被覆盖的一轮花一次 LLM 判路调用）
        final Agent delegatedAgent = resolveDelegatedAgent(mentions, degradations, degradedCodes);
        String bindAgentId = agentId;
        if (delegatedAgent != null && "auto".equals(agentId)) {
            bindAgentId = null;   // 首问绑定按默认智能体收敛；已锁定会话本就忽略请求 agentId，无影响
        }
        // 智能体（4.1）：会话级绑定——已锁定的会话沿用锁定值，未锁定的按请求解析并锁定（首问路由一次）。
        // 派遣在 resolveModel 之后（用当轮生效模型判路），失败回落默认智能体
        final Agent boundAgent = resolveSessionAgent(sessionId, bindAgentId, question, resolvedModel, emitter,
                degradations, degradedCodes);
        // 委派生效判定：@ 的智能体与会话绑定（或默认解析结果）同一人时无需覆盖，也不发委派事件
        final boolean delegatedRound = delegatedAgent != null
                && (boundAgent == null || !delegatedAgent.getId().equals(boundAgent.getId()));
        final Agent agent = delegatedRound ? delegatedAgent : boundAgent;
        if (delegatedRound) {
            log.info("[AGENT] 会话 {} 本轮由 @ 提及的智能体 {}（{}）作答（会话绑定不变）",
                    sessionId, delegatedAgent.getId(), delegatedAgent.getName());
            emitAgentDelegated(emitter, delegatedAgent, sessionId);
        }
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
            if (planMode) {
                addDegradation(degradations, degradedCodes, "planWorkflowUnsupported",
                        "该智能体由工作流驱动，计划模式不生效，本轮按工作流直接执行");
            }
            runWorkflowChat(sessionId, question, userId, emitter, startTime, degradations, degradedCodes,
                    agent, guestMode, regenerate, replaceMessageId, editVariantGroup,
                    delegatedRound ? delegatedAgent : null);
            return;
        }
        // 目标知识库集合（检索按库的向量模型分组逐库查询；null=不限，全库分组检索）。
        // 内置「问渠」按本轮使用者解析为「自己的默认库 ＋ 官方内置手册库」（智能体全局一只、默认库每人一个，无法静态绑定）。
        // 按需委派开启「收窄检索范围」（agent.dispatchNarrowScope）时，本集合会在路由判定后被
        // 重赋值为「主智能体库 ∪ 被选中子智能体库」（见检索前的路由段），因此不能声明为 final
        java.util.Collection<String> scopeKbIds = scopeKbIdsOf(agent, userId);
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
        // 本轮思考强度档位归一：用户本轮显式选的档位优先；不在模型支持档位内（含前端被绕过、
        // 手改请求下发越级档位）→ 回落模型登记的默认档位，不静默改写成用户选的值。
        final String thinkLevel = resolveReasoningLevel(resolvedModel, reasoningLevel);
        // 「不使用知识库」的纯角色智能体：整条跳过检索链路（改写/深度思考检索/命中填充/子代理编排都不跑，
        // 省掉整轮检索+重排成本）；用户手动 @ 的文档仍会前置进上下文（手动指定优先于智能体配置）。
        // 内置「问渠」豁免：其范围固定为使用者默认库（配置页无入口、后端拒绝改动），存量行若带 1 也不生效
        final boolean knowledgeOff = agent != null && !Integer.valueOf(1).equals(agent.getIsBuiltin())
                && Integer.valueOf(1).equals(agent.getKnowledgeDisabled());
        // 检索范围：智能体关联的知识库（主路径）→ 库内文档；knowledgeScope 降级为「库内再细选文档」
        // @ 文档时收窄到这些文档（显式指定优先）——用户就是在问这份文档，别的文档的块不该进上下文
        final Set<String> scopeDocIds = mentionScope != null && !mentionScope.docIds().isEmpty()
                ? new LinkedHashSet<>(mentionScope.docIds())
                : resolveScopeDocIds(agent, userId);
        // Agentic RAG「检索-反思」循环模式判定：开启后本轮强制暴露知识检索工具，并在生成提示词
        // 注入「证据充分性自评」循环规则——模型判断首轮检索证据不足时自主换关键词/换角度再检索
        // （次数仍由 agent.maxToolSteps 兜底），证据足够才作答。
        // 前置不满足 fail-loud（登记降级并本轮关闭，不注入指挥模型调不存在工具的提示词）：
        // 工具总开关未开 / 模型不支持 Function Calling。
        final boolean reflectiveRetrieval;
        {
            boolean reflectOn = !knowledgeOff && reflectiveRetrievalOn(useDeepThink, thinkLevel);
            if (reflectOn && !configService.getBoolean("tool.enabled")) {
                addDegradation(degradations, degradedCodes, "reflectiveDegraded",
                        "检索-反思循环需要工具总开关（tool.enabled）开启，本轮未生效");
                reflectOn = false;
            }
            if (reflectOn && !modelRegistryService.toolCapableOf(resolvedModel)) {
                addDegradation(degradations, degradedCodes, "reflectiveDegraded",
                        "当前模型「" + modelDisplayName(resolvedModel)
                                + "」不支持工具调用（Function Calling），检索-反思循环本轮未生效");
                reflectOn = false;
            }
            reflectiveRetrieval = reflectOn;
            if (reflectOn) {
                log.info("[AGENTIC-RAG] 检索-反思循环已启用: session={} model={} deepThink={} level={}",
                        sessionId, resolvedModel, useDeepThink, thinkLevel);
            }
        }
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
                if (useDeepThink) {
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
            // 计划意图识别（按用户意图自动开启计划模式）：尽早启动、与图片/附件/检索并行跑，闸门前取结果。
            // 计划开关已开时无需检测（本轮必走计划）；重新生成不检测——用户要的是重答，不是重出一份计划
            final java.util.concurrent.CompletableFuture<Boolean> planIntentFuture = startPlanIntentCheck(
                    emitter, guestMode, planMode, regenerate, sessionId, question, resolvedModel,
                    (userImages != null && !userImages.isEmpty()) || (attachments != null && !attachments.isEmpty()));
            // 0. 用户上传图片，两条链路（图片理解只看当前聊天模型的能力位——个人默认视觉模型已下线）：
            //    ① 聊天模型自带图片理解（visionCapable，「聊天+视觉」一体登记形态）：原图随消息直发
            //       （buildAnswerStream 转 image_url 部件）——省一次视觉调用、不丢图细节；
            //    ② 模型不支持读图：图片仅落盘随消息展示，fail-loud 登记可见降级（不静默忽略），
            //       并在问题里注入诚实说明——让模型知道自己看不见图，不猜测内容、必要时建议换模型。
            //       不把原图直发文本模型：BYOM 网关可能 400/静默丢图，模型更可能对着看不见的图编内容。
            //       能力判定错了的兜底在模型登记处：把「图片理解」改为支持即走直读，无需任何个人配置。
            boolean chatSeesImages = modelRegistryService.visionCapableOf(resolvedModel);
            List<UserImageService.UserImage> userImgs;
            String imgNote;
            if (chatSeesImages) {
                userImgs = userImageService.processDirect(userImages);
                imgNote = "";
                log.info("[IMAGE] 聊天模型自带图片理解，图片直发模型: session={} model={} count={}",
                        sessionId, resolvedModel, userImgs.size());
            } else {
                userImgs = userImageService.processDisplay(userImages);
                imgNote = userImgs.isEmpty() ? "" : "\n\n用户上传了 " + userImgs.size()
                        + " 张图片，但当前模型不支持图片理解，图片内容对你不可见。"
                        + "请勿猜测图片内容；若图片对回答关键，可建议用户更换支持图片理解的模型后重试。";
                // 降级只在真有图片被排除时登记：0 张图片≠降级——模型不支持视觉但本轮没传图，
                // 没有任何内容「仅作展示」，不该对用户弹「本轮 0 张图片仅作展示」的提示（也不刷 WARN 日志）
                if (!userImgs.isEmpty()) {
                    addDegradation(degradations, degradedCodes, "chatModelNoVision",
                            "当前模型「" + resolvedModel.substring(resolvedModel.indexOf('/') + 1)
                                    + "」不支持图片理解，本轮 " + userImgs.size()
                                    + " 张图片仅作展示、未参与回答；如需识别图片请更换支持图片理解的模型");
                    log.warn("[FAIL-LOUD] 聊天模型不支持图片理解，图片仅展示: session={} model={} count={}",
                            sessionId, resolvedModel, userImgs.size());
                }
            }

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
            //     ID 随 done 下发（userMessageId）：编辑重发后前端要拿真实消息 ID 挂切换器/再编辑。
            String userMessageId = null;
            if (!regenerate) {
                List<String> earlyImgUrls = userImgs.stream().map(UserImageService.UserImage::url).toList();
                // @ / # 引用随用户消息常驻落库（此前仅轮级注入、刷新后丢失）；其余中间列本轮不写，保持原语义
                userMessageId = sessionService.appendMessage(sessionId, "user", question,
                        earlyImgUrls.isEmpty() ? null : earlyImgUrls, null,
                        null, null, null, null,
                        attachmentsMeta.isEmpty() ? null : JSON.toJSONString(attachmentsMeta),
                        null, null, null, null, null, null, null,
                        mentions != null && !mentions.isEmpty() ? JSON.toJSONString(mentions) : null,
                        historyRefs != null && !historyRefs.isEmpty() ? JSON.toJSONString(historyRefs) : null);
                // 编辑重发：新用户消息挂上被替换旧分支的版本组键（appendMessage 不为此扩参，落库返回后补挂）
                if (userMessageId != null && editVariantGroup != null && !editVariantGroup.isBlank()) {
                    sessionService.setMessageVariant(userMessageId, editVariantGroup);
                }
            }

            // # 历史引用文本（两条生成分支共用）：用户从本会话历史显式挑选的问答，前置进本轮上下文
            String historyRefText = buildHistoryRefText(historyRefs);

            // 0.4 智能体声明「不使用知识库」：跳过改写/深度思考/检索/子代理编排整条链路，
            //     直接走生成（仅 @ 引用的文档块会前置进上下文）。图片提问也不走视觉检索，
            //     但图片描述仍会随问题发给模型（多模态理解与知识库无关）。
            if (knowledgeOff) {
                log.info("[AGENT] 智能体 {} 不使用知识库，跳过检索链路", agent.getId());
                // 该分支不检索，但用户手动 @ 的文档仍按「手动指定优先」取块前置
                // （方法注释里承诺已久的语义，此前只有注释没有实现）
                String mentionText = buildMentionText(loadMentionChunks(mentionScope, degradations, degradedCodes));
                runNoKnowledgeChat(sessionId, question, userId, userImgs, imgNote, attachmentText, userSkillText,
                        mentionText, historyRefText, preHeartbeat, emitter, startTime, thinkingHolder,
                        degradations, degradedCodes, agent, stageMs, resolvedModel, guestMode, replaceMessageId,
                        userMessageId, delegatedRound ? delegatedAgent : null, planMode, skills);
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
                            java.util.Collection<String> subKbs = scopeKbIdsOf(sub, userId);
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
            if (useDeepThink) {
                DeepThinkResult dr = runDeepThinking(sessionId, question, imgNote, attachmentText, emitter, resolvedModel, thinkLevel);
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
                        // adaptiveTopK=true：多路内部按路数收敛（≤2 路开补采，≥3 路不开，见 searchMulti 注释）
                        hits = hybridRetrievalService.searchMulti(queries, retrievalDiag, scopeKbIds, true);
                        rankQuery = dr.refinedQuery();
                    } else {
                        retrievalQuery = thinkTerms.isEmpty()
                                ? dr.refinedQuery()
                                : dr.refinedQuery() + " " + String.join(" ", thinkTerms);
                        // adaptiveTopK=true：生产问答，让「权限有效召回」参与 topK 配额（见 HybridRetrievalService.search 四参重载）
                        hits = hybridRetrievalService.search(retrievalQuery, retrievalDiag, scopeKbIds, true);
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
                    hits = hybridRetrievalService.search(retrievalQuery, retrievalDiag, scopeKbIds, true);
                    hits = rerankIfNeeded(hits, retrievalQuery, degradations, degradedCodes);
                } else {
                    hits = hybridRetrievalService.search(retrievalQuery, retrievalDiag, scopeKbIds, true);
                    // 重排只在此处做：上方 thinkTerms 分支已重排过，落到这里再排一次是同批文档同 query
                    // 的重复计费/重复延迟（second rank 结果不变）
                    hits = rerankIfNeeded(hits, retrievalQuery, degradations, degradedCodes);
                }
                hits = applyScope(hits, scopeDocIds); // 智能体知识库范围约束
            }
            // M4/M13/L1 fail-loud：检索单路失败/降级透传（keywordFallback 仅调试展示，不扰用户）
            if (retrievalDiag.isVectorFailed()) {
                addDegradation(degradations, degradedCodes, "vectorFailed", "向量检索失败，本次仅关键词召回");
            }
            // 多库检索中某库向量检索失败：其余库向量命中仍参与召回，不能谎称"仅关键词召回"；
            // 提示用户相关库未参与本次召回（具体供应商/原因见服务端日志与额度通知）
            if (retrievalDiag.isVectorPartialFailed()) {
                addDegradation(degradations, degradedCodes, "vectorPartialFailed", "部分知识库向量检索失败，相关库未参与本次召回");
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
            // 本轮主链路重排是否实际执行（有任一命中拿到重排分）：已执行时，未重排候选不得再走融合门入场（见填充段分域门）
            boolean rerankActive = hits.stream().anyMatch(h -> h.rerankScore() != null);
            // 兜住最后一处静默：重排"看起来可用"（探测通过、调用也发了）却没给出任何重排分——
            // 此时门同样退化到融合门，与不可用后果一致，必须同样告知（不能只在 debugUnavailableReason 有值时才提示）
            if (!rerankActive && !hits.isEmpty()) {
                addDegradation(degradations, degradedCodes, "rerankUnavailable",
                        "重排本轮未返回相关性分数：上下文与引用按融合分筛选，相关性可能偏低");
            }

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
            //    + 对话历史（预算驱动全量带入：近期原样 + 更早轮次滚动摘要，见 assembleHistory）
            int historyCompressedTurns = 0;   // 本轮压缩进摘要的轮数（done 下发，前端提示「已压缩 N 轮」）
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
                            + "并只能使用工具实际返回的编号；搜索未覆盖的内容如实说明未找到依据，不得凭常识补写。");
            // 使用者身份段：手册/资料里的管理端章节对所有提问者都可检索到，但回答要按角色收敛——普通成员
            // （无管理端权限）只给使用侧结论并说明「由管理员配置」，管理员给完整操作路径。
            // 身份已在流水线线程装载（loadIdentity），游客分享/MCP 等链路按会话归属人判定；未登录按普通成员口径
            boolean adminLike = roleService.isAdminCode(com.wenqu.ai.util.RequestUser.role());
            system.append(adminLike
                    ? "\n\n【使用者身份】本轮提问者是管理员：涉及系统设置、模型供应商、成员与权限、部署运维、API Key 等管理端内容时，"
                            + "可给出完整操作路径与配置说明。"
                    : "\n\n【使用者身份】本轮提问者是普通成员（没有管理端权限）：涉及系统设置、模型供应商、成员与权限、部署运维、API Key 等管理端内容时，"
                            + "说明该能力属于管理端、由管理员配置，不要展开管理端操作步骤；只回答他在使用侧能做什么（如提问、上传资料、选用智能体等）。");
            // 检索-反思循环（Agentic RAG）：首轮检索结果之外授权模型自主多轮检索——工具已强制暴露
            // （enabledToolCallbacks），这里只补「何时该再检索、何时该停」的判定规则
            if (reflectiveRetrieval) {
                system.append("\n\n【检索-反思循环】以上参考资料是首轮检索结果，回答前请先自评证据充分性："
                        + "存在关键缺口（结论/数值/步骤缺失、资料之间矛盾），或问题涉及资料未覆盖的"
                        + "具体功能/字段/步骤/报错时，调用 searchKnowledge 工具补充检索——换关键词"
                        + "（同义说法、具体字段名、报错原文）或换角度（拆出的子问题）再试；"
                        + "证据足够后立即作答、不再检索；多轮检索仍无收获时如实说明知识库缺少该部分依据，"
                        + "不得凭常识编造。每次检索结果会附「证据充分性自评」提示，按其指引决定再检索还是作答。");
            }
            system
                    .append(relatedPromptLine());
            // 容量计量分段标记：按 StringBuilder 位置切出各段，供容量面板分类展示（系统提示词/记忆/技能/其他）
            int partRoleEnd = system.length();
            // 用户长期记忆（跨会话个性化）：注入本人记忆 + 累加使用度；游客分享会话不注入
            // （发布者的个人记忆不外泄给匿名访客）；空记忆/未开启零影响
            if (!guestMode) {
                String memoryText = userMemoryService.injectText(userId, question);
                if (memoryText != null) system.append("\n\n").append(memoryText);
            }
            int partMemoryEnd = system.length();
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
            int partSkillEnd = system.length();
            // SubAgent 并行检索要点：作为补充资料段（不占 [N] 引用编号空间，仅辅助生成）
            if (subOutcome != null && !subOutcome.digestText().isBlank()) {
                system.append("\n\n【并行检索要点】\n").append(subOutcome.digestText());
                stageMs.put("subagent", subOutcome.elapsedMs());
            }
            int partOtherEnd = system.length();

            // 3. 预算先行：历史装配与知识块填充共用同一预算（历史按压缩阈值占一部分，其余留给检索资料）
            CtxBudget ctxBudget = resolveContextBudget(resolvedModel, requestedContextWindow, degradations, degradedCodes);
            int budget = ctxBudget.budget();

            // 4. 对话历史：预算驱动全量带入——近期原样 + 更早轮次滚动摘要（替代旧的「按轮数 + 单条截断」）
            HistoryBundle historyBundle = assembleHistory(sessionId, resolvedModel, budget, degradations, degradedCodes);
            historyCompressedTurns = historyBundle.compressedTurns();
            String historySummaryBlock = summaryBlock(historyBundle.summaryText());
            if (!historySummaryBlock.isEmpty() || !historyBundle.recentText().isEmpty()) {
                system.append("\n\n对话历史：\n");
                if (!historySummaryBlock.isEmpty()) system.append(historySummaryBlock);
                system.append(historyBundle.recentText());
            }

            // 图片说明拼入问题（仅"模型不支持读图"时有值：诚实说明图片不可见，防模型瞎猜；
            // 直读时无文本，图片本体以 image_url 部件随消息下发）
            StringBuilder userQuestion = new StringBuilder(question);
            if (!imgNote.isBlank()) {
                userQuestion.append(imgNote);
            }
            // 用户上传附件文本拼入问题（主 LLM 结合附件内容回答）
            if (!attachmentText.isBlank()) {
                userQuestion.append("\n\n用户上传了附件，内容如下（请结合附件内容回答问题，引用时注明来自哪个附件）：\n")
                        .append(attachmentText);
            }
            // # 历史引用前置：用户显式挑选的本会话历史问答（内容与「对话历史」同源，但由用户点名——
            // 不受「最近 N 轮」窗口限制，长会话里早于窗口的问答也能被重新带上）
            if (!historyRefText.isBlank()) {
                userQuestion.append("\n\n【本轮引用的历史对话】用户从本会话历史中指定了以下问答作为本轮参考，"
                        + "请结合它们回答当前问题：\n").append(historyRefText);
            }
            // 思考链注入：把深度思考的推理过程（截断）作为参考注入，让"想过的"作用于"答"；
            // 明确说明必须以参考资料为准，思考只是辅助拆解
            if (!thinkingInject.isBlank()) {
                userQuestion.append("\n\n【你的思考过程】以下是本问题此前生成的深度分析过程，供参考其中的拆解与判断，"
                        + "但最终回答必须以下方参考资料为准：\n").append(thinkingInject);
            }

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
            // 子代理命中优先级仅次于检索命中（针对性视角检索，价值高于部分普通召回）；同样受智能体知识库范围约束。
            // 分支检索不重排（只带融合分）：不补重排直接并入，会以「无重排分」身份走融合门（minFusionScore，
            // 默认 0.5）入场，绕过主链路 0.6 重排门（修复前无关块即由此混进上下文与引用来源）——
            // 与主链路同 query 小批量强制重排（≤6 块低于 minHits 窗口，rank() 会整批跳过，须走 rankForced），
            // 让子代理块与主链路块同分域、同门槛；重排不可用时原样并入（与主链路「重排不可用」同语义，走融合门）
            if (subOutcome != null && !subOutcome.hits().isEmpty()) {
                List<HybridRetrievalService.Hit> subHits = subOutcome.hits();
                if (rerankService.debugUnavailableReason() == null) {
                    subHits = rerankService.rankForced(new ArrayList<>(subHits), retrievalQuery);
                } else {
                    addDegradation(degradations, degradedCodes, "rerankUnavailable",
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
            st.reasoningLevel = thinkLevel; // 本轮思考强度档位（请求级优先，null=只有开关无强度）
            st.guestMode = guestMode; // 游客分享会话：工具只保留知识检索与内置项（见 enabledToolCallbacks）
            st.replaceMessageId = replaceMessageId; // 重新生成：落库前软删被替换的旧回答
            st.userMessageId = userMessageId; // 本轮用户消息 ID（编辑重发后前端挂切换器/再编辑用）
            st.delegatedAgentId = delegatedRound ? delegatedAgent.getId() : null; // §4 轮级委派归属（随 done 下发）
            st.delegatedAgentName = delegatedRound ? delegatedAgent.getName() : null;
            st.toolApprovalMode = agent == null ? null : agent.getToolApprovalMode(); // 有副作用工具审批模式
            st.maxToolSteps = resolveMaxToolSteps(agent); // 单轮工具步数上限（智能体覆盖 > 全局）
            st.reflectiveRetrieval = reflectiveRetrieval; // 检索-反思循环：工具强制暴露与结果自评约束按此门控
            // Token 消耗可视化回填：上下文实际用量/预算/填充块数（输出侧在 done 时用回答正文估算）
            st.contextTokens = usedTokens + fixedTokens;
            st.budgetTokens = budget;
            st.contextHits = docNo - 1;
            // 生效最大输出（模型管理中该模型声明的值）：max_tokens 下发与终态触顶判定共用
            st.effectiveMaxOutput = ctxBudget.maxOutput();
            st.windowTokens = ctxBudget.window();
            st.windowSource = ctxBudget.windowSource();
            st.historyCompressedTurns = historyCompressedTurns;
            // 容量分类用量（估算，done 时按网关真实 prompt_tokens 等比校准）：
            // 按 system 组装时的分段标记切出各段，避免为计量再跑一遍拼接
            Map<String, Integer> parts = new LinkedHashMap<>();
            String sysAll = system.toString();
            parts.put("system", TokenCounter.estimate(sysAll.substring(0, Math.min(partRoleEnd, sysAll.length()))));
            parts.put("memory", TokenCounter.estimate(sysAll.substring(Math.min(partRoleEnd, sysAll.length()),
                    Math.min(partMemoryEnd, sysAll.length()))));
            parts.put("skill", TokenCounter.estimate(sysAll.substring(Math.min(partMemoryEnd, sysAll.length()),
                    Math.min(partSkillEnd, sysAll.length()))));
            parts.put("other", TokenCounter.estimate(sysAll.substring(Math.min(partSkillEnd, sysAll.length()),
                    Math.min(partOtherEnd, sysAll.length()))));
            parts.put("summary", historyBundle.summaryTokens());
            parts.put("messages", historyBundle.recentTokens());
            parts.put("chunks", usedTokens);
            parts.put("input", TokenCounter.estimate(userQuestion.toString()));
            st.ctxParts = parts;
            // 编排视图：回填分支最终状态（subOutcome.branches 来自编排完成后的 ctx.branches，含各分支命中数）
            if (subOutcome != null && !subOutcome.branches().isEmpty()) {
                st.subagentBranches = subOutcome.branches();
            }
            st.subagentRoute = subagentRouteInfo;
            // 合并主流程已记录的分段（改写 / 检索），后续生成与自检由流回调继续写入 st.stageMs
            st.stageMs.putAll(stageMs);
            // 计划模式（人在回路）：先产出执行计划等用户批准，批准后的计划注入本轮 system 再进生成。
            // 挂起点在 st 创建之后：断线后台续跑/人工等待不计费/刷新恢复全部继承 askUser 的既有语义。
            // 用户没开计划开关时，按消息意图自动开启：识别命中=本轮按计划模式走（卡片标「自动开启」）
            boolean planRound = planMode;
            if (!planRound && awaitPlanIntent(planIntentFuture)) {
                planRound = true;
                st.planAuto = true;
                log.info("[PLAN] 意图识别命中，本轮自动开启计划模式: session={}", sessionId);
            }
            if (planRound) {
                String planQuestion = question + (imgNote == null || imgNote.isBlank() ? "" : imgNote)
                        + (attachmentText.isBlank() ? ""
                        : "\n\n（用户上传了 " + preparedAtts.size() + " 个附件，其内容将在执行阶段提供）");
                String gate = planApprovalGate(st, agent, rolePart, planQuestion, skills,
                        buildRefsDigest(sources), system);
                if (gate == null) {
                    // 拒绝/超时：整轮就此终止（plan_cancelled 让前端卡片定格「未批准」）；
                    // 「继续对话」取代：本版作废（plan_superseded，随后用户那条意见消息会接上）。
                    // 两者都要补标准 done 收尾——SSE 读循环「流关闭且无 done」会按『连接被提前关闭』误报中断错；
                    // 收尾轮没有流式回答可挂计划卡：先补落一条收尾助手消息（含 plan 快照，刷新后卡不丢）
                    if (st.planSuperseded) {
                        persistPlanSuperseded(st);
                        sendSseEvent(emitter, "plan_superseded", "{}", sessionId);
                    } else {
                        persistPlanRejected(st);
                        sendSseEvent(emitter, "plan_cancelled", "{}", sessionId);
                    }
                    Map<String, Object> cancelled = new LinkedHashMap<>();
                    cancelled.put("sources", List.of());
                    cancelled.put("related", List.of());
                    cancelled.put("degradations", degradations);
                    if (st.planSuperseded) cancelled.put("planSuperseded", true);
                    else cancelled.put("planCancelled", true);
                    sendSseEvent(emitter, "done", JSON.toJSONString(cancelled), sessionId);
                    completeEmitter(emitter);
                    return;
                }
            }
            st.disposableRef.set(buildAnswerStream(system.toString(), user, st, agent));

            // 前端断开/终态时停止生成。整轮截断（原「SSE 超时」）已由 startTurnWatchdog 承担：
            // 容器级超时不可续期且会把人工答题时间算进去，emitter 不再设容器超时
            emitter.onCompletion(() -> { if (!st.keepRunningWithoutChannel()) st.disposeSafe(); });
            emitter.onError(t -> { if (!st.keepRunningWithoutChannel()) st.disposeSafe(); });

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
            // 检索-反思循环（reflectiveRetrieval）轮次强制暴露知识检索工具：该模式本身就是
            // "模型自主多轮检索"的显式开启，不再要求 tool.knowledgeRetrieval.enabled 子开关
            if (st.reflectiveRetrieval || toolOn(agent, "tool.knowledgeRetrieval.enabled", agent == null ? null : agent.getToolKnowledge())) {
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
        // 同上：检索-反思循环轮次强制暴露（游客分支与常规分支同一语义）
        if (st.reflectiveRetrieval || toolOn(agent, "tool.knowledgeRetrieval.enabled", agent == null ? null : agent.getToolKnowledge())) {
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
        // 工具执行确认=off（禁用）：不给模型沙盒/MCP 这类有副作用工具。UI 与手册均按此语义承诺，
        // 此前仅 ask 分支有实现、off 静默等同 auto——按承诺补齐。
        // 联网搜索不受 off 管辖：启停由自身开关决定，纳入审批（webSearch.requireApproval）后仅 ask 会拦截。
        boolean approvalOff = agent != null && "off".equalsIgnoreCase(agent.getToolApprovalMode());
        // 沙盒工具（隔离执行环境）：tool.sandbox.enabled 控制；暂无智能体级三态覆盖（toolSandbox 列未加，
        // 见 SandboxTools 类注释）——沙盒本身按会话隔离，工具一旦启用对所有会话可用
        if (!approvalOff && toolOn(agent, "tool.sandbox.enabled", null)) {
            for (org.springframework.ai.tool.ToolCallback cb :
                    org.springframework.ai.support.ToolCallbacks.from(sandboxTools)) {
                callbacks.add(cb);
                sensitiveTools.add(cb.getToolDefinition().name());
            }
        }
        // MCP 外部工具（工具生态层）：连的是**当前用户**登记的 server（连接池按 uid 分池），失败自动跳过
        if (!approvalOff && (agent == null || agent.getToolMcp() == null || agent.getToolMcp() == 1)) {
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
                java.util.Set<String> mcpNames = new java.util.HashSet<>();
                for (org.springframework.ai.tool.ToolCallback cb : mcpClientService.toolCallbacks(userId, onlyMcp)) {
                    callbacks.add(cb);
                    String toolName = cb.getToolDefinition().name();
                    sensitiveTools.add(toolName);
                    mcpNames.add(toolName);
                }
                st.mcpToolNames = java.util.Set.copyOf(mcpNames);
            } catch (Exception e) {
                log.warn("[MCP] 加载外部工具失败（跳过，不影响问答）: {}", e.getMessage());
            }
        }
        // 记录本轮"有副作用"的工具名单（沙盒/MCP）：智能体 toolApprovalMode=ask 时执行前需用户确认
        st.sensitiveToolNames = sensitiveTools;
        // DSML 剥离白名单：本轮真实下发的工具名（小写）。部分模型在 Function Calling 之外还会把
        // 工具调用以 <searchKnowledge><parameter …></parameter></searchKnowledge> 的 DSML 文本再输出
        // 一遍（工具卡片已由 native tool_calls 生成，文本那份是重复产物），不剥离会原样漏给用户。
        // 排除与 HTML/XML 标准标签同名的工具（MCP 工具名由外部 server 自定，可能叫 link/form/table）：
        // 那类名字按标签剥会误伤模型正常写的 HTML/XML 正文，宁可漏剥离也不能吃掉用户内容。
        st.dsmlToolNamesLc = callbacks.stream()
                .map(cb -> cb.getToolDefinition().name())
                .filter(java.util.Objects::nonNull)
                .map(n -> n.toLowerCase())
                .filter(n -> !HTML_TAG_NAMES.contains(n))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
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
        // 身份快照在此处（入口线程）捕获：instrumentTools 由问答流水线在 chat-pipeline 线程调用，
        // 此时 RequestUser 可见。工具实际执行在 boundedElastic 线程，必须靠这个快照把身份带过去
        // （见 callWithRetry）。放在执行点捕获是无效的——那里已经在弹性线程上，只会拿到 anonymous。
        final com.wenqu.ai.util.RequestUser.Snapshot identity =
                com.wenqu.ai.util.RequestUser.snapshot();
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
                    // 用户已叫停本轮：一步工具都不再执行（副作用工具尤其不能放行——沙盒命令会真的跑），
                    // 也让正在等的模型轮次尽快拿到终态而不是继续往下调。
                    if (st.userStopped) {
                        recordToolStatus(st, name, toolInput, "error", "本轮已被用户停止",
                                System.currentTimeMillis() - begin, 0);
                        return "{\"error\":\"本轮已被用户停止，不再执行任何工具。\"}";
                    }
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
                                com.wenqu.ai.model.ToolApproval rec = new com.wenqu.ai.model.ToolApproval();
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
                            // 审批待决站内通知（旁路）：SSE 只能触达正开着这个会话页的人，人不在页面时
                            // 铃铛是唯一可感知面。超时/裁决后通知不撤（留在列表里作审计痕迹，点了即已读）。
                            notificationService.create(st.userId, com.wenqu.ai.model.Notification.TYPE_TOOL_APPROVAL,
                                    "工具执行待确认：" + name,
                                    "智能体正在请求执行有副作用工具「" + name + "」，请在会话中确认或拒绝（超时未确认按拒绝处理）。",
                                    "session", st.sessionId, "tool:" + approvalId, approvalId);
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
                                beginHumanWait(st.emitter);   // 用户确认时间不计入整轮机器预算（见 TurnDeadline）
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
                                } finally {
                                    endHumanWait(st.emitter);
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
                        // 基础设施故障回调（CTX_INFRA_ERROR_SINK）对全部沙盒工具注入：沙盒不可用时工具
                        // 不抛异常（抛出去会掐断整轮流、连带已生成正文与全部工具记录一起落空），而是把
                        // 失败文本回给模型 + 经此回调上报，让本条记录落成 error 而不是绿色对勾的 done。
                        org.springframework.ai.chat.model.ToolContext effectiveCtx = toolContext;
                        // 沙盒工具判定（决定要不要注入故障上报回调）：与 sandboxTool 无关的工具
                        // 保持原行为，effectiveCtx 原样透传。
                        boolean sandboxTool = SandboxTools.TOOL_NAMES.contains(name);
                        // 基础设施故障信箱：本次调用独占的 AtomicReference（局部变量，同名工具并发
                        // 调用也各持各的，不会串号）。工具执行期被填充，call 返回后据此定status。
                        java.util.concurrent.atomic.AtomicReference<String> infraError = null;
                        if (toolContext != null && (SandboxTools.STREAMING_TOOL_NAME.equals(name) || sandboxTool)) {
                            java.util.Map<String, Object> enhanced = new java.util.HashMap<>(toolContext.getContext());
                            if (SandboxTools.STREAMING_TOOL_NAME.equals(name)) {
                                enhanced.put(SandboxTools.CTX_OUTPUT_SINK,
                                        (java.util.function.Consumer<String>) delta -> recordToolOutput(st, name, delta));
                            }
                            if (sandboxTool) {
                                infraError = new java.util.concurrent.atomic.AtomicReference<>();
                                enhanced.put(SandboxTools.CTX_INFRA_ERROR_SINK,
                                        (java.util.function.Consumer<String>) infraError::set);
                                effectiveCtx = new org.springframework.ai.chat.model.ToolContext(enhanced);
                            }
                        }
                        // 精确检索工具：注入来源注册器——命中块注册进当前流 sources 续编引用编号，
                        // 工具文本改【引用N】提示模型按编号标注，前端角标悬浮/引用弹窗因此可溯源；
                        // 同步注入本轮检索范围——工具与主链路同库界，不得越过智能体知识库绑定检索
                        boolean kbTool = "searchKnowledge".equals(name);
                        if (kbTool) {
                            // 注册器带 degrade 通道：工具链路（重排不可用等）可写回本轮 degradations，
                            // 与主链路 addDegradation 同一份列表、同一展示位
                            KnowledgeRetrievalTool.setSourceRegistrar(
                                    new KnowledgeRetrievalTool.SourceRegistrar() {
                                        @Override
                                        public KnowledgeRetrievalTool.SourceRegistrar.Registration register(
                                                HybridRetrievalService.Hit h, String snippet) {
                                            return st.registerToolSource(h, snippet);
                                        }

                                        @Override
                                        public void degrade(String code, String msg) {
                                            addDegradation(st.degradations, st.degradedCodes, code, msg);
                                        }
                                    });
                            KnowledgeRetrievalTool.setKbScope(st.toolScopeKbIds, st.toolScopeDocIds);
                            // 检索-反思循环轮次：工具结果尾部附「证据充分性自评」约束（按轮注入，finally 清理）
                            KnowledgeRetrievalTool.setReflective(st.reflectiveRetrieval);
                        }
                        // 联网搜索工具：注入本轮落点（配额扣减 + 引用注册 + 降级提示）。
                        // 工具对象是全局单例，落点必须按轮注入并即时清理，否则跨会话串号。
                        boolean webTool = WebSearchTools.TOOL_NAME.equals(name);
                        if (webTool) {
                            WebSearchTools.setSink(st);
                        }
                        // MCP 外部工具：结果注册引用来源（tool.mcpCiteEnabled）——模型依据 MCP 内容
                        // 作答时正文才有可点溯源的 [N]，否则是悬空角标（引用面板无对应条目）。
                        boolean mcpTool = st.mcpToolNames.contains(name);
                        try {
                            int[] attempts = {0};
                            String result = callWithRetry(cb, toolInput, effectiveCtx, name, attempts, identity);
                            if (mcpTool) {
                                result = st.registerMcpCitations(name, result);
                            }
                            // 沙盒基础设施故障（provisioner 不可达等）：工具把失败文本回给了模型、
                            // 没抛异常，但这条记录必须落成 error——否则「沙盒压根没通」在聊天区
                            // 显示为绿色对勾的「成功」，与「命令跑了退出码非 0」混为一谈。
                            String infra = infraError == null ? null : infraError.get();
                            if (infra != null && !infra.isBlank()) {
                                recordToolStatus(st, name, toolInput, "error", infra,
                                        System.currentTimeMillis() - begin, attempts[0]);
                            } else {
                                recordToolStatus(st, name, toolInput, "done", result,
                                        System.currentTimeMillis() - begin, attempts[0]);
                            }
                            return result;
                        } catch (Exception e) {
                            recordToolStatus(st, name, toolInput, "error", e.getMessage(),
                                    System.currentTimeMillis() - begin, 0);
                            throw e;
                        } finally {
                            if (kbTool) {
                                KnowledgeRetrievalTool.clearSourceRegistrar();
                                KnowledgeRetrievalTool.clearKbScope();
                                KnowledgeRetrievalTool.clearReflective();
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

    /** SSE 心跳调度池（2 线程，守护）：仅供 15s keepalive 注释行下发。与看门狗分池——心跳是
     *  高频周期任务（百级并发 = 每 15s 约两百次 send），看门狗是整轮截断的安全网：同队时一个
     *  慢客户端卡住的 send 会连看门狗一起停摆，全平台的截断机制失效 */
    private static final java.util.concurrent.ScheduledExecutorService TOOL_HEARTBEAT_POOL =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sse-keepalive");
                t.setDaemon(true);
                return t;
            });

    /** 整轮存活看门狗（独立单线程，与心跳分池见上）：检查本身无 IO。到点的截断收尾虽有阻塞
     *  send，但失联客户端快速抛错、健康客户端 send 极短，独占线程不与两百次/15s 的心跳互相放大 */
    private static final java.util.concurrent.ScheduledExecutorService TURN_WATCHDOG_POOL =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "turn-watchdog");
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

    // ==================== 整轮存活看门狗 ====================

    /**
     * 本轮耗时台账：整轮预算只计<b>机器</b>时间，人在回路的等待从中扣除。
     * <p>背景：askUser 提问卡单卡允许等 chat.askTimeoutMs（默认 10 分钟）、工具审批 120s，而容器级 SSE
     * 超时是<b>从请求开始
     * 一路走到头的墙钟</b>且不可续期（Spring 在 async 启动后禁止改 timeout，实测发心跳也不复位）。
     * 于是「用户慢慢答完第二张卡」必然在答题途中被掐断：提问线程被中断、askId 落库改成 TIMEOUT，
     * 用户点提交收到「提问不存在、已回答或已超时」。现 emitter 不设容器超时（见 ChatController），
     * 截断判定改由本台账 + {@link #startTurnWatchdog} 负责。
     */
    private static final class TurnDeadline {
        final long startMs = System.currentTimeMillis();
        final long budgetMs;
        /** 已结束的人在回路等待累计（提问卡 + 工具审批阻塞时长），不计入预算 */
        final java.util.concurrent.atomic.AtomicLong humanWaitMs = new java.util.concurrent.atomic.AtomicLong();
        /** 进行中的等待：计数 + 起点。必须**边等边扣**——等完再补记的话，看门狗在用户答题那几分钟里
         *  看到的还是整段墙钟，照样把正在作答的轮次掐了（2026-10-07 复测实测到） */
        final java.util.concurrent.atomic.AtomicInteger openWaits = new java.util.concurrent.atomic.AtomicInteger();
        volatile long waitBegin;
        /** 只截断一次：到点后并发 tick 不重复发 warn/complete */
        final java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        volatile java.util.concurrent.ScheduledFuture<?> watchdog;
        /** 本轮流式状态（AnswerStreamState 构造时挂入）：到点停流与半程正文截断落库用；工作流轮为 null */
        volatile AnswerStreamState state;

        TurnDeadline(long budgetMs) {
            this.budgetMs = budgetMs;
        }

        void beginWait() {
            if (openWaits.getAndIncrement() == 0) waitBegin = System.currentTimeMillis();
        }

        void endWait() {
            if (openWaits.decrementAndGet() <= 0) {
                long b = waitBegin;
                if (b > 0) humanWaitMs.addAndGet(System.currentTimeMillis() - b);
                waitBegin = 0;
            }
        }

        long machineMs() {
            long w = humanWaitMs.get();
            long b = waitBegin;
            if (b > 0) w += System.currentTimeMillis() - b;
            return System.currentTimeMillis() - startMs - w;
        }

        /** 通道断开时台账要不要留着续跑：本轮正等用户作答，或已转后台续跑且尚未落库收尾 */
        boolean holdsForDetachedTurn() {
            AnswerStreamState s = state;
            if (s == null) return false;
            return s.askWaits.get() > 0 || s.planWaits.get() > 0 || (s.detached && !s.settled());
        }
    }

    /** emitter → 本轮台账：与 ACTIVE_SSE 同生命周期（onCompletion/onError 或 forgetSseChannel 清） */
    private static final java.util.concurrent.ConcurrentHashMap<SseEmitter, TurnDeadline> TURN_DEADLINES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 整轮机器耗时预算（chat.sseTimeoutMs，默认 5 分钟） */
    private long turnMachineBudgetMs() {
        long t = configService.getLong("chat.sseTimeoutMs");
        return t > 0 ? t : 300000L;
    }

    /**
     * 整轮存活看门狗：每 {@link #TOOL_HEARTBEAT_INTERVAL_MS} 检查一次「墙钟 − 人工等待」是否越过预算。
     * 到点先发 warn（此刻 emitter 还活着，前端气泡真能看到「回答超时已截断」），再 dispose 停流
     * （半程正文按截断态落库）并 complete。失联兜底另有 15s 心跳、前端 120s 空闲看门狗与各阶段自身
     * 超时，本看门狗只管「整轮做机器工作太久」。
     */
    private java.util.concurrent.ScheduledFuture<?> startTurnWatchdog(SseEmitter emitter, String sessionId,
                                                                      TurnDeadline d) {
        java.util.concurrent.ScheduledFuture<?> f = TURN_WATCHDOG_POOL.scheduleWithFixedDelay(() -> {
            long machineMs = d.machineMs();
            if (machineMs <= d.budgetMs) return;
            if (!d.fired.compareAndSet(false, true)) return;
            log.warn("[FAIL-LOUD] 本轮问答超时截断: session={} 机器耗时 {}ms / 预算 {}ms（人工等待 {}ms 不计入）",
                    sessionId, machineMs, d.budgetMs, d.humanWaitMs.get());
            try {
                sendSseEvent(emitter, "warn", "回答超时已截断，请重试或缩短问题", sessionId);
                AnswerStreamState st = d.state;
                if (st != null) st.disposeSafe();
                completeEmitter(emitter);
            } finally {
                stopTurnDeadline(emitter);
            }
        }, TOOL_HEARTBEAT_INTERVAL_MS, TOOL_HEARTBEAT_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        d.watchdog = f;
        return f;
    }

    /** 结束本轮台账：停看门狗并放掉 emitter 引用（终态/断连/收集型通道共用） */
    private static void stopTurnDeadline(SseEmitter emitter) {
        TurnDeadline d = TURN_DEADLINES.remove(emitter);
        if (d != null && d.watchdog != null) {
            d.watchdog.cancel(false);
        }
    }

    /**
     * 通道生命周期回调（onCompletion/onTimeout/onError）：清断开登记并回收整轮台账。
     * <p>例外：台账所绑的那轮正在等用户作答、或已转入「断线后台续跑」且尚未落库收尾时，
     * <b>必须留着台账与看门狗</b>——那是后台轮唯一的机器耗时上界，随通道一起回收等于放它无界跑下去。
     */
    private void releaseSseChannel(SseEmitter emitter, TurnDeadline deadline, String sessionId) {
        ACTIVE_SSE.remove(emitter);
        if (deadline != null && deadline.holdsForDetachedTurn()) {
            log.info("[ASK] 客户端断开但本轮仍在等人作答/后台续跑，整轮台账与看门狗保留: session={}", sessionId);
            return;
        }
        stopTurnDeadline(emitter);
    }

    /**
     * 人在回路等待开始/结束（提问卡与工具审批的阻塞窗口）：这段墙钟不计入整轮机器预算。
     * 未登记台账的通道（收集型）直接忽略。
     */
    private static void beginHumanWait(SseEmitter emitter) {
        TurnDeadline d = TURN_DEADLINES.get(emitter);
        if (d != null) d.beginWait();
    }

    private static void endHumanWait(SseEmitter emitter) {
        TurnDeadline d = TURN_DEADLINES.get(emitter);
        if (d != null) d.endWait();
    }

    /**
     * 工具调用瞬时故障自动重试：失败后 500ms 重试一次（共至多 2 次），attempts 记录实际尝试次数。
     * 场景是沙盒容器冷启动、网络抖动这类瞬时故障；确定性错误（路径不存在等）多付一次 500ms 代价可接受
     * ——业务层的"错误输出"（如命令退出码非 0）不是异常，不会触发重试。模型看到的只有终态与 attempts。
     * 注意不声明 throws：ToolCallback.call 只抛运行时异常，包装 checked 会破坏调用方 precise-rethrow。
     */
    private String callWithRetry(org.springframework.ai.tool.ToolCallback cb, String toolInput,
                                 org.springframework.ai.chat.model.ToolContext toolContext,
                                 String name, int[] attempts,
                                 com.wenqu.ai.util.RequestUser.Snapshot identity) {
        // 工具执行发生在 Reactor boundedElastic 线程（Spring AI ToolCallAdvisor 对阻塞工具 subscribeOn），
        // 而 RequestUser 用裸 ThreadLocal 只在入口线程可见 → 工具内读身份会回落 anonymous。
        // 对检索类工具这不是安全降级而是功能损坏：loadVisibleKbIds() 判 anonymous 为「什么都读不到」
        // → 库门表达式恒假 → 融合召回 0（线上实测：主链路命中 31 块，工具侧恒空并耗尽 15 步工具预算）。
        // identity 由 instrumentTools 在入口线程捕获后传入——不能在执行点临时 snapshot()，
        // 那里已经在弹性线程上、捕获到的只会是 anonymous（实测修复无效）。
        // 统一恢复：一次覆盖全部工具（检索/沙盒/MCP/工作流），避免逐工具打补丁。
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
            // restore() 返回的句柄用于 finally 复原：弹性线程会被复用，不清理会污染后续任务的身份
            try (AutoCloseable ignored = com.wenqu.ai.util.RequestUser.restore(identity)) {
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

    /**
     * 哪些工具的 SSE 实时副本必须带完整入参/结果（不受 {@link #TOOL_IO_SSE_BRIEF} 截断）。
     *
     * <p>截断对这些工具不是「摘要」而是「破坏」：前端要 JSON.parse 出来才能渲染，
     * 砍一刀后解析必然失败，于是卡片只剩半截。askUser 尤其致命——多问题的入参
     * （questions 数组）动辄 600+ 字符，200 字截断后既丢问题、又把答案的 JSON
     * 数组当纯文本原样打印，且**刷新后走8KB 全文又正常**，看起来像随机故障。
     */
    private static boolean needsFullToolIO(String name) {
        return "askUser".equals(name);
    }

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
        synchronized (st.timeline) {
            // 连续追加合并进末段：过程独白是按 token 增量下发的（routeProcessText 每条都调本方法），
            // 无条件新建段会把一段独白切成上百个 1~2 字的过程段，done 落库后前端每个碎片各渲染一个
            // 「执行过程」折叠头。末段仍是过程段且正好接在锚点上（其间没插工具/产物段）时延伸它。
            // 工具/产物打断时末段已是 tool/artifact 段，此处自然另起新段，交错顺序如实保留。
            if (!st.timeline.isEmpty()) {
                Map<String, Object> last = st.timeline.get(st.timeline.size() - 1);
                if (last != null && "process".equals(last.get("kind")) && toInt(last.get("to")) == from) {
                    last.put("to", pos);
                    st.processAnchor.set(pos);
                    return;
                }
            }
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("kind", "process");
            seg.put("from", from);
            seg.put("to", pos);
            st.timeline.add(seg);
        }
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

    /**
     * 中断/失败兜底落库：客户端停止/断开、看门狗截断、流错误把生成流掐断时，把已产出的半程内容
     * 按截断态落库。此前只有完整走完 doOnComplete 才落库，中断轮在历史里只剩问题——刷新/重进会话后
     * 「已生成的部分」全部蒸发（连用户盯着跑了五分钟的工具卡片与计划快照一起）。与正常完成路径
     * 共用 answerPersistGate 幂等闸（CAS 先到先得，谁先落库谁赢）。
     * 只落「已下发给前端」的正文（fullResponse 与 token 事件同源；尾部扣留的标签前缀缓冲不参与）；
     * 引用自检/图片过滤/记忆提取/QA 日志均不再执行（那是完整轮的收尾语义），正文尾部追加标记，
     * 时间线仍按原始半程长度夹取（标记落在区间之外，前端 restore 后由尾段兜底渲染）。
     * <p>「正文为空但已有工具记录」同样落库：工具执行/计划确认这类轮的价值全在工具卡片与计划快照上，
     * 正文可能一个 token 都没有——按「正文非空」判会让整轮记录蒸发。真正空轮（正文、工具记录
     * 都为空，如客户端秒断）不落库：落一条空消息只会污染历史。
     *
     * @param tailMarker 正文尾部追加的可见标记（截断态/失败态各自不同，均落在时间线区间之外）
     */
    private void persistPartialAnswer(AnswerStreamState st, String tailMarker) {
        try {
            String answer = st.fullResponse.toString();
            // 空轮不落库：正文、工具记录、过程独白、已完成的思考全空时，落了也是一条空消息，只污染历史。
            // 思考要算进来——深度思考收尾后、正文首字之前被掐的轮（断线/看门狗/用户停止都在这窗口），
            // 那一轮并非「什么都没发生」：刷新后至少留得住它想过什么。
            String thinkingSoFar = st.thinkingHolder[0];
            if (answer.isEmpty() && st.toolCalls.isEmpty() && st.processResponse.length() == 0
                    && (thinkingSoFar == null || thinkingSoFar.isBlank())) {
                return;
            }
            List<Map<String, Object>> sources = st.sources;
            List<String> finalImgs = new ArrayList<>(st.imgIndex.values());
            String sourcesJson = sources.isEmpty() ? null : JSON.toJSONString(sources);
            // retrieved 状态行 refs 对齐 + 编排视图随持久化（与 doOnComplete 同款小步：来源可能被工具续编）
            String finalRetrievedJson = st.retrievedJson;
            try {
                Map<String, Object> rj = JSON.parseObject(st.retrievedJson);
                if (rj != null) {
                    Integer oldRefs = rj.get("refs") instanceof Number n ? n.intValue() : null;
                    if (oldRefs == null || oldRefs != sources.size()) {
                        rj.put("refs", sources.size());
                    }
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
            List<Map<String, Object>> sessionArtifacts = artifactService.takeArtifacts(st.sessionId);
            List<Map<String, Object>> toolCallSnapshot = new ArrayList<>(st.toolCalls);
            String toolCallsJson = toolCallSnapshot.isEmpty() ? null : JSON.toJSONString(toolCallSnapshot);
            // 时间线按原始半程正文夹取；截断标记追加在快照之后（区间之外，前端尾段兜底渲染）
            List<Map<String, Object>> timelineSnapshot =
                    buildTimelineSnapshot(st, answer, toolCallSnapshot.size(), sessionArtifacts.size());
            String timelineJson = timelineSnapshot.isEmpty() ? null : JSON.toJSONString(timelineSnapshot);
            String processText = st.processResponse.toString();
            Map<String, Object> tokens = buildUsageSnapshot(st, answer);
            // 重新生成被中断：同样软删被替换的旧回答并登记分支版本组——用户已看到新版在气泡里替代旧版，
            // 历史保持同观感（半程截断版同样挂组，多版本口径一致）
            String replaceGroup = null;
            if (st.replaceMessageId != null && !st.replaceMessageId.isBlank()) {
                replaceGroup = sessionService.beginAnswerReplace(st.replaceMessageId);
            }
            // 走全量重载（带 plan）：中断轮同样把计划快照随截断版落库，刷新后计划卡与半程回答一起回显
            String partialMessageId = sessionService.appendMessage(st.sessionId, "assistant", answer + tailMarker,
                    finalImgs, sourcesJson, st.thinkingHolder[0], finalRetrievedJson,
                    sessionArtifacts.isEmpty() ? null : JSON.toJSONString(sessionArtifacts),
                    toolCallsJson, null, JSON.toJSONString(tokens), timelineJson,
                    processText.isEmpty() ? null : processText,
                    st.agentId, st.agentName, st.model,
                    null, null, null,   // related / mentions / historyRefs：中断兜底沿用原有口径不带
                    planRecordJson(st));
            if (replaceGroup != null && partialMessageId != null) {
                sessionService.setMessageVariant(partialMessageId, replaceGroup);
            }
            // 中断即终态：产物 emitter/监听注册表一并清理（此前断开路径无人清理，靠下一轮覆盖兜底）
            artifactService.unregisterEmitter(st.sessionId);
            log.info("[SSE] 中断/失败兜底：半程回答已落库 (session={}, chars={}, tools={})",
                    st.sessionId, answer.length(), toolCallSnapshot.size());
        } catch (Exception e) {
            // 兜底失败只记日志：管道已断无处下发错误，回退为现状「中断即丢失」
            log.warn("[SSE] 中断兜底落库失败 (session={}): {}", st.sessionId, e.getMessage());
        }
    }

    /**
     * Token 用量快照（done 下发与落库共用）：工具调用循环逐轮求和（native 原始口径）=全部请求轮次总量，
     * 与供应商账单同口径；最终轮（prompt 最大：工具结果逐轮入上下文）的输出用于触顶判定、其 prompt 用于
     * ctxParts 校准（分类拆的是最终上下文而非各轮总和）。无逐轮数据时回落旧单值字段，
     * 连真实 usage 都没有再回落 TokenCounter 估算；估算才需 10% 余量，实报应如实。
     * 中断兜底路径复用同一口径：逐轮实测（若有）如实反映已耗用量，无则按半程正文估算。
     */
    private Map<String, Object> buildUsageSnapshot(AnswerStreamState st, String answer) {
        long sumPrompt = st.roundUsage.promptTotal(), sumOutput = st.roundUsage.completionTotal();
        long sumCached = st.roundUsage.cachedTotal();
        long finalPrompt = st.roundUsage.finalRoundPrompt();
        long finalRoundOutput = st.roundUsage.finalRoundCompletion();
        boolean hasRounds = !st.roundUsage.isEmpty();
        boolean realOutput = hasRounds ? sumOutput > 0 : st.realOutputTokens > 0;
        int outputTokens = realOutput ? (int) (hasRounds ? sumOutput : st.realOutputTokens)
                : TokenCounter.estimate(answer);
        // 输出触顶 fail-loud：网关真实 completion_tokens 达到输出上限 ⇒ 大概率被
        // 截断（finish_reason=length），回答/related 推荐块不完整。原实现静默落库，
        // 用户只会看到残缺回答而无任何提示（"没有回答出内容"的帮凶之一）。
        // 上限与 max_tokens 下发同源（模型管理中该模型声明的最大输出）；未声明输出上限时无从判定，跳过。
        // 多轮时只看生成最终回答那一轮的输出（各轮工具调用参数的输出不参与触顶判定）
        int maxOutput = st.effectiveMaxOutput;
        int answerRoundOutput = hasRounds ? (int) finalRoundOutput : outputTokens;
        if (maxOutput > 0 && realOutput && answerRoundOutput >= maxOutput) {
            addDegradation(st.degradations, st.degradedCodes, "outputTruncated",
                    "回答达到输出长度上限（" + maxOutput + " tokens），可能不完整；可在模型管理中调大该模型的最大输出");
        }
        int promptTokens = hasRounds ? (int) sumPrompt
                : (st.realPromptTokens > 0 ? st.realPromptTokens : st.contextTokens);
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("context", st.contextTokens);
        tokens.put("budget", st.budgetTokens);
        tokens.put("hits", st.contextHits);
        tokens.put("output", outputTokens);
        tokens.put("prompt", promptTokens);
        tokens.put("outputIsReal", realOutput);
        tokens.put("total", promptTokens + outputTokens);
        // 容量面板数据：生效窗口/来源 + 分类用量（按网关真实 prompt 等比校准）+ 缓存命中
        tokens.put("window", st.windowTokens);
        tokens.put("windowSource", st.windowSource);
        tokens.put("cached", hasRounds ? (int) sumCached : st.cachedPromptTokens);
        tokens.put("parts", calibrateParts(st.ctxParts,
                hasRounds ? (int) finalPrompt : promptTokens, hasRounds || st.realPromptTokens > 0));
        if (st.historyCompressedTurns > 0) {
            tokens.put("historyCompressed", st.historyCompressedTurns);
        }
        return tokens;
    }

    /**
     * 回答被截断的判定与用户可见标注文案（null=未截断）。两条独立证据：
     * ① 网关明说 finish_reason=length（输出达上限）；
     * ② 暗截断——最终轮真实输出 token 折算的字符量，远高于实际进入回答/过程/思考/工具入参的字符数，
     *    说明有内容在剥离环节消失了（此前这类轮次连一条提示都没有，用户只看到断在半句的回答）。
     * ② 只在网关回了真实 usage 时参与判定，且门槛留足余量：中英混排、表格与 markdown 都会拉低
     * 字符/token 比，宁可漏报不可误报。
     */
    private String truncationSuffix(AnswerStreamState st, String answer) {
        if (st.finishLength) {
            addDegradation(st.degradations, st.degradedCodes, "outputTruncated",
                    "回答达到模型输出长度上限被截断；可在模型管理中调大该模型的最大输出");
            log.warn("[FAIL-LOUD] 网关 finish_reason=length，回答被截断: session={}, 正文 {} 字",
                    st.sessionId, answer.length());
            return LENGTH_TRUNCATED_SUFFIX;
        }
        long finalRoundOutput = st.roundUsage.finalRoundCompletion();
        boolean realUsage = st.roundUsage.completionTotal() > 0 || st.realOutputTokens > 0;
        // 工具入参同样是模型生成的输出（写文件类工具动辄几百 token），不计进"已交代"必然误判
        int toolArgChars = 0;
        for (Map<String, Object> rec : st.toolCalls) {
            Object args = rec.get("args");
            if (args != null) toolArgChars += String.valueOf(args).length();
        }
        int accounted = answer.length() + st.processResponse.length() + st.reasoningChars + toolArgChars;
        if (realUsage && finalRoundOutput >= OUTPUT_LOSS_MIN_TOKENS
                && accounted < finalRoundOutput * OUTPUT_LOSS_CHAR_PER_TOKEN) {
            addDegradation(st.degradations, st.degradedCodes, "outputLost",
                    "模型本轮生成 " + finalRoundOutput + " tokens，仅 " + accounted + " 字进入回答，内容疑似丢失");
            log.warn("[FAIL-LOUD] 生成量与正文字数不符: session={}, 最终轮输出={} tokens, 已交代={} 字"
                            + "（正文 {}/过程 {}/思考 {}/工具入参 {}）, finishReason={}",
                    st.sessionId, finalRoundOutput, accounted, answer.length(),
                    st.processResponse.length(), st.reasoningChars, toolArgChars, st.lastFinishReason);
            return OUTPUT_LOST_SUFFIX;
        }
        return null;
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
        int sseLimit = needsFullToolIO(name) ? TOOL_IO_MAX : TOOL_IO_SSE_BRIEF;
        String argsBrief = truncBrief(input, sseLimit);
        rec.put("args", argsBrief);
        if (resultOrError != null) {
            rec.put(status.equals("error") ? "error" : "result", truncBrief(resultOrError, sseLimit));
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
        // 账本留痕：工具步的「何时开始/结束、耗时、成败」是一轮执行轨迹的主干。载荷用 SSE 那份短摘要
        // ——终态全文（8KB）已随消息落库，账本不重复存大字段；start 也记，回放才看得出每步的起点。
        sessionEventService.append(st.sessionId, st.userId, st.turnId, "tool", JSON.toJSONString(rec));
    }

    /**
     * 本轮思考强度档位归一：用户显式选的（reqLevel，须落在模型支持档位内）优先，
     * 否则用模型登记的默认档位。返回值 null = 本轮不指定强度（只有思考开关，无强度语义）。
     */
    private String resolveReasoningLevel(String model, String reqLevel) {
        String lv = reqLevel == null ? null : reqLevel.trim().toLowerCase();
        if (lv != null && !lv.isBlank() && modelRegistryService.reasoningLevelsOf(model).contains(lv)) {
            return lv;
        }
        if (lv != null && !lv.isBlank()) {
            // 越级/非法档位：回落模型默认，并留可查日志（不静默按用户选的值下发）
            log.warn("[REASONING] 思考强度档位 {} 不在模型 {} 的支持档位内，回落其默认档位", lv, model);
        }
        return modelRegistryService.defaultReasoningLevelOf(model);
    }

    /**
     * 检索-反思循环模式判定（Agentic RAG · 自主多轮检索；retrieval.reflectiveRetrieval：
     * off/high/always，默认 off——非默认全量：循环轮次多出一次到多次工具往返与自评开销，
     * 延迟/token 成本由显式开启者承担）：
     * off = 一次性管线（现状）；high = 深度思考高档档的增强（本轮深度思考开启且思考强度 ≥ high）；
     * always = 独立开关（知识管线轮次全部启用，与是否深度思考无关）。
     * 键值缺失/非法一律按 off 处理。
     */
    private boolean reflectiveRetrievalOn(boolean deepThink, String thinkLevel) {
        String mode = configService.get("retrieval.reflectiveRetrieval");
        if (mode == null || mode.isBlank()) return false;
        switch (mode.trim().toLowerCase()) {
            case "always":
                return true;
            case "high":
                if (!deepThink || thinkLevel == null) return false;
                List<String> levels = ModelRegistryService.REASONING_LEVEL_LIST;
                return levels.indexOf(thinkLevel) >= levels.indexOf("high");
            default:
                return false;
        }
    }

    /** 模型展示名（降级事件文案用）：有登记取展示名，否则回退模型 id，引用无效回退原串 */
    private String modelDisplayName(String ref) {
        ModelInfo mi = modelRegistryService.modelInfoOf(ref);
        if (mi == null) return ref == null ? "" : ref;
        return (mi.getDisplayName() == null || mi.getDisplayName().isBlank()) ? mi.getModelId() : mi.getDisplayName();
    }

    /** data URL（data:image/png;base64,…）→ Spring AI Media（委派链转 data URI image_url）；解析失败返回 null 跳过该图 */
    private static org.springframework.ai.content.Media mediaOf(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma <= 0) return null;
            String meta = dataUrl.substring(5, comma);
            String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            if (!mime.startsWith("image/")) return null;
            byte[] bytes = java.util.Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            if (bytes.length == 0) return null;
            return org.springframework.ai.content.Media.builder()
                    .mimeType(org.springframework.util.MimeTypeUtils.parseMimeType(mime))
                    .data(bytes)
                    .build();
        } catch (Exception e) {
            log.warn("[IMAGE] 图片 dataUrl 解析失败，跳过该图: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建并订阅主 LLM 流式回答（H2：未输出任何 token 时中断自动重试，次数 chat.streamRetryCount 可配）。
     * 可变状态与 complete 回调依赖收敛在 AnswerStreamState；重试时重建全新流并丢弃旧缓冲。
     */
    private Disposable buildAnswerStream(String system, String user, AnswerStreamState st, Agent agent) {
        SseEmitter emitter = st.emitter;
        // 智能体归属快照：随助手消息落库（完成路径直接用闭包 agent，中断兜底路径取这里的快照）
        st.agentId = agent == null ? null : agent.getId();
        st.agentName = agent == null ? null : agent.getName();
        // 登记产物 emitter：供 PresentArtifactTool 在流式执行中实时下发 artifact 事件（结束/出错时清理）
        artifactService.registerEmitter(st.sessionId, emitter);
        // 产物生成时刻监听：把产物卡片按生成顺序插进本轮时间线（刷新后仍在原位，不再堆到气泡底部）
        artifactService.registerArtifactListener(st.sessionId, (sid, idx) -> pushTimelineArtifact(st, idx));
        // 整轮流级心跳：从这里到终态（complete/error/dispose）全程保活，覆盖工具执行
        // 与「工具结束→最终回答首 token」两段静默盲区（重试重建流时幂等复用）
        startRunHeartbeat(st);
        org.springframework.ai.tool.ToolCallback[] toolCallbacks =
                instrumentTools(st.enabledCallbacks != null
                        ? st.enabledCallbacks : enabledToolCallbacks(agent, st.userId, st), st);
        // 模型不支持 Function Calling 时不下发 tools：部分网关收到 tools 字段直接 400（fail-loud，不静默）。
        // 登记为用户可见降级事件——工具是智能体配置带来的，用不了要让人知道原因。
        if (toolCallbacks.length > 0 && !modelRegistryService.toolCapableOf(st.model)) {
            log.warn("[FAIL-LOUD] 当前模型不支持 Function Calling，本轮不下发工具: model={}", st.model);
            addDegradation(st.degradations, st.degradedCodes, "modelToolUnsupported",
                    "当前模型「" + modelDisplayName(st.model) + "」不支持工具调用（Function Calling），本轮未启用工具");
            toolCallbacks = new org.springframework.ai.tool.ToolCallback[0];
        }
        // 过程叙述规范仅工具模式注入：无工具的纯对话没有"过程"可叙，加了反而诱导模型输出标签
        String sysFinal = toolCallbacks.length == 0 ? system : system + PROCESS_NARRATION_GUIDE;
        // 工具 schema 计量（走请求的 tools 字段，不进 system）：按「名称+描述+入参 schema」估算，
        // 内置工具与 MCP 工具分桶——MCP server 动辄几十个工具，是容量面板里最容易被忽略的一块
        int toolSchemaTokens = 0;
        int mcpSchemaTokens = 0;
        for (org.springframework.ai.tool.ToolCallback cb : toolCallbacks) {
            org.springframework.ai.tool.definition.ToolDefinition def = cb.getToolDefinition();
            if (def == null) continue;
            int t = TokenCounter.estimate(def.name() + " " + def.description() + " " + def.inputSchema());
            toolSchemaTokens += t;
            if (st.mcpToolNames.contains(def.name())) mcpSchemaTokens += t;
        }
        st.ctxParts.put("toolSchema", toolSchemaTokens - mcpSchemaTokens);
        st.ctxParts.put("mcpSchema", mcpSchemaTokens);
        if (!PROCESS_NARRATION_GUIDE.isEmpty() && toolCallbacks.length > 0) {
            st.ctxParts.merge("other", TokenCounter.estimate(PROCESS_NARRATION_GUIDE), Integer::sum);
        }
        // 用量预下发（usage 事件）：prompt 侧用量此刻已齐（ctxParts 已含工具 schema），生成一开始就推给前端
        // 点亮容量圆环/明细卡——不再等 done 才出现；done 仍以网关实测值下发并覆盖为最终版（输出/缓存/校准只在那里有）。
        // 估算口径与容量面板分类同源：各段 TokenCounter 估算直和（未按实测校准，校准在 done 完成）。
        int promptEstimate = 0;
        for (Integer v : st.ctxParts.values()) {
            promptEstimate += v == null ? 0 : v;
        }
        Map<String, Object> usageEarly = new LinkedHashMap<>();
        usageEarly.put("context", st.contextTokens);
        usageEarly.put("budget", st.budgetTokens);
        usageEarly.put("hits", st.contextHits);
        usageEarly.put("prompt", promptEstimate);
        usageEarly.put("window", st.windowTokens);
        usageEarly.put("windowSource", st.windowSource);
        usageEarly.put("parts", st.ctxParts);
        sendSseEvent(emitter, "usage", JSON.toJSONString(usageEarly), st.sessionId);
        // 聊天直读图片：本轮图片走直读链路时（UserImage.dataUrl 在场即标记），原图以
        // image_url 内容部件随用户消息发给聊天模型——模型自带图片理解（省一次调用、不丢图细节）。
        // 仅展示链路（模型不支持读图）的图片 dataUrl 为空 → media 列表为空 → 走纯文本分支，行为不变。
        // Media→data URI 的转换由委派链原生完成（DynamicOpenAiChatModel 原样透传 instructions 给 OpenAiChatModel）
        java.util.List<org.springframework.ai.content.Media> media = st.userImgs == null ? java.util.List.of()
                : st.userImgs.stream()
                        .filter(i -> i.dataUrl() != null && !i.dataUrl().isBlank())
                        .map(i -> mediaOf(i.dataUrl()))
                        .filter(java.util.Objects::nonNull)
                        .toList();
        var spec = chatClient.prompt().system(sysFinal);
        spec = media.isEmpty()
                ? spec.user(user)
                : spec.user(u -> u.text(user).media(media.toArray(new org.springframework.ai.content.Media[0])));
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(st.model);
        // 最大输出：以模型管理中该模型声明的「最大输出」为准；未声明不下发 max_tokens（交由厂商默认，无全局兜底值）
        if (st.effectiveMaxOutput > 0) {
            optionsBuilder.maxTokens(st.effectiveMaxOutput);
        }
        // 思考强度档位：本轮请求级（用户在聊天页选的）优先，其次模型登记的默认档位；
        // 按厂商方言映射成各自字段下发（OpenAI/DeepSeek/GLM/豆包/Kimi/MiniMax reasoning_effort、
        // Claude thinking.budget_tokens、Qwen thinking_budget），不做单字段裸透传——那只对
        // OpenAI 系生效。恒思考模型（always）本轮一定开着思考，可开关模型按 st.deepThink；
        // 登记了档位但网关无语义 → fail-loud 提示。
        String thinkLevel = st.reasoningLevel != null
                ? st.reasoningLevel : modelRegistryService.defaultReasoningLevelOf(st.model);
        boolean thinkingOn = st.deepThink
                || ModelRegistryService.THINK_ALWAYS.equals(modelRegistryService.referenceThinking(st.model));
        if (thinkLevel != null && thinkingOn) {
            // reasoning_effort 必须走原生 setter：放进 extraBody 会被序列化两次
            // （ChatCompletionRequest 同时有原生 reasoningEffort 组件与 extraBody），
            // 网关按重复字段拒绝——DeepSeek 返回 422 "duplicate field reasoning_effort"（实测）。
            String effort = modelRegistryService.reasoningEffortValue(st.model, thinkLevel, true);
            if (effort != null) {
                optionsBuilder.reasoningEffort(effort);
            }
            Map<String, Object> thinkBody = modelRegistryService.reasoningExtraBody(st.model, thinkLevel, true);
            if (!thinkBody.isEmpty()) {
                optionsBuilder.extraBody(thinkBody);
            }
            if (effort != null || !thinkBody.isEmpty()) {
                log.info("[REASONING] 思考强度已下发: model={} level={} effort={} extra={}",
                        st.model, thinkLevel, effort, thinkBody.keySet());
            } else {
                log.warn("[FAIL-LOUD] 模型登记了思考强度档位但网关无强度方言，未下发: model={} level={}",
                        st.model, thinkLevel);
                addDegradation(st.degradations, st.degradedCodes, "reasoningLevelUnsupported",
                        "模型「" + modelDisplayName(st.model) + "」登记了默认思考强度（" + thinkLevel
                                + "），但该网关不支持强度调节（已按仅思考开关处理）");
            }
        }
        // 思考模式下不发 temperature：主流网关（DeepSeek/GLM/豆包/Claude/Qwen 官方文档均明确）
        // 在思考开启时忽略 temperature/top_p/presence_penalty/frequency_penalty——既不报错也不生效，
        // 属于"设了却静默失效"。此时不如下发，省一次无效参数并避免误解为"温度已按配置生效"。
        if (thinkingOn) {
            log.debug("[REASONING] 思考模式生效，本轮不下发 temperature（网关会静默忽略）: model={}", st.model);
        } else {
            optionsBuilder.temperature(configService.getDouble("chat.temperature"));
        }
        return spec
                // 模型配置界面：per-request 动态覆盖模型名与温度（保存即生效）；maxTokens 限制输出长度（防失控长文/成本）
                // st.model 为本轮解析好的模型（引用或遗留名，供应商路由由 DynamicOpenAiChatModel 按引用完成）
                .options(optionsBuilder.build())
                // 工具调用（Function Calling）：默认开启（tool.* 配置）；总开关+各子工具开关均开启时，
                // 模型可在回答中主动调用工具（精确检索知识库、交付文件产物），补充主链路未召回的上下文。
                // 传空数组等价未配置工具，不影响现有行为（零侵入）。
                // instrumentTools 包装：工具执行前后发 tool_status SSE 并记录过程（状态展示）。
                // 必须用 .toolCallbacks()：.tools() 只接受 @Tool 注解对象，传 ToolCallback 实例会抛
                // IllegalStateException（Spring AI 1.1.8 实测坑）。
                .toolCallbacks(toolCallbacks)
                // 工具上下文：把当前会话 ID 与用户 ID 注入，供产物交付、沙盒等工具定位会话与归属。
                // userId 必须随 toolContext 透传——工具回调跑在 Reactor boundedElastic 线程上
                // （Spring AI 1.1.8 ToolCallAdvisor 对阻塞工具执行 subscribeOn，不占网关连接线程），
                // 读 RequestUser.uid()（ThreadLocal）跨线程失效会回落成 anonymous，导致沙盒建到 shared/anonymous。
                // 另注入 askUser 执行器（闭包持有本轮会话态 st）：提问卡 SSE、阻塞等待、超时收尾都依赖它。
                .toolContext(toolContext(st))
                .stream()
                // 用 chatResponse 而非 content：流式中顺便捕获网关返回的真实 token usage（部分兼容网关
                // 在末块 metadata.usage 里给出 completion_tokens；拿不到则回落 TokenCounter 估算）。
                // 用 map 抽出 content 字符串，下游 doOnNext 逻辑（related 缓冲/剥离/发送）完全不变。
                .chatResponse()
                // 用量归属：这笔问答的推理开销记在谁头上。模型层拿不到业务身份，靠 Reactor Context
                // 向上游透传（ThreadLocal 在 netty 事件循环线程不可见），由 DynamicOpenAiChatModel
                // 在路由出口写台账。工具调用循环的每一轮同属本次问答，走同一份归属。
                .contextWrite(ctx -> com.wenqu.ai.util.UsageAttr.put(ctx,
                        com.wenqu.ai.util.UsageAttr.of(st.userId, st.sessionId, null, "chat")))
                .map(resp -> {
                    org.springframework.ai.chat.metadata.Usage usage = resp.getMetadata() == null
                            ? null : resp.getMetadata().getUsage();
                    if (usage != null) {
                        Integer completion = usage.getCompletionTokens();
                        if (completion != null && completion > 0) st.realOutputTokens = completion;
                        Integer prompt = usage.getPromptTokens();
                        if (prompt != null && prompt > 0) st.realPromptTokens = prompt;
                        // 缓存命中 token（prompt 缓存）：网关方言不一，尽力从 nativeUsage 里挖；
                        // 挖不到保持 0，容量面板隐藏「缓存命中率」行（不显示假数据）
                        st.cachedPromptTokens = extractCachedTokens(usage);
                        // 工具调用循环逐轮累加（native 原始口径，见 UsageAccumulator 注释）；
                        // 上面的单值字段保留作 native 缺失网关的单轮兜底
                        st.roundUsage.accept(resp.getMetadata() == null ? null : resp.getMetadata().getId(), usage);
                    }
                    Object output = resp.getResult() == null ? null : resp.getResult().getOutput();
                    String delta = "";
                    if (resp.getResult() != null) {
                        // 终止原因挂在 Generation 元数据上（网关只在末块携带）。工具循环各轮末块的到达顺序
                        // 不保证（见 UsageAccumulator 注释），故不按"最后一个"取值，而是任一轮报 length 即判
                        // 截断：正常轮只会是 stop / tool_calls，出现 length 就是那一轮输出被上限切断了。
                        String fr = resp.getResult().getMetadata() == null
                                ? null : resp.getResult().getMetadata().getFinishReason();
                        if (fr != null && !fr.isBlank()) {
                            st.lastFinishReason = fr;
                            if ("length".equalsIgnoreCase(fr)) st.finishLength = true;
                        }
                    }
                    if (output instanceof org.springframework.ai.chat.messages.AssistantMessage am) {
                        delta = am.getText() == null ? "" : am.getText();
                        // 思考增量计入 completion tokens 却不进正文，生成量对账必须扣掉它否则必然误判
                        Object rc = am.getMetadata() == null ? null : am.getMetadata().get("reasoningContent");
                        if (rc != null) st.reasoningChars += String.valueOf(rc).length();
                    }
                    return delta;
                })
                .doOnNext(token -> {
                    st.emitBuf.append(token);
                    String bufStr = st.emitBuf.toString();
                    // 未闭合的 <process> 开口：开口后的内容走增量通道（过程独白按 token 流式下发，不再等
                    // </process> 到达才整块蹦出）。开口前的内容（head）照旧走下面的完整块/正文剥离流程；
                    // 开口后的正文（openBody）只扣留「可能是 </process> 真前缀」的尾巴，其余立即下发；
                    // 缓冲收敛为「开口标签 + 尾巴」，下一 token 从同一开口位置继续追加。
                    int openIdx = bufStr.lastIndexOf("<process>");
                    boolean openProcess = openIdx >= 0 && bufStr.indexOf("</process>", openIdx) < 0;
                    String head = openProcess ? bufStr.substring(0, openIdx) : bufStr;
                    String openBody = openProcess ? bufStr.substring(openIdx + "<process>".length()) : null;
                    // 存在未完整闭合的 related/process 块（开始/闭合标签被跨 token 切分也覆盖）：继续缓冲不下发
                    // （process 的未闭合开口已切到 openBody 增量通道，head 里只剩半截开始标签这类残留）
                    boolean unclosedRelated = containsUnclosedRelated(head);
                    boolean unclosedProcess = containsUnclosedProcess(head);
                    // 未闭合的 DSML 工具调用块：与 related/process 同策略——继续缓冲等闭合，
                    // 闭合后整块丢弃。模型把工具调用以文本再写一遍时，开标签与参数值先到、
                    // 闭合标签后到，不等闭合就发会把半截标签漏进正文。
                    boolean unclosedDsml = containsUnclosedDsml(bufStr, st.dsmlToolNamesLc);
                    if (unclosedDsml && !unclosedRelated && !unclosedProcess) {
                        // 只可能是 DSML 未闭合：整批继续缓冲等闭合标签到达（与 related/process 同款等待）
                        if (bufStr.length() > 3000) {
                            // 模型压根不写闭合标签（输出触顶截断在闭合之前）：开口起整段丢弃，
                            // 否则裸露的 <searchKnowledge> 与参数值会一路落屏——按原文发正文等于把协议噪声泄给用户
                            String trimmed = dropUnclosedDsmlTail(bufStr, st.dsmlToolNamesLc);
                            if (!trimmed.equals(bufStr)) {
                                addDegradation(st.degradations, st.degradedCodes, "toolTextMalformed",
                                        "模型以文本形式输出了未闭合的工具调用，已从回答中移除");
                            }
                            st.emitBuf.setLength(0);
                            if (!trimmed.isEmpty()) {
                                st.fullResponse.append(trimmed);
                                if (!sendSseEvent(emitter, "token", trimmed, st.sessionId)
                                        && !st.keepRunningWithoutChannel()) {
                                    st.disposeSafe();
                                    return;
                                }
                            }
                        }
                        return;
                    }
                    if (unclosedRelated || unclosedProcess) {
                        if (bufStr.length() > 3000) {
                            st.emitBuf.setLength(0);
                            // 缓冲整体倾倒，未闭合开口的增量态一并归零（其内容已随本次倾倒归入过程通道）
                            st.processOpen = false;
                            st.openProcessChars = 0;
                            st.processMalformedNoted = false;
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
                                    if (!sendSseEvent(emitter, "token", ansPart, st.sessionId)
                                            && !st.keepRunningWithoutChannel()) {
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
                                // 客户端断开：取消流订阅立即停止模型输出（不补 error/complete）；
                                // 本轮在等人作答或已转后台续跑则只丢事件、继续生成
                                if (!sendSseEvent(emitter, "token", raw, st.sessionId)
                                        && !st.keepRunningWithoutChannel()) {
                                    st.disposeSafe();
                                    return;
                                }
                            }
                        }
                        return;
                    }
                    // 提取完整 process 块 → 过程通道（不进正文）：实时 SSE process 事件 + 时间线过程段
                    StringBuilder procBuf = new StringBuilder();
                    java.util.regex.Matcher pm = processPattern.matcher(head);
                    // 每个块内容剥前导空白：模型写 "<process>\n内容\n</process>"，标签后的换行是格式噪声，
                    // 不剥则灰字块顶部空一行且随消息落库（多个块同批拼接时中间也会出空白行）
                    while (pm.find()) procBuf.append(pm.group(1).stripLeading());
                    String clean = head.replaceAll("<process>[\\s\\S]*?</process>", "");
                    // 剥离完整 related 块，收集推荐内容
                    java.util.regex.Matcher rm = relatedPattern.matcher(clean);
                    while (rm.find()) {
                        st.relatedBlock.append(rm.group(1)).append("\n");
                    }
                    clean = clean.replaceAll("<related>[\\s\\S]*?</related>", "");
                    // 剥离模型以文本形式重复输出的工具调用（DSML）：工具卡片已由 native 链路渲染，
                    // 这份是重复产物，留着就以标签字样漏进正文。放在 related 之后、扣留之前，
                    // 保证扣留窗口看到的已是清洗过的文本。
                    if (!st.dsmlToolNamesLc.isEmpty()) {
                        String dsmlClean = stripDsmlToolText(clean, st.dsmlToolNamesLc);
                        if (!dsmlClean.equals(clean)) {
                            if (!st.dsmlStrippedNoted) {
                                st.dsmlStrippedNoted = true;
                                addDegradation(st.degradations, st.degradedCodes, "toolTextLeak",
                                        "模型以文本形式重复输出了工具调用内容，已自动从回答中移除");
                                log.warn("[TOOL-TEXT] 模型以 DSML 文本重复输出工具调用，已剥离: model={} sid={}",
                                        st.model, st.sessionId);
                            }
                            clean = dsmlClean;
                        }
                    }
                    // 未闭合开口的增量：扣留可能是 </process> 真前缀的尾巴，其余立即下发。首块剥前导空白
                    // （模型写 "<process>\n内容"，不剥则灰字块顶部空一行），其后原样追加（段内换行属模型内容）
                    String emitProc = "";
                    String heldProc = "";
                    if (openBody != null) {
                        int keepProc = processEndPrefixSuffixLen(openBody);
                        heldProc = openBody.substring(openBody.length() - keepProc);
                        emitProc = openBody.substring(0, openBody.length() - keepProc);
                        if (!st.processOpen) {
                            emitProc = emitProc.stripLeading();
                            st.processOpen = !emitProc.isEmpty(); // 剥完仍空（当前只到换行）：下个增量继续剥
                        }
                        st.openProcessChars += emitProc.length();
                        if (st.openProcessChars > 3000 && !st.processMalformedNoted) {
                            st.processMalformedNoted = true;
                            addDegradation(st.degradations, st.degradedCodes, "processMalformed",
                                    "模型输出格式异常（process 标签长时间未闭合），已按过程叙述继续呈现");
                        }
                    } else {
                        // 开口已闭合（或本批无开口）：增量态归零，下一个开口重新按首块处理
                        st.processOpen = false;
                        st.openProcessChars = 0;
                        st.processMalformedNoted = false;
                    }
                    // 尾部只扣留「可能是标签真前缀」的后缀（通常 0 字符），其余立即下发——
                    // 此前固定扣 19 字符：正文 token 滞后下发而 tool_status/process 事件即时下发，
                    // 旁路事件越过未下发正文 ⇒ 前端实时时间线错序断词（「提|示文件已存在」被劈开）
                    st.emitBuf.setLength(0);
                    int keep = tagPrefixSuffixLen(clean);
                    String sendPart = clean.substring(0, clean.length() - keep);
                    String tailKeep = clean.substring(clean.length() - keep);
                    st.emitBuf.append(tailKeep);
                    // 开口标签与扣留尾巴留在缓冲：下一 token 仍从此开口位置续（增量态因此可跨 token 延续）
                    if (openBody != null) st.emitBuf.append("<process>").append(heldProc);
                    if (!sendPart.isEmpty()) {
                        st.fullResponse.append(sendPart);
                        // 客户端断开：取消流订阅立即停止模型输出（不补 error/complete）；
                        // 本轮在等人作答或已转后台续跑则只丢事件、继续生成（正文仍会完整落库）
                        if (!sendSseEvent(emitter, "token", sendPart, st.sessionId)
                                && !st.keepRunningWithoutChannel()) {
                            st.disposeSafe();
                        }
                    }
                    // 过程事件在正文之后下发（同缓冲内先到的正文先行，保持流式顺序）
                    if (procBuf.length() > 0) {
                        routeProcessText(st, procBuf.toString());
                    }
                    // 未闭合开口的增量紧随其后（到达顺序即真实顺序，前端按序续写过程段）
                    if (!emitProc.isEmpty()) {
                        routeProcessText(st, emitProc);
                    }
                })
                .doOnError(error -> {
                    // 客户端已断开：不重试也不报错（管道已不在），直接收尾；
                    // 本轮在等人作答或已转后台续跑则照常走错误处置（重试/降级落库），不给断开掐死
                    if (clientDisconnected(st.emitter) && !st.keepRunningWithoutChannel()) {
                        st.disposeSafe();
                        return;
                    }
                    Throwable root = error;
                    while (root.getCause() != null) root = root.getCause();
                    // 额度不足/限流不重试：重试只会再打一次注定失败的请求（可能再被计费一次），
                    // 额度不足等多久都不会自己好，该做的是提示去充值或换模型。
                    // 识别口径在 ModelQuotaGuard（与聊天链路同一处），此处只负责不给它重试。
                    boolean retryable = !ModelQuotaGuard.isQuotaExhausted(error)
                            && !ModelQuotaGuard.isRateLimited(error);
                    boolean noTokenYet = st.fullResponse.length() == 0 && st.emitBuf.length() == 0;
                    if (retryable && noTokenYet && st.retried.getAndIncrement() < streamRetryCount()) {
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
                        // 未闭合开口的增量态同属本轮缓冲：一并归零（否则重试后首块不再剥前导空白）
                        st.processOpen = false;
                        st.openProcessChars = 0;
                        st.processMalformedNoted = false;
                        // 终止原因与思考量是旧一次尝试的观测值：不归零则重试后可能带着上次的 length 误标
                        st.finishLength = false;
                        st.lastFinishReason = null;
                        st.reasoningChars = 0;
                        st.disposableRef.set(buildAnswerStream(system, user, st, agent));
                        return;
                    }
                    log.error("Stream error: {} -> {}", error.getClass().getSimpleName(), root.toString());
                    // 额度不足/限流单独成句：这两类的处置与「稍后重试」完全相反
                    // （限流等一会就好，额度不足要去充值或换模型），混进通用兜底等于没告诉用户
                    String msg;
                    if (root instanceof java.net.ConnectException) {
                        msg = "无法连接 AI 服务，请检查网络或 API 地址";
                    } else if (ModelQuotaGuard.isQuotaExhausted(error)) {
                        msg = "模型服务额度不足：请到「模型供应商」充值，或改用其他模型";
                    } else if (ModelQuotaGuard.isRateLimited(error)) {
                        msg = "模型服务限流，请稍后重试";
                    } else {
                        msg = "AI 回复失败，请稍后重试";
                    }
                    st.degradations.add(Map.of("code",
                            ModelQuotaGuard.isQuotaExhausted(error) ? "modelQuotaExhausted" : "streamError",
                            "msg", "模型输出中断：" + msg));
                    sendSseEvent(emitter, "error", msg, st.sessionId);
                    // 失败兜底落库：工具失败/超出单轮步数上限这类轮次，诊断信息全在工具记录里
                    // （工具名、入参、失败原因、耗时），正文可能一个 token 都没有。此前错误路径
                    // 既不落库也不走 persistPartialAnswer，导致整条助手消息连同全部工具卡片在刷新/
                    // 重进会话后彻底蒸发——用户看到的只是「问题还在，回答说没了」。
                    // 与正常完成/中断兜底共用 answerPersistGate幂等闸，三条路径 CAS 先到先得。
                    if (st.claimTerminal()) {
                        persistPartialAnswer(st, STREAM_ERROR_SUFFIX);
                    }
                    // 终态：停整轮流级心跳（error 路径）
                    stopRunHeartbeat(st);
                    completeEmitter(emitter);
                    stopTurnDeadline(emitter);
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
                        // 收尾兜底再剥一次 DSML：跨 token 切分导致主路径扣留窗口没兜住的整块
                        // （闭合标签恰好落在缓冲被扣留、下一 token 才补齐的场景）
                        if (!st.dsmlToolNamesLc.isEmpty()) {
                            String dsmlRest = dropUnclosedDsmlTail(
                                    stripDsmlToolText(rest, st.dsmlToolNamesLc), st.dsmlToolNamesLc);
                            if (!dsmlRest.equals(rest)) {
                                if (!st.dsmlStrippedNoted) {
                                    st.dsmlStrippedNoted = true;
                                    addDegradation(st.degradations, st.degradedCodes, "toolTextLeak",
                                            "模型以文本形式重复输出了工具调用内容，已自动从回答中移除");
                                }
                                rest = dsmlRest;
                            }
                        }
                        if (!rest.isEmpty()) {
                            st.fullResponse.append(rest);
                            sendSseEvent(emitter, "token", rest, st.sessionId);
                        }
                        // 正文下发后再路由过程独白（流式顺序：先到的正文先行）。
                        // 尾部可能压着半截 </process>（输出触顶截断在闭合标签之前）：剥掉，
                        // 免得 "</pro" 这类残片当成过程内容落屏/落库
                        int ptLt = procTail.lastIndexOf("<");
                        if (ptLt >= 0 && isProcessEndStart(procTail.substring(ptLt))) procTail.setLength(ptLt);
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
                    // 终态兜底：整轮再剥一次 DSML 工具调用文本。流式主路径已逐批剥离，
                    // 这里覆盖非流式/异常路径与跨 token 边界残留——工具卡片已展示同一内容，
                    // 残留标签纯属噪声，落到用户眼前就是截图里那种裸露的 <searchknowledge>。
                    if (!st.dsmlToolNamesLc.isEmpty()) {
                        String dsmlAnswer = dropUnclosedDsmlTail(
                                stripDsmlToolText(answer, st.dsmlToolNamesLc), st.dsmlToolNamesLc);
                        if (!dsmlAnswer.equals(answer)) {
                            if (!st.dsmlStrippedNoted) {
                                st.dsmlStrippedNoted = true;
                                addDegradation(st.degradations, st.degradedCodes, "toolTextLeak",
                                        "模型以文本形式重复输出了工具调用内容，已自动从回答中移除");
                            }
                            log.warn("[TOOL-TEXT] 终态兜底剥离 DSML 工具调用文本 {} 字: session={}",
                                    answer.length() - dsmlAnswer.length(), st.sessionId);
                            answer = dsmlAnswer;
                        }
                    }
                    // 护栏：模型把正文写进 <related> 时，"剥离推荐块"等于把回答吃掉——实测出现过
                    // 回答只剩开头 28 字、其余整段落进推荐块（推荐块此前不随消息落库，刷新后彻底消失）。
                    // 推荐位只放短句，超长条目按正文并回回答。
                    List<String> relatedChips = new ArrayList<>();
                    List<String> relatedProse = new ArrayList<>();
                    for (String r : related) {
                        if (r.length() > RELATED_PROSE_MIN_CHARS) relatedProse.add(r); else relatedChips.add(r);
                    }
                    if (!relatedProse.isEmpty()) {
                        String merged = String.join("\n\n", relatedProse);
                        answer = answer.isBlank() ? merged : answer.stripTrailing() + "\n\n" + merged;
                        related = relatedChips;
                        addDegradation(st.degradations, st.degradedCodes, "relatedHoldsAnswer",
                                "模型把正文写进了推荐块（" + relatedProse.size() + " 段），已并回回答");
                        log.warn("[FAIL-LOUD] related 内含正文 {} 段（{} 字），已并回回答: session={}",
                                relatedProse.size(), merged.length(), st.sessionId);
                    }
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
                    // 引用编号越界校验：剔除超出来源范围的 [N]（LLM 偶发编造编号，用户点击角标无溯源）。
                    // 来源为空（maxRef=0）时同样执行——本轮没有任何可溯源来源，正文不允许残留任何 [N]；
                    // 修复前 sources 为空时整段校验被跳过，模型给 MCP 资料自编的 [1][2] 全部悬空（面板无对应条目可点）。
                    // 代码段（``` 围栏/行内反引号）跳过：代码里的 [0] 是字面量下标，无差别剔除会改坏代码示例。
                    int maxRef = sources.size();
                    List<int[]> citeCodeSegs = codeRanges(answer);
                    java.util.regex.Matcher cm = CITE_PATTERN.matcher(answer);
                    StringBuilder cb = new StringBuilder();
                    int invalidRefs = 0;
                    while (cm.find()) {
                        int n = Integer.parseInt(cm.group(1));
                        if (!inCodeSegment(citeCodeSegs, cm.start()) && (n < 1 || n > maxRef)) {
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
                        // 用量归属：自检跑在流收尾回调线程上，ThreadLocal 身份已不可见——hold 显式带上
                        com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(
                                st.userId, st.sessionId, null, "citecheck"));
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
                        } finally {
                            com.wenqu.ai.util.UsageAttr.clear();
                        }
                        st.stageMs.put("citation", System.currentTimeMillis() - st.startTime);
                    }
                    // 截断必须让用户看见：降级提示受 chat.retrievalDebugEnabled 控制（面向用户的部署
                    // 默认关闭），只落提示等于没提示——标注直接进正文尾部，随 done 与落库一起走，
                    // 刷新后仍在（与中断兜底的 TRUNCATION_SUFFIX 同口径）。
                    String truncSuffix = truncationSuffix(st, answer);
                    if (truncSuffix != null) {
                        answer = answer.stripTrailing() + truncSuffix;
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
                    // noHit 事后判定（基于本轮最终引用）：主链路检索 0 填充但工具检索（智能体知识检索工具等）
                    // 注册了来源时不算"未检索到"——生成前判定拿不到工具后续注册的引用，会出现在回答带着
                    // [N] 引用的同时提示"未检索到相关资料"的自相矛盾。最终（sources 含工具注册项）仍为空才提示。
                    if (sources.isEmpty()) {
                        addDegradation(st.degradations, st.degradedCodes, "noHit", "未检索到相关资料，回答可能缺乏依据");
                    }
                    // Token 用量快照（done 下发与落库共用；中断兜底路径同口径，见 buildUsageSnapshot）
                    Map<String, Object> tokens = buildUsageSnapshot(st, answer);
                    // 落库幂等闸：与中断兜底（disposeSafe → persistPartialAnswer）CAS 先到先得——
                    // SSE 超时回调与正常完成存在并发窗口，不加闸同一轮可能落两条。CAS 失败说明
                    // 截断版已入库，此处放弃落库（messageId 为空，done 照常下发，反馈按钮退化为不可用）
                    String messageId = null;
                    if (st.claimTerminal()) {
                        // 重新生成：先软删被替换的旧回答再写新回答。放在落库这一刻而不是请求开始——
                        // 本轮失败时旧回答仍在，用户不会两头空；不删则历史里同一问题会出现两条答案。
                        // 旧回答软删时登记分支版本组（组键挂到新回答），多版本可跨刷新切换
                        String replaceGroup = null;
                        if (st.replaceMessageId != null && !st.replaceMessageId.isBlank()) {
                            replaceGroup = sessionService.beginAnswerReplace(st.replaceMessageId);
                        }
                        // 16 参重载（含 tokens/model）：助手消息把用量 JSON 与生效模型随行落库
                        // （历史回看/会话累计 + 使用统计按模型分组的数据源）
                        messageId = sessionService.appendMessage(st.sessionId, "assistant", answer,
                                finalImgs, sourcesJson, st.thinkingHolder[0], finalRetrievedJson,
                                sessionArtifacts.isEmpty() ? null : JSON.toJSONString(sessionArtifacts),
                                toolCallsJson, null, JSON.toJSONString(tokens), timelineJson,
                                processText.isEmpty() ? null : processText,
                                // 未使用智能体的问答 agent 为 null（会话未绑定/走全局配置）：必须判空——
                                // 此前直接 agent.getId() 在 onComplete 回调里抛 NPE，被 reactor 丢弃
                                // （onComplete 阶段抛异常无处路由），表现为「助手消息不落库 + done 永不
                                // 下发 + 前端永远转圈」，且日志只有一行 onErrorDropped 极难定位。
                                agent == null ? null : agent.getId(),
                                agent == null ? null : agent.getName(),
                                st.model,
                                // 相关推荐随消息落库：此前只随 done 下发，刷新后「接下来可以」整块消失
                                related.isEmpty() ? null : JSON.toJSONString(related),
                                // 助手消息无 @ / # 引用（引用仅用户消息携带），占位保持主方法签名一致
                                null, null,
                                // 计划批准卡随行落库：刷新/历史按轮重建气泡计划卡（此前只活在实时流里）
                                planRecordJson(st));
                        // 新回答挂上被替换旧回答的版本组键：组内版本序列即 ‹ n/N › 切换数据源
                        if (replaceGroup != null && messageId != null) {
                            sessionService.setMessageVariant(messageId, replaceGroup);
                        }
                    }

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
                    // 本轮用户消息 ID（regenerate 时为 null，沿用上一轮请求已入库的消息）：
                    // 编辑重发后前端给新用户消息挂分支切换器、支持再次编辑
                    if (st.userMessageId != null && !st.userMessageId.isBlank()) {
                        donePayload.put("userMessageId", st.userMessageId);
                    }
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
                    // 本轮生效模型引用（与落库同源）：前端把 live 气泡的模型校正为后端权威解析值
                    // （请求未带覆盖时后端回落个人默认，前端本地只知道空覆盖），供「模型已切换」比对
                    donePayload.put("model", st.model);
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
                    // §4 轮级委派归属：本轮由 @ 提及的智能体作答时单独下发（agentId/agentName 保持会话绑定口径，
                    // 前端绑定镜像不受影响；气泡归属按委派值展示，与刷新后按落库快照回显的结果一致）
                    if (st.delegatedAgentId != null) {
                        donePayload.put("delegatedAgentId", st.delegatedAgentId);
                        donePayload.put("delegatedAgentName",
                                st.delegatedAgentName == null ? "" : st.delegatedAgentName);
                    }
                    sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), st.sessionId);
                    completeEmitter(emitter);
                    // 本轮收尾：显式回收台账（断线后台续跑的轮在通道断开回调里被故意留着，重复回收无害）
                    stopTurnDeadline(emitter);
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
        /**
         * 本轮身份把手（turnId + userId 的来源）：沿用 {@code chat()} 入口发的那一个；
         * 非 chat 入口（手动压缩等）没有把手，自造一个、也自己摘。
         */
        final TurnHandle handle;
        /**
         * 本轮唯一 ID。计划卡、子智能体编排、检索统计这些事件都早于本状态构造，
         * 共用同一个 ID 才拼得回一条完整轨迹。
         */
        final String turnId;
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
        /** 未闭合 &lt;process&gt; 开口的增量下发状态：processOpen=本开口已下发过首块（首块剥前导空白，
         *  其后原样追加）；openProcessChars=本开口已下发字符数（长时间不闭合时 fail-loud 提示一次） */
        volatile boolean processOpen;
        volatile int openProcessChars;
        volatile boolean processMalformedNoted;
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
        /** 本轮思考强度档位（请求级优先，null=只有思考开关、无强度语义）；生成流按此下发厂商方言字段 */
        volatile String reasoningLevel;
        /** 游客分享会话（公开链接）：工具白名单收窄为知识检索+内置项，沙盒/产物/MCP/技能不暴露 */
        volatile boolean guestMode;
        /** 本轮"有副作用"工具名单（沙盒/MCP，enabledToolCallbacks 装配时回填）：审批模式 ask 时执行前需确认 */
        volatile java.util.Set<String> sensitiveToolNames = java.util.Set.of();
        /** 本轮接入的 MCP 工具名单（enabledToolCallbacks 装配时回填）：结果注册引用来源用 */
        volatile java.util.Set<String> mcpToolNames = java.util.Set.of();
        /**
         * 本轮生效工具名（小写）：剥离模型误吐的 DSML 工具调用文本的白名单。
         * 只认本轮真实下发的工具名——按通用 XML 规则剥会把模型正常写的 HTML/XML 正文
         * （技术问答里代码块尤其常见）当噪声吃掉，白名单匹配则天然不误伤。
         */
        volatile java.util.Set<String> dsmlToolNamesLc = java.util.Set.of();
        /** DSML 文本剥离是否已提示过降级（同一轮只提示一次，避免刷屏） */
        volatile boolean dsmlStrippedNoted;
        /** 本轮已注册的 MCP 引用条数（上限 tool.mcpCiteMaxRefs，防引用面板被单轮刷屏） */
        final java.util.concurrent.atomic.AtomicInteger mcpCiteCount = new java.util.concurrent.atomic.AtomicInteger();
        /** 本轮 askUser 已提问次数（上限 MAX_ASKS_PER_TURN）：多轮澄清合法，刷屏循环不合法 */
        final java.util.concurrent.atomic.AtomicInteger askCount = new java.util.concurrent.atomic.AtomicInteger();
        /** 工具执行审批模式（本轮智能体的 toolApprovalMode；null=auto） */
        volatile String toolApprovalMode;
        /** 单轮工具调用步数上限（agent.maxToolSteps > 全局 agent.maxToolSteps；<=0 不限制）；已执行步数 */
        volatile int maxToolSteps;
        final java.util.concurrent.atomic.AtomicInteger toolStepCount = new java.util.concurrent.atomic.AtomicInteger();
        /**
         * 检索-反思循环模式（Agentic RAG，runChat 按配置与思考档位判定后回填）：开启时本轮强制暴露
         * 知识检索工具（enabledToolCallbacks 两分支 ||），工具结果尾部附「证据充分性自评」约束
         * （KnowledgeRetrievalTool.setReflective 按轮注入）。knowledgeOff / 工作流驱动轮恒为 false。
         */
        volatile boolean reflectiveRetrieval;
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
        /** 生效最大输出 token（模型管理中该模型声明的最大输出；0=未声明，不下发 max_tokens）：max_tokens 下发与终态触顶判定共用 */
        volatile int effectiveMaxOutput;
        /** 网关返回的真实 usage（部分兼容网关末块携带；拿不到保持 0，回落 TokenCounter 估算） */
        volatile int realPromptTokens;
        volatile int realOutputTokens;
        /**
         * 网关在末块携带的终止原因（stop/length/tool_calls/…；null=网关未带）。
         * 截断判定的直接依据——此前只看「输出 token ≥ 声明的最大输出」，而模型声明值常远大于
         * 厂商实际上限，那条判定等于永不生效。
         */
        volatile String lastFinishReason;
        /** 本轮任一轮次报过 finish_reason=length（输出触顶）：截断标注的判据，比逐轮取值抗末块乱序 */
        volatile boolean finishLength;
        /** 本轮网关返回的思考增量字符数（计入 completion tokens 但不进正文）：生成量对账要扣掉它 */
        volatile int reasoningChars;
        /** 缓存命中的 prompt token（网关 prompt_tokens_details.cached_tokens；0=网关未回传，面板隐藏该行） */
        volatile int cachedPromptTokens;
        /**
         * 生成链路的逐轮用量累加器（工具调用循环每轮一次真实请求，累加后才是本次问答实耗）。
         * 与台账（DynamicOpenAiChatModel 出口记账）共用 {@link com.wenqu.ai.util.UsageAccumulator}
         * 的口径，保证「回答气泡上的本次用量」与「个人统计/账单」同源。
         */
        final com.wenqu.ai.util.UsageAccumulator roundUsage = new com.wenqu.ai.util.UsageAccumulator();
        /** 本轮生效窗口 token 与来源（用户所选档位/模型声明/未声明），容量面板展示「已用 X / 窗口 Y」 */
        volatile int windowTokens;
        volatile String windowSource = "";
        /** 容量分类用量（消息/早期摘要/知识块/系统提示词/系统工具/MCP 工具/技能/其他）：估算后按真实 prompt 等比校准 */
        volatile Map<String, Integer> ctxParts = new LinkedHashMap<>();
        /** 本轮压缩进摘要的历史轮数（0=未压缩）；done 下发供前端提示「已压缩 N 轮」 */
        volatile int historyCompressedTurns;
        /** 编排视图：各子代理分支的最终状态（主链路编排完成后回填，随 done 下发并持久化） */
        volatile java.util.List<Map<String, Object>> subagentBranches = List.of();
        /** 按需委派的路由结果（{candidates,picked,names}；未启用路由时为 null），随 done 下发并持久化 */
        volatile Map<String, Object> subagentRoute = null;
        /** 重新生成时被替换的旧回答消息 ID：落库前软删旧行（历史只留最新一版；null=普通问答） */
        volatile String replaceMessageId;
        /** 本轮用户消息 ID（0.3 段即时落库返回；regenerate 时为 null——沿用上一轮请求已入库的消息）。
         *  随 done 下发：编辑重发后前端用它给新用户消息挂分支切换器、支持再次编辑 */
        volatile String userMessageId;
        /** 助手消息落库幂等闸：正常完成（doOnComplete）与中断兜底（disposeSafe → persistPartialAnswer）
         *  两条路径 CAS 先到先得——SSE 超时回调与正常完成存在并发窗口，不加闸同一轮可能落两条 */
        final java.util.concurrent.atomic.AtomicBoolean answerPersistGate = new java.util.concurrent.atomic.AtomicBoolean(false);
        /**
         * 提问卡（askUser）阻塞计数：>0 表示本轮正挂在「等你作答」上。此刻通道失效<b>不中止本轮</b>
         * ——提问已落库，用户在另一台设备或刷新页面后仍能答，答案必须回到还阻塞着的工具线程。
         */
        final java.util.concurrent.atomic.AtomicInteger askWaits = new java.util.concurrent.atomic.AtomicInteger();
        /**
         * 计划批准卡（planApproval）阻塞计数：>0 表示本轮正挂在「等你批准执行计划」上。
         * 语义与 {@link #askWaits} 相同：此刻通道失效不中止本轮——计划已落库，用户在另一台设备
         * 或刷新页面后仍能批准，批准后的回答必须回到还阻塞着的流水线线程。
         */
        final java.util.concurrent.atomic.AtomicInteger planWaits = new java.util.concurrent.atomic.AtomicInteger();
        /**
         * 本轮计划快照（计划模式，{plan,status}）：runPlanPhase 裁决落定时写入，助手消息落库时随 plan 列持久化
         * ——刷新/切会话后按轮重建气泡计划卡（此前计划只活在实时流里，刷新即丢）。null=本轮未走计划模式。
         */
        volatile Map<String, Object> planRecord;
        /** 计划被用户「继续对话」取代（gate 收尾按取代口径：不写「未批准」收束语，发 plan_superseded） */
        volatile boolean planSuperseded;
        /** 本轮计划是「按消息意图自动开启」（用户没开计划开关）：随计划快照落库，卡片标「自动开启」 */
        volatile boolean planAuto;
        /**
         * 已装配的工具回调缓存（计划模式闸门先行装配时写入）：buildAnswerStream 直接复用，
         * 避免 MCP 工具集二次装配（外部 server 连接不重复建立）。null=未缓存，走原路径。
         */
        volatile java.util.List<org.springframework.ai.tool.ToolCallback> enabledCallbacks;
        /**
         * 断线后台续跑：本轮失去了通道（人在作答时才断开），此后 SSE 事件一律丢弃，
         * 但生成继续走完并按正常路径完整落库——用户回到本会话就能看到答案。
         */
        volatile boolean detached;

        /**
         * 用户按下「停止本轮」：置位后不再执行任何工具步（含副作用工具），生成流被掐断，
         * 半程正文按 {@link #STOP_SUFFIX} 落库。
         * <p>与 {@link #detached} 分开是因为口径相反：断线时「人在等答复」要把本轮留在后台跑完，
         * 而用户叫停是<b>明确要它停</b>——正挂在审批/提问/计划等待上的轮同样必须停下。
         */
        volatile boolean userStopped;

        /**
         * 通道已失效（客户端断开 / emitter 完成 / 发送失败）时问一句：本轮还要不要继续跑？
         * <p>正在等用户作答、或早已转入后台续跑 ⇒ 置 {@link #detached} 返回 true：事件丢弃但不掐流，
         * 用户答完后回答照常生成并落库；其余情形返回 false，由调用方照常规中止本轮并落半程正文
         * ——「断开即止损」的成本闸门口径不变，只在人在回路这一段让路。
         */
        boolean keepRunningWithoutChannel() {
            if (askWaits.get() > 0 || planWaits.get() > 0 || detached) {
                detached = true;
                return true;
            }
            return false;
        }

        /** 本轮是否已收尾（正文已落库，正常完成或截断兜底都算）：台账据此回收 */
        boolean settled() {
            return answerPersistGate.get();
        }

        /**
         * 占住「本轮已收尾」闸位，并同时摘掉会话轮次登记（{@link #TURN_STATES} / {@link #TURN_HANDLES}）。
         * <p>摘登记必须挂在这里而不是流水线线程的 finally：{@code runChat} 在 {@code subscribe} 之后
         * 立刻就返回了，生成与工具循环此后跑在响应式线程上——finally 一摘，正在出字的轮就从轮次表里
         * 消失，「停止本轮」再也找不到它，账本的归属也会断。谁先占闸谁负责摘，两条终态路径共用。
         *
         * @return true=本次占位成功（调用方应当落库）；false=已有别的终态路径收过尾
         */
        boolean claimTerminal() {
            boolean claimed = answerPersistGate.compareAndSet(false, true);
            if (claimed) {
                TURN_HANDLES.remove(sessionId, handle);
                TURN_STATES.remove(sessionId, this);
            }
            return claimed;
        }

        /** 本轮智能体归属快照（buildAnswerStream 回填）：中断兜底落库时 appendMessage 需要，彼时已拿不到闭包里的 agent */
        volatile String agentId;
        volatile String agentName;
        /** §4 轮级委派：本轮由 @ 提及的智能体作答时的归属（null=无委派）。随 done 单独下发——
         *  done 的 agentId/agentName 保持「会话绑定」口径（前端绑定镜像依赖它），委派归属不可混入 */
        volatile String delegatedAgentId;
        volatile String delegatedAgentName;

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
            // 挂进整轮台账：看门狗到点要停流并截断落库（收集型通道无台账，自然跳过）
            TurnDeadline d = TURN_DEADLINES.get(emitter);
            if (d != null) d.state = this;
            TurnHandle h = TURN_HANDLES.get(sessionId);
            if (h == null) {
                // 非 chat 入口（手动压缩等）：自造把手，收尾时由 claimTerminal 按值摘掉
                h = new TurnHandle(java.util.UUID.randomUUID().toString().replace("-", ""), userId);
                TURN_HANDLES.put(sessionId, h);
            }
            this.handle = h;
            this.turnId = h.turnId();
            // 登记进会话轮次表：服务端「停止本轮」按 sessionId 找到它（摘除见 chat 流水线 finally 与落库收尾）
            TURN_STATES.put(sessionId, this);
        }

        void disposeSafe() {
            // 客户端断开：顺带停整轮流级心跳（防断开后调度任务空转泄漏；发送失败也会自停，双保险）
            java.util.concurrent.ScheduledFuture<?> hb = heartbeat;
            if (hb != null) hb.cancel(false);
            // 本轮已中止：整轮台账与看门狗一并回收（通道断开回调遇后台续跑时会故意留着它）
            stopTurnDeadline(emitter);
            Disposable d = disposableRef.get();
            if (d != null) d.dispose();
            // 中断兜底：按截断态落库，刷新/重进会话后已生成的部分仍在历史里（此前只有完整完成
            // 才落库，中断轮在历史里只剩问题）。与正常完成路径共用幂等闸（CAS 先到先得）。
            // 看门狗截断/断线多发生在工具循环里——正文可能一个 token 都没有，价值全在工具卡片
            // 与计划快照上（用户盯着跑了五分钟的一场执行），只认正文非空会让这种轮整条蒸发，
            // 用户看到的是「已输出的内容刷新后全没了」。真正空轮（无正文且无工具记录）由
            // persistPartialAnswer 内部守卫拦住，不落空消息。
            // 本回调可能在容器线程触发（超时/错误），与流线程的正文追加存在理论竞态——
            // dispose() 已先行掐断上游，最坏读到略短的快照，对存档可接受
            if (claimTerminal()) {
                persistPartialAnswer(this, userStopped ? STOP_SUFFIX : TRUNCATION_SUFFIX);
            }
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

        // ==================== MCP 工具结果：引用注册（origin=MCP） ====================

        /** 结果文本里的 http(s) 链接（MCP 结果常是 JSON 转义文本，换行为字面 \n 序列——字符类显式排除反斜杠，防 URL 粘上后续正文） */
        private static final Pattern MCP_URL_PATTERN = Pattern.compile("https?://[^\\s\"'<>\\\\]+");

        /**
         * MCP 工具结果注册引用来源：扫描结果文本里的 http(s) URL 逐个注册（origin=MCP，与
         * TOOL/WEB 共用同一编号空间；归一化 URL 复用 webRefByUrl 去重——联网与 MCP 拿到同一
         * 网页合并同号）。返回在原结果后追加「引用来源清单」的文本，模型据此在正文标注 [N]；
         * 无 URL / 超上限时原样返回（纯文本结果不伪造来源，模型强编的 [N] 由终态越界校验剔除）。
         * 约定：只有结果文本显式给出 URL 的内容才可溯源——这是通用 MCP 工具唯一稳定的来源标识
         * （如 Context7 文档片段的「Source: https://…」行）。
         */
        String registerMcpCitations(String toolName, String result) {
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
                String key = com.wenqu.ai.service.websearch.WebSearchService.normalizeUrl(url);
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
                String key = com.wenqu.ai.service.websearch.WebSearchService.normalizeUrl(f[0]);
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
            String key = com.wenqu.ai.service.websearch.WebSearchService.normalizeUrl(r.url());
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

    /**
     * 未闭合 process 块增量下发的尾部扣留长度：块内只可能出现 &lt;/process&gt; 一个标签，
     * 因此只扣「可能是它真前缀」的后缀（≤9 字符），其余内容立即下发——过程独白据此与正文一样
     * 按 token 流式长出，而不是等闭合标签到达整块蹦出。
     */
    private static int processEndPrefixSuffixLen(String s) {
        if (s.isEmpty()) return 0;
        int start = Math.max(0, s.length() - 9);
        for (int i = start; i < s.length(); i++) {
            String tail = s.substring(i);
            if (tail.length() < "</process>".length() && "</process>".startsWith(tail)) return s.length() - i;
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

    // ==================== DSML 工具调用文本剥离 ====================
    // 部分模型在 native tool_calls 之外，还会把同一次工具调用以 DSML 文本再写一遍正文：
    //   <searchKnowledge><parameter name="query">…</parameter></searchKnowledge>
    // 工具卡片已经由 native 链路渲染过，这份文本是纯重复产物，不剥离就会以标签字样漏给用户
    // （线上症状：正文里出现 <searchknowledge> / </parameter> 等裸露标签）。
    // 只按「本轮真实下发的工具名」匹配，不做通用 XML 剥离——技术问答里模型正常写的
    // HTML/XML 正文（尤其代码块内的标签）不能被吃掉。

    /** 参数标签（DSML 的参数承载形式），整体含开闭：<parameter …>…</parameter> */
    private static final Pattern dsmlParamPattern =
            Pattern.compile("<parameter\\b[^>]*>[\\s\\S]*?</parameter>", Pattern.CASE_INSENSITIVE);
    /** 残缺参数标签：未闭合的开口与跨 token 切碎的闭合（输出触顶截断时常见） */
    private static final Pattern dsmlParamLoosePattern =
            Pattern.compile("</?parameter\\b[^>]*>?", Pattern.CASE_INSENSITIVE);
    /**
     * HTML/XML 常见标准标签名（小写）：与工具名同名时不做 DSML 剥离。
     * MCP 工具名由外部 server 自定，可能撞上这些名字——按标签剥会吃掉模型正常写的正文。
     * 这些名字本来也不像 DSML 工具名（DSML 惯例是驼峰），排除掉对实际剥离效果无损失。
     */
    private static final java.util.Set<String> HTML_TAG_NAMES = java.util.Set.of(
            "a", "b", "br", "div", "p", "span", "link", "meta", "form", "input", "button",
            "table", "tr", "td", "th", "thead", "tbody", "ul", "ol", "li", "dl", "dt", "dd",
            "h1", "h2", "h3", "h4", "h5", "h6", "img", "code", "pre", "section", "article",
            "header", "footer", "nav", "aside", "main", "style", "script", "title", "head",
            "body", "html", "label", "select", "option", "textarea", "iframe", "video", "audio",
            "canvas", "svg", "path", "g", "text", "circle", "rect", "defs", "use", "symbol");

    /**
     * 剥离正文里的 DSML 工具调用文本（按本轮工具名白名单，忽略大小写）。
     * 分两轮：先整块（<tool …>…</tool>）后参数标签，避免块内多个 parameter 时残留标签字样。
     *
     * @param s     待清洗文本（一批 token 或收尾缓冲）
     * @param names 本轮工具名小写集合；空集合直接原样返回（无工具的纯对话不受影响）
     * @return 清洗后的文本
     */
    static String stripDsmlToolText(String s, java.util.Set<String> names) {
        if (s == null || s.isEmpty() || names == null || names.isEmpty()) return s;
        String out = s;
        // 整块：<tool>…</tool>。工具名逐个转义后拼正则（MCP 工具名可能含正则元字符）
        for (String n : names) {
            String q = java.util.regex.Pattern.quote(n);
            out = out.replaceAll("(?i)<" + q + "\\b[^>]*>[\\s\\S]*?</" + q + "\\s*>", "");
            // 未闭合的开口（截断在 </tool> 之前）：开口起整段丢弃，否则标签与参数值会露屏
            int lt = indexOfIgnoreCase(out, "<" + n);
            if (lt >= 0) {
                // 确认是标签开口而非正文里的同名片段（如 "<searchknowledge 很有用"）
                int gt = out.indexOf('>', lt);
                if (gt < 0 || gt - lt <= 1 + n.length() + 1) out = out.substring(0, lt);
            }
        }
        out = dsmlParamPattern.matcher(out).replaceAll("");
        out = dsmlParamLoosePattern.matcher(out).replaceAll("");
        return out;
    }

    /** 忽略大小写查找子串下标，找不到返回 -1 */
    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase().indexOf(needle.toLowerCase());
    }

    /**
     * 丢弃首个未闭合 DSML 工具调用块自开口起的全部内容（截断兜底）：
     * 返回首个未闭合开口之前的前缀。若没有未闭合块则原样返回。
     */
    static String dropUnclosedDsmlTail(String s, java.util.Set<String> names) {
        if (s == null || s.isEmpty() || names == null || names.isEmpty()) return s;
        String low = s.toLowerCase();
        int cut = -1;
        for (String n : names) {
            int oi = low.indexOf("<" + n);
            while (oi >= 0) {
                int after = oi + 1 + n.length();
                boolean boundary = after >= low.length();
                if (!boundary) {
                    char c = low.charAt(after);
                    boundary = !Character.isLetterOrDigit(c) && c != '_';
                }
                if (boundary && low.indexOf("</" + n, oi) < 0) {
                    cut = cut < 0 ? oi : Math.min(cut, oi);
                    break;
                }
                oi = low.indexOf("<" + n, oi + 1);
            }
        }
        return cut < 0 ? s : s.substring(0, cut);
    }

    /**
     * 文本里是否存在未闭合的 DSML 工具调用块（含跨 token 切分的标签片段）。
     * 用于流式缓冲：出现这种块要继续缓冲等它闭合，闭合后整块丢弃——
     * 否则闭合标签到达前已经把开口和参数值当正文发出去了（正文里出现半截标签）。
     */
    static boolean containsUnclosedDsml(String s, java.util.Set<String> names) {
        if (s == null || s.isEmpty() || names == null || names.isEmpty()) return false;
        String low = s.toLowerCase();
        for (String n : names) {
            String open = "<" + n;
            String close = "</" + n;
            int oi = low.indexOf(open);
            if (oi < 0) continue;
            // 开口后必须紧跟标签边界（空白 / > / /），否则是正文里的同名片段
            int after = oi + open.length();
            if (after < low.length()) {
                char c = low.charAt(after);
                if (Character.isLetterOrDigit(c) || c == '_') continue;
            }
            if (low.indexOf(close, oi) < 0) return true;
        }
        return false;
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
     * §4 轮级委派下发：{id,name,description}。用户主动 @ 的归属变化要在当轮看得见（气泡上「由 X 作答本轮」），
     * 不能像自动派遣那样归入排障开关——那是系统行为，这是用户指令。
     */
    private void emitAgentDelegated(SseEmitter emitter, Agent delegate, String sessionId) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", delegate.getId());
            info.put("name", delegate.getName());
            info.put("description", delegate.getDescription());
            sendSseEvent(emitter, "agent_delegated", JSON.toJSONString(info), sessionId);
        } catch (Exception e) {
            log.debug("[AGENT] 委派事件下发失败（不影响问答）: {}", e.getMessage());
        }
    }

    /**
     * §4 会话内 @ 智能体：从本轮 mentions 解析轮级委派的智能体（type=agent，取第一个）。
     * <p>控制器同步段已做存在性/可读性/子智能体校验；流水线异步线程仍按同一信任边界走
     * {@code agentService.get} 重新解析实体（与 kb/doc 引用同口径，不信任请求直达数据）——
     * 校验通过后至流水线执行之间被删除/改权限的罕见竞态按降级处理：回落会话绑定智能体，
     * 不终止本轮（会话智能体还能答，损失只是"委派没生效"且用户看得见原因）。
     * 多个 agent 提及：取第一个，其余登记降级提示（答一轮的语义只有单主语）。
     */
    private Agent resolveDelegatedAgent(List<ChatRequest.Mention> mentions,
                                        List<Map<String, String>> degradations, Set<String> degradedCodes) {
        if (mentions == null || mentions.isEmpty()) return null;
        Agent first = null;
        boolean hasMore = false;
        for (ChatRequest.Mention m : mentions) {
            if (m == null || !"agent".equals(m.getType()) || m.getId() == null || m.getId().isBlank()) continue;
            if (first != null) {
                hasMore = true;
                continue;
            }
            Agent a = agentService.get(m.getId());
            if (a == null) {
                String shown = (m.getName() == null || m.getName().isBlank()) ? m.getId() : m.getName();
                addDegradation(degradations, degradedCodes, "agentMentionUnavailable",
                        "你 @ 的智能体「" + shown + "」已不可访问（被删除或权限变更），本轮由会话绑定的智能体回答");
                log.warn("[AGENT] @ 提及的智能体 {} 不可解析，本轮回落会话绑定", m.getId());
            } else {
                first = a;
            }
        }
        if (first != null && hasMore) {
            addDegradation(degradations, degradedCodes, "agentMentionMultiple",
                    "一次只能由一个智能体作答：已取第一个 @ 的「" + first.getName() + "」，其余提及本轮忽略");
        }
        return first;
    }

    /**
     * 模型解析链（优先级从高到低）：会话级覆盖（聊天页手动切换）> 用户个人默认模型。
     * 值为引用（providerId/modelId）或遗留纯模型名均可，供应商网关路由由 DynamicOpenAiChatModel 按引用解析；
     * 智能体不再绑定聊天模型、全局 chat.model 兜底已移除——均未配置时返回空串，由调用方 fail-loud 引导配置。
     */
    private String resolveModel(String modelOverride, com.wenqu.ai.model.User prefUser) {
        if (modelOverride != null && !modelOverride.isBlank()) return modelOverride.trim();
        if (prefUser != null && prefUser.getDefaultModel() != null && !prefUser.getDefaultModel().isBlank()) {
            return prefUser.getDefaultModel();
        }
        return "";
    }

    /** 个人偏好用户行（含三类个人默认模型）；匿名/未登录/查询失败返回 null（全部走空语义） */
    /**
     * 在流水线线程内装载本轮用户身份（uid / 部门 / 角色），供检索的文档可见性过滤按**真实用户**判定。
     * <p>背景：问答流水线跑在独立线程池、没有请求上下文，而 {@code HybridRetrievalService} 的
     * 库门下推（{@code loadVisibleKbIds}）与文档可见性晚绑定（{@code loadVisibleDocIdsOfHits}）
     * 都从 {@code RequestUser} 取身份 ⇒ 只会拿到 anonymous。其后果是：配了共享范围
     * （{@code access_level=department/user}）的文档，对**包括被授权者在内**的所有人都判为不可见并被过滤——
     * 也就是"共享给我、或我自己限定范围的资料，在问答里检索不到"。
     * <p>身份从用户档案读，与调用线程无关：网页问答（Tomcat 线程）与定时任务（池线程，uid 由参数传入）
     * 走同一来源；代价是每轮多一次用户表主键查询（与 runChat 内取个人偏好那次同表主键查询同一量级）。
     *
     * @return 是否已装载（未装载时调用方不需要清理）
     */
    private boolean loadIdentity(String userId) {
        if (userId == null || userId.isBlank()
                || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(userId)) {
            // 匿名池（历史兼容会话）：保持 anonymous 语义，不做任何提升
            return false;
        }
        try {
            com.wenqu.ai.model.User u = userMapper.selectById(userId);
            if (u == null) return false;
            com.wenqu.ai.util.RequestUser.set(u.getUid(), u.getDepartmentId(), u.getRole());
            return true;
        } catch (Exception e) {
            // 装载失败按匿名继续（不打断问答），但必须留痕：否则又变成"共享资料检索不到"且无迹可查
            log.warn("[FAIL-LOUD] 流水线线程装载用户身份失败（本轮检索可见性按匿名判定）uid={}: {}",
                    userId, e.getMessage());
            return false;
        }
    }

    private com.wenqu.ai.model.User loadPrefUser(String userId) {
        if (userId == null || userId.isBlank() || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(userId)) {
            return null;
        }
        try {
            return userMapper.selectById(userId);
        } catch (Exception e) {
            log.debug("[PREF] 个人偏好加载失败（视为未配置）: {}", e.getMessage());
            return null;
        }
    }

    /** 系统提示词：智能体显式填写则用智能体提示词（提示词归智能体管，平台已不设全局 System Prompt 配置项），
     *  否则使用内置默认（AppProperties.systemPrompt，yml/env 可覆盖） */
    private String resolveSystemPrompt(Agent agent) {
        boolean hasOwn = agent != null && agent.getSystemPrompt() != null && !agent.getSystemPrompt().isBlank();
        return hasOwn ? agent.getSystemPrompt() : properties.getSystemPrompt();
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
    private java.util.Collection<String> scopeKbIdsOf(Agent agent, String userId) {
        Set<String> builtinScope = builtinScopeKbIds(agent, userId);
        if (builtinScope != null) return builtinScope;
        return (agent == null || agent.getKnowledgeBaseIds() == null || agent.getKnowledgeBaseIds().isBlank())
                ? null : KnowledgeBaseService.splitIds(agent.getKnowledgeBaseIds());
    }

    /**
     * 内置「问渠」的检索范围（非内置或身份无法解析时返回 null）= <b>本轮使用者的默认库「问渠」
     * ＋ 官方内置手册库</b>（「问渠使用手册」，存在时）。默认库每人一个、没有可静态绑定的库 ID，
     * 按使用者动态解析（见 {@link #builtinDefaultKbId}）；手册库是独立的内置库（全局一只、
     * 全员只读），并轨后「问渠怎么用」类问题才检索得到官方手册。
     *
     * @return 库 ID 集合（默认库在前）；非内置智能体或无法解析身份时返回 null（回落不限库）
     */
    private Set<String> builtinScopeKbIds(Agent agent, String userId) {
        String defaultKb = builtinDefaultKbId(agent, userId);
        if (defaultKb == null) return null;
        Set<String> out = new java.util.LinkedHashSet<>();
        out.add(defaultKb);
        KnowledgeBase manual = knowledgeBaseService.builtinKb();
        if (manual != null && manual.getId() != null && !manual.getId().isBlank()) {
            out.add(manual.getId());
        }
        return out;
    }

    /**
     * 内置「问渠」的默认库解析 = <b>本轮使用者自己的默认库「问渠」</b>。智能体行全局唯一
     * （启动维护：多余降级、缺失播种，created_by='system'），默认库却每人一个——没有可静态
     * 绑定的库 ID，故在这里按使用者动态解析（{@link KnowledgeBaseService#defaultId} 懒创建）。
     * 完整检索范围（默认库 ＋ 官方手册库）见 {@link #builtinScopeKbIds}。
     * <p>游客分享/MCP/定时任务等链路传入的 userId 即发布者或任务归属人，同样按人解析；
     * 匿名或解析失败返回 null（回落旧行为：不限库，仍受可见性约束）。
     *
     * @return 默认库 id；非内置智能体或无法解析身份时返回 null
     */
    private String builtinDefaultKbId(Agent agent, String userId) {
        if (agent == null || !Integer.valueOf(1).equals(agent.getIsBuiltin())) return null;
        if (userId == null || userId.isBlank() || com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(userId)) return null;
        try {
            return knowledgeBaseService.defaultId(userId);
        } catch (Exception e) {
            log.warn("[KB] 内置「问渠」默认库解析失败（本轮按不限库检索）uid={}: {}", userId, e.getMessage());
            return null;
        }
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
     * <p>内置「问渠」例外：范围恒为使用者默认库「问渠」＋官方内置手册库的<b>整库</b>
     * （见 {@link #builtinScopeKbIds}），不叠加文档级细选——该字段对内置无编辑入口，存量值也不再生效。</p>
     */
    private Set<String> resolveScopeDocIds(Agent agent, String userId) {
        if (agent == null) return null;
        Set<String> builtinScope = builtinScopeKbIds(agent, userId);
        if (builtinScope != null) {
            // 内置：整库范围（默认库＋官方手册库），文档级细选不参与
            return knowledgeBaseService.docIdsOf(builtinScope);
        }
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

    /**
     * # 历史引用的文本化：用户从本会话历史点名的问答，按「[N] 问 / 答」成对编组前置。
     * 单条超长截断（chat.historyRefMaxChars，默认 4000 字符）——引用是给模型指重点的，
     * 不是整卷搬运；截断是显式行为，尾部带省略标记，不静默吞掉。
     */
    private String buildHistoryRefText(List<ChatRequest.HistoryRef> refs) {
        if (refs == null || refs.isEmpty()) return "";
        int maxChars = Math.max(200, configService.getInt("chat.historyRefMaxChars", 4000));
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ChatRequest.HistoryRef r : refs) {
            if (r == null) continue;
            boolean isUser = "user".equalsIgnoreCase(r.getRole());
            String content = r.getContent() == null ? "" : r.getContent().trim();
            if (content.isEmpty()) continue;
            if (content.length() > maxChars) content = content.substring(0, maxChars) + "…（已截断）";
            sb.append('[').append(i++).append("] ")
              .append(isUser ? "问" : "答").append("：").append(content).append('\n');
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

    /** 上下文预算解析结果：窗口与最大输出均来自「用户所选/模型行声明」（模型管理页维护），供触顶提示与 max_tokens 下发区分来源 */
    private record CtxBudget(int window, int maxOutput, int budget, String windowSource) {}

    private CtxBudget resolveContextBudget(String resolvedModel, Integer requestedWindow,
                                           List<Map<String, String>> degradations, Set<String> degradedCodes) {
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
            addDegradation(degradations, degradedCodes, "ctxWindowUndeclared",
                    "该模型未声明上下文窗口，检索资料将无法填入；请在模型管理页为 " + resolvedModel + " 声明窗口");
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
            addDegradation(degradations, degradedCodes, "contextBudgetFloored",
                    "该模型声明的最大输出 " + maxOutput + " 不小于" + windowSource + "预算（" + windowSource + " " + window
                            + " × 安全系数 " + ModelRegistryService.CONTEXT_SAFETY_FACTOR + " = " + windowBudget + "），"
                            + "上下文预算被托底为 1000，检索资料将无法填入；请在"
                            + (fromUser ? "聊天页把该模型的上下文窗口档位调大" : "模型管理中调小该模型的最大输出"));
            log.warn("[CTX] 最大输出 {} ≥ 窗口预算 {}（窗口 {}，来源={}），上下文预算托底 1000",
                    maxOutput, windowBudget, window, windowSource);
        }
        return new CtxBudget(window, maxOutput, Math.max(budget, 1000), windowSource);
    }

    /** 生效最大输出 token：以模型行声明为准（模型管理页维护）；未声明返回 0 = 不下发 max_tokens，交由厂商默认（无知识库分支的 max_tokens 下发与触顶判定共用） */
    private int effectiveMaxOutputOf(String resolvedModel) {
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

    /**
     * 构建注入「辅助调用」的对话历史（多轮改写/自动派遣等短上下文场景）：
     * 单条截断 + 剥离 [图片N] 标记 + token 总上限。主回答走 {@link #assembleHistory}（预算驱动全量带入）。
     */
    private String buildHistoryText(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return "";
        int perMsgChars = 200;
        int maxTokens = 1200;
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

    // ==================== 对话历史：预算驱动全量带入 + 滚动压缩 ====================

    /** 历史装配结果：注入 prompt 的两段文本 + 分类用量（容量面板） + 本轮压缩轮数 */
    private record HistoryBundle(String summaryText, String recentText,
                                 int summaryTokens, int recentTokens, int compressedTurns) {}

    /** 历史扫描上限（条）：单会话未压缩积压的读取上限，超出部分下一轮压缩自愈 */
    private static final int HISTORY_SCAN_LIMIT = 200;
    /** 至少保留原样的消息条数（2 轮）：保证最近一问一答逐字在场，压缩不吞掉刚说的话 */
    private static final int HISTORY_MIN_KEEP = 4;
    /** 手动压缩（/compact）单次请求的摘要调用上限与单批输入上限：长会话分多批滚动合并，
     *  避免一次调用吃掉整窗（输入超限会被网关直接拒绝）；到上限仍有积压时回传 partial，不假装压完了 */
    private static final int COMPACT_MAX_ROUNDS = 8;
    private static final int COMPACT_CHUNK_TOKENS = 12000;
    /** 手动压缩单批超时下限（毫秒）：用户显式发起、界面有 loading，不必卡自动路径那个 20 秒的紧阈值
     *  （单批输出接近 1024 上限时实测可跑 19 秒，紧阈值下会误判超时） */
    private static final long COMPACT_TIMEOUT_FLOOR_MS = 60000L;

    /**
     * 预算驱动装配对话历史（替代旧的「按轮数 + 单条截断」）：近期轮次原样注入、更早轮次滚动压缩进会话摘要，
     * 全部历史信息始终在场（近期原样 + 远期摘要）。
     * <p>
     * 压缩触发线 = 检索预算 × context.compressRatio。不在整窗打满时才压：压缩调用自身要花输入 token 与数秒时延，
     * 提前压才能给当轮回答留出空间，且之后每轮摊销、不会每轮都触发。
     * 压缩关闭或调用失败 → 回落「按预算装近期、更早丢弃」（失败时强制可见降级提示，不静默丢历史）。
     */
    private HistoryBundle assembleHistory(String sessionId, String resolvedModel, int budget,
                                          List<Map<String, String>> degradations, Set<String> degradedCodes) {
        Session session = sessionService.sessionById(sessionId);
        String summary = (session == null || session.getHistorySummary() == null) ? "" : session.getHistorySummary();
        long untilSeq = (session == null || session.getSummaryUntilSeq() == null) ? 0L : session.getSummaryUntilSeq();
        List<Map<String, Object>> backlog = sessionService.getHistoryAfter(sessionId, untilSeq, HISTORY_SCAN_LIMIT);
        if (backlog == null) {
            // M6 fail-loud：历史读取失败 → 本次对话无历史注入
            addDegradation(degradations, degradedCodes, "historyFailed", "会话历史读取失败，本次无多轮记忆");
            return new HistoryBundle("", "", 0, 0, 0);
        }
        boolean compressOn = configService.getBoolean("context.historyCompress");
        double ratio = Math.max(0.1, Math.min(0.9, configService.getDouble("context.compressRatio")));
        int threshold = Math.max(1000, (int) (budget * ratio));
        int summaryTokens = TokenCounter.estimate(summary);
        int compressedTurns = 0;

        if (compressOn && backlog.size() > HISTORY_MIN_KEEP) {
            int totalTokens = summaryTokens;
            for (Map<String, Object> m : backlog) totalTokens += TokenCounter.estimate(historyLine(m));
            if (totalTokens > threshold) {
                // 近期原样保留到「阈值 − 摘要」装不下为止，更早的（连同旧摘要）滚动并入新摘要
                int keepFrom = fitFromNewest(backlog, Math.max(200, threshold - summaryTokens));
                if (keepFrom > 0) {
                    List<Map<String, Object>> older = backlog.subList(0, keepFrom);
                    try {
                        // 自动压缩跑在回答链路里：超时用紧阈值（等不起），与手动 /compact 的宽等待分开
                        String merged = summarizeHistory(sessionId, resolvedModel, summary, historyText(older), null, null);
                        if (merged != null && !merged.isBlank()) {
                            long newUntil = seqOf(older.get(older.size() - 1));
                            sessionService.updateHistorySummary(sessionId, merged, newUntil);
                            summary = merged;
                            summaryTokens = TokenCounter.estimate(summary);
                            backlog = backlog.subList(keepFrom, backlog.size());
                            compressedTurns = Math.max(1, older.size() / 2);
                            log.info("[CTX] 历史压缩完成: {} 条并入摘要（until={}，摘要 {} token，保留近期 {} 条）",
                                    older.size(), newUntil, summaryTokens, backlog.size());
                        }
                    } catch (Exception e) {
                        // 压缩失败不能静默：用户会看到「早期对话消失」却不知原因。绕过检索调试开关直接上报。
                        degradations.add(Map.of("code", "historyCompressFailed", "level", "warn",
                                "msg", "早期对话压缩失败，本轮仅带入近期历史（下轮自动重试）"));
                        log.warn("[CTX] 历史压缩失败，回落「按预算装近期」: {}", e.getMessage());
                    }
                }
            }
        }
        // 近期原样：从最新往回装到「阈值 − 摘要」为止（压缩关闭/失败时即「按预算装近期、更早丢弃」）
        int recentBudget = Math.max(200, threshold - summaryTokens);
        int from = fitFromNewest(backlog, recentBudget);
        String recentText = historyText(backlog.subList(from, backlog.size()));
        return new HistoryBundle(summary, recentText, summaryTokens, TokenCounter.estimate(recentText), compressedTurns);
    }

    /**
     * 手动压缩会话上下文（/compact 斜杠命令）。
     * <p>
     * 与自动压缩的差别：自动压缩在「摘要 + 积压 &gt; 检索预算 × compressRatio」时才动、且只压到阈值内；
     * 用户显式发起时不等阈值，也不看 {@code context.historyCompress} 开关——该开关只管"要不要自动压"，
     * 而摘要注入本身与它无关（关掉自动压缩的人照样会带摘要），所以手动压缩始终可用。
     * 压缩目标明确：除最近 {@link #HISTORY_MIN_KEEP} 条原样保留外，其余全部并入会话摘要。
     * <p>
     * 长会话分多批：每批从最旧往新取到 {@link #COMPACT_CHUNK_TOKENS} 为止，先并入旧摘要再落库
     * （下一批复用刚落库的摘要，边压边持久化——中途失败时已压部分不丢）；最多 {@link #COMPACT_MAX_ROUNDS} 批，
     * 到上限仍有积压则回传 {@code partial=true}，由前端提示可再次执行。历史读取失败/首批压缩失败会抛异常
     * （会话未被改动，fail-loud），已压了一部分之后再失败则返回已完成的部分。
     *
     * @param resolvedModel 摘要调用所用模型（调用方已解析；空值由调用方拦在前面）
     * @param instruction   用户附加要求（可空）：希望这次摘要保留什么、忽略什么
     * @return compressedTurns / compressedMessages / keepRecent / summaryTokens / summary / rounds / partial
     */
    public Map<String, Object> compactSession(String sessionId, String resolvedModel, String instruction) {
        Session session = sessionService.sessionById(sessionId);
        String summary = (session == null || session.getHistorySummary() == null) ? "" : session.getHistorySummary();
        long untilSeq = (session == null || session.getSummaryUntilSeq() == null) ? 0L : session.getSummaryUntilSeq();
        int compressedMessages = 0;
        int rounds = 0;
        boolean partial = false;
        long batchTimeout = Math.max(configService.getLong("context.compressTimeoutMs", 20000L),
                COMPACT_TIMEOUT_FLOOR_MS);
        while (rounds < COMPACT_MAX_ROUNDS) {
            List<Map<String, Object>> backlog = sessionService.getHistoryAfter(sessionId, untilSeq, HISTORY_SCAN_LIMIT);
            if (backlog == null) {
                if (compressedMessages == 0) throw new com.wenqu.ai.common.BizException("会话历史读取失败，请稍后重试");
                partial = true;
                break;
            }
            int keepFrom = backlog.size() - HISTORY_MIN_KEEP;
            if (keepFrom <= 0) break;   // 只剩近期原样：压完了
            List<Map<String, Object>> older = backlog.subList(0, compactChunkSize(backlog, keepFrom));
            long newUntil = seqOf(older.get(older.size() - 1));
            // 序号没推进（消息缺 sequence）：宁可停手也不做死循环，已压的部分照常返回
            if (newUntil <= untilSeq) {
                partial = true;
                break;
            }
            String merged;
            try {
                merged = summarizeHistory(sessionId, resolvedModel, summary, historyText(older), instruction, batchTimeout);
            } catch (Exception e) {
                if (compressedMessages == 0) {
                    log.warn("[CTX] 手动压缩失败（{} 条待压，会话未改动）: {}", older.size(), e.getMessage());
                    throw new com.wenqu.ai.common.BizException("压缩失败：" + compactFailReason(e) + "，会话未被改动");
                }
                log.warn("[CTX] 手动压缩中途失败（已并入 {} 条）: {}", compressedMessages, e.getMessage());
                partial = true;
                break;
            }
            if (merged == null || merged.isBlank()) {
                // 空摘要不能落库：会把旧摘要一起清掉，等于凭空丢历史。
                // 模型空回答多半是思考档吃掉了输出预算（maxTokens 计入推理 token）——留痕才查得动
                log.warn("[CTX] 手动压缩拿到空摘要（已并入 {} 条，本批 {} 条，模型 {}）",
                        compressedMessages, older.size(), resolvedModel);
                if (compressedMessages == 0) throw new com.wenqu.ai.common.BizException("模型未返回摘要内容，会话未被改动，请稍后重试");
                partial = true;
                break;
            }
            sessionService.updateHistorySummary(sessionId, merged, newUntil);
            summary = merged;
            untilSeq = newUntil;
            compressedMessages += older.size();
            rounds++;
            log.info("[CTX] 手动压缩第 {} 批：{} 条并入摘要（until={}，摘要 {} token）",
                    rounds, older.size(), newUntil, TokenCounter.estimate(summary));
        }
        if (!partial && rounds >= COMPACT_MAX_ROUNDS) {
            // 达到批次上限：确认是真的还有积压（而非刚好压完），否则用户会白看到一句"可再次执行"
            List<Map<String, Object>> rest = sessionService.getHistoryAfter(sessionId, untilSeq, HISTORY_MIN_KEEP + 1);
            partial = rest != null && rest.size() > HISTORY_MIN_KEEP;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compressedTurns", compressedMessages <= 0 ? 0 : Math.max(1, compressedMessages / 2));
        out.put("compressedMessages", compressedMessages);
        out.put("keepRecent", HISTORY_MIN_KEEP);
        out.put("summaryTokens", TokenCounter.estimate(summary));
        out.put("summary", summary);
        out.put("rounds", rounds);
        out.put("partial", partial);
        return out;
    }

    /** 手动压缩的失败原因：超时的 TimeoutException 消息为 null，直接拼 getMessage() 会给用户一句空话 */
    private String compactFailReason(Exception e) {
        if (e instanceof TimeoutException) return "模型响应超时";
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    /** 单批压缩条数：从最旧往新装，装到 {@link #COMPACT_CHUNK_TOKENS} 为止（至少 1 条——单条自身超上限也得压走） */
    private int compactChunkSize(List<Map<String, Object>> backlog, int limit) {
        int total = 0;
        int take = 0;
        while (take < limit) {
            int t = TokenCounter.estimate(historyLine(backlog.get(take)));
            if (take > 0 && total + t > COMPACT_CHUNK_TOKENS) break;
            total += t;
            take++;
        }
        return Math.max(1, take);
    }

    /**
     * 非问答轮（手动压缩 /compact）的生效模型解析：显式指定 &gt; 个人默认；返回空串表示未配置，
     * 由调用方 fail-loud 引导用户选模型（与问答轮同一解析顺序，避免两条路走进不同模型）
     */
    public String resolveChatModel(String userId, String modelOverride) {
        return resolveModel(modelOverride, loadPrefUser(userId));
    }

    /** 一条历史消息的注入文本（role: content，剥离 [图片N] 标记；全文注入，不再按字符硬截断） */
    private String historyLine(Map<String, Object> msg) {
        String role = String.valueOf(msg.getOrDefault("role", ""));
        String content = stripImageMarks(String.valueOf(msg.getOrDefault("content", ""))).trim();
        if (content.isEmpty()) return "";
        return role + ": " + content + "\n";
    }

    private String historyText(List<Map<String, Object>> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : msgs) sb.append(historyLine(m));
        return sb.toString();
    }

    /** 从最新往回挑选能装进预算的消息，返回起始下标；至少保留最新一条（单条超预算时不再往前扩） */
    private int fitFromNewest(List<Map<String, Object>> msgs, int budgetTokens) {
        int total = 0;
        int from = msgs.size();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            int t = TokenCounter.estimate(historyLine(msgs.get(i)));
            if (total > 0 && total + t > budgetTokens) break;
            total += t;
            from = i;
        }
        return from;
    }

    /** 消息的 sequence（压缩覆盖点推进用）；缺失时回落 0（不推进，下轮重试） */
    private long seqOf(Map<String, Object> msg) {
        Object seq = msg.get("sequence");
        if (seq instanceof Number n) return n.longValue();
        try {
            return seq == null ? 0L : Long.parseLong(String.valueOf(seq));
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 缓存命中 token 查阅（①强类型 native usage ②Map 方言字段③DeepSeek prompt_cache_hit_tokens）。
     * 实现统一放在 UsageAccumulator：台账与回答气泡共用一套方言解析，口径不会漂移；
     * 全部取不到返回 0——容量面板/台账的缓存列隐藏，不显示假数据。
     */
    private int extractCachedTokens(org.springframework.ai.chat.metadata.Usage usage) {
        return (int) com.wenqu.ai.util.UsageAccumulator.cachedTokensOf(usage);
    }

    /**
     * 分类用量按网关真实 prompt token 等比校准：估算器（TokenCounter 启发式）与真实 tokenizer 有偏差，
     * 直接用估算值会让「分类之和 ≠ 网关报的 prompt_tokens」，面板看起来自相矛盾。
     * 有真实值时整体等比缩放（保持各分类相对比例），无真实值时原样返回估算。
     */
    private Map<String, Integer> calibrateParts(Map<String, Integer> parts, int promptTokens, boolean hasReal) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (parts == null || parts.isEmpty()) return out;
        long sum = 0;
        for (Integer v : parts.values()) sum += (v == null ? 0 : v);
        if (!hasReal || sum <= 0 || promptTokens <= 0) {
            for (Map.Entry<String, Integer> e : parts.entrySet()) out.put(e.getKey(), e.getValue());
            return out;
        }
        double k = promptTokens / (double) sum;
        long acc = 0;
        Map.Entry<String, Integer> last = null;
        for (Map.Entry<String, Integer> e : parts.entrySet()) {
            int v = (int) Math.round(e.getValue() * k);
            out.put(e.getKey(), v);
            acc += v;
            last = e;
        }
        // 舍入误差归到最后一个分类，保证分类之和严格等于网关 prompt_tokens
        if (last != null) out.put(last.getKey(), (int) (last.getValue() + (promptTokens - acc)));
        return out;
    }

    /** 早期摘要注入段（与近期原样历史拼进 system 的「对话历史」段） */
    private String summaryBlock(String summary) {
        return (summary == null || summary.isBlank()) ? ""
                : "【早期对话摘要】（更早轮次已压缩，完整记录可在会话中回看）\n" + summary.trim() + "\n";
    }

    /**
     * 滚动历史摘要：把「已有摘要 + 本轮要压缩的更早轮次」合并成新摘要（用本轮生效模型，关思考、限输出）。
     * 摘要与会话绑定、与模型无关（切换模型可复用）；超时/失败抛异常由调用方回落。
     * 用量归属：压缩跑在专属线程池上，ThreadLocal/请求上下文都不可见——入口线程先取 uid，
     * 池内 hold 显式带上（台账按 UsageAttr 记账），finally 清理防线程复用污染。
     *
     * @param instruction 用户对本次压缩的额外要求（/compact 可带，自动压缩传 null）：想保住什么、忽略什么
     * @param timeoutOverrideMs 超时覆盖（毫秒，null=用 context.compressTimeoutMs）：手动压缩没有"卡在回答中间"
     *                          的问题，可以等更久——单批输出接近上限时耗时可到 20 秒上下，与自动路径的
     *                          紧阈值不是一回事
     */
    private String summarizeHistory(String sessionId, String resolvedModel, String oldSummary, String olderText,
                                    String instruction, Long timeoutOverrideMs) throws Exception {
        String prompt = "你是对话历史压缩器。把下面的对话记录压缩成简洁要点摘要，供后续回答继续参考：\n"
                + "1. 保留：用户的目标与诉求、已达成的结论与决定、出现的关键实体（人名/产品名/编号/数值/时间）、尚未解决的问题。\n"
                + "2. 丢弃：寒暄、重复表述、已被后续对话推翻的中间过程。\n"
                + "3. 用第三人称陈述（\"用户询问了…\"\"助手回答了…\"），按时间顺序条目化。\n"
                + "4. 只输出摘要正文，不要解释、不要加标题。\n"
                + (instruction == null || instruction.isBlank() ? ""
                        : "5. 用户对本次压缩的额外要求（优先满足，但不得与 1~4 冲突）：" + instruction.trim() + "\n")
                + "\n"
                + (oldSummary == null || oldSummary.isBlank() ? "" : "【已有摘要（更早的历史）】\n" + oldSummary + "\n\n")
                + "【本次要并入的对话】\n" + olderText;
        long timeoutMs = timeoutOverrideMs != null ? timeoutOverrideMs
                : configService.getLong("context.compressTimeoutMs", 20000L);
        // 摘要不需要推理：思考 token 与正文共享 max_tokens，默认开思考的模型会把预算全花在推理上、
        // 正文返回空（实测摘要 completion 恒等于上限而内容为空）。能关思考的方言下发关闭字段；
        // 关不掉的（恒思考 / 未识别方言）多留一份输出预算，别让摘要被推理挤没。
        Map<String, Object> thinkOff = modelRegistryService.reasoningOffBody(resolvedModel);
        int summaryMaxTokens = thinkOff.isEmpty() ? 2560 : 1024;
        String billingUid = com.wenqu.ai.util.RequestUser.uid();
        java.util.concurrent.Future<String> f = compressExecutor.submit(() -> {
            com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(
                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                    sessionId, null, "compress"));
            try {
                OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder()
                        .model(resolvedModel)
                        .temperature(0.2)
                        .maxTokens(summaryMaxTokens);
                if (!thinkOff.isEmpty()) opts.extraBody(thinkOff);
                return chatClient.prompt()
                        .system("你是对话历史压缩器，只输出要点摘要。")
                        .user(prompt)
                        .options(opts.build())
                        .call()
                        .content();
            } finally {
                com.wenqu.ai.util.UsageAttr.clear();
            }
        });
        return f.get(timeoutMs, TimeUnit.MILLISECONDS);
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
        // 1. 收集每个编号首次出现的"前文句子"（重复引用按首次判定；代码段里的 [数字] 是字面量，不参与判定）
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
    private DeepThinkResult runDeepThinking(String sessionId, String question, String imgNote,
                                            String attachmentText,
                                            SseEmitter emitter, String resolvedModel, String reasoningLevel) {
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
        // user = 原始问题 + 图片说明（仅"模型不支持读图"时有值）
        StringBuilder user = new StringBuilder(question);
        if (imgNote != null && !imgNote.isBlank()) {
            user.append(imgNote);
        }
        // 附件内容随思考上下文（附件是回答素材，思考阶段就应看到）
        if (attachmentText != null && !attachmentText.isBlank()) {
            user.append("\n\n用户上传了附件，内容如下（仅用于辅助思考）：\n").append(attachmentText);
        }

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(resolvedModel);
        // 思考模式下不发 temperature：主流网关在 thinking 开启时忽略 temperature/top_p 等采样参数
        // （DeepSeek/GLM/豆包/Claude/Qwen 官方文档均明确）——不报错也不生效，属静默失效。
        // 仅 thinkingMode=model（网关原生思考）适用；prompt 模式是提示词引导、思考走 content，
        // 采样参数照常生效。
        if (!("model".equals(thinkingMode) && enableThinking)) {
            optionsBuilder.temperature(configService.getDouble("chat.temperature"));
        }
        // qwen 思考模式 max_tokens 会导致空输出：默认不设，仅显式配置 >0 时才设
        if (maxThinkingTokens > 0) {
            optionsBuilder.maxTokens(maxThinkingTokens);
        }
        // thinkingMode=model：extraBody 透传 enable_thinking，思考从 reasoning_content 提取
        if ("model".equals(thinkingMode) && enableThinking) {
            optionsBuilder.extraBody(Map.of("enable_thinking", true));
        }
        // 思考强度档位（本轮请求级，null 时回落模型登记默认档位；thinkingMode=model 且开着思考时才下发）：
        // 按厂商方言映射成 reasoning_effort / thinking.budget_tokens / thinking_budget。
        // 与上面 enable_thinking 合并（extraBody 单次设置，后者覆盖前者——故在此合并成一个 map）。
        if ("model".equals(thinkingMode) && enableThinking) {
            String thinkLevel = reasoningLevel != null
                    ? reasoningLevel : modelRegistryService.defaultReasoningLevelOf(resolvedModel);
            if (thinkLevel != null) {
                // reasoning_effort 走原生 setter（extraBody 会导致同名字段序列化两次 → 网关 422）
                String effort = modelRegistryService.reasoningEffortValue(resolvedModel, thinkLevel, true);
                if (effort != null) {
                    optionsBuilder.reasoningEffort(effort);
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("enable_thinking", true);
                body.putAll(modelRegistryService.reasoningExtraBody(resolvedModel, thinkLevel, true));
                optionsBuilder.extraBody(body);
                log.info("[REASONING] 深度思考强度已下发: model={} level={} effort={} extra={}",
                        resolvedModel, thinkLevel, effort, body.keySet());
            }
        }

        StringBuilder thinking = new StringBuilder();
        // 用量归属：思考流是独立订阅，doFinally 落在网关 I/O 线程上，ThreadLocal 不可见——
        // 入口线程先取 uid，用 Reactor Context 带上（护栏截断/断连取消时，中断估算同样按此归属）
        String billingUid = com.wenqu.ai.util.RequestUser.uid();
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
                    .contextWrite(ctx -> com.wenqu.ai.util.UsageAttr.put(ctx,
                            com.wenqu.ai.util.UsageAttr.of(
                                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                                    sessionId, null, "think")))
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

    /** 会话轮级互斥标记：sessionId → 在跑。随轮进出（chat 入口/流水线 finally），进程重启即清空
     *  （与挂起恢复管道同为内存态，语义一致） */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> TURN_IN_FLIGHT =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 会话在跑轮次的流状态：sessionId → AnswerStreamState，供服务端「停止本轮」按会话定位。
     * <p>刻意不复用 {@link #TURN_IN_FLIGHT} 的布尔标记，也不靠 SSE 通道反查：要能叫停的轮次里，
     * 有一类通道早就断了（断线后台续跑）、还有一类正挂在等人裁决上（审批/提问/计划等待），
     * 这两种都不是「连接还在」能表达的。随轮进出（状态构造登记 / 流水线 finally 摘除），进程重启即清空。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, AnswerStreamState> TURN_STATES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 本轮身份把手：sessionId → (turnId, userId)，在 {@code chat()} 抢到轮级互斥的那一刻就建好。
     * <p>比 {@link #TURN_STATES} 早得多也必须早：计划卡、子智能体编排、检索统计这些事件发生在
     * {@link AnswerStreamState} 构造之前，只靠状态表归因会把同一轮的轨迹劈成「无 turn_id 的孤儿行」
     * 与「有 turn_id 的行」两截，回放时拼不回去。
     */
    private record TurnHandle(String turnId, String userId) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, TurnHandle> TURN_HANDLES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 会话事件账本收录的「结构性事件」白名单（其余 SSE 类型不落账本）。
     * <p>刻意不含 {@code done}：轮终态快照本就随助手消息落库（正文/时间线/工具列表/用量），
     * 账本再存一份只是把大 JSON 截断成半截文本，没有增量信息。也不含 token/thinking/process/
     * stage/image/plan_delta/keepalive 这类高频增量——它们会让账本行数淹没真正的执行轨迹。
     */
    private static final java.util.Set<String> LEDGER_SSE_TYPES = java.util.Set.of(
            "approval_required", "ask_user", "plan", "plan_approval", "plan_cancelled", "plan_superseded",
            "artifact", "subagent", "subagent_route", "agent_dispatched", "agent_delegated", "agent_bound",
            "retrieved", "usage", "error", "warn");

    /** 工具执行审批挂起项：approvalId → 等待用户批准（内存态；进程重启/刷新页面即失效，超时自动拒绝） */
    private record PendingApproval(String sessionId, String userId, String toolName,
                                   java.util.concurrent.CompletableFuture<Boolean> future) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, PendingApproval> PENDING_APPROVALS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * askUser 挂起项：askId → 等待用户作答的内存唤醒句柄。
     * <p>只有它负责「把答案送回还阻塞着的工具线程」；卡片本体（问题/时限/答案）以 c_ai_tool_approval
     * 为准，所以刷新页面或换设备后仍能查到并作答。进程重启后句柄消失（live=false），此时作答只入审计、
     * 无法送达模型，该轮按超时收尾。
     */
    private record PendingAsk(String sessionId, String userId,
                              java.util.concurrent.CompletableFuture<String> future) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, PendingAsk> PENDING_ASKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** askUser 单轮提问上限：防模型把「一问一答」变成刷屏循环；正常澄清 1~2 问足够 */
    private static final int MAX_ASKS_PER_TURN = 3;

    /** askUser 单张卡片承载的问题上限（一卡多问）：超出则整卡拒绝、让模型合并后重问。
     *  刻意不做「静默截断保留前 N」：截断会让模型拿到的答案数组比它自己传入的问题数短，
     *  下标对不上（模型会把第 N 个答案当成第 N+1 问的），且界面上会出现「有问无答」的残缺问。 */
    private static final int MAX_QUESTIONS_PER_CARD = 6;

    /** askUser 单题候选上限：卡面固定「3 个候选 + 1 行自由输入」共 4 行，超过就不像选择题而像阅读理解。
     *  模型给多了按前 N 个截断（第一项是推荐项，截掉的是次选），不整题丢弃——丢题会让答案数组与模型
     *  传入的问题数对不上，界面上还会出现「有问无答」的残缺问。 */
    private static final int MAX_ASK_OPTIONS = 3;

    /** askUser 提问卡等待上限（一卡多问需人工逐题作答，比工具审批宽）：默认 10 分钟，
     *  平台可调（chat.askTimeoutMs）。提问已落库且等待期间断线不中止本轮，所以窗口可以给到分钟级；
     *  前端倒计时取 SSE 下发的 timeoutMs、跨设备恢复取 DB 的 created_at + 本值，两处口径必须同源。 */
    private long askTimeoutMs() {
        long t = configService.getLong("chat.askTimeoutMs");
        return t > 0 ? t : 600000L;
    }

    // ==================== 计划模式（人在回路：先出执行计划，批准后按计划执行） ====================

    /** 计划批准挂起项：planApprovalId → 等待用户批准/退回重出（内存态；进程重启后句柄消失，超时按未批准收尾）。
     *  与工具审批/askUser 同一套挂起-恢复管道：内存 future 阻塞流水线线程 + DB 卡片记录 + SSE 卡 + 站内通知 */
    private record PendingPlan(String sessionId, String userId,
                               java.util.concurrent.CompletableFuture<PlanDecision> future) {
    }

    /** 单版计划的用户裁决：approve=批准（plan 为批准版文本，可能沿 API 带过用户改稿）；
     *  reject=取消本轮（plan/feedback 均为 null）；revise=退回让模型按 feedback 重出下一版；
     *  supersede=用户带着修改意见继续对话（本版作废，新一轮会产出修改后的计划） */
    private record PlanDecision(boolean approved, String plan, String reviseFeedback, boolean superseded) {
        static PlanDecision approve(String plan) { return new PlanDecision(true, plan, null, false); }
        static PlanDecision reject() { return new PlanDecision(false, null, null, false); }
        static PlanDecision revise(String feedback) { return new PlanDecision(false, null, feedback, false); }
        static PlanDecision supersede() { return new PlanDecision(false, null, null, true); }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, PendingPlan> PENDING_PLANS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 计划文本长度护栏：超限直接停收增量（计划是一份动作清单，写这么长一定是跑题了） */
    private static final int PLAN_MAX_CHARS = 6000;

    /** 计划阶段（单版）结果：plan=本版计划文本（生成失败为 null）；approved=true 表示用户已批准本版；
     *  reviseFeedback 非 null=用户要修改本版（gate 据此带意见重出下一版）；
     *  superseded=true=用户带着修改意见继续对话（本版作废，整轮收尾）；
     *  generateFailed=true=计划生成失败（已登记降级，调用方按普通模式继续）；
     *  三者皆否=拒绝/超时（整轮终止） */
    private record PlanPhaseOutcome(String plan, boolean approved, boolean generateFailed, String reviseFeedback,
                                    boolean superseded) {
        static PlanPhaseOutcome of(String plan, boolean approved, String reviseFeedback) {
            return new PlanPhaseOutcome(plan, approved, false, reviseFeedback, false);
        }
        static PlanPhaseOutcome failed() { return new PlanPhaseOutcome(null, false, true, null, false); }
        static PlanPhaseOutcome superseded(String plan) { return new PlanPhaseOutcome(plan, false, false, null, true); }
    }

    /** 计划生成流式超时（chat.planGenTimeoutMs，默认 120s；阻塞流水线线程，必须有界） */
    private long planGenTimeoutMs() {
        long t = configService.getLong("chat.planGenTimeoutMs");
        return t > 0 ? t : 120000L;
    }

    /** 计划批准等待上限（chat.planTimeoutMs，默认 10 分钟，与 askUser 提问卡同级的人工等待） */
    private long planTimeoutMs() {
        long t = configService.getLong("chat.planTimeoutMs");
        return t > 0 ? t : 600000L;
    }

    // ==================== 计划意图识别（按用户意图自动开启计划模式） ====================

    /** 意图识别专用线程池（与派遣路由同款：2 线程 + 超时 cancel(true) 中断底层调用；daemon 不阻 JVM 退出） */
    private static final java.util.concurrent.ThreadPoolExecutor PLAN_INTENT_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "plan-intent");
                t.setDaemon(true);
                return t;
            });

    /**
     * 启动计划意图识别（与图片/附件/检索并行跑，闸门前取结果）：用户在计划开关之外没提计划时，
     * 判断这条消息是否属于「值得先出执行计划再回答」的复杂任务（多步骤/有交付物/范围口径需确认）。
     * 与闸门同口径跳过不适用的轮次：游客、无工具模型（计划本就降级）、收集型通道（定时任务/MCP/子智能体
     * 没人能批准计划卡）、重新生成（用户要的是重答不是重出计划）。返回 null = 本轮不检测。
     */
    private java.util.concurrent.CompletableFuture<Boolean> startPlanIntentCheck(
            SseEmitter emitter, boolean guestMode, boolean planMode, boolean regenerate,
            String sessionId, String question, String model, boolean hasAttachments) {
        if (planMode || guestMode || regenerate || emitter instanceof CollectingSseEmitter) return null;
        if (!configService.getBoolean("chat.planAutoIntent", true)) return null;
        if (model == null || !modelRegistryService.toolCapableOf(model)) return null;
        String uid = com.wenqu.ai.util.RequestUser.uid();
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(
                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(uid) ? null : uid, null, null, "planIntent"));
            try {
                String history = "";
                try {
                    history = buildHistoryText(sessionService.getRecentHistory(sessionId, 2));
                } catch (Exception e) { /* 无上下文也能判 */ }
                String prompt = "你是「计划模式」判断器。判断这条用户消息是否适合先产出一份执行计划、"
                        + "经用户确认后再回答（计划模式＝先列出要做什么、分几步、每步用什么，用户批准后才动手）。\n"
                        + "\n适合：任务需要多步完成（检索、计算、生成、整理等多环节）；要产出交付物（报告/方案/文件）；"
                        + "范围、口径或方向需要用户拍板；做了不易回头。\n"
                        + "不适合：简单问答、闲聊、单个事实查询、翻译润色改写、一句话就能答完的。\n"
                        + (hasAttachments ? "（本条消息附带了文件或图片——通常意味着要把材料加工成某个产物，倾向适合。）\n" : "")
                        + (history == null || history.isBlank() ? ""
                        : "最近对话（辅助理解意图，如「继续」「改成 X」这类指代）：\n" + history + "\n")
                        + "\n用户消息：" + question
                        + "\n\n只回答 YES 或 NO，不要输出任何其他内容。";
                // 与计划轮同口径的思考档处理：能关思考的方言下发关闭字段（思考与正文共享 max_tokens，
                // 恒思考模型会把预算花在推理上、判定正文被挤空）；关不掉的多留一份输出预算
                Map<String, Object> thinkOff = modelRegistryService.reasoningOffBody(model);
                org.springframework.ai.openai.OpenAiChatOptions.Builder opts =
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .model(model)
                                .temperature(0.0)
                                .maxTokens(thinkOff.isEmpty() ? 3072 : 128)
                                .internalToolExecutionEnabled(false);
                if (!thinkOff.isEmpty()) opts.extraBody(thinkOff);
                String out = chatClient.prompt()
                        .user(prompt)
                        .options(opts.build())
                        .call()
                        .content();
                boolean yes = out != null && out.trim().toUpperCase(java.util.Locale.ROOT).startsWith("YES");
                log.info("[PLAN] 计划意图识别：{}（{} 字消息）", yes ? "适合" : "不适合", question.length());
                return yes;
            } catch (Exception e) {
                log.info("[PLAN] 计划意图识别失败，本轮不自动开计划: {}", e.getMessage());
                return false;
            } finally {
                com.wenqu.ai.util.UsageAttr.clear();
            }
        }, PLAN_INTENT_EXECUTOR);
    }

    /** 取意图识别结果（闸门前调用）：没跑完最多再等 1.5 秒——自动开启宁缺毋滥，不拖慢回答 */
    private boolean awaitPlanIntent(java.util.concurrent.CompletableFuture<Boolean> f) {
        if (f == null) return false;
        try {
            return Boolean.TRUE.equals(f.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            log.info("[PLAN] 计划意图识别超时，本轮不自动开计划");
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计划模式闸门（runChat 主链路与无知识库分支共用）：装配本轮工具集 → 无工具/游客降级 →
     * 产出执行计划 → 挂起等用户裁决。批准后的计划由调用方注入执行轮 system。
     * <p>裁决含「退回重出」：用户不满意可带修改意见退回，模型在同一轮内重出下一版（v2/v3…），
     * 反复直到确认/取消/超时。每版落一条批准记录（REVISED=被用户意见取代），版本链随本轮计划快照落库。
     *
     * @return null=本轮应就此终止（用户拒绝/超时发 plan_cancelled、或「继续对话」取代发 plan_superseded，
     *         由调用方按 {@link AnswerStreamState#planSuperseded} 分流收尾）；
     *         ""=降级为普通回答（无工具/游客/生成失败，降级提示已登记，不注入计划）；
     *         非 null=批准后的计划文本（调用方追加进 system）
     */
    private String planApprovalGate(AnswerStreamState st, Agent agent, String rolePart, String planQuestion,
                                    List<String> skills, String refsDigest, StringBuilder system) {
        if (st.guestMode) {
            addDegradation(st.degradations, st.degradedCodes, "planGuestUnsupported",
                    "游客会话不支持计划模式，本轮按普通模式回答");
            return "";
        }
        // 计划门控的对象是工具执行：模型不支持 Function Calling 时工具本来就下不去，计划没有可门控的执行，
        // 直接降级（不缓存工具集，让 buildAnswerStream 的同款检查走它自己的标准降级提示）
        if (!modelRegistryService.toolCapableOf(st.model)) {
            addDegradation(st.degradations, st.degradedCodes, "planNoTools",
                    "当前模型不支持工具调用（Function Calling），计划模式未生效，已按普通模式回答");
            return "";
        }
        // 计划轮自己不执行任何工具，但计划必须基于「本轮真实可用的能力」来写——先按与生成轮完全
        // 相同的口径装配一次工具集，既给计划当能力清单，也缓存给 buildAnswerStream 复用（MCP 连接不二次建立）
        java.util.List<org.springframework.ai.tool.ToolCallback> rawCbs = enabledToolCallbacks(agent, st.userId, st);
        st.enabledCallbacks = rawCbs;
        if (rawCbs.isEmpty()) {
            addDegradation(st.degradations, st.degradedCodes, "planNoTools",
                    "本轮没有启用的工具可执行，计划模式未生效，已按普通模式回答");
            return "";
        }
        // 版本链 [{plan, feedback}]：feedback=产出该版的用户意见（v1 为空串）
        List<Map<String, Object>> versions = new java.util.ArrayList<>();
        String feedback = null;   // 本版应遵循的用户意见（v1 为 null）
        while (true) {
            boolean first = versions.isEmpty() && feedback == null;
            sendSseEvent(st.emitter, "stage",
                    first ? "正在制定计划…" : "正在按你的意见修改计划…", st.sessionId);
            PlanPhaseOutcome po = runPlanPhase(st, rolePart, planQuestion, skills, rawCbs, refsDigest,
                    feedback, versions, first);
            if (po.reviseFeedback() != null) {
                // 用户退回本版：记账后带着意见重出下一版（站内通知只在首版发，修订不再提醒）
                versions.add(planVersionEntry(po.plan(), feedback));
                feedback = po.reviseFeedback();
                continue;
            }
            if (po.superseded()) {
                // 用户带着修改意见继续对话：本版作废（不是「未批准」——内容没被否定，是提出了修改）。
                // 快照落 superseded，收尾（收尾助手消息 + plan_superseded 事件）见调用方
                versions.add(planVersionEntry(po.plan(), feedback));
                st.planRecord = planSnapshot(versions, "superseded", st.planAuto);
                st.planSuperseded = true;
                return null;
            }
            if (po.generateFailed()) {
                // 修订轮生成失败：已有一版等待批准的计划，不能悄悄放弃用户诉求转普通回答——
                // 也没有可裁决的卡可挂（旧卡已按 REVISED 收口），登记降级后按停止本轮收尾
                if (!versions.isEmpty()) {
                    st.planRecord = planSnapshot(versions, "rejected", st.planAuto);
                    addDegradation(st.degradations, st.degradedCodes, "planReviseFailed",
                            "按你的意见重出计划失败，本轮已停止（可重新发送）");
                    return null;
                }
                return "";
            }
            versions.add(planVersionEntry(po.plan(), feedback));
            if (po.approved()) {
                st.planRecord = planSnapshot(versions, "approved", st.planAuto);
                String block = approvedPlanBlock(po.plan());
                system.append(block);
                // 容量计量：计划块与技能/摘要一样是注入段，并入 other 桶（done 的用量校准同源）
                st.ctxParts.merge("other", TokenCounter.estimate(block), Integer::sum);
                return po.plan();
            }
            // 拒绝/超时：整轮终止（快照含各版计划，历史里这轮的「未批准」卡照常带修改记录）
            st.planRecord = planSnapshot(versions, "rejected", st.planAuto);
            return null;
        }
    }

    /** 版本链条目：feedback=产出该版的用户意见（v1 为空串，前端按「你的意见」插在两版之间展示） */
    private Map<String, Object> planVersionEntry(String plan, String feedback) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("plan", plan == null ? "" : plan);
        v.put("feedback", feedback == null ? "" : feedback);
        return v;
    }

    /** 本轮计划快照（助手消息 plan 列）：plan=末版正文（卡片主体展示），versions=完整版本链（修改记录）；
     *  auto=true=该计划按消息意图自动开启（用户没开计划开关），历史回显时卡片照常标「自动开启」 */
    private Map<String, Object> planSnapshot(List<Map<String, Object>> versions, String status, boolean auto) {
        Map<String, Object> rec = new LinkedHashMap<>();
        Map<String, Object> last = versions.get(versions.size() - 1);
        rec.put("plan", last.get("plan"));
        rec.put("status", status);
        rec.put("versions", new java.util.ArrayList<>(versions));
        if (auto) rec.put("auto", true);
        return rec;
    }

    /** 批准后的计划 → 执行轮 system 注入块 */
    private String approvedPlanBlock(String plan) {
        return "\n\n【已批准的执行计划】用户已审阅并批准以下执行计划，请按计划逐步执行；"
                + "执行中发现计划不可行时可作必要偏离，但需在回答中说明原因：\n" + plan;
    }

    /**
     * 已检索资料清单（计划模式的计划输入）：只给标题与来源文档名——计划关心「有什么可依据」，
     * 不需要正文本身；全文在执行轮照常注入，计划调用保持轻量（标题清单 vs 全文差一个数量级的输入）。
     */
    private String buildRefsDigest(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map<String, Object> s : sources) {
            if (n >= 10) {
                sb.append("…等共 ").append(sources.size()).append(" 段\n");
                break;
            }
            Object title = s.get("title");
            sb.append("- ").append(title == null || String.valueOf(title).isBlank()
                    ? "（无标题）" : String.valueOf(title));
            Object fileName = s.get("fileName");
            if (fileName != null && !String.valueOf(fileName).isBlank()) {
                sb.append("（").append(fileName).append("）");
            }
            sb.append("\n");
            n++;
        }
        return sb.toString().trim();
    }

    /**
     * 计划阶段（单版）执行体：一次无工具的流式模型调用产出计划文本（plan_delta 逐段下发，用户即时看到计划成形），
     * 流毕落库计划批准卡（c_ai_tool_approval，tool_name=planApproval）并阻塞等待用户裁决。
     * <p>等待语义与 askUser 相同：人工等待不计入整轮机器预算（beginHumanWait）；等待期间通道失效不中止
     * 本轮（卡片已落库，用户刷新/换设备后仍可批准，回答转后台续跑照常落库）；超时按未批准收尾。
     * <p>计划生成本身失败/超时/产出为空：不挂卡，登记降级后按普通模式继续——计划模式不能把本轮卡死。
     *
     * @param userFeedback 非 null=本版是用户退回后的修订版，按该意见重出（v1 为 null）
     * @param versions     已产的版本链（修订版作为上一版上下文喂给模型）
     * @param notify       是否发「执行计划待确认」站内通知（仅首版发，修订版用户就在会话里）
     */
    private PlanPhaseOutcome runPlanPhase(AnswerStreamState st, String rolePart, String planQuestion,
                                          List<String> skills,
                                          java.util.List<org.springframework.ai.tool.ToolCallback> callbacks,
                                          String refsDigest, String userFeedback,
                                          List<Map<String, Object>> versions, boolean notify) {
        // ---- 1. 计划 prompt：角色段 + 计划指令 + 能力清单（与生成轮同口径装配的回调名与描述）----
        StringBuilder ps = new StringBuilder(rolePart == null ? "" : rolePart)
                .append("\n\n【计划模式】用户为本轮开启了计划模式：请先产出一份执行计划供用户确认，暂时不要开始回答。\n")
                .append("计划要求：\n")
                .append("1. 这是给用户审阅的「结果计划」：用 Markdown 有序列表说明成品将做成什么样——最终交付物是什么形态、"
                        + "包含哪些部分、关键选择是什么（结构、内容范围、风格基调、呈现方式等），"
                        + "用户看完就能判断是不是他要的东西；也可以说明会怎么让他看到它（如先给一版可预览的原型）；\n")
                .append("2. 只写用户关心的结果：不写工具名、能力名、内部做法与执行细节；\n")
                .append("3. 关键要素在出计划前已向用户确认过（若发生过提问，答案见「用户已确认的关键要素」）："
                        + "不要再写「先与你确认…」这类步骤；仍有拿不准的细节按你的最佳判断定，"
                        + "并在计划里点明这是你的选择，用户可以直接改；\n")
                .append("4. 只能计划用下面列出的能力去做，不要出现清单之外的动作（清单仅供你核对可行性，名字不要写进计划）：\n");
        int listed = 0;
        for (org.springframework.ai.tool.ToolCallback cb : callbacks) {
            if (listed >= 15) {
                ps.append("   - …等共 ").append(callbacks.size()).append(" 个工具\n");
                break;
            }
            var def = cb.getToolDefinition();
            if (def == null) continue;
            String desc = def.description() == null ? "" : def.description();
            if (desc.length() > 80) desc = desc.substring(0, 80) + "…";
            ps.append("   - ").append(def.name()).append("：").append(desc).append("\n");
            listed++;
        }
        ps.append("5. 事情简单或资料已足够时，计划可以只有一条（例如：直接产出结果）；\n")
                .append("6. 只输出计划本身（Markdown 列表），不要输出前言、解释或计划以外的任何内容，总长度控制在 10 行以内。");
        StringBuilder pu = new StringBuilder(planQuestion == null ? "" : planQuestion);
        if (skills != null && !skills.isEmpty()) {
            pu.append("\n\n（用户本轮指定了技能：").append(String.join("、", skills))
                    .append("——其全文将在执行阶段注入，计划里可以提到按技能执行）");
        }
        if (refsDigest != null && !refsDigest.isBlank()) {
            pu.append("\n\n【已检索资料清单】本轮已检索到以下参考资料（仅标题，执行阶段提供全文）：\n").append(refsDigest);
        }
        String historyText = buildHistoryText(sessionService.getRecentHistory(st.sessionId, 2));
        if (!historyText.isEmpty()) {
            pu.append("\n\n对话历史：\n").append(historyText);
        }
        // 修订版（用户在等批界面退回并给了意见）：上一版计划 + 修改意见一起喂给模型重出——
        // 用户也可能直接改写了计划全文，此时意见即改稿，按改稿重出（只做必要澄清，别丢用户的改动）
        if (userFeedback != null) {
            String prevPlan = "";
            for (int i = versions.size() - 1; i >= 0; i--) {
                Object p = versions.get(i).get("plan");
                if (p != null && !String.valueOf(p).isBlank()) { prevPlan = String.valueOf(p); break; }
            }
            ps.append("\n\n【修订轮】本次不是首版计划：用户已看过上一版并提出了修改意见，"
                    + "请按意见产出新的完整计划（仍是 Markdown 列表、10 行以内、只输出计划本身）；"
                    + "与用户意见无关的部分保持原样，不要借机重写。");
            pu.append("\n\n【上一版计划（用户要修改它）】\n").append(prevPlan)
                    .append("\n\n【用户的修改意见】\n").append(userFeedback)
                    .append("\n\n请产出修改后的新一版计划。");
        } else {
            // 「继续对话」轮（上一版计划被用户带着意见取代）：本轮问题就是对上一版提的修改要求，
            // 把上一版计划带上，产出"修改后的计划"而不是从零重来
            String prevSuperseded = findSupersededPlan(st.sessionId);
            if (prevSuperseded != null) {
                ps.append("\n\n【继续对话】用户对上一版执行计划提出了修改要求（见下方的用户消息），"
                        + "请在他要改的基础上产出修改后的完整计划（仍是 Markdown 列表、10 行以内、只输出计划本身）；"
                        + "与他意见无关的部分保持原样，不要借机重写。");
                pu.append("\n\n【上一版计划（用户要改的是它）】\n").append(prevSuperseded);
            }
        }
        // ---- 1.5 计划前的要素澄清（每轮计划的首版前问一次，同轮修订版不问）：模型拿不准才弹提问卡，
        //          答完把确认结果并入计划依据——「先问 → 答 → 再出计划」的第一步；拿得准走 READY 不打扰。
        //          收集型通道没人能作答（定时任务/MCP/子智能体），跳过。
        // lambda 中引用需 effectively final，用容器承接
        java.util.concurrent.atomic.AtomicBoolean clarifyAsked = new java.util.concurrent.atomic.AtomicBoolean();
        if (versions.isEmpty() && userFeedback == null && !(st.emitter instanceof CollectingSseEmitter)) {
            java.util.List<BuiltinTools.AskQuestion> clarifyQs = planClarifyQuestions(st, rolePart, pu.toString());
            if (!clarifyQs.isEmpty()) {
                clarifyAsked.set(true);
                if (!st.detached) sendSseEvent(st.emitter, "stage", "正在确认关键要素…", st.sessionId);
                // 提问卡走与执行期 askUser 完全相同的记录管道：start（时间线占位，前端对 askUser 的
                // start 不渲染）+ 终态（前端据此撤下提问面板、问答记录卡落进 toolCalls 随消息持久化）。
                // 不记录的话「提问面板撤下」永远不会触发（面板只在 askUser 工具终态或整轮结束时撤），
                // 而计划轮要挂着等用户批准 ⇒ 提问面板会一直挡住计划卡与确认栏。
                String askArgs = JSON.toJSONString(Map.of("questions", clarifyQs.stream().map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("topic", q.topic());
                    m.put("question", q.question());
                    m.put("options", q.options());
                    return m;
                }).toList()));
                long askStartAt = System.currentTimeMillis();
                recordToolStatus(st, "askUser", askArgs, "start", null, 0, 1);
                String clarified = doAskUserMulti(st, clarifyQs);
                recordToolStatus(st, "askUser", askArgs, "done", clarified,
                        System.currentTimeMillis() - askStartAt, 1);
                String qa = formatClarifyAnswers(clarifyQs, clarified);
                if (!qa.isBlank()) {
                    pu.append("\n\n【用户已确认的关键要素】\n").append(qa);
                }
                pu.append("\n\n（未作答或未问到的关键点：按你的最佳判断给默认，并在计划里点明该处，用户可以直接改。）");
                if (!st.detached) sendSseEvent(st.emitter, "stage", "正在制定计划…", st.sessionId);
            }
        }
        // ---- 2. 调用选项：辅助调用口径——能关思考的方言下发关闭字段（思考与正文共享 max_tokens，
        //         默认开思考的模型会把预算花在推理上、计划正文被挤空）；关不掉的多留一份输出预算。
        //         计划要短，max_tokens 收紧到声明值以内。 ----
        Map<String, Object> thinkOff = modelRegistryService.reasoningOffBody(st.model);
        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder()
                .model(st.model)
                .temperature(configService.getDouble("chat.temperature"))
                .maxTokens(thinkOff.isEmpty() ? 3072 : 1536);
        if (!thinkOff.isEmpty()) opts.extraBody(thinkOff);
        if (st.effectiveMaxOutput > 0 && st.effectiveMaxOutput < 3072) {
            opts.maxTokens(st.effectiveMaxOutput);
        }
        // ---- 3. 流式生成：增量走 plan_delta（复用整轮流级心跳/看门狗）----
        // 通道失效时分两类：本轮刚问过用户（用户在等待期断线/换了设备，答案已送到）或正等人作答/已转后台
        // 续跑 ⇒ 只丢事件、继续产出并挂卡（卡片落库，从「待批准计划」恢复）；其余照旧「断开即止损」中止本轮。
        // 注意不能只靠 keepRunningWithoutChannel：答案送达时 askWaits 已归零，而断线往往到第一次发送
        // 失败才被发现（等待期没有写入事件）——这个竞态窗口靠 clarifyAsked 兜住。
        StringBuilder planText = new StringBuilder();
        String billingUid = com.wenqu.ai.util.RequestUser.uid();
        try {
            chatClient.prompt()
                    .system(ps.toString())
                    .user(pu.toString())
                    .options(opts.build())
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        String delta = "";
                        if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                            String text = resp.getResult().getOutput().getText();
                            delta = text == null ? "" : text;
                        }
                        if (!delta.isEmpty() && planText.length() <= PLAN_MAX_CHARS) {
                            planText.append(delta);
                            if (!st.detached && !sendSseEvent(st.emitter, "plan_delta", delta, st.sessionId)) {
                                if (clarifyAsked.get() || st.keepRunningWithoutChannel()) {
                                    st.detached = true;   // 转后台续跑：此后只积累不发送
                                } else {
                                    throw new SseClientGoneException();
                                }
                            }
                        }
                    })
                    .contextWrite(ctx -> com.wenqu.ai.util.UsageAttr.put(ctx,
                            com.wenqu.ai.util.UsageAttr.of(
                                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                                    st.sessionId, null, "chat")))
                    .blockLast(Duration.ofMillis(planGenTimeoutMs()));
        } catch (SseClientGoneException e) {
            log.info("[PLAN] 客户端断开，计划流终止: session={}", st.sessionId);
            return PlanPhaseOutcome.failed();
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 计划生成失败/超时，本轮降级为普通回答: session={} {}", st.sessionId, e.getMessage());
            addDegradation(st.degradations, st.degradedCodes, "planGenFailed",
                    "计划生成失败，本轮已按普通模式继续");
            return PlanPhaseOutcome.failed();
        }
        String draft = stripPlanNoise(planText.toString());
        if (draft.isBlank()) {
            log.warn("[FAIL-LOUD] 计划生成为空（正文 0 token），本轮降级为普通回答: session={}", st.sessionId);
            addDegradation(st.degradations, st.degradedCodes, "planGenFailed",
                    "计划生成为空，本轮已按普通模式继续");
            return PlanPhaseOutcome.failed();
        }
        // ---- 4. 挂起等批准：落库 + 内存句柄 + 站内通知 + SSE 卡（与 askUser 同一管道）----
        long timeout = planTimeoutMs();
        String planApprovalId = java.util.UUID.randomUUID().toString();
        java.util.concurrent.CompletableFuture<PlanDecision> future = new java.util.concurrent.CompletableFuture<>();
        try {
            com.wenqu.ai.model.ToolApproval rec = new com.wenqu.ai.model.ToolApproval();
            rec.setId(planApprovalId);
            rec.setSessionId(st.sessionId);
            rec.setUserId(st.userId);
            rec.setToolName("planApproval");
            rec.setStatus("PENDING");
            rec.setRequestArgs(JSON.toJSONString(java.util.Map.of("plan", draft)));
            rec.setCreatedAt(java.time.LocalDateTime.now());
            toolApprovalMapper.insert(rec);
        } catch (Exception e) {
            log.warn("[PLAN] 计划记录落库失败（不阻塞计划流程）: {}", e.getMessage());
        }
        PENDING_PLANS.put(planApprovalId, new PendingPlan(st.sessionId, st.userId, future));
        if (notify) {
            try {
                notificationService.create(st.userId, com.wenqu.ai.model.Notification.TYPE_TOOL_APPROVAL,
                        "执行计划待确认",
                        "模型为本轮产出了执行计划，请在会话中批准或修改后执行（超时未确认本轮将停止）。",
                        "session", st.sessionId, "tool:" + planApprovalId, planApprovalId);
            } catch (Exception e) {
                log.warn("[PLAN] 计划通知写入失败（不阻塞计划流程）: {}", e.getMessage());
            }
        }
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("planApprovalId", planApprovalId);
            req.put("plan", draft);
            req.put("timeoutMs", timeout);
            if (st.planAuto) req.put("auto", true);   // 按消息意图自动开启：卡片标「自动开启」
            // 卡片下发失败（连接已断）：本轮转后台续跑——卡片已落库，用户刷新/换设备后仍可批准，
            // 批准后的回答照常生成并落库（与 askUser 同语义）
            if (!sendSseEvent(st.emitter, "plan_approval", JSON.toJSONString(req), st.sessionId)) {
                st.detached = true;
                log.info("[PLAN] 计划卡通道已失效，本轮转后台续跑: id={} session={}", planApprovalId, st.sessionId);
            }
            log.info("[PLAN] 等待用户确认执行计划: id={} session={} 窗口={}ms 计划 {} 字",
                    planApprovalId, st.sessionId, timeout, draft.length());
            PlanDecision decision;
            st.planWaits.incrementAndGet();
            beginHumanWait(st.emitter);   // 用户裁决时间不计入整轮机器预算（见 TurnDeadline）
            try {
                decision = future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                markApprovalResolved(planApprovalId, "TIMEOUT", st.userId);
                decision = null;
                log.warn("[PLAN] 计划批准超时，按未批准收尾: id={} session={}", planApprovalId, st.sessionId);
            } catch (java.util.concurrent.ExecutionException ee) {
                markApprovalResolved(planApprovalId, "TIMEOUT", st.userId);
                decision = null;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                markApprovalResolved(planApprovalId, "TIMEOUT", st.userId);
                decision = null;
            } finally {
                st.planWaits.decrementAndGet();
                endHumanWait(st.emitter);
            }
            // 本版结果交回闸门（版本链快照由闸门统一组装，这里不再落 st.planRecord）
            if (decision == null) return PlanPhaseOutcome.of(draft, false, null);                    // 超时=未批准
            if (decision.reviseFeedback() != null) return PlanPhaseOutcome.of(draft, false, decision.reviseFeedback()); // 退回重出
            if (decision.superseded()) return PlanPhaseOutcome.superseded(draft);                    // 继续对话：本版作废
            if (!decision.approved()) return PlanPhaseOutcome.of(draft, false, null);                // 拒绝
            String finalPlan = decision.plan() == null || decision.plan().isBlank() ? draft : decision.plan();
            return PlanPhaseOutcome.of(finalPlan, true, null);                                       // 批准
        } finally {
            PENDING_PLANS.remove(planApprovalId);
        }
    }

    /**
     * 计划前的要素澄清（首版计划前的一次小判定）：判断是否有「无法从资料与对话推断、必须由用户拍板」的
     * 关键要素——有就先走提问卡问清、答完再出计划（「先问 → 答 → 再出计划」）；模型拿得准则输出 READY，
     * 不打扰用户、直接出计划。上下文与计划轮同源：同一段角色提示词（判定者先知道自己是谁、产品是什么，
     * 才不会有「你是做什么的」这类本可自答的问题）+ 同一段 user 文本；失败/超时/输出不可解析一律当
     * READY——澄清是加分项，不能把计划轮卡住。
     */
    private java.util.List<BuiltinTools.AskQuestion> planClarifyQuestions(AnswerStreamState st, String rolePart,
                                                                          String planContext) {
        String instructions = (rolePart == null || rolePart.isBlank() ? "" : rolePart + "\n\n")
                + "你是「计划前澄清」判断器：用户开启了计划模式，你即将给他一份「成品将做成什么样」的计划。"
                + "先判断：有哪些关键要素，是「不确认就很可能做错方向」、又无法从用户消息 / 已检索资料 / "
                + "对话历史 / 上一版计划里得到的？\n"
                + "- 逐个自问：用户消息里已经给了吗？能从资料/历史里看出来吗？能先按默认做、在计划里标出让用户改吗？"
                + "只要占一条，就不要问它；\n"
                + "- 只问真正卡方向的关键取舍（面向谁、范围取舍、风格基调、交付形态这类），通常 1~2 个，最多 3 个；"
                + "能不问就不问；全都能定就输出 READY；\n"
                + "- 每个问题一句话交代背景与要决定的事；给 2~3 个具体候选（第一项是你推荐的），用户也可自由输入；\n"
                + "- 只输出下面两种内容之一，不要任何解释：\n"
                + "  无需提问：READY\n"
                + "  需要提问：{\"questions\":[{\"topic\":\"话题\",\"question\":\"……\",\"options\":[\"……\",\"……\"]}]}";
        // 与计划轮同口径的思考档处理：能关的关掉；这是小判定，生成封顶 20s，超时当 READY
        Map<String, Object> thinkOff = modelRegistryService.reasoningOffBody(st.model);
        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder()
                .model(st.model)
                .temperature(0.0)
                .maxTokens(thinkOff.isEmpty() ? 3072 : 1536)
                .internalToolExecutionEnabled(false);
        if (!thinkOff.isEmpty()) opts.extraBody(thinkOff);
        StringBuilder out = new StringBuilder();
        String billingUid = com.wenqu.ai.util.RequestUser.uid();
        try {
            chatClient.prompt()
                    .system(instructions)
                    .user(planContext)
                    .options(opts.build())
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        if (resp.getResult() != null && resp.getResult().getOutput() != null
                                && out.length() < 8000) {
                            String t = resp.getResult().getOutput().getText();
                            if (t != null) out.append(t);
                        }
                    })
                    .contextWrite(ctx -> com.wenqu.ai.util.UsageAttr.put(ctx,
                            com.wenqu.ai.util.UsageAttr.of(
                                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                                    st.sessionId, null, "chat")))
                    .blockLast(Duration.ofMillis(Math.min(planGenTimeoutMs(), 20000L)));
        } catch (Exception e) {
            log.info("[PLAN] 要素澄清判定失败/超时，直接出计划: session={} {}", st.sessionId, e.getMessage());
            return java.util.List.of();
        }
        return parseClarifyQuestions(out.toString());
    }

    /** 解析澄清判定输出：READY / 无 JSON / 解析失败 / 全部不合法都返回空表（=不用问）；问题按提问卡限制收敛
     *  （最多 3 问、每题 2~MAX_ASK_OPTIONS 个候选），避免走到整卡拒绝那条路上去 */
    private java.util.List<BuiltinTools.AskQuestion> parseClarifyQuestions(String raw) {
        if (raw == null || raw.isBlank()) return java.util.List.of();
        String s = raw.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return java.util.List.of();
        try {
            JSONArray arr = JSON.parseObject(s.substring(a, b + 1)).getJSONArray("questions");
            if (arr == null) return java.util.List.of();
            java.util.List<BuiltinTools.AskQuestion> qs = new java.util.ArrayList<>();
            for (int i = 0; i < arr.size() && qs.size() < 3; i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o == null) continue;
                String question = o.getString("question") == null ? "" : o.getString("question").trim();
                if (question.isEmpty()) continue;
                if (question.length() > 500) question = question.substring(0, 500);
                String topic = o.getString("topic") == null ? "" : o.getString("topic").trim();
                if (topic.length() > 16) topic = topic.substring(0, 16);
                java.util.List<String> opts = new java.util.ArrayList<>();
                JSONArray oa = o.getJSONArray("options");
                if (oa != null) for (Object oo : oa) {
                    if (opts.size() >= MAX_ASK_OPTIONS) break;
                    String v = oo == null ? "" : String.valueOf(oo).trim();
                    if (v.isEmpty() || opts.contains(v)) continue;
                    opts.add(v.length() > 200 ? v.substring(0, 200) : v);
                }
                if (opts.size() < 2) continue;   // 少于 2 个候选不构成选择题（与提问卡口径一致）
                qs.add(new BuiltinTools.AskQuestion(topic, question, opts));
            }
            return qs;
        } catch (Exception e) {
            log.info("[PLAN] 要素澄清输出不可解析，按无需提问处理: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    /** 提问卡的批量答案 → 计划轮可读的问答块；未作答（忽略/超时/留空）的条目不列 */
    private String formatClarifyAnswers(java.util.List<BuiltinTools.AskQuestion> qs, String answers) {
        java.util.List<String> ans = new java.util.ArrayList<>();
        if (qs.size() == 1) {
            ans.add(answers == null ? "" : answers);
        } else {
            try {
                for (Object o : JSON.parseArray(answers == null ? "[]" : answers)) {
                    ans.add(o == null ? "" : String.valueOf(o));
                }
            } catch (Exception e) {
                log.warn("[PLAN] 澄清答案解析失败，按未作答处理: {}", e.getMessage());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < qs.size(); i++) {
            String a = i < ans.size() ? ans.get(i).trim() : "";
            if (a.isEmpty() || a.startsWith("（用户未回答这一题")) continue;
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("- ").append(qs.get(i).question()).append("\n  用户选择：").append(a);
        }
        return sb.toString();
    }

    /** 计划正文降噪：剥掉 <process>/<related> 闭合块与首个未闭合开口之后的内容（计划轮没有工具，
     *  正常不会产出这些标签，防角色提示词诱导出的独白混进计划卡） */
    private String stripPlanNoise(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw.replaceAll("(?s)<process>.*?</process>|<related>.*?</related>", " ");
        int cut = s.indexOf("<process>");
        int cut2 = s.indexOf("<related>");
        if (cut >= 0 && (cut2 < 0 || cut < cut2)) s = s.substring(0, cut);
        else if (cut2 >= 0) s = s.substring(0, cut2);
        return s.trim();
    }

    /** 从落库 request_args 还原模型原稿计划（批准未带编辑稿时回退用） */
    private String parsePlanDraft(String argsJson) {
        try {
            com.alibaba.fastjson2.JSONObject args = JSON.parseObject(argsJson);
            return args == null ? "" : (args.getString("plan") == null ? "" : args.getString("plan"));
        } catch (Exception e) {
            return "";
        }
    }

    /** 上一版被「继续对话」取代的计划文本（新一轮按用户意见改它；没有则 null）：
     *  只认「最近一条助手消息就是被取代的计划」——中间隔了别的回答就不挂上一版，避免把无关计划当底稿 */
    private String findSupersededPlan(String sessionId) {
        try {
            List<Map<String, Object>> recent = sessionService.getRecentHistory(sessionId, 3);
            if (recent == null) return null;
            for (int i = recent.size() - 1; i >= 0; i--) {
                Map<String, Object> m = recent.get(i);
                if (m == null || !"assistant".equals(String.valueOf(m.get("role")))) continue;
                Object planObj = m.get("plan");
                if (!(planObj instanceof Map)) return null;
                Map<?, ?> plan = (Map<?, ?>) planObj;
                if (!"superseded".equals(String.valueOf(plan.get("status")))) return null;
                Object p = plan.get("plan");
                String text = p == null ? "" : String.valueOf(p);
                return text.isBlank() ? null : text;
            }
        } catch (Exception e) {
            log.warn("[PLAN] 读取上一版计划失败（本轮按首版出计划）: session={} {}", sessionId, e.getMessage());
        }
        return null;
    }

    /**
     * 用户裁决执行计划（计划模式）：仅发起本轮问答的用户本人可裁决（uid 比对，与工具审批同口径）。
     * revise=true 为退回重出（plan=用户的修改意见/改稿，同一轮内模型产出下一版，可反复）；
     * supersede=true 为「继续对话」（用户带着修改意见继续对话，本版作废，新一轮按意见重做计划）；
     * approved=false 为拒绝（整轮终止）；approved=true 时 plan 为用户（可能编辑过的）批准版计划，
     * 空串回落模型原稿。返回 false 表示裁决没能送达（记录不存在/非本人/已收尾/句柄随进程重启消失）。
     */
    public boolean resolvePlanApproval(String planApprovalId, boolean approved, String plan, boolean revise,
                                       boolean supersede, String uid) {
        if (planApprovalId == null || planApprovalId.isBlank()) return false;
        com.wenqu.ai.model.ToolApproval rec = toolApprovalMapper.selectById(planApprovalId);
        if (rec == null || !"planApproval".equals(rec.getToolName())) return false;
        if (uid == null || !uid.equals(rec.getUserId())) {
            log.warn("[PLAN] 裁决人非本轮用户，拒绝: id={} by={}", planApprovalId, uid);
            return false;
        }
        if (!"PENDING".equals(rec.getStatus())) return false; // 已处理，幂等
        PendingPlan p = PENDING_PLANS.get(planApprovalId);
        if (p == null) {
            // 唤醒句柄已不在（进程重启/多副本下那轮挂在别的实例）：终态照落，本轮无法续跑
            markApprovalResolved(planApprovalId, approved || revise || supersede ? "TIMEOUT" : "REJECTED", uid);
            log.info("[PLAN] 唤醒句柄已不在，本轮无法续跑: id={} session={}", planApprovalId, rec.getSessionId());
            return false;
        }
        if (!uid.equals(p.userId())) return false;
        if (supersede) {
            // 用户带着修改意见继续对话：本版作废（不是「未批准」——内容没被否定，是提出了修改）。
            // 前端随后把那条意见作为用户消息发出，新一轮按意见产出修改后的计划
            markApprovalResolved(planApprovalId, "SUPERSEDED", uid);
            log.info("[PLAN] 用户带着修改意见继续对话，本版计划作废: id={} session={}", planApprovalId, rec.getSessionId());
            return p.future().complete(PlanDecision.supersede());
        }
        if (revise) {
            String feedback = plan == null ? "" : plan.trim();
            if (feedback.isBlank()) return false;   // 空意见不能退回（前端也会拦）
            // 本版被用户的修改意见取代：意见留审计（answer 列与 askUser 同语义），状态记 REVISED
            try {
                toolApprovalMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.wenqu.ai.model.ToolApproval>()
                        .eq(com.wenqu.ai.model.ToolApproval::getId, planApprovalId)
                        .set(com.wenqu.ai.model.ToolApproval::getAnswer,
                                feedback.length() > 8000 ? feedback.substring(0, 8000) : feedback));
            } catch (Exception e) {
                log.warn("[PLAN] 修改意见留审计失败（不阻塞退回）: {}", e.getMessage());
            }
            markApprovalResolved(planApprovalId, "REVISED", uid);
            log.info("[PLAN] 用户要求修改计划，退回重出下一版: id={} session={} 意见 {} 字",
                    planApprovalId, rec.getSessionId(), feedback.length());
            return p.future().complete(PlanDecision.revise(feedback));
        }
        if (!approved) {
            markApprovalResolved(planApprovalId, "REJECTED", uid);
            return p.future().complete(PlanDecision.reject());
        }
        String finalPlan = plan == null || plan.isBlank() ? parsePlanDraft(rec.getRequestArgs()) : plan.trim();
        // 批准版计划留审计（answer 列与 askUser 同语义：用户最终确认的内容）
        try {
            toolApprovalMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.wenqu.ai.model.ToolApproval>()
                    .eq(com.wenqu.ai.model.ToolApproval::getId, planApprovalId)
                    .set(com.wenqu.ai.model.ToolApproval::getAnswer,
                            finalPlan.length() > 8000 ? finalPlan.substring(0, 8000) : finalPlan));
        } catch (Exception e) {
            log.warn("[PLAN] 批准稿留审计失败（不阻塞批准）: {}", e.getMessage());
        }
        markApprovalResolved(planApprovalId, "APPROVED", uid);
        log.info("[PLAN] 用户已批准执行计划: id={} session={} 编辑={}", planApprovalId, rec.getSessionId(),
                plan != null && !plan.isBlank());
        return p.future().complete(PlanDecision.approve(finalPlan));
    }

    /**
     * 待批准计划列表（卡片持久化后的恢复入口）：按会话取本人名下仍挂在「上一轮计划」上的记录，
     * 供前端在会话加载/切换、或点开计划通知进入会话时重建批准卡。
     * <p>版本链重建：自最新记录往前，直到遇到两版之间的终态（APPROVED/REJECTED/TIMEOUT）为止——
     * 这一串 PENDING/REVISED 就是本轮（同一轮内退回重出）的计划各版；链尾不是 PENDING 说明正在重出中，不重建。
     * 返回 items：最新一条（含 {@code planApprovalId/plan/timeoutMs/remainingMs/createdAt/expired/live}）
     * 加 {@code versions:[{plan,feedback}]} 版本链；{@code live=false} 表示唤醒句柄已不在
     * （进程重启/多副本），批准送不到模型，前端按「本轮已结束」提示。
     */
    public java.util.List<java.util.Map<String, Object>> listPendingPlans(String sessionId, String uid) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) return out;
        long window = planTimeoutMs();
        try {
            java.util.List<com.wenqu.ai.model.ToolApproval> rows = toolApprovalMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.wenqu.ai.model.ToolApproval>()
                            .eq(com.wenqu.ai.model.ToolApproval::getSessionId, sessionId)
                            .eq(com.wenqu.ai.model.ToolApproval::getUserId, uid)
                            .eq(com.wenqu.ai.model.ToolApproval::getToolName, "planApproval")
                            .orderByDesc(com.wenqu.ai.model.ToolApproval::getCreatedAt)
                            .last("LIMIT 30"));
            if (rows.isEmpty()) return out;
            // 自最新往前收集本轮版本链（PENDING/REVISED 继续、遇到终态即止）。
            // 最近一条已是终态（批准/取代/拒绝/超时，绝大多数会话进来看的就是这种情况）⇒ 链为空，
            // 没有待批计划可重建——必须先判空再取 last：getLast() 对空链抛 NoSuchElementException
            // （message 为 null），每次切进会话都会刷「待批准计划查询失败 …: null」的误导性警告
            java.util.LinkedList<com.wenqu.ai.model.ToolApproval> chain = new java.util.LinkedList<>();
            for (com.wenqu.ai.model.ToolApproval rec : rows) {
                String s = rec.getStatus();
                if ("PENDING".equals(s) || "REVISED".equals(s)) chain.addFirst(rec);
                else break;
            }
            if (chain.isEmpty()) return out;
            com.wenqu.ai.model.ToolApproval last = chain.getLast();
            // 链尾必须是 PENDING（可裁决）才重建；还是 REVISED = 正在按意见重出下一页，等它生成完再取
            if (!"PENDING".equals(last.getStatus())) return out;
            long createdMs = last.getCreatedAt() == null ? 0L
                    : last.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            long remaining = createdMs > 0 ? Math.max(0L, createdMs + window - System.currentTimeMillis()) : window;
            // 已过期但还没被超时路径收尾（收尾需要流水线线程醒一次）：不再当作可批卡片重建
            if (remaining <= 0) return out;
            // 各版 = 该版计划文本 + 产出该版的用户意见（前一版被 REVISED 时记在它的 answer 列；v1 无意见）
            java.util.List<Map<String, Object>> versions = new java.util.ArrayList<>();
            String producedBy = "";
            for (com.wenqu.ai.model.ToolApproval rec : chain) {
                String plan = parsePlanDraft(rec.getRequestArgs());
                if (plan.isBlank()) continue;
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("plan", plan);
                v.put("feedback", producedBy);
                versions.add(v);
                producedBy = "REVISED".equals(rec.getStatus()) && rec.getAnswer() != null ? rec.getAnswer() : "";
            }
            if (versions.isEmpty()) return out;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("planApprovalId", last.getId());
            item.put("plan", versions.get(versions.size() - 1).get("plan"));
            item.put("versions", versions);
            item.put("timeoutMs", window);
            item.put("remainingMs", remaining);
            item.put("createdAt", createdMs);
            item.put("expired", false);
            item.put("live", PENDING_PLANS.containsKey(last.getId()));
            out.add(item);
        } catch (Exception e) {
            log.warn("[PLAN] 待批准计划查询失败 session={}: {}", sessionId, e.getMessage());
        }
        return out;
    }

    /** 计划快照 → 助手消息 plan 列 JSON（null=本轮未走计划模式）；落库点见 buildAnswerStream 与中断兜底 */
    private String planRecordJson(AnswerStreamState st) {
        return st.planRecord == null ? null : JSON.toJSONString(st.planRecord);
    }

    /**
     * 计划被拒/超时的收尾落库：本轮没有流式回答（gate==null 直接终止，不会走 buildAnswerStream 的落库）。
     */
    private void persistPlanRejected(AnswerStreamState st) {
        persistPlanCloseout(st, "（计划未批准，本轮已停止）", "拒绝");
    }

    /**
     * 「继续对话」取代的收尾落库：与拒绝轮同构，但正文与卡片终态是「已被取代」而不是「未批准」——
     * 用户并没有否定计划内容，只是提出了修改；新一轮会按他的意见产出修改后的计划。
     */
    private void persistPlanSuperseded(AnswerStreamState st) {
        persistPlanCloseout(st, "（你提出了修改，这版计划已被取代）", "取代");
    }

    /**
     * 计划收尾轮（未批准 / 已被取代）的兜底落库：正文=前端同款收束语 + plan 快照 + 本轮执行快照。
     * <p>执行快照（提问卡问答、工具卡片、过程独白、时间线）必须一并落：这些在实时气泡里是用户
     * 看得见的「已输出的内容」（如计划前的那张问答记录卡），只落收束语会让它们刷新后整块消失，
     * 而计划卡本身也随刷新蒸发——历史只剩一条孤零零的用户提问。
     * <p>answerPersistGate 先到先得：占住闸位也宣告本轮已收尾，通道断开兜底（disposeSafe）与
     * 看门狗不再对同一轮补落第二条。
     */
    private void persistPlanCloseout(AnswerStreamState st, String content, String phase) {
        if (!st.claimTerminal()) return;   // 已有终态路径落过库
        try {
            List<Map<String, Object>> sessionArtifacts = artifactService.takeArtifacts(st.sessionId);
            List<Map<String, Object>> toolCallSnapshot = new ArrayList<>(st.toolCalls);
            String toolCallsJson = toolCallSnapshot.isEmpty() ? null : JSON.toJSONString(toolCallSnapshot);
            // 时间线按收束语长度夹取：计划轮的正文只可能是收束语（无回答流），工具/过程段照常保留
            List<Map<String, Object>> timelineSnapshot =
                    buildTimelineSnapshot(st, content, toolCallSnapshot.size(), sessionArtifacts.size());
            String timelineJson = timelineSnapshot.isEmpty() ? null : JSON.toJSONString(timelineSnapshot);
            String processText = st.processResponse.toString();
            sessionService.appendMessage(st.sessionId, "assistant", content,
                    null, null, st.thinkingHolder[0], st.retrievedJson,
                    sessionArtifacts.isEmpty() ? null : JSON.toJSONString(sessionArtifacts),
                    toolCallsJson, null, null, timelineJson, processText.isEmpty() ? null : processText,
                    st.agentId, st.agentName, st.model,
                    null, null, null,
                    planRecordJson(st));
        } catch (Exception e) {
            log.warn("[PLAN] {}轮收尾落库失败 (session={}): {}", phase, st.sessionId, e.getMessage());
        }
    }

    /**
     * askUser 工具执行体（一卡多问版）：向用户一次性下发多个结构化问题并阻塞等待批量作答，
     * 与工具审批同一套挂起-恢复管道（内存 future 阻塞工具线程 + DB 卡片记录 + SSE 提问卡 + 站内通知旁路）。
     * <p>返回值直接作为工具结果回给模型：每问一个答案的并行数组（多问为 JSON 数组字符串，单问退化为纯文本）。
     * <b>超时与忽略都不替用户选</b>——该题回一句「用户没有回答」，由模型自行推进（见
     * {@link #buildCombinedAnswer}）。等待窗口 chat.askTimeoutMs（默认 10 分钟）；这段时间内客户端断开
     * 不中止本轮（见 {@link AnswerStreamState#keepRunningWithoutChannel}），用户刷新或换设备后仍可按
     * DB 记录重建卡片并把答案送回来。
     */
    private String doAskUserMulti(AnswerStreamState st, java.util.List<BuiltinTools.AskQuestion> raw) {
        if (st.guestMode) {
            return "（游客会话不支持结构化提问：请改为在回答正文中直接列出候选选项，请用户回复序号或自行描述。）";
        }
        if (st.askCount.incrementAndGet() > MAX_ASKS_PER_TURN) {
            return "（本轮提问次数已达上限 " + MAX_ASKS_PER_TURN + " 次：请基于已有信息直接作答，不要再提问。）";
        }
        // 归一为多问题列表（每题独立校验：空问题/选项非法直接丢弃该题，不留半成品卡）
        java.util.List<QItem> qs = new java.util.ArrayList<>();
        for (BuiltinTools.AskQuestion a : raw) {
            if (a == null) continue;
            String q = a.question() == null ? "" : a.question().trim();
            if (q.isEmpty()) continue;
            if (q.length() > 500) q = q.substring(0, 500);
            String t = a.topic() == null ? "" : a.topic().trim();
            if (t.length() > 16) t = t.substring(0, 16);
            java.util.List<String> opts = new java.util.ArrayList<>();
            if (a.options() != null) {
                for (String o : a.options()) {
                    if (opts.size() >= MAX_ASK_OPTIONS) break;
                    if (o == null) continue;
                    String s = o.trim();
                    if (s.isEmpty() || opts.contains(s)) continue;
                    opts.add(s.length() > 200 ? s.substring(0, 200) : s);
                }
            }
            if (opts.size() < 2) continue; // 少于 2 个候选不构成选择题，非法该题跳过
            qs.add(new QItem(t, q, opts));
        }
        if (qs.isEmpty()) {
            return "（无有效问题：每题须含非空的 question 与 2~" + MAX_ASK_OPTIONS + " 个候选选项，工具未执行；请调整后重试，或直接在回答正文中列出选项提问。）";
        }
        if (qs.size() > MAX_QUESTIONS_PER_CARD) {
            return "（本次提问共 " + qs.size() + " 个问题，超过单卡上限 " + MAX_QUESTIONS_PER_CARD
                    + " 个，工具未执行：请把问题合并或分批，压缩到 " + MAX_QUESTIONS_PER_CARD + " 个以内后重新提问。）";
        }
        long timeout = askTimeoutMs();
        String askId = java.util.UUID.randomUUID().toString();
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        // 落库（复用 c_ai_tool_approval：tool_name=askUser，questions 存 request_args，答案数组存 answer）
        try {
            com.wenqu.ai.model.ToolApproval rec = new com.wenqu.ai.model.ToolApproval();
            rec.setId(askId);
            rec.setSessionId(st.sessionId);
            rec.setUserId(st.userId);
            rec.setToolName("askUser");
            rec.setStatus("PENDING");
            rec.setRequestArgs(askArgsJson(qs));
            rec.setCreatedAt(java.time.LocalDateTime.now());
            toolApprovalMapper.insert(rec);
        } catch (Exception e) {
            log.warn("[ASK] 提问记录落库失败（不阻塞提问流程）: {}", e.getMessage());
        }
        PENDING_ASKS.put(askId, new PendingAsk(st.sessionId, st.userId, future));
        // 提问待答站内通知（旁路）：卡片已落库且等待期间断线不中止本轮，人不在本页时铃铛是唯一
        // 可感知面，点进去仍能把这张卡答完（会话加载时按 DB 待答记录重建卡片）。
        try {
            notificationService.create(st.userId, com.wenqu.ai.model.Notification.TYPE_TOOL_ASK,
                    qs.size() > 1 ? "智能体有 " + qs.size() + " 个问题想跟你确认" : "智能体在等你回答",
                    "回到会话里作答，它才按你的选择继续；没答的那题不会替你选。",
                    "session", st.sessionId, "ask:" + askId, askId);
        } catch (Exception e) {
            log.warn("[ASK] 提问通知写入失败（不阻塞提问）: {}", e.getMessage());
        }
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("askId", askId);
            req.put("questions", qs.stream().map(this::askQuestionToMap).toList());
            req.put("timeoutMs", timeout);
            // 卡片下发失败（连接已断）：本轮就此转入后台续跑——提问已落库，用户从别的页面答完，
            // 回答照样生成并落库，回会话页可见。不转的话答完的第一个 token 发送失败会掐掉整轮。
            if (!sendSseEvent(st.emitter, "ask_user", JSON.toJSONString(req), st.sessionId)) {
                st.detached = true;
                log.info("[ASK] 提问卡通道已失效，本轮转后台续跑: askId={} session={}", askId, st.sessionId);
            }
            log.info("[ASK] 等待用户批量作答: askId={} session={} n={} 窗口={}ms",
                    askId, st.sessionId, qs.size(), timeout);
            String answer;
            beginHumanWait(st.emitter);   // 用户作答时间不计入整轮机器预算（见 TurnDeadline）
            st.askWaits.incrementAndGet();
            try {
                answer = future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                answer = buildCombinedAnswer(askId, st.userId, qs, null, "TIMEOUT");
            } catch (java.util.concurrent.ExecutionException ee) {
                markAskResolved(askId, "TIMEOUT", null, st.userId);
                answer = "（用户中止了本轮回答：请基于已有信息直接作答，不要再次提问。）";
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                markAskResolved(askId, "TIMEOUT", null, st.userId);
                answer = "（本轮问答已被中止：请基于已有信息直接作答，不要再次提问。）";
            } finally {
                st.askWaits.decrementAndGet();
                endHumanWait(st.emitter);
            }
            return answer;
        } finally {
            PENDING_ASKS.remove(askId);
        }
    }

    /** 单题内部表示 */
    private record QItem(String topic, String question, java.util.List<String> options) {}

    /** questions → SSE/落库用的 Map（topic 可空省略） */
    private Map<String, Object> askQuestionToMap(QItem x) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!x.topic().isEmpty()) m.put("topic", x.topic());
        m.put("question", x.question());
        m.put("options", x.options());
        return m;
    }

    /** questions → request_args JSON（TEXT 列，放宽到 8000 字符防多问题截断） */
    private String askArgsJson(java.util.List<QItem> qs) {
        String json = JSON.toJSONString(java.util.Map.of("questions", qs.stream().map(this::askQuestionToMap).toList()));
        return json.length() > 8000 ? json.substring(0, 8000) : json;
    }

    /**
     * 用户回答智能体提问：仅发起该轮问答的用户本人可答（uid 比对，内存态与 DB 双重校验，
     * 与工具审批同口径）。
     * <p>提问卡已落库、等待期间断线不中止本轮，所以刷新页面或换设备后按 {@link #listPendingAsks}
     * 取回卡片仍能把答案送到那根还阻塞着的工具线程。返回 false 表示这次作答没能送达（记录不存在/
     * 非本人/已收尾，或唤醒句柄已随进程重启消失），调用方据此提示用户。
     */
    public boolean resolveAsk(String askId, java.util.List<String> answers, String uid) {
        if (askId == null || askId.isBlank()) return false;
        com.wenqu.ai.model.ToolApproval rec = toolApprovalMapper.selectById(askId);
        if (rec == null || !"askUser".equals(rec.getToolName())) return false;
        if (uid == null || !uid.equals(rec.getUserId())) {
            log.warn("[ASK] 答题人非本轮用户，拒绝: askId={} by={}", askId, uid);
            return false;
        }
        if (!"PENDING".equals(rec.getStatus())) return false; // 已处理，幂等
        java.util.List<QItem> qs = parseQuestions(rec.getRequestArgs());
        if (qs.isEmpty()) return false;
        java.util.List<String> norm = answers == null ? java.util.List.of() : answers;
        PendingAsk p = PENDING_ASKS.get(askId);
        if (p == null) {
            // 没有人真的在等这轮（进程重启过，或多副本下这轮挂在另一个实例上）：答案无处可送。
            // 记录置终态并把用户所答留进审计列，卡片因此从待答列表消失，不再误导成「答了就生效」
            markAskResolved(askId, "TIMEOUT", JSON.toJSONString(norm), uid);
            log.info("[ASK] 唤醒句柄已不在，本轮无法续跑: askId={} session={}", askId, rec.getSessionId());
            return false;
        }
        if (!uid.equals(p.userId())) {
            log.warn("[ASK] 答题人非本轮用户（内存），拒绝: askId={} by={}", askId, uid);
            return false;
        }
        String combined = buildCombinedAnswer(askId, uid, qs, norm, "APPROVED");
        return p.future().complete(combined);
    }

    /**
     * 待答提问列表（卡片持久化后的恢复入口）：按会话取本人名下仍是 PENDING 的 askUser 记录，
     * 供前端在会话加载/切换、或点开 tool.ask 通知进入会话时重建提问卡。
     * <p>{@code live=false} 表示卡片还在但唤醒句柄已不在（进程重启；多副本下也可能是那轮挂在别的实例），
     * 此时作答送不到模型，前端按「本轮已结束」提示而不是给一个静默失败的输入框。
     * 倒计时以 DB 的 {@code createdAt} + 当前窗口换算，与后端阻塞超时同源。
     */
    public java.util.List<java.util.Map<String, Object>> listPendingAsks(String sessionId, String uid) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) return out;
        long window = askTimeoutMs();
        try {
            java.util.List<com.wenqu.ai.model.ToolApproval> rows = toolApprovalMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.wenqu.ai.model.ToolApproval>()
                            .eq(com.wenqu.ai.model.ToolApproval::getSessionId, sessionId)
                            .eq(com.wenqu.ai.model.ToolApproval::getUserId, uid)
                            .eq(com.wenqu.ai.model.ToolApproval::getToolName, "askUser")
                            .eq(com.wenqu.ai.model.ToolApproval::getStatus, "PENDING")
                            .orderByAsc(com.wenqu.ai.model.ToolApproval::getCreatedAt));
            for (com.wenqu.ai.model.ToolApproval rec : rows) {
                java.util.List<QItem> qs = parseQuestions(rec.getRequestArgs());
                if (qs.isEmpty()) continue;
                long createdMs = rec.getCreatedAt() == null ? 0L
                        : rec.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                long remaining = createdMs > 0 ? Math.max(0L, createdMs + window - System.currentTimeMillis()) : window;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("askId", rec.getId());
                item.put("questions", qs.stream().map(this::askQuestionToMap).toList());
                item.put("timeoutMs", window);
                item.put("remainingMs", remaining);
                item.put("createdAt", createdMs);
                // 已过期但还没被超时路径收尾（超时分支落终态需要工具线程醒一次）：不再当作可答卡片重建
                item.put("expired", remaining <= 0);
                item.put("live", PENDING_ASKS.containsKey(rec.getId()));
                out.add(item);
                if (out.size() >= 10) break; // 一卡最多 6 问、一轮最多 3 卡，10 条封顶纯属防御
            }
        } catch (Exception e) {
            log.warn("[ASK] 待答提问查询失败 session={}: {}", sessionId, e.getMessage());
        }
        return out;
    }


    /**
     * 用户忽略提问（提问面板「忽略」按钮）：不作答，立刻让本轮带着「用户没答这一题」继续推进
     * ——与超时同一语义的提前触发，都不替用户选答案。审计记 TIMEOUT + 空答案（用户确实做了「跳过」动作）。
     */
    public boolean resolveAskIgnore(String askId, String uid) {
        if (askId == null || askId.isBlank()) return false;
        // 问题列表从落库 args 还原（DB 是权威源：内存 future 只负责唤醒）
        com.wenqu.ai.model.ToolApproval rec = toolApprovalMapper.selectById(askId);
        if (rec == null || !"askUser".equals(rec.getToolName())) return false;
        if (uid == null || !uid.equals(rec.getUserId())) {
            log.warn("[ASK] 忽略人非本轮用户，拒绝: askId={} by={}", askId, uid);
            return false;
        }
        if (!"PENDING".equals(rec.getStatus())) return false; // 已处理，幂等
        java.util.List<QItem> qs = parseQuestions(rec.getRequestArgs());
        if (qs.isEmpty()) return false;
        PendingAsk p = PENDING_ASKS.get(askId);
        if (p == null) {
            markAskResolved(askId, "TIMEOUT", null, uid);
            log.info("[ASK] 唤醒句柄已不在，忽略动作无处生效: askId={} session={}", askId, rec.getSessionId());
            return false;
        }
        if (uid == null || !uid.equals(p.userId())) return false;
        String combined = buildCombinedAnswer(askId, uid, qs, null, "IGNORE");
        return p.future().complete(combined);
    }

    /** 从落库 request_args 还原问题列表（兼容旧式单问题 {question,options} 与一卡多问 {questions}） */
    private java.util.List<QItem> parseQuestions(String argsJson) {
        java.util.List<QItem> out = new java.util.ArrayList<>();
        try {
            com.alibaba.fastjson2.JSONObject args = JSON.parseObject(argsJson);
            if (args == null) return out;
            com.alibaba.fastjson2.JSONArray arr = args.getJSONArray("questions");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    com.alibaba.fastjson2.JSONObject o = arr.getJSONObject(i);
                    if (o == null) continue;
                    String q = o.getString("question");
                    if (q == null || q.isEmpty()) continue;
                    com.alibaba.fastjson2.JSONArray opts = o.getJSONArray("options");
                    java.util.List<String> os = opts == null ? java.util.List.of() : opts.toJavaList(String.class);
                    out.add(new QItem(o.getString("topic") == null ? "" : o.getString("topic"), q, os));
                }
                return out;
            }
            // 旧式单问题
            String q = args.getString("question");
            if (q == null || q.isEmpty()) return out;
            com.alibaba.fastjson2.JSONArray opts = args.getJSONArray("options");
            java.util.List<String> os = opts == null ? java.util.List.of() : opts.toJavaList(String.class);
            out.add(new QItem(args.getString("topic") == null ? "" : args.getString("topic"), q, os));
        } catch (Exception e) {
            log.warn("[ASK] 解析提问参数失败: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 组装批量答案：未作答的问题<b>不替用户选</b>——把「这一题没答」如实回给模型，让它基于已有
     * 信息自行推进或在正文里请用户回复。此前按推荐项（options[0]）代答：即便附了「并非用户亲自选择」
     * 的注脚，模型转述时仍会把默认决策讲成用户亲选，且业界（Claude Code 到期取消、Cursor 上报 skip）
     * 都没有「超时即替人选第一项」这一档。
     * 落库 answer 列（TEXT），返回工具结果文本（单问退化为纯文本，多问为 JSON 数组字符串，与问题下标对齐）。
     */
    private String buildCombinedAnswer(String askId, String uid, java.util.List<QItem> qs,
                                       java.util.List<String> userAnswers, String mode) {
        java.util.List<String> finalAnswers = new java.util.ArrayList<>();
        for (int i = 0; i < qs.size(); i++) {
            String ua = (userAnswers != null && i < userAnswers.size()) ? userAnswers.get(i) : null;
            if (ua != null && !ua.trim().isEmpty()) { finalAnswers.add(ua.trim()); continue; }
            // 统一前缀「（用户未回答这一题」：前端问答记录据此渲染成「未作答」，
            // 不把给模型看的处置指令原样摊给用户（见 web/src/chat/projections.js askUserView）
            String lead = switch (mode) {
                case "IGNORE" -> "（用户未回答这一题（点了忽略）";
                case "TIMEOUT" -> "（用户未回答这一题（已超时）";
                default -> "（用户未回答这一题（提交时留空）";
            };
            finalAnswers.add(lead + "。请基于已有信息自行判断，不要替用户假定选择，也不要重复提问。）");
        }
        String answerJson = JSON.toJSONString(finalAnswers);
        String status = "APPROVED".equals(mode) ? "APPROVED" : "TIMEOUT";
        markAskResolved(askId, status, answerJson, uid);
        if (qs.size() == 1) return finalAnswers.get(0);
        return answerJson;
    }

    /** 更新提问记录为终态（幂等：已非 PENDING 直接返回 false；uid 不匹配拒绝；answer 可空）。best-effort 不抛 */
    private boolean markAskResolved(String askId, String status, String answer, String uid) {
        try {
            com.wenqu.ai.model.ToolApproval rec = toolApprovalMapper.selectById(askId);
            if (rec == null) return false;
            if (uid != null && !uid.equals(rec.getUserId())) {
                log.warn("[ASK] 答题人非本轮用户（DB），拒绝: askId={} by={}", askId, uid);
                return false;
            }
            if (!"PENDING".equals(rec.getStatus())) return false; // 已处理，幂等
            toolApprovalMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.wenqu.ai.model.ToolApproval>()
                    .eq(com.wenqu.ai.model.ToolApproval::getId, askId)
                    .set(com.wenqu.ai.model.ToolApproval::getStatus, status)
                    .set(com.wenqu.ai.model.ToolApproval::getAnswer, answer)
                    .set(com.wenqu.ai.model.ToolApproval::getResolvedAt, java.time.LocalDateTime.now()));
            return true;
        } catch (Exception e) {
            log.warn("[ASK] 提问裁决落库失败: {}", e.getMessage());
            return false;
        }
    }

    /** 本轮工具上下文：会话/用户归属 + askUser 执行器（闭包持有 st，提问面板 SSE 与阻塞等待都靠它） */
    private java.util.Map<String, Object> toolContext(AnswerStreamState st) {
        java.util.Map<String, Object> ctx = new java.util.HashMap<>();
        ctx.put(PresentArtifactTool.CTX_SESSION_ID, st.sessionId);
        ctx.put(PresentArtifactTool.CTX_USER_ID, st.userId);
        ctx.put(BuiltinTools.CTX_ASK,
                (BuiltinTools.AskFn) qs -> doAskUserMulti(st, qs));
        return ctx;
    }

    /**
     * 用户裁决工具审批：仅发起该轮问答的用户本人可批（uid 比对，内存态与 DB 双重校验）；
     * 内存态丢失（进程重启/已超时）时仍可更新 DB 审计记录（幂等），但无法唤醒已死的工具线程。
     */
    /**
     * 从通知恢复审批卡：按 id 取审批记录（仅本人可查；不存在/非本人/已失效返回 null）。
     * 用于用户点开 tool.approval 通知后，在会话内重建审批卡——刷新丢失的 SSE 卡据此补回。
     */
    public com.wenqu.ai.model.ToolApproval getApproval(String approvalId, String uid) {
        if (approvalId == null || approvalId.isBlank()) return null;
        try {
            com.wenqu.ai.model.ToolApproval rec = toolApprovalMapper.selectById(approvalId);
            if (rec == null || rec.getUserId() == null || !rec.getUserId().equals(uid)) return null;
            return rec;
        } catch (Exception e) {
            log.warn("[TOOL] 恢复审批查询失败 approvalId={}: {}", approvalId, e.getMessage());
            return null;
        }
    }

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
            com.wenqu.ai.model.ToolApproval rec = toolApprovalMapper.selectById(approvalId);
            if (rec == null) return false;
            if (uid != null && !uid.equals(rec.getUserId())) {
                log.warn("[TOOL] 审批人非本轮用户（DB），拒绝: approvalId={} by={}", approvalId, uid);
                return false;
            }
            if (!"PENDING".equals(rec.getStatus())) return false; // 已裁决，幂等
            toolApprovalMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.wenqu.ai.model.ToolApproval>()
                    .eq(com.wenqu.ai.model.ToolApproval::getId, approvalId)
                    .set(com.wenqu.ai.model.ToolApproval::getStatus, status)
                    .set(com.wenqu.ai.model.ToolApproval::getResolvedAt, java.time.LocalDateTime.now()));
            return true;
        } catch (Exception e) {
            log.warn("[TOOL] 审批裁决落库失败: {}", e.getMessage());
            return false;
        }
    }

    /** 启动期清理遗留 PENDING（进程重启后内存态丢失，DB 中超时未裁决的记录置 TIMEOUT，避免审计永久挂起；
     *  askUser 提问卡同表同理——重启后那根阻塞的工具线程已不在，卡片留着也无人可唤醒） */
    @jakarta.annotation.PostConstruct
    public void purgeStaleApprovals() {
        try {
            long timeoutSec = approvalTimeoutMs() / 1000 + 5;
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusSeconds(timeoutSec);
            toolApprovalMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.wenqu.ai.model.ToolApproval>()
                    .eq(com.wenqu.ai.model.ToolApproval::getStatus, "PENDING")
                    .lt(com.wenqu.ai.model.ToolApproval::getCreatedAt, cutoff)
                    .set(com.wenqu.ai.model.ToolApproval::getStatus, "TIMEOUT")
                    .set(com.wenqu.ai.model.ToolApproval::getResolvedAt, java.time.LocalDateTime.now()));
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
     * 用完必须主动调用：它不会触发 onCompletion（没有真实响应可完成），否则 ACTIVE_SSE 与整轮台账
     * 会一直持有该 emitter 引用不放（看门狗也停不下来）。
     */
    static void forgetSseChannel(SseEmitter emitter) {
        if (emitter != null) {
            ACTIVE_SSE.remove(emitter);
            stopTurnDeadline(emitter);
        }
    }

    /** 客户端断开信号：思考流 doOnNext 内中断同步消费（blockLast）用 */
    private static final class SseClientGoneException extends RuntimeException {
    }

    /**
     * SSE 发送。返回 false = 通道已不可用（客户端断开，或 emitter 已完成后仍在发送）；
     * 失败只记日志不抛错（fail-loud），调用方在关键发送点按返回值短路后续 LLM/检索开销。
     */
    private boolean sendSseEvent(SseEmitter emitter, String type, String content, String sessionId) {
        // 账本按「事件是否发生」记，不按「是否送达」记：断线转后台续跑的那一轮，恰恰是最需要事后回放的
        // 过程（卡片、编排、裁决都在那一刻之后产生，而通道已经没了）。只收结构性事件
        // （{@link #LEDGER_SSE_TYPES}）——token/thinking/plan_delta 这类增量与心跳没有超出消息表的信息量。
        if (LEDGER_SSE_TYPES.contains(type)) {
            TurnHandle handle = sessionId == null ? null : TURN_HANDLES.get(sessionId);
            sessionEventService.append(sessionId, handle == null ? null : handle.userId(),
                    handle == null ? null : handle.turnId(), type, content);
        }
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
            markSseDead(emitter);
            log.warn("[FAIL-LOUD] SSE 客户端断开 (type={}, session={}): {}", type, sessionId, e.getMessage());
            return false;
        } catch (IllegalStateException e) {
            // "ResponseBodyEmitter has already completed"：终态/截断之后仍有发送（如迟到的流增量）。
            // 必须在这里咽掉——异常冒出会走 DispatcherServlet 的异步派发错误分支，
            // 全局异常处理器再往 text/event-stream 里写 ResultJson，必然二次失败并刷屏。
            markSseDead(emitter);
            log.debug("[SSE] 通道已关闭，事件丢弃 (type={}, session={}): {}", type, sessionId, e.getMessage());
            return false;
        }
    }

    private static void markSseDead(SseEmitter emitter) {
        ACTIVE_SSE.computeIfAbsent(emitter,
                k -> new java.util.concurrent.atomic.AtomicBoolean()).set(true);
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
            // 用量归属：改写跑在专属线程池上，请求上下文不可见——入口线程先取 uid，池内 hold 显式带上
            String billingUid = com.wenqu.ai.util.RequestUser.uid();
            java.util.concurrent.Future<String> f = rewriteExecutor.submit(() -> {
                com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(
                        com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                        null, null, "rewrite"));
                try {
                    return chatClient.prompt()
                            .system("你是检索查询改写器，只输出改写后的查询文本。")
                            .user(prompt)
                            .options(OpenAiChatOptions.builder()
                                    .model(resolvedModel)
                                    .temperature(0.0)
                                    .maxTokens(200)
                                    .build())
                            .call()
                            .content();
                } finally {
                    com.wenqu.ai.util.UsageAttr.clear();
                }
            });
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
                                 String replaceMessageId, String editVariantGroup, Agent delegatedAgent) {
        sendSseEvent(emitter, "plan", JSON.toJSONString(List.of("执行工作流")), sessionId);
        sendSseEvent(emitter, "stage", "正在执行工作流…", sessionId);
        java.util.concurrent.ScheduledFuture<?> heartbeat = scheduleKeepalive(emitter, "工作流");
        try {
            String workflowUserMsgId = null;
            if (!regenerate) {
                workflowUserMsgId = sessionService.appendMessage(sessionId, "user", question, null, null, null, null, null, null, null);
                // 编辑重发：新用户消息挂上被替换旧分支的版本组键（与主链路同口径）
                if (workflowUserMsgId != null && editVariantGroup != null && !editVariantGroup.isBlank()) {
                    sessionService.setMessageVariant(workflowUserMsgId, editVariantGroup);
                }
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
            // 重新生成：先软删被替换的旧回答并登记分支版本组（与主链路口径一致，多版本可切换）
            String replaceGroup = null;
            if (replaceMessageId != null && !replaceMessageId.isBlank()) {
                replaceGroup = sessionService.beginAnswerReplace(replaceMessageId);
            }
            String messageId = sessionService.appendMessage(sessionId, "assistant", answer,
                    workflowImages.isEmpty() ? null : List.copyOf(workflowImages), null, null, null, null, null,
                    null, JSON.toJSONString(tokens), null, null,
                    agent.getId(), agent.getName());
            if (replaceGroup != null && messageId != null) {
                sessionService.setMessageVariant(messageId, replaceGroup);
            }
            qaLogService.logAsync(sessionId, question, answer, List.of(), false,
                    System.currentTimeMillis() - startTime, question, null, false,
                    messageId, agent.getId());
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("sources", List.of());
            donePayload.put("related", List.of());
            donePayload.put("messageId", messageId);
            // 本轮用户消息 ID（编辑重发后前端给新用户消息挂分支切换器；regenerate 时为 null 不下发）
            if (workflowUserMsgId != null && !workflowUserMsgId.isBlank()) {
                donePayload.put("userMessageId", workflowUserMsgId);
            }
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
            // §4 轮级委派归属（与主链路同口径）：工作流智能体被 @ 委派时气泡归属按委派值展示
            if (delegatedAgent != null) {
                donePayload.put("delegatedAgentId", delegatedAgent.getId());
                donePayload.put("delegatedAgentName", delegatedAgent.getName() == null ? "" : delegatedAgent.getName());
            }
            sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), sessionId);
            completeEmitter(emitter);
            // 记忆提取：工作流节点用的是自己的模型，这里按「用户个人默认聊天模型」提取长期记忆
            // （没有配置默认模型就不提取——记忆提取必须有模型，不传空引用去让下游猜/报内部错）
            com.wenqu.ai.model.User prefUser = loadPrefUser(userId);
            String memoryModel = prefUser == null ? null : prefUser.getDefaultModel();
            if (memoryModel != null && !memoryModel.isBlank()) {
                userMemoryService.maybeExtract(userId, sessionId, question, answer, guestMode, memoryModel);
            } else {
                log.debug("[WORKFLOW-CHAT] 未配置个人默认模型，跳过长期记忆提取: uid={}", userId);
            }
            log.info("[WORKFLOW-CHAT] 工作流回答完成: session={} run={} v={} 耗时 {}ms",
                    sessionId, run.getId(), run.getVersion(), run.getDurationMs());
        } catch (com.wenqu.ai.common.BizException e) {
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
        // 工作流 inputs 的短历史用固定 5 轮（代码常量，非用户设置）：工作流节点自带上下文语义，
        // 不做预算驱动全量带入，避免把主链路的压缩策略耦合进工作流
        List<Map<String, Object>> recent = sessionService.getRecentHistory(sessionId, 5);
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
            throw new com.wenqu.ai.common.BizException("工作流的结束节点没有产出任何出参，无法作为回答");
        }
        Object answer = outputs.get("answer");
        if (answer == null && outputs.size() == 1) answer = outputs.values().iterator().next();
        if (answer == null) {
            throw new com.wenqu.ai.common.BizException("工作流的结束节点未声明 answer 出参（当前出参："
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
     * ③ 图片按能力位处理：模型支持读图时原图随消息直发（media 部件），不支持时注入不可见说明（不参与检索）。
     * 复用主回答流：sources/retrieved 均空 → 前端检索状态行与引用区天然不渲染。
     */
    private void runNoKnowledgeChat(String sessionId, String question, String userId,
                                    List<UserImageService.UserImage> userImgs,
                                    String imgNote, String attachmentText, String userSkillText,
                                    String mentionText, String historyRefText,
                                    java.util.concurrent.ScheduledFuture<?> preHeartbeat,
                                    SseEmitter emitter, long startTime,
                                    String[] thinkingHolder, List<Map<String, String>> degradations,
                                    Set<String> degradedCodes, Agent agent, Map<String, Long> stageMs,
                                    String resolvedModel, boolean guestMode, String replaceMessageId,
                                    String userMessageId, Agent delegatedAgent, boolean planMode,
                                    List<String> skills) {
        try {
            // 计划意图识别（按用户意图自动开启计划模式）：与主链路同口径；本分支无检索可争抢，
            // 识别在闸门前串行取结果（宁缺毋滥，最多让本轮多等 awaitPlanIntent 的上限）
            final java.util.concurrent.CompletableFuture<Boolean> planIntentFuture = startPlanIntentCheck(
                    emitter, guestMode, planMode,
                    replaceMessageId != null && !replaceMessageId.isBlank(),
                    sessionId, question, resolvedModel, attachmentText != null && !attachmentText.isBlank());
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
            // chatflow 主回答：沿用固定 5 轮短历史（代码常量，非用户设置）；预算驱动全量带入只在 runChat 主链路生效
            List<Map<String, Object>> recentHistory = sessionService.getRecentHistory(sessionId, 5);
            if (recentHistory == null) {
                addDegradation(degradations, degradedCodes, "historyFailed", "会话历史读取失败，本次无多轮记忆");
                recentHistory = List.of();
            }
            String historyText = buildHistoryText(recentHistory);
            if (!historyText.isEmpty()) {
                system.append("\n\n对话历史：\n").append(historyText);
            }

            // 拼装用户消息：问题 + 图片说明（仅"模型不支持读图"时有值）+ 附件内容
            StringBuilder userQuestion = new StringBuilder(question);
            if (imgNote != null && !imgNote.isBlank()) {
                userQuestion.append(imgNote);
            }
            if (attachmentText != null && !attachmentText.isBlank()) {
                userQuestion.append("\n\n用户上传了附件，内容如下（请结合附件内容回答问题）：\n").append(attachmentText);
            }
            if (hasMention) {
                userQuestion.append("\n\n【本轮显式引用的资料】用户通过 @ 指定了以下文档内容，"
                        + "回答时请优先依据这些资料，并在引用处标注 [N] 编号：\n").append(mentionText);
            }
            if (historyRefText != null && !historyRefText.isBlank()) {
                userQuestion.append("\n\n【本轮引用的历史对话】用户从本会话历史中指定了以下问答作为本轮参考，"
                        + "请结合它们回答当前问题：\n").append(historyRefText);
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
            st.toolScopeKbIds = scopeKbIdsOf(agent, userId);
            st.toolScopeDocIds = resolveScopeDocIds(agent, userId);
            // 本轮生效模型（会话覆盖 > 个人默认）：不赋值会让 buildAnswerStream 发出无 model 的请求，
            // DynamicOpenAiChatModel 落到遗留全局网关且 model 为空 → 网关 400（2026-09-25 通用助手实测）
            st.model = resolvedModel;
            st.guestMode = guestMode; // 游客分享会话：工具只保留知识检索与内置项
            st.replaceMessageId = replaceMessageId; // 重新生成：落库前软删被替换的旧回答
            st.userMessageId = userMessageId; // 本轮用户消息 ID（随 done 下发，编辑重发后前端挂切换器）
            st.delegatedAgentId = delegatedAgent != null ? delegatedAgent.getId() : null; // §4 轮级委派归属
            st.delegatedAgentName = delegatedAgent != null ? delegatedAgent.getName() : null;
            st.toolApprovalMode = agent == null ? null : agent.getToolApprovalMode(); // 有副作用工具审批模式
            st.maxToolSteps = resolveMaxToolSteps(agent); // 单轮工具步数上限（智能体覆盖 > 全局）
            st.heartbeat = preHeartbeat; // 前置心跳句柄移交（终态照旧停止）
            st.contextTokens = 0;
            st.budgetTokens = 0;
            st.contextHits = 0;
            // 生效最大输出（模型管理中该模型声明的值）：buildAnswerStream 的 max_tokens 与触顶判定共用
            st.effectiveMaxOutput = effectiveMaxOutputOf(resolvedModel);
            st.stageMs.putAll(stageMs);
            // 计划模式（人在回路）：与主链路同一段钩子（本分支没有检索资料，计划输入不含资料清单）；
            // 未开计划开关时同样按消息意图自动开启（识别命中=本轮按计划模式走，卡片标「自动开启」）
            boolean planRound = planMode;
            if (!planRound && awaitPlanIntent(planIntentFuture)) {
                planRound = true;
                st.planAuto = true;
                log.info("[PLAN] 意图识别命中，本轮自动开启计划模式（无知识库分支）: session={}", sessionId);
            }
            if (planRound) {
                String planQuestion = question + (imgNote == null || imgNote.isBlank() ? "" : imgNote)
                        + (attachmentText == null || attachmentText.isBlank() ? ""
                        : "\n\n（用户上传了附件，其内容将在执行阶段提供）");
                String gate = planApprovalGate(st, agent, resolveSystemPrompt(agent), planQuestion, skills,
                        null, system);
                if (gate == null) {
                    // 同主链路：拒绝/超时=未批准定格；「继续对话」取代=本版作废；均先补落收尾助手消息
                    // （含 plan 快照）再发对应事件 + 标准 done（防「连接被提前关闭」误报）
                    if (st.planSuperseded) {
                        persistPlanSuperseded(st);
                        sendSseEvent(emitter, "plan_superseded", "{}", sessionId);
                    } else {
                        persistPlanRejected(st);
                        sendSseEvent(emitter, "plan_cancelled", "{}", sessionId);
                    }
                    Map<String, Object> cancelled = new LinkedHashMap<>();
                    cancelled.put("sources", List.of());
                    cancelled.put("related", List.of());
                    cancelled.put("degradations", degradations);
                    if (st.planSuperseded) cancelled.put("planSuperseded", true);
                    else cancelled.put("planCancelled", true);
                    sendSseEvent(emitter, "done", JSON.toJSONString(cancelled), sessionId);
                    completeEmitter(emitter);
                    return;
                }
            }
            st.disposableRef.set(buildAnswerStream(system.toString(), user, st, agent));
            emitter.onCompletion(() -> { if (!st.keepRunningWithoutChannel()) st.disposeSafe(); });
            emitter.onError(t -> { if (!st.keepRunningWithoutChannel()) st.disposeSafe(); });
        } catch (Exception e) {
            log.error("No-knowledge chat error", e);
            sendSseEvent(emitter, "error", "系统处理异常，请稍后重试", sessionId);
            completeEmitter(emitter);
        }
    }
}
