package com.wenqu.ai.service.httptool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.common.BizException;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP 工具集的归一化模型、上限与凭据口径（智能体自有配置，存 {@code c_ai_agent.http_tools} JSON 列）。
 * <p>
 * 形状（与 {@code c_ai_user_mcp.headers} 同一套凭据纪律——整块 JWE 密文入库、回显只给状态不给值）：
 * <pre>[[{id,name,baseUrl,timeoutMs,enabled,auth:{type,header,value,hasValue},
 *   ops:[{name,method,path,summary,params:[{name,in,required}],hasBody,inputSchema}]}]</pre>
 * 上限刻意收紧：每智能体 ≤4 个工具集、每集 ≤20 操作、合计 ≤30——工具越多提示词越大，
 * 工具 schema 在 prompt 里是与系统提示词并列的固定开销（toolSchema 占 5.25k token）。
 *
 * @author yuanke
 */
public final class HttpToolModels {

    public static final int MAX_TOOLSETS = 4;
    public static final int MAX_OPS_PER_SET = 20;
    public static final int MAX_TOTAL_OPS = 30;
    public static final int MAX_SPEC_CHARS = 512 * 1024;
    /** HTTP 响应读取上限（字节）：宿主接口返回大 JSON 时先在这里截住，避免把模型线程的堆吃满 */
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    /** 进模型上下文的结果截断：超过的部分对作答几乎无增益，token 成本却是线性的 */
    public static final int MODEL_RESULT_CHARS = 16 * 1024;
    public static final int TIMEOUT_MIN_MS = 1000;
    public static final int TIMEOUT_MAX_MS = 60000;
    public static final int TIMEOUT_DEFAULT_MS = 60000;

    /** 允许导出为对话工具的方法（CONNECT/TRACE 这类对「智能体调宿主 API」没有意义的直接不收） */
    public static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

    private HttpToolModels() {
    }

    /** 工具集鉴权（value 落库态为 JWE 密文；{@code hasValue} 供回显与「值不变不重加密」判定） */
    @Data
    public static class HttpAuth {
        private String type;      // none / bearer / apiKeyHeader
        private String header;    // apiKeyHeader 时的头名（默认 X-Api-Key）
        private String value;     // 密文（JWE: 前缀）
        private boolean hasValue;

        public static HttpAuth none() {
            HttpAuth a = new HttpAuth();
            a.type = "none";
            return a;
        }
    }

    /** 单个操作的路由参数（执行时按 in 分流到 path/query/header，不靠模型猜） */
    @Data
    public static class HttpParam {
        private String name;
        private String in;        // path / query / header
        private boolean required;
    }

    /** 归一化后的操作：{@code name} 即对话工具名（保存期算定，运行期不再重算） */
    @Data
    public static class HttpOp {
        private String name;
        private String method;
        private String path;
        private String summary;
        private List<HttpParam> params;
        private boolean hasBody;
        private Map<String, Object> inputSchema;
    }

    @Data
    public static class HttpToolset {
        private String id;
        private String name;
        private String baseUrl;
        private Integer timeoutMs;
        private Boolean enabled;
        private HttpAuth auth;
        private List<HttpOp> ops;
    }

    /** 工具集名/操作名共用的清洗：对话工具名只允许 [a-zA-Z0-9_-]（与 OpenAI 侧约定同口径） */
    public static String sanitizeNamePart(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String s = sb.toString();
        while (s.startsWith("_")) s = s.substring(1);
        while (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 对话工具名：{工具集名}_{操作名}，超长截断；空操作名回落 opN；拒绝 wen_qu 前缀（MCP 组合名空间） */
    public static String toolName(String setName, String operationId, int fallbackIndex) {
        String s = sanitizeNamePart(setName);
        String o = sanitizeNamePart(operationId);
        if (o.isEmpty()) o = "op" + Math.max(1, fallbackIndex);
        if (s.isEmpty()) s = "api";
        String name = (s + "_" + o);
        if (name.length() > 64) name = name.substring(0, 64);
        if (name.startsWith("wen_qu")) name = "api_" + name;
        return name;
    }

    /**
     * 保存期归一化：请求体的工具集数组 → 校验 + 补默认 + 操作名算定 + 凭据加密的落库 JSON。
     * <p>凭据口径：{@code auth.value} 非空视为新明文（校验后加密）；空/缺失 = 沿用既有值
     * （按工具集 id 匹配）——编辑表单拿到的是脱敏回显，值列本来就是空的，原样保存不能把密文洗掉。
     * 空/NULL 入参 = 显式清空配置（返回 null）。校验 fail-loud：坏配置保存成功只会变成运行期静默不挂。
     */
    public static String normalizeFromSave(Object raw, String existingJson, ConfigCrypto cf) {
        if (raw == null) return null;
        // 兼容两种入口形态：网页保存传数组；市场模板安装走快照 JSON（httpTools 是嵌套字符串）
        if (raw instanceof String s) {
            if (s.isBlank()) return null;
            try {
                raw = JSON.parse(s);
            } catch (Exception e) {
                throw new BizException("httpTools 不是合法 JSON");
            }
        }
        if (!(raw instanceof List<?> list)) {
            throw new BizException("httpTools 须为工具集数组");
        }
        if (list.isEmpty()) return null;
        if (list.size() > MAX_TOOLSETS) {
            throw new BizException("HTTP 工具集最多 " + MAX_TOOLSETS + " 个");
        }
        // 既有密文按 id 索引：编辑时值列留空=沿用旧密文
        Map<String, String> existingAuth = new LinkedHashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                List<HttpToolset> prev = JSON.parseArray(existingJson, HttpToolset.class);
                for (HttpToolset t : prev) {
                    if (t != null && t.getId() != null && t.getAuth() != null
                            && t.getAuth().getValue() != null && !t.getAuth().getValue().isBlank()) {
                        existingAuth.put(t.getId(), t.getAuth().getValue());
                    }
                }
            } catch (Exception ignored) {
                // 既有列损坏时按无既有值处理：本次保存的明文会重新加密
            }
        }
        JSONArray out = new JSONArray();
        Set<String> setNames = new HashSet<>();
        Set<String> toolNames = new HashSet<>();
        int totalOps = 0;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (!(o instanceof Map<?, ?> m)) {
                throw new BizException("第 " + (i + 1) + " 个 HTTP 工具集格式不正确");
            }
            JSONObject one = normalizeOne(m, existingAuth, setNames, toolNames, cf);
            out.add(one);
            totalOps += one.getJSONArray("ops").size();
        }
        if (totalOps > MAX_TOTAL_OPS) {
            throw new BizException("HTTP 工具操作合计最多 " + MAX_TOTAL_OPS + " 个（当前 " + totalOps + "）");
        }
        return out.toJSONString();
    }

    @SuppressWarnings("unchecked")
    private static JSONObject normalizeOne(Map<?, ?> m, Map<String, String> existingAuth,
                                           Set<String> setNames, Set<String> toolNames, ConfigCrypto cf) {
        JSONObject t = new JSONObject((Map<String, Object>) m);
        String name = sanitizeNamePart(t.getString("name"));
        if (name.isEmpty()) throw new BizException("HTTP 工具集名称不能为空（字母/数字/_/-）");
        if (name.length() > 40) name = name.substring(0, 40);
        if (!setNames.add(name)) throw new BizException("HTTP 工具集名称重复：" + name);
        String baseUrl = t.getString("baseUrl") == null ? "" : t.getString("baseUrl").trim();
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new BizException("工具集「" + name + "」的接口地址须以 http(s):// 开头");
        }
        if (baseUrl.length() > 512) throw new BizException("工具集「" + name + "」的接口地址过长");
        String id = t.getString("id");
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString().replace("-", "");

        JSONObject auth = t.getJSONObject("auth");
        JSONObject authOut = new JSONObject();
        String type = auth == null ? "none" : auth.getString("type");
        if (type == null || (!type.equals("none") && !type.equals("bearer") && !type.equals("apiKeyHeader"))) {
            throw new BizException("工具集「" + name + "」的鉴权类型无法识别（none / bearer / apiKeyHeader）");
        }
        authOut.put("type", type);
        String header = auth == null ? null : auth.getString("header");
        authOut.put("header", header == null || header.isBlank() ? "X-Api-Key" : header.trim());
        String value = auth == null ? null : auth.getString("value");
        if (value == null || value.isBlank()) {
            String prev = existingAuth.get(id);
            authOut.put("value", prev);
            authOut.put("hasValue", prev != null && !prev.isBlank());
        } else {
            if (value.length() > 4096) throw new BizException("工具集「" + name + "」的鉴权凭据过长");
            authOut.put("value", cf.encrypt(value));
            authOut.put("hasValue", true);
        }

        Object rawOps = t.get("ops");
        if (!(rawOps instanceof List<?> ops) || ops.isEmpty()) {
            throw new BizException("工具集「" + name + "」至少要勾选一个操作");
        }
        if (ops.size() > MAX_OPS_PER_SET) {
            throw new BizException("工具集「" + name + "」最多导出 " + MAX_OPS_PER_SET + " 个操作");
        }
        JSONArray opsOut = new JSONArray();
        for (int j = 0; j < ops.size(); j++) {
            Object ro = ops.get(j);
            if (!(ro instanceof Map<?, ?> om)) {
                throw new BizException("工具集「" + name + "」第 " + (j + 1) + " 个操作格式不正确");
            }
            JSONObject op = normalizeOp(name, (Map<?, ?>) om, j, toolNames);
            opsOut.add(op);
        }

        JSONObject outSet = new JSONObject(new LinkedHashMap<>());
        outSet.put("id", id);
        outSet.put("name", name);
        outSet.put("baseUrl", baseUrl);
        Integer timeout = toInt(t.get("timeoutMs"));
        if (timeout == null || timeout < TIMEOUT_MIN_MS || timeout > TIMEOUT_MAX_MS) timeout = TIMEOUT_DEFAULT_MS;
        outSet.put("timeoutMs", timeout);
        Object enabled = t.get("enabled");
        outSet.put("enabled", !(enabled instanceof Boolean b) || b);
        outSet.put("auth", authOut);
        outSet.put("ops", opsOut);
        return outSet;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject normalizeOp(String setName, Map<?, ?> om, int index, Set<String> toolNames) {
        JSONObject op = new JSONObject((Map<String, Object>) om);
        String method = op.getString("method") == null ? "" : op.getString("method").trim().toUpperCase();
        if (!METHODS.contains(method)) {
            throw new BizException("操作方法不支持：" + method + "（允许 GET/POST/PUT/PATCH/DELETE/HEAD）");
        }
        String path = op.getString("path") == null ? "" : op.getString("path").trim();
        if (!path.startsWith("/")) throw new BizException("操作路径须以 / 开头：" + path);
        if (path.length() > 512) throw new BizException("操作路径过长");
        String tool = toolName(setName,
                op.getString("operationId") == null ? path : op.getString("operationId"), index + 1);
        if (!toolNames.add(tool)) {
            throw new BizException("操作名冲突：" + tool + "（请调整工具集名或 operationId）");
        }
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("name", tool);
        out.put("method", method);
        out.put("path", path);
        out.put("summary", op.getString("summary") == null ? "" : op.getString("summary"));
        // params（path/query/header 路由）与 hasBody 在预览/保存同源算定，这里只做形状校验
        Object rawParams = op.get("params");
        List<Map<String, Object>> params = new ArrayList<>();
        if (rawParams instanceof List<?> pl) {
            for (Object p : pl) {
                if (!(p instanceof Map<?, ?> pm)) continue;
                String in = String.valueOf(pm.get("in"));
                if (!in.equals("path") && !in.equals("query") && !in.equals("header")) continue;
                String pn = pm.get("name") == null ? "" : String.valueOf(pm.get("name"));
                if (pn.isBlank() || "body".equals(pn)) continue;
                params.add(Map.of("name", pn, "in", in,
                        "required", Boolean.TRUE.equals(pm.get("required"))));
            }
        }
        out.put("params", params);
        boolean hasBody = Boolean.TRUE.equals(op.get("hasBody"))
                || (op.get("inputSchema") instanceof Map<?, ?> is
                && is.get("properties") instanceof Map<?, ?> props && props.containsKey("body"));
        out.put("hasBody", hasBody);
        Object schema = op.get("inputSchema");
        out.put("inputSchema", schema instanceof Map<?, ?> sm ? sm : Map.of("type", "object", "properties", Map.of()));
        return out;
    }

    /**
     * 运行期宽松解析（坏条目跳过不炸轮）：DB 里的 JSON → 工具集列表。
     * 保存期已经校验过，这里的容错只兜「外部改库/旧版本格式」。
     */
    public static List<HttpToolset> parseStored(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<HttpToolset> out = new ArrayList<>();
            for (HttpToolset t : JSON.parseArray(json, HttpToolset.class)) {
                if (t != null && t.getName() != null && t.getBaseUrl() != null && t.getOps() != null) {
                    out.add(t);
                }
            }
            return out;
        } catch (Exception e) {
            return null;   // 解析失败由调用方决定降级口径（fail-loud 到轮内可见降级）
        }
    }

    /** 回显脱敏：value 清空、只留 hasValue；解析失败返回 null（宁可不回显，不回显密文） */
    public static String mask(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            JSONArray arr = JSON.parseArray(json);
            for (int i = 0; i < arr.size(); i++) {
                JSONObject t = arr.getJSONObject(i);
                JSONObject auth = t == null ? null : t.getJSONObject("auth");
                if (auth == null) continue;
                String v = auth.getString("value");
                auth.put("hasValue", v != null && !v.isBlank());
                auth.put("value", "");
            }
            return arr.toJSONString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 既有配置里的全部对话工具名（toolDeny 校验的白名单扩展：动态工具不在注册中心） */
    public static Set<String> opNames(String json) {
        List<HttpToolset> sets = parseStored(json);
        Set<String> out = new HashSet<>();
        if (sets == null) return out;
        for (HttpToolset t : sets) {
            for (HttpOp op : t.getOps()) {
                if (op.getName() != null) out.add(op.getName());
            }
        }
        return out;
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? null : Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 加密口（避免本类直依赖 Spring bean，保存链路传入） */
    public interface ConfigCrypto {
        String encrypt(String plain);
    }
}
