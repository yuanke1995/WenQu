package com.wenqu.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追问改写的纯函数单测：触发门、输出清洗、等价判定。
 * 不起 Spring、不调真实模型。
 */
class FollowUpQueryRewriterTest {

    private static List<Map<String, Object>> history() {
        return List.of(Map.of("role", "user", "content", "报销制度什么时候生效？"));
    }

    @Test
    @DisplayName("触发门：无历史/空词/过长/独立问句都跳过")
    void gateSkips() {
        assertThat(FollowUpQueryRewriter.gate("它", null)).isEqualTo("no-history");
        assertThat(FollowUpQueryRewriter.gate("  ", history())).isEqualTo("blank");
        assertThat(FollowUpQueryRewriter.gate("这是一个已经足够长且完全独立的检索问句用来验证触发门的长度阈值", history()))
                .isEqualTo("too-long");
        assertThat(FollowUpQueryRewriter.gate("报销流程一般需要哪些材料呢", history())).isEqualTo("standalone");
    }

    @Test
    @DisplayName("触发门：短词与含指代词的查询放行")
    void gateAllows() {
        assertThat(FollowUpQueryRewriter.gate("生效时间", history())).isNull();
        assertThat(FollowUpQueryRewriter.gate("这份制度适用哪些部门", history())).isNull();
    }

    @Test
    @DisplayName("清洗：去前缀、引号、换行与代码围栏")
    void sanitize() {
        assertThat(FollowUpQueryRewriter.sanitize("改写后的检索词：报销制度生效时间"))
                .isEqualTo("报销制度生效时间");
        assertThat(FollowUpQueryRewriter.sanitize("```\n\"报销制度\"\n```")).isEqualTo("报销制度");
        assertThat(FollowUpQueryRewriter.sanitize("  多行\n检索\t词  ")).isEqualTo("多行 检索 词");
        assertThat(FollowUpQueryRewriter.sanitize("   ")).isNull();
        assertThat(FollowUpQueryRewriter.sanitize("长".repeat(FollowUpQueryRewriter.MAX_OUTPUT_CHARS + 1))).isNull();
    }

    @Test
    @DisplayName("判定：与原词相同不算改写，清洗后不同才算成功")
    void decide() {
        var unchanged = FollowUpQueryRewriter.decide("报销制度", "\"报销制度\"", 30);
        assertThat(unchanged.applied()).isFalse();
        assertThat(unchanged.skipReason()).isEqualTo("unchanged");

        var applied = FollowUpQueryRewriter.decide("它", "报销制度生效时间", 42);
        assertThat(applied.applied()).isTrue();
        assertThat(applied.query()).isEqualTo("报销制度生效时间");
        assertThat(applied.elapsedMs()).isEqualTo(42);

        var empty = FollowUpQueryRewriter.decide("它", "  ", 10);
        assertThat(empty.applied()).isFalse();
        assertThat(empty.skipReason()).isEqualTo("empty-output");
        assertThat(empty.query()).isEqualTo("它");
    }
}
