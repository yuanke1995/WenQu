package com.wenqu.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring @Scheduled（LDAP 目录定时同步，当前消费者是 {@code DirectorySyncService.tick}）。
 * 新增调度任务需接受「单线程调度器」默认（多任务长跑需自配 TaskScheduler 池）。
 *
 * @author yuanke
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
