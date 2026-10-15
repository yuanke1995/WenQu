package com.wenqu.ai.service;

import io.agentscope.core.state.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务清单桥（问渠护栏 → 框架 state）的断言。
 * <p>盯住两件最容易坏的事：①<b>状态词映射不能反</b>——问渠用 doing/done、框架用
 * in_progress/completed，映射写反的表现是「模型标了完成，右栏显示还在做」，而右栏是用户直接看的；
 * ②<b>护栏不能因为换了存储就丢</b>——≤N 项、单项截断、同时只允许一项 doing，这些框架不管，
 * 丢了就是一张用户没法看的卡。
 */
@DisplayName("任务清单：问渠护栏 ↔ 框架 AgentState 桥接")
class TodoBridgeTest {

    @Test
    void 问渠状态词与框架状态词一一对应() {
        // 映射反了的后果：模型标 done，右栏显示 doing（进度条永远卡在中间）
        assertEquals(Task.State.IN_PROGRESS, TodoBridge.toFrameworkState("doing"));
        assertEquals(Task.State.COMPLETED, TodoBridge.toFrameworkState("done"));
        assertEquals(Task.State.PENDING, TodoBridge.toFrameworkState("pending"));
    }

    @Test
    void 回读时状态词转回问渠那套() {
        // 前端契约一个字没改：右栏认 doing/done，不认 in_progress/completed
        assertEquals("doing", TodoBridge.toWenquStatus(Task.State.IN_PROGRESS));
        assertEquals("done", TodoBridge.toWenquStatus(Task.State.COMPLETED));
        assertEquals("pending", TodoBridge.toWenquStatus(Task.State.PENDING));
        assertEquals("pending", TodoBridge.toWenquStatus(null));
    }

    @Test
    void 认不出的状态词按待办处理而不是抛异常() {
        // 模型偶尔会自造状态词（"doinging"/"in progress"/空串）。抛异常会让工具报错、模型反复重试；
        // 归一到 pending 才是能救的那条路（框架的 fromWire 反而是抛 IllegalArgumentException 的）
        assertEquals(Task.State.PENDING, TodoBridge.toFrameworkState("doinging"));
        assertEquals(Task.State.PENDING, TodoBridge.toFrameworkState("in progress"));
        assertEquals(Task.State.PENDING, TodoBridge.toFrameworkState(null));
        assertEquals(Task.State.PENDING, TodoBridge.toFrameworkState(""));
    }

    @Test
    void 护栏仍在问渠这一侧生效() {
        TodoService svc = new TodoService(null);
        // ① 同时三项 doing → 只留第一项，其余降为 pending（框架不管这条）
        var n = svc.normalize(List.of(
                new TodoService.TodoItem("第一件", "doing"),
                new TodoService.TodoItem("第二件", "doing"),
                new TodoService.TodoItem("第三件", "doing")));
        assertTrue(n.ok());
        assertEquals(3, n.items().size());
        assertEquals(1, n.items().stream().filter(i -> i.status().equals("doing")).count(),
                "同时只允许一项 doing——框架的 todo_write 也会拒，但那时模型已经拿到报错在重试了；"
                        + "问渠这层要先把话说清（notes 里带解释）而不是让工具失败");
        assertFalse(n.notes().isEmpty(), "被改动的项要说出来，否则模型不知道为什么自己标的 doing 没了");

        // ② 不认的状态词 → 归一 + 出说明
        var n2 = svc.normalize(List.of(new TodoService.TodoItem("一件事", "运行中")));
        assertEquals("pending", n2.items().get(0).status());
        assertFalse(n2.notes().isEmpty());
    }

    @Test
    void 空清单不通过() {
        TodoService svc = new TodoService(null);
        assertFalse(svc.normalize(List.of()).ok(), "空清单不能写进去（要收尾就把每项标 done 后整表传回）");
        assertFalse(svc.normalize(List.of(new TodoService.TodoItem("  ", "pending"))).ok(),
                "只有空白内容的条目等同于没有条目");
    }

    @Test
    void 写进活状态就要能从同一份读回来() {
        // 盯住真机踩过的那次：写入落在 session.inspect() 重放出来的只读快照上，改完即丢，
        // 于是每轮都报「清单已写入但回读失败」。这条断言只在「写的那份==读的那份」时成立
        var state = io.agentscope.core.state.AgentState.builder().build();
        var items = List.of(new TodoService.TodoItem("核对共享范围", "doing"),
                new TodoService.TodoItem("写手册章节", "pending"));
        var written = new TodoBridge().replace(state, "session-1", items);
        assertTrue(written.ok(), "写入失败会把原因带回模型：" + written.reason());
        assertEquals(2, state.getTasksContext().getTasks().size());

        String doc = new TodoBridge().docJson(state, items, "turn-1");
        assertNotNull(doc);
        assertTrue(doc.contains("\"turnId\":\"turn-1\""), "前端按 turnId 标「哪一轮写的」");
        assertTrue(doc.contains("核对共享范围") && doc.contains("\"doing\""),
                "右栏认 doing/done，不认 in_progress/completed：" + doc);
        assertTrue(doc.contains("写手册章节") && doc.contains("\"pending\""));
    }

    @Test
    void 整表重写保留条目标识只换状态() {
        var bridge = new TodoBridge();
        var state = io.agentscope.core.state.AgentState.builder().build();
        bridge.replace(state, "session-1", List.of(new TodoService.TodoItem("第一件事", "pending"),
                new TodoService.TodoItem("第二件事", "pending")));
        var first = state.getTasksContext().getTasks();
        bridge.replace(state, "session-1", List.of(new TodoService.TodoItem("第一件事", "done"),
                new TodoService.TodoItem("第二件事", "doing")));
        var second = state.getTasksContext().getTasks();
        assertEquals(first.stream().map(io.agentscope.core.state.Task::getId).toList(),
                second.stream().map(io.agentscope.core.state.Task::getId).toList(),
                "标识每轮都变就等于每次重写都在建新条目，框架侧的引用与留痕全对不上");
    }

    @Test
    void 没有活状态就明确失败而不是写进快照() {
        // 拿不到本轮状态（工具上下文没带）时，报错是唯一正确的行为：写投影那份是静默丢失
        var bad = new TodoBridge().replace(null, "session-1",
                List.of(new TodoService.TodoItem("一件事", "pending")));
        assertFalse(bad.ok());
        assertFalse(bad.reason().isBlank(), "原因要能回给模型，它才知道这一轮没记上");
        assertNull(new TodoBridge().docJson((io.agentscope.core.state.AgentState) null, List.of(), "t"));
    }
}
