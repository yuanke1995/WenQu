package com.wenqu.ai.agent.chat;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 在跑轮次的三张登记表（P6 刀 0 自 {@code RagService} 的三张 private static Map 提出，单持有者）。
 * <p>表本身是 private 的：外面只能经下面这些方法进出，不再有任何类能绕过方法名往「这一轮」的状态上伸手
 * ——这正是 P6 §一 拦路点要避免的那种跨类共享可变口袋。表随进程生死（重启即清空），不落库。
 * <p>三张表的键各不相同，因为它们的生命周期不同：
 * <ul>
 *   <li>{@link #state(String) 会话→流状态}：从构造起到终态收口，供服务端「停止本轮」按会话定位。
 *       刻意不复用「连接还在不在」那种布尔——要能叫停的轮次里，有一类通道早就断了（断线后台续跑）、
 *       还有一类正挂在等人裁决上，这两种都不是通道状态能表达的；</li>
 *   <li>{@link #handle(String) 会话→身份把手}：比流状态早（抢到轮级互斥就建），全程归因用；</li>
 *   <li>{@link #deadline(SseEmitter) 通道→耗时台账}：与那条通道同生命周期，人在回路等待靠它扣除。</li>
 * </ul>
 *
 * @author yuanke
 */
public final class TurnRegistry {

    private TurnRegistry() {
    }

    private static final ConcurrentMap<String, TurnState> STATES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, TurnHandle> HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<SseEmitter, TurnDeadline> DEADLINES = new ConcurrentHashMap<>();

    // ==================== 会话 → 本轮流状态 ====================

    /** 该会话此刻在跑的那一轮（没有在跑返回 null） */
    public static TurnState state(String sessionId) {
        return sessionId == null ? null : STATES.get(sessionId);
    }

    /** 此刻有没有在跑的轮（进程收尾时先问一句，没有就整块跳过） */
    public static boolean hasRunning() {
        return !STATES.isEmpty();
    }

    /** 此刻所有在跑的轮（快照，进程收尾时按轮截断落库用） */
    public static Collection<TurnState> runningStates() {
        return List.copyOf(STATES.values());
    }

    public static void registerState(String sessionId, TurnState st) {
        STATES.put(sessionId, st);
    }

    /** 按值摘除（防止把后一轮的登记误摘掉） */
    public static void unregisterState(String sessionId, TurnState st) {
        STATES.remove(sessionId, st);
    }

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
