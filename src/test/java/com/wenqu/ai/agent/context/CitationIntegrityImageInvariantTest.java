package com.wenqu.ai.agent.context;

import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.service.ConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回答定稿的配图清单不变量（回归：「流式有图、答完没图」）。
 *
 * <p>故障形态：定稿阶段曾有一道按图片描述词面相关性删图的兜底，标记独立成行时判定文本退化成
 * 只剩用户问题，整批正确配图被判错配；正文标记被删、images 清单被清空重编，done.finalImages
 * 再整体覆盖流式期间下发的图。修法不是给那道兜底换判据，而是<b>取消定稿改写清单的可能性</b>：
 * 图片由命中块统一编号，清单是唯一事实来源，定稿校验只读它判编号越界，Outcome 里根本没有清单字段。
 *
 * <p>这三个用例锁住该结构契约；谁再把「裁剪 / 重编后的图片清单」加回定稿结果，这里会先红。
 */
class CitationIntegrityImageInvariantTest {

    private static CitationIntegrityService service() {
        ConfigService cfg = mock(ConfigService.class);
        when(cfg.getBoolean("chat.citationCheckEnabled")).thenReturn(false); // 跳过 LLM 自检，只跑纯字符串检查
        return new CitationIntegrityService(cfg, mock(ChatClient.class), mock(ChatRunRuntime.class),
                mock(com.wenqu.ai.service.ModelRegistryService.class));
    }

    private static CitationIntegrityService.Ctx ctx(List<String> images) {
        return new CitationIntegrityService.Ctx("s1", "u1", "provider/model",
                new ArrayList<Map<String, Object>>(), images,
                new ArrayList<Map<String, String>>(), new HashSet<String>(),
                new HashMap<String, Long>(), System.currentTimeMillis());
    }

    @Test
    @DisplayName("截图标记独立成行时，正文标记与配图清单都原样保留")
    void standaloneImageMarksStay() {
        List<String> images = new ArrayList<>(List.of("/img/a.png", "/img/b.png", "/img/c.png"));
        String answer = "1. 进入报表设计界面\n[图片1]\n2. 拖拽文本组件\n[图片2]\n3. 保存表单\n[图片3]";

        var out = service().check(answer, ctx(images));

        assertThat(out.text()).isEqualTo(answer);
        assertThat(images).containsExactly("/img/a.png", "/img/b.png", "/img/c.png");
    }

    @Test
    @DisplayName("编造编号只从正文删除，清单不因「正文没引用」而缩水")
    void bogusMarksNeverShrinkImageList() {
        List<String> images = new ArrayList<>(List.of("/img/a.png", "/img/b.png"));
        String answer = "步骤一\n[图片1]\n步骤二\n[图片2]\n补充一句。\n[图片9]";

        var out = service().check(answer, ctx(images));

        assertThat(out.text()).contains("[图片1]", "[图片2]").doesNotContain("[图片9]");
        assertThat(images).containsExactly("/img/a.png", "/img/b.png");
    }

    @Test
    @DisplayName("定稿结果不含配图清单：调用方没有第二份清单可覆盖流式下发的图")
    void outcomeHasNoImageList() {
        assertThat(Arrays.stream(CitationIntegrityService.Outcome.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList())
                .containsExactly("text", "sources");
    }
}
