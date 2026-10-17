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

    public SubAgentBranchRuntime(ConfigService configService,
                                SessionService sessionService,
                                ArtifactService artifactService,
                                DocumentMetaCache documentMetaCache,
                                ChatRunRuntime runtime) {
        this.configService = configService;
        this.sessionService = sessionService;
        this.artifactService = artifactService;
        this.documentMetaCache = documentMetaCache;
        this.runtime = runtime;
    }

    public BranchRunner branchRunner(TurnRunner turn, String parentSessionId, String parentTurnId, String userId,
                                     boolean guestMode, SseEmitter parentEmitter) {
        return req -> {
            long start = System.currentTimeMillis();
            String childSession = null;
            CollectingSseEmitter sink = new CollectingSseEmitter();
            // 分支的实时过程（P3a 刀2）：那一支在父轮卡片上原本只有「跑了 N 步工具」这个事后计数，
            // 一支跑两三分钟时用户对着一个静止的数字等。这里把它自己那几路过程事件原样转给父轮通道，
            // 事件名换成 subagent_step、带上分支序号，前端归到对应那一行下面（不重组协议、不改内容）。
            // 只转不发完就没了：这份实时步骤不落库，刷新后看到的仍是步数与回报全文。
            final int branchIndex = Math.max(0, req.branchIndex());
            final boolean[] first = {false};
            sink.forwardProcess((type, content) -> {
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
            });
            try {
                childSession = sessionService.createTempSession(req.userId());
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
                        .subagentDepth(req.depth())
                        .parentBrief(req.parentBrief())
                        .parentSessionId(req.parentSessionId())
                        .build(), sink);
                if (!sink.awaitDone(req.timeoutMs())) {
                    // 超时必须把那一支叫停：它还在流水线里跑，而父轮已经不等它了。
                    // 不叫停的后果是"删了一条还在被写的会话"+"一条线程被派出去的任务占住"，
                    // 并发几轮之后问答池就被没人等的分支填满。
                    runtime.stopTurn(childSession, req.userId());
                    return BranchReport.failed(BranchReport.TIMEOUT,
                            "分支超时（" + req.timeoutMs() / 1000 + " 秒）", System.currentTimeMillis() - start);
                }
                if (sink.lastError() != null && !sink.lastError().isBlank()) {
                    return BranchReport.failed(BranchReport.FAILED, sink.lastError(),
                            System.currentTimeMillis() - start);
                }
                String answer = sink.answer() == null ? "" : sink.answer().strip();
                if (answer.isBlank()) {
                    return BranchReport.failed(BranchReport.FAILED, "子智能体未产生回答",
                            System.currentTimeMillis() - start);
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
                        }
                    }
                } catch (Exception e) {
                    log.warn("[SUBAGENT] 分支回报解析失败（正文照常带回，引用与图片不续编）: {}", e.getMessage());
                }
                // 产物收养：子轮交付的东西归到父会话，用户在父轮里就点得到（跑完即删的临时会话留不住它）
                List<String> artifactFiles = new ArrayList<>();
                try {
                    for (Map<String, Object> a : artifactService.adoptFromSession(childSession, parentSessionId)) {
                        Object f = a.get("filename");
                        if (f != null) artifactFiles.add(String.valueOf(f));
                    }
                } catch (Exception e) {
                    log.warn("[SUBAGENT] 分支产物收养失败（回答仍有效，产物留在子会话）: {}", e.getMessage());
                }
                long ms = System.currentTimeMillis() - start;
                log.info("[SUBAGENT] 全循环分支完成 {}：正文 {}字 引用 {} 条 工具步 {} 产物 {} 个 耗时 {}ms",
                        req.describe(), answer.length(), childSources.size(), steps, artifactFiles.size(), ms);
                return new BranchReport(BranchReport.DONE, answer, List.copyOf(childSources),
                        List.copyOf(childImages), steps, List.copyOf(artifactFiles), ms, null);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("[SUBAGENT] 全循环分支异常（按失败降级，不影响主链路）{}: {}",
                        req.describe(), cause.toString());
                return BranchReport.failed(BranchReport.FAILED, cause.toString(), System.currentTimeMillis() - start);
            } finally {
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
}

