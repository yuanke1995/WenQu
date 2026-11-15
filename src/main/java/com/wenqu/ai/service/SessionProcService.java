package com.wenqu.ai.service;

import com.wenqu.ai.executor.ExecutorRegistry;
import com.wenqu.ai.model.Project;
import com.wenqu.ai.model.Session;
import com.wenqu.ai.sandbox.ProvisionerSandboxBackend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条会话的<b>后台进程</b>：本机执行器上的与服务器沙盒里的，合成一张清单。
 *
 * <p><b>为什么要合成</b>：工作区会话的命令跑在用户电脑上（本机执行器），沙盒会话跑在容器里；
 * 同一条会话在「执行器离线」的那些轮会退回沙盒，于是两边都可能背着进程。界面上只该看到一张
 * 「这条会话起了什么」的卡，而不是让用户自己判断去哪个来源找。
 *
 * <p><b>真源各自在源头</b>：本机侧读执行器的进程登记 + 实况进程表，沙盒侧读容器 {@code /proc}；
 * 服务端不留台账，所以后端重启、换副本都不影响清单。执行器重启后登记仍在这台电脑上。
 *
 * <p><b>失败口径</b>：每条来源各自回 {@code available/reason/message}——
 * 「执行器离线」「执行器版本旧」「读不到」都不是「没有进程」，绝不静默成空列表。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionProcService {

    /** 执行器进程类调用（两三次 ps 扫描 + kill 等待）的等待上限。 */
    private static final long LOCAL_CALL_TIMEOUT_MS = 15_000;
    /** 侧栏轮询最多问几台设备：多设备是极少数，超出的按 partial 报，不假装扫完了。 */
    private static final int MAX_SWEEP_DEVICES = 4;

    private final SessionService sessionService;
    private final ProjectService projectService;
    private final SandboxProcService sandboxProcService;
    private final ConfigService configService;
    private final ExecutorRegistry executorRegistry;
    private final com.wenqu.ai.executor.ExecutorService executorService;

    /**
     * 本会话两个来源的后台进程清单。
     *
     * @return {@code {available, procs:[{side,tag,sessionId,command,roots,pids,ports,elapsedSec,startedAt,own,killable}],
     *         sides:{local:{applicable,available,reason,message}, sandbox:{...}}}}
     */
    public Map<String, Object> list(String sessionId, String uid) {
        Session session = sessionService.assertOwned(sessionId, uid);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> procs = new ArrayList<>();
        Map<String, Object> sides = new LinkedHashMap<>();

        Map<String, Object> local = new LinkedHashMap<>();
        collectLocal(sessionId, uid, session, local, procs);
        sides.put("local", local);

        Map<String, Object> sandbox = new LinkedHashMap<>();
        collectSandbox(sessionId, uid, sandbox, procs);
        sides.put("sandbox", sandbox);

        procs.sort(Comparator.comparingLong(p -> -num(p.get("elapsedSec"))));
        out.put("available", Boolean.TRUE.equals(local.get("available")) || Boolean.TRUE.equals(sandbox.get("available")));
        out.put("procs", procs);
        out.put("sides", sides);
        return out;
    }

    /**
     * 侧栏会话行标记用的批量实况：沙盒 + 本机执行器两路汇总。
     *
     * @return {@code {sessions:{会话id:进程树数}, partial}}；partial=true 时结果只多不少
     */
    public Map<String, Object> live(String uid) {
        Map<String, Integer> sessions = new LinkedHashMap<>();
        boolean partial = false;
        try {
            Map<String, Object> sb = sandboxProcService.liveBySession(uid);
            mergeCounts(sessions, sb.get("sessions"));
            partial = Boolean.TRUE.equals(sb.get("partial"));
        } catch (Exception e) {
            partial = true;
            log.warn("[PROC] 沙盒进程汇总失败 uid={}: {}", uid, e.getMessage());
        }
        List<String> devices = onlineExecutors(uid);
        if (devices.size() > MAX_SWEEP_DEVICES) {
            devices = devices.subList(0, MAX_SWEEP_DEVICES);
            partial = true;
        }
        for (String executorId : devices) {
            ExecutorRegistry.Result r = executorRegistry.call(executorId, "liveProcs",
                    Map.of("uid", uid), 5_000);
            if (!r.ok()) {
                // 旧执行器不认识 liveProcs：不算「没扫全」，只是那台设备没有这份能力
                if (!"E_UNSUPPORTED".equals(r.code())) partial = true;
                continue;
            }
            mergeCounts(sessions, r.data().get("sessions"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", sessions);
        out.put("partial", partial);
        return out;
    }

    /**
     * 停掉一棵后台进程树。越权判定与沙盒同口径：按<b>标记里编的会话</b>再验一次归属——
     * 同机/同容器可以有多条会话，光验请求里的会话证明不了这棵进程是谁起的。
     */
    public Map<String, Object> kill(String sessionId, String uid, String side, String tag) {
        Session session = sessionService.assertOwned(sessionId, uid);
        String owner = ProvisionerSandboxBackend.sessionOfProcTag(tag);
        if (owner == null) throw new IllegalArgumentException("进程标识不合法");
        // 同一会话的标记已经验过；只有标记编的是「同容器里别的会话」时才需要再验一次归属
        if (!owner.equals(sessionId)) sessionService.assertOwned(owner, uid);
        if ("sandbox".equals(side)) {
            return sandboxProcService.kill(sessionId, uid, tag);
        }
        if (!"local".equals(side)) throw new IllegalArgumentException("未知的进程来源");

        boolean responded = false;
        for (String executorId : killTargets(session, uid)) {
            if (!belongs(uid, executorId) || !executorRegistry.online(executorId)) continue;
            ExecutorRegistry.Result r = executorRegistry.call(executorId, "killProc",
                    Map.of("tag", tag), LOCAL_CALL_TIMEOUT_MS);
            if (r.ok()) {
                responded = true;
                if (Boolean.TRUE.equals(r.data().get("found"))) return r.data();
                continue;   // 这台设备上没登记过这棵：换下一台（设备迁移后仍能停）
            }
            if ("E_UNSUPPORTED".equals(r.code())) {
                throw new IllegalStateException("本机执行器版本过旧，请升级并重启执行器后再试");
            }
            if ("E_EXECUTOR_OFFLINE".equals(r.code()) || "E_TIMEOUT".equals(r.code())) continue;
            throw new IllegalStateException(r.message() == null ? "停止本机进程失败" : r.message());
        }
        // 一台能问的设备都没有：这不是「它自己退了」，而是命令没发出去
        if (!responded) throw new IllegalStateException("本机执行器不在线，停止命令没有发出去");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        out.put("killed", 0);
        out.put("remaining", 0);
        return out;
    }

    /**
     * 停掉本会话起的<b>全部</b>进程树。
     * <p>沙盒侧只停 {@code own=true} 的：同工作区的容器里可能还有别的会话起的进程，
     * 「全部停止」是停这条会话的，不是把同容器里别人的也带走。
     */
    public Map<String, Object> killAll(String sessionId, String uid, String side) {
        Session session = sessionService.assertOwned(sessionId, uid);
        int killed = 0;
        int remaining = 0;
        boolean found = false;
        if ("sandbox".equals(side)) {
            Map<String, Object> r = sandboxProcService.list(sessionId, uid);
            Object raw = r.get("procs");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m) || !Boolean.TRUE.equals(m.get("own"))) continue;
                    Object tag = m.get("tag");
                    if (tag == null) continue;
                    Map<String, Object> one = sandboxProcService.kill(sessionId, uid, String.valueOf(tag));
                    killed += num(one.get("killed"));
                    remaining += num(one.get("remaining"));
                    found = true;
                }
            }
        } else if ("local".equals(side)) {
            boolean responded = false;
            for (String executorId : killTargets(session, uid)) {
                if (!belongs(uid, executorId) || !executorRegistry.online(executorId)) continue;
                ExecutorRegistry.Result r = executorRegistry.call(executorId, "killSession",
                        Map.of("sessionId", sessionId), LOCAL_CALL_TIMEOUT_MS);
                if (!r.ok()) {
                    if ("E_UNSUPPORTED".equals(r.code())) {
                        throw new IllegalStateException("本机执行器版本过旧，请升级并重启执行器后再试");
                    }
                    if ("E_EXECUTOR_OFFLINE".equals(r.code()) || "E_TIMEOUT".equals(r.code())) continue;
                    throw new IllegalStateException(r.message() == null ? "停止本机进程失败" : r.message());
                }
                responded = true;
                if (Boolean.TRUE.equals(r.data().get("found"))) found = true;
                killed += num(r.data().get("killed"));
                remaining += num(r.data().get("remaining"));
            }
            if (!responded) throw new IllegalStateException("本机执行器不在线，停止命令没有发出去");
        } else {
            throw new IllegalArgumentException("未知的进程来源");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", found);
        out.put("killed", killed);
        out.put("remaining", remaining);
        return out;
    }

    // ==================== 两路取数 ====================

    private void collectLocal(String sessionId, String uid, Session session,
                              Map<String, Object> side, List<Map<String, Object>> procs) {
        List<String> candidates = localExecutors(session, uid);
        boolean applicable = !candidates.isEmpty() || boundExecutor(session) != null;
        side.put("applicable", applicable);
        side.put("available", false);
        side.put("reason", null);
        side.put("message", null);
        if (!applicable) return;
        if (candidates.isEmpty()) {
            side.put("reason", "executor-offline");
            side.put("message", "本机执行器不在线，本机起的进程暂时看不到");
            return;
        }
        boolean anyOnline = false;
        for (String executorId : candidates) {
            if (!belongs(uid, executorId)) continue;
            if (!executorRegistry.online(executorId)) continue;
            anyOnline = true;
            ExecutorRegistry.Result r = executorRegistry.call(executorId, "procs",
                    Map.of("sessionIds", List.of(sessionId)), LOCAL_CALL_TIMEOUT_MS);
            if (r.ok()) {
                side.put("available", true);
                Object raw = r.data().get("procs");
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        if (!(o instanceof Map<?, ?> m)) continue;
                        Map<String, Object> item = new LinkedHashMap<>();
                        m.forEach((k, v) -> item.put(String.valueOf(k), v));
                        item.put("side", "local");
                        item.put("own", sessionId.equals(String.valueOf(item.get("sessionId"))));
                        procs.add(item);
                    }
                }
                continue;
            }
            if ("E_UNSUPPORTED".equals(r.code())) {
                side.put("reason", "executor-too-old");
                side.put("message", "本机执行器版本过旧：重启或升级执行器后，本机进程会显示在这里");
                continue;
            }
            if (!Boolean.TRUE.equals(side.get("available"))) {
                side.put("reason", "error");
                side.put("message", r.message() == null ? "读不到本机进程表" : r.message());
            }
            log.warn("[PROC] 读本机进程失败 sid={} executor={} code={} msg={}",
                    sessionId, executorId, r.code(), r.message());
        }
        if (!anyOnline) {
            side.put("available", false);
            side.put("reason", "executor-offline");
            side.put("message", "本机执行器不在线，本机起的进程暂时看不到");
        }
    }

    private void collectSandbox(String sessionId, String uid,
                                Map<String, Object> side, List<Map<String, Object>> procs) {
        side.put("applicable", false);
        side.put("available", false);
        side.put("reason", null);
        side.put("message", null);
        if (!configService.getBoolean("tool.sandbox.enabled")) return;
        side.put("applicable", true);
        try {
            Map<String, Object> r = sandboxProcService.list(sessionId, uid);
            if (!Boolean.TRUE.equals(r.get("available"))) return;   // 沙盒没在跑：正常，不是失败
            side.put("available", true);
            Object raw = r.get("procs");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    m.forEach((k, v) -> item.put(String.valueOf(k), v));
                    item.put("side", "sandbox");
                    procs.add(item);
                }
            }
        } catch (IllegalStateException e) {
            side.put("reason", "error");
            side.put("message", e.getMessage());
        }
    }

    // ==================== 会话 → 设备 ====================

    /**
     * 这条会话可能落过的本机执行器。
     * <p>显式工作区固定在工作区那台；普通会话优先用已落库的会话目录那台——
     * 换成「所有在线设备」会去问一台根本没跑过这条会话的电脑（拿到的只能是空）。
     */
    private List<String> localExecutors(Session session, String uid) {
        String bound = boundExecutor(session);
        if (bound != null) {
            return executorRegistry.online(bound) ? List.of(bound) : List.of();
        }
        return onlineExecutors(uid);
    }

    /** 停止动作要能区分「不在线」与「没登记过」：这里把绑定的那台留着，离线由调用方如实报。 */
    private List<String> killTargets(Session session, String uid) {
        String bound = boundExecutor(session);
        if (bound != null) return belongs(uid, bound) ? List.of(bound) : List.of();
        return onlineExecutors(uid);
    }

    private String boundExecutor(Session session) {
        if (session == null) return null;
        Project project = projectService.projectOfSession(session.getId());
        if (project != null && !Project.SELECTION_IMPLICIT.equals(project.getSelectionStatus())) {
            String ex = project.getExecutorId();
            return ex == null || ex.isBlank() ? null : ex;
        }
        String ex = session.getWsExecutorId();
        return ex == null || ex.isBlank() ? null : ex;
    }

    private List<String> onlineExecutors(String uid) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : executorService.list(uid)) {
            Object id = row.get("id");
            if (id == null) continue;
            String executorId = String.valueOf(id);
            if (executorRegistry.online(executorId)) out.add(executorId);
        }
        return out;
    }

    private boolean belongs(String uid, String executorId) {
        try {
            return executorService.belongsTo(uid, executorId);
        } catch (Exception e) {
            return false;
        }
    }

    private static void mergeCounts(Map<String, Integer> into, Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            int n = num(e.getValue());
            if (n <= 0) continue;
            into.merge(String.valueOf(e.getKey()), n, Integer::sum);
        }
    }

    private static int num(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }
}
