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
 * 框架原生工具的档位闸（全框架化 P1 / 方案 B）。
 *
 * <p><b>为什么需要它</b>：沙箱与文件系统改由框架提供后，那七把工具
 * （{@code read_file}/{@code write_file}/{@code edit_file}/{@code grep_files}/
 * {@code glob_files}/{@code list_files}/{@code execute}）挂在<b>框架自己的 Toolkit</b> 上，
 * 不再经过问渠的 {@code ToolRegistry} 与 {@code ExecutionPolicy#allowsTool} 装配链——
 * 而档位裁剪的语义是「只读档下写类工具<b>不进模型的工具表</b>，不是点了被拒」。
 * 框架工具绕开装配链就等于绕开档位：只读智能体会拿到能写文件的工具。
 *
 * <p><b>为什么放在onActing 而不是装配期</b>：框架工具不进问渠注册中心，
 * 装配期没有它的 {@link com.wenqu.ai.agent.tool.ToolDescriptor} 可判（见
 * {@code FrameworkToolLabels} 的注释：把它们塞进 {@code ToolRegistry} 过不了启动期硬核对）。
 * 所以改为在工具执行前逐个判、拒的当场回一段可解释文本——形态与
 * {@link AgentxStepBudget} 完全一致（它也是装配期挂 middleware、执行期判）。
 *
 * <p><b>execute 为什么必须一起裁掉</b>：容器没有任何按档位收束命令的机制
 * （provisioner 建容器时不接档位），「能跑命令」就等于「能写文件」——
 * 与 {@code SandboxToolProvider} 里那条判据同源，注释也写着同一个理由。
 *
 * @see AgentxStepBudget 同一 middleware 链上的另一道闸（步数预算）
 */
public final class AgentxFrameworkToolGate implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentxFrameworkToolGate.class);

    /**
     * 框架文件工具的副作用分类（只读 vs 会动外部世界）。
     *
     * <p>刻意按<b>工具名</b>而非 descriptor 建表：框架工具没有问渠的 descriptor，
     * 而这张表要与 {@code SideEffect} 保持同一口径——只读的四把不写、其余三把写。
     * 框架若改名，这里取不到值即按<b>写类</b>处理（fail-closed：漏判成只读比误判成写更危险）。
     */
    private static final Map<String, Boolean> FRAMEWORK_TOOL_WRITES =
            // Map.of 最多 10 对；这里超了，用 ofEntries 保序
            Map.ofEntries(
                    Map.entry("read_file", false),
                    Map.entry("grep_files", false),
                    Map.entry("glob_files", false),
                    Map.entry("list_files", false),
                    Map.entry("write_file", true),
                    Map.entry("edit_file", true),
                    Map.entry("execute", true),
                    // 记忆四把（框架 P3 注册）：读类不写、memory_save 会写 MEMORY.md 与日账。
                    // 不登记的话它们会走「未登记副作用=按写类」，只读档下连 memory_search 都被拒。
                    Map.entry("memory_search", false),
                    Map.entry("memory_get", false),
                    Map.entry("session_search", false),
                    Map.entry("memory_save", true));

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
            if (permits(policy, call.getName())) {
                allowed.add(call);
            } else {
                denied.add(call);
            }
        }
        if (denied.isEmpty()) {
            return next.apply(input);
        }

        String message = denied.stream().allMatch(c -> "memory_save".equals(c.getName()))
                ? (denyMemoryWrites ? DENY_MEMORY_WRITE : DENY_MEMORY_READONLY)
                : String.format(DENY_TEMPLATE, policy.sandboxMode().label(), denied.get(0).getName());
        log.info("[agentx] 档位拒绝框架工具 turn={} mode={} tools={}",
                turnLabel, policy.sandboxMode().code(), denied.stream().map(ToolUseBlock::getName).toList());

        AgentscopeChatEngine.Recording rec = currentRecording();
        if (rec != null) {
            // 被拒的也要有卡：status=error 让界面显示成红色失败，而不是「调了个工具，什么都没发生」。
            for (ToolUseBlock call : denied) {
                rec.toolCall(call.getName(), "error", argsOf(call), 0L, System.currentTimeMillis(), 0L);
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
     * 这个框架工具本轮能不能跑。
     *
     * <p>按名黑名单优先（与 {@code ExecutionPolicy#allowsTool} 同序），再过只读档裁剪。
     * 非框架工具（问渠自己的族）一律放行——它们已经过装配链的 {@code allowsTool}，
     * 在这里再判一次就是第二套口径。
     */
    private boolean permits(ExecutionPolicy policy, String toolName) {
        if (toolName == null || toolName.isBlank()) return true;
        if (denyMemoryWrites && "memory_save".equals(toolName)) return false;
        if (policy.denies(toolName)) return false;
        Boolean writes = FRAMEWORK_TOOL_WRITES.get(toolName);
        if (writes == null) {
            // 框架新加的工具：不在表内说明问渠没审过它，按写类处理（只读档下不给），
            // 并留一条日志让它可被发现，而不是静默放行。
            log.info("[agentx] 框架新工具未登记副作用分类，按写类处理: {}", toolName);
            return policy.sandboxMode() != SandboxMode.READ_ONLY;
        }
        if (policy.sandboxMode() == SandboxMode.READ_ONLY) {
            return !writes;
        }
        return true;
    }
}