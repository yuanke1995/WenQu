package com.wisesoft.wenqu.repositories;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wisesoft.wenqu.common.DateTimeUtils;
import com.wisesoft.wenqu.models.AgentRunRequest;
import com.wisesoft.wenqu.repository.port.AgentRunRequestMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 智能体线程请求队列表仓储。
 *
 * <p>由参考实现的 repositories/agent_run_request_repository.py 逐方法翻译：
 * 幂等键为 request_id（与 Message、AgentRun 共用的稳定标识）、自增 id 只用于 FIFO 排序稳定性、
 * 状态流转用 {@code SELECT … FOR UPDATE} 串行化派发与取消的竞争、
 * Steer 请求优先于其余请求、队列位置用 COUNT 计算而非拉全量。
 *
 * <p>必要替换：
 * <ul>
 *   <li>排序表达式 {@code (queue_policy != "steer").asc()} → MySQL 的
 *       {@code ORDER BY (queue_policy <> 'steer') ASC}（MyBatis-Plus 的 orderBy 不支持表达式，
 *       故整段排序用原生片段）。
 *   <li>行值比较 {@code (created_at, id) < (request.created_at, request.id)} → MySQL 的行构造器
 *       {@code (created_at, id) < (?, ?)}（语义一致，含 id 作为稳定性兜底）。
 *   <li>JSON 列（origin_metadata / input_payload）写入显式序列化。
 *   <li>{@code with_for_update()} → {@code FOR UPDATE}，取锁方法加 {@code @Transactional}
 *       （自动提交下锁会立即释放）。
 * </ul>
 */
@Repository
public class AgentRunRequestRepository {

    /** Steer 队列策略：优先于其余请求。 */
    public static final String QUEUE_POLICY_STEER = "steer";

    /** 队列中待派发状态。 */
    public static final String STATUS_QUEUED = "queued";

    /** 已派发状态。 */
    public static final String STATUS_DISPATCHED = "dispatched";

    private final AgentRunRequestMapper requestMapper;

    public AgentRunRequestRepository(AgentRunRequestMapper requestMapper) {
        this.requestMapper = requestMapper;
    }

    public AgentRunRequest getByRequestId(String requestId) {
        return requestMapper.selectOne(new LambdaQueryWrapper<AgentRunRequest>().eq(AgentRunRequest::getRequestId, requestId));
    }

    /** 按 request_id 取行锁；状态分支由调用方决定。 */
    @Transactional
    public AgentRunRequest lockByRequestId(String requestId) {
        return requestMapper.selectOne(
                new LambdaQueryWrapper<AgentRunRequest>().eq(AgentRunRequest::getRequestId, requestId).last("FOR UPDATE"));
    }

    /** 登记一条请求。 */
    @Transactional
    public AgentRunRequest create(
            String requestId,
            String uid,
            String agentSlug,
            String conversationThreadId,
            String source,
            String channel,
            String externalId,
            Map<String, Object> originMetadata,
            String queuePolicy,
            Integer inputMessageId,
            Map<String, Object> inputPayload,
            String status) {
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        AgentRunRequest request = new AgentRunRequest();
        request.setRequestId(requestId);
        request.setUid(String.valueOf(uid));
        request.setAgentSlug(agentSlug);
        request.setConversationThreadId(conversationThreadId);
        request.setSource(source);
        request.setChannel(channel);
        request.setExternalId(externalId);
        request.setOriginMetadata(JSON.toJSONString(originMetadata == null ? new LinkedHashMap<>() : originMetadata));
        request.setQueuePolicy(queuePolicy);
        request.setStatus(status);
        request.setInputMessageId(inputMessageId);
        request.setInputPayload(JSON.toJSONString(inputPayload == null ? new LinkedHashMap<>() : inputPayload));
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        requestMapper.insert(request);
        return request;
    }

    /** 线程待处理请求：Steer 优先，其余保持 FIFO。 */
    private LambdaQueryWrapper<AgentRunRequest> queuedForThreadQuery(
            String uid, String agentSlug, String conversationThreadId) {
        return new LambdaQueryWrapper<AgentRunRequest>()
                .eq(AgentRunRequest::getUid, String.valueOf(uid))
                .eq(AgentRunRequest::getAgentSlug, agentSlug)
                .eq(AgentRunRequest::getConversationThreadId, conversationThreadId)
                .eq(AgentRunRequest::getStatus, STATUS_QUEUED)
                .last("ORDER BY (queue_policy <> 'steer') ASC, created_at ASC, id ASC");
    }

    /** 读取线程内尚未派发的 Steer 请求。 */
    public AgentRunRequest getPendingSteer(String uid, String agentSlug, String conversationThreadId) {
        return requestMapper.selectOne(
                new LambdaQueryWrapper<AgentRunRequest>()
                        .eq(AgentRunRequest::getUid, String.valueOf(uid))
                        .eq(AgentRunRequest::getAgentSlug, agentSlug)
                        .eq(AgentRunRequest::getConversationThreadId, conversationThreadId)
                        .eq(AgentRunRequest::getQueuePolicy, QUEUE_POLICY_STEER)
                        .eq(AgentRunRequest::getStatus, STATUS_QUEUED));
    }

    /** 原子读取并锁定 FIFO 队首（queued）。 */
    @Transactional
    public AgentRunRequest getQueueHead(String uid, String agentSlug, String conversationThreadId) {
        LambdaQueryWrapper<AgentRunRequest> wrapper = queuedForThreadQuery(uid, agentSlug, conversationThreadId);
        wrapper.last("ORDER BY (queue_policy <> 'steer') ASC, created_at ASC, id ASC LIMIT 1 FOR UPDATE");
        return requestMapper.selectOne(wrapper);
    }

    /** 线程内全部待处理请求（Steer 优先，其余 FIFO）。 */
    public List<AgentRunRequest> listQueued(String uid, String agentSlug, String conversationThreadId) {
        return requestMapper.selectList(queuedForThreadQuery(uid, agentSlug, conversationThreadId));
    }

    /** 给定已加载的请求对象，返回 1-based FIFO 位置；不在 queued 队列返回 0。 */
    public int getQueuePositionFor(AgentRunRequest request) {
        if (!STATUS_QUEUED.equals(request.getStatus())) {
            return 0;
        }
        if (QUEUE_POLICY_STEER.equals(request.getQueuePolicy())) {
            return 1;
        }
        LambdaQueryWrapper<AgentRunRequest> wrapper =
                new LambdaQueryWrapper<AgentRunRequest>()
                        .eq(AgentRunRequest::getUid, request.getUid())
                        .eq(AgentRunRequest::getAgentSlug, request.getAgentSlug())
                        .eq(AgentRunRequest::getConversationThreadId, request.getConversationThreadId())
                        .eq(AgentRunRequest::getStatus, STATUS_QUEUED)
                        .and(
                                w ->
                                        w.eq(AgentRunRequest::getQueuePolicy, QUEUE_POLICY_STEER)
                                                .or(
                                                        inner ->
                                                                inner.ne(AgentRunRequest::getQueuePolicy, QUEUE_POLICY_STEER)
                                                                        .apply(
                                                                                "(created_at, id) < ({0}, {1})",
                                                                                request.getCreatedAt(),
                                                                                request.getId())));
        Long count = requestMapper.selectCount(wrapper);
        return (count == null ? 0 : count.intValue()) + 1;
    }

    /** 1-based FIFO 位置；请求不在 queued 队列返回 0。 */
    public int getQueuePosition(String requestId) {
        AgentRunRequest request = getByRequestId(requestId);
        if (request == null) {
            return 0;
        }
        return getQueuePositionFor(request);
    }

    /** 标记请求已派发（仅 queued 可转）。 */
    @Transactional
    public AgentRunRequest markDispatched(String requestId, String runId) {
        AgentRunRequest request = lockByRequestId(requestId);
        if (request == null || !STATUS_QUEUED.equals(request.getStatus())) {
            return null;
        }
        LocalDateTime now = DateTimeUtils.utcNowNaive();
        requestMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRunRequest>()
                        .eq(AgentRunRequest::getId, request.getId())
                        .set(AgentRunRequest::getStatus, STATUS_DISPATCHED)
                        .set(AgentRunRequest::getDispatchedRunId, runId)
                        .set(AgentRunRequest::getDispatchedAt, now)
                        .set(AgentRunRequest::getUpdatedAt, now));
        request.setStatus(STATUS_DISPATCHED);
        request.setDispatchedRunId(runId);
        request.setDispatchedAt(now);
        request.setUpdatedAt(now);
        return request;
    }
}
