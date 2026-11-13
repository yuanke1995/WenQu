package com.wenqu.ai.agent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 插话收据台账：按 turnId 分桶，目标轮（无把手）也要能按同一份查到。 */
class SteerReceiptsTest {

    @Test
    @DisplayName("按 turnId 隔离：两条轮各自的收据互不可见")
    void isolatedByTurn() {
        SteerReceipts.add("turn-a", new SteerReceipts.SteerAck("r1", "先看 A", "m1", List.of("https://img/a.png"), List.of()));
        SteerReceipts.add("turn-b", new SteerReceipts.SteerAck("r2", "先看 B", "m2", List.of(), List.of()));

        assertThat(SteerReceipts.find("turn-a", "r1").text()).isEqualTo("先看 A");
        assertThat(SteerReceipts.find("turn-a", "r2")).isNull();
        assertThat(SteerReceipts.find("turn-b", "r2").messageId()).isEqualTo("m2");

        SteerReceipts.clear("turn-a");
        SteerReceipts.clear("turn-b");
    }

    @Test
    @DisplayName("unsent 排除已应用的收据（收尾补发只交回模型没听到的那些）")
    void unsentSkipsApplied() {
        SteerReceipts.add("turn-c",
                new SteerReceipts.SteerAck("r1", "听到的", "m1", List.of(), List.of()),
                new SteerReceipts.SteerAck("r2", "没听到的", "m2", List.of(), List.of()));

        List<SteerReceipts.SteerAck> left = SteerReceipts.unsent("turn-c", Set.of("r1"));
        assertThat(left).extracting(SteerReceipts.SteerAck::receiptId).containsExactly("r2");

        SteerReceipts.clear("turn-c");
        assertThat(SteerReceipts.unsent("turn-c", Set.of())).isEmpty();
        assertThat(SteerReceipts.find("turn-c", "r1")).isNull();
    }

    @Test
    @DisplayName("轮数上限淘汰最旧的一轮，不无限增长")
    void evictsOldestTurn() {
        for (int i = 0; i < 600; i++) {
            SteerReceipts.add("turn-" + i, new SteerReceipts.SteerAck("r" + i, "x", "m", List.of(), List.of()));
        }
        assertThat(SteerReceipts.turnCount()).isLessThanOrEqualTo(512);
        assertThat(SteerReceipts.find("turn-0", "r0")).isNull();
        assertThat(SteerReceipts.find("turn-599", "r599")).isNotNull();
        for (int i = 0; i < 600; i++) SteerReceipts.clear("turn-" + i);
    }
}
