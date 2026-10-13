package com.wenqu.ai.agent.chat;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本轮耗时台账：整轮预算只计<b>机器</b>时间，人在回路的等待从中扣除。
 * <p>P6 刀 0 自 {@code ChatService} 提出顶层（原先是它的私有 static 嵌套类）。
 * <p>背景：askUser 提问卡单卡允许等 chat.askTimeoutMs（默认 10 分钟）、工具审批 120s，而容器级 SSE
 * 超时是<b>从请求开始一路走到头的墙钟</b>且不可续期（Spring 在 async 启动后禁止改 timeout，实测发心跳
 * 也不复位）。于是「用户慢慢答完第二张卡」必然在答题途中被掐断：提问线程被中断、askId 落库改成 TIMEOUT，
 * 用户点提交收到「提问不存在、已回答或已超时」。现 emitter 不设容器超时（见 ChatController），
 * 截断判定改由本台账 + 问答侧的整轮看门狗负责。
 *
 * @author yuanke
 */
public final class TurnDeadline {

    public final long startMs = System.currentTimeMillis();
    public final long budgetMs;
    /** 已结束的人在回路等待累计（提问卡 + 工具审批阻塞时长），不计入预算 */
    public final AtomicLong humanWaitMs = new AtomicLong();
    /** 进行中的等待：计数 + 起点。必须<b>边等边扣</b>——等完再补记的话，看门狗在用户答题那几分钟里
     *  看到的还是整段墙钟，照样把正在作答的轮次掐了（2026-10-07 复测实测到） */
    public final AtomicInteger openWaits = new AtomicInteger();
    public volatile long waitBegin;
    /** 只截断一次：到点后并发 tick 不重复发 warn/complete */
    public final AtomicBoolean fired = new AtomicBoolean();
    public volatile ScheduledFuture<?> watchdog;
    /** 本轮流式状态（{@link TurnState} 构造时挂入）：到点停流与半程正文截断落库用；工作流轮为 null */
    public volatile TurnState state;

    public TurnDeadline(long budgetMs) {
        this.budgetMs = budgetMs;
    }

    public void beginWait() {
        if (openWaits.getAndIncrement() == 0) waitBegin = System.currentTimeMillis();
    }

    public void endWait() {
        if (openWaits.decrementAndGet() <= 0) {
            long b = waitBegin;
            if (b > 0) humanWaitMs.addAndGet(System.currentTimeMillis() - b);
            waitBegin = 0;
        }
    }

    /** 本轮已花掉的机器时间（墙钟扣掉人工等待，进行中的那段也扣） */
    public long machineMs() {
        long w = humanWaitMs.get();
        long b = waitBegin;
        if (b > 0) w += System.currentTimeMillis() - b;
        return System.currentTimeMillis() - startMs - w;
    }

    /** 通道断开时台账要不要留着续跑：本轮正等用户作答，或已转后台续跑且尚未落库收尾 */
    public boolean holdsForDetachedTurn() {
        TurnState s = state;
        if (s == null) return false;
        return s.askWaits.get() > 0 || s.planWaits.get() > 0 || (s.channel.detached && !s.settled());
    }
}
