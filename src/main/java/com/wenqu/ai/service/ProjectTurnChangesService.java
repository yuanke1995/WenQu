package com.wenqu.ai.service;

import com.wenqu.ai.executor.ExecutorFiles;
import com.wenqu.ai.mapper.MessageMapper;
import com.wenqu.ai.mapper.SessionMapper;
import com.wenqu.ai.model.Message;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.ProjectRoot;
import com.wenqu.ai.model.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 每轮文件改动（DSH workspace-changes 口径）：轮前让本机执行器给工作区拍 git 快照、
 * 轮末对比并汇总「这一轮改了哪些文件、各多少行」，随助手消息落库与 done 帧下发；
 * 用户点开某个文件的差异时，再让执行器现算 hunk 回传。
 *
 * <p><b>内容不出用户电脑</b>：快照对象与改动前捕获全部留在执行器那台机器的临时目录里，
 * 服务端拿到的只有「相对路径 + 行数」；hunk 是用户主动点开时才产生的少量派生文本，
 * 通道量级与现有文件预览一致。因此这里没有内容缓存，也没有第二份快照。
 *
 * <p><b>降级口径</b>：非本机工作区、执行器离线、执行器版本过旧、快照失败——一律静默跳过
 * （这一轮不出改动卡，也不给问答添错），与 DSH「git 不可用时只列文件工具改动」同一精神。
 */
@Slf4j
@Service
public class ProjectTurnChangesService {

    /** 轮前快照最多等这么久：执行器还没回就不再等，轮末对比交给 turnEnd 自己兜（快照没到=这轮没对比）。 */
    private static final long BASELINE_WAIT_SECONDS = 10;

    private final ProjectService projectService;
    private final ExecutorFiles executorFiles;
    private final MessageMapper messageMapper;
    private final SessionMapper sessionMapper;

    /** 单线程发轮前快照：不阻塞问答主链路，FIFO 保证快照先于本轮第一次写到达执行器队列。 */
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "turn-changes");
        t.setDaemon(true);
        return t;
    });

    /** sessionId → 本轮 turnId：{@code ExecutorFilesystem} 调用期读取，给写类调用带上 turnId。 */
    private final ConcurrentHashMap<String, String> activeTurns = new ConcurrentHashMap<>();

    /** sessionId → 轮前快照的进行中调用（settle 时等它落定）。 */
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pendingStarts =
            new ConcurrentHashMap<>();

    public ProjectTurnChangesService(ProjectService projectService, ExecutorFiles executorFiles,
                                     MessageMapper messageMapper, SessionMapper sessionMapper) {
        this.projectService = projectService;
        this.executorFiles = executorFiles;
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
    }

    /** 本会话当前轮的 turnId（没有进行中的轮返回 null）。 */
    public String currentTurnId(String sessionId) {
        return sessionId == null ? null : activeTurns.get(sessionId);
    }

    /**
     * 轮开始：异步发轮前快照。只有本机工作区且执行器在线才发；其余情况静默跳过。
     * 调用时机必须早于本轮任何写类执行器调用——执行器按队列 FIFO 处理，快照先入队，
     * 执行器端才有 state 可挂「改动前捕获」。
     */
    public void start(String sessionId, String turnId) {
        if (sessionId == null || sessionId.isBlank() || turnId == null || turnId.isBlank()) return;
        try {
            Project p = projectService.projectOfSession(sessionId);
            if (p == null || !projectService.isLocal(p)) return;
            List<ProjectRoot> roots = projectService.rootsOf(p);
            if (roots.isEmpty()) return;
            if (!executorFiles.online(p.getExecutorId())) return;
            activeTurns.put(sessionId, turnId);
            CompletableFuture<Map<String, Object>> f = CompletableFuture.supplyAsync(
                    () -> executorFiles.turnStart(p.getExecutorId(), roots, turnId), pool);
            pendingStarts.put(sessionId, f);
        } catch (Exception e) {
            log.warn("[CHANGES] 轮前快照发起失败（这轮不出改动卡）session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 轮收尾：等轮前快照落定 → 轮末对比 → 返回可直接落库/下发的 summary（null=这轮不出改动卡）。
     * 幂等：重复调用或没有进行中的轮都返回 null。失败一律静默（日志一条），绝不给收尾添错。
     */
    public Map<String, Object> settle(String sessionId, String turnId) {
        if (sessionId == null || turnId == null || turnId.isBlank()) return null;
        String active = activeTurns.remove(sessionId);
        CompletableFuture<Map<String, Object>> f = pendingStarts.remove(sessionId);
        if (f == null && active == null) return null;   // start 没发过（非本机工作区/离线）：零开销直出
        if (active != null && !turnId.equals(active)) return null;   // 已是下一轮的把柄，不串
        if (f != null) {
            try {
                f.get(BASELINE_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 快照没落定也继续问 turnEnd：执行器没收到 turnStart 会回 supported=false，自然不出卡
                log.warn("[CHANGES] 轮前快照未在限时内落定 session={} turn={}: {}", sessionId, turnId, e.getMessage());
            }
        }
        try {
            Project p = projectService.projectOfSession(sessionId);
            if (p == null || !projectService.isLocal(p)) return null;
            Map<String, Object> r = executorFiles.turnEnd(p.getExecutorId(), projectService.rootsOf(p), turnId);
            return summarize(r, turnId);
        } catch (Exception e) {
            log.warn("[CHANGES] 轮末对比失败（这轮不出改动卡）session={} turn={}: {}", sessionId, turnId, e.getMessage());
            return null;
        }
    }

    /**
     * 取一条历史消息里第 index 个改动文件的差异（按消息归属校验 uid）。
     * 快照数据已过期（执行器清了 scratch / 执行器换机）回可读 400。
     */
    public Map<String, Object> fileDiff(String uid, String messageId, int index) {
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("缺少消息标识");
        Message message = messageMapper.selectById(messageId);
        if (message == null || message.getDeleted() != null && message.getDeleted() != 0) {
            throw new IllegalArgumentException("消息不存在或不属于你");
        }
        Session session = sessionMapper.selectById(message.getSessionId());
        if (session == null || !uid.equals(session.getUserId())) {
            throw new IllegalArgumentException("消息不存在或不属于你");
        }
        String changes = message.getChanges() == null ? "" : message.getChanges();
        if (changes.isBlank()) throw new IllegalArgumentException("这条消息没有记录文件改动");
        Map<String, Object> parsed;
        try {
            parsed = com.alibaba.fastjson2.JSON.parseObject(changes);
        } catch (Exception e) {
            throw new IllegalArgumentException("这条消息的改动记录不可读");
        }
        String turnId = parsed.get("turnId") == null ? "" : String.valueOf(parsed.get("turnId"));
        Object filesObj = parsed.get("files");
        if (turnId.isBlank() || !(filesObj instanceof List<?> files)
                || index < 0 || index >= files.size()
                || !(files.get(index) instanceof Map<?, ?> file)) {
            throw new IllegalArgumentException("没有这份文件的改动记录");
        }
        String display = file.get("path") == null ? "" : String.valueOf(file.get("path"));
        if (display.isBlank()) throw new IllegalArgumentException("没有这份文件的改动记录");
        Project p = projectService.projectOfSession(message.getSessionId());
        if (p == null || !projectService.isLocal(p)) throw new IllegalArgumentException("这条消息不在本机工作区里");
        try {
            return executorFiles.turnDiff(p.getExecutorId(), projectService.rootsOf(p), turnId, display);
        } catch (IllegalStateException e) {
            // 执行器离线/超时/版本旧：对用户来说都是「点开太晚或环境不在」，给同一句可读话
            throw new IllegalArgumentException("改动详情暂时读不到（本机执行器不在线或已清理）");
        }
    }

    /** 轮末数据 → summary（不认识/没改动都返回 null）。files 原样透传（相对路径+行数，无内容）。 */
    private Map<String, Object> summarize(Map<String, Object> r, String turnId) {
        if (r == null || !Boolean.TRUE.equals(r.get("supported"))) return null;
        Object files = r.get("files");
        if (!(files instanceof List<?> list) || list.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("turnId", turnId);
        out.put("files", files);
        out.put("total", r.get("total") instanceof Number n ? n.intValue() : list.size());
        out.put("added", r.get("added") instanceof Number n ? n.intValue() : 0);
        out.put("deleted", r.get("deleted") instanceof Number n ? n.intValue() : 0);
        return out;
    }
}
