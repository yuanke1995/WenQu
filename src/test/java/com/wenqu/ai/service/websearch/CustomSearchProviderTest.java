package com.wenqu.ai.service.websearch;

import com.sun.net.httpserver.HttpServer;
import com.wenqu.ai.service.ConfigService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自定义 HTTP 搜索适配器（custom 服务商）契约：请求模板渲染、响应点路径解析、鉴权与 fail-loud。
 * <p>
 * 用 JDK 内置 HttpServer 造三家真实响应形状（智谱 / 博查嵌套 / 百炼深层点路径）真打 HTTP，
 * 保证「照 tips 抄模板就能接上任意厂商」这条承诺不是纸面话。
 */
class CustomSearchProviderTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastAuth = new AtomicReference<>("");
    private final AtomicReference<String> lastUri = new AtomicReference<>("");
    /** 各路径的固定响应（未登记的路径回落智谱形状），供多形状用例共用同一个录制 handler */
    private final Map<String, String> pathResponses = new HashMap<>();

    /** 固定配置表的 ConfigService 桩：provider 只读 custom* 与 model 键，其余依赖用不上 */
    private CustomSearchProvider provider(Map<String, String> cfg) {
        // 缺省键回落 schema def（字段路径四键），对齐运行时 defaults() 基座口径
        Map<String, String> merged = new HashMap<>(Map.of(
                "webSearch.customResultsPath", "results",
                "webSearch.customTitlePath", "title",
                "webSearch.customUrlPath", "url",
                "webSearch.customSnippetPath", "content"));
        merged.putAll(cfg);
        ConfigService stub = new ConfigService(null, null, null, null, null, null, null, null) {
            @Override
            public String get(String key) {
                String v = merged.get(key);
                return v == null ? "" : v;
            }
        };
        return new CustomSearchProvider(stub);
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization") == null
                    ? "" : ex.getRequestHeaders().getFirst("Authorization"));
            lastUri.set(ex.getRequestURI().toString());
            String prefix = ex.getRequestURI().getPath();
            String configured = pathResponses.getOrDefault(prefix, """
                    {"search_result":[{"title":"智谱结果","link":"https://z.example.com/1","content":"智谱摘要","media":"zhihu","publish_date":"2026-11-14"}]}
                    """);
            byte[] resp = configured.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        pathResponses.clear();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/web_search";
    }

    @Test
    void zhipuShapePostTemplate() throws Exception {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("webSearch.customMethod", "POST");
        cfg.put("webSearch.customBody", "{\"search_query\":\"{{query}}\",\"count\":{{count}}}");
        cfg.put("webSearch.customResultsPath", "search_result");
        cfg.put("webSearch.customUrlPath", "link");
        cfg.put("webSearch.customPublishedPath", "publish_date");
        List<WebSearchResult> out = provider(cfg).search(
                new WebSearchRequest(base(), "sk-test", 5000, "问渠 渠道", 3));
        assertThat(out).hasSize(1);
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
        assertThat(lastBody.get()).isEqualTo("{\"search_query\":\"问渠 渠道\",\"count\":3}");
        assertThat(out.get(0).url()).isEqualTo("https://z.example.com/1");
        assertThat(out.get(0).title()).isEqualTo("智谱结果");
        assertThat(out.get(0).snippet()).isEqualTo("智谱摘要");
        assertThat(out.get(0).publishedAt()).isEqualTo("2026-11-14");
    }

    @Test
    void bochaShapeNestedPathAndDefaultFieldPaths() throws Exception {
        pathResponses.put("/bocha", """
                {"data":{"webPages":{"value":[{"name":"博查结果","url":"https://b.example.com/1","snippet":"博查摘要"}]}}}
                """);
        Map<String, String> cfg = new HashMap<>();
        cfg.put("webSearch.customBody", "{\"query\":\"{{query}}\"}");
        // 结果数组三层嵌套；标题/链接/摘要走默认 def（title/url/content 不合博查口径，须显式覆盖）
        cfg.put("webSearch.customResultsPath", "data.webPages.value");
        cfg.put("webSearch.customTitlePath", "name");
        cfg.put("webSearch.customSnippetPath", "snippet");
        List<WebSearchResult> out = provider(cfg).search(
                new WebSearchRequest(base().replace("/web_search", "/bocha"), "", 5000, "x", 5));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).url()).isEqualTo("https://b.example.com/1");
        assertThat(out.get(0).snippet()).isEqualTo("博查摘要");
    }

    @Test
    void dashscopeShapeDeepPath() throws Exception {
        pathResponses.put("/dash", """
                {"output":{"search_info":{"search_results":[{"title":"百炼结果","url":"https://d.example.com/1"}]}},"usage":{}}
                """);
        Map<String, String> cfg = new HashMap<>();
        cfg.put("webSearch.customMethod", "POST");
        cfg.put("webSearch.customBody", "{\"model\":\"{{model}}\",\"input\":{\"messages\":[{\"role\":\"user\",\"content\":\"{{query}}\"}]},\"parameters\":{\"enable_search\":true}}");
        cfg.put("webSearch.customResultsPath", "output.search_info.search_results");
        cfg.put("webSearch.model", "qwen-max");
        List<WebSearchResult> out = provider(cfg).search(
                new WebSearchRequest(base().replace("/web_search", "/dash"), "sk", 5000, "x", 5));
        assertThat(out).hasSize(1);
        // {{model}} 占位被替换进请求体；百炼结果无摘要 → 空串由上层补占位说明
        assertThat(lastBody.get()).contains("qwen-max").contains("\"content\":\"x\"");
        assertThat(out.get(0).snippet()).isNullOrEmpty();
    }

    @Test
    void getUrlTemplateEncodesQuery() throws Exception {
        pathResponses.put("/search", """
                {"results":[{"title":"SearXNG 形状","url":"https://s.example.com/1","content":"摘要"}]}
                """);
        Map<String, String> cfg = new HashMap<>();
        cfg.put("webSearch.customMethod", "GET");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/search?q={{query}}&limit={{count}}";
        List<WebSearchResult> out = provider(cfg).search(
                new WebSearchRequest(url, "", 5000, "问渠 & 渠道", 2));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).url()).isEqualTo("https://s.example.com/1");
        // {{query}} 已 URL 编码进 query string（解码后还原原词、原始 URI 里无裸空格/裸 &）
        assertThat(java.net.URLDecoder.decode(lastUri.get(), StandardCharsets.UTF_8))
                .contains("q=问渠 & 渠道").doesNotContain("%");
    }

    @Test
    void failLoudOnMissingTemplateAndMissingResults() {
        Map<String, String> noBody = new HashMap<>();
        noBody.put("webSearch.customMethod", "POST");
        assertThatThrownBy(() -> provider(noBody).search(
                new WebSearchRequest(base(), "", 5000, "x", 1)))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("customBody");

        Map<String, String> wrongPath = new HashMap<>();
        wrongPath.put("webSearch.customBody", "{\"q\":\"{{query}}\"}");
        wrongPath.put("webSearch.customResultsPath", "not.there");
        assertThatThrownBy(() -> provider(wrongPath).search(
                new WebSearchRequest(base(), "", 5000, "x", 1)))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("not.there");

        Map<String, String> noQueryPlaceholder = new HashMap<>();
        noQueryPlaceholder.put("webSearch.customMethod", "GET");
        assertThatThrownBy(() -> provider(noQueryPlaceholder).search(
                new WebSearchRequest(base(), "", 5000, "x", 1)))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("{{query}}");
    }

    @Test
    void jsonEscapesQuotesInQuery() throws Exception {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("webSearch.customBody", "{\"q\":\"{{query}}\"}");
        cfg.put("webSearch.customResultsPath", "search_result");
        provider(cfg).search(new WebSearchRequest(base(), "", 5000, "引\"号\\反斜", 1));
        // 请求体仍是合法 JSON 且原词完整还原，模板替换不破坏 JSON 结构
        com.alibaba.fastjson2.JSONObject obj =
                com.alibaba.fastjson2.JSON.parseObject(lastBody.get());
        assertThat(obj.getString("q")).isEqualTo("引\"号\\反斜");
    }
}
