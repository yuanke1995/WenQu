package com.wenqu.ai.agentx.turn;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * done 终态载荷：键位、条件键与 BrowserCompatible 序列化特性。
 */
class TurnDonePayloadTest {

    @Test
    @DisplayName("完整载荷：固定键齐全，墙钟按 wall+prep 合成")
    void buildsFullPayload() {
        TurnDonePayload.Input in = TurnDonePayload.Input.builder()
                .finalContent("最终答案")
                .messageId("m1")
                .userMessageId("u1")
                .turnId("t1")
                .model("deepseek")
                .agentId("a1")
                .agentName("助手")
                .sources(List.of(Map.of("ref", 1)))
                .finalImages(List.of("/img/1.png"))
                .degradations(List.of(Map.of("code", "x", "msg", "y")))
                .elapsedMs(1200)
                .humanWaitMs(300)
                .turnWallMs(1800)
                .prepMs(300)
                .tokens(Map.of("total", 42))
                .toolCalls(List.of(Map.of("name", "search_knowledge")))
                .blocks(Map.of("v", 1, "blocks", List.of()))
                .changes(Map.of("total", 2))
                .artifacts(List.of(Map.of("filename", "a.html")))
                .related(List.of("下一步"))
                .thinking("想过了")
                .bodyRewritten(true)
                .pendingSteers(List.of(Map.of("text", "补充", "messageId", "s1")))
                .build();

        JSONObject done = TurnDonePayload.build(in);
        // 改写事实由后端一处裁决、随 done 显式宣告——前端判据的唯一来源，键不许缺席
        assertThat(done.getBooleanValue("bodyRewritten")).isTrue();

        assertThat(done.getString("finalContent")).isEqualTo("最终答案");
        assertThat(done.getString("messageId")).isEqualTo("m1");
        assertThat(done.getString("userMessageId")).isEqualTo("u1");
        assertThat(done.getString("turnId")).isEqualTo("t1");
        assertThat(done.getString("model")).isEqualTo("deepseek");
        assertThat(done.getString("agentId")).isEqualTo("a1");
        assertThat(done.getString("agentName")).isEqualTo("助手");
        assertThat(done.getJSONArray("sources")).hasSize(1);
        assertThat(done.getJSONArray("finalImages")).containsExactly("/img/1.png");
        assertThat(done.getJSONArray("degradations")).hasSize(1);
        assertThat(done.getLongValue("elapsedMs")).isEqualTo(1200);
        assertThat(done.getLongValue("humanWaitMs")).isEqualTo(300);
        assertThat(done.getLongValue("turnWallMs")).isEqualTo(1800);
        assertThat(done.getLongValue("prepMs")).isEqualTo(300);
        assertThat(done.getJSONObject("tokens").getIntValue("total")).isEqualTo(42);
        assertThat(done.getJSONArray("toolCalls")).hasSize(1);
        assertThat(done.getJSONObject("blocks")).isNotNull();
        assertThat(done.getJSONObject("changes").getIntValue("total")).isEqualTo(2);
        assertThat(done.getJSONArray("artifacts")).hasSize(1);
        assertThat(done.getJSONArray("related")).containsExactly("下一步");
        assertThat(done.getString("thinking")).isEqualTo("想过了");
        assertThat(done.getJSONArray("pendingSteers")).hasSize(1);
    }

    @Test
    @DisplayName("可选键为空时不出现：blocks/changes/plan/pendingSteers")
    void omitsEmptyOptionalKeys() {
        JSONObject done = TurnDonePayload.build(TurnDonePayload.Input.builder()
                .finalContent("答案")
                .artifacts(List.of())
                .build());

        assertThat(done).doesNotContainKeys("blocks", "changes", "planRecord", "pendingSteers",
                "planSuperseded", "planCancelled");
        assertThat(done.getJSONArray("artifacts")).isEmpty();
        // 未改写的轮也带键（布尔恒下发，前端拿「无键」只认旧服务端）
        assertThat(done).containsKeys("bodyRewritten");
        assertThat(done.getBooleanValue("bodyRewritten")).isFalse();
    }

    @Test
    @DisplayName("计划被取代/拒绝时带对应布尔键与 planRecord")
    void planFlags() {
        JSONObject superseded = TurnDonePayload.build(TurnDonePayload.Input.builder()
                .finalContent("")
                .planRecord(Map.of("status", "superseded"))
                .build());
        assertThat(superseded.getBooleanValue("planSuperseded")).isTrue();
        assertThat(superseded.getJSONObject("planRecord")).isNotNull();

        JSONObject rejected = TurnDonePayload.build(TurnDonePayload.Input.builder()
                .finalContent("")
                .planRecord(Map.of("status", "rejected"))
                .build());
        assertThat(rejected.getBooleanValue("planCancelled")).isTrue();
    }

    @Test
    @DisplayName("extras 只追加分支独有键，核心键一处构造")
    void extrasAppendBranchKeysOnly() {
        JSONObject done = TurnDonePayload.build(TurnDonePayload.Input.builder()
                .finalContent("工作流答案")
                .messageId("m9")
                .turnId("t9")
                .turnWallMs(900)
                .prepMs(100)
                .elapsedMs(900)
                .humanWaitMs(0)
                .blocks(Map.of("v", 1))
                .extras(Map.of("workflowRun", Map.of("runId", "r1", "version", 3),
                        "delegatedAgentName", "子智能体"))
                .build());

        assertThat(done.getJSONObject("workflowRun").getString("runId")).isEqualTo("r1");
        assertThat(done.getString("delegatedAgentName")).isEqualTo("子智能体");
        assertThat(done.getLongValue("turnWallMs")).isEqualTo(900);
        assertThat(done).containsKeys("finalContent", "messageId", "turnId", "tokens",
                "toolCalls", "artifacts", "related", "thinking", "model");
    }

    @Test
    @DisplayName("BrowserCompatible：控制字符转义，不让整包 JSON 非法")
    void escapesControlCharacters() {
        String json = TurnDonePayload.toJson(TurnDonePayload.Input.builder()
                .finalContent("a")
                .thinking("x\u001Ay")
                .build());

        assertThat(json).doesNotContain("\u001A");
        assertThat(json.toLowerCase()).contains("\\u001a");
    }
}
