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
 * 工具执行前的档位闸。
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
 * <b>这里不许再列工具名字表</b>：抄一份名单就是第三套口径，漏一个名字会把读类能力判成写操作。
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

    /**
     * 按名停用时的回话：工具是被本轮停用名单（平台停用、智能体黑名单、开放接入通道只开问答）挡下的，
     * 与档位无关——此时档位可能本来就是可写的，套 {@link #DENY_TEMPLATE} 会说出
     * 「沙盒目录可写却只许读」这种自相矛盾的话，还引导用户去调一个调了也没用的档位。
     */
    private static final String DENY_DENYLIST =
            "「%s」已被本轮的停用工具名单挡下（平台停用名单、智能体黑名单，或开放接入通道只开放问答），"
                    + "本轮不执行。请改用仍然可用的工具，或直接基于已有信息作答；"
                    + "确实需要这项能力时，请提示用户检查工具停用配置，或改用完整客户端运行该智能体。";

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
     * 本轮的工具卡记录器。
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
        this(policySupplier, java.util.Set.of());
    }

    /**
     * @param guestMcpAllow 游客轮发布者授权的 MCP server 名单（非空=名单内 MCP 工具按「发布者预批」
     *                      在本闸放行）。只读档的判据读不到 MCP 工具的真实性质——{@code readOnly()}
     *                      对 MCP 一律按「会改」处理（远端自报的 readOnlyHint 不可信）——名单是
     *                      发布者对自己个人资产的显式亮出，是这里唯一可认的授权凭据；名单之外的
     *                      MCP 工具照旧按只读档拒绝。
     */
    public AgentxFrameworkToolGate(Supplier<ExecutionPolicy> policySupplier, java.util.Set<String> guestMcpAllow) {
        this.policySupplier = policySupplier;
        this.guestMcpAllow = guestMcpAllow == null ? java.util.Set.of() : guestMcpAllow;
    }

    private final java.util.Set<String> guestMcpAllow;

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

        log.info("[agentx] 工具闸拒绝 turn={} completionCheck={} mode={} tools={}",
                turnLabel, completionCheck, policy.sandboxMode().code(),
                denied.stream().map(ToolUseBlock::getName).toList());

        AgentscopeChatEngine.Recording rec = currentRecording();
        if (rec != null) {
            // 被拒的也要有卡：status=error 让界面显示成红色失败，而不是「调了个工具，什么都没发生」。
            // 拒绝理由一并落库（只发帧不落库的话，刷新后这张红叉卡就说不清为什么红）
            for (ToolUseBlock call : denied) {
                rec.toolCall(call.getName(), "error", argsOf(call), 0L, System.currentTimeMillis(), 0L,
                        denyMessage(policy, call.getName()));
            }
        }

        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        // 被拒的工具就地退出工具表：模型下一步不该再看见它（见 hideFromToolSurface）
        for (ToolUseBlock call : denied) {
            hideFromToolSurface(agent, state, call.getName());
        }
        String replyId = state == null ? null : state.getReplyId();
        Flux<AgentEvent> deniedFlux = Flux.defer(() -> {
            List<AgentEvent> events = new ArrayList<>();
            String agentName = state == null ? null : agent.getName();
            for (ToolUseBlock call : denied) {
                String message = denyMessage(policy, call.getName());
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
        // 游客授权名单内的 MCP 工具按「发布者预批」放行（口径与装配期 guestPreapproved 一致）；
        // 名单外的 MCP 工具照旧落进下面的只读判据（对 MCP 恒为拒绝）
        if (!guestMcpAllow.isEmpty() && com.wenqu.ai.agent.tool.ToolFamily.looksLikeMcpTool(toolName)) {
            return true;
        }
        if (policy.sandboxMode() == SandboxMode.READ_ONLY) return readOnly(agent, toolName);
        return true;
    }

    /**
     * 拒绝回话按拦截原因选文案：档位只读说「把档位调到可写」，按名停用说「查停用配置/换完整客户端」——
     * 两者的出路不同，混用一条模板会让界面出现「沙盒目录可写却只许读」这种自相矛盾的读数。
     */
    private String denyMessage(ExecutionPolicy policy, String toolName) {
        if (completionCheck) return String.format(DENY_COMPLETION_CHECK, toolName);
        if (policy.denies(toolName)) return String.format(DENY_DENYLIST, toolName);
        if ("memory_save".equals(toolName)) return denyMemoryWrites ? DENY_MEMORY_WRITE : DENY_MEMORY_READONLY;
        return String.format(DENY_TEMPLATE, policy.sandboxMode().label(), toolName);
    }

    /**
     * 被拒的工具就地退出模型的工具表。
     *
     * <p>框架生成下发 schema 时只对有组的工具做激活集过滤，<b>无组工具恒可见</b>。
     * 框架自带的沙盒文件/命令工具在 build 之后才进工具表，错过轮首 {@code FrameworkSessions#applyToolSurface}
     * 那一次就没人给它们建组——模型每一步都看得见、调了才被本闸拒掉，白烧调用。
     * 这里按 {@code applyToolSurface} 的同一套组名口径补建一把一组并移出激活集：
     * 框架每次模型调用都现读活状态里的激活集，下一步起的 schema 里就没有这把工具了。
     * 只改活状态、不落库：下一轮开轮的工具面写入会按档位与名单重新裁决并持久化，不该在这里抢跑。
     */
    private void hideFromToolSurface(Agent agent, AgentState state, String toolName) {
        try {
            io.agentscope.core.tool.Toolkit toolkit = agent == null ? null : agent.getToolkit();
            if (toolkit == null || state == null || toolName == null || toolName.isBlank()) return;
            if (toolkit.getTool(toolName) == null) return;
            if (toolkit.getToolGroup(toolName) == null) {
                toolkit.createToolGroup(toolName, "wenqu:" + toolName, true,
                        io.agentscope.core.tool.ToolGroupScope.EXTERNAL);
                toolkit.addToolToGroup(toolName, toolName);
            }
            state.getToolContext().replaceActivatedGroups(java.util.List.of(toolName), java.util.List.of());
        } catch (RuntimeException e) {
            // 自愈失败不影响这次拒绝本身：闸照样挡，下一轮开轮的工具面写入会补上
            log.info("[agentx] 拒绝后工具面自愈未生效 turn={} tool={}: {}", turnLabel, toolName, e.toString());
        }
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