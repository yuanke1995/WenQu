package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.BuiltinTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置工具族：只剩 askUser（人在回路）。
 * <p>
 * 2026-11-06 物理退役了纯计算 10 把——那些能力模型原生具备，常驻只占 schema；
 * 本族保留 askUser，因为它是「人在回路」的真实产品能力，不是模型能自给的。
 * <p>
 * guestAllowed=true 照旧：免登录分享会话仍把内置项留给访客（配合知识检索）。
 * askUser 的 sideEffect 记为 PURE 是有意的——它不改变世界，只是把人拉回这一轮；
 * 它的阻塞性由人在回路那套机制（超时不代答、跨设备恢复）管，不该混进副作用分级里。
 */
@Component
public class AskUserToolProvider implements ToolFamilyProvider {

    private final BuiltinTools builtinTools;

    public AskUserToolProvider(BuiltinTools builtinTools) {
        this.builtinTools = builtinTools;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.ASK_USER;
    }

    @Override
    public Object holder() {
        return builtinTools;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                // 提问卡是「人在回路」那一件：分支轮（P3a）没有观众，等不到答复，故带 viewerRequired
                desc("askUser", "向用户提问（人在回路）", "向用户提问", "向你提出问题（可多选的题会标出），点选或输入答案后继续回答", true));
    }

    private static ToolDescriptor desc(String name, String label, String cardLabel, String cardHint,
                                       boolean viewerRequired) {
        return new ToolDescriptor(name, ToolFamily.ASK_USER, label, cardLabel, cardHint,
                SideEffect.PURE, null, true, false, null, viewerRequired);
    }

    /**
     * 两道闸都要过：工具总闸 + 智能体三态；再叠智能体的「具体项」白名单
     * （agent.builtinTools：null=全要，否则只挂选中的那几个）。
     * <p>askUser 也在白名单里跟着走——这条判据同时被提示词的「人机协同三入口」段查询：
     * 提问卡没被选中时那一行不该写给模型，否则模型会对着不存在的工具连环空转。
     */
    @Override
    public boolean exposes(ToolDescriptor d, ToolMountContext mc) {
        return ToolFamilyProvider.super.exposes(d, mc) && mc.selectedByAgent(family(), d.name());
    }

    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> exposes(d, mc));
    }
}
