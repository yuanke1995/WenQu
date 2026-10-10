package com.wenqu.ai.agent.tool;

/**
 * 人在回路审批通道（问人，不是平台裁决）。
 * <p>
 * 与 {@link ToolGuard} 分开的理由：守卫是平台自己的不可协商闸，审批是要等一个活人回话的阻塞动作，
 * 它有超时、有通知、有落库审计、还有「根本没人能答」这种状态。把四种结局塞进一个 boolean
 * （迁移前就是 {@code CompletableFuture<Boolean>}）会丢掉可观测性：用户看不到「超时」与「拒绝」
 * 的区别，运维也分不清是审批卡没人点还是审批被否。
 *
 * @author yuanke
 */
public interface ToolApprover {

    /**
     * 请用户批准一次调用（阻塞到有裁决、超时、或本轮被叫停）。
     *
     * @param timeoutMs 等待上限；超时按拒绝处理（不代用户批准）
     */
    Outcome approve(ToolInvocation inv, long timeoutMs);

    /** 审批结局（对齐业界 runtime 的通行划分：allowed-once / rejected / cancelled / timeout / unavailable） */
    enum Outcome {
        /** 用户批准，仅对本次调用有效 */
        ALLOWED,
        /** 用户明确拒绝 */
        REJECTED,
        /** 等待超时（默认按拒绝处理，不代答） */
        TIMED_OUT,
        /** 本轮已被用户叫停，等待中止 */
        CANCELLED,
        /** 压根没有可应答的人（工作流后台节点、定时任务、无 SSE 通道） */
        UNAVAILABLE
    }
}
