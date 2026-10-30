package com.wenqu.ai.agentx;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * SYSTEM 取景框：把<b>本轮真实下发</b>给模型的系统提示词登记到会话槽位，供容量面板切出「系统提示词」一段。
 *
 * <p><b>为什么必须从请求侧取</b>：容量面板的分类切的是 {@code session.transcript()}，而
 * {@code SessionViews.transcript()} 只回放 {@code message/user}、{@code message/assistant}、
 * {@code tool/result} 三类事件——<b>系统提示词根本不在表里</b>。它却是这轮上下文里最大的一段
 * （真机实测一条智能体：请求里那条 SYSTEM 共 26,608 字 ≈ 8,861 tokens，占本轮 prompt 14,358 的六成）。
 * 缺了这一段，{@code FrameworkCtxParts} 的等比校准会把这块缺口按倍数摊给其余各段，
 * 舍入残差还落进最大那段——「工具声明」被虚报成 13,525，而它真实只有 5,708。
 * 任何人拿容量条判断「该不该裁工具」都会被这个假数带偏，所以这一段必须补上。
 *
 * <p><b>为什么挂在 {@code onModelCall}</b>：{@link ModelCallInput#messages()} 是唯一装着
 * <b>装配完成后</b>那份 SYSTEM 的位置。智能体自己那份 {@code sysPrompt} 只是它的开头一段——
 * 框架的技能目录、子智能体与异步任务指引、工作区与记忆上下文都是中间件在临发前拼进去的，
 * 按问渠手里那截字符串算只会少算，与 {@link FrameworkCtxParts} 类注释立的规矩相反
 * （猜出来的数字进容量条，比不显示更坏）。
 *
 * <p><b>为什么不另建一份缓存</b>：槽位本来就按会话一个、随实例常驻（见 {@link AgentxTurnSlot}），
 * 每次模型调用覆盖一份引用——最后一调的那份正是 {@code usageOf} 校准所依据的那一轮输入。
 * 这里只搬 {@code Msg} 引用、不复制文本，也不碰事件流：取景失败顶多少一段，不该打死这一轮。
 */
public final class AgentxSystemCaptureMiddleware implements MiddlewareBase {

    private final AgentxTurnSlot slot;

    public AgentxSystemCaptureMiddleware(AgentxTurnSlot slot) {
        this.slot = slot;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        slot.noteSystemMessages(systemOnly(input == null ? null : input.messages()));
        return next.apply(input);
    }

    /** 请求消息里的 SYSTEM 条目（通常一条；框架若分段就是多条，全收）。 */
    private static List<Msg> systemOnly(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Msg> out = null;
        for (Msg m : messages) {
            if (m != null && m.getRole() == MsgRole.SYSTEM) {
                if (out == null) out = new ArrayList<>(2);
                out.add(m);
            }
        }
        return out == null ? List.of() : List.copyOf(out);
    }
}
