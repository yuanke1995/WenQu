package com.wenqu.ai.agentx;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住分工段的两个契约：每条规则只在相关工具真挂上时出现，且绝不提本轮不存在的工具名。
 * <p>第二条尤其要紧——提示词教一件本轮做不到的事，与 {@code dispatchDiscipline} 的口径冲突。
 */
class ToolDisciplineTest {

    @Test
    void saysNothingWhenNothingRelevantIsMounted() {
        assertThat(ToolDiscipline.guidance(Set.of())).isEmpty();
        assertThat(ToolDiscipline.guidance(null)).isEmpty();
        // 只挂桌面族时没有歧义要澄清：唯一那台电脑就是用户的电脑
        assertThat(ToolDiscipline.guidance(Set.of("cuaListApps", "cuaOpenApp"))).isEmpty();
        // 与任何分工对都无关的工具
        assertThat(ToolDiscipline.guidance(Set.of("officeConvert", "browserOpen"))).isEmpty();
    }

    @Test
    void sshWithoutDesktopSaysItCannotOperateTheLocalMachine() {
        String text = ToolDiscipline.guidance(Set.of("ssh_exec", "ssh_list_aliases"));

        assertThat(text).contains("本机").contains("远端");
        assertThat(text).contains("不能打开或操作那台电脑上的软件");
        assertThat(text).doesNotContain("cua_");
    }

    @Test
    void sshWithDesktopPointsToTheDesktopFamily() {
        String text = ToolDiscipline.guidance(
                Set.of("ssh_exec", "ssh_list_aliases", "cuaListApps", "cuaOpenApp"));

        assertThat(text).contains("cua_");
        assertThat(text).contains("不要拿 ssh 别名绕过去");
        assertThat(text).doesNotContain("不能打开或操作那台电脑上的软件");
    }

    @Test
    void lookupRulesOnlyNameToolsThatAreActuallyMounted() {
        String knowledgeOnly = ToolDiscipline.guidance(Set.of("search_knowledge"));
        assertThat(knowledgeOnly).contains("search_knowledge");
        assertThat(knowledgeOnly).doesNotContain("webSearch")
                .doesNotContain("webFetch")
                .doesNotContain("searchConversations")
                .doesNotContain("session_event_search");

        String all = ToolDiscipline.guidance(Set.of(
                "search_knowledge", "webSearch", "webFetch", "searchConversations", "session_event_search"));

        assertThat(all).contains("业务知识与文档用 search_knowledge")
                .contains("时效与外部事实用 webSearch")
                .contains("已有网址要读原文用 webFetch")
                .contains("我之前问过什么")
                .contains("哪一轮跑过什么");
    }

    @Test
    void batchHumanInTheLoopAndDeliveryRulesNeedBothSides() {
        String batch = ToolDiscipline.guidance(Set.of("run_code"));
        assertThat(batch).contains("run_code 只用于循环、批量、多件同类");

        assertThat(ToolDiscipline.guidance(Set.of("askUser"))).doesNotContain("askUser");
        assertThat(ToolDiscipline.guidance(Set.of("writeTodo"))).doesNotContain("writeTodo");
        String human = ToolDiscipline.guidance(Set.of("askUser", "writeTodo"));
        assertThat(human).contains("要用户拍板才能继续用 askUser")
                .contains("用 writeTodo");

        assertThat(ToolDiscipline.guidance(Set.of("presentArtifact"))).doesNotContain("deliver_artifact");
        String delivery = ToolDiscipline.guidance(Set.of("presentArtifact", "deliver_artifact"));
        assertThat(delivery).contains("presentArtifact").contains("deliver_artifact");
    }

    @Test
    void fullSetHasOneSectionHeaderAndOneBulletPerRule() {
        String text = ToolDiscipline.guidance(Set.of(
                "ssh_exec", "cuaOpenApp", "search_knowledge", "webSearch", "webFetch",
                "searchConversations", "session_event_search", "run_code",
                "askUser", "writeTodo", "presentArtifact", "deliver_artifact"));

        assertThat(text).startsWith("\n\n## 工具分工\n");
        assertThat(text.chars().filter(c -> c == '\n').count()).isEqualTo(8); // 两个前导换行 + 标题行 + 5 条
        assertThat(text).contains("ssh_* 只连远端主机")
                .contains("已有网址要读原文用 webFetch")
                .contains("run_code 只用于循环")
                .contains("deliver_artifact 按路径交付");
    }
}
