package com.wenqu.ai.service.websearch;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.service.ConfigService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义 HTTP 搜索 API 适配（万能模板口径）。
 * <p>
 * 各模型厂商自带的联网搜索 API 格式互不相同（智谱 {@code search_query→search_result[]}、
 * 百炼 {@code input.messages+enable_search→output.search_info.search_results[]}、
 * OpenAI Responses {@code tools:[web_search]}，鉴权与字段全不一样），硬编码预设追不完——
 * 所以本实现不预设厂商：**请求体是模板（占位符替换）、响应是 JSON 点路径取值**，
 * 任何返回 JSON 的搜索端点都能接。
 * <p>
 * 接口口径（全部可配置）：
 * <pre>
 * POST：{baseUrl} + 请求体模板（占位符 {{query}}、{{count}}、{{model}}）
 * GET ：{baseUrl} 即 URL 模板（如 http://127.0.0.1:8888/search?q={{query}}&amp;limit={{count}}，
 *       {{query}} 自动 URL 编码）
 * 响应：customResultsPath 点路径取结果数组，条目内按 title/url/snippet/published 点路径取字段
 * </pre>
 * 与内置三家的差别：
 * <ul>
 *   <li>配置面最大：7 个 custom* 键（方法 / 请求体模板 / 结果数组路径 / 三字段路径 / 发布时间路径）；</li>
 *   <li>鉴权宽松：apiKey 非空即按 Bearer 带，缺 key 不拦（厂商 401 原样透出，与 generic 同款策略）；</li>
 *   <li>fail-loud：POST 缺请求体模板、GET 模板缺 {{query}} 占位符、响应解析不出结果数组，都报可读错误。</li>
 * </ul>
 * 典型模板见 config-schema 的 tips（智谱 / 百炼 / OpenAI Responses 三家示例，照抄改 key 即用）。
 *
 * @author yuanke
 */
@Component
public class CustomSearchProvider implements WebSearchProvider {

    private final ConfigService configService;

    public CustomSearchProvider(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String id() {
        return "custom";
    }

    @Override
    public List<WebSearchResult> search(WebSearchRequest req) throws Exception {
        boolean post = !"GET".equalsIgnoreCase(cfg("webSearch.customMethod"));
        String base = req.baseUrl() == null ? "" : req.baseUrl().trim();
        if (base.isEmpty()) {
            throw new WebSearchException("自定义搜索服务未配置服务地址：请在 个人设置 → 联网搜索 填写服务端点地址", true);
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        String model = cfg("webSearch.model");
        Map<String, String> headers = new HashMap<>(1);
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            headers.put("Authorization", "Bearer " + req.apiKey().trim());
        }

        String resp;
        if (post) {
            String tmpl = cfg("webSearch.customBody");
            if (tmpl == null || tmpl.isBlank()) {
                throw new WebSearchException("自定义搜索服务未配置请求体模板：请在 个人设置 → 联网搜索 填写请求体模板"
                        + "（如 {\"search_query\":\"{{query}}\",\"count\":{{count}}}，示例见该字段的提示）", true);
            }
            String url = render(base, req, model, Escape.NONE);
            String body = render(tmpl, req, model, Escape.JSON);
            if (!JSON.isValid(body)) {
                throw new WebSearchException("自定义搜索请求体模板渲染后不是合法 JSON：请检查 {{query}}/{{model}} "
                        + "是否落在字符串引号内、模板是否有多余逗号（渲染结果开头：" + head(body) + "）", true);
            }
            resp = SearchHttp.post(url, headers, body, req.timeoutMs());
        } else {
            if (!base.contains("{{query}}")) {
                throw new WebSearchException("自定义搜索服务 GET 模式的服务地址须含 {{query}} 占位符"
                        + "（如 http://127.0.0.1:8888/search?q={{query}}&format=json&limit={{count}}）", true);
            }
            resp = SearchHttp.get(render(base, req, model, Escape.URL), headers, req.timeoutMs());
        }

        String resultsPath = cfg("webSearch.customResultsPath", ConfigDefaults.WEB_SEARCH_CUSTOM_RESULTS_PATH);
        JSONObject obj = JSON.parseObject(resp);
        JSONArray arr = obj == null ? null : pickArray(obj, resultsPath);
        if (arr == null) {
            throw new WebSearchException("自定义搜索服务响应缺少结果数组（webSearch.customResultsPath="
                    + resultsPath + " 取不到，响应开头：" + head(resp) + "）");
        }
        List<WebSearchResult> out = new ArrayList<>();
        for (int i = 0; i < arr.size() && out.size() < req.maxResults(); i++) {
            JSONObject it = arr.getJSONObject(i);
            if (it == null) continue;
            String u = pickString(it, cfg("webSearch.customUrlPath", ConfigDefaults.WEB_SEARCH_CUSTOM_URL_PATH));
            if (u == null || u.isBlank()) continue;
            out.add(new WebSearchResult(
                    pickString(it, cfg("webSearch.customTitlePath", ConfigDefaults.WEB_SEARCH_CUSTOM_TITLE_PATH)),
                    u.trim(),
                    pickString(it, cfg("webSearch.customSnippetPath", ConfigDefaults.WEB_SEARCH_CUSTOM_SNIPPET_PATH)),
                    null,
                    pickString(it, cfg("webSearch.customPublishedPath")),
                    null,
                    null));
        }
        return out;
    }

    /** query 占位符的转义口径：JSON 请求体转义引号反斜杠、GET URL 做 URL 编码、URL 模板外原样 */
    private enum Escape { NONE, JSON, URL }

    /** 模板渲染：{{query}} / {{count}} / {{model}} 占位符替换，query 与 model 都按 {@link Escape} 转义 */
    private static String render(String tmpl, WebSearchRequest req, String model, Escape esc) {
        String q = req.query() == null ? "" : req.query();
        String m = model == null ? "" : model.trim();
        q = escape(q, esc);
        m = escape(m, esc);
        return tmpl.replace("{{query}}", q)
                .replace("{{count}}", String.valueOf(req.maxResults()))
                .replace("{{model}}", m);
    }

    /** JSON 转义要连带控制字符（query 里的换行/制表符不转义会直接破坏请求体）；URL 场景整体编码 */
    private static String escape(String s, Escape esc) {
        return switch (esc) {
            case JSON -> s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
            case URL -> java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
            case NONE -> s;
        };
    }

    /** 点路径取值：逐段下钻 JSONObject，取不到返回 null（路径段不允许跨数组，结果数组本身由 pickArray 负责） */
    private static Object pick(JSONObject obj, String path) {
        if (path == null || path.isBlank()) return null;
        Object cur = obj;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof JSONObject o)) return null;
            cur = o.get(seg.trim());
            if (cur == null) return null;
        }
        return cur;
    }

    private static JSONArray pickArray(JSONObject obj, String path) {
        return pick(obj, path) instanceof JSONArray a ? a : null;
    }

    private static String pickString(JSONObject obj, String path) {
        Object v = pick(obj, path);
        return v == null ? null : String.valueOf(v);
    }

    private String cfg(String key) {
        return cfg(key, "");
    }

    /**
     * personalOnly 键没有全局层：本人没填时 {@code ConfigService.get} 返回空串，
     * schema 的 def 只用于个人设置页占位展示。运行时的内置默认值必须由代码常量承担，
     * 否则界面写着「默认（results）」而实际取不到任何结果。
     */
    private String cfg(String key, String fallback) {
        String v = configService.get(key);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    /** 报错时带响应开头，便于对着厂商文档排查（截 120 字符） */
    private static String head(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
