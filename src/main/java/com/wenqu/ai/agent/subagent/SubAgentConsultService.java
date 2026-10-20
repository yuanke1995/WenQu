package com.wenqu.ai.agent.subagent;

import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.service.AgentService;
import com.wenqu.ai.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能体咨询的执行侧（P5 agent-as-tool）：预算、去重、跑分支、把回报转成给模型的文本。
 * <p>
 * 独立成服务而不是写进问答侧，为的是让"这一轮咨询了谁、花了几次"这件事有一份明确归属：
 * 台账对象按轮创建（{@link #newLedger()}），由工具上下文带着走，没有静态表、也没有 ThreadLocal
 * ——本仓 P1 那一刀删掉的就是"池化线程漏清把上一轮状态留给下一个会话"这类写法。
 * <p>
 * <b>引用编号的口径</b>：分支回报里的 {@code [N]} 是<b>子轮自己的编号</b>，直接拼进父轮上下文会让
 * 模型抄出一个指错资料的角标（{@link BranchReport} 的类注释把这条写得很重）。编排器那条路有整表回填
 * （占位符 {@code ⟦b<支>:r<号>⟧} → 父轮编号），工具这条路不搭那趟车：它一次只回来一份回报，
 * 于是改成「剥掉子轮编号 + 把子轮资料注册成父轮引用 + 明确告诉模型该标哪几个号」。
 * 少了一段映射，换来的是不会指错——宁可模型引用得粗，也不要引用到别的资料上。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class SubAgentConsultService {

    /** 正文里的引用角标（子轮编号）：{@code [3]}、{@code [图片2]} 两类都要清，前者清成空、后者原样留着没意义 */
    private static final Pattern CHILD_REF = Pattern.compile("\\[(?:图片)?\\d+]");

    private final ConfigService configService;
    private final AgentService agentService;

    public SubAgentConsultService(ConfigService configService, AgentService agentService) {
        this.configService = configService;
        this.agentService = agentService;
    }

    /** 本轮的咨询台账（一轮一个，不跨轮复用） */
    public Ledger newLedger() {
        return new Ledger();
    }

    /**
     * 跑一次咨询所需的父轮上下文（一轮内不变）。
     *
     * @param onConsulted 一次咨询真跑通之后的回调（id, 名称）——问答侧用它发「本轮咨询了谁」的可见事件。
     *                    刻意只在成功时回调：失败的咨询没交出任何东西，标成"咨询过"等于虚报工作量。
     */
    public record Ctx(String parentSessionId, String parentTurnId, String userId, String modelRef,
                      boolean guestMode, int depth, String selfAgentId, String preConsultedAgentId,
                      java.util.function.BiConsumer<String, String> onConsulted) {
    }

    /**
     * 把子轮来源<b>整批</b>登记成父轮引用的出口；登记不了（被父轮相关度门/配额滤掉、不在本轮检索范围）
     * 的条数从返回值里自然缺席，调用方据此如实告诉模型"另有 N 处未登记"。
     * <p>由问答侧提供实现——它才知道父轮的引用表与编号空间在哪，也持着父轮问题与重排服务
     * （门必须按<b>父轮问题</b>判，子轮的门管不了父轮的面板，见 {@link #render}）。
     */
    @FunctionalInterface
    public interface SourceSink {

        /** 一条登记成功的来源：父轮编号 + 标题（回报文本列给模型用） */
        record Registered(int ref, String title) {
        }

        /**
         * 整批登记；实现方内部做相关度门与配额后返回实际登记上的条目（保序）。
         *
         * @param childSources 子轮交回的来源清单（{@code sources} 原样）
         * @return 登记（含复用既有编号）的条目；空列表 = 一条都没进
         */
        List<Registered> registerAll(List<Map<String, Object>> childSources);
    }

    /** 本轮已咨询过的助手 → 当时那份「给模型的文本」（同一位问两次不该再花一整条子轮） */
    public static final class Ledger {
        private final Map<String, String> answered = new LinkedHashMap<>();
        /**
         * 每次真跑过的咨询那一份「子对话」（助手 id → 结构化回报），供聊天区显示问答。
         * <p>按 id 存而不是一个槽位：一轮可以咨询多位，且工具在弹性线程上并发跑，
         * 单槽会被后跑的那一位盖掉前一位（问答侧取走即摘，见 {@link #takeReport}）。
         */
        private final Map<String, Map<String, Object>> reports = new ConcurrentHashMap<>();
        private int started;

        synchronized String cached(String agentId) {
            return answered.get(agentId);
        }

        synchronized void remember(String agentId, String text) {
            answered.put(agentId, text);
        }

        /** 记一次已发起的咨询（上限判定在 consult 里做，这里只计数） */
        synchronized void start() {
            started++;
        }

        synchronized int used() {
            return started;
        }

        void report(String agentId, Map<String, Object> payload) {
            if (agentId != null && payload != null) reports.put(agentId, payload);
        }

        /** 问答侧取走某一位的子对话（取走即摘：一条咨询只对应一张卡片，也不留跨轮残留） */
        public Map<String, Object> takeReport(String agentId) {
            return agentId == null ? null : reports.remove(agentId);
        }
    }

    /** 一次咨询的两个出口：给模型的文本 + 给用户看的子对话（同一次跑出来的两份，编号口径必须一致） */
    private record Rendered(String text, Map<String, Object> chat) {
    }

    /**
     * 执行一次咨询。返回给模型的文本；任何失败都返回文本而不抛异常（一次咨询不该打断整轮）。
     *
     * @param runner 本轮的分支执行器（父轮回传，见 {@link BranchRunner} 的接法理由）
     * @param sink   父轮引用登记器（null = 本轮不接引用，回报里就不提编号）
     */
    public String consult(Ledger ledger, Ctx ctx, BranchRunner runner, SourceSink sink,
                          String agentId, String question) {
        if (agentId == null || agentId.isBlank()) return "错误：agent_id 不能为空，请从「派遣助手」清单里取 id。";
        if (question == null || question.isBlank()) return "错误：question 不能为空，写清楚要它查什么。";
        if (runner == null) return "咨询通道本轮未就绪（没有可用的执行器），请基于已有资料回答。";

        String id = agentId.strip();
        if (id.equals(ctx.selfAgentId())) {
            return "你自己就是这位助手，无需咨询自己。请直接回答，或改问清单里的其他助手。";
        }
        Agent a = agentService.get(id);
        if (a == null) {
            return "查无这位助手：id=" + id + " 不在派遣助手清单里。请按清单里的 agent_id 逐字使用，不要猜。";
        }
        if (!agentService.consultCandidates(ctx.selfAgentId()).stream().anyMatch(x -> x.getId().equals(id))) {
            // 存在但不可读/已停用：与"猜错 id"分开报，前者是权限问题，排查方向完全不同
            return "没有权限咨询「" + a.getName() + "」（不在本轮可读范围内）。请改问清单里的其他助手。";
        }

        if (id.equals(ctx.preConsultedAgentId())) {
            // @ 已经强制咨询过这一位，结论就在本轮上下文里：模型再调一次工具就是同一条子轮跑两遍
            //（实测发生过：@ 智报 + 模型自己又派了智报 → 两条分支、双份开销）
            return "本轮已经咨询过这位助手，它的结论与资料已在你的上下文里，无需重复咨询；请据此作答，或改问清单里的其他助手。";
        }
        String hit = ledger.cached(id);
        if (hit != null) {
            log.info("[CONSULT] 本轮已咨询过 {}，直接回上次回报（省一条子轮）", a.getName());
            return hit + "\n\n（提示：这位助手本轮已经答过，上面就是它的回报，不要再问它。）";
        }
        int cap = Math.max(1, configService.getInt("agent.consultMaxPerTurn", ConfigDefaults.AGENT_CONSULT_MAX_PER_TURN));
        if (ledger.used() + 1 > cap) {
            log.info("[CONSULT] 本轮咨询已达上限 {}，拒绝对 {} 的咨询", cap, a.getName());
            return "本轮最多咨询 " + cap + " 位助手，额度已用完。请基于已拿到的资料作答。";
        }
        ledger.start();

        long timeoutMs = Math.max(5_000, configService.getLong("agent.consultTimeoutMs"));
        long t0 = System.currentTimeMillis();
        BranchReport report;
        try {
            report = runner.run(new BranchRequest(ctx.parentSessionId(), ctx.parentTurnId(),
                    a.getId(), a.getName(), question, "", ctx.depth() + 1, ctx.userId(),
                    ctx.modelRef(), ctx.guestMode(), timeoutMs, -1));
        } catch (Exception e) {
            // 执行器契约是不外抛；真抛了说明契约破了，这里兜住并留真因（CompletableFuture 那类包装的 message 常为 null）
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[CONSULT] 咨询 {} 异常: {}", a.getName(), cause.toString());
            ledger.report(id, failedChat(a, question, BranchReport.FAILED, cause.getMessage(),
                    System.currentTimeMillis() - t0));
            return "咨询「" + a.getName() + "」失败：" + cause.getMessage() + "。请基于已有资料回答，并说明这一部分没核实到。";
        }
        long ms = System.currentTimeMillis() - t0;
        if (!report.usable()) {
            String why = report.error() == null || report.error().isBlank() ? "未产出可用结论" : report.error();
            String text = "咨询「" + a.getName() + "」没有拿到结果（" + why + "，用时 " + ms / 1000 + " 秒）。"
                    + "换一位助手或换个问法再试，也可以基于现有资料作答并说明这一部分没查到。";
            ledger.remember(id, text);
            ledger.report(id, failedChat(a, question, report.status(), why, ms));
            return text;
        }

        Rendered r = render(a, question, report, sink, ms);
        ledger.remember(id, r.text());
        ledger.report(id, r.chat());
        if (ctx.onConsulted() != null) {
            try {
                ctx.onConsulted().accept(a.getId(), a.getName());
            } catch (Exception e) {
                log.debug("[CONSULT] 咨询可见回调失败（不影响回报）: {}", e.getMessage());
            }
        }
        log.info("[CONSULT] 咨询 {} 完成：回报 {} 字、资料 {} 条、工具步 {}、用时 {}ms（本轮第 {} 次）",
                a.getName(), report.answerOrEmpty().length(), report.sourcesOrEmpty().size(),
                report.toolSteps(), ms, ledger.used());
        return r.text();
    }

    /**
     * 没跑成的那一次也要留一份子对话：卡片上如实写「没问到结果（超时/失败 + 原因）」，
     * 而不是这一位从聊天区悄悄少一张卡——「失败要看得见」与工具卡同一条口径。
     */
    private static Map<String, Object> failedChat(Agent a, String question, String status, String error, long ms) {
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("agentId", a.getId());
        chat.put("agentName", a.getName());
        chat.put("question", question == null ? "" : question.strip());
        chat.put("status", BranchReport.TIMEOUT.equals(status) ? BranchReport.TIMEOUT : BranchReport.FAILED);
        chat.put("error", error == null ? "" : error);
        chat.put("elapsedMs", ms);
        return chat;
    }

    /** 回报 → 给模型的文本 + 给用户看的子对话：清掉子轮编号、登记父轮引用、把可用的引用号如实列出来 */
    private Rendered render(Agent a, String question, BranchReport report, SourceSink sink, long ms) {
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(a.getName()).append("」的回报（用时 ").append(ms / 1000)
                .append(" 秒，它自己跑了 ").append(report.toolSteps()).append(" 步工具）：\n");
        // 子轮编号此刻仍指向它自己的引用表，原样带回去就是让父轮抄错号——一律清掉，资料靠下面那份清单进上下文
        String body = CHILD_REF.matcher(report.answerOrEmpty()).replaceAll("");
        sb.append(body.isBlank() ? "（无正文）" : body.strip());
        if (!report.artifactFilesOrEmpty().isEmpty()) {
            sb.append("\n\n它交付的产物：").append(String.join("、", report.artifactFilesOrEmpty()));
        }
        List<Map<String, Object>> registered = new ArrayList<>();
        if (sink != null && !report.sourcesOrEmpty().isEmpty()) {
            // 整批送父轮登记：门与配额由父轮按自己的问题判（子轮的门是子轮问题的门，
            // 管不了父轮面板——修复前这里逐条全量搬入，一场跑偏的咨询能把 28 条无关引用摆进用户眼前）
            List<SourceSink.Registered> regs = sink.registerAll(report.sourcesOrEmpty());
            if (!regs.isEmpty()) {
                StringBuilder refs = new StringBuilder();
                for (SourceSink.Registered reg : regs) {
                    if (refs.length() > 0) refs.append("、");
                    refs.append('[').append(reg.ref()).append(']').append('《')
                            .append(String.valueOf(reg.title())).append('》');
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("ref", reg.ref());
                    one.put("title", String.valueOf(reg.title()));
                    registered.add(one);
                }
                sb.append("\n\n它的结论依据这些资料，已登记进你的引用表：").append(refs)
                        .append("。引用时请在正文里按这些编号标注（形如 ").append('[').append("编号]").append("）。");
            }
            int dropped = report.sourcesOrEmpty().size() - regs.size();
            if (dropped > 0) {
                sb.append("\n（另有 ").append(dropped).append(" 处资料未通过本轮相关度校验或超出引用配额，未登记为引用，不要在正文里引用它们。）");
            }
        }
        // 给用户看的那一份：编号用的是**父轮**编号（与下面这份资料清单同源），
        // 所以子轮自己那些 [N] 一律不带进回答正文——与模型看到的那份保持同一把尺
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("agentId", a.getId());
        chat.put("agentName", a.getName());
        chat.put("question", question == null ? "" : question.strip());
        chat.put("answer", body.strip());
        chat.put("status", BranchReport.DONE);
        chat.put("toolSteps", report.toolSteps());
        chat.put("elapsedMs", ms);
        chat.put("sources", registered);
        chat.put("artifacts", report.artifactFilesOrEmpty());
        return new Rendered(sb.toString(), chat);
    }
}
