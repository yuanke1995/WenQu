package com.wenqu.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.mapper.AgentEvalResultMapper;
import com.wenqu.ai.mapper.AgentEvalRunMapper;
import com.wenqu.ai.model.AgentEvalCase;
import com.wenqu.ai.model.AgentEvalResult;
import com.wenqu.ai.model.AgentEvalRun;
import com.wenqu.ai.model.AgentEvalSuite;
import com.wenqu.ai.model.Notification;
import com.wenqu.ai.util.UsageAttr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智能体评测执行器：一题一轮走<b>真实问答流水线</b>（检索/人设/工具/引用与网页问答完全一致，
 * {@code source=eval} 让需人工确认的工具按「无在场通道」语义自动拒绝——评测只读，不执行副作用工具）。
 * <p>
 * 执行口径（plan-agent-eval.md §3.5/§6.2）：
 * <ul>
 *   <li>每题 {@code kind=temp} 临时会话，跑完连同消息一起删（finally 兜底），结果表留存答案/引用/指标快照；</li>
 *   <li>专用 daemon 池（2 线程）= 全局并发上限，不占问答流水线线程池；每智能体 1 个运行在 Service 层闸；</li>
 *   <li>单题超时 {@code agentEval.perCaseTimeoutMs}、单次总预算 {@code agentEval.runTimeoutMs}；</li>
 *   <li>可取消：取消标记逐题检查 + 在跑那一题 {@code ChatRunRuntime#stopTurn} 叫停，已完成结果保留；</li>
 *   <li>评判模型调用以 {@code UsageAttr(kind=eval)} 入账（同步通道，runner 线程同线程调用成立）；
 *       作答调用照常走 chat 记账归触发用户。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class AgentEvalRunner {

    private final AgentEvalRunMapper runMapper;
    private final AgentEvalResultMapper resultMapper;
    private final SessionService sessionService;
    private final ChatService chatService;
    private final ChatRunRuntime chatRunRuntime;
    private final AgentEvalScorer scorer;
    private final NotificationService notificationService;
    private final ConfigService configService;

    /** 运行注册表：runId → 取消标记 / 在跑会话 / 展示名（取消入口与执行体两端共用） */
    private final Map<String, RunCtx> active = new ConcurrentHashMap<>();

    private static final class RunCtx {
        final String uid;
        final String agentName;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<String> currentSession = new AtomicReference<>();

        RunCtx(String uid, String agentName) {
            this.uid = uid;
            this.agentName = agentName;
        }
    }

    /** daemon 池：2 线程 = 全局并发 2（plan §3.5）。评测是分钟级批任务，勿并入共享池 */
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "agent-eval");
        t.setDaemon(true);
        return t;
    });

    public AgentEvalRunner(AgentEvalRunMapper runMapper, AgentEvalResultMapper resultMapper,
                           SessionService sessionService, ChatService chatService,
                           ChatRunRuntime chatRunRuntime, AgentEvalScorer scorer,
                           NotificationService notificationService, ConfigService configService) {
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.chatRunRuntime = chatRunRuntime;
        this.scorer = scorer;
        this.notificationService = notificationService;
        this.configService = configService;
    }

    // ==================== 对外 ====================

    /** 提交执行（Service 已做完闸门与 run 落库；cases 为已启用的执行清单，顺序稳定） */
    public void submit(AgentEvalRun run, AgentEvalSuite suite, String agentName, List<AgentEvalCase> cases) {
        RunCtx ctx = new RunCtx(run.getCreatedBy(), agentName);
        active.put(run.getId(), ctx);
        pool.submit(() -> {
            try {
                execute(run, suite, cases, ctx);
            } finally {
                active.remove(run.getId());
            }
        });
    }

    /** 取消：置标记 + 叫停在跑那一题。返回 false = 该运行不在本实例跑（已结束/重启清理过） */
    public boolean cancel(String runId) {
        RunCtx ctx = active.get(runId);
        if (ctx == null) return false;
        ctx.cancelled.set(true);
        String sid = ctx.currentSession.get();
        if (sid != null) {
            try {
                chatRunRuntime.stopTurn(sid, ctx.uid);
            } catch (Exception e) {
                log.debug("[AGENT-EVAL] 取消在跑用例失败（不影响取消标记）run={}: {}", runId, e.getMessage());
            }
        }
        return true;
    }

    // ==================== 执行体 ====================

    private void execute(AgentEvalRun run, AgentEvalSuite suite, List<AgentEvalCase> cases, RunCtx ctx) {
        String uid = run.getCreatedBy();
        long perCaseTimeoutMs = Math.max(30_000, configService.getLong("agentEval.perCaseTimeoutMs", 180_000));
        long runTimeoutMs = Math.max(perCaseTimeoutMs, configService.getLong("agentEval.runTimeoutMs", 900_000));
        long deadline = System.currentTimeMillis() + runTimeoutMs;
        int passed = 0;
        long latencySum = 0;
        long tokensSum = 0;
        double scoreSum = 0;
        int scored = 0;
        int executed = 0;
        String fatal = null;
        try {
            for (AgentEvalCase c : cases) {
                if (ctx.cancelled.get()) break;
                long caseStart = System.currentTimeMillis();
                if (caseStart >= deadline) {
                    fatal = "总预算用尽（" + (runTimeoutMs / 60_000) + " 分钟），剩余用例未执行";
                    break;
                }
                CaseTurn turn = runCase(run, c, ctx, Math.min(perCaseTimeoutMs, deadline - caseStart));
                executed++;
                latencySum += turn.latencyMs();
                tokensSum += turn.tokens();
                if (turn.result().getPassed() != null && turn.result().getPassed() == 1) passed++;
                if (turn.result().getScore() != null) {
                    scoreSum += turn.result().getScore();
                    scored++;
                }
            }
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 评测运行 {} 执行异常: {}", run.getId(), e.getMessage(), e);
            fatal = "执行异常：" + trim(String.valueOf(e.getMessage()), 300);
        }
        finalizeRun(run, suite, ctx.agentName, cases.size(), executed, passed, scored, scoreSum,
                latencySum, tokensSum, ctx.cancelled.get(), fatal);
    }

    /** 单题：临时会话 → 完整问答一轮 → 评分 → 写 result。会话清理在 finally，异常也不泄漏 */
    private CaseTurn runCase(AgentEvalRun run, AgentEvalCase c, RunCtx ctx, long timeoutMs) {
        String uid = run.getCreatedBy();
        long t0 = System.currentTimeMillis();
        String sessionId = sessionService.createTempSession(uid);
        ctx.currentSession.set(sessionId);
        AgentEvalResult result = new AgentEvalResult();
        result.setRunId(run.getId());
        result.setCaseId(c.getId());
        result.setQuestion(trim(c.getQuestion(), 2000));
        long tokens = 0;
        try {
            CollectingSseEmitter sink = new CollectingSseEmitter();
            boolean timeout;
            try {
                chatService.chat(ChatCommand.of(sessionId, c.getQuestion(), uid)
                        .userImages(List.of()).attachments(List.of()).skills(List.of())
                        .agentId(run.getAgentId())
                        .source(TurnLease.KIND_EVAL).sourceName("评测 · " + ctx.agentName)
                        .build(), sink);
                timeout = !sink.awaitDone(timeoutMs);
            } finally {
                // 收集型通道不触发 onCompletion，必须主动清登记，否则 ChatRunRuntime 的 ACTIVE_SSE 残留
                ChatRunRuntime.forgetSseChannel(sink);
            }
            if (ctx.cancelled.get()) return new CaseTurn(result, 0, 0);   // 已取消：不写失败行，外层收口

            if (timeout) {
                fail(result, "单题超时（" + (timeoutMs / 1000) + " 秒）未收到完整回答");
                result.setLatencyMs(System.currentTimeMillis() - t0);
                insertResult(result);
                return new CaseTurn(result, result.getLatencyMs(), 0);
            }
            if (sink.lastError() != null && !sink.lastError().isBlank()) {
                fail(result, "执行失败：" + trim(sink.lastError(), 300));
                result.setLatencyMs(System.currentTimeMillis() - t0);
                insertResult(result);
                return new CaseTurn(result, result.getLatencyMs(), 0);
            }

            String answer = sink.answer();
            JSONObject done = parseDone(sink.donePayload());
            if (done != null) {
                String fc = done.getString("finalContent");
                if (fc != null && !fc.isBlank()) answer = fc;
                JSONObject tk = done.getJSONObject("tokens");
                if (tk != null && tk.getLong("total") != null) tokens = tk.getLong("total");
            }
            List<Map<String, Object>> sources = sourcesOf(done);
            JSONArray toolCalls = done == null ? null : done.getJSONArray("toolCalls");

            // 引用召回 → 评判 → 综合分（评判在 UsageAttr kind=eval 下同步调用，费用单列「评测」）
            AgentEvalScorer.RecallOutcome rec = scorer.recall(c, sources);
            Object[] faithRel = null;
            if (run.getJudgeModel() != null && !run.getJudgeModel().isBlank()) {
                UsageAttr.hold(new UsageAttr.Attr(uid, sessionId, null, "eval"));
                try {
                    faithRel = scorer.judge(c.getQuestion(), sources, answer, run.getJudgeModel());
                } catch (Exception e) {
                    log.warn("[AGENT-EVAL] 用例评判失败（按引用召回单独计分）run={} case={}: {}",
                            run.getId(), c.getId(), e.getMessage());
                } finally {
                    UsageAttr.clear();
                }
            }
            AgentEvalScorer.ScoreOutcome so = scorer.score(c, rec, faithRel,
                    run.getPassScore() == null ? 60 : run.getPassScore());

            result.setSources(trim(JSON.toJSONString(sources == null ? List.of() : sources), 32 * 1024));
            result.setToolCalls(toolCalls == null ? null : trim(toolCalls.toJSONString(), 8 * 1024));
            result.setAnswer(trim(answer, 8000));
            result.setRecall(so.recall());
            result.setFaithfulness(so.faithfulness());
            result.setRelevance(so.relevance());
            result.setJudgeNote(so.judgeNote());
            result.setScore(so.score());
            result.setPassed(so.passed() ? 1 : 0);
            result.setFailureReason(so.failureReason());
            result.setTokens(tokens);
            result.setLatencyMs(System.currentTimeMillis() - t0);
            insertResult(result);
            return new CaseTurn(result, result.getLatencyMs(), tokens);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 用例执行失败 run={} case={}: {}", run.getId(), c.getId(), e.getMessage(), e);
            fail(result, "执行失败：" + trim(String.valueOf(e.getMessage()), 300));
            result.setLatencyMs(System.currentTimeMillis() - t0);
            insertResult(result);
            return new CaseTurn(result, result.getLatencyMs(), tokens);
        } finally {
            ctx.currentSession.set(null);
            try {
                sessionService.deleteSession(uid, sessionId);
            } catch (Exception ignored) {
                // 临时会话删除失败不炸主流程（kind=temp 不进会话列表，残留由会话清理兜底）
            }
        }
    }

    /** 运行收口：聚合指标 + 终态 + 站内通知（通知旁路语义，失败不影响收口） */
    private void finalizeRun(AgentEvalRun run, AgentEvalSuite suite, String agentName, int total, int executed,
                             int passed, int scored, double scoreSum, long latencySum, long tokensSum,
                             boolean cancelled, String fatal) {
        try {
            AgentEvalRun update = runMapper.selectById(run.getId());
            if (update == null) return;
            update.setTotal(total);
            update.setPassed(passed);
            if (executed > 0) {
                update.setPassRate(round2(passed * 100.0 / executed));
                update.setAvgLatencyMs(latencySum / executed);
            } else {
                update.setPassRate(null);
                update.setAvgLatencyMs(null);
            }
            update.setAvgScore(scored == 0 ? null : round2(scoreSum / scored));
            update.setTotalTokens(tokensSum);
            update.setFinishedAt(LocalDateTime.now());
            if (fatal != null) {
                update.setStatus("failed");
                update.setError(fatal);
            } else if (cancelled) {
                update.setStatus("cancelled");
                update.setError("已取消：已完成 " + executed + "/" + total + " 条，结果保留");
            } else {
                update.setStatus("succeeded");
            }
            runMapper.updateById(update);
            notifyOutcome(update, suite, agentName, executed, total);
            log.info("[AGENT-EVAL] 运行 {} 结束：status={} {}/{} 通过率={} 平均分={}",
                    run.getId(), update.getStatus(), passed, executed, update.getPassRate(), update.getAvgScore());
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 评测运行收口失败 run={}: {}", run.getId(), e.getMessage(), e);
        }
    }

    private void notifyOutcome(AgentEvalRun run, AgentEvalSuite suite, String agentName, int executed, int total) {
        String name = agentName == null || agentName.isBlank() ? run.getAgentId() : agentName;
        try {
            String title;
            String content;
            if ("succeeded".equals(run.getStatus())) {
                title = "评测完成：" + name + " 通过率 "
                        + AgentEvalScorer.trimNum(run.getPassRate() == null ? 0 : run.getPassRate()) + "%";
                content = gateLine(run, suite) + "，共 " + executed + "/" + total + " 条。点击查看逐条结果。";
            } else if ("cancelled".equals(run.getStatus())) {
                title = "评测已取消：" + name;
                content = run.getError() == null ? "本轮回归被取消。" : run.getError();
            } else {
                title = "评测失败：" + name;
                content = run.getError() == null ? "本轮回归未完成，详情见运行历史。" : run.getError();
            }
            notificationService.create(run.getCreatedBy(),
                    "failed".equals(run.getStatus()) ? Notification.TYPE_AGENT_EVAL_FAILED : Notification.TYPE_AGENT_EVAL_DONE,
                    title, content, "agentEval", run.getAgentId());
        } catch (Exception e) {
            log.debug("[AGENT-EVAL] 站内通知发送失败（不影响收口）: {}", e.getMessage());
        }
    }

    private String gateLine(AgentEvalRun run, AgentEvalSuite suite) {
        Double threshold = run.getThresholdRate() != null ? run.getThresholdRate() : suite.getThresholdRate();
        double th = threshold == null ? 80 : threshold;
        Double rate = run.getPassRate();
        if (rate == null) return "本轮无有效结果";
        return (rate >= th ? "达标（门槛 " : "低于门槛（门槛 ") + AgentEvalScorer.trimNum(th) + "%）";
    }

    // ==================== 小工具 ====================

    private void insertResult(AgentEvalResult r) {
        try {
            resultMapper.insert(r);
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 评测结果落库失败 run={} case={}: {}", r.getRunId(), r.getCaseId(), e.getMessage());
        }
    }

    /** 失败行公共填充：问题快照调用方已设，这里补失败语义 */
    private void fail(AgentEvalResult r, String reason) {
        r.setPassed(0);
        r.setFailureReason(reason);
    }

    /**
     * 解析 done 载荷。坑账：{@code CollectingSseEmitter} 把 done 帧的 content（本身就是一份 JSON 串）
     * 再 {@code toJSONString} 了一次——落进来的是「字符串里套 JSON」的双层形态，直接 parseObject 会拿到
     * 一个字符串而不是对象，sources/toolCalls/tokens 会整体静默丢失。这里先 parse 成通用对象，
     * 是字符串就再剥一层。
     */
    private JSONObject parseDone(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            Object o = JSON.parse(payload);
            if (o instanceof JSONObject jo) return jo;
            if (o instanceof String s && !s.isBlank()) {
                Object inner = JSON.parse(s);
                if (inner instanceof JSONObject jo2) return jo2;
            }
        } catch (Exception ignored) {
            // 坏载荷按无 sources/tokens 处理（回答正文仍取自 token 流累积，不受影响）
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sourcesOf(JSONObject done) {
        if (done == null) return List.of();
        JSONArray arr = done.getJSONArray("sources");
        return arr == null ? List.of() : (List<Map<String, Object>>) (List<?>) arr.toJavaList(Map.class);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record CaseTurn(AgentEvalResult result, long latencyMs, long tokens) {
    }
}
