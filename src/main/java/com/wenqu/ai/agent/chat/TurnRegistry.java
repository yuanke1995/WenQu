package com.wenqu.ai.agent.chat;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 在跑轮次的两张登记表（单持有者）。
 * <p>表本身是 private 的：外面只能经下面这些方法进出，不会有类绕过方法名往「这一轮」的状态上伸手
 * ——避免跨类共享可变口袋。表随进程生死（重启即清空），不落库。
 * <p>两张表的键各不相同，因为它们的生命周期不同：
 * <ul>
 *   <li>{@link #handle(String) 会话→身份把手}：比一切都早（抢到轮级互斥就建），全程归因用——
 *       计划卡、子智能体编排、检索统计这些事件都早于任何流式结构，只靠它才挂得上同一个 turnId；</li>
 *   <li>{@link #deadline(SseEmitter) 通道→耗时台账}：与那条通道同生命周期，人在回路等待靠它扣除。</li>
 * </ul>
 * <p>会话的流状态由 AgentScope 自己的会话状态持有，问渠侧不另存一份，故这里只需这两张登记表。
 *
 * @author yuanke
 */
public final class TurnRegistry {

    private TurnRegistry() {
    }

    private static final ConcurrentMap<String, TurnHandle> HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<SseEmitter, TurnDeadline> DEADLINES = new ConcurrentHashMap<>();

    // ==================== 会话 → 本轮身份把手 ====================

    public static TurnHandle handle(String sessionId) {
        return sessionId == null ? null : HANDLES.get(sessionId);
    }

    public static void registerHandle(String sessionId, TurnHandle h) {
        HANDLES.put(sessionId, h);
    }

    public static void unregisterHandle(String sessionId, TurnHandle h) {
        HANDLES.remove(sessionId, h);
    }

    /** 无条件摘（流水线 finally 那一条路：把手归本轮所有，摘掉就是它结束了） */
    public static void removeHandle(String sessionId) {
        HANDLES.remove(sessionId);
    }

    // ==================== 通道 → 整轮耗时台账 ====================

    public static TurnDeadline deadline(SseEmitter emitter) {
        return emitter == null ? null : DEADLINES.get(emitter);
    }

    public static void registerDeadline(SseEmitter emitter, TurnDeadline d) {
        DEADLINES.put(emitter, d);
    }

    /** 结束本轮台账：停看门狗并放掉 emitter 引用（终态/断连/收集型通道共用） */
    public static void stopDeadline(SseEmitter emitter) {
        TurnDeadline d = DEADLINES.remove(emitter);
        if (d != null && d.watchdog != null) {
            d.watchdog.cancel(false);
        }
    }

    /**
     * 人在回路等待开始/结束（提问卡与工具审批的阻塞窗口）：这段墙钟不计入整轮机器预算。
     * 未登记台账的通道（收集型）直接忽略。
     */
    public static void beginHumanWait(SseEmitter emitter) {
        TurnDeadline d = DEADLINES.get(emitter);
        if (d != null) d.beginWait();
    }

    public static void endHumanWait(SseEmitter emitter) {
        TurnDeadline d = DEADLINES.get(emitter);
        if (d != null) d.endWait();
    }
}
