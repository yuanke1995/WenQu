package com.wenqu.ai.service;

import com.wenqu.ai.service.HybridRetrievalService.Hit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索门判定核单测：分域门、重排单侧约束、低分全灭时 top-1 保底。
 * 纯函数，不起 Spring 上下文，也不依赖重排服务。
 */
class RetrievalGateTest {

    private static Hit hit(String id, double fusion, Double rerank) {
        return new Hit(id, "doc-" + id, "标题" + id, "正文" + id, List.of(), fusion, 0, "章节", rerank, "kb");
    }

    @Test
    @DisplayName("有命中过门：原样保留，不触发保底")
    void keepsPassingHits() {
        var result = RetrievalGate.apply(
                List.of(hit("a", 0.7, 0.9), hit("b", 0.6, 0.7)),
                0.6, 0.5, true, "top1", 0.05);
        assertThat(result.kept()).hasSize(2);
        assertThat(result.lowConfidence()).isFalse();
        assertThat(result.dropped()).isZero();
    }

    @Test
    @DisplayName("全部低于绝对门：top1 策略只保底最相关的 1 条并标记低置信")
    void rescuesTop1WhenAllBelowGate() {
        // 隐性问答实测：唯一正确块重排分 0.192，绝对门 0.6 会全杀
        var result = RetrievalGate.apply(
                List.of(hit("a", 0.62, 0.192), hit("b", 0.61, 0.150)),
                0.6, 0.5, true, "top1", 0.05);
        assertThat(result.kept()).containsExactly(hit("a", 0.62, 0.192));
        assertThat(result.lowConfidence()).isTrue();
        assertThat(result.dropped()).isEqualTo(1);
    }

    @Test
    @DisplayName("top1 策略关闭时全灭就是空，不保底")
    void noRescueWhenPolicyOff() {
        var result = RetrievalGate.apply(
                List.of(hit("a", 0.62, 0.192)),
                0.6, 0.5, true, "off", 0.05);
        assertThat(result.kept()).isEmpty();
        assertThat(result.lowConfidence()).isFalse();
    }

    @Test
    @DisplayName("保底分低于 floor：视为没有相关内容，不强行塞给模型")
    void noRescueBelowFloor() {
        var result = RetrievalGate.apply(
                List.of(hit("a", 0.62, 0.02)),
                0.6, 0.5, true, "top1", 0.05);
        assertThat(result.kept()).isEmpty();
        assertThat(result.lowConfidence()).isFalse();
    }

    @Test
    @DisplayName("重排没跑成：按融合门处理，不做 top-1 保底")
    void noRescueWhenRerankUnavailable() {
        var result = RetrievalGate.apply(
                List.of(hit("a", 0.4, null)),
                0.6, 0.5, false, "top1", 0.05);
        assertThat(result.kept()).isEmpty();
        assertThat(result.lowConfidence()).isFalse();
    }

    @Test
    @DisplayName("重排执行过：未拿到重排分的尾部候选不借融合门入场")
    void rerankActiveExcludesFusionOnlyTail() {
        var result = RetrievalGate.apply(
                List.of(hit("a", 0.9, 0.8), hit("b", 0.9, null)),
                0.6, 0.5, true, "top1", 0.05);
        assertThat(result.kept()).containsExactly(hit("a", 0.9, 0.8));
    }
}
