package com.wisesoft.wenqu.service;

import com.wisesoft.wenqu.agents.AgentCompositeBackend;
import com.wisesoft.wenqu.agents.AgentContextService;
import com.wisesoft.wenqu.agents.AgentManager;
import com.wisesoft.wenqu.agents.BaseAgent;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.agents.BaseAgent.AgentsGraphPort;
import com.wisesoft.wenqu.agents.BackendPaths;
import com.wisesoft.wenqu.agents.GraphStateSnapshot;
import com.wisesoft.wenqu.agents.SkillService;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxBackend;
import com.wisesoft.wenqu.agents.backends.sandbox.ProvisionerSandboxProvider;
import com.wisesoft.wenqu.agents.middlewares.SummaryMiddleware;
import com.wisesoft.wenqu.agents.middlewares.TokenUsageMiddleware;
import com.wisesoft.wenqu.common.ApiHttpException;
import com.wisesoft.wenqu.models.Agent;
import com.wisesoft.wenqu.models.AgentRun;
import com.wisesoft.wenqu.models.Conversation;
import com.wisesoft.wenqu.models.User;
import com.wisesoft.wenqu.permissions.PermissionSubject;
import com.wisesoft.wenqu.repositories.AgentRepository;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import com.wisesoft.wenqu.repositories.AgentRunRequestRepository;
import com.wisesoft.wenqu.repositories.AgentStateRepository;
import com.wisesoft.wenqu.repositories.ConversationRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 线程级主动上下文压缩用例（对应参考实现 {@code services/context_compression_service.py}）。
 *
 * <p>在<b>线程空闲</b>时把该线程的 checkpoint 压缩一次：取当前 agent state → 生成摘要 →
 * 把摘要事件与新的上下文压力指标写回 checkpoint。同线程的新请求由 {@code conversations}
 * 行的排他锁串行化（{@link ConversationRepository#lockConversationByThreadId}）。
 *
 * <h3>逐块对位</h3>
 * <ul>
 *   <li>{@link #compressThreadContext} ← {@code compress_thread_context}</li>
 *   <li>{@link #ensureThreadIdle} ← {@code _ensure_thread_idle}</li>
 *   <li>{@link #compressAgentCheckpointInRuntime} ← {@code _compress_agent_checkpoint_in_runtime}</li>
 *   <li>{@link #ensureRuntimeAvailable} ← {@code _ensure_runtime_available}</li>
 *   <li>{@link #releaseRuntime} ← {@code _release_runtime}</li>
 *   <li>{@link #compressAgentCheckpoint} ← {@code _compress_agent_checkpoint}</li>
 *   <li>{@link #withCompressionUsage} ← {@code _with_compression_usage}</li>
 * </ul>
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ol>
 *   <li><b>摘要执行能力未装配</b>：参考实现 {@code compressor.aforce_summarize(values)} 的方法骨架
 *       全部位于 langchain {@code SummarizationMiddleware} 基类（{@code _apply_event_to_messages} /
 *       {@code _determine_cutoff_index} / {@code _partition_messages} / {@code _aoffload_inline_media}
 *       / {@code _get_session_id} / {@code _build_new_messages_with_path} / {@code _count_tokens}），
 *       本工程未照搬该基类；引擎面自带的 {@code SummarizationHook} 语义不等价（无「历史落盘 +
 *       可恢复」机制，cutoff 算法亦不同），不能替代。故此处收敛为 {@link CheckpointCompressor}
 *       端口，<b>未装配时抛出异常，绝不静默返回空 update</b>（那会让调用方误以为「无需压缩」）。</li>
 *   <li><b>checkpoint 写入能力未装配</b>：参考实现经 {@code CompiledStateGraph.aupdate_state} 写回；
 *       本工程 {@link AgentsGraphPort} 当前只提供 {@code agetState}，无对应写入口。故收敛为
 *       {@link CheckpointStateWriter} 端口，未装配时抛出异常。</li>
 *   <li><b>无显式 DB 会话 / 无 {@code db.commit()} 调用点</b>：参考实现接收 {@code AsyncSession}
 *       并在末尾 {@code await db.commit()}；本工程各 repository 自管事务，服务层用
 *       {@link TransactionTemplate} 圈定「加锁 → 校验 → 压缩 → 提交」的边界（{@code PROPAGATION_REQUIRED}，
 *       与 {@code ChatService} 同口径），因此本类没有 commit 调用点（等价）。</li>
 *   <li><b>{@code asyncio.to_thread} 无 Java 对位</b>：参考实现把阻塞 I/O 交给线程池；Java 侧这些
 *       调用本身就是同步阻塞，直接调用（等价，不引入线程池语义）。</li>
 *   <li><b>摘要阈值取值更宽容</b>：参考实现 {@code getattr(context, "summary_threshold", DEFAULT)}
 *       在「属性存在但值为 {@code None}」时会 {@code None * 1024} 抛 {@code TypeError}；
 *       本工程对非数值一律回落 {@link BaseContext#DEFAULT_SUMMARY_THRESHOLD_K}（避免脏配置炸链路）。</li>
 *   <li><b>摘要结果为空时的返回面</b>：参考实现 {@code _with_compression_usage} 里 {@code dict(result)}
 *       要求 {@code result} 恒为 dict；本工程保持同样的前提（端口返回 null 时不写 checkpoint，
 *       见 {@link #compressAgentCheckpoint} 的 {@code update} 空值判断）。</li>
 * </ol>
 */
@Slf4j
@Service
public class ContextCompressionService {

    /** checkpoint 读取用的 config 键（与 {@code ChatService.readCheckpointState} 同形）。 */
    private static final String CHECKPOINT_NS_KEY = "checkpoint_ns";

    /** 主动压缩能力标识（对应参考实现的 capabilities 取值）。 */
    private static final String CONTEXT_COMPRESSION_CAPABILITY = "context_compression";

    /** 会话 / 运行的可见性归属键（对应参考实现 {@code kind="main"}）。 */
    private static final String THREAD_BUSY_CODE = "thread_busy";

    private final ConversationRepository conversationRepository;
    private final AgentRepository agentRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentRunRequestRepository agentRunRequestRepository;
    private final AgentManager agentManager;
    private final AgentRunService agentRunService;
    private final WorkdirService workdirService;
    private final AgentContextService agentContextService;
    private final AgentCompositeBackend agentCompositeBackend;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<CheckpointCompressor> checkpointCompressor;
    private final ObjectProvider<CheckpointStateWriter> checkpointStateWriter;

    public ContextCompressionService(
            ConversationRepository conversationRepository,
            AgentRepository agentRepository,
            AgentRunRepository agentRunRepository,
            AgentRunRequestRepository agentRunRequestRepository,
            AgentManager agentManager,
            AgentRunService agentRunService,
            WorkdirService workdirService,
            AgentContextService agentContextService,
            AgentCompositeBackend agentCompositeBackend,
            PlatformTransactionManager transactionManager,
            ObjectProvider<CheckpointCompressor> checkpointCompressor,
            ObjectProvider<CheckpointStateWriter> checkpointStateWriter) {
        this.conversationRepository = conversationRepository;
        this.agentRepository = agentRepository;
        this.agentRunRepository = agentRunRepository;
        this.agentRunRequestRepository = agentRunRequestRepository;
        this.agentManager = agentManager;
        this.agentRunService = agentRunService;
        this.workdirService = workdirService;
        this.agentContextService = agentContextService;
        this.agentCompositeBackend = agentCompositeBackend;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.checkpointCompressor = checkpointCompressor;
        this.checkpointStateWriter = checkpointStateWriter;
    }

    // =========================================================================
    // === 端口（能力缺口载体，见类注释能力差异 1 / 2） ===
    // =========================================================================

    /**
     * 主动压缩执行端口（对应参考实现
     * {@code create_summary_middleware_from_context(...)} + {@code compressor.aforce_summarize(values)}）。
     *
     * <p>参考实现把两步都压在 {@code SummaryMiddleware}（langchain 基类）上；本工程该基类的方法骨架
     * 未照搬（见类注释能力差异 1），故把「执行」抽为端口：{@code compressor} 是本类按 context 构建出的
     * 中间件实例（构建面照搬），端口负责在其上完成 {@code aforce_summarize}。
     */
    @FunctionalInterface
    public interface CheckpointCompressor {

        /**
         * @return {@code (update, result)}：{@code update} 为要写入 checkpoint 的增量（空则跳过写入）；
         *     {@code result} 为对外返回的压缩结果
         */
        CompressionOutcome forceSummarize(
                SummaryMiddleware compressor, BaseContext context, Map<String, Object> values);
    }

    /** {@link CheckpointCompressor} 的返回体（对应参考实现 {@code update, result} 二元组）。 */
    public record CompressionOutcome(Map<String, Object> update, Map<String, Object> result) {
    }

    /**
     * checkpoint 写入端口（对应 {@code CompiledStateGraph.aupdate_state}）。
     *
     * <p>本工程 {@link AgentsGraphPort} 只提供 {@code agetState}（见类注释能力差异 2）。
     */
    @FunctionalInterface
    public interface CheckpointStateWriter {

        /** 通过 canonical graph 追加一个 state checkpoint。 */
        void updateState(String uid, String threadId, Map<String, Object> values);
    }

    // =========================================================================
    // === 用例入口 ===
    // =========================================================================

    /**
     * 在线程空闲时压缩 checkpoint（对应 {@code compress_thread_context}）。
     *
     * <p>同线程新请求由 {@code conversations} 行的排他锁串行化。
     *
     * @throws ApiHttpException 404 线程不存在 / 智能体不存在 / 智能体后端不存在；409 线程忙；422 后端无该能力
     */
    public Map<String, Object> compressThreadContext(String threadId, User currentUser) {
        String uid = String.valueOf(currentUser.getUid());
        return transactionTemplate.execute(status -> {
            Conversation conversation = conversationRepository.lockConversationByThreadId(threadId);
            if (conversation == null
                    || !uid.equals(conversation.getUid())
                    || "deleted".equals(conversation.getStatus())) {
                throw ApiHttpException.notFound("对话线程不存在");
            }

            String agentSlug = conversation.getAgentId();
            ensureThreadIdle(uid, agentSlug, threadId);

            Agent agentItem = agentRepository.getVisibleBySlug(
                    agentSlug, PermissionSubject.of(currentUser), AgentRepository.AgentEntryKind.MAIN);
            if (agentItem == null) {
                throw ApiHttpException.notFound("智能体不存在");
            }
            BaseAgent agent = agentManager.getAgent(agentItem.getBackendId());
            if (agent == null) {
                throw ApiHttpException.notFound("智能体后端不存在");
            }
            if (!agent.getCapabilities().contains(CONTEXT_COMPRESSION_CAPABILITY)) {
                throw ApiHttpException.unprocessable("当前智能体不支持主动上下文压缩");
            }

            BaseContext context = agentRunService.loadAgentRunContext(agentItem, agent);
            Map<String, Object> extraMetadata = AgentRunService.parseJsonObject(conversation.getExtraMetadata());
            Object requestedModel = extraMetadata == null ? null : extraMetadata.get("model_spec");
            String modelSpec = agentRunService.resolveAgentRunModelSpec(
                    requestedModel == null ? null : String.valueOf(requestedModel),
                    context.getString("model"));
            String workdirPath = workdirService.ensureConversationWorkdirAvailable(conversation, uid, null);
            context.update(Map.of(
                    "uid", uid,
                    "thread_id", threadId,
                    "model", modelSpec,
                    "runtime_scope_id", threadId,
                    "workdir_relative_path", workdirPath,
                    "workdir_path", BackendPaths.runtimeWorkdirPath(workdirPath)));

            return compressAgentCheckpointInRuntime(agent, context, threadId, uid, workdirPath);
        });
    }

    // =========================================================================
    // === 空闲校验 ===
    // =========================================================================

    /**
     * 拒绝会与 checkpoint 维护竞争的运行、等待交互和排队请求（对应 {@code _ensure_thread_idle}）。
     *
     * @throws ApiHttpException 409，detail 为 {@code {"code": "thread_busy", "message": ...}}
     */
    private void ensureThreadIdle(String uid, String agentSlug, String threadId) {
        AgentRun activeRun = agentRunRepository.getActiveRunByThreadForUser(agentSlug, threadId, uid);
        AgentRun latestRun = agentRunRepository.getLatestChatOrResumeRun(uid, agentSlug, threadId);
        List<?> queuedRequests = agentRunRequestRepository.listQueued(uid, agentSlug, threadId);
        if (activeRun == null
                && (queuedRequests == null || queuedRequests.isEmpty())
                && (latestRun == null || !"interrupted".equals(latestRun.getStatus()))) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", THREAD_BUSY_CODE);
        detail.put("message", "线程仍有运行、交互或排队请求，暂时不能压缩");
        throw ApiHttpException.conflictWithDetail(detail);
    }

    // =========================================================================
    // === 运行时生命周期 ===
    // =========================================================================

    /** 在一次性 Sandbox 生命周期内压缩 checkpoint（对应 {@code _compress_agent_checkpoint_in_runtime}）。 */
    private Map<String, Object> compressAgentCheckpointInRuntime(
            BaseAgent agent, BaseContext context, String threadId, String uid, String workdirPath) {
        Map<String, Object> result;
        try {
            agentContextService.prepareAgentRuntimeContext(context);
            ensureRuntimeAvailable(threadId, uid, workdirPath);
            result = compressAgentCheckpoint(agent, context);
        } catch (RuntimeException | Error exc) {
            // 对应参考实现的 except BaseException 分支：释放失败只记录，原异常优先。
            try {
                releaseRuntime(threadId, uid, workdirPath);
            } catch (RuntimeException | Error releaseError) {
                log.error("主动压缩失败后释放 Sandbox 失败: {}", releaseError.getMessage(), releaseError);
            }
            throw exc;
        }
        releaseRuntime(threadId, uid, workdirPath);
        return result;
    }

    /**
     * 确保主动压缩可以通过 Agent backend 写入可恢复历史（对应 {@code _ensure_runtime_available}）。
     *
     * <p>参考实现两侧 I/O 都走 {@code asyncio.to_thread}（见类注释能力差异 4）。
     */
    private void ensureRuntimeAvailable(String threadId, String uid, String workdirPath) {
        SkillService.getUserSkillsRootDir(uid);
        ProvisionerSandboxBackend backend = new ProvisionerSandboxBackend(
                ProvisionerSandboxProvider.getSandboxProvider(), threadId, uid, workdirPath, true, true);
        backend.ensureAvailable();
    }

    /** 释放主动压缩创建或复用的 Sandbox（对应 {@code _release_runtime}）。 */
    private void releaseRuntime(String threadId, String uid, String workdirPath) {
        ProvisionerSandboxProvider.getSandboxProvider()
                .release(threadId, uid, true, workdirPath);
    }

    // =========================================================================
    // === checkpoint 压缩内核 ===
    // =========================================================================

    /** 使用当前 Agent 配置生成摘要并通过 canonical graph 更新 checkpoint（对应 {@code _compress_agent_checkpoint}）。 */
    private Map<String, Object> compressAgentCheckpoint(BaseAgent agent, BaseContext context) {
        AgentsGraphPort graph = agent.getGraph(context);
        // 对应参考实现的 create_summary_middleware_from_context(context, backend=create_agent_composite_backend(context))：
        // 构建面照搬（工具结果落盘端口取本 Run 的沙盒 composite backend），执行面见 CheckpointCompressor。
        SummaryMiddleware compressor = SummaryMiddleware.createFromContext(
                context,
                summaryToolResultBackend(agentCompositeBackend.createAgentCompositeBackend(context)),
                null);
        String uid = String.valueOf(context.get("uid"));
        String threadId = String.valueOf(context.get("thread_id"));
        AgentStateRepository stateRepository =
                new AgentStateRepository(stateGraphPort(graph), uid, threadId);
        Map<String, Object> values = stateRepository.getValues();
        CompressionOutcome outcome = forceSummarize(compressor, context, values);
        Map<String, Object> update = outcome.update();
        Map<String, Object> result = outcome.result();
        if (update != null && !update.isEmpty()) {
            int triggerTokens = summaryThreshold(context) * 1024;
            stateRepository.update(withCompressionUsage(update, values, result, triggerTokens));
        }
        return result;
    }

    /**
     * 把已构建的中间件交给端口执行 {@code aforce_summarize}（见类注释能力差异 1）。
     *
     * @throws IllegalStateException 端口未装配（绝不静默返回空结果）
     */
    private CompressionOutcome forceSummarize(
            SummaryMiddleware compressor, BaseContext context, Map<String, Object> values) {
        CheckpointCompressor executor = checkpointCompressor.getIfAvailable();
        if (executor == null) {
            throw new IllegalStateException(
                    "主动上下文压缩的摘要执行能力未装配（aforce_summarize 骨架位于未照搬的 "
                            + "SummarizationMiddleware 基类）——请装配 CheckpointCompressor 端口");
        }
        return executor.forceSummarize(compressor, context, values);
    }

    /**
     * 把主动压缩结果合并进下一轮上下文压力指标（对应 {@code _with_compression_usage}）。
     *
     * <p>剔除上一轮快照里的「上下文估算字段」（{@link TokenUsageMiddleware#TOKEN_USAGE_CONTEXT_FIELDS}），
     * 保留桶结构（如 {@code thread/total}），再写入本轮压缩结果与新的阈值压力。
     */
    static Map<String, Object> withCompressionUsage(
            Map<String, Object> update,
            Map<String, Object> previousValues,
            Map<String, Object> result,
            int summaryTriggerTokens) {
        Object previousUsage = previousValues == null ? null : previousValues.get("token_usage");
        Map<String, Object> tokenUsage = new LinkedHashMap<>();
        if (previousUsage instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!TokenUsageMiddleware.TOKEN_USAGE_CONTEXT_FIELDS.contains(key)) {
                    tokenUsage.put(key, entry.getValue());
                }
            }
        }
        tokenUsage.put("compression", new LinkedHashMap<>(result));
        tokenUsage.put("summary_active", true);
        tokenUsage.put("summary_trigger_tokens", summaryTriggerTokens);
        Map<String, Object> merged = new LinkedHashMap<>(update);
        merged.put("token_usage", tokenUsage);
        return merged;
    }

    // =========================================================================
    // === 适配与取值工具 ===
    // =========================================================================

    /**
     * 把编译图端口适配为 {@link AgentStateRepository.StateGraphPort}。
     *
     * <p>读取面：走 {@link AgentsGraphPort#agetState}（与 {@code ChatService.readCheckpointState} 同形）；
     * 写入面：委托 {@link CheckpointStateWriter} 端口（见类注释能力差异 2）。
     */
    private AgentStateRepository.StateGraphPort stateGraphPort(AgentsGraphPort graph) {
        return new AgentStateRepository.StateGraphPort() {
            @Override
            public boolean hasCheckpointer() {
                return graph.hasCheckpointer();
            }

            @Override
            public Map<String, Object> getState(String uid, String threadId) {
                GraphStateSnapshot snapshot = graph.agetState(checkpointConfig(uid, threadId));
                return snapshot == null ? null : snapshot.values();
            }

            @Override
            public void updateState(String uid, String threadId, Map<String, Object> values) {
                CheckpointStateWriter writer = checkpointStateWriter.getIfAvailable();
                if (writer == null) {
                    throw new IllegalStateException(
                            "checkpoint 写入能力未装配（本工程编译图端口无 aupdate_state 对位）"
                                    + "——请装配 CheckpointStateWriter 端口");
                }
                writer.updateState(uid, threadId, values);
            }
        };
    }

    /** checkpoint 读取 config（与 {@code ChatService.readCheckpointState} 的 config 同形）。 */
    private static Map<String, Object> checkpointConfig(String uid, String threadId) {
        Map<String, Object> configurable = new LinkedHashMap<>();
        configurable.put("uid", uid);
        configurable.put("thread_id", threadId);
        configurable.put(CHECKPOINT_NS_KEY, "");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("configurable", configurable);
        return config;
    }

    /**
     * 摘要触发阈值（token）。
     *
     * @see ContextCompressionService 能力差异 5
     */
    private static int summaryThreshold(BaseContext context) {
        Object raw = context == null ? null : context.get("summary_threshold");
        return raw instanceof Number number ? number.intValue() : BaseContext.DEFAULT_SUMMARY_THRESHOLD_K;
    }

    /** 工具结果落盘端口：把 {@code backend.write} 的 error 文本原样上报（对应 {@code backend.write} 返回面）。 */
    private static SummaryMiddleware.ToolResultBackend summaryToolResultBackend(ProvisionerSandboxBackend backend) {
        return (path, content) -> backend.write(path, content).error;
    }
}
