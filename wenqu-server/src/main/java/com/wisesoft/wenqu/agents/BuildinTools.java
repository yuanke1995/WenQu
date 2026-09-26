package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.agents.ToolkitsRegistry.ToolDefinition;
import com.wisesoft.wenqu.agents.backends.sandbox.SandboxFilesystemBackendAdapter;
import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.common.QuestionUtils;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

/**
 * 内建工具集（对应参考实现 {@code agents/toolkits/buildin/tools.py}）。
 *
 * <p>四个工具逐条对位：
 * <ul>
 *   <li>{@code web_search}：豆包/ Tavily 二选一（{@link #resolveWebSearchProvider}），
 *       豆包链路（payload / 响应解析 / HTTP）全量照搬。</li>
 *   <li>{@code present_artifacts}：把已生成文件登记给前端（路径白名单校验 + 普通文件校验）。</li>
 *   <li>{@code ocr_parse_file}：沙盒文件 OCR 成 Markdown 并落盘 outputs/ocr。</li>
 *   <li>{@code ask_user_question}：规范化问题后中断等待用户回答。</li>
 * </ul>
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li>{@code httpx.Client} → JDK {@link HttpClient}（超时 15s 与参考实现一致）。</li>
 *   <li>{@code os.getenv} → {@link System#getenv}。</li>
 *   <li>{@code runtime: ToolRuntime} / {@code InjectedToolCallId} → {@link #CONTEXT} /
 *       {@link #TOOL_CALL_ID} 线程绑定（与 {@code KnowledgeTools} / {@code SkillInstallTool} 同手法）。</li>
 *   <li>{@code Command(update={...})} → 工具返回载体 {@link ArtifactCommand} / {@link AskUserCommand}
 *       （见能力差异 1）。</li>
 *   <li>{@code pathlib.PurePosixPath} → {@link PosixPathLite}；{@code tempfile} → JDK 临时文件 API。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>Command 状态写回</b>：{@code present_artifacts} 的 {@code Command(update={"artifacts":...})}
 *       与 {@code ask_user_question} 的 {@code interrupt(...)} 均无对位；前者收敛为
 *       {@link ArtifactCommand}（含 artifacts 与回给模型的文本），后者收敛为 {@link AskUserCommand}
 *       （含规范化问题与中断载荷，答案由构图方回填）。</li>
 *   <li><b>Tavily 供应商不可用</b>：{@code langchain_tavily.TavilySearch} 无对应实现，
 *       provider 解析保留其映射与展示名，但选中 tavily 时不注册工具（与
 *       {@code dify} / {@code notion} 同口径）。</li>
 *   <li><b>{@code present_artifacts} 的沙盒「文件是否存在」校验</b>：参考实现在路径白名单通过后
 *       构造 {@code ProvisionerSandboxBackend(...)} 并调用 {@code regular_file_exists}，
 *       不存在则抛「文件不存在或不是普通文件: …」（见参考实现 {@code agents/toolkits/buildin/tools.py:238-246}）。
 *       路径白名单与 {@code ..} 穿越校验（纯函数，参考实现 ValueError 文案逐字）一直保留。
 *       <b>本条已于 2026-09-23 接线</b>：{@link PresentArtifactsTool} 经
 *       {@code SandboxFilesystemBackendAdapter#regularFileExists} 真实查询沙盒；
 *       未绑定 backend 时按"文件不存在"处理（不静默放行）。
 *       （原注：缺口是「未接线」而非「未搬」，沙盒数据面已随 §三 backends 落地；
 *       运行时仍取不到沙盒的根因是外部 provisioner 服务未部署，同 {@code SkillRemoteInstall}。）</li>
 *   <li><b>OCR 引擎解析未搬</b>：{@code services/ocr_service.py}（{@code parse_document} /
 *       {@code resolve_ocr_engine_id}）未搬，故 {@code ocrParseFile} 只完成校验与输出路径计算
 *       （{@link #nextOcrOutputPath} / {@link #safeOcrOutputStem}），解析步骤不可用。</li>
 * </ol>
 */
@Slf4j
public final class BuildinTools {

    /** 参考实现 {@code _OCR_OUTPUT_DIR_NAME}。 */
    public static final String OCR_OUTPUT_DIR_NAME = "ocr";

    /** 参考实现 {@code _OCR_PREVIEW_LIMIT}。 */
    public static final int OCR_PREVIEW_LIMIT = 1200;

    /** 参考实现 {@code _SAFE_OUTPUT_STEM_RE}（保留中文、字母数字、.{@code _}、-）。 */
    public static final String SAFE_OUTPUT_STEM_PATTERN = "[^A-Za-z0-9._\\-\u4e00-\u9fff]+";

    /** 参考实现 {@code _DOUBAO_SEARCH_URL}（逐字）。 */
    public static final String DOUBAO_SEARCH_URL = "https://open.feedcoopapi.com/search_api/web_search";

    /** 参考实现 {@code web_search} 工具名（逐字）。 */
    public static final String WEB_SEARCH_TOOL = "web_search";

    /** 参考实现 {@code present_artifacts} 工具名（逐字）。 */
    public static final String PRESENT_ARTIFACTS_TOOL = "present_artifacts";

    /** 参考实现 {@code ocr_parse_file} 工具名（逐字）。 */
    public static final String OCR_PARSE_FILE_TOOL = "ocr_parse_file";

    /** 参考实现 {@code ask_user_question} 工具名（逐字）。 */
    public static final String ASK_USER_QUESTION_TOOL = "ask_user_question";

    /** 参考实现 HTTP 超时（秒）。 */
    static final int HTTP_TIMEOUT_SECONDS = 15;

    /** 当前调用的运行时上下文（对应 {@code ToolRuntime.context}）。 */
    static final ThreadLocal<BaseContext> CONTEXT = new ThreadLocal<>();

    /** 当前调用的 tool_call_id（对应 {@code InjectedToolCallId}）。 */
    static final ThreadLocal<String> TOOL_CALL_ID = new ThreadLocal<>();

    /** 参考实现 {@code DOUBAO_SEARCH_DESCRIPTION}（逐字）。 */
    public static final String DOUBAO_SEARCH_DESCRIPTION = """
            执行网络网页搜索，通过豆包联网搜索获取实时高质量互联网网页内容、新闻和站点资料。

            适用场景：
            1. 获取最新的时事新闻、即时信息或最新科技动态
            2. 检索特定网站的内容（通过 sites 参数指定）
            3. 查找指定时间范围内发布的新闻或文章（通过 time_range 参数过滤）

            参数使用建议：
            - query: 输入简短清晰的搜索关键词或简短提问
            - count: 默认 10 条，深度调研可适当调大（最多 50 条）
            - time_range: 需要最新消息或时效性强的资讯时建议传入 'OneDay'、'OneWeek' 或 'OneMonth'
            - sites: 仅需特定站点（如官媒、平台）时传入站点域名
            """;

    /** 参考实现 {@code PRESENT_ARTIFACTS_DESCRIPTION}（逐字）。 */
    public static final String PRESENT_ARTIFACTS_DESCRIPTION = """
            将已经生成好的结果文件展示给用户。

            使用场景：
            1. 你已经写好了最终结果文件；建议放在当前 Project Workdir 的 `outputs/` 下
            2. 你希望前端在对话结束后显示这些结果文件卡片
            3. 这些文件需要支持下载或预览

            注意事项：
            1. 可以传入当前 Project Workdir、User Data 或已授权 Skills 中的普通文件
            2. 不要传入中间过程文件，只有真正需要给用户看的结果文件才调用
            3. 可以一次传多个文件
            """;

    /** 参考实现 {@code OCR_PARSE_FILE_DESCRIPTION}（逐字）。 */
    public static final String OCR_PARSE_FILE_DESCRIPTION = """
            将沙盒中的 PDF、Office 文档或图片文件解析为 Markdown 文本，并把结果保存为文件。

            使用场景：
            1. 用户上传了 PDF、Office 文档或图片附件，需要提取其中的文字内容
            2. Project Workdir、User Data 或 Skills 下已有文件，需要转成可读取的 Markdown
            3. 解析结果较长，后续应使用 read_file 读取保存后的 Markdown 文件

            注意事项：
            1. file_path 必须位于当前用户可见范围
            2. 解析结果会写入当前 Project Workdir 的 outputs/ocr/ 下
            4. 工具只返回结果文件路径和短预览，不直接返回完整 OCR 文本
            5. 如需在前端展示结果文件，请再调用 present_artifacts
            """;

    /** 参考实现 {@code ASK_USER_QUESTION_DESCRIPTION}（逐字）。 */
    public static final String ASK_USER_QUESTION_DESCRIPTION = """
            在执行过程中，当你需要用户做决定或补充需求时，使用这个工具向用户提问。

            适用场景：
            1. 收集用户偏好或需求（例如风格、范围、优先级）
            2. 澄清模糊指令（存在多种合理解释时）
            3. 在实现过程中让用户选择方案方向
            4. 在有明显权衡时让用户做取舍

            使用规范：
            1. questions 提供 1-5 个问题，每项包含：question、options、multi_select、allow_other
            2. 每个问题的 options 提供 2-5 个有区分度的选项，每项包含 label 和 value
            3. 若有推荐选项：把推荐项放在第一位，并在 label 末尾加 "(Recommended)"
            4. 若需要多选：将该问题的 multi_select 设为 true
            5. allow_other 通常保持 true，用户可通过 Other 输入自定义答案

            注意事项：
            1. 不要用这个工具询问“是否继续执行”“计划是否准备好”这类流程控制问题
            2. 不要在信息已充分、无需用户决策时滥用该工具
            3. 先基于现有上下文自行决策，只有关键不确定性时才提问

            返回结果：
            answer 为 object，格式为 {question_id: answer}。
            其中 answer 可能是 string（单选）、list（多选）或 object（Other 文本）。
            """;

    /** 参考实现 {@code DoubaoSearchInput} 的入参 schema。 */
    public static final String DOUBAO_SEARCH_SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"搜索查询词，1-100字符，必须精准描述检索需求"},
              "count":{"type":"integer","description":"返回搜索结果数量，支持 1-50 条，默认 10 条","minimum":1,"maximum":50,"default":10},
              "time_range":{"type":"string","description":"按发文时间筛选结果。可选枚举值:\\n- 'OneDay': 近24小时内\\n- 'OneWeek': 近1周内\\n- 'OneMonth': 近1个月内\\n- 'OneYear': 近1年内\\n- 'YYYY-MM-DD..YYYY-MM-DD': 自定义日期范围区间"},
              "sites":{"type":"array","items":{"type":"string"},"description":"指定限定搜索的完整域名列表 (如 ['sohu.com', '163.com'])，最多支持 20 个站点"},
              "block_hosts":{"type":"array","items":{"type":"string"},"description":"指定屏蔽的搜索域名列表 (如 ['example.com'])，最多支持 5 个站点"},
              "content_format":{"type":"string","description":"正文返回格式，支持 'text' (纯文本) 或 'markdown' (Markdown 格式)，默认 'text'","default":"text"}
            },"required":["query"]}""";

    /** 参考实现 {@code PresentArtifactsInput} 的入参 schema。 */
    public static final String PRESENT_ARTIFACTS_SCHEMA = """
            {"type":"object","properties":{
              "filepaths":{"type":"array","items":{"type":"string"},"description":"需要展示给用户的文件绝对路径列表；建议把交付物放在 Project outputs/ 下"}
            },"required":["filepaths"]}""";

    /** 参考实现 {@code OcrParseFileInput} 的入参 schema。 */
    public static final String OCR_PARSE_FILE_SCHEMA = """
            {"type":"object","properties":{
              "file_path":{"type":"string","description":"需要 OCR 解析的 Project、User Data 或已授权 Skill 文件绝对路径"},
              "ocr_engine":{"type":"string","description":"可选 OCR 引擎；省略时使用系统默认 OCR 引擎"}
            },"required":["file_path"]}""";

    private BuildinTools() {}

    /** 绑定一次工具调用的运行时上下文与 tool_call_id。 */
    public static void bind(BaseContext context, String toolCallId) {
        CONTEXT.set(context);
        TOOL_CALL_ID.set(toolCallId == null ? "" : toolCallId);
    }

    // ==================== web_search：豆包 ====================

    /** 对应参考实现 {@code _build_doubao_search_payload}。 */
    static Map<String, Object> buildDoubaoSearchPayload(
            String query,
            int count,
            String timeRange,
            List<String> sites,
            List<String> blockHosts,
            String contentFormat) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("NeedUrl", true);
        if (sites != null && !sites.isEmpty()) {
            filter.put("Sites", String.join("|", sites.subList(0, Math.min(20, sites.size()))));
        }
        if (blockHosts != null && !blockHosts.isEmpty()) {
            filter.put("BlockHosts", String.join("|", blockHosts.subList(0, Math.min(5, blockHosts.size()))));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Query", query == null ? "" : query.substring(0, Math.min(100, query.length())));
        payload.put("SearchType", "web");
        payload.put("Count", Math.min(Math.max(1, count), 50));
        payload.put("Filter", filter);
        payload.put("ContentFormats",
                contentFormat != null && contentFormat.equalsIgnoreCase("markdown") ? "markdown" : "text");
        if (timeRange != null && !timeRange.isEmpty()) {
            payload.put("TimeRange", timeRange);
        }
        return payload;
    }

    /** 对应参考实现 {@code _parse_doubao_search_response}。 */
    static Map<String, Object> parseDoubaoSearchResponse(String query, Map<String, Object> data) {
        Map<String, Object> responseMetadata = asMap(data.get("ResponseMetadata"));
        Object errorInfo = responseMetadata.get("Error");
        if (errorInfo != null) {
            log.error("Doubao search API returned error: {}", errorInfo);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("query", query);
            error.put("results", new ArrayList<>());
            error.put("error", asMap(errorInfo).getOrDefault("Message", "Unknown error"));
            return error;
        }
        Map<String, Object> resultData = asMap(data.get("Result"));
        List<Map<String, Object>> results = new ArrayList<>();
        Object webResults = resultData.get("WebResults");
        if (webResults instanceof List<?> list) {
            for (Object entry : list) {
                Map<String, Object> item = asMap(entry);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("title", firstText(item, "Title"));
                result.put("url", firstText(item, "Url"));
                result.put("content", firstText(item, "Summary", "Snippet", "Content"));
                result.put("score", item.get("RankScore"));
                if (item.get("SiteName") != null) {
                    result.put("site_name", item.get("SiteName"));
                }
                if (item.get("PublishTime") != null) {
                    result.put("publish_time", item.get("PublishTime"));
                }
                results.add(result);
            }
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("query", query);
        parsed.put("results", results);
        double timeCost = resultData.get("TimeCost") instanceof Number number ? number.doubleValue() : 0d;
        parsed.put("response_time", timeCost / 1000.0);
        return parsed;
    }

    /** 对应参考实现 {@code _doubao_search}（HTTP 走 JDK {@link HttpClient}）。 */
    static Map<String, Object> doubaoSearch(
            String query,
            int count,
            String timeRange,
            List<String> sites,
            List<String> blockHosts,
            String contentFormat) {
        String apiKey = System.getenv("DOUBAO_SEARCH_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("query", query);
            missing.put("results", new ArrayList<>());
            missing.put("error", "DOUBAO_SEARCH_API_KEY 未配置");
            return missing;
        }
        Map<String, Object> payload =
                buildDoubaoSearchPayload(query, count, timeRange, sites, blockHosts, contentFormat);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(DOUBAO_SEARCH_URL))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.toJSONString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            Map<String, Object> data = JSON.parseObject(response.body());
            return parseDoubaoSearchResponse(query, data == null ? Map.of() : data);
        } catch (Exception exc) {
            log.error("Doubao search failed: {}", exc.toString());
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("query", query);
            failed.put("results", new ArrayList<>());
            failed.put("error", String.valueOf(exc));
            return failed;
        }
    }

    /**
     * 对应参考实现 {@code _resolve_web_search_provider}。
     *
     * <p>能力差异（类注释 2）：选中 {@code tavily} 时返回 {@code null}（无 {@code TavilySearch} 实现）。
     */
    static String resolveWebSearchProvider() {
        String configured = System.getenv("WEB_SEARCH_PROVIDER");
        configured = configured == null ? "" : configured.strip().toLowerCase();
        if (!configured.isEmpty()) {
            String envKey = switch (configured) {
                case "doubao" -> "DOUBAO_SEARCH_API_KEY";
                case "tavily" -> "TAVILY_API_KEY";
                default -> null;
            };
            if (envKey == null) {
                log.warn("Unknown WEB_SEARCH_PROVIDER '{}', ignoring.", configured);
                return null;
            }
            if (configured.equals("tavily")) {
                log.warn("WEB_SEARCH_PROVIDER is set to 'tavily', but no Tavily implementation is available.");
                return null;
            }
            String value = System.getenv(envKey);
            if (value == null || value.isEmpty()) {
                log.warn("WEB_SEARCH_PROVIDER is set to '{}', but {} is not configured.", configured, envKey);
                return null;
            }
            return configured;
        }
        if (isConfigured("DOUBAO_SEARCH_API_KEY")) {
            return "doubao";
        }
        if (isConfigured("TAVILY_API_KEY")) {
            log.warn("TAVILY_API_KEY is configured, but no Tavily implementation is available.");
        }
        return null;
    }

    /**
     * 对应参考实现 {@code _register_web_search_tool}（模块加载期注册）。
     *
     * <p>注册成功返回工具实例，未选中供应商返回 {@code null}（与参考实现静默跳过一致）。
     */
    public static WebSearchTool registerWebSearchTool() {
        String provider = resolveWebSearchProvider();
        if (provider == null) {
            return null;
        }
        WebSearchTool tool = new WebSearchTool();
        ToolkitsRegistry.register(tool, new ToolkitsRegistry.ToolExtraMetadata(
                "buildin", List.of("搜索"), "豆包 网页搜索", "", ""));
        return tool;
    }

    // ==================== present_artifacts ====================

    /** {@code present_artifacts} 的 {@code Command} 载体（见类注释「能力差异 1」）。 */
    public record ArtifactCommand(List<String> artifacts, String message) {
    }

    /** 对应参考实现 {@code _normalize_presented_artifact_path}。 */
    static String normalizePresentedArtifactPath(
            String filepath, BaseContext context, Predicate<String> fileExists) {
        resolveRuntimeSandboxScope(context);
        String normalizedInput = filepath == null ? "" : filepath.strip();
        if (normalizedInput.isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        String normalizedPath = normalizedInput.startsWith("/") ? normalizedInput : "/" + normalizedInput;
        String workdirPath = stripTrailingSlash(context == null ? null : context.getString("workdir_path"));
        boolean allowed = (!workdirPath.isEmpty() && normalizedPath.startsWith(workdirPath + "/"))
                || normalizedPath.startsWith(stripTrailingSlash(BackendPaths.VIRTUAL_PATH_PREFIX) + "/")
                || normalizedPath.startsWith(BackendPaths.VIRTUAL_SKILLS_PATH + "/");
        if (workdirPath.isEmpty() || !allowed) {
            throw new IllegalArgumentException("文件不在当前用户可见范围内: " + normalizedInput);
        }
        // 沙盒「是否为普通文件」校验（参考实现 tools.py:244-245 的 regular_file_exists）。
        // 能力差异 3 于 2026-09-23 接线：沙盒数据面已落地，此处改为真实查询；
        // 未绑定 backend（fileExists 为 null）时按"文件不存在"处理，不静默放行。
        if (fileExists == null || !fileExists.test(normalizedPath)) {
            throw new IllegalArgumentException("文件不存在或不是普通文件: " + normalizedInput);
        }
        return normalizedPath;
    }

    /** 对应参考实现 {@code present_artifacts}。 */
    static ArtifactCommand presentArtifacts(
            List<String> filepaths, BaseContext context, Predicate<String> fileExists) {
        try {
            List<String> normalized = new ArrayList<>();
            for (String filepath : filepaths == null ? List.<String>of() : filepaths) {
                normalized.add(normalizePresentedArtifactPath(filepath, context, fileExists));
            }
            return new ArtifactCommand(normalized, "已将交付物展示给用户");
        } catch (IllegalArgumentException exc) {
            return new ArtifactCommand(List.of(), "Error: " + exc.getMessage());
        }
    }

    /** 注册 {@code present_artifacts}（category=buildin）。 */
    public static PresentArtifactsTool registerPresentArtifactsTool() {
        PresentArtifactsTool tool = new PresentArtifactsTool(null, null);
        ToolkitsRegistry.register(tool, new ToolkitsRegistry.ToolExtraMetadata(
                "buildin", List.of("文件", "交付物"), "展示交付物", "", ""));
        return tool;
    }

    /**
     * {@code present_artifacts} 工具本体（每 Run 经 {@code boundTo} 绑定沙盒 backend 与运行时上下文）。
     *
     * <p>对应参考实现 {@code present_artifacts}（tools.py:271-287）：把已生成的普通文件登记进
     * {@code artifacts} 并回一句 ToolMessage 文本。参考实现靠
     * {@code Command(update={"artifacts": ..., "messages": [...]})} 写 state，
     * 本工程工具只能返回字符串，故 artifacts 经 {@link AgentStateWriteback} 暂存、
     * 由 {@code AgentStateWritebackHook} 落回 OverAllState（与参考实现落点等价）。
     */
    public static final class PresentArtifactsTool implements ToolDefinition, ToolCallback {

        private final SandboxFilesystemBackendAdapter backend;
        private final BaseContext context;

        public PresentArtifactsTool(SandboxFilesystemBackendAdapter backend, BaseContext context) {
            this.backend = backend;
            this.context = context;
        }

        public PresentArtifactsTool boundTo(SandboxFilesystemBackendAdapter backendValue, BaseContext contextValue) {
            return new PresentArtifactsTool(backendValue, contextValue);
        }

        @Override
        public String getName() {
            return PRESENT_ARTIFACTS_TOOL;
        }

        @Override
        public String getDescription() {
            return PRESENT_ARTIFACTS_DESCRIPTION;
        }

        @Override
        public Map<String, Object> getArgsSchema() {
            return JSON.parseObject(PRESENT_ARTIFACTS_SCHEMA);
        }

        public String getCategory() {
            return "buildin";
        }

        public String getDisplayName() {
            return "展示交付物";
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(PRESENT_ARTIFACTS_TOOL)
                    .description(PRESENT_ARTIFACTS_DESCRIPTION)
                    .inputSchema(PRESENT_ARTIFACTS_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            Map<String, Object> args;
            try {
                args = JSON.parseObject(toolInput == null ? "{}" : toolInput,
                        new com.alibaba.fastjson2.TypeReference<Map<String, Object>>() {});
            } catch (RuntimeException exc) {
                return "Error: invalid tool input: " + exc.getMessage();
            }
            List<String> filepaths = new ArrayList<>();
            Object raw = args == null ? null : args.get("filepaths");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        filepaths.add(String.valueOf(item));
                    }
                }
            }
            ArtifactCommand command = presentArtifacts(filepaths, context, this::regularFileExists);
            if (command.artifacts().isEmpty()) {
                return command.message();
            }
            AgentStateWriteback.addArtifacts(context, command.artifacts());
            return command.message();
        }

        /** 沙盒普通文件校验（未绑定 backend 时返回 false：宁可不展示，也不登记不存在的文件）。 */
        private boolean regularFileExists(String virtualPath) {
            return backend != null && backend.regularFileExists(virtualPath);
        }
    }

    // ==================== ocr_parse_file ====================

    /** 对应参考实现 {@code _resolve_ocr_source_path}（沙盒校验见能力差异 3/4）。 */
    static String resolveOcrSourcePath(String filePath, BaseContext context) {
        resolveRuntimeSandboxScope(context);
        String normalizedInput = filePath == null ? "" : filePath.strip();
        if (normalizedInput.isEmpty()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        if (PosixPathLite.parse(normalizedInput).partsContain("..")) {
            throw new IllegalArgumentException("只允许解析当前用户可见范围内的文件");
        }
        String cleanVirtualPath = "/" + stripLeadingSlashes(normalizedInput);
        String workdirPath = stripTrailingSlash(context == null ? null : context.getString("workdir_path"));
        boolean allowed = (!workdirPath.isEmpty() && cleanVirtualPath.startsWith(workdirPath + "/"))
                || cleanVirtualPath.startsWith(stripTrailingSlash(BackendPaths.VIRTUAL_PATH_PREFIX) + "/")
                || cleanVirtualPath.startsWith(BackendPaths.VIRTUAL_SKILLS_PATH + "/");
        if (workdirPath.isEmpty() || !allowed) {
            throw new IllegalArgumentException("只允许解析当前用户可见范围内的文件");
        }
        return cleanVirtualPath;
    }

    /** 对应参考实现 {@code _next_ocr_output_path}（重名追加 {@code -1/-2...}）。 */
    static String nextOcrOutputPath(String workdirPath, String sourceVirtualPath, Predicate<String> exists) {
        String baseName = safeOcrOutputStem(sourceVirtualPath);
        String candidate = workdirPath + "/outputs/" + OCR_OUTPUT_DIR_NAME + "/" + baseName + ".md";
        int index = 1;
        while (exists.test(candidate)) {
            candidate = workdirPath + "/outputs/" + OCR_OUTPUT_DIR_NAME + "/" + baseName + "-" + index + ".md";
            index++;
        }
        return candidate;
    }

    /** 对应参考实现 {@code _safe_ocr_output_stem}。 */
    static String safeOcrOutputStem(String sourcePath) {
        String stem = PosixPathLite.stemOf(sourcePath);
        stem = (stem == null ? "" : stem).strip();
        if (stem.isEmpty()) {
            stem = "ocr_result";
        }
        String safe = stem.replaceAll(SAFE_OUTPUT_STEM_PATTERN, "_").replaceAll("^[._-]+|[._-]+$", "");
        return safe.isEmpty() ? "ocr_result" : safe;
    }

    /** 对应参考实现 {@code _ocr_preview}（返回 [预览, 是否被截断]）。 */
    public record OcrPreview(String preview, boolean truncated) {
    }

    /** 对应参考实现 {@code _ocr_preview}。 */
    static OcrPreview ocrPreview(String markdown) {
        String text = markdown == null ? "" : markdown;
        if (text.length() <= OCR_PREVIEW_LIMIT) {
            return new OcrPreview(text, false);
        }
        String head = text.substring(0, OCR_PREVIEW_LIMIT);
        return new OcrPreview(stripTrailing(head), true);
    }

    // ==================== ask_user_question ====================

    /** {@code ask_user_question} 的 {@code interrupt} 载体（见类注释「能力差异 1」）。 */
    public record AskUserCommand(List<Map<String, Object>> questions, Map<String, Object> interruptPayload) {
    }

    /** 对应参考实现 {@code ask_user_question}（{@code interrupt} 由构图方承载）。 */
    static AskUserCommand askUserQuestion(Object questions) {
        Object parsed = questions;
        if (parsed instanceof String text) {
            try {
                parsed = JSON.parse(text);
                log.debug("Parsed string questions to list: {}", parsed);
            } catch (RuntimeException exc) {
                log.error("Failed to parse questions string: {}, using None", exc.toString());
                parsed = null;
            }
        }
        List<Map<String, Object>> normalized = QuestionUtils.normalizeQuestions(parsed == null ? List.of() : parsed);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("questions 至少需要包含一个有效问题");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questions", normalized);
        payload.put("source", "ask_user_question");
        return new AskUserCommand(normalized, payload);
    }

    // ==================== 运行时作用域 ====================

    /** 对应参考实现 {@code _resolve_runtime_sandbox_scope}。 */
    static SandboxScope resolveRuntimeSandboxScope(BaseContext context) {
        String runtimeThreadId = firstNonBlank(
                runtimeScopeValue(context, "runtime_scope_id"), runtimeScopeValue(context, "thread_id"));
        String uid = runtimeScopeValue(context, "uid");
        String workdirPath = runtimeScopeValue(context, "workdir_relative_path");
        if (runtimeThreadId == null) {
            throw new IllegalArgumentException("当前运行时缺少 thread_id");
        }
        if (uid == null) {
            throw new IllegalArgumentException("当前运行时缺少 uid");
        }
        if (workdirPath == null) {
            throw new IllegalArgumentException("当前运行时缺少 workdir_relative_path");
        }
        return new SandboxScope(runtimeThreadId, uid, workdirPath);
    }

    /** {@code _resolve_runtime_sandbox_scope} 的返回三元组。 */
    public record SandboxScope(String runtimeThreadId, String uid, String workdirRelativePath) {
    }

    /** 对应参考实现 {@code _runtime_scope_value}（本工程从 context 读取）。 */
    static String runtimeScopeValue(BaseContext context, String key) {
        if (context == null) {
            return null;
        }
        String value = context.getString(key);
        return value == null || value.strip().isEmpty() ? null : value.strip();
    }

    // ==================== 工具载体 ====================

    /** {@code web_search} 工具（豆包链路）。 */
    public static final class WebSearchTool implements ToolDefinition, ToolCallback {

        @Override
        public String getName() {
            return WEB_SEARCH_TOOL;
        }

        @Override
        public String getDescription() {
            return DOUBAO_SEARCH_DESCRIPTION;
        }

        @Override
        public Map<String, Object> getArgsSchema() {
            return JSON.parseObject(DOUBAO_SEARCH_SCHEMA);
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(WEB_SEARCH_TOOL)
                    .description(DOUBAO_SEARCH_DESCRIPTION)
                    .inputSchema(DOUBAO_SEARCH_SCHEMA)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            Map<String, Object> args = KnowledgeTools.parseArgs(toolInput);
            Map<String, Object> result = doubaoSearch(
                    KnowledgeTools.stringArg(args, "query"),
                    KnowledgeTools.intArg(args, "count", 10),
                    KnowledgeTools.stringArg(args, "time_range"),
                    KnowledgeTools.stringListArg(args, "sites"),
                    KnowledgeTools.stringListArg(args, "block_hosts"),
                    KnowledgeTools.stringArg(args, "content_format") == null
                            ? "text" : KnowledgeTools.stringArg(args, "content_format"));
            return JSON.toJSONString(result);
        }
    }

    // ==================== 小工具 ====================

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    static String firstText(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            Object value = item.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    static boolean isConfigured(String envKey) {
        String value = System.getenv(envKey);
        return value != null && !value.isEmpty();
    }

    static String stripTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    static String stripLeadingSlashes(String value) {
        return value == null ? "" : value.replaceAll("^/+", "");
    }

    static String stripTrailing(String value) {
        return value == null ? "" : value.replaceAll("\\s+$", "");
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
