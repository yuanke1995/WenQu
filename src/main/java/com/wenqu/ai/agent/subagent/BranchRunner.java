package com.wenqu.ai.agent.subagent;

/**
 * 全循环分支的执行器（P3a 的接缝）。
 * <p>
 * 为什么做成「父轮按轮回传的函数」而不是一个注入到编排器里的 Spring bean：编排器由
 * {@code ChatService} 构造注入，而分支执行要做的事就是再调一次 {@code ChatService.chat(...)}——
 * 把执行器做成 bean 就成了 ChatService ↔ 编排器 ↔ 执行器的三方循环依赖，只能靠
 * {@code ObjectProvider} 或字段注入解开，而那种解法把一次编译期可见的接错推迟成运行期的 null。
 * 传函数则连「这一轮才有父轮上下文」这件事一起传了：分支的归属用户、父会话、深度都来自当轮，
 * 放在单例 bean 上就变成又一份跨轮共享的 ThreadLocal 状态（P1 第一刀删掉的那三对就是这类东西）。
 * <p>
 * 实现方的契约（见 {@code SubAgentBranchRuntime#branchRunner}，P6 刀 3b 起簿记在那边）：
 * <ul>
 *   <li>用自己的临时会话跑完整问答管线，<b>不许</b>复用父会话——{@code chat()} 的轮级互斥是按会话的，
 *       借父会话会当场撞上父轮自己那一坑，表现成"派出去就失败"；</li>
 *   <li>任何异常/超时都在实现内收敛成 {@code status=failed|timeout} 的回报，不外抛；</li>
 *   <li>跑完必须收养产物、删临时会话、清通道登记（收集型通道不触发 onCompletion）。</li>
 * </ul>
 *
 * @author yuanke
 */
@FunctionalInterface
public interface BranchRunner {

    /** 跑一条分支；返回回报（永不返回 null） */
    BranchReport run(BranchRequest request);
}
