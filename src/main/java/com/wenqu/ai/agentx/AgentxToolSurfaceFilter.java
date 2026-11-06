package com.wenqu.ai.agentx;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 异步升级首轮的工具面闸：工作区还在后台装配时，聊天档不向模型暴露文件/命令工具。
 *
 * <p>为什么需要：异步首轮如果给了文件工具，模型会把文件写进宿主 scratch；下一轮切到沙盒，
 * 两套文件系统不是同一个，用户会看到「明明写了文件却不见了」。首轮只答/规划，工作区好了下一轮再动手，
 * 从源头消掉这个交接问题。只过滤模型看到的 schema，不碰注册表与执行体；asyncPending=false 时零影响。
 */
public final class AgentxToolSurfaceFilter implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentxToolSurfaceFilter.class);

    /** 这些工具都落到某个文件系统/命令执行上；异步首轮隐藏。presentArtifact 不在其中（平台产物库，不经 scratch）。 */
    private static final Set<String> FILE_TOOLS = Set.of(
            "read_file", "write_file", "edit_file", "grep_files", "glob_files", "list_files",
            "execute", "run_code", "deliver_artifact");

    private final AgentxTurnSlot slot;
    private volatile boolean announced;

    public AgentxToolSurfaceFilter(AgentxTurnSlot slot) {
        this.slot = slot;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (input == null || input.tools() == null || !slot.asyncPending()) {
            return next.apply(input);
        }
        List<ToolSchema> filtered = new ArrayList<>(input.tools().size());
        List<String> removed = new ArrayList<>();
        for (ToolSchema tool : input.tools()) {
            if (tool != null && FILE_TOOLS.contains(tool.getName())) {
                removed.add(tool.getName());
            } else {
                filtered.add(tool);
            }
        }
        if (removed.isEmpty()) {
            return next.apply(input);
        }
        if (!announced) {
            announced = true;
            log.info("[PROFILE] 异步升级首轮隐藏文件/命令工具 {} 把: {}",
                    removed.size(), String.join(",", removed));
        }
        return next.apply(new ModelCallInput(
                input.messages(), filtered, input.options(), input.model()));
    }
}
