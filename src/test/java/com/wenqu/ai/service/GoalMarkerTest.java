package com.wenqu.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标收尾标记的判定（对齐 DSH：没有验收标准，完成/受阻只认助手在回答最后一行写下的标记）。
 *
 * <p>这是目标模式唯一的产品收尾信号，误判的代价很实在：把「没做完」判成完成会让任务半途停下，
 * 把「做完了」判成没做完会继续烧轮。所以这里把「只认最后一行」「剥强调符/句读」「受阻原因截取」
 * 三条口径钉死。
 */
class GoalMarkerTest {

    @Test
    @DisplayName("完成标记：只认最后一行，允许加粗/句号/缩进")
    void doneMarkerOnLastLine() {
        assertThat(GoalService.declaresDone("做完了。\n\n【目标完成】")).isTrue();
        assertThat(GoalService.declaresDone("做完了。\n**【目标完成】**")).isTrue();
        assertThat(GoalService.declaresDone("【目标完成】。")).isTrue();
        assertThat(GoalService.declaresDone("  【目标完成】  \n")).isTrue();
        assertThat(GoalService.declaresDone("【 目标完成 】")).isTrue();
        // 标记后同一行接着列交付物也算完成（模型常写「【目标完成】交付物：index.html」）
        assertThat(GoalService.declaresDone("【目标完成】交付物：index.html")).isTrue();
    }

    @Test
    @DisplayName("完成标记：出现在正文中间不算（模型解释「等会儿会写」不是完成）")
    void doneMarkerMidAnswerIsNotDone() {
        assertThat(GoalService.declaresDone("【目标完成】这句话我等会儿再写\n还剩两步")).isFalse();
        assertThat(GoalService.declaresDone("还没做完")).isFalse();
        assertThat(GoalService.declaresDone("")).isFalse();
        assertThat(GoalService.declaresDone(null)).isFalse();
    }

    @Test
    @DisplayName("受阻标记：最后一行以它开头，标记后面的字就是原因")
    void blockedMarkerCarriesReason() {
        assertThat(GoalService.declaresBlocked("先做了一半。\n【目标受阻】缺少生产环境数据库的写权限"))
                .isEqualTo("缺少生产环境数据库的写权限");
        assertThat(GoalService.declaresBlocked("**【目标受阻】：** 外部接口一直 500"))
                .isEqualTo("外部接口一直 500");
        assertThat(GoalService.declaresBlocked("【目标受阻】"))
                .isEqualTo("助手报告目标受阻");
        assertThat(GoalService.declaresBlocked("做完了\n【目标完成】")).isNull();
        assertThat(GoalService.declaresBlocked(null)).isNull();
    }

    @Test
    @DisplayName("收尾标记落库前摘掉：正文里只留交付说明，不留控制信号")
    void markersAreStrippedFromStoredAnswer() {
        assertThat(GoalService.stripMarkers("页面做好了。\n\n【目标完成】"))
                .isEqualTo("页面做好了。");
        assertThat(GoalService.stripMarkers("做了一半。\n【目标完成】交付物：index.html"))
                .isEqualTo("做了一半。");
        assertThat(GoalService.stripMarkers("先停。\n【目标受阻】缺少权限"))
                .isEqualTo("先停。");
        // 正文中间提到标记不是控制信号：一个字都不改
        assertThat(GoalService.stripMarkers("【目标完成】这个标记由我最后写。\n还剩两步"))
                .isEqualTo("【目标完成】这个标记由我最后写。\n还剩两步");
    }

    @Test
    @DisplayName("完成与受阻互斥：最后一行是完成后，不再读成受阻")
    void doneAndBlockedAreExclusive() {
        assertThat(GoalService.declaresDone("【目标受阻】缺权限")).isFalse();
        assertThat(GoalService.declaresBlocked("【目标完成】")).isNull();
    }
}
