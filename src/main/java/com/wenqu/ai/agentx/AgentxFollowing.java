package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.service.ModelRegistryService;
import io.agentscope.harness.agent.session.AgentSession;

/**
 * 续跑所需的一整套本轮上下文（从 AgentxContinuationOpener 的内嵌 record 提出为顶层类型）。
 *
 * <p>单独成类是为了让 {@link AgentxContinuationOpener} 不再同时持有这 12 个类型：
 * 打开器只负责「装配」，这份结果则被跟随器、续跑起手、目标轮共同读取。
 *
 * @param cmd            本轮命令（续跑重建）
 * @param channel        事件通道（挂在收集型 emitter 上）
 * @param pool           本轮引用池
 * @param route          模型路由
 * @param hooks          本轮钩子（游标、用量、咨询账本）
 * @param policy         执行档位
 * @param session        常驻会话实例（重建或复用）
 * @param historyBefore  本轮开跑前的会话记录条数（用量与增量的起点）
 * @param startCursor    本轮开跑前的 Session Log 游标
 * @param recording      本轮录制器
 * @param contextWindow  上下文窗口
 * @param recovered      是否走「重启接续」这条路
 * @author yuanke
 */
public record AgentxFollowing(ChatCommand cmd, RunChannel channel, WenquSourcePool pool,
                              ModelRegistryService.ModelRoute route, WenquAgentxTools.TurnHooks hooks,
                              ExecutionPolicy policy, AgentSession session, int historyBefore, long startCursor,
                              AgentxTurnRecording recording, int contextWindow, boolean recovered) {
}
