package com.wenqu.ai.agent.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插话收据台账：按 <b>turnId</b> 分桶，不挂轮次把手。
 * <p><b>为什么不能挂把手</b>：目标轮的续跑轮（{@code goal:…}）不登记 {@link TurnHandle}，而插话照样能送进
 * 它那一跑。收据挂把手的话，目标轮即使收下了这句，{@code inbox/applied} 时也找不到它——回答时间线里的
 * steer 段与收尾 {@code pendingSteers} 对账会一起丢。
 * <p>turnId 全局唯一（用户轮是随机 UUID，目标轮是 {@code goal:{goalId}:{n}}），所以不需要会话命名空间。
 * 写来自 HTTP 线程、读来自流水线线程；一轮内写少读一次，用 {@link CopyOnWriteArrayList}。
 */
public final class SteerReceipts {

    private SteerReceipts() {
    }

    /**
     * 一条已经送进框架会话的插话。
     *
     * @param receiptId 框架 steer 的收据号（与 {@code inbox/applied} 里那些 commandId 同一个词）
     * @param text      用户原话（交回前端续发时要的就是这句，不带模型侧那句声明）
     * @param messageId 随它落库的那条用户消息 ID：前端补发时复用同一颗气泡，刷新后也不会变两条
     * @param images    随这句插话一起发出去的回显 URL：回答时间线里的那段要跟用户气泡一样能看见图
     * @param attachments 随这句插话一起送出的附件元信息（name/mime/size/fileId）：
     *                    时间线段与用户气泡都要如实显示「这句话还带了哪些材料」
     */
    public record SteerAck(String receiptId, String text, String messageId, List<String> images,
                           List<Map<String, Object>> attachments) {
    }

    /** 同时保留多少轮的收据：正常只有 1~2 轮在飞，超出的都是没走到收尾就断掉的轮 */
    private static final int MAX_TURNS = 512;

    private static final Map<String, CopyOnWriteArrayList<SteerAck>> BY_TURN = new ConcurrentHashMap<>();
    /** 插入顺序，供超限时淘汰最旧的一轮（与 {@code GoalService.abortedTurns} 同一思路） */
    private static final ConcurrentLinkedQueue<String> ORDER = new ConcurrentLinkedQueue<>();

    public static void add(String turnId, SteerAck ack) {
        if (turnId == null || turnId.isBlank() || ack == null) return;
        BY_TURN.computeIfAbsent(turnId, k -> {
            ORDER.add(k);
            return new CopyOnWriteArrayList<>();
        }).add(ack);
        evictOverflow();
    }

    public static void add(String turnId, SteerAck... acks) {
        if (acks == null) return;
        for (SteerAck ack : acks) add(turnId, ack);
    }

    /** 按收据号找回那句插话；收据号本轮唯一，配不上就是没有。 */
    public static SteerAck find(String turnId, String receiptId) {
        if (turnId == null || receiptId == null) return null;
        CopyOnWriteArrayList<SteerAck> list = BY_TURN.get(turnId);
        if (list == null) return null;
        for (SteerAck a : list) {
            if (a != null && receiptId.equals(a.receiptId())) return a;
        }
        return null;
    }

    /**
     * 这一轮收下、但模型<b>没收到</b>的那些插话（收尾时交回前端续发，不许静默丢话）。
     *
     * @param applied 本轮已经应用的收据号（来自 {@code inbox/applied}）
     */
    public static List<SteerAck> unsent(String turnId, Set<String> applied) {
        List<SteerAck> out = new ArrayList<>();
        if (turnId == null) return out;
        CopyOnWriteArrayList<SteerAck> list = BY_TURN.get(turnId);
        if (list == null) return out;
        for (SteerAck ack : list) {
            if (ack == null || ack.receiptId() == null) continue;
            if (applied != null && applied.contains(ack.receiptId())) continue;
            out.add(ack);
        }
        return out;
    }

    /** 这一轮真正收场了：摘掉它的收据（用户轮与目标轮的收尾都走这里）。 */
    public static void clear(String turnId) {
        if (turnId == null || turnId.isBlank()) return;
        BY_TURN.remove(turnId);
        ORDER.remove(turnId);
    }

    /** 只在测试里看总量：确认没有按轮泄漏。 */
    static int turnCount() {
        return BY_TURN.size();
    }

    private static void evictOverflow() {
        while (BY_TURN.size() > MAX_TURNS) {
            String oldest = ORDER.poll();
            if (oldest == null) return;
            BY_TURN.remove(oldest);
        }
    }
}
