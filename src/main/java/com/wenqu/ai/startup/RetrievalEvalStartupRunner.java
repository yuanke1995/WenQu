package com.wenqu.ai.startup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.mapper.RetrievalEvalResultMapper;
import com.wenqu.ai.mapper.RetrievalEvalRunMapper;
import com.wenqu.ai.model.RetrievalEvalResult;
import com.wenqu.ai.model.RetrievalEvalRun;
import com.wenqu.ai.service.RetrievalEvalCatalogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检索评估启动收尾：旧 JSON 评估集一次性导入、重启残留 running 运行收口、超期运行清理。
 * <p>必须晚于 SchemaMigrator（@Order(0)）跑，否则新表尚未创建。
 */
@Slf4j
@Component
@Order(20)
public class RetrievalEvalStartupRunner implements ApplicationRunner {

    private final RetrievalEvalCatalogService catalogService;
    private final RetrievalEvalRunMapper runMapper;
    private final RetrievalEvalResultMapper resultMapper;

    public RetrievalEvalStartupRunner(RetrievalEvalCatalogService catalogService,
                                      RetrievalEvalRunMapper runMapper,
                                      RetrievalEvalResultMapper resultMapper) {
        this.catalogService = catalogService;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            catalogService.importLegacyIfPresent();
        } catch (Exception e) {
            log.warn("[Eval] 旧评估集导入失败（不阻塞启动）: {}", e.getMessage());
        }
        closeOrphanRuns();
        purgeExpiredRuns();
    }

    /** 进程重启后残留 running：没有执行器在跑，标失败并保留已完成结果。 */
    private void closeOrphanRuns() {
        try {
            // 只收口 30 分钟前启动的残留行：多副本部署时，另一实例刚启动的运行不能被误杀
            List<RetrievalEvalRun> running = runMapper.selectList(new LambdaQueryWrapper<RetrievalEvalRun>()
                    .eq(RetrievalEvalRun::getStatus, "running")
                    .lt(RetrievalEvalRun::getStartedAt, LocalDateTime.now().minusMinutes(30)));
            for (RetrievalEvalRun run : running) {
                run.setStatus("failed");
                run.setError("服务重启，运行中断（已完成结果保留）");
                run.setFinishedAt(LocalDateTime.now());
                runMapper.updateById(run);
            }
            if (!running.isEmpty()) log.info("[Eval] 重启残留 running 运行已收口 {} 条", running.size());
        } catch (Exception e) {
            log.warn("[Eval] 残留运行收口失败: {}", e.getMessage());
        }
    }

    /** 运行记录保留 90 天（逐题结果随运行一起删），避免评估表无限增长。 */
    private void purgeExpiredRuns() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
            List<RetrievalEvalRun> old = runMapper.selectList(new LambdaQueryWrapper<RetrievalEvalRun>()
                    .lt(RetrievalEvalRun::getCreateTime, cutoff)
                    .last("LIMIT 100"));
            if (old.isEmpty()) return;
            List<String> ids = old.stream().map(RetrievalEvalRun::getId).toList();
            resultMapper.delete(new LambdaQueryWrapper<RetrievalEvalResult>()
                    .in(RetrievalEvalResult::getRunId, ids));
            runMapper.deleteBatchIds(ids);
            log.info("[Eval] 超期运行已清理 {} 条（保留 90 天）", ids.size());
        } catch (Exception e) {
            log.warn("[Eval] 超期运行清理失败: {}", e.getMessage());
        }
    }
}
