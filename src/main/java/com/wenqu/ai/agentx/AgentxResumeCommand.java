package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.SseRunEventChannel;
import com.wenqu.ai.service.CollectingSseEmitter;
import lombok.RequiredArgsConstructor;

/**
 * 续跑轮的命令与出口（从 AgentxContinuationOpener 抽出）。
 *
 * <p>续跑轮没有问题正文，只有会话归属与（可选的）计划/游客口径；作答方按 agentOverride →
 * 会话列 → 平台默认 的顺序解析。
 *
 * <p>出口挂在收集型 emitter 上：这一跑未必有真实观众，但事件仍走同一条 sendSseEvent
 * （账本、运行总线、落库都不变），断线重连那一份 HTTP 响应才是观众。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxResumeCommand {

    private final AgentxPromptContext promptCtx;
    private final WenquAgentxTools tools;

    public ChatCommand build(String sessionId, String userId, String guestUid, boolean planFirst,
                             String agentOverride) {
        String resolvedAgentId = agentOverride == null || agentOverride.isBlank()
                ? promptCtx.agentIdOfSession(sessionId) : agentOverride;
        return ChatCommand.builder().sessionId(sessionId).question("")
                .userId(userId == null || userId.isBlank() ? "guest" : userId)
                .agentId(resolvedAgentId)
                .guestMode(guestUid != null && !guestUid.isBlank())
                .planMode(planFirst)
                .guestUid(guestUid)
                .build();
    }

    public RunChannel channel(String sessionId) {
        return new SseRunEventChannel(tools.runtime(), new CollectingSseEmitter(), sessionId);
    }
}
