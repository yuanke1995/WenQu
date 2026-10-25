package com.wenqu.ai.startup;

import com.wenqu.ai.service.MarketService;
import com.wenqu.ai.service.WorkflowTemplates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 官方工作流模板种入市场（幂等）：{@link WorkflowTemplates} 的内置模板以
 * {@code author_uid=system} 的官方条目出现在市场里——「工作流」页签从此开箱有内容，
 * 模板也第一次有了持久化载体（此前是 /workflow/templates 里的三段硬编码 DSL）。
 * <p>
 * 语义见 {@link MarketService#seedOfficialWorkflow}：不存在则建（上架中）、DSL 变化则刷新指纹；
 * 管理员下架过的不会被重新上架（{@code status} 不动），官方条目不可删除只可下架。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class MarketBuiltinSeeder implements ApplicationRunner {

    private final MarketService marketService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (WorkflowTemplates.Template t : WorkflowTemplates.list()) {
                marketService.seedOfficialWorkflow(t.key(), t.name(), t.description(), t.dsl());
            }
        } catch (Exception e) {
            log.warn("[MARKET] 官方模板种入失败（不阻塞启动）: {}", e.toString());
        }
    }
}
