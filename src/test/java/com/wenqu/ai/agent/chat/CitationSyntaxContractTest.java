package com.wenqu.ai.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用标记语法的前后端契约：后端解析认的 {@code [N]} / {@code [图片N：描述]} 与前端
 * {@code web/src/utils/markdown.js} 渲染角标/图片占位认的必须是同一套形状。
 * <p>两端各写一份正则时，任何一侧放宽或收紧都会静默分叉：后端剔得掉的标记前端渲染不出、
 * 或前端渲染成角标的文本后端判不成引用——角标错位与悬空标记都长在这条缝上。
 * 本测试直接从前端源码里抽出正则跑同一组样本，改任何一端都会在这里先红。
 */
class CitationSyntaxContractTest {

    /** surefire 工作目录=仓库根；读不到说明前端文件挪了位置，契约本身就该报警 */
    private static String frontendSource() throws Exception {
        Path p = Path.of("web", "src", "utils", "markdown.js");
        assertThat(p)
                .as("markdown.js 位置变了：同步更新本契约测试的读取路径")
                .exists();
        return Files.readString(p);
    }

    /** JS 字符串字面量里的正则源（\\ 还原成 \），编译成 Java Pattern——两端方言在这组样本上等价 */
    private static Pattern compileFromJsLiteral(String jsSource, String constName) {
        Matcher m = Pattern.compile("const " + constName + " = '((?:[^'\\\\]|\\\\.)*)'")
                .matcher(jsSource);
        assertThat(m.find()).as("前端 %s 常量没找到：命名变了就是契约变了", constName).isTrue();
        return Pattern.compile(m.group(1).replace("\\\\", "\\"));
    }

    @Test
    @DisplayName("图片标记：前端 IMG_MARK_SRC 与后端 IMG_NUMBER_PATTERN 对同一组样本认出同一标记")
    void imageMarkerAgrees() throws Exception {
        Pattern front = compileFromJsLiteral(frontendSource(), "IMG_MARK_SRC");
        Pattern back = CitationConvention.IMG_NUMBER_PATTERN;

        for (String s : List.of("[图片1]", "[图片2：报表设计界面]", "[图片10]",
                "拖拽组件[图片3：字段面板]到画布", "[图片", "图片1]", "[图片X]", "[图片]")) {
            Matcher f = front.matcher(s);
            Matcher b = back.matcher(s);
            while (f.find() && b.find()) {
                assertThat(b.group()).as("样本「%s」两端识别的标记不一致", s).isEqualTo(f.group());
            }
            assertThat(f.find()).as("样本「%s」前端多认了一处", s).isFalse();
            assertThat(b.find()).as("样本「%s」后端多认了一处", s).isFalse();
        }
    }

    @Test
    @DisplayName("引用角标：前端渲染用的 [数字] 形状与后端 CITE_PATTERN 一致")
    void citeMarkerAgrees() throws Exception {
        String js = frontendSource();
        // 前端角标两处形状（检测 /\[\d+\]/ 与拆分 /(\[\d+\])/）都必须是「方括号包纯数字」；
        // 字面量钉死，改动前端正则而不改后端时这里先红
        assertThat(js).contains("/\\[\\d+\\]/");
        assertThat(js).contains("/(\\[\\d+\\])/");

        Pattern front = Pattern.compile("\\[\\d+\\]");
        Pattern back = CitationConvention.CITE_PATTERN;
        for (String s : List.of("[1]", "[23]", "句子[3]结尾", "[a]", "[]", "[图片1]", "[1a]")) {
            Matcher f = front.matcher(s);
            Matcher b = back.matcher(s);
            while (f.find() && b.find()) {
                assertThat(b.group()).as("样本「%s」两端识别的角标不一致", s).isEqualTo(f.group());
            }
            assertThat(f.find()).as("样本「%s」前端多认了一处", s).isFalse();
            assertThat(b.find()).as("样本「%s」后端多认了一处", s).isFalse();
        }
    }

    @Test
    @DisplayName("三通道约定都带句级归属——历史上 MCP 漏过这一段")
    void allChannelsCarryAttributionRule() {
        String shared = "每个编号必须指向实际提供了该句内容的那一条来源";
        assertThat(CitationConvention.knowledgeBase()).contains(shared);
        assertThat(CitationConvention.webSearch()).contains(shared);
        assertThat(CitationConvention.mcp("[1] 某站文章（example.com）")).contains(shared);
    }
}
