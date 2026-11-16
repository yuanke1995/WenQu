package com.wenqu.ai.agentx.turn;

import com.wenqu.ai.agent.chat.ChatCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-b 评测前文回放的装配口径：前缀套信封前置进模型输入；不带前缀的调用逐字节不变。
 * 空技能/无附件/无 @/# 的 cmd 不触达装配器的任何外部依赖，依赖位传 null 即可纯函数验证。
 */
class TurnPromptAssemblerReplayPrefixTest {

    private final TurnPromptAssembler assembler = new TurnPromptAssembler(null, null, null, null);

    private static ChatCommand cmd(String question, String prefix) {
        return ChatCommand.of("s1", question, "u1")
                .userImages(List.of()).attachments(List.of()).skills(List.of())
                .replayPrefix(prefix)
                .build();
    }

    @Test
    void prefixGoesBeforeQuestionInsideEnvelope() {
        String prefix = "用户：如何设计一个表单\n助手：表单在报表管理界面中制作。\n";
        String out = assembler.userContentWithContext(cmd("有没有相关截图", prefix), null, "");
        int prefixPos = out.indexOf(prefix);
        int questionPos = out.indexOf("有没有相关截图");
        assertTrue(prefixPos >= 0, "前文转录必须进模型输入");
        assertTrue(questionPos > prefixPos, "前文必须排在问题之前");
        assertTrue(out.contains("前文对话回放"), "必须有语境声明段");
        assertTrue(out.indexOf("eval-prefix") < prefixPos, "前文必须套 UntrustedContent 信封");
    }

    @Test
    void noPrefixKeepsLegacyOutputByteForByte() {
        assertEquals("普通问题", assembler.userContentWithContext(cmd("普通问题", null), null, ""));
        assertEquals("普通问题", assembler.userContentWithContext(cmd("普通问题", "  "), null, ""));
    }
}