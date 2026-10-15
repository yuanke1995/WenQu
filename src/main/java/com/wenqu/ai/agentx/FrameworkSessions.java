package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

/**
 * 按 {@code (userId, sessionId)} 持有框架会话（方案 §6 风险3 的最终形态：AgentSession 原生接管轮次）。
 * <p>为什么要常驻而不是「每请求建一个用完就关」：文档与实测都确认，待办持久化（{@code pending()}）、
 * 答复后续跑（{@code respond} 自动接续原任务与工具调用，应用不必拼工具 ID）、断线按游标读增量事件
 * 这几件事挂在 {@code AgentSession} 上，而它必须活到后台执行结束。用完即关的实例等于把这些能力又丢掉。
 * <p>与 per-user 模型覆盖的冲突这样解：实例按「会话本轮使用的模型引用」键控，
 * <b>模型换了就重建实例并接管同一条会话</b>——Phase 0 已实测换一个全新实例、沿用同一
 * {@code (userId, sessionId)} 能读到完整历史并接着答，所以接续不靠实例永生，靠框架的会话存储。
 * <p>待办的归属同时落 {@code c_ai_tool_approval}，所以进程重启后还能从表里取回 {@code (userId, sessionId,
 * turnId)} 重建实例接回那一张卡（{@link ResumeHandler#resume}）——唤醒句柄在 {@link AgentSession} 上、
 * 随进程没了，但状态在框架的 stateStore 里，缺的只是「把实例按归属重建回来」这一步。
 */
@Component
public class FrameworkSessions {

    private static final Logger log = LoggerFactory.getLogger(FrameworkSessions.class);

    /** 常驻实例上限：超出的按最久未用关闭。框架实例带工作区与传输层，不能有上限地攒。 */
    private static final int MAX_HELD = 200;

    private record Held(HarnessAgent agent, AgentSession session, String modelRef) {
    }

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();
    /** 待办 → 所属会话：{@code /tool-approval/{approvalId}} 只带得到审批号，得靠它找回是哪条会话。 */
    private final Map<String, String> pendingOwner = new ConcurrentHashMap<>();
    /**
     * 每条常驻会话的步数预算闸：middleware 只能在 build 期登记进实例，而它的计数按「轮」归零，
     * 所以引擎要在开轮时拿到<b>这一个实例自己的</b>那把闸——放进本表随实例一起淘汰，
     * 模型切换重建实例时旧的自动作废（键相同、直接覆盖）。
     */
    private final Map<String, AgentxStepBudget> stepBudgets = new ConcurrentHashMap<>();
    /**
     * 哪几条会话此刻有「把这一轮跟到收尾」的循环。
     * <p>这不是实例存活判据（那是 {@link #held}）：循环有墙钟上限、也会因超时退出，退了实例还在。
     * 答复端点要问的是「我这次答完，有没有人接着把这一轮跑完并落库」——只有这个表能答。
     */
    private final Map<String, Boolean> followers = new ConcurrentHashMap<>();
    /**
     * 待办的持久化落点（同一张 {@code c_ai_tool_approval}）。
     * <p>不落这一行的后果是用户看得见的具体问题：<b>刷新页面后框架引擎的确认卡/提问卡/计划卡取不回来</b>
     * ——三个 {@code /*&#47;pending} 恢复端点都是按这张表列的，legacy 有、框架原先没有。
     */
    private final com.wenqu.ai.agent.hitl.HitlDecisionService hitl;

    public FrameworkSessions(com.wenqu.ai.agent.hitl.HitlDecisionService hitl) {
        this.hitl = hitl;
    }

    /**
     * 进程重启后的续跑入口（由引擎在装配期登记）。
     * <p>为什么要它：待办的唤醒句柄在 {@code AgentSession} 上，进程一重启就没了。此时若只回一句
     * 「已批准」，用户看到的是一次成功的操作加上一件什么都没发生的事；而框架的会话状态本来就持久化在
     * {@code stateStore} 里，完全可以从表里取回归属、把这条待办重新接上跑完并落库。
     */
    public interface ResumeHandler {
        /**
         * @param kind approval（工具确认）/ ask（提问卡）/ plan（计划卡）
         * @return 接续出来的那条任务；null = 接不动，调用方按失效处理
         */
        AgentSession.Task resume(String sessionId, String userId, String turnId, String requestId,
                                 String kind, boolean approved, String reason, String answer);

        /**
         * 答复已经送进框架会话，但这一轮没有活着的跟随者——补起一个把它跟到收尾并落库。
         * <p>为什么要它：跟随循环有墙钟上限（{@code chat.sseTimeoutMs}），到点就退；退了之后卡片仍可答复，
         * 框架也会重新起一轮执行，但没有人在那儿读事件、也没有人落库，用户看到的就是一次「已批准」
         * 加上一件什么都没发生的事（真机实测：续跑跑完了、正文却没落库）。
         */
        void follow(String sessionId, String userId, String turnId);
    }

    private volatile ResumeHandler resumeHandler;

    public void bindResume(ResumeHandler handler) {
        this.resumeHandler = handler;
    }

    /**
     * 进程重启后接回一条待办：归属只在这张表里，所以先查行再交给引擎重新起一轮。
     *
     * @return null = 没有这条记录或引擎接不动（调用方回落旧通道，按失效处理）
     */
    AgentSession.Task resume(String requestId, String kind, boolean approved, String reason, String answer) {
        ResumeHandler h = resumeHandler;
        if (h == null || requestId == null) return null;
        var row = hitl.findApproval(requestId);
        if (row == null) return null;
        AgentSession.Task task = h.resume(row.getSessionId(), row.getUserId(), row.getTurnId(), requestId,
                kind, approved, reason, answer);
        if (task == null) return null;
        // 接回来之后这条待办又回到「进程内有主」的状态：归属、终态都在这一处落，三条通道不各写一遍
        pendingOwner.put(requestId, (row.getUserId() == null || row.getUserId().isBlank()
                ? "guest" : row.getUserId()) + "|" + row.getSessionId());
        hitl.resolveFrameworkPending(requestId, "ask".equals(kind) ? "ANSWERED"
                : (approved ? "APPROVED" : "REJECTED"), null, answer != null ? answer : reason);
        log.info("[agentx] 重启后接回待办 {}（{}）：已重新起一轮续跑 session={}",
                requestId, kind, row.getSessionId());
        return task;
    }

    /**
     * 框架待办出现时登记归属并落库。
     *
     * @param toolName 确认类填真实工具名；提问卡填 {@code askUser}；计划卡填 {@code planApproval}（与手写侧同词，
     *                 恢复端点就是按这两个名字分流的）
     */
    public void recordPending(ChatCommand cmd, String sessionKey, String turnId, String requestId,
                              String toolName, String requestArgs) {
        if (requestId == null || requestId.isBlank()) return;
        pendingOwner.put(requestId, sessionKey);
        hitl.recordFrameworkPending(requestId, cmd.getSessionId(), cmd.getUserId(), turnId, toolName, requestArgs);
    }


    /**
     * 取这一轮的会话。{@code agentFactory} 只在需要新建或模型已换时被调用（框架侧建实例有成本，
     * 每请求都建就失去常驻的意义）。
     */
    public AgentSession open(ChatCommand cmd, String modelRef, Function<RuntimeContext, HarnessAgent> agentFactory) {
        String key = key(cmd);
        Held current = held.get(key);
        if (current != null && current.modelRef().equals(modelRef)) {
            touch(key);
            return current.session();
        }
        if (current != null) {
            // 模型已切换：旧实例不再能用（框架的模型只能在 build 期定），关掉并接管同一条会话
            closeQuietly(key, current);
            held.remove(key);
            log.info("[agentx] 会话 {} 模型切换 {}→{}，重建实例并接管同一会话", key, current.modelRef(), modelRef);
        }
        RuntimeContext ctx = RuntimeContext.builder()
                .userId(cmd.getUserId() == null || cmd.getUserId().isBlank() ? "guest" : cmd.getUserId())
                .sessionId(cmd.getSessionId())
                .build();
        HarnessAgent agent = agentFactory.apply(ctx);
        AgentSession session = agent.session(ctx);
        session.start();
        held.put(key, new Held(agent, session, modelRef));
        touch(key);
        evictOverflow();
        return session;
    }

    /** 本轮新出现的待办登记归属，供答复端点找回会话。 */
    public void notePending(String sessionKey, Map<String, SessionEvent> pending) {
        for (String requestId : pending.keySet()) {
            pendingOwner.put(requestId, sessionKey);
        }
    }

    /**
     * 占住这一条会话的跟随位：占到才该起一轮续跑（占不到说明已经有人在跟，不该有两个循环读同一条会话——
     * 那会各落一条助手消息）。
     */
    public boolean reserveFollower(String sessionKey) {
        return sessionKey != null && followers.putIfAbsent(sessionKey, Boolean.TRUE) == null;
    }

    /**
     * 开新一轮问答时强制占位：这一路是权威的提问者（轮级互斥已经保证同会话同时只有一轮在答），
     * 不能因为上一轮的续跑还没让位就不跟。
     */
    public void claimFollower(String sessionKey) {
        if (sessionKey != null) followers.put(sessionKey, Boolean.TRUE);
    }

    /** 跟随循环退出（正常收尾、超时、叫停都算）：让位，之后再有答复就补起一个新的。 */
    public void releaseFollower(String sessionKey) {
        if (sessionKey != null) followers.remove(sessionKey);
    }

    public boolean hasFollower(String sessionKey) {
        return sessionKey != null && followers.containsKey(sessionKey);
    }

    /**
     * 答复之后确认「有人把这一轮跟到收尾」，没有就补起一个。
     * <p>归属记录（{@code c_ai_tool_approval} 那一行）在这一刻已经是终态，所以 turnId 只能从表里取——
     * 它是续跑那一跑的记账身份（待办、任务清单、步数预算都挂在它下面）。
     */
    private void ensureFollowing(String requestId) {
        ResumeHandler h = resumeHandler;
        if (h == null || requestId == null) return;
        var row = hitl.findApproval(requestId);
        if (row == null) return;
        // 该不该起、能不能起（同一条会话不许两个循环对着跟）由引擎那侧的原子占坑决定，这里只负责问一声
        h.follow(row.getSessionId(), row.getUserId(), row.getTurnId());
    }

    /**
     * 答复一张确认卡。
     *
     * @return false = 这个审批号不属于框架引擎的任何会话（交回人在回路的旧通道处理）
     */
    public AgentSession.Task respond(String approvalId, boolean approved, String reason) {
        String sessionKey = pendingOwner.remove(approvalId);
        Held h = sessionKey == null ? null : held.get(sessionKey);
        if (h == null) return resume(approvalId, "approval", approved, reason, null);
        // 框架自己找回待办所属的原任务与工具调用并安排接续执行，这里不拼工具 ID、也不再调 resume
        AgentSession.Task resumed = h.session().respond(approvalId,
                approved ? io.agentscope.harness.agent.session.SessionAnswer.approve()
                        : io.agentscope.harness.agent.session.SessionAnswer.reject(reason));
        hitl.resolveFrameworkPending(approvalId, approved ? "APPROVED" : "REJECTED", null, reason);
        log.info("[agentx] 待办已答复 requestId={} approved={} → 接续任务={}",
                approvalId, approved, resumed.commandId());
        ensureFollowing(approvalId);
        return resumed;
    }

    /**
     * 答复一个提问卡（问题类待办）。与确认卡同一条通道：框架自己找回是哪一次工具调用在等人。
     *
     * @return false = 该 askId 不属于框架会话（交回人在回路的旧通道）
     */
    public AgentSession.Task respondAsk(String askId, String answerText) {
        String sessionKey = pendingOwner.remove(askId);
        Held h = sessionKey == null ? null : held.get(sessionKey);
        if (h == null) return resume(askId, "ask", true, null, answerText);
        AgentSession.Task resumed = h.session().respond(askId,
                io.agentscope.harness.agent.session.SessionAnswer.text(answerText));
        hitl.resolveFrameworkPending(askId, "ANSWERED", null, answerText);
        log.info("[agentx] 提问已答复 askId={} → 接续任务={}", askId, resumed.commandId());
        ensureFollowing(askId);
        return resumed;
    }

    /**
     * 计划卡裁决后的「界面要定格成什么样」：批准/退回重出/继续对话/拒绝 在问渠界面上是四种终态，
     * 而框架只认 approve/reject 两态。这里把界面语义按会话留下一份，引擎收尾时取走补发对应帧——
     * 不补的表现为计划卡在用户已经拒绝之后还挂着「等待中」。
     */
    private final Map<String, String> planOutcomes = new ConcurrentHashMap<>();

    /** 计划卡的裁决：批准走 approve，其余按拒绝并把用户意见作为理由送回模型。 */
    public boolean respondPlanApproval(String planApprovalId, boolean approved, String reason) {
        return respondPlanApproval(planApprovalId, approved, reason, approved ? "approve" : "reject");
    }

    /**
     * @param outcome 界面终态：approve / revise（退回重出）/ supersede（继续对话、本版作废）/ reject（取消本轮）
     */
    public boolean respondPlanApproval(String planApprovalId, boolean approved, String reason, String outcome) {
        String sessionKey = pendingOwner.remove(planApprovalId);
        Held live = sessionKey == null ? null : held.get(sessionKey);
        String why = reason == null || reason.isBlank() ? "用户未批准这版计划" : reason;
        if (live == null) {
            // 重启后接回：续跑起来才算裁决送达；界面终态按接回后的归属键留下
            if (resume(planApprovalId, "plan", approved, why, null) == null) return false;
            planOutcomes.put(pendingOwner.getOrDefault(planApprovalId, ""), outcome == null ? "reject" : outcome);
            return true;
        }
        Held h = live;
        h.session().respond(planApprovalId, approved
                ? io.agentscope.harness.agent.session.SessionAnswer.approve()
                : io.agentscope.harness.agent.session.SessionAnswer.reject(why));
        hitl.resolveFrameworkPending(planApprovalId, approved ? "APPROVED" : "REJECTED", null, why);
        planOutcomes.put(sessionKey, outcome == null ? "reject" : outcome);
        log.info("[agentx] 计划卡已裁决 id={} approved={} outcome={}", planApprovalId, approved, outcome);
        ensureFollowing(planApprovalId);
        return true;
    }

    /** 引擎取走并清掉本轮的计划裁决（一次裁决只对应一次定格）。 */
    public String takePlanOutcome(String sessionKey) {
        return sessionKey == null ? null : planOutcomes.remove(sessionKey);
    }

    /**
     * 这张待办此刻是否真能被答（进程内还持有那条会话）。
     * <p>恢复端点的 {@code live=false} 语义是「卡片取回也没人能唤醒」（进程重启/多副本在别的实例上）；
     * 框架待办的唤醒句柄在 {@code AgentSession} 上，所以存活判据是这里而不是旧通道的内存表。
     */
    public boolean isLive(String requestId) {
        return requestId != null && pendingOwner.containsKey(requestId) && held.containsKey(pendingOwner.get(requestId));
    }

    /**
     * 等待预算用尽：把这条会话还挂着的待办落成 TIMEOUT 并摘掉归属。
     * <p>不落终态的话库里永远留着 PENDING——刷新后端点还会把一张已经作废的卡片重建出来，
     * 用户答了才发现什么都没发生。超时一律不替用户代答（既有口径），这里只改状态。
     */
    public void markPendingTimeout(String sessionKey) {
        if (sessionKey == null) return;
        for (Map.Entry<String, String> e : new java.util.LinkedHashMap<>(pendingOwner).entrySet()) {
            if (!sessionKey.equals(e.getValue())) continue;
            hitl.resolveFrameworkPending(e.getKey(), "TIMEOUT", null, null);
            pendingOwner.remove(e.getKey());
        }
    }

    /**
     * 按「这一轮」进出计划模式。实例是按会话常驻的，所以计划模式不能在 build 期定死；
     * 框架给的是 {@code enterPlanMode(userId, sessionId)} 这一对显式开关，用完必须退出，
     * 否则下一条不想要计划的轮会继承它。
     */
    public void setPlanMode(ChatCommand cmd, boolean on) {
        Held h = held.get(key(cmd));
        if (h == null) return;
        String userId = cmd.getUserId() == null || cmd.getUserId().isBlank() ? "guest" : cmd.getUserId();
        try {
            if (on) {
                h.agent().enterPlanMode(userId, cmd.getSessionId());
                // 计划模式是不是真的进了，直接决定 plan_* 那一族工具能不能被模型调用
                // （框架的 PlanModeMiddleware 会把不在计划位上的计划工具调用滤掉，症状是「空补全」而不是报错）
                log.info("[agentx] 计划模式已开启？key={} active={}", key(cmd),
                        h.agent().isPlanModeActive(userId, cmd.getSessionId()));
            }
            else if (h.agent().isPlanModeActive(userId, cmd.getSessionId())) {
                h.agent().exitPlanMode(userId, cmd.getSessionId());
            }
        } catch (Exception e) {
            log.warn("[agentx] 计划模式切换失败 on={}: {}", on, e.toString());
        }
    }

    /**
     * 手动 /compact 在框架引擎下的落点：这一条会话下一次建实例时强制压缩。
     * <p>为什么是「下一次」而不是「当场」：框架的压缩长在推理循环里（{@code CompactionMiddleware} 在
     * 每次模型调用前判阈值），它没有「把已有会话就地压一次」的入口。而 /compact 要的结果就是
     * 「下一次提问别再背着那些旧对话」——在下一次提问开跑之前压，效果与用户要的是同一件事。
     */
    private final java.util.Set<String> compactRequests = ConcurrentHashMap.newKeySet();

    /** 登记一次强制压缩并丢掉常驻实例（下一轮会带新配置重建；会话状态在 stateStore 里，不丢历史）。 */
    public void requestCompact(String userId, String sessionId) {
        String key = (userId == null || userId.isBlank() ? "guest" : userId) + "|" + sessionId;
        compactRequests.add(key);
        Held h = held.remove(key);
        if (h != null) {
            order.remove(key);
            closeQuietly(key, h);
        }
    }

    /** 取走并清掉这次强制压缩（一次请求只对应一次生效）。 */
    public boolean takeCompactRequest(String sessionKey) {
        return sessionKey != null && compactRequests.remove(sessionKey);
    }

    /** 建实例时登记这一路会话的步数预算闸（引擎在 {@code buildAgent} 里回调）。 */
    public void noteStepBudget(String sessionKey, AgentxStepBudget budget) {
        if (sessionKey != null && budget != null) stepBudgets.put(sessionKey, budget);
    }

    /** 取这一轮所属实例的步数预算闸；实例还没建出来时返回 null（开轮即会建，不该长期为空）。 */
    public AgentxStepBudget stepBudget(String sessionKey) {
        return stepBudgets.get(sessionKey);
    }

    /**
     * 按会话键直接答复一条待办——<b>不</b>走「归属查不到就重启接回」那一层。
     * <p>恢复路径自己要用这个口：那条路是先查表、重建实例、再 respond，若再走回带兜底的入口，
     * 就会自己绕回重启分支。
     */
    public AgentSession.Task respondRaw(String sessionKey, String requestId, String kind,
                                        boolean approved, String reason, String answer) {
        Held h = sessionKey == null ? null : held.get(sessionKey);
        if (h == null || requestId == null) return null;
        io.agentscope.harness.agent.session.SessionAnswer a = switch (kind == null ? "approval" : kind) {
            case "ask" -> io.agentscope.harness.agent.session.SessionAnswer.text(answer == null ? "" : answer);
            default -> approved ? io.agentscope.harness.agent.session.SessionAnswer.approve()
                    : io.agentscope.harness.agent.session.SessionAnswer.reject(
                            reason == null || reason.isBlank() ? "用户未批准" : reason);
        };
        return h.session().respond(requestId, a);
    }

    public void interrupt(ChatCommand cmd) {
        Held h = held.get(key(cmd));
        if (h != null) h.session().interrupt();
    }

    public String key(ChatCommand cmd) {
        return (cmd.getUserId() == null ? "guest" : cmd.getUserId()) + "|" + cmd.getSessionId();
    }

    private void touch(String key) {
        order.remove(key);
        order.addLast(key);
    }

    private void evictOverflow() {
        while (order.size() > MAX_HELD) {
            String oldest = order.pollFirst();
            Held h = oldest == null ? null : held.remove(oldest);
            if (h != null) closeQuietly(oldest, h);
        }
    }

    private void closeQuietly(String key, Held h) {
        try {
            h.session().close();
        } catch (Exception e) {
            log.warn("[agentx] 关闭会话失败 {}: {}", key, e.toString());
        }
        try {
            h.agent().close();
        } catch (Exception e) {
            log.warn("[agentx] 关闭框架实例失败 {}: {}", key, e.toString());
        }
    }
}
