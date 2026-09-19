package com.wisesoft.wenqu.agents.callbacks;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.wisesoft.wenqu.agents.BaseContext;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.repositories.AgentRunRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过 Agent 模型调用拦截器记录首次模型请求时间。
 *
 * <p>对应参考实现 {@code agents/callbacks/model_request_timing.py} 的
 * {@code FirstModelRequestRecorder}（LangChain {@code BaseCallbackHandler}）。
 *
 * <p>参考实现在 LangChain 的 {@code on_chat_model_start} 回调中捕获首次时间，
 * 再由 Run owner 调用 {@code persist(run_id, worker_id)} 持久化。Java 侧没有
 * LangChain 回调体系，改用 {@link ModelInterceptor}：首次 {@link #interceptModel}
 * 即对应"首次 ChatModel 调用前"，在同一拦截点捕获时间并写库（write-once）。
 *
 * <h3>能力差异（显式标注，非遗漏）</h3>
 * <ol>
 *   <li><b>捕获与持久化合二为一</b>：参考实现把捕获（回调内）与持久化（worker 调
 *       {@code persist}）拆开；本类在首次拦截时原子完成两者。仓储
 *       {@link AgentRunRepository#recordFirstModelRequest} 自带 write-once 守卫
 *       （已写入则 no-op），且要求 Run 仍由当前 worker 持有效 lease —— 与参考实现
 *       "由 Run owner 持久化"的约束一致。</li>
 *   <li><b>状态存于 per-run 上下文而非实例字段</b>：参考实现的 recorder 是每 run 一个新
 *       实例（实例字段存首次时间）。本工程中间件按既有约定为单例安全（无 per-run 可变
 *       实例字段），故把已捕获标志存进 {@link BaseContext} 的动态字段
 *       {@link #FIRST_MODEL_REQUEST_AT_KEY}，随 run 天然隔离，避免单例串扰。</li>
 *   <li><b>异常不阻断模型调用</b>：参考实现 {@code persist} 捕获所有异常仅 warning；
 *       本类同样用 try/catch 包裹写库，失败只记日志，绝不影响 {@code handler.call} 继续。</li>
 * </ol>
 */
public class ModelRequestTimingMiddleware extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ModelRequestTimingMiddleware.class);

    /** runtime context 在本工程里由构图方以该键放进 {@link ModelRequest#getContext()}，
     *  与 {@code ContextAwareInterceptor#CONTEXT_KEY} 一致。 */
    public static final String CONTEXT_KEY = "context";

    /** 当前 run 已捕获首次模型请求时间的动态字段键（隐藏、per-run 隔离）。 */
    public static final String FIRST_MODEL_REQUEST_AT_KEY = "_first_model_request_at";

    private final AgentRunRepository agentRunRepository;

    public ModelRequestTimingMiddleware(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    @Override
    public String getName() {
        return "model_request_timing";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        BaseContext context = contextOf(request);
        if (context != null) {
            recordIfFirst(context);
        }
        return handler.call(request);
    }

    /**
     * 在首次 ChatModel 调用前记录时间并持久化（对应参考实现的
     * {@code on_chat_model_start} + {@code persist}）。
     *
     * <p>已为当前 run 捕获过（动态字段非空）则直接跳过，避免对每条消息重复写库。
     */
    private void recordIfFirst(BaseContext context) {
        if (context.getDynamic(FIRST_MODEL_REQUEST_AT_KEY, null) != null) {
            return;
        }
        Object runIdRaw = context.get("run_id");
        Object workerIdRaw = context.get("worker_id");
        String runId = runIdRaw == null ? "" : String.valueOf(runIdRaw);
        String workerId = workerIdRaw == null ? "" : String.valueOf(workerIdRaw);
        if (runId.isEmpty() || workerId.isEmpty() || agentRunRepository == null) {
            return;
        }
        LocalDateTime observedAt = DateTimeUtils.utcNowNaive();
        // 先落动态字段，保证后续拦截跳过（即便下方写库抛异常也不再重试，与参考单次 persist 一致）。
        context.setDynamic(FIRST_MODEL_REQUEST_AT_KEY, observedAt);
        try {
            agentRunRepository.recordFirstModelRequest(runId, workerId, observedAt, null);
        } catch (Exception e) {
            log.warn("Failed to persist first model request timing: run={}", runId, e);
        }
    }

    /** 取 {@link ModelRequest#getContext()} 里的运行时上下文。 */
    private static BaseContext contextOf(ModelRequest request) {
        Map<String, Object> raw = request == null ? null : request.getContext();
        Object value = raw == null ? null : raw.get(CONTEXT_KEY);
        return value instanceof BaseContext context ? context : null;
    }
}
