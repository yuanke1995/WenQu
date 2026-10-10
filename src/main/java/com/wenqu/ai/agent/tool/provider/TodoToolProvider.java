package com.wenqu.ai.agent.tool.provider;

import com.wenqu.ai.agent.tool.SideEffect;
import com.wenqu.ai.agent.tool.ToolDescriptor;
import com.wenqu.ai.agent.tool.ToolFamily;
import com.wenqu.ai.agent.tool.ToolFamilyProvider;
import com.wenqu.ai.service.TodoService;
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
                SideEffect.READ, "tool.todo.enabled", false, false, null));
    }
}
