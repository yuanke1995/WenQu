package com.wisesoft.wenqu.agents;

import com.wisesoft.wenqu.knowledge.KnowledgeBaseManager;
import com.wisesoft.wenqu.repositories.KnowledgeBaseRepository;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 把本次 Run 的运行时依赖绑定到工具副本上。
 *
 * <p>参考实现里 {@code ToolRuntime} 由 LangGraph 在执行前注入各工具函数
 * （{@code async def query_kb(..., runtime: ToolRuntime)}）——即运行时依赖由框架按调用补齐。
 * 本工程的 {@link org.springframework.ai.tool.ToolCallback#call(String)} 没有这个注入点，
 * 故由<b>构图方</b>在构图时调用本类：为本次 Run 派生携带依赖的工具副本，
 * 而不是把依赖放在共享实例或线程级静态变量上（并发 Run 会互相串用）。
 *
 * <p>对位关系：
 * <ul>
 *   <li>{@link KnowledgeTools.KnowledgeTool} → {@link KnowledgeTools.ToolRuntime}
 *       （{@code context} / {@code manager} / {@code backend} / {@code repository} 四项，
 *       其中 manager 对位模块级单例 {@code knowledge_base}）；</li>
 *   <li>{@link SkillInstallTool} → 运行上下文（{@code ToolRuntime.context}）；</li>
 *   <li>其余工具（MCP 工具、无运行时依赖的内置工具）原样透传，顺序不变。</li>
 * </ul>
 */
@Service
public class ToolRuntimeBinder {

    private final KnowledgeBaseManager knowledgeBaseManager;
    private final KnowledgeBaseBackend knowledgeBaseBackend;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SkillInstallTool skillInstallTool;

    public ToolRuntimeBinder(
            KnowledgeBaseManager knowledgeBaseManager,
            KnowledgeBaseBackend knowledgeBaseBackend,
            KnowledgeBaseRepository knowledgeBaseRepository,
            SkillInstallTool skillInstallTool) {
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.knowledgeBaseBackend = knowledgeBaseBackend;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.skillInstallTool = skillInstallTool;
    }

    /** 绑定本次 Run 的运行时依赖；返回顺序与入参一致。 */
    public List<Object> bindRuntimeTools(List<?> tools, BaseContext context, List<Interceptor> interceptors) {
        List<Object> bound = new ArrayList<>();
        if (tools == null) {
            return bound;
        }
        KnowledgeTools.ToolRuntime runtime = new KnowledgeTools.ToolRuntime(
                context, knowledgeBaseManager, knowledgeBaseBackend, knowledgeBaseRepository);
        // 文件工具（read_file 等）绑定本 Run 的沙盒 backend；allowlist 由
        // FilesystemMiddleware.getTools() 声明（_AGENT_FS_TOOLS 去 disabled）。
        FilesystemMiddleware fsMiddleware = interceptors == null ? null : interceptors.stream()
                .filter(FilesystemMiddleware.class::isInstance)
                .map(FilesystemMiddleware.class::cast)
                .findFirst()
                .orElse(null);
        for (Object tool : tools) {
            if (tool instanceof KnowledgeTools.KnowledgeTool knowledgeTool) {
                bound.add(knowledgeTool.boundTo(runtime));
                continue;
            }
            if (tool instanceof ToolkitsRegistry.ToolDefinition definition
                    && SkillInstallTool.TOOL_NAME.equals(definition.getName())) {
                bound.add(skillInstallTool.boundTo(context));
                continue;
            }
            if (tool instanceof FilesystemTools.ReadFileTool readFileTool) {
                if (fsMiddleware != null && fsMiddleware.getTools().contains(FilesystemTools.READ_FILE_TOOL)) {
                    bound.add(readFileTool.boundTo(fsMiddleware.getBackend()));
                }
                continue;
            }
            if (tool instanceof BuildinTools.PresentArtifactsTool presentArtifactsTool) {
                // 产物登记需要沙盒做「普通文件」校验（BuildinTools 能力差异 3，2026-09-23 接线）
                bound.add(presentArtifactsTool.boundTo(
                        fsMiddleware == null ? null : fsMiddleware.getBackend(), context));
                continue;
            }
            bound.add(tool);
        }
        return bound;
    }
}
