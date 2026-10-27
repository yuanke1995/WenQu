package com.wenqu.ai.startup;

import com.wenqu.ai.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 默认租户登记（Phase 4 · P9）。
 *
 * <p>跑在 {@code SchemaMigrator}（@Order(0)，补 {@code tenant_id} 列与 {@code c_ai_tenant} 表）之后：
 * 单租户部署也要有一行真实租户，否则「无租户」会变成代码里的第二条路径——两条路径迟早漂。
 * 存量行由数据库列默认值回填成 {@code default}，不需要任何搬迁。
 *
 * @author yuanke
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class TenantSeedRunner implements ApplicationRunner {

    private final TenantService tenantService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            tenantService.ensureDefault();
        } catch (Exception e) {
            log.warn("[SETUP] 默认租户登记失败（行级过滤仍按 default 生效，不影响启动）: {}", e.toString());
        }
    }
}
