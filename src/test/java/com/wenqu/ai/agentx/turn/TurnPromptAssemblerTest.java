package com.wenqu.ai.agentx.turn;

import com.wenqu.ai.dto.ChatRequest;
import com.wenqu.ai.service.UserImageService;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 提示词装配的纯部分：引用标注 JSON 与多模态消息形态。 */
class TurnPromptAssemblerTest {

    private final TurnPromptAssembler assembler = new TurnPromptAssembler(null, null, null, null);

    @Test
    @DisplayName("引用标注：空为 null，非空按原字段序列化")
    void referenceJson() {
        assertThat(TurnPromptAssembler.mentionsJson(null)).isNull();
        assertThat(TurnPromptAssembler.mentionsJson(List.of())).isNull();
        assertThat(TurnPromptAssembler.historyRefsJson(null)).isNull();
        assertThat(TurnPromptAssembler.historyRefsJson(List.of())).isNull();

        ChatRequest.Mention m = new ChatRequest.Mention();
        m.setType("doc");
        m.setId("d1");
        m.setName("需求.docx");
        assertThat(TurnPromptAssembler.mentionsJson(List.of(m)))
                .contains("\"type\":\"doc\"").contains("\"id\":\"d1\"").contains("需求.docx");

        ChatRequest.HistoryRef h = new ChatRequest.HistoryRef();
        h.setMessageId("m1");
        h.setRole("assistant");
        h.setContent("上一轮答案");
        assertThat(TurnPromptAssembler.historyRefsJson(List.of(h)))
                .contains("\"messageId\":\"m1\"").contains("上一轮答案");
    }

    @Test
    @DisplayName("多模态：文本 + 合法 data URL 图片 → 两个内容块")
    void multimodalWithImage() {
        UserImageService.UserImage img = new UserImageService.UserImage(
                "/img/1.png", "data:image/png;base64,AAAA");

        Msg msg = assembler.buildMultimodalMsg("看图", List.of(img));

        assertThat(msg).isNotNull();
        assertThat(msg.getContent()).hasSize(2);
        assertThat(msg.getContent().get(0)).isInstanceOf(TextBlock.class);
        assertThat(msg.getContent().get(1)).isInstanceOf(ImageBlock.class);
    }

    @Test
    @DisplayName("多模态：没有可用图片时退回纯文本（返回 null，由调用方发文本）")
    void multimodalWithoutUsableImage() {
        assertThat(assembler.buildMultimodalMsg("纯文本", List.of())).isNull();
        assertThat(assembler.buildMultimodalMsg("纯文本",
                List.of(new UserImageService.UserImage("/a", null)))).isNull();
        assertThat(assembler.buildMultimodalMsg("纯文本",
                List.of(new UserImageService.UserImage("/b", "not-a-data-url")))).isNull();
    }
}
