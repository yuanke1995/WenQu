package com.wenqu.ai.agent.chat;

// [保留] 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件，勿当 legacy 控制流删除。

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本轮身份把手：turnId + userId + 通道，外加两个跨线程标志（叫停 / 未送达的插话）。
 * <p>P6 刀 0 自 {@code ChatService} 提出顶层（原先是它的私有 record，只有内层状态能碰）。
 * <p><b>它比 {@link TurnState} 早得多也必须早</b>：在 {@code chat()} 抢到轮级互斥的那一刻就建好——
 * 计划卡、子智能体编排、检索统计这些事件发生在流状态构造之前，只靠状态表归因会把同一轮的轨迹劈成
 * 「无 turn_id 的孤儿行」与「有 turn_id 的行」两截，回放时拼不回去。
 * <p>登记与摘除都经 {@link TurnRegistry}（单持有者），谁叫停谁置 {@link #stopped()}。
 *
 * @param turnId   本轮唯一 ID（消息行、事件账本、卡片都挂它）
 * @param userId   发起本轮的用户：叫停与裁决都只认本人
 * @param emitter  本轮入口的那条通道（叫停时据此收尾；后台续跑时可能已失效）
 * @param stopped  本轮是否已被叫停：各检查点与守卫据此短路
 * @param steers   运行中插话的待送达队列（随下一个工具步送达；收尾时未送出的转存回本轮）
 */
public record TurnHandle(String turnId, String userId, SseEmitter emitter,
                        AtomicBoolean stopped, ConcurrentLinkedQueue<String> steers) {

    public TurnHandle(String turnId, String userId, SseEmitter emitter) {
        this(turnId, userId, emitter, new AtomicBoolean(false), new ConcurrentLinkedQueue<>());
    }
}
