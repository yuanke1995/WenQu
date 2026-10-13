package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.SubAgentTools;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能体咨询族（P5）。
 * <p>
 * 两条本族特有的装配判据：
 * <ul>
 *   <li><b>分支轮不挂</b>：被咨询方自己那一支里再挂咨询工具，就是乘数扇出——一支派三支、每支再各派，
 *       深度硬顶拦得住无限递归却拦不住"合法的一层宽扇出"把成本乘上去。分支要做的事是把手上这一件
 *       事办完，不是把父轮的分工再猜一遍（与 {@code RagService} 里"分支轮跳过隐式多视角编排"同一条理由）。</li>
 *   <li><b>游客不可见</b>：免登录分享会话保留的是"这个被发布的智能体自己会答"的语义，
 *       让它经咨询读到你私有的库与助手，等于把分享链接变成读别人资料的入口。</li>
 * </ul>
 *
 * @author yuanke
 */
@Component
public class SubAgentToolProvider implements ToolFamilyProvider {

    private final SubAgentTools tools = new SubAgentTools();

    @Override
    public ToolFamily family() {
        return ToolFamily.SUBAGENT;
    }

    @Override
    public Object holder() {
        return tools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(new ToolDescriptor("consultAgent", ToolFamily.SUBAGENT,
                "智能体咨询", "问另一位助手", "把这一小问交给职责对口的助手去查，它带着自己的知识库把结论与资料交回来",
                SideEffect.MUTATE, "tool.subagentConsult.enabled", false, true, null));
    }

    @Override
    public boolean exposes(ToolDescriptor d, ToolMountContext mc) {
        return !mc.branchTurn() && ToolFamilyProvider.super.exposes(d, mc);
    }

    @Override
    public List<org.springframework.ai.tool.ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> exposes(d, mc));
    }
}
