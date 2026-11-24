package com.wenqu.ai.agentx.team;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
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
    private volatile BiConsumer<ChatCommand, org.springframework.web.servlet.mvc.method.annotation.SseEmitter> runner;

    public TeamWakeService(SessionService sessionService, ChatRunRuntime runtime) {
        this.sessionService = sessionService;
        this.runtime = runtime;
    }

    /** 由问答侧绑定「起一轮」入口（chatService::chat），避免 TeamWakeService ↔ ChatService 构造环。 */
    public void bindRunner(
            BiConsumer<ChatCommand, org.springframework.web.servlet.mvc.method.annotation.SseEmitter> runner) {
        this.runner = runner;
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
                .source("team")
                .build();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                r.accept(cmd, new com.wenqu.ai.service.CollectingSseEmitter());
            } catch (Exception e) {
                log.warn("[TEAM] 唤醒失败 session={}: {}", sessionId, e.toString());
            }
        }, java.util.concurrent.ForkJoinPool.commonPool());
    }
}
