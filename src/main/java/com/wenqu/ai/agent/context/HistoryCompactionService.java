package com.wenqu.ai.agent.context;

import com.wenqu.ai.agent.chat.ChatRunRuntime;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.SessionService;
import com.wenqu.ai.util.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 对话历史的<b>预算驱动装配与滚动压缩</b>（P6 刀 4 第一族，自 {@code ChatService} 抽出）。
 *
 * <p>这一族回答三个问题：本轮带多少历史（近期原样 + 早期摘要）、什么时候压（阈值）、
 * 用户手动 /compact 时怎么分批压完。它<b>不认识本轮状态</b>——入参只有会话、模型、预算与
 * 降级登记用的两只容器，所以它与 {@code TurnState} 之间没有任何方向的依赖。
 *
 * <p>四条口径别在改动中丢：
 * <ul>
 *   <li><b>压缩阈值 = 检索预算 × context.compressRatio</b>，在整窗打满之前压：压缩调用自身要花
 *       输入 token 与数秒时延，提前压才给当轮回答留出空间，且之后每轮摊销；</li>
 *   <li>{@code context.historyCompress} 只管<b>要不要自动压</b>，与摘要注入本身无关——
 *       所以手动压缩永远可用；</li>
 *   <li>自动路径用紧超时（等不起），手动路径用宽超时（界面有 loading，单批可跑 20 秒上下）；</li>
 *   <li>历史读取失败/压缩失败一律 fail-loud，绝不静默丢历史。</li>
 * </ul>
 *
 * @author yuanke
 */
@Slf4j
@Service
public class HistoryCompactionService {

    /** 历史装配结果：注入 prompt 的两段文本 + 分类用量（容量面板） + 本轮装配时的摘要覆盖点 */
    public record HistoryBundle(String summaryText, String recentText,
                                int summaryTokens, int recentTokens, long summaryUntilSeq) {}

    /** 历史扫描上限（条）：单会话未压缩积压的读取上限，超出部分下一轮压缩自愈 */
    private static final int HISTORY_SCAN_LIMIT = 200;
    /** 至少保留原样的消息条数（2 轮）：保证最近一问一答逐字在场，压缩不吞掉刚说的话 */
    private static final int HISTORY_MIN_KEEP = 4;
    /** 手动压缩（/compact）单次请求的摘要调用上限与单批输入上限：长会话分多批滚动合并，
     *  避免一次调用吃掉整窗（输入超限会被网关直接拒绝）；到上限仍有积压时回传 partial，不假装压完了 */
    private static final int COMPACT_MAX_ROUNDS = 8;
    private static final int COMPACT_CHUNK_TOKENS = 12000;
    /** 手动压缩单批超时下限（毫秒）：用户显式发起、界面有 loading，不必卡自动路径那个 20 秒的紧阈值
     *  （单批输出接近 1024 上限时实测可跑 19 秒，紧阈值下会误判超时） */
    private static final long COMPACT_TIMEOUT_FLOOR_MS = 60000L;

    private final ConfigService configService;
    private final SessionService sessionService;
    private final ChatClient chatClient;
    private final ModelRegistryService modelRegistryService;
    private final ChatRunRuntime runtime;

    /**
     * 历史压缩专用线程池：与改写池分开（同轮可能先压缩再改写，共用会互相排队拉长首字时延）。
     * 线程数随 chat.pipelineThreads 联动（问答流水线侧调 {@link #syncPoolSize}）：压缩是偶发的长
     * LLM 调用（≤20s），排队只延迟压缩不影响本轮可答性，给流水线并发的四分之一即可。
     * 直接构造 ThreadPoolExecutor（Executors 工厂返回不可转型的包装类，调容需 setCore/Max）
     */
    private final ThreadPoolExecutor compressExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "history-compress");
        t.setDaemon(true);
        return t;
    });

    public HistoryCompactionService(ConfigService configService,
                                    SessionService sessionService,
                                    ChatClient chatClient,
                                    ModelRegistryService modelRegistryService,
                                    ChatRunRuntime runtime) {
        this.configService = configService;
        this.sessionService = sessionService;
        this.chatClient = chatClient;
        this.modelRegistryService = modelRegistryService;
        this.runtime = runtime;
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        compressExecutor.shutdownNow();
    }

    /**
     * 辅助池联动（问答流水线按 chat.pipelineThreads 扩容时调用）：压缩是偶发长调用，
     * 给四分之一流水线并发。不联动的话，流水线扩到百级而本池固定 1，压缩成片超时静默降级。
     */
    public void syncPoolSize(int n) {
        if (n <= 0 || n == compressExecutor.getCorePoolSize()) return;
        // 有界调容：全程维持 max ≥ core 的不变量（先抬 max、core 归位后再收 max）
        compressExecutor.setMaximumPoolSize(Math.max(compressExecutor.getMaximumPoolSize(), n));
        compressExecutor.setCorePoolSize(n);
        compressExecutor.setMaximumPoolSize(n);
    }

    // ==================== 装配 ====================

    /**
     * 预算驱动装配对话历史（替代旧的「按轮数 + 单条截断」）：近期轮次原样注入、更早轮次滚动压缩进会话摘要，
     * 全部历史信息始终在场（近期原样 + 远期摘要）。
     * <p>
     * 压缩触发线 = 检索预算 × context.compressRatio。不在整窗打满时才压：压缩调用自身要花输入 token 与数秒时延，
     * 提前压才能给当轮回答留出空间，且之后每轮摊销、不会每轮都触发。
     * 压缩关闭或调用失败 → 回落「按预算装近期、更早丢弃」（失败时强制可见降级提示，不静默丢历史）。
     */
    public HistoryBundle assemble(String sessionId, String resolvedModel, int budget,
                                  List<Map<String, String>> degradations, Set<String> degradedCodes) {
        com.wenqu.ai.model.Session session = sessionService.sessionById(sessionId);
        String summary = (session == null || session.getHistorySummary() == null) ? "" : session.getHistorySummary();
        long untilSeq = (session == null || session.getSummaryUntilSeq() == null) ? 0L : session.getSummaryUntilSeq();
        List<Map<String, Object>> backlog = sessionService.getHistoryAfter(sessionId, untilSeq, HISTORY_SCAN_LIMIT);
        if (backlog == null) {
            // M6 fail-loud：历史读取失败 → 本次对话无历史注入
            runtime.addDegradation(degradations, degradedCodes, "historyFailed", "会话历史读取失败，本次无多轮记忆");
            return new HistoryBundle("", "", 0, 0, untilSeq);
        }
        boolean compressOn = configService.getBoolean("context.historyCompress");
        double ratio = Math.max(0.1, Math.min(0.9, configService.getDouble("context.compressRatio")));
        int threshold = Math.max(1000, (int) (budget * ratio));
        int summaryTokens = TokenCounter.estimate(summary);

        if (compressOn && backlog.size() > HISTORY_MIN_KEEP) {
            int totalTokens = summaryTokens;
            for (Map<String, Object> m : backlog) totalTokens += TokenCounter.estimate(historyLine(m));
            if (totalTokens > threshold) {
                // 近期原样保留到「阈值 − 摘要」装不下为止，更早的（连同旧摘要）滚动并入新摘要
                int keepFrom = fitFromNewest(backlog, Math.max(200, threshold - summaryTokens));
                if (keepFrom > 0) {
                    List<Map<String, Object>> older = backlog.subList(0, keepFrom);
                    try {
                        // 自动压缩跑在回答链路里：超时用紧阈值（等不起），与手动 /compact 的宽等待分开
                        String merged = summarizeHistory(sessionId, resolvedModel, summary, historyText(older), null, null);
                        if (merged != null && !merged.isBlank()) {
                            long newUntil = seqOf(older.get(older.size() - 1));
                            sessionService.updateHistorySummary(sessionId, merged, newUntil);
                            summary = merged;
                            summaryTokens = TokenCounter.estimate(summary);
                            backlog = backlog.subList(keepFrom, backlog.size());
                            // 覆盖点前移即「本轮压掉了东西」：对话流里那条「已压缩上下文」分隔线就靠它定位
                            untilSeq = newUntil;
                            log.info("[CTX] 历史压缩完成: {} 条并入摘要（until={}，摘要 {} token，保留近期 {} 条）",
                                    older.size(), newUntil, summaryTokens, backlog.size());
                        }
                    } catch (Exception e) {
                        // 压缩失败不能静默：用户会看到「早期对话消失」却不知原因。绕过检索调试开关直接上报。
                        degradations.add(Map.of("code", "historyCompressFailed", "level", "warn",
                                "msg", "早期对话压缩失败，本轮仅带入近期历史（下轮自动重试）"));
                        log.warn("[CTX] 历史压缩失败，回落「按预算装近期」: {}", e.getMessage());
                    }
                }
            }
        }
        // 近期原样：从最新往回装到「阈值 − 摘要」为止（压缩关闭/失败时即「按预算装近期、更早丢弃」）
        int recentBudget = Math.max(200, threshold - summaryTokens);
        int from = fitFromNewest(backlog, recentBudget);
        String recentText = historyText(backlog.subList(from, backlog.size()));
        return new HistoryBundle(summary, recentText, summaryTokens, TokenCounter.estimate(recentText), untilSeq);
    }

    /**
     * 当前这份滚动摘要（对话流里「已压缩上下文」分隔线点开要看的东西）。
     * <p>会话只有<b>一份</b>摘要：每次压缩都把更早的对话并入同一份、覆盖点往后推。所以从任何一条
     * 分隔线点进来看到的都是这一份——它不是"当时那一版快照"，而是"现在生效的这份"，界面上按这个口径说。
     */
    public Map<String, Object> currentSummary(String sessionId) {
        com.wenqu.ai.model.Session session = sessionService.sessionById(sessionId);
        String summary = (session == null || session.getHistorySummary() == null) ? "" : session.getHistorySummary();
        long untilSeq = (session == null || session.getSummaryUntilSeq() == null) ? 0L : session.getSummaryUntilSeq();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("summaryTokens", TokenCounter.estimate(summary));
        out.put("untilSeq", untilSeq);
        out.put("keepTurns", HISTORY_MIN_KEEP / 2);
        return out;
    }

    /** 早期摘要注入段（与近期原样历史拼进 system 的「对话历史」段） */
    public String summaryBlock(String summary) {
        return (summary == null || summary.isBlank()) ? ""
                : "【早期对话摘要】（更早轮次已压缩，完整记录可在会话中回看）\n" + summary.trim() + "\n";
    }

    /**
     * 近期历史的短文本（非问答链路也用这一份：深度思考的对话上下文、工作流 inputs.history、
     * 无知识库分支的历史）。按字符截断 + token 总上限，保证旁路不会把整段历史塞进提示词。
     */
    public String buildHistoryText(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return "";
        int perMsgChars = 200;
        int maxTokens = 1200;
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map<String, Object> msg : history) {
            String role = String.valueOf(msg.getOrDefault("role", ""));
            String content = String.valueOf(msg.getOrDefault("content", ""));
            content = stripImageMarks(content).trim();
            if (content.isEmpty()) continue;
            if (content.length() > perMsgChars) {
                content = content.substring(0, perMsgChars) + "…";
            }
            String line = role + ": " + content + "\n";
            int tokens = TokenCounter.estimate(line);
            if (total + tokens > maxTokens) {
                break; // 超总上限，丢弃更早的历史
            }
            sb.append(line);
            total += tokens;
        }
        return sb.toString();
    }

    // ==================== 手动压缩（/compact） ====================

    /**
     * 手动压缩会话上下文（/compact 斜杠命令）。
     * <p>
     * 与自动压缩的差别：自动压缩在「摘要 + 积压 &gt; 检索预算 × compressRatio」时才动、且只压到阈值内；
     * 用户显式发起时不等阈值，也不看 {@code context.historyCompress} 开关——该开关只管"要不要自动压"，
     * 而摘要注入本身与它无关（关掉自动压缩的人照样会带摘要），所以手动压缩始终可用。
     * 压缩目标明确：除最近 {@link #HISTORY_MIN_KEEP} 条原样保留外，其余全部并入会话摘要。
     * <p>
     * 长会话分多批：每批从最旧往新取到 {@link #COMPACT_CHUNK_TOKENS} 为止，先并入旧摘要再落库
     * （下一批复用刚落库的摘要，边压边持久化——中途失败时已压部分不丢）；最多 {@link #COMPACT_MAX_ROUNDS} 批，
     * 到上限仍有积压则回传 {@code partial=true}，由前端提示可再次执行。历史读取失败/首批压缩失败会抛异常
     * （会话未被改动，fail-loud），已压了一部分之后再失败则返回已完成的部分。
     *
     * @param resolvedModel 摘要调用所用模型（调用方已解析；空值由调用方拦在前面）
     * @param instruction   用户附加要求（可空）：希望这次摘要保留什么、忽略什么
     * @return compressedTurns / compressedMessages / keepRecent / keepTurns / summaryTokens / freedTokens
     *         / summary / rounds / partial
     */
    public Map<String, Object> compactSession(String sessionId, String resolvedModel, String instruction) {
        com.wenqu.ai.model.Session session = sessionService.sessionById(sessionId);
        String summary = (session == null || session.getHistorySummary() == null) ? "" : session.getHistorySummary();
        long untilSeq = (session == null || session.getSummaryUntilSeq() == null) ? 0L : session.getSummaryUntilSeq();
        int compressedMessages = 0;
        int rounds = 0;
        boolean partial = false;
        // 「腾出多少」按进出差额计：并入摘要的原文（连同被重写的那份旧摘要）不再逐字付费，
        // 但新摘要本身要占一份——只报净收益，别拿原文规模冒充省下来的空间
        int foldedTokens = TokenCounter.estimate(summary);
        long batchTimeout = Math.max(configService.getLong("context.compressTimeoutMs", 20000L),
                COMPACT_TIMEOUT_FLOOR_MS);
        while (rounds < COMPACT_MAX_ROUNDS) {
            List<Map<String, Object>> backlog = sessionService.getHistoryAfter(sessionId, untilSeq, HISTORY_SCAN_LIMIT);
            if (backlog == null) {
                if (compressedMessages == 0) throw new com.wenqu.ai.common.BizException("会话历史读取失败，请稍后重试");
                partial = true;
                break;
            }
            int keepFrom = backlog.size() - HISTORY_MIN_KEEP;
            if (keepFrom <= 0) break;   // 只剩近期原样：压完了
            List<Map<String, Object>> older = backlog.subList(0, compactChunkSize(backlog, keepFrom));
            long newUntil = seqOf(older.get(older.size() - 1));
            // 序号没推进（消息缺 sequence）：宁可停手也不做死循环，已压的部分照常返回
            if (newUntil <= untilSeq) {
                partial = true;
                break;
            }
            String olderText = historyText(older);
            String merged;
            try {
                merged = summarizeHistory(sessionId, resolvedModel, summary, olderText, instruction, batchTimeout);
            } catch (Exception e) {
                if (compressedMessages == 0) {
                    log.warn("[CTX] 手动压缩失败（{} 条待压，会话未改动）: {}", older.size(), e.getMessage());
                    throw new com.wenqu.ai.common.BizException("压缩失败：" + compactFailReason(e) + "，会话未被改动");
                }
                log.warn("[CTX] 手动压缩中途失败（已并入 {} 条）: {}", compressedMessages, e.getMessage());
                partial = true;
                break;
            }
            if (merged == null || merged.isBlank()) {
                // 空摘要不能落库：会把旧摘要一起清掉，等于凭空丢历史。
                // 模型空回答多半是思考档吃掉了输出预算（maxTokens 计入推理 token）——留痕才查得动
                log.warn("[CTX] 手动压缩拿到空摘要（已并入 {} 条，本批 {} 条，模型 {}）",
                        compressedMessages, older.size(), resolvedModel);
                if (compressedMessages == 0) throw new com.wenqu.ai.common.BizException("模型未返回摘要内容，会话未被改动，请稍后重试");
                partial = true;
                break;
            }
            sessionService.updateHistorySummary(sessionId, merged, newUntil);
            // 只在落库成功后计收益：失败/空摘要那批没进摘要，不该算成"腾出来的"
            foldedTokens += TokenCounter.estimate(olderText);
            summary = merged;
            untilSeq = newUntil;
            compressedMessages += older.size();
            rounds++;
            log.info("[CTX] 手动压缩第 {} 批：{} 条并入摘要（until={}，摘要 {} token）",
                    rounds, older.size(), newUntil, TokenCounter.estimate(summary));
        }
        if (!partial && rounds >= COMPACT_MAX_ROUNDS) {
            // 达到批次上限：确认是真的还有积压（而非刚好压完），否则用户会白看到一句"可再次执行"
            List<Map<String, Object>> rest = sessionService.getHistoryAfter(sessionId, untilSeq, HISTORY_MIN_KEEP + 1);
            partial = rest != null && rest.size() > HISTORY_MIN_KEEP;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compressedTurns", compressedMessages <= 0 ? 0 : Math.max(1, compressedMessages / 2));
        out.put("compressedMessages", compressedMessages);
        out.put("keepRecent", HISTORY_MIN_KEEP);
        // 原样保留的问答对数：界面按「次问答」说话，别让前端拿 keepRecent 折半去猜
        out.put("keepTurns", HISTORY_MIN_KEEP / 2);
        out.put("summaryTokens", TokenCounter.estimate(summary));
        out.put("freedTokens", Math.max(0, foldedTokens - TokenCounter.estimate(summary)));
        out.put("summary", summary);
        out.put("rounds", rounds);
        out.put("partial", partial);
        return out;
    }

    /** 手动压缩的失败原因：超时的 TimeoutException 消息为 null，直接拼 getMessage() 会给用户一句空话 */
    private String compactFailReason(Exception e) {
        if (e instanceof TimeoutException) return "模型响应超时";
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }

    /** 单批压缩条数：从最旧往新装，装到 {@link #COMPACT_CHUNK_TOKENS} 为止（至少 1 条——单条自身超上限也得压走） */
    private int compactChunkSize(List<Map<String, Object>> backlog, int limit) {
        int total = 0;
        int take = 0;
        while (take < limit) {
            int t = TokenCounter.estimate(historyLine(backlog.get(take)));
            if (take > 0 && total + t > COMPACT_CHUNK_TOKENS) break;
            total += t;
            take++;
        }
        return Math.max(1, take);
    }

    // ==================== 内部：历史文本与摘要调用 ====================

    /** 一条历史消息的注入文本（role: content，剥离 [图片N] 标记；全文注入，不再按字符硬截断） */
    private String historyLine(Map<String, Object> msg) {
        String role = String.valueOf(msg.getOrDefault("role", ""));
        String content = stripImageMarks(String.valueOf(msg.getOrDefault("content", ""))).trim();
        if (content.isEmpty()) return "";
        return role + ": " + content + "\n";
    }

    private String historyText(List<Map<String, Object>> msgs) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : msgs) sb.append(historyLine(m));
        return sb.toString();
    }

    /** 从最新往回挑选能装进预算的消息，返回起始下标；至少保留最新一条（单条超预算时不再往前扩） */
    private int fitFromNewest(List<Map<String, Object>> msgs, int budgetTokens) {
        int total = 0;
        int from = msgs.size();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            int t = TokenCounter.estimate(historyLine(msgs.get(i)));
            if (total > 0 && total + t > budgetTokens) break;
            total += t;
            from = i;
        }
        return from;
    }

    /** 消息的 sequence（压缩覆盖点推进用）；缺失时回落 0（不推进，下轮重试） */
    private long seqOf(Map<String, Object> msg) {
        Object seq = msg.get("sequence");
        if (seq instanceof Number n) return n.longValue();
        try {
            return seq == null ? 0L : Long.parseLong(String.valueOf(seq));
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 剥离文本中的图片标记 [图片N] / [图片N：描述] / [图片：描述]（用于注入历史，避免编号与本轮冲突）
     */
    private String stripImageMarks(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("\\[图片\\d*(?:[：:][^\\]]*)?\\]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * 滚动历史摘要：把「已有摘要 + 本轮要压缩的更早轮次」合并成新摘要（用本轮生效模型，关思考、限输出）。
     * 摘要与会话绑定、与模型无关（切换模型可复用）；超时/失败抛异常由调用方回落。
     * 用量归属：压缩跑在专属线程池上，ThreadLocal/请求上下文都不可见——入口线程先取 uid，
     * 池内 hold 显式带上（台账按 UsageAttr 记账），finally 清理防线程复用污染。
     *
     * @param instruction 用户对本次压缩的额外要求（/compact 可带，自动压缩传 null）：想保住什么、忽略什么
     * @param timeoutOverrideMs 超时覆盖（毫秒，null=用 context.compressTimeoutMs）：手动压缩没有"卡在回答中间"
     *                          的问题，可以等更久——单批输出接近上限时耗时可到 20 秒上下，与自动路径的
     *                          紧阈值不是一回事
     */
    private String summarizeHistory(String sessionId, String resolvedModel, String oldSummary, String olderText,
                                    String instruction, Long timeoutOverrideMs) throws Exception {
        String prompt = "你是对话历史压缩器。把下面的对话记录压缩成简洁要点摘要，供后续回答继续参考：\n"
                + "1. 保留：用户的目标与诉求、已达成的结论与决定、出现的关键实体（人名/产品名/编号/数值/时间）、尚未解决的问题。\n"
                + "2. 丢弃：寒暄、重复表述、已被后续对话推翻的中间过程。\n"
                + "3. 用第三人称陈述（\"用户询问了…\"\"助手回答了…\"），按时间顺序条目化。\n"
                + "4. 只输出摘要正文，不要解释、不要加标题。\n"
                + (instruction == null || instruction.isBlank() ? ""
                        : "5. 用户对本次压缩的额外要求（优先满足，但不得与 1~4 冲突）：" + instruction.trim() + "\n")
                + "\n"
                + (oldSummary == null || oldSummary.isBlank() ? "" : "【已有摘要（更早的历史）】\n" + oldSummary + "\n\n")
                + "【本次要并入的对话】\n" + olderText;
        long timeoutMs = timeoutOverrideMs != null ? timeoutOverrideMs
                : configService.getLong("context.compressTimeoutMs", 20000L);
        // 摘要不需要推理：思考 token 与正文共享 max_tokens，默认开思考的模型会把预算全花在推理上、
        // 正文返回空（实测摘要 completion 恒等于上限而内容为空）。能关思考的方言下发关闭字段；
        // 关不掉的（恒思考 / 未识别方言）多留一份输出预算，别让摘要被推理挤没。
        Map<String, Object> thinkOff = modelRegistryService.reasoningOffBody(resolvedModel);
        int summaryMaxTokens = thinkOff.isEmpty() ? 2560 : 1024;
        String billingUid = com.wenqu.ai.util.RequestUser.uid();
        java.util.concurrent.Future<String> f = compressExecutor.submit(() -> {
            com.wenqu.ai.util.UsageAttr.hold(com.wenqu.ai.util.UsageAttr.of(
                    com.wenqu.ai.util.RequestUser.ANONYMOUS.equals(billingUid) ? null : billingUid,
                    sessionId, null, "compress"));
            try {
                OpenAiChatOptions.Builder opts = OpenAiChatOptions.builder()
                        .model(resolvedModel)
                        .temperature(0.2)
                        .maxTokens(summaryMaxTokens);
                if (!thinkOff.isEmpty()) opts.extraBody(thinkOff);
                return chatClient.prompt()
                        .system("你是对话历史压缩器，只输出要点摘要。")
                        .user(prompt)
                        .options(opts.build())
                        .call()
                        .content();
            } finally {
                com.wenqu.ai.util.UsageAttr.clear();
            }
        });
        return f.get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
