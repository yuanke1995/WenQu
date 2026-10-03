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
 * Tavily Search API 适配。
 * <p>
 * 接口口径（以官方文档为准）：
 * <pre>
 * POST https://api.tavily.com/search
 * Header: Authorization: Bearer &lt;api_key&gt; / Content-Type: application/json
 * Body:   {"query":"...","max_results":5,"search_depth":"basic","include_answer":false}
 * Resp:   {"query":"...","answer":"...","results":[{"title":"","url":"","content":"","score":0.85}],"response_time":1.2}
 * </pre>
 * 说明：Tavily 早期版本把 api_key 放在 body，现行为是 Bearer 头，本实现按后者。
 * results 里不保证有发布时间字段，因此 {@code publishedAt} 恒为 null（不猜测、不拿当前时间顶替）。
 *
 * @author yuanke
 */
@Component
public class TavilySearchProvider implements WebSearchProvider {

    /** 官方默认地址（webSearch.baseUrl 留空时用） */
    private static final String DEFAULT_URL = "https://api.tavily.com/search";
    /** basic 是官方默认的平衡档；advanced 更准但更慢更贵，交给服务商侧配置更合适 */
    private static final String SEARCH_DEPTH = "basic";

    @Override
    public String id() {
        return "tavily";
    }

    @Override
    public List<WebSearchResult> search(WebSearchRequest req) throws Exception {
        String url = (req.baseUrl() == null || req.baseUrl().isBlank()) ? DEFAULT_URL : req.baseUrl().trim();
        JSONObject body = new JSONObject();
        body.put("query", req.query());
        body.put("max_results", req.maxResults());
        body.put("search_depth", SEARCH_DEPTH);
        // 不取 answer/raw_content：answer 是服务商替模型做的总结，会把引用证据变成二手内容；
        // raw_content 是整页正文，体积大且不如摘要适合做 [N] 证据。
        body.put("include_answer", false);
        body.put("include_raw_content", false);

        Map<String, String> headers = new HashMap<>(2);
        headers.put("Authorization", "Bearer " + (req.apiKey() == null ? "" : req.apiKey().trim()));

        String resp = SearchHttp.post(url, headers, body.toJSONString(), req.timeoutMs());
        JSONObject obj = JSON.parseObject(resp);
        JSONArray arr = obj.getJSONArray("results");
        List<WebSearchResult> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject it = arr.getJSONObject(i);
            if (it == null) continue;
            String u = it.getString("url");
            if (u == null || u.isBlank()) continue; // 没有链接的结果无法溯源，直接丢弃
            Double score = null;
            Object sv = it.get("score");
            if (sv instanceof Number n) score = n.doubleValue();
            out.add(new WebSearchResult(
                    it.getString("title"),
                    u.trim(),
                    it.getString("content"),
                    null,
                    null,
                    score));
        }
        return out;
    }
}
