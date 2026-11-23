package com.wenqu.ai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正文抽取的边界：去噪、容器优先、兜底、封顶都钉住。
 *
 * <p>这是模型可见内容的第一道结构闸——漏掉正文会让工具答「没内容」，漏掉噪声会把导航和脚本
 * 一起送进上下文，两者都是难查的静默故障。
 */
class WebBodyExtractorTest {

    @Test
    void keepsArticleBodyAndDropsPageChrome() {
        String html = """
                <html><head><title>示例</title>
                <script>function evil() { return 'ALERT-SCRIPT'; }</script>
                <style>.nav { color: ALERT-STYLE; }</style></head>
                <body>
                  <nav>站点导航 ALERT-NAV</nav>
                  <header>页头 ALERT-HEADER</header>
                  <article><h1>正文标题</h1>
                    <p>正文第一段，这里是要给模型读的真实内容。</p>
                    <p>正文第二段，补充细节与数据。</p>
                  </article>
                  <aside>推荐 ALERT-ASIDE</aside>
                  <footer>页脚版权 ALERT-FOOTER</footer>
                  <!-- ALERT-COMMENT -->
                </body></html>
                """;

        String text = WebBodyExtractor.extract(html, 12000);

        assertThat(text).contains("正文标题", "正文第一段", "正文第二段");
        assertThat(text).doesNotContain("ALERT-NAV", "ALERT-HEADER", "ALERT-ASIDE",
                "ALERT-FOOTER", "ALERT-SCRIPT", "ALERT-STYLE", "ALERT-COMMENT");
        assertThat(text).contains("\n");
    }

    @Test
    void fallsBackToBodyWhenNoSemanticContainer() {
        String html = "<html><body><div><p>没有 article 标签时的正文内容，至少要有这么长才不会被当成空壳丢掉。</p></div></body></html>";

        String text = WebBodyExtractor.extract(html, 12000);

        assertThat(text).contains("没有 article 标签时的正文内容");
    }

    @Test
    void keepsLineBreaksFromBlockTags() {
        String html = "<article><p>第一行</p><p>第二行<br>第三行</p></article>";

        assertThat(WebBodyExtractor.extract(html, 12000)).contains("第一行\n第二行\n第三行");
    }

    @Test
    void capsLongBodyWithExplicitNotice() {
        String html = "<article><p>" + "正文内容".repeat(3000) + "</p></article>";

        String text = WebBodyExtractor.extract(html, 50);

        assertThat(text).startsWith("正文内容");
        assertThat(text).contains("正文已截断，未抓取完整内容");
        assertThat(text.length()).isGreaterThan(50);
    }

    @Test
    void emptyInputReturnsEmptyText() {
        assertThat(WebBodyExtractor.extract((String) null, 12000)).isEmpty();
        assertThat(WebBodyExtractor.extract("  ", 12000)).isEmpty();
        assertThat(WebBodyExtractor.extract(new byte[0])).isEmpty();
    }
}
