package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.ProjectTurnChangesService;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 开轮后的「本轮上下文」装配（从 AgentscopeChatEngine 抽出）：录制器与游标、计划位、
 * 送模型输入的拼装与工具面下发。
 *
 * <p>计划模式按轮进入（跑完必须退出）；输入里除了问题原文还有附件正文/@引用/#历史引用、
 * 用户改动对账、目标上下文与计划血缘——落库只保证人能回看到，不等于模型收到了。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxTurnContext {

    private final AgentxTurnRecordingFactory recordingFactory;
    private final AgentxTurnInput turnInput;
    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;
    private final AgentxTurnGateReset gateReset;
    private final AgentxSessionWarmup sessionWarmup;

    /** 本轮上下文。 */
    public record Assembled(AgentxTurnRecording recording, int historyBefore, long startCursor,
                            boolean planEngaged, String modelInput, Msg multimodal, boolean goalActive) {
    }

    /**
     * @param driftNote 用户改动对账（轮首，只等一小会儿）
     */
    public Assembled assemble(ChatCommand cmd, AgentSession session, RunChannel channel,
                              WenquAgentxTools.TurnHooks hooks, ConcurrentLinkedQueue<String> spills,
                              AgentBinding binding, String turnId, String imgNote,
                              boolean chatSeesImages, List<com.wenqu.ai.service.UserImageService.UserImage> userImgs,
                              WenquSourcePool pool, java.util.function.Supplier<String> driftNote) {
        sessionWarmup.warm(cmd, session, sessions::abandoned);
        gateReset.reset(cmd, turnId, tools, pool, channel);
        AgentxTurnRecordingFactory.Created created = recordingFactory.create(cmd, session, hooks, spills);
        boolean planEngaged = turnInput.engagePlan(cmd, channel, sessions::setPlanMode);
        String modelInput = turnInput.userContent(cmd, binding, imgNote, driftNote);
        AgentxTurnInput.Result assembled = turnInput.assemble(cmd, modelInput, binding, planEngaged,
                chatSeesImages, userImgs);
        return new Assembled(created.recording(), created.historyBefore(), created.startCursor(),
                planEngaged, assembled.modelInput(), assembled.multimodal(), assembled.goalActive());
    }
}
