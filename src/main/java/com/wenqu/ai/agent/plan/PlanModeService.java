package com.wenqu.ai.agent.plan;

import com.wenqu.ai.agent.context.HistoryCompactionService;
import com.wenqu.ai.service.CollectingSseEmitter;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * <b>计划模式</b>这一族，框架平移后只剩「计划意图识别」。
 *
 * <p>原先是完整的人在本路计划闸门（出计划 → 挂批准卡 → 批准后注入执行轮 / 退回重出 / 继续对话取代），
 * 随 2026-10-15 手写推理循环的删除整体退场：那套闸门绑在 {@code TurnState} 上（版本链、澄清提问轮、
 * 计划快照落库都在问答侧的状态袋里），框架路径下由 AgentScope 原生的 {@code enablePlanMode()} 接管
 * （{@code plan_*} 工具 + 计划位拦截 + {@code plan_approval} 确认事件，见 {@code AgentscopeChatEngine}
 * 与 {@code FrameworkSessions}）。两侧不是双跑：框架引擎只调本类的意图识别，不调任何计划闸门。
 * <p>留下来的意图识别仍归问渠：判据是产品语义（「不先对齐就会白做」不是框架概念），且要按问渠的
 * 游客/重新生成/收集型通道口径跳过不适用的轮次。
 *
 * @author yuanke
 */
@Slf4j
@Service
public class PlanModeService {

    /** 意图识别专用线程池（与派遣路由同款：2 线程 + 超时 cancel(true) 中断底层调用；daemon 不阻 JVM 退出） */
    private static final java.util.concurrent.ThreadPoolExecutor PLAN_INTENT_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "plan-intent");
                t.setDaemon(true);
                return t;
            });

    private final ConfigService configService;
    private final ChatClient chatClient;
    private final ModelRegistryService modelRegistryService;
    private final SessionService sessionService;
    private final HistoryCompactionService historyCompaction;

    public PlanModeService(ConfigService configService,
                           ChatClient chatClient,
                           ModelRegistryService modelRegistryService,
                           SessionService sessionService,
                           HistoryCompactionService historyCompaction) {
        this.configService = configService;
        this.chatClient = chatClient;
        this.modelRegistryService = modelRegistryService;
        this.sessionService = sessionService;
        this.historyCompaction = historyCompaction;
    }

    // ==================== 计划意图识别（按用户意图自动开启计划模式） ====================

    /**
     * 启动计划意图识别（与图片/附件/检索并行跑，闸门前取结果）：用户在计划开关之外没提计划时，
     * 判断这条消息是否「不先对齐就会白做」——方向没定清又要从零产出成型交付物，或动作不易回头。
     * 环节多、有产物本身都不是理由（进度有任务清单，不拦人）。判据见下方提示词，改判据要同步改
     * config-schema 的 planAutoIntent 说明。
     * 与闸门同口径跳过不适用的轮次：游客、无工具模型（计划本就降级）、收集型通道（定时任务/MCP/子智能体
     * 没人能批准计划卡）、重新生成（用户要的是重答不是重出计划）。返回 null = 本轮不检测。
     */
    public CompletableFuture<Boolean> startIntentCheck(
            SseEmitter emitter, boolean guestMode, boolean planMode, boolean regenerate,
            String sessionId, String question, String model, boolean hasAttachments) {
        if (planMode || guestMode || regenerate || emitter instanceof CollectingSseEmitter) return null;
        if (!configService.getBoolean("chat.planAutoIntent", true)) return null;
        if (model == null || !modelRegistryService.toolCapableOf(model)) return null;
        String uid = com.wenqu.ai.util.RequestUser.uid();
        return CompletableFuture.supplyAsync(() -> {
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
                        + "一句话判据：只有「先花一分钟对齐要交付成什么样」能明显减少返工，或这一步下去不易回头，才算适合；"
                        + "只是环节多、只是最终会有产物，都不算。\n"
                        + (hasAttachments ? "（本条消息附带了文件或图片——通常意味着要把材料加工成产物，但仍以「做法说清了没有、"
                        + "有没有要他拍板的岔路」为准。）" : "")
                        + (history == null || history.isBlank() ? ""
                        : "最近对话（辅助理解意图，如「继续」「改成 X」这类指代）：\n" + history + "\n")
                        + "\n用户消息：" + question
                        + "\n\n只回答 YES 或 NO，不要输出任何其他内容。";
                // 与计划轮同口径的思考档处理：能关思考的方言下发关闭字段（思考与正文共享 max_tokens，
                // 恒思考模型会把预算花在推理上，判定正文被挤空）；关不掉的多留一份输出预算
                Map<String, Object> thinkOff = modelRegistryService.reasoningOffBody(model);
                OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder()
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
    public boolean awaitIntent(CompletableFuture<Boolean> f) {
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
}
