package com.wenqu.ai.agent.chat;

// [保留] 问渠轮次台账 / 个性化提示词 —— AgentScope 框架不提供等价能力，框架引擎(agentx)与共享服务仍依赖此文件，勿当作已退役的控制流删除。

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本轮身份把手：turnId + userId + 通道，外加两个跨线程标志（叫停 / 已送进框架会话的插话收据）。
 * <p>P6 刀 0 自 {@code ChatService} 提出顶层（原先是它的私有 record，只有内层状态能碰）。
 * <p><b>它比任何流式结构都早，也必须早</b>：在 {@code chat()} 抢到轮级互斥的那一刻就建好——
 * 计划卡、子智能体编排、检索统计这些事件发生在流状态构造之前，只靠状态表归因会把同一轮的轨迹劈成
 * 「无 turn_id 的孤儿行」与「有 turn_id 的行」两截，回放时拼不回去。
 * <p>登记与摘除都经 {@link TurnRegistry}（单持有者），谁叫停谁置 {@link #stopped()}。
 *
 * @param turnId    本轮唯一 ID（消息行、事件账本、卡片都挂它）
 * @param userId    发起本轮的用户：叫停与裁决都只认本人
 * @param emitter   本轮入口的那条通道（叫停时据此收尾；后台续跑时可能已失效）
 * @param stopped   本轮是否已被叫停：各检查点与守卫据此短路
 * @param steerAcks 本轮已送进框架会话的插话收据。插话由框架在下一次调模型前写进上下文并记一条
 *                  {@code inbox/applied}，收尾时<b>没等到账</b>的那些必须交回前端补发——
 *                  用户说过的话不许静默丢掉。{@code CopyOnWriteArrayList}：写来自 HTTP 线程、
 *                  读来自流水线线程，一轮内写少读一次。
 */
public record TurnHandle(String turnId, String userId, SseEmitter emitter,
                        AtomicBoolean stopped, List<SteerAck> steerAcks) {

    /**
     * 一条已经送进框架会话的插话。
     *
     * @param receiptId 框架 steer 的收据号（与 {@code inbox/applied} 里那些 commandId 同一个词）
     * @param text      用户原话（交回前端续发时要的就是这句，不带模型侧那句声明）
     * @param messageId 随它落库的那条用户消息 ID：前端补发时复用同一颗气泡，刷新后也不会变两条
     */
    public record SteerAck(String receiptId, String text, String messageId) {
    }

    public TurnHandle(String turnId, String userId, SseEmitter emitter) {
        this(turnId, userId, emitter, new AtomicBoolean(false), new CopyOnWriteArrayList<>());
    }
}
