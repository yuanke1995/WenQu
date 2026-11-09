package com.wenqu.ai.startup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wenqu.ai.agentx.FrameworkSessions;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.mapper.ToolApprovalMapper;
import com.wenqu.ai.model.Message;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.model.ToolApproval;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 孤儿轮接续：把「发出去了、却没有任何人在跑」的那一轮重新跟到收尾。
 *
 * <p><b>为什么需要</b>：框架的常驻实例与跟随循环都在进程内存里，进程一停，跑一半的轮就没人管了——
 * 此前只有<b>挂在人身上的待办</b>能把自己接回来（用户答那张卡时按表里的归属重建实例）。
 * 没挂卡的长轮（调研到一半、脚本跑到一半）就这么丢了，而用户只看到自己发出去的问题旁边空着。
 * 框架自己留了接入口：{@code AgentSession#start()} 的语义就是「应用重启后开始派发持久化的排队任务」，
 * 缺的只是「有谁来把那条会话打开」。这个扫描器就是那一下。
 *
 * <p><b>判据为什么取消息表而不是事件账本</b>：账本有保留期、也有总开关（关掉就没有账），
 * 而「这条问题发出去之后这个会话再没有出现过回答」是用户自己看得见的事实。
 * 不按轮号找那条缺失的回答也是因为这个：消息表带逻辑删除，按轮号判会把「重新生成」取代掉的
 * 旧轮当成断了。
 *
 * <p><b>三类轮明确不接</b>：① 待办还开着的（{@code PENDING}）——那一轮在等人，不是断了，
 * 由卡片答复那条路接（{@code ensureFollowing}）；② 已经有跟随者的——同一条会话不许两个循环对着跟，
 * 这条由引擎侧的原子占位兜住，这里只是少做一遍无用重建；③ <b>已经交代过结局的</b>——那一问身上带着
 * 失败原因（{@code fail_msg}）或终止原因（{@code end_reason}）时，那一轮是按失败/叫停收的口，只是
 * 刻意没落助手行。重跑它等于替用户决定「再试一次」；而接续那一跑在新实例上读不到旧实例留的失败痕迹，
 * 正文回退会把上一轮的答复当本轮正文存档。
 *
 * <p>扫描按调度中心的节拍跑（内置节拍 2 分钟，服务一起来就先接一次），
 * 因此它出现在「系统设置 → 定时任务」的运行状态与执行日志里；节拍不做成配置项，也不提供暂停
 * （停掉等于让中断的轮永远停在「只有问题没有回答」那一行）。
 * 扫描线程没有请求上下文，租户行级过滤对它不注入谓词（见 {@code WenquTenantLineHandler}
 * 对「未绑定线程」那一条），所以这一眼看到的是全平台的孤儿轮；这是系统任务该有的口径。
 *
 * @author yuanke
 */
@Component
public class OrphanTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(OrphanTurnRunner.class);

    /** 单轮最多接这么多条（按最晚的问题排），剩下的下一轮再看。 */
    private static final int BATCH = 20;

    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;
    private final ToolApprovalMapper approvalMapper;
    private final com.wenqu.ai.mapper.AgentShareMapper shareMapper;
    private final FrameworkSessions sessions;
    private final com.wenqu.ai.service.ConfigService configService;
    /** 运行总线：用来区分「真孤儿」与「还活着的轮」（见 {@link #sweep()}） */
    private final com.wenqu.ai.service.ChatRunBus chatRunBus;

    /**
     * 最近一次扫描的结果（系统巡检读它）。
     * <p>为什么留痕而不只写日志：接续成功是「用户什么都没丢」，接续失败是「这条问题真的没人答了」，
     * 两种都值得让管理员一眼看到；日志会滚掉，巡检报告不会。
     */
    private volatile String lastSweepAt = "";
    private volatile int lastFound;
    private volatile int lastTaken;
    private volatile String lastError = "";

    public OrphanTurnRunner(MessageMapper messageMapper, SessionMapper sessionMapper,
                            ToolApprovalMapper approvalMapper, com.wenqu.ai.mapper.AgentShareMapper shareMapper,
                            FrameworkSessions sessions,
                            com.wenqu.ai.service.ConfigService configService,
                            com.wenqu.ai.service.ChatRunBus chatRunBus) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.approvalMapper = approvalMapper;
        this.shareMapper = shareMapper;
        this.sessions = sessions;
        this.configService = configService;
        this.chatRunBus = chatRunBus;
    }

    /** 最近一次扫描（时间、发现几条、接了几条、最后一次报错）。 */
    public record Sweep(String at, int found, int taken, String error) {
    }

    public Sweep lastSweep() {
        return new Sweep(lastSweepAt, lastFound, lastTaken, lastError);
    }

    /**
     * 扫一遍并接回孤儿轮，返回交回引擎的条数（调度中心按内置节拍 2 分钟调，
     * 服务起来后 20 秒先跑一次——重启正是这件事最常见的原因）。
     */
    public int sweep() {
        List<Message> orphans;
        try {
            orphans = findOrphans();
        } catch (Exception e) {
            // 接不回是运维事实，不该把调度线程带下去：下一轮还会再试
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("[RECOVER] 孤儿轮扫描失败（下一轮再试）: {}", e.toString());
            return 0;
        }
        lastSweepAt = LocalDateTime.now().toString();
        lastFound = orphans.size();
        if (orphans.isEmpty()) {
            lastTaken = 0;
            lastError = "";
            return 0;
        }
        int taken = 0;
        String error = "";
        for (Message ask : orphans) {
            // 这一轮在本副本上还活着（轮次把手或总线 run 还在）：它只是「慢」，不是孤儿。典型形态是
            // 沙盒装配卡住几分钟——消息表里当然还没有回答、也还没有 end_reason，但原线程马上会接着跑；
            // 这里再接一次就是同一问两条流水线。
            // 只看本副本：进程真的死过时这两处都是空的，重启后的首轮扫描照旧立刻接。
            boolean alive = com.wenqu.ai.agent.chat.TurnRegistry.handle(ask.getSessionId()) != null
                    || chatRunBus.running(ask.getSessionId()) != null;
            if (alive) {
                log.info("[RECOVER] 这一轮还在跑（可能卡在装配期），不按孤儿接: session={} turn={}",
                        ask.getSessionId(), ask.getTurnId());
                continue;
            }
            try {
                Owner owner = ownerOf(ask.getSessionId());
                if (owner == null || owner.uid() == null || owner.uid().isBlank()) {
                    lastError = "会话归属解析不出（访客会话没有作答方记录？）";
                    log.warn("[RECOVER] 接不动：会话归属解析不出 session={} turn={}",
                            ask.getSessionId(), ask.getTurnId());
                    continue;
                }
                sessions.requestFollow(ask.getSessionId(), owner.uid(), ask.getTurnId(), true, owner.guestUid());
                taken++;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("[RECOVER] 接续失败 session={} turn={}: {}",
                        ask.getSessionId(), ask.getTurnId(), e.toString());
            }
        }
        lastTaken = taken;
        lastError = error;
        log.warn("[RECOVER] 发现 {} 条没人跟的轮，已交给引擎接续 {} 条（进程是不是中途停过？）",
                orphans.size(), taken);
        return taken;
    }

    /**
     * 只接最近这么多分钟内发出去的问题（{@code chat.turnResumeWindowMinutes}，默认 1 小时）。
     * <p>为什么要有窗口：更久没答的那一轮早就是历史，把它翻出来重跑等于凭空给用户一条迟到的回答，
     * 还多一份账单；写 0 或负数按默认值走——这件事是兜底，不该被误配成「整表重放」。
     */
    private LocalDateTime windowFrom() {
        int minutes = configService.getInt("chat.turnResumeWindowMinutes", 60);
        return LocalDateTime.now().minusMinutes(minutes <= 0 ? 60 : minutes);
    }

    /**
     * 窗口内「问完了却再没有过回答、也没交代过结局」的轮。
     * <p>判据刻意不看<b>同轮号有没有助手消息</b>：消息表带逻辑删除，「重新生成」软删的那份旧回答
     * 根本查不出来，按轮号判就会把用户已经取代掉的旧轮当成断了的重跑一遍——屏幕上凭空多一条迟到的回答。
     * 改成「这条问题之后，同一会话里再也没有出现过任何助手消息」：旧轮后面跟着新回答，自然排除；
     * 而用户真的盯着空白屏幕等的那一类，最后一条就是他发出去的问题。
     * <p>只看「有没有回答」还漏了一整类：失败轮与叫停轮<b>本来就不落助手行</b>（失败只在那一问上挂
     * {@code fail_msg}，零产出的停止挂 {@code end_reason}），它们后面同样空着，却早就交代完了。
     * 这两类要按列名排除，否则每一轮扫描都会把一条已经说过「没跑成」的提问重跑一遍。
     */
    private List<Message> findOrphans() {
        List<Message> asks = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getRole, "user")
                .isNotNull(Message::getTurnId)
                .isNull(Message::getFailMsg)
                .isNull(Message::getEndReason)
                .ge(Message::getCreateTime, windowFrom())
                .notExists("select 1 from c_ai_message a where a.session_id = c_ai_message.session_id"
                        + " and a.role = 'assistant' and a.create_time >= c_ai_message.create_time")
                .orderByDesc(Message::getCreateTime)
                .last("limit " + BATCH));
        if (asks.isEmpty()) return List.of();
        Map<String, Message> byTurn = new LinkedHashMap<>();
        for (Message m : asks) {
            if (m.getSessionId() == null || m.getSessionId().isBlank()) continue;
            byTurn.putIfAbsent(m.getTurnId(), m);
        }
        if (byTurn.isEmpty()) return List.of();
        List<String> turns = new ArrayList<>(byTurn.keySet());

        // 待办还开着的轮是在等人，不是断了：那条路由卡片答复接回（FrameworkSessions#ensureFollowing）
        Set<String> parked = new HashSet<>();
        for (ToolApproval p : approvalMapper.selectList(new LambdaQueryWrapper<ToolApproval>()
                .select(ToolApproval::getTurnId)
                .eq(ToolApproval::getStatus, "PENDING")
                .in(ToolApproval::getTurnId, turns))) {
            parked.add(p.getTurnId());
        }
        List<Message> out = new ArrayList<>();
        for (Map.Entry<String, Message> e : byTurn.entrySet()) {
            if (parked.contains(e.getKey())) continue;
            out.add(e.getValue());
        }
        return out;
    }

    /**
     * 这一轮当时是以谁的身份跑的、是不是访客轮。
     * <p>访客会话的行归属是 {@code share-visitor:<id>}——那是<b>接流</b>归属用的（别人拿到同一个 token
     * 也接不走不属于自己的会话）；而那一轮的检索可见性与默认模型按<b>发布者</b>装载。接续必须按后者去解析
     * 模型，否则解不出来、引擎一声不响地不接。
     * <p>访客标记要一起带上：漏了它，接出来的那一轮挂的工具面会比原来那一轮宽（沙盒/联网/MCP 都给了访客）。
     */
    private Owner ownerOf(String sessionId) {
        Session s = sessionMapper.selectOne(new LambdaQueryWrapper<Session>()
                .select(Session::getUserId, Session::getAgentId).eq(Session::getId, sessionId));
        if (s == null) return null;
        String uid = s.getUserId();
        if (uid == null || !uid.startsWith(com.wenqu.ai.service.AgentShareService.VISITOR_UID_PREFIX)) {
            return new Owner(uid, null);
        }
        if (s.getAgentId() == null || s.getAgentId().isBlank()) return null;
        com.wenqu.ai.model.AgentShare share = shareMapper.selectOne(new LambdaQueryWrapper<com.wenqu.ai.model.AgentShare>()
                .select(com.wenqu.ai.model.AgentShare::getCreatedBy)
                .eq(com.wenqu.ai.model.AgentShare::getAgentId, s.getAgentId())
                .last("LIMIT 1"));
        if (share == null || share.getCreatedBy() == null || share.getCreatedBy().isBlank()) return null;
        return new Owner(share.getCreatedBy(), uid);
    }

    /** 会话的运行身份：以谁的名义跑（uid）+ 原本是不是访客轮（guestUid，非访客为 null）。 */
    private record Owner(String uid, String guestUid) {
    }
}
