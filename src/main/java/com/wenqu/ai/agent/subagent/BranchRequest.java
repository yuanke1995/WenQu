package com.wenqu.ai.agent.subagent;

/**
 * 一次全循环分支的执行请求（父轮交给分支执行器的那份入参）。
 * <p>
 * 为什么是一份记录而不是八个位置参数：分支执行的入参里全是同类型字符串（父会话 id / 子智能体 id /
 * 用户问题 / 生效模型引用），写错位置编译器不拦——而这一轮跑在并行线程上，写错的表现是
 * 「子智能体拿着父会话的 id 去问自己的问题」，只在并发时偶发、事后极难归因。这正是 P1 把
 * {@code chat()} 收成 {@code ChatCommand} 的同一个理由，在分支侧的复现。
 * <p>
 * {@code parentBrief} 与 {@code depth} 是 P3a 相对原「检索 + 要点提炼」分支新增的两件事：前者让分支
 * 继承父轮上下文（对标 DeepSeek Harness 的 fork-in-process），后者让平台能拦住无限套娃，
 * 并让每层预算随深度递减。
 *
 * @param parentSessionId 派出这条分支的父会话（日志关联与产物收养的目标）
 * @param parentTurnId    父轮 id（同一轮的几条分支在账本上归到一起）
 * @param childAgentId    子智能体 id（分支按它自己的配置跑）
 * @param childAgentName  子智能体名称（回报段展示用）
 * @param question        派它去干什么（委派模式下就是原问题，差异体现在各自的知识库范围与角色视角）
 * @param parentBrief     父轮上下文摘要（问题 + 最近若干轮对话；预算已按深度裁剪；可为空串）
 * @param depth           这一支处于第几层（1 = 父轮的直接分支）
 * @param userId          归属用户：可见性、个人资产（技能/MCP）、成本台账都按他
 * @param modelRef        父轮本轮生效的模型引用（分支沿用同一套模型，不重新裁决）
 * @param guestMode       免登录分享会话：分支继承父轮的收窄口径
 * @param timeoutMs       这一支的等待上限（超时按失败处理，不拖垮父轮）
 * @param branchIndex     这一支在编排视图里的序号（父轮卡片靠它把实时步骤归到正确那一行）
 * @author yuanke
 */
public record BranchRequest(String parentSessionId, String parentTurnId, String childAgentId,
                            String childAgentName, String question, String parentBrief, int depth,
                            String userId, String modelRef, boolean guestMode, long timeoutMs,
                            int branchIndex) {

    /** 日志与异常信息里的一行身份（哪个父会话的第几层哪一支） */
    public String describe() {
        return "branch[" + childAgentName + " @" + depth + "] parent=" + parentSessionId;
    }
}
