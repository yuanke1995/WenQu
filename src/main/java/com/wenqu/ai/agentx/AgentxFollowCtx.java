package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agentx.turn.TurnFollowLoop;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator;
import com.wenqu.ai.engine.RunChannel;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.session.AgentSession;

import java.util.Map;

/**
 * 一轮跟随的共享状态（从 AgentxFollowRunner 的内部类抽出）。
 *
 * <p>跟随循环、结算、收尾三个阶段读写同一份状态：收尾阶段要的正文、失败、待办快照
 * 都是前面阶段算出来的，用可变对象传递比十几个返回值更直白。
 *
 * @author yuanke
 */
public final class AgentxFollowCtx {
    AgentSession session;
    ChatCommand cmd;
    RunChannel channel;
    WenquSourcePool pool;
    WenquAgentxTools.TurnHooks hooks;
    String turnId;
    int historyBefore;
    int contextWindow;
    AgentSession.Task current;
    AgentxTurnRecording recording;
    long startedAt;
    long prepMs;
    boolean recovered;
    boolean orphanRecovered;
    long machineBudgetMs;
    long abruptBefore;
    long modelErrBefore;
    MeteredModel turnModel;
    TurnInteractionCoordinator interactions;
    TurnFollowLoop loop;
    AgentxTurnMirror mirror;
    boolean superseded;
    String planOutcomeRaw;
    Map<String, Object> planRecord;
    Map<String, SessionEvent> pendingAtClose;
    boolean ranOutBudget;
    String answer;
    boolean streamedBody;
    String failure;
    String spokenFailure;
    boolean awaiting;
    long machineMs;
    long wallMs;
    String recoveredNote;
    boolean cutByMaxIters;
}
