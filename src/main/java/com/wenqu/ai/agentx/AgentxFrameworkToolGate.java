package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.SandboxMode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 工具执行前的档位闸（全框架化 P1 / 方案 B）。
 *
 * <p><b>为什么需要它</b>：沙箱与文件系统改由框架提供后，那批工具挂在<b>框架自己的 Toolkit</b> 上，
 * 不再经过问渠的 {@code ToolRegistry} 与 {@code ExecutionPolicy#allowsTool} 装配链；而问渠还欠两件
 * 框架没有档位表达的事——目标完成检查轮（只核对不做新工作）与子智能体轮（不许写长期记忆）。
 * 这里在执行前逐个判、拒的当场回一段<b>中文可解释文本</b>（框架权限引擎那句是英文），形态与
 * {@link AgentxStepBudget} 一致：装配期挂 middleware、执行期判。
 *
 * <p><b>判据只有一处</b>：只读档能不能跑，读的是工具自己申报的那份只读标记
 * （{@code @Tool(readOnly=…)} 换算进 {@code ToolBase}；问渠那批由 {@code SideEffect} 经
 * {@link SpringAiToolAdapter} 带过来）。框架权限引擎在只读档也按同一份标记裁
 * （{@code PermissionEngine#checkExploreMode}），本闸只是把拒绝提前一步、并把理由说成人话。
 * <b>这里不许再列工具名字表</b>：抄一份名单就是第三套口径，漏一个名字会把读类能力判成写操作——
 * 2026-10-20 的现场是只读档的智能体五发检索全拒、界面上一点错都没有。
 *
 * <p><b>execute 为什么在只读档不给</b>：容器没有任何按档位收束命令的机制（provisioner 建容器时
 * 不接档位），「能跑命令」就等于「能写文件」，所以框架把它申报成非只读——与
 * {@code SandboxToolProvider} 里那条判据同源。
 *
 * @see AgentxStepBudget 同一 middleware 链上的另一道闸（步数预算）
 */
public final class AgentxFrameworkToolGate implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentxFrameworkToolGate.class);

    /** 拒时的回话。措辞与问渠守卫同族：说清「为什么不能」与「接下来怎么办」，模型才能改道。 */
    private static final String DENY_TEMPLATE =
            "当前是%s，只允许读取与检索类工具，%s 会修改沙盒里的文件或状态，因此本轮不执行。"
                    + "请改用 read_file / grep_files / glob_files / list_files 获取信息，"
                    + "并直接基于已有信息作答；确实需要写入时，请提示用户把权限档位调到可写。";

    /** 子智能体/咨询轮专用拒绝语：那一轮是只读的，别把内部推演写进用户长期记忆。 */
    private static final String DENY_MEMORY_WRITE =
            "当前是子智能体只读轮，不能写长期记忆（memory_save 本轮不执行）。"
                    + "请把结论交回父轮，由父轮统一决定是否记住。";

    /** 普通主轮但档位是只读：同样拒绝写记忆，措辞指向权限档位而不是子轮。 */
    private static final String DENY_MEMORY_READONLY =
            "当前是只读档，不能写长期记忆（memory_save 本轮不执行）。"
                    + "请基于已有信息作答；确实需要记住时，请提示用户把权限档位调到可写。";

    /** 完成检查轮：只允许核对已有产物并提交/记录，不允许做新工作。 */
    private static final String DENY_COMPLETION_CHECK =
            "当前是目标完成检查轮，只允许读取与检索类工具与 goal_submit / goal_note；%s 会做新工作，"
                    + "本轮不执行。请直接核对已有产物后调用 goal_submit，或用 goal_note 的 remaining 写清缺口。";

    private static final Set<String> COMPLETION_CHECK_EXTRA = Set.of("goal_submit", "goal_note");

    private final Set<ExtensionPoint> points = EnumSet.of(ExtensionPoint.ON_ACTING);

    /** 本轮策略；按轮取，故用 Supplier 而不是构造期固定。 */
    private final Supplier<ExecutionPolicy> policySupplier;

    private volatile String turnLabel = "";

    /**
     * 本实例是子智能体/咨询轮：只读记忆（保留注入与 search/get），拒绝 memory_save。
     * <p>为什么闸在 onActing 而不是 build 期摘工具：框架把四把记忆工具打包注册，
     * 没有「只摘写」的开关；而 disableMemoryTools 会连 MEMORY.md 注入一起关掉（只读也读不到）。
     */
    private volatile boolean denyMemoryWrites = false;

    /**
     * 本轮是不是「目标完成检查轮」：只放行读工具 + goal_submit/goal_note。
     * 由引擎在开轮时按 goal.continuation.completionCheck 设置；beginTurn 默认清零。
     */
    private volatile boolean completionCheck = false;

    public void denyMemoryWrites(boolean deny) {
        this.denyMemoryWrites = deny;
    }

    /**
     * 本轮的工具卡记录器（全框架化 P1）。
     *
     * <p>只用于<b>被本闸挡下</b>的那几次调用：放行的工具由引擎读框架 Session Log 的
     * {@code tool/requested}／{@code tool/result} 统一记卡（入参、耗时、成功/失败都在那两帧里）。
     * 这里若再替它们记一遍 start/done，同一次调用就会在落库清单里留下两张卡
     * （一张只有起点、一张只有耗时），界面上「执行了 N 个操作」直接翻倍。
     * 被拒的调用不产生框架的 result 事件，所以只有这一条路能留下「它被拒了」的卡。
     *
     * <p>用 holder 延迟取：闸在 build 期就挂上，而 {@code Recording} 在建实例<b>之后</b>才创建，
     * 直接构造期传入会拿到 null。
     */
    private volatile java.util.function.Supplier<
            AgentscopeChatEngine.Recording> recordingSupplier;

    public AgentxFrameworkToolGate(Supplier<ExecutionPolicy> policySupplier) {
        this.policySupplier = policySupplier;
    }

    /** 挂上本轮的工具卡记录器（每轮开轮时由引擎调用）。 */
    public void bindRecording(AgentscopeChatEngine.Recording recording) {
        this.recordingSupplier = () -> recording;
    }

    /**
     * 开一轮：挂上可见降级出口。
     *
     * @param turnLabel 本轮标签（用于日志区分是谁被拒了）
     */
    public void beginTurn(String turnLabel) {
        this.turnLabel = turnLabel == null ? "" : turnLabel;
        this.completionCheck = false;
    }

    /** 开一轮时设置：这是不是完成检查轮（只读 + goal_submit/goal_note）。 */
    public void setCompletionCheck(boolean completionCheck) {
        this.completionCheck = completionCheck;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return points;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input == null ? null : input.toolCalls();
        if (calls == null || calls.isEmpty()) return next.apply(input);

        ExecutionPolicy policy = policySupplier == null ? null : policySupplier.get();
        if (policy == null) {
            // 拿不到策略按放行处理会静默透传；但这里是「本轮不设档位」而非「档位判不出」，
            // 与 permitsWritePath 的 fail-closed 不是一回事——无策略时问渠本身也不收束任何族。
            return next.apply(input);
        }

        List<ToolUseBlock> allowed = new ArrayList<>(calls.size());
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : calls) {
            if (permits(policy, agent, call.getName())) {
                allowed.add(call);
            } else {
                denied.add(call);
            }
        }
        if (denied.isEmpty()) {
            return next.apply(input);
        }

        String message;
        if (completionCheck) {
            message = String.format(DENY_COMPLETION_CHECK, denied.get(0).getName());
        } else if (denied.stream().allMatch(c -> "memory_save".equals(c.getName()))) {
            message = denyMemoryWrites ? DENY_MEMORY_WRITE : DENY_MEMORY_READONLY;
        } else {
            message = String.format(DENY_TEMPLATE, policy.sandboxMode().label(), denied.get(0).getName());
        }
        log.info("[agentx] 工具闸拒绝 turn={} completionCheck={} mode={} tools={}",
                turnLabel, completionCheck, policy.sandboxMode().code(),
                denied.stream().map(ToolUseBlock::getName).toList());

        AgentscopeChatEngine.Recording rec = currentRecording();
        if (rec != null) {
            // 被拒的也要有卡：status=error 让界面显示成红色失败，而不是「调了个工具，什么都没发生」。
            // 拒绝理由一并落库（只发帧不落库的话，刷新后这张红叉卡就说不清为什么红）
            for (ToolUseBlock call : denied) {
                rec.toolCall(call.getName(), "error", argsOf(call), 0L, System.currentTimeMillis(), 0L, message);
            }
        }

        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        String replyId = state == null ? null : state.getReplyId();
        Flux<AgentEvent> deniedFlux = Flux.defer(() -> {
            List<AgentEvent> events = new ArrayList<>();
            String agentName = state == null ? null : agent.getName();
            for (ToolUseBlock call : denied) {
                ToolResultBlock result = ToolResultBlock.text(message)
                        .withIdAndName(call.getId(), call.getName())
                        .withState(ToolResultState.DENIED);
                if (state != null) {
                    state.contextMutable().add(
                            ToolResultMessageBuilder.buildToolResultMsg(result, call, agentName));
                }
                events.add(new ToolResultStartEvent(replyId, call.getId(), call.getName()));
                events.add(new ToolResultTextDeltaEvent(replyId, call.getId(), call.getName(), message));
                events.add(new ToolResultEndEvent(replyId, call.getId(), call.getName(),
                        ToolResultState.DENIED));
            }
            return Flux.fromIterable(events);
        });
        if (allowed.isEmpty()) return deniedFlux;
        return deniedFlux.concatWith(next.apply(new ActingInput(allowed)));
    }

    /** 入参序列化：与手写侧同口径（工具卡要落库，刷新后据此重建）。 */
    private static String argsOf(ToolUseBlock call) {
        java.util.Map<String, Object> input = call.getInput();
        if (input == null || input.isEmpty()) return null;
        try {
            return com.alibaba.fastjson2.JSON.toJSONString(input);
        } catch (RuntimeException e) {
            // 入参里出现问渠 JSON 表达不了的类型时也不能让整条工具链崩掉，退回摘要即可
            return "{unserializable:" + input.size() + "keys}";
        }
    }

    private AgentscopeChatEngine.Recording currentRecording() {
        java.util.function.Supplier<AgentscopeChatEngine.Recording> s = recordingSupplier;
        return s == null ? null : s.get();
    }

    /**
     * 这一把这个工具能不能跑。
     *
     * <p>只读档的判据是<b>工具自己申报</b>的那份只读标记，问渠不在这里抄名字表：
     * 框架原生工具的 {@code @Tool(readOnly=…)} 本来就带得对（read/grep/glob/list/memory_search/
     * session_search/memory_get 是只读，write_file/edit_file/execute/deliver_artifact 不是），
     * 问渠那批由 {@code SideEffect} 换算后经 {@link SpringAiToolAdapter} 带进框架工具基类。
     * 这里再列一份名单就是第三套口径，而它漏一个名字就把检索工具判成写操作。
     */
    boolean permits(ExecutionPolicy policy, Agent agent, String toolName) {
        if (toolName == null || toolName.isBlank()) return true;
        if (completionCheck) {
            // 检查轮只做核对：只读类 + goal_submit/goal_note。业务工具的 policy.denies 仍然生效。
            return (COMPLETION_CHECK_EXTRA.contains(toolName) || readOnly(agent, toolName))
                    && !policy.denies(toolName);
        }
        if (denyMemoryWrites && "memory_save".equals(toolName)) return false;
        if (policy.denies(toolName)) return false;
        if (policy.sandboxMode() == SandboxMode.READ_ONLY) return readOnly(agent, toolName);
        return true;
    }

    /**
     * 这件工具会不会改到外部世界——读它申报给框架的那份只读标记，取不到就按<b>会改</b>处理。
     * <p>漏判成只读的代价是越权写入，误判成写的代价只是一次可见的拒绝，所以这里 fail-closed
     * 的方向与问答侧的审批登记一致；工具对象都取不到时留一条日志，别让它静默变成「这把工具永远调不动」。
     */
    private boolean readOnly(Agent agent, String toolName) {
        io.agentscope.core.tool.AgentTool tool = agent == null || agent.getToolkit() == null
                ? null : agent.getToolkit().getTool(toolName);
        if (tool == null) {
            log.info("[agentx] 工具取不到只读标记，按会改动外部世界处理: {}", toolName);
            return false;
        }
        // MCP 工具的只读位是远端服务器自己报的（框架读 MCP 注解的 readOnlyHint 填进来）：
        // 一句 readOnlyHint=true 就能同时绕过只读档与「执行前确认」，那是别人填写的安全声明，
        // 不能当问渠的判据用。问渠对这一族的口径一直是「能不能用由档位说，免检由不了它自己说」。
        if (tool instanceof io.agentscope.core.tool.ToolBase base && base.isMcp()) return false;
        return tool.isReadOnly();
    }
}