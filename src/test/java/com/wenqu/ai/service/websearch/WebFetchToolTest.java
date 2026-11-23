package com.wenqu.ai.service.websearch;

import com.wenqu.ai.common.BizException;
import com.wenqu.ai.service.WebPageFetcher;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * webFetch 的契约：同族同闸同配额、正文进不可信信封、注册引用、失败直说。
 *
 * <p>网络部分用可覆盖的 {@link WebPageFetcher} 桩，不碰真实站点；这里只钉工具层行为，
 * SSRF 与大小上限由 {@code SsrfGuard} / {@code WebPageFetcher} 自己的测试负责。
 */
class WebFetchToolTest {

    private static final String HTML = """
            <html><head><title>示例文章</title></head><body>
            <nav>ALERT-NAV</nav>
            <article><h1>示例文章</h1><p>这是要交给模型的正文内容，包含具体事实与数据。</p></article>
            </body></html>
            """;

    private static WebPageFetcher fetcher(String html) {
        return new WebPageFetcher() {
            @Override
            public FetchResult fetchPage(URI uri) {
                return new FetchResult(uri, html.getBytes(StandardCharsets.UTF_8), "text/html");
            }
        };
    }

    private static class Sink implements WebSearchTools.WebSearchSink {
        final AtomicInteger quota = new AtomicInteger(1);
        final AtomicReference<WebSearchResult> registered = new AtomicReference<>();

        @Override public boolean tryConsumeQuota() { return quota.getAndDecrement() > 0; }
        @Override public String question() { return null; }
        @Override public int register(WebSearchResult result) {
            registered.set(result);
            return 7;
        }
        @Override public int register(WebSearchResult result, Double score) { return register(result); }
        @Override public void degrade(String code, String message) { }
        @Override public String roundRerankModel() { return null; }
    }

    private static WebSearchTools tools(WebPageFetcher fetcher) {
        return new WebSearchTools(null, null, null, fetcher);
    }

    @Test
    void registersCitationAndWrapsBodyAsUntrustedData() {
        Sink sink = new Sink();

        String out = tools(fetcher(HTML)).webFetch("https://example.com/a", sink);

        assertThat(out).contains("【引用7】")
                .contains("示例文章")
                .contains("这是要交给模型的正文内容")
                .contains("<untrusted-data")
                .contains("</untrusted-data>")
                .contains("https://example.com/a")
                .doesNotContain("ALERT-NAV");
        assertThat(sink.quota.get()).isZero();
        assertThat(sink.registered.get()).isNotNull();
        assertThat(sink.registered.get().url()).isEqualTo("https://example.com/a");
        assertThat(sink.registered.get().snippet()).contains("这是要交给模型的正文内容");
    }

    @Test
    void withoutSinkUsesPlainFragmentLabel() {
        String out = tools(fetcher(HTML)).webFetch("https://example.com/a", (WebSearchTools.WebSearchSink) null);

        assertThat(out).contains("【网页正文】").doesNotContain("【引用");
    }

    @Test
    void quotaIsConsumedBeforeAnyNetworkCall() {
        AtomicBoolean called = new AtomicBoolean(false);
        WebPageFetcher fetcher = new WebPageFetcher() {
            @Override
            public FetchResult fetchPage(URI uri) {
                called.set(true);
                return new FetchResult(uri, HTML.getBytes(StandardCharsets.UTF_8), "text/html");
            }
        };
        Sink exhausted = new Sink();
        exhausted.quota.set(0);

        String out = tools(fetcher).webFetch("https://example.com/a", exhausted);

        assertThat(out).contains("已达到本轮联网工具次数上限");
        assertThat(called).isFalse();
    }

    @Test
    void rejectsNonHttpUrlBeforeFetching() {
        AtomicBoolean called = new AtomicBoolean(false);
        WebPageFetcher fetcher = new WebPageFetcher() {
            @Override
            public FetchResult fetchPage(URI uri) {
                called.set(true);
                return new FetchResult(uri, HTML.getBytes(StandardCharsets.UTF_8), "text/html");
            }
        };

        assertThat(tools(fetcher).webFetch("file:///etc/passwd", new Sink()))
                .contains("只支持 http/https");
        assertThat(called).isFalse();
    }

    @Test
    void fetchFailureIsFailLoud() {
        WebPageFetcher failing = new WebPageFetcher() {
            @Override
            public FetchResult fetchPage(URI uri) {
                throw new BizException("网页抓取失败: 站点不可达");
            }
        };

        assertThat(tools(failing).webFetch("https://example.com/a", new Sink()))
                .contains("网页抓取失败", "站点不可达", "不要编造网页内容");
    }

    @Test
    void emptyBodySaysSoInsteadOfReturningEmptyEvidence() {
        String out = tools(fetcher("<html><head></head><body></body></html>"))
                .webFetch("https://example.com/empty", new Sink());

        assertThat(out).contains("没有可提取的正文", "不要编造内容");
    }
}
