package com.wisesoft.wenqu.agents;

import com.alibaba.fastjson2.JSON;
import com.wisesoft.wenqu.agents.ToolkitsRegistry.ToolDefinition;
import com.wisesoft.wenqu.common.PosixPathLite;
import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.models.KnowledgeBase;
import com.wisesoft.wenqu.repositories.KnowledgeBaseRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

/**
 * 知识库工具集（对应参考实现 {@code agents/toolkits/kbs/tools.py}）。
 *
 * <p>7 个工具逐条对位：{@code list_kbs} / {@code get_mindmap} / {@code query_kb} /
 * {@code open_kb_document} / {@code find_kb_document} / {@code search_file} /
 * {@code download_kb_file}。注册分类、标签与展示名与参考实现的 {@code @tool(...)} 装饰器逐字一致。
 *
 * <p>这 7 个工具正是「knowledge-base」内置 Skill 声明的依赖工具
 * （{@code RuntimeSkill.tools}），由 {@code SkillsMiddleware} 做激活门控。
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li>{@code @tool(category=..., tags=..., display_name=...)} → 显式构造
 *       {@link ToolkitsRegistry.ToolExtraMetadata} 并 {@link ToolkitsRegistry#register}。</li>
 *   <li>{@code runtime: ToolRuntime}（LangGraph 按调用注入）→ {@link ToolRuntime} 作为
 *       <b>执行体的显式入参</b>（与参考实现各工具函数的 {@code runtime} 形参逐位对位）；
 *       构图方按本次 Run 用 {@link KnowledgeTool#boundTo} 派生绑定副本，
 *       注册表里保留的是不带 runtime 的原型（与 {@code @tool} 装饰器收集的对象同义）。</li>
 *   <li>{@code knowledge_base}（模块级单例，函数内迟延导入）→ {@link ToolRuntime#manager()}
 *       （本工程知识库运行时即 {@link KnowledgeBaseManager}）。</li>
 *   <li>pydantic {@code args_schema} → JSON Schema 常量字符串（字段名与必填项逐字对齐）。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>{@code download_kb_file} 的沙盒落盘尚未接线</b>：参考实现写入
 *       {@code ProvisionerSandboxBackend}（该沙盒数据面**已随 §三 backends 落地**，见
 *       {@code agents/backends/sandbox/}；原文写「未搬」，2026-09-20 更正 → 是「未接线」）。本类保留工具签名、
 *       可见性校验、下载与路径计算（{@link #resolveDownloadOutputPath}），但在缺少沙盒后端时返回
 *       明确的不可用文案，不静默成功（与 {@code dify} / {@code notion} / {@code skill_remote_install}
 *       同口径）。</li>
 *   <li><b>工具返回值为字符串</b>：参考实现可直接返回 dict/list；本工程 {@link ToolCallback}
 *       返回 JSON 字符串（结构、字段名与参考实现返回值逐字一致）。</li>
 * </ol>
 */
@Slf4j
public final class KnowledgeTools {

    /**
     * 一次工具调用所需的运行时依赖（对应参考实现由 LangGraph 注入的 {@code ToolRuntime}）。
     *
     * <p>参考实现里 {@code runtime} 是各工具函数的形参 —— 即「每次调用各自携带一份 runtime」。
     * 本工程把它还原成显式数据载体：{@link #getCommonKbTools()} 产出的注册表原型不带 runtime，
     * 构图方在构图时用 {@link KnowledgeTool#boundTo} 派生绑定副本后再交给引擎执行。
     *
     * @param context    运行时上下文（{@code runtime.context}）
     * @param manager    知识库门面（对位模块级单例 {@code yuxi.knowledge.runtime.knowledge_base}）
     * @param backend    可见知识库解析后端（{@code runtime.backend}，可为 null）
     * @param repository 知识库仓储（{@code get_mindmap} 读 {@code mindmap} 列用）
     */
    public record ToolRuntime(
            BaseContext context,
            KnowledgeBaseManager manager,
            KnowledgeBaseBackend backend,
            KnowledgeBaseRepository repository) {}

    /** 参考实现 {@code list_kbs} 的入参 schema。 */
    static final String LIST_KBS_SCHEMA = schema(Map.of("dummy", stringProperty("Dummy parameter - ignore")), List.of());

    /** 参考实现 {@code get_mindmap} 的入参 schema。 */
    static final String GET_MINDMAP_SCHEMA =
            schema(Map.of("kb_name", stringProperty("知识库名称，用于指定要获取思维导图的知识库")), List.of("kb_name"));

    /** 参考实现 {@code query_kb}（{@code SearchInputSchema}）的入参 schema。 */
    static final String QUERY_KB_SCHEMA = schema(
            new LinkedHashMap<>(Map.of(
                    "kb_id", stringProperty("知识库资源 ID"),
                    "query_text", stringProperty("查询内容"),
                    "file_name", stringProperty("可选，限定在某个文件内检索"))),
            List.of("kb_id", "query_text"));

    /** 参考实现 {@code open_kb_document}（{@code OpenInputSchema}）的入参 schema。 */
    static final String OPEN_KB_DOCUMENT_SCHEMA = schema(
            new LinkedHashMap<>(Map.of(
                    "kb_id", stringProperty("知识库资源 ID"),
                    "file_id", stringProperty("知识库文件 ID"),
                    "line", intProperty("可选，起始行号（1 开始）"),
                    "offset", intProperty("可选，起始字符偏移"),
                    "window_size", intProperty("窗口大小，默认 1800"))),
            List.of("kb_id", "file_id"));

    /** 参考实现 {@code find_kb_document}（{@code FindInputSchema}）的入参 schema。 */
    static final String FIND_KB_DOCUMENT_SCHEMA = schema(
            new LinkedHashMap<>(Map.of(
                    "kb_id", stringProperty("知识库资源 ID"),
                    "file_id", stringProperty("知识库文件 ID"),
                    "patterns", Map.of("type", "array", "items", Map.of("type", "string"),
                            "description", "关键词或正则模式列表"),
                    "use_regex", Map.of("type", "boolean", "description", "是否按正则匹配，默认 false"),
                    "case_sensitive", Map.of("type", "boolean", "description", "是否区分大小写，默认 false"),
                    "max_windows", intProperty("最多返回窗口数，默认 5"),
                    "window_size", intProperty("窗口大小，默认 80"))),
            List.of("kb_id", "file_id", "patterns"));

    /** 参考实现 {@code search_file} 的入参 schema。 */
    static final String SEARCH_FILE_SCHEMA = schema(
            new LinkedHashMap<>(Map.of(
                    "kb_name", stringProperty("知识库名称，为空时搜索所有知识库"),
                    "query", stringProperty("搜索关键词，为空时返回所有文件"),
                    "offset", intProperty("偏移量，从 0 开始"),
                    "limit", intProperty("返回数量限制，默认 300"))),
            List.of());

    /** 参考实现 {@code download_kb_file} 的入参 schema。 */
    static final String DOWNLOAD_KB_FILE_SCHEMA = schema(
            new LinkedHashMap<>(Map.of(
                    "kb_id", stringProperty("知识库资源 ID"),
                    "file_id", stringProperty("知识库文件 ID，来自 query_kb 或 search_file 的返回结果"),
                    "save_as", stringProperty("落盘文件名；为空时使用原始文件名。仅取文件名部分，不可包含目录"))),
            List.of("kb_id", "file_id"));

    private KnowledgeTools() {}

    /**
     * 对应参考实现 {@code get_common_kb_tools}：返回 7 个通用知识库工具（按参考实现的顺序）。
     */
    public static List<KnowledgeTool> getCommonKbTools() {
        List<KnowledgeTool> tools = new ArrayList<>();
        tools.add(new KnowledgeTool(
                "list_kbs",
                "列出当前用户可访问的知识库列表\n\n"
                        + "返回用户基于权限可访问的知识库名称列表。这个列表是根据用户的角色和部门信息过滤后的结果，\n"
                        + "但不包括用户在当前对话中未启用的知识库。",
                LIST_KBS_SCHEMA,
                "knowledge", "列出知识库",
                KnowledgeTools::listKbs));
        tools.add(new KnowledgeTool(
                "get_mindmap",
                "获取指定知识库的思维导图结构\n\n"
                        + "当用户想要了解知识库的整体结构、文件分类、知识架构时使用此工具。",
                GET_MINDMAP_SCHEMA,
                "knowledge", "获取思维导图",
                KnowledgeTools::getMindmap));
        tools.add(new KnowledgeTool(
                "query_kb",
                "在指定知识库中检索内容\n\n"
                        + "当用户需要查询具体内容时使用此工具。返回结果中的 file_id 可继续用于 "
                        + "find_kb_document 或 open_kb_document。",
                QUERY_KB_SCHEMA,
                "knowledge", "检索知识库",
                KnowledgeTools::queryKb));
        tools.add(new KnowledgeTool(
                "open_kb_document",
                "按行窗口打开知识库文档原文\n\n"
                        + "当 query_kb 返回的片段不足以回答问题，或需要查看某个文档的上下文时使用。",
                OPEN_KB_DOCUMENT_SCHEMA,
                "knowledge", "打开知识库文档",
                KnowledgeTools::openKbDocument));
        tools.add(new KnowledgeTool(
                "find_kb_document",
                "在已知知识库文件内做关键词或正则定位。\n\n"
                        + "当 query_kb 已找到候选文件，但需要在该文件内定位术语、指标、章节或实体时使用。",
                FIND_KB_DOCUMENT_SCHEMA,
                "knowledge", "定位文档内容",
                KnowledgeTools::findKbDocument));
        tools.add(new KnowledgeTool(
                "search_file",
                "搜索知识库中的文件\n\n如果不指定知识库，将搜索所有可访问的知识库；如果不指定搜索关键词，将返回所有文件。",
                SEARCH_FILE_SCHEMA,
                "knowledge", "搜索知识库文件",
                KnowledgeTools::searchFile));
        tools.add(new KnowledgeTool(
                "download_kb_file",
                "下载知识库文件的原始二进制（pdf/docx/xlsx 等）到沙盒 outputs 目录。\n\n"
                        + "当后续需要对原始文件结构做处理时使用；query_kb/open_kb_document 只返回文本切片。",
                DOWNLOAD_KB_FILE_SCHEMA,
                "knowledge", "下载知识库文件",
                KnowledgeTools::downloadKbFile));
        return tools;
    }

    /** 注册 7 个工具到 {@link ToolkitsRegistry}（对应装饰器在导入期的自动收集）。 */
    public static List<KnowledgeTool> registerAll() {
        List<KnowledgeTool> tools = getCommonKbTools();
        for (KnowledgeTool tool : tools) {
            ToolkitsRegistry.register(tool, new ToolkitsRegistry.ToolExtraMetadata(
                    "knowledge", List.of("知识库"), tool.getDisplayName(), "", ""));
        }
        return tools;
    }

    /** 供构图方注册进引擎的「已绑定本次 Run runtime」的 {@link ToolCallback} 视图（7 个，顺序同参考实现）。 */
    public static List<ToolCallback> getToolCallbacks(ToolRuntime runtime) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (KnowledgeTool tool : getCommonKbTools()) {
            callbacks.add(tool.boundTo(runtime));
        }
        return callbacks;
    }

    // ==================== 7 个工具执行体 ====================

    /** 对应 {@code list_kbs}。 */
    static Object listKbs(ToolRuntime runtime, Map<String, Object> args) {
        BaseContext context = runtime == null ? null : runtime.context();
        String uid = context == null ? null : context.getString("uid");
        if (uid == null || uid.isEmpty()) {
            return "无法获取用户信息";
        }
        List<Map<String, Object>> available = resolveVisibleKnowledgeBasesForQuery(runtime);
        if (available.isEmpty()) {
            return "当前没有可访问的知识库";
        }
        List<Map<String, Object>> formatted = new ArrayList<>();
        for (Map<String, Object> kb : available) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kb_id", kb.get("kb_id"));
            item.put("name", kb.getOrDefault("name", ""));
            Object description = kb.get("description");
            item.put("description", description == null || String.valueOf(description).isEmpty()
                    ? "无描述" : description);
            formatted.add(item);
        }
        return formatted;
    }

    /** 对应 {@code get_mindmap}。 */
    static Object getMindmap(ToolRuntime runtime, Map<String, Object> args) {
        String kbName = stringArg(args, "kb_name");
        if (kbName == null || kbName.isEmpty()) {
            return "请提供知识库名称";
        }
        List<Map<String, Object>> visible = resolveVisibleKnowledgeBasesForQuery(runtime);
        Map<String, Object> target = null;
        for (Map<String, Object> kb : visible) {
            if (kbName.equals(kb.get("name"))) {
                target = kb;
                break;
            }
        }
        if (target == null) {
            return "知识库 '" + kbName + "' 不存在或当前会话未启用";
        }
        String targetKbId = String.valueOf(target.get("kb_id"));
        try {
            KnowledgeBaseRepository repository = runtime == null ? null : runtime.repository();
            KnowledgeBase kb = repository == null ? null : repository.getByKbId(targetKbId);
            if (kb == null) {
                return "知识库 " + target.get("name") + " 不存在";
            }
            Object mindmapData = parseMindmap(kb.getMindmap());
            if (mindmapData == null || (mindmapData instanceof Map<?, ?> map && map.isEmpty())) {
                return "知识库 " + target.get("name") + " 还没有生成思维导图。";
            }
            StringBuilder text = new StringBuilder("知识库 ").append(target.get("name")).append(" 的思维导图结构：\n\n");
            appendMindmap(text, mindmapData, 0);
            return text.toString();
        } catch (RuntimeException exc) {
            log.error("获取思维导图失败: {}", exc.getMessage());
            return "获取思维导图失败: " + exc;
        }
    }

    /** 对应 {@code query_kb}。 */
    static Object queryKb(ToolRuntime runtime, Map<String, Object> args) {
        String kbId = stringArg(args, "kb_id");
        String queryText = stringArg(args, "query_text");
        if (kbId == null || kbId.isEmpty()) {
            return "请提供 kb_id";
        }
        if (queryText == null || queryText.isEmpty()) {
            return "请提供查询内容";
        }
        List<Map<String, Object>> visible = resolveVisibleKnowledgeBasesForQuery(runtime);
        String error = findQueryTarget(kbId, visible);
        if (error != null) {
            return error;
        }
        try {
            Map<String, Object> options = new LinkedHashMap<>();
            String fileName = stringArg(args, "file_name");
            if (fileName != null && !fileName.isEmpty()) {
                options.put("file_name", fileName);
            }
            return runtime.manager().retrieve(kbId, queryText, options);
        } catch (RuntimeException exc) {
            log.error("检索失败: {}", exc.getMessage());
            return "检索失败: " + exc;
        }
    }

    /** 对应 {@code open_kb_document}。 */
    static Object openKbDocument(ToolRuntime runtime, Map<String, Object> args) {
        String kbId = normalize(stringArg(args, "kb_id"));
        String fileId = normalize(stringArg(args, "file_id"));
        if (kbId.isEmpty()) {
            return "请提供 kb_id";
        }
        if (fileId.isEmpty()) {
            return "请提供 file_id";
        }
        List<Map<String, Object>> visible = resolveVisibleKnowledgeBasesForQuery(runtime);
        String error = findQueryTarget(kbId, visible);
        if (error != null) {
            return error;
        }
        try {
            Integer line = intArg(args, "line");
            int startOffset = line != null ? line - 1 : intArg(args, "offset", 0);
            int windowSize = intArg(args, "window_size", 1800);
            return runtime.manager().openDocument(kbId, fileId, startOffset, windowSize);
        } catch (RuntimeException exc) {
            log.error("打开知识库文档失败: {}", exc.getMessage());
            return "打开知识库文档失败: " + exc;
        }
    }

    /** 对应 {@code find_kb_document}。 */
    static Object findKbDocument(ToolRuntime runtime, Map<String, Object> args) {
        String kbId = normalize(stringArg(args, "kb_id"));
        String fileId = normalize(stringArg(args, "file_id"));
        if (kbId.isEmpty()) {
            return "请提供 kb_id";
        }
        if (fileId.isEmpty()) {
            return "请提供 file_id";
        }
        List<String> patterns = stringListArg(args, "patterns");
        if (patterns.isEmpty()) {
            return "请提供 patterns";
        }
        List<Map<String, Object>> visible = resolveVisibleKnowledgeBasesForQuery(runtime);
        String error = findQueryTarget(kbId, visible);
        if (error != null) {
            return error;
        }
        try {
            return runtime.manager().findInDocument(
                    kbId,
                    fileId,
                    patterns,
                    Boolean.TRUE.equals(args.get("use_regex")),
                    Boolean.TRUE.equals(args.get("case_sensitive")),
                    intArg(args, "max_windows", 5),
                    intArg(args, "window_size", 80));
        } catch (RuntimeException exc) {
            log.error("知识库文档内检索失败: {}", exc.getMessage());
            return "知识库文档内检索失败: " + exc;
        }
    }

    /** 对应 {@code search_file}。 */
    static Object searchFile(ToolRuntime runtime, Map<String, Object> args) {
        String kbName = stringArg(args, "kb_name");
        String query = stringArg(args, "query");
        if ((kbName == null || kbName.isEmpty()) && (query == null || query.isEmpty())) {
            return "请提供知识库名称或搜索关键词，不能同时为空";
        }
        List<Map<String, Object>> visible = resolveVisibleKnowledgeBasesForQuery(runtime);
        if (visible.isEmpty()) {
            return "无法获取当前会话可访问的知识库";
        }
        List<Map<String, Object>> targetKbs = new ArrayList<>();
        if (kbName != null && !kbName.isEmpty()) {
            for (Map<String, Object> kb : visible) {
                if (kbName.equals(kb.get("name"))) {
                    targetKbs.add(kb);
                }
            }
            if (targetKbs.isEmpty()) {
                return "知识库 '" + kbName + "' 不存在或当前会话未启用";
            }
        } else {
            targetKbs.addAll(visible);
        }
        KnowledgeBaseManager manager = runtime.manager();
        List<Map<String, Object>> searchable = new ArrayList<>();
        for (Map<String, Object> kb : targetKbs) {
            if (manager.databaseTypeSupportsDocuments(String.valueOf(kb.get("kb_type")))) {
                searchable.add(kb);
            }
        }
        if (searchable.isEmpty()) {
            return "当前匹配的知识库只支持检索，不支持文件搜索";
        }
        return manager.searchDocumentFiles(
                searchable,
                query,
                intArg(args, "offset", 0),
                intArg(args, "limit", 300),
                null,
                false,
                false);
    }

    /** 对应 {@code download_kb_file}（沙盒落盘见类注释「能力差异 1」）。 */
    static Object downloadKbFile(ToolRuntime runtime, Map<String, Object> args) {
        String kbId = normalize(stringArg(args, "kb_id"));
        String fileId = normalize(stringArg(args, "file_id"));
        if (kbId.isEmpty()) {
            return "请提供 kb_id";
        }
        if (fileId.isEmpty()) {
            return "请提供 file_id";
        }
        List<Map<String, Object>> visible = resolveVisibleKnowledgeBasesForQuery(runtime);
        String error = findQueryTarget(kbId, visible);
        if (error != null) {
            return error;
        }
        KnowledgeBaseManager manager = runtime.manager();
        Map<String, Object> data;
        try {
            data = manager.getFileDownload(kbId, fileId, "original");
        } catch (IllegalArgumentException exc) {
            return exc.getMessage();
        } catch (RuntimeException exc) {
            log.error("下载知识库原始文件失败: {}", exc.getMessage());
            return "下载知识库原始文件失败: " + exc;
        }
        SandboxScope scope = runtimeSandboxScope(runtime);
        if (scope == null) {
            return "无法获取当前会话的沙盒上下文，缺少 thread_id 或 uid";
        }
        String outputPath = resolveDownloadOutputPath(
                scope.workdirPath(), data, fileId, stringArg(args, "save_as"),
                path -> Boolean.FALSE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("virtual_path", outputPath);
        result.put("filename", data.get("filename") == null ? fileId : data.get("filename"));
        result.put("media_type", data.get("media_type"));
        result.put("size_bytes", data.get("content") instanceof byte[] bytes ? bytes.length : 0);
        result.put("saved_as", PosixPathLite.nameOf(outputPath));
        return "沙盒校验未接线，无法下载知识库原始文件；"
                + "已解析的目标路径：" + outputPath;
    }

    // ==================== 共享 helper（对应参考实现细节层） ====================

    /** 对应参考实现 {@code _resolve_visible_knowledge_bases_for_query}。 */
    static List<Map<String, Object>> resolveVisibleKnowledgeBasesForQuery(ToolRuntime runtime) {
        BaseContext context = runtime == null ? null : runtime.context();
        if (context == null) {
            return List.of();
        }
        Object cached = context.getDynamic(KnowledgeBaseBackend.VISIBLE_KNOWLEDGE_BASES_ATTR, null);
        if (cached instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> typed = new LinkedHashMap<>();
                    raw.forEach((key, value) -> typed.put(String.valueOf(key), value));
                    result.add(typed);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        try {
            KnowledgeBaseBackend backend = runtime.backend();
            return backend == null ? List.of() : backend.resolveVisibleKnowledgeBasesForContext(context);
        } catch (RuntimeException exc) {
            log.warn("解析会话可见知识库失败: {}", exc.getMessage());
            return List.of();
        }
    }

    /** 对应参考实现 {@code _find_query_target}（校验 kb_id 在会话可见范围内）。 */
    static String findQueryTarget(String kbId, List<Map<String, Object>> visibleKbs) {
        if (visibleKbs == null || visibleKbs.isEmpty()) {
            return "无法获取当前会话可访问的知识库";
        }
        String normalized = normalize(kbId);
        Set<String> visibleIds = new java.util.LinkedHashSet<>();
        for (Map<String, Object> kb : visibleKbs) {
            visibleIds.add(normalize(kb.get("kb_id")));
        }
        if (!visibleIds.contains(normalized)) {
            return "知识库资源 '" + normalized + "' 不存在或当前会话未启用";
        }
        return null;
    }

    /** 对应参考实现 {@code _runtime_sandbox_scope}。 */
    static SandboxScope runtimeSandboxScope(ToolRuntime runtime) {
        BaseContext context = runtime == null ? null : runtime.context();
        if (context == null) {
            return null;
        }
        String runtimeThreadId = firstNonBlank(context.getString("runtime_scope_id"), context.getString("thread_id"));
        String uid = context.getString("uid");
        String workdirRelativePath = context.getString("workdir_relative_path");
        String workdirPath = context.getString("workdir_path");
        if (isBlank(runtimeThreadId) || isBlank(uid) || isBlank(workdirRelativePath) || isBlank(workdirPath)) {
            return null;
        }
        return new SandboxScope(runtimeThreadId, uid, workdirRelativePath, workdirPath);
    }

    /** {@code _runtime_sandbox_scope} 的返回四元组。 */
    public record SandboxScope(String runtimeThreadId, String uid, String workdirRelativePath, String workdirPath) {
    }

    /**
     * 对应参考实现 {@code _resolve_download_output_path}（重名追加 {@code _1/_2...}）。
     *
     * @param exists 沙盒后端的文件存在性判定（本工程无沙盒时传恒 false）
     */
    static String resolveDownloadOutputPath(
            String workdirPath,
            Map<String, Object> data,
            String fileId,
            String saveAs,
            java.util.function.Predicate<String> exists) {
        String wantedName = (saveAs == null || saveAs.isBlank()
                ? String.valueOf(data.getOrDefault("filename", fileId))
                : saveAs).strip();
        String baseName = PosixPathLite.nameOf(wantedName);
        if (baseName == null || baseName.isEmpty()) {
            baseName = fileId;
        }
        String candidate = workdirPath + "/outputs/" + baseName;
        if (!exists.test(candidate)) {
            return candidate;
        }
        int dot = baseName.lastIndexOf('.');
        String stemPart = dot > 0 ? baseName.substring(0, dot) : baseName;
        String suffixPart = dot > 0 ? baseName.substring(dot) : "";
        int index = 1;
        while (exists.test(candidate)) {
            candidate = workdirPath + "/outputs/" + stemPart + "_" + index + suffixPart;
            index++;
        }
        return candidate;
    }

    // ==================== 工具载体 ====================

    /** 递归把思维导图 JSON 转成层级文本（对应参考实现内层 {@code mindmap_to_text}）。 */
    private static void appendMindmap(StringBuilder builder, Object node, int level) {
        if (!(node instanceof Map<?, ?> raw)) {
            return;
        }
        String indent = "  ".repeat(level);
        builder.append(indent).append("- ").append(raw.get("content") == null ? "" : raw.get("content")).append("\n");
        Object children = raw.get("children");
        if (children instanceof List<?> list) {
            for (Object child : list) {
                appendMindmap(builder, child, level + 1);
            }
        }
    }

    /** 思维导图列是 JSON 文本，解析为 Map/List（参考实现直接读 dict）。 */
    static Object parseMindmap(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch (RuntimeException exc) {
            log.warn("思维导图数据解析失败: {}", exc.getMessage());
            return null;
        }
    }

    /**
     * 知识库工具载体：同时实现 {@link ToolDefinition}（注册表/元数据面）与
     * {@link ToolCallback}（框架执行面）。
     *
     * <p>注册表里保存的是 {@code runtime == null} 的<b>原型</b>（与参考实现 {@code @tool}
     * 装饰器收集到的对象同义，只用于名字/描述/schema 与门控）；执行期必须先经
     * {@link #boundTo(ToolRuntime)} 得到携带本次 Run 依赖的副本。
     */
    public static final class KnowledgeTool implements ToolDefinition, ToolCallback {

        private final String name;
        private final String description;
        private final String argsSchema;
        private final String category;
        private final String displayName;
        private final KnowledgeToolBody body;

        /** 本次 Run 的运行时依赖（对应参考实现按调用注入的 {@code ToolRuntime}）。 */
        private final ToolRuntime runtime;

        KnowledgeTool(String name, String description, String argsSchema,
                String category, String displayName, KnowledgeToolBody body) {
            this(name, description, argsSchema, category, displayName, body, null);
        }

        private KnowledgeTool(String name, String description, String argsSchema,
                String category, String displayName, KnowledgeToolBody body, ToolRuntime runtime) {
            this.name = name;
            this.description = description;
            this.argsSchema = argsSchema;
            this.category = category;
            this.displayName = displayName;
            this.body = body;
            this.runtime = runtime;
        }

        /**
         * 派生绑定到本次 Run 的工具副本（对应参考实现里 LangGraph 把 {@code runtime}
         * 作为调用入参注入到每个工具函数）。
         */
        public KnowledgeTool boundTo(ToolRuntime runtime) {
            return new KnowledgeTool(name, description, argsSchema, category, displayName, body, runtime);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getArgsSchema() {
            return JSON.parseObject(argsSchema);
        }

        public String getCategory() {
            return category;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(argsSchema)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            if (runtime == null) {
                log.warn("Knowledge tool {} called without runtime binding", name);
                return "知识库工具缺少运行时绑定（构图方必须先 boundTo）";
            }
            Map<String, Object> args = parseArgs(toolInput);
            Object result = body.run(runtime, args);
            return result instanceof String text ? text : JSON.toJSONString(result);
        }
    }

    /** 工具执行体（对应参考实现各工具的 {@code coroutine}；{@code runtime} 为显式入参）。 */
    @FunctionalInterface
    interface KnowledgeToolBody {
        Object run(ToolRuntime runtime, Map<String, Object> args);
    }

    // ==================== 小工具 ====================

    static Map<String, Object> parseArgs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(toolInput);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException("工具入参不是合法 JSON: " + exc.getMessage());
        }
    }

    static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static Integer intArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Integer value = intArg(args, key);
        return value == null ? defaultValue : value;
    }

    @SuppressWarnings("unchecked")
    static List<String> stringListArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return List.of();
    }

    static String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    static Map<String, Object> intProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    /** 组装 JSON Schema（{@code properties} + {@code required}）。 */
    static String schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", required);
        return JSON.toJSONString(root);
    }
}
