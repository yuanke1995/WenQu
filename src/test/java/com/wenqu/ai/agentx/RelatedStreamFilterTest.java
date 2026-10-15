package com.wenqu.ai.agentx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code <related>} / {@code <process>} 流式剥离的回归账。
 * <p>为什么单独立一份：这条能力真机只跑过一次「恰好把开标签切成两块」的现场，
 * 而它最容易坏的地方正是跨增量切分与尾部扣留——坏了的症状是标签原文印进用户读到的回答里。
 * 长条目并回正文那条护栏（{@code prose}）真机那次也没被触发（夹具条目没过 80 字阈值），
 * 在这里补上，不拿「两侧行为一致」冒充「护栏已验」。
 */
@DisplayName("框架引擎：推荐块与过程块的流式剥离")
class RelatedStreamFilterTest {

    private static String feedAll(RelatedStreamFilter f, String... pieces) {
        StringBuilder out = new StringBuilder();
        for (String p : pieces) out.append(f.feed(p));
        out.append(f.finish());
        return out.toString();
    }

    @Test
    @DisplayName("开标签跨两块也剥得干净，闭合标签之后的文字归回正文")
    void splitsTagAcrossChunks() {
        RelatedStreamFilter f = new RelatedStreamFilter();
        String body = feedAll(f, "问渠是个 AI 平台。<rel", "ated>怎么共享知识库|产物能下载吗", "</related>", "还有尾巴");
        assertEquals("问渠是个 AI 平台。还有尾巴", body);
        assertEquals(List.of("怎么共享知识库", "产物能下载吗"), f.chips(80));
        assertTrue(f.prose(80).isEmpty());
    }

    @Test
    @DisplayName("闭合标签被切开时，块内文字不得漏进正文")
    void splitsClosingTag() {
        RelatedStreamFilter f = new RelatedStreamFilter();
        String body = feedAll(f, "正文。<related>建议一|建议二</rel", "ated>收尾一句");
        assertEquals("正文。收尾一句", body);
        assertEquals(List.of("建议一", "建议二"), f.chips(80));
    }

    @Test
    @DisplayName("没有推荐块的普通回答一个字都不扣（扣留只针对标签真前缀）")
    void plainTextPassesThrough() {
        RelatedStreamFilter f = new RelatedStreamFilter();
        StringBuilder out = new StringBuilder();
        for (String piece : new String[]{"今天", "天气", "不错", "。"}) out.append(f.feed(piece));
        out.append(f.finish());
        assertEquals("今天天气不错。", out.toString());
        assertTrue(!f.hasRelated());
    }

    @Test
    @DisplayName("开标签来了但闭合标签始终没来：整段按推荐块处理，不把半截标签留给用户")
    void unclosedRelatedBlock() {
        RelatedStreamFilter f = new RelatedStreamFilter();
        String body = feedAll(f, "回答到这。<related>建议一|建议二");
        assertEquals("回答到这。", body);
        assertEquals(List.of("建议一", "建议二"), f.chips(80));
    }

    @Test
    @DisplayName("长条目按正文并回而不是当芯片（真机那次没触发到的护栏）")
    void longEntriesCountAsProse() {
        String longIdea = ("这一段长得像是正文而不是一个可以点选的推荐条目，因为它超过了八十个字符，"
                + "按护栏应该并回回答而不是当芯片使用，继续凑够长度以触发那条护栏判定，"
                + "再补几句把字数坐实到阈值之上：模型确实会把整段说明写进推荐块里。");
        assertTrue(longIdea.length() > 80, "夹具本身要够长，否则测的是另一件事：" + longIdea.length());
        RelatedStreamFilter f = new RelatedStreamFilter();
        feedAll(f, "正文在此。<related>能点选的短问句|" + longIdea + "</related>");
        assertEquals(List.of("能点选的短问句"), f.chips(80));
        assertEquals(List.of(longIdea), f.prose(80));
    }

    @Test
    @DisplayName("过程块单独成列，不混进正文也不混进推荐")
    void processBlockSeparate() {
        RelatedStreamFilter f = new RelatedStreamFilter();
        String body = feedAll(f, "结论一句。<process>我先查了手册", "再看了一眼清单</process>尾巴照常");
        assertEquals("结论一句。尾巴照常", body);
        assertEquals("我先查了手册再看了一眼清单", f.process());
    }
}
