package com.wisesoft.wenqu.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动状态载体：参考实现 {@code request.app.state.startup_complete} /
 * {@code startup_components}（由 {@code server/utils/lifespan.py} 在应用生命周期里写入）的 Java 等价物。
 *
 * <p>平台差异（必要替换）：FastAPI 的 lifespan 用 {@code app.state} 挂载进程级状态；
 * Spring 侧以单例 Bean 承载，并在 {@link ApplicationReadyEvent}（容器就绪、可接流量）时置位，
 * 与参考实现「startup_complete 表示启动阶段完成」的语义一致。
 *
 * <p>组件登记入口供各依赖在启动自检时写入（{@code register}），键与
 * {@code ReadinessService.componentSnapshot} 约定一致：{@code {名称: {status, required, code?}}}。
 */
@Component
public class StartupState {

    private volatile boolean startupComplete = false;

    private final Map<String, Map<String, Object>> startupComponents = new LinkedHashMap<>();

    /** 标记启动阶段完成（ApplicationReadyEvent：容器已就绪，可接流量）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void markStartupComplete() {
        this.startupComplete = true;
    }

    public boolean isStartupComplete() {
        return startupComplete;
    }

    /** 登记（或覆盖）一个启动自检组件事实。 */
    public synchronized void register(String name, String status, boolean required, String code) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("status", status);
        component.put("required", required);
        if (code != null && !code.isEmpty()) {
            component.put("code", code);
        }
        startupComponents.put(name, component);
    }

    /** 启动自检组件快照（线程安全拷贝）。 */
    public synchronized Map<String, Map<String, Object>> startupComponents() {
        return new LinkedHashMap<>(startupComponents);
    }
}
