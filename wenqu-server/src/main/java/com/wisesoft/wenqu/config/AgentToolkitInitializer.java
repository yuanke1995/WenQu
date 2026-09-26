package com.wisesoft.wenqu.config;

import com.wisesoft.wenqu.agents.BuildinTools;
import com.wisesoft.wenqu.agents.FilesystemTools;
import com.wisesoft.wenqu.agents.KnowledgeTools;
import com.wisesoft.wenqu.agents.SkillInstallTool;
import com.wisesoft.wenqu.agents.ToolkitsRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 启动期「工具注册表填充」组件。
 *
 * <p>对应参考实现 {@code agents/toolkits/__init__.py} 的模块导入副作用：
 * <pre>
 * from . import buildin, debug          # 导入即触发 buildin 各模块的 @tool 装饰器
 * from .kbs import get_common_kb_tools  # kbs 模块同样在导入期注册 7 个知识库工具
 * </pre>
 * Python 靠「装饰器 + 模块导入」完成注册，本工程无该机制，故以容器启动期组件显式承载同一份动作：
 * {@link KnowledgeTools#registerAll()}（7 个）、{@link BuildinTools#registerWebSearchTool()}（按
 * {@code WEB_SEARCH_PROVIDER}/{@code DOUBAO_SEARCH_API_KEY} 决定是否有）、
 * {@link SkillInstallTool#register()}（1 个）。
 *
 * <p><b>不限定进程</b>（与 {@link McpStartupInitializer} / {@link StartupDataInitializer}
 * 不同）：注册表是 JVM 内的静态表，api 与 worker 两个进程各自执行 Run，都必须在构图前填好；
 * 参考实现里这两个进程都 import 了 {@code yuxi.agents.toolkits}，故口径一致。
 *
 * <p>时机选 {@link PostConstruct}（容器 refresh 期）而非 {@code ApplicationReadyEvent}：
 * {@code ToolkitsService} 的工具元数据缓存是<b>懒加载且非空即不再刷新</b>
 * （{@code ensureMetadataLoaded} 的早退），一旦首个请求先跑在注册之前，
 * 该进程的元数据缓存会永久停在空表（即日志里的 {@code Tool service loaded 0 tools}）。
 */
@Slf4j
@Component
public class AgentToolkitInitializer {

    private final SkillInstallTool skillInstallTool;

    public AgentToolkitInitializer(SkillInstallTool skillInstallTool) {
        this.skillInstallTool = skillInstallTool;
    }

    /** 把内置工具（知识库 / 网页搜索 / 安装技能）注册进 {@link ToolkitsRegistry}。 */
    @PostConstruct
    public void registerToolkits() {
        // 静态注册表在同一 JVM 内跨上下文存活（devtools restart / 手动刷新上下文不会清静态状态），
        // 已填充即跳过：重复注册会累积同名实例（工具解析虽会按名去重，元数据表却会出现重复项）。
        if (!ToolkitsRegistry.getAllToolInstances().isEmpty()) {
            log.info("Toolkits registry already initialized ({} instances), skip",
                    ToolkitsRegistry.getAllToolInstances().size());
            return;
        }
        int knowledgeCount = KnowledgeTools.registerAll().size();
        boolean webSearchRegistered = BuildinTools.registerWebSearchTool() != null;
        BuildinTools.registerPresentArtifactsTool();
        skillInstallTool.register();
        FilesystemTools.register();
        log.info(
                "Toolkits registry initialized: knowledge={}, web_search={}, present_artifacts=1, "
                        + "install_skill=1, read_file=1, total={}",
                knowledgeCount, webSearchRegistered, ToolkitsRegistry.getAllToolInstances().size());
    }
}
