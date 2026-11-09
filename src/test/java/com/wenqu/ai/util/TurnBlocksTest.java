package com.wenqu.ai.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分享视图裁剪：只留 answer 文本块，过程/工具/思考不外发。
 */
class TurnBlocksTest {

    private static final String FULL = "{\"v\":1,\"turnId\":\"t1\",\"blocks\":["
            + "{\"id\":\"b1\",\"kind\":\"think\",\"text\":\"内部思考\"},"
            + "{\"id\":\"b2\",\"kind\":\"text\",\"channel\":\"narration\",\"text\":\"我先查一下。\"},"
            + "{\"id\":\"b3\",\"kind\":\"tool\",\"name\":\"search_knowledge\",\"args\":\"{\\\"query\\\":\\\"x\\\"}\"},"
            + "{\"id\":\"b4\",\"kind\":\"process\",\"text\":\"过程独白\"},"
            + "{\"id\":\"b5\",\"kind\":\"text\",\"channel\":\"answer\",\"text\":\"最终答案。\"}"
            + "]}";

    @Test
    @DisplayName("只保留 answer 文本块，工具/过程/思考全部裁掉")
    void keepsAnswerTextOnly() {
        Map<String, Object> out = TurnBlocks.answerOnly(FULL);

        assertThat(out).isNotNull();
        assertThat(out.get("v")).isEqualTo(1);
        JSONArray blocks = (JSONArray) out.get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getJSONObject(0).getString("kind")).isEqualTo("text");
        assertThat(blocks.getJSONObject(0).getString("channel")).isEqualTo("answer");
        assertThat(blocks.getJSONObject(0).getString("text")).isEqualTo("最终答案。");
        assertThat(TurnBlocks.answerText(out)).isEqualTo("最终答案。");
    }

    @Test
    @DisplayName("已解析的 Map 与字符串两种入口结果一致")
    void mapInputWorks() {
        Map<String, Object> out = TurnBlocks.answerOnly(JSON.parseObject(FULL));

        assertThat(out).isNotNull();
        assertThat(TurnBlocks.answerText(out)).isEqualTo("最终答案。");
    }

    @Test
    @DisplayName("没有 answer 块：不产出可外发结构")
    void noAnswerReturnsNull() {
        String json = "{\"v\":1,\"blocks\":["
                + "{\"id\":\"b1\",\"kind\":\"tool\",\"name\":\"search_knowledge\"},"
                + "{\"id\":\"b2\",\"kind\":\"text\",\"channel\":\"narration\",\"text\":\"说明\"}"
                + "]}";

        assertThat(TurnBlocks.answerOnly(json)).isNull();
        assertThat(TurnBlocks.answerOnlyJson(json)).isNull();
    }

    @Test
    @DisplayName("多段 answer 按序拼接")
    void multipleAnswerBlocksJoinInOrder() {
        Map<String, Object> out = TurnBlocks.answerOnly(Map.of(
                "v", 1,
                "blocks", List.of(
                        Map.of("id", "b1", "kind", "text", "channel", "answer", "text", "上"),
                        Map.of("id", "b2", "kind", "text", "channel", "answer", "text", "下"))));

        assertThat(TurnBlocks.answerText(out)).isEqualTo("上下");
    }

    @Test
    @DisplayName("notice 块不外发：终态那句已在 answer 正文里，出去就是两份")
    void noticeBlocksAreDropped() {
        String json = "{\"v\":1,\"blocks\":["
                + "{\"id\":\"b1\",\"kind\":\"text\",\"channel\":\"answer\",\"text\":\"半截答案\"},"
                + "{\"id\":\"b2\",\"kind\":\"notice\",\"tone\":\"stopped\",\"text\":\"> ⏹ 已停止本轮\"},"
                + "{\"id\":\"b3\",\"kind\":\"text\",\"channel\":\"answer\",\"text\":\"\\n\\n> ⏹ 已停止本轮\"}"
                + "]}";

        Map<String, Object> out = TurnBlocks.answerOnly(json);

        JSONArray blocks = (JSONArray) out.get("blocks");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.getJSONObject(0).getString("id")).isEqualTo("b1");
        assertThat(blocks.getJSONObject(1).getString("id")).isEqualTo("b3");
        assertThat(TurnBlocks.answerText(out)).isEqualTo("半截答案\n\n> ⏹ 已停止本轮");
    }

    @Test
    @DisplayName("非法/空载荷按无块处理")
    void invalidPayloadReturnsNull() {
        assertThat(TurnBlocks.answerOnly(null)).isNull();
        assertThat(TurnBlocks.answerOnly("")).isNull();
        assertThat(TurnBlocks.answerOnly("{bad json")).isNull();
        assertThat(TurnBlocks.answerOnly(Map.of())).isNull();
    }
}
