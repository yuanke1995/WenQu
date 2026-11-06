package com.wenqu.ai.service;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 一条消息的写入参数对象。
 * <p>取代 {@code SessionService.appendMessage} 的 14 层 telescoping 重载：21 个位置参数一路传到底，
 * 新增一列就要补一层重载，相邻同类型实参写反编译器也不会拦。对象化后调用方按字段名传值，
 * blocks 也与旧列在同一次写入里落库。
 * <p>刻意不做 null 归一化：null=未提供、空集合=明确没有，两种语义在落库与降级缓存里不同。
 */
@Builder
@Getter
public final class MessageWrite {

    final String sessionId;
    final String role;
    final String content;
    final List<String> images;
    final String sources;
    final String thinking;
    final String retrieved;
    final String artifacts;
    final String toolCalls;
    final String attachments;
    final String tokens;
    final String timeline;
    final String processText;
    final String agentId;
    final String agentName;
    final String model;
    final String related;
    final String mentions;
    final String historyRefs;
    final String plan;
    final String turnId;
    /** 显示块投影（JSON: {v,turnId,blocks:[...]})；与 content/timeline 同一次写入落库 */
    final String blocks;
}
