package com.wisesoft.wenqu.config;

import com.wisesoft.wenqu.agents.McpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动期「内置 MCP 服务器同步」组件。
 *
 * <p>对应参考实现 {@code server/utils/lifespan.py} 中的这一段：
 * <pre>
 * await _initialize_startup_component(
 *     app,
 *     name="builtin_mcp_servers",
 *     required=False,
 *     operation=ensure_builtin_mcp_servers_in_db,
 * )
 * </pre>
 * 语义逐条对齐：执行 {@link McpService#ensureBuiltinMcpServersInDb()}；成功登记
 * {@code {"status": "ok", "required": false}}；失败登记
 * {@code {"status": "error", "required": false, "code": 异常类名}} 并记日志，<b>但不中断启动</b>
 * （{@code required=False}）。异常本身由被调用方负责上抛，本组件负责承接并降级——
 * 与参考实现「入口统一 catch、按 required 决定是否抛」的分工一致。
 *
 * <p>平台差异（必要替换）：参考实现把这些启动组件串在一个 FastAPI lifespan 里；
 * 本工程的 §五 入口（{@code main.py} / {@code lifespan.py}）尚未照搬，故先以独立的
 * {@link ApplicationReadyEvent} 监听器承载该组件——照搬入口时这一块<b>无需再写</b>，
 * 直接沿用本类即可（{@link StartupState#register} 的键名与参考实现一致：
 * {@code builtin_mcp_servers}）。执行顺序用 {@link Order} 提到最高优先级，
 * 使组件状态在 {@link StartupState#markStartupComplete()} 置位<b>之前</b>登记，
 * 与参考实现「先跑组件、末尾才把 startup_complete 置真」的顺序一致。
 *
 * <p>与参考实现同名的另一处调用点是 {@code services/run_worker.py} 的 worker 启动流程；
 * 因 {@code ensure_builtin_mcp_servers_in_db} 幂等，且本工程目前只有 API 进程，
 * 不另开 worker 侧调用点（待 run_worker 照搬时按其原位调用同一方法）。
 */
@Slf4j
@Component
public class McpStartupInitializer {

    /** 启动组件名（与参考实现的 {@code name="builtin_mcp_servers"} 逐字一致）。 */
    private static final String COMPONENT_NAME = "builtin_mcp_servers";

    private final McpService mcpService;

    private final StartupState startupState;

    public McpStartupInitializer(McpService mcpService, StartupState startupState) {
        this.mcpService = mcpService;
        this.startupState = startupState;
    }

    /** 应用就绪时同步内置 MCP 定义（失败只降级记录，不阻断启动）。 */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void initializeBuiltinMcpServers() {
        try {
            mcpService.ensureBuiltinMcpServersInDb();
        } catch (Exception exc) {
            String code = exc.getClass().getSimpleName();
            startupState.register(COMPONENT_NAME, "error", false, code);
            log.error("Startup component failed: component={}, required=false, type={}",
                    COMPONENT_NAME, code);
            return;
        }
        startupState.register(COMPONENT_NAME, "ok", false, null);
    }
}
