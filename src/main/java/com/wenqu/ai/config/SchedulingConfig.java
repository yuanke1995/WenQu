package com.wenqu.ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring @Scheduled（Phase 1 批次 5：LDAP 目录定时同步）。
 * 本工程此前无任何 @Scheduled 任务（ScheduledJobService 是自管线程），开启后首个消费者
 * 是 {@code DirectorySyncService.tick}；未来新增调度任务需接受「单线程调度器」默认
 * （多任务长跑需自配 TaskScheduler 池）。
 *
 * @author yuanke
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
