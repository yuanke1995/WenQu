package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

/**
 * 本轮录制器与事件游标的建立（从 AgentscopeChatEngine 抽出）。
 *
 * <p>用量与游标都必须从「本轮开跑之前」起读：框架的会话记录与 Session Log 跨轮常驻，
 * 整表求和会把历史轮的 token 计进这一轮账单，从 0 读会把上一轮的正文与工具卡当增量再发一遍
 * （前端按 pos 去重只护得住同一条气泡的重复片段，护不住跨轮）。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxTurnRecordingFactory {

    private final WenquAgentxTools tools;
    private final ToolDisplayNames displayNames;
    private final BiConsumer<ChatCommand, AgentxTurnRecording> bindToolGate;

    /** 建立结果：录制器、用量起点、事件游标起点。 */
    public record Created(AgentxTurnRecording recording, int historyBefore, long startCursor) {
    }

    public Created create(ChatCommand cmd, AgentSession session, WenquAgentxTools.TurnHooks hooks,
                          ConcurrentLinkedQueue<String> spills) {
        int historyBefore = session.transcript().messages().size();
        long startCursor = session.log().head().seq();
        AgentxTurnRecording recording = new AgentxTurnRecording(spills, hooks.consultLedger(), tools, hooks,
                displayNames, cmd.getUserId());
        bindToolGate.accept(cmd, recording);
        return new Created(recording, historyBefore, startCursor);
    }
}
