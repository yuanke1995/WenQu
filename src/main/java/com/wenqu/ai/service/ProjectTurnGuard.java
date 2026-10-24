package com.wenqu.ai.service;

/**
 * 工作区轮次执行守卫：同一共用目录的工作区同时只允许一轮在跑（对齐 AgentScope Harness
 * {@code SandboxExecutionGuard} 的职责——串行化同一沙盒槽位的命令执行，并让锁携带身份）。
 *
 * <p>取代旧 {@code ProjectTurnMutex} 的匿名 0/1 计数：租约记下占用者（{@link TurnLease}），
 * 被拒者能收到「谁在跑」，查询端点能把占用态摆上工作区页。并发模型本身不变——仍是
 * 「快速失败不排队」的同工作区单轮语义。
 *
 * <p><b>两个实现</b>：{@link JvmProjectTurnGuard}（默认，单实例）与 {@link RedisProjectTurnGuard}
 * （{@code project.turnGuard.store=redis} 时启用，多副本前置——前提是所有副本共享同一份工作目录
 * 存储，见该类注释）。切换是配置键的事，接口两侧语义一致。
 */
public interface ProjectTurnGuard {

    /**
     * 尝试占用一个工作区。
     *
     * @return true=占到了（调用方必须在轮次结束时 {@link #end}）；false=该工作区已有未过期租约
     */
    boolean tryBegin(String projectId, TurnLease lease);

    /** 续约：仅当占用者仍是 {@code turnId} 本轮才延长；多副本实现走 Redis/JDBC 原子操作 */
    void renew(String projectId, String turnId, long ttlMs);

    /** 释放（幂等：仅当占用者仍是 {@code turnId} 本轮才清，迟到的释放不会误伤后来者的租约） */
    void end(String projectId, String turnId);

    /**
     * 当前占用者；空闲或租约已过期（持有方没来得及释放——轮次线程卡死/进程残留）返回 {@code null}。
     * 过期租约在读路径顺手清掉，这是租约制对旧计数制的关键升级：卡死的轮不再把工作区锁到重启。
     */
    TurnLease current(String projectId);

    /** 是否有未过期租约（文件服务挡改名/删除与挡覆盖上传共用这一判据） */
    default boolean isBusy(String projectId) {
        return projectId != null && !projectId.isBlank() && current(projectId) != null;
    }
}
