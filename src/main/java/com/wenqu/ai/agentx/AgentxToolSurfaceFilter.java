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
 * 工具面闸：只把本轮真正用得上的工具交给模型。
 *
 * <ul>
 *   <li>没有可咨询子智能体：隐藏 agent 派遣、任务查询、异步等待七把编排工具。普通问答用不到，
 *       常驻只会占 schema、增加误选；</li>
 *   <li>没有活跃目标：隐藏 goal_note/goal_submit 两把目标控制工具。目标续跑轮的 turnId 以 goal:
 *       开头，即使槽位标记没来得及置真也不会误伤。</li>
 * </ul>
 *
 * <p>只过滤模型看到的 schema，不碰注册表与执行体：工具本身仍在 Toolkit 里，只是这一轮看不见。
 * 两组条件都不命中时零拷贝、零影响。
 */
public final class AgentxToolSurfaceFilter implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentxToolSurfaceFilter.class);

    /** 没有子智能体时用不到：派遣、查询、等待异步子任务。 */
    private static final Set<String> SUBAGENT_TOOLS = Set.of(
            "agent_spawn", "agent_send", "agent_list",
            "task_cancel", "task_list", "task_output", "wait_async_results");

    /** 没有活跃目标时用不到：目标进度记录与完成申请。 */
    private static final Set<String> GOAL_TOOLS = Set.of("goal_note", "goal_submit");

    private final AgentxTurnSlot slot;
    private final boolean subagentsEnabled;
    private boolean announcedSubagents;
    private boolean announcedGoal;

    public AgentxToolSurfaceFilter(AgentxTurnSlot slot, boolean subagentsEnabled) {
        this.slot = slot;
        this.subagentsEnabled = subagentsEnabled;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (input == null || input.tools() == null || input.tools().isEmpty()) {
            return next.apply(input);
        }
        boolean hideSubagents = !subagentsEnabled;
        boolean hideGoal = !goalActive();
        if (!hideSubagents && !hideGoal) {
            return next.apply(input);
        }
        List<ToolSchema> filtered = new ArrayList<>(input.tools().size());
        List<String> removedSubagents = new ArrayList<>();
        List<String> removedGoal = new ArrayList<>();
        for (ToolSchema tool : input.tools()) {
            if (tool == null) {
                continue;
            }
            String name = tool.getName();
            if (hideSubagents && SUBAGENT_TOOLS.contains(name)) {
                removedSubagents.add(name);
                continue;
            }
            if (hideGoal && GOAL_TOOLS.contains(name)) {
                removedGoal.add(name);
                continue;
            }
            filtered.add(tool);
        }
        logOnce(removedSubagents, removedGoal);
        int removed = removedSubagents.size() + removedGoal.size();
        if (removed == 0) {
            return next.apply(input);
        }
        return next.apply(new ModelCallInput(
                input.messages(), filtered, input.options(), input.model()));
    }

    /** 目标续跑轮不依赖槽位标记：turnId 以 goal: 开头就是目标轮。 */
    private boolean goalActive() {
        if (slot.goalActive()) {
            return true;
        }
        AgentxTurnSlot.Turn turn = slot.current();
        return turn != null && turn.turnId() != null && turn.turnId().startsWith("goal:");
    }

    private void logOnce(List<String> subagents, List<String> goal) {
        if (!subagents.isEmpty() && !announcedSubagents) {
            announcedSubagents = true;
            log.info("[PROFILE] 无子智能体，隐藏编排工具 {} 把: {}",
                    subagents.size(), String.join(",", subagents));
        }
        if (!goal.isEmpty() && !announcedGoal) {
            announcedGoal = true;
            log.info("[PROFILE] 无活跃目标，隐藏目标工具 {} 把: {}",
                    goal.size(), String.join(",", goal));
        }
    }
}
