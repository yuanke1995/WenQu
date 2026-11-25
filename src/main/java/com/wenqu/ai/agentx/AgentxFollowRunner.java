package com.wenqu.ai.agentx;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.TurnCloseout;
import com.wenqu.ai.agent.chat.TurnRecorder;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.engine.RunChannel;
import com.wenqu.ai.engine.RunEventType;
import io.agentscope.core.session.SessionEvent;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.TurnNotifyService;
import com.wenqu.ai.agentx.turn.TurnFollowLoop;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator;
import com.wenqu.ai.agentx.turn.TurnSessionGateway;
import com.wenqu.ai.agentx.output.ModelOutputParser;
import io.agentscope.harness.agent.session.AgentSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 跟着一轮跑到收尾（从 AgentscopeChatEngine 抽出）：有待办就等人答复，没待办就跟到最后一条任务，
 * 任务结算后再让出一拍复查；收尾时判失败/回填正文/落计划/结算目标/收口待办，最后交给
 * {@link AgentxTurnFinisher} 落库与下发 done。
 *
 * <p>独立成方法族是为了让「进程重启后接回一条待办」能复用同一段跟随逻辑——那一跑不是新问答。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxFollowRunner {

    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;
    private final GoalService goalService;
    private final TurnNotifyService turnNotify;
    private final AgentxTurnUsage turnUsage;
    private final AgentxPendingAnnouncer pendingAnnouncer;
    private final AgentxPendingAbandoner pendingAbandoner;
    private final AgentxTurnSupport turnSupport;
    private final AgentxTurnSnapshot turnSnapshotBuilder;
    private final AgentxLogDrain logDrain;
    private final AgentxTurnFinisher finisher;
    private final AgentxTurnSettler turnSettler;
    private final LongSupplier pendingBudget;
    private final IntSupplier turnSeconds;
    private final IntSupplier maxIters;
    private final AgentxLoopRunner loopRunner;

    /** 读一次「权威状态」的最长间隔（毫秒）：租约过期与 inbox 拒绝不落事件，只能定时回查。 */
    private static final long STATE_RECHECK_MS = 10_000L;
    /** 本轮提交离队的宽限：投递循环 100ms 一跳，超过这一段还没启动就是被闸门锁住了。 */
    private static final long LAUNCH_GRACE_MS = 30_000L;



    public void followTurn(AgentxTurnRun run) {
        AgentxFollowCtx c = new AgentxFollowCtx();
        c.session = run.session(); c.cmd = run.cmd(); c.channel = run.channel(); c.pool = run.pool();
        c.hooks = run.hooks(); c.turnId = run.turnId(); c.historyBefore = run.historyBefore();
        c.contextWindow = run.contextWindow(); c.current = run.current(); c.recording = run.recording();
        c.startedAt = run.startedAt(); c.prepMs = run.prepMs(); c.recovered = run.recovered();
        runLoop(c, run.route(), run.startCursor());
        turnSettler.settlePlan(c);
        turnSettler.settlePending(c);
        turnSettler.settle(c);
        turnSettler.close(c, run.route(), run.userMessageId());
    }

    /** 阶段一：跑跟随循环，收口人工等待，把游标推到 head。 */
    private void runLoop(AgentxFollowCtx c, ModelRegistryService.ModelRoute route, long startCursor) {
        loopRunner.run(c, route, startCursor);
    }

    /** 阶段二：计划卡裁决的界面终态 + 落 plan 列。 */

    /**
     * 裁决过的计划卡随助手消息落 plan 列（字段与手写侧 planSnapshot 同名：plan/status/versions/auto）。
     * 不落的表现：刷新后只剩「还在等确认」的那一张，已批过的每张计划都消失，
     * 用户回头看不出这一轮是按哪一版计划跑的。
     */

    /** 阶段三：预算用尽叫停、待办终态收口。 */

    /** 阶段四：正文回填、失败判定、人话、步数上限补收尾、目标终拍。 */


    /** 正文回填与失败判定（失败判据必须排在回填前面）。 */

    /** 撞步数上限：把框架补的那条收尾正文接回来。 */

    /** 目标模式轮终一拍：记账 → 判完成/受阻/安全帽 → 续跑由 onUserTurnEnd 决定。 */

    /** 阶段五：落库 + done 帧 + 站内通知。 */
}