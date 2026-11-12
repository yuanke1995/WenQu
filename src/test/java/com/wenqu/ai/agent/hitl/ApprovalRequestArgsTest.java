package com.wenqu.ai.agent.hitl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 审批卡 request_args 的两种形状：带理由的包装与存量裸 args，恢复时都要还原对。 */
class ApprovalRequestArgsTest {

    @Test
    @DisplayName("带理由的包装：拆出 args 与 reason 原文（命令里的引号不串位）")
    void unwrapsReason() {
        String stored = "{\"approvalReason\":\"本轮已读取过外部内容（execute）。这条命令会删除或覆盖数据。\","
                + "\"args\":\"{\\\"command\\\":\\\"python3 -m http.server 8765 >/tmp/wq_preview.log\\\"}\"}";
        String[] parts = HitlDecisionService.splitApprovalRequestArgs(stored);
        assertThat(parts[0]).isEqualTo("{\"command\":\"python3 -m http.server 8765 >/tmp/wq_preview.log\"}");
        assertThat(parts[1]).contains("删除或覆盖数据");
    }

    @Test
    @DisplayName("裸 args（旧记录）原样返回，reason 为空；参数本身是 JSON 也不会被误拆")
    void keepsLegacyArgs() {
        String legacy = "{\"command\":\"ls -la\"}";
        String[] parts = HitlDecisionService.splitApprovalRequestArgs(legacy);
        assertThat(parts[0]).isEqualTo(legacy);
        assertThat(parts[1]).isEmpty();

        String[] plain = HitlDecisionService.splitApprovalRequestArgs("path=index.html");
        assertThat(plain[0]).isEqualTo("path=index.html");
        assertThat(plain[1]).isEmpty();
    }

    @Test
    @DisplayName("包装被截断/空值：不吞内容，按 args 原样展示")
    void toleratesBrokenJson() {
        assertThat(HitlDecisionService.splitApprovalRequestArgs("{\"approvalReason\":\"x").length).isEqualTo(2);
        assertThat(HitlDecisionService.splitApprovalRequestArgs(null)[0]).isEmpty();
        assertThat(HitlDecisionService.splitApprovalRequestArgs("{}")).containsExactly("{}", "");
    }
}
