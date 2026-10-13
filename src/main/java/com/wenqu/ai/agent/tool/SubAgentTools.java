package com.wenqu.ai.agent.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 智能体咨询工具（P5 agent-as-tool）。
 * <p>
 * 它要做的事是过去那套「自动派遣 + 会话锁定」做的事——按问题挑合适的资料域——但换成模型在生成循环里
 * 自己决定：调不调、调哪几个、调几次。层级也从「换哪个角色」降到「查哪块资料」：回答者始终是
 * 本轮那个助手，被咨询方只交回自己查到的东西与专业视角，不改人设、不改权限档。
 * <p>
 * <b>可咨询清单不写进工具描述</b>，而是走系统提示的「可咨询助手」能力段：{@code @Tool} 的描述是编译期
 * 常量，而清单是本轮的（按使用者可读范围算），且这条仓库早有约定——工具挂不挂与提示词教不教都问
 * {@link ToolFamilyProvider#exposes}，两处同源（见 {@code ToolFamily} 类注释与问答侧的能力分工段）。
 * 工具描述里只教「id 从清单那一节取」。
 * <p>
 * 上下文注入沿用本仓 P1 的口径：执行器与本轮台账都装进 {@link ToolContext}，一次调用独占一份，
 * 不留跨调用的可变状态（老写法是类级 ThreadLocal，池化线程漏清就把上一轮的台账留给下一个会话）。
 *
 * @author yuanke
 */
public class SubAgentTools {

    /** toolContext 键：本轮的咨询执行器（值为 {@link ConsultFn}） */
    public static final String CTX_CONSULT = "wenqu.subagent.consult";

    /**
     * 本轮咨询执行器。由问答侧按轮注入，实现方负责预算、去重与回报转文本。
     * <p>返回给模型的文本已经是「可以直接抄进答案」的形态：含回报正文与引用编号提示；
     * 失败与超限也返回文本而不是抛异常（与 {@code ToolCallback.call} 只抛运行时异常同口径，
     * 一次咨询失败不该把整轮问答打断）。
     */
    @FunctionalInterface
    public interface ConsultFn {
        String consult(String agentId, String question);
    }

    private static ConsultFn consultOf(ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get(CTX_CONSULT);
        return v instanceof ConsultFn f ? f : null;
    }

    /**
     * @Tool 描述刻意不提具体智能体名字：清单每轮随使用者变化，写死任何一位都会在别的租户身上说谎。
     */
    @Tool(description = "咨询另一位智能体：它带着自己那套知识库与专业视角，把问题查一遍再把结论与资料交回给你。"
            + "当问题落在某位助手的职责范围内、而你手上没有对应资料时调用；可以调用多次、每次咨询不同的助手。"
            + "可咨询的助手清单（含 id 与职责范围）见系统提示里的「可咨询助手」一节，agentId 必须逐字取自那份清单，不要猜。"
            + "通用问题（常识、写作、建议）不要咨询——没有对应助手，直接自己答。")
    public String consultAgent(
            @ToolParam(description = "要咨询的助手 id，逐字取自「可咨询助手」清单") String agentId,
            @ToolParam(description = "派它去做什么：写清楚要查的对象与想要的结论，可以是原问题，也可以是你拆解后的一小问") String question,
            ToolContext toolContext) {
        ConsultFn fn = consultOf(toolContext);
        if (fn == null) {
            // 装配与执行之间断了线（工具挂上了却没注入执行器）：如实告诉模型，别让它以为"查无此事"
            return "咨询通道本轮未就绪，无法咨询其他助手。请基于已有资料回答，并说明这一部分没能核实。";
        }
        return fn.consult(agentId, question);
    }
}
