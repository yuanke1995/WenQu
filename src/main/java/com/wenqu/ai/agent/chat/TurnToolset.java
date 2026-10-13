package com.wenqu.ai.agent.chat;

import com.wenqu.ai.agent.permission.ExecutionPolicy;
import com.wenqu.ai.agent.subagent.SubAgentConsultService;
import org.springframework.ai.tool.ToolCallback;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 本轮的<b>工具表与档位</b>（P6 刀 0 第二步自 {@link TurnState} 的平铺字段下沉）。
 *
 * <p><b>为什么单独一簇</b>：这一族的全部内容是「本轮到底能用什么、以什么档位用」——由装配侧
 * （{@code enabledToolCallbacks}/{@code applyPermissionTier}）写入，由守卫侧与提示词侧读取。
 * 刀 1 之后它在 P1 的 seam 上已经有了自己的上下文对象（{@code ToolMountContext}），但那是<b>判据</b>的入参，
 * 装配结果仍要落回轮次状态；把它们和引用池、正文缓冲混在一个袋里，就看不出这一族才是"权限面"。
 *
 * <p>两条口径别在改动中丢：
 * <ul>
 *   <li>{@link #permissionPolicy} 与 {@link #toolApprovalMode} 是<b>开轮解析一次</b>的结果，
 *       装配与守卫必须读同一份——两份各算各的就会出现「挂着的被拦、没挂的反能跑」；</li>
 *   <li>{@link #dsmlToolNamesLc} 只认本轮真实下发的工具名，按通用 XML 规则剥会吃掉模型正常写的
 *       HTML/XML 正文。</li>
 * </ul>
 *
 * @author yuanke
 */
public final class TurnToolset {

    /** 本轮"有副作用"工具名单（沙盒/MCP，装配时回填）：审批模式 ask 时执行前需确认 */
    public volatile Set<String> sensitiveToolNames = Set.of();
    /** 本轮接入的 MCP 工具名单（装配时回填）：结果注册引用来源用 */
    public volatile Set<String> mcpToolNames = Set.of();
    /**
     * 本轮生效工具名（小写）：剥离模型误吐的 DSML 工具调用文本的白名单。
     * 只认本轮真实下发的工具名——按通用 XML 规则剥会把模型正常写的 HTML/XML 正文
     * （技术问答里代码块尤其常见）当噪声吃掉，白名单匹配则天然不误伤。
     */
    public volatile Set<String> dsmlToolNamesLc = Set.of();
    /** DSML 文本剥离是否已提示过降级（同一轮只提示一次，避免刷屏） */
    public volatile boolean dsmlStrippedNoted;
    /** 精确检索工具的检索范围（与主链路同库界，工具执行线程内生效）：kbIds 限定库，docIds 后过滤命中 */
    public volatile Collection<String> toolScopeKbIds;
    public volatile Set<String> toolScopeDocIds;
    /**
     * 本轮权限档位策略（P2，开轮解析一次）：装配侧与守卫侧共用这一份。
     * <p>刻意不在两处各算各的：档位禁用某工具时，装配要让它从模型的工具表里消失，守卫要拦住绕过
     * 装配的调用——两份判据一旦漂移，就会出现「挂着的被拦、没挂的反能跑」。
     */
    public volatile ExecutionPolicy permissionPolicy;
    /** 工具执行审批模式（本轮生效值：智能体显式设置优先，未设置落到权限档位的建议值；null=auto） */
    public volatile String toolApprovalMode;
    /** 单轮工具调用步数上限（agent.maxToolSteps > 全局 agent.maxToolSteps；<=0 不限制）；已执行步数 */
    public volatile int maxToolSteps;
    public final java.util.concurrent.atomic.AtomicInteger toolStepCount =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * 本轮可被沙盒脚本子调用的工具（P3b）：名字 → <b>已过守卫链包装</b>的回调。
     * <p>只在挂了 run_code 时构建；null = 本轮没有这条通道（脚本里的回调一律拒）。
     * 用 Map 而不是列表：容器侧按工具名找函数，名字重复时后挂的覆盖前面的比"两个都能调"清楚。
     */
    public volatile Map<String, ToolCallback> subCallTools;
    /**
     * 已装配的工具回调缓存（计划模式闸门先行装配时写入）：buildAnswerStream 直接复用，
     * 避免 MCP 工具集二次装配（外部 server 连接不重复建立）。null=未缓存，走原路径。
     */
    public volatile java.util.List<ToolCallback> enabledCallbacks;
    /**
     * 本轮的智能体咨询上下文（P5 agent-as-tool）：与 {@link #maxToolSteps} 同一时刻记下——
     * 那里是 agent 与 subagentDepth 同时在手上的最后一站，工具在弹性线程上跑，拿不到这两个局部量。
     */
    public volatile SubAgentConsultService.Ctx consultCtx;
    /**
     * 本轮咨询台账（去重 + 每轮上限）。构造第一件事就建，不能懒建：工具回调并发执行，
     * 懒建会造出两份台账，于是"同一位助手只咨询一次"在并发下不成立。
     */
    public final SubAgentConsultService.Ledger consultLedger;

    public TurnToolset(SubAgentConsultService consult) {
        // 台账在建工具面第一件事就建：工具回调并发执行，懒建会造出两份，
        // 「同一位助手只咨询一次」这条规则在并发下就不成立
        this.consultLedger = consult.newLedger();
    }
}
