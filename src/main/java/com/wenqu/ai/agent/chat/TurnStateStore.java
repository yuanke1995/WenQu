package com.wenqu.ai.agent.chat;

/**
 * 会话轮级互斥的存储端口。
 *
 * <p>轮状态不再直接写在进程内 static map：当前实现是单副本 + Redis 租约，
 * 业务态由存储层持有，进程重启后可清理。新增轮状态一律走这个端口，
 * 不允许再往 {@code ChatRunRuntime} 里加无界 static 集合。
 */
public interface TurnStateStore {

    /** 抢占某会话的轮锁；false=已有一轮在跑 */
    boolean tryAcquire(String sessionId);

    /** 释放某会话的轮锁（只释放自己持有的那一份） */
    void release(String sessionId);
}
