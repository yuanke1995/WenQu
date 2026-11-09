package com.wenqu.ai.agentx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 按轮进度：前进看「结果有没有新信息」，不看调用签名是否相同。 */
class AgentxTurnProgressTest {

    @Test
    @DisplayName("同一段内容反复读：涨到阈值后先提醒收尾、再判失控")
    void repeatedReadStalls() {
        AgentxTurnProgress p = new AgentxTurnProgress();
        for (int i = 0; i < 5; i++) {
            p.note("read_file", "{\"path\":\"/a.html\"}", "<html>same</html>", false);
        }
        assertThat(p.staleStreak()).isEqualTo(4);   // 首次算前进
        assertThat(p.stalled()).isFalse();          // 4 < 5

        p.note("read_file", "{\"path\":\"/a.html\"}", "<html>same</html>", false);
        assertThat(p.stalled()).isTrue();
    }

    @Test
    @DisplayName("换一把工具读同一段内容：仍然算没前进（绕不开）")
    void alternatingToolsSameContentStalls() {
        AgentxTurnProgress p = new AgentxTurnProgress();
        p.note("read_file", "{\"path\":\"/a.html\"}", "same-content", false);
        for (int i = 0; i < 5; i++) {
            p.note(i % 2 == 0 ? "grep_files" : "read_file",
                    "{\"n\":" + i + "}", "same-content", false);
        }
        assertThat(p.stalled()).isTrue();
    }

    @Test
    @DisplayName("换参数再失败：不算前进")
    void varyingArgsButFailingStalls() {
        AgentxTurnProgress p = new AgentxTurnProgress();
        for (int i = 0; i < 6; i++) {
            p.note("read_file", "{\"offset\":" + i * 10 + "}", "Error: File not found", true);
        }
        assertThat(p.stalled()).isTrue();
    }

    @Test
    @DisplayName("写到不同内容算前进；同样内容再写一次不算")
    void writesNeedNewContentToProgress() {
        AgentxTurnProgress p = new AgentxTurnProgress();
        p.note("write_file", "{\"path\":\"/a.html\",\"content\":\"v1\"}", "Written to /a.html", false);
        for (int i = 0; i < 5; i++) {
            p.note("write_file", "{\"path\":\"/a.html\",\"content\":\"v1\"}", "Written to /a.html", false);
        }
        assertThat(p.stalled()).isTrue();

        p.note("write_file", "{\"path\":\"/a.html\",\"content\":\"v2\"}", "Written to /a.html", false);
        assertThat(p.staleStreak()).isZero();
        assertThat(p.stalled()).isFalse();
    }

    @Test
    @DisplayName("读到新内容立刻归零；收尾提醒每轮只发一次")
    void newContentResetsAndWrapUpOnce() {
        AgentxTurnProgress p = new AgentxTurnProgress();
        p.note("read_file", "{\"path\":\"/a.html\"}", "one", false);
        p.note("read_file", "{\"path\":\"/a.html\"}", "one", false);
        p.note("read_file", "{\"path\":\"/a.html\"}", "one", false);
        p.note("read_file", "{\"path\":\"/a.html\"}", "one", false);
        assertThat(p.takeWrapUp()).isTrue();
        assertThat(p.takeWrapUp()).isFalse();
        assertThat(p.stalled()).isFalse();

        p.note("read_file", "{\"path\":\"/a.html\"}", "two", false);
        assertThat(p.staleStreak()).isZero();
    }
}
