package com.wenqu.ai.agentx.turn;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定稿正文改写事实的一致性（回归：角标与面板错位、时间线失效这一族 bug 的地基）。
 *
 * <p>这一族 bug 的共同根因是「正文被改写到什么程度」由多个消费方各自现场推导，判据边界一不一致
 * 就错位。{@link BodyReconcile} 把裁决收敛成唯一出处后，本测试锁两件事：
 * ① classify 对五种真实改写形状（无改写 / related 并回 / 中断尾标 / 自检剔标记 / 自检重编号）
 * 的判定与产品语义一致；② 块投影 {@code TurnTranscriptRecorder.toJson} 在每种形状下都产出的
 * 「text 块拼接 == 定稿正文」且非文本块（工具卡）永不因正文改写被丢——这条不成立时，前端拿到的
 * blocks 与 finalContent 就是两套账。
 */
class FinishRewriteConsistencyTest {

    private static final String STREAM = "第一句。[1] 第二句。[2]";

    // ---------- ① 裁决行为表 ----------

    @Test
    @DisplayName("五种改写形状的裁决与产品语义一致")
    void classifyCoversRealShapes() {
        assertThat(BodyReconcile.classify(STREAM, STREAM))
                .isEqualTo(BodyReconcile.Verdict.UNCHANGED);
        // related 并回 / 中断尾标 / 暗截断后缀：都是往流式全文后面接尾巴
        assertThat(BodyReconcile.classify(STREAM, STREAM + "\n\n相关推荐"))
                .isEqualTo(BodyReconcile.Verdict.APPENDED);
        assertThat(BodyReconcile.classify(STREAM, STREAM + "\n\n> ⏹ 已停止本轮，以上为已生成的部分"))
                .isEqualTo(BodyReconcile.Verdict.APPENDED);
        // 引用自检剔掉句尾标记：正文在流中间分叉，前缀区间不再可信
        assertThat(BodyReconcile.classify(STREAM, "第一句。[1] 第二句。"))
                .isEqualTo(BodyReconcile.Verdict.REPLACED);
        // 引用自检重编号：同样分叉
        assertThat(BodyReconcile.classify(STREAM, "第一句。[1] 第二句。[3]"))
                .isEqualTo(BodyReconcile.Verdict.REPLACED);
    }

    @Test
    @DisplayName("空值口径：null 按空串，空流全文+非空正文算追加")
    void classifyNullSemantics() {
        assertThat(BodyReconcile.classify(null, null)).isEqualTo(BodyReconcile.Verdict.UNCHANGED);
        assertThat(BodyReconcile.classify("", "通知文本")).isEqualTo(BodyReconcile.Verdict.APPENDED);
        assertThat(BodyReconcile.classify("正文", "")).isEqualTo(BodyReconcile.Verdict.REPLACED);
    }

    // ---------- ② 块投影在每种形状下与定稿正文对账 ----------

    /** 流式全文拆两段写、中间夹一张工具卡——正文块与工具块的交错是投影的主形状。 */
    private static TurnTranscriptRecorder recorderWithStream() {
        TurnTranscriptRecorder r = new TurnTranscriptRecorder();
        r.textDelta(STREAM.substring(0, 6));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "search_knowledge");
        tool.put("status", "done");
        r.tool(tool);
        r.textDelta(STREAM.substring(6));
        return r;
    }

    private static JSONObject blocksOf(TurnTranscriptRecorder r, String body) {
        String json = r.toJson("t1", body);
        assertThat(json).isNotNull();
        return JSON.parseObject(json);
    }

    private static String joinedText(JSONObject out) {
        StringBuilder sb = new StringBuilder();
        JSONArray blocks = out.getJSONArray("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            JSONObject b = blocks.getJSONObject(i);
            if ("text".equals(b.getString("kind"))) sb.append(b.getString("text"));
        }
        return sb.toString();
    }

    private static int toolBlockCount(JSONObject out) {
        JSONArray blocks = out.getJSONArray("blocks");
        int n = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if ("tool".equals(blocks.getJSONObject(i).getString("kind"))) n++;
        }
        return n;
    }

    @Test
    @DisplayName("无改写：text 块拼接等于定稿正文，工具块原位保留")
    void unchangedKeepsBlocks() {
        JSONObject out = blocksOf(recorderWithStream(), STREAM);
        assertThat(joinedText(out)).isEqualTo(STREAM);
        assertThat(toolBlockCount(out)).isEqualTo(1);
    }

    @Test
    @DisplayName("仅追加尾段：后缀并入新 text 块，拼接等于定稿正文，工具块原位保留")
    void appendedAddsSuffixBlock() {
        String body = STREAM + "\n\n> ⏹ 已停止本轮，以上为已生成的部分";
        JSONObject out = blocksOf(recorderWithStream(), body);
        assertThat(joinedText(out)).isEqualTo(body);
        assertThat(toolBlockCount(out)).isEqualTo(1);
    }

    @Test
    @DisplayName("整体替换：旧 text 块作废、最终正文接末尾，工具块不许被连坐丢掉")
    void replacedSwapsTextKeepsTools() {
        String body = "第一句。[1] 第二句。";
        JSONObject out = blocksOf(recorderWithStream(), body);
        assertThat(joinedText(out)).isEqualTo(body);
        assertThat(toolBlockCount(out)).isEqualTo(1);
    }
}
