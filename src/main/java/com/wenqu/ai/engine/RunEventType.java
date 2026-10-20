package com.wenqu.ai.engine;

// [保留] 引擎抽象与前端事件契约 —— AgentScope 框架引擎经 RunChannel/RunEvent 向问渠前端发事件，前端只认这套事件名与契约，不是死代码，勿删。

/**
 * 问答这一轮往前端推的事件词汇表。
 * <p>名字与 {@code ChatRunRuntime.sendSseEvent} 的 type 实参一一对应，是<b>前端契约</b>而不是内部枚举：
 * 改名等于断约，所以每条都钉死 SSE 事件名，由 {@code web/scripts/check-sse-contract.mjs} 与
 * {@code web/src/api.js} 的 CHAT_EVENT_CB 双向核对（多一条、少一条、拼错都判红）。
 * <p>这张表存在的原因：框架引擎产出的是 AgentScope 的 30 类 SessionLog 事件，必须在适配层
 * 收敛到同一张表上，前端才不用跟着框架的事件名走。手写运行时与灰度分流已随平移下线。
 */
public enum RunEventType {

    TOKEN("token"),
    PROCESS("process"),
    WARN("warn"),
    THINKING("thinking"),
    THINKING_DONE("thinking_done"),
    TOOL_STATUS("tool_status"),
    TOOL_OUTPUT("tool_output"),
    RUNCODE_PROGRESS("runcode_progress"),
    SUBAGENT_STEP("subagent_step"),
    AGENT_DELEGATED("agent_delegated"),
    AGENT_CONSULTED("agent_consulted"),
    APPROVAL_REQUIRED("approval_required"),
    ASK_USER("ask_user"),
    PLAN("plan"),
    PLAN_DELTA("plan_delta"),
    PLAN_APPROVAL("plan_approval"),
    PLAN_CANCELLED("plan_cancelled"),
    PLAN_SUPERSEDED("plan_superseded"),
    TODO("todo"),
    RETRIEVED("retrieved"),
    USAGE("usage"),
    IMAGE("image"),
    ARTIFACT("artifact"),
    /** 运行中插话<b>被模型听到</b>的那一刻（框架 {@code inbox/applied}）。不是「用户按了发送」——
     *  那一刻由 {@code POST /chat/steer} 自己回执，这条只对应「送进这一轮的上下文」，故带正文与消息 ID。 */
    STEER("steer"),
    SNAPSHOT("snapshot"),
    DONE("done"),
    ERROR("error");

    private final String sseName;

    RunEventType(String sseName) {
        this.sseName = sseName;
    }

    /** 发到前端的那条 SSE 的 event name——契约值，别按 Java 命名习惯改。 */
    public String sseName() {
        return sseName;
    }
}
