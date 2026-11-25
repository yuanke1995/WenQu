package com.wenqu.ai.agentx;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.Map;

import reactor.core.publisher.Mono;

/**
 * 框架原生文件/命令工具的描述改写（从 AgentscopeChatEngine 抽出）。
 * 这里在 build 之后按同名重注册一个「只改 getDescription()」的薄包装，
 * 其余（名字、入参表、只读位、执行本身）全部转给框架原实现——控制流仍归框架。
 *
 * @author yuanke
 */
public final class AgentxToolDescription {

    private AgentxToolDescription() {
    }

    /**
     * 把 {@code write_file} 的 schema 说明改成与问渠实际行为一致的那句。
     *
     * <p>框架 2.1.0-BETA1 的说明仍写着 "Write content to a <b>new</b> file"，而问渠侧把
     * {@code write} 定为「无则新建、有则整体替换」（{@code ProvisionerSandboxFilesystem#write}，
     * 手册 {@code 09-技能与沙盒} 同一口径）。说明与行为不一致不是显示问题：模型是照着说明行事的，
     * "new file" 等于叫它换路径，一份交付会被拆成多段留在沙盒里。
     *
     * <p><b>只能在 build 之后改</b>：框架 {@code HarnessAgent:2530} 先 {@code this.toolkit.copy()}，
     * 再往那个副本上注册文件工具（{@code :2895}）——build 前注册的同名工具会被这次注册盖掉
     * （{@code ToolRegistry#registerTool} 是后写赢）。{@code getToolkit()} 返回的正是那个副本，
     * 此处按同名重新注册即完成替换。
     *
     * <p>{@link DescriptionOverrideTool} 只改 {@code getDescription()}，名字、入参表、只读位、
     * 执行本身全部转给框架原实现——控制流仍归框架，问渠不另造一把写文件的工具。
     * 说明已是我们要的那句时直接跳过：本方法每轮都会走，不挡住就会一层层套娃。
     */
    public static void alignWriteFileDescription(HarnessAgent agent) {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            return;
        }
        AgentTool writeFile = toolkit.getTool(FrameworkToolLabels.FilesystemToolNames.WRITE_FILE);
        if (writeFile == null || WRITE_FILE_DESCRIPTION.equals(writeFile.getDescription())) {
            return;
        }
        toolkit.registerAgentTool(new DescriptionOverrideTool(writeFile, WRITE_FILE_DESCRIPTION));
    }

    /** {@code write_file} 该对模型说的那句（与问渠实现同真）。 */
    private static final String WRITE_FILE_DESCRIPTION =
            "Write content to a file, creating parent directories if needed. "
                    + "If the file already exists its whole content is replaced - use edit_file to change part of it.";

    /**
     * 给框架 {@code execute} 的说明补一句「退出码取整条命令链的最后一条」。
     *
     * <p>工具卡状态按「有没有拿到退出码」定性：非 0 退出码是业务结果（卡片完成态，码与输出
     * 都原样给模型），不再判成工具失败。但 {@code &&} 链会在第一处失败时短路——模型把探测
     * 命令（查端口、查进程、探端点）用 {@code &&} 串起来时，一项连不上后面的项就不执行了，
     * 输出不完整。所以把探测写法教给模型：步骤间用 {@code ;} 或给每步补 {@code || true}。
     *
     * <p>顺带说明工作区根：执行器/Sandbox 都以工作区根为 cwd，模型不必每条命令都前缀
     * {@code cd <workspace> &&}；要换目录用 {@code working_directory} 参数。
     *
     * <p>与 {@link #alignWriteFileDescription} 同一时机（build 之后、护栏之前）：
     * 先换说明，再让 {@link AgentxNativeToolGuard} 包住它；反过来会把护栏包在里面，
     * 下一轮 {@code wrapNative} 就认不出「已经包过」而重复套壳。
     */
    public static void alignExecuteDescription(HarnessAgent agent) {
        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) return;
        AgentTool execute = toolkit.getTool(FrameworkToolLabels.FilesystemToolNames.EXECUTE);
        if (execute == null) return;
        String description = execute.getDescription();
        if (description != null && description.contains(EXECUTE_DESCRIPTION_MARKER)) return;
        toolkit.registerAgentTool(new DescriptionOverrideTool(execute,
                (description == null || description.isBlank() ? "" : description + "\n\n")
                        + EXECUTE_DESCRIPTION_GUIDANCE));
    }

    /** 用来判断说明是否已经补过，避免每轮 build 一层层套娃。 */
    private static final String EXECUTE_DESCRIPTION_MARKER =
            "Exit status is the exit status of the LAST command";

    private static final String EXECUTE_DESCRIPTION_GUIDANCE =
            EXECUTE_DESCRIPTION_MARKER + " in the chain. "
                    + "For probe/diagnostic commands (checking ports, processes, endpoints), separate the steps "
                    + "with `;` or append `|| true` instead of chaining with `&&`: a `&&` chain stops at the "
                    + "first failing step, so the remaining checks never run and the report is incomplete. "
                    + "To start a long-running service (dev server, daemon), launch it fully detached and "
                    + "return immediately: `nohup <cmd> > <log> 2>&1 < /dev/null &` — never run a foreground "
                    + "service command, it blocks until timeout and holds the executor's reply. "
                    + "The launch command exiting 0 only means the process was spawned, NOT that the service "
                    + "is healthy: after starting one, verify with a separate follow-up command, e.g. "
                    + "`sleep 3; curl -sf http://localhost:<port>/<health-path> || tail -n 50 <log>` "
                    + "(fallback to the log tail when the endpoint is not reachable yet), and report the "
                    + "verified state instead of assuming success. "
                    + "Commands already run at the workspace root, so a `cd <workspace>` prefix is unnecessary; "
                    + "use working_directory when a different relative directory is needed.";

    /** 只替换工具说明、其余行为原样委托的包装（见 {@link #alignWriteFileDescription}）。 */
    private record DescriptionOverrideTool(AgentTool delegate, String description) implements AgentTool {

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Map<String, Object> getParameters() {
            return delegate.getParameters();
        }

        @Override
        public Boolean getStrict() {
            return delegate.getStrict();
        }

        @Override
        public Map<String, Object> getOutputSchema() {
            return delegate.getOutputSchema();
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
        }

        @Override
        public boolean isReturnDirect() {
            return delegate.isReturnDirect();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return delegate.callAsync(param);
        }
    }
}
