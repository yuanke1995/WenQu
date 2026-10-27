package com.wenqu.ai.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置。
 * <p>
 * 分页拦截器必须显式注册：缺了它 {@code selectPage} 不改写 SQL（无 LIMIT 全量返回）、
 * {@code Page.total} 恒为 0，所有分页列表（执行日志 / Trace / GraphRAG 三元组）表现为
 * 「表格无限增长、页码不显示」。
 *
 * @author yuanke
 */
@Configuration
public class MybatisPlusConfig {

    private final WenquTenantLineHandler tenantLineHandler;

    public MybatisPlusConfig(WenquTenantLineHandler tenantLineHandler) {
        this.tenantLineHandler = tenantLineHandler;
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 租户闸必须排在分页之前：它改写的是 SQL 本体（WHERE 里加 tenant_id），分页的 COUNT 与 LIMIT
        // 要在改写之后才算数——反过来会数出跨租户的总行数，页码与实际内容对不上。
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(tenantLineHandler));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
