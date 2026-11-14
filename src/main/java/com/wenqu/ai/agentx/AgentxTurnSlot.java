package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.KnowledgeRetrievalTool;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

/**
 * 一条会话的「当前轮」槽位（会话级上下文）。
 *
 * <p><b>为什么必须有它</b>：框架实例（{@link io.agentscope.harness.agent.HarnessAgent}）按
 * {@code (userId, sessionId)} 常驻，工具表在 <b>build 期</b>就定死了。而引用池、通道、轮号、
 * 咨询台账、外溢队列、配置覆盖这些全是<b>一轮</b>的东西——建实例时把它们捕进工具闭包，实例一复用，
 * 第二轮起工具写的就是上一轮的池子：命中注册不进来（引用全被自检判越界剔除）、
 * 咨询事件发到已收口的通道、清单/台账挂到别的轮号上。
 *
 * <p><b>为什么是这个形状</b>：实例只在 build 期建一次，工具可以安全捕获<b>本会话</b>的这个槽位
 * （槽位随会话常驻，不随轮变），但每次工具调用都现读 {@link #current()} ——
 * 引擎在 {@code submit} 之前 {@link #begin(Turn)}，这一轮收口时 {@link #end(String)}。
 * 与 {@link AgentxStepBudget#beginTurn}、{@link AgentxFrameworkToolGate} 的「实例常驻、按轮重置」
 * 是同一套做法，不是另造一层运行时。
 *
 * <p><b>为什么不把状态塞进 {@code AgentState}</b>：那是框架会 checkpoint、会跨进程恢复的会话状态；
 * 引用池里有 SseEmitter 与请求作用域对象，落进 state store 既是泄漏也是脏数据。这里的槽位只活在进程内。
 *
 * <p><b>并发口径</b>：同一条会话同一时刻只有一轮（问答侧有轮级互斥），槽位按会话一个。
 * {@link #end(String)} 只清「自己那一轮」，旧轮的 {@code finally} 不会把新轮清掉；
 * 线程间通过 {@code volatile} 发布，工具在弹性线程上总能看见本轮最新的一份。
 */
public final class AgentxTurnSlot {

    /**
     * 本轮的完整落点。字段全是「一轮内不变」的引用，工具每次调用取一份即可；
     * 不做逐字段 setter，避免出现半轮状态（换轮要么整份换，要么还是上一份）。
     */
    public record Turn(String turnId,
                       WenquSourcePool pool,
                       RunChannel channel,
                       WenquAgentxTools.TurnHooks hooks,
                       ExecutionPolicy policy,
                       KnowledgeRetrievalTool.KbScope scope,
                       AgentxTaint taint) {
    }

    /**
     * 建实例时才能确定、且跨轮稳定的工具面：脚本子调用的原始回调清单与 MCP 工具名。
     * <p>它们随工具表在 build 期固定；每轮只是拿这份清单重新装一个
     * {@code RunCodeTurn}（轮号、通道、台账都是当轮的）。
     */
    public record Surface(List<ToolCallback> rawCallbacks, Set<String> mcpToolNames,
                          boolean runCodeMounted) {
        public static Surface empty() {
            return new Surface(List.of(), Set.of(), false);
        }
    }

    private volatile Turn current;
    private volatile Surface surface = Surface.empty();

    /**
     * 实例装配时是否注入了工作区记忆（.wenqu/MEMORY.md）。build 期定，随实例跨轮稳定：
     * 记忆注入是持久注入面，本轮一开场就应按「已读过可编辑资料」处理，后续破坏性命令要确认。
     */
    private volatile boolean workspaceMemoryTainted = false;

    public void noteWorkspaceMemoryTainted(boolean tainted) {
        this.workspaceMemoryTainted = tainted;
    }

    public boolean workspaceMemoryTainted() {
        return workspaceMemoryTainted;
    }

    /** 本轮档位（chat/workspace）：排障与 tokens 账本用，由引擎在开轮前登记。 */
    private volatile String profile;

    public void noteProfile(String value) {
        this.profile = value;
    }

    public String profile() {
        return profile;
    }

    /** 本轮是否有活跃目标（用户插话轮也会置真）；目标工具只在此时下发，普通问答不常驻。 */
    private volatile boolean goalActive;

    public void noteGoalActive(boolean value) {
        this.goalActive = value;
    }

    public boolean goalActive() {
        return goalActive;
    }

    /**
     * 这个会话实例的工具表（build 期由引擎登记）。
     * <p>为什么放槽位而不是 {@link Surface}：{@code Surface} 在 {@code frameworkTools} 里绑，
     * 那一刻 Toolkit 还没建出来；而工具表与它一样是<b>随实例跨轮稳定</b>的一份，读它的只有一件事——
     * 「脚本子调用要把框架申报只读的那几把也纳进名册」。
     */
    private volatile io.agentscope.core.tool.Toolkit toolkit;

    public void noteToolkit(io.agentscope.core.tool.Toolkit toolkit) {
        this.toolkit = toolkit;
    }

    public io.agentscope.core.tool.Toolkit toolkit() {
        return toolkit;
    }

    /**
     * 「本次会话都允许」的工具名：审批卡上选了会话级放行后登记，之后同一工具不再弹卡。
     * <p>按<b>工具名</b>登记、不放宽到全部工具：放行「执行命令」不该顺带把「写文件」也放过去。
     * 与轮无关（换轮不清）；槽位被回收、进程重启或换一条会话就重新问人——失败方向是更安全的那一侧。
     */
    private final Set<String> sessionApproved = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void allowToolForSession(String toolName) {
        if (toolName != null && !toolName.isBlank()) sessionApproved.add(toolName);
    }

    public boolean toolAllowedForSession(String toolName) {
        return toolName != null && !toolName.isBlank() && sessionApproved.contains(toolName);
    }

    /** 此刻进行中的那一轮；没有（轮间空档/已收口）返回 null。 */
    public Turn current() {
        return current;
    }

    /**
     * 取本轮落点；轮已结束仍被调用说明工具在收口之后才跑到（或装配与执行断线），
     * 这里明确失败而不是回落到上一轮——回落到旧池子正是这套 bug 的原始形态。
     */
    public Turn require() {
        Turn t = current;
        if (t == null) throw new IllegalStateException("本轮已结束：工具调用没有可用的轮上下文");
        return t;
    }

    public boolean active() {
        return current != null;
    }

    public void begin(Turn turn) {
        this.current = turn;
        this.profile = null;
        this.goalActive = false;
    }

    /**
     * 收口。只清匹配的这一轮：同一条会话若已经开了新轮（极端并发下），旧轮的 finally 不许误清。
     */
    public void end(String turnId) {
        Turn t = current;
        if (t != null && (turnId == null || turnId.equals(t.turnId()))) {
            current = null;
        }
    }

    /** 建实例时登记一次工具面；重建实例会重新登记（旧实例随之关闭）。 */
    public void bindSurface(Surface value) {
        this.surface = value == null ? Surface.empty() : value;
    }

    public Surface surface() {
        return surface;
    }
}
