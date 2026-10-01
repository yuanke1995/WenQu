package com.wisesoft.ai.service.websearch;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 自建 / 通用搜索服务适配（ SearXNG 元搜索口径）。
 * <p>
 * 接口口径：
 * <pre>
 * GET {baseUrl}/search?q=&lt;urlencoded&gt;&amp;format=json&amp;limit=N
 * Resp: {"query":"","number_of_results":0,"results":[{"title":"","url":"","content":"","engine":"google","score":1.0,"published_date":""}]}
 * </pre>
 * 与另两家的差别：
 * <ul>
 *   <li>**baseUrl 必填**（自建实例没有公共默认地址）；</li>
 *   <li>通常**无鉴权**，apiKey 留空即可（非空时仍按 Bearer 带上，兼容前面挂了网关的场景）；</li>
 *   <li>摘要字段是 {@code content} 而不是 snippet。</li>
 * </ul>
 * 注意：SearXNG 的 JSON 格式需要在实例 settings.yml 里开启（formats 含 json），否则返回 403，
 * 本实现把 403 原样透出为 HTTP 403，不做重试。
 *
 * @author yuanke
 */
@Component
public class GenericSearchProvider implements WebSearchProvider {

    @Override
    public String id() {
        return "generic";
    }

    @Override
    public List<WebSearchResult> search(WebSearchRequest req) throws Exception {
        String base = req.baseUrl() == null ? "" : req.baseUrl().trim();
        if (base.isEmpty()) {
            throw new WebSearchException("通用搜索服务未配置服务地址（webSearch.baseUrl 必填，如 http://127.0.0.1:8888）", true);
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/search?q=" + URLEncoder.encode(req.query(), StandardCharsets.UTF_8)
                + "&format=json&limit=" + req.maxResults();

        java.util.Map<String, String> headers = new java.util.HashMap<>(1);
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            headers.put("Authorization", "Bearer " + req.apiKey().trim());
        }

        String resp = SearchHttp.get(url, headers, req.timeoutMs());
        JSONObject obj = JSON.parseObject(resp);
        JSONArray arr = obj == null ? null : obj.getJSONArray("results");
        List<WebSearchResult> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject it = arr.getJSONObject(i);
            if (it == null) continue;
            String u = it.getString("url");
            if (u == null || u.isBlank()) continue;
            Double score = null;
            Object sv = it.get("score");
            if (sv instanceof Number n) score = n.doubleValue();
            out.add(new WebSearchResult(
                    it.getString("title"),
                    u.trim(),
                    it.getString("content"),
                    null,
                    it.getString("published_date"),
                    score));
        }
        return out;
    }
}
