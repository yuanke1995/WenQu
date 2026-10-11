package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.agent.tool.ToolMountContext;
import com.wenqu.ai.service.TodoService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 任务清单族（writeTodo）。
 * <p>
 * 这一行是本次重构顺带补上的漏：工具上线时「清单页有英文名、没有中文名」——因为中文名当时手抄在
 * {@code ToolInventoryService.LABELS} 里，加新工具的人不知道要同步。声明式提供方 + 启动期反射核对
 * 之后，加了 @Tool 方法却没声明描述符会当场记 ERROR，漏项不再靠人记得住。
 */
@Component
public class TodoToolProvider implements ToolFamilyProvider {

    private final TodoService todoService;

    public TodoToolProvider(TodoService todoService) {
        this.todoService = todoService;
    }

    @Override
    public ToolFamily family() {
        return ToolFamily.TODO;
    }

    @Override
    public Object holder() {
        return todoService;
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return List.of(new ToolDescriptor("writeTodo", ToolFamily.TODO,
                "任务清单", "整理任务清单", "把要做的事列成清单，看得见总共几件、还剩几件",
                SideEffect.READ, "tool.todo.enabled", false, false, null,
                // viewerRequired=true：清单是给正在看这一轮的人看的进度板。子智能体分支（P3a）跑在
                // 一条没有观众、跑完即删的临时会话里，写进去的清单谁都看不到，故分支轮不挂——
                // 判据落在描述符字段上而不是装配处再抄一个 if（见 ToolDescriptor#viewerRequired）
                true));
    }

    /**
     * 没有智能体级三态（要加 c_ai_agent 列），只有全局一档；对匿名访客不挂由描述符
     * {@code guestAllowed=false} 拦住。
     * <p>这条判据同时被提示词的「人机协同三入口」段查询（清单卡那一行写不写），
     * 所以问答侧不再自己抄一份 {@code todoToolExposed}。
     */
    @Override
    public List<ToolCallback> callbacks(ToolMountContext mc) {
        return mountFiltered(mc, d -> exposes(d, mc));
    }
}
