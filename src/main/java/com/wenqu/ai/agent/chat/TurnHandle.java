package com.wenqu.ai.agent.chat;

// 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件。

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本轮身份把手：turnId + userId + 通道，外加一个跨线程标志（叫停）。
 * <p><b>它比任何流式结构都早，也必须早</b>：在 {@code chat()} 抢到轮级互斥的那一刻就建好——
 * 计划卡、子智能体编排、检索统计这些事件发生在流状态构造之前，只靠状态表归因会把同一轮的轨迹劈成
 * 「无 turn_id 的孤儿行」与「有 turn_id 的行」两截，回放时拼不回去。
 * <p>登记与摘除都经 {@link TurnRegistry}（单持有者），谁叫停谁置 {@link #stopped()}。
 * <p>插话收据<b>不在</b>这里：目标轮没有把手，但插话照样能送进它那一跑，收据按 turnId 存在
 * {@link SteerReceipts}。
 *
 * @param turnId  本轮唯一 ID（消息行、事件账本、卡片都挂它）
 * @param userId  发起本轮的用户：叫停与裁决都只认本人
 * @param emitter 本轮入口的那条通道（叫停时据此收尾；后台续跑时可能已失效）
 * @param stopped 本轮是否已被叫停：各检查点与守卫据此短路
 */
public record TurnHandle(String turnId, String userId, SseEmitter emitter, AtomicBoolean stopped) {

    public TurnHandle(String turnId, String userId, SseEmitter emitter) {
        this(turnId, userId, emitter, new AtomicBoolean(false));
    }
}
