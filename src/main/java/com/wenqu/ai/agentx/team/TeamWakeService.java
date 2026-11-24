package com.wenqu.ai.agentx.team;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 团队消息的「唤醒承接器」：框架 {@code TeamsMiddleware} 把 mailbox 消息落进目标会话收件箱后，
 * 经 {@code MessageBus.enqueueWakeup} 回调到这里，由问渠起一轮把收件箱读出来处理。
 *
 * <p>这是宿主在框架里的角色（框架网关在别的部署里干这件事），不是重写唤醒语义：准入规则
 * 「已有活轮就只入队、不重复起轮」在这里收口——活轮的下一次模型步会由框架 InboxMiddleware
 * 自己 drain 收件箱。
 *
 * @author yuanke
 */
@Component
public class TeamWakeService {

    private static final Logger log = LoggerFactory.getLogger(TeamWakeService.class);

    private final SessionService sessionService;
    private final ChatRunRuntime runtime;
    /** 成员轮直播台：唤醒起的这一轮在无请求线程上跑，帧经它抄送给成员会话页里的观众。 */
    private final TeamMemberLiveFeed liveFeed;
    private volatile BiConsumer<ChatCommand, org.springframework.web.servlet.mvc.method.annotation.SseEmitter> runner;

    /**
     * 成员轮专用有界池（核心 4、峰值 16、队列 256）。
     * <p>原先挂在 {@code ForkJoinPool.commonPool()} 上：成员轮是长阻塞（在等人的审批卡上一挂就是几分钟），
     * 公共池被拖住会连带压垮同一个 JVM 里所有走公共池的杂活。队列满时<b>直接丢弃并记 warn</b>：
     * 消息已经在框架收件箱里，下一轮或用户手动发一句都能捞回来——比无声阻塞强。
     */
    private final java.util.concurrent.ThreadPoolExecutor executor;
    /** 同时在跑的唤醒数量上限：与池的峰值一致，超出的直接放弃（同上：消息不丢，只少一次自动唤醒）。 */
    private final Semaphore inFlight = new Semaphore(16);

    public TeamWakeService(SessionService sessionService, ChatRunRuntime runtime, TeamMemberLiveFeed liveFeed) {
        this.sessionService = sessionService;
        this.runtime = runtime;
        this.liveFeed = liveFeed;
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        this.executor = new java.util.concurrent.ThreadPoolExecutor(4, 16, 60L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "team-wake-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                (r, pool) -> log.warn("[TEAM] 唤醒队列已满，放弃一次自动唤醒（消息仍在收件箱，下一轮或手动发消息可捞回）"));
    }

    /** 由问答侧绑定「起一轮」入口（chatService::chat），避免 TeamWakeService ↔ ChatService 构造环。 */
    public void bindRunner(
            BiConsumer<ChatCommand, org.springframework.web.servlet.mvc.method.annotation.SseEmitter> runner) {
        this.runner = runner;
    }

    /**
     * 起一轮并把它跑完；无论正常/异常都收台。
     * <p>为什么用 {@code awaitDone} 而不是等回调：收集型通道没有完成回调，{@code awaitDone} 是它
     * 唯一能「等到 done/error 帧」的口径（也正因为如此，这里必须给它一个上限，不能在无观众时无限等）。
     */
    private void runWake(BiConsumer<ChatCommand, org.springframework.web.servlet.mvc.method.annotation.SseEmitter> r,
                         ChatCommand cmd, String sessionId) {
        if (!inFlight.tryAcquire()) {
            log.warn("[TEAM] 同时唤醒的成员已达上限，放弃一次自动唤醒（消息仍在收件箱）session={}", sessionId);
            return;
        }
        // 开台与起跑必须在同一个任务里、并且紧挨着：在提交线程上开台的话，
        // 排队期间进页面的观众会拿到一份空快照然后干等；在这里开，快照里至少已经有这一轮的开头帧。
        liveFeed.open(sessionId);
        com.wenqu.ai.service.CollectingSseEmitter sink = new com.wenqu.ai.service.CollectingSseEmitter();
        String ending = "这一轮已结束";
        try {
            r.accept(cmd, sink);
            // 上限取整轮预算的同量级：成员轮最长就跑这么久，超过就是异常残留，如实收台而不是挂着
            sink.awaitDone(60L * 60_000L);
        } catch (Exception e) {
            ending = "这一轮没跑成：" + e.getClass().getSimpleName();
            log.warn("[TEAM] 唤醒失败 session={}: {}", sessionId, e.toString());
        } finally {
            inFlight.release();
            liveFeed.finish(sessionId, ending);
        }
    }

    /** 唤醒一名成员：已有活轮则跳过（收件箱由活轮的 InboxMiddleware 读）。 */
    public void wake(String uid, String sessionId, String agentIdHint) {
        wake(uid, sessionId, agentIdHint, null);
    }

    /**
     * 唤醒一名成员并把「这一句」作为本轮问题带进去（null=用默认的“处理收件箱”文案）。
     * <p>为什么要显式带内容：框架 {@code TeamWakeups} 依赖已实例化的 TeamsMiddleware 才会
     * 把消息 push 进 inbox；刚 spawn、还没跑过的成员拿不到那个 hook，收件箱可能是空的。
     * 由宿主直接把消息正文作为本轮问题，保证成员一定看得到要它做什么。
     */
    public void wake(String uid, String sessionId, String agentIdHint, String question) {
        if (sessionId == null || sessionId.isBlank()) return;
        BiConsumer<ChatCommand, org.springframework.web.servlet.mvc.method.annotation.SseEmitter> r = runner;
        if (r == null) return;
        Session s = sessionService.sessionById(sessionId);
        if (s == null) return;
        boolean member = SessionService.KIND_TEAM.equals(s.getKind());
        // 成员轮唤醒成员；lead 会话唤醒根（成员完成任务后要能让 lead 接着汇总）。
        if (!member && !sessionService.isTeamEnabled(sessionId)) return;
        if (runtime.turnIdOf(sessionId) != null) {
            // 已经有活轮在跑：消息已入收件箱，活轮下一步会读到，不另起一轮
            return;
        }
        String agent = s.getAgentId() == null || s.getAgentId().isBlank() ? agentIdHint : s.getAgentId();
        String root = member ? s.getRootSessionId() : sessionId;
        String q = (question != null && !question.isBlank()) ? question
                : (member
                    ? "你收到了团队消息，请先处理收件箱里的内容（如需执行任务按任务板执行），处理完简要回报。"
                    : "团队成员有新的进展或任务结果，请查看任务板与收件箱，必要时继续推进，并把结论汇总给用户。");
        ChatCommand cmd = ChatCommand.builder()
                .sessionId(sessionId)
                .question(q)
                .userId(uid)
                .userImages(List.of())
                .attachments(List.of())
                .skills(List.of())
                .agentId(agent)
                .teamMode(true)
                .memberSessionId(member ? sessionId : null)
                .memberName(member ? s.getMemberName() : null)
                .teamRootSessionId(root)
                // 审批卡与放行按「根会话」归集；成员轮本身仍是顶层轮（parentSessionId 为空），
                // 通知/看门狗不该因为审批归集而被当成子轮静音。
                .rootSessionId(root)
                .source(com.wenqu.ai.service.TurnSource.TEAM)
                .build();
        // 直播：先开台再起跑。前端成员会话页订阅 /team/member/{sid}/live，拿到的就是这一轮
        // 逐字长出来的正文与逐步追加的工具卡——与主问答同一套事件契约，只换了出口。
        try {
            executor.execute(() -> runWake(r, cmd, sessionId));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // 池已关（进程收尾）或队列已满：这一轮没起跑，也就不开台——成员页照旧读历史
            log.warn("[TEAM] 唤醒被拒绝 session={}: {}", sessionId, rejected.toString());
        }
    }
}
