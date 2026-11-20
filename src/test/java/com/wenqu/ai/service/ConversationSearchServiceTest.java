package com.wenqu.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史对话检索的摘要片段：命中词在中间时要围绕它截取，而不是只给开头。
 *
 * @author yuanke
 */
class ConversationSearchServiceTest {

    @Test
    @DisplayName("短内容原样返回（压缩空白）")
    void shortText() {
        assertThat(ConversationSearchService.excerpt("  你好   问渠  ", "问渠")).isEqualTo("你好 问渠");
    }

    @Test
    @DisplayName("长内容围绕命中词截取，两端加省略号")
    void longTextCenteredOnKeyword() {
        String prefix = "前".repeat(400);
        String suffix = "后".repeat(400);
        String s = ConversationSearchService.excerpt(prefix + "关键词" + suffix, "关键词");
        assertThat(s).contains("关键词");
        assertThat(s).startsWith("…");
        assertThat(s).endsWith("…");
        assertThat(s.length()).isLessThanOrEqualTo(244);
    }
}
