package com.wenqu.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作区级轮次互斥：<b>同一工作区同时只允许一轮在跑</b>。
 *
 * <p><b>为什么需要它</b>：实际共用工作目录的工作区（判据 {@code ProjectService#workdirShared}）里，
 * 同一工作区的所有会话共用一个沙盒容器与同一个工作目录（跨会话共享文件是工作区的卖点，
 * 也正是风险来源）：用户在打字的同时，到点的定时任务在写文件、跑着的工作流在改中间产物
 * —— 三方并发写一个目录，产物互相覆盖、脚本读到半截文件。立项卡把这题标成"真客户出现并发需求再补"，
 * 补法就是这把锁。目录各一份的工作区没有这个风险，调用方不取锁。
 *
 * <p><b>口径</b>：与既有的会话轮级互斥（{@code ChatRunRuntime#tryBeginTurn}）同一风格——**快速失败**，
 * 不排队等待：一轮可能跑几分钟，让用户对着输入框等锁是不体面的。抢不到就立刻告诉他"这个工作区里
 * 已有任务在跑"，他自己决定等、停，还是去停那个任务。
 *
 * <p><b>作用域与已知边界</b>：锁在 JVM 内（与会话轮级互斥同一实现口径）；多副本部署时各副本各管各的，
 * 跨副本并发仍不受保护。真要跨副本，需要把锁挪到 Redis/MySQL——但那就要求所有跑沙盒的副本共享
 * 同一份工作目录存储，届时再改不迟（这是刻意的取舍，不是遗漏）。
 *
 * <p>锁表按工作区 id 常驻（工作区数量是个位数），不回收也不构成泄漏。
 */
@Slf4j
@Component
public class ProjectTurnMutex {

    /** 工作区 id → 该工作区当前在跑的轮数（0 或 1；用计数而非布尔，便于自检与将来放宽） */
    private final ConcurrentMap<String, AtomicInteger> active = new ConcurrentHashMap<>();

    /**
     * 尝试占用一个工作区。
     *
     * @return true=占到了（调用方必须在轮次结束时 {@link #end(String)}）；false=该工作区已有轮次在跑
     */
    public boolean tryBegin(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return true;   // 不属任何工作区的会话：不受这把锁约束（会话级互斥已够）
        }
        AtomicInteger counter = active.computeIfAbsent(projectId, k -> new AtomicInteger());
        if (!counter.compareAndSet(0, 1)) {
            return false;
        }
        log.debug("[PROJECT] 占用工作区轮次锁 project={}（同时在跑 {} 轮）", projectId, counter.get());
        return true;
    }

    /** 释放工作区（幂等：多调一次只是把计数按回 0，不会误释放别人的轮次——计数法天然免疫）。 */
    public void end(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        AtomicInteger counter = active.get(projectId);
        if (counter != null) {
            counter.set(0);
            log.debug("[PROJECT] 释放工作区轮次锁 project={}", projectId);
        }
    }

    /** 当前是否有轮次在跑（排障/自检用；不作为判据——判据只看 tryBegin 的返回值） */
    public boolean isBusy(String projectId) {
        AtomicInteger counter = active.get(projectId);
        return counter != null && counter.get() > 0;
    }
}