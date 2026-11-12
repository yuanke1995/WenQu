package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.permission.SandboxMode;
import com.wenqu.ai.util.UntrustedContent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 框架原生文件/命令工具的「外部数据 + 污点」护栏（间接提示词注入防护的另一半）。
 *
 * <p><b>为什么要在 build 之后包一层</b>：{@code read_file / grep_files / execute} 是框架
 * {@code FilesystemTool / ShellExecuteTool} 提供的，结果直接进模型上下文；问渠既改不了框架的
 * 结果拼装，也不能在 {@code ExecutorFilesystem} 里包（{@code edit_file} 内部还要读原文做替换）。
 * 与 {@code alignWriteFileDescription} 同一做法：建完实例后按同名重新注册一层包装，控制流仍走框架。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>出口加信封</b>：读文件、grep、列目录、命令输出的文本统一套 {@link UntrustedContent#toolResult}
 *       并标脏本轮；</li>
 *   <li><b>入口加确认</b>：本轮已读过外部内容时，{@code execute} 里的删除/覆盖命令、完全权限档下
 *       写到工作区外的文件工具，返回 {@code ask} 让人在场确认（{@link AgentxTaint}）。</li>
 * </ol>
 *
 * <p>只包这七把：它们是「内容来自用户电脑」的出口。知识检索/联网/文档几族的信封在各自工具内部，
 * MCP/脚本在 {@link SpringAiToolAdapter} 里，别在这里重复包。
 *
 * @author yuanke
 */
public final class AgentxNativeToolGuard extends ToolBase {

    public enum Kind {
        /** read_file / grep_files / glob_files / list_files：返回文件内容或目录清单 */
        DATA,
        /** execute：命令输出 + 破坏性命令确认 */
        EXEC,
        /** write_file：越界写确认（漂移保护在执行器侧，见 wenqu-agent opWrite） */
        WRITE,
        /** edit_file：越界写确认 */
        EDIT
    }

    private final AgentTool inner;
    private final Kind kind;
    /** 本轮落点（读当前轮 policy/taint）；实例跨轮常驻，不能把 turn 烘进来。 */
    private final AgentxTurnSlot slot;
    /** 工作区根（绝对路径，规范化后）；只用于「绝对路径是否越界」的词法判断。 */
    private final List<String> roots;
    /** 本轮有没有可应答的确认通道（游客分享/API Key 轮没有）：没有就拒绝而不是挂一张没人答的卡。 */
    private final boolean canAsk;

    public AgentxNativeToolGuard(AgentTool inner, Kind kind, AgentxTurnSlot slot, List<String> roots) {
        this(inner, kind, slot, roots, true);
    }

    public AgentxNativeToolGuard(AgentTool inner, Kind kind, AgentxTurnSlot slot, List<String> roots,
                                 boolean canAsk) {
        super(inner.getName(), inner.getDescription(), inner.getParameters(), inner.isReadOnly(),
                concurrencySafe(inner), external(inner), mcpName(inner), stateInjected(inner),
                inner.isReturnDirect());
        this.inner = inner;
        this.kind = kind;
        this.slot = slot;
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.canAsk = canAsk;
    }

    @Override
    public Boolean getStrict() {
        return inner.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return inner.getOutputSchema();
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input,
                                                     PermissionContextState context) {
        Mono<PermissionDecision> base = inner instanceof ToolBase tb
                ? tb.checkPermissions(input, context)
                : Mono.just(PermissionDecision.passthrough(getName()));
        return base.flatMap(decision -> {
            PermissionDecision forced = forcedConfirmation(input);
            if (forced != null && (decision == null
                    || decision.getBehavior() != io.agentscope.core.permission.PermissionBehavior.DENY)) {
                return Mono.just(forced);
            }
            // 内层的原生工具默认是 passthrough：在直接权限路径下它表示「交给后面的规则/默认判」，
            // 而我们的强制确认已经在本层判完，继续 passthrough 会在「带 deny 规则」时被框架的默认
            // ask 分支改成每把工具都弹卡。这里把它收敛成 allow——档位/黑名单仍由 AgentxFrameworkToolGate
            // 与 PermissionEngine 的 deny/explore 分支先判，安全性不变。
            if (decision == null
                    || decision.getBehavior() == io.agentscope.core.permission.PermissionBehavior.PASSTHROUGH) {
                return Mono.just(PermissionDecision.allow("允许"));
            }
            return Mono.just(decision);
        });
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return inner.callAsync(param).map(block -> {
            if (block == null || kind == Kind.WRITE || kind == Kind.EDIT) return block;
            markTainted();
            return envelope(block, sourceOf(param));
        });
    }

    /**
     * 污点触发时的强制确认。返回 null = 不接管（沿用内层决策）。
     * <p>decisionReason 里带 {@code safety}：框架权限引擎对这一类 ask 会保留到最后，
     * 不会被档位的普通 allow 规则吞掉（见 {@code PermissionEngine} 的 ask 分支）。
     */
    private PermissionDecision forcedConfirmation(Map<String, Object> input) {
        AgentxTurnSlot.Turn turn = slot == null ? null : slot.current();
        ExecutionPolicy policy = turn == null ? null : turn.policy();
        AgentxTaint taint = turn == null ? null : turn.taint();
        String note = forcedAskNote(kind, input, policy, taint, roots);
        if (note == null) return null;
        if (kind == Kind.EXEC) {
            String command = input == null ? "" : String.valueOf(input.get("command"));
            return ask(note + "请先确认：\n" + abbreviate(command));
        }
        return ask(note + "请先确认这是你要的结果。");
    }

    /**
     * 强制确认的理由，同时也是审批卡上「为什么问」的说明：null = 本层不需要问人。
     * <p>与 {@link #forcedConfirmation} 共用这一份判据，审批卡不另抄一套规则；
     * 卡片文案里不回显命令正文（正文本来就在卡上的参数块里）。
     *
     * @param policy 本轮权限档位（越界写只在完全权限档才值得确认）
     * @param taint  本轮污点（没读过外部内容就不接管）
     * @param roots  工作区根（越界判断用；空表按任何绝对路径都越界处理）
     */
    static String forcedAskNote(Kind kind, Map<String, Object> input, ExecutionPolicy policy,
                                AgentxTaint taint, List<String> roots) {
        if (taint == null || !taint.tainted()) return null;
        if (kind == Kind.EXEC) {
            String command = input == null ? null : String.valueOf(input.get("command"));
            if (command == null || command.isBlank() || "null".equals(command)) return null;
            boolean destructive = AgentxDestructiveCommands.isDestructive(command);
            // 工作区内修改档下越界写会被执行器/OS 沙箱直接拒绝，问了也批不了；只有完全权限档才值得确认。
            boolean outside = policy != null
                    && policy.sandboxMode() == SandboxMode.DANGER_FULL_ACCESS
                    && AgentxDestructiveCommands.writesOutside(command, roots);
            boolean exfil = AgentxDestructiveCommands.exfiltrates(command);
            if (!destructive && !outside && !exfil) return null;
            String what = destructive ? "删除或覆盖数据"
                    : outside ? "写到工作区之外" : "把本地数据发到外部";
            return "本轮已读取过外部内容（" + sources(taint) + "）。这条命令会" + what
                    + "，直接执行有被资料里藏着的指令利用的风险。";
        }
        if (kind == Kind.WRITE || kind == Kind.EDIT) {
            // 工作区内修改档下越界写本来就会被执行器拒绝；这里只管「完全权限」这一档——
            // 那是唯一放行越界写的地方，必须在读过外部内容后回到人面前确认一次。
            if (policy == null || policy.sandboxMode() != SandboxMode.DANGER_FULL_ACCESS) return null;
            String path = input == null ? null : String.valueOf(input.get("path"));
            if (path == null || path.isBlank() || "null".equals(path)) return null;
            if (!AgentxDestructiveCommands.outside(path, roots)) return null;
            return "本轮已读取过外部内容（" + sources(taint) + "）。这一步要写到工作区之外：" + path + "。";
        }
        return null;
    }

    private PermissionDecision ask(String message) {
        if (!canAsk) {
            return PermissionDecision.deny(message + "\n本轮回合没有可应答的确认通道，按最保守处理不执行。");
        }
        return PermissionDecision.builder()
                .behavior(io.agentscope.core.permission.PermissionBehavior.ASK)
                .message(message)
                .decisionReason("safety: taint-" + kind.name().toLowerCase(Locale.ROOT))
                .build();
    }

    private static String sources(AgentxTaint taint) {
        var s = taint.sources();
        return s.isEmpty() ? "本轮工具输出" : String.join("、", s);
    }

    private void markTainted() {
        AgentxTurnSlot.Turn turn = slot == null ? null : slot.current();
        if (turn != null) turn.taint().mark(getName());
    }

    /** 信封来源：工具名 + 这次读的文件/命令（让模型知道这段数据从哪来）。 */
    private String sourceOf(ToolCallParam param) {
        Map<String, Object> input = param == null ? null : param.getInput();
        Object path = input == null ? null : input.get("path");
        if (path == null && input != null) path = input.get("file_path");
        String p = path == null ? "" : String.valueOf(path);
        return p.isBlank() || "null".equals(p) ? getName() : getName() + " " + p;
    }

    /**
     * 文本块套信封。原生文件/命令工具的输出只有文本；万一出现非文本块原样保留。
     * 多段文本只给第一段带完整声明，后段只套信封，避免同一份结果重复整段规则。
     */
    private ToolResultBlock envelope(ToolResultBlock block, String source) {
        List<ContentBlock> output = block.getOutput();
        if (output == null || output.isEmpty()) return block;
        List<ContentBlock> wrapped = new ArrayList<>(output.size());
        boolean first = true;
        for (ContentBlock c : output) {
            if (!(c instanceof TextBlock t)) {
                wrapped.add(c);
                continue;
            }
            String text = first
                    ? UntrustedContent.toolResult(source, t.getText())
                    : UntrustedContent.envelope(source, t.getText());
            first = false;
            wrapped.add(TextBlock.builder().text(text).build());
        }
        ToolResultBlock out = ToolResultBlock.of(block.getId(), block.getName(), wrapped,
                block.getMetadata() == null ? Map.of() : block.getMetadata());
        if (block.getState() != null) out = out.withState(block.getState());
        if (block.getExecutionDetails() != null) out = out.withExecutionDetails(block.getExecutionDetails());
        return out;
    }

    private static String abbreviate(String command) {
        String c = command.strip();
        return c.length() > 500 ? c.substring(0, 500) + "…" : c;
    }

    private static boolean concurrencySafe(AgentTool tool) {
        return tool instanceof ToolBase b && b.isConcurrencySafe();
    }

    private static boolean external(AgentTool tool) {
        return tool instanceof ToolBase b && b.isExternalTool();
    }

    private static boolean stateInjected(AgentTool tool) {
        return tool instanceof ToolBase b && b.isStateInjected();
    }

    private static String mcpName(AgentTool tool) {
        return tool instanceof ToolBase b && b.isMcp() ? b.getMcpName() : null;
    }
}
