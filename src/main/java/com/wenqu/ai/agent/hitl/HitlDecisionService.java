package com.wenqu.ai.agent.hitl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wenqu.ai.agent.tool.ToolRegistry;
import com.wenqu.ai.mapper.ToolApprovalMapper;
import com.wenqu.ai.model.ToolApproval;
import com.wenqu.ai.service.ConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 人在回路裁决服务（P6 刀 1，自 {@code ChatService} 抽出）。
 * <p>三张卡共用同一套「挂起—恢复」管道：内存 future 阻塞着等人的那根线程 + c_ai_tool_approval 里一条
 * PENDING 记录（卡片的权威源）+ SSE 卡片（只触达正开着页面的人）+ 站内通知（人不在页面时的可感知面，
 * 由问答运行时发起，不在本类）。本类只管前两件：<b>谁的哪张卡还挂在等、裁决怎么落终态、
 * 刷新或换设备后怎么把它捞回来</b>。
 * <p>SSE 下发与阻塞等待仍在运行时里（它们持有本轮的 emitter 与计时），因为通道是「这一轮」的出口，
 * 不是卡片的内容。
 * <p><b>为什么登记表是实例字段而不是 static</b>（此前是 static final）：静态 Map 让任何代码都能绕过
 * 方法签名往本轮状态上伸手，正是 P6 要避免的那种跨类共享可变口袋；收成实例字段后唯一的持有者就是本类，
 * 外面只能经 {@code openX} / {@code resolveX} / {@code terminateSessionWaits} 进来。
 * 语义等价：Spring 单例 + 本类只被这一个 bean 实例化，static 与实例字段在这里指向同一张表。
 * <p><b>一条不能破的顺序（有真机事故，2026-10-12）</b>：卡片落终态必须在掐流之前。见
 * {@link #terminateSessionWaits}。这条约束原本是靠调用方记住两个方法的先后，现在收进一个方法内部。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class HitlDecisionService {

    // ==================== 提问卡的本轮限制（执行期 askUser 与计划前澄清共用一份口径） ====================

    /** askUser 单轮提问上限：防模型把「一问一答」变成刷屏循环；正常澄清 1~2 问足够 */
    public static final int MAX_ASKS_PER_TURN = 3;

    /** askUser 单张卡片承载的问题上限（一卡多问）：超出则整卡拒绝、让模型合并后重问。
     *  刻意不做「静默截断保留前 N」：截断会让模型拿到的答案数组比它自己传入的问题数短，
     *  下标对不上（模型会把第 N 个答案当成第 N+1 问的），且界面上会出现「有问无答」的残缺问。 */
    public static final int MAX_QUESTIONS_PER_CARD = 6;

    /** askUser 单题候选上限：卡面固定「3 个候选 + 1 行自由输入」共 4 行，超过就不像选择题而像阅读理解。
     *  模型给多了按前 N 个截断（第一项是推荐项，截掉的是次选），不整题丢弃——丢题会让答案数组与模型
     *  传入的问题数对不上，界面上还会出现「有问无答」的残缺问。 */
    public static final int MAX_ASK_OPTIONS = 3;


    private final ConfigService configService;
    private final ToolApprovalMapper toolApprovalMapper;
    private final ToolRegistry toolRegistry;

    public HitlDecisionService(ConfigService configService,
                               ToolApprovalMapper toolApprovalMapper,
                               ToolRegistry toolRegistry) {
        this.configService = configService;
        this.toolApprovalMapper = toolApprovalMapper;
        this.toolRegistry = toolRegistry;
    }

    // ==================== 挂起登记 ====================

    /**
     * 一张挂在等人裁决的卡的唤醒句柄。登记时给出，{@code close()} 即摘掉登记——
     * 等待方用 try-with-resources 包住等待段，就不会出现「解了 future 却漏摘登记」。
     *
     * @param future 等待方 {@code get(timeout)} 的那只 future；裁决侧 complete 它
     */
    public static final class WaitHandle<T> implements AutoCloseable {
        private final ConcurrentMap<String, ?> registry;
        private final String id;
        private final CompletableFuture<T> future;

        WaitHandle(ConcurrentMap<String, ?> registry, String id, CompletableFuture<T> future) {
            this.registry = registry;
            this.id = id;
            this.future = future;
        }

        public CompletableFuture<T> future() {
            return future;
        }

        @Override
        public void close() {
            registry.remove(id);
        }
    }

    /** 工具执行审批挂起项：approvalId → 等待用户批准（内存态；进程重启即失效，超时自动拒绝） */
    private record PendingApproval(String sessionId, String userId, String toolName,
                                   CompletableFuture<Boolean> future) {
    }

    /**
     * askUser 挂起项：askId → 等待用户作答的内存唤醒句柄。
     * <p>只有它负责「把答案送回还阻塞着的工具线程」；卡片本体（问题/时限/答案）以 c_ai_tool_approval
     * 为准，所以刷新页面或换设备后仍能查到并作答。进程重启后句柄消失（live=false），此时作答只入审计、
     * 无法送达模型，该轮按超时收尾。
     */
    private record PendingAsk(String sessionId, String userId, CompletableFuture<String> future) {
    }

    /** 计划批准挂起项：planApprovalId → 等待用户批准/退回重出（内存态；进程重启后句柄消失，超时按未批准收尾）。
     *  与工具审批/askUser 同一套挂起-恢复管道：内存 future 阻塞流水线线程 + DB 卡片记录 + SSE 卡 + 站内通知 */
    private record PendingPlan(String sessionId, String userId, CompletableFuture<PlanDecision> future) {
    }

    private final ConcurrentMap<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingAsk> pendingAsks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingPlan> pendingPlans = new ConcurrentHashMap<>();

    /** 单版计划的用户裁决：approve=批准（plan 为批准版文本，可能沿 API 带过用户改稿）；
     *  reject=取消本轮（plan/feedback 均为 null）；revise=退回让模型按 feedback 重出下一版；
     *  supersede=用户带着修改意见继续对话（本版作废，新一轮会产出修改后的计划） */
    public record PlanDecision(boolean approved, String plan, String reviseFeedback, boolean superseded) {
        static PlanDecision approve(String plan) { return new PlanDecision(true, plan, null, false); }
        static PlanDecision reject() { return new PlanDecision(false, null, null, false); }
        static PlanDecision revise(String feedback) { return new PlanDecision(false, null, feedback, false); }
        static PlanDecision supersede() { return new PlanDecision(false, null, null, true); }
    }

    /** askUser 的单题内部表示（落库与下发的形状都从它派生，见 {@link #askArgsJson} 与 {@link #questionsPayload}）。
     *  {@code multi} 决定卡片是单选还是可勾选多项，<b>必须随题面一起过落库这一道</b>：刷新/换设备的卡片
     *  是从 {@code request_args} 还原的，这里少带一个字段，恢复出来的多选就会变成单选（勾第二个会把第一个顶掉）。 */
    public record QItem(String topic, String question, List<String> options, boolean multi) {
    }

    /**
     * 挂起一张工具执行审批：落 PENDING 记录（审计与跨设备恢复的权威源）+ 登记唤醒句柄。
     * <p>落库失败只警告不阻塞——卡片看不见时用户无法裁决，但那根线程仍会在超时后自己收尾；
     * 反过来若因为审计写不进就不等，用户侧表现是「工具莫名其妙跑了」，更糟。
     */
    public WaitHandle<Boolean> openApproval(String approvalId, String sessionId, String userId, String turnId,
                                            String toolName, String toolInput) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            ToolApproval rec = new ToolApproval();
            rec.setId(approvalId);
            rec.setSessionId(sessionId);
            rec.setUserId(userId);
            rec.setTurnId(turnId);
            rec.setToolName(toolName);
            rec.setStatus("PENDING");
            String argsSummary = toolInput == null ? ""
                    : (toolInput.length() > 2000 ? toolInput.substring(0, 2000) : toolInput);
            rec.setRequestArgs(argsSummary);
            rec.setCreatedAt(LocalDateTime.now());
            toolApprovalMapper.insert(rec);
        } catch (Exception e) {
            log.warn("[TOOL] 审批记录落库失败（不阻塞审批流程）: {}", e.getMessage());
        }
        pendingApprovals.put(approvalId, new PendingApproval(sessionId, userId, toolName, future));
        return new WaitHandle<>(pendingApprovals, approvalId, future);
    }

    /**
     * 为<b>框架引擎</b>的待办落一条同表记录（P3/人在回路平移）：卡片要能在刷新/换设备后经
     * {@code /tool-approval/pending}、{@code /ask-user/pending}、{@code /plan-approval/pending} 取回。
     * <p>刻意只做「落这一行」：不建 future、不注册内存唤醒句柄——框架那侧的等待与接续执行归
     * {@code AgentSession}（pending/respond 是一等公民），这里要的只是同一份持久化与审计形状。
     * <p>已存在同 id 就原样返回（幂等：待办每轮会被复查多次，重复 insert 会撞主键）。
     *
     * @param toolName 确认类填工具名；提问类填 {@code askUser}；计划卡填 {@code planApproval}（与手写侧同词）
     */
    public void recordFrameworkPending(String id, String sessionId, String userId, String turnId,
                                       String toolName, String requestArgs) {
        if (id == null || id.isBlank()) return;
        try {
            if (toolApprovalMapper.selectById(id) != null) return;
            ToolApproval rec = new ToolApproval();
            rec.setId(id);
            rec.setSessionId(sessionId);
            rec.setUserId(userId);
            rec.setTurnId(turnId);
            rec.setToolName(toolName);
            rec.setStatus("PENDING");
            rec.setRequestArgs(requestArgs == null ? ""
                    : (requestArgs.length() > 2000 ? requestArgs.substring(0, 2000) : requestArgs));
            rec.setCreatedAt(java.time.LocalDateTime.now());
            toolApprovalMapper.insert(rec);
        } catch (Exception e) {
            log.warn("[AGENTX] 框架待办落库失败（不阻塞本轮，但刷新后这张卡取不回）: {}", e.getMessage());
        }
    }

    /** 按 ID 取那条待办记录（框架恢复路径要用：归属会话/用户/轮次都只在这张表里）。不存在返回 null。 */
    public com.wenqu.ai.model.ToolApproval findApproval(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return toolApprovalMapper.selectById(id);
        } catch (Exception e) {
            log.debug("[AGENTX] 查待办记录失败: {}", e.getMessage());
            return null;
        }
    }

    /** 框架待办被答复/裁决：把这一行落成终态（超时与忽略都不代答，只标状态）。 */
    public void resolveFrameworkPending(String id, String status, String uid, String answer) {
        try {
            ToolApproval rec = id == null ? null : toolApprovalMapper.selectById(id);
            if (rec == null || uid != null && !uid.equals(rec.getUserId())) return;
            rec.setStatus(status);
            if (answer != null && !answer.isBlank()) rec.setAnswer(answer);
            rec.setResolvedAt(java.time.LocalDateTime.now());
            toolApprovalMapper.updateById(rec);
        } catch (Exception e) {
            log.warn("[AGENTX] 框架待办终态回写失败: {}", e.getMessage());
        }
    }

    /** 这条待办是否属于框架引擎且仍在等（恢复端点据此标 live，避免「卡片取回却没人能答」）。 */
    public java.util.Set<String> pendingIdsOf(String sessionId, String uid) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (sessionId == null) return out;
        try {
            for (ToolApproval rec : toolApprovalMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ToolApproval>()
                    .eq(ToolApproval::getSessionId, sessionId)
                    .eq(ToolApproval::getUserId, uid)
                    .eq(ToolApproval::getStatus, "PENDING"))) {
                if (rec.getId() != null) out.add(rec.getId());
            }
        } catch (Exception e) {
            log.debug("[AGENTX] 列待办失败（按空集处理）: {}", e.getMessage());
        }
        return out;
    }

    /** 挂起一张 askUser 提问卡（一卡多问）：问题列表存 request_args，答案数组存 answer。 */
    public WaitHandle<String> openAsk(String askId, String sessionId, String userId, String turnId,
                                      List<QItem> questions) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            ToolApproval rec = new ToolApproval();
            rec.setId(askId);
            rec.setSessionId(sessionId);
            rec.setUserId(userId);
            rec.setTurnId(turnId);
            rec.setToolName("askUser");
            rec.setStatus("PENDING");
            rec.setRequestArgs(askArgsJson(questions));
            rec.setCreatedAt(LocalDateTime.now());
            toolApprovalMapper.insert(rec);
        } catch (Exception e) {
            log.warn("[ASK] 提问记录落库失败（不阻塞提问流程）: {}", e.getMessage());
        }
        pendingAsks.put(askId, new PendingAsk(sessionId, userId, future));
        return new WaitHandle<>(pendingAsks, askId, future);
    }

    /** 挂起一张执行计划批准卡：本版计划文本存 request_args，批准稿/修改意见存 answer。 */
    public WaitHandle<PlanDecision> openPlan(String planApprovalId, String sessionId, String userId, String turnId,
                                             String planDraft) {
        CompletableFuture<PlanDecision> future = new CompletableFuture<>();
        try {
            ToolApproval rec = new ToolApproval();
            rec.setId(planApprovalId);
            rec.setSessionId(sessionId);
            rec.setUserId(userId);
            rec.setTurnId(turnId);
            rec.setToolName("planApproval");
            rec.setStatus("PENDING");
            rec.setRequestArgs(JSON.toJSONString(Map.of("plan", planDraft)));
            rec.setCreatedAt(LocalDateTime.now());
            toolApprovalMapper.insert(rec);
        } catch (Exception e) {
            log.warn("[PLAN] 计划记录落库失败（不阻塞计划流程）: {}", e.getMessage());
        }
        pendingPlans.put(planApprovalId, new PendingPlan(sessionId, userId, future));
        return new WaitHandle<>(pendingPlans, planApprovalId, future);
    }

    // ==================== 会话级终止（叫停 / 无观众宽限中止） ====================

    /**
     * 这一轮不再往下跑了：先把挂在人身上的卡片落成终态，再解开还在阻塞的等待。
     * <p><b>顺序不能倒</b>：{@code disposeSafe()} 会中断那根正阻塞在 {@code future.get()} 上的工具线程，
     * 它走的是 {@code InterruptedException} 分支——既不落库、其 {@code finally} 还要把挂起项从表里摘掉。
     * 放在掐流之后就变成对着空表落库，记录照旧停在 PENDING（2026-10-12 真机抓到：叫停返回成功，
     * 而 {@code /tool-approval/pending} 仍把那条当待批，日志里先出「审批未通过 outcome=CANCELLED」再出解开）。
     * <p>落库都带「非 PENDING 即拒绝」的幂等闸，所以流水线线程随后那一次 TIMEOUT 写入自然落空，先到先得。
     * <p>不解开 future 的话，流水线线程会一直挂在 {@code future.get(timeout)} 上、会话互斥要等超时才释放
     * ——「停了还不能马上问下一句」。以前只解 future 不落库：{@code complete(false)} / {@code complete(reject())}
     * 让等待处的 {@code TimeoutException} 分支变成不可达，那条记录就一直停在 PENDING，审计看不出这一轮是谁停的。
     * <p>提问卡的答案留空：没答就是没答，不代答（与超时不代答同一条口径）。
     *
     * @param endReason {@code STOPPED}=用户叫停，{@code INTERRUPTED}=无观众宽限到点中止（审计要分得开这两种）
     */
    public void terminateSessionWaits(String sessionId, String endReason) {
        if (sessionId == null) return;
        markSessionWaitsTerminal(sessionId, endReason);
        releaseSessionWaits(sessionId, endReason);
    }

    /** 解开该会话所有挂在人身上的等待：审批按拒绝、提问按中止（走既有 ExecutionException 分支，
     *  回给模型「用户中止了本轮回答」）、计划按未批准终止本轮。 */
    private void releaseSessionWaits(String sessionId, String endReason) {
        String abortHint = "STOPPED".equals(endReason) ? "用户已停止本轮" : "本轮长时间无人查看，已中止";
        pendingApprovals.entrySet().stream()
                .filter(e -> sessionId.equals(e.getValue().sessionId()))
                .forEach(e -> e.getValue().future().complete(false));
        pendingAsks.values().stream()
                .filter(p -> sessionId.equals(p.sessionId()))
                // 刻意不用 CancellationException：CompletableFuture.get() 对它有特殊分支——原样直抛、
                // 不包成 ExecutionException，会绕过提问等待处既有的「用户中止本轮」兜底，冒成系统异常
                .forEach(p -> p.future().completeExceptionally(new IllegalStateException(abortHint)));
        pendingPlans.entrySet().stream()
                .filter(e -> sessionId.equals(e.getValue().sessionId()))
                .forEach(e -> e.getValue().future().complete(PlanDecision.reject()));
    }

    /** 把这一轮挂在人身上的三张卡落成终态（提问卡不写答案，见 {@link #terminateSessionWaits}） */
    private void markSessionWaitsTerminal(String sessionId, String endReason) {
        int written = 0;
        for (var e : pendingApprovals.entrySet()) {
            if (!sessionId.equals(e.getValue().sessionId())) continue;
            if (markApprovalResolved(e.getKey(), endReason, e.getValue().userId())) written++;
        }
        for (var e : pendingAsks.entrySet()) {
            if (!sessionId.equals(e.getValue().sessionId())) continue;
            if (markAskResolved(e.getKey(), endReason, null, e.getValue().userId())) written++;
        }
        for (var e : pendingPlans.entrySet()) {
            if (!sessionId.equals(e.getValue().sessionId())) continue;
            if (markApprovalResolved(e.getKey(), endReason, e.getValue().userId())) written++;
        }
        if (written > 0) {
            log.info("[HITL] 人在回路卡片随本轮收尾落终态: session={} status={} 共 {} 条", sessionId, endReason, written);
        }
    }

    // ==================== 等待窗口（卡片倒计时与后端阻塞超时同源） ====================

    /** 审批等待上限（chat.approvalTimeoutMs，默认 120s；阻塞工具调用线程，必须有界） */
    public long approvalTimeoutMs() {
        long t = configService.getLong("chat.approvalTimeoutMs");
        return t > 0 ? t : 120000L;
    }

    /** askUser 提问卡等待上限（一卡多问需人工逐题作答，比工具审批宽）：默认 10 分钟，
     *  平台可调（chat.askTimeoutMs）。提问已落库且等待期间断线不中止本轮，所以窗口可以给到分钟级；
     *  前端倒计时取 SSE 下发的 timeoutMs、跨设备恢复取 DB 的 created_at + 本值，两处口径必须同源。 */
    public long askTimeoutMs() {
        long t = configService.getLong("chat.askTimeoutMs");
        return t > 0 ? t : 600000L;
    }

    /** 计划批准等待上限（chat.planTimeoutMs，默认 10 分钟，与 askUser 提问卡同级的人工等待） */
    public long planTimeoutMs() {
        long t = configService.getLong("chat.planTimeoutMs");
        return t > 0 ? t : 600000L;
    }

    // ==================== 工具执行审批 ====================

    /**
     * 从通知恢复审批卡：按 id 取审批记录（仅本人可查；不存在/非本人/已失效返回 null）。
     * 用于用户点开 tool.approval 通知后，在会话内重建审批卡——刷新丢失的 SSE 卡据此补回。
     */
    public ToolApproval getApproval(String approvalId, String uid) {
        if (approvalId == null || approvalId.isBlank()) return null;
        try {
            ToolApproval rec = toolApprovalMapper.selectById(approvalId);
            if (rec == null || rec.getUserId() == null || !rec.getUserId().equals(uid)) return null;
            return rec;
        } catch (Exception e) {
            log.warn("[TOOL] 恢复审批查询失败 approvalId={}: {}", approvalId, e.getMessage());
            return null;
        }
    }

    /**
     * 用户裁决工具审批：仅发起该轮问答的用户本人可批（uid 比对，内存态与 DB 双重校验）；
     * 内存态丢失（进程重启/已超时）时仍可更新 DB 审计记录（幂等），但无法唤醒已死的工具线程。
     */
    public boolean resolveApproval(String approvalId, boolean approved, String uid) {
        if (approvalId == null || approvalId.isBlank()) return false;
        String status = approved ? "APPROVED" : "REJECTED";
        boolean dbOk = markApprovalResolved(approvalId, status, uid);
        PendingApproval p = pendingApprovals.get(approvalId);
        if (p == null) {
            // 内存态缺失：DB 更新成功说明是合法裁决（或已被裁决过），返回 dbOk；线程已不可唤醒
            return dbOk;
        }
        if (uid == null || !uid.equals(p.userId())) {
            log.warn("[TOOL] 审批人非本轮用户，拒绝: approvalId={} by={}", approvalId, uid);
            return false;
        }
        return p.future().complete(approved);
    }

    /** 更新审批记录为终态（幂等：已非 PENDING 直接返回 false；uid 不匹配拒绝）。best-effort 不抛 */
    public boolean markApprovalResolved(String approvalId, String status, String uid) {
        try {
            ToolApproval rec = toolApprovalMapper.selectById(approvalId);
            if (rec == null) return false;
            if (uid != null && !uid.equals(rec.getUserId())) {
                log.warn("[TOOL] 审批人非本轮用户（DB），拒绝: approvalId={} by={}", approvalId, uid);
                return false;
            }
            if (!"PENDING".equals(rec.getStatus())) return false; // 已裁决，幂等
            toolApprovalMapper.update(null, new LambdaUpdateWrapper<ToolApproval>()
                    .eq(ToolApproval::getId, approvalId)
                    .set(ToolApproval::getStatus, status)
                    .set(ToolApproval::getResolvedAt, LocalDateTime.now()));
            return true;
        } catch (Exception e) {
            log.warn("[TOOL] 审批裁决落库失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 待批准的工具执行审批列表（与 {@link #listPendingAsks}、{@code /plan-approval/pending} 同一套恢复口径）：
     * 按会话取本人名下仍是 PENDING 的审批记录（排除提问卡与计划卡共用的那两类 tool_name）。
     * <p>为什么必须有这条：审批卡此前只有「实时 SSE 下发」和「知道 approvalId 才能查」两条路，
     * 于是刷新或换设备接回这一轮时，<b>正卡在「等你批准执行」上的那一张完全看不见</b>——而这一轮又豁免
     * 断线宽限、会一直等下去，用户读到的就是「智能体莫名其妙不动了」。铃铛深链能救，但前提是他去点了铃铛。
     * <p>{@code live=false} 同提问卡语义：记录还在但唤醒句柄已不在（进程重启/多副本挂在别的实例），
     * 批准送不回模型，前端按已失效处理而不是给一个静默失败的按钮。
     */
    public List<Map<String, Object>> listPendingApprovals(String sessionId, String uid) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank() || uid == null || uid.isBlank()) return out;
        long window = approvalTimeoutMs();
        try {
            List<ToolApproval> rows = toolApprovalMapper.selectList(
                    new LambdaQueryWrapper<ToolApproval>()
                            .eq(ToolApproval::getSessionId, sessionId)
                            .eq(ToolApproval::getUserId, uid)
                            .eq(ToolApproval::getStatus, "PENDING")
                            .notIn(ToolApproval::getToolName, "askUser", "planApproval")
                            .orderByAsc(ToolApproval::getCreatedAt));
            for (ToolApproval rec : rows) {
                long createdMs = rec.getCreatedAt() == null ? 0L
                        : rec.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                // 审批窗口只有 120s（比提问/计划短得多）：过期就别再重建一张点了没反应的卡
                long remaining = createdMs > 0 ? Math.max(0L, createdMs + window - System.currentTimeMillis()) : window;
                if (remaining <= 0) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("approvalId", rec.getId());
                // 归属轮次：与提问卡同一口径，恢复时挂回自己那一轮而不是最后一条助手气泡
                item.put("turnId", rec.getTurnId() == null ? "" : rec.getTurnId());
                item.put("tool", rec.getToolName());
                // 展示名与实时审批卡同源（注册中心）：恢复出来的卡片若只给英文名，用户在同一张卡上
                // 会先看到「运行命令」、刷新后看到「execute」
                toolRegistry.putDisplayFields(item, rec.getToolName());
                item.put("args", rec.getRequestArgs() == null ? "" : rec.getRequestArgs());
                item.put("timeoutMs", window);
                item.put("remainingMs", remaining);
                item.put("createdAt", createdMs);
                item.put("live", pendingApprovals.containsKey(rec.getId()));
                out.add(item);
                if (out.size() >= 5) break;   // 一步只挂一张卡，5 条纯属防御
            }
        } catch (Exception e) {
            log.warn("[TOOL] 待批准审批查询失败 session={}: {}", sessionId, e.getMessage());
        }
        return out;
    }

    /**
     * 启动期清理遗留 PENDING（进程重启后内存态丢失，DB 中超时未裁决的记录置 TIMEOUT，避免审计永久挂起）。
     * <p><b>必须按卡种分窗口</b>：三张卡的等待预算差 5 倍（工具审批 120s，提问卡与计划卡各 10 分钟），
     * 而这一段原先拿 {@code approvalTimeoutMs} 一刀切全部行——重启一次就把还有 8 分钟寿命的提问卡
     * 提前判成 TIMEOUT，用户刷新回来看到一张已经作废的卡，答了只得到「这次作答没能送达」。
     */
    @jakarta.annotation.PostConstruct
    public void purgeStaleApprovals() {
        try {
            // 提问卡与计划卡同档（askTimeoutMs / planTimeoutMs），其余按审批窗口；+5s 是扫描落库时刻的偏差余量
            long askSec = askTimeoutMs() / 1000 + 5;
            long planSec = planTimeoutMs() / 1000 + 5;
            long approvalSec = approvalTimeoutMs() / 1000 + 5;
            purgeStale("askUser", askSec);
            purgeStale("planApproval", planSec);
            // 工具审批卡：明确排除上面两类，它们各有自己的窗口
            purgeStale(null, approvalSec);
        } catch (Exception e) {
            log.warn("[TOOL] 遗留审批清理失败（可忽略，下次启动重试）: {}", e.getMessage());
        }
    }

    /**
     * 把某类卡里已过窗口的 PENDING 落成 TIMEOUT。
     *
     * @param toolName 卡种（{@code askUser} / {@code planApproval}）；null = 工具审批卡（排除前两类）
     */
    private void purgeStale(String toolName, long windowSec) {
        try {
            var q = new LambdaUpdateWrapper<ToolApproval>()
                    .eq(ToolApproval::getStatus, "PENDING")
                    .lt(ToolApproval::getCreatedAt, LocalDateTime.now().minusSeconds(windowSec))
                    .set(ToolApproval::getStatus, "TIMEOUT")
                    .set(ToolApproval::getResolvedAt, LocalDateTime.now());
            if (toolName == null) {
                q.notIn(ToolApproval::getToolName, "askUser", "planApproval");
            } else {
                q.eq(ToolApproval::getToolName, toolName);
            }
            toolApprovalMapper.update(null, q);
        } catch (Exception e) {
            log.warn("[TOOL] 遗留审批清理失败（可忽略，下次启动重试） card={}: {}", toolName, e.getMessage());
        }
    }

    // ==================== askUser 提问卡 ====================

    /**
     * 用户回答智能体提问：仅发起该轮问答的用户本人可答（uid 比对，内存态与 DB 双重校验，
     * 与工具审批同口径）。
     * <p>提问卡已落库、等待期间断线不中止本轮，所以刷新页面或换设备后按 {@link #listPendingAsks}
     * 取回卡片仍能把答案送到那根还阻塞着的工具线程。返回 false 表示这次作答没能送达（记录不存在/
     * 非本人/已收尾，或唤醒句柄已随进程重启消失），调用方据此提示用户。
     */
    public boolean resolveAsk(String askId, List<String> answers, String uid) {
        if (askId == null || askId.isBlank()) return false;
        ToolApproval rec = toolApprovalMapper.selectById(askId);
        if (rec == null || !"askUser".equals(rec.getToolName())) return false;
        if (uid == null || !uid.equals(rec.getUserId())) {
            log.warn("[ASK] 答题人非本轮用户，拒绝: askId={} by={}", askId, uid);
            return false;
        }
        if (!"PENDING".equals(rec.getStatus())) return false; // 已处理，幂等
        List<QItem> qs = parseQuestions(rec.getRequestArgs());
        if (qs.isEmpty()) return false;
        List<String> norm = answers == null ? List.of() : answers;
        PendingAsk p = pendingAsks.get(askId);
        if (p == null) {
            // 没有人真的在等这轮（进程重启过，或多副本下这轮挂在另一个实例上）：答案无处可送。
            // 记录置终态并把用户所答留进审计列，卡片因此从待答列表消失，不再误导成「答了就生效」
            markAskResolved(askId, "TIMEOUT", JSON.toJSONString(norm), uid);
            log.info("[ASK] 唤醒句柄已不在，本轮无法续跑: askId={} session={}", askId, rec.getSessionId());
            return false;
        }
        if (!uid.equals(p.userId())) {
            log.warn("[ASK] 答题人非本轮用户（内存），拒绝: askId={} by={}", askId, uid);
            return false;
        }
        String combined = buildCombinedAnswer(askId, uid, qs, norm, "APPROVED");
        return p.future().complete(combined);
    }

    /**
     * 用户忽略提问（提问面板「忽略」按钮）：不作答，立刻让本轮带着「用户没答这一题」继续推进
     * ——与超时同一语义的提前触发，都不替用户选答案。审计记 TIMEOUT + 空答案（用户确实做了「跳过」动作）。
     */
    public boolean resolveAskIgnore(String askId, String uid) {
        if (askId == null || askId.isBlank()) return false;
        // 问题列表从落库 args 还原（DB 是权威源：内存 future 只负责唤醒）
        ToolApproval rec = toolApprovalMapper.selectById(askId);
        if (rec == null || !"askUser".equals(rec.getToolName())) return false;
        if (uid == null || !uid.equals(rec.getUserId())) {
            log.warn("[ASK] 忽略人非本轮用户，拒绝: askId={} by={}", askId, uid);
            return false;
        }
        if (!"PENDING".equals(rec.getStatus())) return false; // 已处理，幂等
        List<QItem> qs = parseQuestions(rec.getRequestArgs());
        if (qs.isEmpty()) return false;
        PendingAsk p = pendingAsks.get(askId);
        if (p == null) {
            markAskResolved(askId, "TIMEOUT", null, uid);
            log.info("[ASK] 唤醒句柄已不在，忽略动作无处生效: askId={} session={}", askId, rec.getSessionId());
            return false;
        }
        if (uid == null || !uid.equals(p.userId())) return false;
        String combined = buildCombinedAnswer(askId, uid, qs, null, "IGNORE");
        return p.future().complete(combined);
    }

    /**
     * 待答提问列表（卡片持久化后的恢复入口）：按会话取本人名下仍是 PENDING 的 askUser 记录，
     * 供前端在会话加载/切换、或点开 tool.ask 通知进入会话时重建提问卡。
     * <p>{@code live=false} 表示卡片还在但唤醒句柄已不在（进程重启；多副本下也可能是那轮挂在别的实例），
     * 此时作答送不到模型，前端按「本轮已结束」提示而不是给一个静默失败的输入框。
     * 倒计时以 DB 的 {@code createdAt} + 当前窗口换算，与后端阻塞超时同源。
     */
    public List<Map<String, Object>> listPendingAsks(String sessionId, String uid) {
        if (uid == null || uid.isBlank()) return new ArrayList<>();
        return queryPendingAsks(sessionId, uid);
    }

    /**
     * 按会话列待答提问卡，<b>不比对 user_id</b>。
     * <p>免登录分享链路的提问卡行记的是<b>发布者</b> uid（游客轮按发布者身份装载检索与默认模型，
     * 见 {@code ShareController.chat} 的 {@code .userId(owner.getUid())}），所以游客侧没法按 uid 认归属——
     * 那会把 `share-visitor:{id}` 一律挡在门外，卡片刷新后就再也取不回来。
     * <p>调用方<b>必须</b>先自行确认「这个会话属于这个访客」（{@code sessionService.assertOwned}），
     * 与游客侧 stop / feedback / chat-run 三个端点同一套两跳判据。
     */
    public List<Map<String, Object>> listPendingAsksForSession(String sessionId) {
        return queryPendingAsks(sessionId, null);
    }

    /** uid 传 null = 只按会话查（游客侧用），非 null = 叠加归属比对（登录侧用） */
    private List<Map<String, Object>> queryPendingAsks(String sessionId, String uid) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) return out;
        long window = askTimeoutMs();
        try {
            LambdaQueryWrapper<ToolApproval> q = new LambdaQueryWrapper<ToolApproval>()
                    .eq(ToolApproval::getSessionId, sessionId)
                    .eq(ToolApproval::getToolName, "askUser")
                    .eq(ToolApproval::getStatus, "PENDING")
                    .orderByAsc(ToolApproval::getCreatedAt);
            if (uid != null && !uid.isBlank()) {
                q.eq(ToolApproval::getUserId, uid);
            }
            List<ToolApproval> rows = toolApprovalMapper.selectList(q);
            for (ToolApproval rec : rows) {
                List<QItem> qs = parseQuestions(rec.getRequestArgs());
                if (qs.isEmpty()) continue;
                long createdMs = rec.getCreatedAt() == null ? 0L
                        : rec.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                long remaining = createdMs > 0 ? Math.max(0L, createdMs + window - System.currentTimeMillis()) : window;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("askId", rec.getId());
                // 归属轮次：恢复出来的卡片要挂回它自己那一轮的气泡（NULL=补列前的存量，前端按原口径处理）
                item.put("turnId", rec.getTurnId() == null ? "" : rec.getTurnId());
                item.put("questions", questionsPayload(qs));
                item.put("timeoutMs", window);
                item.put("remainingMs", remaining);
                item.put("createdAt", createdMs);
                // 已过期但还没被超时路径收尾（超时分支落终态需要工具线程醒一次）：不再当作可答卡片重建
                item.put("expired", remaining <= 0);
                item.put("live", pendingAsks.containsKey(rec.getId()));
                out.add(item);
                if (out.size() >= 10) break; // 一卡最多 6 问、一轮最多 3 卡，10 条封顶纯属防御
            }
        } catch (Exception e) {
            log.warn("[ASK] 待答提问查询失败 session={}: {}", sessionId, e.getMessage());
        }
        return out;
    }

    /** 更新提问记录为终态（幂等：已非 PENDING 直接返回 false；uid 不匹配拒绝；answer 可空）。best-effort 不抛 */
    public boolean markAskResolved(String askId, String status, String answer, String uid) {
        try {
            ToolApproval rec = toolApprovalMapper.selectById(askId);
            if (rec == null) return false;
            if (uid != null && !uid.equals(rec.getUserId())) {
                log.warn("[ASK] 答题人非本轮用户（DB），拒绝: askId={} by={}", askId, uid);
                return false;
            }
            if (!"PENDING".equals(rec.getStatus())) return false; // 已处理，幂等
            toolApprovalMapper.update(null, new LambdaUpdateWrapper<ToolApproval>()
                    .eq(ToolApproval::getId, askId)
                    .set(ToolApproval::getStatus, status)
                    .set(ToolApproval::getAnswer, answer)
                    .set(ToolApproval::getResolvedAt, LocalDateTime.now()));
            return true;
        } catch (Exception e) {
            log.warn("[ASK] 提问裁决落库失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 组装批量答案：未作答的问题<b>不替用户选</b>——把「这一题没答」如实回给模型，让它基于已有
     * 信息自行推进或在正文里请用户回复。此前按推荐项（options[0]）代答：即便附了「并非用户亲自选择」
     * 的注脚，模型转述时仍会把默认决策讲成用户亲选，且业界（Claude Code 到期取消、Cursor 上报 skip）
     * 都没有「超时即替人选第一项」这一档。
     * 落库 answer 列（TEXT），返回工具结果文本（单问退化为纯文本，多问为 JSON 数组字符串，与问题下标对齐）。
     */
    public String buildCombinedAnswer(String askId, String uid, List<QItem> qs,
                                      List<String> userAnswers, String mode) {
        List<String> finalAnswers = new ArrayList<>();
        for (int i = 0; i < qs.size(); i++) {
            String ua = (userAnswers != null && i < userAnswers.size()) ? userAnswers.get(i) : null;
            if (ua != null && !ua.trim().isEmpty()) { finalAnswers.add(ua.trim()); continue; }
            // 统一前缀「（用户未回答这一题」：前端问答记录据此渲染成「未作答」，
            // 不把给模型看的处置指令原样摊给用户（见 web/src/chat/projections.js askUserView）
            String lead = switch (mode) {
                case "IGNORE" -> "（用户未回答这一题（点了忽略）";
                case "TIMEOUT" -> "（用户未回答这一题（已超时）";
                default -> "（用户未回答这一题（提交时留空）";
            };
            finalAnswers.add(lead + "。请基于已有信息自行判断，不要替用户假定选择，也不要重复提问。）");
        }
        String answerJson = JSON.toJSONString(finalAnswers);
        String status = "APPROVED".equals(mode) ? "APPROVED" : "TIMEOUT";
        markAskResolved(askId, status, answerJson, uid);
        if (qs.size() == 1) return finalAnswers.get(0);
        return answerJson;
    }

    /** 从落库 request_args 还原问题列表（兼容旧式单问题 {question,options} 与一卡多问 {questions}）。
     *  {@code multi} 走 {@code getBooleanValue}：缺字段与显式 false 都是单选，历史数据不用回填。 */
    public List<QItem> parseQuestions(String argsJson) {
        List<QItem> out = new ArrayList<>();
        try {
            JSONObject args = JSON.parseObject(argsJson);
            if (args == null) return out;
            JSONArray arr = args.getJSONArray("questions");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (o == null) continue;
                    String q = o.getString("question");
                    if (q == null || q.isEmpty()) continue;
                    JSONArray opts = o.getJSONArray("options");
                    List<String> os = opts == null ? List.of() : opts.toJavaList(String.class);
                    out.add(new QItem(o.getString("topic") == null ? "" : o.getString("topic"),
                            q, os, o.getBooleanValue("multi")));
                }
                return out;
            }
            // 旧式单问题
            String q = args.getString("question");
            if (q == null || q.isEmpty()) return out;
            JSONArray opts = args.getJSONArray("options");
            List<String> os = opts == null ? List.of() : opts.toJavaList(String.class);
            out.add(new QItem(args.getString("topic") == null ? "" : args.getString("topic"),
                    q, os, args.getBooleanValue("multi")));
        } catch (Exception e) {
            log.warn("[ASK] 解析提问参数失败: {}", e.getMessage());
        }
        return out;
    }

    /** questions → request_args JSON（TEXT 列，放宽到 8000 字符防多问题截断） */
    public String askArgsJson(List<QItem> qs) {
        String json = JSON.toJSONString(Map.of("questions", qs.stream().map(this::askQuestionToMap).toList()));
        return json.length() > 8000 ? json.substring(0, 8000) : json;
    }

    /** questions → SSE/待答列表用的 Map 数组（与落库同源） */
    public List<Map<String, Object>> questionsPayload(List<QItem> qs) {
        return qs.stream().map(this::askQuestionToMap).toList();
    }

    /** 单题 → Map（topic 可空省略；multi 只在为真时写，与引擎 {@code askCard} 那份同形） */
    private Map<String, Object> askQuestionToMap(QItem x) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!x.topic().isEmpty()) m.put("topic", x.topic());
        m.put("question", x.question());
        m.put("options", x.options());
        if (x.multi()) m.put("multi", true);
        return m;
    }

    // ==================== 执行计划批准卡 ====================

    /** 从落库 request_args 还原模型原稿计划（批准未带编辑稿时回退用） */
    public String parsePlanDraft(String argsJson) {
        try {
            JSONObject args = JSON.parseObject(argsJson);
            return args == null ? "" : (args.getString("plan") == null ? "" : args.getString("plan"));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 用户裁决执行计划（计划模式）：仅发起本轮问答的用户本人可裁决（uid 比对，与工具审批同口径）。
     * revise=true 为退回重出（plan=用户的修改意见/改稿，同一轮内模型产出下一版，可反复）；
     * supersede=true 为「继续对话」（用户带着修改意见继续对话，本版作废，新一轮按意见重做计划）；
     * approved=false 为拒绝（整轮终止）；approved=true 时 plan 为用户（可能编辑过的）批准版计划，
     * 空串回落模型原稿。返回 false 表示裁决没能送达（记录不存在/非本人/已收尾/句柄随进程重启消失）。
     */
    public boolean resolvePlanApproval(String planApprovalId, boolean approved, String plan, boolean revise,
                                       boolean supersede, String uid) {
        if (planApprovalId == null || planApprovalId.isBlank()) return false;
        ToolApproval rec = toolApprovalMapper.selectById(planApprovalId);
        if (rec == null || !"planApproval".equals(rec.getToolName())) return false;
        if (uid == null || !uid.equals(rec.getUserId())) {
            log.warn("[PLAN] 裁决人非本轮用户，拒绝: id={} by={}", planApprovalId, uid);
            return false;
        }
        if (!"PENDING".equals(rec.getStatus())) return false; // 已处理，幂等
        PendingPlan p = pendingPlans.get(planApprovalId);
        if (p == null) {
            // 唤醒句柄已不在（进程重启/多副本下那轮挂在别的实例）：终态照落，本轮无法续跑
            markApprovalResolved(planApprovalId, approved || revise || supersede ? "TIMEOUT" : "REJECTED", uid);
            log.info("[PLAN] 唤醒句柄已不在，本轮无法续跑: id={} session={}", planApprovalId, rec.getSessionId());
            return false;
        }
        if (!uid.equals(p.userId())) return false;
        if (supersede) {
            // 用户带着修改意见继续对话：本版作废（不是「未批准」——内容没被否定，是提出了修改）。
            // 前端随后把那条意见作为用户消息发出，新一轮按意见产出修改后的计划
            markApprovalResolved(planApprovalId, "SUPERSEDED", uid);
            log.info("[PLAN] 用户带着修改意见继续对话，本版计划作废: id={} session={}", planApprovalId, rec.getSessionId());
            return p.future().complete(PlanDecision.supersede());
        }
        if (revise) {
            String feedback = plan == null ? "" : plan.trim();
            if (feedback.isBlank()) return false;   // 空意见不能退回（前端也会拦）
            // 本版被用户的修改意见取代：意见留审计（answer 列与 askUser 同语义），状态记 REVISED
            try {
                toolApprovalMapper.update(null, new LambdaUpdateWrapper<ToolApproval>()
                        .eq(ToolApproval::getId, planApprovalId)
                        .set(ToolApproval::getAnswer,
                                feedback.length() > 8000 ? feedback.substring(0, 8000) : feedback));
            } catch (Exception e) {
                log.warn("[PLAN] 修改意见留审计失败（不阻塞退回）: {}", e.getMessage());
            }
            markApprovalResolved(planApprovalId, "REVISED", uid);
            log.info("[PLAN] 用户要求修改计划，退回重出下一版: id={} session={} 意见 {} 字",
                    planApprovalId, rec.getSessionId(), feedback.length());
            return p.future().complete(PlanDecision.revise(feedback));
        }
        if (!approved) {
            markApprovalResolved(planApprovalId, "REJECTED", uid);
            return p.future().complete(PlanDecision.reject());
        }
        String finalPlan = plan == null || plan.isBlank() ? parsePlanDraft(rec.getRequestArgs()) : plan.trim();
        // 批准版计划留审计（answer 列与 askUser 同语义：用户最终确认的内容）
        try {
            toolApprovalMapper.update(null, new LambdaUpdateWrapper<ToolApproval>()
                    .eq(ToolApproval::getId, planApprovalId)
                    .set(ToolApproval::getAnswer,
                            finalPlan.length() > 8000 ? finalPlan.substring(0, 8000) : finalPlan));
        } catch (Exception e) {
            log.warn("[PLAN] 批准稿留审计失败（不阻塞批准）: {}", e.getMessage());
        }
        markApprovalResolved(planApprovalId, "APPROVED", uid);
        log.info("[PLAN] 用户已批准执行计划: id={} session={} 编辑={}", planApprovalId, rec.getSessionId(),
                plan != null && !plan.isBlank());
        return p.future().complete(PlanDecision.approve(finalPlan));
    }

    /**
     * 待批准计划列表（卡片持久化后的恢复入口）：按会话取本人名下仍挂在「上一轮计划」上的记录，
     * 供前端在会话加载/切换、或点开计划通知进入会话时重建批准卡。
     * <p>版本链重建：自最新记录往前，直到遇到两版之间的终态（APPROVED/REJECTED/TIMEOUT）为止——
     * 这一串 PENDING/REVISED 就是本轮（同一轮内退回重出）的计划各版；链尾不是 PENDING 说明正在重出中，不重建。
     * 返回 items：最新一条（含 {@code planApprovalId/plan/timeoutMs/remainingMs/createdAt/expired/live}）
     * 加 {@code versions:[{plan,feedback}]} 版本链；{@code live=false} 表示唤醒句柄已不在
     * （进程重启/多副本），批准送不到模型，前端按「本轮已结束」提示。
     */
    public List<Map<String, Object>> listPendingPlans(String sessionId, String uid) {
        if (uid == null || uid.isBlank()) return new ArrayList<>();
        return queryPendingPlans(sessionId, uid);
    }

    /**
     * 按会话列待批计划卡，<b>不比对 user_id</b>。与 {@link #listPendingAsksForSession} 同因：
     * 免登录分享链路的卡片行记的是发布者 uid，游客侧按 uid 过滤永远取不回卡片。
     * 调用方必须先自行确认会话归属（ShareController 走 assertOwned(sessionId, visitorUid)）。
     */
    public List<Map<String, Object>> listPendingPlansForSession(String sessionId) {
        return queryPendingPlans(sessionId, null);
    }

    private List<Map<String, Object>> queryPendingPlans(String sessionId, String uid) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) return out;
        long window = planTimeoutMs();
        try {
            LambdaQueryWrapper<ToolApproval> q = new LambdaQueryWrapper<ToolApproval>()
                            .eq(ToolApproval::getSessionId, sessionId)
                            .eq(ToolApproval::getToolName, "planApproval")
                            .orderByDesc(ToolApproval::getCreatedAt)
                            .last("LIMIT 30");
            if (uid != null && !uid.isBlank()) {
                q.eq(ToolApproval::getUserId, uid);
            }
            List<ToolApproval> rows = toolApprovalMapper.selectList(q);
            if (rows.isEmpty()) return out;
            // 自最新往前收集本轮版本链（PENDING/REVISED 继续、遇到终态即止）。
            // 最近一条已是终态（批准/取代/拒绝/超时，绝大多数会话进来看的就是这种情况）⇒ 链为空，
            // 没有待批计划可重建——必须先判空再取 last：getLast() 对空链抛 NoSuchElementException
            // （message 为 null），每次切进会话都会刷「待批准计划查询失败 …: null」的误导性警告
            LinkedList<ToolApproval> chain = new LinkedList<>();
            for (ToolApproval rec : rows) {
                String s = rec.getStatus();
                if ("PENDING".equals(s) || "REVISED".equals(s)) chain.addFirst(rec);
                else break;
            }
            if (chain.isEmpty()) return out;
            ToolApproval last = chain.getLast();
            // 链尾必须是 PENDING（可裁决）才重建；还是 REVISED = 正在按意见重出下一页，等它生成完再取
            if (!"PENDING".equals(last.getStatus())) return out;
            long createdMs = last.getCreatedAt() == null ? 0L
                    : last.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            long remaining = createdMs > 0 ? Math.max(0L, createdMs + window - System.currentTimeMillis()) : window;
            // 已过期但还没被超时路径收尾（收尾需要流水线线程醒一次）：不再当作可批卡片重建
            if (remaining <= 0) return out;
            // 各版 = 该版计划文本 + 产出该版的用户意见（前一版被 REVISED 时记在它的 answer 列；v1 无意见）
            List<Map<String, Object>> versions = new ArrayList<>();
            String producedBy = "";
            for (ToolApproval rec : chain) {
                String plan = parsePlanDraft(rec.getRequestArgs());
                if (plan.isBlank()) continue;
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("plan", plan);
                v.put("feedback", producedBy);
                versions.add(v);
                producedBy = "REVISED".equals(rec.getStatus()) && rec.getAnswer() != null ? rec.getAnswer() : "";
            }
            if (versions.isEmpty()) return out;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("planApprovalId", last.getId());
            // 归属轮次：与提问卡、审批卡同一口径
            item.put("turnId", last.getTurnId() == null ? "" : last.getTurnId());
            item.put("plan", versions.get(versions.size() - 1).get("plan"));
            item.put("versions", versions);
            item.put("timeoutMs", window);
            item.put("remainingMs", remaining);
            item.put("createdAt", createdMs);
            item.put("expired", false);
            item.put("live", pendingPlans.containsKey(last.getId()));
            out.add(item);
        } catch (Exception e) {
            log.warn("[PLAN] 待批准计划查询失败 session={}: {}", sessionId, e.getMessage());
        }
        return out;
    }
}
