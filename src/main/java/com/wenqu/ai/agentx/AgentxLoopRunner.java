package com.wenqu.ai.agentx;

import com.wenqu.ai.agentx.turn.TurnFollowLoop;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator;
import com.wenqu.ai.service.GoalService;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;

/**
 * 跟随循环的驱动（从 AgentxFollowRunner 抽出）：建协调器与循环、装状态镜像、跑循环、
 * 收口人工等待、把事件游标推到 head。
 *
 * <p>读取起点与播报起点分开定：读取起点是「本轮第一条事件」（续跑那一跑里批准之前跑掉的工具
 * 也属于这一轮，从接回时刻起读会让落库的工具卡只剩半截）；播报起点是接回/开轮那一刻，
 * 历史帧不重放给观众。
 *
 * @author yuanke
 */
@RequiredArgsConstructor
public class AgentxLoopRunner {

    private static final long STATE_RECHECK_MS = 10_000L;
    private static final long LAUNCH_GRACE_MS = 30_000L;

    private final FrameworkSessions sessions;
    private final WenquAgentxTools tools;
    private final GoalService goalService;
    private final AgentxTurnUsage turnUsage;
    private final AgentxPendingAnnouncer pendingAnnouncer;
    private final AgentxTurnSupport turnSupport;
    private final AgentxTurnSnapshot turnSnapshotBuilder;
    private final AgentxLogDrain logDrain;
    private final LongSupplier pendingBudget;
    private final IntSupplier turnSeconds;

    public void run(AgentxFollowCtx c, ModelRegistryService.ModelRoute route, long startCursor) {
        // 恢复轮只对用户轮生效：目标轮的收尾由 GoalService 记账，多落一条「重启中断」的助手行会跟目标状态打架
        boolean orphanRecovered = c.recovered && (c.turnId == null || !c.turnId.startsWith("goal:"));
        long machineBudgetMs = turnSeconds.getAsInt() * 1000L;
        // 断流对账的读数起点：主链模型包装器按会话常驻、跨轮复用，本轮的断流次数只能取收尾读数
        // 减开轮读数（见 MeteredModel.abruptCloses）。实例还没建出来时是 null——那一跑连模型都没调，
        // 也就没有「断流」可判。
        MeteredModel turnModel = sessions.chatModel(sessions.key(c.cmd));
        long abruptBefore = turnModel == null ? 0L : turnModel.abruptCloses();
        long modelErrBefore = turnModel == null ? 0L : turnModel.modelErrors();
        // 接流快照改由本轮自己供：外壳开轮时只有「还没出字」那份空快照，而正文/工具卡/时间线
        // 只活在这里的 AgentxTurnRecording 与引用池里。不回填的表现为——生成中刷新，气泡接回来是一片空白。
        c.channel.offerSnapshot(() -> turnSnapshotBuilder.build(c.cmd, c.channel, c.pool, route, c.recording, c.turnId, c.startedAt, c.prepMs));
        // 人在回路等待/超时状态机：人工等待累计、三档预算与超时决策都收在协调器里，
        // 引擎只负责 begin/end 台账与超时后的发帧/回送。
        TurnInteractionCoordinator interactions = new TurnInteractionCoordinator(
                System::currentTimeMillis, kind -> pendingBudget.getAsLong());
        // 读取起点与播报起点分开定：
        //  - 读取起点是「本轮第一条事件」——续跑那一跑里批准之前跑掉的工具也属于这一轮，
        //    从接回时刻起读会让落库的工具卡只剩半截；
        //  - 播报起点是接回/开轮那一刻，历史帧不重放给观众（前端按 pos 去重只护得住同一条气泡，
        //    跨轮重放等于把上一轮的正文再打一遍）。
        TurnFollowLoop loop = new TurnFollowLoop();
        c.loop.seed(AgentxTextUtil.turnStartSeq(c.session, c.current == null ? null : c.current.turnId(), startCursor), startCursor);
        // 连续多步没有带回新信息：由引擎叫停（不再等模型自己停下来）
        // 等待预算用尽：卡片已落终态，收尾时不再对用户说「答完会自动接着跑」
        // 本轮有没有真的被框架启动。投递循环 100ms 一跳，正常几百毫秒内就离队；被闸门跳过时它会
        // 永远停在 queued，而「没启动」这件事框架自己不报错——不另设一条早于整轮预算的判据，
        // 用户看到的就只是「转了五分钟然后说超时」，一个字也没有。
        long launchDeadline = c.startedAt + LAUNCH_GRACE_MS;
        // 会话状态跟事件认（见 AgentxTurnMirror）。起轮种一次权威待办，之后每一跳只认这一跳读到的事件。
        AgentxTurnMirror mirror = new AgentxTurnMirror();
        c.mirror.resyncPending(c.session);
        // 兜底复查时刻：租约过期与 inbox 拒绝不落事件，只能按 STATE_RECHECK_MS 回查权威值

        // 一整轮里待办可能出好几次：批准之后模型仍可再要一次确认。所以这里不是「等一次答复」，
        // 而是跟随会话现状——有待办就等人，没待办就跟到最后一条任务，任务结算了还要再看一眼
        // 有没有新待办或接续出来的新任务，两者都没有才算这一轮跑完；只等一次就收尾的话，
        // 第二次确认的卡发不出去，正文也是空的。
        TurnFollowLoop.Hooks followHooks = new AgentxFollowHooks(c.session, c.cmd, c.channel, c.pool, c.turnId,
                c.historyBefore, c.contextWindow, c.recording, c.startedAt, c.interactions, c.loop, c.mirror, turnUsage,
                pendingAnnouncer, turnSupport, logDrain, sessions, tools, goalService);
        TurnFollowLoop.LoopResult loopResult = c.loop.run(new TurnFollowLoop.RunContext(
                c.current, c.machineBudgetMs, launchDeadline, STATE_RECHECK_MS, c.interactions), followHooks);
        c.current = loopResult.current();
        // 兜底：超时收尾 / stopRequested 跳出都可能停在「仍在等人」的状态上。
        // 不收口的话台账的 openWaits 永远 >0，之后这一轮算出来的机器耗时永远是负数/偏小，
        // 看门狗形同虚设；而引擎自己的墙钟已经进了下一轮比较，必须先把这段补齐再往下走。
        c.interactions.settle(() -> AgentxEmitUtil.endHumanWait(c.channel, c.recording));
        // 收尾把游标跑到 head：这一轮的尾巴增量不能再漏（镜像传 null——出轮之后没人读状态）
        logDrain.drain(c.session, c.loop.cursor(), c.loop.emitFrom(), c.turnId, c.channel, c.recording, null);
    }
}
