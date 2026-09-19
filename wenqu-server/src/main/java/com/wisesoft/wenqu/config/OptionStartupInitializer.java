package com.wisesoft.wenqu.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 启动期「管理员配置项定义同步」。
 *
 * <p>对应参考实现 {@code server/utils/lifespan.py} 中的这一段：
 * <pre>
 * from yuxi.config.options import ensure_options_in_db, invalidate_option_cache, system_options
 *
 * async with pg_manager.get_async_session_context() as session:
 *     await ensure_options_in_db(session)
 *     await session.commit()
 * await invalidate_option_cache(system_options.key)
 * </pre>
 *
 * <p><b>为什么必须有这一步</b>：{@code config_options} 表是「代码定义 params、管理员只改 value」的
 * 形态——{@link OptionsService#OPTION_DEFINITIONS} 里的 6 个定义（{@code system_options}、
 * OCR 引擎、远程技能来源策略等）在库中<b>没有行</b>时，任何按 key 读取都会抛
 * {@code IllegalStateException: 配置项不存在: <key>}，表现为 {@code GET /api/system/config} 500。
 * 参考实现靠启动期的 {@code ensure_options_in_db} 幂等补行解决；本工程此前缺这一调用点。
 *
 * <h3>为什么用 {@link PostConstruct} 而不是 {@code ApplicationReadyEvent}</h3>
 * 参考实现里这一步是 lifespan 中的<b>裸调用</b>（不像 {@code builtin_mcp_servers} 那样包在
 * {@code _initialize_startup_component(required=...)} 里），失败会直接让 lifespan 抛异常、
 * 进程拒绝启动。{@code @PostConstruct} 在容器 refresh 期间执行，抛异常即
 * {@code BeanCreationException} → 启动失败，与「非可选、失败即不启动」的语义一致；
 * 同时它天然早于所有 {@code ApplicationReadyEvent} 监听器，保证后续组件读配置时定义已就绪。
 *
 * <p>执行顺序（与参考实现 lifespan 一致）：本类（配置定义）→ {@code builtin_mcp_servers}
 * → {@code builtin_skills} → {@code model_providers}。
 */
@Component
public class OptionStartupInitializer {

    private final OptionsService optionsService;

    public OptionStartupInitializer(OptionsService optionsService) {
        this.optionsService = optionsService;
    }

    /** 幂等同步系统定义，随后失效该配置项的进程缓存（保留管理员已保存的值）。 */
    @PostConstruct
    public void synchronizeOptionDefinitions() {
        optionsService.ensureOptionsInDb();
        optionsService.invalidateOptionCache(OptionsService.SYSTEM_OPTIONS.getKey());
    }
}
