package com.wenqu.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史对话「回溯」的纯函数部分：事件摘要截取与工具行整形，不依赖 Spring/数据库。
 *
 * @author yuanke
 */
class SessionEventSearchServiceTest {

    @Test
    @DisplayName("excerpt：短内容原样返回（压缩空白）")
    void excerptShort() {
        assertThat(SessionEventSearchService.excerpt("  跑了   browserOpen  ", "browserOpen"))
                .isEqualTo("跑了 browserOpen");
    }

    @Test
    @DisplayName("excerpt：长内容围绕命中词截取，两端加省略号")
    void excerptCentered() {
        String prefix = "前".repeat(400);
        String suffix = "后".repeat(400);
        String s = SessionEventSearchService.excerpt(prefix + "关键词" + suffix, "关键词");
        assertThat(s).contains("关键词").startsWith("…").endsWith("…");
        assertThat(s.length()).isLessThanOrEqualTo(304);
    }

    @Test
    @DisplayName("toolLine：JSON 工具事件展开成「名字 · 状态 · 耗时 · 入参/结果」")
    void toolLineJson() {
        String payload = "{\"name\":\"browserClick\",\"label\":\"点击页面元素\",\"status\":\"done\","
                + "\"elapsedMs\":320,\"args\":\"{\\\"ref\\\":\\\"e12\\\"}\",\"result\":\"ok\"}";
        String line = SessionEventSearchService.toolLine(payload);
        assertThat(line)
                .contains("点击页面元素")
                .contains("browserClick")
                .contains("done")
                .contains("320ms")
                .contains("入参=")
                .contains("结果=");
    }

    @Test
    @DisplayName("toolLine：载荷不是 JSON 时退化为摘要，不抛")
    void toolLineNonJson() {
        assertThat(SessionEventSearchService.toolLine("用户停止本轮")).isEqualTo("用户停止本轮");
    }
}
