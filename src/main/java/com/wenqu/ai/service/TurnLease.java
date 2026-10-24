package com.wenqu.ai.service;

/**
 * 工作区轮次租约的<b>占用者身份</b>（对齐 AgentScope Harness {@code SandboxExecutionGuard} 的
 * 租约式写隔离口径：锁不只是「有没有人」，还必须说得出「是谁、从何时起、到何时止」）。
 *
 * <p>这里的身份只服务两件事：被拒者收到的报错能<b>指名道姓</b>（「定时任务『周报』正在跑」，
 * 不再是含糊的「另一条对话、定时任务或工作流」），以及查询端点能把占用态摆到工作区页上。
 *
 * @param turnId    持锁轮次（守卫释放按它比对，防止误释放后来者的租约）
 * @param sessionId 持锁会话（前端「跳过去看」用）
 * @param kind      发起方：{@link #KIND_USER} / {@link #KIND_SCHEDULED} / {@link #KIND_WORKFLOW}
 * @param name      展示名：会话标题 / 定时任务名 / 工作流子智能体名
 * @param uid       发起者
 * @param startedAt 占用开始时刻（epoch ms）
 * @param deadline  租约到期（epoch ms）：到期未被释放/续约即视为持有方已死，读路径顺手清掉
 */
public record TurnLease(String turnId, String sessionId, String kind, String name, String uid,
                        long startedAt, long deadline) {

    public static final String KIND_USER = "user";
    public static final String KIND_SCHEDULED = "scheduled";
    public static final String KIND_WORKFLOW = "workflow";

    /** 租约是否已过期末（读路径据此把死租约清掉，等价于框架 leaseTtl 的自愈语义） */
    public boolean expired() {
        return System.currentTimeMillis() > deadline;
    }

    /** 续约到新期限（其余身份字段不变） */
    public TurnLease renewed(long newDeadline) {
        return new TurnLease(turnId, sessionId, kind, name, uid, startedAt, newDeadline);
    }

    /** 「谁在跑」的一句话：报错文案与查询端点共用同一出口，两处口径不漂移 */
    public String describe() {
        String n = name == null || name.isBlank() ? "" : "「" + name + "」";
        return switch (kind == null ? KIND_USER : kind) {
            case KIND_SCHEDULED -> "定时任务" + n;
            case KIND_WORKFLOW -> "工作流子智能体" + n;
            default -> "会话" + n + "的一轮对话";
        };
    }
}
