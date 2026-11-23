package com.wenqu.ai.agentx.team;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 团队依赖 shim 的描述符（工具清单/卡片中文名的单一真源）。
 * <p>holder() 返回 null：实际工具是按会话 new 出来的 {@code TeamTaskDependTool}（要当轮 slot），
 * 不走单例 Bean，启动期反射核对因此跳过。
 *
 * @author yuanke
 */
@Component
public class TeamDependToolProvider implements ToolFamilyProvider {

    @Override
    public ToolFamily family() {
        return ToolFamily.TEAM;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(
                new ToolDescriptor("team_task_depend", ToolFamily.TEAM,
                        "团队任务依赖", "登记任务依赖", "登记两个任务的先后依赖（前置完成，后继才可认领）",
                        SideEffect.MUTATE, null, false, false));
    }
}
