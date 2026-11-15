package com.wenqu.ai.service.websearch;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自建 SearXNG 适配器契约：请求必带 language（否则 bing 返回无关降级页）、响应字段收敛。
 */
class GenericSearchProviderTest {

    private HttpServer server;
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", ex -> {
            lastQuery.set(ex.getRequestURI().getRawQuery());
            byte[] body = ("{\"results\":[{\"title\":\"钉钉考勤\",\"url\":\"https://www.dingtalk.com/page/check\","
                    + "\"content\":\"考勤与排班\",\"score\":1.0,\"published_date\":\"2026-01-01\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private List<WebSearchResult> search(String query) throws Exception {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GenericSearchProvider().search(new WebSearchRequest(base, "", 5000, query, 10));
    }

    @Test
    void chineseQueryPinsChineseLanguage() throws Exception {
        List<WebSearchResult> results = search("考勤系统");

        assertThat(lastQuery.get()).contains("language=zh-CN");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).snippet()).isEqualTo("考勤与排班");
        assertThat(results.get(0).publishedAt()).isEqualTo("2026-01-01");
    }

    @Test
    void englishQueryUsesEnglishLanguage() throws Exception {
        search("attendance system");

        assertThat(lastQuery.get()).contains("language=en").doesNotContain("zh-CN");
    }

    @Test
    void mixedQueryPrefersChinese() throws Exception {
        search("Spring AI 和 LangChain 区别");

        assertThat(lastQuery.get()).contains("language=zh-CN");
    }
}