package com.wisesoft.wenqu.thread;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ThreadPoolManager 的优雅停机入口：容器关闭时等待在跑任务收尾（5s 超时后强制中断，见 shutdown()）。
 * ThreadPoolManager 是静态工具类不受 Spring 管理，由本 Bean 代理触发 @PreDestroy。
 */
@Slf4j
@Component
public class ThreadPoolLifecycle {

    @PreDestroy
    void shutdown() {
        ThreadPoolManager.shutdown();
    }
}
