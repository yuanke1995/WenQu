package com.wenqu.ai.agent.subagent;

import com.alibaba.fastjson2.JSON;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.service.ArtifactService;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.DocumentMetaCache;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.service.CollectingSseEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子智能体分支的簿记（P6 刀 3b，自 {@code ChatService} 抽出）：派一支、收一支的回报、
 * 把子轮用过的引用与图片回填进父轮编号表、给下一层准备继承用的父轮上下文。
 * <p><b>「跑一整轮」仍然是交进来的函数</b>（{@link TurnRunner}）。分支是一条完整的问答轮，
 * 而流水线还在问答服务里（刀 5 才搬进门面）——注入进来就是运行时候选之间的一根环。
 * 这也是卡里拦路点 2 的真正判据：<b>一族能不能做成 bean，看它要不要回头调用问答入口</b>；
 * 子调用通道（刀 3a）不需要，所以它是 bean；这一族需要，所以只有簿记搬得动。
 * <p>回填为什么要在这里做而不能在编排处做：编排那一刻父轮的编号还没定稿，先编必撞号。
 * 范围外的那几块只摘编号不注册——库范围是能力边界，不能因为「分支跑在另一个会话里」绕过去。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SubAgentBranchRuntime {

    /** 跑一整轮的口子：由问答侧按轮传入（通常是 {@code ChatService::chat}） */
    public interface TurnRunner {
        void run(ChatCommand cmd, SseEmitter sink);
    }

    private final ConfigService configService;
    private final SessionService sessionService;
    private final ArtifactService artifactService;
    private final DocumentMetaCache documentMetaCache;
    private final ChatRunRuntime runtime;
    private final com.wenqu.ai.agentx.FrameworkSessions frameworkSessions;
    private final com.wenqu.ai.agent.hitl.HitlDecisionService hitl;

    /** 父会话 → 正在跑的分支：父轮结束或强制同步超时要能精确叫停并收口待办。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<BranchHandle>> branches =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 一支正在跑的分支（叫停所需的最小身份） */
    private record BranchHandle(String childSession, String userId, CollectingSseEmitter sink) {
    }

    public SubAgentBranchRuntime(ConfigService configService,
                                SessionService sessionService,
                                ArtifactService artifactService,
                                DocumentMetaCache documentMetaCache,
                                ChatRunRuntime runtime,
                                com.wenqu.ai.agentx.FrameworkSessions frameworkSessions,
                                com.wenqu.ai.agent.hitl.HitlDecisionService hitl) {
        this.configService = configService;
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.documentMetaCache = documentMetaCache;
        this.runtime = runtime;
        this.frameworkSessions = frameworkSessions;
        this.hitl = hitl;
    }

    /**
     * 子助手强制同步等待秒数：框架 ctx（{@code FrameworkSessions}）与分支自身收口共用这一份。
     * <p>取 max(子轮预算, 审批预算) + 60s，夹在 [60, 600]（框架上限）；配置
     * {@code agent.spawnSyncTimeoutSeconds} &gt; 0 时以它为准。
     */
    public static int spawnSyncTimeoutSeconds(ConfigService configService) {
        int configured = configService.getInt("agent.spawnSyncTimeoutSeconds",
                com.wenqu.ai.config.ConfigDefaults.AGENT_SPAWN_SYNC_TIMEOUT_SECONDS);
        if (configured > 0) return Math.min(600, Math.max(30, configured));
        long budget = Math.max(configService.getLong("agent.consultTimeoutMs", 300_000L),
                configService.getLong("chat.approvalTimeoutMs", 120_000L));
        return (int) Math.min(600L, Math.max(60L, budget / 1000L + 60L));
    }

    public BranchRunner branchRunner(TurnRunner turn, String parentSessionId, String parentTurnId, String userId,
                                     boolean guestMode, boolean apiKey, SseEmitter parentEmitter) {
        return branchRunner(turn, parentSessionId, parentTurnId, userId, guestMode, apiKey, parentEmitter, null, null);
    }

    /**
     * 带父轮审批档位与来源的版本：{@code inheritedApprovalMode} 供子轮「未显式设置才继承」，
     * {@code source} 让子轮沿用父轮发起方身份（无观众链路 fail-closed 的判据）。
     */
    public BranchRunner branchRunner(TurnRunner turn, String parentSessionId, String parentTurnId, String userId,
                                     boolean guestMode, boolean apiKey, SseEmitter parentEmitter,
                                     String inheritedApprovalMode, String source) {
        return req -> {
            long start = System.currentTimeMillis();
            String childSession = null;
            CollectingSseEmitter sink = new CollectingSseEmitter();
            // 干活视角的逐步记录：转发帧里每张终态工具卡顺手留一份摘要（名字/中文名/单步耗时/成败），
            // 随 BranchReport 进 subChat 落库——刷新后卡片上那条「执行过程」时间线的真身就是它。
            // 直播那份靠同一条转发流（subagent_step），两处同源，天然同构。
            List<Map<String, Object>> workSteps = java.util.Collections.synchronizedList(new ArrayList<>());
            java.util.Set<String> seenStepKeys = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
            // 分支的实时过程（P3a 刀2）：那一支在父轮卡片上原本只有「跑了 N 步工具」这个事后计数，
            // 一支跑两三分钟时用户对着一个静止的数字等。这里把它自己那几路过程事件原样转给父轮通道，
            // 事件名换成 subagent_step、带上分支序号，前端归到对应那一行下面（不重组协议、不改内容）。
            // 只转不发完就没了：这份实时步骤不落库，刷新后看到的仍是步数与回报全文
            // （终态步骤清单 2026-10-25 起随 subChat.steps 落库，见上）。
            final int branchIndex = Math.max(0, req.branchIndex());
            final boolean[] first = {false};
            sink.forwardProcess((type, content) -> {
                // 审批卡上翻：子轮没有观众，这张卡只有转给父轮才有人批。
                // 载荷原样保留（approvalId/tool/args/timeoutMs），只补来源与提示；前端卡片读这些字段。
                if ("approval_required".equals(type)) {
                    String enriched = enrichApprovalCard(content, req.childAgentName());
                    boolean sentToParent = runtime.sendSseEvent(parentEmitter, "approval_required", enriched, parentSessionId);
                    log.info("[SUBAGENT] 分支审批卡已上翻：branch={} agent={} 父轮接收={} session={}",
                            branchIndex, req.childAgentName(), sentToParent, parentSessionId);
                    return;
                }
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("i", branchIndex);
                step.put("agent", req.childAgentName());
                step.put("type", type);
                step.put("content", content == null ? "" : content);
                boolean sent = runtime.sendSseEvent(parentEmitter, "subagent_step", JSON.toJSONString(step), parentSessionId);
                if (!first[0]) {
                    // 只在首帧留一行：接通与否是"这一支有没有实时过程"的分水岭，而静默失败最难查
                    first[0] = true;
                    log.info("[SUBAGENT] 分支实时过程首帧：branch={} type={} 父轮接收={} session={}",
                            branchIndex, type, sent, parentSessionId);
                }
                // 干活视角采步：只认终态帧（start 那张卡在前端直播里自己会转圈）；
                // toolCard 载荷不带 denied，被挡下的区分靠 done 载荷那一步的对齐（见下）。
                // 同一次调用的帧可能经两条路各到一遍（SSE 直发 + 总线扇出，真机实测成对出现）——
                // 按 name+startedAtMs 去重，同一把工具只进时间线一次
                if ("tool_status".equals(type) && workSteps.size() < BranchReport.MAX_STEPS) {
                    try {
                        com.alibaba.fastjson2.JSONObject tc =
                                com.alibaba.fastjson2.JSON.parseObject(String.valueOf(content == null ? "" : content));
                        String st = tc == null ? null : tc.getString("status");
                        if (tc != null && tc.getString("name") != null && ("done".equals(st) || "error".equals(st))) {
                            String stepKey = tc.getString("name") + "#" + tc.getLongValue("startedAtMs");
                            if (seenStepKeys.add(stepKey)) {
                                Map<String, Object> one = new LinkedHashMap<>();
                                one.put("n", tc.getString("name"));
                                if (tc.getString("label") != null) one.put("l", tc.getString("label"));
                                long ms = tc.getLongValue("elapsedMs");
                                if (ms > 0) one.put("ms", ms);
                                one.put("s", "error".equals(st) ? "error" : "done");
                                workSteps.add(one);
                            }
                        }
                    } catch (Exception ignore) {
                        // 采不上一步不影响正路：时间线少一行比分支报失败强
                    }
                }
            });
            BranchHandle handle = null;
            try {
                childSession = sessionService.createTempSession(req.userId());
                handle = new BranchHandle(childSession, req.userId(), sink);
                registerBranch(parentSessionId, handle);
                turn.run(ChatCommand.builder()
                        .sessionId(childSession)
                        .question(req.question())
                        .userId(req.userId())
                        .userImages(List.of())
                        .attachments(List.of())
                        .skills(List.of())
                        .agentId(req.childAgentId())
                        .modelOverride(req.modelRef())
                        .guestMode(guestMode)
                        // 子轮继承父轮的 API Key 收窄口径：否则 Key 派一支子智能体就能重新拿到文件/命令工具
                        .apiKey(apiKey)
                        .subagentDepth(req.depth())
                        .parentBrief(req.parentBrief())
                        .parentSessionId(req.parentSessionId())
                        .parentTurnId(parentTurnId)
                        .inheritedApprovalMode(inheritedApprovalMode)
                        .source(source)
                        .build(), sink);
                // 强制同步时父轮会一直等到这里返回。分支必须在 force_sync 截止前收口（留 5s 让回报送达），
                // 否则会出现「父轮报同步超时、子轮还在跑」的孤儿。没有开强制同步时不做这道硬截止。
                long forceSyncMs = configService.getBoolean("agent.spawnForceSync",
                        com.wenqu.ai.config.ConfigDefaults.AGENT_SPAWN_FORCE_SYNC)
                        ? spawnSyncTimeoutSeconds(configService) * 1000L : 0L;
                long hardDeadline = forceSyncMs > 0 ? start + forceSyncMs - 5_000L : Long.MAX_VALUE;
                long firstWait = req.timeoutMs();
                if (hardDeadline != Long.MAX_VALUE) {
                    firstWait = Math.min(firstWait, Math.max(1_000L, hardDeadline - System.currentTimeMillis()));
                }
                boolean settled = sink.awaitDone(firstWait);
                if (!settled) {
                    // 有人正在等答复（审批/提问/计划）时不要先杀子轮：它卡在人的手上，
                    // 按那一类卡自己的剩余预算再等一段，让引擎先按 TIMEOUT 干净收口。
                    long remain = hitl.pendingInteractionRemainingMs(childSession, req.userId());
                    // 没有待交互也给 5s 收尾：子轮可能正在写 Timeout 终态，掐在同一毫秒会把收口截断
                    long grace = remain > 0 ? remain + 60_000L : 5_000L;
                    if (hardDeadline != Long.MAX_VALUE) {
                        grace = Math.min(grace, Math.max(0L, hardDeadline - System.currentTimeMillis()));
                    }
                    if (grace > 0) {
                        log.info("[SUBAGENT] 超时宽限 {}ms（待交互剩余 {}ms）: session={}", grace, remain, childSession);
                        settled = sink.awaitDone(grace);
                    }
                }
                if (!settled) {
                    // 超时必须把那一支叫停：它还在流水线里跑，而父轮已经不等它了。
                    // 不叫停的后果是"删了一条还在被写的会话"+"一条线程被派出去的任务占住"，
                    // 并发几轮之后问答池就被没人等的分支填满。
                    runtime.stopTurn(childSession, req.userId());
                    return BranchReport.failed(BranchReport.TIMEOUT,
                            "分支超时（" + req.timeoutMs() / 1000 + " 秒）", System.currentTimeMillis() - start, workSteps);
                }
                if (sink.lastError() != null && !sink.lastError().isBlank()) {
                    return BranchReport.failed(BranchReport.FAILED, sink.lastError(),
                            System.currentTimeMillis() - start, workSteps);
                }
                String answer = sink.answer() == null ? "" : sink.answer().strip();
                if (answer.isBlank()) {
                    return BranchReport.failed(BranchReport.FAILED, "子智能体未产生回答",
                            System.currentTimeMillis() - start, workSteps);
                }
                // 子轮的引用/图片表是父轮续编编号的依据（done 载荷里那份是<b>已签名</b>的展示副本，
                // 存进回报前先把签名参数剥掉，回到与主链路一致的原始 URL）
                List<Map<String, Object>> childSources = new ArrayList<>();
                List<String> childImages = new ArrayList<>();
                int steps = 0;
                try {
                    // done 的 content 在 runtime.sendSseEvent 里是被当**字符串**再序列化一次的（外层 {"content":"{...}"}），
                    // 所以这里要解两层：直接 parseObject 会在内层引号处报 illegal input（真机实测踩到，
                    // 表现是引用与工具步数静默为 0，只有日志能看出来）
                    Object parsed = sink.donePayload() == null ? null : com.alibaba.fastjson2.JSON.parse(sink.donePayload());
                    if (parsed instanceof String s) parsed = com.alibaba.fastjson2.JSON.parse(s);
                    com.alibaba.fastjson2.JSONObject done = parsed instanceof com.alibaba.fastjson2.JSONObject o ? o : null;
                    if (done != null) {
                        com.alibaba.fastjson2.JSONArray ss = done.getJSONArray("sources");
                        if (ss != null) {
                            for (int i = 0; i < ss.size(); i++) {
                                com.alibaba.fastjson2.JSONObject o = ss.getJSONObject(i);
                                if (o == null) continue;
                                Map<String, Object> m = new LinkedHashMap<>(o);
                                m.put("images", rawUrlsOf(o.getList("images", String.class)));
                                childSources.add(m);
                            }
                        }
                        childImages = rawUrlsOf(done.getList("finalImages", String.class));
                        // 「这一支跑了 N 步工具」只数真执行过的：被守卫挡下的那几次工具体一次没跑，
                        // 按台账条数算会把"平台只让它试了 4 次"读成"它试了 8 次还没找到"
                        com.alibaba.fastjson2.JSONArray tc = done.getJSONArray("toolCalls");
                        if (tc != null) {
                            for (int i = 0; i < tc.size(); i++) {
                                com.alibaba.fastjson2.JSONObject o = tc.getJSONObject(i);
                                if (o != null && !o.getBooleanValue("denied")) steps++;
                            }
                            alignDeniedFlags(tc, workSteps);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SUBAGENT] 分支回报解析失败（正文照常带回，引用与图片不续编）: {}", e.getMessage());
                }
                // 产物收养：子轮交付的东西归到父会话，用户在父轮里就点得到（跑完即删的临时会话留不住它）
                List<String> artifactFiles = new ArrayList<>();
                // 干活视角的产物明细：url/大小/类型随卡落库，卡上的 chips 才点得开；
                // url 剥掉签名参数再存——落库的必须是稳定地址，带时效签名过期就是死链（与 images 同一口径）
                List<Map<String, Object>> artifactDetail = new ArrayList<>();
                try {
                    for (Map<String, Object> a : artifactService.adoptFromSession(childSession, parentSessionId)) {
                        Object f = a.get("filename");
                        if (f != null) artifactFiles.add(String.valueOf(f));
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("f", String.valueOf(f));
                        Object u = a.get("url");
                        if (u != null) {
                            String us = String.valueOf(u);
                            int q = us.indexOf('?');
                            d.put("u", q > 0 ? us.substring(0, q) : us);
                        }
                        if (a.get("size") instanceof Number n) d.put("s", n.longValue());
                        Object ex = a.get("ext");
                        if (ex != null && !String.valueOf(ex).isBlank()) d.put("e", String.valueOf(ex));
                        artifactDetail.add(d);
                    }
                } catch (Exception e) {
                    log.warn("[SUBAGENT] 分支产物收养失败（回答仍有效，产物留在子会话）: {}", e.getMessage());
                }
                long ms = System.currentTimeMillis() - start;
                log.info("[SUBAGENT] 全循环分支完成 {}：正文 {}字 引用 {} 条 工具步 {} 产物 {} 个 耗时 {}ms",
                        req.describe(), answer.length(), childSources.size(), steps, artifactFiles.size(), ms);
                return new BranchReport(BranchReport.DONE, answer, List.copyOf(childSources),
                        List.copyOf(childImages), steps, List.copyOf(workSteps),
                        List.copyOf(artifactFiles), List.copyOf(artifactDetail), ms, null);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("[SUBAGENT] 全循环分支异常（按失败降级，不影响主链路）{}: {}",
                        req.describe(), cause.toString());
                return BranchReport.failed(BranchReport.FAILED, cause.toString(), System.currentTimeMillis() - start, workSteps);
            } finally {
                if (handle != null) unregisterBranch(parentSessionId, handle);
                // 子轮结束（正常/异常/被叫停）时还挂着的待办一律落 STOPPED：
                // 临时会话马上要删，PENDING 留着只会在父会话刷新时被重建出一张点了没反应的卡
                if (childSession != null) {
                    try {
                        frameworkSessions.markPendingTerminal(req.userId(), childSession, "STOPPED");
                    } catch (Exception ignored) {
                        // 收口失败不影响回答，下一次启动期清理会兜底
                    }
                }
                // 收集型通道不触发 onCompletion，登记必须主动清（否则整轮台账与断开表挂着引用）
                ChatRunRuntime.forgetSseChannel(sink);
                if (childSession != null) {
                    try {
                        sessionService.deleteSession(req.userId(), childSession);
                    } catch (Exception ignored) {
                        // 删失败只多留一条临时会话，不影响回答；硬抛会把已跑完的分支报成失败
                    }
                }
            }
        };
    }

    private void registerBranch(String parentSessionId, BranchHandle h) {
        branches.computeIfAbsent(parentSessionId,
                k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(h);
    }

    private void unregisterBranch(String parentSessionId, BranchHandle h) {
        java.util.Set<BranchHandle> set = branches.get(parentSessionId);
        if (set == null) return;
        set.remove(h);
        if (set.isEmpty()) branches.remove(parentSessionId, set);
    }

    /** 父轮结束（正常/停止/异常）：这一轮派出去、还在跑的分支全部收口。 */
    public void cancelAll(String parentSessionId) {
        java.util.Set<BranchHandle> set = branches.get(parentSessionId);
        if (set == null) return;
        for (BranchHandle h : List.copyOf(set)) cancelHandle(h, "父轮已结束");
    }

    private void cancelHandle(BranchHandle h, String reason) {
        try {
            h.sink().cancel(reason);
        } catch (Exception ignored) {
            // 通道已被收掉不影响下一步收口
        }
        try {
            frameworkSessions.markPendingTerminal(h.userId(), h.childSession(), "STOPPED");
        } catch (Exception ignored) {
            // 下一轮启动期清理会兜底
        }
        try {
            runtime.stopTurn(h.childSession(), h.userId());
        } catch (Exception e) {
            log.warn("[SUBAGENT] 叫停分支失败 session={}: {}", h.childSession(), e.getMessage());
        }
    }

    /** 展示层签名 URL → 原始 URL（与库内/上下文里存的那份同源，才能与主链路的编号表对上） */
    private static List<String> rawUrlsOf(List<String> urls) {
        if (urls == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String u : urls) {
            if (u == null || u.isBlank()) continue;
            int q = u.indexOf('?');
            out.add(q > 0 ? u.substring(0, q) : u);
        }
        return List.copyOf(out);
    }

    /**
     * 用子轮 done 载荷的 toolCalls（name + denied）对齐逐步记录：转发帧里的工具卡不带 denied，
     * 「被挡下」与「跑出错」在时间线上是两种话术；顺手补上转发漏采的步（排尾部，无耗时）。
     */
    private static void alignDeniedFlags(com.alibaba.fastjson2.JSONArray toolCalls,
                                         List<Map<String, Object>> workSteps) {
        if (toolCalls == null || toolCalls.isEmpty()) return;
        synchronized (workSteps) {
            for (int i = 0; i < toolCalls.size(); i++) {
                com.alibaba.fastjson2.JSONObject o = toolCalls.getJSONObject(i);
                if (o == null || o.getString("name") == null) continue;
                String name = o.getString("name");
                boolean denied = o.getBooleanValue("denied");
                boolean matched = false;
                for (Map<String, Object> s : workSteps) {
                    if (name.equals(s.get("n")) && !s.containsKey("d")) {
                        if (denied) s.put("d", true);
                        matched = true;
                        break;
                    }
                }
                if (!matched && !denied && workSteps.size() < BranchReport.MAX_STEPS) {
                    // 转发漏采的兜底：没有耗时与中文名，但比时间线上凭空少一步强
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("n", name);
                    one.put("s", "done");
                    workSteps.add(one);
                }
            }
        }
    }

    /**
     * 子轮审批卡上翻前的加工：补来源（前端据此显示「主助手 / 子助手·名字」），其余字段原样保留。
     * <p>approvalId/tool/args/timeoutMs 不能改——父轮那张卡的裁决端点按 approvalId 找回子会话。
     */
    private static String enrichApprovalCard(String content, String agentName) {
        try {
            com.alibaba.fastjson2.JSONObject card =
                    com.alibaba.fastjson2.JSON.parseObject(content == null ? "" : content);
            if (card == null) return content;
            String name = agentName == null || agentName.isBlank() ? "子助手" : agentName;
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("kind", "subagent");
            src.put("agent", name);
            card.put("subagent", src);
            String hint = card.getString("hint");
            String suffix = "来自「" + name + "」";
            card.put("hint", hint == null || hint.isBlank() ? suffix : hint + " · " + suffix);
            return com.alibaba.fastjson2.JSON.toJSONString(card);
        } catch (Exception e) {
            // 加不上来源不改变这张卡的语义：宁可少一个标签，也不能把审批号弄丢
            return content;
        }
    }
}

