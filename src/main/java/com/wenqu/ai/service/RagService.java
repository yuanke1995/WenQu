package com.wenqu.ai.service;

import com.wenqu.ai.agent.tool.ResourceRefs;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.TurnDeadline;
import com.wenqu.ai.agent.chat.TurnHandle;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.agent.chat.SseClientGoneException;
import com.wenqu.ai.agent.hitl.HitlDecisionService;
import com.wenqu.ai.agent.chat.TurnState;
import com.wenqu.ai.agent.hitl.HitlDecisionService.PlanDecision;
import com.wenqu.ai.agent.hitl.HitlDecisionService.QItem;
import com.wenqu.ai.agent.subagent.BranchReport;
import com.wenqu.ai.agent.subagent.BranchRequest;
import com.wenqu.ai.agent.subagent.BranchRunner;
import com.wenqu.ai.agent.tool.ToolApprover;
import com.wenqu.ai.agent.tool.ToolEnrichment;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolInvocation;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.agent.tool.ToolPipelineFactory;
import com.wenqu.ai.agent.tool.ToolRegistry;
import com.wenqu.ai.agent.tool.ToolTurnContext;
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
     * 判断一次流式失败是不是"上游不认 tools"（P5 §六）。
     * <p>判据刻意保守：必须同时出现「工具/function」与「不支持/无效/未知」两类字样。
     * 宁可漏判（走原有的失败提示）也不误判——误判会把一次真实的额度或服务故障
     * 伪装成"本轮没有工具"，用户就会拿着错的原因去排查。
     * <p>取文本要覆盖**整条异常链加 HTTP 响应体**：实测（假上游 {@code scripts/fake_upstream_no_tools.py}）
     * 网关的 400 到这一层只剩 {@code WebClientResponseException: 400 Bad Request from POST …}，
     * 上游写的 "does not support tools" 在 {@code getResponseBodyAsString()} 里——只看 message 时判据永远落空，
     * 换路一次都不会触发，表现照旧是"整轮失败"（这条链路在补上假上游之前无法实测，就这么静默着）。
     */
    private static boolean looksLikeToolsRejected(Throwable error) {
        String m = failureText(error).toLowerCase();
        boolean toolMention = m.contains("tool") || m.contains("function");
        boolean rejected = m.contains("not support") || m.contains("unsupported") || m.contains("invalid")
                || m.contains("unknown") || m.contains("unrecognized") || m.contains("unexpected");
        return toolMention && rejected;
    }

    /** 异常链上的全部可见文本（含 WebClient 的 HTTP 响应体，限长防大串），最多回溯 16 层防自引用成因死循环 */
    private static String failureText(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable t = error;
        for (int depth = 0; t != null && depth < 16; t = t.getCause(), depth++) {
            sb.append(t.getMessage()).append('\n');
            if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException wre) {
                sb.append(wre.getResponseBodyAsString()).append('\n');
            }
            if (sb.length() > 8000) break;
        }
        return sb.toString();
    }

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
                runtime.addDegradation(degradations, degradedCodes, "rerankUnavailable",
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
    /**
     * 进程收尾（重启/部署/被容器杀掉）时追加的标记。第三种成因，必须与前两个分开：用户既没按停止、
     * 连接也没断，是服务本身换了个进程——写成「已停止本轮」会让人以为是自己手滑停的。
     */
    private static final String RESTART_SUFFIX = "\n\n> ⏹ 服务在这一轮中途重启，以上为已生成的部分";
    /**
     * 整轮失败（模型流抛异常 / 工具失败冒泡）时追加在正文尾部的标注。
     * 与 TurnState.TRUNCATION_SUFFIX 区分：中断是「本来还能继续」，失败是「这次没能答完」。
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

    /** 知识库服务：解析「智能体关联的知识库 → 允许检索的文档集合」，检索按库隔离 */
    private final KnowledgeBaseService knowledgeBaseService;

    /** 角色服务：提问者是否管理员级（内置 admin/superadmin 或自定义 admin_flag 角色）——回答按角色收敛管理端内容 */
    private final RoleService roleService;

    /** 用户表（个人默认模型解析） */
    private final com.wenqu.ai.mapper.UserMapper userMapper;
    /** 产物交付工具（Function Calling；生成文件并实时推送） */
    /** 内置高频工具（计算/当前时间/日期差等，tool.builtin.enabled 控制，默认开） */
    private final BuiltinTools builtinTools;
    /** 联网搜索工具（webSearch.enabled 控制，默认关；结果注册进引用体系，与知识库来源同编号） */
    /** 沙盒工具（execute / read_file / write_file / ls；tool.sandbox.enabled 控制，默认关，依赖 provisioner 服务） */
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
    /** 智能体咨询（P5 agent-as-tool）：模型在工具循环里自己决定咨询谁；台账按轮建 */
    private final com.wenqu.ai.agent.subagent.SubAgentConsultService subAgentConsult;
    private final ModelRegistryService modelRegistryService;
    /**
     * M4：工作流服务（智能体绑定工作流时取用）。走 ObjectProvider <b>惰性</b>获取而不是直接注入：
     * {@code WorkflowService → WorkflowEngine → RagService} 已是一条依赖链（子智能体节点复用问答管线），
     * 这里再直接注入就成环（Spring Boot 3 默认禁止循环依赖，启动直接失败）。惰性取用把这个
     * 「只有绑定了工作流的智能体才用得到」的反向依赖推迟到调用时，依赖图保持单向。
     */

    /** @ 引用：被引文档的块直接前置进上下文（不经检索，不参与相关性门） */
    private final com.wenqu.ai.mapper.KnowledgeMapper knowledgeMapper;
    /** 站内通知：工具审批待决（tool.approval）通知发起人。旁路语义 */
    private final NotificationService notificationService;
    /** 会话事件账本：结构性事件按写入顺序留痕（工具/人在回路/编排/轮终态/停止）。旁路语义，不进模型输入 */
    private final SessionEventService sessionEventService;
    /** 任务清单工具（writeTodo）：整表覆盖会话当前清单，写入器经工具上下文注入本轮 */
    private final TodoService todoService;
    /**
     * 运行总线（断线重连接流）：本轮的执行与那条 SSE 通道解耦——事件先进总线再扇给此刻挂着的通道，
     * 观众走光不掐本轮（宽限到点才按中断收束），迟到的观众先拿快照再接增量。详见 {@link ChatRunBus}。
     */
    private final ChatRunBus chatRunBus;
    /**
     * 工具大输出外溢：超过展示上限的原文整份落盘，卡片只留预览 + spillId，
     * 「查看完整输出」按需读回——此前是截断即丢，尾部少了多少、少了什么，用户与排障的人都看不到。
     */
    private final ToolSpillService toolSpillService;
    /**
     * 工具调用管线（P1 seam 化）：守卫链（叫停/预算/审批）→ 上下文注入 → 执行 → 观测 → 插话附送。
     * 迁移前这一整段是 instrumentTools 里的匿名内部类，于是工作流与技能的裸调路径无从复用同一批闸。
     */
    private final ToolPipelineFactory toolPipeline;
    /** 工具注册中心：中文名、副作用等级、是否需要审批的单一真源（装配与卡片展示都读它） */
    private final ToolRegistry toolRegistry;
    /**
     * 权限档位解析（P2）：沙盒执行档 × 工具执行确认捆成的具名预设，加上平台默认与管理员上限。
     * <p>本轮只解析一次并落在 {@code TurnState} 上，装配侧与守卫侧读同一份——两处各算一次
     * 就会漂出「工具表里没收、执行时却放得过去」这类反向错配。
     */
    private final com.wenqu.ai.agent.permission.PermissionPolicyResolver permissionPolicyResolver;
    /**
     * run_code 的一次性运行凭据（P3b）。
     * <p>问答侧只在一个地方用它：用户叫停本轮时把这条轮还没撤的凭据一并撤掉。凭据的作用域本来就是
     * "这一次运行"，人已经不想要这一轮了，脚本却还能继续回调平台，那是多出来的一个窗口。
     */
    private final com.wenqu.ai.agent.runcode.RunTokenRegistry runTokens;
    /**
     * 人在回路裁决服务（P6 刀 1 抽族）：三张卡（工具执行审批 / askUser 提问 / 执行计划批准）的
     * 挂起登记、终态落库、用户裁决与跨设备恢复都在它那里。本类只留「发卡 + 阻塞等待」那一段——
     * 那一段持有的是本轮的通道与计时，而卡片本身（谁在等、等哪一张、答成什么样）与通道无关。
     */
    private final com.wenqu.ai.agent.hitl.HitlDecisionService hitl;
    /**
     * 本轮状态的运行时依赖（P6 刀 0）。
     * <p>{@code TurnState} 原先是私有内部类，隐式拿着整个问答服务——任何一处「抽服务」都会变成多个类
     * 共享同一个可变口袋。提顶层后它要什么必须写在 {@link TurnState.Deps} 里，这张清单就是唯一答案。
     * <p>那三个动作（SSE 投递、降级登记、半程落库）暂时还长在问答侧：它们都要动通道与落库，归属随
     * 刀 2「轮次控制」那一刀才搬得干净。这里交的是匿名实现而不是 this——{@code TurnState} 拿到的是
     * 三个方法，不是整个服务。
     */
    private final TurnState.Deps turnDeps;
    /**
     * 轮次控制与存活（P6 刀 2）：SSE 投递与断开跟踪、心跳与存活看门狗、停止本轮 / 运行中插话 /
     * 探测接流、断线宽限中止都在它那里。本类只剩「这一轮怎么把资料找出来、答案怎么生成出来」。
     * <p>方向单向：问答侧调用它，它不回叫问答侧。{@code chat()} 刻意留在这边（要提交流水线），
     * 而原先直接摸那三张登记表的四处改走它的具名闸口——登记表不外露，进出都得走名字。
     */
    private final ChatRunRuntime runtime;

    /**
     * run_code 的子调用通道与实时面板（P6 刀 3a）：脚本里那一路与模型直调同一条守卫链，
     * 扇出清单/在途/额度这本账也只在子调用通道里才看得全。本类只在挂了 run_code 的轮次找它。
     */
    private final com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime subCall;

    /**
     * 子智能体分支的簿记（P6 刀 3b）：派一支的通道、回报文本、引用与图片回填、给下一层的父轮上下文。
     * 「跑一整轮」仍由本类传入（{@code this::chat}），所以它不认识问答服务、也没有反向引用。
     */
    private final com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branch;

    /**
     * 对话历史的装配与滚动压缩（P6 刀 4）：本轮带多少历史、什么时候压、手动 /compact 怎么分批压完。
     * 它不认识 {@code TurnState}，只要会话、模型、预算与降级容器——所以问答侧调它，它不回叫问答侧。
     */
    private final com.wenqu.ai.agent.context.HistoryCompactionService historyCompaction;

    /**
     * 深度思考这一族（P6 刀 4）：生成前那次同步消费的思考流与从里面提出的检索计划。
     * 它不回头调问答入口，所以能做成 bean；检索与生成仍在本类（阶段3 交回这里执行）。
     */
    private final com.wenqu.ai.agent.think.DeepThinkService deepThinkService;

    /**
     * chatflow 桥（P6 刀 4）：智能体绑定了工作流的那条路——每轮把问题送进图，end 节点出参就是回答。
     * 这一条路没有生成流，所以桥完全不碰 {@code TurnState}，只吃通道与会话落库。
     */
    private final com.wenqu.ai.agent.chatflow.ChatflowBridge chatflow;

    /**
     * 计划模式这一族（P6 刀 4）：出计划、挂批准卡、按意见重出下一版、计划意图识别。
     * 它要「按与生成轮同一条口径」装配一次工具集、要弹一张与执行期同管道的提问卡——
     * 这两件事都在本类，所以经 {@link #planHooks} 交进去（同 P3a 把「跑一整轮」当参数交进分支族那条口径）。
     */
    private final com.wenqu.ai.agent.plan.PlanModeService plan;

    /** 计划族本轮要用的回call（无本轮状态，只是把本类那四个方法按接口签名摆出来） */
    private final com.wenqu.ai.agent.plan.PlanModeService.PlanTurnHooks planHooks;

    /** M1：查询改写专用线程池（隔离超时任务，避免占用公共池/无限堆积）。
     *  线程数随 chat.pipelineThreads 联动扩容（syncPipelineSize）：改写是每轮必经的短 LLM 调用
     *  （≤8s），固定 2 线程在百级并发下排队到超时、改写静默降级为原句——不报错但召回质量塌方 */
    private ThreadPoolExecutor rewriteExecutor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), r -> {
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
        historyCompaction.syncPoolSize(Math.max(2, n / 4));   // 压缩池归历史族自己管（刀 4）
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

    /**
     * 进程收尾：把还在跑的这一轮<b>已生成的半程正文</b>落库，别让它跟着进程一起蒸发。
     * <p>为什么单列一刀：宽限中止、用户停止、流错误三条路都走 {@link #persistPartialAnswer}，只有重启不会——
     * SIGTERM 一到就 {@code shutdownNow()}，那一轮的正文与工具卡全丢，用户回来只看见自己那句问题，
     * 接流也只会回 {@code running:false}（把手是内存态）。这条不改变「跨进程续跑」的边界（跑一半的
     * LLM 流接不回来），只是把已经产出的部分按中断口径留在历史里。
     * <p>为什么走 ContextClosedEvent 而不是再加一个 @PreDestroy：同一个 bean 上多个 @PreDestroy
     * 不保证顺序，而容器关闭事件先于所有 bean 销毁——这里必须抢在 {@code pipelineExecutor} 被掐之前读到
     * 状态表，也抢在 DataSource 关掉之前写完。
     */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    void flushInFlightTurnsOnShutdown() {
        if (!TurnRegistry.hasRunning()) return;
        int saved = 0;
        for (TurnState st : TurnRegistry.runningStates()) {
            String sid = st.sessionId;
            try {
                if (st.claimTerminal()) {
                    persistPartialAnswer(st, RESTART_SUFFIX);
                    saved++;
                }
                // 账本同样要留这一笔：事后回看「这一轮为什么只有半程」，不写会以为是自己断的线
                sessionEventService.append(sid, st.userId, st.turnId, "stop",
                        "进程收尾中止：正文 " + st.fullResponse.length() + " 字、工具步 "
                                + st.tools.toolStepCount.get() + " 步");
                // 一条都没产出的轮：给那一问打上「中断」的终止原因，否则用户只看到一句孤零零的问题
                sessionService.markTurnStopped(sid, "interrupted");
                // 总线立刻收摊：接流的观众当场看到这一轮停了，而不是干等巡检
                chatRunBus.finish(sid);
            } catch (Exception e) {
                log.warn("[SHUTDOWN] 半途正文留存失败 session={}: {}", sid, e.getMessage());
            }
        }
        if (saved > 0) log.info("[SHUTDOWN] 进程收尾：{} 轮在跑的半程正文已按中断态落库", saved);
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
                      BuiltinTools builtinTools,
                      SkillService skillService,
                      ChatAttachmentService chatAttachmentService,
                      SubAgentOrchestrator subAgentOrchestrator,
                      AgentService agentService,
                      com.wenqu.ai.agent.subagent.SubAgentConsultService subAgentConsult,
                      KnowledgeBaseService knowledgeBaseService,
                      RoleService roleService,
                      com.wenqu.ai.mapper.UserMapper userMapper,
                      ModelRegistryService modelRegistryService,
                      UserMemoryService userMemoryService,
                      UserConfigService userConfigService,
                      com.wenqu.ai.mapper.KnowledgeMapper knowledgeMapper,
                      NotificationService notificationService,
                      SessionEventService sessionEventService,
                      TodoService todoService,
                      ChatRunBus chatRunBus,
                      ToolSpillService toolSpillService,
                      ToolPipelineFactory toolPipeline,
                      ToolRegistry toolRegistry,
                      com.wenqu.ai.agent.permission.PermissionPolicyResolver permissionPolicyResolver,
                      com.wenqu.ai.agent.runcode.RunTokenRegistry runTokens,
                      com.wenqu.ai.agent.hitl.HitlDecisionService hitl,
                      ChatRunRuntime runtime,
                      com.wenqu.ai.agent.runcode.RunCodeSubCallRuntime subCall,
                      com.wenqu.ai.agent.subagent.SubAgentBranchRuntime branch,
                      com.wenqu.ai.agent.context.HistoryCompactionService historyCompaction,
                      com.wenqu.ai.agent.think.DeepThinkService deepThinkService,
                      com.wenqu.ai.agent.chatflow.ChatflowBridge chatflow,
                      com.wenqu.ai.agent.plan.PlanModeService plan) {
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
        this.builtinTools = builtinTools;
        this.skillService = skillService;
        this.chatAttachmentService = chatAttachmentService;
        this.subAgentOrchestrator = subAgentOrchestrator;
        this.agentService = agentService;
        this.subAgentConsult = subAgentConsult;
        this.knowledgeBaseService = knowledgeBaseService;
        this.roleService = roleService;
        this.userMapper = userMapper;
        this.userMemoryService = userMemoryService;
        this.userConfigService = userConfigService;
        this.modelRegistryService = modelRegistryService;
        this.knowledgeMapper = knowledgeMapper;
        this.notificationService = notificationService;
        this.sessionEventService = sessionEventService;
        this.todoService = todoService;
        this.chatRunBus = chatRunBus;
        this.toolSpillService = toolSpillService;
        this.toolPipeline = toolPipeline;
        this.toolRegistry = toolRegistry;
        this.permissionPolicyResolver = permissionPolicyResolver;
        this.runTokens = runTokens;
        this.hitl = hitl;
        this.runtime = runtime;
        this.subCall = subCall;
        this.branch = branch;
        this.historyCompaction = historyCompaction;
        this.deepThinkService = deepThinkService;
        this.chatflow = chatflow;
        this.plan = plan;
        this.planHooks = new com.wenqu.ai.agent.plan.PlanModeService.PlanTurnHooks() {
            @Override
            public java.util.List<org.springframework.ai.tool.ToolCallback> mountTools(
                    com.wenqu.ai.model.Agent agent, TurnState st) {
                return enabledToolCallbacks(agent, st.userId, st);
            }

            @Override
            public boolean todoExposed(com.wenqu.ai.model.Agent agent, TurnState st) {
                return RagService.this.todoExposed(st.guestMode, agent, st.branchTurn);
            }

            @Override
            public void recordToolStatus(TurnState st, String name, String input, String status,
                                         String resultOrError, long elapsedMs, int attempts, boolean denied) {
                RagService.this.recordToolStatus(st, name, input, status, resultOrError, elapsedMs, attempts, denied);
            }

            @Override
            public String askUser(TurnState st, java.util.List<BuiltinTools.AskQuestion> questions) {
                return RagService.this.doAskUserMulti(st, questions);
            }
        };
        // 本轮状态的依赖清单：必须在上面全部赋值之后再建（它要引用这些服务）
        this.turnDeps = new TurnState.Deps(configService, rerankService, imageUrlSigner, chatRunBus,
                subAgentConsult, new TurnState.TurnRuntime() {
                    @Override
                    public boolean sendSse(SseEmitter e, String type, String content, String sessionId) {
                        return runtime.sendSseEvent(e, type, content, sessionId);
                    }

                    @Override
                    public void addDegradation(List<Map<String, String>> list, Set<String> codes,
                                               String code, String msg) {
                        runtime.addDegradation(list, codes, code, msg);
                    }

                    @Override
                    public void persistPartial(TurnState st, String tailMarker) {
                        persistPartialAnswer(st, tailMarker);
                    }
                });
    }

    /**
     * 处理用户问题（可含上传图片与附件），通过 SSE 流式返回。
     * <p>P1 seam 化：这里原本是 10 个 telescoping 重载层层委托（10 参一路加到 19 参），
     * 现收成 {@link ChatCommand} 一个入参对象。加能力不再动调用方，相邻同类型实参写反也不再
     * 只能靠人眼拦（见 ChatCommand 类注释）。
     * <p>{@code emitter} 刻意不进命令对象：它是这一轮的<b>出口</b>而不是内容——工作流子 agent 用
     * CollectingSseEmitter，定时任务与对外 MCP 端点各有自己的收集通道，与「问了什么」正交；
     * 混进同一个列表就会出现第 11 个重载。整条流水线在独立线程池执行（重活不占 Tomcat 请求线程）。
     */
    public void chat(ChatCommand cmd, SseEmitter emitter) {
        // 解包成本轮内的局部名：流水线体量大（互斥登记、总线开轮、状态构造、异步提交），
        // 逐处改成 cmd.getX() 的收益是零、风险是把一个位置写错的 bug 埋进八千行里
        final String sessionId = cmd.getSessionId();
        final String question = cmd.getQuestion();
        final List<String> userImages = cmd.getUserImages();
        final List<ChatRequest.Attachment> attachments = cmd.getAttachments();
        final List<String> skills = cmd.getSkills();
        final List<ChatRequest.Mention> mentions = cmd.getMentions();
        final List<ChatRequest.HistoryRef> historyRefs = cmd.getHistoryRefs();
        final boolean deepThink = cmd.isDeepThink();
        final String agentId = cmd.getAgentId();
        final String modelOverride = cmd.getModelOverride();
        final String userId = cmd.getUserId();
        final String reasoningLevel = cmd.getReasoningLevel();
        final Integer requestedContextWindow = cmd.getRequestedContextWindow();
        final boolean guestMode = cmd.isGuestMode();
        final boolean regenerate = cmd.isRegenerate();
        final String replaceMessageId = cmd.getReplaceMessageId();
        final String editVariantGroup = cmd.getEditVariantGroup();
        final boolean planMode = cmd.isPlanMode();
        final boolean fromSteer = cmd.isFromSteer();

        // 是否深度思考由用户自己决定（对话页 per-model 开关）+ 模型能力决定，平台不代为路由：
        // 管理员侧的自动路由已删除——它会覆盖用户显式关闭的选择、强制消耗用户的 token。
        final boolean useDeepThink = deepThink;
        // 会话轮级互斥：同一会话同时只允许一轮在跑。并发两问会历史交错、ArtifactService EMITTERS
        // 相互覆盖（后轮覆盖前轮，先完成者注销掉后轮的推送）、首问智能体绑定竞态。断线后台续跑
        // 与 askUser 等待期同样占坑——等待作答时再发新消息正是要挡的交错。限频/归属校验已在上游。
        if (!runtime.tryBeginTurn(sessionId)) {
            log.warn("[FAIL-LOUD] 同会话并发问答被拒: session={}", sessionId);
            runtime.sendSseEvent(emitter, "error", "该会话上一轮还在回答中，请等回答结束或先停止本轮，再发送新消息", sessionId);
            runtime.completeEmitter(emitter);
            return;
        }
        // 本轮身份从这一刻起可用（早于流状态构造）：账本的每条事件都挂得到同一个 turnId
        final String turnId = java.util.UUID.randomUUID().toString().replace("-", "");
        TurnRegistry.registerHandle(sessionId, new TurnHandle(turnId, userId, emitter));
        // 断开跟踪：登记查表项并绑定生命周期回调清理；发送失败也会打标（见 runtime.sendSseEvent），各等待点据此短路后续 LLM/检索开销
        runtime.trackChannel(emitter);
        // 运行总线登记（P0 断线重连接流）：本轮的执行不再绑死在发起时那条通道上——刷新/换设备能接回来看它
        // 继续长出来，观众走光也只是「暂时没人看」，宽限（chat.detachGraceMs）到点才由总线回调按中断收束，
        // 走的还是既有半程截断落库那条路。刻意不登记：收集型通道（定时任务/MCP/工作流节点没有真实响应）、
        // 开关关闭（行为回到「断开即中止」）。
        // 游客分享会话<b>也登记</b>（2026-10-12 补全）：归属用的是访客自己的身份（share-visitor:{随机}）
        // 而不是发布者——用发布者的 uid 挂归属，等于任何拿到分享链接的人都能接走别人会话的流。
        // 访客身份缺失（老链接没带 visitorId）就退回不登记，照旧按历史回显，绝不退成「按发布者放行」。
        final String runOwner = guestMode ? cmd.getGuestUid() : userId;
        if (chatRunBus.enabled() && runOwner != null && !runOwner.isBlank()
                && !(emitter instanceof CollectingSseEmitter)) {
            chatRunBus.open(sessionId, turnId, runOwner, emitter,
                    () -> resumeSnapshot(sessionId, turnId),
                    () -> runtime.abortTurnWithoutViewer(sessionId),
                    () -> {
                        TurnHandle h = TurnRegistry.handle(sessionId);
                        return h != null && turnId.equals(h.turnId());
                    });
        }
        // 整轮存活看门狗：emitter 无容器超时，截断由台账按机器耗时判定（人工等待不计入，见 TurnDeadline）。
        // 收集型通道（定时任务/MCP/子智能体节点）不挂：它没有真实响应、节奏由调用方 awaitDone 控制。
        TurnDeadline ledger = null;
        if (!(emitter instanceof CollectingSseEmitter)) {
            ledger = new TurnDeadline(runtime.turnMachineBudgetMs());
            TurnRegistry.registerDeadline(emitter, ledger);
            runtime.startTurnWatchdog(emitter, sessionId, ledger);
        }
        final TurnDeadline deadline = ledger;
        emitter.onCompletion(() -> runtime.releaseSseChannel(emitter, deadline, sessionId));
        emitter.onTimeout(() -> runtime.releaseSseChannel(emitter, deadline, sessionId));
        emitter.onError(t -> runtime.releaseSseChannel(emitter, deadline, sessionId));
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
                runChat(cmd, emitter, useDeepThink);
                } finally {
                    // 轮次登记（TURN_STATES / TURN_HANDLES）刻意不在这里摘：runChat 在 subscribe 后就
                    // 返回了，生成与工具循环此后跑在响应式线程上，这里摘会让「停止本轮」找不到正在出字
                    // 的那一轮。摘除统一挂在 TurnState#claimTerminal()（本轮真收尾那一刻）。
                    // 反过来，前置阶段叫停的轮次要等检查点才退场，所以「这一问没有回答」的标记
                    // 只能在本线程走到底时打——早一步判会抢在落库之前，晚一步这里已经出不来了。
                    // 断线宽限到点的中止也在这一并判：那条路径由总线线程 dispose，把手当场就没了，
                    // 只看 runtime.turnStopRequested 会漏打标记，用户下次进来读到的是「问出去了却什么都没有」的空档。
                    // 两种终止要分开写：用户点停止 ≠ 没人看超时——后者不是用户做的事，文案不许怪他
                    if (runtime.takeBareAbort(sessionId, turnId)) {
                        sessionService.markTurnStopped(sessionId, "interrupted");
                    } else if (runtime.turnStopRequested(sessionId)) {
                        sessionService.markTurnStopped(sessionId, "stopped");
                    }
                    runtime.endTurnMutex(sessionId);
                    if (identity) com.wenqu.ai.util.RequestUser.clear();
                    // 智能体检索参数的作用域覆盖随本轮结束清除（ThreadLocal，池化线程复用必须清，
                    // 否则下一轮请求会继承上一轮智能体的检索策略）
                    configService.clearOverride();
                    configService.clearUserOverrides();
                }
            });
        } catch (RejectedExecutionException e) {
            // 任务未入队即拒绝：轮级互斥标记须同步放掉，否则该会话从此被永久挡在门外
            TurnRegistry.removeHandle(sessionId);
            runtime.endTurnMutex(sessionId);
            // L7 fail-loud：繁忙拒绝时告知当前队列长度（用户可感知拥堵程度）
            int queued = pipelineExecutor == null ? 0 : pipelineExecutor.getQueue().size();
            log.warn("[FAIL-LOUD] 问答流水线繁忙，拒绝请求（排队 {}）: session={}", queued, sessionId);
            runtime.sendSseEvent(emitter, "error", "系统繁忙（当前排队 " + queued + " 个请求），请稍后重试", sessionId);
            runtime.completeEmitter(emitter);
        }
    }

    /**
     * 问答流水线主体（独立线程执行）：图片/附件处理 → 改写 → 检索/深度思考 → 上下文构建 → LLM 流式输出
     */
    /**
     * 本轮真正跑起来的那一段。入参同样是 {@link ChatCommand}（外加本轮裁决出的 deepThink——
     * 它由用户开关与模型能力在本轮入口共同决定，不回写命令对象，避免「命令」被流水线改脏）。
     */
    private void runChat(ChatCommand cmd, SseEmitter emitter, boolean deepThink) {
        // 解包成本轮内的局部名：流水线体量大（互斥登记、总线开轮、状态构造、异步提交），
        // 逐处改成 cmd.getX() 的收益是零、风险是把一个位置写错的 bug 埋进八千行里
        final String sessionId = cmd.getSessionId();
        final String question = cmd.getQuestion();
        final List<String> userImages = cmd.getUserImages();
        final List<ChatRequest.Attachment> attachments = cmd.getAttachments();
        final List<String> skills = cmd.getSkills();
        final List<ChatRequest.Mention> mentions = cmd.getMentions();
        final List<ChatRequest.HistoryRef> historyRefs = cmd.getHistoryRefs();
        final String agentId = cmd.getAgentId();
        final String modelOverride = cmd.getModelOverride();
        final String userId = cmd.getUserId();
        final String reasoningLevel = cmd.getReasoningLevel();
        final Integer requestedContextWindow = cmd.getRequestedContextWindow();
        final boolean guestMode = cmd.isGuestMode();
        final boolean regenerate = cmd.isRegenerate();
        final String replaceMessageId = cmd.getReplaceMessageId();
        final String editVariantGroup = cmd.getEditVariantGroup();
        final boolean planMode = cmd.isPlanMode();
        // 插话续发：用户补在上一轮跑着时的方向，随 done 回落成新一轮发出来。默认 false 时行为逐字不变
        final boolean fromSteer = cmd.isFromSteer();
        // deepThink 不从这里解包：它是本轮入口裁决后的值（用户开关 + 模型能力），比命令里那份更晚
        // P3a 子智能体分支的三件事：深度、父轮交代、父会话。全是默认值（0/null/null）时本轮就是
        // 用户在对话里直接发起的那一轮，行为与引入这些字段之前逐字相同。
        final int subagentDepth = cmd.getSubagentDepth();
        final boolean branchTurn = cmd.branchTurn();
        final String parentBrief = cmd.getParentBrief();
        final String parentSessionId = cmd.getParentSessionId();
        if (branchTurn) {
            log.info("[SUBAGENT] 本轮是子智能体分支: 深度={} 父会话={} 本会话={} 摘要={}字",
                    subagentDepth, parentSessionId, sessionId, parentBrief == null ? 0 : parentBrief.length());
        }

        long startTime = System.currentTimeMillis();
        // 本轮身份：取自 chat() 入口登记的把手（runChat 全程都在这一轮里跑，把手到真收尾才摘），
        // 随消息落库挂上——逐气泡的「过程」按它取这一轮的事件账本
        final String turnId = runtime.turnIdOf(sessionId);
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
            runtime.sendSseEvent(emitter, "error",
                    "未指定模型：请在对话右上角选择模型，或在个人设置中配置默认模型", sessionId);
            runtime.completeEmitter(emitter);
            return;
        }
        // 本轮回答的全部降级/兜底事件（fail-loud：随 done 下发，前端渲染警示条；code 去重，同类只报一次）。
        // 声明在智能体解析之前：会话锁定的智能体若已不可用，需要就地登记提示而不是静默改用全局配置
        List<Map<String, String>> degradations = new ArrayList<>();
        Set<String> degradedCodes = new HashSet<>();
        // @ 智能体（P5 换芯）：不再是"整轮换成它作答"，而是"本轮必须咨询它一次"——它带着自己的
        // 知识库跑一条分支，把资料与专业视角交回来，人设/工具/权限档仍归本轮作答方。
        // 用户显式点名的咨询不受 agent.enabled 影响：那是平台侧编排开关，这条是用户指令。
        final Agent forcedConsult = resolveDelegatedAgent(mentions, degradations, degradedCodes);
        // 作答方解析（P5）：请求带 id 用它、否则默认智能体；不再有首问锁定
        final Agent agent = resolveTurnAgent(agentId, degradations, degradedCodes);
        final boolean consultSelf = forcedConsult != null && agent != null
                && forcedConsult.getId().equals(agent.getId());
        if (forcedConsult != null && !consultSelf) {
            log.info("[AGENT] 会话 {} 本轮强制咨询 @ 提及的智能体 {}（{}）",
                    sessionId, forcedConsult.getId(), forcedConsult.getName());
            emitAgentDelegated(emitter, forcedConsult, sessionId);
        } else if (consultSelf) {
            // @ 的就是本轮作答方：咨询自己没有对象，如实说一句，而不是把用户的指令静默吞掉
            runtime.addDegradation(degradations, degradedCodes, "consultSelf",
                    "你 @ 的就是本轮作答的助手，无需再咨询自己，已直接按它的资料回答");
        }
        if (agent != null) {
            log.info("[AGENT] 本轮使用智能体 {}（{}）", agent.getId(), agent.getName());
            // 会话行记一笔"最近作答方"（供分享页按智能体列会话与访客回收用；
            // P5 起它不再是锁定值，下一轮照常按请求重新解析）
            sessionService.recordTurnAgent(sessionId, agent.getId(), agent.getName());
        }
        // M4：智能体绑定工作流（chatflow）——回答由工作流产出，整条不走常规检索/生成链路。
        // 位置在智能体解析之后：绑定是智能体的属性，会话级锁定后每轮都走同一张图（同一人设语义）。
        // 图片/附件在此路径下不参与（工作流只接收文本入参），显式登记为降级提示而不是静默丢弃。
        if (agent != null && agent.getWorkflowId() != null && !agent.getWorkflowId().isBlank()) {
            if ((userImages != null && !userImages.isEmpty()) || (attachments != null && !attachments.isEmpty())) {
                runtime.addDegradation(degradations, degradedCodes, "workflowInputIgnored",
                        "该智能体由工作流驱动，本轮的图片/附件不会传入工作流（工作流当前只接收文本入参）");
            }
            if (planMode) {
                runtime.addDegradation(degradations, degradedCodes, "planWorkflowUnsupported",
                        "该智能体由工作流驱动，计划模式不生效，本轮按工作流直接执行");
            }
            // memoryModel 用本轮已经取到的那份个人偏好（工作流节点用自己的模型，长期记忆按个人默认提取）
            chatflow.runWorkflowChat(sessionId, question, userId,
                    prefUser == null ? null : prefUser.getDefaultModel(),
                    emitter, startTime, degradations, degradedCodes,
                    agent, guestMode, branchTurn, regenerate, replaceMessageId, editVariantGroup,
                    null);
            return;
        }
        // 目标知识库集合（检索按库的向量模型分组逐库查询；null=不限，全库分组检索）。
        // P5：用户在对话页直接问的那一轮<b>不再按"作答智能体绑了哪些库"划墙</b>——问题会跨主题，
        // 墙的表现就是"上一轮问手册、这一轮问法律，法律库一块也进不来"。范围回到本轮使用者的可见库
        // （检索侧按 loadVisibleKbIds 收口，放开的是"智能体的库界"，不是"人的权限"）。
        // 仍然保留库界的两种情形：① 免登录分享页与对外 MCP（那是匿名访客读资料的唯一权限墙）；
        // ② 子智能体分支（被咨询的那一位必须只查自己那套库，否则"派给专业助手"就没有意义，
        //    父轮已经把所有可见库查过一遍了）。
        // @ 引用仍会把范围收窄到被引库（用户显式指定优先，见下）。
        java.util.Collection<String> scopeKbIds = scopeKbIdsOf(agent, userId, guestMode || branchTurn);
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
        // 检索范围：智能体关联的知识库（主路径）→ 库内文档；knowledgeScope 降级为「库内再细选文档」。
        // @ 文档时收窄到这些文档（显式指定优先）——用户就是在问这份文档，别的文档的块不该进上下文。
        // P5：登录页的父轮与上面同口径，不取智能体的文档细选（否则解绑只解了一半）。
        final Set<String> scopeDocIds = mentionScope != null && !mentionScope.docIds().isEmpty()
                ? new LinkedHashSet<>(mentionScope.docIds())
                : resolveScopeDocIds(agent, userId, guestMode || branchTurn);
        // Agentic RAG「检索-反思」循环模式判定：开启后本轮强制暴露知识检索工具，并在生成提示词
        // 注入「证据充分性自评」循环规则——模型判断首轮检索证据不足时自主换关键词/换角度再检索
        // （次数仍由 agent.maxToolSteps 兜底），证据足够才作答。
        // 前置不满足 fail-loud（登记降级并本轮关闭，不注入指挥模型调不存在工具的提示词）：
        // 工具总开关未开 / 模型不支持 Function Calling。
        final boolean reflectiveRetrieval;
        {
            boolean reflectOn = !knowledgeOff && reflectiveRetrievalOn(useDeepThink, thinkLevel);
            if (reflectOn && !configService.getBoolean("tool.enabled")) {
                runtime.addDegradation(degradations, degradedCodes, "reflectiveDegraded",
                        "检索-反思循环需要工具总开关（tool.enabled）开启，本轮未生效");
                reflectOn = false;
            }
            if (reflectOn && !modelRegistryService.toolCapableOf(resolvedModel)) {
                runtime.addDegradation(degradations, degradedCodes, "reflectiveDegraded",
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
            runtime.sendSseEvent(emitter, "plan", JSON.toJSONString(planSteps), sessionId);
            // 0. 进度提示：理解问题阶段（图片描述/改写都有耗时，先给用户反馈）
            runtime.sendSseEvent(emitter, "stage", "正在理解问题…", sessionId);
            // 前置心跳：从本轮一开始就保活（图片视觉描述/深度思考/检索都是长静默区，
            // serverless 端点冷启动时检索可达 90s+，此前该阶段无心跳 ⇒ 前端 120s 空闲看门狗
            // 掐断连接，用户看到"无输出且报错"而后端其实还在干活）。句柄在 st 创建时接管
            // （runtime.startRunHeartbeat 幂等复用），终态路径照旧停止；st 前异常/断开由发送失败自停兜住。
            final java.util.concurrent.ScheduledFuture<?> preHeartbeat =
                    runtime.scheduleKeepalive(emitter, "前置");
            // 计划意图识别（按用户意图自动开启计划模式）：尽早启动、与图片/附件/检索并行跑，闸门前取结果。
            // 计划开关已开时无需检测（本轮必走计划）；重新生成不检测——用户要的是重答，不是重出一份计划
            final java.util.concurrent.CompletableFuture<Boolean> planIntentFuture = plan.startIntentCheck(
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
                    runtime.addDegradation(degradations, degradedCodes, "chatModelNoVision",
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
                        historyRefs != null && !historyRefs.isEmpty() ? JSON.toJSONString(historyRefs) : null,
                        null,                       // 计划卡只在助手消息上，用户提问没有
                        // 本轮身份：一问一答共用一个 turnId，逐气泡「过程」按它取这一轮的账本事件
                        turnId);
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
                        userMessageId, null, planMode, skills, subagentDepth, fromSteer);
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

            // 断开或用户叫停短路（图片视觉处理/改写期间断开已在此打标）：思考与检索都是成本操作，不再发起
            if (runtime.turnAborted(sessionId, emitter)) {
                log.info("[SSE] 客户端断开或已停止，终止本轮问答（检索前）: session={}", sessionId);
                return;
            }

            // 0.5 按需委派路由提前：委派挑选（route）不依赖检索结果，放在检索前执行。
            // 总耗时不变：原时序为 检索→route→子代理，现对调为 route→检索→子代理（串行段相同）。
            // （这里曾有一条「委派收窄主检索范围」的分支（agent.dispatchNarrowScope）：把主检索收窄到
            //  主智能体库∪被选中子智能体库。P5 解绑后主检索本来就覆盖使用者全部可见库，收窄反而重新
            //  造出一堵墙，故连同该配置键一并删除。）
            List<Agent> subCandidates = List.of();
            SubAgentOrchestrator.RouteResult routeRes = null;
            // 本轮实际要咨询的助手（@ 点名的那位一定在内，即使平台侧路由没挑它）
            List<Agent> consultAgents = List.of();
            // 按需委派的路由结果（创建 TurnState 时要回填，供 done 下发与持久化）
            Map<String, Object> subagentRouteInfo = null;
            if (configService.getBoolean("agent.enabled") || forcedConsult != null) {
                List<Agent> declared = resolveSubAgents(agent);
                // @ 点名的那位排在最前且一定在场：它可能不是"子智能体"、也没挂在作答方名下，
                // 但用户点了名——用户指令优先于平台侧编排开关（agent.enabled 关着也照样咨询）。
                subCandidates = forcedConsult == null ? declared : withFirst(forcedConsult, declared);
                if (!subCandidates.isEmpty()) {
                    // 阶段文案统一：路由挑选属"理解问题"准备段（具体挑了谁由 subagent_route 事件承载）
                    runtime.sendSseEvent(emitter, "stage", "正在理解问题…", sessionId);
                    routeRes = subAgentOrchestrator.route(question, subCandidates, resolvedModel);
                    List<Agent> delegated = routeRes.picked();
                    if (forcedConsult != null && !hasAgentId(delegated, forcedConsult.getId())) {
                        // 路由没挑上用户点名的一位：不拿路由结论否决用户指令，补进去
                        List<Agent> merged = new ArrayList<>();
                        merged.add(forcedConsult);
                        merged.addAll(delegated);
                        delegated = merged;
                    }
                    consultAgents = delegated;
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
                    runtime.sendSseEvent(emitter, "subagent_route", JSON.toJSONString(routeInfo), sessionId);
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
                var dr = deepThinkService.run(sessionId, question, imgNote, attachmentText, emitter, resolvedModel, thinkLevel);
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
                    runtime.sendSseEvent(emitter, "stage", "正在检索资料…", sessionId);
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
                    runtime.addDegradation(degradations, degradedCodes, "deepThinkDegraded", "深度思考未完成，已转为普通回答");
                }
            }
            // 深度思考期间断开/叫停（thinking 发送失败或下一个增量撞上停止标志即中止思考流）：降级路径同样不再继续检索
            if (runtime.turnAborted(sessionId, emitter)) {
                log.info("[SSE] 客户端断开或已停止，终止本轮问答（思考后）: session={}", sessionId);
                return;
            }
            // 降级/未开启深度思考：走普通单路检索
            if (hits == null) {
                runtime.sendSseEvent(emitter, "stage", "正在检索资料…", sessionId);
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
                runtime.addDegradation(degradations, degradedCodes, "vectorFailed", "向量检索失败，本次仅关键词召回");
            }
            // 多库检索中某库向量检索失败：其余库向量命中仍参与召回，不能谎称"仅关键词召回"；
            // 提示用户相关库未参与本次召回（具体供应商/原因见服务端日志与额度通知）
            if (retrievalDiag.isVectorPartialFailed()) {
                runtime.addDegradation(degradations, degradedCodes, "vectorPartialFailed", "部分知识库向量检索失败，相关库未参与本次召回");
            }
            if (retrievalDiag.isKeywordFailed()) {
                runtime.addDegradation(degradations, degradedCodes, "keywordDegraded", "关键词引擎不可用，已降级 MySQL 检索");
            }
            if (retrievalDiag.isKeywordBusy()) {
                runtime.addDegradation(degradations, degradedCodes, "keywordBusy", "关键词检索繁忙/超时，本次仅向量召回");
            }
            if (retrievalDiag.isMultiTimeout()) {
                runtime.addDegradation(degradations, degradedCodes, "multiTimeout", "多路检索超时，仅用已完成结果");
            }
            log.info("[RAG] 检索命中 {} 块, query={}", hits.size(), retrievalQuery);
            // 本轮主链路重排是否实际执行（有任一命中拿到重排分）：已执行时，未重排候选不得再走融合门入场（见填充段分域门）
            boolean rerankActive = hits.stream().anyMatch(h -> h.rerankScore() != null);
            // 兜住最后一处静默：重排"看起来可用"（探测通过、调用也发了）却没给出任何重排分——
            // 此时门同样退化到融合门，与不可用后果一致，必须同样告知（不能只在 debugUnavailableReason 有值时才提示）
            if (!rerankActive && !hits.isEmpty()) {
                runtime.addDegradation(degradations, degradedCodes, "rerankUnavailable",
                        "重排本轮未返回相关性分数：上下文与引用按融合分筛选，相关性可能偏低");
            }

            // SubAgent 并行编排（4.3，默认关）：多视角并行检索 + 要点提炼；P3a 起被委派的子智能体
            // 可以按档位跑「真全循环」（整轮问答，见 SubAgentOrchestrator 类注释）。
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
                    // 执行形态与工具步数：让用户看得见"这一支是交资料还是真干活"（digest 恒 steps=0）
                    m.put("mode", branch.mode());
                    m.put("steps", branch.steps());
                    runtime.sendSseEvent(emitter, "subagent", JSON.toJSONString(m), sessionId);
                };
                // 全循环分支的执行器（P3a）：把分支交回这条管线自己跑。
                // 「跑一整轮」仍是按传进来的函数（this::chat），簿记在 SubAgentBranchRuntime 里——
                // 判据见该类的注释：要回头调问答入口的一族不能做成互相注入的 bean。
                BranchRunner runner = branch.branchRunner(this::chat, sessionId, turnId, userId, guestMode, emitter);
                if (subCandidates.isEmpty() && branchTurn) {
                    // 分支轮不再展开「多视角并行检索」：这一支本身就是父轮扇出来的一条分支，
                    // 再展开一层就是乘数成本（真机实测每支多 4~6 秒与两次提炼调用），
                    // 而分支要做的事是"把这件事办完"，不是把父轮的检索再猜一遍视角。
                    // 它自己**显式挂了**子智能体时照样往下派（受深度硬顶约束），所以只跳这一条隐式多视角。
                    log.info("[SUBAGENT] 分支轮跳过隐式多视角编排（乘数成本），本支只做主检索 + 工具循环");
                } else if (subCandidates.isEmpty()) {
                    // 未挂子智能体 → 原有的「多视角并行检索」
                    // 阶段文案统一：并行检索只是检索的一种实现（分支进度由 subagent 事件承载）
                    runtime.sendSseEvent(emitter, "stage", "正在检索资料…", sessionId);
                    subOutcome = subAgentOrchestrator.run(new SubAgentOrchestrator.BranchSpec(
                            question, null, onBranch, resolvedModel, subagentDepth, sessionId, turnId,
                            userId, null, guestMode, runner));
                } else {
                    // 挂了子智能体 → 路由已在检索前完成（见 0.5 段的 subagent_route 事件），此处直接按挑选结果并行咨询；
                    // 全量为空=路由判定无需咨询任何助手（问题与各助手职责均不匹配），跳过编排
                    List<Agent> delegated = consultAgents;
                    if (delegated.isEmpty()) {
                        log.info("[SUBAGENT] 按需委派判定无需咨询任何助手，跳过并行编排（问题与各助手职责均不匹配）");
                    } else {
                        // 阶段文案统一：子智能体也是检索路数之一（咨询了哪几个由 subagent 事件承载）
                        runtime.sendSseEvent(emitter, "stage", "正在检索资料…", sessionId);
                        subOutcome = subAgentOrchestrator.run(new SubAgentOrchestrator.BranchSpec(
                                question, delegated, onBranch, resolvedModel, subagentDepth, sessionId, turnId,
                                userId, branch.branchBrief(sessionId, question, subagentDepth), guestMode, runner));
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
            // 父轮交代（P3a 分支继承父历史）：主智能体派活时只传后半句是跑不动的——
            // "把上面那段写成脚本"里没有"上面那段"，分支就只能检索到一堆无关东西，回报回来是废的。
            // 同时把"这一轮没有人可问"说在前面：提问卡与任务清单的工具在分支轮压根不挂
            // （判据见 ToolMountContext#turnAllows），不说明的话模型会写出"我先问你一个问题"却没有工具可用。
            if (branchTurn && parentBrief != null && !parentBrief.isBlank()) {
                system.append("\n\n【上游交代】你是被主智能体派出来办这件事的子智能体，它那边的上下文如下")
                        .append("（用来理解要做什么，不要把它的原话抄进你的回报）：\n").append(parentBrief)
                        .append("\n这一轮没有用户可以对话：缺信息就按资料里最合理的默认做完，")
                        .append("把你的假设写在结论里，不要停下来等回答。");
            }
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
            // 人机协同三入口（计划卡/任务清单/提问卡）的分工指引：与无知识库分支共用同一装配（见 humanLoopGuide）
            system.append(humanLoopGuide(guestMode, agent, resolvedModel, emitter, branchTurn));
            system.append(consultGuide(guestMode, agent, branchTurn));
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
            if (skillExposed(guestMode, agent, userId)) {
                // 智能体级筛选：skills 为 null → 注入全部启用技能；空串 → 一个都不注入；逗号串 → 只注入这些。
                // 能不能注入由 skillExposed 判（与 readSkill 的挂载同一份判据，实现在 SkillToolProvider），
                // 不会出现「清单里列出了技能、却没有读它的工具」的矛盾状态。
                Set<String> skillRefs = agent == null ? null : scopeOf(agent.getSkills());
                ResourceRefs.Scope skillScope = ResourceRefs.resolve(skillRefs, userId);
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
                        runtime.addDegradation(degradations, degradedCodes, "agentSkillNotMine",
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
                        runtime.addDegradation(degradations, degradedCodes, "agentSkillUnavailable",
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
            // 全循环分支的回报段（P3a）：这一段里的引用与图片编号此刻还是<b>子轮自己的</b>，
            // 先以 ⟦b<支>:r<号>⟧ / ⟦b<支>:i<号>⟧ 占位入位（长度与最终形态接近，所以能按现长计入预算），
            // 等主链路资料填充完、父轮编号定了，再就地替换（见 branch.resolveBranchReportRefs）。
            // 为什么不能直接拼子轮原文：子轮的 [3] 与父轮的 [3] 指的是两份不同资料，
            // 拼起来就是一个指向错误来源的引用——用户点开看到的是别处的内容。
            int subReportStart = -1;
            int subReportEnd = -1;
            if (subOutcome != null && !subOutcome.fulls().isEmpty()) {
                String reports = branch.branchReportsText(subOutcome.fulls());
                if (!reports.isBlank()) {
                    system.append("\n\n【子智能体回报】以下是它替你办完的一件事，其中结论的来源已并入本轮参考资料：\n")
                            .append(reports);
                    subReportStart = system.length() - reports.length();
                    subReportEnd = system.length();
                    stageMs.put("subagent", subOutcome.elapsedMs());
                }
            }
            int partOtherEnd = system.length();

            // 3. 预算先行：历史装配与知识块填充共用同一预算（历史按压缩阈值占一部分，其余留给检索资料）
            CtxBudget ctxBudget = resolveContextBudget(resolvedModel, requestedContextWindow, degradations, degradedCodes);
            int budget = ctxBudget.budget();

            // 4. 对话历史：预算驱动全量带入——近期原样 + 更早轮次滚动摘要（替代旧的「按轮数 + 单条截断」）
            var historyBundle = historyCompaction.assemble(sessionId, resolvedModel, budget, degradations, degradedCodes);
            historyCompressedTurns = historyBundle.compressedTurns();
            String historySummaryBlock = historyCompaction.summaryBlock(historyBundle.summaryText());
            if (!historySummaryBlock.isEmpty() || !historyBundle.recentText().isEmpty()) {
                system.append("\n\n对话历史：\n");
                if (!historySummaryBlock.isEmpty()) system.append(historySummaryBlock);
                system.append(historyBundle.recentText());
            }

            // 图片说明拼入问题（仅"模型不支持读图"时有值：诚实说明图片不可见，防模型瞎猜；
            // 直读时无文本，图片本体以 image_url 部件随消息下发）
            StringBuilder userQuestion = steerAwareQuestion(question, fromSteer);
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
                    runtime.addDegradation(degradations, degradedCodes, "rerankUnavailable",
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
                Matcher matcher = TurnState.IMG_PLACEHOLDER_PATTERN.matcher(text);
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
                    runtime.addDegradation(degradations, degradedCodes, "contextTruncated",
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

            // 全循环分支的编号回填（P3a）：此刻父轮引用表已定稿，把回报段里的子轮编号换成父轮的，
            // 并把父轮资料表里确实没有的那几块注册进来（origin=SUBAGENT），让"子智能体查到的"也能点开看来源。
            // 放在这里而不是编排处：编排时父轮编号还不存在，先编必然撞号。
            if (subReportStart >= 0 && subOutcome != null) {
                String resolved = branch.resolveBranchReportRefs(system.substring(subReportStart, subReportEnd),
                        subOutcome.fulls(), sources, imgIndex, imgDescIndex, fileNameMap, scopeDocIds,
                        degradations, degradedCodes);
                system.replace(subReportStart, subReportEnd, resolved);
                partOtherEnd = system.length();   // 容量面板的「其他」段边界随替换长度移动
                log.info("[SUBAGENT] 回报段编号回填完成：父轮引用 {} 条，长度 {}→{}",
                        sources.size(), subReportEnd - subReportStart, resolved.length());
            }

            // 3.5 检索状态行（豆包式，回答上方常驻）：搜索 N 个关键词，参考 M 段资料
            // 只展示主词元（jieba 有效词），2-gram/4-gram 子词元仅参与召回、不展示给用户
            List<String> searchTerms = keywordExtractor.extractMain(retrievalQuery);
            String retrievedJson = JSON.toJSONString(Map.of(
                    "keywords", searchTerms.size(), "refs", docNo - 1, "terms", searchTerms));
            runtime.sendSseEvent(emitter, "retrieved", retrievedJson, sessionId);

            // 4. SSE 先发图片 URL 列表（编号顺序，生产开启鉴权时动态签名）
            if (!imgIndex.isEmpty()) {
                List<String> signedUrls = imgIndex.values().stream().map(imageUrlSigner::signUrl).toList();
                runtime.sendSseEvent(emitter, "image", JSON.toJSONString(signedUrls), sessionId);
            }

            String user = context.length() == 0
                    ? userQuestion.toString()
                    : userQuestion + "\n\n参考资料：\n" + context;

            // 5. 异步流式生成（缓冲过滤 <related> 块：跨 token 分割也能正确剥离，前端不会看到标签原文）
            // 生成前最后一道短路检查：流式回答是最长成本段，断开即不再发起（生成中断开由 doOnNext 发送失败取消订阅）
            if (runtime.turnAborted(sessionId, emitter)) {
                log.info("[SSE] 客户端断开或已停止，终止本轮问答（生成前）: session={}", sessionId);
                return;
            }
            runtime.sendSseEvent(emitter, "stage", "正在生成回答…", sessionId);
            TurnState st = new TurnState(sessionId, question, userId, emitter,
                    imgIndex, imgDescIndex, sources, userImgs, startTime, queryForLog, thinkingHolder,
                    degradations, degradedCodes, retrievedJson, turnDeps);
            st.refs.docFileNames = fileNameMap; // 工具命中注册来源时取文件名（悬浮提示/引用弹窗展示用）
            st.refs.docMetaCache = documentMetaCache; // 映射覆盖不到的文档（工具本轮首次命中）按需补查
            st.channel.heartbeat = preHeartbeat; // 前置心跳句柄移交（buildAnswerStream 的 runtime.startRunHeartbeat 幂等复用，终态照旧停止）
            st.tools.toolScopeKbIds = scopeKbIds; // 精确检索工具与主链路同库界（库隔离）
            st.tools.toolScopeDocIds = scopeDocIds;
            st.model = resolvedModel; // 本轮生效模型（会话覆盖 > 个人默认）
            st.deepThink = useDeepThink; // 归一后的深度思考（按生效模型能力 + 用户开关）
            st.reasoningLevel = thinkLevel; // 本轮思考强度档位（请求级优先，null=只有开关无强度）
            st.guestMode = guestMode; // 游客分享会话：工具只保留知识检索与内置项（见 enabledToolCallbacks）
            st.replaceMessageId = replaceMessageId; // 重新生成：落库前软删被替换的旧回答
            st.userMessageId = userMessageId; // 本轮用户消息 ID（编辑重发后前端挂切换器/再编辑用）
            // 本轮强制咨询的助手（@ 换芯后的可见标记，随 done 下发；不再是"谁作答"的归属）
            st.delegatedAgentId = forcedConsult != null && !consultSelf ? forcedConsult.getId() : null;
            st.delegatedAgentName = forcedConsult != null && !consultSelf ? forcedConsult.getName() : null;
            applyPermissionTier(agent, st); // 权限档位（P2）：沙盒执行档 + 生效的工具执行确认档位
            st.tools.maxToolSteps = resolveMaxToolSteps(agent, subagentDepth); // 步数上限（智能体覆盖 > 全局；分支逐层减半）
            // 智能体咨询（P5 agent-as-tool）：把"我是谁、在第几层、用哪个模型、谁在问"打包成一份按轮快照。
            // 工具回调跑在弹性线程上，拿不到这些局部量，也不许去读任何跨轮状态（与 P1 删 ThreadLocal 同一条理由）。
            st.tools.consultCtx = new com.wenqu.ai.agent.subagent.SubAgentConsultService.Ctx(
                    sessionId, turnId, userId, resolvedModel, guestMode, subagentDepth,
                    agent == null ? "" : agent.getId(),
                    // @ 强制咨询过的那位：工具侧据此拒绝同一轮再开一条相同分支
                    forcedConsult != null && !consultSelf ? forcedConsult.getId() : "",
                    // 咨询跑通即广播（id, 名称）：前端那条「本轮咨询了谁」的徽标要活着长出来，
                    // 不能等整轮 done——用户盯着转圈的这两三十秒里就该看见它在问谁
                    (consultedId, consultedName) -> runtime.sendSseEvent(st.channel.emitter, "agent_consulted",
                            JSON.toJSONString(Map.of("id", consultedId, "name", consultedName)), st.sessionId));
            st.branchTurn = branchTurn;   // 子智能体分支：「要有人在场」的工具不挂（见 ToolMountContext#turnAllows）
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
            // 合并主流程已记录的分段（改写 / 检索），后续生成与自检由流回调继续写入 st.channel.stageMs
            st.channel.stageMs.putAll(stageMs);
            // 计划模式（人在回路）：先产出执行计划等用户批准，批准后的计划注入本轮 system 再进生成。
            // 挂起点在 st 创建之后：断线后台续跑/人工等待不计费/刷新恢复全部继承 askUser 的既有语义。
            // 用户没开计划开关时，按消息意图自动开启：识别命中=本轮按计划模式走（卡片标「自动开启」）
            boolean planRound = planMode;
            if (!planRound && plan.awaitIntent(planIntentFuture)) {
                planRound = true;
                st.planAuto = true;
                log.info("[PLAN] 意图识别命中，本轮自动开启计划模式: session={}", sessionId);
            }
            if (planRound) {
                String planQuestion = question + (imgNote == null || imgNote.isBlank() ? "" : imgNote)
                        + (attachmentText.isBlank() ? ""
                        : "\n\n（用户上传了 " + preparedAtts.size() + " 个附件，其内容将在执行阶段提供）");
                String gate = plan.approvalGate(st, agent, rolePart, planQuestion, skills,
                        plan.refsDigest(sources), system, planHooks);
                if (gate == null) {
                    // 拒绝/超时：整轮就此终止（plan_cancelled 让前端卡片定格「未批准」）；
                    // 「继续对话」取代：本版作废（plan_superseded，随后用户那条意见消息会接上）。
                    // 两者都要补标准 done 收尾——SSE 读循环「流关闭且无 done」会按『连接被提前关闭』误报中断错；
                    // 收尾轮没有流式回答可挂计划卡：先补落一条收尾助手消息（含 plan 快照，刷新后卡不丢）
                    if (st.planSuperseded) {
                        persistPlanSuperseded(st);
                        runtime.sendSseEvent(emitter, "plan_superseded", "{}", sessionId);
                    } else {
                        persistPlanRejected(st);
                        runtime.sendSseEvent(emitter, "plan_cancelled", "{}", sessionId);
                    }
                    Map<String, Object> cancelled = new LinkedHashMap<>();
                    cancelled.put("sources", List.of());
                    cancelled.put("related", List.of());
                    cancelled.put("degradations", degradations);
                    if (st.planSuperseded) cancelled.put("planSuperseded", true);
                    else cancelled.put("planCancelled", true);
                    // 计划被拒/超时这条 done 也必须挂插话：漏了它，插进的话就在这个分支里静默消失
                    runtime.attachLeftoverSteers(cancelled, st, sessionId);
                    runtime.sendSseEvent(emitter, "done", JSON.toJSONString(cancelled), sessionId);
                    runtime.completeEmitter(emitter);
                    return;
                }
            }
            st.channel.disposableRef.set(buildAnswerStream(system.toString(), user, st, agent));

            // 前端断开/终态时停止生成。整轮截断（原「SSE 超时」）已由 runtime.startTurnWatchdog 承担：
            // 容器级超时不可续期且会把人工答题时间算进去，emitter 不再设容器超时
            emitter.onCompletion(() -> { if (!st.keepRunningWithoutChannel()) st.disposeSafe(); });
            emitter.onError(t -> { if (!st.keepRunningWithoutChannel()) st.disposeSafe(); });

        } catch (Exception e) {
            if (runtime.turnStopRequested(sessionId)) {
                // 用户叫停后，被解开的等待与被掐断的流会以异常形态冒到这里——这是预期收场，
                // 不能报「系统处理异常」（那等于把用户的操作说成平台故障）
                log.info("[SSE] 本轮已被用户停止，收尾异常按预期吞掉: session={} {}", sessionId, e.getMessage());
                runtime.completeEmitter(emitter);
                return;
            }
            log.error("Chat error", e);
            runtime.sendSseEvent(emitter, "error", "系统处理异常，请稍后重试", sessionId);
            runtime.completeEmitter(emitter);
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
                                                                                          TurnState st) {
        java.util.List<org.springframework.ai.tool.ToolCallback> callbacks = new ArrayList<>(4);
        java.util.Set<String> sensitiveTools = new java.util.HashSet<>();
        if (!configService.getBoolean("tool.enabled")) {
            st.tools.sensitiveToolNames = sensitiveTools;
            return callbacks;
        }
        // 挂不挂由各族自己判（绞杀式中）：内置/检索/联网/产物/技能/任务清单六族已迁到注册中心，
        // 装配处只剩「问每个族本轮要挂什么」。检索-反思循环的强制暴露、智能体三态覆盖、
        // 具体项白名单（agent.builtinTools）、游客可见性都落在 provider 与描述符的 guestAllowed 上——
        // 迁移前这几条在游客分支与常规分支各抄了一份，于是「管理员收紧一处」时另一处照旧放行。
        ToolMountContext mc = toolMountContext(agent, userId, st);
        java.util.Set<String> mcpNames = new java.util.HashSet<>();
        for (ToolFamilyProvider p : toolRegistry.providersInFamilyOrder()) {
            java.util.List<org.springframework.ai.tool.ToolCallback> mounted = p.callbacks(mc);
            // 「本轮哪些工具来自 MCP」在这里按族收集（MCP 结果要注册引用来源，tool.mcpCiteEnabled），
            // 不让 provider 反向写问答侧的状态对象
            if (p.family() == ToolFamily.MCP) {
                for (org.springframework.ai.tool.ToolCallback cb : mounted) {
                    mcpNames.add(cb.getToolDefinition().name());
                }
            }
            callbacks.addAll(mounted);
        }
        if (!mcpNames.isEmpty()) st.tools.mcpToolNames = java.util.Set.copyOf(mcpNames);
        // 八族全部由 provider 装配（P1 seam 化的装配面到此收口）：游客收窄看描述符 guestAllowed、
        // 审批=off 的整族禁用看 ToolFamily.blockedWhenApprovalOff、开关归属与具体项白名单看各族 exposes，
        // MCP 的个人资产语义看 McpToolProvider。这里不再留任何一族的 if——迁移时最贵的错就是
        // 「provider 加一段、老分支忘删」（第二刀实际发生过 webSearch 双挂，靠去重守卫喊出来）。
        if (st.guestMode) {
            // 保留一条可 grep 的游客标记（只挂了什么由下一行那条公共日志逐字列出）
            log.info("[TOOL] 游客分享会话：只挂描述符声明 guestAllowed 的能力");
        }
        finishMounts(callbacks, sensitiveTools, st);
        return callbacks;
    }

    /**
     * 装配收尾（游客与常规两条路径共用；迁移前收尾只在常规路径跑、游客分支自己 log 一句就 return，
     * 于是游客拿不到 DSML 剥离白名单——那条差异不是设计，是复制分支时漏带的）。
     * 做三件事：同名工具去重、本轮审批名单、DSML 剥离白名单 + 挂载日志。
     * <p>去重是族迁移期的安全网：一族同时被 provider 和残留的老分支挂上时，模型的工具表里会出现两个
     * 同名项——Spring 不报错、各厂商行为不一，症状是「模型偶尔对着同一个工具反复调」，从回答里几乎
     * 看不出成因，所以宁可在这里喊出来。
     */
    private void finishMounts(java.util.List<org.springframework.ai.tool.ToolCallback> callbacks,
                              java.util.Set<String> sensitiveTools, TurnState st) {
        java.util.Set<String> seenNames = new java.util.LinkedHashSet<>();
        java.util.List<String> duplicated = new ArrayList<>(1);
        callbacks.removeIf(cb -> {
            String n = cb.getToolDefinition().name();
            if (seenNames.add(n)) return false;
            duplicated.add(n);
            return true;
        });
        if (!duplicated.isEmpty()) {
            log.error("[TOOL] 同名工具被重复挂载，已去重（族迁移期装配口径重叠，请把老分支删干净）: {}", duplicated);
        }
        // 本轮「有副作用工具」名单由注册中心的描述符判定，不再在装配处逐个 add：
        // 沙盒族恒需确认、联网搜索看 webSearch.requireApproval、内置/检索/产物/技能/清单不需要；
        // **没在注册中心登记的工具一律需要确认**（当前即 MCP 外部工具）——外部 server 的工具名前缀是
        // 用户自己起的 clientInfo 名，靠命名规律识别会漏，漏一次的结果是跳过审批，不是少问一句。
        // 此前这条规则在装配处 add 一遍、清单页硬编码一遍、前端再抄一遍，三份各自演进的直接后果
        // 是新工具的审批属性看不出来、中文名丢失。
        for (org.springframework.ai.tool.ToolCallback cb : callbacks) {
            String n = cb.getToolDefinition().name();
            if (toolRegistry.requiresApproval(n, configService::getBoolean)) {
                sensitiveTools.add(n);
            }
        }
        // 记录本轮"有副作用"的工具名单（沙盒/MCP）：智能体 toolApprovalMode=ask 时执行前需用户确认
        st.tools.sensitiveToolNames = sensitiveTools;
        // DSML 剥离白名单：本轮真实下发的工具名（小写）。部分模型在 Function Calling 之外还会把
        // 工具调用以 <searchKnowledge><parameter …></parameter></searchKnowledge> 的 DSML 文本再输出
        // 一遍（工具卡片已由 native tool_calls 生成，文本那份是重复产物），不剥离会原样漏给用户。
        // 排除与 HTML/XML 标准标签同名的工具（MCP 工具名由外部 server 自定，可能叫 link/form/table）：
        // 那类名字按标签剥会误伤模型正常写的 HTML/XML 正文，宁可漏剥离也不能吃掉用户内容。
        st.tools.dsmlToolNamesLc = callbacks.stream()
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
    }

    /**
     * 开轮解析本轮权限档位（P2）：一次算完，落 {@code TurnState} 供装配与守卫共读。
     * <p>审批模式在这里一并改写：智能体显式设过就以它为准，没设过才用档位的建议值
     * （常规档的建议值是 null=不动它，所以未选档的存量智能体这一行与改动前逐字相同）。
     */
    private void applyPermissionTier(Agent agent, TurnState st) {
        st.tools.permissionPolicy = resolvePermissionPolicy(agent, st.guestMode,
                (code, message) -> runtime.addDegradation(st.degradations, st.degradedCodes, code, message));
        st.tools.toolApprovalMode = st.tools.permissionPolicy.approvalMode();
        log.info("[PERM] 本轮权限档位: {} session={}", st.tools.permissionPolicy.describe(), st.sessionId);
    }

    /**
     * 档位解析（装配判据与执行守卫的唯一入口）。
     * <p>「档位 → 智能体的哪一列」这条映射留在问答侧，与 {@link #agentFlagFor} 同一口径：
     * 通用策略层不该认识业务实体。
     */
    private com.wenqu.ai.agent.permission.ExecutionPolicy resolvePermissionPolicy(
            Agent agent, boolean guestMode, java.util.function.BiConsumer<String, String> onClamp) {
        return permissionPolicyResolver.resolve(
                agent == null ? null : agent.getPermissionPreset(),
                agent == null ? null : agent.getToolApprovalMode(),
                guestMode,
                agent == null ? null : agent.getToolDeny(),
                onClamp);
    }

    /**
     * 本轮的工具装配上下文：把智能体配置、全局开关与游客态交给各族自己判挂不挂。
     * <p>「族 → c_ai_agent 的哪一列」这条映射留在问答侧：Agent 是业务实体，通用装配层不该认识它，
     * 否则下一族想加开关时得先改通用层。
     */
    private ToolMountContext toolMountContext(Agent agent, String userId, TurnState st) {
        return mountContext(agent, userId, st.guestMode, st.reflectiveRetrieval, st.branchTurn,
                (code, message) -> runtime.addDegradation(st.degradations, st.degradedCodes, code, message),
                st.tools.permissionPolicy);
    }

    /**
     * 本轮的工具装配判据上下文。
     * <p>提示词侧（能力分工段、已批准计划落成清单的那句提示）也走这里问「这个工具本轮挂不挂」——
     * 两处必须同一份判据，否则会出现「提示词教模型用某个工具，工具表里却没有它」。
     * <p>提示词侧只问判据、不装配回调：userId 传空串不影响任何判据，降级出口用 no-op
     * （今天只有 MCP 族会报降级，而它只在装配回调时被调用）。
     */
    private ToolMountContext mountContext(Agent agent, String userId, boolean guestMode, boolean reflective) {
        return mountContext(agent, userId, guestMode, reflective, false, (code, message) -> {
        }, null);
    }

    /**
     * @param turnPolicy 本轮已解析的权限档位（问答侧传 {@code st.tools.permissionPolicy}，与守卫读同一份）；
     *                   null = 现算（提示词侧那条路径没有本轮状态对象）
     * @param branchTurn 本轮是否为子智能体分支（P3a）：分支里「要有人在场」的工具（提问卡、任务清单）
     *                   不进工具表，判据与装配同一条 {@link ToolMountContext#turnAllows}
     */
    private ToolMountContext mountContext(Agent agent, String userId, boolean guestMode, boolean reflective,
                                          boolean branchTurn,
                                          java.util.function.BiConsumer<String, String> degradationSink,
                                          com.wenqu.ai.agent.permission.ExecutionPolicy turnPolicy) {
        return new ToolMountContext() {
            @Override
            public String userId() {
                return userId == null ? "" : userId;
            }

            @Override
            public boolean guestMode() {
                return guestMode;
            }

            @Override
            public boolean branchTurn() {
                return branchTurn;
            }

            @Override
            public boolean reflectiveRetrieval() {
                return reflective;
            }

            @Override
            public boolean gateOn(String gateKey) {
                return configService.getBoolean(gateKey);
            }

            @Override
            public boolean masterOn() {
                return configService.getBoolean("tool.enabled");
            }

            @Override
            public Integer agentFlag(ToolFamily family) {
                return agentFlagFor(family, agent);
            }

            @Override
            public java.util.Set<String> agentSelection(ToolFamily family) {
                return agentSelectionFor(family, agent);
            }

            @Override
            public boolean approvalOff() {
                // 工具执行确认=off（禁用）：不给模型沙盒/MCP 这类有副作用工具。UI 与手册均按此语义承诺，
                // 此前仅 ask 分支有实现、off 静默等同 auto——按承诺补齐。联网搜索不受本档管辖（族上未标）。
                return agent != null && "off".equalsIgnoreCase(agent.getToolApprovalMode());
            }

            @Override
            public boolean approvalAsk() {
                return agent != null && "ask".equalsIgnoreCase(agent.getToolApprovalMode());
            }

            @Override
            public com.wenqu.ai.agent.permission.ExecutionPolicy permissionPolicy() {
                if (turnPolicy != null) return turnPolicy;
                return resolvePermissionPolicy(agent, guestMode, degradationSink);
            }

            @Override
            public void degrade(String code, String message) {
                degradationSink.accept(code, message);
            }
        };
    }

    /** 族 → 智能体的「具体项」选择（null=未指定，本族全挂）；只有内置与 MCP 有这一层 */
    private java.util.Set<String> agentSelectionFor(ToolFamily family, Agent agent) {
        if (agent == null) return null;
        return switch (family) {
            case BUILTIN -> scopeOf(agent.getBuiltinTools());
            case MCP -> scopeOf(agent.getMcps());
            default -> null;
        };
    }

    /** 族 → 智能体级三态列（null = 本族没有智能体级开关，跟随全局） */
    private Integer agentFlagFor(ToolFamily family, Agent agent) {
        if (agent == null) return null;
        return switch (family) {
            case BUILTIN -> agent.getToolBuiltin();
            case KNOWLEDGE -> agent.getToolKnowledge();
            case WEBSEARCH -> agent.getToolWebsearch();
            case ARTIFACT -> agent.getToolArtifact();
            case SKILL -> agent.getToolSkill();
            case SANDBOX -> null;   // 沙盒暂无智能体级列（c_ai_agent 未建 toolSandbox，见 SandboxTools 类注释）
            // 智能体咨询（P5）同理：c_ai_agent 没有 toolSubagent 列，本族只受全局
            // tool.subagentConsult.enabled 与工具总闸管。要按人/按智能体收口的话，加列要走配置页 schema，
            // 别在这里塞"看起来能配"的 null 让管理员以为勾了就没效果。
            case SUBAGENT -> null;
            case TODO, MCP -> null;
        };
    }

    /**
     * 工具调用管线接入口（P1 seam 化第一刀）。
     * <p>
     * 迁移前这里是两百行匿名内部类：叫停闸、步数闸、审批等待、上下文注入、状态观测、插话附送全部
     * 内联，并且直读 TurnState 的私有字段——于是工作流的 mcp/skill 节点想复用其中任何一道闸
     * 都无从下手（那两条路现在是 {@code target.call(json)} 裸调，绕开审批、绕开预算、绕开工具卡与
     * 事件账本）。守卫链与管线本体已迁到 {@code com.wenqu.ai.agent.tool}，这里只剩一份
     * 「本轮问答状态 → 工具执行层契约」的适配。
     * <p>
     * 本期只接问答流水线这一条装配路径，行为与迁移前逐项对齐；其余旁路留待后续族迁移时接同一条管线。
     */
    private org.springframework.ai.tool.ToolCallback[] instrumentTools(
            java.util.List<org.springframework.ai.tool.ToolCallback> rawTools, TurnState st) {
        return toolPipeline.wrap(rawTools, toolTurnContext(st));
    }

    // ==================== P3b：沙盒脚本的子调用回程 ====================

    /**
     * 本轮的工具执行上下文：把 TurnState 的问答态映射成 {@link ToolTurnContext} 声明的
     * 十余件事。
     * <p>
     * <b>身份快照在本方法（入口线程）捕获</b>：工具实际执行在 Reactor boundedElastic 线程上，
     * RequestUser 是流水线线程装载的 ThreadLocal，跨线程读不到会回落 anonymous——对检索类工具
     * 这不是安全降级而是功能损坏（库门恒假 → 召回 0 并耗尽工具预算，线上实测过）。
     * 放在执行点捕获同样无效，那里已经在弹性线程上。见 {@link #callWithRetry}。
     */
    private ToolTurnContext toolTurnContext(TurnState st) {
        final com.wenqu.ai.util.RequestUser.Snapshot identity = com.wenqu.ai.util.RequestUser.snapshot();
        // 本轮检索参数覆盖（库级 + 智能体级）与个人配置覆盖装在同一个 ThreadLocal 盲区里：
        // 不带进工具线程，配置页上那份 retrieval.* 对 searchKnowledge 一律静默失效，
        // 实测表现是「主链路命中并引用、工具侧恒空」——同一轮里两套检索参数，谁都没报错。
        final Map<String, String> cfgOverrides = configService.currentOverrides();
        final Map<String, String> userOverrides = configService.currentUserOverrides();
        // 本轮基础上下文：会话/用户归属 + askUser 执行器 + 任务清单写入器 + 大输出外溢信箱
        final Map<String, Object> baseCtx = java.util.Collections.unmodifiableMap(toolContext(st));
        // 按族的上下文注入：本轮落点随本次调用传递，工具侧不再读任何跨调用的静态状态
        // （迁移前这三处分别是 setSourceRegistrar/setKbScope/setReflective、setSink，
        //  以及沙盒那两个 per-call 回调——全部收进这里，作用域=本次调用）
        final Map<ToolFamily, ToolEnrichment> enrichments = Map.of(
                ToolFamily.KNOWLEDGE, inv -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    // 引用注册器：命中块注册进当前流 sources 续编引用编号，工具文本改【引用N】提示
                    // 模型按编号标注，前端角标悬浮/引用弹窗因此可溯源
                    m.put(KnowledgeRetrievalTool.CTX_SOURCE_REGISTRAR, new KnowledgeRetrievalTool.SourceRegistrar() {
                        @Override
                        public KnowledgeRetrievalTool.SourceRegistrar.Registration register(
                                HybridRetrievalService.Hit h, String snippet) {
                            return st.refs.registerToolSource(h, snippet);
                        }

                        @Override
                        public void degrade(String code, String msg) {
                            // 工具链路（重排不可用等）可写回本轮 degradations，与主链路同一份列表、同一展示位
                            runtime.addDegradation(st.degradations, st.degradedCodes, code, msg);
                        }
                    });
                    // 检索范围：工具与主链路同库界，不得越过智能体知识库绑定去检索其他库
                    m.put(KnowledgeRetrievalTool.CTX_KB_SCOPE,
                            new KnowledgeRetrievalTool.KbScope(st.tools.toolScopeKbIds, st.tools.toolScopeDocIds));
                    m.put(KnowledgeRetrievalTool.CTX_REFLECTIVE, st.reflectiveRetrieval);
                    return m;
                },
                // 联网搜索的落点=本轮引用池（刀 0 第二步后 sink 由 TurnSourceRegistry 实现，
                // 不再是整个状态袋——工具拿不到本轮的任何其它字段，也就没法往别处写）
                ToolFamily.WEBSEARCH, inv -> Map.of(WebSearchTools.CTX_SINK, st.refs),
                ToolFamily.SANDBOX, inv -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    // 本轮权限档位（P2）：沙盒工具据此判定这次写入是否落在允许区内。走上下文而不是让
                    // 工具去查配置——档位是「这一轮」的属性，同一会话的两轮可以不同档
                    m.put(SandboxTools.CTX_SANDBOX_POLICY, st.tools.permissionPolicy);
                    // run_code 的子调用回程（P3b）：只在这一轮真挂了 run_code 时才有内容，
                    // 且必须在执行侧现取——本轮的可调用清单在 st.tools.subCallTools 里，装配期还没建好
                    boolean runCode = SandboxTools.RUN_CODE_TOOL_NAME.equals(inv.toolName());
                    if (runCode) subCall.startRunBoard(st);
                    if (runCode) {
                        m.put(SandboxTools.CTX_RUN_CODE_BRIDGE, subCall.runCodeBridge(st));
                    }
                    // 输出增量回调：execute 检测到它即走流式执行（后台脱离+轮询），增量经
                    // recordToolOutput 转 SSE，前端「看着它跑」。
                    // <p>run_code 必须走同一条：它此前不在这份名单里，于是一段脚本从起跑到结束
                    // 屏幕上一个字都没有——扇出 30 份资料时那就是两三分钟的纯转圈，
                    // 用户的读法是"卡死了"，然后点停止把活儿掐掉。
                    if (SandboxTools.STREAMING_TOOL_NAME.equals(inv.toolName()) || runCode) {
                        m.put(SandboxTools.CTX_OUTPUT_SINK,
                                (java.util.function.Consumer<String>) delta ->
                                        recordToolOutput(st, inv.toolName(), delta));
                    }
                    // 基础设施故障信箱对全部沙盒工具注入：沙盒不可用时工具不抛异常（抛出会掐断整轮流、
                    // 连带已生成正文与全部工具记录一起落空），而是把失败文本回给模型 + 经此信箱上报，
                    // 让这条记录落成 error 而不是绿色对勾的 done。信箱挂在本次调用上，同名工具并发也各是各的
                    m.put(SandboxTools.CTX_INFRA_ERROR_SINK,
                            (java.util.function.Consumer<String>) msg -> inv.recordFailure(msg));
                    return m;
                });
        return new ToolTurnContext() {
            @Override
            public String sessionId() {
                return st.sessionId;
            }

            @Override
            public String userId() {
                return st.userId;
            }

            @Override
            public String turnId() {
                return st.turnId;
            }

            @Override
            public boolean userStopped() {
                return st.userStopped;
            }

            @Override
            public int maxToolSteps() {
                return st.tools.maxToolSteps;
            }

            @Override
            public int stepsTaken() {
                return st.tools.toolStepCount.get();
            }

            @Override
            public void takeStep() {
                st.tools.toolStepCount.incrementAndGet();
            }

            @Override
            public String approvalMode() {
                return st.tools.toolApprovalMode;
            }

            @Override
            public boolean requiresApproval(String toolName) {
                return st.tools.sensitiveToolNames != null && st.tools.sensitiveToolNames.contains(toolName);
            }

            @Override
            public long approvalTimeoutMs() {
                // 必须点名取外部服务的方法：匿名类里裸写 approvalTimeoutMs() 解析到的是本方法自己，
                // 审批闸一读等待上限就无限递归把工具线程栈打爆（ApprovalGate 调它即在「每次问我」档必炸）
                return hitl.approvalTimeoutMs();
            }

            @Override
            public ToolApprover approver() {
                // 子智能体分支（P3a）没有可应答通道：那条临时会话没人开着，审批卡发不出去、
                // 铃铛会指向一条跑完就删的会话，而工具线程要白等 chat.approvalTimeoutMs（默认 120s）。
                // 返回 null 走的正是 ApprovalGate 已写明的「问不到就直接拒，理由写明无人可确认」那条，
                // 覆盖装配挡不住的 MCP 动态工具（它们没有静态描述符，族级裁剪够不着名字）。
                if (st.branchTurn) return null;
                return (inv, timeoutMs) -> requestToolApproval(st, inv, timeoutMs);
            }

            @Override
            public com.wenqu.ai.agent.permission.ExecutionPolicy permissionPolicy() {
                return st.tools.permissionPolicy;
            }

            @Override
            public Map<String, Object> baseToolContext() {
                return baseCtx;
            }

            @Override
            public Map<ToolFamily, ToolEnrichment> enrichments() {
                return enrichments;
            }

            @Override
            public String execute(org.springframework.ai.tool.ToolCallback raw, String arguments,
                                  Map<String, Object> context, ToolInvocation inv, int[] attemptsOut) {
                return callWithRetry(raw, arguments,
                        new org.springframework.ai.chat.model.ToolContext(context),
                        inv.toolName(), attemptsOut, identity, cfgOverrides, userOverrides);
            }

            @Override
            public void observe(ToolInvocation inv, String status, String resultOrError) {
                boolean starting = "start".equals(status);
                recordToolStatus(st, inv.toolName(), inv.arguments(), status, resultOrError,
                        starting ? 0 : inv.elapsedMillis(), starting ? 1 : inv.attempts(), inv.denied());
            }

            @Override
            public void onOutput(ToolInvocation inv, String delta) {
                recordToolOutput(st, inv.toolName(), delta);
            }

            @Override
            public String decorateResult(ToolInvocation inv, String result) {
                // MCP 外部工具结果注册引用来源（tool.mcpCiteEnabled）：模型依据 MCP 内容作答时正文才有
                // 可点溯源的 [N]，否则是悬空角标（引用面板无对应条目）
                if (st.tools.mcpToolNames != null && st.tools.mcpToolNames.contains(inv.toolName())) {
                    return st.refs.registerMcpCitations(inv.toolName(), result);
                }
                return result;
            }

            @Override
            public String appendSteers(ToolInvocation inv, String result) {
                String steered = runtime.drainSteers(st);
                return steered.isEmpty() ? result : result + "\n\n" + steered.trim();
            }
        };
    }

    /**
     * 有副作用工具的执行确认（人在回路的问人那一段：落库审计、站内通知、SSE 审批卡、阻塞等待）。
     * <p>
     * 定性交给 {@link ToolApprover.Outcome}，不再是一个 boolean：拒绝、超时、无人可确认在迁移前都
     * 收敛成同一个 false，于是「审批卡没人点」与「用户点了拒绝」在日志和文案里分不开，
     * 用户读到的是一句含糊的「未批准」。
     * <p>
     * 阻塞等待有界（chat.approvalTimeoutMs，默认 120s）；超时/拒绝都<b>不代用户批准</b>，
     * 由守卫把原因回给模型，让它基于已有信息继续而不是反复重试。
     */
    private ToolApprover.Outcome requestToolApproval(TurnState st, ToolInvocation inv, long timeoutMs) {
        String name = inv.toolName();
        String toolInput = inv.arguments();
        String approvalId = inv.callId();
        // 挂起这张审批卡（P6 刀 1：落 PENDING 记录与登记唤醒句柄都在 HitlDecisionService 一次做完）。
        // 句柄是 AutoCloseable：离开作用域即摘登记，不会再出现「解了 future 却漏摘登记」那种半成品。
        try (var wait = hitl.openApproval(approvalId, st.sessionId, st.userId, st.turnId, name, toolInput)) {
            // 审批待决站内通知（旁路）：SSE 只能触达正开着这个会话页的人，人不在页面时铃铛是唯一可感知面。
            // 超时/裁决后通知不撤（留在列表里作审计痕迹，点了即已读）。
            notificationService.create(st.userId, com.wenqu.ai.model.Notification.TYPE_TOOL_APPROVAL,
                    "工具执行待确认：" + name,
                    "智能体正在请求执行有副作用工具「" + name + "」，请在会话中确认或拒绝（超时未确认按拒绝处理）。",
                    "session", st.sessionId, "tool:" + approvalId, approvalId);
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("approvalId", approvalId);
            req.put("tool", name);
            // 中文名来自注册中心：审批卡上写「execute」用户看不懂，而前端那份手抄映射对 MCP 动态
            // 工具和无声明工具一律失效，下发之后前端只兜底、不再是唯一来源
            putToolDisplayName(req, name);
            String args = toolInput == null ? "" : toolInput;
            req.put("args", args.length() > 2000 ? args.substring(0, 2000) + "…" : args);
            req.put("timeoutMs", timeoutMs);
            runtime.sendSseEvent(st.channel.emitter, "approval_required", JSON.toJSONString(req), st.sessionId);
            log.info("[TOOL] 等待用户确认: tool={} approvalId={} session={}", name, approvalId, st.sessionId);
            boolean approved;
            ToolApprover.Outcome timeoutOutcome = ToolApprover.Outcome.TIMED_OUT;
            TurnRegistry.beginHumanWait(st.channel.emitter);   // 用户确认时间不计入整轮机器预算（见 TurnDeadline）
            try {
                approved = wait.future().get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.ExecutionException ee) {
                approved = false;
                timeoutOutcome = ToolApprover.Outcome.REJECTED;
            } catch (java.util.concurrent.TimeoutException te) {
                approved = false;
                hitl.markApprovalResolved(approvalId, "TIMEOUT", st.userId);
                log.warn("[TOOL] 审批超时，按拒绝处理: tool={} session={}", name, st.sessionId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                approved = false;
                timeoutOutcome = ToolApprover.Outcome.CANCELLED;
            } finally {
                TurnRegistry.endHumanWait(st.channel.emitter);
            }
            if (!approved) {
                return timeoutOutcome;
            }
            log.info("[TOOL] 用户已批准: tool={} session={}", name, st.sessionId);
            return ToolApprover.Outcome.ALLOWED;
        }
    }

    /** 工具展示名下发：卡片名与悬停说明都取注册中心，未知工具（MCP 动态名）不下发空字段，前端用自己那份兜底 */
    private void putToolDisplayName(Map<String, Object> payload, String name) {
        toolRegistry.putDisplayFields(payload, name);
    }

    // ==================== 工具执行心跳 ====================

    private void stopRunHeartbeat(TurnState st) {
        // 心跳句柄的停法只有一份，在轮次运行时里（起也在那儿）
        runtime.stopHeartbeat(st);
        // 整轮收口时一并撤掉脚本面板的 ticker：中断/异常那几条路没有 run_code 终态记录，
        // 只靠"终态自停"会留下一个每秒空转的周期任务
        st.board.runBoardActive = false;
        subCall.stopRunBoardTicker(st);
    }

    // ==================== 工具调用瞬时故障重试 ====================
    // （刀 2 把心跳与存活看门狗本体搬进 ChatRunRuntime 之后，这个章节位上剩的是重试包装；
    //  标题按内容改直，别再用旧横幅误导"看门狗还在这里"）

    /**
     * 工具调用瞬时故障自动重试：失败后 500ms 重试一次（共至多 2 次），attempts 记录实际尝试次数。
     * 场景是沙盒容器冷启动、网络抖动这类瞬时故障；确定性错误（路径不存在等）多付一次 500ms 代价可接受
     * ——业务层的"错误输出"（如命令退出码非 0）不是异常，不会触发重试。模型看到的只有终态与 attempts。
     * 注意不声明 throws：ToolCallback.call 只抛运行时异常，包装 checked 会破坏调用方 precise-rethrow。
     */
    private String callWithRetry(org.springframework.ai.tool.ToolCallback cb, String toolInput,
                                 org.springframework.ai.chat.model.ToolContext toolContext,
                                 String name, int[] attempts,
                                 com.wenqu.ai.util.RequestUser.Snapshot identity,
                                 Map<String, String> overrides, Map<String, String> userOverrides) {
        // 工具执行发生在 Reactor boundedElastic 线程（Spring AI ToolCallAdvisor 对阻塞工具 subscribeOn），
        // 而 RequestUser 用裸 ThreadLocal 只在入口线程可见 → 工具内读身份会回落 anonymous。
        // 对检索类工具这不是安全降级而是功能损坏：loadVisibleKbIds() 判 anonymous 为「什么都读不到」
        // → 库门表达式恒假 → 融合召回 0（线上实测：主链路命中 31 块，工具侧恒空并耗尽 15 步工具预算）。
        // identity 由 instrumentTools 在入口线程捕获后传入——不能在执行点临时 snapshot()，
        // 那里已经在弹性线程上、捕获到的只会是 anonymous（实测修复无效）。
        // 本轮检索参数覆盖（ConfigService 的 OVERRIDE/USER_OVERRIDE 两个 ThreadLocal）同一个盲区、
        // 同一种损坏：库级/智能体级 retrieval.* 不带进来，工具就按全局参数检索，
        // 于是「主链路按 0.6 关键词权重命中、工具按 0.2 把它压出重排窗口」——两份参数同一个会话。
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
                // 本轮配置覆盖同样要显式带过来，并复原成「进入前的那一份」——putOverrides 是合并语义，
                // 所以先 clear 再装回 prev，直接 putOverrides(prev) 会把本轮的键留给下一个任务
                Map<String, String> prev = configService.currentOverrides();
                Map<String, String> prevUser = configService.currentUserOverrides();
                configService.putOverrides(overrides);
                configService.putUserOverrides(userOverrides);
                try {
                    return cb.call(toolInput, toolContext);
                } finally {
                    configService.clearOverride();
                    configService.clearUserOverrides();
                    if (!prev.isEmpty()) configService.putOverrides(prev);
                    if (!prevUser.isEmpty()) configService.putUserOverrides(prevUser);
                }
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
    /** 原文已外溢成功时挂在预览尾部的说明（前端据此显示「查看完整输出」，这句只兜住展开前的一眼） */
    private static final String SPILL_SUFFIX = "\n\n…（以上为预览，完整输出可点卡片下方「查看完整输出」读回）";

    /** 取出工具侧上报的外溢标识（按工具名匹配最早那条未消费的；没有返回 null） */
    private static String takeToolSpill(TurnState st, String name) {
        for (java.util.Iterator<String> it = st.toolSpills.iterator(); it.hasNext(); ) {
            String e = it.next();
            int bar = e.indexOf('|');
            if (bar > 0 && name.equals(e.substring(0, bar))) {
                it.remove();
                return e.substring(bar + 1);
            }
        }
        return null;
    }

    /** 超出展示上限<b>又没能留住原文</b>（外溢开关关闭或落盘失败）时的实话：说清楚少了多少，别装作完整 */
    private static String spillNote(String text) {
        if (text == null || text.length() <= TOOL_IO_MAX) return "";
        return "\n\n…（其余 " + (text.length() - TOOL_IO_MAX) + " 字未显示，且原文未能留存，请缩小范围重跑）";
    }

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
    private void recordToolOutput(TurnState st, String toolName, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        String brief = delta.length() > 4096 ? delta.substring(0, 4096) : delta; // 单片上限，防异常大块撑爆 SSE 帧
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", toolName);
        rec.put("delta", brief);
        String payload = JSON.toJSONString(rec);
        // 总线优先：接流的观众也要看着工具卡片转圈与实时输出滚动（快照给不了「正在跑」这一条）
        if (!chatRunBus.publishRaw(st.channel.emitter, "tool_output", payload, st.sessionId)) {
            try {
                st.channel.emitter.send(SseEmitter.event().name("tool_output")
                        .data("{\"type\":\"tool_output\",\"content\":" + payload
                                + ",\"sessionId\":\"" + st.sessionId + "\"}"));
            } catch (Exception e) {
                log.debug("[TOOL-OUTPUT] SSE 下发失败（客户端可能已断开）: {}", e.getMessage());
            }
        }
    }

    /**
     * 把锚点到 pos 之间的正文记为一个文本段（无新增正文则忽略）。工具/产物插入时间线前调用，
     * 使「正文 → 工具 → 正文」的交错顺序可被记录（只存区间，不复制正文）。
     */
    private void flushTimelineText(TurnState st, int pos) {
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
    private void flushTimelineProcess(TurnState st, int pos) {
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
    private void routeProcessText(TurnState st, String delta) {
        if (delta == null || delta.isEmpty()) return;
        st.processResponse.append(delta);
        flushTimelineProcess(st, st.processResponse.length());
        runtime.sendSseEvent(st.channel.emitter, "process", delta, st.sessionId);
    }

    /** 产物生成时刻：正文成段后插入产物段（下标指向本轮产物清单，与 artifacts 数组同源同序） */
    private void pushTimelineArtifact(TurnState st, int artifactIndex) {
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
    private List<Map<String, Object>> buildTimelineSnapshot(TurnState st, String answer,
                                                            int toolCount, int artifactCount) {
        return timelineSnapshot(st, answer == null ? 0 : answer.length(), toolCount, artifactCount, true);
    }

    /**
     * 时间线快照。<b>flush 是这个方法的唯一分岔</b>：
     * <ul>
     *   <li>{@code true}（终态落库/done）：把锚点之后尚未成段的正文/过程补成段并<b>推进锚点</b>——
     *       本轮到此结束，锚点没有后续可言；</li>
     *   <li>{@code false}（断线重连接流）：<b>绝不动锚点</b>。在实时跑着的一轮上推进锚点，等于在接流那一刻
     *       凭空多切一个文本段，把用户正在看的表格/代码块从中间劈开，而 done 之后又是另一份切法
     *       （实时视图与刷新视图本应同源）。尾段只在副本上补齐，用于让接流方看到「已经流出的那段正文」。</li>
     * </ul>
     */
    private List<Map<String, Object>> timelineSnapshot(TurnState st, int len,
                                                       int toolCount, int artifactCount, boolean flush) {
        int processLen = st.processResponse.length();
        List<Map<String, Object>> src;
        synchronized (st.timeline) {
            src = new ArrayList<>(st.timeline.size() + 2);
            for (Map<String, Object> seg : st.timeline) {
                if (seg != null) src.add(new LinkedHashMap<>(seg));
            }
        }
        if (flush) {
            flushTimelineText(st, len);
            flushTimelineProcess(st, processLen);
        } else {
            appendPendingTimelineTails(st, src, len, processLen);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> seg : src) {
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
        if (out.size() == 1 && "text".equals(out.get(0).get("kind"))
                && toInt(out.get(0).get("from")) == 0 && toInt(out.get(0).get("to")) == len) {
            return List.of();
        }
        return out;
    }

    /**
     * 只读尾段补齐：把「锚点之后尚未成段的正文/过程」接在副本末尾（与 flushTimelineText/Process 同规则：
     * 末段同类且正好接在锚点上就延伸它，否则另起一段）。只改 {@code src}，不碰 {@code st.timeline} 与锚点。
     */
    private static void appendPendingTimelineTails(TurnState st, List<Map<String, Object>> src,
                                                   int len, int processLen) {
        appendTailIfLonger(src, "text", st.timelineAnchor.get(), len);
        appendTailIfLonger(src, "process", st.processAnchor.get(), processLen);
    }

    private static void appendTailIfLonger(List<Map<String, Object>> src, String kind, int from, int to) {
        if (to <= from) return;
        Map<String, Object> last = src.isEmpty() ? null : src.get(src.size() - 1);
        if (last != null && kind.equals(last.get("kind")) && toInt(last.get("to")) == from) {
            last.put("to", to);
            return;
        }
        Map<String, Object> seg = new LinkedHashMap<>();
        seg.put("kind", kind);
        seg.put("from", from);
        seg.put("to", to);
        src.add(seg);
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
    private void persistPartialAnswer(TurnState st, String tailMarker) {
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
            List<Map<String, Object>> sources = st.refs.sources;
            List<String> finalImgs = new ArrayList<>(st.refs.imgIndex.values());
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
                    planRecordJson(st), st.turnId);
            if (replaceGroup != null && partialMessageId != null) {
                sessionService.setMessageVariant(partialMessageId, replaceGroup);
            }
            // 中断轮同样留降级证据（半程回答也是这一轮的事实）
            sessionService.persistDegradations(partialMessageId, st.degradations);
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
    private Map<String, Object> buildUsageSnapshot(TurnState st, String answer) {
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
            runtime.addDegradation(st.degradations, st.degradedCodes, "outputTruncated",
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
    private String truncationSuffix(TurnState st, String answer) {
        if (st.finishLength) {
            runtime.addDegradation(st.degradations, st.degradedCodes, "outputTruncated",
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
            runtime.addDegradation(st.degradations, st.degradedCodes, "outputLost",
                    "模型本轮生成 " + finalRoundOutput + " tokens，仅 " + accounted + " 字进入回答，内容疑似丢失");
            log.warn("[FAIL-LOUD] 生成量与正文字数不符: session={}, 最终轮输出={} tokens, 已交代={} 字"
                            + "（正文 {}/过程 {}/思考 {}/工具入参 {}）, finishReason={}",
                    st.sessionId, finalRoundOutput, accounted, answer.length(),
                    st.processResponse.length(), st.reasoningChars, toolArgChars, st.lastFinishReason);
            return OUTPUT_LOST_SUFFIX;
        }
        return null;
    }

    private void recordToolStatus(TurnState st, String name, String input,
                                  String status, String resultOrError, long elapsedMs, int attempts,
                                  boolean denied) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("name", name);
        // 展示名与悬停说明随后端下发：工具卡上写「execute」用户读不懂，而前端那份手抄映射
        // 对 MCP 动态工具与新增工具一律失效（writeTodo 上线时清单与卡片都只剩英文名）
        putToolDisplayName(rec, name);
        rec.put("status", status);
        rec.put("elapsedMs", elapsedMs);
        // 被守卫在执行前挡下：卡片照常留一行（失败要看得见），但这条不计入「跑了 N 步工具」
        if (denied) rec.put("denied", true);
        if (attempts > 1) {
            rec.put("attempts", attempts); // 自动重试后成功：前端显示「重试 N 次」
        }
        int sseLimit = needsFullToolIO(name) ? TOOL_IO_MAX : TOOL_IO_SSE_BRIEF;
        String argsBrief = truncBrief(input, sseLimit);
        rec.put("args", argsBrief);
        if (resultOrError != null) {
            rec.put(status.equals("error") ? "error" : "result", truncBrief(resultOrError, sseLimit));
        }
        // run_code 的扇出清单（P3b 过程下钻）：一次运行内部调了哪些工具、各自多久、有没有被挡下。
        // 只带终态那一份，且随卡片一起落库——刷新后仍然点得开，不然这段结构只有实时看得到
        if ("run_code".equals(name) && !"start".equals(status)) {
            // 这一段脚本结束了：面板不再刷（终态那一份就挂在下面这条记录里，随消息落库）
            st.board.runBoardActive = false;
            subCall.stopRunBoardTicker(st);
            if (!st.board.subCallLog.isEmpty()) rec.put("fanOut", subCall.buildFanOut(st, false));
            // 这一次运行到底是跑完了、超时了，还是沙盒压根没通——字段化随卡片落库，
            // 刷新后仍然看得出来（正文里那句话只有读的人才知道它在说什么）
            if (st.board.runOutcome != null) {
                rec.put("run", st.board.runOutcome);
                st.board.runOutcome = null;
            }
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
            // 超过上限的那一段不再「截断即丢」：整份原文外溢到磁盘，卡片留 spillId 供「查看完整输出」按需读回。
            // 写失败退回原样截断（旁路语义，绝不影响本轮问答）
            String argsSpillId = input != null && input.length() > TOOL_IO_MAX
                    ? toolSpillService.spill(st.sessionId, st.userId, name, input) : null;
            full.put("args", truncBrief(input, TOOL_IO_MAX)
                    + (argsSpillId == null ? spillNote(input) : SPILL_SUFFIX));
            if (argsSpillId != null) full.put("argsSpillId", argsSpillId);
            if (resultOrError != null) {
                String key = status.equals("error") ? "error" : "result";
                // 工具侧截断时已留存过就用它那一份（那才是全文）；否则由这层的上限补一份
                String spillId = takeToolSpill(st, name);
                if (spillId == null && resultOrError.length() > TOOL_IO_MAX) {
                    spillId = toolSpillService.spill(st.sessionId, st.userId, name, resultOrError);
                }
                full.put(key, truncBrief(resultOrError, TOOL_IO_MAX)
                        + (spillId == null ? spillNote(resultOrError) : SPILL_SUFFIX));
                if (spillId != null) full.put("spillId", spillId);
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
        String ssePayload = JSON.toJSONString(rec);
        // 总线优先：接流的观众看到的工具卡片要有实时状态（快照只给到接流那一刻为止的记录）
        if (!chatRunBus.publishRaw(st.channel.emitter, "tool_status", ssePayload, st.sessionId)) {
            try {
                st.channel.emitter.send(SseEmitter.event().name("tool_status")
                        .data("{\"type\":\"tool_status\",\"content\":" + ssePayload
                                + ",\"sessionId\":\"" + st.sessionId + "\"}"));
            } catch (Exception e) {
                log.debug("[TOOL-STATUS] SSE 下发失败（客户端可能已断开）: {}", e.getMessage());
            }
        }
        // 账本留痕：工具步的「何时开始/结束、耗时、成败」是一轮执行轨迹的主干。载荷用 SSE 那份短摘要
        // ——终态全文（8KB）已随消息落库，账本不重复存大字段；start 也记，回放才看得出每步的起点。
        sessionEventService.append(st.sessionId, st.userId, st.turnId, "tool", ssePayload);
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
     * 可变状态与 complete 回调依赖收敛在 TurnState；重试时重建全新流并丢弃旧缓冲。
     */
    private Disposable buildAnswerStream(String system, String user, TurnState st, Agent agent) {
        SseEmitter emitter = st.channel.emitter;
        // 智能体归属快照：随助手消息落库（完成路径直接用闭包 agent，中断兜底路径取这里的快照）
        st.agentId = agent == null ? null : agent.getId();
        st.agentName = agent == null ? null : agent.getName();
        // 登记产物 emitter：供 PresentArtifactTool 在流式执行中实时下发 artifact 事件（结束/出错时清理）
        artifactService.registerEmitter(st.sessionId, emitter);
        // 产物生成时刻监听：把产物卡片按生成顺序插进本轮时间线（刷新后仍在原位，不再堆到气泡底部）
        artifactService.registerArtifactListener(st.sessionId, (sid, idx) -> pushTimelineArtifact(st, idx));
        // 整轮流级心跳：从这里到终态（complete/error/dispose）全程保活，覆盖工具执行
        // 与「工具结束→最终回答首 token」两段静默盲区（重试重建流时幂等复用）
        runtime.startRunHeartbeat(st);
        java.util.List<org.springframework.ai.tool.ToolCallback> rawToolCallbacks =
                st.tools.enabledCallbacks != null ? st.tools.enabledCallbacks : enabledToolCallbacks(agent, st.userId, st);
        // 一份本轮上下文，两条管线：主链路用包装后的回调给模型，脚本子调用用同一条上下文再包一条
        // （身份快照只能在这条线程上取——流水线入口才有 RequestUser，子调用到达时在 Tomcat 线程）
        ToolTurnContext turnCtx = toolTurnContext(st);
        org.springframework.ai.tool.ToolCallback[] toolCallbacks = toolPipeline.wrap(rawToolCallbacks, turnCtx);
        subCall.prepareSubCallPipeline(st, rawToolCallbacks, turnCtx);
        // 模型不支持 Function Calling 时不下发 tools：部分网关收到 tools 字段直接 400（fail-loud，不静默）。
        // 登记为用户可见降级事件——工具是智能体配置带来的，用不了要让人知道原因。
        if (toolCallbacks.length > 0 && !modelRegistryService.toolCapableOf(st.model)) {
            log.warn("[FAIL-LOUD] 当前模型不支持 Function Calling，本轮不下发工具: model={}", st.model);
            runtime.addDegradation(st.degradations, st.degradedCodes, "modelToolUnsupported",
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
            if (st.tools.mcpToolNames.contains(def.name())) mcpSchemaTokens += t;
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
        runtime.sendSseEvent(emitter, "usage", JSON.toJSONString(usageEarly), st.sessionId);
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
                runtime.addDegradation(st.degradations, st.degradedCodes, "reasoningLevelUnsupported",
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
                        // 归属 kind：子智能体分支记 subagent（P3a）。台账按 uid 记账本来就落在同一个人头上，
                        // 这里区分的是"这个人自己问的"与"他派出去的智能体问的"——排查一轮为什么这么贵时，
                        // 得能看出钱花在分支上而不是主轮上。
                        com.wenqu.ai.util.UsageAttr.of(st.userId, st.sessionId, null,
                                st.branchTurn ? "subagent" : "chat")))
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
                    st.channel.emitBuf.append(token);
                    String bufStr = st.channel.emitBuf.toString();
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
                    boolean unclosedDsml = containsUnclosedDsml(bufStr, st.tools.dsmlToolNamesLc);
                    if (unclosedDsml && !unclosedRelated && !unclosedProcess) {
                        // 只可能是 DSML 未闭合：整批继续缓冲等闭合标签到达（与 related/process 同款等待）
                        if (bufStr.length() > 3000) {
                            // 模型压根不写闭合标签（输出触顶截断在闭合之前）：开口起整段丢弃，
                            // 否则裸露的 <searchKnowledge> 与参数值会一路落屏——按原文发正文等于把协议噪声泄给用户
                            String trimmed = dropUnclosedDsmlTail(bufStr, st.tools.dsmlToolNamesLc);
                            if (!trimmed.equals(bufStr)) {
                                runtime.addDegradation(st.degradations, st.degradedCodes, "toolTextMalformed",
                                        "模型以文本形式输出了未闭合的工具调用，已从回答中移除");
                            }
                            st.channel.emitBuf.setLength(0);
                            if (!trimmed.isEmpty()) {
                                st.fullResponse.append(trimmed);
                                if (!runtime.sendSseEvent(emitter, "token", trimmed, st.sessionId)
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
                            st.channel.emitBuf.setLength(0);
                            // 缓冲整体倾倒，未闭合开口的增量态一并归零（其内容已随本次倾倒归入过程通道）
                            st.processOpen = false;
                            st.openProcessChars = 0;
                            st.processMalformedNoted = false;
                            if (unclosedProcess && !unclosedRelated && bufStr.contains("<process>")) {
                                // 未闭合 <process>（模型漏写闭合标签）：开口前是正文走 token，
                                // 其后剥标签整体归入过程通道（fail-loud 标记）——按原文发正文会把独白漏给用户
                                runtime.addDegradation(st.degradations, st.degradedCodes, "processMalformed",
                                        "模型输出格式异常（process 标签未闭合），开口后内容已整体归入过程叙述");
                                int pIdx = bufStr.indexOf("<process>");
                                String ansPart = bufStr.substring(0, pIdx);
                                String procPart = bufStr.substring(pIdx + "<process>".length());
                                if (!ansPart.isEmpty()) {
                                    st.fullResponse.append(ansPart);
                                    if (!runtime.sendSseEvent(emitter, "token", ansPart, st.sessionId)
                                            && !st.keepRunningWithoutChannel()) {
                                        st.disposeSafe();
                                        return;
                                    }
                                }
                                routeProcessText(st, procPart.stripLeading());
                            } else {
                                // related 未闭合（或两类标签并存难以可靠拆分）：按原文发送（extractRelated 兜底清理）；fail-loud 标记
                                runtime.addDegradation(st.degradations, st.degradedCodes, "relatedMalformed",
                                        "模型输出格式异常（related 标签未闭合），已按原文清理");
                                String raw = bufStr;
                                st.fullResponse.append(raw);
                                // 客户端断开：取消流订阅立即停止模型输出（不补 error/complete）；
                                // 本轮在等人作答或已转后台续跑则只丢事件、继续生成
                                if (!runtime.sendSseEvent(emitter, "token", raw, st.sessionId)
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
                    if (!st.tools.dsmlToolNamesLc.isEmpty()) {
                        String dsmlClean = stripDsmlToolText(clean, st.tools.dsmlToolNamesLc);
                        if (!dsmlClean.equals(clean)) {
                            if (!st.tools.dsmlStrippedNoted) {
                                st.tools.dsmlStrippedNoted = true;
                                runtime.addDegradation(st.degradations, st.degradedCodes, "toolTextLeak",
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
                            runtime.addDegradation(st.degradations, st.degradedCodes, "processMalformed",
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
                    st.channel.emitBuf.setLength(0);
                    int keep = tagPrefixSuffixLen(clean);
                    String sendPart = clean.substring(0, clean.length() - keep);
                    String tailKeep = clean.substring(clean.length() - keep);
                    st.channel.emitBuf.append(tailKeep);
                    // 开口标签与扣留尾巴留在缓冲：下一 token 仍从此开口位置续（增量态因此可跨 token 延续）
                    if (openBody != null) st.channel.emitBuf.append("<process>").append(heldProc);
                    if (!sendPart.isEmpty()) {
                        st.fullResponse.append(sendPart);
                        // 客户端断开：取消流订阅立即停止模型输出（不补 error/complete）；
                        // 本轮在等人作答或已转后台续跑则只丢事件、继续生成（正文仍会完整落库）
                        if (!runtime.sendSseEvent(emitter, "token", sendPart, st.sessionId)
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
                    if (runtime.clientDisconnected(st.channel.emitter) && !st.keepRunningWithoutChannel()) {
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
                    boolean noTokenYet = st.fullResponse.length() == 0 && st.channel.emitBuf.length() == 0;
                    // 模型登记为"支持工具调用"、上游却把带 tools 的请求打回来（私有化网关与
                    // vLLM/Ollama 裸部署最常见）。原样重试只会再失败一次；这里改走"不挂工具"的同一轮，
                    // 并如实登记降级——不这么做的话用户看到的只是一句"服务异常"，
                    // 而真实情况是"这一轮不会咨询其他助手、也不会补充检索"。
                    if (!st.toolsDropped && noTokenYet && retryable && looksLikeToolsRejected(error)) {
                        st.toolsDropped = true;
                        st.toolsRetryQueued = true;
                        st.tools.enabledCallbacks = java.util.List.of();
                        runtime.addDegradation(st.degradations, st.degradedCodes, "toolsRejected",
                                "当前模型不接受工具调用，本轮已改为不带工具回答：不会咨询其他助手，也不会补充检索");
                        log.warn("[FAIL-LOUD] 上游拒绝 tools 请求，本轮改为不挂工具重试: {}", root);
                    }
                    // 换路只给一次机会，用完即闸门关闭；通用重试仍走 chat.streamRetryCount 的额度
                    boolean toolsSwapRetry = st.toolsRetryQueued;
                    st.toolsRetryQueued = false;
                    if ((toolsSwapRetry || (retryable && st.retried.getAndIncrement() < streamRetryCount()))
                            && noTokenYet) {
                        log.warn("[FAIL-LOUD] 主 LLM 流式中断且未输出 token，自动重试第 {} 次: {} -> {}",
                                st.retried.get(), error.getClass().getSimpleName(), root);
                        // 重建全新流，丢弃旧缓冲（避免重试拼接出重复内容）
                        st.fullResponse.setLength(0);
                        st.channel.emitBuf.setLength(0);
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
                        st.channel.disposableRef.set(buildAnswerStream(system, user, st, agent));
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
                    runtime.sendSseEvent(emitter, "error", msg, st.sessionId);
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
                    runtime.completeEmitter(emitter);
                    TurnRegistry.stopDeadline(emitter);
                    artifactService.unregisterEmitter(st.sessionId);
                    chatRunBus.finish(st.sessionId);
                })
                .doOnComplete(() -> {
                    // 终态：先停整轮流级心跳（complete 路径），收尾阶段不再有心跳字节
                    stopRunHeartbeat(st);
                    // 下发缓冲尾部（可能残留滑动窗口），并剥离可能的不完整标签
                    if (st.channel.emitBuf.length() > 0) {
                        String rest = st.channel.emitBuf.toString();
                        st.channel.emitBuf.setLength(0);
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
                            runtime.addDegradation(st.degradations, st.degradedCodes, "processMalformed",
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
                            runtime.addDegradation(st.degradations, st.degradedCodes, "relatedMalformed",
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
                        if (!st.tools.dsmlToolNamesLc.isEmpty()) {
                            String dsmlRest = dropUnclosedDsmlTail(
                                    stripDsmlToolText(rest, st.tools.dsmlToolNamesLc), st.tools.dsmlToolNamesLc);
                            if (!dsmlRest.equals(rest)) {
                                if (!st.tools.dsmlStrippedNoted) {
                                    st.tools.dsmlStrippedNoted = true;
                                    runtime.addDegradation(st.degradations, st.degradedCodes, "toolTextLeak",
                                            "模型以文本形式重复输出了工具调用内容，已自动从回答中移除");
                                }
                                rest = dsmlRest;
                            }
                        }
                        if (!rest.isEmpty()) {
                            st.fullResponse.append(rest);
                            runtime.sendSseEvent(emitter, "token", rest, st.sessionId);
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
                    if (!st.tools.dsmlToolNamesLc.isEmpty()) {
                        String dsmlAnswer = dropUnclosedDsmlTail(
                                stripDsmlToolText(answer, st.tools.dsmlToolNamesLc), st.tools.dsmlToolNamesLc);
                        if (!dsmlAnswer.equals(answer)) {
                            if (!st.tools.dsmlStrippedNoted) {
                                st.tools.dsmlStrippedNoted = true;
                                runtime.addDegradation(st.degradations, st.degradedCodes, "toolTextLeak",
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
                        runtime.addDegradation(st.degradations, st.degradedCodes, "relatedHoldsAnswer",
                                "模型把正文写进了推荐块（" + relatedProse.size() + " 段），已并回回答");
                        log.warn("[FAIL-LOUD] related 内含正文 {} 段（{} 字），已并回回答: session={}",
                                relatedProse.size(), merged.length(), st.sessionId);
                    }
                    // 引用来源（局部可变：语义一致性自检会剔除不支撑的条目并重编 ref，替换新列表）
                    List<Map<String, Object>> sources = st.refs.sources;

                    // 图片相关性校验兜底：剔除与描述不匹配的 [图片N] 标记并重建编号（LLM 偶发错配）
                    List<String> finalImgs = new ArrayList<>(st.refs.imgIndex.values());
                    if (properties.getImages().getImageFilter().isEnabled() && !st.refs.imgIndex.isEmpty()) {
                        ImageFilterService.RebuildResult rr = imageFilterService.rebuild(answer, st.refs.imgDescIndex, st.question,
                                properties.getImages().getImageFilter().getMinHits(),
                                properties.getImages().getImageFilter().getPreContextChars());
                        if (!rr.dropped().isEmpty()) {
                            // M7 fail-loud：图片被剔除后回答仍引用旧编号，需告知
                            runtime.addDegradation(st.degradations, st.degradedCodes, "imgFilterDropped",
                                    "已剔除 " + rr.dropped().size() + " 张与回答内容不匹配的图片引用");
                            log.info("[IMG-FILTER] 图片错配剔除 {} 个: {}", rr.dropped().size(), rr.dropped());
                            answer = rr.text();
                            finalImgs = rr.keptSeq().stream().map(st.refs.imgIndex::get).toList();
                        }
                    }
                    // L4 fail-loud：有引用来源但回答未标注任何 [N]（溯源缺失）
                    // 注意用 find() 而非 matches()：matches 全串锚定且 . 不跨行，多行回答永远误判为未标注
                    if (!sources.isEmpty() && !CITE_PATTERN.matcher(answer).find()) {
                        runtime.addDegradation(st.degradations, st.degradedCodes, "noCitation", "回答未标注引用来源");
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
                        runtime.addDegradation(st.degradations, st.degradedCodes, "invalidCitation",
                                "已移除 " + invalidRefs + " 处无效的引用标注（编号超出来源范围）");
                        log.info("[CITE-CHECK] 剔除越界引用 {} 处 (maxRef={})", invalidRefs, maxRef);
                    }
                    // 图片占位越界校验（与引用越界校验同构）：正文 [图片N] 编号必须落在本轮真实图片
                    // （imgIndex，由主链路上下文填充建立）范围内。工具模式下模型手里没有编号图片
                    // （工具返回文本不带编号、images 为空），却可能按「尽量配图」规则编造 [图片N]，
                    // 前端 images[N-1] 映射不到就把字面 [图片N] 原样显示在回答里。剔除标记 + fail-loud。
                    int maxImg = st.refs.imgIndex.size();
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
                        runtime.addDegradation(st.degradations, st.degradedCodes, "invalidImageRef",
                                "已移除 " + bogusImgs + " 处无效的图片标记（本轮没有对应的图片资料）");
                        log.info("[IMG-CHECK] 剔除无效图片标记 {} 处 (maxImg={})", bogusImgs, maxImg);
                    }
                    // 生成完成（引用自检前的最后一步）
                    st.channel.stageMs.put("generate", System.currentTimeMillis() - st.channel.startTime);
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
                                runtime.addDegradation(st.degradations, st.degradedCodes, "citationUnsupported",
                                        "已剔除 " + ccr.droppedCount() + " 处与引用内容不匹配的引用标注");
                                log.info("[CITE-CHECK] 引用语义一致性剔除 {} 处: {}", ccr.droppedCount(), ccr.dropped());
                            }
                        } catch (Exception e) {
                            // fail-loud：自检失败保持原回答（校验是增强，不是必选防线）
                            log.warn("[FAIL-LOUD] 引用一致性自检失败（保持原回答）: {}", e.getMessage());
                        } finally {
                            com.wenqu.ai.util.UsageAttr.clear();
                        }
                        st.channel.stageMs.put("citation", System.currentTimeMillis() - st.channel.startTime);
                    }
                    // 截断必须让用户看见：降级提示受 chat.retrievalDebugEnabled 控制（面向用户的部署
                    // 默认关闭），只落提示等于没提示——标注直接进正文尾部，随 done 与落库一起走，
                    // 刷新后仍在（与中断兜底的 TurnState.TRUNCATION_SUFFIX 同口径）。
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
                        runtime.addDegradation(st.degradations, st.degradedCodes, "noHit", "未检索到相关资料，回答可能缺乏依据");
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
                                planRecordJson(st),
                                // 本轮身份：账本事件按 turnId 分组，消息带上它，气泡才能直接回看自己那一轮的过程
                                st.turnId);
                        // 新回答挂上被替换旧回答的版本组键：组内版本序列即 ‹ n/N › 切换数据源
                        if (replaceGroup != null && messageId != null) {
                            sessionService.setMessageVariant(messageId, replaceGroup);
                        }
                    }

                    // 降级条随消息行落库：只随 done 事件下发时刷新即消失，这一轮"降了什么级"就没了证据
                    sessionService.persistDegradations(messageId, st.degradations);

                    // 本轮降级事件随消息行落库：done 已下发过一次，刷新后据此仍能看到"这一轮降了什么级"
                    sessionService.persistDegradations(messageId, st.degradations);

                    // 异步落问答日志（不阻塞 SSE 完成）；messageId/agentId 随行（trace 关联键与筛选维度）
                    List<String> hitDocIds = sources.stream().map(s -> String.valueOf(s.get("docId"))).toList();
                    qaLogService.logAsync(st.sessionId, st.question, answer, hitDocIds,
                            !st.refs.sources.isEmpty(), System.currentTimeMillis() - st.channel.startTime,
                            st.queryForLog, st.channel.stageMs.isEmpty() ? null : JSON.toJSONString(st.channel.stageMs),
                            st.deepThink, messageId, agent == null ? null : agent.getId());

                    // done 事件：引用来源/相关推荐/消息ID + 校验修正后的内容/图片 + 思考全文 + 本轮全部降级事件（fail-loud）
                    Map<String, Object> donePayload = new LinkedHashMap<>();
                    // 引用来源内的 images 同样动态签名（引用弹窗直接加载；原始 URL 存库，签名仅作用于下发副本）
                    donePayload.put("sources", imageUrlSigner.signSourceImages(sources));
                    donePayload.put("related", related);
                    donePayload.put("messageId", messageId);
                    // 本轮身份（与刚落库消息行的 turn_id 同源）：当轮气泡立刻能按轮回看过程，
                    // 不必刷新等历史回填——「过程」入口不该是刷新后才有的东西
                    donePayload.put("turnId", st.turnId);
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
                    // 本轮作答方归属（P5）：不再有会话锁定，前端按这一轮实际作答的智能体显示归属徽标
                    if (agent != null) {
                        donePayload.put("agentId", agent.getId());
                        donePayload.put("agentName", agent.getName() == null ? "" : agent.getName());
                    }
                    // §4 轮级委派归属：本轮由 @ 提及的智能体作答时单独下发（agentId/agentName 保持会话绑定口径，
                    // 前端绑定镜像不受影响；气泡归属按委派值展示，与刷新后按落库快照回显的结果一致）
                    if (st.delegatedAgentId != null) {
                        donePayload.put("delegatedAgentId", st.delegatedAgentId);
                        donePayload.put("delegatedAgentName",
                                st.delegatedAgentName == null ? "" : st.delegatedAgentName);
                    }
                    // 本轮收尾仍有没赶上的插话（模型这轮没再调工具就直接答完了）：交回前端自动作为
                    // 下一句发出，与计划「继续对话」同一条通路——用户说过的话不许静默丢掉
                    runtime.attachLeftoverSteers(donePayload, st, st.sessionId);
                    runtime.sendSseEvent(emitter, "done", JSON.toJSONString(donePayload), st.sessionId);
                    runtime.completeEmitter(emitter);
                    // 本轮收尾：显式回收台账（断线后台续跑的轮在通道断开回调里被故意留着，重复回收无害）
                    TurnRegistry.stopDeadline(emitter);
                    artifactService.unregisterEmitter(st.sessionId);
                    chatRunBus.finish(st.sessionId);   // done 已扇给所有观众，总线跟着收摊
                    // 记忆提取：问答完整落定后异步提炼长期记忆（服务内自判开关/游客/匿名，best-effort）；
                    // 提取调用跟随本轮生效模型（st.model，done 时 fail-loud 保证非空）
                    userMemoryService.maybeExtract(st.userId, st.sessionId, st.question,
                            st.fullResponse.toString(), st.guestMode, st.model);
                })
                .subscribe();
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

    // ---- 智能体（P5）本轮作答方解析 ----

    /**
     * 本轮作答的智能体：请求带 id 就用它（分享页 / 定时任务 / 工作流 / 对外 MCP 都是显式指定），
     * 不带就用默认智能体（isDefault=1）；未设默认才落到纯系统设置的全局配置。
     * <p>P5 起这里<b>不再有"会话锁定"</b>。过去首问把智能体锁进会话，是为了让同一会话里人设、知识库、
     * 工具集全程一致；代价是跨主题的问题只能对着自己那套库空转——"上一轮问手册、这一轮问法律就查不到"。
     * 现在资料域由模型经 consultAgent 主动派出去查，作答方不需要被锁，也没有"换人请新建会话"这回事。
     * <p>显式指定却解析不到（已删除或无权限）时 fail-loud：静默改用全局配置等于悄悄换人，用户无从察觉。
     */
    private Agent resolveTurnAgent(String requestedAgentId, List<Map<String, String>> degradations,
                                   Set<String> degradedCodes) {
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            // 个人默认助手（设置页「对话偏好」里选的那位）：流水线线程已装载个人覆盖，直接 get 就是本人值。
            // 它为替代被删掉的"会话锁定"而存在——整天只跟一位助手聊的人不必每会话重挑一次。
            String personal = configService.get("agent.personalDefault");
            if (personal != null && !personal.isBlank()) {
                Agent mine = agentService.get(personal.trim());
                if (mine != null) return mine;
                // 选了之后被删/失去共享：如实说一句再回落，而不是让人以为自己的默认还生效
                runtime.addDegradation(degradations, degradedCodes, "personalAgentGone",
                        "你的默认助手已不可用（被删除或不再共享给你），本轮已改用平台默认智能体");
            }
            return agentService.defaultAgent();
        }
        Agent picked = agentService.get(requestedAgentId);
        if (picked == null) {
            runtime.addDegradation(degradations, degradedCodes, "agentMissing",
                    "所选智能体不可用（已删除或你无访问权限），本轮按全局配置回答");
            log.warn("[AGENT] 请求的智能体 {} 不可解析，本轮按全局配置回答", requestedAgentId);
        }
        return picked;
    }

    /**
     * §4 轮级委派下发：{id,name,description}。用户主动 @ 的归属变化要在当轮看得见（气泡上「由 X 作答本轮」），
     * 不能像系统侧的检索细节那样归入排障开关——那是机器行为，这是用户指令。
     */
    private void emitAgentDelegated(SseEmitter emitter, Agent delegate, String sessionId) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", delegate.getId());
            info.put("name", delegate.getName());
            info.put("description", delegate.getDescription());
            runtime.sendSseEvent(emitter, "agent_delegated", JSON.toJSONString(info), sessionId);
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
                runtime.addDegradation(degradations, degradedCodes, "agentMentionUnavailable",
                        "你 @ 的智能体「" + shown + "」已不可访问（被删除或权限变更），本轮由会话绑定的智能体回答");
                log.warn("[AGENT] @ 提及的智能体 {} 不可解析，本轮回落会话绑定", m.getId());
            } else {
                first = a;
            }
        }
        if (first != null && hasMore) {
            runtime.addDegradation(degradations, degradedCodes, "agentMentionMultiple",
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

    /**
     * 内置工具本轮是否真的挂得上：本族开关（全局/智能体三态）+ 智能体的「具体项白名单」两道都过才算。
     * <p>判据本体在 {@code BuiltinToolProvider.exposes}，这里只是问答侧的查询口——装配与提示词
     * 共用同一份，否则会出现「提示词教模型用某个工具，工具表里却没有它」，模型只会对着不存在的能力空转。
     */
    private boolean builtinExposed(boolean guestMode, Agent agent, String name, boolean branchTurn) {
        return toolRegistry.exposes(ToolFamily.BUILTIN, name,
                mountContext(agent, "", guestMode, false, branchTurn, (code, message) -> {
                }, null));
    }

    /**
     * 任务清单工具本轮是否真的挂得上：总闸 + tool.todo.enabled，对匿名访客恒为否
     * （游客收窄那条写在描述符的 guestAllowed 上，不在这里另判一遍）。
     * <p>子智能体分支（branchTurn=true）这里判不出来也不该在这儿判——清单的
     * {@code viewerRequired=true} 让 {@link ToolMountContext#turnAllows} 把它收掉，
     * 与装配那处是同一条判据（在此再写一个 {@code !branchTurn} 就是第二份真源，迟早漂移）。
     */
    private boolean todoExposed(boolean guestMode, Agent agent, boolean branchTurn) {
        return toolRegistry.anyExposed(ToolFamily.TODO,
                mountContext(agent, "", guestMode, false, branchTurn, (code, message) -> {
                }, null));
    }

    /** 咨询工具本轮挂不挂（判据本体在 {@code SubAgentToolProvider.exposes}：族开关 + 游客 + 分支轮不挂） */
    private boolean consultExposed(boolean guestMode, Agent agent, boolean branchTurn) {
        return toolRegistry.exposes(ToolFamily.SUBAGENT, "consultAgent",
                mountContext(agent, "", guestMode, false, branchTurn, (code, message) -> {
                }, null));
    }

    /**
     * 「可咨询助手」一节（P5 agent-as-tool）：把本轮真的能咨询的助手连同 id 一起交给模型。
     * <p>为什么必须写进提示词而不是塞进工具描述：{@code @Tool} 描述是编译期常量，而这份清单是本轮的
     * （按使用者可读范围算）。没有这一节，模型只能猜 id——猜错的表现是一次莫名其妙的"查无此人"，
     * 用户看不出是自己没配助手还是模型编了个号。
     * <p>挂不上工具就不写这一节（与装配同一条判据）：教模型用一件它手上没有的能力，是本仓反复点名的错。
     * 没有可读助手 / 游客 / 分支轮 → 返回空串，对本轮提示词零影响。
     */
    private String consultGuide(boolean guestMode, Agent agent, boolean branchTurn) {
        if (!consultExposed(guestMode, agent, branchTurn)) return "";
        String catalog = agentService.consultCatalog(agent == null ? "" : agent.getId());
        if (catalog.isBlank()) return "";
        return "\n\n## 可咨询助手\n"
                + "遇到超出你手上资料、且明显落在某位助手职责范围内的问题，用 consultAgent 派它去查"
                + "（可以派多次，每次一位；它会带着自己的知识库查一遍再把结论与资料交回）。"
                + "id 必须逐字取自下面这份清单，不要猜。"
                + "通用问题（常识、写作、建议）不要派——没有对应的助手，直接自己答。\n"
                + catalog;
    }

    /**
     * 人机协同三入口（计划卡 / 任务清单 / 提问卡）的分工指引段。
     * <p>为什么要有它：三者都在「把决定权交回用户」，工具描述各说各话时模型会乱选——实测里多步任务
     * 默认用 askUser 连环反问「我按 X 做行不行」，而不是直接开工并建一张进度清单。
     * <p>每一行只在对应入口本轮真用得上时才写（判据与 {@link #enabledToolCallbacks} 同源：都问
     * {@code ToolRegistry}，实现在各族 provider 里）：
     * 计划卡对游客、无工具的收集型通道、不支持 Function Calling 的模型永不出现，写了就是让它等一张不会来的卡。
     * 三个入口都不可用时返回空串，对本轮提示词零影响。
     * <p>子智能体分支（{@code branchTurn}）走的是同一份判据：提问卡与任务清单的
     * {@code viewerRequired} 让它们在分支轮不挂，于是这一整段在分支里自然为空——
     * 不需要在此另写一条"分支不写三入口"的分支。
     */
    private String humanLoopGuide(boolean guestMode, Agent agent, String model, SseEmitter emitter,
                                  boolean branchTurn) {
        boolean todoOn = todoExposed(guestMode, agent, branchTurn);
        boolean askOn = builtinExposed(guestMode, agent, "askUser", branchTurn);
        boolean planOn = !guestMode && configService.getBoolean("tool.enabled")
                && !(emitter instanceof CollectingSseEmitter)
                && modelRegistryService.toolCapableOf(model);
        StringBuilder tri = new StringBuilder();
        if (planOn) {
            tri.append("\n- 计划卡（执行前对齐「要交付什么」）：由用户开启计划开关、或系统判定任务够复杂时自动出现，"
                    + "**不需要你调起**；下方若出现【已批准的执行计划】，它就是本轮的交付约定——按它执行，"
                    + "不要再逐条向用户确认计划里已经写明的事。");
        }
        if (todoOn) {
            tri.append("\n- 任务清单 writeTodo（执行中的进度）：纯展示，不等用户点任何东西。"
                    + "要做 3 步以上就先建一张清单（只列要做的实事，别把「建这份清单」本身写成一项），"
                    + "之后每开始一项更新一次、全部做完再把它整体标成 done。")
                    .append(askOn ? "**多步任务默认用它，而不是反问用户「我按 X 做行不行」**；"
                            : "它是长任务里让用户知道还剩几件事的唯一入口；");
        }
        if (askOn) {
            tri.append("\n- 提问卡 askUser（必须由人拍板的岔路口）：只在缺一个你无法自行判断、"
                    + "且不问就做不下去的关键选择时使用，一次把要问的都问完（可多题一卡）。"
                    + "不要用它确认安排、汇报进度，也不要用它代替上面的计划与清单。");
        }
        if (tri.length() == 0) return "";
        return "\n\n【人机协同三入口】以下" + (planOn && todoOn && askOn ? "三处" : "几处")
                + "会把决定权交回用户，按用途选，不要混用：" + tri
                + "\n拿不准时：按合理默认直接推进，把你的假设写进回答里让用户改，比连环提问更好。";
    }

    /**
     * 技能目录本轮该不该注入：判据与 {@code readSkill} 的挂载<b>同源</b>（问 {@code ToolRegistry}，
     * 实现只在 {@code SkillToolProvider.exposes} 一处）。
     * <p>这里此前是自有一套：技能是**个人资产**、没有族级全局开关（用户装了就是要用，停用由本人在
     * 技能列表里决定），所以旧写法在智能体未显式指定时<b>恒注入</b>。后果是两种错配——
     * ① 工具总闸 {@code tool.enabled} 关着时，提示词里列着技能清单、模型却没有取正文的工具；
     * ② 免登录访客同样注入清单，而访客会话压根不挂 {@code readSkill}。
     * 那段注释自己写了「要与挂载保持一致」，代码却没做到；合到同一判据后两种情况都不存在
     * （总闸关着不注入，访客因 {@code guestAllowed=false} 也不注入）。
     */
    private boolean skillExposed(boolean guestMode, Agent agent, String userId) {
        return toolRegistry.anyExposed(ToolFamily.SKILL, mountContext(agent, userId, guestMode, false));
    }

    /**
     * 解析主智能体委派的子智能体（4.1 与 4.3 打通）。
     * <p>未配置或配置为空 → 返回空列表，编排走原有的多视角策略；
     * 配置了 ID → 逐个加载，跳过已删除或已改回普通智能体的（避免残留 ID 造成空转）。</p>
     */
    /** 把某位排到列表最前（已在场时去重后前置，不重复出现两次） */
    private static List<Agent> withFirst(Agent first, List<Agent> rest) {
        List<Agent> out = new ArrayList<>();
        out.add(first);
        for (Agent a : rest) if (!first.getId().equals(a.getId())) out.add(a);
        return out;
    }

    /** 列表里是否已有该 id（@ 点名去重用） */
    private static boolean hasAgentId(List<Agent> list, String id) {
        for (Agent a : list) if (a.getId().equals(id)) return true;
        return false;
    }

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
        if (subs.size() > maxParallelBranches()) {
            log.warn("[AGENT] 委派子智能体 {} 个，超出并行上限 {}，仅取前 {} 个",
                    subs.size(), maxParallelBranches(), maxParallelBranches());
            return subs.subList(0, maxParallelBranches());
        }
        return subs;
    }

    /** 并行分支上限（与编排器同一条判据，两处都读 {@code agent.maxParallelSubAgents}） */
    private int maxParallelBranches() {
        return Math.max(1, Math.min(8, configService.getInt("agent.maxParallelSubAgents", 4)));
    }

    // ==================== P3a：子智能体真全循环 ====================

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
     * 本轮的库界（主链路检索与精确检索工具共用）。
     * <p>{@code walled=false}（用户在对话页直接发起的轮）返回 null＝不限库，检索侧按使用者可见库收口：
     * 智能体绑定的库不再是"这一轮能查什么"的墙（P5）。返回 null 与"智能体没绑库"是同一条路径，
     * 语义就是"没有墙"。
     * <p>{@code walled=true}（免登录分享页 / 对外 MCP / 子智能体分支）保持原语义：内置「问渠」按使用者
     * 解析为「默认库＋官方手册库」，其余按智能体绑定的库。前两者是权限墙，后者是"专业域"的定义。
     */
    private java.util.Collection<String> scopeKbIdsOf(Agent agent, String userId, boolean walled) {
        if (!walled) return null;
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
    private Set<String> resolveScopeDocIds(Agent agent, String userId, boolean walled) {
        // 与 {@link #scopeKbIdsOf} 同一条口径：登录页的父轮没有墙，被咨询方/游客/分支才有
        if (!walled) return null;
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
                runtime.addDegradation(degradations, degradedCodes, "mentionEmpty",
                        "你引用的文档还没有可用的解析内容，本轮不会把它作为参考资料");
            } else {
                log.info("[MENTION] @ 引用文档块前置 {} 块（{} 篇，每篇上限 {}）",
                        out.size(), scope.docIds().size(), perDoc);
            }
            return out;
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] @ 引用文档块加载失败: {}", e.toString());
            runtime.addDegradation(degradations, degradedCodes, "mentionLoadFailed", "引用的文档内容读取失败，本轮未带入");
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
            runtime.addDegradation(degradations, degradedCodes, "ctxWindowUndeclared",
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
            runtime.addDegradation(degradations, degradedCodes, "contextBudgetFloored",
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
     * 非问答轮（手动压缩 /compact）的生效模型解析：显式指定 &gt; 个人默认；返回空串表示未配置，
     * 由调用方 fail-loud 引导用户选模型（与问答轮同一解析顺序，避免两条路走进不同模型）
     */
    public String resolveChatModel(String userId, String modelOverride) {
        return resolveModel(modelOverride, loadPrefUser(userId));
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























    /** 计划快照 → 助手消息 plan 列 JSON（null=本轮未走计划模式）；落库点见 buildAnswerStream 与中断兜底 */
    private String planRecordJson(TurnState st) {
        return st.planRecord == null ? null : JSON.toJSONString(st.planRecord);
    }

    /**
     * 计划被拒/超时的收尾落库：本轮没有流式回答（gate==null 直接终止，不会走 buildAnswerStream 的落库）。
     */
    private void persistPlanRejected(TurnState st) {
        persistPlanCloseout(st, "（计划未批准，本轮已停止）", "拒绝");
    }

    /**
     * 「继续对话」取代的收尾落库：与拒绝轮同构，但正文与卡片终态是「已被取代」而不是「未批准」——
     * 用户并没有否定计划内容，只是提出了修改；新一轮会按他的意见产出修改后的计划。
     */
    private void persistPlanSuperseded(TurnState st) {
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
    private void persistPlanCloseout(TurnState st, String content, String phase) {
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
            String closeoutMessageId = sessionService.appendMessage(st.sessionId, "assistant", content,
                    null, null, st.thinkingHolder[0], st.retrievedJson,
                    sessionArtifacts.isEmpty() ? null : JSON.toJSONString(sessionArtifacts),
                    toolCallsJson, null, null, timelineJson, processText.isEmpty() ? null : processText,
                    st.agentId, st.agentName, st.model,
                    null, null, null,
                    planRecordJson(st), st.turnId);
            // 降级条同样要能刷新后还在（计划收尾轮也会登记降级，如"上游不认工具"）
            sessionService.persistDegradations(closeoutMessageId, st.degradations);
        } catch (Exception e) {
            log.warn("[PLAN] {}轮收尾落库失败 (session={}): {}", phase, st.sessionId, e.getMessage());
        }
    }

    /**
     * askUser 工具执行体（一卡多问版）：向用户一次性下发多个结构化问题并阻塞等待批量作答，
     * 与工具审批同一套挂起-恢复管道（内存 future 阻塞工具线程 + DB 卡片记录 + SSE 提问卡 + 站内通知旁路）。
     * <p>返回值直接作为工具结果回给模型：每问一个答案的并行数组（多问为 JSON 数组字符串，单问退化为纯文本）。
     * <b>超时与忽略都不替用户选</b>——该题回一句「用户没有回答」，由模型自行推进（见
     * {@link com.wenqu.ai.agent.hitl.HitlDecisionService#buildCombinedAnswer}）。等待窗口 chat.askTimeoutMs
     * （默认 10 分钟）；这段时间内客户端断开
     * 不中止本轮（见 {@link TurnState#keepRunningWithoutChannel}），用户刷新或换设备后仍可按
     * DB 记录重建卡片并把答案送回来。
     */
    private String doAskUserMulti(TurnState st, java.util.List<BuiltinTools.AskQuestion> raw) {
        if (st.guestMode) {
            return "（游客会话不支持结构化提问：请改为在回答正文中直接列出候选选项，请用户回复序号或自行描述。）";
        }
        if (st.askCount.incrementAndGet() > HitlDecisionService.MAX_ASKS_PER_TURN) {
            return "（本轮提问次数已达上限 " + HitlDecisionService.MAX_ASKS_PER_TURN + " 次：请基于已有信息直接作答，不要再提问。）";
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
                    if (opts.size() >= HitlDecisionService.MAX_ASK_OPTIONS) break;
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
            return "（无有效问题：每题须含非空的 question 与 2~" + HitlDecisionService.MAX_ASK_OPTIONS + " 个候选选项，工具未执行；请调整后重试，或直接在回答正文中列出选项提问。）";
        }
        if (qs.size() > HitlDecisionService.MAX_QUESTIONS_PER_CARD) {
            return "（本次提问共 " + qs.size() + " 个问题，超过单卡上限 " + HitlDecisionService.MAX_QUESTIONS_PER_CARD
                    + " 个，工具未执行：请把问题合并或分批，压缩到 " + HitlDecisionService.MAX_QUESTIONS_PER_CARD + " 个以内后重新提问。）";
        }
        long timeout = hitl.askTimeoutMs();
        String askId = java.util.UUID.randomUUID().toString();
        // 挂起这张提问卡：落 PENDING 记录（questions 存 request_args）+ 登记唤醒句柄，都在新服务一次做完
        try (var wait = hitl.openAsk(askId, st.sessionId, st.userId, st.turnId, qs)) {
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
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("askId", askId);
            req.put("questions", hitl.questionsPayload(qs));
            req.put("timeoutMs", timeout);
            // 卡片下发失败（连接已断）：本轮就此转入后台续跑——提问已落库，用户从别的页面答完，
            // 回答照样生成并落库，回会话页可见。不转的话答完的第一个 token 发送失败会掐掉整轮。
            if (!runtime.sendSseEvent(st.channel.emitter, "ask_user", JSON.toJSONString(req), st.sessionId)) {
                st.channel.detached = true;
                log.info("[ASK] 提问卡通道已失效，本轮转后台续跑: askId={} session={}", askId, st.sessionId);
            }
            log.info("[ASK] 等待用户批量作答: askId={} session={} n={} 窗口={}ms",
                    askId, st.sessionId, qs.size(), timeout);
            String answer;
            TurnRegistry.beginHumanWait(st.channel.emitter);   // 用户作答时间不计入整轮机器预算（见 TurnDeadline）
            st.askWaits.incrementAndGet();
            try {
                answer = wait.future().get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                answer = hitl.buildCombinedAnswer(askId, st.userId, qs, null, "TIMEOUT");
            } catch (java.util.concurrent.ExecutionException ee) {
                hitl.markAskResolved(askId, "TIMEOUT", null, st.userId);
                answer = "（用户中止了本轮回答：请基于已有信息直接作答，不要再次提问。）";
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                hitl.markAskResolved(askId, "TIMEOUT", null, st.userId);
                answer = "（本轮问答已被中止：请基于已有信息直接作答，不要再次提问。）";
            } finally {
                st.askWaits.decrementAndGet();
                TurnRegistry.endHumanWait(st.channel.emitter);
            }
            return answer;
        }
    }

    /** 本轮工具上下文：会话/用户归属 + askUser 执行器（闭包持有 st，提问面板 SSE 与阻塞等待都靠它） */
    private java.util.Map<String, Object> toolContext(TurnState st) {
        java.util.Map<String, Object> ctx = new java.util.HashMap<>();
        ctx.put(PresentArtifactTool.CTX_SESSION_ID, st.sessionId);
        ctx.put(PresentArtifactTool.CTX_USER_ID, st.userId);
        ctx.put(BuiltinTools.CTX_ASK,
                (BuiltinTools.AskFn) qs -> doAskUserMulti(st, qs));
        // 任务清单写入器：整表落库 + 实时 SSE + 账本留痕（回给模型的文本由 TodoService 组装，
        // 让模型看见"归一/截断了什么"，而不是以为原样写进去了）
        ctx.put(TodoService.CTX_TODO, (TodoService.WriteFn) items -> {
            TodoService.Normalized n = todoService.normalize(items);
            if (!n.ok()) return "错误：清单没有有效条目，每项都要写一句可验收的动作。";
            String doc = todoService.docJson(st.turnId, n.items());
            todoService.save(st.sessionId, doc);
            sessionEventService.append(st.sessionId, st.userId, st.turnId, "todo", doc);
            runtime.sendSseEvent(st.channel.emitter, "todo", doc, st.sessionId);
            return todoService.describe(n);
        });
        // 工具大输出外溢：沙盒那层截断前把整份原文交给我们落盘，卡片给「查看完整输出」按需读回。
        // 经上下文注入而不是全局单例——工具在弹性线程上并发跑，单例的归属会串到别人的轮上
        ctx.put(SandboxTools.CTX_SPILL, (java.util.function.BiFunction<String, String, String>)
                (toolName, text) -> toolSpillService.spill(st.sessionId, st.userId, toolName, text));
        ctx.put(SandboxTools.CTX_SPILL_REPORT, (java.util.function.BiConsumer<String, String>)
                (toolName, spillId) -> st.toolSpills.offer(toolName + "|" + spillId));
        // 智能体咨询（P5 agent-as-tool）：模型自己决定咨询谁、咨询几次，回答者不变。
        // 执行器按轮回传（为什么不是 bean 见 BranchRunner 接口注释），台账与父轮上下文都取本轮快照；
        // 被咨询方查到的资料经 sink 注册进父轮引用表，所以它的结论点得开出处、模型标得出角标。
        // consultCtx 缺省（非主流水线那条路建的 st，例如二次生成路径）时如实回一句，不 NPE 也不静默。
        ctx.put(com.wenqu.ai.agent.tool.SubAgentTools.CTX_CONSULT,
                (com.wenqu.ai.agent.tool.SubAgentTools.ConsultFn) (agentId, q) -> {
                    if (st.tools.consultCtx == null) {
                        return "咨询通道本轮未就绪，请基于已有资料回答。";
                    }
                    return subAgentConsult.consult(st.tools.consultLedger, st.tools.consultCtx,
                            branch.branchRunner(this::chat, st.sessionId, st.turnId, st.userId, st.guestMode, st.channel.emitter),
                            src -> st.refs.registerConsultedSource(src), agentId, q);
                });
        return ctx;
    }

    /**
     * 单轮工具步数上限：智能体覆盖（null=继承全局；0=不限制；负数按继承处理）。
     * <p>子智能体分支（{@code depth} &ge; 1）按层<b>减半</b>，并留一个下限 3：
     * 一条分支能调的工具数是父轮并发乘数的一部分——父轮派 4 支、每支不限步数，一轮就是一场
     * 没人看着的工具循环。减半是"越深的层越该做一件小事"这个直觉的机器可读版本；
     * 下限 3 防的是基础值很小或层数很深时 {@code >>} 直接算出 0——而 0 在本平台语义是<b>不限制</b>，
     * 递减递减出不限制是最坏的结果。
     * <p>基础值为 0（智能体显式"不限制"）时分支不跟随：那只有人工盯着的会话里才安全，
     * 分支按全局档减半处理，宁可少跑也不放飞。
     */
    private int resolveMaxToolSteps(Agent agent, int depth) {
        Integer v = agent == null ? null : agent.getMaxToolSteps();
        int global = configService.getInt("agent.maxToolSteps", 15);
        boolean unlimited = v != null && v == 0;
        int base = (v == null || v < 0) ? global : v;
        if (depth <= 0) return Math.max(0, base);
        if (unlimited) base = Math.max(1, global);
        return Math.max(3, base >> depth);
    }


    // ==================== 断线重连接流（P0 最后一刀，配合 ChatRunBus） ====================

    /**
     * 接流快照：从本轮的权威缓冲重建「到此刻为止看得见的东西」。
     * <p>这些缓冲本来就是 done 落库与实时流的同源数据，所以接流方拿到的形状与刷新后回看历史一致。
     * <p>不含：待裁决卡片（提问/计划/审批）——它们的时限与答案记录以 c_ai_tool_approval 为准，
     * 前端走既有的 {@code /ask-user/pending}、{@code /plan-approval/pending} 恢复入口，
     * 那里才有剩余的等待毫秒数与「唤醒句柄是否还在」（进程重启过就答不进去）；逐字增量事件也不含，
     * 正文取缓冲本身比回放上千条 token 事件快得多。
     */
    private Map<String, Object> resumeSnapshot(String sessionId, String turnId) {
        Map<String, Object> snap = new LinkedHashMap<>();
        TurnHandle h = TurnRegistry.handle(sessionId);
        if (h == null || !turnId.equals(h.turnId())) {
            snap.put("running", false);   // 本轮已收尾（把手由 claimTerminal 摘掉）
            return snap;
        }
        TurnState st = TurnRegistry.state(sessionId);
        if (st == null) {
            // 还在前置阶段（改写/检索/深度思考/计划编排）：流状态尚未构造，此刻确实没有正文可给。
            // 阶段文案、计划步骤与任务清单由总线镜像在 snapshot.events 里，前端重放即可复现界面
            snap.put("running", true);
            snap.put("phase", "pre");
            return snap;
        }
        if (st.settled()) {
            snap.put("running", false);
            return snap;
        }
        String content = st.fullResponse.toString();
        List<Map<String, Object>> artifacts = artifactService.takeArtifacts(sessionId);
        List<Map<String, Object>> toolCalls = new ArrayList<>(st.toolCalls);
        List<Map<String, Object>> sources;
        synchronized (st.refs.sources) {
            sources = new ArrayList<>(st.refs.sources);
        }
        snap.put("running", true);
        snap.put("phase", "generating");
        // 本轮身份：接回的那颗气泡同样要能按轮回看过程（与 done/落库同源，不是新造一个 id）
        snap.put("turnId", st.turnId);
        snap.put("content", content);
        snap.put("processText", st.processResponse.toString());
        snap.put("thinking", st.thinkingHolder[0]);
        snap.put("deepThink", st.deepThink);
        snap.put("toolCalls", toolCalls);
        snap.put("artifacts", artifacts);
        snap.put("timeline", timelineSnapshot(st, content.length(), toolCalls.size(), artifacts.size(), false));
        snap.put("sources", imageUrlSigner.signSourceImages(sources));
        snap.put("images", new ArrayList<>(st.refs.imgIndex.values()).stream().map(imageUrlSigner::signUrl).toList());
        snap.put("model", st.model);
        snap.put("toolStepCount", st.tools.toolStepCount.get());
        snap.put("maxToolSteps", st.tools.maxToolSteps);
        if (!st.subagentBranches.isEmpty()) snap.put("subagentBranches", st.subagentBranches);
        if (st.subagentRoute != null) snap.put("subagentRoute", st.subagentRoute);
        if (st.planRecord != null) snap.put("planRecord", st.planRecord);
        if (st.agentName != null) {
            snap.put("agentId", st.agentId);
            snap.put("agentName", st.agentName);
        }
        return snap;
    }

    /**
     * 会话事件账本的<b>分轮回放</b>（{@code GET /session/{id}/events} 的实现）。
     * <p>按会话取最近 200 条（写入序倒序）→ 按 turnId 分组（最新轮在前）→ 组内翻回发生顺序。
     * <p>带 {@code turn} 时改为<b>只看那一轮</b>（逐气泡「过程」的取数，按消息行的 turnId 精确取，正序）：
     * 会话级列表回答「这几轮跑了什么」，按轮回答「这一条回答怎么跑出来的」。
     * 载荷只给 240 字摘要：这一份是给人回看「这一轮怎么跑出来的」，不是把原文再存一遍——
     * 完整工具输出走 {@link ToolSpillService} 那套按需读回。
     * <p>账本不参与模型输入（刻意边界，见 {@link SessionEventService}），读口也不改变这一点。
     */
    public Map<String, Object> listSessionEvents(String sessionId, String uid, Integer turns, String turn) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 按轮回放（逐气泡「过程」）：消息行带着 turnId，直接取那一轮、按发生顺序返回，
        // 不必先扫会话最近 200 条再分组——用户点的是这一条回答，不是整个会话
        if (turn != null && !turn.isBlank()) {
            java.util.List<Map<String, Object>> events = new java.util.ArrayList<>();
            for (com.wenqu.ai.model.SessionEvent ev : sessionEventService.listByTurn(sessionId, uid, turn, 200)) {
                events.add(eventItem(ev));
            }
            out.put("turn", turn);
            out.put("turns", events.isEmpty() ? List.of()
                    : List.of(turnGroup(turn, events)));
            out.put("scanned", events.size());
            return out;
        }
        int wantTurns = turns == null || turns <= 0 ? 5 : Math.min(turns, 20);
        java.util.List<com.wenqu.ai.model.SessionEvent> rows = sessionEventService.listRecent(sessionId, uid, 200);
        java.util.LinkedHashMap<String, java.util.List<Map<String, Object>>> groups = new java.util.LinkedHashMap<>();
        for (com.wenqu.ai.model.SessionEvent ev : rows) {
            String tid = ev.getTurnId() == null ? "" : ev.getTurnId();
            groups.computeIfAbsent(tid, k -> new java.util.ArrayList<>()).add(eventItem(ev));
        }
        java.util.List<Map<String, Object>> turnsOut = new java.util.ArrayList<>();
        for (var g : groups.entrySet()) {
            java.util.Collections.reverse(g.getValue());   // 组内回到发生顺序
            turnsOut.add(turnGroup(g.getKey(), g.getValue()));
            if (turnsOut.size() >= wantTurns) break;
        }
        out.put("turns", turnsOut);
        out.put("scanned", rows.size());
        return out;
    }

    /** 账本行 → 回放条目（id/类型/时间 + ≤240 字摘要） */
    private Map<String, Object> eventItem(com.wenqu.ai.model.SessionEvent ev) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", ev.getId());
        item.put("type", ev.getType());
        item.put("at", ev.getCreatedAt() == null ? 0L
                : ev.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        String p = ev.getPayload() == null ? "" : ev.getPayload();
        item.put("summary", p.length() > 240 ? p.substring(0, 240) + "…" : p);
        // 工具行单独把「第几步、成没成、多久」拆出来：对照视图要靠它与回答里的工具卡对上号，
        // 而摘要可能被 240 字正好切在 status 之前——那种情况下前端数不出第几步
        if ("tool".equals(ev.getType())) {
            try {
                com.alibaba.fastjson2.JSONObject o = com.alibaba.fastjson2.JSON.parseObject(p);
                if (o != null) {
                    item.put("name", o.getString("name"));
                    item.put("status", o.getString("status"));
                    if (o.get("elapsedMs") != null) item.put("elapsedMs", o.get("elapsedMs"));
                }
            } catch (Exception ignore) {
                // 载荷不是 JSON（历史行或被截断）：照旧只给摘要，对照视图少一个编号而已
            }
        }
        return item;
    }

    /** 一组事件 → 一轮（起止时间取首末事件） */
    private Map<String, Object> turnGroup(String turnId, java.util.List<Map<String, Object>> events) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("turnId", turnId);
        t.put("startedAt", events.isEmpty() ? 0L : events.get(0).get("at"));
        t.put("endedAt", events.isEmpty() ? 0L : events.get(events.size() - 1).get("at"));
        t.put("count", events.size());
        t.put("events", events);
        return t;
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
                runtime.addDegradation(degradations, degradedCodes, "queryRewriteFailed", "多轮查询改写返回空，按原句检索");
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
            runtime.addDegradation(degradations, degradedCodes, "queryRewriteFailed", "多轮查询改写超时（retrieval.rewriteTimeoutMs），按原句检索");
            return question;
        } catch (Exception e) {
            runtime.addDegradation(degradations, degradedCodes, "queryRewriteFailed", "多轮查询改写失败，按原句检索: " + e.getMessage());
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

    /**
     * 插话续发的用户消息前缀：把「这句话是对上一轮回答的补充」讲清楚，别让模型当成一次新提问。
     * <p>不做这件事的实测后果（短问答必现，模型不调工具时插话必然赶不上工具步）：
     * 用户插话「答案是巴黎吗」，模型当成新问题起题，回答里会写「你消息里的『答案是巴黎吗』这一段，
     * 在当前会话的知识库里没有对应资料」——用户看到的是"我补的方向被当成在问知识库"。
     *
     * <p>写进<b>用户消息</b>而不是 system prompt：这条声明必须和那句话贴在同一条消息里，
     * 混进 system 会和几十行角色设定挤在一起，注意力权重被摊薄。
     */
    private static StringBuilder steerAwareQuestion(String question, boolean fromSteer) {
        if (!fromSteer) return new StringBuilder(question);
        return new StringBuilder(
                "（下面是用户在上一轮回答生成过程中补充的方向，不是新的提问。"
                        + "请顺着这个方向调整上一轮的回答，不要重新起题，也不要把它当作在检索资料。）\n")
                .append(question);
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
                                    List<String> skills, int subagentDepth, boolean fromSteer) {
        // 与主链路同一个判据：深度 > 0 就是子智能体分支（人在回路工具不挂、步数预算递减）
        final boolean branchTurn = subagentDepth > 0;
        try {
            // 计划意图识别（按用户意图自动开启计划模式）：与主链路同口径；本分支无检索可争抢，
            // 识别在闸门前串行取结果（宁缺毋滥，最多让本轮多等 awaitPlanIntent 的上限）
            final java.util.concurrent.CompletableFuture<Boolean> planIntentFuture = plan.startIntentCheck(
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
            // 人机协同三入口：无知识库分支同样挂着 writeTodo/askUser，缺这段就会和主链路行为不一致
            system.append(humanLoopGuide(guestMode, agent, resolvedModel, emitter, branchTurn));
            system.append(consultGuide(guestMode, agent, branchTurn));
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
                runtime.addDegradation(degradations, degradedCodes, "historyFailed", "会话历史读取失败，本次无多轮记忆");
                recentHistory = List.of();
            }
            String historyText = historyCompaction.buildHistoryText(recentHistory);
            if (!historyText.isEmpty()) {
                system.append("\n\n对话历史：\n").append(historyText);
            }

            // 拼装用户消息：问题 + 图片说明（仅"模型不支持读图"时有值）+ 附件内容
            StringBuilder userQuestion = steerAwareQuestion(question, fromSteer);
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

            if (runtime.turnAborted(sessionId, emitter)) {
                log.info("[SSE] 客户端断开或已停止，终止本轮问答（无知识库分支，生成前）: session={}", sessionId);
                return;
            }
            runtime.sendSseEvent(emitter, "stage", "正在生成回答…", sessionId);
            stageMs.put("total", System.currentTimeMillis() - startTime);
            TurnState st = new TurnState(sessionId, question, userId, emitter,
                    new LinkedHashMap<>(), new HashMap<>(), new ArrayList<>(), userImgs,
                    startTime, question, thinkingHolder, degradations, degradedCodes, null, turnDeps);
            st.refs.docMetaCache = documentMetaCache;
            // 本轮无知识库检索，但工具仍可能被模型调用：范围与主链路同口径（P5——登录页的父轮没有库界，
            // 游客与子智能体分支才按智能体绑定的库收口）
            st.tools.toolScopeKbIds = scopeKbIdsOf(agent, userId, guestMode || branchTurn);
            st.tools.toolScopeDocIds = resolveScopeDocIds(agent, userId, guestMode || branchTurn);
            // 本轮生效模型（会话覆盖 > 个人默认）：不赋值会让 buildAnswerStream 发出无 model 的请求，
            // DynamicOpenAiChatModel 落到遗留全局网关且 model 为空 → 网关 400（2026-09-25 通用助手实测）
            st.model = resolvedModel;
            st.guestMode = guestMode; // 游客分享会话：工具只保留知识检索与内置项
            st.replaceMessageId = replaceMessageId; // 重新生成：落库前软删被替换的旧回答
            st.userMessageId = userMessageId; // 本轮用户消息 ID（随 done 下发，编辑重发后前端挂切换器）
            st.delegatedAgentId = delegatedAgent != null ? delegatedAgent.getId() : null; // §4 轮级委派归属
            st.delegatedAgentName = delegatedAgent != null ? delegatedAgent.getName() : null;
            applyPermissionTier(agent, st); // 权限档位（P2）：与主链路同一份解析，无检索分支不另算
            st.tools.maxToolSteps = resolveMaxToolSteps(agent, subagentDepth); // 步数上限（分支逐层减半，见该方法注释）
            st.branchTurn = branchTurn;   // 子智能体分支：「要有人在场」的工具不挂
            st.channel.heartbeat = preHeartbeat; // 前置心跳句柄移交（终态照旧停止）
            st.contextTokens = 0;
            st.budgetTokens = 0;
            st.contextHits = 0;
            // 生效最大输出（模型管理中该模型声明的值）：buildAnswerStream 的 max_tokens 与触顶判定共用
            st.effectiveMaxOutput = effectiveMaxOutputOf(resolvedModel);
            st.channel.stageMs.putAll(stageMs);
            // 计划模式（人在回路）：与主链路同一段钩子（本分支没有检索资料，计划输入不含资料清单）；
            // 未开计划开关时同样按消息意图自动开启（识别命中=本轮按计划模式走，卡片标「自动开启」）
            boolean planRound = planMode;
            if (!planRound && plan.awaitIntent(planIntentFuture)) {
                planRound = true;
                st.planAuto = true;
                log.info("[PLAN] 意图识别命中，本轮自动开启计划模式（无知识库分支）: session={}", sessionId);
            }
            if (planRound) {
                String planQuestion = question + (imgNote == null || imgNote.isBlank() ? "" : imgNote)
                        + (attachmentText == null || attachmentText.isBlank() ? ""
                        : "\n\n（用户上传了附件，其内容将在执行阶段提供）");
                String gate = plan.approvalGate(st, agent, resolveSystemPrompt(agent), planQuestion, skills,
                        null, system, planHooks);
                if (gate == null) {
                    // 同主链路：拒绝/超时=未批准定格；「继续对话」取代=本版作废；均先补落收尾助手消息
                    // （含 plan 快照）再发对应事件 + 标准 done（防「连接被提前关闭」误报）
                    if (st.planSuperseded) {
                        persistPlanSuperseded(st);
                        runtime.sendSseEvent(emitter, "plan_superseded", "{}", sessionId);
                    } else {
                        persistPlanRejected(st);
                        runtime.sendSseEvent(emitter, "plan_cancelled", "{}", sessionId);
                    }
                    Map<String, Object> cancelled = new LinkedHashMap<>();
                    cancelled.put("sources", List.of());
                    cancelled.put("related", List.of());
                    cancelled.put("degradations", degradations);
                    if (st.planSuperseded) cancelled.put("planSuperseded", true);
                    else cancelled.put("planCancelled", true);
                    // 计划被拒/超时这条 done 也必须挂插话：漏了它，插进的话就在这个分支里静默消失
                    runtime.attachLeftoverSteers(cancelled, st, sessionId);
                    runtime.sendSseEvent(emitter, "done", JSON.toJSONString(cancelled), sessionId);
                    runtime.completeEmitter(emitter);
                    return;
                }
            }
            st.channel.disposableRef.set(buildAnswerStream(system.toString(), user, st, agent));
            emitter.onCompletion(() -> { if (!st.keepRunningWithoutChannel()) st.disposeSafe(); });
            emitter.onError(t -> { if (!st.keepRunningWithoutChannel()) st.disposeSafe(); });
        } catch (Exception e) {
            log.error("No-knowledge chat error", e);
            runtime.sendSseEvent(emitter, "error", "系统处理异常，请稍后重试", sessionId);
            runtime.completeEmitter(emitter);
        }
    }
}
