package com.wenqu.ai.startup;

import com.wenqu.ai.service.AgentEvalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 智能体评测启动清理：进程重启后，上一进程的 running 运行既没有执行线程也没有取消入口，
 * 不清理会永久卡住「运行中」门禁态与并发闸。启动时把残留 running 置 failed
 * （「服务重启，运行中断」），已完成用例的结果保留。
 * <p>排在 {@code SchemaMigrator}（@Order(0)）之后即可；评测表不存在时清理语句随建表兜底，
 * 失败只 warn 不阻塞启动。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(30)
public class AgentEvalStartupCleaner implements ApplicationRunner {

    private final AgentEvalService agentEvalService;

    public AgentEvalStartupCleaner(AgentEvalService agentEvalService) {
        this.agentEvalService = agentEvalService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            agentEvalService.markInterruptedRuns();
        } catch (Exception e) {
            log.warn("[AGENT-EVAL] 启动清理失败（不阻塞启动）: {}", e.getMessage());
        }
    }
}
