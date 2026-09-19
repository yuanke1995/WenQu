package com.wisesoft.wenqu.config;

import com.wisesoft.wenqu.agents.SkillService;
import com.wisesoft.wenqu.models.ModelProvider;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.ModelProviderCache;
import com.wisesoft.wenqu.service.ModelProviderService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动期「内置与派生数据同步」，严格按参考实现 {@code server/utils/lifespan.py}
 * 中四个组件的原始顺序执行：
 *
 * <pre>
 * await _initialize_startup_component(app, name="builtin_skills",  required=True, operation=initialize_builtin_skills)
 *
 * async def initialize_default_agents() -> None:
 *     async with pg_manager.get_async_session_context() as session:
 *         repository = AgentRepository(session)
 *         await repository.ensure_default_agent()
 *         await repository.ensure_general_purpose_subagent()
 *         await repository.ensure_web_search_subagent()
 *         await repository.ensure_deep_research_agents()
 * await _initialize_startup_component(app, name="default_agents",  required=True, operation=initialize_default_agents)
 *
 * await _initialize_startup_component(app, name="model_providers", required=True, operation=initialize_model_providers)
 *
 * async def initialize_model_cache() -> None:
 *     async with pg_manager.get_async_session_context() as session:
 *         providers = await get_all_model_providers(session)
 *         model_cache.rebuild(providers)
 * await _initialize_startup_component(app, name="model_cache",     required=True, operation=initialize_model_cache)
 * </pre>
 *
 * <p><b>顺序不可调换</b>：{@code default_agents} 夹在 {@code builtin_skills} 与
 * {@code model_providers} 之间；{@code model_cache} 必须紧跟 {@code model_providers}——
 * 它读的是上一步刚写库的供应商事实，反序会缓存到旧数据（{@code model_cache} 有 5 秒本地 memo
 * 与 Redis TTL，缓存到旧值后不会自愈，只能靠一次写操作触发重建）。
 *
 * <p>四个组件在参考实现里都是 {@code required=True}：失败登记
 * {@code {"status":"error","required":true,"code":异常类名}} 并<b>抛出</b>
 * {@code RequiredStartupComponentError}，当前进程不得继续接流量。本工程照搬该语义：
 * 登记 error 后向上抛，由 Spring Boot 让启动失败（不降级、不吞异常）——
 * 「跑起来但能力残缺」比「启动失败」更难排查，故按参考实现 fail-closed。
 *
 * <p>参数口径：
 * <ul>
 *   <li>{@code init_builtin_skills} 的 {@code created_by} 参考实现取默认值 {@code "system"}，
 *       本工程传 {@code null} 由 {@link SkillService#initBuiltinSkills} 归一到同一默认值。</li>
 *   <li>{@code ensure_default_agent()} 等四个调用在参考实现里<b>不传</b> {@code created_by}
 *       （即 {@code None}），故本工程同样传 {@code null}，不自行填 {@code "system"}。</li>
 * </ul>
 *
 * <p>执行顺序：{@link OptionStartupInitializer}（容器 refresh 期）→ {@code builtin_mcp_servers}
 * （{@link McpStartupInitializer}）→ <b>本类</b>，与参考实现 lifespan 的先后一致。
 */
@Component
public class StartupDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(StartupDataInitializer.class);

    private final SkillService skillService;
    private final AgentRepository agentRepository;
    private final ModelProviderService modelProviderService;
    private final ModelProviderCache modelProviderCache;
    private final StartupState startupState;

    public StartupDataInitializer(
            SkillService skillService,
            AgentRepository agentRepository,
            ModelProviderService modelProviderService,
            ModelProviderCache modelProviderCache,
            StartupState startupState) {
        this.skillService = skillService;
        this.agentRepository = agentRepository;
        this.modelProviderService = modelProviderService;
        this.modelProviderCache = modelProviderCache;
        this.startupState = startupState;
    }

    /** 应用就绪时按 lifespan 顺序同步四类数据（任一失败即阻断启动）。 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public void initializeStartupData() {
        try {
            skillService.initBuiltinSkills(null);
        } catch (Exception exc) {
            failRequiredComponent("builtin_skills", exc);
        }
        startupState.register("builtin_skills", "ok", true, null);

        try {
            initializeDefaultAgents();
        } catch (Exception exc) {
            failRequiredComponent("default_agents", exc);
        }
        startupState.register("default_agents", "ok", true, null);

        try {
            modelProviderService.ensureBuiltinModelProvidersInDb();
        } catch (Exception exc) {
            failRequiredComponent("model_providers", exc);
        }
        startupState.register("model_providers", "ok", true, null);

        try {
            List<ModelProvider> providers = modelProviderService.getAllModelProviders();
            modelProviderCache.rebuild(providers);
            log.info("Model cache rebuilt: {} models loaded", modelProviderCache.getAllSpecs().size());
        } catch (Exception exc) {
            failRequiredComponent("model_cache", exc);
        }
        startupState.register("model_cache", "ok", true, null);
    }

    /**
     * 确保平台至少具有可用的默认 Agent 定义。
     *
     * <p>调用次序与参考实现逐行一致：默认 Agent → 通用子智能体 → 联网搜索子智能体 → 深度研究编排器
     * 及其配套子智能体。前置的默认标记归位（{@code set_default}）是后三者的前提。
     */
    private void initializeDefaultAgents() {
        agentRepository.ensureDefaultAgent(null);
        agentRepository.ensureGeneralPurposeSubagent(null);
        agentRepository.ensureWebSearchSubagent(null);
        agentRepository.ensureDeepResearchAgents(null);
    }

    /** 登记失败事实并向上抛（required=True 的组件失败必须让启动失败）。 */
    private void failRequiredComponent(String component, Exception exc) {
        String code = exc.getClass().getSimpleName();
        startupState.register(component, "error", true, code);
        log.error("Startup component failed: component={}, required=true, type={}", component, code);
        throw new IllegalStateException("Required startup component failed: component=" + component, exc);
    }
}
