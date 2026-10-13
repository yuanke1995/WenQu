package com.wenqu.ai.agent.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.Map;

/**
 * 一轮问答交给工具执行层的契约（P1 seam 的 Consumer 侧）。
 * <p>
 * 为什么是接口而不是一句「把 TurnState 传进去」：TurnState 是本轮的可变状态袋（P6 刀 0 才从 ChatService
 *   的私有内部类提成顶层类），工具层不该因为「想读一个本轮的事实」就依赖它的类型，
 * 86 个字段、什么都有。守卫链一旦认识它，就再也拿不出问答流水线之外——工作流节点与技能那两条
 * 现在完全绕过守卫的旁路（{@code WorkflowEngine.mcpBody}/{@code skillBody}）会永远接不上来。
 * 本接口只声明工具执行层真正需要的十余件事，其余本轮状态留在问答侧自己管。
 * <p>
 * 生命周期：一轮一个。实现方（当前的 {@link com.wenqu.ai.agent.chat.TurnState}）不许把它存进静态字段
 * 或跨轮复用——这一条正是本次迁移要修掉的老写法。
 *
 * @author yuanke
 */
public interface ToolTurnContext {

    // ==================== 归属 ====================

    String sessionId();

    String userId();

    /** 本轮 id（工具步要按轮写进事件账本，见 SessionEvent） */
    String turnId();

    // ==================== 预算与终止 ====================

    /** 用户是否已叫停本轮（叫停后一步工具都不该再执行，副作用工具尤其不能放行） */
    boolean userStopped();

    /** 本轮工具调用步数上限，<=0 表示不限 */
    int maxToolSteps();

    /** 本轮已用步数 */
    int stepsTaken();

    /** 占用一步（记账；由预算守卫在放行时调用，与迁移前「过闸即计数」的时序一致） */
    void takeStep();

    // ==================== 审批 ====================

    /** 本轮审批模式：auto / ask / off（取自智能体配置） */
    String approvalMode();

    /** 该工具本轮是否需要用户批准（有副作用工具名单；由注册中心判定后落到本轮快照） */
    boolean requiresApproval(String toolName);

    /** 审批等待上限（毫秒） */
    long approvalTimeoutMs();

    /** 审批通道；null = 本轮没有人可应答（后台续跑、工作流节点、定时任务） */
    ToolApprover approver();

    // ==================== 权限档位 ====================

    /**
     * 本轮生效的权限档位（P2）。默认 null = 本轮无档位判定，守卫不做档位裁剪。
     * <p>与装配侧 {@link ToolMountContext#permissionPolicy()} 必须是<b>同一份</b>对象：档位禁用的工具
     * 在工具表里消失，若执行侧另算一份，就会出现「挂着的被拦、没挂的反而能跑」这种最难查的错配。
     */
    default com.wenqu.ai.agent.permission.ExecutionPolicy permissionPolicy() {
        return null;
    }

    // ==================== 上下文注入 ====================

    /** 本轮已装配的基础工具上下文（会话/用户归属、askUser 执行器、任务清单写入器、大输出外溢） */
    Map<String, Object> baseToolContext();

    /** 按族的额外注入（知识检索/联网搜索/沙盒各一条），管线在每次调用前合并进上下文 */
    Map<ToolFamily, ToolEnrichment> enrichments();

    // ==================== 执行与观测 ====================

    /**
     * 真正执行工具回调。问答侧的实现负责带上身份快照与自动重试（工具回调跑在弹性线程上，
     * RequestUser 那个 ThreadLocal 到不了，必须显式把入口线程捕获的快照带过去）。
     * <p>
     * 不声明 throws：与 {@code ToolCallback.call} 同口径，只抛运行时异常。包装 checked 会逼着
     * 上层给一个永远不会来的 catch 分支，也破坏工具回调原有的 precise-rethrow。
     *
     * @param attemptsOut 单元素数组，回填实际尝试次数（供观测层显示「重试 N 次」）
     */
    String execute(ToolCallback raw, String arguments, Map<String, Object> context,
                   ToolInvocation inv, int[] attemptsOut);

    /** 记录一条工具状态（start / done / error）：SSE 卡片、done 汇总、落库与事件账本都在实现里 */
    void observe(ToolInvocation inv, String status, String resultOrError);

    /** 工具的流式输出增量（沙盒命令的逐行回显） */
    void onOutput(ToolInvocation inv, String delta);

    /**
     * 结果回给模型之前的加工：把 MCP 工具命中注册为引用来源（正文里的 [N] 才有对应条目）。
     * <b>在观测之前调用</b>——工具卡片要显示注册后的那份文本，与迁移前一致。
     */
    String decorateResult(ToolInvocation inv, String result);

    /**
     * 把用户中途插话附到本步结果尾部（<b>在观测之后</b>才附，否则卡片里会出现「用户插话」这种
     * 不属于工具输出的文本）。循环的消息列表由框架持有，工具返回值是我们唯一能干净影响
     * 下一轮输入的位置。附在尾部而不是开头——MCP 工具可能回严格 JSON，前置会把结构打断。
     */
    String appendSteers(ToolInvocation inv, String result);

}
