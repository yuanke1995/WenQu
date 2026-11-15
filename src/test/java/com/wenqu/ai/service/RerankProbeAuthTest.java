package com.wenqu.ai.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 探测必须带各路由自己的 Bearer：OpenAI 兼容网关（硅基流动等）的 /v1/models 同样要求鉴权，
 * 漏带 Key 会稳定 401，把可用路由误判成故障（重排整轮不执行）。
 */
class RerankProbeAuthTest {

    private static final String KEY = "test-key";
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void probeAndRerankSendBearer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + KEY).equals(auth)) {
                byte[] body = "{\"code\":30014,\"message\":\"Token is invalid.\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(401, body.length);
                ex.getResponseBody().write(body);
            } else {
                byte[] body = "{\"data\":[{\"id\":\"BAAI/bge-reranker-v2-m3\"}]}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.createContext("/v1/rerank", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            int code = ("Bearer " + KEY).equals(auth) ? 200 : 401;
            byte[] body = ("{\"results\":[{\"index\":0,\"relevance_score\":0.2},"
                    + "{\"index\":1,\"relevance_score\":0.9}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ModelRegistryService registry = new ModelRegistryService(null, null, null, null, null, null, null) {
            @Override
            public ModelRoute resolveReference(String value) {
                return new ModelRoute("p1", baseUrl, KEY, null, null, "BAAI/bge-reranker-v2-m3", "rerank");
            }
        };
        ConfigService config = new ConfigService(null, null, null, null, null, null, null, null) {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public int getInt(String key) {
                return 0;
            }

            @Override
            public int getInt(String key, int def) {
                return def;
            }
        };

        RerankService service = new RerankService(config, registry);
        List<HybridRetrievalService.Hit> hits = List.of(
                hit("a", "低相关", 0.8),
                hit("b", "高相关", 0.5));
        List<HybridRetrievalService.Hit> ranked = service.rankForced(hits, "问题", "p1/BAAI/bge-reranker-v2-m3");

        assertEquals("b", ranked.get(0).knowledgeId());
        assertNotNull(ranked.get(0).rerankScore());
        assertEquals(0.9, ranked.get(0).rerankScore());
    }

    private static HybridRetrievalService.Hit hit(String id, String title, double score) {
        return new HybridRetrievalService.Hit(id, "d" + id, title, "内容" + id,
                List.of(), score, 0, null, null, "kb1");
    }
}
