package com.wenqu.ai.agentx;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.service.ConfigService;
import com.wenqu.ai.service.ModelRegistryService;
import com.wenqu.ai.service.UsageLedgerService;
import com.wenqu.ai.service.OrgService;
import com.wenqu.ai.service.UsageQuotaService;
import com.wenqu.ai.config.ConfigDefaults;
import com.wenqu.ai.util.UsageAttr;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 一轮问答里「模型与记忆怎么配」的全部决定（从 AgentscopeChatEngine 抽出）。
 *
 * <p>原先这六件事散在引擎类的 195 行里：主链/辅助链各自的 GenerateOptions、执行配置、
 * 记忆与压缩配置、以及「这条链要不要开记忆」。它们共享同一批输入
 * （本轮命令、模型登记、全局配置、会话表），却与「一轮怎么跑」的控制流无关——
 * 放在引擎里只是因为它先被那里用到。抽出来后引擎只负责编排，
 * 模型参数怎么算、什么时候关思考、压缩阈值怎么取，全部只在这一处决定。
 *
 * <p>刻意保留成薄委托：引擎里同名的 private 方法原样留着、只转发到这里，
 * 这样原来十几处调用点一行都不用改，行为等价、便于逐步回退。
 *
 * @author yuanke
 */
@Slf4j
@RequiredArgsConstructor
public class AgentxModelConfig {

    private final ModelRegistryService modelRegistry;
    private final ConfigService configService;
    private final FrameworkSessions sessions;
    private final UsageLedgerService usageLedger;
    private final UsageQuotaService quotaService;
    private final OrgService orgService;

    /** 主链一次调用的尝试次数（含首次）：与框架 MODEL_DEFAULTS 同数，只是把静默上限收下来。 */
    private static final int MODEL_MAX_ATTEMPTS = 3;
    /** 辅助调用（记忆提炼／上下文压缩）的尝试次数：慢一次没人等，不值得再烧一整段静默。 */
    private static final int AUX_MODEL_MAX_ATTEMPTS = 2;
    /** 单次模型调用的静默上限默认值（毫秒），见 modelExecutionConfig。 */
    private static final int MODEL_STALL_TIMEOUT_MS_DEFAULT = 0;

    io.agentscope.core.model.GenerateOptions generateOptions(ChatCommand cmd, String ref, int maxOutput) {
        io.agentscope.core.model.GenerateOptions.Builder b =
                io.agentscope.core.model.GenerateOptions.builder()
                        .executionConfig(modelExecutionConfig(MODEL_MAX_ATTEMPTS));
        // 登记了最大输出才下发 max_tokens（0=未登记，交厂商默认）：不设的话单次补全上限就是
        // 厂商默认（token-plan 族为 8192），模型内联大产物必被截断成残缺参数。
        if (maxOutput > 0) b.maxTokens(maxOutput);
        String thinkLevel = cmd.getReasoningLevel() != null ? cmd.getReasoningLevel()
                : modelRegistry.defaultReasoningLevelOf(ref);
        boolean thinkingOn = cmd.isDeepThink()
                || ModelRegistryService.THINK_ALWAYS.equals(modelRegistry.referenceThinking(ref));
        if (!thinkingOn) {
            b.temperature(configService.getDouble("chat.temperature"));
            // 未勾深度思考 ≠ 一言不发：DeepSeek V4／豆包这类网关<b>默认开思考</b>的模型，
            // 不显式下发关闭字段就吃网关默认——界面上「思考强度：未开启」、实际单轮照烧
            // 几万 reasoning token。与辅助链路（auxGenerateOptions）同一条口径：
            // reasoningOffBody 只对登记为「可开关思考」的模型给关闭方言；恒思考（R1/QwQ）、
            // 未识别方言、OpenAI 系与 GLM（拒绝 disabled，400）都返回空 Map，行为与不下发一致。
            for (Map.Entry<String, Object> e : modelRegistry.reasoningOffBody(ref).entrySet()) {
                b.additionalBodyParam(e.getKey(), e.getValue());
            }
            return b.build();
        }
        if (thinkLevel == null) {
            // 与手写侧同一件事：没登记档位就没有可映射的方言字段（思考开关本身走模型的默认行为）
            return b.build();
        }
        // reasoning_effort 走原生 setter：框架把它放进 additionalBodyParams 会和内部序列化撞成重复字段
        String effort = modelRegistry.reasoningEffortValue(ref, thinkLevel, true);
        if (effort != null) b.reasoningEffort(effort);
        Map<String, Object> thinkBody = modelRegistry.reasoningExtraBody(ref, thinkLevel, true);
        for (Map.Entry<String, Object> e : thinkBody.entrySet()) {
            b.additionalBodyParam(e.getKey(), e.getValue());
        }
        if (effort != null || !thinkBody.isEmpty()) {
            log.info("[REASONING] 思考强度已下发（框架引擎）: model={} level={} effort={} extra={}",
                    ref, thinkLevel, effort, thinkBody.keySet());
        } else {
            log.warn("[FAIL-LOUD] 模型登记了思考强度档位但网关无强度方言，未下发: model={} level={}",
                    ref, thinkLevel);
        }
        return b.build();
    }

    /**
     * 辅助调用（框架的记忆提炼与上下文压缩）那一份请求参数：<b>把思考关掉</b>，其余照最小集。
     * <p>为什么要单独一份：这两类调用由框架直接拿建实例时那份模型去发（调用点传的是
     * {@code options=null}，只吃模型实例的 configuredOptions），所以「用户这一轮开了深度思考」会
     * 一并带进摘要与记忆里，凭空多出一大段 reasoning token，折完就扔。
     * 摘要要的是把话压短，不是想清楚。
     * <p>{@code reasoningOffBody} 只在模型登记为「可开关思考」时给关闭方言；<b>恒思考那一族
     * （R1／deepseek-flash 等）压根没有关的开关</b>，返回空 Map 时这里就只发最小集（不猜方言字段，
     * 发了会被网关 400 拒掉）。这一族确实省不掉，至少不再
     * 把主链的「思考强度档位」也叠上去。
     * <p>温度沿用设置页那一个值：这两类调用本来就跟着问答走，不为它们另开一档。
     * <p><b>刻意不下发登记的 max_tokens</b>（与主链 {@code generateOptions} 唯一的参数差）：
     * 摘要/压缩的输出天然短，厂商默认上限（8192 级）在这里是 runaway 护栏而非瓶颈——
     * 放大到登记值只会在恒思考族上给失控推理更大的燃烧空间，换不来任何收益。
     */
    io.agentscope.core.model.GenerateOptions auxGenerateOptions(ChatCommand cmd, String ref) {
        io.agentscope.core.model.GenerateOptions.Builder b =
                io.agentscope.core.model.GenerateOptions.builder()
                        .temperature(configService.getDouble("chat.temperature"))
                        // 辅助调用少一次重试就少挂一整段静默：主链撞 429 值得等，摘要晚一帧没人等
                        .executionConfig(modelExecutionConfig(AUX_MODEL_MAX_ATTEMPTS));
        int offApplied = 0;
        for (Map.Entry<String, Object> e : modelRegistry.reasoningOffBody(ref).entrySet()) {
            b.additionalBodyParam(e.getKey(), e.getValue());
            offApplied++;
        }
        if (offApplied == 0 && ModelRegistryService.THINK_ALWAYS.equals(modelRegistry.referenceThinking(ref))) {
            // 关不掉且这一族恒思考：说清楚它在，别让它冒充「已经省下了」
            log.info("[agentx] 模型 {} 恒思考、无关闭方言，辅助调用（记忆/压缩）仍会带 reasoning", ref);
        }
        return b.build();
    }

    /**
     * 单次模型调用的超时与重试（框架原生口子 {@code GenerateOptions.executionConfig}）。
     * <p><b>这条闸管的是「静默」不是「总时长」</b>：框架把它挂在响应流的 {@code timeout()} 上
     * （{@code ModelUtils.applyTimeoutAndRetry}），逐块复位——上游还在吐字就不算超时，停住不动才算。
     * <p>为什么要显式设：框架默认 {@code MODEL_DEFAULTS} 的静默上限是 5 分钟，而本机事件日志里
     * 26,850 个真实块间隔的最大值是 <b>0.891 秒</b>（p99=90ms，没有一段超过 30 秒）——5 分钟那道
     * 默认线保护不了任何人，只会把「这条流已经死了」拖成用户屏幕上五分钟的空转，再乘上重试次数。
     * <p>{@code mergeConfigs(这份, MODEL_DEFAULTS)} 只覆盖我点名的三项，退避曲线与重试判据仍取框架
     * 那份（{@code RETRYABLE_ERRORS}：429/5xx/超时/IO 才重试，400/401 直接失败——把 401 重试三遍
     * 只是让额度不足晚三分钟才说出口）。重试还有一条框架自带的保护：已经流出过可见内容就不重试，
     * 免得同一句话在气泡里印两遍。
     */
    io.agentscope.core.model.ExecutionConfig modelExecutionConfig(int maxAttempts) {
        io.agentscope.core.model.ExecutionConfig.Builder b =
                io.agentscope.core.model.ExecutionConfig.builder()
                        .maxAttempts(maxAttempts)
                        .retryOn(io.agentscope.core.model.ExecutionConfig.RETRYABLE_ERRORS);
        int stallMs = configService.getInt("chat.modelStallTimeoutMs", MODEL_STALL_TIMEOUT_MS_DEFAULT);
        if (stallMs > 0) b.timeout(java.time.Duration.ofMillis(stallMs));
        // 0=不限制：这一项留给框架 MODEL_DEFAULTS 那 5 分钟，主链完全不设静默闸的场景是「上游就是慢」
        return io.agentscope.core.model.ExecutionConfig.mergeConfigs(
                b.build(), io.agentscope.core.model.ExecutionConfig.MODEL_DEFAULTS);
    }

    /**
     * 记忆提炼配置：只在平台总闸与个人开关都开着时装配（见 {@code buildAgent} 的记忆闸）。
     * 任一关掉时走框架 {@code disableMemoryHooks()}：flush 与后台维护都不注册，模型侧也不会
     * 再收到「问答后自动提炼」的引导——只把 {@code FlushTrigger} 改成 never 会留下这句假承诺，
     * 后台合并也照跑。已经沉淀下来的 {@code MEMORY.md} 仍照常注入，与两处界面
     * 「仅关生成，不关注入」的说明对齐。
     *
     * <p>三处口径：① default 的每轮 ALWAYS 走可配节流（默认 10 分钟），否则每轮问答
     * 都多一次主模型提炼调用；② prompt 追加问渠规则（中文、只记持久事实、不记密钥/探针）；
     * ③ 记忆模型包 MeteredModel（kind=memory），成本进台账。{@code MEMORY.md} 的硬上限
     * 不在这里——它落在写入文件系统那一层（{@code MemoryCappedFilesystem}）。
     * <p>进来的模型是 {@code auxModel}（关思考那一份，见 {@link #auxGenerateOptions}）：提炼一段
     * 记忆要的是把话记下来，不需要它先推理两千 token。
     *
     * <p>容量上限固定 3000 token：consolidation 的提示词目标按它的 2/3
     * 推导（3000 → 2000），不单列「目标上限」——两个数要人肉保证大小关系，配反了
     * 每次合并都会被裁。日账保留期同理沿用框架默认 90 天，不单独配置。
     */
    io.agentscope.harness.agent.memory.MemoryConfig memoryConfig(
            ChatCommand cmd, io.agentscope.core.model.Model auxModel, String modelRef) {
        io.agentscope.core.model.Model memoryModel = new MeteredModel(auxModel, usageLedger, quotaService,
                com.wenqu.ai.util.UsageAttr.of(cmd.getUserId(), cmd.getSessionId(), null, "memory",
                        null, cmd.getShareId()), modelRef);
        int maxMdTokens = Math.max(500, ConfigDefaults.MEMORY_MAX_MD_TOKENS);
        int minutes = ConfigDefaults.MEMORY_FLUSH_THROTTLE_MINUTES;
        return io.agentscope.harness.agent.memory.MemoryConfig.builder()
                .model(memoryModel)
                .flushPrompt(com.wenqu.ai.memory.MemoryPrompts.flushPrompt())
                .consolidationPrompt(com.wenqu.ai.memory.MemoryPrompts.consolidationPrompt())
                // 目标值由硬上限推导（默认 3000 → 2000），留 1/3 余量；下限 500 防 0。
                .consolidationMaxTokens(Math.max(500, maxMdTokens * 2 / 3))
                .flushTrigger(minutes <= 0
                        ? io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger.always()
                        : io.agentscope.harness.agent.memory.MemoryConfig.FlushTrigger.throttled(
                                java.time.Duration.ofMinutes(minutes)))
                .build();
    }

    /** 长期记忆写入口径：个人开关（游客/子轮在建实例时另有硬闸，不走这里）。 */
    boolean memoryOn(ChatCommand cmd) {
        return orgService.memoryFlushEnabled(cmd.getUserId());
    }

    /**
     * 压缩配置。问渠只把「按消息条数触发」这一道关掉（{@code triggerMessages(0)}），其余走框架默认
     * （动态 token 阈值、逐字留最近 20 条、prune 开）——框架那条本身就是一条会跑的滚动压缩，
     * 问渠不再另算一份摘要，两边各算就是两份「已压缩上下文」互相冒充。
     * <p><b>为什么取消条数那道线</b>：它数的是整条会话累计的对话消息（一次工具调用＝ASSISTANT 调用
     * ＋TOOL 结果两条），25 次工具调用就撞满 50 条——而它和「窗口还剩多少」没有关系。窗口那道线
     * 算出来是 95.6 万 token（1M 档），条数那道线总是先把它抢掉，结果是长会话被反复折成摘要、
     * 而容量条上明明还空着。折完尾部只留 ≤8K token，条数又从个位数重新数起，等于越干越勤。
     * 真把上游窗口顶爆了有兜底：{@code CompactionMiddleware#overflowRecovery} 认 provider 的
     * context-overflow 错，当场强制折一次并重试同一轮，所以取消条数那道线不会变成「直接报错」。
     * <p>手动 {@code /compact} 在这一路会话上留过请求时，本次建实例强制「有一条就先压」，
     * 让那次点击真的在下一次提问前把旧对话折进去（框架没有「就地压一次」的入口，压缩长在推理循环前）。
     * <p>记忆关闭（平台/个人）或游客/子轮时显式 {@code flushBeforeCompact(false)}：框架的
     * {@code FlushTrigger.never()} 只管每轮收尾，压缩触发时仍会按默认 {@code true} 提炼一次
     * （{@code MemoryFlushMiddleware} 注释原文：*"The CompactionMiddleware and overflow-recovery
     * paths still run their own flush when they fire."*）。压缩前的 flush 不走 MemoryConfig，
     * 只能在这里关；溢出兜底在 CompactionMiddleware 内复制同一开关，因此一并生效。
     * <p>摘要模型包 MeteredModel（kind=compress）：压缩平时不常跑，但一跑就是一次不小的输入，
     * 不记账就永远不知道它花在哪。进来的模型是 {@code auxModel}（关思考那一份，见
     * {@link #auxGenerateOptions}）——辅助调用带上 reasoning 也是折完就扔的。
     */
    io.agentscope.harness.agent.memory.compaction.CompactionConfig compactionConfig(
            ChatCommand cmd, io.agentscope.core.model.Model auxModel, String modelRef) {
        io.agentscope.core.model.Model compressModel = new MeteredModel(auxModel, usageLedger, quotaService,
                com.wenqu.ai.util.UsageAttr.of(cmd.getUserId(), cmd.getSessionId(), null, "compress",
                        null, cmd.getShareId()),
                modelRef);
        io.agentscope.harness.agent.memory.compaction.CompactionConfig.Builder b =
                io.agentscope.harness.agent.memory.compaction.CompactionConfig.builder()
                        .model(compressModel)
                        // 0=不按条数触发（框架判据是 triggerMessages > 0 才比条数），只留窗口那道线
                        .triggerMessages(0);
        // 只关 MemoryConfig 的 per-call flush 会漏掉压缩前这一次；游客/子轮本就不写记忆，一并关。
        if (cmd.isGuestMode() || cmd.branchTurn() || !memoryOn(cmd)) {
            b.flushBeforeCompact(false);
        }
        String key = sessions.key(cmd);
        String forcedInstruction = sessions.takeCompactRequest(key);
        if (forcedInstruction == null) {
            return b.build();
        }
        // 强制压缩只应作用「下一次提问」那一个实例：实例按会话常驻，triggerMessages(1) 若不复位会被
        // 这一条会话的后续每一轮继承（每轮都压）。这里标「下次开轮重建」，让下一轮回到默认压缩配置。
        // 摘要要求走同一道生命周期：它烤进这一个实例的提示词，实例一换就回默认，不会串到之后的轮次。
        sessions.markRebuildAfterCompact(key);
        if (!forcedInstruction.isBlank()) {
            b.summaryPrompt(FrameworkSessions.summaryPromptWith(forcedInstruction));
        }
        return b.triggerMessages(1).build();
    }

}
