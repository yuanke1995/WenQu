package com.wenqu.ai.service.websearch;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 博查 AI 搜索（web-search 普通搜索）适配。
 * <p>
 * 接口口径（以官方文档为准）：
 * <pre>
 * POST https://api.bochaai.com/v1/web-search
 * Header: Authorization: Bearer &lt;api_key&gt; / Content-Type: application/json
 * Body:   {"query":"...","count":8,"summary":true,"freshness":"noLimit"}
 * Resp:   {"data":{"webPages":{"value":[{"name":"","url":"","snippet":"","summary":"","siteName":"","datePublished":""}]}}}
 * </pre>
 * 摘要优先取 {@code snippet}，为空回退 {@code summary}（后者是原文长摘要，退而求其次）。
 * 时间范围固定 noLimit：官方明确说明指定范围容易出现"该时间窗内无结果"，时效筛选交给模型在
 * 提问里表达更合适。
 *
 * @author yuanke
 */
@Component
public class BochaSearchProvider implements WebSearchProvider {

    private static final String DEFAULT_URL = "https://api.bochaai.com/v1/web-search";

    @Override
    public String id() {
        return "bocha";
    }

    @Override
    public List<WebSearchResult> search(WebSearchRequest req) throws Exception {
        String url = (req.baseUrl() == null || req.baseUrl().isBlank()) ? DEFAULT_URL : req.baseUrl().trim();
        JSONObject body = new JSONObject();
        body.put("query", req.query());
        body.put("count", req.maxResults());
        body.put("summary", true);
        body.put("freshness", "noLimit");

        Map<String, String> headers = new HashMap<>(2);
        headers.put("Authorization", "Bearer " + (req.apiKey() == null ? "" : req.apiKey().trim()));

        String resp = SearchHttp.post(url, headers, body.toJSONString(), req.timeoutMs());
        JSONObject obj = JSON.parseObject(resp);
        JSONArray arr = obj == null ? null
                : (obj.getJSONObject("data") == null ? null
                : (obj.getJSONObject("data").getJSONObject("webPages") == null ? null
                : obj.getJSONObject("data").getJSONObject("webPages").getJSONArray("value")));
        List<WebSearchResult> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject it = arr.getJSONObject(i);
            if (it == null) continue;
            String u = it.getString("url");
            if (u == null || u.isBlank()) continue;
            String snippet = it.getString("snippet");
            if (snippet == null || snippet.isBlank()) snippet = it.getString("summary");
            out.add(new WebSearchResult(
                    it.getString("name"),
                    u.trim(),
                    snippet,
                    it.getString("siteName"),
                    it.getString("datePublished"),
                    null));
        }
        return out;
    }
}
