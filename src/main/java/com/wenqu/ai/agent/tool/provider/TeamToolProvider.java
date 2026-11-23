package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能体团队族：清单中文名与卡片展示名的单一真源。
 *
 * <p>holder() 返回 null：本族工具不是单例 Bean，而是按会话/轮次构造的 {@code TeamTools}
 * （它要捕获当轮 slot 才能拿到 root/session），由引擎在团队会话轮直接注册进 Toolkit。
 * 这里只提供描述符供工具清单页与卡片标签使用，启动期的反射核对因此跳过（见
 * {@link ToolFamilyProvider#holder()} 的 null 语义）。
 *
 * <p>没有 gateKey——团队是会话级开关（会话行 team_enabled），关闭时整族不挂；不进系统设置。
 *
 * @author yuanke
 */
@Component
public class TeamToolProvider implements ToolFamilyProvider {

    @Override
    public ToolFamily family() {
        return ToolFamily.TEAM;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                new ToolDescriptor("team_list", ToolFamily.TEAM,
                        "团队成员发现", "查看团队成员", "列出本会话团队的全部成员与在线状态",
                        SideEffect.READ, null, false, false),
                new ToolDescriptor("team_send", ToolFamily.TEAM,
                        "成员消息", "给成员发消息", "向团队另一位成员发消息或追加任务",
                        SideEffect.MUTATE, null, false, false),
                new ToolDescriptor("team_wait", ToolFamily.TEAM,
                        "成员消息等待", "等待成员消息", "等待团队 inbox 的下一条消息",
                        SideEffect.PURE, null, false, false, true),
                new ToolDescriptor("team_task_create", ToolFamily.TEAM,
                        "团队任务创建", "新建团队任务", "在团队任务板上建一个任务节点",
                        SideEffect.MUTATE, null, false, false),
                new ToolDescriptor("team_task_depend", ToolFamily.TEAM,
                        "团队任务依赖", "登记任务依赖", "登记两个任务的先后依赖（from→to）",
                        SideEffect.MUTATE, null, false, false),
                new ToolDescriptor("team_task_claim", ToolFamily.TEAM,
                        "团队任务认领", "认领团队任务", "认领一个就绪的团队任务",
                        SideEffect.MUTATE, null, false, false),
                new ToolDescriptor("team_task_complete", ToolFamily.TEAM,
                        "团队任务完成", "更新团队任务", "把任务记为完成或失败（失败不解锁下游）",
                        SideEffect.MUTATE, null, false, false),
                new ToolDescriptor("team_task_list", ToolFamily.TEAM,
                        "团队任务板", "查看团队任务", "查看团队任务板的节点、状态与依赖",
                        SideEffect.READ, null, false, false));
    }
}
