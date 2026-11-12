package com.wenqu.ai.engine;

// [保留] 引擎抽象与前端事件契约 —— AgentScope 框架引擎经 RunChannel/RunEvent 向问渠前端发事件，前端只认这套事件名与契约，不是死代码，勿删。

/**
 * 问答这一轮往前端推的事件词汇表，也是前后端事件契约的唯一真源。
 *
 * <p>每条事件自带：SSE 名、前端回调名、账本展示文案/色调、是否进 SSE 账本、是否进断线镜像。
 * {@code ChatRunRuntime} 的 ledger 名单、{@code ChatRunBus} 的 mirror 名单、前端
 * {@code CHAT_EVENT_CB / SSE_LABELS / SSE_TONES} 都由本枚举派生——新增事件只改这里，
 * 再跑一次契约生成器。
 *
 * <p>三条硬约束：
 * <ul>
 *   <li>{@code sseName} 是契约值，改名等于断约；</li>
 *   <li>{@code sseLedger} 只描述「这条 SSE 帧要不要进会话事件账本」，目标态另有 GoalService 的
 *       专用账本，不在这里重复标记；</li>
 *   <li>{@code callback} 为 null 表示前端不做常规派发（done/error 由两条链路的收尾逻辑单独处理）。</li>
 * </ul>
 */
public enum RunEventType {

    TOKEN("token", "onToken", null, null, false, false),
    PROCESS("process", "onProcess", "过程", null, false, false),
    WARN("warn", "onWarn", "提醒", "warn", true, true),
    THINKING("thinking", "onThinking", null, null, false, false),
    THINKING_DONE("thinking_done", "onThinkingDone", null, null, false, false),
    TOOL_STATUS("tool_status", "onToolStatus", "工具", "tool", true, false),
    TOOL_OUTPUT("tool_output", "onToolOutput", null, "tool", false, false),
    RUNCODE_PROGRESS("runcode_progress", "onRunCodeProgress", null, null, false, true),
    SUBAGENT_STEP("subagent_step", "onSubagentStep", null, "sub", false, false),
    AGENT_DELEGATED("agent_delegated", "onAgentDelegated", "委派作答", "sub", true, false),
    AGENT_CONSULTED("agent_consulted", "onAgentConsulted", "智能体咨询", "sub", true, false),
    APPROVAL_REQUIRED("approval_required", "onApprovalRequired", "工具待批准", "card", true, false),
    ASK_USER("ask_user", "onAskUser", "提问卡", "card", true, false),
    PLAN("plan", "onPlan", "本轮步骤", "card", true, true),
    DEEP_PLAN_DELTA("deep_plan_delta", "onDeepPlanDelta", null, null, false, false),
    DEEP_PLAN_APPROVAL("deep_plan_approval", "onDeepPlanApproval", "深度执行计划待批准", "card", true, false),
    DEEP_PLAN_CANCELLED("deep_plan_cancelled", "onDeepPlanCancelled", "深度执行计划未批准", "card", true, false),
    DEEP_PLAN_SUPERSEDED("deep_plan_superseded", "onDeepPlanSuperseded", "深度执行计划被取代", "card", true, false),
    TODO("todo", "onTodo", "任务清单", null, false, true),
    /** 深度执行态变更：进镜像（断线重连要能重建执行面板）；账本由 GoalService 按执行 ID 单独写。 */
    DEEP_STATE("deep_state", "onDeepState", "深度执行态", null, false, true),
    /** 深度执行跨轮续跑开了新的一轮（后台轮）：前端据此自动接流。 */
    DEEP_TURN("deep_turn", "onDeepTurn", "深度执行续跑", null, false, false),
    RETRIEVED("retrieved", "onRetrieved", "检索", "retr", true, true),
    /** 本轮有一条技能被读取（点名注入或模型按需读取）；刷新后回看要能重建「已按技能 X 执行」芯片。 */
    SKILL_HIT("skill_hit", "onSkillHit", "技能命中", null, true, false),
    USAGE("usage", "onUsage", "用量", "use", true, true),
    /** 轮内自动压缩的开始/结束：不发这一帧，压缩那二十多秒在界面上是纯静默。 */
    COMPACT_STATE("compact_state", "onCompactState", "上下文压缩", null, false, true),
    IMAGE("image", "onImage", null, null, false, false),
    ARTIFACT("artifact", "onArtifact", "产物", "art", true, false),
    /** 运行中插话被模型听到的那一刻（框架 inbox/applied），不是「用户按了发送」。 */
    STEER("steer", "onSteer", "中途插话", null, false, false),
    /** 本轮服务端起表基准：开流第一帧下发，前端据此锚定秒表。 */
    TURN_START("turn_start", "onTurnStart", null, null, false, false),
    SNAPSHOT("snapshot", "onSnapshot", null, null, false, false),
    DONE("done", null, "本轮结束", "done", false, false),
    ERROR("error", null, "失败", "err", true, false);

    private final String sseName;
    private final String callback;
    private final String label;
    private final String tone;
    private final boolean sseLedger;
    private final boolean mirror;

    RunEventType(String sseName, String callback, String label, String tone,
                 boolean sseLedger, boolean mirror) {
        this.sseName = sseName;
        this.callback = callback;
        this.label = label;
        this.tone = tone;
        this.sseLedger = sseLedger;
        this.mirror = mirror;
    }

    /** 发到前端的那条 SSE 的 event name——契约值，别按 Java 命名习惯改。 */
    public String sseName() {
        return sseName;
    }

    /** 前端派发回调名；null=由链路收尾逻辑单独处理（done/error）。 */
    public String callback() {
        return callback;
    }

    /** 账本/过程面板展示文案；null=不单列一行，落到兜底文案。 */
    public String label() {
        return label;
    }

    /** 过程面板色调；null=默认。 */
    public String tone() {
        return tone;
    }

    /** 这条 SSE 帧是否进会话事件账本。 */
    public boolean sseLedger() {
        return sseLedger;
    }

    /** 这条 SSE 帧是否进断线重连镜像。 */
    public boolean mirror() {
        return mirror;
    }

    /** 进 SSE 账本的事件名集合（ChatRunRuntime 用）。 */
    public static java.util.Set<String> sseLedgerNames() {
        return java.util.Arrays.stream(values())
                .filter(RunEventType::sseLedger)
                .map(RunEventType::sseName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** 进断线镜像的事件名集合（ChatRunBus 用）。 */
    public static java.util.Set<String> mirrorNames() {
        return java.util.Arrays.stream(values())
                .filter(RunEventType::mirror)
                .map(RunEventType::sseName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
