package com.wenqu.ai.service.websearch;

import com.sun.net.httpserver.HttpServer;
import com.wenqu.ai.agentx.WenquAgentxTools.TurnHooks;
import com.wenqu.ai.service.ConfigService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

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
 * 联网搜索个人层口径：服务商/Key/模板是个人专属配置，必须满足三条——
 * <ol>
 *   <li>个人未设置 → 回落平台内置 SearXNG（generic，免费），不碰平台层存过的计费 Key；</li>
 *   <li>个人选了计费厂商但没填 Key → fail-loud 报个人设置引导，绝不借用平台 Key；</li>
 *   <li>个人全套配置（custom）在工具线程重放后按本人值生效。</li>
 * </ol>
 * ConfigService 用桩实现 resolveChain 的等价语义（USER_OVERRIDE ThreadLocal → personalOnly 空闸 → 平台表），
 * 真链的 ThreadLocal 交互（putUserOverrides/clearUserOverrides）走 ConfigService 实方法。
 */
class WebSearchPersonalLayerTest {

    /** 与 schema 的 personalOnly 标记一致的键集（本测试的桩链按此判「无全局层」） */
    private static final java.util.Set<String> PERSONAL_ONLY = java.util.Set.of(
            "webSearch.provider", "webSearch.apiKey", "webSearch.model",
            "webSearch.customMethod", "webSearch.customBody", "webSearch.customResultsPath",
            "webSearch.customTitlePath", "webSearch.customUrlPath", "webSearch.customSnippetPath",
            "webSearch.customPublishedPath");

    private static HttpServer server;
    private static final AtomicReference<String> lastAuth = new AtomicReference<>("");
    private static final AtomicReference<String> lastBody = new AtomicReference<>("");

    /** 平台层存值（非 personalOnly 键的兜底）；personalOnly 键永不读这张表 */
    private Map<String, String> platform = new HashMap<>();

    private ConfigService stub() {
        return new ConfigService(null, null, null, null, null, null, null, null) {
            @Override
            public String get(String key) {
                Map<String, String> ov = currentUserOverrides();
                if (ov != null) {
                    String u = ov.get(key);
                    if (u != null && !u.isBlank()) return u;
                }
                if (PERSONAL_ONLY.contains(key)) return "";   // 个人专属键无全局层
                String v = platform.get(key);
                return v == null ? "" : v;
            }

            @Override
            public boolean getBoolean(String key) {
                // 绕开真实 getOrDefaultParsed（其兜底链依赖未注入的 schema），按链值直接解析
                return Boolean.parseBoolean(get(key).trim());
            }

            @Override
            public Layer layerOf(String key) {
                // 运行时靠这一层区分「部署方配的平台内置地址」与「用户自己填的服务地址」
                Map<String, String> ov = currentUserOverrides();
                if (ov != null && ov.get(key) != null && !ov.get(key).isBlank()) return Layer.USER_OVERRIDE;
                return platform.containsKey(key) ? Layer.PLATFORM_DB : Layer.CODE_DEFAULT;
            }
        };
    }

    @BeforeAll
    static void start() throws IOException {
        // 类级共享一个假端点：SearchHttp 的静态 HttpClient 有连接池，每个测试起停新端点会在
        // 端口重绑时拿到上一世的旧连接（ConnectException），共享端点就没这个污染
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization") == null
                    ? "" : ex.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = """
                    {"results":[{"title":"平台内置结果","url":"https://sx.example.com/1","content":"摘要"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void resetPlatform() {
        lastAuth.set("");
        lastBody.set("");
        platform = new HashMap<>();
        platform.put("tool.enabled", "true");
        platform.put("webSearch.enabled", "true");
        // USER_OVERRIDE 是 ConfigService 的 static ThreadLocal：上一测试残留的个人覆盖必须清掉，
        // 否则「个人未设置」用例会被前一个用例的 custom 配置污染
        new ConfigService(null, null, null, null, null, null, null, null).clearUserOverrides();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private WebSearchService service(ConfigService cfg) {
        return new WebSearchService(cfg, List.of(new GenericSearchProvider(), new CustomSearchProvider(cfg)));
    }

    @Test
    void unsetPersonalFallsBackToBuiltinSearxng() throws Exception {
        platform.put("webSearch.baseUrl", base());   // 平台内置 SearXNG 地址
        // 个人什么都没配 → generic 平台内置，免费搜索可用
        List<WebSearchResult> out = service(stub()).search("问渠", 3);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).url()).isEqualTo("https://sx.example.com/1");
        // 平台层即便存过计费 Key，个人未设置时也绝不会被读到（personalOnly 无全局层）
        platform.put("webSearch.apiKey", "platform-secret-key");
        assertThat(service(stub()).search("问渠", 3)).hasSize(1);
    }

    @Test
    void paidProviderWithoutPersonalKeyFailsLoudInsteadOfUsingPlatformKey() {
        platform.put("webSearch.apiKey", "platform-secret-key");
        ConfigService cfg = stub();
        cfg.putUserOverrides(Map.of("webSearch.provider", "bocha"));   // 个人选了计费厂商但没填自己的 Key
        assertThatThrownBy(() -> service(cfg).search("x", 1))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("个人设置")
                .hasMessageContaining("bocha");
    }

    @Test
    void personalCustomConfigWorksEndToEnd() throws Exception {
        // 个人选 custom + 自家厂商端点：请求须打到个人地址、带个人 Key、模板按个人值渲染
        platform.put("webSearch.baseUrl", base() + "/builtin");   // 平台内置地址——个人请求绝不应打到这
        server.createContext("/mine", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization") == null
                    ? "" : ex.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = """
                    {"search_result":[{"title":"个人厂商结果","link":"https://mine.example.com/1","content":"个人摘要"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        ConfigService cfg = stub();
        cfg.putUserOverrides(Map.of(
                "webSearch.provider", "custom",
                "webSearch.apiKey", "sk-mine",
                "webSearch.baseUrl", base() + "/mine",
                "webSearch.customBody", "{\"search_query\":\"{{query}}\",\"count\":{{count}},\"model\":\"{{model}}\"}",
                "webSearch.customResultsPath", "search_result",
                "webSearch.customUrlPath", "link",
                "webSearch.model", "qwen-max"));
        List<WebSearchResult> out = service(cfg).search("问渠", 2);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).url()).isEqualTo("https://mine.example.com/1");
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-mine");
        assertThat(lastBody.get()).contains("\"search_query\":\"问渠\"").contains("qwen-max");
    }

    @Test
    void toolThreadReplaysUserOverridesFromTurnHooks() {
        // 模拟框架弹性线程：请求线程上 ThreadLocal 为空，覆盖只能经 ToolContext 里的 TurnHooks 带过来。
        // 个人选 bocha 但没填 Key：重放生效 → fail-loud 报个人设置引导；不重放 → 静默回落 generic 平台内置
        platform.put("webSearch.baseUrl", base());
        ConfigService cfg = stub();
        WebSearchService svc = service(cfg);
        WebSearchTools tools = new WebSearchTools(svc, cfg, null);   // 缺 Key 在重排之前就 fail，rerank 不会触达

        String out = tools.webSearch("x", 1, new ToolContext(Map.of(
                WebSearchTools.CTX_SINK, (WebSearchTools.WebSearchSink) new WebSearchTools.WebSearchSink() {
                    public boolean tryConsumeQuota() { return true; }
                    public String question() { return null; }
                    public int register(WebSearchResult r) { return 1; }
                    public int register(WebSearchResult r, Double s) { return 1; }
                    public void degrade(String c, String m) { }
                    public String roundRerankModel() { return null; }
                },
                WebSearchTools.CTX_HOOKS, new TurnHooks(null, "u1", null, null, null, null,
                        null, Map.of(), Map.of("webSearch.provider", "bocha"), null, null, null))));
        assertThat(out).contains("个人设置").contains("bocha");
    }

    @Test
    void webSearchRerankUsesRoundModelFromSink() {
        // 回归：联网工具跑在弹性线程，检索调用结束时 rerank.model 线程覆盖已撤。
        // 必须用 sink 带过来的本轮收敛模型走 3 参 rankForced；走 2 参重载会恒定拿不到路由，
        // 库里绑了模型也报「未绑定重排模型」并退化成词面过滤。
        platform.put("webSearch.baseUrl", base());
        ConfigService cfg = stub();
        AtomicReference<String> seenRef = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean usedTwoArg = new java.util.concurrent.atomic.AtomicBoolean(false);
        com.wenqu.ai.service.RerankService fake = new com.wenqu.ai.service.RerankService(null, null) {
            @Override
            public String debugUnavailableReason(String modelRef) {
                seenRef.set(modelRef);
                return null;   // 本轮可用，让调用方真的去跑重排
            }

            @Override
            public java.util.List<com.wenqu.ai.service.HybridRetrievalService.Hit> rankForced(
                    java.util.List<com.wenqu.ai.service.HybridRetrievalService.Hit> candidates,
                    String query, String modelRef) {
                return candidates;   // 不打分的桩：只要验证走了这条重载
            }

            @Override
            public java.util.List<com.wenqu.ai.service.HybridRetrievalService.Hit> rankForced(
                    java.util.List<com.wenqu.ai.service.HybridRetrievalService.Hit> candidates, String query) {
                usedTwoArg.set(true);
                return candidates;
            }
        };
        WebSearchTools tools = new WebSearchTools(service(cfg), cfg, fake);
        tools.webSearch("问渠", 1, new WebSearchTools.WebSearchSink() {
            public boolean tryConsumeQuota() { return true; }
            public String question() { return "问渠"; }
            public int register(WebSearchResult r) { return 1; }
            public int register(WebSearchResult r, Double s) { return 1; }
            public void degrade(String c, String m) { }
            public String roundRerankModel() { return "vendor:rerank-x"; }
        });
        assertThat(seenRef.get()).isEqualTo("vendor:rerank-x");
        assertThat(usedTwoArg.get()).as("联网搜索不得再用只认线程覆盖的 2 参重载").isFalse();
    }
}
