package com.wisesoft.wenqu.agents.middlewares;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.wisesoft.wenqu.agents.AgentStateWriteback;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.UserRepository;
import com.wisesoft.wenqu.service.AgentRunService;
import com.wisesoft.wenqu.service.InputMessageService;
import com.wisesoft.wenqu.service.SubagentRunService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

/**
 * 子智能体任务中间件（对应参考实现 {@code agents/middlewares/subagent_task.py} 的
 * {@code YuxiSubAgentMiddleware}，类名去掉参考实现品牌前缀）。
 *
 * <p>职责（与参考实现逐条对位）：
 * <ul>
 *   <li>工厂 {@link #create} 按父智能体上下文加载可见子智能体，无可用项返回 {@code null}
 *       （对应 {@code create_subagent_task_middleware}）。</li>
 *   <li>注入 {@code task} 系统提示段（{@link #TASK_SYSTEM_PROMPT}）。</li>
 *   <li>注册 5 个工具：{@code task} / {@code subagent_start} / {@code subagent_status} /
 *       {@code subagent_cancel} / {@code subagent_await}。</li>
 * </ul>
 *
 * <h3>必要替换</h3>
 * <ol>
 *   <li>提示词与工具描述里的参考实现品牌词 → 「问渠」（与内置 Skill 描述同一口径）。</li>
 *   <li>{@code StructuredTool.from_function(coroutine=...)} → {@link ToolCallback}；
 *       {@code Annotated[str, "描述"]} 的参数描述以 JSON Schema 承载。</li>
 *   <li>{@code runtime.tool_call_id}（LangGraph {@code ToolRuntime}）→ 本工程工具只收到
 *       JSON 入参，tool_call_id 由构图方在调用前通过 {@link #bindToolCallId} 绑定
 *       （与 {@code MemoryMiddleware} 的 ThreadLocal 绑定同一手法）。</li>
 *   <li>{@code pg_manager.get_async_session_context()} → Spring Bean 注入（{@link SubagentRunService} /
 *       {@link AgentRunService} 自管事务）。</li>
 * </ol>
 *
 * <h3>能力差异（显式标注）</h3>
 * <ol>
 *   <li><b>Command 状态写回</b>：参考实现 5 个工具均返回
 *       {@code Command(update={"messages":[...],"subagent_runs":[...]})}。本工程工具只能返回字符串，
 *       故把「要写回 state 的部分」抽成 {@link ToolCommand} 载体，经 {@link #drainPendingCommands()}
 *       交给构图方落 state —— 与 {@code TokenUsageMiddleware} 的「纯函数 + context 传递」同一解法。
 *       工具返回给模型的文本与参考实现 {@code Command.update["messages"][0].content} 逐字一致。</li>
 *   <li><b>工具 Schema 复用</b>：参考实现用模块级 {@code _TOOL_INPUT_SCHEMAS} 缓存 pydantic schema，
 *       避免逐 Run 重新推导（单测 {@code test_fixed_tool_schemas_reused_without_sharing_parent_run} 锁死）。
 *       本工程 Schema 是编译期常量字符串（本类 {@code *_SCHEMA}），天然满足「不逐 Run 推导」，
 *       且工具实例与父 Run 的绑定仍逐次创建（见 {@link #bindToolCallId}）。</li>
 *   <li><b>只保留同步链路</b>：参考实现的 {@code atask} 等是协程；本工程工具同步执行，
 *       {@code await_agent_run_result} 的阻塞语义由 {@link AgentRunService#awaitAgentRunResult} 承载。</li>
 * </ol>
 */
public class SubAgentMiddleware extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentMiddleware.class);

    /** 参考实现 {@code TASK_SYSTEM_PROMPT}（品牌词已替换，其余逐字）。 */
    public static final String TASK_SYSTEM_PROMPT = """
            ## `task`（子智能体任务工具）

            你可以使用 `task` 工具把复杂、独立的子任务交给已配置的子智能体处理。子智能体只返回最终结果，你看不到它的中间步骤。
            工具结果会包含子智能体线程 ID，后续需要继续同一个子任务时，把该 ID 作为 `thread_id` 传回 `task`。

            使用原则：
            - 任务足够复杂、可以独立完成、或需要隔离上下文时使用。
            - 多个互不依赖的子任务可以并行调用多个 `task`。
            - 继续既有子智能体任务时传入之前结果中的 `thread_id`；新任务不要填写 `thread_id`。
            - 不要并行调用同一个 `thread_id`，避免多个续跑请求同时写入同一子线程。
            - 简单问题或少量直接工具调用不要委派。
            - 调用时必须选择下方可用的 `subagent_slug`，并在 `description` 中写清目标、上下文和期望输出。
            - 不要通过 shell、curl、HTTP API 或命令行间接调用子智能体；需要子智能体时必须使用 `task` 工具。

            后台子智能体：
            - 长任务或多个可并行任务优先使用 `subagent_start`，它会立即返回 `run_id` 和 `thread_id`，父智能体可以继续工作。
            - 后续用 `subagent_status` 查询状态和最近进度，`subagent_cancel` 取消，
              `subagent_await` 在明确需要结果时等待。
            - `thread_id` 是子智能体长期上下文 ID；同一个 `thread_id` 完成后可以继续创建新的 run。
              若同线程已有运行中 run，会返回 busy，不会隐藏排队。
            - 短任务且父智能体必须立刻依赖结果时继续使用 `task`。

            Available subagent slugs:

            {available_agents}""";

    /** 参考实现 {@code TASK_TOOL_DESCRIPTION}（品牌词已替换，其余逐字）。 */
    public static final String TASK_TOOL_DESCRIPTION = """
            Launch a configured 问渠 subagent to handle an isolated task.

            Available subagent slugs:
            {available_agents}

            Use `subagent_slug` to select one available subagent and put the full task brief in `description`.
            Omit `thread_id` for a new task. To continue a previous subagent task, pass the child thread ID returned by
            that prior task result as `thread_id`.
            Do not call subagents through shell, curl, HTTP APIs, or command-line indirection.""";

    /** 参考实现 {@code SUBAGENT_START_DESCRIPTION}（逐字）。 */
    public static final String SUBAGENT_START_DESCRIPTION = """
            Start a configured 问渠 subagent asynchronously.

            Returns a child thread ID for future continuation and a run ID for status/cancel/result checks.
            Use this for long-running or parallelizable subagent work. If `thread_id` is provided, it continues that subagent
            thread when no active run is currently writing to it.""";

    /** 参考实现 {@code SUBAGENT_STATUS_DESCRIPTION}（逐字）。 */
    public static final String SUBAGENT_STATUS_DESCRIPTION = """
            Check a subagent run status by run_id.

            Returns the current run status, a compact progress summary with the latest 3 readable messages, and the final result
            when the run has reached a terminal status.""";

    /** 参考实现 {@code SUBAGENT_CANCEL_DESCRIPTION}（逐字）。 */
    public static final String SUBAGENT_CANCEL_DESCRIPTION = "Cancel a running subagent run by run_id.";

    /** 参考实现 {@code SUBAGENT_AWAIT_DESCRIPTION}（逐字）。 */
    public static final String SUBAGENT_AWAIT_DESCRIPTION =
            "Wait for a subagent run to finish and return its final result.";

    /** 参考实现 {@code TASK_DESCRIPTION_ARG}（逐字）。 */
    static final String TASK_DESCRIPTION_ARG = "需要子智能体独立完成的任务描述，包含必要上下文和期望输出。";

    /** 参考实现 {@code SUBAGENT_SLUG_ARG}（逐字）。 */
    static final String SUBAGENT_SLUG_ARG = "要调用的子智能体 slug，必须是工具描述中列出的可用项之一。";

    /** 参考实现 {@code TASK_THREAD_ID_ARG}（逐字）。 */
    static final String TASK_THREAD_ID_ARG = "可选。要继续的既有子智能体线程 ID，通常来自之前 task 工具结果；新任务不要填写。";

    /** 参考实现 {@code ASYNC_THREAD_ID_ARG}（逐字）。 */
    static final String ASYNC_THREAD_ID_ARG = "可选。要继续的后台子智能体线程 ID，来自之前 subagent_start 返回的 thread_id；新任务不要填写。";

    /** 参考实现 {@code SUBAGENT_RUN_ID_ARG}（逐字）。 */
    static final String SUBAGENT_RUN_ID_ARG = "子智能体运行 ID，由 subagent_start 返回。";

    /** 参考实现 {@code _task_result_response} 的兜底文案（逐字）。 */
    static final String EMPTY_OUTPUT_TEXT = "子智能体已完成任务，但没有返回文本结果。";

    /** 参考实现 {@code _task_wait_timeout_response} 的超时文案（逐字）。 */
    static final String WAIT_TIMEOUT_TEXT = "子智能体仍在运行，等待最终结果超时；请稍后继续查询。";

    /** 参考实现 {@code _tool_result_with_thread_id} 的前缀模板（逐字）。 */
    static final String THREAD_ID_PREFIX_TEMPLATE = "> 子智能体线程 ID: %s\n\n---\n\n%s";

    /** 参考实现 {@code task} 拒绝未配置 slug 的文案模板（逐字）。 */
    static final String UNKNOWN_SLUG_TEMPLATE = "无法调用子智能体 %s，可用子智能体只有：%s";

    /** 参考实现后台工具缺少 uid / 父 run id 的文案模板（逐字）。 */
    static final String MISSING_UID_TEMPLATE = "%s：当前运行时缺少 uid";
    static final String MISSING_RUN_ID_TEMPLATE = "%s：当前运行时缺少父运行 ID";

    static final String TASK_SCHEMA = """
            {"type":"object","properties":{
              "description":{"type":"string","description":"需要子智能体独立完成的任务描述，包含必要上下文和期望输出。"},
              "subagent_slug":{"type":"string","description":"要调用的子智能体 slug，必须是工具描述中列出的可用项之一。"},
              "thread_id":{"type":"string","description":"可选。要继续的既有子智能体线程 ID，通常来自之前 task 工具结果；新任务不要填写。"}
            },"required":["description","subagent_slug"]}""";

    static final String SUBAGENT_START_SCHEMA = """
            {"type":"object","properties":{
              "description":{"type":"string","description":"需要子智能体独立完成的任务描述，包含必要上下文和期望输出。"},
              "subagent_slug":{"type":"string","description":"要调用的子智能体 slug，必须是工具描述中列出的可用项之一。"},
              "thread_id":{"type":"string","description":"可选。要继续的后台子智能体线程 ID，来自之前 subagent_start 返回的 thread_id；新任务不要填写。"}
            },"required":["description","subagent_slug"]}""";

    static final String RUN_ID_SCHEMA = """
            {"type":"object","properties":{
              "run_id":{"type":"string","description":"子智能体运行 ID，由 subagent_start 返回。"}
            },"required":["run_id"]}""";

    private final BaseContext parentContext;
    private final Map<String, Agent> subagents;
    private final String systemPrompt;
    private final List<ToolCallback> tools;

    private final SubagentRunService subagentRunService;
    private final AgentRunService agentRunService;

    /** 当前工具调用的 tool_call_id（对应 {@code runtime.tool_call_id}，见类注释「必要替换 3」）。 */
    private final ThreadLocal<String> toolCallId = new ThreadLocal<>();

    /** 待落 state 的 {@code Command} 载体（见类注释「能力差异 1」）。 */
    private final List<ToolCommand> pendingCommands = new CopyOnWriteArrayList<>();

    public SubAgentMiddleware(BaseContext parentContext, List<Agent> subagents,
            SubagentRunService subagentRunService, AgentRunService agentRunService) {
        this.parentContext = parentContext;
        this.subagentRunService = subagentRunService;
        this.agentRunService = agentRunService;
        Map<String, Agent> bySlug = new LinkedHashMap<>();
        StringBuilder available = new StringBuilder();
        for (Agent agent : subagents == null ? List.<Agent>of() : subagents) {
            if (agent == null || agent.getSlug() == null || bySlug.containsKey(agent.getSlug())) {
                continue;
            }
            bySlug.put(agent.getSlug(), agent);
            if (available.length() > 0) {
                available.append("\n");
            }
            String description = agent.getDescription() == null || agent.getDescription().isEmpty()
                    ? agent.getName()
                    : agent.getDescription();
            available.append("- ").append(agent.getSlug()).append(": ").append(description);
        }
        this.subagents = bySlug;
        String availableAgents = available.toString();
        this.systemPrompt = TASK_SYSTEM_PROMPT.replace("{available_agents}", availableAgents);
        List<ToolCallback> built = new ArrayList<>();
        built.add(taskTool(availableAgents));
        built.add(subagentStartTool(availableAgents));
        built.add(runIdTool("subagent_status", SUBAGENT_STATUS_DESCRIPTION));
        built.add(runIdTool("subagent_cancel", SUBAGENT_CANCEL_DESCRIPTION));
        built.add(runIdTool("subagent_await", SUBAGENT_AWAIT_DESCRIPTION));
        this.tools = List.copyOf(built);
    }

    /**
     * 对应参考实现 {@code create_subagent_task_middleware}：按父智能体上下文加载可见子智能体。
     *
     * <p>{@code uid} 为空、用户不存在、或无可见子智能体时返回 {@code null}（与参考实现一致）。
     */
    public static SubAgentMiddleware create(
            BaseContext parentContext,
            AgentRepository agentRepository,
            UserRepository userRepository,
            SubagentRunService subagentRunService,
            AgentRunService agentRunService) {
        if (parentContext == null || agentRepository == null || userRepository == null) {
            return null;
        }
        String uid = parentContext.getString("uid");
        if (uid == null || uid.isEmpty()) {
            return null;
        }
        User user = userRepository.getByUid(uid);
        if (user == null) {
            return null;
        }
        List<String> selectedSlugs = new ArrayList<>();
        Object configured = parentContext.get("subagents");
        if (configured instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    String slug = String.valueOf(item).strip();
                    if (!slug.isEmpty()) {
                        selectedSlugs.add(slug);
                    }
                }
            }
        }
        List<Agent> subagents = new ArrayList<>();
        if (!selectedSlugs.isEmpty()) {
            for (String slug : new LinkedHashSet<>(selectedSlugs)) {
                Agent agent = agentRepository.getVisibleBySlug(
                        slug, PermissionSubject.of(user), AgentRepository.AgentEntryKind.SUBAGENT);
                if (agent != null) {
                    subagents.add(agent);
                }
            }
        } else {
            subagents.addAll(agentRepository.listVisibleSubagents(PermissionSubject.of(user)));
        }
        if (subagents.isEmpty()) {
            return null;
        }
        return new SubAgentMiddleware(parentContext, subagents, subagentRunService, agentRunService);
    }

    @Override
    public String getName() {
        return "subagent_task";
    }

    /** 5 个子智能体工具（对应参考实现 {@code self.tools}）。 */
    @Override
    public List<ToolCallback> getTools() {
        return tools;
    }

    /** 已注入的系统提示段（供构图方核对）。 */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        // 平台差异（见类注释「能力差异 1」的落点）：参考实现由框架把
        // Command(update={"subagent_runs": [...]}) 写回 state；本工程在此把上一次工具调用
        // 攒下的载体转交给 Run 级写回缓冲，由 AgentStateWritebackHook 落回 OverAllState。
        // 时机：本方法每次模型调用前执行，而工具执行发生在上一次模型调用之后，故增量必被取走。
        flushPendingCommandsToWriteback();
        ModelRequest updated = ModelRequest.builder(request)
                .systemMessage(MemoryMiddleware.appendToSystemMessage(request.getSystemMessage(), systemPrompt))
                .build();
        return handler.call(updated);
    }

    /** 把待落 state 的 {@code subagent_runs} 增量转交写回缓冲。 */
    private void flushPendingCommandsToWriteback() {
        List<ToolCommand> commands = drainPendingCommands();
        if (commands.isEmpty()) {
            return;
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        for (ToolCommand command : commands) {
            if (command != null && command.subagentRun() != null) {
                runs.add(command.subagentRun());
            }
        }
        AgentStateWriteback.addSubagentRuns(parentContext, runs);
    }

    // ==================== Command 载体 ====================

    /**
     * 参考实现 {@code Command(update={"messages":[...],"subagent_runs":[...]})} 的等价载体
     * （见类注释「能力差异 1」）。
     *
     * @param content     回给模型的工具结果文本
     * @param toolCallId  关联的 tool_call_id
     * @param subagentRun 要并入 state 的 {@code subagent_runs} 项（可为 null）
     */
    public record ToolCommand(String content, String toolCallId, Map<String, Object> subagentRun) {
    }

    /** 取走并清空待落 state 的 Command 载体（由构图方调用）。 */
    public List<ToolCommand> drainPendingCommands() {
        List<ToolCommand> drained = new ArrayList<>(pendingCommands);
        pendingCommands.clear();
        return drained;
    }

    /** 绑定一次工具调用的 tool_call_id（对应 {@code ToolRuntime.tool_call_id}）。 */
    public SubAgentMiddleware bindToolCallId(String toolCallIdValue) {
        toolCallId.set(toolCallIdValue);
        return this;
    }

    // ==================== 纯函数：结果封装 ====================

    /** 对应参考实现 {@code _tool_result_with_thread_id}。 */
    static String toolResultWithThreadId(String childThreadId, String content) {
        return String.format(THREAD_ID_PREFIX_TEMPLATE, childThreadId, content);
    }

    /** 对应参考实现 {@code _task_result_response}（返回 Command 载体）。 */
    static ToolCommand taskResultResponse(
            Map<String, Object> result, String toolCallIdValue, Map<String, Object> subagentRun) {
        String output = result.get("output") == null ? "" : String.valueOf(result.get("output")).strip();
        if (output.isEmpty() && result.get("error") instanceof Map<?, ?> error) {
            Object message = error.get("message");
            output = message == null ? "" : String.valueOf(message);
            if (output.isEmpty()) {
                output = "子智能体运行失败";
            }
        }
        if (output.isEmpty()) {
            output = EMPTY_OUTPUT_TEXT;
        }
        Object childThreadId = subagentRun.get("child_thread_id");
        return new ToolCommand(
                toolResultWithThreadId(String.valueOf(childThreadId), output), toolCallIdValue, subagentRun);
    }

    /** 对应参考实现 {@code _task_wait_timeout_response}（返回 Command 载体）。 */
    static ToolCommand taskWaitTimeoutResponse(
            Map<String, Object> result, String toolCallIdValue, Map<String, Object> subagentRun) {
        String status = result.get("status") == null
                ? String.valueOf(subagentRun.get("status"))
                : String.valueOf(result.get("status"));
        if (status.isEmpty() || "null".equals(status)) {
            status = "running";
        }
        String runId = result.get("agent_run_id") == null
                ? String.valueOf(subagentRun.get("run_id"))
                : String.valueOf(result.get("agent_run_id"));
        String output = "子智能体仍在运行（status: " + status + "），尚未返回最终文本结果。\n"
                + "run_id: " + runId + "\n"
                + "请稍后使用 subagent_status 或 subagent_await 查询结果；不要把当前结果视为任务已完成。";
        Object childThreadId = subagentRun.get("child_thread_id");
        return new ToolCommand(
                toolResultWithThreadId(String.valueOf(childThreadId), output), toolCallIdValue, subagentRun);
    }

    /** 对应参考实现 {@code _json_tool_command}（{@code json.dumps(ensure_ascii=False, indent=2)}）。 */
    static ToolCommand jsonToolCommand(
            Map<String, Object> payload, String toolCallIdValue, Map<String, Object> subagentRun) {
        String content = JSON.toJSONString(payload,
                JSONWriter.Feature.WriteMapNullValue,
                JSONWriter.Feature.PrettyFormat);
        return new ToolCommand(content, toolCallIdValue, subagentRun);
    }

    // ==================== 工具实现 ====================

    /** 对应参考实现 {@code _build_task_tool}。 */
    private ToolCallback taskTool(String availableAgents) {
        return new SubagentToolCallback("task", TASK_TOOL_DESCRIPTION.replace("{available_agents}", availableAgents),
                TASK_SCHEMA, (args, callId) -> runTask(args, callId));
    }

    /** 对应参考实现 {@code _build_async_subagent_tools} 的 {@code asubagent_start}。 */
    private ToolCallback subagentStartTool(String availableAgents) {
        String description = SUBAGENT_START_DESCRIPTION + "\n\nAvailable subagent slugs:\n" + availableAgents;
        return new SubagentToolCallback("subagent_start", description, SUBAGENT_START_SCHEMA,
                (args, callId) -> runSubagentStart(args, callId));
    }

    private ToolCallback runIdTool(String name, String description) {
        return new SubagentToolCallback(name, description, RUN_ID_SCHEMA, (args, callId) -> {
            String runId = stringArg(args, "run_id");
            String errorPrefix = switch (name) {
                case "subagent_status" -> "无法查询子智能体";
                case "subagent_cancel" -> "无法取消子智能体";
                default -> "无法等待子智能体";
            };
            String runtimeError = requireParentRuntime(errorPrefix);
            if (runtimeError != null) {
                return runtimeError;
            }
            String uid = uidOf();
            String createdByRunId = runIdOf();
            AgentRun run;
            try {
                run = getVerifiedSubagentRun(runId, uid, createdByRunId);
            } catch (IllegalArgumentException exc) {
                return exc.getMessage();
            }
            return switch (name) {
                case "subagent_status" -> subagentStatus(run, uid, callId);
                case "subagent_cancel" -> subagentCancel(run, uid, callId);
                default -> subagentAwait(run, runId, uid, createdByRunId, callId);
            };
        });
    }

    /** 对应参考实现 {@code atask}。 */
    private String runTask(Map<String, Object> args, String callId) {
        String description = stringArg(args, "description");
        String slug = stringArg(args, "subagent_slug");
        String threadId = stringArg(args, "thread_id");
        if (!subagents.containsKey(slug)) {
            return String.format(UNKNOWN_SLUG_TEMPLATE, slug, allowedSlugsText());
        }
        String runtimeError = requireParentRuntime("无法调用子智能体");
        if (runtimeError != null) {
            return runtimeError;
        }
        String uid = uidOf();
        String createdByRunId = runIdOf();
        Agent agentItem = subagents.get(slug);
        SubagentRunService.SubagentStartResult started;
        try {
            started = subagentRunService.start(uid, createdByRunId, agentItem,
                    InputMessageService.buildChatInputMessage(description, null), callId, threadId);
        } catch (SubagentRunService.SubagentRunBusy busy) {
            pendingCommands.add(jsonToolCommand(busy.toPayload(), callId, null));
            return JSON.toJSONString(busy.toPayload(),
                    JSONWriter.Feature.WriteMapNullValue, JSONWriter.Feature.PrettyFormat);
        } catch (IllegalArgumentException exc) {
            return exc.getMessage();
        }
        String runId = started.run().getId();
        Map<String, Object> result;
        AgentRun run;
        try {
            result = agentRunService.awaitAgentRunResult(runId, uid);
            run = getVerifiedSubagentRun(runId, uid, createdByRunId);
        } catch (AgentRunService.AgentRunWaitTimeout timeout) {
            try {
                run = getVerifiedSubagentRun(runId, uid, createdByRunId);
            } catch (IllegalArgumentException exc) {
                return exc.getMessage();
            }
            ToolCommand command = taskWaitTimeoutResponse(timeout.getResult(), callId,
                    SubagentRunService.serializeSubagentRunState(run));
            pendingCommands.add(command);
            return command.content();
        } catch (IllegalArgumentException exc) {
            return exc.getMessage();
        }
        ToolCommand command = taskResultResponse(result, callId, SubagentRunService.serializeSubagentRunState(run));
        pendingCommands.add(command);
        return command.content();
    }

    /** 对应参考实现 {@code asubagent_start}。 */
    private String runSubagentStart(Map<String, Object> args, String callId) {
        String description = stringArg(args, "description");
        String slug = stringArg(args, "subagent_slug");
        String threadId = stringArg(args, "thread_id");
        if (!subagents.containsKey(slug)) {
            return String.format(UNKNOWN_SLUG_TEMPLATE, slug, allowedSlugsText());
        }
        String runtimeError = requireParentRuntime("无法启动子智能体");
        if (runtimeError != null) {
            return runtimeError;
        }
        String uid = uidOf();
        String createdByRunId = runIdOf();
        Agent agentItem = subagents.get(slug);
        SubagentRunService.SubagentStartResult result;
        try {
            result = subagentRunService.start(uid, createdByRunId, agentItem,
                    InputMessageService.buildChatInputMessage(description, null), callId, threadId);
        } catch (SubagentRunService.SubagentRunBusy busy) {
            ToolCommand command = jsonToolCommand(busy.toPayload(), callId, null);
            pendingCommands.add(command);
            return command.content();
        } catch (IllegalArgumentException exc) {
            return exc.getMessage();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.created() ? "started" : "existing");
        payload.put("run_id", result.run().getId());
        payload.put("thread_id", result.relation().getChildThreadId());
        payload.put("subagent_slug", slug);
        payload.put("subagent_name", agentItem.getName());
        payload.put("created_by_run_id", result.run().getCreatedByRunId());
        payload.put("run_status", result.run().getStatus());
        payload.put("continuing", result.continuing());
        payload.put("subagent_thread_relation_id", result.relation().getId());
        payload.putAll(SubagentRunService.subagentRunUrls(result.run().getId()));
        ToolCommand command = jsonToolCommand(payload, callId,
                SubagentRunService.serializeSubagentRunState(result.run()));
        pendingCommands.add(command);
        return command.content();
    }

    /** 对应参考实现 {@code asubagent_status}。 */
    private String subagentStatus(AgentRun run, String uid, String callId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", run.getStatus());
        payload.put("run_id", run.getId());
        payload.put("thread_id", run.getConversationThreadId());
        payload.put("subagent_slug", run.getAgentSlug());
        payload.put("error", run.getErrorMessage());
        payload.put("progress",
                agentRunService.getAgentRunProgress(run.getId(), AgentRunService.RUN_PROGRESS_MESSAGE_LIMIT));
        payload.putAll(SubagentRunService.subagentRunUrls(run.getId()));
        if (AgentRunRepository.TERMINAL_RUN_STATUSES.contains(run.getStatus())) {
            Map<String, Object> result = agentRunService.getAgentRunResult(run.getId(), uid);
            if (result != null && !result.isEmpty()) {
                payload.put("result", result);
            }
        }
        ToolCommand command = jsonToolCommand(payload, callId, SubagentRunService.serializeSubagentRunState(run));
        pendingCommands.add(command);
        return command.content();
    }

    /** 对应参考实现 {@code asubagent_cancel}。 */
    private String subagentCancel(AgentRun run, String uid, String callId) {
        AgentRun cancelled;
        try {
            cancelled = agentRunService.requestCancelAgentRun(run.getId(), uid, false);
        } catch (IllegalArgumentException exc) {
            return exc.getMessage();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", cancelled.getStatus());
        payload.put("run_id", cancelled.getId());
        payload.put("thread_id", cancelled.getConversationThreadId());
        payload.putAll(SubagentRunService.subagentRunUrls(cancelled.getId()));
        ToolCommand command = jsonToolCommand(payload, callId, SubagentRunService.serializeSubagentRunState(cancelled));
        pendingCommands.add(command);
        return command.content();
    }

    /** 对应参考实现 {@code asubagent_await}。 */
    private String subagentAwait(AgentRun run, String runId, String uid, String createdByRunId, String callId) {
        boolean waitTimedOut = false;
        Map<String, Object> result;
        AgentRun latest = run;
        try {
            result = agentRunService.awaitAgentRunResult(runId, uid);
            latest = getVerifiedSubagentRun(runId, uid, createdByRunId);
        } catch (AgentRunService.AgentRunWaitTimeout timeout) {
            waitTimedOut = true;
            result = timeout.getResult();
            try {
                latest = getVerifiedSubagentRun(runId, uid, createdByRunId);
            } catch (IllegalArgumentException exc) {
                return exc.getMessage();
            }
        } catch (IllegalArgumentException exc) {
            return exc.getMessage();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", latest.getStatus());
        payload.put("run_id", latest.getId());
        payload.put("thread_id", latest.getConversationThreadId());
        payload.put("result", result);
        if (waitTimedOut) {
            payload.put("wait_timed_out", true);
            payload.put("message", WAIT_TIMEOUT_TEXT);
        }
        ToolCommand command = jsonToolCommand(payload, callId, SubagentRunService.serializeSubagentRunState(latest));
        pendingCommands.add(command);
        return command.content();
    }

    // ==================== 父运行上下文 ====================

    /** 对应参考实现 {@code _require_async_parent_runtime}。 */
    private String requireParentRuntime(String errorPrefix) {
        if (uidOf().isEmpty()) {
            return String.format(MISSING_UID_TEMPLATE, errorPrefix);
        }
        if (runIdOf().isEmpty()) {
            return String.format(MISSING_RUN_ID_TEMPLATE, errorPrefix);
        }
        return null;
    }

    private String uidOf() {
        String uid = parentContext == null ? null : parentContext.getString("uid");
        return uid == null ? "" : uid.strip();
    }

    private String runIdOf() {
        String runId = parentContext == null ? null : parentContext.getString("run_id");
        return runId == null ? "" : runId.strip();
    }

    /** 对应参考实现 {@code _get_verified_subagent_run}。 */
    private AgentRun getVerifiedSubagentRun(String runId, String uid, String createdByRunId) {
        return subagentRunService.getRunForCreator(uid, createdByRunId, runId);
    }

    private String allowedSlugsText() {
        List<String> allowed = new ArrayList<>();
        for (String slug : subagents.keySet()) {
            allowed.add("`" + slug + "`");
        }
        return String.join(", ", allowed);
    }

    static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 子智能体工具的框架适配器（见类注释「必要替换 2」）。 */
    private final class SubagentToolCallback implements ToolCallback {

        private final String name;
        private final String description;
        private final String inputSchema;
        private final ToolBody body;

        SubagentToolCallback(String name, String description, String inputSchema, ToolBody body) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.body = body;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            String callId = toolCallId.get();
            if (callId == null || callId.isEmpty()) {
                log.warn("Subagent tool {} called without tool_call_id bound", name);
            }
            try {
                return body.run(parseArgs(toolInput), callId == null ? "" : callId);
            } catch (RuntimeException exc) {
                log.warn("Subagent tool {} failed: {}", name, exc.getMessage());
                throw exc;
            }
        }
    }

    /** 工具执行体（对应参考实现各工具的 {@code coroutine}）。 */
    @FunctionalInterface
    interface ToolBody {
        String run(Map<String, Object> args, String toolCallId);
    }

    private static Map<String, Object> parseArgs(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(toolInput);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (RuntimeException exc) {
            throw new IllegalArgumentException("工具入参不是合法 JSON: " + exc.getMessage());
        }
    }
}
