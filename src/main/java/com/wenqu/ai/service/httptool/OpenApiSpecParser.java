package com.wenqu.ai.service.httptool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI 3 规格的子集解析器（手写，不引入 swagger-parser 依赖——仓库取向是能不加依赖就不加）。
 * <p>
 * 只提取「导出为对话工具」需要的最小面：paths×methods → operationId/summary/参数/requestBody，
 * {@code $ref} 就地递归解析（深度上限 6，环截断为 {@code {"type":"object"}}）。参数与请求体合成
 * 一份扁平 JSON Schema 作为工具入参表——body 一律收进单个 {@code body} 对象属性，
 * 不追求 schema 的完美翻译，够模型把参数填对即可；超复杂的 schema（序列化超 8KB）整体降级为
 * {@code {"type":"object"}} 并记警告，导入流程不因规格花哨而失败。
 * <p>YAML 用 snakeyaml 的 {@link SafeConstructor} 解析（2.x 默认即安全装载，这里显式声明防退化）。
 *
 * @author yuanke
 */
public final class OpenApiSpecParser {

    private static final int MAX_PATHS = 200;
    private static final int MAX_OPS = 100;
    private static final int REF_MAX_DEPTH = 6;
    private static final int SCHEMA_MAX_CHARS = 8192;
    private static final Set<String> HTTP_METHODS = HttpToolModels.METHODS;

    /** 解析产物：单操作（保存期再合成最终工具名） */
    public record ParsedOp(String operationId, String method, String path, String summary,
                           List<HttpToolModels.HttpParam> params, boolean hasBody,
                           Map<String, Object> inputSchema) {
    }

    public record ParseResult(String suggestedName, String suggestedBaseUrl,
                              List<ParsedOp> ops, List<String> warnings) {
    }

    private OpenApiSpecParser() {
    }

    /** 解析（fail-loud：规格问题当场报，别让用户到运行期才发现导出了个空工具集） */
    @SuppressWarnings("unchecked")
    public static ParseResult parse(String specText) {
        if (specText == null || specText.isBlank()) {
            throw new BizException("请粘贴 OpenAPI 规格内容");
        }
        if (specText.length() > HttpToolModels.MAX_SPEC_CHARS) {
            throw new BizException("规格过大（上限 " + (HttpToolModels.MAX_SPEC_CHARS / 1024) + "KB），"
                    + "请拆分工具集或只保留需要的接口");
        }
        String trimmed = specText.trim();
        Object loaded;
        try {
            if (trimmed.startsWith("{")) {
                loaded = JSON.parse(trimmed);
            } else {
                loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(trimmed);
            }
        } catch (Exception e) {
            throw new BizException("规格解析失败：" + e.getMessage());
        }
        if (!(loaded instanceof Map)) {
            throw new BizException("规格内容不是合法的 OpenAPI 文档（顶层须为对象）");
        }
        Map<String, Object> root = (Map<String, Object>) loaded;
        Object version = root.get("openapi");
        if (version == null || !String.valueOf(version).startsWith("3")) {
            throw new BizException("仅支持 OpenAPI 3.x 规格（检测到 "
                    + (version == null ? root.containsKey("swagger") ? "swagger " + root.get("swagger") : "未知版本"
                    : String.valueOf(version)) + "）；"
                    + "swagger 2.0 文档请先用工具转换为 OpenAPI 3");
        }
        List<String> warnings = new ArrayList<>();

        String suggestedName = HttpToolModels.sanitizeNamePart(titleOf(root));
        String suggestedBaseUrl = firstServerUrl(root);

        Map<String, Object> paths = asMap(root.get("paths"));
        if (paths.isEmpty()) {
            throw new BizException("规格里没有 paths（无接口可导出）");
        }
        if (paths.size() > MAX_PATHS) {
            warnings.add("接口路径超过 " + MAX_PATHS + " 条，仅解析前 " + MAX_PATHS + " 条");
        }
        List<ParsedOp> ops = new ArrayList<>();
        Set<String> usedOpIds = new LinkedHashSet<>();
        int pathCount = 0;
        for (Map.Entry<String, Object> p : paths.entrySet()) {
            if (++pathCount > MAX_PATHS || ops.size() >= MAX_OPS) break;
            String path = p.getKey();
            Map<String, Object> pathItem = asMap(p.getValue());
            // 路径级参数（对该路径全部操作生效）与操作级参数合并，操作级优先
            List<Map<String, Object>> pathLevelParams = paramsOf(pathItem.get("parameters"), root);
            for (Map.Entry<String, Object> m : pathItem.entrySet()) {
                String method = m.getKey().toUpperCase();
                if (!HTTP_METHODS.contains(method)) continue;
                if (ops.size() >= MAX_OPS) {
                    warnings.add("操作数达到 " + MAX_OPS + " 上限，其余未解析");
                    break;
                }
                Map<String, Object> op = asMap(m.getValue());
                if (op.isEmpty()) continue;
                ops.add(buildOp(op, method, path, pathLevelParams, root, usedOpIds, warnings));
            }
        }
        if (ops.isEmpty()) {
            throw new BizException("规格里没有可导出的 HTTP 操作（仅支持 GET/POST/PUT/PATCH/DELETE/HEAD）");
        }
        return new ParseResult(suggestedName, suggestedBaseUrl, ops, warnings);
    }

    private static ParsedOp buildOp(Map<String, Object> op, String method, String path,
                                    List<Map<String, Object>> pathLevelParams, Map<String, Object> root,
                                    Set<String> usedOpIds, List<String> warnings) {
        String operationId = op.get("operationId") == null ? "" : String.valueOf(op.get("operationId")).trim();
        if (operationId.isEmpty()) {
            operationId = HttpToolModels.sanitizeNamePart(
                    method.toLowerCase() + path.replaceAll("/\\{[^}]*}", "")).replace('-', '_');
        } else {
            operationId = HttpToolModels.sanitizeNamePart(operationId);
        }
        String base = operationId.isEmpty() ? "op" : operationId;
        String unique = base;
        int n = 2;
        while (!usedOpIds.add(unique)) unique = base + "_" + n++;

        String summary = op.get("summary") == null ? "" : String.valueOf(op.get("summary")).trim();
        if (summary.isEmpty() && op.get("description") != null) {
            String d = String.valueOf(op.get("description")).trim();
            summary = d.length() > 120 ? d.substring(0, 120) : d;
        }

        // 参数：路径级 + 操作级（同名操作级优先）；body 相关在这里不出现（requestBody 单独走）
        Map<String, Map<String, Object>> paramByName = new LinkedHashMap<>();
        List<Map<String, Object>> allParams = new ArrayList<>(pathLevelParams);
        allParams.addAll(paramsOf(op.get("parameters"), root));
        boolean pathHasBody = false;
        for (Map<String, Object> pm : allParams) {
            String name = pm.get("name") == null ? "" : String.valueOf(pm.get("name"));
            String in = pm.get("in") == null ? "" : String.valueOf(pm.get("in"));
            if (name.isEmpty() || in.equals("cookie") || in.equals("body")) continue;
            paramByName.put(name, pm);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : paramByName.entrySet()) {
            Map<String, Object> pm = e.getValue();
            Map<String, Object> schema = asMap(resolve(pm.get("schema"), root, 0, new ArrayDeque<>()));
            Map<String, Object> prop = new LinkedHashMap<>();
            Object type = schema.get("type");
            prop.put("type", type == null ? "string" : type);
            if (schema.get("format") != null) prop.put("format", schema.get("format"));
            if (schema.get("description") != null) prop.put("description", schema.get("description"));
            properties.put(e.getKey(), prop);
            if (Boolean.TRUE.equals(pm.get("required")) || "path".equals(pm.get("in"))) required.add(e.getKey());
        }

        // requestBody：application/json 的 schema 解析后收进单个 body 属性
        boolean hasBody = false;
        Map<String, Object> rb = asMap(resolve(op.get("requestBody"), root, 0, new ArrayDeque<>()));
        Map<String, Object> content = asMap(rb.get("content"));
        Map<String, Object> bodySchema = asMap(content.get("application/json")).isEmpty()
                ? asMap(content.get("*/*")) : asMap(content.get("application/json"));
        if (!bodySchema.isEmpty()) {
            Map<String, Object> resolved = asMap(resolve(bodySchema.get("schema"), root, 0, new ArrayDeque<>()));
            String flat = JSON.toJSONString(resolved);
            if (flat.length() > SCHEMA_MAX_CHARS) {
                warnings.add("「" + method + " " + path + "」请求体 schema 过复杂，已降级为自由对象");
                resolved = new LinkedHashMap<>(Map.of("type", "object"));
            }
            properties.put("body", resolved);
            if (Boolean.TRUE.equals(rb.get("required"))) required.add("body");
            hasBody = true;
        }

        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) inputSchema.put("required", required);
        inputSchema.put("additionalProperties", false);

        List<HttpToolModels.HttpParam> params = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : paramByName.entrySet()) {
            HttpToolModels.HttpParam hp = new HttpToolModels.HttpParam();
            hp.setName(e.getKey());
            hp.setIn(String.valueOf(e.getValue().get("in")));
            hp.setRequired(Boolean.TRUE.equals(e.getValue().get("required")) || "path".equals(e.getValue().get("in")));
            params.add(hp);
        }
        return new ParsedOp(unique, method, path, summary, params, hasBody, inputSchema);
    }

    private static List<Map<String, Object>> paramsOf(Object raw, Map<String, Object> root) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            Map<String, Object> m = asMap(resolve(o, root, 0, new ArrayDeque<>()));
            if (!m.isEmpty()) out.add(m);
        }
        return out;
    }

    /**
     * {@code $ref} 解析（文档内相对路径 {@code #/components/schemas/X}）。
     * 深度超限/环/断链都回落 {@code {"type":"object"}}——导入宁可少一点精度，不因一条坏引用整单失败。
     */
    @SuppressWarnings("unchecked")
    static Object resolve(Object node, Map<String, Object> root, int depth, Deque<String> chain) {
        if (depth > REF_MAX_DEPTH) return Map.of("type", "object");
        if (!(node instanceof Map<?, ?> m)) return node;
        Object ref = m.get("$ref");
        if (ref instanceof String s && s.startsWith("#/")) {
            if (chain.contains(s)) return Map.of("type", "object");
            Object cur = root;
            for (String part : s.substring(2).split("/")) {
                if (!(cur instanceof Map<?, ?> cm)) return Map.of("type", "object");
                cur = cm.get(part.replace("~1", "/").replace("~0", "~"));
                if (cur == null) return Map.of("type", "object");
            }
            chain.push(s);
            try {
                return resolve(cur, root, depth + 1, chain);
            } finally {
                chain.pop();
            }
        }
        // 非 $ref 的映射：递归解内层 properties/items，让嵌套 $ref 也展开
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) m).entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                out.put(String.valueOf(e.getKey()), resolveContainer(v, root, depth, chain));
            } else {
                out.put(String.valueOf(e.getKey()), v);
            }
        }
        return out;
    }

    private static Object resolveContainer(Object v, Map<String, Object> root, int depth, Deque<String> chain) {
        if (depth > REF_MAX_DEPTH) return Map.of("type", "object");
        if (v instanceof Map<?, ?>) return resolve(v, root, depth + 1, chain);
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(o instanceof Map<?, ?> ? resolve(o, root, depth + 1, chain) : o);
            return out;
        }
        return v;
    }

    private static String titleOf(Map<String, Object> root) {
        Map<String, Object> info = asMap(root.get("info"));
        Object t = info.get("title");
        return t == null ? "" : String.valueOf(t);
    }

    private static String firstServerUrl(Map<String, Object> root) {
        if (root.get("servers") instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> s && s.get("url") != null) {
            return String.valueOf(s.get("url")).trim();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }
}
