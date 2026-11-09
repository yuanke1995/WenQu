package com.wenqu.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 产物工具的内容校验：引用句与非 HTML 网页必须在下库前拒绝。
 */
class PresentArtifactToolTest {

    @Test
    @DisplayName("网页产物：content 是「见 index.html」引用句时拒绝")
    void rejectsPointerInsteadOfHtml() {
        String reason = PresentArtifactTool.contentRejection(
                "index.html", "见 index.html（单文件网页，含内联样式与脚本，双击即可在浏览器打开）");
        assertThat(reason).isNotNull();
        assertThat(reason).contains("deliver_artifact");
    }

    @Test
    @DisplayName("网页产物：只有文件名、没有任何标签时拒绝")
    void rejectsBareFilenameOrPlainTextAsHtml() {
        assertThat(PresentArtifactTool.contentRejection("index.html", "index.html")).isNotNull();
        assertThat(PresentArtifactTool.contentRejection("index.html", "问渠官网")).isNotNull();
    }

    @Test
    @DisplayName("网页产物：完整 HTML 或片段放行")
    void acceptsRealHtml() {
        assertThat(PresentArtifactTool.contentRejection("index.html", "<!DOCTYPE html><html><body>x</body></html>"))
                .isNull();
        assertThat(PresentArtifactTool.contentRejection("index.htm", "<h1>问渠</h1>")).isNull();
    }

    @Test
    @DisplayName("非网页产物：正常正文放行，短引用句与裸文件名拒绝")
    void handlesNonHtmlArtifacts() {
        assertThat(PresentArtifactTool.contentRejection("清单.md", "# 操作清单\n\n| 项 | 值 |\n| - | - |")).isNull();
        assertThat(PresentArtifactTool.contentRejection("清单.md", "详见 操作清单.md")).isNotNull();
        assertThat(PresentArtifactTool.contentRejection("数据.csv", "数据.csv")).isNotNull();
        // 超过引用句长度上限的正文，即使开头提到文件名也不按引用误杀
        assertThat(PresentArtifactTool.contentRejection(
                "清单.md", "见 index.html 的导航结构，以下是完整清单：\n" + "条目\n".repeat(60))).isNull();
    }
}
