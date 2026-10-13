package com.wenqu.ai.agent.plan;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.agent.chat.SseClientGoneException;
import com.wenqu.ai.agent.chat.TurnRegistry;
import com.wenqu.ai.agent.chat.TurnState;
import com.wenqu.ai.agent.context.HistoryCompactionService;
import com.wenqu.ai.agent.hitl.HitlDecisionService;
import com.wenqu.ai.agent.hitl.HitlDecisionService.PlanDecision;
import com.wenqu.ai.util.TokenCounter;
import com.wenqu.ai.model.Agent;
import com.wenqu.ai.service.BuiltinTools;
import com.wenqu.ai.service.CollectingSseEmitter;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.NotificationService;
import com.wenqu.ai.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>计划模式</b>这一族（P6 刀 4，自 {@code RagService} 抽出）：出计划 → 挂批准卡等人裁决 →
 * 批准后把计划注入执行轮 / 退回则带意见重出下一版 / 「继续对话」则本版作废；
 * 外加计划意图识别（用户没开计划开关时，按这条消息判「不先对齐就会白做」）。
 *
 * <p><b>为什么它需要一份回call接口</b>：计划轮要「按与生成轮完全相同的口径」装配一次工具集
 * （能力清单喂给模型，也缓存给生成轮复用，MCP 不二次建连），要在计划前弹一张提问卡，
 * 要把这张卡记进本轮的工具卡管道。这四件事都在问答侧与人在回路上，本族一样都不该自己实现——
 * 于是它们以 {@link PlanTurnHooks} 的形式交进来。这与 P3a 的 {@code branchRunner(TurnRunner turn, …)}
 * 是同一条口径：<b>要回头调问答侧的东西，就当参数传进来，别让这个 bean 认识问答服务</b>
 * （P6 拦路点 2 修正后的判据）。
 *
 * <p>刻意留在问答侧的两件（不属本族）：
 * <ul>
 *   <li>{@code persistPlanRejected / persistPlanSuperseded / persistPlanCloseout} 与 {@code planRecordJson}：
 *       计划收尾轮的落库要动消息表与<b>时间线快照</b>，那是刀 5 的持久化缝；搬过来要么拖上整族时间线，
 *       要么加第五个回call，两条都比现状差；</li>
 *   <li>提问卡的执行体 {@code doAskUserMulti}：执行期 askUser 与计划前澄清共用它。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class PlanModeService {

    /**
     * 计划轮要请问答侧代做的四件事（<b>本轮</b>绑定，所以按调用传入——本类不认识 {@code RagService}）。
     */
    public interface PlanTurnHooks {
        /** 与生成轮同口径装配本轮工具集（计划的能力清单；结果同时缓存给生成轮复用） */
        List<ToolCallback> mountTools(Agent agent, TurnState st);

        /** 「已批准计划落成任务清单」那句提示的门控（判据本体在描述符的 exposes，不许在这里再抄一份） */
        boolean todoExposed(Agent agent, TurnState st);

        /** 本轮工具卡登记：计划前的提问卡要走与执行期 askUser 完全相同的记录管道 */
        void recordToolStatus(TurnState st, String name, String input, String status,
                              String resultOrError, long elapsedMs, int attempts, boolean denied);

        /** 提问卡执行体（人在回路的同一条通道） */
        String askUser(TurnState st, List<BuiltinTools.AskQuestion> questions);
    }

    private final ConfigService configService;
    private final ChatClient chatClient;
    private final ModelRegistryService modelRegistryService;
    private final SessionService sessionService;
    private final ChatRunRuntime runtime;
    private final HitlDecisionService hitl;
    private final NotificationService notificationService;
    private final HistoryCompactionService historyCompaction;

    public PlanModeService(ConfigService configService,
                           ChatClient chatClient,
                           ModelRegistryService modelRegistryService,
                           SessionService sessionService,
                           ChatRunRuntime runtime,
                           HitlDecisionService hitl,
                           NotificationService notificationService,
                           HistoryCompactionService historyCompaction) {
        this.configService = configService;
        this.chatClient = chatClient;
        this.modelRegistryService = modelRegistryService;
        this.sessionService = sessionService;
        this.runtime = runtime;
        this.hitl = hitl;
        this.notificationService = notificationService;
        this.historyCompaction = historyCompaction;
    }

    // ==================== 计划模式（人在回路：先出执行计划，批准后按计划执行） ====================

    /** 计划文本长度护栏：超限直接停收增量（计划是一份动作清单，写这么长一定是跑题了） */
    private static final int PLAN_MAX_CHARS = 6000;

    /** 计划阶段（单版）结果：plan=本版计划文本（生成失败为 null）；approved=true 表示用户已批准本版；
     *  reviseFeedback 非 null=用户要修改本版（gate 据此带意见重出下一版）；
     *  superseded=true=用户带着修改意见继续对话（本版作废，整轮收尾）；
     *  generateFailed=true=计划生成失败（已登记降级，调用方按普通模式继续）；
     *  三者皆否=拒绝/超时（整轮终止） */
    private record PlanPhaseOutcome(String plan, boolean approved, boolean generateFailed, String reviseFeedback,
                                    boolean superseded) {
        static PlanPhaseOutcome of(String plan, boolean approved, String reviseFeedback) {
            return new PlanPhaseOutcome(plan, approved, false, reviseFeedback, false);
        }
        static PlanPhaseOutcome failed() { return new PlanPhaseOutcome(null, false, true, null, false); }
        static PlanPhaseOutcome superseded(String plan) { return new PlanPhaseOutcome(plan, false, false, null, true); }
    }

    /** 计划生成流式超时（chat.planGenTimeoutMs，默认 120s；阻塞流水线线程，必须有界） */
    private long planGenTimeoutMs() {
        long t = configService.getLong("chat.planGenTimeoutMs");
        return t > 0 ? t : 120000L;
    }

    // ==================== 计划意图识别（按用户意图自动开启计划模式） ====================

    /** 意图识别专用线程池（与派遣路由同款：2 线程 + 超时 cancel(true) 中断底层调用；daemon 不阻 JVM 退出） */
    private static final java.util.concurrent.ThreadPoolExecutor PLAN_INTENT_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "plan-intent");
                t.setDaemon(true);
                return t;
            });

    /**
     * 启动计划意图识别（与图片/附件/检索并行跑，闸门前取结果）：用户在计划开关之外没提计划时，
     * 判断这条消息是否「不先对齐就会白做」——方向没定清又要从零产出成型交付物，或动作不易回头。
     * 环节多、有产物本身都不是理由（进度有任务清单，不拦人）。判据见下方提示词，改判据要同步改
     * config-schema 的 planAutoIntent 说明。
     * 与闸门同口径跳过不适用的轮次：游客、无工具模型（计划本就降级）、收集型通道（定时任务/MCP/子智能体
     * 没人能批准计划卡）、重新生成（用户要的是重答不是重出计划）。返回 null = 本轮不检测。
     */
    public java.util.concurrent.CompletableFuture<Boolean> startIntentCheck(
            SseEmitter emitter, boolean guestMode, boolean planMode, boolean regenerate,
            String sessionId, String question, String model, boolean hasAttachments) {
        if (planMode || guestMode || regenerate || emitter instanceof CollectingSseEmitter) return null;
        if (!configService.getBoolean("chat.planAutoIntent", true)) return null;
        if (model == null || !modelRegistryService.toolCapableOf(model)) return null;
        String uid = com.wenqu.ai.util.RequestUser.uid();
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(
                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(uid) ? null : uid, null, null, "planIntent"));
            try {
                String history = "";
                try {
                    history = historyCompaction.buildHistoryText(sessionService.getRecentHistory(sessionId, 2));
                } catch (Exception e) { /* 无上下文也能判 */ }
                String prompt = "你是「计划模式」判断器。计划模式＝先写一份「要做什么、分几步」的计划，用户批准后才动手，"
                        + "代价是这一轮会被拦住等他点确认。所以只在「不先对齐就会白做」时才判适合。\n"
                        + "\n适合（必须命中其一）：用户还没说清要做成什么样，需要他先定方向，且这轮要从零产出一份成型交付物"
                        + "（网页/文件/报告/方案/多份材料）；或动作不易回头（删除、覆盖、批量改写、对外发送、改线上）。\n"
                        + "不适合（一律判不适合，直接回答就行）：\n"
                        + "· 用户已把做法或步骤写死——原样运行、逐字使用、依次调用这几个工具、分几次查、按这个格式改；\n"
                        + "· 用户在问你的判断——还能不能再优化、行不行、有什么问题、为什么、帮我分析；他要的是结论，不是计划；\n"
                        + "· 只读性质：查、看、找、读、汇总，不改动任何东西；\n"
                        + "· 对已有内容做局部改动：润色、调整、换措辞换配色、改一处；\n"
                        + "· 环节多但没有需要他拍板的岔路——执行进度有任务清单展示，不需要计划卡。\n"
                        + "一句话判据：只有「先花一分钟对齐要交付成什么样」能明显减少返工，或这一步做下去不易回头，才算适合；"
                        + "只是环节多、只是最终会有产物，都不算。\n"
                        + (hasAttachments ? "（本条消息附带了文件或图片——通常意味着要把材料加工成产物，但仍以「做法说清了没有、"
                        + "有没有要他拍板的岔路」为准。）\n" : "")
                        + (history == null || history.isBlank() ? ""
                        : "最近对话（辅助理解意图，如「继续」「改成 X」这类指代）：\n" + history + "\n")
                        + "\n用户消息：" + question
                        + "\n\n只回答 YES 或 NO，不要输出任何其他内容。";
                // 与计划轮同口径的思考档处理：能关思考的方言下发关闭字段（思考与正文共享 max_tokens，
                // 恒思考模型会把预算花在推理上、判定正文被挤空）；关不掉的多留一份输出预算
                Map<String, Object> thinkOff = modelRegistryService.reasoningOffBody(model);
                org.springframework.ai.openai.OpenAiChatOptions.Builder opts =
                        org.springframework.ai.openai.OpenAiChatOptions.builder()
                                .model(model)
                                .temperature(0.0)
                                .maxTokens(thinkOff.isEmpty() ? 3072 : 128)
                                .internalToolExecutionEnabled(false);
                if (!thinkOff.isEmpty()) opts.extraBody(thinkOff);
                String out = chatClient.prompt()
                        .user(prompt)
                        .options(opts.build())
                        .call()
                        .content();
                boolean yes = out != null && out.trim().toUpperCase(java.util.Locale.ROOT).startsWith("YES");
                log.info("[PLAN] 计划意图识别：{}（{} 字消息）", yes ? "适合" : "不适合", question.length());
                return yes;
            } catch (Exception e) {
                log.info("[PLAN] 计划意图识别失败，本轮不自动开计划: {}", e.getMessage());
                return false;
            } finally {
                com.wenqu.ai.util.UsageAttr.clear();
            }
        }, PLAN_INTENT_EXECUTOR);
    }

    /** 取意图识别结果（闸门前调用）：没跑完最多再等 1.5 秒——自动开启宁缺毋滥，不拖慢回答 */
    public boolean awaitIntent(java.util.concurrent.CompletableFuture<Boolean> f) {
        if (f == null) return false;
        try {
            return Boolean.TRUE.equals(f.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            log.info("[PLAN] 计划意图识别超时，本轮不自动开计划");
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计划模式闸门（runChat 主链路与无知识库分支共用）：装配本轮工具集 → 无工具/游客降级 →
     * 产出执行计划 → 挂起等用户裁决。批准后的计划由调用方注入执行轮 system。
     * <p>裁决含「退回重出」：用户不满意可带修改意见退回，模型在同一轮内重出下一版（v2/v3…），
     * 反复直到确认/取消/超时。每版落一条批准记录（REVISED=被用户意见取代），版本链随本轮计划快照落库。
     *
     * @return null=本轮应就此终止（用户拒绝/超时发 plan_cancelled、或「继续对话」取代发 plan_superseded，
     *         由调用方按 {@link TurnState#planSuperseded} 分流收尾）；
     *         ""=降级为普通回答（无工具/游客/生成失败，降级提示已登记，不注入计划）；
     *         非 null=批准后的计划文本（调用方追加进 system）
     */
    public String approvalGate(TurnState st, Agent agent, String rolePart, String planQuestion,
                               List<String> skills, String refsDigest, StringBuilder system, PlanTurnHooks hooks) {
        if (st.guestMode) {
            runtime.addDegradation(st.degradations, st.degradedCodes, "planGuestUnsupported",
                    "游客会话不支持计划模式，本轮按普通模式回答");
            return "";
        }
        // 计划门控的对象是工具执行：模型不支持 Function Calling 时工具本来就下不去，计划没有可门控的执行，
        // 直接降级（不缓存工具集，让 buildAnswerStream 的同款检查走它自己的标准降级提示）
        if (!modelRegistryService.toolCapableOf(st.model)) {
            runtime.addDegradation(st.degradations, st.degradedCodes, "planNoTools",
                    "当前模型不支持工具调用（Function Calling），计划模式未生效，已按普通模式回答");
            return "";
        }
        // 计划轮自己不执行任何工具，但计划必须基于「本轮真实可用的能力」来写——先按与生成轮完全
        // 相同的口径装配一次工具集，既给计划当能力清单，也缓存给 buildAnswerStream 复用（MCP 连接不二次建立）
        List<ToolCallback> rawCbs = hooks.mountTools(agent, st);
        st.tools.enabledCallbacks = rawCbs;
        if (rawCbs.isEmpty()) {
            runtime.addDegradation(st.degradations, st.degradedCodes, "planNoTools",
                    "本轮没有启用的工具可执行，计划模式未生效，已按普通模式回答");
            return "";
        }
        // 版本链 [{plan, feedback}]：feedback=产出该版的用户意见（v1 为空串）
        List<Map<String, Object>> versions = new java.util.ArrayList<>();
        String feedback = null;   // 本版应遵循的用户意见（v1 为 null）
        while (true) {
            boolean first = versions.isEmpty() && feedback == null;
            runtime.sendSseEvent(st.channel.emitter, "stage",
                    first ? "正在制定计划…" : "正在按你的意见修改计划…", st.sessionId);
            PlanPhaseOutcome po = runPlanPhase(st, rolePart, planQuestion, skills, rawCbs, refsDigest,
                    feedback, versions, first, hooks);
            if (po.reviseFeedback() != null) {
                // 用户退回本版：记账后带着意见重出下一版（站内通知只在首版发，修订不再提醒）
                versions.add(planVersionEntry(po.plan(), feedback));
                feedback = po.reviseFeedback();
                continue;
            }
            if (po.superseded()) {
                // 用户带着修改意见继续对话：本版作废（不是「未批准」——内容没被否定，是提出了修改）。
                // 快照落 superseded，收尾（收尾助手消息 + plan_superseded 事件）见调用方
                versions.add(planVersionEntry(po.plan(), feedback));
                st.planRecord = planSnapshot(versions, "superseded", st.planAuto);
                st.planSuperseded = true;
                return null;
            }
            if (po.generateFailed()) {
                // 修订轮生成失败：已有一版等待批准的计划，不能悄悄放弃用户诉求转普通回答——
                // 也没有可裁决的卡可挂（旧卡已按 REVISED 收口），登记降级后按停止本轮收尾
                if (!versions.isEmpty()) {
                    st.planRecord = planSnapshot(versions, "rejected", st.planAuto);
                    runtime.addDegradation(st.degradations, st.degradedCodes, "planReviseFailed",
                            "按你的意见重出计划失败，本轮已停止（可重新发送）");
                    return null;
                }
                return "";
            }
            versions.add(planVersionEntry(po.plan(), feedback));
            if (po.approved()) {
                st.planRecord = planSnapshot(versions, "approved", st.planAuto);
                String block = approvedPlanBlock(po.plan(), hooks.todoExposed(agent, st));
                system.append(block);
                // 容量计量：计划块与技能/摘要一样是注入段，并入 other 桶（done 的用量校准同源）
                st.ctxParts.merge("other", TokenCounter.estimate(block), Integer::sum);
                return po.plan();
            }
            // 拒绝/超时：整轮终止（快照含各版计划，历史里这轮的「未批准」卡照常带修改记录）
            st.planRecord = planSnapshot(versions, "rejected", st.planAuto);
            return null;
        }
    }

    /** 版本链条目：feedback=产出该版的用户意见（v1 为空串，前端按「你的意见」插在两版之间展示） */
    private Map<String, Object> planVersionEntry(String plan, String feedback) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("plan", plan == null ? "" : plan);
        v.put("feedback", feedback == null ? "" : feedback);
        return v;
    }

    /** 本轮计划快照（助手消息 plan 列）：plan=末版正文（卡片主体展示），versions=完整版本链（修改记录）；
     *  auto=true=该计划按消息意图自动开启（用户没开计划开关），历史回显时卡片照常标「自动开启」 */
    private Map<String, Object> planSnapshot(List<Map<String, Object>> versions, String status, boolean auto) {
        Map<String, Object> rec = new LinkedHashMap<>();
        Map<String, Object> last = versions.get(versions.size() - 1);
        rec.put("plan", last.get("plan"));
        rec.put("status", status);
        rec.put("versions", new java.util.ArrayList<>(versions));
        if (auto) rec.put("auto", true);
        return rec;
    }

    /** 批准后的计划 → 执行轮 system 注入块 */
    private String approvedPlanBlock(String plan, boolean todoExposed) {
        return "\n\n【已批准的执行计划】用户已审阅并批准以下执行计划，请按计划逐步执行；"
                + "执行中发现计划不可行时可作必要偏离，但需在回答中说明原因。"
                + (todoExposed ? "开工前先把下面每一条落成任务清单项（writeTodo，整表一次传全），"
                        + "随后每完成一条就更新状态——清单是给这个计划配的执行进度视图，不是第二次确认。" : "")
                + "\n" + plan;
    }

    /**
     * 已检索资料清单（计划模式的计划输入）：只给标题与来源文档名——计划关心「有什么可依据」，
     * 不需要正文本身；全文在执行轮照常注入，计划调用保持轻量（标题清单 vs 全文差一个数量级的输入）。
     */
    public String refsDigest(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map<String, Object> s : sources) {
            if (n >= 10) {
                sb.append("…等共 ").append(sources.size()).append(" 段\n");
                break;
            }
            Object title = s.get("title");
            sb.append("- ").append(title == null || String.valueOf(title).isBlank()
                    ? "（无标题）" : String.valueOf(title));
            Object fileName = s.get("fileName");
            if (fileName != null && !String.valueOf(fileName).isBlank()) {
                sb.append("（").append(fileName).append("）");
            }
            sb.append("\n");
            n++;
        }
        return sb.toString().trim();
    }

    /**
     * 计划阶段（单版）执行体：一次无工具的流式模型调用产出计划文本（plan_delta 逐段下发，用户即时看到计划成形），
     * 流毕落库计划批准卡（c_ai_tool_approval，tool_name=planApproval）并阻塞等待用户裁决。
     * <p>等待语义与 askUser 相同：人工等待不计入整轮机器预算（beginHumanWait）；等待期间通道失效不中止
     * 本轮（卡片已落库，用户刷新/换设备后仍可批准，回答转后台续跑照常落库）；超时按未批准收尾。
     * <p>计划生成本身失败/超时/产出为空：不挂卡，登记降级后按普通模式继续——计划模式不能把本轮卡死。
     *
     * @param userFeedback 非 null=本版是用户退回后的修订版，按该意见重出（v1 为 null）
     * @param versions     已产的版本链（修订版作为上一版上下文喂给模型）
     * @param notify       是否发「执行计划待确认」站内通知（仅首版发，修订版用户就在会话里）
     */
    private PlanPhaseOutcome runPlanPhase(TurnState st, String rolePart, String planQuestion,
                                          List<String> skills,
                                          java.util.List<org.springframework.ai.tool.ToolCallback> callbacks,
                                          String refsDigest, String userFeedback,
                                          List<Map<String, Object>> versions, boolean notify,
                                          PlanTurnHooks hooks) {
        // ---- 1. 计划 prompt：角色段 + 计划指令 + 能力清单（与生成轮同口径装配的回调名与描述）----
        StringBuilder ps = new StringBuilder(rolePart == null ? "" : rolePart)
                .append("\n\n【计划模式】用户为本轮开启了计划模式：请先产出一份执行计划供用户确认，暂时不要开始回答。\n")
                .append("计划要求：\n")
                .append("1. 这是给用户审阅的「结果计划」：用 Markdown 有序列表说明成品将做成什么样——最终交付物是什么形态、"
                        + "包含哪些部分、关键选择是什么（结构、内容范围、风格基调、呈现方式等），"
                        + "用户看完就能判断是不是他要的东西；也可以说明会怎么让他看到它（如先给一版可预览的原型）；\n")
                .append("2. 只写用户关心的结果：不写工具名、能力名、内部做法与执行细节；\n")
                .append("3. 关键要素在出计划前已向用户确认过（若发生过提问，答案见「用户已确认的关键要素」）："
                        + "不要再写「先与你确认…」这类步骤；仍有拿不准的细节按你的最佳判断定，"
                        + "并在计划里点明这是你的选择，用户可以直接改；\n")
                .append("4. 只能计划用下面列出的能力去做，不要出现清单之外的动作（清单仅供你核对可行性，名字不要写进计划）：\n");
        int listed = 0;
        for (org.springframework.ai.tool.ToolCallback cb : callbacks) {
            if (listed >= 15) {
                ps.append("   - …等共 ").append(callbacks.size()).append(" 个工具\n");
                break;
            }
            var def = cb.getToolDefinition();
            if (def == null) continue;
            String desc = def.description() == null ? "" : def.description();
            if (desc.length() > 80) desc = desc.substring(0, 80) + "…";
            ps.append("   - ").append(def.name()).append("：").append(desc).append("\n");
            listed++;
        }
        ps.append("5. 事情简单或资料已足够时，计划可以只有一条（例如：直接产出结果）；\n")
                .append("6. 只输出计划本身（Markdown 列表），不要输出前言、解释或计划以外的任何内容，总长度控制在 10 行以内。");
        StringBuilder pu = new StringBuilder(planQuestion == null ? "" : planQuestion);
        if (skills != null && !skills.isEmpty()) {
            pu.append("\n\n（用户本轮指定了技能：").append(String.join("、", skills))
                    .append("——其全文将在执行阶段注入，计划里可以提到按技能执行）");
        }
        if (refsDigest != null && !refsDigest.isBlank()) {
            pu.append("\n\n【已检索资料清单】本轮已检索到以下参考资料（仅标题，执行阶段提供全文）：\n").append(refsDigest);
        }
        String historyText = historyCompaction.buildHistoryText(sessionService.getRecentHistory(st.sessionId, 2));
        if (!historyText.isEmpty()) {
            pu.append("\n\n对话历史：\n").append(historyText);
        }
        // 修订版（用户在等批界面退回并给了意见）：上一版计划 + 修改意见一起喂给模型重出——
        // 用户也可能直接改写了计划全文，此时意见即改稿，按改稿重出（只做必要澄清，别丢用户的改动）
        if (userFeedback != null) {
            String prevPlan = "";
            for (int i = versions.size() - 1; i >= 0; i--) {
                Object p = versions.get(i).get("plan");
                if (p != null && !String.valueOf(p).isBlank()) { prevPlan = String.valueOf(p); break; }
            }
            ps.append("\n\n【修订轮】本次不是首版计划：用户已看过上一版并提出了修改意见，"
                    + "请按意见产出新的完整计划（仍是 Markdown 列表、10 行以内、只输出计划本身）；"
                    + "与用户意见无关的部分保持原样，不要借机重写。");
            pu.append("\n\n【上一版计划（用户要修改它）】\n").append(prevPlan)
                    .append("\n\n【用户的修改意见】\n").append(userFeedback)
                    .append("\n\n请产出修改后的新一版计划。");
        } else {
            // 「继续对话」轮（上一版计划被用户带着意见取代）：本轮问题就是对上一版提的修改要求，
            // 把上一版计划带上，产出"修改后的计划"而不是从零重来
            String prevSuperseded = findSupersededPlan(st.sessionId);
            if (prevSuperseded != null) {
                ps.append("\n\n【继续对话】用户对上一版执行计划提出了修改要求（见下方的用户消息），"
                        + "请在他要改的基础上产出修改后的完整计划（仍是 Markdown 列表、10 行以内、只输出计划本身）；"
                        + "与他意见无关的部分保持原样，不要借机重写。");
                pu.append("\n\n【上一版计划（用户要改的是它）】\n").append(prevSuperseded);
            }
        }
        // ---- 1.5 计划前的要素澄清（每轮计划的首版前问一次，同轮修订版不问）：模型拿不准才弹提问卡，
        //          答完把确认结果并入计划依据——「先问 → 答 → 再出计划」的第一步；拿得准走 READY 不打扰。
        //          收集型通道没人能作答（定时任务/MCP/子智能体），跳过。
        // lambda 中引用需 effectively final，用容器承接
        java.util.concurrent.atomic.AtomicBoolean clarifyAsked = new java.util.concurrent.atomic.AtomicBoolean();
        if (versions.isEmpty() && userFeedback == null && !(st.channel.emitter instanceof CollectingSseEmitter)) {
            java.util.List<BuiltinTools.AskQuestion> clarifyQs = planClarifyQuestions(st, rolePart, pu.toString());
            if (!clarifyQs.isEmpty()) {
                clarifyAsked.set(true);
                if (!st.channel.detached) runtime.sendSseEvent(st.channel.emitter, "stage", "正在确认关键要素…", st.sessionId);
                // 提问卡走与执行期 askUser 完全相同的记录管道：start（时间线占位，前端对 askUser 的
                // start 不渲染）+ 终态（前端据此撤下提问面板、问答记录卡落进 toolCalls 随消息持久化）。
                // 不记录的话「提问面板撤下」永远不会触发（面板只在 askUser 工具终态或整轮结束时撤），
                // 而计划轮要挂着等用户批准 ⇒ 提问面板会一直挡住计划卡与确认栏。
                String askArgs = JSON.toJSONString(Map.of("questions", clarifyQs.stream().map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("topic", q.topic());
                    m.put("question", q.question());
                    m.put("options", q.options());
                    return m;
                }).toList()));
                long askStartAt = System.currentTimeMillis();
                hooks.recordToolStatus(st, "askUser", askArgs, "start", null, 0, 1, false);
                String clarified = hooks.askUser(st, clarifyQs);
                hooks.recordToolStatus(st, "askUser", askArgs, "done", clarified,
                        System.currentTimeMillis() - askStartAt, 1, false);
                String qa = formatClarifyAnswers(clarifyQs, clarified);
                if (!qa.isBlank()) {
                    pu.append("\n\n【用户已确认的关键要素】\n").append(qa);
                }
                pu.append("\n\n（未作答或未问到的关键点：按你的最佳判断给默认，并在计划里点明该处，用户可以直接改。）");
                if (!st.channel.detached) runtime.sendSseEvent(st.channel.emitter, "stage", "正在制定计划…", st.sessionId);
            }
        }
        // ---- 2. 调用选项：辅助调用口径——能关思考的方言下发关闭字段（思考与正文共享 max_tokens，
        //         默认开思考的模型会把预算花在推理上、计划正文被挤空）；关不掉的多留一份输出预算。
        //         计划要短，max_tokens 收紧到声明值以内。 ----
        Map<String, Object> thinkOff = modelRegistryService.reasoningOffBody(st.model);
        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder()
                .model(st.model)
                .temperature(configService.getDouble("chat.temperature"))
                .maxTokens(thinkOff.isEmpty() ? 3072 : 1536);
        if (!thinkOff.isEmpty()) opts.extraBody(thinkOff);
        if (st.effectiveMaxOutput > 0 && st.effectiveMaxOutput < 3072) {
            opts.maxTokens(st.effectiveMaxOutput);
        }
        // ---- 3. 流式生成：增量走 plan_delta（复用整轮流级心跳/看门狗）----
        // 通道失效时分两类：本轮刚问过用户（用户在等待期断线/换了设备，答案已送到）或正等人作答/已转后台
        // 续跑 ⇒ 只丢事件、继续产出并挂卡（卡片落库，从「待批准计划」恢复）；其余照旧「断开即止损」中止本轮。
        // 注意不能只靠 keepRunningWithoutChannel：答案送达时 askWaits 已归零，而断线往往到第一次发送
        // 失败才被发现（等待期没有写入事件）——这个竞态窗口靠 clarifyAsked 兜住。
        StringBuilder planText = new StringBuilder();
        String billingUid = com.wenqu.ai.util.RequestUser.uid();
        try {
            chatClient.prompt()
                    .system(ps.toString())
                    .user(pu.toString())
                    .options(opts.build())
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        String delta = "";
                        if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                            String text = resp.getResult().getOutput().getText();
                            delta = text == null ? "" : text;
                        }
                        if (!delta.isEmpty() && planText.length() <= PLAN_MAX_CHARS) {
                            planText.append(delta);
                            if (!st.channel.detached && !runtime.sendSseEvent(st.channel.emitter, "plan_delta", delta, st.sessionId)) {
                                if (clarifyAsked.get() || st.keepRunningWithoutChannel()) {
                                    st.channel.detached = true;   // 转后台续跑：此后只积累不发送
                                } else {
                                    throw new SseClientGoneException();
                                }
                            }
                        }
                    })
                    .contextWrite(ctx -> com.wenqu.ai.util.UsageAttr.put(ctx,
                            com.wenqu.ai.util.UsageAttr.of(
                                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                                    st.sessionId, null, "chat")))
                    .blockLast(Duration.ofMillis(planGenTimeoutMs()));
        } catch (SseClientGoneException e) {
            log.info("[PLAN] 客户端断开，计划流终止: session={}", st.sessionId);
            return PlanPhaseOutcome.failed();
        } catch (Exception e) {
            log.warn("[FAIL-LOUD] 计划生成失败/超时，本轮降级为普通回答: session={} {}", st.sessionId, e.getMessage());
            runtime.addDegradation(st.degradations, st.degradedCodes, "planGenFailed",
                    "计划生成失败，本轮已按普通模式继续");
            return PlanPhaseOutcome.failed();
        }
        String draft = stripPlanNoise(planText.toString());
        if (draft.isBlank()) {
            log.warn("[FAIL-LOUD] 计划生成为空（正文 0 token），本轮降级为普通回答: session={}", st.sessionId);
            runtime.addDegradation(st.degradations, st.degradedCodes, "planGenFailed",
                    "计划生成为空，本轮已按普通模式继续");
            return PlanPhaseOutcome.failed();
        }
        // ---- 4. 挂起等批准：落库 + 唤醒句柄（都在 HitlDecisionService）+ 站内通知 + SSE 卡（与 askUser 同一管道）----
        long timeout = hitl.planTimeoutMs();
        String planApprovalId = java.util.UUID.randomUUID().toString();
        try (var wait = hitl.openPlan(planApprovalId, st.sessionId, st.userId, st.turnId, draft)) {
            if (notify) {
                try {
                    notificationService.create(st.userId, com.wenqu.ai.model.Notification.TYPE_TOOL_APPROVAL,
                            "执行计划待确认",
                            "模型为本轮产出了执行计划，请在会话中批准或修改后执行（超时未确认本轮将停止）。",
                            "session", st.sessionId, "tool:" + planApprovalId, planApprovalId);
                } catch (Exception e) {
                    log.warn("[PLAN] 计划通知写入失败（不阻塞计划流程）: {}", e.getMessage());
                }
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("planApprovalId", planApprovalId);
            req.put("plan", draft);
            req.put("timeoutMs", timeout);
            if (st.planAuto) req.put("auto", true);   // 按消息意图自动开启：卡片标「自动开启」
            // 卡片下发失败（连接已断）：本轮转后台续跑——卡片已落库，用户刷新/换设备后仍可批准，
            // 批准后的回答照常生成并落库（与 askUser 同语义）
            if (!runtime.sendSseEvent(st.channel.emitter, "plan_approval", JSON.toJSONString(req), st.sessionId)) {
                st.channel.detached = true;
                log.info("[PLAN] 计划卡通道已失效，本轮转后台续跑: id={} session={}", planApprovalId, st.sessionId);
            }
            log.info("[PLAN] 等待用户确认执行计划: id={} session={} 窗口={}ms 计划 {} 字",
                    planApprovalId, st.sessionId, timeout, draft.length());
            PlanDecision decision;
            st.planWaits.incrementAndGet();
            TurnRegistry.beginHumanWait(st.channel.emitter);   // 用户裁决时间不计入整轮机器预算（见 TurnDeadline）
            try {
                decision = wait.future().get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                hitl.markApprovalResolved(planApprovalId, "TIMEOUT", st.userId);
                decision = null;
                log.warn("[PLAN] 计划批准超时，按未批准收尾: id={} session={}", planApprovalId, st.sessionId);
            } catch (java.util.concurrent.ExecutionException ee) {
                hitl.markApprovalResolved(planApprovalId, "TIMEOUT", st.userId);
                decision = null;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                hitl.markApprovalResolved(planApprovalId, "TIMEOUT", st.userId);
                decision = null;
            } finally {
                st.planWaits.decrementAndGet();
                TurnRegistry.endHumanWait(st.channel.emitter);
            }
            // 本版结果交回闸门（版本链快照由闸门统一组装，这里不再落 st.planRecord）
            if (decision == null) return PlanPhaseOutcome.of(draft, false, null);                    // 超时=未批准
            if (decision.reviseFeedback() != null) return PlanPhaseOutcome.of(draft, false, decision.reviseFeedback()); // 退回重出
            if (decision.superseded()) return PlanPhaseOutcome.superseded(draft);                    // 继续对话：本版作废
            if (!decision.approved()) return PlanPhaseOutcome.of(draft, false, null);                // 拒绝
            String finalPlan = decision.plan() == null || decision.plan().isBlank() ? draft : decision.plan();
            return PlanPhaseOutcome.of(finalPlan, true, null);                                       // 批准
        }
    }

    /**
     * 计划前的要素澄清（首版计划前的一次小判定）：判断是否有「无法从资料与对话推断、必须由用户拍板」的
     * 关键要素——有就先走提问卡问清、答完再出计划（「先问 → 答 → 再出计划」）；模型拿得准则输出 READY，
     * 不打扰用户、直接出计划。上下文与计划轮同源：同一段角色提示词（判定者先知道自己是谁、产品是什么，
     * 才不会有「你是做什么的」这类本可自答的问题）+ 同一段 user 文本；失败/超时/输出不可解析一律当
     * READY——澄清是加分项，不能把计划轮卡住。
     * <p>问的范围刻意收紧：计划卡本身就是「用户看一版、不满意退回重出」的确认关口，凡计划里写明了
     * 就能被用户直接改掉的取舍（呈现形式、篇幅长短、风格措辞……）都不必提前问——实测过一轮：判在这里
     * 问了「三块内容合成一张表还是分三段」，而这个问题用户在看计划时改一句就解决，提问卡却把整轮挂住十分钟。
     */
    private java.util.List<BuiltinTools.AskQuestion> planClarifyQuestions(TurnState st, String rolePart,
                                                                          String planContext) {
        String instructions = (rolePart == null || rolePart.isBlank() ? "" : rolePart + "\n\n")
                + "你是「计划前澄清」判断器：用户开启了计划模式，你即将给他一份「成品将做成什么样」的计划。"
                + "先判断：有哪些关键要素，是「不确认就很可能做错方向」、又无法从用户消息 / 已检索资料 / "
                + "对话历史 / 上一版计划里得到的？\n"
                + "- 逐个自问：用户消息里已经给了吗？能从资料/历史里看出来吗？能先按默认做、在计划里标出让用户改吗？"
                + "只要占一条，就不要问它；\n"
                + "- **不要问**这些：成品长什么样（表格还是分段、篇幅、语气措辞、要不要配图举例）、"
                + "内容取舍的宽窄、可以分几种做法——这些都会写进计划，用户看计划时改一句就行，"
                + "提前问等于把同一件事确认两遍；\n"
                + "- 只问「问错了整份计划就得重做」的方向性前提：例如这件事给谁用、要解决的到底是哪个问题、"
                + "有哪个业务口径是你无权替他定的；通常 0~1 个，最多 3 个；一个都不该问就输出 READY；\n"
                + "- 每个问题一句话交代背景与要决定的事；给 2~3 个具体候选（第一项是你推荐的），用户也可自由输入；\n"
                + "- 只输出下面两种内容之一，不要任何解释：\n"
                + "  无需提问：READY\n"
                + "  需要提问：{\"questions\":[{\"topic\":\"话题\",\"question\":\"……\",\"options\":[\"……\",\"……\"]}]}";
        // 与计划轮同口径的思考档处理：能关的关掉；这是小判定，生成封顶 20s，超时当 READY
        Map<String, Object> thinkOff = modelRegistryService.reasoningOffBody(st.model);
        OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder()
                .model(st.model)
                .temperature(0.0)
                .maxTokens(thinkOff.isEmpty() ? 3072 : 1536)
                .internalToolExecutionEnabled(false);
        if (!thinkOff.isEmpty()) opts.extraBody(thinkOff);
        StringBuilder out = new StringBuilder();
        String billingUid = com.wenqu.ai.util.RequestUser.uid();
        try {
            chatClient.prompt()
                    .system(instructions)
                    .user(planContext)
                    .options(opts.build())
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        if (resp.getResult() != null && resp.getResult().getOutput() != null
                                && out.length() < 8000) {
                            String t = resp.getResult().getOutput().getText();
                            if (t != null) out.append(t);
                        }
                    })
                    .contextWrite(ctx -> com.wenqu.ai.util.UsageAttr.put(ctx,
                            com.wenqu.ai.util.UsageAttr.of(
                                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                                    st.sessionId, null, "chat")))
                    .blockLast(Duration.ofMillis(Math.min(planGenTimeoutMs(), 20000L)));
        } catch (Exception e) {
            log.info("[PLAN] 要素澄清判定失败/超时，直接出计划: session={} {}", st.sessionId, e.getMessage());
            return java.util.List.of();
        }
        return parseClarifyQuestions(out.toString());
    }

    /** 解析澄清判定输出：READY / 无 JSON / 解析失败 / 全部不合法都返回空表（=不用问）；问题按提问卡限制收敛
     *  （最多 3 问、每题 2~{@link HitlDecisionService#MAX_ASK_OPTIONS} 个候选），避免走到整卡拒绝那条路上去 */
    private java.util.List<BuiltinTools.AskQuestion> parseClarifyQuestions(String raw) {
        if (raw == null || raw.isBlank()) return java.util.List.of();
        String s = raw.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return java.util.List.of();
        try {
            JSONArray arr = JSON.parseObject(s.substring(a, b + 1)).getJSONArray("questions");
            if (arr == null) return java.util.List.of();
            java.util.List<BuiltinTools.AskQuestion> qs = new java.util.ArrayList<>();
            for (int i = 0; i < arr.size() && qs.size() < 3; i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o == null) continue;
                String question = o.getString("question") == null ? "" : o.getString("question").trim();
                if (question.isEmpty()) continue;
                if (question.length() > 500) question = question.substring(0, 500);
                String topic = o.getString("topic") == null ? "" : o.getString("topic").trim();
                if (topic.length() > 16) topic = topic.substring(0, 16);
                java.util.List<String> opts = new java.util.ArrayList<>();
                JSONArray oa = o.getJSONArray("options");
                if (oa != null) for (Object oo : oa) {
                    if (opts.size() >= HitlDecisionService.MAX_ASK_OPTIONS) break;
                    String v = oo == null ? "" : String.valueOf(oo).trim();
                    if (v.isEmpty() || opts.contains(v)) continue;
                    opts.add(v.length() > 200 ? v.substring(0, 200) : v);
                }
                if (opts.size() < 2) continue;   // 少于 2 个候选不构成选择题（与提问卡口径一致）
                qs.add(new BuiltinTools.AskQuestion(topic, question, opts));
            }
            return qs;
        } catch (Exception e) {
            log.info("[PLAN] 要素澄清输出不可解析，按无需提问处理: {}", e.getMessage());
            return java.util.List.of();
        }
    }

    /** 提问卡的批量答案 → 计划轮可读的问答块；未作答（忽略/超时/留空）的条目不列 */
    private String formatClarifyAnswers(java.util.List<BuiltinTools.AskQuestion> qs, String answers) {
        java.util.List<String> ans = new java.util.ArrayList<>();
        if (qs.size() == 1) {
            ans.add(answers == null ? "" : answers);
        } else {
            try {
                for (Object o : JSON.parseArray(answers == null ? "[]" : answers)) {
                    ans.add(o == null ? "" : String.valueOf(o));
                }
            } catch (Exception e) {
                log.warn("[PLAN] 澄清答案解析失败，按未作答处理: {}", e.getMessage());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < qs.size(); i++) {
            String a = i < ans.size() ? ans.get(i).trim() : "";
            if (a.isEmpty() || a.startsWith("（用户未回答这一题")) continue;
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("- ").append(qs.get(i).question()).append("\n  用户选择：").append(a);
        }
        return sb.toString();
    }

    /** 计划正文降噪：剥掉 <process>/<related> 闭合块与首个未闭合开口之后的内容（计划轮没有工具，
     *  正常不会产出这些标签，防角色提示词诱导出的独白混进计划卡） */
    private String stripPlanNoise(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw.replaceAll("(?s)<process>.*?</process>|<related>.*?</related>", " ");
        int cut = s.indexOf("<process>");
        int cut2 = s.indexOf("<related>");
        if (cut >= 0 && (cut2 < 0 || cut < cut2)) s = s.substring(0, cut);
        else if (cut2 >= 0) s = s.substring(0, cut2);
        return s.trim();
    }

    /** 上一版被「继续对话」取代的计划文本（新一轮按用户意见改它；没有则 null）：
     *  只认「最近一条助手消息就是被取代的计划」——中间隔了别的回答就不挂上一版，避免把无关计划当底稿 */
    private String findSupersededPlan(String sessionId) {
        try {
            List<Map<String, Object>> recent = sessionService.getRecentHistory(sessionId, 3);
            if (recent == null) return null;
            for (int i = recent.size() - 1; i >= 0; i--) {
                Map<String, Object> m = recent.get(i);
                if (m == null || !"assistant".equals(String.valueOf(m.get("role")))) continue;
                Object planObj = m.get("plan");
                if (!(planObj instanceof Map)) return null;
                Map<?, ?> plan = (Map<?, ?>) planObj;
                if (!"superseded".equals(String.valueOf(plan.get("status")))) return null;
                Object p = plan.get("plan");
                String text = p == null ? "" : String.valueOf(p);
                return text.isBlank() ? null : text;
            }
        } catch (Exception e) {
            log.warn("[PLAN] 读取上一版计划失败（本轮按首版出计划）: session={} {}", sessionId, e.getMessage());
        }
        return null;
    }
}
