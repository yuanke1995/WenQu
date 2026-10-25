package com.wenqu.ai.service;

import com.wenqu.ai.sandbox.ProvisionerSandboxBackend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 沙盒里的<b>后台进程</b>：列出这一会话的沙盒中还在跑的东西，并按归属标记杀掉它。
 *
 * <p><b>要解决的问题</b>：模型让助手「把写好的前后端跑起来自己看效果」时，起的是 {@code npm run dev}
 * 这类<b>不会自己退</b>的进程。而沙盒那条 {@code /v1/shell/exec} 到点只是<b>不再等</b>它
 * （见 {@code ProvisionerSandboxBackend#execute}：超时回 {@code status=running}，进程照旧活着），
 * 于是端口被占着、下一轮重跑撞「address already in use」，而界面上什么都看不见——
 * 用户唯一的线索是模型那句「我先起个服务」。
 *
 * <p><b>为什么这里没有一张进程表</b>：真源在容器里。每条会话内的命令都被包成
 * {@code ( export WQ_PROC_TAG=…; <命令> )}，子进程与孙进程<b>天然继承</b>这个环境变量，
 * 于是扫一遍 {@code /proc} 下各进程的 {@code environ} 就是实况——不用维护一份会和现实漂移的登记，
 * 也不受「后端重启、换副本」影响（表在容器里，不在本进程内存里）。
 *
 * <p><b>边界（如实）</b>：只有<b>带标记</b>的进程进这张清单。三类看不见——
 * ① 这次改动之前起的老进程；② 工作流代码节点/定时任务起的（没有会话上下文，{@code procTag=null}）；
 * ③ 模型自己 {@code unset} 掉标记或换了个执行域（{@code sudo}、{@code docker exec}）起走的。
 * 沙盒被空闲回收（{@code sandbox.idleReleaseMinutes}）时容器整个没了，清单自然归零。
 *
 * @author yuanke
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxProcService {

    /** 一次最多列多少棵进程树：再多也读不完，且说明沙盒已经成灾了。 */
    private static final int MAX_GROUPS = 50;

    /** 一次侧栏扫描最多碰几个容器（超出的按 {@code partial} 报，不假装查完了）。 */
    private static final int MAX_SWEEP = 8;

    /** 整批扫描的等待上限（秒）：单条扫描自己有 25s 超时，八个并行也不能让一次侧栏轮询拖到分钟级。 */
    private static final int SWEEP_DEADLINE_SECONDS = 12;

    private final SandboxService sandboxService;
    private final SessionService sessionService;

    /**
     * 列本会话沙盒里还在跑的后台进程。
     *
     * @param sessionId 会话（决定沙盒作用域：挂了共享目录的工作区按工作区，故同工作区别的会话
     *                  起的东西也会在这里出现，标 {@code own=false}）
     * @return {@code {available, procs:[...]}}；沙盒没在跑 → {@code available=false}（不创建容器）
     * @throws IllegalStateException 沙盒在跑但<b>读不到进程表</b>——绝不退成「没有后台进程」，
     *                               那会把一个正占着端口的服务读成不存在（与「空态不许掩盖失败」同一条口径）
     */
    public Map<String, Object> list(String sessionId, String uid) {
        Map<String, Object> out = new LinkedHashMap<>();
        ProvisionerSandboxBackend backend;
        try {
            backend = sandboxService.backendIfPresent(sessionId, uid);
        } catch (Exception e) {
            out.put("available", false);
            out.put("procs", List.of());
            return out;
        }
        if (backend == null || !sandboxService.available(sessionId, uid)) {
            out.put("available", false);
            out.put("procs", List.of());
            return out;
        }
        List<String> lines = backend.scanProcs();
        if (lines == null) {
            throw new IllegalStateException("沙盒此刻读不到进程表（命令没跑完或沙盒不可达），不是「没有后台进程」");
        }
        out.put("available", true);
        out.put("procs", groupByTag(lines, sessionId));
        return out;
    }

    /**
     * 杀掉一棵进程树（按归属标记）。
     *
     * @param tag 来自 {@link #list} 的标记；形状不合法或不属于调用者可操作的会话即拒绝
     * @return {@code {killed: N, remaining: [pid]}}——{@code remaining} 非空 = 有进程杀不掉，如实回
     */
    public Map<String, Object> kill(String sessionId, String uid, String tag) {
        String owner = ProvisionerSandboxBackend.sessionOfProcTag(tag);
        if (owner == null) {
            throw new IllegalArgumentException("进程标识不合法");
        }
        // 越权判定按标记里编的那个会话，而不是按请求传来的会话：同工作区共享容器时，
        // 传来的 sessionId 只能证明「这个容器你能碰」，证明不了「这个进程是你起的」。
        sessionService.assertOwned(owner, uid);
        sessionService.assertOwned(sessionId, uid);
        ProvisionerSandboxBackend backend = sandboxService.backendIfPresent(sessionId, uid);
        int[] r = backend == null ? null : backend.killByProcTag(tag);
        if (r == null) {
            throw new IllegalStateException("沙盒不可达，这条停止命令没有发出去");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("killed", r[0]);
        out.put("remaining", r[1]);
        log.info("[SANDBOX] 停止后台进程 session={} tag={} killed={} 仍幸存={}",
                sessionId, tag, r[0], r[1]);
        return out;
    }

    /**
     * 一次扫过<b>本进程里所有还活着的沙盒</b>，按进程归属会话汇总——给侧栏会话行那个标记用。
     *
     * <p><b>为什么不逐会话查</b>：侧栏一次摊几十条会话，逐条打 {@code /proc} 就是几十次容器往返。
     * 而容器数天然远小于会话数（只有空闲窗口内用过沙盒的会话才有容器），且没有活容器时这里
     * 一次网络都不发（{@code SandboxService#liveBackends} 读的是进程内缓存），所以侧栏可以放心轮询。
     *
     * <p><b>会话是从标记里读出来的，不是从容器名反推的</b>：一个容器可以装着同一工作区里好几个会话
     * 起的进程，每个标记编的就是起它的那个会话 id——所以标记能精确落到那一行，不需要「这个工作区的
     * 会话都亮一下」这种糊掉的口径。
     *
     * @return {@code {sessions:{会话id:进程树数}, partial}}。{@code partial=true} = 有一批容器没扫成
     *         （超出单次上限、到点没回来、或那份进程表读不到）——这时这份结果<b>只多不少</b>：
     *         列出来的都是真的，没列出来的只是没查到，客户端不该据此把已有的标记摘掉
     */
    public Map<String, Object> liveBySession(String uid) {
        List<ProvisionerSandboxBackend> backends = sandboxService.liveBackends(uid);
        boolean partial = backends.size() > MAX_SWEEP;
        if (partial) {
            backends = backends.subList(0, MAX_SWEEP);
        }
        Map<String, Set<String>> tagsBySession = new ConcurrentHashMap<>();
        AtomicInteger failed = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, backends.size()));
        try {
            for (ProvisionerSandboxBackend backend : backends) {
                futures.add(CompletableFuture.runAsync(() -> {
                    List<String> lines;
                    try {
                        lines = backend.scanProcs();
                    } catch (Exception e) {
                        lines = null;
                    }
                    if (lines == null) {
                        failed.incrementAndGet();
                        return;
                    }
                    for (String line : lines) {
                        if (!line.startsWith("WQPROC|")) continue;
                        String[] parts = line.split("\\|", -1);
                        if (parts.length < 6) continue;
                        String owner = ProvisionerSandboxBackend.sessionOfProcTag(parts[4].strip());
                        if (owner != null) {
                            tagsBySession.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(parts[4].strip());
                        }
                    }
                }, pool));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(SWEEP_DEADLINE_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                partial = true;   // 到点没全回来：没答上的那几个容器按「不知道」处理，不按「没有进程」
            }
        } finally {
            pool.shutdownNow();
        }
        Map<String, Integer> sessions = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : tagsBySession.entrySet()) {
            if (!e.getValue().isEmpty()) sessions.put(e.getKey(), e.getValue().size());
        }
        if (partial) {
            log.warn("[SANDBOX] 后台进程标记本轮不完整 (uid={} 扫了 {} 个容器，读不到 {} 个)",
                    uid, backends.size(), failed.get());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", sessions);
        out.put("partial", partial);
        return out;
    }

    /**
     * 把扫描行按标记归成一棵棵进程树。
     *
     * <p>每棵树取<b>根进程</b>的命令行做展示（父进程不在同一棵树里的那个）——根上写的才是
     * {@code npm run dev}，而它的子孙多半是 {@code node .../vite} 这类看不出意图的壳。
     */
    private List<Map<String, Object>> groupByTag(List<String> lines, String sessionId) {
        Map<String, List<String[]>> byTag = new LinkedHashMap<>();   // tag -> [pid, ppid, etimes, cmd]
        Map<String, Set<String>> portsByPid = new LinkedHashMap<>();
        Set<String> allPids = new LinkedHashSet<>();
        for (String line : lines) {
            String[] parts = line.split("\\|", -1);
            if (line.startsWith("WQPROC|") && parts.length >= 6) {
                String pid = parts[1].strip();
                if (pid.isEmpty()) continue;
                allPids.add(pid);
                byTag.computeIfAbsent(parts[4], t -> new ArrayList<>())
                        .add(new String[]{pid, parts[2].strip(), parts[3].strip(), parts[5].strip()});
            } else if (line.startsWith("WQPORT|") && parts.length >= 3) {
                portsByPid.computeIfAbsent(parts[1].strip(), p -> new LinkedHashSet<>()).add(parts[2].strip());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> e : byTag.entrySet()) {
            Set<String> tree = new LinkedHashSet<>();
            for (String[] row : e.getValue()) tree.add(row[0]);
            List<String[]> roots = new ArrayList<>();
            for (String[] row : e.getValue()) {
                if (row[1].isEmpty() || !tree.contains(row[1])) roots.add(row);
            }
            // 一个根都没有 = 父进程也在这一棵树里且互相指（不该发生）：退回按最早启动的那个当根，
            // 宁可展示得不完美，也不把一棵真实存在的进程树整棵藏掉
            if (roots.isEmpty()) roots = java.util.Collections.singletonList(e.getValue().get(0));
            long oldestSec = Long.MAX_VALUE;
            Set<String> ports = new LinkedHashSet<>();
            for (String[] row : e.getValue()) {
                long sec = parseLong(row[2], 0);
                if (sec < oldestSec) oldestSec = sec;
                ports.addAll(portsByPid.getOrDefault(row[0], Set.of()));
            }
            // 一次命令可以同时起走两棵互不相干的树（前端 + 后端各一个 dev server），
            // 只露第一根就会让用户以为「卡上说的不是我起的那个」——多根就都列出来。
            StringBuilder cmd = new StringBuilder();
            for (int i = 0; i < roots.size() && i < 3; i++) {
                if (i > 0) cmd.append(" ─┬─ ");
                cmd.append(roots.get(i)[3].strip());
            }
            if (roots.size() > 3) cmd.append(" ── …其余 ").append(roots.size() - 3).append(" 棵");
            String owner = ProvisionerSandboxBackend.sessionOfProcTag(e.getKey());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tag", e.getKey());
            item.put("sessionId", owner == null ? "" : owner);
            item.put("own", sessionId.equals(owner));
            // 认不出归属会话的标记（不是本平台起的，或形状是旧版/手工塞的）停不了：
            // 卡片照列，但不给停止按钮——给了就是一条必然失败的死操作
            item.put("killable", owner != null);
            item.put("command", cmd.isEmpty() ? "(读不到命令行)" : cmd.toString());
            item.put("roots", roots.size());
            item.put("pids", new ArrayList<>(tree));
            item.put("elapsedSec", oldestSec == Long.MAX_VALUE ? 0 : oldestSec);
            item.put("startedAt", oldestSec == Long.MAX_VALUE ? null : System.currentTimeMillis() - oldestSec * 1000L);
            item.put("ports", new ArrayList<>(ports));
            out.add(item);
        }
        // 最久没关的排最前：这张卡要解决的是「忘了关」，不是「刚起了什么」
        out.sort(Comparator.comparingLong(m -> -parseLong(String.valueOf(m.get("elapsedSec")), 0)));
        return out.size() > MAX_GROUPS ? out.subList(0, MAX_GROUPS) : out;
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
