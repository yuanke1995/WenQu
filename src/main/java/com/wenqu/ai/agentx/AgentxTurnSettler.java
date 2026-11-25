package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.TurnCloseout;
import com.wenqu.ai.agent.chat.TurnRecorder;
import com.wenqu.ai.agentx.turn.TurnSessionGateway;
import com.wenqu.ai.agentx.output.ModelOutputParser;
import com.wenqu.ai.engine.RunEventType;
import com.wenqu.ai.agentx.turn.TurnFollowLoop;
import com.wenqu.ai.agentx.turn.TurnInteractionCoordinator;
import com.wenqu.ai.agentx.AgentxTextUtil;
import io.agentscope.core.session.SessionEvent;
import com.wenqu.ai.service.GoalService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.TurnNotifyService;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 一轮跟随的结算（从 AgentxFollowRunner 抽出）：正文回填与失败判定、撞步数上限补收尾、
 * 目标轮终拍、落库 + done 帧 + 站内通知。
 *
 * <p>失败判据必须排在正文回填前面：回填不问这一轮是不是失败的那一跑，上游 401 的轮因此会顶着
 * 「答好了」的样子把上一轮的正文存进历史，失败一个字没提。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxTurnSettler {

    private final AgentxTurnFinisher finisher;
    private final FrameworkSessions sessions;
    private final GoalService goalService;
    private final TurnNotifyService turnNotify;
    private final java.util.function.IntSupplier maxIters;
    private final AgentxPendingAbandoner pendingAbandoner;

    public void settle(AgentxFollowCtx c) {
        settleFailure(c);
        settleMaxIters(c);
        settleGoalRound(c);
    }

    private void settleFailure(AgentxFollowCtx c) {
        // 正文取哪一份：优先本轮实际流出来的那一截（与发出去的 token 帧同源）；只有它为空、且这一轮
        // 本身没失败，才回退到会话里最后一条有正文的助手消息。先查会话会拿到<b>上一轮的答复</b>：
        // 被叫停的那一跑在 Transcript 里没有 committed text，等于拿别人的话给这一轮存档。
        // 流末尾留着没定性的尾巴先归位（它可能只是正文的最后几个字）
        ModelOutputParser.Delta finishTail = c.recording.related.finish();
        c.recording.answer(finishTail.text());
        c.recording.process(finishTail.process());
        String answer = c.recording.answerText();
        // 这一轮到底有没有真的流出过正文——下面撞步数上限那一段要靠它决定「补一段收尾」还是「别补」：
        // 空正文时下面那条回退取的就是框架收尾消息本身，再补一遍等于把同一段话印两次
        final boolean streamedBody = !c.answer.isBlank();
        // 失败判据必须排在回填前面：回填不问这一轮是不是失败的那一跑，上游 401 的轮因此会顶着
        // 「答好了」的样子把上一轮的正文存进历史，失败一个字没提。
        String failure = AgentxHistoryText.runFailure(c.session, c.current);
        // 上游把连接直接收了、没给带 finishReason/usage 的末块：框架那一跑看到的是「正常结束」，
        // executionError 为空、done 帧照发——屏幕上是一段凭空短掉的正文，刷新后连「这一轮断过」
        // 都读不出来。
        // 框架不报，就只能问渠自己报：这个判据出自模型出口那一层，不靠上游错误文本。
        if (c.failure == null && c.turnModel != null && c.turnModel.abruptCloses() > c.abruptBefore) {
            c.failure = AgentxRunFailure.UPSTREAM_STREAM_CUT;
            log.warn("[FAIL-LOUD] 本轮上游中途断流（流出过正文但没有末块）: session={} turn={}",
                    c.cmd.getSessionId(), c.turnId);
        }
        // 界面那一句人话（emitRunFailure 生成的），通知要抄它而不是抄上游原始串：
        // 原始串形如「HTTP transport error during streaming: HTTP request failed with status 403…」，
        // 是英文、带响应体碎片，用户在铃铛里读不出「该充值还是该换模型」
        String spokenFailure = null;
        // 框架把「重试烧完」压成一句 Retries exhausted: 2/2，真正的 429/额度文案留在它挂住的
        // 那次失败里；只看这句会把「额度耗尽」说成「模型无响应」，指的方向正好反了。
        // 只往<b>已判定失败</b>的那一句并原文——重试成功的那一轮同样会记一次错误，
        // 拿它当失败就是把答好了的一轮报成故障。
        // 并原文也只认「Retries exhausted」这一句本身：doOnError 对每次失败的尝试都计数，
        // 重试救回的旧错也在 modelErrors 差值里——只看差值就会把无关的 429 拼上断流轮，
        // 而 emitRunFailure 对断流哨兵是<b>全等</b>匹配，一拼就失配、断流文案永远不再出现。
        if (c.failure != null && c.turnModel != null && c.turnModel.modelErrors() > c.modelErrBefore
                && c.failure.toLowerCase(java.util.Locale.ROOT).contains("retries exhausted")) {
            String detail = c.turnModel.lastModelError();
            if (detail != null && !detail.isBlank() && !c.failure.contains(detail)) {
                c.failure = c.failure + " | " + detail;
            }
        }
        if (c.answer.isBlank() && c.failure == null) c.answer = AgentxHistoryText.stripFallbackBody(
                c.recording, AgentxHistoryText.lastAssistantText(c.session, c.historyBefore));
        // 框架那一跑本身失败（上游 402 额度、网关异常、运行被拒……）必须说出来：它只发一条
        // 「done + 空正文」，用户看到的是「它答了个空」，而不是「这次没答出来，原因是额度不足」。
        // 失败判据用框架自己留的 executionError / 任务终态。
        if (c.loop.neverLaunched()) {
            // 单独一句而不是套用「AI 回复失败，请稍后重试」：这一轮模型根本没被调用，重试同样的坑还在，
            // 得让用户知道该换个动作
            c.pool.registrar().degrade("frameworkNotLaunched", "框架引擎本轮未启动执行（提交停在队列里）");
            c.channel.emit(RunEventType.ERROR,
                    "框架引擎本轮没有开始执行（模型未被调用），请重试；反复出现请新建一条会话");
            log.warn("[FAIL-LOUD] 本轮 submit 始终未被框架启动: c.session={} turn={} 空等 {}ms",
                    c.cmd.getSessionId(), c.turnId, System.currentTimeMillis() - c.startedAt);
        } else if (!c.loop.stopped() && c.failure != null) {
            // 不看正文空不空：跑出过半截正文的那一跑同样是被 402／网关异常打死的。若只在
            // c.answer 空时才出声，症状是「气泡照报已完成、正文停在半句」，而错误卡与「这一轮中断」
            // 那句状态全都没长出来——用户得自己发现它没说完。
            // 叫停那一支不套用：那是用户自己按的，再说一句「AI 回复失败」就是把他的动作报成故障。
            c.spokenFailure = AgentxRunFailure.emit(c.pool, c.channel, c.failure);
        }
    }

    private void settleMaxIters(AgentxFollowCtx c) {
        // 框架撞步数上限时补的那条收尾正文要接回来：它走 SUMMARY 用途，被 translate() 那道
        // 「只透传 REASONING」的闸挡在流外（那道闸是为压缩摘要与记忆提炼加的，那两样确实不该进聊天流）。
        // 不接的表现：正文停在半句，气泡照报「已完成」，而它其实一步都没做完——沙盒里已经生成的
        // 文件也没机会交付出去。只在<b>流里确有正文</b>时补：空正文那一支上面已经把这条收尾消息
        // 回退取走了（同一个 ModelOutputParser 喂两遍还会把推荐芯片数成两份）。
        String wrapUp = AgentxHistoryText.maxIterationsWrapUp(c.session, c.historyBefore);
        boolean cutByMaxIters = wrapUp != null && !c.loop.stopped() && !c.loop.timedOut() && !c.loop.neverLaunched() && c.failure == null;
        if (c.cutByMaxIters && c.streamedBody) {
            // 过同一个剥离器：这条收尾正文同样可能被模型塞进 <related>／<process>，
            // 而推荐芯片只在 related 那一份状态上算，绕过它等于这一轮最后那颗芯片凭空少一源
            String extra = AgentxHistoryText.stripFallbackBody(c.recording, wrapUp).strip();
            if (!extra.isEmpty() && !c.answer.contains(extra)) {
                String tail = "\n\n" + extra;
                c.answer = c.answer + tail;
                c.recording.answer(tail);
            }
        }
        if (c.cutByMaxIters) {
            c.channel.emit(RunEventType.WARN, "这一轮的推理步数已用满（上限 " + maxIters.getAsInt()
                    + " 步），活没干完就停了。发一句「继续」可以接着做");
        }
        // 卡片还开着、且没有被判超时：这一轮只是暂时没人跟——用户答了会有新的跟随者把它跑完并落库。
        // 这时候落一条「已中断」就是两件坏事：它说的话是假的（这轮并没有结束），等真正的答案
        // 来了历史里还多出一条气泡（同一个问题两个助手回答）。
        // 继续对话取代的那一轮不算「暂时没人跟」：中断之后不会再有跟随者，而这一版的「已被取代」
        // 必须随行落库（历史里要有这一版计划，新一轮也靠它取上一版），所以放行到 persist。
        boolean awaiting = !c.loop.timedOut() && !c.superseded && !c.pendingAtClose.isEmpty();
        long wallMs = System.currentTimeMillis() - c.startedAt;
        long machineMs = Math.max(0L, c.wallMs - c.interactions.humanWaitMs());
        // 报出去的「本轮耗时」说的是机器干活用了多久，不是这一轮在墙上挂了多久：人在计划卡上读了
        // 三分钟，读成「它答了三分多钟」就是把人自己的时间记到了机器账上。同一句里把两个数都留下，
        // 截断与卡死排障要看的是墙钟，成本与性能看的是机器时间——少一个数就必然有人拿错一个数对账。
        log.info("[TURN] 本轮收口: c.session={} 机器耗时={}ms 墙钟={}ms 开轮前准备={}ms 整轮墙钟={}ms 人工等待={}ms",
                c.cmd.getSessionId(), c.machineMs, c.wallMs, c.prepMs, c.wallMs + c.prepMs, c.interactions.humanWaitMs());
    }

    private void settleGoalRound(AgentxFollowCtx c) {
        // 目标模式轮终一拍：记账 → 判完成/受阻/安全帽 →（由 onUserTurnEnd 决定）续跑。
        // 放在 finish 之前：finish 会落库并发 done，目标态要先于那一帧定下来，前端才看得到一致的状态。
        // 工具调用数取本轮实例自己的步数闸（taken 是判过即占一步的口径，含被拒的）。
        // 本轮正文一起交给目标服务：助手在末尾写【目标完成】就是平台停下来的信号（见 GoalCopy.DONE_MARKER），
        // 写【目标受阻】则以受阻原因停住——没有验收标准，也没有让人逐条核验的中间态。
        int goalToolCalls = 0;
        AgentxStepBudget turnBudget = sessions.stepBudget(sessions.key(c.cmd));
        if (turnBudget != null) goalToolCalls = turnBudget.taken();
        // 没正常跑完的轮（额度/网关/没启动）不进目标续跑链：否则一次失败会被滚成无限重试。
        // 用户按了停止同理：停止就是「这一轮到此为止」的意思，不能自动再起一轮把停止撤销掉。
        boolean goalTurnHealthy = c.failure == null && !c.loop.neverLaunched();
        goalService.onRoundSettled(c.cmd.getSessionId(), c.cmd.getUserId(), c.turnId, c.machineMs, goalToolCalls,
                c.channel, c.answer, goalTurnHealthy, c.loop.stopped() || c.loop.stalled(), c.failure);
        // 收尾标记（【目标完成】/【目标受阻】…）是平台控制信号，落库与下发的正文里不出现：
        // 摘在 onTurnEnd 之后、finish 之前，用户读到的最后一条就是干净的交付说明。
        c.answer = com.wenqu.ai.service.GoalService.stripMarkers(c.answer);
        // 重启接续的轮：旧进程干到哪一步，新实例这边没有记录（AgentxTurnRecording 是新的一份），框架侧也没有
        // 待跑步骤——正文与过程两头空时，收尾判据会把整轮静默丢掉，历史里只剩提问、清单卡还写着已完成。
        // 这种轮必须落一句话交代清楚；本轮真跑出过东西（正文/工具/思考）就不算「空转」，交给正常收尾。
        c.recoveredNote = c.orphanRecovered && c.failure == null && c.answer.isBlank()
                && c.recording.toolCalls.isEmpty() && c.recording.thinking.isEmpty()
                && !c.loop.stopped() && !c.loop.timedOut() && !c.ranOutBudget && !c.loop.stalled()
                && !c.loop.neverLaunched() && !c.superseded && !c.awaiting
                ? TurnRecorder.RECOVERED_NOTE : null;
    }

    public void close(AgentxFollowCtx c, ModelRegistryService.ModelRoute route, String userMessageId) {
        finisher.finish(c.cmd, c.channel, c.pool, route, c.hooks.modelRef(), c.contextWindow, c.answer, c.recording,
                userMessageId, c.loop.stopped(),
                c.loop.timedOut(), c.ranOutBudget, c.loop.stalled(), c.awaiting, c.startedAt, c.interactions.humanWaitMs(), c.prepMs,
                c.session, c.historyBefore, c.turnId, c.planRecord, c.recoveredNote);
        // 站内通知（旁路：内部全捕获，写不进去只记 WARN，绝不影响这一轮收口）：长轮跑完时
        // 人多半已经切走或锁屏，只靠屏幕看得见等于「跑完没人知道」。
        // 「提前停了」与「没跑成」分两句说：已经写出一半正文的那一轮，用户真读到了东西，

        // 把它报成「没跑成」等于说那段话不存在。
        // 铃铛只说人话：c.spokenFailure 就是 emitRunFailure 生成的那一句。若在它为空时退回
        // `c.failure`——那是上游原始串（「HTTP transport error during streaming: HTTP request failed
        // with status 403…」，英文、带响应体碎片），用户在铃铛里读不出「该充值还是该换模型」，
        // 而用户自己叫停的那一轮也会被报成故障。原始串留给日志，不进通知。
        String earlyExit = com.wenqu.ai.agent.chat.TurnCloseout.earlyExitReason(
                c.spokenFailure, c.loop.neverLaunched(), c.loop.timedOut(), c.ranOutBudget, c.loop.stalled(), c.cutByMaxIters);
        turnNotify.onTurnSettled(c.cmd, c.turnId, c.machineMs, c.recording.toolCallList().size(),
                c.loop.stopped() || c.loop.stalled(), c.awaiting, earlyExit, !c.answer.isBlank(),
                c.turnId != null && c.turnId.startsWith(com.wenqu.ai.service.GoalService.GOAL_TURN_ID_PREFIX));
    }

    public void settlePlan(AgentxFollowCtx c) {
        // 计划卡裁决的界面终态：拒绝=deep_plan_cancelled、继续对话=deep_plan_superseded。不补的表现为
        // 用户已经拒了计划，那张卡却还挂着「等待中」（控制流仍归框架判，这里只补留痕帧与 done 标记）
        String planOutcomeRaw = sessions.takePlanOutcome(sessions.key(c.cmd));
        TurnInteractionCoordinator.PlanOutcome planOutcome =
                TurnInteractionCoordinator.planOutcome(c.planOutcomeRaw);
        boolean superseded = planOutcome == TurnInteractionCoordinator.PlanOutcome.SUPERSEDED;
        if (c.superseded) c.channel.emit(RunEventType.DEEP_PLAN_SUPERSEDED, "{}");
        else if (planOutcome == TurnInteractionCoordinator.PlanOutcome.CANCELLED) {
            c.channel.emit(RunEventType.DEEP_PLAN_CANCELLED, "{}");
        }
        // 整轮机器预算用尽而循环是自己退的：屏幕上这一轮已经收尾，框架那一跑却还活着——不叫停，
        // 下一步与下一段钱就记在没有人跟的那一跑上。中断是协作式的：整轮那道闸打不断在途调用，
        // 在途那一步跑完之后不再起新的模型调用；已经流出半截的正文照旧走下面的收尾落库，不丢。
        // 判据用同一个 c.machineMs——与上面 while 的退出条件一字不差，否则「退出了」与「预算用尽」
        // 会对不上，把正常答完的那一轮也报成超时。
        boolean ranOutBudget = false;
        if (!c.loop.stopped() && !c.loop.neverLaunched() && !c.superseded
                && TurnFollowLoop.budgetExhausted(AgentxTextUtil.machineMs(c.startedAt, c.interactions.humanWaitMs(), c.interactions.waitingOnHuman(), c.interactions.waitBeganAt()), c.machineBudgetMs)) {
            c.session.interrupt();
            c.ranOutBudget = true;
            c.channel.emit(RunEventType.WARN, com.wenqu.ai.agent.chat.TurnRecorder.TURN_BUDGET_NOTE);
            log.warn("[FAIL-LOUD] 整轮机器预算用尽，已叫停框架那一跑: session={} turn={} 预算={}ms",
                    c.cmd.getSessionId(), c.turnId, c.machineBudgetMs);
        }
        // 跟随循环退场时框架那一跑还活着：这不是正常收尾。它还会继续调模型、继续计费，
        // 而下一条新轮会在它尚未提交的上下文上从零开始。
        // 预算那一条上面已经叫停过；这里兜住其余退出支路，确保「引擎的轮」与「框架的 run」同生共死。
        if (!c.loop.stopped() && !c.loop.neverLaunched() && !c.superseded && !c.ranOutBudget && !c.loop.timedOut()
                && (c.current == null || !c.current.settled())
                && !c.session.inspect().activeRuns().isEmpty()) {
            c.session.interrupt();
            log.warn("[FAIL-LOUD] 跟随循环退场时框架那一跑仍在执行，已叫停: session={} turn={}",
                    c.cmd.getSessionId(), c.turnId);
        }
        buildPlanRecord(c);
    }

    private void buildPlanRecord(AgentxFollowCtx c) {
        java.util.List<Map<String, Object>> planVersionList = c.recording.planVersionsCopy();
        if (c.planOutcomeRaw != null && !planVersionList.isEmpty()) {
            Map<String, Object> last = planVersionList.get(planVersionList.size() - 1);
            c.planRecord = new java.util.LinkedHashMap<>();
            c.planRecord.put("plan", last.get("plan"));
            c.planRecord.put("status", "approve".equals(c.planOutcomeRaw) ? "approved"
                    : "supersede".equals(c.planOutcomeRaw) ? "superseded" : "rejected");
            // 版本项现在带 feedback（产出该版的修改意见）：刷新后 PlanViewer 的版本轨也能逐版回看
            c.planRecord.put("versions", planVersionList);
            // 计划血缘：这一版自己的稳定 id + 它基于哪一版旧计划（旧计划那一轮的执行统计也落库，
            // 刷新/换设备后新卡仍能显示「调整自 v1 · 已执行 2/5」，不必再依赖本机内存）。
            if (c.recording.planApprovalIdValue() != null) c.planRecord.put("id", c.recording.planApprovalIdValue());
            if (c.cmd.getPlanBaseId() != null && !c.cmd.getPlanBaseId().isBlank()) {
                c.planRecord.put("planBaseId", c.cmd.getPlanBaseId());
                c.planRecord.put("planBaseVersion", c.cmd.getPlanBaseVersion());
                c.planRecord.put("planBaseDone", c.cmd.getPlanBaseDone());
                c.planRecord.put("planBaseTotal", c.cmd.getPlanBaseTotal());
                c.planRecord.put("planBaseFailed", c.cmd.getPlanBaseFailed());
            }
        }
    }

    public void settlePending(AgentxFollowCtx c) {
        // 机器预算用尽退场、卡片还挂着：这一轮不会再有跟随者（超时回收要靠活着的那个循环，它已经退了）。
        // 不判终态就是两件坏事，而且第二件正是用户报的那个症状：
        //  1) 下面 `c.awaiting` 那一支刻意不落库 → 用户刷一下页面，整半轮过程（思考/工具卡/引用/用量）
        //     在库里根本不存在，界面上凭空消失，只剩第一句提问；
        //  2) 卡片留在 PENDING → 恢复端点还能把它重建出来，但答了没人接，成了「点了没反应」。
        // 所以按超时收口：待办落 TIMEOUT、这一轮走 timedOut 那条落库与文案（半程内容据此可见）。
        // 收尾的权威待办快照只做一次：pending() 按 256 一批从 0 倒走整条 commit 链，万级事件
        // 日志上一次就是十几秒，下面四处各读一遍等于
        // 四份白付。abandon 结算掉的卡在快照里仍挂着——那几支已经把 timedOut 置真，c.awaiting
        // 的 !timedOut 正好把这笔陈旧性挡掉；真还在等人的卡快照如实保留，语义不变。
        Map<String, SessionEvent> pendingAtClose = c.session.pending();
        if (!c.loop.stopped() && !c.loop.timedOut() && !c.superseded && !c.loop.neverLaunched() && !c.pendingAtClose.isEmpty()) {
            c.channel.emit(RunEventType.WARN, "本轮已超时结束，等你确认的那一步已作废");
            c.loop.timeout();
        }

        if (!c.loop.stopped() && !c.loop.timedOut() && !c.superseded && c.pendingAtClose.size() > 0) {
            // 「答完会自动接着跑」只对还活着的等待成立：超时那一支已经把待办落成终态，
            // 再说这句就是在承诺一件不会发生的事
            c.channel.emit(RunEventType.WARN, "有工具在等你确认，答完会自动接着跑（可稍后回来接着看它）");
        }
        // 这一轮到此不再有人跟随：还挂在人身上的待办当场收口（框架结算 + 卡片终态 + 待办行落库）。
        // neverLaunched 那一支不碰——那些待办属于**上一轮**，卡片可能正等用户答，本轮没启动不是它的错。
        if (!c.loop.neverLaunched()) pendingAbandoner.abandon(c.session, c.cmd, c.channel, c.recording, c.loop.stopped() || c.loop.stalled(), c.pendingAtClose);
        sessions.setPlanMode(c.cmd, false);
    }
}
