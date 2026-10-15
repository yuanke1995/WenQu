package com.wenqu.ai.agentx;

import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架引擎的上下文容量分类断言。
 * <p>盯住四件事，每条都是这份代码最容易坏的地方：
 * ①分类只按框架自己的消息结构（role + 内容块）判，不靠任何拼装期标记；
 * ②检索结果与其它工具结果必须分开（两者在容量含义上完全两回事）；
 * ③思考内容要能从「历史对话」里摘出来单独成段（deepseek 这族关着思考也回 reasoning）；
 * ④校准后各类之和严格等于网关实测 prompt，且零宽段不出现。
 */
@DisplayName("框架引擎：上下文容量分类（按 AgentScope 消息结构）")
class FrameworkCtxPartsTest {

    private static TextBlock text(String s) {
        return TextBlock.builder().text(s).build();
    }

    private static ThinkingBlock thinking(String s) {
        return ThinkingBlock.builder().thinking(s).build();
    }

    private static ToolResultBlock toolResult(String name, String out) {
        return new ToolResultBlock("id-" + name, name, List.of(text(out)));
    }

    private static Msg assistantWithThinking(String answer, String thought) {
        List<ContentBlock> blocks = new ArrayList<>();
        blocks.add(thinking(thought));
        blocks.add(text(answer));
        return new AssistantMessage(blocks);
    }

    @Test
    void 历史与本轮提问按位置分开且系统提示词单列() {
        List<Msg> msgs = List.of(
                new SystemMessage("你是一个严谨的知识库助手，回答要有依据。"),
                new UserMessage("上一轮问过什么"),
                new AssistantMessage("上一轮的回答"),
                new UserMessage("这一轮的问题"));
        // historyBefore=3 ⇒ 前三条是历史，最后一条是本轮新增
        Map<String, Integer> parts = FrameworkCtxParts.of(msgs, 3, null, 0);

        assertTrue(parts.containsKey("system"), "系统提示词应单列一段");
        assertTrue(parts.containsKey("history"), "上一轮问答应归入历史对话");
        assertTrue(parts.containsKey("input"), "本轮提问应单列");
        // 历史段只含上一轮那两条，不含本轮提问
        assertTrue(parts.get("history") > 0);
        assertFalse(parts.containsKey("retrieval"), "没调工具就不该有检索段");
        assertFalse(parts.containsKey("toolOutput"), "没调工具就不该有工具结果段");
    }

    @Test
    void 检索结果与其它工具结果分成两段() {
        List<Msg> msgs = List.of(
                new SystemMessage("系统提示词"),
                new UserMessage("问题"),
                new ToolResultMessage(toolResult("search_knowledge", "这是知识库召回的资料片段，可能很长很长")),
                new ToolResultMessage(toolResult("read_file", "文件内容")));
        Map<String, Integer> parts = FrameworkCtxParts.of(msgs, 2, null, 0);

        assertTrue(parts.containsKey("retrieval"), "检索类工具结果应单列「检索资料」");
        assertTrue(parts.containsKey("toolOutput"), "非检索类工具结果应进「工具结果」");
        assertTrue(parts.get("retrieval") > parts.get("toolOutput"),
                "资料片段更长时，检索段应大于工具结果段（分桶方向反了就是 bug）");
    }

    @Test
    void 本轮思考内容单独成段且不进历史() {
        // historyBefore=1 ⇒ 只有索引 0（系统提示词）算历史，索引 1 起都是本轮新增：
        // 用户这轮的问题 + 助手这轮的回答（含 thinking）
        List<Msg> msgs = List.of(
                new SystemMessage("系统提示词"),
                new UserMessage("问题"),
                assistantWithThinking("最终回答", "先想想这个问题该怎么拆"));
        Map<String, Integer> parts = FrameworkCtxParts.of(msgs, 1, null, 0);

        assertTrue(parts.containsKey("thinking"), "本轮思考应单独成段");
        assertFalse(parts.containsKey("history"),
                "本轮助手输出不是历史，不该凭空出现 history 段（生成量已在 output 里，别算两次）");
    }

    @Test
    void 校准后各类之和严格等于网关实测() {
        List<Msg> msgs = List.of(
                new SystemMessage("系统提示词占一段"),
                new UserMessage("历史问题"),
                new AssistantMessage("历史回答"),
                new UserMessage("本轮问题"),
                new ToolResultMessage(toolResult("search_knowledge", "召回的资料")));
        int realPrompt = 12345;
        Map<String, Integer> parts = FrameworkCtxParts.of(msgs, 3, null, realPrompt);

        int sum = parts.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(realPrompt, sum, "校准后各分类之和必须严格等于网关实测 prompt，"
                + "否则容量条的分段与顶部百分比会各说各话");
        assertTrue(parts.values().stream().allMatch(v -> v > 0), "不该出现零宽段（画出来是一条看不见的线）");
    }

    @Test
    void 没有实测用量时如实给估算不硬凑() {
        List<Msg> msgs = List.of(
                new SystemMessage("系统提示词"),
                new UserMessage("问题"));
        Map<String, Integer> parts = FrameworkCtxParts.of(msgs, 2, null, 0);

        assertFalse(parts.isEmpty(), "即使没有网关实测，也该给出估算构成");
        int sum = parts.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(sum > 0);
        // 没实测就没有校准基准，估算值不该恰好等于某个凭空造的数
        assertFalse(parts.containsKey("context"));
    }

    @Test
    void 空转录返回空表而不是异常() {
        assertTrue(FrameworkCtxParts.of(null, 0, null, 1000).isEmpty());
        assertTrue(FrameworkCtxParts.of(List.of(), 0, null, 1000).isEmpty());
    }

    @Test
    void 超出转录长度的起点不把整段当本轮新增() {
        // historyBefore 超出 transcript 长度：整段都该算历史，而不是「全部本轮新增」
        List<Msg> msgs = List.of(
                new SystemMessage("系统提示词"),
                new UserMessage("唯一一轮的问题"));
        Map<String, Integer> parts = FrameworkCtxParts.of(msgs, 99, null, 0);

        assertTrue(parts.containsKey("history"), "越界的起点应被夹住，整段仍算历史");
        assertFalse(parts.containsKey("input"), "越界不该造出本轮提问段");
    }
}
