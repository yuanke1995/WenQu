package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.GoalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 开轮前置：本轮 turnId、生效策略日志、问题落库、装配前停止预检（从 AgentscopeChatEngine 抽出）。
 *
 * <p>问题必须先落库再建实例：建实例会先装配沙盒、可能卡很久，而侧栏只列「有消息的会话」——
 * 等建完再落库的话，用户中途停止/刷新时这一问还没进库，整条会话会被当成空会话隐藏。
 * 停止预检必须排在落库之后：早于落库就返回会让用户「问出去了，刷新后连问题都没有」。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxTurnPrelude {

    private final WenquAgentxTools tools;
    private final GoalService goalService;
    private final AgentxTurnSupport turnSupport;

    /** 前置结果：turnId、用户消息 id、是否已被用户停止（true 时调用方应直接返回）。 */
    public record Result(String turnId, String userMessageId, boolean stopped) {
    }

    public Result prepare(ChatCommand cmd, RunChannel channel, String turnId,
                          java.util.List<String> userImgUrls) {
        log.info("[PERM] session={} turn={} source={} depth={} agent={} {}",
                cmd.getSessionId(), turnId, cmd.getSource(), cmd.getSubagentDepth(), cmd.getAgentId(),
                tools.policyFor(cmd).describe());
        // 附件/@/#历史引用随用户消息一并持久化，刷新后回显不丢
        String userMessageId = cmd.isRegenerate() || cmd.isFromSteer() ? null
                : turnSupport.appendQuestion(cmd, turnId, userImgUrls);
        if (!tools.stopRequested(cmd)) return new Result(turnId, userMessageId, false);
        log.info("[agentx] 本轮在装配前已被用户停止，跳过实例与模型调用: session={} turn={}",
                cmd.getSessionId(), turnId);
        // 目标态的收尾照常走：onRoundSettled(stopped=true) 会把目标轮结算并转暂停，
        // 跳过它等于用户按了停止、目标还在后台接着跑。
        goalService.onRoundSettled(cmd.getSessionId(), cmd.getUserId(), turnId, 0L, 0,
                channel, "", true, true, null);
        goalService.onUserTurnEnd(cmd.getSessionId(), cmd.getUserId(), turnId);
        return new Result(turnId, userMessageId, true);
    }
}
