package com.wenqu.ai.service;

import com.wenqu.ai.agent.chat.ChatCommand;
import com.wenqu.ai.agentx.WenquAgentxTools;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.TaskContextState;
import io.agentscope.harness.agent.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务清单的存储桥：<b>问渠的护栏 + 框架的 state</b>。
 *
 * <p>问渠的清单原来自己落 {@code c_ai_session.todos}。单引擎化之后不这么做了 ——
 * 清单的权威状态该由框架持有（{@code todo_write} 工具与 {@code TaskReminderMiddleware}
 * 每轮重述都围绕 {@code AgentState.tasksContext}），问渠再存一份就是两个真值源，
 * 迟早对不上（模型读到框架那份、前端显示问渠那份，用户看到的进度和模型以为的进度不是一回事）。
 *
 * <p>本类只做翻译，不做存储：
 * <ul>
 *   <li><b>写入</b>：问渠归一后的条目 → 框架 {@link Task} 列表 → {@code TaskContextState#replaceTasks}。
 *       状态词映射 {@code doing→in_progress}、{@code done→completed}
 *       （问渠用前者、框架用后者，语义同、词不同）；</li>
 *   <li><b>读出</b>：{@code session.inspect().restore().getTasksContext()} → 问渠的清单文档 JSON，
 *       供 REST 端点与前端回显——前端契约一个字没改。</li>
 * </ul>
 *
 * <p><b>为什么保留问渠的护栏</b>（≤N 项、单项截断、同时只允许一项 doing）：框架的
 * {@code todo_write} 不管这些，而清单卡是用户直接看见的界面。模型一次写二十项、
 * 把单项写成一段话、同时标三项 doing，在框架那边都是"合法"的，在用户那边是一张没法看的卡。
 * 护栏留在问渠这一侧，是因为它是<b>呈现约束</b>而不是存储约束。
 */
@Service
public class TodoBridge {

    private static final Logger log = LoggerFactory.getLogger(TodoBridge.class);

    /** 写入结果：失败时带一句可回给模型的原因（工具报错会让模型反复重试，所以要能救的救、说清的说清） */
    public record WriteResult(boolean ok, String reason) {
        static WriteResult good() {
            return new WriteResult(true, null);
        }

        static WriteResult bad(String reason) {
            return new WriteResult(false, reason);
        }
    }

    /**
     * 整表替换本会话的任务清单。
     *
     * @param items 已由 {@link TodoService#normalize} 归一过的条目（≤N 项、状态合法、单项 doing 至多一项）
     */
    public WriteResult replace(WenquAgentxTools.TurnHooks hooks, List<TodoService.TodoItem> items) {
        if (hooks == null) return WriteResult.bad("本轮未启用任务清单");
        AgentSession session = hooks.agentSession();
        if (session == null) return WriteResult.bad("会话实例不可用");
        try {
            AgentState state = restoreState(session);
            if (state == null) return WriteResult.bad("会话状态读不出来");
            TaskContextState ctx = state.getTasksContext();
            TaskContextState snapshot = ctx.snapshot();
            List<Task> rebuilt = new ArrayList<>(items.size());
            // 按内容匹配上一轮的 task，保留 id/createdAt：整表重写不该让标识符每轮都变
            Map<String, Task> byContent = new LinkedHashMap<>();
            for (Task t : snapshot.getTasks()) {
                if (t.getSubject() != null) byContent.putIfAbsent(t.getSubject(), t);
            }
            for (TodoService.TodoItem item : items) {
                Task.State state1 = toFrameworkState(item.status());
                Task prior = byContent.get(item.content());
                Task.Builder b = Task.builder()
                        .subject(item.content())
                        .description(item.content())
                        .state(state1);
                if (prior != null) b.id(prior.getId()).createdAt(prior.getCreatedAt());
                rebuilt.add(b.build());
            }
            // 乐观锁：expectedRevision 用刚读到的快照版本，冲突说明这一轮里被别处改过
            ctx.replaceTasks(rebuilt, snapshot.getRevision());
            log.info("[TODO] 清单已写入框架状态: 会话={} 共 {} 项（进行中 {}）", hooks.sessionId(), rebuilt.size(),
                    rebuilt.stream().filter(t -> t.getState() == Task.State.IN_PROGRESS).count());
            return WriteResult.good();
        } catch (Exception e) {
            log.warn("[TODO] 清单写入框架状态失败: session={}, {}", hooks.sessionId(), e.toString());
            return WriteResult.bad(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 组装清单文档 JSON：<b>以刚落进框架 state 的那份为真源读回来</b>，不是拿传入的 items 再拼一次。
     * <p>为什么要绕这一圈：items 是问渠归一后的结果，state 是框架最终接受的结果。两者理论上
     * 一致（护栏在写之前就拦过了），但一旦框架侧再做任何归一（截断、排序、补 id），发给前端的
     * 就会和模型下一轮看到的不一致。读回来是唯一能让「用户看到的」与「模型以为的」同源的办法。
     *
     * @param turnId 清单所属轮次（前端按它标「哪一轮写的」）
     * @return null=写进去的清单读不回来（此时宁可不发，前端保留上一份，别让清单卡整块消失）
     */
    public String docJson(AgentSession session, List<TodoService.TodoItem> fallback, String turnId) {
        List<Task> tasks = readTasks(session);
        if (tasks == null) tasks = toTasks(fallback);
        if (tasks.isEmpty()) return null;
        List<Map<String, Object>> rows = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("content", t.getSubject());
            row.put("status", toWenquStatus(t.getState()));
            rows.add(row);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("turnId", turnId == null ? "" : turnId);
        doc.put("items", rows);
        doc.put("updatedAt", java.time.LocalDateTime.now().withNano(0).toString());
        return com.alibaba.fastjson.JSON.toJSONString(doc);
    }

    /** 读框架 state 里的任务列表；读不到返回 null（与「读到了但是空的」要分得开） */
    private List<Task> readTasks(AgentSession session) {
        if (session == null) return null;
        try {
            AgentState state = restoreState(session);
            if (state == null) return null;
            List<Task> tasks = state.getTasksContext().getTasks();
            return tasks == null ? List.of() : tasks;
        } catch (Exception e) {
            log.warn("[TODO] 清单回读失败: {}", e.toString());
            return null;
        }
    }

    /** 问渠条目 → 框架 Task（不含「保留上一轮 id」那步，那是写入路径的事，这里只做纯转换） */
    private static List<Task> toTasks(List<TodoService.TodoItem> items) {
        List<Task> out = new ArrayList<>();
        if (items == null) return out;
        for (TodoService.TodoItem i : items) {
            if (i == null || i.content() == null || i.content().isBlank()) continue;
            out.add(Task.builder()
                    .subject(i.content())
                    .description(i.content())
                    .state(toFrameworkState(i.status()))
                    .build());
        }
        return out;
    }

    /** 框架 state 是从会话投影里 restore 出来的（框架没有对外暴露活的对象） */
    private AgentState restoreState(AgentSession session) {
        SessionProjection projection = session.inspect();
        return projection == null ? null : projection.restore();
    }

    /** 问渠状态词 → 框架状态枚举（doing→in_progress、done→completed；其余归 pending） */
    static Task.State toFrameworkState(String wenquStatus) {
        String s = wenquStatus == null ? "" : wenquStatus.strip().toLowerCase();
        return switch (s) {
            case "doing", "in_progress" -> Task.State.IN_PROGRESS;
            case "done", "completed" -> Task.State.COMPLETED;
            default -> Task.State.PENDING;
        };
    }

    /** 框架状态枚举 → 问渠状态词（前端右栏那份契约一个字没改） */
    static String toWenquStatus(Task.State state) {
        if (state == null) return "pending";
        return switch (state) {
            case IN_PROGRESS -> "doing";
            case COMPLETED -> "done";
            default -> "pending";
        };
    }
}
